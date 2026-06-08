package io.github.maxlyth.hapaneld.esphome

import com.google.protobuf.MessageLite
import io.github.maxlyth.hapaneld.esphome.proto.BinarySensorStateResponse
import io.github.maxlyth.hapaneld.esphome.proto.ColorMode
import io.github.maxlyth.hapaneld.esphome.proto.EntityCategory
import io.github.maxlyth.hapaneld.esphome.proto.EventResponse
import io.github.maxlyth.hapaneld.esphome.proto.LightCommandRequest
import io.github.maxlyth.hapaneld.esphome.proto.LightStateResponse
import io.github.maxlyth.hapaneld.esphome.proto.ListEntitiesBinarySensorResponse
import io.github.maxlyth.hapaneld.esphome.proto.ListEntitiesButtonResponse
import io.github.maxlyth.hapaneld.esphome.proto.ListEntitiesEventResponse
import io.github.maxlyth.hapaneld.esphome.proto.ListEntitiesLightResponse
import io.github.maxlyth.hapaneld.esphome.proto.ListEntitiesNumberResponse
import io.github.maxlyth.hapaneld.esphome.proto.ListEntitiesSelectResponse
import io.github.maxlyth.hapaneld.esphome.proto.ListEntitiesSensorResponse
import io.github.maxlyth.hapaneld.esphome.proto.ListEntitiesSwitchResponse
import io.github.maxlyth.hapaneld.esphome.proto.ListEntitiesTextResponse
import io.github.maxlyth.hapaneld.esphome.proto.NumberCommandRequest
import io.github.maxlyth.hapaneld.esphome.proto.NumberMode
import io.github.maxlyth.hapaneld.esphome.proto.NumberStateResponse
import io.github.maxlyth.hapaneld.esphome.proto.SelectCommandRequest
import io.github.maxlyth.hapaneld.esphome.proto.SelectStateResponse
import io.github.maxlyth.hapaneld.esphome.proto.SensorStateClass
import io.github.maxlyth.hapaneld.esphome.proto.SensorStateResponse
import io.github.maxlyth.hapaneld.esphome.proto.SwitchCommandRequest
import io.github.maxlyth.hapaneld.esphome.proto.SwitchStateResponse
import io.github.maxlyth.hapaneld.esphome.proto.TextCommandRequest
import io.github.maxlyth.hapaneld.esphome.proto.TextStateResponse

/**
 * Transport-agnostic ESPHome entity descriptor (spike/esphome-api). One per HA entity; built by
 * PaneldService mirroring the MQTT discovery gating, then served generically by EspHomeServer.
 *
 * - [listId]/[listMsg]: the ListEntities*Response (static descriptive config; built once at registry time).
 * - [stateId]/[state]: the *StateResponse supplier (null for command-only entities: button).
 * - [cmdId]/[apply]: the *CommandRequest id + applier (null for read-only: sensor/binary_sensor/event).
 *   The server parses the command by id, routes to the entity whose [key] matches, calls [apply], then
 *   re-sends [state]. ESPHome message ids are the `option (id)` values from api.proto.
 */
class EspEntity(
    val key: Int,
    val listId: Int,
    val listMsg: MessageLite,
    val stateId: Int = 0,
    val state: (() -> MessageLite)? = null,
    val cmdId: Int = 0,
    val apply: ((MessageLite) -> Unit)? = null,
)

/** Builders — keep all proto knowledge here so PaneldService stays declarative. */
object Esp {
    // ---- message ids (api.proto option (id)) ----
    const val LIGHT_LIST = 15; const val LIGHT_STATE = 24; const val LIGHT_CMD = 32
    const val SWITCH_LIST = 17; const val SWITCH_STATE = 26; const val SWITCH_CMD = 33
    const val NUMBER_LIST = 49; const val NUMBER_STATE = 50; const val NUMBER_CMD = 51
    const val SELECT_LIST = 52; const val SELECT_STATE = 53; const val SELECT_CMD = 54
    const val SENSOR_LIST = 16; const val SENSOR_STATE = 25
    const val BINSENSOR_LIST = 12; const val BINSENSOR_STATE = 21
    const val BUTTON_LIST = 61; const val BUTTON_CMD = 62
    const val TEXT_LIST = 97; const val TEXT_STATE = 98; const val TEXT_CMD = 99
    const val EVENT_LIST = 107; const val EVENT_STATE = 108

    private val CONFIG = EntityCategory.ENTITY_CATEGORY_CONFIG
    private val NONE = EntityCategory.ENTITY_CATEGORY_NONE

    /** Brightness-only light (screen, button-backlight). on = brightness>0. */
    fun brightnessLight(key: Int, objectId: String, name: String, icon: String = "",
                        getOn: () -> Boolean, getBri: () -> Int, set: (on: Boolean, bri: Int?) -> Unit) = EspEntity(
        key, LIGHT_LIST,
        ListEntitiesLightResponse.newBuilder().setObjectId(objectId).setKey(key).setName(name)
            .addSupportedColorModes(ColorMode.COLOR_MODE_BRIGHTNESS).setEntityCategory(NONE)
            .also { if (icon.isNotEmpty()) it.icon = icon }.build(),
        LIGHT_STATE, { LightStateResponse.newBuilder().setKey(key).setState(getOn())
            .setBrightness(getBri().coerceIn(0, 255) / 255f).setColorMode(ColorMode.COLOR_MODE_BRIGHTNESS).build() },
        LIGHT_CMD, { m -> (m as LightCommandRequest).let {
            if (it.hasState && !it.state) set(false, null)
            else set(true, if (it.hasBrightness) (it.brightness * 255f).toInt().coerceIn(0, 255) else null) } },
    )

    /** RGB light (sysfs/jni LED). */
    fun rgbLight(key: Int, objectId: String, name: String,
                 getState: () -> Triple<Boolean, Int, IntArray>,    // on, brightness(0-255), rgb
                 set: (on: Boolean, bri: Int, r: Int, g: Int, b: Int) -> Unit) = EspEntity(
        key, LIGHT_LIST,
        ListEntitiesLightResponse.newBuilder().setObjectId(objectId).setKey(key).setName(name)
            .addSupportedColorModes(ColorMode.COLOR_MODE_RGB).setEntityCategory(NONE).build(),
        LIGHT_STATE, {
            val (on, bri, rgb) = getState()
            LightStateResponse.newBuilder().setKey(key).setState(on).setBrightness(bri.coerceIn(0, 255) / 255f)
                .setColorMode(ColorMode.COLOR_MODE_RGB).setColorBrightness(1f)
                .setRed(rgb[0] / 255f).setGreen(rgb[1] / 255f).setBlue(rgb[2] / 255f).build()
        },
        LIGHT_CMD, { m -> (m as LightCommandRequest).let {
            if (it.hasState && !it.state) { set(false, 0, 0, 0, 0) } else {
                val bri = if (it.hasBrightness) (it.brightness * 255f).toInt().coerceIn(0, 255) else 255
                val r = if (it.hasRgb) (it.red * 255f).toInt() else 255
                val g = if (it.hasRgb) (it.green * 255f).toInt() else 255
                val b = if (it.hasRgb) (it.blue * 255f).toInt() else 255
                set(true, bri, r, g, b)
            } } },
    )

    /** On/off light (no brightness) — e.g. S9E button LEDs. */
    fun onOffLight(key: Int, objectId: String, name: String, icon: String = "",
                   get: () -> Boolean, set: (Boolean) -> Unit) = EspEntity(
        key, LIGHT_LIST,
        ListEntitiesLightResponse.newBuilder().setObjectId(objectId).setKey(key).setName(name)
            .addSupportedColorModes(ColorMode.COLOR_MODE_ON_OFF).setEntityCategory(NONE)
            .also { if (icon.isNotEmpty()) it.icon = icon }.build(),
        LIGHT_STATE, { LightStateResponse.newBuilder().setKey(key).setState(get()).setColorMode(ColorMode.COLOR_MODE_ON_OFF).build() },
        LIGHT_CMD, { m -> set((m as LightCommandRequest).run { if (hasState) state else true }) },
    )

    fun switchE(key: Int, objectId: String, name: String, config: Boolean = true, icon: String = "",
                get: () -> Boolean, set: (Boolean) -> Unit) = EspEntity(
        key, SWITCH_LIST,
        ListEntitiesSwitchResponse.newBuilder().setObjectId(objectId).setKey(key).setName(name)
            .setEntityCategory(if (config) CONFIG else NONE).also { if (icon.isNotEmpty()) it.icon = icon }.build(),
        SWITCH_STATE, { SwitchStateResponse.newBuilder().setKey(key).setState(get()).build() },
        SWITCH_CMD, { m -> set((m as SwitchCommandRequest).state) },
    )

    fun number(key: Int, objectId: String, name: String, min: Float, max: Float, step: Float,
               unit: String = "", slider: Boolean = true, config: Boolean = false, icon: String = "",
               get: () -> Float, set: (Float) -> Unit) = EspEntity(
        key, NUMBER_LIST,
        ListEntitiesNumberResponse.newBuilder().setObjectId(objectId).setKey(key).setName(name)
            .setMinValue(min).setMaxValue(max).setStep(step).setUnitOfMeasurement(unit)
            .setMode(if (slider) NumberMode.NUMBER_MODE_SLIDER else NumberMode.NUMBER_MODE_BOX)
            .setEntityCategory(if (config) CONFIG else NONE).also { if (icon.isNotEmpty()) it.icon = icon }.build(),
        NUMBER_STATE, { NumberStateResponse.newBuilder().setKey(key).setState(get()).build() },
        NUMBER_CMD, { m -> set((m as NumberCommandRequest).state) },
    )

    fun select(key: Int, objectId: String, name: String, options: List<String>, config: Boolean = true,
               icon: String = "", get: () -> String, set: (String) -> Unit) = EspEntity(
        key, SELECT_LIST,
        ListEntitiesSelectResponse.newBuilder().setObjectId(objectId).setKey(key).setName(name)
            .addAllOptions(options).setEntityCategory(if (config) CONFIG else NONE)
            .also { if (icon.isNotEmpty()) it.icon = icon }.build(),
        SELECT_STATE, { SelectStateResponse.newBuilder().setKey(key).setState(get()).build() },
        SELECT_CMD, { m -> set((m as SelectCommandRequest).state) },
    )

    fun text(key: Int, objectId: String, name: String, icon: String = "",
             get: () -> String, set: (String) -> Unit) = EspEntity(
        key, TEXT_LIST,
        ListEntitiesTextResponse.newBuilder().setObjectId(objectId).setKey(key).setName(name)
            .setMinLength(0).setMaxLength(255).setEntityCategory(NONE)
            .also { if (icon.isNotEmpty()) it.icon = icon }.build(),
        TEXT_STATE, { TextStateResponse.newBuilder().setKey(key).setState(get()).build() },
        TEXT_CMD, { m -> set((m as TextCommandRequest).state) },
    )

    fun sensor(key: Int, objectId: String, name: String, unit: String, deviceClass: String,
               decimals: Int = 0, get: () -> Float) = EspEntity(
        key, SENSOR_LIST,
        ListEntitiesSensorResponse.newBuilder().setObjectId(objectId).setKey(key).setName(name)
            .setUnitOfMeasurement(unit).setAccuracyDecimals(decimals).setDeviceClass(deviceClass)
            .setStateClass(SensorStateClass.STATE_CLASS_MEASUREMENT).build(),
        SENSOR_STATE, { SensorStateResponse.newBuilder().setKey(key).setState(get()).build() },
    )

    fun binarySensor(key: Int, objectId: String, name: String, deviceClass: String, get: () -> Boolean) = EspEntity(
        key, BINSENSOR_LIST,
        ListEntitiesBinarySensorResponse.newBuilder().setObjectId(objectId).setKey(key).setName(name)
            .setDeviceClass(deviceClass).build(),
        BINSENSOR_STATE, { BinarySensorStateResponse.newBuilder().setKey(key).setState(get()).build() },
    )

    fun button(key: Int, objectId: String, name: String, deviceClass: String = "", icon: String = "",
               onPress: () -> Unit) = EspEntity(
        key, BUTTON_LIST,
        ListEntitiesButtonResponse.newBuilder().setObjectId(objectId).setKey(key).setName(name)
            .also { if (deviceClass.isNotEmpty()) it.deviceClass = deviceClass; if (icon.isNotEmpty()) it.icon = icon }.build(),
        cmdId = BUTTON_CMD, apply = { onPress() },
    )

    /** Event entity (button presses). State is pushed via EspHomeServer.publishEvent, not polled. */
    fun event(key: Int, objectId: String, name: String, eventTypes: List<String>) = EspEntity(
        key, EVENT_LIST,
        ListEntitiesEventResponse.newBuilder().setObjectId(objectId).setKey(key).setName(name)
            .setDeviceClass("button").addAllEventTypes(eventTypes).build(),
    )

    /** Build an EventResponse for a button event (sent ad-hoc by the server). */
    fun eventMsg(key: Int, type: String): MessageLite =
        EventResponse.newBuilder().setKey(key).setEventType(type).build()
}
