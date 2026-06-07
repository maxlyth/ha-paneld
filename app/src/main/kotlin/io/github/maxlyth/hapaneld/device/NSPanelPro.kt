package io.github.maxlyth.hapaneld.device

/**
 * Sonoff NSPanel Pro (Rockchip PX30 / rk3326, Android 8.1). Fingerprint: model/device `px30_evb`.
 * Toolbox `su -c` is reachable from the app. No RGB LED node. Has a Silicon Labs EFR32 Zigbee gateway
 * managed via the on-device Sonoff stack in `/vendor/bin/siliconlabs_host`.
 * Hardware reference: docs/hardware/nspanel-pro.md
 */
object NSPanelPro : DeviceProfile {
    override val id = "nspanel-pro"
    override val displayName = "Sonoff NSPanel Pro"
    override val socClass = "PX30 / rk3326"
    override val suForm = SuForm.TOOLBOX
    override val appCanSu = true
    override val ledMechanism = LedMechanism.NONE
    override val screenOff = ScreenOff.SU_BLPOWER
    override val zigbeeGatewayDir = "/vendor/bin/siliconlabs_host"
    override val relayBase: String? = null
    override val buttonLedGpioBase: Int? = null
    override val manufacturer = "Sonoff"
    override val model = "NSPanel Pro"
    override val evdevButtons = emptyList<EvdevButton>()
    // PX30 offers no schedutil; its load-following governor is interactive.
    override val cpuGovernors = mapOf("Performance" to "performance", "Efficiency" to "powersave", "Auto" to "interactive")
}
