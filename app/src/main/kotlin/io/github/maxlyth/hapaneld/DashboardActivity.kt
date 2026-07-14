package io.github.maxlyth.hapaneld

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import io.github.maxlyth.hapaneld.control.BottomSwipeDetector
import io.github.maxlyth.hapaneld.control.BuiltinDashboard
import io.github.maxlyth.hapaneld.dashboard.EntityFilterProtocol
import io.github.maxlyth.hapaneld.dashboard.EntityFilterTelemetry
import io.github.maxlyth.hapaneld.dashboard.EntityLearningProtocol
import io.github.maxlyth.hapaneld.dashboard.EntityLearningRuntime
import org.json.JSONObject
import java.io.File
import java.net.NetworkInterface
import java.util.Collections

/**
 * Built-in dashboard renderer (experimental): a full-screen WebView onto the configured Home
 * Assistant URL, signed in through the frontend's documented external-auth bridge — the same
 * `?external_auth=1` + `window.externalApp` contract the HA Companion app uses. Requires `ha_url`
 * and `ha_token` (a long-lived access token set at provisioning); with either blank this activity
 * shows nothing useful, so entry points should gate on [Config.haUrl].
 *
 * Deliberately NOT a Companion replacement: no notifications, no sensors, no media/file-chooser
 * support — users who need those run the Companion app instead. The renderer's whole job is to
 * paint the dashboard and stay alive: a killed renderer process is rebuilt in place rather than
 * crashing the app (the page-level half of the never-blank guarantee).
 */
class DashboardActivity : AppCompatActivity() {

    private var web: WebView? = null
    private var swipe: SwipeRefreshLayout? = null
    private var root: FrameLayout? = null                       // holds the swipe layout + fullscreen video
    private var entityFilterSignature = "disabled"
    private var entityFilterLease: EntityFilterTelemetry.Lease? = null
    private var customView: View? = null                        // active onShowCustomView (fullscreen) view
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // --- long-run reliability state (all touched on the main thread only) ---
    private val main = Handler(Looper.getMainLooper())
    private val rendererGate = RendererGenerationGate()
    private var rendererGeneration = 0L
    private var activityOwner = 0L
    private var conn: ConnectivityManager? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var networkRecovery: NetworkRecoveryGate? = null
    // Set in onDestroy. Bridge/network callbacks marshal onto the main thread, so one of their posts can
    // land AFTER onDestroy's removeCallbacksAndMessages and re-arm the self-perpetuating watchdog on a
    // dead activity. Every posted handler checks this first so nothing runs (or re-schedules) post-destroy.
    @Volatile private var destroyed = false
    // Stored so onDestroy can clear it by identity — an old instance destroyed after a new one's onCreate
    // must not wipe the new instance's registration (see BuiltinDashboard.clearScreenListener).
    private val screenListener: (Boolean) -> Unit = { awake -> runOnUiThread { onScreenChanged(awake) } }
    // Frontend-handshake watchdog: the page "loading" (onPageFinished) is NOT health — the frontend JS
    // app must post `connection-status: connected` on the external bus. Until it does, we retry with
    // backoff (a panel has no buttons to press). This catches the "loaded onto a blank/dead frontend"
    // class that onPageFinished and the crash budget both miss. The watchdog runs ONLY while the screen is
    // awake: a screen-off pauses JS timers, so a paused WebView could never complete the handshake and the
    // watchdog would just reload-loop all night behind a dark panel.
    private var frontendConnected = false
    private val retryPolicy = DashboardRetryPolicy()
    private var clearedThisLoad = false
    private val watchdog = Runnable { onWatchdogTimeout() }
    // After a screen-off reload (memory shed), let the fresh page load with timers LIVE for a settle
    // window, then freeze — so we never reload into a frozen WebView, but also never leave it churning
    // behind a dark screen forever. Fires only if the frontend hasn't already connected (which freezes early).
    private val darkSettle = Runnable {
        if (!destroyed && BuiltinDashboard.ownsActivity(activityOwner) && !screenAwake) web?.pauseTimers()
    }
    // Time-based memory ceiling. onTrimMemory's RUNNING_* levels are deprecated + never delivered on
    // Android 14 (the fleet), so a periodic check is the portable ceiling: reload invisibly at the next
    // screen-off past RELOAD_INTERVAL_MS, or force a (brief, visible) reload past RELOAD_HARD_MS so a
    // never-sleeping panel still sheds accreted WebView memory ~daily.
    private val periodicCheck = Runnable { onPeriodicCheck() }
    private var lastFullLoadAt = 0L
    private var reloadPending = false
    private var screenAwake = true
    // App→frontend external-bus command ids (navigate etc.); replies echo the id back (logged).
    private var busId = 0
    // Auth-failure latch. A terminal login-settings rejection (refresh token/client id, repeated
    // auth-invalid from the frontend) must not become an infinite reload loop on an unattended panel —
    // no unchanged retry can repair it. Latch: stop the retry machinery and show a clear on-panel
    // message naming the fix. Unlatched by a new load (MQTT reload/navigate, config change) or a
    // successful connect. Transient failures (HA down) never count toward the latch.
    private var authInvalids = 0
    private var refreshRejects = 0
    private var authLatched = false
    // Idle return-to-home: after N minutes with no touch (configurable, 0 = off), navigate the frontend
    // back to the home dashboard via the bus — cheaper than a reload, bounds history growth from casual
    // navigation, and snaps a wandering kiosk back where it belongs.
    private var lastTouchAt = 0L
    private val idleCheck = Runnable { onIdleCheck() }
    // Last light pull-to-refresh — a second pull inside the window escalates to a full hard reload.
    private var lastLightRefreshAt = 0L
    // True while the branded "Reconnecting…" page has replaced a failed main-frame load. Every reload
    // path must then loadUrl() the real dashboard rather than reload() (which would reload the
    // interstitial itself). Cleared on any real load or connect.
    private var interstitialShown = false
    private var waitingStatus: TextView? = null
    private var waitingStage: TextView? = null
    private var waitingProgress: ProgressBar? = null
    private var waitingEstimateMs = 0L
    private var waitingEstimateLearned = false
    private var waitingStartedAt = 0L
    private val waitingTick = object : Runnable {
        override fun run() {
            if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner) || waitingStatus == null) return
            val elapsed = SystemClock.elapsedRealtime() - waitingStartedAt
            val expected = ((waitingEstimateMs + 2_500L) / 5_000L) * 5L
            waitingStatus?.text = if (waitingEstimateLearned) {
                "${elapsed / 1_000L}s elapsed  ·  usually about ${expected}s"
            } else {
                "${elapsed / 1_000L}s elapsed  ·  learning this panel's timing"
            }
            waitingProgress?.progress = networkWaitProgress(elapsed, waitingEstimateMs)
            waitingStage?.text = startupNetworkStage(startupNetworkSnapshot())
            main.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityOwner = BuiltinDashboard.acquireActivityOwner()
        supportActionBar?.hide()
        val config = Config(this)
        if (config.haUrl.isBlank()) {
            // We're a HOME activity: never finish to a blank home. With no URL to render, hand off to
            // the admin launcher (also a HOME activity) so the panel is never stranded.
            Log.w(TAG, "no ha_url configured — opening the admin launcher instead")
            fallbackToLauncher()
            return
        }
        configureEntityFilter(config)
        // Freeze the WebView when the panel screen is off (CPU/heat/memory), and reload the moment
        // connectivity returns if the frontend isn't connected — registered for the activity's lifetime.
        BuiltinDashboard.setScreenListener(screenListener)
        // Adopt the CURRENT screen state — an instance created while the panel is dark (a relaunch at
        // night) must not assume the screen is on: it would arm the handshake watchdog against a page
        // that's about to be frozen, and never freeze the WebView until the next real transition.
        screenAwake = BuiltinDashboard.screenAwakeNow
        val networkAvailable = registerNetworkCallback()
        main.postDelayed(periodicCheck, PERIODIC_CHECK_MS)
        lastTouchAt = SystemClock.elapsedRealtime()
        main.postDelayed(idleCheck, IDLE_CHECK_MS)
        if (networkAvailable) buildAndLoad(config) else showWaitingForNetwork()
        // Created dark: let the initial load settle, then freeze (onLoadStarted skipped the watchdog;
        // onConnectionStatus freezes earlier if the frontend connects first).
        if (!screenAwake) main.postDelayed(darkSettle, DARK_SETTLE_MS)
    }

    /** Any touch (the WebView consumes them, but the activity sees them first) resets the idle clock. */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        lastTouchAt = SystemClock.elapsedRealtime()
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        destroyed = true
        rendererGate.close()
        entityFilterLease?.let(EntityFilterTelemetry::stop)
        entityFilterLease = null
        BuiltinDashboard.releaseActivityOwner(activityOwner)
        BuiltinDashboard.clearScreenListener(screenListener)
        netCallback?.let { cb -> runCatching { conn?.unregisterNetworkCallback(cb) } }
        main.removeCallbacksAndMessages(null)
        teardownWeb()
        super.onDestroy()
    }

    /** Fully release the WebView so a long-lived process doesn't leak it: stop background loading,
     *  detach the JS bridge, destroy. `resumeTimers()` is process-global and pauseTimers() may have been
     *  called on a screen-off — without this, an activity destroyed while dark would leave JS timers
     *  frozen for every future WebView in this forever process (a never-blank violation). */
    private fun teardownWeb() {
        rendererGate.invalidate()
        main.removeCallbacks(watchdog)
        main.removeCallbacks(darkSettle)
        customView?.let { view -> runCatching { root?.removeView(view) } }
        runCatching { customViewCallback?.onCustomViewHidden() }
        customView = null
        customViewCallback = null
        web?.let { w ->
            runCatching { w.resumeTimers() }
            runCatching { w.loadUrl("about:blank") }
            (w.parent as? ViewGroup)?.removeView(w)
            runCatching { w.removeJavascriptInterface("externalApp") }
            runCatching { w.destroy() }
        }
        web = null
        swipe = null
        frontendConnected = false
    }

    private fun rendererCurrent(generation: Long, view: WebView? = null): Boolean =
        !destroyed && BuiltinDashboard.ownsActivity(activityOwner) &&
            rendererGate.owns(generation) && (view == null || web === view)

    private fun entityFilterSignature(config: Config): String {
        val learning = ":learning=${config.dashboardEntityLearningEnabled}"
        if (!config.dashboardEntityFilterEnabled) return "disabled$learning"
        return runCatching {
            val ids = EntityFilterProtocol.normalize(config.dashboardEntityFilterIds)
            if (ids.isEmpty()) "disabled$learning" else "enabled:${EntityFilterProtocol.hash(ids)}:${config.haUrl}$learning"
        }.getOrDefault("invalid$learning")
    }

    /** Prepare the exact allow-list for document-start interception. Unsupported/invalid state degrades
     *  to the ordinary direct HA connection without changing the persisted opt-in. */
    private fun configureEntityFilter(config: Config) {
        entityFilterSignature = entityFilterSignature(config)
        if (!entityFilterSignature.startsWith("enabled:")) {
            entityFilterLease = EntityFilterTelemetry.stopped()
            return
        }
        val ids = runCatching { EntityFilterProtocol.normalize(config.dashboardEntityFilterIds) }
            .getOrElse {
                Log.e(TAG, "invalid entity-filter configuration", it)
                entityFilterSignature = "disabled"
                val lease = EntityFilterTelemetry.stopped()
                entityFilterLease = lease
                EntityFilterTelemetry.failed(lease, "invalid_configuration")
                EntityFilterTelemetry.directFallback(lease)
                return
            }
        val lease = EntityFilterTelemetry.started(ids)
        entityFilterLease = lease
        if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Log.w(TAG, "entity filter unavailable: document-start script unsupported")
            EntityFilterTelemetry.failed(lease, "document_start_unsupported")
            EntityFilterTelemetry.directFallback(lease)
            return
        }
    }

    /** Document-start installation failed before page load. Continue on the ordinary native socket so
     *  the opt-in can never strand the dashboard; telemetry makes the unfiltered fallback explicit. */
    private fun fallbackFromEntityFilterInterceptor(error: Throwable) {
        Log.e(TAG, "failed to install entity-filter subscription interceptor", error)
        entityFilterSignature = "disabled"
        val lease = entityFilterLease ?: return
        if (EntityFilterTelemetry.isActive(lease)) {
            EntityFilterTelemetry.failed(lease, "document_start_install")
            EntityFilterTelemetry.directFallback(lease)
        }
    }

    /**
     * Screen on/off fan-out. Screen ON → resume rendering + JS timers, and re-arm the handshake watchdog
     * if the frontend isn't connected (recover a page that broke while frozen). Screen OFF → pause
     * rendering and disarm the watchdog (no handshake retries while timers are frozen); then either shed
     * memory with an invisible reload (kept live through a settle window, see [darkSettle]) if one is due,
     * or freeze JS timers. `pauseTimers()` is process-global, which is fine — the dashboard is our only
     * live WebView.
     */
    private fun onScreenChanged(awake: Boolean) {
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return
        screenAwake = awake
        val w = web ?: return
        if (awake) {
            main.removeCallbacks(darkSettle)
            w.resumeTimers(); w.onResume()
            lastTouchAt = SystemClock.elapsedRealtime() // a wake implies presence — don't instantly snap home
            if (!frontendConnected && !authLatched) armWatchdog(INITIAL_HANDSHAKE_MS)
        } else {
            w.onPause()
            main.removeCallbacks(watchdog) // a paused WebView can't complete the handshake — don't loop
            if (authLatched) { w.pauseTimers(); return }
            if (reloadDue()) {
                // Reload to shed memory, but DON'T pause timers yet — a paused WebView can't load. Let it
                // run; onConnectionStatus freezes it the moment it connects, or darkSettle freezes it after
                // the settle window if it never does. Either way it doesn't churn all night.
                reloadPending = false
                lastFullLoadAt = SystemClock.elapsedRealtime()
                doReloadNoWatchdog("quiet reload (screen off, due)")
                main.removeCallbacks(darkSettle); main.postDelayed(darkSettle, DARK_SETTLE_MS)
            } else {
                w.pauseTimers()
            }
        }
    }

    /** A reload is worthwhile now if memory pressure asked for one, or it's been a long time since the
     *  last full load (WebView memory accretes over days). */
    private fun reloadDue(): Boolean =
        reloadPending || (SystemClock.elapsedRealtime() - lastFullLoadAt > RELOAD_INTERVAL_MS)

    /** Portable memory ceiling (see [periodicCheck]): reload invisibly at a screen-off past the interval,
     *  or force a brief visible reload past the hard cap so a panel that never sleeps still sheds memory. */
    private fun onPeriodicCheck() {
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return
        if (authLatched) { main.postDelayed(periodicCheck, PERIODIC_CHECK_MS); return }
        val idle = SystemClock.elapsedRealtime() - lastFullLoadAt
        when {
            idle < RELOAD_INTERVAL_MS -> {}
            !screenAwake -> {
                lastFullLoadAt = SystemClock.elapsedRealtime()
                doReloadNoWatchdog("periodic (screen off, ${idle / 3_600_000}h idle)")
                main.removeCallbacks(darkSettle); main.postDelayed(darkSettle, DARK_SETTLE_MS)
            }
            idle >= RELOAD_HARD_MS -> doReload("periodic hard cap (${idle / 3_600_000}h idle)")
            else -> reloadPending = true // visible now — wait for the next screen-off to reload invisibly
        }
        main.postDelayed(periodicCheck, PERIODIC_CHECK_MS)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner) || authLatched) return
        // UI_HIDDEN is a routine lifecycle signal, NOT memory pressure — it's delivered on every
        // screen-off (activities stop when the display sleeps), so reacting to it made every screen-off
        // full-reload the frontend, defeating the 6-hour quiet-reload ceiling in onScreenChanged.
        if (level == TRIM_MEMORY_UI_HIDDEN) return
        // Any real trim signal means shed the WebView's accreted memory. Gate at RUNNING_LOW so we catch
        // BOTH the pre-14 foreground RUNNING_* levels AND the pressure levels Android 14 actually
        // delivers (BACKGROUND / MODERATE / COMPLETE, all ≥ RUNNING_LOW's value) — the previous
        // RUNNING_MODERATE..CRITICAL window was dead code on the fleet's Android-14 panels.
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            reloadPending = true
            // If we're not the visible dashboard, reload right now rather than risk an OOM renderer kill.
            if (!screenAwake || !BuiltinDashboard.foreground) {
                reloadPending = false
                lastFullLoadAt = SystemClock.elapsedRealtime()
                doReloadNoWatchdog("memory pressure L$level")
                if (!screenAwake) { main.removeCallbacks(darkSettle); main.postDelayed(darkSettle, DARK_SETTLE_MS) }
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        if (!destroyed && BuiltinDashboard.ownsActivity(activityOwner) && !authLatched &&
            (!screenAwake || !BuiltinDashboard.foreground)) {
            lastFullLoadAt = SystemClock.elapsedRealtime()
            doReloadNoWatchdog("onLowMemory")
            if (!screenAwake) { main.removeCallbacks(darkSettle); main.postDelayed(darkSettle, DARK_SETTLE_MS) }
        }
    }

    /** Hand off to ha-paneld's admin launcher and finish — the never-strand fallback for a missing
     *  WebView, an unconfigured URL, or a crash-looping renderer. */
    private fun fallbackToLauncher() {
        runCatching {
            startActivity(Intent(this, AdminLauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.e(TAG, "admin-launcher fallback failed: ${it.message}") }
        finish()
    }

    /** Allow a renderer rebuild if within budget. The budget lives in [BuiltinDashboard] (process-
     *  global), NOT here: fallbackToLauncher() finishes this instance and the kiosk/watchdog return
     *  loop starts a fresh one — a per-instance counter would reset every relaunch, turning a reliably
     *  crashing page into an infinite fallback/relaunch churn. Exhaustion engages the renderer latch
     *  ([BuiltinDashboard.rendererLatched]), which suppresses automatic relaunches for a cooldown. */
    private fun allowRebuild(): Boolean =
        BuiltinDashboard.consumeRebuildBudget(SystemClock.elapsedRealtime())

    /** A singleTask relaunch reaching the live renderer means one of three things, disambiguated here:
     *  a **navigate** ([BuiltinDashboard.navPath] set — one-shot, consumed), an explicit **reload**
     *  ([BuiltinDashboard.consumeReloadRequest] — set by reloadDashboard / a credential change), or a
     *  plain **bring-to-foreground** (the kiosk/watchdog return loops). A healthy foregrounded page is
     *  left untouched — snapping back from Recents must not blank the dashboard with a full reload. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return
        val config = Config(this)
        if (config.haUrl.isBlank()) {
            // Same never-strand guard as onCreate: the URL was cleared while we were running — a load
            // would build a scheme-less garbage URL behind an eternal "Reconnecting…" interstitial.
            Log.w(TAG, "ha_url cleared — opening the admin launcher instead")
            fallbackToLauncher()
            return
        }
        // The filter endpoint reloads this singleTask activity after committing. Document-start scripts
        // cannot be replaced in an existing WebView, so a filter-set change or learning-mode change
        // deliberately rebuilds only the WebView while keeping the foreground service and app process alive.
        val nextFilterSignature = entityFilterSignature(config)
        if (nextFilterSignature != entityFilterSignature) {
            Log.i(TAG, "entity instrumentation changed — rebuilding dashboard WebView")
            unlatchAuth("entity-filter change")
            retryPolicy.reset()
            interstitialShown = false
            teardownWeb()
            configureEntityFilter(config)
            buildAndLoad(config)
            return
        }
        val nav = BuiltinDashboard.consumeNavPath()
        val reload = BuiltinDashboard.consumeReloadRequest()
        val w = web
        val healthy = w != null && frontendConnected && !authLatched && !interstitialShown
        if (nav == null && !reload && healthy) return   // foreground-only: page is fine, nothing to do
        if (nav != null && !reload && healthy) {
            // Navigate on a healthy page: an instant bus re-navigate (same as idle-return), not a full
            // page load — the JS bundle + websocket stay live.
            Log.i(TAG, "navigate -> /$nav (bus)")
            w.evaluateJavascript(ExternalAuthProtocol.navigateCommand(++busId, nav), null)
            return
        }
        unlatchAuth("new load") // a deliberate reload/navigate (or a config change) is the retry consent
        retryPolicy.reset()
        interstitialShown = false
        if (w == null) { buildAndLoad(config); return }  // defensive: relaunched with no WebView built
        // Re-apply force-dark (only set at WebView creation otherwise) and, on the no-system-dark-mode
        // panels, write the panel's dark_mode into HA's own per-device theme store before the fresh
        // load — that store is what actually re-renders HA (see selectedThemeJs; force-dark alone is a
        // no-op on HA). The write must precede loadUrl: localStorage is synchronous and origin-scoped,
        // so the reloaded frontend boots straight into the new scheme.
        applyForceDark(w)
        val url = ExternalAuthProtocol.dashboardUrl(config.haUrl, nav ?: config.homeDashboard)
        val generation = rendererGeneration
        if (android.os.Build.VERSION.SDK_INT < 29) {
            // The theme write must COMPLETE before the navigation — evaluateJavascript is async (queued
            // to the JS thread), and a loadUrl issued right after can tear the page down first, losing
            // the write. The result callback runs after evaluation, on the UI thread.
            w.evaluateJavascript(ExternalAuthProtocol.selectedThemeJs(Config(this).darkMode, onlyIfAbsent = false)) {
                if (rendererCurrent(generation, w)) w.loadUrl(url)
            }
        } else {
            w.loadUrl(url)
        }
        onLoadStarted()
    }

    // --- frontend-handshake watchdog + reconnect ---

    /** Called after every page load that should be health-checked (initial, navigate, watchdog reload,
     *  network-regain reload): reset the connected state + arm the handshake watchdog — but only while the
     *  screen is awake, since a paused WebView can't run the JS that completes the handshake. */
    private fun onLoadStarted() {
        frontendConnected = false
        clearedThisLoad = false
        lastFullLoadAt = SystemClock.elapsedRealtime()
        BuiltinDashboard.recordLoadStart(lastFullLoadAt) // TTI origin (first call since process start = cold)
        if (screenAwake) armWatchdog(INITIAL_HANDSHAKE_MS) else main.removeCallbacks(watchdog)
    }

    private fun armWatchdog(ms: Long) {
        if (destroyed) return
        main.removeCallbacks(watchdog); main.postDelayed(watchdog, ms)
    }

    private fun onWatchdogTimeout() {
        // Never retry on a dead activity, while frozen (screen off), or latched — all runaway loops.
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner) || frontendConnected || !screenAwake || authLatched) return
        Log.w(TAG, "frontend handshake watchdog fired (no connection-status:connected) — reloading")
        reloadTarget()
        lastFullLoadAt = SystemClock.elapsedRealtime()
        BuiltinDashboard.recordRendererReload(lastFullLoadAt) // involuntary: handshake stalled
        BuiltinDashboard.recordLoadStart(lastFullLoadAt)      // warm TTI origin for the recovery load
        clearedThisLoad = false
        armWatchdog(retryPolicy.afterRetry())
    }

    /** Fired from the external-bus `connection-status` message: the frontend telling us it connected or
     *  dropped its websocket. Posted onto the main thread, so it can race onDestroy — guard on [destroyed]. */
    private fun onConnectionStatus(event: String, generation: Long) {
        if (!rendererCurrent(generation)) return
        if (event == "connected") {
            frontendConnected = true
            BuiltinDashboard.recordConnected(SystemClock.elapsedRealtime()) // TTI: load-start → interactive
            interstitialShown = false // real page demonstrably loaded
            unlatchAuth("frontend connected") // auth demonstrably works — clear any stale latch + counters
            main.removeCallbacks(watchdog)
            retryPolicy.reset()
            if (!clearedThisLoad) {
                // Drop the auth/redirect history entries so Back can't reach a stale login page, and
                // persist cookies so an unclean process death right after login doesn't lose the session.
                web?.clearHistory(); clearedThisLoad = true
                runCatching { CookieManager.getInstance().flush() }
            }
            captureDashboardTheme(generation)
            // If we connected while the screen is off (a screen-off memory reload), freeze now — the fresh
            // page is loaded + connected, so there's nothing left to do behind the dark screen.
            if (!screenAwake) { main.removeCallbacks(darkSettle); web?.pauseTimers() }
            Log.i(TAG, "frontend connected")
        } else {
            val wasConnected = frontendConnected
            frontendConnected = false
            // auth-invalid is the frontend saying HA refused its token. One can be a race around a token
            // refresh; several in a row mean the credential is dead — latch instead of reload-looping.
            if (event == "auth-invalid" && ++authInvalids >= AUTH_INVALID_LATCH) { latchAuthFailure("repeated auth-invalid") ; return }
            // disconnected: give the frontend's own reconnect a grace window, then reload — but only
            // while awake (a frozen WebView can't reconnect; wake re-arms the handshake watchdog).
            if (screenAwake && !authLatched) {
                val delay = retryPolicy.connectionFailureDelay(wasConnected)
                Log.i(TAG, "frontend '$event' — arming retry watchdog in ${delay}ms (wasConnected=$wasConnected)")
                armWatchdog(delay)
            }
        }
    }

    /** Remember HA's own per-device theme so the native pre-WebView launch screen matches it on the
     * next boot. The JS returns only true/false/null, avoiding any localStorage contents in logs. */
    private fun captureDashboardTheme(generation: Long) {
        if (!rendererCurrent(generation)) return
        val script = """(function(){try{var t=JSON.parse(localStorage.getItem('selectedTheme')||'null');return t&&typeof t.dark==='boolean'?t.dark:null}catch(e){return null}})()"""
        web?.evaluateJavascript(script) { result ->
            if (!rendererCurrent(generation)) return@evaluateJavascript
            when (result) {
                "true" -> Config(this).setDashboardThemeDark(true)
                "false" -> Config(this).setDashboardThemeDark(false)
            }
        }
    }

    /** The token refresher got a terminal rejection from HA. One could be a freak intermediary event;
     *  two consecutive responses mean the unchanged login settings cannot recover — latch. */
    private fun onAuthRejected(generation: Long) {
        if (!rendererCurrent(generation) || authLatched) return
        if (++refreshRejects >= REFRESH_REJECT_LATCH) latchAuthFailure("login settings rejected by HA")
    }

    /** Stop the retry machinery and show a clear on-panel message naming the fix — a panel retrying a
     *  rejected login settings forever is churn with no exit, and a silent blank is a never-blank violation.
     *  The `:8888` health warnings surface the same state off-panel via [BuiltinDashboard.authLatched]. */
    private fun latchAuthFailure(why: String) {
        if (authLatched) return
        authLatched = true
        BuiltinDashboard.setActivityAuthLatched(activityOwner, true)
        main.removeCallbacks(watchdog)
        Log.e(TAG, "auth latched ($why) — showing fix instructions, no further retries until reload/reconfig")
        val ip = io.github.maxlyth.hapaneld.control.Diagnostics.ipAddress()
        val cfg = if (ip != null) "http://$ip:8888/configure" else "port 8888 of this panel's IP address"
        web?.loadDataWithBaseURL(
            null,
            """<!doctype html><html><body style="background:#121212;color:#eee;font-family:sans-serif;
               display:flex;align-items:center;justify-content:center;height:100vh;margin:0">
               <div style="max-width:80%;text-align:center">
               <h1 style="color:#f66">Home Assistant sign-in rejected</h1>
               <p style="font-size:1.3em">This panel's saved Home Assistant login settings were rejected,
               so the dashboard has stopped retrying.</p>
               <p style="font-size:1.3em"><b>Fix:</b> open <b>$cfg</b> &rarr; Dashboard and check the
               refresh token and OAuth client ID, or set a long-lived access token. The dashboard reloads automatically
               when the configuration changes.</p>
               </div></body></html>""",
            "text/html", "utf-8", null,
        )
    }

    private fun unlatchAuth(why: String) {
        authInvalids = 0; refreshRejects = 0
        if (!authLatched) return
        authLatched = false
        BuiltinDashboard.setActivityAuthLatched(activityOwner, false)
        Log.i(TAG, "auth latch cleared ($why)")
    }

    private fun onNetworkAvailable() {
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner) || authLatched) return
        // Ignore only the registration callback when the activity started online. If it started without
        // a default network, its first onAvailable is the boot-time Wi-Fi arrival and must recover now.
        if (networkRecovery?.onAvailable() != true) return
        if (web == null) {
            val waitMs = (SystemClock.elapsedRealtime() - waitingStartedAt).coerceAtLeast(0L)
            if (waitingStartedAt > 0L) Config(this).setLastNetworkWaitMs(waitMs)
            Log.i(TAG, "network became available during startup after ${waitMs}ms — creating dashboard WebView")
            waitingStatus = null
            waitingStage = null
            waitingProgress = null
            main.removeCallbacks(waitingTick)
            retryPolicy.reset()
            buildAndLoad(Config(this))
            if (!screenAwake) {
                main.removeCallbacks(darkSettle)
                main.postDelayed(darkSettle, DARK_SETTLE_MS)
            }
            return
        }
        if (frontendConnected || !screenAwake) return // frozen page reconnects itself on wake
        Log.i(TAG, "network regained while frontend not connected — reloading immediately")
        retryPolicy.reset() // a changed environment deserves a fresh fast cadence if HA is still starting
        doReload("network regained")
    }

    /** Pull-to-refresh: a full `WebView.reload()` re-boots the entire frontend app (seconds of blank),
     *  which is overkill for a healthy page — ask the running frontend to re-navigate to the current
     *  view over the external bus instead (instant; JS bundle + websocket stay live). The full reload is
     *  kept for the case that actually needs it: a frontend that isn't connected. */
    private fun lightRefresh() {
        val w = web
        if (w == null || !frontendConnected) { doReload("pull-to-refresh (frontend not connected)"); return }
        // Pull twice in quick succession = full hard reload (the browser's reload-vs-hard-reload
        // pattern, no extra UI): the light navigate re-renders from the frontend's in-memory config,
        // which can't pick up a YAML-mode dashboard edit — a second deliberate pull escalates.
        val now = SystemClock.elapsedRealtime()
        if (now - lastLightRefreshAt < HARD_REFRESH_WINDOW_MS) {
            lastLightRefreshAt = 0L
            doReload("pull-to-refresh (hard, double-pull)")
            return
        }
        lastLightRefreshAt = now
        val path = runCatching { android.net.Uri.parse(w.url).path }.getOrNull().orEmpty()
            .ifBlank { Config(this).homeDashboard }
        Log.i(TAG, "pull-to-refresh -> light navigate ($path)")
        w.evaluateJavascript(ExternalAuthProtocol.navigateCommand(++busId, path), null)
        // No page-load events fire for a bus navigate — clear the spinner after a short beat.
        val generation = rendererGeneration
        main.postDelayed({ if (rendererCurrent(generation, w)) swipe?.isRefreshing = false }, LIGHT_REFRESH_SPINNER_MS)
    }

    /** Idle return-to-home (opt-in): after the configured minutes with no touch, swap the frontend back
     *  to the home dashboard — a bus navigate with replace, so it's instant and keeps history flat. */
    private fun onIdleCheck() {
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return
        main.postDelayed(idleCheck, IDLE_CHECK_MS)
        if (!screenAwake || !frontendConnected || authLatched) return
        val config = Config(this)
        val minutes = config.dashboardIdleReturnMin
        val home = config.homeDashboard.trim().trim('/')
        if (minutes <= 0 || home.isEmpty()) return
        if (SystemClock.elapsedRealtime() - lastTouchAt < minutes * 60_000L) return
        val current = runCatching { android.net.Uri.parse(web?.url).path }.getOrNull().orEmpty().trim('/')
        if (current == home) return
        Log.i(TAG, "idle ${minutes}min — returning to home dashboard (/$home)")
        web?.evaluateJavascript(ExternalAuthProtocol.navigateCommand(++busId, home), null)
    }

    /** Register once and return whether Android already has a default network. When false, onCreate
     *  holds the WebView back entirely: loading HA's cached shell while offline only exposes HA's own
     *  long connection-failed countdown on top of Chromium's main-frame failure. */
    private fun registerNetworkCallback(): Boolean {
        conn = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val initiallyAvailable = conn?.activeNetwork != null
        networkRecovery = NetworkRecoveryGate(initiallyAvailable)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { runOnUiThread { onNetworkAvailable() } }
            override fun onLost(network: Network) { runOnUiThread {
                networkRecovery?.onLost()
            } }
        }
        netCallback = cb
        // One default-network callback for the activity's lifetime (unregistered in onDestroy) — never a
        // per-load registration, which would hit Android's per-app callback limit on a forever process.
        val registered = runCatching {
            val manager = conn ?: error("ConnectivityManager unavailable")
            manager.registerDefaultNetworkCallback(cb)
        }.onFailure { Log.w(TAG, "network callback register failed: ${it.message}") }.isSuccess
        // Never strand on the waiting screen if this OEM cannot register the callback: fall back to the
        // existing WebView watchdog path, which can still recover by polling loads.
        return initiallyAvailable || !registered
    }

    /** One quiet startup state while Android brings networking up. This is native rather than cached
     *  WebView content, so neither Chromium's error UI nor HA's 60-second reconnect page can appear. */
    private fun showWaitingForNetwork() {
        val dm = resources.displayMetrics
        val density = dm.density
        val hDp = (dm.heightPixels / density).toInt()
        val compact = hDp < 560
        val config = Config(this)
        waitingEstimateLearned = config.lastNetworkWaitMs > 0L
        waitingEstimateMs = config.lastNetworkWaitMs.takeIf { it > 0L } ?: DEFAULT_NETWORK_WAIT_MS
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val fallbackDark = if (android.os.Build.VERSION.SDK_INT >= 29) systemDark else config.darkMode
        val dark = config.dashboardThemeDark ?: fallbackDark
        val bg = Color.parseColor(if (dark) "#111111" else "#ffffff")
        val body = Color.parseColor(if (dark) "#c8ccd2" else "#2a2e34")
        val subtle = Color.parseColor(if (dark) "#8a8f99" else "#5a6068")
        val accent = Color.parseColor(if (dark) "#4a9eff" else "#1669d6")
        val track = Color.parseColor(if (dark) "#30343a" else "#dce1e7")
        val wordmark = ImageView(this).apply {
            val themed = Configuration(resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            }
            setImageDrawable(createConfigurationContext(themed).getDrawable(R.drawable.wordmark))
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            contentDescription = "ha-paneld"
        }
        val running = TextView(this).apply {
            text = "v${BuildConfig.VERSION_NAME} · running"
            setTextColor(subtle)
            textSize = 12f
            gravity = Gravity.CENTER
        }
        val explanation = TextView(this).apply {
            text = "The panel service is running."
            setTextColor(body)
            textSize = if (compact) 12.5f else 14f
            gravity = Gravity.CENTER
        }
        val stage = TextView(this).apply {
            text = startupNetworkStage(startupNetworkSnapshot())
            setTextColor(body)
            textSize = if (compact) 14f else 16f
            gravity = Gravity.CENTER
        }
        val destination = TextView(this).apply {
            text = "Home Assistant will open automatically"
            setTextColor(accent)
            textSize = if (compact) 14f else 16f
            gravity = Gravity.CENTER
        }
        val status = TextView(this).apply {
            setTextColor(subtle)
            textSize = if (compact) 11.5f else 13f
            gravity = Gravity.CENTER
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1_000
            progress = 0
            progressTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(track)
            isIndeterminate = false
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val verticalPad = (if (compact) 16 else 36) * density
            setPadding((24 * density).toInt(), verticalPad.toInt(), (24 * density).toInt(), verticalPad.toInt())
            addView(wordmark, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, ((if (compact) 52 else 72) * density).toInt(),
            ).apply {
                bottomMargin = ((if (compact) 8 else 14) * density).toInt()
                gravity = Gravity.CENTER_HORIZONTAL
            })
            addView(running)
            addView(explanation, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ((if (compact) 16 else 24) * density).toInt() })
            addView(stage, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ((if (compact) 18 else 26) * density).toInt() })
            addView(progress, LinearLayout.LayoutParams(
                ((if (compact) 220 else 280) * density).toInt(), (5 * density).toInt().coerceAtLeast(3),
            ).apply {
                topMargin = ((if (compact) 24 else 32) * density).toInt()
                bottomMargin = ((if (compact) 14 else 18) * density).toInt()
                gravity = Gravity.CENTER_HORIZONTAL
            })
            addView(destination)
            addView(status, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ((if (compact) 7 else 10) * density).toInt() })
        }
        val container = FrameLayout(this).apply {
            setBackgroundColor(bg)
            val colW = minOf(dm.widthPixels - (48 * density).toInt(), (512 * density).toInt())
            addView(ScrollView(this@DashboardActivity).apply {
                isFillViewport = true
                setBackgroundColor(bg)
                addView(content, FrameLayout.LayoutParams(
                    colW, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL,
                ))
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        root = container
        setContentView(container)
        waitingStatus = status
        waitingStage = stage
        waitingProgress = progress
        waitingStartedAt = SystemClock.elapsedRealtime()
        waitingTick.run()
        Log.i(TAG, "no default network at startup — waiting before creating WebView")
    }

    /** Best-effort portable view of pre-default-network progress. Ethernet carrier comes from sysfs
     * when readable; interface/address state uses java.net and works without privileged APIs. */
    private fun startupNetworkSnapshot(): StartupNetworkSnapshot {
        val interfaces = runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces()).filterNot { it.isLoopback }
        }.getOrDefault(emptyList())
        val candidates = interfaces.filter {
            it.name.startsWith("eth") || it.name.startsWith("en") || it.name.startsWith("wlan")
        }
        val linkUp = candidates.any { iface ->
            val carrier = if (iface.name.startsWith("eth") || iface.name.startsWith("en")) {
                runCatching { File("/sys/class/net/${iface.name}/carrier").readText().trim() == "1" }.getOrNull()
            } else null
            carrier ?: runCatching { iface.isUp }.getOrDefault(false)
        }
        val addressAssigned = candidates.any { iface ->
            Collections.list(iface.inetAddresses).any { address ->
                !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress
            }
        }
        return StartupNetworkSnapshot(
            interfacePresent = candidates.isNotEmpty(),
            linkUp = linkUp,
            addressAssigned = addressAssigned,
            defaultNetwork = conn?.activeNetwork != null,
        )
    }

    /** Re-load the dashboard: a plain reload normally, but a fresh loadUrl of the real dashboard when
     *  the WebView is currently showing the "Reconnecting…" interstitial (reloading THAT would just
     *  re-show the interstitial forever). */
    private fun reloadTarget() {
        val w = web ?: return
        if (interstitialShown) {
            interstitialShown = false
            w.loadUrl(currentUrl(Config(this)))
        } else {
            w.reload()
        }
    }

    /** Reload + arm the handshake watchdog (only fires while awake) — for user/health-driven reloads. */
    private fun doReload(reason: String) {
        Log.i(TAG, "reload: $reason")
        reloadTarget()
        onLoadStarted()
    }

    /** Reload without arming the watchdog — for screen-off memory reloads, where the settle timer (not the
     *  watchdog) governs when the page freezes, so the watchdog can't turn a dark reload into a loop. */
    private fun doReloadNoWatchdog(reason: String) {
        Log.i(TAG, "reload: $reason")
        frontendConnected = false
        clearedThisLoad = false
        reloadTarget()
    }

    /** A main-frame load failed (HA down / network out / DNS): replace Android's native gray error page
     *  with a branded dark "reconnecting" screen while the handshake watchdog keeps retrying behind it.
     *  The native error page can't be styled or suppressed any other way, and a wall panel showing
     *  `net::ERR_CONNECTION_REFUSED` between retries reads as broken rather than waiting. */
    private fun showReconnecting(detail: String) {
        if (destroyed || authLatched || interstitialShown) return
        interstitialShown = true
        Log.w(TAG, "main-frame load error ($detail) — showing reconnecting page; watchdog retries continue")
        web?.loadDataWithBaseURL(
            null,
            """<!doctype html><html><body style="background:#121212;color:#eee;font-family:sans-serif;
               display:flex;align-items:center;justify-content:center;height:100vh;margin:0">
               <div style="max-width:80%;text-align:center">
               <h1>Reconnecting to Home Assistant&hellip;</h1>
               <p style="font-size:1.2em;color:#aaa">The dashboard couldn't be reached and will keep
               retrying automatically.</p>
               <p style="color:#666"><small>$detail</small></p>
               </div></body></html>""",
            "text/html", "utf-8", null,
        )
    }

    /** The URL to show: a pending navigate path (consumed — one-shot, so crash rebuilds and
     *  interstitial recoveries return to home rather than replaying a stale navigate), else the
     *  configured home dashboard. */
    private fun currentUrl(config: Config): String =
        ExternalAuthProtocol.dashboardUrl(config.haUrl, BuiltinDashboard.consumeNavPath() ?: config.homeDashboard)

    // Publish foreground state so SystemController.dashboardState can drive the watchdog + kiosk
    // return-loop from an in-process signal instead of a root pidof/dumpsys probe.
    //
    // The precise signal is TOP-RESUMED, not focus or resume/pause. On some OEM launchers (verified on
    // rk3576/Android-14 quickstep) opening Overview/Recents leaves the dashboard both window-FOCUSED and
    // RESUMED behind a translucent overlay — so onPause and onWindowFocusChanged never fire — yet the
    // dashboard is no longer the top activity the user is on. onTopResumedActivityChanged (API 29+) is
    // the lifecycle form of dumpsys `topResumedActivity` and flips exactly on that transition.
    // onResume/onPause are the API<29 baseline (older panels lack the callback; their launchers also
    // predate translucent Overview, so resume/pause suffices there).
    override fun onResume() { super.onResume(); BuiltinDashboard.setActivityForeground(activityOwner, true); applyFullscreen(); applyOverscroll(); applyZoom() }

    /** Android's overscroll stretch (12+) / edge-glow (older) when a drag runs past the top or bottom
     *  of the page. Off by default on a wall panel; the hidden `dashboard_overscroll` API setting turns
     *  it back on. Re-read + applied on resume so a live config change lands on the foreground relaunch. */
    private fun applyOverscroll() {
        web?.overScrollMode =
            if (Config(this).dashboardOverscroll) View.OVER_SCROLL_ALWAYS else View.OVER_SCROLL_NEVER
    }

    /** Page zoom (%). Re-read + applied on resume so a live `dashboard_zoom` change lands; the POST
     *  path also reloads the page, since a fresh load is where the initial scale reliably takes effect. */
    private fun applyZoom() {
        web?.setInitialScale((resources.displayMetrics.density * Config(this).dashboardZoom).toInt())
    }

    /**
     * Edge-to-edge kiosk (issue #25): hide the Android status + navigation bars while the dashboard is
     * up. BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE keeps the admin escape — a swipe from a screen edge
     * reveals the bars briefly — so this can never strand anyone. Now that the dashboard is our own
     * activity this is plain app-level immersive (no root, unlike suppressing bars for a foreign
     * renderer). Re-asserted on resume and on regaining window focus, because the system restores bars
     * after transient reveals and some dialogs. Toggle: Configure → Dashboard → "Fullscreen dashboard".
     */
    private fun applyFullscreen() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (Config(this).dashboardFullscreen) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullscreen()
    }
    override fun onPause() { BuiltinDashboard.setActivityForeground(activityOwner, false); super.onPause() }
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        BuiltinDashboard.setActivityForeground(activityOwner, isTopResumedActivity)
    }

    /**
     * The dashboard's DEFAULT colour scheme, by Android tier (a theme picked inside HA always wins —
     * WEB_THEME_DARKENING_ONLY never darkens a page that pinned a light theme):
     *  - 13+: nothing to do — the WebView derives prefers-color-scheme from the app theme, which is
     *    FOLLOW_SYSTEM, so the OS setting drives it live (uiMode arrives via onConfigurationChanged).
     *  - 10..12: a system dark/light setting exists but old WebViews don't follow the app theme — mirror
     *    the CURRENT system uiMode into the force-dark flag (re-applied on every uiMode change below).
     *  - 9-: no system setting exists — the panel's `dark_mode` config (Display card) decides.
     */
    private fun applyForceDark(w: WebView) = runCatching {
        if (android.os.Build.VERSION.SDK_INT >= 33) return@runCatching
        val dark = if (android.os.Build.VERSION.SDK_INT >= 29) {
            (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        } else {
            Config(this).darkMode
        }
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.FORCE_DARK)) {
            @Suppress("DEPRECATION")
            androidx.webkit.WebSettingsCompat.setForceDark(
                w.settings,
                if (dark) androidx.webkit.WebSettingsCompat.FORCE_DARK_ON else androidx.webkit.WebSettingsCompat.FORCE_DARK_OFF,
            )
        }
        if (dark && androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.FORCE_DARK_STRATEGY)) {
            @Suppress("DEPRECATION")
            androidx.webkit.WebSettingsCompat.setForceDarkStrategy(
                w.settings, androidx.webkit.WebSettingsCompat.DARK_STRATEGY_WEB_THEME_DARKENING_ONLY,
            )
        }
    }

    /** configChanges declares uiMode, so a system dark/light flip lands here instead of recreating the
     *  activity — re-mirror it into the 10..12 force-dark flag (the frontend re-themes live off the
     *  resulting prefers-color-scheme change; no reload). No-op below 10 (config-driven) and on 13+. */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (android.os.Build.VERSION.SDK_INT in 29..32) web?.let { applyForceDark(it) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildAndLoad(config: Config) {
        if (destroyed || !BuiltinDashboard.ownsActivity(activityOwner)) return
        val generation = rendererGate.open()
        rendererGeneration = generation
        // The page carries a live HA session, so only expose the WebView's DevTools socket when network
        // adb is deliberately on (a debug posture) — not by default. The CDP relay (also off by default)
        // is the LAN publisher on top of this.
        WebView.setWebContentsDebuggingEnabled(config.networkAdbEnabled)
        // A rebuild (renderer crash) discards the old root that held any fullscreen (onShowCustomView)
        // view. Clear the stale references so the crash didn't leave customView non-null — otherwise every
        // future onShowCustomView would be rejected and fullscreen video would be permanently broken.
        customView = null
        customViewCallback = null
        val w = try {
            createWebView(config, generation)
        } catch (e: Throwable) {
            // A missing / updating / broken system WebView (exactly the population WebView-auto-heal
            // targets) throws here. As a HOME activity we must not crash — Android would relaunch us
            // into a crash-loop — so fall back to the admin launcher. This consumes the rebuild budget
            // too: the kiosk/watchdog loops relaunch us, createWebView throws again, and without the
            // budget that cycle would churn forever; with it the renderer latches after a burst and
            // retries once per cooldown (plenty for a WebView update to finish installing).
            Log.e(TAG, "system WebView unavailable — falling back to admin launcher", e)
            BuiltinDashboard.consumeRebuildBudget(SystemClock.elapsedRealtime())
            fallbackToLauncher()
            return
        }
        web = w
        // Wrap in a pull-to-refresh layout: a drag that starts at the very top edge of the screen and
        // pulls down does a light reload of the current page (no app relaunch). The gesture is gated on
        // its ORIGIN (see EdgePullRefreshLayout) — a downward drag that begins inside the dashboard
        // content never triggers it, so scrolling views and adjusting cards behave normally (#29).
        // The spinner is cleared when the page finishes (or errors) — see the WebViewClient.
        val refresh = EdgePullRefreshLayout(this).apply {
            addView(w, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnRefreshListener { lightRefresh() }
        }
        swipe = refresh
        // A dark root behind the (transparent) WebView so a reload never flashes white — very visible on
        // a wall panel at night. Also hosts the fullscreen-video view from onShowCustomView. The root is a
        // BottomSwipeFrame so a bottom-edge swipe-up reveals the soft navbar in-process (see the class) —
        // replacing the service's overlay strip that made the dashboard's bottom band tap-dead.
        val container = BottomSwipeFrame(
            this,
            enabled = { BuiltinDashboard.navbarSwipeEnabled },
            onSwipeUp = { BuiltinDashboard.requestNavbarReveal() },
        ).apply {
            setBackgroundColor(BG_DARK)
            addView(refresh, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        root = container
        setContentView(container)
        w.loadUrl(currentUrl(config))
        onLoadStarted()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(config: Config, generation: Long): WebView = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // Guard against a WebView bug that collapses fonts to unreadably small on some engines — the
        // HA Companion carries the same workaround (their PR #3353). 5px floors it without affecting
        // normal dashboard type.
        settings.minimumFontSize = 5
        // Camera cards / live feeds must start without a tap — the default (require user gesture) leaves
        // every stream paused on a touchless panel. (Not media-file playback, which stays out of scope.)
        settings.mediaPlaybackRequiresUserGesture = false
        setBackgroundColor(BG_DARK) // no white flash before first paint
        // Overscroll stretch/glow off by default (see applyOverscroll) — set before first layout.
        overScrollMode = if (config.dashboardOverscroll) View.OVER_SCROLL_ALWAYS else View.OVER_SCROLL_NEVER
        // Page zoom to match the HA Companion's default sizing (it scales by device density); pinch
        // stays off (no builtInZoomControls) — the zoom is a deliberate per-panel value (see applyZoom).
        setInitialScale((resources.displayMetrics.density * config.dashboardZoom).toInt())
        applyForceDark(this)
        // Seed the dashboard's DEFAULT colour scheme through HA's own per-device theme store, before
        // any page script runs — only when the panel has no system dark mode (the Display-card toggle
        // population) and only if the user hasn't picked a theme in HA (see selectedThemeJs).
        if (android.os.Build.VERSION.SDK_INT < 29) runCatching {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                    this, ExternalAuthProtocol.selectedThemeJs(config.darkMode, onlyIfAbsent = true), setOf("*"),
                )
            }
        }
        // Force panel-appropriate HA frontend prefs on this WebView's FIRST run: hide the sidebar,
        // never suspend the websocket when idle, no haptics. HA's defaults are wrong for a wall panel
        // and don't carry over from the Companion, and most users don't know these settings exist —
        // so seed them once (self-gated by a localStorage sentinel), then leave them user-changeable.
        runCatching {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                    this, ExternalAuthProtocol.panelDefaultsJs(), setOf("*"),
                )
            }
        }
        // Intercept only the primary HA socket's outbound subscribe_entities command. The socket itself
        // remains Chromium-native, preserving its TLS, compression and external-app lifecycle signals.
        val filterLease = entityFilterLease
        if (filterLease != null && EntityFilterTelemetry.isActive(filterLease)) runCatching {
            androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                this,
                EntityFilterProtocol.documentStartScript(config.haUrl, config.dashboardEntityFilterIds),
                setOf("*"),
            )
        }.onFailure(::fallbackFromEntityFilterInterceptor)
        if (config.dashboardEntityLearningEnabled) runCatching {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                    this,
                    EntityLearningProtocol.documentStartScript(config.haUrl),
                    setOf("*"),
                )
            }
        }.onFailure { Log.w(TAG, "entity-learning access observer unavailable", it) }
        // The HA frontend relies on cookies (incl. third-party for some integrations).
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        addJavascriptInterface(ExternalAuthBridge(config, generation, filterLease), "externalApp")
        webChromeClient = dashboardChromeClient(generation)
        webViewClient = object : WebViewClient() {
            // A dead renderer process must not take the app down with it: rebuild the WebView in place
            // (else Android kills the whole process + the panel's HTTP/MQTT service), but within a budget
            // so a reliably-crashing page falls back to the admin launcher instead of spinning.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "renderer process gone (crash=${detail.didCrash()})")
                val owned = rendererCurrent(generation, view)
                // An OOM-killed renderer can leave a corrupted Chromium code-cache behind, which can
                // crash the REBUILT renderer too — clear it (best-effort; the view is defunct and may
                // throw) so the crash budget isn't burned on a persistent on-disk cause.
                runCatching { view.clearCache(true) }
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
                if (owned) {
                    rendererGate.invalidate()
                    web = null
                    main.removeCallbacks(watchdog)
                    main.removeCallbacks(darkSettle)
                    runCatching { customViewCallback?.onCustomViewHidden() }
                    customView = null
                    customViewCallback = null
                    runOnUiThread {
                        if (allowRebuild()) {
                            BuiltinDashboard.recordRendererReload(SystemClock.elapsedRealtime()) // involuntary crash rebuild
                            buildAndLoad(Config(this@DashboardActivity))
                        } else { Log.e(TAG, "renderer crash-looping — falling back to admin launcher"); fallbackToLauncher() }
                    }
                }
                return true
            }

            // Keep navigation on the dashboard host (the panel has no browser). Allow same-host
            // redirects across schemes (http↔https behind a proxy/HSTS); only a different HOST is
            // treated as an external link and blocked.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!rendererCurrent(generation, view)) return true
                return !dashboardNavigationAllowed(config.haUrl, request.url.toString())
            }

            // Stop the pull-to-refresh spinner once the (main-frame) load settles, success or error, so
            // it never spins forever on a hung reload.
            override fun onPageFinished(view: WebView, url: String) {
                if (!rendererCurrent(generation, view)) return
                swipe?.isRefreshing = false
                // Re-assert the page zoom AFTER load — HA's frontend ships its own <meta viewport
                // initial-scale=1>, which overrides a scale set before load, so a pre-load setInitialScale
                // silently reverts to default (dashboard looks compact). HACA does exactly this in its
                // own onPageFinished. Keeps our sizing matching the Companion app's.
                applyZoom()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (!rendererCurrent(generation, view) || !request.isForMainFrame) return
                swipe?.isRefreshing = false
                // Replace Android's un-styleable gray net::ERR_* page with the branded reconnecting
                // screen; the handshake watchdog (already armed by this load) keeps retrying behind it.
                val detail = "${error.description}".replace("<", "&lt;")
                runOnUiThread { showReconnecting(detail) }
            }

            // HTTP errors are consulted ONLY for the main frame: a sub-resource 401/404 (a HACS module,
            // a picture card) is that card's problem, not the page's — reacting to it (HACA's early
            // mistake) turns one broken card into a broken dashboard. A main-frame error just logs;
            // recovery is the handshake watchdog's job (no `connected` will arrive → reload w/ backoff).
            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse,
            ) {
                if (!rendererCurrent(generation, view) || !request.isForMainFrame) return
                swipe?.isRefreshing = false
                Log.w(TAG, "main-frame HTTP ${errorResponse.statusCode}")
            }
        }
    }

    /**
     * Without a WebChromeClient, three dashboard interactions fail SILENTLY: JS `confirm()` (the frontend
     * uses it for destructive actions — the UI hangs on an unanswered result), fullscreen video, and
     * getUserMedia permission requests. This provides the minimum: native dialogs for confirm/alert,
     * fullscreen show/hide, and a permission handler that grants only resources whose Android permission
     * we actually hold (none of camera/mic by default — the getUserMedia/intercom path is out of scope),
     * else denies explicitly so the card shows its own error instead of hanging.
     */
    private fun dashboardChromeClient(generation: Long) = object : WebChromeClient() {
        override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
            // Showing a dialog on a finishing/destroyed activity throws BadTokenException and takes the
            // whole process down. If we can't show it, cancel the JS call so the frontend isn't left hung.
            if (!rendererCurrent(generation, view) || isFinishing || isDestroyed) { result.cancel(); return true }
            AlertDialog.Builder(this@DashboardActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
            if (!rendererCurrent(generation, view) || isFinishing || isDestroyed) { result.confirm(); return true }
            AlertDialog.Builder(this@DashboardActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.confirm() }
                .show()
            return true
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (!rendererCurrent(generation)) { callback.onCustomViewHidden(); return }
            if (customView != null) { callback.onCustomViewHidden(); return }
            customView = view
            customViewCallback = callback
            root?.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        override fun onHideCustomView() {
            if (!rendererCurrent(generation)) return
            customView?.let { root?.removeView(it) }
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            if (!rendererCurrent(generation)) { request.deny(); return }
            val granted = request.resources.filter { res ->
                val perm = when (res) {
                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                    else -> null
                }
                perm != null && ContextCompat.checkSelfPermission(this@DashboardActivity, perm) == PackageManager.PERMISSION_GRANTED
            }
            if (granted.isNotEmpty()) request.grant(granted.toTypedArray()) else request.deny()
        }
    }

    /**
     * The frontend's external-auth JS bridge (V1 contract; the frontend feature-detects
     * `window.externalApp` and only uses V2 when `externalAppV2` also exists, so V1 alone is
     * complete). Methods run on the WebView's JS-bridge thread — every reply hops to the main
     * thread for `evaluateJavascript`. Reply construction is the pure [ExternalAuthProtocol]
     * so the contract is unit-testable without a WebView.
     */
    inner class ExternalAuthBridge(
        private val config: Config,
        private val generation: Long,
        private val filterLease: EntityFilterTelemetry.Lease?,
    ) {

        @JavascriptInterface
        fun getExternalAuth(payload: String) {
            if (!rendererCurrent(generation)) return
            // Resolve (and lazily refresh) the access token off the main thread — we're already on the
            // WebView's JS-bridge thread, so the blocking refresh HTTP is fine here. `force` (set by the
            // frontend after a 401) bypasses the cached token so a rejected token isn't re-handed.
            val r = DashboardAuth.forConfig(
                config,
                force = ExternalAuthProtocol.forceOf(payload),
                stillCurrent = { rendererCurrent(generation) },
            )
            // A terminal rejection feeds the auth latch; transient failures (HA down) don't — the
            // frontend just re-asks and recovers when HA does.
            if (r.rejected) runOnUiThread { onAuthRejected(generation) }
            evaluate(ExternalAuthProtocol.authReply(payload, r.session?.accessToken, r.session?.expiresInSec ?: 0L))
        }

        @JavascriptInterface
        fun revokeExternalAuth(payload: String) {
            if (rendererCurrent(generation)) evaluate(ExternalAuthProtocol.revokeReply(payload))
        }

        /** Called only by the document-start WebSocket wrapper after it rewrites subscribe_entities. */
        @JavascriptInterface
        fun entityFilterSubscriptionModified() {
            val lease = filterLease ?: return
            if (rendererCurrent(generation) && config.dashboardEntityFilterEnabled) {
                EntityFilterTelemetry.subscriptionModified(lease)
            }
        }

        /** Batched dependency evidence from the document-start `hass.states` observer. */
        @JavascriptInterface
        fun entityLearningAccesses(payload: String) {
            if (rendererCurrent(generation) && config.dashboardEntityLearningEnabled && payload.length <= 1_000_000) {
                EntityLearningRuntime.recordAccessBatch(payload)
            }
        }

        @JavascriptInterface
        fun entityLearningMetrics(payload: String) {
            if (rendererCurrent(generation) && config.dashboardEntityLearningEnabled && payload.length <= 1_000_000) {
                EntityLearningRuntime.recordMetricBatch(payload)
            }
        }

        @JavascriptInterface
        fun externalBus(message: String) {
            if (!rendererCurrent(generation)) return
            // Tap on the sidebar's "App Configuration" entry → open our :8888 Configure UI on the panel.
            if (ExternalAuthProtocol.isConfigScreenShow(message)) {
                runOnUiThread {
                    if (!rendererCurrent(generation)) return@runOnUiThread
                    runCatching {
                        // No NEW_TASK: launched from this Activity context, ConfigActivity stacks on the
                        // dashboard's task, so its back/close returns straight to the live dashboard.
                        startActivity(
                            Intent(this@DashboardActivity, ConfigActivity::class.java)
                                .putExtra("path", "/configure"),
                        )
                    }
                }
            }
            // Side-channel: a `connection-status` event drives the handshake watchdog (on the main thread).
            ExternalAuthProtocol.connectionEvent(message)?.let { ev -> runOnUiThread { onConnectionStatus(ev, generation) } }
            if (ExternalAuthProtocol.isThemeUpdate(message)) runOnUiThread { captureDashboardTheme(generation) }
            // Command replies (navigate etc.) confirm execution — log-only, but gold when debugging.
            ExternalAuthProtocol.resultOf(message)?.let { (id, ok) -> Log.d(TAG, "bus result id=$id success=$ok") }
            evaluate(ExternalAuthProtocol.busReply(message, BuildConfig.VERSION_NAME))
        }

        private fun evaluate(script: String?) {
            if (script == null) return
            runOnUiThread { if (rendererCurrent(generation)) web?.evaluateJavascript(script, null) }
        }
    }

    companion object {
        private const val TAG = "ha-paneld/dashboard"
        private const val INITIAL_HANDSHAKE_MS = 25_000L        // generous: a cold PX30 frontend can need 20s+
        private const val RELOAD_INTERVAL_MS = 6 * 60 * 60 * 1000L   // shed WebView memory at a screen-off, ~6h
        private const val RELOAD_HARD_MS = 26 * 60 * 60 * 1000L      // force a visible reload if never idle-dark
        private const val PERIODIC_CHECK_MS = 30 * 60 * 1000L        // how often the memory-ceiling check runs
        private const val DARK_SETTLE_MS = 30_000L              // let a screen-off reload load before freezing
        private const val BG_DARK = 0xFF121212.toInt()
        private const val LIGHT_REFRESH_SPINNER_MS = 800L       // bus navigate is instant; brief spinner ack
        private const val HARD_REFRESH_WINDOW_MS = 6_000L       // second pull inside this = full hard reload
        private const val IDLE_CHECK_MS = 60_000L               // idle return-to-home tick
        private const val AUTH_INVALID_LATCH = 3                // consecutive auth-invalid events → latch
        private const val REFRESH_REJECT_LATCH = 2              // consecutive definitive refresh rejections → latch
        private const val DEFAULT_NETWORK_WAIT_MS = 60_000L     // calm first-boot progress until learned
    }
}

/**
 * Pull-to-refresh that only arms for a drag beginning at the very top edge of the screen — a pull in
 * from the bezel. SwipeRefreshLayout's stock gate asks whether the child can scroll up, but the HA
 * frontend scrolls *inside* the page, so the WebView always reports "at the top" and every downward
 * drag on the dashboard (scrolling a view, dragging a slider card) would start the gesture (#29).
 * Gating on the gesture's origin makes scroll position irrelevant: a drag whose initial touch lands
 * below the edge band is never intercepted, so the content underneath sees it untouched.
 */
private class EdgePullRefreshLayout(context: Context) : SwipeRefreshLayout(context) {
    private val edgePx = (EDGE_BAND_DP * resources.displayMetrics.density).toInt()
    private var armed = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) armed = ev.y <= edgePx
        return armed && super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean = armed && super.onTouchEvent(ev)

    private companion object {
        // Wide enough that a quick bezel swipe's first sampled touch still lands inside it; narrow
        // enough that a drag starting on dashboard content (HA's header alone is ~56dp) never does.
        const val EDGE_BAND_DP = 24
    }
}

/**
 * Root container that detects the soft-navbar swipe-reveal gesture IN-PROCESS while the built-in
 * renderer is foreground — replacing [io.github.maxlyth.hapaneld.control.NavbarController]'s bottom
 * overlay strip, which consumed every touch in its 48dp band and made the dashboard's bottom edge
 * tap-dead (root `input tap` re-injection) or tap-dropping (no root). Qualification is by the gesture's
 * ORIGIN in a bottom edge band, never by content-scroll state — the same principle as
 * [EdgePullRefreshLayout] at the top edge. A gesture that never crosses the upward-travel threshold is
 * NEVER intercepted, so taps and scrolls reach the WebView untouched with zero latency.
 */
private class BottomSwipeFrame(
    context: Context,
    private val enabled: () -> Boolean,
    private val onSwipeUp: () -> Unit,
) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val detector = BottomSwipeDetector(
        bandPx = BottomSwipeDetector.BAND_DP * density,
        minTravelPx = BottomSwipeDetector.MIN_TRAVEL_DP * density,
    )
    private var stolen = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { stolen = false; detector.onDown(ev.x, ev.y, height, enabled()) }
            MotionEvent.ACTION_POINTER_DOWN -> detector.abort() // a pinch on a bottom-band card is not a reveal
            MotionEvent.ACTION_MOVE -> if (detector.onMove(ev.x, ev.y)) {
                stolen = true
                onSwipeUp()
                return true // steal: the framework CANCELs the child chain (page sees a normal touchcancel)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> detector.abort()
        }
        return false
    }

    /** After a steal, consume the remainder of the gesture so the stream stays with us. */
    override fun onTouchEvent(ev: MotionEvent): Boolean = stolen

    /** WebView content (slider/map cards handling their own drag) calls this to stop us intercepting;
     *  honour it for everything EXCEPT an edge-origin gesture we're still tracking, so page content can't
     *  defeat the reveal (the DrawerLayout edge-swipe precedent). */
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (disallowIntercept && detector.tracking) return
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }
}

/**
 * Pure reply-builders for the HA frontend's external-auth / external-bus JS contract. Null = no
 * reply (never evaluate anything for a payload we don't recognise — callback names in particular
 * are validated against the frontend's fixed constants, so an attacker-supplied function name is
 * dropped rather than executed).
 */
object ExternalAuthProtocol {

    /** Build the dashboard URL: `<haUrl>/<path>?external_auth=1`. [path] is an optional local dashboard
     *  path (e.g. `my-panel/dash` or `/lovelace/0`); leading/trailing slashes are normalised, blank =
     *  the HA root. `external_auth=1` tells the frontend to authenticate via our JS bridge. */
    fun dashboardUrl(haUrl: String, path: String): String {
        val base = haUrl.trim().trimEnd('/')
        val p = path.trim().trim('/')
        if (p.isEmpty()) return "$base/?external_auth=1"
        // If the path already carries a query string, join with & so external_auth isn't swallowed.
        val sep = if (p.contains('?')) "&" else "?"
        return "$base/$p${sep}external_auth=1"
    }

    /**
     * JS that writes the HA frontend's own per-device theme store (`selectedTheme` in localStorage —
     * exactly what the profile page's Auto/Light/Dark radio writes). This is the lever that actually
     * re-renders HA: the WebView force-dark route is a no-op on HA because WEB_THEME_DARKENING_ONLY
     * only acts on pages whose `color-scheme` meta declares dark support, and HA only declares it
     * AFTER it has already gone dark (verified live on an Android 8.1 panel, 2026-07-10 — the media
     * query never flips, so force-dark alone never darkened HA at all). [onlyIfAbsent] = seed a
     * DEFAULT without stomping a theme the user picked in HA; false = a deliberate dark-mode toggle,
     * which overrides like the radio does.
     */
    fun selectedThemeJs(dark: Boolean, onlyIfAbsent: Boolean): String {
        val write = """localStorage.setItem('selectedTheme', JSON.stringify({dark:$dark}))"""
        return if (onlyIfAbsent) "try{if(!localStorage.getItem('selectedTheme')){$write}}catch(e){}"
        else "try{$write}catch(e){}"
    }

    /** The frontend's `force` flag (set after a 401 to demand a fresh token), false if absent/malformed. */
    fun forceOf(payload: String): Boolean =
        runCatching { JSONObject(payload).optBoolean("force", false) }.getOrDefault(false)


    /** `getExternalAuth` reply: `externalAuthSetToken(true, {access_token, expires_in})`, or a
     *  `(false)` failure when no token is available (unconfigured, or a refresh that failed closed).
     *  [expiresInSec] is the token's remaining life the frontend uses to know when to ask again. */
    fun authReply(payload: String, accessToken: String?, expiresInSec: Long): String? {
        if (callbackOf(payload) != "externalAuthSetToken") return null
        if (accessToken.isNullOrBlank()) return "externalAuthSetToken(false)"
        val auth = JSONObject().put("access_token", accessToken).put("expires_in", expiresInSec)
        return "externalAuthSetToken(true, $auth)"
    }

    /** `revokeExternalAuth` ack. The panel doesn't own the long-lived token (the admin minted it),
     *  so there's no server-side revoke to perform — acknowledge so the frontend can finish logout. */
    fun revokeReply(payload: String): String? =
        if (callbackOf(payload) == "externalAuthRevokeToken") "externalAuthRevokeToken(true)" else null

    /** External-bus handler. `config/get` is the one message the frontend BLOCKS on during startup;
     *  everything else (connection-status, theme-update, haptic, …) is fire-and-forget and must be
     *  swallowed, not errored, for forward compatibility. Every capability is declared off so the
     *  frontend never offers phone features (tags, barcode, Assist, downloads) on a panel. */
    fun busReply(message: String, appVersion: String): String? {
        val msg = runCatching { JSONObject(message) }.getOrNull() ?: return null
        if (msg.optString("type") != "config/get") return null
        val result = JSONObject()
            // true → HA renders an "App Configuration" entry in the sidebar; a tap sends the incoming
            // `config_screen/show` bus message, which we route to ConfigActivity (the :8888 Configure UI).
            .put("hasSettingsScreen", true)
            .put("canWriteTag", false)
            .put("hasExoPlayer", false)
            .put("canCommissionMatter", false)
            .put("canImportThreadCredentials", false)
            .put("hasAssist", false)
            .put("hasBarCodeScanner", 0)
            .put("canSetupImprov", false)
            .put("downloadFileSupported", false)
            .put("hasEntityAddTo", false)
            .put("hasAssistSettings", false)
            .put("appVersion", appVersion)
        val reply = JSONObject()
            .put("id", msg.optInt("id"))
            .put("type", "result")
            .put("success", true)
            .put("result", result)
        return "externalBus($reply);"
    }

    /** True if [message] is the frontend's `config_screen/show` command — sent when the user taps the
     *  "App Configuration" sidebar entry (which we enable via `hasSettingsScreen`). The app opens its
     *  own config UI in response. */
    fun isConfigScreenShow(message: String): Boolean =
        runCatching { JSONObject(message).optString("type") == "config_screen/show" }.getOrDefault(false)

    /** Document-start script that forces panel-appropriate HA frontend prefs on this WebView's FIRST
     *  run, then never again: hide the sidebar, keep the websocket alive when idle, no haptics. Values
     *  are `JSON.stringify`'d to match HA's `ha-pref-storage` localStorage format. Self-gated by a
     *  sentinel key so it applies once, survives reloads/restarts, never clobbers a later user change,
     *  and re-applies after a renderer-storage wipe (a fresh first run). */
    fun panelDefaultsJs(): String =
        """(function(){try{
            if(localStorage.getItem('__hapaneld_panel_defaults'))return;
            localStorage.setItem('dockedSidebar',JSON.stringify('always_hidden'));
            localStorage.setItem('suspendWhenHidden',JSON.stringify(false));
            localStorage.setItem('vibrate',JSON.stringify(false));
            localStorage.setItem('__hapaneld_panel_defaults','1');
        }catch(e){}})();"""

    /** If [message] is a `connection-status` external-bus event, its event name ("connected",
     *  "disconnected", "auth-invalid"); null for any other message. The frontend posts this so the app
     *  knows when the websocket is up/down — the health signal `onPageFinished` can't give. Tolerates the
     *  event under `payload.event` or at the top level. */
    fun connectionEvent(message: String): String? {
        val msg = runCatching { JSONObject(message) }.getOrNull() ?: return null
        if (msg.optString("type") != "connection-status") return null
        val ev = msg.optJSONObject("payload")?.optString("event").orEmpty().ifBlank { msg.optString("event") }
        return ev.takeIf { it in setOf("connected", "disconnected", "auth-invalid") }
    }

    fun isThemeUpdate(message: String): Boolean =
        runCatching { JSONObject(message).optString("type") == "theme-update" }.getOrDefault(false)

    /** App→frontend `navigate` command: swap the frontend's view to [path] *inside* the running app —
     *  no page reload, the JS bundle and websocket stay live (instant, vs several seconds of blank for
     *  `WebView.reload()`). `options.replace` keeps kiosk history flat. HA ≥ 2025.6. */
    fun navigateCommand(id: Int, path: String): String {
        val p = "/" + path.trim().trim('/')
        val msg = JSONObject()
            .put("id", id)
            .put("type", "command")
            .put("command", "navigate")
            .put("payload", JSONObject().put("path", p).put("options", JSONObject().put("replace", true)))
        return "externalBus($msg);"
    }

    /** If [message] is the frontend's `result` reply to an app-sent command, its (id, success);
     *  null otherwise. Used to confirm a bus command was actually executed. */
    fun resultOf(message: String): Pair<Int, Boolean>? {
        val msg = runCatching { JSONObject(message) }.getOrNull() ?: return null
        if (msg.optString("type") != "result") return null
        return msg.optInt("id", -1).takeIf { it >= 0 }?.let { it to msg.optBoolean("success", false) }
    }

    private fun callbackOf(payload: String): String =
        runCatching { JSONObject(payload).optString("callback") }.getOrDefault("")
}
