package io.github.maxlyth.hapaneld.http

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.io.File
import org.junit.Test

class PanelBrowserTitleTest {
    @Test
    fun stableBuildUsesFriendlyNameFirst() {
        assertEquals("Example Panel", panelBrowserTitle("Example Panel", versionName = "0.9.5", versionCode = 260))
        assertEquals("Example Panel · Configure", panelBrowserTitle("Example Panel", "Configure", "0.9.5", 260))
        assertEquals("Example Panel · REST API", panelBrowserTitle("Example Panel", "REST API", "0.9.5", 260))
        assertEquals("ha-paneld", panelBrowserTitle("   ", versionName = "0.9.5", versionCode = 260))
    }

    @Test
    fun prereleaseBuildNumberLeadsEveryBrowserTitle() {
        assertEquals("260 · Example Panel", panelBrowserTitle("Example Panel", versionName = "0.9.5-rc1", versionCode = 260))
        assertEquals("260 · Example Panel · Configure", panelBrowserTitle("Example Panel", "Configure", "0.9.5-rc1", 260))
        assertEquals("260 · ha-paneld", panelBrowserTitle("   ", versionName = "0.9.5-rc1", versionCode = 260))
    }

    @Test
    fun backupFilenameUsesFriendlyNameMetadataRatherThanTitlePosition() {
        val source = File("src/main/assets/install.js").readText()
        assertTrue(source.contains("switcher.dataset.selfName"))
        assertTrue(source.contains("document.title.split('·')[0]"))
    }
}
