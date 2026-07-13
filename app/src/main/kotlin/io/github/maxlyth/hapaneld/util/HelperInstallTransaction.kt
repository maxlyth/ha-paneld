package io.github.maxlyth.hapaneld.util

import io.github.maxlyth.hapaneld.platform.Daemon
import io.github.maxlyth.hapaneld.platform.DaemonLongResult
import java.io.File

/**
 * Owns one helper-backed APK install from source-file claim through terminal daemon reply. The source
 * is moved into a unique persistent app-private staging path before submission, so neither cache
 * eviction nor a later upload/download can replace bytes an in-flight helper may still consume.
 *
 * A terminal reply or definite connection failure releases the staged file. An indeterminate call
 * deliberately retains it: the helper worker is independent of the client socket, so deletion would
 * violate input ownership when no terminal reply proved completion.
 */
internal class HelperInstallTransaction(
    private val daemon: Daemon,
    private val timeoutMs: Long = INSTALL_TIMEOUT_MS,
) {
    fun install(apk: File, stagingDir: File): String {
        val owned = claim(apk, stagingDir)
            ?: return "install failed: could not claim helper staging"
        return when (val result = daemon.sendLong("INSTALL ${owned.absolutePath}", timeoutMs)) {
            is DaemonLongResult.Reply -> {
                owned.delete()
                if (result.value == "OK") "OK" else "install failed: daemon install failed"
            }
            DaemonLongResult.NotSubmitted -> {
                owned.delete()
                "install failed: daemon unreachable"
            }
            DaemonLongResult.Indeterminate ->
                "install outcome unknown: helper staging retained for safety"
        }
    }

    private fun claim(apk: File, stagingDir: File): File? {
        if (!apk.isFile || apk.length() <= 0L) {
            apk.delete()
            return null
        }
        if (!stagingDir.isDirectory && !stagingDir.mkdirs()) {
            apk.delete()
            return null
        }
        val expected = apk.length()
        val owned = runCatching {
            File.createTempFile(STAGING_PREFIX, ".apk", stagingDir).also {
                if (!it.delete()) error("could not reserve staging path")
            }
        }.getOrElse {
            apk.delete()
            return null
        }
        val claimed = apk.renameTo(owned) || runCatching {
            apk.inputStream().use { input -> owned.outputStream().use { input.copyTo(it) } }
            owned.length() == expected
        }.getOrDefault(false)
        if (!claimed || !owned.isFile || owned.length() != expected) {
            owned.delete()
            apk.delete()
            return null
        }
        apk.delete()
        return owned
    }

    companion object {
        internal const val INSTALL_TIMEOUT_MS = 180_000L
        internal const val STAGING_DIR = "helper-install-staging"
        internal const val STAGING_PREFIX = "hapaneld-helper-install-"
    }
}
