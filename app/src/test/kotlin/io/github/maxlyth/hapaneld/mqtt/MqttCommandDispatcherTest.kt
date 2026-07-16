package io.github.maxlyth.hapaneld.mqtt

import io.github.maxlyth.hapaneld.MqttCommandDispatcher
import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttCommandDispatcherTest {
    @Test fun blockedOnThenFloodEndsAtLatestOffWithConstantBacklog() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val states = Collections.synchronizedList(mutableListOf<String>())
        val dispatcher = MqttCommandDispatcher(threadName = "mqtt-latest-flood-test")

        assertEquals(MqttCommandDispatcher.Admission.ACCEPTED, dispatcher.submitLatest("screen") {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            states += "ON"
            finished.countDown()
        })
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        repeat(999) { index ->
            dispatcher.submitLatest("screen") { states += if (index % 2 == 0) "ON" else "OFF" }
        }
        assertEquals(MqttCommandDispatcher.Admission.COALESCED, dispatcher.submitLatest("screen") {
            states += "OFF"
            finished.countDown()
        })
        assertEquals(1, dispatcher.pendingCount())

        release.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        dispatcher.closeAndDrain()
        assertEquals(listOf("ON", "OFF"), states)
    }

    @Test fun replacingAStateMovesItBehindOtherAcceptedTopics() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(3)
        val observed = Collections.synchronizedList(mutableListOf<String>())
        val dispatcher = MqttCommandDispatcher(threadName = "mqtt-state-order-test")

        dispatcher.submitAction {
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            observed += "blocker"
            finished.countDown()
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        dispatcher.submitLatest("screen") { observed += "screen-old" }
        dispatcher.submitLatest("volume") { observed += "volume"; finished.countDown() }
        assertEquals(MqttCommandDispatcher.Admission.COALESCED, dispatcher.submitLatest("screen") {
            observed += "screen-new"
            finished.countDown()
        })

        release.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        dispatcher.closeAndDrain()
        assertEquals(listOf("blocker", "volume", "screen-new"), observed)
    }

    @Test fun actionFifoHasAnIndependentSmallBound() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(3)
        val actions = Collections.synchronizedList(mutableListOf<Int>())
        val dispatcher = MqttCommandDispatcher(
            maxPending = 5,
            maxPendingActions = 2,
            threadName = "mqtt-action-bound-test",
        )
        dispatcher.submitAction {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
            actions += 0
            finished.countDown()
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        assertEquals(MqttCommandDispatcher.Admission.ACCEPTED, dispatcher.submitAction {
            actions += 1
            finished.countDown()
        })
        assertEquals(MqttCommandDispatcher.Admission.ACCEPTED, dispatcher.submitAction {
            actions += 2
            finished.countDown()
        })
        assertEquals(MqttCommandDispatcher.Admission.REJECTED, dispatcher.submitAction { actions += 3 })
        // Stateful work still has room even when the action allowance is full.
        assertEquals(MqttCommandDispatcher.Admission.ACCEPTED, dispatcher.submitLatest("screen") {})

        release.countDown()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        dispatcher.closeAndDrain()
        assertEquals(listOf(0, 1, 2), actions)
    }

    @Test fun synchronousSettingSharesOrderingAndReportsFailureTruthfully() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val dispatcher = MqttCommandDispatcher(threadName = "mqtt-http-authority-test")
        val caller = Executors.newSingleThreadExecutor()
        try {
            dispatcher.submitLatest("mqtt-screen") {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                order += "mqtt"
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val http = caller.submit<Boolean> {
                dispatcher.runLatest("http-screen") { order += "http" }
            }
            assertFalse(http.isDone)
            release.countDown()
            assertTrue(http.get(5, TimeUnit.SECONDS))
            assertEquals(listOf("mqtt", "http"), order)
            assertFalse(dispatcher.runLatest("failure") { error("controller failed") })
        } finally {
            dispatcher.closeAndDrain()
            caller.shutdownNow()
        }
    }

    @Test fun supersededSynchronousSettingReturnsFalseAndLatestReturnsTrue() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondAdmitted = CountDownLatch(1)
        val applied = Collections.synchronizedList(mutableListOf<String>())
        val dispatcher = MqttCommandDispatcher(threadName = "mqtt-http-conflation-test")
        val callers = Executors.newFixedThreadPool(2)
        try {
            dispatcher.submitAction {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val first = callers.submit<MqttCommandDispatcher.RunResult> {
                dispatcher.runLatestResult("http:screen") { applied += "ON" }
            }
            val pendingDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (dispatcher.pendingCount() != 1 && System.nanoTime() < pendingDeadline) Thread.yield()
            assertEquals(1, dispatcher.pendingCount())
            val second = callers.submit<MqttCommandDispatcher.RunResult> {
                dispatcher.runLatestResult("http:screen", onAdmission = { secondAdmitted.countDown() }) {
                    applied += "OFF"
                }
            }
            assertTrue(secondAdmitted.await(5, TimeUnit.SECONDS))
            release.countDown()

            val firstResult = first.get(5, TimeUnit.SECONDS)
            val secondResult = second.get(5, TimeUnit.SECONDS)
            assertEquals(MqttCommandDispatcher.Admission.ACCEPTED, firstResult.admission)
            assertFalse(firstResult.executed)
            assertEquals(MqttCommandDispatcher.Execution.SUPERSEDED, firstResult.execution)
            assertEquals(MqttCommandDispatcher.Admission.COALESCED, secondResult.admission)
            assertTrue(secondResult.executed)
            assertEquals(MqttCommandDispatcher.Execution.SUCCEEDED, secondResult.execution)
            assertEquals(listOf("OFF"), applied)
        } finally {
            dispatcher.closeAndDrain()
            callers.shutdownNow()
        }
    }

    @Test fun closeCancelsPendingDrainsActiveAndRejectsLateCommands() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeReturned = CountDownLatch(1)
        val closeRestoredInterrupt = AtomicBoolean(false)
        val observed = Collections.synchronizedList(mutableListOf<String>())
        val dispatcher = MqttCommandDispatcher(threadName = "mqtt-close-test")

        dispatcher.submitAction {
            entered.countDown()
            while (release.count > 0) {
                try {
                    release.await(50, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    // Model a platform call which cannot be cancelled after it entered the controller.
                }
            }
            observed += "active"
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        dispatcher.submitAction { observed += "pending" }
        val closer = Thread {
            assertEquals(1, dispatcher.closeAndDrain())
            closeRestoredInterrupt.set(Thread.currentThread().isInterrupted)
            closeReturned.countDown()
        }.apply { start() }

        assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS))
        val admissionDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (dispatcher.pendingCount() != 0 && System.nanoTime() < admissionDeadline) Thread.yield()
        assertEquals(0, dispatcher.pendingCount())
        closer.interrupt()
        assertEquals(MqttCommandDispatcher.Admission.CLOSED, dispatcher.submitLatest("late") {
            observed += "late"
        })
        release.countDown()
        assertTrue(closeReturned.await(5, TimeUnit.SECONDS))
        assertTrue(closeRestoredInterrupt.get())
        assertEquals(listOf("active"), observed)
        assertEquals(0, dispatcher.pendingCount())
    }

    @Test fun instrumentationKeyIsFixedAndPublicSafe() {
        assertEquals("mqtt.command_dispatch", FeatureCostOperation.MQTT_COMMAND_DISPATCH.id)
    }

    @Test fun admittedHandlerExceptionIsDistinguishedFromClosedAdmission() {
        val dispatcher = MqttCommandDispatcher(threadName = "mqtt-result-test")
        val failed = dispatcher.runLatestResult("failure") { error("controller failed") }
        assertEquals(MqttCommandDispatcher.Admission.ACCEPTED, failed.admission)
        assertEquals(MqttCommandDispatcher.Execution.FAILED, failed.execution)

        dispatcher.closeAndDrain()
        val closed = dispatcher.runLatestResult("closed") {}
        assertEquals(MqttCommandDispatcher.Admission.CLOSED, closed.admission)
        assertEquals(MqttCommandDispatcher.Execution.NOT_ADMITTED, closed.execution)
    }
}
