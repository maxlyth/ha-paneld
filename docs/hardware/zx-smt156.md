# ZX-SMT156 / RK3566_T panel

> [!NOTE]
> This is a preliminary profile based on the diagnostic report in GitHub [#24](https://github.com/maxlyth/ha-paneld/issues/24). It covers a genuine OEM/generic-market SMT156 wall panel identified through the owner's ELC/OEM product evidence, but it has not been validated on maintainer hardware.

This 15.6-inch Android wall panel identifies its firmware as `ZX-SMT156` and its exact model/device as `rk3566_t`. The `_t` device identifier is the reliable discriminator; generic `rk3566` matching would collide with unrelated TPA10 and S9E hardware.

| | |
|---|---|
| Profile fingerprint | model/device `rk3566_t`; reported firmware `ZX-SMT156-R128V1.2B-15.6-GG-J4.79U-20250926` |
| SoC | Rockchip rk3566, four cores, `rk30board` hardware |
| Android | 13 (API 33) |
| ABI | arm64-v8a, with 32-bit compatibility |
| Display | 1920×1080 at 160 dpi, 15.6 inches |
| RAM / storage | 4 GB / 32 GB |
| Root | no app-accessible `su` on the reported installation; USB `adb root`, vendor engineering mode and persistent unlock routes were not tested |
| RGB LED | app-direct `/dev/ledjni`, confirmed working |
| Sensors | binary proximity and ambient light confirmed; GXHT30 climate inputs identified through reporter-supplied `adb shell` evidence |
| System WebView | Google WebView 149 reported; current enough for the HA frontend |
| Relays | present in the vendor MQTT implementation according to the reporter, but no Android/sysfs control path has been identified |

## ha-paneld support

The bundled [`zx-smt156.yaml`](../../app/src/main/assets/device-profiles/zx-smt156.yaml) profile provides correct identity and explicitly routes the working `/dev/ledjni` RGB controller. It uses the conservative LED transfer inherited from the previous Generic fallback until a reporter supplies a measured response curve. Standard Android brightness, light/proximity sensing, navigation, MQTT and the built-in dashboard work without root.

The profile exposes Room temperature and Room humidity as an optional enhanced capability, not part of the core support contract. Reporter evidence shows `sun-ths` temperature in hundredths of a degree Celsius on `ABS_THROTTLE` and `sun-hum` relative humidity in hundredths of a percent on vendor axis `0x1d`. ha-paneld prefers its established helper when present and otherwise uses the locally approved Shizuku shell identity that the collector proved can read these nodes. A local temperature calibration offset remains available because wall-panel self-heating has not been characterised.

The reported app installation had no app-accessible `su`, but USB `adb root`, vendor engineering mode and a persistent unlock route remain uncharacterised rather than ruled out. Runtime route probes remain decisive and each privileged route is checked when used. Until one is established, true backlight-off, reboot and root-only controls need an installed helper; locally approved Shizuku can provide only its documented narrower subset and is not foundational to ZX-SMT156 support. The profile deliberately declares no relay path: inventing one from the vendor MQTT feature list would risk controlling the wrong sysfs device.

## Characterising missing hardware

Diagnostics from 0.9.2 and later include a bounded `[hardware]` block containing input-device names, bound I²C/IIO names, thermal-zone types and likely relay-class entries. That report identified the `sun-ths` and `sun-hum` input devices; the relay path remains unknown. The next useful relay evidence is the package name of the vendor MQTT service and its narrowly scoped traffic while an attended test toggles one relay.

For the already identified `sun-ths` and `sun-hum` input devices, download and run the read-only collector below. Replace the example address with the panel's address, then paste the complete line into the same Git Bash, WSL, macOS Terminal or Linux terminal where `adb` works:

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/collect-panel-hardware.sh -o collect-panel-hardware.sh && bash collect-panel-hardware.sh --serial 192.168.1.50:5555 --observe climate
```

This only reads the two climate input devices; it does not change the panel. The live observation is bounded to 10 seconds and 32 events per exact sensor name. Review the terminal output before sharing it; if the command reports an error, share that error instead. The 2026-07-17 `adb shell` report established the input names, axes and values consistent with centi-unit scaling for the bounded reader included in this release. Reporter comparison of ha-paneld's displayed readings against the vendor display or an external thermometer/hygrometer is still required before calling them hardware-validated. ADB-shell readability does not grant ordinary app access; the included path uses either the installed allowlisted helper or the same shell identity after local Shizuku approval.

No public firmware source has been found for this model. Preserve a firmware/recovery backup before modifying the vendor installation and share only hardware metadata—not firmware blobs—in a public issue.
