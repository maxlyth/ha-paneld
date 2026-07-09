package io.github.maxlyth.hapaneld

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import io.github.maxlyth.hapaneld.control.BuiltinDashboard
import org.json.JSONObject

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
    // Renderer-crash rebuild budget (never-blank): a page that reliably crashes the WebView renderer
    // must not become a tight rebuild loop — after [MAX_REBUILDS] within [REBUILD_WINDOW_MS] we fall
    // back to the admin launcher instead of respawning.
    private var rebuilds = 0
    private var rebuildWindowStart = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        val config = Config(this)
        if (config.haUrl.isBlank()) {
            // We're a HOME activity: never finish to a blank home. With no URL to render, hand off to
            // the admin launcher (also a HOME activity) so the panel is never stranded.
            Log.w(TAG, "no ha_url configured — opening the admin launcher instead")
            fallbackToLauncher()
            return
        }
        buildAndLoad(config)
    }

    /** Hand off to ha-paneld's admin launcher and finish — the never-strand fallback for a missing
     *  WebView, an unconfigured URL, or a crash-looping renderer. */
    private fun fallbackToLauncher() {
        runCatching {
            startActivity(Intent(this, AdminLauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.e(TAG, "admin-launcher fallback failed: ${it.message}") }
        finish()
    }

    /** Allow a renderer rebuild if within budget; resets the counter each [REBUILD_WINDOW_MS] window. */
    private fun allowRebuild(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - rebuildWindowStart > REBUILD_WINDOW_MS) { rebuildWindowStart = now; rebuilds = 0 }
        return ++rebuilds <= MAX_REBUILDS
    }

    /** Re-launch (singleTask) is the reload / navigate signal: load the current target (an MQTT
     *  navigate sets [BuiltinDashboard.navPath]; otherwise the configured home dashboard), so a plain
     *  reload re-loads the same view and a navigate switches to the new path. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        web?.loadUrl(currentUrl(Config(this)))
    }

    /** The URL to show: the navigate path if one is pending, else the configured home dashboard. */
    private fun currentUrl(config: Config): String =
        ExternalAuthProtocol.dashboardUrl(config.haUrl, BuiltinDashboard.navPath ?: config.homeDashboard)

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
    override fun onResume() { super.onResume(); BuiltinDashboard.foreground = true }
    override fun onPause() { BuiltinDashboard.foreground = false; super.onPause() }
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        BuiltinDashboard.foreground = isTopResumedActivity
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildAndLoad(config: Config) {
        // The page carries a live HA session, so only expose the WebView's DevTools socket when network
        // adb is deliberately on (a debug posture) — not by default. The CDP relay (also off by default)
        // is the LAN publisher on top of this.
        WebView.setWebContentsDebuggingEnabled(config.networkAdbEnabled)
        val w = try {
            createWebView(config)
        } catch (e: Throwable) {
            // A missing / updating / broken system WebView (exactly the population WebView-auto-heal
            // targets) throws here. As a HOME activity we must not crash — Android would relaunch us
            // into a crash-loop — so fall back to the admin launcher.
            Log.e(TAG, "system WebView unavailable — falling back to admin launcher", e)
            fallbackToLauncher()
            return
        }
        web = w
        // Wrap in a pull-to-refresh layout: dragging down from the top of the dashboard does a light
        // reload of the current page (no app relaunch). It only triggers when the WebView is scrolled to
        // the top (SwipeRefreshLayout's default canChildScrollUp checks the child), so normal scrolling
        // is unaffected. The spinner is cleared when the page finishes (or errors) — see the WebViewClient.
        val refresh = SwipeRefreshLayout(this).apply {
            addView(w, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnRefreshListener { Log.i(TAG, "pull-to-refresh -> reload"); w.reload() }
        }
        swipe = refresh
        setContentView(refresh)
        w.loadUrl(currentUrl(config))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(config: Config): WebView = WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // The HA frontend relies on cookies (incl. third-party for some integrations).
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
        addJavascriptInterface(ExternalAuthBridge(config), "externalApp")
        webViewClient = object : WebViewClient() {
            // A dead renderer process must not take the app down with it: rebuild the WebView in place
            // (else Android kills the whole process + the panel's HTTP/MQTT service), but within a budget
            // so a reliably-crashing page falls back to the admin launcher instead of spinning.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.w(TAG, "renderer process gone (crash=${detail.didCrash()})")
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                view.destroy()
                if (web === view) {
                    web = null
                    runOnUiThread {
                        if (allowRebuild()) buildAndLoad(Config(this@DashboardActivity))
                        else { Log.e(TAG, "renderer crash-looping — falling back to admin launcher"); fallbackToLauncher() }
                    }
                }
                return true
            }

            // Keep navigation on the dashboard host (the panel has no browser). Allow same-host
            // redirects across schemes (http↔https behind a proxy/HSTS); only a different HOST is
            // treated as an external link and blocked.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return request.url.host != android.net.Uri.parse(config.haUrl).host
            }

            // Stop the pull-to-refresh spinner once the (main-frame) load settles, success or error, so
            // it never spins forever on a hung reload.
            override fun onPageFinished(view: WebView, url: String) { swipe?.isRefreshing = false }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) swipe?.isRefreshing = false
            }
        }
    }

    /**
     * The frontend's external-auth JS bridge (V1 contract; the frontend feature-detects
     * `window.externalApp` and only uses V2 when `externalAppV2` also exists, so V1 alone is
     * complete). Methods run on the WebView's JS-bridge thread — every reply hops to the main
     * thread for `evaluateJavascript`. Reply construction is the pure [ExternalAuthProtocol]
     * so the contract is unit-testable without a WebView.
     */
    inner class ExternalAuthBridge(private val config: Config) {

        @JavascriptInterface
        fun getExternalAuth(payload: String) {
            // Resolve (and lazily refresh) the access token off the main thread — we're already on the
            // WebView's JS-bridge thread, so the blocking refresh HTTP is fine here. `force` (set by the
            // frontend after a 401) bypasses the cached token so a revoked token isn't re-handed.
            val session = DashboardAuth.forConfig(config, force = ExternalAuthProtocol.forceOf(payload))
            evaluate(ExternalAuthProtocol.authReply(payload, session?.accessToken, session?.expiresInSec ?: 0L))
        }

        @JavascriptInterface
        fun revokeExternalAuth(payload: String) = evaluate(ExternalAuthProtocol.revokeReply(payload))

        @JavascriptInterface
        fun externalBus(message: String) = evaluate(ExternalAuthProtocol.busReply(message, BuildConfig.VERSION_NAME))

        private fun evaluate(script: String?) {
            if (script == null) return
            runOnUiThread { web?.evaluateJavascript(script, null) }
        }
    }

    companion object {
        private const val TAG = "ha-paneld/dashboard"
        private const val MAX_REBUILDS = 3
        private const val REBUILD_WINDOW_MS = 60_000L
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
     *  path (e.g. `bmp-panel/dash` or `/lovelace/0`); leading/trailing slashes are normalised, blank =
     *  the HA root. `external_auth=1` tells the frontend to authenticate via our JS bridge. */
    fun dashboardUrl(haUrl: String, path: String): String {
        val base = haUrl.trim().trimEnd('/')
        val p = path.trim().trim('/')
        if (p.isEmpty()) return "$base/?external_auth=1"
        // If the path already carries a query string, join with & so external_auth isn't swallowed.
        val sep = if (p.contains('?')) "&" else "?"
        return "$base/$p${sep}external_auth=1"
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
            .put("hasSettingsScreen", false)
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

    private fun callbackOf(payload: String): String =
        runCatching { JSONObject(payload).optString("callback") }.getOrDefault("")
}
