package io.github.maxlyth.hapaneld.http

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File
import org.junit.Test

class PanelBrowserTitleTest {
    @Test
    fun friendlyNameLeadsEveryBrowserTitle() {
        assertEquals("Example Panel", panelBrowserTitle("Example Panel"))
        assertEquals("Example Panel · Configure", panelBrowserTitle("Example Panel", "Configure"))
        assertEquals("Example Panel · REST API", panelBrowserTitle("Example Panel", "REST API"))
        assertEquals("ha-paneld", panelBrowserTitle("   "))
    }

    @Test
    fun backupFilenameUsesFriendlyNameMetadataRatherThanTitlePosition() {
        val source = File("src/main/assets/install.js").readText()
        assertTrue(source.contains("switcher.dataset.selfName"))
        assertTrue(source.contains("document.title.split('·')[0]"))
    }
}
