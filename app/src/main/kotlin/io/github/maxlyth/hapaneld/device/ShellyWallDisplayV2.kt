package io.github.maxlyth.hapaneld.device

/**
 * Shelly Wall Display — modern generation (arm64-v8a userspace, Android 11).
 * Covers the SKUs that share the `WallDisplayV2` OTA track:
 *   "Jenna"    — Wall Display X2i — CONFIRMED Rockchip PX30 / Android 11 (firmware parse 2026-06-28)
 *   "Blake"    — Wall Display XL, 10.1" — SoC unconfirmed (10.1" panel may differ from the PX30 below)
 *   "Maverick" — Wall Display U1 (US SKU)
 *   "Cally"    — Wall Display XLi / X1i
 *   "Dayna"    — Wall Display D1 (display-only)
 * (SKU-string ↔ codename ↔ screen-size mapping in older notes is unreliable — a vendor OTA labelled
 * "SAWD-3A1XE10EU2" actually targets `ro.product.device=Jenna`. Trust the codename, not the SKU prefix.)
 *
 * Fingerprint: Build.MODEL / Build.DEVICE matches the device codename ("blake", "jenna", "cally",
 * "maverick", "dayna") OR the SKU string ("SAWD-3A1XE10EU2" etc.).
 *
 * CONFIRMED from firmware (Jenna, WallDisplayV2 2.7.1 + JennaUpdateSDIO partition OTA, parsed 2026-06-28):
 *   - SoC: Rockchip PX30 (DTB `rockchip,px30-evb-ddr3`) — the SAME chip as the Sonoff NSPanel Pro. The
 *     "arm64" is just a 64-bit userspace on PX30's Cortex-A35 cores. Kernel Linux 4.19.232 (built by Allterco).
 *   - Android 11 (API 30, RD2A.211001.002), **userdebug** build → `adb root` is likely available during setup.
 *   - ODM is **Smatek** — same manufacturer as the S9E (DTB carries a `smatek-keep-relay` driver). See [S9e].
 *   - Display (Jenna): 720×1440 portrait MIPI-DSI, ~5.5" (68×121 mm). Touch: Goodix GT9xx.
 *   - Camera present (Camera2.apk + manifest `android.hardware.camera`); WebRTC libs bundled (libjingle).
 *
 * DEPLOYMENT CONSTRAINTS:
 *   - The Stargate app (`cloud.shelly.stargate`) is provisioned as **Device Owner** with full device-admin
 *     policies AND is the **home launcher** (MainActivity = HOME+LAUNCHER). Replacing it / setting ha-paneld
 *     as default home is hard — expect the launcher-chooser, and Stargate can't be uninstalled without root.
 *   - No app-runtime root (`appCanSu=false`). Install via Shelly AppStore (firmware 2.6.0+) or ADB.
 *
 * RELAY NOTE: On-board relays are physically **GPIO** relays driven by the Smatek `smatek-keep-relay`
 * latching kernel driver (Jenna: GPIO 18/19 + a detect line on GPIO 20), NOT a sysfs class ha-paneld can
 * reach without root, and there are no exported relay broadcast intents (Stargate drives them internally via
 * Gen2 RPC). So relayBase = null and relay control routes through HA's Shelly integration.
 *
 * PROXIMITY NOTE: Jenna carries an **STK3A5x** light+proximity combo at i2c 0x46 — proximity ENABLED in the
 * kernel — the SAME sensor family as the NSPanel Pro (where ha-paneld reads proximity via SensorManager).
 * The Stargate manifest doesn't declare `sensor.proximity`, but feature declarations are advisory: the
 * SensorManager TYPE_PROXIMITY path likely still works, so wake-on-wave may be possible. NEEDS hardware
 * confirmation. (Blake/XL's "Motion" component is a separate UART mmWave radar driven by Stargate's
 * libserial_port.so — that one is genuinely not reachable without root.) Ambient light (STK3A5x ALS) is
 * declared (`sensor.light`) and SensorManager-readable.
 *
 * SPECULATIVE (still): Blake/Cally/Maverick/Dayna hardware is inferred, not parsed — only Jenna is confirmed.
 * Confirm hasRecents and density on live hardware before removing this note.
 */
object ShellyWallDisplayV2 : DeviceProfile {
    override val id = "shelly-wall-display-v2"
    override val displayName = "Shelly Wall Display (modern)"
    override val socClass = "PX30 / rk3326 (Jenna confirmed; others unverified)"
    override val suForm = SuForm.NONE
    override val appCanSu = false
    override val ledMechanism = LedMechanism.NONE   // DTB: pwm-backlight only, no RGB LED node
    override val screenOff = ScreenOff.BRIGHTNESS_ZERO
    override val zigbeeGatewayDir: String? = null
    override val relayBase: String? = null
    override val buttonLedGpioBase: Int? = null
    override val proximityTech: String? = "Infrared"     // Jenna: STK3A5x reflective-IR combo (like NSPanel Pro); likely SensorManager-readable
    override val lightTech: String? = "Ambient light"    // STK3A5x ALS, declared + SensorManager-readable
    override val manufacturer = "Shelly"
    override val model = "Wall Display"
    override val tameVendorCandidates = emptyList<TameCandidate>()
    override val evdevButtons = emptyList<EvdevButton>()   // gpio-keys (/dev/input/event*) require root
    override val cpuGovernors: Map<String, String>? = null
    override val recommendedDensity: Int? = null     // TODO: verify per-model (Jenna 720×1440 ~5.5" ≈ 300 ppi → ~320 native)
    override val recommendedFontScale: Float? = null
}
