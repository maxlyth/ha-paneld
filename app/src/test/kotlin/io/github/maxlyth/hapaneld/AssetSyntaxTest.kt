package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Cheap "malformed asset" smoke test for the bundled `:8888` UI. The web layer (client JS, static JSON)
 * has no other automated coverage — a syntactically broken `switcher.js`/`configure.js` or a corrupt
 * `openapi.json` would otherwise ship to the fleet and only surface as a dead tab in a browser. This
 * runs `node --check` on every JS asset and `JSON.parse` on every JSON asset.
 *
 * Not a full HTTP/DOM test (the real Ktor server needs an Android Context; jsdom isn't a dep) — it only
 * catches *syntax* breakage, which is the regression class that's both real and cheap to guard here. It
 * self-skips (JUnit assume) when `node` or the assets dir is unavailable, so a runner without Node.js
 * doesn't fail the build; CI runners and the dev container both have Node, so it runs there.
 */
class AssetSyntaxTest {

    private val assetsDir: File? =
        listOf("src/main/assets", "app/src/main/assets", "../app/src/main/assets")
            .map { File(it) }
            .firstOrNull { it.isDirectory }

    private fun nodeAvailable(): Boolean =
        runCatching { run(listOf("node", "--version")).first == 0 }.getOrDefault(false)

    /** Run a command, returning (exitCode, combined stdout+stderr). */
    private fun run(cmd: List<String>): Pair<Int, String> {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    @Test fun everyJsAssetParses() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        assumeTrue("node not available (skipping)", nodeAvailable())
        val js = dir!!.listFiles { f -> f.isFile && f.name.endsWith(".js") }?.sortedBy { it.name } ?: emptyList()
        assertTrue("no .js assets found under ${dir.path}", js.isNotEmpty())
        for (f in js) {
            val (code, out) = run(listOf("node", "--check", f.absolutePath))
            assertEquals("${f.name} is not valid JavaScript:\n$out", 0, code)
        }
    }

    @Test fun everyJsonAssetParses() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        assumeTrue("node not available (skipping)", nodeAvailable())
        val json = dir!!.listFiles { f -> f.isFile && f.name.endsWith(".json") }?.sortedBy { it.name } ?: emptyList()
        for (f in json) {
            val (code, out) = run(
                listOf("node", "-e", "JSON.parse(require('fs').readFileSync(process.argv[1],'utf8'))", f.absolutePath),
            )
            assertEquals("${f.name} is not valid JSON:\n$out", 0, code)
        }
    }
}
