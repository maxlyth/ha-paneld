# Shelly Wall Display

> [!NOTE]
> **Research-only — no physical unit tested here.** Facts are sourced from firmware OTA analysis,
> the official [ShellyGroup/Wall-Display-Changelog](https://github.com/ShellyGroup/Wall-Display-Changelog),
> Shelly KB articles, the HA frontend issue tracker, and Pen Test Partners' security disclosure.
> A DeviceProfile implementation is in progress (separate process). This page is the hardware
> reference for that work.

*Researched 2026-06-26.*

---

## Product family

Shelly's wall-panel range uses the **`SAWD-*`** model prefix (Smart Android Wall Display). Seven SKUs
across two hardware generations:

### Legacy (Android 7, armeabi-v7a)

| SKU | Codename | Market name | Screen | Relays | Proximity |
|---|---|---|---|---|---|
| SAWD-0A1XX10EU1 | Stargate | Wall Display | 3.97" / 4" LCD colour touch | 1 | none |
| SAWD-2A1XX10EU1 | Pegasus | Wall Display X2 | 6.9" | 2 | IR (gpio-keys) |

> [!NOTE]
> A third legacy variant, **"Atlantis"** (SAWD-1A1XX10EU1), is reported by the community project [ShellyElevate](https://github.com/RapierXbox/ShellyElevate) with 1 relay and IR proximity. It does not appear in Shelly's official SKU table or changelog and has not been independently confirmed; treat it as an undocumented variant rather than an official product.

Legacy devices do not have AppStore support and will eventually stop receiving firmware updates.
Firmware downgrading is **not supported** on modern devices.

### Modern (Android 11+, arm64-v8a)

| SKU | Codename | Market name | Screen | Relays | Proximity |
|---|---|---|---|---|---|
| SAWD-3A1XE10EU2 | Blake | Wall Display XL | 10.1" | 2 | **LD2410 mmWave radar** |
| SAWD-5A1XX10EU0 | Jenna | Wall Display X2i | mid-size | 2 | IR (gpio-keys) |
| SAWD-6A1XX10EU0 | Cally | Wall Display X1i | compact | 2 | IR |
| SAWD-4A1XE10US0 | Maverick | Wall Display U1 | US SKU | 1 | IR |
| SAWD-6A0XX0EU0 | Dayna | Wall Display D1 | display-only | 0 | IR |

---

## Hardware platform

The Wall Display is an **Android device**, not an ESP-based embedded product like Shelly Gen1/Gen2
switches. It runs a custom Android launcher app called "Stargate".

| | Legacy (Stargate / Pegasus) | Modern (Blake / Jenna / Cally / Maverick / Dayna) |
|---|---|---|
| SoC | MediaTek **MT6580** (`K400_MT6580_32_N`) | — (arm64, Android 11+) |
| RAM | ~400 MB | — |
| Storage | ~7.5 GB eMMC | — |
| ABI | **armeabi-v7a** | **arm64-v8a** |
| Android | 7 | 11+ |
| Wi-Fi | — | Wi-Fi 6 (XL) |
| BT | BLE | BLE |

Built-in sensors (vary by model): temperature, humidity, ambient light, proximity (Blake/XL uses an **LD2410 mmWave radar** chip; other models use IR; Stargate has none), and on-board relay(s) for load switching. Modern devices all have a physical power button; Cally and Blake have 4 additional side buttons.

---

## Home Assistant dashboard integration

### Two modes (firmware 2.7.0+)

**Mode 1 — Built-in WebView browser** (all models): Settings → *Home Assistant* (formerly
Settings → Network → Home Assistant on older firmware) opens a WebView at a configured HA URL.
As of 2.7.0 this is un-deprecated and includes a *Clear WebView cache* option.

**Mode 2 — HA Companion app** (modern / AppStore devices only — Blake, Jenna, Cally, Maverick,
Dayna): install HA Companion from the built-in AppStore. The Companion runs in the system WebView
and has worked correctly in cases where the built-in browser had rendering issues.

Community guides recommend HACS kiosk-mode to hide the HA sidebar/header for a clean panel look
on either mode.

### HA WebView history

| Firmware | Status |
|---|---|
| < 2.3.0-beta | Feature does not exist |
| 2.3.0-beta | Introduced — Settings → Network → Home Assistant |
| 2.6.0 | **Deprecated** for AppStore devices; Companion preferred |
| 2.7.0 | **Un-deprecated** — coexists with Companion app |

### Known compatibility issues

The built-in browser WebView on the Wall Display XL had rendering/layout problems with HA frontend
2025.12 and 2026.1 beta (tracked in `home-assistant/frontend#28755` and `#28746`; core compatibility:
`home-assistant/core#162665`). The HA Companion app on the same device rendered correctly,
confirming the issue was WebView-specific rather than device hardware.

---

## Access model — adb, root, sideloading

> [!WARNING]
> **There is no user-facing adb or root access on Shelly Wall Display devices.** Shelly does not
> expose Developer options, `adb`, or `su` to end users. This is the primary constraint on any
> ha-paneld integration.

**What this means for ha-paneld:**
- The daemon (`hapaneld-helper`) cannot be installed — no privileged path to `/system`.
- `su`-backed actions (true screen-off, brightness sysfs, CPU governor, screenshot, sensor reads)
  are not available.
- The `appCanSu = false` daemon path is moot without a way to install the daemon binary.
- **Sideloading** is possible on modern devices via the built-in AppStore (2.6.0+) or by any
  method Shelly exposes. Legacy devices have no sideload path.

Community project `RapierXbox/ShellyElevate` attempts to run HA stably on the Wall Display as an
alternative to the Stargate launcher — it exposes relays/sensors/buttons to HA and wraps the
WebView as a kiosk — and gives an indication of what is achievable without root.

---

## Built-in sensors and relay

Sensor and relay details vary by model. From firmware and product pages:

| Component | Notes |
|---|---|
| Temperature + humidity | Present on Stargate (4"), X2, and reportedly others |
| Ambient light | Confirmed on XL (Blake); XL has auto-brightness |
| Proximity / radar | Blake/XL uses an **LD2410 mmWave radar** chip for `Motion` (occupancy) added 2.6.0. Other modern models use IR proximity via gpio-keys. Neither is exposed via `android.hardware.sensor.proximity` — both require root to read directly. |
| Relay | 1 × on-board relay on most models; XL has a relay + input connector |

These are managed entirely by the Stargate launcher via Shelly's own RPC API — they surface to HA
as Shelly Gen2 entities, not via ha-paneld. A DeviceProfile entry would only expose what ha-paneld
can reach directly, which — absent root — is limited to what Android's own APIs report.

---

## Firmware OTA mechanism

### What the firmware actually is

Wall Display firmware is an **Android APK** (the Stargate launcher app) packaged as a signed Android
OTA ZIP. It is architecturally nothing like Shelly Gen1 (ESP8266 `.zip`) or Gen2 switch firmware
(EFR32 `.gbl`) — it is an Android application update applied by the Shelly in-app OTA downloader,
not a partition-level flash tool.

### OTA API

Wall Display devices use the **Shelly Gen2 RPC API** for update management:

```
Shelly.CheckForUpdate  →  { "stable": {"version": "2.7.1", "build_id": "..."}, "beta": {...} }
Shelly.Update { "stage": "stable" }    // pull from the update manifest
Shelly.Update { "url": "..." }         // install from a custom URL
```

An hourly check (added 2.6.0) and a startup check trigger automatically. The 2.7.0 OTA sanity
check verifies the downloaded update is built for the correct hardware before applying.

### Update manifest endpoints (verified 2026-06-26)

There are **two firmware tracks** — one per hardware generation:

#### Track 1 — Legacy (armeabi-v7a, Android 7: Stargate + Pegasus)

```
GET https://updates.shelly.cloud/update/WallDisplay
```

Covers SAWD-0A1XX10EU1 (Stargate) and SAWD-2A1XX10EU1 (Pegasus). The OTA updater-script asserts
`ro.product.device` is `k400_mt6580_32_n` (Stargate) or `e500_7731e_32u_o` (Pegasus) before
applying.

Response as of 2026-06-26 (`stable.version`: `2.7.1`, `build_id`:
`20260609-205046/2.7.1-857d7175`; CDN URL is a SHA-256-named blob — see note below).

#### Track 2 — Modern (arm64-v8a, Android 11+: all five modern SKUs)

```
GET https://updates.shelly.cloud/update/WallDisplayV2
```

Covers Blake, Jenna, Cally, Maverick, Dayna. The OTA updater-script reads `ro.build.product` for
logging only — no per-product assertion — so one ZIP installs on all modern models.

Response as of 2026-06-26: same version (`2.7.1`) and build_id as Track 1; compiled for arm64-v8a.

Both tracks share version numbers and build IDs — they are compiled together from the same codebase
for different ABIs.

> [!NOTE]
> **The CDN URL is content-addressed (SHA-256 filename, no version in path).** It rotates with every
> release and cannot be inferred for older versions. There are no Wayback Machine archives of the CDN
> blobs. To build a version archive: monitor both manifest endpoints on each firmware release (track
> via ShellyGroup/Wall-Display-Changelog commits) and download both URLs immediately — they are
> unreachable after they rotate.

#### Static legacy CDN (SAWD-0A1XX10EU1 only)

```
https://repo.shelly.cloud/firmware/SAWD-0A1XX10EU1/stable/SAWD-0A1XX10EU1.zip
https://repo.shelly.cloud/firmware/SAWD-0A1XX10EU1/stable/SAWD-0A1XX10EU1-WebViewUpdate.zip
```

Frozen at version 1.2.1 (2023-08-15). The WebView update ZIP (107.5 MB) contains the system
WebView APK for the legacy Android 7 device. Directory listing returns 403.

### OTA file format

Verified from both tracks (2026-06-26):

Common structure of every OTA ZIP:
- `META-INF/com/google/android/updater-script` — custom shell-script OTA applier (not Edify)
- `manifest.json` — `{"name":"WallDisplay[V2]","version":"X.Y.Z","build_id":"...","build_timestamp":"..."}`
- `system/priv-app/Stargate/Stargate.apk` — the Shelly launcher (31 MB arm64 / ~10 MB armeabi-v7a)
- `META-INF/MANIFEST.MF` + `CERT.SF` + `CERT.RSA` — signed with SignApk

Legacy additions: `scatter.txt` (MediaTek MT6580 partition layout); `META-INF/com/android/metadata`
(build fingerprint `alps/full_k400_mt6580_32_n/...`, Android 7.0); updater-script asserts product
device before proceeding.

Modern additions: `device_owner_2.xml`, `device_admins.xml`, `tzdata/` updates; no `scatter.txt`
(not a partition-level flash).

The Stargate APK native-lib path confirms ABI: `lib/armeabi-v7a/libstargate_input.so` (legacy),
`lib/arm64-v8a/libstargate_input.so` (modern).

### Files downloaded 2026-06-26

| File | Track | Version | ABI | Size |
|---|---|---|---|---|
| `WallDisplay-2.7.1-stable.bin` | `WallDisplay` | 2.7.1 | armeabi-v7a | 28.6 MB |
| `WallDisplayV2-2.7.1-stable.bin` | `WallDisplayV2` | 2.7.1 | arm64-v8a | 38 MB |
| `SAWD-0A1XX10EU1-stable-firmware.zip` | repo.shelly.cloud (static) | 1.2.1 (2023-08-15) | armeabi-v7a | 12.5 MB |
| `SAWD-0A1XX10EU1-stable-WebViewUpdate.zip` | repo.shelly.cloud (static) | WebView for Android 7 | armeabi-v7a | 107.5 MB |

Archived to Google Drive `shelly/` 2026-06-26.

---

## Security

Firmware 2.6.0 disclosed that RPC-over-BLE was open to any BLE connection without authentication
(reported by Pen Test Partners). Fixed in stages:
- **2.6.0** — BLE connection confirmation dialog added (user must acknowledge before RPC session
  opens)
- **2.7.0** — GATT server set to non-connectable when *Enable Bluetooth RPC* is off; aggressive BLE
  scan matching to reduce unintended connections

---

## Firmware version history (stable track, most recent first)

| Version | Date | Notes |
|---|---|---|
| 2.7.1 | 2026-06-10 | Automatic BT stack management (BLE on only when needed); aggressive BLE scan match; external sensor + BLE Gateway coupling |
| 2.7.0 | 2026-06-03 | Multi-dashboard (XL: 5, X2i/Pegasus: 3, others: 1); HA WebView un-deprecated + cache clear; `Ui.OpenCameraFullscreen` RPC; OTA hardware sanity check; GATT non-connectable when RPC-over-BLE off |
| 2.6.2 | 2026-05-20 | Shelly Camera tiles (live video stream); screensaver/brightness/thermostat fixes; auto-brightness fix X2i |
| 2.6.1 | 2026-05-14 | Legacy SW-input-in-screensaver fix; language-change infinite loading fix |
| 2.6.0 | 2026-05-12 | AppStore (modern only); Scripts (QuickJS); Virtual Components; Motion/Occupancy (XL radar); BLE auth dialog; Fahrenheit; hourly OTA check; HA WebView deprecated for AppStore models |
| 2.5.8 | ~2026-02 | Thermostat schedule regression fix |
| 2.5.7 | ~2026-01 | Layout/alarm/thermostat fixes; BLE RPC permission popup |
| 2.5.6 | 2026-02-04 | Home page symmetry; WebView background pause on heavy HA dashboards (crash avoidance; also causes WebRTC reload) |
| 2.5.5 | 2025-12-08 | Radio alarm, multi-channel roller, BT headset, brightness, RSSI/WiFi band info |
| 2.5.4 | ~2025-11 | Weather tile, thermostat SW input, timezone 2025b, X2 landscape |
| 2.5.3 | ~2025-10 | Gesture actions; WebView version check for old X1 |
| 2.5.2 | 2025-10-29 | Thermostat schedule override fix; screen-off idle fix |
| 2.5.1 | 2025-10-27 | Android Accessibility settings; screen timeout options; Shelly Weather Station; gestures; XL side-button thermostat |
| 2.4.5 | 2025-10-02 | MQTT connection fix |
| 2.4.4 | ~2025-09 | WiFi network deletion fix; XL portrait mode; XL button notifyInputEvent |
| 2.4.3 | ~2025-09 | Zendure tile; XL group actions; disable horizontal swipe for HA |
| 2.4.2 | ~2025-08 | PV tile update fix |
| 2.4.1 | ~2025-08 | PV tile crash fix |
| 2.4.0 | ~2025-08 | Settings import/export; alarms; PV tiles; media types (RINGTONE/ALERT); speed test |
| 2.3.4 | 2025-02-23 | Emergency hotfix: OTA update channel failure (devices stuck on old firmware) |
| 2.3.2 | early 2025 | Hotfix: virtual groups/WD Media Players removed from home screens |
| 2.3.1 | early 2025 | SONOS tile; HTTP → OkHttp3 |
| 2.3.0 | early 2025 | SONOS integration |
| 2.0.0 | 2024 | Major 2.x rewrite |
| 1.2.1 | 2023-08-15 | Oldest confirmed downloadable version (static CDN) |

Full changelog: [ShellyGroup/Wall-Display-Changelog](https://github.com/ShellyGroup/Wall-Display-Changelog) (covers 2.4.0+; earlier via `community.shelly.cloud`, login required).

---

## DeviceProfile — what is known / what is unknown

A DeviceProfile for the Shelly Wall Display is in development. This section records what the profile
can and cannot declare from research alone, pending hardware confirmation.

### Known / derivable from firmware

| Field | Value | Source |
|---|---|---|
| `id` | `"shelly-wall-display"` / `"shelly-wall-display-xl"` | — |
| `displayName` | `"Shelly Wall Display"` / `"Shelly Wall Display XL"` | — |
| `socClass` | `"MT6580"` (legacy) / `"arm64"` (modern, SoC TBC) | firmware metadata |
| `suForm` | `SuForm.NONE` | no user-accessible root |
| `appCanSu` | `false` | no user-accessible root |
| `usesDaemon` | `false` (daemon cannot be installed) | no `/system` write path |
| `ledMechanism` | `LedMechanism.NONE` (no driver path known) | TBC on hardware |
| `screenOff` | `ScreenOff.NONE` (no privileged screen-off path) | TBC on hardware |
| `relayBase` | TBC — relay present on hardware, no sysfs path yet | needs hardware probe |
| `zigbeeGatewayDir` | `null` (no Zigbee on Wall Display) | confirmed |
| `efr32UartPath` | `null` | confirmed |
| `buttonLedGpioBase` | `null` | not detected |
| `hasRecents` | TBC — likely present on Android 11 modern devices | needs hardware confirm |
| `proximityTech` | `"Radar"` (Blake/XL: **LD2410 mmWave**); `"Infrared"` (Jenna/Cally/Maverick/Dayna); none (Stargate) | firmware analysis, ShellyElevate source |
| `tameVendorCandidates` | TBC — Stargate APK (`com.shelly.stargate`?) + any preinstalled vendor apps | needs package enumeration on hardware |

### Unknown — requires a live unit

- Exact SoC model for modern devices (Blake/XL etc.)
- Relay sysfs path (class name, node format)
- Exact package name for the Stargate launcher
- Any preinstalled vendor apps worth taming
- Whether `Accessibility` service / key-injection survive across reboots (common panel gotcha)
- Whether ha-paneld's HTTP service can be reached from LAN (port 8888) — depends on Android
  firewall config
- Display density defaults and appropriate `rec` values per model

---

## Sources

- [ShellyGroup/Wall-Display-Changelog](https://github.com/ShellyGroup/Wall-Display-Changelog) — official firmware changelog
- [Shelly Wall Display KB](https://kb.shelly.cloud/knowledge-base/shelly-wall-display) — original (4") KB
- [Shelly Wall Display XL KB](https://kb.shelly.cloud/knowledge-base/shelly-wall-display-xl) — XL KB
- [Shelly Gen2 RPC API](https://shelly-api-docs.shelly.cloud/gen2/ComponentsAndServices/Shelly/) — `Shelly.CheckForUpdate` / `Shelly.Update`
- [home-assistant/frontend#28755](https://github.com/home-assistant/frontend/issues/28755) — built-in browser WebView rendering issue (XL)
- [home-assistant/core#162665](https://github.com/home-assistant/core/issues/162665) — HA integration compatibility
- [RapierXbox/ShellyElevate](https://github.com/RapierXbox/ShellyElevate) — community project: HA on Wall Display without root
- [Pen Test Partners — Shelly BLE disclosure](https://www.pentestpartners.com/) — BLE RPC auth issue (fixed 2.6.0/2.7.0)
