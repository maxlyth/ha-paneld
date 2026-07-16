package io.github.maxlyth.hapaneld.provisioning

import io.github.maxlyth.hapaneld.device.profile.ProfileArtifacts
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
        helperImportance = if (profile.usesDaemon) ProvisioningImportance.REQUIRED else null,
        shizuku = profile.provisioning.shizuku,
        webView = target,
    )
}
