package io.github.maxlyth.hapaneld.device

/**
 * Shelly Wall Display — legacy generation (MediaTek MT6580, Android 7, armeabi-v7a).
 * Covers three SKUs that share the same `WallDisplay` OTA track:
 *   SAWD-0A1XX10EU1 "Stargate" — 3.97" / 4" LCD, 1 relay, no proximity
 *   SAWD-1A1XX10EU1 "Atlantis" — undocumented variant; 1 relay, has IR proximity (from ShellyElevate)
 *   SAWD-2A1XX10EU1 "Pegasus"  — 6.9" LCD, 2 relays, has IR proximity
 *
 * Fingerprint: Build.MODEL / Build.DEVICE matches the device codename ("stargate", "pegasus", "atlantis")
 * OR the SKU string ("SAWD-0A1XX10EU1" etc.) OR Build.DEVICE contains "k400_mt6580" / "e500_7731e".
 *
 * CONFIRMED from firmware (WallDisplay 1.2.1 + 2.7.1, parsed 2026-06-28):
 *   - SoC: MediaTek MT6580, Android **7.0** (NRD90M), `ro.product.device=k400_mt6580_32_n`, userdebug build
 *     (so `adb root` is likely available during setup). armeabi-v7a. MTK scatter partition layout.
 *   - The Stargate app (`cloud.shelly.stargate`) is the **Device Owner** with full device-admin policies AND
 *     the **home launcher** (MainActivity = HOME+LAUNCHER) — same as the modern track; replacing it is hard.
 *   - WebView: a separate `SAWD-0A1XX10EU1-WebViewUpdate.zip` installs `com.google.android.webview`
 *     **119.0.6045.194** (Chrome 119, maxSdk 28) — the only official Shelly WebView update, Stargate SKU only.
 *
 * DEPLOYMENT CONSTRAINT: These devices have no root and no AppStore (pre-2.6.0 feature). Installation
 * requires ADB (USB debugging enabled in device developer options). Sideloading via `adb install` and
 * setting ha-paneld as default home via Android Settings is the only practical path.
 *
 * RELAY NOTE: On-board relays are controlled via the Shelly Gen2 RPC API (switch:0, switch:1) from
 * within the Stargate app (no exported relay broadcast intents). ha-paneld has no direct sysfs relay path —
 * relay control must go through HA's Shelly integration (local HTTP API). relayBase = null.
 *
 * PROXIMITY NOTE: The Stargate manifest declares only `android.hardware.sensor.light` (ALS), NOT proximity.
 * Pegasus/Atlantis have an IR proximity sensor via gpio-keys (/dev/input/event*) that requires root to read.
 * Proximity wake-on-wave is unavailable; ambient light (ALS) is SensorManager-readable.
 *
 * SPECULATIVE: Per-model details (relay counts, which SKUs have proximity) are inferred from community
 * sources (ShellyElevate). Confirm display density and hasRecents on live hardware before removing this note.
 */
object ShellyWallDisplay : DeviceProfile {
    override val id = "shelly-wall-display"
    override val displayName = "Shelly Wall Display (legacy)"
    override val socClass = "MediaTek MT6580"
    override val suForm = SuForm.NONE
    override val appCanSu = false
    override val ledMechanism = LedMechanism.NONE
    override val screenOff = ScreenOff.BRIGHTNESS_ZERO
    override val zigbeeGatewayDir: String? = null
    override val efr32UartPath: String? = null
    override val relayBase: String? = null
    override val buttonLedGpioBase: Int? = null
    override val proximityTech: String? = null       // Stargate: none; Pegasus/Atlantis: IR via gpio-keys, not accessible without root
    override val lightTech: String? = "Ambient light"
    override val manufacturer = "Shelly"
    override val model = "Wall Display"
    override val tameVendorCandidates = emptyList<TameCandidate>()
    override val evdevButtons = emptyList<EvdevButton>()   // gpio-keys require root
    override val cpuGovernors: Map<String, String>? = null
    override val recommendedDensity: Int? = null     // TODO: verify on hardware (~170 ppi on 3.97" at 480×480 → probably 160)
    override val recommendedFontScale: Float? = null
}
