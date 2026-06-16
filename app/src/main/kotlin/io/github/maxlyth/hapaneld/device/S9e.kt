package io.github.maxlyth.hapaneld.device

/**
 * Smatek S9E (Rockchip rk3566, Android 11). ⚠️ UNVERIFIED on hardware — values from the vendor docs
 * (seaky#98 + the Smatek listing) and two stock firmware images (see docs/hardware/s9e.md). Has two
 * mains relays whose sysfs class was renamed across firmware — `/sys/class/strelay` on 1.1.0+,
 * `/sys/class/st_relay` on the initial 1.0.2 — and four button LEDs at gpio 147–150 (not exported at
 * boot; RelayController exports them). The firmware also carries an RGB led_r/g/b + a vibrator, both
 * currently unexposed. Detection keys on `ro.product.version` starting "S9"; relays/LEDs also surface
 * via runtime probe even if it falls back to [Generic].
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
    // The S9E hardware HAS Zigbee, but it's a Tuya/Smatek gateway — NOT Sonoff's siliconlabs_host
    // stack that ZigbeeController drives. Its path + control protocol are unknown and can't be guessed;
    // stays null until reverse-engineered on a real unit (then either set this, or add a Tuya path).
    override val zigbeeGatewayDir: String? = null
    // Firmware 1.1.0+ uses /sys/class/strelay; the initial 1.0.2 image used /sys/class/st_relay. Probe
    // strelay first (most panels in the field), fall back to st_relay. See docs/hardware/s9e.md.
    override val relayBase = "/sys/class/strelay"
    override val relayBaseFallbacks = listOf("/sys/class/st_relay")
    override val buttonLedGpioBase = 147
    override val manufacturer = "Smatek"
    override val model = "S9E"
    override val evdevButtons = emptyList<EvdevButton>()
    override val cpuGovernors = mapOf("Performance" to "performance", "Efficiency" to "powersave", "Auto" to "schedutil")
    override val recommendedDensity: Int? = null
    override val recommendedFontScale: Float? = null
}
