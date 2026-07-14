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

    @Test fun dashboardIssuesAreRenderedEscapedWithReversibleSafetyControls() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        assumeTrue("node not available (skipping)", nodeAvailable())
        val script = """
            const fs=require('fs'),vm=require('vm');
            const ignoredMode=process.argv[2]==='ignored';
            function el(){return {dataset:{},className:'',textContent:'',innerHTML:'',checked:false,disabled:false,value:'',classList:{toggle(){},remove(){}},addEventListener(){},querySelector(){return el()},querySelectorAll(){return []},appendChild(v){this.child=v}}}
            const ids={};['entity-status','entity-search','entity-sync','entity-activate','entity-action-result','entity-auto-static','entity-auto-runtime','entity-issues','entity-issues-summary','entity-issues-list','entity-issues-rescan','entity-dynamic','entity-dynamic-list'].forEach(k=>ids[k]=el());
            const issue='<img src=x onerror=alert(1)>';
            global.document={hidden:false,getElementById:k=>ids[k],querySelectorAll:()=>[],querySelector:()=>null,createElement:()=>el()};
            global.setInterval=()=>0;global.confirm=()=>false;global.alert=()=>{};
            global.fetch=(url)=>Promise.resolve({ok:true,json:()=>Promise.resolve(url.includes('/issues')?{dashboard_issue_count:1,blocking_issue_count:ignoredMode?0:1,ignored_issue_count:ignoredMode?1:0,items:[{blocking:!ignoredMode,ignored:ignoredMode,fingerprint:'0123456789abcdef',view_title:issue,source_locations:[issue],rule_summary:issue,candidate_count:1600,limit:64,reason:issue,recommendation:issue}],dynamic_expressions:[{source_location:issue,literal:issue,fingerprint:'dynamic-1',truncated:true}]}:{state:'active',stream_entity_count:10,stream_mode:'filtered',catalog_count:100,suggested_count:0,last_sync_at:0,db_bytes:0}),text:()=>Promise.resolve('')});
            vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
            setImmediate(()=>{const row=ids['entity-issues-list'].child,html=row.innerHTML,dynamic=ids['entity-dynamic-list'].child.innerHTML;if(html.includes(issue)||!html.includes('&lt;img src=x onerror=alert(1)&gt;'))process.exit(2);if(ignoredMode){if(ids['entity-issues-summary'].textContent.includes('need a choice')||!ids['entity-issues-summary'].textContent.includes('resolved for activation'))process.exit(3);if(!row.child||row.child.textContent!=='Re-enable safety check')process.exit(4)}else{if(!ids['entity-issues-summary'].textContent.includes('1 entity-filter check'))process.exit(3);if(!row.child||row.child.textContent!=='Ignore potential entities and continue')process.exit(4)}if(dynamic.includes(issue)||!dynamic.includes('&lt;img src=x onerror=alert(1)&gt;'))process.exit(5);if(ids['entity-dynamic'].hidden)process.exit(6)});
        """.trimIndent()
        for (mode in listOf("blocking", "ignored")) {
            val (code, out) = run(listOf("node", "-e", script, File(dir, "entities.js").absolutePath, mode))
            assertEquals("dashboard issue presentation contract failed ($mode):\n$out", 0, code)
        }
    }

    @Test fun dashboardIssuesEndpointIsDocumented() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        val paths = JSONObject(File(dir, "openapi.json").readText()).getJSONObject("paths")
        assertTrue(paths.has("/api/v1/dashboard/entities/issues"))
    }

    @Test fun configureSaveRefreshesEntityTabInBothDirections() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        assumeTrue("node not available (skipping)", nodeAvailable())
        val script = """
            const fs=require('fs'),vm=require('vm');
            const initiallyEnabled=process.argv[2]==='true';
            let reloads=0,posted='';
            const made=[];
            function element(tag){
              const e={tag:tag||'',style:{},dataset:{},handlers:{},children:[],className:'',textContent:'',innerHTML:'',value:'',disabled:false,
                classList:{toggle(){},add(){},remove(){}},
                setAttribute(k,v){this[k]=v},addEventListener(k,v){this.handlers[k]=v},appendChild(v){this.children.push(v);return v},
                scrollIntoView(){}};
              made.push(e);return e;
            }
            const ids={};['cfg-groups','cfg-status','cfg-msg','savebtn','tab-basic','tab-adv'].forEach(k=>ids[k]=element(k));
            global.document={
              getElementById:k=>ids[k]||(ids[k]=element(k)),createElement:tag=>element(tag),
              querySelector:sel=>sel==='.nav a[href^="/entities"]'&&initiallyEnabled?element('a'):null
            };
            global.location={hash:'',reload(){reloads++}};
            global.window=global;
            const schema=[
              {key:'dashboard_package',type:'STRING',group:'Dashboard',label:'Dashboard app',available:true,picker:'renderer'},
              {key:'dashboard_entity_learning',type:'BOOL',group:'Dashboard',label:'Automatic dashboard entity filter',available:true}
            ];
            global.fetch=(url,opts)=>{
              if(opts&&opts.method==='POST'){posted=String(opts.body||'');return Promise.resolve({ok:true,json:()=>Promise.resolve({})})}
              if(url==='/api/v1/config/schema')return Promise.resolve({json:()=>Promise.resolve(schema)});
              if(url==='/api/v1/config')return Promise.resolve({json:()=>Promise.resolve({settings:{dashboard_package:'builtin',dashboard_entity_learning:initiallyEnabled?'true':'false'},ha_expose:{}})});
              if(url==='/api/v1/apps')return Promise.resolve({json:()=>Promise.resolve({apps:[]})});
              return Promise.reject(new Error('unexpected fetch '+url));
            };
            vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
            setImmediate(()=>setImmediate(()=>{
              const toggle=made.find(e=>e.role==='switch');
              if(!toggle||!toggle.handlers.click)process.exit(2);
              toggle.handlers.click();
              global.cfgSave();
              setImmediate(()=>setImmediate(()=>{
                const expected=initiallyEnabled?'false':'true';
                if(!posted.includes('dashboard_entity_learning='+expected))process.exit(3);
                if(reloads!==1)process.exit(4);
              }));
            }));
        """.trimIndent()
        for (initial in listOf(false, true)) {
            val (code, out) = run(
                listOf("node", "-e", script, File(dir, "configure.js").absolutePath, initial.toString()),
            )
            assertEquals("Configure entity-tab ${if (initial) "disable" else "enable"} transition failed:\n$out", 0, code)
        }
    }
}
