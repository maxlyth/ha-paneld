package io.github.maxlyth.hapaneld.i18n

import io.github.maxlyth.hapaneld.config.SettingsRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsCatalogueContractTest {
    private val catalogueFile = File("src/main/assets/i18n/en.json")
    private val configureFile = File("src/main/assets/configure.js")

    @Test fun `authoritative English catalogue exactly covers visible Settings copy`() {
        val catalogue = SourceCatalogue.parse(catalogueFile.readText())
        val expected = linkedMapOf<String, String>()
        SettingsRegistry.SPECS.forEach { spec ->
            expected[spec.labelKey] = spec.label
            if (spec.help.isNotEmpty()) expected[spec.helpKey] = spec.help
        }

        assertEquals(87, SettingsRegistry.SPECS.size)
        assertEquals(173, expected.size)
        assertEquals(expected.keys, catalogue.strings.keys.toSet())
        expected.forEach { (key, text) ->
            val record = checkNotNull(catalogue.strings[key])
            assertEquals("English drift for $key", text, record.text)
            assertEquals("source hash drift for $key", sourceHash(text), record.sourceHash)
        }
    }

    @Test fun `setting-derived catalogue keys are unique and durable`() {
        val keys = SettingsRegistry.SPECS.flatMap { spec ->
            listOf(spec.labelKey) + if (spec.help.isEmpty()) emptyList() else listOf(spec.helpKey)
        }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.all { it.matches(Regex("settings\\.[a-z0-9_]+\\.(label|help)")) })
    }

    @Test fun `catalogued help and delayed locale refresh preserve the live form contract`() {
        val configure = configureFile.readText()
        assertTrue(configure.contains("var helpKids = [el(\"span\", { lang: f.helpLanguage, text: f.help })]"))
        assertTrue(configure.contains("""} else if (f.key === "auto_sleep") {
      help = el("small", { lang: f.helpLanguage, text: f.help });"""))
        assertTrue(configure.contains("var generation = editGeneration"))
        assertTrue(configure.contains("request !== schemaLanguageRequest || dirty || editGeneration !== generation"))
    }
}
