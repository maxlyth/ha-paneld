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
            ha = HaEntity(
                "switch", "wake_on_wave", "Wake on wave",
                """"command_topic":"ha-paneld/{panel}/wake_on_wave/set","state_topic":"ha-paneld/{panel}/wake_on_wave/state","icon":"mdi:gesture-tap","entity_category":"config"""",
            ),
        ),
        SettingSpec(
            key = "prevent_idle_dim", type = SettingType.BOOL, group = "Behaviour",
            label = "Prevent idle dim", default = "true",
            help = "Stop the vendor firmware dimming the backlight at the screen-off timeout.",
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
            key = "silence_boot_chime", type = SettingType.BOOL, group = "Behaviour",
            label = "Silence boot chime", default = "false", scope = Scope.DEVICE,
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
            // HA select entity is published by the discovery pass (option list comes from NavbarController).
        ),

        // ---- Display -----------------------------------------------------------------------------
        SettingSpec(
            key = "auto_brightness", type = SettingType.BOOL, group = "Display",
            label = "Auto-brightness", default = "false",
            help = "On-panel engine maps a lux stream to the backlight (off = HA drives the screen).",
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
            key = "keep_awake", type = SettingType.BOOL, group = "Behaviour",
            label = "Keep awake", default = "true",
            help = "Hold a partial wakelock so the SoC/network never suspend (screen still sleeps freely).",
        ),
        SettingSpec(
            key = "home_dashboard", type = SettingType.STRING, group = "Behaviour",
            label = "Home dashboard", default = "",
            help = "Local dashboard path a reload returns to (e.g. /lovelace/0; blank = wherever it was).",
        ),

        // ---- System ------------------------------------------------------------------------------
        SettingSpec(
            key = "companion_auto_update", type = SettingType.BOOL, group = "System",
            label = "Companion auto-update", default = "false", scope = Scope.DEVICE,
            help = "Install/update the minimal HA Companion over root when missing or out of date.",
            ha = HaEntity(
                "switch", "companion_auto_update", "Companion auto-update",
                """"command_topic":"ha-paneld/{panel}/companion_auto_update/set","state_topic":"ha-paneld/{panel}/companion_auto_update/state","icon":"mdi:cellphone-arrow-down","entity_category":"config"""",
            ),
        ),
        SettingSpec(
            key = "self_update", type = SettingType.BOOL, group = "System",
            label = "Self-update", default = "false", scope = Scope.DEVICE,
            help = "ha-paneld updates itself from GitHub releases on the selected channel (opt-in).",
            ha = HaEntity(
                "switch", "self_update", "Self-update",
                """"command_topic":"ha-paneld/{panel}/self_update/set","state_topic":"ha-paneld/{panel}/self_update/state","icon":"mdi:package-up","entity_category":"config"""",
            ),
        ),
        SettingSpec(
            key = "update_channel", type = SettingType.ENUM, group = "System",
            label = "Update channel", default = "stable", options = listOf("stable", "prerelease"),
            scope = Scope.DEVICE,
            help = "Release channel the self-updater follows.",
            ha = HaEntity(
                "select", "update_channel", "Update channel",
                """"command_topic":"ha-paneld/{panel}/update_channel/set","state_topic":"ha-paneld/{panel}/update_channel/state","options":["Stable","Pre-release"],"icon":"mdi:source-branch","entity_category":"config"""",
            ),
        ),
        SettingSpec(
            key = "dashboard_package", type = SettingType.STRING, group = "System",
            label = "Dashboard app", default = "",
            help = "Package whose force-stop+relaunch is the dashboard \"reload\" (blank = auto-detect).",
        ),
        SettingSpec(
            key = "launcher_package", type = SettingType.STRING, group = "System",
            label = "Launcher app", default = "",
            help = "Package the Launcher button brings forward (blank = auto-pick).",
        ),
        SettingSpec(
            key = "tame_vendor_packages", type = SettingType.STRING, group = "System",
            label = "Tame vendor packages", default = "",
            help = "Space/comma-separated packages to force-stop + disable on boot (opt-in).",
        ),
        SettingSpec(
            key = "instrumentation", type = SettingType.BOOL, group = "System",
            label = "Performance sampler", default = "true",
            help = "Master switch for the (page-view gated) performance sampler.",
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
            help = "Collector host (e.g. a Vector instance); blank keeps shipping inert.",
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
    )

    private val byKey: Map<String, SettingSpec> = SPECS.associateBy { it.key }

    fun spec(key: String): SettingSpec? = byKey[key]

    /** Settings accepted via the HTTP config API (everything settable; excludes publish-only sensors). */
    fun settable(): List<SettingSpec> = SPECS.filterNot { it.readOnly }
}
