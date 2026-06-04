package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * On-demand bridge that exposes the dashboard WebView's Chrome DevTools endpoint
 * (`@webview_devtools_remote_<pid>`) to the LAN on :[PORT], so a user can open chrome://inspect
 * against the panel with **no adb** — the step that's undocumented and makes people give up.
 *
 * Mechanics: a tiny native relay ([assets]/cdprelay-*) pumps `0.0.0.0:PORT` <-> the abstract socket.
 * It must run as root (an app can't connect to another app's abstract socket under SELinux; the su
 * domain can). We verified the WebView's CDP HTTP handler accepts an IP `Host`, so no proxying is
 * needed — a dumb byte-pump suffices and chrome://inspect rewrites nothing.
 *
 * Requires: WebView debugging enabled on the dashboard app (Companion → Settings → Troubleshooting →
 * "WebView remote debugging") and root. **Security:** while running, full DevTools (read + control)
 * is exposed to the whole LAN — so it is off by default, started on demand, and stoppable.
 */
object CdpRelay {
    private const val TAG = "ha-paneld/cdp"
    const val PORT = 9222
    private const val BIN = "/data/local/tmp/cdprelay"

    @Volatile
    var running = false
        private set

    /** Resolve the WebView CDP abstract socket name (null if debugging isn't enabled / no root). */
    private fun socketName(): String? =
        Su.runOutput("cat /proc/net/unix | grep -o 'webview_devtools_remote_[0-9]*' | head -1")
            ?.trim()?.ifEmpty { null }

    private fun extractBinary(ctx: Context): File? {
        val abi = if (Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) "cdprelay-arm64" else "cdprelay-arm"
        return runCatching {
            val f = File(ctx.filesDir, "cdprelay")
            ctx.assets.open(abi).use { input -> f.outputStream().use { input.copyTo(it) } }
            f
        }.getOrElse { Log.w(TAG, "extract failed", it); null }
    }

    /** Status codes the HTTP layer maps to UI text. */
    fun start(ctx: Context): String {
        if (!Su.available()) return "needs-root"
        val name = socketName() ?: return "no-socket" // WebView debugging not enabled
        val bin = extractBinary(ctx) ?: return "no-binary"
        stop() // clear any stale instance first
        val ok = Su.run(
            "cp ${bin.absolutePath} $BIN && chmod 755 $BIN && ( $BIN $PORT $name >/dev/null 2>&1 & )",
        )
        running = ok
        Log.i(TAG, "start relay ($name) -> $ok")
        return if (ok) "started" else "failed"
    }

    fun stop(): Boolean {
        val ok = Su.run("pkill -f $BIN")
        running = false
        return ok
    }
}
