package io.github.maxlyth.hapaneld.i18n

import io.github.maxlyth.hapaneld.http.PaneldServer
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

class HtmlUiCatalogueContractTest {
    private val assets = File("src/main/assets")
    private val server = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt")
    private val catalogue = JSONObject(File(assets, "i18n/en.json").readText()).getJSONObject("strings")

    @Test fun `shell dashboard and Configure literal keys are present and owned by their source surface`() {
        val usages = linkedMapOf(
            "shell" to literalKeys(server.readText(), "strings\\.get"),
            "dashboard" to (
                literalKeys(server.readText(), "strings\\.get") +
                    literalKeys(File(assets, "info.js").readText(), "i18nText") +
                    dynamicFactKeys(server.readText())
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

        assertEquals("the complete source catalogue is a reviewed release contract", 699, source.strings.size)
        assertEquals("the declared HTML UI preview scope must not shrink silently", 526, expected.size)
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

    @Test fun `every human tab keeps an explicit Simplified Chinese shell and navigation`() {
        val source = server.readText()
        val routes = listOf("setup", "profiles", "install", "fleet", "logs", "entities")

        routes.forEach { route ->
            val body = routeBody(source, route)
            assertTrue(
                "/$route must resolve the request locale so GET /$route?lang=zh-Hans emits a Chinese shell",
                body.contains("requestStrings(call)"),
            )
            assertTrue("/$route must render its translated section title", body.contains("strings.get(\"shell.nav."))
            assertTrue("/$route must report its mixed shell/body languages", body.contains("HttpHeaders.ContentLanguage"))
        }

        val chinese = CatalogueLoader { name -> File(assets, name).readText() }.strings("zh-Hans")
        assertEquals("zh-Hans", chinese.requestedLocale)
        assertFalse("the Dashboard shell sentinel must be translated", chinese.get("shell.nav.dashboard") == "Dashboard")
        assertFalse("the Configure shell sentinel must be translated", chinese.get("shell.nav.configure") == "Configure")

        val serverInstance = unsafe().allocateInstance(PaneldServer::class.java) as PaneldServer
        val localizedHref = PaneldServer::class.java.getDeclaredMethod(
            "localizedHref",
            String::class.java,
            Strings::class.java,
        ).apply { isAccessible = true }
        assertEquals(
            "/configure?lang=zh-Hans#cfg-camera",
            localizedHref.invoke(serverInstance, "/configure#cfg-camera", chinese),
        )
        assertEquals(
            "/install?repair=1&lang=zh-Hans#camera",
            localizedHref.invoke(serverInstance, "/install?repair=1#camera", chinese),
        )
        assertFalse(
            "Dashboard edit links must not drop an explicit ?lang=zh-Hans override",
            Regex("href=\\\"/(?:configure|install)#").containsMatchIn(functionBody(source, "infoHtml")),
        )
    }

    @Test fun `visible Camera dashboard copy is catalogue-backed and promoted for Simplified Chinese`() {
        val serverCamera = functionBody(server.readText(), "infoHtml")
        val scriptCamera = javascriptFunctionBody(File(assets, "info.js").readText(), "cameraCard")

        listOf(
            ">Camera stream ",
            ">reading…<",
            ">What the stream was asked for and what it is delivering.",
        ).forEach { literal ->
            assertFalse("server-rendered Camera copy is still hard-coded: $literal", serverCamera.contains(literal))
        }
        listOf(
            "{label:'Session'",
            "{label:'Encoding'",
            "{label:'Encoder'",
            "{label:'Requested'",
            "{label:'Frame rate'",
            "{label:'Bitrate'",
            "?{label:'Delivery'",
            "paint(tbl,[{label:'Camera',val:'status unavailable'",
        ).forEach { literal ->
            assertFalse("dynamic Camera copy is still hard-coded: $literal", scriptCamera.contains(literal))
        }

        val keys = literalKeys(serverCamera, "strings\\.get") + literalKeys(scriptCamera, "i18nText")
        val cameraKeys = keys.filterTo(sortedSetOf()) { it.startsWith("dashboard.camera.") }
        assertTrue("the visible Camera card must consume dashboard.camera catalogue keys", cameraKeys.size >= 20)

        val source = SourceCatalogue.parse(File(assets, "i18n/en.json").readText())
        val target = TargetCatalogue.parse(File(assets, "i18n/zh-Hans.json").readText(), source)
        cameraKeys.forEach { key ->
            val english = checkNotNull(source.strings[key]) { "$key is used but absent from English" }
            val chinese = checkNotNull(target.strings[key]) { "zh-Hans is missing $key" }
            assertEquals("zh-Hans has stale source text for $key", english.sourceHash, chinese.sourceHash)
            assertTrue(
                "zh-Hans must promote $key before the collaborator preview",
                chinese.state == TranslationState.MACHINE_CROSS_CHECKED ||
                    chinese.state == TranslationState.COMMUNITY_CORRECTED,
            )
        }
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
        Regex("$function\\(\\s*[\\\"']((?:shell|dashboard|configure)\\.[a-z0-9._-]+)[\\\"']")
            .findAll(source)
            .mapTo(sortedSetOf()) { it.groupValues[1] }

    private fun dynamicFactKeys(source: String): Set<String> =
        Regex("->\\s*\\\"([a-z0-9_]+)\\\"")
            .findAll(functionBody(source, "factLabel"))
            .mapTo(sortedSetOf()) { "dashboard.fact.${it.groupValues[1]}" }

    private fun functionBody(source: String, name: String): String {
        val start = source.indexOf("fun $name(").takeIf { it >= 0 }
            ?: source.indexOf("function $name(").takeIf { it >= 0 }
            ?: error("missing function $name")
        val next = source.indexOf("\n    private fun ", start + 1).takeIf { it >= 0 } ?: source.length
        return source.substring(start, next)
    }

    private fun routeBody(source: String, path: String): String {
        val marker = "get(\"/$path\")"
        val start = source.indexOf(marker).also { require(it >= 0) { "missing /$path route" } }
        val next = source.indexOf("\n                get(\"/", start + marker.length)
            .takeIf { it >= 0 } ?: source.length
        return source.substring(start, next)
    }

    private fun javascriptFunctionBody(source: String, name: String): String {
        val start = source.indexOf("function $name(").also { require(it >= 0) { "missing function $name" } }
        val next = source.indexOf("\nfunction ", start + 1).takeIf { it >= 0 } ?: source.length
        return source.substring(start, next)
    }

    private fun unsafe(): Unsafe {
        val field = Unsafe::class.java.getDeclaredField("theUnsafe")
        field.isAccessible = true
        return field.get(null) as Unsafe
    }
}
