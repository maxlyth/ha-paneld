package io.github.maxlyth.hapaneld.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 * points at nothing. The indicator is the visible bottom of a circle **centred on the lens itself**: on
 * screen it reads as an arc curving under the camera, flush to the bezel. A margin here would detach the
 * arc from the bezel and lose that, so there is none.
 *
 * Where the lens is, is a per-board measurement (`hardware.camera_lens_offset_px`), not something the
 * app can infer — the two camera panels differ by 20 px.
 */
object CameraIndicatorGeometry {
    /**
     * Height of the visible arc, in screen pixels — what the room actually sees below the screen edge.
     * This is the design choice; the circle's size follows from it and from where the lens is.
     */
    const val VISIBLE_BAND_PX = 53

    /**
     * Fallback for a profile that has not measured its lens. Deliberately the value the first version of
     * this indicator assumed implicitly, so an unmeasured panel is no worse off than before.
     */
    const val DEFAULT_LENS_OFFSET_PX = 27

    /**
     * Radius: far enough that the circle's centre lands on the lens while its bottom stays
     * [VISIBLE_BAND_PX] below the screen edge. A higher lens means a bigger, flatter arc.
     *
     * Note what this trades away. The first version fixed the diameter at three dashboard headers less a
     * margin, and *derived* the centre from a visible fraction — which silently asserted the lens was 27
     * px above the active area. It is not: measured from photographs of both panels, it is 63 px on the
     * TPA10 and 43 px on the WF1589T. Diameter, lens-centring and band height are three constraints on
     * two degrees of freedom, so one had to give; the fixed diameter is the one that was never observable
     * from the room, while the other two are exactly what a person looking at the panel judges.
     */
    fun radiusPx(lensOffsetPx: Int): Float = (lensOffsetPx + VISIBLE_BAND_PX).toFloat()

    /** Overlay window width — the circle's full width. */
    fun windowWidthPx(lensOffsetPx: Int): Int = Math.round(radiusPx(lensOffsetPx) * 2f)

    /** Overlay window height — the visible band, so the arc meets the screen edge exactly. */
    val windowHeightPx: Int get() = VISIBLE_BAND_PX

    /** Circle centre within the window, horizontally centred. */
    fun centreX(widthPx: Int): Float = widthPx / 2f

    /**
     * Circle centre's y within the window: negative by exactly the lens offset, which is what puts the
     * centre *on the lens* rather than somewhere that happens to look close.
     */
    fun centreY(lensOffsetPx: Int): Float = -lensOffsetPx.toFloat()

    /** Radius inset by half the stroke so the outline is not clipped by the window edge. */
    fun radius(lensOffsetPx: Int, strokePx: Float): Float = radiusPx(lensOffsetPx) - strokePx / 2f

    /** A profile's measurement, or the fallback when it has none. */
    fun lensOffsetOrDefault(profileValue: Int?): Int =
        profileValue?.takeIf { it > 0 } ?: DEFAULT_LENS_OFFSET_PX
}

/**
 * How the camera light moves during the session's first seconds, before it begins to back off.
 *
 * Two levels, stepped, once a second - deliberately not an animator. Cost is the whole argument, and
 * duty cycle is why it matters here: this light can be up for as long as a camera session lasts, so
 * whatever it costs, it costs continuously. Measured on a live panel, the compositor spends about
 * 6.8 ms of CPU per composited frame, so a stepped two-level pulse costs two layer updates a second -
 * under 1.4% of one core in the worst case, and effectively nothing while the dashboard is already
 * compositing. Driving the same effect with a `ValueAnimator` on alpha would redraw at the display
 * refresh rate whether or not the value visibly changed, which measures around 41% of a core. For an
 * effect that ends in a second that might be a fair trade; for one that may run for hours beside a
 * rendering dashboard it is not.
 *
 * [DIM] is a visible level rather than transparent on purpose. A hard blink costs exactly the same but
 * leaves nothing on screen for half of every second, and the privacy contract already refuses an
 * indication that presents as invisible sub-second blinks.
 *
 * These are the values the session *starts* at; [CameraIndicatorAttenuation] carries it on from here.
 */
object CameraIndicatorPulse {
    const val PERIOD_MS = 1_000L
    const val STEP_MS = PERIOD_MS / 2
    const val BRIGHT = 1.0f
    const val DIM = 0.42f

    /** The two levels, so a test can assert the dim one is still visible rather than off. */
    fun alphaFor(bright: Boolean): Float = if (bright) BRIGHT else DIM
}

/**
 * How the camera light backs off once a session keeps running, and the reason it is allowed to.
 *
 * The all-or-nothing presentation this replaces was right for the case the privacy contract is written
 * around - a camera that opens when nobody expected it - and wrong for the case the trial produced,
 * where somebody deliberately leaves a stream or a motion consumer running and a light at full
 * prominence sits on their dashboard for hours. Those are the same situation differing only in how long
 * the session has been running, so elapsed session time is the only thing this schedule is a function
 * of. Not the consumer, not the setting, not anything the dashboard can reach.
 *
 * It backs off in two ways at once, and the asymmetry is the whole design:
 *
 * - **The lit part keeps its length** ([LIT_MS]) while the gap after it stretches, from [MIN_GAP_MS] to
 *   [MAX_GAP_MS] - roughly one flash a second to roughly one flash a minute.
 * - **The flash dims only as far as [LIT_FLOOR], and the gap goes all the way to transparent.**
 *
 * [LIT_FLOOR] is the clause the whole design rests on, and it is a floor on *the blip*, not on some
 * level held between blips. What the room gets at rest is a real flash - half a second, at better than
 * half opacity - arriving about once a minute; between flashes the screen is the dashboard and nothing
 * else. The two constants that must never move toward zero are therefore [LIT_MS] and [LIT_FLOOR]: a
 * flash that got shorter as well as rarer, or that faded toward nothing, would end as a blink too brief
 * or too faint to register, and that is the presentation the privacy contract refuses. A stretching gap
 * is not that, because what it stretches is the interval between unmistakable flashes.
 *
 * The gap grows geometrically rather than linearly. Linear growth spends most of the ramp already close
 * to a minute apart; geometric growth stays visibly frequent while somebody might still be reacting to
 * the camera opening, then backs off quickly once nobody is.
 *
 * Cost falls as it attenuates: a settled session issues two layer updates a minute where a prominent one
 * issues two a second, so the longest sessions - the ones this exists for - are the cheapest to indicate.
 */
object CameraIndicatorAttenuation {
    /** Full prominence for this long, so a camera opening unexpectedly is unmistakable while it matters. */
    const val PROMINENT_MS = 30_000L

    /** How long the backing-off takes. Fast enough to stop dominating, slow enough not to read as a fault. */
    const val RAMP_MS = 120_000L

    /** The lit part of every cycle, at every point in the schedule. This is the constant that never moves. */
    const val LIT_MS = CameraIndicatorPulse.STEP_MS

    /** Gap after the flash at full prominence: with [LIT_MS] this is the once-a-second light we start at. */
    const val MIN_GAP_MS = CameraIndicatorPulse.STEP_MS

    /** Gap at rest: with [LIT_MS] the settled cycle is exactly one minute long. */
    const val MAX_GAP_MS = 59_500L

    /**
     * The floor the flash settles to. This is the one opacity here that may never approach zero: at rest
     * the blip is the whole indication, so [LIT_MS] and this constant are what the feature stands on.
     *
     * Both levels *descend from* [CameraIndicatorPulse], which [alphaAt] reads directly rather than
     * restating. Aliases for those two opening values used to sit here and were removed: nothing read
     * them, and the mutation battery proved it by failing to make a change to one matter.
     */
    const val LIT_FLOOR = 0.62f

    /**
     * The level between flashes fades all the way out. A session still opens at the never-blank
     * [CameraIndicatorPulse.DIM], so the first half-minute is unchanged, but as the gap stretches it
     * reaches transparent: a dim arc held for fifty-nine of every sixty seconds is exactly the standing
     * prominence this feature exists to remove.
     */
    const val GAP_FLOOR = 0.0f

    /**
     * How far through the backing-off a session is: 0 while prominent, 1 once settled.
     *
     * The prominent phase needs no branch of its own — before [PROMINENT_MS] the numerator is negative
     * and the clamp already answers 0. An explicit early return was written here first and removed: the
     * mutation battery could not make it matter, which is the definition of a guard that is not doing
     * anything. The clamp is also what makes this fail *prominent* rather than faded if the clock ever
     * runs backwards, so it is load-bearing in both directions.
     */
    fun progressAt(elapsedMs: Long): Float =
        ((elapsedMs - PROMINENT_MS).toFloat() / RAMP_MS).coerceIn(0f, 1f)

    /** Gap after the flash, growing geometrically with progress. */
    fun gapMsAt(elapsedMs: Long): Long {
        val p = progressAt(elapsedMs).toDouble()
        val grown = MIN_GAP_MS.toDouble() * Math.pow(MAX_GAP_MS.toDouble() / MIN_GAP_MS.toDouble(), p)
        return Math.round(grown).coerceIn(MIN_GAP_MS, MAX_GAP_MS)
    }

    /** Alpha for either half of the cycle, descending linearly to its floor and never below it. */
    fun alphaAt(elapsedMs: Long, lit: Boolean): Float {
        val p = progressAt(elapsedMs)
        // Taken from the pulse itself rather than restated, so the first half-minute of a session is the
        // light that shipped before this schedule existed, by construction and not by coincidence.
        val bright = CameraIndicatorPulse.alphaFor(bright = lit)
        val floor = if (lit) LIT_FLOOR else GAP_FLOOR
        return (bright - p * (bright - floor)).coerceIn(floor, bright)
    }

    /** Whole cycle length at a point in the schedule, which is what a person reads as "how often". */
    fun periodMsAt(elapsedMs: Long): Long = LIT_MS + gapMsAt(elapsedMs)

    /** What the light shows next and for how long: the whole visible timeline, as one pure step. */
    data class Step(val lit: Boolean, val alpha: Float, val holdMs: Long)

    /**
     * One step of the cycle. Everything the running indicator decides is here, so the timeline a person
     * would actually watch can be folded out in a test instead of waited for on a panel.
     */
    fun stepAfter(elapsedMs: Long, wasLit: Boolean): Step {
        val lit = !wasLit
        return Step(lit, alphaAt(elapsedMs, lit), if (lit) LIT_MS else gapMsAt(elapsedMs))
    }

    /**
     * Whether the display's screen state changing restores full prominence. Only the return of the
     * display does: the clock measures how long the room has had the light in front of it, and a dark
     * screen has shown it nothing, so time spent dark is not time the indication was on offer. Going
     * dark does not restart anything, because the overlay is not what indicates then — the LED is.
     */
    fun restartsForScreen(wasDark: Boolean, nowDark: Boolean): Boolean = wasDark && !nowDark

    /**
     * Whether a call to open the camera restores full prominence. It always does, including when the
     * overlay is still up from the session that just ended: a reopen inside the post-close hold is a new
     * opening of the camera however close it lands, and so is a reopen after a permission, encoder or
     * device fault. Consumers attaching to a session that is already running never reach this, which is
     * what keeps churn from flashing the light back to full brightness.
     */
    fun restartsForOpen(@Suppress("UNUSED_PARAMETER") alreadyAttached: Boolean): Boolean = true
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
 * - The overlay attenuates over the session on [CameraIndicatorAttenuation]'s fixed schedule, and every
 *   discontinuity restores full prominence: [show] does, so a new hardware session and a reopen after a
 *   permission, encoder or device fault all present as new; the return of the display after screen-off
 *   does, detected in [refresh], because the clock measures how long the room has had the light in front
 *   of it and a dark screen has shown it nothing. Consumers attaching and leaving inside one continuous
 *   session do not reach this class at all, which is what keeps churn from flashing the light bright.
 */
class CameraIndicator(
    private val context: Context,
    private val ledEffect: LedEffectController,
    private val restoreLed: () -> Unit,
    /** True while the screen is intended off, including the never-blank dim floor. */
    private val screenOff: () -> Boolean,
    /** The active profile's measured lens offset in screen px; null falls back to the default. */
    private val cameraLensOffsetPx: Int? = null,
    private val holdAfterCloseMs: Long = HOLD_AFTER_CLOSE_MS,
    /** Monotonic clock for the attenuation schedule; injected so the schedule is testable without waiting. */
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private val lensOffsetPx = CameraIndicatorGeometry.lensOffsetOrDefault(cameraLensOffsetPx)
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var view: View? = null
    private var generation = 0L
    private var ledHold: LedEffectController.Hold? = null
    private var ledLit = false
    /** When the current prominence period started, on [clock]. Every reset moves it forward. */
    private var prominenceStartedAtMs = 0L
    /** Which half of the cycle is showing. The cycle flips it, so the first run after a reset lights it. */
    private var lit = false
    /** The alpha actually on the view, so an unchanged level costs no layer update at all. */
    private var appliedAlpha = Float.NaN
    /** Whether the last [refresh] saw the screen intended off, so the return edge can be detected. */
    private var lastDark = false

    /**
     * One half-cycle, re-posting itself with the delay its own half is due. It stops by returning without
     * re-posting once the view is gone, so a missed cancellation cannot leave it running against a
     * detached window.
     *
     * The two halves are not the same length and the difference grows: the lit half is always
     * [CameraIndicatorAttenuation.LIT_MS], the unlit half is whatever
     * [CameraIndicatorAttenuation.gapMsAt] says for the session's age. That is
     * the whole mechanism — there is no animator, no interpolator and no second timer, and the schedule
     * costs strictly less as it goes on.
     *
     * It keeps running while the screen is intended off, and that is a deliberate choice rather than an
     * oversight: pausing it would need its own resume path on every route back, and by then it is issuing
     * a couple of layer updates a minute anyway. The window only exists while a camera session does,
     * which bounds it. [refresh] restarts it at full prominence when the display comes back.
     */
    private val cycle = object : Runnable {
        override fun run() {
            val delay: Long
            synchronized(lock) {
                val current = view ?: return
                val step = CameraIndicatorAttenuation.stepAfter(clock() - prominenceStartedAtMs, lit)
                lit = step.lit
                if (step.alpha != appliedAlpha) {
                    current.alpha = step.alpha
                    appliedAlpha = step.alpha
                }
                delay = step.holdMs
            }
            main.postDelayed(this, delay)
        }
    }

    /**
     * Put the light back to full prominence and restart the schedule. Safe from any thread, and does its
     * work on the main looper for a reason: cancelling and re-posting from a worker can race a cycle run
     * that is already executing there and leave two schedules running. Posting means the whole reset and
     * every cycle run are ordered on one thread, so there is nothing to interleave.
     */
    private fun resetProminence() {
        main.post {
            main.removeCallbacks(cycle)
            val attached = synchronized(lock) {
                if (view == null) false else {
                    prominenceStartedAtMs = clock()
                    lit = false
                    appliedAlpha = Float.NaN
                    true
                }
            }
            // A freshly added view is already fully opaque, so the first run lighting it is not a flash.
            if (attached) cycle.run()
        }
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
        var reset = false
        val ran = onMain {
            synchronized(lock) {
                if (view != null) {
                    // A reopen inside the post-close hold is still a new opening of the camera, so it
                    // presents as new rather than inheriting however far the last one had faded.
                    attached = true
                    reset = CameraIndicatorAttenuation.restartsForOpen(alreadyAttached = true)
                    return@onMain
                }
                val candidate = IndicatorView(context, lensOffsetPx)
                val params = WindowManager.LayoutParams(
                    CameraIndicatorGeometry.windowWidthPx(lensOffsetPx),
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
                    reset = CameraIndicatorAttenuation.restartsForOpen(alreadyAttached = false)
                    true
                }.onFailure { Log.w(TAG, "indicator addView failed: ${it.javaClass.simpleName}") }
                    .getOrDefault(false)
            }
        }
        if (!ran || !attached) return false
        if (reset) resetProminence()
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
            // The fade clock measures how long the room has had the light in front of it, and a dark
            // screen has shown it nothing, so the display coming back is a discontinuity like any other.
            if (CameraIndicatorAttenuation.restartsForScreen(wasDark = lastDark, nowDark = dark)) resetProminence()
            lastDark = dark
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
                main.removeCallbacks(cycle)
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
                main.removeCallbacks(cycle)
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
    private class IndicatorView(context: Context, private val lensOffsetPx: Int) : View(context) {
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
            val cy = CameraIndicatorGeometry.centreY(lensOffsetPx)
            val r = CameraIndicatorGeometry.radius(lensOffsetPx, ring.strokeWidth)
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
