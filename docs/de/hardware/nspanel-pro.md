> [!IMPORTANT]
> Dieses Dokument wurde maschinell erstellt und automatisch gegengeprüft, jedoch nicht systematisch von Personen geprüft, die diese Sprache sprechen. Die englische Dokumentation ist maßgeblich. [Englisches Original lesen](../../hardware/nspanel-pro.md) oder [ein Issue zur Übersetzungskorrektur öffnen](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Sonoff NSPanel Pro (Rockchip PX30 / rk3326)

Das ursprüngliche NSPanel Pro ist ein kleines PX30-Panel im **quadratischen 480×480-Format** mit integriertem **Zigbee-3.0-Koordinator**, ohne NFC/IR und mit der leistungsschwächsten CPU der hier dokumentierten Panels. Seine Varianten **86P** und **120P** verwenden unterschiedliche Displays und Platinen – siehe [Varianten](#varianten--86p-und-120p). Diese Seite wurde hauptsächlich anhand eines laufenden **86P** (Android 8.1, gerootet, Toolbox `su`) durch Reverse Engineering erstellt und behandelt diese ursprünglichen Varianten, sofern nicht ausdrücklich anders angegeben.

> [!TIP]
> Most-needed facts: ships **`userdebug` with no adb password** (`adb root` just works); **LED is not characterised** (no controllable RGB node found); light + proximity are **app-direct**; the on-board **EFR32 Zigbee radio** is managed over a local broker, not by reflashing. Update the **WebView first** — see [WebView — update this first](#webview--zuerst-aktualisieren).

| | |
|---|---|
| SoC | Rockchip **PX30 / rk3326** |
| CPU | 4× **Cortex-A35** mit bis zu **1,512 GHz** (im Leerlauf 408 MHz) |
| GPU | **Mali-G31** (am Gerät bestätigt) |
| Display | **Quadratisch, 480×480** (1:1), ~4 Zoll, 160 dpi (mdpi, passend zu den physischen ~170 ppi), 60 Hz → eine **480×480-dp**-Zeichenfläche |
| RAM | **2 GB** (≈1960 MB nutzbar) |
| Speicher | eMMC; `/data` ≈ 3,5 GB |
| Android | 8.1 (API 27) |
| ABI | arm64-v8a |
| Funkmodule | **Zigbee 3.0** (Silicon-Labs-EFR32-Koordinator an UART `ttyS5` – siehe unten), WLAN, Bluetooth. Kein NFC, IR, Ethernet oder Mobilfunk. |

> [!NOTE]
> Der Cortex-A35 ist ein Effizienzkern mit deutlich geringerem Durchsatz pro Takt als der A55 (TPA10) oder A72 (WF1589T). Zusammen mit 2 GB RAM ist das NSPanel Pro das **Einsteigermodell hinsichtlich der Leistung** unter den drei hier dokumentierten Panels – siehe den [Leistungsvergleich](README.md#leistungsvergleich-und-praktische-bereitstellung).

> [!TIP]
> Changing firmware on a button-less panel? Read [Firmware backup & restore](../../firmware-backup-restore.md) first — the NSPanel Pro (PX30) uses [seaky's roottool/tools](../../firmware-backup-restore.md#per-panel-notes) rather than `rkdeveloptool`.

## Varianten – 86P und 120P

Die ursprüngliche NSPanel-Pro-Reihe wird in zwei physisch unterschiedlichen Panels angeboten, benannt nach der **86-mm**- bzw. **120-mm**-Wanddose für den EU-Markt. Die obige Spezifikationstabelle und der Großteil dieser Seite wurden an einem **86P** erfasst; das **120P** verwendet eine andere Platine:

| | NSPanel Pro **86P** | NSPanel Pro **120P** |
|---|---|---|
| SoC | Rockchip **PX30** | Rockchip **RK3326-S** (dieselbe PX30/RK3326-Familie; `ro.board.platform=rk3326`, Gerätebaum `rockchip,px30`) |
| Display | **480×480** quadratisch, ~160 dpi, nur Hochformat | **750×1334** im Hochformat, **240 dpi** (Override 250); Querformat verfügbar; ~1 cm schmaler und länger als das 86P |
| Build-IDs | Beide melden `ro.product.model/device/name = px30_evb` (gemeinsamer Rockchip-Platinenname – *kein* zuverlässiges Unterscheidungsmerkmal für die Variante) | wie 86P |
| `ro.product.version` | `s6_android_x.y.z`-Klasse | `NSPanelXXXP_x.y.z` (OTA-Kanal `nspanel-pro-ver120`, vollständiges ROM `SN_3326S_750X1334_…`) |
| OTA-Form | vollständiges ROM bis einschließlich **4.0.12**; danach indexierte Releases werden als Diffs oder reine APK-Updates ausgeliefert (siehe [Firmware-Index](../../hardware/nspanel-pro-firmware.md)) | wie 86P |
| Näherungssensor-Firmware | **4.0.12 stellte abgestufte** Messwerte wieder her | blieb unter 4.x **binär** (Kernel-Abweichung zwischen den Modellen – siehe [Sensoren](#sensoren--licht-und-näherung-sind-direkt-aus-der-app-zugänglich)) |

Beide besitzen das EFR32-Zigbee-Funkmodul, Android 8.1 (AOSP), arm64-v8a sowie die nachfolgend beschriebene Root- und Wiederherstellungsumgebung. Live auf einem 120P (Firmware `NSPanel120P_3.7.1`) verifiziert: `wm size`=750×1334, Dichte 240, `ro.board.platform=rk3326`.

> [!NOTE]
> This page does not crown a firmware version in prose — the generated [complete index](../../hardware/nspanel-pro-firmware-archive.md) is the authority, and it goes stale less often. The flashing procedure is hardware-verified through **4.4.0**; releases indexed past that are CDN-verified only, never live-flash verified here. As of 2026-08-14, for the most recently added of them no vendor documentation was found: [Sonoff's public changelog](https://sonoff.tech/en-us/blogs/news/sonoff-nspanel-pro-version-update-information-and-faq) documents up to **4.6.0**, 4.6.2 and 4.8.0 were located only by probing the CDN, and 4.7.0 is discussed only in an [eWeLink user feedback thread](https://forum.ewelink.cc/t/nspanel-pro-v4-7-0-feeback/208789) — a discussion thread, not a release announcement. The **4.5.3** release is a ROM diff on 120P but an APK-only update on 86P, and **4.6.2** is an app-only update with no ROM diff on either channel, so an upgrade is not always a single hop. Absence from the index means not-found-by-probe; the CDN cannot be listed, so it is never proof a build does not exist. The CoolKit CDN scheme and the full flashing how-to are on the [firmware & flashing page](../../hardware/nspanel-pro-firmware.md); every verified OTA URL is in the [complete index](../../hardware/nspanel-pro-firmware-archive.md), and the community-facing subset is the Discussion linked from there, which is regenerated from this repo's data files and can lag them.
>
> **⚠ Community reports describe restart loops on 4.5.1 / 4.5.2** (~10–60 min intervals on both models). For 4.7.0, the user feedback thread contains reports of sub-device connectivity trouble after updating, some resolved by a reboot and others described as continuing; this project has not reproduced or quantified them, so treat them as unverified user reports rather than a known regression. Verify any newer release on one panel before deploying widely; **4.0.12** remains the conservative full-ROM checkpoint to pin. The firmware Discussion carries the current community evidence, regenerated from this repo's index whenever the scheduled monitor next runs.

### Firmware-Besonderheiten nach Version

Behaviour that changes across eWeLink firmware versions, oldest first. `ro.product.version` is the **internal** id (`s6_android_x.y.z` on the 86P / `NSPanelXXXP_x.y.z` on the 120P) — *not* the marketing/OTA number the eWeLink app shows (4.0.12, 4.5.x). Detection and any version-keyed logic must read `ro.product.version`, not the marketing string.

| Firmware | Besonderheit / Verhalten | Auswirkung – was zu tun ist |
|---|---|---|
| **älter (vor 1.3.2)** | Kein adb-Schalter in der App; Entwickleroptionen über die Benutzeroberfläche nicht erreichbar | Enable adb via the internal **OTG port** (open the case) — [Gaining adb + root](#adb--und-root-zugriff-erlangen). |
| **v1.3.2+** | Die adb-Aktivierung wurde in die eWeLink-App verschoben | eWeLink → *Device Settings* → tap **Device ID ×8** → developer mode → adb. |
| **v1.4+** | Entwicklermodus aus der Benutzeroberfläche **entfernt** (nicht mehr verfügbar) | Enable adb via the **5× power-cycle** at the Sonoff boot animation — [Gaining adb + root](#adb--und-root-zugriff-erlangen). |
| **3.5.1 (86P, verifiziert)** | Das vorinstallierte System-WebView ist **Chromium 107.0.5304.105** – viel zu alt, um ein modernes HA-Dashboard darzustellen; andere Firmware kann abweichen | Check and update the WebView **first** — [WebView — update this first](#webview--zuerst-aktualisieren). ha-paneld's panel-health banner also flags outdated versions (min Chromium 110). |
| **v3.7.1** (120P, live) | Referenz-Build als Ausgangsbasis | `wm size`=750×1334, Dichte 240, `ro.board.platform=rk3326`. |
| **v4.0.0** (Einführung am 19.09.2025) | Die Original-Firmware **enthält F-Droid** und bewirbt die Installation von FOSS-/HA-Apps; deutlich schnellere Benutzeroberfläche | On-device install path opens — [Firmware v4.0.0](#firmware-v400--offizielle-app-installation-über-f-droid). Confirm **APP** *and* **OS** version both read ≥ 4.0.0. |
| **v4.0.12** | Näherung: **abgestufte Messwerte** beim **86P** wiederhergestellt; das **120P bleibt binär** (Kernel-Abweichung zwischen den Modellen) | Recommended stable pin for HA-only panels. The panel's raw input shape is model- and firmware-specific, but ha-paneld learns and normalizes either form — see [Sensors](#sensoren--licht-und-näherung-sind-direkt-aus-der-app-zugänglich). |
| **v4.5.1 / v4.5.2** | **Widespread community restart-loop reports** (~10–60 min, both models); 4.5.2 is an APK-only layer on 4.5.1 | **Superseded by later releases.** Pin at **4.0.12** for maximum stability, or test a newer release on one panel first. Check the firmware Discussion for current evidence. |
| **v4.5.3** | Automatische Matter-Erkennung und Optimierungen der Bildschirmverwaltung; ROM-Diff beim 120P, beim 86P dagegen nur ein APK-Update | No 4.5.3-specific restart-loop evidence found; superseded by later releases. |
| **v4.6.0** (Juni 2026) | **Lokales Webportal** (`nspanelpro.local` – Einrichtung im LAN, MQTT-Discovery-Export nach HA, Matter Bridge); bei der CDN-Prüfung des Projekts wurden Diffs von 4.0.12 / 4.4.0 / 4.5.1 gefunden | In Sonoffs öffentlichem Änderungsprotokoll dokumentiert. **4.6.2** ist als reines App-Update ohne ROM-Diff auf beiden Kanälen indexiert; 4.6.1 wurde nicht gefunden. |
| **v4.7.0** (Juli 2026) | In einem eWeLink-Feedback-Thread besprochen, aber in Sonoffs öffentlichem Änderungsprotokoll **nicht enthalten**; gilt für Panels der 1. und 2. Generation; Benutzer melden neu hinzugefügte Unterstützung für Basic-Relais der 5. Generation (BASIC-1GS); die CDN-Prüfung des Projekts fand bei beiden Modellen eingehende Diffs von 4.0.12 / 4.4.0 / 4.5.1 / 4.6.0 sowie zusätzlich von 4.5.3 nur beim 120P | Community reports of sub-device connectivity trouble, some reboot-resolved and some described as continuing; unverified by this project. Verify on one panel before deploying widely. |
| **v4.8.0** (August 2026) | **Keine Release-Ankündigung und kein Änderungsprotokoll gefunden** – durch Abfragen des CDN entdeckt. Ein eWeLink-Mitarbeiterbeitrag vom 16.07.2026 im [Roadmap-Thread](https://forum.ewelink.cc/t/nspanel-pro-roadmap-and-co-created-future/206240) kündigte die Version für August an und bestätigte eine Funktion: eine Option zur automatischen Aktualisierung des Panels über die eWeLink-App. Die CDN-Prüfung des Projekts fand bei beiden Modellen eingehende Diffs von 4.0.12 / 4.4.0 / 4.5.1 / 4.6.0 / 4.7.0 sowie zusätzlich von 4.5.3 nur beim 120P | Contents otherwise unknown, and no community feedback thread has been found, so there is no report either way on stability. Treat it as unassessed rather than clean. **If the auto-update option ships enabled, a panel could take firmware unattended** — check that setting before relying on a pinned version. |

> [!NOTE]
> These are original 86P/120P quirks. The NSPanel Pro **Gen2** (RK3326-**S**, dual relays, EFR32**MG24**) is a different hardware target. Sonoff ships Gen1 and Gen2 on the same firmware version line (4.7.0 covers both), so do not infer a separate firmware line or assume every original-model note carries over.

Sibling Tuya-family boards — **S6E/T6E** (relay variants; S6E = T6E + 2 relays), [**S9E**](../../hardware/s9e.md) (Smatek), [**TPA10**](tpa10.md) (RK3566, Cortex-A55, Android 11) — are separate targets, not NSPanel Pro firmware.

> [!CAUTION]
> Detection can't rely on `ro.product.model` (both are `px30_evb`). Use `ro.product.version` / display metrics / `ro.board.platform` to tell 86P from 120P. Proximity behavior also differs between models and firmware, so ha-paneld learns from the live readings instead of selecting a firmware-specific classifier.

## adb- und Root-Zugriff erlangen

Unlike the TPA10, the NSPanel Pro has **no adb password** — it ships as a `userdebug` / test-keys build (`ro.debuggable=1`), so `adb root` works and `/system` is remountable. The hard part is only *reaching* developer options, which the eWeLink firmware hides differently per version. Distilled from blakadder's guides ([sideload](https://blakadder.com/nspanel-pro-sideload/), [secrets](https://blakadder.com/nspanel-pro-secrets/)).

**1. Enable adb** — the route depends on firmware:

- **Older firmware** — open the case (back screws, disconnect the touch connector) to expose the OTG USB port and connect a host; adb works directly over USB.
- **Firmware v1.3.2+** — in the **eWeLink app** → the panel's *Device Settings*, tap the **Device ID 8×** to enable developer mode, which restores adb.
- **Firmware v1.4+** (developer mode removed) — power-cycle the panel **5×** during the Sonoff boot animation to force a recovery boot, and in that window `adb install ultra-small-launcher.apk`; after reboot set that launcher as default, then *Settings → System → About tablet → Build number* ×7 to re-enable developer options and turn on USB debugging.

**2. Go to network adb** (so you don't need the case open):

```bash
adb tcpip 5555
adb shell ip -o a            # find the panel IP
adb connect <panel-ip>:5555
adb shell su 0 setprop persist.adb.tcp.port 5555   # survive reboot (service prop resets)
```

**3. Root.** Because the build is `userdebug`, `adb root` gives a root adbd shell immediately. ha-paneld calls `su` from the app sandbox, so install a persistent `su` into `/system` (this fleet's panels carry **SuperSU `su` 2.76** at `/system/xbin/su`):

```bash
adb root
adb disable-verity          # only if remount is refused; this reboots the panel
adb remount                 # or: adb shell mount -o remount,rw /system
adb push su /system/xbin/su
adb shell chmod 06755 /system/xbin/su
```

> [!CAUTION]
> Disable the eWeLink apps (`com.eWeLinkNSPro.dev`, `com.eWeLinkControlPanel`) only **after** adb + `su` are solid and you have a home/back alternative — ha-paneld's nav actions cover the latter. Note the eWeLink **Zigbee gateway** stack is independent of these apps and keeps running; manage it with ha-paneld's [Zigbee router switch](#zigbee-gateway) rather than removing it.

## Firmware v4.0.0 – offizielle App-Installation über F-Droid

From **v4.0.0** (phased roll-out from 19 September 2025) the stock eWeLink firmware **officially bundles [F-Droid](https://f-droid.org/)** and promotes installing FOSS apps on the panel — Home Assistant's own Companion app is the headline example. Update via the panel (top drop-down → *Settings → About → Software update*) or the eWeLink app, then confirm both **APP Version** and **OS Version** read ≥ 4.0.0. Sonoff states F-Droid apps "will not affect NSPanel Pro's original features" (existing setups/automations stay intact) and that an app's F-Droid build "may differ slightly from the latest release". The update also markedly speeds up screen-swipe/UI responsiveness. Source: [Sonoff — NSPanel Pro V4.0.0 update](https://sonoff.tech/en-us/blogs/news/nspanel-pro-v4-0-0-update-now-supports-f-droid-and-home-assistant-app-install).

> [!NOTE]
> **Why this matters for ha-paneld.** F-Droid is a sanctioned, **on-device** install channel, so an APK can reach a panel with **no PC/adb** and F-Droid handles update notifications. **But F-Droid solves distribution, not privilege:** the headline features (overlay navbar, screen on/off, relays, button LEDs, Zigbee control) still need `su`, so the adb/root setup above stays a prerequisite for full function — only the non-privileged surface (MQTT discovery, sensors, brightness, HTTP UI) works on a stock unrooted panel.

## WebView – zuerst aktualisieren

An 86P freshly flashed to firmware `3.5.1` (build `164637`) was verified with `com.android.webview` **107.0.5304.105** (Chromium 107), which is too old to render a current Home Assistant dashboard. Other firmware and models may differ, so check the installed provider before deciding whether to update. The archived OTA diff packages do not include a WebView APK, so this version was read from the live unit with `dumpsys webviewupdate`. That unit runs Chromium **138** after a clean adb update. See [Updating the system WebView](README.md#system-webview-aktualisieren).

## LED

Auf diesem Gerät wurden weder ein `/sys/class/leds`-RGB-Knoten noch ein `/dev/ledjni` gefunden. Daher ist beim NSPanel Pro **keine per App/sysfs steuerbare RGB-LED bekannt** (im Gegensatz zum `avsux`-Knoten des TPA10 und zum `/dev/ledjni` des WF1589T). Bildschirmhelligkeit und Hintergrundbeleuchtung verwenden die üblichen Android-Schnittstellen.

## Sensoren – Licht und Näherung sind direkt aus der App zugänglich

Anders als beim TPA10 (wo Licht/Temperatur nur mit Root zugänglich sind) stellt das NSPanel Pro seinen Sensortek-Kombisensor über den standardmäßigen `SensorManager` bereit: `android.sensor.light`, `android.sensor.proximity` und `android.sensor.accelerometer` – alle sind von einer normalen App ohne Root lesbar. ha-paneld liest Licht und Näherung hier direkt aus. Ein Temperatur-/Feuchtigkeitssensor ist nicht verbaut.

> [!NOTE]
> **Näherungsmesswerte hängen von Firmware UND Modell ab.** Der Sensor ist ein Sensortek STK3A5x ToF in einer Aussparung der oberen Platine hinter dem Deckglas. Der Ruhe-Basiswert unterscheidet sich stark von Gerät zu Gerät (ein Gerät ~1000, ein anderes ~4000); nur die *relative* Änderung ist relevant. Ein hoher Ruhewert ist daher normal und kein Fehler. Bis etwa Firmware **3.3** liefert er abgestufte Messwerte (Intervall ~50 ms); ab etwa **3.3–3.4** stellte der Kernel-Treiber auf binär 0/1 um. **4.0.12 stellte abgestufte Messwerte nur beim 86P wieder her** – das **120P blieb binär**. ha-paneld verarbeitet anhand des Live-Verhaltens beide Formen und normalisiert den nutzbaren Bereich über die gesamte Geräteflotte; Profile enthalten keine firmwareabhängigen Schwellenwerte oder Klassifizierungen für abgestufte/binäre Werte mehr. (Quellen: seaky tools #142/#144/#171/#262.)

<details>
<summary>Gebundene I²C-Geräte (reale Hardware)</summary>

| I²C-Adresse | Treiber / Name | Funktion |
|---|---|---|
| `0-0020` | `rk809` | PMIC |
| `1-001a` / `1-005a` | `CST226` / `CST226SE` | Kapazitiver Touch-Controller von Hynitron |
| `2-003c` | `tp` | Touchpanel |
| `2-0046` | `ls_stk3a5x` + `ps_stk3a5x` | Sensortek **STK3A5x**-Kombisensor für Umgebungslicht und Näherung |
| `2-0047` | `ls_stk3x3x` + `ps_stk3x3x` | Sensortek **STK3x3x** für Licht und Näherung (alternative Variante) |

</details>

## Zigbee-Gateway

Das NSPanel Pro besitzt ein integriertes **Silicon-Labs-EFR32-Zigbee-3.0-Funkmodul** an UART `/dev/ttyS5`. Es wird von einem Hersteller-Host-Stack (`/vendor/bin/siliconlabs_host/zgateway`) über einen lokalen MQTT-Broker gesteuert – denselben Stack verwenden die eWeLink-Apps, sodass das Panel als eWeLink-Zigbee-Hub ausgeliefert wird.

ha-paneld manages it directly (v0.6.1+): `switch.<panel>_zigbee_router` turns the panel into a Zigbee **router/repeater** that extends your existing mesh (it starts the gateway and ensures the Repeater role), and turns it back off again (stopping the gateway, freeing the radio). It works over the local broker — credential-free, no `ttyS5` handling. The panel then appears as a normal router in your ZHA / Zigbee2MQTT coordinator.

> [!NOTE]
> Switching role is **not a reflash** — there is no `.gbl`/bootloader step; it just sets the EZSP node type. For partition-level firmware work see [Firmware backup & restore](../../firmware-backup-restore.md).

> [!NOTE]
> **Es ist kein Thread Border Router dokumentiert oder charakterisiert.** Der installierte Hersteller-Stack verwendet den EFR32 als Zigbee-NCP. Obwohl der EFR32MG21-Chip mehrere Protokolle unterstützt, belegt dies weder eine Thread-Firmware noch eine Border-Router-Implementierung auf dem Panel; Sonoff dokumentiert stattdessen eine Matter Bridge.
>
> **4.x reworked the Zigbee stack** — community inspection found a forked Zigbee2MQTT, a changed on-device MQTT password and a different boot sequence. [Sonoff documents coordinator↔router switching](https://sonoff.tech/blogs/news/nspanel-pro-v4-3-0-central-heating-redefining-whole-home-temperature-automation) in current firmware, but ha-paneld's private local-broker control path was built against ≤3.x and **may need adapting on 4.x**. (Community sources: seaky tools #244/#241/#255 and roottool#3.)

> [!WARNING]
> **A legacy vendor-native Zigbee-watchdog defect is confirmed by the reporter on NSPanel Pro 120 stock 3.8.0.** Firmware containing the recursive `LD_LIBRARY_PATH` assignment described in [Issue #34](https://github.com/maxlyth/ha-paneld/issues/34) can eventually make every external command launched by the watchdog fail with `E2BIG`, consume one CPU core and stop recovering a dead `zgateway`. A reboot resets the problem only temporarily. See [Performance tuning](../performance.md#alten-zigbee-watchdog-defekt-des-serienmäßigen-nspanel-pro-ausschließen) for the evidence boundary and repair-safety requirements. The reporter-provided workaround has not yet been independently validated by the project. Community inspection of 4.0.12 and 4.6.0 did not find the vulnerable assignment.

### Anforderungen – Firmware ≥ v2.2.0

The host stack is the **manufacturer's own** (eWeLink/Sonoff) gateway, versioned to match the panel firmware (e.g. `sonoff-v3.5.4`). Zigbee **router mode** was added in **NSPanel Pro firmware v2.2.0** (2023 — eWeLink app → *Device Settings → Pilot Features → Zigbee Mode*); local host-stack repeater support landed in gateway package v1.1.9. In practice:

- **Gateway present** (firmware ≥ v2.2.0, or side-loaded) → ha-paneld detects it and publishes `switch.<panel>_zigbee_router`. Toggle ON and the panel joins your coordinator as a router.
- **No gateway** (very old firmware, never provisioned) → the switch **doesn't appear** — it's gated on the gateway's launch script existing. Update firmware (≥ v2.2.0), or see migration below.

ha-paneld **drives** the gateway; it doesn't ship or install it (it's eWeLink's binary). Recent firmware (4.x) adds a Matter bridge and can export Zigbee devices to Home Assistant through MQTT Discovery — alternatives to the router role.

### Gateway-Zustand und automatische Eindämmung

On a Zigbee-capable panel, `sensor.<panel>_zigbee_gateway_health` reports the vendor stack independently of the router switch. This means an unconfigured stock gateway is still visible without granting ha-paneld permission to stop it.

When the router switch has explicitly been turned ON, ha-paneld allows a 15-minute startup and pairing grace, then watches once per minute for two runaway signatures: an explicitly invalid/unjoined network combined with more than 50% of one CPU core for five consecutive samples, or at least three gateway PID changes within ten minutes. A joined router with sustained high CPU is warning-only and remains running. Unknown 4.x layouts or missing firmware-specific join evidence fail safe to `unknown`.

Turning the Zigbee router switch ON explicitly requests Repeater mode even when the vendor gateway process is already running, so an ON command sent while your ZHA/Zigbee2MQTT coordinator permits joining acts as a fresh join retry without spawning a second gateway supervisor.

The Configure tab shows a **Request join** action directly beneath the Zigbee router switch. The existing switch remains the only on/off control. Enable permit-join on ZHA/Zigbee2MQTT, then request joining and confirm that permit-join is open. The action reasserts Repeater mode, starts a fresh 15-minute grace and polls the bounded health status; it does not reboot or restart the panel. The button is unavailable while the router is disabled, already joined or cooling down after a recent request.

After the pairing grace, an enabled gateway that is still unjoined produces a persistent dashboard, Install-tab and status-API warning linked to that Configure action. Do not leave it in that state: repeated join retries can consume substantial CPU. Either join the panel as a router or turn off the Zigbee router switch.

If a configured legacy gateway meets a runaway rule, ha-paneld persists the router switch OFF and attempts one bounded containment. Vendor-native containment can target only the Sonoff guard, `zgateway`, and the matching local broker. If a process cannot be stopped, the respawner is removed where possible and surviving gateway work is demoted to nice 19 and Android's background cpuset. Turning the router switch ON later explicitly starts one fresh grace period and retry.

The health attributes include firmware/product version, gateway layout/package version, joined/role status, rounded gateway and guard CPU, recent restart count and containment result. They never include the Zigbee network key, raw local-broker credentials, radio MAC or raw gateway `netinfo`.

### Migration von NSPanelTools

[NSPanelTools (NSPPT)](https://github.com/seaky/nspanel_pro_tools_apk) side-loads the official Sonoff gateway package onto firmware that didn't ship it; many users run it today. ha-paneld coexists and can take over the gateway:

- **Side-by-side is fine.** ha-paneld's router control is idempotent — it **defers** to whatever already runs the gateway (won't double-start or fight NSPPT); auto-brightness is opt-in/off. Nothing conflicts by default.
- **Handing the gateway to ha-paneld:** the host stack lives in `/vendor` and **survives uninstalling the NSPPT app** (verified — a persistent hook even keeps boot-starting it). Remove the NSPPT APK and ha-paneld keeps driving the gateway; if the boot hook is also stripped, ha-paneld's boot-restore starts it when the switch was left ON.

> [!NOTE]
> Both tools touch the screen/sensors. Coexistence is benign today, but enabling overlapping features (e.g. wake-on-wave alongside an NSPPT equivalent) can cause redundant actions — remove NSPPT once ha-paneld covers your needs.

<details>
<summary>Interna des EZSP-Host-Stacks (Broker-Themen, Supervisor, Rollenpersistenz)</summary>

On the legacy vendor-native ≤3.x stack, the radio runs **EZSP NCP firmware** (EFR32MG21, EZSP v8); `zgateway` is an EZSP *host* binary in `/vendor/bin/siliconlabs_host/`, kept alive by its own `guard_process.sh` supervisor (a 5-second loop, boot-started) and controlled over a **local mosquitto broker** on `127.0.0.1:1883` (anonymous — the `password_file` line is commented out in `mosquitto.conf`). The 4.x stack differs as described above.

- role status: `zigbee/system/network-role/information` → `{"role":"Repeater"|"Coordinator"}`
- role switch: `zigbee/system/network-role/switch` ← `{"role":"Repeater"}`

"Repeater" is router mode (extends an existing mesh — the supported sweet spot); the role persists in the NCP's NVM. The vendor `zgateway` survives removal of the eWeLink *apps* (it lives in `/vendor`, not in an APK).

For a full standalone Zigbee2MQTT/ZHA coordinator *on the panel* instead, see [seaky/nspanel_pro_zigbee](https://github.com/seaky/nspanel_pro_zigbee), which swaps the host stack (heavier; not what ha-paneld does).

</details>

## Zusammenfassung des Zugriffsmodells

- **Licht / Näherung / Beschleunigungssensor**: direkter App-Zugriff (`SensorManager`).
- **Screen brightness / sleep / navigate / TTS**: standard Android paths (`su` for true backlight-off).
- **LED**: keine charakterisiert.
- **Zigbee**: EFR32 radio managed via the on-device gateway's local broker (`switch.<panel>_zigbee_router`).
- **Funkmodule**: Zigbee 3.0 + WLAN/Bluetooth.

## Zu erwartende Leistung

Das NSPanel Pro ist bei aufwendigen Dashboards durch **CPU und RAM beschränkt**:

- Im Leerlauf läuft es mit 408 MHz und belegt ≈500 MB RAM; ein umfangreiches Lovelace-Dashboard beansprucht beides stark.
- **Die 2 GB RAM sind der begrenzende Faktor** – Dashboard-WebView, Android und Hintergrund-Apps konkurrieren darum; große Dashboards mit vielen Karten, großen Bildern, langen Verlaufsdiagrammen oder rechenintensiven benutzerdefinierten Karten verursachen WebView-Neuladungen und Ruckeln.
- Mit den A35-Kernen sind Seitenwechsel und Animationen sichtbar langsamer als auf Panels mit A55/A72.

Wenn Sie den integrierten Renderer von ha-paneld verwenden, beginnen Sie mit dem [automatischen Entitätsfilter für das Dashboard](../performance.md#1-entitätsabonnement-des-integrierten-renderers-filtern), damit das Panel keine Zustände verarbeitet, die sein Dashboard nie anzeigt. Verwenden Sie anschließend die Leistungskarten auf der Registerkarte „Dashboard“, um verbleibende aufwendige Ansichten, Speicherdruck oder thermische Grenzen zu ermitteln, bevor Sie das Dashboard vereinfachen. Der Filter steht dem Companion-Renderer nicht zur Verfügung; dort sind weiterhin die Optimierung der Aktualisierungen an der Quelle und ein schlankeres Dashboard die unterstützten Optionen.

---

Siehe den [Panel-Hardwareindex](README.md) für den panelübergreifenden Vergleich und die Methodik sowie die Referenzen zu [TPA10](tpa10.md) / [WF1589T](wf1589t.md) / [S9E](../../hardware/s9e.md) für die anderen Panels.
