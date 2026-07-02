package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.platform.RootShell

/**
 * On-board relay + button-LED control for panels that expose them — currently the **Smatek S9E**,
 * which has two mains relays (sysfs class `/sys/class/strelay` on firmware 1.1.0+, `/sys/class/st_relay`
 * on the initial 1.0.2 — both probed) and four button LEDs at `/sys/class/gpio/gpio147`–`gpio150`, all
 * driven by `echo 1`/`echo 0`. The sysfs locations come from the active [DeviceProfile]
 * ([DeviceProfile.relayBase] + [DeviceProfile.relayBaseFallbacks] / [DeviceProfile.buttonLedGpioBase]); a
 * profile with none (every panel except the S9E) makes this controller inert with no su probes. Each
 * node's presence is still runtime-confirmed, so the entities appear only where the hardware really is.
 * The button-LED pins aren't exported at boot, so they're exported on demand (see [ledCount]).
 *
 * The nodes are root-owned, so writes go through [Su]. On a panel without `su` the capability simply
 * doesn't activate (graceful — like the other root-gated controllers).
 *
 * ⚠️ UNTESTED on hardware — derived from the vendor paths reported in
 * seaky/nspanel_pro_tools_apk#98 + two stock firmware images; no S9E was available to validate. These
 * switch **mains loads**, so treat as experimental until confirmed on a real unit.
 */
class RelayController(profile: DeviceProfile = DeviceProfile.detect(), private val root: RootShell = Su) {

    // Candidate relay-class bases, primary first. The S9E renamed st_relay -> strelay across firmware,
    // so the profile supplies both; base() picks the first whose dir actually holds relayN nodes.
    private val candidateBases: List<String> = listOfNotNull(profile.relayBase) + profile.relayBaseFallbacks
    private val ledBase: Int? = profile.buttonLedGpioBase

    @Volatile private var resolvedBase: String? = null

    /** First candidate relay base whose dir holds `relayN` nodes (cached after the first probe), or null. */
    private fun base(): String? {
        resolvedBase?.let { return it }
        for (b in candidateBases) if (relayNodeCount(b) > 0) { resolvedBase = b; return b }
        return null
    }

    private fun relayNodeCount(b: String): Int {
        val out = root.runOutput("ls $b 2>/dev/null") ?: return 0
        return out.split(Regex("\\s+")).count { it.matches(Regex("relay\\d+")) }
    }

    /** Number of relays exposed (0 if no candidate relay base is present). */
    fun count(): Int = base()?.let { relayNodeCount(it) } ?: 0

    fun available(): Boolean = count() > 0

    /** Set relay [n] (1-based) on/off. Returns true if the write ran. */
    fun set(n: Int, on: Boolean): Boolean {
        val base = base() ?: return false
        return root.run("echo ${if (on) 1 else 0} > $base/relay$n")
    }

    /** Current state of relay [n], or false if unreadable. */
    fun get(n: Int): Boolean {
        val base = base() ?: return false
        return root.runOutput("cat $base/relay$n 2>/dev/null")?.trim() == "1"
    }

    // --- S9E button LEDs: gpio <buttonLedGpioBase..+3>, on/off via su ---

    /** Number of button LEDs present (0–4); 0 when the profile declares no button-LED base. The S9E does
     *  NOT export gpio147–150 at boot (init exports only gpio113), so each pin is exported here before
     *  probing — otherwise the `/value` nodes don't exist and the LEDs would never surface. */
    fun ledCount(): Int {
        val ledBase = ledBase ?: return 0
        return (0 until 4).count { ensureGpio(ledBase + it) }
    }

    /** Set button LED [i] (0-based, F1..F4) on/off. */
    fun ledSet(i: Int, on: Boolean): Boolean {
        val node = ledNode(i) ?: return false
        return root.run("echo ${if (on) 1 else 0} > $node")
    }

    /** Current state of button LED [i], or false if unreadable. */
    fun ledGet(i: Int): Boolean {
        val node = ledNode(i) ?: return false
        return root.runOutput("cat $node 2>/dev/null")?.trim() == "1"
    }

    private fun ledNode(i: Int): String? = ledBase?.let { "/sys/class/gpio/gpio${it + i}/value" }

    /** Export [gpio] if the kernel hasn't (the S9E exports only gpio113 at boot) and set it to output,
     *  both best-effort/idempotent, then report whether its `value` node is now present. */
    private fun ensureGpio(gpio: Int): Boolean {
        val dir = "/sys/class/gpio/gpio$gpio"
        root.run("[ -e $dir ] || echo $gpio > /sys/class/gpio/export 2>/dev/null; " +
            "[ -w $dir/direction ] && echo out > $dir/direction 2>/dev/null")
        return exists("$dir/value")
    }

    private fun exists(p: String?): Boolean =
        p != null && root.runOutput("ls $p 2>/dev/null")?.trim()?.isNotEmpty() == true
}
