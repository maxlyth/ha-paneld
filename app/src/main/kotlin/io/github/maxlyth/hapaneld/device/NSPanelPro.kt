package io.github.maxlyth.hapaneld.device

import io.github.maxlyth.hapaneld.util.SystemProps

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
    // No relays on Gen1 NSPanel Pro (86P/120P). The PX30 kernel exposes /sys/class/st_relay with FOUR
    // PHANTOM nodes (relay1-4 + mode) on every variant regardless of physical population — verified on a
    // 120P that has zero physical relays (2026-06-08), and there's no per-node "present" attribute to
    // filter on, so a sysfs probe over-reports. Left null. Gen2 ADDS 2 real relays (a new feature); when
    // adding Gen2 support, declare a fixed relay count for it — do NOT sysfs-probe this class.
    override val relayBase: String? = null
    override val buttonLedGpioBase: Int? = null
    // IR reflective proximity + ambient-light (the standard combo ALS/proximity IC class). Inferred from
    // the graded-reflectance behaviour + the firmware "proximity distance" quirk, not a teardown — the HAL
    // exposes no chipset. Correct here if a teardown identifies the part (e.g. "Infrared (STK3338)").
    override val proximityTech: String? = "Infrared"
    override val lightTech: String? = "Ambient light"

    // Seaky firmware quirk (github.com/seaky/nspanel_pro_roottool_apk): a new kernel proximity driver lands
    // at fw >= 3.0.0 (86P) / >= 3.5.0 (120P) and drops graded distance → BINARY; below that it's GRADED.
    // ro.product.version is `s6_android_x.y.z` on the 86P and `NSPanel120P_x.y.z` on the 120P.
    override fun proximityGradedForFirmware(productVersion: String): Boolean? {
        val fw = firmwareVersion(productVersion) ?: return null
        val threshold = if ("120" in productVersion.substringBefore('_')) "3.5.0" else "3.0.0"
        return verLt(fw, threshold)
    }

    override val manufacturer = "Sonoff"
    override val model = "NSPanel Pro"
    override fun panelModelLabel(productVersion: String): String = when {
        productVersion.startsWith(S6_VERSION_PREFIX, ignoreCase = true) ->
            "NSPanel 86P" + firmwareVersion(productVersion)?.let { " · fw $it" }.orEmpty()
        productVersion.startsWith("NSPanel", ignoreCase = true) -> {
            val suffix = productVersion.substringBefore('_').drop("NSPanel".length)
            if (suffix.isBlank()) displayName
            else "NSPanel $suffix" + firmwareVersion(productVersion)?.let { " · fw $it" }.orEmpty()
        }
        else -> displayName
    }

    private fun firmwareVersion(productVersion: String): String? = when {
        productVersion.startsWith(S6_VERSION_PREFIX, ignoreCase = true) -> productVersion.drop(S6_VERSION_PREFIX.length)
        productVersion.startsWith("NSPanel", ignoreCase = true) -> productVersion.substringAfter('_', "")
        else -> ""
    }.ifBlank { null }
    // Curated, annotated tame suggestions. "vendor" = Sonoff/eWeLink's own apps; "chipset" = Rockchip
    // (the PX30 SoC vendor) factory/demo apps that small-production panels routinely ship — they look
    // alarming but are safe to disable. All are suggestions only; nothing is auto-disabled, and every
    // action is reversible. Listed even if a given firmware lacks one (absent packages drop out silently).
    // eWeLink + the factory burn-in/test tools are `defaultTame` — a panel run as an HA dashboard almost
    // always wants them gone (eWeLink draws over the dashboard; the test tools are dead weight). The demo
    // video player stays OFF (a deliberate per-package choice, not clutter to strip by default).
    override val tameVendorCandidates = listOf(
        TameCandidate("com.eWeLinkControlPanel", listOf("vendor", "overlay", "boot"),
            "eWeLink/Sonoff control-panel app. After some Sonoff firmware updates it starts on boot and draws a floating widget over the dashboard.", defaultTame = true),
        TameCandidate("com.android.rk", listOf("chipset", "test"),
            "Rockchip (PX30 SoC) factory/test app left in the firmware image. Not used by the panel in normal operation.", defaultTame = true),
        TameCandidate("android.rk.RockVideoPlayer", listOf("chipset", "demo"),
            "Rockchip demo video player bundled with the SoC image. Safe to disable unless you actually use it."),
        TameCandidate("com.cghs.stresstest", listOf("vendor", "test", "clutter"),
            "Factory hardware stress-test (\"burn-in\") tool (app label \"Stresstest for 8.1\"). Used on the production line to soak the panel; not needed in normal operation.", defaultTame = true),
        TameCandidate("com.smatek.test", listOf("vendor", "test", "clutter"),
            "CoolKit (eWeLink/Sonoff) factory test tool (label \"测试工具\" = \"Test Tool\"), bundled in /oem so it survives a factory reset. Diagnostic only — not used in normal operation.", defaultTame = true),
        TameCandidate("com.rockchip.devicetest", listOf("chipset", "test", "clutter"),
            "Rockchip (PX30 SoC) factory device-test suite. Diagnostic only; not used by the panel in normal operation.", defaultTame = true),
        TameCandidate("com.DeviceTest", listOf("vendor", "test", "clutter"),
            "Factory device-test app (privileged system, label \"DeviceTest(android8.0)\") left in the firmware image. Diagnostic only — not used in normal operation.", defaultTame = true),
    )
    override val evdevButtons = emptyList<EvdevButton>()
    // PX30 offers no schedutil; its load-following governor is interactive.
    override val cpuGovernors = mapOf("Performance" to "performance", "Efficiency" to "powersave", "Auto" to "interactive")
    // Recommended display density + text scale per variant — the "rec" button on the info page applies
    // these. 86P (480×480) reads best at 160 dpi; the larger 120P at 250. The Gen2 86P reports an 86P
    // product string, so the "120" check covers it (→ 160). Text scale 1.0 across the board. Read lazily so
    // ro.product.version is available (post-boot).
    override val recommendedDensity: Int? by lazy {
        if ("120" in SystemProps.get("ro.product.version").substringBefore('_')) 250 else 160
    }
    override val recommendedFontScale: Float? = 1.0f

    // HA Companion 2026.6.5+ crash-loops on this PX30/Android 8.1 base (missing CarUxRestrictionsManager
    // class → NoClassDefFoundError, classloader poisoned). Pin the auto-updater to the last good build.
    override val companionMaxVersion = "2026.5.4"

    // LineageOS Android System WebView — the last build that installs on Android 8.1 (Chromium 138).
    // The stock ~107 renders the HA dashboard blank. Signer pinned; sideloaded from the mirror.
    override val recommendedWebView = WebViewSpec(
        url = "https://github.com/maxlyth/ha-paneld/releases/download/webview-mirror/lineageos-webview-138.0.7204.63.apk",
        version = "138.0.7204.63",
        certSha256 = "518325ef7f96c0d1194c2e856b040d636166ffb846717d72fa87f4fae5be7bbb",
    )

    private const val S6_VERSION_PREFIX = "s6_android_"
}

/** True if dotted-numeric version [a] < [b] (e.g. "1.2.6" < "3.0.0"). Non-numeric/missing parts → 0. */
private fun verLt(a: String, b: String): Boolean {
    val pa = a.split('.'); val pb = b.split('.')
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val x = pa.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
        val y = pb.getOrNull(i)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
        if (x != y) return x < y
    }
    return false
}
