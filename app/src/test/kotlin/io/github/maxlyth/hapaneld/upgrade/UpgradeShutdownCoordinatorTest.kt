package io.github.maxlyth.hapaneld.upgrade

import io.github.maxlyth.hapaneld.persistence.CleanDatabaseProof
import io.github.maxlyth.hapaneld.persistence.StateQuiescence
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UpgradeShutdownCoordinatorTest {
    @Test fun nonceIsExactlyThirtyTwoLowercaseHexCharacters() {
        assertEquals("0123456789abcdef0123456789abcdef", canonicalUpgradeNonce("0123456789abcdef0123456789abcdef"))
        assertNull(canonicalUpgradeNonce("0123456789ABCDEF0123456789ABCDEF"))
        assertNull(canonicalUpgradeNonce("0123456789abcdef0123456789abcde"))
        assertNull(canonicalUpgradeNonce("0123456789abcdef0123456789abcdeg"))
        assertNull(canonicalUpgradeNonce(" 0123456789abcdef0123456789abcdef"))
    }

    @Test fun readyWireResultIsExactAndContainsStableDatabaseEvidence() {
        val proof = CleanDatabaseProof(
            databaseBytes = 4096,
            sha256 = "ab".repeat(32),
            userVersion = 14,
            appStateRows = 133,
        )

        assertEquals(
            "HAPANELD_UPGRADE_READY_V1:0123456789abcdef0123456789abcdef:321:522:4096:" +
                "${"ab".repeat(32)}:14:133",
            formatUpgradeReady("0123456789abcdef0123456789abcdef", 321, 522, proof),
        )
        assertEquals(
            "HAPANELD_UPGRADE_RELEASED_V1:0123456789abcdef0123456789abcdef",
            formatUpgradeReleased("0123456789abcdef0123456789abcdef"),
        )
    }

    @Test fun onlyOneRequestCanOwnTheShutdownAndWrongNonceCannotReleaseIt() {
        val gate = UpgradeRequestGate()
        val first = RecordingCompletion()

        assertTrue(gate.arm(NONCE, first))
        assertFalse(gate.arm(OTHER_NONCE, RecordingCompletion()))
        assertFalse(gate.release(OTHER_NONCE).matched)
        assertTrue(gate.release(NONCE).matched)
        assertEquals(listOf("failed:released_before_ready"), first.events)
    }

    @Test fun cleanShutdownTransfersFreezeUntilMatchingRelease() {
        val gate = UpgradeRequestGate()
        val completion = RecordingCompletion()
        val reopened = AtomicBoolean()
        val successorReleased = AtomicBoolean()
        val freeze = StateQuiescence { reopened.set(true) }
        val proof = CleanDatabaseProof(8192, "cd".repeat(32), 14, 27)

        assertTrue(gate.arm(NONCE, completion))
        val claim = checkNotNull(gate.claimShutdown())
        assertTrue(gate.holdReady(claim, freeze, proof) { successorReleased.set(true) })
        assertFalse(reopened.get())
        assertFalse(successorReleased.get())
        assertEquals(listOf("ready:$NONCE"), completion.events)

        val released = gate.release(NONCE)
        assertTrue(released.matched)
        assertSame(freeze, released.freeze)
        released.releaseSuccessor?.invoke()
        released.freeze?.close()
        assertTrue(reopened.get())
        assertTrue(successorReleased.get())
    }

    @Test fun shutdownFailureAndWatchdogCancellationNeverReportReady() {
        val gate = UpgradeRequestGate()
        val completion = RecordingCompletion()

        assertTrue(gate.arm(NONCE, completion))
        val cancelled = gate.cancel(NONCE, "checkpoint_failed")

        assertTrue(cancelled.matched)
        assertNull(cancelled.freeze)
        assertEquals(listOf("failed:checkpoint_failed"), completion.events)
        assertFalse(completion.events.any { it.startsWith("ready:") })
    }

    @Test fun finalizerAlreadyInFlightCannotSatisfyOrCancelANewRequest() {
        val gate = UpgradeRequestGate()
        val completion = RecordingCompletion()
        val unbound = UpgradeShutdownClaim(Any())
        val freeze = StateQuiescence {}
        val proof = CleanDatabaseProof(4096, "ef".repeat(32), 14, 1)

        assertTrue(gate.arm(NONCE, completion))
        assertFalse(gate.holdReady(unbound, freeze, proof) {})
        assertFalse(gate.cancelClaim(unbound, "stale_finalizer").matched)
        assertTrue(completion.events.isEmpty())

        val claimed = checkNotNull(gate.claimShutdown())
        assertTrue(gate.holdReady(claimed, freeze, proof) {})
        assertEquals(listOf("ready:$NONCE"), completion.events)
    }

    @Test fun heldReleaseCompletesFreezeAndPredecessorBeforeRestart() {
        val events = mutableListOf<String>()

        releaseUpgradeHold(
            freeze = StateQuiescence { events += "freeze" },
            releaseSuccessor = { events += "barrier" },
            restartService = { events += "start" },
        )

        assertEquals(listOf("freeze", "barrier", "start"), events)
    }

    @Test fun releaseIsStatelessWhenNoReadyProcessStateSurvives() {
        val gate = UpgradeRequestGate()
        assertTrue(gate.release(NONCE).matched)
        assertTrue(gate.release(NONCE).matched)
    }

    @Test fun releaseAttemptsEveryOrderedStepEvenWhenEachOneThrows() {
        val events = mutableListOf<String>()

        val failures = releaseUpgradeHold(
            freeze = StateQuiescence { events += "freeze"; error("freeze") },
            additionalFreeze = StateQuiescence { events += "additional"; error("additional") },
            releaseSuccessor = { events += "barrier"; error("barrier") },
            restartService = { events += "start"; error("start") },
        )

        assertEquals(listOf("freeze", "additional", "barrier", "start"), events)
        assertEquals(4, failures.size)
    }

    @Test fun statelessReleaseIsTruthfulAboutRestartSuccessAndFailure() {
        val successful = executeUpgradeRelease(UpgradeRequestGate(), NONCE) {}
        assertTrue(successful.succeeded)

        val failed = executeUpgradeRelease(UpgradeRequestGate(), NONCE) { error("start") }
        assertTrue(failed.accepted)
        assertFalse(failed.succeeded)
        assertEquals(1, failed.failures.size)
    }

    @Test fun liveDifferentNonceIsNotReportedAsReleased() {
        val gate = UpgradeRequestGate()
        assertTrue(gate.arm(NONCE, RecordingCompletion()))
        val outcome = executeUpgradeRelease(gate, OTHER_NONCE) {}
        assertFalse(outcome.accepted)
        assertFalse(outcome.succeeded)
    }

    private class RecordingCompletion : UpgradeRequestCompletion {
        val events = mutableListOf<String>()
        override fun ready(nonce: String, proof: CleanDatabaseProof) {
            events += "ready:$nonce"
        }
        override fun failed(reason: String) {
            events += "failed:$reason"
        }
    }

    private companion object {
        const val NONCE = "0123456789abcdef0123456789abcdef"
        const val OTHER_NONCE = "fedcba9876543210fedcba9876543210"
    }
}
