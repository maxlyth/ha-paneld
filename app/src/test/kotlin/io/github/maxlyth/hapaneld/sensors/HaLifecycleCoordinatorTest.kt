package io.github.maxlyth.hapaneld.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaLifecycleCoordinatorTest {
    private var now = 0L
    private val seen = mutableListOf<Int>()

    private fun coordinator() = HaLifecycleCoordinator(
        lifecycle = HaLifecycle(backOnlineWindowMs = 8_000L),
        nowMs = { now },
        onChanged = { seen += 1 },
    )

    private fun phase(c: HaLifecycleCoordinator, p: HaExactEntityStreamPhase) =
        c.onSignal(HaLifecycleSignal.Transport(p))

    private fun event(c: HaLifecycleCoordinator, e: HaLifecycleEvent) =
        c.onSignal(HaLifecycleSignal.Event(e))

    @Test fun liveProvesTheServerIsRunningAndClearsAnOutage() {
        val c = coordinator()
        event(c, HaLifecycleEvent.STOP)
        phase(c, HaExactEntityStreamPhase.RECONNECTING)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, c.snapshot().state)

        now = 30_000
        phase(c, HaExactEntityStreamPhase.LIVE)
        assertEquals(HaLifecycleState.BACK_ONLINE, c.snapshot().state)
    }

    @Test fun midHandshakePhasesAreNotTreatedAsADisconnect() {
        val c = coordinator()
        listOf(
            HaExactEntityStreamPhase.AUTHENTICATING,
            HaExactEntityStreamPhase.CONNECTING,
            HaExactEntityStreamPhase.SUBSCRIBING,
            HaExactEntityStreamPhase.SYNCHRONIZING,
        ).forEach { phase(c, it) }
        assertEquals(
            "an ordinary reconnect handshake must not be reported as a fault",
            HaLifecycleState.NORMAL,
            c.snapshot().state,
        )
    }

    @Test fun everyTerminalTransportPhaseCountsAsALostConnection() {
        listOf(
            HaExactEntityStreamPhase.RECONNECTING,
            HaExactEntityStreamPhase.AUTH_FAILED,
            HaExactEntityStreamPhase.STOPPED,
            HaExactEntityStreamPhase.DISABLED,
        ).forEach { terminal ->
            now = 0
            seen.clear()
            val c = coordinator()
            phase(c, terminal)
            assertEquals("$terminal must report a lost connection", HaLifecycleState.CONNECTION_LOST, c.snapshot().state)
        }
    }

    @Test fun aShutdownSurvivesTheDisconnectThatFollowsIt() {
        val c = coordinator()
        event(c, HaLifecycleEvent.STOP)
        phase(c, HaExactEntityStreamPhase.RECONNECTING)
        phase(c, HaExactEntityStreamPhase.AUTHENTICATING)
        phase(c, HaExactEntityStreamPhase.CONNECTING)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, c.snapshot().state)
    }

    // ---- the source names an observer, never a guess ----------------------------------------------

    @Test fun aLocallyNoticedConnectionLossCarriesNoSource() {
        val c = coordinator()
        phase(c, HaExactEntityStreamPhase.RECONNECTING)
        val snap = c.snapshot()
        assertEquals(HaLifecycleState.CONNECTION_LOST, snap.state)
        assertNull("nobody observed this state; naming a source would invent an observation", snap.source)
    }

    @Test fun theInitialNormalCarriesNoSource() {
        assertNull("nothing has been observed yet", coordinator().snapshot().source)
    }

    @Test fun anAuthenticatedRecoveryIsAttributedToTheSocketThatProvedIt() {
        val c = coordinator()
        c.onMqttStatus(HaLifecycleEvent.STOP)
        assertEquals(HaLifecycleSource.MQTT, c.snapshot().source)
        phase(c, HaExactEntityStreamPhase.LIVE)
        val snap = c.snapshot()
        assertEquals(HaLifecycleState.BACK_ONLINE, snap.state)
        assertEquals(
            "the proof was a fresh authenticated socket, so the notice carries its name",
            HaLifecycleSource.SOCKET,
            snap.source,
        )
    }

    /**
     * The notification carries no payload, so what matters is that consumers are woken exactly once per
     * distinct state and can then read the current value themselves.
     */
    @Test fun theListenerIsWokenOncePerDistinctStateAndReadsTheRest() {
        val c = coordinator()
        event(c, HaLifecycleEvent.STOP)
        event(c, HaLifecycleEvent.FINAL_WRITE)
        event(c, HaLifecycleEvent.CLOSE)
        assertEquals("three shutdown stages are one user-visible state", 1, seen.size)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, c.snapshot().state)

        event(c, HaLifecycleEvent.START)
        event(c, HaLifecycleEvent.STARTED)
        assertEquals(3, seen.size)
        assertEquals(HaLifecycleState.BACK_ONLINE, c.snapshot().state)
    }

    @Test fun repeatedIdenticalSignalsDoNotRenotifyTheListener() {
        val c = coordinator()
        repeat(5) { phase(c, HaExactEntityStreamPhase.RECONNECTING) }
        assertEquals(1, seen.size)
        assertEquals(HaLifecycleState.CONNECTION_LOST, c.snapshot().state)
    }

    @Test fun rejectionIsSurfacedWithoutClaimingAnOutageButDoesNotify() {
        val c = coordinator()
        c.onSignal(HaLifecycleSignal.Rejected)
        val snap = c.snapshot()
        assertTrue(snap.refused)
        assertEquals("learning nothing is not an outage", HaLifecycleState.NORMAL, snap.state)
        // The refusal changes the diagnostics wording, so pollers of the rendered snapshot must be
        // woken — suppressing this left the row describing a route Home Assistant had refused.
        assertEquals(1, seen.size)
    }

    // ---- a refusal describes ONE session ----------------------------------------------------------

    /**
     * A demand or credential change REPLACES the socket session without any disconnect phase — the old
     * job is simply cancelled. The successor announces itself by issuing its own subscriptions, and
     * from that moment the recorded refusal describes a session that no longer answers; keeping it
     * would tell the new user they were refused when they were never asked.
     */
    @Test fun aReplacedSessionsRefusalDoesNotOutliveItIntoTheSuccessor() {
        val c = coordinator()
        c.onSignal(HaLifecycleSignal.Rejected)
        assertTrue(c.snapshot().refused)

        phase(c, HaExactEntityStreamPhase.SUBSCRIBING)
        val snap = c.snapshot()
        assertFalse("the successor session answers for itself", snap.refused)
        assertEquals("clearing a refusal is a wording change and must notify", 2, seen.size)
    }

    @Test fun aRefusedSessionsDisconnectClearsTheRefusalEvenIfTheSuccessorNeverSubscribes() {
        val c = coordinator()
        c.onSignal(HaLifecycleSignal.Rejected)
        phase(c, HaExactEntityStreamPhase.RECONNECTING)
        assertFalse(
            "a successor stuck before SUBSCRIBING still stops claiming the refusal",
            c.snapshot().refused,
        )
    }

    /**
     * Hiding is not retiring. A socket-sourced outage has only socket clearers — `homeassistant_started`
     * and a fresh authenticated LIVE both arrive on the connection that just stopped being watched — so
     * if the watch is switched off and later back on, and the reconnect fails, the panel would resurface
     * an outage from the PREVIOUS era as though it were current.
     */
    @Test fun disablingTheSocketWatchRetiresSocketSourcedStateRatherThanHidingIt() {
        val c = coordinator()
        event(c, HaLifecycleEvent.STOP)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, c.snapshot().state)

        c.onSocketWatchStopped()
        val retired = c.snapshot()
        assertEquals("the claim is gone, not merely unrendered", HaLifecycleState.NORMAL, retired.state)
        assertNull(retired.source)

        // Re-enabled, with a reconnect that never succeeds: nothing may reappear from the old era.
        phase(c, HaExactEntityStreamPhase.CONNECTING)
        phase(c, HaExactEntityStreamPhase.RECONNECTING)
        assertEquals(
            "a failed reconnect reports generic loss, never the retired shutdown",
            HaLifecycleState.CONNECTION_LOST,
            c.snapshot().state,
        )
    }

    @Test fun disablingTheSocketWatchLeavesAnMqttSourcedClaimAlone() {
        val c = coordinator()
        c.onMqttStatus(HaLifecycleEvent.STOP)
        c.onSocketWatchStopped()
        assertEquals(
            "MQTT still has its own channel and its own clearers",
            HaLifecycleState.SHUTTING_DOWN,
            c.snapshot().state,
        )
        assertEquals(HaLifecycleSource.MQTT, c.snapshot().source)
    }

    @Test fun replacingTheBrokerChannelRetiresItsClaimButNotTheSocketsFileNote() {
        val c = coordinator()
        c.onMqttStatus(HaLifecycleEvent.STOP)
        c.onMqttChannelRetired()
        assertEquals(HaLifecycleState.NORMAL, c.snapshot().state)

        val other = coordinator()
        event(other, HaLifecycleEvent.STOP)
        other.onMqttChannelRetired()
        assertEquals(
            "a socket claim survives a broker generation change",
            HaLifecycleState.SHUTTING_DOWN,
            other.snapshot().state,
        )
    }

    @Test fun switchingTheWatchOffRetiresTheRefusalWithIt() {
        val c = coordinator()
        c.onSignal(HaLifecycleSignal.Rejected)
        seen.clear()
        c.onSocketWatchStopped()
        assertFalse("no session is being watched, so no refusal describes one", c.snapshot().refused)
        assertEquals(1, seen.size)
    }

    /**
     * The state does not move but the WORDING does: the broker could only say "gone offline", and the
     * socket then proves intent. Deduplicating on state alone suppressed this exact upgrade.
     */
    @Test fun aStrongerSourceForTheSameStateRenotifiesSoWordingCanUpgrade() {
        val c = coordinator()
        c.onMqttStatus(HaLifecycleEvent.STOP)
        assertEquals(1, seen.size)
        assertEquals(HaLifecycleSource.MQTT, c.snapshot().source)

        event(c, HaLifecycleEvent.STOP)
        assertEquals("same state, stronger source, must renotify", 2, seen.size)
        val snap = c.snapshot()
        assertEquals(HaLifecycleSource.SOCKET, snap.source)
        assertEquals(HaLifecycleState.SHUTTING_DOWN, snap.state)
    }

    /**
     * The two clauses of the publish decision each prevent a distinct suppression defect. Going
     * backwards by revision lets an older snapshot overwrite a newer key, after which the next REAL
     * transition back to the newer value is swallowed as a duplicate; renotifying identical renderings
     * turns every poll into a repaint.
     */
    @Test fun thePublishDecisionNeverGoesBackwardsAndNeverRepeatsItself() {
        val older = HaLifecycle.Snapshot(HaLifecycleState.SHUTTING_DOWN, HaLifecycleSource.MQTT, false, 3L, 0L)
        val newer = HaLifecycle.Snapshot(HaLifecycleState.BACK_ONLINE, HaLifecycleSource.MQTT, false, 4L, 8_000L)
        assertTrue(lifecyclePublishDecision(newer, older))
        assertFalse("an older revision must lose, even though it renders differently",
            lifecyclePublishDecision(older, newer))
        assertFalse("a rendering-identical snapshot must not renotify",
            lifecyclePublishDecision(newer.copy(revision = 9L), newer))
    }

    @Test fun theRemainingLifetimeIsNotPartOfTheRenderingIdentity() {
        val early = HaLifecycle.Snapshot(HaLifecycleState.BACK_ONLINE, HaLifecycleSource.SOCKET, false, 4L, 8_000L)
        val late = early.copy(revision = 5L, backOnlineRemainingMs = 1_000L)
        assertFalse(
            "a notice merely aging is not a new fact and must not renotify",
            lifecyclePublishDecision(late, early),
        )
    }

    @Test fun aTornInterleavingCannotSuppressTheLaterRealTransition() {
        // The concrete failure the ordering rule prevents: MQTT and socket callbacks race, the older
        // snapshot wins the lock last, and the machine's CURRENT state can no longer be published
        // because the stale key matches it. With snapshots captured inside the lock and revisions
        // refusing to go backwards, the last publication always reflects the machine's final word.
        val c = coordinator()
        c.onMqttStatus(HaLifecycleEvent.STOP)
        event(c, HaLifecycleEvent.STOP)     // stronger source, same state
        c.onMqttStatus(HaLifecycleEvent.STOP) // late MQTT duplicate must not downgrade the key
        assertEquals(HaLifecycleSource.SOCKET, c.snapshot().source)
        event(c, HaLifecycleEvent.STARTED)
        assertEquals("the final transition must have been announced", 3, seen.size)
        assertEquals(HaLifecycleState.BACK_ONLINE, c.snapshot().state)
    }

    @Test fun anMqttChannelLossDowngradesAndNotifiesThroughTheCoordinator() {
        val c = coordinator()
        c.onMqttStatus(HaLifecycleEvent.STOP)
        assertEquals(1, seen.size)
        c.onMqttChannelLost()
        val snap = c.snapshot()
        assertEquals(HaLifecycleState.CONNECTION_LOST, snap.state)
        assertNull("the claimant can no longer retract, so the state stops carrying its name", snap.source)
        assertEquals("the downgrade is a visible change and must poke consumers", 2, seen.size)
    }

    @Test fun theBackOnlineNoticeRetiresOnceItsWindowPasses() {
        val c = coordinator()
        event(c, HaLifecycleEvent.STARTED)
        assertEquals(HaLifecycleState.BACK_ONLINE, c.snapshot().state)
        now = 9_000
        assertEquals(HaLifecycleState.NORMAL, c.snapshot().state)
    }

    @Test fun theSnapshotCarriesTheRemainingLifetimeOfTheNoticeItDescribes() {
        val c = coordinator()
        event(c, HaLifecycleEvent.STARTED)
        now = 3_000
        val snap = c.snapshot()
        assertEquals(HaLifecycleState.BACK_ONLINE, snap.state)
        assertEquals(
            "the lifetime comes from the SAME read as the state it belongs to",
            5_000L,
            snap.backOnlineRemainingMs,
        )
    }
}
