package io.github.maxlyth.hapaneld.mqtt

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import io.github.maxlyth.hapaneld.metrics.FeatureCostOperation
import io.github.maxlyth.hapaneld.metrics.FeatureCostOutcome
import io.github.maxlyth.hapaneld.metrics.FeatureCosts
import io.github.maxlyth.hapaneld.util.SingleFlightExecutor

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
            // Advertise an application-sized inbound ceiling in CONNECT. HiveMQ enforces it while
            // decoding, before a malicious/broken broker can materialize an MQTT-sized payload.
            .restrictions()
            .maximumPacketSize(MAX_INBOUND_PACKET_BYTES)
            .receiveMaximum(MAX_INBOUND_IN_FLIGHT)
            .applyRestrictions()
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
        old?.let { disconnectBounded(it) }
    }

    override fun publishThenDisconnect(publications: List<MqttFinalPublish>, timeoutMs: Long) {
        val c = client ?: return
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val detach = {
            if (finished.compareAndSet(false, true)) {
                if (client === c) client = null
                disconnectBounded(c)
            }
        }
        // HiveMQ's send admission can itself wedge. One process-wide zero-queue slot and one shared
        // scheduler bound both worker and timeout resources across repeated bridge rebuilds.
        when (FINAL_PUBLISH.execute(timeoutMs, onFinish = detach) { finish ->
            try {
                val pending = publications.map { publication ->
                    c.publishWith()
                        .topic(publication.topic)
                        .payload(publication.payload)
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .retain(publication.retain)
                        .send()
                }
                java.util.concurrent.CompletableFuture.allOf(*pending.toTypedArray())
                    .whenComplete { _, _ -> finish() }
            } catch (_: Exception) {
                finish()
            }
        }) {
            FinalPublishAdmission.ADMITTED -> Unit
            FinalPublishAdmission.BUSY -> {
                FeatureCosts.registry.recordDropped(FeatureCostOperation.MQTT_TEARDOWN)
                detach()
            }
            FinalPublishAdmission.REJECTED ->
                FeatureCosts.registry.recordDropped(FeatureCostOperation.MQTT_TEARDOWN)
        }
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
                val payloadSize = p.payload.map { it.remaining() }.orElse(0)
                if (client === c && payloadSize <= MAX_INBOUND_PACKET_BYTES) {
                    onMessage(p.topic.toString(), p.payloadAsBytes, p.isRetain)
                }
            }
            .send()
    }

    internal companion object {
        const val MAX_INBOUND_PACKET_BYTES = 64 * 1024
        const val MAX_INBOUND_IN_FLIGHT = 16
        private val TEARDOWN = MqttTeardownGate()
        private val FINAL_PUBLISH = MqttFinalPublishGate()

        private fun disconnectBounded(client: Mqtt5AsyncClient) {
            val accepted = TEARDOWN.execute {
                val cost = FeatureCosts.registry.span(FeatureCostOperation.MQTT_TEARDOWN)
                runCatching { client.disconnect() }
                    .onFailure { cost.outcome(FeatureCostOutcome.FAILURE) }
                cost.close()
            }
            if (!accepted) FeatureCosts.registry.recordDropped(FeatureCostOperation.MQTT_TEARDOWN)
        }
    }
}

internal enum class FinalPublishAdmission { ADMITTED, BUSY, REJECTED }

/** One process-wide final-publication attempt, retained until ACK completion or its timeout. */
internal class MqttFinalPublishGate(
    private val executor: SingleFlightExecutor = SingleFlightExecutor("mqtt-final-publish"),
) : AutoCloseable {
    private val active = java.util.concurrent.atomic.AtomicBoolean(false)
    private val scheduler = java.util.concurrent.ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "mqtt-final-timeout").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    fun execute(
        timeoutMs: Long,
        onFinish: () -> Unit,
        action: (finish: () -> Unit) -> Unit,
    ): FinalPublishAdmission {
        require(timeoutMs > 0L)
        if (!active.compareAndSet(false, true)) return FinalPublishAdmission.BUSY
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val timeout = java.util.concurrent.atomic.AtomicReference<java.util.concurrent.ScheduledFuture<*>>()
        val finish = {
            if (completed.compareAndSet(false, true)) {
                timeout.get()?.cancel(false)
                runCatching(onFinish)
                active.set(false)
            }
        }
        val scheduled = scheduler.schedule(finish, timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        timeout.set(scheduled)
        if (completed.get()) scheduled.cancel(false)
        if (!executor.execute {
                try { action(finish) } catch (_: Exception) { finish() }
            }
        ) {
            finish()
            return FinalPublishAdmission.REJECTED
        }
        return FinalPublishAdmission.ADMITTED
    }

    override fun close() {
        scheduler.shutdownNow()
        executor.close(500L)
    }
}

/** Process-wide zero-queue gate: one wedged client disconnect can consume at most one daemon thread. */
internal class MqttTeardownGate(
    private val executor: SingleFlightExecutor = SingleFlightExecutor("mqtt-teardown"),
) : AutoCloseable {
    fun execute(action: () -> Unit): Boolean = executor.execute(action)
    override fun close() = executor.close(500L)
}
