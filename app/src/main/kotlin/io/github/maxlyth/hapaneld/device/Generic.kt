package io.github.maxlyth.hapaneld.device

/**
 * Fallback profile for an unrecognised panel. It permits only generic runtime probes such as Android
 * sensors, available CPU governors and known LED transports. Vendor paths and mappings remain absent:
 * relays, radios, evdev buttons and climate protocols need an exact profile before they are exposed.
 */
object Generic : DeviceProfile {
    override val id = "generic"
    override val displayName = "Unknown panel"
    override val socClass = "?"
    override val suForm = SuForm.ANDROID    // try the common Android form; Su autodetects either way
    override val appCanSu = true            // optimistic; Su.available() confirms at runtime
    override val ledMechanism = LedMechanism.AUTODETECT   // probe only the known generic LED transports
    // If AUTODETECT resolves to the rk3576 ioctl LED, use the safe stub transfer (correct colour). See LedTransfer.
    override val ledTransfer = io.github.maxlyth.hapaneld.hardware.LedTransfer.Rk3576FourBit
    override val screenOff = ScreenOff.BRIGHTNESS_ZERO
    override val zigbeeGatewayDir: String? = null
    override val relayBase: String? = null
    override val buttonLedGpioBase: Int? = null
    override val manufacturer: String? = null   // infer from Build.MANUFACTURER
    override val model: String? = null           // infer from Build.MODEL
    override val evdevButtons = emptyList<EvdevButton>()   // unknown panel: a11y key capture only
    override val cpuGovernors: Map<String, String>? = null  // resolve from runtime-available governors
    override val recommendedDensity: Int? = null
    override val recommendedFontScale: Float? = null
}
