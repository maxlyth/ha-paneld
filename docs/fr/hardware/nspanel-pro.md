> [!IMPORTANT]
> Ce document est généré automatiquement et fait l’objet d’une vérification croisée automatique, mais il n’a pas été systématiquement relu par des locuteurs de cette langue. La documentation en anglais fait foi. [Consulter la source en anglais](../../hardware/nspanel-pro.md) ou [signaler une correction de traduction](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Sonoff NSPanel Pro (Rockchip PX30 / rk3326)

Le NSPanel Pro d’origine est un petit panneau PX30 **carré de 480×480**, doté d’un **coordinateur Zigbee 3.0** intégré, sans NFC ni IR, et équipé du processeur le moins puissant des panneaux documentés ici. Ses variantes **86P** et **120P** utilisent des écrans et des cartes différents — voir [Variantes](#variantes--86p-et-120p). Cette page repose principalement sur la rétro-ingénierie d’un **86P** réel (Android 8.1, rooté, boîte à outils `su`) et couvre ces variantes d’origine, sauf indication explicite contraire.

> [!TIP]
> Most-needed facts: ships **`userdebug` with no adb password** (`adb root` just works); **LED is not characterised** (no controllable RGB node found); light + proximity are **app-direct**; the on-board **EFR32 Zigbee radio** is managed over a local broker, not by reflashing. Update the **WebView first** — see [WebView — update this first](#webview--à-mettre-à-jour-en-premier).

| | |
|---|---|
| SoC | Rockchip **PX30 / rk3326** |
| CPU | 4× **Cortex-A35** jusqu’à **1,512 GHz** (408 MHz au repos) |
| GPU | **Mali-G31** (confirmé sur l’appareil) |
| Écran | **Carré de 480×480** (1:1), ~4", 160 dpi (mdpi, bien adapté aux ~170 ppp physiques), 60 Hz → ce qui donne **480×480 dp** de surface utile |
| RAM | **2 Go** (≈1 960 Mo utilisables) |
| Stockage | eMMC ; `/data` ≈ 3,5 Go |
| Android | 8.1 (API 27) |
| ABI | arm64-v8a |
| Radios | **Zigbee 3.0** (coordinateur Silicon Labs EFR32 sur UART `ttyS5` — voir ci-dessous), Wi-Fi, Bluetooth. Ni NFC, ni IR, ni Ethernet, ni réseau cellulaire. |

> [!NOTE]
> Le Cortex-A35 est un cœur économe dont les performances à fréquence égale sont nettement inférieures à celles de l’A55 (TPA10) ou de l’A72 (WF1589T). Avec ses 2 Go de RAM, le NSPanel Pro offre les **performances d’entrée de gamme** parmi les trois panneaux documentés ici — voir la [comparaison des performances](README.md#comparaison-des-performances-et-déploiement-pratique).

> [!TIP]
> Changing firmware on a button-less panel? Read [Firmware backup & restore](../../firmware-backup-restore.md) first — the NSPanel Pro (PX30) uses [seaky's roottool/tools](../../firmware-backup-restore.md#per-panel-notes) rather than `rkdeveloptool`.

## Variantes — 86P et 120P

La gamme NSPanel Pro d’origine existe sous la forme de deux panneaux physiquement différents, nommés d’après les boîtiers muraux européens de **86 mm** et de **120 mm**. Le tableau des caractéristiques ci-dessus et l’essentiel de cette page ont été relevés sur un **86P** ; le **120P** utilise une carte différente :

| | NSPanel Pro **86P** | NSPanel Pro **120P** |
|---|---|---|
| SoC | Rockchip **PX30** | Rockchip **RK3326-S** (même famille PX30/RK3326 ; `ro.board.platform=rk3326`, arbre de périphériques `rockchip,px30`) |
| Écran | **480×480** carré, ~160 dpi, portrait uniquement | **750×1334** en portrait, **240 dpi** (valeur forcée à 250) ; paysage disponible ; ~1 cm plus étroit et plus long que le 86P |
| Identifiants de build | les deux indiquent `ro.product.model/device/name = px30_evb` (nom de carte Rockchip commun — ce n’est *pas* un moyen fiable de distinguer les variantes) | comme le 86P |
| `ro.product.version` | `s6_android_x.y.z`-type | `NSPanelXXXP_x.y.z` (canal OTA `nspanel-pro-ver120`, ROM complète `SN_3326S_750X1334_…`) |
| Forme de la mise à jour OTA | ROM complète jusqu’à **4.0.12** ; les versions répertoriées ensuite sont distribuées sous forme de mises à jour différentielles ou de mises à jour de l’APK uniquement (voir l’[index des firmwares](../../hardware/nspanel-pro-firmware.md)) | comme le 86P |
| Comportement de proximité selon le firmware | **La version 4.0.12 a rétabli les mesures graduelles** de proximité | est resté **binaire** en version 4.x (divergence du noyau selon le modèle — voir [Capteurs](#capteurs--luminosité-et-proximité-accessibles-directement-par-lapplication)) |

Les deux modèles partagent la radio Zigbee EFR32, Android 8.1 (AOSP), arm64-v8a, ainsi que les modalités d’accès root et de récupération décrites ci-dessous. Vérification sur un 120P réel (firmware `NSPanel120P_3.7.1`) : `wm size`=750×1334, densité 240, `ro.board.platform=rk3326`.

> [!NOTE]
> This page does not crown a firmware version in prose — the generated [complete index](../../hardware/nspanel-pro-firmware-archive.md) is the authority, and it goes stale less often. The flashing procedure is hardware-verified through **4.4.0**; releases indexed past that are CDN-verified only, never live-flash verified here. As of 2026-08-14, for the most recently added of them no vendor documentation was found: [Sonoff's public changelog](https://sonoff.tech/en-us/blogs/news/sonoff-nspanel-pro-version-update-information-and-faq) documents up to **4.6.0**, 4.6.2 and 4.8.0 were located only by probing the CDN, and 4.7.0 is discussed only in an [eWeLink user feedback thread](https://forum.ewelink.cc/t/nspanel-pro-v4-7-0-feeback/208789) — a discussion thread, not a release announcement. The **4.5.3** release is a ROM diff on 120P but an APK-only update on 86P, and **4.6.2** is an app-only update with no ROM diff on either channel, so an upgrade is not always a single hop. Absence from the index means not-found-by-probe; the CDN cannot be listed, so it is never proof a build does not exist. The CoolKit CDN scheme and the full flashing how-to are on the [firmware & flashing page](../../hardware/nspanel-pro-firmware.md); every verified OTA URL is in the [complete index](../../hardware/nspanel-pro-firmware-archive.md), and the community-facing subset is the Discussion linked from there, which is regenerated from this repo's data files and can lag them.
>
> **⚠ Community reports describe restart loops on 4.5.1 / 4.5.2** (~10–60 min intervals on both models). For 4.7.0, the user feedback thread contains reports of sub-device connectivity trouble after updating, some resolved by a reboot and others described as continuing; this project has not reproduced or quantified them, so treat them as unverified user reports rather than a known regression. Verify any newer release on one panel before deploying widely; **4.0.12** remains the conservative full-ROM checkpoint to pin. The firmware Discussion carries the current community evidence, regenerated from this repo's index whenever the scheduled monitor next runs.

### Particularités selon la version du firmware

Behaviour that changes across eWeLink firmware versions, oldest first. `ro.product.version` is the **internal** id (`s6_android_x.y.z` on the 86P / `NSPanelXXXP_x.y.z` on the 120P) — *not* the marketing/OTA number the eWeLink app shows (4.0.12, 4.5.x). Detection and any version-keyed logic must read `ro.product.version`, not the marketing string.

| Firmware | Particularité / comportement | Impact — marche à suivre |
|---|---|---|
| **ancien (avant 1.3.2)** | Aucun bouton adb dans l’application ; options pour les développeurs inaccessibles depuis l’interface | Enable adb via the internal **OTG port** (open the case) — [Gaining adb + root](#obtention-de-laccès-adb-et-root). |
| **v1.3.2 et ultérieures** | L’activation d’adb a été déplacée dans l’application eWeLink | eWeLink → *Device Settings* → tap **Device ID ×8** → developer mode → adb. |
| **v1.4 et ultérieures** | Mode développeur **supprimé** de l’interface | Enable adb via the **5× power-cycle** at the Sonoff boot animation — [Gaining adb + root](#obtention-de-laccès-adb-et-root). |
| **3.5.1 (86P, vérifiée)** | Le WebView système d’origine utilise **Chromium 107.0.5304.105** — version beaucoup trop ancienne pour afficher un tableau de bord HA moderne ; les autres firmwares peuvent différer | Check and update the WebView **first** — [WebView — update this first](#webview--à-mettre-à-jour-en-premier). ha-paneld's panel-health banner also flags outdated versions (min Chromium 110). |
| **v3.7.1** (120P, vérifié sur matériel réel) | Build initial de référence | `wm size`=750×1334, densité 240, `ro.board.platform=rk3326`. |
| **v4.0.0** (déploiement le 19/09/2025) | Le firmware d’origine **inclut F-Droid** et met en avant l’installation d’applications FOSS/HA ; interface nettement plus rapide | On-device install path opens — [Firmware v4.0.0](#firmware-v400--installation-officielle-dapplications-via-f-droid). Confirm **APP** *and* **OS** version both read ≥ 4.0.0. |
| **v4.0.12** | Mesures de proximité **graduelles** rétablies sur le **86P** ; le **120P reste binaire** (divergence du noyau selon le modèle) | Recommended stable pin for HA-only panels. The panel's raw input shape is model- and firmware-specific, but ha-paneld learns and normalizes either form — see [Sensors](#capteurs--luminosité-et-proximité-accessibles-directement-par-lapplication). |
| **v4.5.1 / v4.5.2** | **Widespread community restart-loop reports** (~10–60 min, both models); 4.5.2 is an APK-only layer on 4.5.1 | **Superseded by later releases.** Pin at **4.0.12** for maximum stability, or test a newer release on one panel first. Check the firmware Discussion for current evidence. |
| **v4.5.3** | Découverte automatique de Matter et optimisations de la gestion de l’écran ; mise à jour différentielle de la ROM sur le 120P, mais mise à jour de l’APK uniquement sur le 86P | No 4.5.3-specific restart-loop evidence found; superseded by later releases. |
| **v4.6.0** (juin 2026) | **Portail Web local** (`nspanelpro.local` — configuration sur le réseau local, export MQTT Discovery vers HA, pont Matter) ; l’inspection du CDN par le projet a trouvé des paquets de mise à jour différentielle depuis les versions 4.0.12 / 4.4.0 / 4.5.1 | Documentée dans le journal public des modifications de Sonoff. **4.6.2** est répertoriée comme une mise à jour de l’application uniquement, sans mise à jour différentielle de la ROM sur aucun des deux canaux, et aucune version 4.6.1 n’a été trouvée. |
| **v4.7.0** (juillet 2026) | Évoquée dans un fil de retours d’utilisateurs eWeLink, mais **absente du journal public des modifications de Sonoff** ; couvre les panneaux Gen1 et Gen2 ; selon les utilisateurs, ajoute la prise en charge du relais Basic de 5e génération (BASIC-1GS) ; l’inspection du CDN par le projet a trouvé, sur les deux modèles, des paquets de mise à jour différentielle depuis les versions 4.0.12 / 4.4.0 / 4.5.1 / 4.6.0, ainsi que depuis la version 4.5.3 sur le 120P uniquement | Community reports of sub-device connectivity trouble, some reboot-resolved and some described as continuing; unverified by this project. Verify on one panel before deploying widely. |
| **v4.8.0** (août 2026) | **Aucune annonce de version ni aucun journal des modifications n’ont été trouvés** — version découverte en sondant le CDN. Dans le [fil consacré à la feuille de route](https://forum.ewelink.cc/t/nspanel-pro-roadmap-and-co-created-future/206240), une publication d’un membre du personnel d’eWeLink datée du 16/07/2026 la prévoyait pour août et confirmait une fonctionnalité : une option permettant de mettre automatiquement à jour le panneau via l’application eWeLink. L’inspection du CDN par le projet a trouvé, sur les deux modèles, des paquets de mise à jour différentielle depuis les versions 4.0.12 / 4.4.0 / 4.5.1 / 4.6.0 / 4.7.0, ainsi que depuis la version 4.5.3 sur le 120P uniquement | Contents otherwise unknown, and no community feedback thread has been found, so there is no report either way on stability. Treat it as unassessed rather than clean. **If the auto-update option ships enabled, a panel could take firmware unattended** — check that setting before relying on a pinned version. |

> [!NOTE]
> These are original 86P/120P quirks. The NSPanel Pro **Gen2** (RK3326-**S**, dual relays, EFR32**MG24**) is a different hardware target. Sonoff ships Gen1 and Gen2 on the same firmware version line (4.7.0 covers both), so do not infer a separate firmware line or assume every original-model note carries over.

Sibling Tuya-family boards — **S6E/T6E** (relay variants; S6E = T6E + 2 relays), [**S9E**](../../hardware/s9e.md) (Smatek), [**TPA10**](tpa10.md) (RK3566, Cortex-A55, Android 11) — are separate targets, not NSPanel Pro firmware.

> [!CAUTION]
> Detection can't rely on `ro.product.model` (both are `px30_evb`). Use `ro.product.version` / display metrics / `ro.board.platform` to tell 86P from 120P. Proximity behavior also differs between models and firmware, so ha-paneld learns from the live readings instead of selecting a firmware-specific classifier.

## Obtention de l’accès adb et root

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
> Disable the eWeLink apps (`com.eWeLinkNSPro.dev`, `com.eWeLinkControlPanel`) only **after** adb + `su` are solid and you have a home/back alternative — ha-paneld's nav actions cover the latter. Note the eWeLink **Zigbee gateway** stack is independent of these apps and keeps running; manage it with ha-paneld's [Zigbee router switch](#passerelle-zigbee) rather than removing it.

## Firmware v4.0.0 — installation officielle d’applications via F-Droid

From **v4.0.0** (phased roll-out from 19 September 2025) the stock eWeLink firmware **officially bundles [F-Droid](https://f-droid.org/)** and promotes installing FOSS apps on the panel — Home Assistant's own Companion app is the headline example. Update via the panel (top drop-down → *Settings → About → Software update*) or the eWeLink app, then confirm both **APP Version** and **OS Version** read ≥ 4.0.0. Sonoff states F-Droid apps "will not affect NSPanel Pro's original features" (existing setups/automations stay intact) and that an app's F-Droid build "may differ slightly from the latest release". The update also markedly speeds up screen-swipe/UI responsiveness. Source: [Sonoff — NSPanel Pro V4.0.0 update](https://sonoff.tech/en-us/blogs/news/nspanel-pro-v4-0-0-update-now-supports-f-droid-and-home-assistant-app-install).

> [!NOTE]
> **Why this matters for ha-paneld.** F-Droid is a sanctioned, **on-device** install channel, so an APK can reach a panel with **no PC/adb** and F-Droid handles update notifications. **But F-Droid solves distribution, not privilege:** the headline features (overlay navbar, screen on/off, relays, button LEDs, Zigbee control) still need `su`, so the adb/root setup above stays a prerequisite for full function — only the non-privileged surface (MQTT discovery, sensors, brightness, HTTP UI) works on a stock unrooted panel.

## WebView — à mettre à jour en premier

An 86P freshly flashed to firmware `3.5.1` (build `164637`) was verified with `com.android.webview` **107.0.5304.105** (Chromium 107), which is too old to render a current Home Assistant dashboard. Other firmware and models may differ, so check the installed provider before deciding whether to update. The archived OTA diff packages do not include a WebView APK, so this version was read from the live unit with `dumpsys webviewupdate`. That unit runs Chromium **138** after a clean adb update. See [Updating the system WebView](README.md#mise-à-jour-du-webview-système).

## LED

Aucun nœud RGB dans `/sys/class/leds` ni aucun périphérique `/dev/ledjni` n’a été trouvé sur cet appareil ; aucune **LED RGB contrôlable par une application ou par sysfs n’est donc caractérisée** sur le NSPanel Pro (contrairement au nœud `avsux` du TPA10 et au périphérique `/dev/ledjni` du WF1589T). La luminosité de l’écran et le rétroéclairage utilisent les interfaces Android standard.

## Capteurs — luminosité et proximité accessibles directement par l’application

Contrairement au TPA10 (sur lequel la luminosité et la température nécessitent les privilèges root), le NSPanel Pro expose son capteur combiné Sensortek via le `SensorManager` standard : `android.sensor.light`, `android.sensor.proximity` et `android.sensor.accelerometer` — tous sont accessibles à une application ordinaire, sans privilèges root. ha-paneld lit ici directement la luminosité et la proximité. Aucun capteur de température ou d’humidité n’est installé.

> [!NOTE]
> **Les mesures de proximité dépendent À LA FOIS du firmware et du modèle.** Le capteur est un dispositif ToF Sensortek STK3A5x placé dans une découpe du circuit imprimé supérieur, derrière le verre de protection. La valeur de repos varie fortement d’un appareil à l’autre (environ 1 000 sur l’un, environ 4 000 sur un autre) ; seule la variation *relative* compte : une valeur de repos élevée est donc normale et non le signe d’un défaut. Jusqu’au firmware **3.3** environ, il produit une mesure graduelle (toutes les ~50 ms) ; vers les versions **3.3 à 3.4**, le pilote du noyau est passé à une valeur binaire 0/1. **La version 4.0.12 a rétabli les mesures graduelles sur le 86P uniquement** — le **120P est resté binaire**. ha-paneld gère les deux à partir du comportement observé et normalise la plage utile sur l’ensemble du parc ; les profils ne définissent plus de seuils propres à chaque firmware ni de classificateurs graduels/binaires. (Sources : outils seaky nº 142/144/171/262.)

<details>
<summary>Périphériques I²C associés à un pilote (matériel réel)</summary>

| Adresse i2c | Pilote / nom | Fonction |
|---|---|---|
| `0-0020` | `rk809` | PMIC |
| `1-001a` / `1-005a` | `CST226` / `CST226SE` | Contrôleur tactile capacitif Hynitron |
| `2-003c` | `tp` | Dalle tactile |
| `2-0046` | `ls_stk3a5x` + `ps_stk3a5x` | Capteur combiné Sensortek **STK3A5x** de luminosité ambiante et de proximité |
| `2-0047` | `ls_stk3x3x` + `ps_stk3x3x` | Capteur Sensortek **STK3x3x** de luminosité et de proximité (autre variante) |

</details>

## Passerelle Zigbee

Le NSPanel Pro intègre une **radio Zigbee 3.0 Silicon Labs EFR32** sur l’UART `/dev/ttyS5`, pilotée par une pile hôte du fabricant (`/vendor/bin/siliconlabs_host/zgateway`) via un broker MQTT local — la même pile que celle utilisée par les applications eWeLink ; le panneau est donc livré comme concentrateur Zigbee eWeLink.

ha-paneld manages it directly (v0.6.1+): `switch.<panel>_zigbee_router` turns the panel into a Zigbee **router/repeater** that extends your existing mesh (it starts the gateway and ensures the Repeater role), and turns it back off again (stopping the gateway, freeing the radio). It works over the local broker — credential-free, no `ttyS5` handling. The panel then appears as a normal router in your ZHA / Zigbee2MQTT coordinator.

> [!NOTE]
> Switching role is **not a reflash** — there is no `.gbl`/bootloader step; it just sets the EZSP node type. For partition-level firmware work see [Firmware backup & restore](../../firmware-backup-restore.md).

> [!NOTE]
> **Aucun routeur de bordure Thread n’est documenté ni caractérisé.** La pile du fournisseur installée utilise l’EFR32 comme NCP Zigbee. Bien que le composant EFR32MG21 soit compatible avec plusieurs protocoles, cela ne démontre pas la présence d’un firmware Thread ni d’une mise en œuvre de routeur de bordure sur le panneau ; Sonoff documente plutôt un pont Matter.
>
> **4.x reworked the Zigbee stack** — community inspection found a forked Zigbee2MQTT, a changed on-device MQTT password and a different boot sequence. [Sonoff documents coordinator↔router switching](https://sonoff.tech/blogs/news/nspanel-pro-v4-3-0-central-heating-redefining-whole-home-temperature-automation) in current firmware, but ha-paneld's private local-broker control path was built against ≤3.x and **may need adapting on 4.x**. (Community sources: seaky tools #244/#241/#255 and roottool#3.)

> [!WARNING]
> **A legacy vendor-native Zigbee-watchdog defect is confirmed by the reporter on NSPanel Pro 120 stock 3.8.0.** Firmware containing the recursive `LD_LIBRARY_PATH` assignment described in [Issue #34](https://github.com/maxlyth/ha-paneld/issues/34) can eventually make every external command launched by the watchdog fail with `E2BIG`, consume one CPU core and stop recovering a dead `zgateway`. A reboot resets the problem only temporarily. See [Performance tuning](../performance.md#écarter-lancien-défaut-du-watchdog-zigbee-du-firmware-dorigine-du-nspanel-pro) for the evidence boundary and repair-safety requirements. The reporter-provided workaround has not yet been independently validated by the project. Community inspection of 4.0.12 and 4.6.0 did not find the vulnerable assignment.

### Prérequis — firmware ≥ v2.2.0

The host stack is the **manufacturer's own** (eWeLink/Sonoff) gateway, versioned to match the panel firmware (e.g. `sonoff-v3.5.4`). Zigbee **router mode** was added in **NSPanel Pro firmware v2.2.0** (2023 — eWeLink app → *Device Settings → Pilot Features → Zigbee Mode*); local host-stack repeater support landed in gateway package v1.1.9. In practice:

- **Gateway present** (firmware ≥ v2.2.0, or side-loaded) → ha-paneld detects it and publishes `switch.<panel>_zigbee_router`. Toggle ON and the panel joins your coordinator as a router.
- **No gateway** (very old firmware, never provisioned) → the switch **doesn't appear** — it's gated on the gateway's launch script existing. Update firmware (≥ v2.2.0), or see migration below.

ha-paneld **drives** the gateway; it doesn't ship or install it (it's eWeLink's binary). Recent firmware (4.x) adds a Matter bridge and can export Zigbee devices to Home Assistant through MQTT Discovery — alternatives to the router role.

### État de la passerelle et confinement automatique

On a Zigbee-capable panel, `sensor.<panel>_zigbee_gateway_health` reports the vendor stack independently of the router switch. This means an unconfigured stock gateway is still visible without granting ha-paneld permission to stop it.

When the router switch has explicitly been turned ON, ha-paneld allows a 15-minute startup and pairing grace, then watches once per minute for two runaway signatures: an explicitly invalid/unjoined network combined with more than 50% of one CPU core for five consecutive samples, or at least three gateway PID changes within ten minutes. A joined router with sustained high CPU is warning-only and remains running. Unknown 4.x layouts or missing firmware-specific join evidence fail safe to `unknown`.

Turning the Zigbee router switch ON explicitly requests Repeater mode even when the vendor gateway process is already running, so an ON command sent while your ZHA/Zigbee2MQTT coordinator permits joining acts as a fresh join retry without spawning a second gateway supervisor.

The Configure tab shows a **Request join** action directly beneath the Zigbee router switch. The existing switch remains the only on/off control. Enable permit-join on ZHA/Zigbee2MQTT, then request joining and confirm that permit-join is open. The action reasserts Repeater mode, starts a fresh 15-minute grace and polls the bounded health status; it does not reboot or restart the panel. The button is unavailable while the router is disabled, already joined or cooling down after a recent request.

After the pairing grace, an enabled gateway that is still unjoined produces a persistent dashboard, Install-tab and status-API warning linked to that Configure action. Do not leave it in that state: repeated join retries can consume substantial CPU. Either join the panel as a router or turn off the Zigbee router switch.

If a configured legacy gateway meets a runaway rule, ha-paneld persists the router switch OFF and attempts one bounded containment. Vendor-native containment can target only the Sonoff guard, `zgateway`, and the matching local broker. If a process cannot be stopped, the respawner is removed where possible and surviving gateway work is demoted to nice 19 and Android's background cpuset. Turning the router switch ON later explicitly starts one fresh grace period and retry.

The health attributes include firmware/product version, gateway layout/package version, joined/role status, rounded gateway and guard CPU, recent restart count and containment result. They never include the Zigbee network key, raw local-broker credentials, radio MAC or raw gateway `netinfo`.

### Migration depuis NSPanelTools

[NSPanelTools (NSPPT)](https://github.com/seaky/nspanel_pro_tools_apk) side-loads the official Sonoff gateway package onto firmware that didn't ship it; many users run it today. ha-paneld coexists and can take over the gateway:

- **Side-by-side is fine.** ha-paneld's router control is idempotent — it **defers** to whatever already runs the gateway (won't double-start or fight NSPPT); auto-brightness is opt-in/off. Nothing conflicts by default.
- **Handing the gateway to ha-paneld:** the host stack lives in `/vendor` and **survives uninstalling the NSPPT app** (verified — a persistent hook even keeps boot-starting it). Remove the NSPPT APK and ha-paneld keeps driving the gateway; if the boot hook is also stripped, ha-paneld's boot-restore starts it when the switch was left ON.

> [!NOTE]
> Both tools touch the screen/sensors. Coexistence is benign today, but enabling overlapping features (e.g. wake-on-wave alongside an NSPPT equivalent) can cause redundant actions — remove NSPPT once ha-paneld covers your needs.

<details>
<summary>Fonctionnement interne de la pile hôte EZSP (sujets du broker, superviseur, persistance du rôle)</summary>

On the legacy vendor-native ≤3.x stack, the radio runs **EZSP NCP firmware** (EFR32MG21, EZSP v8); `zgateway` is an EZSP *host* binary in `/vendor/bin/siliconlabs_host/`, kept alive by its own `guard_process.sh` supervisor (a 5-second loop, boot-started) and controlled over a **local mosquitto broker** on `127.0.0.1:1883` (anonymous — the `password_file` line is commented out in `mosquitto.conf`). The 4.x stack differs as described above.

- role status: `zigbee/system/network-role/information` → `{"role":"Repeater"|"Coordinator"}`
- role switch: `zigbee/system/network-role/switch` ← `{"role":"Repeater"}`

"Repeater" is router mode (extends an existing mesh — the supported sweet spot); the role persists in the NCP's NVM. The vendor `zgateway` survives removal of the eWeLink *apps* (it lives in `/vendor`, not in an APK).

For a full standalone Zigbee2MQTT/ZHA coordinator *on the panel* instead, see [seaky/nspanel_pro_zigbee](https://github.com/seaky/nspanel_pro_zigbee), which swaps the host stack (heavier; not what ha-paneld does).

</details>

## Résumé du modèle d’accès

- **Luminosité / proximité / accéléromètre** : accès direct par l’application (`SensorManager`).
- **Screen brightness / sleep / navigate / TTS**: standard Android paths (`su` for true backlight-off).
- **LED** : aucune n’est caractérisée.
- **Zigbee**: EFR32 radio managed via the on-device gateway's local broker (`switch.<panel>_zigbee_router`).
- **Radios** : Zigbee 3.0 + Wi-Fi/BT.

## Performances attendues

Le NSPanel Pro est **limité par son CPU et sa RAM** pour les tableaux de bord élaborés :

- Au repos, il fonctionne à 408 MHz et utilise ≈500 Mo de RAM ; un tableau de bord Lovelace lourd sollicite fortement ces deux ressources.
- **Les 2 Go de RAM constituent la principale contrainte** — le WebView du tableau de bord, Android et les applications en arrière-plan se les disputent ; les grands tableaux de bord comportant de nombreuses cartes, de grandes images, de longs graphiques d’historique ou des cartes personnalisées gourmandes en ressources provoquent des rechargements de WebView et des saccades.
- Les cœurs A35 rendent les transitions de page et les animations sensiblement plus lentes que sur les panneaux A55/A72.

Avec le moteur de rendu intégré de ha-paneld, commencez par le [filtre automatique des entités du tableau de bord](../performance.md#1-filtrer-labonnement-aux-entités-du-moteur-de-rendu-intégré) afin que le panneau ne traite pas les états que son tableau de bord n’affiche jamais. Utilisez ensuite les cartes de performances de l’onglet Dashboard pour repérer les vues encore lourdes, la pression sur la mémoire ou les limites thermiques avant de simplifier le tableau de bord. Ce filtre n’est pas disponible avec le moteur de rendu Companion ; pour celui-ci, les options prises en charge restent l’ajustement de la fréquence des mises à jour à la source et un tableau de bord plus léger.

---

Consultez l’[index du matériel des panneaux](README.md) pour la comparaison entre panneaux et la méthode, ainsi que les références [TPA10](tpa10.md) / [WF1589T](wf1589t.md) / [S9E](../../hardware/s9e.md) pour les autres panneaux.
