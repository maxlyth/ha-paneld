> [!IMPORTANT]
> Dieses Dokument wurde maschinell erstellt und automatisch gegengeprüft, jedoch nicht systematisch von Personen geprüft, die diese Sprache sprechen. Die englische Dokumentation ist maßgeblich. [Englisches Original lesen](../../hardware/tpa10.md) oder [ein Issue zur Übersetzungskorrektur öffnen](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Tuya TPA10 (Rockchip rk3566)

Ein geräumiges **10-Zoll-Panel mit 1920×1200** und rk3566, einer einzelnen RGB-LED an der Vorderseite, einer monochromen Tastenbeleuchtung, umfangreicher Sensorik (ToF-Näherung, Temperatur und Luftfeuchtigkeit, Umgebungslicht) sowie fünf physischen Tasten – ohne Zigbee, NFC oder IR. An einem realen Gerät rückentwickelt (Android 11, gerootet, `su` vorhanden).

> [!TIP]
> Most-needed facts: adb is **password-protected** — use the USB diagnostics-app backdoor; LED and the root-only sensors need the **`hapaneld-helper` root helper daemon**; the front LED's `custom_animation` write can **reboot the panel** (see caution below). Update the **WebView first** — see [WebView — update this first](#webview--zuerst-aktualisieren).

| | |
|---|---|
| SoC | Rockchip **rk3566** |
| Display | **1920×1200** (16:10 im Querformat), ~10,1 Zoll / **~226 physische ppi**, 56 Hz. Logische Werks-/Basisdichte von Android: **240 dpi** (wird häufig für die Dashboard-Größe überschrieben; ha-paneld empfiehlt 212) |
| Android | 11 (API 30) |
| ABI | armeabi-v7a (32-Bit-Userspace) |
| Funkmodule | WLAN, Bluetooth und BLE sowie eine herstellerspezifische `com.smartos.xinch.platform.ethernet`-Funktion (kabelgebunden/PoE). **Kein Zigbee, kein NFC, kein IR und kein Mobilfunk.** |
| Root | `su` available; the LED/sysfs sensors are `system:system`, so a **root helper daemon is required** (see below). |

> [!TIP]
> Changing firmware on a button-less panel? Read [Firmware backup & restore](../../firmware-backup-restore.md) first. The TPA10 (rk3566, Android 11, 7.28 GB eMMC `mmcblk2`) has a verified software-entered Loader route through `adb reboot loader` and `rkdeveloptool`. The recessed [pin-hole button](#tasten) is not a Linux input, but its factory-reset or Maskrom behavior has not been safely confirmed; do not rely on it as a recovery route.

## adb- und Root-Zugriff erlangen

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
> Die herstellereigenen Apps auf dem Gerät waren *keine* hilfreiche Quelle für die Rückentwicklung: `com.tuya.devicetest` ist odex-kompiliert (kein DEX in der APK), und `com.smartos.xinch.hardware` enthält das Tuya-**AVS-(Alexa-)SDK** (`libLibSampleApp.so`, 17 MB) sowie einen Schlüsselleser (`libjnimain.so`). Maßgebliche Quelle sind die selbstdokumentierenden sysfs-Knoten des Geräts.

## WebView – zuerst aktualisieren

The stock WebView is **Chrome 83** — far too old for a current HA frontend, so the dashboard shows blank or broken until you replace it. The recommended build is **LineageOS System WebView 150** (`armeabi-v7a`) — a current, maintained, vanilla-Chromium engine. Prefer it over Cromite: Cromite patches Chromium's autoplay content-setting to *block*, which stops Home Assistant camera-card (WebRTC) streams from starting without a tap; LineageOS leaves autoplay allowed, so camera streams start on their own.

Der Videodecoder des TPA10 hat innerhalb von WebView nur begrenzte Reserven. Mehrere sichtbare 720p-WebRTC-Karten können Frames verwerfen oder dazu führen, dass ein Stream auf sein erstes Bild wartet, obwohl ein einzelner Stream problemlos läuft. Verwenden Sie in diesem Fall Substreams mit geringerer Auflösung, weniger gleichzeitig automatisch startende Karten oder Standbilder, die bei Antippen zum Livevideo wechseln.

Download it from the ha-paneld mirror (stable URL):

```
https://github.com/maxlyth/ha-paneld/releases/download/webview-mirror/lineageos-webview-150.0.7871.63-arm.apk
```

### Warum eine normale Installation nicht funktioniert

The WebView is packaged as `com.android.webview`, so it must **replace** the system provider, and the two obvious routes both fail on this Android-11 panel:

- `adb install -r` is rejected — `INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`. Android only lets you update `com.android.webview` with an APK signed by the **same key** as what's already installed, and each WebView vendor uses a different key. This is also why **ha-paneld's built-in "Update WebView" / auto-update can't do this first swap** — it installs over `pm install`, which the panel blocks. The swap below is a **one-time** manual step; once LineageOS is in place, ha-paneld *can* auto-apply future LineageOS updates (same signer).
- The ROM's allowlist accepts only `com.android.webview` (not the `com.google.android.webview` variant), so a "…Google" build installs but is never selected.

### Funktionierende Methode (Root) – Datei ersetzen und Signatursperre aufheben

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

### RGB-LED – `avsux`-Treiber (Root-Hilfsdaemon)

Die vordere RGB-LED ist eine **einzelne** LED (`avsux_info` → `led type:[single] nums:[1]`) am `leds_pwm_avs`-Plattformtreiber (Gerät `avsux`), erreichbar unter `/sys/class/leds/avs-pwm-led/`.

> [!CAUTION]
> Writing `custom_animation` to `avsux_select` has been observed to **reboot the panel**. Use `avsux_animation` for colour; treat `avsux_select`/`custom_animation` as read-only unless testing.

There is **no app-accessible `/dev` node** for the LED (contrast the [WF1589T](wf1589t.md)'s `/dev/ledjni`), and the sysfs attributes are `system:system` — an `untrusted_app` cannot write them. ha-paneld therefore ships a small **root helper daemon** (`/system/bin/hapaneld-helper`, root, unix socket) that the app talks to; `SocketLedController` is the client. See [`helper/README.md`](../../../helper/README.md).

<details>
<summary>sysfs-Attribute von `avs-pwm-led`</summary>

| Attribut | Rechte | Verwendung |
|---|---|---|
| `brightness` | `system:system` rw | overall level 0–255 |
| `avsux_animation` | `system:system` rw | safe colour/animation write |
| `avsux_select` | `system:system` rw | `custom_animation[][0][0]:<dur_ms>:<RRGGBB>[,…≤12 slots]` |
| `avsux_firmware` | r | listet benannte Animationen auf (`bootanime`, `idle`) |
| `avsux_info` | r | Metadaten (Anzahl/Typ der LEDs) |

</details>

### Tastenbeleuchtung

`/sys/class/leds/button-backlight/brightness` — **monochrome** PWM, 0–255 (standard `leds_pwm` driver, device `pwmleds`). `system:system` 0664, so driven through the same `hapaneld-helper` daemon.

## Sensoren

Proximity is app-direct via `SensorManager`; temperature, humidity and ambient light are root-only (input subsystem / i2c) and need the helper daemon.

> [!TIP]
> Der CHT8305 macht dieses Panel zu einem geeigneten **Raumtemperatur- und Luftfeuchtigkeitssensor** für Home Assistant. Der Hilfsdaemon liest ihn mit dem Verb `CHT8305` aus (eine `EVIOCGABS`-Punktabfrage der `ABS_THROTTLE`-Eingabeachse des Treibers, wobei die Eingabegeräte `temperature`/`humidity` anhand ihres Namens zugeordnet werden). ha-paneld stellt anschließend über MQTT zwei optional aktivierbare Sensoren für **Raumtemperatur** und **Raumluftfeuchtigkeit** bereit (Konfigurieren → Diagnose; standardmäßig deaktiviert). Eine erweiterte Einstellung **Raumtemperatur-Offset** (oder `sensors.room_temp_offset_c` des Profils) korrigiert die Eigenerwärmung des Panels.

Dank ToF basiert die Näherungserkennung des TPA10 tatsächlich auf einer Entfernungsmessung, doch die Android-HAL quantisiert sie. ha-paneld ermittelt die Endpunkte und die Richtung des aktiven Sensors und meldet anschließend dieselbe normalisierte Näherungsskala wie auf anderen unterstützten Panels, anstatt sich auf einen festen gerätespezifischen Schwellenwert zu verlassen.

<details>
<summary>Sensorchips und Zugriffswege</summary>

| Sensor | Chip | Zugriff |
|---|---|---|
| Näherung (ToF) | Vishay **VI5300** (i2c-3 `0x6c`, `proximity_vi5300`, Abfrage alle 30 ms) | Android `SensorManager` `TYPE_PROXIMITY` (no root). Raw mm distance on the driver's i2c node (`…/i2c-3/3-006c`) needs root. |
| Temperatur und Luftfeuchtigkeit | **CHT8305** (`temperature_cht8305` @3-0040, `humidity_cht8305` @3-0040-1) | **Not** in `SensorManager`; reports via the **input subsystem** on i2c — root only. |
| Umgebungslicht | **CG5256** (`light_cg5256`) | Not in `SensorManager` (root). |

</details>

## Tasten

Das TPA10 verfügt über **drei Arten** physischer Tasten, die auf dem Gerät mit `getevent` bestätigt wurden:

- **Die vier seitlichen Tasten** – `adc-keys`, standardmäßige KeyEvents, die `F1`–`F4` zugeordnet und vom Barrierefreiheits-Tastenfilter von ha-paneld erfasst werden (kein besonderer Zugriffsweg).
- **Die fünfte (orangefarbene) Taste** – ein `EV_SW`-*Schalter*, keine Taste; sie wird über den evdev-Leser des Root-Hilfsdaemons erfasst und als HA-Ereignis ausgegeben.
- **The pin-hole button** (recessed, beside the USB-C port) — recovery / reflash only, **not** HA-instrumentable.

<details>
<summary>Details zu den einzelnen Tasten (Scancodes, evdev, Wiederherstellungsfunktion)</summary>

**1. The four side buttons — `adc-keys`, standard KeyEvents.** On the rk3566 **SARADC** (`fe720000.saradc`), device `adc-keys1` (`/dev/input/event7`), scancodes `59`–`62`. Stock `Generic.kl` maps these to **`F1`–`F4`** → Android `KEYCODE_F1`–`F4`, which ha-paneld captures via its accessibility key-filter (no special path). They can be remapped by editing `/system/usr/keylayout/Generic.kl` (e.g. to `BRIGHTNESS_*` / `VOLUME_*`) on an su-capable unit.

**2. Die fünfte (orangefarbene) Taste – ein *Schalter*, keine Taste.** Auf `gpio-keys` (`/dev/input/event8`) meldet sie **`EV_SW` `SW_MUTE_DEVICE`** (Schaltercode `14`), ein *rastendes* Ereignis – **kein** Ereignis vom Typ `EV_KEY`. Deshalb gibt es dafür keinen Keylayout-Eintrag, und Android bzw. die Bedienungshilfe stellen das Ereignis nie bereit (sodass es in der Originalfirmware ungenutzt bleibt). ha-paneld erfasst es über den evdev-Leser des Root-Hilfsdaemons (`WATCH /dev/input/event8`, `sw=true`) und gibt bei jedem Umschalten ein HA-Ereignis (`KEYCODE_MUTE`) aus – durchgängig validiert. Dies ist das Verhalten der **Originalfirmware** (zum Zeitpunkt der Erstellung andernorts nicht dokumentiert).

**3. The pin-hole button (recessed, beside the USB-C port) — not an Android input.** It is absent from `getevent`, `gpio-keys` and `dmesg`, so it is **not HA-instrumentable**. Its electrical role has not been safely confirmed: placement and the rk3566 platform suggest a reset or boot-mode function, but there is no verified hold duration, factory-reset behavior or Maskrom entry procedure. Do not press or hold it on the assumption that it provides a recoverable reflash path; use the verified software-entered Loader route while Android still boots and follow the evidence boundary in [Firmware backup & restore](../../firmware-backup-restore.md).

</details>

## Kamera

GalaxyCore **GC05A2 / GC5035**, ein 2592x1944-Sensor. Android meldet eine Kamera mit Hardware-Level `LIMITED`, ausschließlich `BACKWARD_COMPATIBLE`, `Facing: Back`, ohne Blitz und ohne Autofokus, über den `legacy/0`-Provider (`device@3.3`).

> [!NOTE]
> The firmware's own feature flags are wrong about it: the panel declares `android.hardware.camera.front` while the HAL reports `Facing: Back`. Gate on the device profile and on what `CameraManager` actually enumerates, never on `hasSystemFeature`.

1280x720 wird in allen drei Formaten angeboten: `IMPLEMENTATION_DEFINED`, `YUV_420_888` und `BLOB`; damit stehen sowohl Vorschau- als auch Standbildformate zur Verfügung. Die HAL gibt für eine 720p-`BLOB`-Aufnahme mit 30 fps eine Verzögerung um einen Frame an. ha-paneld erfasst jedoch `YUV_420_888` und führt die JPEG-Komprimierung in Software durch, sodass dieser Wert nicht die Kosten eines Schnappschusses in der App beschreibt. `android.control.aeAvailableTargetFpsRanges` bietet nur `[15 30]` und `[30 30]`: Es gibt keinen fest eingestellten Sensormodus mit 15 fps; ein 15-fps-Stream entsteht daher durch die Taktung des Encoders und nicht durch eine entsprechende Anforderung an den Sensor.

Der einzige Hardware-H.264-Encoder ist `OMX.rk.video_encoder.avc` und wird von der OMX-IL-HAL bereitgestellt (dieses Panel besitzt einen `media.codec`-Prozess und keinen Codec2-Herstellerdienst). Er gibt `176x144`–`1920x1088` mit einer `16x8`-Ausrichtung, einen gemeldeten Bitratenbereich von 1 bit/s bis 10 Mbit/s und vier gleichzeitige Instanzen an. Es gibt **keinen Hardware-HEVC-Encoder**; der einzige HEVC-Eintrag ist `c2.android.hevc.encoder` in Software, ausgeliefert als `enabled="false"`.

> [!IMPORTANT]
> Behandeln Sie die `media_codecs_performance.xml`-Angaben des Herstellers als Textbausteine und nicht als Messwerte: Die AVC-Encoder in Hardware und Software melden sowohl bei 720x480 als auch bei 1280x720 identische `measured-frame-rate`-Werte, was kein echter Messwert sein kann. Gehen Sie von 720p aus, bis Sie auf Ihrem eigenen Panel etwas anderes gemessen haben.

Ein Panel, das Kamerakarten anzeigt, befindet sich nicht im Leerlauf: Auf dem gemessenen Flotten-Dashboard hielt ha-paneld zwei aktive `OMX.rk.video_decoder.avc`-Instanzen; eine Encodiersitzung konkurriert daher mit vorhandenen Decodieraufgaben auf derselben Rockchip-VPU.

ha-paneld includes an **experimental camera-serving feature that is off by default**. It is enabled per panel by the **Camera** switch on the Camera card in Configure, which appears because this panel has a camera - here because the device profile declares `hardware.camera`, though a board whose profile says nothing offers the card too whenever Android enumerates a camera (see [enabling the camera on a panel whose profile does not declare one](../../profiles/unofficial/README.md#enabling-the-camera-on-a-panel-whose-profile-does-not-declare-one)), alongside **Resolution**, **Frame rate** and **Bitrate** (720p / 15 fps / 2000 kbps) and an **Exposure** bias in stops for a camera that reads the room darker or brighter than it looks. Those three are what a stream gets when its URL asks for nothing, and a stream URL can override any of them in either direction; they are defaults, not ceilings. Several viewers share one encode session, bound by the first one to connect, so a second viewer costs packetisation and network rather than a second encode. With the switch on it serves a video-only H.264 stream at `rtsp://<panel>:8554/live` and a still at `GET /api/v1/camera/snapshot.jpg`. Enabling the switch from Home Assistant asks for approval on the panel first, in every security mode. The RTSP URL is intended for Home Assistant Generic Camera, go2rtc and Frigate ingestion. Whenever the camera is open the panel draws its own red indicator that page content cannot cover, and if that indicator cannot be drawn the camera does not open. It flashes about once a second when the camera opens and backs off over a couple of minutes to a flash about once a minute; the half-second flash never gets shorter or fades away, settling at 62% opacity as it becomes rarer. A new stream, a recovery after a fault or the screen coming back on returns it to a flash every second, and no setting turns it off or changes the schedule. See [`GET /api/v1/status`](../api.md) for the `camera` object that reports the encoder, the delivered frame rate and bitrate, and any fault.

> [!WARNING]
> Do not put a Home Assistant camera card for **this** panel on **this** panel's own dashboard. The panel would decode its own encode in a loop, on the same video engine, for no benefit.


## Weitere Chips

Kamera: GalaxyCore **GC05A2 / GC5035**; Audio-Codec: **ES7202**; Goodix-Touchcontroller; `rk808`/`rk860`-PMIC.

## Zusammenfassung des Zugriffsmodells

- **LED + button backlight**: root only (`system:system` sysfs) → via `hapaneld-helper`.
- **Näherung**: direkter App-Zugriff (`SensorManager`).
- **Temp / humidity / light**: root only (input subsystem / i2c) → would need the daemon.
- **Buttons**: 4 side buttons app-direct (KeyEvents via a11y); 5th orange button is an `EV_SW` switch → via `hapaneld-helper` evdev watch; pin-hole button is recovery/maskrom (not input).

---

Im [Panel-Hardwareindex](README.md) finden Sie den panelübergreifenden Vergleich und die Methode; die Referenzen zu [NSPanel Pro](nspanel-pro.md), [WF1589T](wf1589t.md) und [S9E](../../hardware/s9e.md) behandeln die anderen Panels.
