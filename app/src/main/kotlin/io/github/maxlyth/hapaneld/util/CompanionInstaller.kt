package io.github.maxlyth.hapaneld.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import io.github.maxlyth.hapaneld.control.Su
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * HACA (HA Companion App) installer / updater. The panels have no Play Store, so the **minimal**
 * Companion variant never auto-updates — ha-paneld is the only update path. Self-heals a missing or
 * out-of-date Companion by fetching the latest minimal APK from `home-assistant/android` releases and
 * installing it over root.
 *
 * **Security — this is NOT a generic installer.** It installs only a package on the [ALLOWLIST], only
 * from that entry's pinned URL, and only after the downloaded APK is verified to (a) declare the
 * allowlisted package and (b) be signed by the pinned signing cert. A signer/package mismatch is
 * refused — so a MITM / DNS-spoof / compromised-asset APK can't be installed, including on a **fresh**
 * install where Android's same-signer update rule doesn't apply. Root-only (`su pm install`); the
 * future daemon `INSTALL` path must enforce the same allowlist at the daemon layer.
 *
 * Network + `su` — always call OFF the main / MQTT thread.
 */
object CompanionInstaller {
    const val FULL_PKG = "io.homeassistant.companion.android"
    const val MINIMAL_PKG = "io.homeassistant.companion.android.minimal"

    // Official HA Companion signer (DN `O=Home Assistant`) — SHA-256 of the signing certificate.
    private const val HA_COMPANION_CERT_SHA256 = "11194ba809b42ddf0e1a7dec6842a59c7ff1119c5482e95febffd5c6014daa5a"
    // Canonical minimal APK from the latest (non-prerelease) home-assistant/android release.
    private const val MINIMAL_APK_URL =
        "https://github.com/home-assistant/android/releases/latest/download/app-minimal-release.apk"
    private const val TAG = "ha-paneld/haca"

    /** A package ha-paneld is permitted to install: pinned signer + pinned source. */
    private data class Allowed(val pkg: String, val certSha256: String, val url: String)
    private val ALLOWLIST = listOf(
        Allowed(MINIMAL_PKG, HA_COMPANION_CERT_SHA256, MINIMAL_APK_URL),
        // Future auto-installed components (e.g. the WebView auto-heal) pin their own package + signer here.
    )

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
        val entry = ALLOWLIST.first { it.pkg == MINIMAL_PKG }

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
        if (!download(entry.url, apk)) return@withContext "download failed"

        // SECURITY GATE — refuse anything that isn't the allowlisted package signed by the pinned cert.
        val why = verifyApk(context, apk.absolutePath, entry)
        if (why != null) { apk.delete(); Log.w(TAG, "refused install: $why"); return@withContext "refused ($why)" }

        // Stage into /data/local/tmp (world-readable context) so the installer can read it regardless of
        // app-cache SELinux labelling, then install and clean up. `-d` (allow downgrade) is deliberate —
        // future stable<->pre-release channel switching must be able to move between versions either way.
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

    /**
     * Null = the APK is allowlist-valid: it declares [entry].pkg AND is signed by the pinned cert.
     * Otherwise a short reason. Reads the APK as the app uid (it's in our cacheDir), before staging.
     */
    @Suppress("DEPRECATION") // GET_SIGNATURES / PackageInfo.signatures for API < 28
    private fun verifyApk(context: Context, apkPath: String, entry: Allowed): String? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(apkPath, flags) ?: return "unreadable APK"
        if (info.packageName != entry.pkg) return "package ${info.packageName} not allowlisted"
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            info.signingInfo?.apkContentsSigners else info.signatures
        if (sigs.isNullOrEmpty()) return "no signature"
        val md = MessageDigest.getInstance("SHA-256")
        val ok = sigs.any {
            md.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }.equals(entry.certSha256, true)
        }
        return if (ok) null else "signer mismatch"
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
