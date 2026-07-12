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
    // Stub transfer for the /dev/ledjni LED — non-linear, 0..255-capable (probed 2026-07-11); currently
    // the safe 0..15 region. Replace with a measured per-channel curve to use more range. See LedTransfer.
    override val ledTransfer = io.github.maxlyth.hapaneld.hardware.LedTransfer.Rk3576FourBit
    override val screenOff = ScreenOff.SU_BLPOWER
    override val zigbeeGatewayDir: String? = null
    override val relayBase: String? = null
    override val buttonLedGpioBase: Int? = null
    override val manufacturer = "Electron"
    override val model = "WF1589T"
    // Curated tame suggestions (live-enumerated). ELC firmware + Rockchip rk3576 SoC — several entries are
    // the common Rockchip/ELC factory apps it shares with the SMT1019. Suggestions only; reversible.
    override val tameVendorCandidates = listOf(
        TameCandidate("com.cghs.stresstest", listOf("vendor", "test", "clutter"),
            "Factory hardware stress-test (\"burn-in\") tool (label \"Stresstest\", priv-app). Production-line QA; not used in normal operation."),
        TameCandidate("com.DeviceTest", listOf("chipset", "test", "clutter"),
            "Generic factory device-test app left in the firmware image. Diagnostic only."),
        TameCandidate("com.rockchip.devicetest", listOf("chipset", "test", "clutter"),
            "Rockchip (rk3576 SoC) factory device-test suite. Diagnostic only; not used in normal operation."),
        TameCandidate("com.gulukai.pwmlightdemo", listOf("vendor", "demo", "clutter"),
            "Vendor PWM-backlight/LED demo app (label \"PwmLightDemo\"). A demo, not the LED driver; safe to disable."),
        TameCandidate("com.elclcd.otaupdater", listOf("vendor", "ota"),
            "ELC \"Firmware Upgrade\" vendor OTA updater. Disable to stop the panel auto-applying vendor firmware updates that can re-add bloat."),
    )
    // The single physical button is the PMIC power key (rk805 pwrkey, event1 = KEY_POWER). GRAB it so
    // it no longer sleeps the panel; the press becomes an HA event for an automation to act on. The
    // PMIC's long-press hardware power-off is independent and unaffected.
    override val evdevButtons = listOf(
        EvdevButton("/dev/input/event1", 116, grab = true, eventType = "KEYCODE_POWER"),
    )
    override val cpuGovernors = mapOf("Performance" to "performance", "Efficiency" to "powersave", "Auto" to "schedutil")
    override val recommendedDensity: Int? = 212   // 1920×1200 10.1"; the firmware default (200) renders HA a touch small
    override val recommendedFontScale: Float? = null
}
