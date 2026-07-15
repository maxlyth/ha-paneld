package io.github.maxlyth.hapaneld.device

import android.os.Build
import io.github.maxlyth.hapaneld.util.SystemProps

/**
 * Per-platform canonical silo. Everything device/platform-specific that the generic functional modules
 * need — su form, LED mechanism, screen-off path, and the sysfs/vendor locations of optional hardware —
 * is declared here, one `object` per platform (see `NSPanelPro`, `Tpa10`, `Wf1589t`, `Smt1019`, `S9e`,
 * `ShellyWallDisplay`, `ShellyWallDisplayV2`, `EchoShow5Gen2`, `ZxSmt156`, `Generic`).
 *
 * Design rule (see docs/architecture/device-profiles.md): a profile declares **candidates + quirks**;
 * the functional modules still **runtime-probe to confirm** whenever the platform exposes a probe
 * (profile says *where to look*, the probe says *whether it's actually there*). [Generic] deliberately
 * keeps unknown hardware conservative: standard Android sensors and generic LED/CPU routes can be
 * probed, while relays, evdev buttons and vendor protocols stay absent until their paths are known.
 */
interface DeviceProfile {
    /** Stable id, e.g. "nspanel-pro" / "tpa10" / "generic". */
    val id: String

    /** Human label for the info page / diagnostics. */
    val displayName: String

    /** SoC class, e.g. "PX30 / rk3326". */
    val socClass: String

    /** Which `su` invocation form works on this platform (or [SuForm.NONE] if the app can't reach su). */
    val suForm: SuForm

    /** Whether a normal app process can exec `su` (false on sandbox-walled panels like the TPA10, which
     *  need the root helper daemon for privileged writes). */
    val appCanSu: Boolean

    /** Whether full profile behavior relies on `helper/hapaneld-helper`. Every sandbox-walled panel needs
     *  it for privileged controls, but app-su panels can need it too: WF1589T's evdev power button is the
     *  important counterexample. This is a diagnostic requirement, not a routing gate; live controllers
     *  still fall through between safe routes when the preferred transport is unavailable. */
    val usesDaemon: Boolean get() =
        !appCanSu ||
            ledMechanism == LedMechanism.SYSFS_DAEMON ||
            ledMechanism == LedMechanism.RK3576_IOCTL_DAEMON ||
            screenOff == ScreenOff.DAEMON_BLPOWER ||
            hasButtonBacklight ||
            evdevButtons.isNotEmpty()

    /** Curated, annotated packages for THIS panel's "Recommended" list in the Vendor-packages card — each a
     *  [TameCandidate] carrying its tags (e.g. "vendor"/"chipset"/"test"/"overlay") and a one-line note from
     *  the profile author explaining what it is and why it's flagged, so a non-engineer gets real context.
     *  *Suggestions only*, never auto-acted. Packages absent from this firmware are dropped silently, so list
     *  the union across firmware versions — no per-release sets needed. Default empty. */
    val tameVendorCandidates: List<TameCandidate> get() = emptyList()

    /** Whether the firmware provides a working Recents/Overview screen. Single-purpose panel images often
     *  ship none (verified 2026-06-10: KEYCODE_APP_SWITCH is a no-op on the Tuya TPA10), so the navbar's
     *  Recents button is omitted where false rather than presenting a dead control. Default true. */
    val hasRecents: Boolean get() = true

    /** How the RGB LED is driven, if any. */
    val ledMechanism: LedMechanism

    /** A distinct monochrome button-backlight node driven by the helper's `BTN` command. Do not infer
     *  this from a daemon-backed RGB controller: SMT1019 uses that controller for `/dev/ledjni` but has
     *  no `/sys/class/leds/button-backlight` node. */
    val hasButtonBacklight: Boolean get() = false

    /** Per-channel LED transfer function (requested 0..255 → hardware value), correcting the panel's
     *  non-linear LED response. Only consumed on the rk3576 ioctl path. Default = passthrough; rk3576
     *  profiles override with a curve for their LED. See [io.github.maxlyth.hapaneld.hardware.LedTransfer]. */
    val ledTransfer: io.github.maxlyth.hapaneld.hardware.LedTransfer
        get() = io.github.maxlyth.hapaneld.hardware.LedTransfer.Identity

    /** Preferred true-screen-off path (runtime tiering still falls back as needed). */
    val screenOff: ScreenOff

    /** Sonoff Zigbee gateway dir, or null if the panel has no managed Zigbee gateway. */
    val zigbeeGatewayDir: String?

    /** Base of the relay sysfs class for on-board relays, or null if none. */
    val relayBase: String?

    /** Additional relay-class sysfs bases to probe when [relayBase] isn't present. The S9E renamed
     *  `/sys/class/st_relay` → `/sys/class/strelay` between firmware 1.0.2 and 1.1.0, so both must be
     *  tried — the controller uses the first whose dir actually holds `relayN` nodes. Default empty. */
    val relayBaseFallbacks: List<String> get() = emptyList()

    /** First GPIO number of the button-LED block (e.g. 147 on the S9E), or null if none. */
    val buttonLedGpioBase: Int?

    /** Declared proximity-sensor technology (e.g. "Time-of-Flight", "Infrared", "Radar"), or null if
     *  unknown. Android's Sensor API has no technology field and the HAL reports generic AOSP names
     *  (verified: "Proximity sensor" / vendor "The Android Open Source Project"), so this can't be probed
     *  — declare per profile where known. Optionally append a known chipset, e.g. "Infrared (STK3338)". */
    val proximityTech: String? get() = null

    /** Pre-calibration polarity for the tuning-UI gauge: true when "near" is the *smaller* raw value
     *  (distance ToF, e.g. TPA10), false when near is the *larger* value (reflective IR). The gauge icons
     *  (panel = near / person = far) assert a direction, so this grounds them before the user calibrates;
     *  the two-point capture then refines it empirically and stores the authoritative value. Null =
     *  unknown (legacy default: assume near-below). Does NOT affect the near/far decision, only the UI. */
    val proximityNearBelow: Boolean? get() = null

    /** Reference raw values for a *profiled* sensor: a typical reading with an object near the panel
     *  ([proximityNearRaw]) and at rest / far ([proximityFarRaw]). When both are set the panel is calibrated
     *  straight from the profile — threshold = midpoint, polarity = near<far (unless [proximityNearBelow]
     *  overrides), hysteresis from the sensitivity preset — so the manual two-point capture is only a
     *  fallback for unprofiled sensors (and a user override). A written profile is authoritative knowledge;
     *  don't make the user re-discover it. Null = unknown → manual calibration. */
    val proximityNearRaw: Float? get() = null
    val proximityFarRaw: Float? get() = null

    /** GPIO number of a raw binary proximity line to poll over root (1 = near, 0 = far), for panels whose
     *  Android `SensorManager` proximity is absent or registers but never delivers events — e.g. the
     *  Smatek S9E (gpio18, reporter-confirmed GitHub #5: reads 0 far / 1 near, no export needed). Null →
     *  use the `SensorManager` proximity sensor. */
    val proximityGpio: Int? get() = null

    /** Declared ambient-light-sensor technology (e.g. "Ambient light (ALS)"), or null if unknown. */
    val lightTech: String? get() = null

    /** True on panels carrying a CHT8305 room temperature/humidity chip whose readings are only reachable
     *  via the root helper daemon (its `/dev/input` nodes are root-owned) — e.g. the TPA10. Gates the opt-in
     *  Room temperature/humidity HA sensors + their calibration offset. Null/false elsewhere. */
    val hasCht8305: Boolean get() = false

    /** Baseline calibration offset (°C) added to the CHT8305 room-temperature reading to correct for panel
     *  self-heating. The maintainer sets a characterised value per profile once measured; the user can add a
     *  further trim via the `room_temp_offset` config key (the two are additive). 0 = no baseline correction. */
    val roomTempOffsetC: Float get() = 0f

    /** Authoritative proximity graded(true) / binary(false) decided from the firmware version, where the
     *  profile knows the rule (e.g. NSPanel Pro's kernel-driver cutover) — overrides runtime observation,
     *  so a graded sensor never momentarily reads "Binary" at idle. Null → fall back to observation.
     *  @param productVersion `ro.product.version`, e.g. "NSPanel120P_3.7.1". */
    fun proximityGradedForFirmware(productVersion: String): Boolean? = null

    /** Default HA device-card manufacturer for this panel (e.g. "Sonoff"), or null to infer from
     *  [Build.MANUFACTURER]. The user's Configure-form value always overrides. */
    val manufacturer: String?

    /** Default HA device-card model/product name (e.g. "NSPanel Pro"), or null to infer from
     *  [Build.MODEL]. Published with a " (ha-paneld)" suffix so the device is distinguishable from a
     *  co-installed integration managing the same hardware; the user's form value overrides verbatim. */
    val model: String?

    /** Model text for the local info page. Most profiles use [displayName]; a profile may decode its
     *  vendor product-version string when that string carries a real variant/firmware identity. */
    fun panelModelLabel(productVersion: String): String = displayName

    /** Hardware buttons the Android input pipeline doesn't usefully deliver to the app, instrumented
     *  via the root helper daemon's evdev WATCH/grab instead. Empty when none. See [EvdevButton]. */
    val evdevButtons: List<EvdevButton>

    /** Maps the HA-facing CPU tiers ("Performance"/"Efficiency"/"Auto") to this SoC's kernel governors.
     *  Mainly to pin "Auto" to the right dynamic governor (schedutil on rk3566/rk3576, interactive on
     *  PX30). Null, or any unmapped/unavailable tier, falls back to runtime resolution in CpuController. */
    val cpuGovernors: Map<String, String>?

    /** Optional per-panel "HA-optimised" display density (dpi) + text (font) scale, offered as a
     *  one-click preset in the display-sizing control. The genuinely-right values are a per-install
     *  preference (room, dashboard design), so these are starting suggestions to be calibrated — null =
     *  offer only factory-base reset + custom. Density is the layout scale; font scale is the WebView text. */
    val recommendedDensity: Int?
    val recommendedFontScale: Float?

    /** Trustworthy physical display pixel density. This is profile evidence, not Android's misleading
     *  `wm density` "Physical density" field (which is only a base logical DPI). Null when unknown. */
    val physicalPpi: Int? get() = null

    /** The System WebView build to install when this panel's stock WebView is too old to render the HA
     *  dashboard. These panels have no Play Store, so ha-paneld sideloads a known-good `com.android.webview`
     *  from the `webview-mirror` release (pinned signer). null = no known-good build for this panel (leave
     *  the WebView alone). Pick the newest the panel's Android version supports (NSPanel Pro's 8.1 caps at
     *  138). See [WebViewInstaller] and docs/hardware/README.md. */
    val recommendedWebView: WebViewSpec? get() = null

    /** The newest HA Companion version known-good on this platform, or null = no cap. The Companion
     *  auto-updater refuses to install a release NEWER than this. 2026.6.5-minimal crash-loops on
     *  PX30/Android 8.1 (missing `CarUxRestrictionsManager` class), so the NSPanel Pro pins to 2026.5.4.
     *  Compared with `UpdateChecker.isNewer` (dotted numeric). */
    val companionMaxVersion: String? get() = null

    companion object {
        /** Every production profile, including [Generic]. Cross-profile contract tests iterate this list;
         *  add a new profile here in the same change that adds its detection rule. */
        internal val knownProfiles: List<DeviceProfile> = listOf(
            NSPanelPro,
            Tpa10,
            EchoShow5Gen2,
            ZxSmt156,
            ShellyWallDisplayV2,
            ShellyWallDisplay,
            Smt1019,
            Wf1589t,
            S9e,
            Generic,
        )

        /** Memoized detected profile: [match] is pure per boot (Build fields + one system property), yet
         *  several call sites re-invoke [detect] on request paths — so resolve it once and reuse. */
        private val cached: DeviceProfile by lazy { match() }

        /**
         * Pick the profile for the running device from [Build] fingerprints; [Generic] when none match.
         * Build-only (no Context needed). Presence-based capabilities (relays/zigbee/sensors) are still
         * runtime-probed by their controllers, so [Generic] is a safe fallback for unknown panels.
         */
        fun detect(): DeviceProfile = cached

        private fun match(): DeviceProfile =
            match(Build.MODEL, Build.DEVICE, SystemProps.get("ro.product.version"))

        /**
         * Pure fingerprint match over the raw [Build] fields + `ro.product.version` (lowercased here).
         * Extracted from the Android-facing [match] so the whole per-platform mapping — including the
         * order-sensitive collision cases (SMT1019 vs WF1589T, TPA10 vs the rk3566-sharing S9E, Gen1 vs
         * Gen2 NSPanel Pro) — is unit-testable without a device. **Branch order is significant**; keep the
         * comments. [Generic] is the no-match fallback.
         */
        internal fun match(rawModel: String, rawDevice: String, rawProductVersion: String): DeviceProfile {
            val model = rawModel.lowercase()
            val device = rawDevice.lowercase()
            val productVersion = rawProductVersion.lowercase()
            return when {
                // Exact product identities must win over broad SoC aliases. This matters when another
                // vendor ships the same Rockchip reference platform: a Shelly Jenna can truthfully carry
                // PX30/rk3326 in one Build field while its product/device field still says "jenna".
                model == "tpa10" || device == "tpa10" -> Tpa10
                device == "cronos" || model == "cronos" -> EchoShow5Gen2
                device == "rk3566_t" || model == "rk3566_t" -> ZxSmt156
                model in setOf("blake", "jenna", "cally", "maverick", "dayna") ||
                    device in setOf("blake", "jenna", "cally", "maverick", "dayna") ||
                    Regex("^sawd-[3456]").containsMatchIn(model) ||
                    Regex("^sawd-[3456]").containsMatchIn(device) -> ShellyWallDisplayV2
                model in setOf("stargate", "pegasus", "atlantis") ||
                    device in setOf("stargate", "pegasus", "atlantis") ||
                    Regex("^sawd-[012]").containsMatchIn(model) ||
                    Regex("^sawd-[012]").containsMatchIn(device) ||
                    "k400_mt6580" in device || "e500_7731e" in device -> ShellyWallDisplay
                "wf2489" in device -> Smt1019
                "wf1589" in device -> Wf1589t
                // The Smatek S9E reports generic Build fields but carries its vendor model code in
                // ro.product.version ("S9_Android_1.1.0"). Exact TPA10/rk3566_t rules already won above.
                "s9e" in model || "s9e" in device || model == "s9" || productVersion.startsWith("s9") -> S9e
                productVersion.startsWith("nspanel") || productVersion.startsWith("s6_android_") -> NSPanelPro
                // Broad reference-platform fallbacks come last. They retain support for historical
                // NSPanel/WF diagnostics while never overriding a product identity known above.
                "px30" in model || "px30" in device || "rk3326" in model || "rk3326" in device -> NSPanelPro
                model == "rk3576_u" -> Wf1589t
                else -> Generic
            }
        }
    }
}

/** `su` invocation form: toolbox `su -c '<cmd>'` (Sonoff PX30) vs Android `su 0 sh -c '<cmd>'` (Tuya
 *  userdebug); NONE = su is not reachable from the app sandbox (use the helper daemon instead). */
enum class SuForm { TOOLBOX, ANDROID, NONE }

/**
 * A known-good System WebView build for a panel (see [DeviceProfile.recommendedWebView]). The package
 * is always `com.android.webview` — the id the Android framework requires to auto-select a WebView
 * provider — so only the source + version + pinned signer vary. [version] is the full Chromium version
 * the build provides (e.g. "138.0.7204.63"); its major gates the "is a newer one worth installing"
 * check. [certSha256] is the build's signing cert (LineageOS / Cromite / …), and [apkSha256] pins the
 * exact mirrored artifact; both are verified before install.
 */
data class WebViewSpec(
    val url: String,
    val version: String,
    val certSha256: String,
    val apkSha256: String,
) {
    /** Chromium major of [version] (e.g. 138), or 0 if unparseable. */
    val major: Int get() = version.substringBefore('.').toIntOrNull() ?: 0
}

/** RGB-LED control mechanism. RK3576_IOCTL = app-direct ioctl on `/dev/ledjni` (e.g. WF1589T);
 *  RK3576_IOCTL_DAEMON = the *same* ioctl but routed through the root helper daemon, for panels where
 *  the app is SELinux-denied the ioctl (e.g. SMT1019 — the node is `system:system`, generic `device`
 *  label); SYSFS_DAEMON = root-only sysfs LED via the daemon (e.g. TPA10); AUTODETECT = probe rk3576
 *  ioctl then the daemon (the [Generic] fallback for an unknown panel); NONE = no LED, skip probing.
 *  The daemon auto-detects sysfs-vs-ledjni itself, so both daemon mechanisms use the same client. */
enum class LedMechanism { RK3576_IOCTL, RK3576_IOCTL_DAEMON, SYSFS_DAEMON, AUTODETECT, NONE }

/** True-screen-off path. */
enum class ScreenOff { SU_BLPOWER, DAEMON_BLPOWER, BRIGHTNESS_ZERO }

/**
 * A hardware button instrumented through the root helper daemon's evdev reader (for keys Android
 * doesn't deliver to the app — e.g. a `KEY_MICMUTE` adc-key, or the power key).
 *
 * @param node    the evdev node, e.g. "/dev/input/event1"
 * @param code    the Linux input code it emits (e.g. KEY_POWER 116, or with [sw] the switch code,
 *                e.g. SW_MUTE_DEVICE 14 on the TPA10 orange button)
 * @param grab    EVIOCGRAB the node exclusively, suppressing the default Android action (e.g. the
 *                power key's screen-lock) so the press becomes an HA event only — gated by automation.
 * @param eventType  the HA `event_type` published on press; must be in the event entity's declared list.
 * @param sw      false = an EV_KEY momentary key (emit on press/DOWN); true = an EV_SW latching switch
 *                (e.g. SW_MUTE_DEVICE) — emit on every toggle, since each physical press flips it.
 */
data class EvdevButton(
    val node: String,
    val code: Int,
    val grab: Boolean,
    val eventType: String,
    val sw: Boolean = false,
)

/**
 * A profile-curated package the user might want to tame, with author metadata so the Vendor-packages UI
 * can explain *what it is* and *why it's listed* — important on small-production panels where, say, the
 * SoC vendor's factory-test apps are routinely left installed and look alarming without context.
 *
 * @param pkg   the Android package name (e.g. "com.eWeLinkControlPanel").
 * @param tags  short classifier labels shown as chips — e.g. "vendor" (the panel maker's app),
 *              "chipset" (the SoC vendor's app), "test"/"demo" (factory/diagnostic), "overlay" (draws
 *              over the screen), "boot" (auto-starts). Free-form; the UI just renders them.
 * @param note  a one-line rationale from the profile author, shown under the row so the user understands
 *              why it's flagged and whether they want it gone. Empty = no note.
 * @param defaultTame  true for genuinely-intrusive firmware clutter (an overlay-drawing vendor kiosk, a
 *              factory burn-in/test tool) that a panel-as-HA-dashboard almost always wants gone. Marks the
 *              candidate as a **recommended default**: badged + one-click "Tame all recommended" in the
 *              `:8888` picker, and auto-applied at provision time (`provision.sh`, guarded — `tame()` never
 *              strands the panel). Off for merely-optional apps (demos, an OTA updater) — those stay a
 *              deliberate per-package choice. Never modifies a stock, un-provisioned panel on its own.
 */
data class TameCandidate(
    val pkg: String,
    val tags: List<String> = emptyList(),
    val note: String = "",
    val defaultTame: Boolean = false,
)
