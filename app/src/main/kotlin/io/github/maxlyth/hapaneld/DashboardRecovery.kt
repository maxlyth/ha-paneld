package io.github.maxlyth.hapaneld

/** Distinguishes the registration-time [android.net.ConnectivityManager.NetworkCallback.onAvailable]
 * from a network that genuinely became available after the renderer started offline. */
internal class NetworkRecoveryGate(initiallyAvailable: Boolean) {
    private var reloadOnAvailable = !initiallyAvailable

    fun onLost() {
        reloadOnAvailable = true
    }

    fun onAvailable(): Boolean {
        val reload = reloadOnAvailable
        reloadOnAvailable = false
        return reload
    }
}

/** Retry cadence for a frontend that has not connected yet. A live dashboard gets a longer grace
 * period so HA can heal a brief websocket flap without a disruptive full-page reload. */
internal class DashboardRetryPolicy(
    private val initialRetryMs: Long = 5_000L,
    private val maxRetryMs: Long = 60_000L,
    private val connectedGraceMs: Long = 90_000L,
) {
    private var retryMs = initialRetryMs

    fun connectionFailureDelay(wasConnected: Boolean): Long =
        if (wasConnected) connectedGraceMs else retryMs

    /** Called when a retry reload actually fires; returns the deadline for that new attempt. */
    fun afterRetry(): Long {
        retryMs = (retryMs * 2).coerceAtMost(maxRetryMs)
        return retryMs
    }

    fun reset() {
        retryMs = initialRetryMs
    }
}

/** 0..950 launch-progress scale. Never claims completion: the real completion signal is Android's
 * network callback, after which the launch view is immediately replaced by the dashboard. */
internal fun networkWaitProgress(elapsedMs: Long, estimateMs: Long): Int {
    if (estimateMs <= 0L) return 0
    return ((elapsedMs.coerceAtLeast(0L) * 1_000L) / estimateMs).coerceAtMost(950L).toInt()
}

internal data class StartupNetworkSnapshot(
    val interfacePresent: Boolean,
    val linkUp: Boolean,
    val addressAssigned: Boolean,
    val defaultNetwork: Boolean,
)

/** User-facing network phase. Deliberate line breaks keep every state balanced on a 480px square. */
internal fun startupNetworkStage(s: StartupNetworkSnapshot): String = when {
    !s.interfacePresent -> "Starting Android network services"
    !s.linkUp -> "Waiting for a network link"
    !s.addressAssigned -> "Network link connected\nWaiting for a network address"
    !s.defaultNetwork -> "Network address received\nPreparing the connection"
    else -> "Network ready\nOpening Home Assistant"
}
