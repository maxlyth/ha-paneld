package io.github.maxlyth.hapaneld

import java.net.URI

/**
 * Owns callbacks from one replaceable WebView generation. Opening or invalidating a generation makes
 * every callback captured by an older renderer stale; closing the gate makes all callbacks terminally
 * stale. Methods are synchronized because the external-auth bridge runs off the Android main thread.
 */
internal class RendererGenerationGate {
    private var sequence = 0L
    private var current = 0L
    private var closed = false

    @Synchronized fun open(): Long {
        check(!closed) { "renderer generation gate is closed" }
        current = ++sequence
        return current
    }

    @Synchronized fun invalidate() {
        current = ++sequence
    }

    @Synchronized fun owns(generation: Long): Boolean =
        !closed && generation != 0L && generation == current

    @Synchronized fun close() {
        closed = true
        current = ++sequence
    }
}

/**
 * Keep top-level navigation on the configured HA authority. HTTP↔HTTPS redirects remain allowed when
 * both URLs use their scheme default or preserve the same explicit port; a different explicit port is
 * a different service and must not inherit the renderer's external-auth bridge.
 */
internal fun dashboardNavigationAllowed(configuredUrl: String, candidateUrl: String): Boolean = runCatching {
    val configured = URI(configuredUrl.trim())
    val candidate = URI(candidateUrl.trim())
    val configuredScheme = configured.scheme?.lowercase() ?: return@runCatching false
    val candidateScheme = candidate.scheme?.lowercase() ?: return@runCatching false
    if (configuredScheme !in setOf("http", "https") || candidateScheme !in setOf("http", "https")) return@runCatching false
    if (!configured.host.equals(candidate.host, ignoreCase = true)) return@runCatching false
    if (configuredScheme == candidateScheme) {
        fun effectivePort(uri: URI, scheme: String): Int =
            if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
        effectivePort(configured, configuredScheme) == effectivePort(candidate, candidateScheme)
    } else {
        (configured.port < 0 && candidate.port < 0) ||
            (configured.port >= 0 && configured.port == candidate.port)
    }
}.getOrDefault(false)

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
