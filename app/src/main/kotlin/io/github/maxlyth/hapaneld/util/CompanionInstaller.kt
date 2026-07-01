package io.github.maxlyth.hapaneld.util

import android.content.Context
import android.util.Log
import io.github.maxlyth.hapaneld.control.Su
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * HACA (HA Companion App) installer / updater. The panels have no Play Store, so the **minimal**
 * Companion variant never auto-updates — ha-paneld is the only update path. Self-heals a missing or
 * out-of-date Companion by fetching the latest minimal APK from `home-assistant/android` releases and
 * installing it over root.
 *
 * Root-only (uses `su` `pm install`). No-ops with a status string when: the Play-managed **full**
 * variant is present (Play owns it), the Companion is already current, or there's no `su`. Network +
 * `su` — always call OFF the main / MQTT thread.
 */
object CompanionInstaller {
    const val FULL_PKG = "io.homeassistant.companion.android"
    const val MINIMAL_PKG = "io.homeassistant.companion.android.minimal"

    // Canonical minimal APK from the latest (non-prerelease) home-assistant/android release.
    private const val MINIMAL_APK_URL =
        "https://github.com/home-assistant/android/releases/latest/download/app-minimal-release.apk"
    private const val TAG = "ha-paneld/haca"

    /** The installed Companion package (full or minimal), or null if neither is present. */
    fun installedPkg(context: Context): String? =
        listOf(FULL_PKG, MINIMAL_PKG).firstOrNull {
            runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess
        }

    private fun versionOf(context: Context, pkg: String): String =
        runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName ?: "" }.getOrElse { "" }

    /**
     * Install the minimal HACA if missing, or update it if a newer release exists. [force] installs the
     * latest regardless of the version check (the manual-button path). Returns a short human status.
     */
    suspend fun installOrUpdate(context: Context, force: Boolean = false): String = withContext(Dispatchers.IO) {
        // The Play-managed full variant owns its own updates — never fight it.
        if (versionOf(context, FULL_PKG).isNotBlank()) return@withContext "skipped: full Companion present (Play-managed)"

        val installed = versionOf(context, MINIMAL_PKG)
        val missing = installed.isBlank()

        // Skip the (large) download when we already know we're current — unless forced or missing.
        if (!missing && !force) {
            val latest = UpdateChecker.fetchLatest("home-assistant/android")?.first
                ?.removePrefix("v")?.let { Regex("-(?:full|minimal|wear)$").replace(it, "") }
            if (latest != null && !UpdateChecker.isNewer(latest, installed)) {
                return@withContext "up to date ($installed)"
            }
        }
        if (!Su.available()) return@withContext "skipped: no root (su needed to install)"

        val apk = File(context.cacheDir, "haca-minimal.apk")
        if (!download(MINIMAL_APK_URL, apk)) return@withContext "download failed"

        // Stage into /data/local/tmp (world-readable context) so the installer can read it regardless of
        // app-cache SELinux labelling, then install and clean up.
        val staged = "/data/local/tmp/haca-minimal.apk"
        val out = try {
            if (!Su.run("cp '${apk.absolutePath}' $staged && chmod 644 $staged")) return@withContext "stage failed"
            Su.runOutput("pm install -r -d $staged 2>&1")?.trim() ?: ""
        } finally {
            runCatching { Su.run("rm -f $staged") }
            apk.delete()
        }

        return@withContext if (out.contains("Success", ignoreCase = true)) {
            val now = versionOf(context, MINIMAL_PKG)
            val verb = if (missing) "installed" else "updated"
            Log.i(TAG, "HACA $verb -> $now")
            "$verb HACA ($now)"
        } else {
            Log.w(TAG, "HACA install failed: $out")
            "install failed: ${out.take(140)}"
        }
    }

    /** Download [url] to [dest], following redirects (GitHub release → CDN). True on success. */
    private fun download(url: String, dest: File): Boolean = runCatching {
        var current = url
        repeat(5) { // follow up to 5 redirects manually (cross-host https redirects)
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            when (conn.responseCode) {
                in 300..399 -> { current = conn.getHeaderField("Location") ?: return false; conn.disconnect() }
                200 -> {
                    conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
                    return dest.length() > 0
                }
                else -> return false
            }
        }
        false
    }.getOrElse { Log.w(TAG, "download error", it); false }
}
