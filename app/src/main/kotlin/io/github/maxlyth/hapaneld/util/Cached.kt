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
    private val supplier: () -> T,
) {
    @Volatile private var value: T? = null
    @Volatile private var builtAt = 0L
    private val lock = Any()

    fun peek(): T? = value

    /** Age of the cached value; MAX_VALUE when never built. Lets callers do stale-while-revalidate. */
    fun ageMs(): Long = if (value == null) Long.MAX_VALUE else System.currentTimeMillis() - builtAt

    fun get(): T {
        value?.let { if (System.currentTimeMillis() - builtAt < ttlMs) return it }
        synchronized(lock) {
            value?.let { if (System.currentTimeMillis() - builtAt < ttlMs) return it }
            val built = supplier()
            value = built
            builtAt = System.currentTimeMillis()
            return built
        }
    }

    fun invalidate() {
        builtAt = 0L
    }
}
