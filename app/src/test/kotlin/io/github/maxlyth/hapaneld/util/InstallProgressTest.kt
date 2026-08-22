package io.github.maxlyth.hapaneld.util

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class InstallProgressTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun cancelledBeforeLaunchCannotStrandOrOverwriteProgress() = runTest {
        val first = InstallProgress.start("first")
        assertNotNull(first)
        assertTrue(InstallProgress.running)
        assertNull(InstallProgress.start("overlap"))

        val job = launch { error("must not run") }
        InstallProgress.finishOnFailure(first!!, job)
        job.cancel()
        testScheduler.runCurrent()
        assertFalse(InstallProgress.running)
        assertEquals("cancelled", InstallProgress.message)

        val second = InstallProgress.start("second")!!
        InstallProgress.finish(first, "stale")
        assertTrue(InstallProgress.running)
        assertEquals("Working…", InstallProgress.message)
        InstallProgress.finish(second, "done")
        assertFalse(InstallProgress.running)
        assertEquals("done", InstallProgress.message)
    }

    @Test fun structuredResultIsFixedShapeBoundedAndClearedByNextOwner() {
        val ticket = InstallProgress.start("restore")!!
        InstallProgress.finish(
            ticket,
            "Restore partially completed",
            InstallProgress.OperationResult(
                status = InstallProgress.Outcome.PARTIAL,
                config = InstallProgress.ComponentResult(InstallProgress.Outcome.ROLLBACK_FAILED, 7),
                companion = InstallProgress.ComponentResult(
                    InstallProgress.Outcome.FAILED,
                    0,
                    "x".repeat(400),
                ),
            ),
        )

        val status = JSONObject(InstallProgress.json())
        assertEquals("partial", status.getJSONObject("result").getString("status"))
        assertEquals(7, status.getJSONObject("result").getJSONObject("config").getInt("items"))
        assertEquals(
            256,
            status.getJSONObject("result").getJSONObject("companion").getString("detail").length,
        )

        val next = InstallProgress.start("next")!!
        assertFalse(JSONObject(InstallProgress.json()).has("result"))
        InstallProgress.finish(next, "done")
    }

    @Test fun destructiveOperationAndConfigureMutationCannotOverlap() {
        val restore = InstallProgress.start("Restore")!!
        try {
            assertNull(InstallProgress.startConfigMutation())
        } finally {
            InstallProgress.finish(restore, "done")
        }

        val configure = InstallProgress.startConfigMutation()!!
        try {
            assertFalse(InstallProgress.running)
            assertNull(InstallProgress.start("Restore"))
            assertNull(InstallProgress.startConfigMutation())
        } finally {
            InstallProgress.finishConfigMutation(configure)
        }

        val next = InstallProgress.start("Restore")!!
        InstallProgress.finish(next, "done")
    }

    @Test fun staleConfigureReleaseCannotClearNewerOwner() {
        val first = InstallProgress.startConfigMutation()!!
        InstallProgress.finishConfigMutation(first)
        val second = InstallProgress.startConfigMutation()!!

        InstallProgress.finishConfigMutation(first)
        assertNull(InstallProgress.start("Restore"))

        InstallProgress.finishConfigMutation(second)
        val restore = InstallProgress.start("Restore")!!
        InstallProgress.finish(restore, "done")
    }

    @Test fun configureOwnerPromotesWithoutObservableReleaseReacquireGap() {
        val configure = InstallProgress.startConfigMutation()!!

        val promoted = InstallProgress.promoteConfigMutation(configure, "ha-paneld")!!

        assertTrue(InstallProgress.running)
        assertEquals("ha-paneld", InstallProgress.component)
        assertNull("a competing operation must not win between commit and install", InstallProgress.start("race"))
        assertNull(InstallProgress.startConfigMutation())
        InstallProgress.finishConfigMutation(configure)
        assertTrue("the obsolete config release must not clear the promoted owner", InstallProgress.running)
        InstallProgress.finish(promoted, "installed")
        assertFalse(InstallProgress.running)
    }

    @Test fun staleConfigureTicketCannotPromoteOrDisturbCurrentOwner() {
        val stale = InstallProgress.startConfigMutation()!!
        InstallProgress.finishConfigMutation(stale)
        val current = InstallProgress.startConfigMutation()!!
        var promoted: InstallProgress.Ticket? = null
        try {
            promoted = InstallProgress.promoteConfigMutation(stale, "ha-paneld")
            assertNull(promoted)
            assertNull(InstallProgress.start("race"))
        } finally {
            // Keep the process-global test seam isolated even when a mutant wrongly promotes the
            // stale ticket and the assertion above fails. The mutation battery should credit that
            // assertion failure, not unrelated NPEs in tests that run afterward.
            if (promoted != null) InstallProgress.finish(promoted, "mutant cleanup")
            else InstallProgress.finishConfigMutation(current)
        }
        val next = InstallProgress.start("next")!!
        InstallProgress.finish(next, "done")
    }
}
