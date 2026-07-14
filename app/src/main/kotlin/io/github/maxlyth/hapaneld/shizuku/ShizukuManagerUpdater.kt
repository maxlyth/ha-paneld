package io.github.maxlyth.hapaneld.shizuku

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.maxlyth.hapaneld.util.AppInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Curated manager updater for managed opt-ins. It never follows a latest-release endpoint: advancing
 * the dependency requires changing and reviewing this immutable release tuple in ha-paneld.
 */
object ShizukuManagerUpdater {
    private const val TAG = "ha-paneld/shizuku-update"
    const val TARGET_VERSION_CODE = 1086L
    const val TARGET_VERSION = "13.6.0.r1086.2650830c"
    const val TARGET_URL =
        "https://github.com/RikkaApps/Shizuku/releases/download/v13.6.0/" +
            "shizuku-v13.6.0.r1086.2650830c-release.apk"
    const val TARGET_APK_SHA256 = "6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f"

    private val pin = AppInstaller.Pin(
        pkg = ShizukuManagerIdentity.PACKAGE,
        certSha256 = ShizukuManagerIdentity.CERT_SHA256,
        apkSha256 = TARGET_APK_SHA256,
    )

    enum class Decision { DISABLED, NOT_READY, UP_TO_DATE, UPDATE }

    internal fun decide(managed: Boolean, autoUpdate: Boolean, ready: Boolean, currentVersionCode: Long?): Decision = when {
        !managed || !autoUpdate -> Decision.DISABLED
        !ready || currentVersionCode == null -> Decision.NOT_READY
        currentVersionCode >= TARGET_VERSION_CODE -> Decision.UP_TO_DATE
        else -> Decision.UPDATE
    }

    suspend fun checkAndUpdate(context: Context): String = withContext(Dispatchers.IO) {
        val current = installedVersionCode(context)
        when (decide(
            managed = ShizukuConsent.managed(context),
            autoUpdate = ShizukuConsent.autoUpdate(context),
            ready = ShizukuBridge.available() && ShizukuManagerIdentity.status(context) == ShizukuManagerIdentity.Status.TRUSTED,
            currentVersionCode = current,
        )) {
            Decision.DISABLED -> return@withContext "disabled"
            Decision.NOT_READY -> return@withContext "deferred: Shizuku not ready"
            Decision.UP_TO_DATE -> return@withContext "up to date ($TARGET_VERSION)"
            Decision.UPDATE -> Unit
        }

        // PackageManager installs are atomic: a failed install leaves the incumbent in place. Keep an
        // additional copy of the trusted incumbent so a post-install identity/version check can actively
        // roll back while the shell UserService is still alive.
        val rollback = trustedIncumbentCopy(context)
        try {
            Log.i(TAG, "updating curated Shizuku manager $current -> $TARGET_VERSION_CODE")
            val result = AppInstaller.install(context, TARGET_URL, pin)
            if (result != "OK") return@withContext result

            ShizukuBridge.refresh()
            val installed = installedVersionCode(context)
            val trusted = ShizukuManagerIdentity.status(context) == ShizukuManagerIdentity.Status.TRUSTED
            if (trusted && installed != null && installed >= TARGET_VERSION_CODE) {
                return@withContext "updated Shizuku -> $TARGET_VERSION"
            }

            val rollbackResult = rollback?.let { AppInstaller.installLocalApk(context, it) }
                ?: "rollback unavailable"
            Log.e(TAG, "post-update verification failed; $rollbackResult")
            "Shizuku update verification failed ($rollbackResult)"
        } finally {
            rollback?.delete()
        }
    }

    @Suppress("DEPRECATION")
    internal fun installedVersionCode(context: Context): Long? = runCatching {
        val info = context.packageManager.getPackageInfo(ShizukuManagerIdentity.PACKAGE, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    }.getOrNull()

    private fun trustedIncumbentCopy(context: Context): File? {
        if (ShizukuManagerIdentity.status(context) != ShizukuManagerIdentity.Status.TRUSTED) return null
        val source = runCatching {
            context.packageManager.getApplicationInfo(ShizukuManagerIdentity.PACKAGE, 0).sourceDir
        }.getOrNull() ?: return null
        return runCatching {
            File.createTempFile("shizuku-rollback-", ".apk", context.cacheDir).also { target ->
                File(source).inputStream().use { input -> target.outputStream().use(input::copyTo) }
            }
        }.getOrNull()
    }
}
