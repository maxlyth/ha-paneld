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
    private const val MAX_APK_DOWNLOAD_BYTES = 512L * 1024L * 1024L
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

        // Preflight free space BEFORE downloading, so a large APK (a WebView build is ~250 MB) can't
        // fill /data or fail half-written on a low-storage panel. We need room for the download only —
        // the su install streams straight from it (no second /data/local/tmp copy). +64 MB margin.
        val size = contentLength(url)
        if (size > MAX_APK_DOWNLOAD_BYTES) {
            Log.w(TAG, "refusing oversized APK download: $size bytes")
            return@withContext "download too large (${size / 1048576}MB)"
        }
        val margin = 64L * 1024L * 1024L
        val free = context.cacheDir.usableSpace
        val spaceLimit = (free - margin).coerceAtLeast(0L)
        if (size > 0L && size > spaceLimit) {
            val need = size + margin
            Log.w(TAG, "insufficient storage: need ${need / 1048576}MB, have ${free / 1048576}MB free")
            return@withContext "insufficient storage (need ${need / 1048576}MB, ${free / 1048576}MB free)"
        }
        val downloadLimit = minOf(MAX_APK_DOWNLOAD_BYTES, spaceLimit)
        if (downloadLimit == 0L) {
            return@withContext "insufficient storage (64MB safety margin unavailable)"
        }
        val apk = runCatching { File.createTempFile("hapaneld-dl-", ".apk", context.cacheDir) }
            .getOrElse { return@withContext "download staging failed" }
        try {
            if (!download(url, apk, downloadLimit)) return@withContext "download failed"
            val why = verifyApk(context, apk.absolutePath, pin)
            if (why != null) {
                Log.w(TAG, "refused install: $why")
                return@withContext "refused ($why)"
            }
            installLocalApk(context, apk)
        } finally {
            apk.delete()
        }
    }

    /** Metadata read from an APK file (no install) — for the Install-tab "upload an APK" preview. */
    data class ApkInfo(val pkg: String, val version: String, val signerSha256: String?)

    /** Parse an APK's package name, versionName and signer SHA-256 without installing it. Null if the file
     *  isn't a readable APK. Used to show the user WHAT they're about to install before they confirm. */
    @Suppress("DEPRECATION") // GET_SIGNATURES / PackageInfo.signatures for API < 28
    fun inspect(context: Context, apkPath: String): ApkInfo? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(apkPath, flags) ?: return null
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            info.signingInfo?.apkContentsSigners else info.signatures
        val md = MessageDigest.getInstance("SHA-256")
        val sha = sigs?.firstOrNull()?.let { md.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }
        return ApkInfo(info.packageName ?: "?", info.versionName ?: "?", sha)
    }

    /**
     * Install an APK already on local disk over root, then delete it. Shared install tail used by both the
     * pinned-download path ([install]) and the Install-tab APK upload. **No signer pin is applied here** —
     * [install] verifies BEFORE calling this; the upload path installs whatever the user explicitly chose
     * (surfaced package/version/signer + confirmed first). Streams straight into `pm install -S` or over
     * the peer-uid-locked daemon socket; an older daemon falls back to its path-based `INSTALL` verb.
     * Returns "OK" or a short reason.
     */
    suspend fun installLocalApk(context: Context, apk: File): String = withContext(Dispatchers.IO) {
        val hasSu = Su.available()
        val hasDaemon = HelperClient.available()
        if (!hasSu && !hasDaemon) { apk.delete(); return@withContext "skipped: no root (su or helper daemon needed)" }
        if (hasSu) {
            val out = try {
                // Stream the APK straight into `pm install -S <size>` — no intermediate /data/local/tmp copy
                // (halves peak disk use). Long-timeout: staging a large stream far exceeds the 5s su bound.
                Su.runWithStdinLong(
                    "pm install -S ${apk.length()} -r -d 2>&1",
                    apk,
                    HelperInstallTransaction.INSTALL_TIMEOUT_MS,
                )?.trim() ?: ""
            } finally {
                apk.delete()
            }
            if (out.contains("Success", ignoreCase = true)) return@withContext "OK"
            Log.w(TAG, "install failed: $out")
            return@withContext "install failed: ${out.take(120)}"
        }

        val result = HelperInstallTransaction(HelperClient).install(
            apk,
            File(context.filesDir, HelperInstallTransaction.STAGING_DIR),
        )
        if (result != "OK") Log.w(TAG, result)
        result
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
    /** The download's size in bytes from a HEAD (following HTTPS redirects), or -1 if unknown. Used to
     *  preflight free space before committing to a large download. */
    private fun contentLength(url: String): Long = runCatching {
        var current = URL(url).takeIf { it.protocol.equals("https", true) } ?: return -1L
        repeat(5) {
            val conn = (current.openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"; instanceFollowRedirects = false
                connectTimeout = 15_000; readTimeout = 15_000
            }
            try {
                when (conn.responseCode) {
                    in 300..399 -> {
                        val loc = conn.getHeaderField("Location") ?: return -1L
                        current = httpsRedirect(current, loc) ?: return -1L
                    }
                    200 -> return conn.contentLengthLong
                    else -> return -1L
                }
            } finally {
                conn.disconnect()
            }
        }
        -1L
    }.getOrDefault(-1L)

    private fun download(url: String, dest: File, maxBytes: Long): Boolean = runCatching {
        var current = URL(url).takeIf { it.protocol.equals("https", true) }
            ?: run { Log.w(TAG, "refusing non-HTTPS URL"); return false }
        repeat(5) {
            val conn = current.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            try {
                when (conn.responseCode) {
                    in 300..399 -> {
                        val loc = conn.getHeaderField("Location") ?: return false
                        current = httpsRedirect(current, loc)
                            ?: run { Log.w(TAG, "refusing non-HTTPS redirect"); return false }
                    }
                    200 -> {
                        val declared = conn.contentLengthLong
                        if (declared > maxBytes) {
                            Log.w(TAG, "refusing oversized APK response: $declared bytes")
                            return false
                        }
                        conn.inputStream.use { input ->
                            dest.outputStream().use { output ->
                                BoundedStreams.copy(input, output, maxBytes)
                            }
                        }
                        return dest.length() > 0
                    }
                    else -> return false
                }
            } finally {
                conn.disconnect()
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
