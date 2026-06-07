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
}
