# ha-paneld

A small Android agent for **wall-mounted Home Assistant panels**. It exposes panel-side hardware
to Home Assistant over HTTP + MQTT auto-discovery + mDNS, so a panel pairs itself with HA when you
sideload the APK — no per-device YAML.

It is built for panel-class Android (Sonoff NSPanelPro, Tuya TPA10, and similar), **not** personal
phones. The official HA Companion app remains the HOME launcher and dashboard; ha-paneld runs as a
headless foreground service alongside it and never takes the foreground.

> **Status: v0.x preview.** The API is not yet stable and breaking changes are expected until
> v1.0.0. Use it on a panel you're comfortable re-flashing.

## Why not just the Companion app?

The Companion app targets personal phones. Wall panels need different primitives: arbitrary-URL
audio announcements, screen/LED/button control via privileged `su` writes, hardware-button events
back to HA, and turnkey mDNS pairing. ha-paneld covers those; Companion keeps doing what it does.

## Capabilities

| Cap | Status | Surface |
|-----|--------|---------|
| TTS / announce audio | v0.1.0 | `POST /play` + MQTT `media_player` |
| Screen brightness + on/off (sleep) | v0.2.0-dev | MQTT `light.<panel>_screen` |
| RGB LED | v0.2.0-dev | MQTT `light.<panel>_led` (per-panel HAL) |
| URL navigate | v0.2.0-dev | MQTT `text.<panel>_navigate` |
| Hardware-button events | v0.2.0-dev (needs device validation) | MQTT `event.<panel>_button` |
| `reload-webview` / `soft-restart` | planned v0.3.0 | MQTT command |

> [!NOTE]
> ha-paneld exposes the panel's light + proximity sensors as data (standard `SensorManager`), but
> they are **not** the occupancy/lux authority — room-level HA sensors (motion, lux, occupancy) are,
> being better placed and already calibrated. Brightness is therefore **HA-driven**: ha-paneld
> exposes the brightness actuator; the policy (from room sensors) lives in Home Assistant. Zigbee
> gateway and app-watchdog are out of scope (coexist with a dedicated tool if you need them).

## The control API — uniform MQTT entities

Every panel publishes the **same** Home Assistant MQTT-discovery entities, regardless of underlying
hardware (the per-panel HAL is hidden behind them). Configure an MQTT broker and they appear with
no YAML:

| Entity | Capability | Notes |
|--------|------------|-------|
| `light.<panel>_screen` | brightness + on/off | on = wake, off = sleep; JSON schema, brightness 0–255 |
| `light.<panel>_led` | RGB | published only when a LED backend is present |
| `text.<panel>_navigate` | push a URL to the panel | depends on Companion intent handling |
| `event.<panel>_button` | hardware button presses | published only when the a11y key-filter is enabled |
| `number.<panel>_volume` | TTS/announce volume | 0–100% → `STREAM_MUSIC`; playback is the HTTP `/play` contract below |
| `sensor.<panel>_illuminance` | ambient lux | standard `SensorManager` `TYPE_LIGHT`; published only if present |
| `binary_sensor.<panel>_proximity` | proximity (occupancy) | standard `SensorManager` `TYPE_PROXIMITY`; published only if present |
| `button.<panel>_reload` | reload dashboard | force-stop + relaunch the Companion WebView (root) |
| `button.<panel>_reboot` | reboot panel | `su -c reboot` (root) |

## HTTP contract (v0.1.0)

```
POST /play          body contains an audio URL (raw or {"url":"…"})
                    -> 200 "playing"  (download + play happen in the background)
                    -> 400 "no-url"   (no URL found in body)
GET  /health        -> 200 "ha-paneld <version> panel=<id>"
```

The agent listens on **:8888**. Self-signed HTTPS sources are accepted (panels live on a trusted
LAN). This is the same contract as the reference shell receiver it replaces, so HA-side automation
needs no change when a panel migrates from the shell receiver to ha-paneld.

## Pairing

The agent advertises `_ha-paneld._tcp.local.` with TXT records (`ver`, `caps`, `path`). If an MQTT
broker is configured it publishes Home Assistant MQTT-discovery configs so panel entities appear
without YAML. With no broker configured, the HTTP surface still works standalone.

## Provisioning (no device UI on rooted/userdebug panels)

All permissions are granted over adb — there is no per-device tap-through. Run the same script on
every panel (config-as-code):

```bash
scripts/provision.sh <panel-ip:5555> [path-to.apk]
```

It installs the APK and grants: `POST_NOTIFICATIONS`, `WRITE_SETTINGS` (brightness, via `appops`),
device-admin force-lock (`dpm set-active-admin`, for sleep), and optionally the accessibility
key-filter (buttons). None of these need a UI on a panel with `su`/adb-root.

Non-root panels: use the in-app setup screen, which fires the standard system permission intents.

**Permission → why:**

| Permission | For | Grant |
|------------|-----|-------|
| `POST_NOTIFICATIONS` | foreground-service notification | runtime / `pm grant` |
| `WRITE_SETTINGS` | screen brightness | `appops set <pkg> WRITE_SETTINGS allow` |
| Device admin (force-lock) | screen sleep (`lockNow`) | `dpm set-active-admin <pkg>/.control.PanelAdminReceiver` |
| Accessibility (key filter) | button events (optional) | `settings put secure enabled_accessibility_services …` |

Device-admin uses **active-admin**, not device-owner (device-owner needs an account-free device and
would conflict with the logged-in Companion).

## RGB LED — vendor `.so`, load-if-present

LED control on some panels (e.g. rk3576 / Electron WF1589T) needs a vendor native library
(`libjnielc.so`, `/dev/ledjni`). That library is third-party and **not bundled** — ha-paneld loads
it at runtime only if the operator has installed it on the panel; the LED entity is simply absent
otherwise. ha-paneld ships only its own clean-room JNI binding, no vendor bytes.

## Supported hardware

ha-paneld needs no system-signed install. Standard-Android capabilities (brightness, sleep,
navigate, TTS) work on any panel; LED/buttons depend on a per-panel HAL.

| Panel class | SoC | Android | ABI | Notes |
|-------------|-----|---------|-----|-------|
| Sonoff NSPanelPro / Pro120 | Rockchip PX30 | 8.1 (API 27) | arm64-v8a | toolbox `su` |
| Tuya TPA10 | Rockchip rk3566 | 11 (API 30) | armeabi-v7a | 32-bit userspace |
| Electron WF1589T | Rockchip rk3576 | userdebug (`adb root`) | arm64-v8a | RGB LED via vendor `.so` |

Other Android panels are welcome — contribute a HAL adapter for your hardware.

**minSdk is 26.** API < 26 is unsupported (the MQTT client cannot connect below API 26).

## Build

```sh
./gradlew :app:assembleDebug      # debug APK -> app/build/outputs/apk/debug/
./gradlew :app:assembleRelease    # release APK (unsigned in CI unless signing is configured)
```

Requires JDK 17. The Gradle wrapper pins the Gradle version; nothing else needs installing.

### Toolchain note

The build is pinned to a conservative AGP 8.7 / Kotlin 2.0 / Gradle 8.10 combo for reliable
first-run CI. Newer AGP/Kotlin is fine to adopt during the v0.x line — versions live in
[`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Stack

- **HTTP** — Ktor CIO engine (coroutine I/O, no thread-per-connection).
- **MQTT** — HiveMQ MQTT 5 client (NIO transport; ABI-agnostic).
- **mDNS** — JmDNS (chosen over `NsdManager` for reliable TXT records across API levels).

## Licence

Apache-2.0. See [LICENSE](LICENSE).
