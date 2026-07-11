package io.github.maxlyth.hapaneld.hardware

import android.util.Log

/**
 * LED adapter for the rk3576 panel (office_dash / Electron WF1589T). Drives `/dev/ledjni` directly
 * through ha-paneld's own NDK ([NativeLed]) — clean-room ioctl, NO vendor `libjnielc.so`, no root,
 * no helper. The char device is world-rwx + app-accessible (SELinux `device` label).
 *
 * HA sends r/g/b as 0..255; the per-profile [transfer] maps each channel to the hardware value that
 * reproduces the requested colour on this panel's non-linear LED before the ioctl. [available] is gated
 * on the native lib loading AND the node being openable, so only the rk3576 panel reports the LED entity.
 */
class Rk3576LedController(
    private val transfer: LedTransfer = LedTransfer.Rk3576FourBit,
) : LedController {

    override fun available(): Boolean = NativeLed.available()

    override fun colorCapable(): Boolean = true

    override fun setRgb(r: Int, g: Int, b: Int) {
        val rc = NativeLed.nativeSetRgb(transfer.red(r), transfer.green(g), transfer.blue(b))
        if (rc != 0) Log.w(TAG, "setRgb failed rc=$rc") else Log.d(TAG, "rgb -> ($r,$g,$b)")
    }

    override fun off() {
        val rc = NativeLed.nativeOff()
        if (rc != 0) Log.w(TAG, "off failed rc=$rc")
    }

    companion object {
        private const val TAG = "ha-paneld/led-rk3576"
    }
}
