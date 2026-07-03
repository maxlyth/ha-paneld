package io.github.maxlyth.hapaneld.util

import android.content.Context
import android.util.Log
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.device.WebViewSpec
import io.github.maxlyth.hapaneld.http.PanelHealth

/**
 * Auto-heals the panel's **System WebView** when it's too old to render the Home Assistant dashboard.
 *
 * These panels have no Play Store, so a stock WebView (often Chromium ~83–107) leaves the HA frontend
 * **blank** and there's no built-in way to update it — the single most common first-run failure. When
 * the profile declares a known-good build ([DeviceProfile.recommendedWebView]), ha-paneld downloads it
 * from the `webview-mirror` release and installs it over root via the pinned-signer [AppInstaller]
 * (the same path the Companion updater uses). The build always uses the `com.android.webview` package,
 * so the framework auto-selects it as the provider — no allowlist edit, no extra app.
 *
 * The install-or-not decision keys on the **real** Chromium engine version (from the WebView UA), NOT
 * the package versionName — a Cromite/LineageOS build stamps the OEM stock version to clear a
 * signature-locked provider gate, so the package version lies. Network + root: call OFF the main thread.
 */
object WebViewInstaller {
    const val WEBVIEW_PKG = "com.android.webview"
    private const val TAG = "ha-paneld/webview"

    /** What [heal] should do — a pure decision so the gating logic is unit-testable without a device. */
    sealed class Decision {
        /** No known-good build for this panel → leave the WebView alone. */
        object NoRecommendation : Decision()
        /** The engine already renders HA (≥ threshold) or is unknown → don't touch it. */
        data class UpToDate(val engineMajor: Int?) : Decision()
        /** The recommended build is no newer than what's already running → nothing to gain. */
        data class NotNewer(val version: String) : Decision()
        /** Install [spec]. */
        data class Install(val spec: WebViewSpec) : Decision()
    }

    /**
     * Decide whether to install. [engineMajor] is the real Chromium major from the WebView UA (null =
     * unknown). [force] skips the age check (the manual "Update WebView" button). Heal only when the
     * engine is genuinely below [minChromium] AND the recommended build is newer, so there's no reinstall
     * loop and an unknown/modern engine is never disturbed.
     */
    fun decide(rec: WebViewSpec?, engineMajor: Int?, minChromium: Int, force: Boolean): Decision = when {
        rec == null -> Decision.NoRecommendation
        force -> Decision.Install(rec)
        engineMajor == null || engineMajor >= minChromium -> Decision.UpToDate(engineMajor)
        rec.major <= engineMajor -> Decision.NotNewer(rec.version)
        else -> Decision.Install(rec)
    }

    /** Heal the WebView per [decide]. Returns a short human status; "OK: …" on a successful install. */
    suspend fun heal(context: Context, profile: DeviceProfile, engineMajor: Int?, force: Boolean = false): String =
        when (val d = decide(profile.recommendedWebView, engineMajor, PanelHealth.MIN_CHROMIUM, force)) {
            is Decision.NoRecommendation -> "no known-good WebView for this panel"
            is Decision.UpToDate -> "up to date (Chromium ${d.engineMajor ?: "?"})"
            is Decision.NotNewer -> "already current (${d.version})"
            is Decision.Install -> {
                Log.i(TAG, "healing WebView → ${d.spec.version} (engine major was $engineMajor)")
                val r = AppInstaller.install(context, d.spec.url, AppInstaller.Pin(WEBVIEW_PKG, d.spec.certSha256))
                if (r == "OK") "OK: installed WebView ${d.spec.version} — reloading the dashboard" else r
            }
        }
}
