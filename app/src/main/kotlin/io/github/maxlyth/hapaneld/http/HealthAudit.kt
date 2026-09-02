package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.storage.StorageDatabaseFailureKind
import io.github.maxlyth.hapaneld.storage.StorageHealthSeverity
import io.github.maxlyth.hapaneld.storage.StorageHealthSnapshot
import io.github.maxlyth.hapaneld.util.UpdateChecker
import org.json.JSONObject

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

    /**
     * Public-safe projection of one storage observation. HTTP, HTML and copy-paste diagnostics all
     * render this same value so severity and remediation cannot drift between surfaces. Paths and raw
     * exception text deliberately never enter this type.
     */
    data class StoragePresentation(
        val state: String,
        val pressureState: String,
        val usableBytes: Long?,
        val totalBytes: Long?,
        val usedPercent: Double?,
        val databaseBytes: Long?,
        val walBytes: Long?,
        val sidecarBytes: Long?,
        val pageSizeBytes: Long?,
        val pageCount: Long?,
        val freelistCount: Long?,
        val schemaVersion: Int?,
        val checkedAtMillis: Long?,
        val quickCheck: String,
        val autoVacuum: String,
        val failure: String?,
        val failureOperation: String?,
        val summary: String,
        val action: String,
    ) {
        /** Stable flat JSON contract for `/api/v1/status`; state stays first for small shell clients. */
        fun statusJson(): String = buildString {
            fun field(name: String, value: Any?) {
                if (length > 1) append(',')
                append(JSONObject.quote(name)).append(':')
                when (value) {
                    null -> append("null")
                    is Number -> append(value)
                    else -> append(JSONObject.quote(value.toString()))
                }
            }
            append('{')
            field("state", state)
            field("pressure_state", pressureState)
            field("usable_bytes", usableBytes)
            field("total_bytes", totalBytes)
            field("used_percent", usedPercent)
            field("database_bytes", databaseBytes)
            field("wal_bytes", walBytes)
            field("sidecar_bytes", sidecarBytes)
            field("page_size_bytes", pageSizeBytes)
            field("page_count", pageCount)
            field("freelist_count", freelistCount)
            field("schema_version", schemaVersion)
            field("quick_check", quickCheck)
            field("auto_vacuum", autoVacuum)
            field("checked_at", checkedAtMillis)
            failure?.let { field("failure", it) }
            failureOperation?.let { field("failure_operation", it) }
            field("summary", summary)
            field("action", action)
            append('}')
        }

        /** Ready-to-render warning content shared by the status warning list and Dashboard banner. */
        fun warningHtml(): String? = when (state) {
            "warning" -> "⚠ <b>Storage pressure: warning</b> — ${html(summary)} ${html(action)}"
            "critical" -> "⛔ <b>Storage pressure: critical</b> — ${html(summary)} ${html(action)}"
            "database_failure" -> "⛔ <b>Database storage failure</b> — ${html(summary)} ${html(action)}"
            else -> null
        }

        fun bannerHtml(): String = warningHtml()?.let {
            val critical = state == "critical" || state == "database_failure"
            "<div class=\"setup${if (critical) " crit" else ""}\">$it</div>"
        }.orEmpty()

        /** One terminal-safe, path-free line for the support dump. */
        fun diagnosticLine(): String = buildString {
            fun metric(name: String, value: Any?) {
                append(' ').append(name).append('=').append(value ?: "unknown")
            }
            append("[storage-health] state=").append(state)
            metric("pressure_state", pressureState)
            metric("usable_bytes", usableBytes)
            metric("total_bytes", totalBytes)
            metric("used_percent", usedPercent)
            metric("database_bytes", databaseBytes)
            metric("wal_bytes", walBytes)
            metric("sidecar_bytes", sidecarBytes)
            metric("page_size_bytes", pageSizeBytes)
            metric("page_count", pageCount)
            metric("freelist_count", freelistCount)
            metric("schema_version", schemaVersion)
            metric("quick_check", quickCheck)
            metric("auto_vacuum", autoVacuum)
            metric("checked_at", checkedAtMillis)
            metric("failure", failure ?: "none")
            // The failing operation was already captured and sanitized to a closed vocabulary; until
            // it was rendered here, a reported `failure=unknown` named nothing an operator could act
            // on and nothing a maintainer could reproduce (Issue #91 residual).
            metric("failure_operation", failureOperation ?: "none")
        }
    }

    fun storage(snapshot: StorageHealthSnapshot): StoragePresentation {
        val probeRan = snapshot.checkedAtMillis > 0L
        val filesystemKnown = probeRan && snapshot.totalBytes > 0L
        fun measured(value: Long): Long? = value.takeIf { probeRan && it >= 0L }
        val usableBytes = snapshot.usableBytes.takeIf { filesystemKnown && it >= 0L }
        val totalBytes = snapshot.totalBytes.takeIf { filesystemKnown }
        val pageCount = snapshot.pageCount.takeIf { probeRan && it > 0L }
        val used = snapshot.usedPercent?.takeIf { filesystemKnown && it.isFinite() && it in 0.0..100.0 }
        val failure = snapshot.databaseFailureKind?.let(::storageFailureWireValue)
        val failureOperation = snapshot.databaseFailureOperationLabel
        // Named in prose as well as in the machine fields: the operator-facing summary is the only
        // one of these surfaces a reporter reads by default.
        val during = failureOperation?.let { " during $it" }.orEmpty()
        val free = usableBytes?.let(::formatBytes) ?: "free space unknown"
        val percent = used?.let { "%.1f%% used".format(java.util.Locale.ROOT, it) }
        val headroom = listOfNotNull(free, percent).joinToString(", ")
        val state = snapshot.severity.name.lowercase(java.util.Locale.ROOT)
        val summary = when (snapshot.severity) {
            StorageHealthSeverity.UNCHECKED -> if (probeRan) {
                "Filesystem capacity could not be measured; retained SQLite metrics are diagnostic only."
            } else {
                "Storage health has not been checked yet."
            }
            StorageHealthSeverity.HEALTHY -> "Storage headroom is healthy ($headroom)."
            StorageHealthSeverity.WARNING -> "Storage or database-file pressure is elevated ($headroom)."
            StorageHealthSeverity.CRITICAL -> "Storage or database-file pressure is critical ($headroom)."
            StorageHealthSeverity.DATABASE_FAILURE -> when (snapshot.databaseFailureKind) {
                StorageDatabaseFailureKind.STORAGE_FULL ->
                    "A database write$during failed when storage was full; recovery is not yet verified. Last measured storage metrics: $headroom."
                StorageDatabaseFailureKind.IO ->
                    "A database operation$during reported disk I/O failure; recovery is not yet verified. Last measured storage metrics: $headroom."
                StorageDatabaseFailureKind.CORRUPTION ->
                    "SQLite reported corruption or failed its quick check$during. Retained storage metrics are diagnostic only: $headroom."
                StorageDatabaseFailureKind.BUSY ->
                    "A database operation$during remained busy or locked; recovery is not yet verified. Last measured storage metrics: $headroom."
                StorageDatabaseFailureKind.UNKNOWN,
                null ->
                    "A database operation$during failed${failure?.let { " ($it)" }.orEmpty()}; recovery is not yet verified. Last measured storage metrics: $headroom."
            }
        }
        val action = when (snapshot.severity) {
            StorageHealthSeverity.UNCHECKED -> if (probeRan) {
                "Retry the storage check and inspect diagnostics if filesystem capacity remains unavailable."
            } else {
                "Wait for the startup storage check to complete."
            }
            StorageHealthSeverity.HEALTHY -> "No action needed."
            StorageHealthSeverity.WARNING ->
                "Review free space and WAL/database-file growth before the next update or settings change."
            StorageHealthSeverity.CRITICAL ->
                "Recover storage headroom or address WAL growth now, and avoid updates or settings changes until health recovers."
            StorageHealthSeverity.DATABASE_FAILURE -> when (snapshot.databaseFailureKind) {
                StorageDatabaseFailureKind.STORAGE_FULL ->
                    "Preserve the database, recover storage headroom, wait for a clean health check, then retry; do not delete or recreate the database."
                StorageDatabaseFailureKind.BUSY ->
                    "Preserve the database, wait for a clean health check, then retry; do not delete or recreate the database."
                StorageDatabaseFailureKind.IO ->
                    "Preserve the database and inspect storage diagnostics; retry only after the I/O cause is resolved."
                StorageDatabaseFailureKind.CORRUPTION ->
                    "Preserve the database, avoid further writes, and inspect diagnostics and recovery options; do not delete or recreate the database."
                StorageDatabaseFailureKind.UNKNOWN,
                null ->
                    "Preserve the database and inspect diagnostics before retrying; do not delete or recreate the database."
            }
        }
        return StoragePresentation(
            state = state,
            pressureState = snapshot.pressureSeverity.name.lowercase(java.util.Locale.ROOT),
            usableBytes = usableBytes,
            totalBytes = totalBytes,
            usedPercent = used,
            databaseBytes = measured(snapshot.mainDatabaseBytes),
            walBytes = measured(snapshot.walBytes),
            sidecarBytes = measured(snapshot.sidecarBytes),
            pageSizeBytes = snapshot.pageSizeBytes.takeIf { probeRan && it > 0L },
            pageCount = pageCount,
            freelistCount = snapshot.freelistCount.takeIf { pageCount != null && it >= 0L },
            schemaVersion = snapshot.schemaVersion.takeIf { probeRan && it > 0 },
            checkedAtMillis = snapshot.checkedAtMillis.takeIf { probeRan },
            quickCheck = if (probeRan) snapshot.quickCheck.name.lowercase(java.util.Locale.ROOT) else "not_run",
            autoVacuum = snapshot.autoVacuumMode.name.lowercase(java.util.Locale.ROOT),
            failure = failure,
            failureOperation = failureOperation,
            summary = summary,
            action = action,
        )
    }

    private fun storageFailureWireValue(kind: StorageDatabaseFailureKind): String = when (kind) {
        StorageDatabaseFailureKind.STORAGE_FULL -> "storage_full"
        StorageDatabaseFailureKind.IO -> "io"
        StorageDatabaseFailureKind.CORRUPTION -> "corruption"
        StorageDatabaseFailureKind.BUSY -> "busy"
        StorageDatabaseFailureKind.UNKNOWN -> "unknown"
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (unit == 0) "$bytes ${units[unit]}" else "%.1f %s".format(java.util.Locale.ROOT, value, units[unit])
    }

    private fun html(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
