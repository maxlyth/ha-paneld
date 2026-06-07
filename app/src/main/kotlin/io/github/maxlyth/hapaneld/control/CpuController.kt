package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.device.DeviceProfile

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
 * when the profile has no mapping (or the mapped governor isn't offered). Writes need root, so this is a
 * [Su] path: present on su-reachable panels (NSPanel Pro PX30, WF1589T), absent on sandbox-walled
 * panels (TPA10) where the select simply doesn't appear.
 */
class CpuController(private val profile: DeviceProfile = DeviceProfile.detect()) {

    /** Governors the kernel offers (e.g. [powersave, performance, schedutil]); empty if unreadable. */
    fun governors(): List<String> =
        Su.runOutput("cat $AVAIL 2>/dev/null")?.trim()
            ?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()

    /** True when governors are readable via su — i.e. we can also set them. */
    fun available(): Boolean = governors().isNotEmpty()

    /** Current raw governor (cpu0), or null if unreadable. */
    private fun gov(): String? = Su.runOutput("cat $GOV0 2>/dev/null")?.trim()?.takeIf { it.isNotEmpty() }

    /** Apply [g] to every core. Returns true if the write ran. */
    private fun set(g: String): Boolean {
        // Governor names are lowercase letters (+ digits in a few BSPs) — sanitise to keep the write safe.
        if (!g.matches(Regex("[a-z0-9_]+"))) return false
        return Su.run("for f in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do echo $g > \$f; done")
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

    /** Apply the governor for a friendly [tier]. Returns true if the write ran. */
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
        private const val GOV0 = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"
        private const val AVAIL = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors"
    }
}
