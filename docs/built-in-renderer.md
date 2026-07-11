# The built-in dashboard renderer

> [!NOTE]
> **Experimental (0.9).** The built-in renderer is off by default and marked *skunk-works* in the UI. The HA Companion app remains the default and a permanently supported choice. Try the renderer if you want a single-app panel; keep the Companion if you need Voice Assistant or notifications.

Since 0.9, ha-paneld can render the Home Assistant dashboard itself, in its own WebView, instead of deferring to a separate dashboard app. A panel then runs as a single-app appliance: one APK to install, update and provision.

## What it is

A WebView inside ha-paneld pointed at your dashboard, authenticated with the frontend's documented `?external_auth=1` contract — the same interface the HA Companion uses. It is integrated with ha-paneld's own machinery rather than treated as a black box: the watchdog and kiosk lock know when the dashboard is actually connected, memory is bounded over long uptimes, and page-level failures are recovered on the panel.

Engineered for weeks-long unattended uptime:

- Freezes the page while the screen is off (roughly 70% of renderer CPU saved overnight), resumes on wake.
- A handshake watchdog reloads a dashboard that loaded but never actually connected, with backing-off retries behind a clean "Reconnecting…" screen instead of a browser error page.
- Memory is shed by invisible reloads at screen-off.
- Renderer crashes are contained and rate-limited — a reliably-crashing page falls back to the admin launcher rather than churning all night.
- A definitively revoked login latches and shows fix-it instructions on the panel instead of retrying forever.

Also: instant pull-to-refresh (drag down from the very top edge of the screen; double-pull for a full reload), optional idle return-to-home, an edge-to-edge fullscreen mode (swipe from a screen edge to reveal the bars), camera-stream autoplay, and private-CA HTTPS (user-installed CAs are trusted).

The renderer sizes the dashboard the same way the Home Assistant Companion app does, so a panel switched over from the Companion keeps its layout; the **Dashboard zoom** setting adjusts it (100% = the Companion default). It also adds an **App Configuration** entry to the Home Assistant sidebar that opens this panel's configuration page, and on first run it hides the sidebar and keeps the connection alive while idle — sensible defaults for a wall panel that you can still change afterwards.

## Turning it on

**On a rooted panel already running a signed-in HA Companion** — nothing to type. In the `:8888` **Configure** tab, set **Dashboard app → Built-in renderer**. It borrows the Companion's sign-in automatically (URL + tokens); the Companion keeps its own login, so switching back is the same picker change.

**On a fresh or Companion-less panel** — provision the sign-in from your admin machine (see [Provisioning](provisioning.md)):

```bash
scripts/provision.sh <panel-ip:5555> --builtin --ha-url https://ha.example --ha-user USER --ha-pass PASS
```

The password never reaches the panel — the login happens on your machine and the panel holds a revocable refresh token. A long-lived access token works too: `--ha-token LLAT` instead of `--ha-user/--ha-pass`.

Either way you can also set the URL and token by hand in the Configure tab's Dashboard card.

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
