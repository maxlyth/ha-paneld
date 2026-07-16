# Amazon Echo Show 5 (2nd generation)

> [!NOTE]
> This preliminary profile targets the community LineageOS 18.1 Android 11 installation, not stock Fire OS. The facts below come from a contributor's diagnostic report in GitHub [#28](https://github.com/maxlyth/ha-paneld/issues/28); the device has not been validated by the maintainer.

The 2021 Echo Show 5 is a compact 5.5-inch smart display codenamed `cronos`. Replacing Fire OS with the community LineageOS build turns it into a conventional userdebug Android panel that can run ha-paneld's built-in dashboard or the Home Assistant Companion app.

| | |
|---|---|
| Profile fingerprint | `ro.product.device=cronos` |
| SoC | MediaTek MT8163, four cores |
| Android | LineageOS 18.1 / Android 11 (API 30), userdebug |
| ABI | armeabi-v7a (32-bit userspace) |
| Display | 960×480 at 195 dpi, 5.5 inches |
| RAM / storage | 1 GB / 8 GB |
| Root | app-accessible Android-style `su` on the reported LineageOS build |
| Sensors | ambient light; no Android proximity sensor reported |
| LED / relays / Zigbee | none reported |
| System WebView | LineageOS WebView 146 reported; current enough for the HA frontend |

## ha-paneld support

The [`EchoShow5Gen2`](../../app/src/main/kotlin/io/github/maxlyth/hapaneld/device/EchoShow5Gen2.kt) profile provides correct device identity, skips nonexistent RGB LED probing, and retains the generic brightness-based screen-off fallback. Hardware buttons, CPU governor mappings and a recommended display-density preset remain unset until measured on a unit.

Network ADB can be enabled through LineageOS developer options. The report confirms persistent TCP ADB on port 5555. Install and provision ha-paneld through the normal [provisioning flow](../provisioning.md).

## Known limitations

- The community LineageOS build is unofficial and replacing Fire OS requires unlocking and reflashing the device.
- This profile does not match the first-generation Echo Show 5 (`checkers`) or first-generation Echo Show 8 (`crown`). Those need separate diagnostic reports and profiles.
- No `/sys/class/backlight` device was reported. The candidate `/sys/class/leds/lcd-backlight` node is not used by the current hardware writer, so screen control still uses Android brightness and the general rooted fallbacks.

## Characterising the remaining candidates

The repository's read-only host collector records the fixed `lcd-backlight` files and the exact `m_alsps_input` capability metadata without changing the device:

```bash
scripts/collect-panel-hardware.sh --serial <panel-ip:5555> --observe light
```

The observed minimum of 10 is not yet evidence of a hardware floor: ha-paneld itself uses 10 as its global never-blank command floor. The reported node is also under `/sys/class/leds`, while the current hardware backlight writer discovers `/sys/class/backlight`. Compare passive reports before and after manually moving Android's brightness slider to establish correlation. Light and proximity meanings need separate attended observations; zero-brightness, touch-wake and recovery behavior cannot be certified by this read-only collector.
