package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.os.PowerManager
import android.util.Log
import io.github.maxlyth.hapaneld.util.HelperClient

/**
 * Screen on/off — vendor-free and **lock-free**.
 *
 * Tiers (first that works wins), all leaving the device Awake — no keyguard, no PIN on wake:
 *  1. Root helper daemon — powers the backlight via `bl_power` (sysfs-LED panels, e.g. TPA10).
 *  2. Direct `su` `bl_power` — for su-capable panels with no daemon (Sonoff PX30): a true
 *     hardware backlight-off. (brightness 0 on these only dims — the backlight stays powered.)
 *  3. Brightness 0 — last-resort dim for panels with neither daemon nor su.
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
            Log.d(TAG, "screen -> off (daemon bl_power)")
            return
        }
        if (Su.run(blPower(false))) {
            Log.d(TAG, "screen -> off (su bl_power)")
            return
        }
        // Last resort: no daemon, no su — dim to 0 (only a dim on panels that clamp a minimum).
        val cur = brightness.getBrightness()
        if (cur > 0) savedLevel = cur
        brightness.setBrightness(0)
        Log.d(TAG, "screen -> off (brightness fallback; saved=$savedLevel)")
    }

    fun wake() {
        if (HelperClient.send("SCREEN ON") == "OK") {
            pulseWake()
            Log.d(TAG, "screen -> on (daemon bl_power)")
            return
        }
        if (Su.run(blPower(true))) {
            pulseWake()
            Log.d(TAG, "screen -> on (su bl_power)")
            return
        }
        brightness.setBrightness(savedLevel.coerceAtLeast(MIN_ON))
        pulseWake()
        Log.d(TAG, "screen -> on (brightness fallback; $savedLevel)")
    }

    // Write FB_BLANK to the first backlight device's bl_power (0=on, 4=off). Fails (exit!=0, so the
    // caller falls through) if there's no backlight node — never silently "succeeds" doing nothing.
    private fun blPower(on: Boolean): String {
        val v = if (on) 0 else 4
        return "d=\$(ls -d /sys/class/backlight/*/ 2>/dev/null|head -1);" +
            "[ -n \"\$d\" ]&&echo $v >\${d}bl_power"
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
