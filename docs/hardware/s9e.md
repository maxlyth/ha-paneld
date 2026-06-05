# Smatek S9E

Hardware reference for the **Smatek S9E** in-wall control panel. Unlike the other pages here, this is
**not** reverse-engineered on a unit in hand: the specs are from Smatek's product listing, and the
control paths from [seaky/nspanel_pro_tools_apk#98](https://github.com/seaky/nspanel_pro_tools_apk/issues/98)
and the [HA community thread](https://community.home-assistant.io/t/smatek-s9e-touch-panel/828244).
Treat the control-path details as unconfirmed until validated on hardware.

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

Sources: [Smatek S9E](https://smatek.com/product/10-1-inch-smart-control-panel-s9e/) ·
[Smatek S9PE-NZ (PoE)](https://smatek.com/product/10-1-inch-android-panel-s9e-nz/) · seaky#98 · HA community thread.

> [!CAUTION]
> The control surfaces below are **root sysfs** writes, and the relays switch **mains loads**. The
> ha-paneld support is implemented from the reported paths but is **untested on hardware** — validate
> on a real S9E before relying on it.

> [!TIP]
> Because the S9E shares the TPA10's **RK3566** platform, ha-paneld's existing app-side features likely
> work without S9E-specific code: temperature/humidity/light may surface through `SensorManager` (as on
> the TPA10's CHT8305), and the **WebView is shipped very old (Chromium 83)** — update it first (Magisk
> OpenWebView, the 2025-03-24 firmware, or a sideloaded SystemWebView). Confirm on a unit.

## Control surfaces (from #98)

### Relays — `st_relay` class

```bash
echo 1 > /sys/class/st_relay/relay1   # on
echo 0 > /sys/class/st_relay/relay1   # off
echo 1 > /sys/class/st_relay/relay2
```

ha-paneld exposes these as `switch.<panel>_relay1` / `switch.<panel>_relay2`, gated on the presence of
the `st_relay` class (so the entities appear only on a panel that has it). This is the first concrete
panel for the [built-in relay roadmap item](../../README.md#status--roadmap).

### Buttons — `F1`–`F4` KeyEvents

The four buttons emit standard Android key codes **131–134** = `KEYCODE_F1`–`KEYCODE_F4`. ha-paneld's
accessibility-service capture already reports these to `event.<panel>_button` (event types
`KEYCODE_F1`…`KEYCODE_F4`); bind dashboard actions to them in HA. No root needed for the events.

### Button LEDs — GPIO `16 + keycode` *(documented, not yet wired)*

Each button has an LED at `/sys/class/gpio/gpio<16+keycode>/value` — i.e. gpio **147–150** for buttons
F1–F4:

```bash
echo 1 > /sys/class/gpio/gpio147/value   # button F1 LED on
```

Per-button on/off, monochrome. Not yet exposed by ha-paneld (would be `light.<panel>_button_led1..4`
via the helper daemon / `su`).

### Proximity radar — GPIO 18 *(documented, not yet wired)*

```bash
cat /sys/class/gpio/gpio18/value   # 1 = present, 0 = clear
```

A root GPIO read (not an Android `SensorManager` sensor), so it needs a polling loop via the helper
daemon / `su`. Not yet wired; the existing wake-on-wave / proximity entities use SensorManager and
won't pick this up.

## Access model summary

- **Relays**: `switch.<panel>_relay1/2` via `/sys/class/st_relay` (root). Implemented, **untested**.
- **Buttons**: `event.<panel>_button` (`KEYCODE_F1`–`F4`), app-direct via a11y. Implemented.
- **Button LEDs / proximity radar**: documented above, not yet wired.

## Sources

- [seaky/nspanel_pro_tools_apk#98 — "Add Smatek S9E Support"](https://github.com/seaky/nspanel_pro_tools_apk/issues/98) — the relay (`st_relay`), button (keycodes 131–134), button-LED (gpio 147–150) and proximity (gpio18) control paths.
- [Home Assistant community: "Smatek S9E Touch Panel"](https://community.home-assistant.io/t/smatek-s9e-touch-panel/828244) — WebView update, GPIO18 proximity scripts, integration notes.
- [Smatek S9E product page](https://smatek.com/product/10-1-inch-smart-control-panel-s9e/) and [S9PE-NZ PoE variant](https://smatek.com/product/10-1-inch-android-panel-s9e-nz/) — SoC, RAM/storage, display, sensors, connectivity.
- [Smatek S9E spec sheet (PDF)](https://smatek.com/wp-content/uploads/2025/03/Smatek-S9E-10-Super-Smart-Control-Panelin-wall-SPEC.pdf) — image-only scan; not text-extractable, but the source datasheet.
