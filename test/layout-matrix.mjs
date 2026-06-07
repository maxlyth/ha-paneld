// Non-blocking layout-stability (CLS) matrix for the info page.
//
// Serves the real info.css/info.js (via test/fixtures/info-fixture.html), mocks /perf + /proximity +
// /inspect with worst-case CYCLING data (process names long↔short, render drawing↔idle, raw sweeping),
// then measures Cumulative Layout Shift across a matrix of viewport WIDTHS × TEXT SIZES (the myopic
// axis) while the live cards are scrolled OFF-SCREEN. Diffs a committed baseline:
//   - worse than baseline (+epsilon)  -> REGRESSION (flagged, but exit 0 — never blocks a build)
//   - within baseline                 -> ok / known-backlog
// `--update-baseline` rewrites test/baseline.json. Local: `node test/layout-matrix.mjs`.
import { chromium } from 'playwright-core';
import http from 'node:http';
import { readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('..', import.meta.url)); // repo root
const CHROME = process.env.CHROME || '/usr/bin/chromium';
const SECS = +(process.env.SECS || 7);
const EPS = +(process.env.EPS || 0.2);  // regression slack — wide for now: CLS is noisy run-to-run
                                        // (timing of polls vs scroll/masonry). TODO: average N runs to tighten.
const WIDTHS = [480, 1280, 1920, 2560]; // 480 = smallest real panel (NSPanel Pro 480×480; below that the
                                        // hardware can't run an HA dashboard); 1920 ≈ 10", 2560 ≈ 15" → up to 4 cols
const FONTS = [16, 20, 24];             // normal → large → x-large (myopic)
const MIME = { '.html': 'text/html', '.css': 'text/css', '.js': 'application/javascript', '.svg': 'image/svg+xml' };

let cyc = 0;
const PROCS = [
  [{ name: 'io.github.maxlyth.hapaneld', cpu: 12 }, { name: 'system_server', cpu: 8 }, { name: 'surfaceflinger', cpu: 4 }, { name: 'io.homeassistant.companion.android.minimal', cpu: 3 }, { name: 'kworker/0:1', cpu: 1 }],
  [{ name: 'system_server', cpu: 9 }, { name: 'zgateway', cpu: 2 }, { name: 'ksoftirqd/0', cpu: 1 }, { name: 'io.github.maxlyth.hapaneld', cpu: 6 }, { name: 'mosquitto', cpu: 1 }],
];
const RAWS = [70, 58, 44, 30, 22, 30, 44, 58];
function perfPayload() {
  const n = cyc++; const drawing = n % 3 === 0;
  return { enabled: true, cpu: 10 + (n % 7), cores: [12, 18, 9, 7], memUsedMb: 900 + (n % 50), memTotalMb: 2000,
    gpu: 5 + (n % 4), gpuMhz: 400, freqMhz: [1512], freqMaxMhz: 1512, load: [1.2 + (n % 2), 1.0, 0.8], tempC: 41 + (n % 3),
    hist: { cpu: [10, 12, 11], ram: [45, 46], gpu: [5, 6] }, top: PROCS[n % PROCS.length],
    render: { status: 'ok', pkg: 'io.homeassistant.companion.android.minimal', verdict: drawing ? 'occasional' : 'smooth',
      mainPct: 30 + (n % 20), jankPct: drawing ? 5 + (n % 10) : null, p99: 18, hist: [3, 5, 4] } };
}
function proxPayload() { const raw = RAWS[cyc % RAWS.length];
  return { present: true, raw, near: raw < 40, max: 9, calibrated: false, threshold: null, nearRaw: null, farRaw: null,
    nearBelow: true, margin: 0, sensitivity: 'MEDIUM', indistinct: false, graded: true, distinct: 32 }; }

async function measure(browser, port, width, font) {
  const page = await browser.newPage({ viewport: { width, height: 860 } });
  await page.route('**/perf', (r) => r.fulfill({ contentType: 'application/json', body: JSON.stringify(perfPayload()) }));
  await page.route('**/proximity', (r) => r.fulfill({ contentType: 'application/json', body: JSON.stringify(proxPayload()) }));
  await page.route('**/inspect', (r) => r.fulfill({ contentType: 'application/json', body: JSON.stringify({ running: false, port: 9222, status: 'no-socket' }) }));
  await page.goto(`http://127.0.0.1:${port}/test/fixtures/info-fixture.html`, { waitUntil: 'domcontentloaded' });
  await page.evaluate((px) => { document.documentElement.style.fontSize = px + 'px'; }, font);
  await page.evaluate(() => {
    window.__cls = 0; window.__by = {};
    new PerformanceObserver((l) => { for (const e of l.getEntries()) { if (e.hadRecentInput) continue; window.__cls += e.value;
      for (const s of e.sources || []) { const n = s.node, c = n && n.closest && n.closest('.card'), h = c && c.querySelector('h2');
        const k = h ? h.textContent.replace(/\s+/g, ' ').trim() : (n && (n.id || n.tagName)) || '?'; window.__by[k] = (window.__by[k] || 0) + e.value; } }
    }).observe({ type: 'layout-shift', buffered: true });
  });
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
  await page.waitForTimeout(SECS * 1000);
  const r = await page.evaluate(() => ({ cls: +window.__cls.toFixed(4), by: window.__by }));
  await page.close();
  return r;
}

const server = http.createServer(async (req, res) => {
  try { const p = decodeURIComponent(req.url.split('?')[0]); const body = await readFile(join(ROOT, p));
    res.writeHead(200, { 'Content-Type': MIME[p.slice(p.lastIndexOf('.'))] || 'application/octet-stream' }); res.end(body);
  } catch { res.writeHead(404); res.end('not found'); }
});
await new Promise((r) => server.listen(0, '127.0.0.1', r));
const port = server.address().port;
const browser = await chromium.launch({ executablePath: CHROME, headless: true, args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-gpu'] });

const results = {};
for (const w of WIDTHS) for (const f of FONTS) {
  const key = `${w}x${f}`; const r = await measure(browser, port, w, f); results[key] = r;
}
await browser.close(); server.close();

let baseline = {};
try { baseline = JSON.parse(await readFile(join(ROOT, 'test/baseline.json'), 'utf8')); } catch { /* none yet */ }

if (process.argv.includes('--update-baseline')) {
  const out = {}; for (const k of Object.keys(results)) out[k] = results[k].cls;
  await writeFile(join(ROOT, 'test/baseline.json'), JSON.stringify(out, null, 2) + '\n');
  console.log('baseline updated:', JSON.stringify(out));
  process.exit(0);
}

console.log('## CLS layout matrix (width × text-px) — non-blocking, good < 0.10\n');
console.log('| cell | CLS | baseline | status | top offender |');
console.log('| --- | --- | --- | --- | --- |');
let regressions = 0;
for (const w of WIDTHS) for (const f of FONTS) {
  const k = `${w}x${f}`; const cls = results[k].cls; const base = baseline[k];
  const top = Object.entries(results[k].by).sort((a, b) => b[1] - a[1])[0];
  const offender = top ? `${top[0]} (${top[1].toFixed(3)})` : '—';
  let status = 'ok';
  if (base == null) status = 'new (no baseline)';
  else if (cls > base + EPS) { status = '⚠ REGRESSION'; regressions++; }
  else if (cls > 0.1) status = 'known-backlog';
  console.log(`| ${k} | ${cls.toFixed(4)} | ${base ?? '—'} | ${status} | ${offender} |`);
}
console.log(`\n${regressions} regression(s). (Report-only — exit 0 regardless.)`);
process.exit(0);
