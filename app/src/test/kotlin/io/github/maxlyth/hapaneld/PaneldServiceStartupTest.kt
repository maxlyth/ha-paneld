package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.util.RendererPreparationCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneldServiceStartupTest {
    @Test fun liveReconfigureResolvesNativeHaLinkWithoutWaitingForMqttConnection() {
        val events = mutableListOf<String>()

        startReconfiguredNetworkRuntime(
            startMdns = { events += "mdns" },
            resolveHaLink = { events += "ha-link" },
            // Models the MQTT-disabled path: start returns without an onConnected callback.
            startMqtt = { events += "mqtt-disabled" },
        )

        assertEquals(listOf("mdns", "ha-link", "mqtt-disabled"), events)
    }

    @Test fun mdnsStartsBeforeRendererReconciliationAndLearning() {
        val events = mutableListOf<String>()

        val result = prepareEntityLearningStartup(
            startMdns = { events += "mdns" },
            reconcileRenderer = {
                events += "reconcile"
                RendererPreparationCoordinator.Result.ALREADY_READY
            },
            startLearning = { events += "learning" },
        )

        assertEquals(RendererPreparationCoordinator.Result.ALREADY_READY, result)
        assertEquals(listOf("mdns", "reconcile", "learning"), events)
    }

    @Test fun closedRendererPreparationDoesNotStartLearning() {
        var learningStarted = false

        val result = prepareEntityLearningStartup(
            startMdns = {},
            reconcileRenderer = { RendererPreparationCoordinator.Result.CLOSED },
            startLearning = { learningStarted = true },
        )

        assertEquals(RendererPreparationCoordinator.Result.CLOSED, result)
        assertFalse(learningStarted)
    }

    @Test fun successfulBorrowCommitNotifiesLearnerAfterPersistence() {
        val events = mutableListOf<String>()

        val committed = commitBorrowedRendererTarget(
            commit = { events += "persist"; true },
            onCommitted = { events += "notify" },
        )

        assertTrue(committed)
        assertEquals(listOf("persist", "notify"), events)
    }

    @Test fun failedBorrowCommitDoesNotNotifyLearner() {
        var notified = false

        val committed = commitBorrowedRendererTarget(
            commit = { false },
            onCommitted = { notified = true },
        )

        assertFalse(committed)
        assertFalse(notified)
    }

    @Test fun learnerStoreClosesOnlyAfterIngressRendererAndJobsDrain() {
        val events = mutableListOf<String>()

        val result = shutdownEntityLearningAfterIngress(
            stopIngress = { events += "http-stop"; true },
            closeRendererAdmission = { events += "renderer-drain"; true },
            detachRuntime = { events += "runtime-detach" },
            cancelAndDrainScope = { events += "scope-drain"; true },
            closeStore = { events += "store-close" },
        )

        assertTrue(result.storeClosed)
        assertEquals(
            listOf("http-stop", "renderer-drain", "runtime-detach", "scope-drain", "store-close"),
            events,
        )
    }

    @Test fun undrainedProducerLeavesLearnerStoreOpenForProcessTeardown() {
        val events = mutableListOf<String>()

        val result = shutdownEntityLearningAfterIngress(
            stopIngress = { events += "http-stop"; true },
            closeRendererAdmission = { events += "renderer-timeout"; false },
            detachRuntime = { events += "runtime-detach" },
            cancelAndDrainScope = { events += "scope-drain"; true },
            closeStore = { events += "store-close" },
        )

        assertFalse(result.storeClosed)
        assertEquals(listOf("http-stop", "renderer-timeout", "runtime-detach", "scope-drain"), events)
    }
}
