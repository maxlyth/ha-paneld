package io.github.maxlyth.hapaneld

import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture

/**
 * One bounded, ordered authority for live MQTT/HTTP commands.
 *
 * Stateful commands conflate by key: replacing a pending value moves the new value to the tail, so
 * the values which survive a burst still execute in their accepted arrival order. Actions never
 * conflate and have a smaller FIFO allowance. Closing rejects new work, cancels everything pending,
 * and waits for the one active handler to finish before returning.
 */
internal class MqttCommandDispatcher(
    private val maxPending: Int = DEFAULT_MAX_PENDING,
    private val maxPendingActions: Int = DEFAULT_MAX_PENDING_ACTIONS,
    private val threadName: String = "mqtt-command-dispatch",
) {
    enum class Admission { ACCEPTED, COALESCED, REJECTED, CLOSED }
    enum class Execution { SUCCEEDED, FAILED, SUPERSEDED, NOT_ADMITTED }
    data class RunResult(val admission: Admission, val execution: Execution) {
        val executed: Boolean get() = execution == Execution.SUCCEEDED
    }

    private data class Work(
        val latestKey: String?,
        val action: () -> Boolean,
        val completion: CompletableFuture<Execution>?,
    )

    private val lock = Object()
    private val pending = ArrayDeque<Work>()
    private var accepting = true
    private var worker: Thread? = null
    private var pendingActions = 0

    init {
        require(maxPending > 0)
        require(maxPendingActions in 1..maxPending)
    }

    fun submitLatest(key: String, command: () -> Unit): Admission {
        require(key.isNotBlank())
        return submit(Work(key, action = { command(); true }, completion = null))
    }

    fun submitAction(command: () -> Unit): Admission =
        submit(Work(latestKey = null, action = { command(); true }, completion = null))

    /** Execute a state-setting command on the same ordered authority and report whether it ran. */
    fun runLatest(key: String, command: () -> Unit): Boolean = runLatestResult(key, command = command).executed

    fun runLatestResult(
        key: String,
        onAdmission: (Admission) -> Unit = {},
        command: () -> Unit,
    ): RunResult {
        require(key.isNotBlank())
        val completion = CompletableFuture<Execution>()
        val admission = submit(Work(key, action = { command(); true }, completion))
        onAdmission(admission)
        val execution = if (admission == Admission.REJECTED || admission == Admission.CLOSED) {
            Execution.NOT_ADMITTED
        } else {
            completion.join()
        }
        return RunResult(admission, execution)
    }

    private fun submit(work: Work): Admission {
        var replaced: Work? = null
        val admission = synchronized(lock) {
            if (!accepting) return@synchronized Admission.CLOSED

            if (work.latestKey != null) {
                val iterator = pending.iterator()
                while (iterator.hasNext()) {
                    val candidate = iterator.next()
                    if (candidate.latestKey == work.latestKey) {
                        iterator.remove()
                        replaced = candidate
                        break
                    }
                }
            }

            if (replaced == null &&
                (pending.size >= maxPending || work.latestKey == null && pendingActions >= maxPendingActions)
            ) return@synchronized Admission.REJECTED

            pending.addLast(work)
            if (work.latestKey == null) pendingActions++
            if (!ensureWorkerLocked()) {
                pending.removeLast()
                if (work.latestKey == null) pendingActions--
                return@synchronized Admission.REJECTED
            }
            lock.notifyAll()
            if (replaced == null) Admission.ACCEPTED else Admission.COALESCED
        }

        when (admission) {
            Admission.COALESCED -> replaced?.completion?.complete(Execution.SUPERSEDED)
            Admission.REJECTED -> {
                replaced?.completion?.complete(Execution.SUPERSEDED)
                work.completion?.complete(Execution.NOT_ADMITTED)
            }
            Admission.CLOSED -> work.completion?.complete(Execution.NOT_ADMITTED)
            Admission.ACCEPTED -> Unit
        }
        return admission
    }

    fun pendingCount(): Int = synchronized(lock) { pending.size }

    /** Returns how many queued commands were cancelled. */
    fun closeAndDrain(): Int {
        val discarded = mutableListOf<Work>()
        val running = synchronized(lock) {
            if (accepting) {
                accepting = false
                while (pending.isNotEmpty()) discarded += pending.removeFirst()
                pendingActions = 0
                lock.notifyAll()
            }
            worker
        }
        discarded.forEach { it.completion?.complete(Execution.NOT_ADMITTED) }

        if (running !== Thread.currentThread()) {
            var interrupted = false
            synchronized(lock) {
                while (worker != null) {
                    try {
                        lock.wait()
                    } catch (_: InterruptedException) {
                        interrupted = true
                        // Teardown correctness is stronger than the caller's interrupt: the active
                        // hardware owner must be drained before its controller is destroyed.
                    }
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        return discarded.size
    }

    private fun ensureWorkerLocked(): Boolean {
        if (worker != null) return true
        val candidate = Thread({ runLoop() }, threadName).apply {
            isDaemon = true
        }
        worker = candidate
        return try {
            candidate.start()
            true
        } catch (_: Throwable) {
            worker = null
            false
        }
    }

    private fun runLoop() {
        try {
            while (true) {
                val work = synchronized(lock) {
                    while (accepting && pending.isEmpty()) lock.waitUninterruptibly()
                    if (!accepting) return
                    pending.removeFirst().also {
                        if (it.latestKey == null) pendingActions--
                    }
                }
                val execution = if (runCatching(work.action).getOrDefault(false)) {
                    Execution.SUCCEEDED
                } else {
                    Execution.FAILED
                }
                work.completion?.complete(execution)
            }
        } finally {
            synchronized(lock) {
                if (worker === Thread.currentThread()) worker = null
                lock.notifyAll()
            }
        }
    }

    private fun Object.waitUninterruptibly() {
        var interrupted = false
        while (true) {
            try {
                wait()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    companion object {
        internal const val DEFAULT_MAX_PENDING = 32
        internal const val DEFAULT_MAX_PENDING_ACTIONS = 8
    }
}
