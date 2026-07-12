package io.github.maxlyth.hapaneld.control

import android.util.Log
import io.github.maxlyth.hapaneld.platform.Daemon
import io.github.maxlyth.hapaneld.platform.NoWakeTap
import io.github.maxlyth.hapaneld.platform.RootShell
import io.github.maxlyth.hapaneld.platform.ScreenPower
import io.github.maxlyth.hapaneld.platform.WakeTap
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
 * keyguard and therefore demands the device PIN on wake. Its collaborators are seamed ([Backlight],
 * [ScreenPower], [RootShell], [Daemon], [WakeTap]) so the never-blank logic is unit-testable without a device.
 *
 * Never-blank guarantee: a screen-off must ALWAYS be locally wakeable. Every real off arms a [WakeTap]
 * (a non-consuming touch overlay) so a tap re-lights the panel. If a wake can't be guaranteed
 * ([WakeTap.canArm] is false — no overlay permission), the off degrades to a visible dim rather than a
 * true dark, so the panel can never look bricked (the failure mode that stranded a freshly-provisioned
 * panel dark + touch-dead).
 */
class ScreenController(
    private val backlight: Backlight,
    private val power: ScreenPower,
    private val root: RootShell = Su,
    private val daemon: Daemon = HelperClient,
    private val wakeTap: WakeTap = NoWakeTap,
) {
    // Last known "on" level, used by the brightness fallback. Survives an off/on cycle.
    @Volatile private var savedLevel = DEFAULT_ON

    /** Invoked after a LOCAL touch-wake so callers can publish screen=ON to HA. Set by the service. */
    @Volatile var onWakeByTap: (() -> Unit)? = null

    // True only between a genuine screen-off and the next wake. The never-blank watchdog uses this to
    // tell a USER-intended dark screen (leave it) from an unintended one (re-light it) — so a stray/
    // stale screen-off can never strand the panel dark, but a deliberate "screen off" still stays off.
    @Volatile private var intendedOff = false

    fun isOn(): Boolean = power.isInteractive()

    /** Whether the last screen state ha-paneld set was a deliberate off (vs. never-asked / woken). */
    fun isIntendedOff(): Boolean = intendedOff

    /** Best-effort: is the backlight actually dark? bl_power 4=off/0=on (root/daemon panels); else the
     *  brightness-fallback path where 0 == off. Unknown → false (never re-light on a guess). */
    fun looksDark(): Boolean {
        val bl = root.runOutput("d=\$(ls -d /sys/class/backlight/*/ 2>/dev/null|head -1);cat \${d}bl_power 2>/dev/null")?.trim()
        return when (bl) {
            "4" -> true
            // A powered backlight with an effective level of zero is still physically dark.
            "0" -> backlight.getBrightness() <= 0
            else -> backlight.getBrightness() <= 0
        }
    }

    /** Record an explicit brightness so the fallback off/on restores to it. */
    fun noteLevel(level: Int) {
        if (level > 0) savedLevel = level.coerceIn(1, 255)
    }

    fun sleep() {
        intendedOff = true
        // Never go fully dark without a guaranteed way back. If touch-to-wake can't be armed (no overlay
        // permission), a real screen-off would leave the panel unwakeable except via HA/proximity — the
        // "looks bricked" failure that stranded a freshly-provisioned panel dark. Degrade to a visible
        // dim instead: still legible + tappable, and HA/proximity can still restore full brightness. This
        // path leaves the screen VISIBLE, so the built-in renderer must NOT be frozen here (a frozen
        // WebView on a still-lit dashboard would show stale, un-tappable cards).
        if (!wakeTap.canArm()) {
            val cur = backlight.getBrightness()
            if (cur > 0) savedLevel = cur
            backlight.setBrightness(NO_WAKE_DIM)
            Log.w(TAG, "screen-off with no touch-to-wake — dimming to floor (never-blank; saved=$savedLevel)")
            return
        }
        // Guaranteed locally wakeable: arm the tap, then power the backlight off for real. Only the two
        // bl_power paths below take the panel *truly* dark — freeze the WebView there (no point rendering
        // behind a black backlight). The brightness fallback (0) is not guaranteed dark on panels that
        // clamp a minimum, so it does NOT freeze (correctness over the CPU saving on those rare panels).
        wakeTap.arm { wake(); onWakeByTap?.invoke() }
        if (daemon.send("SCREEN OFF") == "OK") {
            BuiltinDashboard.onScreenAwake(false)
            Log.d(TAG, "screen -> off (daemon bl_power)")
            return
        }
        if (root.run(blPower(false))) {
            BuiltinDashboard.onScreenAwake(false)
            Log.d(TAG, "screen -> off (su bl_power)")
            return
        }
        // Last resort: no daemon, no su — dim to 0 (only a dim on panels that clamp a minimum). Uses the
        // raw setter so it can reach 0: the public setBrightness floors at MIN_VISIBLE to stay never-blank.
        val cur = backlight.getBrightness()
        if (cur > 0) savedLevel = cur
        backlight.setBrightnessRaw(0)
        Log.d(TAG, "screen -> off (brightness fallback; saved=$savedLevel)")
    }

    fun wake() {
        intendedOff = false
        // Resume the built-in renderer's WebView (no-op if it isn't the dashboard).
        BuiltinDashboard.onScreenAwake(true)
        wakeTap.disarm()
        if (daemon.send("SCREEN ON") == "OK") {
            power.pulseWake()
            Log.d(TAG, "screen -> on (daemon bl_power)")
            return
        }
        if (root.run(blPower(true))) {
            power.pulseWake()
            Log.d(TAG, "screen -> on (su bl_power)")
            return
        }
        backlight.setBrightness(savedLevel.coerceAtLeast(MIN_ON))
        power.pulseWake()
        Log.d(TAG, "screen -> on (brightness fallback; $savedLevel)")
    }

    // Write FB_BLANK to the first backlight device's bl_power (0=on, 4=off). Fails (exit!=0, so the
    // caller falls through) if there's no backlight node — never silently "succeeds" doing nothing.
    private fun blPower(on: Boolean): String {
        val v = if (on) 0 else 4
        return "d=\$(ls -d /sys/class/backlight/*/ 2>/dev/null|head -1);" +
            "[ -n \"\$d\" ]&&echo $v >\${d}bl_power"
    }

    companion object {
        private const val TAG = "ha-paneld/screen"
        private const val DEFAULT_ON = 160
        private const val MIN_ON = 10
        // Dim level for a screen-off that can't be made touch-wakeable: low but clearly on, never blank.
        private const val NO_WAKE_DIM = 10
    }
}
