# Taming intrusive vendor packages

A firmware update can leave a vendor app on the panel that relaunches itself on boot and draws a floating widget over your dashboard. On the NSPanel Pro 120P, for example, a Sonoff firmware update left `com.eWeLinkControlPanel` starting on boot and overlaying a control widget on top of whatever ha-paneld was showing. ha-paneld can neutralise packages like this — but only ones you explicitly name.

## What it does

For each package on your blocklist, ha-paneld applies three reversible, privileged actions on boot:

| Step | Mechanism | Effect |
| --- | --- | --- |
| Force-stop | `am force-stop <pkg>` | Kills the app now so its widget disappears immediately. |
| Disable boot-relaunch | `pm disable-user --user 0 <pkg>` | Stops it starting again on the next boot. Reversible with `pm enable <pkg>`. |
| Block overlays | `appops set <pkg> SYSTEM_ALERT_WINDOW deny` | Strips its permission to draw a floating window over the dashboard. |

All three are privileged, so the feature needs **root or the [helper daemon](../helper/README.md)** — which means it also works on sandbox-walled panels (the TPA10), where the actions route through the daemon's `STOP` / `DISABLE` / `OVERLAY` verbs.

## Using it

Open the on-panel config page (`http://<panel-ip>:8888/`), find **Tame vendor packages** in the Configure card, and list the package names you want gone — one per line, or space/comma-separated. Save, and they're tamed on the next service start (and every boot after).

The feature is **off by default**: the blocklist ships empty, and ha-paneld touches nothing until you deliberately add a package. To find a package name, the panel's own app list or `pm list packages` over adb will show it; the eWeLink control panel is `com.eWeLinkControlPanel`.

## Safety

- **Reversible.** Disabling is undone with `pm enable <pkg>` (or by an app/firmware update). Nothing here is destructive — no uninstall, no data wipe.
- **Critical packages are refused.** ha-paneld will never stop or disable the system UI, Settings, telephony, the Android framework, or itself, even if you list one — both the app and the privileged daemon enforce this, so a typo can't brick the panel.
- **Only installed packages are touched**, and only the ones you name. Removing a package from the list does not auto-re-enable it (re-enable it yourself with `pm enable` if needed).

> [!WARNING]
> You're naming packages to disable. Stick to vendor bloat you recognise. Disabling a package the panel depends on (a vendor launcher you actually use, an input method, etc.) can leave the panel hard to navigate until you re-enable it over adb. When in doubt, leave it off.
