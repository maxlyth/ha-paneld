package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.control.SystemController

internal enum class LaunchDestination {
    INTRO,
    DASHBOARD,
}

internal data class LaunchScreenDecision(
    val destination: LaunchDestination,
    val rememberVersionShown: Boolean = false,
)

internal data class SavedLaunchIntroState(
    val explicitAdminEntry: Boolean,
    val pendingVersionCode: Long?,
    val autoReturnRemainingMs: Long?,
    val autoReturnNextAttemptRemainingMs: Long?,
)

internal data class RestoredLaunchIntroPlan(
    val explicitAdminEntry: Boolean,
    val pendingVersionCode: Long?,
    val autoReturnDeadlineMs: Long?,
    val autoReturnDelayMs: Long?,
)

/** Restore a visible intro without re-running destination policy or resetting its return window. */
internal fun restoreLaunchIntro(
    saved: SavedLaunchIntroState,
    currentVersionCode: Long,
    lastShownVersionCode: Long?,
    nowMs: Long,
    minimumReturnDelayMs: Long,
    maximumReturnWindowMs: Long,
): RestoredLaunchIntroPlan {
    val pendingVersion = saved.pendingVersionCode
        ?.takeIf { it == currentVersionCode && lastShownVersionCode != currentVersionCode }
    if (saved.explicitAdminEntry) {
        return RestoredLaunchIntroPlan(true, pendingVersion, null, null)
    }
    val remaining = saved.autoReturnRemainingMs
        ?.coerceIn(0L, maximumReturnWindowMs)
        ?.takeIf { it > 0L }
    val desiredDelay = saved.autoReturnNextAttemptRemainingMs
        ?.coerceIn(0L, remaining ?: 0L)
        ?: minimumReturnDelayMs
    val delay = remaining
        ?.let { maxOf(desiredDelay, minimumReturnDelayMs) }
        ?.takeIf { it <= remaining }
    return RestoredLaunchIntroPlan(
        explicitAdminEntry = false,
        pendingVersionCode = pendingVersion,
        autoReturnDeadlineMs = remaining?.takeIf { delay != null }?.let { nowMs + it },
        autoReturnDelayMs = delay,
    )
}

/** Pure policy for ha-paneld's launcher/QR screen. Recovery always wins over kiosk convenience. */
internal object LaunchScreenPolicy {
    fun decide(
        kioskEnabled: Boolean,
        configuredRenderer: String,
        builtInUrlConfigured: Boolean,
        dashboardLaunchAvailable: Boolean,
        dashboardCrashLooping: Boolean,
        currentVersionCode: Long,
        lastShownVersionCode: Long?,
        explicitAdminEntry: Boolean,
    ): LaunchScreenDecision {
        if (explicitAdminEntry || !kioskEnabled) return LaunchScreenDecision(LaunchDestination.INTRO)
        if (!PostUpdateReturnPolicy.dashboardReady(
                configuredRenderer = configuredRenderer,
                builtInUrlConfigured = builtInUrlConfigured,
                dashboardLaunchAvailable = dashboardLaunchAvailable,
                dashboardCrashLooping = dashboardCrashLooping,
            )
        ) {
            return LaunchScreenDecision(LaunchDestination.INTRO)
        }
        if (configuredRenderer == SystemController.BUILTIN_DASHBOARD &&
            lastShownVersionCode != currentVersionCode
        ) {
            return LaunchScreenDecision(
                destination = LaunchDestination.INTRO,
                rememberVersionShown = true,
            )
        }
        return LaunchScreenDecision(LaunchDestination.DASHBOARD)
    }
}

/** Exact configured external renderer, or Companion auto-detection order when configuration is blank. */
internal fun externalRendererCandidates(configuredRenderer: String): List<String> = when {
    configuredRenderer == SystemController.BUILTIN_DASHBOARD -> emptyList()
    configuredRenderer.isNotBlank() -> listOf(configuredRenderer)
    else -> listOf(
        "io.homeassistant.companion.android.minimal",
        "io.homeassistant.companion.android",
    )
}

/** Pure admission gate for the asynchronous first-draw acknowledgement. */
internal fun mayAcknowledgePresentedIntro(
    generationMatches: Boolean,
    presentedViewMatches: Boolean,
    viewAttached: Boolean,
    activityFinishing: Boolean,
    activityDestroyed: Boolean,
    startupChosen: Boolean,
): Boolean = generationMatches &&
    presentedViewMatches &&
    viewAttached &&
    !activityFinishing &&
    !activityDestroyed &&
    startupChosen
