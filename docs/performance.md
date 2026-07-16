# Performance tuning for Home Assistant wall panels

This guide explains why an inexpensive Android wall panel can feel sluggish, stutter while scrolling, blank out or stop updating even though the same dashboard works well on a phone or desktop. Start with the work Home Assistant sends to the panel, then measure the remaining dashboard and hardware limits instead of assuming the panel must be replaced.

> [!TIP]
> A common cause is the number of Home Assistant entity states and updates reaching the dashboard. A large installation can make a low-powered panel process thousands of entities that it never displays. With ha-paneld's built-in renderer, the first fix to try is the automatic dashboard entity filter.

## Measure before changing anything

Open the panel's web page at `http://<panel-ip>:8888/` or use the Home Assistant device page's **Visit** link. Record the same dashboard view for a similar period before and after each change so the comparison is meaningful.

- **Performance page** — dashboard response time, unexpected reloads, CPU, GPU, RAM, clock speed, temperature and the busiest processes.
- **Rendering metrics** — frame rate, jank, main-thread long tasks and JavaScript heap use from the WebView.
- **Entities page** — what the built-in renderer currently subscribes to, what automatic learning found and which dashboard rules still need a decision.
- **Diagnostics dump** (`/diag`) — a copy-paste report for a bug report when the cause is still unclear.

High renderer CPU with a large unfiltered subscription is a strong reason to test filtering. High JavaScript heap use, repeated reloads or one unusually expensive dashboard view points toward card and layout work as well. Treat the measurements together; a single CPU snapshot cannot identify the cause by itself.

### Rule out the legacy stock NSPanel Pro Zigbee-watchdog defect

Legacy stock firmware containing a recursive `export LD_LIBRARY_PATH=/vendor/bin/siliconlabs_host/:${LD_LIBRARY_PATH}` assignment can make the vendor's `guard_process.sh` the performance problem itself. A [community investigation](https://github.com/maxlyth/ha-paneld/issues/34) confirmed the defect on an NSPanel Pro 120 running stock 3.8.0 and reported the condition across all 16 panels in that fleet. The script prepends its directory every five seconds; after roughly ten hours in that setup the environment string crosses Linux's per-string execution limit, external commands start failing with `E2BIG`, `sleep` stops delaying the loop and the watchdog can pin one CPU core. At that point it can also fail to restart a dead `zgateway`, leaving Zigbee unavailable. Broader exposure across legacy 1.x–3.x firmware is plausible where the same line exists, but has not been independently verified by the ha-paneld project.

Look for this pattern when ha-paneld reports periodic system load on an otherwise idle stock NSPanel Pro:

- `guard_process.sh` stays near 100% of one CPU core and its process size grows far above the reported healthy value of about 9 MB;
- `zgateway` is absent or no longer recovers; and
- rebooting helps, but the load returns around ten hours later in the reported stock setup.

A reboot only resets the accumulating environment temporarily. Issue #34 contains a reporter-provided root/ADB workaround, but the project has not yet independently validated that mutation and recovery sequence. Do not apply it unless the exact recursive assignment is present once in the vendor-native script. Any repair must first verify a non-empty backup and preserve ownership, mode and SELinux metadata. Abort before mutation on an unexpected match; after mutation, roll back if restart or verification fails, return `/vendor` to read-only, and verify both `zgateway` and its availability topic. A firmware update that rewrites `/vendor` removes the local patch; the defect returns only if the target firmware still contains the vulnerable assignment. Community inspection of firmware 4.0.12 and 4.6.0 did not find it.

## Fixes, in order of impact

### 1. Filter the built-in renderer's entity subscription

Home Assistant's frontend normally subscribes to the state of every entity visible to the signed-in user. The panel must receive and process those states even when its dashboard uses only a small subset. ha-paneld's built-in renderer can add the dashboard's learned entity set to that native subscription, so Home Assistant filters the stream before serializing and sending it. The panel keeps its ordinary authenticated Home Assistant connection; no proxy or additional server is involved.

The automatic filter is opt-in and applies only to the built-in renderer:

1. In `:8888` open **Configure → Dashboard**, select **Built-in renderer**, then enable **Automatic dashboard entity filter**.
2. Open the **Entities** tab and select **Scan dashboard now**.
3. Visit every dashboard tab and exercise controls, pop-ups and conditional content so runtime dependencies have a chance to be observed.
4. Review the current, suggested and excluded entities. Pin anything required indirectly by a custom card or template.
5. Resolve any entity-filter checks. Narrow a broad or dynamic dashboard rule where practical, or make an explicit choice while accepting the warning shown by the panel.
6. Apply the policy-selected set, let the dashboard reload and compare the same views on the Performance page.

Automatic learning cannot prove every custom card or dynamic template dependency. A missing entity may leave a card stale or unavailable, so review the result on a non-critical panel first and keep filtering disabled until the candidate is credible. The previous subscription remains available as the safe rollback: turn off **Automatic dashboard entity filter** and reload the dashboard.

Advanced testers can supply and inspect an exact list through the API. The UI workflow, manual API format, runtime status and rollback commands are documented in [The built-in dashboard renderer](built-in-renderer.md#experimental-entity-filter-092).

### 2. Lighten the dashboard itself

- Split a dense dashboard into focused views and avoid mounting cards the panel never needs.
- Prefer built-in cards when a custom card continuously animates, creates a large document tree or performs frequent JavaScript work.
- Watch JavaScript heap use. If one view repeatedly approaches the ceiling, simplify it or use `button.<panel>_reload` as a temporary recovery path while finding the expensive card.
- Test camera and graph cards separately. Their decoding, history queries and rendering cost can dominate even after entity filtering is working correctly.

### 3. Reduce unnecessary updates at the source

Entity filtering protects the panel from unrelated entities, but it does not make a required entity cheaper. If a dashboard really displays a power meter, BLE distance sensor, rapidly changing template or noisy diagnostic entity, reduce that source's update rate where the integration supports it. This can also reduce recorder and database work for the whole Home Assistant installation.

Useful controls include ESPHome throttling or delta filters, Zigbee reporting intervals, integration `scan_interval` settings and less frequent template updates. Confirm the change does not make an automation or history view less useful before applying it globally.

### 4. Match the remaining dashboard to the hardware

PX30 and rk3566 panels can run a focused dashboard well, but they still have limited single-thread performance and usually only 2 GB of RAM. Entity filtering removes unnecessary state work; it cannot make an oversized camera stream, complex animation or very large history graph free. Design for the panel's logical display size and test the heaviest view rather than judging only the home tab.

## Checklist

- [ ] The built-in renderer is selected where Assist voice control and native notifications are not required
- [ ] Automatic dashboard entity filtering is enabled, scanned, reviewed and explicitly applied
- [ ] Every dashboard tab, pop-up and conditional path has been exercised during learning
- [ ] Custom-card and template dependencies are pinned or otherwise accounted for
- [ ] Entity-filter checks have been resolved deliberately rather than ignored accidentally
- [ ] The same views have been compared before and after filtering on the Performance page
- [ ] Heavy cards, camera streams and graphs have been tested separately
- [ ] Required high-frequency entities have been tuned at the source where appropriate
- [ ] If the panel uses a legacy stock NSPanel Pro Zigbee stack, its `guard_process.sh` has been checked
- [ ] A reload and filter-disable recovery path has been verified

## What the built-in filter replaced

Before ha-paneld could filter its own subscription, one deployment used a dedicated dashboard-only Home Assistant instance fed through the `remote_homeassistant` integration. It reduced the panel-facing feed from about 3,410 entities and 7.3 updates per second to about 310 entities and 0.77 updates per second, making the dashboards usable. It also required a second Home Assistant installation, bridged entities, separate configuration, authentication, updates, backups, monitoring and another failure path.

The built-in filter addresses that panel-load problem inside ha-paneld, so the split-instance system is no longer needed or maintained in that deployment and is not recommended for built-in-renderer users. This comparison remains here to show the amount of infrastructure the integrated solution replaces. Users who must retain the Companion app or another renderer cannot use ha-paneld's filter on that renderer; source-side tuning still applies, while any external filtering arrangement remains outside ha-paneld's supported setup.

---

## Reference

### Why a large Home Assistant installation can slow a panel

The Home Assistant frontend maintains a WebSocket subscription containing the current state and subsequent updates for the entities available to the user. Without a restricted entity set, the renderer receives far more data than a focused wall dashboard normally uses. Its JavaScript main thread must parse the messages, update the frontend state model and decide whether anything visible changed.

Low-cost panels are especially sensitive because JavaScript execution, layout and paint depend heavily on one renderer thread. Extra CPU cores help other work but do not remove that latency, and limited RAM makes garbage collection increasingly disruptive as the WebView heap grows.

### Common symptoms

| Symptom | Likely cause |
| --- | --- |
| Delayed taps, sluggish navigation or scrolling stutter | renderer main-thread work from a large entity stream, heavy cards or both |
| One dashboard view is much worse than the others | expensive cards, camera decoding, history data or a large document tree on that view |
| Whole view goes blank while the outer interface remains | WebView heap pressure or renderer failure |
| Dashboard gradually degrades over days | heap growth, memory fragmentation or a card that accumulates work |
| Entities stop updating and later arrive in a burst | WebSocket interruption, reconnect or a renderer unable to keep up |
| Similar panels behave differently | different dashboard content, entity subscription, WebView version, uptime or thermal state |
| Legacy stock NSPanel Pro becomes janky around ten hours after boot and `guard_process.sh` uses one core | check for the recursive vendor Zigbee-watchdog assignment leading to `E2BIG` |

Garbage collection periodically pauses JavaScript to reclaim unused memory. Near the heap ceiling those pauses become longer and more frequent, which can starve rendering even when the Android process itself has not crashed.
