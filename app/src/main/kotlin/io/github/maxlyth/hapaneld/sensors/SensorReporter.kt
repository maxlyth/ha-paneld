package io.github.maxlyth.hapaneld.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.control.Su
import io.github.maxlyth.hapaneld.device.DeviceProfile
import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import io.github.maxlyth.hapaneld.metrics.FeatureCostOutcome
import io.github.maxlyth.hapaneld.metrics.FeatureCosts
import io.github.maxlyth.hapaneld.util.SystemProps
import kotlin.math.abs

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
 * Publishing is change-gated to avoid MQTT spam: light on a >=20% lux change (min 15s apart);
 * proximity only on near<->far transitions (the raw stream never leaves the device).
 */
class SensorReporter(
    context: Context,
    private val config: Config,
    private val profile: DeviceProfile = DeviceProfile.detect(),
) {
    private val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val proximitySensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    // Onboard climate via the standard HAL — SUPPRESSED on CHT8305 panels (TPA10). There the chip is read
    // through the root helper daemon as room_temp/room_humidity instead: its SensorManager ambient-temp /
    // humidity sensors register (present) but never stream a value, so they'd only surface a frozen, stale
    // reading duplicating the daemon entities. Defer entirely to the daemon path where the profile declares
    // the chip; on any other panel the HAL sensors work as before.
    private val hasCht8305: Boolean = profile.hasCht8305
    private val tempSensor: Sensor? = if (hasCht8305) null else sm.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
    private val humiditySensor: Sensor? = if (hasCht8305) null else sm.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY)
    // Root binary proximity GPIO (1=near, 0=far) for panels whose SensorManager proximity never fires
    // (Smatek S9E gpio18). Null → use [proximitySensor] above. Polled instead of registered when set.
    private val proximityGpio: Int? = profile.proximityGpio
    private var proxPoller: Thread? = null

    // Live (un-throttled) readings for the Sensors card — updated on EVERY sensor event, independent
    // of the publish throttles and of whether the value is exposed to HA at all.
    @Volatile private var liveLux = Float.NaN
    @Volatile private var liveLuxAt = 0L
    @Volatile private var liveTemp = Float.NaN
    @Volatile private var liveTempAt = 0L
    @Volatile private var liveHumid = Float.NaN
    @Volatile private var liveHumidAt = 0L
    @Volatile private var liveProximityAt = 0L
    private var lastNear: Boolean? = null
    private var listener: SensorEventListener? = null
    // Background thread the SensorManager delivers callbacks on (keeps blocking MQTT publishes off main).
    private var sensorThread: HandlerThread? = null
    @Volatile private var activeRun: SensorRunCallbacks? = null
    private val seenRaw = java.util.TreeSet<Float>() // distinct raw values observed → graded vs binary

    // Authoritative graded/binary from the firmware version where the profile knows the rule (NSPanel Pro
    // kernel-driver cutover), so a graded sensor never reads "Binary" at idle. Null → observe (TPA10 etc.).
    private val fwGraded: Boolean? =
        profile.proximityGradedForFirmware(SystemProps.get("ro.product.version"))

    private fun gradedObserved(): Boolean =
        synchronized(seenRaw) { seenRaw.size > 2 && (seenRaw.last() - seenRaw.first()) >= 2f }

    /** Proximity graded(true) vs binary(false): firmware rule where the profile knows it, else observation. */
    private fun proximityGraded(): Boolean = fwGraded ?: gradedObserved()

    /** Latest raw proximity reading (device-native units), updated ungated on every event. */
    @Volatile
    var lastRaw: Float = Float.NaN
        private set

    fun hasLight() = lightSensor != null
    fun hasProximity() = proximitySensor != null || proximityGpio != null
    fun hasTemperature() = tempSensor != null
    fun hasHumidity() = humiditySensor != null
    fun maxRange(): Float = if (proximityGpio != null) 1f else proximitySensor?.maximumRange ?: 0f

    /** Light value-type + range for the info page (lux is a continuous float), or null if absent. */
    fun lightDesc(): String? = lightSensor?.let { "Float · 0–${fmtV(it.maximumRange)} lx" }

    /**
     * LIVE sensor readings as a JSON FRAGMENT (no outer braces) for the `/api/v1/sensors` endpoint —
     * the Sensors card. Shows ALL sensors the panel has, independent of publish throttles and of
     * whether a value is exposed to HA (config can hide entities; the hardware view must not hide
     * with them). Ages are seconds since the last reading; a sensor that exists but has not reported
     * yet has a null value.
     */
    fun valuesJson(): String {
        val now = android.os.SystemClock.elapsedRealtime()
        fun age(at: Long) = if (at <= 0L) "null" else ((now - at) / 1000).toString()
        val light = if (!hasLight()) """"present":false""" else
            """"present":true,"lux":${if (liveLux.isNaN()) "null" else fmtV(liveLux)},"age_s":${age(liveLuxAt)}"""
        val prox = if (!hasProximity()) """"present":false""" else
            """"present":true,"near":${lastNear ?: "null"},"raw":${if (lastRaw.isNaN()) "null" else fmtV(lastRaw)},"age_s":${age(liveProximityAt)}"""
        val temp = if (!hasTemperature()) """"present":false""" else
            """"present":true,"c":${if (liveTemp.isNaN()) "null" else fmtV(liveTemp)},"age_s":${age(liveTempAt)}"""
        val humid = if (!hasHumidity()) """"present":false""" else
            """"present":true,"pct":${if (liveHumid.isNaN()) "null" else fmtV(liveHumid)},"age_s":${age(liveHumidAt)}"""
        return """"light":{$light},"proximity":{$prox},"temperature":{$temp},"humidity":{$humid}"""
    }

    /** Proximity value-type + range for the info page, or null if absent. Graded vs binary via
     *  [proximityGraded] (firmware rule where known, else observation); graded → Integer/Float by the
     *  sensor's resolution, binary → near/far. Range from the sensor's maximumRange. */
    fun proximityDesc(): String? {
        if (proximityGpio != null) return "Binary · near/far (root GPIO $proximityGpio)"
        val s = proximitySensor ?: return null
        val graded = proximityGraded()
        return when {
            !graded -> "Binary · near/far (0 / ${fmtV(s.maximumRange)} cm)"
            s.resolution >= 1f -> "Integer · 0–${fmtV(s.maximumRange)} cm"
            else -> "Float · 0–${fmtV(s.maximumRange)} cm"
        }
    }

    private fun fmtV(f: Float): String =
        if (f == f.toLong().toFloat()) f.toLong().toString() else "%.1f".format(f)

    @Synchronized
    fun start(
        onLux: (Int) -> Unit,
        onProximity: (Boolean) -> Unit,
        onTemperature: (Float) -> Unit = {},
        onHumidity: (Float) -> Unit = {},
        // Un-throttled, every-event lux feed for the on-panel auto-brightness engine. The MQTT-report
        // path (onLux) stays change-gated; auto-brightness needs raw samples for a fast lights-on react.
        onLuxRaw: (Float) -> Unit = {},
    ) {
        if (lightSensor == null && proximitySensor == null && tempSensor == null && humiditySensor == null && proximityGpio == null) return
        if (activeRun != null) return
        val run = SensorRunCallbacks(onLux, onLuxRaw, onProximity, onTemperature, onHumidity)
        activeRun = run
        lastNear = null
        listener = object : SensorEventListener {
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_LIGHT -> {
                        val lux = e.values[0]
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (!run.isOpen()) return
                        liveLux = lux; liveLuxAt = now
                        run.light(lux, now)
                    }
                    Sensor.TYPE_PROXIMITY -> {
                        if (!run.isOpen()) return
                        val raw = e.values[0]
                        lastRaw = raw
                        liveProximityAt = android.os.SystemClock.elapsedRealtime()
                        synchronized(seenRaw) {
                            if (seenRaw.size < 32) seenRaw.add(Math.round(raw * 10) / 10f)
                        }
                        SensorTrace.recordProx(raw, computeNear(raw)) // raw trace (debug fit-testing)
                        evaluateProximity(run)
                    }
                    // Climate is slow + informational — keep recorder load tiny: report only on a
                    // meaningful delta (>=0.2C / >=1%), min 60s apart, first reading always. Stable
                    // climate produces NO updates at all (no forced periodic). Values are rounded
                    // (1dp temp, integer humidity) at publish so precision wobble can't make rows.
                    Sensor.TYPE_AMBIENT_TEMPERATURE -> {
                        if (!run.isOpen()) return
                        val t = e.values[0]; val now = android.os.SystemClock.elapsedRealtime()
                        liveTemp = t; liveTempAt = now
                        run.temperature(t, now)
                    }
                    Sensor.TYPE_RELATIVE_HUMIDITY -> {
                        if (!run.isOpen()) return
                        val h = e.values[0]; val now = android.os.SystemClock.elapsedRealtime()
                        liveHumid = h; liveHumidAt = now
                        run.humidity(h, now)
                    }
                }
            }
        }
        // Deliver sensor callbacks on a DEDICATED background thread, not the main looper. onSensorChanged
        // fans out to MQTT publishes (publishLight/Proximity/Temperature/Humidity); a HiveMQ publish can
        // block on an internal monitor while the connection is (re)establishing, so on the main thread a
        // wedged broker + a live light sensor = a hung UI thread → ANR ("ha-paneld isn't responding").
        // Off-main, a slow/blocked publish only stalls this thread, never the app. (Same class of fix as
        // moving Su.run() off the main thread.)
        val h = HandlerThread("ha-paneld-sensors").also { it.start() }
        sensorThread = h
        val handler = Handler(h.looper)
        lightSensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
        // Proximity: poll the raw GPIO over root where the SensorManager sensor never fires (S9E);
        // otherwise register the real SensorManager sensor. UI delay (not NORMAL) keeps wake-on-approach
        // and live UI tuning responsive without meaningful battery cost.
        if (proximityGpio != null) startProximityPoll(proximityGpio, run)
        else proximitySensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI, handler) }
        tempSensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
        humiditySensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
        Log.i(TAG, "sensors started (light=${hasLight()} proximity=${hasProximity()} temp=${hasTemperature()} humidity=${hasHumidity()})")
    }

    /** Poll the raw binary proximity GPIO over root (S9E gpio18; its SensorManager proximity registers
     *  but never delivers). 1 = near, 0 = far (reporter-confirmed). Feeds the same evaluate/publish path
     *  as the SensorManager proximity, so calibration UI, change-gating and wake-on-wave work unchanged.
     *  Reads go through the persistent `su` shell (~13 ms), so the steady poll is cheap. */
    private fun startProximityPoll(gpio: Int, run: SensorRunCallbacks) {
        val node = "/sys/class/gpio/gpio$gpio/value"
        proxPoller = Thread {
            while (run.isOpen()) {
                val cost = FeatureCosts.registry.beginSynchronous(FeatureCostOperation.GPIO_PROXIMITY_POLL)
                var outcome = FeatureCostOutcome.SUCCESS
                val raw = try {
                    when (Su.runOutput("cat $node 2>/dev/null")?.trim()) {
                        "1" -> 1f
                        "0" -> 0f
                        else -> {
                            outcome = FeatureCostOutcome.FAILURE
                            Float.NaN
                        }
                    }
                } catch (e: Exception) {
                    outcome = FeatureCostOutcome.FAILURE
                    Float.NaN
                } finally {
                    FeatureCosts.registry.finishSynchronous(
                        FeatureCostOperation.GPIO_PROXIMITY_POLL,
                        cost,
                        outcome = outcome,
                        workUnits = 1,
                    )
                }
                // The root read can outlive stop(). Reject its result before it mutates replacement state.
                if (!run.isOpen() || activeRun !== run) break
                if (!raw.isNaN()) {
                    lastRaw = raw
                    liveProximityAt = android.os.SystemClock.elapsedRealtime()
                    synchronized(seenRaw) { if (seenRaw.size < 32) seenRaw.add(raw) }
                    SensorTrace.recordProx(raw, computeNear(raw))
                    evaluateProximity(run)
                }
                try { Thread.sleep(PROX_POLL_MS) } catch (e: InterruptedException) { break }
            }
        }.apply { isDaemon = true; name = "ha-paneld-prox"; start() }
        Log.i(TAG, "proximity via root gpio$gpio poll (${PROX_POLL_MS}ms)")
    }

    /** Recompute the binary from the cached raw + current calibration, publishing on a change.
     *  Called after a capture / threshold / sensitivity edit so HA reflects it without needing motion. */
    fun reevaluate() = activeRun?.let(::evaluateProximity)

    private fun evaluateProximity(run: SensorRunCallbacks) {
        if (!run.isOpen()) return
        val raw = lastRaw
        if (raw.isNaN()) return
        val near = computeNear(raw)
        if (lastNear != near) {
            lastNear = near
            run.proximity(near)
        }
    }

    /** Schmitt trigger off the calibration; pre-calibration fallback = legacy `raw < maximumRange`. */
    private fun computeNear(raw: Float): Boolean {
        if (proximityGpio != null) return raw >= 0.5f   // clean binary GPIO: 1 = near, 0 = far (no calibration)
        val t = config.proximityThreshold
        if (!config.proximityCalibrated || t.isNaN()) {
            // Binary sensor (Android convention: 0 = near, any positive = far): the "far" reading is NOT
            // necessarily maximumRange — e.g. NSPanel 4.x reports far=1 with maximumRange=9, so the old
            // `raw < maximumRange` was always-near (0<9 AND 1<9). Treat ~0 as near instead.
            if (!proximityGraded()) return raw < 0.5f
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
        val graded = proximityGraded()
        fun f(v: Float) = if (v.isNaN()) "null" else v.toString()
        return "{\"present\":${hasProximity()},\"raw\":${f(raw)},\"near\":${lastNear ?: false}," +
            "\"max\":${maxRange()},\"calibrated\":${config.proximityCalibrated}," +
            "\"threshold\":${f(th)},\"nearRaw\":${f(nr)},\"farRaw\":${f(fr)}," +
            "\"nearBelow\":${config.proximityNearBelow},\"margin\":${config.proximityMargin}," +
            "\"sensitivity\":\"${config.proximitySensitivity.name}\",\"indistinct\":$indistinct," +
            "\"graded\":$graded,\"distinct\":${vals.size}}"
    }

    @Synchronized
    fun stop() {
        activeRun?.close()
        activeRun = null
        proxPoller?.interrupt()
        proxPoller = null
        listener?.let { sm.unregisterListener(it) }
        listener = null
        sensorThread?.quitSafely()
        sensorThread = null
    }

    companion object {
        private const val TAG = "ha-paneld/sensors"
        internal const val PROX_POLL_MS = 500L // root-GPIO proximity poll cadence (wake-on-wave latency)
    }
}
