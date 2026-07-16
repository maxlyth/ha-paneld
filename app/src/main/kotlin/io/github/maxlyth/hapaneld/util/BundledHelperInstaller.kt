package io.github.maxlyth.hapaneld.util

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.maxlyth.hapaneld.control.Su
import java.io.File

/**
 * First-start migration backstop for the supported in-app self-update path.
 *
 * Provisioning remains the durable helper installer. A previously released direct-su panel can,
 * however, install a newer APK before the external provisioner runs. The new APK therefore carries
 * its exact helper and launches a root-owned `/data/local` copy when the installed daemon lacks the
 * Companion protocol. Helper-only panels cannot safely replace their own old daemon and remain
 * explicitly reprovision-gated instead of accepting a generic privileged upgrade verb.
 */
internal object BundledHelperInstaller {
    enum class Result { ALREADY_CURRENT, INSTALLED, SKIPPED, FAILED }

    @Synchronized
    fun ensureCurrent(context: Context, directSuExpected: Boolean): Result {
        if (HelperClient.supportsCompanionData() && HelperClient.matchesBundledHelper()) return Result.ALREADY_CURRENT
        if (!directSuExpected || !Su.available()) return Result.SKIPPED
        val asset = helperAssetName(Build.SUPPORTED_ABIS.asIterable()) ?: return Result.SKIPPED
        val staged = runCatching { File.createTempFile("hapaneld-helper-", ".bin", context.cacheDir) }
            .getOrElse { return Result.FAILED }
        var privilegedAttempted = false
        return try {
            context.assets.open(asset).use { input -> staged.outputStream().use(input::copyTo) }
            val expectedSha256 = AppInstaller.sha256(staged)
            privilegedAttempted = true
            val output = Su.runWithStdinLongChecked(
                bundledHelperInstallCommand(expectedSha256),
                staged,
                INSTALL_TIMEOUT_MS,
            )
            if (output?.lineSequence()?.lastOrNull()?.trim() != "INSTALL_OK") {
                Log.w(TAG, "bundled helper install failed: ${output.orEmpty().take(120)}")
                recoverPreviousHelper()
                return Result.FAILED
            }
            repeat(CAPABILITY_POLLS) {
                if (HelperClient.supportsCompanionData() && HelperClient.matchesBundledHelper()) return Result.INSTALLED
                Thread.sleep(CAPABILITY_POLL_MS)
            }
            Log.w(TAG, "bundled helper launched but exact Companion capability did not appear")
            recoverPreviousHelper()
            Result.FAILED
        } catch (failure: Exception) {
            Log.w(TAG, "bundled helper migration failed", failure)
            if (privilegedAttempted) recoverPreviousHelper()
            Result.FAILED
        } finally {
            staged.delete()
        }
    }

    private const val TAG = "ha-paneld/helper-migrate"
    private const val INSTALL_TIMEOUT_MS = 30_000L
    private const val CAPABILITY_POLLS = 20
    private const val CAPABILITY_POLL_MS = 250L
    private const val RECOVERY_POLLS = 8
    private const val RECOVERY_POLL_MS = 250L

    private fun recoverPreviousHelper() {
        // A failed root submission may have left the pre-existing helper untouched. Do not kill the
        // only working ephemeral daemon merely because it predates BUILDID or this APK's identity.
        if (HelperClient.available() && !HelperClient.matchesBundledHelper()) return
        for (command in bundledHelperRecoveryCommands()) {
            if (!Su.runLong(command, INSTALL_TIMEOUT_MS)) continue
            repeat(RECOVERY_POLLS) {
                if (HelperClient.available()) return
                Thread.sleep(RECOVERY_POLL_MS)
            }
        }
        Log.e(TAG, "could not restore a responsive root helper after migration failure")
    }
}

internal fun helperAssetName(abis: Iterable<String>): String? = when {
    abis.any { it == "arm64-v8a" } -> "hapaneld-helper-arm64"
    abis.any { it == "armeabi-v7a" } -> "hapaneld-helper-arm"
    else -> null
}

/** Fixed command: callers contribute only a validated lowercase SHA-256 digest. */
internal fun bundledHelperInstallCommand(expectedSha256: String): String {
    require(expectedSha256.matches(Regex("[0-9a-f]{64}")))
    val dollar = '$'
    return """
        [ -d /data/local ] && [ ! -L /data/local ] &&
        rm -f /data/local/.hapaneld-helper.new &&
        cat > /data/local/.hapaneld-helper.new &&
        actual=${dollar}( (sha256sum /data/local/.hapaneld-helper.new 2>/dev/null || toybox sha256sum /data/local/.hapaneld-helper.new 2>/dev/null) | awk '{print ${dollar}1}' ) &&
        [ "${dollar}actual" = "$expectedSha256" ] &&
        chown 0:0 /data/local/.hapaneld-helper.new &&
        chmod 700 /data/local/.hapaneld-helper.new &&
        rm -f /data/local/.hapaneld-helper.previous &&
        ( [ ! -f /data/local/hapaneld-helper ] ||
          ( [ ! -L /data/local/hapaneld-helper ] &&
            cp -p /data/local/hapaneld-helper /data/local/.hapaneld-helper.previous &&
            chown 0:0 /data/local/.hapaneld-helper.previous &&
            chmod 700 /data/local/.hapaneld-helper.previous ) ) &&
        mv -f /data/local/.hapaneld-helper.new /data/local/hapaneld-helper &&
        (stop hapaneld_helper 2>/dev/null || true) &&
        (pkill -x hapaneld-helper 2>/dev/null || true) &&
        (/data/local/hapaneld-helper >/dev/null 2>&1 &) &&
        echo INSTALL_OK
    """.trimIndent()
}

/**
 * Ordered recovery candidates. Each attempt is followed by a root-authenticated PING in the app;
 * successfully forking a background executable alone is not evidence that it could execute, bind or
 * survive SELinux. Init is tried before durable path guesses because its service may point elsewhere.
 */
internal fun bundledHelperRecoveryCommands(): List<String> = listOf(
    """
        [ -f /data/local/.hapaneld-helper.previous ] &&
        [ ! -L /data/local/.hapaneld-helper.previous ] &&
        (stop hapaneld_helper 2>/dev/null || true) &&
        (pkill -x hapaneld-helper 2>/dev/null || true) &&
        mv -f /data/local/.hapaneld-helper.previous /data/local/hapaneld-helper &&
        chown 0:0 /data/local/hapaneld-helper &&
        chmod 700 /data/local/hapaneld-helper &&
        (/data/local/hapaneld-helper >/dev/null 2>&1 &)
    """.trimIndent(),
    """
        (stop hapaneld_helper 2>/dev/null || true) &&
        (pkill -x hapaneld-helper 2>/dev/null || true) &&
        start hapaneld_helper
    """.trimIndent(),
    """
        [ -x /data/adb/hapaneld/hapaneld-helper ] &&
        (stop hapaneld_helper 2>/dev/null || true) &&
        (pkill -x hapaneld-helper 2>/dev/null || true) &&
        (/data/adb/hapaneld/hapaneld-helper >/dev/null 2>&1 &)
    """.trimIndent(),
    """
        [ -x /system/bin/hapaneld-helper ] &&
        (stop hapaneld_helper 2>/dev/null || true) &&
        (pkill -x hapaneld-helper 2>/dev/null || true) &&
        (/system/bin/hapaneld-helper >/dev/null 2>&1 &)
    """.trimIndent(),
)
