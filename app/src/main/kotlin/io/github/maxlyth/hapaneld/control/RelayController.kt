package io.github.maxlyth.hapaneld.control

/**
 * On-board relay control for panels that expose a `st_relay` sysfs class — currently the **Smatek
 * S9E**, which has two mains relays at `/sys/class/st_relay/relay1` and `relay2` (`echo 1`/`echo 0`).
 * The presence of the `st_relay` class is the device fingerprint, so the relay entities appear only
 * on a panel that actually has them.
 *
 * The nodes are root-owned, so writes go through [Su]. On a panel without `su` reachable from the app
 * sandbox the capability simply doesn't activate (graceful — like the other root-gated controllers).
 *
 * ⚠️ UNTESTED on hardware — derived from the vendor paths reported in
 * seaky/nspanel_pro_tools_apk#98; no S9E was available to validate. These switch **mains loads**, so
 * treat as experimental until confirmed on a real unit.
 */
class RelayController {

    /** Number of relays exposed (0 if the panel has no `st_relay` class). */
    fun count(): Int {
        val out = Su.runOutput("ls $BASE 2>/dev/null") ?: return 0
        return out.split(Regex("\\s+")).count { it.matches(Regex("relay\\d+")) }
    }

    fun available(): Boolean = count() > 0

    /** Set relay [n] (1-based) on/off. Returns true if the write ran. */
    fun set(n: Int, on: Boolean): Boolean =
        Su.run("echo ${if (on) 1 else 0} > $BASE/relay$n")

    /** Current state of relay [n], or false if unreadable. */
    fun get(n: Int): Boolean = Su.runOutput("cat $BASE/relay$n 2>/dev/null")?.trim() == "1"

    companion object {
        private const val BASE = "/sys/class/st_relay"
    }
}
