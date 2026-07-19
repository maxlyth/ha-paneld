# The built-in dashboard renderer

> [!NOTE]
> **Experimental (0.9).** The built-in renderer is the integrated path for dashboard entity filtering. The HA Companion app remains supported for panels that need Voice Assistant or native notifications.

Since 0.9, ha-paneld can render the Home Assistant dashboard itself, in its own WebView, instead of deferring to a separate dashboard app. A panel then runs as a single-app appliance: one APK to install, update and provision.

## What it is

A WebView inside ha-paneld pointed at your dashboard, authenticated with the frontend's documented `?external_auth=1` contract — the same interface the HA Companion uses. It is integrated with ha-paneld's own machinery rather than treated as a black box: the watchdog and kiosk lock know when the dashboard is actually connected, memory is bounded over long uptimes, and page-level failures are recovered on the panel.

Engineered for weeks-long unattended uptime:

- Freezes the page while the screen is off (roughly 70% of renderer CPU saved overnight), resumes on wake.
- A handshake watchdog reloads a dashboard that loaded but never actually connected, with backing-off retries behind a clean "Reconnecting…" screen instead of a browser error page.
- Memory is shed by invisible reloads at screen-off.
- Renderer crashes are contained and rate-limited — a reliably-crashing page falls back to the admin launcher rather than churning all night.
- Terminally rejected login settings latch and show fix-it instructions on the panel instead of retrying forever. To borrow a signed-in Companion login again, clear the Home Assistant server URL and save; otherwise enter a new long-lived access token from the Home Assistant user profile.

Also: instant pull-to-refresh (drag down from the very top edge of the screen; double-pull for a full reload), optional idle return-to-home, an edge-to-edge fullscreen mode (swipe from a screen edge to reveal the bars), camera-stream autoplay, and private-CA HTTPS (user-installed CAs are trusted). On panels using ha-paneld's software navigation bar, **Dashboard** brings the configured renderer to the foreground without reloading it; **Reload** remains a separate recovery action.

The renderer sizes the dashboard the same way the Home Assistant Companion app does, so a panel switched over from the Companion keeps its layout; the **Dashboard zoom** setting adjusts it (100% = the Companion default). It also adds an **App Configuration** entry to the Home Assistant sidebar that opens this panel's configuration page, and on first run it hides the sidebar and keeps the connection alive while idle — sensible defaults for a wall panel that you can still change afterwards.

## Turning it on

**On a rooted panel already running a signed-in HA Companion** — nothing to type. In the `:8888` **Configure** tab, set **Dashboard app → Built-in renderer**. It borrows the Companion's sign-in automatically (URL + tokens); the Companion keeps its own login, so switching back is the same picker change.

**On a fresh or Companion-less panel** — the simplest path is the panel's `:8888` **Configure** page. Select **Built-in renderer**, then enter the Home Assistant URL and a long-lived access token in the Dashboard card.

For unattended setup from an admin machine, replace the example panel address and Home Assistant details in this checkout-free command (see [Provisioning](provisioning.md)):

```bash
# First create an owner-only password file as shown in the linked provisioning guide.
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | \
  bash -s -- --provision 192.168.1.50:5555 --builtin \
  --ha-url https://homeassistant.example.com --ha-user your-user --ha-pass-file ha-password.txt
```

The password never reaches the panel — the login happens on your machine and the panel holds a revocable refresh token. A long-lived access token works too: `--ha-token-file ha-token.txt` instead of `--ha-user/--ha-pass-file`. See [Provisioning and fleet updates](provisioning.md) for securely creating credential files and the trusted-LAN transport boundary. Literal `--ha-pass` and `--ha-token` values remain compatibility options, but expose the value in the original shell command and process list.

Either way you can also set the URL and token by hand in the Configure tab's Dashboard card.

## Experimental entity filter

> [!WARNING]
> This is an opt-in tester feature. Automatic learning cannot prove every custom-card or dynamic-template dependency, and an incomplete entity set can leave cards missing or stale. Review it on a non-critical panel first and keep the filter-disable rollback available.

The filter applies only to ha-paneld's built-in renderer. It changes the frontend's Home Assistant subscription so filtering happens on the Home Assistant server before states are serialized and sent to the panel. The Companion app and other dashboard applications are unaffected.

### Automatic workflow

1. In `:8888` open **Configure → Dashboard**, select **Built-in renderer**, then enable **Automatic dashboard entity filter**.
2. Open the **Entities** tab and select **Scan dashboard now**.
3. Visit every dashboard tab and exercise controls, pop-ups and conditional content so runtime dependencies have a chance to be observed.
4. Review the current, suggested and excluded lists. Pin entities used indirectly by custom cards or templates, and resolve any entity-filter checks shown above the tables.
5. Select **Apply policy set** when the candidate is ready. ha-paneld shows the old and new entity counts before asking for confirmation, then reloads the dashboard with the filtered subscription.

The Entities page explains why each entity was found, records manual inclusions and exclusions, and keeps recognized broad or dynamic rules visible until the user fixes them or explicitly chooses how to proceed. Unrecognized behavior can still exist, so test every view after activation. If anything is missing, turn off **Automatic dashboard entity filter** in Configure and reload before revising the candidate.

### Reset learned data

Use **Reset learned data** on the Entities page when obsolete dashboard evidence or earlier manual decisions make the candidate misleading. After explicit confirmation it clears learned dashboard membership and evidence, manual pin/exclude overrides, and ignored safety decisions. It preserves the known-good active filter, keeps the Home Assistant catalog used for candidate names, and starts a replacement scan when learning is enabled. This makes reset a rebuild operation rather than an immediate expansion back to the full Home Assistant state stream.

The stronger API reset below can additionally remove the stored active filter by sending `clear_filter:true`. Use that only when the filter itself must be discarded.

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

Set **Dashboard app** back to the HA Companion (or blank it) in the Configure tab. The renderer switch takes effect immediately.

## What it deliberately does not do

- **No Voice Assistant (Assist)** and **no notifications** — keep the HA Companion on the panel where those matter.
- **No fullscreen media niceties** (file chooser, casting-style playback) — permanently out of scope; the answer there is the Companion.

## Requirements and limits

- A **system WebView** new enough to render the HA frontend. ha-paneld can auto-install a known-good WebView on rooted panels; a too-old WebView shows a health warning in the `:8888` UI.
- Provisioning a **fresh** sign-in (username/password or token) works on any panel; the **automatic Companion borrow** needs root and an installed, signed-in Companion.
