package io.github.maxlyth.hapaneld.util

/**
 * TTL + single-flight memo for expensive probes (su round-trips, `wm density`, process checks).
 * [get] returns the cached value while it's fresh and otherwise rebuilds — concurrent callers wait
 * for the one in-flight build instead of stampeding the probe. [peek] never blocks: it returns the
 * last-known value (possibly stale) or null if never built — the "render instantly, hydrate later"
 * read. [invalidate] forces the next [get] to rebuild (call after a write that changes the probed
 * state, so the UI doesn't show pre-write values for a TTL).
 */
class Cached<T : Any>(
    private val ttlMs: Long,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val supplier: () -> T,
) {
    @Volatile private var value: T? = null
    @Volatile private var builtAt = Long.MIN_VALUE
    private val lock = Any()

    init { require(ttlMs >= 0L) }

    fun peek(): T? = value

    /** Age of the cached value; MAX_VALUE when never built. Lets callers do stale-while-revalidate. */
    fun ageMs(): Long = ageAt(nowMs())

    fun get(): T {
        value?.let { if (ageAt(nowMs()) < ttlMs) return it }
        synchronized(lock) {
            value?.let { if (ageAt(nowMs()) < ttlMs) return it }
            val built = supplier()
            value = built
            builtAt = nowMs()
            return built
        }
    }

    fun invalidate() {
        builtAt = Long.MIN_VALUE
    }

    /** Prime the cache with a known value (fresh). Use after a write whose result is known, so the UI
     *  shows it immediately instead of racing a re-probe that may still read the pre-write state. */
    fun set(v: T) {
        synchronized(lock) {
            value = v
            builtAt = nowMs()
        }
    }

    private fun ageAt(now: Long): Long {
        val built = builtAt
        if (value == null || built == Long.MIN_VALUE || now < built) return Long.MAX_VALUE
        return now - built
    }
}
