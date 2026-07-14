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
