# Shelly Wall Display

> [!NOTE]
> **Research-only — no physical unit tested here.** Facts are sourced from firmware OTA analysis (including a device-tree parse of the modern partition image), the official [ShellyGroup/Wall-Display-Changelog](https://github.com/ShellyGroup/Wall-Display-Changelog), Shelly KB articles, the HA frontend issue tracker, and Pen Test Partners' security disclosure. `ShellyWallDisplay` (legacy) and `ShellyWallDisplayV2` (modern) DeviceProfiles are implemented but remain **speculative** until verified on hardware.

*Researched 2026-06-26; firmware deep-parse 2026-06-28.*

---

## Product family

Shelly's wall-panel range uses the **`SAWD-*`** model prefix (Smart Android Wall Display). Seven SKUs across two hardware generations:

### Legacy (Android 7, armeabi-v7a)

| SKU | Codename | Market name | Screen | Relays | Proximity |
|---|---|---|---|---|---|
| SAWD-0A1XX10EU1 | Stargate | Wall Display | 3.97" / 4" LCD colour touch | 1 | none |
| SAWD-2A1XX10EU1 | Pegasus | Wall Display X2 | 6.9" | 2 | IR (gpio-keys) |

> [!NOTE]
> A third legacy variant, **"Atlantis"** (SAWD-1A1XX10EU1), is reported by the community project [ShellyElevate](https://github.com/RapierXbox/ShellyElevate) with 1 relay and IR proximity. It does not appear in Shelly's official SKU table or changelog and has not been independently confirmed; treat it as an undocumented variant rather than an official product.

Legacy devices do not have AppStore support and will eventually stop receiving firmware updates. Firmware downgrading is **not supported** on modern devices.

### Modern (Android 11, arm64-v8a)

| Codename | Market name | Screen | Relays | Proximity |
|---|---|---|---|---|
| Jenna | Wall Display X2i | 720×1440 ~5.5" (firmware-confirmed) | 2 | STK3A5x (combo, like NSPanel Pro) |
| Blake | Wall Display XL | 10.1" | 2 | reported LD2410 mmWave radar (unconfirmed) |
| Cally | Wall Display X1i / XLi | compact | 2 | IR |
| Maverick | Wall Display U1 (US) | — | 1 | IR |
| Dayna | Wall Display D1 | display-only | 0 | IR |

> [!CAUTION]
> The `SAWD-*` SKU ↔ codename ↔ screen-size mapping circulating in community notes is **unreliable**. A partition OTA filed under SKU `SAWD-3A1XE10EU2` (claimed to be Blake/XL/10.1") actually asserts `ro.product.device == "Jenna"` and flashes a **5.5" Rockchip PX30** image. Match on the **codename** (`ro.product.device`), not the SKU prefix. Only **Jenna** has been confirmed from firmware so far; the other modern models' SoC and proximity hardware are inferred, and a 10.1" XL may well be a different SoC.

---

## Hardware platform

The Wall Display is an **Android device**, not an ESP-based embedded product like Shelly Gen1/Gen2 switches. It runs a custom Android launcher app called "Stargate".

| | Legacy (Stargate / Pegasus) | Modern (Jenna confirmed; others inferred) |
|---|---|---|
| SoC | MediaTek **MT6580** (`k400_mt6580_32_n`) | **Rockchip PX30** (Jenna — same SoC as the Sonoff NSPanel Pro) |
| ABI | **armeabi-v7a** | **arm64-v8a** (64-bit userspace on PX30's Cortex-A35 cores) |
| Android | 7.0 (`NRD90M`) | 11 (API 30, `RD2A.211001.002`); kernel Linux 4.19.232 |
| Build type | userdebug / test-keys | userdebug / release-keys |
| Wi-Fi | — | Wi-Fi 6 (XL) |
| BT | BLE | BLE |
| ODM | — | **Smatek** (DTB `smatek-keep-relay`; same maker as the [S9E](s9e.md)) |

The "arm64" modern track is **not** an exotic SoC — at least the **Jenna (Wall Display X2i)** is a Rockchip PX30, the same chip as the NSPanel Pro, just running a 64-bit Android 11 userspace. Both legacy and modern firmware are **userdebug** builds, so `adb root` is likely available *if* an `adb` connection can be obtained (Developer options are not user-exposed — see *Access model*).

Built-in sensors (Jenna, confirmed from the device tree): **STK3A5x** ambient-light + proximity combo (i2c 0x46 — the same sensor family as the NSPanel Pro), Goodix **GT9xx** touch, two GPIO relays via the Smatek latching driver, and a camera. The Blake/XL's "Motion" component (added 2.6.0) is **reported** to be an LD2410 mmWave radar. That is not hardware-confirmed, and no `ld2410` kernel driver appears in the firmware — so if the radar is present, it is a **UART-attached** module driven by the Stargate app (`libserial_port.so`), not an Android sensor.

---

## Home Assistant dashboard integration

### Two modes (firmware 2.7.0+)

**Mode 1 — Built-in WebView browser** (all models): Settings → *Home Assistant* (formerly Settings → Network → Home Assistant on older firmware) opens a WebView at a configured HA URL. As of 2.7.0 this is un-deprecated and includes a *Clear WebView cache* option.

**Mode 2 — HA Companion app** (modern / AppStore devices only — Blake, Jenna, Cally, Maverick, Dayna): install HA Companion from the built-in AppStore. The Companion runs in the system WebView and has worked correctly in cases where the built-in browser had rendering issues.

Community guides recommend HACS kiosk-mode to hide the HA sidebar/header for a clean panel look on either mode.

### HA WebView history

| Firmware | Status |
|---|---|
| < 2.3.0-beta | Feature does not exist |
| 2.3.0-beta | Introduced — Settings → Network → Home Assistant |
| 2.6.0 | **Deprecated** for AppStore devices; Companion preferred |
| 2.7.0 | **Un-deprecated** — coexists with Companion app |

### Known compatibility issues

The built-in browser WebView on the Wall Display XL had rendering/layout problems with HA frontend 2025.12 and 2026.1 beta (tracked in `home-assistant/frontend#28755` and `#28746`; core compatibility: `home-assistant/core#162665`). The HA Companion app on the same device rendered correctly, confirming the issue was WebView-specific rather than device hardware.

### WebView

**Legacy (MT6580, Android 7 — Stargate, Pegasus, Atlantis):** The stock system WebView is not included in the standard Shelly OTA packages — it comes from the base factory ROM and its exact version has not been established from available firmware. For the **Stargate** model (`SAWD-0A1XX10EU1`), Shelly publishes a separate WebView update package on the static CDN (`SAWD-0A1XX10EU1-WebViewUpdate.zip`, 107 MB) that installs `com.google.android.webview` **119.0.6045.194** (Chrome 119, ~Oct 2023, armeabi-v7a, minSDK 24/Android 7.0). Inspection of this ZIP (2026-06-26): package installs to `system/app/GoogleWebView/GoogleWebView.apk`. There is no equivalent WebView update ZIP for Stargate or Pegasus — users on those models are limited to whatever the factory ROM ships.

**Modern V2 (arm64, Android 11 — Jenna confirmed; Blake/Cally/Maverick/Dayna):** The standard Shelly OTA does not include a WebView package (the 2.7.1 OTA contains only app updates — Stargate.apk, Camera2.apk, ShellyPlaceholder.apk). The stock WebView version from the base image has not been extracted. (Note: the Stargate APK is *built against* the Android 13 SDK — `platformBuildVersion=13` — but the device OS is Android 11, per the partition OTA's build fingerprint.)

---

## Access model — adb, root, sideloading

> [!WARNING]
> **There is no user-facing adb or root access exposed on Shelly Wall Display devices.** Shelly does not surface Developer options, `adb`, or `su` to end users. This is the primary constraint on any ha-paneld integration.

**A caveat worth probing:** both the legacy and modern firmware are **userdebug** builds (confirmed from the OTA build fingerprints). On a userdebug build, `adb root` *succeeds* — so **if** an `adb` connection can be established (e.g. Developer options can be reached, or a service-mode/USB path exists), full root and the daemon become available. This has not been tested on a unit; it's the most promising avenue for a deeper ha-paneld integration and is worth investigating before assuming the device is fully closed.

**What the closed-by-default posture means for ha-paneld:**
- Without an `adb` foothold, the daemon (`hapaneld-helper`) cannot be installed — no privileged path to `/system`.
- `su`-backed actions (true screen-off, brightness sysfs, CPU governor, screenshot, sensor reads) are then unavailable; the `appCanSu = false` path applies.
- **Sideloading** is possible on modern devices via the built-in AppStore (2.6.0+) or by any method Shelly exposes. Legacy devices have no sideload path.
- The Stargate launcher is provisioned as **Device Owner** *and* is the **home launcher**, so even with a sideload, setting ha-paneld as the default home triggers the launcher-chooser and Stargate cannot be uninstalled without root.

Community project `RapierXbox/ShellyElevate` attempts to run HA stably on the Wall Display as an alternative to the Stargate launcher; it exposes relays/sensors/buttons to HA, wraps the WebView as a kiosk, and gives an indication of what is achievable without root.

---

## Built-in sensors and relay

Sensor and relay details vary by model. From firmware and product pages:

| Component | Notes |
|---|---|
| Temperature + humidity | Present on Stargate (4"), X2, and reportedly others |
| Ambient light | STK3A5x ALS (Jenna, confirmed). Declared as `android.hardware.sensor.light` → readable via Android `SensorManager`. |
| Proximity | **Jenna**: STK3A5x proximity (i2c 0x46), **enabled in the kernel** — the same sensor the NSPanel Pro exposes through `SensorManager`. The Stargate manifest doesn't declare the `sensor.proximity` *feature*, but feature tags are advisory, so the `SensorManager` proximity path likely still works — i.e. wake-on-wave may be possible **without** root (unverified on hardware). **Blake/XL**: reported LD2410 mmWave radar (see above) — UART-attached, app-only, not reachable. |
| Relay | GPIO relays via Smatek's `smatek-keep-relay` latching driver (Jenna: GPIO 18/19 + a detect line on GPIO 20), 1–2 per model. Driven by Stargate internally over Gen2 RPC — no exported broadcast intents, no standard `/sys/class` path. |

The relays and the radar are managed entirely by the Stargate launcher via Shelly's own RPC API — they surface to HA as **Shelly Gen2 entities** (use HA's Shelly integration), not via ha-paneld. Without root, ha-paneld can reach only what Android's own APIs report: ambient light, and **probably** proximity, via `SensorManager`.

---

## Firmware OTA mechanism

### What the firmware actually is

Wall Display firmware is an **Android APK** (the Stargate launcher app) packaged as a signed Android OTA ZIP. It is architecturally nothing like Shelly Gen1 (ESP8266 `.zip`) or Gen2 switch firmware (EFR32 `.gbl`) — it is an Android application update applied by the Shelly in-app OTA downloader, not a partition-level flash tool.

### OTA API

Wall Display devices use the **Shelly Gen2 RPC API** for update management:

```
Shelly.CheckForUpdate  →  { "stable": {"version": "2.7.1", "build_id": "..."}, "beta": {...} }
Shelly.Update { "stage": "stable" }    // pull from the update manifest
Shelly.Update { "url": "..." }         // install from a custom URL
```

An hourly check (added 2.6.0) and a startup check trigger automatically. The 2.7.0 OTA sanity check verifies the downloaded update is built for the correct hardware before applying.

### Update manifest endpoints (verified 2026-06-26)

There are **two firmware tracks** — one per hardware generation:

#### Track 1 — Legacy (armeabi-v7a, Android 7: Stargate + Pegasus)

```
GET https://updates.shelly.cloud/update/WallDisplay
```

Covers SAWD-0A1XX10EU1 (Stargate) and SAWD-2A1XX10EU1 (Pegasus). The OTA updater-script asserts `ro.product.device` is `k400_mt6580_32_n` (Stargate) or `e500_7731e_32u_o` (Pegasus) before applying.

Response as of 2026-06-26 (`stable.version`: `2.7.1`, `build_id`: `20260609-205046/2.7.1-857d7175`; CDN URL is a SHA-256-named blob — see note below).

#### Track 2 — Modern (arm64-v8a, Android 11+: all five modern SKUs)

```
GET https://updates.shelly.cloud/update/WallDisplayV2
```

Covers Blake, Jenna, Cally, Maverick, Dayna. The OTA updater-script reads `ro.build.product` for logging only — no per-product assertion — so one ZIP installs on all modern models.

Response as of 2026-06-26: same version (`2.7.1`) and build_id as Track 1; compiled for arm64-v8a.

Both tracks share version numbers and build IDs — they are compiled together from the same codebase for different ABIs.

> [!NOTE]
> **The CDN URL is content-addressed (SHA-256 filename, no version in path).** It rotates with every release and cannot be inferred for older versions. There are no Wayback Machine archives of the CDN blobs. To build a version archive: monitor both manifest endpoints on each firmware release (track via ShellyGroup/Wall-Display-Changelog commits) and download both URLs immediately — they are unreachable after they rotate.

#### Static legacy CDN (SAWD-0A1XX10EU1 only)

```
https://repo.shelly.cloud/firmware/SAWD-0A1XX10EU1/stable/SAWD-0A1XX10EU1.zip
https://repo.shelly.cloud/firmware/SAWD-0A1XX10EU1/stable/SAWD-0A1XX10EU1-WebViewUpdate.zip
```

Frozen at version 1.2.1 (2023-08-15). The WebView update ZIP (107.5 MB) contains the system WebView APK for the legacy Android 7 device. Directory listing returns 403.

### OTA file format

Verified from both tracks (2026-06-26):

Common structure of every OTA ZIP:
- `META-INF/com/google/android/updater-script` — custom shell-script OTA applier (not Edify)
- `manifest.json` — `{"name":"WallDisplay[V2]","version":"X.Y.Z","build_id":"...","build_timestamp":"..."}`
- `system/priv-app/Stargate/Stargate.apk` — the Shelly launcher (31 MB arm64 / ~10 MB armeabi-v7a)
- `META-INF/MANIFEST.MF` + `CERT.SF` + `CERT.RSA` — signed with SignApk

Legacy additions: `scatter.txt` (MediaTek MT6580 partition layout); `META-INF/com/android/metadata` (build fingerprint `alps/full_k400_mt6580_32_n/...`, Android 7.0); updater-script asserts product device before proceeding.

Modern additions: `device_owner_2.xml`, `device_admins.xml`, `tzdata/` updates; no `scatter.txt` (not a partition-level flash).

The Stargate APK native-lib path confirms ABI: `lib/armeabi-v7a/libstargate_input.so` (legacy), `lib/arm64-v8a/libstargate_input.so` (modern).

### Files downloaded 2026-06-26

| File | Track | Version | ABI | Size |
|---|---|---|---|---|
| `WallDisplay-2.7.1-stable.bin` | `WallDisplay` | 2.7.1 | armeabi-v7a | 28.6 MB |
| `WallDisplayV2-2.7.1-stable.bin` | `WallDisplayV2` | 2.7.1 | arm64-v8a | 38 MB |
| `SAWD-0A1XX10EU1-stable-firmware.zip` | repo.shelly.cloud (static) | 1.2.1 (2023-08-15) | armeabi-v7a | 12.5 MB |
| `SAWD-0A1XX10EU1-stable-WebViewUpdate.zip` | repo.shelly.cloud (static) | WebView for Android 7 | armeabi-v7a | 107.5 MB |

---

## Security

Firmware 2.6.0 disclosed that RPC-over-BLE was open to any BLE connection without authentication (reported by Pen Test Partners). Fixed in stages:
- **2.6.0** — BLE connection confirmation dialog added (user must acknowledge before RPC session opens)
- **2.7.0** — GATT server set to non-connectable when *Enable Bluetooth RPC* is off; aggressive BLE scan matching to reduce unintended connections

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

Two profiles are implemented: **`ShellyWallDisplay`** (legacy MT6580) and **`ShellyWallDisplayV2`** (modern). This section records what they declare from firmware analysis and what still needs a live unit.

### Known / derivable from firmware

| Field | `ShellyWallDisplay` (legacy) | `ShellyWallDisplayV2` (modern) | Source |
|---|---|---|---|
| `socClass` | `"MediaTek MT6580"` | `"PX30 / rk3326 (Jenna confirmed; others unverified)"` | OTA build fingerprint; modern DTB |
| `suForm` / `appCanSu` | `NONE` / `false` | `NONE` / `false` | no user-exposed root (but userdebug — see *Access model*) |
| `ledMechanism` | `NONE` | `NONE` | DTB: pwm-backlight only, no RGB LED node |
| `screenOff` | `BRIGHTNESS_ZERO` | `BRIGHTNESS_ZERO` | no privileged screen-off path |
| `relayBase` | `null` | `null` | GPIO relays driven by Stargate via Gen2 RPC — no app-reachable sysfs |
| `zigbeeGatewayDir` / `efr32UartPath` | `null` / `null` | `null` / `null` | no Zigbee/Thread radio |
| `proximityTech` | `null` (IR via gpio-keys, root-gated) | `"Infrared"` (Jenna STK3A5x combo) | manifest declares only `sensor.light`; modern DTB enables STK3A5x proximity |
| `lightTech` | `"Ambient light"` | `"Ambient light"` (STK3A5x ALS) | `android.hardware.sensor.light` declared |
| App package | `cloud.shelly.stargate` (Device Owner + home launcher) | same | Stargate APK manifest + `device_owner_2.xml` |

### Unknown — requires a live unit

- Whether `SensorManager` proximity actually delivers on a modern unit (Jenna) — the high-value check: it would enable wake-on-wave without root, since the STK3A5x is enabled in the kernel.
- SoC of the **non-Jenna** modern models — a 10.1" XL (Blake) may not be a PX30; only Jenna is parsed.
- `hasRecents` per model (the Stargate home image may suppress overview).
- Display density defaults and appropriate `recommendedDensity` per model (Jenna 720×1440 ~5.5" ≈ 320 native).
- Whether an `adb` foothold + `adb root` is reachable on the userdebug build (would unlock the daemon).
- Whether ha-paneld's HTTP service (`:8888`) is reachable from the LAN (depends on the device's firewall).

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
