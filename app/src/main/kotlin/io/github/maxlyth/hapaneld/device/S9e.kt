package io.github.maxlyth.hapaneld.device

/**
 * Smatek S9E (Rockchip rk3566, Android 11). ⚠️ UNVERIFIED on hardware — values from the vendor docs
 * (seaky#98 + the Smatek listing). Has two mains relays at `/sys/class/st_relay` and four button LEDs
 * at gpio 147–150; no RGB LED node. Fingerprint guess (model/device contains "s9e") — but its relays
 * surface via runtime probe even if it falls back to [Generic], so getting the fingerprint exact is not
 * critical until a unit is available.
 * Hardware reference: docs/hardware/s9e.md
 */
object S9e : DeviceProfile {
    override val id = "s9e"
    override val displayName = "Smatek S9E"
    override val socClass = "rk3566"
    override val suForm = SuForm.ANDROID
    override val appCanSu = true            // vendor uses execRootCmd; unconfirmed for a normal app
    override val ledMechanism = LedMechanism.NONE
    override val screenOff = ScreenOff.BRIGHTNESS_ZERO
    override val zigbeeGatewayDir: String? = null
    override val relayBase = "/sys/class/st_relay"
    override val buttonLedGpioBase = 147
}
