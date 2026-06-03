package io.github.maxlyth.hapaneld.hardware

/**
 * Per-panel RGB LED HAL. The uniform interface lets the MQTT `light.<panel>_led` entity look
 * identical fleet-wide; the adapter underneath differs by hardware (vendor JNI on rk3576, sysfs
 * or other vendor lib elsewhere). r/g/b are HA range 0..255; adapters scale to the panel's native
 * range. [available] is false when the panel has no usable LED backend (e.g. vendor .so absent),
 * so the bridge can skip publishing the entity.
 */
interface LedController {
    fun available(): Boolean
    fun setRgb(r: Int, g: Int, b: Int)
    fun off()
}

/** Used on panels with no known LED backend. */
class NoOpLedController : LedController {
    override fun available() = false
    override fun setRgb(r: Int, g: Int, b: Int) {}
    override fun off() {}
}
