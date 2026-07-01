# Control API & HTTP reference

How ha-paneld exposes a panel to Home Assistant: the uniform MQTT entities, the HTTP contract on `:8888`, and how a panel pairs itself. For a high-level capability list see the [README](../README.md#capabilities); to browse and try every endpoint live, open `http://<panel>:8888/api` (the OpenAPI explorer) or fetch `/openapi.json`.

## Uniform MQTT entities

Every panel publishes the **same** Home Assistant MQTT-discovery entities, regardless of underlying
hardware (the per-panel HAL is hidden behind them). Configure an MQTT broker and they appear with
no YAML:

| Entity | Capability | Notes |
|--------|------------|-------|
| `light.<panel>_screen` | brightness + on/off | on = backlight on, off = true backlight-off (no keyguard/PIN); JSON schema, brightness 0–255 |
| `light.<panel>_led` | RGB | published only when a LED backend is present (NDK `/dev/ledjni` or the root helper) |
| `text.<panel>_navigate` | push a URL to the panel | depends on Companion intent handling; last URL restored on reconnect |
| `event.<panel>_button` | hardware button presses | published only when the a11y key-filter is enabled |
| `number.<panel>_volume` | TTS/announce volume | 0–100% → `STREAM_MUSIC`; playback is the HTTP `/play` contract below |
| `sensor.<panel>_illuminance` | ambient lux | standard `SensorManager` `TYPE_LIGHT`; published only if present |
| `binary_sensor.<panel>_proximity` | proximity (occupancy) | standard `SensorManager` `TYPE_PROXIMITY`; published only if present |
| `button.<panel>_reload` | reload dashboard | force-stop + relaunch the configured dashboard package (root helper, else `su`) |
| `button.<panel>_reboot` | reboot panel | root helper, else `su` |
| `button.<panel>_launcher` | bring a launcher to the foreground | fires `CATEGORY_HOME` at a non-default launcher (or configured `launcher_package`), leaving the boot/default home app unchanged |
| `button.<panel>_home` | bring the HA dashboard to the foreground | launches `dashboard_package` if set, else the default home app (the HA Companion) — the complement of the Launcher button |

The device's display name (`configuration_url` "Visit" link, friendly name) and the LED/screen
states are re-published on every (re)connect, and the MQTT client auto-reconnects, so HA stays in
sync after a panel reboot or broker blip.

## HTTP contract

```text
GET  /              panel info + config page (versions, hardware, status; panel_id,
                    friendly name, MQTT broker/creds, dashboard package). This is the
                    device's configuration_url, so HA shows a "Visit" link.
GET  /api           interactive REST API explorer (renders the OpenAPI spec)
GET  /openapi.json  OpenAPI 3 spec of this API (import into Swagger / Postman)
POST /config        form-encoded settings from the page; persists + live-reconfigures
GET  /config        full config as JSON (MQTT password redacted) for fleet tooling
GET  /perf          live performance JSON (CPU %/clock, GPU, RAM, temp, top procs,
                    responsiveness) — polled by the page; sampled only while viewed
POST /instrumentation   enabled=true|false — turn the perf sampler on/off
GET  /proximity     live proximity raw + calibration (raw stays on the panel)
POST /proximity/{capture,threshold,sensitivity,reset}   tune the cutoff
GET  /inspect · POST /inspect/{start,stop}              WebView DevTools relay (:9222)
GET  /diag          copy-paste diagnostics dump (build, SELinux, su probe, /dev +
                    /sys node listings, packages, capability assessment)
GET  /health        -> 200 "ha-paneld <version> panel=<id>"
POST /play          body contains an audio URL (raw or {"url":"…"})
                    -> 200 "playing"  (download + play happen in the background)
                    -> 400 "no-url"   (no URL found in body)
```

The web page at `/` is how a user sets the **MQTT broker** without adb — find the panel's IP (mDNS
`_ha-paneld._tcp`, or the router), open `http://<ip>:8888/`, fill in the broker + credentials, Save.

The agent listens on **:8888**. Self-signed HTTPS sources are accepted (panels live on a trusted
LAN). This is the same contract as the reference shell receiver it replaces, so HA-side automation
needs no change when a panel migrates from the shell receiver to ha-paneld.

## Pairing

The agent advertises `_ha-paneld._tcp.local.` with TXT records (`ver`, `caps`, `path`). If an MQTT
broker is configured it publishes Home Assistant MQTT-discovery configs so panel entities appear
without YAML.

**Zero-config:** with no broker set, ha-paneld browses for Home Assistant's own `_home-assistant._tcp`
advert on the LAN and uses its MQTT broker on :1883 — so a fresh install pairs itself. If the broker
needs a login (e.g. the HA Mosquitto add-on), set the username/password on the config page; the MQTT
status reads *auth rejected* until they're right. Set the broker explicitly if it's elsewhere or your
LAN has more than one Home Assistant. With nothing found, the HTTP surface still works standalone.
