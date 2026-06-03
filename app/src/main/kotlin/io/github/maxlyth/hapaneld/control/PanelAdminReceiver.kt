package io.github.maxlyth.hapaneld.control

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context

/**
 * Device-admin receiver. Its only purpose is to hold the `force-lock` policy so [ScreenController]
 * can call `DevicePolicyManager.lockNow()` to sleep the screen — there is no public Android API to
 * turn the screen off without device-admin (or a vendor call), and we want a vendor-free path.
 *
 * Activated at provisioning via `dpm set-active-admin <pkg>/.control.PanelAdminReceiver`
 * (active-admin is sufficient for lockNow; device-owner is NOT used — it requires an account-free
 * device and would conflict with the logged-in HA Companion).
 */
class PanelAdminReceiver : DeviceAdminReceiver() {
    companion object {
        fun component(context: Context): ComponentName =
            ComponentName(context, PanelAdminReceiver::class.java)
    }
}
