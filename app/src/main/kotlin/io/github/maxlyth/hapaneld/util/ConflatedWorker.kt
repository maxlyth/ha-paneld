package io.github.maxlyth.hapaneld.util

/** One worker with a single latest-value slot; stale transitions can never form an unbounded FIFO. */
internal class ConflatedWorker<T>(
    threadName: String,
    private val consume: (T) -> Unit,
) : AutoCloseable {
    enum class Admission { ACCEPTED, COALESCED, CLOSED }

    private val lock = Object()
    private var pending: T? = null
    private var hasPending = false
    private var closed = false
    private var worker: Thread? = null
    private val workerName = threadName

    fun submit(value: T): Admission = synchronized(lock) {
        if (closed) return Admission.CLOSED
        val result = if (hasPending) Admission.COALESCED else Admission.ACCEPTED
        pending = value
        hasPending = true
        if (worker == null) {
            worker = Thread({ runLoop() }, workerName).apply { isDaemon = true; start() }
        }
        lock.notifyAll()
        result
    }

    fun pendingCount(): Int = synchronized(lock) { if (hasPending) 1 else 0 }

    @Suppress("UNCHECKED_CAST")
    private fun runLoop() {
        try {
            while (true) {
                val next = synchronized(lock) {
                    while (!closed && !hasPending) {
                        try { lock.wait() } catch (_: InterruptedException) { if (closed) return }
                    }
                    if (closed) return
                    (pending as T).also { pending = null; hasPending = false }
                }
                consume(next)
            }
        } finally {
            synchronized(lock) {
                if (worker === Thread.currentThread()) worker = null
            }
        }
    }

    override fun close() {
        closeWorker()?.interrupt()
    }

    /** Stop accepting work, discard the pending slot, and wait at most [timeoutMs] for the active
     * consumer to return. The bounded join lets lifecycle owners prove an old generation is drained
     * without risking an indefinite service shutdown when the underlying I/O ignores interruption. */
    fun closeAndJoin(timeoutMs: Long): Boolean {
        val running = closeWorker()
        running?.interrupt()
        if (running == null || running === Thread.currentThread()) return running == null
        try {
            running.join(timeoutMs.coerceAtLeast(0L))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        return !running.isAlive
    }

    private fun closeWorker(): Thread? = synchronized(lock) {
        if (!closed) {
            closed = true
            pending = null
            hasPending = false
            lock.notifyAll()
        }
        worker
    }
}
