# Sonoff NSPanel Pro (Rockchip PX30 / rk3326)

Hardware reference for the **Sonoff NSPanel Pro** (and Pro120) — the most common Home-Assistant wall
panel on the market. Reverse-engineered on a live unit (Android 8.1, rooted, toolbox `su`) on
2026-06-05.

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

## WebView — update this first

The NSPanel Pro ships with a WebView/Chromium far too old to render a current Home Assistant dashboard
(blank/broken UI in the HA Companion app). Update it cleanly over adb — no root, no F-Droid; this unit
runs Chromium **138** afterwards. See [Updating the system WebView](README.md#updating-the-system-webview).

## Bound i2c devices (real hardware)

| i2c addr | driver / name | What it is |
|---|---|---|
| `0-0020` | `rk809` | PMIC |
| `1-001a` / `1-005a` | `CST226` / `CST226SE` | Hynitron capacitive touch controller |
| `2-003c` | `tp` | touch panel |
| `2-0046` | `ls_stk3a5x` + `ps_stk3a5x` | Sensortek **STK3A5x** ambient-light + proximity combo |
| `2-0047` | `ls_stk3x3x` + `ps_stk3x3x` | Sensortek **STK3x3x** light + proximity (alt variant) |

## Sensors — light + proximity are app-direct

Unlike the TPA10 (where light/temp are root-only), the NSPanel Pro exposes its Sensortek combo through
standard `SensorManager`: `android.sensor.light`, `android.sensor.proximity`, and
`android.sensor.accelerometer` — all readable by a normal app, no root. ha-paneld reads light +
proximity here directly. No temperature/humidity sensor is fitted.

## LED

No `/sys/class/leds` RGB node and no `/dev/ledjni` were found on this unit, so there is **no
app/​sysfs-controllable RGB LED characterised** on the NSPanel Pro (contrast the TPA10's `avsux` node
and the WF1589T's `/dev/ledjni`). Screen brightness/backlight use the standard Android paths.

## Zigbee gateway

The NSPanel Pro has a built-in **Silicon Labs EFR32 Zigbee 3.0 radio** on UART `/dev/ttyS5`. Out of
the box it is owned by the vendor daemon `/vendor/bin/siliconlabs_host/zgateway` and driven by the
eWeLink apps (`com.eWeLinkNSPro.dev`, `com.eWeLinkControlPanel`) — i.e. the panel ships as an eWeLink
Zigbee hub.

The radio runs **EZSP NCP firmware** (EFR32MG21, EZSP v8); `zgateway` is an EZSP *host* binary in
`/vendor/bin/siliconlabs_host/`, kept alive by its own `guard_process.sh` supervisor (a 5-second loop,
boot-started) and controlled over a **local mosquitto broker** on `127.0.0.1:1883` (anonymous — the
`password_file` line is commented out in `mosquitto.conf`):

- role status: `zigbee/system/network-role/information` → `{"role":"Repeater"|"Coordinator"}`
- role switch: `zigbee/system/network-role/switch` ← `{"role":"Repeater"}`

"Repeater" is router mode (extends an existing mesh — the supported sweet spot); the role persists in
the NCP's NVM. Switching role is **not a reflash** — there is no `.gbl`/bootloader step anywhere; it
just sets the EZSP node type. The vendor `zgateway` survives removal of the eWeLink *apps* (it lives in
`/vendor`, not in an APK).

ha-paneld manages this directly (v0.6.1+): `switch.<panel>_zigbee_router` turns the panel into a Zigbee
router/repeater (starts the guard + ensures Repeater role) and back off (stops the guard + zstack,
freeing the radio) — over the local broker, credential-free, no `ttyS5` handling. The router then
appears as a normal device in your ZHA / Zigbee2MQTT coordinator. For a full standalone Zigbee2MQTT/ZHA
coordinator *on the panel* instead, see [seaky/nspanel_pro_zigbee](https://github.com/seaky/nspanel_pro_zigbee),
which swaps the host stack (heavier; not what ha-paneld does).

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
lean, prefer the split-instance (HADS) approach to cut WebSocket event volume, and use ha-paneld's
instrumentation (CPU clock/throttling, responsiveness, top-5 processes, 1-click WebView DevTools
relay) to find what's actually costing frames on *this* hardware.
