package io.github.maxlyth.hapaneld.util

import android.content.Context
import android.util.Log
import io.github.maxlyth.hapaneld.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * ha-paneld self-update — the panels have no Play Store, so ha-paneld is its own update path (same
 * pinned-signer install as the Companion updater, via [AppInstaller.HA_PANELD]). A per-panel **channel** selects which
 * releases to follow: `stable` (GitHub releases/latest — non-prerelease) or `prerelease` (the newest
 * published release, incl. rc builds). Installing a newer build restarts the service (the package's
 * MY_PACKAGE_REPLACED receiver relaunches it); a channel switch may move down a version — allowed by
 * the installer's `-d`. Network + su — call OFF the main / MQTT thread.
 */
object SelfUpdater {
    const val STABLE = "stable"
    const val PRERELEASE = "prerelease"
    private const val REPO = "maxlyth/ha-paneld"
    private const val TAG = "ha-paneld/selfupdate"
    // The ha-paneld release ships a single APK asset.
    private val APK_MATCH: (String) -> Boolean = { it.endsWith(".apk", ignoreCase = true) }

    /** Up to [limit] recent versions on [channel] for the Install-tab picker (version + release-notes URL). */
    fun versions(channel: String, limit: Int = 10): List<ReleaseCatalog.Version> =
        ReleaseCatalog.list(REPO, channel, limit, APK_MATCH) { it.removePrefix("v") }

    /** Install a SPECIFIC ha-paneld release by its [tag] (the version picker). Resolves the tag's APK asset
     *  server-side (the tag comes from the client, so it's validated + only used to look up the pinned
     *  asset), then installs with the pinned signer. Downgrades are allowed (`pm install -d`). */
    suspend fun installVersion(context: Context, tag: String): String = withContext(Dispatchers.IO) {
        val url = ReleaseCatalog.apkUrl(REPO, tag, APK_MATCH) ?: return@withContext "no APK asset for $tag"
        Log.i(TAG, "self-install ha-paneld tag $tag")
        val r = AppInstaller.install(context, url, AppInstaller.HA_PANELD)
        if (r == "OK") "installing ha-paneld $tag" else r // process restarts on success
    }

    /** The newest release for [channel] → (versionTag without leading v, apk asset URL), or null. */
    fun resolve(channel: String): Pair<String, String>? = runCatching {
        // prerelease → list (newest first); stable → releases/latest (skips prereleases/drafts).
        val api = if (channel == PRERELEASE)
            "https://api.github.com/repos/$REPO/releases?per_page=10"
        else
            "https://api.github.com/repos/$REPO/releases/latest"
        val conn = URL(api).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000; conn.readTimeout = 8_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        if (conn.responseCode != 200) return@runCatching null
        val json = conn.inputStream.bufferedReader().readText()
        // For the list form, the FIRST object is the newest release; its tag + first .apk asset are the
        // first matches in document order — the same regexes work for the single /latest object too.
        val tag = Regex(""""tag_name"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
            ?.removePrefix("v") ?: return@runCatching null
        val apk = Regex(""""browser_download_url"\s*:\s*"([^"]+\.apk)"""").find(json)?.groupValues?.get(1)
            ?: return@runCatching null
        tag to apk
    }.getOrNull()

    /**
     * Update ha-paneld to the newest build on [channel] if it differs from the running version. [force]
     * installs the channel's newest even if not strictly newer (the manual button). Returns a status.
     */
    suspend fun checkAndUpdate(context: Context, channel: String, force: Boolean = false): String =
        withContext(Dispatchers.IO) {
            val (latest, apkUrl) = resolve(channel) ?: return@withContext "no release found ($channel)"
            val current = BuildConfig.VERSION_NAME
            // Auto path: only move when the channel's newest is actually newer (isNewer handles rc order
            // and stable>prerelease), so we never churn or auto-downgrade. The manual button forces it.
            if (!force && !UpdateChecker.isNewer(latest, current)) return@withContext "up to date ($current, $channel)"
            Log.i(TAG, "self-update $current -> $latest ($channel)")
            val r = AppInstaller.install(context, apkUrl, AppInstaller.HA_PANELD)
            if (r == "OK") "updating ha-paneld -> $latest" else r  // process restarts on success
        }
}
