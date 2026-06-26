package io.github.maxlyth.hapaneld.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
    // Dashboard package whose force-stop+relaunch is the Reload button's action (blank => auto-detect the
    // HA Companion; see [SystemController.reloadDashboard]).
    private val dashboardPkg: () -> String,
    // Back/Recents route: root `input keyevent` where the app can su, else accessibility (see NavActions).
    private val appCanSu: Boolean,
    // Omit the Recents button on panels whose firmware has no overview screen (e.g. Tuya TPA10).
    private val hasRecents: Boolean,
    // Notify HA after a LOCAL navbar change so light.<panel>_screen / number.<panel>_volume don't go
    // stale (the bar steps brightness/volume directly, bypassing the MQTT command path).
    private val onBrightnessChanged: () -> Unit = {},
    private val onVolumeChanged: () -> Unit = {},
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())

    private var bar: View? = null      // the visible button row
    private var strip: View? = null    // the swipe-reveal edge trigger (Swipe-reveal mode only)
    private var brightLabel: TextView? = null  // live brightness % (wide panels only)
    private var volLabel: TextView? = null     // live volume % (wide panels only)
    private var volReceiver: BroadcastReceiver? = null  // refreshes volLabel on external volume changes
    private val hideRunnable = Runnable { animateBarOut() }

    // Narrow-panel pop-up slider (brightness/volume): a small separate overlay above the bar. Only one open
    // at a time; [sliderKind] tracks which control owns it so re-tapping the same icon toggles it shut.
    private var sliderView: View? = null
    private var sliderKind: Slider? = null
    private val sliderHide = Runnable { dismissSlider() }

    private enum class Slider { BRIGHTNESS, VOLUME }

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
        if (m != MODE_OFF) {
            ensureOverlayPermission()
            if (volReceiver == null) {
                val r = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        main.post { updateVolLabel() }
                    }
                }
                // "android.media.VOLUME_CHANGED_ACTION" is an @hide constant — use the string literal.
                // RECEIVER_EXPORTED is required from API 33 for receiving system broadcasts.
                @Suppress("UnspecifiedRegisterReceiverFlag")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(r, IntentFilter(VOLUME_CHANGED_ACTION), Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(r, IntentFilter(VOLUME_CHANGED_ACTION))
                }
                volReceiver = r
            }
        } else {
            volReceiver?.let { runCatching { context.unregisterReceiver(it) }; volReceiver = null }
        }
        if (appCanSu && m == MODE_ALWAYS) {
            // Always-on: apply the display overscan (blocking) before posting the bar so the content
            // visibly shifts up before the bar appears, not after. Su contention is acceptable here
            // since the user just toggled a setting.
            applyOverscan(BAR_HEIGHT_DP)
            main.post {
                main.removeCallbacks(hideRunnable)
                removeBar(); removeStrip()
                addBar(autoHide = false)
            }
        } else {
            // Swipe-reveal / Off: post the view work immediately so the strip appears without waiting
            // on the su call. Clear any leftover overscan in the background — it shouldn't block the
            // strip from being available (overscan clear contends with startup tame su calls and can
            // take 5–10 s on a cold start, which previously delayed the strip by that long).
            main.post {
                main.removeCallbacks(hideRunnable)
                removeBar(); removeStrip()
                if (m == MODE_SWIPE) addStrip()
            }
            if (appCanSu) Thread { runCatching { applyOverscan(0) } }.start()
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
        // Wide panels (e.g. landscape TPA10) keep the ±-pairs with the live % readout; narrow panels (a
        // portrait NSPanel 120P) have no room for that, so they collapse each control to a single icon that
        // opens a vertical pop-up slider — which also frees the space for the Reload button.
        if (widthDp() >= VALUE_WIDTH_THRESHOLD_DP) buildWideRow(row, autoHide)
        else buildNarrowRow(row, autoHide)
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

    /** Wide layout: nav group (Back · Launcher · [Recents] · Reload) + the ±-pairs with the live % readout.
     *  Each ±/value triple's members are weight [TIGHTEN] (35% tighter), with the reclaimed weight as
     *  spacers either side so the group keeps its default slot and nav spacing is untouched. */
    private fun buildWideRow(row: LinearLayout, autoHide: Boolean) {
        val side = 3 * (1f - TIGHTEN) / 2f
        // Match every group boundary to the triple→separator gap (bar ends + nav→separator), accounting for
        // nav cells (½ = 0.5) being wider than triple cells (½ = TIGHTEN/2).
        val edge = (side + TIGHTEN / 2f - 0.5f).coerceAtLeast(0f)
        row.addView(spacer(edge))
        addNavGroup(row, autoHide)
        row.addView(spacer(edge))
        row.addView(separator())
        row.addView(spacer(side))
        row.addView(repeatButton(R.drawable.ic_nav_bright_down, autoHide) { stepBrightness(-BRIGHT_STEP); updateBrightLabel() })
        valueLabel().also { brightLabel = it; row.addView(it) }
        row.addView(repeatButton(R.drawable.ic_nav_bright_up, autoHide) { stepBrightness(+BRIGHT_STEP); updateBrightLabel() })
        row.addView(spacer(side))
        row.addView(separator())
        row.addView(spacer(side))
        row.addView(repeatButton(R.drawable.ic_nav_vol_down, autoHide) { volume.step(up = false); updateVolLabel(); onVolumeChanged() })
        valueLabel().also { volLabel = it; row.addView(it) }
        row.addView(repeatButton(R.drawable.ic_nav_vol_up, autoHide) { volume.step(up = true); updateVolLabel(); onVolumeChanged() })
        row.addView(spacer(side))
        updateBrightLabel(); updateVolLabel()
    }

    /** Narrow layout (portrait NSPanel 120P): everything is a single weight-1.0 icon, evenly spread —
     *  Back · Launcher · [Recents] · Reload | Brightness | Volume. Brightness/Volume each open a vertical
     *  pop-up slider above the icon (there's no room for ±-pairs or a % label). */
    private fun buildNarrowRow(row: LinearLayout, autoHide: Boolean) {
        addNavGroup(row, autoHide)
        row.addView(separator())
        row.addView(sliderButton(R.drawable.ic_nav_bright_up, Slider.BRIGHTNESS, autoHide))
        row.addView(separator())
        row.addView(sliderButton(R.drawable.ic_nav_vol_up, Slider.VOLUME, autoHide))
    }

    /** Back · Launcher · [Recents] · Reload — the navigation cluster shared by both layouts. Back/Recents/
     *  Launcher run a slow su / activity call, so navButton offloads it and holds the press highlight until
     *  it completes. Reload force-stops + relaunches the dashboard app. */
    private fun addNavGroup(row: LinearLayout, autoHide: Boolean) {
        row.addView(navButton(R.drawable.ic_nav_back, autoHide) { NavActions.back(appCanSu) })
        row.addView(navButton(R.drawable.ic_nav_launcher, autoHide) { system.launchLauncher(launcherPkg()) })
        if (hasRecents) row.addView(navButton(R.drawable.ic_nav_recents, autoHide) { NavActions.recents(appCanSu) })
        row.addView(navButton(R.drawable.ic_nav_reload, autoHide) { system.reloadDashboard(dashboardPkg()) })
    }

    /** Narrow-panel control button: a single weight-1.0 icon that toggles a vertical pop-up slider for
     *  [kind] above it. Tap to open (re-tap to close); the slider drives brightness/volume live. */
    private fun sliderButton(icon: Int, kind: Slider, autoHide: Boolean): View {
        val cell = iconCell(icon, 1f)
        cell.isClickable = true
        cell.setOnClickListener {
            if (autoHide) main.removeCallbacks(hideRunnable)   // keep the bar up while adjusting
            if (sliderKind == kind) dismissSlider() else showSlider(kind, cell)
        }
        return cell
    }

    /** Show a vertical drag-slider for [kind] in its own overlay, centred above [anchor]. Outside-tap or a
     *  short idle timeout closes it. Only one is ever open (re-shows replace the previous). */
    private fun showSlider(kind: Slider, anchor: View) {
        dismissSlider()
        if (!canDraw()) return
        val level0 = when (kind) {
            Slider.BRIGHTNESS -> brightness.getBrightness().coerceIn(0, 255) / 255f
            Slider.VOLUME -> volume.getPercent() / 100f
        }
        val slider = VerticalSlider(level0) { lv ->
            when (kind) {
                Slider.BRIGHTNESS -> { brightness.setBrightness((lv * 255).toInt().coerceIn(10, 255)); onBrightnessChanged() }
                Slider.VOLUME -> { volume.setPercent((lv * 100).toInt()); onVolumeChanged() }
            }
            resetSliderTimeout()
        }
        val pad = dp(SLIDER_PAD_DP)
        val container = FrameLayout(context).apply {
            setPadding(pad, pad, pad, pad)
            background = roundedBg()
            addView(slider, FrameLayout.LayoutParams(dp(SLIDER_TRACK_W_DP) + 2 * pad, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
            // Outside touch (FLAG_WATCH_OUTSIDE_TOUCH) closes; inside touches fall through to the slider.
            setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { dismissSlider(); true } else false }
        }
        val loc = IntArray(2); anchor.getLocationOnScreen(loc)
        val w = dp(SLIDER_TRACK_W_DP) + 4 * pad
        val lp = WindowManager.LayoutParams(
            w, dp(SLIDER_HEIGHT_DP), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            x = (loc[0] + anchor.width / 2 - w / 2).coerceAtLeast(0)
            y = dp(BAR_HEIGHT_DP) + dp(SLIDER_GAP_DP)
        }
        try {
            wm.addView(container, lp); sliderView = container; sliderKind = kind; resetSliderTimeout()
        } catch (e: Exception) {
            Log.w(TAG, "addView(slider) failed", e)
        }
    }

    private fun dismissSlider() {
        main.removeCallbacks(sliderHide)
        sliderView?.let { runCatching { wm.removeView(it) } }
        sliderView = null; sliderKind = null
    }

    private fun resetSliderTimeout() {
        main.removeCallbacks(sliderHide)
        main.postDelayed(sliderHide, SLIDER_IDLE_MS)
    }

    /** Vertical fill-slider: a rounded track filled from the bottom to the current 0..1 [level]; drag
     *  anywhere on it to set the level, which calls [onLevel] live. */
    private inner class VerticalSlider(level0: Float, val onLevel: (Float) -> Unit) : View(context) {
        private var level = level0.coerceIn(0f, 1f)
        private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = SLIDER_TRACK_COLOR }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            val r = w / 2f
            c.drawRoundRect(0f, 0f, w, h, r, r, trackPaint)
            val top = (h * (1f - level)).coerceIn(0f, h)
            c.drawRoundRect(0f, top, w, h, r, r, fillPaint)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    level = (1f - e.y / height).coerceIn(0f, 1f)
                    onLevel(level); invalidate()
                }
                MotionEvent.ACTION_UP -> resetSliderTimeout()
            }
            return true
        }
    }

    /** Rounded charcoal background for the slider pop-up (same charcoal as the bar). */
    private fun roundedBg(): Drawable = android.graphics.drawable.GradientDrawable().apply {
        setColor(BAR_BG); cornerRadius = dp(SLIDER_RADIUS_DP).toFloat()
    }

    /** Slide the bar down off the bottom edge, then remove it (swipe-reveal auto-hide). */
    private fun animateBarOut() {
        val b = bar ?: return
        b.animate().translationY(dp(BAR_HEIGHT_DP).toFloat()).setDuration(ANIM_MS)
            // Detach via detachBar (NOT removeBar) — calling animate().cancel() from inside the animator's
            // own end action re-applies the transform and flashes the bar back at its resting position for
            // a frame. detachBar hides-then-removes without cancelling, killing that flash.
            .withEndAction { detachBar(b) }.start()
    }

    /** Detach the bar view without touching its animator. Make it INVISIBLE before `removeView` so the
     *  window manager's final layout pass on removal can't draw it at its resting position for a frame. */
    private fun detachBar(b: View) {
        dismissSlider()   // a pop-up slider must never outlive its bar
        runCatching { b.visibility = View.INVISIBLE; wm.removeView(b) }
        if (bar === b) {
            bar = null
            brightLabel = null
            volLabel = null
        }
    }

    private fun addStrip() {
        if (strip != null) return
        if (!canDraw()) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not held (no root to grant it); navbar suppressed")
            return
        }
        val edge = View(context)
        // lp is declared before the touch listener so the listener can capture it for the
        // FLAG_NOT_TOUCHABLE trick used to re-inject taps (see ACTION_UP handling below).
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(STRIP_HEIGHT_DP),
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.BOTTOM }
        // Consume the whole gesture (return true for all events) so the dashboard WebView doesn't
        // receive partial swipes — returning false on MOVE previously let an off-screen-origin swipe-up
        // scroll the dashboard while also revealing the bar. Touches above the strip still pass through
        // normally (they're outside this window's bounds).
        // Reveal is gated on a genuine upward swipe (≥ SWIPE_MIN_DP travel) so taps don't accidentally
        // pop the bar up. On root panels, taps consumed by the strip are re-injected: the strip is
        // briefly flagged FLAG_NOT_TOUCHABLE so `input tap` routes to the underlying app rather than
        // looping back to the strip itself.
        var downX = 0f; var downY = 0f; var swiped = false
        edge.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; swiped = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!swiped && downY - e.rawY >= dp(SWIPE_MIN_DP)) { swiped = true; reveal() }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!swiped && appCanSu) {
                        val x = downX.toInt(); val y = downY.toInt()
                        // Pause strip touchability so the re-injected tap routes to the window behind
                        // instead of back to the strip. Restored after `input tap` returns (~300 ms).
                        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        runCatching { wm.updateViewLayout(edge, lp) }
                        Thread {
                            runCatching { Su.run("input tap $x $y") }
                            main.post {
                                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                                runCatching { wm.updateViewLayout(edge, lp) }
                            }
                        }.start()
                    }
                    true
                }
                else -> true
            }
        }
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

    /** Tear down the bar immediately (mode change). Cancels any in-flight slide first — safe here since
     *  it's an external call, not the animator's own end action — then detaches. */
    private fun removeBar() {
        bar?.let { b -> runCatching { b.animate().cancel() }; detachBar(b) }
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
        onBrightnessChanged()
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

    private fun applyOverscan(heightDp: Int) {
        Su.run("wm overscan 0,0,0,${dp(heightDp)}")
    }

    /** Reset any display overscan set by this controller. Call from the service's onDestroy so the
     *  reserved bottom margin doesn't persist after ha-paneld stops. */
    fun cleanup() {
        volReceiver?.let { runCatching { context.unregisterReceiver(it) }; volReceiver = null }
        main.post { dismissSlider() }
        if (appCanSu && mode == MODE_ALWAYS) {
            Thread { runCatching { Su.run("wm overscan 0,0,0,0") } }.start()
        }
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
        // AudioManager.VOLUME_CHANGED_ACTION is @hide — use the string literal directly.
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"

        const val MODE_OFF = "Off"
        const val MODE_ALWAYS = "Always on"
        const val MODE_SWIPE = "Swipe reveal"
        val MODES = listOf(MODE_OFF, MODE_ALWAYS, MODE_SWIPE)

        private const val BAR_HEIGHT_DP = 56   // taller so the icons read at a glance
        private const val STRIP_HEIGHT_DP = 48   // swipe-reveal capture zone — 12→28→48: a fast off-screen
        // swipe-up's DOWN sometimes landed above a thinner strip, so the dashboard caught it and scrolled
        private const val SWIPE_MIN_DP = 20     // upward travel (dp) before a strip touch counts as a swipe
        private const val FEEDBACK_MIN_MS = 350L  // min press-highlight visible time (bridges su latency)
        private const val ICON_SIZE_DP = 30    // fixed icon size, centred in its cell (uniform across all buttons)
        private const val TIGHTEN = 0.65f      // triple-member cell weight vs nav's 1.0 → ~35% tighter spacing
        private val BAR_BG = 0xC2282C34.toInt() // charcoal @ ~76% — translucent but still reads solid
        private val PRESS_TINT = 0x55FFFFFF.toInt() // press-feedback flash (~33% white)
        private const val AUTO_HIDE_MS = 5000L  // +25% over 4000 — 4s felt too brief in use
        private const val ANIM_MS = 220L       // swipe-reveal slide in/out duration
        private const val VALUE_WIDTH_THRESHOLD_DP = 600 // wide panels (TPA10) get the % readout; square NSPanels don't
        private const val VALUE_LABEL_MIN_W_DP = 42
        private const val BRIGHT_STEP = 32     // brightness (0–255) per tap ≈ 12.5%
        private const val REPEAT_DELAY_MS = 400L     // hold this long before ramping starts
        private const val REPEAT_INTERVAL_MS = 120L  // then step every this often while held
        private val SEP_COLOR = 0x99FFFFFF.toInt()   // 60% white group divider — clearly visible
        private const val SEP_WIDTH_DP = 2
        private const val SEP_HEIGHT_DP = 30
        private const val SEP_MARGIN_DP = 7          // gap each side of the group divider

        // Narrow-panel pop-up slider (brightness/volume).
        private const val SLIDER_HEIGHT_DP = 170     // total pop-up height above the bar
        private const val SLIDER_TRACK_W_DP = 14     // the draggable track width
        private const val SLIDER_PAD_DP = 10         // padding inside the rounded pop-up
        private const val SLIDER_GAP_DP = 6          // gap between the pop-up and the bar's top edge
        private const val SLIDER_RADIUS_DP = 14
        private const val SLIDER_IDLE_MS = 3000L     // auto-close after this long with no interaction
        private val SLIDER_TRACK_COLOR = 0x55FFFFFF.toInt() // unfilled track (~33% white)
    }
}
