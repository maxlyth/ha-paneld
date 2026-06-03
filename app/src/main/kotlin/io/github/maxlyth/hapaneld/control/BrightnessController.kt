package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Screen brightness via the standard `Settings.System` API — no vendor lib. Requires the
 * `WRITE_SETTINGS` app-op (granted at provisioning via `appops set <pkg> WRITE_SETTINGS allow`,
 * or via the setup Activity on non-root installs).
 *
 * HA-driven by design: ha-paneld exposes only the brightness *actuator*. Brightness *policy*
 * (from room lux / occupancy) is computed HA-side and pushed here — the panel never runs an
 * on-device light-sensor loop (panel light sensors are inconsistent across the fleet).
 *
 * HA brightness is 0–255; Android `SCREEN_BRIGHTNESS` is also 0–255, so it maps 1:1.
 */
class BrightnessController(private val context: Context) {

    fun canWrite(): Boolean = Settings.System.canWrite(context)

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
            Log.d(TAG, "brightness -> $v")
        } catch (e: SecurityException) {
            Log.w(TAG, "WRITE_SETTINGS not granted — cannot set brightness", e)
        }
    }

    fun getBrightness(): Int =
        try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Settings.SettingNotFoundException) {
            -1
        }

    companion object {
        private const val TAG = "ha-paneld/brightness"
    }
}
