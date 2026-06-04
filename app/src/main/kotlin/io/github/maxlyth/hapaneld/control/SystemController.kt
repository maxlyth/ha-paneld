package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.maxlyth.hapaneld.util.HelperClient

/**
 * Panel-level actions: reload the dashboard and reboot. Both are privileged (force-stop / reboot)
 * and a sandboxed app can't do them directly. Preferred path is the root helper daemon (works on
 * panels where the app can't exec `su`, e.g. TPA10); falls back to `su` where it's available.
 */
class SystemController(private val context: Context) {

    /**
     * Reload the dashboard by force-stopping [dashboardPkg] and relaunching it. The package is
     * configured per panel (HTTP config page); blank disables the action, since the dashboard host
     * differs across panels (HA Companion, a browser, a custom WebView).
     */
    fun reloadDashboard(dashboardPkg: String) {
        if (dashboardPkg.isBlank()) {
            Log.w(TAG, "reload: no dashboard_package configured — skipping")
            return
        }
        if (HelperClient.available()) {
            HelperClient.send("RELOAD $dashboardPkg")
            Log.i(TAG, "dashboard reloaded via daemon ($dashboardPkg)")
            return
        }
        // Fallback for su-capable panels: su force-stops, the app relaunches.
        if (Su.run("am force-stop $dashboardPkg")) {
            context.packageManager.getLaunchIntentForPackage(dashboardPkg)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(it) }
            }
            Log.i(TAG, "dashboard reloaded via su ($dashboardPkg)")
        } else {
            Log.w(TAG, "reload: neither daemon nor su available")
        }
    }

    fun reboot() {
        if (HelperClient.available()) {
            HelperClient.send("REBOOT")
            Log.i(TAG, "reboot via daemon")
            return
        }
        Log.i(TAG, "reboot via su")
        Su.fireAndForget("reboot")
    }

    companion object {
        private const val TAG = "ha-paneld/system"
    }
}
