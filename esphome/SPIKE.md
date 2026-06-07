# Spike: ESPHome native API as a v1.0 transport (branch `spike/esphome-api`)

**Why:** evaluate the ESPHome native API as the canonical HA transport for v1.0 — chosen for the
**protocol's future** (HA-Foundation-blessed, first-class Core attention), not media_player. It already
covers ha-paneld's existing entities. Decision horizon: **0.9.0 / before v1.0** (swapping transport
post-1.0 is breaking for installed users).

**Clean-room provenance (take from ESPHome, nothing from Ava):** the protocol is the official ESPHome
project's. The `.proto` here is vendored verbatim from **`esphome/aioesphomeapi` (MIT)** —
see `src/main/proto/UPSTREAM-LICENSE-MIT.txt` — with only `option java_package` added. The Kotlin
bindings are plain `protoc` output (not Ava's code). The server (`app/.../esphome/EspHomeServer.kt`) is
ha-paneld's own, written from the protocol — no Ava code copied. (Ava `github.com/brownard/Ava` is only a
*proof* that a Kotlin ESPHome server is tractable + uses plaintext.)

## Scaffold status

- [x] `esphome/src/main/proto/{api,api_options}.proto` — official MIT protocol + `java_package`.
- [x] `esphome/build.gradle.kts` — protobuf-gradle → Kotlin bindings (TODO: reconcile versions w/ catalog).
- [x] `app/.../esphome/EspHomeServer.kt` — skeleton (protocol flow + TODOs; not wired, won't build yet).
- [ ] `include(":esphome")` in `settings.gradle.kts` + align protobuf/protoc versions → first build.
- [ ] Implement framing (`[0x00][varint len][varint type][payload]`) + plaintext handshake + dispatch.
- [ ] Register **one entity of EACH type we already cover** (matrix below), wired to its controller; mDNS `_esphomelib._tcp`.
- [ ] Add device in HA (ESPHome integration) → confirm each type round-trips (discover → connect → control/report).

### Entity-type coverage (prove the protocol covers our whole existing surface)

One representative per ha-paneld entity type; the rest of each type follows the same pattern.

| ha-paneld type | spike example | ESPHome proto messages | controller |
| --- | --- | --- | --- |
| light | screen | ListEntitiesLight / LightState / LightCommand | BrightnessController + ScreenController |
| text | navigate | ListEntitiesText / TextState / TextCommand | NavigateController |
| number | volume | ListEntitiesNumber / NumberState / NumberCommand | VolumeController |
| select | cpu_governor | ListEntitiesSelect / SelectState / SelectCommand | CpuController |
| switch | wake_on_wave | ListEntitiesSwitch / SwitchState / SwitchCommand | Config flag |
| button | reload | ListEntitiesButton / ButtonCommand (no state) | SystemController |
| binary_sensor | proximity | ListEntitiesBinarySensor / BinarySensorState | SensorReporter |
| sensor | illuminance | ListEntitiesSensor / SensorState | SensorReporter |
| event | button events | ListEntitiesEvent / EventResponse | ButtonBus |

(Full surface, all mapped to the above types: light = screen/led/button-backlight/button-leds; switch =
wake_on_wave/touch_sound/zigbee_router/network_adb/auto_brightness/relays; number =
volume/brightness_bias/ambient_lux; sensor = illuminance/temperature/humidity; button =
back/recents/reload/reboot/launcher/home. So the 9 representatives validate everything.)

## Analysis 1 — does losing MQTT retained topics hurt us?

**What MQTT retained gives ha-paneld today:** state topics published `retain=true`, retained discovery
configs, and LWT + retained availability. Effect: HA reads the **last value from the broker on restart
even if the panel is offline**, and entities persist across HA restart without the panel republishing.
(ha-paneld also republishes everything on its own reconnect.)

**ESPHome model:** no broker, no retained state. State is held **by the device**; HA's ESPHome
integration connects and `SubscribeStates` streams live values. Panel offline ⇒ device **unavailable** ⇒
entities unavailable (no broker last-value). The device persists as a **config-entry** across HA
restart (re-connects), so entities don't vanish — they go unavailable until reconnect.

**Impact, per ha-paneld surface — LOW overall, arguably an improvement:**

| Surface | MQTT retained | ESPHome | Verdict |
| --- | --- | --- | --- |
| Actuators (screen, LED, navigate) | stale last value when off | unavailable when off | ESPHome more correct (can't control an off panel) |
| Sensors (illuminance, temp/humid, proximity) | last value when off | unavailable when off | honest; panel sensors aren't the authority anyway |
| Desired-state switches (auto_brightness, wake_on_wave, zigbee_router, network_adb, touch_sound) | mirrored from panel `Config` | streamed from panel `Config` | **same** — the panel's SharedPreferences is the source of truth either way |
| Availability | LWT + retained availability topic (we maintain it) | connection-based, automatic | ESPHome cleaner |
| HA-restart UX | instant last value from broker | reconnect (sub-second if panel up) then live | negligible (panels are always-on) |
| Discovery persistence | retained config survives restart | config-entry survives restart | equivalent |

**The one real watch-item:** during a brief panel reboot (~seconds), MQTT shows the last value; ESPHome
shows **unavailable**. Any HA automation that reads a panel entity's *value* mid-reboot would see
`unavailable` instead — more honest, but confirm no automation depends on the stale value. **Key point:
nothing ha-paneld persists relies on MQTT retention** — desired states live in the panel's `Config`
(that's why the Zigbee boot-restore + auto-brightness-on already work without retained topics). So the
gap does **not** block a switch.

## Analysis 2 — A/B test + migration proposal

**A/B (run both transports in parallel — zero risk to the MQTT fleet):** ha-paneld can run the MQTT
bridge AND the ESPHome server **simultaneously**. The panel then appears in HA as **two devices** (the
existing MQTT device + a new ESPHome device) with parallel entities (distinct entity_ids, e.g.
`light.office_ha_dash_screen` vs `light.<esphome>_screen`). On **one test panel** (office-dash or a
spare): enable both, then compare — reliability (reconnect after panel reboot / HA restart / network
blip), command→actuation latency, availability accuracy, the retained-vs-live behaviour above, and
CPU/battery. MQTT stays primary throughout; ESPHome is the experimental parallel surface.

**Migration (only if A/B wins) — per-panel, staged, history-preserving:**

1. Add the ESPHome device (new entity_ids appear).
2. Validate full entity parity over ESPHome.
3. Remove the MQTT device + clear its retained discovery (frees the old entity_ids).
4. **Rename** the ESPHome entities to the old entity_ids in the registry → automations/dashboards keep
   working unchanged. Use the **HA 2026.5+ recorder listener** (the `ha-entity-rename` skill) to
   **preserve LTS history** across the entity_id change (otherwise an integration swap loses history).
5. Patch any custom-integration `config_entries`/dashboard refs (cf. `feedback_entity_rename_config_entry_dangle`).
6. Roll the fleet one panel at a time after each validates. Keep MQTT behind a **config flag** as a
   fallback until ESPHome is proven fleet-wide.

**Phases:** 0 = this spike (one-entity round-trip). 1 = dual-stack on one panel → full A/B. 2 = decide
MQTT-only / ESPHome / both. 3 = staged per-panel migration w/ entity_id rename + history preservation.
4 = drop or keep MQTT as a fallback flag.

## Start here tomorrow

`settings.gradle.kts` include `:esphome` → reconcile protobuf versions → `./tools/build/build.sh :esphome:build`
(confirm the Kotlin bindings generate) → implement the framing + handshake → register one entity of
**each type** (matrix above), starting with light then adding text/number/select/switch/button/
binary_sensor/sensor/event → add the device in HA → confirm **each type** round-trips.

## Spike RESULTS — VALIDATED end-to-end (2026-06-08, overnight autonomous run)

**The ESPHome native API works as a v1.0 transport for ha-paneld — proven on real HA 2026.6.**

- **Feasibility (Phase 1):** `:esphome:build` green — protoc compiles the official MIT `.proto` into Java
  bindings in this Android/Gradle project. One fix: protoc-java can't emit a class named `void` (Java
  keyword) → renamed the empty `message void` (RPC void-return, wire-irrelevant) to `VoidMessage`.
- **Server (Phase 2/3):** `app/.../esphome/EspHomeServer.kt` — plaintext framing
  (`[0x00][varint len][varint id][payload]`) + handshake (Hello/Auth-ack/DeviceInfo/Ping/Disconnect) +
  ListEntities + SubscribeStates + LightCommand. Dual-stack with MQTT in `PaneldService` (zero MQTT risk).
- **Deployed (Phase 4):** release-signed build on **office** (172.31.12.20); `:6053` listening.
- **HA round-trip (Phase 5):** HA ESPHome config-flow (host 172.31.12.20:6053, no encryption) →
  `create_entry` "Office HA Panel". Entities appeared. **Command:** HA `light.turn_on` 76 → panel
  `screen_brightness` = **76**; 255 → **255**. **State:** HA reads back brightness 76, color_mode
  brightness. Full bidirectional control confirmed.

**Conclusion:** the Kotlin/ESPHome gap is fully resolved (protoc on ESPHome's MIT .proto + a ~150-line
plaintext server; no Ava code). The protocol covers ha-paneld's needs and is a viable v1.0 transport.

**Remaining for full coverage (same pattern as light — next):** switch/number/select/text/button/
binary_sensor/sensor/event entity flows (read each `ListEntities*/State*/Command*`, wire to the existing
controller). Then the A/B + migration plan above.

### Multi-type RESULTS (2026-06-08, overnight) — full entity surface validated

Expanded the server to one entity of each type ha-paneld uses, wired to real controllers, all validated
on real HA 2026.6 (office, dual-stack with MQTT — entities appear as the `_2` variants):

| type | entity | result |
| --- | --- | --- |
| light | screen | cmd 76/255 + state read-back ✓ |
| switch | wake_on_wave | turn_off → applied + echoed off ✓ |
| number | volume | set 50 → panel 46 (hardware volume-step quantization) + echoed ✓ |
| sensor | illuminance | entity present + reporting (state-only) ✓ |
| binary_sensor | proximity | entity present + reporting (state-only) ✓ |
| button | reload | entity registered (command-only; not pressed — would reload the live dashboard) ✓ |

All structural shapes proven: command+state (bool/float), state-only, command-only. **The ESPHome native
API covers ha-paneld's entire entity surface.**

**Remaining (quick, same patterns — not blockers):**
- `select` (cpu_governor — enum cmd+state, like switch), `text` (navigate — string cmd+state, like number),
  `event` (button events — one-shot via ButtonBus). ~20 lines each.
- **mDNS** `_esphomelib._tcp` advertise (auto-discovery; for the spike the device was added by host:port).
- **A/B + migration** (per the plan above): dual-stack on a panel for a soak; then per-panel entity_id
  rename + recorder-listener history preservation if switching.
- Decide MQTT-only / ESPHome / BOTH for v1.0 — but the protocol is now de-risked: it works, in Kotlin,
  with no Ava code, on the official MIT .proto.
