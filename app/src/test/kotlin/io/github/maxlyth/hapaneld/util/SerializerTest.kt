package io.github.maxlyth.hapaneld.util

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class SerializerTest {
    /**
     * The config-change race: reconfigure() stops + rebuilds + restarts the MQTT/mDNS stack, and two
     * overlapping runs must never interleave (that left the bridge stopped/half-built — "resubmit until it
     * works"). Fire many concurrent blocks that suspend mid-way (yield + delay) — an unserialized
     * implementation would let a second block enter while the first is suspended — and assert no two are
     * ever active simultaneously.
     */
    @Test fun concurrentBlocksNeverOverlap() = runTest {
        val serializer = Serializer(this)
        var active = 0
        var maxActive = 0
        var completed = 0
        val jobs = (1..20).map {
            serializer.launch {
                active++
                if (active > maxActive) maxActive = active
                yield()    // an unserialized impl would let the next block enter here…
                delay(1)   // …and here
                active--
                completed++
            }
        }
        jobs.forEach { it.join() }
        assertEquals("every block ran", 20, completed)
        assertEquals("never two blocks active at once", 1, maxActive)
    }
}
