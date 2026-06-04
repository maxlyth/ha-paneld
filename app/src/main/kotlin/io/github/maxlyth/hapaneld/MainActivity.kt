package io.github.maxlyth.hapaneld

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.maxlyth.hapaneld.util.localIpv4

/**
 * Launcher Activity. Requests the notification permission (Android 13+) and starts [PaneldService],
 * then shows a small standing screen — app icon, the full config URL, and buttons to open the config
 * page or the dashboard. Previously it `finish()`ed immediately after starting the service, which on
 * a panel with no real home app drops back to the launcher and looks like a crash. The agent runs
 * headless as a foreground service regardless of this Activity.
 */
class MainActivity : AppCompatActivity() {

    private val requestNotif =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        ) { PaneldService.start(this) }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private val url: String get() = "http://${localIpv4() ?: "127.0.0.1"}:${Config.DEFAULT_PORT}/"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(buildUi())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            PaneldService.start(this)
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(32), dp(36), dp(32), dp(36))
        }
        // The transparent foreground (device + blue mark), NOT the adaptive mipmap — the mipmap paints
        // its white background square (uneven mask padding) which clashes with the dark screen.
        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_launcher_foreground)
            layoutParams = LinearLayout.LayoutParams(dp(116), dp(116)).apply { bottomMargin = dp(8) }
        })
        root.addView(text("ha-paneld", 22f, "#FFFFFF", bold = true))
        root.addView(text("v${BuildConfig.VERSION_NAME} · running in the background", 13f, "#8a8f99", padBottom = 18))
        // Friendly explanation of what the app is.
        root.addView(text(
            "This device is a Home Assistant wall panel. ha-paneld runs in the background so Home " +
                "Assistant can control the screen, LED, buttons and speaker and read its sensors — all " +
                "over your local network. The dashboard itself runs in the Home Assistant app.",
            14f, "#c8ccd2", padBottom = 22, maxWidth = 380,
        ))
        // The full URL — tappable here, and readable so it can be typed on another device.
        root.addView(TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(Color.parseColor("#4a9eff"))
            text = url
            setOnClickListener { openConfig() }
        })
        root.addView(text("Open this address in a browser to configure the panel", 12f, "#8a8f99", padTop = 4, padBottom = 24))
        root.addView(button("Open configuration") { openConfig() })
        // Only offer the HA app button when the Home Assistant Companion app is actually installed.
        companionPackage()?.let { root.addView(button("Open Home Assistant app") { openDashboard() }) }

        return ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#111111"))
            isFillViewport = true
            addView(
                root,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { gravity = Gravity.CENTER },
            )
        }
    }

    private fun text(
        s: String, size: Float, color: String,
        bold: Boolean = false, padTop: Int = 0, padBottom: Int = 0, maxWidth: Int = 0,
    ): TextView = TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = size
        setTextColor(Color.parseColor(color))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (maxWidth > 0) this.maxWidth = dp(maxWidth)
        setPadding(0, dp(padTop), 0, dp(padBottom))
        text = s
    }

    private fun companionPackage(): String? =
        listOf("io.homeassistant.companion.android.minimal", "io.homeassistant.companion.android")
            .firstOrNull { packageManager.getLaunchIntentForPackage(it) != null }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor("#2557A7"))
        }
        setPadding(dp(24), dp(14), dp(24), dp(14))
        layoutParams = LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8); bottomMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    // Open the config page in-app — kiosk panels usually have no browser, so an ACTION_VIEW intent
    // would find no handler and do nothing.
    private fun openConfig() {
        runCatching { startActivity(Intent(this, ConfigActivity::class.java)) }
    }

    private fun openDashboard() {
        for (p in listOf("io.homeassistant.companion.android.minimal", "io.homeassistant.companion.android")) {
            packageManager.getLaunchIntentForPackage(p)?.let {
                startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return
            }
        }
    }
}
