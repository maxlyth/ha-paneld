package io.github.maxlyth.hapaneld

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * In-app config screen: a WebView onto the local config page (127.0.0.1:8888). Panels are usually
 * kiosks with no browser installed, so an `ACTION_VIEW http://…` intent finds no handler and silently
 * does nothing — this guarantees the config page opens on the panel itself. Cleartext to localhost is
 * permitted by `usesCleartextTraffic` in the manifest.
 */
class ConfigActivity : AppCompatActivity() {

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient() // keep links + the config-form POST inside this WebView
        }
        setContentView(web)
        // Optional path (e.g. the sidebar "App Configuration" tap opens /configure); default = the info page.
        val path = intent?.getStringExtra("path")?.takeIf { it.startsWith("/") } ?: "/"
        web.loadUrl("http://127.0.0.1:${Config.DEFAULT_PORT}$path")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
