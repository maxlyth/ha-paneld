package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.maxlyth.hapaneld.util.HelperClient

/** Foreground/liveness state of the dashboard app, as seen by the app watchdog. */
enum class AppState { FG, BG, DEAD, UNKNOWN }

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
        val all = pm.queryIntentActivities(home, 0)
        val default = pm.resolveActivity(home, 0)?.activityInfo?.packageName
        // Apps that register CATEGORY_HOME but are NOT an app-drawer launcher we'd want to land on:
        // ourselves, Settings, and the HA Companion (a kiosk dashboard, which registers as HOME).
        val notALauncher = { p: String ->
            p == context.packageName || p == "com.android.settings" ||
                p == "io.homeassistant.companion.android" || p == "io.homeassistant.companion.android.minimal"
        }
        val ri = when {
            configuredPkg.isNotBlank() -> all.firstOrNull { it.activityInfo.packageName == configuredPkg }
            // Prefer the actual default home when it's a real launcher (e.g. the vendor launcher) — the old
            // code always skipped the default and grabbed the first alternate, which on kiosk panels is the
            // HA Companion (registers as HOME) → opened the dashboard instead of a launcher (the bug).
            default != null && !notALauncher(default) -> all.firstOrNull { it.activityInfo.packageName == default }
            // Default IS a kiosk/dashboard (or us): fall back to any other real launcher.
            else -> all.firstOrNull { !notALauncher(it.activityInfo.packageName) && it.activityInfo.packageName != default }
        }
        if (ri == null) {
            Log.w(TAG, "launcher: no suitable launcher found (set launcher_package?)")
            return
        }
        val comp = "${ri.activityInfo.packageName}/${ri.activityInfo.name}"
        if (!privilegedStart(comp)) directStart(home.setPackage(ri.activityInfo.packageName))
        Log.i(TAG, "launcher -> $comp")
    }

    /** True if [pkg] is installed with a launchable activity — guards a stale configured launcher. */
    fun isLaunchable(pkg: String): Boolean =
        pkg.isNotBlank() && runCatching { context.packageManager.getLaunchIntentForPackage(pkg) != null }.getOrDefault(false)

    /**
     * Open ha-paneld's own on-demand admin launcher (an app drawer for panel admin). The default for
     * the navbar Launcher button — replaces landing on the vendor pseudo-launcher. Reached by explicit
     * component, so it works even when no other launcher is installed.
     */
    fun launchAdminLauncher() {
        val comp = "${context.packageName}/.AdminLauncherActivity"
        if (!privilegedStart(comp)) {
            directStart(Intent().setClassName(context.packageName, "${context.packageName}.AdminLauncherActivity"))
        }
        Log.i(TAG, "admin launcher -> $comp")
    }

    /** Set the default HOME (launcher) to [component] ("pkg/cls"). Daemon SETHOME, else su. */
    private fun setHomeActivity(component: String): Boolean {
        if (component.isBlank()) return false
        if (HelperClient.available() && HelperClient.send("SETHOME $component") == "OK") return true
        return Su.run("cmd package set-home-activity $component")
    }

    /**
     * Keep the dashboard app (HA Companion) as the default home.
     *
     * Our [AdminLauncherActivity] declares `CATEGORY_HOME` so it's a selectable / last-resort launcher.
     * The side effect: Android **clears the default-home association** whenever a package adds or changes
     * a HOME activity (i.e. every ha-paneld install/update) — after which pressing Home pops a chooser
     * instead of booting straight to the dashboard. So on boot we re-assert the dashboard app as the
     * default home, but only when home is unowned (the system resolver) or owned by *us* — a deliberate
     * third-party launcher set as home is left alone. If the dashboard app isn't installed we do nothing,
     * leaving our admin launcher as the genuine last-resort home.
     */
    fun ensureDashboardHome(dashboardPkg: String) {
        val target = resolveDashboard(dashboardPkg)
        if (target.isBlank()) { Log.i(TAG, "ensureHome: no dashboard app installed; leaving home as-is"); return }
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val current = pm.resolveActivity(home, 0)?.activityInfo?.packageName
        if (current == target) return                                   // already correct
        // Respect a real third-party launcher the user chose; only reclaim from "no default" or ourselves.
        if (current != null && current != "android" && current != context.packageName) return
        val comp = pm.queryIntentActivities(home, 0)
            .firstOrNull { it.activityInfo.packageName == target }
            ?.let { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
        if (comp == null) { Log.w(TAG, "ensureHome: $target has no HOME activity"); return }
        Log.i(TAG, "ensureHome: default home was '$current' -> $comp")
        setHomeActivity(comp)
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

    /**
     * Report the dashboard app's state for the watchdog: [AppState.FG] (alive + focused), [AppState.BG]
     * (alive but not focused), [AppState.DEAD] (no process), or [AppState.UNKNOWN] (can't tell — no
     * root and no daemon, or no dashboard installed). Prefers the daemon's `APPSTATE` verb; falls back
     * to `su` (`pidof` for liveness, `dumpsys window` focus for foreground). `pidof … ; true` keeps the
     * shell exit 0 so a *blank* reply means "dead" while a *null* reply means su itself was unavailable.
     */
    fun dashboardState(dashboardPkg: String): AppState {
        val pkg = resolveDashboard(dashboardPkg)
        if (pkg.isBlank()) return AppState.UNKNOWN
        if (HelperClient.available()) {
            return when (HelperClient.send("APPSTATE $pkg")) {
                "FG" -> AppState.FG
                "BG" -> AppState.BG
                "DEAD" -> AppState.DEAD
                else -> AppState.UNKNOWN
            }
        }
        val pid = Su.runOutput("pidof $pkg 2>/dev/null; true") ?: return AppState.UNKNOWN
        if (pid.isBlank()) return AppState.DEAD
        val focus = Su.runOutput("dumpsys window 2>/dev/null | grep mCurrentFocus") ?: ""
        return if (focus.contains("$pkg/")) AppState.FG else AppState.BG
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
