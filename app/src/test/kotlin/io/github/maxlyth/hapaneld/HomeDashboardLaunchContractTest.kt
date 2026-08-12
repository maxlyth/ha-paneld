package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.testsupport.TestSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DashboardActivity's launch-cache wiring is lifecycle code on a real Activity (no Robolectric in
 * this repo), so the renderer-side contract is pinned against the source, the established idiom for
 * this file (see HomeDashboardResolutionGateTest). The pure policy itself is unit-tested in
 * HomeDashboardLaunchCacheTest / HomeDashboardLaunchCacheConfigTest.
 */
class HomeDashboardLaunchContractTest {

    private val source = TestSources.kotlin("DashboardActivity.kt").readText()

    private val resolver = source.substring(
        source.indexOf("private fun resolveHomeDashboardAndLoad"),
        source.indexOf("private fun navigateAfterHomeDashboardCorrection"),
    )

    private val correction = source.substring(
        source.indexOf("private fun navigateAfterHomeDashboardCorrection"),
        source.indexOf("@SuppressLint(\"SetJavaScriptEnabled\")\n    private fun buildCompatibleAndLoad"),
    )

    private val retry = source.substring(
        source.indexOf("private val homeDashboardRetry"),
        source.indexOf("/** Endpoint the on-panel Home Assistant sign-in is currently showing"),
    )

    @Test fun `a cached owner-matched path navigates immediately and is marked provisional`() {
        val cacheRead = resolver.indexOf("cachedHomeDashboardLaunchPath()")
        val liveResolution = resolver.indexOf("EntityLearningRuntime.resolveHomeDashboard")
        assertTrue(cacheRead in 0 until liveResolution)
        val cachedBranch = resolver.substring(cacheRead, resolver.indexOf("if (provisionalPath == null)"))
        assertTrue(cachedBranch.contains("HomeDashboardSource.CACHED"))
        assertTrue(cachedBranch.contains("confirmed = false"))
        assertTrue(cachedBranch.contains("buildCompatibleAndLoad(config)"))
    }

    @Test fun `the selecting screen shows only when nothing provisional is rendering`() {
        val screen = resolver.indexOf("\"Selecting the Home Assistant dashboard\"")
        val gate = resolver.indexOf("if (provisionalPath == null)")
        assertTrue(gate in 0 until screen)
    }

    @Test fun `a transient failure keeps the cache and retries quietly behind a rendering page`() {
        val failure = resolver.substring(
            resolver.indexOf("if (resolution == null)"),
            resolver.indexOf("homeDashboardRetryPolicy.reset()"),
        )
        // The retry is scheduled unconditionally; the teardown screen is gated on nothing rendering.
        assertTrue(failure.contains("main.postDelayed(homeDashboardRetry, delay)"))
        val screen = failure.indexOf("\"Home Assistant dashboard list unavailable\"")
        val gate = failure.indexOf("if (shownPath == null || web == null)")
        assertTrue(gate in 0 until screen)
        // The transient branch never touches the persisted cache, in either direction.
        assertFalse(failure.contains("clearHomeDashboardLaunchPathIfOwned"))
        assertFalse(failure.contains("setHomeDashboardLaunchPathIfOwned"))
    }

    @Test fun `a confirmed empty dashboard list clears the cache with the screen`() {
        val confirmedNone = resolver.substring(
            resolver.indexOf("if (resolution.path == null)", resolver.indexOf("homeDashboardRetryPolicy.reset()")),
            resolver.indexOf("setHomeDashboardLaunchPathIfOwned"),
        )
        assertTrue(confirmedNone.contains("clearHomeDashboardLaunchPathIfOwned(launchOwner)"))
        assertTrue(confirmedNone.contains("\"No Home Assistant dashboards available\""))
    }

    @Test fun `every successful live resolution is the only writer of the cache`() {
        // One write site in the whole renderer, inside the resolution-success path — an MQTT navigate
        // or idle return can never stamp the cache.
        assertEquals(1, Regex("setHomeDashboardLaunchPathIfOwned").findAll(source).count())
        assertTrue(resolver.contains("setHomeDashboardLaunchPathIfOwned(launchOwner, resolution.path)"))
    }

    @Test fun `a differing live answer corrects the rendering page and a matching one leaves it alone`() {
        assertTrue(resolver.contains("HomeDashboardLaunchCache.refreshOutcome(shownPath, resolution)"))
        assertTrue(resolver.contains("navigateAfterHomeDashboardCorrection(resolution.path)"))
        // The no-page path still builds, so a cacheless cold start is unchanged.
        assertTrue(resolver.contains("shownPath == null || web == null -> buildCompatibleAndLoad(currentConfig)"))
    }

    @Test fun `the correction prefers the live frontend bus and reloads otherwise`() {
        assertTrue(correction.contains("sendBusNavigate(path)"))
        assertTrue(correction.contains("rotateBusDocument"))
        assertTrue(correction.contains("loadUrl(target)"))
        assertTrue(correction.contains("onLoadStarted()"))
    }

    @Test fun `a navigation chosen after the provisional page outranks the correction`() {
        // Every deliberate-target site bumps the epoch…
        val busNavigate = source.substring(
            source.indexOf("private fun sendBusNavigate"),
            source.indexOf("private fun noteDeliberateDashboardNavigation"),
        )
        assertTrue(busNavigate.contains("noteDeliberateDashboardNavigation()"))
        // Seven call sites plus the declaration: bus navigate, first chosen-target load, relaunch
        // load, the correction's own full load, main-frame navigation START, redirect/renderer
        // navigation at onPageStarted, and SPA history change. A new navigation route that forgets to bump the epoch is the
        // defect this count guards.
        assertEquals(7, Regex("noteDeliberateDashboardNavigation\\(\\)").findAll(source).count() - 1)
        // …the provisional launch captures the epoch its own load set…
        assertTrue(resolver.contains("provisionalHomeDashboardEpoch = dashboardNavigationEpoch"))
        // …and the on-screen correction applies only while they still match; the store always wins.
        val corrected = resolver.substring(
            resolver.indexOf("RefreshOutcome.CORRECTED"),
            resolver.indexOf("else -> Unit"),
        )
        assertTrue(corrected.contains("if (dashboardNavigationEpoch != provisionalEpoch)"))
        assertTrue(corrected.contains("navigateAfterHomeDashboardCorrection(resolution.path)"))
        val persistIndex = resolver.indexOf("setHomeDashboardLaunchPathIfOwned(launchOwner, resolution.path)")
        assertTrue(persistIndex in 0 until resolver.indexOf("RefreshOutcome.CORRECTED"))
    }

    @Test fun `a bus correction proves itself within a bound or escalates to a full load`() {
        // The verifier is armed with the named bound, judges by the pure convergence rule, is
        // cancelled by any newer navigation, and its escalation is the shared full-load route.
        assertTrue(correction.contains("CORRECTION_VERIFY_MS"))
        assertTrue(correction.contains("HomeDashboardLaunchCache.correctionConverged(currentPath, path)"))
        assertTrue(correction.contains("dashboardNavigationEpoch != issuedEpoch) return@postDelayed"))
        assertTrue(correction.contains("loadCorrectedHomeDashboard()"))
        val fallback = source.substring(
            source.indexOf("private fun loadCorrectedHomeDashboard"),
            source.indexOf("@SuppressLint(\"SetJavaScriptEnabled\")\n    private fun buildCompatibleAndLoad"),
        )
        assertTrue(fallback.contains("rotateBusDocument"))
        assertTrue(fallback.contains("noteDeliberateDashboardNavigation()"))
        assertTrue(fallback.contains("onLoadStarted()"))
    }

    @Test fun `the scheduled retry never invalidates a provisional page it would blank`() {
        assertTrue(retry.contains("if (homeDashboardResolution?.confirmed != false) invalidateHomeDashboardResolution"))
    }

    @Test fun `a failed durable invalidation keeps retrying instead of reporting convergence`() {
        val confirmedNone = resolver.substring(
            resolver.indexOf("if (resolution.path == null)", resolver.indexOf("homeDashboardRetryPolicy.reset()")),
            resolver.indexOf("setHomeDashboardLaunchPathIfOwned"),
        )
        // The return value is consumed, not discarded: a stale row that survives deletion would be
        // replayed by the next launch or a Retry while the screen claimed a settled state.
        assertTrue(confirmedNone.contains("if (!currentConfig.clearHomeDashboardLaunchPathIfOwned(launchOwner))"))
        assertTrue(confirmedNone.contains("main.postDelayed(homeDashboardRetry, delay)"))
        assertTrue(confirmedNone.contains("clear its stored dashboard"))
    }

    @Test fun `the resolver protocol itself never emits the cached source`() {
        val protocol = TestSources.kotlin("dashboard/EntityLearningProtocol.kt").readText()
        val resolveBody = protocol.substring(
            protocol.indexOf("fun resolveHomeDashboard("),
            protocol.indexOf("/** Accept only well-formed `mdi:` icon names"),
        )
        assertFalse(resolveBody.contains("CACHED"))
    }

    @Test fun `navigation inside the frontend outranks a pending correction`() {
        // The generation guard must observe REAL navigation (tapped link, back, SPA history change),
        // not only the app's own dispatch sites — a user who walks away from the provisional page
        // must not be yanked back by the delayed correction.
        // The file has more than one WebViewClient, so anchor the end marker AFTER the start.
        val historyHook = source.indexOf("override fun doUpdateVisitedHistory")
        val client = source.substring(
            historyHook,
            source.indexOf("override fun onPageFinished", historyHook),
        )
        assertTrue(client.contains("observedDashboardNavigationIsForeign(url)"))
        assertTrue(client.contains("noteDeliberateDashboardNavigation()"))
        // Ownership must also transfer when an allowed main-frame navigation BEGINS: a correction
        // completing between shouldOverrideUrlLoading and doUpdateVisitedHistory would otherwise
        // overwrite a navigation the user had already started.
        val override = source.substring(
            source.indexOf("override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {", historyHook - 40_000),
            historyHook,
        )
        assertTrue(override.contains("observedDashboardNavigationIsForeign(request.url.toString())"))
        assertTrue(override.contains("noteDeliberateDashboardNavigation()"))
        // Redirects and renderer-initiated navigations bypass shouldOverrideUrlLoading entirely and
        // surface at onPageStarted, which previously rotated the document without claiming.
        // Search BACKWARDS from the history hook: an earlier WebViewClient (the on-panel sign-in)
        // also defines onPageStarted, and a forward search can land on it.
        val started = source.substring(source.lastIndexOf("override fun onPageStarted", historyHook), historyHook)
        assertTrue(started.contains("if (observedDashboardNavigationIsForeign(url)) noteDeliberateDashboardNavigation()"))
        assertTrue(started.indexOf("if (expected == url) return") < started.indexOf("observedDashboardNavigationIsForeign"))
        // After the frontend connects the page is the user's: a view change inside one dashboard
        // counts, which a root-only comparison silently allowed a correction to overrule.
        val foreignFn = source.substring(
            source.indexOf("private fun observedDashboardNavigationIsForeign"),
            source.indexOf("/** The only app→frontend external-bus evaluation site."),
        )
        assertTrue(foreignFn.contains("if (frontendConnected)"))
        assertTrue(foreignFn.contains("HomeDashboardLaunchCache.sameDashboardRoute(observed, claimed)"))
        val foreign = source.substring(
            source.indexOf("private fun observedDashboardNavigationIsForeign"),
            source.indexOf("/** The only app→frontend external-bus evaluation site."),
        )
        // Before the frontend connects the page is still OUR load settling (frontend default view,
        // server redirect), so only a different dashboard ROOT counts — otherwise every launch would
        // cancel its own correction. An unparseable location is never foreign.
        assertTrue(foreign.contains("HomeDashboardLaunchCache.dashboardRootOf(observed) != HomeDashboardLaunchCache.dashboardRootOf(claimed)"))
        assertTrue(foreign.contains("?: return false"))
        // Every app-issued navigation records its target so the comparison has a baseline.
        assertTrue(Regex("noteAppNavigationTarget\\(").findAll(source).count() >= 4)
    }

    @Test fun `the refresh behind a provisional page forces a live read`() {
        assertTrue(resolver.contains("forceLive = provisionalPath != null"))
    }

    @Test fun `a lost WebView rebuilds from cache before the in-flight job short-circuits`() {
        val rebuild = resolver.indexOf("home dashboard rebuilt from cache")
        val earlyReturn = resolver.indexOf("if (homeDashboardCheckingOwner == owner && homeDashboardJob?.isActive == true) return")
        assertTrue(rebuild in 0 until earlyReturn)
        val block = resolver.substring(resolver.indexOf("if (web == null) {"), earlyReturn)
        assertTrue(block.contains("cachedHomeDashboardLaunchPath()"))
        assertTrue(block.contains("buildCompatibleAndLoad(config)"))
        // The rebuilt resolution must ALSO be provisional and epoch-anchored. A battery survivor
        // proved this site was unpinned: marking it confirmed here would let a rebuild outlive the
        // live answer that is supposed to supersede it, and no assertion would have noticed.
        assertTrue(block.contains("HomeDashboardSource.CACHED"))
        assertTrue(block.contains("confirmed = false"))
        assertTrue(block.contains("provisionalHomeDashboardEpoch = dashboardNavigationEpoch"))
        // Both provisional sites (first launch and post-crash rebuild) mark it unconfirmed.
        assertEquals(2, Regex("confirmed = false").findAll(resolver).count())
    }
}
