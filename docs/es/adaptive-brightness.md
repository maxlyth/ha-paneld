> [!IMPORTANT]
> Este documento se genera automáticamente y se somete a comprobaciones cruzadas automáticas, pero no ha sido revisado sistemáticamente por hablantes de este idioma. La documentación en inglés es la fuente de referencia. [Consulta la fuente en inglés](../adaptive-brightness.md) o [abre una incidencia para corregir la traducción](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Brillo adaptativo

El brillo adaptativo es un controlador opcional integrado en el panel que aprende el patrón habitual de luz ambiental alrededor del panel y ajusta la pantalla sin necesidad de una automatización de Home Assistant. Está desactivado de forma predeterminada y, mientras lo esté, seguirá disponible el control habitual del brillo, ya sea manual o mediante Home Assistant.

## Elegir la fuente de luz

Abre **Configurar → Pantalla** y elige **Fuente de luz ambiental**:

- Déjalo en blanco para usar el sensor de luz del propio panel cuando el perfil activo y la comprobación de capacidades en tiempo real indiquen que hay uno disponible.
- Selecciona una entidad de iluminancia de Home Assistant cuando el panel no tenga un sensor local adecuado o cuando un sensor instalado en la habitación represente mejor la luz que percibe el usuario.

The Home Assistant source uses one exact authenticated entity subscription rather than the full state stream. If neither source is available, ha-paneld leaves adaptive control unavailable instead of guessing from time alone.

When a Home Assistant illuminance source is selected, ha-paneld can seed the on-panel pattern with up to seven days of its existing Home Assistant history. This gives automatic brightness a useful starting point instead of waiting for fresh readings to accumulate. It depends on the source being recorded and the Home Assistant history service being available; if no usable history is returned, learning simply starts from new readings.

## Activarlo y ajustarlo

Enable **Auto-brightness** in the same Display card. The controller retains up to seven days of bounded, on-panel ambient history and learns the normal pattern for the time of day. Short positive deviations, such as a room light being switched on, can raise the proposed level above that baseline.

**Minimum level** sets the lowest level proposed by automatic control and rescales the learned range from that floor to full brightness. It ranges from 4% to 99%. The 4% floor is where the backlight's own never-blank minimum sits, so anything lower would move the control without changing the screen. It does not limit manual brightness, which can still be set lower.

**Sensitivity** is the percentage of a difference from the learned pattern that is applied to the screen, once the controller has decided the difference is real. At 0% it ignores the difference and uses the learned pattern alone. Lower values make the response steadier, and the default of 50% leaves equal room to tune in either direction. It is not a raw follow-the-light control: a brief brightening is only acted on once it has been sustained or risen sharply enough to be admitted, and while the daily pattern is still being learned the screen follows the measured light regardless of this setting. The seven-day chart previews the observed range, learned baseline and proposed level before or while the controller is active. Unsaved Minimum level and Sensitivity changes are reflected in the preview without rewriting stored ambient history.

The history is tied to the selected source and a coarse room/time context derived from the configured Home Assistant location and timezone. A material location, timezone or source change starts a separate history rather than silently applying evidence learned for another room context.

## Cambios manuales y recuperación

A manual brightness change records a four-hour temporary preference, so the learner does not immediately fight the user. Subsequent automatic changes retain 20% influence at first, then regain full authority through a smooth four-hour fade. Select **Resume full auto** in the adaptive-brightness panel to end that preference immediately instead of waiting for the fade to complete.

Select **Reset learned history** after moving the panel, replacing its light source or when the retained week no longer represents the room. The confirmation deletes the seven-day ambient history for the current source and room/time context, then restarts learning; it does not change the selected source or the Auto-brightness setting.

Adaptive brightness uses Android's normal screen-brightness path and does not require root. Reading a particular panel sensor can still depend on that sensor's hardware access path; the active profile and live capability checks remain authoritative.

## Control desde Home Assistant

When exposed, `switch.<panel>_auto_brightness` enables or disables the same on-panel controller. Screen brightness remains `light.<panel>_screen`. A manual brightness command can therefore create the same temporary preference as a local change; use the Configure page's **Resume full auto** action to return immediately to the learned target.
