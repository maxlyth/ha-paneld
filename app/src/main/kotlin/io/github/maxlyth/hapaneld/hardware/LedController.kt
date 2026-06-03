package io.github.maxlyth.hapaneld.hardware

/**
 * Per-panel RGB LED HAL. The uniform interface lets the MQTT `light.<panel>_led` entity look
 * identical fleet-wide; the adapter underneath differs by hardware (clean-room NDK ioctl on the
 * rk3576 `/dev/ledjni`, root sysfs elsewhere). r/g/b are HA range 0..255; adapters scale to the
 * panel's native range. [available] is false when the panel has no usable LED backend (no node /
 * no root), so the bridge can skip publishing the entity.
 */
interface LedController {
    fun available(): Boolean

    /** True if the backend can set arbitrary RGB; false = brightness-only (HA publishes no colour). */
    fun colorCapable(): Boolean

    fun setRgb(r: Int, g: Int, b: Int)
    fun off()
}

/** Used on panels with no known LED backend. */
class NoOpLedController : LedController {
    override fun available() = false
    override fun colorCapable() = false
    override fun setRgb(r: Int, g: Int, b: Int) {}
    override fun off() {}
}

/**
 * Picks the LED backend for this panel: rk3576 NDK ioctl on `/dev/ledjni` (probed by opening the
 * node), else the sysfs adapter. A panel with no node + no usable backend just yields
 * `available()=false` → the bridge skips the LED entity.
 *
 * NOTE: [SysfsLedController] currently shells out via `su`, which a sandboxed app CANNOT do
 * (SELinux `untrusted_app`; confirmed on TPA10, `su: error=13`). It therefore always reports
 * `available()=false` from inside the app and is effectively a NoOp. The real TPA10/sysfs path is
 * a root helper daemon (ha-paneld connects over a localhost socket) — not yet built. Until then,
 * only rk3576 has a working app-direct LED.
 */
object LedFactory {
    fun detect(): LedController {
        val rk = Rk3576LedController()
        if (rk.available()) return rk
        return SysfsLedController()
    }
}
