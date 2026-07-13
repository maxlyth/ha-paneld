package io.github.maxlyth.hapaneld.util

import io.github.maxlyth.hapaneld.platform.Daemon
import io.github.maxlyth.hapaneld.platform.DaemonLongResult
import io.github.maxlyth.hapaneld.platform.DaemonStreamResult
import java.io.File

/**
 * Owns one helper-backed APK install through terminal daemon reply. A current helper receives bytes
 * over the authenticated socket, so its SELinux domain never opens app-private storage. Closing that
 * socket ends source consumption even when the install outcome is unknown.
 *
 * An older helper rejects the stream verb before payload and falls back to the path-based transaction:
 * the source is then claimed into unique persistent staging and retained after an indeterminate call.
 */
internal class HelperInstallTransaction(
    private val daemon: Daemon,
    private val timeoutMs: Long = INSTALL_TIMEOUT_MS,
    private val staging: HelperInstallStaging = HelperInstallStaging.shared,
) {
    fun install(apk: File, stagingDir: File): String {
        if (!apk.isFile || apk.length() <= 0L) {
            apk.delete()
            return "install failed: invalid APK input"
        }
        when (val streamed = daemon.sendFile("INSTALLSTREAM ${apk.length()}", apk, timeoutMs)) {
            is DaemonStreamResult.Reply -> {
                apk.delete()
                return if (streamed.value == "OK") "OK" else "install failed: daemon install failed"
            }
            DaemonStreamResult.NotSubmitted -> {
                apk.delete()
                return "install failed: daemon unreachable"
            }
            DaemonStreamResult.Indeterminate -> {
                apk.delete()
                return "install outcome unknown: streamed input released"
            }
            DaemonStreamResult.Unsupported -> Unit
        }

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
