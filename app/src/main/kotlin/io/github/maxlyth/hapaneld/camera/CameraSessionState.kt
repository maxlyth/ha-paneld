package io.github.maxlyth.hapaneld.camera

import java.util.concurrent.CompletableFuture

/**
 * The camera session's ownership state, with no Android in it. `CameraSessionOwner` is the adapter
 * that drives the device; every enable/open/lease/close decision is made here so each interleaving —
 * first and last lease, a second subscriber joining mid-open, the master switch turning off during an
 * open, an open that fails while subscribers wait, a stop at any point — is a unit test rather than
 * something a reviewer has to trace by hand.
 *
 * The [generation] is the only identity a lease or a device callback carries. It advances exactly when
 * a new open attempt series starts and when the session closes for any reason, so a callback or frame
 * from a superseded attempt is dropped by comparison rather than by a flag somebody must remember to
 * set. A lease taken during [Phase.OPENING] carries the generation of the open it is joining, which is
 * what lets the first lease close the session it opened.
 *
 * Not thread-safe: the owner serialises every call under one lock.
 */
class CameraSessionState(private val policy: () -> CameraSessionPolicy) {

    enum class Phase { IDLE, OPENING, LIVE, DEGRADED, STOPPING }

    var phase: Phase = Phase.IDLE
        private set
    var generation: Long = 0L
        private set
    var consecutiveFailures: Int = 0
        private set
    var lastFrameAtMs: Long? = null
        private set
    var openedAtMs: Long = 0L
        private set

    private val leases = LinkedHashSet<Long>()
    private var nextLease = 1L
    private val waiters = ArrayList<CompletableFuture<ByteArray?>>()
    private var pendingFault: CameraFault? = null

    val clients: Int get() = leases.size

    sealed interface Admission {
        data class Refused(val reason: CameraRefusal) : Admission
        /** The session is open or opening; the caller holds [lease] and waits for [generation]'s outcome if any. */
        data class Join(val lease: Long, val generation: Long) : Admission
        /** The caller's lease is the first; the owner must start open attempt [generation]. */
        data class Open(val lease: Long, val generation: Long) : Admission
    }

    /** [gate] is the classified refusal from the static gates, or null when they all pass. */
    fun acquire(gate: CameraRefusal?, nowMs: Long): Admission {
        if (phase == Phase.STOPPING) return Admission.Refused(CameraRefusal.STOPPING)
        if (gate != null) return Admission.Refused(gate)
        val lease = nextLease++
        leases += lease
        return when (phase) {
            Phase.LIVE, Phase.OPENING -> Admission.Join(lease, generation)
            Phase.IDLE, Phase.DEGRADED -> {
                // A degraded session retries on the next subscriber: that is the documented recovery.
                consecutiveFailures = 0
                beginAttempt(nowMs)
                Admission.Open(lease, generation)
            }
            Phase.STOPPING -> error("unreachable")
        }
    }

    private fun beginAttempt(nowMs: Long) {
        phase = Phase.OPENING
        generation++
        openedAtMs = nowMs
        lastFrameAtMs = null
        pendingFault = null
    }

    /** True when [gen] is the live attempt and the session is now serving. */
    fun openSucceeded(gen: Long): Boolean {
        if (gen != generation || phase != Phase.OPENING) return false
        phase = Phase.LIVE
        consecutiveFailures = 0
        return true
    }

    sealed interface Failure {
        /** A superseded attempt; nothing to do. */
        data object Ignored : Failure
        /** Nobody is waiting any more; the owner releases hardware and closes. */
        data object Close : Failure
        data class Reopen(val afterMs: Long, val attempt: Int) : Failure
        data class Degrade(val attempt: Int) : Failure
    }

    /** An open attempt for [gen] failed with [fault]. Subscribers keep their leases across a reopen. */
    fun openFailed(gen: Long, fault: CameraFault): Failure {
        if (gen != generation) return Failure.Ignored
        consecutiveFailures++
        if (leases.isEmpty()) return Failure.Close
        return when (val d = policy().onFailure(fault, consecutiveFailures)) {
            is CameraSessionPolicy.Decision.Degrade -> {
                phase = Phase.DEGRADED
                Failure.Degrade(d.attempt)
            }
            is CameraSessionPolicy.Decision.Reopen -> {
                phase = Phase.OPENING
                Failure.Reopen(d.afterMs, d.attempt)
            }
            else -> error("onFailure never continues or closes")
        }
    }

    /** True when the caller must close the hardware: the last lease is gone. */
    fun release(lease: Long): Boolean {
        if (!leases.remove(lease)) return false
        return leases.isEmpty() && phase != Phase.IDLE && phase != Phase.STOPPING
    }

    /** The master switch turned off. True when the owner must release hardware now, whatever the phase. */
    fun disable(): Boolean {
        if (phase == Phase.IDLE || phase == Phase.STOPPING) return false
        // Advancing the generation is what makes an in-flight open's callbacks and frames stale.
        generation++
        return true
    }

    /** Hardware has been released; settle everyone and rest in [next]. */
    fun closed(next: Phase) {
        require(next == Phase.IDLE || next == Phase.STOPPING || next == Phase.DEGRADED)
        phase = next
        generation++
        leases.clear()
        val drained = waiters.toList()
        waiters.clear()
        drained.forEach { it.complete(null) }
        pendingFault = null
    }

    fun stopping(): Boolean {
        if (phase == Phase.STOPPING) return false
        val hadHardware = phase != Phase.IDLE
        phase = Phase.STOPPING
        generation++
        return hadHardware
    }

    /** A frame arrived for [gen]; returns the waiters to satisfy, or nothing if the frame is stale. */
    fun frame(gen: Long, nowMs: Long): List<CompletableFuture<ByteArray?>> {
        if (gen != generation || phase != Phase.LIVE) return emptyList()
        lastFrameAtMs = nowMs
        consecutiveFailures = 0
        val ready = waiters.toList()
        waiters.clear()
        return ready
    }

    fun addWaiter(w: CompletableFuture<ByteArray?>) {
        waiters += w
    }

    fun removeWaiter(w: CompletableFuture<ByteArray?>) {
        waiters.remove(w)
    }

    fun noteDeviceFault(gen: Long, fault: CameraFault): Boolean {
        if (gen != generation) return false
        pendingFault = fault
        return true
    }

    /** The watchdog tick; the policy decides, this only supplies the observed facts. */
    fun tick(nowMs: Long, enabled: Boolean, indicated: Boolean): CameraSessionPolicy.Decision {
        val fault = pendingFault ?: if (!indicated) CameraFault.INDICATION else null
        pendingFault = null
        return policy().onTick(
            CameraSessionPolicy.Tick(
                nowMs = nowMs, openedAtMs = openedAtMs, lastFrameAtMs = lastFrameAtMs, clients = leases.size,
                enabled = enabled, stopping = phase == Phase.STOPPING, deviceFault = fault,
                consecutiveFailures = consecutiveFailures,
            ),
        )
    }

    /** A reopen decided by the tick keeps the generation: the subscribers are the same. */
    fun reopening(attempt: Int) {
        phase = Phase.OPENING
        consecutiveFailures = attempt
        lastFrameAtMs = null
        pendingFault = null
    }

    fun degraded(attempt: Int) {
        phase = Phase.DEGRADED
        consecutiveFailures = attempt
        generation++
        leases.clear()
        val drained = waiters.toList()
        waiters.clear()
        drained.forEach { it.complete(null) }
    }
}
