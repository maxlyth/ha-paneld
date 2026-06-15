package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.maxlyth.hapaneld.R

/**
 * Soft on-screen navigation bar drawn as a system overlay (`TYPE_APPLICATION_OVERLAY`). For panels
 * whose firmware suppresses the native Android navigation bar — NSPanel Pro hardcodes
 * `config_showNavigationBar=false`, and both `policy_control` and `qemu.hw.mainkeys` are ignored —
 * so the user otherwise has no Back / Home / Recents. (Replaces the equivalent NSPanelToolsPro
 * feature for users who have uninstalled it.) Three modes:
 *
 * - `Off` — no overlay.
 * - `Always on` — persistent bar pinned to the bottom edge.
 * - `Swipe reveal` — bar hidden; a thin bottom-edge touch strip reveals it for [AUTO_HIDE_MS], then
 *   it auto-hides.
 *
 * Buttons (left→right): Back, Launcher, Recents, Brightness−/+, Volume−/+. The panel has no physical
 * buttons, so the bar doubles as a hardware-button surface — brightness and volume step locally with
 * no round-trip to HA. Back/Recents use the accessibility service's global actions (no root, but
 * `PanelAccessibilityService` must be enabled); Launcher opens the device launcher / app drawer via
 * [SystemController]; brightness/volume go through their controllers — all of those work without a11y.
 * Drawing the overlay needs `SYSTEM_ALERT_WINDOW`, which the panels don't grant by default — we grant
 * it via root appops when available. Without the permission and without root the overlay silently
 * no-ops, like the other root-gated capabilities.
 *
 * All [WindowManager] mutations are posted to the main looper — `addView`/`removeView` must run on the
 * UI thread, but [apply] is called from the MQTT thread.
 */
class NavbarController(
    private val context: Context,
    private val system: SystemController,
    private val volume: VolumeController,
    private val brightness: BrightnessController,
    private val launcherPkg: () -> String,
    // Back/Recents route: root `input keyevent` where the app can su, else accessibility (see NavActions).
    private val appCanSu: Boolean,
    // Omit the Recents button on panels whose firmware has no overview screen (e.g. Tuya TPA10).
    private val hasRecents: Boolean,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())

    private var bar: View? = null      // the visible button row
    private var strip: View? = null    // the swipe-reveal edge trigger (Swipe-reveal mode only)
    private var brightLabel: TextView? = null  // live brightness % (wide panels only)
    private var volLabel: TextView? = null     // live volume % (wide panels only)
    private val hideRunnable = Runnable { animateBarOut() }

    @Volatile
    var mode: String = MODE_OFF
        private set

    /**
     * Idempotent: tear down the current overlay state and rebuild for [newMode]. Called off the main
     * thread (MQTT / startup coroutine), so the one potentially-blocking step — granting the overlay
     * appop via root su — happens here, before the view work is posted to the UI thread.
     */
    fun apply(newMode: String) {
        val m = normalise(newMode)
        mode = m
        if (m != MODE_OFF) ensureOverlayPermission()
        main.post {
            main.removeCallbacks(hideRunnable)
            removeBar()
            removeStrip()
            when (m) {
                MODE_ALWAYS -> addBar(autoHide = false)
                MODE_SWIPE -> addStrip()
                else -> {} // Off — nothing drawn
            }
        }
    }

    // --- overlay construction (main thread only) ---

    private fun addBar(autoHide: Boolean) {
        if (bar != null) return
        if (!canDraw()) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not held (no root to grant it); navbar suppressed")
            return
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(BAR_BG)
            gravity = Gravity.CENTER_VERTICAL
        }
        // Only wide panels (e.g. landscape TPA10) get the live value readout between the ±-pairs;
        // square NSPanels are too narrow, so there it's icons-only.
        val showValues = widthDp() >= VALUE_WIDTH_THRESHOLD_DP
        // Nav buttons are weight 1.0 (the even baseline spacing — unchanged). Each ±/value triple has
        // its members at weight TIGHTEN (35% tighter), with the reclaimed weight as spacers either side
        // so the group still occupies its default slot and nav spacing is untouched. A triple of k
        // members reclaims k·(1−TIGHTEN), split evenly to the two side spacers.
        val k = if (showValues) 3 else 2
        val side = k * (1f - TIGHTEN) / 2f
        // The triple→separator gap is (side + half a triple cell). Match every group boundary to it —
        // both bar ends and the nav→separator gap — accounting for nav cells (½ = 0.5) being wider than
        // triple cells (½ = TIGHTEN/2). Keeps recents↔separator equal to the other three separator gaps.
        val edge = (side + TIGHTEN / 2f - 0.5f).coerceAtLeast(0f)
        row.addView(spacer(edge))
        // Back/Recents/Launcher run a slow su / activity call — navButton offloads it AND holds the press
        // highlight lit from touch-down until it completes, so the tap isn't mistaken for a no-op.
        row.addView(navButton(R.drawable.ic_nav_back, autoHide) { NavActions.back(appCanSu) })
        row.addView(navButton(R.drawable.ic_nav_launcher, autoHide) { system.launchLauncher(launcherPkg()) })
        // Recents only where the firmware actually has an overview screen.
        if (hasRecents) row.addView(navButton(R.drawable.ic_nav_recents, autoHide) { NavActions.recents(appCanSu) })
        row.addView(spacer(edge))
        // Brightness — tap steps once, press-and-hold ramps; label (if shown) updates live.
        row.addView(separator())
        row.addView(spacer(side))
        row.addView(repeatButton(R.drawable.ic_nav_bright_down, autoHide) { stepBrightness(-BRIGHT_STEP); updateBrightLabel() })
        if (showValues) valueLabel().also { brightLabel = it; row.addView(it) }
        row.addView(repeatButton(R.drawable.ic_nav_bright_up, autoHide) { stepBrightness(+BRIGHT_STEP); updateBrightLabel() })
        row.addView(spacer(side))
        // Volume — tap/hold; showUi flashes the system volume slider for feedback.
        row.addView(separator())
        row.addView(spacer(side))
        row.addView(repeatButton(R.drawable.ic_nav_vol_down, autoHide) { volume.setPercent(volume.getPercent() - VOL_STEP, showUi = true); updateVolLabel() })
        if (showValues) valueLabel().also { volLabel = it; row.addView(it) }
        row.addView(repeatButton(R.drawable.ic_nav_vol_up, autoHide) { volume.setPercent(volume.getPercent() + VOL_STEP, showUi = true); updateVolLabel() })
        row.addView(spacer(side))
        if (showValues) { updateBrightLabel(); updateVolLabel() }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(BAR_HEIGHT_DP),
            overlayType(),
            // Not focusable (don't steal key focus from the dashboard) but still touchable.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        try {
            // Slide up from the bottom edge: start translated fully below the window frame, animate to 0.
            row.translationY = dp(BAR_HEIGHT_DP).toFloat()
            wm.addView(row, lp)
            row.animate().translationY(0f).setDuration(ANIM_MS).start()
            bar = row
            if (autoHide) scheduleHide()
        } catch (e: Exception) {
            Log.w(TAG, "addView(bar) failed", e)
        }
    }

    /** Slide the bar down off the bottom edge, then remove it (swipe-reveal auto-hide). */
    private fun animateBarOut() {
        val b = bar ?: return
        b.animate().translationY(dp(BAR_HEIGHT_DP).toFloat()).setDuration(ANIM_MS)
            .withEndAction { removeBar() }.start()
    }

    private fun addStrip() {
        if (strip != null) return
        if (!canDraw()) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not held (no root to grant it); navbar suppressed")
            return
        }
        val edge = View(context).apply {
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_DOWN) { reveal(); true } else false
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(STRIP_HEIGHT_DP),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        try {
            wm.addView(edge, lp)
            strip = edge
        } catch (e: Exception) {
            Log.w(TAG, "addView(strip) failed", e)
        }
    }

    /** Swipe-reveal: show the bar over the strip, schedule auto-hide. If a slide-out is in flight,
     *  cancel it and snap back to fully shown. */
    private fun reveal() {
        val b = bar
        if (b == null) {
            addBar(autoHide = true)
        } else {
            b.animate().cancel()
            b.translationY = 0f
            scheduleHide()
        }
    }

    private fun scheduleHide() {
        main.removeCallbacks(hideRunnable)
        main.postDelayed(hideRunnable, AUTO_HIDE_MS)
    }

    private fun removeBar() {
        bar?.let { runCatching { it.animate().cancel(); wm.removeView(it) } }
        bar = null
        brightLabel = null
        volLabel = null
    }

    /** Live "NN%" readout placed between a ±-pair on wide panels. */
    private fun valueLabel(): TextView = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        minWidth = dp(VALUE_LABEL_MIN_W_DP)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, TIGHTEN)
    }

    private fun updateBrightLabel() {
        brightLabel?.text = "${(brightness.getBrightness().coerceAtLeast(0) * 100 + 127) / 255}%"
    }

    private fun updateVolLabel() {
        volLabel?.text = "${volume.getPercent()}%"
    }

    /** Current display width in dp (rotation-aware) — gates the value readout. */
    private fun widthDp(): Int = context.resources.displayMetrics.let {
        (it.widthPixels / it.density).toInt()
    }

    private fun removeStrip() {
        strip?.let { runCatching { wm.removeView(it) } }
        strip = null
    }

    /** A weighted cell holding a FIXED-size, centred icon. The cell [weight] controls spacing (nav = 1.0
     *  baseline; triple members = [TIGHTEN] → narrower cells, tighter spacing), but the icon itself is a
     *  constant [ICON_SIZE_DP] regardless of cell width — so triple icons render the same size as nav
     *  icons even when their cells are narrow (otherwise FIT_CENTER shrinks them on narrow panels). */
    private fun iconCell(icon: Int, weight: Float): FrameLayout {
        val iv = ImageView(context).apply {
            setImageResource(icon)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = FrameLayout.LayoutParams(dp(ICON_SIZE_DP), dp(ICON_SIZE_DP), Gravity.CENTER)
        }
        return FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
            // Instant press highlight on touch-down — the action itself (esp. Back/Recents via su) can
            // lag ~200-300ms, so without this a tap reads as "nothing happened". Shows on touch-down for
            // clickable nav buttons automatically; repeatButton sets isPressed for the ±-buttons.
            foreground = pressHighlight()
            addView(iv)
        }
    }

    /** Transparent normally, a translucent flash while pressed. No theme needed (overlay isn't themed). */
    private fun pressHighlight(): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(PRESS_TINT))
        addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
    }

    /** Weighted flexible gap — the reclaimed width either side of a tightened triple keeps the group
     *  centred in its slot so nav spacing stays at the default. */
    private fun spacer(weight: Float): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, weight)
    }

    /** Single-tap nav button — weight 1.0, spread evenly across the bar (unchanged baseline). The press
     *  highlight is held from touch-down until [action] (a slow su / activity call, run off the UI
     *  thread) completes, with a [FEEDBACK_MIN_MS] floor, so the tap stays acknowledged during the lag. */
    private fun navButton(icon: Int, autoHide: Boolean, action: () -> Unit): View {
        val iv = iconCell(icon, 1f)
        iv.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    iv.isPressed = true
                    if (autoHide) scheduleHide()
                    val t0 = SystemClock.uptimeMillis()
                    Thread {
                        runCatching { action() }
                        val hold = (FEEDBACK_MIN_MS - (SystemClock.uptimeMillis() - t0)).coerceAtLeast(0L)
                        main.postDelayed({ iv.isPressed = false }, hold)
                    }.start()
                    true
                }
                else -> true // consume UP/MOVE/CANCEL — the action's completion clears the highlight
            }
        }
        return iv
    }

    /** Press-and-hold triple button (weight [TIGHTEN]): [step] fires on touch-down, then repeats while
     *  held (volume / brightness ramping). Pauses auto-hide for the duration of the press. */
    private fun repeatButton(icon: Int, autoHide: Boolean, step: () -> Unit): View {
        val iv = iconCell(icon, TIGHTEN)
        val repeater = object : Runnable {
            override fun run() { step(); main.postDelayed(this, REPEAT_INTERVAL_MS) }
        }
        iv.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    iv.isPressed = true   // show the press highlight (we consume the event below)
                    if (autoHide) main.removeCallbacks(hideRunnable) // don't hide mid-hold
                    step()
                    main.postDelayed(repeater, REPEAT_DELAY_MS)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    iv.isPressed = false
                    main.removeCallbacks(repeater)
                    if (autoHide) scheduleHide()
                    true
                }
                else -> false
            }
        }
        return iv
    }

    /** Vertical divider between button groups (shorter than the bar height, centred). The wide
     *  side margins also push the groups apart so the ±-pairs cluster visibly tighter. */
    private fun separator(): View = View(context).apply {
        setBackgroundColor(SEP_COLOR)
        layoutParams = LinearLayout.LayoutParams(dp(SEP_WIDTH_DP), dp(SEP_HEIGHT_DP)).apply {
            gravity = Gravity.CENTER_VERTICAL
            val m = dp(SEP_MARGIN_DP); leftMargin = m; rightMargin = m
        }
    }

    /** Step screen brightness by [delta] (of 0–255), floored at 10 so the screen never goes black. */
    private fun stepBrightness(delta: Int) {
        val cur = brightness.getBrightness().let { if (it < 0) 128 else it }
        brightness.setBrightness((cur + delta).coerceIn(10, 255))
    }

    // --- permission ---

    private fun canDraw(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /** Grant `SYSTEM_ALERT_WINDOW` via root appops if it isn't already held (panels have no UI flow). */
    private fun ensureOverlayPermission(): Boolean {
        if (canDraw()) return true
        Su.run("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        return canDraw()
    }

    private fun overlayType(): Int =
        // minSdk 26 == O, so always the unprivileged overlay type; guard kept for clarity.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    private fun normalise(m: String): String =
        MODES.firstOrNull { it.equals(m.trim().trim('"'), ignoreCase = true) } ?: MODE_OFF

    companion object {
        private const val TAG = "ha-paneld/navbar"

        const val MODE_OFF = "Off"
        const val MODE_ALWAYS = "Always on"
        const val MODE_SWIPE = "Swipe reveal"
        val MODES = listOf(MODE_OFF, MODE_ALWAYS, MODE_SWIPE)

        private const val BAR_HEIGHT_DP = 56   // taller so the icons read at a glance
        private const val STRIP_HEIGHT_DP = 28   // swipe-reveal touch target — 12dp was too thin to hit
        private const val FEEDBACK_MIN_MS = 350L  // min press-highlight visible time (bridges su latency)
        private const val ICON_SIZE_DP = 30    // fixed icon size, centred in its cell (uniform across all buttons)
        private const val TIGHTEN = 0.65f      // triple-member cell weight vs nav's 1.0 → ~35% tighter spacing
        private val BAR_BG = 0xC2282C34.toInt() // charcoal @ ~76% — translucent but still reads solid
        private val PRESS_TINT = 0x55FFFFFF.toInt() // press-feedback flash (~33% white)
        private const val AUTO_HIDE_MS = 4000L
        private const val ANIM_MS = 220L       // swipe-reveal slide in/out duration
        private const val VALUE_WIDTH_THRESHOLD_DP = 600 // wide panels (TPA10) get the % readout; square NSPanels don't
        private const val VALUE_LABEL_MIN_W_DP = 42
        private const val VOL_STEP = 10        // volume % per tap
        private const val BRIGHT_STEP = 32     // brightness (0–255) per tap ≈ 12.5%
        private const val REPEAT_DELAY_MS = 400L     // hold this long before ramping starts
        private const val REPEAT_INTERVAL_MS = 120L  // then step every this often while held
        private val SEP_COLOR = 0x99FFFFFF.toInt()   // 60% white group divider — clearly visible
        private const val SEP_WIDTH_DP = 2
        private const val SEP_HEIGHT_DP = 30
        private const val SEP_MARGIN_DP = 7          // gap each side of the group divider
    }
}
