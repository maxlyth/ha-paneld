package io.github.maxlyth.hapaneld.config

import java.util.Locale

/**
 * The authoritative, ordered list of ha-paneld settings. Adding a setting here makes it appear in the
 * HTTP config API + generated form, in bundles + revisions, and (when [SettingSpec.ha] is set and the
 * panel exposes it) as a Home Assistant entity — with no per-key wiring in three places.
 *
 * Coverage note: this first pass registers the settings whose current value is **Config-backed**
 * (read directly from SharedPreferences). Controller-sourced entities (touch sound, CPU profile,
 * network ADB, zigbee), option-driven selects (navbar/cpu), the core controls (screen/led/volume/
 * navigate), publish-only sensors, dynamic relay/button-LED entities, and the action buttons are
 * published by a separate discovery pass (they are not "settings" or need a runtime value provider);
 * they are gated in by capability at publish time. See the discovery rewrite (Stage C).
 */
object SettingsRegistry {

    /** Bump whenever the persisted shape changes; drives bundle migration. */
    const val SCHEMA = 1

    val SPECS: List<SettingSpec> = listOf(
        // ---- Identity ----------------------------------------------------------------------------
        SettingSpec(
            key = "panel_id", type = SettingType.STRING, group = "Identity",
            label = "Panel ID", default = "", tier = Tier.BASIC, scope = Scope.IDENTITY,
            help = "Stable id used in entity IDs and MQTT topics (lowercase, digits, underscores).",
            validate = { raw ->
                val slug = raw.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "_").trim('_')
                if (slug.isEmpty()) Validation.Bad("panel_id: must contain a letter or digit")
                else Validation.Ok(slug)
            },
        ),
        SettingSpec(
            key = "friendly_name", type = SettingType.STRING, group = "Identity",
            label = "Friendly name", default = "", tier = Tier.BASIC, scope = Scope.IDENTITY,
            help = "HA device display name.",
        ),
        SettingSpec(
            key = "manufacturer", type = SettingType.STRING, group = "Identity",
            label = "Manufacturer", default = "", scope = Scope.DEVICE,
            help = "HA device-card manufacturer override (blank = profile/auto).",
        ),
        SettingSpec(
            key = "model", type = SettingType.STRING, group = "Identity",
            label = "Model", default = "", scope = Scope.DEVICE,
            help = "HA device-card model override (blank = profile/auto).",
        ),

        // ---- MQTT --------------------------------------------------------------------------------
        SettingSpec(
            key = "mqtt_broker", type = SettingType.STRING, group = "MQTT",
            label = "Broker URL", default = "", tier = Tier.BASIC,
            help = "e.g. tcp://homeassistant.local:1883 — blank auto-discovers HA over mDNS.",
        ),
        SettingSpec(
            key = "mqtt_user", type = SettingType.STRING, group = "MQTT",
            label = "Username", default = "", tier = Tier.BASIC,
        ),
        SettingSpec(
            key = "mqtt_password", type = SettingType.PASSWORD, group = "MQTT",
            label = "Password", default = "", tier = Tier.BASIC, secret = true,
            help = "Blank on save keeps the current password.",
        ),

        // ---- Behaviour ---------------------------------------------------------------------------
        SettingSpec(
            key = "wake_on_wave", type = SettingType.BOOL, group = "Behaviour",
            label = "Wake on wave", default = "true", tier = Tier.BASIC,
            help = "Wake the screen locally the instant proximity reads near.",
            availableWhen = { it.hasProximity },
            haExposedByDefault = true,
            ha = HaEntity(
                "switch", "wake_on_wave", "Wake on wave",
                """"command_topic":"ha-paneld/{panel}/wake_on_wave/set","state_topic":"ha-paneld/{panel}/wake_on_wave/state","icon":"mdi:gesture-tap","entity_category":"config"""",
            ),
        ),
        SettingSpec(
            key = "prevent_idle_dim", type = SettingType.BOOL, group = "Behaviour",
            label = "Prevent idle dim", default = "true",
            help = "Stop the vendor firmware dimming the backlight at the screen-off timeout.",
            haExposedByDefault = true,
            ha = HaEntity(
                "switch", "prevent_idle_dim", "Prevent idle dim",
                """"command_topic":"ha-paneld/{panel}/prevent_idle_dim/set","state_topic":"ha-paneld/{panel}/prevent_idle_dim/state","icon":"mdi:brightness-7","entity_category":"config"""",
            ),
        ),
        SettingSpec(
            key = "watchdog_enabled", type = SettingType.BOOL, group = "Behaviour",
            label = "App watchdog", default = "false", scope = Scope.PORTABLE,
            help = "Self-heal the dashboard app: relaunch if it dies, return if backgrounded too long.",
            ha = HaEntity(
                "switch", "watchdog", "App watchdog",
                """"command_topic":"ha-paneld/{panel}/watchdog/set","state_topic":"ha-paneld/{panel}/watchdog/state","icon":"mdi:restart-alert","entity_category":"config"""",
            ),
        ),
        SettingSpec(
            key = "kiosk_lock", type = SettingType.BOOL, group = "Behaviour",
            label = "Kiosk lock (experimental)", default = "false", scope = Scope.DEVICE,
            help = "EXPERIMENTAL. Needs root — no effect on a panel without it. Suppress + disable the " +
                "Android system nav bar (Home/Recents/notification shade) so a non-admin can't accidentally " +
                "leave the dashboard. A reboot always clears it; release from here, HA, adb, or 7 rapid " +
                "taps in the top-left corner.",
            ha = HaEntity(
                "switch", "kiosk_lock", "Kiosk lock",
                """"command_topic":"ha-paneld/{panel}/kiosk_lock/set","state_topic":"ha-paneld/{panel}/kiosk_lock/state","icon":"mdi:lock","entity_category":"config"""",
            ),
        ),
        SettingSpec(
            key = "silence_boot_chime", type = SettingType.BOOL, group = "Behaviour",
            label = "Silence boot chime", default = "true", scope = Scope.DEVICE,
            help = "Mute the firmware startup chime.",
            ha = HaEntity(
                "switch", "silence_boot_chime", "Silence boot chime",
                """"command_topic":"ha-paneld/{panel}/silence_boot_chime/set","state_topic":"ha-paneld/{panel}/silence_boot_chime/state","icon":"mdi:volume-off","entity_category":"config"""",
            ),
        ),
        SettingSpec(
            key = "navbar_mode", type = SettingType.ENUM, group = "Behaviour",
            label = "Navbar mode", default = "Off",
            options = listOf("Off", "Always on", "Swipe reveal"),
            help = "Soft on-screen navigation bar for panels with no native navbar.",
            haExposedByDefault = true,
            // HA select entity is published by the discovery pass (option list comes from NavbarController).
        ),

        // ---- Display -----------------------------------------------------------------------------
        SettingSpec(
            key = "auto_brightness", type = SettingType.BOOL, group = "Display",
            label = "Auto-brightness", default = "false",
            help = "On-panel engine maps a lux stream to the backlight (off = HA drives the screen).",
            haExposedByDefault = true,
            ha = HaEntity(
                "switch", "auto_brightness", "Auto-brightness",
                """"command_topic":"ha-paneld/{panel}/auto_brightness/set","state_topic":"ha-paneld/{panel}/auto_brightness/state","icon":"mdi:brightness-auto","entity_category":"config"""",
            ),
        ),
        SettingSpec(
            key = "brightness_bias", type = SettingType.INT, group = "Display",
            label = "Brightness bias", default = "0", min = -100.0, max = 100.0, step = 5.0,
            help = "Dimmer (−) ↔ brighter (+) offset added to the auto-brightness curve.",
            ha = HaEntity(
                "number", "brightness_bias", "Brightness bias",
                """"command_topic":"ha-paneld/{panel}/brightness_bias/set","state_topic":"ha-paneld/{panel}/brightness_bias/state","min":-100,"max":100,"step":5,"mode":"slider","icon":"mdi:brightness-6","entity_category":"config"""",
            ),
        ),

        SettingSpec(
            key = "touch_sound", type = SettingType.BOOL, group = "Behaviour",
            label = "Touch sound", default = "true",
            help = "Audible tap feedback (system touch sounds).",
            haExposedByDefault = true,
            ha = HaEntity(
                "switch", "touch_sound", "Touch sound",
                """"command_topic":"ha-paneld/{panel}/touch_sound/set","state_topic":"ha-paneld/{panel}/touch_sound/state","icon":"mdi:volume-high","entity_category":"config"""",
            ),
        ),
        SettingSpec(
            key = "cpu_governor", type = SettingType.ENUM, group = "System",
            label = "CPU profile", default = "Auto",
            // Mirrors CpuController.TIERS (kept literal — this package is pure/Android-free).
            options = listOf("Performance", "Efficiency", "Auto"),
            help = "CPU scaling intent; Auto = the SoC's dynamic governor.",
            availableWhen = { it.cpuGovernors },
            haExposedByDefault = true,
            ha = HaEntity(
                "select", "cpu_governor", "CPU profile",
                """"command_topic":"ha-paneld/{panel}/cpu_governor/set","state_topic":"ha-paneld/{panel}/cpu_governor/state","options":["Performance","Efficiency","Auto"],"icon":"mdi:speedometer","entity_category":"config"""",
            ),
        ),
        SettingSpec(
            key = "network_adb", type = SettingType.BOOL, group = "System",
            label = "Network ADB", default = "false", scope = Scope.DEVICE,
            help = "Standing LAN adb on :5555, re-asserted at boot/reconnect (root panels).",
            availableWhen = { it.networkAdb },
            ha = HaEntity(
                "switch", "network_adb", "Network ADB",
                """"command_topic":"ha-paneld/{panel}/network_adb/set","state_topic":"ha-paneld/{panel}/network_adb/state","icon":"mdi:adb","entity_category":"config"""",
            ),
        ),
        SettingSpec(
            key = "zigbee_router", type = SettingType.BOOL, group = "System",
            label = "Zigbee router", default = "false", scope = Scope.DEVICE,
            help = "Run the on-board Zigbee gateway as a router/repeater (NSPanel Pro).",
            availableWhen = { it.zigbeePresent },
            ha = HaEntity(
                "switch", "zigbee_router", "Zigbee router",
                """"command_topic":"ha-paneld/{panel}/zigbee_router/set","state_topic":"ha-paneld/{panel}/zigbee_router/state","icon":"mdi:zigbee","entity_category":"config"""",
            ),
        ),
        SettingSpec(
            key = "ambient_lux", type = SettingType.INT, group = "Display",
            label = "Ambient lux (HA-fed)", default = "0", min = 0.0, max = 100000.0,
            transient = true,
            help = "Room lux an HA automation feeds to auto-brightness (sensor-less panels).",
            haExposedByDefault = true,
            ha = HaEntity(
                "number", "ambient_lux", "Ambient lux (HA-fed)",
                """"command_topic":"ha-paneld/{panel}/ambient_lux/set","state_topic":"ha-paneld/{panel}/ambient_lux/state","min":0,"max":100000,"step":1,"mode":"box","unit_of_measurement":"lx","icon":"mdi:brightness-5","entity_category":"config"""",
            ),
        ),
        SettingSpec(
            key = "keep_awake", type = SettingType.BOOL, group = "Behaviour",
            label = "Keep awake", default = "true",
            help = "Hold a partial wakelock so the SoC/network never suspend (screen still sleeps freely).",
        ),

        // ---- Dashboard ---------------------------------------------------------------------------
        // Which app renders the dashboard + (for the skunk-works built-in renderer) how it connects to HA.
        SettingSpec(
            key = "dashboard_package", type = SettingType.STRING, group = "Dashboard",
            label = "Dashboard app", default = "", picker = "renderer",
            help = "Which app renders the dashboard: the HA Companion, Fully Kiosk, or ha-paneld's built-in renderer (skunk-works). Blank = auto-detect the installed Companion.",
        ),
        SettingSpec(
            key = "home_dashboard", type = SettingType.STRING, group = "Dashboard",
            label = "Home dashboard", default = "",
            help = "Local dashboard path a reload returns to, e.g. /lovelace/0 (built-in renderer: the view it loads). Blank = wherever it was.",
        ),
        SettingSpec(
            key = "dashboard_fullscreen", type = SettingType.BOOL, group = "Dashboard",
            label = "Fullscreen dashboard", default = "true",
            help = "Built-in renderer: hide the Android status and navigation bars for an edge-to-edge dashboard (swipe from a screen edge to reveal them briefly). Turn off to keep the system bars visible.",
        ),
        SettingSpec(
            key = "dashboard_overscroll", type = SettingType.BOOL, group = "Dashboard",
            label = "Dashboard overscroll effect", default = "false", hidden = true,
            help = "Built-in renderer: allow Android's overscroll stretch/glow when a drag runs past " +
                "the top or bottom of the dashboard. Off by default (a wall panel rarely scrolls, and " +
                "the bounce looks out of place). API-only — set true to restore the native effect.",
        ),
        SettingSpec(
            key = "dashboard_idle_return_min", type = SettingType.INT, group = "Dashboard",
            label = "Idle return to home (min)", default = "0", min = 0.0, max = 1440.0,
            help = "Built-in renderer: minutes with no touch before the dashboard snaps back to the Home dashboard view (instant in-app navigation, not a reload). 0 = off; needs Home dashboard set.",
        ),
        SettingSpec(
            key = "dashboard_zoom", type = SettingType.INT, group = "Dashboard",
            label = "Dashboard zoom (%)", default = "100", min = 50.0, max = 300.0, step = 10.0,
            help = "Built-in renderer page zoom % (100 = Companion default). Prefer Display sizing " +
                "(density) for crisp scaling; this is a compatibility fallback.",
        ),
        SettingSpec(
            key = "ha_url", type = SettingType.STRING, group = "Dashboard",
            label = "Home Assistant URL", default = "",
            help = "Built-in renderer only (skunk-works): the HA base URL, e.g. http://homeassistant.local:8123. Blank disables the built-in renderer.",
        ),
        SettingSpec(
            key = "dark_mode", type = SettingType.BOOL, group = "Display",
            label = "Dark mode", default = "true",
            help = "Themes ha-paneld's own screens and sets the dashboard's default colour scheme on panels without a system dark-mode setting (Android 9 and older). A theme picked inside Home Assistant overrides the dashboard default; this web UI always follows the viewing browser's own preference.",
            // Panels with a native system dark/light control (Android 10+) follow the OS setting for
            // everything, so the toggle is hidden there.
            availableWhen = { !it.hasSystemDarkMode },
        ),
        SettingSpec(
            key = "ha_token", type = SettingType.PASSWORD, group = "Dashboard",
            label = "HA access token", default = "", secret = true,
            help = "Built-in renderer only: the access token it signs into HA with (a long-lived token, or the current short-lived one when a refresh token is set). Blank on save keeps the current token; entering a NEW token replaces any stored refresh token (the recovery path for a revoked login).",
        ),
        SettingSpec(
            key = "ha_refresh_token", type = SettingType.PASSWORD, group = "Dashboard",
            label = "HA refresh token", default = "", secret = true,
            help = "Built-in renderer only: optional OAuth refresh token (from a username/password login at provisioning). When set, the renderer refreshes its own access token, so no long-lived token lives on the panel. Blank on save keeps the current one.",
        ),
        SettingSpec(
            key = "ha_client_id", type = SettingType.STRING, group = "Dashboard",
            label = "HA OAuth client_id", default = "",
            help = "Built-in renderer only: client_id used when refreshing the token. Blank = the HA URL (the frontend default). Set it to the client the refresh token was issued for, e.g. https://home-assistant.io/android to reuse an HA Companion refresh token.",
        ),

        // ---- System ------------------------------------------------------------------------------
        SettingSpec(
            key = "self_update", type = SettingType.BOOL, group = "System",
            label = "ha-paneld auto-update", default = "false", scope = Scope.DEVICE,
            help = "ha-paneld updates itself from GitHub releases on the selected channel (opt-in).",
            ha = HaEntity(
                "switch", "self_update", "ha-paneld auto-update",
                """"command_topic":"ha-paneld/{panel}/self_update/set","state_topic":"ha-paneld/{panel}/self_update/state","icon":"mdi:package-up","entity_category":"config"""",
            ),
        ),
        SettingSpec(
            key = "update_channel", type = SettingType.ENUM, group = "System",
            label = "ha-paneld auto-update channel", default = "stable", options = listOf("stable", "prerelease"),
            scope = Scope.DEVICE,
            help = "Release channel the self-updater follows.",
            ha = HaEntity(
                "select", "update_channel", "ha-paneld auto-update channel",
                """"command_topic":"ha-paneld/{panel}/update_channel/set","state_topic":"ha-paneld/{panel}/update_channel/state","options":["Stable","Pre-release"],"icon":"mdi:source-branch","entity_category":"config"""",
            ),
        ),
        SettingSpec(
            key = "companion_auto_update", type = SettingType.BOOL, group = "System",
            label = "Companion auto-update", default = "false", scope = Scope.DEVICE,
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
            help = "Keep the System WebView on this panel's recommended build (from the ha-paneld mirror), installing a newer one over root on the update check. Off by default — a WebView swap needs a restart to take effect. Only shown where a recommended build exists (not on Play-updated panels).",
            availableWhen = { it.webViewManaged },
            ha = HaEntity(
                "switch", "webview_auto_update", "WebView auto-update",
                """"command_topic":"ha-paneld/{panel}/webview_auto_update/set","state_topic":"ha-paneld/{panel}/webview_auto_update/state","icon":"mdi:web-sync","entity_category":"config"""",
            ),
        ),
        SettingSpec(
            key = "launcher_package", type = SettingType.STRING, group = "System",
            label = "Launcher app", default = "", picker = "package",
            help = "App the Launcher button brings forward (blank = auto-pick).",
        ),
        // Vendor taming is managed by the "Vendor packages" card (add/re-enable per app), so no free-text
        // field here — the card is the single source. `config.tameVendorPackages` + POST /tame still drive it.

        // ---- Logging -----------------------------------------------------------------------------
        SettingSpec(
            key = "log_ship_enabled", type = SettingType.BOOL, group = "Logging",
            label = "Ship logs", default = "false", scope = Scope.DEVICE,
            help = "Forward this panel's own logcat to a central sink (LAN-only, redacted).",
        ),
        SettingSpec(
            key = "log_ship_host", type = SettingType.STRING, group = "Logging",
            label = "Sink host", default = "", scope = Scope.DEVICE,
            help = "Log-collector host; blank keeps shipping inert.",
        ),
        SettingSpec(
            key = "log_ship_port", type = SettingType.INT, group = "Logging",
            label = "Sink port", default = "514", min = 1.0, max = 65535.0, scope = Scope.DEVICE,
        ),
        SettingSpec(
            key = "log_ship_protocol", type = SettingType.ENUM, group = "Logging",
            label = "Protocol", default = "syslog", options = listOf("syslog", "http"),
            scope = Scope.DEVICE,
        ),

        // ---- Diagnostics -------------------------------------------------------------------------
        // Publish-only panel telemetry (readOnly sensors), all OPT-IN (haExposedByDefault=false): the
        // panel stays quiet in HA until a pip is enabled. Values come from [control.Diagnostics] and
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
            label = "Boot time", default = "",
            help = "When the panel last booted (a timestamp — HA shows the elapsed uptime).",
            haExposedByDefault = false,
            ha = HaEntity(
                "sensor", "diag_boot", "Boot time",
                """"state_topic":"ha-paneld/{panel}/diag_boot/state","device_class":"timestamp","icon":"mdi:clock-start","entity_category":"diagnostic"""",
                readOnly = true,
            ),
        ),

        // ---- Room climate (CHT8305 panels only, e.g. TPA10) --------------------------------------
        // Real environmental sensors (NOT entity_category=diagnostic), opt-in like the diagnostics above.
        // Read root-only via the helper daemon; only offered where the chip is present.
        SettingSpec(
            key = "room_temp", type = SettingType.FLOAT, group = "Diagnostics",
            label = "Room temperature", default = "",
            help = "Room air temperature from the panel's CHT8305 sensor (calibration offset applied).",
            haExposedByDefault = false, availableWhen = { it.hasCht8305 },
            ha = HaEntity(
                "sensor", "room_temp", "Room temperature",
                """"state_topic":"ha-paneld/{panel}/room_temp/state","device_class":"temperature","unit_of_measurement":"°C","state_class":"measurement"""",
                readOnly = true,
            ),
        ),
        SettingSpec(
            key = "room_humidity", type = SettingType.INT, group = "Diagnostics",
            label = "Room humidity", default = "",
            help = "Relative humidity from the panel's CHT8305 sensor.",
            haExposedByDefault = false, availableWhen = { it.hasCht8305 },
            ha = HaEntity(
                "sensor", "room_humidity", "Room humidity",
                """"state_topic":"ha-paneld/{panel}/room_humidity/state","device_class":"humidity","unit_of_measurement":"%","state_class":"measurement"""",
                readOnly = true,
            ),
        ),
        // Self-heat calibration trim (°C) added to the reported room temperature. Advanced + local-only
        // (no HA entity); the profile carries a baseline and this is an additional user trim. API-settable.
        SettingSpec(
            key = "room_temp_offset", type = SettingType.FLOAT, group = "Diagnostics",
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
}
