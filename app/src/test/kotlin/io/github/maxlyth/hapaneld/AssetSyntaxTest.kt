package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONObject
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

    @Test fun dashboardIssuesAreRenderedAsEscapedReadOnlyDiagnostics() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        assumeTrue("node not available (skipping)", nodeAvailable())
        val script = """
            const fs=require('fs'),vm=require('vm');
            function el(){return {dataset:{},className:'',textContent:'',innerHTML:'',checked:false,disabled:false,value:'',classList:{toggle(){},remove(){}},addEventListener(){},querySelector(){return el()},querySelectorAll(){return []},appendChild(v){this.child=v}}}
            const ids={};['entity-status','entity-search','entity-sync','entity-activate','entity-action-result','entity-auto-static','entity-auto-runtime','entity-issues','entity-issues-summary','entity-issues-list','entity-issues-rescan','entity-dynamic','entity-dynamic-list'].forEach(k=>ids[k]=el());
            const issue='<img src=x onerror=alert(1)>';
            global.document={hidden:false,getElementById:k=>ids[k],querySelectorAll:()=>[],querySelector:()=>null,createElement:()=>el()};
            global.setInterval=()=>0;global.confirm=()=>false;global.alert=()=>{};
            global.fetch=(url)=>Promise.resolve({ok:true,json:()=>Promise.resolve(url.includes('/issues')?{dashboard_issue_count:1,blocking_issue_count:1,items:[{blocking:true,view_title:issue,source_locations:[issue],rule_summary:issue,candidate_count:1600,limit:64,reason:issue,recommendation:issue}],dynamic_expressions:[{source_location:issue,literal:issue,fingerprint:'dynamic-1',truncated:true}]}:{state:'active',stream_entity_count:10,stream_mode:'filtered',catalog_count:100,suggested_count:0,last_sync_at:0,db_bytes:0}),text:()=>Promise.resolve('')});
            vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
            setImmediate(()=>{const html=ids['entity-issues-list'].child.innerHTML,dynamic=ids['entity-dynamic-list'].child.innerHTML;if(html.includes(issue)||!html.includes('&lt;img src=x onerror=alert(1)&gt;'))process.exit(2);if(!ids['entity-issues-summary'].textContent.includes('1 blocking'))process.exit(3);if(dynamic.includes(issue)||!dynamic.includes('&lt;img src=x onerror=alert(1)&gt;'))process.exit(4);if(ids['entity-dynamic'].hidden)process.exit(5)});
        """.trimIndent()
        val (code, out) = run(listOf("node", "-e", script, File(dir, "entities.js").absolutePath))
        assertEquals("dashboard issue presentation contract failed:\n$out", 0, code)
    }

    @Test fun dashboardIssuesEndpointIsDocumented() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        val paths = JSONObject(File(dir, "openapi.json").readText()).getJSONObject("paths")
        assertTrue(paths.has("/api/v1/dashboard/entities/issues"))
    }
}
