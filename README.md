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
| TTS / announce audio | v0.1.0 | `POST /play` + MQTT `media_player` discovery |
| Screen brightness | planned v0.2.0 | MQTT `light.<panel>_screen` |
| Button-backlight / RGB LEDs | planned v0.2.0 | per-panel HAL |
| Hardware-button events | planned v0.3.0 | MQTT events |
| Proximity sensor | planned v0.3.0 | MQTT sensor |
| `reload-webview` / `soft-restart` | planned v0.3.0 | HTTP command |

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

## Supported hardware

ha-paneld shells out to `su -c` for privileged hardware writes (Phase ≥2) and otherwise needs no
system-signed install. Confirmed targets:

| Panel class | SoC | Android | ABI | Notes |
|-------------|-----|---------|-----|-------|
| Sonoff NSPanelPro / Pro120 | Rockchip PX30 | 8.1 (API 27) | arm64-v8a | toolbox `su` |
| Tuya TPA10 | Rockchip rk3566 | 11 (API 30) | armeabi-v7a | 32-bit userspace |

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
