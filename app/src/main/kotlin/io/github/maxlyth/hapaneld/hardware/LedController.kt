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
 * Picks the LED backend for this panel:
 * - rk3576: NDK ioctl on the app-accessible `/dev/ledjni` (probed by opening the node) — app-direct.
 * - sysfs-LED panels (e.g. TPA10): the [SocketLedController], which talks to the root helper daemon
 *   (`helper/hapaneld-ledd`) over loopback. A sandboxed app cannot write `sysfs_lights` nor exec
 *   `su` (SELinux `untrusted_app`; confirmed on TPA10), so the privilege lives in the daemon.
 *
 * `detect()` returns the socket controller without probing; the bridge later calls `available()`
 * (off the main thread), which returns false when the daemon isn't running → the LED entity is
 * skipped. A panel with neither a node nor the daemon yields no LED entity.
 */
object LedFactory {
    fun detect(): LedController {
        val rk = Rk3576LedController()
        if (rk.available()) return rk
        return SocketLedController()
    }
}
