package io.github.maxlyth.hapaneld.dashboard

import io.github.maxlyth.hapaneld.util.ByteLimitExceeded
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InterruptedIOException
import java.io.File
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

class EntityLearningManagerSafetyTest {
    @Test fun disabledAutomaticLearningIsStartupInertUntilExplicitDemand() {
        assertFalse(shouldInitializeEntityLearningOnStart(enabled = false))
        assertTrue(shouldInitializeEntityLearningOnStart(enabled = true))

        val source = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/dashboard/EntityLearningManager.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/dashboard/EntityLearningManager.kt"),
        ).first(File::isFile).readText()
        val start = source.substring(
            source.indexOf("fun start()"),
            source.indexOf("private fun applyDefaultResolverMigration"),
        )
        assertTrue(
            "disabled startup must return before catalog preparation, migration, diagnostics, or queries",
            start.indexOf("shouldInitializeEntityLearningOnStart") < start.indexOf("ensureInitialized()"),
        )
        assertTrue("periodic work must have explicit owned cancellation", "cancelPeriodicSyncLocked()" in source)
        assertTrue(
            "a cancelled periodic read must recheck admission before it can launch a new sync",
            "if (!isActive || !config.dashboardEntityLearningEnabled) continue" in source,
        )
        val disable = source.substring(source.indexOf("fun setEnabled"), source.indexOf("/** Explicitly promote"))
        assertTrue(
            "disable must cancel periodic work before returning",
            disable.indexOf("cancelPeriodicSyncLocked()") < disable.lastIndexOf("onFilterChanged()"),
        )
    }

    @Test fun stateProjectionBoundsFriendlyName() {
        val limits = HaStatesReadLimits(maxFriendlyNameChars = 18)
        assertEquals(
            EntityCatalogStore.StateRow("binary_sensor.window", "on", "Upper Window Group"),
            validateHaStateRow("binary_sensor.window", "on", 1, limits, "Upper Window Group"),
        )
        assertThrows(IllegalStateException::class.java) {
            validateHaStateRow("sensor.room", "on", 1, limits, "A friendly name that is too long")
        }
    }

    @Test fun stateResponseBoundsAcceptProjectionAndRejectOversizePayloadRowsAndFields() {
        val limits = HaStatesReadLimits(maxBytes = 8, maxRows = 1, maxEntityIdChars = 16, maxStateChars = 4)
        assertEquals(
            EntityCatalogStore.StateRow("sensor.room", "21.5"),
            validateHaStateRow("sensor.room", "21.5", 1, limits),
        )
        assertThrows(ByteLimitExceeded::class.java) {
            DeadlineBoundedInputStream(ByteArrayInputStream(ByteArray(9)), 8, 1_000) { 0 }.readBytes()
        }
        assertThrows(IllegalStateException::class.java) {
            validateHaStateRow("sensor.two", "off", 2, limits)
        }
        assertThrows(IllegalStateException::class.java) {
            validateHaStateRow("sensor.identifier_too_long", "on", 1, limits)
        }
        assertThrows(IllegalStateException::class.java) {
            validateHaStateRow("sensor.room", "state-too-long", 1, limits)
        }
    }

    @Test fun stateResponseStreamEnforcesMonotonicReadDeadline() {
        var now = 0L
        assertThrows(SocketTimeoutException::class.java) {
            DeadlineBoundedInputStream(
                ByteArrayInputStream(byteArrayOf(1)), 16, 1,
                nanoTime = { (now++ * 2_000_000L) },
            ).read()
        }
    }

    @Test fun stateResponseStreamHonorsThreadInterruption() {
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) {
                DeadlineBoundedInputStream(ByteArrayInputStream(byteArrayOf(1)), 16, 1_000) { 0 }.read()
            }
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun stateResponseByteCountingDoesNotRetainAnotherPayloadCopy() {
        val payload = """[{"entity_id":"sensor.room","state":"21","attributes":{}}]""".toByteArray()
        val counted = CountingInputStream(ByteArrayInputStream(payload))
        assertEquals(1, readHaStates(counted).size)
        assertEquals(payload.size.toLong(), counted.bytesRead)
    }

    @Test fun streamingStateReaderHandlesEscapesNumbersAndNestedSkippedValues() {
        val payload = """[{"ignored":{"nested":[1,true,null,{"value":"discard"}]},"attributes":{"unit":"C","friendly_name":"Room \u2603"},"state":21.5,"entity_id":"sensor.room"}]"""

        assertEquals(
            listOf(EntityCatalogStore.StateRow("sensor.room", "21.5", "Room ☃")),
            readHaStates(ByteArrayInputStream(payload.toByteArray())),
        )
    }

    @Test fun streamingStateReaderRejectsOversizeFieldsAndTrailingDocuments() {
        val limits = HaStatesReadLimits(maxEntityIdChars = 8)
        assertThrows(IllegalStateException::class.java) {
            readHaStates(ByteArrayInputStream("""[{"entity_id":"sensor.identifier_too_long","state":"on"}]""".toByteArray()), limits)
        }
        assertThrows(IllegalStateException::class.java) {
            readHaStates(ByteArrayInputStream("[]{}".toByteArray()))
        }
    }

    private fun state(
        origin: String = "https://ha.example",
        instanceKey: String = "instance-a",
        targetKey: String = "instance-a:dashboard-a",
        dashboardPath: String = "/dashboard-a",
        credentialFingerprint: String = "credential-a",
        learningEnabled: Boolean = true,
        applied: Boolean = true,
        filterEnabled: Boolean = true,
        filterIds: List<String> = listOf("light.a"),
        autoStatic: Boolean = true,
        autoRuntime: Boolean = true,
        overrides: Map<String, String> = emptyMap(),
        forceBootstrap: Boolean = false,
    ) = EntityLearningEffectState(
        origin, instanceKey, targetKey, dashboardPath, credentialFingerprint,
        learningEnabled, applied, filterEnabled, filterIds, autoStatic, autoRuntime,
        overrides, forceBootstrap,
    )

    @Test fun syncSnapshotRejectsEveryTargetPolicyAndCredentialChange() {
        val original = state()
        val snapshot = EntityLearningSyncSnapshot(7, "https://ha.example", "token", original)

        assertTrue(snapshot.matchesCurrent(7, original))
        assertFalse(snapshot.matchesCurrent(8, original))
        assertFalse(snapshot.matchesCurrent(7, original.copy(origin = "https://other.example")))
        assertFalse(snapshot.matchesCurrent(7, original.copy(instanceKey = "instance-b")))
        assertFalse(snapshot.matchesCurrent(7, original.copy(targetKey = "instance-a:dashboard-b")))
        assertFalse(snapshot.matchesCurrent(7, original.copy(dashboardPath = "/dashboard-b")))
        assertFalse(snapshot.matchesCurrent(7, original.copy(credentialFingerprint = "credential-b")))
        assertFalse(snapshot.matchesCurrent(7, original.copy(autoRuntime = false)))
        assertFalse(snapshot.matchesCurrent(7, original.copy(filterIds = listOf("light.b"))))
        assertFalse(snapshot.matchesCurrent(7, original.copy(forceBootstrap = true)))
    }

    @Test fun effectMutationCancelsAndReleasesTheActiveSyncSlot() {
        val active = Job()

        val replacementSlot = supersedeEntityLearningSync(active)

        assertTrue(active.isCancelled)
        assertEquals(null, replacementSlot)
    }

    @Test fun firstEnableInvalidatesOldEffectsBeforeStartingExactlyOneBootstrapSync() {
        val events = mutableListOf<String>()
        val obsolete = Job()
        var ownedSync: Job? = obsolete
        var initializationCalls = 0
        var bootstrapStarts = 0

        val newlyInitialized = initializeEntityLearningAfterEffectInvalidation(
            invalidate = {
                events += "invalidate"
                ownedSync = supersedeEntityLearningSync(ownedSync)
            },
            initialize = {
                initializationCalls++
                events += "initialize"
                ownedSync = Job()
                bootstrapStarts++
                true
            },
        )
        if (!newlyInitialized) bootstrapStarts++

        assertTrue(newlyInitialized)
        assertEquals(1, initializationCalls)
        assertEquals("lazy initialization owns the only required bootstrap start", 1, bootstrapStarts)
        assertEquals(listOf("invalidate", "initialize"), events)
        assertTrue(obsolete.isCancelled)
        assertTrue("the initialization-owned bootstrap sync must remain active", ownedSync?.isActive == true)
    }

    @Test fun supersededScanCannotPersistFailureOntoCurrentGeneration() {
        assertTrue(shouldPersistEntityLearningFailure(7, 7, "instance-a", "/dash", "instance-a", "/dash"))
        assertFalse(shouldPersistEntityLearningFailure(7, 8, "instance-a", "/dash", "instance-a", "/dash"))
        assertFalse(shouldPersistEntityLearningFailure(7, 7, "instance-a", "/dash", "instance-b", "/dash"))
        assertFalse(shouldPersistEntityLearningFailure(7, 7, "instance-a", "/dash", "instance-a", "/other"))
    }

    @Test fun telemetryQueuedBeforeResetCannotWriteAfterInvalidation() {
        val generation = AtomicLong(3)
        val barrier = EntityTelemetryWriteBarrier(generation::get)
        val admittedGeneration = generation.get()
        var wrote = false

        barrier.invalidateAndWrite(invalidate = { generation.incrementAndGet() }) {}

        assertFalse(barrier.writeIfCurrent(admittedGeneration) { wrote = true })
        assertFalse(wrote)
    }

    @Test fun resetWaitsForAnActiveTelemetryWriteThenClearsIt() {
        val generation = AtomicLong(8)
        val barrier = EntityTelemetryWriteBarrier(generation::get)
        val evidence = mutableListOf<String>()
        val writeEntered = CountDownLatch(1)
        val releaseWrite = CountDownLatch(1)
        val resetAttempted = CountDownLatch(1)
        val resetFinished = CountDownLatch(1)

        val writer = thread(start = true) {
            barrier.writeIfCurrent(generation.get()) {
                writeEntered.countDown()
                releaseWrite.await()
                evidence += "old"
            }
        }
        assertTrue(writeEntered.await(2, TimeUnit.SECONDS))
        val resetter = thread(start = true) {
            resetAttempted.countDown()
            barrier.invalidateAndWrite(
                invalidate = { generation.incrementAndGet() },
                write = { evidence.clear() },
            )
            resetFinished.countDown()
        }

        assertTrue(resetAttempted.await(2, TimeUnit.SECONDS))
        assertFalse(resetFinished.await(50, TimeUnit.MILLISECONDS))
        releaseWrite.countDown()
        writer.join(2_000)
        resetter.join(2_000)

        assertFalse(writer.isAlive)
        assertFalse(resetter.isAlive)
        assertTrue(evidence.isEmpty())
    }

    @Test fun concurrentDisableCannotBeOvertakenByAnAlreadyRunningActivation() {
        data class DurableState(
            var learningEnabled: Boolean = true,
            var filterEnabled: Boolean = false,
            var applied: Boolean = false,
        )

        val owner = Any()
        val state = DurableState()
        val activationEntered = CountDownLatch(1)
        val releaseActivation = CountDownLatch(1)
        val disableAttempted = CountDownLatch(1)
        val disableFinished = CountDownLatch(1)

        val activation = thread(start = true) {
            withEntityLearningMutationLock(owner) {
                activationEntered.countDown()
                releaseActivation.await()
                state.filterEnabled = true
                state.applied = true
            }
        }
        assertTrue(activationEntered.await(2, TimeUnit.SECONDS))
        val disable = thread(start = true) {
            disableAttempted.countDown()
            withEntityLearningMutationLock(owner) {
                state.learningEnabled = false
                state.filterEnabled = false
                state.applied = false
            }
            disableFinished.countDown()
        }

        assertTrue(disableAttempted.await(2, TimeUnit.SECONDS))
        assertFalse("disable must wait for the active mutation", disableFinished.await(50, TimeUnit.MILLISECONDS))
        releaseActivation.countDown()
        activation.join(2_000)
        disable.join(2_000)

        assertFalse(activation.isAlive)
        assertFalse(disable.isAlive)
        assertFalse(state.learningEnabled)
        assertFalse(state.filterEnabled)
        assertFalse(state.applied)
    }

    @Test fun queuedPromotionRequiresUnchangedEligiblePolicyAndNoBlockers() {
        val original = state()
        val queued = EntityLearningPromotionSnapshot(11, original)

        assertTrue(queued.isEligible(11, original, blockingIssues = 0))
        assertFalse(queued.isEligible(12, original, blockingIssues = 0))
        assertFalse(queued.isEligible(11, original.copy(learningEnabled = false), blockingIssues = 0))
        assertFalse(queued.isEligible(11, original.copy(applied = false), blockingIssues = 0))
        assertFalse(queued.isEligible(11, original.copy(autoRuntime = false), blockingIssues = 0))
        assertFalse(queued.isEligible(11, original.copy(targetKey = "instance-b:dashboard-a"), blockingIssues = 0))
        assertFalse(queued.isEligible(11, original, blockingIssues = 1))
    }

    @Test fun quietSuspendingOperationCannotOutliveWallClockDeadline() = runTest {
        var timedOut = false
        try {
            withEntityLearningDeadline(1_000) { awaitCancellation() }
        } catch (_: TimeoutCancellationException) {
            timedOut = true
        }
        assertTrue(timedOut)
    }

    @Test fun overridePreferenceFailureNeverTouchesTheCatalog() {
        val events = mutableListOf<String>()
        val failed = runCatching {
            runEntityOverrideTransaction(
                commitOverridePreferences = { events += "prefs-failed"; false },
                applyStoreOverride = { events += "store" },
                commitActiveFilter = { events += "filter"; true },
                restoreStoreOverride = { events += "store-rollback" },
                restoreOverridePreferences = { events += "prefs-rollback"; true },
            )
        }.isFailure

        assertTrue(failed)
        assertEquals(listOf("prefs-failed"), events)
    }

    @Test fun activeFilterFailureRollsBackBothOverrideStoresAndCannotReportSuccess() {
        val events = mutableListOf<String>()
        val failed = runCatching {
            runEntityOverrideTransaction(
                commitOverridePreferences = { events += "prefs"; true },
                applyStoreOverride = { events += "store" },
                commitActiveFilter = { events += "filter-failed"; false },
                restoreStoreOverride = { events += "store-rollback" },
                restoreOverridePreferences = { events += "prefs-rollback"; true },
            )
        }.isFailure

        assertTrue(failed)
        assertEquals(
            listOf("prefs", "store", "filter-failed", "store-rollback", "prefs-rollback"),
            events,
        )
    }

    @Test fun successfulOverrideTransactionDoesNotRunRollback() {
        val events = mutableListOf<String>()
        runEntityOverrideTransaction(
            commitOverridePreferences = { events += "prefs"; true },
            applyStoreOverride = { events += "store" },
            commitActiveFilter = { events += "filter"; true },
            restoreStoreOverride = { events += "store-rollback" },
            restoreOverridePreferences = { events += "prefs-rollback"; true },
        )
        assertEquals(listOf("prefs", "store", "filter"), events)
    }

    @Test fun authenticatedHaConfigAddsBoundedValidIdentityAliases() {
        assertEquals(
            listOf(
                "https://configured.example/ha",
                "http://homeassistant.local:8123",
                "https://public.example/ha",
            ),
            haInstanceCandidateUrls(
                "https://configured.example/ha",
                """{
                  "internal_url":"http://homeassistant.local:8123",
                  "external_url":"https://public.example/ha",
                  "base_url":"javascript:alert(1)"
                }""",
            ),
        )
    }

    @Test fun configuredIdentityCandidateSurvivesMissingMalformedOrDuplicateConfig() {
        val configured = "https://configured.example"
        assertEquals(listOf(configured), haInstanceCandidateUrls(configured, null))
        assertEquals(listOf(configured), haInstanceCandidateUrls(configured, "not-json"))
        assertEquals(
            listOf(configured),
            haInstanceCandidateUrls(
                configured,
                """{"internal_url":"$configured","external_url":"","base_url":"ftp://invalid.example"}""",
            ),
        )
        assertEquals(
            listOf(configured),
            haInstanceCandidateUrls(
                configured,
                """{"internal_url":"https://oversized.example/${"a".repeat(3_000)}"}""",
            ),
        )
    }
}
