package io.github.maxlyth.hapaneld.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityLearningI18nContractTest {
    private val assets = File("src/main/assets")
    private val script = File(assets, "proximity-learning.js").readText()
    private val source = SourceCatalogue.parse(File(assets, "i18n/en.json").readText())
    private val keys = source.strings.keys.filterTo(sortedSetOf()) { it.startsWith(PREFIX) }

    @Test fun `finite proximity learning catalogue is complete and promoted in every release locale`() {
        assertEquals("the reviewed proximity-learning vocabulary changed", 63, keys.size)
        AppLocale.RELEASE_LOCALES.filterNot { it == AppLocale.ENGLISH }.forEach { locale ->
            val target = TargetCatalogue.parse(File(assets, "i18n/$locale.json").readText(), source)
            assertEquals(
                "$locale proximity-learning keys must exactly match English",
                keys,
                target.strings.keys.filterTo(sortedSetOf()) { it.startsWith(PREFIX) },
            )
            keys.forEach { key ->
                val english = source.strings.getValue(key)
                val translated = target.strings.getValue(key)
                assertEquals("$locale $key source hash drifted", english.sourceHash, translated.sourceHash)
                assertTrue(
                    "$locale $key must be promoted beyond a draft",
                    translated.state == TranslationState.MACHINE_CROSS_CHECKED ||
                        translated.state == TranslationState.COMMUNITY_CORRECTED,
                )
            }
            val permittedSourceIdentical = when (locale) {
                "es" -> setOf("$PREFIX.detail.with_health", "$PREFIX.experimental")
                else -> setOf("$PREFIX.detail.with_health")
            }
            assertEquals(
                "$locale has an unreviewed source-identical proximity target",
                permittedSourceIdentical,
                keys.filterTo(sortedSetOf()) { key ->
                    target.strings.getValue(key).text == source.strings.getValue(key).text
                },
            )
        }
    }

    @Test fun `client binds every finite dynamic vocabulary and marks unknown evidence as English`() {
        val messageSuffixes = Regex("\\[\"([a-z0-9_]+)\", \"[^\"]+\"\\]")
            .findAll(script.substringAfter("var messages = {").substringBefore("};"))
            .mapTo(sortedSetOf()) { it.groupValues[1] }
        val expectedMessages = keys.asSequence()
            .filter { it.startsWith("$PREFIX.message.") && it != "$PREFIX.message.wave_accepted" }
            .mapTo(sortedSetOf()) { it.removePrefix("$PREFIX.message.") }

        assertEquals("known runtime messages and catalogue records diverged", expectedMessages, messageSuffixes)
        assertEquals(
            "finite phase records changed without the client map",
            keys.filterTo(sortedSetOf()) { it.startsWith("$PREFIX.phase.") },
            Regex("\\[\"($PREFIX\\.phase\\.[a-z0-9_]+)\", \"[^\"]+\"\\]")
                .findAll(script).mapTo(sortedSetOf()) { it.groupValues[1] },
        )
        assertEquals(
            "finite health records changed without the client map",
            keys.filterTo(sortedSetOf()) { it.startsWith("$PREFIX.health.") },
            Regex("[a-z_]+: \\[\"([a-z_]+)\", \"[^\"]+\"\\]")
                .findAll(
                    script.substring(script.indexOf("function healthText"))
                        .substringAfter("var labels = {").substringBefore("};"),
                )
                .mapTo(sortedSetOf()) { "$PREFIX.health.${it.groupValues[1]}" },
        )
        assertEquals(
            "finite mode records changed without the client map",
            keys.filterTo(sortedSetOf()) { it.startsWith("$PREFIX.mode.") },
            Regex("(?:identifying|binary|graded): \"([a-z_]+)\"")
                .findAll(script.substringAfter("var modes = {").substringBefore("};"))
                .mapTo(sortedSetOf()) { "$PREFIX.mode.${it.groupValues[1]}" },
        )
        assertTrue(script.contains("presented(raw || \"\", false)"))
        assertTrue(script.contains("data.error ? presented(String(data.error), false)"))
        assertTrue(script.contains("target.setAttribute(\"lang\", \"en\")"))
        assertFalse("opaque runtime evidence must never enter an HTML sink", script.contains("result.innerHTML"))
        assertFalse("opaque runtime evidence must never enter an HTML sink", script.contains("detail.innerHTML"))
    }

    @Test fun `destructive reset remains an explicit localized confirmation`() {
        assertTrue(script.contains("window.confirm(t(\"$PREFIX.confirm.forget\""))
        assertTrue(script.contains("post(\"/api/v1/proximity/relearn\", { confirm: \"true\" })"))
        assertTrue(
            "the confirmation must still precede the mutation",
            script.indexOf("window.confirm(t(\"$PREFIX.confirm.forget\"") <
                script.indexOf("post(\"/api/v1/proximity/relearn\""),
        )
    }

    private companion object {
        const val PREFIX = "configure.proximity"
    }
}
