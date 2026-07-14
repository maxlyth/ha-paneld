package io.github.maxlyth.hapaneld.http

import java.io.File
import kotlin.test.assertTrue
import org.junit.Test

class PanelNavigationSourceTest {
    @Test
    fun installPrecedesTestInPrimaryNavigation() {
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
        val nav = source.substringAfter("private fun navBar(active: String)")
            .substringBefore("private fun entitiesBody()")
        val install = nav.indexOf("tab(\"install\", \"/install\", \"Install\")")
        val test = nav.indexOf("tab(\"test\", \"/test\", \"Test\")")
        assertTrue(install >= 0, "Install navigation entry is missing")
        assertTrue(test >= 0, "Test navigation entry is missing")
        assertTrue(install < test, "Install must appear before the lower-priority Test tab")
    }
}
