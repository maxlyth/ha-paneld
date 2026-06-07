package io.github.maxlyth.hapaneld.esphome

// SPIKE SKELETON (branch spike/esphome-api) — NOT wired in, NOT expected to compile until the :esphome
// proto module is generated + this is wired to the controllers + PaneldService. See SPIKE.md.
//
// Goal of the spike: prove HA's ESPHome integration can discover → connect → control ONE entity
// (light.<panel>_screen) served by ha-paneld over the ESPHome native API. If that round-trips, the
// protocol is validated as a v1.0 transport candidate (alongside or replacing MQTT).
//
// Protocol (plaintext — no Noise; HA supports it; matches ha-paneld's LAN-trust posture):
//   - TCP listen on :6053. Frame = [0x00][varint len][varint msg-type][protobuf payload].
//     (Noise frames start 0x01; we only do plaintext 0x00 for the spike.)
//   - Handshake: HelloRequest -> HelloResponse; ConnectRequest -> ConnectResponse (no password).
//   - DeviceInfoRequest -> DeviceInfoResponse (name, mac=androidId, model, esphome_version...).
//   - ListEntitiesRequest -> one ListEntities*Response PER ENTITY TYPE we already cover, then
//     ListEntitiesDoneResponse. SubscribeStatesRequest -> stream the *StateResponse for each.
//     *CommandRequest -> apply to the matching controller, then echo the new state.
//   - PingRequest -> PingResponse (keepalive). DisconnectRequest -> close.
//   - Advertise via mDNS: _esphomelib._tcp.local. (so HA auto-discovers; reuse MdnsAdvertiser).
//
// Entity-type coverage for the spike — ONE representative per ha-paneld type, to prove the protocol
// covers our whole existing surface (proto messages in io.github.maxlyth.hapaneld.esphome.proto.*):
//   light        screen        -> ListEntitiesLight / LightState / LightCommand            (BrightnessController)
//   text         navigate      -> ListEntitiesText / TextState / TextCommand               (NavigateController)
//   number       volume        -> ListEntitiesNumber / NumberState / NumberCommand          (VolumeController)
//   select       cpu_governor  -> ListEntitiesSelect / SelectState / SelectCommand          (CpuController)
//   switch       wake_on_wave  -> ListEntitiesSwitch / SwitchState / SwitchCommand          (Config flag)
//   button       reload        -> ListEntitiesButton / ButtonCommand (no state)            (SystemController)
//   binary_sensor proximity    -> ListEntitiesBinarySensor / BinarySensorState             (SensorReporter)
//   sensor       illuminance   -> ListEntitiesSensor / SensorState                          (SensorReporter)
//   event        button events -> ListEntitiesEvent / EventResponse                         (ButtonBus)
//
// TODO(spike) tomorrow:
//   1. Wire :esphome into settings.gradle.kts + reconcile protobuf versions with libs.versions.toml; build.
//   2. Implement frame read/write (varint + length-prefix) over a coroutine-backed Socket.
//   3. Implement the handshake + dispatch using the generated proto classes.
//   4. Register ONE entity of EACH type above, wired to its existing controller (start with light, then
//      add text/number/select/switch/button/binary_sensor/sensor/event — each is the same pattern).
//   5. mDNS _esphomelib._tcp; add the device in HA (ESPHome integration); confirm each type round-trips.
//   6. Compare vs the MQTT entity for each type (parity, latency, availability) — feeds the A/B.

class EspHomeServer {
    companion object {
        const val PORT = 6053
        const val MDNS_SERVICE = "_esphomelib._tcp.local."
    }
    // TODO: fun start(scope, brightness, screen, ...) — listen, accept, per-connection handshake + dispatch.
    // TODO: fun stop()
}
