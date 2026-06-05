package io.github.maxlyth.hapaneld.control

/**
 * On-board relay + button-LED control for panels that expose them — currently the **Smatek S9E**,
 * which has two mains relays at `/sys/class/st_relay/relay1`/`relay2` and four button LEDs at
 * `/sys/class/gpio/gpio147`–`gpio150` (= 16 + the F1–F4 keycodes), all driven by `echo 1`/`echo 0`.
 * Each node's presence is the fingerprint, so the entities appear only on a panel that has them.
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

    // --- S9E button LEDs: gpio 147-150 (16 + F1..F4 keycode), on/off via su ---

    /** Number of button LEDs present (0–4); 0 on panels without the gpio nodes. */
    fun ledCount(): Int = (0 until 4).count { exists(ledNode(it)) }

    /** Set button LED [i] (0-based, F1..F4) on/off. */
    fun ledSet(i: Int, on: Boolean): Boolean = Su.run("echo ${if (on) 1 else 0} > ${ledNode(i)}")

    /** Current state of button LED [i], or false if unreadable. */
    fun ledGet(i: Int): Boolean = Su.runOutput("cat ${ledNode(i)} 2>/dev/null")?.trim() == "1"

    private fun ledNode(i: Int) = "/sys/class/gpio/gpio${147 + i}/value"
    private fun exists(p: String) = Su.runOutput("ls $p 2>/dev/null")?.trim()?.isNotEmpty() == true

    companion object {
        private const val BASE = "/sys/class/st_relay"
    }
}
