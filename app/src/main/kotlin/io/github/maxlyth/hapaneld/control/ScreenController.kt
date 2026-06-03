package io.github.maxlyth.hapaneld.control

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Screen sleep/wake — vendor-free.
 *
 * - **sleep**: `DevicePolicyManager.lockNow()` (needs active device-admin with force-lock, see
 *   [PanelAdminReceiver]). The only public, vendor-free way to turn a panel screen off.
 * - **wake**: a transient `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` wakelock. Deprecated
 *   but still the simplest headless screen-on; held briefly then released so the normal
 *   screen-off timeout resumes.
 */
class ScreenController(private val context: Context) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    fun isAdminActive(): Boolean = dpm.isAdminActive(PanelAdminReceiver.component(context))

    fun isOn(): Boolean = pm.isInteractive

    fun sleep() {
        if (!isAdminActive()) {
            Log.w(TAG, "device-admin not active — cannot sleep (run: dpm set-active-admin)")
            return
        }
        dpm.lockNow()
        Log.d(TAG, "screen -> sleep")
    }

    @Suppress("DEPRECATION")
    fun wake() {
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "ha-paneld:wake",
        )
        wl.acquire(3_000)
        Log.d(TAG, "screen -> wake")
    }

    companion object {
        private const val TAG = "ha-paneld/screen"
    }
}
