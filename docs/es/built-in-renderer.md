> [!IMPORTANT]
> Este documento se genera automáticamente y se somete a comprobaciones cruzadas automáticas, pero no ha sido revisado sistemáticamente por hablantes de este idioma. La documentación en inglés es la fuente de referencia. [Consulta la fuente en inglés](../built-in-renderer.md) o [abre una incidencia para corregir la traducción](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# El renderizador integrado de paneles de control

> [!NOTE]
> **Experimental (0.9).** El renderizador integrado es la vía incorporada para filtrar entidades del panel de control. La HA Companion app sigue siendo una opción admitida cuando un panel necesita más de un servidor de Home Assistant, el control por voz de Assist o notificaciones nativas.

ha-paneld puede mostrar el panel de control de Home Assistant en su propia WebView en lugar de transferirlo a una aplicación de panel de control independiente. Esto ayuda al panel a volver a su panel de control con menos demora después de reiniciar la aplicación. Puede volver a abrir el último panel de control que Home Assistant verificó para el mismo servidor, la misma cuenta y la misma configuración de Panel de control de inicio mientras comprueba en segundo plano la lista actual de paneles de control. Si Home Assistant informa de que el panel de control se eliminó o de que cambió el valor predeterminado de la cuenta, el panel pasa a la opción actual.

Una vez que el panel de control está en funcionamiento, ha-paneld puede detectar una conexión bloqueada, liberar la memoria acumulada de WebView y contener los fallos del renderizador. La conexión integrada también permite filtrar entidades del panel de control. El panel sigue siendo un dispositivo de una sola aplicación, con un único APK para instalar, actualizar y aprovisionar.

## Inicio y recuperación

The renderer uses Home Assistant's documented `?external_auth=1` interface, which is the same interface used by the HA Companion app. ha-paneld can therefore tell when the dashboard has connected instead of treating the page as a black box.

- Vuelve a abrir el último panel de control verificado después de reiniciar la aplicación mientras actualiza en segundo plano la lista de paneles de control de Home Assistant. Primero se sigue ejecutando una breve comprobación de compatibilidad. La ruta recordada está vinculada al servidor de Home Assistant, la cuenta y el panel de control configurado, y un panel de control o una pestaña del panel de control configurados explícitamente siguen teniendo prioridad.
- Congela la página mientras la pantalla está apagada y la reanuda al activarse, lo que ahorra aproximadamente un 70% de la CPU del renderizador durante la noche.
- Recarga un panel de control que se abrió pero nunca llegó a conectarse. Los reintentos se ralentizan después de varios fallos y el panel muestra una pantalla clara de **Volviendo a conectar con Home Assistant…** en lugar de una página de error del navegador.
- Automatically retries recoverable checks with increasing delays. A permanently rejected login stops the retry loop and shows Browser sign-in instructions. An unsupported Home Assistant version or incompatible WebView names the required update and waits for it.
- Libera la memoria acumulada mediante recargas invisibles mientras la pantalla está apagada.
- Contains and rate-limits renderer crashes. A page that continues to crash falls back to the admin launcher instead of restarting all night.
- When Home Assistant announces that it is stopping or goes offline through MQTT availability, the panel shows a native notice and clears it only after Home Assistant proves it is back.

You can pull down from the very top edge of the screen to refresh, or pull twice for a full reload. The renderer also supports an optional idle return to the Home dashboard, camera-stream autoplay and private-CA HTTPS using user-installed certificate authorities. **Hide Android system bars** provides an edge-to-edge dashboard; swipe from a screen edge to reveal the bars again. On panels using ha-paneld's software navigation bar, **Dashboard** brings the configured renderer to the foreground without reloading it. **Reload** remains a separate recovery action.

El renderizador ajusta el tamaño del panel de control de la misma forma que la Home Assistant Companion app, por lo que cambiar desde Companion conserva el diseño. **Nivel de zoom (%)** ajusta el resultado; 100% coincide con el valor predeterminado de Companion. El renderizador añade una entrada de **Configuración de la aplicación** a la barra lateral de Home Assistant que abre la página de configuración del panel. En la primera ejecución, oculta la barra lateral acoplada y mantiene activa la conexión durante la inactividad. Puedes seguir abriendo la barra lateral o cambiar estos valores predeterminados más adelante. La opción independiente **Ocultar navegación de Home Assistant (nativa)** solicita a la interfaz que quite su navegación mientras el modo quiosco nativo está activo.

## Requisitos y compatibilidad

A partir de ha-paneld 0.9.6, el renderizador integrado requiere ambas cosas:

- **Home Assistant 2026.4.2 o posterior**; y
- una Android System WebView que admita el agente de escucha seguro de WebMessage utilizado por la interfaz de host nativo de Home Assistant.

La mayoría de los usuarios solo necesitan una Android System WebView actualizada. ha-paneld comprueba la capacidad requerida de WebView y verifica la compatibilidad con Home Assistant antes de cargar el panel de control.

If the panel shows **Home Assistant upgrade required**, upgrade Home Assistant and select **Retry**. Nothing on the panel substitutes for that.

Si muestra **El visor web de este panel es demasiado antiguo**, la pantalla indica qué puede hacer al respecto este panel concreto, ya que depende del modelo y de cómo esté configurado el panel:

- **The panel can repair itself.** When a known-good Android System WebView is pinned in the panel profile and ha-paneld is permitted to install it, the screen offers **Update the web viewer**. Select it and the panel downloads and installs that version, then ha-paneld restarts once to use it. If the screen comes back afterwards, the pinned version did not resolve the fault and the manual routes below still apply.
- **The panel cannot, and the screen says why.** Once ha-paneld has confirmed that automatic repair is unavailable, it names one of three reasons, and the update has to be done by hand, after which you select **Retry**: a known-good version is pinned but ha-paneld is not permitted to install it; no known-good version is pinned for this panel; or the panel takes its Android System WebView from a store, which will replace it more safely than ha-paneld would. Reinstalling the same version repairs a damaged one.

How Android System WebView is updated by hand depends on the panel: some take it from Google Play, others only from a vendor firmware update or a manually installed build.

The built-in renderer does not fall back to the older, less isolated bridge. Another renderer may help when Home Assistant itself cannot be upgraded. The Companion app uses the same system WebView, so it cannot bypass an obsolete WebView on the panel.

## Activación

On a new or reset panel, open `http://<panel>:8888/setup` from a laptop or phone, or select **Set up** on the panel itself. The guided journey chooses the renderer, signs in to Home Assistant, selects the account default, a dashboard or a specific dashboard tab, and asks about the entity filter before the first dashboard load. Authorization happens in the administrator's browser, so credentials do not need to be typed on the panel.

En un panel existente, abra la página `:8888` **Configurar** del panel. En **Conexión con Home Assistant**, introduzca la URL de Home Assistant y elija **Inicio de sesión en el navegador**; después, seleccione **Renderizador integrado** como Aplicación del panel de control.

Existing rooted installations that already imported a signed-in Companion session remain supported as a compatibility path. New installations should use Browser sign-in.

Para realizar una configuración desatendida desde un equipo de administración, sustituya la dirección de ejemplo del panel y los datos de Home Assistant en este comando que no requiere clonar el repositorio (consulte [Aprovisionamiento](provisioning.md)):

```bash
# First create an owner-only password file as shown in the linked provisioning guide.
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | \
  bash -s -- --provision 192.168.1.50:5555 --builtin \
  --ha-url https://homeassistant.example.com --ha-user your-user --ha-pass-file ha-password.txt
```

The password never reaches the panel because the login happens on your machine. The panel holds a revocable refresh token. A long-lived access token works too: `--ha-token-file ha-token.txt` instead of `--ha-user/--ha-pass-file`. See [Provisioning and fleet updates](provisioning.md) for securely creating credential files and the trusted-LAN transport boundary. Literal `--ha-pass` and `--ha-token` values remain compatibility options, but expose the value in the original shell command and process list.

For automated provisioning, a token or username/password flow remains available as an advanced fallback. Interactive installations should use Browser sign-in.

## Apariencia del panel de control frente al bloqueo de Android

La sección avanzada de Configurar ofrece tres controles independientes. Cada uno afecta a una capa diferente:

| Opción de Configurar | Qué cambia | Qué **no** cambia |
|---|---|---|
| **Ocultar navegación de Home Assistant (nativa)** (activada de forma predeterminada) | After Home Assistant connects, asks its native frontend to hide its navigation. Built-in renderer only. | Does not lock Android, hide Android system bars, or inject or modify dashboard CSS. If Home Assistant rejects or does not support the command, the dashboard is left unchanged. |
| **Ocultar barras del sistema Android** (activado de forma predeterminada) | Hides Android's status and navigation bars for an edge-to-edge dashboard. Swipe from an edge to reveal them. Built-in renderer only. | Does not prevent someone leaving the app and does not hide Home Assistant's own menus/navigation. |
| **Bloquear Android en el panel de control (experimental)** (desactivado de forma predeterminada) | With root, hides Android system bars and returns to the selected dashboard within about three seconds when another app or Recents opens. This is a casual-use deterrent, not an adversarial security boundary. | Does not change the Home Assistant dashboard appearance. It has no effect without root. Reboot provides a 60-second unlocked recovery window, then the saved lock is reasserted. |

For a cleaner dashboard, start with **Hide Home Assistant navigation (native)** and/or **Hide Android system bars**. Enable **Lock Android to dashboard** only when discouraging casual escape from the app is required and you have tested the documented release routes: Configure, the Home Assistant switch, adb, seven rapid taps in the top-left corner, or the unlocked window after reboot.

## Filtro experimental de entidades

> [!WARNING]
> This is an opt-in tester feature. Automatic learning cannot prove every custom-card or dynamic-template dependency, and an incomplete entity set can leave cards missing or stale. Review it on a non-critical panel first and keep the filter-disable rollback available.

The filter applies only to ha-paneld's built-in renderer. It changes the frontend's Home Assistant subscription, so Home Assistant filters the states before serializing and sending them to the panel. The Companion app and other dashboard applications are unaffected.

### Flujo de trabajo automático

1. In `:8888` open **Configure → Dashboard**, select **Built-in renderer**, then enable **Entity filtering**.
2. Open the **Entities** tab and select **Scan dashboard now**.
3. Visit every dashboard tab and use its controls, pop-ups and conditional content so ha-paneld can observe runtime dependencies.
4. Review the current, suggested and excluded lists. Pin entities used indirectly by custom cards or templates, and resolve any entity-filter checks shown above the tables.
5. Select **Apply policy set** when the candidate is ready. ha-paneld shows the old and new entity counts before asking for confirmation, then reloads the dashboard with the filtered subscription.

The Entities page explains why each entity was found, records manual pin and exclusion choices, and keeps recognized broad or dynamic rules visible until the user fixes them or explicitly chooses how to proceed. Unrecognized behavior can still exist, so test every dashboard tab after activation. If anything is missing, turn off **Entity filtering** in Configure and reload before revising the candidate.

### Cuando el panel mantiene el panel de control en espera

With automatic filtering on, the built-in renderer never opens Home Assistant unfiltered. Until a scan has produced a set it can vouch for, the panel shows a native hold screen instead of the dashboard, and the hold has three distinct causes. While the first scan is running or has failed, the panel retries it on a widening schedule, because the usual reason is that Home Assistant is not up yet. If Home Assistant rejects the panel's credential, the hold names the sign-in. If the scan finished and found a rule it cannot bound, such as a strategy-generated dashboard or an unbounded selector, the hold asks for a decision: ignore the flagged rules and continue, turn the filter off, or review them on the Entities page. That decision can be made at the panel or from any device on the network at `http://<panel>:8888/entities`, and the hold screen shows that address.

A hold that is waiting on a decision is settled, so the panel does not rescan the catalogue while it waits. It asks Home Assistant whether the dashboard changed, five minutes after the hold settles and then at most hourly, and rescans only when the dashboard's configuration or the account default has actually changed, when the decision is made, or when the panel's Home Assistant settings change. `GET /api/v1/dashboard/entities/sync` reports the cause in `hold_reason` (`synchronizing`, `synchronization`, `authentication` or `decision`) and sets `resync_suspended` while a decision is the only thing outstanding.

An update can force the panel to re-check a dashboard it was already filtering. When that re-check flags a rule on a dashboard the panel had already been running a filter on, the panel records the rule as ignored, restores the entity set it was running, and opens the dashboard rather than hold it; the rule stays visible on the Entities page and can be re-enabled there. This applies only to the re-check an update forces, only when a previously accepted filter exists, and only when the restored set is not empty. Rules the panel can never ignore, such as a dashboard too large to diagnose, still hold the renderer.

### Plantillas y anclajes manuales

ha-paneld does not run dashboard templates, so it cannot know which entities a template returns, and it does not guess. What it does with the two kinds of entity a template touches is deliberately different.

Entities a template only **reads**, such as a state tested as a condition, need nothing. Home Assistant renders the template itself and sends the panel the result, over a separate subscription the entity filter does not touch. Those entities are supposed to be absent from the lists on the Entities page, and adding them would only make the subscription larger for no benefit.

Entities a template **returns** are different. They become cards on the dashboard, which read their state through the filtered subscription, so they do have to be in it. ha-paneld cannot discover them without running the template, and choosing to continue past an entity-discovery check does not add them either; that choice only lets automatic updates carry on without them.

To add one, type any part of its name or ID into the search box at the top of the Entities page. The search covers the complete Home Assistant catalogue rather than only the entities already found, and reports how many matches each table holds. Set every entity you need to **Pinned**. A manual pin is kept until you remove it, including across dashboard changes and rescans.

### Restablecer datos aprendidos

Use **Reset learned data** on the Entities page when obsolete dashboard evidence or earlier manual decisions make the candidate misleading. After explicit confirmation it clears learned dashboard membership and evidence, manual pin/exclude overrides, and ignored safety decisions. It preserves the known-good active filter, keeps the Home Assistant catalog used for candidate names, and starts a replacement scan when learning is enabled. This makes reset a rebuild operation rather than an immediate expansion back to the full Home Assistant state stream.

The stronger API reset below can also remove the stored active filter by sending `clear_filter:true`. Use it only when the filter itself must be discarded.

### Lista exacta manual

Advanced testers can bypass automatic learning and supply an exact list through the API. Create a JSON file containing every entity required by every dashboard tab, including entities referenced indirectly by custom cards or templates:

```json
{
  "enabled": true,
  "entity_ids": [
    "binary_sensor.front_door",
    "climate.living_room",
    "light.kitchen"
  ]
}
```

Carga la lista completa en el panel:

```bash
PANEL_IP=192.0.2.10
curl --fail --show-error \
  --header 'Content-Type: application/json' \
  --data @entity-filter.json \
  "http://${PANEL_IP}:8888/api/v1/dashboard/entity-filter"
```

El renderizador integrado se vuelve a cargar después de una actualización. Una vez que el panel de control se haya vuelto a conectar, consulta el estado:

```bash
curl --fail --show-error \
  "http://${PANEL_IP}:8888/api/v1/dashboard/entity-filter"
```

A working filtered connection reports `enabled: true`, `runtime.active: true`, `runtime.mode: "native_socket"`, at least one `modifiedSubscriptions`, and zero `failures` and `directFallbacks`. A fallback means the dashboard remains connected but is receiving the ordinary unfiltered stream.

Posting `entity_ids` replaces the complete list. Keep your source JSON because the status endpoint deliberately returns only the count and a stable hash, and config exports do not include the entity IDs.

Disable filtering while retaining the stored list:

```bash
curl --fail --show-error \
  --header 'Content-Type: application/json' \
  --data '{"enabled":false}' \
  "http://${PANEL_IP}:8888/api/v1/dashboard/entity-filter"
```

Remove the stored filter, manual overrides, ignored safety decisions and rebuildable learning evidence with the confirmation-gated reset:

```bash
curl --fail --show-error \
  --header 'Content-Type: application/json' \
  --data '{"confirm":true,"clear_filter":true}' \
  "http://${PANEL_IP}:8888/api/v1/dashboard/entities/reset"
```

Like the rest of ha-paneld's control API, this endpoint is unauthenticated and intended for use on a trusted LAN.

## Temas

**Tema del panel de control** (Configurar → Renderizador integrado) determina quién elige el modo claro u oscuro:

- **Follow Home Assistant** (la opción predeterminada) deja la elección en manos de Home Assistant. El panel solo proporciona un punto de partida: en Android 13+, este sigue en tiempo real el ajuste del sistema; en Android 10-12, sigue el ajuste del sistema cuando se carga el panel de control; y en Android 9 y versiones anteriores, lo establece el interruptor «Modo oscuro» (Configurar → Pantalla). Un tema elegido en Home Assistant prevalece sobre ese punto de partida.
- **Dark** y **Light** hacen que el panel elija. Esto está pensado para un panel de control de quiosco con la barra lateral oculta, donde no se puede acceder en absoluto a la página de perfil de Home Assistant desde el panel.

Forzar un tema solo cambia la parte clara/oscura de la elección. Los temas con nombre y sus colores se mantienen exactamente como están; al volver a Follow Home Assistant, la parte clara/oscura recupera el valor que tenía antes o Auto si no había ninguno. El panel nunca cambia el tema almacenado en tu *cuenta* de Home Assistant, por lo que un panel configurado como Dark no puede oscurecer tu teléfono.

Hay un caso en el que no puede imponerse: si este usuario de Home Assistant ha elegido explícitamente Claro u Oscuro (en lugar de Auto), esa elección sigue prevaleciendo, ya que anularla supondría cambiar un ajuste compartido con todos los demás dispositivos en los que inicie sesión ese usuario. Configura el tema del usuario como Auto o utiliza otro usuario de Home Assistant para el panel; así se aplicará la elección del panel. Cuando esto ocurre, el panel lo indica en lugar de permanecer en silencio: la tarjeta **Diagnóstico en tiempo de ejecución** de las páginas de `:8888` señala que el tema de Home Assistant está anulando el Tema del panel de control, y `GET /api/v1/status` lo muestra en `renderer` como `theme_overridden: true`, junto con `theme_policy` y `theme_effective`, e indica la solución en `action`.

La interfaz web de `:8888` es independiente de todo esto y siempre sigue al navegador en el que la estás viendo.

## Reversión

Open Configure, select an installed Home Assistant Companion app under **Dashboard app** and save the change. The switch takes effect immediately. Do not select **Auto** for this purpose because Auto uses the built-in renderer when it is ready.

## Limitaciones

- **No support for more than one Home Assistant server, Assist voice control or native notifications.** Keep the HA Companion on the panel where those matter.
- **No incluye funciones multimedia adicionales a pantalla completa**, como un selector de archivos o reproducción al estilo de la transmisión de contenido. Estas funciones quedan permanentemente fuera del ámbito; utiliza Companion cuando las necesites.
- A **current system WebView** is still required to render the Home Assistant frontend. ha-paneld can install a known-good WebView on supported rooted panels; an obsolete WebView produces a health warning in the `:8888` interface.
- Browser sign-in and advanced non-interactive provisioning work without root. Legacy Companion-session import requires root and remains only for existing installations.
