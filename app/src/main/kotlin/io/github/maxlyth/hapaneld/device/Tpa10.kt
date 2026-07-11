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
    override val hasRecents = false   // Tuya firmware has no overview screen (KEYCODE_APP_SWITCH is a no-op)
    override val ledMechanism = LedMechanism.SYSFS_DAEMON
    override val screenOff = ScreenOff.DAEMON_BLPOWER
    override val zigbeeGatewayDir: String? = null
    override val relayBase: String? = null
    override val buttonLedGpioBase: Int? = null
    override val proximityTech: String? = "Time-of-Flight"
    override val proximityNearBelow: Boolean? = true   // ToF: a nearer object reads a *smaller* distance
    override val lightTech: String? = "Ambient light"
    // CHT8305 room temp/humidity on i2c bus 3 @0x40, reported via the input subsystem (root-owned nodes)
    // — read through the helper daemon's CHT8305 verb. See docs/hardware/tpa10.md.
    override val hasCht8305 = true
    // roomTempOffsetC stays 0 until self-heating is characterised over time; the user can trim live via
    // the room_temp_offset config key. (One spot reading — ~+1.9 °C vs room — is not yet a calibration.)
    override val manufacturer = "Tuya"
    override val model = "TPA10"
    // Curated tame suggestions (live-enumerated). "vendor" = the Tuya/Xinch "SmartOS" app suite; "chipset"
    // = Rockchip SoC apps. Deliberately EXCLUDES com.smartos.xinch.{systemui,launcher,provision,setting,
    // hardware} — those are the panel's actual SystemUI / home / setup / Settings / HAL bridge, and taming
    // one would brick the panel (note: the is_critical_pkg guard only knows com.android.systemui, not this
    // vendor-renamed one). Suggestions only — nothing is auto-disabled, every action is reversible.
    override val tameVendorCandidates = listOf(
        TameCandidate("com.smartos.xinch.smarthome", listOf("vendor", "smarthome"),
            "Xinch (SmartOS/Tuya) smart-home control app. Redundant on a Home Assistant panel; safe to disable."),
        TameCandidate("com.smartos.xinch.smartiot", listOf("vendor", "smarthome"),
            "Xinch (SmartOS/Tuya) IoT control app. Redundant alongside Home Assistant; safe to disable."),
        TameCandidate("com.smartos.xinch.communicate", listOf("vendor", "test"),
            "Xinch \"central-control interconnect\" app (label \"TEST-中控互联\"). Vendor demo/test; not used in normal operation."),
        TameCandidate("com.smartos.xinch.monitor", listOf("vendor", "test", "clutter"),
            "Xinch \"PerformanceMonitor\" diagnostic app. Not needed in normal operation."),
        TameCandidate("com.tuya.devicetest", listOf("chipset", "test", "clutter"),
            "Tuya factory device-test app (in /odm, persists across factory reset). Diagnostic only."),
    )
    // The 5th (orange) button is a gpio-key that reports SW_MUTE_DEVICE (switch code 14) on event8 —
    // an EV_SW latching toggle, NOT a key, which is why Android/keylayouts never surface it. Confirmed
    // by getevent on-device (2026-06). WATCH it as a switch (sw=true): each physical press flips the
    // toggle and we emit an HA event.
    override val evdevButtons = listOf(
        EvdevButton("/dev/input/event8", 14, grab = false, eventType = "KEYCODE_MUTE", sw = true),
    )
    override val cpuGovernors = mapOf("Performance" to "performance", "Efficiency" to "powersave", "Auto" to "schedutil")
    override val recommendedDensity: Int? = 226     // 10" 1920×1200 ≈ 226 ppi — natural 1:1 layout scale
    override val recommendedFontScale: Float? = 1.0f

    // LineageOS SystemWebView (armeabi-v7a; vanilla Chromium). Swapped off Cromite (2026-07-11): Cromite
    // patches the autoplay content-setting to BLOCK, which broke HA camera-card WebRTC autoplay in the
    // built-in renderer, and its releases have stalled — LineageOS is a newer, maintained, autoplay-ALLOW
    // engine on the same `com.android.webview` package + install route. Cromite 147 stays in the mirror as
    // the rollback asset. Signer pinned; sideloaded from the mirror.
    override val recommendedWebView = WebViewSpec(
        url = "https://github.com/maxlyth/ha-paneld/releases/download/webview-mirror/lineageos-webview-150.0.7871.63-arm.apk",
        version = "150.0.7871.63",
        certSha256 = "32a2fc74d731105859e5a85df16d95f102d85b22099b8064c5d8915c61dad1e0",
    )
}
