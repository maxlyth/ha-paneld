package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.util.UpdateChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthAuditTest {
    private val update = UpdateChecker.UpdateInfo("HA Companion", "2026.5.4", "2026.6.5", "https://x")

    @Test fun healthyPanelHasNoFindings() {
        val f = HealthAudit.evaluate(webViewTooOld = false, webViewDisplay = "138.0", hasRenderer = true, updates = emptyList())
        assertTrue(f.isEmpty())
    }

    @Test fun oldWebViewCarriesTheVersionToShow() {
        val f = HealthAudit.evaluate(webViewTooOld = true, webViewDisplay = "com.android.webview 107.0.5304.105", hasRenderer = true, updates = emptyList())
        assertEquals(1, f.size)
        assertEquals(HealthAudit.Kind.WEBVIEW_OLD, f[0].kind)
        assertEquals("com.android.webview 107.0.5304.105", f[0].detail)
    }

    @Test fun missingRendererIsReported() {
        val f = HealthAudit.evaluate(webViewTooOld = false, webViewDisplay = "", hasRenderer = false, updates = emptyList())
        assertEquals(listOf(HealthAudit.Kind.NO_RENDERER), f.map { it.kind })
    }

    @Test fun eachUpdateBecomesOneFindingCarryingItsInfo() {
        val f = HealthAudit.evaluate(webViewTooOld = false, webViewDisplay = "", hasRenderer = true, updates = listOf(update))
        assertEquals(1, f.size)
        assertEquals(HealthAudit.Kind.UPDATE, f[0].kind)
        assertEquals(update, f[0].update)
    }

    @Test fun findingsAreOrderedWebViewThenRendererThenUpdates() {
        val f = HealthAudit.evaluate(webViewTooOld = true, webViewDisplay = "107", hasRenderer = false, updates = listOf(update))
        assertEquals(
            listOf(HealthAudit.Kind.WEBVIEW_OLD, HealthAudit.Kind.NO_RENDERER, HealthAudit.Kind.UPDATE),
            f.map { it.kind },
        )
    }
}
