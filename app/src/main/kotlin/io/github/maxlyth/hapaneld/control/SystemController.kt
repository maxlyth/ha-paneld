package io.github.maxlyth.hapaneld.control

import android.os.SystemClock
import android.util.Log
import io.github.maxlyth.hapaneld.platform.Daemon
import io.github.maxlyth.hapaneld.platform.RootShell
import io.github.maxlyth.hapaneld.platform.SystemEnv
import io.github.maxlyth.hapaneld.util.AndroidInput
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
    // Foreground state of the built-in renderer (our own activity) — seamed so the `builtin` dashboard
    // path stays unit-testable without loading an Android Activity. Defaults to the live process flag.
    private val builtinForeground: () -> Boolean = { BuiltinDashboard.foreground },
) {

    private enum class PrivilegedStartResult { STARTED, BLOCKED, FAILED }

    /** Launch an activity [component] ("pkg/cls") via the privileged path. BUSY is an explicit helper
     * safety boundary: never bypass it through su or an in-process background activity start. */
    private fun privilegedStart(component: String): PrivilegedStartResult {
        if (!AndroidInput.isComponent(component)) return PrivilegedStartResult.FAILED
        return when (daemon.send("START $component")) {
            "OK" -> PrivilegedStartResult.STARTED
            "BUSY" -> PrivilegedStartResult.BLOCKED
            else -> if (root.run("am start -n $component")) {
                PrivilegedStartResult.STARTED
            } else {
                PrivilegedStartResult.FAILED
            }
        }
    }

    /** True when [pkg] selects ha-paneld's own built-in WebView renderer rather than a foreign app.
     *  Our own package name is treated as the sentinel too: some callers resolve the renderer to a real
     *  package (e.g. for perf attribution), and letting it fall through to the foreign-app paths would
     *  `am force-stop` ha-paneld itself — killing the service, MQTT and the web UI. */
    private fun isBuiltin(pkg: String) = pkg == BUILTIN_DASHBOARD || pkg == env.ownPackage

    /** Bring the built-in DashboardActivity up (or, if already running, to the foreground — a singleTask
     *  relaunch reaches onNewIntent, which only reloads when [BuiltinDashboard.requestReload] was set).
     *  Refuses while the renderer is crash-latched so the kiosk/watchdog return loops can't churn a
     *  crash-looping WebView; an explicit reload clears the latch first and always proceeds. */
    private fun startBuiltin() {
        if (BuiltinDashboard.rendererLatched(SystemClock.elapsedRealtime())) {
            Log.w(TAG, "builtin renderer crash-latched — refusing automatic relaunch (explicit reload clears it)")
            return
        }
        val comp = "${env.ownPackage}/.DashboardActivity"
        if (privilegedStart(comp) == PrivilegedStartResult.FAILED) env.directStart(comp)
        Log.i(TAG, "builtin dashboard -> $comp")
    }

    /** Configured dashboard pkg, else the installed HA Companion (this is an HA project). The sentinel
     *  [BUILTIN_DASHBOARD] passes through unchanged (it's non-blank) — downstream methods special-case it.
     *  Public so the config UI can show what a blank ("auto") dashboard_package actually resolved to. */
    fun resolveDashboard(pkg: String): String {
        if (pkg.isNotBlank()) return pkg.takeIf(AndroidInput::isDashboardTarget).orEmpty()
        for (p in listOf("io.homeassistant.companion.android.minimal", "io.homeassistant.companion.android")) {
            if (env.isInstalled(p)) return p
        }
        return ""
    }

    /** Force-stop the dashboard and relaunch it. */
    fun reloadDashboard(dashboardPkg: String) {
        val pkg = resolveDashboard(dashboardPkg)
        if (CompanionDataOperationGate.blocks(pkg)) {
            Log.i(TAG, "dashboard reload suppressed while Companion data operation owns $pkg")
            return
        }
        // Built-in renderer: an explicit reload clears any crash latch (deliberate retry consent), flags
        // the relaunch as reload-intent, and reaches onNewIntent → fresh page load.
        if (isBuiltin(pkg)) {
            BuiltinDashboard.clearRendererLatch()
            BuiltinDashboard.requestReload()
            startBuiltin()
            return
        }
        if (!AndroidInput.isPackage(pkg)) { Log.w(TAG, "reload: invalid or missing dashboard package"); return }
        val daemonReply = daemon.send("RELOAD $pkg")
        if (daemonReply == "BUSY") {
            Log.i(TAG, "dashboard reload refused while helper owns Companion data")
            return
        }
        val route = ShortOperationRouter.effect(
            EffectAttempt(PrivilegeRoute.DAEMON) { daemonReply == "OK" },
            EffectAttempt(PrivilegeRoute.SU) {
                if (!root.run("am force-stop $pkg")) return@EffectAttempt false
                val comp = env.launchComponent(pkg)
                if (comp == null) return@EffectAttempt root.run("monkey -p $pkg 1")
                when (privilegedStart(comp)) {
                    PrivilegedStartResult.STARTED,
                    PrivilegedStartResult.BLOCKED -> true
                    PrivilegedStartResult.FAILED -> root.run("monkey -p $pkg 1")
                }
            },
        )
        when (route) {
            PrivilegeRoute.DAEMON -> Log.i(TAG, "reload via daemon ($pkg)")
            PrivilegeRoute.SU -> Log.i(TAG, "reload via su fallback ($pkg)")
            else -> Log.w(TAG, "reload: helper and su both failed")
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
        if (privilegedStart(comp) == PrivilegedStartResult.FAILED) env.directStart(comp)
        Log.i(TAG, "launcher -> $comp")
    }

    /** The launcher package [launchLauncher] would land on for [configuredPkg] (query-only) — lets
     *  the config UI show what a blank ("auto") launcher_package actually resolved to. */
    fun resolvedLauncher(configuredPkg: String): String? = pickLauncher(configuredPkg)?.pkg

    private fun pickLauncher(configuredPkg: String): io.github.maxlyth.hapaneld.platform.ActivityRef? {
        // Our package exposes more than one HOME activity. PackageManager ordering is not a contract,
        // so an explicit "ha-paneld" selection must never accidentally resolve to DashboardActivity.
        // It means the on-demand panel-admin app drawer, regardless of the order returned below.
        if (isAdminLauncherSelection(configuredPkg)) return adminLauncherActivity()
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
        val comp = adminLauncherActivity().component
        if (privilegedStart(comp) == PrivilegedStartResult.FAILED) env.directStart(comp)
        Log.i(TAG, "admin launcher -> $comp")
    }

    /** True when Launcher app explicitly selects ha-paneld's panel-admin app drawer. */
    fun isAdminLauncherSelection(configuredPkg: String): Boolean = configuredPkg == env.ownPackage

    private fun adminLauncherActivity() =
        io.github.maxlyth.hapaneld.platform.ActivityRef(env.ownPackage, ".AdminLauncherActivity")

    /** Whether ha-paneld currently owns Android HOME. Android 14's HOME role is package-granular when
     * one package has multiple HOME activities and may resolve DashboardActivity even after accepting
     * AdminLauncherActivity. DashboardActivity routes real HOME intents to Panel admin when explicitly
     * selected, so package ownership is the durable enforcement condition across platform versions. */
    fun isAdminLauncherHome(): Boolean {
        val current = env.defaultHome() ?: return false
        return current.pkg == env.ownPackage
    }

    /** Force ha-paneld's admin launcher to be Android HOME. Unlike [ensureDashboardHome], this is an
     * explicit override policy: it deliberately reclaims HOME from vendor and third-party launchers.
     * The command is issued only on drift, making this safe for an immediate save, startup, and a
     * periodic vendor-firmware drift check. Returns true when HOME was already correct or the repair
     * command was accepted. */
    fun ensureAdminLauncherHome(): Boolean = ensureAdminLauncherHomeWithRoute(null)

    internal fun ensureAdminLauncherHome(admittedRoute: PrivilegeRoute): Boolean =
        ensureAdminLauncherHomeWithRoute(admittedRoute)

    private fun ensureAdminLauncherHomeWithRoute(admittedRoute: PrivilegeRoute?): Boolean {
        if (isAdminLauncherHome()) return true
        val comp = adminLauncherActivity().component
        Log.i(TAG, "ensureHome(admin): default home was '${env.defaultHome()?.component}' -> $comp")
        return setHomeActivity(comp, admittedRoute)
    }

    /** Apply the HOME policy associated with Launcher app. Explicit ha-paneld selection force-enforces
     * [AdminLauncherActivity]; every other value restores the existing automatic dashboard policy.
     * Returns true only for the explicit policy, allowing the service to arm/cancel its drift check. */
    fun applyLauncherHomePolicy(
        configuredLauncherPkg: String,
        dashboardPkg: String,
        builtinReady: Boolean = true,
    ): Boolean {
        if (isAdminLauncherSelection(configuredLauncherPkg)) {
            ensureAdminLauncherHome()
            return true
        }
        ensureDashboardHome(dashboardPkg, builtinReady)
        return false
    }

    /** Set the default HOME (launcher) to [component] ("pkg/cls"). Daemon SETHOME, else su. */
    private fun setHomeActivity(
        component: String,
        admittedRoute: PrivilegeRoute? = null,
    ): Boolean {
        if (!AndroidInput.isComponent(component)) return false
        if (admittedRoute != null) return when (admittedRoute) {
            PrivilegeRoute.DAEMON -> daemon.send("SETHOME $component") == "OK"
            PrivilegeRoute.SU -> root.run("cmd package set-home-activity $component")
            PrivilegeRoute.SHIZUKU,
            PrivilegeRoute.ACCESSIBILITY -> false
        }
        return ShortOperationRouter.effect(
            EffectAttempt(PrivilegeRoute.DAEMON) { daemon.send("SETHOME $component") == "OK" },
            EffectAttempt(PrivilegeRoute.SU) { root.run("cmd package set-home-activity $component") },
        ) != null
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
    fun ensureDashboardHome(dashboardPkg: String, builtinReady: Boolean = true) {
        val target = resolveDashboard(dashboardPkg)
        // Only claim HOME for the built-in renderer once it's actually renderable (an HA URL is set) —
        // else DashboardActivity would be the home yet immediately hand off, churning HOME needlessly.
        if (isBuiltin(target)) { if (builtinReady) ensureBuiltinHome(); return }
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

    /** Make our built-in DashboardActivity the default home (parity with the Companion path): so the
     *  panel boots to it, the Home key returns to it, and it self-heals as a home app. Reclaims from an
     *  unowned ("android") resolver, ourselves, or a known dashboard renderer that ha-paneld itself set
     *  as home (the Companion — [ensureDashboardHome] made it the default on every existing panel, so
     *  switching to the built-in renderer must be able to take HOME back from it). A genuinely
     *  third-party launcher the user chose is still left alone. */
    private fun ensureBuiltinHome() {
        val comp = env.homeActivities().firstOrNull { it.pkg == env.ownPackage && it.cls.endsWith("DashboardActivity") }?.component
        if (comp == null) { Log.w(TAG, "ensureHome(builtin): DashboardActivity has no HOME activity"); return }
        val current = env.defaultHome()
        if (current?.component == comp) return                           // already correct
        val curPkg = current?.pkg
        if (curPkg != null && curPkg != "android" && curPkg != env.ownPackage && curPkg !in KNOWN_RENDERER_HOMES) return
        Log.i(TAG, "ensureHome(builtin): default home was '${current?.component}' -> $comp")
        setHomeActivity(comp)
    }

    /** Bring the dashboard (or the default home app) to the foreground. */
    fun launchHome(dashboardPkg: String) {
        val pkg = resolveDashboard(dashboardPkg)
        if (CompanionDataOperationGate.blocks(pkg)) {
            Log.i(TAG, "home launch suppressed while Companion data operation owns $pkg")
            return
        }
        if (isBuiltin(pkg)) { startBuiltin(); return }
        val comp = if (pkg.isNotBlank()) env.launchComponent(pkg) else env.defaultHome()?.component
        if (comp == null) { Log.w(TAG, "home: no target resolved"); return }
        if (privilegedStart(comp) == PrivilegedStartResult.FAILED) env.directStart(comp)
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
        // Built-in renderer lives in our always-running process: a direct lifecycle flag gives FG
        // (resumed) / BG (paused or not yet created) with no root/daemon probe — BG lets the kiosk/
        // watchdog return-loops recreate it via launchHome. Crash-latched (renderer crash-looping,
        // relaunch budget spent) reports DEAD: the kiosk loop ignores DEAD, and the watchdog's existing
        // crash-loop backoff + health warning take over instead of a 1.2s relaunch churn.
        if (isBuiltin(pkg)) return when {
            BuiltinDashboard.rendererLatched(SystemClock.elapsedRealtime()) -> AppState.DEAD
            builtinForeground() -> AppState.FG
            else -> AppState.BG
        }
        if (!AndroidInput.isPackage(pkg)) return AppState.UNKNOWN
        return ShortOperationRouter.value(
            ValueAttempt(PrivilegeRoute.DAEMON) {
                when (daemon.send("APPSTATE $pkg")) {
                    "FG" -> AppState.FG
                    "BG" -> AppState.BG
                    "DEAD" -> AppState.DEAD
                    else -> null
                }
            },
            ValueAttempt(PrivilegeRoute.SU) {
                val pid = root.runOutput("pidof $pkg 2>/dev/null; true") ?: return@ValueAttempt null
                if (pid.isBlank()) return@ValueAttempt AppState.DEAD
                val focus = root.runOutput("dumpsys window 2>/dev/null | grep mCurrentFocus")
                    ?: return@ValueAttempt null
                if (focus.contains("$pkg/")) AppState.FG else AppState.BG
            }
        )?.value ?: AppState.UNKNOWN
    }

    fun reboot() {
        val route = ShortOperationRouter.effect(
            EffectAttempt(PrivilegeRoute.DAEMON) { daemon.send("REBOOT") == "OK" },
            EffectAttempt(PrivilegeRoute.SU) { root.fireAndForget("reboot") },
        )
        when (route) {
            PrivilegeRoute.DAEMON -> Log.i(TAG, "reboot via daemon")
            PrivilegeRoute.SU -> Log.i(TAG, "reboot via su fallback")
            else -> Log.w(TAG, "reboot: helper and su both unavailable")
        }
    }

    companion object {
        private const val TAG = "ha-paneld/system"

        /** Sentinel `dashboard_package` value selecting ha-paneld's own built-in WebView renderer
         *  ([io.github.maxlyth.hapaneld.DashboardActivity]) instead of a foreign dashboard app. */
        const val BUILTIN_DASHBOARD = "builtin"

        /** Dashboard renderers ha-paneld itself may have set as the default home ([ensureDashboardHome])
         *  — [ensureBuiltinHome] is allowed to reclaim HOME from these when switching to the built-in
         *  renderer; anything else as home is a deliberate third-party launcher and is left alone. */
        internal val KNOWN_RENDERER_HOMES = setOf(
            "io.homeassistant.companion.android.minimal",
            "io.homeassistant.companion.android",
        )

        // Vendor kiosk apps that register CATEGORY_HOME but aren't real launchers — the navbar Launcher
        // button must never land on them (they obstruct the dashboard). eWeLink's control panel on
        // Sonoff/NSPanel Pro is the known offender; add more here as other vendors surface.
        private val VENDOR_PSEUDO_LAUNCHERS = setOf("com.eWeLinkControlPanel")
    }
}
