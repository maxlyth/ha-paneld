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
    private val staging: HelperInstallStaging = HelperInstallStaging.shared,
) {
    fun install(apk: File, stagingDir: File): String {
        val owned = staging.claim(apk, stagingDir)
            ?: return "install failed: could not claim helper staging"
        return when (val result = daemon.sendLong("INSTALL ${owned.absolutePath}", timeoutMs)) {
            is DaemonLongResult.Reply -> {
                staging.release(owned, delete = true)
                if (result.value == "OK") "OK" else "install failed: daemon install failed"
            }
            DaemonLongResult.NotSubmitted -> {
                staging.release(owned, delete = true)
                "install failed: daemon unreachable"
            }
            DaemonLongResult.Indeterminate -> {
                staging.release(owned, delete = false)
                "install outcome unknown: helper staging retained for safety"
            }
        }
    }

    companion object {
        internal const val INSTALL_TIMEOUT_MS = 180_000L
        internal const val STAGING_DIR = "helper-install-staging"
        internal const val STAGING_PREFIX = "hapaneld-helper-install-"
    }
}
