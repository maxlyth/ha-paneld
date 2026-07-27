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
    private const val RELEASES_URL = "https://github.com/maxlyth/ha-paneld/releases"
    private val APK_MATCH: (String) -> Boolean = { it.endsWith(".apk", ignoreCase = true) }

    /** Up to [limit] recent versions on [channel] for the Install-tab picker (version + release-notes URL). */
    fun versions(channel: String, limit: Int = 10): List<ReleaseCatalog.Version> =
        ReleaseCatalog.list(REPO, channel, limit, APK_MATCH) { it.removePrefix("v") }

    /** SQLite-config boundary. From v0.9.4 the panel's entire config lives in a database (app_state); a
     *  build older than this cannot read that database, so installing below it would strand the config. */
    const val MIN_DOWNGRADE_VERSION = "0.9.4"

    /** True when installing [candidateVersion] from [currentVersion] would cross below the config-store
     *  boundary ([MIN_DOWNGRADE_VERSION]). An unparseable version is treated as on-boundary (not blocked). Pure. */
    fun crossesConfigFloor(candidateVersion: String, currentVersion: String): Boolean =
        (UpdateChecker.compareVersions(currentVersion, MIN_DOWNGRADE_VERSION) ?: 0) >= 0 &&
            (UpdateChecker.compareVersions(candidateVersion, MIN_DOWNGRADE_VERSION) ?: 0) < 0

    /** Install a specific ha-paneld release by its [tag]. The tag is validated and resolved back through
     *  the fixed repository before the package/signer-pinned installer sees its asset. */
    suspend fun installVersion(context: Context, tag: String): String = withContext(Dispatchers.IO) {
        // Never let a manual/tag install cross below the config-store boundary and strand the panel's
        // config in a database the older build cannot read.
        if (crossesConfigFloor(tag.removePrefix("v"), BuildConfig.VERSION_NAME)) {
            Log.w(TAG, "refusing $tag — below the config-store floor $MIN_DOWNGRADE_VERSION")
            return@withContext "refused: $tag predates $MIN_DOWNGRADE_VERSION and cannot read this panel's config store"
        }
        val url = ReleaseCatalog.apkUrl(REPO, tag, APK_MATCH) ?: return@withContext "no APK asset for $tag"
        Log.i(TAG, "self-install ha-paneld tag $tag")
        when (val outcome = AppInstaller.install(context, url, AppInstaller.HA_PANELD, allowShizuku = true)) {
            InstallOutcome.Succeeded -> "installing ha-paneld $tag"
            is InstallOutcome.Failure -> outcome.message
        }
    }

    /** The newest release for [channel] as one coherent target (version + APK URL + release-notes URL), or
     *  null. Feeds the shared [ComponentUpdater] resolve -> compare -> decide pipeline. */
    fun resolveTarget(channel: String): ComponentUpdater.Target? =
        ReleaseCatalog.newestApk(REPO, channel, APK_MATCH) { it.removePrefix("v") }
            ?.let { (version, apkUrl) -> ComponentUpdater.Target(version, apkUrl, RELEASES_URL) }

    /** Update ha-paneld to the newest build on [channel] if it is newer. [force] installs the channel's
     *  newest even when equal or older, which is the deliberate manual/channel-switch downgrade path. */
    suspend fun checkAndUpdate(context: Context, channel: String, force: Boolean = false): String =
        withContext(Dispatchers.IO) {
            val current = BuildConfig.VERSION_NAME
            when (val outcome = ComponentUpdater.resolveUpdate(current, force) { resolveTarget(channel) }) {
                ComponentUpdater.Outcome.Unresolved -> "no release found ($channel)"
                ComponentUpdater.Outcome.UpToDate -> "up to date ($current, $channel)"
                is ComponentUpdater.Outcome.Update -> {
                    val latest = outcome.target.version
                    Log.i(TAG, "self-update $current -> $latest ($channel)")
                    when (val install = AppInstaller.install(context, outcome.target.apkUrl, AppInstaller.HA_PANELD, allowShizuku = true)) {
                        InstallOutcome.Succeeded -> "updating ha-paneld -> $latest"
                        is InstallOutcome.Failure -> install.message
                    }
                }
            }
        }
}
