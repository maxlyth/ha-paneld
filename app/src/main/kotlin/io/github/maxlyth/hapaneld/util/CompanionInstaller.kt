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
    private const val REPO = "home-assistant/android"
    // The installable Companion is the minimal variant's release asset.
    private val APK_MATCH: (String) -> Boolean = { it == "app-minimal-release.apk" }

    /** The installed Companion package (full or minimal), or null if neither is present. */
    fun installedPkg(context: Context): String? =
        listOf(FULL_PKG, MINIMAL_PKG).firstOrNull {
            runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess
        }

    /** Up to [limit] recent versions on [channel] for the Install-tab picker (variant-stripped display
     *  version + release-notes URL). Only releases that actually ship a minimal APK are installable. */
    fun versions(channel: String, limit: Int = 10): List<ReleaseCatalog.Version> =
        ReleaseCatalog.list(REPO, channel, limit, APK_MATCH) { UpdateChecker.stripVariant(it.removePrefix("v")) }

    /** Install a SPECIFIC Companion release by its [tag] (the version picker). Resolves the tag's minimal
     *  APK asset server-side, then installs with the pinned signer. Skips if the Play-managed full Companion
     *  is present (never clobber it). */
    suspend fun installVersion(context: Context, tag: String): String = withContext(Dispatchers.IO) {
        if (AppInstaller.installedVersion(context, FULL_PKG).isNotBlank())
            return@withContext "skipped: full Companion present (Play-managed)"
        val url = ReleaseCatalog.apkUrl(REPO, tag, APK_MATCH) ?: return@withContext "no minimal APK for $tag"
        Log.i(TAG, "install Companion tag $tag")
        val r = AppInstaller.install(context, url, AppInstaller.COMPANION_MINIMAL)
        if (r != "OK") return@withContext r
        "installing HA Companion ${UpdateChecker.stripVariant(tag.removePrefix("v"))}"
    }

    /** Resolve the newest release on [channel] to (version, minimal-APK url). `stable` uses
     *  releases/latest (never a prerelease); `prerelease` takes the newest release of any kind from the
     *  release list — GitHub orders it newest-first, so the first `tag_name` and the first
     *  `app-minimal-release.apk` asset URL in document order belong to that release. */
    private fun resolve(channel: String): Pair<String, String>? {
        if (channel != "prerelease")
            return UpdateChecker.fetchLatest("home-assistant/android")
                ?.first?.removePrefix("v")?.let { UpdateChecker.stripVariant(it) to MINIMAL_APK_URL }
        return runCatching {
            val conn = java.net.URL("https://api.github.com/repos/home-assistant/android/releases?per_page=10")
                .openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8_000; conn.readTimeout = 8_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            if (conn.responseCode != 200) return@runCatching null
            val json = conn.inputStream.bufferedReader().readText()
            val tag = Regex(""""tag_name"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
                ?.removePrefix("v") ?: return@runCatching null
            val url = Regex(""""browser_download_url"\s*:\s*"([^"]*app-minimal-release\.apk)"""")
                .find(json)?.groupValues?.get(1) ?: return@runCatching null
            UpdateChecker.stripVariant(tag) to url
        }.getOrNull()
    }

    /** Install the minimal Companion if missing, or update it if a newer release exists on [channel].
     *  [force] skips the version check (the manual-button path). Returns a short human status. */
    suspend fun installOrUpdate(context: Context, force: Boolean = false, channel: String = "stable"): String = withContext(Dispatchers.IO) {
        if (AppInstaller.installedVersion(context, FULL_PKG).isNotBlank())
            return@withContext "skipped: full Companion present (Play-managed)"

        val installed = AppInstaller.installedVersion(context, MINIMAL_PKG)
        val missing = installed.isBlank()
        val resolved = resolve(channel)
        if (!missing && !force) {
            // Compare variant-stripped ("2026.6.5-minimal" IS 2026.6.5 — issue #17); a same-version
            // install of the other variant is never an upgrade.
            val latest = resolved?.first
            if (latest != null && !UpdateChecker.isNewer(latest, UpdateChecker.stripVariant(installed)))
                return@withContext "up to date ($installed)"
        }

        val r = AppInstaller.install(context, resolved?.second ?: MINIMAL_APK_URL, AppInstaller.COMPANION_MINIMAL)
        if (r != "OK") return@withContext r
        val now = AppInstaller.installedVersion(context, MINIMAL_PKG)
        val verb = if (missing) "installed" else "updated"
        Log.i(TAG, "Companion $verb -> $now")
        "$verb HA Companion app ($now)"
    }
}
