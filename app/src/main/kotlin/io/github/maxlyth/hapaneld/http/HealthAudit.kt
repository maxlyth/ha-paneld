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
    enum class Kind { SCHEMA_ROLLED_BACK, WEBVIEW_OLD, NO_RENDERER, UPDATE }

    data class Finding(
        val kind: Kind,
        /** WEBVIEW_OLD: the WebView version string. SCHEMA_ROLLED_BACK: the schema-version detail. Empty otherwise. */
        val detail: String = "",
        /** Present only for [Kind.UPDATE] findings — the available component update. */
        val update: UpdateChecker.UpdateInfo? = null,
    )

    /**
     * @param webViewTooOld  engine-aware verdict (`PanelInfo.webViewStatus(...).tooOld`)
     * @param webViewDisplay the WebView version string to show in the WEBVIEW_OLD finding
     * @param hasRenderer    a supported dashboard app (built-in / Companion / configured package) is present
     * @param brokerConfigured whether the user has configured an MQTT broker; renderer setup follows it
     * @param updates        available component updates, already filtered + ordered by the caller
     * @param schemaRolledBack   config was reset by a version downgrade (last reconcile was PRESERVED_FRESH)
     * @param schemaRollbackDetail the schema-version detail to show (e.g. "schema 13 → 11")
     */
    fun evaluate(
        webViewTooOld: Boolean,
        webViewDisplay: String,
        hasRenderer: Boolean,
        brokerConfigured: Boolean,
        updates: List<UpdateChecker.UpdateInfo>,
        schemaRolledBack: Boolean = false,
        schemaRollbackDetail: String = "",
    ): List<Finding> = buildList {
        // A downgrade that reset config to defaults is the most severe finding — render it first.
        if (schemaRolledBack) add(Finding(Kind.SCHEMA_ROLLED_BACK, detail = schemaRollbackDetail))
        if (webViewTooOld) add(Finding(Kind.WEBVIEW_OLD, detail = webViewDisplay))
        if (brokerConfigured && !hasRenderer) add(Finding(Kind.NO_RENDERER))
        updates.forEach { add(Finding(Kind.UPDATE, update = it)) }
    }
}
