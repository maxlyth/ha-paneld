package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.metrics.PanelMetrics
import io.github.maxlyth.hapaneld.platform.Daemon
import io.github.maxlyth.hapaneld.platform.RootShell
import io.github.maxlyth.hapaneld.util.HelperClient

/**
 * CPU scaling-governor control, exposed to HA as three intent-based tiers rather than raw kernel
 * governor names (which mean nothing to most users):
 *   - Performance — `performance`: max clocks always. Snappy, but constant parasitic draw on an
 *     always-on panel.
 *   - Efficiency  — `powersave`: coolest/quietest, minimal idle draw; sluggish dashboards.
 *   - Auto        — a dynamic governor (schedutil/interactive/ondemand) that ramps up under load
 *     (dashboard interaction) and idles low. The sensible default for a mains-powered, 24/7 panel:
 *     fast when someone's using it, minimal waste when no one is.
 *
 * Each tier maps to a kernel governor via [DeviceProfile.cpuGovernors] — Auto differs by SoC (schedutil
 * on rk3566/rk3576, interactive on PX30) — and falls back to resolving from the runtime-available list
 * when the profile has no mapping. Governors are **read** through the shared [PanelMetrics] reader (the
 * cpufreq sysfs is world-readable, so its direct→su strategy reads directly on every panel); the *write*
 * needs root. [DeviceProfile.appCanSu] orders the live attempts: su first on known su-reachable panels,
 * helper first on sandbox-walled panels. A failed preferred route always falls through to the other.
 */
class CpuController(
    private val profile: DeviceProfile = DeviceProfile.detect(),
    private val root: RootShell = Su,
    private val daemon: Daemon = HelperClient,
    private val metrics: PanelMetrics = PanelMetrics.shared,
) {

    /** Governors the kernel offers (e.g. [powersave, performance, schedutil]); empty if unreadable. */
    fun governors(): List<String> =
        metrics.cpuAvailableGovernors()?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()

    /** True when governors are readable. A later set can still fail if neither privileged route works. */
    fun available(): Boolean = governors().isNotEmpty()

    /** Current raw governor (cpu0), or null if unreadable. */
    private fun gov(): String? = metrics.cpuGovernor()?.trim()?.takeIf { it.isNotEmpty() }

    /** Apply [g] to every core. Returns true only when one live route reports success. */
    private fun set(g: String): Boolean {
        // Governor names are lowercase letters (+ digits in a few BSPs) — sanitise to keep the write safe.
        if (!g.matches(Regex("[a-z0-9_]+"))) return false
        val su = EffectAttempt(PrivilegeRoute.SU) {
            root.run(
                "found=0; failed=0; for f in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; " +
                    "do found=1; { echo $g > \"\$f\"; } 2>/dev/null || failed=1; done; " +
                    "[ \"\$found\" -eq 1 ] && [ \"\$failed\" -eq 0 ]"
            )
        }
        val helper = EffectAttempt(PrivilegeRoute.DAEMON) { daemon.send("GOV $g") == "OK" }
        val attempts = if (profile.appCanSu) arrayOf(su, helper) else arrayOf(helper, su)
        return ShortOperationRouter.effect(*attempts) != null
    }

    /** Resolve a friendly [tier] to a kernel governor: profile default if the SoC offers it, else from
     *  the available list (Auto = best dynamic governor). Null only if nothing is available. */
    private fun govFor(tier: String): String? {
        val avail = governors()
        profile.cpuGovernors?.get(tier)?.let { if (it in avail) return it }
        return when (tier) {
            PERFORMANCE -> "performance".takeIf { it in avail }
            EFFICIENCY -> "powersave".takeIf { it in avail }
            AUTO -> DYNAMICS.firstOrNull { it in avail }
            else -> null
        } ?: avail.firstOrNull()
    }

    /** Apply the governor for a friendly [tier]. Returns true when one privileged route succeeds. */
    fun setTier(tier: String): Boolean = govFor(tier)?.let { set(it) } ?: false

    /** The current tier, reverse-mapped from the live governor (any dynamic governor reads as Auto). */
    fun currentTier(): String? = when (gov()) {
        null -> null
        "performance" -> PERFORMANCE
        "powersave" -> EFFICIENCY
        else -> AUTO
    }

    companion object {
        const val PERFORMANCE = "Performance"
        const val EFFICIENCY = "Efficiency"
        const val AUTO = "Auto"

        /** The HA-facing options, in order. */
        val TIERS = listOf(PERFORMANCE, EFFICIENCY, AUTO)

        // Dynamic (load-following) governors, best first — used to resolve Auto when the profile doesn't.
        private val DYNAMICS = listOf("schedutil", "interactive", "ondemand", "conservative")
    }
}
