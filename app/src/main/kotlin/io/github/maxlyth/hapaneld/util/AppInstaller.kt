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
        // the su install streams straight from it (no second /data/local/tmp copy).
        val size = contentLength(url)
        if (size > MAX_APK_DOWNLOAD_BYTES) {
            Log.w(TAG, "refusing oversized APK download: $size bytes")
            return@withContext InstallOutcome.Retryable("download too large (${size / 1048576} MB)")
        }
        val free = context.cacheDir.usableSpace
        val downloadLimit = downloadCeiling(size, free)
        if (downloadLimit <= 0L) {
            val why = if (size > 0L)
                "insufficient storage (need ${size / 1048576} MB, ${free / 1048576} MB free)"
            else "insufficient storage (no free space)"
            Log.w(TAG, why)
            return@withContext InstallOutcome.Retryable(why)
        }
        val apk = runCatching { File.createTempFile("hapaneld-dl-", ".apk", context.cacheDir) }
            .getOrElse { return@withContext InstallOutcome.Retryable("download staging failed") }
        withStagedFiles { staged ->
            staged.stage(apk)
            if (download(url, apk, downloadLimit) != DownloadResult.Succeeded)
                return@withStagedFiles InstallOutcome.Retryable("download failed")
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
        val stateQuiescence = if (replacingSelf) {
            prepareSelfReplace(
                snapshot = { ConfigUpgradeBackup.snapshot(context) },
                quiesce = { AppState.quiesceForSelfReplace(context, SELF_REPLACE_STATE_FLUSH_MS) },
                warn = { Log.w(TAG, it) },
            )
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

    /**
     * Bytes the staged download may occupy, or 0 to refuse. Admission is the download's **actual**
     * requirement: a declared [size] must fit in [free] and nothing beyond it is reserved, because a
     * fixed surplus only ever refused upgrades that would have installed. A server that declares no
     * length ([size] < 0) is still bounded — by whichever of [free] and [MAX_APK_DOWNLOAD_BYTES] is
     * smaller — and only a completely full filesystem is refused outright. Space exhausted while
     * writing is reported by the write itself rather than pre-judged here.
     */
    internal fun downloadCeiling(size: Long, free: Long): Long {
        val usable = free.coerceAtLeast(0L)
        if (size > usable) return 0L
        return minOf(MAX_APK_DOWNLOAD_BYTES, usable)
    }

    /**
     * Prepare to replace the running package: take the private configuration revision, then quiesce
     * state writes. The revision is defense in depth, not a precondition — an unwritable one warns
     * through [warn] and the upgrade continues, because stranding the panel on the old build is the
     * worse outcome and nothing in the restore path requires this optional copy. [quiesce] is still
     * the gate; a null return means state is not yet safe to replace under.
     */
    internal fun prepareSelfReplace(
        snapshot: () -> Boolean,
        quiesce: () -> io.github.maxlyth.hapaneld.persistence.StateQuiescence?,
        warn: (String) -> Unit,
    ): io.github.maxlyth.hapaneld.persistence.StateQuiescence? {
        if (!snapshot()) warn(SNAPSHOT_UNWRITABLE_WARNING)
        return quiesce()
    }

    internal const val SNAPSHOT_UNWRITABLE_WARNING =
        "pre-upgrade configuration revision could not be written; continuing with the upgrade"

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

    /**
     * Why a [download] did not produce bytes. The pinned [install] path collapses every non-[Succeeded]
     * value into one retryable failure, exactly as it did when this returned a Boolean; the Install-page
     * review flow reports them separately, because an administrator fetching an APK from somewhere else
     * on the network needs to know whether the panel was refused, timed out, or hit the size ceiling.
     */
    internal enum class DownloadResult { Succeeded, TooLarge, TimedOut, Failed }

    /**
     * Download [url] to [dest], following redirects (GitHub release → CDN).
     *
     * HTTPS-only for every hop: the initial URL and each redirect target must be `https`, else the
     * fetch is refused. The APK is signer-pinned after download ([verifyApk]) on the [install] path, so
     * a substituted blob still can't install — but refusing plaintext hops closes the residual downgrade
     * (an `https→http` redirect would otherwise fetch the update over cleartext, leaking the request and
     * letting a network attacker waste the download before the pin rejects it). Real callers use GitHub
     * `https` release URLs that redirect to `https` CDNs, so this rejects nothing legitimate; the
     * Install-page URL source deliberately inherits the same rule rather than widening it.
     *
     * Bounded three ways, none of which trusts the peer: at most five redirect hops, a whole-operation
     * [DOWNLOAD_TOTAL_TIMEOUT_MS] deadline that a slow drip cannot outlast, and [maxBytes] enforced by
     * [copyBeforeDeadline] on bytes actually read — a lying or absent `Content-Length` cannot widen it.
     */
    internal fun download(url: String, dest: File, maxBytes: Long): DownloadResult {
        val deadline = MonotonicDeadline(DOWNLOAD_TOTAL_TIMEOUT_MS)
        // Held as a non-null local: `current` is reassigned from inside the redirect loop, so a nullable
        // captured var would never smart-cast.
        val origin = runCatching { URL(url) }.getOrNull()?.takeIf { it.protocol.equals("https", true) }
            ?: run {
                Log.w(TAG, "refusing non-HTTPS or unparseable URL")
                return DownloadResult.Failed
            }
        var current: URL = origin
        return try {
            repeat(5) {
                val remainingMs = deadline.remainingMs()
                if (remainingMs <= 0L) return DownloadResult.TimedOut
                val conn = current.openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.connectTimeout = minOf(15_000L, remainingMs).coerceAtLeast(1L).toInt()
                conn.readTimeout = minOf(60_000L, remainingMs).coerceAtLeast(1L).toInt()
                try {
                    when (conn.responseCode) {
                        in 300..399 -> {
                            val loc = conn.getHeaderField("Location") ?: return DownloadResult.Failed
                            current = httpsRedirect(current, loc)
                                ?: run { Log.w(TAG, "refusing non-HTTPS redirect"); return DownloadResult.Failed }
                        }
                        200 -> {
                            val declared = conn.contentLengthLong
                            if (declared > maxBytes) {
                                Log.w(TAG, "refusing oversized APK response: $declared bytes")
                                return DownloadResult.TooLarge
                            }
                            conn.inputStream.use { input ->
                                dest.outputStream().use { output ->
                                    copyBeforeDeadline(input, output, maxBytes, deadline::remainingMs)
                                }
                            }
                            return if (dest.length() > 0) DownloadResult.Succeeded else DownloadResult.Failed
                        }
                        else -> return DownloadResult.Failed
                    }
                } finally {
                    conn.disconnect()
                }
            }
            DownloadResult.Failed
        } catch (error: Exception) {
            downloadFailure(error)
        }
    }

    /**
     * Classify a failed download. Kept separate from [download] because the distinction is only
     * reachable through a live transfer otherwise, and it is the distinction the Install page shows an
     * operator: a size breach must not be reported as an unreachable host, nor a stall as a refusal.
     */
    internal fun downloadFailure(error: Exception): DownloadResult = when (error) {
        is ByteLimitExceeded -> {
            Log.w(TAG, "refusing APK response beyond the byte ceiling")
            DownloadResult.TooLarge
        }
        is java.net.SocketTimeoutException -> {
            Log.w(TAG, "APK download stalled or exceeded its deadline")
            DownloadResult.TimedOut
        }
        else -> {
            Log.w(TAG, "download error", error)
            DownloadResult.Failed
        }
    }

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
