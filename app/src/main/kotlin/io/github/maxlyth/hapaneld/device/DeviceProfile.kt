package io.github.maxlyth.hapaneld.device

import android.os.Build

/**
 * Per-platform canonical silo. Everything device/platform-specific that the generic functional modules
 * need — su form, LED mechanism, screen-off path, and the sysfs/vendor locations of optional hardware —
 * is declared here, one `object` per platform (see `NSPanelPro`, `Tpa10`, `Wf1589t`, `S9e`, `Generic`).
 *
 * Design rule (see docs/architecture/device-profiles.md): a profile declares **candidates + quirks**;
 * the functional modules still **runtime-probe to confirm** (profile says *where to look*, the probe
 * says *whether it's actually there*). The [Generic] profile probes everything generically, so an
 * unknown panel still works for whatever it physically has, with no profile written.
 *
 * Stage 1 (0.7.0): this module is defined but not yet consumed — no behaviour change. Controllers are
 * migrated to read the active profile one at a time in later stages.
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

    /** Whether the firmware provides a working Recents/Overview screen. Single-purpose panel images often
     *  ship none (verified 2026-06-10: KEYCODE_APP_SWITCH is a no-op on the Tuya TPA10), so the navbar's
     *  Recents button is omitted where false rather than presenting a dead control. Default true. */
    val hasRecents: Boolean get() = true

    /** How the RGB LED is driven, if any. */
    val ledMechanism: LedMechanism

    /** Preferred true-screen-off path (runtime tiering still falls back as needed). */
    val screenOff: ScreenOff

    /** Sonoff Zigbee gateway dir, or null if the panel has no managed Zigbee gateway. */
    val zigbeeGatewayDir: String?

    /** Base of the `st_relay` sysfs class for on-board relays, or null if none. */
    val relayBase: String?

    /** First GPIO number of the button-LED block (e.g. 147 on the S9E), or null if none. */
    val buttonLedGpioBase: Int?

    /** Declared proximity-sensor technology (e.g. "Time-of-Flight", "Infrared", "Radar"), or null if
     *  unknown. Android's Sensor API has no technology field and the HAL reports generic AOSP names
     *  (verified: "Proximity sensor" / vendor "The Android Open Source Project"), so this can't be probed
     *  — declare per profile where known. Optionally append a known chipset, e.g. "Infrared (STK3338)". */
    val proximityTech: String? get() = null

    /** Declared ambient-light-sensor technology (e.g. "Ambient light (ALS)"), or null if unknown. */
    val lightTech: String? get() = null

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
     *  offer only native-reset + custom. Density is the layout scale; font scale is the WebView text. */
    val recommendedDensity: Int?
    val recommendedFontScale: Float?

    companion object {
        /**
         * Pick the profile for the running device from [Build] fingerprints; [Generic] when none match.
         * Build-only (no Context needed). Presence-based capabilities (relays/zigbee/sensors) are still
         * runtime-probed by their controllers, so [Generic] is a safe fallback for unknown panels.
         */
        fun detect(): DeviceProfile {
            val model = Build.MODEL.lowercase()
            val device = Build.DEVICE.lowercase()
            // The Smatek S9E reports generic Build fields (MODEL "S9" / DEVICE "rk3566_r") but carries
            // its vendor model code in ro.product.version ("S9_Android_1.1.0") — confirmed from a
            // reporter's /diag (GitHub #3, 2026-06-15). Match that so it doesn't fall back to Generic
            // (which hides its relays + button LEDs). rk3566 is shared with the TPA10, but the TPA10 is
            // matched first by device == "tpa10".
            val productVersion = sysProp("ro.product.version").lowercase()
            return when {
                // px30 = Gen1 86P/120P; rk3326(-s) = Gen2 (best-effort — capabilities are runtime-probed,
                // so an unverified Gen2 still detects relays/zigbee/sensors correctly).
                "px30" in model || "px30" in device || "rk3326" in model || "rk3326" in device -> NSPanelPro
                model == "tpa10" || device == "tpa10" -> Tpa10
                "wf1589" in device || model == "rk3576_u" -> Wf1589t
                "s9e" in model || "s9e" in device || model == "s9" || productVersion.startsWith("s9") -> S9e
                else -> Generic
            }
        }

        /** Read an Android system property (e.g. ro.product.version) via SystemProperties reflection —
         *  no Context needed, so it's usable from this Build-only detector. */
        private fun sysProp(key: String): String = runCatching {
            @Suppress("PrivateApi")
            val m = Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            (m.invoke(null, key) as? String).orEmpty()
        }.getOrDefault("")
    }
}

/** `su` invocation form: toolbox `su -c '<cmd>'` (Sonoff PX30) vs Android `su 0 sh -c '<cmd>'` (Tuya
 *  userdebug); NONE = su is not reachable from the app sandbox (use the helper daemon instead). */
enum class SuForm { TOOLBOX, ANDROID, NONE }

/** RGB-LED control mechanism. AUTODETECT = probe rk3576 ioctl then the sysfs daemon (the [Generic]
 *  fallback for an unknown panel); NONE = the panel has no LED, skip probing. */
enum class LedMechanism { RK3576_IOCTL, SYSFS_DAEMON, AUTODETECT, NONE }

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
