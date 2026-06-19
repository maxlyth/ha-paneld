package io.github.maxlyth.hapaneld.util

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Loopback client for the root helper daemon (`helper/hapaneld-ledd`) on `127.0.0.1:8889`. The app
 * (`untrusted_app`) can connect to loopback TCP but cannot write the root-only sysfs nodes the
 * daemon owns (LED + backlight power). Used by both the sysfs LED controller and the screen
 * controller. All calls are short blocking socket I/O — invoke off the main thread.
 */
object HelperClient {
    private const val PORT = 8889
    private const val TIMEOUT_MS = 500
    private const val TAG = "ha-paneld/helper"

    /** True when the daemon answers `PING`. */
    fun available(): Boolean = send("PING") == "OK"

    /** Send one command; return the daemon's reply line (trimmed), or null if unreachable. */
    fun send(cmd: String): String? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", PORT), TIMEOUT_MS)
            s.soTimeout = TIMEOUT_MS
            s.getOutputStream().apply { write((cmd + "\n").toByteArray()); flush() }
            BufferedReader(InputStreamReader(s.getInputStream())).readLine()?.trim()
        }
    } catch (e: Exception) {
        Log.d(TAG, "daemon not reachable (${e.message})")
        null
    }

    /** Send one command and read the full **binary** reply (e.g. `SCREENCAP` PNG bytes). Half-closes the
     *  write side so the daemon's serve loop sees EOF, processes the command, and closes — giving us EOF
     *  after all the bytes. Longer timeout (screencap takes ~1-2s). Null if unreachable/empty. */
    fun sendBytes(cmd: String): ByteArray? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", PORT), TIMEOUT_MS)
            s.soTimeout = 5000
            s.getOutputStream().apply { write((cmd + "\n").toByteArray()); flush() }
            s.shutdownOutput()                       // daemon read -> 0 after handling -> closes -> EOF here
            s.getInputStream().readBytes().takeIf { it.isNotEmpty() }
        }
    } catch (e: Exception) {
        Log.d(TAG, "daemon bytes failed (${e.message})")
        null
    }
}
