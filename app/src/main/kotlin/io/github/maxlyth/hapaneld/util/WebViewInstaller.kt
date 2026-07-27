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

    /**
     * The result of a [heal] attempt. [status] is the exact human-readable message shown in the UI /
     * InstallProgress; callers switch on the variant rather than re-parsing it.
     */
    sealed interface HealResult {
        val status: String
        /** A no-op decision (no recommendation, engine already fine, or the pin is not newer). */
        data class NoAction(override val status: String) : HealResult
        /** The recommended WebView was installed and the dashboard should be reactivated. */
        data class Installed(override val status: String) : HealResult
        /** The install was attempted but failed. [terminal] = a durable rejection (retrying the same pin
         *  cannot help), as opposed to a transient failure that a later tick may clear. */
        data class Failed(override val status: String, val terminal: Boolean) : HealResult
    }

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
     * unknown). [force] skips every check (the manual "Update WebView" button). [autoUpdate] is the
     * scheduled auto-update intent: it drops the "engine already renders HA → leave it" short-circuit so
     * a WORKING engine still advances to a newer pinned build — the heal path (autoUpdate=false) instead
     * only touches an engine genuinely below [minChromium]. Either way an unknown engine is never
     * disturbed and a pin that isn't newer is a no-op, so there's no reinstall loop from [decide] alone.
     */
    fun decide(rec: WebViewSpec?, engineMajor: Int?, minChromium: Int, force: Boolean, autoUpdate: Boolean = false): Decision = when {
        rec == null -> Decision.NoRecommendation
        force -> Decision.Install(rec)
        engineMajor == null -> Decision.UpToDate(null) // can't compare → don't touch
        !autoUpdate && engineMajor >= minChromium -> Decision.UpToDate(engineMajor) // heal-only: leave a working engine
        rec.major <= engineMajor -> Decision.NotNewer(rec.version)
        else -> Decision.Install(rec)
    }

    /**
     * Loop guard for the scheduled auto-update ([io.github.maxlyth.hapaneld.PaneldService] `autoUpdateWebView`):
     * skip re-attempting the same pinned [recVersion] once a prior tick recorded it AND the running
     * [engineMajor] still hasn't reached [recMajor] — i.e. the provider isn't actually switching
     * (variant / signature-locked hardware where `pm install` can't change the WebView signer), so
     * re-downloading ~90 MB (and, on the built-in renderer, restarting the process) every 24 h tick would be
     * pointless. A pin bump ([recVersion] differs from the recorded one) clears it and re-attempts; an
     * unknown engine ([engineMajor] == null) counts as "still not switched" so a records-then-can't-verify
     * panel also stops retrying. Kept pure + unit-tested because this predicate is the only thing standing
     * between an opt-in panel and a daily re-download/restart loop, and a regression here stays green.
     */
    fun shouldSkipAutoUpdate(lastVersion: String, recVersion: String, recMajor: Int, engineMajor: Int?): Boolean =
        lastVersion == recVersion && (engineMajor == null || engineMajor < recMajor)

    /** Whether an auto-update result is durable evidence that retrying the same pin cannot help. Network,
     *  staging, storage, and temporarily unavailable privilege failures remain retryable on the next tick;
     *  successful/no-op decisions and package-manager rejection of a provider swap are terminal until the
     *  profile pin changes or the user explicitly retries. The terminal-vs-retryable classification is the
     *  producer's ([InstallOutcome]), carried on [HealResult.Failed.terminal]. */
    internal fun shouldRecordAutoAttempt(result: HealResult): Boolean = when (result) {
        is HealResult.Failed -> result.terminal
        is HealResult.NoAction, is HealResult.Installed -> true
    }

    /** Heal the WebView per [decide], returning a typed [HealResult] whose [HealResult.status] is a short
     *  human status ("OK: …" on a successful install). [autoUpdate] = the scheduled update-to-pin path
     *  (advance a working engine to a newer pin). */
    suspend fun heal(context: Context, profile: DeviceProfile, engineMajor: Int?, force: Boolean = false, autoUpdate: Boolean = false): HealResult =
        when (val d = decide(profile.recommendedWebView, engineMajor, PanelHealth.MIN_CHROMIUM, force, autoUpdate)) {
            is Decision.NoRecommendation -> HealResult.NoAction("no known-good WebView for this panel")
            is Decision.UpToDate -> HealResult.NoAction("up to date (Chromium ${d.engineMajor ?: "?"})")
            is Decision.NotNewer -> HealResult.NoAction("already current (${d.version})")
            is Decision.Install -> {
                Log.i(TAG, "healing WebView → ${d.spec.version} (engine major was $engineMajor)")
                when (val outcome = AppInstaller.install(
                    context,
                    d.spec.url,
                    AppInstaller.Pin(WEBVIEW_PKG, d.spec.certSha256, d.spec.apkSha256),
                    allowShizuku = false,
                )) {
                    InstallOutcome.Succeeded ->
                        HealResult.Installed("OK: installed WebView ${d.spec.version} — reloading the dashboard")
                    is InstallOutcome.Rejected -> HealResult.Failed(outcome.message, terminal = true)
                    is InstallOutcome.Retryable -> HealResult.Failed(outcome.message, terminal = false)
                }
            }
        }
}
