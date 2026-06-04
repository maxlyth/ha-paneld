# Performance tuning for Home Assistant wall panels

Cheap Android wall panels (Sonoff NSPanel Pro, Tuya TPA10, generic rk3566/rk3576 boards) routinely
feel sluggish, stutter when scrolling, blank out, or silently stop updating. There is very little
written about *why*, so this is a field guide: the real causes, how to **measure** them with
ha-paneld, and the fixes that actually move the needle — in rough order of impact.

> TL;DR: the single biggest cause is usually **the Home Assistant WebSocket event firehose**, not the
> panel's CPU. The biggest fix is to **reduce the number of state events the dashboard has to
> process**, not to buy a faster panel.

## Why panels get slow

A Lovelace dashboard is a Chromium WebView. When it connects, HA's frontend WebSocket subscribes it
to **every `state_changed` event in the instance**. The WebView's JavaScript main thread has to parse
and process each one. On a busy Home Assistant this is a firehose:

- A real-world example: a primary instance with **~3,400 entities emitting ~7.3 events/sec**. Every
  panel on it must handle all 7.3/sec, forever, on top of rendering.
- Cheap panels are **single-thread-bound for rendering**: Chromium fans compositing/raster across
  cores, but JS execution + layout + paint run on one main thread. More cores raise throughput, not
  per-frame latency — so "8 cores at 30% each" can still jank if one thread is saturated.
- RAM is tight. The V8 JS heap grows until it hits a ceiling, then GC starves the render pipeline.

### Failure modes you'll actually see

| Symptom | Likely cause |
| --- | --- |
| Scrolling/animation stutter, laggy touch | renderer main-thread saturated (event volume + heavy cards) |
| **Whole view goes blank** (header/tabs present, cards gone), touches do nothing | **JS heap near its ceiling (~94% seen in the wild)** — GC starves rendering |
| Dashboard slowly degrades over days of uptime | heap creep / memory fragmentation → periodic reload needed |
| Entities stop updating, then a burst | WebSocket disconnect/reconnect under load |
| One panel fine, another slow | heavier dashboard, more entities in view, or higher uptime |

## Measure it first (with ha-paneld)

Open the panel's web page (`http://<panel-ip>:8888/`, the HA device's **Visit** link):

- **Performance chart** — live CPU / GPU / RAM history + load average + temperature. If CPU/load are
  pegged, the panel is compute-bound; if RAM is high and climbing, suspect the heap-ceiling stall.
- **Capabilities** — what works on *this* firmware, and how to fix shortfalls.
- **Diagnostics dump** (`/diag`) — paste into a bug report.
- **Rendering metrics** (planned, via the DevTools/CDP relay) — FPS, jank %, main-thread long tasks,
  and **JS heap %** straight from the WebView, so you can see the heap-ceiling stall coming.

Don't guess — a panel that feels laggy with *low* CPU is waiting on data (event/WebSocket volume),
not compute. The chart tells you which.

## Fixes, in order of impact

### 1. Cut the event volume the panel sees (biggest lever)

The dashboard doesn't need to subscribe to your whole instance. Two ways to shrink the firehose:

- **Give panels a dedicated, dashboard-only HA instance** that mirrors *only* the entities the
  dashboards render (via the [`remote_homeassistant`](https://github.com/custom-components/remote_homeassistant)
  bridge, or a separate instance fed a curated include-list). In one deployment this turned a
  **3,410-entity / 7.3 ev/s** firehose into a **310-entity / 0.77 ev/s** view — a ~10× reduction —
  and was the difference between panels that stall and panels that don't. The dashboard instance runs
  no automations, no recorder noise, no extras: it just serves Lovelace.
- **Or reduce event volume at the source** on your main instance (helps panels *and* the recorder):
  disable or throttle the noisiest entities — power meters, BLE distance/trilateration, Zigbee
  diagnostics, high-frequency templates. Throttle source-side (ESPHome `throttle_average`/`delta`,
  Zigbee `configure_reporting`), bump `scan_interval`, and trim attribute fan-out. Find the worst
  offenders by querying the recorder for the highest state-change rates.

### 2. Lighten the dashboard

- Fewer cards and **fewer entities per view** — split a dense dashboard into more tabs; a view only
  pays for what's mounted.
- Prefer built-in cards; some custom cards are heavy (continuous animation, large DOM, per-frame JS).
- Watch the **JS heap**: if a view trends toward the ceiling, it's too heavy — simplify it, or
  schedule a periodic dashboard reload (ha-paneld's `button.<panel>_reload`) as a stopgap.

### 3. Right-size expectations for the hardware

PX30/rk3566-class panels are fine for a curated dashboard on a reduced event feed; they are not fine
as a full admin dashboard on a 3,000-entity firehose. Match the dashboard and the feed to the panel.

## Checklist

- [ ] Panels connect to a **reduced** entity/event feed (dedicated dashboard instance or curated bridge), not the full instance
- [ ] Noisiest entities disabled/throttled at the source
- [ ] Dashboards split into focused views; heavy custom cards audited
- [ ] CPU/RAM and (when available) JS-heap/jank watched on the ha-paneld Performance page
- [ ] A reload path for the heap-ceiling stall (`button.<panel>_reload`)
