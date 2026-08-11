package io.github.maxlyth.hapaneld.http

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Custom dashboard-path control exists twice — the Configure form and the guided wizard — because
 * the two surfaces are separate assets with no shared module. These pin the parts where drift between
 * them, or a regression in one of them, would be silent for the user: the two files agreeing on what a
 * path looks like, the empty box never submitting as Auto, and Custom staying reachable in exactly the
 * states where hand-entering a path is the only way through.
 */
class HomeDashboardCustomPathUiContractTest {
    private val configure = File("src/main/assets/configure.js").readText()
    private val setup = File("src/main/assets/setup.js").readText()
    private val css = File("src/main/assets/info.css").readText()

    private val picker = configure.substring(
        configure.indexOf("if (f.picker === \"ha_dashboard\")"),
        configure.indexOf("if (f.picker === \"ha_area\")"),
    )

    /** The value of a `var NAME = "…";` declaration, so the test reads the asset rather than a copy. */
    private fun literal(source: String, name: String): String? =
        Regex("""var $name = "((?:[^"\\]|\\.)*)";""").find(source)?.groupValues?.get(1)

    @Test fun bothSurfacesAgreeOnWhatACustomDashboardPathLooksLike() {
        // Two independently-maintained copies of the rule are how one surface starts accepting what the
        // other rejects. Compare the declarations themselves; a divergent edit fails here, not on a panel.
        val configureSentinel = literal(configure, "CUSTOM_DASHBOARD")
        val setupSentinel = literal(setup, "CUSTOM_DASHBOARD")
        assertNotNull("configure.js declares no CUSTOM_DASHBOARD sentinel", configureSentinel)
        assertEquals(configureSentinel, setupSentinel)

        val configurePattern = literal(configure, "DASHBOARD_PATH_PATTERN")
        val setupPattern = literal(setup, "DASHBOARD_PATH_PATTERN")
        assertNotNull("configure.js declares no DASHBOARD_PATH_PATTERN", configurePattern)
        assertEquals(configurePattern, setupPattern)

        // The sentinel must be unreachable as a real value: a dashboard root is ^[a-z0-9][a-z0-9_-]*$,
        // so anything a user could legitimately type would collide and silently mean "Custom".
        val root = configureSentinel!!.trim('/').substringBefore('/')
        assertFalse(
            "the Custom sentinel $configureSentinel is a legal dashboard root and can collide",
            Regex("^[a-z0-9][a-z0-9_-]*$").matches(root),
        )
    }

    @Test fun neitherSurfaceDelegatesPathValidityToTheBrowsersPatternEngine() {
        // `pattern` is compiled with the `v` flag by current Chromium, where the unescaped `/` and `?`
        // in this expression are invalid — and a browser that cannot parse a pattern IGNORES the
        // constraint rather than failing loudly. Configure's check was therefore inert while the
        // wizard's identical expression worked, and the string-parity test below could not see it.
        assertFalse(
            "the dashboard path must not be validated by an HTML pattern attribute",
            picker.contains("pattern:") || setup.contains("pattern:"),
        )
        // Both surfaces evaluate it themselves, so a non-compiling expression cannot pass as a clean run.
        assertTrue(configure.contains("new RegExp(\"^(?:\" + DASHBOARD_PATH_PATTERN + \")$\")"))
        assertTrue(setup.contains("new RegExp(\"^(?:\" + DASHBOARD_PATH_PATTERN + \")$\")"))
    }

    @Test fun anAbandonedCustomPathCannotBlockALaterSave() {
        // A hidden control that stays invalid refuses Save with nothing on screen to correct, because
        // reportValidity() cannot surface an invisible field. Disabling it bars it from constraint
        // validation and from the row scan; the explicit clear covers the value being read back.
        assertTrue(picker.contains("customInput.disabled = !on"))
    }

    @Test fun bothCustomInputsAnnounceTheirOwnExplanation() {
        // The note carries the fallback warning, which is the only thing standing between a typed path
        // and a panel quietly showing something else. It has to reach assistive technology.
        assertTrue(picker.contains("\"aria-describedby\": \"cfg-home_dashboard-path-note\""))
        assertTrue(picker.contains("role: \"status\", \"aria-live\": \"polite\""))
        assertTrue(setup.contains("\"aria-describedby\": \"wiz-home_dashboard_custom_note\""))
        assertTrue(setup.contains("role: \"status\", \"aria-live\": \"polite\""))
    }

    @Test fun anEmptyCustomPathCannotBeSavedAsASilentAuto() {
        // Configure leans on native validity, which only bites while the input is the live control.
        assertTrue(picker.contains("customInput.required = on"))
        // …and the row's validity scan must reach it. The select precedes it in the DOM and is always
        // valid, so a scan that stops at the first control reports the row clean and posts the blank.
        assertTrue(configure.contains("row.querySelectorAll(\"input,select,textarea\")"))
        assertFalse(configure.contains("row.querySelector(\"input,select,textarea\")"))
        // The wizard has no form semantics, so its gate is the primary button plus a submit-time refusal.
        assertTrue(setup.contains("var customIncomplete = hdCustom && !(chosen && wellFormedDashboardPath(chosen))"))
        assertTrue(setup.contains("if (hdCustom && !(chosen && wellFormedDashboardPath(chosen)))"))
    }

    @Test fun theSentinelIsNeverPostedAsADashboardPath() {
        // Both surfaces translate the sentinel into the typed path at the moment of selection. If the
        // raw sentinel reached the config POST it would be rejected by the setting's validator, which
        // is a confusing way to discover a UI bug.
        assertTrue(picker.contains("dashboardSelect.value === CUSTOM_DASHBOARD ? customInput.value.trim() : dashboardSelect.value"))
        assertTrue(setup.contains("typed.home_dashboard = hdCustom ? customInput.value.trim() : sel.value"))
        // The wizard's select carries no competing inline handler that would write the sentinel first.
        assertFalse(setup.contains("onchange: function (e) { typed.home_dashboard = e.target.value;"))
    }

    @Test fun customStaysReachableWhenTheAccountListsNoDashboards() {
        // Previously the control was disabled in this state, which is precisely when someone needs to
        // type a path by hand — a non-admin account seeing no dashboards, or a failed catalogue fetch.
        assertFalse("the dashboard select must no longer be disabled", picker.contains("dashboardSelect.disabled = true"))
        assertFalse("the wizard must not block the step when Custom is live", setup.contains("b.disabled = chosen === undefined || noDashboards;"))
        assertTrue(setup.contains("(noDashboards && !hdCustom)"))
    }

    @Test fun theRevealedInputIsActuallyHiddenAndFitsANarrowPanel() {
        // .hd-custom sets display, which beats the hidden attribute's UA rule — without this the input
        // is permanently visible and the reveal is decorative.
        assertTrue(".hd-custom[hidden] has no display:none rule", css.contains(".hd-custom[hidden]{display:none}"))
        // The wrapper, not just the select, must be constrained or a long option pushes the native
        // popup out of its card. It tracks its column rather than carrying a fixed width, which is what
        // collapses it on a narrow panel — deliberately WITHOUT a media-query rule, since a bare
        // `.hd-picker` override there is lower specificity than this selector and would never apply.
        assertTrue(
            "the picker wrapper must track its column, not a fixed width",
            Regex("""\.frow \.fctl \.hd-picker\{[^}]*width:min\(260px,100%\)""").containsMatchIn(css),
        )
    }
}
