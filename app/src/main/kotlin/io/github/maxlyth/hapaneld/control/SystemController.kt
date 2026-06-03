package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Panel-level actions: reload the dashboard and reboot. Both require root (`su`) — force-stopping
 * another app and rebooting are privileged operations a sideloaded app can't do directly. The
 * fleet's PX30 panels have toolbox `su`; on a panel without it these are graceful no-ops
 * (load-if-present, like the LED HAL).
 */
class SystemController(private val context: Context) {

    /** Reload the dashboard by force-stopping the Companion (its WebView host) and relaunching it. */
    fun reloadDashboard(companionPkg: String = DEFAULT_COMPANION) {
        if (!su("am force-stop $companionPkg")) {
            Log.w(TAG, "reload: su unavailable — cannot force-stop $companionPkg")
            return
        }
        // Relaunch via the package's own launcher intent (no need to know its Activity class).
        val launch = context.packageManager.getLaunchIntentForPackage(companionPkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(launch) }
        }
        Log.i(TAG, "dashboard reloaded ($companionPkg)")
    }

    fun reboot() {
        Log.i(TAG, "reboot requested")
        // Fire-and-forget: the device goes down, so don't wait on the process.
        su("reboot", wait = false)
    }

    private fun su(cmd: String, wait: Boolean = true): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        if (wait) p.waitFor() == 0 else true
    } catch (e: Exception) {
        Log.w(TAG, "su failed: $cmd", e)
        false
    }

    companion object {
        private const val TAG = "ha-paneld/system"
        private const val DEFAULT_COMPANION = "io.homeassistant.companion.android.minimal"
    }
}
