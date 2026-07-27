package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSleepUiContractTest {
    @Test fun `configure UI shows bounded status and current policy replay only while enabled`() {
        val source = asset("configure.js").readText()
        val fieldLoop = source.substringAfter("fields.forEach(function (f) {")
            .substringBefore("if (g === \"Display\")")
        val rowIndex = fieldLoop.indexOf("card.appendChild(row(f));")
        val statusIndex = fieldLoop.indexOf("f.key === \"auto_sleep\"")
        val nextFieldDecorationIndex = fieldLoop.indexOf("g === \"Home Assistant connection\"")

        assertTrue("status must include policy phase and reason", "Phase:" in source && "Reason:" in source)
        assertTrue("status must include learned delay and source count", "Learned delay:" in source && "Sources:" in source)
        assertTrue("manual screen authority must be visible", "Manual screen override:" in source)
        assertTrue(
            "the HA history visualization must be discoverable from the activity card",
            "When exposed, open the Auto-sleep activity binary sensor in Home Assistant for its history timeline." in source,
        )
        assertTrue("status belongs only to the enabled form", "values.auto_sleep === \"true\"" in fieldLoop)
        assertTrue("status refresh must update its own node", "updateAutoSleepSummary();" in source)
        assertTrue(
            "status must immediately follow the auto sleep control rather than the Behaviour group",
            rowIndex >= 0 && statusIndex > rowIndex && nextFieldDecorationIndex > statusIndex,
        )
        assertFalse(
            "status must not be appended after every Behaviour setting",
            "if (g === \"Behaviour\") {\n        if (values.auto_sleep === \"true\")" in source,
        )
        assertFalse("manual source picker must not ship", "ha_binary_sensors" in source)
        assertFalse("source catalog endpoint must not ship", "/api/v1/auto-sleep/sources" in source)
        assertFalse("auto sleep must not run a polling timer", "AUTO_SLEEP_REFRESH_MS" in source || "scheduleAutoSleepRefresh" in source)
        assertTrue("selected history period must be requested", "/api/v1/auto-sleep/history?hours=" in source && "autoSleepHistoryHours" in source)
        assertTrue("timeline must default to 24 hours", "var autoSleepHistoryHours = 24;" in source)
        assertTrue("timeline must be rendered on panel", "auto-sleep-chart" in source && "drawAutoSleepChart" in source)
        assertTrue(
            "timeline must synchronously restore its cached snapshot when the form rerenders",
            "drawAutoSleepChart(chart, true);" in source && "autoSleepHistory = null" in source &&
                "if (clearSnapshot) autoSleepHistory = null" in source,
        )
        assertTrue("timeline must expose all categorical outputs", listOf("Hold awake", "Allow sleep", "Inhibited").all { it in source })
        assertTrue(
            "timeline legend must use compact labels that fit narrow panels",
            listOf("Detected / Awake", "Clear / Sleep", "Unavailable").all { it in source } &&
                "Unavailable / inhibited" !in source,
        )
        assertTrue("timeline must show every source below the calculated result", "Calculated auto-sleep" in source && "autoSleepSourceLanes" in source)
        assertTrue("timeline must offer useful zoom periods", "[6, 24, 48].forEach" in source && "aria-pressed" in source)
        assertTrue("each lane must expose interval state details", "no intervals" in source && "interval" in source && "role: \"img\"" in source)
        assertTrue("timeline must provide an accessible description", "auto-sleep-chart-description" in source && "aria-describedby" in source)
        val statusLoad = source.substringAfter("function loadAutoSleepData() {")
            .substringBefore("var autoSleepResizeTimer")
        val historyLoad = source.substringAfter("function loadAutoSleepHistory() {")
            .substringBefore("function invalidateAutoSleepHistory")
        assertTrue(
            "history must wait for typed status readiness",
            statusLoad.indexOf("fetch(\"/api/v1/auto-sleep\"") in 0 until
                statusLoad.indexOf("if (autoSleepHistoryReady(autoSleepStatus))"),
        )
        assertTrue(
            "status and history must belong to the currently assigned Area before replacing the chart",
            "function autoSleepAreaMatches(value)" in source &&
                "status.enabled === true && autoSleepAreaMatches(status)" in source &&
                "if (!autoSleepAreaMatches(body))" in historyLoad,
        )
        assertTrue(
            "ready status must automatically load history",
            "if (autoSleepHistoryReady(autoSleepStatus))" in statusLoad &&
                "loadAutoSleepHistory();" in statusLoad,
        )
        assertTrue(
            "every transitional discovery phase must remain automatically observed",
            listOf("authenticating", "discovering", "learning", "connecting", "synchronizing", "reconnecting")
                .all { "\"$it\"" in source } && "scheduleAutoSleepReadiness(" in source,
        )
        assertTrue(
            "temporary source and transport races must recover automatically",
            listOf("runtime_unavailable", "sources_changed", "history_transport", "history_unavailable")
                .all { "\"$it\"" in source } && "retryAutomatically" in source,
        )
        assertFalse(
            "status and history refreshes must not release retained card geometry while retrying",
            "configCardSizeGeometryInvalid" in statusLoad || "configCardSizeGeometryInvalid" in historyLoad,
        )
        assertFalse("fixed startup retry exhaustion must not return", "autoSleepHistoryReadyAttempts" in source)
        assertFalse("history must not require a retry button", "auto-sleep-retry" in source)
        assertTrue(
            "persistent history failure recovery must be slow and stable client errors terminal",
            "Math.min(60 * 1000, autoSleepHistoryRetryDelayMs * 2)" in source &&
                "error.status >= 400 && error.status < 500" in source,
        )
        assertTrue(
            "stable discovery failures must have truthful terminal guidance",
            listOf("no_area", "no_credible_sources", "auth_failed", "discovery_failed", "status_failed")
                .all { "phase === \"$it\"" in source },
        )
        assertTrue(
            "typed history parse and limit failures must not be mislabeled as connection failures",
            "detail === \"history_parse\"" in source && "activity timestamps this panel could not read" in source &&
                "detail === \"history_limit\"" in source && "activity history is too large" in source,
        )
        assertFalse("opaque generic replay error must not return", "Could not replay activity history." in source)
        assertTrue("stale history responses must be fenced", "request !== autoSleepHistoryRequest" in source)
        assertTrue(
            "committed source changes must invalidate in-flight history",
            "invalidateAutoSleepData();" in source && "autoSleepHistoryRequest++;" in source &&
                "autoSleepHistoryLoading = false;" in source &&
                listOf("auto_sleep", "ha_url", "ha_token").all { "\"$it\"" in source },
        )
        val oauthSuccess = source.substringAfter("if (status === \"success\") {").substringBefore("} else {")
        assertTrue(
            "OAuth success must invalidate and reload the replay",
            "invalidateAutoSleepData(true);" in oauthSuccess && "setTimeout(loadAutoSleepData, 0);" in oauthSuccess,
        )
        val summary = source.substringAfter("function autoSleepSummaryText() {").substringBefore("function autoSleepPanel() {")
        assertTrue(
            "learned Home Assistant Area must lead the activity summary",
            summary.indexOf("Home Assistant Area:") in 0 until summary.indexOf("Phase:"),
        )
        val panel = source.substringAfter("function autoSleepPanel() {").substringBefore("function updateAutoSleepSummary() {")
        assertFalse("self-evident replay copy must not clutter the chart", "One-minute replay from selected Area-source history" in panel)
        assertFalse("historical caveat belongs outside the chart UI", "Source-history estimate; past panel touches" in panel)
        assertFalse(
            "removed replay and caveat prose must not remain in the accessible chart summary",
            "One-minute current-version replay" in source || "This source-history estimate excludes" in source,
        )
        assertTrue(
            "source rows must expose a persistent include toggle",
                "function toggleAutoSleepSource(source)" in source &&
                "fetch(\"/api/v1/auto-sleep/source\"" in source &&
                "area_key: areaKey, source_key: sourceKey, included: !included" in source &&
                "autoSleepSourceUpdating[sourceKey]" in source &&
                "aria-pressed" in source && "aria-disabled" in source && "Suppressed" in source &&
                "invalidateAutoSleepData();" in source && "loadAutoSleepData();" in source,
        )
        assertTrue(
            "source and range refreshes must retain the prior snapshot behind one non-layout overlay",
            "auto-sleep-chart-content" in source && "auto-sleep-loading-overlay" in source &&
                "content.replaceChildren(replacement)" in source && "autoSleepHistoryBusy()" in source &&
                "var shouldReplace = replaceSnapshot === true || !content.firstChild" in source &&
                "if (!shouldReplace)" in source,
        )
        assertTrue(
            "unrelated Configure renders must keep the activity subtree connected",
            "var retainedAutoSleepPanel = document.getElementById(\"auto-sleep-status\")" in source &&
                "autoSleepParking.appendChild(retainedAutoSleepPanel)" in source &&
                "if (retainedAutoSleepPanel && g === \"Behaviour\") root.appendChild(card)" in source &&
                "card.appendChild(retainedAutoSleepPanel || autoSleepPanel())" in source &&
                "if (autoSleepParking) autoSleepParking.remove()" in source,
        )
        assertTrue(
            "retained chart renders must restore both nested and page scroll after focus",
            "retainedAutoSleepScrollTop" in source && "retainedAutoSleepPageY" in source &&
                "window.scrollTo(retainedAutoSleepPageX, retainedAutoSleepPageY)" in source,
        )
        assertTrue(
            "retained source handlers and ARIA state must follow current busy state rather than a captured render value",
            "function sourceInteractionBlocked()" in source &&
                "autoSleepSourceUpdating[sourceKey] || autoSleepHistoryBusy()" in source &&
                "if (!sourceInteractionBlocked()) toggleAutoSleepSource(source)" in source &&
                "row.setAttribute(\"aria-disabled\", retainedBusy ? \"true\" : \"false\")" in source,
        )
        assertTrue(
            "source mutation callbacks must not overwrite a newer Area generation",
            "var updateGeneration = autoSleepAreaGeneration" in source &&
                "updateGeneration !== autoSleepAreaGeneration" in source &&
                "autoSleepSourceUpdating[sourceKey] !== updateToken" in source,
        )
        assertFalse(
            "terminal history errors must not delete the retained snapshot",
            "if (!retryAutomatically) autoSleepHistory = null" in source,
        )
        assertTrue(
            "tooltip ownership must separate the full source label from the chart action",
            "class: \"auto-sleep-label\", text: labelText, title: label" in source &&
                "trackAttrs.title = included ? \"Click to suppress this source\"" in source,
        )
        assertFalse(
            "rows and individual intervals must not own redundant hover titles",
            "rowAttrs.title" in source || "class: \"auto-sleep-interval \" + state, title:" in source,
        )
        assertTrue(
            "an all-suppressed Area must still load history so sources can be re-enabled",
            "phase === \"no_included_sources\"" in source && "discovered_source_count" in source,
        )
        assertTrue(
            "OFF to ON must wait for a fresh Home Assistant Area prerequisite",
            "/api/v1/auto-sleep/prerequisite" in source &&
                "autoSleepPrerequisite.eligible !== true" in source &&
                "Assign this panel to a Home Assistant Area before enabling Auto sleep." in source &&
                "Checking this panel’s Home Assistant Area…" in source,
        )
        assertTrue(
            "Area prerequisite status must be accessible and refreshed on page return without polling",
            "auto-sleep-prerequisite-status" in source && "aria-describedby" in source &&
                "window.addEventListener(\"focus\", scheduleAutoSleepPrerequisite)" in source &&
                "document.addEventListener(\"visibilitychange\"" in source,
        )
        assertTrue(
            "a disabled auto sleep switch must remain keyboard discoverable while activation is suppressed",
            "role: \"switch\", tabindex: \"0\"" in source &&
                "autoSleepPrerequisite.eligible !== true) return" in source,
        )
        val areaRefresh = source.substringAfter("var initialAreaMismatch =")
            .substringBefore("} else if (nextAreaName) {")
        assertTrue(
            "Area changes must fence stale requests while retaining the completed replay until its replacement is ready",
            "autoSleepAreaMatchesName" in areaRefresh &&
                "autoSleepAreaGeneration++;" in areaRefresh &&
                "invalidateAutoSleepData();" in areaRefresh &&
                "updateAutoSleepHistory();" in areaRefresh &&
                "invalidateAutoSleepData(true);" !in areaRefresh &&
                "updateAutoSleepHistory(true);" !in areaRefresh &&
                "function convergeAutoSleepOffForMissingArea()" in source &&
                "savedValues.auto_sleep = \"false\"" in source,
        )
        assertFalse("Behaviour card must not retain its obsolete subtitle", "\"Behaviour\": \"Android/app behaviour\"" in source)
        assertTrue(
            "auto sleep help must describe active screen actuation",
            "Automatically wake the panel when activity is detected and switch the screen off after the learned delay." in source,
        )
        val css = asset("info.css").readText()
        val historyRule = css.substringAfter(".auto-sleep-history{").substringBefore("}")
        assertTrue("timeline must fill the column without an outer card", "width:100%" in historyRule)
        assertFalse(
            "timeline must not use an outer card treatment",
            listOf("border:", "border-radius:", "background:").any { it in historyRule },
        )
        val intervalRule = css.substringAfter(".auto-sleep-interval{").substringBefore("}")
        assertFalse("timeline intervals must have square corners", "border-radius" in intervalRule)
        assertFalse("source lanes must not have horizontal separators", ".auto-sleep-lane{min-height:31px;border-bottom" in css)
        assertTrue("calculated and source rows must share one height", ".auto-sleep-lane.policy{background:" in css)
        assertTrue("suppressed sources must remain visibly distinct", ".auto-sleep-lane.source.suppressed{" in css)
        assertTrue(
            "calculated sleep intervals must be blue rather than sharing source-clear grey",
            ".auto-sleep-interval.off{background:#68727f" in css &&
                ".auto-sleep-lane.policy .auto-sleep-interval.allow_sleep{background:#9aafc2;color:#172533" in css,
        )
        assertTrue(
            "unavailable and inhibited intervals must use the red warning treatment",
            ".auto-sleep-interval.unavailable,.auto-sleep-interval.inhibited{background:repeating-linear-gradient(135deg,#a33b3b" in css,
        )
        assertTrue(
            "preparing history must overlay rather than resize the chart",
            ".auto-sleep-history{position:relative" in css &&
                ".auto-sleep-loading-overlay{position:absolute;inset:0" in css,
        )
    }

    private fun asset(name: String): File {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(File(working, "app/src/main/assets/$name"), File(working, "src/main/assets/$name"))
            .first { it.isFile }
    }
}
