# Adaptive brightness

Adaptive brightness is an optional on-panel controller that learns the normal ambient-light pattern around a panel and adjusts the screen without requiring a Home Assistant automation. It is off by default, and ordinary manual or Home Assistant brightness control remains available when it is off.

## Choose the light source

Open **Configure → Display** and choose **Ambient light source**:

- Leave it blank to use the panel's own light sensor when the active profile and live capability probe expose one.
- Select one Home Assistant illuminance entity when the panel has no suitable local sensor or a room-mounted sensor better represents the light seen by the user.

The Home Assistant source uses one exact authenticated entity subscription rather than the full state stream. If neither source is available, ha-paneld leaves adaptive control unavailable instead of guessing from time alone.

When a Home Assistant illuminance source is selected, ha-paneld can seed the on-panel pattern with up to seven days of its existing Home Assistant history. This gives automatic brightness a useful starting point instead of waiting for fresh readings to accumulate. It depends on the source being recorded and the Home Assistant history service being available; if no usable history is returned, learning simply starts from new readings.

## Turn it on and tune it

Enable **Auto-brightness** in the same Display card. The controller retains up to seven days of bounded, on-panel ambient history and learns the normal pattern for the time of day. Short positive deviations, such as a room light being switched on, can raise the proposed level above that baseline.

**Minimum level** sets the lowest level proposed by automatic control and rescales the learned range from that floor to full brightness. Its 4% default preserves the existing visible floor. It does not limit manual brightness, which can still be set lower.

**Sensitivity** controls how strongly the screen follows deviations from the learned pattern. The default 50 is the balanced starting point; lower values make the response steadier and higher values make it more reactive. The seven-day chart previews the observed range, learned baseline and proposed level before or while the controller is active. Unsaved Minimum level and Sensitivity changes are reflected in the preview without rewriting stored ambient history.

The history is tied to the selected source and a coarse room/time context derived from the configured Home Assistant location and timezone. A material location, timezone or source change starts a separate history rather than silently applying evidence learned for another room context.

## Manual changes and recovery

A manual brightness change records a four-hour temporary preference, so the learner does not immediately fight the user. Subsequent automatic changes retain 20% influence at first, then regain full authority through a smooth four-hour fade. Select **Resume full auto** in the adaptive-brightness panel to end that preference immediately instead of waiting for the fade to complete.

Select **Reset learned history** after moving the panel, replacing its light source or when the retained week no longer represents the room. The confirmation deletes the seven-day ambient history for the current source and room/time context, then restarts learning; it does not change the selected source or the Auto-brightness setting.

Adaptive brightness uses Android's normal screen-brightness path and does not require root. Reading a particular panel sensor can still depend on that sensor's hardware access path; the active profile and live capability checks remain authoritative.

## Home Assistant control

When exposed, `switch.<panel>_auto_brightness` enables or disables the same on-panel controller. Screen brightness remains `light.<panel>_screen`. A manual brightness command can therefore create the same temporary preference as a local change; use the Configure page's **Resume full auto** action to return immediately to the learned target.
