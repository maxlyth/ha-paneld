package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

class ProximityWizardCoordinatorTest {
    private class Fixture(narrator: ProximityWizardNarrator? = null) {
        var active = false
        var startAccepted = true
        var launchAccepted = true
        var acquireAccepted = true
        var starts = 0
        var launches = 0
        var releases = 0
        var cancels = 0
        val coordinator = ProximityWizardCoordinator(
            startSession = { starts++; active = startAccepted; startAccepted },
            active = { active },
            status = { """{"sessionId":"current"}""" },
            visible = { true },
            localAction = { false },
            cancel = { _, _ -> cancels++; active = false; true },
            heartbeat = { it == "current" },
            reset = { true },
            acquireDisplay = { acquireAccepted },
            releaseDisplay = { releases++ },
            launch = { launches++; launchAccepted },
            narrator = narrator,
        )
    }

    @Test fun failedLaunchCancelsSessionAndReleasesDisplay() {
        val f = Fixture()
        try {
            f.launchAccepted = false
            assertFalse(f.coordinator.remote("start", ""))
            assertFalse(f.active)
            assertEquals(1, f.cancels)
            assertEquals(1, f.releases)
            assertEquals(1, f.launches)
        } finally { f.coordinator.close() }
    }

    @Test fun failedStartReleasesDisplayWithoutLaunching() {
        val f = Fixture()
        try {
            f.startAccepted = false
            assertFalse(f.coordinator.remote("start", ""))
            assertEquals(1, f.releases)
            assertEquals(0, f.launches)
            assertEquals(0, f.cancels)
        } finally { f.coordinator.close() }
    }

    @Test fun failedDisplayAdmissionCannotStartSession() {
        val f = Fixture()
        try {
            f.acquireAccepted = false
            assertFalse(f.coordinator.remote("start", ""))
            assertEquals(0, f.starts)
            assertEquals(0, f.launches)
        } finally { f.coordinator.close() }
    }

    @Test fun activeSessionRejectsSecondLaunchAndCloseDetachesAdmission() {
        val f = Fixture()
        try {
            assertTrue(f.coordinator.remote("start", ""))
            assertFalse(f.coordinator.remote("start", ""))
            assertEquals(1, f.starts)
            assertEquals(1, f.launches)
            f.coordinator.close()
            assertFalse(f.coordinator.remote("start", ""))
            assertFalse(ProximityWizardHost.action("current", "save"))
            assertEquals(1, f.releases)
        } finally { f.coordinator.close() }
    }

    @Test fun hostNarrationIsServiceOwnedAndCancelSilencesIt() = runTest {
        val spoken = mutableListOf<String>()
        val narrator = ProximityWizardNarrator({ text, _, _ -> spoken += text }, dispatcher = StandardTestDispatcher(testScheduler))
        val f = Fixture(narrator)
        try {
            assertTrue(ProximityWizardHost.narrate("current", "near|APPROACH", "Approach", "en-GB"))
            testScheduler.runCurrent()
            assertEquals(listOf("Approach"), spoken)
            assertTrue(f.coordinator.remote("cancel", "current"))
            assertTrue(ProximityWizardHost.narrate("current", "near|APPROACH", "Approach", "en-GB"))
            testScheduler.runCurrent()
            assertEquals(listOf("Approach", "Approach"), spoken)
        } finally { f.coordinator.close() }
        assertFalse(ProximityWizardHost.narrate("current", "waves|WAVE", "Wave", "en-GB"))
    }
}
