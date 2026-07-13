package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import io.github.maxlyth.hapaneld.platform.WakeTap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Touch-to-wake over a 1 px `FLAG_WATCH_OUTSIDE_TOUCH` overlay — the same non-consuming pattern as
 * [TouchSoundController]. While the screen is off, the overlay receives `ACTION_OUTSIDE` for a tap
 * anywhere on screen WITHOUT consuming it (the tap still reaches the dashboard) and re-lights the panel.
 *
 * Non-consuming is deliberate and load-bearing: the wake mechanism can never itself block touch, so it
 * can't become the unresponsive-panel it exists to prevent. Worst case if it misbehaves is a stray tap
 * reaching the dashboard — never a dead screen.
 *
 * Needs `SYSTEM_ALERT_WINDOW` (root-granted for the navbar). [canArm] reports whether it's held so
 * ScreenController can degrade a screen-off to a dim when there'd otherwise be no guaranteed local wake.
 */
class OverlayWakeTap(context: Context) : WakeTap {
    private val ctx = context.applicationContext
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var view: View? = null
    private val generation = AtomicLong()

    override fun canArm(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(ctx)

    @Suppress("ClickableViewAccessibility")
    override fun arm(onTap: () -> Unit): Boolean {
        if (!canArm()) return false
        val token = generation.incrementAndGet()
        val installed = onMain(token, invalidateOnTimeout = true) {
            removeCurrent()
            val fired = AtomicBoolean()
            val v = View(ctx)
            v.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE && fired.compareAndSet(false, true)) {
                    Log.d(TAG, "tap while dark -> wake")
                    // Wake off the main thread: onTap calls Su.run()/daemon I/O (ANR risk on the looper).
                    Thread({
                        runCatching { onTap() }.onFailure {
                            fired.set(false) // an exceptional wake must leave the next tap able to retry
                            Log.w(TAG, "wake onTap failed: ${it.message}")
                        }
                    }, "screen-wake-tap").start()
                }
                false // never consume — the tap still reaches the dashboard
            }
            val lp = WindowManager.LayoutParams(
                1, 1, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START }
            val added = runCatching { wm.addView(v, lp) }
                .onFailure { Log.w(TAG, "wake overlay addView failed: ${it.message}") }
                .isSuccess
            if (!added) return@onMain false
            if (generation.get() != token) {
                runCatching { wm.removeView(v) }
                return@onMain false
            }
            view = v
            true
        }
        if (!installed) Log.w(TAG, "wake overlay was not confirmed; refusing a true screen-off")
        return installed
    }

    override fun disarm() {
        val token = generation.incrementAndGet()
        onMain(token, invalidateOnTimeout = false) {
            removeCurrent()
            true
        }
    }

    private fun removeCurrent() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    /** Serialize WindowManager ownership on the main looper while giving screen commands a bounded answer. */
    private fun onMain(token: Long, invalidateOnTimeout: Boolean, action: () -> Boolean): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return if (generation.get() == token) action() else false
        }
        val done = CountDownLatch(1)
        val result = AtomicBoolean()
        if (!main.post {
                try {
                    if (generation.get() == token) result.set(action())
                } finally {
                    done.countDown()
                }
            }
        ) return false
        val completed = try {
            done.await(MAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!completed && invalidateOnTimeout && generation.compareAndSet(token, token + 1)) {
            // The main action may have attached immediately before the timeout won the race. Remove only
            // this invalidated generation; a newer arm/disarm owns the view once it advances the token.
            main.post {
                if (generation.get() == token + 1) removeCurrent()
            }
        }
        return completed && result.get()
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    companion object {
        private const val TAG = "ha-paneld/wake"
        private const val MAIN_TIMEOUT_MS = 1_000L
    }
}
