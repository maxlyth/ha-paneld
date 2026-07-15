package io.github.maxlyth.hapaneld.util

import android.content.Context
import android.util.Log
import io.github.maxlyth.hapaneld.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    private val APK_MATCH: (String) -> Boolean = { it.endsWith(".apk", ignoreCase = true) }

    /** Up to [limit] recent versions on [channel] for the Install-tab picker (version + release-notes URL). */
    fun versions(channel: String, limit: Int = 10): List<ReleaseCatalog.Version> =
        ReleaseCatalog.list(REPO, channel, limit, APK_MATCH) { it.removePrefix("v") }

    /** Install a specific ha-paneld release by its [tag]. The tag is validated and resolved back through
     *  the fixed repository before the package/signer-pinned installer sees its asset. */
    suspend fun installVersion(context: Context, tag: String): String = withContext(Dispatchers.IO) {
        val url = ReleaseCatalog.apkUrl(REPO, tag, APK_MATCH) ?: return@withContext "no APK asset for $tag"
        Log.i(TAG, "self-install ha-paneld tag $tag")
        val result = AppInstaller.install(context, url, AppInstaller.HA_PANELD, allowShizuku = true)
        if (result == "OK") "installing ha-paneld $tag" else result
    }

    /** The newest release for [channel] as one coherent version/asset pair, or null. */
    fun resolve(channel: String): Pair<String, String>? =
        ReleaseCatalog.newestApk(REPO, channel, APK_MATCH) { it.removePrefix("v") }

    /** Update ha-paneld to the newest build on [channel] if it is newer. [force] installs the channel's
     *  newest even when equal or older, which is the deliberate manual/channel-switch downgrade path. */
    suspend fun checkAndUpdate(context: Context, channel: String, force: Boolean = false): String =
        withContext(Dispatchers.IO) {
            val (latest, apkUrl) = resolve(channel) ?: return@withContext "no release found ($channel)"
            val current = BuildConfig.VERSION_NAME
            if (!force && !UpdateChecker.isNewer(latest, current)) return@withContext "up to date ($current, $channel)"
            Log.i(TAG, "self-update $current -> $latest ($channel)")
            val result = AppInstaller.install(context, apkUrl, AppInstaller.HA_PANELD, allowShizuku = true)
            if (result == "OK") "updating ha-paneld -> $latest" else result
        }
}
