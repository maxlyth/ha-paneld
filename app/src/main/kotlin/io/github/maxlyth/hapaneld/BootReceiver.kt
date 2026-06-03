package io.github.maxlyth.hapaneld

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the agent after a panel reboot, so TTS survives the aggressive panel power cycling. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            PaneldService.start(context)
        }
    }
}
