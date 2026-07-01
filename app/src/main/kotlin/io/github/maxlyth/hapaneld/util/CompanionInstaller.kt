package io.github.maxlyth.hapaneld.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * HA Companion app installer / updater. The panels have no Play Store, so the **minimal**
 * Companion never auto-updates — ha-paneld is the only update path. Self-heals a missing or
 * out-of-date Companion via the shared [AppInstaller] (pinned signer + package allowlist). Leaves a
 * Play-managed *full* Companion alone. Network + su — call OFF the main / MQTT thread.
 */
object CompanionInstaller {
    const val FULL_PKG = "io.homeassistant.companion.android"
    const val MINIMAL_PKG = "io.homeassistant.companion.android.minimal"

    // Canonical minimal APK from the latest (non-prerelease) home-assistant/android release.
    private const val MINIMAL_APK_URL =
        "https://github.com/home-assistant/android/releases/latest/download/app-minimal-release.apk"
    private const val TAG = "ha-paneld/companion"

    /** The installed Companion package (full or minimal), or null if neither is present. */
    fun installedPkg(context: Context): String? =
        listOf(FULL_PKG, MINIMAL_PKG).firstOrNull {
            runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess
        }

    /** Install the minimal Companion if missing, or update it if a newer release exists. [force] skips the
     *  version check (the manual-button path). Returns a short human status. */
    suspend fun installOrUpdate(context: Context, force: Boolean = false): String = withContext(Dispatchers.IO) {
        if (AppInstaller.installedVersion(context, FULL_PKG).isNotBlank())
            return@withContext "skipped: full Companion present (Play-managed)"

        val installed = AppInstaller.installedVersion(context, MINIMAL_PKG)
        val missing = installed.isBlank()
        if (!missing && !force) {
            val latest = UpdateChecker.fetchLatest("home-assistant/android")?.first
                ?.removePrefix("v")?.let { Regex("-(?:full|minimal|wear)$").replace(it, "") }
            if (latest != null && !UpdateChecker.isNewer(latest, installed)) return@withContext "up to date ($installed)"
        }

        val r = AppInstaller.install(context, MINIMAL_APK_URL, AppInstaller.COMPANION_MINIMAL)
        if (r != "OK") return@withContext r
        val now = AppInstaller.installedVersion(context, MINIMAL_PKG)
        val verb = if (missing) "installed" else "updated"
        Log.i(TAG, "Companion $verb -> $now")
        "$verb HA Companion app ($now)"
    }
}
