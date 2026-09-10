> [!IMPORTANT]
> Este documento se genera automáticamente y se somete a comprobaciones cruzadas automáticas, pero no ha sido revisado sistemáticamente por hablantes de este idioma. La documentación en inglés es la fuente de referencia. [Consulta la fuente en inglés](../../hardware/README.md) o [abre una incidencia para corregir la traducción](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Referencias de hardware de los paneles

Reverse-engineered hardware fact sheets for the wall panels ha-paneld targets — SoC, LED control, sensors, buttons, NFC, Zigbee/IR, relays, adb/root access. These devices ship with almost no public documentation, so these notes record what is physically on each board and how to drive it, gathered from live units (rooted / userdebug `adb root`). If your panel is not listed, start with the no-build [runtime profile authoring workflow](../../profiles/README.md). If your panel has a camera the Camera card does not offer, see [enabling the camera on a panel whose profile does not declare one](../../profiles/unofficial/README.md#enabling-the-camera-on-a-panel-whose-profile-does-not-declare-one) and keep unverified hardware facts explicit.

| Panel | SoC | Control de LED | Sensores destacados | NFC | Zigbee/IR | Referencia |
|---|---|---|---|---|---|---|
| Tuya TPA10 | rk3566 | `avsux` sysfs (demonio root) | ToF VI5300, temperatura+humedad CHT8305, luz CG5256; **[cámara](tpa10.md#cámara)** (GC05A2) y ADC de captura ES7202 (no se ha verificado que el micrófono sea utilizable) | no | no | [tpa10.md](tpa10.md) |
| Electron WF1589T | rk3576 | `/dev/ledjni` (directo desde la aplicación) | IMU de 6 ejes (KXTJ9 + BMA2xx); **[cámara](wf1589t.md#cámara)** (GC05A2) y micrófono ES7202 | sí (NXP, pero NFC de Android deshabilitado) | no | [wf1589t.md](wf1589t.md) |
| Sonoff NSPanel Pro | rk3326 / PX30 | ninguno (sin nodo RGB) | luz + proximidad STK3A5x (directo desde la aplicación) | no | **Zigbee** (Silabs EFR32, UART); sin IR | [nspanel-pro.md](nspanel-pro.md) |
| Smatek S9E † | rk3566 | LED GPIO por botón (root) | proximidad por radar, luz, temperatura+humedad; **2 relés de red eléctrica** (`st_relay`); RS485 + Ethernet | no | **Zigbee** | [s9e.md](../../hardware/s9e.md) |
| ZHICAI SMT1019 ‡ | rk3576 | servicio auxiliar con privilegios root en la compilación `userdebug` del proveedor; no disponible en el firmware de fábrica | temperatura + humedad GXHT30 (precisión sin verificar); proximidad VI530x experimental | no | no | [smt1019.md](../../hardware/smt1019.md) |
| ZX-SMT156 / RK3566_T ‡ | rk3566 | `/dev/ledjni` (directo desde la aplicación) | proximidad binaria, luz ambiental; temperatura+humedad GXHT30 (mediante el servicio auxiliar o una alternativa fija ejecutada desde el shell) | desconocido | se han notificado relés del proveedor; ruta de control desconocida | [zx-smt156.md](../../hardware/zx-smt156.md) |
| Shelly Wall Display § | MT6580 / SC7731E / RK3326-S / RK3566 (según el modelo) | ninguno confirmado | luz ambiental en original/X2/X1i/X2i/XL; temperatura/humedad en original + X2; proximidad en X2/X1i/X2i; movimiento en XL; los relés varían según el modelo/base | no confirmado para todos los modelos | no confirmado para todos los modelos | [shelly-wall-display.md](../../hardware/shelly-wall-display.md) |

† Las especificaciones del S9E proceden del listado de Smatek; las rutas de control proceden de [#98](https://github.com/seaky/nspanel_pro_tools_apk/issues/98) y del hilo de la comunidad de HA, y **no** se han validado aquí en una unidad. La compatibilidad con relés/botones está implementada, pero no se ha probado.

‡ Los datos de SMT1019 y ZX-SMT156 proceden de diagnósticos aportados por usuarios y de pruebas enlazadas del OEM o de comercios ([#8](https://github.com/maxlyth/ha-paneld/issues/8), [#24](https://github.com/maxlyth/ha-paneld/issues/24)); ninguno de los dos paneles está disponible para pruebas locales. Hay pruebas aportadas por usuarios de la persistencia del servicio auxiliar y de las variables climáticas sin procesar del SMT1019, pero la precisión de las mediciones climáticas, la proximidad de extremo a extremo y el perfil completo aún requieren pruebas de hardware. La compatibilidad con las mediciones climáticas en ZX es opcional; las vías de acceso root por USB o del proveedor y de desbloqueo persistente siguen sin probarse.

§ Los datos de Shelly Wall Display proceden del análisis del firmware OTA (incluido el análisis del árbol de dispositivos de la imagen de partición moderna), del registro oficial de cambios y de fuentes de la comunidad/base de conocimientos; **no** se han validado aquí en una unidad. La OTA **heredada** declara que la compilación de destino es `userdebug`, por lo que `adb root` podría estar disponible en ella si se consigue acceso inicial mediante adb; la OTA **moderna** no declara ningún tipo de compilación. Consulte [shelly-wall-display.md](../../hardware/shelly-wall-display.md) para ver las pruebas de cada rama y la declaración de la propia Shelly sobre el hardware actual. Los perfiles YAML `shelly-wall-display` y `shelly-wall-display-v2` incluidos están implementados, pero son especulativos.

> [!TIP]
> Before modifying firmware on a rooted **TPA10 or WF1589T**, read [Firmware backup & restore](../../firmware-backup-restore.md). Those Rockchip panels use `adb reboot loader` and `rkdeveloptool` rather than the usual Android button combination. The guide does not apply to the MediaTek Shelly family, an unrooted panel or uncharacterised hardware, and it does not yet claim a write-ready Maskrom recovery path.

## Método

- **Silicio real**: dispositivos i2c vinculados mediante `/sys/bus/i2c/devices/*/name`; *no* `…/drivers/`, porque los BSP de Rockchip incorporan cientos de controladores opcionales y el listado `drivers/` da muchos falsos positivos.
- **Radios**: `pm list features` (`nfc`, `consumerir`, `bluetooth`, `ethernet`, …) + nodos `/dev`.
- **Sensores expuestos por Android**: `dumpsys sensorservice`.
- **Superficies de control**: `/sys/class/leds`, `/dev` y los atributos propios de cada nodo LED (algunos paneles se autodescriben, como `avsux_info` / `avsux_firmware` del TPA10).

Las correcciones y ampliaciones para otros paneles son bienvenidas.

The `Native` navbar mode is profile-gated, not specific to Electron panels. The bundled WF1589T profile currently declares it because that firmware's Android navbar has been verified. Other profiles can enable the same mode after their system bar has been confirmed.

## Obtención de acceso adb + root

Cada panel obtiene acceso adb/root de forma distinta; las páginas de cada panel contienen los pasos completos específicos del firmware:

- **Sonoff NSPanel Pro** — `userdebug`/test-keys, **no adb password**; the only hurdle is reaching developer mode (varies by eWeLink firmware). `adb root` + remount + a SuperSU `su`. → [nspanel-pro.md](nspanel-pro.md#obtener-acceso-adb-y-root).
- **Tuya TPA10** — adb is **password-protected**; the reliable route is the USB diagnostics-app backdoor (`su` already present). → [tpa10.md](tpa10.md#obtener-acceso-adb-y-root).
- **Electron WF1589T** — `userdebug` with Google Play; `adb root` works directly (LED is app-direct, so root is rarely needed). → [wf1589t.md](wf1589t.md).

## Comparación de rendimiento y despliegue práctico

Las tres clases de panel forman una jerarquía clara: **NSPanel Pro (PX30)** en la gama básica, **TPA10 (rk3566)** en la gama media y **WF1589T (rk3576)** en la gama alta. La geometría de la pantalla es la primera limitación de diseño; en los paneles de 2 GB, la RAM es la limitación principal. Las cifras proceden del endpoint `/perf` de ha-paneld y de las especificaciones de los dispositivos.

<details>
<summary>Jerarquía de especificaciones (CPU / RAM / GPU / pantalla)</summary>

| | NSPanel Pro (PX30) | TPA10 (rk3566) | WF1589T (rk3576) |
|---|---|---|---|
| CPU | 4× Cortex-A35 a 1,5 GHz | 4× Cortex-A55 a 1,8 GHz | 4× A72 a 2,1 GHz + 4× A53 a 1,9 GHz |
| RAM | 2 GB | 2 GB | 4 GB |
| GPU | Mali-G31 | Mali-G52 (2EE) | Mali-G52 (MC3) |
| Pantalla | 480×480 **cuadrada**, ~4" | 1920×1200 16:10, ~10,1"/~226 ppp | 1920×1200 16:10, ~10,1"/~226 ppp |
| Frecuencia de actualización | 60 Hz | 56 Hz | 60 Hz |
| Diseño (dp) | base lógica de 160 dpi → 480×480 dp | base lógica de 240 dpi; ha-paneld recomienda 212 | base lógica de 160 dpi → 1920×1200 dp; interfaz diminuta, [aumente la densidad](wf1589t.md#densidad-de-pantalla-auméntela) |
| Cámara | ninguna | GC05A2, `Facing: Back`; codificación H.264 mediante `OMX.rk.video_encoder.avc` | GC05A2, `Facing: Front`; codificación H.264 mediante `c2.rk.avc.encoder` |
| Clase | gama básica | gama media | gama alta |

</details>

<details>
<summary>Instantánea de `/perf` en funcionamiento (ilustrativa, no es una prueba de rendimiento controlada)</summary>

Cada panel con su propia carga de trabajo real:

| | PX30 (casi inactivo) | WF1589T (panel de control activo) |
|---|---|---|
| CPU | 9 % | 29 % |
| Frecuencia | 408 MHz (de 1512) | núcleos de alto rendimiento a 1608 MHz (de 2112) |
| RAM utilizada | 508 / 1960 MB | 2265 / 3897 MB |
| Temperatura | 49 °C | 63 °C |
| Capacidad de respuesta | fluido, hilo principal 3,6 % | fluido, hilo principal 25,9 % |

(El TPA10 queda entre ambos en cuanto a CPU.)

</details>

**Qué significa esto para desplegar un panel de control real:**

- **La geometría de la pantalla es la primera limitación de diseño.** La pantalla **cuadrada** de 480×480 del NSPanel Pro (480 dp) solo admite una columna estrecha; la pantalla de 10,1" y 1920×1200 del TPA10 ofrece mucho espacio para paneles de control con varias columnas; el WF1589T se entrega con una densidad lógica base baja, por lo que la interfaz es diminuta hasta que se aumenta. Diseñe el panel de control según el **lienzo en dp y la relación de aspecto** del panel, no según su número bruto de píxeles. Los DPI lógicos base de Android son un ajuste de diseño, no los PPP físicos.
- **Paneles de 2 GB (PX30, TPA10): la RAM es la limitación principal.** El WebView del panel de control, Android y las aplicaciones en segundo plano comparten unos 2 GB; los paneles de control pesados con muchas tarjetas, imágenes grandes, gráficos de historial extensos o tarjetas personalizadas costosas provocan recargas y tirones en WebView. Los 4 GB del WF1589T eliminan en gran medida esta presión.
- **El NSPanel Pro tiene la CPU más lenta de esta comparación** (A35), por lo que las transiciones y animaciones son visiblemente más lentas que en las unidades A55/A72. Mantenga sus paneles de control lo más ligeros posible.
- **En el renderizador integrado, filtre la suscripción a entidades de Home Assistant antes de simplificar un panel de control o sustituir el panel.** El aprendizaje automático de entidades puede impedir que estados no relacionados lleguen a WebView y conservar a la vez la conexión normal del panel con Home Assistant. Consulte [Ajuste del rendimiento](../performance.md).
- **ha-paneld mide los cuellos de botella restantes**: el tiempo de respuesta del panel de control, las recargas inesperadas, la frecuencia y la limitación térmica de la CPU, la presión de memoria, los procesos más activos y las métricas de renderizado de WebView ayudan a distinguir los límites del hardware de un panel de control costoso o de un exceso de datos.

## Actualización del WebView del sistema

**Lea esto antes que nada**: es el fallo más común durante el primer inicio en estos paneles.

ha-paneld's built-in renderer and the HA Companion app both rely on Android's **system WebView**, and most of these panels ship with one far too old to run a current Home Assistant frontend. Out of the box this can produce a **blank or broken dashboard, missing cards, or "browser not supported"**. Panels **without** Google Play (NSPanel Pro, TPA10) cannot update it automatically through the Play Store, so install a current WebView using the appropriate method below. The **WF1589T and the SMT1019 have Google Play**, so update *Android System WebView* from the Play Store or use the Play WebView development channel.

The clean way is a direct adb sideload of the standard Android System WebView (package **`com.android.webview`**), matched to the panel's Android version and ABI — **no F-Droid, no third-party app store** (the workarounds the NSPanel-Pro community threads resort to). Per-panel known-working builds and the full sideload/verify steps are below.

> [!TIP]
> The package name must be `com.android.webview` for the system to select it automatically. Mind the distinction: the **SystemWebView** builds from Cromite and LineageOS use `com.android.webview` and *do* register as the provider — but the regular **Cromite / Bromite *browser*** app uses a different package and does **not**. Use the SystemWebView build, not the browser APK.

### Versiones de fábrica y sustitutos de funcionamiento conocido para cada panel

"Stock" = what the vendor firmware ships from factory, verified from firmware OTA inspection or a live device. "Replacement" = what is confirmed working after sideload. Redistributable builds are mirrored as ha-paneld Release assets; sideload with `adb install -r <file>`.

| Panel | ABI | De fábrica (firmware del proveedor) | Sustituto (`com.android.webview`) | Descarga |
|---|---|---|---|---|
| NSPanel Pro 86P (PX30) | arm64-v8a | Chromium **107.0.5304.105** verified on firmware 3.5.1; check other firmware/models before updating | **LineageOS** 138.0.7204.63 — last build for Android **8.1** | [recurso de la versión](https://github.com/maxlyth/ha-paneld/releases/download/webview-mirror/lineageos-webview-138.0.7204.63.apk) · [APKMirror](https://www.apkmirror.com/apk/lineageos/android-system-webview-2/android-system-webview-138-0-7204-63-2-release/android-system-webview-138-0-7204-63-8-android-apk-download/download) |
| TPA10 (rk3566) | armeabi-v7a | **Chrome 83** (`com.android.webview`); demasiado antiguo para la interfaz actual de HA | **LineageOS** SystemWebView 150.0.7871.63 — vanilla Chromium, allows camera autoplay (Cromite 147 blocks it, kept as fallback). **Signature-locked — needs the root swap in [tpa10.md](tpa10.md#webview-actualízalo-primero), not a plain sideload.** | [recurso arm](https://github.com/maxlyth/ha-paneld/releases/download/webview-mirror/lineageos-webview-150.0.7871.63-arm.apk) |
| WF1589T (rk3576) | arm64-v8a | WebView de Google Play (se actualiza automáticamente) | update via Play Store — no sideload needed | — |
| S9E (rk3566) | **arm64-v8a** | **Firmware-dependent — check before replacing.** Chromium **83.0.4103.120** on the 2024-07 build (too old for a current HA frontend), but **131.0.6778.200** on the 2025-12 build | Only needed on the older firmware: **LineageOS** 150.0.7871.63 (**arm64**) — *provisional, unverified hardware*; may be signature-locked like the TPA10. On 2025-12 firmware the stock WebView is already current | [recurso arm64](https://github.com/maxlyth/ha-paneld/releases/download/webview-mirror/lineageos-webview-150.0.7871.63-arm64.apk) |
| SMT1019 (rk3576) | arm64-v8a | `com.google.android.webview` **124.0.6367.179**; Android 14 **con** Google Play | **Update *Android System WebView* from the Play Store** — no sideload needed. Do not sideload a `com.android.webview` build here: the panel's own provider list is supplied by a product overlay, so a sideloaded provider may not be selected | — |
| ZX-SMT156 / RK3566_T | arm64-v8a | Google WebView **149.0.7827.164** (firmware del usuario que informó) | Google WebView is current; no replacement needed | — |
| Shelly Wall Display original (MT6580) | armeabi-v7a | **desconocido** (ROM base Android 7) | `com.google.android.webview` **119.0.6045.194** via [official Shelly ZIP](https://repo.shelly.cloud/firmware/SAWD-0A1XX10EU1/stable/SAWD-0A1XX10EU1-WebViewUpdate.zip) — see [shelly-wall-display.md](../../hardware/shelly-wall-display.md#webview) | — |
| Shelly Wall Display X2 (SC7731E) | armeabi-v7a | **desconocido** (ROM base Android 8.1) | sin confirmar; compruebe `adb shell dumpsys webviewupdate` | — |
| Shelly Wall Display X1i/X2i/XL (arm64) | arm64-v8a | **desconocido** (ROM base Android 11; no está presente en la OTA de Shelly) | sin confirmar; compruebe `adb shell dumpsys webviewupdate` | — |

Todas las compilaciones replicadas están disponibles en la [**réplica de WebView para paneles** publicada](https://github.com/maxlyth/ha-paneld/releases/tag/webview-mirror); se concibió como una lista comunitaria viva de versiones de funcionamiento conocido. ¿Ha conseguido que alguna funcione en otro panel o versión? Las contribuciones son bienvenidas.

> [!NOTE]
> - **Pick the newest WebView your panel's Android version supports.** The NSPanel Pro's Android 8.1 caps at 138 (the last Chromium for Android 8/9); newer builds won't install. Android 10+ (the TPA10's 11) runs current **LineageOS** WebView (150).
> - **Los enlaces de descarga *directa* de APKMirror son URL prefirmadas de corta duración que caducan en una hora**; use la página o los archivos de la versión de ha-paneld indicados arriba (duraderos). La réplica existe precisamente porque los paneles carecen de Play y se distribuyen con firmware de hace años; de otro modo, encontrar una compilación funcional puede llevar días.

<details>
<summary>Pasos para instalar mediante sideload y verificar</summary>

1. Download a current **Android System WebView** APK — package **`com.android.webview`**. **LineageOS** System WebView is the recommended build across Android versions: 138 is the last for Android 8.1, and 150 covers Android 10+ (both in the mirror). It's vanilla Chromium, so it doesn't carry Cromite's autoplay block that stops HA camera streams. It uses the `com.android.webview` package, so it's picked as the provider automatically (no allowlist editing, no extra app), and it's open / freely redistributable. Match your panel's ABI; per-panel downloads are above.

> [!IMPORTANT]
> The simple sideload below works on panels whose ROM waives the WebView signature check (e.g. the NSPanel Pro's userdebug build). **Signature-locked panels (the TPA10, and likely other vendor user builds) reject a plain sideload** — `signatures do not match`. Those need the one-time root swap (replace the system WebView file + clear its `packages.xml` entry); see [tpa10.md → WebView](tpa10.md#webview-actualízalo-primero) for the exact procedure.
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

Fichas de datos por panel: [NSPanel Pro](nspanel-pro.md) · [TPA10](tpa10.md) · [WF1589T](wf1589t.md) · [S9E](../../hardware/s9e.md) · [SMT1019](../../hardware/smt1019.md) · [ZX-SMT156](../../hardware/zx-smt156.md) · [Shelly Wall Display](../../hardware/shelly-wall-display.md).
