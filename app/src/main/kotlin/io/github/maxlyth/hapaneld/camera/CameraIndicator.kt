package io.github.maxlyth.hapaneld.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import io.github.maxlyth.hapaneld.control.LedEffectController
import io.github.maxlyth.hapaneld.control.Su
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Decides whether the room is being told the camera is on. Pure so the rule is a unit test: the overlay
 * alone is positive only while the display is lit; once the screen is intended off the overlay sits at
 * the never-blank floor where it is illegible, so only a lit LED counts.
 */
object CameraIndicationPolicy {
    fun positive(overlayAttached: Boolean, screenOff: Boolean, ledLit: Boolean): Boolean =
        overlayAttached && (!screenOff || ledLit)

    fun route(overlayAttached: Boolean, ledLit: Boolean): CameraIndication = when {
        ledLit -> CameraIndication.LED
        overlayAttached -> CameraIndication.OVERLAY
        else -> CameraIndication.NONE
    }
}

/**
 * Where the camera light is drawn, kept pure so the shape is a unit test rather than something only a
 * screenshot can check.
 *
 * Both camera-bearing panels put the lens at the **top centre** of the bezel, so a light in a corner
 * points at nothing. The indicator is the lowest third of a circle whose centre sits above the screen
 * edge: on screen it reads as an arc curving under the lens, flush to the bezel. A margin here would
 * detach the arc from the bezel and lose that, so there is none.
 */
object CameraIndicatorGeometry {
    /**
     * Height of the dashboard's own tab bar, in screen pixels. The indicator is sized against on-screen
     * content rather than in dp, because it has to look right *next to that bar* — a dp size would drift
     * away from it as soon as the two panels' densities differ, which they do (226 and 212).
     */
    const val TAB_BAR_PX = 56

    /** Margin taken off the circle so the arc does not crowd the bar it is measured against. */
    const val MARGIN_PX = 8

    /** Full circle diameter: three tab bars, less the margin. Only its lowest third is on-screen. */
    const val DIAMETER_PX = TAB_BAR_PX * 3 - MARGIN_PX

    /** Fraction of the circle's height that is visible below the screen edge. */
    const val VISIBLE_FRACTION = 0.33f

    val radiusPx: Float get() = DIAMETER_PX / 2f

    /** Overlay window width — the circle's full width. */
    val windowWidthPx: Int get() = DIAMETER_PX

    /** Overlay window height — the visible band, so the arc meets the screen edge exactly. */
    val windowHeightPx: Int get() = Math.round(DIAMETER_PX * VISIBLE_FRACTION)

    /** Circle centre within the window, horizontally centred. */
    fun centreX(widthPx: Int): Float = widthPx / 2f

    /**
     * Circle centre's y within the window — negative, i.e. above the screen edge, by however much of
     * the circle is hidden. Showing the bottom [VISIBLE_FRACTION] means hiding `r - visible` of it.
     */
    fun centreY(): Float = -(radiusPx - DIAMETER_PX * VISIBLE_FRACTION)

    /** Radius inset by half the stroke so the outline is not clipped by the window edge. */
    fun radius(strokePx: Float): Float = radiusPx - strokePx / 2f
}

/**
 * How the camera light moves. Two levels, stepped, once a second — deliberately not an animator.
 *
 * Cost is the whole argument. Measured on a live panel, the compositor spends about 6.8 ms of CPU per
 * composited frame, so a stepped two-level pulse costs two layer updates a second — under 1.4% of one
 * core in the worst case, and effectively nothing while the dashboard is already compositing. Driving
 * the same effect with a `ValueAnimator` on alpha would redraw at the display refresh rate whether or
 * not the value visibly changed, which measures around 41% of a core: an order of magnitude too much
 * for something that only decorates a warning light. The project's ceiling for that kind of work is
 * 5% of a core, and only the stepped form is inside it.
 *
 * [DIM] is a visible level rather than transparent on purpose. A hard blink costs exactly the same but
 * leaves nothing on screen for half of every second, and the privacy contract already refuses an
 * indication that presents as invisible sub-second blinks.
 */
object CameraIndicatorPulse {
    const val PERIOD_MS = 1_000L
    const val STEP_MS = PERIOD_MS / 2
    const val BRIGHT = 1.0f
    const val DIM = 0.42f

    /** The two levels, so a test can assert the dim one is still visible rather than off. */
    fun alphaFor(bright: Boolean): Float = if (bright) BRIGHT else DIM

    /** Alpha at a given step index, so the sequence itself is testable without a Handler. */
    fun alphaAtStep(step: Long): Float = alphaFor(step % 2L == 0L)
}

/**
 * The camera-in-use light the room can see. In code:
 *
 * - An always-on-top, non-touchable overlay centred under the camera, drawn with the same window type
 *   the navigation-bar and kiosk overlays use, so page content cannot cover it and a tap never hits it.
 *   Its shape and placement are [CameraIndicatorGeometry].
 * - [show] returns only once the window is confirmed attached on the main thread AND the indication is
 *   positive for the current screen state; false means the owner must not open the camera.
 * - [refresh] is the continuing prerequisite: the owner calls it on every watchdog tick and closes the
 *   session when it returns false. While the screen is intended off the display is dark or at the
 *   never-blank floor, so the indication moves to the status LED through a [LedEffectController.Hold];
 *   if that hold cannot be taken or lit, the indication is negative and capture stops.
 * - [hide] keeps the light on for a minimum hold so rapid snapshot polling reads as one continuous
 *   indication; a [show] inside the hold cancels it. Releasing the LED never restores from a snapshot:
 *   [restoreLed] re-derives it from persisted intent, and that work runs on a worker thread because the
 *   LED HAL blocks on every write.
 */
class CameraIndicator(
    private val context: Context,
    private val ledEffect: LedEffectController,
    private val restoreLed: () -> Unit,
    /** True while the screen is intended off, including the never-blank dim floor. */
    private val screenOff: () -> Boolean,
    private val holdAfterCloseMs: Long = HOLD_AFTER_CLOSE_MS,
) {
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var view: View? = null
    private var generation = 0L
    private var ledHold: LedEffectController.Hold? = null
    private var ledLit = false
    private var pulseStep = 0L
    /**
     * One step of the pulse, re-posting itself. It stops by returning without re-posting once the view
     * is gone, so a missed [stopPulse] cannot leave it running against a detached window.
     *
     * It keeps running while the screen is intended off, and that is a deliberate choice rather than an
     * oversight: pausing it would need its own resume path on every route back, and two layer updates a
     * second sits far inside the 5% budget even on a dark panel. The window only exists while a camera
     * session does, which bounds it.
     */
    private val pulse = object : Runnable {
        override fun run() {
            synchronized(lock) {
                val current = view ?: return
                current.alpha = CameraIndicatorPulse.alphaAtStep(pulseStep++)
            }
            main.postDelayed(this, CameraIndicatorPulse.STEP_MS)
        }
    }

    /** Start the pulse if it is not already running. Main thread only. */
    private fun startPulse() {
        main.removeCallbacks(pulse)
        pulseStep = 0L
        main.post(pulse)
    }

    /** Stop the pulse and leave the light fully on, so a stopped pulse never dims the indication. */
    private fun stopPulse() {
        main.removeCallbacks(pulse)
        synchronized(lock) { view }?.alpha = CameraIndicatorPulse.BRIGHT
    }

    fun route(): CameraIndication = synchronized(lock) { CameraIndicationPolicy.route(view != null, ledLit) }

    /** Attach the overlay and confirm the indication is positive; false means the camera must not open. */
    fun show(): Boolean {
        synchronized(lock) { generation++ }
        if (!ensureOverlayPermission()) {
            Log.w(TAG, "overlay permission missing; refusing to open the camera")
            return false
        }
        var attached = false
        val ran = onMain {
            synchronized(lock) {
                if (view != null) {
                    attached = true
                    return@onMain
                }
                val candidate = IndicatorView(context)
                val params = WindowManager.LayoutParams(
                    CameraIndicatorGeometry.windowWidthPx,
                    CameraIndicatorGeometry.windowHeightPx,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    // Under the lens, touching the bezel: both camera panels centre the camera on the
                    // top edge, and any margin here would float the arc away from it.
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    x = 0
                    y = 0
                }
                attached = runCatching {
                    wm.addView(candidate, params)
                    view = candidate
                    startPulse()
                    true
                }.onFailure { Log.w(TAG, "indicator addView failed: ${it.javaClass.simpleName}") }
                    .getOrDefault(false)
            }
        }
        if (!ran || !attached) return false
        return refresh()
    }

    /**
     * Move the indication between the overlay and the LED to match the screen state, and report whether
     * the room is being told. Blocking LED work; never call on the main thread.
     */
    fun refresh(): Boolean {
        val dark = screenOff()
        synchronized(lock) {
            if (view == null) return false
            if (dark && ledHold == null) {
                val hold = ledEffect.hold()
                if (hold == null) {
                    Log.w(TAG, "screen is off and the LED is unavailable; indication is negative")
                } else if (hold.setSolid(255, 0, 0)) {
                    ledHold = hold
                    ledLit = true
                } else {
                    hold.close()
                    Log.w(TAG, "screen is off and the LED would not light; indication is negative")
                }
            } else if (dark && ledHold != null && !ledLit) {
                // A hold whose write failed earlier: try once more rather than sit negative for ever.
                ledLit = ledHold?.setSolid(255, 0, 0) == true
            } else if (!dark && ledHold != null) {
                releaseLedLocked()
            }
            return CameraIndicationPolicy.positive(overlayAttached = true, screenOff = dark, ledLit = ledLit)
        }
    }

    /** Keep the light on for the minimum hold, then take everything down. */
    fun hide() {
        val token = synchronized(lock) { ++generation }
        main.postDelayed({
            val removeLed: Boolean
            synchronized(lock) {
                if (generation != token) return@postDelayed
                main.removeCallbacks(pulse)
                view?.let { runCatching { wm.removeView(it) } }
                view = null
                removeLed = ledHold != null
            }
            // The LED HAL blocks on every write; never restore it from the main looper.
            if (removeLed) offMain { synchronized(lock) { if (generation == token) releaseLedLocked() } }
        }, holdAfterCloseMs)
    }

    /** Teardown: no hold, no delay. Safe from any thread. */
    fun forceHide() {
        synchronized(lock) { generation++ }
        onMain {
            synchronized(lock) {
                main.removeCallbacks(pulse)
                view?.let { runCatching { wm.removeView(it) } }
                view = null
            }
        }
        val onMainNow = Looper.myLooper() == Looper.getMainLooper()
        if (onMainNow) offMain { synchronized(lock) { releaseLedLocked() } }
        else synchronized(lock) { releaseLedLocked() }
    }

    private fun releaseLedLocked() {
        val hold = ledHold ?: return
        ledHold = null
        ledLit = false
        hold.close()
        runCatching { restoreLed() }.onFailure { Log.w(TAG, "LED restore failed: ${it.javaClass.simpleName}") }
    }

    private fun offMain(action: () -> Unit) {
        Thread({ runCatching(action).onFailure { Log.w(TAG, "LED work failed: ${it.javaClass.simpleName}") } }, "camera-light-led").start()
    }

    private fun ensureOverlayPermission(): Boolean {
        if (canDraw()) return true
        // The navbar grants itself the same way on rooted panels; without root this stays false.
        Su.run("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        return canDraw()
    }

    private fun canDraw(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun onMain(action: () -> Unit): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
            return true
        }
        val done = CountDownLatch(1)
        if (!main.post {
                try {
                    action()
                } finally {
                    done.countDown()
                }
            }
        ) return false
        return try {
            done.await(MAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /** A red disc with a white ring: legible at walking-past distance, unmistakable as "recording". */
    private class IndicatorView(context: Context) : View(context) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(220, 30, 30) }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f * context.resources.displayMetrics.density
        }

        override fun onDraw(canvas: Canvas) {
            // A whole circle whose centre sits above the screen edge, so the window clips everything
            // but its lowest third — the arc that curves under the lens.
            val cx = CameraIndicatorGeometry.centreX(width)
            val cy = CameraIndicatorGeometry.centreY()
            val r = CameraIndicatorGeometry.radius(ring.strokeWidth)
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r, ring)
        }
    }

    companion object {
        private const val TAG = "ha-paneld/camera-light"
        private const val MAIN_TIMEOUT_MS = 1_000L
        const val HOLD_AFTER_CLOSE_MS = 3_000L
    }
}
