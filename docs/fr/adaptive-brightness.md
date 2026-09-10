> [!IMPORTANT]
> Ce document est généré automatiquement et fait l’objet d’une vérification croisée automatique, mais il n’a pas été systématiquement relu par des locuteurs de cette langue. La documentation en anglais fait foi. [Consulter la source en anglais](../adaptive-brightness.md) ou [signaler une correction de traduction](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Luminosité adaptative

La luminosité adaptative est un mécanisme de contrôle facultatif exécuté directement sur le panneau. Elle apprend le profil habituel de lumière ambiante autour du panneau et ajuste l’écran sans nécessiter d’automatisation Home Assistant. Elle est désactivée par défaut ; lorsqu’elle est désactivée, le réglage manuel ordinaire ou le contrôle de la luminosité par Home Assistant reste disponible.

## Choisir la source de lumière ambiante

Ouvrez **Configurer → Affichage** et choisissez **Source de lumière ambiante** :

- Laissez ce champ vide pour utiliser le capteur de luminosité du panneau lorsque le profil actif et la détection des capacités en temps réel indiquent qu’il en existe un.
- Sélectionnez une entité d’éclairement Home Assistant si le panneau ne dispose pas d’un capteur local adapté ou si un capteur installé dans la pièce représente mieux la luminosité perçue par l’utilisateur.

The Home Assistant source uses one exact authenticated entity subscription rather than the full state stream. If neither source is available, ha-paneld leaves adaptive control unavailable instead of guessing from time alone.

When a Home Assistant illuminance source is selected, ha-paneld can seed the on-panel pattern with up to seven days of its existing Home Assistant history. This gives automatic brightness a useful starting point instead of waiting for fresh readings to accumulate. It depends on the source being recorded and the Home Assistant history service being available; if no usable history is returned, learning simply starts from new readings.

## Activer et régler

Enable **Auto-brightness** in the same Display card. The controller retains up to seven days of bounded, on-panel ambient history and learns the normal pattern for the time of day. Short positive deviations, such as a room light being switched on, can raise the proposed level above that baseline.

**Minimum level** sets the lowest level proposed by automatic control and rescales the learned range from that floor to full brightness. It ranges from 4% to 99%. The 4% floor is where the backlight's own never-blank minimum sits, so anything lower would move the control without changing the screen. It does not limit manual brightness, which can still be set lower.

**Sensitivity** is the percentage of a difference from the learned pattern that is applied to the screen, once the controller has decided the difference is real. At 0% it ignores the difference and uses the learned pattern alone. Lower values make the response steadier, and the default of 50% leaves equal room to tune in either direction. It is not a raw follow-the-light control: a brief brightening is only acted on once it has been sustained or risen sharply enough to be admitted, and while the daily pattern is still being learned the screen follows the measured light regardless of this setting. The seven-day chart previews the observed range, learned baseline and proposed level before or while the controller is active. Unsaved Minimum level and Sensitivity changes are reflected in the preview without rewriting stored ambient history.

The history is tied to the selected source and a coarse room/time context derived from the configured Home Assistant location and timezone. A material location, timezone or source change starts a separate history rather than silently applying evidence learned for another room context.

## Modifications manuelles et récupération

A manual brightness change records a four-hour temporary preference, so the learner does not immediately fight the user. Subsequent automatic changes retain 20% influence at first, then regain full authority through a smooth four-hour fade. Select **Resume full auto** in the adaptive-brightness panel to end that preference immediately instead of waiting for the fade to complete.

Select **Reset learned history** after moving the panel, replacing its light source or when the retained week no longer represents the room. The confirmation deletes the seven-day ambient history for the current source and room/time context, then restarts learning; it does not change the selected source or the Auto-brightness setting.

Adaptive brightness uses Android's normal screen-brightness path and does not require root. Reading a particular panel sensor can still depend on that sensor's hardware access path; the active profile and live capability checks remain authoritative.

## Contrôle depuis Home Assistant

When exposed, `switch.<panel>_auto_brightness` enables or disables the same on-panel controller. Screen brightness remains `light.<panel>_screen`. A manual brightness command can therefore create the same temporary preference as a local change; use the Configure page's **Resume full auto** action to return immediately to the learned target.
