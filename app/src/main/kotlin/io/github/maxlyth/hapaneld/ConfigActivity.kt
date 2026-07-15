package io.github.maxlyth.hapaneld

import android.content.res.Configuration
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import io.github.maxlyth.hapaneld.shizuku.ShizukuSetupDialog

/**
 * In-app config screen: a WebView onto the local config page (127.0.0.1:8888). Panels are usually
 * kiosks with no browser installed, so an `ACTION_VIEW http://…` intent finds no handler and silently
 * does nothing — this guarantees the config page opens on the panel itself. Cleartext to localhost is
 * permitted by `usesCleartextTraffic` in the manifest.
 *
 * Opened from the built-in dashboard's "App Configuration" sidebar entry, so it carries a top bar with
 * a back control that returns to the dashboard, and it pins the web UI to the panel's own light/dark.
 */
class ConfigActivity : AppCompatActivity() {

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowContentAccess = false
            settings.allowFileAccess = false
            webViewClient = WebViewClient() // keep links + the config-form POST inside this WebView
        }
        // A top bar with a back arrow → return to the dashboard (finish this activity). Without it there
        // is no obvious way off the config page on a kiosk panel with no visible system nav.
        val bar = Toolbar(this).apply {
            title = getString(applicationInfo.labelRes).ifBlank { "ha-paneld" }
            subtitle = "Settings"
            navigationIcon = androidx.appcompat.content.res.AppCompatResources.getDrawable(
                this@ConfigActivity, androidx.appcompat.R.drawable.abc_ic_ab_back_material,
            )
            navigationContentDescription = "Back to dashboard"
            setNavigationOnClickListener { finish() }
            menu.add("Enhanced access").apply {
                setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER)
                setOnMenuItemClickListener {
                    ShizukuSetupDialog.show(this@ConfigActivity)
                    true
                }
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        // Optional path (e.g. the sidebar "App Configuration" tap opens /configure); default = the info page.
        val path = intent?.getStringExtra("path")?.takeIf { it.startsWith("/") } ?: "/"
        // Pin the web UI to the panel's own light/dark (the app applies DayNight at process start, so the
        // activity's uiMode reflects the panel's dark_mode/system setting). The :8888 UI honours ?theme=.
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val sep = if (path.contains('?')) "&" else "?"
        web.loadUrl("http://127.0.0.1:${Config.DEFAULT_PORT}$path${sep}theme=${if (dark) "dark" else "light"}")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
