package io.github.maxlyth.hapaneld.control

import android.util.Log
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.sensors.SensorTrace
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Optional on-panel auto-brightness engine. Consumes a lux stream — from the panel's own ambient-light
 * sensor where present, or **HA-fed room lux** for panels without one (e.g. the WF1589T) — and drives
 * the screen backlight via [BrightnessController].
 *
 * **Why this exists despite ha-paneld being otherwise actuator-only:** the most reliable, responsive
 * trigger is *room lights switching on* — the panel's own sensor sees a sudden step, with a latency and
 * robustness no HA automation (which has to be wired up, and inevitably breaks) can match. So this is a
 * deliberate, **opt-in** exception behind `switch.<panel>_auto_brightness` (default off → behaviour is
 * exactly the pure actuator). HA remains the lux *authority*: on sensor-less panels it feeds lux in.
 *
 * **Asymmetric response** (the key behaviour): the smoothing time-constant scales with the *magnitude*
 * of the change. A large, sudden jump (lights on/off) tracks **fast** so it feels snappy; small or slow
 * drift (sunrise) and sensor noise are **heavily** averaged, with a deadband, so the panel never
 * flickers or hunts. One EMA whose `alpha` is chosen per sample gives both.
 *
 * Fed from two sources (latest sample wins): the un-throttled ALS tap in `SensorReporter`, and the
 * HA-fed `number.<panel>_ambient_lux`. Thread-safe (both arrive on different threads).
 */
class AutoBrightnessController(
    private val brightness: BrightnessController,
    private val config: Config,
) {
    private val lock = Any()
    private var smoothed = -1f   // EMA of lux; <0 = uninitialised (snap on first sample)
    private var applied = -1     // last brightness we set (deadband reference)

    /** Feed one lux sample (ALS or HA-fed). Drives the backlight only when enabled + writable; always
     *  records to [SensorTrace] (raw lux always; smoothed/target/applied when the engine is active). */
    fun submitLux(lux: Float) {
        if (lux.isNaN() || lux < 0f) return
        var sm: Float? = null   // engine internals for the trace; null when the engine is idle
        var tgt: Int? = null
        var app: Int? = null
        if (!config.autoBrightness) {
            synchronized(lock) { smoothed = -1f; applied = -1 }  // reset so re-enable snaps cleanly
        } else if (brightness.canWrite()) {
            var toSet = -1
            synchronized(lock) {
                if (smoothed < 0f) {
                    smoothed = lux                   // first sample after enable → snap, don't ramp
                } else {
                    // Ratio of change (perception is logarithmic): a big ratio = a real step (lights on)
                    // → fast attack; a small ratio = drift/noise → heavy smoothing.
                    val hi = max(lux, smoothed)
                    val lo = max(min(lux, smoothed), 1f)
                    val alpha = if (hi / lo >= FAST_RATIO) FAST_ALPHA else SLOW_ALPHA
                    smoothed += alpha * (lux - smoothed)
                }
                val t = curve(smoothed)
                sm = smoothed; tgt = t
                if (applied < 0 || abs(t - applied) >= DEADBAND) { applied = t; toSet = t } // deadband
                app = applied
            }
            if (toSet >= 0) {
                brightness.setBrightness(toSet)
                Log.d(TAG, "lux≈${sm?.roundToInt()} -> brightness $toSet (bias ${config.brightnessBias})")
            }
        }
        SensorTrace.recordLux(lux, sm, tgt, app)
    }

    /** Perceptual lux→brightness: log curve from [MIN_BRIGHT]..255 over 0..[REF_LUX], shifted by bias. */
    private fun curve(lux: Float): Int {
        val frac = (ln(lux + 1f) / ln(REF_LUX + 1f)).coerceIn(0f, 1f)
        val base = MIN_BRIGHT + frac * (255 - MIN_BRIGHT)
        return (base + config.brightnessBias).roundToInt().coerceIn(MIN_BRIGHT, 255)
    }

    companion object {
        private const val TAG = "ha-paneld/autobright"
        private const val FAST_RATIO = 2.0f   // ≥2× change vs the running average → lights-on style step
        private const val FAST_ALPHA = 0.6f   // fast attack on big steps (snappy)
        private const val SLOW_ALPHA = 0.05f  // heavy smoothing on drift / sensor noise (calm)
        private const val DEADBAND = 4        // ignore <4/255 target moves (flicker guard)
        private const val MIN_BRIGHT = 8      // never auto-dim fully dark — keep a readable floor
        private const val REF_LUX = 1000f     // lux at which the curve reaches full brightness
    }
}
