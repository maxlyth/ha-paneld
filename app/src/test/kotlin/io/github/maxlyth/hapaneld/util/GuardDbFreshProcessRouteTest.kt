package io.github.maxlyth.hapaneld.util

import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardDbFreshProcessRouteTest {
    @Test fun `failed INTENT with helper EMPTY clears to ordinary PaneldService`() {
        assertEquals(
            GuardDbFreshProcessRoute.ORDINARY_PANELD,
            guardDbFreshProcessRoute(
                GuardDbSentinelState.INTENT,
                GuardDbMaintenanceProtocol.Phase.EMPTY,
            ),
        )
    }

    @Test fun `retained baseline and armed custody route to writer free maintenance`() {
        listOf(
            GuardDbSentinelState.BASELINE_READY to GuardDbMaintenanceProtocol.Phase.EMPTY,
            GuardDbSentinelState.ARMED to GuardDbMaintenanceProtocol.Phase.WAIT_B_HEALTH,
        ).forEach { (sentinel, helper) ->
            assertEquals(
                "$sentinel with $helper",
                GuardDbFreshProcessRoute.WRITER_FREE_MAINTENANCE,
                guardDbFreshProcessRoute(sentinel, helper),
            )
        }
    }

    @Test fun `fresh process composes reconciler then PaneldService redirect before ordinary DB owners`() {
        val application = TestSources.kotlin("HaPaneldApp.kt").readText()
        val startup = TestSources.kotlin("util/GuardDbStartupHealth.kt").readText()
        val service = TestSources.kotlin("PaneldService.kt").readText()
        val onCreate = service.substring(
            service.indexOf("override fun onCreate()"),
            service.indexOf("override fun onStartCommand("),
        )

        assertTrue(application.contains("GuardDbStartupAcknowledger.reconcileBeforeServices(this)"))
        assertTrue(startup.contains("guardDbFreshProcessRoute(sentinel.state, status.phase)"))
        assertTrue(onCreate.indexOf("GuardDbProcessAdmission.maintenanceRequired()") <
            onCreate.indexOf("Config(this)"))
        assertTrue(onCreate.indexOf("GuardDbMaintenanceService.start(this)") <
            onCreate.indexOf("Config(this)"))
    }
}
