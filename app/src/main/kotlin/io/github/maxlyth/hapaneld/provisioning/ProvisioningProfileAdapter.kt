package io.github.maxlyth.hapaneld.provisioning

import io.github.maxlyth.hapaneld.device.profile.ProfileArtifacts
import io.github.maxlyth.hapaneld.device.profile.ResolvedProfile
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.device.LedMechanism
import io.github.maxlyth.hapaneld.device.ScreenOff

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
        helperImportance = if (profile.requiresProvisioningHelper()) ProvisioningImportance.REQUIRED else null,
        shizuku = profile.provisioning.shizuku,
        webView = target,
    )
}

/**
 * A helper is required only when the selected runtime routes actually depend on it. A sandboxed app
 * without helper-backed drivers may have fewer optional capabilities, but that is not a hard profile
 * requirement and must not become a perpetual installer warning.
 */
internal fun DeviceProfile.requiresProvisioningHelper(): Boolean =
    ledMechanism == LedMechanism.SYSFS_DAEMON ||
        ledMechanism == LedMechanism.RK3576_IOCTL_DAEMON ||
        screenOff == ScreenOff.DAEMON_BLPOWER ||
        hasButtonBacklight ||
        hasCht8305 ||
        evdevButtons.isNotEmpty()
