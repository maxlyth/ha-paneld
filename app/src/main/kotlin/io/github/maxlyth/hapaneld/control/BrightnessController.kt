package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Screen brightness via the standard `Settings.System` API — no vendor lib. Requires the
 * `WRITE_SETTINGS` app-op (granted at provisioning via `appops set <pkg> WRITE_SETTINGS allow`,
 * or via the setup Activity on non-root installs).
 *
 * Actuator-first by design: brightness *policy* (from room lux / occupancy) is normally computed
 * HA-side and pushed here. The one opt-in exception is [AutoBrightnessController]
 * (`switch.<panel>_auto_brightness`, default off), which runs an on-device curve off the panel's light
 * sensor (or HA-fed lux) — see its docs for why that's worth a local loop.
 *
 * HA brightness is 0–255; Android `SCREEN_BRIGHTNESS` is also 0–255, so it maps 1:1.
 */
class BrightnessController(private val context: Context) {

    fun canWrite(): Boolean = Settings.System.canWrite(context)

    // The hardware backlight node (path, max). Sonoff firmware idle-dims this sysfs node directly and does
    // NOT honour SCREEN_BRIGHTNESS, so we read + drive it to keep HA in sync with the real backlight (and
    // to actually move it). Null → no node or no root → fall back to the Android setting. Discovered once.
    private val backlight: Pair<String, Int>? by lazy {
        val dir = Su.runOutput("ls -d /sys/class/backlight/*/ 2>/dev/null | head -1")?.trim()
        if (dir.isNullOrEmpty()) return@lazy null
        val max = Su.runOutput("cat ${dir}max_brightness 2>/dev/null")?.trim()?.toIntOrNull()
        if (max == null || max <= 0) null else dir to max
    }

    /** @param level 0–255. Switches the panel to manual brightness mode and applies [level]. */
    fun setBrightness(level: Int) {
        val v = level.coerceIn(0, 255)
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                v,
            )
            Log.d(TAG, "brightness setting -> $v")
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SETTINGS not granted — cannot set brightness", e)
        }
        // Also drive the hardware node: on Sonoff panels the firmware owns the backlight sysfs and the
        // Android setting alone doesn't move it. No-op where there's no node / no root.
        backlight?.let { (dir, max) ->
            val hw = (v.toLong() * max / 255).toInt().coerceIn(0, max)
            Su.runOutput("echo $hw > ${dir}brightness")
        }
    }

    /** Reports the EFFECTIVE backlight (sysfs actual_brightness, scaled to 0–255) so HA reflects external /
     *  firmware dimming that bypasses SCREEN_BRIGHTNESS; falls back to the Android setting. */
    fun getBrightness(): Int {
        backlight?.let { (dir, max) ->
            val actual = Su.runOutput("cat ${dir}actual_brightness 2>/dev/null")?.trim()?.toIntOrNull()
            if (actual != null) return (actual.toLong() * 255 / max).toInt().coerceIn(0, 255)
        }
        return try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Settings.SettingNotFoundException) {
            -1
        }
    }

    companion object {
        private const val TAG = "ha-paneld/brightness"
    }
}
