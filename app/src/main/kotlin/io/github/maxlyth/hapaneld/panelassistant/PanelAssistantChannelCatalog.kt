package io.github.maxlyth.hapaneld.panelassistant

import io.github.maxlyth.hapaneld.assist.VoiceState
import io.github.maxlyth.hapaneld.config.SettingsRegistry
import io.github.maxlyth.hapaneld.mqtt.SoftwareComponent
import io.github.maxlyth.hapaneld.mqtt.SoftwareUpdateEntities
import io.github.maxlyth.hapaneld.storage.StorageHealthSeverity
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** How an MQTT state payload becomes a typed value on the native transport (protocol section 7). */
internal enum class PanelAssistantValueKind { BOOLEAN, NUMBER, OPTION, TEXT, LIGHT, UPDATE }

/** One channel as the `hello` describes it. Codes and facts only, never display text. */
internal data class PanelAssistantChannelDescriptor(
    val channel: String,
    val platform: String,
    val translationKey: String,
    val uniqueSuffix: String,
    val kind: PanelAssistantValueKind,
    val family: String? = null,
    val index: Int? = null,
    val entityCategory: String? = null,
    val enabledDefault: Boolean = true,
    val deviceClass: String? = null,
    val unit: String? = null,
    val stateClass: String? = null,
    val forceUpdate: Boolean = false,
    val options: List<String>? = null,
    val min: Number? = null,
    val max: Number? = null,
    val step: Number? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("channel", channel)
        .put("platform", platform)
        .put("translation_key", translationKey)
        .put("unique_suffix", uniqueSuffix)
        .put("family", family ?: JSONObject.NULL)
        .put("index", index ?: JSONObject.NULL)
        .put("entity_category", entityCategory ?: JSONObject.NULL)
        .put("enabled_default", enabledDefault)
        .put("device_class", deviceClass ?: JSONObject.NULL)
        .put("unit", unit ?: JSONObject.NULL)
        .put("state_class", stateClass ?: JSONObject.NULL)
        .put("force_update", forceUpdate)
        .put("options", options?.let(::JSONArray) ?: JSONObject.NULL)
        .put("min", min ?: JSONObject.NULL)
        .put("max", max ?: JSONObject.NULL)
        .put("step", step ?: JSONObject.NULL)
}

/**
 * The native transport's view of the converger's channels, derived from the same registrations the MQTT
 * edge announces: registry-backed entities from their [SettingsRegistry] discovery descriptor, matched by
 * the state topic leaf (which is the channel), and the hand-written discovery entities from the literals
 * in `MqttBridge.publishDiscovery`. `unique_suffix` is always today's MQTT unique id after `<panel>_`.
 *
 * Two converger channels are MQTT plumbing rather than channels on the wire: their attribute payloads
 * are folded into the parent channel's observation (protocol section 16). The update channels are
 * renamed to their wire ids.
 */
internal object PanelAssistantChannelCatalog {
    /** Attribute channel → the channel whose observation carries its attributes. */
    val FOLDED: Map<String, String> = mapOf(
        "storage_health_attributes" to "storage_health",
        "diag_wifi_outages_attributes" to "diag_wifi_outages_24h",
    )

    private val RENAMED: Map<String, String> = SoftwareComponent.entries.associate {
        SoftwareUpdateEntities.stateChannelKey(it) to "update_${it.wire}"
    }

    /** The wire channel an MQTT converger channel reports on; null for a folded attribute channel. */
    fun wireChannel(converger: String): String? =
        if (converger in FOLDED) null else RENAMED[converger] ?: converger

    /** Sensors with no closed value set; their string payload goes on the wire unchanged. */
    private val TEXT_SENSORS = setOf("diag_ip", "diag_boot", "diag_wifi_ssid")

    /** Closed value sets of sensors whose registry descriptor carries no options. */
    private val SENSOR_OPTIONS: Map<String, List<String>> = mapOf(
        "voice_state" to VoiceState.entries.map { it.wireValue },
    )

    private val FAMILY = Regex("^(relay|button_led)([1-9][0-9]*)$")

    private val registryByLeaf: Map<String, io.github.maxlyth.hapaneld.config.SettingSpec> by lazy {
        SettingsRegistry.haCapable().mapNotNull { spec ->
            val topic = registryBody(spec).optString("state_topic")
            STATE_LEAF.find(topic)?.groupValues?.get(1)?.let { it to spec }
        }.toMap()
    }

    private val STATE_LEAF = Regex("^ha-paneld/panel/([a-z][a-z0-9_]*)/state$")

    private fun registryBody(spec: io.github.maxlyth.hapaneld.config.SettingSpec): JSONObject {
        val entity = requireNotNull(spec.ha)
        val options = JSONArray(spec.options).toString()
        return JSONObject("{" + entity.body.replace("{panel}", "panel").replace("{options}", options) + "}")
    }

    /** A snake_case code for an MQTT display label: `Pre-release` → `prerelease`, `Always on` → `always_on`. */
    fun optionCode(label: String): String = label.lowercase(Locale.ROOT)
        .replace("-", "")
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')

    /** The descriptor of a wire channel, or null when this build does not know it. */
    fun describe(wire: String): PanelAssistantChannelDescriptor? {
        FAMILY.matchEntire(wire)?.let { match ->
            val family = match.groupValues[1]
            val relay = family == "relay"
            return PanelAssistantChannelDescriptor(
                channel = wire,
                platform = if (relay) "switch" else "light",
                translationKey = family,
                uniqueSuffix = wire,
                kind = if (relay) PanelAssistantValueKind.BOOLEAN else PanelAssistantValueKind.LIGHT,
                family = family,
                index = match.groupValues[2].toInt(),
            )
        }
        HAND_WRITTEN[wire]?.let { return it }
        val spec = registryByLeaf[wire] ?: return null
        val entity = requireNotNull(spec.ha)
        val body = registryBody(spec)
        val platform = entity.component
        val options = body.optJSONArray("options")
            ?.let { array -> (0 until array.length()).map { optionCode(array.getString(it)) } }
            ?: SENSOR_OPTIONS[wire]
        val kind = when (platform) {
            "switch", "binary_sensor" -> PanelAssistantValueKind.BOOLEAN
            "number" -> PanelAssistantValueKind.NUMBER
            "select" -> PanelAssistantValueKind.OPTION
            "light" -> PanelAssistantValueKind.LIGHT
            "text" -> PanelAssistantValueKind.TEXT
            else -> when {
                options != null -> PanelAssistantValueKind.OPTION
                wire in TEXT_SENSORS -> PanelAssistantValueKind.TEXT
                else -> PanelAssistantValueKind.NUMBER
            }
        }
        return PanelAssistantChannelDescriptor(
            channel = wire,
            platform = platform,
            translationKey = wire,
            uniqueSuffix = entity.objectSuffix,
            kind = kind,
            entityCategory = body.optString("entity_category").ifEmpty { null },
            enabledDefault = spec.haExposedByDefault,
            deviceClass = body.optString("device_class").ifEmpty { null },
            unit = body.optString("unit_of_measurement").ifEmpty { null },
            stateClass = body.optString("state_class").ifEmpty { null },
            forceUpdate = entity.periodicRefresh,
            options = options,
            min = body.opt("min") as? Number,
            max = body.opt("max") as? Number,
            step = body.opt("step") as? Number,
        )
    }

    private fun wireOnly(channel: String, platform: String, options: List<String>? = null) =
        PanelAssistantChannelDescriptor(
            channel = channel,
            platform = platform,
            translationKey = channel,
            // The retired discovery tombstone's id, so a later entity attaches to the same suffix.
            uniqueSuffix = channel,
            kind = if (options == null) PanelAssistantValueKind.BOOLEAN else PanelAssistantValueKind.OPTION,
            entityCategory = "config",
            // No MQTT entity exists for these today (protocol section 15).
            enabledDefault = false,
            options = options,
        )

    private val UPDATE_CHANNEL_OPTIONS = listOf("Stable", "Pre-release").map(::optionCode)

    private val HAND_WRITTEN: Map<String, PanelAssistantChannelDescriptor> = listOf(
        PanelAssistantChannelDescriptor(
            channel = "led", platform = "light", translationKey = "led", uniqueSuffix = "led",
            kind = PanelAssistantValueKind.LIGHT, options = listOf("none", "strobe", "blink", "pulse"),
        ),
        PanelAssistantChannelDescriptor(
            channel = "buttons", platform = "light", translationKey = "buttons", uniqueSuffix = "buttons",
            kind = PanelAssistantValueKind.LIGHT,
        ),
        PanelAssistantChannelDescriptor(
            channel = "navigate", platform = "text", translationKey = "navigate", uniqueSuffix = "navigate",
            kind = PanelAssistantValueKind.TEXT,
        ),
        PanelAssistantChannelDescriptor(
            channel = "home_dashboard", platform = "text", translationKey = "home_dashboard",
            uniqueSuffix = "home_dashboard", kind = PanelAssistantValueKind.TEXT, entityCategory = "config",
        ),
        PanelAssistantChannelDescriptor(
            channel = "storage_health", platform = "sensor", translationKey = "storage_health",
            uniqueSuffix = "storage_health", kind = PanelAssistantValueKind.OPTION, entityCategory = "diagnostic",
            options = StorageHealthSeverity.entries.map { it.name.lowercase(Locale.ROOT) },
        ),
        wireOnly("watchdog", "switch"),
        wireOnly("silence_boot_chime", "switch"),
        wireOnly("prevent_idle_dim", "switch"),
        wireOnly("self_update", "switch"),
        wireOnly("zigbee_router", "switch"),
        wireOnly("update_channel", "select", UPDATE_CHANNEL_OPTIONS),
    ).associateBy { it.channel } + SoftwareComponent.entries.associate { component ->
        val wire = "update_${component.wire}"
        wire to PanelAssistantChannelDescriptor(
            channel = wire,
            platform = "update",
            translationKey = wire,
            uniqueSuffix = SoftwareUpdateEntities.uniqueId("", component).removePrefix("_"),
            kind = PanelAssistantValueKind.UPDATE,
            entityCategory = "config",
        )
    }
}
