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

> [!NOTE]
> **The class name is firmware-dependent** (see [Firmware versions](#firmware-versions)): `st_relay` on
> the initial 1.0.2 image, `strelay` on 1.1.0 and later. ha-paneld probes **both** names since **0.8.2**
> and uses whichever the panel exposes. **Confirmed working on hardware** (reporter, 0.8.2, GitHub #3).

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

## LED — per-button GPIO backlight (confirmed working, 0.8.2)

Each button has an LED at `/sys/class/gpio/gpio<16+keycode>/value` — i.e. gpio **147–150** for buttons
F1–F4. Per-button on/off, monochrome. ha-paneld exposes these as `light.<panel>_button_led1..4`
(on/off via `su`), counted from the `buttonLedGpioBase` profile value. This was inert on the S9E until
**0.8.2-rc3** fixed device detection (GitHub #3/#4 — the panel was falling back to the generic profile);
the write path matches the reporter's vendor code exactly. **Confirmed working on hardware** (reporter,
0.8.2, GitHub #3/#4). Note the LEDs are **not** under `/sys/class/leds` (that class holds only the
`mmc2::` SD-card LED on the S9E) — they are raw GPIOs.

> [!NOTE]
> **Firmware analysis (2026-06-16):** the init scripts export **only `gpio113`** — the button-LED pins
> **gpio147–150 are not exported at boot**, so the `/sys/class/gpio/gpioNNN/value` nodes don't exist
> until exported. **0.8.2** exports (and sets to output) each pin on demand before the first write, which
> is why the LEDs now work despite not being init-exported.

```bash
echo 147 > /sys/class/gpio/export        # one-time, if the node is absent
echo 1 > /sys/class/gpio/gpio147/value   # button F1 LED on
```

> [!TIP]
> The firmware also carries an **RGB status LED** (`led_r` / `led_g` / `led_b`), a **vibrator/haptic**
> motor and **Ethernet-activity LEDs** — none currently exposed by ha-paneld. The RGB LED is the most
> useful future addition (a panel-wide status colour); the rest are low value. Not yet wired.

## Sensors — proximity (SensorManager registers but does NOT deliver; gpio18 is the real path)

> [!IMPORTANT]
> **Corrected 2026-06-16** from the reporter's live readings (GitHub #5). A proximity sensor is
> *registered* in `SensorManager` (an earlier `/diag` showed `Proximity=yes · Binary · 0/1 cm`), but on
> the S9E it **never delivers events**: `binary_sensor.<panel>_proximity` reads **Unknown** in HA, the
> tuning card shows **`raw —` · FAR**, and waving a hand changes nothing. The light sensor on the same
> panel works (≈46 lx), so this is proximity-specific. The earlier claim that SensorManager proximity
> "already works" on the S9E was **wrong** — and so the local **wake-on-wave** does not work on the S9E.

The real signal is a **root GPIO read at GPIO 18** (the kernel registers a phantom Android sensor that
never fires, so the value has to be read from sysfs):

```bash
cat /sys/class/gpio/gpio18/value   # 1 = near, 0 = far  (no export needed — reporter-confirmed, #5)
```

So S9E proximity reads `gpio18` over root directly instead of relying on `SensorManager`. **Implemented
in 0.8.3**: `DeviceProfile.proximityGpio` = 18, so `SensorReporter` polls the node ~2×/s through the
persistent `su` shell and feeds the same `binary_sensor.<panel>_proximity` + wake-on-wave path (the dead
SensorManager proximity isn't registered on the S9E). Reporter-confirmed: gpio18 reads **0 far / 1
near**, no export needed. Ambient light + temperature/humidity surface via `SensorManager` as expected.

## Access model summary

- **Relays**: `switch.<panel>_relay1/2` via the relay sysfs class (root); probes both `strelay`/`st_relay`. **Confirmed working (0.8.2).**
- **Buttons**: `event.<panel>_button` (`KEYCODE_F1`–`F4`), app-direct via a11y. Implemented.
- **Button LEDs**: `light.<panel>_button_led1..4` via `su` (gpio 147–150, exported on demand). **Confirmed working (0.8.2).**
- **Proximity**: `binary_sensor.<panel>_proximity` via a **root `gpio18` poll** (0.8.3) — the SensorManager proximity registers but never fires, so it's bypassed. 0 far / 1 near; wake-on-wave works via the poller.
- **Light**: `sensor.<panel>_illuminance` via `SensorManager` — **works**.

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
