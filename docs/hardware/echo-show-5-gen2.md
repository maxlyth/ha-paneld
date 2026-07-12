# Amazon Echo Show 5 (2nd generation)

> [!NOTE]
> This preliminary profile targets the community LineageOS 18.1 Android 11 installation, not stock Fire OS. The facts below come from a contributor's diagnostic report in GitHub [#28](https://github.com/maxlyth/ha-paneld/issues/28); the device has not been validated by the maintainer.

The 2021 Echo Show 5 is a compact 5.5-inch smart display codenamed `cronos`. Replacing Fire OS with the community LineageOS build turns it into a conventional userdebug Android panel on which ha-paneld and the Home Assistant Companion can run.

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
- No dedicated backlight sysfs device was reported; screen control currently uses Android brightness and the general rooted fallbacks.
