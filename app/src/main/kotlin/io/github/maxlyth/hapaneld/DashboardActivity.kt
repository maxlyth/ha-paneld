package io.github.maxlyth.hapaneld

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        val config = Config(this)
        if (config.haUrl.isBlank()) {
            Log.w(TAG, "no ha_url configured — nothing to render")
            finish()
            return
        }
        buildAndLoad(config)
    }

    /** Reload on re-launch (singleTask): a second start intent is the "reload the dashboard" signal. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        web?.reload()
    }

    // Publish foreground state so SystemController.dashboardState can drive the watchdog + kiosk
    // return-loop from a lifecycle flag (in-process) instead of a root pidof/dumpsys probe.
    override fun onResume() { super.onResume(); BuiltinDashboard.foreground = true }
    override fun onPause() { BuiltinDashboard.foreground = false; super.onPause() }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildAndLoad(config: Config) {
        // Own WebView => we can expose its DevTools socket; the CDP relay (off by default) is what
        // actually publishes it to the LAN, so this alone only enables local adb inspection.
        WebView.setWebContentsDebuggingEnabled(true)
        val w = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // The HA frontend relies on cookies (incl. third-party for some integrations).
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(ExternalAuthBridge(config), "externalApp")
            webViewClient = object : WebViewClient() {
                // A dead renderer process must not take the app down with it: swallow the loss and
                // rebuild the WebView in place, else Android kills the whole process (and with it
                // the panel's HTTP/MQTT service).
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    Log.w(TAG, "renderer process gone (crash=${detail.didCrash()}) — rebuilding")
                    (view.parent as? android.view.ViewGroup)?.removeView(view)
                    view.destroy()
                    if (web === view) {
                        web = null
                        runOnUiThread { buildAndLoad(Config(this@DashboardActivity)) }
                    }
                    return true
                }

                // Keep navigation on the dashboard: the panel has no browser, so external links
                // would either silently do nothing or wedge the kiosk on a foreign page.
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val target = request.url
                    val allowed = android.net.Uri.parse(config.haUrl)
                    return !(target.host == allowed.host && target.scheme == allowed.scheme)
                }
            }
        }
        web = w
        setContentView(w)
        w.loadUrl("${config.haUrl}/?external_auth=1")
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
        fun getExternalAuth(payload: String) = evaluate(ExternalAuthProtocol.authReply(payload, config.haToken))

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
    }
}

/**
 * Pure reply-builders for the HA frontend's external-auth / external-bus JS contract. Null = no
 * reply (never evaluate anything for a payload we don't recognise — callback names in particular
 * are validated against the frontend's fixed constants, so an attacker-supplied function name is
 * dropped rather than executed).
 */
object ExternalAuthProtocol {

    /** `getExternalAuth` reply: `externalAuthSetToken(true, {access_token, expires_in})`, or a
     *  `(false)` failure when no token is configured. A long-lived token advertises a year of
     *  validity — the frontend only tracks expiry to know when to ask again. */
    fun authReply(payload: String, token: String): String? {
        if (callbackOf(payload) != "externalAuthSetToken") return null
        if (token.isBlank()) return "externalAuthSetToken(false)"
        val auth = JSONObject().put("access_token", token).put("expires_in", 31_536_000L)
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
