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
}
