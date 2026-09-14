package io.github.maxlyth.hapaneld.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Embedded mode changes presentation only: the shell below the tab bar, never a guard or approval. */
class EmbeddedShellContractTest {
    private val server = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
    private val assets = File("src/main/assets")

    private fun functionBody(name: String): String {
        val start = server.indexOf("private fun $name(")
        assertTrue(name, start >= 0)
        val next = server.indexOf("\n    private fun ", start + 1)
        return server.substring(start, if (next < 0) server.length else next)
    }

    @Test fun `the shell omits the header and switcher only when embedded`() {
        val shell = functionBody("pageShell")
        assertTrue(shell.contains("val header = if (embed != null) \"\" else"))
        assertTrue(shell.contains("val switcher = if (embed != null) \"\" else"))
        assertEquals("the switcher script is emitted only through the LAN branch", 1, Regex("assets/switcher.js").findAll(shell).count())
        assertTrue(shell.contains("<div class=\"topbar\">\$header\${navBar(active, strings, preserveExplicitEnglish, embed?.hiddenTabs.orEmpty())}</div>"))
        assertTrue(shell.contains("\$switcher<div id=\"halifebar\""))
        assertTrue(shell.contains("if (embed != null) \"\"\"<script src=\"assets/panel-assistant-sign-in.js\"></script>\"\"\" else \"\""))
    }

    @Test fun `hidden tabs leave the tab bar but keep their routes`() {
        val nav = functionBody("navBar")
        assertTrue(nav.contains("fun tab(id: String, href: String, label: String): String = if (id in hiddenTabs) \"\" else"))
        assertTrue(nav.contains("if (\"api\" in hiddenTabs) \"\" else"))
        EmbedMode.TABS.filterNot { it == "api" || it == "setup" }.forEach { tab ->
            assertTrue(tab, nav.contains("tab(\"$tab\","))
        }
        assertTrue(server.contains("get(\"/api\") {"))
    }

    @Test fun `every page route passes the parsed switch and only embedded requests skip the wizard redirect`() {
        assertEquals(1, Regex("""call\.admitEmbedMode\(\)""").findAll(server).count())
        assertTrue(server.contains("if (embed == null && call.request.uri.substringBefore('?') in WIZARD_REDIRECT_PAGES &&"))
        listOf("profiles", "install", "fleet", "logs", "entities").forEach { page ->
            assertTrue(page, Regex("""page\("$page", [^\n]*, call\.embedMode\(\)\)""").containsMatchIn(server))
        }
        assertTrue(server.contains("infoHtml(strings, call.embedMode())"))
        assertEquals("configure and setup pass the switch to the shell", 2, Regex("""^\s+embed = call\.embedMode\(\),$""", RegexOption.MULTILINE).findAll(server).count())
    }

    @Test fun `the switch is never read by a guard, an approval or a log line`() {
        val http = File("src/main/kotlin/io/github/maxlyth/hapaneld/http")
        val readers = http.walk().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            file.readLines().withIndex().filter { (_, line) ->
                (line.contains("EmbedMode.HEADER") || line.contains("embedMode()")) && file.name != "EmbedMode.kt"
            }.map { (_, line) -> line.trim() }
        }.toList()
        assertTrue(readers.toString(), readers.none { Regex("""Log\.|authorize|OriginGuard|approval""", RegexOption.IGNORE_CASE).containsMatchIn(it) })
        assertFalse(File(http, "OriginGuard.kt").readText().contains("Embed"))
    }

    @Test fun `embedded Configure never touches Home Assistant's language key`() {
        val configure = File(assets, "configure.js").readText()
        val store = configure.substringAfter("function storeBrowserLanguage(value) {").substringBefore("\n  }")
        assertTrue(store.trimStart().startsWith("if (EMBEDDED) return;"))
        val read = configure.substringAfter("function browserLanguageChoice() {").substringBefore("\n  }\n")
        assertTrue(read.indexOf("if (EMBEDDED) return \"\";") in 0 until read.indexOf("localStorage.getItem(\"selectedLanguage\")"))
        assertEquals(1, Regex("""localStorage\.getItem\("selectedLanguage"\)""").findAll(configure).count())
        assertEquals(1, Regex("""localStorage\.setItem\("selectedLanguage"""").findAll(configure).count())
    }

    @Test fun `embedded sign-in posts to the sidebar endpoint and shows catalogue results`() {
        val helper = File(assets, "panel-assistant-sign-in.js").readText()
        assertTrue(helper.contains("fetch(\"_panel_assistant/sign-in\", { method: \"POST\""))
        assertTrue(helper.contains("var key = \"shell.pa_sign_in_\" + code;"))
        listOf("internal_url_missing", "panel_unreachable", "panel_refused").forEach { assertTrue(it, helper.contains("\"$it\"")) }
        assertTrue(File(assets, "configure.js").readText().contains("window.panelAssistantSignIn().then("))
        assertTrue(File(assets, "setup.js").readText().contains("window.panelAssistantSignIn().then("))
    }
}
