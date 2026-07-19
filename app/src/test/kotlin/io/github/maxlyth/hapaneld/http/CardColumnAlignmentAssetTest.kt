package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class CardColumnAlignmentAssetTest {
    private val assetsDir: File by lazy {
        listOf(File("src/main/assets"), File("app/src/main/assets"), File("../app/src/main/assets"))
            .first(File::isDirectory)
    }

    private fun asset(name: String): String = File(assetsDir, name).readText()

    private fun nodeAvailable(): Boolean = runCatching {
        ProcessBuilder("node", "--version").start().waitFor() == 0
    }.getOrDefault(false)

    private fun runNode(script: String, vararg args: String): Pair<Int, String> {
        val process = ProcessBuilder(listOf("node", "-e", script) + args)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    @Test fun threePagesUseOneObserverFreeAlignmentAuthority() {
        val shared = asset("card-column-alignment.js")
        val pages = mapOf(
            "configure.js" to "attach(\"cfg-groups\")",
            "install.js" to "attach('install-cards')",
            "info.js" to "attach('dashboard-cards')",
        )

        assertTrue(shared.contains("function normalize(root)"))
        assertTrue(shared.contains("card.getBoundingClientRect()"))
        assertTrue(shared.contains("var settleTimer = null"))
        assertTrue(shared.contains("global.setTimeout(function ()"))
        assertTrue(shared.contains("}, 120)"))
        assertEquals(1, Regex("global\\.addEventListener\\(\\\"resize\\\"").findAll(shared).count())
        assertEquals(1, Regex("visualViewport\\.addEventListener\\(\\\"resize\\\"").findAll(shared).count())
        assertEquals(1, Regex("fonts\\.ready\\.then\\(schedule\\)").findAll(shared).count())
        assertFalse(shared.contains("MutationObserver"))

        pages.forEach { (name, attachment) ->
            val source = asset(name)
            assertTrue("$name must attach its masonry root to the shared authority", source.contains(attachment))
            assertFalse("$name must not retain a page-local geometry implementation", source.contains("ColumnTops"))
            assertFalse("$name must not observe its dynamic subtree", source.contains("MutationObserver"))
            assertFalse("$name must not retain a page-local alignment timer", source.contains("ColumnAlignmentTimer"))
            assertFalse("$name must not measure masonry cards itself", source.contains("card.getBoundingClientRect()"))
        }

        val configure = asset("configure.js")
        assertTrue(configure.contains("focusHash();\n    scheduleConfigColumnAlignment();"))

        val install = asset("install.js")
        assertTrue(install.contains("scheduleInstallColumnAlignment = window.CardColumnAlignment"))
        assertTrue(install.contains("card.style.display = ''; scheduleInstallColumnAlignment()"))
        assertTrue(install.contains("btn.disabled = false; scheduleInstallColumnAlignment();"))

        val dashboard = asset("info.js")
        assertTrue(dashboard.contains("card.style.display='';refreshScreenshot(card);scheduleDashboardColumnAlignment();"))
        assertTrue(dashboard.contains("scheduleDashboardColumnAlignment();\n }\n function hydrate"))
    }

    @Test fun sharedAuthorityCoalescesAndCorrectsEveryCardInAnOffsetColumn() {
        assumeTrue("node not available", nodeAvailable())
        val script = """
            const fs=require('fs'),vm=require('vm');
            let frames=[],timers=[],windowResize=[],viewportResize=[],fontReady=[],nextTimer=0;
            function card(left,top,display){return {
              style:{position:'stale',top:'9px',display:display||''},
              classList:{contains(name){return name==='card'}},
              getBoundingClientRect(){return {left:left,top:top}}
            }}
            const first=card(0.2,10),second=card(99.6,12),third=card(100.4,51),hidden=card(200,30,'none');
            const root={children:[first,second,third,hidden]};
            global.window=global;
            global.document={getElementById(id){return id==='cards'?root:null},fonts:{ready:{then(fn){fontReady.push(fn)}}}};
            global.addEventListener=(type,fn)=>{if(type==='resize')windowResize.push(fn)};
            global.visualViewport={addEventListener(type,fn){if(type==='resize')viewportResize.push(fn)}};
            global.requestAnimationFrame=fn=>{frames.push(fn);return frames.length};
            global.setTimeout=(fn,delay)=>{const timer={id:++nextTimer,fn,delay,cleared:false};timers.push(timer);return timer};
            global.clearTimeout=timer=>{timer.cleared=true};
            vm.runInThisContext(fs.readFileSync(process.argv[1],'utf8'));

            const schedule=CardColumnAlignment.attach('cards');
            if(typeof schedule!=='function'||CardColumnAlignment.attach('cards')!==schedule)process.exit(2);
            if(CardColumnAlignment.attach('missing')!==null)process.exit(3);
            if(windowResize.length!==1||viewportResize.length!==1||fontReady.length!==1)process.exit(4);

            schedule();schedule();schedule();
            if(frames.length!==1)process.exit(5);
            frames.shift()();
            if(first.style.position!==''||first.style.top!=='')process.exit(6);
            if(second.style.position!=='relative'||second.style.top!=='-2px')process.exit(7);
            if(third.style.position!=='relative'||third.style.top!=='-2px')process.exit(8);
            if(hidden.style.position!=='stale'||hidden.style.top!=='9px')process.exit(9);
            let active=timers.filter(timer=>!timer.cleared);
            if(active.length!==1||active[0].delay!==120)process.exit(10);

            windowResize[0]();fontReady[0]();viewportResize[0]();
            if(!active[0].cleared||frames.length!==1)process.exit(11);
            frames.shift()();
            active=timers.filter(timer=>!timer.cleared);
            if(active.length!==1||active[0].delay!==120)process.exit(12);
            active[0].cleared=true;active[0].fn();
            if(second.style.top!=='-2px'||third.style.top!=='-2px')process.exit(13);
        """.trimIndent()
        val (code, output) = runNode(script, File(assetsDir, "card-column-alignment.js").absolutePath)
        assertEquals("shared card-column alignment behavior failed:\n$output", 0, code)
    }

    @Test fun servedPagesLoadSharedAuthorityImmediatelyBeforeTheirPageScript() {
        val server = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()
        listOf("configure.js", "install.js").forEach { pageScript ->
            assertTrue(
                "$pageScript must load after the shared alignment authority",
                server.contains(
                    """<script src="/assets/card-column-alignment.js"></script>
<script src="/assets/$pageScript"></script>""",
                ),
            )
        }
        assertTrue(
            "info.js must load after the shared alignment authority",
            server.contains(
                """<script src="/assets/card-column-alignment.js"></script>
<script src="/info.js"></script>""",
            ),
        )
    }

    @Test fun layoutFixtureLoadsTheSharedAuthorityBeforeDashboardCode() {
        val fixture = listOf(File("test/fixtures/info-fixture.html"), File("../test/fixtures/info-fixture.html"))
            .first(File::isFile)
            .readText()
        val shared = fixture.indexOf("card-column-alignment.js")
        val dashboard = fixture.indexOf("info.js")
        assertTrue(shared >= 0)
        assertTrue(dashboard > shared)
        assertTrue(fixture.contains("id=\"dashboard-cards\""))
    }
}
