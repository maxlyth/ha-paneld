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
}
