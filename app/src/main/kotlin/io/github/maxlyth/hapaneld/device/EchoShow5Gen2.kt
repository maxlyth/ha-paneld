package io.github.maxlyth.hapaneld.device

/**
 * Amazon Echo Show 5 (2nd generation, 2021), codename `cronos`, running the community LineageOS 18.1
 * Android 11 userdebug build. Stock Fire OS is not a supported installation path. Initial profile from
 * the reporter's diagnostics in GitHub #28; hardware-specific control paths remain deliberately empty
 * until they are measured on a unit.
 */
object EchoShow5Gen2 : DeviceProfile {
    override val id = "echo-show-5-gen2"
    override val displayName = "Amazon Echo Show 5 (2nd Gen)"
    override val socClass = "MediaTek MT8163"
    override val suForm = SuForm.ANDROID
    override val appCanSu = true
    override val ledMechanism = LedMechanism.NONE
    override val screenOff = ScreenOff.BRIGHTNESS_ZERO
    override val zigbeeGatewayDir: String? = null
    override val relayBase: String? = null
    override val buttonLedGpioBase: Int? = null
    override val lightTech: String? = "Ambient light"
    override val manufacturer = "Amazon"
    override val model = "Echo Show 5 (2nd Gen)"
    override val evdevButtons = emptyList<EvdevButton>()
    override val cpuGovernors: Map<String, String>? = null
    override val recommendedDensity: Int? = null
    override val recommendedFontScale: Float? = null
}
