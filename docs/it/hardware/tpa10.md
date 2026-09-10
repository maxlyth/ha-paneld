> [!IMPORTANT]
> Questo documento è generato automaticamente e verificato mediante controlli incrociati automatici, ma non è stato rivisto sistematicamente da persone che parlano questa lingua. La documentazione in inglese fa fede. [Leggi la fonte in inglese](../../hardware/tpa10.md) oppure [apri una segnalazione per correggere la traduzione](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Tuya TPA10 (Rockchip rk3566)

Un ampio pannello rk3566 con display **da 10 pollici, 1920×1200**, un singolo LED RGB anteriore, la retroilluminazione monocromatica dei pulsanti, un ricco insieme di sensori (prossimità ToF, temperatura e umidità, luce ambientale) e cinque pulsanti fisici, senza Zigbee, NFC né IR. Reverse engineering eseguito su un'unità reale (Android 11, con root, `su` presente).

> [!TIP]
> Most-needed facts: adb is **password-protected** — use the USB diagnostics-app backdoor; LED and the root-only sensors need the **`hapaneld-helper` root helper daemon**; the front LED's `custom_animation` write can **reboot the panel** (see caution below). Update the **WebView first** — see [WebView — update this first](#webview-esegui-prima-laggiornamento).

| | |
|---|---|
| SoC | Rockchip **rk3566** |
| Display | **1920×1200** (16:10 orizzontale), ~10,1" / **~226 ppi fisici**, 56 Hz. Densità logica di fabbrica/base Android **240 dpi** (comunemente sovrascritto per il dimensionamento del dashboard; ha-paneld consiglia 212) |
| Android | 11 (API 30) |
| ABI | armeabi-v7a (spazio utente a 32 bit) |
| Radio | Wi-Fi, Bluetooth + BLE, oltre a una funzionalità `com.smartos.xinch.platform.ethernet` del fornitore (cablata/PoE). **No zigbee, no NFC, no IR, no cellulare.** |
| Root | `su` available; the LED/sysfs sensors are `system:system`, so a **root helper daemon is required** (see below). |

> [!TIP]
> Changing firmware on a button-less panel? Read [Firmware backup & restore](../../firmware-backup-restore.md) first. The TPA10 (rk3566, Android 11, 7.28 GB eMMC `mmcblk2`) has a verified software-entered Loader route through `adb reboot loader` and `rkdeveloptool`. The recessed [pin-hole button](#pulsanti) is not a Linux input, but its factory-reset or Maskrom behavior has not been safely confirmed; do not rely on it as a recovery route.

## Ottenere accesso adb + root

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
> Le app del fornitore presenti sul dispositivo *non erano* una fonte utile per il reverse engineering: `com.tuya.devicetest` è in formato odex (nessun dex nell'APK) e `com.smartos.xinch.hardware` include l'SDK Tuya **AVS (Alexa)** (`libLibSampleApp.so`, 17 MB), oltre a un lettore di chiavi (`libjnimain.so`). La fonte autorevole è costituita dai nodi sysfs autodocumentanti del dispositivo.

## WebView: esegui prima l'aggiornamento

The stock WebView is **Chrome 83** — far too old for a current HA frontend, so the dashboard shows blank or broken until you replace it. The recommended build is **LineageOS System WebView 150** (`armeabi-v7a`) — a current, maintained, vanilla-Chromium engine. Prefer it over Cromite: Cromite patches Chromium's autoplay content-setting to *block*, which stops Home Assistant camera-card (WebRTC) streams from starting without a tap; LineageOS leaves autoplay allowed, so camera streams start on their own.

Il TPA10 ha un margine di capacità limitato per la decodifica video all'interno di WebView. Più schede WebRTC 720p visibili possono perdere fotogrammi o lasciare un flusso in attesa del primo fotogramma, anche se un singolo flusso funziona correttamente. Se ciò accade, usa substream a risoluzione inferiore, meno schede con riproduzione automatica simultanea oppure istantanee che passano al video live quando vengono toccate.

Download it from the ha-paneld mirror (stable URL):

```
https://github.com/maxlyth/ha-paneld/releases/download/webview-mirror/lineageos-webview-150.0.7871.63-arm.apk
```

### Perché un'installazione semplice non funziona

The WebView is packaged as `com.android.webview`, so it must **replace** the system provider, and the two obvious routes both fail on this Android-11 panel:

- `adb install -r` is rejected — `INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`. Android only lets you update `com.android.webview` with an APK signed by the **same key** as what's already installed, and each WebView vendor uses a different key. This is also why **ha-paneld's built-in "Update WebView" / auto-update can't do this first swap** — it installs over `pm install`, which the panel blocks. The swap below is a **one-time** manual step; once LineageOS is in place, ha-paneld *can* auto-apply future LineageOS updates (same signer).
- The ROM's allowlist accepts only `com.android.webview` (not the `com.google.android.webview` variant), so a "…Google" build installs but is never selected.

### Metodo di lavoro (root): sostituisci il file + cancella il blocco della firma

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

### LED RGB: driver `avsux` (daemon helper root)

Il LED RGB anteriore è un **singolo** LED (`avsux_info` → `led type:[single] nums:[1]`) sul driver della piattaforma `leds_pwm_avs` (dispositivo `avsux`), esposto a `/sys/class/leds/avs-pwm-led/`.

> [!CAUTION]
> Writing `custom_animation` to `avsux_select` has been observed to **reboot the panel**. Use `avsux_animation` for colour; treat `avsux_select`/`custom_animation` as read-only unless testing.

There is **no app-accessible `/dev` node** for the LED (contrast the [WF1589T](wf1589t.md)'s `/dev/ledjni`), and the sysfs attributes are `system:system` — an `untrusted_app` cannot write them. ha-paneld therefore ships a small **root helper daemon** (`/system/bin/hapaneld-helper`, root, unix socket) that the app talks to; `SocketLedController` is the client. See [`helper/README.md`](../../../helper/README.md).

<details>
<summary>Attributi sysfs di `avs-pwm-led`</summary>

| Attributo | Perm | Uso |
|---|---|---|
| `brightness` | `system:system` rw | overall level 0–255 |
| `avsux_animation` | `system:system` rw | safe colour/animation write |
| `avsux_select` | `system:system` rw | `custom_animation[][0][0]:<dur_ms>:<RRGGBB>[,…≤12 slots]` |
| `avsux_firmware` | r | elenca le animazioni denominate (`bootanime`, `idle`) |
| `avsux_info` | r | metadati (numero/tipo di LED) |

</details>

### Retroilluminazione dei pulsanti

`/sys/class/leds/button-backlight/brightness` — **monochrome** PWM, 0–255 (standard `leds_pwm` driver, device `pwmleds`). `system:system` 0664, so driven through the same `hapaneld-helper` daemon.

## Sensori

Proximity is app-direct via `SensorManager`; temperature, humidity and ambient light are root-only (input subsystem / i2c) and need the helper daemon.

> [!TIP]
> Il CHT8305 rende questo pannello un valido **sensore di temperatura e umidità ambientale** per Home Assistant. Il daemon helper lo legge con il comando `CHT8305` (una lettura puntuale `EVIOCGABS` dell'asse di input `ABS_THROTTLE` del driver, individuando per nome i dispositivi di input `temperature`/`humidity`). ha-paneld espone quindi tramite MQTT due sensori opzionali: **Temperatura ambiente** e **Umidità ambiente** (Configura → Diagnostica; disattivati per impostazione predefinita). L'impostazione avanzata **Offset della temperatura ambiente** (oppure `sensors.room_temp_offset_c` nel profilo) compensa l'autoriscaldamento del pannello.

Il ToF del TPA10 significa che la prossimità è realmente basata sulla distanza, ma l'HAL di Android la quantizza. ha-paneld apprende i punti finali e la direzione del sensore in tempo reale, quindi segnala la stessa scala di prossimità normalizzata utilizzata nei pannelli supportati anziché fare affidamento su un limite fisso specifico del dispositivo.

<details>
<summary>Chip sensore + percorsi di accesso</summary>

| Sensore | Chip | Accesso |
|---|---|---|
| Prossimità (ToF) | Vishay **VI5300** (i2c-3 `0x6c`, `proximity_vi5300`, sondaggio di 30 ms) | Android `SensorManager` `TYPE_PROXIMITY` (no root). Raw mm distance on the driver's i2c node (`…/i2c-3/3-006c`) needs root. |
| Temperatura + umidità | **CHT8305** (`temperature_cht8305` @3-0040, `humidity_cht8305` @3-0040-1) | **Not** in `SensorManager`; reports via the **input subsystem** on i2c — root only. |
| Luce ambientale | **CG5256** (`light_cg5256`) | Not in `SensorManager` (root). |

</details>

## Pulsanti

Il TPA10 ha **tre classi** di pulsanti fisici, confermati sul dispositivo con `getevent`:

- **I quattro pulsanti laterali** — `adc-keys`, KeyEvents standard mappati su `F1`–`F4`, catturati dal filtro chiave di accessibilità di ha-paneld (nessun percorso speciale).
- **Il quinto pulsante (arancione)**: un `EV_SW` *interruttore*, non un tasto; viene monitorato dal lettore evdev del daemon helper root, che genera un evento HA.
- **The pin-hole button** (recessed, beside the USB-C port) — recovery / reflash only, **not** HA-instrumentable.

<details>
<summary>Dettagli per pulsante (codici di scansione, evdev, ruolo di ripristino)</summary>

**1. The four side buttons — `adc-keys`, standard KeyEvents.** On the rk3566 **SARADC** (`fe720000.saradc`), device `adc-keys1` (`/dev/input/event7`), scancodes `59`–`62`. Stock `Generic.kl` maps these to **`F1`–`F4`** → Android `KEYCODE_F1`–`F4`, which ha-paneld captures via its accessibility key-filter (no special path). They can be remapped by editing `/system/usr/keylayout/Generic.kl` (e.g. to `BRIGHTNESS_*` / `VOLUME_*`) on an su-capable unit.

**2. Il quinto pulsante (arancione): un *interruttore*, non un tasto.** Su `gpio-keys` (`/dev/input/event8`) genera **`EV_SW` `SW_MUTE_DEVICE`** (codice interruttore `14`), un evento *bistabile* — **non** un `EV_KEY`. Per questo non esiste alcuna voce di keylayout e Android/a11y non lo espone mai (quindi il firmware di serie lo lascia inattivo). ha-paneld lo monitora tramite il lettore evdev del daemon helper root (`WATCH /dev/input/event8`, `sw=true`) e genera un evento HA (`KEYCODE_MUTE`) a ogni commutazione, convalidato end-to-end. Questo è un comportamento **di serie** (non documentato altrove al momento della stesura).

**3. The pin-hole button (recessed, beside the USB-C port) — not an Android input.** It is absent from `getevent`, `gpio-keys` and `dmesg`, so it is **not HA-instrumentable**. Its electrical role has not been safely confirmed: placement and the rk3566 platform suggest a reset or boot-mode function, but there is no verified hold duration, factory-reset behavior or Maskrom entry procedure. Do not press or hold it on the assumption that it provides a recoverable reflash path; use the verified software-entered Loader route while Android still boots and follow the evidence boundary in [Firmware backup & restore](../../firmware-backup-restore.md).

</details>

## Fotocamera

GalaxyCore **GC05A2 / GC5035**, un sensore 2592x1944. Android segnala una fotocamera a livello hardware `LIMITED`, solo `BACKWARD_COMPATIBLE`, `Facing: Back`, senza flash e senza messa a fuoco automatica, tramite il provider `legacy/0` (`device@3.3`).

> [!NOTE]
> The firmware's own feature flags are wrong about it: the panel declares `android.hardware.camera.front` while the HAL reports `Facing: Back`. Gate on the device profile and on what `CameraManager` actually enumerates, never on `hasSystemFeature`.

1280x720 è disponibile in tutti e tre i formati: `IMPLEMENTATION_DEFINED`, `YUV_420_888` e `BLOB`, quindi sono disponibili sia formati di anteprima sia di acquisizione di immagini fisse. L'HAL dichiara uno stallo di un fotogramma per un'acquisizione `BLOB` 720p a 30 fps, ma ha-paneld acquisisce `YUV_420_888` ed esegue la compressione JPEG via software, quindi tale valore non misura il costo delle istantanee dell'app. `android.control.aeAvailableTargetFpsRanges` offre solo `[15 30]` e `[30 30]`: non esiste una modalità del sensore bloccata a 15 fps, quindi un flusso a 15 fps deriva dal cadenzamento dell'encoder, non da una richiesta al sensore.

L'unico codificatore H.264 hardware è `OMX.rk.video_encoder.avc`, gestito dall'HAL OMX IL (questo pannello ha un processo `media.codec` e nessun servizio Codec2 del fornitore). Dichiara `176x144`-`1920x1088` con allineamento `16x8`, un intervallo di bitrate dichiarato da 1 bps a 10 Mbps e quattro istanze simultanee. Non è presente **alcun codificatore HEVC hardware**; l'unica voce HEVC è il componente software `c2.android.hevc.encoder`, fornito `enabled="false"`.

> [!IMPORTANT]
> Considera le cifre `media_codecs_performance.xml` del fornitore come valori generici, non come misurazioni: i codificatori AVC hardware e software riportano valori `measured-frame-rate` identici sia a 720x480 sia a 1280x720, quindi non possono essere misurazioni reali. Presupponi 720p finché non avrai eseguito misurazioni diverse sul tuo pannello.

Un pannello che mostra schede di telecamere non è inattivo: nella dashboard della flotta sottoposta a misurazione, ha-paneld manteneva attive due istanze `OMX.rk.video_decoder.avc`, quindi una sessione di codifica compete con il carico di decodifica già presente sulla stessa VPU Rockchip.

ha-paneld includes an **experimental camera-serving feature that is off by default**. It is enabled per panel by the **Camera** switch on the Camera card in Configure, which appears because this panel has a camera - here because the device profile declares `hardware.camera`, though a board whose profile says nothing offers the card too whenever Android enumerates a camera (see [enabling the camera on a panel whose profile does not declare one](../../profiles/unofficial/README.md#enabling-the-camera-on-a-panel-whose-profile-does-not-declare-one)), alongside **Resolution**, **Frame rate** and **Bitrate** (720p / 15 fps / 2000 kbps) and an **Exposure** bias in stops for a camera that reads the room darker or brighter than it looks. Those three are what a stream gets when its URL asks for nothing, and a stream URL can override any of them in either direction; they are defaults, not ceilings. Several viewers share one encode session, bound by the first one to connect, so a second viewer costs packetisation and network rather than a second encode. With the switch on it serves a video-only H.264 stream at `rtsp://<panel>:8554/live` and a still at `GET /api/v1/camera/snapshot.jpg`. Enabling the switch from Home Assistant asks for approval on the panel first, in every security mode. The RTSP URL is intended for Home Assistant Generic Camera, go2rtc and Frigate ingestion. Whenever the camera is open the panel draws its own red indicator that page content cannot cover, and if that indicator cannot be drawn the camera does not open. It flashes about once a second when the camera opens and backs off over a couple of minutes to a flash about once a minute; the half-second flash never gets shorter or fades away, settling at 62% opacity as it becomes rarer. A new stream, a recovery after a fault or the screen coming back on returns it to a flash every second, and no setting turns it off or changes the schedule. See [`GET /api/v1/status`](../../api.md) for the `camera` object that reports the encoder, the delivered frame rate and bitrate, and any fault.

> [!WARNING]
> Do not put a Home Assistant camera card for **this** panel on **this** panel's own dashboard. The panel would decode its own encode in a loop, on the same video engine, for no benefit.


## Altro silicio

Fotocamera GalaxyCore **GC05A2 / GC5035**; codec audio **ES7202**; controller touch Goodix; PMIC `rk808`/`rk860`.

## Riepilogo del modello di accesso

- **LED + button backlight**: root only (`system:system` sysfs) → via `hapaneld-helper`.
- **Prossimità**: app-direct (`SensorManager`).
- **Temp / humidity / light**: root only (input subsystem / i2c) → would need the daemon.
- **Buttons**: 4 side buttons app-direct (KeyEvents via a11y); 5th orange button is an `EV_SW` switch → via `hapaneld-helper` evdev watch; pin-hole button is recovery/maskrom (not input).

---

Consulta l'[indice hardware dei pannelli](README.md) per il confronto tra pannelli e la metodologia, nonché le pagine di riferimento di [NSPanel Pro](nspanel-pro.md), [WF1589T](wf1589t.md) e [S9E](../../hardware/s9e.md) per gli altri pannelli.
