package io.github.maxlyth.hapaneld.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import android.os.SystemClock
import android.util.Log
import io.github.maxlyth.hapaneld.Config
import io.github.maxlyth.hapaneld.device.DeviceProfile
import java.util.Locale
import java.util.concurrent.CompletableFuture

internal fun formatSensorValue(value: Float): String =
    if (value == value.toLong().toFloat()) value.toLong().toString()
    else String.format(Locale.ROOT, "%.1f", value)

internal data class ProximitySourcePolicy(
    val sparseLearning: Boolean,
    val onChangeHalLiveness: Boolean,
    val legacySeedEligible: Boolean,
)

/** HAL delivery metadata selects liveness and legacy-seed safety, never sparse learning: vendor HALs
 * commonly claim on-change while emitting densely. Reporting sparsity is classified from live cadence. */
internal fun proximitySourcePolicy(
    proximityGpio: Int?,
    halReportingMode: Int?,
): ProximitySourcePolicy = ProximitySourcePolicy(
    sparseLearning = proximityGpio != null,
    onChangeHalLiveness = proximityGpio == null && halReportingMode == Sensor.REPORTING_MODE_ON_CHANGE,
    legacySeedEligible = proximityGpio == null && halReportingMode == Sensor.REPORTING_MODE_CONTINUOUS,
)

internal fun proximityReportingSparse(
    sparseLearning: Boolean,
    cadenceClassified: Boolean,
    continuousCadenceConfirmed: Boolean,
): Boolean = sparseLearning || cadenceClassified && !continuousCadenceConfirmed

/** A normal on-change wave is two callbacks. Four within the three-second window is the first
 * sustained cadence that can exceed the fleet's one-report-per-second numeric budget. */
internal fun proximityCadenceWindowIsContinuous(sampleCount: Int): Boolean = sampleCount >= 4

/**
 * Reports standard Android environmental sensors and feeds every proximity source through one
 * hardware-neutral learner. Device-native proximity values never become HA state: HA sees only a
 * learned binary and, when trustworthy, a fleet-normalized 0 (far) to 100 (near) level.
 */
class SensorReporter(
    context: Context,
    config: Config,
    private val profile: DeviceProfile,
) {
    private val appContext = context.applicationContext
    private val config = config
    private val sm = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val proximitySensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val hasCht8305: Boolean = profile.hasCht8305
    private val tempSensor: Sensor? = if (hasCht8305) null else sm.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)
    private val humiditySensor: Sensor? = if (hasCht8305) null else sm.getDefaultSensor(Sensor.TYPE_RELATIVE_HUMIDITY)
    private val proximityGpio: Int? = profile.proximityGpio
    private val proximityPolicy = proximitySourcePolicy(
        proximityGpio,
        proximitySensor?.reportingMode,
    )
    @Volatile private var proximityRuntime: ProximityLearningRuntime? = null
    @Volatile private var rangedProximityListener: (() -> Unit)? = null
    @Volatile private var lastRangedEligibility = false
    private var proximityPrepared = false

    @Volatile private var liveLux = Float.NaN
    @Volatile private var liveLuxAt = 0L
    @Volatile private var liveTemp = Float.NaN
    @Volatile private var liveTempAt = 0L
    @Volatile private var liveHumid = Float.NaN
    @Volatile private var liveHumidAt = 0L
    @Volatile private var liveProximityAt = 0L
    @Volatile private var lastRaw = Float.NaN
    @Volatile private var activeRun: SensorRunCallbacks? = null
    private var listener: SensorEventListener? = null
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private var gpioClient: GpioProximityClient? = null
    private var proximitySampleCount = 0
    private var cadenceClassified = false
    private var continuousCadenceConfirmed = false
    private var cadenceCheckScheduled = false
    private var staleCheckScheduled = false
    private var onChangeProbeScheduled = false
    private var onChangeProbeAwaiting = false

    private val cadenceCheck = Runnable {
        cadenceCheckScheduled = false
        val run = activeRun ?: return@Runnable
        if (!run.isOpen() || proximityPolicy.sparseLearning) return@Runnable
        cadenceClassified = true
        continuousCadenceConfirmed = proximityCadenceWindowIsContinuous(proximitySampleCount)
        proximitySampleCount = 0
        if (continuousCadenceConfirmed) {
            scheduleStaleCheck(PROXIMITY_STALE_MS + 1L)
        } else {
            scheduleOnChangeProbe(ON_CHANGE_PROBE_INTERVAL_MS)
            // A second true on-change edge was capped while this window was unresolved. Re-admitting
            // sparse reporting must flush that held numeric state even if the HAL now remains silent.
            proximityRuntime?.tick(
                SystemClock.elapsedRealtime(),
                sparseReporting = true,
            )?.let { deliverProximity(it, run) }
        }
    }

    private val staleCheck = Runnable {
        staleCheckScheduled = false
        val run = activeRun ?: return@Runnable
        if (!run.isOpen() || !continuousCadenceConfirmed || liveProximityAt <= 0L) return@Runnable
        val now = SystemClock.elapsedRealtime()
        val remaining = PROXIMITY_STALE_MS - (now - liveProximityAt)
        if (remaining >= 0L) {
            scheduleStaleCheck(remaining + 1L)
            return@Runnable
        }
        proximityRuntime?.tick(now, sparseReporting = reportingSparse())?.let { deliverProximity(it, run) }
    }

    private val onChangeProbeTimeout = Runnable {
        onChangeProbeAwaiting = false
        val run = activeRun ?: return@Runnable
        if (!run.isOpen() || !needsHalLivenessProbe()) return@Runnable
        deliverUnavailable(run)
        scheduleOnChangeProbe(ON_CHANGE_PROBE_INTERVAL_MS)
    }

    private val onChangeProbe = Runnable {
        onChangeProbeScheduled = false
        val run = activeRun ?: return@Runnable
        val sensor = proximitySensor ?: return@Runnable
        val activeListener = listener ?: return@Runnable
        val handler = sensorHandler ?: return@Runnable
        if (!run.isOpen() || !needsHalLivenessProbe()) return@Runnable
        // Android on-change registration is required to produce a current-value event. Re-registering
        // infrequently is a liveness probe, not a polling path; failure takes HA offline fail-closed.
        sm.unregisterListener(activeListener, sensor)
        onChangeProbeAwaiting = true
        val registered = sm.registerListener(activeListener, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
        if (!registered) {
            onChangeProbeAwaiting = false
            deliverUnavailable(run)
            scheduleOnChangeProbe(ON_CHANGE_PROBE_INTERVAL_MS)
        } else {
            handler.postDelayed(onChangeProbeTimeout, ON_CHANGE_ACQUIRE_TIMEOUT_MS)
        }
    }

    init {
        if (!hasProximity()) {
            // Retirement is unconditional: a panel that lost/replaced its source must not carry old
            // threshold heuristics into a later install.
            config.consumeLegacyProximityLearningSeed()
            proximityPrepared = true
        }
    }

    /** Construct the learner only after any predecessor service has finalized its model checkpoint. */
    @Synchronized
    fun prepare() {
        if (proximityPrepared) return
        proximityPrepared = true
        proximityRuntime = ProximityLearningRuntime(
            appContext,
            config,
            proximitySourceIdentity(),
            sparseLearningSource = proximityPolicy.sparseLearning,
            legacySeedEligible = proximityPolicy.legacySeedEligible,
        )
        updateRangedEligibility()
    }

    fun hasLight() = lightSensor != null
    fun hasProximity() = proximitySensor != null || proximityGpio != null
    fun hasRangedProximity() = proximityRuntime?.isRangedSignal() == true
    fun hasTemperature() = tempSensor != null
    fun hasHumidity() = humiditySensor != null

    fun lightDesc(): String? = lightSensor?.let { "Float · 0–${fmtV(it.maximumRange)} lx" }

    fun proximityDesc(): String? = proximityRuntime?.summary()

    fun proximitySummary(): String = proximityRuntime?.summary() ?: "No proximity source"

    fun proximityReady(): Boolean = proximityRuntime?.isWaveReady() == true

    /** Notify the service only when empirical signal-shape eligibility changes. Notifications carry no
     *  truth: every active consumer re-reads this service-owned reporter before acting. */
    fun setRangedProximityListener(listener: (() -> Unit)?) {
        val current = hasRangedProximity()
        synchronized(this) {
            rangedProximityListener = listener
            lastRangedEligibility = current
        }
        listener?.invoke()
    }

    /** Live readings for the panel UI. Raw proximity is diagnostic-only and never leaves this API. */
    fun valuesJson(): String {
        val now = SystemClock.elapsedRealtime()
        fun age(at: Long): Long? = if (at <= 0L) null else ((now - at).coerceAtLeast(0L) / 1000L)
        val light = if (!hasLight()) "\"present\":false" else
            "\"present\":true,\"lux\":${if (liveLux.isNaN()) "null" else fmtV(liveLux)},\"age_s\":${age(liveLuxAt) ?: "null"}"
        val proximity = proximityRuntime?.json(lastRaw, age(liveProximityAt)) ?: "{\"present\":false}"
        val temp = if (!hasTemperature()) "\"present\":false" else
            "\"present\":true,\"c\":${if (liveTemp.isNaN()) "null" else fmtV(liveTemp)},\"age_s\":${age(liveTempAt) ?: "null"}"
        val humidity = if (!hasHumidity()) "\"present\":false" else
            "\"present\":true,\"pct\":${if (liveHumid.isNaN()) "null" else fmtV(liveHumid)},\"age_s\":${age(liveHumidAt) ?: "null"}"
        return "\"light\":{$light},\"proximity\":$proximity,\"temperature\":{$temp},\"humidity\":{$humidity}"
    }

    fun proximityJson(): String = proximityRuntime?.json(
        lastRaw,
        if (liveProximityAt <= 0L) null else
            ((SystemClock.elapsedRealtime() - liveProximityAt).coerceAtLeast(0L) / 1000L),
    ) ?: "{\"present\":false,\"learning\":\"unavailable\",\"phase\":\"unavailable\"}"

    fun startProximityTeach(): Boolean = proximityRuntime?.startTeach() == true

    fun startProximityTest(): Boolean = proximityRuntime?.startTest() == true

    fun cancelProximitySession(): Boolean = proximityRuntime?.cancelSession() == true

    fun relearnProximity(): Boolean {
        if (proximityRuntime?.relearn() != true) return false
        updateRangedEligibility()
        activeRun?.proximity(null, null)
        return true
    }

    @Synchronized
    fun start(
        onLux: (Int) -> Unit,
        onProximity: (Boolean?, Int?, Int) -> Unit,
        onGesture: () -> Unit = {},
        onTemperature: (Float) -> Unit = {},
        onHumidity: (Float) -> Unit = {},
        onLuxRaw: (Float) -> Unit = {},
    ) {
        if (!hasLight() && !hasProximity() && !hasTemperature() && !hasHumidity()) return
        if (activeRun != null) return
        val run = SensorRunCallbacks(onLux, onLuxRaw, onProximity, onGesture, onTemperature, onHumidity)
        activeRun = run
        proximitySampleCount = 0
        cadenceClassified = proximityPolicy.sparseLearning
        continuousCadenceConfirmed = false
        cadenceCheckScheduled = false
        staleCheckScheduled = false
        onChangeProbeScheduled = false
        onChangeProbeAwaiting = false

        listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

            override fun onSensorChanged(event: SensorEvent) {
                if (!run.isOpen() || activeRun !== run || event.values.isEmpty()) return
                when (event.sensor.type) {
                    Sensor.TYPE_LIGHT -> {
                        val lux = event.values[0]
                        val now = SystemClock.elapsedRealtime()
                        liveLux = lux
                        liveLuxAt = now
                        run.light(lux, now)
                    }
                    Sensor.TYPE_PROXIMITY -> handleProximity(event.values[0], run)
                    Sensor.TYPE_AMBIENT_TEMPERATURE -> {
                        val value = event.values[0]
                        val now = SystemClock.elapsedRealtime()
                        liveTemp = value
                        liveTempAt = now
                        run.temperature(value, now)
                    }
                    Sensor.TYPE_RELATIVE_HUMIDITY -> {
                        val value = event.values[0]
                        val now = SystemClock.elapsedRealtime()
                        liveHumid = value
                        liveHumidAt = now
                        run.humidity(value, now)
                    }
                }
            }
        }

        val thread = HandlerThread("ha-paneld-sensors").also { it.start() }
        sensorThread = thread
        val handler = Handler(thread.looper)
        sensorHandler = handler
        lightSensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
        if (hasProximity() && !initializeProximitySource(run, { activeRun === run }) {
                if (proximityGpio == null) {
                    proximitySensor?.let {
                        if (proximityPolicy.onChangeHalLiveness) onChangeProbeAwaiting = true
                        val registered = sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, handler)
                        if (proximityPolicy.onChangeHalLiveness) {
                            if (registered) {
                                // Registration happens on the service-start caller while delivery uses this
                                // handler. Arm from the handler so an already-delivered initial value can clear
                                // the awaiting flag before any timeout is scheduled.
                                handler.post {
                                    if (onChangeProbeAwaiting && activeRun === run) {
                                        handler.postDelayed(onChangeProbeTimeout, ON_CHANGE_ACQUIRE_TIMEOUT_MS)
                                    }
                                }
                            } else {
                                onChangeProbeAwaiting = false
                                scheduleOnChangeProbe(ON_CHANGE_PROBE_INTERVAL_MS)
                            }
                        }
                    }
                } else {
                    val client = GpioProximityClient(
                        gpio = proximityGpio,
                        onValue = { raw -> if (run.isOpen() && activeRun === run) handleProximity(raw, run) },
                        onUnavailable = {
                            if (run.isOpen() && activeRun === run) {
                                deliverUnavailable(run)
                            }
                        },
                    )
                    gpioClient = client
                    client.start()
                }
            }
        ) return
        tempSensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
        humiditySensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
        Log.i(
            TAG,
            "sensors started (light=${hasLight()} proximity=${hasProximity()} temp=${hasTemperature()} humidity=${hasHumidity()})",
        )
    }

    private fun handleProximity(raw: Float, run: SensorRunCallbacks) {
        if (!run.isOpen() || activeRun !== run) return
        val now = SystemClock.elapsedRealtime()
        // Preserve the admitted final edge, then immediately reopen empirical classification. A HAL
        // that resumes dense delivery after a quiet startup therefore gets at most that one sparse
        // report before returning to the numeric rate budget.
        val sparseForObservation = reportingSparse()
        lastRaw = raw
        if (raw.isFinite()) {
            liveProximityAt = now
            if (!proximityPolicy.sparseLearning && !continuousCadenceConfirmed) {
                if (cadenceClassified) {
                    cadenceClassified = false
                    proximitySampleCount = 1
                } else {
                    proximitySampleCount++
                }
                if (!cadenceCheckScheduled) {
                    cadenceCheckScheduled = true
                    sensorHandler?.postDelayed(cadenceCheck, CADENCE_CLASSIFY_MS)
                }
            } else if (continuousCadenceConfirmed && !staleCheckScheduled) {
                // A valid sample after a previously detected stall starts a fresh watchdog epoch.
                scheduleStaleCheck(PROXIMITY_STALE_MS + 1L)
            }
            if (onChangeProbeAwaiting) {
                sensorHandler?.removeCallbacks(onChangeProbeTimeout)
                onChangeProbeAwaiting = false
            }
            if (needsHalLivenessProbe()) {
                scheduleOnChangeProbe(ON_CHANGE_PROBE_INTERVAL_MS)
            }
        }
        val decision = proximityRuntime?.observe(raw, now, sparseReporting = sparseForObservation) ?: return
        updateRangedEligibility()
        deliverProximity(decision, run)
    }

    private fun deliverProximity(decision: ProximityLearningRuntime.Decision, run: SensorRunCallbacks) {
        SensorTrace.recordProx(lastRaw, decision.near)
        if (decision.reportMask != ProximityReportGate.NONE) {
            run.proximity(decision.near, decision.normalizedLevel, decision.reportMask)
        }
        if (decision.deliberateGesture) run.gesture()
    }

    private fun scheduleStaleCheck(delayMs: Long) {
        if (staleCheckScheduled) return
        val handler = sensorHandler ?: return
        staleCheckScheduled = true
        handler.postDelayed(staleCheck, delayMs.coerceAtLeast(1L))
    }

    private fun scheduleOnChangeProbe(delayMs: Long) {
        if (onChangeProbeScheduled || onChangeProbeAwaiting) return
        val handler = sensorHandler ?: return
        onChangeProbeScheduled = true
        handler.postDelayed(onChangeProbe, delayMs.coerceAtLeast(1L))
    }

    private fun needsHalLivenessProbe(): Boolean = proximityGpio == null &&
        (proximityPolicy.onChangeHalLiveness || cadenceClassified && !continuousCadenceConfirmed)

    private fun reportingSparse(): Boolean = proximityReportingSparse(
        sparseLearning = proximityPolicy.sparseLearning,
        cadenceClassified = cadenceClassified,
        continuousCadenceConfirmed = continuousCadenceConfirmed,
    )

    private fun deliverUnavailable(run: SensorRunCallbacks) {
        proximityRuntime?.sourceUnavailable()?.takeIf {
            it.reportMask != ProximityReportGate.NONE
        }?.let {
            run.proximity(null, null, it.reportMask)
        }
    }

    private fun updateRangedEligibility() {
        val current = hasRangedProximity()
        val listener = synchronized(this) {
            if (current == lastRangedEligibility) return
            lastRangedEligibility = current
            rangedProximityListener
        }
        listener?.invoke()
    }

    private fun proximitySourceIdentity(): String {
        val acquisition = proximityGpio?.let { "helper-gpio:$it" }
            ?: proximitySensor?.let { sensor ->
            listOf(
                "android-hal",
                sensor.vendor,
                sensor.name,
                sensor.stringType,
                sensor.version.toString(),
                sensor.type.toString(),
                sensor.resolution.toString(),
                sensor.maximumRange.toString(),
            ).joinToString("|")
        }
            ?: "absent"
        // Firmware is part of the epoch, not a behavior rule. A vendor can invert or quantize the same
        // named HAL sensor in an OTA; selecting a new empty model is safer than validating old anchors
        // against an on-change stream whose new far value may look exactly like the old near value.
        return "$acquisition|build=${Build.FINGERPRINT}|display=${Build.DISPLAY}"
    }

    private fun fmtV(value: Float) = formatSensorValue(value)

    @Synchronized
    fun stop(): CompletableFuture<Unit> {
        activeRun?.close()
        activeRun = null
        gpioClient?.stop()
        gpioClient = null
        listener?.let { sm.unregisterListener(it) }
        listener = null
        sensorHandler?.removeCallbacks(cadenceCheck)
        sensorHandler?.removeCallbacks(staleCheck)
        sensorHandler?.removeCallbacks(onChangeProbe)
        sensorHandler?.removeCallbacks(onChangeProbeTimeout)
        sensorHandler = null
        sensorThread?.quitSafely()
        sensorThread = null
        return proximityRuntime?.closeAsync() ?: CompletableFuture.completedFuture(Unit)
    }

    private companion object {
        const val TAG = "ha-paneld/sensors"
        const val CADENCE_CLASSIFY_MS = 3_000L
        const val PROXIMITY_STALE_MS = 60_000L
        const val ON_CHANGE_PROBE_INTERVAL_MS = 15L * 60_000L
        const val ON_CHANGE_ACQUIRE_TIMEOUT_MS = 5_000L
    }
}
