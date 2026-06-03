package io.github.maxlyth.hapaneld.hardware

import android.util.Log
import com.example.elcapi.jnielc

/**
 * LED adapter for the rk3576 panel (office_dash / Electron WF1589T). Drives the vendor
 * `libjnielc.so` (`/dev/ledjni` + ioctl) via the [jnielc] clean-room binding. Load-if-present:
 * if the operator hasn't installed the .so, [available] is false and the LED entity is skipped.
 *
 * HA sends r/g/b as 0..255; the panel's PWM is 4-bit (0..15), so each channel is scaled ×15/255.
 */
class Rk3576LedController : LedController {

    private val loaded: Boolean = try {
        System.loadLibrary("jnielc")
        true
    } catch (e: Throwable) {
        Log.i(TAG, "libjnielc.so not present — LED capability disabled on this panel")
        false
    }

    override fun available(): Boolean = loaded

    override fun colorCapable(): Boolean = true

    override fun setRgb(r: Int, g: Int, b: Int) {
        if (!loaded) return
        try {
            jnielc.seekstart()
            jnielc.ledseek(0xa1, scale(r))
            jnielc.ledseek(0xa2, scale(g))
            jnielc.ledseek(0xa3, scale(b))
            jnielc.seekstop()
            Log.d(TAG, "rgb -> ($r,$g,$b)")
        } catch (e: Throwable) {
            Log.w(TAG, "ledseek failed", e)
        }
    }

    override fun off() {
        if (!loaded) return
        try {
            jnielc.ledoff()
        } catch (e: Throwable) {
            Log.w(TAG, "ledoff failed", e)
        }
    }

    private fun scale(v: Int): Int = (v.coerceIn(0, 255) * 15) / 255

    companion object {
        private const val TAG = "ha-paneld/led"
    }
}
