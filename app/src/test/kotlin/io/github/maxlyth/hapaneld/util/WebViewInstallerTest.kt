package io.github.maxlyth.hapaneld.util

import io.github.maxlyth.hapaneld.device.WebViewSpec
import io.github.maxlyth.hapaneld.util.WebViewInstaller.Decision
import io.github.maxlyth.hapaneld.util.WebViewInstaller.decide
import io.github.maxlyth.hapaneld.util.WebViewInstaller.shouldSkipAutoUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewInstallerTest {
    private val rec = WebViewSpec("https://example/webview.apk", "138.0.7204.63", "abcd")
    private val MIN = 110

    @Test fun installsWhenEngineTooOldAndRecommendedNewer() {
        assertEquals(Decision.Install(rec), decide(rec, engineMajor = 107, minChromium = MIN, force = false))
    }

    @Test fun skipsWhenEngineAtOrAboveThreshold() {
        // Already renders HA (e.g. the recommended build is in) — no reinstall loop.
        assertTrue(decide(rec, engineMajor = 138, minChromium = MIN, force = false) is Decision.UpToDate)
        assertTrue(decide(rec, engineMajor = 110, minChromium = MIN, force = false) is Decision.UpToDate)
    }

    @Test fun skipsWhenEngineUnknown() {
        // Cromite-swap can leave the UA unreadable early; never act on an unknown engine.
        assertTrue(decide(rec, engineMajor = null, minChromium = MIN, force = false) is Decision.UpToDate)
    }

    @Test fun skipsWhenRecommendedNotNewerThanTooOldEngine() {
        // Threshold above the recommended build (hypothetical): don't install something no newer.
        assertTrue(decide(rec, engineMajor = 138, minChromium = 200, force = false) is Decision.NotNewer)
        assertTrue(decide(rec, engineMajor = 150, minChromium = 200, force = false) is Decision.NotNewer)
    }

    @Test fun noRecommendationLeavesWebViewAlone() {
        assertEquals(Decision.NoRecommendation, decide(null, engineMajor = 83, minChromium = MIN, force = false))
        // …even with force (nothing to install).
        assertEquals(Decision.NoRecommendation, decide(null, engineMajor = 83, minChromium = MIN, force = true))
    }

    @Test fun forceInstallsRegardlessOfAge() {
        // The manual "Update WebView" button — reinstall even if the engine looks current.
        assertEquals(Decision.Install(rec), decide(rec, engineMajor = 138, minChromium = MIN, force = true))
    }

    @Test fun specMajorParses() {
        assertEquals(138, rec.major)
        assertEquals(0, WebViewSpec("u", "unknown", "c").major)
    }

    // --- auto-update path (Mechanism A): advance a WORKING engine to a newer pin ---

    private val newer = WebViewSpec("https://example/lineageos-150.apk", "150.0.7871.63", "beef")

    @Test fun autoUpdateAdvancesAWorkingEngineToANewerPin() {
        // The Cromite-147 → LineageOS-150 swap: the engine renders HA (147 ≥ 110) so the heal path leaves
        // it alone, but the auto-update path advances it to the newer pinned build.
        assertTrue(decide(newer, engineMajor = 147, minChromium = MIN, force = false) is Decision.UpToDate)
        assertEquals(Decision.Install(newer), decide(newer, engineMajor = 147, minChromium = MIN, force = false, autoUpdate = true))
    }

    @Test fun autoUpdateSkipsWhenPinNotNewer() {
        // Already on the pinned major (or newer) → NotNewer, so no reinstall loop after a successful swap.
        assertTrue(decide(rec, engineMajor = 138, minChromium = MIN, force = false, autoUpdate = true) is Decision.NotNewer)
        assertTrue(decide(rec, engineMajor = 150, minChromium = MIN, force = false, autoUpdate = true) is Decision.NotNewer)
    }

    @Test fun autoUpdateNeverActsOnUnknownEngine() {
        assertTrue(decide(rec, engineMajor = null, minChromium = MIN, force = false, autoUpdate = true) is Decision.UpToDate)
    }

    @Test fun autoUpdateAlsoHealsABrokenEngine() {
        // Below the floor AND older than the pin → the auto-update path covers the heal case in one branch.
        assertEquals(Decision.Install(rec), decide(rec, engineMajor = 83, minChromium = MIN, force = false, autoUpdate = true))
    }

    // --- loop guard for the scheduled auto-update (shouldSkipAutoUpdate) ---
    // The ONLY thing preventing a daily ~90 MB re-download (and, on the built-in renderer, an exitProcess
    // restart) on an opt-in panel where the provider won't switch. It lives in a Service method the JVM
    // suite can't exercise, so pin the extracted predicate here — a regression (record-only-on-OK, or
    // flipping the comparison) would otherwise reintroduce the loop with the whole suite still green.

    @Test fun autoUpdateGuardSkipsWhenAttemptedPinNeverBound() {
        // Signature-locked TPA10: recorded the pin last tick, cross-signer install rejected → engine still 147.
        assertTrue(shouldSkipAutoUpdate(lastVersion = "150.0.7871.63", recVersion = "150.0.7871.63", recMajor = 150, engineMajor = 147))
    }

    @Test fun autoUpdateGuardSkipsWhenEngineUnknownAfterAttempt() {
        // Recorded the pin but the UA won't parse (records-then-can't-verify) → treat as not switched, stop.
        assertTrue(shouldSkipAutoUpdate(lastVersion = "150.0.7871.63", recVersion = "150.0.7871.63", recMajor = 150, engineMajor = null))
    }

    @Test fun autoUpdateGuardDoesNotSkipAfterProviderBound() {
        // Successful swap: engine now at/above the pin's major → don't skip (decide then returns NotNewer,
        // so still no reinstall loop, but the guard itself must NOT short-circuit here).
        assertFalse(shouldSkipAutoUpdate(lastVersion = "150.0.7871.63", recVersion = "150.0.7871.63", recMajor = 150, engineMajor = 150))
        assertFalse(shouldSkipAutoUpdate(lastVersion = "150.0.7871.63", recVersion = "150.0.7871.63", recMajor = 150, engineMajor = 151))
    }

    @Test fun autoUpdateGuardDoesNotSkipOnPinBump() {
        // Maintainer advanced the pin (new version string) → re-attempt even though the old one was recorded.
        assertFalse(shouldSkipAutoUpdate(lastVersion = "150.0.7871.63", recVersion = "151.0.0.0", recMajor = 151, engineMajor = 147))
    }

    @Test fun autoUpdateGuardDoesNotSkipOnFirstAttempt() {
        // Nothing recorded yet (fresh panel) → never skip the first attempt.
        assertFalse(shouldSkipAutoUpdate(lastVersion = "", recVersion = "150.0.7871.63", recMajor = 150, engineMajor = 147))
    }
}
