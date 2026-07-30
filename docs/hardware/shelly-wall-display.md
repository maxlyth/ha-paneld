# Shelly Wall Display

> [!NOTE]
> **Research-only — no physical unit tested here.** Product specifications below come from Shelly's current product and knowledge-base pages; firmware behaviour comes from OTA analysis and the official [Wall Display changelog](https://github.com/ShellyGroup/Wall-Display-Changelog). The two bundled profiles predate the current model-specific specifications and remain **speculative** until they can be split and verified on hardware.

---

## Product family

Shelly's current range spans several distinct Android platforms. Firmware codenames are useful when inspecting an OTA, but should not be treated as a substitute for the retail model name.

| Firmware codename | Market name | Display | Platform | Relay hardware |
|---|---|---|---|---|
| Stargate | Wall Display (original) | 4", 480×480 | MT6580, Android 7 | 1 output |
| Pegasus | [Wall Display X2](https://kb.shelly.cloud/knowledge-base/shelly-wall-display-x2) | 6.9", 1440×720 | SC7731E / Cortex-A7, Android 8.1 | 1 output |
| Cally | [Wall Display X1i](https://kb.shelly.cloud/knowledge-base/shelly-wall-display-x1i) | 4", 720×720 | RK3326-S / Cortex-A35, Android 11 | interchangeable base: 1 output standard; optional 2-output base |
| Jenna | [Wall Display X2i](https://kb.shelly.cloud/knowledge-base/shelly-wall-display-x2i) | 6.9", 1440×720 | RK3326-S / Cortex-A35, Android 11 | interchangeable base: 1 output standard; optional 2-output base |
| Blake | [Wall Display XL](https://kb.shelly.cloud/knowledge-base/shelly-wall-display-xl) | 10.1" | RK3566 / Cortex-A55, Android 11 | 1 output |
| Maverick | Wall Display U1 (US) | — | not established | — |
| Dayna | Wall Display D1 | — | not established | — |

> [!NOTE]
> A variant called **Atlantis** is reported by the community project [ShellyElevate](https://github.com/RapierXbox/ShellyElevate), but it has not been matched to a current official product page. Treat it as undocumented until it can be identified from a live unit.

> [!CAUTION]
> An archived partition image filed under an XL-like SKU identifies itself as `Jenna` and describes a different display platform. It is useful evidence about that image, but not reliable evidence for current retail product specifications. The official model pages above take precedence; use runtime identifiers when matching an actual device.

---

## Hardware platform

The Wall Display is an **Android device**, not an ESP-based embedded product like Shelly Gen1/Gen2 switches. It runs a custom Android launcher app called "Stargate".

The original Wall Display, X2, X1i/X2i and XL are not one interchangeable hardware class: they use MT6580, SC7731E, RK3326-S and RK3566 respectively. The shared OTA channels describe package compatibility, not a shared SoC. The legacy image targets a `userdebug` base build, so `adb root` may be possible there *if* an ADB connection can be established, but no user-facing route has been verified; the modern image carries no build fingerprint, so the same cannot be claimed for it. See *Access model* below.

Shelly documents temperature and humidity sensing on the original and X2, and ambient-light sensing on the original, X2, X1i, X2i and XL. X2, X1i and X2i have documented proximity sensing; the XL has a motion sensor. Their exact components and app-independent Android access paths have not been established. Relay count is model- and base-dependent, and no app-accessible standard Android relay interface has been established.

---

## Home Assistant dashboard integration

### Two modes (firmware 2.7.0+)

**Mode 1 — Built-in WebView browser** (all models): Settings → *Home Assistant* (formerly Settings → Network → Home Assistant on older firmware) opens a WebView at a configured HA URL. As of 2.7.0 this is un-deprecated and includes a *Clear WebView cache* option.

**Mode 2 — HA Companion app** (modern / AppStore devices only — Blake, Jenna, Cally, Maverick, Dayna): install HA Companion from the built-in AppStore. The Companion runs in the system WebView and has worked correctly in cases where the built-in browser had rendering issues.

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

**Original Wall Display (`SAWD-0A1XX10EU1`, Android 7):** The stock system WebView is not included in the standard OTA package. Shelly publishes a separate update ZIP that installs `com.google.android.webview` **119.0.6045.194**. No equivalent package has been established for the other models.

**WallDisplayV2 track (arm64, Android 11):** The standard OTA does not include a WebView package. Its stock WebView version has not been established from the available firmware.

---

## Access model — adb, root, sideloading

> [!WARNING]
> **There is no user-facing adb or root access exposed on Shelly Wall Display devices.** Shelly does not surface Developer options, `adb`, or `su` to end users. This is the primary constraint on any ha-paneld integration.

**A caveat worth probing, but only on legacy hardware.** The legacy OTA declares its target build in `META-INF/com/android/metadata`, and at firmware 2.7.3 that is still `alps/full_k400_mt6580_32_n/k400_mt6580_32_n:7.0/NRD90M/vXD100008:userdebug/test-keys`. On a userdebug build `adb root` *succeeds*, so **if** an `adb` connection can be established, root and the daemon become available. Two limits on that evidence: the fingerprint describes the device's **base OS image**, whose timestamp is 2022-11-14 and which the app-only OTA does not change; and no `adb` route has been verified on a unit.

**It does not extend to the modern track.** The WallDisplayV2 package ships **no build fingerprint at all** — there is no `META-INF/com/android/metadata`, and no `userdebug`, `test-keys`, `release-keys` or `ro.build.fingerprint` string anywhere outside the bundled APKs (checked at 2.7.3). Its updater-script reads `ro.build.product` and `ro.build.version.incremental` for logging only. So the **retail modern OTA** evidences no build type either way, and Shelly states that production devices ship the `user` build type with ADB and developer facilities disabled by default.

One qualification, so the scope is exact: an archived **partition** image (not part of either retail OTA track) identifies itself as Android 11 `userdebug`. Its SKU-to-codename filing is unreliable and its display platform contradicts the current retail specification, so it is evidence about that image, not about what modern retail units run. Treat modern hardware as closed unless a live unit shows otherwise.

**What the closed-by-default posture means for ha-paneld:**
- Without an `adb` foothold, the daemon (`hapaneld-helper`) cannot be installed — no privileged path to `/system`.
- `su`-backed actions (true screen-off, brightness sysfs, CPU governor, screenshot, sensor reads) are then unavailable; the profiles declare `platform.app_can_su: false`.
- The modern built-in AppStore proves that Shelly can distribute approved applications, but ha-paneld is not currently established as one of them. No general user-facing ADB or sideload route has been verified. Legacy devices have no confirmed installation path.
- The Stargate launcher is provisioned as **Device Owner** *and* is the **home launcher**, so even with a sideload, setting ha-paneld as the default home triggers the launcher-chooser and Stargate cannot be uninstalled without root.

Community project `RapierXbox/ShellyElevate` attempts to run HA stably on the Wall Display as an alternative to the Stargate launcher; it exposes relays/sensors/buttons to HA, wraps the WebView as a kiosk, and gives an indication of what is achievable without root.

---

## Built-in sensors and relay

Sensor and relay details vary by model. From firmware and product pages:

| Component | Notes |
|---|---|
| Temperature + humidity | Documented on the original and X2. X1i, X2i and XL do not have built-in temperature/humidity sensors. Android API visibility is unverified. |
| Ambient light | Documented on the original, X2, X1i, X2i and XL; Android `SensorManager` visibility is unverified on hardware. |
| Motion / proximity | Proximity is documented on X2, X1i and X2i; the XL has an official motion sensor. Exact technology and Android API visibility remain unverified. |
| Relay | One output on the original, X2 and XL. X1i/X2i ship with a one-output base and support a separately sold two-output base. No app-accessible standard control path has been established. |

Relay entities should be handled through Home Assistant's Shelly integration rather than assumed to be directly controllable by ha-paneld. Sensor visibility to ordinary Android apps remains unverified.

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

### Update manifest endpoints (verified)

There are **two firmware tracks**, divided by package ABI rather than one uniform hardware generation:

#### Track 1 — WallDisplay (armeabi-v7a: original + X2)

```
GET https://updates.shelly.cloud/update/WallDisplay
```

Covers SAWD-0A1XX10EU1 (Stargate) and SAWD-2A1XX10EU1 (Pegasus). The OTA updater-script asserts `ro.product.device` is `k400_mt6580_32_n` (Stargate) or `e500_7731e_32u_o` (Pegasus) before applying.

Example response, captured at 2.7.1 (`stable.version`: `2.7.1`, `build_id`: `20260609-205046/2.7.1-857d7175`; CDN URL is a SHA-256-named blob — see note below). For what is current, read the index rather than this example.

#### Track 2 — WallDisplayV2 (arm64-v8a, Android 11 models)

```
GET https://updates.shelly.cloud/update/WallDisplayV2
```

Covers Blake, Jenna, Cally, Maverick, Dayna. The OTA updater-script reads `ro.build.product` for logging only — no per-product assertion — so one ZIP installs on all modern models.

Response: same version and build_id as Track 1 (`2.7.1` in the example above); compiled for arm64-v8a.

Both tracks share version numbers and build IDs — they are compiled together from the same codebase for different ABIs.

> [!NOTE]
> **The CDN URL is content-addressed (SHA-256 filename, no version in path).** It rotates with every release and cannot be inferred for older versions: once a newer release ships, the previous URL returns 404. Each release is therefore archived to the Wayback Machine as it is discovered, and the capture timestamps are recorded alongside the CDN URL in [`tools/firmware-index/fw-shelly-walldisplay.dat`](../../tools/firmware-index/fw-shelly-walldisplay.dat), which is what the archive links in the download table resolve to. A release that was never captured at the time it was current is unrecoverable.

#### Static legacy CDN (SAWD-0A1XX10EU1 only)

```
https://repo.shelly.cloud/firmware/SAWD-0A1XX10EU1/stable/SAWD-0A1XX10EU1.zip
https://repo.shelly.cloud/firmware/SAWD-0A1XX10EU1/stable/SAWD-0A1XX10EU1-WebViewUpdate.zip
```

Frozen at version 1.2.1 (2023-08-15). The WebView update ZIP (107.5 MB) contains the system WebView APK for the legacy Android 7 device. Directory listing returns 403.

### OTA file format

Verified from both tracks:

Common structure of every OTA ZIP:
- `META-INF/com/google/android/updater-script` — custom shell-script OTA applier (not Edify)
- `manifest.json` — `{"name":"WallDisplay[V2]","version":"X.Y.Z","build_id":"...","build_timestamp":"..."}`
- `system/priv-app/Stargate/Stargate.apk` — the Shelly launcher (31 MB arm64 / ~10 MB armeabi-v7a)
- `META-INF/MANIFEST.MF` + `CERT.SF` + `CERT.RSA` — signed with SignApk

Legacy additions: `scatter.txt` (MediaTek MT6580 partition layout); `META-INF/com/android/metadata` (build fingerprint `alps/full_k400_mt6580_32_n/...`, Android 7.0); updater-script asserts product device before proceeding.

Modern additions: `device_owner_2.xml`, `device_admins.xml`, `tzdata/` updates; no `scatter.txt` (not a partition-level flash).

The Stargate APK native-lib path confirms ABI: `lib/armeabi-v7a/libstargate_input.so` (legacy), `lib/arm64-v8a/libstargate_input.so` (modern).

### Known firmware artifacts

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
| 2.7.3 | 2026-07-29 | Idle Cloud connection: after ~1 minute of screensaver the device asks the Cloud to report statuses for selected devices only (thermostat sensor/actuator and the sensor chosen for the dashboard and screensaver), reducing network throughput. Connected Blu H&T readings and the device's own status reports are still sent |
| 2.7.2 | 2026-07-16 | Third-party apps uninstalled on factory reset; custom dashboard icon enumeration fixed (icons may be replaced once on update); thermostat actuator selection fixed for modern devices with more than one relay; Blake radar `Motion` RPC namespace fixed when the radar was already configured at startup |
| 2.7.1 | 2026-06-10 | Automatic BT stack management (BLE on only when needed); aggressive BLE scan match; external sensor + BLE Gateway coupling |
| 2.7.0 | 2026-06-03 | Multi-dashboard (XL: 5, X2i/Pegasus: 3, others: 1); HA WebView un-deprecated + cache clear; `Ui.OpenCameraFullscreen` RPC; OTA hardware sanity check; GATT non-connectable when RPC-over-BLE off |
| 2.6.2 | 2026-05-20 | Shelly Camera tiles (live video stream); screensaver/brightness/thermostat fixes; auto-brightness fix X2i |
| 2.6.1 | 2026-05-15 | Legacy SW-input-in-screensaver fix; language-change infinite loading fix |
| 2.6.0 | 2026-05-12 | AppStore (modern only); Scripts (QuickJS); Virtual Components; Motion/Occupancy (XL); BLE auth dialog; Fahrenheit; hourly OTA check; HA WebView deprecated for AppStore models |
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

## Device profiles — what is known / what is unknown

Two bundled YAML profiles currently follow the two OTA tracks: [`shelly-wall-display.yaml`](../../app/src/main/assets/device-profiles/shelly-wall-display.yaml) and [`shelly-wall-display-v2.yaml`](../../app/src/main/assets/device-profiles/shelly-wall-display-v2.yaml). The current product specifications show that an OTA track is too broad to represent model-specific SoC, display and relay facts. Treat those profile fields as preliminary until the profiles are split or made deliberately generic.

### Known / derivable from firmware

| Field | `shelly-wall-display` (legacy) | `shelly-wall-display-v2` (modern) | Source |
|---|---|---|---|
| `soc_class` | currently MT6580, but the X2 is SC7731E | currently PX30/rk3326, but X1i/X2i are RK3326-S and XL is RK3566 | official model pages; OTA fingerprints |
| `platform.su_form` / `app_can_su` | `none` / `false` | `none` / `false` | no user-exposed root (legacy targets a userdebug base build; modern is unevidenced — see *Access model*) |
| `hardware.led.mechanism` | `none` | `none` | current profile declaration; not verified across every model |
| `hardware.screen_off` | `brightness-zero` | `brightness-zero` | no privileged screen-off path |
| `hardware.relay_base` | absent | absent | relay operation is routed through the HA Shelly integration; no app-accessible standard path established |
| `hardware.zigbee_gateway_dir` | absent | absent | no Zigbee/Thread radio |
| `sensors.proximity_technology` | unverified per model | unverified per model | requires live hardware evidence |
| `sensors.light_technology` | `Ambient light` | `Ambient light` | official model pages; Android API exposure unverified |
| App package | `cloud.shelly.stargate` (Device Owner + home launcher) | same | Stargate APK manifest + `device_owner_2.xml` |

### Unknown — requires a live unit

- Exact runtime identifiers needed to split X2, X1i, X2i and XL without false-positive profile matches.
- Whether Android `SensorManager` exposes each documented ambient-light or motion/proximity sensor.
- `platform.has_recents` per model (the Stargate home image may suppress overview).
- Display density defaults and an appropriate `provisioning.display.density` per model.
- Whether an `adb` foothold + `adb root` is reachable on the legacy userdebug base build (would unlock the daemon). For modern hardware the prior question is what build type it actually runs, which the OTA does not reveal.
- Whether ha-paneld's HTTP service (`:8888`) is reachable from the LAN (depends on the device's firewall).

---

## Sources

- [ShellyGroup/Wall-Display-Changelog](https://github.com/ShellyGroup/Wall-Display-Changelog) — official firmware changelog
- [Shelly Wall Display KB](https://kb.shelly.cloud/knowledge-base/shelly-wall-display) — original (4") KB
- [Shelly Wall Display X2 KB](https://kb.shelly.cloud/knowledge-base/shelly-wall-display-x2)
- [Shelly Wall Display X1i KB](https://kb.shelly.cloud/knowledge-base/shelly-wall-display-x1i)
- [Shelly Wall Display X2i KB](https://kb.shelly.cloud/knowledge-base/shelly-wall-display-x2i)
- [Shelly Wall Display XL KB](https://kb.shelly.cloud/knowledge-base/shelly-wall-display-xl) — XL KB
- [Shelly Gen2 RPC API](https://shelly-api-docs.shelly.cloud/gen2/ComponentsAndServices/Shelly/) — `Shelly.CheckForUpdate` / `Shelly.Update`
- [home-assistant/frontend#28755](https://github.com/home-assistant/frontend/issues/28755) — built-in browser WebView rendering issue (XL)
- [home-assistant/core#162665](https://github.com/home-assistant/core/issues/162665) — HA integration compatibility
- [RapierXbox/ShellyElevate](https://github.com/RapierXbox/ShellyElevate) — community project: HA on Wall Display without root
- [Pen Test Partners — Shelly BLE disclosure](https://www.pentestpartners.com/) — BLE RPC auth issue (fixed 2.6.0/2.7.0)
