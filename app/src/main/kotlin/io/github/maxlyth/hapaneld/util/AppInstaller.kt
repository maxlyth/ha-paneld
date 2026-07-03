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
 * Shared root APK installer with a **pinned signer + package allowlist**. This is NOT a generic
 * installer: an install proceeds only if the downloaded APK declares the pinned package AND is signed
 * by the pinned certificate — a package/signer mismatch is refused, so a MITM / DNS-spoof / compromised
 * asset can't be installed even on a fresh (no-incumbent) install. Used by both the HA Companion app
 * updater and ha-paneld's own self-update.
 *
 * Install path: `su` directly, else the peer-uid-locked helper `INSTALL` verb. `pm install -r -d` — the
 * `-d` (allow downgrade) is deliberate, so a stable<->pre-release channel switch can move either way.
 * Network + su — always call OFF the main / MQTT thread.
 */
object AppInstaller {
    data class Pin(val pkg: String, val certSha256: String)

    // Pinned signers (public certificate fingerprints — NOT secrets).
    val HA_PANELD = Pin("io.github.maxlyth.hapaneld", "ac6193307fb0b70113aae205d7549406f96e063bc5491b67b1d5694a34b0e339")
    val COMPANION_MINIMAL = Pin("io.homeassistant.companion.android.minimal", "11194ba809b42ddf0e1a7dec6842a59c7ff1119c5482e95febffd5c6014daa5a")

    private const val TAG = "ha-paneld/install"
    // Staging + `pm install` of a large APK (a WebView build ~250 MB) needs far longer than the default
    // 5s su bound. 3 min covers a slow-eMMC copy + install with margin.
    private const val INSTALL_TIMEOUT_MS = 180_000L

    fun installedVersion(context: Context, pkg: String): String =
        runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName ?: "" }.getOrElse { "" }

    /**
     * Download [url], refuse unless the APK declares [pin].pkg AND is signed by [pin].certSha256, then
     * install over root. Returns "OK" on success, else a short reason. The caller owns the
     * version/should-update decision.
     */
    suspend fun install(context: Context, url: String, pin: Pin): String = withContext(Dispatchers.IO) {
        val hasSu = Su.available()
        val hasDaemon = HelperClient.available()
        if (!hasSu && !hasDaemon) return@withContext "skipped: no root (su or helper daemon needed)"

        val apk = File(context.cacheDir, "hapaneld-dl.apk")
        if (!download(url, apk)) return@withContext "download failed"

        val why = verifyApk(context, apk.absolutePath, pin)
        if (why != null) { apk.delete(); Log.w(TAG, "refused install: $why"); return@withContext "refused ($why)" }

        val out = try {
            if (hasSu) {
                val staged = "/data/local/tmp/hapaneld-dl.apk"
                try {
                    // Long-timeout su: staging + installing a large APK (a WebView build is ~250 MB)
                    // far exceeds the default 5s su bound. Always one-shot, so it can't wedge the shell.
                    if (!Su.runLong("cp '${apk.absolutePath}' $staged && chmod 644 $staged", INSTALL_TIMEOUT_MS)) "stage failed"
                    else Su.runOutputLong("pm install -r -d $staged 2>&1", INSTALL_TIMEOUT_MS)?.trim() ?: ""
                } finally { runCatching { Su.run("rm -f $staged") } }
            } else {
                // Daemon reads the verified APK from our data dir; send() is synchronous so the delete
                // below is safe. OK → success.
                when (HelperClient.send("INSTALL ${apk.absolutePath}")) {
                    "OK" -> "Success"
                    null -> "daemon unreachable"
                    else -> "daemon install failed"
                }
            }
        } finally { apk.delete() }

        if (out.contains("Success", ignoreCase = true)) "OK"
        else { Log.w(TAG, "install failed: $out"); "install failed: ${out.take(120)}" }
    }

    /** Null = APK declares [pin].pkg AND is signed by the pinned cert; else a short reason. */
    @Suppress("DEPRECATION") // GET_SIGNATURES / PackageInfo.signatures for API < 28
    private fun verifyApk(context: Context, apkPath: String, pin: Pin): String? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(apkPath, flags) ?: return "unreadable APK"
        if (info.packageName != pin.pkg) return "package ${info.packageName} not allowlisted"
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            info.signingInfo?.apkContentsSigners else info.signatures
        if (sigs.isNullOrEmpty()) return "no signature"
        val md = MessageDigest.getInstance("SHA-256")
        val ok = sigs.any {
            md.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) }.equals(pin.certSha256, true)
        }
        return if (ok) null else "signer mismatch"
    }

    /**
     * Download [url] to [dest], following redirects (GitHub release → CDN). True on success.
     *
     * HTTPS-only for every hop: the initial URL and each redirect target must be `https`, else the
     * fetch is refused. The APK is signer-pinned after download ([verifyApk]), so a substituted blob
     * still can't install — but refusing plaintext hops closes the residual downgrade (an
     * `https→http` redirect would otherwise fetch the update over cleartext, leaking the request and
     * letting a network attacker waste the download before the pin rejects it). All real callers use
     * GitHub `https` release URLs that redirect to `https` CDNs, so this rejects nothing legitimate.
     */
    private fun download(url: String, dest: File): Boolean = runCatching {
        var current = URL(url).takeIf { it.protocol.equals("https", true) }
            ?: run { Log.w(TAG, "refusing non-HTTPS URL"); return false }
        repeat(5) {
            val conn = current.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            when (conn.responseCode) {
                in 300..399 -> {
                    val loc = conn.getHeaderField("Location") ?: return false
                    conn.disconnect()
                    current = httpsRedirect(current, loc) ?: run { Log.w(TAG, "refusing non-HTTPS redirect"); return false }
                }
                200 -> {
                    conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
                    return dest.length() > 0
                }
                else -> return false
            }
        }
        false
    }.getOrElse { Log.w(TAG, "download error", it); false }

    /**
     * Resolve a redirect [location] (absolute or relative) against [base] and return it **only** if the
     * result is HTTPS; null means "refuse" (non-HTTPS target, or unparseable). Resolving against [base]
     * also handles relative `Location` headers, which the previous `URL(location)` mishandled.
     */
    internal fun httpsRedirect(base: URL, location: String): URL? =
        runCatching { URL(base, location) }.getOrNull()?.takeIf { it.protocol.equals("https", true) }
}
