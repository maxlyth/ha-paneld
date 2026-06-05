package io.github.maxlyth.hapaneld.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import io.github.maxlyth.hapaneld.Config
import kotlin.math.abs
import kotlin.math.max

/**
 * Reports the panel's standard Android light + proximity sensors to HA. Both are plain
 * `SensorManager` sensors (`android.sensor.light`/`proximity`, `perm: n/a`) — no root, no vendor lib.
 * Panel sensors are exposed as data, not as the occupancy/lux *authority* (room sensors remain that).
 *
 * **Proximity** is graded on this hardware (TPA10 ToF ≈ 20, NSPanelPro ≈ 106 at idle — different
 * scales, and the near/far polarity is inverted between them). Rather than push that per-device mess
 * into HA, the raw value stays on-device (cached in [lastRaw], surfaced in the HTTP UI for tuning)
 * and HA receives only a clean binary. The binary is a **Schmitt trigger** off a user calibration
 * (two captures → midpoint threshold + inferred polarity; dead-zone width from a sensitivity preset),
 * which absorbs the scale + inversion and resists flapping without adding latency. Uncalibrated panels
 * fall back to the legacy `raw < maximumRange` so behaviour is unchanged until tuned.
 *
 * Publishing is change-gated to avoid MQTT spam: light on a >=20% lux change (min 2s apart);
 * proximity only on near<->far transitions (the raw stream never leaves the device).
 */
class SensorReporter(context: Context, private val config: Config) {
    private val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val proximitySensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    // Onboard climate (e.g. TPA10 CHT8305) — standard HAL sensors, no root/vendor lib.
    private val tempSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
    private val humiditySensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY)

    private var lastLux = -1f
    private var lastLuxAt = 0L
    private var lastNear: Boolean? = null
    private var lastTemp = Float.NaN
    private var lastTempAt = 0L
    private var lastHumid = Float.NaN
    private var lastHumidAt = 0L
    private var listener: SensorEventListener? = null
    private var onProximity: ((Boolean) -> Unit)? = null
    private var onTemp: ((Float) -> Unit)? = null
    private var onHumid: ((Float) -> Unit)? = null
    private val seenRaw = java.util.TreeSet<Float>() // distinct raw values observed → graded vs binary

    /** Latest raw proximity reading (device-native units), updated ungated on every event. */
    @Volatile
    var lastRaw: Float = Float.NaN
        private set

    fun hasLight() = lightSensor != null
    fun hasProximity() = proximitySensor != null
    fun hasTemperature() = tempSensor != null
    fun hasHumidity() = humiditySensor != null
    fun maxRange(): Float = proximitySensor?.maximumRange ?: 0f

    fun start(
        onLux: (Int) -> Unit,
        onProximity: (Boolean) -> Unit,
        onTemperature: (Float) -> Unit = {},
        onHumidity: (Float) -> Unit = {},
    ) {
        if (lightSensor == null && proximitySensor == null && tempSensor == null && humiditySensor == null) return
        this.onProximity = onProximity
        this.onTemp = onTemperature
        this.onHumid = onHumidity
        listener = object : SensorEventListener {
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_LIGHT -> {
                        val lux = e.values[0]
                        val now = System.currentTimeMillis()
                        val changed = lastLux < 0 || abs(lux - lastLux) >= max(1f, lastLux * 0.2f)
                        if (changed && now - lastLuxAt >= 15000) {
                            lastLux = lux
                            lastLuxAt = now
                            onLux(lux.toInt())
                        }
                    }
                    Sensor.TYPE_PROXIMITY -> {
                        lastRaw = e.values[0]
                        synchronized(seenRaw) {
                            if (seenRaw.size < 32) seenRaw.add(Math.round(e.values[0] * 10) / 10f)
                        }
                        evaluateProximity()
                    }
                    // Climate is slow + informational — keep recorder load tiny: report only on a
                    // meaningful delta (>=0.2C / >=1%), min 60s apart, first reading always. Stable
                    // climate produces NO updates at all (no forced periodic). Values are rounded
                    // (1dp temp, integer humidity) at publish so precision wobble can't make rows.
                    Sensor.TYPE_AMBIENT_TEMPERATURE -> {
                        val t = e.values[0]; val now = System.currentTimeMillis()
                        if ((lastTemp.isNaN() || abs(t - lastTemp) >= 0.2f) && now - lastTempAt >= 60000) {
                            lastTemp = t; lastTempAt = now; onTemp?.invoke(t)
                        }
                    }
                    Sensor.TYPE_RELATIVE_HUMIDITY -> {
                        val h = e.values[0]; val now = System.currentTimeMillis()
                        if ((lastHumid.isNaN() || abs(h - lastHumid) >= 1.0f) && now - lastHumidAt >= 60000) {
                            lastHumid = h; lastHumidAt = now; onHumid?.invoke(h)
                        }
                    }
                }
            }
        }
        lightSensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        // UI delay (not NORMAL): proximity is on-change + low-power, so a faster max-rate just makes
        // both wake-on-approach and live UI tuning responsive without meaningful battery cost.
        proximitySensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        tempSensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        humiditySensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        Log.i(TAG, "sensors started (light=${hasLight()} proximity=${hasProximity()} temp=${hasTemperature()} humidity=${hasHumidity()})")
    }

    /** Recompute the binary from the cached raw + current calibration, publishing on a change.
     *  Called after a capture / threshold / sensitivity edit so HA reflects it without needing motion. */
    fun reevaluate() = evaluateProximity()

    private fun evaluateProximity() {
        val raw = lastRaw
        if (raw.isNaN()) return
        val near = computeNear(raw)
        if (lastNear != near) {
            lastNear = near
            onProximity?.invoke(near)
        }
    }

    /** Schmitt trigger off the calibration; pre-calibration fallback = legacy `raw < maximumRange`. */
    private fun computeNear(raw: Float): Boolean {
        val t = config.proximityThreshold
        if (!config.proximityCalibrated || t.isNaN()) {
            return raw < (proximitySensor?.maximumRange ?: Float.MAX_VALUE)
        }
        val m = config.proximityMargin
        val lo = t - m
        val hi = t + m
        val held = lastNear ?: false
        return if (config.proximityNearBelow) {
            when { raw < lo -> true; raw > hi -> false; else -> held }
        } else {
            when { raw > hi -> true; raw < lo -> false; else -> held }
        }
    }

    /** Live proximity state for the HTTP tuning UI. Raw + calibration; `indistinct` flags a bad capture. */
    fun proximityJson(): String {
        val raw = lastRaw
        val nr = config.proximityNearRaw
        val fr = config.proximityFarRaw
        val th = config.proximityThreshold
        val indistinct = config.proximityCalibrated && abs(nr - fr) < 1f
        val vals = synchronized(seenRaw) { seenRaw.toList() }
        // Graded (worth a threshold/gauge) vs binary (0/1 — only polarity matters). Until the user
        // triggers near+far we've seen too few values to tell, so default to binary (the common case).
        val graded = vals.size > 2 && (vals.last() - vals.first()) >= 2f
        fun f(v: Float) = if (v.isNaN()) "null" else v.toString()
        return "{\"present\":${hasProximity()},\"raw\":${f(raw)},\"near\":${lastNear ?: false}," +
            "\"max\":${maxRange()},\"calibrated\":${config.proximityCalibrated}," +
            "\"threshold\":${f(th)},\"nearRaw\":${f(nr)},\"farRaw\":${f(fr)}," +
            "\"nearBelow\":${config.proximityNearBelow},\"margin\":${config.proximityMargin}," +
            "\"sensitivity\":\"${config.proximitySensitivity.name}\",\"indistinct\":$indistinct," +
            "\"graded\":$graded,\"distinct\":${vals.size}}"
    }

    fun stop() {
        listener?.let { sm.unregisterListener(it) }
        listener = null
        onProximity = null
        onTemp = null
        onHumid = null
    }

    companion object {
        private const val TAG = "ha-paneld/sensors"
    }
}
