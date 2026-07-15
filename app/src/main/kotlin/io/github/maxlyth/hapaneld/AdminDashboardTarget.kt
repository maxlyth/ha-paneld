package io.github.maxlyth.hapaneld

internal sealed interface AdminDashboardTarget {
    data object Builtin : AdminDashboardTarget
    data class Foreign(val packageName: String) : AdminDashboardTarget
}

private val COMPANION_PACKAGES = listOf(
    "io.homeassistant.companion.android.minimal",
    "io.homeassistant.companion.android",
)

/** Resolve the admin launcher's Dashboard tile without silently overriding an explicit selection. */
internal fun resolveAdminDashboardTarget(
    configuredPackage: String,
    builtinReady: Boolean,
    isLaunchable: (String) -> Boolean,
): AdminDashboardTarget? = when {
    configuredPackage == "builtin" -> AdminDashboardTarget.Builtin.takeIf { builtinReady }
    configuredPackage.isNotBlank() -> configuredPackage
        .takeIf(isLaunchable)
        ?.let(AdminDashboardTarget::Foreign)
    else -> COMPANION_PACKAGES
        .firstOrNull(isLaunchable)
        ?.let(AdminDashboardTarget::Foreign)
}
