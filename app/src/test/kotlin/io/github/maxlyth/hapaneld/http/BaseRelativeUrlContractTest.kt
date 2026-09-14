package io.github.maxlyth.hapaneld.http

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every URL the web interface emits resolves against `<base href="/">`, so Panel Assistant's proxy can mount
 * the interface under its own path by rewriting that one element. A root-relative literal would escape the
 * proxy, so a new one fails here unless it is listed below with its reason.
 */
class BaseRelativeUrlContractTest {
    private val assets = File("src/main/assets")
    private val http = File("src/main/kotlin/io/github/maxlyth/hapaneld/http")

    /** Quoted literals in page scripts that start with `/` and are not URLs the page loads or links. */
    private val scriptExceptions = mapOf(
        // OpenAPI operation paths, matched against the specification's own keys (the API tab is hidden when embedded).
        "api.js" to setOf("/api/v1/config", "/api/v1/config/export", "/api/v1/config/import", "/api/v1/restore", "/api/v1/action"),
        // Rate units shown as text.
        "info.js" to setOf("/hr", "/s"),
        // Suffixes appended to the base-relative profiles API root.
        "profiles.js" to setOf("/", "/revisions/", "/report", "/probe", "/import", "/template", "/device-draft", "/rollback", "/select", "/delete", "/schema"),
        // Home Assistant dashboard routes and their placeholder, not panel URLs.
        "setup.js" to setOf("/", "/dashboard-name/tab-name"),
        "configure.js" to setOf("/", "/dashboard-name/tab-name", "/live"),
    )

    private val scriptLiteral = Regex("""(?<![\w\\])(["'`])(/(?:[A-Za-z_?#][^"'`\s]*)?)\1""")

    @Test fun `page scripts emit no root-relative URL literal`() {
        val offenders = pageSources().flatMap { file ->
            val allowed = scriptExceptions[file.name].orEmpty()
            file.readLines().withIndex()
                .filterNot { (_, line) -> line.trimStart().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
                .flatMap { (index, line) ->
                    scriptLiteral.findAll(line.replace("<base href=\"/\">", "")).map { it.groupValues[2] }
                        .filterNot { it in allowed }
                        .map { "${file.name}:${index + 1}: $it" }
                        .toList()
                }
        }
        assertEquals("root-relative literals escape the proxy base", emptyList<String>(), offenders)
    }

    @Test fun `the scanner finds the literal shapes it guards`() {
        listOf("fetch(\"/api/v1/status\")", "el.src = '/assets/x.js'", "location.href = `/configure`", "a.href = \"/\"")
            .forEach { assertTrue(it, scriptLiteral.containsMatchIn(it)) }
        listOf("fetch(\"api/v1/status\")", "x.split(\"/\")[0] + '/hr'.length", "/^\\/foo/.test(v)")
            .forEach { sample -> assertTrue(sample, scriptLiteral.findAll(sample).all { it.groupValues[2] in setOf("/", "/hr") }) }
    }

    // The base element itself is the one root-relative URL, and the proxy rewrites it.
    private val markupLiteral = Regex("""(?:(?<!<base )(?:href|src|action)=\\?["']|url=|(?:localizedHref|setupHref)\(")/(?!/)""")

    @Test fun `server markup emits no root-relative URL`() {
        val offenders = http.listFiles { f -> f.extension == "kt" }!!.sortedBy { it.name }
            // The OAuth callback page is Home Assistant's redirect target on the LAN, never proxied.
            .filterNot { it.name == "HaOAuthRoutes.kt" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> markupLiteral.containsMatchIn(line) }
                    .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim().take(120)}" }
            }
        assertEquals("root-relative markup escapes the proxy base", emptyList<String>(), offenders)
        listOf("""<a href="/configure">""", """"<a href=\"/install\">"""", "content='2;url=/configure'", """localizedHref("/api", strings)""")
            .forEach { assertTrue(it, markupLiteral.containsMatchIn(it)) }
    }

    @Test fun `every generated HTML document declares the root base first`() {
        val doctype = Regex("""<!doctype html>(?!<base href=\\?"/\\?">|<html[^>]*><head><base href="/">)""", RegexOption.IGNORE_CASE)
        val offenders = http.listFiles { f -> f.extension == "kt" }!!
            .filterNot { it.name == "HaOAuthRoutes.kt" }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> doctype.containsMatchIn(line.replace("\$themeAttr", "")) }
                    .map { (index, _) -> "${file.name}:${index + 1}" }
            }
        assertEquals(emptyList<String>(), offenders)
        assertTrue(File(assets, "api.html").readText().startsWith("""<!doctype html><html lang="__API_LANG__"><head><base href="/">"""))
        val shell = File(http, "PaneldServer.kt").readText()
        assertTrue(shell.contains("""<html lang="${'$'}{esc(strings.requestedLocale)}"${'$'}themeAttr><head><base href="/"><meta charset="utf-8">"""))
    }

    @Test fun `resolved URLs replace pathname comparisons`() {
        val install = File(assets, "install.js").readText()
        assertTrue(install.contains("new URL('api/v1/tame', document.baseURI).href"))
        assertTrue(!install.contains("new URL(form.action, location.href).pathname"))
        val buildwatch = File(assets, "buildwatch.js").readText()
        assertTrue(buildwatch.contains("new URL(\"configure\", document.baseURI).href"))
        assertTrue(!Regex("""new URL\([^)]*location\.origin\)""").containsMatchIn(pageSources().joinToString("\n") { it.readText() }))
    }

    private fun pageSources(): List<File> =
        assets.listFiles { f -> f.isFile && (f.extension == "js" || f.extension == "html") }!!.sortedBy { it.name }
}
