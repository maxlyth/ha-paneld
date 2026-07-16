package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.util.ConflatedWorker
import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttLifecycleWorkerTest {
    @Test fun reannouncementCostUsesAFixedNonIdentifyingOperation() {
        assertEquals("mqtt.discovery_reannounce", FeatureCostOperation.MQTT_DISCOVERY_REANNOUNCE.id)
    }

    @Test fun birthBurstRunsOnlyTheLatestDebouncedReannouncement() {
        val pauseStarted = CountDownLatch(1)
        val releasePause = CountDownLatch(1)
        val performed = CountDownLatch(1)
        var calls = 0
        val dispatcher = MqttReannounceDispatcher(
            debounceMs = 250,
            pause = {
                pauseStarted.countDown()
                releasePause.await(1, TimeUnit.SECONDS)
            },
            perform = {
                calls += 1
                performed.countDown()
            },
        )

        assertEquals(ConflatedWorker.Admission.ACCEPTED, dispatcher.submit())
        assertTrue(pauseStarted.await(1, TimeUnit.SECONDS))
        assertEquals(ConflatedWorker.Admission.ACCEPTED, dispatcher.submit())
        assertEquals(ConflatedWorker.Admission.COALESCED, dispatcher.submit())
        assertEquals(1, dispatcher.pendingCount())
        releasePause.countDown()

        assertTrue(performed.await(1, TimeUnit.SECONDS))
        assertEquals(1, calls)
        assertTrue(dispatcher.closeAndJoin(1_000))
        assertEquals(ConflatedWorker.Admission.CLOSED, dispatcher.submit())
    }

    @Test fun teardownCancelsAReannouncementStillInsideItsDebounceWindow() {
        val pauseStarted = CountDownLatch(1)
        val releasePause = CountDownLatch(1)
        var calls = 0
        val dispatcher = MqttReannounceDispatcher(
            debounceMs = 250,
            pause = {
                pauseStarted.countDown()
                try {
                    releasePause.await(1, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    // Let the dispatcher observe its closed generation after the bounded pause.
                }
            },
            perform = { calls += 1 },
        )

        dispatcher.submit()
        assertTrue(pauseStarted.await(1, TimeUnit.SECONDS))
        assertTrue(dispatcher.closeAndJoin(1_000))
        releasePause.countDown()
        assertEquals(0, calls)
    }

    @Test fun connectAnnouncementSuppressesARetainedBirthAlreadyInTheDebounceSlot() {
        val pauseStarted = CountDownLatch(1)
        val releasePause = CountDownLatch(1)
        var calls = 0
        val dispatcher = MqttReannounceDispatcher(
            debounceMs = 250,
            pause = {
                pauseStarted.countDown()
                releasePause.await(1, TimeUnit.SECONDS)
            },
            perform = { calls += 1 },
        )

        dispatcher.submit()
        assertTrue(pauseStarted.await(1, TimeUnit.SECONDS))
        dispatcher.suppressPending()
        releasePause.countDown()

        assertTrue(dispatcher.closeAndJoin(1_000))
        assertEquals(0, calls)
    }

    @Test fun replacementActuationAlwaysRunsAfterAnUninterruptibleOldGeneration() {
        val coordinator = MqttZigbeeActuationCoordinator()
        val oldLease = coordinator.activate()
        val oldStarted = CountDownLatch(1)
        val releaseOld = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val events = Collections.synchronizedList(mutableListOf<String>())

        val old = Thread {
            val result = coordinator.executeIfCurrent(oldLease) {
                oldStarted.countDown()
                releaseOld.await(1, TimeUnit.SECONDS)
                events += "old-on"
            }
            assertTrue(result.executed)
            assertFalse(result.currentAfter)
            finished.countDown()
        }
        old.start()
        assertTrue(oldStarted.await(1, TimeUnit.SECONDS))

        coordinator.retire(oldLease)
        val replacementLease = coordinator.activate()
        val replacement = Thread {
            val result = coordinator.executeIfCurrent(replacementLease) { events += "replacement-off" }
            assertTrue(result.executed)
            assertTrue(result.currentAfter)
            finished.countDown()
        }
        replacement.start()
        releaseOld.countDown()

        assertTrue(finished.await(1, TimeUnit.SECONDS))
        assertEquals(listOf("old-on", "replacement-off"), events.toList())
    }

    @Test fun invalidatedGenerationCannotBeginAQueuedActuation() {
        val coordinator = MqttZigbeeActuationCoordinator()
        val oldLease = coordinator.activate()
        coordinator.retire(oldLease)
        coordinator.activate()

        val result = coordinator.executeIfCurrent(oldLease) { error("obsolete action ran") }

        assertFalse(result.executed)
        assertFalse(result.currentAfter)
    }
}
