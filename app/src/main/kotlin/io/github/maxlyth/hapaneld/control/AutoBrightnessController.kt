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
    private val engine = AutoBrightnessEngine()

    /** Feed one lux sample (ALS or HA-fed). Drives the backlight only when enabled + writable; always
     *  records to [SensorTrace] (raw lux always; smoothed/target/applied when the engine is active). */
    fun submitLux(lux: Float) {
        val enabled = config.autoBrightness
        val sample = engine.submit(
            lux = lux,
            enabled = enabled,
            writable = enabled && brightness.canWrite(),
            bias = config.brightnessBias,
        ) ?: return
        sample.toSet?.let {
            brightness.setBrightness(it)
            Log.d(TAG, "lux≈${sample.smoothed?.roundToInt()} -> brightness $it (bias ${config.brightnessBias})")
        }
        SensorTrace.recordLux(lux, sample.smoothed, sample.target, sample.applied)
    }

    companion object {
        private const val TAG = "ha-paneld/autobright"
    }
}

/** Pure, serialized auto-brightness policy; Android/config access stays in [AutoBrightnessController]. */
internal class AutoBrightnessEngine {
    private var smoothed = -1f   // EMA of lux; <0 = uninitialised (snap on first sample)
    private var applied = -1     // last brightness command (deadband reference)

    @Synchronized
    fun submit(lux: Float, enabled: Boolean, writable: Boolean, bias: Int): AutoBrightnessSample? {
        if (!lux.isFinite() || lux < 0f) return null
        if (!enabled) {
            smoothed = -1f
            applied = -1
            return AutoBrightnessSample()
        }
        if (!writable) return AutoBrightnessSample()

        if (smoothed < 0f) {
            smoothed = lux
        } else {
            // Ratio of change (perception is logarithmic): a big ratio = a real step (lights on)
            // → fast attack; a small ratio = drift/noise → heavy smoothing.
            val hi = max(lux, smoothed)
            val lo = max(min(lux, smoothed), 1f)
            val alpha = if (hi / lo >= FAST_RATIO) FAST_ALPHA else SLOW_ALPHA
            smoothed += alpha * (lux - smoothed)
        }
        val target = curve(smoothed, bias)
        val toSet = if (applied < 0 || abs(target - applied) >= DEADBAND) target else null
        if (toSet != null) applied = toSet
        return AutoBrightnessSample(smoothed, target, applied, toSet)
    }

    /** Perceptual lux→brightness: log curve over 0..[REF_LUX], shifted by bias. */
    private fun curve(lux: Float, bias: Int): Int {
        val frac = (ln(lux + 1f) / ln(REF_LUX + 1f)).coerceIn(0f, 1f)
        val base = BrightnessController.MIN_VISIBLE + frac * (255 - BrightnessController.MIN_VISIBLE)
        return (base + bias).roundToInt().coerceIn(BrightnessController.MIN_VISIBLE, 255)
    }

    companion object {
        private const val FAST_RATIO = 2.0f   // ≥2× change vs the running average → lights-on style step
        private const val FAST_ALPHA = 0.6f   // fast attack on big steps (snappy)
        private const val SLOW_ALPHA = 0.05f  // heavy smoothing on drift / sensor noise (calm)
        private const val DEADBAND = 4        // ignore <4/255 target moves (flicker guard)
        private const val REF_LUX = 1000f     // lux at which the curve reaches full brightness
    }
}

internal data class AutoBrightnessSample(
    val smoothed: Float? = null,
    val target: Int? = null,
    val applied: Int? = null,
    val toSet: Int? = null,
)
