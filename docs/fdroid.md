# Installing ha-paneld via F-Droid

[F-Droid](https://f-droid.org/) is an open-source Android app store. ha-paneld publishes a small F-Droid **repository**, so you can add it and then install ha-paneld — and get every future update — **directly on the panel, with no PC and no adb**.

> [!NOTE]
> This installs ha-paneld, the headless agent. The non-root features (MQTT auto-discovery, sensors, brightness, the HTTP control/info page) work straight away. The **root-gated** features (relays, button LEDs, true screen-off, Zigbee router, the on-screen nav bar) still need `su` on the panel — F-Droid only handles delivery, not rooting. See [Install](../README.md#install) for provisioning.

## 1. Get F-Droid onto the panel

- **Sonoff NSPanel Pro (firmware 4.0.0 or newer):** F-Droid is officially supported — install it from the panel's own software/app flow. See [the v4.0.0 note](hardware/nspanel-pro.md#firmware-v400--official-f-droid-app-install).
- **Any other panel / older firmware:** download the F-Droid app from [f-droid.org](https://f-droid.org/), turn on **"install unknown apps"** for your browser/file manager, and tap the APK to install. No browser on the panel? Push it once over adb: `adb install F-Droid.apk`.

## 2. Add the ha-paneld repository

In the F-Droid app, go to **Settings → Repositories → ➕ (add)** and enter:

```text
https://maxlyth.github.io/ha-paneld/fdroid/repo?fingerprint=ac6193307fb0b70113aae205d7549406f96e063bc5491b67b1d5694a34b0e339
```

> [!TIP]
> Keep the whole URL including `?fingerprint=…` — it pins the repository to ha-paneld's signing key so nothing else can impersonate it. You can also open the [repo landing page](https://maxlyth.github.io/ha-paneld/) and scan its QR code instead of typing the URL.

## 3. Install and update

Search for **ha-paneld** in F-Droid and install it. From then on F-Droid notifies you when a new version is out — tap to update, on the panel, no PC needed.

Only **stable** releases appear here; pre-releases (`…-rcN`) are intentionally excluded.

## After installing

- **Blank or broken dashboard?** Update the panel's system WebView first — this trips up almost everyone. See [Updating the system WebView](hardware/README.md#updating-the-system-webview).
- **Want the root features?** Set up `su` / provisioning — see [Install](../README.md#install).
- Prefer to sideload manually? The same signed APKs are on the [GitHub releases](https://github.com/maxlyth/ha-paneld/releases) page.

## How the repository works (maintainers)

The repo is rebuilt from the GitHub releases on every stable release and served from GitHub Pages. It's signed with the **same key as the release APKs**, so an F-Droid install cleanly updates a build that was sideloaded by hand (no signature clash). Maintainer setup + CI details: [`fdroid/README.md`](../fdroid/README.md).
