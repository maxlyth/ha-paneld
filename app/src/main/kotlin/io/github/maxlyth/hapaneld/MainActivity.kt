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
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.maxlyth.hapaneld.util.LocalAdminEndpoint
import io.github.maxlyth.hapaneld.util.localIpv4
import io.github.maxlyth.hapaneld.util.localIpv6

/**
 * Launcher Activity. Requests the notification permission (Android 13+) and starts [PaneldService],
 * then either opens the configured dashboard under kiosk policy or shows a small standing screen — app
 * icon, the full config URL, and buttons to open the config page or dashboard. The standing screen stays
 * available for explicit admin and recovery entry. The agent runs headless as a foreground service
 * regardless of this Activity.
 */
class MainActivity : AppCompatActivity() {

    private val requestNotif =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        ) { startServiceAndChooseDestination() }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private val config by lazy { Config(this) }
    private val url: String
        get() = LocalAdminEndpoint.externalUrl(localIpv4(), localIpv6(), config.httpPort)
    private val handler = Handler(Looper.getMainLooper())
    private var autoReturn: Runnable? = null
    private var autoReturnDeadlineMs = 0L
    private var autoReturnNextAttemptMs = 0L
    private var startupChosen = false
    private var restoredIntroState: SavedLaunchIntroState? = null
    private var introExplicitAdminEntry = false
    private var introVersionPending: Long? = null
    private var introGeneration = 0L
    private var presentedIntro: View? = null
    private var introAcknowledgement: IntroAcknowledgement? = null

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
        restoredIntroState = savedInstanceState?.takeIf { it.getBoolean(STATE_INTRO_PRESENTED, false) }?.let {
            SavedLaunchIntroState(
                explicitAdminEntry = it.getBoolean(STATE_INTRO_EXPLICIT, false),
                pendingVersionCode = it.getLongOrNull(STATE_INTRO_PENDING_VERSION),
                autoReturnRemainingMs = it.getLongOrNull(STATE_AUTO_RETURN_REMAINING),
                autoReturnNextAttemptRemainingMs = it.getLongOrNull(STATE_AUTO_RETURN_NEXT_REMAINING),
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Preserve the existing non-blank first-launch surface behind Android's permission dialog.
            // This provisional view is not a policy choice and therefore never acknowledges a version.
            setContentView(buildUi())
            requestNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startServiceAndChooseDestination()
        }
    }

    private fun startServiceAndChooseDestination() {
        PaneldService.start(this)
        chooseDestination()
    }

    private fun chooseDestination() {
        if (startupChosen) return
        restoredIntroState?.let { saved ->
            val restored = restoreLaunchIntro(
                saved = saved,
                currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                lastShownVersionCode = config.lastLaunchScreenVersionCode,
                nowMs = SystemClock.elapsedRealtime(),
                minimumReturnDelayMs = AUTO_RETURN_RESTORE_GRACE_MS,
                maximumReturnWindowMs = AUTO_RETURN_WINDOW_MS,
            )
            restoredIntroState = null
            presentIntro(restored)
            return
        }
        val target = dashboardIntent()
        val explicitAdminEntry = intent?.getBooleanExtra(EXTRA_EXPLICIT_ADMIN_ENTRY, false) == true
        // ACTION_MAIN/CATEGORY_LAUNCHER cannot distinguish a genuine icon tap from Android restoring the
        // launcher task after update/boot. The Admin Launcher tile is the deliberately explicit route;
        // treating every launcher intent as explicit would reopen #31 for routine task restoration.
        // Do not flash the QR screen while waiting for cold service health. A known crash latch is
        // actionable here; otherwise DashboardActivity owns native network/auth recovery and a foreign
        // renderer owns its own startup surface.
        val decision = LaunchScreenPolicy.decide(
            kioskEnabled = config.kioskLock,
            configuredRenderer = config.dashboardPackage,
            builtInUrlConfigured = config.haUrl.isNotBlank(),
            dashboardLaunchAvailable = target != null,
            dashboardCrashLooping = PanelStatus.dashboardCrashLooping,
            currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
            lastShownVersionCode = config.lastLaunchScreenVersionCode,
            explicitAdminEntry = explicitAdminEntry,
        )
        if (decision.destination == LaunchDestination.DASHBOARD && target != null) {
            val launched = runCatching { startActivity(target) }.isSuccess
            if (launched) {
                startupChosen = true
                finish()
                return
            }
        }

        presentIntro(
            RestoredLaunchIntroPlan(
                explicitAdminEntry = explicitAdminEntry,
                pendingVersionCode = BuildConfig.VERSION_CODE.toLong().takeIf {
                    decision.rememberVersionShown
                },
                autoReturnDeadlineMs = null,
                autoReturnDelayMs = null,
            ),
            freshDecision = decision,
        )
    }

    private fun presentIntro(
        plan: RestoredLaunchIntroPlan,
        freshDecision: LaunchScreenDecision? = null,
    ) {
        // Install the recovery/config surface before acknowledging it. A failed durable commit simply
        // shows the intro again next time, which is safer than hiding the only visible recovery route.
        val intro = buildUi()
        setContentView(intro)
        presentedIntro = intro
        updateKioskAdminVisibility()
        introExplicitAdminEntry = plan.explicitAdminEntry
        introVersionPending = plan.pendingVersionCode
        startupChosen = true
        plan.pendingVersionCode?.let { acknowledgeVersionAfterFirstDraw(intro, it) }
        if (!plan.explicitAdminEntry) {
            if (plan.autoReturnDeadlineMs != null && plan.autoReturnDelayMs != null) {
                maybeArmAutoReturn(
                    restoredDeadlineMs = plan.autoReturnDeadlineMs,
                    restoredDelayMs = plan.autoReturnDelayMs,
                )
            } else if (freshDecision != null) {
                maybeArmAutoReturn(ignoreUpdateAge = freshDecision.rememberVersionShown)
            }
        }
    }

    private fun acknowledgeVersionAfterFirstDraw(view: View, versionCode: Long) {
        introAcknowledgement?.cancel()
        val generation = ++introGeneration
        introAcknowledgement = IntroAcknowledgement(view, versionCode, generation).also { it.arm() }
    }

    private inner class IntroAcknowledgement(
        private val view: View,
        private val versionCode: Long,
        private val generation: Long,
    ) : ViewTreeObserver.OnDrawListener, Runnable {
        private val observer = view.viewTreeObserver
        private var posted = false

        fun arm() = observer.addOnDrawListener(this)

        override fun onDraw() {
            if (posted) return
            posted = true
            // OnDrawListener cannot be removed during dispatch. Posting completes this draw first.
            view.post(this)
        }

        override fun run() {
            removeListener()
            if (introAcknowledgement === this) introAcknowledgement = null
            if (mayAcknowledgePresentedIntro(
                    generationMatches = generation == introGeneration,
                    presentedViewMatches = presentedIntro === view,
                    viewAttached = view.isAttachedToWindow,
                    activityFinishing = isFinishing,
                    activityDestroyed = isDestroyed,
                    startupChosen = startupChosen,
                )
            ) {
                if (config.commitLaunchScreenVersionShown(versionCode) &&
                    introVersionPending == versionCode
                ) {
                    introVersionPending = null
                }
            }
        }

        fun cancel() {
            view.removeCallbacks(this)
            removeListener()
        }

        private fun removeListener() {
            if (observer.isAlive) observer.removeOnDrawListener(this)
        }
    }

    // After an app update the launcher lands on this UI; once the configured renderer is launchable,
    // bounce back to the dashboard so it doesn't linger. Cancelled by any
    // touch (so someone who opened it on purpose isn't yanked away). Gated on a recent app update, so a
    // deliberate open long afterwards just stays put. A policy-selected changed-version intro also gets
    // this short return even when kiosk was enabled after the APK update timestamp aged out.
    //
    // POLL rather than check once: after a restart (worst case a whole-fleet restart flooding the broker)
    // The built-in renderer is ready once its HA URL and launch target exist. A launchable external
    // renderer owns its own HA readiness; MQTT is optional for both. Re-check every
    // [AUTO_RETURN_POLL_MS] until the renderer-specific gate opens or the [AUTO_RETURN_WINDOW_MS]
    // window elapses.
    private fun maybeArmAutoReturn(
        ignoreUpdateAge: Boolean = false,
        restoredDeadlineMs: Long? = null,
        restoredDelayMs: Long? = null,
    ) {
        if (!config.autoReturnDashboard || dashboardIntent() == null) return
        val updated = runCatching { packageManager.getPackageInfo(packageName, 0).lastUpdateTime }.getOrDefault(0L)
        if (restoredDeadlineMs == null &&
            !ignoreUpdateAge &&
            System.currentTimeMillis() - updated > 5 * 60 * 1000L
        ) return
        val now = SystemClock.elapsedRealtime()
        val deadline = restoredDeadlineMs ?: now + AUTO_RETURN_WINDOW_MS
        val firstDelay = restoredDelayMs ?: AUTO_RETURN_FIRST_MS
        if (deadline <= now || now + firstDelay > deadline) return
        val r = object : Runnable {
            override fun run() {
                val launchAvailable = dashboardIntent() != null
                if (PostUpdateReturnPolicy.dashboardReady(
                        configuredRenderer = config.dashboardPackage,
                        builtInUrlConfigured = config.haUrl.isNotBlank(),
                        dashboardLaunchAvailable = launchAvailable,
                        dashboardCrashLooping = PanelStatus.dashboardCrashLooping,
                    )
                ) {
                    cancelAutoReturn()
                    if (openDashboard()) finish()
                    return
                }
                val retryNow = SystemClock.elapsedRealtime()
                if (retryNow >= deadline) { cancelAutoReturn(); return } // give up (unconfigured)
                autoReturnNextAttemptMs = retryNow + AUTO_RETURN_POLL_MS
                handler.postDelayed(this, AUTO_RETURN_POLL_MS)
            }
        }
        autoReturn = r
        autoReturnDeadlineMs = deadline
        autoReturnNextAttemptMs = now + firstDelay
        handler.postDelayed(r, firstDelay) // let a genuine touch cancel + give MQTT a head start
    }

    private fun cancelAutoReturn() {
        autoReturn?.let { handler.removeCallbacks(it) }
        autoReturn = null
        autoReturnDeadlineMs = 0L
        autoReturnNextAttemptMs = 0L
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.action == MotionEvent.ACTION_DOWN) cancelAutoReturn() // user is here on purpose
        return super.dispatchTouchEvent(ev)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (presentedIntro != null) {
            outState.putBoolean(STATE_INTRO_PRESENTED, true)
            outState.putBoolean(STATE_INTRO_EXPLICIT, introExplicitAdminEntry)
            introVersionPending?.let { outState.putLong(STATE_INTRO_PENDING_VERSION, it) }
            if (autoReturn != null) {
                val now = SystemClock.elapsedRealtime()
                val remaining = (autoReturnDeadlineMs - now).coerceIn(0L, AUTO_RETURN_WINDOW_MS)
                val nextRemaining = (autoReturnNextAttemptMs - now).coerceIn(0L, remaining)
                outState.putLong(STATE_AUTO_RETURN_REMAINING, remaining)
                outState.putLong(STATE_AUTO_RETURN_NEXT_REMAINING, nextRemaining)
            }
        }
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        KioskAdminUi.setVisible(this, false)
        cancelAutoReturn()
        introAcknowledgement?.cancel()
        introAcknowledgement = null
        presentedIntro = null
        introGeneration++
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        updateKioskAdminVisibility()
    }

    override fun onStop() {
        KioskAdminUi.setVisible(this, false)
        super.onStop()
    }

    private fun updateKioskAdminVisibility() {
        KioskAdminUi.setVisible(this, presentedIntro != null)
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
            contentDescription = getString(R.string.wordmark_description)
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
                contentDescription = getString(R.string.config_qr_description, url)
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
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { qr ->
            for (x in 0 until size) for (y in 0 until size)
                qr.setPixel(x, y, if (bits.get(x, y)) Color.BLACK else Color.WHITE)
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

    /** Intent that opens the CONFIGURED dashboard: a ready built-in renderer, an explicitly selected
     * foreign renderer, or Companion auto-detection when the selection is blank. Null preserves the
     * recovery screen when nothing launchable exists. */
    private fun dashboardIntent(): Intent? {
        if (config.dashboardPackage == SystemController.BUILTIN_DASHBOARD && config.haUrl.isNotBlank()) {
            return Intent(this, DashboardActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        for (p in externalRendererCandidates(config.dashboardPackage)) {
            packageManager.getLaunchIntentForPackage(p)?.let { return it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        }
        return null
    }

    private fun openDashboard(): Boolean = dashboardIntent()?.let {
        runCatching { startActivity(it) }.isSuccess
    } ?: false

    private fun Bundle.getLongOrNull(key: String): Long? =
        if (containsKey(key)) getLong(key) else null

    companion object {
        internal const val EXTRA_EXPLICIT_ADMIN_ENTRY =
            "io.github.maxlyth.hapaneld.extra.EXPLICIT_ADMIN_ENTRY"
        private const val STATE_INTRO_PRESENTED = "launch_intro_presented"
        private const val STATE_INTRO_EXPLICIT = "launch_intro_explicit"
        private const val STATE_INTRO_PENDING_VERSION = "launch_intro_pending_version"
        private const val STATE_AUTO_RETURN_REMAINING = "launch_intro_return_remaining"
        private const val STATE_AUTO_RETURN_NEXT_REMAINING = "launch_intro_return_next_remaining"
        private const val AUTO_RETURN_RESTORE_GRACE_MS = 250L
        const val AUTO_RETURN_FIRST_MS = 8_000L   // initial delay before the first redirect attempt
        const val AUTO_RETURN_POLL_MS = 2_000L    // re-check cadence while waiting for MQTT to reconnect
        const val AUTO_RETURN_WINDOW_MS = 90_000L // give up after this (genuinely unconfigured panel)
    }
}
