package io.github.maxlyth.hapaneld.device

/**
 * Fallback profile for an unrecognised panel. Declares no device-specific paths/quirks, so the
 * functional modules fall back to pure runtime probing — an unknown panel still advertises whatever it
 * physically has (LED via the rk3576/daemon probes, sensors via SensorManager, etc.). A real
 * `device/<panel>.kt` is only needed when a panel requires a quirk the generic path can't infer.
 */
object Generic : DeviceProfile {
    override val id = "generic"
    override val displayName = "Unknown panel"
    override val socClass = "?"
    override val suForm = SuForm.ANDROID    // try the common Android form; Su autodetects either way
    override val appCanSu = true            // optimistic; Su.available() confirms at runtime
    override val ledMechanism = LedMechanism.AUTODETECT   // probe everything; unknown panel may have a LED
    override val screenOff = ScreenOff.BRIGHTNESS_ZERO
    override val zigbeeGatewayDir: String? = null
    override val relayBase: String? = null
    override val buttonLedGpioBase: Int? = null
    override val manufacturer: String? = null   // infer from Build.MANUFACTURER
    override val model: String? = null           // infer from Build.MODEL
    override val evdevButtons = emptyList<EvdevButton>()   // unknown panel: a11y key capture only
}
