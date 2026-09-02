package io.github.maxlyth.hapaneld.sensors

import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The layer-3 probe's one owner: what to probe, when, and what the bursts have shown.
 *
 * It deliberately runs no loop of its own. The shared socket already ticks on a fixed cadence to send
 * its WebSocket ping, and that tick is a perfectly good clock to ask "is a burst due?" against — so
 * this adds no thread, no lifecycle and nothing to leak. The caller does the dispatching, because a
 * burst blocks for as long as its echoes take and must never sit on the socket's own probe path.
 *
 * Serialised on one lock in the [HaNetworkPathMonitor] style. Retains counts and round trips only;
 * the target address is held to send to and is never exposed on any surface.
 */
internal class PathProbeMonitor(
    private val source: PathEchoSource,
    private val schedule: PathProbeSchedule = PathProbeSchedule(),
    private val history: PathProbeHistory = PathProbeHistory(),
    private val echoesPerBurst: Int = PathProbeHistory.ECHOES_PER_BURST,
    private val perEchoTimeoutMs: Long = PER_ECHO_TIMEOUT_MS,
) {
    private val lock = Any()
    private val bursting = AtomicBoolean(false)

    @Volatile private var target: InetAddress? = null
    private var measuring = false

    /**
     * The address the shared socket actually connected on, as reported by the transport.
     *
     * Taken from the live connection rather than resolved here, so the probe always measures the path
     * the dashboard is using even when the two families disagree about which one works.
     */
    fun onRouteConnected(address: InetAddress) {
        target = address
    }

    /** Measurement follows the authenticated socket, exactly as the WebSocket monitor's does. */
    fun onSocketState(state: HaSocketState) = synchronized(lock) {
        when (state) {
            HaSocketState.LIVE -> measuring = true
            HaSocketState.CONNECTING -> Unit
            HaSocketState.STOPPED -> {
                measuring = false
                schedule.reset()
                history.reset()
                target = null
            }
        }
    }

    /**
     * A silent pong timeout on the shared socket: bring the next burst forward.
     *
     * This is the two probes cooperating. A pong that never arrived is precisely the observation the
     * layer-3 probe exists to attribute — a path that lost the packet, or a server that never sent
     * one — so it is the one moment where an immediate answer is worth the echoes.
     */
    fun onSocketSilence(nowMs: Long) = synchronized(lock) {
        schedule.escalate(nowMs)
    }

    /**
     * Claim the right to run a burst now, or null when one is not due.
     *
     * Returns the target so the caller can run [runBurst] off the calling thread. A burst already in
     * flight always wins: bursts must never overlap, or their echoes race for the same sequence
     * numbers and the loss figure becomes fiction.
     */
    fun claimBurst(nowMs: Long): InetAddress? = synchronized(lock) {
        val address = target ?: return null
        if (!measuring) return null
        if (!schedule.due(nowMs)) return null
        if (!bursting.compareAndSet(false, true)) return null
        schedule.started(nowMs)
        address
    }

    /** Run the claimed burst. Blocking; the caller must already be off the socket's probe path. */
    fun runBurst(target: InetAddress, nowMs: () -> Long) {
        try {
            val burst = source.burst(target, echoesPerBurst, perEchoTimeoutMs, nowMs)
            synchronized(lock) {
                if (burst == null) {
                    // The platform withholds the capability. Nothing here will ever be measured, and
                    // the panel keeps the verdict its WebSocket can support.
                    history.markUnsupported()
                } else {
                    history.record(burst)
                    schedule.completed(nowMs(), clean = burst.clean)
                }
            }
        } finally {
            bursting.set(false)
        }
    }

    /** One atomic reading for the surfaces, or null when there is nothing to describe. */
    fun snapshot(nowMs: Long): Snapshot? = synchronized(lock) {
        if (!measuring && history.state != PathProbeAvailability.UNSUPPORTED) return null
        val aggregate = history.aggregate()
        Snapshot(
            availability = aggregate.availability,
            severity = history.severity(),
            bursts = aggregate.bursts,
            sent = aggregate.sent,
            received = aggregate.received,
            lossPercent = aggregate.lossPercent,
            p50Ms = aggregate.p50Ms,
            p95Ms = aggregate.p95Ms,
            maxMs = aggregate.maxMs,
            jitterMs = aggregate.jitterMs,
            consecutiveDeadBursts = aggregate.consecutiveDeadBursts,
            intervalMs = schedule.currentIntervalMs,
            lastBurstAgeMs = schedule.lastBurstAgeMs(nowMs),
        )
    }

    /**
     * The one tuple a surface may render.
     *
     * [severity] is null whenever the probe is not entitled to a verdict — unsupported, or not yet
     * proven — and a surface must render that as "not measured", never as health.
     */
    data class Snapshot(
        val availability: PathProbeAvailability,
        val severity: HaNetworkPathSeverity?,
        val bursts: Int,
        val sent: Int,
        val received: Int,
        val lossPercent: Double,
        val p50Ms: Long,
        val p95Ms: Long,
        val maxMs: Long,
        val jitterMs: Long,
        val consecutiveDeadBursts: Int,
        val intervalMs: Long,
        val lastBurstAgeMs: Long,
    )

    companion object {
        /** Per echo. A LAN answers in single-digit milliseconds; a second is already a lost packet. */
        const val PER_ECHO_TIMEOUT_MS = 1_000L
    }
}
