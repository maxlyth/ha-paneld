package io.github.maxlyth.hapaneld.hardware

import android.util.Log
import io.github.maxlyth.hapaneld.util.HelperClient

/**
 * LED adapter for panels whose LED is root-only sysfs (e.g. Tuya TPA10). A sandboxed app
 * (`untrusted_app`) cannot write the `sysfs_lights` node nor exec `su`, so the root helper daemon
 * ([helper/hapaneld-ledd](../../../../../../../helper/README.md)) does the write; this controller
 * talks to it via [HelperClient] over loopback. Full RGB.
 *
 * [available] returns true only when the daemon answers `PING`, so the LED entity is published only
 * on panels provisioned with the helper. Calls are blocking socket I/O — the bridge invokes them
 * off the main thread.
 */
class SocketLedController : LedController {

    override fun available(): Boolean = HelperClient.available()

    override fun colorCapable(): Boolean = true

    override fun setRgb(r: Int, g: Int, b: Int) {
        if (HelperClient.send("RGB ${clamp(r)} ${clamp(g)} ${clamp(b)}") != "OK") Log.w(TAG, "setRgb failed")
    }

    override fun off() {
        if (HelperClient.send("OFF") != "OK") Log.w(TAG, "off failed")
    }

    private fun clamp(v: Int) = v.coerceIn(0, 255)

    companion object {
        private const val TAG = "ha-paneld/led-socket"
    }
}
