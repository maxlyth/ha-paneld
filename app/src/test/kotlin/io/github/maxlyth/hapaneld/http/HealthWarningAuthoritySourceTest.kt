package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the "one warning-list authority" consolidation: every render surface reads the WebView/renderer
 * verdict from a single request-scoped snapshot (so a page can't disagree with itself), routes the
 * HealthAudit dispatch through one helper, and keeps each per-surface `updates` input distinct.
 */
class HealthWarningAuthoritySourceTest {
    private val server = File("src/main/kotlin/io/github/maxlyth/hapaneld/http/PaneldServer.kt").readText()

    @Test fun webViewAndRendererAreProbedExactlyOncePerRender() {
        // Both health inputs are fetched only inside the request snapshot (healthInputs); no surface
        // re-probes them independently, which is what previously skewed a single page's banner vs facts.
        assertEquals(1, Regex("PanelInfo\\.webViewStatus\\(appContext\\)").findAll(server).count())
        assertEquals(1, Regex("PanelInfo\\.dashboardRenderers\\(appContext").findAll(server).count())
        assertTrue(
            server.contains(
                "private fun healthInputs(): HealthInputs = HealthInputs(\n" +
                    "        PanelInfo.webViewStatus(appContext),\n" +
                    "        PanelInfo.dashboardRenderers(appContext, config.dashboardPackage, config.haUrl).isNotEmpty(),\n" +
                    "        config.mqttBroker.isNotBlank(),\n" +
                    "    )",
            ),
        )
    }

    @Test fun missingRendererFindingWaitsForConfiguredBroker() {
        assertTrue(server.contains("brokerConfigured = h.brokerConfigured"))
        assertTrue(server.contains("h.brokerConfigured && problems.isEmpty() && extra.isEmpty()"))
    }

    @Test fun healthAuditIsDispatchedThroughOneHelper() {
        // The HealthAudit.evaluate call lives once, in healthFindings; the three surfaces call the helper.
        assertEquals(1, Regex("HealthAudit\\.evaluate\\(").findAll(server).count())
        assertTrue(server.contains("): List<HealthAudit.Finding> = HealthAudit.evaluate("))
    }

    @Test fun thePerSurfaceUpdateInputsStayDistinct() {
        // Install tab: no updates in the top warnings (they live in the Managed-components card).
        assertTrue(server.contains("healthFindings(h, wv.display, emptyList())"))
        // Configure tab: only setup blockers, no update warnings above the settings form.
        assertTrue(server.contains("healthFindings(healthInputs(), \"\", emptyList())"))
        // GET /api/v1/status: the UNFILTERED list (Ignore only silences the dashboard banner).
        assertTrue(server.contains("healthFindings(h, h.webView.display, UpdateChecker.current(appContext))"))
        // Dashboard banner: the ignore-FILTERED list.
        assertTrue(
            server.contains(
                "healthFindings(h, s.facts[\"System WebView\"] ?: \"\", " +
                    "UpdateChecker.current(appContext, config.ignoredUpdates))",
            ),
        )
    }

    @Test fun configureTabSurfacesMissingRendererNextStepInPlace() {
        val configure = server.substring(
            server.indexOf("private fun configureBody(strings: AppStrings)"),
            server.indexOf("private fun profilesBody(strings: AppStrings)"),
        )
        assertTrue(configure.contains("configureSetupBanners(strings)"))
        assertTrue(configure.contains("private fun configureSetupBanners(strings: AppStrings)"))
        assertTrue(configure.contains("haSignInNeededForEffectiveDashboard()"))
        assertTrue(configure.contains("HealthAudit.Kind.NO_RENDERER"))
        assertTrue(configure.contains("configure.setup.renderer.title"))
        assertTrue(configure.contains("configure.setup.renderer.body"))
    }

    @Test fun theOnePageRenderThreadsTheSameSnapshotIntoEverySurface() {
        // The banner, facts card and diagnostics rows all take the shared HealthInputs rather than re-probing.
        assertTrue(server.contains("private fun bannersHtml(s: Snap, h: HealthInputs, strings: AppStrings): String"))
        assertTrue(server.contains("private fun factRowsHtml(s: Snap, keys: List<String>, h: HealthInputs, strings: AppStrings): String"))
        assertTrue(server.contains("private fun contextRowsHtml(s: Snap, h: HealthInputs, strings: AppStrings): String"))
        // Warm hydration (/api/v1/info) captures once, then threads it into each fragment.
        assertTrue(server.contains("bannersHtml(s, h, strings)"))
        assertTrue(server.contains("factRowsHtml(s, infoKeys(s), h, strings)"))
        assertTrue(server.contains("contextRowsHtml(s, h, strings)"))
    }
}
