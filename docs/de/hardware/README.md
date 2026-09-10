> [!IMPORTANT]
> Dieses Dokument wurde maschinell erstellt und automatisch gegengeprüft, jedoch nicht systematisch von Personen geprüft, die diese Sprache sprechen. Die englische Dokumentation ist maßgeblich. [Englisches Original lesen](../../hardware/README.md) oder [ein Issue zur Übersetzungskorrektur öffnen](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Panel-Hardwarereferenzen

Reverse-engineered hardware fact sheets for the wall panels ha-paneld targets — SoC, LED control, sensors, buttons, NFC, Zigbee/IR, relays, adb/root access. These devices ship with almost no public documentation, so these notes record what is physically on each board and how to drive it, gathered from live units (rooted / userdebug `adb root`). If your panel is not listed, start with the no-build [runtime profile authoring workflow](../../profiles/README.md). If your panel has a camera the Camera card does not offer, see [enabling the camera on a panel whose profile does not declare one](../../profiles/unofficial/README.md#enabling-the-camera-on-a-panel-whose-profile-does-not-declare-one) and keep unverified hardware facts explicit.

| Panel | SoC | LED-Steuerung | Wichtige Sensoren | NFC | Zigbee/IR | Referenz |
|---|---|---|---|---|---|---|
| Tuya TPA10 | rk3566 | `avsux` sysfs (Root-Daemon) | ToF VI5300, CHT8305 für Temperatur und Luftfeuchtigkeit, CG5256 für Licht; **[Kamera](tpa10.md#kamera)** (GC05A2) und ES7202-Aufnahme-ADC (Nutzbarkeit des Mikrofons nicht verifiziert) | nein | nein | [tpa10.md](tpa10.md) |
| Electron WF1589T | rk3576 | `/dev/ledjni` (direkt über App) | 6-Achsen-IMU (KXTJ9 + BMA2xx); **[Kamera](wf1589t.md#kamera)** (GC05A2) und ES7202-Mikrofon | ja (NXP, Android-NFC jedoch deaktiviert) | nein | [wf1589t.md](wf1589t.md) |
| Sonoff NSPanel Pro | rk3326 / PX30 | keine (kein RGB-Knoten) | STK3A5x für Licht + Näherung (direkt über App) | nein | **Zigbee** (Silabs EFR32, UART); kein IR | [nspanel-pro.md](nspanel-pro.md) |
| Smatek S9E † | rk3566 | GPIO-LEDs je Taste (Root) | Radar-Näherung, Licht, Temperatur + Luftfeuchtigkeit; **2 Netzspannungsrelais** (`st_relay`); RS485 + Ethernet | nein | **Zigbee** | [s9e.md](../../hardware/s9e.md) |
| ZHICAI SMT1019 ‡ | rk3576 | Root-Hilfsprogramm im `userdebug`-Build des Lieferanten; auf der Originalfirmware nicht verfügbar | GXHT30 für Temperatur + Luftfeuchtigkeit (Genauigkeit nicht verifiziert); experimentelle VI530x-Näherungserkennung | nein | nein | [smt1019.md](../../hardware/smt1019.md) |
| ZX-SMT156 / RK3566_T ‡ | rk3566 | `/dev/ledjni` (direkt über App) | binäre Näherungserkennung, Umgebungslicht; GXHT30 für Temperatur + Luftfeuchtigkeit (Hilfsprogramm oder korrigierte Alternative auf Shell-Ebene) | unbekannt | Herstellerrelais gemeldet, Steuerungspfad unbekannt | [zx-smt156.md](../../hardware/zx-smt156.md) |
| Shelly Wall Display § | MT6580 / SC7731E / RK3326-S / RK3566 (modellabhängig) | keine bestätigt | Umgebungslicht beim Original/X2/X1i/X2i/XL; Temperatur/Luftfeuchtigkeit beim Original + X2; Näherung beim X2/X1i/X2i; Bewegung beim XL; Relais variieren je nach Modell/Basis | nicht für jedes Modell bestätigt | nicht für jedes Modell bestätigt | [shelly-wall-display.md](../../hardware/shelly-wall-display.md) |

† Die S9E-Spezifikationen stammen aus Smateks Produktangaben; die Steuerungspfade aus [#98](https://github.com/seaky/nspanel_pro_tools_apk/issues/98) und dem HA-Community-Thread. Sie wurden **nicht** an einem hier vorhandenen Gerät validiert — Relais- und Tastenunterstützung sind implementiert, aber ungetestet.

‡ Die Fakten zu SMT1019 und ZX-SMT156 stammen aus Diagnoseinformationen von Berichtenden und verknüpften OEM- oder Händlernachweisen ([#8](https://github.com/maxlyth/ha-paneld/issues/8), [#24](https://github.com/maxlyth/ha-paneld/issues/24)); keines der Panels steht für lokale Tests zur Verfügung. Für die Persistenz des SMT1019-Hilfsprogramms und die Rohachsen der Klimasensoren liegen Nachweise von Berichtenden vor, aber Klimagenauigkeit, durchgängige Näherungserkennung und das vollständige Profil benötigen noch Hardwaretests. Die ZX-Klimasensorunterstützung ist optional; USB- oder Hersteller-Root sowie dauerhafte Entsperrpfade sind weiterhin ungetestet.

§ Die Fakten zum Shelly Wall Display stammen aus der Analyse von Firmware-OTAs (einschließlich einer Device-Tree-Analyse des modernen Partitionsabbilds), dem offiziellen Änderungsprotokoll sowie Community-/KB-Quellen. Sie wurden **nicht** an einem hier vorhandenen Gerät validiert. Das **ältere** OTA deklariert einen `userdebug`-Zielbuild, daher könnte `adb root` dort erreichbar sein, wenn ein adb-Zugang besteht; das **moderne** OTA deklariert keinen Build-Typ — siehe [shelly-wall-display.md](../../hardware/shelly-wall-display.md) für die Nachweise je Zweig und Shellys eigene Aussage zur aktuellen Hardware. Die mitgelieferten YAML-Profile `shelly-wall-display` und `shelly-wall-display-v2` sind implementiert, aber spekulativ.

> [!TIP]
> Before modifying firmware on a rooted **TPA10 or WF1589T**, read [Firmware backup & restore](../../firmware-backup-restore.md). Those Rockchip panels use `adb reboot loader` and `rkdeveloptool` rather than the usual Android button combination. The guide does not apply to the MediaTek Shelly family, an unrooted panel or uncharacterised hardware, and it does not yet claim a write-ready Maskrom recovery path.

## Methode

- **Reale Chips**: gebundene i2c-Geräte über `/sys/bus/i2c/devices/*/name` — *nicht* `…/drivers/`, denn Rockchip-BSPs kompilieren Hunderte optionale Treiber ein und die Auflistung unter `drivers/` meldet viel zu viele Geräte.
- **Funkmodule**: `pm list features` (`nfc`, `consumerir`, `bluetooth`, `ethernet`, …) + `/dev`-Knoten.
- **Von Android bereitgestellte Sensoren**: `dumpsys sensorservice`.
- **Steuerungsschnittstellen**: `/sys/class/leds`, `/dev` und die eigenen Attribute jedes LED-Knotens (einige Panels dokumentieren sich selbst, z. B. `avsux_info` / `avsux_firmware` beim TPA10).

Korrekturen und Ergänzungen für weitere Panels sind willkommen.

The `Native` navbar mode is profile-gated, not specific to Electron panels. The bundled WF1589T profile currently declares it because that firmware's Android navbar has been verified. Other profiles can enable the same mode after their system bar has been confirmed.

## adb- + Root-Zugriff erlangen

Der Weg zu adb/Root ist bei jedem Panel anders; die Seiten zu den einzelnen Panels enthalten die vollständigen, firmwarespezifischen Schritte:

- **Sonoff NSPanel Pro** — `userdebug`/test-keys, **no adb password**; the only hurdle is reaching developer mode (varies by eWeLink firmware). `adb root` + remount + a SuperSU `su`. → [nspanel-pro.md](nspanel-pro.md#adb--und-root-zugriff-erlangen).
- **Tuya TPA10** — adb is **password-protected**; the reliable route is the USB diagnostics-app backdoor (`su` already present). → [tpa10.md](tpa10.md#adb--und-root-zugriff-erlangen).
- **Electron WF1589T** — `userdebug` with Google Play; `adb root` works directly (LED is app-direct, so root is rarely needed). → [wf1589t.md](wf1589t.md).

## Leistungsvergleich und praktische Bereitstellung

Die drei Panelklassen bilden eine klare Abstufung: **NSPanel Pro (PX30)** als Einstiegsklasse, **TPA10 (rk3566)** als Mittelklasse und **WF1589T (rk3576)** als Oberklasse. Die Bildschirmgeometrie ist die erste Designeinschränkung; bei Panels mit 2 GB ist der Arbeitsspeicher der begrenzende Faktor. Die Werte stammen vom eigenen `/perf`-Endpunkt von ha-paneld und aus den Gerätespezifikationen.

<details>
<summary>Spezifikationsstufen (CPU / RAM / GPU / Display)</summary>

| | NSPanel Pro (PX30) | TPA10 (rk3566) | WF1589T (rk3576) |
|---|---|---|---|
| CPU | 4× Cortex-A35 @1,5 GHz | 4× Cortex-A55 @1,8 GHz | 4× A72 @2,1 GHz + 4× A53 @1,9 GHz |
| RAM | 2 GB | 2 GB | 4 GB |
| GPU | Mali-G31 | Mali-G52 (2EE) | Mali-G52 (MC3) |
| Display | 480×480 **quadratisch**, ca. 4 Zoll | 1920×1200 16:10, ca. 10,1 Zoll/ca. 226 ppi | 1920×1200 16:10, ca. 10,1 Zoll/ca. 226 ppi |
| Bildwiederholrate | 60 Hz | 56 Hz | 60 Hz |
| Layout (dp) | logische Basisdichte 160 dpi → 480×480 dp | logische Basisdichte 240 dpi; ha-paneld empfiehlt 212 | logische Basisdichte 160 dpi → 1920×1200 dp — Benutzeroberfläche winzig, [Dichte erhöhen](wf1589t.md#anzeigedichte--erhöhen) |
| Kamera | keine | GC05A2, `Facing: Back`; H.264-Kodierung über `OMX.rk.video_encoder.avc` | GC05A2, `Facing: Front`; H.264-Kodierung über `c2.rk.avc.encoder` |
| Klasse | Einstiegsklasse | Mittelklasse | Oberklasse |

</details>

<details>
<summary>Momentaufnahme von `/perf` (zur Veranschaulichung, kein kontrollierter Benchmark)</summary>

Jedes Panel unter seiner eigenen realen Arbeitslast:

| | PX30 (weitgehend im Leerlauf) | WF1589T (aktives Dashboard) |
|---|---|---|
| CPU | 9 % | 29 % |
| Takt | 408 MHz (von 1512) | große Kerne 1608 MHz (von 2112) |
| RAM belegt | 508 / 1960 MB | 2265 / 3897 MB |
| Temperatur | 49 °C | 63 °C |
| Reaktionsfähigkeit | flüssig, Hauptthread 3,6 % | flüssig, Hauptthread 25,9 % |

(Der TPA10 liegt bei der CPU zwischen den beiden.)

</details>

**Was dies für eine reale Dashboard-Bereitstellung bedeutet:**

- **Die Bildschirmgeometrie ist die erste Designeinschränkung.** Auf das 480×480 große **quadratische** Display (480 dp) des NSPanel Pro passt nur eine einzelne schmale Spalte; das 10,1-Zoll-Display des TPA10 mit 1920×1200 bietet tatsächlich viel Platz für mehrspaltige Dashboards; das WF1589T wird mit einer niedrigen logischen Basisdichte ausgeliefert, sodass die Benutzeroberfläche winzig ist, bis die Dichte erhöht wird. Richten Sie das Dashboard an der **dp-Zeichenfläche und dem Seitenverhältnis** des Panels aus, nicht an seiner reinen Pixelzahl. Die logische Basis-DPI von Android ist eine Layouteinstellung, keine physische PPI-Angabe.
- **Bei Panels mit 2 GB (PX30, TPA10) ist der RAM der begrenzende Faktor.** Dashboard-WebView, Android und Hintergrund-Apps teilen sich etwa 2 GB; aufwendige Dashboards mit vielen Karten, großen Bildern, langen Verlaufsdiagrammen oder rechenintensiven benutzerdefinierten Karten führen zu WebView-Neuladungen und Ruckeln. Die 4 GB des WF1589T beseitigen diesen Engpass weitgehend.
- **Das NSPanel Pro hat in diesem Vergleich die langsamste CPU** (A35), weshalb Übergänge und Animationen sichtbar langsamer als auf A55-/A72-Geräten sind. Halten Sie seine Dashboards besonders schlank.
- **Filtern Sie beim integrierten Renderer zunächst das Home-Assistant-Entitätsabonnement, bevor Sie ein Dashboard vereinfachen oder das Panel ersetzen.** Das automatische Erlernen von Entitäten kann verhindern, dass nicht zugehörige Zustände die WebView erreichen, während die normale Home-Assistant-Verbindung des Panels erhalten bleibt. Siehe [Leistungsoptimierung](../performance.md).
- **ha-paneld misst die verbleibenden Engpässe**: Dashboard-Reaktionszeit, unerwartete Neuladungen, CPU-Takt und Drosselung, Speicherdruck, die am stärksten ausgelasteten Prozesse sowie WebView-Renderingmetriken helfen dabei, Hardwaregrenzen von einem aufwendigen Dashboard oder übermäßigen Datenmengen zu unterscheiden.

## System-WebView aktualisieren

**Lesen Sie dies zuerst** — es handelt sich um den häufigsten Fehler beim ersten Start auf diesen Panels.

ha-paneld's built-in renderer and the HA Companion app both rely on Android's **system WebView**, and most of these panels ship with one far too old to run a current Home Assistant frontend. Out of the box this can produce a **blank or broken dashboard, missing cards, or "browser not supported"**. Panels **without** Google Play (NSPanel Pro, TPA10) cannot update it automatically through the Play Store, so install a current WebView using the appropriate method below. The **WF1589T and the SMT1019 have Google Play**, so update *Android System WebView* from the Play Store or use the Play WebView development channel.

The clean way is a direct adb sideload of the standard Android System WebView (package **`com.android.webview`**), matched to the panel's Android version and ABI — **no F-Droid, no third-party app store** (the workarounds the NSPanel-Pro community threads resort to). Per-panel known-working builds and the full sideload/verify steps are below.

> [!TIP]
> The package name must be `com.android.webview` for the system to select it automatically. Mind the distinction: the **SystemWebView** builds from Cromite and LineageOS use `com.android.webview` and *do* register as the provider — but the regular **Cromite / Bromite *browser*** app uses a different package and does **not**. Use the SystemWebView build, not the browser APK.

### Originalversionen und bekanntermaßen funktionierende Ersatzversionen je Panel

"Stock" = what the vendor firmware ships from factory, verified from firmware OTA inspection or a live device. "Replacement" = what is confirmed working after sideload. Redistributable builds are mirrored as ha-paneld Release assets; sideload with `adb install -r <file>`.

| Panel | ABI | Original (Herstellerfirmware) | Ersatz (`com.android.webview`) | Download |
|---|---|---|---|---|
| NSPanel Pro 86P (PX30) | arm64-v8a | Chromium **107.0.5304.105** verified on firmware 3.5.1; check other firmware/models before updating | **LineageOS** 138.0.7204.63 — last build for Android **8.1** | [Release-Asset](https://github.com/maxlyth/ha-paneld/releases/download/webview-mirror/lineageos-webview-138.0.7204.63.apk) · [APKMirror](https://www.apkmirror.com/apk/lineageos/android-system-webview-2/android-system-webview-138-0-7204-63-2-release/android-system-webview-138-0-7204-63-8-android-apk-download/download) |
| TPA10 (rk3566) | armeabi-v7a | **Chrome 83** (`com.android.webview`) — zu alt für das aktuelle HA-Frontend | **LineageOS** SystemWebView 150.0.7871.63 — vanilla Chromium, allows camera autoplay (Cromite 147 blocks it, kept as fallback). **Signature-locked — needs the root swap in [tpa10.md](tpa10.md#webview--zuerst-aktualisieren), not a plain sideload.** | [ARM-Asset](https://github.com/maxlyth/ha-paneld/releases/download/webview-mirror/lineageos-webview-150.0.7871.63-arm.apk) |
| WF1589T (rk3576) | arm64-v8a | Google Play WebView (automatische Updates) | update via Play Store — no sideload needed | — |
| S9E (rk3566) | **arm64-v8a** | **Firmware-dependent — check before replacing.** Chromium **83.0.4103.120** on the 2024-07 build (too old for a current HA frontend), but **131.0.6778.200** on the 2025-12 build | Only needed on the older firmware: **LineageOS** 150.0.7871.63 (**arm64**) — *provisional, unverified hardware*; may be signature-locked like the TPA10. On 2025-12 firmware the stock WebView is already current | [arm64-Asset](https://github.com/maxlyth/ha-paneld/releases/download/webview-mirror/lineageos-webview-150.0.7871.63-arm64.apk) |
| SMT1019 (rk3576) | arm64-v8a | `com.google.android.webview` **124.0.6367.179** — Android 14 **mit** Google Play | **Update *Android System WebView* from the Play Store** — no sideload needed. Do not sideload a `com.android.webview` build here: the panel's own provider list is supplied by a product overlay, so a sideloaded provider may not be selected | — |
| ZX-SMT156 / RK3566_T | arm64-v8a | Google WebView **149.0.7827.164** (Firmware der berichtenden Person) | Google WebView is current; no replacement needed | — |
| Shelly Wall Display Original (MT6580) | armeabi-v7a | **unbekannt** (Android-7-Basis-ROM) | `com.google.android.webview` **119.0.6045.194** via [official Shelly ZIP](https://repo.shelly.cloud/firmware/SAWD-0A1XX10EU1/stable/SAWD-0A1XX10EU1-WebViewUpdate.zip) — see [shelly-wall-display.md](../../hardware/shelly-wall-display.md#webview) | — |
| Shelly Wall Display X2 (SC7731E) | armeabi-v7a | **unbekannt** (Android-8.1-Basis-ROM) | nicht bestätigt — siehe `adb shell dumpsys webviewupdate` | — |
| Shelly Wall Display X1i/X2i/XL (arm64) | arm64-v8a | **unbekannt** (Android-11-Basis-ROM; nicht im Shelly-OTA enthalten) | nicht bestätigt — siehe `adb shell dumpsys webviewupdate` | — |

Alle gespiegelten Builds befinden sich im [**Panel-WebView-Spiegel**-Release](https://github.com/maxlyth/ha-paneld/releases/tag/webview-mirror) — gedacht als fortlaufend gepflegte Community-Liste bekanntermaßen funktionierender Versionen. Haben Sie einen Build auf einem anderen Panel oder eine andere Version zum Laufen gebracht? Beiträge sind willkommen.

> [!NOTE]
> - **Pick the newest WebView your panel's Android version supports.** The NSPanel Pro's Android 8.1 caps at 138 (the last Chromium for Android 8/9); newer builds won't install. Android 10+ (the TPA10's 11) runs current **LineageOS** WebView (150).
> - **Die *direkten* Downloadlinks von APKMirror sind kurzlebige, vorsignierte URLs, die innerhalb einer Stunde ablaufen** — verwenden Sie die Seite oder die obigen ha-paneld-Release-Assets (dauerhaft). Der Spiegel existiert gerade deshalb, weil die Panels kein Play besitzen und mit jahrealter Firmware ausgeliefert werden; andernfalls kann es Tage dauern, einen funktionierenden Build zu finden.

<details>
<summary>Schritte zum Sideloading und Überprüfen</summary>

1. Download a current **Android System WebView** APK — package **`com.android.webview`**. **LineageOS** System WebView is the recommended build across Android versions: 138 is the last for Android 8.1, and 150 covers Android 10+ (both in the mirror). It's vanilla Chromium, so it doesn't carry Cromite's autoplay block that stops HA camera streams. It uses the `com.android.webview` package, so it's picked as the provider automatically (no allowlist editing, no extra app), and it's open / freely redistributable. Match your panel's ABI; per-panel downloads are above.

> [!IMPORTANT]
> The simple sideload below works on panels whose ROM waives the WebView signature check (e.g. the NSPanel Pro's userdebug build). **Signature-locked panels (the TPA10, and likely other vendor user builds) reject a plain sideload** — `signatures do not match`. Those need the one-time root swap (replace the system WebView file + clear its `packages.xml` entry); see [tpa10.md → WebView](tpa10.md#webview--zuerst-aktualisieren) for the exact procedure.
2. Sideload it (no root):

   ```sh
   adb install -r android-system-webview.apk
   ```

   It installs to `/data/app` and supersedes the stale stock WebView.
3. Verify the active provider + version:

   ```sh
   adb shell dumpsys webviewupdate | grep "Current WebView package"
   ```

If the panel lists more than one provider, select it explicitly:

```sh
adb shell cmd webviewupdate set-webview-implementation com.android.webview
```

or via Developer options → *WebView implementation*.

</details>

---

Datenblätter je Panel: [NSPanel Pro](nspanel-pro.md) · [TPA10](tpa10.md) · [WF1589T](wf1589t.md) · [S9E](../../hardware/s9e.md) · [SMT1019](../../hardware/smt1019.md) · [ZX-SMT156](../../hardware/zx-smt156.md) · [Shelly Wall Display](../../hardware/shelly-wall-display.md).
