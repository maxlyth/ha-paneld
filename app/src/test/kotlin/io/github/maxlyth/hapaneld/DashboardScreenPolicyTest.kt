package io.github.maxlyth.hapaneld

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardScreenPolicyTest {
    @Test fun preventIdleDimOwnsTheBuiltInRendererWindowFlag() {
        assertTrue(shouldKeepBuiltInRendererScreenOn(preventIdleDim = true))
        assertFalse(shouldKeepBuiltInRendererScreenOn(preventIdleDim = false))
    }

    @Test fun activityAppliesAndClearsTheFlagAcrossLiveAndResumePaths() {
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/DashboardActivity.kt").readText()
        val policy = source.substring(
            source.indexOf("private fun applyRendererScreenPolicy()"),
            source.indexOf("private fun applyOverscroll()"),
        )

        assertTrue(policy.contains("window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)"))
        assertTrue(policy.contains("window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)"))
        assertTrue(source.contains("activityConfig.registerChangeListener(rendererPowerListener)"))
        assertTrue(source.contains("activityConfig.unregisterChangeListener(rendererPowerListener)"))
        assertTrue(source.substring(source.indexOf("override fun onResume()"), source.indexOf("private fun applyOverscroll()"))
            .contains("applyRendererScreenPolicy()"))
    }

    @Test fun dashboardEntryBootstrapsTheServiceBeforeConfigGates() {
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/DashboardActivity.kt").readText()
        val onCreate = source.substring(
            source.indexOf("override fun onCreate(savedInstanceState: Bundle?)"),
            source.indexOf("override fun dispatchTouchEvent"),
        )

        assertTrue(onCreate.contains("PaneldService.start(this)"))
        assertTrue(onCreate.indexOf("PaneldService.start(this)") < onCreate.indexOf("val config = Config(this)"))
        assertTrue(onCreate.contains("if (!config.builtInRendererReady())"))
    }

    @Test fun unauthenticatedBuiltinCompatibilityFailureUsesSignInRepairCopy() {
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/DashboardActivity.kt").readText()
        val block = source.substring(
            source.indexOf("DashboardV2ProbeResult.AuthenticationFailed"),
            source.indexOf("is DashboardV2ProbeResult.Unavailable"),
        )

        assertTrue(block.contains("config.haToken.isBlank() && config.haRefreshToken.isBlank()"))
        assertTrue(block.contains("\"Home Assistant sign-in needed\""))
        assertTrue(block.contains("\"Home Assistant version check rejected\""))
    }

    @Test fun theEntityFilterHoldNamesTheReasonTheAddressAndAWayOut() {
        // The hold protects the first impression; it must never become a trap. Someone standing at the panel
        // with no browser to hand needs both the address of the page and a way to proceed without it, and the
        // skip has to say what it costs — it is the unfiltered load this screen exists to avoid.
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/DashboardActivity.kt").readText()
        val screen = source.substring(
            source.indexOf("private fun showWaitingForEntityFilterAnswer()"),
            source.indexOf("private fun showWaitingForEntityBootstrap()"),
        )
        assertTrue(screen.contains("to optimise the "))
        assertTrue("the page's own address must be shown", screen.contains("\"/setup\""))
        assertTrue(screen.contains("\"Skip and load the dashboard now\""))
        assertTrue("skipping must state its cost", screen.contains("which is slower on this panel"))
        assertTrue(screen.contains("skipEntityFilterQuestion()"))

        // Skipping records the SAME answer the browser's decline records, so the two surfaces cannot disagree
        // about whether the question was asked — and it must not quietly enable what the user passed over.
        val skip = source.substring(
            source.indexOf("private fun skipEntityFilterQuestion()"),
            source.indexOf("private fun showWaitingForEntityFilterAnswer()"),
        )
        assertTrue(skip.contains("config.setupEntityFilterAnswered = true"))
        assertFalse(skip.contains("dashboardEntityLearning"))
        assertTrue(skip.contains("buildAndLoad(config)"))
    }
}
