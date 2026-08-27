package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.control.BuiltinDashboard
import io.github.maxlyth.hapaneld.util.HaTransportEvidence
import io.github.maxlyth.hapaneld.util.HaTransportFault
import org.json.JSONObject

/**
 * Which renderer the panel is configured to run, so a consumer never has to infer applicability from
 * an absent field. A deployment check that treats "no renderer telemetry" as "nothing to worry
 * about" is how a panel showing an error screen passes for healthy, so the mode is always stated.
 */
internal enum class RendererMode(val wire: String) {
    /** ha-paneld's own WebView renderer — the only one whose admission this panel observes. */
    BUILTIN("builtin"),

    /** A foreign dashboard app (the Home Assistant Companion, or a configured package). Its
     *  connection to Home Assistant is its own business and ha-paneld cannot see it. */
    EXTERNAL("external"),

    /** No dashboard renderer is configured at all. */
    NONE("none"),
}

/** Where renderer admission currently stands. Ordered from healthiest to least informative. */
internal enum class RendererAdmissionState(val wire: String) {
    /** Admitted AND the Home Assistant frontend has connected — the dashboard is genuinely up. */
    RENDERED("rendered"),

    /** Admission passed, but the frontend has not connected yet. A panel can sit here indefinitely
     *  when the version check succeeded on a cached version and the page load then hits the same
     *  wall — which is precisely the state a green soak must not accept as rendered. */
    ADMITTED("admitted"),

    /** An admission check is in flight. */
    CHECKING("checking"),

    /** Admission is blocked; [RendererAdmissionPresentation.outcome] says what the panel learned. */
    BLOCKED("blocked"),

    /** Nothing has been observed for the renderer generation that is live now — a fresh process, a
     *  replaced activity, or a renderer that is not running. Never a statement of health. */
    UNOBSERVED("unobserved"),
}

/**
 * Process-local record of what renderer admission last learned, written by
 * [io.github.maxlyth.hapaneld.DashboardActivity] and read by the `:8888` surfaces.
 *
 * Shaped like the other process-global runtime holders ([BuiltinDashboard],
 * [io.github.maxlyth.hapaneld.sensors.HaLifecycleRuntime]): the renderer writes, everything else
 * reads, and an unwritten holder answers with the honest "nothing observed" rather than a default
 * that reads as health.
 *
 * **Ownership reuses the existing activity lease rather than inventing a second one.** Activity
 * lifetimes overlap while Android replaces a task, and every admission callback already checks
 * [BuiltinDashboard.ownsActivity]; a private generation counter here would be a second notion of the
 * same fact, free to disagree with it. A record is therefore renderable only while the generation
 * that wrote it still owns the activity — which also means a replaced or destroyed renderer's
 * verdict retires by itself, with no teardown hook to forget and no stale verdict to mistake for
 * the live one.
 *
 * **Never persisted.** After a restart there is no record, so a panel that has not yet decided
 * anything says so instead of inheriting a verdict from before the reboot.
 */
internal object RendererAdmissionRuntime {

    /**
     * One admission verdict as an indivisible tuple. Piecewise reads are deliberately not offered:
     * two reads can straddle a transition and render a combination that never existed — an outcome
     * from one attempt beside the fault of another.
     */
    internal data class Record(
        val state: RendererAdmissionState,
        /** What the panel learned when admission was blocked; null when it was not. */
        val outcome: AdmissionOutcome?,
        /** True when admission passed on a PREVIOUSLY verified version because the live check could
         *  not complete. The renderer starts, but nothing about the server was confirmed this time. */
        val admittedOnCachedVersion: Boolean,
        val evidence: HaTransportEvidence,
        val observedAtElapsedMs: Long,
    )

    /**
     * Everything one renderer generation has told us, held as ONE value so a reader cannot pair an
     * admission verdict with a connection flag from a different generation or a different moment.
     * The pair is the whole point: "admitted" and "the frontend is connected" are separately true,
     * and a panel can be the first without being the second.
     */
    internal data class Live(
        /** The [BuiltinDashboard.acquireActivityOwner] lease of the renderer this describes. */
        val owner: Long,
        val record: Record?,
        /** Whether the Home Assistant frontend is connected RIGHT NOW — not whether it ever was.
         *  The renderer writes both halves, so this is the owner reporting its own state rather than
         *  a copy of somebody else's that could drift from it. */
        val frontendConnected: Boolean,
    )

    private val lock = Any()

    @Volatile private var live: Live? = null

    /**
     * Record [state] for the renderer generation holding [owner]. Ignored — not queued, not
     * remembered — when that generation no longer owns the activity, so a predecessor finishing a
     * probe after its replacement took over cannot describe the live renderer.
     *
     * Returns whether anything was stored, which is what the tests assert on: a write that was
     * refused and a write that was overwritten look identical from the read side.
     */
    fun record(
        owner: Long,
        state: RendererAdmissionState,
        nowElapsedMs: Long,
        outcome: AdmissionOutcome? = null,
        admittedOnCachedVersion: Boolean = false,
        evidence: HaTransportEvidence = HaTransportEvidence.NONE,
    ): Boolean {
        // Ownership is asked OUTSIDE the lock: `BuiltinDashboard` takes its own monitor, and nesting
        // two global locks in opposite orders from different call paths is how a deadlock cycle
        // closes. The read side re-checks ownership, so a generation that loses the activity between
        // this test and the store cannot have its verdict rendered anyway.
        if (!BuiltinDashboard.ownsActivity(owner)) return false
        val next = Record(state, outcome, admittedOnCachedVersion, evidence, nowElapsedMs)
        synchronized(lock) {
            val previous = live?.takeIf { it.owner == owner }
            live = Live(owner, next, previous?.frontendConnected ?: false)
        }
        return true
    }

    /**
     * Publish whether the Home Assistant frontend is connected. Called from the renderer's own
     * `frontendConnected` setter, so every assignment reaches here and none can be forgotten — the
     * list of sites is DERIVED from the property rather than hand-maintained beside it.
     */
    fun setFrontendConnected(owner: Long, connected: Boolean): Boolean {
        if (!BuiltinDashboard.ownsActivity(owner)) return false
        synchronized(lock) {
            val previous = live?.takeIf { it.owner == owner }
            live = Live(owner, previous?.record, connected)
        }
        return true
    }

    /**
     * The current renderer generation's state, or null when there is none to report.
     *
     * The ownership test is the authority and is applied on READ, not only on write: a generation
     * that lost the activity between a write's check and its store leaves a value behind, and this
     * is where that value stops being renderable. It is also why no teardown hook is needed —
     * [BuiltinDashboard.releaseActivityOwner] retires the whole tuple as a side effect of retiring
     * the renderer it belonged to, and [BuiltinDashboard.acquireActivityOwner] retires it as a side
     * effect of a replacement taking over.
     */
    fun current(): Live? = live?.takeIf { BuiltinDashboard.ownsActivity(it.owner) }

    /** Test seam: forget everything, as a fresh process would. */
    internal fun reset() {
        synchronized(lock) { live = null }
    }
}

/**
 * The one public-safe projection of renderer/Home Assistant admission health, rendered identically by
 * the info card, `GET /api/v1/status` and the `/api/v1/diag` dump — so severity cannot drift between
 * a page a maintainer reads and a report a user pastes.
 *
 * Modelled on [io.github.maxlyth.hapaneld.http.HealthAudit.StoragePresentation], including its
 * boundary: **paths, URLs, hosts, credentials and raw exception text never enter this type.** The
 * transport failure arrives already classified as [HaTransportFault]; [faultDetail] is a sanitized
 * class name, and [transport] is the URL SCHEME alone.
 */
internal data class RendererAdmissionPresentation(
    val mode: RendererMode,
    val state: RendererAdmissionState,
    /** Wire form of the blocking [AdmissionOutcome], or `ok` / `ok_cached` when admission passed. */
    val outcome: String,
    val fault: HaTransportFault,
    val faultDetail: String?,
    /** How a blocked verdict may recover on its own, or `none` when nothing is blocked. */
    val recovery: String,
    /** `https`, `http`, or `unknown` — the scheme of the configured Home Assistant URL, never the URL. */
    val transport: String,
    /** The configured address-family policy that HA connections follow. Not an observed family: no
     *  client on this panel reports which address it actually reached, and inventing one would be a
     *  claim rather than an observation. */
    val addressFamilyPolicy: String,
    /**
     * How long ago the ADMISSION verdict was observed, or null when nothing has been observed.
     *
     * Admission does not re-run once it passes — an admitted renderer short-circuits the probe — so on
     * a healthy panel this is the age of the renderer generation, not a measure of how recently the
     * dashboard was seen working. "Is it up now?" is [RendererAdmissionState.RENDERED], which the
     * frontend maintains live. Read this instead as "when was this verdict formed", and compare it
     * with [packageUpdatedAgeMs] to learn whether the verdict postdates an upgrade.
     */
    val observedAgeMs: Long?,
    /**
     * How long ago the process holding this observation started.
     *
     * The runtime record is process-local and never persisted, so no observation can be older than
     * this — an anchor a consumer can check rather than a property it has to take on trust.
     */
    val processAgeMs: Long,
    /**
     * How long ago this app package was last installed or replaced, or null when the package manager
     * would not say.
     *
     * A package replacement kills the process, so evidence gathered after an upgrade is necessarily
     * YOUNGER than this value. That comparison is per panel, which is the point: a wave installs its
     * panels minutes or hours apart, and one shared window judged against the last install will call
     * an earlier panel stale when its telemetry was truthful all along.
     *
     * Wall-clock, unlike every other age here, because that is the only clock the package manager
     * records against. A panel whose clock is stepped between the install and the read distorts it,
     * so a consumer comparing the two allows a margin.
     */
    val packageUpdatedAgeMs: Long?,
    val summary: String,
    val action: String,
) {

    /** Stable flat JSON for `GET /api/v1/status`; `mode` and `state` stay first for shell clients. */
    fun statusJson(): String = buildString {
        fun field(name: String, value: Any?) {
            if (length > 1) append(',')
            append(JSONObject.quote(name)).append(':')
            when (value) {
                null -> append("null")
                is Number -> append(value)
                is Boolean -> append(value)
                else -> append(JSONObject.quote(value.toString()))
            }
        }
        append('{')
        field("mode", mode.wire)
        field("state", state.wire)
        field("outcome", outcome)
        field("fault", fault.wire)
        field("fault_detail", faultDetail)
        field("recovery", recovery)
        field("transport", transport)
        field("address_family_policy", addressFamilyPolicy)
        field("observed_age_ms", observedAgeMs)
        field("process_age_ms", processAgeMs)
        field("package_updated_age_ms", packageUpdatedAgeMs)
        field("rendered", state == RendererAdmissionState.RENDERED)
        field("summary", summary)
        field("action", action)
        append('}')
    }

    /** One terminal-safe, host-free line for the copy-paste support dump. */
    fun diagnosticLine(): String = buildString {
        append("[renderer] mode=").append(mode.wire)
        append(" state=").append(state.wire)
        append(" outcome=").append(outcome)
        append(" fault=").append(fault.wire)
        append(" detail=").append(faultDetail ?: "none")
        append(" recovery=").append(recovery)
        append(" transport=").append(transport)
        append(" address_family=").append(addressFamilyPolicy)
        append(" observed_age=").append(observedAgeMs?.let { fmtAge(it) } ?: "never")
        append(" process_age=").append(fmtAge(processAgeMs))
        append(" package_updated=").append(packageUpdatedAgeMs?.let { fmtAge(it) } ?: "unknown")
    }

    /** The Runtime diagnostics card row, or null when there is nothing worth a permanent row. */
    fun statusText(): String? = when (mode) {
        RendererMode.NONE -> null
        // A foreign renderer's connection to Home Assistant belongs to that app. Saying "not
        // observed" is the honest row; claiming health we cannot see is the defect being fixed.
        RendererMode.EXTERNAL -> "external renderer · Home Assistant connection not observed by ha-paneld"
        // The age is the ADMISSION's, and it is labelled as such. It used to read "seen 22h2m ago",
        // which on a panel that had been rendering continuously for those 22 hours told a maintainer
        // the dashboard had last been sighted the previous morning. Admission simply does not re-run
        // once it passes; the live fact is the state, which leads the row already.
        RendererMode.BUILTIN -> buildString {
            append("built-in · ").append(summary)
            observedAgeMs?.let { append(" · admitted ").append(fmtAge(it)).append(" ago") }
        }
    }

    companion object {
        /** Wire outcome when the live version check succeeded. */
        const val OUTCOME_OK = "ok"

        /** Wire outcome when the renderer was admitted on a previously verified version because the
         *  live check could not complete — admitted, but nothing confirmed this time. */
        const val OUTCOME_OK_CACHED = "ok_cached"

        /** Wire outcome when no admission verdict exists for the live renderer generation. */
        const val OUTCOME_UNOBSERVED = "unobserved"

        private const val RECOVERY_NONE = "none"

        /**
         * Build the projection from the configured renderer plus whatever the live generation has
         * recorded. Pure: every input is supplied, so the whole matrix is unit-testable without an
         * Activity, a WebView or a clock.
         *
         * @param live the live generation's tuple, or null when nothing is reportable.
         * @param processStartElapsedMs when this process started, on the same `elapsedRealtime` clock
         *   as [nowElapsedMs], so the subtraction is exact rather than approximately right.
         * @param packageUpdatedAtMs the package manager's `lastUpdateTime`, or null/0 when unknown.
         *   Wall-clock, hence [nowWallMs] beside it; nothing else here uses that clock.
         */
        fun of(
            mode: RendererMode,
            haUrl: String,
            addressFamilyPolicy: String,
            live: RendererAdmissionRuntime.Live?,
            nowElapsedMs: Long,
            processStartElapsedMs: Long,
            packageUpdatedAtMs: Long?,
            nowWallMs: Long,
        ): RendererAdmissionPresentation {
            val record = live?.record
            val connected = live?.frontendConnected == true
            val transport = transportOf(haUrl)
            val family = addressFamilyPolicy.trim().lowercase().replace(' ', '_').ifBlank { "automatic" }
            // Both anchors describe the app, not the renderer, so they are reported for every mode:
            // "no renderer telemetry" and "no anchor to judge it against" are different problems and a
            // consumer must not have to tell them apart from an absent field.
            val processAgeMs = (nowElapsedMs - processStartElapsedMs).coerceAtLeast(0L)
            // A non-positive lastUpdateTime is the package manager declining to answer, not an install
            // at the epoch. Reported as null so it cannot be mistaken for a very old install, which
            // would let any observation look post-upgrade.
            val packageUpdatedAgeMs = packageUpdatedAtMs
                ?.takeIf { it > 0L }
                ?.let { (nowWallMs - it).coerceAtLeast(0L) }
            if (mode != RendererMode.BUILTIN) {
                return RendererAdmissionPresentation(
                    mode = mode,
                    state = RendererAdmissionState.UNOBSERVED,
                    outcome = OUTCOME_UNOBSERVED,
                    fault = HaTransportFault.NONE,
                    faultDetail = null,
                    recovery = RECOVERY_NONE,
                    transport = transport,
                    addressFamilyPolicy = family,
                    observedAgeMs = null,
                    processAgeMs = processAgeMs,
                    packageUpdatedAgeMs = packageUpdatedAgeMs,
                    summary = if (mode == RendererMode.EXTERNAL) {
                        "an external renderer is configured; ha-paneld does not observe its Home Assistant connection"
                    } else {
                        "no dashboard renderer is configured"
                    },
                    action = if (mode == RendererMode.EXTERNAL) {
                        "Check the dashboard on the panel itself; ha-paneld cannot verify a foreign renderer."
                    } else {
                        "Choose a dashboard renderer in Configure."
                    },
                )
            }
            // A live frontend connection outranks the admission verdict, and only upgrades an
            // ADMITTED one: a panel admitted on a cached version whose page then dies at the same
            // wall is `admitted`, never `rendered`. That distinction is the whole reason this exists:
            // admitted-and-blank is indistinguishable from healthy to every other check the panel
            // has. It is deliberately "connected now", not "connected at some point this
            // generation": a blocked screen tears the page down without ending the generation, so
            // an ever-connected latch would keep calling a replaced dashboard rendered.
            val state = when {
                record == null -> if (connected) RendererAdmissionState.RENDERED else RendererAdmissionState.UNOBSERVED
                connected && record.state.isAdmitted() -> RendererAdmissionState.RENDERED
                else -> record.state
            }
            val outcome = when {
                record == null -> OUTCOME_UNOBSERVED
                record.outcome != null -> record.outcome.name.lowercase()
                record.admittedOnCachedVersion -> OUTCOME_OK_CACHED
                record.state == RendererAdmissionState.CHECKING -> OUTCOME_UNOBSERVED
                else -> OUTCOME_OK
            }
            val recovery = record?.outcome?.let { admissionRetryClass(it).name.lowercase() } ?: RECOVERY_NONE
            val evidence = record?.evidence ?: HaTransportEvidence.NONE
            return RendererAdmissionPresentation(
                mode = mode,
                state = state,
                outcome = outcome,
                fault = evidence.fault,
                faultDetail = HaTransportFault.sanitize(evidence.token),
                recovery = recovery,
                transport = transport,
                addressFamilyPolicy = family,
                observedAgeMs = record?.let { (nowElapsedMs - it.observedAtElapsedMs).coerceAtLeast(0L) },
                processAgeMs = processAgeMs,
                packageUpdatedAgeMs = packageUpdatedAgeMs,
                summary = summaryOf(state, record, evidence.fault),
                action = actionOf(state, record?.outcome),
            )
        }

        /** Only the scheme leaves the panel — a full URL would carry the host into a pasted report. */
        private fun transportOf(haUrl: String): String =
            when (haUrl.trim().substringBefore("://", "").lowercase()) {
                "https" -> "https"
                "http" -> "http"
                else -> "unknown"
            }

        private fun RendererAdmissionState.isAdmitted(): Boolean =
            this == RendererAdmissionState.ADMITTED || this == RendererAdmissionState.RENDERED

        private fun summaryOf(
            state: RendererAdmissionState,
            record: RendererAdmissionRuntime.Record?,
            fault: HaTransportFault,
        ): String = when (state) {
            RendererAdmissionState.RENDERED ->
                if (record?.admittedOnCachedVersion == true) {
                    "dashboard rendered; admitted on a previously verified Home Assistant version"
                } else {
                    "dashboard rendered"
                }
            RendererAdmissionState.ADMITTED ->
                if (record?.admittedOnCachedVersion == true) {
                    "admitted on a previously verified Home Assistant version; the dashboard has not connected"
                } else {
                    "admitted; the dashboard has not connected yet"
                }
            RendererAdmissionState.CHECKING -> "checking Home Assistant compatibility"
            RendererAdmissionState.BLOCKED -> blockedSummary(record?.outcome, fault)
            RendererAdmissionState.UNOBSERVED -> "no admission observed for the current renderer"
        }

        private fun blockedSummary(outcome: AdmissionOutcome?, fault: HaTransportFault): String {
            val cause = when (outcome) {
                AdmissionOutcome.TRANSPORT_FAILED -> "Home Assistant could not be reached"
                AdmissionOutcome.DASHBOARD_LIST_UNREADABLE -> "the dashboard list could not be read"
                AdmissionOutcome.SIGN_IN_PAGE_UNREACHABLE -> "the panel sign-in page did not load"
                AdmissionOutcome.BRIDGE_HANDSHAKE_MISSED -> "the secure dashboard bridge handshake did not arrive"
                AdmissionOutcome.VERSION_UNVERIFIABLE -> "Home Assistant reported no recognized version"
                AdmissionOutcome.CREDENTIAL_REFUSED -> "Home Assistant refused the panel's saved sign-in"
                AdmissionOutcome.SIGN_IN_REQUIRED -> "the panel is not connected to Home Assistant"
                AdmissionOutcome.NO_LEGAL_DASHBOARD -> "this account can reach no dashboard"
                AdmissionOutcome.UNSUPPORTED_HA -> "Home Assistant is older than the supported floor"
                AdmissionOutcome.BRIDGE_UNAVAILABLE -> "this Android System WebView cannot provide the secure bridge"
                AdmissionOutcome.BRIDGE_ATTACH_FAILED -> "the secure dashboard bridge could not be attached"
                null -> "renderer admission is blocked"
            }
            // The classified fault is appended only when there IS one: a credential refusal with a
            // trailing "(none)" reads as a second, missing piece of evidence.
            return if (fault == HaTransportFault.NONE || fault == HaTransportFault.UNKNOWN) {
                "blocked — $cause"
            } else {
                "blocked — $cause (${fault.wire})"
            }
        }

        private fun actionOf(state: RendererAdmissionState, outcome: AdmissionOutcome?): String = when {
            state != RendererAdmissionState.BLOCKED -> ""
            outcome == null -> "Open the panel to see what the renderer is reporting."
            admissionRetryClass(outcome) == AdmissionRetryClass.MANUAL_ONLY ->
                "This will not clear on its own — repair the Home Assistant connection, server version or WebView."
            else -> "The panel retries on its own; repair the Home Assistant side and it recovers unattended."
        }

        /** Compact age: `3d2h`, `5h12m`, `47m`, `23s`. Matches the dump's uptime formatting. */
        internal fun fmtAge(ms: Long): String {
            val s = ms / 1000
            val d = s / 86400
            val h = (s % 86400) / 3600
            val m = (s % 3600) / 60
            return when {
                d > 0 -> "${d}d${h}h"
                h > 0 -> "${h}h${m}m"
                m > 0 -> "${m}m"
                else -> "${s}s"
            }
        }
    }
}
