package io.github.maxlyth.hapaneld.util

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConflatedWorkerTest {
    @Test fun blockedWorkRetainsOnlyTheLatestPendingValue() {
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val consumed = mutableListOf<Int>()
        val worker = ConflatedWorker<Int>("conflated-test") { value ->
            synchronized(consumed) { consumed += value }
            if (value == 1) {
                firstStarted.countDown()
                releaseFirst.await(2, TimeUnit.SECONDS)
            } else secondFinished.countDown()
        }
        try {
            assertEquals(ConflatedWorker.Admission.ACCEPTED, worker.submit(1))
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS))
            assertEquals(ConflatedWorker.Admission.ACCEPTED, worker.submit(2))
            assertEquals(ConflatedWorker.Admission.COALESCED, worker.submit(3))
            assertEquals(1, worker.pendingCount())
            releaseFirst.countDown()
            assertTrue(secondFinished.await(1, TimeUnit.SECONDS))
            assertEquals(listOf(1, 3), synchronized(consumed) { consumed.toList() })
            assertEquals(0, worker.pendingCount())
        } finally {
            releaseFirst.countDown()
            worker.close()
        }
        assertEquals(ConflatedWorker.Admission.CLOSED, worker.submit(4))
    }

    @Test fun closeAndJoinIsBoundedAndCanObserveLaterDrain() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = ConflatedWorker<Unit>("conflated-close") {
            started.countDown()
            while (release.count > 0) {
                try {
                    release.await()
                } catch (_: InterruptedException) {
                    // Model privileged I/O which does not return merely because its owner was stopped.
                }
            }
        }

        assertEquals(ConflatedWorker.Admission.ACCEPTED, worker.submit(Unit))
        assertTrue(started.await(1, TimeUnit.SECONDS))
        val zeroStartedAt = System.nanoTime()
        assertFalse(worker.closeAndJoin(0L))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - zeroStartedAt) < 500L)
        assertFalse(worker.closeAndJoin(20))
        assertEquals(ConflatedWorker.Admission.CLOSED, worker.submit(Unit))

        release.countDown()
        assertTrue(worker.closeAndJoin(1_000))
    }
}
