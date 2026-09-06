package io.github.maxlyth.hapaneld.i18n

import io.github.maxlyth.hapaneld.config.SettingsRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsCatalogueContractTest {
    private val catalogueFile = File("src/main/assets/i18n/en.json")
    private val configureFile = File("src/main/assets/configure.js")
    private val releaseTargetLocales = AppLocale.RELEASE_LOCALES.filterNot { it == AppLocale.ENGLISH }

    @Test fun `authoritative English catalogue exactly covers visible Settings copy`() {
        val catalogue = SourceCatalogue.parse(catalogueFile.readText())
        val expected = linkedMapOf<String, String>()
        SettingsRegistry.SPECS.forEach { spec ->
            expected[spec.labelKey] = spec.label
            if (spec.help.isNotEmpty()) expected[spec.helpKey] = spec.help
        }

        assertEquals(87, SettingsRegistry.SPECS.size)
        assertEquals(173, expected.size)
        val settings = catalogue.strings.filterKeys { it.startsWith("settings.") }
        assertEquals("Settings must remain an exact independently-owned subset", expected.keys, settings.keys)
        expected.forEach { (key, text) ->
            val record = checkNotNull(settings[key])
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

    @Test fun `every release target has a current reviewed Settings translation`() {
        val source = SourceCatalogue.parse(catalogueFile.readText())
        val settings = source.strings.filterKeys { it.startsWith("settings.") }

        releaseTargetLocales.forEach { locale ->
            val target = TargetCatalogue.parse(File("src/main/assets/i18n/$locale.json").readText(), source)
            assertEquals(
                "$locale Settings key set must exactly match English",
                settings.keys,
                target.strings.keys.filterTo(sortedSetOf()) { it.startsWith("settings.") },
            )
            settings.forEach { (key, english) ->
                val translated = checkNotNull(target.strings[key]) { "$locale is missing $key" }
                assertEquals("$locale has stale source text for $key", english.sourceHash, translated.sourceHash)
                assertTrue(
                    "$locale $key must be reviewed before it can replace the English fallback",
                    translated.state == TranslationState.MACHINE_CROSS_CHECKED ||
                        translated.state == TranslationState.COMMUNITY_CORRECTED,
                )
            }
        }
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
