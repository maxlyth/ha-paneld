package io.github.maxlyth.hapaneld

import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import io.github.maxlyth.hapaneld.control.BrightnessController
import io.github.maxlyth.hapaneld.control.NavigateController
import io.github.maxlyth.hapaneld.control.ScreenController
import io.github.maxlyth.hapaneld.control.VolumeController
import io.github.maxlyth.hapaneld.hardware.LedController
import io.github.maxlyth.hapaneld.input.ButtonBus
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * MQTT bridge — the single uniform control API across the fleet. Publishes Home Assistant
 * MQTT-discovery configs so every panel exposes identical entities (the per-hardware HAL is hidden
 * behind them), subscribes to the command topics, and dispatches to the controllers. Best-effort:
 * disabled silently when no broker is configured (the HTTP /play surface works standalone).
 *
 * Entities published (per panel):
 * - `light.<panel>_screen`  — brightness + on/off (on=wake, off=sleep). HA-driven; no on-device loop.
 * - `light.<panel>_led`     — RGB (only if [led].available()).
 * - `text.<panel>_navigate` — URL navigate.
 * - `event.<panel>_button`  — hardware button events (only if [buttonsEnabled]).
 * - `media_player.<panel>_paneld` — TTS/announce (HTTP /play does the work).
 */
class MqttBridge(
    private val config: Config,
    private val brightness: BrightnessController,
    private val screen: ScreenController,
    private val led: LedController,
    private val navigate: NavigateController,
    private val volume: VolumeController,
    private val buttonsEnabled: Boolean,
) {
    private var client: Mqtt5AsyncClient? = null

    private val panel = config.panelId
    private val availabilityTopic = "ha-paneld/$panel/availability"
    private val cmdScreen = "ha-paneld/$panel/screen/set"
    private val cmdLed = "ha-paneld/$panel/led/set"
    private val cmdNavigate = "ha-paneld/$panel/navigate/set"
    private val cmdVolume = "ha-paneld/$panel/volume/set"
    private val stateScreen = "ha-paneld/$panel/screen/state"
    private val stateLed = "ha-paneld/$panel/led/state"
    private val stateNavigate = "ha-paneld/$panel/navigate/state"
    private val stateVolume = "ha-paneld/$panel/volume/state"
    private val eventButton = "ha-paneld/$panel/button/event"

    fun start() {
        val broker = config.mqttBroker.trim()
        if (broker.isEmpty()) {
            Log.i(TAG, "no broker configured — MQTT disabled")
            return
        }
        try {
            val uri = URI(if (broker.contains("://")) broker else "tcp://$broker")
            val host = uri.host ?: return
            val port = if (uri.port > 0) uri.port else 1883

            val c = MqttClient.builder()
                .useMqttVersion5()
                .identifier("ha-paneld-$panel")
                .serverHost(host)
                .serverPort(port)
                .buildAsync()

            val connect = c.connectWith().willPublish()
                .topic(availabilityTopic)
                .payload("offline".toByteArray())
                .qos(MqttQos.AT_LEAST_ONCE)
                .retain(true)
                .applyWillPublish()
            if (config.mqttUser.isNotEmpty()) {
                connect.simpleAuth()
                    .username(config.mqttUser)
                    .password(config.mqttPassword.toByteArray())
                    .applySimpleAuth()
            }
            connect.send().get(10, TimeUnit.SECONDS)
            client = c

            c.subscribeWith()
                .topicFilter("ha-paneld/$panel/+/set")
                .qos(MqttQos.AT_LEAST_ONCE)
                .callback { publish -> onCommand(publish) }
                .send()

            publishDiscovery(c)
            publish(c, availabilityTopic, "online", retain = true)
            publish(c, stateVolume, volume.getPercent().toString())
            ButtonBus.listener = { event -> publishButton(event) }
            Log.i(TAG, "connected to $host:$port — discovery published for $panel")
        } catch (e: Exception) {
            Log.w(TAG, "MQTT connect failed", e)
        }
    }

    // ---- command dispatch ----

    private fun onCommand(publish: Mqtt5Publish) {
        val topic = publish.topic.toString()
        val payload = String(publish.payloadAsBytes)
        try {
            when (topic) {
                cmdScreen -> handleScreen(payload)
                cmdLed -> handleLed(payload)
                cmdNavigate -> handleNavigate(payload)
                cmdVolume -> handleVolume(payload)
                else -> Log.d(TAG, "unhandled command topic $topic")
            }
        } catch (e: Exception) {
            Log.w(TAG, "command failed on $topic: $payload", e)
        }
    }

    private fun handleScreen(payload: String) {
        val json = JSONObject(payload)
        val on = json.optString("state", "ON").equals("ON", ignoreCase = true)
        if (!on) {
            screen.sleep()
            publish(client!!, stateScreen, """{"state":"OFF"}""")
            return
        }
        screen.wake()
        val level = if (json.has("brightness")) json.getInt("brightness") else brightness.getBrightness().coerceAtLeast(1)
        brightness.setBrightness(level)
        publish(client!!, stateScreen, """{"state":"ON","brightness":$level}""")
    }

    private fun handleLed(payload: String) {
        val json = JSONObject(payload)
        val on = json.optString("state", "ON").equals("ON", ignoreCase = true)
        if (!on) {
            led.off()
            publish(client!!, stateLed, """{"state":"OFF"}""")
            return
        }
        val br = if (json.has("brightness")) json.getInt("brightness") else 255
        val color = json.optJSONObject("color")
        var r = color?.optInt("r", 255) ?: 255
        var g = color?.optInt("g", 255) ?: 255
        var b = color?.optInt("b", 255) ?: 255
        // Apply HA brightness as a scalar over the colour (json light sends them separately).
        r = r * br / 255; g = g * br / 255; b = b * br / 255
        led.setRgb(r, g, b)
        publish(
            client!!,
            stateLed,
            """{"state":"ON","color_mode":"rgb","brightness":$br,"color":{"r":${color?.optInt("r", 255) ?: 255},"g":${color?.optInt("g", 255) ?: 255},"b":${color?.optInt("b", 255) ?: 255}}}""",
        )
    }

    private fun handleNavigate(payload: String) {
        // text entity sends the raw URL string.
        val url = payload.trim().trim('"')
        if (url.isNotEmpty()) {
            navigate.navigate(url)
            publish(client!!, stateNavigate, url)
        }
    }

    private fun handleVolume(payload: String) {
        // number entity sends a plain numeric string (0..100).
        val pct = payload.trim().trim('"').toDoubleOrNull()?.toInt() ?: return
        volume.setPercent(pct)
        publish(client!!, stateVolume, volume.getPercent().toString())
    }

    private fun publishButton(event: String) {
        client?.let { publish(it, eventButton, """{"event_type":"$event"}""") }
    }

    // ---- discovery ----

    private fun publishDiscovery(c: Mqtt5AsyncClient) {
        val device = """"device":{"identifiers":["ha-paneld-$panel"],"name":"$panel","manufacturer":"ha-paneld","model":"panel agent","sw_version":"${Config.VERSION}"}"""
        val avail = """"availability_topic":"$availabilityTopic","payload_available":"online","payload_not_available":"offline""""

        publishConfig(
            c, "light", "${panel}_screen",
            """{"name":"$panel screen","unique_id":"${panel}_screen","schema":"json","brightness":true,"supported_color_modes":["brightness"],"command_topic":"$cmdScreen","state_topic":"$stateScreen",$avail,$device}""",
        )

        if (led.available()) {
            publishConfig(
                c, "light", "${panel}_led",
                """{"name":"$panel LED","unique_id":"${panel}_led","schema":"json","brightness":true,"supported_color_modes":["rgb"],"command_topic":"$cmdLed","state_topic":"$stateLed",$avail,$device}""",
            )
        }

        publishConfig(
            c, "text", "${panel}_navigate",
            """{"name":"$panel navigate","unique_id":"${panel}_navigate","command_topic":"$cmdNavigate","state_topic":"$stateNavigate","mode":"text",$avail,$device}""",
        )

        if (buttonsEnabled) {
            publishConfig(
                c, "event", "${panel}_button",
                """{"name":"$panel button","unique_id":"${panel}_button","state_topic":"$eventButton","event_types":["KEYCODE_BACK","KEYCODE_HOME","KEYCODE_DPAD_CENTER","KEYCODE_VOLUME_UP","KEYCODE_VOLUME_DOWN"],$avail,$device}""",
            )
        }

        // TTS/announce playback volume (STREAM_MUSIC). HA has no MQTT media_player platform, so
        // volume is a number entity rather than a media_player slider. Playback itself is the
        // HTTP /play contract; this controls how loud it is.
        publishConfig(
            c, "number", "${panel}_volume",
            """{"name":"$panel volume","unique_id":"${panel}_volume","command_topic":"$cmdVolume","state_topic":"$stateVolume","min":0,"max":100,"step":1,"mode":"slider","unit_of_measurement":"%","icon":"mdi:volume-high",$avail,$device}""",
        )
    }

    private fun publishConfig(c: Mqtt5AsyncClient, component: String, objectId: String, payload: String) {
        publish(c, "homeassistant/$component/$objectId/config", payload, retain = true)
    }

    private fun publish(c: Mqtt5AsyncClient, topic: String, payload: String, retain: Boolean = false) {
        c.publishWith()
            .topic(topic)
            .payload(payload.toByteArray())
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(retain)
            .send()
    }

    fun stop() {
        ButtonBus.listener = null
        runCatching {
            client?.let {
                publish(it, availabilityTopic, "offline", retain = true)
                it.disconnect()
            }
        }
        client = null
    }

    companion object {
        private const val TAG = "ha-paneld/mqtt"
    }
}
