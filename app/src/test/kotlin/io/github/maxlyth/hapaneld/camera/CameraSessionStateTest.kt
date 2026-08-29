package io.github.maxlyth.hapaneld.camera

import io.github.maxlyth.hapaneld.camera.CameraSessionState.Admission
import io.github.maxlyth.hapaneld.camera.CameraSessionState.Failure
import io.github.maxlyth.hapaneld.camera.CameraSessionState.Phase
import io.github.maxlyth.hapaneld.camera.CameraSessionState.Release
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
 * none do, waiters are independent, and a stop settles everything. The stream cases add the encoder's
 * ownership: it is wanted exactly while a stream lease exists, bound by the first stream lease, kept
 * across a reopen, dropped with every other subscriber when the session ends, and held off after it
 * fails without touching a snapshot.
 */
class CameraSessionStateTest {

    private val policy = CameraSessionPolicy(frameIntervalMs = 66L, maxConsecutiveFailures = 3)
    private val state = CameraSessionState { policy }
    private val binding = StreamBinding(fps = 15, kbps = 2_000)

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

    private fun openStream(nowMs: Long = 1_000L, binding: StreamBinding = this.binding): Admission.Open {
        val admission = state.acquire(gate = null, nowMs = nowMs, kind = LeaseKind.STREAM, binding = binding)
        assertTrue("expected a fresh open, got $admission", admission is Admission.Open)
        return admission as Admission.Open
    }

    private fun joinStream(nowMs: Long, binding: StreamBinding = this.binding): Admission.Join {
        val admission = state.acquire(gate = null, nowMs = nowMs, kind = LeaseKind.STREAM, binding = binding)
        assertTrue("expected to join, got $admission", admission is Admission.Join)
        return admission as Admission.Join
    }

    @Test fun theFirstLeaseOpensAndItsReleaseClosesTheSessionItOpened() {
        val first = open()
        assertEquals(Phase.OPENING, state.phase)
        assertTrue(state.isCurrent(first.attempt))
        assertTrue(state.openSucceeded(first.attempt))
        assertEquals(Phase.LIVE, state.phase)
        assertEquals("the last lease must release the hardware", Release.Close, state.release(first.lease))
        assertEquals("the phase changes at the decision, not when hardware trails", Phase.IDLE, state.phase)
        assertNull(state.currentAttempt)
        assertEquals(0, state.clients)
    }

    @Test fun aSecondSubscriberJoinsTheOpenInFlightSharesItsOutcomeAndTheLastOneOutCloses() {
        val first = open()
        val joined = state.acquire(gate = null, nowMs = 1_001L)
        assertTrue("expected to join the open in flight, got $joined", joined is Admission.Join)
        val second = joined as Admission.Join
        assertFalse("a snapshot never starts the encoder", second.startEncoder)
        val wait = requireNotNull(state.awaitOpen())
        assertEquals(2, state.clients)
        assertTrue(state.openSucceeded(first.attempt))
        assertNull(settled(wait, "the joiner learns the open succeeded"))
        assertEquals("not the last lease", Release.None, state.release(first.lease))
        assertEquals(Release.Close, state.release(second.lease))
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
        assertNull("a frame from the staled attempt must not be delivered", state.frame(first.attempt, 2_000L))
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
        assertEquals("the departing caller ends the session", Release.Close, state.release(first.lease))
        assertEquals(Phase.IDLE, state.phase)
        assertEquals("the failure is remembered across the session", 1, state.consecutiveFailures)
        assertEquals("and so is the backoff", 1_000L, state.retryNotBeforeMs)
        assertEquals(Admission.Refused(CameraRefusal.FAILED), state.acquire(gate = null, nowMs = 500L))
        // Poll 2 after the backoff: attempt 2, fails, backoff doubles.
        val second = open(nowMs = 1_000L)
        assertEquals(Failure.Reopen(2_000L, 2), state.openFailed(second.attempt, CameraFault.OPEN, CameraRefusal.FAILED, 1_000L))
        assertEquals(Release.Close, state.release(second.lease))
        assertEquals(3_000L, state.retryNotBeforeMs)
        // Poll 3: the ceiling — degraded, visibly, and held there for the maximum backoff.
        val third = open(nowMs = 3_000L)
        assertEquals(Failure.Degrade(3), state.openFailed(third.attempt, CameraFault.OPEN, CameraRefusal.FAILED, 3_000L))
        assertEquals("degrading already ended the session", Release.None, state.release(third.lease))
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
        assertEquals(Release.Close, state.release(first.lease))
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
        assertEquals("the queue drains but the frame is still live", emptyList<CompletableFuture<ByteArray?>>(), state.frame(first.attempt, 1_600L))
    }

    @Test fun aFrameFromAnEarlierAttemptNeverTouchesTheCurrentSession() {
        val first = open()
        assertTrue(state.openSucceeded(first.attempt))
        assertEquals(Release.Close, state.release(first.lease))
        val second = open(nowMs = 5_000L)
        assertTrue(state.openSucceeded(second.attempt))
        assertNull(state.frame(first.attempt, 5_000L))
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
        assertEquals(Release.Close, state.release(first.lease))
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

    // ---- the ladder after a successful open ------------------------------------------------------------

    @Test fun aFaultThatRecursAfterEverySuccessfulOpenStillReachesTheCeiling() {
        val first = openStream()
        assertTrue(state.openSucceeded(first.attempt))
        // No frame ever arrives; the device faults after each open.
        assertTrue(state.noteDeviceFault(first.attempt, CameraFault.DEVICE_ERROR))
        assertEquals(CameraSessionPolicy.Decision.Reopen(1_000L, CameraFault.DEVICE_ERROR, 1), state.tick(first.attempt, 2_000L, enabled = true, indicated = true))
        assertTrue(state.reopening(first.attempt, 1))
        val second = requireNotNull(state.reopenAttempt(3_000L))
        assertTrue(state.openSucceeded(second))
        assertEquals("a successful open is not proof; only a frame is", 1, state.consecutiveFailures)
        assertTrue(state.noteDeviceFault(second, CameraFault.DEVICE_ERROR))
        assertEquals(CameraSessionPolicy.Decision.Reopen(2_000L, CameraFault.DEVICE_ERROR, 2), state.tick(second, 4_000L, enabled = true, indicated = true))
        assertTrue(state.reopening(second, 2))
        val third = requireNotNull(state.reopenAttempt(6_000L))
        assertTrue(state.openSucceeded(third))
        assertTrue(state.noteDeviceFault(third, CameraFault.DEVICE_ERROR))
        assertEquals(
            "bounded: the ceiling is reached, not an endless cycle",
            CameraSessionPolicy.Decision.Degrade(CameraFault.DEVICE_ERROR, 3),
            state.tick(third, 7_000L, enabled = true, indicated = true),
        )
    }

    @Test fun aDeliveredFrameIsWhatResetsTheLadder() {
        val first = open()
        assertEquals(Failure.Reopen(1_000L, 1), state.openFailed(first.attempt, CameraFault.OPEN, CameraRefusal.FAILED, 1_000L))
        assertEquals(1, state.consecutiveFailures)
        val second = requireNotNull(state.reopenAttempt(2_000L))
        assertTrue(state.openSucceeded(second))
        assertEquals("still counting until a frame proves the session", 1, state.consecutiveFailures)
        assertNotNull(state.frame(second, 3_000L))
        assertEquals(0, state.consecutiveFailures)
    }

    // ---- stream leases -----------------------------------------------------------------------------

    @Test fun aStreamLeaseFirstOpensTheSessionAndWantsTheEncoderOnceLive() {
        val first = openStream()
        assertEquals(Phase.OPENING, state.phase)
        assertTrue("wanted from the moment the lease exists", state.encoderWanted)
        assertEquals(binding, state.streamBinding)
        assertTrue(state.openSucceeded(first.attempt))
        assertTrue("the owner starts the encoder on the successful open", state.encoderWanted)
        assertEquals(1, state.streamClients)
    }

    @Test fun aStreamJoiningALiveSnapshotSessionStartsTheEncoderOnceAndLaterStreamsJoinItsBinding() {
        val snapshot = open()
        assertTrue(state.openSucceeded(snapshot.attempt))
        assertFalse(state.encoderWanted)
        val stream = joinStream(2_000L)
        assertTrue("first stream lease on a live session starts the encoder now", stream.startEncoder)
        assertEquals(binding, state.streamBinding)
        val second = joinStream(2_100L, StreamBinding(fps = 5, kbps = 500))
        assertFalse("the encoder is already running; the second stream joins it", second.startEncoder)
        assertEquals("the first client's parameters win", binding, state.streamBinding)
        assertEquals(2, state.streamClients)
        assertEquals(3, state.clients)
    }

    @Test fun theLastStreamLeaseStopsTheEncoderWhileASnapshotLeaseKeepsTheSession() {
        val snapshot = open()
        assertTrue(state.openSucceeded(snapshot.attempt))
        val stream = joinStream(2_000L)
        assertEquals("the snapshot lease still holds the hardware", Release.StopEncoder, state.release(stream.lease))
        assertFalse(state.encoderWanted)
        assertNull("the binding leaves with the last stream lease", state.streamBinding)
        assertEquals(Phase.LIVE, state.phase)
        assertEquals("now the last lease of any kind", Release.Close, state.release(snapshot.lease))
    }

    @Test fun theLastLeaseOfAnyKindClosesTheSessionEvenWhenItIsAStream() {
        val stream = openStream()
        assertTrue(state.openSucceeded(stream.attempt))
        assertEquals(Release.Close, state.release(stream.lease))
        assertNull(state.streamBinding)
        assertFalse(state.encoderWanted)
        assertEquals(Phase.IDLE, state.phase)
    }

    @Test fun aStreamJoiningMidOpenStartsWithTheOpenNotBeforeIt() {
        val snapshot = open()
        val stream = joinStream(1_001L)
        assertFalse("nothing to start on a session that is still opening", stream.startEncoder)
        assertTrue(state.openSucceeded(snapshot.attempt))
        assertTrue("the successful open finds the encoder wanted", state.encoderWanted)
        assertEquals(binding, state.streamBinding)
    }

    @Test fun aReopenKeepsTheStreamLeasesAndTheirBindingSoTheEncoderRestartsWithTheSession() {
        val stream = openStream()
        assertTrue(state.openSucceeded(stream.attempt))
        state.frame(stream.attempt, 1_100L)
        val decision = state.tick(stream.attempt, nowMs = 9_000L, enabled = true, indicated = true)
        assertTrue("$decision", decision is CameraSessionPolicy.Decision.Reopen)
        assertTrue(state.reopening(stream.attempt, 1))
        assertEquals(Phase.OPENING, state.phase)
        assertEquals("the subscriber is the same", 1, state.streamClients)
        assertEquals(binding, state.streamBinding)
        val fired = requireNotNull(state.reopenAttempt(10_000L))
        assertTrue(state.openSucceeded(fired))
        assertTrue(state.encoderWanted)
    }

    @Test fun degradingOrClosingDropsStreamLeasesAndTheBindingWithEveryOtherSubscriber() {
        val stream = openStream()
        assertTrue(state.openSucceeded(stream.attempt))
        assertTrue(state.degraded(stream.attempt, 3, nowMs = 5_000L))
        assertEquals(0, state.streamClients)
        assertNull(state.streamBinding)
        assertFalse(state.encoderWanted)
        val again = openStream(nowMs = 5_000L + policy.maxBackoffMs)
        assertTrue(state.openSucceeded(again.attempt))
        assertTrue(state.disable())
        assertEquals(0, state.clients)
        assertNull(state.streamBinding)
        assertEquals("a late release after the close is a no-op", Release.None, state.release(again.lease))
    }

    @Test fun aFailedEncoderHoldsStreamLeasesOffForTheBackoffButNeverASnapshot() {
        state.encoderFailed(nowMs = 10_000L)
        assertEquals(
            Admission.Refused(CameraRefusal.STREAM_ENCODER),
            state.acquire(gate = null, nowMs = 20_000L, kind = LeaseKind.STREAM, binding = binding),
        )
        assertEquals("the camera is not what failed", 0, state.clients)
        val snapshot = state.acquire(gate = null, nowMs = 20_000L)
        assertTrue("$snapshot", snapshot is Admission.Open)
        val afterHold = state.acquire(gate = null, nowMs = 40_000L, kind = LeaseKind.STREAM, binding = binding)
        assertTrue("$afterHold", afterHold is Admission.Join)
        assertNull("one admitted attempt clears the hold", state.encoderHoldUntilMs)
    }

    @Test fun aStreamLeaseMustCarryABinding() {
        val failed = runCatching { state.acquire(gate = null, nowMs = 0L, kind = LeaseKind.STREAM, binding = null) }
        assertTrue(failed.exceptionOrNull() is IllegalArgumentException)
        assertEquals("no lease was taken", 0, state.clients)
    }

    @Test fun aLiveFrameWithNoSnapshotWaiterIsStillLiveForTheEncoder() {
        val stream = openStream()
        assertTrue(state.openSucceeded(stream.attempt))
        val delivered = state.frame(stream.attempt, 1_500L)
        assertEquals("live, nobody waiting for a JPEG", emptyList<CompletableFuture<ByteArray?>>(), delivered)
        assertEquals(1_500L, state.lastFrameAtMs)
    }
}
