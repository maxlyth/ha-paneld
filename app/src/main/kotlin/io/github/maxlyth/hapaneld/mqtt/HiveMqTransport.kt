package io.github.maxlyth.hapaneld.mqtt

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient

/**
 * The real [MqttTransport] over the HiveMQ MQTT5 async client. Owns the client instance, the
 * connected/disconnected listener wiring, and the **generation guard**: listeners act for the CURRENT
 * client only, and a superseded client is told to stop auto-reconnecting. Without this guard a zombie
 * client's disconnected-listener overwrote the live connection's state and spawned reconnect storms
 * (the long-unexplained NOT_AUTHORIZED-while-connected incident).
 */
class HiveMqTransport : MqttTransport {
    @Volatile private var client: Mqtt5AsyncClient? = null
    @Volatile private var callbacks: MqttCallbacks? = null

    override fun connect(config: MqttConnectConfig, callbacks: MqttCallbacks) {
        this.callbacks = callbacks
        var self: Mqtt5AsyncClient? = null
        var builder = MqttClient.builder()
            .useMqttVersion5()
            .identifier(config.clientId)
            .serverHost(config.host)
            .serverPort(config.port)
            // Auto-reconnect so a network blip / broker restart never permanently orphans the panel;
            // re-subscribe + re-publish discovery happen in onConnected on every connect.
            .automaticReconnectWithDefaultConfig()
            .addConnectedListener { if (client === self) callbacks.onConnected() }
            .addDisconnectedListener { ctx ->
                if (client !== self) {
                    runCatching { ctx.reconnector.reconnect(false) } // zombie: kill its auto-reconnect
                    return@addDisconnectedListener
                }
                if (!callbacks.onDisconnected(ctx.cause?.message ?: ctx.cause?.toString())) {
                    runCatching { ctx.reconnector.reconnect(false) }
                }
            }
        // ssl:///mqtts:// broker → TLS with the default JVM trust store (CA-signed cert validates).
        if (config.tls) builder = builder.sslWithDefaultConfig()
        val c = builder.buildAsync()
        self = c
        client = c
        val connect = c.connectWith()
            .keepAlive(config.keepAliveSeconds)
            .willPublish()
            .topic(config.willTopic)
            .payload(config.willPayload.toByteArray())
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(true)
            .applyWillPublish()
        if (!config.user.isNullOrEmpty()) {
            connect.simpleAuth()
                .username(config.user)
                .password((config.password ?: "").toByteArray())
                .applySimpleAuth()
        }
        connect.send() // async; onConnected does subscribe + discovery on success
    }

    override fun disconnectDetached() {
        val old = client
        client = null
        old?.let { Thread({ runCatching { it.disconnect() } }, "mqtt-teardown").apply { isDaemon = true }.start() }
    }

    override fun publishThenDisconnect(publications: List<MqttFinalPublish>, timeoutMs: Long) {
        val c = client ?: return
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val detach = {
            if (finished.compareAndSet(false, true)) {
                if (client === c) client = null
                Thread({ runCatching { c.disconnect() } }, "mqtt-teardown").apply { isDaemon = true }.start()
            }
        }
        // HiveMQ's async API documents publish->disconnect as a composed future. Keep both the potentially
        // blocking send admission and the timeout away from the lifecycle lane: ACK wins on a healthy link;
        // timeout wins on a wedged one; the atomic boundary makes either ordering idempotent.
        Thread({
            runCatching {
                val pending = publications.map { publication ->
                    c.publishWith()
                        .topic(publication.topic)
                        .payload(publication.payload)
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .retain(publication.retain)
                        .send()
                }
                java.util.concurrent.CompletableFuture.allOf(*pending.toTypedArray())
                    .whenComplete { _, _ -> detach() }
            }.onFailure { detach() }
        }, "mqtt-final-publish").apply { isDaemon = true }.start()
        Thread({
            try { Thread.sleep(timeoutMs) } catch (_: InterruptedException) { }
            detach()
        }, "mqtt-final-timeout").apply { isDaemon = true }.start()
    }

    override fun publish(topic: String, payload: ByteArray, retain: Boolean, onComplete: ((Boolean) -> Unit)?) {
        val c = client ?: run { onComplete?.invoke(false); return }
        c.publishWith()
            .topic(topic)
            .payload(payload)
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(retain)
            .send()
            // A QoS-1 publish the broker ACKs proves the link is truly alive — the liveness signal the
            // watchdog trusts over HiveMQ's self-reported connected state (which lies on a half-open socket).
            .whenComplete { _, ex ->
                // A superseded client's future can complete after a watchdog rebuild. It must not refresh
                // the replacement connection's liveness or complete state work owned by that connection.
                if (client !== c) return@whenComplete
                val success = ex == null
                if (success) callbacks?.onPublishAck()
                onComplete?.invoke(success)
            }
    }

    override fun subscribe(topicFilter: String, onMessage: (String, ByteArray, Boolean) -> Unit) {
        val c = client ?: return
        c.subscribeWith()
            .topicFilter(topicFilter)
            .qos(MqttQos.AT_LEAST_ONCE)
            .callback { p ->
                // A watchdog rebuild can leave the old reactor alive briefly. Commands and HA-birth
                // messages from that superseded client must not cross into the replacement generation.
                if (client === c) onMessage(p.topic.toString(), p.payloadAsBytes, p.isRetain)
            }
            .send()
    }
}
