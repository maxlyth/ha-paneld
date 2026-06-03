package io.github.maxlyth.hapaneld.hardware

import android.util.Log
import io.github.maxlyth.hapaneld.control.Su

/**
 * LED adapter for panels whose LEDs are standard Linux `/sys/class/leds/*` nodes driven via root
 * (e.g. Tuya TPA10: `button-backlight` + `avs-pwm-led`). Writes only the **safe** `brightness`
 * nodes — NOT the `avsux_*` firmware/animation interface, which reliably reboots the TPA10 (verified
 * 2026-06-03). So this is on/off + dim, not arbitrary RGB ([colorCapable] = false); HA sends a
 * brightness-only light.
 *
 * Both the button backlight and the front LED follow the on/off+dim together as "the panel LED".
 */
class SysfsLedController : LedController {

    private val nodes = listOf(
        "/sys/class/leds/avs-pwm-led/brightness",
        "/sys/class/leds/button-backlight/brightness",
    )

    private val present: Boolean by lazy {
        // Needs root AND at least the front LED node. One probe at first use.
        Su.run("test -e /sys/class/leds/avs-pwm-led/brightness")
    }

    override fun available(): Boolean = present

    override fun colorCapable(): Boolean = false

    override fun setRgb(r: Int, g: Int, b: Int) {
        if (!present) return
        // No safe arbitrary-colour path on these panels — map colour to intensity (max channel).
        val level = maxOf(r, g, b).coerceIn(0, 255)
        write(level)
    }

    override fun off() {
        if (present) write(0)
    }

    private fun write(level: Int) {
        val cmd = nodes.joinToString("; ") { "echo $level > $it" }
        if (!Su.run(cmd)) Log.w(TAG, "LED write failed (su/node)")
        else Log.d(TAG, "sysfs LED -> $level")
    }

    companion object {
        private const val TAG = "ha-paneld/led-sysfs"
    }
}
