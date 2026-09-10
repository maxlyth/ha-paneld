> [!IMPORTANT]
> Questo documento è generato automaticamente e verificato mediante controlli incrociati automatici, ma non è stato rivisto sistematicamente da persone che parlano questa lingua. La documentazione in inglese fa fede. [Leggi la fonte in inglese](../adaptive-brightness.md) oppure [apri una segnalazione per correggere la traduzione](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Luminosità adattiva

La luminosità adattiva è un sistema di controllo opzionale che opera sul pannello, apprende il normale andamento della luce ambientale attorno al pannello e regola lo schermo senza richiedere un'automazione di Home Assistant. È disattivata per impostazione predefinita; quando è disattivata, rimane disponibile il normale controllo manuale della luminosità o tramite Home Assistant.

## Scegli la sorgente di luce ambientale

Apri **Configura → Schermo** e scegli **Sorgente di luce ambientale**:

- Lascia il campo vuoto per utilizzare il sensore di luce del pannello, se il profilo attivo e la verifica in tempo reale delle funzionalità ne indicano la disponibilità.
- Seleziona un'entità di illuminamento di Home Assistant quando il pannello non dispone di un sensore locale adatto oppure quando un sensore installato nella stanza rappresenta meglio la luce percepita dall'utente.

The Home Assistant source uses one exact authenticated entity subscription rather than the full state stream. If neither source is available, ha-paneld leaves adaptive control unavailable instead of guessing from time alone.

When a Home Assistant illuminance source is selected, ha-paneld can seed the on-panel pattern with up to seven days of its existing Home Assistant history. This gives automatic brightness a useful starting point instead of waiting for fresh readings to accumulate. It depends on the source being recorded and the Home Assistant history service being available; if no usable history is returned, learning simply starts from new readings.

## Attivala e regolala

Enable **Auto-brightness** in the same Display card. The controller retains up to seven days of bounded, on-panel ambient history and learns the normal pattern for the time of day. Short positive deviations, such as a room light being switched on, can raise the proposed level above that baseline.

**Minimum level** sets the lowest level proposed by automatic control and rescales the learned range from that floor to full brightness. It ranges from 4% to 99%. The 4% floor is where the backlight's own never-blank minimum sits, so anything lower would move the control without changing the screen. It does not limit manual brightness, which can still be set lower.

**Sensitivity** is the percentage of a difference from the learned pattern that is applied to the screen, once the controller has decided the difference is real. At 0% it ignores the difference and uses the learned pattern alone. Lower values make the response steadier, and the default of 50% leaves equal room to tune in either direction. It is not a raw follow-the-light control: a brief brightening is only acted on once it has been sustained or risen sharply enough to be admitted, and while the daily pattern is still being learned the screen follows the measured light regardless of this setting. The seven-day chart previews the observed range, learned baseline and proposed level before or while the controller is active. Unsaved Minimum level and Sensitivity changes are reflected in the preview without rewriting stored ambient history.

The history is tied to the selected source and a coarse room/time context derived from the configured Home Assistant location and timezone. A material location, timezone or source change starts a separate history rather than silently applying evidence learned for another room context.

## Modifiche manuali e ripristino

A manual brightness change records a four-hour temporary preference, so the learner does not immediately fight the user. Subsequent automatic changes retain 20% influence at first, then regain full authority through a smooth four-hour fade. Select **Resume full auto** in the adaptive-brightness panel to end that preference immediately instead of waiting for the fade to complete.

Select **Reset learned history** after moving the panel, replacing its light source or when the retained week no longer represents the room. The confirmation deletes the seven-day ambient history for the current source and room/time context, then restarts learning; it does not change the selected source or the Auto-brightness setting.

Adaptive brightness uses Android's normal screen-brightness path and does not require root. Reading a particular panel sensor can still depend on that sensor's hardware access path; the active profile and live capability checks remain authoritative.

## Controllo tramite Home Assistant

When exposed, `switch.<panel>_auto_brightness` enables or disables the same on-panel controller. Screen brightness remains `light.<panel>_screen`. A manual brightness command can therefore create the same temporary preference as a local change; use the Configure page's **Resume full auto** action to return immediately to the learned target.
