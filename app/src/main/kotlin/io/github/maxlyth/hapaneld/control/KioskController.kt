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
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import io.github.maxlyth.hapaneld.metrics.FeatureCostOutcome
import io.github.maxlyth.hapaneld.metrics.FeatureCosts
import io.github.maxlyth.hapaneld.platform.RootShell
import java.util.concurrent.atomic.AtomicLong

/**
 * **Experimental kiosk lock.** Stops a NON-admin from ACCIDENTALLY navigating away from the dashboard on
 * an open/generic panel (no vendor lockdown). It is deliberately NOT an anti-adversary / jailbreak guard —
 * the design goal is "casual users can't wander off" with **no way to brick the panel and no way for an
 * admin to get locked out.**
 *
 * All RUNTIME-ONLY (no device-owner, no persistent system state), so a reboot clears everything and nothing
 * can brick the panel. Three layers (root, via ha-paneld's existing [Su] pattern):
 *  - **Aggressive return loop (the reliable, universal one)** — while locked, poll the dashboard's
 *    foreground state ([SystemController.dashboardState]) and, the moment it's backgrounded (Recents / any
 *    other app), re-launch it ([SystemController.launchHome]). A casual user physically CAN'T stay away —
 *    they're pulled back within ~[RETURN_POLL_MS]. This is what actually enforces the lock, because on some
 *    OEM SystemUIs the nav-button disable below is ignored.
 *  - `cmd statusbar disable-for-setup true` — disables HOME/RECENT/shade where the OEM honours it (verified
 *    IGNORED on rk3576/Android-14, hence the return loop; a harmless no-op where ignored).
 *  - `policy_control immersive.full=*` — HIDE both bars for a clean full-screen look. STICKY immersive, so a
 *    deliberate swipe still briefly flashes them; on such a panel the return loop is what stops an escape.
 *
 * Escapes, layered so no single failure strands an admin: the `:8888` toggle (any LAN browser), the HA
 * switch, adb, a **reboot** (clears the runtime lock; the service re-asserts only after a delay, so every
 * power-cycle gives an unlocked window), and an **on-device unlock gesture** — [UNLOCK_TAPS] rapid taps in
 * the top-left corner, network-independent, casual-proof — which fires [onUnlockRequested].
 */
class KioskController(
    context: Context,
    private val system: SystemController,
    private val config: Config,
    private val root: RootShell = Su,
) {
    private val ctx = context.applicationContext
    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var unlockView: View? = null
    private var taps = 0
    private var lastTapAt = 0L
    @Volatile private var locked = false
    @Volatile private var returnThread: Thread? = null
    private val returnGeneration = AtomicLong()
    private val applyLock = Any()

    /** Fired when the on-device unlock gesture completes. The service wires this to persist OFF + publish
     *  to HA + call [apply]`(false)` — all off the main thread. */
    @Volatile var onUnlockRequested: (() -> Unit)? = null

    /** Apply or clear the lock. Runs the root commands on the CALLING thread (blocking) — call it off the
     *  main thread (the MQTT thread, a boot coroutine, or the service's unlock thread); the overlay work is
     *  posted to the main thread internally. */
    fun apply(on: Boolean) = synchronized(applyLock) {
        if (on) {
            root.run("cmd statusbar disable-for-setup true")
            root.run("settings put global policy_control 'immersive.full=*'")
            // apply() is explicitly off-main; grant before posting view-only overlay work so a slow or
            // wedged root shell can never block the Android UI looper.
            ensureOverlayPerm()
            showUnlockCorner()
            locked = true
            startReturnLoop()
            Log.i(TAG, "kiosk lock ON")
        } else {
            // Revoke the poll generation before any blocking root cleanup. A dashboard-state call
            // already in flight must not observe a later ON and revive itself or launch stale work.
            locked = false
            stopReturnLoop()
            root.run("cmd statusbar disable-for-setup false")
            root.run("settings delete global policy_control")
            hideUnlockCorner()
            Log.i(TAG, "kiosk lock OFF")
        }
    }

    /** While locked, snap the dashboard back to the foreground the instant the user lands elsewhere
     *  (Recents / another app). This is the mechanism that actually enforces the lock on OEMs that ignore
     *  the nav-button disable. Reuses the watchdog's foreground probe; a no-op when it reads UNKNOWN
     *  (no root/daemon). Leaves ha-paneld's own admin UI alone so the unlock paths aren't fought. */
    private fun startReturnLoop(): Unit = synchronized(this) {
        if (!locked || returnThread?.isAlive == true) return
        val generation = returnGeneration.incrementAndGet()
        lateinit var worker: Thread
        worker = Thread {
            try {
                while (returnGeneration.isCurrent(generation, locked)) {
                    val cost = FeatureCosts.registry.beginSynchronous(FeatureCostOperation.KIOSK_STATE_POLL)
                    var outcome = FeatureCostOutcome.SUCCESS
                    try {
                        val pkg = config.dashboardPackage
                        val state = system.dashboardState(pkg)
                        if (!returnGeneration.isCurrent(generation, locked)) {
                            outcome = FeatureCostOutcome.CANCELLED
                            break
                        }
                        if (state == AppState.BG) {
                            Log.i(TAG, "left the dashboard while locked -> returning to it")
                            system.launchHome(pkg)
                        }
                    } catch (e: Exception) {
                        outcome = FeatureCostOutcome.FAILURE
                    } finally {
                        FeatureCosts.registry.finishSynchronous(
                            FeatureCostOperation.KIOSK_STATE_POLL,
                            cost,
                            outcome = outcome,
                            workUnits = 1,
                        )
                    }
                    if (!returnGeneration.isCurrent(generation, locked)) break
                    try { Thread.sleep(RETURN_POLL_MS) } catch (e: InterruptedException) { break }
                }
            } finally {
                synchronized(this@KioskController) {
                    if (returnThread === worker) {
                        returnThread = null
                        // If OFF→ON happened while an old blocking probe was unwinding, restore one
                        // current poller only after the obsolete thread has fully terminated.
                        if (locked) startReturnLoop()
                    }
                }
            }
        }.apply { isDaemon = true; name = "kiosk-return" }
        returnThread = worker
        worker.start()
    }

    private fun stopReturnLoop() {
        val old = synchronized(this) {
            returnGeneration.incrementAndGet()
            returnThread?.also(Thread::interrupt)
        }
        if (old != null && old !== Thread.currentThread()) {
            runCatching { old.join(RETURN_STOP_JOIN_MS) }
        }
    }

    /** Ensure SYSTEM_ALERT_WINDOW (root-granted; already held for the navbar/touch-sound, granted here
     *  defensively in case this is the first overlay on the panel). */
    private fun ensureOverlayPerm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) {
            root.run("appops set ${ctx.packageName} SYSTEM_ALERT_WINDOW allow")
        }
    }

    @Suppress("ClickableViewAccessibility")
    private fun showUnlockCorner() = main.post {
        if (unlockView != null) return@post
        val v = View(ctx)
        v.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) countTap()
            true // consume: this small corner is a dedicated unlock zone
        }
        val size = (UNLOCK_DP * ctx.resources.displayMetrics.density).toInt()
        val lp = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching { wm.addView(v, lp); unlockView = v }
            .onFailure { Log.w(TAG, "unlock overlay addView failed: ${it.message}") }
    }

    private fun hideUnlockCorner() = main.post {
        unlockView?.let { runCatching { wm.removeView(it) } }
        unlockView = null
        taps = 0
    }

    /** Count a tap in the unlock corner; [UNLOCK_TAPS] within [TAP_GAP_MS] of each other fire the unlock. */
    private fun countTap() {
        val now = System.currentTimeMillis()
        if (now - lastTapAt > TAP_GAP_MS) taps = 0
        lastTapAt = now
        if (++taps >= UNLOCK_TAPS) {
            taps = 0
            Log.i(TAG, "kiosk unlock gesture")
            onUnlockRequested?.invoke()
        }
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    companion object {
        private const val TAG = "ha-paneld/kiosk"
        private const val UNLOCK_TAPS = 7    // rapid taps in the corner to release (casual-proof)
        private const val TAP_GAP_MS = 1_500L // max gap between taps before the count resets
        private const val UNLOCK_DP = 48     // corner unlock-zone size (a small dead zone while locked)
        internal const val RETURN_POLL_MS = 3_000L // experimental return loop; bounds steady privileged probes
        internal const val RETURN_STOP_JOIN_MS = 6_000L
    }
}

internal fun AtomicLong.isCurrent(generation: Long, enabled: Boolean): Boolean =
    enabled && get() == generation
