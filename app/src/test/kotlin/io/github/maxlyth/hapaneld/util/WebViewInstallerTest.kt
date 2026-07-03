package io.github.maxlyth.hapaneld.util

import io.github.maxlyth.hapaneld.device.WebViewSpec
import io.github.maxlyth.hapaneld.util.WebViewInstaller.Decision
import io.github.maxlyth.hapaneld.util.WebViewInstaller.decide
import org.junit.Assert.assertEquals
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
}
