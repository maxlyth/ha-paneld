package io.github.maxlyth.hapaneld.control

import io.github.maxlyth.hapaneld.device.DeviceProfile

/**
 * On-board relay + button-LED control for panels that expose them — currently the **Smatek S9E**,
 * which has two mains relays at `/sys/class/st_relay/relay1`/`relay2` and four button LEDs at
 * `/sys/class/gpio/gpio147`–`gpio150`, all driven by `echo 1`/`echo 0`. The sysfs locations come from
 * the active [DeviceProfile] ([DeviceProfile.relayBase] / [DeviceProfile.buttonLedGpioBase]); a profile
 * with neither (every panel except the S9E) makes this controller inert with no su probes. Each node's
 * presence is still runtime-confirmed, so the entities appear only where the hardware really is.
 *
 * The nodes are root-owned, so writes go through [Su]. On a panel without `su` the capability simply
 * doesn't activate (graceful — like the other root-gated controllers).
 *
 * ⚠️ UNTESTED on hardware — derived from the vendor paths reported in
 * seaky/nspanel_pro_tools_apk#98; no S9E was available to validate. These switch **mains loads**, so
 * treat as experimental until confirmed on a real unit.
 */
class RelayController(profile: DeviceProfile = DeviceProfile.detect()) {

    private val base: String? = profile.relayBase
    private val ledBase: Int? = profile.buttonLedGpioBase

    /** Number of relays exposed (0 if the profile declares no relay base). */
    fun count(): Int {
        val base = base ?: return 0
        val out = Su.runOutput("ls $base 2>/dev/null") ?: return 0
        return out.split(Regex("\\s+")).count { it.matches(Regex("relay\\d+")) }
    }

    fun available(): Boolean = count() > 0

    /** Set relay [n] (1-based) on/off. Returns true if the write ran. */
    fun set(n: Int, on: Boolean): Boolean {
        val base = base ?: return false
        return Su.run("echo ${if (on) 1 else 0} > $base/relay$n")
    }

    /** Current state of relay [n], or false if unreadable. */
    fun get(n: Int): Boolean {
        val base = base ?: return false
        return Su.runOutput("cat $base/relay$n 2>/dev/null")?.trim() == "1"
    }

    // --- S9E button LEDs: gpio <buttonLedGpioBase..+3>, on/off via su ---

    /** Number of button LEDs present (0–4); 0 when the profile declares no button-LED base. */
    fun ledCount(): Int {
        if (ledBase == null) return 0
        return (0 until 4).count { exists(ledNode(it)) }
    }

    /** Set button LED [i] (0-based, F1..F4) on/off. */
    fun ledSet(i: Int, on: Boolean): Boolean {
        val node = ledNode(i) ?: return false
        return Su.run("echo ${if (on) 1 else 0} > $node")
    }

    /** Current state of button LED [i], or false if unreadable. */
    fun ledGet(i: Int): Boolean {
        val node = ledNode(i) ?: return false
        return Su.runOutput("cat $node 2>/dev/null")?.trim() == "1"
    }

    private fun ledNode(i: Int): String? = ledBase?.let { "/sys/class/gpio/gpio${it + i}/value" }
    private fun exists(p: String?): Boolean =
        p != null && Su.runOutput("ls $p 2>/dev/null")?.trim()?.isNotEmpty() == true
}
