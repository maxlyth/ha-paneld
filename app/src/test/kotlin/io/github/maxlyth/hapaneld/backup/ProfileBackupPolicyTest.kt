package io.github.maxlyth.hapaneld.backup

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Document

class ProfileBackupPolicyTest {
    @Test
    fun `implicit android backup is disabled`() {
        val manifest = document("src/main/AndroidManifest.xml")
        val applications = manifest.getElementsByTagName("application")

        assertEquals(1, applications.length)
        assertEquals("false", applications.item(0).attributes.getNamedItemNS(ANDROID_NS, "allowBackup")?.nodeValue)
    }

    @Test
    fun `android backup rules never migrate credentials or panel-local state`() {
        val legacy = exclusions("src/main/res/xml/backup_rules.xml")
        val modern = exclusions("src/main/res/xml/data_extraction_rules.xml")

        for (preferences in NON_TRANSFERABLE_PREFS) {
            assertEquals("legacy exclusion for $preferences", 1, legacy.count { it == preferences })
            assertEquals("modern exclusions for $preferences", 2, modern.count { it == preferences })
        }
    }

    private fun exclusions(path: String): List<String> {
        val document = document(path)
        return (0 until document.getElementsByTagName("exclude").length).mapNotNull { index ->
            document.getElementsByTagName("exclude").item(index).attributes
                ?.getNamedItem("path")?.nodeValue
        }
    }

    private fun document(path: String): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(path))

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        val NON_TRANSFERABLE_PREFS = listOf(
            "ha-paneld.xml",
            "ha-paneld-shizuku.xml",
            "ha-paneld-device-profiles.xml",
            "ha-paneld-profile-calibration.xml",
            "ha-paneld-controller-state.xml",
        )
    }
}
