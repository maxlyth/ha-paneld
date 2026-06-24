# Matching dashboard size to a desktop browser (display sizing)

A Home Assistant dashboard designed in a desktop browser often renders at the wrong size on an Android wall panel. ha-paneld exposes two controls — **density (DPI)** and **text size (font scale)** — on the on-device config page so you can bring a panel's layout and text back in line with the desktop. You usually need both, and good values are a per-install preference rather than a per-model constant.

> [!WARNING]
> **Experimental / R&D — pre-release.** The control works, but the *right* values for each panel aren't dialled in yet. Treat the density/text-size controls as something to experiment with, not a finished feature. Feedback on good per-panel values is welcome.

## Using it

ha-paneld exposes both levers on the on-device config page (the **Display sizing** card) — set a custom density and text size, apply (live), or reset to native. Both persist across reboot (they're secure/system settings).

> [!NOTE]
> **Needs root or the helper daemon.** The card appears on su-reachable panels (NSPanel Pro PX30, WF1589T) and on sandbox-walled panels running the [root helper daemon](../helper/README.md) (TPA10) — there both density and text size route through the daemon. It's absent only where neither is available.

To tune: open the same dashboard on your desktop browser for reference, then lower the density until the layout matches (more cards fit), and nudge the text size until text matches. Good values are a **per-install** preference (panel resolution, viewing distance, how the dashboard is designed), not a fixed per-model constant — which is why no canonical defaults ship yet.

## The two levers

| Lever | What it controls | Mechanism |
| --- | --- | --- |
| **Density (DPI)** | The whole *layout* — the effective dp viewport is `physical px ÷ (density / 160)`. Lower dpi → more fits (closer to a desktop's wider viewport); higher → larger. | `wm density <n>` |
| **Text size (font scale)** | *Text* in the dashboard WebView — Android's system font scale becomes the WebView `textZoom` (`textZoom = scale × 100`). | `settings put system font_scale <f>` |

Adjusting density alone scales layout but can leave text mis-sized relative to a desktop; the system font scale corrects the text. Tuning the two together is what brings a panel in line with the desktop browser the dashboard was designed in.

## Why panels need this

A dashboard designed in a **desktop browser** often renders at the wrong size on an Android wall panel — cards too large with clipped edges, or too small. An **iPhone** stays broadly in sync with the desktop; Android panels frequently don't, because panel manufacturers ship a system **density** and **font scale** that aren't well matched to the physical display.

> [!NOTE]
> `DeviceProfile.recommendedDensity` / `recommendedFontScale` are the per-panel hooks for an "HA-optimised" one-click preset once values are calibrated (currently unset → the control offers only custom + native-reset).
