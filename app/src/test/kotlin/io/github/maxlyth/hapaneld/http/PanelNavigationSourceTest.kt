package io.github.maxlyth.hapaneld.http

import java.io.File
import kotlin.test.assertTrue
import org.junit.Test

class PanelNavigationSourceTest {
    @Test
    fun unfinishedTabsAreWithheldAndInstallRemainsInPrimaryNavigation() {
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
        val nav = source.substringAfter("private fun navBar(active: String, strings: AppStrings)")
            .substringBefore("private fun entitiesBody()")
        val install = nav.indexOf("tab(\"install\", \"/install\", strings.get(\"shell.nav.install\"))")
        val profiles = nav.indexOf("tab(\"profiles\", \"/profiles\", strings.get(\"shell.nav.profile\"))")
        assertTrue(install >= 0, "Install navigation entry is missing")
        assertTrue(profiles >= 0, "Profile navigation entry is missing")
        assertTrue(
            "tab(\"entities\", \"/entities\", strings.get(\"shell.nav.entities\"))" in nav,
            "Entities must remain reachable before first-time entity-filter activation completes",
        )
        assertTrue("disabled-tab" !in nav, "Entities must not become an inert navigation label")
        assertTrue("tab(\"test\"" !in nav, "The unverified Test tab must not be public navigation")
        assertTrue(
            "get(\"/test\") { call.respondRedirect(\"/\") }" in source,
            "Existing /test bookmarks should return users to Dashboard",
        )
        assertTrue("private fun testBody()" !in source, "The withheld remote-control page must not render")
        assertTrue("tab(\"fleet\"" !in nav, "The Fleet placeholder must not be public navigation")
        assertTrue("href=\"/fleet\"" !in nav, "The Fleet placeholder must not be linked directly")
        assertTrue("get(\"/fleet\")" in source, "Existing /fleet bookmarks should remain available")
        assertTrue("private fun fleetBody(strings: AppStrings)" in source, "The dormant Fleet implementation should remain intact")
    }

    @Test
    fun entitiesPageAcceptsAutoWhenItResolvesToTheBuiltInRenderer() {
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
        val entities = source.substringAfter("private fun entitiesBody()")
            .substringBefore("private fun entityTableHtml")
        assertTrue(
            "!effectiveDashboardIsBuiltin()" in entities,
            "Entities must follow the effective renderer so blank Auto can expose an enabled filter",
        )
        assertTrue(
            "config.dashboardPackage != SystemController.BUILTIN_DASHBOARD" !in entities,
            "A literal renderer gate incorrectly treats Auto→built-in as a foreign renderer",
        )
        val update = source.substringAfter("private suspend fun handleEntityFilterPost")
            .substringBefore("private fun startHaOAuth")
        assertTrue(
            "if (effectiveDashboardIsBuiltin())" in update,
            "Entity-filter updates must reload an Auto-selected built-in renderer",
        )
        assertTrue(
            "config.dashboardPackage == SystemController.BUILTIN_DASHBOARD" !in update,
            "A literal renderer gate skips entity-filter reloads for Auto→built-in",
        )
    }
}
