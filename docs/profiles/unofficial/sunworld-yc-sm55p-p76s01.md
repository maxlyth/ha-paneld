# Unofficial Sunworld YC-SM55P profile notes

> [!IMPORTANT]
> This is an import-only community profile based on one contributor's diagnostic report and a vendor firmware image supplied through GitHub [#129](https://github.com/maxlyth/ha-paneld/issues/129). It is not bundled, automatically selected, maintainer-validated or fleet-qualified. It requires ha-paneld 0.9.7-rc3 or later because earlier builds can roll back an imported profile during the activation restart.

The reported panel is sold as a 5.5-inch Sunworld YC-SM55P and uses the Portworld YC-P76S01 board. It runs Android 14 on a Rockchip RK3576S and was rooted by its owner with Magisk. The submitted vendor image confirms the board, display, touch and key hardware without relying only on the running Android system.

| | |
|---|---|
| Profile fingerprint | model `rk3576s_u`, device `rk3576s_u` and product version `1.0.0` |
| SoC | Rockchip RK3576S, four Cortex-A72 plus four Cortex-A53 cores |
| Android | Android 14 (API 34), userdebug |
| Reported firmware | `P76S01-A14-SM55P-JD9365-720x1280-20260612-V0.01` |
| Inspected vendor image | `P76S01-A14-55P-MIPI-720x1280-20260722-V0.02` |
| Display | 720×1280, 5.5 inches; logical 240 dpi on the reported panel |
| RAM / storage | 3.8 GB / 62.5 GB on the reported panel |
| Root | Owner-installed Magisk; app-accessible Android-style `su` and the ha-paneld root helper were active in the report |
| Touch / keys | CHSC capacitive touch and standard Android volume keys |
| LED | No panel RGB LED. The firmware's eMMC activity LED is not a user-facing panel light and is left alone |
| Screen off | `brightness-zero`; the PWM backlight is confirmed, but a true panel-power route has not been tested |

## Why it is import-only

The firmware identifies itself as `rk3576s_u` with product version `1.0.0`. Those are generic Rockchip build values rather than a unique retail-product fingerprint, so bundling this profile would risk selecting it on a different P76S01 panel. Manual import keeps that choice with an owner who can confirm the product in front of them.

The profile deliberately does not declare a physical display density. The retail listing gives a 5.5-inch diagonal, but the firmware's panel dimensions appear to be generic board data rather than a reliable measurement of the finished product.

## Hardware still to confirm

The vendor image contains device-tree entries for AHT20 temperature and humidity, EM3071x light and proximity, and a GC2145 camera. Matching entries also appear on the running I2C buses, but a device-tree node does not prove that a component is fitted, exposed to Android or usable by ha-paneld. The reporter says the finished product has no camera or proximity sensor, so the first profile leaves all of these optional devices disabled.

The firmware also includes Portworld boot-start and human-sensor utilities. They are not disabled by this revision because their purpose and effect on this exact product have not yet been checked.

## First activation

Use ha-paneld 0.9.7-rc3 or later, then follow the [unofficial catalog procedure](README.md) to import [`community-sunworld-yc-sm55p-p76s01.yaml`](community-sunworld-yc-sm55p-p76s01.yaml), validate it and activate it while somebody can see and touch the panel. After restart:

1. Confirm that the Profiles page shows `Unofficial Sunworld YC-SM55P (P76S01)` as active and does not roll back to Generic.
2. Check brightness, screen off and touch wake from both the panel UI and Home Assistant.
3. Check the volume keys, audio playback and microphone if the retail unit includes one.
4. Record whether ambient light, proximity, temperature, humidity or camera data is physically available; do not infer it from the I2C list alone.
5. Exercise **Roll back** before relying on the profile unattended.

The first activation result can move confirmed firmware into `tested_firmware` and replace these conservative omissions with hardware declarations supported by a live test.
