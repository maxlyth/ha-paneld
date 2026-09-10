> [!IMPORTANT]
> Questo documento è generato automaticamente e verificato mediante controlli incrociati automatici, ma non è stato rivisto sistematicamente da persone che parlano questa lingua. La documentazione in inglese fa fede. [Leggi la fonte in inglese](../../hardware/nspanel-pro.md) oppure [apri una segnalazione per correggere la traduzione](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Sonoff NSPanel Pro (Rockchip PX30 / rk3326)

L'NSPanel Pro originale è un piccolo pannello **quadrato da 480×480** PX30 con un **coordinatore Zigbee 3.0 integrato**, senza NFC/IR e con la CPU a minor consumo tra i pannelli qui documentati. Le varianti **86P** e **120P** utilizzano display e schede diversi — consulta [Varianti](#varianti-86p-contro-120p). Questa pagina è stata ricostruita principalmente mediante reverse engineering su un **86P** reale (Android 8.1, con root, toolbox `su`) e tratta queste varianti originali, salvo diversa indicazione esplicita.

> [!TIP]
> Most-needed facts: ships **`userdebug` with no adb password** (`adb root` just works); **LED is not characterised** (no controllable RGB node found); light + proximity are **app-direct**; the on-board **EFR32 Zigbee radio** is managed over a local broker, not by reflashing. Update the **WebView first** — see [WebView — update this first](#webview-esegui-prima-questo-aggiornamento).

| | |
|---|---|
| SoC | Rockchip **PX30 / rk3326** |
| CPU | 4× **Cortex-A35** @ fino a **1,512 GHz** (inattivo a 408 MHz) |
| GPU | **Mali-G31** (confermato dal dispositivo) |
| Display | **quadrato da 480×480** (1:1), ~4", 160 dpi (mdpi, ben corrispondenti ai ~170 ppi fisici), 60 Hz → un'area di visualizzazione di **480×480 dp** effettivi |
| RAM | **2 GB** (≈1960 MB utilizzabili) |
| Archiviazione | eMMC; `/data` ≈ 3,5 GB |
| Android | 8.1 (API 27) |
| ABI | arm64-v8a |
| Radio | **Zigbee 3.0** (coordinatore EFR32 di Silicon Labs su UART `ttyS5` — vedi sotto), Wi-Fi, Bluetooth. Niente NFC, IR, Ethernet, cellulare. |

> [!NOTE]
> Il Cortex-A35 è un core orientato all'efficienza, con prestazioni per ciclo nettamente inferiori rispetto all'A55 (TPA10) o all'A72 (WF1589T). Insieme ai 2 GB di RAM, ciò rende l'NSPanel Pro il **modello dalle prestazioni più modeste** fra i tre pannelli qui documentati — consulta il [confronto delle prestazioni](README.md#confronto-delle-prestazioni-e-implementazione-pratica).

> [!TIP]
> Changing firmware on a button-less panel? Read [Firmware backup & restore](../../firmware-backup-restore.md) first — the NSPanel Pro (PX30) uses [seaky's roottool/tools](../../firmware-backup-restore.md#per-panel-notes) rather than `rkdeveloptool`.

## Varianti: 86P contro 120P

La linea NSPanel Pro originale comprende due pannelli fisicamente diversi, denominati in base alle scatole da incasso europee da **86 mm** e **120 mm**. La tabella delle specifiche precedente e gran parte di questa pagina si basano su un **86P**; il **120P** utilizza una scheda diversa:

| | NSPanel Pro **86P** | NSPanel Pro **120P** |
|---|---|---|
| SoC | Rockchip **PX30** | Rockchip **RK3326-S** (stessa famiglia PX30/RK3326; `ro.board.platform=rk3326`, albero dei dispositivi `rockchip,px30`) |
| Display | **480×480** quadrato, ~160 dpi, solo verticale | **750×1334** in verticale, **240 dpi** (override impostato a 250); orientamento orizzontale disponibile; circa 1 cm più stretto e più lungo dell'86P |
| ID build | entrambi riportano `ro.product.model/device/name = px30_evb` (nome della scheda Rockchip condivisa - *non* un discriminatore di variante affidabile) | come 86P |
| `ro.product.version` | `s6_android_x.y.z`-classe | `NSPanelXXXP_x.y.z` (canale OTA `nspanel-pro-ver120`, ROM completa `SN_3326S_750X1334_…`) |
| Formato OTA | ROM completa fino alla **4.0.12**; le versioni successive presenti nell'indice vengono distribuite come diff o solo come APK (consulta l'[indice del firmware](../../hardware/nspanel-pro-firmware.md)) | come 86P |
| Firmware di prossimità | **La versione 4.0.12 ha ripristinato le letture** con valori di distanza | è rimasto **binario** alla versione 4.x (divergenza del kernel per modello - vedere [Sensori](#sensori-luce-e-prossimità-sono-accessibili-direttamente-dallapp)) |

Entrambi condividono la radio Zigbee EFR32, Android 8.1 (AOSP), arm64-v8a e la storia di root/ripristino di seguito. Verificato in tempo reale su un 120P (fw `NSPanel120P_3.7.1`): `wm size`=750×1334, densità 240, `ro.board.platform=rk3326`.

> [!NOTE]
> This page does not crown a firmware version in prose — the generated [complete index](../../hardware/nspanel-pro-firmware-archive.md) is the authority, and it goes stale less often. The flashing procedure is hardware-verified through **4.4.0**; releases indexed past that are CDN-verified only, never live-flash verified here. As of 2026-08-14, for the most recently added of them no vendor documentation was found: [Sonoff's public changelog](https://sonoff.tech/en-us/blogs/news/sonoff-nspanel-pro-version-update-information-and-faq) documents up to **4.6.0**, 4.6.2 and 4.8.0 were located only by probing the CDN, and 4.7.0 is discussed only in an [eWeLink user feedback thread](https://forum.ewelink.cc/t/nspanel-pro-v4-7-0-feeback/208789) — a discussion thread, not a release announcement. The **4.5.3** release is a ROM diff on 120P but an APK-only update on 86P, and **4.6.2** is an app-only update with no ROM diff on either channel, so an upgrade is not always a single hop. Absence from the index means not-found-by-probe; the CDN cannot be listed, so it is never proof a build does not exist. The CoolKit CDN scheme and the full flashing how-to are on the [firmware & flashing page](../../hardware/nspanel-pro-firmware.md); every verified OTA URL is in the [complete index](../../hardware/nspanel-pro-firmware-archive.md), and the community-facing subset is the Discussion linked from there, which is regenerated from this repo's data files and can lag them.
>
> **⚠ Community reports describe restart loops on 4.5.1 / 4.5.2** (~10–60 min intervals on both models). For 4.7.0, the user feedback thread contains reports of sub-device connectivity trouble after updating, some resolved by a reboot and others described as continuing; this project has not reproduced or quantified them, so treat them as unverified user reports rather than a known regression. Verify any newer release on one panel before deploying widely; **4.0.12** remains the conservative full-ROM checkpoint to pin. The firmware Discussion carries the current community evidence, regenerated from this repo's index whenever the scheduled monitor next runs.

### Peculiarità del firmware per versione

Behaviour that changes across eWeLink firmware versions, oldest first. `ro.product.version` is the **internal** id (`s6_android_x.y.z` on the 86P / `NSPanelXXXP_x.y.z` on the 120P) — *not* the marketing/OTA number the eWeLink app shows (4.0.12, 4.5.x). Detection and any version-keyed logic must read `ro.product.version`, not the marketing string.

| Firmware | Caratteristica/comportamento | Impatto: cosa fare |
|---|---|---|
| **versioni meno recenti (prima della 1.3.2)** | Nessuna attivazione/disattivazione adb in-app; opzioni sviluppatore non raggiungibili dall'interfaccia utente | Enable adb via the internal **OTG port** (open the case) — [Gaining adb + root](#ottenere-accesso-adb--root). |
| **v1.3.2+** | abilitazione adb spostata nell'app eWeLink | eWeLink → *Device Settings* → tap **Device ID ×8** → developer mode → adb. |
| **v1.4+** | Modalità sviluppatore **rimossa** dall'interfaccia utente | Enable adb via the **5× power-cycle** at the Sonoff boot animation — [Gaining adb + root](#ottenere-accesso-adb--root). |
| **3.5.1 (86P, verificato)** | La WebView di sistema stock è **Chromium 107.0.5304.105**: decisamente troppo vecchia per visualizzare una dashboard HA moderna; altri firmware potrebbero differire | Check and update the WebView **first** — [WebView — update this first](#webview-esegui-prima-questo-aggiornamento). ha-paneld's panel-health banner also flags outdated versions (min Chromium 110). |
| **v3.7.1** (120P, live) | Build di riferimento di base | `wm size`=750×1334, densità 240, `ro.board.platform=rk3326`. |
| **v4.0.0** (distribuzione dal 19-09-2025) | Il firmware stock **include F-Droid** e promuove l'installazione di app FOSS/HA; interfaccia utente decisamente più veloce | On-device install path opens — [Firmware v4.0.0](#firmware-v400-installazione-ufficiale-dellapp-f-droid). Confirm **APP** *and* **OS** version both read ≥ 4.0.0. |
| **v4.0.12** | Prossimità: **letture con valori di distanza** ripristinate sull'**86P**; il **120P rimane binario** (divergenza del kernel specifica del modello) | Recommended stable pin for HA-only panels. The panel's raw input shape is model- and firmware-specific, but ha-paneld learns and normalizes either form — see [Sensors](#sensori-luce-e-prossimità-sono-accessibili-direttamente-dallapp). |
| **v4.5.1 / v4.5.2** | **Widespread community restart-loop reports** (~10–60 min, both models); 4.5.2 is an APK-only layer on 4.5.1 | **Superseded by later releases.** Pin at **4.0.12** for maximum stability, or test a newer release on one panel first. Check the firmware Discussion for current evidence. |
| **v4.5.3** | Rilevamento automatico Matter e ottimizzazioni della gestione dello schermo; diff ROM sul 120P, ma aggiornamento solo APK sull'86P | No 4.5.3-specific restart-loop evidence found; superseded by later releases. |
| **v4.6.0** (giugno 2026) | **Portale Web locale** (`nspanelpro.local` — configurazione LAN, esportazione MQTT Discovery in HA, Matter Bridge); L'ispezione CDN del progetto ha rilevato differenze rispetto a 4.0.12 / 4.4.0 / 4.5.1 | Documentato nel registro delle modifiche pubblico di Sonoff. **4.6.2** è indicizzato come aggiornamento solo app senza differenze ROM su nessuno dei canali e non è stato trovato alcun 4.6.1. |
| **v4.7.0** (luglio 2026) | Discusso in un thread di feedback degli utenti eWeLink ma **assente dal registro delle modifiche pubblico di Sonoff**; copre i pannelli Gen1 e Gen2; gli utenti segnalano che è stato aggiunto il supporto per il relè Basic gen-5 (BASIC-1GS); L'ispezione della CDN del progetto ha rilevato differenze in entrata da 4.0.12 / 4.4.0 / 4.5.1 / 4.6.0 su entrambi i modelli, più 4.5.3 solo su 120P | Community reports of sub-device connectivity trouble, some reboot-resolved and some described as continuing; unverified by this project. Verify on one panel before deploying widely. |
| **v4.8.0** (agosto 2026) | **Nessun annuncio di rilascio e nessun registro delle modifiche trovato**: individuato sondando il CDN. Un post dello staff di eWeLink il 16-07-2026 nel [thread della roadmap](https://forum.ewelink.cc/t/nspanel-pro-roadmap-and-co-created-future/206240) lo ha programmato per agosto e ne ha confermato una funzionalità: un'opzione per aggiornare automaticamente il pannello tramite l'app eWeLink. L'ispezione del progetto CDN ha rilevato differenze in entrata da 4.0.12 / 4.4.0 / 4.5.1 / 4.6.0 / 4.7.0 su entrambi i modelli, più 4.5.3 solo su 120P | Contents otherwise unknown, and no community feedback thread has been found, so there is no report either way on stability. Treat it as unassessed rather than clean. **If the auto-update option ships enabled, a panel could take firmware unattended** — check that setting before relying on a pinned version. |

> [!NOTE]
> These are original 86P/120P quirks. The NSPanel Pro **Gen2** (RK3326-**S**, dual relays, EFR32**MG24**) is a different hardware target. Sonoff ships Gen1 and Gen2 on the same firmware version line (4.7.0 covers both), so do not infer a separate firmware line or assume every original-model note carries over.

Sibling Tuya-family boards — **S6E/T6E** (relay variants; S6E = T6E + 2 relays), [**S9E**](../../hardware/s9e.md) (Smatek), [**TPA10**](tpa10.md) (RK3566, Cortex-A55, Android 11) — are separate targets, not NSPanel Pro firmware.

> [!CAUTION]
> Detection can't rely on `ro.product.model` (both are `px30_evb`). Use `ro.product.version` / display metrics / `ro.board.platform` to tell 86P from 120P. Proximity behavior also differs between models and firmware, so ha-paneld learns from the live readings instead of selecting a firmware-specific classifier.

## Ottenere accesso adb + root

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
> Disable the eWeLink apps (`com.eWeLinkNSPro.dev`, `com.eWeLinkControlPanel`) only **after** adb + `su` are solid and you have a home/back alternative — ha-paneld's nav actions cover the latter. Note the eWeLink **Zigbee gateway** stack is independent of these apps and keeps running; manage it with ha-paneld's [Zigbee router switch](#gateway-zigbee) rather than removing it.

## Firmware v4.0.0: installazione ufficiale dell'app F-Droid

From **v4.0.0** (phased roll-out from 19 September 2025) the stock eWeLink firmware **officially bundles [F-Droid](https://f-droid.org/)** and promotes installing FOSS apps on the panel — Home Assistant's own Companion app is the headline example. Update via the panel (top drop-down → *Settings → About → Software update*) or the eWeLink app, then confirm both **APP Version** and **OS Version** read ≥ 4.0.0. Sonoff states F-Droid apps "will not affect NSPanel Pro's original features" (existing setups/automations stay intact) and that an app's F-Droid build "may differ slightly from the latest release". The update also markedly speeds up screen-swipe/UI responsiveness. Source: [Sonoff — NSPanel Pro V4.0.0 update](https://sonoff.tech/en-us/blogs/news/nspanel-pro-v4-0-0-update-now-supports-f-droid-and-home-assistant-app-install).

> [!NOTE]
> **Why this matters for ha-paneld.** F-Droid is a sanctioned, **on-device** install channel, so an APK can reach a panel with **no PC/adb** and F-Droid handles update notifications. **But F-Droid solves distribution, not privilege:** the headline features (overlay navbar, screen on/off, relays, button LEDs, Zigbee control) still need `su`, so the adb/root setup above stays a prerequisite for full function — only the non-privileged surface (MQTT discovery, sensors, brightness, HTTP UI) works on a stock unrooted panel.

## WebView: esegui prima questo aggiornamento

An 86P freshly flashed to firmware `3.5.1` (build `164637`) was verified with `com.android.webview` **107.0.5304.105** (Chromium 107), which is too old to render a current Home Assistant dashboard. Other firmware and models may differ, so check the installed provider before deciding whether to update. The archived OTA diff packages do not include a WebView APK, so this version was read from the live unit with `dumpsys webviewupdate`. That unit runs Chromium **138** after a clean adb update. See [Updating the system WebView](README.md#aggiornamento-della-webview-di-sistema).

## LED

Su questa unità non sono stati trovati né `/sys/class/leds` come nodo RGB né `/dev/ledjni`, quindi sull'NSPanel Pro **non è stato caratterizzato alcun LED RGB controllabile tramite app/sysfs** (a differenza del nodo `avsux` del TPA10 e di `/dev/ledjni` sul WF1589T). La luminosità e la retroilluminazione dello schermo utilizzano i percorsi Android standard.

## Sensori: luce e prossimità sono accessibili direttamente dall'app

A differenza del TPA10 (dove luce e temperatura richiedono il root), l'NSPanel Pro espone il sensore combinato Sensortek tramite le API `SensorManager` standard: `android.sensor.light`, `android.sensor.proximity` e `android.sensor.accelerometer` — tutti leggibili da un'app normale, senza root. Qui ha-paneld legge direttamente luce e prossimità. Non è installato alcun sensore di temperatura o umidità.

> [!NOTE]
> **Le letture di prossimità dipendono sia dal firmware SIA dal modello.** Il sensore è un Sensortek STK3A5x ToF, collocato in un'apertura del PCB superiore dietro il vetro di copertura. Il valore di riferimento a riposo varia notevolmente fra le unità (circa 1.000 su una e circa 4.000 su un'altra); conta solo la variazione *relativa*, quindi un valore di base elevato a riposo è normale e non indica un guasto. Fino a circa il firmware **3.3** forniva una lettura con valori di distanza (cadenza di circa 50 ms); fra circa **3.3 e 3.4** il driver del kernel è passato al valore binario 0/1. **La versione 4.0.12 ha ripristinato le letture con valori di distanza solo sull'86P** — il **120P è rimasto binario**. ha-paneld rileva il comportamento effettivo, gestisce entrambe le forme e normalizza l'intervallo utile nell'intera flotta; i profili non codificano più soglie specifiche del firmware né classificatori per letture con intervallo o binarie. (Fonti: strumenti seaky #142/#144/#171/#262.)

<details>
<summary>Dispositivi i2c associati (hardware reale)</summary>

| indirizzo i2c | driver / nome | Che cos'è |
|---|---|---|
| `0-0020` | `rk809` | PMIC |
| `1-001a` / `1-005a` | `CST226` / `CST226SE` | Controller touch capacitivo Hynitron |
| `2-003c` | `tp` | pannello touch |
| `2-0046` | `ls_stk3a5x` + `ps_stk3a5x` | Sensortek **STK3A5x** combo luce ambientale + prossimità |
| `2-0047` | `ls_stk3x3x` + `ps_stk3x3x` | Sensortek **STK3x3x** luce + prossimità (variante alternativa) |

</details>

## Gateway Zigbee

L'NSPanel Pro dispone di una radio **Silicon Labs EFR32 Zigbee 3.0** integrata su UART `/dev/ttyS5`, gestita da uno stack host del produttore (`/vendor/bin/siliconlabs_host/zgateway`) tramite un broker MQTT locale — lo stesso stack utilizzato dalle app eWeLink; il pannello viene quindi fornito come hub Zigbee eWeLink.

ha-paneld manages it directly (v0.6.1+): `switch.<panel>_zigbee_router` turns the panel into a Zigbee **router/repeater** that extends your existing mesh (it starts the gateway and ensures the Repeater role), and turns it back off again (stopping the gateway, freeing the radio). It works over the local broker — credential-free, no `ttyS5` handling. The panel then appears as a normal router in your ZHA / Zigbee2MQTT coordinator.

> [!NOTE]
> Switching role is **not a reflash** — there is no `.gbl`/bootloader step; it just sets the EZSP node type. For partition-level firmware work see [Firmware backup & restore](../../firmware-backup-restore.md).

> [!NOTE]
> **Non è documentato né caratterizzato alcun Thread Border Router.** Lo stack del produttore installato usa l'EFR32 come NCP Zigbee. Sebbene il chip EFR32MG21 supporti più protocolli, ciò non dimostra la presenza sul pannello di un firmware Thread o di un'implementazione Border Router; Sonoff documenta invece un Matter Bridge.
>
> **4.x reworked the Zigbee stack** — community inspection found a forked Zigbee2MQTT, a changed on-device MQTT password and a different boot sequence. [Sonoff documents coordinator↔router switching](https://sonoff.tech/blogs/news/nspanel-pro-v4-3-0-central-heating-redefining-whole-home-temperature-automation) in current firmware, but ha-paneld's private local-broker control path was built against ≤3.x and **may need adapting on 4.x**. (Community sources: seaky tools #244/#241/#255 and roottool#3.)

> [!WARNING]
> **A legacy vendor-native Zigbee-watchdog defect is confirmed by the reporter on NSPanel Pro 120 stock 3.8.0.** Firmware containing the recursive `LD_LIBRARY_PATH` assignment described in [Issue #34](https://github.com/maxlyth/ha-paneld/issues/34) can eventually make every external command launched by the watchdog fail with `E2BIG`, consume one CPU core and stop recovering a dead `zgateway`. A reboot resets the problem only temporarily. See [Performance tuning](../performance.md#escludere-il-difetto-legacy-del-watchdog-zigbee-del-firmware-originale-di-nspanel-pro) for the evidence boundary and repair-safety requirements. The reporter-provided workaround has not yet been independently validated by the project. Community inspection of 4.0.12 and 4.6.0 did not find the vulnerable assignment.

### Requisiti: firmware ≥ v2.2.0

The host stack is the **manufacturer's own** (eWeLink/Sonoff) gateway, versioned to match the panel firmware (e.g. `sonoff-v3.5.4`). Zigbee **router mode** was added in **NSPanel Pro firmware v2.2.0** (2023 — eWeLink app → *Device Settings → Pilot Features → Zigbee Mode*); local host-stack repeater support landed in gateway package v1.1.9. In practice:

- **Gateway present** (firmware ≥ v2.2.0, or side-loaded) → ha-paneld detects it and publishes `switch.<panel>_zigbee_router`. Toggle ON and the panel joins your coordinator as a router.
- **No gateway** (very old firmware, never provisioned) → the switch **doesn't appear** — it's gated on the gateway's launch script existing. Update firmware (≥ v2.2.0), or see migration below.

ha-paneld **drives** the gateway; it doesn't ship or install it (it's eWeLink's binary). Recent firmware (4.x) adds a Matter bridge and can export Zigbee devices to Home Assistant through MQTT Discovery — alternatives to the router role.

### Integrità del gateway e contenimento automatico

On a Zigbee-capable panel, `sensor.<panel>_zigbee_gateway_health` reports the vendor stack independently of the router switch. This means an unconfigured stock gateway is still visible without granting ha-paneld permission to stop it.

When the router switch has explicitly been turned ON, ha-paneld allows a 15-minute startup and pairing grace, then watches once per minute for two runaway signatures: an explicitly invalid/unjoined network combined with more than 50% of one CPU core for five consecutive samples, or at least three gateway PID changes within ten minutes. A joined router with sustained high CPU is warning-only and remains running. Unknown 4.x layouts or missing firmware-specific join evidence fail safe to `unknown`.

Turning the Zigbee router switch ON explicitly requests Repeater mode even when the vendor gateway process is already running, so an ON command sent while your ZHA/Zigbee2MQTT coordinator permits joining acts as a fresh join retry without spawning a second gateway supervisor.

The Configure tab shows a **Request join** action directly beneath the Zigbee router switch. The existing switch remains the only on/off control. Enable permit-join on ZHA/Zigbee2MQTT, then request joining and confirm that permit-join is open. The action reasserts Repeater mode, starts a fresh 15-minute grace and polls the bounded health status; it does not reboot or restart the panel. The button is unavailable while the router is disabled, already joined or cooling down after a recent request.

After the pairing grace, an enabled gateway that is still unjoined produces a persistent dashboard, Install-tab and status-API warning linked to that Configure action. Do not leave it in that state: repeated join retries can consume substantial CPU. Either join the panel as a router or turn off the Zigbee router switch.

If a configured legacy gateway meets a runaway rule, ha-paneld persists the router switch OFF and attempts one bounded containment. Vendor-native containment can target only the Sonoff guard, `zgateway`, and the matching local broker. If a process cannot be stopped, the respawner is removed where possible and surviving gateway work is demoted to nice 19 and Android's background cpuset. Turning the router switch ON later explicitly starts one fresh grace period and retry.

The health attributes include firmware/product version, gateway layout/package version, joined/role status, rounded gateway and guard CPU, recent restart count and containment result. They never include the Zigbee network key, raw local-broker credentials, radio MAC or raw gateway `netinfo`.

### Migrazione da NSPanelTools

[NSPanelTools (NSPPT)](https://github.com/seaky/nspanel_pro_tools_apk) side-loads the official Sonoff gateway package onto firmware that didn't ship it; many users run it today. ha-paneld coexists and can take over the gateway:

- **Side-by-side is fine.** ha-paneld's router control is idempotent — it **defers** to whatever already runs the gateway (won't double-start or fight NSPPT); auto-brightness is opt-in/off. Nothing conflicts by default.
- **Handing the gateway to ha-paneld:** the host stack lives in `/vendor` and **survives uninstalling the NSPPT app** (verified — a persistent hook even keeps boot-starting it). Remove the NSPPT APK and ha-paneld keeps driving the gateway; if the boot hook is also stripped, ha-paneld's boot-restore starts it when the switch was left ON.

> [!NOTE]
> Both tools touch the screen/sensors. Coexistence is benign today, but enabling overlapping features (e.g. wake-on-wave alongside an NSPPT equivalent) can cause redundant actions — remove NSPPT once ha-paneld covers your needs.

<details>
<summary>Dettagli interni dello stack host EZSP (topic del broker, supervisore, persistenza del ruolo)</summary>

On the legacy vendor-native ≤3.x stack, the radio runs **EZSP NCP firmware** (EFR32MG21, EZSP v8); `zgateway` is an EZSP *host* binary in `/vendor/bin/siliconlabs_host/`, kept alive by its own `guard_process.sh` supervisor (a 5-second loop, boot-started) and controlled over a **local mosquitto broker** on `127.0.0.1:1883` (anonymous — the `password_file` line is commented out in `mosquitto.conf`). The 4.x stack differs as described above.

- role status: `zigbee/system/network-role/information` → `{"role":"Repeater"|"Coordinator"}`
- role switch: `zigbee/system/network-role/switch` ← `{"role":"Repeater"}`

"Repeater" is router mode (extends an existing mesh — the supported sweet spot); the role persists in the NCP's NVM. The vendor `zgateway` survives removal of the eWeLink *apps* (it lives in `/vendor`, not in an APK).

For a full standalone Zigbee2MQTT/ZHA coordinator *on the panel* instead, see [seaky/nspanel_pro_zigbee](https://github.com/seaky/nspanel_pro_zigbee), which swaps the host stack (heavier; not what ha-paneld does).

</details>

## Riepilogo del modello di accesso

- **Luce/prossimità/accelerometro**: accesso diretto dall'app (`SensorManager`).
- **Screen brightness / sleep / navigate / TTS**: standard Android paths (`su` for true backlight-off).
- **LED**: nessuno caratterizzato.
- **Zigbee**: EFR32 radio managed via the on-device gateway's local broker (`switch.<panel>_zigbee_router`).
- **Radio**: Zigbee 3.0 + Wi-Fi/BT.

## Aspettative di prestazione

L'NSPanel Pro è **limitato dalla CPU e dalla RAM** con dashboard complesse:

- A riposo funziona a 408 MHz con circa 500 MB di RAM in uso; una dashboard Lovelace pesante mette sotto forte carico sia la CPU sia la RAM.
- **I 2 GB di RAM sono il limite principale** — la WebView della dashboard, Android e le app in background competono per questa memoria; dashboard grandi con molte schede, immagini pesanti, grafici cronologici estesi o schede personalizzate onerose causano ricaricamenti e scatti della WebView.
- I core A35 rendono le transizioni di pagina e le animazioni visibilmente più lente rispetto ai pannelli A55/A72.

Quando utilizzi il renderer integrato di ha-paneld, inizia con il [filtro automatico delle entità della dashboard](../performance.md#1-filtrare-la-sottoscrizione-alle-entità-del-renderer-integrato), affinché il pannello non elabori gli stati che la dashboard non visualizza mai. Usa quindi le schede delle prestazioni nella scheda Dashboard per individuare le viste ancora pesanti, la pressione sulla memoria o i limiti termici prima di semplificare la dashboard. Il filtro non è disponibile per il renderer Companion; in quel caso le opzioni supportate restano l'ottimizzazione degli aggiornamenti lato sorgente e una dashboard più leggera.

---

Consulta l'[indice hardware dei pannelli](README.md) per il confronto tra pannelli e il metodo utilizzato, nonché le pagine di riferimento per [TPA10](tpa10.md) / [WF1589T](wf1589t.md) / [S9E](../../hardware/s9e.md).
