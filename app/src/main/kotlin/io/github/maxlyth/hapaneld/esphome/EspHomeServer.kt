package io.github.maxlyth.hapaneld.esphome

import android.util.Log
import com.google.protobuf.MessageLite
import io.github.maxlyth.hapaneld.esphome.proto.AuthenticationResponse
import io.github.maxlyth.hapaneld.esphome.proto.BinarySensorStateResponse
import io.github.maxlyth.hapaneld.esphome.proto.ButtonCommandRequest
import io.github.maxlyth.hapaneld.esphome.proto.ColorMode
import io.github.maxlyth.hapaneld.esphome.proto.DeviceInfoResponse
import io.github.maxlyth.hapaneld.esphome.proto.DisconnectResponse
import io.github.maxlyth.hapaneld.esphome.proto.EntityCategory
import io.github.maxlyth.hapaneld.esphome.proto.HelloRequest
import io.github.maxlyth.hapaneld.esphome.proto.HelloResponse
import io.github.maxlyth.hapaneld.esphome.proto.LightCommandRequest
import io.github.maxlyth.hapaneld.esphome.proto.LightStateResponse
import io.github.maxlyth.hapaneld.esphome.proto.ListEntitiesBinarySensorResponse
import io.github.maxlyth.hapaneld.esphome.proto.ListEntitiesButtonResponse
import io.github.maxlyth.hapaneld.esphome.proto.ListEntitiesDoneResponse
import io.github.maxlyth.hapaneld.esphome.proto.ListEntitiesLightResponse
import io.github.maxlyth.hapaneld.esphome.proto.ListEntitiesNumberResponse
import io.github.maxlyth.hapaneld.esphome.proto.ListEntitiesSensorResponse
import io.github.maxlyth.hapaneld.esphome.proto.ListEntitiesSwitchResponse
import io.github.maxlyth.hapaneld.esphome.proto.NumberCommandRequest
import io.github.maxlyth.hapaneld.esphome.proto.NumberMode
import io.github.maxlyth.hapaneld.esphome.proto.NumberStateResponse
import io.github.maxlyth.hapaneld.esphome.proto.PingResponse
import io.github.maxlyth.hapaneld.esphome.proto.SensorStateClass
import io.github.maxlyth.hapaneld.esphome.proto.SensorStateResponse
import io.github.maxlyth.hapaneld.esphome.proto.SwitchCommandRequest
import io.github.maxlyth.hapaneld.esphome.proto.SwitchStateResponse
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * SPIKE (branch spike/esphome-api): a minimal ESPHome **native-API server** in Kotlin, so HA's ESPHome
 * integration discovers -> connects -> controls ha-paneld. Plaintext API (no Noise; HA supports it).
 * Built on the official MIT .proto compiled in :esphome — no Ava code.
 *
 * Covers one entity of each type ha-paneld exposes, to prove the protocol covers our whole surface:
 *   light (screen) · switch (wake_on_wave) · number (volume) · sensor (illuminance) ·
 *   binary_sensor (proximity) · button (reload). (select/text/event follow the same pattern.)
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
    // light (screen)
    private val getBrightness: () -> Int, private val setBrightness: (Int) -> Unit,
    private val wake: () -> Unit, private val sleep: () -> Unit,
    // switch (wake_on_wave)
    private val getWakeOnWave: () -> Boolean, private val setWakeOnWave: (Boolean) -> Unit,
    // number (volume %)
    private val getVolume: () -> Int, private val setVolume: (Int) -> Unit,
    // sensor (illuminance lx) + binary_sensor (proximity near)
    private val getIlluminance: () -> Float, private val getProximityNear: () -> Boolean,
    // button (reload dashboard)
    private val onReload: () -> Unit,
) {
    @Volatile private var server: ServerSocket? = null
    private val clients = java.util.Collections.synchronizedList(mutableListOf<ClientConn>())

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

    /** Push live sensor/light/etc. states to subscribed clients (call on local change). */
    fun publishStates() { synchronized(clients) { clients.toList() }.forEach { it.sendAllStatesIfSubscribed() } }

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
                11 -> { sendListEntities(); send(19, ListEntitiesDoneResponse.getDefaultInstance()) }
                20 -> { subscribed = true; sendAllStates() }
                32 -> {  // LightCommandRequest
                    val c = LightCommandRequest.parseFrom(payload)
                    if (c.key == K_LIGHT) {
                        if (c.hasState && !c.state) sleep() else { if (c.hasState && c.state) wake(); if (c.hasBrightness) setBrightness((c.brightness * 255f).toInt().coerceIn(0, 255)) }
                        send(24, lightState())
                    }
                }
                33 -> { val c = SwitchCommandRequest.parseFrom(payload); if (c.key == K_SWITCH) { setWakeOnWave(c.state); send(26, switchState()) } }
                51 -> { val c = NumberCommandRequest.parseFrom(payload); if (c.key == K_NUMBER) { setVolume(c.state.toInt().coerceIn(0, 100)); send(50, numberState()) } }
                62 -> { val c = ButtonCommandRequest.parseFrom(payload); if (c.key == K_BUTTON) onReload() }
                else -> Log.d(TAG, "unhandled esphome msg id=$type len=${payload.size}")
            }
        }

        private fun sendListEntities() {
            send(15, ListEntitiesLightResponse.newBuilder().setObjectId("screen").setKey(K_LIGHT).setName("Screen")
                .addSupportedColorModes(ColorMode.COLOR_MODE_BRIGHTNESS).setEntityCategory(EntityCategory.ENTITY_CATEGORY_NONE).build())
            send(17, ListEntitiesSwitchResponse.newBuilder().setObjectId("wake_on_wave").setKey(K_SWITCH).setName("Wake on wave")
                .setEntityCategory(EntityCategory.ENTITY_CATEGORY_CONFIG).build())
            send(49, ListEntitiesNumberResponse.newBuilder().setObjectId("volume").setKey(K_NUMBER).setName("Volume")
                .setMinValue(0f).setMaxValue(100f).setStep(1f).setUnitOfMeasurement("%").setMode(NumberMode.NUMBER_MODE_SLIDER).build())
            send(16, ListEntitiesSensorResponse.newBuilder().setObjectId("illuminance").setKey(K_SENSOR).setName("Illuminance")
                .setUnitOfMeasurement("lx").setAccuracyDecimals(0).setDeviceClass("illuminance").setStateClass(SensorStateClass.STATE_CLASS_MEASUREMENT).build())
            send(12, ListEntitiesBinarySensorResponse.newBuilder().setObjectId("proximity").setKey(K_BINSENSOR).setName("Proximity").setDeviceClass("occupancy").build())
            send(61, ListEntitiesButtonResponse.newBuilder().setObjectId("reload").setKey(K_BUTTON).setName("Reload dashboard").build())
        }

        private fun sendAllStates() {
            send(24, lightState()); send(26, switchState()); send(50, numberState()); send(25, sensorState()); send(21, binSensorState())
        }
        fun sendAllStatesIfSubscribed() { if (subscribed) runCatching { sendAllStates() } }

        private fun deviceInfo() = DeviceInfoResponse.newBuilder().setUsesPassword(false).setName(deviceName)
            .setMacAddress(macAddress).setEsphomeVersion("ha-paneld $version").setModel(model)
            .setManufacturer(manufacturer).setFriendlyName(friendlyName).setApiEncryptionSupported(false).build()

        private fun lightState(): LightStateResponse { val b = getBrightness()
            return LightStateResponse.newBuilder().setKey(K_LIGHT).setState(b > 0).setBrightness(b.coerceIn(0, 255) / 255f).setColorMode(ColorMode.COLOR_MODE_BRIGHTNESS).build() }
        private fun switchState() = SwitchStateResponse.newBuilder().setKey(K_SWITCH).setState(getWakeOnWave()).build()
        private fun numberState() = NumberStateResponse.newBuilder().setKey(K_NUMBER).setState(getVolume().toFloat()).build()
        private fun sensorState() = SensorStateResponse.newBuilder().setKey(K_SENSOR).setState(getIlluminance()).build()
        private fun binSensorState() = BinarySensorStateResponse.newBuilder().setKey(K_BINSENSOR).setState(getProximityNear()).build()

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
        private const val K_LIGHT = 1; private const val K_SWITCH = 2; private const val K_NUMBER = 3
        private const val K_SENSOR = 4; private const val K_BINSENSOR = 5; private const val K_BUTTON = 6

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
