# UI layout-stability tests (CLS) — report-only

Objective layout-stability testing for the info page (`GET /`). **Non-blocking by design**: it never
fails a build or gates a merge — it produces a report that flags **regressions** (vs a committed
baseline) and lists high-CLS cells as **backlog**. The intent is ongoing visibility as the UI settles,
not a gate.

## What it does

`layout-matrix.mjs` serves the **real** `app/src/main/assets/info.css` + `info.js` (via
`fixtures/info-fixture.html` — no duplication), mocks `/perf`, `/proximity`, `/inspect` with worst-case
**cycling** data (process names long↔short, render drawing↔idle, proximity raw sweeping), then measures
**Cumulative Layout Shift** (the `layout-shift` PerformanceObserver) across a matrix of:

- viewport **widths** `480 / 1280 / 1920 / 2560` — 480 = smallest real panel (NSPanel Pro 480×480),
  then real panel sizes (1920 ≈ 10″, 2560 ≈ 15″; masonry expands to up to 4 columns), and
- **text sizes** `16 / 20 / 24 px` root font (the *myopic-user* axis — larger text wraps to more lines).

…while the live cards are scrolled **off-screen**, then diffs `baseline.json`.

## Run it

```bash
cd test && npm ci                # exact playwright-core version from package-lock.json (no browser download)
# needs a chromium: apk add chromium / apt-get install chromium ; or set CHROME=
CHROME=/usr/bin/chromium node layout-matrix.mjs              # report
CHROME=/usr/bin/chromium node layout-matrix.mjs --update-baseline   # rewrite baseline.json
```

Env: `CHROME` (chromium path), `SECS` (poll window per cell), `EPS` (regression slack).

## CI

[`.github/workflows/ui-layout.yml`](../.github/workflows/ui-layout.yml) runs the matrix on changes to
`app/src/main/assets/**` or `test/**` and writes the table to the job summary. The job is
`continue-on-error` — **green regardless** of CLS.

## Known limitations / backlog

- **CLS is noisy run-to-run** (timing of polls vs scroll/masonry) — `EPS` is wide (0.2) so only gross
  regressions flag. TODO: average N runs per cell to tighten the baseline.
- **Current baseline is below the 0.1 target in every cell.** A 2026-07-16 local rerun covered all 12
  width/text-size combinations with zero regressions and a maximum CLS of 0.0247. Reopen layout work
  only when the report identifies a reproducible regression; do not preserve the harness's obsolete
  first-run masonry/large-text findings as active backlog.
- **Fixture vs real page**: absolute numbers may over-state vs the device (the fixture's card heights /
  scroll differ) — calibrate against a live panel when convenient.
