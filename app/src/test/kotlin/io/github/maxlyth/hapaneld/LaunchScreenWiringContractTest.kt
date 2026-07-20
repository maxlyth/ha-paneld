package io.github.maxlyth.hapaneld

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchScreenWiringContractTest {
    private fun source(name: String): String = listOf(
        File("src/main/kotlin/io/github/maxlyth/hapaneld/$name"),
        File("app/src/main/kotlin/io/github/maxlyth/hapaneld/$name"),
    ).first { it.isFile }.readText()

    @Test fun adminLauncherMarksItsIntroEntryExplicitly() {
        val admin = source("AdminLauncherActivity.kt")
        val config = source("ConfigActivity.kt")
        val main = source("MainActivity.kt")
        assertTrue(admin.contains("putExtra(MainActivity.EXTRA_EXPLICIT_ADMIN_ENTRY, true)"))
        assertTrue(main.contains("explicitAdminEntry = explicitAdminEntry"))
        assertTrue(main.contains("introExplicitAdminEntry = plan.explicitAdminEntry"))
        assertTrue(main.contains("if (!plan.explicitAdminEntry)"))
        assertTrue(main.contains("KioskAdminUi.setVisible(this, presentedIntro != null)"))
        assertTrue(main.contains("KioskAdminUi.setVisible(this, false)"))
        assertTrue(admin.indexOf("KioskAdminUi.setVisible(this, true)") < admin.indexOf("setContentView(buildUi())"))
        assertTrue(config.indexOf("KioskAdminUi.setVisible(this, true)") < config.indexOf("web = WebView(this)"))
        assertTrue(admin.contains("KioskAdminUi.setVisible(this, true)"))
        assertTrue(config.contains("KioskAdminUi.setVisible(this, true)"))
    }

    @Test fun serviceStartsBeforePolicyCanRedirectAndIntroPrecedesAcknowledgement() {
        val main = source("MainActivity.kt")
        val startup = main.substring(
            main.indexOf("private fun startServiceAndChooseDestination"),
            main.indexOf("private fun chooseDestination"),
        )
        assertTrue(startup.indexOf("PaneldService.start(this)") < startup.indexOf("chooseDestination()"))

        val choose = main.substring(
            main.indexOf("private fun chooseDestination"),
            main.indexOf("// After an app update"),
        )
        assertTrue(choose.indexOf("setContentView(intro)") < choose.indexOf("acknowledgeVersionAfterFirstDraw"))
        assertTrue(choose.contains("finish()"))

        val acknowledge = main.substring(
            main.indexOf("private fun acknowledgeVersionAfterFirstDraw"),
            main.indexOf("// After an app update"),
        )
        assertTrue(acknowledge.contains("IntroAcknowledgement(view, versionCode, generation).also { it.arm() }"))
        assertTrue(acknowledge.indexOf("override fun onDraw()") < acknowledge.indexOf("commitLaunchScreenVersionShown"))
        assertTrue(acknowledge.contains("mayAcknowledgePresentedIntro("))
        assertTrue(acknowledge.contains("generationMatches = generation == introGeneration"))
        assertTrue(acknowledge.contains("presentedViewMatches = presentedIntro === view"))
        assertTrue(acknowledge.contains("viewAttached = view.isAttachedToWindow"))
        assertTrue(main.contains("maybeArmAutoReturn(ignoreUpdateAge = freshDecision.rememberVersionShown)"))
        assertTrue(main.contains("restoredIntroState?.let { saved ->"))
        assertTrue(main.contains("presentIntro(restored)"))

        val save = main.substring(
            main.indexOf("override fun onSaveInstanceState"),
            main.indexOf("override fun onDestroy()"),
        )
        assertTrue(save.contains("introVersionPending?.let"))
        assertTrue(save.contains("STATE_AUTO_RETURN_REMAINING"))
        assertTrue(save.contains("STATE_AUTO_RETURN_NEXT_REMAINING"))
        assertTrue(save.contains("coerceIn(0L, AUTO_RETURN_WINDOW_MS)"))

        val destroy = main.substring(
            main.indexOf("override fun onDestroy()"),
            main.indexOf("private fun buildUi()"),
        )
        assertTrue(destroy.contains("introAcknowledgement?.cancel()"))
        assertTrue(destroy.contains("presentedIntro = null"))
        assertTrue(destroy.contains("introGeneration++"))
    }
}
