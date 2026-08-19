# The built-in dashboard renderer

> [!NOTE]
> **Experimental (0.9).** The built-in renderer is the integrated path for dashboard entity filtering. The HA Companion app remains supported when a panel needs more than one Home Assistant server, Assist voice control or native notifications.

ha-paneld can display the Home Assistant dashboard in its own WebView instead of handing it to a separate dashboard app. This helps the panel return to its dashboard with less delay after an app restart. It can reopen the last dashboard that Home Assistant verified for the same server, account and Home dashboard setting while it checks the current dashboard list in the background. If Home Assistant reports that the dashboard was removed or the account default changed, the panel moves to the current choice.

Once the dashboard is running, ha-paneld can detect a stalled connection, release accumulated WebView memory and contain renderer crashes. The built-in connection also enables dashboard entity filtering. The panel remains a single-app appliance, with one APK to install, update and provision.

## Startup and recovery

The renderer uses Home Assistant's documented `?external_auth=1` interface, which is the same interface used by the HA Companion app. ha-paneld can therefore tell when the dashboard has connected instead of treating the page as a black box.

- Reopens the last verified dashboard after an app restart while refreshing Home Assistant's dashboard list in the background. A short compatibility check still runs first. The remembered route is tied to the Home Assistant server, account and configured dashboard, and an explicitly configured dashboard or dashboard tab remains authoritative.
- Freezes the page while the screen is off and resumes it on wake, saving roughly 70% of renderer CPU overnight.
- Reloads a dashboard that opened but never connected. Retries slow down after repeated failures, and the panel shows a clear "Reconnecting…" screen instead of a browser error page.
- Automatically retries recoverable checks with increasing delays. A permanently rejected login stops the retry loop and shows Browser sign-in instructions. An unsupported Home Assistant version or incompatible WebView names the required update and waits for it.
- Releases accumulated memory through invisible reloads while the screen is off.
- Contains and rate-limits renderer crashes. A page that continues to crash falls back to the admin launcher instead of restarting all night.
- When Home Assistant announces that it is stopping or goes offline through MQTT availability, the panel shows a native notice and clears it only after Home Assistant proves it is back.

You can pull down from the very top edge of the screen to refresh, or pull twice for a full reload. The renderer also supports an optional idle return to the Home dashboard, camera-stream autoplay and private-CA HTTPS using user-installed certificate authorities. **Hide Android system bars** provides an edge-to-edge dashboard; swipe from a screen edge to reveal the bars again. On panels using ha-paneld's software navigation bar, **Dashboard** brings the configured renderer to the foreground without reloading it. **Reload** remains a separate recovery action.

The renderer sizes the dashboard in the same way as the Home Assistant Companion app, so switching from Companion preserves the layout. **Dashboard zoom** adjusts the result, with 100% matching the Companion default. The renderer adds an **App Configuration** entry to the Home Assistant sidebar that opens the panel's configuration page. On first run it hides the docked sidebar and keeps the connection alive while idle. You can still open the sidebar or change these defaults later. The separate **Hide Home Assistant navigation (native)** option asks the frontend to remove its navigation while native kiosk mode is active.

## Requirements and compatibility

Starting with ha-paneld 0.9.6, the built-in renderer requires both:

- **Home Assistant 2026.4.2 or newer**; and
- an Android System WebView that supports the secure WebMessage listener used by Home Assistant's native-host interface.

Most users only need to install a current Android System WebView. ha-paneld checks the required WebView capability and verifies Home Assistant compatibility before it loads the dashboard. If the panel shows **Home Assistant upgrade required** or **Secure dashboard bridge unavailable**, update the named component and select **Retry**.

The built-in renderer does not fall back to the older, less isolated bridge. Another renderer may help when Home Assistant itself cannot be upgraded. The Companion app uses the same system WebView, so it cannot bypass an obsolete WebView on the panel.

## Turning it on

On a new or reset panel, open `http://<panel>:8888/setup` from a laptop or phone, or select **Set up** on the panel itself. The guided journey chooses the renderer, signs in to Home Assistant, selects the account default, a dashboard or a specific dashboard tab, and asks about the entity filter before the first dashboard load. Authorization happens in the administrator's browser, so credentials do not need to be typed on the panel.

On an existing panel, open the panel's `:8888` **Configure** page. Under **Home Assistant connection**, enter the Home Assistant URL and choose **Browser sign-in**, then select **Built-in renderer** as the Dashboard app.

Existing rooted installations that already imported a signed-in Companion session remain supported as a compatibility path. New installations should use Browser sign-in.

For unattended setup from an admin machine, replace the example panel address and Home Assistant details in this checkout-free command (see [Provisioning](provisioning.md)):

```bash
# First create an owner-only password file as shown in the linked provisioning guide.
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | \
  bash -s -- --provision 192.168.1.50:5555 --builtin \
  --ha-url https://homeassistant.example.com --ha-user your-user --ha-pass-file ha-password.txt
```

The password never reaches the panel because the login happens on your machine. The panel holds a revocable refresh token. A long-lived access token works too: `--ha-token-file ha-token.txt` instead of `--ha-user/--ha-pass-file`. See [Provisioning and fleet updates](provisioning.md) for securely creating credential files and the trusted-LAN transport boundary. Literal `--ha-pass` and `--ha-token` values remain compatibility options, but expose the value in the original shell command and process list.

For automated provisioning, a token or username/password flow remains available as an advanced fallback. Interactive installations should use Browser sign-in.

## Dashboard appearance versus Android lock

Advanced Configure exposes three independent controls. Each affects a different layer:

| Configure option | What it changes | What it does **not** change |
|---|---|---|
| **Hide Home Assistant navigation (native)** (on by default) | After Home Assistant connects, asks its native frontend to hide its navigation. Built-in renderer only. | Does not lock Android, hide Android system bars, or inject or modify dashboard CSS. If Home Assistant rejects or does not support the command, the dashboard is left unchanged. |
| **Hide Android system bars** (on by default) | Hides Android's status and navigation bars for an edge-to-edge dashboard. Swipe from an edge to reveal them. Built-in renderer only. | Does not prevent someone leaving the app and does not hide Home Assistant's own menus/navigation. |
| **Lock Android to dashboard (experimental)** (off by default) | With root, hides Android system bars and returns to the selected dashboard within about three seconds when another app or Recents opens. This is a casual-use deterrent, not an adversarial security boundary. | Does not change the Home Assistant dashboard appearance. It has no effect without root. Reboot provides a 60-second unlocked recovery window, then the saved lock is reasserted. |

For a cleaner dashboard, start with **Hide Home Assistant navigation (native)** and/or **Hide Android system bars**. Enable **Lock Android to dashboard** only when discouraging casual escape from the app is required and you have tested the documented release routes: Configure, the Home Assistant switch, adb, seven rapid taps in the top-left corner, or the unlocked window after reboot.

## Experimental entity filter

> [!WARNING]
> This is an opt-in tester feature. Automatic learning cannot prove every custom-card or dynamic-template dependency, and an incomplete entity set can leave cards missing or stale. Review it on a non-critical panel first and keep the filter-disable rollback available.

The filter applies only to ha-paneld's built-in renderer. It changes the frontend's Home Assistant subscription, so Home Assistant filters the states before serializing and sending them to the panel. The Companion app and other dashboard applications are unaffected.

### Automatic workflow

1. In `:8888` open **Configure → Dashboard**, select **Built-in renderer**, then enable **Automatic dashboard entity filter**.
2. Open the **Entities** tab and select **Scan dashboard now**.
3. Visit every dashboard tab and use its controls, pop-ups and conditional content so ha-paneld can observe runtime dependencies.
4. Review the current, suggested and excluded lists. Pin entities used indirectly by custom cards or templates, and resolve any entity-filter checks shown above the tables.
5. Select **Apply policy set** when the candidate is ready. ha-paneld shows the old and new entity counts before asking for confirmation, then reloads the dashboard with the filtered subscription.

The Entities page explains why each entity was found, records manual pin and exclusion choices, and keeps recognized broad or dynamic rules visible until the user fixes them or explicitly chooses how to proceed. Unrecognized behavior can still exist, so test every dashboard tab after activation. If anything is missing, turn off **Automatic dashboard entity filter** in Configure and reload before revising the candidate.

### Reset learned data

Use **Reset learned data** on the Entities page when obsolete dashboard evidence or earlier manual decisions make the candidate misleading. After explicit confirmation it clears learned dashboard membership and evidence, manual pin/exclude overrides, and ignored safety decisions. It preserves the known-good active filter, keeps the Home Assistant catalog used for candidate names, and starts a replacement scan when learning is enabled. This makes reset a rebuild operation rather than an immediate expansion back to the full Home Assistant state stream.

The stronger API reset below can also remove the stored active filter by sending `clear_filter:true`. Use it only when the filter itself must be discarded.

### Manual exact list

Advanced testers can bypass automatic learning and supply an exact list through the API. Create a JSON file containing every entity required by every dashboard tab, including entities referenced indirectly by custom cards or templates:

```json
{
  "enabled": true,
  "entity_ids": [
    "binary_sensor.front_door",
    "climate.living_room",
    "light.kitchen"
  ]
}
```

Upload the complete list to the panel:

```bash
PANEL_IP=192.0.2.10
curl --fail --show-error \
  --header 'Content-Type: application/json' \
  --data @entity-filter.json \
  "http://${PANEL_IP}:8888/api/v1/dashboard/entity-filter"
```

The built-in renderer reloads after an update. Once the dashboard has reconnected, inspect the status:

```bash
curl --fail --show-error \
  "http://${PANEL_IP}:8888/api/v1/dashboard/entity-filter"
```

A working filtered connection reports `enabled: true`, `runtime.active: true`, `runtime.mode: "native_socket"`, at least one `modifiedSubscriptions`, and zero `failures` and `directFallbacks`. A fallback means the dashboard remains connected but is receiving the ordinary unfiltered stream.

Posting `entity_ids` replaces the complete list. Keep your source JSON because the status endpoint deliberately returns only the count and a stable hash, and config exports do not include the entity IDs.

Disable filtering while retaining the stored list:

```bash
curl --fail --show-error \
  --header 'Content-Type: application/json' \
  --data '{"enabled":false}' \
  "http://${PANEL_IP}:8888/api/v1/dashboard/entity-filter"
```

Remove the stored filter, manual overrides, ignored safety decisions and rebuildable learning evidence with the confirmation-gated reset:

```bash
curl --fail --show-error \
  --header 'Content-Type: application/json' \
  --data '{"confirm":true,"clear_filter":true}' \
  "http://${PANEL_IP}:8888/api/v1/dashboard/entities/reset"
```

Like the rest of ha-paneld's control API, this endpoint is unauthenticated and intended for use on a trusted LAN.

## Theming

The dashboard follows the panel's dark/light preference by default. On Android 13+ this tracks the system setting live; on Android 9-12 the "Dark mode" toggle (Configure → Display) sets it. A theme picked *inside* Home Assistant always wins over the default.

## Reverting

Open Configure, select an installed Home Assistant Companion app under **Dashboard app** and save the change. The switch takes effect immediately. Do not select **Auto** for this purpose because Auto uses the built-in renderer when it is ready.

## Limits

- **No support for more than one Home Assistant server, Assist voice control or native notifications.** Keep the HA Companion on the panel where those matter.
- **No fullscreen media extras**, such as a file chooser or casting-style playback. These are permanently out of scope; use the Companion where they matter.
- A **current system WebView** is still required to render the Home Assistant frontend. ha-paneld can install a known-good WebView on supported rooted panels; an obsolete WebView produces a health warning in the `:8888` interface.
- Browser sign-in and advanced non-interactive provisioning work without root. Legacy Companion-session import requires root and remains only for existing installations.
