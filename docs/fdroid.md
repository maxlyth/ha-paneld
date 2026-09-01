# Installing ha-paneld via F-Droid

[F-Droid](https://f-droid.org/) is an open-source Android app store. ha-paneld publishes a small F-Droid **repository**, so you can add it and then install ha-paneld — and get every future update — **directly on the panel, with no PC and no adb**.

> [!NOTE]
> F-Droid installs the ha-paneld app but does not perform its provisioning. The built-in dashboard, MQTT auto-discovery framework, brightness and the HTTP control page do not require root. Discovery publishes only the sensors and controls supported by the active profile and live access probes; some proximity, climate, relay, button-LED, true screen-off, Zigbee and system-integration paths still need privileged access. See [Install](../README.md#install) to complete setup.

## 1. Get F-Droid onto the panel

- **Sonoff NSPanel Pro (firmware 4.0.0 or newer):** F-Droid is officially supported — install it from the panel's own software/app flow. See [the v4.0.0 note](hardware/nspanel-pro.md#firmware-v400--official-f-droid-app-install).
- **Any other panel / older firmware:** download the F-Droid app from [f-droid.org](https://f-droid.org/), turn on **"install unknown apps"** for your browser/file manager, and tap the APK to install. No browser on the panel? Push it once over adb: `adb install F-Droid.apk`.

## 2. Add the ha-paneld repository

In the F-Droid app, go to **Settings → Repositories → ➕ (add)** and enter:

```text
https://fdroid.ha-paneld.com/fdroid/repo?fingerprint=ac6193307fb0b70113aae205d7549406f96e063bc5491b67b1d5694a34b0e339
```

> [!TIP]
> Keep the whole URL including `?fingerprint=…` — it pins the repository to ha-paneld's signing key so nothing else can impersonate it. You can also open the [repo landing page](https://fdroid.ha-paneld.com/index.html) to copy the URL.

## 3. Install and update

Search for **ha-paneld** in F-Droid and install it. From then on F-Droid notifies you when a new version is out — tap to update, on the panel, no PC needed.

Only **stable** releases appear here; pre-releases (`…-rcN`) are intentionally excluded.

## After installing

- **Blank or broken dashboard?** Update the panel's system WebView first — this trips up almost everyone. See [Updating the system WebView](hardware/README.md#updating-the-system-webview).
- **Want the root features?** Set up `su` / provisioning — see [Install](../README.md#install).
- Prefer to sideload manually? The same signed APKs are on the [GitHub releases](https://github.com/maxlyth/ha-paneld/releases) page.

## How the repository works (maintainers)

The repo is rebuilt from the GitHub releases on every stable release and served from a dedicated Cloudflare R2 bucket. It's signed with the **same key as the release APKs**, so an F-Droid install cleanly updates a build that was sideloaded by hand (no signature clash). Maintainer setup + CI details: [`tools/fdroid/README.md`](../tools/fdroid/README.md).
