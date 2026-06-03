package io.github.maxlyth.hapaneld.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.abs
import kotlin.math.max

/**
 * Reports the panel's standard Android light + proximity sensors to HA. Both are plain
 * `SensorManager` sensors on the NSPanelPro (`android.sensor.light`/`proximity`, `perm: n/a`) —
 * no root, no vendor lib. Panel sensors are exposed as data, not as the occupancy/lux *authority*
 * (room sensors remain that); HA decides what to do with them.
 *
 * Publishing is change-gated to avoid MQTT spam: light on a >=20% lux change (min 2s apart),
 * proximity on near<->far transitions only. Sensors absent on the hardware are simply not
 * advertised ([hasLight]/[hasProximity] false).
 */
class SensorReporter(context: Context) {
    private val sm = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lightSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val proximitySensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)

    private var lastLux = -1f
    private var lastLuxAt = 0L
    private var lastNear: Boolean? = null
    private var listener: SensorEventListener? = null

    fun hasLight() = lightSensor != null
    fun hasProximity() = proximitySensor != null

    fun start(onLux: (Int) -> Unit, onProximity: (Boolean) -> Unit) {
        if (lightSensor == null && proximitySensor == null) return
        listener = object : SensorEventListener {
            override fun onAccuracyChanged(s: Sensor?, accuracy: Int) {}
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_LIGHT -> {
                        val lux = e.values[0]
                        val now = System.currentTimeMillis()
                        val changed = lastLux < 0 || abs(lux - lastLux) >= max(1f, lastLux * 0.2f)
                        if (changed && now - lastLuxAt >= 2000) {
                            lastLux = lux
                            lastLuxAt = now
                            onLux(lux.toInt())
                        }
                    }
                    Sensor.TYPE_PROXIMITY -> {
                        // Most panel proximity sensors are binary: a value below the sensor's max
                        // range means "near".
                        val near = e.values[0] < (e.sensor.maximumRange)
                        if (lastNear != near) {
                            lastNear = near
                            onProximity(near)
                        }
                    }
                }
            }
        }
        lightSensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        proximitySensor?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        Log.i(TAG, "sensors started (light=${hasLight()} proximity=${hasProximity()})")
    }

    fun stop() {
        listener?.let { sm.unregisterListener(it) }
        listener = null
    }

    companion object {
        private const val TAG = "ha-paneld/sensors"
    }
}
