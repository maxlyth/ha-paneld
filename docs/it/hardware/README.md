> [!IMPORTANT]
> Questo documento è generato automaticamente e verificato mediante controlli incrociati automatici, ma non è stato rivisto sistematicamente da persone che parlano questa lingua. La documentazione in inglese fa fede. [Leggi la fonte in inglese](../../hardware/README.md) oppure [apri una segnalazione per correggere la traduzione](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Riferimenti hardware dei pannelli

Reverse-engineered hardware fact sheets for the wall panels ha-paneld targets — SoC, LED control, sensors, buttons, NFC, Zigbee/IR, relays, adb/root access. These devices ship with almost no public documentation, so these notes record what is physically on each board and how to drive it, gathered from live units (rooted / userdebug `adb root`). If your panel is not listed, start with the no-build [runtime profile authoring workflow](../../profiles/README.md). If your panel has a camera the Camera card does not offer, see [enabling the camera on a panel whose profile does not declare one](../../profiles/unofficial/README.md#enabling-the-camera-on-a-panel-whose-profile-does-not-declare-one) and keep unverified hardware facts explicit.

| Pannello | SoC | Controllo LED | Sensori principali | NFC | Zigbee/IR | Riferimento |
|---|---|---|---|---|---|---|
| Tuya TPA10 | rk3566 | `avsux` sysfs (demone root) | ToF VI5300, CHT8305 temperatura+umidità, CG5256 luce; **[fotocamera](tpa10.md#fotocamera)** (GC05A2) e ADC di acquisizione ES7202 (non è verificato che il microfono sia utilizzabile) | no | no | [tpa10.md](tpa10.md) |
| Electron WF1589T | rk3576 | `/dev/ledjni` (diretto dall'app) | IMU a 6 assi (KXTJ9 + BMA2xx); **[fotocamera](wf1589t.md#fotocamera)** (GC05A2) e microfono ES7202 | sì (NXP, ma Android-NFC disabilitato) | no | [wf1589t.md](wf1589t.md) |
| Sonoff NSPanel Pro | rk3326 / PX30 | nessuno (nessun nodo RGB) | STK3A5x luce + prossimità (diretto dall'app) | no | **Zigbee** (Silabs EFR32, UART); nessun IR | [nspanel-pro.md](nspanel-pro.md) |
| Smatek S9E † | rk3566 | LED GPIO per pulsante (root) | prossimità radar, luce, temperatura+umidità; **2 relè per la tensione di rete** (`st_relay`); RS485 + Ethernet | no | **Zigbee** | [s9e.md](../../hardware/s9e.md) |
| ZHICAI SMT1019 ‡ | rk3576 | helper root sulla build `userdebug` del fornitore; non disponibile sul firmware di serie | GXHT30: temperatura + umidità (precisione non verificata); sensore di prossimità VI530x sperimentale | no | no | [smt1019.md](../../hardware/smt1019.md) |
| ZX-SMT156 / RK3566_T ‡ | rk3566 | `/dev/ledjni` (diretto dall'app) | prossimità binaria, luce ambientale; GXHT30 temperatura+umidità (helper o alternativa fissa a livello di shell) | sconosciuto | segnalata la presenza di relè del produttore; percorso di controllo sconosciuto | [zx-smt156.md](../../hardware/zx-smt156.md) |
| Shelly Wall Display § | MT6580 / SC7731E / RK3326-S / RK3566 (a seconda del modello) | nessuno accertato | luce ambientale su original/X2/X1i/X2i/XL; temperatura/umidità su original + X2; prossimità su X2/X1i/X2i; rilevamento del movimento su XL; i relè variano in base al modello e alla base | non accertato per tutti i modelli | non accertato per tutti i modelli | [shelly-wall-display.md](../../hardware/shelly-wall-display.md) |

† Le specifiche dell’S9E provengono dalla scheda di Smatek; i percorsi di controllo provengono da [#98](https://github.com/seaky/nspanel_pro_tools_apk/issues/98) e dal thread della community di HA e **non** sono stati convalidati su un’unità in loco — il supporto per relè e pulsanti è implementato ma non testato.

‡ I dati relativi a SMT1019 e ZX-SMT156 provengono dalla diagnostica fornita dagli utenti che li hanno segnalati e dalle prove OEM o commerciali collegate ([#8](https://github.com/maxlyth/ha-paneld/issues/8), [#24](https://github.com/maxlyth/ha-paneld/issues/24)); nessuno dei due pannelli è disponibile per test locali. La persistenza dell’helper SMT1019 e gli assi grezzi dei dati climatici sono documentati dagli utenti, ma l’accuratezza delle misurazioni climatiche, il rilevamento di prossimità end-to-end e il profilo completo richiedono ancora test sull’hardware. Il supporto climatico dello ZX è opzionale; i percorsi di accesso root via USB o del fornitore e quelli di sblocco persistente non sono ancora stati testati.

§ I dati su Shelly Wall Display provengono dall’analisi degli OTA del firmware (inclusa l’analisi del device tree dell’immagine della partizione moderna), dal changelog ufficiale e da fonti della community/KB e **non** sono stati convalidati su un’unità in loco. L’OTA **legacy** indica come target una build `userdebug`, quindi `adb root` potrebbe essere accessibile se si riesce prima a ottenere un accesso adb; l’OTA **moderno** non dichiara alcun tipo di build — vedere [shelly-wall-display.md](../../hardware/shelly-wall-display.md) per le prove relative a ciascun ramo e per la dichiarazione di Shelly sull’hardware attuale. I profili YAML `shelly-wall-display` e `shelly-wall-display-v2` inclusi sono implementati ma speculativi.

> [!TIP]
> Before modifying firmware on a rooted **TPA10 or WF1589T**, read [Firmware backup & restore](../../firmware-backup-restore.md). Those Rockchip panels use `adb reboot loader` and `rkdeveloptool` rather than the usual Android button combination. The guide does not apply to the MediaTek Shelly family, an unrooted panel or uncharacterised hardware, and it does not yet claim a write-ready Maskrom recovery path.

## Metodo

- **Silicio effettivamente presente**: dispositivi i2c associati tramite `/sys/bus/i2c/devices/*/name` — *non* `…/drivers/`, perché i BSP Rockchip includono in fase di compilazione centinaia di driver opzionali e l’elenco `drivers/` sovrastima enormemente l’hardware presente.
- **Radio**: `pm list features` (`nfc`, `consumerir`, `bluetooth`, `ethernet`, …) + nodi `/dev`.
- **Sensori esposti ad Android**: `dumpsys sensorservice`.
- **Interfacce di controllo**: `/sys/class/leds`, `/dev` e gli attributi specifici di ciascun nodo LED (alcuni pannelli sono autodescrittivi, ad esempio gli attributi `avsux_info` / `avsux_firmware` del TPA10).

Sono gradite correzioni e integrazioni per altri pannelli.

The `Native` navbar mode is profile-gated, not specific to Electron panels. The bundled WF1589T profile currently declares it because that firmware's Android navbar has been verified. Other profiles can enable the same mode after their system bar has been confirmed.

## Ottenere accesso adb + root

Ogni pannello raggiunge adb/root in modo diverso; le pagine per pannello contengono i passaggi completi specifici del firmware:

- **Sonoff NSPanel Pro** — `userdebug`/test-keys, **no adb password**; the only hurdle is reaching developer mode (varies by eWeLink firmware). `adb root` + remount + a SuperSU `su`. → [nspanel-pro.md](nspanel-pro.md#ottenere-accesso-adb--root).
- **Tuya TPA10** — adb is **password-protected**; the reliable route is the USB diagnostics-app backdoor (`su` already present). → [tpa10.md](tpa10.md#ottenere-accesso-adb--root).
- **Electron WF1589T** — `userdebug` with Google Play; `adb root` works directly (LED is app-direct, so root is rarely needed). → [wf1589t.md](wf1589t.md).

## Confronto delle prestazioni e implementazione pratica

Le tre classi di pannelli formano una scala chiara: **NSPanel Pro (PX30)** entry-level, **TPA10 (rk3566)** di fascia media, **WF1589T (rk3576)** di fascia alta. La geometria dello schermo è il primo vincolo di progettazione; sui pannelli da 2 GB la RAM è il fattore limitante. I dati provengono dall'endpoint `/perf` di ha-paneld e dalle specifiche dei dispositivi.

<details>
<summary>Scala delle specifiche (CPU/RAM/GPU/display)</summary>

| | NSPanel Pro (PX30) | TPA10 (rk3566) | WF1589T (rk3576) |
|---|---|---|---|
| CPU | 4× Cortex-A35 a 1,5 GHz | 4× Cortex-A55 a 1,8 GHz | 4× A72 a 2,1 GHz + 4× A53 a 1,9 GHz |
| RAM | 2 GB | 2 GB | 4 GB |
| GPU | Mali-G31 | Mali-G52 (2EE) | Mali-G52 (MC3) |
| Display | 480×480 **quadrato**, ~4" | 1920×1200 16:10, ~10,1"/~226 ppi | 1920×1200 16:10, ~10,1"/~226 ppi |
| Frequenza di aggiornamento | 60 Hz | 56 Hz | 60 Hz |
| Layout (dp) | densità logica di base: 160 dpi → 480×480 dp | densità logica di base: 240 dpi; ha-paneld consiglia 212 | densità logica di base: 160 dpi → 1920×1200 dp — interfaccia minuscola, [aumenta la densità](wf1589t.md#densità-dello-schermo-aumentala) |
| Fotocamera | nessuna | GC05A2, `Facing: Back`; Codifica H.264 tramite `OMX.rk.video_encoder.avc` | GC05A2, `Facing: Front`; Codifica H.264 tramite `c2.rk.avc.encoder` |
| Classe | livello base | fascia media | fascia alta |

</details>

<details>
<summary>Istantanea `/perf` live (illustrativa, non un benchmark controllato)</summary>

Ogni pannello con il proprio carico di lavoro reale:

| | PX30 (per lo più inattivo) | WF1589T (dashboard attivo) |
|---|---|---|
| CPU | 9 % | 29 % |
| Frequenza di clock | 408 MHz (di 1512) | core ad alte prestazioni a 1608 MHz (su 2112) |
| RAM utilizzata | 508/1960 MB | 2265/3897 MB |
| Temp | 49 °C | 63 °C |
| Reattività | fluido, thread principale 3,6% | fluido, thread principale 25,9% |

(Per prestazioni della CPU, il TPA10 si colloca tra i due.)

</details>

**Che cosa significa per una distribuzione reale del dashboard:**

- **La geometria dello schermo è il primo vincolo di progettazione.** Lo schermo **quadrato** da 480×480 dell'NSPanel Pro (480 dp) può contenere solo una singola colonna stretta; il display da 10,1" e 1920×1200 del TPA10 offre davvero molto spazio per dashboard a più colonne; il WF1589T viene fornito con una densità logica di base bassa, quindi l'interfaccia è minuscola finché la densità non viene aumentata. Progetta la dashboard in base all'**area in dp e alle proporzioni** del pannello, non al numero grezzo di pixel. Il valore DPI logico di base di Android è un'impostazione del layout, non la densità fisica in PPI.
- **Pannelli da 2 GB (PX30, TPA10): la RAM è il fattore limitante.** La WebView della dashboard, Android e le eventuali app in background condividono circa 2 GB; dashboard pesanti con molte schede, immagini grandi, grafici con una cronologia estesa o schede personalizzate impegnative causano ricaricamenti della WebView e scatti. I 4 GB del WF1589T eliminano in gran parte questa pressione.
- **L'NSPanel Pro ha la CPU più lenta in questo confronto** (A35), quindi le transizioni e le animazioni sono visibilmente più lente rispetto alle unità A55/A72. Mantieni le sue dashboard il più possibile leggere.
- **Per il renderer integrato, filtra la sottoscrizione agli aggiornamenti delle entità di Home Assistant prima di semplificare una dashboard o sostituire il pannello.** L'apprendimento automatico delle entità può impedire che stati non pertinenti raggiungano la WebView, preservando al contempo la normale connessione del pannello a Home Assistant. Vedi [Ottimizzazione delle prestazioni](../performance.md).
- **ha-paneld misura i colli di bottiglia rimanenti**: il tempo di risposta del dashboard, i ricaricamenti imprevisti, la frequenza di clock e il throttling della CPU, la pressione sulla memoria, i processi più impegnativi e le metriche di rendering di WebView aiutano a distinguere i limiti dell'hardware da un dashboard oneroso o da un volume eccessivo di dati.

## Aggiornamento della WebView di sistema

**Leggi questo prima di ogni altra cosa**: è l'errore di primo avvio più comune su questi pannelli.

ha-paneld's built-in renderer and the HA Companion app both rely on Android's **system WebView**, and most of these panels ship with one far too old to run a current Home Assistant frontend. Out of the box this can produce a **blank or broken dashboard, missing cards, or "browser not supported"**. Panels **without** Google Play (NSPanel Pro, TPA10) cannot update it automatically through the Play Store, so install a current WebView using the appropriate method below. The **WF1589T and the SMT1019 have Google Play**, so update *Android System WebView* from the Play Store or use the Play WebView development channel.

The clean way is a direct adb sideload of the standard Android System WebView (package **`com.android.webview`**), matched to the panel's Android version and ABI — **no F-Droid, no third-party app store** (the workarounds the NSPanel-Pro community threads resort to). Per-panel known-working builds and the full sideload/verify steps are below.

> [!TIP]
> The package name must be `com.android.webview` for the system to select it automatically. Mind the distinction: the **SystemWebView** builds from Cromite and LineageOS use `com.android.webview` and *do* register as the provider — but the regular **Cromite / Bromite *browser*** app uses a different package and does **not**. Use the SystemWebView build, not the browser APK.

### Versioni stock per pannello e sostituzioni verificate e funzionanti

"Stock" = what the vendor firmware ships from factory, verified from firmware OTA inspection or a live device. "Replacement" = what is confirmed working after sideload. Redistributable builds are mirrored as ha-paneld Release assets; sideload with `adb install -r <file>`.

| Pannello | ABI | Stock (firmware del fornitore) | Sostituzione (`com.android.webview`) | Scarica |
|---|---|---|---|---|
| NSPanel Pro 86P (PX30) | arm64-v8a | Chromium **107.0.5304.105** verified on firmware 3.5.1; check other firmware/models before updating | **LineageOS** 138.0.7204.63 — last build for Android **8.1** | [asset della release](https://github.com/maxlyth/ha-paneld/releases/download/webview-mirror/lineageos-webview-138.0.7204.63.apk) · [APKMirror](https://www.apkmirror.com/apk/lineageos/android-system-webview-2/android-system-webview-138-0-7204-63-2-release/android-system-webview-138-0-7204-63-8-android-apk-download/download) |
| TPA10 (rk3566) | armeabi-v7a | **Chrome 83** (`com.android.webview`): troppo vecchio per l'attuale frontend HA | **LineageOS** SystemWebView 150.0.7871.63 — vanilla Chromium, allows camera autoplay (Cromite 147 blocks it, kept as fallback). **Signature-locked — needs the root swap in [tpa10.md](tpa10.md#webview-esegui-prima-laggiornamento), not a plain sideload.** | [asset ARM](https://github.com/maxlyth/ha-paneld/releases/download/webview-mirror/lineageos-webview-150.0.7871.63-arm.apk) |
| WF1589T (rk3576) | arm64-v8a | Google Play WebView (aggiornamenti automatici) | update via Play Store — no sideload needed | — |
| S9E (rk3566) | **arm64-v8a** | **Firmware-dependent — check before replacing.** Chromium **83.0.4103.120** on the 2024-07 build (too old for a current HA frontend), but **131.0.6778.200** on the 2025-12 build | Only needed on the older firmware: **LineageOS** 150.0.7871.63 (**arm64**) — *provisional, unverified hardware*; may be signature-locked like the TPA10. On 2025-12 firmware the stock WebView is already current | [risorsa arm64](https://github.com/maxlyth/ha-paneld/releases/download/webview-mirror/lineageos-webview-150.0.7871.63-arm64.apk) |
| SMT1019 (rk3576) | arm64-v8a | `com.google.android.webview` **124.0.6367.179** — Android 14 **con** Google Play | **Update *Android System WebView* from the Play Store** — no sideload needed. Do not sideload a `com.android.webview` build here: the panel's own provider list is supplied by a product overlay, so a sideloaded provider may not be selected | — |
| ZX-SMT156 / RK3566_T | arm64-v8a | Google WebView **149.0.7827.164** (firmware segnalato) | Google WebView is current; no replacement needed | — |
| Shelly Wall Display originale (MT6580) | armeabi-v7a | **sconosciuto** (ROM base Android 7) | `com.google.android.webview` **119.0.6045.194** via [official Shelly ZIP](https://repo.shelly.cloud/firmware/SAWD-0A1XX10EU1/stable/SAWD-0A1XX10EU1-WebViewUpdate.zip) — see [shelly-wall-display.md](../../hardware/shelly-wall-display.md#webview) | — |
| Shelly Wall Display X2 (SC7731E) | armeabi-v7a | **sconosciuto** (ROM base Android 8.1) | non stabilito: controlla `adb shell dumpsys webviewupdate` | — |
| Shelly Wall Display X1i/X2i/XL (arm64) | arm64-v8a | **sconosciuto** (ROM base Android 11; non presente in Shelly OTA) | non stabilito: controlla `adb shell dumpsys webviewupdate` | — |

Tutte le build archiviate nel mirror si trovano nella [**raccolta mirror Panel WebView** pubblicata](https://github.com/maxlyth/ha-paneld/releases/tag/webview-mirror): è pensata come un elenco vivo e gestito dalla comunità delle versioni di cui è noto il corretto funzionamento. Ne hai fatta funzionare una su un altro pannello o un'altra versione? I contributi sono benvenuti.

> [!NOTE]
> - **Pick the newest WebView your panel's Android version supports.** The NSPanel Pro's Android 8.1 caps at 138 (the last Chromium for Android 8/9); newer builds won't install. Android 10+ (the TPA10's 11) runs current **LineageOS** WebView (150).
> - **I link di download di APKMirror *diretti* sono URL prefirmati di breve durata che scadono entro un'ora**: usa la pagina oppure gli asset durevoli della release di ha-paneld indicati sopra. Il mirror esiste proprio perché i pannelli non dispongono di Play e vengono forniti con firmware vecchi di anni; altrimenti, trovare una build funzionante può richiedere giorni.

<details>
<summary>Procedura di sideload e verifica</summary>

1. Download a current **Android System WebView** APK — package **`com.android.webview`**. **LineageOS** System WebView is the recommended build across Android versions: 138 is the last for Android 8.1, and 150 covers Android 10+ (both in the mirror). It's vanilla Chromium, so it doesn't carry Cromite's autoplay block that stops HA camera streams. It uses the `com.android.webview` package, so it's picked as the provider automatically (no allowlist editing, no extra app), and it's open / freely redistributable. Match your panel's ABI; per-panel downloads are above.

> [!IMPORTANT]
> The simple sideload below works on panels whose ROM waives the WebView signature check (e.g. the NSPanel Pro's userdebug build). **Signature-locked panels (the TPA10, and likely other vendor user builds) reject a plain sideload** — `signatures do not match`. Those need the one-time root swap (replace the system WebView file + clear its `packages.xml` entry); see [tpa10.md → WebView](tpa10.md#webview-esegui-prima-laggiornamento) for the exact procedure.
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

Schede informative per pannello: [NSPanel Pro](nspanel-pro.md) · [TPA10](tpa10.md) · [WF1589T](wf1589t.md) · [S9E](../../hardware/s9e.md) · [SMT1019](../../hardware/smt1019.md) · [ZX-SMT156](../../hardware/zx-smt156.md) · [Shelly Wall Display](../../hardware/shelly-wall-display.md).
