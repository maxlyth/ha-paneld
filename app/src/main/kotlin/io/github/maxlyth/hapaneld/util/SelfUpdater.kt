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

    internal sealed interface ChannelPreparation {
        data class Unresolved(val message: String) : ChannelPreparation
        data class UpToDate(val message: String) : ChannelPreparation
        data class Refused(val message: String) : ChannelPreparation
        data class Ready(
            val prepared: AppInstaller.PreparedSelfInstall,
            val message: String,
        ) : ChannelPreparation {
            val databaseDisposition: AppInstaller.SelfInstallDatabaseDisposition
                get() = prepared.databaseDisposition
        }
    }

    /** Install a specific ha-paneld release by its [tag]. The tag is validated and resolved back through
     *  the fixed repository before the package/signer-pinned installer sees its asset. */
    suspend fun installVersion(context: Context, tag: String): String = withContext(Dispatchers.IO) {
        val url = ReleaseCatalog.apkUrl(REPO, tag, APK_MATCH) ?: return@withContext "no APK asset for $tag"
        Log.i(TAG, "self-install ha-paneld tag $tag")
        when (val preparation = AppInstaller.prepareSelfInstall(context, url)) {
            is AppInstaller.SelfInstallPreparation.Failed -> preparation.outcome.message
            is AppInstaller.SelfInstallPreparation.Ready -> preparation.prepared.use { prepared ->
                when (val outcome = AppInstaller.installPrepared(context, prepared)) {
                    InstallOutcome.Succeeded -> "installing ha-paneld $tag"
                    is InstallOutcome.Failure -> outcome.message
                }
            }
        }
    }

    /** The newest release for [channel] as one coherent target (version + APK URL + release-notes URL), or
     *  null. Feeds the shared [ComponentUpdater] resolve -> compare -> decide pipeline. */
    fun resolveTarget(channel: String): ComponentUpdater.Target? =
        ReleaseCatalog.newestApk(REPO, channel, APK_MATCH) { it.removePrefix("v") }
            ?.let { (version, apkUrl) -> ComponentUpdater.Target(version, apkUrl, RELEASES_URL) }

    /**
     * Resolve, download, authenticate and database-admit one exact channel candidate without mutating
     * configuration or installing it. A [ChannelPreparation.Ready] owns the admitted bytes until its
     * prepared capability is installed or closed by the caller.
     */
    internal suspend fun prepareChannelUpdate(
        context: Context,
        channel: String,
        force: Boolean = false,
    ): ChannelPreparation = withContext(Dispatchers.IO) {
        val current = BuildConfig.VERSION_NAME
        when (val outcome = ComponentUpdater.resolveUpdate(current, force) { resolveTarget(channel) }) {
            ComponentUpdater.Outcome.Unresolved -> ChannelPreparation.Unresolved("no release found ($channel)")
            ComponentUpdater.Outcome.UpToDate -> ChannelPreparation.UpToDate("up to date ($current, $channel)")
            is ComponentUpdater.Outcome.Update -> {
                val target = outcome.target
                when (val preparation = AppInstaller.prepareSelfInstall(context, target.apkUrl)) {
                    is AppInstaller.SelfInstallPreparation.Failed ->
                        ChannelPreparation.Refused(preparation.outcome.message)
                    is AppInstaller.SelfInstallPreparation.Ready -> ChannelPreparation.Ready(
                        preparation.prepared,
                        "updating ha-paneld -> ${target.version}",
                    )
                }
            }
        }
    }

    /**
     * A database recovery replaces the live database with its pre-migration snapshot. It is safe for a
     * package-only update, but a config-coupled channel transaction would write its new channel (and any
     * mixed settings) into the database that recovery then discards. Reject and destroy that capability
     * before the caller can commit configuration; exact/manual/periodic package-only callers intentionally
     * do not pass through this adapter.
     */
    internal fun admitConfigCoupledChannel(preparation: ChannelPreparation): ChannelPreparation =
        if (preparation is ChannelPreparation.Ready &&
            preparation.databaseDisposition == AppInstaller.SelfInstallDatabaseDisposition.RECOVER
        ) {
            preparation.prepared.close()
            ChannelPreparation.Refused(
                "An update-channel change cannot recover an older database snapshot.",
            )
        } else preparation.also {
            if (it is ChannelPreparation.Ready) it.prepared.restrictToDirectConsumption()
        }

    internal data class PreparedInstallOutcome(val message: String, val installed: Boolean)

    internal suspend fun installPreparedOutcome(
        context: Context,
        prepared: AppInstaller.PreparedSelfInstall,
    ): PreparedInstallOutcome = when (val outcome = AppInstaller.installPrepared(context, prepared)) {
        InstallOutcome.Succeeded -> PreparedInstallOutcome(
            "updating ha-paneld -> ${prepared.version}",
            installed = true,
        )
        is InstallOutcome.Failure -> PreparedInstallOutcome(outcome.message, installed = false)
    }

    /** Consume a previously admitted exact channel candidate without resolving or observing it again. */
    internal suspend fun installPrepared(
        context: Context,
        prepared: AppInstaller.PreparedSelfInstall,
    ): String = installPreparedOutcome(context, prepared).message

    /** Update ha-paneld to the newest build on [channel] if it is newer. [force] installs the channel's
     *  newest even when equal or older, which is the deliberate manual/channel-switch downgrade path. */
    suspend fun checkAndUpdate(context: Context, channel: String, force: Boolean = false): String =
        withContext(Dispatchers.IO) {
            when (val preparation = prepareChannelUpdate(context, channel, force)) {
                is ChannelPreparation.Unresolved -> preparation.message
                is ChannelPreparation.UpToDate -> preparation.message
                is ChannelPreparation.Refused -> preparation.message
                is ChannelPreparation.Ready -> preparation.prepared.use { prepared ->
                    Log.i(TAG, "self-update ${BuildConfig.VERSION_NAME} -> ${prepared.version} ($channel)")
                    installPrepared(context, prepared)
                }
            }
        }
}
