# Unofficial Echo Show 5 Gen 2 LineageOS profile notes

> [!IMPORTANT]
> This is an import-only community profile based on one contributor's diagnostic report in GitHub [#28](https://github.com/maxlyth/ha-paneld/issues/28). It describes that user's LineageOS 18.1 reflash, not the retail device as sold. Stock Fire OS cannot run ha-paneld, and this profile is not bundled, automatically selected, maintainer-validated or fleet-qualified.

The reported 2021 device is a 5.5-inch smart display whose reflash identifies the hardware with device codename `cronos` and model `Amzn Echo Show 5 (2nd Generation)`. The unofficial profile requires both observed values in the same match group; it can be broadened only after another diagnostic proves a real firmware variant.

| | |
|---|---|
| Profile fingerprint | device `cronos` plus model `Amzn Echo Show 5 (2nd Generation)` |
| SoC | MediaTek MT8163, four cores |
| Android | LineageOS 18.1 / Android 11 (API 30), userdebug |
| ABI | armeabi-v7a (32-bit userspace) |
| Display | 960×480 at 195 dpi, 5.5 inches |
| RAM / storage | 1 GB / 8 GB |
| Root | app-accessible Android-style `su` on the one reported LineageOS build |
| Sensors | ambient light reported; no Android proximity sensor reported |
| LED / relays / Zigbee | none reported |
| System WebView | LineageOS WebView 146 reported |

## Profile scope

The [`community-cronos-lineageos18.yaml`](community-cronos-lineageos18.yaml) profile selects only existing compiled Android mechanisms: ordinary brightness-based screen-off, Android ambient-light sensing and the reported Android-style `su` form. It declares no RGB LED, relay, radio, hardware-button, CPU-governor or density behavior.

The root declaration records evidence from one image; it does not grant authority. After activation and restart, the running driver must probe the live route. A missing or blocked route remains unavailable and must not be treated as proof that another reflash is compatible.

## Limitations

- Unlocking, reflashing, recovery and maintaining the community LineageOS image are outside the ha-paneld project. No installation instructions or recovery images are supplied here.
- The profile does not describe the first-generation Echo Show 5 or any Echo Show 8 generation.
- The reported `/sys/class/leds/lcd-backlight` node is not evidence of a supported hardware backlight writer. Screen control remains on the ordinary Android brightness route.
- The reported minimum brightness of 10 matches ha-paneld's general never-blank floor and does not establish a hardware limit.

Use the [unofficial catalog procedure](README.md) to import and validate the YAML. Review a fresh on-panel diagnostic report and confirm every expected capability after restart before relying on it unattended.
