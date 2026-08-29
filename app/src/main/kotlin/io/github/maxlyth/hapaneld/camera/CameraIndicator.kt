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
 * The camera-in-use light the room can see. In code:
 *
 * - An always-on-top, non-touchable overlay in a fixed corner, drawn with the same window type the
 *   navigation-bar and kiosk overlays use, so page content cannot cover it and a tap never hits it.
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
                    dp(SIZE_DP),
                    dp(SIZE_DP),
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    x = dp(MARGIN_DP)
                    y = dp(MARGIN_DP)
                }
                attached = runCatching {
                    wm.addView(candidate, params)
                    view = candidate
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
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(cx, cy) - ring.strokeWidth
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r, ring)
        }
    }

    companion object {
        private const val TAG = "ha-paneld/camera-light"
        private const val SIZE_DP = 28
        private const val MARGIN_DP = 10
        private const val MAIN_TIMEOUT_MS = 1_000L
        const val HOLD_AFTER_CLOSE_MS = 3_000L
    }
}
