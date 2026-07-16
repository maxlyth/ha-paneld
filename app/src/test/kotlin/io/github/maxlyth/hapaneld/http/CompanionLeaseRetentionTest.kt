package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.restoreCompanionLaunchSuppression
import io.github.maxlyth.hapaneld.control.CompanionDataOperationGate
import io.github.maxlyth.hapaneld.control.CompanionDataOperationState
import io.github.maxlyth.hapaneld.util.CompanionOperationStatus
import io.github.maxlyth.hapaneld.util.DurableRecoveryMarker
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CompanionLeaseRetentionTest {
    private val companionPackage = "io.homeassistant.companion.android"

    @Test
    fun markedProcessRestartUnavailableThenBusyThenIdleRetainsAndClearsSuppression() = runTest {
        val (operationState, directory) = armedOperationState()
        var retainedLease: CompanionDataOperationGate.Lease? = null

        assertEquals(
            CompanionOperationStatus.UNAVAILABLE,
            restoreCompanionLaunchSuppression(
                packageName = companionPackage,
                operationState = operationState,
                operationStatus = { CompanionOperationStatus.UNAVAILABLE },
                retain = { retainedLease = it },
            ),
        )
        assertTrue(CompanionDataOperationGate.blocks(companionPackage))

        val statuses = ArrayDeque(
            listOf(CompanionOperationStatus.BUSY, CompanionOperationStatus.IDLE),
        )
        var releases = 0
        val retention = launch {
            retainCompanionLeaseUntilHelperIdle(
                lease = requireNotNull(retainedLease),
                operationState = operationState,
                afterRelease = { releases++ },
                operationStatus = { statuses.removeFirst() },
                pollMs = 1_000L,
            )
        }
        runCurrent()
        assertTrue(CompanionDataOperationGate.blocks(companionPackage))
        assertTrue(operationState.isPending())

        advanceTimeBy(1_000L)
        runCurrent()
        retention.join()

        assertFalse(CompanionDataOperationGate.blocks(companionPackage))
        assertFalse(operationState.isPending())
        assertEquals(1, releases)
        assertTrue(statuses.isEmpty())
        directory.deleteRecursively()
    }

    @Test
    fun unavailableThreeTimesThenBusyKeepsLaunchSuppressedUntilIdle() = runTest {
        val statuses = ArrayDeque(
            listOf(
                CompanionOperationStatus.UNAVAILABLE,
                CompanionOperationStatus.UNAVAILABLE,
                CompanionOperationStatus.UNAVAILABLE,
                CompanionOperationStatus.BUSY,
                CompanionOperationStatus.IDLE,
            ),
        )
        val lease = requireNotNull(CompanionDataOperationGate.acquire(companionPackage))
        val (operationState, directory) = armedOperationState()
        var releases = 0
        val retention = launch {
            retainCompanionLeaseUntilHelperIdle(
                lease = lease,
                operationState = operationState,
                afterRelease = { releases++ },
                operationStatus = { statuses.removeFirst() },
                pollMs = 1_000L,
            )
        }

        runCurrent()
        repeat(2) {
            assertTrue(CompanionDataOperationGate.blocks(companionPackage))
            assertEquals(0, releases)
            advanceTimeBy(1_000L)
            runCurrent()
        }

        // The former heuristic released here after the third unavailable probe.
        assertTrue(CompanionDataOperationGate.blocks(companionPackage))
        assertEquals(0, releases)
        advanceTimeBy(1_000L)
        runCurrent()

        assertTrue(CompanionDataOperationGate.blocks(companionPackage))
        assertEquals(0, releases)
        advanceTimeBy(1_000L)
        runCurrent()

        assertFalse(CompanionDataOperationGate.blocks(companionPackage))
        assertEquals(1, releases)
        assertTrue(statuses.isEmpty())
        assertFalse(operationState.isPending())
        retention.join()
        directory.deleteRecursively()
    }

    @Test
    fun legacyDaemonCannotClearMarkerLeftByANewerHelper() = runTest {
        val statuses = ArrayDeque(
            listOf(
                CompanionOperationStatus.UNAVAILABLE,
                CompanionOperationStatus.UNSUPPORTED,
                CompanionOperationStatus.IDLE,
            ),
        )
        val lease = requireNotNull(CompanionDataOperationGate.acquire(companionPackage))
        val (operationState, directory) = armedOperationState()
        var releases = 0
        val retention = launch {
            retainCompanionLeaseUntilHelperIdle(
                lease = lease,
                operationState = operationState,
                afterRelease = { releases++ },
                operationStatus = { statuses.removeFirst() },
                pollMs = 1_000L,
            )
        }

        runCurrent()
        assertTrue(CompanionDataOperationGate.blocks(companionPackage))
        assertEquals(0, releases)

        advanceTimeBy(1_000L)
        runCurrent()

        assertTrue(CompanionDataOperationGate.blocks(companionPackage))
        assertEquals(0, releases)
        assertTrue(operationState.isPending())

        advanceTimeBy(1_000L)
        runCurrent()

        assertFalse(CompanionDataOperationGate.blocks(companionPackage))
        assertEquals(1, releases)
        assertTrue(statuses.isEmpty())
        assertFalse(operationState.isPending())
        retention.join()
        directory.deleteRecursively()
    }

    @Test
    fun affirmativeNeverStartedOutcomeClearsMarkerWithoutWaitingForUnavailableHelper() {
        val (operationState, directory) = armedOperationState()
        val lease = requireNotNull(CompanionDataOperationGate.acquire(companionPackage))
        var retained = false
        var released = false

        val transferred = settleCompanionOperationLease(
            lease = lease,
            operationState = operationState,
            possiblyInFlight = false,
            retain = { _, _ -> retained = true },
            afterRelease = { released = true },
        )

        assertFalse(transferred)
        assertFalse(retained)
        assertTrue(released)
        assertFalse(operationState.isPending())
        assertFalse(CompanionDataOperationGate.blocks(companionPackage))
        directory.deleteRecursively()
    }

    private fun armedOperationState(): Pair<CompanionDataOperationState, java.io.File> {
        val directory = Files.createTempDirectory("companion-operation-test").toFile()
        val state = CompanionDataOperationState.forTest(
            DurableRecoveryMarker(directory.resolve("pending")),
        )
        assertTrue(state.arm())
        return state to directory
    }
}
