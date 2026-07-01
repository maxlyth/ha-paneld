package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Holds a `PARTIAL_WAKE_LOCK` so a mains-powered panel's SoC + network never suspend (the screen is
 * still free to sleep independently). Without this, Android Doze / suspend can freeze the app's
 * threads — notably the MQTT client's reactor and its keepalive timer — while the broker times the
 * connection out, leaving a half-open (CLOSE-WAIT) socket that HiveMQ never notices and that
 * `isConnected()` keeps reporting as live (fleet-wide stall, 2026-07-01). Keeping the CPU + network up
 * is the root-cause fix; the MQTT keepalive + liveness watchdog are the belt-and-suspenders recovery.
 *
 * A wall panel is mains-powered, so an unbounded partial wakelock is the intended behaviour, not a
 * battery bug. [apply] is idempotent; toggle off via the `keep_awake` config key for the rare
 * battery-powered case.
 */
class PowerController(context: Context) {
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var wl: PowerManager.WakeLock? = null

    @Synchronized
    fun apply(keepAwake: Boolean) {
        if (keepAwake) acquire() else release()
    }

    @Suppress("WakelockTimeout") // deliberately unbounded — a mains panel must never suspend
    @Synchronized
    private fun acquire() {
        val w = wl ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).also {
            it.setReferenceCounted(false)   // idempotent: acquire()/release() are level-triggered, not counted
            wl = it
        }
        if (w.isHeld) return
        runCatching { w.acquire() }
            .onSuccess { Log.i(TAG, "partial wakelock held — SoC + network kept awake") }
            .onFailure { Log.w(TAG, "wakelock acquire failed", it) }
    }

    @Synchronized
    private fun release() {
        wl?.let { if (it.isHeld) runCatching { it.release() } }
        Log.i(TAG, "partial wakelock released")
    }

    fun isHeld(): Boolean = wl?.isHeld == true

    companion object { private const val TAG = "ha-paneld:keepawake" }
}
