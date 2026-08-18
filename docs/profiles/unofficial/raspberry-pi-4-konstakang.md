# Unofficial Raspberry Pi 4 KonstaKANG LineageOS profile notes

> [!IMPORTANT]
> This is an import-only community profile based on one contributor's diagnostic report in GitHub [#107](https://github.com/maxlyth/ha-paneld/issues/107). It describes a Raspberry Pi 4 running a community Android build with a separately sourced HDMI touchscreen — an assembly, not a retail wall panel. It is not bundled, automatically selected, maintainer-validated or fleet-qualified.

The reported device is a Raspberry Pi 4 Model B Rev 1.4 running [KonstaKANG's](https://konstakang.com/devices/rpi4/) LineageOS build of Android 16, rooted with Magisk, driving a third-party touchscreen over HDMI. Because the display is chosen by the owner rather than supplied with the board, nothing about its size, density or wake behaviour is a property of the Pi, and the profile declares none of it.

| | |
|---|---|
| Profile fingerprint | device `rpi4` plus a model containing `4 model b` |
| SoC | Broadcom BCM2711, four cores |
| Android | KonstaKANG LineageOS / Android 16 (API 36), build `BP4A.251205.006`, userdebug |
| ABI | arm64-v8a |
| Display | 1920×1080 at 240 dpi logical; physical size unknown and owner-supplied |
| RAM / storage | 7.6 GB / 128 GB |
| Root | Magisk; app-accessible Android-style `su`, and the ha-paneld root helper running |
| Sensors | none reported — no ambient light, no proximity, no IIO devices |
| LED / relays / Zigbee | none. The `ACT` and `PWR` board LEDs are not panel indicators and are not driven |
| Screen off | `keyevent` — this board exposes no `/sys/class/backlight` device at all |

## Profile scope

The [`community-rpi4-konstakang-lineageos.yaml`](community-rpi4-konstakang-lineageos.yaml) profile changes exactly one behaviour: it selects `hardware.screen_off: keyevent`. Everything else it declares is either identity or a conservative restatement of what ha-paneld already establishes for itself.

That single declaration is the reason the profile exists. This board has no Linux backlight node, so the default brightness-zero route has no real actuator and the panel can only dim. The `keyevent` route instead puts Android itself to sleep with `KEYCODE_SLEEP`, which is a different state with different rules, and one of them cannot be probed: Android delivers touches to a sleeping device only where the touchscreen is wired as a platform wake source. On owner-supplied hardware that is unknowable without trying it, which is why it must be declared by a person who has checked rather than inferred by the app.

Root is declared as evidence, not as authority. After activation the running driver still probes the live route, and a missing or blocked route stays unavailable.

## Before you rely on it

Follow the [unofficial catalog procedure](README.md) to import and validate, then the [staged testing checklist](../testing.md). Stage 4 is the one that matters here, and it must be done with somebody standing at the panel:

1. Put the screen to sleep from Home Assistant.
2. **Touch the panel.** If it wakes, this profile suits the hardware. If it does not, Home Assistant is the only way back, and you should decide whether that is acceptable before leaving the panel unattended.
3. Wake it from Home Assistant and confirm the dashboard returns rather than a lock screen.
4. Exercise **Roll back** before relying on the profile.

A panel with a PIN, pattern or password configured is refused outright and dims instead, because nobody types a credential on a wall panel.

If a touch does not wake the panel and you would rather keep the previous behaviour, edit two lines and save a new revision: set `hardware.screen_off` to `brightness-zero`, and in `requires.drivers` replace `screen.keyevent` with `screen.brightness-zero`. It is a swap rather than a deletion — a capability's driver must be listed, so removing the entry without replacing it fails validation. Bump the profile `version` at the same time, because that is a behavioural change.

## Limitations

- Reflashing, recovery and maintaining the community Android image are outside the ha-paneld project. No installation instructions or recovery images are supplied here.
- Matching sees only the model, device and product-version build facts, so it cannot tell KonstaKANG's LineageOS image apart from the AOSP or Android TV images for the same board. Those are untested; confirm you are running the LineageOS build before pinning.
- The profile does not describe the Raspberry Pi 5, the Pi 400 or the Compute Module 4.
- Reboot behaviour on this board needed no profile field. ha-paneld's root helper no longer treats a command's exit status as proof that a panel is going down, which is what made `svc power reboot` appear to succeed here without rebooting.
- The board's `ACT` and `PWR` LEDs are status indicators for the computer, not a panel RGB LED, and are deliberately left alone.

Review a fresh on-panel diagnostic report and confirm every expected capability after restart before relying on this profile unattended.
