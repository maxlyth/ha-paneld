# ZX-SMT156 / RK3566_T panel

> [!NOTE]
> This is a preliminary community profile based on the diagnostic report in GitHub [#24](https://github.com/maxlyth/ha-paneld/issues/24). The panel is unbranded and has not been validated by the maintainer.

This 15.6-inch Android wall panel identifies its firmware as `ZX-SMT156` and its exact model/device as `rk3566_t`. The `_t` device identifier is the reliable discriminator; generic `rk3566` matching would collide with unrelated TPA10 and S9E hardware.

| | |
|---|---|
| Profile fingerprint | model/device `rk3566_t`; reported firmware `ZX-SMT156-R128V1.2B-15.6-GG-J4.79U-20250926` |
| SoC | Rockchip rk3566, four cores, `rk30board` hardware |
| Android | 13 (API 33) |
| ABI | arm64-v8a, with 32-bit compatibility |
| Display | 1920×1080 at 160 dpi, 15.6 inches |
| RAM / storage | 4 GB / 32 GB |
| Root | no app-accessible `su`; SELinux reported permissive |
| RGB LED | app-direct `/dev/ledjni`, confirmed working |
| Sensors | binary proximity and ambient light confirmed |
| System WebView | Google WebView 149 reported; current enough for the HA frontend |
| Relays / climate | present in the vendor MQTT implementation according to the reporter, but no Android/sysfs control path has been identified |

## ha-paneld support

The [`ZxSmt156`](../../app/src/main/kotlin/io/github/maxlyth/hapaneld/device/ZxSmt156.kt) profile provides correct identity and explicitly routes the working `/dev/ledjni` RGB controller. It uses the conservative LED transfer inherited from the previous Generic fallback until a reporter supplies a measured response curve. Standard Android brightness, light/proximity sensing, navigation, MQTT and the built-in dashboard work without root.

True backlight-off, reboot, density changes and other privileged controls remain unavailable unless a root/helper installation path is established. The profile deliberately declares no relay or temperature/humidity path: inventing one from the vendor MQTT feature list would risk controlling the wrong sysfs device.

## Characterising missing hardware

Diagnostics from 0.9.2-rc1 and later include a bounded `[hardware]` block containing input-device names, bound I²C/IIO names, thermal-zone types and likely relay-class entries. A follow-up `/api/v1/diag` report should reveal whether the vendor climate and relay functions use standard kernel surfaces. If those lists remain empty, the next useful evidence is the package name of the vendor MQTT service and its logs while toggling a relay or reading climate data.

No public firmware source has been found for this model. Preserve a firmware/recovery backup before modifying the vendor installation and share only hardware metadata—not firmware blobs—in a public issue.
