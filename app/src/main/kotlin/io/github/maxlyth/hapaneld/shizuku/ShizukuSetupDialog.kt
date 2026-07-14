package io.github.maxlyth.hapaneld.shizuku

import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** On-panel-only opt-in surface. ConfigActivity is not exported and there is no remote equivalent. */
object ShizukuSetupDialog {
    fun show(activity: AppCompatActivity) {
        ShizukuBridge.refresh()
        val consented = ShizukuConsent.enabled(activity)
        val managed = ShizukuConsent.managed(activity)
        val state = ShizukuBridge.state
        val managerRunning = ShizukuBridge.managerRunning()
        val message = buildString {
            append(description(state))
            append("\n\nThis grants only APK install/update, screenshots, key/tap input, density, and text-size operations. ")
            append("It does not expose a general shell, root app data, reboot, logs, or vendor-app controls.")
            if (managed) append("\n\nThis panel was provisioned as managed; verified manager updates are enabled by default.")
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Enhanced access (Shizuku)")
            .setMessage(message)
            .setNegativeButton("Close", null)

        when {
            state == ShizukuState.MANAGER_MISSING || state == ShizukuState.MANAGER_UNTRUSTED -> Unit
            consented && state == ShizukuState.STOPPED && !managerRunning -> {
                dialog.setPositiveButton("Open Shizuku") { _, _ -> launchManager(activity) }
                dialog.setNeutralButton("Disable") { _, _ -> ShizukuBridge.disable() }
            }
            consented && state == ShizukuState.PERMISSION_REQUIRED -> {
                dialog.setPositiveButton("Request permission") { _, _ ->
                    ShizukuBridge.enable(activity, managed = managed)
                }
                dialog.setNeutralButton("Disable") { _, _ -> ShizukuBridge.disable() }
            }
            consented -> dialog.setPositiveButton("Disable") { _, _ -> ShizukuBridge.disable() }
            state == ShizukuState.STOPPED && !managerRunning -> {
                dialog.setPositiveButton("Open Shizuku") { _, _ -> launchManager(activity) }
            }
            else -> {
                dialog.setPositiveButton("Enable") { _, _ -> ShizukuBridge.enable(activity) }
                dialog.setNeutralButton("Enable managed") { _, _ -> ShizukuBridge.enable(activity, managed = true) }
            }
        }
        dialog.show()
    }

    internal fun description(state: ShizukuState): String = when (state) {
        ShizukuState.MANAGER_MISSING ->
            "Shizuku is not installed. Use the ha-paneld provisioning script once over ADB, then approve access here on the panel."
        ShizukuState.MANAGER_UNTRUSTED ->
            "A package named Shizuku is installed, but its signing certificate is not trusted. ha-paneld will not connect to it."
        ShizukuState.STOPPED ->
            "Shizuku is installed but its service is stopped, or enhanced access is not enabled. Start Shizuku, then return here."
        ShizukuState.PERMISSION_REQUIRED ->
            "Shizuku is running. Enable enhanced access to show Shizuku's local permission prompt."
        ShizukuState.BINDING -> "Connecting to Shizuku…"
        ShizukuState.READY -> "Enhanced access is ready."
        ShizukuState.INCOMPATIBLE ->
            "The Shizuku service returned an unexpected identity or protocol version. Access is blocked."
        ShizukuState.ERROR -> "Shizuku could not be connected. Close this dialog and try again."
    }

    private fun launchManager(activity: AppCompatActivity) {
        val intent: Intent = activity.packageManager.getLaunchIntentForPackage(ShizukuManagerIdentity.PACKAGE)
            ?: return
        runCatching { activity.startActivity(intent) }
    }
}
