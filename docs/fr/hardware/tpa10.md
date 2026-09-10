> [!IMPORTANT]
> Ce document est généré automatiquement et fait l’objet d’une vérification croisée automatique, mais il n’a pas été systématiquement relu par des locuteurs de cette langue. La documentation en anglais fait foi. [Consulter la source en anglais](../../hardware/tpa10.md) ou [signaler une correction de traduction](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Tuya TPA10 (Rockchip rk3566)

Un panneau rk3566 spacieux de **10" 1920×1200**, doté d’une unique LED RGB en façade, d’un rétroéclairage monochrome des boutons, d’un riche ensemble de capteurs (proximité ToF, température et humidité, luminosité ambiante) et de cinq boutons physiques, mais dépourvu de Zigbee, de NFC et d’infrarouge. Rétro-ingénierie effectuée sur un appareil réel (Android 11, rooté, avec `su`).

> [!TIP]
> Most-needed facts: adb is **password-protected** — use the USB diagnostics-app backdoor; LED and the root-only sensors need the **`hapaneld-helper` root helper daemon**; the front LED's `custom_animation` write can **reboot the panel** (see caution below). Update the **WebView first** — see [WebView — update this first](#webview--à-mettre-à-jour-en-premier).

| | |
|---|---|
| SoC | Rockchip **rk3566** |
| Écran | **1920×1200** (16:10 en orientation paysage), ~10,1" / **~226 ppp physiques**, 56 Hz. Densité logique Android d’usine/de base : **240 dpi** (souvent remplacée pour dimensionner le tableau de bord ; ha-paneld recommande 212) |
| Android | 11 (API 30) |
| ABI | armeabi-v7a (espace utilisateur 32 bits) |
| Communications radio | Wi-Fi, Bluetooth + BLE, ainsi qu’une fonctionnalité `com.smartos.xinch.platform.ethernet` du fournisseur (connexion filaire/PoE). **Ni Zigbee, ni NFC, ni infrarouge, ni réseau cellulaire.** |
| Root | `su` available; the LED/sysfs sensors are `system:system`, so a **root helper daemon is required** (see below). |

> [!TIP]
> Changing firmware on a button-less panel? Read [Firmware backup & restore](../../firmware-backup-restore.md) first. The TPA10 (rk3566, Android 11, 7.28 GB eMMC `mmcblk2`) has a verified software-entered Loader route through `adb reboot loader` and `rkdeveloptool`. The recessed [pin-hole button](#boutons) is not a Linux input, but its factory-reset or Maskrom behavior has not been safely confirmed; do not rely on it as a recovery route.

## Obtention des accès adb et root

The TPA10 ships with adb **password-protected** and network adb off. The reliable route to first access is the USB diagnostics-app backdoor (no password maths). `su` is already present, so once adb is in you have root. Distilled from [seaky/nspanel_pro_tools_apk#123](https://github.com/seaky/nspanel_pro_tools_apk/issues/123).

**1. Enable Developer options** — Settings → *About* → tap the build/version number 7×.

**2. First access — USB diagnostics-app backdoor (recommended).** Developer options exposes a Tuya engineering **diagnostics app** (Chinese-only UI). While that app is open, adb over the **USB** port is allowed *without* the password, and the session is already rooted. Connect USB and:

```bash
adb devices          # the panel appears
adb shell su 0 id    # uid=0 → root confirmed
```

The TPA10's adb-root is more dependable over the USB port than over the network.

**3. Make adb persist (root, password-free — the reliable route).** With the diagnostics app foreground you have a rooted adb session (`su` is present; `adb root` also works — it's a `userdebug` build). Use it to persist adb so you never need the test app or a password again. This survives the diagnostics app closing **and** a full reboot — verified on a live unit. Push + run as root:

```bash
# persist-adb.sh — run via the diagnostics-app backdoor:
#   adb push persist-adb.sh /data/local/tmp/ && adb shell su 0 sh /data/local/tmp/persist-adb.sh
settings put global adb_enabled 1                   # USB debugging, persisted in /data
settings put global development_settings_enabled 1  # keep Developer options visible
setprop persist.adb.tcp.port 5555                   # network adb on :5555 — persist.* survives reboot

# Pre-authorise each controlling machine so NO on-screen "Allow USB debugging" is needed after reboot.
# Append the contents of every workstation's ~/.android/adbkey.pub (one key per line):
mkdir -p /data/misc/adb
cat >> /data/misc/adb/adb_keys <<'KEYS'
PASTE-EACH-adbkey.pub-LINE-HERE
KEYS
chmod 640 /data/misc/adb/adb_keys
chown system:shell /data/misc/adb/adb_keys 2>/dev/null
restorecon /data/misc/adb/adb_keys 2>/dev/null

setprop ctl.restart adbd                            # apply now (and it auto-starts every boot)
echo "adb persisted: adb_enabled=$(settings get global adb_enabled) tcp=$(getprop persist.adb.tcp.port)"
```

The panel must be on Wi-Fi for the network route; thereafter `adb connect <panel-ip>:5555` works from any pre-authorised machine, across reboots, with the vendor apps closed. A `/data` wipe / factory reset clears `adb_keys` + `adb_enabled`, so re-run this after one.

> [!WARNING]
> **The Developer-options "Enable ADB" *password* is not a usable path — do not try to compute it.** Contrary to the [#123](https://github.com/seaky/nspanel_pro_tools_apk/issues/123) community recipe, it does not reproduce. Decompiling `checkDevPassword` in `com.smartos.xinch.setting` confirms the *shape*: `base64(takeLast(ro.tuya.uuid,3) + takeLast(deviceId,3))` then `takeLast(6)`, case-insensitive (or `takeLast(ro.tuya.uuid,6)` when `deviceId` is empty). But the `deviceId` field it uses could not be matched to any readable identifier — `ro.serialno`, `android_id` and `ro.tuya.key` were all rejected on a live unit. The app's logger is **not** logcat, so the expected value can't be read on-device either. The #123 worked example also has a typo (`11a`+`xia` written as `11xia`; it must be `11axia`). Use the root method above — it makes the password irrelevant.

> [!CAUTION]
> Disable the vendor `com.smartos.xinch.*` packages only as the **very last step**, after confirming adb is solid *and* you have a replacement for the hardware buttons. Disabling the hardware/setting apps before adb is reliable can lock you out. ha-paneld's remote nav actions (Back/Recents) and the button-backlight/LED entities replace the vendor app's functions.

> [!NOTE]
> Les applications embarquées du fournisseur n’ont *pas* constitué une source utile pour la rétro-ingénierie : `com.tuya.devicetest` est précompilée au format odex (aucun dex dans l’APK), tandis que `com.smartos.xinch.hardware` intègre le **SDK AVS (Alexa)** de Tuya (`libLibSampleApp.so`, 17 Mo) ainsi qu’un lecteur de clés (`libjnimain.so`). La source faisant autorité réside dans les nœuds sysfs auto-documentés de l’appareil lui-même.

## WebView — à mettre à jour en premier

The stock WebView is **Chrome 83** — far too old for a current HA frontend, so the dashboard shows blank or broken until you replace it. The recommended build is **LineageOS System WebView 150** (`armeabi-v7a`) — a current, maintained, vanilla-Chromium engine. Prefer it over Cromite: Cromite patches Chromium's autoplay content-setting to *block*, which stops Home Assistant camera-card (WebRTC) streams from starting without a tap; LineageOS leaves autoplay allowed, so camera streams start on their own.

Le TPA10 dispose d’une marge de décodage vidéo limitée dans WebView. Plusieurs cartes WebRTC 720p visibles peuvent perdre des images ou laisser un flux attendre sa première image alors qu’un flux unique fonctionne correctement. Dans ce cas, utilisez des sous-flux de résolution inférieure, moins de cartes en lecture automatique simultanée, ou des instantanés qui basculent vers la vidéo en direct lors d’un appui.

Download it from the ha-paneld mirror (stable URL):

```
https://github.com/maxlyth/ha-paneld/releases/download/webview-mirror/lineageos-webview-150.0.7871.63-arm.apk
```

### Pourquoi une installation classique ne fonctionne pas

The WebView is packaged as `com.android.webview`, so it must **replace** the system provider, and the two obvious routes both fail on this Android-11 panel:

- `adb install -r` is rejected — `INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`. Android only lets you update `com.android.webview` with an APK signed by the **same key** as what's already installed, and each WebView vendor uses a different key. This is also why **ha-paneld's built-in "Update WebView" / auto-update can't do this first swap** — it installs over `pm install`, which the panel blocks. The swap below is a **one-time** manual step; once LineageOS is in place, ha-paneld *can* auto-apply future LineageOS updates (same signer).
- The ROM's allowlist accepts only `com.android.webview` (not the `com.google.android.webview` variant), so a "…Google" build installs but is never selected.

### Méthode fonctionnelle (root) — remplacer le fichier et supprimer le verrou de signature

The panel is signature-locked, but it is rootable: the app can't `su`, but a shell can (`adb shell su root <cmd>`). The trick is to drop the new APK into the *system* WebView slot and delete its entry from the package database so PackageManager re-registers it **fresh** on reboot (which reads the new APK's own signature — no conflict).

```bash
IP=<panel-ip>:5555                                    # e.g. 192.168.1.50:5555
adb connect $IP
adb -s $IP shell su root id                           # confirm it prints uid=0(root)

# 1. push the new WebView, and back up the current WebView + package database first:
adb -s $IP push lineageos-webview-150.0.7871.63-arm.apk /data/local/tmp/wv-new.apk
adb -s $IP shell su root sh -c 'cp /product/app/webview/webview.apk /data/local/tmp/webview.bak;
                                cp /data/system/packages.xml /data/local/tmp/packages.xml.bak'

# 2. replace the system WebView APK (remount /product read-write first — verity must already be off;
#    a never-modified panel needs a one-time `adb root && adb disable-verity && adb reboot` beforehand):
adb -s $IP shell su root sh -c 'mount -o rw,remount /product;
    cp /data/local/tmp/wv-new.apk /product/app/webview/webview.apk;
    chmod 644 /product/app/webview/webview.apk; chown root:root /product/app/webview/webview.apk;
    restorecon /product/app/webview/webview.apk'

# 3. remove the single <package name="com.android.webview" …>…</package> element from packages.xml.
#    Do NOT hand-edit it — pull it, let a parser remove exactly that element, then push it back:
adb -s $IP shell su root sh -c 'cp /data/system/packages.xml /data/local/tmp/pkgs.xml; chmod 644 /data/local/tmp/pkgs.xml'
adb -s $IP pull /data/local/tmp/pkgs.xml packages.xml
python3 - <<'PY'
import xml.etree.ElementTree as ET
d = open('packages.xml', encoding='utf-8').read()
m = '<package name="com.android.webview"'
assert d.count(m) == 1, 'expected exactly one com.android.webview package'
s  = d.find(m); ls = d.rfind('\n', 0, s) + 1                      # start of that line
e  = d.find('</package>', s) + len('</package>'); le = d.find('\n', e) + 1  # end of its closing line
new = d[:ls] + d[le:]
ET.fromstring(new)                                                # abort if the result isn't valid XML
open('packages.xml', 'w', encoding='utf-8').write(new)
print('removed com.android.webview; XML still valid')
PY
adb -s $IP push packages.xml /data/local/tmp/pkgs.new
adb -s $IP shell su root sh -c 'cp /data/local/tmp/pkgs.new /data/system/packages.xml;
    chown system:system /data/system/packages.xml; chmod 660 /data/system/packages.xml;
    restorecon /data/system/packages.xml'

# 4. reboot — PackageManager registers the new WebView fresh:
adb -s $IP reboot
```

**If anything goes wrong**, revert with the backups from step 1: copy `/data/local/tmp/webview.bak` back over `/product/app/webview/webview.apk` and `/data/local/tmp/packages.xml.bak` back over `/data/system/packages.xml` (same `chown`/`chmod`/`restorecon`), then reboot.

> [!CAUTION]
> **The reported WebView version is wrong with this method — don't trust it.** `Settings → WebView` and `adb shell dumpsys webviewupdate` still show **`83.0.4103.120`**, because a sideloaded SystemWebView **stamps the OEM stock `versionName`/`versionCode`** to clear the panel's min-version gate and get selected. The *actual* engine is 150. Verify it by:
> - **User-Agent** — open any "what's my user agent" page on the panel; the UA contains `Chrome/150.0.7871.63`.
> - **ha-paneld** — the `:8888` info page / `/api/v1/diag` shows `engine Chromium 150.0.7871.63` (it reads the UA, not the stamped package version).

(This supersedes the earlier "clean adb sideload" note, which does **not** work on this signature-locked panel. Cromite 147 remains available in the mirror as a fallback — same procedure, different APK — if you ever need it.)

## LED

### LED RGB — pilote `avsux` (démon auxiliaire exécuté avec les droits root)

La LED RGB en façade est une LED **unique** (`avsux_info` → `led type:[single] nums:[1]`) rattachée au pilote de plateforme `leds_pwm_avs` (périphérique `avsux`), exposée sous `/sys/class/leds/avs-pwm-led/`.

> [!CAUTION]
> Writing `custom_animation` to `avsux_select` has been observed to **reboot the panel**. Use `avsux_animation` for colour; treat `avsux_select`/`custom_animation` as read-only unless testing.

There is **no app-accessible `/dev` node** for the LED (contrast the [WF1589T](wf1589t.md)'s `/dev/ledjni`), and the sysfs attributes are `system:system` — an `untrusted_app` cannot write them. ha-paneld therefore ships a small **root helper daemon** (`/system/bin/hapaneld-helper`, root, unix socket) that the app talks to; `SocketLedController` is the client. See [`helper/README.md`](../../../helper/README.md).

<details>
<summary>Attributs sysfs de `avs-pwm-led`</summary>

| Attribut | Droits | Utilisation |
|---|---|---|
| `brightness` | `system:system` rw | overall level 0–255 |
| `avsux_animation` | `system:system` rw | safe colour/animation write |
| `avsux_select` | `system:system` rw | `custom_animation[][0][0]:<dur_ms>:<RRGGBB>[,…≤12 slots]` |
| `avsux_firmware` | r | répertorie les animations nommées (`bootanime`, `idle`) |
| `avsux_info` | r | métadonnées (nombre/type de LED) |

</details>

### Rétroéclairage des boutons

`/sys/class/leds/button-backlight/brightness` — **monochrome** PWM, 0–255 (standard `leds_pwm` driver, device `pwmleds`). `system:system` 0664, so driven through the same `hapaneld-helper` daemon.

## Capteurs

Proximity is app-direct via `SensorManager`; temperature, humidity and ambient light are root-only (input subsystem / i2c) and need the helper daemon.

> [!TIP]
> Grâce au CHT8305, ce panneau peut servir de **capteur de température et d’humidité ambiantes** pour Home Assistant. Le démon auxiliaire le lit avec la commande `CHT8305` (une lecture ponctuelle `EVIOCGABS` de l’axe d’entrée `ABS_THROTTLE` du pilote, en faisant correspondre par leur nom les périphériques d’entrée `temperature`/`humidity`). ha-paneld expose ensuite via MQTT deux capteurs facultatifs, **Température ambiante** et **Humidité ambiante** (Configurer → Diagnostics ; désactivés par défaut). Un réglage avancé **Décalage de la température ambiante** (ou le paramètre `sensors.room_temp_offset_c` du profil) corrige l’auto-échauffement du panneau.

Grâce au capteur ToF du TPA10, la proximité repose réellement sur une mesure de distance, mais la HAL Android la quantifie. ha-paneld apprend les bornes et le sens de variation du capteur en fonctionnement, puis fournit la même échelle de proximité normalisée que sur les autres panneaux pris en charge au lieu de s’appuyer sur un seuil fixe propre à l’appareil.

<details>
<summary>Puces des capteurs et chemins d’accès</summary>

| Capteur | Puce | Accès |
|---|---|---|
| Proximité (ToF) | Vishay **VI5300** (i2c-3 `0x6c`, `proximity_vi5300`, interrogation toutes les 30 ms) | Android `SensorManager` `TYPE_PROXIMITY` (no root). Raw mm distance on the driver's i2c node (`…/i2c-3/3-006c`) needs root. |
| Température + humidité | **CHT8305** (`temperature_cht8305` @3-0040, `humidity_cht8305` @3-0040-1) | **Not** in `SensorManager`; reports via the **input subsystem** on i2c — root only. |
| Luminosité ambiante | **CG5256** (`light_cg5256`) | Not in `SensorManager` (root). |

</details>

## Boutons

Le TPA10 possède **trois catégories** de boutons physiques, confirmées sur l’appareil avec `getevent` :

- **Les quatre boutons latéraux** — `adc-keys`, des KeyEvents standard associés à `F1`–`F4`, interceptés par le filtre de touches d’accessibilité de ha-paneld (aucun chemin particulier).
- **Le cinquième bouton (orange)** — un `EV_SW` *commutateur*, et non une touche ; il est instrumenté par le lecteur evdev du daemon auxiliaire root et émis sous forme d’événement HA.
- **The pin-hole button** (recessed, beside the USB-C port) — recovery / reflash only, **not** HA-instrumentable.

<details>
<summary>Détail par bouton (scancodes, evdev, rôle dans la récupération)</summary>

**1. The four side buttons — `adc-keys`, standard KeyEvents.** On the rk3566 **SARADC** (`fe720000.saradc`), device `adc-keys1` (`/dev/input/event7`), scancodes `59`–`62`. Stock `Generic.kl` maps these to **`F1`–`F4`** → Android `KEYCODE_F1`–`F4`, which ha-paneld captures via its accessibility key-filter (no special path). They can be remapped by editing `/system/usr/keylayout/Generic.kl` (e.g. to `BRIGHTNESS_*` / `VOLUME_*`) on an su-capable unit.

**2. Le cinquième bouton (orange) — un *commutateur*, et non une touche.** Sur `gpio-keys` (`/dev/input/event8`), il signale **`EV_SW` `SW_MUTE_DEVICE`** (code de commutateur `14`), un événement *à maintien d’état* — **pas** un `EV_KEY`. C’est pourquoi il ne possède aucune entrée keylayout et n’est jamais exposé par Android/l’accessibilité (le micrologiciel d’origine le laisse donc inactif). ha-paneld l’instrumente au moyen du lecteur evdev du daemon auxiliaire root (`WATCH /dev/input/event8`, `sw=true`) et émet un événement HA (`KEYCODE_MUTE`) à chaque basculement — fonctionnement validé de bout en bout. Il s’agit du comportement **d’origine** (non documenté ailleurs à la date de rédaction).

**3. The pin-hole button (recessed, beside the USB-C port) — not an Android input.** It is absent from `getevent`, `gpio-keys` and `dmesg`, so it is **not HA-instrumentable**. Its electrical role has not been safely confirmed: placement and the rk3566 platform suggest a reset or boot-mode function, but there is no verified hold duration, factory-reset behavior or Maskrom entry procedure. Do not press or hold it on the assumption that it provides a recoverable reflash path; use the verified software-entered Loader route while Android still boots and follow the evidence boundary in [Firmware backup & restore](../../firmware-backup-restore.md).

</details>

## Caméra

GalaxyCore **GC05A2 / GC5035**, un capteur 2592x1944. Android signale une caméra de niveau matériel `LIMITED`, compatible uniquement avec `BACKWARD_COMPATIBLE`, `Facing: Back`, sans flash ni mise au point automatique, via le fournisseur `legacy/0` (`device@3.3`).

> [!NOTE]
> The firmware's own feature flags are wrong about it: the panel declares `android.hardware.camera.front` while the HAL reports `Facing: Back`. Gate on the device profile and on what `CameraManager` actually enumerates, never on `hasSystemFeature`.

La résolution 1280x720 est proposée dans les trois formats : `IMPLEMENTATION_DEFINED`, `YUV_420_888` et `BLOB` ; les formats d’aperçu et de capture d’image fixe sont donc tous deux disponibles. La HAL annonce un blocage d’une image pour une capture `BLOB` 720p à 30 i/s, mais ha-paneld capture en `YUV_420_888` et effectue la compression JPEG par logiciel : ce chiffre ne mesure donc pas le coût d’un instantané pour l’application. `android.control.aeAvailableTargetFpsRanges` ne propose que `[15 30]` et `[30 30]` : il n’existe aucun mode capteur verrouillé à 15 i/s ; un flux à 15 i/s résulte donc de la cadence imposée à l’encodeur, et non d’une demande adressée au capteur.

Le seul encodeur matériel H.264 est `OMX.rk.video_encoder.avc`, fourni par la HAL OMX IL (ce panneau possède un processus `media.codec` et aucun service fournisseur Codec2). Il annonce une plage de `176x144` à `1920x1088` avec un alignement de `16x8`, une plage de débit déclarée de 1 bit/s à 10 Mbit/s et quatre instances simultanées. Il n’existe **aucun encodeur HEVC matériel** ; la seule entrée HEVC est l’encodeur logiciel `c2.android.hevc.encoder`, livré avec `enabled="false"`.

> [!IMPORTANT]
> Considérez les chiffres du fournisseur dans `media_codecs_performance.xml` comme du texte générique et non comme des mesures : les encodeurs AVC matériel et logiciel déclarent des valeurs `measured-frame-rate` identiques en 720x480 comme en 1280x720, ce qui ne peut pas correspondre à une mesure réelle. Partez sur 720p tant que vous n’avez pas effectué vos propres mesures sur votre panneau.

Un panneau qui affiche des cartes de caméra n’est pas inactif : sur le tableau de bord du parc mesuré, ha-paneld maintenait deux instances `OMX.rk.video_decoder.avc` actives ; une session d’encodage entre donc en concurrence avec le décodage existant sur le même VPU Rockchip.

ha-paneld includes an **experimental camera-serving feature that is off by default**. It is enabled per panel by the **Camera** switch on the Camera card in Configure, which appears because this panel has a camera - here because the device profile declares `hardware.camera`, though a board whose profile says nothing offers the card too whenever Android enumerates a camera (see [enabling the camera on a panel whose profile does not declare one](../../profiles/unofficial/README.md#enabling-the-camera-on-a-panel-whose-profile-does-not-declare-one)), alongside **Resolution**, **Frame rate** and **Bitrate** (720p / 15 fps / 2000 kbps) and an **Exposure** bias in stops for a camera that reads the room darker or brighter than it looks. Those three are what a stream gets when its URL asks for nothing, and a stream URL can override any of them in either direction; they are defaults, not ceilings. Several viewers share one encode session, bound by the first one to connect, so a second viewer costs packetisation and network rather than a second encode. With the switch on it serves a video-only H.264 stream at `rtsp://<panel>:8554/live` and a still at `GET /api/v1/camera/snapshot.jpg`. Enabling the switch from Home Assistant asks for approval on the panel first, in every security mode. The RTSP URL is intended for Home Assistant Generic Camera, go2rtc and Frigate ingestion. Whenever the camera is open the panel draws its own red indicator that page content cannot cover, and if that indicator cannot be drawn the camera does not open. It flashes about once a second when the camera opens and backs off over a couple of minutes to a flash about once a minute; the half-second flash never gets shorter or fades away, settling at 62% opacity as it becomes rarer. A new stream, a recovery after a fault or the screen coming back on returns it to a flash every second, and no setting turns it off or changes the schedule. See [`GET /api/v1/status`](../../api.md) for the `camera` object that reports the encoder, the delivered frame rate and bitrate, and any fault.

> [!WARNING]
> Do not put a Home Assistant camera card for **this** panel on **this** panel's own dashboard. The panel would decode its own encode in a loop, on the same video engine, for no benefit.


## Autres composants

Caméra GalaxyCore **GC05A2 / GC5035** ; codec audio **ES7202** ; contrôleur tactile Goodix ; PMIC `rk808`/`rk860`.

## Synthèse du modèle d’accès

- **LED + button backlight**: root only (`system:system` sysfs) → via `hapaneld-helper`.
- **Proximité** : accès direct par l’application (`SensorManager`).
- **Temp / humidity / light**: root only (input subsystem / i2c) → would need the daemon.
- **Buttons**: 4 side buttons app-direct (KeyEvents via a11y); 5th orange button is an `EV_SW` switch → via `hapaneld-helper` evdev watch; pin-hole button is recovery/maskrom (not input).

---

Consultez l’[index du matériel des panneaux](README.md) pour la comparaison entre panneaux et la méthode, ainsi que les références [NSPanel Pro](nspanel-pro.md) / [WF1589T](wf1589t.md) / [S9E](../../hardware/s9e.md) pour les autres panneaux.
