package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.util.AndroidInput
import java.io.File

/** Truthful lifecycle state for the native relay: command acceptance is not process liveness. */
internal class RelayProcessState(private val probe: () -> Boolean) {
    private var expected = false

    @Synchronized fun start(launch: () -> Boolean): Boolean {
        expected = false
        if (!launch()) return false
        expected = probe()
        return expected
    }

    @Synchronized fun running(): Boolean {
        if (!expected) return false
        if (probe()) return true
        expected = false
        return false
    }

    @Synchronized fun stop(terminate: () -> Boolean): Boolean {
        val stopped = terminate()
        expected = false
        return stopped
    }
}

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
    private const val ROOT_DIR = "/data/local/.hapaneld-cdp"
    private const val BIN = "$ROOT_DIR/cdprelay"

    private val process = RelayProcessState {
        Su.runOutput("pidof cdprelay 2>/dev/null")?.trim()?.isNotEmpty() == true
    }

    val running: Boolean get() = process.running()

    /** Resolve only the configured renderer's WebView socket. Picking the first global socket can
     * expose another app's DevTools endpoint when multiple debuggable WebViews are running. */
    private fun socketName(ctx: Context): String? {
        val configured = Config(ctx).dashboardPackage
        val pkg = if (configured == SystemController.BUILTIN_DASHBOARD) ctx.packageName else configured
        if (!AndroidInput.isPackage(pkg)) return null
        val pids = Su.runOutput("pidof $pkg")
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.mapNotNull { it.toIntOrNull()?.takeIf { pid -> pid > 0 } }
            ?.toSet()
            .orEmpty()
        if (pids.isEmpty()) return null
        val sockets = Su.runOutput("cat /proc/net/unix") ?: return null
        return selectDevToolsSocket(sockets, pids)
    }

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
        val name = socketName(ctx) ?: return "no-socket" // WebView debugging not enabled
        val bin = extractBinary(ctx) ?: return "no-binary"
        stop() // clear any stale instance first
        val ok = process.start {
            // Give the child a short chance to bind and fail before probing; a successful background
            // shell launch alone cannot prove that the socket name or TCP port was usable.
            Su.run(startCommand(bin.absolutePath, name))
        }
        Log.i(TAG, "start relay ($name) -> $ok")
        return if (ok) "started" else "failed"
    }

    fun stop(): Boolean = process.stop { Su.run("pkill -f $BIN") }

    /** Atomically stage below root-controlled /data/local. `/data/local/tmp` is shell-owned, so even a
     * root-owned child there could be renamed and replaced by a Shizuku-capable shell peer. */
    internal fun startCommand(source: String, socketName: String): String =
        "rm -rf $ROOT_DIR && mkdir -m 700 $ROOT_DIR && chown 0:0 $ROOT_DIR && " +
            "cp $source $BIN.new && chown 0:0 $BIN.new && chmod 755 $BIN.new && mv -f $BIN.new $BIN && " +
            "( $BIN $PORT $socketName >/dev/null 2>&1 & ) && sleep 1"

    internal fun selectDevToolsSocket(unixSockets: String, rendererPids: Set<Int>): String? =
        Regex("(?:^|[^A-Za-z0-9_])(webview_devtools_remote_([0-9]+))(?:$|[^0-9])")
            .findAll(unixSockets)
            .mapNotNull { match ->
                match.groupValues[2].toIntOrNull()
                    ?.takeIf(rendererPids::contains)
                    ?.let { match.groupValues[1] }
            }
            .distinct()
            .singleOrNull()
}
