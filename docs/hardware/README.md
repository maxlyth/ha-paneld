# Panel hardware references

Reverse-engineered hardware notes for the wall panels ha-paneld targets. These devices ship with
almost no public documentation, so these notes record what is physically on each board and how to
drive it — gathered from live units (rooted / userdebug `adb root`) on 2026-06-05.

| Panel | SoC | LED control | Notable sensors | NFC | Zigbee/IR | Reference |
|---|---|---|---|---|---|---|
| Tuya TPA10 | rk3566 | `avsux` sysfs (root daemon) | ToF VI5300, CHT8305 temp+humidity, CG5256 light | no | no | [tpa10.md](tpa10.md) |
| Electron WF1589T | rk3576 | `/dev/ledjni` (app-direct) | 6-axis IMU (KXTJ9 + BMA2xx) | yes — NXP, but Android-NFC disabled | no | [wf1589t.md](wf1589t.md) |
| Sonoff NSPanel Pro | rk3326 / PX30 | — | — | no | no | not yet characterised |

## Method

- **Real silicon**: bound i2c devices via `/sys/bus/i2c/devices/*/name` — *not* `…/drivers/`, because
  Rockchip BSPs compile in hundreds of optional drivers and the `drivers/` listing over-reports badly.
- **Radios**: `pm list features` (`nfc`, `consumerir`, `bluetooth`, `ethernet`, …) + `/dev` nodes.
- **Android-exposed sensors**: `dumpsys sensorservice`.
- **Control surfaces**: `/sys/class/leds`, `/dev`, and each LED node's own attributes (some panels
  self-document, e.g. the TPA10's `avsux_info` / `avsux_firmware`).

Corrections and additions for other panels are welcome.
