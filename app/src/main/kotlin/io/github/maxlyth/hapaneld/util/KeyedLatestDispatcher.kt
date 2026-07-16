package io.github.maxlyth.hapaneld.util

import java.util.LinkedHashMap
import java.util.concurrent.TimeUnit

/** One daemon consumer with a bounded insertion-ordered map of latest pending values per key. */
internal class KeyedLatestDispatcher<K : Any, V : Any>(
    threadName: String,
    private val maxPendingKeys: Int,
    private val consume: (K, V) -> Unit,
) : AutoCloseable {
    enum class Admission { ACCEPTED, COALESCED, REJECTED, CLOSED }

    private val lock = Object()
    private val pending = LinkedHashMap<K, V>()
    private val workerName = threadName
    private var worker: Thread? = null
    private var closed = false

    init { require(maxPendingKeys > 0) }

    fun submit(key: K, value: V): Admission = synchronized(lock) {
        if (closed) return Admission.CLOSED
        val exists = pending.containsKey(key)
        if (!exists && pending.size >= maxPendingKeys) return Admission.REJECTED
        if (exists) pending.remove(key) // latest cross-key ordering: a replacement moves to the tail
        pending[key] = value
        if (worker == null) {
            worker = Thread(::runLoop, workerName).apply { isDaemon = true; start() }
        }
        lock.notifyAll()
        if (exists) Admission.COALESCED else Admission.ACCEPTED
    }

    fun pendingCount(): Int = synchronized(lock) { pending.size }

    private fun runLoop() {
        try {
            while (true) {
                val next = synchronized(lock) {
                    while (!closed && pending.isEmpty()) {
                        try { lock.wait() } catch (_: InterruptedException) { if (closed) return }
                    }
                    if (closed) return
                    val entry = pending.entries.first()
                    (entry.key to entry.value).also { pending.remove(entry.key) }
                }
                consume(next.first, next.second)
            }
        } finally {
            synchronized(lock) {
                if (worker === Thread.currentThread()) worker = null
                lock.notifyAll()
            }
        }
    }

    fun closeAndJoin(timeoutMs: Long): Boolean {
        val running = synchronized(lock) {
            closed = true
            pending.clear()
            lock.notifyAll()
            worker
        }
        running?.interrupt()
        if (running == null || running === Thread.currentThread()) return running == null
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0L))
        var interrupted = false
        synchronized(lock) {
            while (worker != null) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) break
                val millis = TimeUnit.NANOSECONDS.toMillis(remaining)
                val nanos = (remaining - TimeUnit.MILLISECONDS.toNanos(millis)).toInt()
                try {
                    lock.wait(millis, nanos)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        return synchronized(lock) { worker == null }
    }

    override fun close() { closeAndJoin(0L) }
}
