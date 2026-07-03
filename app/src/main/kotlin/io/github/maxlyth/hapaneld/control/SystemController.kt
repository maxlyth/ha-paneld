package io.github.maxlyth.hapaneld.control

import android.util.Log
import io.github.maxlyth.hapaneld.platform.Daemon
import io.github.maxlyth.hapaneld.platform.RootShell
import io.github.maxlyth.hapaneld.platform.SystemEnv
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
 *
 * Collaborators are seamed — package/activity queries via [SystemEnv], privilege via [RootShell] /
 * [Daemon] — so the launcher-selection, default-home, and dashboard-state logic is unit-testable
 * without a device.
 */
class SystemController(
    private val env: SystemEnv,
    private val root: RootShell = Su,
    private val daemon: Daemon = HelperClient,
) {

    /** Launch an activity [component] ("pkg/cls") via the privileged path (daemon START, else su). */
    private fun privilegedStart(component: String): Boolean {
        if (component.isBlank()) return false
        // Prefer the daemon, but only trust an explicit OK — an older daemon without the START verb
        // replies ERR, so fall back to su (covers su-capable panels with a stale daemon).
        if (daemon.available() && daemon.send("START $component") == "OK") return true
        return root.run("am start -n $component")
    }

    /** Configured dashboard pkg, else the installed HA Companion (this is an HA project). Public so
     *  the config UI can show what a blank ("auto") dashboard_package actually resolved to. */
    fun resolveDashboard(pkg: String): String {
        if (pkg.isNotBlank()) return pkg
        for (p in listOf("io.homeassistant.companion.android.minimal", "io.homeassistant.companion.android")) {
            if (env.isInstalled(p)) return p
        }
        return ""
    }

    /** Force-stop the dashboard and relaunch it. */
    fun reloadDashboard(dashboardPkg: String) {
        val pkg = resolveDashboard(dashboardPkg)
        if (pkg.isBlank()) { Log.w(TAG, "reload: no dashboard pkg (set dashboard_package)"); return }
        if (daemon.available()) { // daemon force-stops + monkey-relaunches as root (no BAL)
            daemon.send("RELOAD $pkg")
            Log.i(TAG, "reload via daemon ($pkg)")
            return
        }
        if (root.run("am force-stop $pkg")) {
            val comp = env.launchComponent(pkg)
            if (comp == null || !privilegedStart(comp)) root.run("monkey -p $pkg 1")
            Log.i(TAG, "reload via su ($pkg)")
        } else {
            Log.w(TAG, "reload: neither daemon nor su available")
        }
    }

    /**
     * Bring a launcher (home screen) to the foreground — for panels with no physical home button.
     * [configuredPkg] forces a package; blank => first registered HOME launcher that isn't the
     * current default, ourselves, or settings. When nothing resolves (kiosk panels often have no
     * dedicated launcher app — the Companion registers HOME but is the dashboard, and the vendor
     * pseudo-launcher may be tamed/absent), falls back to our own admin launcher so the Launcher
     * key always lands somewhere; a stale configured package degrades the same way.
     */
    fun launchLauncher(configuredPkg: String) {
        val ri = pickLauncher(configuredPkg)
        if (ri == null) {
            Log.i(TAG, "launcher: none resolvable — opening the admin launcher")
            launchAdminLauncher()
            return
        }
        val comp = ri.component
        if (!privilegedStart(comp)) env.directStart(comp)
        Log.i(TAG, "launcher -> $comp")
    }

    /** The launcher package [launchLauncher] would land on for [configuredPkg] (query-only) — lets
     *  the config UI show what a blank ("auto") launcher_package actually resolved to. */
    fun resolvedLauncher(configuredPkg: String): String? = pickLauncher(configuredPkg)?.pkg

    private fun pickLauncher(configuredPkg: String): io.github.maxlyth.hapaneld.platform.ActivityRef? {
        val all = env.homeActivities()
        val default = env.defaultHome()?.pkg
        // Apps that register CATEGORY_HOME but are NOT an app-drawer launcher we'd want to land on:
        // ourselves, Settings, the HA Companion (a kiosk dashboard, which registers as HOME), and known
        // vendor kiosk pseudo-launchers (e.g. eWeLink's control panel on Sonoff panels) — landing on
        // those obstructs the dashboard instead of giving the user an app drawer.
        val notALauncher = { p: String ->
            p == env.ownPackage || p == "com.android.settings" ||
                p == "io.homeassistant.companion.android" || p == "io.homeassistant.companion.android.minimal" ||
                p in VENDOR_PSEUDO_LAUNCHERS
        }
        return when {
            configuredPkg.isNotBlank() -> all.firstOrNull { it.pkg == configuredPkg }
            // Prefer the actual default home when it's a real launcher (e.g. the vendor launcher) — the old
            // code always skipped the default and grabbed the first alternate, which on kiosk panels is the
            // HA Companion (registers as HOME) → opened the dashboard instead of a launcher (the bug).
            default != null && !notALauncher(default) -> all.firstOrNull { it.pkg == default }
            // Default IS a kiosk/dashboard (or us): fall back to any other real launcher.
            else -> all.firstOrNull { !notALauncher(it.pkg) && it.pkg != default }
        }
    }

    /**
     * Open ha-paneld's own on-demand admin launcher (an app drawer for panel admin). The default for
     * the navbar Launcher button — replaces landing on the vendor pseudo-launcher. Reached by explicit
     * component, so it works even when no other launcher is installed.
     */
    fun launchAdminLauncher() {
        val comp = "${env.ownPackage}/.AdminLauncherActivity"
        if (!privilegedStart(comp)) env.directStart(comp)
        Log.i(TAG, "admin launcher -> $comp")
    }

    /** Set the default HOME (launcher) to [component] ("pkg/cls"). Daemon SETHOME, else su. */
    private fun setHomeActivity(component: String): Boolean {
        if (component.isBlank()) return false
        if (daemon.available() && daemon.send("SETHOME $component") == "OK") return true
        return root.run("cmd package set-home-activity $component")
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
        val current = env.defaultHome()?.pkg
        if (current == target) return                                   // already correct
        // Respect a real third-party launcher the user chose; only reclaim from "no default" or ourselves.
        if (current != null && current != "android" && current != env.ownPackage) return
        val comp = env.homeActivities().firstOrNull { it.pkg == target }?.component
        if (comp == null) { Log.w(TAG, "ensureHome: $target has no HOME activity"); return }
        Log.i(TAG, "ensureHome: default home was '$current' -> $comp")
        setHomeActivity(comp)
    }

    /** Bring the dashboard (or the default home app) to the foreground. */
    fun launchHome(dashboardPkg: String) {
        val pkg = resolveDashboard(dashboardPkg)
        val comp = if (pkg.isNotBlank()) env.launchComponent(pkg) else env.defaultHome()?.component
        if (comp == null) { Log.w(TAG, "home: no target resolved"); return }
        if (!privilegedStart(comp)) env.directStart(comp)
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
        if (daemon.available()) {
            return when (daemon.send("APPSTATE $pkg")) {
                "FG" -> AppState.FG
                "BG" -> AppState.BG
                "DEAD" -> AppState.DEAD
                else -> AppState.UNKNOWN
            }
        }
        val pid = root.runOutput("pidof $pkg 2>/dev/null; true") ?: return AppState.UNKNOWN
        if (pid.isBlank()) return AppState.DEAD
        val focus = root.runOutput("dumpsys window 2>/dev/null | grep mCurrentFocus") ?: ""
        return if (focus.contains("$pkg/")) AppState.FG else AppState.BG
    }

    fun reboot() {
        if (daemon.available()) {
            daemon.send("REBOOT")
            Log.i(TAG, "reboot via daemon")
            return
        }
        Log.i(TAG, "reboot via su")
        root.fireAndForget("reboot")
    }

    companion object {
        private const val TAG = "ha-paneld/system"

        // Vendor kiosk apps that register CATEGORY_HOME but aren't real launchers — the navbar Launcher
        // button must never land on them (they obstruct the dashboard). eWeLink's control panel on
        // Sonoff/NSPanel Pro is the known offender; add more here as other vendors surface.
        private val VENDOR_PSEUDO_LAUNCHERS = setOf("com.eWeLinkControlPanel")
    }
}
