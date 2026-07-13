package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.control.DashboardRecoveryPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardRecoveryPolicyTest {
    private fun policy() = DashboardRecoveryPolicy(
        deadStreakLimit = 2,
        backgroundTimeoutMs = 300,
        crashLoop = CrashLoopTracker(maxRelaunches = 2, windowMs = 1_000, backoffMs = 5_000),
    )

    @Test fun deadProcessNeedsConsecutiveEvidenceAndThenUsesCrashBudget() {
        val policy = policy()
        assertEquals(Action.NONE, policy.evaluate("a", AppState.DEAD, 0).action)
        assertEquals(Action.RELAUNCH_DEAD, policy.evaluate("a", AppState.DEAD, 1).action)
        assertEquals(Action.NONE, policy.evaluate("a", AppState.DEAD, 2).action)
        assertEquals(Action.RELAUNCH_DEAD, policy.evaluate("a", AppState.DEAD, 3).action)
        assertEquals(Action.NONE, policy.evaluate("a", AppState.DEAD, 4).action)
        val suppressed = policy.evaluate("a", AppState.DEAD, 5)
        assertEquals(Action.NONE, suppressed.action)
        assertTrue(suppressed.crashLooping)
    }

    @Test fun foregroundRecoveryClearsCrashHistoryAndWarning() {
        val policy = policy()
        repeat(6) { policy.evaluate("a", AppState.DEAD, it.toLong()) }
        assertTrue(policy.evaluate("a", AppState.DEAD, 6).crashLooping)
        assertFalse(policy.evaluate("a", AppState.FG, 7).crashLooping)
        assertEquals(Action.NONE, policy.evaluate("a", AppState.DEAD, 8).action)
        assertEquals(Action.RELAUNCH_DEAD, policy.evaluate("a", AppState.DEAD, 9).action)
    }

    @Test fun changingTargetRetiresOldCrashAndBackgroundState() {
        val policy = policy()
        policy.evaluate("old", AppState.BG, 0)
        assertEquals(Action.NONE, policy.evaluate("new", AppState.BG, 500).action)
        assertEquals(Action.RETURN_FROM_BACKGROUND, policy.evaluate("new", AppState.BG, 800).action)

        repeat(6) { policy.evaluate("new", AppState.DEAD, 900 + it.toLong()) }
        assertTrue(policy.evaluate("new", AppState.DEAD, 907).crashLooping)
        val replacement = policy.evaluate("replacement", AppState.DEAD, 908)
        assertFalse(replacement.crashLooping)
        assertEquals(Action.NONE, replacement.action)
    }

    @Test fun unknownProbeBreaksDeadStreakButPreservesBackgroundTimer() {
        val policy = policy()
        policy.evaluate("a", AppState.DEAD, 0)
        policy.evaluate("a", AppState.UNKNOWN, 1)
        assertEquals(Action.NONE, policy.evaluate("a", AppState.DEAD, 2).action)

        policy.evaluate("a", AppState.BG, 10)
        policy.evaluate("a", AppState.UNKNOWN, 200)
        assertEquals(Action.RETURN_FROM_BACKGROUND, policy.evaluate("a", AppState.BG, 310).action)
    }

    @Test fun resetIsATerminalBoundary() {
        val policy = policy()
        repeat(6) { policy.evaluate("a", AppState.DEAD, it.toLong()) }
        policy.reset()
        val first = policy.evaluate("a", AppState.DEAD, 10)
        assertFalse(first.crashLooping)
        assertEquals(Action.NONE, first.action)
    }
}
