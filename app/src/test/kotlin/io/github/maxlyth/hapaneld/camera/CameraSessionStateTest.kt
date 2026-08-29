package io.github.maxlyth.hapaneld.camera

import io.github.maxlyth.hapaneld.camera.CameraSessionState.Admission
import io.github.maxlyth.hapaneld.camera.CameraSessionState.Failure
import io.github.maxlyth.hapaneld.camera.CameraSessionState.Phase
import java.util.concurrent.CompletableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The interleavings the first submission got wrong, each pinned: the first lease closes the session it
 * opened, a subscriber joining mid-open shares its outcome, the master switch stales an in-flight open,
 * an open that fails with subscribers waiting climbs the ladder instead of stopping, waiters are
 * independent, and a stop settles everything.
 */
class CameraSessionStateTest {

    private val policy = CameraSessionPolicy(frameIntervalMs = 66L, maxConsecutiveFailures = 3)
    private val state = CameraSessionState { policy }

    private fun open(): Admission.Open {
        val admission = state.acquire(gate = null, nowMs = 1_000L)
        return admission as Admission.Open
    }

    @Test fun theFirstLeaseOpensAndItsReleaseClosesTheSessionItOpened() {
        val first = open()
        assertEquals(Phase.OPENING, state.phase)
        assertTrue(state.openSucceeded(first.generation))
        assertEquals(Phase.LIVE, state.phase)
        assertTrue("the last lease must close the hardware", state.release(first.lease))
        state.closed(Phase.IDLE)
        assertEquals(Phase.IDLE, state.phase)
        assertEquals(0, state.clients)
    }

    @Test fun aSecondSubscriberJoinsTheOpenInFlightAndTheLastOneOutCloses() {
        val first = open()
        val second = state.acquire(gate = null, nowMs = 1_001L) as Admission.Join
        assertEquals(first.generation, second.generation)
        assertEquals(2, state.clients)
        assertTrue(state.openSucceeded(first.generation))
        assertFalse("not the last lease", state.release(first.lease))
        assertTrue(state.release(second.lease))
    }

    @Test fun theStaticGatesRefuseWithoutTakingALease() {
        val refused = state.acquire(gate = CameraRefusal.DISABLED, nowMs = 0L)
        assertEquals(Admission.Refused(CameraRefusal.DISABLED), refused)
        assertEquals(0, state.clients)
        assertEquals(Phase.IDLE, state.phase)
    }

    @Test fun disablingDuringAnOpenStalesItSoALateSuccessAndFrameAreDropped() {
        val first = open()
        assertTrue("hardware must be released now", state.disable())
        assertFalse("the in-flight open no longer counts", state.openSucceeded(first.generation))
        val waiter = CompletableFuture<ByteArray?>()
        state.addWaiter(waiter)
        assertTrue("a frame from the staled open must not be delivered", state.frame(first.generation, 2_000L).isEmpty())
        state.closed(Phase.IDLE)
        assertTrue("closing settles every waiter", waiter.isDone)
        assertNull(waiter.get())
    }

    @Test fun anOpenThatFailsWithSubscribersWaitingClimbsTheLadderThenDegrades() {
        val first = open()
        val one = state.openFailed(first.generation, CameraFault.OPEN)
        assertEquals(Failure.Reopen(1_000L, 1), one)
        assertEquals(Phase.OPENING, state.phase)
        assertEquals(1, state.clients)
        val two = state.openFailed(first.generation, CameraFault.OPEN)
        assertEquals(Failure.Reopen(2_000L, 2), two)
        val three = state.openFailed(first.generation, CameraFault.OPEN)
        assertEquals(Failure.Degrade(3), three)
        assertEquals(Phase.DEGRADED, state.phase)
    }

    @Test fun anOpenThatFailsWithNobodyWaitingJustCloses() {
        val first = open()
        assertTrue(state.release(first.lease))
        state.closed(Phase.IDLE)
        // A failure for the superseded attempt is ignored; one for a live attempt with no leases closes.
        assertEquals(Failure.Ignored, state.openFailed(first.generation, CameraFault.OPEN))
        val again = open()
        state.release(again.lease)
        assertEquals(Failure.Close, state.openFailed(again.generation, CameraFault.OPEN))
    }

    @Test fun aDegradedSessionRetriesForTheNextSubscriberWithAFreshCount() {
        val first = open()
        repeat(3) { state.openFailed(first.generation, CameraFault.OPEN) }
        assertEquals(Phase.DEGRADED, state.phase)
        state.degraded(3)
        val retry = state.acquire(gate = null, nowMs = 9_000L)
        assertTrue("$retry", retry is Admission.Open)
        assertEquals(0, state.consecutiveFailures)
        assertEquals(Phase.OPENING, state.phase)
    }

    @Test fun concurrentSnapshotsGetIndependentWaitersAndOneFrameSatisfiesAll() {
        val first = open()
        assertTrue(state.openSucceeded(first.generation))
        val a = CompletableFuture<ByteArray?>()
        val b = CompletableFuture<ByteArray?>()
        state.addWaiter(a)
        state.addWaiter(b)
        val ready = state.frame(first.generation, 1_500L)
        assertEquals(listOf(a, b), ready)
        assertEquals(1_500L, state.lastFrameAtMs)
        assertTrue("the queue drains", state.frame(first.generation, 1_600L).isEmpty())
    }

    @Test fun aFrameFromAnEarlierGenerationNeverTouchesTheCurrentSession() {
        val first = open()
        assertTrue(state.openSucceeded(first.generation))
        assertTrue(state.release(first.lease))
        state.closed(Phase.IDLE)
        val second = open()
        assertTrue(state.openSucceeded(second.generation))
        assertTrue(state.frame(first.generation, 5_000L).isEmpty())
        assertNull(state.lastFrameAtMs)
    }

    @Test fun losingIndicationIsAFaultTheTickReports() {
        val first = open()
        assertTrue(state.openSucceeded(first.generation))
        state.frame(first.generation, 1_100L)
        val decision = state.tick(nowMs = 1_200L, enabled = true, indicated = false)
        assertTrue("$decision", decision is CameraSessionPolicy.Decision.Reopen && decision.fault == CameraFault.INDICATION)
        assertEquals(CameraSessionPolicy.Decision.Continue, state.tick(nowMs = 1_300L, enabled = true, indicated = true))
    }

    @Test fun stoppingSettlesWaitersRefusesNewLeasesAndReportsWhetherHardwareWasHeld() {
        assertFalse("nothing to release when idle", state.stopping())
        val fresh = CameraSessionState { policy }
        val first = fresh.acquire(gate = null, nowMs = 0L) as Admission.Open
        fresh.openSucceeded(first.generation)
        val waiter = CompletableFuture<ByteArray?>()
        fresh.addWaiter(waiter)
        assertTrue("hardware was held", fresh.stopping())
        fresh.closed(Phase.STOPPING)
        assertTrue(waiter.isDone)
        assertEquals(Admission.Refused(CameraRefusal.STOPPING), fresh.acquire(gate = null, nowMs = 1L))
        assertEquals(0, fresh.clients)
    }
}
