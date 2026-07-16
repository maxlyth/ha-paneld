package io.github.maxlyth.hapaneld

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdminUiAssetContractTest {
    private val assetsDir: File =
        listOf("src/main/assets", "app/src/main/assets", "../app/src/main/assets")
            .map(::File)
            .first { it.isDirectory }

    @Test fun configureSwitchesExposeStateAndKeyboardControl() {
        val source = File(assetsDir, "configure.js").readText()
        assertTrue(source.contains("\"aria-checked\""))
        assertTrue(source.contains("event.key !== \"Enter\""))
        assertTrue(source.contains("event.key !== \" \""))
        assertTrue(source.contains("event.preventDefault()"))
    }

    @Test fun collapsedNavigationReportsStateAndClosesOnEscape() {
        val source = File(assetsDir, "switcher.js").readText()
        assertTrue(source.contains("aria-expanded"))
        assertTrue(source.contains("aria-controls"))
        assertTrue(source.contains("event.key !== 'Escape'"))
        assertTrue(source.contains("burger.focus()"))
    }

    @Test fun narrowConfigureRowsStackWithoutMinimumWidthOverflow() {
        val source = File(assetsDir, "info.css").readText()
        assertTrue(source.contains("@media(max-width:600px)"))
        assertTrue(source.contains(".frow{flex-direction:column"))
        assertTrue(source.contains("width:100%;min-width:0;box-sizing:border-box"))
    }
}
