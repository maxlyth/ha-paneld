package io.github.maxlyth.hapaneld.http

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structural half of the Dashboard card-wall placement contract. The behavioural half lives in
 * `test/browser-behavior.test.mjs`; these are the facts that must not be re-broken by an edit and that a
 * browser assertion can only observe with the right timing.
 *
 * Regression: on narrow viewports the Dashboard wall was displaced in opposite directions — above the
 * correct position below 858px and below it above 858px — plus an 81px snap on every narrow load. Measured
 * on the offline harness at 480px: reload displaced the wall -412.84px in 3/3 runs, and CLS was 0.084 at
 * 360/480/600px against 0 at >=700px.
 */
class DashboardPlacementContractTest {
    private val assetsDir: File =
        listOf("src/main/assets", "app/src/main/assets", "../app/src/main/assets")
            .map(::File)
            .first { it.isDirectory }

    private val serverSource: File =
        listOf(
            "src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
            "app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
            "../app/src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt",
        ).map(::File).first { it.isFile }

    /**
     * The header script decides the sticky bar's final height (tab-bar collapse + header item hiding), so it
     * must run before any page content is laid out. As a tail script it arrived after first paint, the tab
     * bar painted wrapped, and the whole wall then snapped 81px upward (topbar 127.86px -> 46.86px).
     */
    @Test fun headerFitScriptIsEmittedBeforeThePageBody() {
        val shell = serverSource.readText()
        val script = shell.indexOf("""<script src="/assets/switcher.js"></script>""")
        val verbar = shell.indexOf("""<div id="verbar" class="setup"""")
        val body = shell.indexOf("\n\$body\n")
        assertTrue("switcher.js is not emitted in the page shell", script > 0)
        assertTrue("the version bar anchor moved; re-check the shell order", verbar > 0)
        assertTrue("the \$body placeholder moved; re-check the shell order", body > 0)
        assertTrue(
            "switcher.js must be emitted before the page body so the topbar height is final at first paint",
            script < verbar && script < body,
        )
    }

    /**
     * A header re-fit is a header concern. Broadcasting a synthetic `resize` also drove the card-column
     * aligner, CardSizeMemory's release(), the info.js high-water-mark reset and fitControls() at whatever
     * moment /api/v1/peers resolved — and only on a multi-panel LAN, which is why the displacement was
     * reproducible but inconsistent. Measured at 900px: +38px drift on 2 of 3 loads, 0 on the third.
     */
    @Test fun headerRefitDoesNotBroadcastASyntheticResize() {
        val source = File(assetsDir, "switcher.js").readText()
        assertFalse(
            "switcher.js must not dispatch a synthetic resize — call the header authority directly",
            source.contains("dispatchEvent(new Event('resize'))") ||
                source.contains("dispatchEvent(new Event(\"resize\"))"),
        )
        assertTrue(source.contains("window.PanelHeaderFit = { refit: fitAll };"))
        assertTrue(source.contains("if (window.PanelHeaderFit) window.PanelHeaderFit.refit();"))
    }

    /**
     * The sticky bar's height depends on whether the tab bar collapsed, which depends on the rendered tab
     * labels — no media query can state it. It is measured once and published, so nothing keeps a frozen
     * copy. profiles.css previously carried `top:91px`/`83px`: an 8px step where the padding step is 12px,
     * and neither subtracted the ~40px the hamburger collapse removes.
     */
    @Test fun topbarHeightIsMeasuredAndPublishedRatherThanHardcoded() {
        val switcher = File(assetsDir, "switcher.js").readText()
        assertTrue(switcher.contains("--topbar-h"))
        assertTrue(switcher.contains("function publishTopbarHeight()"))
        assertTrue(
            "the published height must come from a measurement of the real bar",
            switcher.contains("bar.getBoundingClientRect().height"),
        )

        val profiles = File(assetsDir, "profiles.css").readText()
        assertTrue(
            "the sticky profile toolbar must read the published topbar height",
            profiles.contains(".profile-toolbar{position:sticky;top:var(--topbar-h,"),
        )
        assertFalse("profiles.css must not re-introduce a frozen topbar height", profiles.contains("top:83px"))
        assertFalse("profiles.css must not re-introduce a frozen topbar height", profiles.contains("top:91px;"))
    }

    /**
     * The Dashboard wall owns its card footprints and must never fall back to an estimated intrinsic size.
     * The shared `contain-intrinsic-size:auto 300px` under-ran 16 of the 17 real cards at 480px, leaving the
     * document ~413px short so a reload restored the scroll onto the wrong content. It also hid the real
     * heights from CardSizeMemory, which then persisted 330px — the placeholder itself — for every
     * off-screen card, so the estimator and the memory defeated each other.
     */
    @Test fun dashboardCardsDoNotUseAnEstimatedIntrinsicSize() {
        val css = File(assetsDir, "info.css").readText()
        assertTrue(
            "the Dashboard wall must opt out of the content-visibility placeholder",
            css.contains("#dashboard-cards>.card{content-visibility:visible;contain-intrinsic-size:auto}"),
        )
    }

    /**
     * Records the arithmetic the shared placeholder rule still depends on, so the next edit cannot restate a
     * one-column claim that the same rule falsifies. `.cards` is `columns:400px` with an 18px gap, so a
     * second column needs 2*400+18 = 818px of content box. The rule's own `.wrap{padding:8px}` makes that a
     * 834px viewport, while its 857px threshold was derived from the 20px padding it replaces — so
     * 834-857px is a two-column masonry running single-column-only placeholders.
     */
    @Test fun singleColumnPlaceholderThresholdIsDocumentedAgainstTheColumnArithmetic() {
        val css = File(assetsDir, "info.css").readText()
        assertTrue(css.contains(".cards{columns:400px;column-gap:18px}"))
        assertTrue(css.contains("@media (max-width:857px){.wrap{padding:8px}"))
        assertTrue(
            "the true one-column boundary must stay recorded beside the rule that depends on it",
            css.contains("834px of viewport"),
        )
    }
}
