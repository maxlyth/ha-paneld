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
