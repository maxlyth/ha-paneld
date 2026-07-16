package io.github.maxlyth.hapaneld

import android.content.res.Configuration
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import io.github.maxlyth.hapaneld.shizuku.ShizukuSetupDialog
import io.github.maxlyth.hapaneld.util.LocalAdminEndpoint
import io.github.maxlyth.hapaneld.util.LocalAdminReadiness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private lateinit var readinessPanel: LinearLayout
    private lateinit var readinessMessage: TextView
    private lateinit var readinessProgress: ProgressBar
    private lateinit var retryButton: Button
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val readiness = LocalAdminReadiness(attempts = 10, retryDelayMs = 250L)
    private var readinessJob: Job? = null
    private var pageUrl: String = ""
    private var healthUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        web = WebView(this).apply {
            visibility = View.GONE
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowContentAccess = false
            settings.allowFileAccess = false
            webViewClient = WebViewClient() // keep links + the config-form POST inside this WebView
        }
        readinessMessage = TextView(this).apply {
            setText(R.string.config_service_starting)
            gravity = Gravity.CENTER
        }
        readinessProgress = ProgressBar(this)
        retryButton = Button(this).apply {
            setText(R.string.retry)
            isAllCaps = false
            isEnabled = false
            setOnClickListener { waitForAdminServer(restartService = true) }
        }
        readinessPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            addView(readinessProgress)
            addView(
                readinessMessage,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 20
                },
            )
            addView(
                retryButton,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 20
                },
            )
        }
        // A top bar with a back arrow → return to the dashboard (finish this activity). Without it there
        // is no obvious way off the config page on a kiosk panel with no visible system nav.
        val bar = Toolbar(this).apply {
            title = getString(applicationInfo.labelRes).ifBlank { "ha-paneld" }
            subtitle = "Settings"
            navigationIcon = androidx.appcompat.content.res.AppCompatResources.getDrawable(
                this@ConfigActivity, R.drawable.ic_toolbar_back,
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
            addView(readinessPanel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        // Optional path (e.g. the sidebar "App Configuration" tap opens /configure); default = the info page.
        val path = intent?.getStringExtra("path")?.takeIf { it.startsWith("/") } ?: "/"
        // Pin the web UI to the panel's own light/dark (the app applies DayNight at process start, so the
        // activity's uiMode reflects the panel's dark_mode/system setting). The :8888 UI honours ?theme=.
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val sep = if (path.contains('?')) "&" else "?"
        val port = Config(this).httpPort
        pageUrl = LocalAdminEndpoint.loopbackUrl(port, "$path${sep}theme=${if (dark) "dark" else "light"}")
        healthUrl = LocalAdminEndpoint.loopbackUrl(port, "/health")
        // ConfigActivity can be entered from the launcher or dashboard during process recovery. Ensure the
        // service is requested, then wait for its actual liveness endpoint instead of racing WebView load.
        PaneldService.start(this)
        waitForAdminServer()
    }

    private fun waitForAdminServer(restartService: Boolean = false) {
        readinessJob?.cancel()
        web.visibility = View.GONE
        readinessPanel.visibility = View.VISIBLE
        readinessProgress.visibility = View.VISIBLE
        readinessMessage.setText(R.string.config_service_starting)
        retryButton.isEnabled = false
        retryButton.visibility = View.GONE
        if (restartService) stopService(Intent(this, PaneldService::class.java))
        readinessJob = activityScope.launch {
            if (restartService) {
                // stopService() teardown is asynchronous. Give the old instance a bounded head start,
                // then request a fresh service process owner instead of re-probing a terminal FAILED one.
                delay(SERVICE_RESTART_DELAY_MS)
                PaneldService.start(this@ConfigActivity)
            }
            val ready = withContext(Dispatchers.IO) {
                readiness.await(probe = { LocalAdminReadiness.healthProbe(healthUrl, timeoutMs = 350) })
            }
            if (!isActive) return@launch
            if (ready) {
                readinessPanel.visibility = View.GONE
                web.visibility = View.VISIBLE
                web.loadUrl(pageUrl)
            } else {
                readinessProgress.visibility = View.GONE
                readinessMessage.setText(R.string.config_service_not_ready)
                retryButton.visibility = View.VISIBLE
                retryButton.isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        readinessJob?.cancel()
        activityScope.cancel()
        web.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    private companion object {
        const val SERVICE_RESTART_DELAY_MS = 500L
    }
}
