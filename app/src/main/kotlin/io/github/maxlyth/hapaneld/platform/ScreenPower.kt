package io.github.maxlyth.hapaneld.platform

import android.content.Context
import android.os.PowerManager

/**
 * The PowerManager operations [io.github.maxlyth.hapaneld.control.ScreenController] needs — a seam so
 * screen on/off is unit-testable without Android. [AndroidScreenPower] is the real impl; tests use a fake.
 */
interface ScreenPower {
    /** True when the device is interactive (screen on) — `PowerManager.isInteractive`. */
    fun isInteractive(): Boolean

    /** Briefly acquire a SCREEN_BRIGHT wakelock that also wakes the device — the wake "pulse". */
    fun pulseWake()
}

/** Real [ScreenPower] over the Android PowerManager. */
class AndroidScreenPower(context: Context) : ScreenPower {
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    override fun isInteractive(): Boolean = pm.isInteractive

    @Suppress("DEPRECATION")
    override fun pulseWake() {
        pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "ha-paneld:wake",
        ).acquire(3_000)
    }
}
