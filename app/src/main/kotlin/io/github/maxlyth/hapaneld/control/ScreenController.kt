package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.os.PowerManager
import android.util.Log
import io.github.maxlyth.hapaneld.util.HelperClient

/**
 * Screen on/off — vendor-free and **lock-free**.
 *
 * Preferred path: the root helper daemon powers the backlight via `bl_power` (a true hardware
 * off/on that leaves the device Awake, so there is NO keyguard and waking never asks for a PIN).
 * Fallback when no daemon is present: set Settings brightness to 0 / restore it — note this is only
 * a dim on panels that clamp a minimum backlight, not a true off.
 *
 * This deliberately avoids `DevicePolicyManager.lockNow()`, which turns the screen off via the
 * keyguard and therefore demands the device PIN on wake.
 */
class ScreenController(
    context: Context,
    private val brightness: BrightnessController,
) {
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    // Last known "on" level, used by the brightness fallback. Survives an off/on cycle.
    @Volatile private var savedLevel = DEFAULT_ON

    fun isOn(): Boolean = pm.isInteractive

    /** Record an explicit brightness so the fallback off/on restores to it. */
    fun noteLevel(level: Int) {
        if (level > 0) savedLevel = level.coerceIn(1, 255)
    }

    fun sleep() {
        if (HelperClient.send("SCREEN OFF") == "OK") {
            Log.d(TAG, "screen -> off (bl_power)")
            return
        }
        // Fallback: no daemon — dim to 0 (best effort).
        val cur = brightness.getBrightness()
        if (cur > 0) savedLevel = cur
        brightness.setBrightness(0)
        Log.d(TAG, "screen -> off (brightness fallback; saved=$savedLevel)")
    }

    fun wake() {
        if (HelperClient.send("SCREEN ON") == "OK") {
            pulseWake()
            Log.d(TAG, "screen -> on (bl_power)")
            return
        }
        // Fallback: restore brightness.
        brightness.setBrightness(savedLevel.coerceAtLeast(MIN_ON))
        pulseWake()
        Log.d(TAG, "screen -> on (brightness fallback; $savedLevel)")
    }

    @Suppress("DEPRECATION")
    private fun pulseWake() {
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "ha-paneld:wake",
        )
        wl.acquire(3_000)
    }

    companion object {
        private const val TAG = "ha-paneld/screen"
        private const val DEFAULT_ON = 160
        private const val MIN_ON = 10
    }
}
