package io.github.maxlyth.hapaneld.http

import java.io.File
import kotlin.test.assertTrue
import org.junit.Test

class PanelNavigationSourceTest {
    @Test
    fun unfinishedTabsAreWithheldAndInstallRemainsInPrimaryNavigation() {
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
        val nav = source.substringAfter("private fun navBar(active: String)")
            .substringBefore("private fun entitiesBody()")
        val install = nav.indexOf("tab(\"install\", \"/install\", \"Install\")")
        val profiles = nav.indexOf("tab(\"profiles\", \"/profiles\", \"Profile\")")
        assertTrue(install >= 0, "Install navigation entry is missing")
        assertTrue(profiles >= 0, "Profile navigation entry is missing")
        assertTrue("tab(\"test\"" !in nav, "The unverified Test tab must not be public navigation")
        assertTrue(
            "get(\"/test\") { call.respondRedirect(\"/\") }" in source,
            "Existing /test bookmarks should return users to Dashboard",
        )
        assertTrue("private fun testBody()" !in source, "The withheld remote-control page must not render")
        assertTrue("tab(\"fleet\"" !in nav, "The Fleet placeholder must not be public navigation")
        assertTrue("href=\"/fleet\"" !in nav, "The Fleet placeholder must not be linked directly")
        assertTrue("get(\"/fleet\")" in source, "Existing /fleet bookmarks should remain available")
        assertTrue("private fun fleetBody()" in source, "The dormant Fleet implementation should remain intact")
    }
}
