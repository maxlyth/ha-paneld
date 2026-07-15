package io.github.maxlyth.hapaneld.device

/**
 * ZHICAI SMT1019 (Rockchip rk3576, Android 14). Marketed "Generic SMT1019 / RK3576_64GB"; fingerprint
 * `ro.product.device` = `WF2489T`. Shares the rk3576 `rk3576_u` model code with the [Wf1589t], but is a
 * different, locked-down unit: stock firmware reports **no root** (`su` absent — `su -c id` exits 127)
 * and its RGB LED ioctl on `/dev/ledjni` is denied to sandboxed apps (`EACCES`/`rc=-13`). Firmware
 * teardown (GitHub #8) confirmed why: the node is `system:system 0664` with the SELinux-generic
 * `device` label, and the vendor's own LED path (`libjnielc.so` / `com.example.elcapi.jnielc`) uses the
 * **identical** clean-room ioctls ha-paneld already knows — `0xa1/0xa2/0xa3` RGB, `0x99` off, 0..15. So
 * the LED works once something privileged issues the ioctl: not app-direct, but via the root helper
 * daemon ([LedMechanism.RK3576_IOCTL_DAEMON]). On a stock (un-rooted, daemon-less) unit the daemon
 * isn't reachable, so the LED entity is simply not published — no false capability.
 * Reporter /diag: GitHub #8. Hardware reference: docs/hardware/smt1019.md
 */
object Smt1019 : DeviceProfile {
    override val id = "smt1019"
    override val displayName = "ZHICAI SMT1019"
    override val socClass = "rk3576"
    override val suForm = SuForm.NONE
    override val appCanSu = false
    override val ledMechanism = LedMechanism.RK3576_IOCTL_DAEMON
    // Stock unit has no su, so the bl_power screen-off paths aren't the default; fall to the app-level
    // brightness-zero path. (A rooted unit also runs the helper daemon, which the LED uses; screen-off
    // is left on the no-root-safe path here.)
    override val screenOff = ScreenOff.BRIGHTNESS_ZERO
    override val zigbeeGatewayDir: String? = null
    override val relayBase: String? = null
    override val buttonLedGpioBase: Int? = null
    override val manufacturer = "ZHICAI"
    override val model = "SMT1019"
    // Curated tame suggestions, derived from the firmware image (ELC/Iiyama + Rockchip rk3576). "vendor" =
    // ELC's own apps; "chipset" = Rockchip. Suggestions only; reversible. `com.broadcastinterface` (a
    // headless, unlabelled vendor IPC bridge) is deliberately left out pending verification.
    override val tameVendorCandidates = listOf(
        TameCandidate("com.elc.smt_test", listOf("vendor", "test", "clutter"),
            "ELC \"smt-test\" factory app (in /odm persist, survives factory reset). Vendor QA/test tool; not used in normal operation."),
        TameCandidate("com.DeviceTest", listOf("chipset", "test", "clutter"),
            "Generic factory device-test app (in /odm persist). Diagnostic only."),
        TameCandidate("com.iiyama.webview.demo", listOf("vendor", "demo", "clutter"),
            "Iiyama WebView demo bundled in the firmware. Not used in normal operation."),
        TameCandidate("com.elclcd.commonkeepalive", listOf("vendor", "service"),
            "ELC \"CommonKeepAlive\" background service that keeps vendor apps resident. Safe to disable on a Home Assistant panel."),
        TameCandidate("com.elclcd.otaupdater", listOf("vendor", "ota"),
            "ELC \"Firmware Upgrade\" — the vendor OTA updater. Disable to stop the panel auto-applying vendor firmware updates that can re-add bloat."),
        TameCandidate("com.android.rockchip.camera2", listOf("chipset"),
            "Rockchip Camera2 / HDMI-in app (label \"HdmiIn\"). Safe to disable unless you use the camera or HDMI input."),
    )
    // No root to grab evdev nodes, and no panel button the Android pipeline misses.
    override val evdevButtons = emptyList<EvdevButton>()
    // CPU governors are root-gated; this unit has no su, so leave tier mapping to runtime resolution.
    override val cpuGovernors: Map<String, String>? = null
    override val recommendedDensity: Int? = null
    override val recommendedFontScale: Float? = null
    // LineageOS SystemWebView (arm64-v8a universal build; vanilla Chromium, autoplay-ALLOW,
    // com.android.webview). PROVISIONAL — SMT1019 is unverified hardware (rk3576 / Android 14, arm64, no
    // GMS). Net-new pin (none before), so this only adds the manual "Update WebView" option until verified.
    override val recommendedWebView = WebViewSpec(
        url = "https://github.com/maxlyth/ha-paneld/releases/download/webview-mirror/lineageos-webview-150.0.7871.63-arm64.apk",
        version = "150.0.7871.63",
        certSha256 = "32a2fc74d731105859e5a85df16d95f102d85b22099b8064c5d8915c61dad1e0",
        apkSha256 = "1319b1e76b4e1cb32d7019b6f7566ebb048e3c09bd0f344124122e58390b5939",
    )
}
