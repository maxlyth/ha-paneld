package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.control.CompanionDataOperationGate
import io.github.maxlyth.hapaneld.control.CompanionDataOperationState
import io.github.maxlyth.hapaneld.util.CompanionOperationStatus
import io.github.maxlyth.hapaneld.util.ConflatedWorker
import io.github.maxlyth.hapaneld.util.DurableRecoveryMarker
import io.github.maxlyth.hapaneld.util.RendererPreparationCoordinator
import java.util.Collections
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneldServiceStartupTest {
    @Test fun blockedReplacementPublishesItsOwnSnapshotThenConflatedNewerConfigReplacesAgain() {
        fun snapshot(name: String) = NetworkConfigurationSnapshot(
            runtime = NetworkRuntimeIdentity(
                panelId = name,
                friendlyName = "Panel $name",
                httpPort = 8_888,
                broker = "tcp://$name:1883",
                user = "user-$name",
                password = "secret-$name",
            ),
            projection = MqttProjectionIdentity("maker", "model-$name", listOf("wake" to true)),
            haLink = HaLinkIdentity("http://ha/$name", "access-$name", "refresh-$name", 1L, "client-$name"),
        )

        val initial = snapshot("initial")
        val replacementA = snapshot("a")
        val replacementB = snapshot("b")
        val desired = AtomicReference(replacementA)
        val active = AtomicReference(initial)
        val replacements = Collections.synchronizedList(mutableListOf<NetworkConfigurationSnapshot>())
        val replacementAStarted = CountDownLatch(1)
        val releaseReplacementA = CountDownLatch(1)
        val replacementBCompleted = CountDownLatch(1)
        val worker = ConflatedWorker<Unit>("network-identity-race-test") {
            val target = desired.get()
            if (target.runtime == active.get().runtime) return@ConflatedWorker
            // Model constructors consuming the immutable target rather than rereading desired config.
            val concreteReplacement = target
            replacements += concreteReplacement
            if (concreteReplacement == replacementA) {
                replacementAStarted.countDown()
                assertTrue(releaseReplacementA.await(2, TimeUnit.SECONDS))
            }
            // Completion must publish the snapshot carried by this concrete replacement. Reading
            // desired.get() here would incorrectly publish B for replacement A and skip the rerun.
            active.set(concreteReplacement)
            if (concreteReplacement == replacementB) replacementBCompleted.countDown()
        }

        try {
            assertEquals(ConflatedWorker.Admission.ACCEPTED, worker.submit(Unit))
            assertTrue(replacementAStarted.await(1, TimeUnit.SECONDS))
            desired.set(replacementB)
            assertEquals(ConflatedWorker.Admission.ACCEPTED, worker.submit(Unit))
            releaseReplacementA.countDown()
            assertTrue(replacementBCompleted.await(1, TimeUnit.SECONDS))
            assertEquals(listOf(replacementA, replacementB), replacements.toList())
            assertEquals(replacementB, active.get())
        } finally {
            releaseReplacementA.countDown()
            worker.close()
        }
    }

    @Test fun localConfigRefreshOnlyRunsTheMqttEffectsWhoseProjectionChanged() {
        val projection = MqttProjectionIdentity("maker", "model", listOf("wake" to true))
        val ha = HaLinkIdentity("http://ha", "access", "refresh", 1L, "client")
        assertEquals(ConfigRefreshEffects(false, false), configRefreshEffects(projection, projection, ha, ha))
        assertEquals(
            ConfigRefreshEffects(true, false),
            configRefreshEffects(projection, projection.copy(exposures = listOf("wake" to false)), ha, ha),
        )
        assertEquals(
            ConfigRefreshEffects(true, false),
            configRefreshEffects(projection, projection.copy(model = "other"), ha, ha),
        )
        assertEquals(
            ConfigRefreshEffects(false, true),
            configRefreshEffects(projection, projection, ha, HaLinkIdentity("http://other", "a", "r", 2L, "c")),
        )
    }

    @Test fun ordinaryStartupRecoveryIsBoundedAndResetsAfterWindowOrClockRollback() {
        assertEquals(StartupRecoveryDecision(true, 1), startupRecoveryDecision(0, 0, 1_000))
        assertEquals(StartupRecoveryDecision(true, 3), startupRecoveryDecision(2, 900, 1_000))
        assertEquals(StartupRecoveryDecision(false, 4), startupRecoveryDecision(3, 900, 1_000))
        assertEquals(StartupRecoveryDecision(true, 1), startupRecoveryDecision(9, 1_000, 700_001))
        assertEquals(StartupRecoveryDecision(true, 1), startupRecoveryDecision(9, 2_000, 1_000))
    }

    @Test fun networkRuntimeIdentityIgnoresLocalSettingsButIncludesConnectionAndAdvertisementValues() {
        fun identity(
            panel: String = "panel",
            friendly: String = "Panel",
            broker: String = "tcp://ha.local:1883",
            user: String = "user",
            password: String = "secret",
        ) = NetworkRuntimeIdentity(panel, friendly, 8888, broker, user, password)

        assertEquals(identity(), identity())
        assertFalse(identity() == identity(panel = "other"))
        assertFalse(identity() == identity(friendly = "Other"))
        assertFalse(identity() == identity(broker = "ssl://ha.local:8883"))
        assertFalse(identity() == identity(user = "other"))
        assertFalse(identity() == identity(password = "other"))
        assertEquals("NetworkRuntimeIdentity(redacted)", identity().toString())
    }

    @Test fun successfulOrdinaryStartupBecomesRunning() {
        assertEquals(
            ServiceStartupDisposition.RUNNING,
            awaitServiceStartup(CompletableFuture.completedFuture(true), profileActivationGeneration = null),
        )
    }

    @Test fun failedOrdinaryStartupBecomesDegraded() {
        assertEquals(
            ServiceStartupDisposition.DEGRADED,
            awaitServiceStartup(CompletableFuture.completedFuture(false), profileActivationGeneration = null),
        )
    }

    @Test fun failedProfileActivationStillRequestsRollback() {
        assertEquals(
            ServiceStartupDisposition.PROFILE_ACTIVATION_ROLLBACK,
            awaitServiceStartup(CompletableFuture.completedFuture(false), profileActivationGeneration = 41L),
        )
    }

    @Test fun exceptionalOrdinaryStartupBecomesDegraded() {
        val startup = CompletableFuture<Boolean>().apply {
            completeExceptionally(IllegalStateException("bind failed"))
        }

        assertEquals(
            ServiceStartupDisposition.DEGRADED,
            awaitServiceStartup(startup, profileActivationGeneration = null),
        )
    }

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

    @Test fun processRestartReconstructsCompanionGateUntilStatusIsAffirmativelySafe() {
        val pkg = "io.homeassistant.companion.android.minimal"
        for (status in CompanionOperationStatus.entries) {
            val dir = Files.createTempDirectory("companion-startup-test").toFile()
            val operationState = CompanionDataOperationState.forTest(
                DurableRecoveryMarker(dir.resolve("pending")),
            )
            assertTrue(operationState.arm())
            var retained: CompanionDataOperationGate.Lease? = null

            assertEquals(
                status,
                restoreCompanionLaunchSuppression(
                    packageName = pkg,
                    operationState = operationState,
                    operationStatus = { status },
                    retain = { retained = it },
                ),
            )

            val unsafe = status == CompanionOperationStatus.BUSY ||
                status == CompanionOperationStatus.UNSUPPORTED ||
                status == CompanionOperationStatus.UNAVAILABLE
            assertEquals("$status gate state", unsafe, CompanionDataOperationGate.blocks(pkg))
            assertEquals("$status retention", unsafe, retained != null)
            assertEquals("$status marker state", unsafe, operationState.isPending())
            retained?.close()
            assertFalse(CompanionDataOperationGate.blocks(pkg))
            dir.deleteRecursively()
        }
    }

    @Test fun ordinaryNoMarkerNoHelperStartupDoesNotAcquireCompanionGate() {
        val pkg = "io.homeassistant.companion.android"
        val dir = Files.createTempDirectory("companion-startup-test").toFile()
        val operationState = CompanionDataOperationState.forTest(
            DurableRecoveryMarker(dir.resolve("pending")),
        )
        var statusProbed = false
        var retained: CompanionDataOperationGate.Lease? = null
        assertEquals(
            CompanionOperationStatus.IDLE,
            restoreCompanionLaunchSuppression(
                pkg,
                operationState,
                { statusProbed = true; CompanionOperationStatus.UNAVAILABLE },
                { retained = it },
            ),
        )
        assertFalse(statusProbed)
        assertFalse(CompanionDataOperationGate.blocks(pkg))
        assertEquals(null, retained)
        dir.deleteRecursively()
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

    @Test fun unchangedRuntimeReplaysDeferredLiveSettingBeforeRefresh() {
        val events = mutableListOf<String>()

        replayThenRefreshLiveConfiguration(
            replay = { events += "replay" },
            refresh = { events += "refresh" },
        )

        assertEquals(listOf("replay", "refresh"), events)
    }

    @Test fun networkReturnRetriesBlankBrokerDiscoveryButNotTerminalOrAuthStates() {
        assertEquals(
            NetworkAvailableAction.RETRY_DISCOVERY,
            networkAvailableAction("discovering", configuredBroker = ""),
        )
        assertEquals(
            NetworkAvailableAction.RECONNECT,
            networkAvailableAction("reconnecting", configuredBroker = "tcp://ha:1883"),
        )
        assertEquals(NetworkAvailableAction.NONE, networkAvailableAction("connected", ""))
        assertEquals(NetworkAvailableAction.NONE, networkAvailableAction("disabled", ""))
        assertEquals(NetworkAvailableAction.NONE, networkAvailableAction("auth-retrying", "tcp://ha:1883"))
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
