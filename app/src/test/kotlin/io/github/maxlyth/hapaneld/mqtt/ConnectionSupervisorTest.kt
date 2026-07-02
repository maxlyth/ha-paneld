package io.github.maxlyth.hapaneld.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression net over the MQTT watchdog decision — the exact behaviour behind the reconnect incidents,
 * now unit-testable via the ConnectionSupervisor extraction. Times are in the same units the service uses.
 */
class ConnectionSupervisorTest {

    private val stale = 150_000L
    private val abandon = 300_000L
    private fun supervisor() = ConnectionSupervisor(stale, abandon)

    @Test fun connectedIsHealthy() {
        assertEquals(ConnectionSupervisor.Action.None, supervisor().tick("connected", 1_000L, 5_000L, 10_000L, false))
    }

    @Test fun disabledIsNoneEvenWhenApparentlyStale() {
        assertEquals(ConnectionSupervisor.Action.None, supervisor().tick("disabled", 1_000L, stale + 1, 10_000L, false))
    }

    @Test fun livenessStaleForcesRebuild() {
        val a = supervisor().tick("connected", 1_000L, stale + 1, 500_000L, false)
        assertTrue("$a", a is ConnectionSupervisor.Action.Rebuild && a.reason == "liveness")
    }

    @Test fun livenessNotArmedUntilFirstAck() {
        // lastOkMs == 0 => liveness disabled; a connected client with no ACK yet is not "stale"
        assertEquals(ConnectionSupervisor.Action.None, supervisor().tick("connected", 0L, stale + 1, 500_000L, false))
    }

    @Test fun stateStuckForcesRebuildAfterTwoTicks() {
        val s = supervisor()
        assertEquals(ConnectionSupervisor.Action.None, s.tick("reconnecting", 0L, 0L, 1_000L, false))
        val a = s.tick("reconnecting", 0L, 0L, 2_000L, false)
        assertTrue("$a", a is ConnectionSupervisor.Action.Rebuild && a.reason == "state")
    }

    @Test fun connectedResetsTheStuckCounter() {
        val s = supervisor()
        s.tick("reconnecting", 0L, 0L, 1_000L, false) // stuck tick 1
        s.tick("connected", 0L, 0L, 2_000L, false)    // reset
        // only one stuck tick again -> not yet a rebuild
        assertEquals(ConnectionSupervisor.Action.None, s.tick("reconnecting", 0L, 0L, 3_000L, false))
    }

    @Test fun skipsRebuildWhileOneInFlightWithinAbandonWindow() {
        val s = supervisor()
        assertTrue(s.tick("connected", 1_000L, stale + 1, 500_000L, false) is ConnectionSupervisor.Action.Rebuild)
        val a = s.tick("connected", 1_000L, stale + 1, 500_000L + 60_000L, true)
        assertTrue("$a", a is ConnectionSupervisor.Action.SkipRebuild)
    }

    @Test fun abandonsWedgedRebuildAndStartsFresh() {
        val s = supervisor()
        assertTrue(s.tick("connected", 1_000L, stale + 1, 500_000L, false) is ConnectionSupervisor.Action.Rebuild)
        // still in flight, but past the abandon window -> start a fresh rebuild
        val a = s.tick("connected", 1_000L, stale + 1, 500_000L + abandon + 1, true)
        assertTrue("$a", a is ConnectionSupervisor.Action.Rebuild)
    }
}
