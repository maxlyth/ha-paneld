package io.github.maxlyth.hapaneld

import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5BlockingClient
import java.net.URI

/**
 * Best-effort MQTT 5 publisher (HiveMQ client). Publishes Home Assistant MQTT-discovery configs
 * so panel entities register without YAML, plus an availability birth/LWT. Disabled silently when
 * no broker is configured (the /play HTTP contract works standalone).
 *
 * v0.1.0 publishes the `media_player.<panel>_paneld` discovery config + an `online`/`offline`
 * availability topic. Command-over-MQTT (play via MQTT instead of HTTP) lands in a later phase;
 * the HTTP /play surface is the v0.1.0 play path.
 */
class MqttPublisher(private val config: Config) {
    private var client: Mqtt5BlockingClient? = null

    private val availabilityTopic = "ha-paneld/${config.panelId}/availability"
    private val discoveryTopic = "homeassistant/media_player/${config.panelId}/config"

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
                .identifier("ha-paneld-${config.panelId}")
                .serverHost(host)
                .serverPort(port)
                .buildBlocking()

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
            connect.send()
            client = c

            publishDiscovery(c)
            publishAvailability(c, online = true)
            Log.i(TAG, "connected to $host:$port, published discovery for ${config.panelId}")
        } catch (e: Exception) {
            Log.w(TAG, "MQTT connect failed", e)
        }
    }

    private fun publishDiscovery(c: Mqtt5BlockingClient) {
        val id = config.panelId
        val payload = """
            {
              "name": "$id paneld",
              "unique_id": "${id}_paneld_media",
              "availability_topic": "$availabilityTopic",
              "payload_available": "online",
              "payload_not_available": "offline",
              "device": {
                "identifiers": ["ha-paneld-$id"],
                "name": "$id",
                "manufacturer": "ha-paneld",
                "model": "panel agent",
                "sw_version": "${Config.VERSION}"
              }
            }
        """.trimIndent()
        c.publishWith()
            .topic(discoveryTopic)
            .payload(payload.toByteArray())
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(true)
            .send()
    }

    private fun publishAvailability(c: Mqtt5BlockingClient, online: Boolean) {
        c.publishWith()
            .topic(availabilityTopic)
            .payload((if (online) "online" else "offline").toByteArray())
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(true)
            .send()
    }

    fun stop() {
        runCatching {
            client?.let {
                publishAvailability(it, online = false)
                it.disconnect()
            }
        }
        client = null
    }

    companion object {
        private const val TAG = "ha-paneld/mqtt"
    }
}
