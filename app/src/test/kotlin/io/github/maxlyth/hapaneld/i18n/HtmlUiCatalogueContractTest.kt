package io.github.maxlyth.hapaneld.i18n

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlUiCatalogueContractTest {
    private val assets = File("src/main/assets")
    private val server = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt")
    private val catalogue = JSONObject(File(assets, "i18n/en.json").readText()).getJSONObject("strings")

    @Test fun `shell dashboard and Configure literal keys are present and owned by their source surface`() {
        val usages = linkedMapOf(
            "shell" to literalKeys(server.readText(), "strings\\.get"),
            "dashboard" to (
                literalKeys(server.readText(), "strings\\.get") +
                    literalKeys(File(assets, "info.js").readText(), "i18nText")
                ).filterTo(sortedSetOf()) { it.startsWith("dashboard.") },
            "configure" to (
                literalKeys(server.readText(), "strings\\.get") +
                    literalKeys(File(assets, "configure.js").readText(), "i18nText")
                ).filterTo(sortedSetOf()) { it.startsWith("configure.") },
        )

        usages.forEach { (surface, allKeys) ->
            val keys = allKeys.filterTo(sortedSetOf()) { it.startsWith("$surface.") }
            assertFalse("$surface must have literal catalogue consumers", keys.isEmpty())
            keys.forEach { key ->
                assertTrue("$key is used but absent from the English catalogue", catalogue.has(key))
                assertEquals("$key is assigned to the wrong catalogue surface", surface, catalogue.getJSONObject(key).getString("surface"))
            }
        }
    }

    @Test fun `Simplified Chinese HTML UI slice is current and promoted`() {
        val source = SourceCatalogue.parse(File(assets, "i18n/en.json").readText())
        val target = TargetCatalogue.parse(File(assets, "i18n/zh-Hans.json").readText(), source)
        val prefixes = listOf("shell.", "dashboard.", "configure.")
        val expected = source.strings.filterKeys { key -> prefixes.any(key::startsWith) }

        assertFalse("the HTML UI slice must not be empty", expected.isEmpty())
        expected.forEach { (key, sourceString) ->
            val translated = checkNotNull(target.strings[key]) { "zh-Hans is missing $key" }
            assertEquals("zh-Hans has stale source text for $key", sourceString.sourceHash, translated.sourceHash)
            assertTrue(
                "zh-Hans must promote $key beyond draft before the collaborator preview",
                translated.state == TranslationState.MACHINE_CROSS_CHECKED ||
                    translated.state == TranslationState.COMMUNITY_CORRECTED,
            )
        }
    }

    @Test fun `shared shell installs localized payload and helper before page scripts`() {
        val source = server.readText()
        val payload = source.indexOf("<script id=\"ha-i18n\"")
        val helper = source.indexOf("<script src=\"/assets/i18n.js\"></script>")
        val pageScripts = source.indexOf("${'$'}extraScripts<script src=\"/assets/power-safety.js\"></script>")
        assertTrue("localized JSON payload must precede the helper", payload >= 0 && payload < helper)
        assertTrue("the helper must precede info.js and configure.js supplied through page content", helper < pageScripts)

        val dashboard = functionBody(source, "infoHtml")
        val configure = functionBody(source, "configureBody")
        assertTrue(dashboard.contains("<script src=\"/info.js\"></script>"))
        assertTrue(configure.contains("<script src=\"/assets/configure.js\"></script>"))
    }

    @Test fun `requested locale is emitted on the document and propagated to navigation and dashboard hydration`() {
        val source = server.readText()
        val shell = functionBody(source, "pageShell")
        val links = functionBody(source, "localizedHref")
        val info = File(assets, "info.js").readText()

        assertTrue(shell.contains("<html lang=\"${'$'}{esc(strings.requestedLocale)}\""))
        assertTrue(links.contains("lang=${'$'}{esc(strings.requestedLocale)}"))
        assertTrue("Dashboard hydration must retain an explicit browser language override", info.contains("fetch(localizedInfoUrl())"))
    }

    @Test fun `dynamic scripts use the browser helper only through an English-safe adapter`() {
        listOf("info.js", "configure.js").forEach { name ->
            val source = File(assets, name).readText()
            assertTrue("$name must expose one local English-fallback adapter", source.contains("function i18nText(key"))
            assertTrue("$name must guard a missing helper", source.contains("window.HaI18n&&") || source.contains("window.HaI18n &&"))
            assertTrue(
                "$name must verify the helper function before calling it",
                Regex("typeof\\s+window\\.HaI18n\\.t\\s*===\\s*[\\\"']function[\\\"']").containsMatchIn(source),
            )
            assertFalse("$name must not retain the collision-prone tr helper", Regex("function\\s+tr\\s*\\(").containsMatchIn(source))
        }
    }

    private fun literalKeys(source: String, function: String): Set<String> =
        Regex("$function\\(\\s*\\\"((?:shell|dashboard|configure)\\.[a-z0-9._-]+)\\\"")
            .findAll(source)
            .mapTo(sortedSetOf()) { it.groupValues[1] }

    private fun functionBody(source: String, name: String): String {
        val start = source.indexOf("fun $name(").takeIf { it >= 0 }
            ?: source.indexOf("function $name(").takeIf { it >= 0 }
            ?: error("missing function $name")
        val next = source.indexOf("\n    private fun ", start + 1).takeIf { it >= 0 } ?: source.length
        return source.substring(start, next)
    }
}
