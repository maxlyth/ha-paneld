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

    /**
     * Bring a launcher (home screen / app drawer) to the foreground — for panels with no physical
     * home/back buttons. Fires a `CATEGORY_HOME` intent at a launcher with `setPackage`, so it
     * launches THAT launcher rather than the default home app (which is usually the HA Companion on
     * these panels). [configuredPkg] forces a specific package; blank => auto-pick the first
     * registered HOME launcher that isn't the current default, ourselves, or the settings fallback.
     */
    fun launchLauncher(configuredPkg: String) {
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val target = configuredPkg.ifBlank {
            val default = pm.resolveActivity(home, 0)?.activityInfo?.packageName
            pm.queryIntentActivities(home, 0)
                .map { it.activityInfo.packageName }
                .firstOrNull { it != default && it != context.packageName && it != "com.android.settings" }
                .orEmpty()
        }
        if (target.isBlank()) {
            Log.w(TAG, "launcher: no alternate launcher found (set launcher_package?)")
            return
        }
        try {
            context.startActivity(home.setPackage(target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Log.i(TAG, "launcher -> $target")
        } catch (e: Exception) {
            Log.w(TAG, "launcher launch failed for $target", e)
        }
    }

    /**
     * Bring the HA dashboard to the foreground (the complement of [launchLauncher], to get back
     * after using a launcher). Launches [dashboardPkg] if set, else the device's DEFAULT home app
     * (the HA Companion on these panels). Does not force-stop — just fronts the app.
     */
    fun launchHome(dashboardPkg: String) {
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val intent = if (dashboardPkg.isNotBlank()) {
            pm.getLaunchIntentForPackage(dashboardPkg) ?: Intent(home).setPackage(dashboardPkg)
        } else {
            home // default resolver = the boot/default home app (HA Companion)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            Log.i(TAG, "home -> ${dashboardPkg.ifBlank { "default" }}")
        } catch (e: Exception) {
            Log.w(TAG, "home launch failed", e)
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
