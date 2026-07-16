package io.github.maxlyth.hapaneld.util

import java.util.concurrent.TimeUnit

/**
 * One daemon worker plus one pending latest-value slot.
 *
 * A running item is allowed to finish, while a burst replaces only the pending item. [onDiscard]
 * observes replaced, close-drained, and post-close items so callers can release per-item resources.
 */
internal class BoundedLatestDispatcher<T>(
    threadName: String,
    private val consume: (T) -> Unit,
    private val onDiscard: (T) -> Unit = {},
) : AutoCloseable {
    enum class Admission { ACCEPTED, COALESCED, CLOSED }

    private val lock = Object()
    private val workerName = threadName
    private var pending: T? = null
    private var hasPending = false
    private var closed = false
    private var worker: Thread? = null

    fun submit(value: T): Admission {
        var discarded: T? = null
        val admission = synchronized(lock) {
            if (closed) {
                discarded = value
                Admission.CLOSED
            } else {
                val result = if (hasPending) Admission.COALESCED else Admission.ACCEPTED
                if (hasPending) discarded = pending
                pending = value
                hasPending = true
                if (worker == null) {
                    worker = Thread({ runLoop() }, workerName).apply {
                        isDaemon = true
                        start()
                    }
                }
                lock.notifyAll()
                result
            }
        }
        discarded?.let(onDiscard)
        return admission
    }

    fun pendingCount(): Int = synchronized(lock) { if (hasPending) 1 else 0 }

    @Suppress("UNCHECKED_CAST")
    private fun runLoop() {
        try {
            while (true) {
                val next = synchronized(lock) {
                    while (!closed && !hasPending) {
                        try {
                            lock.wait()
                        } catch (_: InterruptedException) {
                            if (closed) return
                        }
                    }
                    if (closed) return
                    (pending as T).also {
                        pending = null
                        hasPending = false
                    }
                }
                consume(next)
            }
        } finally {
            synchronized(lock) {
                if (worker === Thread.currentThread()) worker = null
                lock.notifyAll()
            }
        }
    }

    /** Wait until the worker has consumed its running item and terminated after [close]. */
    fun awaitTermination(timeoutMs: Long): Boolean {
        val timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        val deadline = System.nanoTime() + timeoutNanos
        synchronized(lock) {
            while (worker != null) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) return false
                val millis = TimeUnit.NANOSECONDS.toMillis(remaining)
                val nanos = (remaining - TimeUnit.MILLISECONDS.toNanos(millis)).toInt()
                try {
                    lock.wait(millis, nanos)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            return true
        }
    }

    override fun close() {
        var discarded: T? = null
        synchronized(lock) {
            if (closed) return
            closed = true
            if (hasPending) discarded = pending
            pending = null
            hasPending = false
            lock.notifyAll()
        }
        discarded?.let(onDiscard)
    }
}
