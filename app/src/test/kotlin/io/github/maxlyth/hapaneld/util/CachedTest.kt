package io.github.maxlyth.hapaneld.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class CachedTest {
    @Test fun peekNeverBuildsAndGetCachesWithinTtl() {
        val builds = AtomicInteger()
        val c = Cached(60_000) { builds.incrementAndGet() }
        assertNull(c.peek())
        assertEquals(0, builds.get())
        assertEquals(1, c.get())
        assertEquals(1, c.get())            // fresh → no rebuild
        assertEquals(1, builds.get())
        assertEquals(1, c.peek())
    }

    @Test fun invalidateForcesRebuildButPeekKeepsLastKnown() {
        val builds = AtomicInteger()
        val c = Cached(60_000) { builds.incrementAndGet() }
        c.get()
        c.invalidate()
        assertEquals(1, c.peek())           // stale value still readable without blocking
        assertEquals(2, c.get())
    }

    @Test fun expiredTtlRebuilds() {
        val builds = AtomicInteger()
        val c = Cached(1) { builds.incrementAndGet() }
        c.get()
        Thread.sleep(10)
        assertEquals(2, c.get())
    }

    @Test fun concurrentGetsSingleFlight() {
        val builds = AtomicInteger()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val c = Cached(60_000) {
            entered.countDown()
            release.await()
            builds.incrementAndGet()
        }
        val results = java.util.concurrent.CopyOnWriteArrayList<Int>()
        val threads = (1..8).map { Thread { results.add(c.get()) }.apply { start() } }
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        release.countDown()                 // one builder runs; the other 7 wait and reuse
        threads.forEach { it.join(5_000) }
        assertEquals(8, results.size)
        assertTrue(results.all { it == 1 })
        assertEquals(1, builds.get())
    }

    @Test fun backwardClockMovementExpiresInsteadOfPinningAStaleValue() {
        var now = 10_000L
        val builds = AtomicInteger()
        val c = Cached(60_000, nowMs = { now }) { builds.incrementAndGet() }
        assertEquals(1, c.get())
        now = 1_000L
        assertEquals(Long.MAX_VALUE, c.ageMs())
        assertEquals(2, c.get())
    }

    @Test fun setAndInvalidateUseTheInjectedMonotonicClock() {
        var now = 100L
        val c = Cached(10, nowMs = { now }) { "built" }
        c.set("known")
        now = 105L
        assertEquals(5L, c.ageMs())
        assertEquals("known", c.get())
        c.invalidate()
        assertEquals(Long.MAX_VALUE, c.ageMs())
        assertEquals("built", c.get())
    }
}
