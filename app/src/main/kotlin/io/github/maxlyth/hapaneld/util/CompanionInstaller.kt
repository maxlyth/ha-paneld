package io.github.maxlyth.hapaneld.util

import android.content.Context
import android.util.Log
import io.github.maxlyth.hapaneld.device.DeviceProfile
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
    val SUPPORTED_PACKAGES = listOf(FULL_PKG, MINIMAL_PKG)

    private const val TAG = "ha-paneld/companion"
    private const val REPO = "home-assistant/android"
    private val APK_MATCH: (String) -> Boolean = { it == "app-minimal-release.apk" }

    /** The installed Companion package (full or minimal), or null if neither is present. */
    fun installedPkg(context: Context): String? =
        SUPPORTED_PACKAGES.firstOrNull {
            runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess
        }

    fun installedPackages(context: Context): Set<String> =
        SUPPORTED_PACKAGES.filterTo(linkedSetOf()) {
            runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess
        }

    /** Up to [limit] recent versions on [channel] for the Install-tab picker. Releases above this
     *  device's safety cap remain visible for their notes but are not installable. */
    fun versions(
        channel: String,
        limit: Int = 10,
        maxVersion: String? = DeviceProfile.detect().companionMaxVersion,
    ): List<ReleaseCatalog.Version> = applyCap(catalog(channel, limit), maxVersion)

    /** A release chosen for installation. [newestVersion] remains the channel head even when [version]
     *  is an older, profile-capped target, so callers can explain why the pin applied. */
    internal data class Target(
        val version: String,
        val apkUrl: String,
        val releaseUrl: String,
        val newestVersion: String,
        val capped: Boolean,
    )

    private fun catalog(channel: String, limit: Int): List<ReleaseCatalog.Version> =
        ReleaseCatalog.list(REPO, channel, limit, APK_MATCH) { UpdateChecker.stripVariant(it.removePrefix("v")) }

    /** Mark releases above a device's safety ceiling as non-installable while retaining their notes. */
    internal fun applyCap(
        versions: List<ReleaseCatalog.Version>,
        maxVersion: String?,
    ): List<ReleaseCatalog.Version> = if (maxVersion == null) versions else versions.map { version ->
        if (withinCap(version.version, maxVersion)) version else version.copy(installable = false, apkUrl = null)
    }

    /** Pick one coherent release/asset target. If the channel head is within the cap but lacks its APK,
     *  fail rather than silently falling back. When the head exceeds the cap, select the newest complete
     *  release at or below it. */
    internal fun chooseTarget(versions: List<ReleaseCatalog.Version>, maxVersion: String?): Target? {
        val newest = versions.firstOrNull() ?: return null
        val capped = maxVersion != null && !withinCap(newest.version, maxVersion)
        val selected = if (!capped) {
            newest.takeIf { it.apkUrl != null }
        } else {
            versions.firstOrNull { withinCap(it.version, maxVersion) && it.apkUrl != null }
        } ?: return null
        return Target(
            version = selected.version,
            apkUrl = selected.apkUrl!!,
            releaseUrl = selected.notesUrl,
            newestVersion = newest.version,
            capped = capped,
        )
    }

    /** Resolve the channel's installable target under [maxVersion]. One structured catalog object owns
     *  each tag, notes URL and APK URL, so selection cannot cross release boundaries. */
    internal fun target(channel: String, maxVersion: String?): Target? =
        chooseTarget(catalog(channel, 40), maxVersion)

    /** Install a specific Companion release by its [tag]. The exact-version picker obeys the same device
     *  cap as automatic and newest-version installs; the cap is a safety boundary, not a preference. */
    suspend fun installVersion(
        context: Context,
        tag: String,
        maxVersion: String? = DeviceProfile.detect().companionMaxVersion,
    ): String = withContext(Dispatchers.IO) {
        if (AppInstaller.installedVersion(context, FULL_PKG).isNotBlank())
            return@withContext "skipped: full Companion present (Play-managed)"
        val version = UpdateChecker.stripVariant(tag.removePrefix("v"))
        exactVersionRefusal(tag, maxVersion)?.let { return@withContext it }
        val url = ReleaseCatalog.apkUrl(REPO, tag, APK_MATCH) ?: return@withContext "no minimal APK for $tag"
        Log.i(TAG, "install Companion tag $tag")
        val result = AppInstaller.install(context, url, AppInstaller.COMPANION_MINIMAL)
        if (result != "OK") return@withContext result
        "installing HA Companion $version"
    }

    /** True when [latestVersion] cannot prove it is at or below [maxVersion]. Null cap = no ceiling. */
    internal fun exceedsCap(latestVersion: String, maxVersion: String?): Boolean =
        maxVersion != null && !withinCap(latestVersion, maxVersion)

    /** A malformed release version cannot prove itself below a safety cap, so capped panels fail closed. */
    internal fun withinCap(version: String, maxVersion: String?): Boolean =
        maxVersion == null || UpdateChecker.compareVersions(UpdateChecker.stripVariant(version), maxVersion)
            ?.let { it <= 0 } == true

    /** Pure exact-picker cap gate used before any release lookup or download. */
    internal fun exactVersionRefusal(tag: String, maxVersion: String?): String? {
        val version = UpdateChecker.stripVariant(tag.removePrefix("v"))
        return if (withinCap(version, maxVersion)) null
        else "refused: HA Companion $version exceeds this panel's $maxVersion safety cap"
    }

    /** Pure install decision. An installed build above the profile ceiling must be remediated even though
     *  the safe target is numerically older; ordinary automatic updates never downgrade. */
    internal fun shouldInstallTarget(installed: String, targetVersion: String, force: Boolean, maxVersion: String?): Boolean =
        installed.isBlank() ||
            force ||
            exceedsCap(installed, maxVersion) ||
            UpdateChecker.isNewer(targetVersion, UpdateChecker.stripVariant(installed))

    /** Install the minimal Companion if missing, update it when newer, or downgrade an already-unsafe
     *  installed build to the newest target inside the device cap. [force] skips only the up-to-date gate;
     *  it never bypasses [maxVersion]. */
    suspend fun installOrUpdate(
        context: Context,
        force: Boolean = false,
        channel: String = "stable",
        maxVersion: String? = DeviceProfile.detect().companionMaxVersion,
    ): String = withContext(Dispatchers.IO) {
        if (AppInstaller.installedVersion(context, FULL_PKG).isNotBlank())
            return@withContext "skipped: full Companion present (Play-managed)"

        val installed = AppInstaller.installedVersion(context, MINIMAL_PKG)
        val missing = installed.isBlank()
        val target = target(channel, maxVersion) ?: return@withContext "no installable release found ($channel)"
        val installedAboveCap = !missing && exceedsCap(installed, maxVersion)

        if (!shouldInstallTarget(installed, target.version, force, maxVersion)) {
            return@withContext if (target.capped)
                "pinned at $installed (latest ${target.newestVersion} exceeds this panel's $maxVersion cap)"
            else "up to date ($installed)"
        }

        val result = AppInstaller.install(context, target.apkUrl, AppInstaller.COMPANION_MINIMAL)
        if (result != "OK") return@withContext result
        val now = AppInstaller.installedVersion(context, MINIMAL_PKG).ifBlank { target.version }
        val verb = when {
            missing -> "installed"
            installedAboveCap -> "downgraded"
            else -> "updated"
        }
        val capApplied = target.capped || installedAboveCap
        Log.i(TAG, "Companion $verb -> $now${if (capApplied) " (capped at $maxVersion)" else ""}")
        "$verb HA Companion app ($now)${if (capApplied) " — pinned to the $maxVersion cap for this panel" else ""}"
    }
}
