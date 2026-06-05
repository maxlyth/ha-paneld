package io.github.maxlyth.hapaneld.control

/**
 * CPU scaling-governor control — a perf/thermal lever for weak panels. Reads the available governors
 * from `scaling_available_governors` and applies a chosen one to every core. powersave runs cooler and
 * quieter but slows the dashboard; performance is snappier but hotter — and HA can automate the choice
 * (e.g. powersave when the room is empty).
 *
 * Writes need root, so this is a [Su] path: it lights up on panels where the app can reach `su`
 * (NSPanel Pro PX30, WF1589T). On sandbox-walled panels (TPA10) `su` fails and the capability simply
 * doesn't appear — a daemon-mediated path could add it later.
 */
class CpuController {

    /** Governors the kernel offers (e.g. [powersave, performance, schedutil]); empty if unreadable. */
    fun governors(): List<String> =
        Su.runOutput("cat $AVAIL 2>/dev/null")?.trim()
            ?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: emptyList()

    /** True when governors are readable via su — i.e. we can also set them. */
    fun available(): Boolean = governors().isNotEmpty()

    /** Current governor (cpu0), or null if unreadable. */
    fun get(): String? = Su.runOutput("cat $GOV0 2>/dev/null")?.trim()?.takeIf { it.isNotEmpty() }

    /** Apply [gov] to every core. Returns true if the write ran. */
    fun set(gov: String): Boolean {
        // Governor names are lowercase letters (+ digits in a few BSPs) — sanitise to keep the write safe.
        if (!gov.matches(Regex("[a-z0-9_]+"))) return false
        return Su.run("for f in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do echo $gov > \$f; done")
    }

    companion object {
        private const val GOV0 = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor"
        private const val AVAIL = "/sys/devices/system/cpu/cpu0/cpufreq/scaling_available_governors"
    }
}
