package io.github.maxlyth.hapaneld.mqtt

/**
 * The MQTT client boundary — everything MqttBridge needs from the underlying client, behind an interface
 * so the connect / reconnect / onConnected lifecycle (the flow with the worst incident history) can be
 * driven by a fake in tests. The real implementation is [HiveMqTransport]; tests inject a fake.
 */
interface MqttTransport {
    /** Build + connect a fresh client, wiring the lifecycle [callbacks]. Replaces any current client; a
     *  superseded client is neutralised (its auto-reconnect stopped, its callbacks ignored). */
    fun connect(config: MqttConnectConfig, callbacks: MqttCallbacks)

    /** Detach + tear the current client down on a throwaway thread — a wedged client (the case that
     *  triggers a liveness rebuild) must never block the caller while the replacement connects. */
    fun disconnectDetached()

    /** Publish [payload] to [topic]; on a broker ACK, [MqttCallbacks.onPublishAck] fires (the QoS-1
     *  liveness signal the reconnect watchdog trusts). No-op when there is no current client. */
    fun publish(topic: String, payload: ByteArray, retain: Boolean, onComplete: ((Boolean) -> Unit)? = null)

    /** Subscribe to [topicFilter]; [onMessage] receives (topic, payload, retained) per delivery. The
     *  retained flag lets the bridge reject stale retained commands (the never-blank incident guard). */
    fun subscribe(topicFilter: String, onMessage: (topic: String, payload: ByteArray, retained: Boolean) -> Unit)
}

/** Everything needed to open one MQTT connection. */
data class MqttConnectConfig(
    val host: String,
    val port: Int,
    // Connect over TLS (from a ssl:///mqtts:// broker URL). Uses the default JVM trust store — a
    // CA-signed broker cert validates; self-signed brokers are a separate follow-up.
    val tls: Boolean,
    val clientId: String,
    val user: String?,
    val password: String?,
    val keepAliveSeconds: Int,
    val willTopic: String,
    val willPayload: String,
)

/** Lifecycle callbacks the transport invokes for the LIVE client only (superseded clients are filtered). */
interface MqttCallbacks {
    fun onConnected()
    fun onDisconnected(causeMessage: String?)
    fun onPublishAck()
}
