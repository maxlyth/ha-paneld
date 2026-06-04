package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.maxlyth.hapaneld.util.HelperClient

/**
 * Panel-level actions: reload the dashboard, bring a launcher / the dashboard to the foreground,
 * reboot.
 *
 * **Why these go through root, not `context.startActivity`:** ha-paneld is a foreground *service*,
 * and Android 10+ (API 29) blocks background activity starts from a service — so a direct
 * `startActivity` silently no-ops on every panel except the API 27 NSPanelPro. Launching via the
 * root helper daemon or `su` (`am start` / `monkey`) runs from a shell/root domain that BAL doesn't
 * restrict. We resolve the target *component* in-app (just a PackageManager query, always allowed)
 * and hand it to the privileged launcher. Pre-BAL panels fall back to a direct start.
 */
class SystemController(private val context: Context) {

    /** Launch an activity [component] ("pkg/cls") via the privileged path (daemon START, else su). */
    private fun privilegedStart(component: String): Boolean {
        if (component.isBlank()) return false
        // Prefer the daemon, but only trust an explicit OK — an older daemon without the START verb
        // replies ERR, so fall back to su (covers su-capable panels with a stale daemon).
        if (HelperClient.available() && HelperClient.send("START $component") == "OK") return true
        return Su.run("am start -n $component")
    }

    /** Direct fallback for pre-BAL (API < 29) panels where the service can start activities. */
    private fun directStart(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Configured dashboard pkg, else the installed HA Companion (this is an HA project). */
    private fun resolveDashboard(pkg: String): String {
        if (pkg.isNotBlank()) return pkg
        for (p in listOf("io.homeassistant.companion.android.minimal", "io.homeassistant.companion.android")) {
            if (runCatching { context.packageManager.getPackageInfo(p, 0) }.isSuccess) return p
        }
        return ""
    }

    /** Force-stop the dashboard and relaunch it. */
    fun reloadDashboard(dashboardPkg: String) {
        val pkg = resolveDashboard(dashboardPkg)
        if (pkg.isBlank()) { Log.w(TAG, "reload: no dashboard pkg (set dashboard_package)"); return }
        if (HelperClient.available()) { // daemon force-stops + monkey-relaunches as root (no BAL)
            HelperClient.send("RELOAD $pkg")
            Log.i(TAG, "reload via daemon ($pkg)")
            return
        }
        if (Su.run("am force-stop $pkg")) {
            val comp = context.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString()
            if (comp == null || !privilegedStart(comp)) Su.run("monkey -p $pkg 1")
            Log.i(TAG, "reload via su ($pkg)")
        } else {
            Log.w(TAG, "reload: neither daemon nor su available")
        }
    }

    /**
     * Bring a launcher (home screen) to the foreground — for panels with no physical home button.
     * [configuredPkg] forces a package; blank => first registered HOME launcher that isn't the
     * current default, ourselves, or settings.
     */
    fun launchLauncher(configuredPkg: String) {
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val default = pm.resolveActivity(home, 0)?.activityInfo?.packageName
        val ri = pm.queryIntentActivities(home, 0).firstOrNull {
            val p = it.activityInfo.packageName
            if (configuredPkg.isNotBlank()) p == configuredPkg
            else p != default && p != context.packageName && p != "com.android.settings"
        }
        if (ri == null) {
            Log.w(TAG, "launcher: no alternate launcher found (set launcher_package?)")
            return
        }
        val comp = "${ri.activityInfo.packageName}/${ri.activityInfo.name}"
        if (!privilegedStart(comp)) directStart(home.setPackage(ri.activityInfo.packageName))
        Log.i(TAG, "launcher -> $comp")
    }

    /** Bring the dashboard (or the default home app) to the foreground. */
    fun launchHome(dashboardPkg: String) {
        val pm = context.packageManager
        val pkg = resolveDashboard(dashboardPkg)
        val comp = if (pkg.isNotBlank()) {
            pm.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString()
        } else {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            pm.resolveActivity(home, 0)?.activityInfo?.let { "${it.packageName}/${it.name}" }
        }
        if (comp == null) { Log.w(TAG, "home: no target resolved"); return }
        if (!privilegedStart(comp)) {
            directStart(pm.getLaunchIntentForPackage(pkg) ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME))
        }
        Log.i(TAG, "home -> $comp")
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
