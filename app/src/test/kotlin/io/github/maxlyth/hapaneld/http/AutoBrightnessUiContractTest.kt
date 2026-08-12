package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBrightnessUiContractTest {
    @Test fun `configure UI discovers HA sources lazily and preserves exact ids`() {
        val source = asset("configure.js").readText()

        assertTrue("schema picker must have a dedicated control", "f.picker === \"ha_illuminance\"" in source)
        assertTrue("candidate catalog must be queried", "/api/v1/auto-brightness/sources?q=" in source)
        assertTrue("catalog loading must wait for focus", "input.addEventListener(\"focus\"" in source)
        assertTrue("blank must explain local-sensor fallback", "Blank uses the panel sensor" in source)
        assertFalse("picker must not delegate placement to a native datalist", "el(\"datalist\"" in source)
        assertTrue("picker must expose combobox and listbox semantics", "role: \"combobox\"" in source && "role: \"listbox\"" in source)
        assertTrue("listbox must use a body-level DOM portal", "document.body.appendChild(list)" in source)
        assertTrue("popup placement must use the visual viewport", "window.visualViewport" in source && "getBoundingClientRect()" in source)
        assertTrue("popup placement must honor a non-zero visual viewport origin", "visual.offsetLeft" in source && "visual.offsetTop" in source)
        assertTrue("popup must widen into available right-side space", "preferredWidth = 520" in source && "viewport.right - edge - left" in source)
        assertTrue("popup must support keyboard selection and dismissal", "event.key === \"ArrowDown\"" in source && "event.key === \"Enter\"" in source && "event.key === \"Escape\"" in source)
        assertTrue("popup must use unified pointer selection for mouse and touch", "option.addEventListener(\"pointerdown\"" in source)
        assertTrue("rerender must remove the portal and listeners", "if (haPickerCleanup) haPickerCleanup()" in source && "removeEventListener(\"pointerdown\"" in source)
        assertFalse("picker must not rewrite entity ids", "toLowerCase()" in source.substringAfter("f.picker === \"ha_illuminance\"").substringBefore("function pointValue"))
    }

    @Test fun `configure UI overlays panel-local days and exposes explicit authority actions`() {
        val source = asset("configure.js").readText()
        val service = projectFile("app/src/main/kotlin/io/github/maxlyth/hapaneld/PaneldService.kt").readText()

        assertTrue("history is bounded to seven days", "/api/v1/auto-brightness/history?hours=168" in source)
        assertTrue("calendar metadata must use the panel timezone", "put(\"time_zone\", zone.id)" in service && "put(\"localDay\", localDay)" in service)
        assertTrue("rolling history must omit the zero-opacity eighth date", "point.dayAge < 7" in source)
        assertTrue("chart must use a fixed 24-hour axis", "p.minuteOfDay / 1440" in source && "\"24:00\"" in source)
        assertTrue("plot must use full canvas width with both scales labelled inside", "left: 0, right: 0" in source && "pad.left + 5" in source && "width - pad.right - 5" in source)
        // Seven days times three traces was twenty-one lines and adjacent days could not be separated at
        // any opacity, blur or colour step - seven ordered levels do not fit between today's saturated
        // colour and the dimmest step still visible on the card. The chart now draws three days and
        // collapses the rest of the week into one painted min/max region.
        assertTrue("only the three most recent days may be drawn as lines", "function autoBrightnessLineDays() { return 3; }" in source)
        assertTrue("the day loop must be bounded by that count", "day.age < autoBrightnessLineDays()" in source)
        assertTrue("older days must thin as well as soften", "widthPx: [1.8, 1.5, 1.3][boundedAge]" in source)
        assertTrue("the per-day width must reach the traces", source.split("style.widthPx, ").size == 4)
        // Opacity is deliberately absent from the ramp: ink conservation already dims a blurred trace,
        // and cutting alpha on top of that bleached the older days until they read as grey.
        assertFalse("age must not be carried by an opacity ramp on top of the blur", "alpha: [1," in source)
        // The week's spread is one painted region, drawn BEFORE the day lines so they sit on top of it,
        // and tinted with the lux hue because a neutral fill reads as haze rather than as deliberate paint.
        assertTrue("the week must collapse into a painted min/max region", "ctx.fillStyle = \"rgba(74,158,255,.15)\"" in source)
        assertTrue("the region must span every day, not just the drawn ones", "if (lowest[slot] == null || low < lowest[slot])" in source && "if (highest[slot] == null || high > highest[slot])" in source)
        // The fill must break wherever the week has no observations, exactly as the day traces do. One
        // polygon over every sampled slot bridges the gap and paints a range that was never measured -
        // more misleading than the equivalent line defect, because a filled area reads as coverage.
        assertTrue("the region must break on missing data rather than bridge it", "if (span.length && slot - span[span.length - 1] !== bucketMinutes)" in source && "spans.forEach" in source)
        assertTrue("the region must be painted before the day lines", source.indexOf("rgba(74,158,255,.15)") < source.indexOf("day.age < autoBrightnessLineDays()"))
        assertFalse("the retired per-day envelope must not linger", "style.band" in source)
        assertTrue("visible detail must describe individually drawn days and the weekly range", "\" days\") + \" drawn individually\"" in source && "earlier history shown as weekly range" in source)
        assertFalse("visible detail must not describe the retired seven-line fade", "overlaid · older days fade" in source)
        assertTrue("canvas accessibility text must describe the current encoding", "the three most recent days drawn individually, with the rest of the week shown as a shaded minimum-to-maximum range" in source)
        // A faint mark loses hue toward the ground, so a more heavily blurred day needs more chroma to
        // still read as blue, amber or purple. Clamped, so this can only saturate, never wash to white.
        assertTrue("blurred traces must be chroma-compensated", "var amount = 1 + blurPx * .13" in source && "grey + (channel - grey) * amount" in source)
        assertFalse("colour must not be computed by an unevenly supported CSS syntax", "color-mix" in source)
        assertTrue("older days must carry a real blur radius, clearly stepped", "blurPx: [0, 1.2, 3.2][boundedAge]" in source)
        assertTrue("the blur radius must be passed to every trace", source.split("style.blurPx);").size == 4)
        // The blur must be DRAWN, not delegated to the 2D context's filter property. That was the first
        // implementation: Chromium and the panel WebView applied it while Safari showed
        // no visible softening, so the same history rendered as two different charts and the radius was
        // tuned blind to 52px. A capability probe is not a fallback either — it silently drops the blur
        // instead of substituting one. The wider rule this encodes: Safari is a first-class target for
        // this UI, so a web API that only some engines implement is not acceptable without a real
        // fallback, and a capability probe is not one.
        assertTrue("the blur must be drawn as a feathered stroke", "ctx.lineWidth = widthPx + 2 * blurPx * pass[0]" in source && "featherPasses" in source)
        // A blur SPREADS a line's brightness; it must never add more. Without normalising the pass
        // opacities by width/spread-width the passes simply stack, so a blurred trace lights more
        // pixels than the sharp one and reads as bolder and glowing rather than receding.
        assertTrue("blurring must conserve ink rather than add it", "var conserve = spreadInk > 0 ? widthPx / spreadInk : 1" in source && "pass[1] * conserve" in source)
        assertFalse("the blur must not be delegated to a renderer-dependent canvas filter", "ctx.filter" in source)
        assertFalse("a canvas filter capability probe must not gate the blur", "\"filter\" in ctx" in source)
        assertFalse("the blur must not migrate to an equally uneven shadow blur", "ctx.shadowBlur" in source)
        assertFalse("portable encoding must not claim pixel-identical engine rasterisation", "same picture on every engine" in source || "renders the same on every modern engine" in source)
        assertTrue("today must remain raw while older days receive wider median filtering", "medianWindow: [1, 3, 5, 7, 9, 11, 13][boundedAge]" in source)
        assertTrue("older days must receive progressively wider weighted smoothing", "averageWindow: [1, 1, 3, 5, 7, 9, 11][boundedAge]" in source)
        assertTrue("smoothing must be display-only and applied within discontinuity-split runs", "smoothedRunValues(run, field, day.age)" in source)
        assertTrue("missing buckets and DST discontinuities must split lines", "point.minute - previous.minute !== bucketMinutes" in source && "point.minuteOfDay - previous.minuteOfDay !== bucketMinutes" in source)
        assertTrue("chart bitmap must be redrawn after layout changes", "setTimeout(drawAutoBrightnessChart, 100)" in source)
        assertTrue("sensitivity edits must reproject history", "f.key === \"auto_brightness_response_percent\"" in source)
        assertTrue("history must echo the sensitivity used for its projection", "put(\"sensitivity\", previewSensitivity)" in service)
        assertTrue("the chart must identify the sensitivity used for its proposed line", "Sensitivity \" + projectionSensitivity" in source)
        assertTrue("minimum edits must reproject history", "f.key === \"auto_brightness_minimum_percent\"" in source)
        assertTrue("minimum preview must use the server projection", "&minimum_percent=" in source)
        // A bound copied into this file drifts from the registry silently, and the failure mode is not
        // an error: the query parameter is dropped and the chart projects the STORED value while the
        // operator believes they are previewing the one they just typed. A hard-coded 95 did exactly
        // that once the ceiling rose to 99, so the preview must read its range from the served schema.
        assertTrue(
            "preview bounds must come from the served schema, never a literal in this file",
            "field.min == null || field.max == null" in source && "value < field.min || value > field.max" in source,
        )
        assertTrue("chart must retain the spike envelope", "min_lux" in source && "max_lux" in source)
        assertTrue("sensitivity preview must draw proposed brightness", "proposedBrightness" in source && "yBrightness" in source)
        assertTrue("brightness axis must use user-facing percentages", "ctx.fillText(\"100%\"" in source && "ctx.fillText(\"0%\"" in source)
        assertTrue("learned history reset must be explicit", "/api/v1/auto-brightness/reset" in source)
        assertTrue("manual pause hand-back must be explicit", "/api/v1/auto-brightness/resume" in source)
        assertTrue("history reset must require confirmation", "Delete the seven-day ambient-light history" in source)
        assertTrue("saving a changed adaptive setting must refresh runtime truth", "hasOwnProperty.call(submittedValues, \"auto_brightness_ha_entity\")" in source)
        assertTrue("save rejection must show the server's actionable reason", "e && e.message ? e.message : \"Save failed.\"" in source)
        assertTrue("invalid numeric controls must be stopped before the custom fetch", "firstInvalidDirtySetting()" in source && "invalid.control.reportValidity" in source)
        assertTrue("enabled history must refresh on its five-minute bucket cadence", "AUTO_BRIGHTNESS_REFRESH_MS = 5 * 60 * 1000" in source)
        assertTrue("disabled or hidden auto-brightness must not schedule chart polling", "values.auto_brightness !== \"true\" || document.hidden" in source)
        assertTrue("hidden Configure pages must stop timer wakes", "document.addEventListener(\"visibilitychange\"" in source && "if (autoBrightRefreshTimer) clearTimeout(autoBrightRefreshTimer)" in source)
        assertTrue("returning to Configure must refresh once", "else if (values.auto_brightness === \"true\")" in source && "loadAutoBrightnessData(false)" in source)
    }

    @Test fun `adaptive history refresh is source consistent and transition polling is bounded`() {
        val source = asset("configure.js").readText()

        assertTrue("status must bypass browser caches", "fetch(\"/api/v1/auto-brightness\", { cache: \"no-store\" })" in source)
        assertTrue("history must bypass browser caches", "minimumSuffix, { cache: \"no-store\" })" in source)
        assertTrue("status and history must be bound by their opaque source revision", "statusRevision === historyRevision" in source)
        assertTrue("a matching old pair must not satisfy a newly selected entity", "autoBrightnessStatusMatchesSelection(result[0])" in source && "actual === expected" in source)
        assertTrue("mismatched history must be replaced instead of painted", "if (!revisionsMatch)" in source && "autoBrightHistory = { points: [] }" in source)
        assertTrue("source saves must enter the transition before reloading", "beginAutoBrightnessSourceTransition(submittedValues.auto_brightness_ha_entity)" in source)
        assertTrue("source saves must fence an in-flight old-source response", "if (!preserveCurrentRequest) autoBrightRequest += 1" in source)
        assertTrue("transition retries must have an explicit finite cap", "AUTO_BRIGHTNESS_TRANSITION_MAX_ATTEMPTS = 10" in source && "autoBrightTransitionAttempts >= AUTO_BRIGHTNESS_TRANSITION_MAX_ATTEMPTS" in source)
        assertTrue("short transition polling must not replace the normal five-minute cadence", "AUTO_BRIGHTNESS_TRANSITION_POLL_MS = 1000" in source && "if (autoBrightSourceTransition) return" in source)
        assertTrue("an empty matching partition must eventually leave loading state", "timedOutEmpty = latest == null" in source && "The selected source has no ambient-light samples yet." in source)
    }

    @Test fun `adaptive chart identifies its source and sample freshness`() {
        val source = asset("configure.js").readText()

        assertTrue("chart summary must explicitly name its selected source", "Source: \" + autoBrightnessSelectedSource()" in source)
        assertTrue("freshness must come from the newest persisted epoch minute", "latestEpochMinute" in source && "latest * 60000" in source)
        assertTrue("freshness must include a local human-readable timestamp", "date.toLocaleString()" in source)
        assertTrue("freshness must state relative age", "ageMinutes + \" min ago\"" in source && "Math.floor(ageMinutes / 1440) + \" days ago\"" in source)
        assertTrue("the chart detail must display freshness", "detail += \" · \" + autoBrightnessFreshness()" in source)
        assertTrue("transition state must be concise and live", "Updating ambient-light source…" in source && "Loading new source history…" in source)
    }

    @Test fun `adaptive controls remain usable on narrow panels`() {
        val css = asset("info.css").readText()

        assertTrue("source picker must shed desktop minimum width", ".ha-entity-picker{width:100%;min-width:0}" in css)
        assertTrue("source listbox must be a contained fixed portal", ".ha-entity-listbox{position:fixed" in css && "overflow-y:auto" in css)
        assertTrue("adaptive header must stack", ".autobright-head{flex-direction:column}" in css)
        assertTrue("chart must have a stable bounded height", ".autobright-chart{height:190px" in css)
    }

    @Test fun `adaptive brightness and updater pairs render as compact subgroups`() {
        val css = asset("info.css").readText()

        assertTrue("the first setting in every card must not have a divider", ".card>h2+.frow{border-top:0}" in css)
        assertTrue("auto-brightness must join the preceding ambient source", "#cfg-auto_brightness_ha_entity + #cfg-auto_brightness" in css)
        assertTrue("minimum must join the auto-brightness toggle", "#cfg-auto_brightness + #cfg-auto_brightness_minimum_percent" in css)
        assertTrue("sensitivity must join the minimum control", "#cfg-auto_brightness_minimum_percent + #cfg-auto_brightness_response_percent" in css)
        assertTrue("learning panel must join the adaptive controls", ".autobright-panel{margin-top:0;padding-top:0;border-top:0}" in css)
        assertTrue("panel updater channel must join its toggle", "#cfg-self_update + #cfg-update_channel" in css)
        assertTrue("Companion updater channel must join its toggle", "#cfg-companion_auto_update + #cfg-companion_update_channel" in css)
    }

    @Test fun `adaptive controls require a configured or local ambient source`() {
        val source = asset("configure.js").readText()

        assertTrue("source readiness must inspect the selected HA entity", "values.auto_brightness_ha_entity" in source)
        assertTrue("blank source placeholder must reflect local capability", "autoBrightStatus.localSourcePresent === true" in source)
        assertTrue("panels without a local source must request an HA entity", "Select a Home Assistant illuminance sensor" in source)
        assertTrue("source readiness must require runtime availability", "autoBrightStatus.sourceAvailable === true" in source)
        assertTrue("source readiness must require a numeric runtime reading", "typeof statusLux === \"number\" && isFinite(statusLux)" in source)
        assertTrue("catalog selections must be available with numeric lux", "item.available === true && typeof lux === \"number\" && isFinite(lux)" in source)
        assertTrue(
            "minimum and sensitivity must be disabled without a source",
            "f.key === \"auto_brightness_minimum_percent\" || f.key === \"auto_brightness_response_percent\"" in source,
        )
        assertTrue(
            "retained learning must survive a temporarily unavailable configured source",
            "if (values.auto_brightness === \"true\" && ambientLightSourceConfigured()) card.appendChild(autoBrightnessPanel())" in source,
        )
        assertTrue("a selected exact HA source remains configured while temporarily unavailable", "auto_brightness_ha_entity || \"\").trim()) return true" in source)
        assertTrue("temporary source loss must be visible without removing history", "Waiting for ambient light…" in source)
        assertTrue("auto-brightness cannot be enabled without a source", "f.key === \"auto_brightness\" && !ambientLightSourceReady()" in source)
        assertTrue("invalid persisted state must render effectively off", "v === \"true\" && !sourceBlocked ? \"true\" : \"false\"" in source)
        assertTrue("dependent rows must be visibly disabled", "dependencyDisabled ? \" dependency-disabled\"" in source)
    }

    private fun asset(name: String): File {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(File(working, "app/src/main/assets/$name"), File(working, "src/main/assets/$name"))
            .first { it.isFile }
    }

    private fun projectFile(path: String): File {
        val working = File(requireNotNull(System.getProperty("user.dir")))
        return listOf(File(working, path), File(working.parentFile, path)).first { it.isFile }
    }
}
