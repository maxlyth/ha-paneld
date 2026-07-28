package io.github.maxlyth.hapaneld.http

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Structural half of the Dashboard placement contract; browser behaviour is covered separately. */
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

    /** The responsive header must settle before page content is laid out. */
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

    /** A header re-fit must not impersonate a viewport resize and disturb card placement. */
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

    /** The Dashboard uses real card heights so restored scroll placement and learned sizes share one authority. */
    @Test fun dashboardCardsDoNotUseAnEstimatedIntrinsicSize() {
        val css = File(assetsDir, "info.css").readText()
        assertTrue(
            "the Dashboard wall must opt out of the content-visibility placeholder",
            css.contains("#dashboard-cards>.card{content-visibility:visible;contain-intrinsic-size:auto}"),
        )
    }

}
