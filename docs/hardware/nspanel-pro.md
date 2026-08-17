# Sonoff NSPanel Pro (Rockchip PX30 / rk3326)

The original NSPanel Pro is a small **480×480 square** PX30 panel with a built-in **Zigbee 3.0 coordinator**, no NFC/IR, and the lowest-power CPU of the panels documented here. Its **86P** and **120P** variants use different displays and boards — see [Variants](#variants--86p-vs-120p). This page was reverse-engineered primarily on a live **86P** (Android 8.1, rooted, toolbox `su`) and covers those original variants unless it explicitly says otherwise.

> [!TIP]
> Most-needed facts: ships **`userdebug` with no adb password** (`adb root` just works); **LED is not characterised** (no controllable RGB node found); light + proximity are **app-direct**; the on-board **EFR32 Zigbee radio** is managed over a local broker, not by reflashing. Update the **WebView first** — see [WebView — update this first](#webview--update-this-first).

| | |
|---|---|
| SoC | Rockchip **PX30 / rk3326** |
| CPU | 4× **Cortex-A35** @ up to **1.512 GHz** (idles at 408 MHz) |
| GPU | **Mali-G31** (device-confirmed) |
| Display | **480×480 square** (1:1), ~4", 160 dpi (mdpi, well-matched to ~170 physical ppi), 60 Hz → a **480×480 dp** canvas |
| RAM | **2 GB** (≈1960 MB usable) |
| Storage | eMMC; `/data` ≈ 3.5 GB |
| Android | 8.1 (API 27) |
| ABI | arm64-v8a |
| Radios | **Zigbee 3.0** (Silicon Labs EFR32 coordinator on UART `ttyS5` — see below), Wi-Fi, Bluetooth. No NFC, IR, ethernet, cellular. |

> [!NOTE]
> The Cortex-A35 is an efficiency core with markedly lower per-clock throughput than the A55 (TPA10) or A72 (WF1589T). Combined with 2 GB RAM, the NSPanel Pro is the **entry-level performer** of the three panels documented here — see the [performance comparison](README.md#performance-comparison--practical-deployment).

> [!TIP]
> Changing firmware on a button-less panel? Read [Firmware backup & restore](../firmware-backup-restore.md) first — the NSPanel Pro (PX30) uses [seaky's roottool/tools](../firmware-backup-restore.md#per-panel-notes) rather than `rkdeveloptool`.

## Variants — 86P vs 120P

The original NSPanel Pro line ships in two physically different panels, named for the EU **86 mm** vs **120 mm** wall box. The spec table above and most of this page were captured on an **86P**; the **120P** is a different board:

| | NSPanel Pro **86P** | NSPanel Pro **120P** |
|---|---|---|
| SoC | Rockchip **PX30** | Rockchip **RK3326-S** (same PX30/RK3326 family; `ro.board.platform=rk3326`, device-tree `rockchip,px30`) |
| Display | **480×480** square, ~160 dpi, portrait-only | **750×1334** portrait, **240 dpi** (override 250); landscape available; ~1 cm narrower + longer than the 86P |
| Build ids | both report `ro.product.model/device/name = px30_evb` (shared Rockchip board name — *not* a reliable variant discriminator) | as 86P |
| `ro.product.version` | `s6_android_x.y.z`-class | `NSPanelXXXP_x.y.z` (OTA channel `nspanel-pro-ver120`, full ROM `SN_3326S_750X1334_…`) |
| OTA form | full ROM through **4.0.12**; releases indexed after it ship as diffs or APK-only (see the [firmware index](nspanel-pro-firmware.md)) | as 86P |
| Proximity firmware | **4.0.12 restored ranged** readings | stayed **binary** at 4.x (per-model kernel divergence — see [Sensors](#sensors--light--proximity-are-app-direct)) |

Both share the EFR32 Zigbee radio, Android 8.1 (AOSP), arm64-v8a, and the root/recovery story below. Live-verified on a 120P (fw `NSPanel120P_3.7.1`): `wm size`=750×1334, density 240, `ro.board.platform=rk3326`.

> [!NOTE]
> This page does not crown a firmware version in prose — the generated [complete index](nspanel-pro-firmware-archive.md) is the authority, and it goes stale less often. The flashing procedure is hardware-verified through **4.4.0**; releases indexed past that are CDN-verified only, never live-flash verified here. As of 2026-08-14, for the most recently added of them no vendor documentation was found: [Sonoff's public changelog](https://sonoff.tech/en-us/blogs/news/sonoff-nspanel-pro-version-update-information-and-faq) documents up to **4.6.0**, 4.6.2 and 4.8.0 were located only by probing the CDN, and 4.7.0 is discussed only in an [eWeLink user feedback thread](https://forum.ewelink.cc/t/nspanel-pro-v4-7-0-feeback/208789) — a discussion thread, not a release announcement. The **4.5.3** release is a ROM diff on 120P but an APK-only update on 86P, and **4.6.2** is an app-only update with no ROM diff on either channel, so an upgrade is not always a single hop. Absence from the index means not-found-by-probe; the CDN cannot be listed, so it is never proof a build does not exist. The CoolKit CDN scheme and the full flashing how-to are on the [firmware & flashing page](nspanel-pro-firmware.md); every verified OTA URL is in the [complete index](nspanel-pro-firmware-archive.md), and the community-facing subset is the Discussion linked from there, which is regenerated from this repo's data files and can lag them.
>
> **⚠ Community reports describe restart loops on 4.5.1 / 4.5.2** (~10–60 min intervals on both models). For 4.7.0, the user feedback thread contains reports of sub-device connectivity trouble after updating, some resolved by a reboot and others described as continuing; this project has not reproduced or quantified them, so treat them as unverified user reports rather than a known regression. Verify any newer release on one panel before deploying widely; **4.0.12** remains the conservative full-ROM checkpoint to pin. The firmware Discussion carries the current community evidence, regenerated from this repo's index whenever the scheduled monitor next runs.

### Firmware quirks by version

Behaviour that changes across eWeLink firmware versions, oldest first. `ro.product.version` is the **internal** id (`s6_android_x.y.z` on the 86P / `NSPanelXXXP_x.y.z` on the 120P) — *not* the marketing/OTA number the eWeLink app shows (4.0.12, 4.5.x). Detection and any version-keyed logic must read `ro.product.version`, not the marketing string.

| Firmware | Quirk / behaviour | Impact — what to do |
|---|---|---|
| **older (pre-1.3.2)** | No in-app adb toggle; developer options unreachable from the UI | Enable adb via the internal **OTG port** (open the case) — [Gaining adb + root](#gaining-adb--root-access). |
| **v1.3.2+** | adb enable moved into the eWeLink app | eWeLink → *Device Settings* → tap **Device ID ×8** → developer mode → adb. |
| **v1.4+** | Developer mode **removed** from the UI | Enable adb via the **5× power-cycle** at the Sonoff boot animation — [Gaining adb + root](#gaining-adb--root-access). |
| **3.5.1 (86P, verified)** | Stock system WebView is **Chromium 107.0.5304.105** — far too old to render a modern HA dashboard; other firmware may differ | Check and update the WebView **first** — [WebView — update this first](#webview--update-this-first). ha-paneld's panel-health banner also flags outdated versions (min Chromium 110). |
| **v3.7.1** (120P, live) | Baseline reference build | `wm size`=750×1334, density 240, `ro.board.platform=rk3326`. |
| **v4.0.0** (roll-out 2025-09-19) | Stock firmware **bundles F-Droid** + promotes FOSS/HA app install; markedly faster UI | On-device install path opens — [Firmware v4.0.0](#firmware-v400--official-f-droid-app-install). Confirm **APP** *and* **OS** version both read ≥ 4.0.0. |
| **v4.0.12** | Proximity **ranged readings** restored on **86P**; **120P stays binary** (per-model kernel divergence) | Recommended stable pin for HA-only panels. The panel's raw input shape is model- and firmware-specific, but ha-paneld learns and normalizes either form — see [Sensors](#sensors--light--proximity-are-app-direct). |
| **v4.5.1 / v4.5.2** | **Widespread community restart-loop reports** (~10–60 min, both models); 4.5.2 is an APK-only layer on 4.5.1 | **Superseded by later releases.** Pin at **4.0.12** for maximum stability, or test a newer release on one panel first. Check the firmware Discussion for current evidence. |
| **v4.5.3** | Matter auto-discovery and screen-management optimizations; ROM diff on 120P but APK-only on 86P | No 4.5.3-specific restart-loop evidence found; superseded by later releases. |
| **v4.6.0** (Jun 2026) | **Local Web Portal** (`nspanelpro.local` — LAN setup, MQTT Discovery export to HA, Matter Bridge); project CDN inspection found diffs from 4.0.12 / 4.4.0 / 4.5.1 | Documented in Sonoff's public changelog. **4.6.2** is indexed as an app-only update with no ROM diff on either channel, and no 4.6.1 has been found. |
| **v4.7.0** (Jul 2026) | Discussed in an eWeLink user feedback thread but **absent from Sonoff's public changelog**; covers Gen1 and Gen2 panels; users report added Basic gen-5 relay (BASIC-1GS) support; project CDN inspection found inbound diffs from 4.0.12 / 4.4.0 / 4.5.1 / 4.6.0 on both models, plus 4.5.3 on the 120P only | Community reports of sub-device connectivity trouble, some reboot-resolved and some described as continuing; unverified by this project. Verify on one panel before deploying widely. |
| **v4.8.0** (Aug 2026) | **No release announcement and no changelog found** — located by probing the CDN. An eWeLink staff post on 2026-07-16 in the [roadmap thread](https://forum.ewelink.cc/t/nspanel-pro-roadmap-and-co-created-future/206240) scheduled it for August and confirmed one feature for it: an option to auto-update the panel through the eWeLink app. Project CDN inspection found inbound diffs from 4.0.12 / 4.4.0 / 4.5.1 / 4.6.0 / 4.7.0 on both models, plus 4.5.3 on the 120P only | Contents otherwise unknown, and no community feedback thread has been found, so there is no report either way on stability. Treat it as unassessed rather than clean. **If the auto-update option ships enabled, a panel could take firmware unattended** — check that setting before relying on a pinned version. |

> [!NOTE]
> These are original 86P/120P quirks. The NSPanel Pro **Gen2** (RK3326-**S**, dual relays, EFR32**MG24**) is a different hardware target. Sonoff ships Gen1 and Gen2 on the same firmware version line (4.7.0 covers both), so do not infer a separate firmware line or assume every original-model note carries over.

Sibling Tuya-family boards — **S6E/T6E** (relay variants; S6E = T6E + 2 relays), [**S9E**](s9e.md) (Smatek), [**TPA10**](tpa10.md) (RK3566, Cortex-A55, Android 11) — are separate targets, not NSPanel Pro firmware.

> [!CAUTION]
> Detection can't rely on `ro.product.model` (both are `px30_evb`). Use `ro.product.version` / display metrics / `ro.board.platform` to tell 86P from 120P. Proximity behavior also differs between models and firmware, so ha-paneld learns from the live readings instead of selecting a firmware-specific classifier.

## Gaining adb + root access

Unlike the TPA10, the NSPanel Pro has **no adb password** — it ships as a `userdebug` / test-keys build (`ro.debuggable=1`), so `adb root` works and `/system` is remountable. The hard part is only *reaching* developer options, which the eWeLink firmware hides differently per version. Distilled from blakadder's guides ([sideload](https://blakadder.com/nspanel-pro-sideload/), [secrets](https://blakadder.com/nspanel-pro-secrets/)).

**1. Enable adb** — the route depends on firmware:

- **Older firmware** — open the case (back screws, disconnect the touch connector) to expose the OTG USB port and connect a host; adb works directly over USB.
- **Firmware v1.3.2+** — in the **eWeLink app** → the panel's *Device Settings*, tap the **Device ID 8×** to enable developer mode, which restores adb.
- **Firmware v1.4+** (developer mode removed) — power-cycle the panel **5×** during the Sonoff boot animation to force a recovery boot, and in that window `adb install ultra-small-launcher.apk`; after reboot set that launcher as default, then *Settings → System → About tablet → Build number* ×7 to re-enable developer options and turn on USB debugging.

**2. Go to network adb** (so you don't need the case open):

```bash
adb tcpip 5555
adb shell ip -o a            # find the panel IP
adb connect <panel-ip>:5555
adb shell su 0 setprop persist.adb.tcp.port 5555   # survive reboot (service prop resets)
```

**3. Root.** Because the build is `userdebug`, `adb root` gives a root adbd shell immediately. ha-paneld calls `su` from the app sandbox, so install a persistent `su` into `/system` (this fleet's panels carry **SuperSU `su` 2.76** at `/system/xbin/su`):

```bash
adb root
adb disable-verity          # only if remount is refused; this reboots the panel
adb remount                 # or: adb shell mount -o remount,rw /system
adb push su /system/xbin/su
adb shell chmod 06755 /system/xbin/su
```

> [!CAUTION]
> Disable the eWeLink apps (`com.eWeLinkNSPro.dev`, `com.eWeLinkControlPanel`) only **after** adb + `su` are solid and you have a home/back alternative — ha-paneld's nav actions cover the latter. Note the eWeLink **Zigbee gateway** stack is independent of these apps and keeps running; manage it with ha-paneld's [Zigbee router switch](#zigbee-gateway) rather than removing it.

## Firmware v4.0.0 — official F-Droid app install

From **v4.0.0** (phased roll-out from 19 September 2025) the stock eWeLink firmware **officially bundles [F-Droid](https://f-droid.org/)** and promotes installing FOSS apps on the panel — Home Assistant's own Companion app is the headline example. Update via the panel (top drop-down → *Settings → About → Software update*) or the eWeLink app, then confirm both **APP Version** and **OS Version** read ≥ 4.0.0. Sonoff states F-Droid apps "will not affect NSPanel Pro's original features" (existing setups/automations stay intact) and that an app's F-Droid build "may differ slightly from the latest release". The update also markedly speeds up screen-swipe/UI responsiveness. Source: [Sonoff — NSPanel Pro V4.0.0 update](https://sonoff.tech/en-us/blogs/news/nspanel-pro-v4-0-0-update-now-supports-f-droid-and-home-assistant-app-install).

> [!NOTE]
> **Why this matters for ha-paneld.** F-Droid is a sanctioned, **on-device** install channel, so an APK can reach a panel with **no PC/adb** and F-Droid handles update notifications. **But F-Droid solves distribution, not privilege:** the headline features (overlay navbar, screen on/off, relays, button LEDs, Zigbee control) still need `su`, so the adb/root setup above stays a prerequisite for full function — only the non-privileged surface (MQTT discovery, sensors, brightness, HTTP UI) works on a stock unrooted panel.

## WebView — update this first

An 86P freshly flashed to firmware `3.5.1` (build `164637`) was verified with `com.android.webview` **107.0.5304.105** (Chromium 107), which is too old to render a current Home Assistant dashboard. Other firmware and models may differ, so check the installed provider before deciding whether to update. The archived OTA diff packages do not include a WebView APK, so this version was read from the live unit with `dumpsys webviewupdate`. That unit runs Chromium **138** after a clean adb update. See [Updating the system WebView](README.md#updating-the-system-webview).

## LED

No `/sys/class/leds` RGB node and no `/dev/ledjni` were found on this unit, so there is **no app/sysfs-controllable RGB LED characterised** on the NSPanel Pro (contrast the TPA10's `avsux` node and the WF1589T's `/dev/ledjni`). Screen brightness/backlight use the standard Android paths.

## Sensors — light + proximity are app-direct

Unlike the TPA10 (where light/temp are root-only), the NSPanel Pro exposes its Sensortek combo through standard `SensorManager`: `android.sensor.light`, `android.sensor.proximity`, and `android.sensor.accelerometer` — all readable by a normal app, no root. ha-paneld reads light + proximity here directly. No temperature/humidity sensor is fitted.

> [!NOTE]
> **Proximity readings are firmware- AND model-dependent.** The sensor is a Sensortek STK3A5x ToF in a top-PCB cutout behind the cover glass. The per-unit rest baseline varies widely (one unit ~1000, another ~4000); only the *relative* change matters, so a high idle baseline is normal, not a fault. Up to ~fw **3.3** it reports a ranged reading (~50 ms cadence); from ~**3.3–3.4** the kernel driver switched it to binary 0/1. **4.0.12 restored ranged readings on the 86P only** — the **120P stayed binary**. ha-paneld handles both from live behavior and normalizes the useful range across the fleet; profiles no longer encode per-firmware thresholds or ranged/binary classifiers. (Sources: seaky tools #142/#144/#171/#262.)

<details>
<summary>Bound i2c devices (real hardware)</summary>

| i2c addr | driver / name | What it is |
|---|---|---|
| `0-0020` | `rk809` | PMIC |
| `1-001a` / `1-005a` | `CST226` / `CST226SE` | Hynitron capacitive touch controller |
| `2-003c` | `tp` | touch panel |
| `2-0046` | `ls_stk3a5x` + `ps_stk3a5x` | Sensortek **STK3A5x** ambient-light + proximity combo |
| `2-0047` | `ls_stk3x3x` + `ps_stk3x3x` | Sensortek **STK3x3x** light + proximity (alt variant) |

</details>

## Zigbee gateway

The NSPanel Pro has a built-in **Silicon Labs EFR32 Zigbee 3.0 radio** on UART `/dev/ttyS5`, driven by a manufacturer host stack (`/vendor/bin/siliconlabs_host/zgateway`) over a local MQTT broker — the same stack the eWeLink apps use, so the panel ships as an eWeLink Zigbee hub.

ha-paneld manages it directly (v0.6.1+): `switch.<panel>_zigbee_router` turns the panel into a Zigbee **router/repeater** that extends your existing mesh (it starts the gateway and ensures the Repeater role), and turns it back off again (stopping the gateway, freeing the radio). It works over the local broker — credential-free, no `ttyS5` handling. The panel then appears as a normal router in your ZHA / Zigbee2MQTT coordinator.

> [!NOTE]
> Switching role is **not a reflash** — there is no `.gbl`/bootloader step; it just sets the EZSP node type. For partition-level firmware work see [Firmware backup & restore](../firmware-backup-restore.md).

> [!NOTE]
> **No Thread Border Router is documented or characterised.** The installed vendor stack uses the EFR32 as a Zigbee NCP. Although the EFR32MG21 silicon is multiprotocol-capable, that does not establish Thread firmware or a border-router implementation on the panel; Sonoff documents a Matter Bridge instead.
>
> **4.x reworked the Zigbee stack** — community inspection found a forked Zigbee2MQTT, a changed on-device MQTT password and a different boot sequence. [Sonoff documents coordinator↔router switching](https://sonoff.tech/blogs/news/nspanel-pro-v4-3-0-central-heating-redefining-whole-home-temperature-automation) in current firmware, but ha-paneld's private local-broker control path was built against ≤3.x and **may need adapting on 4.x**. (Community sources: seaky tools #244/#241/#255 and roottool#3.)

> [!WARNING]
> **A legacy vendor-native Zigbee-watchdog defect is confirmed by the reporter on NSPanel Pro 120 stock 3.8.0.** Firmware containing the recursive `LD_LIBRARY_PATH` assignment described in [Issue #34](https://github.com/maxlyth/ha-paneld/issues/34) can eventually make every external command launched by the watchdog fail with `E2BIG`, consume one CPU core and stop recovering a dead `zgateway`. A reboot resets the problem only temporarily. See [Performance tuning](../performance.md#rule-out-the-legacy-stock-nspanel-pro-zigbee-watchdog-defect) for the evidence boundary and repair-safety requirements. The reporter-provided workaround has not yet been independently validated by the project. Community inspection of 4.0.12 and 4.6.0 did not find the vulnerable assignment.

### Requirements — firmware ≥ v2.2.0

The host stack is the **manufacturer's own** (eWeLink/Sonoff) gateway, versioned to match the panel firmware (e.g. `sonoff-v3.5.4`). Zigbee **router mode** was added in **NSPanel Pro firmware v2.2.0** (2023 — eWeLink app → *Device Settings → Pilot Features → Zigbee Mode*); local host-stack repeater support landed in gateway package v1.1.9. In practice:

- **Gateway present** (firmware ≥ v2.2.0, or side-loaded) → ha-paneld detects it and publishes `switch.<panel>_zigbee_router`. Toggle ON and the panel joins your coordinator as a router.
- **No gateway** (very old firmware, never provisioned) → the switch **doesn't appear** — it's gated on the gateway's launch script existing. Update firmware (≥ v2.2.0), or see migration below.

ha-paneld **drives** the gateway; it doesn't ship or install it (it's eWeLink's binary). Recent firmware (4.x) adds a Matter bridge and can export Zigbee devices to Home Assistant through MQTT Discovery — alternatives to the router role.

### Gateway health and automatic containment

On a Zigbee-capable panel, `sensor.<panel>_zigbee_gateway_health` reports the vendor stack independently of the router switch. This means an unconfigured stock gateway is still visible without granting ha-paneld permission to stop it.

When the router switch has explicitly been turned ON, ha-paneld allows a 15-minute startup and pairing grace, then watches once per minute for two runaway signatures: an explicitly invalid/unjoined network combined with more than 50% of one CPU core for five consecutive samples, or at least three gateway PID changes within ten minutes. A joined router with sustained high CPU is warning-only and remains running. Unknown 4.x layouts or missing firmware-specific join evidence fail safe to `unknown`.

Turning the Zigbee router switch ON explicitly requests Repeater mode even when the vendor gateway process is already running, so an ON command sent while your ZHA/Zigbee2MQTT coordinator permits joining acts as a fresh join retry without spawning a second gateway supervisor.

The Configure tab shows a **Request join** action directly beneath the Zigbee router switch. The existing switch remains the only on/off control. Enable permit-join on ZHA/Zigbee2MQTT, then request joining and confirm that permit-join is open. The action reasserts Repeater mode, starts a fresh 15-minute grace and polls the bounded health status; it does not reboot or restart the panel. The button is unavailable while the router is disabled, already joined or cooling down after a recent request.

After the pairing grace, an enabled gateway that is still unjoined produces a persistent dashboard, Install-tab and status-API warning linked to that Configure action. Do not leave it in that state: repeated join retries can consume substantial CPU. Either join the panel as a router or turn off the Zigbee router switch.

If a configured legacy gateway meets a runaway rule, ha-paneld persists the router switch OFF and attempts one bounded containment. Vendor-native containment can target only the Sonoff guard, `zgateway`, and the matching local broker. If a process cannot be stopped, the respawner is removed where possible and surviving gateway work is demoted to nice 19 and Android's background cpuset. Turning the router switch ON later explicitly starts one fresh grace period and retry.

The health attributes include firmware/product version, gateway layout/package version, joined/role status, rounded gateway and guard CPU, recent restart count and containment result. They never include the Zigbee network key, raw local-broker credentials, radio MAC or raw gateway `netinfo`.

### Migrating from NSPanelTools

[NSPanelTools (NSPPT)](https://github.com/seaky/nspanel_pro_tools_apk) side-loads the official Sonoff gateway package onto firmware that didn't ship it; many users run it today. ha-paneld coexists and can take over the gateway:

- **Side-by-side is fine.** ha-paneld's router control is idempotent — it **defers** to whatever already runs the gateway (won't double-start or fight NSPPT); auto-brightness is opt-in/off. Nothing conflicts by default.
- **Handing the gateway to ha-paneld:** the host stack lives in `/vendor` and **survives uninstalling the NSPPT app** (verified — a persistent hook even keeps boot-starting it). Remove the NSPPT APK and ha-paneld keeps driving the gateway; if the boot hook is also stripped, ha-paneld's boot-restore starts it when the switch was left ON.

> [!NOTE]
> Both tools touch the screen/sensors. Coexistence is benign today, but enabling overlapping features (e.g. wake-on-wave alongside an NSPPT equivalent) can cause redundant actions — remove NSPPT once ha-paneld covers your needs.

<details>
<summary>EZSP host stack internals (broker topics, supervisor, role persistence)</summary>

On the legacy vendor-native ≤3.x stack, the radio runs **EZSP NCP firmware** (EFR32MG21, EZSP v8); `zgateway` is an EZSP *host* binary in `/vendor/bin/siliconlabs_host/`, kept alive by its own `guard_process.sh` supervisor (a 5-second loop, boot-started) and controlled over a **local mosquitto broker** on `127.0.0.1:1883` (anonymous — the `password_file` line is commented out in `mosquitto.conf`). The 4.x stack differs as described above.

- role status: `zigbee/system/network-role/information` → `{"role":"Repeater"|"Coordinator"}`
- role switch: `zigbee/system/network-role/switch` ← `{"role":"Repeater"}`

"Repeater" is router mode (extends an existing mesh — the supported sweet spot); the role persists in the NCP's NVM. The vendor `zgateway` survives removal of the eWeLink *apps* (it lives in `/vendor`, not in an APK).

For a full standalone Zigbee2MQTT/ZHA coordinator *on the panel* instead, see [seaky/nspanel_pro_zigbee](https://github.com/seaky/nspanel_pro_zigbee), which swaps the host stack (heavier; not what ha-paneld does).

</details>

## Access model summary

- **Light / proximity / accelerometer**: app-direct (`SensorManager`).
- **Screen brightness / sleep / navigate / TTS**: standard Android paths (`su` for true backlight-off).
- **LED**: none characterised.
- **Zigbee**: EFR32 radio managed via the on-device gateway's local broker (`switch.<panel>_zigbee_router`).
- **Radios**: Zigbee 3.0 + Wi-Fi/BT.

## Performance expectations

The NSPanel Pro is **CPU- and RAM-constrained** for rich dashboards:

- Idle it sits at 408 MHz with ≈500 MB RAM in use; a heavy Lovelace dashboard pushes both hard.
- **2 GB RAM is the binding constraint** — the dashboard WebView, Android and background apps compete for it; large dashboards with many cards, big images, long history graphs or expensive custom cards cause WebView reloads and jank.
- The A35 cores make page transitions and animations visibly slower than on A55/A72 panels.

When using ha-paneld's built-in renderer, start with the [automatic dashboard entity filter](../performance.md#1-filter-the-built-in-renderers-entity-subscription) so the panel does not process states its dashboard never displays. Then use the performance cards on the Dashboard tab to identify remaining heavy views, memory pressure or thermal limits before simplifying the dashboard. The filter is not available to the Companion renderer, where source-side update tuning and a leaner dashboard remain the supported options.

---

See the [panel hardware index](README.md) for the cross-panel comparison and method, and the [TPA10](tpa10.md) / [WF1589T](wf1589t.md) / [S9E](s9e.md) references for the other panels.
