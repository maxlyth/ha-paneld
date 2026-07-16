package io.github.maxlyth.hapaneld.util

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyedLatestDispatcherTest {
    @Test fun `latest per key is bounded while distinct keys retain order`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val consumed = CountDownLatch(3)
        val seen = Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
        val dispatcher = KeyedLatestDispatcher<String, Int>("keyed-test", 2) { key, value ->
            if (key == "running") { entered.countDown(); release.await(2, TimeUnit.SECONDS) }
            seen += key to value
            consumed.countDown()
        }
        try {
            assertEquals(KeyedLatestDispatcher.Admission.ACCEPTED, dispatcher.submit("running", 0))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(KeyedLatestDispatcher.Admission.ACCEPTED, dispatcher.submit("a", 1))
            assertEquals(KeyedLatestDispatcher.Admission.COALESCED, dispatcher.submit("a", 2))
            assertEquals(KeyedLatestDispatcher.Admission.ACCEPTED, dispatcher.submit("b", 3))
            assertEquals(KeyedLatestDispatcher.Admission.REJECTED, dispatcher.submit("c", 4))
            release.countDown()
            assertTrue(consumed.await(2, TimeUnit.SECONDS))
            assertTrue(dispatcher.closeAndJoin(2_000))
            assertEquals(listOf("running" to 0, "a" to 2, "b" to 3), seen.toList())
        } finally {
            release.countDown()
            dispatcher.closeAndJoin(2_000)
        }
    }

    @Test fun `close rejects late work and interruption cannot deadlock drain`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dispatcher = KeyedLatestDispatcher<String, Int>("keyed-close", 1) { _, _ ->
            entered.countDown()
            while (true) {
                try { if (release.await(2, TimeUnit.SECONDS)) break } catch (_: InterruptedException) { }
            }
        }
        assertEquals(KeyedLatestDispatcher.Admission.ACCEPTED, dispatcher.submit("a", 1))
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val done = CountDownLatch(1)
        val closer = Thread { dispatcher.closeAndJoin(2_000); done.countDown() }.apply { start() }
        closer.interrupt()
        release.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        closer.join(1_000)
        assertFalse(closer.isAlive)
        assertEquals(KeyedLatestDispatcher.Admission.CLOSED, dispatcher.submit("a", 2))
    }
}
