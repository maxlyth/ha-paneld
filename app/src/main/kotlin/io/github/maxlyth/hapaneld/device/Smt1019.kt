package io.github.maxlyth.hapaneld.device

/**
 * ZHICAI SMT1019 (Rockchip rk3576, Android 14). Marketed "Generic SMT1019 / RK3576_64GB"; fingerprint
 * `ro.product.device` = `WF2489T`. Shares the rk3576 `rk3576_u` model code with the [Wf1589t], but is a
 * different, locked-down unit: it reports **no root** (`su` absent — `su -c id` exits 127) and its RGB
 * LED ioctl on `/dev/ledjni` is denied to sandboxed apps (`EACCES`/`rc=-13` on the ioctl; the node opens
 * O_RDONLY but the kernel rejects the ioctl — SELinux/uid-gated to privileged domains, where the
 * vendor's own system-privileged MQTT app works). So unlike the WF1589T there is no app-direct LED path
 * and no su to drive it: the LED is declared [LedMechanism.NONE] rather than mis-probed as present.
 * Reporter /diag: GitHub #8. Hardware reference: docs/hardware/smt1019.md
 */
object Smt1019 : DeviceProfile {
    override val id = "smt1019"
    override val displayName = "ZHICAI SMT1019"
    override val socClass = "rk3576"
    override val suForm = SuForm.NONE
    override val appCanSu = false
    override val ledMechanism = LedMechanism.NONE
    // No root and no helper daemon, so the bl_power screen-off paths are unreachable; fall to the
    // app-level brightness-zero path.
    override val screenOff = ScreenOff.BRIGHTNESS_ZERO
    override val zigbeeGatewayDir: String? = null
    override val relayBase: String? = null
    override val buttonLedGpioBase: Int? = null
    override val manufacturer = "ZHICAI"
    override val model = "SMT1019"
    // No root to grab evdev nodes, and no panel button the Android pipeline misses.
    override val evdevButtons = emptyList<EvdevButton>()
    // CPU governors are root-gated; this unit has no su, so leave tier mapping to runtime resolution.
    override val cpuGovernors: Map<String, String>? = null
    override val recommendedDensity: Int? = null
    override val recommendedFontScale: Float? = null
}
