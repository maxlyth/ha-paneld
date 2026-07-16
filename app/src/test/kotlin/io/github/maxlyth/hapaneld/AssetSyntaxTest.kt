package io.github.maxlyth.hapaneld

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val js = dir!!.walkTopDown().filter { it.isFile && it.name.endsWith(".js") }.sortedBy { it.path }.toList()
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
        val json = dir!!.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.sortedBy { it.path }.toList()
        for (f in json) {
            val (code, out) = run(
                listOf("node", "-e", "JSON.parse(require('fs').readFileSync(process.argv[1],'utf8'))", f.absolutePath),
            )
            assertEquals("${f.name} is not valid JSON:\n$out", 0, code)
        }
    }

    @Test fun installLinksRequireGithubHttps() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        assumeTrue("node not available (skipping)", nodeAvailable())
        val script = """
            const fs=require('fs'),vm=require('vm');
            const attrs={notes:'https://github.com/example/project/releases/tag/v1',apk:'https://github.com/example/project/releases/download/v1/app.apk',installable:'1'};
            const option={getAttribute(k){return attrs[k.replace('data-','')]||''}};
            function link(){return {href:'stale',style:{},removeAttribute(k){if(k==='href')this.href=''}}}
            const notes=link(),download=link(),button={disabled:false,getAttribute(){return '1'}};
            const row={querySelector(s){if(s==='.cvsel')return {selectedOptions:[option]};if(s==='.cnotes')return notes;if(s==='.cinstall')return button;if(s==='.cdl')return download;return null}};
            global.window=global;
            global.document={querySelector(){return row},querySelectorAll(){return []},getElementById(){return null},createElement(){return {}}};
            global.fetch=()=>Promise.reject(new Error('unused'));
            vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
            function check(good){
              global.verChanged('paneld');
              if(good){if(!notes.href.startsWith('https://github.com/')||notes.style.visibility!=='visible')process.exit(2);if(!download.href.startsWith('https://github.com/')||download.style.display!=='')process.exit(3)}
              else{if(notes.href||notes.style.visibility!=='hidden')process.exit(4);if(download.href||download.style.display!=='none')process.exit(5)}
            }
            check(true);
            for(const bad of ['javascript:alert(1)','http://github.com/example/project','https://github.com.evil.example/project']){
              attrs.notes=bad;attrs.apk=bad;notes.href='stale';download.href='stale';check(false);
            }
        """.trimIndent()
        val (code, out) = run(listOf("node", "-e", script, File(dir, "install.js").absolutePath))
        assertEquals("Install links accepted a non-GitHub HTTPS target:\n$out", 0, code)
    }

    @Test fun installPickerPrefersNewestInstallableVersion() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        assumeTrue("node not available (skipping)", nodeAvailable())
        val script = """
            const fs=require('fs'),vm=require('vm');
            const fallback=process.argv[2]==='fallback';
            function option(){return {attrs:{},value:'',textContent:'',setAttribute(k,v){this.attrs[k]=v},getAttribute(k){return this.attrs[k]||''}}}
            const vsel={children:[],selectedIndex:-1,_html:'',appendChild(o){this.children.push(o)},
              set innerHTML(v){this._html=v;this.children=[];this.selectedIndex=-1},get innerHTML(){return this._html},
              get selectedOptions(){return this.selectedIndex>=0?[this.children[this.selectedIndex]]:[]}};
            const chan={value:'stable'},current={textContent:'1.0.0'};
            const link={href:'',style:{},removeAttribute(k){if(k==='href')this.href=''}};
            const button={disabled:true,getAttribute(){return '1'}};
            const row={querySelector(s){if(s==='.cchan')return chan;if(s==='.cvsel')return vsel;if(s==='.cver')return current;if(s==='.cnotes')return link;if(s==='.cinstall')return button;if(s==='.cdl')return null;return null}};
            const versions=fallback?
              [{tag:'v3',version:'3.0.0',installable:false},{tag:'v2',version:'2.0.0',installable:false},{tag:'v1',version:'1.0.0',installable:false}]:
              [{tag:'v3',version:'3.0.0',installable:false},{tag:'v2',version:'2.0.0',installable:true},{tag:'v1',version:'1.0.0',installable:true}];
            global.window=global;
            global.document={querySelector(){return row},querySelectorAll(){return []},getElementById(){return null},createElement(){return option()}};
            global.fetch=()=>Promise.resolve({json:()=>Promise.resolve({versions})});
            vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));
            global.loadVersions('paneld');
            setImmediate(()=>setImmediate(()=>{const expected=fallback?2:1;if(vsel.selectedIndex!==expected)process.exit(2)}));
        """.trimIndent()
        for (mode in listOf("recommended", "fallback")) {
            val (code, out) = run(listOf("node", "-e", script, File(dir, "install.js").absolutePath, mode))
            assertEquals("Install picker did not select the expected version ($mode):\n$out", 0, code)
        }
    }

    @Test fun navigationTargetsDoNotRoundTripThroughDomText() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        val switcher = File(dir, "switcher.js").readText()
        assertFalse("peer navigation target must not be stored in a DOM attribute", switcher.contains("data-base"))
        assertTrue("peer navigation must use the validated in-memory target", switcher.contains("targets[sel.selectedIndex]"))
        assertTrue("peer ports must be validated", switcher.contains("Number.isInteger(port)"))
        assertTrue("peer IPv4 octets must be bounded", switcher.contains("Number(part) > 255"))

        val info = File(dir, "info.js").readText()
        assertFalse("screenshot URL must not be copied from DOM text", info.contains("im.src=im.getAttribute('data-src')"))
        assertTrue("hydration must use the fixed same-origin screenshot endpoint", info.contains("im.src='/api/v1/screenshot.png'"))
    }

    @Test fun performanceCardUsesDirectEvidenceAndLabelsCompanionAsAProxy() {
        val dir = assetsDir
        assumeTrue("assets dir not found (skipping)", dir != null)
        val info = File(dir, "info.js").readText()
        assertTrue(info.contains("built-in live instrumentation"))
        assertTrue(info.contains("State-event main thread"))
        assertTrue(info.contains("renderer CPU proxy"))
        assertTrue(info.contains("not actual tap latency"))
        assertTrue(info.contains("WebView remote debugging"))
        assertFalse(info.contains("label:'How it feels'"))
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
              {key:'dashboard_package',type:'STRING',group:'Dashboard',label:'Dashboard app',available:true,picker:'renderer',ha:true},
              {key:'dashboard_entity_learning',type:'BOOL',group:'Dashboard',label:'Automatic dashboard entity filter',available:true,ha:true}
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
                const params=new URLSearchParams(posted);
                if(params.get('dashboard_entity_learning')!==expected)process.exit(3);
                if(reloads!==1)process.exit(4);
                if(params.has('dashboard_package')||params.has('ha_expose_dashboard_package')||params.has('ha_expose_dashboard_entity_learning'))process.exit(5);
                if(Array.from(params.keys()).length!==1)process.exit(6);
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
