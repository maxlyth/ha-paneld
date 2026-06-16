# Smatek S9E (Rockchip RK3566)

A 10.1" **1920×1200** RK3566 in-wall panel — same SoC family as the TPA10 — with **2 on-board mains
relays**, four LED-backlit buttons, a radar proximity sensor, plus **Zigbee, Ethernet and RS485**.
Unlike the other pages here, this is **not** reverse-engineered on a unit in hand: specs are from
Smatek's listing and control paths from community sources. Treat control-path details as unconfirmed
until validated on hardware.

> [!TIP]
> Most-needed facts: shares the TPA10's **RK3566** platform, so app-side features likely work without
> S9E-specific code; the **relays switch mains loads** (root sysfs, implemented but untested); buttons
> emit **`F1`–`F4`** KeyEvents (app-direct, no root). Update the **very old (Chromium 83) WebView**
> first.

| | |
|---|---|
| SoC | Rockchip **RK3566** (quad Cortex-A55) — same SoC family as the TPA10 |
| RAM / storage | **2 GB** RAM / **16 GB** eMMC |
| Display | **10.1"**, **1920×1200** multi-touch |
| Android | 11 |
| Connectivity | Wi-Fi, Bluetooth, **Zigbee**, **RJ45 Ethernet**, **RS485** (a PoE variant, **S9PE**, also exists) |
| Sensors | proximity **radar**, ambient **light**, **temperature + humidity** |
| Inputs | **4 physical buttons** with individual LEDs |
| Relays | **2 on-board mains relays** |
| Root | vendor app uses `execRootCmd` → root is available; some units ship with developer mode pre-unlocked. Whether `su` is reachable from a normal app sandbox is **unconfirmed** (it decides whether ha-paneld drives the sysfs nodes directly or needs the root helper daemon). |

> [!CAUTION]
> The control surfaces below are **root sysfs** writes, and the relays switch **mains loads**. The
> ha-paneld support is implemented from the reported paths but is **untested on hardware** — validate
> on a real S9E before relying on it.

> [!TIP]
> Because the S9E shares the TPA10's **RK3566** platform, ha-paneld's existing app-side features likely
> work without S9E-specific code: temperature/humidity/light may surface through `SensorManager` (as on
> the TPA10's CHT8305), and the **WebView is shipped very old (Chromium 83)** — update it first (Magisk
> OpenWebView, the 2025-03-24 firmware, or a sideloaded SystemWebView). Confirm on a unit.

> [!TIP]
> Changing firmware on a button-less panel? Read [Firmware backup & restore](../firmware-backup-restore.md)
> first — the S9E is an RK3566 Rockchip device, so the usual button-combo fastboot/recovery advice does
> not apply (partition table not yet captured on a unit here).

## Firmware versions

Two stock images have been analysed (block-OTA `.zip`, AOSP dynamic-partition format — `brotli -d`
the `.new.dat.br`, `lpunpack` the super image, `strings`-grep `build.prop` + init `.rc`). Both report
`Build.MODEL` **`S9`** / `Build.DEVICE` **`rk3566_r`**, Android 11; the vendor build code lives in
**`ro.product.version`**:

| Image | `ro.product.version` | Build | Relay class |
|---|---|---|---|
| `S9_1920x1200_20240712_Android_US` | `S9_Android_1.0.2` | `eng.*.20240712` | `/sys/class/st_relay` |
| `S9_1920x1200_20251202_Android_US` | `S9_Android_1.1.0` | `eng.xiaolp.20251202.160404` | `/sys/class/strelay` |

> [!IMPORTANT]
> **The relay sysfs class was renamed between firmware versions.** Only the **initial** image (1.0.2)
> uses `/sys/class/st_relay`; **all newer** images (1.1.0+) use `/sys/class/strelay` — reporter-confirmed
> in [#3](https://github.com/maxlyth/ha-paneld/issues/3) and matched by the firmware diff. ha-paneld
> ≤ 0.8.2 probes only `st_relay`, so relays are invisible on any panel running 1.1.0 or later. The fix
> (probe both class names) is tracked for **0.8.3** — see the relay section below.

Downloads (Smatek, as shared by the reporter in [#3](https://github.com/maxlyth/ha-paneld/issues/3) — the
URLs carry Smatek's shared download password):

- [`S9_1920x1200_20240712_Android_US` — 1.0.2](http://docs.smatek.store:10001/s/48vAcr?password=icwm34)
- [`S9_1920x1200_20251202_Android_US` — 1.1.0](http://docs.smatek.store:10001/s/QEYPSL?password=wyh1gh)

Diffing the two images: **only `ro.product.version` and the relay class differ.** Every other control
path documented below (button keycodes, button-LED GPIOs, proximity GPIO, sensor wiring) is identical
across both — so detection keyed on `ro.product.version` starting `S9` covers the whole line.

## Relays — `strelay` / `st_relay` class (root)

ha-paneld exposes the two mains relays as `switch.<panel>_relay1` / `switch.<panel>_relay2`, gated on
the presence of the relay sysfs class (so the entities appear only on a panel that has it). This is the
first concrete panel for the [built-in relay roadmap item](../../README.md#status--roadmap).

> [!CAUTION]
> **The class name is firmware-dependent** (see [Firmware versions](#firmware-versions)): `st_relay` on
> the initial 1.0.2 image, `strelay` on 1.1.0 and later. ha-paneld ≤ 0.8.2 probes only `st_relay`, so a
> panel on 1.1.0+ shows no relay entities. Probing both names is tracked for **0.8.3**.

```bash
# firmware 1.1.0+ (most panels in the field)
echo 1 > /sys/class/strelay/relay1    # on
echo 0 > /sys/class/strelay/relay1    # off
echo 1 > /sys/class/strelay/relay2

# firmware 1.0.2 (initial release)
echo 1 > /sys/class/st_relay/relay1
```

## Buttons — `F1`–`F4` KeyEvents (app-direct)

The four buttons emit standard Android key codes **131–134** = `KEYCODE_F1`–`KEYCODE_F4`. ha-paneld's
accessibility-service capture already reports these to `event.<panel>_button` (event types
`KEYCODE_F1`…`KEYCODE_F4`); bind dashboard actions to them in HA. No root needed for the events.

## LED — per-button GPIO backlight (implemented; live since 0.8.2-rc3)

Each button has an LED at `/sys/class/gpio/gpio<16+keycode>/value` — i.e. gpio **147–150** for buttons
F1–F4. Per-button on/off, monochrome. ha-paneld exposes these as `light.<panel>_button_led1..4`
(on/off via `su`), counted from the `buttonLedGpioBase` profile value. This was inert on the S9E until
**0.8.2-rc3** fixed device detection (GitHub #3/#4 — the panel was falling back to the generic profile);
the write path matches the reporter's vendor code exactly.

> [!NOTE]
> **Firmware analysis (2026-06-16):** the init scripts in both images export **only `gpio113`** — the
> button-LED pins **gpio147–150 are not exported at boot**, so the `/sys/class/gpio/gpioNNN/value` nodes
> won't exist until exported. ha-paneld ≤ 0.8.2 writes the node directly with no export step, which is the
> likely reason the LEDs are still unconfirmed on hardware. An idempotent `echo NNN > /sys/class/gpio/export`
> before the first write is tracked for **0.8.3**.

```bash
echo 147 > /sys/class/gpio/export        # one-time, if the node is absent
echo 1 > /sys/class/gpio/gpio147/value   # button F1 LED on
```

> [!TIP]
> The firmware also carries an **RGB status LED** (`led_r` / `led_g` / `led_b`), a **vibrator/haptic**
> motor and **Ethernet-activity LEDs** — none currently exposed by ha-paneld. The RGB LED is the most
> useful future addition (a panel-wide status colour); the rest are low value. Not yet wired.

## Sensors — proximity (SensorManager; gpio18 is the raw path)

> [!NOTE]
> **Updated 2026-06-15** from a reporter's `/diag` (GitHub #5). The earlier note here — that the S9E
> proximity is "not an Android `SensorManager` sensor" and the existing entities "won't pick this up" —
> was **wrong**.

The S9E proximity **does** surface through Android `SensorManager`: the reporter's `/diag` shows
`Proximity=yes · Binary · near/far (0 / 1 cm)`. So ha-paneld's existing `binary_sensor.<panel>_proximity`
and the **wake-on-wave** local screen-wake already work on the S9E — no S9E-specific code needed.

The raw hardware path is a **root GPIO read at GPIO 18**. The kernel driver also registers it as the
Android sensor, so `gpio18` and `SensorManager` are the **same signal via two routes**:

```bash
cat /sys/class/gpio/gpio18/value   # 1 = present, 0 = clear
```

Reading `gpio18` directly **likely won't provide any better functionality right now**: it's a binary
`0/1` — the same near/far `SensorManager` already reports — and the Android path is event-driven and
needs no root or polling loop. Keep `gpio18` on file only as a **fallback** if a future S9E firmware
stops exposing the Android sensor (cf. Sonoff fw > 3 changing proximity reporting). Ambient light and
temperature + humidity likely also surface via `SensorManager` (as on the TPA10's CHT8305).

## Access model summary

- **Relays**: `switch.<panel>_relay1/2` via the relay sysfs class (root). Implemented for `st_relay` (firmware 1.0.2); **probe-both fix for `strelay` on 1.1.0+ pending 0.8.3**. **Untested on hardware.**
- **Buttons**: `event.<panel>_button` (`KEYCODE_F1`–`F4`), app-direct via a11y. Implemented.
- **Button LEDs**: `light.<panel>_button_led1..4` via `su` (gpio 147–150). Implemented; live since the 0.8.2-rc3 detection fix, **untested**.
- **Proximity**: `binary_sensor.<panel>_proximity` + wake-on-wave via `SensorManager` — works (per the reporter's `/diag`). Raw `gpio18` documented as a fallback only, not wired.

## Sources

<details>
<summary>Source links + provenance</summary>

- [ha-paneld#3 — "Smatek S9E"](https://github.com/maxlyth/ha-paneld/issues/3) — the reporter's `/diag` (detection strings, `SensorManager` proximity), the relay-class rename (`st_relay` → `strelay`), and the two firmware download links below.
- Smatek S9E stock firmware (shared by the reporter in #3): [1.0.2 / 20240712](http://docs.smatek.store:10001/s/48vAcr?password=icwm34), [1.1.0 / 20251202](http://docs.smatek.store:10001/s/QEYPSL?password=wyh1gh) — the two images analysed (2026-06-16) for the relay-class rename, the gpio147–150 export gap, the RGB LED / vibrator, and detection-string stability.
- [seaky/nspanel_pro_tools_apk#98 — "Add Smatek S9E Support"](https://github.com/seaky/nspanel_pro_tools_apk/issues/98) — the relay (`st_relay`), button (keycodes 131–134), button-LED (gpio 147–150) and proximity (gpio18) control paths.
- [Home Assistant community: "Smatek S9E Touch Panel"](https://community.home-assistant.io/t/smatek-s9e-touch-panel/828244) — WebView update, GPIO18 proximity scripts, integration notes.
- [Smatek S9E product page](https://smatek.com/product/10-1-inch-smart-control-panel-s9e/) and [S9PE-NZ PoE variant](https://smatek.com/product/10-1-inch-android-panel-s9e-nz/) — SoC, RAM/storage, display, sensors, connectivity.
- [Smatek S9E spec sheet (PDF)](https://smatek.com/wp-content/uploads/2025/03/Smatek-S9E-10-Super-Smart-Control-Panelin-wall-SPEC.pdf) — image-only scan; not text-extractable, but the source datasheet.

</details>

---

See the [panel hardware index](README.md) for the cross-panel comparison and method, and the
[NSPanel Pro](nspanel-pro.md) / [TPA10](tpa10.md) (the same RK3566 platform) / [WF1589T](wf1589t.md)
references for the other panels.
