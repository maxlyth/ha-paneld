package io.github.maxlyth.hapaneld.backup

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileBackupPolicyTest {
    @Test
    fun `android backup never migrates profile activation preferences`() {
        val legacy = exclusions("src/main/res/xml/backup_rules.xml")
        val modern = exclusions("src/main/res/xml/data_extraction_rules.xml")

        assertEquals(1, legacy.count { it == PROFILE_PREFS })
        assertEquals(2, modern.count { it == PROFILE_PREFS })
    }

    private fun exclusions(path: String): List<String> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(path))
        return (0 until document.getElementsByTagName("exclude").length).mapNotNull { index ->
            document.getElementsByTagName("exclude").item(index).attributes
                ?.getNamedItem("path")?.nodeValue
        }
    }

    private companion object {
        const val PROFILE_PREFS = "ha-paneld-device-profiles.xml"
    }
}
