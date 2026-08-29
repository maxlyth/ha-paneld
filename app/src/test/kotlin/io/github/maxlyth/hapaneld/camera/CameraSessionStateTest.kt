package io.github.maxlyth.hapaneld.camera

import io.github.maxlyth.hapaneld.camera.CameraSessionState.Admission
import io.github.maxlyth.hapaneld.camera.CameraSessionState.Failure
import io.github.maxlyth.hapaneld.camera.CameraSessionState.Phase
import java.util.concurrent.CompletableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every interleaving two rounds of review found, pinned: the first lease closes the session it opened, a
 * subscriber joining mid-open shares its outcome, a disable ends the session synchronously so a quick
 * re-enable starts a real attempt and every waiting caller is settled, a callback from a superseded
 * attempt is recognised by identity, a reopen gets its own attempt and its own first-frame grace, an
 * open that fails climbs the ladder while subscribers remain and is backed off across sessions when
 * none do, waiters are independent, and a stop settles everything.
 */
class CameraSessionStateTest {

    private val policy = CameraSessionPolicy(frameIntervalMs = 66L, maxConsecutiveFailures = 3)
    private val state = CameraSessionState { policy }

    /** A future that must already be settled; an unsettled one fails the test instead of blocking it. */
    private fun <T> settled(future: CompletableFuture<T>, why: String): T {
        assertTrue(why, future.isDone)
        return future.get()
    }

    /** Asserts rather than casts: a session that wrongly stays open answers Join here, and that must fail. */
    private fun open(nowMs: Long = 1_000L): Admission.Open {
        val admission = state.acquire(gate = null, nowMs = nowMs)
        assertTrue("expected a fresh open, got $admission", admission is Admission.Open)
        return admission as Admission.Open
    }

    @Test fun theFirstLeaseOpensAndItsReleaseClosesTheSessionItOpened() {
        val first = open()
        assertEquals(Phase.OPENING, state.phase)
        assertTrue(state.isCurrent(first.attempt))
        assertTrue(state.openSucceeded(first.attempt))
        assertEquals(Phase.LIVE, state.phase)
        assertTrue("the last lease must release the hardware", state.release(first.lease))
        assertEquals("the phase changes at the decision, not when hardware trails", Phase.IDLE, state.phase)
        assertNull(state.currentAttempt)
        assertEquals(0, state.clients)
    }

    @Test fun aSecondSubscriberJoinsTheOpenInFlightSharesItsOutcomeAndTheLastOneOutCloses() {
        val first = open()
        val joined = state.acquire(gate = null, nowMs = 1_001L)
        assertTrue("expected to join the open in flight, got $joined", joined is Admission.Join)
        val second = joined as Admission.Join
        val wait = requireNotNull(state.awaitOpen())
        assertEquals(2, state.clients)
        assertTrue(state.openSucceeded(first.attempt))
        assertNull(settled(wait, "the joiner learns the open succeeded"))
        assertFalse("not the last lease", state.release(first.lease))
        assertTrue(state.release(second.lease))
    }

    @Test fun theStaticGatesRefuseWithoutTakingALease() {
        assertEquals(Admission.Refused(CameraRefusal.DISABLED), state.acquire(gate = CameraRefusal.DISABLED, nowMs = 0L))
        assertEquals(0, state.clients)
        assertEquals(Phase.IDLE, state.phase)
    }

    @Test fun disablingEndsTheSessionAtOnceSettlesWaitersAndAQuickReenableStartsARealAttempt() {
        val first = open()
        val waiting = requireNotNull(state.awaitOpen())
        val frame = CompletableFuture<ByteArray?>()
        state.addWaiter(frame)
        assertTrue("hardware must be released", state.disable())
        assertEquals("no joinable phase is left behind", Phase.IDLE, state.phase)
        assertEquals(CameraRefusal.DISABLED, settled(waiting, "an open waiter is settled, not left to time out"))
        assertTrue("frame waiters are settled too", frame.isDone)
        assertFalse("the staled attempt's success is refused", state.openSucceeded(first.attempt))
        assertTrue(state.frame(first.attempt, 2_000L).isEmpty())
        // Re-enable before the old hardware has even been released: a brand-new attempt, never a join.
        val again = state.acquire(gate = null, nowMs = 2_001L)
        assertTrue("$again", again is Admission.Open)
        assertNotEquals(first.attempt, (again as Admission.Open).attempt)
        assertTrue(state.isCurrent(again.attempt))
        assertFalse("the old attempt stays stale", state.isCurrent(first.attempt))
    }

    @Test fun aCallbackFromASupersededAttemptIsRecognisedByIdentity() {
        val first = open()
        assertEquals(Failure.Reopen(1_000L, 1), state.openFailed(first.attempt, CameraFault.OPEN, CameraRefusal.FAILED, 1_100L))
        assertNull("between failure and the fired reopen no attempt is current", state.currentAttempt)
        val second = requireNotNull(state.reopenAttempt(nowMs = 2_100L))
        assertNotEquals(first.attempt, second)
        assertEquals("the reopen restarts the first-frame grace", 2_100L, state.openedAtMs)
        // The old attempt's late callbacks are ignored in every form.
        assertEquals(Failure.Ignored, state.openFailed(first.attempt, CameraFault.DEVICE_ERROR, CameraRefusal.FAILED, 2_200L))
        assertFalse(state.noteDeviceFault(first.attempt, CameraFault.DISCONNECTED))
        assertFalse(state.openSucceeded(first.attempt))
        assertTrue(state.openSucceeded(second))
        assertEquals(Phase.LIVE, state.phase)
    }

    @Test fun anOpenThatFailsWithSubscribersWaitingClimbsTheLadderThenDegrades() {
        val first = open()
        val waiting = requireNotNull(state.awaitOpen())
        assertEquals(Failure.Reopen(1_000L, 1), state.openFailed(first.attempt, CameraFault.OPEN, CameraRefusal.FAILED, 1_000L))
        assertEquals(CameraRefusal.FAILED, settled(waiting, "the waiting caller learns the refusal at once"))
        assertEquals("the lease survives the reopen", 1, state.clients)
        val second = requireNotNull(state.reopenAttempt(2_000L))
        assertEquals(Failure.Reopen(2_000L, 2), state.openFailed(second, CameraFault.OPEN, CameraRefusal.FAILED, 2_000L))
        val third = requireNotNull(state.reopenAttempt(4_000L))
        assertEquals(Failure.Degrade(3), state.openFailed(third, CameraFault.OPEN, CameraRefusal.FAILED, 4_000L))
        assertEquals(Phase.DEGRADED, state.phase)
        assertEquals(0, state.clients)
    }

    @Test fun failureMemoryOutlivesTheSessionSoAPollerIsBackedOffAndThenDegraded() {
        // Poll 1: the open fails while the caller is still waiting; it learns the refusal and leaves.
        val first = open(nowMs = 0L)
        assertEquals(Failure.Reopen(1_000L, 1), state.openFailed(first.attempt, CameraFault.OPEN, CameraRefusal.FAILED, 0L))
        assertTrue("the departing caller ends the session", state.release(first.lease))
        assertEquals(Phase.IDLE, state.phase)
        assertEquals("the failure is remembered across the session", 1, state.consecutiveFailures)
        assertEquals("and so is the backoff", 1_000L, state.retryNotBeforeMs)
        assertEquals(Admission.Refused(CameraRefusal.FAILED), state.acquire(gate = null, nowMs = 500L))
        // Poll 2 after the backoff: attempt 2, fails, backoff doubles.
        val second = open(nowMs = 1_000L)
        assertEquals(Failure.Reopen(2_000L, 2), state.openFailed(second.attempt, CameraFault.OPEN, CameraRefusal.FAILED, 1_000L))
        assertTrue(state.release(second.lease))
        assertEquals(3_000L, state.retryNotBeforeMs)
        // Poll 3: the ceiling — degraded, visibly, and held there for the maximum backoff.
        val third = open(nowMs = 3_000L)
        assertEquals(Failure.Degrade(3), state.openFailed(third.attempt, CameraFault.OPEN, CameraRefusal.FAILED, 3_000L))
        assertFalse("degrading already ended the session", state.release(third.lease))
        assertEquals(Phase.DEGRADED, state.phase)
        assertEquals(3_000L + policy.maxBackoffMs, state.retryNotBeforeMs)
        assertEquals(Admission.Refused(CameraRefusal.FAILED), state.acquire(gate = null, nowMs = 10_000L))
        // After the hold a subscriber retries with a fresh count, as promised.
        val retry = state.acquire(gate = null, nowMs = 3_000L + policy.maxBackoffMs)
        assertTrue("$retry", retry is Admission.Open)
        assertEquals(0, state.consecutiveFailures)
    }

    @Test fun anOpenThatFailsWithNobodyWaitingClosesTheSession() {
        val first = open(nowMs = 0L)
        assertTrue(state.release(first.lease))
        assertEquals(Failure.Ignored, state.openFailed(first.attempt, CameraFault.OPEN, CameraRefusal.FAILED, 1L))
        val again = open(nowMs = 5_000L)
        state.release(again.lease)
        assertEquals(Phase.IDLE, state.phase)
    }

    @Test fun concurrentSnapshotsGetIndependentWaitersAndOneFrameSatisfiesAll() {
        val first = open()
        assertTrue(state.openSucceeded(first.attempt))
        val a = CompletableFuture<ByteArray?>()
        val b = CompletableFuture<ByteArray?>()
        state.addWaiter(a)
        state.addWaiter(b)
        assertEquals(listOf(a, b), state.frame(first.attempt, 1_500L))
        assertEquals(1_500L, state.lastFrameAtMs)
        assertTrue("the queue drains", state.frame(first.attempt, 1_600L).isEmpty())
    }

    @Test fun aFrameFromAnEarlierAttemptNeverTouchesTheCurrentSession() {
        val first = open()
        assertTrue(state.openSucceeded(first.attempt))
        assertTrue(state.release(first.lease))
        val second = open(nowMs = 5_000L)
        assertTrue(state.openSucceeded(second.attempt))
        assertTrue(state.frame(first.attempt, 5_000L).isEmpty())
        assertNull(state.lastFrameAtMs)
    }

    @Test fun losingIndicationIsAFaultTheTickReportsAndAStaleTickIsIgnored() {
        val first = open()
        assertTrue(state.openSucceeded(first.attempt))
        state.frame(first.attempt, 1_100L)
        val decision = state.tick(first.attempt, nowMs = 1_200L, enabled = true, indicated = false)
        assertTrue("$decision", decision is CameraSessionPolicy.Decision.Reopen && decision.fault == CameraFault.INDICATION)
        assertEquals(CameraSessionPolicy.Decision.Continue, state.tick(first.attempt, 1_300L, enabled = true, indicated = true))
        assertNull("a tick for another attempt decides nothing", state.tick(first.attempt + 1, 1_300L, enabled = true, indicated = true))
    }

    @Test fun aReopenDecidedForALiveSessionCannotBeAppliedAfterADisableEndedIt() {
        // The decision/apply gap: the tick decides Reopen, then a disable lands before it is applied.
        val first = open(nowMs = 1_000L)
        assertTrue(state.openSucceeded(first.attempt))
        state.frame(first.attempt, 1_100L)
        val decision = state.tick(first.attempt, nowMs = 9_000L, enabled = true, indicated = true)
        assertTrue("$decision", decision is CameraSessionPolicy.Decision.Reopen)
        assertTrue(state.disable())
        assertFalse("a reopen for an ended session is refused", state.reopening(first.attempt, 1))
        assertEquals("and changes nothing", Phase.IDLE, state.phase)
        assertNull(state.currentAttempt)
        assertNull("nothing to fire", state.reopenAttempt(nowMs = 10_000L))
    }

    @Test fun aReopenOrDegradeDecidedBeforeAStopOrTheLastLeaseLeavingIsRefused() {
        val first = open(nowMs = 1_000L)
        assertTrue(state.openSucceeded(first.attempt))
        assertTrue(state.release(first.lease))
        assertEquals(Phase.IDLE, state.phase)
        assertFalse(state.reopening(first.attempt, 1))
        assertFalse(state.degraded(first.attempt, 3, 2_000L))
        assertEquals(Phase.IDLE, state.phase)
        val fresh = CameraSessionState { policy }
        val again = fresh.acquire(gate = null, nowMs = 0L) as Admission.Open
        assertTrue(fresh.openSucceeded(again.attempt))
        assertTrue(fresh.stopping())
        assertFalse("a stopped session stays stopped", fresh.reopening(again.attempt, 1))
        assertFalse(fresh.degraded(again.attempt, 3, 1L))
        assertEquals(Phase.STOPPING, fresh.phase)
    }

    @Test fun aReopenOrDegradeIsOnlyForTheCurrentAttempt() {
        val first = open(nowMs = 1_000L)
        assertTrue(state.openSucceeded(first.attempt))
        assertFalse("a superseded attempt id cannot move a live session", state.reopening(first.attempt + 7, 1))
        assertFalse(state.degraded(first.attempt + 7, 3, 2_000L))
        assertEquals(Phase.LIVE, state.phase)
        assertTrue(state.reopening(first.attempt, 1))
    }

    @Test fun aLiveReopenKeepsTheLeasesEndsTheAttemptAndTheFiredAttemptGetsFreshGrace() {
        val first = open(nowMs = 1_000L)
        assertTrue(state.openSucceeded(first.attempt))
        assertTrue(state.reopening(first.attempt, attempt = 1))
        assertEquals(Phase.OPENING, state.phase)
        assertEquals(1, state.clients)
        assertNull(state.currentAttempt)
        val fired = requireNotNull(state.reopenAttempt(nowMs = 9_000L))
        assertEquals(9_000L, state.openedAtMs)
        assertNull("no stale last-frame time survives into the new attempt", state.lastFrameAtMs)
        assertTrue(state.openSucceeded(fired))
    }

    @Test fun stoppingSettlesEverythingRefusesNewLeasesAndReportsWhetherHardwareWasHeld() {
        assertFalse("nothing to release when idle", state.stopping())
        val fresh = CameraSessionState { policy }
        val first = fresh.acquire(gate = null, nowMs = 0L) as Admission.Open
        val waiting = requireNotNull(fresh.awaitOpen())
        assertTrue("hardware was held", fresh.stopping())
        assertEquals(CameraRefusal.STOPPING, settled(waiting, "stopping settles the waiting caller"))
        assertFalse(fresh.openSucceeded(first.attempt))
        assertEquals(Admission.Refused(CameraRefusal.STOPPING), fresh.acquire(gate = null, nowMs = 1L))
        assertEquals(0, fresh.clients)
    }

    @Test fun awaitOpenIsOnlyOfferedWhileAnOpenIsInFlight() {
        assertNull(state.awaitOpen())
        val first = open()
        assertNotNull(state.awaitOpen())
        state.openSucceeded(first.attempt)
        assertNull("nothing to wait for once live", state.awaitOpen())
    }
}
