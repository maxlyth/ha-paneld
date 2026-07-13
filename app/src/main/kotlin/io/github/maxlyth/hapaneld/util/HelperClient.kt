package io.github.maxlyth.hapaneld.util

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import io.github.maxlyth.hapaneld.platform.Daemon
import io.github.maxlyth.hapaneld.platform.DaemonLongResult
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Client for the root helper daemon (`helper/hapaneld-helper`) over an **abstract-namespace UNIX
 * socket** (`@hapaneld-helper`). The app (`untrusted_app`) cannot write the root-only sysfs nodes the
 * daemon owns (LED + backlight power), so it asks the daemon. The daemon authenticates us by uid
 * (`SO_PEERCRED`) and rejects any other app — which is why this is a UNIX socket, not the old
 * unauthenticated `127.0.0.1:8889` TCP. Used by the sysfs LED + screen controllers (and others).
 * Calls are blocking socket I/O with verb-appropriate bounds — invoke off the main thread.
 */
object HelperClient : Daemon {
    private const val SOCK = "hapaneld-helper"   // abstract socket name; matches SOCK_NAME in main.c
    private const val TIMEOUT_MS = 500
    private const val TAG = "ha-paneld/helper"

    // Abstract local sockets have no connect timeout (no network round-trip): connect returns at once
    // if the daemon is listening, else throws — caught by the callers below.
    private fun open(): LocalSocket = LocalSocket().apply {
        connect(LocalSocketAddress(SOCK, LocalSocketAddress.Namespace.ABSTRACT))
    }

    /** True when the daemon answers `PING`. */
    override fun available(): Boolean = send("PING") == "OK"

    /** Send one command; return the daemon's reply line (trimmed), or null if unreachable. */
    override fun send(cmd: String): String? = try {
        open().use { s ->
            s.soTimeout = TIMEOUT_MS
            s.outputStream.apply { write((cmd + "\n").toByteArray()); flush() }
            BufferedReader(InputStreamReader(s.inputStream)).readLine()?.trim()
        }
    } catch (e: Exception) {
        Log.d(TAG, "daemon not reachable (${e.message})")
        null
    }

    /**
     * Long textual call for operations such as `INSTALL`. Once writing starts, any missing terminal
     * reply is indeterminate: closing our socket does not cancel the daemon's per-connection worker.
     */
    override fun sendLong(cmd: String, timeoutMs: Long): DaemonLongResult {
        val socket = try {
            open()
        } catch (e: Exception) {
            Log.d(TAG, "daemon long call not submitted (${e.message})")
            return DaemonLongResult.NotSubmitted
        }
        return socket.use { s ->
            var submissionBegan = false
            try {
                s.soTimeout = timeoutMs.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
                submissionBegan = true
                s.outputStream.apply { write((cmd + "\n").toByteArray()); flush() }
                BufferedReader(InputStreamReader(s.inputStream)).readLine()?.trim()
                    ?.let(DaemonLongResult::Reply)
                    ?: DaemonLongResult.Indeterminate
            } catch (e: Exception) {
                Log.d(TAG, "daemon long call ${if (submissionBegan) "indeterminate" else "not submitted"} (${e.message})")
                if (submissionBegan) DaemonLongResult.Indeterminate else DaemonLongResult.NotSubmitted
            }
        }
    }

    /** Send one command and read the full **binary** reply (e.g. `SCREENCAP` PNG bytes). Half-closes the
     *  write side so the daemon's serve loop sees EOF, processes the command, and closes — giving us EOF
     *  after all the bytes. Longer timeout (screencap takes ~1-2s). Null if unreachable/empty. */
    override fun sendBytes(cmd: String): ByteArray? = try {
        open().use { s ->
            s.soTimeout = 5000
            s.outputStream.apply { write((cmd + "\n").toByteArray()); flush() }
            s.shutdownOutput()                       // daemon read -> 0 after handling -> closes -> EOF here
            s.inputStream.readBytes().takeIf { it.isNotEmpty() }
        }
    } catch (e: Exception) {
        Log.d(TAG, "daemon bytes failed (${e.message})")
        null
    }
}
