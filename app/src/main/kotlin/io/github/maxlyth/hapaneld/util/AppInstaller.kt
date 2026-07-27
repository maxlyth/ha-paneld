package io.github.maxlyth.hapaneld.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import io.github.maxlyth.hapaneld.control.Su
import io.github.maxlyth.hapaneld.persistence.AppState
import io.github.maxlyth.hapaneld.shizuku.ShizukuBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Shared privileged APK installer with a **pinned signer + package allowlist**. This is NOT a generic
 * installer: an install proceeds only if the downloaded APK declares the pinned package AND is signed
 * by the pinned certificate — a package/signer mismatch is refused, so a MITM / DNS-spoof / compromised
 * asset can't be installed even on a fresh (no-incumbent) install. Used by both the HA Companion app
 * updater and ha-paneld's own self-update.
 *
 * Install path is selected once from currently available authorities: `su` first, then the
 * peer-uid-locked helper `INSTALL` verb, then (only for explicitly allowed curated packages) the typed
 * Shizuku shell-UID service. An attempted install is never replayed through a second authority because
 * a timed-out package-manager transaction can still have committed. Arbitrary uploads and the System
 * WebView never allow Shizuku. `pm install -r -d` — the
 * `-d` (allow downgrade) is deliberate, so a stable<->pre-release channel switch can move either way.
 * Network + package installation — always call OFF the main / MQTT thread.
 */
object AppInstaller {
    data class Pin(val pkg: String, val certSha256: String, val apkSha256: String? = null)
    internal enum class InstallRoute { SU, DAEMON, SHIZUKU, NONE }

    // Pinned signers (public certificate fingerprints — NOT secrets).
    val HA_PANELD = Pin("io.github.maxlyth.hapaneld", "ac6193307fb0b70113aae205d7549406f96e063bc5491b67b1d5694a34b0e339")
    val COMPANION_MINIMAL = Pin("io.homeassistant.companion.android.minimal", "11194ba809b42ddf0e1a7dec6842a59c7ff1119c5482e95febffd5c6014daa5a")

    private const val TAG = "ha-paneld/install"
    private const val MAX_APK_DOWNLOAD_BYTES = 512L * 1024L * 1024L
    private const val SELF_REPLACE_STATE_FLUSH_MS = 10_000L
    fun installedVersion(context: Context, pkg: String): String =
        runCatching { context.packageManager.getPackageInfo(pkg, 0).versionName ?: "" }.getOrElse { "" }

    /**
     * Download [url], refuse unless the APK declares [pin].pkg AND is signed by [pin].certSha256, then
     * install over an available privileged route. Returns a typed [InstallOutcome] — [InstallOutcome.Succeeded]
     * or a [InstallOutcome.Failure] carrying the exact status message. The caller owns the
     * version/should-update decision.
     */
    suspend fun install(
        context: Context,
        url: String,
        pin: Pin,
        allowShizuku: Boolean = false,
    ): InstallOutcome = withContext(Dispatchers.IO) {
        val hasSu = Su.available()
        val hasDaemon = HelperClient.available()
        val hasShizuku = ShizukuBridge.available()
        if (selectInstallRoute(hasSu, hasDaemon, hasShizuku, allowShizuku) == InstallRoute.NONE)
            return@withContext InstallOutcome.Retryable("skipped: no permitted installer")

        // Preflight free space BEFORE downloading, so a large APK (a WebView build is ~250 MB) can't
        // fill /data or fail half-written on a low-storage panel. We need room for the download only —
        // the su install streams straight from it (no second /data/local/tmp copy). +64 MB margin.
        val size = contentLength(url)
        if (size > MAX_APK_DOWNLOAD_BYTES) {
            Log.w(TAG, "refusing oversized APK download: $size bytes")
            return@withContext InstallOutcome.Retryable("download too large (${size / 1048576} MB)")
        }
        val margin = 64L * 1024L * 1024L
        val free = context.cacheDir.usableSpace
        val spaceLimit = (free - margin).coerceAtLeast(0L)
        if (size > 0L && size > spaceLimit) {
            val need = size + margin
            Log.w(TAG, "insufficient storage: need ${need / 1048576} MB, have ${free / 1048576} MB free")
            return@withContext InstallOutcome.Retryable(
                "insufficient storage (need ${need / 1048576} MB, ${free / 1048576} MB free)",
            )
        }
        val downloadLimit = minOf(MAX_APK_DOWNLOAD_BYTES, spaceLimit)
        if (downloadLimit == 0L) {
            return@withContext InstallOutcome.Retryable("insufficient storage (64 MB safety margin unavailable)")
        }
        val apk = runCatching { File.createTempFile("hapaneld-dl-", ".apk", context.cacheDir) }
            .getOrElse { return@withContext InstallOutcome.Retryable("download staging failed") }
        withStagedFiles { staged ->
            staged.stage(apk)
            if (!download(url, apk, downloadLimit)) return@withStagedFiles InstallOutcome.Retryable("download failed")
            val why = verifyApk(context, apk.absolutePath, pin)
            if (why != null) {
                Log.w(TAG, "refused install: $why")
                return@withStagedFiles InstallOutcome.Rejected("refused ($why)")
            }
            installLocalApk(context, apk, allowShizuku)
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
     * Install an APK already on local disk through one permitted route, then delete it. Shared install tail used by both the
     * pinned-download path ([install]) and the Install-tab APK upload. **No signer pin is applied here** —
     * [install] verifies BEFORE calling this; the upload path installs whatever the user explicitly chose
     * (surfaced package/version/signer + confirmed first). Streams straight into `pm install -S` or over
     * the peer-uid-locked daemon socket; an older daemon falls back to its path-based `INSTALL` verb.
     * Shizuku is considered only when [allowShizuku] is explicitly true; arbitrary upload callers keep
     * the default false.
     * Returns a typed [InstallOutcome].
     */
    suspend fun installLocalApk(
        context: Context,
        apk: File,
        allowShizuku: Boolean = false,
    ): InstallOutcome = withContext(Dispatchers.IO) {
        val hasSu = Su.available()
        val hasDaemon = HelperClient.available()
        val hasShizuku = ShizukuBridge.available()
        val route = selectInstallRoute(hasSu, hasDaemon, hasShizuku, allowShizuku)
        if (route == InstallRoute.NONE) {
            apk.delete()
            return@withContext InstallOutcome.Retryable("skipped: no permitted installer")
        }
        val replacingSelf = inspect(context, apk.absolutePath)?.pkg == context.packageName
        if (replacingSelf && !ConfigUpgradeBackup.snapshot(context)) {
            apk.delete()
            return@withContext InstallOutcome.Retryable(
                "install deferred: configuration backup could not be written",
            )
        }
        val stateQuiescence = if (replacingSelf) {
            AppState.quiesceForSelfReplace(context, SELF_REPLACE_STATE_FLUSH_MS)
        } else null
        if (replacingSelf && stateQuiescence == null) {
            apk.delete()
            return@withContext InstallOutcome.Retryable("install deferred: application state is still being saved")
        }
        var installSucceeded = false
        try {
            if (route == InstallRoute.SU) {
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
                if (out.contains("Success", ignoreCase = true)) {
                    installSucceeded = true
                    return@withContext InstallOutcome.Succeeded
                }
                Log.w(TAG, "install failed: $out")
                return@withContext installFailure(out)
            }

            val outcome: InstallOutcome = if (route == InstallRoute.DAEMON) {
                HelperInstallTransaction(HelperClient).install(
                    apk,
                    File(context.filesDir, HelperInstallTransaction.STAGING_DIR),
                )
            } else if (route == InstallRoute.SHIZUKU) {
                val out = try {
                    ShizukuBridge.installApk(apk, allowDowngrade = true, HelperInstallTransaction.INSTALL_TIMEOUT_MS)
                        ?.trim().orEmpty()
                } finally {
                    apk.delete()
                }
                if (out.contains("Success", ignoreCase = true)) InstallOutcome.Succeeded
                else installFailure(out.ifBlank { "Shizuku installer unavailable" })
            } else {
                apk.delete()
                InstallOutcome.Retryable("skipped: no permitted installer")
            }
            if (outcome is InstallOutcome.Failure) Log.w(TAG, outcome.message)
            installSucceeded = outcome is InstallOutcome.Succeeded
            outcome
        } finally {
            // Package-manager success may return before process replacement. Keep state mutation
            // admission quiesced across that lag; only a failed install reopens writes.
            finishSelfReplaceQuiescence(stateQuiescence, installSucceeded)
        }
    }

    /**
     * Classify a `pm install` failure [output] line into a typed [InstallOutcome.Failure]. A
     * package-manager `Failure [...]` rejection is durable — retrying the same APK cannot help — so it
     * is [InstallOutcome.Rejected]; anything else (empty output, an installer-unavailable note, other
     * text) is [InstallOutcome.Retryable]. The message preserves the historical
     * `"install failed: <trimmed output>"` text exactly.
     */
    internal fun installFailure(output: String): InstallOutcome {
        val message = "install failed: ${output.take(120)}"
        return if (output.startsWith("Failure [")) InstallOutcome.Rejected(message)
        else InstallOutcome.Retryable(message)
    }

    internal fun finishSelfReplaceQuiescence(
        quiescence: io.github.maxlyth.hapaneld.persistence.StateQuiescence?,
        installSucceeded: Boolean,
    ) {
        if (!installSucceeded) quiescence?.close()
    }

    /** Null = APK declares [pin].pkg AND is signed by the pinned cert; else a short reason. */
    @Suppress("DEPRECATION") // GET_SIGNATURES / PackageInfo.signatures for API < 28
    private fun verifyApk(context: Context, apkPath: String, pin: Pin): String? {
        pin.apkSha256?.let { expected ->
            val actual = sha256(File(apkPath))
            if (!actual.equals(expected, ignoreCase = true)) return "APK checksum mismatch"
        }
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

    internal fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    internal fun selectInstallRoute(
        hasSu: Boolean,
        hasDaemon: Boolean,
        hasShizuku: Boolean,
        allowShizuku: Boolean,
    ): InstallRoute = when {
        hasSu -> InstallRoute.SU
        hasDaemon -> InstallRoute.DAEMON
        allowShizuku && hasShizuku -> InstallRoute.SHIZUKU
        else -> InstallRoute.NONE
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
        val deadline = MonotonicDeadline(DOWNLOAD_TOTAL_TIMEOUT_MS)
        var current = URL(url).takeIf { it.protocol.equals("https", true) }
            ?: run { Log.w(TAG, "refusing non-HTTPS URL"); return false }
        repeat(5) {
            val remainingMs = deadline.remainingMs()
            if (remainingMs <= 0L) return false
            val conn = current.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = minOf(15_000L, remainingMs).coerceAtLeast(1L).toInt()
            conn.readTimeout = minOf(60_000L, remainingMs).coerceAtLeast(1L).toInt()
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
                                copyBeforeDeadline(input, output, maxBytes, deadline::remainingMs)
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

    internal fun copyBeforeDeadline(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        maxBytes: Long,
        remainingMs: () -> Long,
    ): Long {
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (true) {
            if (remainingMs() <= 0L) throw java.net.SocketTimeoutException("APK download deadline exceeded")
            val read = input.read(buffer)
            if (read < 0) return copied
            if (read == 0) continue
            copied += read
            if (copied > maxBytes) throw ByteLimitExceeded(maxBytes)
            if (remainingMs() <= 0L) throw java.net.SocketTimeoutException("APK download deadline exceeded")
            output.write(buffer, 0, read)
        }
    }

    private const val DOWNLOAD_TOTAL_TIMEOUT_MS = 10L * 60_000L
}
