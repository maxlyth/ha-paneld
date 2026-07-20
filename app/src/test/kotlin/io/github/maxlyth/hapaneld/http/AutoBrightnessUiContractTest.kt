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
        assertTrue("yesterday must become a subtle reference and older days must fade further", "alpha: [1, .28, .20, .14, .10, .07, .05][boundedAge]" in source)
        assertTrue("historical blur must start at five pixels and grow by sixty percent per day", "5 * Math.pow(1.6, boundedAge - 1)" in source)
        assertTrue("historical blur must use canvas filtering without a sharp-centre shadow", "ctx.filter = \"blur(" in source && "ctx.shadowBlur" !in source)
        assertTrue("today must remain raw while older days receive wider median filtering", "medianWindow: [1, 3, 5, 7, 9, 11, 13][boundedAge]" in source)
        assertTrue("older days must receive progressively wider weighted smoothing", "averageWindow: [1, 1, 3, 5, 7, 9, 11][boundedAge]" in source)
        assertTrue("smoothing must be display-only and applied within discontinuity-split runs", "smoothedRunValues(run, field, day.age)" in source)
        assertTrue("missing buckets and DST discontinuities must split lines", "point.minute - previous.minute !== bucketMinutes" in source && "point.minuteOfDay - previous.minuteOfDay !== bucketMinutes" in source)
        assertTrue("chart bitmap must be redrawn after layout changes", "setTimeout(drawAutoBrightnessChart, 100)" in source)
        assertTrue("sensitivity edits must reproject history", "f.key === \"auto_brightness_sensitivity\"" in source)
        assertTrue("minimum edits must reproject history", "f.key === \"auto_brightness_minimum_percent\"" in source)
        assertTrue("minimum preview must use the server projection", "&minimum_percent=" in source)
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
        assertTrue("sensitivity must join the minimum control", "#cfg-auto_brightness_minimum_percent + #cfg-auto_brightness_sensitivity" in css)
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
            "f.key === \"auto_brightness_minimum_percent\" || f.key === \"auto_brightness_sensitivity\"" in source,
        )
        assertTrue("learning must be omitted without a source", "if (ambientLightSourceReady()) card.appendChild(autoBrightnessPanel())" in source)
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
