> [!IMPORTANT]
> Este documento se genera automáticamente y se somete a comprobaciones cruzadas automáticas, pero no ha sido revisado sistemáticamente por hablantes de este idioma. La documentación en inglés es la fuente de referencia. [Consulta la fuente en inglés](../performance.md) o [abre una incidencia para corregir la traducción](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Optimización del rendimiento de los paneles murales de Home Assistant

Esta guía explica por qué un panel mural Android económico puede responder con lentitud, dar tirones al desplazarse, quedarse en blanco o dejar de actualizarse aunque el mismo panel de control funcione bien en un teléfono o equipo de escritorio. Empiece por el trabajo que Home Assistant envía al panel y, después, mida las limitaciones restantes del panel de control y del hardware en lugar de suponer que debe sustituir el panel.

> [!TIP]
> Una causa habitual es la cantidad de estados y actualizaciones de entidades de Home Assistant que llegan al panel de control. Una instalación grande puede hacer que un panel de baja potencia procese miles de entidades que nunca muestra. Con el renderizador integrado de ha-paneld, la primera solución que debe probar es el filtrado de entidades.

## Mida antes de cambiar nada

Abra la página web del panel en `http://<panel-ip>:8888/` o use el enlace **Visitar** de la página del dispositivo en Home Assistant. Registre la misma vista del panel de control durante un periodo similar antes y después de cada cambio para que la comparación sea significativa.

- **Capacidad de respuesta del panel de control** — latencia real de interacción, desglose entre entrada, controlador y presentación de la interacción más lenta, bloqueo del hilo principal, tiempo hasta que se vuelve interactivo y recargas inesperadas del renderizador.
- **Flujo de estados de Home Assistant** — actualizaciones de estado por segundo, tasa de carga útil JSON sin comprimir, tamaño de la carga inicial, tiempo hasta que el hilo principal cede y las tres entidades con mayor contribución durante la última hora, clasificadas tanto por tasa de actualización como por volumen de carga útil.
- **Rendimiento** y **Procesos principales** — CPU, GPU, RAM, velocidad de reloj, temperatura y los procesos con mayor actividad.
- **Transmisión de la cámara** (solo en un panel cuyo perfil declare una cámara): los suscriptores que mantienen abierta la sesión, el codificador en uso, la frecuencia de fotogramas solicitada junto a la entregada y la tasa de bits entregada junto al límite de tasa de bits del codificador. Una frecuencia muy inferior a la solicitada se indica como tal, sin atribuirle una causa: el ritmo de captura en una habitación poco iluminada, la ruta de codificación y la contención por el hardware de vídeo son posibles causas, y la tarjeta no intenta determinar cuál de ellas es. La solicitud nunca se reduce de forma silenciosa. Cuando la sesión no puede proporcionar a una transmisión la frecuencia solicitada, la tarjeta muestra la solicitud junto a la frecuencia asignada al codificador, en lugar de modificar el objetivo para ajustarlo. La frecuencia de captura la establece quien abre la cámara, por lo que una transmisión que se une a una sesión abierta por una instantánea se codifica con el valor predeterminado configurado. No muestra deliberadamente ninguna cifra de CPU, porque la codificación por hardware se ejecuta tanto en el hardware del códec y el compositor como en la aplicación, y ningún proceso por sí solo representa el coste de la cámara. Consulte la carga total del panel en Rendimiento y Procesos principales.
- **Página Entidades** — a qué está suscrito actualmente el renderizador integrado, qué encontró el aprendizaje automático y qué reglas del panel de control aún necesitan una decisión.
- **Volcado de diagnósticos** (`/diag`) — un informe que puede copiar y pegar en un informe de errores cuando la causa aún no está clara.

El renderizador integrado recopila directamente estas mediciones del navegador y no necesita root ni depuración de WebView. La aplicación Companion sigue siendo un modo de compatibilidad: ha-paneld puede mostrar la CPU del renderizador e indicadores indirectos del renderizado de Android cuando root o el asistente están disponibles, pero no puede afirmar que se trate de la latencia de interacción real de Companion.

El valor del hilo principal para un evento de estado comienza cuando se distribuye un mensaje relevante de Home Assistant y termina cuando el navegador cede después de todos los controladores y las microtareas. Incluye el pequeño coste del observador de ha-paneld y cualquier otro trabajo de la tarea de ese mensaje, por lo que deliberadamente no se etiqueta como tiempo de procesamiento exclusivo de Home Assistant. La tasa de carga útil corresponde al JSON sin comprimir de la aplicación que procesa la interfaz, no al tráfico de red comprimido.

Use el gráfico alineado para buscar correlaciones. Los picos de interacción que coinciden con un tráfico de estados elevado y una alta ocupación por eventos de estado apuntan al flujo masivo de datos. Un tiempo elevado de los controladores con un flujo tranquilo apunta al JavaScript del panel de control. Los retrasos de presentación y los fotogramas de renderizado prolongados apuntan a tareas de diseño, animación, cámara o contenido multimedia. Las recargas repetidas del renderizador apuntan a problemas de memoria o inestabilidad del renderizador. Interprete las mediciones conjuntamente; una única instantánea de la CPU no permite identificar por sí sola la causa.

La fila **Causa probable** aplica automáticamente estas reglas conservadoras e indica su nivel de confianza. Indica deliberadamente que no hay una causa dominante clara cuando las pruebas no permiten determinarla.

### Comparación A/B externa con y sin filtrado

Si el panel de control sin filtrar sobrecarga su propio WebView, los valores de renderizado de la página pueden dejar de cambiar. En su lugar, el recopilador del lado del host consulta periódicamente la API de rendimiento del servicio de ha-paneld, por lo que la CPU de todo el panel sigue siendo observable y, cuando root o el asistente están disponibles, también lo es la sonda nativa del hilo del renderizador de Chromium. También registra la antigüedad del último lote de telemetría del navegador; un valor superior a 15 segundos se notifica como detenido en lugar de tratar silenciosamente como actual una muestra antigua del navegador.

The collector is read-only with respect to user configuration. It never enables, disables or rewrites the entity filter; the first binding request may create one private installation key in ha-paneld's internal state. Select the same dashboard view and workload for both arms, change the filter through the panel UI, wait for the dashboard reload, then run one command from a repository checkout for each state:

```bash
python3 scripts/measure-dashboard-performance.py collect --panel http://192.168.1.50:8888 --expect filtered --label filtered --output filtered.json
```

```bash
python3 scripts/measure-dashboard-performance.py collect --panel http://192.168.1.50:8888 --expect unfiltered --label unfiltered --pair-with filtered.json --output unfiltered.json
```

Compare los dos resultados acotados y legibles por máquina:

```bash
python3 scripts/measure-dashboard-performance.py compare filtered.json unfiltered.json --output comparison.json
```

Each arm defaults to three minutes with a 30-second warm-up and 10-second polling. `--pair-with` reuses an opaque comparison id from the first result, allowing the comparison to reject a different physical panel, configured Home Assistant/dashboard target or measurement-relevant setting without recording those private values. It cannot detect a different view selected manually within the same configured dashboard, so keep the visible view and workload unchanged. The collector rejects the arm if the expected mode is not active, the filter revision or renderer generation changes, the build/configuration changes during collection, filtering falls back, or it retains fewer than three samples, fewer than three whole-panel CPU samples or fewer than three native renderer-main CPU samples. It still writes the invalid result and the exact validation reasons. Existing output files are never overwritten.

The JSON intentionally omits the panel URL and id, Home Assistant URL and dashboard path, entity ids, process names and filter hash. The panel computes the opaque fingerprints with a private random 256-bit installation key that never leaves the panel; fingerprints are unique to that comparison pair, so separate measurements cannot be correlated through them or used to guess room-style panel names. A panel that cannot persist this key refuses the measurement binding. The unfiltered entity count is the last synchronized catalog-backed count, not a live count recovered from the overloaded WebView; it is reported as unavailable when the catalog is empty. Network and browser traffic counters are normalized to their actual observed intervals, so a tolerated missed poll cannot skew the comparison. Browser-derived state/render timing and browser traffic rates are omitted from summaries when stale, while service-side CPU, memory, network and renderer-main values continue to be collected.

### Depuración remota opcional de WebView

Las tarjetas de rendimiento integradas normales no requieren DevTools. Use la tarjeta **Depuración remota de WebView** solo cuando necesite una inspección más profunda a nivel del código fuente.

For the Companion app, first enable **Companion → Settings → Troubleshooting → WebView remote debugging**, then relaunch the dashboard. This setting is easy to miss: without it, the relay cannot discover the Companion WebView. The LAN relay itself also requires root.

### Descarte el defecto heredado del supervisor de Zigbee del firmware de fábrica de NSPanel Pro

Legacy stock firmware containing a recursive `export LD_LIBRARY_PATH=/vendor/bin/siliconlabs_host/:${LD_LIBRARY_PATH}` assignment can make the vendor's `guard_process.sh` the performance problem itself. A [community investigation](https://github.com/maxlyth/ha-paneld/issues/34) confirmed the defect on an NSPanel Pro 120 running stock 3.8.0 and reported the condition across all 16 panels in that fleet. The script prepends its directory every five seconds; after roughly ten hours in that setup the environment string crosses Linux's per-string execution limit, external commands start failing with `E2BIG`, `sleep` stops delaying the loop and the watchdog can pin one CPU core. At that point it can also fail to restart a dead `zgateway`, leaving Zigbee unavailable. Broader exposure across legacy 1.x–3.x firmware is plausible where the same line exists, but has not been independently verified by the ha-paneld project.

Busque este patrón cuando ha-paneld indique una carga periódica del sistema en un NSPanel Pro con el firmware de fábrica que, por lo demás, esté inactivo:

- `guard_process.sh` stays near 100% of one CPU core and its process size grows far above the reported healthy value of about 9 MB;
- `zgateway` is absent or no longer recovers; and
- rebooting helps, but the load returns around ten hours later in the reported stock setup.

A reboot only resets the accumulating environment temporarily. Issue #34 contains a reporter-provided root/ADB workaround, but the project has not yet independently validated that mutation and recovery sequence. Do not apply it unless the exact recursive assignment is present once in the vendor-native script. Any repair must first verify a non-empty backup and preserve ownership, mode and SELinux metadata. Abort before mutation on an unexpected match; after mutation, roll back if restart or verification fails, return `/vendor` to read-only, and verify both `zgateway` and its availability topic. A firmware update that rewrites `/vendor` removes the local patch; the defect returns only if the target firmware still contains the vulnerable assignment. Community inspection of firmware 4.0.12 and 4.6.0 did not find it.

This watchdog defect is distinct from a stable `zgateway` busy-looping against an unresponsive radio. ha-paneld's Zigbee health sensor distinguishes the guard and gateway CPU, join evidence and restart history. It warns about the exact recursive assignment but does not edit vendor scripts automatically. A configured, explicitly unjoined gateway that remains above 50% of one core for five minute-level samples after its 15-minute grace is automatically switched OFF and contained; a joined high-CPU router is warning-only.

## Soluciones, por orden de impacto

### 1. Filtre la suscripción a entidades del renderizador integrado

Home Assistant's frontend normally subscribes to the state of every entity visible to the signed-in user. The panel must receive and process those states even when its dashboard uses only a small subset. ha-paneld's built-in renderer can add the dashboard's learned entity set to that native subscription, so Home Assistant filters the stream before serializing and sending it. The panel keeps its ordinary authenticated Home Assistant connection; no proxy or additional server is involved.

The automatic filter is opt-in and applies only to the built-in renderer:

1. In `:8888` open **Configure → Dashboard**, select **Built-in renderer (ha-paneld)**, then enable **Entity filtering**.
2. Open the **Entities** tab and select **Scan dashboard now**.
3. Visit every dashboard tab and exercise controls, pop-ups and conditional content so runtime dependencies have a chance to be observed.
4. Review the current, suggested and excluded entities. Pin anything required indirectly by a custom card or template.
5. Resolve any entity-filter checks. Narrow a broad or dynamic dashboard rule where practical, or make an explicit choice while accepting the warning shown by the panel.
6. Apply the policy-selected set, let the dashboard reload and compare the same views using the performance cards on the Dashboard tab.

Automatic learning cannot prove every custom card or dynamic template dependency. A missing entity may leave a card stale or unavailable, so review the result on a non-critical panel first and keep filtering disabled until the candidate is credible. The previous subscription remains available as the safe rollback: turn off **Entity filtering** and reload the dashboard.

If old learned evidence or manual choices no longer describe the dashboard, use **Reset learned data** on the Entities page. The confirmed reset clears learned membership, pins/exclusions and ignored safety decisions, preserves the known-good active filter and starts a replacement scan. The filter therefore stays as the rollback boundary while the candidate is rebuilt; use the stronger API reset documented below only when the stored filter itself must also be removed.

Advanced testers can supply and inspect an exact list through the API. The UI workflow, manual API format, runtime status and rollback commands are documented in [The built-in dashboard renderer](built-in-renderer.md#filtro-experimental-de-entidades).

### 2. Aligere el propio panel de control

- Divida un panel de control denso en vistas específicas y evite montar tarjetas que el panel nunca necesite.
- Prefiera las tarjetas integradas cuando una tarjeta personalizada se anime continuamente, cree un árbol de documentos grande o ejecute tareas frecuentes de JavaScript.
- Watch interaction processing, long animation frames and renderer reloads. If one view repeatedly dominates them, simplify it or use `button.<panel>_reload` as a temporary recovery path while finding the expensive card.
- Pruebe por separado las tarjetas de cámara y de gráfico. El coste de su decodificación, sus consultas al historial y su renderizado puede predominar incluso cuando el filtrado de entidades funciona correctamente.

### 3. Reduzca las actualizaciones innecesarias en el origen

Entity filtering protects the panel from unrelated entities, but it does not make a required entity cheaper. If a dashboard really displays a power meter, BLE distance sensor, rapidly changing template or noisy diagnostic entity, reduce that source's update rate where the integration supports it. This can also reduce recorder and database work for the whole Home Assistant installation.

Useful controls include ESPHome throttling or delta filters, Zigbee reporting intervals, integration `scan_interval` settings and less frequent template updates. Confirm the change does not make an automation or history view less useful before applying it globally.

### 4. Adapte el panel de control restante al hardware

Los paneles PX30 y rk3566 pueden ejecutar bien un panel de control específico, pero siguen teniendo un rendimiento limitado en un solo hilo y normalmente solo 2 GB de RAM. El filtrado de entidades elimina el procesamiento de estados innecesario; no puede eliminar el coste de un flujo de cámara sobredimensionado, una animación compleja o un gráfico del historial muy grande. Diseñe para el tamaño lógico de la pantalla del panel y pruebe la vista más exigente en lugar de evaluar únicamente la pestaña de inicio.

## Lista de comprobación

- [ ] The built-in renderer is selected where Assist voice control and native notifications are not required
- [ ] Automatic dashboard entity filtering is enabled, scanned, reviewed and explicitly applied
- [ ] Every dashboard tab, pop-up and conditional path has been exercised during learning
- [ ] Custom-card and template dependencies are pinned or otherwise accounted for
- [ ] Entity-filter checks have been resolved deliberately rather than ignored accidentally
- [ ] The same views have been compared before and after filtering using the performance cards on the Dashboard tab
- [ ] If the page itself stalls unfiltered, the two arms have been collected and validated with the out-of-band measurement script
- [ ] Las tarjetas pesadas, las transmisiones de cámaras y los gráficos se han probado por separado
- [ ] Required high-frequency entities have been tuned at the source where appropriate
- [ ] If the panel uses a legacy stock NSPanel Pro Zigbee stack, its `guard_process.sh` has been checked
- [ ] A reload and filter-disable recovery path has been verified

## Lo que sustituyó el filtro integrado

Before ha-paneld could filter its own subscription, one deployment used a dedicated dashboard-only Home Assistant instance fed through the `remote_homeassistant` integration. It reduced the panel-facing feed from about 3,410 entities and 7.3 updates per second to about 310 entities and 0.77 updates per second, making the dashboards usable. It also required a second Home Assistant installation, bridged entities, separate configuration, authentication, updates, backups, monitoring and another failure path.

The built-in filter addresses that panel-load problem inside ha-paneld, so the split-instance system is no longer needed or maintained in that deployment and is not recommended for built-in-renderer users. This comparison remains here to show the amount of infrastructure the integrated solution replaces. Users who must retain the Companion app or another renderer cannot use ha-paneld's filter on that renderer; source-side tuning still applies, while any external filtering arrangement remains outside ha-paneld's supported setup.

---

## Referencia

### Por qué una instalación grande de Home Assistant puede ralentizar un panel

El frontend de Home Assistant mantiene una suscripción WebSocket que contiene el estado actual y las actualizaciones posteriores de las entidades disponibles para el usuario. Sin un conjunto restringido de entidades, el renderizador recibe muchos más datos de los que suele utilizar un panel de control mural específico. Su hilo principal de JavaScript debe analizar los mensajes, actualizar el modelo de estados del frontend y decidir si ha cambiado algo visible.

Los paneles de bajo coste son especialmente sensibles porque la ejecución de JavaScript, la disposición y el pintado dependen en gran medida de un único hilo del renderizador. Los núcleos de CPU adicionales ayudan con otras tareas, pero no eliminan esa latencia, y la RAM limitada hace que la recolección de basura resulte cada vez más perjudicial a medida que crece el heap de WebView.

### Síntomas comunes

| Síntoma | Causa probable |
| --- | --- |
| Toques con retraso, navegación lenta o desplazamiento entrecortado | trabajo del hilo principal del renderizador debido a un gran flujo de entidades, tarjetas pesadas o ambos |
| Una vista del panel de control es mucho peor que las demás | tarjetas exigentes, decodificación de cámaras, datos del historial o un árbol de documentos grande en esa vista |
| Toda la vista queda en blanco mientras la interfaz externa permanece visible | presión sobre el montón de WebView o fallo del renderizador |
| El panel de control se degrada gradualmente a lo largo de varios días | crecimiento del montón, fragmentación de la memoria o una tarjeta que acumula trabajo |
| Las entidades dejan de actualizarse y después llegan de golpe | interrupción de WebSocket, reconexión o un renderizador incapaz de mantener el ritmo |
| Los paneles similares se comportan de manera diferente | contenido distinto del panel de control, suscripción de entidades, versión de WebView, tiempo de actividad o estado térmico |
| Legacy stock NSPanel Pro becomes janky around ten hours after boot and `guard_process.sh` uses one core | check for the recursive vendor Zigbee-watchdog assignment leading to `E2BIG` |

La recolección de basura pausa periódicamente JavaScript para recuperar la memoria que ya no se utiliza. Cerca del límite del montón, esas pausas se vuelven más largas y frecuentes, lo que puede privar de recursos al renderizado incluso cuando el propio proceso de Android no se ha cerrado inesperadamente.
