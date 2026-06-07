package io.github.maxlyth.hapaneld.esphome

import android.util.Log
import com.google.protobuf.MessageLite
import io.github.maxlyth.hapaneld.esphome.proto.AuthenticationResponse
import io.github.maxlyth.hapaneld.esphome.proto.ColorMode
import io.github.maxlyth.hapaneld.esphome.proto.DeviceInfoResponse
import io.github.maxlyth.hapaneld.esphome.proto.DisconnectResponse
import io.github.maxlyth.hapaneld.esphome.proto.EntityCategory
import io.github.maxlyth.hapaneld.esphome.proto.HelloRequest
import io.github.maxlyth.hapaneld.esphome.proto.HelloResponse
import io.github.maxlyth.hapaneld.esphome.proto.LightCommandRequest
import io.github.maxlyth.hapaneld.esphome.proto.LightStateResponse
import io.github.maxlyth.hapaneld.esphome.proto.ListEntitiesDoneResponse
import io.github.maxlyth.hapaneld.esphome.proto.ListEntitiesLightResponse
import io.github.maxlyth.hapaneld.esphome.proto.PingResponse
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * SPIKE (branch spike/esphome-api): a minimal ESPHome **native-API server** in Kotlin, so HA's ESPHome
 * integration discovers -> connects -> controls ha-paneld. Plaintext API (no Noise; HA supports it; matches
 * ha-paneld's LAN-trust posture). Built on the official MIT .proto compiled in :esphome — no Ava code.
 *
 * This first cut proves the protocol with ONE entity (screen light). The framing + handshake are the hard
 * part; additional entity types (switch/number/select/text/button/binary_sensor/sensor/event) are the same
 * pattern and get added next.
 *
 * Plaintext frame: [0x00][varint payload-len][varint message-id][protobuf payload].
 */
class EspHomeServer(
    private val deviceName: String,   // hostname / node name (<=31)
    private val macAddress: String,   // "XX:XX:XX:XX:XX:XX"
    private val version: String,      // ha-paneld version
    private val friendlyName: String,
    private val model: String,
    private val manufacturer: String,
    private val getBrightness: () -> Int,        // 0..255 (<=0 == off)
    private val setBrightness: (Int) -> Unit,    // 0..255
    private val wake: () -> Unit,
    private val sleep: () -> Unit,
) {
    @Volatile private var server: ServerSocket? = null
    private val clients = java.util.Collections.synchronizedList(mutableListOf<ClientConn>())
    private val lightKey = 1   // fixed32 key correlating state<->command

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
            } catch (e: Exception) {
                Log.w(TAG, "esphome accept loop ended", e)
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }; server = null
        synchronized(clients) { clients.forEach { it.close() }; clients.clear() }
    }

    /** Push the current light state to all subscribed clients (call when brightness changes locally). */
    fun publishLightState() {
        synchronized(clients) { clients.toList() }.forEach { it.sendLightStateIfSubscribed() }
    }

    private inner class ClientConn(private val sock: Socket) {
        private val ins: InputStream = sock.getInputStream()
        private val out: OutputStream = sock.getOutputStream()
        @Volatile private var subscribed = false

        fun start() = thread(name = "esphome-conn", isDaemon = true) {
            try { loop() } catch (e: Exception) { Log.d(TAG, "conn closed: ${e.message}") } finally { close(); clients.remove(this) }
        }

        private fun loop() {
            while (!sock.isClosed) {
                val preamble = ins.read()
                if (preamble < 0) return
                if (preamble != 0x00) { Log.w(TAG, "non-plaintext frame ($preamble) — Noise not supported in spike"); return }
                val len = readVarint(ins)
                val type = readVarint(ins)
                val payload = readN(ins, len)
                handle(type, payload)
            }
        }

        private fun handle(type: Int, payload: ByteArray) {
            when (type) {
                1 -> {
                    val hello = HelloRequest.parseFrom(payload)
                    Log.i(TAG, "hello from '${hello.clientInfo}' api ${hello.apiVersionMajor}.${hello.apiVersionMinor}")
                    send(2, HelloResponse.newBuilder()
                        .setApiVersionMajor(1).setApiVersionMinor(10)
                        .setServerInfo("ha-paneld $version").setName(deviceName).build())
                }
                3 -> send(4, AuthenticationResponse.newBuilder().setInvalidPassword(false).build())
                5 -> { send(6, DisconnectResponse.getDefaultInstance()); close() }
                7 -> send(8, PingResponse.getDefaultInstance())
                9 -> send(10, deviceInfo())
                11 -> {
                    send(15, ListEntitiesLightResponse.newBuilder()
                        .setObjectId("screen").setKey(lightKey).setName("Screen")
                        .addSupportedColorModes(ColorMode.COLOR_MODE_BRIGHTNESS)
                        .setEntityCategory(EntityCategory.ENTITY_CATEGORY_NONE).build())
                    send(19, ListEntitiesDoneResponse.getDefaultInstance())
                }
                20 -> { subscribed = true; sendLightState() }
                32 -> {
                    val cmd = LightCommandRequest.parseFrom(payload)
                    if (cmd.key == lightKey) {
                        if (cmd.hasState && !cmd.state) sleep()
                        else {
                            if (cmd.hasState && cmd.state) wake()
                            if (cmd.hasBrightness) setBrightness((cmd.brightness * 255f).toInt().coerceIn(0, 255))
                        }
                        sendLightState()
                    }
                }
                else -> Log.d(TAG, "unhandled esphome msg id=$type len=${payload.size}")
            }
        }

        private fun deviceInfo(): DeviceInfoResponse = DeviceInfoResponse.newBuilder()
            .setUsesPassword(false).setName(deviceName).setMacAddress(macAddress)
            .setEsphomeVersion("ha-paneld $version").setModel(model).setManufacturer(manufacturer)
            .setFriendlyName(friendlyName).setApiEncryptionSupported(false).build()

        private fun lightStateMsg(): LightStateResponse {
            val b = getBrightness()
            return LightStateResponse.newBuilder().setKey(lightKey).setState(b > 0)
                .setBrightness(b.coerceIn(0, 255) / 255f).setColorMode(ColorMode.COLOR_MODE_BRIGHTNESS).build()
        }
        fun sendLightState() = send(24, lightStateMsg())
        fun sendLightStateIfSubscribed() { if (subscribed) runCatching { sendLightState() } }

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

        private fun readVarint(ins: InputStream): Int {
            var result = 0; var shift = 0
            while (true) {
                val b = ins.read(); if (b < 0) throw EOFException()
                result = result or ((b and 0x7f) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        private fun writeVarint(out: OutputStream, value: Int) {
            var v = value
            while (true) { val b = v and 0x7f; v = v ushr 7; if (v != 0) out.write(b or 0x80) else { out.write(b); return } }
        }
        private fun readN(ins: InputStream, n: Int): ByteArray {
            val buf = ByteArray(n); var off = 0
            while (off < n) { val r = ins.read(buf, off, n - off); if (r < 0) throw EOFException(); off += r }
            return buf
        }
    }
}
