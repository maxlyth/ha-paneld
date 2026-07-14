package io.github.maxlyth.hapaneld.http

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class DisplayDensityTerminologySourceTest {
    @Test fun displaySizingLabelsAndroidResetReferenceAsFactoryBase() {
        val source = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
        val card = source.substringAfter("private fun displayCardHtml()")
            .substringBefore("private fun asset(")

        assertTrue("Logical density (dpi)" in card)
        assertTrue("factory base" in card)
        assertFalse("· native" in card)
    }
}
