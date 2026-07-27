package io.github.maxlyth.hapaneld

import io.github.maxlyth.hapaneld.dashboard.EntityFilterProtocol
import io.github.maxlyth.hapaneld.http.HA_OAUTH_CALLBACK_PATH
import java.net.URI
import java.net.URLEncoder

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

internal data class WakeMediaRecoveryTicket(
    val cycle: Long,
    val rendererGeneration: Long,
)

internal enum class WakeMediaRecoveryAction { NONE, INSPECT, RELOAD }

/** Owns one bounded media-resume check for each real screen-off to screen-on cycle. */
internal class WakeMediaRecoveryGate {
    private var sequence = 0L
    private var current: WakeMediaRecoveryTicket? = null
    private var deferred = false
    private var recoveryClaimed = false
    private var closed = false

    @Synchronized fun begin(rendererGeneration: Long): WakeMediaRecoveryTicket {
        check(!closed) { "wake media recovery gate is closed" }
        deferred = false
        return current?.takeIf { it.rendererGeneration == rendererGeneration } ?: WakeMediaRecoveryTicket(
            cycle = ++sequence,
            rendererGeneration = rendererGeneration,
        ).also {
            current = it
            recoveryClaimed = false
        }
    }

    /** Preserve a real wake edge until this exact renderer generation reconnects its frontend. */
    @Synchronized fun defer(rendererGeneration: Long): WakeMediaRecoveryTicket {
        check(!closed) { "wake media recovery gate is closed" }
        val ticket = current?.takeIf { it.rendererGeneration == rendererGeneration } ?: WakeMediaRecoveryTicket(
            cycle = ++sequence,
            rendererGeneration = rendererGeneration,
        ).also {
            current = it
            recoveryClaimed = false
        }
        deferred = true
        return ticket
    }

    /** Claim a deferred wake only for the renderer that owned the original dark-to-awake edge. */
    @Synchronized fun activateDeferred(rendererGeneration: Long): WakeMediaRecoveryTicket? {
        val ticket = current?.takeIf { deferred && it.rendererGeneration == rendererGeneration } ?: return null
        deferred = false
        return ticket
    }

    @Synchronized fun owns(ticket: WakeMediaRecoveryTicket): Boolean =
        !closed && current == ticket

    @Synchronized fun onArmResult(ticket: WakeMediaRecoveryTicket, candidates: Int): WakeMediaRecoveryAction {
        if (!owns(ticket)) return WakeMediaRecoveryAction.NONE
        if (candidates > 0) return WakeMediaRecoveryAction.INSPECT
        complete(ticket)
        return WakeMediaRecoveryAction.NONE
    }

    @Synchronized fun onInspectResult(ticket: WakeMediaRecoveryTicket, stalled: Boolean): WakeMediaRecoveryAction {
        if (!owns(ticket) || recoveryClaimed) return WakeMediaRecoveryAction.NONE
        if (!stalled) {
            complete(ticket)
            return WakeMediaRecoveryAction.NONE
        }
        recoveryClaimed = true
        return WakeMediaRecoveryAction.RELOAD
    }

    @Synchronized fun complete(ticket: WakeMediaRecoveryTicket) {
        if (current == ticket) {
            current = null
            deferred = false
        }
    }

    @Synchronized fun invalidate() {
        current = null
        deferred = false
        recoveryClaimed = false
    }

    @Synchronized fun close() {
        closed = true
        invalidate()
    }
}

/** Exact scripts evaluated in the live dashboard to distinguish healthy resumed media from a dead camera card. */
internal object WakeMediaRecoveryScript {
    private const val STATE = "__haPanelWakeMedia"

    fun arm(cycle: Long): String =
        """
        (()=>{try{
          const videos=[];
          const visit=root=>{
            root.querySelectorAll('video').forEach(video=>videos.push(video));
            root.querySelectorAll('*').forEach(element=>{if(element.shadowRoot)visit(element.shadowRoot);});
          };
          const live=video=>{const stream=video.srcObject;return !!(stream&&typeof stream.getVideoTracks==='function'&&stream.getVideoTracks().some(track=>track.readyState==='live'));};
          const visible=video=>{const rect=video.getBoundingClientRect();const style=getComputedStyle(video);return video.isConnected&&rect.width>0&&rect.height>0&&rect.bottom>0&&rect.right>0&&rect.top<innerHeight&&rect.left<innerWidth&&style.display!=='none'&&style.visibility!=='hidden'&&Number(style.opacity)!==0;};
          const frames=video=>{try{if(typeof video.getVideoPlaybackQuality==='function'){const n=Number(video.getVideoPlaybackQuality().totalVideoFrames);if(Number.isFinite(n))return {supported:true,value:n};}if(typeof video.webkitDecodedFrameCount==='number'&&Number.isFinite(video.webkitDecodedFrameCount))return {supported:true,value:video.webkitDecodedFrameCount};}catch(_){}return {supported:false,value:0};};
          visit(document);
          const candidates=videos.filter(video=>visible(video)&&!video.ended&&(!video.paused||video.autoplay||live(video)));
          candidates.forEach(video=>{try{const playing=video.play();if(playing&&typeof playing.catch==='function')playing.catch(()=>{});}catch(_){}});
          window.$STATE={cycle:$cycle,samples:candidates.map(video=>({video,time:Number(video.currentTime)||0,frames:frames(video)}))};
          return candidates.length;
        }catch(_){return -1;}})()
        """.trimIndent()

    fun inspect(cycle: Long): String =
        """
        (()=>{try{
          const state=window.$STATE;
          delete window.$STATE;
          if(!state||state.cycle!==$cycle||!state.samples.length)return -1;
          const live=video=>{const stream=video.srcObject;return !!(stream&&typeof stream.getVideoTracks==='function'&&stream.getVideoTracks().some(track=>track.readyState==='live'));};
          const visible=video=>{const rect=video.getBoundingClientRect();const style=getComputedStyle(video);return video.isConnected&&rect.width>0&&rect.height>0&&rect.bottom>0&&rect.right>0&&rect.top<innerHeight&&rect.left<innerWidth&&style.display!=='none'&&style.visibility!=='hidden'&&Number(style.opacity)!==0;};
          const frames=video=>{try{if(typeof video.getVideoPlaybackQuality==='function'){const n=Number(video.getVideoPlaybackQuality().totalVideoFrames);if(Number.isFinite(n))return {supported:true,value:n};}if(typeof video.webkitDecodedFrameCount==='number'&&Number.isFinite(video.webkitDecodedFrameCount))return {supported:true,value:video.webkitDecodedFrameCount};}catch(_){}return {supported:false,value:0};};
          if(state.samples.some(sample=>!visible(sample.video)||sample.video.ended||(sample.video.paused&&!sample.video.autoplay&&!live(sample.video))))return -1;
          const progress=state.samples.map(sample=>{const now=frames(sample.video);if(sample.frames.supported){if(!now.supported)return null;return now.value>sample.frames.value;}return (Number(sample.video.currentTime)||0)>sample.time+0.05;});
          if(progress.some(value=>value===null))return -1;
          return progress.every(Boolean)?0:1;
        }catch(_){return -1;}})()
        """.trimIndent()
}

internal fun javascriptIntResult(result: String?): Int? =
    result?.trim()?.removeSurrounding("\"")?.toIntOrNull()

/**
 * Keep top-level navigation on the configured HA authority. An HTTP configuration may upgrade to HTTPS
 * when both URLs use scheme defaults or preserve the same explicit port. An HTTPS configuration never
 * downgrades to HTTP: cleartext content must not inherit the renderer's external-auth bridge.
 */
internal fun dashboardNavigationAllowed(configuredUrl: String, candidateUrl: String): Boolean = runCatching {
    val configured = URI(configuredUrl.trim())
    val candidate = URI(candidateUrl.trim())
    val configuredScheme = configured.scheme?.lowercase() ?: return@runCatching false
    val candidateScheme = candidate.scheme?.lowercase() ?: return@runCatching false
    if (configuredScheme !in setOf("http", "https") || candidateScheme !in setOf("http", "https")) return@runCatching false
    if (!configured.host.equals(candidate.host, ignoreCase = true)) return@runCatching false
    if (configuredScheme == "https" && candidateScheme != "https") return@runCatching false
    if (configuredScheme == candidateScheme) {
        fun effectivePort(uri: URI, scheme: String): Int =
            if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
        effectivePort(configured, configuredScheme) == effectivePort(candidate, candidateScheme)
    } else {
        (configured.port < 0 && candidate.port < 0) ||
            (configured.port >= 0 && configured.port == candidate.port)
    }
}.getOrDefault(false)

/**
 * A Home Assistant URL is configured but no credential is: the built-in renderer cannot render yet, but
 * it CAN run the on-panel sign-in that produces the missing credential.
 *
 * Deliberately a sibling of `Config.builtInRendererReady()` rather than a relaxation of it. That
 * predicate means "can render right now" and is load-bearing for `MainActivity`, `LaunchScreenPolicy` and
 * `PostUpdateReturnPolicy`; widening it would let the launcher hand off to a renderer with no credential
 * and strand the panel. This is strictly narrower — every state that stranded before still strands — and
 * it exists so the one state that can make progress on its own is allowed to.
 *
 * Without it the panel deadlocks on first run: saving a URL relaunches DashboardActivity, the readiness
 * gate rejects it for want of a token, and it bounces to the QR screen — while the only screen that can
 * obtain that token lives behind the same gate. The user sees repeated reloads and never a login.
 */
internal fun haSignInPending(haUrl: String, haToken: String, haRefreshToken: String): Boolean =
    haUrl.isNotBlank() && haToken.isBlank() && haRefreshToken.isBlank()

/**
 * Whether the built-in renderer must hold its first load until the entity-filter question is answered.
 *
 * A first load against an unfiltered Home Assistant is slow and laggy on a weak panel — measured at 94% p95
 * renderer CPU and touch responses ~60% slower on a Cortex-A35 panel against 3,769 entities — and it is the
 * first thing a new owner ever sees. Loading it and then offering to fix it inverts the order that matters:
 * the bad impression has already been made. So the panel waits on its existing pre-render surface, which is
 * a screen it is already showing safely, rather than loading something it will immediately have to reload.
 *
 * Narrow on purpose, and only ever a delay:
 *  - it applies to the built-in renderer alone, since a foreign renderer's subscription is not ours to narrow;
 *  - it requires a credential, so it can never mask the sign-in states [haSignInPending] handles;
 *  - it ends on an explicit answer, either way, so declining still gets a dashboard.
 *
 * A panel that was already configured before this question existed reports answered, so an upgrade never
 * stops rendering — the hold is for panels being set up, not for panels already working.
 */
internal fun entityFilterQuestionPending(
    builtinRenderer: Boolean,
    haUrl: String,
    haToken: String,
    haRefreshToken: String,
    entityFilterAnswered: Boolean,
    setupEverCompleted: Boolean = false,
    entityFilterEnabled: Boolean = false,
): Boolean = builtinRenderer &&
    // The filter being ON is self-evident proof the question is moot: it cannot be true on a fresh panel
    // (the setting defaults off) and it is true on every panel that has ever been through this. Derived at
    // the moment of the check rather than trusted from a flag written once at startup — which is what failed.
    // Three fleet panels were found stranded on the hold screen with the filter already enabled, because the
    // one-shot migration below had run at a moment when the configuration read back blank and so recorded
    // "not a pre-existing install". A durable fact about the panel beats a flag captured at one instant.
    !entityFilterEnabled &&
    // A panel that has ALREADY finished setup is never held, whatever the answer flag says. The flag is new,
    // so it defaults false on every panel that upgrades — and without this an upgrade stops a working panel
    // from showing its dashboard to ask a question it was never asked before. Found exactly that way, on two
    // configured panels, by the synthetic canary. Holding a first render is protecting a first impression;
    // holding a panel that already had one is a regression, and an optimisation question never justifies it.
    !setupEverCompleted &&
    !entityFilterAnswered &&
    haUrl.isNotBlank() &&
    (haToken.isNotBlank() || haRefreshToken.isNotBlank())

internal fun panelHaOAuthStartUrl(haUrl: String): String =
    "http://127.0.0.1:8888/api/v1/ha/oauth/panel-start?ha_url=${URLEncoder.encode(haUrl.trim().trimEnd('/'), "UTF-8")}"

internal fun panelHaOAuthNavigationAllowed(configuredUrl: String, candidateUrl: String): Boolean =
    dashboardNavigationAllowed(configuredUrl, candidateUrl) || isPanelHaOAuthCallback(candidateUrl)

internal fun isPanelHaOAuthCallback(candidateUrl: String): Boolean = runCatching {
    val uri = URI(candidateUrl.trim())
    val scheme = uri.scheme?.lowercase() ?: return@runCatching false
    val host = uri.host?.lowercase() ?: return@runCatching false
    scheme == "http" &&
        host in setOf("127.0.0.1", "localhost") &&
        (uri.port == -1 || uri.port == 8888) &&
        uri.path == HA_OAUTH_CALLBACK_PATH
}.getOrDefault(false)

/**
 * Exact document origins that may inherit panel startup scripts. This mirrors [dashboardNavigationAllowed]:
 * HTTPS is restricted to its configured origin, while an HTTP configuration also admits only the same-host
 * HTTPS upgrade using the same explicit port (or the HTTPS default when neither URL has one).
 */
internal fun dashboardDocumentStartOrigins(configuredUrl: String): Set<String> {
    val configured = URI(configuredUrl.trim())
    val configuredOrigin = EntityFilterProtocol.origin(configuredUrl)
    if (!configured.scheme.equals("http", ignoreCase = true)) return setOf(configuredOrigin)
    val https = URI(
        "https",
        null,
        configured.host?.lowercase() ?: throw IllegalArgumentException("ha_url must contain a host"),
        configured.port,
        null,
        null,
        null,
    )
    return linkedSetOf(configuredOrigin, EntityFilterProtocol.origin(https.toString()))
}

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
