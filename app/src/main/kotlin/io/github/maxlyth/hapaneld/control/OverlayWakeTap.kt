package io.github.maxlyth.hapaneld.control

import android.content.Context
import android.util.Log
import io.github.maxlyth.hapaneld.platform.WakeTap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Touch-to-wake through [PanelTouchObserver]'s shared 1 px `FLAG_WATCH_OUTSIDE_TOUCH` overlay. While the
 * screen is off, the observer receives `ACTION_OUTSIDE` for a tap without consuming it (the tap still
 * reaches the dashboard) and re-lights the panel.
 *
 * Non-consuming is deliberate and load-bearing: the wake mechanism can never itself block touch, so it
 * can't become the unresponsive-panel it exists to prevent. Worst case if it misbehaves is a stray tap
 * reaching the dashboard — never a dead screen.
 *
 * Needs `SYSTEM_ALERT_WINDOW` (root-granted for the navbar). [canArm] reports whether it's held so
 * ScreenController can degrade a screen-off to a dim when there'd otherwise be no guaranteed local wake.
 */
class OverlayWakeTap(
    context: Context,
    private val observer: PanelTouchObserver = PanelTouchObserver.shared(context),
) : WakeTap {
    @Volatile private var subscription: PanelTouchObserver.Subscription? = null
    private val generation = AtomicLong()

    override fun canArm(): Boolean = observer.canObserve()

    @Synchronized
    override fun arm(onTap: () -> Unit): Boolean {
        if (!canArm()) return false
        val token = generation.incrementAndGet()
        subscription?.close()
        subscription = null
        val fired = AtomicBoolean()
        val installed = observer.subscribe {
            if (generation.get() == token && fired.compareAndSet(false, true)) {
                Log.d(TAG, "tap while dark -> wake")
                // Screen wake can call helper/root I/O; never run it on the main touch callback.
                Thread({
                    runCatching { onTap() }.onFailure {
                        fired.set(false) // an exceptional wake must leave the next tap able to retry
                        Log.w(TAG, "wake onTap failed: ${it.message}")
                    }
                }, "screen-wake-tap").start()
            }
        }
        if (installed == null || generation.get() != token) {
            installed?.close()
            Log.w(TAG, "wake overlay was not confirmed; refusing a true screen-off")
            return false
        }
        subscription = installed
        return true
    }

    @Synchronized
    override fun disarm() {
        generation.incrementAndGet()
        subscription?.close()
        subscription = null
    }

    companion object {
        private const val TAG = "ha-paneld/wake"
    }
}
