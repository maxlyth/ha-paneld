package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.platform.ActivityRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SystemController over the seamed collaborators ([io.github.maxlyth.hapaneld.platform.SystemEnv],
 * RootShell, Daemon) — no device. Covers the daemon→su tier selection, the launcher-selection
 * algorithm (incl. the kiosk-default bug fix), the ensureDashboardHome reclaim rules, and the
 * dashboardState parsing.
 */
class SystemControllerTest {
    /** [BuiltinDashboard] is a process-global object — reset its mutable state so tests can't leak
     *  a crash latch / pending reload / navigate into each other. */
    @Before fun resetBuiltinDashboard() {
        BuiltinDashboard.clearRendererLatch()
        BuiltinDashboard.consumeReloadRequest()
        BuiltinDashboard.navPath = null
        BuiltinDashboard.foreground = false
    }

    private val OWN = "io.github.maxlyth.hapaneld"
    private val MIN = "io.homeassistant.companion.android.minimal" // HA Companion (minimal)
    private val VENDOR = "com.vendor.launcher"

    /** Build a controller. [daemon] = null means the daemon is unavailable (su path); a map (even empty)
     *  means available with those exact-command replies. [su] is the su `run` result; [suOut] feeds runOutput. */
    private fun sc(
        env: FakeSystemEnv,
        daemon: Map<String, String>? = null,
        su: Boolean = true,
        suOut: Map<String, String> = emptyMap(),
        builtinForeground: Boolean = false,
    ): Triple<SystemController, FakeRootShell, FakeDaemon> {
        val root = FakeRootShell(outputs = suOut, runResult = su)
        val d = FakeDaemon(replies = daemon ?: emptyMap(), available = daemon != null)
        return Triple(SystemController(env, root, d, builtinForeground = { builtinForeground }), root, d)
    }

    private val BUILTIN = SystemController.BUILTIN_DASHBOARD
    private val DASH_HOME = ActivityRef(OWN, "io.github.maxlyth.hapaneld.DashboardActivity")

    // ---------- reboot ----------
    @Test fun rebootPrefersDaemon() {
        val (c, root, d) = sc(FakeSystemEnv(), daemon = emptyMap())
        c.reboot()
        assertTrue("daemon REBOOT sent", d.sent.contains("REBOOT"))
        assertTrue("no su when daemon present", root.ran.isEmpty())
    }

    @Test fun rebootFallsToSu() {
        val (c, root, _) = sc(FakeSystemEnv(), daemon = null)
        c.reboot()
        assertTrue("su reboot fired, got ${root.ran}", root.ran.contains("reboot"))
    }

    // ---------- resolveDashboard / dashboardState ----------
    @Test fun dashboardStateUnknownWhenNoDashboardInstalled() =
        assertEquals(AppState.UNKNOWN, sc(FakeSystemEnv(), daemon = null).first.dashboardState(""))

    @Test fun dashboardStateResolvesCompanionWhenPkgBlank() {
        // blank configured pkg → resolveDashboard finds the installed Companion, then queries it.
        val env = FakeSystemEnv(installed = setOf(MIN))
        val (c, _, d) = sc(env, daemon = mapOf("APPSTATE $MIN" to "FG"))
        assertEquals(AppState.FG, c.dashboardState(""))
        assertTrue(d.sent.contains("APPSTATE $MIN"))
    }

    @Test fun dashboardStateDaemonRepliesMapped() {
        fun state(reply: String) = sc(FakeSystemEnv(installed = setOf(MIN)), daemon = mapOf("APPSTATE $MIN" to reply)).first.dashboardState(MIN)
        assertEquals(AppState.FG, state("FG"))
        assertEquals(AppState.BG, state("BG"))
        assertEquals(AppState.DEAD, state("DEAD"))
        assertEquals("unknown reply → UNKNOWN", AppState.UNKNOWN, state("ERR"))
    }

    @Test fun dashboardStateViaSuPidofAndFocus() {
        // pidof present + focus contains pkg/ → FG; not focused → BG; blank pidof → DEAD; no su → UNKNOWN.
        fun state(out: Map<String, String>) = sc(FakeSystemEnv(installed = setOf(MIN)), daemon = null, suOut = out).first.dashboardState(MIN)
        assertEquals(AppState.FG, state(mapOf("pidof" to "1234", "dumpsys window" to "mCurrentFocus=Window{$MIN/$MIN.WebViewActivity}")))
        assertEquals(AppState.BG, state(mapOf("pidof" to "1234", "dumpsys window" to "mCurrentFocus=Window{com.android.launcher/X}")))
        assertEquals(AppState.DEAD, state(mapOf("pidof" to "")))
        assertEquals("no su output → UNKNOWN", AppState.UNKNOWN, state(emptyMap()))
    }

    // ---------- reloadDashboard ----------
    @Test fun reloadPrefersDaemon() {
        val (c, root, d) = sc(FakeSystemEnv(installed = setOf(MIN)), daemon = emptyMap())
        c.reloadDashboard(MIN)
        assertTrue("daemon RELOAD sent", d.sent.contains("RELOAD $MIN"))
        assertTrue("no su when daemon present", root.ran.isEmpty())
    }

    @Test fun reloadViaSuForceStopThenPrivilegedStart() {
        val env = FakeSystemEnv(installed = setOf(MIN), launchers = mapOf(MIN to "$MIN/.Main"))
        val (c, root, _) = sc(env, daemon = null, su = true)
        c.reloadDashboard(MIN)
        assertTrue("force-stop ran", root.ran.contains("am force-stop $MIN"))
        assertTrue("privileged start ran", root.ran.contains("am start -n $MIN/.Main"))
        assertFalse("monkey not used when start succeeds", root.ran.any { it.startsWith("monkey") })
    }

    @Test fun reloadFallsToMonkeyWithoutLaunchComponent() {
        val (c, root, _) = sc(FakeSystemEnv(installed = setOf(MIN)), daemon = null, su = true)
        c.reloadDashboard(MIN)
        assertTrue("monkey relaunch, got ${root.ran}", root.ran.contains("monkey -p $MIN 1"))
    }

    @Test fun reloadNoopWhenNoDashboard() {
        val (c, root, d) = sc(FakeSystemEnv(), daemon = null)
        c.reloadDashboard("")
        assertTrue(root.ran.isEmpty() && d.sent.isEmpty())
    }

    @Test fun invalidStoredDashboardNeverCrossesPrivilegeBoundary() {
        val injected = "com.example;reboot"
        val (c, root, d) = sc(FakeSystemEnv(), daemon = emptyMap())
        c.reloadDashboard(injected)
        assertEquals(AppState.UNKNOWN, c.dashboardState(injected))
        assertTrue(root.ran.isEmpty())
        assertTrue(d.sent.isEmpty())
    }

    // ---------- launchLauncher (the selection algorithm) ----------
    private fun launcherEnv(default: String?, vararg homes: String) = FakeSystemEnv(
        homes = homes.map { ActivityRef(it, "L") },
        default = default?.let { ActivityRef(it, "L") },
    )

    @Test fun launcherPrefersRealDefault() {
        val (c, root, _) = sc(launcherEnv(VENDOR, VENDOR, MIN), daemon = null)
        c.launchLauncher("")
        assertTrue("real default launcher chosen", root.ran.contains("am start -n $VENDOR/L"))
    }

    @Test fun launcherSkipsKioskCompanionDefault() {
        // The bug: default home is the Companion kiosk (registers HOME) → must fall through to a real launcher.
        val (c, root, _) = sc(launcherEnv(MIN, MIN, VENDOR), daemon = null)
        c.launchLauncher("")
        assertTrue("skipped kiosk default, chose real launcher", root.ran.contains("am start -n $VENDOR/L"))
    }

    @Test fun launcherSkipsEweLinkKioskDefault() {
        // eWeLink's control panel registers HOME but is vendor kiosk garbage, not a launcher — when it's
        // the default home, the Launcher button must fall through to a real launcher (a field report).
        val ewelink = "com.eWeLinkControlPanel"
        val (c, root, _) = sc(launcherEnv(ewelink, ewelink, VENDOR), daemon = null)
        c.launchLauncher("")
        assertTrue("skipped eWeLink kiosk, chose real launcher", root.ran.contains("am start -n $VENDOR/L"))
    }

    @Test fun launcherEweLinkOnlyHomeFallsBackToAdminLauncher() {
        // eWeLink the sole registered home → no real launcher, so open ha-paneld's admin launcher.
        val ewelink = "com.eWeLinkControlPanel"
        val (c, root, _) = sc(launcherEnv(ewelink, ewelink), daemon = null)
        c.launchLauncher("")
        assertTrue(
            "admin launcher opened when only eWeLink registers HOME",
            root.ran.contains("am start -n io.github.maxlyth.hapaneld/.AdminLauncherActivity"),
        )
    }

    @Test fun launcherHonoursConfiguredPkg() {
        val (c, root, _) = sc(launcherEnv(VENDOR, VENDOR, "com.other.home"), daemon = null)
        c.launchLauncher("com.other.home")
        assertTrue("configured launcher wins over default", root.ran.contains("am start -n com.other.home/L"))
    }

    @Test fun launcherNoneSuitableFallsBackToAdminLauncher() {
        // Only the Companion registers HOME (and it's the default) → no launcher app to land on, so
        // the key must open ha-paneld's own admin launcher instead of silently doing nothing.
        val (c, root, _) = sc(launcherEnv(MIN, MIN), daemon = null)
        c.launchLauncher("")
        assertTrue(
            "admin launcher opened as the fallback",
            root.ran.contains("am start -n io.github.maxlyth.hapaneld/.AdminLauncherActivity"),
        )
    }

    @Test fun launcherStaleConfiguredPkgFallsBackToAdminLauncher() {
        // A configured launcher that's no longer installed degrades the same way as none-resolvable.
        val (c, root, _) = sc(launcherEnv(MIN, MIN), daemon = null)
        c.launchLauncher("com.gone.launcher")
        assertTrue(
            "admin launcher opened for a stale configured package",
            root.ran.contains("am start -n io.github.maxlyth.hapaneld/.AdminLauncherActivity"),
        )
    }

    @Test fun launcherViaDaemonStart() {
        val (c, root, d) = sc(launcherEnv(VENDOR, VENDOR), daemon = mapOf("START $VENDOR/L" to "OK"))
        c.launchLauncher("")
        assertTrue("daemon START used", d.sent.contains("START $VENDOR/L"))
        assertTrue("no su when daemon START ok", root.ran.isEmpty())
    }

    @Test fun launcherFallsToDirectStartWhenPrivilegedFails() {
        val env = launcherEnv(VENDOR, VENDOR)
        val (c, _, _) = sc(env, daemon = null, su = false) // no daemon, su run fails
        c.launchLauncher("")
        assertTrue("direct-start fallback used, got ${env.directStarts}", env.directStarts.contains("$VENDOR/L"))
    }

    // ---------- launchAdminLauncher ----------
    @Test fun adminLauncherStartsOwnComponent() {
        val (c, root, _) = sc(FakeSystemEnv(), daemon = null, su = true)
        c.launchAdminLauncher()
        assertTrue(root.ran.contains("am start -n $OWN/.AdminLauncherActivity"))
    }

    @Test fun adminLauncherFallsToDirectStart() {
        val env = FakeSystemEnv()
        val (c, _, _) = sc(env, daemon = null, su = false)
        c.launchAdminLauncher()
        assertTrue(env.directStarts.contains("$OWN/.AdminLauncherActivity"))
    }

    // ---------- ensureDashboardHome (reclaim rules) ----------
    @Test fun ensureHomeNoopWhenNoDashboardInstalled() {
        val (c, root, d) = sc(FakeSystemEnv(), daemon = null)
        c.ensureDashboardHome("")
        assertTrue(root.ran.isEmpty() && d.sent.isEmpty())
    }

    @Test fun ensureHomeNoopWhenAlreadyDefault() {
        val env = FakeSystemEnv(installed = setOf(MIN), homes = listOf(ActivityRef(MIN, "Home")), default = ActivityRef(MIN, "Home"))
        val (c, root, d) = sc(env, daemon = null)
        c.ensureDashboardHome(MIN)
        assertTrue("already correct → no set-home", root.ran.isEmpty() && d.sent.isEmpty())
    }

    @Test fun ensureHomeRespectsThirdPartyLauncher() {
        val env = FakeSystemEnv(
            installed = setOf(MIN), homes = listOf(ActivityRef(MIN, "Home")), default = ActivityRef("com.thirdparty.home", "L"),
        )
        val (c, root, d) = sc(env, daemon = null)
        c.ensureDashboardHome(MIN)
        assertTrue("a deliberate 3rd-party home is left alone", root.ran.isEmpty() && d.sent.isEmpty())
    }

    @Test fun ensureHomeReclaimsFromAndroidResolverViaSu() {
        val env = FakeSystemEnv(installed = setOf(MIN), homes = listOf(ActivityRef(MIN, "Home")), default = ActivityRef("android", "Resolver"))
        val (c, root, _) = sc(env, daemon = null, su = true)
        c.ensureDashboardHome(MIN)
        assertTrue("reclaim from resolver via su", root.ran.contains("cmd package set-home-activity $MIN/Home"))
    }

    @Test fun ensureHomeReclaimsFromSelfViaDaemon() {
        val env = FakeSystemEnv(installed = setOf(MIN), homes = listOf(ActivityRef(MIN, "Home")), default = ActivityRef(OWN, ".AdminLauncherActivity"))
        val (c, _, d) = sc(env, daemon = mapOf("SETHOME $MIN/Home" to "OK"))
        c.ensureDashboardHome(MIN)
        assertTrue("reclaim from ourselves via daemon SETHOME", d.sent.contains("SETHOME $MIN/Home"))
    }

    @Test fun ensureHomeNoopWhenTargetHasNoHomeActivity() {
        val env = FakeSystemEnv(installed = setOf(MIN), homes = emptyList(), default = ActivityRef("android", "R"))
        val (c, root, d) = sc(env, daemon = null, su = true)
        c.ensureDashboardHome(MIN)
        assertTrue("target has no HOME activity → nothing set", root.ran.isEmpty() && d.sent.isEmpty())
    }

    // ---------- launchHome ----------
    @Test fun launchHomeUsesDashboardLaunchComponent() {
        val env = FakeSystemEnv(installed = setOf(MIN), launchers = mapOf(MIN to "$MIN/.Main"))
        val (c, root, _) = sc(env, daemon = null, su = true)
        c.launchHome(MIN)
        assertTrue(root.ran.contains("am start -n $MIN/.Main"))
    }

    @Test fun launchHomeUsesDefaultHomeWhenNoDashboard() {
        val env = FakeSystemEnv(default = ActivityRef(VENDOR, "L")) // nothing installed, blank pkg
        val (c, root, _) = sc(env, daemon = null, su = true)
        c.launchHome("")
        assertTrue(root.ran.contains("am start -n $VENDOR/L"))
    }

    @Test fun launchHomeNoTargetResolved() {
        val env = FakeSystemEnv() // nothing installed, no default
        val (c, root, _) = sc(env, daemon = null)
        c.launchHome("")
        assertTrue("no target → no start", root.ran.isEmpty() && env.directStarts.isEmpty())
    }

    // ---------- built-in renderer (dashboard_package = "builtin") ----------
    // The built-in renderer is ha-paneld's own DashboardActivity. resolveDashboard passes the sentinel
    // through, and dashboardState/launchHome/reloadDashboard/ensureDashboardHome special-case it so the
    // kiosk return-loop + watchdog drive it with no root/daemon probe and no foreign package.

    @Test fun builtinDashboardStateFromForegroundFlag() {
        // FG when our activity is resumed, BG otherwise (DEAD only while crash-latched — tested below).
        // No daemon/su probe is consulted.
        val env = FakeSystemEnv()
        assertEquals(AppState.FG, sc(env, daemon = null, builtinForeground = true).first.dashboardState(BUILTIN))
        assertEquals(AppState.BG, sc(env, daemon = null, builtinForeground = false).first.dashboardState(BUILTIN))
    }

    @Test fun builtinDashboardStateIgnoresDaemon() {
        // Even with a daemon available, the built-in path uses the lifecycle flag, not APPSTATE.
        val (c, _, d) = sc(FakeSystemEnv(), daemon = mapOf("APPSTATE $BUILTIN" to "DEAD"), builtinForeground = true)
        assertEquals(AppState.FG, c.dashboardState(BUILTIN))
        assertTrue("no APPSTATE probe for builtin", d.sent.isEmpty())
    }

    @Test fun builtinLaunchHomeStartsDashboardActivity() {
        val (c, root, _) = sc(FakeSystemEnv(), daemon = null, su = true)
        c.launchHome(BUILTIN)
        assertTrue("started our DashboardActivity", root.ran.contains("am start -n $OWN/.DashboardActivity"))
    }

    @Test fun builtinLaunchHomeFallsToDirectStart() {
        val env = FakeSystemEnv()
        val (c, _, _) = sc(env, daemon = null, su = false) // no daemon, su fails
        c.launchHome(BUILTIN)
        assertTrue("direct-start fallback", env.directStarts.contains("$OWN/.DashboardActivity"))
    }

    @Test fun builtinReloadRelaunchesActivity() {
        // No force-stop / monkey for builtin — a singleTask relaunch is the reload (onNewIntent).
        val (c, root, d) = sc(FakeSystemEnv(), daemon = mapOf("RELOAD $BUILTIN" to "OK"))
        c.reloadDashboard(BUILTIN)
        assertTrue("relaunch via START", d.sent.contains("START $OWN/.DashboardActivity"))
        assertFalse("no RELOAD force-stop path", d.sent.contains("RELOAD $BUILTIN"))
    }

    @Test fun builtinEnsureHomeSetsDashboardActivityFromResolver() {
        val env = FakeSystemEnv(homes = listOf(DASH_HOME), default = ActivityRef("android", "Resolver"))
        val (c, root, _) = sc(env, daemon = null, su = true)
        c.ensureDashboardHome(BUILTIN)
        assertTrue("set home to DashboardActivity", root.ran.contains("cmd package set-home-activity ${DASH_HOME.component}"))
    }

    @Test fun builtinEnsureHomeNoopWhenAlreadyDefault() {
        val env = FakeSystemEnv(homes = listOf(DASH_HOME), default = DASH_HOME)
        val (c, root, d) = sc(env, daemon = null, su = true)
        c.ensureDashboardHome(BUILTIN)
        assertTrue("already our home → nothing set", root.ran.isEmpty() && d.sent.isEmpty())
    }

    @Test fun builtinEnsureHomeRespectsThirdPartyLauncher() {
        val env = FakeSystemEnv(homes = listOf(DASH_HOME), default = ActivityRef("com.thirdparty.home", "L"))
        val (c, root, d) = sc(env, daemon = null, su = true)
        c.ensureDashboardHome(BUILTIN)
        assertTrue("a deliberate 3rd-party home is left alone", root.ran.isEmpty() && d.sent.isEmpty())
    }

    @Test fun builtinEnsureHomeReclaimsFromCompanion() {
        // ensureDashboardHome itself set the Companion as default home on every pre-0.9 panel — switching
        // to the built-in renderer must be able to take HOME back from it (it is NOT a third-party choice).
        val env = FakeSystemEnv(homes = listOf(DASH_HOME), default = ActivityRef(MIN, "Home"))
        val (c, root, _) = sc(env, daemon = null, su = true)
        c.ensureDashboardHome(BUILTIN)
        assertTrue("reclaimed home from the Companion", root.ran.contains("cmd package set-home-activity ${DASH_HOME.component}"))
    }

    @Test fun builtinReloadWithOwnPackageNameRoutesBuiltin() {
        // The navbar resolves the renderer to our own package name; the foreign-app path would
        // `am force-stop` ha-paneld itself (killing service+MQTT+web UI). Own package = builtin.
        val (c, root, d) = sc(FakeSystemEnv(), daemon = mapOf("START $OWN/.DashboardActivity" to "OK"))
        c.reloadDashboard(OWN)
        assertTrue("relaunch via START", d.sent.contains("START $OWN/.DashboardActivity"))
        assertFalse("no force-stop of ourselves", d.sent.any { it.startsWith("RELOAD") } || root.ran.any { it.contains("force-stop") })
    }

    // ---------- renderer crash latch (process-global budget) ----------

    private fun exhaustRebuildBudget() {
        repeat(BuiltinDashboard.MAX_REBUILDS) { assertTrue(BuiltinDashboard.consumeRebuildBudget(0L)) }
        assertFalse("budget exhausted → latch", BuiltinDashboard.consumeRebuildBudget(0L))
    }

    @Test fun builtinStateDeadWhileCrashLatched() {
        exhaustRebuildBudget()
        // DEAD hands the situation to the watchdog's crash-loop backoff; the kiosk loop ignores DEAD.
        assertEquals(AppState.DEAD, sc(FakeSystemEnv(), daemon = null, builtinForeground = true).first.dashboardState(BUILTIN))
    }

    @Test fun builtinLaunchRefusedWhileCrashLatched() {
        exhaustRebuildBudget()
        val env = FakeSystemEnv()
        val (c, root, d) = sc(env, daemon = emptyMap())
        c.launchHome(BUILTIN)
        assertTrue("no relaunch while latched", root.ran.isEmpty() && d.sent.isEmpty() && env.directStarts.isEmpty())
    }

    @Test fun builtinExplicitReloadClearsCrashLatch() {
        exhaustRebuildBudget()
        val (c, _, d) = sc(FakeSystemEnv(), daemon = mapOf("START $OWN/.DashboardActivity" to "OK"))
        c.reloadDashboard(BUILTIN) // deliberate reload = retry consent
        assertTrue("latch cleared, relaunch proceeds", d.sent.contains("START $OWN/.DashboardActivity"))
        assertTrue("reload intent flagged for onNewIntent", BuiltinDashboard.consumeReloadRequest())
        assertEquals("fresh budget after clear", true, BuiltinDashboard.consumeRebuildBudget(0L))
    }

    @Test fun rebuildBudgetWindowResets() {
        repeat(BuiltinDashboard.MAX_REBUILDS) { assertTrue(BuiltinDashboard.consumeRebuildBudget(0L)) }
        // Past the window the counter resets instead of latching — occasional crashes are fine.
        assertTrue(BuiltinDashboard.consumeRebuildBudget(BuiltinDashboard.REBUILD_WINDOW_MS + 1))
        assertFalse("latch not engaged by spaced crashes", BuiltinDashboard.rendererLatched(BuiltinDashboard.REBUILD_WINDOW_MS + 1))
    }

    @Test fun rendererLatchExpires() {
        exhaustRebuildBudget()
        assertTrue(BuiltinDashboard.rendererLatched(BuiltinDashboard.RENDERER_LATCH_MS - 1))
        assertFalse("latch expires → automatic relaunches resume", BuiltinDashboard.rendererLatched(BuiltinDashboard.RENDERER_LATCH_MS))
    }

    // ---------- one-shot navigate + reload-intent flags ----------

    @Test fun navPathConsumedOnce() {
        BuiltinDashboard.navPath = "office/dash"
        assertEquals("office/dash", BuiltinDashboard.consumeNavPath())
        assertNull("navigate is one-shot — later rebuilds return to home", BuiltinDashboard.consumeNavPath())
    }

    @Test fun reloadRequestConsumedOnce() {
        assertFalse("no reload pending by default (kiosk snap-back = foreground only)", BuiltinDashboard.consumeReloadRequest())
        BuiltinDashboard.requestReload()
        assertTrue(BuiltinDashboard.consumeReloadRequest())
        assertFalse(BuiltinDashboard.consumeReloadRequest())
    }
}
