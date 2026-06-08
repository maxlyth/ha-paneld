# Install from a phone (no PC)

ha-paneld installs over **network ADB**, which needs no laptop — a phone on the same Wi-Fi can do the
whole thing. Two routes below; the first one fully provisions.

> [!NOTE]
> The hard part isn't installing the APK — it's **granting permissions** (screen brightness + the
> button accessibility service) without tapping through on a wall-mounted panel. That needs an ADB
> *shell*, which is why **Termux** (Route A) provisions completely; a GUI installer gets the APK on but
> usually leaves the permissions for you to grant on the panel.

> [!WARNING]
> Not yet validated end-to-end on a handset — the steps follow from how the installer works, but please
> report back. Contributions/corrections welcome.

## 1. Enable wireless debugging on the panel (once)

Every supported panel is Android 11+, which supports USB-free debugging:

1. **Settings → About** → tap the build number **7×** to unlock Developer options.
2. **Developer options → Wireless debugging** → on → **Pair device with pairing code**. Note the
   **IP:port** and **6-digit code** it shows (the *pairing* port differs from the *connect* port).

## 2a. Route A — Termux (full install, recommended)

[Termux](https://termux.dev) is a free terminal app that runs the *same* installer a laptop would.

1. Install Termux from [F-Droid](https://f-droid.org/packages/com.termux/) (the Play Store build is
   outdated).
2. In Termux:

   ```bash
   pkg update && pkg install android-tools curl
   adb pair <panel-ip>:<pair-port>     # type the 6-digit pairing code
   adb connect <panel-ip>:5555         # or the wireless-debug connect port shown on the panel
   curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash
   ```

   The installer detects the connected panel, installs the APK, **grants the permissions**, and starts
   the agent — exactly as from a laptop.

> [!TIP]
> If the one-liner can't see the panel, do it by hand: download the
> [latest APK](https://github.com/maxlyth/ha-paneld/releases/latest), then
> `adb install -r <apk>` and grant the two permissions —
> `adb shell appops set io.github.maxlyth.hapaneld WRITE_SETTINGS allow`, and enable the **ha-paneld**
> accessibility service (panel Settings → Accessibility, or the app's setup screen).

## 2b. Route B — atvTools (GUI, partial)

[atvTools](https://play.google.com/store/apps/details?id=dev.vodik7.atvtools) is a phone app that pushes
APKs to an Android panel over the same network ADB — friendlier if you'd rather avoid a terminal.

1. Install atvTools on your phone; enable wireless debugging on the panel (step 1).
2. Let it detect the panel, then install the
   [latest ha-paneld APK](https://github.com/maxlyth/ha-paneld/releases/latest).

> [!CAUTION]
> atvTools installs the APK but doesn't run the permission-granting shell commands, so afterwards you
> still need to grant **Modify system settings** and the **accessibility** service on the panel (the
> app's setup screen guides this), or finish with the Termux grants above.

## 3. Finish

From your phone's browser, open `http://<panel-ip>:8888/` to set the MQTT broker (or let it
auto-discover Home Assistant on the LAN), and if the dashboard looks broken, update the panel's
[system WebView](hardware/README.md#updating-the-system-webview).
