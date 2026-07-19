# Adaptive proximity and wake on wave

Proximity sensors on Android wall panels do not share one useful scale. Some report distance, some report only two values, polarity can differ, and idle readings can drift between units or firmware versions. ha-paneld learns the connected sensor's clear baseline, near reference, polarity and reporting style instead of requiring model-specific thresholds.

## Learning and teaching

Open **Configure → Presence & wake** to see the current phase. Learning runs locally and normally needs no setup:

1. Keep clear of the panel while it identifies the idle signal and room baseline.
2. Use the panel normally so deliberate approaches and retreats can be distinguished from idle jitter.
3. If faster setup is useful, select **Teach a wave** and complete three deliberate waves when prompted.
4. Once ready, **Test a wave** checks one gesture without waking the display.

Touch-to-wake remains available throughout learning. **Forget learned proximity** deletes the evidence for that sensor and returns it to the learning journey; use it after moving the panel, changing firmware or replacing the sensor route.

## Home Assistant entities

When the model is trustworthy and the active profile exposes a usable proximity source, Home Assistant receives:

- `binary_sensor.<panel>_proximity` as learned near/far occupancy; and
- `sensor.<panel>_proximity_level` as a fleet-normalized 0–100 value, where 0 is far and 100 is near.

The entities stay unavailable while evidence is insufficient or the sensor route is unhealthy. ha-paneld does not publish a confident-looking value from an untrusted model. Binary-only sensors can still provide occupancy and wake gestures, while a meaningful normalized level is published only when the observed signal supports it.

## Wake on wave

**Wake on wave** recognizes one bounded far→near→far movement. Prolonged presence, teaching, testing and short idle jitter do not wake the display. This avoids treating someone standing near the panel, or a noisy dense sensor, as a stream of wake requests.

The feature needs a live proximity source and a ready learned model, but the source itself may be an ordinary Android sensor or a profile-selected helper route. The web UI and diagnostics report the actual route and readiness; a profile declaration alone does not invent sensor availability.
