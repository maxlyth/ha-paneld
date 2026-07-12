package io.github.maxlyth.hapaneld.device

/**
 * Unbranded 15.6-inch Android 13 panel whose firmware identifies itself as ZX-SMT156 and whose exact
 * device fingerprint is `rk3566_t`. Initial profile from GitHub #24. The app can drive `/dev/ledjni`
 * directly despite the rk3566 SoC; no su, relay path, or vendor climate-sensor path is yet established.
 */
object ZxSmt156 : DeviceProfile {
    override val id = "zx-smt156"
    override val displayName = "ZX-SMT156 / RK3566_T"
    override val socClass = "Rockchip rk3566"
    override val suForm = SuForm.NONE
    override val appCanSu = false
    override val ledMechanism = LedMechanism.RK3576_IOCTL
    // Retain the conservative transfer used while this panel fell through Generic. A reporter can
    // replace it with a measured curve once the LED response is characterised.
    override val ledTransfer = io.github.maxlyth.hapaneld.hardware.LedTransfer.Rk3576FourBit
    override val screenOff = ScreenOff.BRIGHTNESS_ZERO
    override val zigbeeGatewayDir: String? = null
    override val relayBase: String? = null
    override val buttonLedGpioBase: Int? = null
    override val lightTech: String? = "Ambient light"
    override val manufacturer: String? = null
    override val model = "ZX-SMT156"
    override val evdevButtons = emptyList<EvdevButton>()
    override val cpuGovernors: Map<String, String>? = null
    override val recommendedDensity: Int? = null
    override val recommendedFontScale: Float? = null
}
