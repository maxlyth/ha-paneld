package io.github.maxlyth.hapaneld.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CdpRelayStateTest {
    @Test fun acceptedLaunchIsNotRunningWhenChildProbeFails() {
        val state = RelayProcessState { false }
        assertFalse(state.start { true })
        assertFalse(state.running())
    }

    @Test fun processDeathInvalidatesAnEarlierSuccessfulStart() {
        var alive = true
        val state = RelayProcessState { alive }
        assertTrue(state.start { true })
        assertTrue(state.running())
        alive = false
        assertFalse(state.running())
    }

    @Test fun stopClearsStateEvenWhenTerminationReportsFailure() {
        val state = RelayProcessState { true }
        assertTrue(state.start { true })
        assertFalse(state.stop { false })
        assertFalse(state.running())
    }
}
