# Sonoff NSPanel Pro (Rockchip PX30 / rk3326)

The most common Home-Assistant wall panel on the market: a small **480×480 square** PX30 panel with a
built-in **Zigbee 3.0 coordinator**, no NFC/IR, and the lowest-power CPU of the panels documented here.
It ships in two physically different sizes — the **86P** (the unit specced below) and the larger **120P**,
which is a *different panel* (RK3326-S, 750×1334 portrait) — see [Variants](#variants--86p-vs-120p).
Reverse-engineered on a live **86P** (Android 8.1, rooted, toolbox `su`) on 2026-06-05.

> [!TIP]
> Most-needed facts: ships **`userdebug` with no adb password** (`adb root` just works); **LED is not
> characterised** (no controllable RGB node found); light + proximity are **app-direct**; the on-board
> **EFR32 Zigbee radio** is managed over a local broker, not by reflashing. Update the **WebView first**
> — see [WebView — update this first](#webview--update-this-first).

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
> The Cortex-A35 is an efficiency core with markedly lower per-clock throughput than the A55 (TPA10)
> or A72 (WF1589T). Combined with 2 GB RAM, the NSPanel Pro is the **entry-level performer** of the
> three panels documented here — see the [performance comparison](README.md#performance-comparison--practical-deployment).

> [!TIP]
> Changing firmware on a button-less panel? Read [Firmware backup & restore](../firmware-backup-restore.md)
> first — the NSPanel Pro (PX30) uses [seaky's roottool/tools](../firmware-backup-restore.md#per-panel-notes)
> rather than `rkdeveloptool`.

## Variants — 86P vs 120P

"NSPanel Pro" ships in two physically different panels, named for the EU **86 mm** vs **120 mm** wall box.
The spec table above and most of this page were captured on an **86P**; the **120P** is a different board:

| | NSPanel Pro **86P** | NSPanel Pro **120P** |
|---|---|---|
| SoC | Rockchip **PX30** | Rockchip **RK3326-S** (same PX30/RK3326 family; `ro.board.platform=rk3326`, device-tree `rockchip,px30`) |
| Display | **480×480** square, ~160 dpi, portrait-only | **750×1334** portrait, **240 dpi** (override 250); landscape available; ~1 cm narrower + longer than the 86P |
| Build ids | both report `ro.product.model/device/name = px30_evb` (shared Rockchip board name — *not* a reliable variant discriminator) | as 86P |
| `ro.product.version` | `s6_android_x.y.z`-class | `NSPanelXXXP_x.y.z` (OTA channel `nspanel-pro-ver120`, full ROM `SN_3326S_750X1334_…`) |
| OTA latest | **4.0.12** (full) → **4.6.0** (diff, current Stable) | **4.0.12** (full) → **4.6.0** (diff, current Stable) |
| Proximity firmware | **4.0.12 restored graded** proximity | stayed **binary** at 4.x (per-model kernel divergence — see [Sensors](#sensors--light--proximity-are-app-direct)) |

Both share the EFR32 Zigbee radio, Android 8.1 (AOSP), arm64-v8a, and the root/recovery story below.
Live-verified on a 120P (fw `NSPanel120P_3.7.1`): `wm size`=750×1334, density 240, `ro.board.platform=rk3326`.

> [!NOTE]
> Both models reach the current ROM (**4.6.0**, the June 2026 official *Stable*) via a 2-step path: 4.0.12 full ROM → 4.6.0 diff. (**4.5.2** was an APK-only layer on the now-superseded 4.5.1.) The CoolKit CDN scheme, every verified OTA URL, and the full flashing how-to are on the [firmware & flashing page](nspanel-pro-firmware.md); the live, community-maintained version index is the Discussion linked from there.
>
> **⚠ 4.5.1 / 4.5.2 had widespread reboot-loop reports** (~10–60 min intervals on both models); **4.6.0** (June 2026) is the current official *Stable* that supersedes them. 4.6.0 is recent and not yet verified on our fleet, so try it on one panel before a fleet roll; **4.0.12** remains the conservative full-ROM checkpoint to pin for maximum stability. Check the firmware Discussion for the current consensus.

### Firmware quirks by version

Behaviour that changes across eWeLink firmware versions, newest-relevant first. `ro.product.version`
is the **internal** id (`s6_android_x.y.z` on the 86P / `NSPanelXXXP_x.y.z` on the 120P) — *not* the
marketing/OTA number the eWeLink app shows (4.0.12, 4.5.x). Detection and any version-keyed logic must
read `ro.product.version`, not the marketing string.

| Firmware | Quirk / behaviour | Impact — what to do |
|---|---|---|
| **all shipping** | Stock system WebView is **Chromium 107.0.5304.105** (verified on fw 3.5.1) — far too old to render a modern HA dashboard | Update the WebView **first** — [WebView — update this first](#webview--update-this-first). ha-paneld's panel-health banner also flags this (min Chromium 110). |
| **older (pre-1.3.2)** | No in-app adb toggle; developer options unreachable from the UI | Enable adb via the internal **OTG port** (open the case) — [Gaining adb + root](#gaining-adb--root-access). |
| **v1.3.2+** | adb enable moved into the eWeLink app | eWeLink → *Device Settings* → tap **Device ID ×8** → developer mode → adb. |
| **v1.4+** | Developer mode **removed** from the UI | Enable adb via the **5× power-cycle** at the Sonoff boot animation — [Gaining adb + root](#gaining-adb--root-access). |
| **v3.7.1** (120P, live) | Baseline reference build | `wm size`=750×1334, density 240, `ro.board.platform=rk3326`. |
| **v4.0.0** (roll-out 2025-09-19) | Stock firmware **bundles F-Droid** + promotes FOSS/HA app install; markedly faster UI | On-device install path opens — [Firmware v4.0.0](#firmware-v400--official-f-droid-app-install). Confirm **APP** *and* **OS** version both read ≥ 4.0.0. |
| **v4.0.12** | Proximity **graded** restored on **86P**; **120P stays binary** (per-model kernel divergence) | Recommended stable pin for HA-only panels. Graded/binary is model-**and**-firmware specific — see [Sensors](#sensors--light--proximity-are-app-direct). |
| **v4.5.1 / v4.5.2** | **Widespread reboot-loop reports** (~10–60 min, both models); 4.5.2 is an APK-only layer on 4.5.1 | **Superseded by v4.6.0.** Pin at **4.0.12** for maximum stability, or move to 4.6.0 (verify on one panel first). Check the firmware Discussion for current consensus. |
| **v4.6.0** (Jun 2026) | Current official **Stable**; **Local Web Portal** (`nspanelpro.local` — LAN setup, MQTT→HA sync, Matter Bridge); diff-only off 4.0.12 / 4.4.0 / 4.5.1 | New — not yet fleet-verified here. The stable successor to the reboot-loopy 4.5.x; still verify on one panel before a fleet roll. |

> [!NOTE]
> These are **Gen1** (86P/120P) quirks. The NSPanel Pro **Gen2** (RK3326-**S**, dual relays,
> EFR32**MG24**) is a different target with its own firmware line — do not assume Gen1 firmware notes
> carry over.

Sibling Tuya-family boards — **S6E/T6E** (relay variants; S6E = T6E + 2 relays), [**S9E**](s9e.md) (Smatek),
[**TPA10**](tpa10.md) (rk3326-class, A53, Android 11) — are separate targets, not NSPanel Pro firmware.

> [!CAUTION]
> Detection can't rely on `ro.product.model` (both are `px30_evb`). Use `ro.product.version` / display
> metrics / `ro.board.platform` to tell 86P from 120P. The **proximity graded-vs-binary rule is per-model
> AND per-firmware** — a single global cutover is wrong (see Sensors).

## Gaining adb + root access

Unlike the TPA10, the NSPanel Pro has **no adb password** — it ships as a `userdebug` / test-keys
build (`ro.debuggable=1`), so `adb root` works and `/system` is remountable. The hard part is only
*reaching* developer options, which the eWeLink firmware hides differently per version. Distilled from
blakadder's guides ([sideload](https://blakadder.com/nspanel-pro-sideload/),
[secrets](https://blakadder.com/nspanel-pro-secrets/)).

**1. Enable adb** — the route depends on firmware:

- **Older firmware** — open the case (back screws, disconnect the touch connector) to expose the OTG
  USB port and connect a host; adb works directly over USB.
- **Firmware v1.3.2+** — in the **eWeLink app** → the panel's *Device Settings*, tap the **Device ID
  8×** to enable developer mode, which restores adb.
- **Firmware v1.4+** (developer mode removed) — power-cycle the panel **5×** during the Sonoff boot
  animation to force a recovery boot, and in that window `adb install ultra-small-launcher.apk`; after
  reboot set that launcher as default, then *Settings → System → About tablet → Build number* ×7 to
  re-enable developer options and turn on USB debugging.

**2. Go to network adb** (so you don't need the case open):

```bash
adb tcpip 5555
adb shell ip -o a            # find the panel IP
adb connect <panel-ip>:5555
adb shell su 0 setprop persist.adb.tcp.port 5555   # survive reboot (service prop resets)
```

**3. Root.** Because the build is `userdebug`, `adb root` gives a root adbd shell immediately. ha-paneld
calls `su` from the app sandbox, so install a persistent `su` into `/system` (this fleet's panels carry
**SuperSU `su` 2.76** at `/system/xbin/su`):

```bash
adb root
adb disable-verity          # only if remount is refused; this reboots the panel
adb remount                 # or: adb shell mount -o remount,rw /system
adb push su /system/xbin/su
adb shell chmod 06755 /system/xbin/su
```

> [!CAUTION]
> Disable the eWeLink apps (`com.eWeLinkNSPro.dev`, `com.eWeLinkControlPanel`) only **after** adb +
> `su` are solid and you have a home/back alternative — ha-paneld's nav actions cover the latter.
> Note the eWeLink **Zigbee gateway** stack is independent of these apps and keeps running; manage it
> with ha-paneld's [Zigbee router switch](#zigbee-gateway) rather than removing it.

## Firmware v4.0.0 — official F-Droid app install

From **v4.0.0** (phased roll-out from 19 September 2025) the stock eWeLink firmware **officially bundles
[F-Droid](https://f-droid.org/)** and promotes installing FOSS apps on the panel — Home Assistant's own
Companion app is the headline example. Update via the panel (top drop-down → *Settings → About → Software
update*) or the eWeLink app, then confirm both **APP Version** and **OS Version** read ≥ 4.0.0. Sonoff
states F-Droid apps "will not affect NSPanel Pro's original features" (existing setups/automations stay
intact) and that an app's F-Droid build "may differ slightly from the latest release"; the update also
markedly speeds up screen-swipe/UI responsiveness. Source:
[Sonoff — NSPanel Pro V4.0.0 update](https://sonoff.tech/en-us/blogs/news/nspanel-pro-v4-0-0-update-now-supports-f-droid-and-home-assistant-app-install).

> [!NOTE]
> **Why this matters for ha-paneld.** F-Droid is a sanctioned, **on-device** install channel, so an APK
> can reach a panel with **no PC/adb** and F-Droid handles update notifications. ha-paneld could be
> distributed either through the **f-droid.org main repo** or — more practically — our **own F-Droid
> repo** (signed with our release key, so it updates the GitHub-release builds in place). **But F-Droid
> solves distribution, not privilege:** the headline features (overlay navbar, screen on/off, relays,
> button LEDs, Zigbee control) still need `su`, so the adb/root setup above stays a prerequisite for full
> function — only the non-privileged surface (MQTT discovery, sensors, brightness, HTTP UI) works on a
> stock unrooted panel.
>
> **To confirm on a 4.0.0 unit:** whether the bundled F-Droid client allows **adding a custom repo URL**
> (needed for our own repo) or is locked to the official repo; whether 4.0.0 also relaxes arbitrary-APK
> "unknown sources" sideloading; and which models/SoC the 4.x line covers (86P / 120P — not stated in the
> post).

## WebView — update this first

The NSPanel Pro ships with a WebView/Chromium far too old to render a current Home Assistant dashboard (blank/broken UI in the HA Companion app). **Stock version (confirmed on a unit freshly flashed to firmware `3.5.1`, build `164637`): `com.android.webview` `107.0.5304.105`** (Chromium 107) — observed directly via `adb shell dumpsys webviewupdate | grep "Current WebView"` after a factory firmware install, since the OTA diff packages on Drive don't include a WebView APK. This is old enough that the HA frontend renders blank, so update it first. Update it cleanly over adb — no root, no F-Droid; this unit runs Chromium **138** afterwards. See [Updating the system WebView](README.md#updating-the-system-webview).

## LED

No `/sys/class/leds` RGB node and no `/dev/ledjni` were found on this unit, so there is **no
app/​sysfs-controllable RGB LED characterised** on the NSPanel Pro (contrast the TPA10's `avsux` node
and the WF1589T's `/dev/ledjni`). Screen brightness/backlight use the standard Android paths.

## Sensors — light + proximity are app-direct

Unlike the TPA10 (where light/temp are root-only), the NSPanel Pro exposes its Sensortek combo through
standard `SensorManager`: `android.sensor.light`, `android.sensor.proximity`, and
`android.sensor.accelerometer` — all readable by a normal app, no root. ha-paneld reads light +
proximity here directly. No temperature/humidity sensor is fitted.

> [!NOTE]
> **Proximity is firmware- AND model-dependent** (Sensortek STK3A5x ToF in a top-PCB cutout behind the
> cover glass; per-unit rest baseline varies widely — one unit ~1000, another ~4000 — only the *relative*
> change matters, so a high idle baseline is normal, not a fault). Up to ~fw **3.3** it reports a
> **graded** raw distance (~50 ms cadence); from ~**3.3–3.4** the kernel driver switched it to **binary
> 0/1** (the distance/threshold control is greyed out, not overridable). **4.0.12 restored graded on the
> 86P only** — the **120P stayed binary**. So a graded-vs-binary decision must be **per-model and
> per-firmware**, not a single global cutover. (Sources: seaky tools #142/#144/#171/#262.)

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

The NSPanel Pro has a built-in **Silicon Labs EFR32 Zigbee 3.0 radio** on UART `/dev/ttyS5`, driven by
a manufacturer host stack (`/vendor/bin/siliconlabs_host/zgateway`) over a local MQTT broker — the same
stack the eWeLink apps use, so the panel ships as an eWeLink Zigbee hub.

ha-paneld manages it directly (v0.6.1+): `switch.<panel>_zigbee_router` turns the panel into a Zigbee
**router/repeater** that extends your existing mesh (starts the gateway, ensures Repeater role) and back
off (stops it, freeing the radio) — over the local broker, credential-free, no `ttyS5` handling. The
panel then appears as a normal router in your ZHA / Zigbee2MQTT coordinator.

> [!NOTE]
> Switching role is **not a reflash** — there is no `.gbl`/bootloader step; it just sets the EZSP node
> type. For partition-level firmware work see [Firmware backup & restore](../firmware-backup-restore.md).

> [!NOTE]
> **Zigbee-only — no Thread.** The EFR32 (EZSP v8, stack 6.10.1) is a Zigbee radio; RK3326 (2018) has no
> 802.15.4 Thread radio, so despite 4.x firmware's "Matter Bridge" marketing there is **no native Thread
> border router** on these panels.
>
> **4.x reworked the Zigbee stack** — Sonoff shipped a forked Zigbee2MQTT (herdsman 23.53 vs upstream
> ~25.x), decommissioned the old NCP client, changed the on-device MQTT password, and altered the boot
> sequence. ha-paneld's `zigbee_router` was built against ≤3.x and **may need adapting on 4.x** — and
> Sonoff disabled coordinator↔router switching on stock 4.x firmware. Tracked for a future release.
> (Sources: seaky tools #244/#241/#255, roottool#3.)

### Requirements — firmware ≥ v2.2.0

The host stack is the **manufacturer's own** (eWeLink/Sonoff) gateway, versioned to match the panel
firmware (e.g. `sonoff-v3.5.4`). Zigbee **router mode** was added in **NSPanel Pro firmware v2.2.0**
(2023 — eWeLink app → *Device Settings → Pilot Features → Zigbee Mode*); local host-stack repeater
support landed in gateway package v1.1.9. In practice:

- **Gateway present** (firmware ≥ v2.2.0, or side-loaded) → ha-paneld detects it and publishes
  `switch.<panel>_zigbee_router`. Toggle ON and the panel joins your coordinator as a router.
- **No gateway** (very old firmware, never provisioned) → the switch **doesn't appear** — it's gated on
  the gateway's launch script existing. Update firmware (≥ v2.2.0), or see migration below.

ha-paneld **drives** the gateway; it doesn't ship or install it (it's eWeLink's binary). Recent firmware
(4.x) adds a Matter bridge + a direct HA Zigbee integration — alternatives to the router role.

### Migrating from NSPanelTools

[NSPanelTools (NSPPT)](https://github.com/seaky/nspanel_pro_tools_apk) side-loads the official Sonoff
gateway package onto firmware that didn't ship it; many users run it today. ha-paneld coexists and can
take over the gateway:

- **Side-by-side is fine.** ha-paneld's router control is idempotent — it **defers** to whatever already
  runs the gateway (won't double-start or fight NSPPT); auto-brightness is opt-in/off. Nothing conflicts
  by default.
- **Handing the gateway to ha-paneld:** the host stack lives in `/vendor` and **survives uninstalling
  the NSPPT app** (verified 2026-06-08 — a persistent hook even keeps boot-starting it). Remove the NSPPT
  APK and ha-paneld keeps driving the gateway; if the boot hook is also stripped, ha-paneld's
  boot-restore starts it when the switch was left ON.

> [!NOTE]
> Both tools touch the screen/sensors. Coexistence is benign today, but enabling overlapping features
> (e.g. wake-on-wave alongside an NSPPT equivalent) can cause redundant actions — remove NSPPT once
> ha-paneld covers your needs.

<details>
<summary>EZSP host stack internals (broker topics, supervisor, role persistence)</summary>

The radio runs **EZSP NCP firmware** (EFR32MG21, EZSP v8); `zgateway` is an EZSP *host* binary in
`/vendor/bin/siliconlabs_host/`, kept alive by its own `guard_process.sh` supervisor (a 5-second loop,
boot-started) and controlled over a **local mosquitto broker** on `127.0.0.1:1883` (anonymous — the
`password_file` line is commented out in `mosquitto.conf`):

- role status: `zigbee/system/network-role/information` → `{"role":"Repeater"|"Coordinator"}`
- role switch: `zigbee/system/network-role/switch` ← `{"role":"Repeater"}`

"Repeater" is router mode (extends an existing mesh — the supported sweet spot); the role persists in
the NCP's NVM. The vendor `zgateway` survives removal of the eWeLink *apps* (it lives in `/vendor`, not
in an APK).

For a full standalone Zigbee2MQTT/ZHA coordinator *on the panel* instead, see
[seaky/nspanel_pro_zigbee](https://github.com/seaky/nspanel_pro_zigbee), which swaps the host stack
(heavier; not what ha-paneld does).

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
- **2 GB RAM is the binding constraint** — the dashboard WebView, the HA Companion app, and Android
  itself compete for it; large dashboards (many cards, big images, long history graphs, heavy custom
  cards) cause WebView reloads and jank.
- The A35 cores make page transitions and animations visibly slower than on A55/A72 panels.

Mitigations (all covered by ha-paneld + [docs/performance.md](../performance.md)): keep dashboards
lean, prefer the split-instance approach to cut WebSocket event volume, and use ha-paneld's
instrumentation (CPU clock/throttling, responsiveness, top-5 processes, 1-click WebView DevTools
relay) to find what's actually costing frames on *this* hardware.

---

See the [panel hardware index](README.md) for the cross-panel comparison and method, and the
[TPA10](tpa10.md) / [WF1589T](wf1589t.md) / [S9E](s9e.md) references for the other panels.
