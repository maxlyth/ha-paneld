package io.github.maxlyth.hapaneld.config

import io.github.maxlyth.hapaneld.util.Json

/**
 * Declarative settings registry — the single source of truth for ha-paneld configuration.
 *
 * A [SettingSpec] describes one setting once; three consumers read it so they can never drift:
 *  1. the HTTP `/api/v1/config` API (validation + accept + the form-generation schema),
 *  2. Home Assistant MQTT discovery ([HaEntity] → discovery payload, gated by per-panel exposure),
 *  3. config bundle export/import + on-panel revision history.
 *
 * Everything in this package is **pure / Android-free** so it runs on the plain JVM unit-test
 * classpath (which has no `org.json` and no Android stubs). JSON is hand-built by string
 * concatenation here — matching the existing `PaneldServer.configJson()` / `MqttBridge` style —
 * and parsing of inbound JSON happens at the Android edge, handing a plain `Map<String,String>`
 * to this layer.
 */

/** Value type of a setting; drives coercion, validation, and the generated form control. */
enum class SettingType { BOOL, INT, FLOAT, ENUM, STRING, PASSWORD }

/** UI disclosure tier. BASIC settings show on the short Configure page; ADVANCED behind a reveal. */
enum class Tier { BASIC, ADVANCED }

/**
 * Portability classification, used by bundle import to decide what a fleet deploy may apply.
 *  - IDENTITY: per-panel identity (panel_id, friendly_name). Restored to the same panel; never cloned.
 *  - DEVICE:   hardware/site-specific (model, cpu profile, log sink). Restored; not cloned by default.
 *  - PORTABLE: behaviour that is safe to push fleet-wide.
 */
enum class Scope { IDENTITY, DEVICE, PORTABLE }

/** Outcome of validating one raw value against a spec. */
sealed class Validation {
    /** Valid; carries the canonical normalized string form to persist. */
    data class Ok(val normalized: String) : Validation()

    /** Invalid; carries a human-readable reason for the API error. */
    data class Bad(val reason: String) : Validation()
}

/**
 * Immutable capability snapshot for a single panel. Built by the service from the same profile +
 * runtime probes it already feeds `MqttBridge`, then consumed by [SettingSpec.availableWhen] and the
 * discovery loop. Plain data class so both stay unit-testable without Android.
 */
data class Capabilities(
    val hasProximity: Boolean = false,
    val hasLight: Boolean = false,
    val hasTemperature: Boolean = false,
    val hasHumidity: Boolean = false,
    val hasCht8305: Boolean = false,
    val hasButtonBacklight: Boolean = false,
    val buttonsEnabled: Boolean = false,
    val hasEvdevButtons: Boolean = false,
    val appCanSu: Boolean = false,
    val hasRecents: Boolean = false,
    val ledAvailable: Boolean = false,
    val ledColorCapable: Boolean = false,
    val zigbeePresent: Boolean = false,
    val cpuGovernors: Boolean = false,
    val networkAdb: Boolean = false,
    val relays: Int = 0,
    val buttonLeds: Int = 0,
    // Android 10+ ships a system-wide dark/light setting; panels that have it follow the OS and hide
    // ha-paneld's own dark_mode toggle (Android 9- panels have no such control, so ours fills the gap).
    val hasSystemDarkMode: Boolean = false,
    // HA Companion (full or minimal) present on the panel — gates the Companion auto-update settings
    // (meaningless without it) and their HA entities.
    val companionInstalled: Boolean = false,
    // The profile pins a known-good System WebView build in the webview-mirror release — gates the
    // webview_auto_update setting (there's nothing to update to without a pin; e.g. Play-updated panels).
    val webViewManaged: Boolean = false,
    // Runtime-only privilege routes. These describe what the panel can do now; they are never config
    // switches and therefore cannot be imported or changed through MQTT/HTTP.
    val shizukuReady: Boolean = false,
    val canInstallApk: Boolean = false,
    val canCaptureAndInput: Boolean = false,
    val canSetDisplay: Boolean = false,
)

/**
 * Home Assistant discovery descriptor for a setting/control. `null` on a [SettingSpec] means the
 * setting is never an HA entity (e.g. panel_id, log sink host).
 *
 * [body] holds the entity-specific middle of the discovery JSON — every field between `unique_id`
 * and the shared `availability` + `device` fragments — with `{panel}` as the topic placeholder.
 * Keeping the body verbatim (rather than re-deriving field order) guarantees the generated payload
 * is byte-identical to today's hand-written discovery, so existing HA installs see no entity churn.
 */
data class HaEntity(
    val component: String,        // light | switch | number | select | sensor | binary_sensor | text | button | event
    val objectSuffix: String,     // unique_id/object_id suffix; full id = "<panel>_<objectSuffix>"
    val name: String,             // HA entity name ("Wake on wave")
    val body: String,             // entity-specific fields, comma-joined, no leading/trailing comma; {panel} = panel id
    val readOnly: Boolean = false,// publish-only (sensors): no /api/v1/config row, value comes from the runtime
) {
    /** State topic this entity reports on, e.g. `ha-paneld/<panel>/wake_on_wave/state`. */
    fun stateTopic(panel: String): String = "ha-paneld/$panel/$objectSuffix/state"

    /**
     * Build the retained discovery payload, byte-identical to the legacy hand-written JSON.
     * [deviceJson] is the shared `"device":{...}` fragment, [availJson] the shared availability
     * fragment — both produced once per publish by the caller. [enabledByDefault]=false appends the
     * soft-hide flag (entity exists in HA but disabled → no recorder load) for the advanced hide mode.
     */
    fun buildDiscoveryJson(
        panel: String,
        availJson: String,
        deviceJson: String,
        enabledByDefault: Boolean = true,
    ): String {
        val edb = if (enabledByDefault) "" else ""","enabled_by_default":false"""
        // object_id keys the HA entity_id to the stable panel_id, not the cosmetic device name —
        // matches the hand-written discovery payloads (discovery-identity anchoring).
        return "{\"name\":\"${jsonEsc(name)}\",\"object_id\":\"${panel}_$objectSuffix\"," +
            "\"unique_id\":\"${panel}_$objectSuffix\"," +
            body.replace("{panel}", panel) + edb + ",$availJson,$deviceJson}"
    }

    companion object {
        fun jsonEsc(s: String): String = Json.esc(s)
    }
}

/**
 * One configuration setting. [key] is the SharedPreferences key, the `/api/v1/config` field name,
 * and the bundle key. [default] is the canonical string form (typed reads coerce it).
 */
data class SettingSpec(
    val key: String,
    val type: SettingType,
    val group: String,                       // UI grouping ("Identity", "MQTT", "Behaviour", "Display", "System", "Logging")
    val label: String,
    val help: String = "",
    val default: String,
    val tier: Tier = Tier.ADVANCED,
    // Fleet imports are intentionally fail-closed: a new setting stays local until its author
    // explicitly confirms that copying it to every panel is safe.
    val scope: Scope = Scope.DEVICE,
    val secret: Boolean = false,             // redacted from GET /config + excluded from export by default
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val options: List<String> = emptyList(), // ENUM choices
    val picker: String? = null,              // dynamic picker for a STRING field ("package" → installed-apps dropdown)
    val ha: HaEntity? = null,                // discovery descriptor, or null if never an HA entity
    // Per-panel default for the expose-to-HA pip. Defaults to FALSE: a setting is local-only (HTTP UI)
    // unless it explicitly opts in with haExposedByDefault=true in SettingsRegistry. Consulted by the
    // discovery gate for every HA-publishable setting (those with `ha`, plus the discovery-pass entities
    // like navbar that carry no `ha` block here). A per-panel expose pip can still override it either way.
    val haExposedByDefault: Boolean = false,
    val transient: Boolean = false,          // accepted + routed to a controller but never persisted (e.g. ambient_lux)
    // Omit from the generated Configure form (an API-only setting): still readable via GET /config,
    // settable via POST /config, and carried in config bundles — just never rendered as a form field.
    val hidden: Boolean = false,
    val availableWhen: (Capabilities) -> Boolean = { true },
    val validate: (String) -> Validation = { Validation.Ok(it) },
) {
    /** True for publish-only sensor entities that have no settable value. */
    val readOnly: Boolean get() = ha?.readOnly == true
}
