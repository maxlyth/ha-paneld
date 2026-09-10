> [!IMPORTANT]
> Este documento se genera automáticamente y se somete a comprobaciones cruzadas automáticas, pero no ha sido revisado sistemáticamente por hablantes de este idioma. La documentación en inglés es la fuente de referencia. [Consulta la fuente en inglés](../../hardware/nspanel-pro.md) o [abre una incidencia para corregir la traducción](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Sonoff NSPanel Pro (Rockchip PX30 / rk3326)

El NSPanel Pro original es un pequeño panel PX30 **cuadrado de 480×480** con un **coordinador Zigbee 3.0** integrado, sin NFC ni IR y con la CPU de menor consumo de los paneles aquí documentados. Sus variantes **86P** y **120P** usan pantallas y placas diferentes; consulta [Variantes](#variantes-86p-y-120p). Esta página se elaboró mediante ingeniería inversa principalmente sobre un **86P** en funcionamiento (Android 8.1, rooteado, toolbox `su`) y trata esas variantes originales salvo indicación expresa en contrario.

> [!TIP]
> Most-needed facts: ships **`userdebug` with no adb password** (`adb root` just works); **LED is not characterised** (no controllable RGB node found); light + proximity are **app-direct**; the on-board **EFR32 Zigbee radio** is managed over a local broker, not by reflashing. Update the **WebView first** — see [WebView — update this first](#webview-actualízalo-primero).

| | |
|---|---|
| SoC | Rockchip **PX30 / rk3326** |
| CPU | 4× **Cortex-A35** a un máximo de **1,512 GHz** (408 MHz en reposo) |
| GPU | **Mali-G31** (confirmada en el dispositivo) |
| Pantalla | **Cuadrada de 480×480** (1:1), ~4", 160 dpi (mdpi, que se ajusta bien a los ~170 ppp físicos), 60 Hz → un lienzo de **480×480 dp** de área útil |
| RAM | **2 GB** (≈1960 MB utilizables) |
| Almacenamiento | eMMC; `/data` ≈3,5 GB |
| Android | 8.1 (API 27) |
| ABI | arm64-v8a |
| Radios | **Zigbee 3.0** (coordinador Silicon Labs EFR32 por UART `ttyS5`; consulta más abajo), Wi-Fi y Bluetooth. Sin NFC, IR, Ethernet ni conexión móvil. |

> [!NOTE]
> El Cortex-A35 es un núcleo eficiente con un rendimiento por ciclo notablemente inferior al A55 (TPA10) o al A72 (WF1589T). Junto con sus 2 GB de RAM, esto convierte al NSPanel Pro en el modelo de **menor rendimiento** de los tres paneles aquí documentados; consulta la [comparación de rendimiento](README.md#comparación-de-rendimiento-y-despliegue-práctico).

> [!TIP]
> Changing firmware on a button-less panel? Read [Firmware backup & restore](../../firmware-backup-restore.md) first — the NSPanel Pro (PX30) uses [seaky's roottool/tools](../../firmware-backup-restore.md#per-panel-notes) rather than `rkdeveloptool`.

## Variantes: 86P y 120P

La línea NSPanel Pro original se ofrece en dos paneles físicamente distintos, cuyos nombres corresponden a las cajas de pared europeas de **86 mm** y **120 mm**. La tabla de especificaciones anterior y la mayor parte de esta página se obtuvieron de un **86P**; el **120P** usa una placa diferente:

| | NSPanel Pro **86P** | NSPanel Pro **120P** |
|---|---|---|
| SoC | Rockchip **PX30** | Rockchip **RK3326-S** (de la misma familia PX30/RK3326; `ro.board.platform=rk3326`, árbol de dispositivos `rockchip,px30`) |
| Pantalla | **Cuadrada de 480×480**, ~160 dpi, solo orientación vertical | **750×1334** en vertical, **240 dpi** (valor sobrescrito a 250); admite orientación horizontal; ~1 cm más estrecha y más larga que la del 86P |
| Identificadores de compilación | ambos indican `ro.product.model/device/name = px30_evb` (nombre de placa Rockchip compartido; *no* permite distinguir de forma fiable las variantes) | igual que el 86P |
| `ro.product.version` | `s6_android_x.y.z` de tipo | `NSPanelXXXP_x.y.z` (canal OTA `nspanel-pro-ver120`, ROM completa `SN_3326S_750X1334_…`) |
| Formato OTA | ROM completa hasta **4.0.12**; las versiones indexadas posteriores se distribuyen como actualizaciones diferenciales o solo como APK (consulta el [índice de firmware](../../hardware/nspanel-pro-firmware.md)) | igual que el 86P |
| Firmware de proximidad | **4.0.12 restauró las mediciones con rango** de proximidad | siguió siendo **binario** en 4.x (divergencia del kernel según el modelo; consulta [Sensores](#sensores-la-aplicación-accede-directamente-a-la-luz-y-la-proximidad)) |

Ambos comparten la radio Zigbee EFR32, Android 8.1 (AOSP), arm64-v8a y el procedimiento de acceso root y recuperación descrito más abajo. Verificado en un 120P en funcionamiento (fw `NSPanel120P_3.7.1`): `wm size`=750×1334, densidad 240, `ro.board.platform=rk3326`.

> [!NOTE]
> This page does not crown a firmware version in prose — the generated [complete index](../../hardware/nspanel-pro-firmware-archive.md) is the authority, and it goes stale less often. The flashing procedure is hardware-verified through **4.4.0**; releases indexed past that are CDN-verified only, never live-flash verified here. As of 2026-08-14, for the most recently added of them no vendor documentation was found: [Sonoff's public changelog](https://sonoff.tech/en-us/blogs/news/sonoff-nspanel-pro-version-update-information-and-faq) documents up to **4.6.0**, 4.6.2 and 4.8.0 were located only by probing the CDN, and 4.7.0 is discussed only in an [eWeLink user feedback thread](https://forum.ewelink.cc/t/nspanel-pro-v4-7-0-feeback/208789) — a discussion thread, not a release announcement. The **4.5.3** release is a ROM diff on 120P but an APK-only update on 86P, and **4.6.2** is an app-only update with no ROM diff on either channel, so an upgrade is not always a single hop. Absence from the index means not-found-by-probe; the CDN cannot be listed, so it is never proof a build does not exist. The CoolKit CDN scheme and the full flashing how-to are on the [firmware & flashing page](../../hardware/nspanel-pro-firmware.md); every verified OTA URL is in the [complete index](../../hardware/nspanel-pro-firmware-archive.md), and the community-facing subset is the Discussion linked from there, which is regenerated from this repo's data files and can lag them.
>
> **⚠ Community reports describe restart loops on 4.5.1 / 4.5.2** (~10–60 min intervals on both models). For 4.7.0, the user feedback thread contains reports of sub-device connectivity trouble after updating, some resolved by a reboot and others described as continuing; this project has not reproduced or quantified them, so treat them as unverified user reports rather than a known regression. Verify any newer release on one panel before deploying widely; **4.0.12** remains the conservative full-ROM checkpoint to pin. The firmware Discussion carries the current community evidence, regenerated from this repo's index whenever the scheduled monitor next runs.

### Particularidades según la versión del firmware

Behaviour that changes across eWeLink firmware versions, oldest first. `ro.product.version` is the **internal** id (`s6_android_x.y.z` on the 86P / `NSPanelXXXP_x.y.z` on the 120P) — *not* the marketing/OTA number the eWeLink app shows (4.0.12, 4.5.x). Detection and any version-keyed logic must read `ro.product.version`, not the marketing string.

| Firmware | Particularidad/comportamiento | Impacto: qué hacer |
|---|---|---|
| **anteriores a 1.3.2** | No hay un control para adb en la aplicación; no se puede acceder a las opciones de desarrollador desde la interfaz | Enable adb via the internal **OTG port** (open the case) — [Gaining adb + root](#obtener-acceso-adb-y-root). |
| **v1.3.2 o posterior** | La activación de adb se trasladó a la aplicación eWeLink | eWeLink → *Device Settings* → tap **Device ID ×8** → developer mode → adb. |
| **v1.4 o posterior** | El modo de desarrollador se **eliminó** de la interfaz | Enable adb via the **5× power-cycle** at the Sonoff boot animation — [Gaining adb + root](#obtener-acceso-adb-y-root). |
| **3.5.1 (86P, verificada)** | El WebView del sistema incluido es **Chromium 107.0.5304.105**, demasiado antiguo para renderizar un panel moderno de HA; otras versiones del firmware pueden diferir | Check and update the WebView **first** — [WebView — update this first](#webview-actualízalo-primero). ha-paneld's panel-health banner also flags outdated versions (min Chromium 110). |
| **v3.7.1** (120P, en funcionamiento) | Compilación de referencia inicial | `wm size`=750×1334, densidad 240, `ro.board.platform=rk3326`. |
| **v4.0.0** (despliegue: 19-09-2025) | El firmware de fábrica **incluye F-Droid** y facilita la instalación de aplicaciones FOSS/HA; la interfaz es notablemente más rápida | On-device install path opens — [Firmware v4.0.0](#firmware-v400-instalación-oficial-de-aplicaciones-desde-f-droid). Confirm **APP** *and* **OS** version both read ≥ 4.0.0. |
| **v4.0.12** | Se restauraron las **lecturas de proximidad con rango** en el **86P**; el **120P sigue usando valores binarios** (divergencia del kernel según el modelo) | Recommended stable pin for HA-only panels. The panel's raw input shape is model- and firmware-specific, but ha-paneld learns and normalizes either form — see [Sensors](#sensores-la-aplicación-accede-directamente-a-la-luz-y-la-proximidad). |
| **v4.5.1 / v4.5.2** | **Widespread community restart-loop reports** (~10–60 min, both models); 4.5.2 is an APK-only layer on 4.5.1 | **Superseded by later releases.** Pin at **4.0.12** for maximum stability, or test a newer release on one panel first. Check the firmware Discussion for current evidence. |
| **v4.5.3** | Detección automática de Matter y optimizaciones de la gestión de pantalla; actualización diferencial de ROM en el 120P, pero solo APK en el 86P | No 4.5.3-specific restart-loop evidence found; superseded by later releases. |
| **v4.6.0** (junio de 2026) | **Portal web local** (`nspanelpro.local`: configuración por LAN, exportación de MQTT Discovery a HA y Matter Bridge); la inspección de la CDN del proyecto encontró paquetes diferenciales de actualización desde 4.0.12 / 4.4.0 / 4.5.1 | Documentado en el registro público de cambios de Sonoff. **4.6.2** está indexada como una actualización únicamente de la aplicación, sin actualización diferencial de ROM en ninguno de los dos canales, y no se ha encontrado ninguna versión 4.6.1. |
| **v4.7.0** (julio de 2026) | Se comentó en un hilo de opiniones de usuarios de eWeLink, pero está **ausente del registro público de cambios de Sonoff**; abarca paneles Gen1 y Gen2; los usuarios informan de que se añadió compatibilidad con el relé Basic de quinta generación (BASIC-1GS); la inspección de la CDN del proyecto encontró paquetes diferenciales de actualización desde 4.0.12 / 4.4.0 / 4.5.1 / 4.6.0 en ambos modelos, además de 4.5.3 solo en el 120P | Community reports of sub-device connectivity trouble, some reboot-resolved and some described as continuing; unverified by this project. Verify on one panel before deploying widely. |
| **v4.8.0** (agosto de 2026) | **No se encontró ningún anuncio de lanzamiento ni registro de cambios**; se localizó probando la CDN. Una publicación del personal de eWeLink del 16-07-2026 en el [hilo de la hoja de ruta](https://forum.ewelink.cc/t/nspanel-pro-roadmap-and-co-created-future/206240) la programaba para agosto y confirmaba una función: una opción para actualizar automáticamente el panel mediante la aplicación eWeLink. La inspección de la CDN del proyecto encontró paquetes diferenciales de actualización desde 4.0.12 / 4.4.0 / 4.5.1 / 4.6.0 / 4.7.0 en ambos modelos, además de 4.5.3 solo en el 120P | Contents otherwise unknown, and no community feedback thread has been found, so there is no report either way on stability. Treat it as unassessed rather than clean. **If the auto-update option ships enabled, a panel could take firmware unattended** — check that setting before relying on a pinned version. |

> [!NOTE]
> These are original 86P/120P quirks. The NSPanel Pro **Gen2** (RK3326-**S**, dual relays, EFR32**MG24**) is a different hardware target. Sonoff ships Gen1 and Gen2 on the same firmware version line (4.7.0 covers both), so do not infer a separate firmware line or assume every original-model note carries over.

Sibling Tuya-family boards — **S6E/T6E** (relay variants; S6E = T6E + 2 relays), [**S9E**](../../hardware/s9e.md) (Smatek), [**TPA10**](tpa10.md) (RK3566, Cortex-A55, Android 11) — are separate targets, not NSPanel Pro firmware.

> [!CAUTION]
> Detection can't rely on `ro.product.model` (both are `px30_evb`). Use `ro.product.version` / display metrics / `ro.board.platform` to tell 86P from 120P. Proximity behavior also differs between models and firmware, so ha-paneld learns from the live readings instead of selecting a firmware-specific classifier.

## Obtener acceso adb y root

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
> Disable the eWeLink apps (`com.eWeLinkNSPro.dev`, `com.eWeLinkControlPanel`) only **after** adb + `su` are solid and you have a home/back alternative — ha-paneld's nav actions cover the latter. Note the eWeLink **Zigbee gateway** stack is independent of these apps and keeps running; manage it with ha-paneld's [Zigbee router switch](#puerta-de-enlace-zigbee) rather than removing it.

## Firmware v4.0.0: instalación oficial de aplicaciones desde F-Droid

From **v4.0.0** (phased roll-out from 19 September 2025) the stock eWeLink firmware **officially bundles [F-Droid](https://f-droid.org/)** and promotes installing FOSS apps on the panel — Home Assistant's own Companion app is the headline example. Update via the panel (top drop-down → *Settings → About → Software update*) or the eWeLink app, then confirm both **APP Version** and **OS Version** read ≥ 4.0.0. Sonoff states F-Droid apps "will not affect NSPanel Pro's original features" (existing setups/automations stay intact) and that an app's F-Droid build "may differ slightly from the latest release". The update also markedly speeds up screen-swipe/UI responsiveness. Source: [Sonoff — NSPanel Pro V4.0.0 update](https://sonoff.tech/en-us/blogs/news/nspanel-pro-v4-0-0-update-now-supports-f-droid-and-home-assistant-app-install).

> [!NOTE]
> **Why this matters for ha-paneld.** F-Droid is a sanctioned, **on-device** install channel, so an APK can reach a panel with **no PC/adb** and F-Droid handles update notifications. **But F-Droid solves distribution, not privilege:** the headline features (overlay navbar, screen on/off, relays, button LEDs, Zigbee control) still need `su`, so the adb/root setup above stays a prerequisite for full function — only the non-privileged surface (MQTT discovery, sensors, brightness, HTTP UI) works on a stock unrooted panel.

## WebView: actualízalo primero

An 86P freshly flashed to firmware `3.5.1` (build `164637`) was verified with `com.android.webview` **107.0.5304.105** (Chromium 107), which is too old to render a current Home Assistant dashboard. Other firmware and models may differ, so check the installed provider before deciding whether to update. The archived OTA diff packages do not include a WebView APK, so this version was read from the live unit with `dumpsys webviewupdate`. That unit runs Chromium **138** after a clean adb update. See [Updating the system WebView](README.md#actualización-del-webview-del-sistema).

## LED

No se encontró ningún nodo `/sys/class/leds` RGB ni ningún `/dev/ledjni` en esta unidad, por lo que no se ha caracterizado **ningún LED RGB controlable mediante una aplicación o sysfs** en el NSPanel Pro (a diferencia del nodo `avsux` del TPA10 y el `/dev/ledjni` del WF1589T). El brillo y la retroiluminación de la pantalla usan las rutas estándar de Android.

## Sensores: la aplicación accede directamente a la luz y la proximidad

A diferencia del TPA10 (donde la luz y la temperatura solo están disponibles con acceso root), el NSPanel Pro expone su sensor combinado Sensortek mediante la interfaz `SensorManager` estándar: `android.sensor.light`, `android.sensor.proximity` y `android.sensor.accelerometer`; una aplicación normal puede leerlos todos sin acceso root. ha-paneld lee aquí directamente la luz y la proximidad. No incorpora ningún sensor de temperatura/humedad.

> [!NOTE]
> **Las lecturas de proximidad dependen tanto del firmware como del modelo.** El sensor es un ToF Sensortek STK3A5x situado en un hueco de la PCB superior tras el cristal frontal. La línea base en reposo varía mucho entre unidades (una ronda 1000 y otra 4000); solo importa el cambio *relativo*, por lo que una línea base alta en reposo es normal y no indica un fallo. Hasta aproximadamente el firmware **3.3** ofrece una lectura con rango (a intervalos de ~50 ms); entre aproximadamente **3.3 y 3.4**, el controlador del kernel pasó a valores binarios 0/1. **4.0.12 restauró las lecturas con rango solo en el 86P**; el **120P siguió usando valores binarios**. ha-paneld admite ambos según el comportamiento observado y normaliza el rango útil en toda la flota; los perfiles ya no codifican umbrales por firmware ni clasificadores de lecturas con rango o binarias. (Fuentes: herramientas de seaky n.º 142/144/171/262.)

<details>
<summary>Dispositivos i2c vinculados (hardware real)</summary>

| dirección i2c | controlador/nombre | Qué es |
|---|---|---|
| `0-0020` | `rk809` | PMIC |
| `1-001a` / `1-005a` | `CST226` / `CST226SE` | Controlador táctil capacitivo Hynitron |
| `2-003c` | `tp` | panel táctil |
| `2-0046` | `ls_stk3a5x` + `ps_stk3a5x` | Sensor combinado Sensortek **STK3A5x** de luz ambiental y proximidad |
| `2-0047` | `ls_stk3x3x` + `ps_stk3x3x` | Sensor Sensortek **STK3x3x** de luz y proximidad (variante alternativa) |

</details>

## Puerta de enlace Zigbee

El NSPanel Pro incorpora una **radio Zigbee 3.0 Silicon Labs EFR32** por UART `/dev/ttyS5`, controlada mediante una pila host del fabricante (`/vendor/bin/siliconlabs_host/zgateway`) a través de un broker MQTT local; es la misma pila que usan las aplicaciones eWeLink, por lo que el panel viene configurado como hub Zigbee de eWeLink.

ha-paneld manages it directly (v0.6.1+): `switch.<panel>_zigbee_router` turns the panel into a Zigbee **router/repeater** that extends your existing mesh (it starts the gateway and ensures the Repeater role), and turns it back off again (stopping the gateway, freeing the radio). It works over the local broker — credential-free, no `ttyS5` handling. The panel then appears as a normal router in your ZHA / Zigbee2MQTT coordinator.

> [!NOTE]
> Switching role is **not a reflash** — there is no `.gbl`/bootloader step; it just sets the EZSP node type. For partition-level firmware work see [Firmware backup & restore](../../firmware-backup-restore.md).

> [!NOTE]
> **No se ha documentado ni caracterizado ningún Thread Border Router.** La pila del proveedor instalada usa el EFR32 como NCP Zigbee. Aunque el chip EFR32MG21 admite varios protocolos, eso no demuestra que el panel incluya firmware Thread ni una implementación de border router; Sonoff documenta, en cambio, un Matter Bridge.
>
> **4.x reworked the Zigbee stack** — community inspection found a forked Zigbee2MQTT, a changed on-device MQTT password and a different boot sequence. [Sonoff documents coordinator↔router switching](https://sonoff.tech/blogs/news/nspanel-pro-v4-3-0-central-heating-redefining-whole-home-temperature-automation) in current firmware, but ha-paneld's private local-broker control path was built against ≤3.x and **may need adapting on 4.x**. (Community sources: seaky tools #244/#241/#255 and roottool#3.)

> [!WARNING]
> **A legacy vendor-native Zigbee-watchdog defect is confirmed by the reporter on NSPanel Pro 120 stock 3.8.0.** Firmware containing the recursive `LD_LIBRARY_PATH` assignment described in [Issue #34](https://github.com/maxlyth/ha-paneld/issues/34) can eventually make every external command launched by the watchdog fail with `E2BIG`, consume one CPU core and stop recovering a dead `zgateway`. A reboot resets the problem only temporarily. See [Performance tuning](../performance.md#descarte-el-defecto-heredado-del-supervisor-de-zigbee-del-firmware-de-fábrica-de-nspanel-pro) for the evidence boundary and repair-safety requirements. The reporter-provided workaround has not yet been independently validated by the project. Community inspection of 4.0.12 and 4.6.0 did not find the vulnerable assignment.

### Requisitos: firmware ≥ v2.2.0

The host stack is the **manufacturer's own** (eWeLink/Sonoff) gateway, versioned to match the panel firmware (e.g. `sonoff-v3.5.4`). Zigbee **router mode** was added in **NSPanel Pro firmware v2.2.0** (2023 — eWeLink app → *Device Settings → Pilot Features → Zigbee Mode*); local host-stack repeater support landed in gateway package v1.1.9. In practice:

- **Gateway present** (firmware ≥ v2.2.0, or side-loaded) → ha-paneld detects it and publishes `switch.<panel>_zigbee_router`. Toggle ON and the panel joins your coordinator as a router.
- **No gateway** (very old firmware, never provisioned) → the switch **doesn't appear** — it's gated on the gateway's launch script existing. Update firmware (≥ v2.2.0), or see migration below.

ha-paneld **drives** the gateway; it doesn't ship or install it (it's eWeLink's binary). Recent firmware (4.x) adds a Matter bridge and can export Zigbee devices to Home Assistant through MQTT Discovery — alternatives to the router role.

### Estado de la puerta de enlace y contención automática

On a Zigbee-capable panel, `sensor.<panel>_zigbee_gateway_health` reports the vendor stack independently of the router switch. This means an unconfigured stock gateway is still visible without granting ha-paneld permission to stop it.

When the router switch has explicitly been turned ON, ha-paneld allows a 15-minute startup and pairing grace, then watches once per minute for two runaway signatures: an explicitly invalid/unjoined network combined with more than 50% of one CPU core for five consecutive samples, or at least three gateway PID changes within ten minutes. A joined router with sustained high CPU is warning-only and remains running. Unknown 4.x layouts or missing firmware-specific join evidence fail safe to `unknown`.

Turning the Zigbee router switch ON explicitly requests Repeater mode even when the vendor gateway process is already running, so an ON command sent while your ZHA/Zigbee2MQTT coordinator permits joining acts as a fresh join retry without spawning a second gateway supervisor.

The Configure tab shows a **Request join** action directly beneath the Zigbee router switch. The existing switch remains the only on/off control. Enable permit-join on ZHA/Zigbee2MQTT, then request joining and confirm that permit-join is open. The action reasserts Repeater mode, starts a fresh 15-minute grace and polls the bounded health status; it does not reboot or restart the panel. The button is unavailable while the router is disabled, already joined or cooling down after a recent request.

After the pairing grace, an enabled gateway that is still unjoined produces a persistent dashboard, Install-tab and status-API warning linked to that Configure action. Do not leave it in that state: repeated join retries can consume substantial CPU. Either join the panel as a router or turn off the Zigbee router switch.

If a configured legacy gateway meets a runaway rule, ha-paneld persists the router switch OFF and attempts one bounded containment. Vendor-native containment can target only the Sonoff guard, `zgateway`, and the matching local broker. If a process cannot be stopped, the respawner is removed where possible and surviving gateway work is demoted to nice 19 and Android's background cpuset. Turning the router switch ON later explicitly starts one fresh grace period and retry.

The health attributes include firmware/product version, gateway layout/package version, joined/role status, rounded gateway and guard CPU, recent restart count and containment result. They never include the Zigbee network key, raw local-broker credentials, radio MAC or raw gateway `netinfo`.

### Migración desde NSPanelTools

[NSPanelTools (NSPPT)](https://github.com/seaky/nspanel_pro_tools_apk) side-loads the official Sonoff gateway package onto firmware that didn't ship it; many users run it today. ha-paneld coexists and can take over the gateway:

- **Side-by-side is fine.** ha-paneld's router control is idempotent — it **defers** to whatever already runs the gateway (won't double-start or fight NSPPT); auto-brightness is opt-in/off. Nothing conflicts by default.
- **Handing the gateway to ha-paneld:** the host stack lives in `/vendor` and **survives uninstalling the NSPPT app** (verified — a persistent hook even keeps boot-starting it). Remove the NSPPT APK and ha-paneld keeps driving the gateway; if the boot hook is also stripped, ha-paneld's boot-restore starts it when the switch was left ON.

> [!NOTE]
> Both tools touch the screen/sensors. Coexistence is benign today, but enabling overlapping features (e.g. wake-on-wave alongside an NSPPT equivalent) can cause redundant actions — remove NSPPT once ha-paneld covers your needs.

<details>
<summary>Detalles internos de la pila host EZSP (temas del broker, supervisor y persistencia de roles)</summary>

On the legacy vendor-native ≤3.x stack, the radio runs **EZSP NCP firmware** (EFR32MG21, EZSP v8); `zgateway` is an EZSP *host* binary in `/vendor/bin/siliconlabs_host/`, kept alive by its own `guard_process.sh` supervisor (a 5-second loop, boot-started) and controlled over a **local mosquitto broker** on `127.0.0.1:1883` (anonymous — the `password_file` line is commented out in `mosquitto.conf`). The 4.x stack differs as described above.

- role status: `zigbee/system/network-role/information` → `{"role":"Repeater"|"Coordinator"}`
- role switch: `zigbee/system/network-role/switch` ← `{"role":"Repeater"}`

"Repeater" is router mode (extends an existing mesh — the supported sweet spot); the role persists in the NCP's NVM. The vendor `zgateway` survives removal of the eWeLink *apps* (it lives in `/vendor`, not in an APK).

For a full standalone Zigbee2MQTT/ZHA coordinator *on the panel* instead, see [seaky/nspanel_pro_zigbee](https://github.com/seaky/nspanel_pro_zigbee), which swaps the host stack (heavier; not what ha-paneld does).

</details>

## Resumen del modelo de acceso

- **Luz/proximidad/acelerómetro**: acceso directo desde la aplicación (`SensorManager`).
- **Screen brightness / sleep / navigate / TTS**: standard Android paths (`su` for true backlight-off).
- **LED**: no se ha caracterizado ninguno.
- **Zigbee**: EFR32 radio managed via the on-device gateway's local broker (`switch.<panel>_zigbee_router`).
- **Radios**: Zigbee 3.0 + Wi-Fi/BT.

## Rendimiento esperado

El NSPanel Pro tiene **limitaciones de CPU y RAM** para paneles de control complejos:

- En reposo funciona a 408 MHz y usa ≈500 MB de RAM; un panel de Lovelace pesado exige mucho de ambos recursos.
- **Los 2 GB de RAM son el principal factor limitante**: el WebView del panel de control, Android y las aplicaciones en segundo plano compiten por ella; los paneles grandes con muchas tarjetas, imágenes de gran tamaño, gráficos de historial extensos o tarjetas personalizadas costosas provocan recargas y tirones en WebView.
- Los núcleos A35 hacen que las transiciones entre páginas y las animaciones sean visiblemente más lentas que en los paneles A55/A72.

Al usar el renderizador integrado de ha-paneld, empieza con el [filtro automático de entidades del panel de control](../performance.md#1-filtre-la-suscripción-a-entidades-del-renderizador-integrado) para que el panel no procese estados que nunca muestra. Después, usa las tarjetas de rendimiento de la pestaña Panel de control para identificar las vistas que aún sean pesadas, la presión de memoria o los límites térmicos antes de simplificar el panel. El filtro no está disponible para el renderizador Companion, donde ajustar la frecuencia de actualización en el origen y usar un panel más ligero siguen siendo las opciones compatibles.

---

Consulta el [índice de hardware de paneles](README.md) para ver la comparación entre paneles y el método, así como las referencias de [TPA10](tpa10.md) / [WF1589T](wf1589t.md) / [S9E](../../hardware/s9e.md) para los demás paneles.
