package io.github.maxlyth.hapaneld.device

/**
 * Tuya TPA10 (Rockchip rk3566, Android 11). Fingerprint: model `TPA10` / device `tpa10`.
 * A normal app CANNOT exec `su` (untrusted_app SELinux domain) — privileged writes go through the root
 * helper daemon. RGB LED is the sysfs `avsux` node, driven via that daemon. No managed Zigbee gateway.
 * Hardware reference: docs/hardware/tpa10.md
 */
object Tpa10 : DeviceProfile {
    override val id = "tpa10"
    override val displayName = "Tuya TPA10"
    override val socClass = "rk3566"
    override val suForm = SuForm.ANDROID
    override val appCanSu = false
    override val ledMechanism = LedMechanism.SYSFS_DAEMON
    override val screenOff = ScreenOff.DAEMON_BLPOWER
    override val zigbeeGatewayDir: String? = null
    override val relayBase: String? = null
    override val buttonLedGpioBase: Int? = null
    override val manufacturer = "Tuya"
    override val model = "TPA10"
    // The 5th (orange) button is a standalone adc-key that stock firmware maps to KEY_MICMUTE on
    // event6 (per the device-tree adc-keys0/func-key + getevent -p). Android doesn't deliver it
    // usefully to the app, so WATCH it (no grab) and surface presses as an HA event. PROVISIONAL:
    // derived from the stock keymap, not yet confirmed on a stock unit (the dev's TPA10 has modified
    // firmware that remaps its buttons). Watch-only, so a wrong node/code is harmless — it just never
    // fires; a stock TPA10 can confirm or correct event6/248.
    override val evdevButtons = listOf(
        EvdevButton("/dev/input/event6", 248, grab = false, eventType = "KEYCODE_MUTE"),
    )
    override val cpuGovernors = mapOf("Performance" to "performance", "Efficiency" to "powersave", "Auto" to "schedutil")
}
