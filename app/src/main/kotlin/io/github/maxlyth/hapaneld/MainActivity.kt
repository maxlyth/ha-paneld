package io.github.maxlyth.hapaneld

import android.Manifest
import android.content.Intent
import io.github.maxlyth.hapaneld.control.SystemController
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
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

    private val config by lazy { Config(this) }
    private val handler = Handler(Looper.getMainLooper())
    private var autoReturn: Runnable? = null

    /** Colours for the standing screen, chosen to read on the panel's current light/dark setting. The
     *  wordmark itself switches via res/drawable(-night)-nodpi/wordmark.png; this covers everything else. */
    private data class Palette(
        val bg: String, val body: String, val subtle: String, val accent: String,
        val btnBg: String, val btnText: Int,
    )
    private val pal: Palette by lazy {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (dark) Palette("#111111", "#c8ccd2", "#8a8f99", "#4a9eff", "#2557a7", Color.WHITE)
        else Palette("#ffffff", "#2a2e34", "#5a6068", "#1669d6", "#2557a7", Color.WHITE)
    }

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
        maybeArmAutoReturn()
    }

    // After an app update the launcher lands on this UI; if the panel is configured (MQTT connected)
    // and the Companion is installed, bounce back to the dashboard so it doesn't linger. Cancelled by any
    // touch (so someone who opened it on purpose isn't yanked away). Gated on a recent app update, so a
    // deliberate open long afterwards just stays put.
    //
    // POLL rather than check once: after a restart (worst case a whole-fleet restart flooding the broker)
    // MQTT can take well over 8s to reconnect. The old one-shot at 8s silently skipped the redirect and
    // never retried, leaving the panel stranded on this UI. Now we re-check every [AUTO_RETURN_POLL_MS]
    // until connected or the [AUTO_RETURN_WINDOW_MS] window elapses, redirecting as soon as it's up.
    private fun maybeArmAutoReturn() {
        if (!config.autoReturnDashboard || dashboardIntent() == null) return
        val updated = runCatching { packageManager.getPackageInfo(packageName, 0).lastUpdateTime }.getOrDefault(0L)
        if (System.currentTimeMillis() - updated > 5 * 60 * 1000L) return // not a post-update launch
        val deadline = System.currentTimeMillis() + AUTO_RETURN_WINDOW_MS
        val r = object : Runnable {
            override fun run() {
                if (PanelStatus.mqttConnected) { autoReturn = null; openDashboard(); return }
                if (System.currentTimeMillis() >= deadline) { autoReturn = null; return } // give up (unconfigured)
                handler.postDelayed(this, AUTO_RETURN_POLL_MS)
            }
        }
        autoReturn = r
        handler.postDelayed(r, AUTO_RETURN_FIRST_MS) // let a genuine touch cancel + give MQTT a head start
    }

    private fun cancelAutoReturn() {
        autoReturn?.let { handler.removeCallbacks(it) }
        autoReturn = null
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.action == MotionEvent.ACTION_DOWN) cancelAutoReturn() // user is here on purpose
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        cancelAutoReturn()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val dm = resources.displayMetrics
        val hDp = (dm.heightPixels / dm.density).toInt()
        // Scale to the vertical budget so it fits WITHOUT scrolling on a 480x480 panel, yet the icon
        // and QR grow prominent on roomy displays. Tiers: tight (≈480²) / medium / large.
        val compact = hDp < 560
        val qrDp = when { hDp < 560 -> 132; hDp < 900 -> 192; else -> 240 }
        val pad = if (compact) dp(16) else dp(36)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // CENTER (not just CENTER_HORIZONTAL): with the ScrollView's fillViewport=true the column is
            // stretched to the viewport height, so this vertically centres the content in any spare
            // space; when content exceeds the screen it keeps its natural height and the ScrollView scrolls.
            gravity = Gravity.CENTER
            setPadding(dp(24), pad, dp(24), pad)
        }
        // Horizontal wordmark (glyph + "ha-paneld") carries the name, so there's no separate title text.
        // Size it by HEIGHT (width follows its aspect via adjustViewBounds), so it scales up on roomy
        // panels but can never overflow the screen width.
        val logoH = when { hDp < 560 -> 52; hDp < 900 -> 72; else -> 96 }
        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.wordmark)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(logoH))
                .apply { bottomMargin = dp(if (compact) 8 else 18) }
        })
        root.addView(text("v${BuildConfig.VERSION_NAME} · running in the background", 12f, pal.subtle, padBottom = if (compact) 8 else 18))
        // Description — shown on all panels (the shorter wordmark frees the vertical space on 480x480);
        // slightly smaller + tighter on compact so it still fits without scrolling.
        root.addView(text(
            "This device is a Home Assistant wall panel. ha-paneld runs in the background so Home " +
                "Assistant can control the screen, LED, buttons and speaker and read its sensors — all " +
                "over your local network. The dashboard is rendered either by ha-paneld's own built-in " +
                "renderer or by the Home Assistant app.",
            if (compact) 12.5f else 14f, pal.body, padBottom = if (compact) 10 else 22,
        ))
        // The full URL — tappable here, and readable so it can be typed on another device.
        root.addView(TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = if (compact) 15f else 18f
            setTextColor(Color.parseColor(pal.accent))
            text = url
            setOnClickListener { openConfig() }
            setPadding(0, 0, 0, dp(if (compact) 6 else 0))
        })
        if (!compact) root.addView(text("Open this address in a browser to configure the panel", 12f, pal.subtle, padTop = 4, padBottom = 16))
        // QR of the config URL — scan with a phone instead of typing it.
        qrBitmap(url, dp(qrDp))?.let { qr ->
            root.addView(ImageView(this).apply {
                setImageBitmap(qr)
                layoutParams = LinearLayout.LayoutParams(dp(qrDp), dp(qrDp)).apply { topMargin = dp(6); bottomMargin = dp(4) }
            })
            if (!compact) root.addView(text("Scan to open the config page on your phone", 12f, pal.subtle, padBottom = 24))
        }
        // Buttons: side-by-side when vertical space is tight (shorter labels), stacked otherwise.
        val cfgBtn = button(if (compact) "Configure" else "Open configuration") { openConfig() }
        val haBtn = dashboardIntent()?.let { button(if (compact) "Dashboard" else "Open dashboard") { openDashboard() } }
        if (compact && haBtn != null) {
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                fun weighted(b: Button) { b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { topMargin = dp(10); marginStart = dp(4); marginEnd = dp(4) } }
                weighted(cfgBtn); weighted(haBtn)
                addView(cfgBtn); addView(haBtn)
            })
        } else {
            root.addView(cfgBtn)
            haBtn?.let { root.addView(it) }
        }

        // Cap the content column so the paragraph wraps instead of stretching across a wide panel,
        // and centre it. A ScrollView remains as a safety net if a panel is unexpectedly short.
        val colW = minOf(dm.widthPixels - dp(48), dp(512))
        return ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(pal.bg))
            isFillViewport = true
            addView(
                root,
                android.widget.FrameLayout.LayoutParams(
                    colW, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL,
                ),
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

    // QR of the config URL via ZXing (pure-Java). Null on failure — the UI just omits the QR then.
    private fun qrBitmap(text: String, size: Int): Bitmap? = try {
        val bits = com.google.zxing.qrcode.QRCodeWriter()
            .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size)
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bmp ->
            for (x in 0 until size) for (y in 0 until size)
                bmp.setPixel(x, y, if (bits.get(x, y)) Color.BLACK else Color.WHITE)
        }
    } catch (e: Exception) {
        null
    }

    private fun button(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        setTextColor(pal.btnText)
        background = GradientDrawable().apply {
            cornerRadius = dp(10).toFloat()
            setColor(Color.parseColor(pal.btnBg))
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

    /** Intent that opens the CONFIGURED dashboard: the built-in renderer when it's selected + has a URL,
     *  otherwise the installed HA Companion. Null if nothing can render — so callers don't launch HACA on
     *  a builtin-only panel (or auto-redirect to a dashboard that isn't there). */
    private fun dashboardIntent(): Intent? {
        if (config.dashboardPackage == SystemController.BUILTIN_DASHBOARD && config.haUrl.isNotBlank()) {
            return Intent(this, DashboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        for (p in listOf("io.homeassistant.companion.android.minimal", "io.homeassistant.companion.android")) {
            packageManager.getLaunchIntentForPackage(p)?.let { return it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }
        return null
    }

    private fun openDashboard() {
        dashboardIntent()?.let { runCatching { startActivity(it) } }
    }

    private companion object {
        const val AUTO_RETURN_FIRST_MS = 8_000L   // initial delay before the first redirect attempt
        const val AUTO_RETURN_POLL_MS = 2_000L    // re-check cadence while waiting for MQTT to reconnect
        const val AUTO_RETURN_WINDOW_MS = 90_000L // give up after this (genuinely unconfigured panel)
    }
}
