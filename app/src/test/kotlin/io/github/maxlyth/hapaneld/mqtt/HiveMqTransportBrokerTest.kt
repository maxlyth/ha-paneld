package io.github.maxlyth.hapaneld.mqtt

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import io.moquette.broker.Server
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class HiveMqTransportBrokerTest {
    @Test
    fun qosAckRetainedStateAndCommandFlagsComposeAgainstBroker() {
        EmbeddedBroker().use { broker ->
            val transport = HiveMqTransport()
            val callbacks = RecordingCallbacks()
            val observer = broker.client("observer")
            val prefix = "ha-paneld-test/${UUID.randomUUID()}"
            val stateTopic = "$prefix/state"
            val liveCommandTopic = "$prefix/command/live"
            val retainedCommandTopic = "$prefix/command/retained"
            try {
                observer.connect().await()
                val observedState = MessageLatch()
                observer.subscribe(stateTopic, observedState)

                transport.connect(broker.config("transport"), callbacks)
                callbacks.connected.awaitOrFail("transport did not connect")

                val completion = CountDownLatch(1)
                transport.publish(stateTopic, "online".toByteArray(), retain = true) { success ->
                    assertTrue(success)
                    completion.countDown()
                }
                completion.awaitOrFail("publish completion did not run")
                callbacks.publishAck.awaitOrFail("QoS-1 acknowledgement did not reach the transport callback")
                val liveState = observedState.awaitMessage("observer did not receive state publication")
                assertContentEquals("online".toByteArray(), liveState.payload)
                assertFalse(liveState.retained)
                assertEquals(MqttQos.AT_LEAST_ONCE, liveState.qos)

                val liveCommand = MessageLatch()
                transport.subscribe(liveCommandTopic) { topic, payload, retained -> liveCommand.offer(topic, payload, retained) }
                observer.publishUntilDelivered(liveCommandTopic, "wake".toByteArray(), retain = false, liveCommand)
                val deliveredLiveCommand = liveCommand.awaitMessage("transport did not receive live command")
                assertEquals(liveCommandTopic, deliveredLiveCommand.topic)
                assertContentEquals("wake".toByteArray(), deliveredLiveCommand.payload)
                assertFalse(deliveredLiveCommand.retained)

                observer.publish(retainedCommandTopic, "sleep".toByteArray(), retain = true)
                val retainedCommand = MessageLatch()
                transport.subscribe(retainedCommandTopic) { topic, payload, retained -> retainedCommand.offer(topic, payload, retained) }
                val deliveredRetainedCommand = retainedCommand.awaitMessage("transport did not receive retained command")
                assertEquals(retainedCommandTopic, deliveredRetainedCommand.topic)
                assertContentEquals("sleep".toByteArray(), deliveredRetainedCommand.payload)
                assertTrue(deliveredRetainedCommand.retained)

                broker.client("retained-reader").use { reader ->
                    reader.connect().await()
                    val retainedState = MessageLatch()
                    reader.subscribe(stateTopic, retainedState)
                    val deliveredRetainedState = retainedState.awaitMessage("fresh client did not receive retained state")
                    assertContentEquals("online".toByteArray(), deliveredRetainedState.payload)
                    assertTrue(deliveredRetainedState.retained)
                }
            } finally {
                transport.disconnectDetached()
                observer.close()
            }
        }
    }

    @Test
    fun finalPublicationsAreAcknowledgedBeforeTransportDisconnects() {
        EmbeddedBroker().use { broker ->
            val transportClientId = "transport-${UUID.randomUUID()}"
            val transport = HiveMqTransport()
            val callbacks = RecordingCallbacks()
            val observer = broker.client("observer")
            val prefix = "ha-paneld-test/${UUID.randomUUID()}"
            val availabilityTopic = "$prefix/availability"
            val tombstoneTopic = "$prefix/discovery"
            try {
                observer.connect().await()
                observer.publish(tombstoneTopic, "old-config".toByteArray(), retain = true)
                val availability = MessageLatch()
                observer.subscribe(availabilityTopic, availability)

                transport.connect(broker.config(transportClientId), callbacks)
                callbacks.connected.awaitOrFail("transport did not connect")

                val startedAt = System.nanoTime()
                transport.publishThenDisconnect(
                    listOf(
                        MqttFinalPublish(availabilityTopic, "offline".toByteArray(), retain = true),
                        MqttFinalPublish(tombstoneTopic, byteArrayOf(), retain = true),
                    ),
                    timeoutMs = 5_000,
                )
                assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 500, "final publication must not block the lifecycle caller")

                assertContentEquals("offline".toByteArray(), availability.awaitMessage("broker did not receive final availability").payload)
                awaitCondition("transport remained connected after final publications") {
                    broker.server.listConnectedClients().none { it.clientID == transportClientId }
                }

                broker.client("retained-reader").use { reader ->
                    reader.connect().await()
                    val retainedAvailability = MessageLatch()
                    reader.subscribe(availabilityTopic, retainedAvailability)
                    val deliveredAvailability = retainedAvailability.awaitMessage("final availability was not retained")
                    assertContentEquals("offline".toByteArray(), deliveredAvailability.payload)
                    assertTrue(deliveredAvailability.retained)

                    val clearedDiscovery = MessageLatch()
                    reader.subscribe(tombstoneTopic, clearedDiscovery)
                    assertFalse(clearedDiscovery.await(350, TimeUnit.MILLISECONDS), "empty final publication did not clear retained discovery")
                }
            } finally {
                transport.disconnectDetached()
                observer.close()
            }
        }
    }

    @Test(timeout = 15_000)
    fun stalledAcknowledgementYieldsToBoundedFinalPublishTimeout() {
        EmbeddedBroker().use { broker ->
            AckDroppingProxy(broker.port).use { proxy ->
                val transportClientId = "transport-${UUID.randomUUID()}"
                val transport = HiveMqTransport()
                val callbacks = RecordingCallbacks()
                val observer = broker.client("observer")
                val availabilityTopic = "ha-paneld-test/${UUID.randomUUID()}/availability"
                try {
                    observer.connect().await()
                    val availability = MessageLatch()
                    observer.subscribe(availabilityTopic, availability)
                    transport.connect(broker.config(transportClientId).copy(port = proxy.port), callbacks)
                    callbacks.connected.awaitOrFail("transport did not connect through acknowledgement proxy")

                    val timeoutMs = 3_000L
                    val startedAt = System.nanoTime()
                    transport.publishThenDisconnect(
                        listOf(MqttFinalPublish(availabilityTopic, "offline".toByteArray(), retain = true)),
                        timeoutMs = timeoutMs,
                    )
                    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 500, "final publication blocked the lifecycle caller")

                    assertContentEquals("offline".toByteArray(), availability.awaitMessage("broker did not receive the unacknowledged final publication").payload)
                    proxy.pubAckDropped.awaitOrFail("proxy did not observe and suppress the broker PUBACK")
                    assertTrue(
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < timeoutMs,
                        "transport detached before the configured timeout",
                    )
                    assertTrue(
                        broker.server.listConnectedClients().any { it.clientID == transportClientId },
                        "transport disconnected before timeout while its PUBACK was suppressed",
                    )

                    val timeoutDeadline = startedAt + TimeUnit.MILLISECONDS.toNanos(timeoutMs + 300)
                    while (System.nanoTime() < timeoutDeadline) Thread.sleep(20)
                    val rejected = CountDownLatch(1)
                    var accepted: Boolean? = null
                    transport.publish("$availabilityTopic/probe", byteArrayOf(1), retain = false) { success ->
                        accepted = success
                        rejected.countDown()
                    }
                    assertTrue(rejected.await(250, TimeUnit.MILLISECONDS), "transport remained attached after final-publication timeout")
                    assertEquals(false, accepted)
                    assertEquals(0, callbacks.disconnected.get(), "timed-out client leaked a disconnect callback after detachment")

                    broker.client("retained-reader").use { reader ->
                        reader.connect().await()
                        val retained = MessageLatch()
                        reader.subscribe(availabilityTopic, retained)
                        val delivered = retained.awaitMessage("unacknowledged final publication was not retained by the broker")
                        assertContentEquals("offline".toByteArray(), delivered.payload)
                        assertTrue(delivered.retained)
                    }
                } finally {
                    transport.disconnectDetached()
                    observer.close()
                }
            }
        }
    }

    @Test
    fun supersededClientCannotReportDisconnectOrAcknowledgeForReplacement() {
        EmbeddedBroker().use { oldBroker ->
            EmbeddedBroker().use { replacementBroker ->
                val transport = HiveMqTransport()
                val oldCallbacks = RecordingCallbacks()
                val replacementCallbacks = RecordingCallbacks()
                val observer = replacementBroker.client("observer")
                val topic = "ha-paneld-test/${UUID.randomUUID()}/state"
                try {
                    transport.connect(oldBroker.config("old-transport"), oldCallbacks)
                    oldCallbacks.connected.awaitOrFail("old transport did not connect")

                    observer.connect().await()
                    val observed = MessageLatch()
                    observer.subscribe(topic, observed)
                    transport.connect(replacementBroker.config("replacement-transport"), replacementCallbacks)
                    replacementCallbacks.connected.awaitOrFail("replacement transport did not connect")

                    oldBroker.stop()
                    Thread.sleep(350)
                    assertEquals(0, oldCallbacks.disconnected.get(), "superseded client leaked a disconnect callback")

                    val completion = CountDownLatch(1)
                    transport.publish(topic, "current".toByteArray(), retain = false) { success ->
                        assertTrue(success)
                        completion.countDown()
                    }
                    completion.awaitOrFail("replacement publication did not complete")
                    replacementCallbacks.publishAck.awaitOrFail("replacement did not receive its acknowledgement")
                    assertEquals(0, oldCallbacks.publishAcks.get(), "superseded callbacks received the replacement acknowledgement")
                    assertContentEquals("current".toByteArray(), observed.awaitMessage("replacement broker did not receive publication").payload)
                } finally {
                    transport.disconnectDetached()
                    observer.close()
                }
            }
        }
    }

    private class EmbeddedBroker : AutoCloseable {
        val port = ServerSocket(0).use { it.localPort }
        val server = Server().withConfig()
            .host("127.0.0.1")
            .port(port)
            .disablePersistence()
            .disableTelemetry()
            .startServer()

        private var running = true

        fun config(clientId: String) = MqttConnectConfig(
            host = "127.0.0.1",
            port = port,
            tls = false,
            clientId = clientId,
            user = null,
            password = null,
            keepAliveSeconds = 10,
            willTopic = "ha-paneld-test/$clientId/availability",
            willPayload = "offline",
        )

        fun client(name: String): TestClient = TestClient(
            MqttClient.builder()
                .useMqttVersion5()
                .identifier("$name-${UUID.randomUUID()}")
                .serverHost("127.0.0.1")
                .serverPort(port)
                .buildAsync(),
        )

        fun stop() {
            if (running) {
                running = false
                server.stopServer()
            }
        }

        override fun close() = stop()
    }

    private class AckDroppingProxy(targetPort: Int) : AutoCloseable {
        private val listener = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val running = AtomicBoolean(true)
        private val sockets = ConcurrentLinkedQueue<Socket>()
        val port: Int = listener.localPort
        val pubAckDropped = CountDownLatch(1)

        private val acceptThread = Thread({
            while (running.get()) {
                val client = runCatching { listener.accept() }.getOrNull() ?: break
                val broker = try {
                    Socket(InetAddress.getLoopbackAddress(), targetPort)
                } catch (_: Exception) {
                    client.close()
                    break
                }
                sockets += client
                sockets += broker
                forward("mqtt-proxy-client", client.inputStream, broker.outputStream, client, broker, parseFrames = false)
                forward("mqtt-proxy-broker", broker.inputStream, client.outputStream, client, broker, parseFrames = true)
            }
        }, "mqtt-proxy-accept").apply { isDaemon = true; start() }

        private fun forward(
            name: String,
            input: InputStream,
            output: OutputStream,
            client: Socket,
            broker: Socket,
            parseFrames: Boolean,
        ) {
            Thread({
                try {
                    if (parseFrames) {
                        while (running.get()) {
                            val frame = readFrame(input) ?: break
                            if ((frame[0].toInt() and 0xf0) == 0x40) {
                                pubAckDropped.countDown()
                            } else {
                                output.write(frame)
                                output.flush()
                            }
                        }
                    } else {
                        input.copyTo(output)
                        output.flush()
                    }
                } catch (_: Exception) {
                    // Closing either side is the proxy's normal teardown signal.
                } finally {
                    runCatching { client.close() }
                    runCatching { broker.close() }
                }
            }, name).apply { isDaemon = true; start() }
        }

        private fun readFrame(input: InputStream): ByteArray? {
            val first = input.read()
            if (first < 0) return null
            val header = ByteArrayOutputStream(5)
            header.write(first)
            var multiplier = 1
            var remaining = 0
            repeat(4) {
                val encoded = input.read()
                if (encoded < 0) return null
                header.write(encoded)
                remaining += (encoded and 0x7f) * multiplier
                if ((encoded and 0x80) == 0) {
                    val payload = input.readNBytes(remaining)
                    if (payload.size != remaining) return null
                    return header.toByteArray() + payload
                }
                multiplier *= 128
            }
            error("invalid MQTT remaining-length field")
        }

        override fun close() {
            running.set(false)
            runCatching { listener.close() }
            sockets.forEach { runCatching { it.close() } }
            acceptThread.join(1_000)
        }
    }

    private class TestClient(private val client: Mqtt5AsyncClient) : AutoCloseable {
        fun connect() = client.connect()

        fun subscribe(topic: String, messages: MessageLatch) {
            client.subscribeWith()
                .topicFilter(topic)
                .qos(MqttQos.AT_LEAST_ONCE)
                .callback { messages.offer(it.topic.toString(), it.payloadAsBytes, it.isRetain, it.qos) }
                .send()
                .await()
        }

        fun publish(topic: String, payload: ByteArray, retain: Boolean) {
            client.publishWith()
                .topic(topic)
                .payload(payload)
                .qos(MqttQos.AT_LEAST_ONCE)
                .retain(retain)
                .send()
                .await()
        }

        fun publishUntilDelivered(topic: String, payload: ByteArray, retain: Boolean, messages: MessageLatch) {
            repeat(20) {
                publish(topic, payload, retain)
                if (messages.await(50, TimeUnit.MILLISECONDS)) return
            }
        }

        override fun close() {
            runCatching { client.disconnect().await() }
        }
    }

    private class RecordingCallbacks : MqttCallbacks {
        val connected = CountDownLatch(1)
        val publishAck = CountDownLatch(1)
        val disconnected = AtomicInteger(0)
        val publishAcks = AtomicInteger(0)

        override fun onConnected() {
            connected.countDown()
        }

        override fun onDisconnected(causeMessage: String?): Boolean {
            disconnected.incrementAndGet()
            return true
        }

        override fun onPublishAck() {
            publishAcks.incrementAndGet()
            publishAck.countDown()
        }
    }

    private data class Message(val topic: String, val payload: ByteArray, val retained: Boolean, val qos: MqttQos?)

    private class MessageLatch {
        private val latch = CountDownLatch(1)
        @Volatile private var message: Message? = null

        fun offer(topic: String, payload: ByteArray, retained: Boolean, qos: MqttQos? = null) {
            message = Message(topic, payload, retained, qos)
            latch.countDown()
        }

        fun await(timeout: Long, unit: TimeUnit): Boolean = latch.await(timeout, unit)

        fun awaitMessage(failure: String): Message {
            latch.awaitOrFail(failure)
            return requireNotNull(message)
        }
    }

    private companion object {
        fun CountDownLatch.awaitOrFail(message: String) {
            assertTrue(await(5, TimeUnit.SECONDS), message)
        }

        fun <T> java.util.concurrent.CompletableFuture<T>.await(): T = get(5, TimeUnit.SECONDS)

        fun awaitCondition(message: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!condition()) {
                assertTrue(System.nanoTime() < deadline, message)
                Thread.sleep(20)
            }
        }
    }
}
