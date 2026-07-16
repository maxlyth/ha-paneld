package io.github.maxlyth.hapaneld.util

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedLatestDispatcherTest {
    @Test fun floodKeepsOnlyRunningAndLatestPendingItem() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val consumed = Collections.synchronizedList(mutableListOf<Int>())
        val discarded = Collections.synchronizedList(mutableListOf<Int>())
        val dispatcher = BoundedLatestDispatcher<Int>(
            threadName = "latest-flood-test",
            consume = {
                consumed += it
                if (it == 0) {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
                finished.countDown()
            },
            onDiscard = { discarded += it },
        )

        assertEquals(BoundedLatestDispatcher.Admission.ACCEPTED, dispatcher.submit(0))
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        repeat(1_000) { dispatcher.submit(it + 1) }

        assertEquals(1, dispatcher.pendingCount())
        release.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        dispatcher.close()
        assertEquals(listOf(0, 1_000), consumed)
        assertEquals((1 until 1_000).toList(), discarded.sorted())
    }

    @Test fun acceptedItemsRunSeriallyInSubmissionOrder() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val consumed = Collections.synchronizedList(mutableListOf<Int>())
        val dispatcher = BoundedLatestDispatcher<Int>("latest-order-test", consume = {
            consumed += it
            if (it == 1) {
                firstEntered.countDown()
                releaseFirst.await(5, TimeUnit.SECONDS)
            }
            finished.countDown()
        })

        dispatcher.submit(1)
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
        dispatcher.submit(2)
        releaseFirst.countDown()

        assertTrue(finished.await(5, TimeUnit.SECONDS))
        dispatcher.close()
        assertEquals(listOf(1, 2), consumed)
    }

    @Test fun closeDrainsPendingAndRejectsFutureItems() {
        val entered = CountDownLatch(1)
        val holdRunning = CountDownLatch(1)
        val runningFinished = CountDownLatch(1)
        val consumed = Collections.synchronizedList(mutableListOf<Int>())
        val discarded = Collections.synchronizedList(mutableListOf<Int>())
        val dispatcher = BoundedLatestDispatcher<Int>(
            threadName = "latest-close-test",
            consume = {
                consumed += it
                entered.countDown()
                holdRunning.await(5, TimeUnit.SECONDS)
                runningFinished.countDown()
            },
            onDiscard = { discarded += it },
        )

        dispatcher.submit(1)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        dispatcher.submit(2)
        dispatcher.close()
        assertEquals(BoundedLatestDispatcher.Admission.CLOSED, dispatcher.submit(3))
        holdRunning.countDown()

        assertTrue(runningFinished.await(5, TimeUnit.SECONDS))
        assertTrue(dispatcher.awaitTermination(5_000L))
        assertEquals(listOf(1), consumed)
        assertEquals(listOf(2, 3), discarded)
        assertEquals(0, dispatcher.pendingCount())
    }

    @Test fun terminationWaitIsBoundedWhileTheRunningItemFinishes() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val dispatcher = BoundedLatestDispatcher<Unit>("latest-drain-test", consume = {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        })

        dispatcher.submit(Unit)
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        dispatcher.close()
        assertFalse(dispatcher.awaitTermination(10L))
        release.countDown()
        assertTrue(dispatcher.awaitTermination(5_000L))
    }
}
