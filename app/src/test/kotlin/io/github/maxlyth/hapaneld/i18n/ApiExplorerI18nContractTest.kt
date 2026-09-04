package io.github.maxlyth.hapaneld.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiExplorerI18nContractTest {
    private fun source(vararg candidates: String): String = candidates.map(::File).first(File::isFile).readText()

    private val html = source("src/main/assets/api.html", "app/src/main/assets/api.html")
    private val script = source("src/main/assets/api.js", "app/src/main/assets/api.js")
    private val server = source(
        "src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
        "app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
    )
    private val english = SourceCatalogue.parse(
        source("src/main/assets/i18n/en.json", "app/src/main/assets/i18n/en.json"),
    )

    @Test fun `external API browser asset parses as JavaScript`() {
        val file = listOf(File("src/main/assets/api.js"), File("app/src/main/assets/api.js")).first(File::isFile)
        val process = ProcessBuilder("node", "--check", file.absolutePath).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals("api.js is not valid JavaScript:\n$output", 0, process.waitFor())
    }

    @Test fun `English API catalogue is the exact frozen browser consumer set`() {
        val records = english.strings.keys.filterTo(sortedSetOf()) { it.startsWith("api.") }
        val consumers = Regex("[\\\"'](api\\.[a-z0-9._-]+)[\\\"']")
            .findAll(script)
            .mapTo(sortedSetOf()) { it.groupValues[1] }

        assertEquals(10, records.size)
        assertEquals(consumers, records)
        assertEquals(
            setOf(
                "api.action.send",
                "api.approval.conditional",
                "api.error.load_spec",
                "api.group.other",
                "api.header.back_to_panel",
                "api.intro.import",
                "api.intro.live",
                "api.intro.network",
                "api.request.body",
                "api.status.error",
            ),
            records,
        )
    }

    @Test fun `API route projects request locale and declares translated chrome plus English specification`() {
        val route = server.substringAfter("get(\"/api\") {").substringBefore("get(\"/health\") {")

        assertTrue(route.contains("val strings = requestStrings(call)"))
        assertTrue(route.contains("setOf(\"api.\", \"configure.hardened.\", \"shell.hardened.\")"))
        assertTrue(route.contains("HttpHeaders.Vary"))
        assertTrue(route.contains("HttpHeaders.AcceptLanguage"))
        assertTrue(route.contains("HttpHeaders.ContentLanguage"))
        assertTrue(route.contains("+ AppLocale.ENGLISH"))
        assertTrue(route.contains("localizedHref(\"/\", strings)"))
        assertTrue(route.contains("browserI18nPayload(strings, projectionPrefixes)"))
        assertTrue(route.contains("strings.requestedLocale"))
    }

    @Test fun `API template exposes a stable external-script hydration contract`() {
        listOf(
            "api-back",
            "api-intro-live",
            "api-intro-import",
            "api-intro-network",
            "hardened-approval-description",
            "hardened-approval-conditional-description",
            "root",
            "api-approval-key",
            "ha-i18n",
        ).forEach { id -> assertTrue("missing frozen DOM id $id", html.contains("id=\"$id\"")) }

        assertTrue(html.contains("<html lang=\"__API_LANG__\""))
        assertTrue(html.contains("href=\"__API_BACK_HREF__\""))
        assertTrue(html.contains("__API_I18N_PAYLOAD__"))
        assertTrue(html.contains("<script src=\"/assets/i18n.js\"></script>"))
        assertTrue(html.contains("<script src=\"/assets/api.js\"></script>"))
        assertFalse("API application logic must remain external and lintable", html.contains("function endpoint("))
    }

    @Test fun `browser renders localized and compatibility data only as safe text`() {
        assertFalse("API Explorer must never create markup from catalogue, spec, response, or error text", script.contains("innerHTML"))
        assertTrue(script.contains("node.textContent = String(value)"))
        assertTrue(script.contains("output.textContent = responseBody"))
        assertTrue(script.contains("el(\"span\", null, error, \"und\")"))
        assertTrue(script.contains("var output = el(\"pre\", null, null, \"und\")"))
        assertTrue(script.contains("operation.summary || \"\", \"en\""))
        assertTrue(script.contains("\" — \" + parameter.description, \"en\""))
    }

    @Test fun `approval classification and exact OpenAPI boundary are preserved`() {
        assertEquals(5, Regex("\\\"/api/v1/(?:config|config/export|config/import|restore|action)\\\"").findAll(script).count())
        assertTrue(script.contains("JSON.stringify(operation.responses || {}).indexOf(\"ApprovalRequired\")"))
        assertTrue(script.contains("configure.hardened.action_approval"))
        assertTrue(script.contains("shell.hardened.key"))
        assertEquals(1, Regex("/api/v1/openapi\\.json").findAll(script).count())
        assertFalse("OpenAPI compatibility prose stays in the machine specification", english.strings.keys.any { it.startsWith("openapi.") })
    }
}
