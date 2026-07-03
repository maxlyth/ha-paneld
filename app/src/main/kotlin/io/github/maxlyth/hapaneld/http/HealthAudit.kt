package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.util.UpdateChecker

/**
 * Pure health-audit decision: which "this panel won't render the dashboard as expected" findings apply,
 * given already-probed inputs. Centralises the logic that was copy-pasted across the dashboard banner
 * ([PaneldServer.bannersHtml]), the Install tab ([PaneldServer.installBody]), and GET /api/v1/status
 * ([PaneldServer.statusJson]) — so the WebView threshold + renderer detection can't drift between them.
 *
 * The three call sites render these findings differently (the dashboard banner adds an "Ignore this
 * version" button; the Install tab adds an "Update WebView now" heal button; the status endpoint emits
 * JSON), but they all agree on the SET of findings this returns. Pure — unit-tested in HealthAuditTest.
 */
object HealthAudit {
    enum class Kind { WEBVIEW_OLD, NO_RENDERER, UPDATE }

    data class Finding(
        val kind: Kind,
        /** WEBVIEW_OLD: the WebView version string to show. Empty otherwise. */
        val detail: String = "",
        /** Present only for [Kind.UPDATE] findings — the available component update. */
        val update: UpdateChecker.UpdateInfo? = null,
    )

    /**
     * @param webViewTooOld  engine-aware verdict (`PanelInfo.webViewStatus(...).tooOld`)
     * @param webViewDisplay the WebView version string to show in the WEBVIEW_OLD finding
     * @param hasRenderer    a dashboard app (Companion / Fully Kiosk / configured package) is present
     * @param updates        available component updates, already filtered + ordered by the caller
     */
    fun evaluate(
        webViewTooOld: Boolean,
        webViewDisplay: String,
        hasRenderer: Boolean,
        updates: List<UpdateChecker.UpdateInfo>,
    ): List<Finding> = buildList {
        if (webViewTooOld) add(Finding(Kind.WEBVIEW_OLD, detail = webViewDisplay))
        if (!hasRenderer) add(Finding(Kind.NO_RENDERER))
        updates.forEach { add(Finding(Kind.UPDATE, update = it)) }
    }
}
