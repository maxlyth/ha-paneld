package io.github.maxlyth.hapaneld.dashboard

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityBootstrapGateTest {
    @Test fun emptyAutomaticLearnerHoldsRenderer() {
        assertTrue(shouldHoldRendererForEntityBootstrap(
            learningEnabled = true,
            filterEnabled = false,
        ))
    }

    @Test fun intentionallyEmptyAutomaticFilterMayRenderUnavailableEntities() {
        assertFalse(shouldHoldRendererForEntityBootstrap(
            learningEnabled = true,
            filterEnabled = true,
        ))
    }

    @Test fun populatedAutomaticFilterMayRender() {
        assertFalse(shouldHoldRendererForEntityBootstrap(
            learningEnabled = true,
            filterEnabled = true,
        ))
    }

    @Test fun manualModeRetainsExistingUnfilteredBehaviour() {
        assertFalse(shouldHoldRendererForEntityBootstrap(
            learningEnabled = false,
            filterEnabled = false,
        ))
    }

    @Test fun learnerCompletionOnlyReloadsSelectedBuiltinRenderer() {
        assertTrue(shouldReloadBuiltinAfterEntityFilterChange("builtin", "builtin"))
        assertFalse(shouldReloadBuiltinAfterEntityFilterChange("", "builtin"))
        assertFalse(shouldReloadBuiltinAfterEntityFilterChange("other.renderer", "builtin"))
        // While the wizard's filter question is still open on a first run, the renderer is deliberately
        // held — the answer route performs the one release, so the config commit's own change must not
        // add a second relaunch (observed 350 ms apart on hardware).
        assertFalse(shouldReloadBuiltinAfterEntityFilterChange(
            "builtin", "builtin", setupEntityFilterAnswered = false, setupEverCompleted = false))
        assertTrue(shouldReloadBuiltinAfterEntityFilterChange(
            "builtin", "builtin", setupEntityFilterAnswered = true, setupEverCompleted = false))
        assertTrue(shouldReloadBuiltinAfterEntityFilterChange(
            "builtin", "builtin", setupEntityFilterAnswered = false, setupEverCompleted = true))
    }

    @Test fun learnerCompletionResolvesAutoBeforeTestingRendererKind() {
        val source = listOf(
            File("src/main/kotlin/io/github/maxlyth/hapaneld/PaneldService.kt"),
            File("app/src/main/kotlin/io/github/maxlyth/hapaneld/PaneldService.kt"),
        ).first { it.isFile }.readText()
        val callback = source.substringAfter("onFilterChanged = {")
            .substringBefore("watchdog = WatchdogController")
        assertTrue(
            "Auto must be resolved before deciding whether learner completion reloads built-in",
            "system.resolveDashboard(config.dashboardPackage)" in callback,
        )
    }
}
