package io.github.maxlyth.hapaneld.provisioning

import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.device.LedMechanism
import io.github.maxlyth.hapaneld.device.ScreenOff
import io.github.maxlyth.hapaneld.device.SuForm
import io.github.maxlyth.hapaneld.device.profile.DataDeviceProfile
import io.github.maxlyth.hapaneld.device.profile.ProfileArtifacts
import io.github.maxlyth.hapaneld.device.profile.ProfileHelperAuthorityDemand
import io.github.maxlyth.hapaneld.device.profile.ProfileMetadata
import io.github.maxlyth.hapaneld.device.profile.ResolvedProfile

/**
 * The single adaptation boundary from the active normalized runtime profile into planner intent.
 * Release-owned artifact metadata is resolved here; no URL, signer, hash, or author prose reaches
 * the plan.
 */
internal fun ResolvedProfile.toProvisioningProfile(): ProvisioningProfile {
    val artifactId = profile.provisioning.webViewArtifactId
    val target = artifactId?.let { id ->
        ProfileArtifacts.webViews[id]?.let { spec ->
            ProvisioningWebViewTarget(artifactId = id, version = spec.version)
        }
    }
    return ProvisioningProfile(
        ref = summary.ref,
        displayName = summary.displayName,
        origin = summary.origin,
        contentVersion = summary.contentVersion,
        // appCanSu is an attempt-order hint, not an observation that this installation currently has
        // root. Privilege guidance is suppressed only after the planner sees a compatible root helper.
        directRootExpected = false,
        helperImportance = if (profile.requiresProvisioningHelper()) ProvisioningImportance.REQUIRED else null,
        shizuku = profile.provisioning.shizuku,
        webView = target,
    )
}

/**
 * A helper is required only when a selected core driver demands that authority. This keeps privileged
 * host/app-su operations out of the helper item while covering app-first controllers whose documented
 * runtime fallback becomes the only privileged authority on a sandbox-walled profile.
 */
internal fun DeviceProfile.requiresProvisioningHelper(): Boolean =
    provisioningDriverIds().any { driver ->
        when (ProfileMetadata.helperAuthorityDemand.getValue(driver)) {
            ProfileHelperAuthorityDemand.NONE -> false
            ProfileHelperAuthorityDemand.SANDBOX_FALLBACK -> !appCanSu
            ProfileHelperAuthorityDemand.REQUIRED -> true
        }
    }

/**
 * Runtime YAML profiles retain their validated driver declaration. The compiled emergency/contract
 * profiles use the same core ids inferred from their normalized capability fields.
 */
private fun DeviceProfile.provisioningDriverIds(): Set<String> =
    (this as? DataDeviceProfile)?.document?.requires?.drivers ?: buildSet {
        when (suForm) {
            SuForm.ANDROID -> if (appCanSu) add("access.android-su")
            SuForm.TOOLBOX -> if (appCanSu) add("access.toolbox-su")
            SuForm.NONE -> Unit
        }
        when (ledMechanism) {
            LedMechanism.RK3576_IOCTL -> add("led.rk3576-ioctl")
            LedMechanism.RK3576_IOCTL_DAEMON -> add("led.rk3576-ioctl-daemon")
            LedMechanism.SYSFS_DAEMON -> add("led.sysfs-daemon")
            LedMechanism.AUTODETECT -> add("led.autodetect")
            LedMechanism.NONE -> Unit
        }
        add(
            when (screenOff) {
                ScreenOff.SU_BLPOWER -> "screen.su-blpower"
                ScreenOff.DAEMON_BLPOWER -> "screen.daemon-blpower"
                ScreenOff.BRIGHTNESS_ZERO -> "screen.brightness-zero"
            },
        )
        if (zigbeeGatewayDir != null) add("radio.siliconlabs-host")
        if (relayBase != null || relayBaseFallbacks.isNotEmpty()) add("relay.sysfs")
        if (buttonLedGpioBase != null) add("relay.gpio-button-led")
        if (hasButtonBacklight) add("input.button-backlight")
        if (proximityTech != null || lightTech != null) add("sensor.android")
        if (proximityGpio != null) add("sensor.gpio-proximity")
        if (hasCht8305) add("sensor.cht8305-daemon")
        if (evdevButtons.isNotEmpty()) add("input.evdev")
        if (recommendedWebView != null) add("update.webview")
    }
