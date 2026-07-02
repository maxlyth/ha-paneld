package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.platform.ActivityRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SystemController over the seamed collaborators ([io.github.maxlyth.hapaneld.platform.SystemEnv],
 * RootShell, Daemon) — no device. Covers the daemon→su tier selection, the launcher-selection
 * algorithm (incl. the kiosk-default bug fix), the ensureDashboardHome reclaim rules, and the
 * dashboardState parsing.
 */
class SystemControllerTest {
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
    ): Triple<SystemController, FakeRootShell, FakeDaemon> {
        val root = FakeRootShell(outputs = suOut, runResult = su)
        val d = FakeDaemon(replies = daemon ?: emptyMap(), available = daemon != null)
        return Triple(SystemController(env, root, d), root, d)
    }

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

    @Test fun launcherHonoursConfiguredPkg() {
        val (c, root, _) = sc(launcherEnv(VENDOR, VENDOR, "com.other.home"), daemon = null)
        c.launchLauncher("com.other.home")
        assertTrue("configured launcher wins over default", root.ran.contains("am start -n com.other.home/L"))
    }

    @Test fun launcherNoneSuitableDoesNothing() {
        // Only the Companion registers HOME (and it's the default) → nothing to land on.
        val (c, root, _) = sc(launcherEnv(MIN, MIN), daemon = null)
        c.launchLauncher("")
        assertTrue("no start attempted", root.ran.isEmpty())
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

    // ---------- isLaunchable ----------
    @Test fun isLaunchableReflectsLaunchComponent() {
        val c = sc(FakeSystemEnv(launchers = mapOf("com.x" to "com.x/.A")), daemon = null).first
        assertTrue(c.isLaunchable("com.x"))
        assertFalse("no launch component", c.isLaunchable("com.y"))
        assertFalse("blank", c.isLaunchable(""))
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
}
