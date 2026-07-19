package io.github.maxlyth.hapaneld.control

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavbarDashboardActionContractTest {
    private val source = listOf(
        File("src/main/kotlin/io/github/maxlyth/hapaneld/control/NavbarController.kt"),
        File("app/src/main/kotlin/io/github/maxlyth/hapaneld/control/NavbarController.kt"),
    ).first(File::isFile).readText()

    @Test fun `dashboard action foregrounds the configured renderer without consuming reload recovery`() {
        val navGroup = source.substringAfter("private fun addNavGroup")
            .substringBefore("/** Narrow-panel control button")
        val dashboard = navGroup.lineSequence().first { "ic_nav_dashboard" in it }

        assertTrue("dashboard uses renderer-aware foregrounding", "system.launchHome(dashboardPkg())" in dashboard)
        assertFalse("dashboard must not reload the renderer", "reloadDashboard" in dashboard)
        assertTrue("explicit reload recovery remains available", "system.reloadDashboard(dashboardPkg())" in navGroup)
        assertTrue(navGroup.indexOf("ic_nav_launcher") < navGroup.indexOf("ic_nav_dashboard"))
        assertTrue(navGroup.indexOf("ic_nav_dashboard") < navGroup.indexOf("ic_nav_reload"))
    }

    @Test fun `dashboard action has a dedicated packaged icon`() {
        val icon = listOf(
            File("src/main/res/drawable/ic_nav_dashboard.xml"),
            File("app/src/main/res/drawable/ic_nav_dashboard.xml"),
        ).first(File::isFile).readText()

        assertTrue("dashboard icon is a vector resource", "<vector" in icon)
        assertTrue("dashboard icon contains visible path data", "android:pathData=" in icon)
    }
}
