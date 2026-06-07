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
    // The 5th (orange) button is a gpio-key that reports SW_MUTE_DEVICE (switch code 14) on event8 —
    // an EV_SW latching toggle, NOT a key, which is why Android/keylayouts never surface it. Confirmed
    // by getevent on-device (2026-06). WATCH it as a switch (sw=true): each physical press flips the
    // toggle and we emit an HA event.
    override val evdevButtons = listOf(
        EvdevButton("/dev/input/event8", 14, grab = false, eventType = "KEYCODE_MUTE", sw = true),
    )
    override val cpuGovernors = mapOf("Performance" to "performance", "Efficiency" to "powersave", "Auto" to "schedutil")
}
