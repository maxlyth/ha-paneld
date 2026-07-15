package io.github.maxlyth.hapaneld.shizuku

import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/** On-panel-only opt-in surface. ConfigActivity is not exported and there is no remote equivalent. */
object ShizukuSetupDialog {
    internal enum class PrimaryAction(val label: String) {
        NONE(""),
        ENABLE("Enable"),
        REQUEST_PERMISSION("Request permission"),
        OPEN_MANAGER("Open Shizuku"),
        DISABLE("Disable"),
    }

    fun show(activity: AppCompatActivity) {
        ShizukuBridge.refresh()
        val consented = ShizukuConsent.enabled(activity)
        val state = ShizukuBridge.state
        val managerRunning = ShizukuBridge.managerRunning()
        val message = buildString {
            append(description(state))
            append("\n\nThis grants only signer-verified ha-paneld and minimal Companion updates, screenshots, key/tap input, density, and text-size operations. ")
            append("It does not allow arbitrary APK uploads, WebView replacement, a general shell, root app data, reboot, logs, or vendor-app controls.")
            append("\n\nOnce enabled, the supported operations can be invoked through ha-paneld's existing trusted-LAN controls on this panel.")
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Enhanced access (Shizuku)")
            .setMessage(message)
            .setNegativeButton("Close", null)

        val primaryAction = primaryAction(consented, state, managerRunning)
        when {
            state == ShizukuState.MANAGER_MISSING || state == ShizukuState.MANAGER_UNTRUSTED -> {
                if (disableAvailable(consented, state)) {
                    dialog.setNeutralButton("Disable") { _, _ -> ShizukuBridge.disable() }
                }
            }
            else -> {
                when (primaryAction) {
                    PrimaryAction.ENABLE,
                    PrimaryAction.REQUEST_PERMISSION -> dialog.setPositiveButton(primaryAction.label) { _, _ ->
                        ShizukuBridge.enable(activity)
                    }
                    PrimaryAction.OPEN_MANAGER -> dialog.setPositiveButton(primaryAction.label) { _, _ ->
                        launchManager(activity)
                    }
                    PrimaryAction.DISABLE -> dialog.setPositiveButton(primaryAction.label) { _, _ ->
                        ShizukuBridge.disable()
                    }
                    PrimaryAction.NONE -> Unit
                }
                if (consented && primaryAction != PrimaryAction.DISABLE) {
                    dialog.setNeutralButton("Disable") { _, _ -> ShizukuBridge.disable() }
                }
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
            "Shizuku is running and ready to show its local permission prompt. Request permission to continue."
        ShizukuState.MANUAL_GRANT_REQUIRED ->
            "Shizuku access was denied, so another permission prompt cannot be shown. Open Shizuku, choose Authorized applications, select ha-paneld, and grant access manually. Then return here."
        ShizukuState.BINDING -> "Connecting to Shizuku…"
        ShizukuState.READY -> "Enhanced access is ready."
        ShizukuState.INCOMPATIBLE ->
            "The Shizuku service returned an unexpected identity or protocol version. Access is blocked."
        ShizukuState.ERROR -> "Shizuku could not be connected. Close this dialog and try again."
    }

    internal fun disableAvailable(consented: Boolean, state: ShizukuState): Boolean = consented &&
        (state == ShizukuState.MANAGER_MISSING || state == ShizukuState.MANAGER_UNTRUSTED)

    internal fun primaryAction(
        consented: Boolean,
        state: ShizukuState,
        managerRunning: Boolean,
    ): PrimaryAction = when {
        state == ShizukuState.MANAGER_MISSING || state == ShizukuState.MANAGER_UNTRUSTED ->
            PrimaryAction.NONE
        state == ShizukuState.MANUAL_GRANT_REQUIRED -> PrimaryAction.OPEN_MANAGER
        state == ShizukuState.STOPPED && !managerRunning -> PrimaryAction.OPEN_MANAGER
        consented && state == ShizukuState.PERMISSION_REQUIRED -> PrimaryAction.REQUEST_PERMISSION
        consented -> PrimaryAction.DISABLE
        else -> PrimaryAction.ENABLE
    }

    private fun launchManager(activity: AppCompatActivity) {
        val intent: Intent = activity.packageManager.getLaunchIntentForPackage(ShizukuManagerIdentity.PACKAGE)
            ?: return
        runCatching { activity.startActivity(intent) }
    }
}
