package io.github.maxlyth.hapaneld.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import io.github.maxlyth.hapaneld.platform.AccessibilityActions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional hardware-button capture. Hardware/capacitive buttons that emit standard Android
 * KeyEvents are reported to HA via [ButtonBus] → MQTT `event.<panel>_button`. Enabled at
 * provisioning with `settings put secure enabled_accessibility_services <pkg>/<this>`.
 *
 * ⚠️ Per-hardware uncertain: panels whose buttons are GPIO-only (no KeyEvent) won't surface here
 * and would need a vendor GPIO HAL (load-if-present), not this service. Needs on-device validation.
 * Requires `canRequestFilterKeyEvents` + `flagRequestFilterKeyEvents` in the a11y config XML.
 */
class PanelAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val name = KeyEvent.keyCodeToString(event.keyCode)
            Log.d(TAG, "key down: $name")
            ButtonBus.emit(name)
        }
        // Do not consume — let the system handle the key normally.
        return super.onKeyEvent(event)
    }

    companion object : AccessibilityActions {
        private const val TAG = "ha-paneld/buttons"

        // Set while the a11y service is connected; lets the MQTT command path perform global nav
        // actions (back/recents/home) with no root — performGlobalAction is an a11y capability.
        @Volatile
        private var instance: PanelAccessibilityService? = null

        override fun back(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false
        override fun recents(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_RECENTS) ?: false
        fun navHome(): Boolean = instance?.performGlobalAction(GLOBAL_ACTION_HOME) ?: false

        /**
         * Dispatch a short accessibility gesture and wait for its completion when called off-main, as
         * all production callers are. Completion matters to the navbar overlay: it stays non-touchable
         * until the injected tap has landed, avoiding a feedback loop into its own edge strip.
         */
        override fun tap(x: Int, y: Int): Boolean {
            val service = instance ?: return false
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 1))
                .build()
            val completed = AtomicBoolean(false)
            val done = CountDownLatch(1)
            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completed.set(true)
                    done.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    done.countDown()
                }
            }
            val handler = Handler(Looper.getMainLooper())
            val accepted = runCatching { service.dispatchGesture(gesture, callback, handler) }
                .getOrDefault(false)
            if (!accepted) return false
            if (Looper.myLooper() == Looper.getMainLooper()) return true
            return runCatching { done.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS) && completed.get() }
                .getOrDefault(false)
        }

        private const val GESTURE_TIMEOUT_MS = 1000L
    }
}
