package io.github.maxlyth.hapaneld

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the agent after a panel reboot or a self-update (`adb install -r`). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> PaneldService.start(context)
        }
    }
}
