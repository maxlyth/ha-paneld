> [!IMPORTANT]
> Este documento se genera automáticamente y se somete a comprobaciones cruzadas automáticas, pero no ha sido revisado sistemáticamente por hablantes de este idioma. La documentación en inglés es la fuente de referencia. [Consulta la fuente en inglés](../../README.md) o [abre una incidencia para corregir la traducción](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="../../app/src/main/res/drawable-night-nodpi/wordmark.png">
  <img src="../../app/src/main/res/drawable-nodpi/wordmark.png" width="360" alt="ha-paneld">
</picture>

[![CI](https://github.com/maxlyth/ha-paneld/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/maxlyth/ha-paneld/actions/workflows/ci.yml)
[![Versión](https://img.shields.io/github/v/release/maxlyth/ha-paneld?include_prereleases&sort=semver&style=flat-square&color=blue)](https://github.com/maxlyth/ha-paneld/releases)
[![Licencia](https://assets.ha-paneld.com/docs/badge/license-apache-2-0-8aa187e4.svg)](../../LICENSE)

<!-- docs-i18n-language-picker:start -->
[English](../../README.md) · [Deutsch](../de/README.md) · [Français](../fr/README.md) · [Italiano](../it/README.md) · **Español** · [简体中文](../zh-Hans/README.md)
<!-- docs-i18n-language-picker:end -->

**La aplicación universal de paneles de control de Home Assistant para paneles de pared Android.**

ha-paneld hace que los paneles de control de Home Assistant resulten prácticos en paneles que, de otro modo, serían demasiado lentos o incómodos de usar. Los paneles de baja potencia pueden ralentizarse o tardar varios segundos en responder cuando están conectados a una instalación grande de Home Assistant. Una causa importante es que el panel recibe y procesa actualizaciones de muchas más entidades de las que muestra su panel de control. **El renderizador integrado de ha-paneld puede aprender qué entidades utiliza el panel de control y pedir a Home Assistant que envíe únicamente esos estados**. En condiciones reales, esto puede reducir la carga de entidades entre 10 y 100× y hacer que, por fin, ese panel de control sea utilizable.

ha-paneld también proporciona a distintas marcas de paneles de pared un conjunto uniforme de controles en Home Assistant. Según el hardware, puede incluir la pantalla, los LED, los botones, los sensores, los relés y el audio. El descubrimiento de MQTT añade los controles disponibles sin YAML específico para cada dispositivo, y el instalador se encarga de la configuración de Android.

Esta es una aplicación para paneles de pared dedicados, no para teléfonos personales. La compatibilidad con el hardware se describe mediante perfiles YAML convencionales, por lo que los propietarios y fabricantes pueden añadir otro panel sin tener que recompilar la aplicación.

La interfaz web ofrece un único lugar para configurar un panel, instalar software y averiguar qué ha fallado. Sus herramientas de rendimiento miden el tiempo de respuesta del panel de control, las recargas inesperadas, la carga de la CPU y la GPU, la velocidad del reloj, la temperatura y los procesos con mayor actividad. El instalador proporciona el mismo procedimiento de configuración y actualización para una colección heterogénea de paneles, mientras que el lanzador integrado y la navegación en pantalla permiten usar de forma práctica los paneles sin teclas físicas.

<picture>
  <source media="(prefers-color-scheme: light)" srcset="https://assets.ha-paneld.com/docs/screenshot/hero-light-a17f5f14.webp">
  <img src="https://assets.ha-paneld.com/docs/screenshot/hero-dark-aeb93099.webp" alt="Panel de control de ha-paneld que muestra el estado del panel en tiempo real, el rendimiento y los controles de la pantalla">
</picture>

<details>
<summary><strong>Más capturas de pantalla</strong></summary>

| Panel de control | Configurar |
|---|---|
| <a href="../img/ui-dashboard-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-dashboard-light.png"><img src="../img/ui-dashboard-dark.png" alt="Pestaña Panel de control" width="420"></picture></a> | <a href="../img/ui-configure-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-configure-light.png"><img src="../img/ui-configure-dark.png" alt="Pestaña Configurar" width="420"></picture></a> |

| Entidades | Instalar |
|---|---|
| <a href="../img/ui-entities-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-entities-light.png"><img src="../img/ui-entities-dark.png" alt="Pestaña Entidades" width="420"></picture></a> | <a href="../img/ui-install-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-install-light.png"><img src="../img/ui-install-dark.png" alt="Pestaña Instalar" width="420"></picture></a> |

| Perfil | Registros |
|---|---|
| <a href="../img/ui-profile-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-profile-light.png"><img src="../img/ui-profile-dark.png" alt="Pestaña Perfil" width="420"></picture></a> | <a href="../img/ui-logs-light.png"><picture><source media="(prefers-color-scheme: light)" srcset="../img/ui-logs-light.png"><img src="../img/ui-logs-dark.png" alt="Pestaña Registros" width="420"></picture></a> |

| Pantalla de espera | Explorador de la API REST |
|---|---|
| <img src="../img/standing-screen.png" alt="Pantalla de espera de ha-paneld con la dirección de configuración y el código QR" width="420"> | <picture><source media="(prefers-color-scheme: light)" srcset="../img/api-explorer-light.png"><img src="../img/api-explorer-dark.png" alt="Explorador de la API REST" width="420"></picture> |

</details>

## Instalación

Si no tienes claro si ha-paneld puede ejecutarse en tu panel, consulta [Paneles y estado de compatibilidad](#paneles-y-estado-de-compatibilidad) antes de instalarlo.

Primero, habilita ADB a través de la red. En algunos paneles, esto se configura en las opciones para desarrolladores; otros necesitan una única conexión USB para ejecutar `adb tcpip 5555`. La [guía de aprovisionamiento](provisioning.md) y las [guías de hardware](../hardware/) específicas de cada modelo explican los métodos disponibles. A continuación, ejecuta lo siguiente desde un ordenador que tenga `adb` y esté en la misma red:

```sh
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash
```

> [!IMPORTANT]
> **En Windows, usa Git Bash o WSL, no PowerShell.** El instalador es un script de `bash`. Git Bash se incluye con [Git for Windows](https://gitforwindows.org/). Instala `adb` con `winget install Google.PlatformTools`, vuelve a abrir el intérprete de comandos y ejecuta el comando. En macOS y Linux puedes ejecutarlo tal como está escrito.

No necesitas clonar el repositorio ni proporcionar ninguna opción. El instalador comprueba que `adb` y `curl` estén disponibles, solicita la dirección del panel y explica cada cambio antes de realizarlo. Descarga la versión estable firmada más reciente, la instala y comprueba que ha-paneld se haya iniciado correctamente.

Si falla un paso obligatorio, el instalador indica el problema y se cierra sin afirmar que la instalación se haya completado correctamente. Corrige el problema y vuelve a ejecutar el mismo comando.

> [!IMPORTANT]
> **Comprueba Home Assistant y el WebView del sistema del panel antes de cargar el panel de control por primera vez.** El renderizador integrado requiere Home Assistant 2026.4.2 o posterior y un WebView moderno. Incluso un panel nuevo puede incluir un WebView demasiado antiguo para mostrar un panel de control actual. Consulta [Requisitos del renderizador integrado](built-in-renderer.md#requisitos-y-compatibilidad) y [Actualización del WebView del sistema](../hardware/README.md#updating-the-system-webview).

Para seguir la versión publicada más reciente, incluidas las versiones candidatas, añade `--prerelease`. Una versión estable más reciente sigue teniendo prioridad:

```sh
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --prerelease
```

El mismo instalador permite el aprovisionamiento desatendido de un solo panel. Consulta [Aprovisionamiento y actualizaciones de flotas](provisioning.md) para obtener información sobre instalaciones mediante scripts, arranque inicial por USB, paneles sin ADB de red y actualizaciones de toda la flota.

ha-paneld no se distribuye a través de Google Play, por lo que la instalación siempre requiere carga lateral. Esto también se aplica a los paneles más recientes que, por lo demás, tienen acceso a Play Store.

### Otros métodos de instalación

- **F-Droid en el panel:** añade el [repositorio de F-Droid de ha-paneld](../fdroid.md) para instalar y actualizar versiones estables sin un ordenador. F-Droid te avisa cuando hay una actualización disponible y te permite instalarla en el panel; las versiones candidatas no están incluidas. El firmware 4.0.0 y posterior de Sonoff NSPanel Pro incluye F-Droid. Esto instala la aplicación, pero las funciones que requieren acceso root siguen necesitando los pasos de aprovisionamiento habituales.
- **Carga lateral manual o arranque inicial por USB:** usa el APK de la [versión más reciente](https://github.com/maxlyth/ha-paneld/releases) y sigue [Aprovisionamiento y actualizaciones de flotas](provisioning.md) para configurar los permisos restantes y completar la puesta en marcha.

## Elige cómo se ejecuta el panel de control

Usa el renderizador integrado si quieres filtrar las entidades del panel de control. También permite iniciar sesión desde otro navegador, seleccionar una pestaña específica del panel de control y agilizar el inicio y la recuperación. Tras reiniciar la aplicación, puede volver a abrir el panel de control predeterminado de la cuenta verificado más recientemente mientras actualiza en segundo plano la lista de paneles de control de Home Assistant.

También se admite la aplicación oficial [Home Assistant Companion](https://github.com/home-assistant/android). Úsala cuando el panel necesite más de un servidor de Home Assistant, el control por voz de Assist o notificaciones nativas. En un panel sin Google Play y con un método de instalación compatible, usa la pestaña Instalar de ha-paneld. El selector aplica el límite de compatibilidad de ese panel en lugar de asumir que la versión más reciente de Companion funcionará en él.

Ambas opciones siguen siendo compatibles. El filtrado de entidades del panel de control solo funciona con el renderizador integrado de ha-paneld.

<a id="panels-and-support-status"></a>

## Paneles y estado de compatibilidad

No es necesario instalar ha-paneld como aplicación del sistema. Los controles básicos de Android, como el brillo, la navegación y TTS, funcionan en paneles compatibles. Los LED, los relés, el apagado real de la pantalla y algunos sensores requieren que su [perfil de panel](../profiles/README.md) incluya soporte para ese modelo. Los eventos de los botones físicos requieren la captura mediante Accesibilidad de Android o un método de perfil verificado.

| Panel | Estado | Android / ABI | Notas |
|---|---|---|---|
| Sonoff NSPanel Pro / Pro 120 | Compatible | Android 8.1, arm64-v8a | PX30 / rk3326-S; el firmware de fábrica proporciona ADB root y el aprovisionamiento normal instala el asistente root autenticado de ha-paneld |
| Tuya TPA10 | Compatible | Android 11, armeabi-v7a | rk3566 con espacio de usuario de 32 bits |
| Electron WF1589T | Compatible | Android 14, arm64-v8a | firmware userdebug rk3576; `adb root`, barra de navegación nativa de Android y control del LED RGB |
| ZHICAI SMT1019 | Probado por la comunidad; algunas funciones son experimentales | Android 14, arm64-v8a | rk3576; el firmware de fábrica no dispone de acceso root accesible para las aplicaciones. El asistente autenticado puede proporcionar acceso adicional al hardware donde esté instalado. La precisión de las mediciones de temperatura y humedad y la compatibilidad con la proximidad aún requieren más pruebas de hardware. [Incidencia #8](https://github.com/maxlyth/ha-paneld/issues/8) |
| ZX-SMT156 / RK3566_T | Preliminar | Android 13, arm64-v8a | El LED RGB y los sensores de luz y proximidad funcionan sin root. La compatibilidad con la medición de temperatura y humedad es opcional; los relés y el acceso root aún se están caracterizando. [Incidencia #24](https://github.com/maxlyth/ha-paneld/issues/24) |
| Smatek S9E | Experimental | Android 11, arm64-v8a | Perfil para los relés integrados, los LED de los botones y la proximidad. Aún se necesita confirmación en hardware S9E real. |
| Shelly Wall Display (original) | Software de fábrica incompatible | Android 7.0, armeabi-v7a | Android es anterior a la versión mínima de ha-paneld. |
| Shelly Wall Display X2 | Solo para investigación | Android 8.1, armeabi-v7a | No hay ninguna ruta de instalación de ha-paneld confirmada. |
| Shelly Wall Display X1i / X2i / XL | Solo para investigación | Android 11, arm64-v8a | Los metadatos del perfil aún deben separarse por modelo. No hay ninguna ruta de instalación de ha-paneld confirmada. |

Consulta la [documentación del hardware](../hardware/) para conocer la configuración específica de cada modelo, las limitaciones conocidas y los detalles del hardware obtenidos mediante ingeniería inversa.

## Capacidades de control del hardware

Cada panel publica únicamente los controles compatibles con su perfil y con el hardware detectado. Sus nombres y comportamiento son coherentes entre los distintos modelos.

| Capacidad | Control mediante Home Assistant o la API |
|---|---|
| Brillo de la pantalla | `light.<panel>_screen` brillo |
| Encendido/apagado de la pantalla | `light.<panel>_screen` encendido/apagado; apagado real de la pantalla cuando el perfil lo admite; en caso contrario, atenuación segura del brillo |
| LED RGB | `light.<panel>_led` en paneles con hardware LED compatible |
| Botones físicos | `event.<panel>_button` cuando esté disponible la captura mediante Accesibilidad de Android o un método de perfil verificado |
| Luz ambiental y proximidad | `sensor.<panel>_illuminance`, `binary_sensor.<panel>_proximity` y un valor normalizado de `sensor.<panel>_proximity_level` entre 0 (lejos) y 100 (cerca) |
| Brillo adaptativo | Aprendizaje opcional durante siete días mediante el sensor de luz del panel o una entidad de iluminancia de Home Assistant |
| Abrir una URL | `text.<panel>_navigate` |
| Controles del panel de control y reinicio | Botones de Home Assistant, además de las acciones Panel de control, Recargar y de navegación en el panel remoto Controles |
| Audio de TTS y anuncios | `POST /play` y `number.<panel>_volume`; consulta la [guía de TTS](../tts.md) |
| Captura del panel de control y toque remoto | Los paneles con un método de captura de pantalla compatible pueden mostrar y actualizar la pantalla desde la pestaña Panel de control; el modo relajado también permite enviar un clic al panel |
| Información y configuración del panel | Abre `http://<panel>:8888/`, también enlazado como **Visitar** en la página del dispositivo de Home Assistant |

Home Assistant detecta estos controles mediante MQTT sin YAML. Las principales familias de entidades y los detalles sobre la API HTTP y el emparejamiento se encuentran en [docs/api.md](../api.md). También puedes explorar y probar la API HTTP de un panel en `http://<panel>:8888/api`.

## Seguridad y acceso root

### Modo reforzado

El modo relajado es el predeterminado y está destinado a redes domésticas de confianza. Usa el [modo reforzado](../security-mode.md) cuando haya dispositivos menos fiables en la misma red. El modo reforzado requiere acceso físico al panel. Alguien debe aprobar en la pantalla del panel las acciones remotas de gran impacto; no se pueden aprobar de forma remota. Las capturas de pantalla siguen siendo visibles, pero los toques remotos están desactivados. El ajuste debe activarse por separado en cada panel y no se copia mediante copias de seguridad, restauraciones ni aprovisionamiento de flotas.

### Funciones que necesitan root

Parte del hardware del panel está oculto para las aplicaciones Android comunes y, por tanto, necesita acceso root. La disponibilidad de root depende del firmware del panel, no de ha-paneld. Algunos paneles exponen `su`; en otros, el instalador puede añadir el pequeño asistente root de ha-paneld. El asistente no proporciona un shell de uso general ni acceso ilimitado a archivos.

La interfaz web marca con un candado los controles no disponibles y explica qué le falta al panel. El instalador y los diagnósticos también indican qué nivel de acceso está disponible.

**No necesitan root:** el emparejamiento con Home Assistant, el brillo y la atenuación de la pantalla, los anuncios de audio, ambas opciones de panel de control, la interfaz web, la API REST y la copia de seguridad y restauración de la configuración. Volver, Aplicaciones recientes, la activación mediante un gesto de la mano y la barra de navegación por software dependen de la capacidad correspondiente de Android o del sensor, pero no requieren root de forma inherente.

**Puede que se necesite root o el asistente autenticado:** apagado físico de la retroiluminación, suspensión de Android cuando el perfil la seleccione, control del LED RGB en algunos paneles, control de la aplicación del proveedor, reinicio y regulador de la CPU. Si el perfil activo no dispone de una forma segura de apagar la pantalla por completo, ha-paneld la atenúa en su lugar.

**Sigue siendo necesario usar `su` directamente dentro de ha-paneld para:** bloquear Android en el panel de control, obtener registros completos del sistema, controlar relés cuando lo requiera el perfil y usar la ruta heredada de importación de sesiones de Companion. Una copia de seguridad completa puede incluir un inicio de sesión de Companion existente, que siempre pasa por el asistente autenticado: el protocolo limitado por descriptores es la única ruta, incluso en paneles con root directo.

Existe una [alternativa avanzada](provisioning.md#alternativa-con-shizuku-para-paneles-sin-root) limitada para paneles que realmente no tienen acceso root, pero no forma parte de la vía habitual para hardware compatible ni proporciona funciones de hardware que requieren acceso root.

## Guías y referencia

### Uso de ha-paneld

- [Aprovisionamiento y actualizaciones de flotas](provisioning.md): instalación desatendida, configuración de ADB mediante USB y red, copias de seguridad y actualizaciones de toda la flota.
- [Renderizador integrado](built-in-renderer.md): requisitos, inicio de sesión remoto, selección del panel de control, recuperación y limitaciones deliberadas.
- [Rendimiento](../performance.md): averigua por qué un panel de control es lento y mide el efecto del filtrado de entidades.
- [Brillo adaptativo](../adaptive-brightness.md): selecciona una fuente de luz, comprende el aprendizaje y restablece el historial después de mover un panel.
- [Proximidad adaptativa y activación con un gesto de la mano](../adaptive-proximity.md): configura la detección de proximidad y enseña el gesto de activación.
- [Modos de seguridad](../security-mode.md): comprende el modo relajado y el modo reforzado, incluidas las acciones que requieren la presencia de alguien junto al panel.
- [TTS](../tts.md): genera voz con un motor TTS de Home Assistant y envíala a un panel.

### Desarrollo y ampliación de ha-paneld

- [API HTTP, MQTT y de Home Assistant](../api.md): los endpoints HTTP, las principales familias de entidades MQTT, el emparejamiento y el descubrimiento. La especificación legible por máquinas está disponible en un panel en `/api/v1/openapi.json`.
- [Perfiles de panel](../profiles/): crea, prueba y comparte compatibilidad con otro panel sin volver a compilar la aplicación.
- [Referencias de hardware](../hardware/): configuración específica de cada modelo, sensores, controles, firmware y notas de ingeniería inversa.
- [Compilación desde el código fuente](../building.md) y [desarrollo local](../local-builds.md): compila con Docker, el contenedor de desarrollo o una cadena de herramientas de Android local.
- [Hoja de ruta](../roadmap.md): trabajo previsto. El trabajo completado se registra en el [registro de cambios](../../CHANGELOG.md).

La página `GET /diag` del panel genera un informe de hardware, firmware y capacidades para los informes de errores. Revísalo y elimina los datos confidenciales antes de publicarlo.

## Otras aplicaciones de quiosco

### Fully Kiosk

No recomiendo ejecutar [Fully Kiosk Browser](https://www.fully-kiosk.com/) y ha-paneld juntos. Ambos intentarían gestionar la pantalla, el comportamiento de quiosco y los controles remotos, lo que dejaría dos lugares donde configurar el mismo panel.

<details>
<summary>Por qué no recomiendo ejecutar ambos</summary>

- Fully Kiosk es software comercial de código cerrado. Sus funciones de administración remota requieren una [licencia de pago para cada dispositivo](https://license.fully-kiosk.com/license/single).
- El filtrado de entidades forma parte del renderizador integrado de ha-paneld, por lo que un navegador independiente no puede usarlo.
- Fully Kiosk se configura por separado en cada dispositivo, lo que resulta incómodo cuando varios paneles de distintas marcas deben comportarse de manera uniforme.

Usa una sola aplicación de panel de control en el panel: el renderizador integrado de ha-paneld, Companion o un navegador de quiosco independiente si ofrece algo que los otros dos no.

</details>

### FreeKiosk

[FreeKiosk](https://github.com/RushB-fr/freekiosk) no está relacionado con ha-paneld, a pesar de la similitud de sus nombres. Es gratuito y de código abierto, pero usa React Native y, por tanto, ejecuta otro motor de JavaScript junto con el panel de control de Home Assistant. Esa carga adicional puede ser considerable en paneles con solo 1–2 GB de RAM.

## Chat de la comunidad

No quería configurar un servidor de Discord ni un espacio de trabajo de Slack para un proyecto de una sola persona, así que estoy experimentando con Matrix y Element. Únete a [#ha-paneld:matrix.org](https://matrix.to/#/#ha-paneld:matrix.org) desde tu cliente de Matrix habitual o consúltalo sin una cuenta en [Element Web](https://app.element.io/#/room/#ha-paneld:matrix.org).

No publiques configuraciones ni enlaces a archivos en incidencias o debates de GitHub a menos que aceptes que permanezcan públicos para siempre. La sala de Matrix también es pública y cualquiera puede leerla, pero Matrix admite además mensajes directos privados para los detalles de soporte que no deban formar parte de un registro público permanente. Oculta las credenciales, las URL privadas y los datos personales antes de publicar configuraciones, registros o enlaces a archivos en cualquier lugar.

## ¿Quieres que tu panel sea compatible?

ha-paneld no tiene botón para donaciones. Es gratuito, y el «pago» que realmente lo impulsa es ampliar la compatibilidad con más paneles. Para eso hace falta hardware que estudiar.

Empieza con la [guía de perfiles de ejecución](../profiles/README.md). El perfil Generic puede generar un borrador pasivo que puedes validar, probar y compartir sin compilar la aplicación. Para poder incluir un perfil con ha-paneld, sigo necesitando pruebas del dispositivo real, especialmente de sus botones, ledes, relés y sensores.

Así que, si quieres ayudar:

- **Crea y comparte un perfil.** Abre `http://<panel-ip>:8888/profiles`, descarga el borrador del dispositivo Generic y sigue las guías de [pruebas](../profiles/testing.md) y [uso compartido](../profiles/sharing.md). Un perfil de la comunidad puede ser útil antes de estar listo para incluirse con ha-paneld.
- **Abre una incidencia con los diagnósticos del panel.** Visita `http://<panel-ip>:8888/diag`, revisa el informe y oculta la información confidencial; después, pégalo en una incidencia nueva. Eso basta para empezar. Trabajaré contigo mediante una breve serie de pruebas para cualquier botón, led, relé o sensor que requiera la presencia de alguien ante el panel.
- **Envíame el panel.** Vivo en el Reino Unido y estaré encantado de realizar directamente la ingeniería inversa. Esta es la vía más rápida para conseguir compatibilidad completa con el hardware. Te lo devolveré (ya tengo demasiados); abre primero una incidencia para que podamos acordar los detalles.

El resultado siempre es abierto: tu panel se convierte en un perfil que todo el mundo puede usar. Esa es la donación.

## Desarrollo

Si quieres trabajar en el propio ha-paneld, empieza por [CONTRIBUTING.md](../../CONTRIBUTING.md). La documentación para desarrolladores abarca la [compilación desde el código fuente](../building.md), las [compilaciones locales y en contenedores de desarrollo](../local-builds.md), la [API HTTP y MQTT](../api.md), el [desarrollo de perfiles de panel](../profiles/README.md), el [entorno de pruebas del navegador](../../test/README.md) y el [proceso de publicación](../RELEASING.md).

He proporcionado deliberadamente suficiente información para usar el contenedor de desarrollo suministrado y compilar una versión de prueba local. No envíes solicitudes de cambios ni informes de problemas generados por ordenador sin modificarlos: lee y comprende cada parte del texto y del código propuestos y, después, reescríbelos con tus propias palabras. Este proyecto está a cargo de una sola persona y no tengo tiempo para revisar contenido generado por ordenador sin filtrar. Sé conciso y escribe para personas; si tienes dudas sobre algo, pregunta primero.

<details>
<summary><strong>Pila tecnológica</strong></summary>

- **Aplicación:** [Kotlin](https://github.com/JetBrains/kotlin), [AndroidX](https://github.com/androidx/androidx) y [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines).
- **HTTP y WebSocket de Home Assistant:** [Ktor](https://github.com/ktorio/ktor), con sus módulos CIO de servidor, cliente y WebSocket.
- **MQTT:** [HiveMQ MQTT Client](https://github.com/hivemq/hivemq-mqtt-client), con su cliente MQTT 5 y transporte NIO íntegramente en Java.
- **mDNS:** [JmDNS](https://github.com/jmdns/jmdns), que anuncia `_ha-paneld._tcp` de modo que las instancias de ha-paneld puedan encontrarse entre sí para el selector de varios paneles. ha-paneld informa cuando ese anuncio se detiene y no puede recuperarse.
- **Perfiles en tiempo de ejecución:** [SnakeYAML Engine](https://github.com/snakeyaml/snakeyaml-engine) para YAML 1.2, con [CodeMirror](https://codemirror.net/) y su [paquete de lenguaje YAML](https://github.com/codemirror/lang-yaml) en el editor de perfiles.
- **QR y registro:** [ZXing](https://github.com/zxing/zxing) para los códigos QR de configuración y [SLF4J](https://github.com/qos-ch/slf4j) para el registro de Ktor y HiveMQ mediante Logcat.

La selección y las actualizaciones de dependencias siguen la [política de dependencias y cadena de suministro](../../SECURITY.md#dependency-and-supply-chain-policy) del proyecto.

</details>

## Traducciones

Las traducciones se generan y se contrastan mediante varios servicios y modelos, entre ellos EuroLLM, DeepL y OpenAI. No han sido revisadas sistemáticamente por hablantes de cada idioma, por lo que el texto en inglés sigue siendo la versión de referencia. Si alguna redacción no está clara o es incorrecta, [abre un informe de corrección de traducción](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

## Agradecimientos

Gracias a **Seaky** por [NSPanel Pro Tools](https://github.com/seaky/nspanel_pro_tools_apk), uno de los proyectos que me inspiraron a iniciar ha-paneld. ha-paneld no es una reimplementación de código abierto de NSPanel Pro Tools. Ha evolucionado hasta convertirse en una plataforma para paneles de pared mucho más amplia, con su propio renderizador de paneles de control, filtrado de entidades, perfiles de hardware en tiempo de ejecución, diagnósticos y aprovisionamiento para paneles de varias marcas. Ahora los dos proyectos tienen conjuntos de funciones muy diferentes y no deben considerarse intercambiables, ni siquiera en un panel Sonoff.

## Licencia

Apache-2.0. Consulta [LICENSE](../../LICENSE).
