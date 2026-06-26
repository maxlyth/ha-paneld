package io.github.maxlyth.hapaneld.device

/**
 * Shelly Wall Display — modern generation (arm64-v8a, Android 11+).
 * Covers five SKUs that share the `WallDisplayV2` OTA track:
 *   SAWD-3A1XE10EU2 "Blake"    — Wall Display XL, 10.1", 2 relays, proximity (LD2410 mmWave radar), 4 buttons
 *   SAWD-4A1XE10US0 "Maverick" — Wall Display U1, US SKU, 1 relay, proximity (IR), power button
 *   SAWD-5A1XX10EU0 "Jenna"    — Wall Display X2i, 2 relays, proximity via /dev/input/event4 (gpio-keys), power button
 *   SAWD-6A1XX10EU0 "Cally"    — Wall Display XLi (also referred to as X1i), 2 relays, proximity, 4 buttons, power button
 *   SAWD-6A0XX0EU0  "Dayna"    — Wall Display D1, display-only (0 relays/inputs), proximity, power button
 *
 * Fingerprint: Build.MODEL / Build.DEVICE matches the device codename ("blake", "jenna", "cally",
 * "maverick", "dayna") OR the SKU string ("SAWD-3A1XE10EU2" etc.).
 *
 * DEPLOYMENT CONSTRAINT: No root access. Installation is possible via:
 *   (a) Shelly AppStore (firmware 2.6.0+, modern devices only) — preferred path; Shelly must approve the listing.
 *   (b) ADB with USB debugging enabled in developer options.
 * Setting ha-paneld as the default home triggers Android's launcher-chooser dialog once a second launcher
 * is installed; the Stargate system launcher cannot be uninstalled without root.
 *
 * RELAY NOTE: On-board relays are RPC-controlled via the Shelly Gen2 local HTTP API (switch:0, switch:1).
 * The Stargate app accesses them via broadcast intents (e.g. "cloud.shelly.blake.relay"); ha-paneld has no
 * direct relay path. Relay control must route through HA's Shelly integration. relayBase = null.
 *
 * PROXIMITY NOTE: Blake/XL uses an LD2410 mmWave radar chip (confirmed as the source of the Motion/Occupancy
 * component in firmware 2.6.0). Other models use IR proximity via gpio-keys. Neither is exposed via the
 * Android SensorManager (android.hardware.sensor.proximity not declared in the manifest). Without root,
 * proximity is unavailable to ha-paneld — wake-on-wave is not possible.
 *
 * SPECULATIVE: All fields are inferred from firmware inspection, ShellyElevate community source code, and
 * the Shelly changelog. None have been verified on live hardware. Confirm hasRecents, density,
 * and sensor availability before removing this note.
 */
object ShellyWallDisplayV2 : DeviceProfile {
    override val id = "shelly-wall-display-v2"
    override val displayName = "Shelly Wall Display (modern)"
    override val socClass = "arm64 (unverified)"
    override val suForm = SuForm.NONE
    override val appCanSu = false
    override val ledMechanism = LedMechanism.NONE
    override val screenOff = ScreenOff.BRIGHTNESS_ZERO
    override val zigbeeGatewayDir: String? = null
    override val efr32UartPath: String? = null
    override val relayBase: String? = null
    override val buttonLedGpioBase: Int? = null
    override val proximityTech: String? = "Infrared"     // Blake uses LD2410 radar; others IR — neither accessible without root
    override val lightTech: String? = "Ambient light"
    override val manufacturer = "Shelly"
    override val model = "Wall Display"
    override val tameVendorCandidates = emptyList<TameCandidate>()
    override val evdevButtons = emptyList<EvdevButton>()   // gpio-keys (/dev/input/event3–7) require root
    override val cpuGovernors: Map<String, String>? = null
    override val recommendedDensity: Int? = null     // TODO: verify per-model (XL 10.1" at ~1280×800 ≈ 149 ppi → 120 or 160?)
    override val recommendedFontScale: Float? = null
}
