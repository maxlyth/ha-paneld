package io.github.maxlyth.hapaneld.dashboard

import io.github.maxlyth.hapaneld.ExternalAuthProtocol
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Executes the `dashboard_theme` document-start scripts against a simulated `localStorage` and
 * `matchMedia`, because the property that matters here is not what the script says but what it leaves
 * behind. A golden pins the bytes; this pins the ownership algebra:
 *
 *  - forcing changes exactly one field, `dark`, and preserves every other field in Home Assistant's
 *    stored theme object (a named theme and its colours are the user's);
 *  - returning to Follow hands that one field back to exactly the value it had, or removes it when it
 *    had none, and removes ha-paneld's own marker;
 *  - a panel that had no stored theme at all is left with no stored theme at all;
 *  - the `prefers-color-scheme` shim answers only colour-scheme queries and delegates the rest.
 *
 * Runs the real emitted script text, so a change to the builder that breaks the transaction fails here
 * rather than only moving a golden.
 */
class DashboardThemePolicyScriptTest {

    private fun nodeAvailable(): Boolean =
        runCatching { run(listOf("node", "--version")).first == 0 }.getOrDefault(false)

    private fun run(cmd: List<String>): Pair<Int, String> {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    /**
     * Run [scripts] in order against a store seeded with [initial] (null = the key is absent), then
     * print the resulting store as JSON. `matchMedia` is recorded so the shim can be asserted too.
     */
    private fun exec(initial: String?, vararg scripts: String): String =
        execFailing(initial, failSetAt = 0, failRemoveAt = 0, scripts = scripts)

    /**
     * As [exec], but the [failSetAt]-th `setItem` and the [failRemoveAt]-th `removeItem` throw (1-based;
     * 0 disables). Storage genuinely throws in the field — a full quota, or a WebView with site data
     * blocked — and a half-applied ownership transaction is the failure that would cost a preference.
     */
    private fun execFailing(initial: String?, failSetAt: Int, failRemoveAt: Int, vararg scripts: String): String {
        val harness = File.createTempFile("theme-policy", ".js")
        harness.deleteOnExit()
        val seed = if (initial == null) "null" else jsString(initial)
        harness.writeText(
            """
            const store = new Map();
            const seed = $seed;
            if (seed !== null) store.set('selectedTheme', seed);
            const nativeCalls = [];
            let sets = 0, removes = 0;
            globalThis.localStorage = {
              getItem: (k) => (store.has(k) ? store.get(k) : null),
              setItem: (k, v) => {
                if (++sets === $failSetAt) throw new Error('QuotaExceededError');
                store.set(k, String(v));
              },
              removeItem: (k) => {
                if (++removes === $failRemoveAt) throw new Error('SecurityError');
                store.delete(k);
              },
            };
            globalThis.window = globalThis;
            globalThis.window.top = globalThis.window;
            globalThis.matchMedia = (q) => { nativeCalls.push(String(q)); return { media: String(q), matches: 'NATIVE' }; };
            ${scripts.joinToString("\n") { it }}
            const out = {};
            for (const [k, v] of store) out[k] = v;
            // Probe the shim BEFORE reading nativeCalls, or the delegation log is captured empty and
            // the delegation assertion passes for the wrong reason.
            out['__dark'] = String(globalThis.matchMedia('(prefers-color-scheme: dark)').matches);
            out['__light'] = String(globalThis.matchMedia('(prefers-color-scheme: light)').matches);
            out['__other'] = String(globalThis.matchMedia('(min-width: 870px)').matches);
            out['__nativeCalls'] = nativeCalls.join('|');
            console.log(JSON.stringify(out));
            """.trimIndent(),
        )
        val (code, out) = run(listOf("node", harness.absolutePath))
        assertEquals("script run failed:\n$out", 0, code)
        return out.trim()
    }

    private fun jsString(v: String): String =
        "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun force(dark: Boolean) = ExternalAuthProtocol.dashboardThemePolicyJs(dark)
    private fun follow() = ExternalAuthProtocol.dashboardThemePolicyJs(null)

    private fun assumeNode() = assumeTrue("node not available (skipping)", nodeAvailable())

    // --- forcing --------------------------------------------------------------------------------

    @Test fun forcingDarkOnAPanelWithNoStoredThemeCreatesOnlyTheDarkField() {
        assumeNode()
        val out = exec(null, force(true))
        assertTrue(out, out.contains(""""selectedTheme":"{\"dark\":true}""""))
        // The marker records that the whole entry was absent, so Follow can remove it again.
        assertTrue(out, out.contains(""""haPaneldForcedThemeDark":"{\"a\":true,\"d\":null}""""))
    }

    @Test fun forcingPreservesANamedThemeAndItsColours() {
        assumeNode()
        val out = exec("""{"theme":"mushroom","primaryColor":"#ff0000","dark":false}""", force(true))
        assertTrue("named theme must survive: $out", out.contains("""\"theme\":\"mushroom\""""))
        assertTrue("colour must survive: $out", out.contains("""\"primaryColor\":\"#ff0000\""""))
        assertTrue("dark must be forced: $out", out.contains("""\"dark\":true"""))
        // The original explicit false is what Follow will hand back.
        assertTrue(out, out.contains(""""haPaneldForcedThemeDark":"{\"a\":false,\"d\":false}""""))
    }

    @Test fun forcingTwiceDoesNotOverwriteTheRecordedOriginal() {
        assumeNode()
        // The second load must not re-snapshot, or the "original" becomes the value we ourselves wrote
        // and the hand-back would return the forced value instead of the user's.
        val out = exec("""{"dark":false}""", force(true), force(true))
        assertTrue(out, out.contains(""""haPaneldForcedThemeDark":"{\"a\":false,\"d\":false}""""))
    }

    // --- handing back ---------------------------------------------------------------------------

    @Test fun followRestoresAnExplicitOriginalExactly() {
        assumeNode()
        val out = exec("""{"theme":"mushroom","dark":false}""", force(true), follow())
        assertTrue("dark handed back: $out", out.contains("""\"dark\":false"""))
        assertTrue("named theme intact: $out", out.contains("""\"theme\":\"mushroom\""""))
        assertTrue("marker removed: $out", !out.contains("haPaneldForcedThemeDark"))
    }

    @Test fun followRestoresAutoByRemovingTheDarkFieldRatherThanWritingFalse() {
        assumeNode()
        // Auto is the ABSENCE of `dark`. Writing false would invent an explicit light preference the
        // user never expressed, and the frontend treats the two differently.
        val out = exec("""{"theme":"mushroom"}""", force(true), follow())
        assertTrue("dark must be gone, not false: $out", !out.contains("""\"dark\""""))
        assertTrue("named theme intact: $out", out.contains("""\"theme\":\"mushroom\""""))
    }

    @Test fun followRemovesTheWholeEntryWhenHaPaneldCreatedIt() {
        assumeNode()
        val out = exec(null, force(true), follow())
        assertTrue("no residue at all: $out", !out.contains("selectedTheme"))
        assertTrue("marker removed: $out", !out.contains("haPaneldForcedThemeDark"))
    }

    @Test fun followKeepsAFieldTheUserAddedWhileForced() {
        assumeNode()
        // The user picked a named theme on the panel while Dark was forced. That field is theirs and
        // must survive the hand-back; only `dark` returns to its original.
        val out = exec(
            null,
            force(true),
            """localStorage.setItem('selectedTheme', JSON.stringify({theme:'mushroom', dark:true}));""",
            follow(),
        )
        assertTrue("their theme survives: $out", out.contains("""\"theme\":\"mushroom\""""))
        assertTrue("dark removed, since there was none originally: $out", !out.contains("""\"dark\""""))
    }

    @Test fun followIsANoOpWhenNothingWasEverForced() {
        assumeNode()
        val before = """{"theme":"mushroom","dark":true}"""
        val out = exec(before, follow())
        assertTrue("an unforced store is untouched: $out", out.contains("""\"theme\":\"mushroom\""""))
        assertTrue("an unforced store is untouched: $out", out.contains("""\"dark\":true"""))
    }

    @Test fun followSurvivesACorruptStoredValue() {
        assumeNode()
        // Fail-safe rather than fail-open: unparseable JSON must not throw (which would abort the rest
        // of the document-start script) and must not be replaced with a value ha-paneld invented.
        val out = exec("not json at all", force(true), follow())
        assertTrue("script completed: $out", out.startsWith("{"))
        assertTrue("marker cleared: $out", !out.contains("haPaneldForcedThemeDark"))
    }

    // --- failed writes ---------------------------------------------------------------------------

    @Test
    fun aForceWhoseSnapshotWriteFailsLeavesTheStoreUntouched() {
        assumeNode()
        // The snapshot is written BEFORE the forced value, so the one ordering that must hold is that
        // there can never be a forced value with no record of what it replaced. Failing the first write
        // must therefore abandon the whole transaction.
        val out = execFailing("""{"theme":"mushroom","dark":false}""", failSetAt = 1, failRemoveAt = 0, scripts = arrayOf(force(true)))
        assertTrue("no marker without a completed force: $out", !out.contains("haPaneldForcedThemeDark"))
        assertTrue("the user's value is untouched: $out", out.contains("""\"dark\":false"""))
        assertTrue("their theme is untouched: $out", out.contains("""\"theme\":\"mushroom\""""))
    }

    @Test
    fun aForceWhoseThemeWriteFailsStillHandsBackTheOriginalUnchanged() {
        assumeNode()
        // The other order: the snapshot landed but the forced write did not. That is safe rather than
        // half-applied, and the proof is that a hand-back from there returns the store to its exact
        // starting state — the marker recorded the true original, not something we wrote.
        val before = """{"theme":"mushroom","dark":false}"""
        val out = execFailing(before, failSetAt = 2, failRemoveAt = 0, scripts = arrayOf(force(true), follow()))
        assertTrue("marker cleared: $out", !out.contains("haPaneldForcedThemeDark"))
        assertTrue("original dark restored: $out", out.contains("""\"dark\":false"""))
        assertTrue("named theme intact: $out", out.contains("""\"theme\":\"mushroom\""""))
    }

    @Test
    fun aHandBackInterruptedBeforeItClearsItsMarkerIsIdempotentOnTheNextLoad() {
        assumeNode()
        // The restore succeeded but removing the marker threw, so the next page load runs the hand-back
        // again over an already-restored store. Running twice must equal running once.
        val once = exec("""{"theme":"mushroom","dark":false}""", force(true), follow())
        val interrupted = execFailing(
            """{"theme":"mushroom","dark":false}""",
            failSetAt = 0,
            failRemoveAt = 1,
            scripts = arrayOf(force(true), follow(), follow()),
        )
        fun stored(json: String) = json.substringAfter(""""selectedTheme":"""").substringBefore("\",\"__")
        assertEquals("a replayed hand-back must not drift", stored(once), stored(interrupted))
        assertTrue("marker eventually cleared: $interrupted", !interrupted.contains("haPaneldForcedThemeDark"))
    }

    // --- the shim -------------------------------------------------------------------------------

    @Test fun theShimAnswersOnlyColourSchemeQueriesAndDelegatesTheRest() {
        assumeNode()
        val dark = exec(null, force(true))
        assertTrue("dark query: $dark", dark.contains(""""__dark":"true""""))
        assertTrue("light query is the negation: $dark", dark.contains(""""__light":"false""""))
        // Anything else must reach the real implementation, or the frontend's layout breakpoints break.
        assertTrue("other queries delegate: $dark", dark.contains(""""__other":"NATIVE""""))
        assertTrue("delegation actually called through: $dark", dark.contains("min-width: 870px"))

        val light = exec(null, force(false))
        assertTrue("light policy: $light", light.contains(""""__dark":"false""""))
        assertTrue("light policy: $light", light.contains(""""__light":"true""""))
        assertTrue("other queries delegate: $light", light.contains(""""__other":"NATIVE""""))
    }

    @Test fun followInstallsNoShimAtAll() {
        assumeNode()
        val out = exec(null, follow())
        assertTrue("no shim under Follow: $out", out.contains(""""__dark":"NATIVE""""))
        assertTrue("no shim under Follow: $out", out.contains(""""__light":"NATIVE""""))
    }

    @Test fun theShimExposesBothTheModernAndDeprecatedListenerApis() {
        assumeNode()
        // themes-mixin uses the deprecated addListener; a missing method would throw inside the
        // frontend's own firstUpdated and take the page down with it.
        val out = exec(
            null,
            force(true),
            """
            const mq = window.matchMedia('(prefers-color-scheme: dark)');
            const names = ['addListener','removeListener','addEventListener','removeEventListener','dispatchEvent'];
            localStorage.setItem('__api', names.map((n) => n + '=' + (typeof mq[n])).join(','));
            mq.addListener(() => {});
            mq.addEventListener('change', () => {});
            localStorage.setItem('__called', 'ok');
            """.trimIndent(),
        )
        for (name in listOf("addListener", "removeListener", "addEventListener", "removeEventListener")) {
            assertTrue("$name must exist: $out", out.contains("$name=function"))
        }
        assertTrue("listeners must be callable: $out", out.contains(""""__called":"ok""""))
    }

    // --- byte identity --------------------------------------------------------------------------

    private fun golden(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/injection-golden/$name")) { "missing golden $name" }
            .readBytes().toString(Charsets.UTF_8).removeSuffix("\n")

    @Test fun themePolicyScriptsAreByteIdentical() {
        assertEquals(golden("themePolicy_dark.js"), force(true))
        assertEquals(golden("themePolicy_light.js"), force(false))
        assertEquals(golden("themePolicy_follow.js"), follow())
    }

    @Test fun everyThemePolicyScriptRunsOnlyInTheTopFrame() {
        for (script in listOf(force(true), force(false), follow())) {
            assertTrue(script, script.startsWith("(()=>{" + InjectionScript.TOP_FRAME_GUARD))
        }
    }
}
