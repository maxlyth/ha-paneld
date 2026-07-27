package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteDashboardActionContractTest {
    private val source = listOf(
        File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
        File("app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt"),
    ).first(File::isFile).readText()

    @Test fun `remote Controls exposes a labelled Dashboard action and distinct Reload recovery`() {
        val controls = source.substringAfter("private fun controlsHtml(s: Snap?): String")
            .substringBefore("/** Hydration payload for the dashboard")

        assertTrue("Dashboard must remain recognisable before narrow layout compacts", controls.contains("⌂<span class=\\\"lbl\\\"> Dashboard</span>"))
        assertTrue("Dashboard must post its own action", controls.contains("pbtn(\"dashboard\""))
        assertTrue("Reload must remain a separately labelled recovery action", controls.contains("pbtn(\"reload\", \"↻ Reload\""))
        assertTrue("Reload must occupy its own Controls row", controls.contains("class=\"ctlrow ctlrow-secondary\""))
        assertTrue(
            "Dashboard belongs with remote navigation, before the recovery row",
            controls.indexOf("pbtn(\"dashboard\"") < controls.indexOf("pbtn(\"reload\""),
        )
    }

    @Test fun `Dashboard route reuses renderer authority while Reload retains recovery authority`() {
        val route = source.substringAfter("post(\"/action\")")
            .substringBefore("// Debug-only sensor trace")
        val dispatch = source.substringAfter("private fun executeRemoteControl(command: RemoteControl)")
            .substringBefore("private fun executeRemoteTap")
        val accepted = source.substringAfter("private val REMOTE_ACTIONS = setOf(")
            .substringBefore(")\n        private val OPAQUE_AUTO_SLEEP_KEY")
        val dashboard = dispatch.lineSequence().first { it.contains("\"dashboard\"") }
        val reload = dispatch.lineSequence().first { it.contains("\"reload\"") }

        assertTrue("POST /action must retain the central admission list", route.contains("a !in REMOTE_ACTIONS"))
        assertTrue("Dashboard action must be admitted", accepted.contains("\"dashboard\""))
        assertTrue("Reload action must be admitted", accepted.contains("\"reload\""))
        assertTrue("Dashboard must foreground the configured effective renderer", dashboard.contains("system.launchHome(config.dashboardPackage)"))
        assertFalse("Dashboard must not reload the renderer", dashboard.contains("reloadDashboard"))
        assertTrue("Reload remains the deliberate recovery path", reload.contains("system.reloadDashboard(config.dashboardPackage)"))
        assertFalse("Reload must not be downgraded to a foreground-only action", reload.contains("launchHome"))
    }
}
