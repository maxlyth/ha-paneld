package io.github.maxlyth.hapaneld.device

/**
 * Electron WF1589T (Rockchip rk3576, Android 14 userdebug). Fingerprint: device `WF1589T` /
 * model `rk3576_u`. App-direct RGB LED via the clean-room `/dev/ledjni` ioctl (no root needed for the
 * LED). userdebug, so `su` is available. No managed Zigbee gateway.
 * Hardware reference: docs/hardware/wf1589t.md
 */
object Wf1589t : DeviceProfile {
    override val id = "wf1589t"
    override val displayName = "Electron WF1589T"
    override val socClass = "rk3576"
    override val suForm = SuForm.ANDROID
    override val appCanSu = true
    override val ledMechanism = LedMechanism.RK3576_IOCTL
    override val screenOff = ScreenOff.SU_BLPOWER
    override val zigbeeGatewayDir: String? = null
    override val relayBase: String? = null
    override val buttonLedGpioBase: Int? = null
    override val manufacturer = "Electron"
    override val model = "WF1589T"
    // The single physical button is the PMIC power key (rk805 pwrkey, event1 = KEY_POWER). GRAB it so
    // it no longer sleeps the panel; the press becomes an HA event for an automation to act on. The
    // PMIC's long-press hardware power-off is independent and unaffected.
    override val evdevButtons = listOf(
        EvdevButton("/dev/input/event1", 116, grab = true, eventType = "KEYCODE_POWER"),
    )
    override val cpuGovernors = mapOf("Performance" to "performance", "Efficiency" to "powersave", "Auto" to "schedutil")
}
