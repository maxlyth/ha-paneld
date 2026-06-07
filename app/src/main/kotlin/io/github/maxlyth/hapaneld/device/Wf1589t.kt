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
}
