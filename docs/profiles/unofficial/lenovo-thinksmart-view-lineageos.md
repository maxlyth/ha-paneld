# Unofficial Lenovo ThinkSmart View LineageOS profile notes

> [!IMPORTANT]
> This is an import-only community profile based on one contributor's diagnostic report and attended screen tests in GitHub [#130](https://github.com/maxlyth/ha-paneld/issues/130). It describes a Lenovo ThinkSmart View reflashed with LineageOS 8.1, not the retail Lenovo firmware. It is not bundled, automatically selected, maintainer-validated or fleet-qualified. It requires ha-paneld 0.9.7-rc3 or later because earlier builds can roll back an imported profile during the activation restart.

The reported panel is a Lenovo ThinkSmart View with Android identity `lenovo starview` / `starfire`, running a community LineageOS 8.1 image with root. The Generic profile can dim it after **Modify system settings** is granted, but Android brightness zero remains visibly lit because this firmware exposes no Linux backlight-class device. An attended test confirmed that `KEYCODE_SLEEP` puts Android fully to sleep.

| | |
|---|---|
| Profile fingerprint | model `lenovo starview` plus device `starfire` |
| SoC | Qualcomm Snapdragon 624 / MSM8953, eight Cortex-A53 cores |
| Android | LineageOS 8.1 (API 27), userdebug |
| Reported firmware | `lineage_starfire-userdebug 8.1.0 OPM7.181205.001 d41d2fdda0 test-keys` |
| Display | 800×1280, 8 inches, approximately 189 ppi |
| RAM / storage | 1.8 GB / 7.8 GB on the reported panel |
| Root | App-accessible Android-style `su` and the ha-paneld root helper were active in the report |
| Sensors | Android ambient-light and proximity sensors appear in the exported profile draft; Lenovo lists both for this product |
| LED | No supported panel RGB LED. The Linux LED entries in the diagnostic report are not declared as a user-facing light |
| Screen off | `keyevent`; `KEYCODE_SLEEP` was proved to enter real Android sleep |

## Screen and wake behavior

The [`community-lenovo-thinksmart-view-lineageos.yaml`](community-lenovo-thinksmart-view-lineageos.yaml) profile changes screen off from `brightness-zero` to `keyevent`. This makes the Home Assistant **Screen** control request Android sleep instead of moving brightness to the firmware's visible minimum.

The hardware test established the local behavior: touch does not wake the sleeping panel, while either physical volume button does. A direct `KEYCODE_WAKEUP` command did not work. ha-paneld's complete keyevent wake path also takes an unprivileged wakelock pulse, so the failed raw command does not prove that Home Assistant wake will fail, but that complete path must be tested after activation rather than assumed.

A PIN, pattern or password must not be configured. The keyevent route refuses to sleep a secured panel and dims it instead because waking into a credential screen can strand a wall-mounted device.

## Sensors and other hardware

The exported device draft contained both Android ambient-light and proximity observations, and Lenovo's product specification lists the same sensors. The profile therefore declares the ordinary Android sensor driver, without any device-specific thresholds or calibration. Confirm that both values respond to real changes after activation.

Lenovo lists camera and microphone hardware for ThinkSmart View configurations, but neither capture path has been proved on this community firmware. They remain disabled in this revision. The GPIO and Linux LED names in the diagnostic report are also left alone because no supported user-facing LED route has been established.

## First activation

Use ha-paneld 0.9.7-rc3 or later, then follow the [unofficial catalog procedure](README.md) to import [`community-lenovo-thinksmart-view-lineageos.yaml`](community-lenovo-thinksmart-view-lineageos.yaml), validate it and activate it while somebody is in front of the panel. After restart:

1. Confirm that the Profiles page shows `Unofficial Lenovo ThinkSmart View (LineageOS)` as active and does not roll back to Generic.
2. Turn **Screen** off from Home Assistant and confirm that Android sleeps fully.
3. Turn **Screen** on from Home Assistant and confirm that the dashboard returns without a lock screen. If this fails, use a physical volume button to recover.
4. Confirm that touch alone does not wake the panel, matching the original hardware test.
5. Check that ambient-light and proximity values respond to physical changes.
6. Exercise **Roll back** before relying on the profile unattended.

The first successful activation can move the reported firmware into `tested_firmware` and settle whether Home Assistant wake is reliable on this LineageOS build.
