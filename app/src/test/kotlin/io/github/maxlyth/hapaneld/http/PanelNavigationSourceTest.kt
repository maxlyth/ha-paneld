package io.github.maxlyth.hapaneld.http

import java.io.File
import kotlin.test.assertTrue
import org.junit.Test

class PanelNavigationSourceTest {
    @Test
    fun testTabIsWithheldAndInstallRemainsInPrimaryNavigation() {
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
        val nav = source.substringAfter("private fun navBar(active: String)")
            .substringBefore("private fun entitiesBody()")
        val install = nav.indexOf("tab(\"install\", \"/install\", \"Install\")")
        assertTrue(install >= 0, "Install navigation entry is missing")
        assertTrue("tab(\"test\"" !in nav, "The unverified Test tab must not be public navigation")
        assertTrue(
            "get(\"/test\") { call.respondRedirect(\"/\") }" in source,
            "Existing /test bookmarks should return users to Dashboard",
        )
        assertTrue("private fun testBody()" !in source, "The withheld remote-control page must not render")
    }
}
