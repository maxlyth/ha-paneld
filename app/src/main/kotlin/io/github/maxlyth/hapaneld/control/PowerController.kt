package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log

/**
 * Keeps a mains-powered panel reachable by holding two locks together:
 *  - a `PARTIAL_WAKE_LOCK` so the SoC never Doze/suspends (the screen is still free to sleep), and
 *  - a **Wi-Fi lock** (`WIFI_MODE_FULL_HIGH_PERF`) so the Wi-Fi radio never enters power-save.
 *
 * Both matter for the 2026-07-01 fleet stall: without the wakelock, suspend freezes the MQTT reactor +
 * keepalive; without the Wi-Fi lock, the radio's power-save stalls idle traffic — the leading cause of
 * the PX30/Android-8.1 panels' idle-TCP-over-IPv6 timeouts (ICMPv6 stayed fine; only sustained TCP
 * died). A partial wakelock does NOT keep the radio awake, so the Wi-Fi lock is a separate, necessary
 * lock. Ethernet panels simply have no Wi-Fi lock to take (createWifiLock still returns a harmless no-op
 * holder). Needs only `ACCESS_WIFI_STATE` (already granted).
 *
 * A wall panel is mains-powered, so holding both unbounded is the intended behaviour, not a battery bug.
 * [apply] is idempotent; toggle off via the `keep_awake` config key for the rare battery-powered case.
 */
class PowerController(context: Context) {
    private val app = context.applicationContext
    private val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var wl: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Synchronized
    fun apply(keepAwake: Boolean) {
        if (keepAwake) acquire() else release()
    }

    @Suppress("WakelockTimeout", "DEPRECATION") // unbounded is intended; FULL_HIGH_PERF is the right mode on API 27
    @Synchronized
    private fun acquire() {
        val w = wl ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).also {
            it.setReferenceCounted(false)   // idempotent: acquire()/release() are level-triggered, not counted
            wl = it
        }
        if (!w.isHeld) runCatching { w.acquire() }
            .onSuccess { Log.i(TAG, "partial wakelock held — SoC + network kept awake") }
            .onFailure { Log.w(TAG, "wakelock acquire failed", it) }

        val wf = wifiLock ?: wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG)?.also {
            it.setReferenceCounted(false)
            wifiLock = it
        }
        if (wf != null && !wf.isHeld) runCatching { wf.acquire() }
            .onSuccess { Log.i(TAG, "wifi high-perf lock held — radio power-save off") }
            .onFailure { Log.w(TAG, "wifi lock acquire failed", it) }
    }

    @Synchronized
    private fun release() {
        wl?.let { if (it.isHeld) runCatching { it.release() } }
        wifiLock?.let { if (it.isHeld) runCatching { it.release() } }
        Log.i(TAG, "power locks released (wakelock + wifi)")
    }

    fun isHeld(): Boolean = wl?.isHeld == true

    companion object { private const val TAG = "ha-paneld:keepawake" }
}
