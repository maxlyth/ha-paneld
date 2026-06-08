package io.github.maxlyth.hapaneld.esphome

import android.util.Log
import com.google.protobuf.MessageLite
import io.github.maxlyth.hapaneld.esphome.proto.AuthenticationResponse
import io.github.maxlyth.hapaneld.esphome.proto.DeviceInfoResponse
import io.github.maxlyth.hapaneld.esphome.proto.DisconnectResponse
import io.github.maxlyth.hapaneld.esphome.proto.HelloRequest
import io.github.maxlyth.hapaneld.esphome.proto.HelloResponse
import io.github.maxlyth.hapaneld.esphome.proto.LightCommandRequest
import io.github.maxlyth.hapaneld.esphome.proto.ListEntitiesDoneResponse
import io.github.maxlyth.hapaneld.esphome.proto.NumberCommandRequest
import io.github.maxlyth.hapaneld.esphome.proto.PingResponse
import io.github.maxlyth.hapaneld.esphome.proto.SelectCommandRequest
import io.github.maxlyth.hapaneld.esphome.proto.SwitchCommandRequest
import io.github.maxlyth.hapaneld.esphome.proto.TextCommandRequest
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * ESPHome native-API server (spike/esphome-api) — registry-driven. Plaintext API (no Noise; HA supports
 * it; LAN-trust). Built on the official MIT .proto compiled in :esphome — no Ava code.
 *
 * The full entity surface is supplied by [entitiesProvider] (PaneldService, mirroring the MQTT discovery
 * gating). The server serves them generically: ListEntities -> each entity's listMsg + Done;
 * SubscribeStates -> each entity's state; commands routed by (message-id, key) to the entity's applier.
 *
 * Plaintext frame: [0x00][varint payload-len][varint message-id][protobuf payload].
 */
class EspHomeServer(
    private val deviceName: String,
    private val macAddress: String,
    private val version: String,
    private val friendlyName: String,
    private val model: String,
    private val manufacturer: String,
    private val webserverPort: Int,                 // -> DeviceInfoResponse.webserver_port = HA "Visit" link
    private val entitiesProvider: () -> List<EspEntity>,
) {
    @Volatile private var server: ServerSocket? = null
    @Volatile private var entitiesCache: List<EspEntity>? = null
    private val clients = java.util.Collections.synchronizedList(mutableListOf<ClientConn>())

    /** Built once, lazily, on a connection thread (gating probes use su/sysfs — never the main thread). */
    private fun entities(): List<EspEntity> =
        entitiesCache ?: synchronized(this) { entitiesCache ?: entitiesProvider().also { entitiesCache = it } }

    fun start() {
        if (server != null) return
        thread(name = "esphome-accept", isDaemon = true) {
            try {
                val s = ServerSocket(PORT); server = s
                Log.i(TAG, "ESPHome native-API server listening on :$PORT (plaintext)")
                while (!s.isClosed) {
                    val sock = try { s.accept() } catch (e: Exception) { break }
                    val c = ClientConn(sock); clients.add(c); c.start()
                }
            } catch (e: Exception) { Log.w(TAG, "esphome accept loop ended", e) }
        }
    }

    fun stop() {
        runCatching { server?.close() }; server = null
        synchronized(clients) { clients.forEach { it.close() }; clients.clear() }
    }

    /** Push live states to subscribed clients (call on local change: sensors, brightness, etc.). */
    fun publishStates() { synchronized(clients) { clients.toList() }.forEach { it.sendStatesIfSubscribed() } }

    /** Fire an event entity (button press) to subscribed clients. objectId matches the event entity. */
    fun publishEvent(objectId: String, type: String) {
        val e = entities().firstOrNull { it.listId == Esp.EVENT_LIST && it.matchesObject(objectId) } ?: return
        synchronized(clients) { clients.toList() }.forEach { it.sendIfSubscribed(Esp.EVENT_STATE, Esp.eventMsg(e.key, type)) }
    }

    private fun EspEntity.matchesObject(objectId: String): Boolean =
        runCatching { listMsg.javaClass.getMethod("getObjectId").invoke(listMsg) == objectId }.getOrDefault(false)

    private inner class ClientConn(private val sock: Socket) {
        private val ins: InputStream = sock.getInputStream()
        private val out: OutputStream = sock.getOutputStream()
        @Volatile private var subscribed = false

        fun start() = thread(name = "esphome-conn", isDaemon = true) {
            try { loop() } catch (e: Exception) { Log.d(TAG, "conn closed: ${e.message}") } finally { close(); clients.remove(this) }
        }

        private fun loop() {
            while (!sock.isClosed) {
                val preamble = ins.read(); if (preamble < 0) return
                if (preamble != 0x00) { Log.w(TAG, "non-plaintext frame ($preamble) — Noise unsupported in spike"); return }
                val len = readVarint(ins); val type = readVarint(ins)
                handle(type, readN(ins, len))
            }
        }

        private fun handle(type: Int, payload: ByteArray) {
            when (type) {
                1 -> { HelloRequest.parseFrom(payload); send(2, HelloResponse.newBuilder()
                    .setApiVersionMajor(1).setApiVersionMinor(10).setServerInfo("ha-paneld $version").setName(deviceName).build()) }
                3 -> send(4, AuthenticationResponse.newBuilder().setInvalidPassword(false).build())
                5 -> { send(6, DisconnectResponse.getDefaultInstance()); close() }
                7 -> send(8, PingResponse.getDefaultInstance())
                9 -> send(10, deviceInfo())
                11 -> { entities().forEach { send(it.listId, it.listMsg) }; send(19, ListEntitiesDoneResponse.getDefaultInstance()) }
                20 -> { subscribed = true; sendStates() }
                in CMD_PARSERS -> {
                    val (key, msg) = CMD_PARSERS.getValue(type)(payload)
                    val e = entities().firstOrNull { it.cmdId == type && it.key == key } ?: return
                    runCatching { e.apply?.invoke(msg) }.onFailure { Log.w(TAG, "apply failed for key=$key", it) }
                    e.state?.let { send(e.stateId, it()) }
                }
                else -> Log.d(TAG, "unhandled esphome msg id=$type len=${payload.size}")
            }
        }

        private fun sendStates() { entities().forEach { e -> e.state?.let { send(e.stateId, it()) } } }
        fun sendStatesIfSubscribed() { if (subscribed) runCatching { sendStates() } }
        fun sendIfSubscribed(id: Int, msg: MessageLite) { if (subscribed) runCatching { send(id, msg) } }

        private fun deviceInfo() = DeviceInfoResponse.newBuilder().setUsesPassword(false).setName(deviceName)
            .setMacAddress(macAddress).setEsphomeVersion("ha-paneld $version").setModel(model)
            .setManufacturer(manufacturer).setFriendlyName(friendlyName).setApiEncryptionSupported(false)
            .setWebserverPort(webserverPort).build()

        private fun send(id: Int, msg: MessageLite) {
            val bytes = msg.toByteArray()
            synchronized(out) { out.write(0x00); writeVarint(out, bytes.size); writeVarint(out, id); out.write(bytes); out.flush() }
        }
        fun close() = runCatching { sock.close() }
    }

    companion object {
        private const val TAG = "ha-paneld/esphome"
        const val PORT = 6053
        const val MDNS_SERVICE = "_esphomelib._tcp.local."

        // command message-id -> (parse bytes -> (key, message)). All *CommandRequest have key as field 1.
        private val CMD_PARSERS: Map<Int, (ByteArray) -> Pair<Int, MessageLite>> = mapOf(
            Esp.LIGHT_CMD to { b: ByteArray -> LightCommandRequest.parseFrom(b).let { it.key to it } },
            Esp.SWITCH_CMD to { b: ByteArray -> SwitchCommandRequest.parseFrom(b).let { it.key to it } },
            Esp.NUMBER_CMD to { b: ByteArray -> NumberCommandRequest.parseFrom(b).let { it.key to it } },
            Esp.SELECT_CMD to { b: ByteArray -> SelectCommandRequest.parseFrom(b).let { it.key to it } },
            Esp.TEXT_CMD to { b: ByteArray -> TextCommandRequest.parseFrom(b).let { it.key to it } },
            Esp.BUTTON_CMD to { b: ByteArray -> io.github.maxlyth.hapaneld.esphome.proto.ButtonCommandRequest.parseFrom(b).let { it.key to it } },
        )

        private fun readVarint(ins: InputStream): Int {
            var result = 0; var shift = 0
            while (true) { val b = ins.read(); if (b < 0) throw EOFException(); result = result or ((b and 0x7f) shl shift); if (b and 0x80 == 0) return result; shift += 7 }
        }
        private fun writeVarint(out: OutputStream, value: Int) {
            var v = value; while (true) { val b = v and 0x7f; v = v ushr 7; if (v != 0) out.write(b or 0x80) else { out.write(b); return } }
        }
        private fun readN(ins: InputStream, n: Int): ByteArray {
            val buf = ByteArray(n); var off = 0; while (off < n) { val r = ins.read(buf, off, n - off); if (r < 0) throw EOFException(); off += r }; return buf
        }
    }
}
