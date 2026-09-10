package io.github.maxlyth.hapaneld.util

import org.json.JSONObject

/**
 * Bounded projection of this panel's own device identity, for the Panel Assistant integration's
 * Home Assistant device card.
 *
 * Reading it changes nothing: no release lookup, no MQTT traffic, no settings write. Every field is
 * presentation text, so anything blank, over-long or carrying control characters is represented as
 * absence rather than guessed or truncated.
 *
 * The Android id is deliberately omitted. The mDNS `did` token is a domain-separated pseudonym of
 * that id precisely so the raw value never leaves the panel, and the integration registers its own
 * Home Assistant device rather than adopting the MQTT bridge's identifiers, so it has no need of
 * MQTT's `serial_number` to match on.
 */
object PanelAssistantDevice {
    /** `DeviceProfile` publishes the model with this suffix to mark the app, not the hardware. */
    const val APP_MODEL_SUFFIX = " (ha-paneld)"

    private const val MAX_FIELD_LENGTH = 128

    /** Bounded, printable, single-line presentation text, or absence. */
    internal fun field(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed.length > MAX_FIELD_LENGTH) return null
        if (trimmed.any { it.isISOControl() }) return null
        return trimmed
    }

    /** The hardware model alone; the advertised value carries the app marker. */
    internal fun hardwareModel(advertised: String?): String? =
        field(advertised?.trimEnd()?.removeSuffix(APP_MODEL_SUFFIX))

    /** Matches the MQTT bridge's `hw_version` so both device cards read identically. */
    internal fun hardwareVersion(androidRelease: String?, buildDisplay: String?): String? {
        val release = field(androidRelease) ?: return null
        val display = field(buildDisplay)
        return field(if (display == null) "Android $release" else "Android $release · $display")
    }

    /**
     * The additive `panel_assistant_device` status object. Always a JSON object, possibly empty;
     * a field the panel cannot state safely is left out instead of being sent blank.
     */
    fun json(
        friendlyName: String?,
        manufacturer: String?,
        model: String?,
        androidRelease: String?,
        buildDisplay: String?,
        area: String?,
    ): String {
        val entries = buildList {
            field(friendlyName)?.let { add("\"name\":${JSONObject.quote(it)}") }
            field(manufacturer)?.let { add("\"manufacturer\":${JSONObject.quote(it)}") }
            hardwareModel(model)?.let { add("\"model\":${JSONObject.quote(it)}") }
            hardwareVersion(androidRelease, buildDisplay)?.let {
                add("\"hw_version\":${JSONObject.quote(it)}")
            }
            field(area)?.let { add("\"area\":${JSONObject.quote(it)}") }
        }
        return "{${entries.joinToString(",")}}"
    }
}
