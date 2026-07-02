package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.provider.Settings
import android.util.Log
import io.github.maxlyth.hapaneld.Config

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
class BrightnessController(private val context: Context) : Backlight {

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

    /**
     * @param level 0–255. Switches the panel to manual brightness mode and applies [level], floored at
     * [MIN_VISIBLE] so a brightness command (HA, auto-brightness, navbar) can never leave the panel
     * blank + touch-dead. A deliberate screen-off is a separate operation via [ScreenController], which
     * uses [setBrightnessRaw] for its last-resort dim-to-0.
     */
    override fun setBrightness(level: Int) = applyBrightness(level.coerceIn(MIN_VISIBLE, 255))

    /** Apply a raw level with NO minimum floor (0 allowed). For [ScreenController]'s screen-off fallback
     *  only — every other caller must use [setBrightness] to preserve the never-blank guarantee. */
    override fun setBrightnessRaw(level: Int) = applyBrightness(level.coerceIn(0, 255))

    private fun applyBrightness(v: Int) {
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
    override fun getBrightness(): Int {
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

    /**
     * Prevent the vendor firmware's idle backlight dim. Some panel firmwares dim the hardware backlight at
     * the `SCREEN_OFF_TIMEOUT` mark even while the OS keeps the screen on (stay-on-while-plugged), so the
     * panel goes very dim after the timeout despite `SCREEN_BRIGHTNESS` at max. Raising the timeout defers
     * that indefinitely. No root needed — `WRITE_SETTINGS` only.
     *
     * On (default, for mains-powered panels): the prior timeout is saved once, then set to "never". Off:
     * the saved firmware default is restored (60 s if none was captured).
     */
    fun applyPreventIdleDim(on: Boolean, config: Config) {
        try {
            val cur = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1)
            if (on) {
                if (cur in 1 until NEVER) config.savedScreenOffTimeout = cur
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, NEVER)
                Log.d(TAG, "prevent idle dim ON: screen_off_timeout $cur -> never")
            } else {
                val restore = config.savedScreenOffTimeout.takeIf { it > 0 } ?: 60000
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, restore)
                Log.d(TAG, "prevent idle dim OFF: screen_off_timeout -> $restore")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SETTINGS not granted — cannot set screen_off_timeout", e)
        }
    }

    companion object {
        private const val TAG = "ha-paneld/brightness"
        private const val MIN_VISIBLE = 10 // setBrightness floor: a dim command must never blank the panel
        private const val NEVER = Int.MAX_VALUE // ~24.8 days; the conventional "never auto-off" sentinel
    }
}
