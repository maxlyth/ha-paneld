package io.github.maxlyth.hapaneld.config

import io.github.maxlyth.hapaneld.util.AndroidInput
import io.github.maxlyth.hapaneld.util.BrokerEndpoint
import io.github.maxlyth.hapaneld.util.LogShipEndpoint
import java.util.Locale

/**
 * The authoritative, ordered list of ha-paneld settings. Adding a setting here makes it appear in the
 * HTTP config API + generated form, in bundles + revisions, and (when [SettingSpec.ha] is set and the
 * panel exposes it) as a Home Assistant entity — with no per-key wiring in three places.
 *
 * Coverage note: this first pass registers the settings whose current value is **Config-backed**
 * (read directly from durable config state). Controller-sourced entities (touch sound, CPU profile,
 * network ADB, zigbee), option-driven selects (navbar/cpu), the core controls (screen/led/volume/
 * navigate), publish-only sensors, dynamic relay/button-LED entities, and the action buttons are
 * published by a separate discovery pass (they are not "settings" or need a runtime value provider);
 * they are gated in by capability at publish time. See the discovery rewrite (Stage C).
 */
object SettingsRegistry {

    /** Bump whenever the persisted shape changes; drives bundle migration. */
    const val SCHEMA = 4
    const val MAX_PANEL_ID_CHARS = 63
    const val DEFAULT_SILENCE_BOOT_CHIME = true
    private const val MAX_PANEL_ID_INPUT_CHARS = 255
    private val HA_ILLUMINANCE_ENTITY = Regex("^sensor\\.[a-z0-9_]+$")

    val SPECS: List<SettingSpec> = listOf(
        // ---- Identity ----------------------------------------------------------------------------
        SettingSpec(
            key = "panel_id", type = SettingType.STRING, group = "Identity",
            label = "Panel ID", default = "", tier = Tier.BASIC, scope = Scope.IDENTITY,
            help = "Stable id used in entity IDs and MQTT topics (lowercase, digits, underscores; 63 characters maximum).",
            validate = { raw ->
                if (raw.length > MAX_PANEL_ID_INPUT_CHARS) {
                    Validation.Bad("panel_id: must be at most $MAX_PANEL_ID_CHARS characters")
                } else {
                    val slug = raw.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')
                    if (slug.isEmpty()) Validation.Bad("panel_id: must contain a letter or digit")
                    else if (slug.length > MAX_PANEL_ID_CHARS) {
                        Validation.Bad("panel_id: must be at most $MAX_PANEL_ID_CHARS characters")
                    } else Validation.Ok(slug)
                }
            },
        ),
        SettingSpec(
            key = "friendly_name", type = SettingType.STRING, group = "Identity",
            label = "Friendly name", default = "", tier = Tier.BASIC, scope = Scope.IDENTITY,
            maxChars = 128,
            help = "HA device display name.",
        ),
        SettingSpec(
            key = "manufacturer", type = SettingType.STRING, group = "Identity",
            label = "Manufacturer", default = "", scope = Scope.DEVICE,
            maxChars = 128,
            help = "HA device-card manufacturer override (blank = profile/auto).",
        ),
        SettingSpec(
            key = "model", type = SettingType.STRING, group = "Identity",
            label = "Model", default = "", scope = Scope.DEVICE,
            maxChars = 128,
            help = "HA device-card model override (blank = profile/auto).",
        ),

        // LAST in the Identity card on purpose (maintainer, 2026-07-26): this is the group's only
        // picker, and a select does not share the text fields' column metrics — mid-card it broke the
        // column line, so it sits at the card's edge where the difference reads as intentional. The
        // columns themselves are deliberately left alone for now; only the order changed.
        SettingSpec(
            key = "ha_area", type = SettingType.STRING, group = "Identity",
            label = "Area in Home Assistant", default = "", picker = "ha_area", scope = Scope.DEVICE,
            // liveApply with a no-op dispatcher on purpose: this key needs NO runtime rebuild (discovery
            // reads it at next publish; the HA write-back is a post-commit server side effect) — but as
            // a non-live key it dragged a FULL network reconfigure behind every wizard dashboard answer,
            // and the HTTP server blip failed the user's next request on hardware.
            liveApply = true,
            maxChars = 128,
            help = "Where this panel lives, by Home Assistant area name. This can only be changed in " +
                "ha-paneld if your HA user has admin permissions, otherwise change it on your device in HA.",
        ),
        // ---- MQTT --------------------------------------------------------------------------------
        SettingSpec(
            key = "mqtt_broker", type = SettingType.STRING, group = "MQTT",
            label = "Broker URL", default = "", tier = Tier.BASIC, scope = Scope.PORTABLE,
            maxChars = 2_048,
            help = "Blank auto-discovers HA over mDNS.",
            validate = { raw ->
                if (raw.isBlank()) Validation.Ok(raw)
                else BrokerEndpoint.normalize(raw)?.let(Validation::Ok)
                    ?: Validation.Bad("mqtt_broker: expected a tcp://, mqtt://, ssl://, mqtts://, or tls:// broker URL")
            },
        ),
        SettingSpec(
            key = "mqtt_user", type = SettingType.STRING, group = "MQTT",
            label = "Username", default = "", tier = Tier.BASIC, scope = Scope.DEVICE,
            maxChars = 256,
            help = "Credential for this panel.",
        ),
        SettingSpec(
            key = "mqtt_password", type = SettingType.PASSWORD, group = "MQTT",
            label = "Password", default = "", tier = Tier.BASIC, scope = Scope.DEVICE, secret = true,
            maxChars = 4_096,
            help = "Blank on save keeps the current password.",
        ),

        // ---- Behaviour ---------------------------------------------------------------------------
        SettingSpec(
            key = "auto_sleep", type = SettingType.BOOL, group = "Behaviour",
            label = "Auto sleep", default = "false", tier = Tier.BASIC, scope = Scope.DEVICE,
            liveApply = true,
            help = "Automatically wake the panel when activity is detected and switch the screen off after the learned delay. Manual screen control remains separate.",
            ha = HaEntity(
                "switch", "auto_sleep", "Auto sleep",
                """"command_topic":"ha-paneld/{panel}/auto_sleep/set","state_topic":"ha-paneld/{panel}/auto_sleep/state","icon":"mdi:sleep","entity_category":"config"""",
            ),
        ),
        SettingSpec(
            key = "navbar_mode", type = SettingType.ENUM, group = "Behaviour",
            label = "Navbar mode", default = "Off",
            liveApply = true,
            options = listOf("Off", "Always on", "Swipe reveal"),
            help = "Soft on-screen navigation bar for panels with no native navbar.",
            ha = HaEntity(
                "select", "navbar", "Navbar",
                """"command_topic":"ha-paneld/{panel}/navbar/set","state_topic":"ha-paneld/{panel}/navbar/state","options":["Off","Always on","Swipe reveal"],"icon":"mdi:gesture-tap-button","entity_category":"config"""",
            ),
        ),
        SettingSpec(
            key = "wake_on_wave", type = SettingType.BOOL, group = "Behaviour",
            label = "Wake on wave", default = "false", tier = Tier.BASIC, scope = Scope.PORTABLE,
            liveApply = true,
            help = "Wake locally after a learned deliberate far-to-near-to-far wave. Touch-to-wake remains available while the panel learns.",
            availableWhen = { it.hasProximity },
            ha = HaEntity(
                "switch", "wake_on_wave", "Wake on wave",
                """"command_topic":"ha-paneld/{panel}/wake_on_wave/set","state_topic":"ha-paneld/{panel}/wake_on_wave/state","icon":"mdi:gesture-tap","entity_category":"config"""",
            ),
        ),
        SettingSpec(
            key = "kiosk_lock", type = SettingType.BOOL, group = "Behaviour",
            label = "Lock Android to dashboard (experimental)", default = "false", scope = Scope.DEVICE,
            liveApply = true,
            help = "Root-only casual-use lock. Hides Android system bars and returns to the selected dashboard " +
                "within about 3 seconds when another app or Recents opens. It does not hide Home Assistant " +
                "navigation. Release it here, from Home Assistant, through adb, with 7 rapid top-left taps, " +
                "or during the 60-second unlocked window after reboot.",
            ha = HaEntity(
                "switch", "kiosk_lock", "Android dashboard lock",
                """"command_topic":"ha-paneld/{panel}/kiosk_lock/set","state_topic":"ha-paneld/{panel}/kiosk_lock/state","icon":"mdi:lock","entity_category":"config"""",
            ),
        ),
        SettingSpec(
            key = "watchdog_enabled", type = SettingType.BOOL, group = "Behaviour",
            label = "App watchdog", default = "false", scope = Scope.PORTABLE,
            liveApply = true,
            help = "Self-heal the dashboard app: relaunch if it dies, return if backgrounded too long.",
        ),
        SettingSpec(
            key = "touch_sound", type = SettingType.BOOL, group = "Behaviour",
            label = "Touch sound", default = "true", scope = Scope.PORTABLE,
            liveApply = true,
            help = "Audible tap feedback (system touch sounds).",
            ha = HaEntity(
                "switch", "touch_sound", "Touch sound",
                """"command_topic":"ha-paneld/{panel}/touch_sound/set","state_topic":"ha-paneld/{panel}/touch_sound/state","icon":"mdi:volume-high","entity_category":"config"""",
            ),
        ),
        SettingSpec(
            key = "silence_boot_chime", type = SettingType.BOOL, group = "Behaviour",
            label = "Silence boot chime", default = DEFAULT_SILENCE_BOOT_CHIME.toString(), scope = Scope.DEVICE,
            liveApply = true,
            help = "Mute the firmware startup chime.",
        ),
        // ---- Display -----------------------------------------------------------------------------
        SettingSpec(
            key = "dark_mode", type = SettingType.BOOL, group = "Display",
            label = "Dark mode", default = "true", scope = Scope.PORTABLE,
            help = "Themes ha-paneld's own screens and sets the dashboard's default colour scheme on panels without a system dark-mode setting (Android 9 and older). A theme picked inside Home Assistant overrides the dashboard default; this web UI always follows the viewing browser's own preference.",
            // Panels with a native system dark/light control (Android 10+) follow the OS setting for
            // everything, so the toggle is hidden there.
            availableWhen = { !it.hasSystemDarkMode },
        ),
        SettingSpec(
            key = "auto_brightness_ha_entity", type = SettingType.STRING, group = "Display",
            label = "Ambient light source", default = "", picker = "ha_illuminance", scope = Scope.DEVICE,
            liveApply = true,
            maxChars = 255,
            help = "Blank uses the panel light sensor. Select a Home Assistant illuminance sensor when the panel has no suitable sensor.",
            validate = { raw ->
                when {
                    raw.isBlank() -> Validation.Ok("")
                    HA_ILLUMINANCE_ENTITY.matches(raw) -> Validation.Ok(raw)
                    else -> Validation.Bad(
                        "auto_brightness_ha_entity: expected a sensor entity id such as sensor.room_illuminance",
                    )
                }
            },
        ),
        SettingSpec(
            key = "auto_brightness", type = SettingType.BOOL, group = "Display",
            label = "Auto-brightness", default = "false", scope = Scope.PORTABLE,
            liveApply = true,
            help = "On-panel engine maps a lux stream to the backlight (off = HA drives the screen).",
            ha = HaEntity(
                "switch", "auto_brightness", "Auto-brightness",
                """"command_topic":"ha-paneld/{panel}/auto_brightness/set","state_topic":"ha-paneld/{panel}/auto_brightness/state","icon":"mdi:brightness-auto","entity_category":"config"""",
            ),
        ),
        SettingSpec(
            key = "auto_brightness_minimum_percent", type = SettingType.INT, group = "Display",
            label = "Minimum level", default = "4", min = 4.0, max = 95.0, step = 1.0,
            liveApply = true,
            scope = Scope.DEVICE,
            help = "Lowest automatic screen level as a percentage. Proposals scale from this floor to full brightness; manual brightness can still go lower.",
        ),
        SettingSpec(
            key = "auto_brightness_sensitivity", type = SettingType.INT, group = "Display",
            label = "Sensitivity", default = "50", min = 0.0, max = 100.0, step = 5.0,
            liveApply = true,
            scope = Scope.DEVICE,
            help = "How strongly the screen follows deviations from its learned ambient-light pattern (50 = balanced).",
        ),

        SettingSpec(
            key = "cpu_governor", type = SettingType.ENUM, group = "System",
            label = "CPU profile", default = "Auto", scope = Scope.DEVICE,
            liveApply = true,
            // Mirrors CpuController.TIERS (kept literal — this package is pure/Android-free).
            options = listOf("Performance", "Efficiency", "Auto"),
            help = "Live CPU scaling profile until reboot; Auto = the SoC's dynamic governor.",
            transient = true,
            availableWhen = { it.cpuGovernors },
            ha = HaEntity(
                "select", "cpu_governor", "CPU profile",
                """"command_topic":"ha-paneld/{panel}/cpu_governor/set","state_topic":"ha-paneld/{panel}/cpu_governor/state","options":["Performance","Efficiency","Auto"],"icon":"mdi:speedometer","entity_category":"config"""",
            ),
        ),
        SettingSpec(
            key = "zigbee_router", type = SettingType.BOOL, group = "System",
            label = "Zigbee router", default = "false", scope = Scope.DEVICE,
            liveApply = true,
            help = "Run the on-board Zigbee gateway as a router/repeater (NSPanel Pro).",
            availableWhen = { it.zigbeePresent },
        ),
        SettingSpec(
            key = "prevent_idle_dim", type = SettingType.BOOL, group = "Behaviour",
            label = "Prevent idle dim", default = "true", scope = Scope.PORTABLE,
            liveApply = true,
            help = "Stop the vendor firmware dimming the backlight at the screen-off timeout.",
        ),
        SettingSpec(
            key = "keep_awake", type = SettingType.BOOL, group = "Behaviour",
            label = "Keep panel responsive", default = "true", scope = Scope.PORTABLE,
            help = "Keep the network and background services running while the screen is off.",
        ),

        // ---- Dashboard ---------------------------------------------------------------------------
        // Which app renders the dashboard + how the built-in renderer connects to HA.
        SettingSpec(
            key = "dashboard_package", type = SettingType.STRING, group = "Dashboard",
            label = "Dashboard app", default = "", picker = "renderer", scope = Scope.DEVICE,
            maxChars = 255,
            help = "App used for the dashboard. Blank uses ha-paneld's built-in renderer.",
            validate = { value ->
                if (AndroidInput.isDashboardTarget(value)) Validation.Ok(value)
                else Validation.Bad("dashboard_package: expected blank, builtin, or an Android package name")
            },
        ),
        SettingSpec(
            key = "dashboard_entity_learning", type = SettingType.BOOL, group = "Dashboard",
            label = "Entity filtering", default = "false", scope = Scope.PORTABLE,
            help = "Built-in renderer: limit Home Assistant's state stream to dashboard-used entities and learn runtime dependencies. Manage sources and pins in Entities.",
        ),
        SettingSpec(
            key = "home_dashboard", type = SettingType.STRING, group = "Dashboard",
            label = "Home dashboard", default = "", picker = "ha_dashboard", scope = Scope.DEVICE,
            liveApply = true,
            maxChars = 2_048,
            help = "Dashboard used by reload and idle return. Auto lets Home Assistant choose.",
        ),
        SettingSpec(
            key = "dashboard_fullscreen", type = SettingType.BOOL, group = "Dashboard",
            label = "Hide Android system bars", default = "true", scope = Scope.PORTABLE,
            help = "Built-in renderer only. Hides Android's status and navigation bars while the dashboard is " +
                "in front; swipe from an edge to reveal them temporarily. It does not lock the panel or hide " +
                "Home Assistant navigation.",
        ),
        SettingSpec(
            key = "dashboard_native_kiosk", type = SettingType.BOOL, group = "Dashboard",
            label = "Hide Home Assistant navigation (native)", default = "true", scope = Scope.PORTABLE,
            help = "Built-in renderer only. After Home Assistant 2026.4.2+ connects, asks its native frontend " +
                "to hide its navigation. On by default; an unsupported or failed request leaves the dashboard " +
                "unchanged. It does not lock Android or inject CSS into the dashboard.",
        ),
        SettingSpec(
            key = "dashboard_overscroll", type = SettingType.BOOL, group = "Dashboard",
            label = "Dashboard overscroll effect", default = "false", scope = Scope.PORTABLE, hidden = true,
            help = "Built-in renderer: allow Android's overscroll stretch/glow when a drag runs past " +
                "the top or bottom of the dashboard. Off by default (a wall panel rarely scrolls, and " +
                "the bounce looks out of place). API-only — set true to restore the native effect.",
        ),
        SettingSpec(
            key = "dashboard_idle_return_min", type = SettingType.INT, group = "Dashboard",
            label = "Idle return to home (min)", default = "0", min = 0.0, max = 1440.0,
            scope = Scope.PORTABLE,
            help = "Built-in renderer: return to Home dashboard after this many idle minutes. 0 = off.",
        ),
        SettingSpec(
            key = "ha_url", type = SettingType.STRING, group = "Dashboard",
            label = "Home Assistant URL", default = "", scope = Scope.PORTABLE,
            maxChars = 2_048,
            help = "Built-in renderer: Home Assistant base URL, e.g. http://homeassistant.local:8123. Blank disables it.",
            validate = { raw ->
                if (raw.isBlank()) Validation.Ok("")
                else normalizeHttpOriginUrl(raw)?.let(Validation::Ok)
                    ?: Validation.Bad("ha_url: expected an http:// or https:// URL with no embedded credentials")
            },
        ),
        SettingSpec(
            key = "ha_token", type = SettingType.PASSWORD, group = "Dashboard",
            label = "Long-lived access token", default = "", scope = Scope.DEVICE,
            secret = true,
            help = "Use browser sign-in when possible. This fallback can be created in your Home Assistant user profile.",
        ),
        SettingSpec(
            key = "ha_refresh_token", type = SettingType.PASSWORD, group = "Dashboard",
            label = "HA refresh token", default = "", scope = Scope.DEVICE, secret = true, hidden = true,
            help = "Internal token state retained for API/config import compatibility. Borrowed Companion and ha-paneld-issued OAuth logins manage it automatically.",
        ),
        SettingSpec(
            key = "ha_token_expiry", type = SettingType.LONG, group = "Dashboard",
            label = "HA access-token expiry", default = "0", scope = Scope.DEVICE, secret = true, hidden = true,
            min = 0.0,
            help = "Internal OAuth access-token expiry retained with its matching access and refresh tokens during private backup and restore.",
        ),
        SettingSpec(
            key = "ha_client_id", type = SettingType.STRING, group = "Dashboard",
            label = "HA OAuth client_id", default = "", scope = Scope.DEVICE, hidden = true,
            maxChars = 2_048,
            help = "Internal token provenance retained for API/config import compatibility. Borrowed Companion tokens use the Android Companion client; ha-paneld browser sign-ins use the panel HTTP origin. Not a user preference.",
        ),
        SettingSpec(
            key = "dashboard_entity_overrides", type = SettingType.STRING, group = "Dashboard",
            label = "Entity filter overrides", default = "", scope = Scope.DEVICE, hidden = true,
            // Explicit pins/exclusions may legitimately cover a large installation. This remains
            // bounded by the config/import envelope, but must not inherit the ordinary text limit.
            maxChars = 1_048_576,
            help = "Backup-only storage for explicit entity pins and forced exclusions managed on the Entities tab.",
        ),
        SettingSpec(
            key = "dashboard_entity_learning_applied", type = SettingType.BOOL, group = "Dashboard",
            label = "Apply learned entity filter", default = "false", scope = Scope.DEVICE, hidden = true,
            help = "Backup-safe activation latch. Set after a safe empty-install bootstrap or an explicit apply from the Entities API or tab.",
        ),
        SettingSpec(
            key = "dashboard_entity_auto_static", type = SettingType.BOOL, group = "Dashboard",
            label = "Auto-subscribe dashboard references", default = "true", scope = Scope.PORTABLE, hidden = true,
            help = "Entities found by parsing dashboard configuration may be added automatically. Evidence remains visible when disabled.",
        ),
        SettingSpec(
            key = "dashboard_entity_auto_runtime", type = SettingType.BOOL, group = "Dashboard",
            label = "Auto-subscribe runtime accesses", default = "true", scope = Scope.PORTABLE, hidden = true,
            help = "Missing entities read through hass.states may be added automatically. Evidence remains visible when disabled.",
        ),
        // Last in the group on purpose: the display-density control is the preferred way to size a
        // dashboard, so this app-level zoom sits below the connection settings users should actually set.
        SettingSpec(
            key = "dashboard_zoom", type = SettingType.INT, group = "Dashboard",
            label = "Zoom (%)", default = "100", min = 50.0, max = 300.0, step = 10.0,
            scope = Scope.DEVICE,
            help = "Browser zoom.",
        ),

        // ---- System ------------------------------------------------------------------------------
        SettingSpec(
            key = "self_update", type = SettingType.BOOL, group = "System",
            label = "ha-paneld auto-update", default = "true", scope = Scope.DEVICE,
            liveApply = true,
            help = "ha-paneld updates itself from GitHub releases on the selected channel. Only shown where verified app install is available.",
            availableWhen = { it.canInstallVerifiedApps },
        ),
        SettingSpec(
            key = "update_channel", type = SettingType.ENUM, group = "System",
            label = "ha-paneld auto-update channel", default = "stable", options = listOf("stable", "prerelease"),
            liveApply = true,
            scope = Scope.DEVICE,
            help = "Release channel the self-updater follows.",
            availableWhen = { it.canInstallVerifiedApps },
        ),
        SettingSpec(
            key = "companion_auto_update", type = SettingType.BOOL, group = "System",
            label = "Companion auto-update", default = "false", scope = Scope.DEVICE,
            liveApply = true,
            help = "Install/update the minimal HA Companion over root when missing or out of date.",
            availableWhen = { it.companionInstalled },
            ha = HaEntity(
                "switch", "companion_auto_update", "Companion auto-update",
                """"command_topic":"ha-paneld/{panel}/companion_auto_update/set","state_topic":"ha-paneld/{panel}/companion_auto_update/state","icon":"mdi:cellphone-arrow-down","entity_category":"config"""",
            ),
        ),
        SettingSpec(
            key = "companion_update_channel", type = SettingType.ENUM, group = "System",
            label = "Companion auto-update channel", default = "stable", options = listOf("stable", "prerelease"),
            liveApply = true,
            scope = Scope.DEVICE,
            help = "Release channel the Companion auto-updater follows.",
            ha = HaEntity(
                "select", "companion_update_channel", "Companion auto-update channel",
                """"command_topic":"ha-paneld/{panel}/companion_update_channel/set","state_topic":"ha-paneld/{panel}/companion_update_channel/state","options":["Stable","Pre-release"],"icon":"mdi:source-branch","entity_category":"config"""",
            ),
            availableWhen = { it.companionInstalled },
        ),
        SettingSpec(
            key = "webview_auto_update", type = SettingType.BOOL, group = "System",
            label = "WebView auto-update", default = "false", scope = Scope.DEVICE,
            liveApply = true,
            help = "Keep the System WebView on this panel's recommended build (from the ha-paneld mirror), installing a newer one over root on the update check. Off by default — a WebView swap needs a restart to take effect. Only shown where a recommended build exists (not on Play-updated panels).",
            availableWhen = { it.webViewManaged },
            ha = HaEntity(
                "switch", "webview_auto_update", "WebView auto-update",
                """"command_topic":"ha-paneld/{panel}/webview_auto_update/set","state_topic":"ha-paneld/{panel}/webview_auto_update/state","icon":"mdi:web-sync","entity_category":"config"""",
            ),
        ),
        SettingSpec(
            key = "launcher_package", type = SettingType.STRING, group = "System",
            label = "Launcher app", default = "", picker = "package", scope = Scope.DEVICE,
            maxChars = 255,
            help = "App the Launcher button brings forward. Blank auto-picks an app. Selecting Panel admin (ha-paneld) also makes and keeps ha-paneld the Android Home app.",
        ),
        SettingSpec(
            key = "tame_vendor_packages", type = SettingType.STRING, group = "System",
            label = "Vendor package selections", default = "", scope = Scope.DEVICE, hidden = true,
            maxChars = TamePackagePolicy.MAX_BYTES,
            help = "Backup-only storage for package selections managed by the Vendor packages card.",
            validate = TamePackagePolicy::normalize,
        ),
        SettingSpec(
            key = "network_adb", type = SettingType.BOOL, group = "System",
            label = "Network ADB", default = "false", scope = Scope.DEVICE,
            liveApply = true,
            help = "Security risk: keeps classic ADB listening on TCP port 5555 across boots and reconnects. Enable only during active maintenance on a trusted network. If ADB was enabled outside ha-paneld, it must also be disabled there.",
            availableWhen = { it.networkAdb },
            ha = HaEntity(
                "switch", "network_adb", "Network ADB",
                """"command_topic":"ha-paneld/{panel}/network_adb/set","state_topic":"ha-paneld/{panel}/network_adb/state","icon":"mdi:adb","entity_category":"config"""",
            ),
        ),
        // ---- Logging -----------------------------------------------------------------------------
        SettingSpec(
            key = "log_ship_enabled", type = SettingType.BOOL, group = "Logging",
            label = "Ship logs", default = "false", scope = Scope.DEVICE,
            help = "Forward this panel's own logcat to a central sink (LAN-only, redacted).",
        ),
        SettingSpec(
            key = "log_ship_host", type = SettingType.STRING, group = "Logging",
            label = "Sink host", default = "", scope = Scope.DEVICE,
            maxChars = 253,
            help = "Log-collector host; blank keeps shipping inert. A scheme or :port here is honoured " +
                "(udp://collector, collector:1514).",
        ),
        SettingSpec(
            key = "log_ship_port", type = SettingType.INT, group = "Logging",
            label = "Sink port", default = "514", min = 1.0, max = 65535.0, scope = Scope.DEVICE,
        ),
        SettingSpec(
            key = "log_ship_protocol", type = SettingType.ENUM, group = "Logging",
            label = "Protocol", default = LogShipEndpoint.DEFAULT_PROTOCOL,
            options = LogShipEndpoint.PROTOCOLS,
            aliases = LogShipEndpoint.ALIASES,
            scope = Scope.DEVICE,
            help = "TCP is the default because it reports a sink that is refusing or unreachable; UDP " +
                "cannot, so a wrong setting there fails silently. Port 514 serves both.",
        ),

        // ---- Sensors -----------------------------------------------------------------------------
        // Live panel readings. These rows carry no editable value; the Configure pip controls whether
        // each reading's existing entity is reported to Home Assistant. Screen and volume deliberately
        // keep their historical commandable entity types/identities rather than creating duplicate
        // numeric sensors: HA natively presents light brightness as a percentage and volume is 0..100%.
        SettingSpec(
            key = "screen", type = SettingType.INT, group = "Sensors",
            label = "Screen brightness", default = "",
            help = "Current screen state and brightness. Home Assistant presents the light's native 0–255 brightness as a percentage.",
            haExposedByDefault = true,
            ha = HaEntity(
                "light", "screen", "Screen",
                """"schema":"json","brightness":true,"supported_color_modes":["brightness"],"command_topic":"ha-paneld/{panel}/screen/set","state_topic":"ha-paneld/{panel}/screen/state"""",
                readOnly = true,
            ),
        ),
        SettingSpec(
            key = "volume", type = SettingType.INT, group = "Sensors",
            label = "Panel volume", default = "",
            help = "Current panel media volume reported on its native 0–100 percent scale.",
            haExposedByDefault = true,
            ha = HaEntity(
                "number", "volume", "Volume",
                """"command_topic":"ha-paneld/{panel}/volume/set","state_topic":"ha-paneld/{panel}/volume/state","min":0,"max":100,"step":1,"mode":"slider","unit_of_measurement":"%","icon":"mdi:volume-high"""",
                readOnly = true,
            ),
        ),
        SettingSpec(
            key = "illuminance", type = SettingType.INT, group = "Sensors",
            label = "Ambient light", default = "",
            help = "Ambient illuminance measured by the panel light sensor.",
            haExposedByDefault = true,
            availableWhen = { it.hasLight },
            ha = HaEntity(
                "sensor", "illuminance", "Illuminance",
                """"state_topic":"ha-paneld/{panel}/illuminance/state","device_class":"illuminance","unit_of_measurement":"lx","state_class":"measurement"""",
                readOnly = true,
            ),
        ),
        SettingSpec(
            key = "proximity", type = SettingType.BOOL, group = "Sensors",
            label = "Proximity", default = "",
            help = "Learned near/far occupancy from a supported proximity source.",
            haExposedByDefault = true,
            availableWhen = { it.hasLearnedProximity },
            ha = HaEntity(
                "binary_sensor", "proximity", "Proximity",
                """"state_topic":"ha-paneld/{panel}/proximity/state","device_class":"occupancy","payload_on":"ON","payload_off":"OFF"""",
                readOnly = true,
            ),
        ),
        SettingSpec(
            key = "proximity_level", type = SettingType.INT, group = "Sensors",
            label = "Proximity level", default = "",
            help = "Normalized learned proximity level from 0 to 100 percent; binary sources report 0 or 100.",
            haExposedByDefault = true,
            availableWhen = { it.hasLearnedProximity },
            ha = HaEntity(
                "sensor", "proximity_level", "Proximity level",
                """"state_topic":"ha-paneld/{panel}/proximity_level/state","unit_of_measurement":"%","state_class":"measurement","icon":"mdi:hand-wave"""",
                readOnly = true,
            ),
        ),
        SettingSpec(
            key = "auto_sleep_activity", type = SettingType.BOOL, group = "Sensors",
            label = "Auto-sleep activity", default = "",
            help = "Whether the auto-sleep policy is currently holding the panel awake. The Home Assistant entity provides the activity history timeline.",
            haExposedByDefault = false,
            ha = HaEntity(
                "binary_sensor", "auto_sleep_activity", "Auto-sleep activity",
                """"state_topic":"ha-paneld/{panel}/auto_sleep_activity/state","payload_on":"ON","payload_off":"OFF","json_attributes_topic":"ha-paneld/{panel}/auto_sleep_activity/attributes","icon":"mdi:motion-sensor"""",
                readOnly = true,
            ),
        ),
        SettingSpec(
            key = "temperature", type = SettingType.FLOAT, group = "Sensors",
            label = "Temperature", default = "",
            help = "Environmental temperature reported by Android's panel sensor.",
            haExposedByDefault = true,
            availableWhen = { it.hasTemperature },
            ha = HaEntity(
                "sensor", "temperature", "Temperature",
                """"state_topic":"ha-paneld/{panel}/temperature/state","device_class":"temperature","unit_of_measurement":"°C","state_class":"measurement"""",
                readOnly = true,
            ),
        ),
        SettingSpec(
            key = "humidity", type = SettingType.FLOAT, group = "Sensors",
            label = "Humidity", default = "",
            help = "Relative humidity reported by Android's panel sensor.",
            haExposedByDefault = true,
            availableWhen = { it.hasHumidity },
            ha = HaEntity(
                "sensor", "humidity", "Humidity",
                """"state_topic":"ha-paneld/{panel}/humidity/state","device_class":"humidity","unit_of_measurement":"%","state_class":"measurement"""",
                readOnly = true,
            ),
        ),

        // ---- Diagnostics -------------------------------------------------------------------------
        // Publish-only panel telemetry (readOnly sensors), all OPT-IN (haExposedByDefault=false): the
        // panel stays quiet in HA until a pip is enabled. Values come from [metrics.PanelMetrics] and
        // are pushed on the heartbeat tick with a deadband so they never flap the broker. (Issue #19)
        SettingSpec(
            key = "diag_ip", type = SettingType.STRING, group = "Diagnostics",
            label = "IP address", default = "",
            help = "This panel's LAN IPv4 address as a sensor.",
            haExposedByDefault = false,
            ha = HaEntity(
                "sensor", "diag_ip", "IP address",
                """"state_topic":"ha-paneld/{panel}/diag_ip/state","icon":"mdi:ip-network","entity_category":"diagnostic"""",
                readOnly = true,
            ),
        ),
        SettingSpec(
            key = "diag_cpu", type = SettingType.INT, group = "Diagnostics",
            label = "CPU usage", default = "",
            help = "Overall CPU busy percentage (root/su panels; unavailable on sandbox-walled panels).",
            haExposedByDefault = false,
            ha = HaEntity(
                "sensor", "diag_cpu", "CPU usage",
                """"state_topic":"ha-paneld/{panel}/diag_cpu/state","unit_of_measurement":"%","state_class":"measurement","icon":"mdi:cpu-64-bit","entity_category":"diagnostic"""",
                readOnly = true,
            ),
        ),
        SettingSpec(
            key = "diag_memory", type = SettingType.INT, group = "Diagnostics",
            label = "Memory usage", default = "",
            help = "Used RAM as a percentage.",
            haExposedByDefault = false,
            ha = HaEntity(
                "sensor", "diag_memory", "Memory usage",
                """"state_topic":"ha-paneld/{panel}/diag_memory/state","unit_of_measurement":"%","state_class":"measurement","icon":"mdi:memory","entity_category":"diagnostic"""",
                readOnly = true,
            ),
        ),
        SettingSpec(
            key = "diag_soc_temp", type = SettingType.FLOAT, group = "Diagnostics",
            label = "SoC temperature", default = "",
            help = "System-on-chip temperature (root/su panels; unavailable on sandbox-walled panels).",
            haExposedByDefault = false,
            ha = HaEntity(
                "sensor", "diag_soc_temp", "SoC temperature",
                """"state_topic":"ha-paneld/{panel}/diag_soc_temp/state","device_class":"temperature","unit_of_measurement":"°C","state_class":"measurement","entity_category":"diagnostic"""",
                readOnly = true,
            ),
        ),
        SettingSpec(
            key = "diag_boot", type = SettingType.STRING, group = "Diagnostics",
            label = "Last boot time", default = "",
            help = "When the panel last booted (a timestamp — HA shows the elapsed uptime).",
            haExposedByDefault = false,
            ha = HaEntity(
                "sensor", "diag_boot", "Last boot time",
                """"state_topic":"ha-paneld/{panel}/diag_boot/state","device_class":"timestamp","icon":"mdi:clock-start","entity_category":"diagnostic"""",
                readOnly = true,
            ),
        ),
        SettingSpec(
            key = "diag_wifi_ssid", type = SettingType.STRING, group = "Diagnostics",
            label = "Wi-Fi network", default = "",
            help = "Current Wi-Fi network name while Wi-Fi is the active connection. This can identify a location and enters HA history when exposed; Android may hide it unless network-information permission is available.",
            haExposedByDefault = false,
            availableWhen = { it.hasWifiSsid },
            ha = HaEntity(
                "sensor", "diag_wifi_ssid", "Wi-Fi network",
                """"state_topic":"ha-paneld/{panel}/diag_wifi_ssid/state","icon":"mdi:wifi","entity_category":"diagnostic"""",
                readOnly = true,
            ),
        ),
        SettingSpec(
            key = "diag_wifi_rssi", type = SettingType.INT, group = "Diagnostics",
            label = "Wi-Fi signal strength", default = "",
            help = "Current Wi-Fi received signal strength in dBm while Wi-Fi is the active connection.",
            haExposedByDefault = false,
            availableWhen = { it.hasWifi },
            ha = HaEntity(
                "sensor", "diag_wifi_rssi", "Wi-Fi signal strength",
                """"state_topic":"ha-paneld/{panel}/diag_wifi_rssi/state","device_class":"signal_strength","unit_of_measurement":"dBm","state_class":"measurement","icon":"mdi:wifi","entity_category":"diagnostic"""",
                readOnly = true,
            ),
        ),

        // ---- Room climate (exact authenticated input layouts only) ----------------------------------
        // Real environmental sensors (NOT entity_category=diagnostic), reported by default like the
        // other readings in the Sensors card.
        // Read through the helper or a fixed Shizuku operation; only offered where the layout is proven.
        SettingSpec(
            key = "room_temp", type = SettingType.FLOAT, group = "Sensors",
            label = "Room temperature", default = "",
            help = "Room air temperature from the panel's supported climate sensor (calibration offset applied).",
            haExposedByDefault = true, availableWhen = { it.hasCht8305 },
            ha = HaEntity(
                "sensor", "room_temp", "Room temperature",
                """"state_topic":"ha-paneld/{panel}/room_temp/state","device_class":"temperature","unit_of_measurement":"°C","state_class":"measurement"""",
                readOnly = true,
            ),
        ),
        SettingSpec(
            key = "room_humidity", type = SettingType.INT, group = "Sensors",
            label = "Room humidity", default = "",
            help = "Relative humidity from the panel's supported climate sensor.",
            haExposedByDefault = true, availableWhen = { it.hasCht8305 },
            ha = HaEntity(
                "sensor", "room_humidity", "Room humidity",
                """"state_topic":"ha-paneld/{panel}/room_humidity/state","device_class":"humidity","unit_of_measurement":"%","state_class":"measurement"""",
                readOnly = true,
            ),
        ),
        // Self-heat calibration trim (°C) added to the reported room temperature. Advanced + local-only
        // (no HA entity); the profile carries a baseline and this is an additional user trim. API-settable.
        SettingSpec(
            key = "room_temp_offset", type = SettingType.FLOAT, group = "Sensors",
            label = "Room temperature offset", default = "0", tier = Tier.ADVANCED, scope = Scope.DEVICE,
            min = -20.0, max = 20.0, step = 0.1, availableWhen = { it.hasCht8305 },
            help = "Correction (°C) added to the reported room temperature — usually negative, since panel " +
                "self-heating reads high. Per-panel (depends on mounting), so not cloned by a fleet push.",
        ),
    )

    private val byKey: Map<String, SettingSpec> = SPECS.associateBy { it.key }

    fun spec(key: String): SettingSpec? = byKey[key]

    /** Settings accepted via the HTTP config API (everything settable; excludes publish-only sensors). */
    fun settable(): List<SettingSpec> = SPECS.filterNot { it.readOnly }

    /** Settable settings whose durable desired state must be applied through the shared live path. */
    fun liveApplyKeys(): List<String> = SPECS.filter { it.liveApply }.map { it.key }

    /** Config-key prefix for the per-setting "expose to HA" pip toggles. These booleans persist in
     *  SharedPreferences and travel in config bundles, so the string is a stored-format contract —
     *  the sole owner of the parse/construct convention lives here rather than at each call site. */
    const val HA_EXPOSE_PREFIX = "ha_expose_"
    /** Config entities that were implicitly exposed before schema 4; used only to preserve upgrades. */
    val LEGACY_DEFAULT_ON_HA_EXPOSURES: Set<String> = setOf(
        "wake_on_wave", "auto_sleep", "navbar_mode",
        "auto_brightness", "touch_sound", "cpu_governor",
    )

    /** The persisted config key for a spec's expose-to-HA pip — byte-identical to `"ha_expose_$key"`. */
    fun exposureKey(spec: SettingSpec): String = "$HA_EXPOSE_PREFIX${spec.key}"

    /** Resolve an `ha_expose_<key>` parameter name to the HA-capable spec it toggles, or null when the
     *  name is not an exposure key, names no registered setting, or names a setting that is never an HA
     *  entity (`ha == null`). The `ha != null` filter is what gates which specs get an expose pip. */
    fun parseExposure(name: String): SettingSpec? =
        name.takeIf { it.startsWith(HA_EXPOSE_PREFIX) }
            ?.removePrefix(HA_EXPOSE_PREFIX)
            ?.let(::spec)
            ?.takeIf { it.ha != null }
}

internal fun normalizeHttpOriginUrl(raw: String): String? {
    val uri = runCatching { java.net.URI(raw.trim()) }.getOrNull() ?: return null
    if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null ||
        uri.rawQuery != null || uri.rawFragment != null
    ) return null
    return uri.toString().trimEnd('/')
}
