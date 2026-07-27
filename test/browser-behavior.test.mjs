import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { join } from 'node:path';
import test from 'node:test';
import { chromium } from 'playwright-core';

const root = join(process.cwd(), '..', 'app', 'src', 'main', 'assets');
const chrome = process.env.CHROME || '/usr/bin/chromium';

function json(body, status = 200) {
  return { status, headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) };
}

function fixture() {
  return `<!doctype html><html><head><meta charset="utf-8"><link rel="stylesheet" href="/info.css"></head><body>
    <span id="hardened-approval-description"></span><span id="hardened-approval-conditional-description"></span>
    <button id="tab-basic"></button><button id="tab-adv"></button>
    <p id="cfg-msg"></p><p id="cfg-status"></p><div id="cfg-groups"></div>
    <div id="proximity-learning-mount"></div><div id="savebar" hidden><button id="savebtn" onclick="cfgSave()"></button></div>
    <script>window.CardColumnAlignment={attach:()=>()=>{}};</script>
    <script src="/configure.js"></script><script src="/proximity-learning.js"></script>
  </body></html>`;
}

async function startHarness(routes, pageFixture = fixture) {
  const server = createServer(async (request, response) => {
    const path = new URL(request.url, 'http://panel.test').pathname;
    if (path === '/') return response.end(pageFixture());
    if (['/configure.js', '/proximity-learning.js', '/install.js', '/info.js'].includes(path)) {
      response.setHeader('content-type', 'application/javascript; charset=utf-8');
      return response.end(await readFile(join(root, path.slice(1)), 'utf8'));
    }
    if (path === '/assets/card-size-memory.js') {
      response.setHeader('content-type', 'application/javascript; charset=utf-8');
      return response.end(await readFile(join(root, 'card-size-memory.js'), 'utf8'));
    }
    if (path === '/info.css') {
      response.setHeader('content-type', 'text/css');
      return response.end(await readFile(join(root, 'info.css'), 'utf8'));
    }
    const result = await routes(path, request);
    response.writeHead(result?.status || (result ? 200 : 404), result?.headers);
    response.end(result?.body || 'not found');
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return { server, url: `http://127.0.0.1:${server.address().port}` };
}

const browserTest = existsSync(chrome) ? test : test.skip;

function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
}

function autoSleepHistory({ included = true, label = 'Office ceiling motion', hours = 6, laneCount = 1, areaName = 'Office', areaKey = 'a'.repeat(64) } = {}) {
  const end = Date.now();
  const start = end - hours * 60 * 60 * 1000;
  return {
    available: true,
    hours,
    area_name: areaName,
    area_key: areaKey,
    window_start_epoch_ms: start,
    window_end_epoch_ms: end,
    segments: [{ start_epoch_ms: start, end_epoch_ms: end, output: included ? 'hold_awake' : 'allow_sleep' }],
    source_lanes: Array.from({ length: laneCount }, (_, index) => ({
      source_key: index.toString(16).padStart(64, 'b').slice(-64), label: index ? `${label} ${index + 1}` : label, included,
      segments: [{ start_epoch_ms: start, end_epoch_ms: end, state: included ? 'on' : 'off' }],
    })),
  };
}

function screenshotFixture({ hardened = false, supported = true } = {}) {
  const dialogShim = supported ? '' : '<script>HTMLDialogElement.prototype.showModal=undefined;</script>';
  return `<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
    <link rel="stylesheet" href="/info.css"></head><body data-hardened="${hardened ? '1' : '0'}">
    <canvas id="perfchart" width="600" height="96"></canvas><canvas id="respchart" width="600" height="150"></canvas>
    <table id="perf"></table><table id="smtbl"></table><table id="streamtbl"></table>
    <h2>Top processes <span class="top-process-modes" role="group" aria-label="Rank processes by"><button type="button" class="top-process-mode on" data-mode="cpu" aria-pressed="true" onclick="setTopMode('cpu')">CPU</button><button type="button" class="top-process-mode" data-mode="ram" aria-pressed="false" onclick="setTopMode('ram')">RAM</button></span></h2>
    <table id="topproc"></table><table id="noisyentities"></table>
    <small id="smhdr"></small><small id="perfage"></small><small id="sensage"></small><small id="insthdr"></small>
    <table id="senstbl"></table><p id="insthint"></p><div id="ctlzone"></div>
    <div id="dashboard-cards"><div class="card" id="shotcard" data-capture-ok="0"><h2>Screenshot</h2>
      <a class="shot" href="/api/v1/screenshot.png" target="_blank" rel="noopener" style="aspect-ratio:16/9">
        <img src="/initial.png" alt="panel screenshot" onload="this.parentElement.classList.add('loaded')" onerror="this.parentElement.classList.add('failed')">
      </a></div></div>
    <script>window.CardColumnAlignment={attach:()=>()=>{}};</script>${dialogShim}<script src="/info.js"></script></body></html>`;
}

function cardMemoryFixture(cold) {
  return `<!doctype html><html><head><style>
    #dashboard-cards{width:820px}.card{box-sizing:border-box;width:400px;min-height:80px;border:1px solid transparent;padding:10px}
    .top-process-card tr{height:34px}
  </style></head><body data-hydrate="${cold ? '1' : '0'}" data-hardened="0">
    <div id="bannerzone"></div><div id="dashboard-cards" data-card-size-page="dashboard" data-card-size-epoch="1" data-card-size-restore="1">
      <div class="card" data-layout-key="panel-info"${cold ? '' : ' style="height:220px"'}><table id="infotbl"><tr><td>reading…</td></tr></table></div>
      <div class="card" data-layout-key="live-metrics">
        <canvas id="perfchart" width="600" height="96"></canvas><canvas id="respchart" width="600" height="150"></canvas>
        <table id="perf"></table><table id="smtbl"></table><table id="streamtbl"></table><table id="noisyentities"></table>
        <small id="smhdr"></small><small id="perfage"></small><small id="sensage"></small><small id="insthdr"></small>
        <table id="senstbl"></table><p id="insthint"></p><div id="ctlzone"></div>
      </div>
      <div class="card" data-layout-key="top-processes"><h2>Top processes</h2><table id="topproc"></table></div>
    </div>
    <script src="/assets/card-size-memory.js"></script><script>window.__alignmentCalls=0;window.CardColumnAlignment={attach:()=>()=>window.__alignmentCalls++};</script><script src="/info.js"></script>
  </body></html>`;
}

function configureCardMemoryFixture(compact) {
  return `<!doctype html><html><head><style>#cfg-groups{width:820px}.card{box-sizing:border-box;width:400px;min-height:80px;padding:10px}
    [data-config-group="Display"]{height:${compact ? '100' : '220'}px}</style></head><body>
    <button id="tab-basic"></button><button id="tab-adv"></button><p id="cfg-msg"></p><p id="cfg-status"></p>
    <div id="cfg-groups" data-card-size-page="configure" data-card-size-epoch="1" data-card-size-restore="1" data-card-size-proximity="0"></div>
    <div id="savebar" hidden><button id="savebtn"></button></div>
    <script src="/assets/card-size-memory.js"></script><script>window.__alignmentCalls=0;window.CardColumnAlignment={attach:()=>()=>window.__alignmentCalls++};</script>
    <script src="/configure.js"></script></body></html>`;
}

function installCardMemoryFixture(compact) {
  return `<!doctype html><html><head><style>#install-cards{width:820px}.card{box-sizing:border-box;width:400px;min-height:80px;padding:10px}
    [data-layout-key="managed-components"]{height:${compact ? '100' : '220'}px}</style></head><body>
    <div id="install-cards" data-card-size-page="install" data-card-size-epoch="1" data-card-size-restore="1">
      <div class="card" data-layout-key="managed-components"><div class="comprow" data-name="paneld"><span class="cver">0.9.6</span>
        <select class="cchan"><option value="stable">Stable</option></select><select class="cvsel"><option>loading…</option></select>
        <a class="cnotes"></a><button class="cinstall" data-root="1"></button><a class="cdl"></a></div><p id="comp-msg"></p></div>
      <div class="card" id="radiocard" data-layout-key="radio-firmware" style="display:none"><span id="radio-status"></span><span id="radio-health"></span></div>
    </div><script src="/assets/card-size-memory.js"></script>
    <script>window.__alignmentCalls=0;window.CardColumnAlignment={attach:()=>()=>window.__alignmentCalls++};</script><script src="/install.js"></script>
  </body></html>`;
}

async function requestBody(request) {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  return Buffer.concat(chunks).toString('utf8');
}

function screenshotRoutes(options = {}) {
  const calls = options.calls || [];
  const initial = readFile(join(process.cwd(), '..', 'docs', 'img', 'ui-dashboard-dark.png'));
  const fresh = readFile(join(process.cwd(), '..', 'docs', 'img', 'icon.png'));
  return async (path, request) => {
    if (path === '/info.css') return { headers: { 'content-type': 'text/css' }, body: await readFile(join(root, 'info.css')) };
    if (path === '/initial.png') return { headers: { 'content-type': 'image/png' }, body: await initial };
    if (path === '/api/v1/input') {
      calls.push({ method: request.method, path, body: await requestBody(request), contentType: request.headers['content-type'] });
      if (options.input) return options.input(path, request, fresh);
      return { status: 200, headers: {
        'content-type': 'image/png',
        'X-ha-paneld-Input-Id': 'tap-17',
        'X-ha-paneld-Input-Route': 'accessibility',
        'X-ha-paneld-Screenshot-Route': 'daemon',
        'X-ha-paneld-Screenshot-Id': 'a'.repeat(64),
      }, body: await fresh };
    }
    if (path === '/api/v1/screenshot.png') {
      calls.push({ method: request.method, path });
      return options.screenshot ? options.screenshot(path, request, fresh) : { status: 200, headers: { 'content-type': 'image/png' }, body: await fresh };
    }
    if (path === '/api/v1/perf') return json(options.perf || {});
    if (path === '/api/v1/sensors') return json({});
    if (path === '/api/v1/inspect') return json({ status: 'needs-root', running: false, port: 9222 });
  };
}

browserTest('Top processes switches between CPU and resident RAM rankings', async (t) => {
  const routes = screenshotRoutes({ perf: {
    top: [{ name: 'cpu-heavy', cpu: 61.2 }],
    topRam: [{ name: 'memory-heavy', ramMb: 128.5 }],
    hist: { cpu: [], ram: [], gpu: [] },
  } });
  const harness = await startHarness(routes, screenshotFixture);
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(1_500);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });

  await assert.doesNotReject(page.locator('#topproc').getByText('cpu-heavy').waitFor());
  assert.equal(await page.locator('#topproc th.num').textContent(), '% CPU');
  assert.equal(await page.locator('.top-process-mode[data-mode="cpu"]').getAttribute('aria-pressed'), 'true');

  await page.locator('.top-process-mode[data-mode="ram"]').click();
  await assert.doesNotReject(page.locator('#topproc').getByText('memory-heavy').waitFor());
  assert.equal(await page.locator('#topproc th.num').textContent(), 'RAM');
  assert.equal(await page.locator('#topproc td.num').textContent(), '128.5 MB');
  assert.equal(await page.locator('.top-process-mode[data-mode="ram"]').getAttribute('aria-pressed'), 'true');
  assert.equal(await page.locator('.top-process-mode[data-mode="cpu"]').getAttribute('aria-pressed'), 'false');

  await page.locator('.top-process-mode[data-mode="cpu"]').click();
  await assert.doesNotReject(page.locator('#topproc').getByText('cpu-heavy').waitFor());
  assert.equal(await page.locator('#topproc th.num').textContent(), '% CPU');
  assert.equal(await page.locator('.top-process-mode[data-mode="cpu"]').getAttribute('aria-pressed'), 'true');
  assert.equal(await page.locator('.top-process-mode[data-mode="ram"]').getAttribute('aria-pressed'), 'false');
});

browserTest('Top processes explains when resident RAM is unavailable from an older helper', async (t) => {
  const routes = screenshotRoutes({ perf: {
    top: [{ name: 'cpu-heavy', cpu: 20 }], topRam: null, hist: { cpu: [], ram: [], gpu: [] },
  } });
  const harness = await startHarness(routes, screenshotFixture);
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(1_500);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.locator('.top-process-mode[data-mode="ram"]').click();

  await assert.doesNotReject(page.locator('#topproc').getByText('RAM data unavailable').waitFor());
  assert.equal(await page.locator('#topproc tr').count(), 2);
});

browserTest('Configure rejects an invalid brightness floor before a save request', async (t) => {
  let configPosts = 0;
  const schema = [
    { key: 'auto_brightness', label: 'Adaptive brightness', group: 'Display', type: 'BOOL', available: true },
    { key: 'auto_brightness_minimum_percent', label: 'Minimum brightness', group: 'Display', type: 'INT', min: 4, max: 95, available: true },
  ];
  const harness = await startHarness((path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') {
      if (request.method === 'POST') { configPosts++; return json({}); }
      return json({ settings: { auto_brightness: 'true', auto_brightness_minimum_percent: '12' }, ha_expose: {}, ha_auth: {} });
    }
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/auto-brightness') return json({ available: true, state: 'learning', sourceAvailable: true, latestLux: 42 });
    if (path.startsWith('/api/v1/auto-brightness/history')) return json({ points: [], bucket_minutes: 5 });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/health') return { body: 'ok cfg=test' };
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(1_500);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.locator('#cfg-auto_brightness_minimum_percent input').fill('2');
  await page.locator('#savebtn').click();
  await assert.rejects(page.waitForRequest((request) => request.url().endsWith('/api/v1/config') && request.method() === 'POST', { timeout: 200 }), /Timeout/);
  assert.equal(configPosts, 0);
  await assert.doesNotReject(page.locator('#cfg-msg').getByText('Minimum brightness must be between 4 and 95.').waitFor());
});

browserTest('Sensitivity preview survives transient HA source loss during save', async (t) => {
  const source = 'sensor.office_illuminance';
  const revision = 'ambient-source-revision';
  const nowMinute = Math.floor(Date.now() / 60000);
  const localDay = new Date(nowMinute * 60000).toISOString().slice(0, 10);
  const sensitivityQueries = [];
  let sensitivity = '50';
  let transientlyUnavailable = false;
  const schema = [
    { key: 'auto_brightness_ha_entity', label: 'Ambient source', group: 'Display', type: 'STRING', available: true },
    { key: 'auto_brightness', label: 'Adaptive brightness', group: 'Display', type: 'BOOL', available: true },
    { key: 'auto_brightness_sensitivity', label: 'Sensitivity', group: 'Display', type: 'INT', min: 0, max: 100, available: true },
  ];
  const harness = await startHarness(async (path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') {
      if (request.method === 'POST') {
        sensitivity = new URLSearchParams(await requestBody(request)).get('auto_brightness_sensitivity');
        transientlyUnavailable = true;
        return json({ ok: true });
      }
      return json({ settings: {
        auto_brightness_ha_entity: source,
        auto_brightness: 'true',
        auto_brightness_sensitivity: sensitivity,
      }, ha_expose: {}, ha_auth: {} });
    }
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/auto-brightness') return json({
      available: true, state: 'learning', sourceAvailable: !transientlyUnavailable,
      localSourcePresent: false, entityId: source,
      latestLux: transientlyUnavailable ? null : 42, sourceRevision: revision,
    });
    if (path === '/api/v1/auto-brightness/history') {
      const projected = Number(new URL(request.url, 'http://panel.test').searchParams.get('sensitivity'));
      sensitivityQueries.push(projected);
      return json({ sensitivity: projected, sourceRevision: revision, latestEpochMinute: nowMinute, bucket_minutes: 5, points: [{
        epochMinute: nowMinute, localDay, minuteOfDay: 720, dayAge: 0,
        observedMeanLux: 42, minLux: 40, maxLux: 44, expectedLux: 30,
        proposedBrightness: projected <= 10 ? 120 : 180,
      }] });
    }
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/health') return { body: 'ok cfg=test' };
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.locator('#auto-brightness-learning').waitFor();

  await page.locator('#cfg-auto_brightness_sensitivity input').fill('10');
  await page.waitForFunction(() => document.querySelector('#auto-brightness-learning')?.textContent.includes('Sensitivity 10'));
  assert.ok(sensitivityQueries.includes(10), `missing live sensitivity preview: ${sensitivityQueries}`);

  await page.locator('#savebtn').click();
  await page.locator('#cfg-msg').getByText('Saved.').waitFor();
  await page.locator('#auto-brightness-learning').waitFor();
  await page.locator('#auto-brightness-learning').getByText(`Waiting for ambient light… · Source: ${source}`).waitFor();
  assert.equal(await page.locator('#auto-brightness-learning').count(), 1);
  assert.equal(await page.locator('[data-config-group="Display"]').count(), 1);
  assert.equal(sensitivity, '10');
});
browserTest('Configure keeps an approval challenge visible instead of treating it as a save', async (t) => {
  const schema = [{ key: 'update_channel', label: 'Update channel', group: 'Updates', type: 'STRING', available: true }];
  const harness = await startHarness((path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') {
      if (request.method === 'POST') return json({ error: 'approval-required', message: 'Approve this change on the panel.' }, 202);
      return json({ settings: { update_channel: 'stable' }, ha_expose: {}, ha_auth: {} });
    }
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(1_500);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.locator('#cfg-update_channel input').fill('beta');
  await page.locator('#savebtn').click();
  await assert.doesNotReject(page.locator('#cfg-msg').getByText('Approve this change on the panel.').waitFor());
  await assert.doesNotReject(page.locator('#savebtn').isEnabled());
});

browserTest('Unrelated Configure saves preserve the connected HA identity without probing again', async (t) => {
  let statusGets = 0;
  const schema = [
    { key: 'ha_url', label: 'Home Assistant URL', group: 'Dashboard', type: 'STRING', available: true },
    { key: 'friendly_name', label: 'Panel name', group: 'System', type: 'STRING', available: true },
  ];
  const harness = await startHarness((path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') {
      if (request.method === 'POST') return json({});
      return json({
        settings: { ha_url: 'https://ha.example', friendly_name: 'Office' },
        ha_expose: {},
        ha_auth: { configured: true, oauth: true },
      });
    }
    if (path === '/api/v1/ha/oauth/status') {
      statusGets++;
      return json({ phase: 'connected', display_name: 'Panel User' });
    }
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/health') return { body: 'ok cfg=auth-status-test' };
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(1_500);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.locator('#cfg-ha-oauth').getByText('Connected as Panel User').waitFor();
  assert.equal(statusGets, 1);

  await page.locator('#cfg-friendly_name input').fill('Example panel');
  const saved = page.waitForResponse((response) => response.url().endsWith('/api/v1/config') && response.request().method() === 'POST');
  await page.locator('#savebtn').click();
  await saved;
  await page.locator('#cfg-ha-oauth').getByText('Connected as Panel User').waitFor();
  await page.waitForTimeout(100);
  assert.equal(statusGets, 1);
});

browserTest('Dashboard restores card height before delayed cold hydration then stores fresh geometry', async (t) => {
  let cold = false;
  let releaseHydration;
  const hydrationGate = new Promise((resolve) => { releaseHydration = resolve; });
  let releaseLiveCards;
  const liveCardsGate = new Promise((resolve) => { releaseLiveCards = resolve; });
  const harness = await startHarness(async (path) => {
    if (path === '/api/v1/info') {
      await hydrationGate;
      return json({ banners: '', shot: false, controls: '', cards: { infotbl: '<tr><td>private-hydrated-sentinel</td></tr>' } });
    }
    if (path === '/api/v1/perf') { await liveCardsGate; return json({ top: [{ name: 'steady-process', cpu: 12 }] }); }
    if (path === '/api/v1/sensors') { await liveCardsGate; return json({}); }
    if (path === '/api/v1/inspect') { await liveCardsGate; return json({ status: 'needs-root', running: false, port: 9222 }); }
  }, () => cardMemoryFixture(cold));
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 900, height: 700 } });
  page.setDefaultTimeout(3_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });

  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.waitForTimeout(1400);
  assert.equal(await page.evaluate(() => localStorage.getItem('ha-paneld.card-sizes.v1.dashboard')), null);
  releaseLiveCards();
  await page.waitForFunction(() => {
    const raw = localStorage.getItem('ha-paneld.card-sizes.v1.dashboard');
    return raw && JSON.parse(raw).cards['panel-info'] === 220;
  });
  cold = true;
  await page.reload({ waitUntil: 'domcontentloaded', timeout: 5_000 });
  assert.equal(await page.locator('[data-layout-key="panel-info"]').evaluate((card) => card.style.minHeight), '220px');
  assert.equal(await page.locator('[data-layout-key="panel-info"]').getAttribute('data-card-size-hint'), '220');

  releaseHydration();
  await page.getByText('private-hydrated-sentinel').waitFor();
  const alignmentBeforeRelease = await page.evaluate(() => window.__alignmentCalls);
  await page.waitForFunction(() => document.querySelector('[data-layout-key="panel-info"]').style.minHeight === '');
  assert.ok(await page.evaluate(() => window.__alignmentCalls) > alignmentBeforeRelease);
  const stored = await page.evaluate(() => localStorage.getItem('ha-paneld.card-sizes.v1.dashboard'));
  assert.equal(stored.includes('private-hydrated-sentinel'), false);
  const parsed = JSON.parse(stored);
  assert.equal(typeof parsed.cards['panel-info'], 'number');
  assert.ok(parsed.cards['panel-info'] >= 48 && parsed.cards['panel-info'] < 220);

  cold = false;
  await page.reload({ waitUntil: 'domcontentloaded', timeout: 5_000 });
  assert.equal(await page.locator('[data-layout-key="panel-info"]').evaluate((card) => card.style.minHeight), `${parsed.cards['panel-info']}px`);
});

browserTest('Dashboard retains remembered Top processes height until restarted sampler has CPU rows', async (t) => {
  let topAvailable = false;
  const topRows = Array.from({ length: 6 }, (_, index) => ({ name: `process-${index + 1}`, cpu: 20 - index }));
  const harness = await startHarness(async (path) => {
    if (path === '/api/v1/perf') return json({ top: topAvailable ? topRows : null });
    if (path === '/api/v1/sensors') return json({});
    if (path === '/api/v1/inspect') return json({ status: 'needs-root', running: false, port: 9222 });
  }, () => cardMemoryFixture(false));
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 900, height: 700 } });
  page.setDefaultTimeout(5_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.addInitScript(() => {
    localStorage.setItem('ha-paneld.card-sizes.v1.dashboard', JSON.stringify({
      schema: 1, epoch: 1, savedAt: Date.now(),
      context: { columns: 2, cardWidth: 400, rootFont: 16, dpr: 1 },
      cards: { 'top-processes': 260 },
    }));
  });

  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  const card = page.locator('[data-layout-key="top-processes"]');
  assert.equal(await card.evaluate((node) => node.style.minHeight), '260px');
  await page.waitForTimeout(1_600);
  assert.equal(await card.getAttribute('data-card-size-hint'), '260');
  assert.equal(await card.evaluate((node) => node.style.minHeight), '260px');

  topAvailable = true;
  await page.evaluate(() => perf());
  await page.locator('#topproc').getByText('process-6').waitFor();
  await page.waitForFunction(() => !document.querySelector('[data-layout-key="top-processes"]').hasAttribute('data-card-size-hint'));
  const stored = JSON.parse(await page.evaluate(() => localStorage.getItem('ha-paneld.card-sizes.v1.dashboard')));
  const steadyHeight = await card.evaluate((node) => Math.round(node.getBoundingClientRect().height));
  assert.ok(steadyHeight > 80);
  assert.equal(stored.cards['top-processes'], steadyHeight);
});

browserTest('Dashboard eventually releases Top processes hint when CPU ranking stays unavailable', async (t) => {
  const harness = await startHarness(async (path) => {
    if (path === '/api/v1/perf') return json({ top: null });
    if (path === '/api/v1/sensors') return json({});
    if (path === '/api/v1/inspect') return json({ status: 'needs-root', running: false, port: 9222 });
  }, () => cardMemoryFixture(false));
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 900, height: 700 } });
  page.setDefaultTimeout(5_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.addInitScript(() => {
    localStorage.setItem('ha-paneld.card-sizes.v1.dashboard', JSON.stringify({
      schema: 1, epoch: 1, savedAt: Date.now(),
      context: { columns: 2, cardWidth: 400, rootFont: 16, dpr: 1 },
      cards: { 'top-processes': 260 },
    }));
  });

  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  const card = page.locator('[data-layout-key="top-processes"]');
  assert.equal(await card.getAttribute('data-card-size-hint'), '260');
  await page.evaluate(async () => { for (let poll = 0; poll < 11; poll++) await perf(); });
  await page.waitForFunction(() => !document.querySelector('[data-layout-key="top-processes"]').hasAttribute('data-card-size-hint'));
  assert.equal(await card.evaluate((node) => node.style.minHeight), `${await card.evaluate((node) => node._hwm)}px`);
});

browserTest('Configure reapplies remembered card heights across its client-side render cycle', async (t) => {
  let compact = false, releaseCold;
  let coldGate = Promise.resolve();
  const harness = await startHarness(async (path) => {
    if (compact && ['/api/v1/radio', '/api/v1/auto-brightness', '/api/v1/auto-brightness/history'].includes(path)) await coldGate;
    if (path === '/api/v1/config/schema') return json([{ key: 'dark_mode', type: 'BOOL', group: 'Display', label: 'Dark mode', available: true }]);
    if (path === '/api/v1/config') return json({ settings: { dark_mode: false }, ha_expose: {}, ha_auth: {} });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/auto-brightness') return json({ available: false, sourceRevision: 1 });
    if (path === '/api/v1/auto-brightness/history') return json({ points: [], sourceRevision: 1 });
  }, () => configureCardMemoryFixture(compact));
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 900, height: 700 } });
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => JSON.parse(localStorage.getItem('ha-paneld.card-sizes.v1.configure') || '{}').cards?.['configure-display'] === 220);
  compact = true; coldGate = new Promise((resolve) => { releaseCold = resolve; });
  await page.reload({ waitUntil: 'domcontentloaded' });
  await page.locator('[data-layout-key="configure-display"]').waitFor();
  assert.equal(await page.locator('[data-layout-key="configure-display"]').evaluate((card) => card.style.minHeight), '220px');
  releaseCold();
  await page.waitForFunction(() => document.querySelector('[data-layout-key="configure-display"]').style.minHeight === '');
  await page.waitForFunction(() => JSON.parse(localStorage.getItem('ha-paneld.card-sizes.v1.configure')).cards['configure-display'] === 100);
});

browserTest('Install holds remembered heights until its initial version and radio loads settle', async (t) => {
  let compact = false, releaseCold;
  let coldGate = Promise.resolve();
  const harness = await startHarness(async (path) => {
    if (compact && ['/api/v1/radio', '/api/v1/install/versions'].includes(path)) await coldGate;
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/install/versions') return json({ versions: [{ tag: 'v0.9.6', version: '0.9.6', installable: true, action: 'Install' }] });
  }, () => installCardMemoryFixture(compact));
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 900, height: 700 } });
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => JSON.parse(localStorage.getItem('ha-paneld.card-sizes.v1.install') || '{}').cards?.['managed-components'] === 220);
  compact = true; coldGate = new Promise((resolve) => { releaseCold = resolve; });
  await page.reload({ waitUntil: 'domcontentloaded' });
  assert.equal(await page.locator('[data-layout-key="managed-components"]').evaluate((card) => card.style.minHeight), '220px');
  await page.waitForTimeout(1400);
  assert.equal(await page.locator('[data-layout-key="managed-components"]').evaluate((card) => card.style.minHeight), '220px');
  releaseCold();
  await page.waitForFunction(() => document.querySelector('[data-layout-key="managed-components"]').style.minHeight === '');
  await page.waitForFunction(() => JSON.parse(localStorage.getItem('ha-paneld.card-sizes.v1.install')).cards['managed-components'] === 100);
});

browserTest('OAuth, auto-brightness and proximity journeys use their bounded local endpoints', async (t) => {
  const calls = [];
  let proximity = { present: true, phase: 'ready', requiredGestures: 3, acceptedGestures: 3, session: { active: false } };
  const schema = [
    { key: 'ha_url', label: 'Home Assistant URL', group: 'Dashboard', type: 'STRING', available: true },
    { key: 'ha_token', label: 'Token', group: 'Dashboard', type: 'STRING', secret: true, available: true },
    { key: 'auto_brightness', label: 'Adaptive brightness', group: 'Display', type: 'BOOL', available: true },
  ];
  const harness = await startHarness((path, request) => {
    calls.push(`${request.method} ${path}`);
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({ settings: { ha_url: 'https://ha.example', ha_token: 'old', auto_brightness: 'true' }, ha_expose: {}, ha_auth: {} });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/ha/oauth/start') return json({ authorization_url: 'https://ha.example/auth/authorize?state=opaque' });
    if (path === '/api/v1/auto-brightness') return json({ available: true, state: 'learning', source_label: 'panel sensor', sourceAvailable: true, latestLux: 42 });
    if (path.startsWith('/api/v1/auto-brightness/history')) return json({ points: [], bucket_minutes: 5 });
    if (path === '/api/v1/proximity') return json(proximity);
    if (path === '/api/v1/proximity/test') { proximity = { ...proximity, session: { active: true, kind: 'test', message: 'Waiting for one wave…' } }; return json(proximity); }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(1_500);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.getByRole('button', { name: 'Connect' }).click();
  await assert.doesNotReject(page.getByText('Sign-in link ready. Open it normally or copy it into a private window.').waitFor());
  await assert.doesNotReject(page.getByRole('link', { name: 'Open sign-in' }).getAttribute('href').then((href) => assert.match(href, /state=opaque/)));
  await page.getByRole('button', { name: 'Test a wave' }).click();
  await assert.doesNotReject(page.getByText('Waiting for one wave…').waitFor());
  assert.ok(calls.includes('POST /api/v1/ha/oauth/start'));
  assert.ok(calls.includes('GET /api/v1/auto-brightness'));
  assert.ok(calls.includes('GET /api/v1/auto-brightness/history'));
  assert.ok(calls.includes('POST /api/v1/proximity/test'));
});

browserTest('Loaded screenshot opens a fitted modal and one exact click replaces both images', async (t) => {
  const calls = [];
  const harness = await startHarness(screenshotRoutes({ calls }), () => screenshotFixture());
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 480, height: 420 } });
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'load', timeout: 5_000 });
  const link = page.locator('#shotcard .shot');
  await link.click();
  const dialog = page.locator('#screenshot-dialog');
  await dialog.waitFor({ state: 'visible' });
  await page.getByRole('dialog', { name: 'Screenshot · live panel' }).waitFor();
  assert.equal(await page.getByRole('button', { name: 'Close' }).evaluate((element) => document.activeElement === element), true);
  const headerLayout = await dialog.locator('.screenshot-dialog-bar').evaluate((bar) => {
    const title = bar.querySelector('h2').getBoundingClientRect();
    const refresh = bar.querySelector('.screenshot-dialog-refresh').getBoundingClientRect();
    const close = bar.querySelector('.screenshot-dialog-close').getBoundingClientRect();
    return { titleLeft: title.left, refreshLeft: refresh.left, closeLeft: close.left, closeRight: close.right, barRight: bar.getBoundingClientRect().right };
  });
  assert.ok(headerLayout.titleLeft < headerLayout.refreshLeft, `title should be left aligned: ${JSON.stringify(headerLayout)}`);
  assert.ok(headerLayout.refreshLeft < headerLayout.closeLeft, `Close should follow Refresh: ${JSON.stringify(headerLayout)}`);
  assert.ok(headerLayout.closeRight <= headerLayout.barRight, `Close should be rightmost: ${JSON.stringify(headerLayout)}`);
  const image = page.locator('#screenshot-dialog-image');
  const box = await image.boundingBox();
  assert.ok(box.width <= 478 && box.height <= 320, `unexpected modal image bounds ${JSON.stringify(box)}`);
  const natural = await image.evaluate((element) => ({ width: element.naturalWidth, height: element.naturalHeight }));
  const clientX = Math.floor(box.x + box.width * 0.37);
  const clientY = Math.floor(box.y + box.height * 0.61);
  const x = Math.floor((clientX - box.x) * natural.width / box.width);
  const y = Math.floor((clientY - box.y) * natural.height / box.height);
  await page.mouse.click(clientX, clientY);
  await page.locator('#screenshot-dialog-status').getByText('Screenshot updated.').waitFor();
  assert.deepEqual(calls.filter((call) => call.path === '/api/v1/input'), [
    { method: 'POST', path: '/api/v1/input', body: `x=${x}&y=${y}&capture=1`, contentType: 'application/x-www-form-urlencoded' },
  ]);
  assert.equal(await dialog.getAttribute('data-input-id'), 'tap-17');
  assert.equal(await dialog.getAttribute('data-input-route'), 'accessibility');
  assert.equal(await dialog.getAttribute('data-screenshot-route'), 'daemon');
  assert.equal(await dialog.getAttribute('data-screenshot-id'), 'a'.repeat(64));
  const cardSrc = await page.locator('#shotcard img').getAttribute('src');
  assert.match(cardSrc, /^blob:/);
  assert.equal(await image.getAttribute('src'), cardSrc);

  await page.getByRole('button', { name: 'Close' }).click();
  assert.equal(await link.evaluate((element) => document.activeElement === element), true);
  await link.click();
  assert.ok((await image.evaluate((element) => element.naturalWidth)) > 0);
  await page.keyboard.press('Escape');
  assert.equal(await link.evaluate((element) => document.activeElement === element), true);
});

browserTest('Keyboard activation opens the overlay without injecting a tap', async (t) => {
  const calls = [];
  const harness = await startHarness(screenshotRoutes({ calls }), () => screenshotFixture());
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'load', timeout: 5_000 });
  await page.locator('#shotcard .shot').focus();
  await page.keyboard.press('Enter');
  await page.locator('#screenshot-dialog').waitFor({ state: 'visible' });
  await page.keyboard.press('Enter');
  await page.waitForTimeout(100);
  assert.equal(calls.filter((call) => call.path === '/api/v1/input').length, 0);
});

browserTest('Missing, modified and unsupported screenshot activations retain the ordinary link', async (t) => {
  const calls = [];
  const harness = await startHarness(screenshotRoutes({ calls }), () => screenshotFixture({ supported: false }));
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'load', timeout: 5_000 });
  const unsupportedPopupPromise = page.waitForEvent('popup');
  await page.locator('#shotcard .shot').click();
  const unsupportedPopup = await unsupportedPopupPromise;
  await unsupportedPopup.waitForLoadState('load');
  assert.equal(new URL(unsupportedPopup.url()).pathname, '/api/v1/screenshot.png');
  await unsupportedPopup.close();

  await page.evaluate(() => { HTMLDialogElement.prototype.showModal = function() { this.setAttribute('open', ''); }; setupScreenshotOverlay(); });
  await page.locator('#shotcard .shot').evaluate((element) => element.classList.remove('loaded'));
  const missingPopupPromise = page.waitForEvent('popup');
  await page.locator('#shotcard .shot').click();
  const missingPopup = await missingPopupPromise;
  await missingPopup.waitForLoadState('load');
  assert.equal(new URL(missingPopup.url()).pathname, '/api/v1/screenshot.png');
  await missingPopup.close();
  const fallback = await page.locator('#shotcard .shot').evaluate((element) => {
    const image = element.querySelector('img');element.classList.add('loaded');image.src='/initial.png';
    const modified = new MouseEvent('click', { bubbles: true, cancelable: true, button: 0, ctrlKey: true }); element.dispatchEvent(modified);
    return { modified: modified.defaultPrevented };
  });
  assert.deepEqual(fallback, { modified: false });
  assert.equal(calls.filter((call) => call.path === '/api/v1/input').length, 0);
});

browserTest('Hardened mode opens a view-only overlay and refuses browser input', async (t) => {
  const calls = [];
  const harness = await startHarness(screenshotRoutes({ calls }), () => screenshotFixture({ hardened: true }));
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'load', timeout: 5_000 });
  await page.locator('#shotcard .shot').click();
  await page.locator('#screenshot-dialog').waitFor({ state: 'visible' });
  assert.equal(await page.locator('#screenshot-dialog-status').textContent(), 'View only — remote input is disabled in Hardened mode.');
  await page.locator('#screenshot-dialog-image').click({ position: { x: 5, y: 5 } });
  await page.waitForTimeout(100);
  assert.equal(calls.filter((call) => call.path === '/api/v1/input').length, 0);
});

browserTest('Tap admission failure retains the image and does not capture or retry', async (t) => {
  const calls = [];
  const harness = await startHarness(screenshotRoutes({ calls, input: async () => json({ error: 'remote-input-disabled' }, 403) }), () => screenshotFixture());
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'load', timeout: 5_000 });
  const original = await page.locator('#shotcard img').getAttribute('src');
  await page.locator('#shotcard .shot').click();
  await page.locator('#screenshot-dialog-image').click({ position: { x: 8, y: 8 } });
  await page.getByText('Remote input is disabled in Hardened mode.').waitFor();
  assert.equal(await page.locator('#shotcard img').getAttribute('src'), original);
  assert.equal(calls.filter((call) => call.path === '/api/v1/input').length, 1);
  assert.equal(calls.filter((call) => call.path === '/api/v1/screenshot.png').length, 0);
});

browserTest('Confirmed capture failure performs one delayed safe GET and never retries the tap', async (t) => {
  const calls = [];
  const harness = await startHarness(screenshotRoutes({
    calls,
    input: async () => ({ status: 503, headers: { 'content-type': 'application/json', 'X-ha-paneld-Input-Route': 'su' }, body: '{}' }),
  }), () => screenshotFixture());
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_500);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'load', timeout: 5_000 });
  await page.locator('#shotcard .shot').click();
  await page.locator('#screenshot-dialog-image').click({ position: { x: 8, y: 8 } });
  await page.getByText('Screenshot updated.').waitFor();
  assert.equal(calls.filter((call) => call.path === '/api/v1/input').length, 1);
  assert.equal(calls.filter((call) => call.path === '/api/v1/screenshot.png').length, 1);
});

browserTest('Ambiguous network failure performs one safe screenshot GET without retrying input', async (t) => {
  const calls = [];
  const harness = await startHarness(screenshotRoutes({ calls }), () => screenshotFixture());
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_500);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'load', timeout: 5_000 });
  await page.evaluate(() => {
    const realFetch = window.fetch;
    window.inputAttempts = 0;
    window.fetch = (url, options) => {
      if (url === '/api/v1/input') { window.inputAttempts++; return Promise.reject(new TypeError('network unavailable')); }
      return realFetch(url, options);
    };
  });
  await page.locator('#shotcard .shot').click();
  await page.locator('#screenshot-dialog-image').click({ position: { x: 8, y: 8 } });
  await page.getByText('Tap outcome unknown; current screenshot refreshed.').waitFor();
  assert.equal(await page.evaluate(() => window.inputAttempts), 1);
  assert.equal(calls.filter((call) => call.path === '/api/v1/input').length, 0);
  assert.equal(calls.filter((call) => call.path === '/api/v1/screenshot.png').length, 1);
});

browserTest('An expired queued tap is reported as not accepted without a fallback capture', async (t) => {
  const calls = [];
  const harness = await startHarness(screenshotRoutes({
    calls,
    input: async () => json({ ok: false, error: 'tap-expired' }, 504),
  }), () => screenshotFixture());
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'load', timeout: 5_000 });
  await page.locator('#shotcard .shot').click();
  await page.locator('#screenshot-dialog-image').click({ position: { x: 8, y: 8 } });
  await page.getByText('Tap was not accepted.').waitFor();
  assert.equal(calls.filter((call) => call.path === '/api/v1/input').length, 1);
  assert.equal(calls.filter((call) => call.path === '/api/v1/screenshot.png').length, 0);
});

browserTest('Duplicate overlay clicks stay single-flight while the combined response is pending', async (t) => {
  const calls = [];
  const harness = await startHarness(screenshotRoutes({ calls, input: async (_path, _request, fresh) => {
    await new Promise((resolve) => setTimeout(resolve, 300));
    return { status: 200, headers: { 'content-type': 'image/png' }, body: await fresh };
  } }), () => screenshotFixture());
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'load', timeout: 5_000 });
  await page.locator('#shotcard .shot').click();
  const image = page.locator('#screenshot-dialog-image');
  await image.dblclick({ position: { x: 9, y: 9 }, delay: 20 });
  await page.getByText('Screenshot updated.').waitFor();
  assert.equal(calls.filter((call) => call.path === '/api/v1/input').length, 1);
});

browserTest('Configure accepts durable pending apply without retrying or losing reconciliation', async (t) => {
  let pending = {}, posts = 0, posted = '', configGetsAfterPost = 0;
  const schema = [{ key: 'touch_sound', label: 'Touch sound', group: 'Behaviour', type: 'BOOL', available: true }];
  const harness = await startHarness(async (path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config' && request.method === 'POST') {
      posts++; posted = await requestBody(request); pending = { touch_sound: 'true' };
      return json({
        ok: true, status: 'saved-apply-pending', applied: [], pending: ['touch_sound'], rejected: [],
        message: 'Settings were saved; retrying hardware apply: touch_sound. If they remain pending, restart the service.',
        settings: { touch_sound: false }, apply_pending: pending, ha_expose: {}, ha_auth: { configured: false },
      }, 202);
    }
    if (path === '/api/v1/config') {
      if (posts && ++configGetsAfterPost > 1) pending = {};
      if (configGetsAfterPost === 3) return { status: 503, headers: { 'content-type': 'application/json' }, body: 'not json' };
      return json({
        settings: { touch_sound: configGetsAfterPost > 1 }, apply_pending: pending,
        ha_expose: {}, ha_auth: { configured: false },
      });
    }
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/health') return { body: 'ok cfg=pending' };
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });

  await page.locator('#cfg-touch_sound [role=switch]').click();
  await page.locator('#savebtn').click();
  await page.getByText('Settings were saved; retrying hardware apply: touch_sound. If they remain pending, restart the service.').waitFor();
  assert.equal(new URLSearchParams(posted).get('touch_sound'), 'true');
  assert.equal(Array.from(new URLSearchParams(posted).keys()).length, 1);
  assert.equal(posts, 1);
  assert.equal(await page.locator('#savebtn').isDisabled(), true);
  assert.equal(await page.locator('#cfg-touch_sound [role=switch]').getAttribute('aria-checked'), 'true');
  await page.evaluate(() => window.cfgSave());
  await page.waitForTimeout(50);
  assert.equal(posts, 1);
  await page.getByText('Saved settings are now applied.').waitFor({ timeout: 9_000 });
  assert.ok(configGetsAfterPost >= 5);
  assert.equal(await page.locator('#cfg-touch_sound [role=switch]').getAttribute('aria-checked'), 'true');
});

browserTest('Persistent pending apply does not rebuild auto-sleep or reset the HA identity', async (t) => {
  let configGets = 0;
  let historyGets = 0;
  let autoSleepStatusGets = 0;
  let prerequisiteGets = 0;
  let statusGets = 0;
  let pendingDesired = 'true';
  const schema = [
    { key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true },
    { key: 'touch_sound', label: 'Touch sound', group: 'Behaviour', type: 'BOOL', available: true },
    { key: 'ha_url', label: 'Home Assistant URL', group: 'Dashboard', type: 'STRING', available: true },
  ];
  const harness = await startHarness((path) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') {
      configGets++;
      return json({
        settings: { auto_sleep: true, touch_sound: false, ha_url: 'https://ha.example' },
        apply_pending: { touch_sound: pendingDesired }, ha_expose: {}, ha_auth: { configured: true, oauth: true },
      });
    }
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/ha/oauth/status') { statusGets++; return json({ phase: 'connected', display_name: 'Panel User' }); }
    if (path === '/api/v1/auto-sleep/prerequisite') { prerequisiteGets++; return json({ eligible: true, phase: 'assigned', area_name: 'Office' }); }
    if (path === '/api/v1/auto-sleep') { autoSleepStatusGets++; return json({ enabled: true, available: true, phase: 'live', area_name: 'Office', source_count: 1 }); }
    if (path === '/api/v1/auto-sleep/history') { historyGets++; return json(autoSleepHistory({ hours: 24 })); }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.locator('#cfg-ha-oauth').getByText('Connected as Panel User').waitFor();
  await page.locator('.auto-sleep-lane.source').waitFor();
  const snapshot = await page.locator('.auto-sleep-chart-snapshot').elementHandle();

  await page.waitForTimeout(3_300);

  assert.equal(configGets, 2);
  assert.equal(statusGets, 1);
  assert.equal(prerequisiteGets, 1);
  assert.equal(autoSleepStatusGets, 1);
  assert.equal(historyGets, 1);
  assert.equal(await snapshot.evaluate((node) => node.isConnected), true);
  assert.equal(await page.locator('.auto-sleep-loading-overlay').isVisible(), false);
  assert.equal(await page.locator('#cfg-ha-oauth .ha-oauth-status').textContent(), 'Connected as Panel User');

  pendingDesired = 'false';
  await page.waitForFunction(() => document.querySelector('#cfg-touch_sound [role=switch]').getAttribute('aria-checked') === 'false', null, { timeout: 5_000 });
  await page.waitForTimeout(300);
  assert.ok(configGets >= 4);
  assert.equal(statusGets, 1);
  assert.equal(prerequisiteGets, 1);
  assert.equal(autoSleepStatusGets, 1);
  assert.equal(historyGets, 1);
  assert.equal(await page.locator('#cfg-touch_sound .apply-pending-status').count(), 1);
  assert.equal(await page.locator('#cfg-ha-oauth .ha-oauth-status').textContent(), 'Connected as Panel User');
  assert.equal(await page.locator('#auto-sleep-prerequisite-status').textContent(), 'Home Assistant Area: Office');
});

browserTest('Configure keeps newer edits while a pending save later converges', async (t) => {
  let pending = {}, posts = 0, getsAfterPost = 0, releasePost;
  const postGate = new Promise((resolve) => { releasePost = resolve; });
  const schema = [{ key: 'touch_sound', label: 'Touch sound', group: 'Behaviour', type: 'BOOL', available: true }];
  const harness = await startHarness(async (path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config' && request.method === 'POST') {
      posts++; pending = { touch_sound: 'true' }; await postGate;
      return json({
        ok: true, status: 'saved-apply-pending', applied: [], pending: ['touch_sound'], rejected: [],
        message: 'Settings were saved; retrying hardware apply: touch_sound. If they remain pending, restart the service.',
      }, 202);
    }
    if (path === '/api/v1/config') {
      if (posts && ++getsAfterPost > 0) pending = {};
      return json({
        settings: { touch_sound: getsAfterPost > 0 }, apply_pending: pending,
        ha_expose: {}, ha_auth: { configured: false },
      });
    }
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });

  const toggle = page.locator('#cfg-touch_sound [role=switch]');
  await toggle.click();
  await page.locator('#savebtn').click();
  await toggle.click();
  releasePost();
  await page.getByText(/Newer changes still need saving/).waitFor();
  await page.getByText('Saved settings are now applied; newer changes still need saving.').waitFor({ timeout: 5_000 });
  assert.equal(await toggle.getAttribute('aria-checked'), 'false');
  assert.equal(await page.locator('#savebtn').isDisabled(), false);
  assert.equal(posts, 1);
});

browserTest('Configure reconciles structured partial failure instead of blindly retrying', async (t) => {
  let posts = 0, friendly = 'Panel';
  const schema = [
    { key: 'friendly_name', label: 'Panel name', group: 'System', type: 'STRING', available: true },
    { key: 'touch_sound', label: 'Touch sound', group: 'Behaviour', type: 'BOOL', available: true },
  ];
  const harness = await startHarness(async (path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config' && request.method === 'POST') {
      posts++; const body = new URLSearchParams(await requestBody(request)); friendly = body.get('friendly_name');
      return json({
        ok: false, status: 'saved-partial', applied: ['friendly_name'], pending: [], rejected: ['touch_sound'],
        message: 'Panel name was saved, but touch_sound could not be durably accepted.',
      }, 500);
    }
    if (path === '/api/v1/config') return json({
      settings: { friendly_name: friendly, touch_sound: false }, apply_pending: {}, ha_expose: {}, ha_auth: { configured: false },
    });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });

  await page.locator('#cfg-friendly_name input').fill('Kitchen');
  await page.locator('#cfg-touch_sound [role=switch]').click();
  await page.locator('#savebtn').click();
  await page.getByText('Panel name was saved, but touch_sound could not be durably accepted.').waitFor();
  assert.equal(await page.locator('#cfg-friendly_name input').inputValue(), 'Kitchen');
  assert.equal(await page.locator('#cfg-touch_sound [role=switch]').getAttribute('aria-checked'), 'false');
  assert.equal(await page.locator('#savebtn').isDisabled(), true);
  assert.equal(posts, 1);
});

browserTest('An older background capture cannot overwrite the post-tap screenshot', async (t) => {
  const calls = [];
  const oldCapture = readFile(join(process.cwd(), '..', 'docs', 'img', 'ui-dashboard-dark.png'));
  const harness = await startHarness(screenshotRoutes({ calls, screenshot: async () => {
    await new Promise((resolve) => setTimeout(resolve, 350));
    return { status: 200, headers: { 'content-type': 'image/png' }, body: await oldCapture };
  } }), () => screenshotFixture());
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'load', timeout: 5_000 });
  await page.evaluate(() => refreshScreenshot(document.getElementById('shotcard')));
  await page.locator('#shotcard .shot').click();
  await page.locator('#screenshot-dialog-image').click({ position: { x: 8, y: 8 } });
  await page.getByText('Screenshot updated.').waitFor();
  const postTapSize = await page.locator('#shotcard img').evaluate((image) => [image.naturalWidth, image.naturalHeight]);
  await page.waitForTimeout(450);
  assert.deepEqual(await page.locator('#shotcard img').evaluate((image) => [image.naturalWidth, image.naturalHeight]), postTapSize);
  assert.equal(calls.filter((call) => call.path === '/api/v1/input').length, 1);
  assert.equal(calls.filter((call) => call.path === '/api/v1/screenshot.png').length, 1);
});

browserTest('Auto-sleep requires an assigned Area before OFF can be switched ON', async (t) => {
  let prerequisiteCalls = 0;
  let prerequisiteAssigned = false;
  const firstPrerequisite = deferred();
  const schema = [{
    key: 'auto_sleep', label: 'Auto sleep', help: 'obsolete passive copy', group: 'Behaviour',
    type: 'BOOL', available: true,
  }];
  const harness = await startHarness((path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({ settings: { auto_sleep: 'false' }, ha_expose: {}, ha_auth: { configured: true } });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/auto-sleep/prerequisite') {
      prerequisiteCalls++;
      return prerequisiteAssigned ? json({ eligible: true, phase: 'assigned', area_name: 'Studio' }) : firstPrerequisite.promise;
    }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });

  const toggle = page.locator('#cfg-auto_sleep [role=switch]');
  const prerequisiteStatus = page.locator('#auto-sleep-prerequisite-status');
  await prerequisiteStatus.waitFor();
  assert.equal(await prerequisiteStatus.textContent(), 'Checking this panel’s Home Assistant Area…');
  assert.equal(await toggle.getAttribute('aria-disabled'), 'true');
  assert.equal(await toggle.getAttribute('tabindex'), '0');
  assert.equal(await toggle.getAttribute('aria-describedby'), 'auto-sleep-prerequisite-status');
  await toggle.press('Enter');
  assert.equal(await toggle.getAttribute('aria-checked'), 'false');
  await toggle.click({ force: true });
  assert.equal(await toggle.getAttribute('aria-checked'), 'false');

  firstPrerequisite.resolve(json({ eligible: false, phase: 'unassigned', area_name: null }));
  await page.getByText('Assign this panel to a Home Assistant Area before enabling Auto sleep.').waitFor();
  assert.equal(await toggle.getAttribute('aria-disabled'), 'true');
  assert.match(await page.locator('#cfg-auto_sleep').innerText(), /Automatically wake the panel when activity is detected and switch the screen off/);
  assert.doesNotMatch(await page.locator('[data-config-group="Behaviour"] h2').innerText(), /Android\/app behaviour/);

  prerequisiteAssigned = true;
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await page.getByText('Home Assistant Area: Studio').waitFor();
  assert.equal(await toggle.getAttribute('aria-disabled'), 'false');
  await toggle.click();
  assert.equal(await toggle.getAttribute('aria-checked'), 'true');
  assert.ok(prerequisiteCalls >= 2);
});

browserTest('Auto-sleep blank-Area discovery failure is terminal without cold-chart reflow', async (t) => {
  let statusCalls = 0;
  let historyCalls = 0;
  const terminal = {
    enabled: true, available: false, phase: 'discovery_failed', reason: 'discovery_failed',
    detail: 'registry_projection', area_name: '', source_count: 0, discovered_source_count: 0,
  };
  const schema = [
    { key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true },
    { key: 'friendly_name', label: 'Panel name', group: 'System', type: 'STRING', available: true },
  ];
  const harness = await startHarness(async (path) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({
      settings: { auto_sleep: 'true', friendly_name: 'Panel' }, ha_expose: {}, ha_auth: { configured: true },
    });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/config/home-dashboards') return json({ items: [], default: {} });
    if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: true, phase: 'assigned', area_name: 'Office' });
    if (path === '/api/v1/auto-sleep') {
      statusCalls++;
      // If a faulty readiness loop starts, retain its Loading state long enough for a frame sample.
      if (statusCalls > 2) await new Promise((resolve) => setTimeout(resolve, 300));
      return json(terminal);
    }
    if (path === '/api/v1/auto-sleep/history') { historyCalls++; return json({ available: false, detail: 'runtime_unavailable' }); }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 480, height: 800 } });
  page.setDefaultTimeout(3_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Office', { exact: true }).waitFor();
  await page.getByText('Auto-sleep source discovery failed. Check the Home Assistant connection.').waitFor();
  assert.equal(statusCalls, 2);

  const panel = await page.locator('#auto-sleep-status').elementHandle();
  const chart = await page.locator('#auto-sleep-chart').elementHandle();
  const content = await page.locator('.auto-sleep-chart-content').elementHandle();
  const snapshot = await page.locator('.auto-sleep-chart-snapshot').elementHandle();
  await page.evaluate(() => {
    window.__autoSleepColdSamples = [];
    window.__autoSleepSummaryMutations = 0;
    new MutationObserver(() => { window.__autoSleepSummaryMutations++; })
      .observe(document.querySelector('#auto-sleep-summary'), { childList: true, characterData: true, subtree: true });
    function sample() {
      const activity = document.querySelector('#auto-sleep-status').getBoundingClientRect();
      const chartBox = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
      const system = document.querySelector('[data-config-group="System"]').getBoundingClientRect();
      window.__autoSleepColdSamples.push({ panelHeight: activity.height, chartHeight: chartBox.height, systemY: system.y });
      window.__autoSleepColdFrame = requestAnimationFrame(sample);
    }
    window.__autoSleepColdFrame = requestAnimationFrame(sample);
  });
  await page.waitForTimeout(1_600);
  const observed = await page.evaluate(() => {
    cancelAnimationFrame(window.__autoSleepColdFrame);
    const ranges = {};
    for (const key of ['panelHeight', 'chartHeight', 'systemY']) {
      const values = window.__autoSleepColdSamples.map((sample) => sample[key]);
      ranges[key] = Math.max(...values) - Math.min(...values);
    }
    return { ranges, summaryMutations: window.__autoSleepSummaryMutations };
  });

  assert.equal(statusCalls, 2);
  assert.equal(historyCalls, 0);
  assert.equal(observed.summaryMutations, 0);
  assert.ok(Object.values(observed.ranges).every((range) => range <= 0.5), JSON.stringify(observed.ranges));
  assert.equal(await panel.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-status')), true);
  assert.equal(await chart.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-chart')), true);
  assert.equal(await content.evaluate((node) => node.isConnected && node === document.querySelector('.auto-sleep-chart-content')), true);
  assert.equal(await snapshot.evaluate((node) => node.isConnected && node === document.querySelector('.auto-sleep-chart-snapshot')), true);
  assert.equal(await page.locator('.auto-sleep-loading-overlay').isVisible(), false);
  assert.equal(await page.locator('.auto-sleep-empty').textContent(), 'No replay data');
});

browserTest('Auto-sleep blank-Area transport failure recovers without retry reflow', async (t) => {
  let statusCalls = 0;
  let historyCalls = 0;
  const recoveredStatus = deferred();
  const transportFailure = {
    enabled: true, available: false, phase: 'discovery_failed', reason: 'discovery_failed',
    detail: 'registry_transport', area_name: '', source_count: 0, discovered_source_count: 0,
  };
  const schema = [
    { key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true },
    { key: 'friendly_name', label: 'Panel name', group: 'System', type: 'STRING', available: true },
  ];
  const harness = await startHarness((path) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({
      settings: { auto_sleep: 'true', friendly_name: 'Panel' }, ha_expose: {}, ha_auth: { configured: true },
    });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/config/home-dashboards') return json({ items: [], default: {} });
    if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: true, phase: 'assigned', area_name: 'Office' });
    if (path === '/api/v1/auto-sleep') {
      statusCalls++;
      return statusCalls <= 2 ? json(transportFailure) : recoveredStatus.promise;
    }
    if (path === '/api/v1/auto-sleep/history') { historyCalls++; return json(autoSleepHistory({ hours: 24 })); }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 480, height: 800 } });
  page.setDefaultTimeout(7_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Office', { exact: true }).waitFor();
  for (let attempt = 0; attempt < 40 && statusCalls < 2; attempt++) await page.waitForTimeout(25);
  assert.equal(statusCalls, 2);
  const settledSummary = await page.locator('#auto-sleep-summary').textContent();

  await page.evaluate(() => {
    window.__autoSleepTransportSamples = [];
    window.__autoSleepTransportSummaryMutations = 0;
    new MutationObserver(() => { window.__autoSleepTransportSummaryMutations++; })
      .observe(document.querySelector('#auto-sleep-summary'), { childList: true, characterData: true, subtree: true });
    function sample() {
      const activity = document.querySelector('#auto-sleep-status').getBoundingClientRect();
      const chartBox = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
      const system = document.querySelector('[data-config-group="System"]').getBoundingClientRect();
      window.__autoSleepTransportSamples.push({ panelHeight: activity.height, chartHeight: chartBox.height, systemY: system.y });
      window.__autoSleepTransportFrame = requestAnimationFrame(sample);
    }
    window.__autoSleepTransportFrame = requestAnimationFrame(sample);
  });
  for (let attempt = 0; attempt < 120 && statusCalls < 3; attempt++) await page.waitForTimeout(50);
  assert.equal(statusCalls, 3);
  const observed = await page.evaluate(() => {
    cancelAnimationFrame(window.__autoSleepTransportFrame);
    const ranges = {};
    for (const key of ['panelHeight', 'chartHeight', 'systemY']) {
      const values = window.__autoSleepTransportSamples.map((sample) => sample[key]);
      ranges[key] = Math.max(...values) - Math.min(...values);
    }
    return { ranges, summaryMutations: window.__autoSleepTransportSummaryMutations };
  });
  assert.equal(await page.locator('#auto-sleep-summary').textContent(), settledSummary);
  assert.equal(observed.summaryMutations, 0);
  assert.ok(Object.values(observed.ranges).every((range) => range <= 0.5), JSON.stringify(observed.ranges));
  assert.equal(historyCalls, 0);

  recoveredStatus.resolve(json({
    enabled: true, available: true, phase: 'live', reason: 'clear', area_name: 'Office', source_count: 1,
  }));
  await page.locator('.auto-sleep-lane.source').waitFor();
  assert.equal(statusCalls, 3);
  assert.equal(historyCalls, 1);
  assert.equal(await page.locator('.auto-sleep-loading-overlay').isVisible(), false);
});

browserTest('Auto-sleep retains chart geometry and swaps a refreshed source snapshot atomically', async (t) => {
  const sourcePost = deferred();
  const refreshedHistory = deferred();
  const homeDashboards = deferred();
  let historyCalls = 0;
  const requestedHistoryHours = [];
  let sourcePosts = 0;
  const schema = [
    { key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true },
    { key: 'friendly_name', label: 'Panel name', group: 'System', type: 'STRING', available: true },
  ];
  const harness = await startHarness((path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({ settings: { auto_sleep: 'true', friendly_name: 'Panel' }, ha_expose: {}, ha_auth: { configured: true } });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/config/home-dashboards') return homeDashboards.promise;
    if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: true, phase: 'assigned', area_name: 'Office' });
    if (path === '/api/v1/auto-sleep') return json({ enabled: true, available: true, phase: 'live', area_name: 'Office', source_count: 1 });
    if (path === '/api/v1/auto-sleep/source' && request.method === 'POST') { sourcePosts++; return sourcePost.promise; }
    if (path === '/api/v1/auto-sleep/history') {
      historyCalls++;
      requestedHistoryHours.push(new URL(request.url, 'http://panel.test').searchParams.get('hours'));
      if (historyCalls === 1) return json(autoSleepHistory({ hours: 24, laneCount: 20 }));
      if (historyCalls === 2) return refreshedHistory.promise;
      return json({ detail: 'terminal' }, 409);
    }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 1000, height: 800 } });
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  const sourceRow = page.locator('.auto-sleep-lane.source').first();
  await sourceRow.waitFor();
  assert.equal(requestedHistoryHours[0], '24');
  assert.equal(await page.getByRole('button', { name: '24h' }).getAttribute('aria-pressed'), 'true');

  const label = sourceRow.locator('.auto-sleep-label');
  const track = sourceRow.locator('.auto-sleep-track');
  assert.equal(await label.getAttribute('title'), 'Office ceiling motion');
  assert.equal(await track.getAttribute('title'), 'Click to suppress this source');
  assert.equal(await sourceRow.getAttribute('title'), null);
  assert.equal(await sourceRow.locator('.auto-sleep-interval').getAttribute('title'), null);
  const oldPanel = await page.locator('#auto-sleep-status').elementHandle();
  const oldChart = await page.locator('#auto-sleep-chart').elementHandle();
  const oldContent = await page.locator('.auto-sleep-chart-content').elementHandle();
  const oldSnapshot = await page.locator('.auto-sleep-chart-snapshot').elementHandle();
  const oldScroll = await page.locator('.auto-sleep-source-scroll').elementHandle();
  await sourceRow.focus();
  await oldScroll.evaluate((node) => { node.scrollTop = 75; });
  const beforePageY = await page.evaluate(() => {
    document.body.style.minHeight = '2000px';
    window.scrollTo(0, 400);
    return window.pageYOffset;
  });
  const before = await page.evaluate(() => {
    const chart = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    const system = document.querySelector('[data-config-group="System"]').getBoundingClientRect();
    return { height: chart.height, legendY: legend.y, systemY: system.y };
  });

  await sourceRow.press('Enter');
  const overlay = page.locator('.auto-sleep-loading-overlay');
  await overlay.waitFor({ state: 'visible' });
  assert.equal(await page.locator('.auto-sleep-lane.source').count(), 20);
  assert.equal(await sourceRow.getAttribute('aria-disabled'), 'true');
  assert.equal(await oldSnapshot.evaluate((node) => node.isConnected), true);
  assert.equal(await oldScroll.evaluate((node) => node.isConnected && node.scrollTop), 75);
  assert.equal(await sourceRow.evaluate((node) => document.activeElement === node), true);
  assert.equal(await page.evaluate(() => window.pageYOffset), beforePageY);
  const duringPost = await page.evaluate(() => {
    const chart = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    const system = document.querySelector('[data-config-group="System"]').getBoundingClientRect();
    return { height: chart.height, legendY: legend.y, systemY: system.y };
  });
  assert.deepEqual(duringPost, before);

  // An unrelated asynchronous Configure probe may legitimately rebuild other cards while the
  // source update is pending. The activity subtree itself must stay connected and unchanged.
  homeDashboards.resolve(json({ items: [{ path: 'overview', title: 'Overview', group: 'dashboard' }], default: {} }));
  await page.waitForTimeout(50);
  assert.equal(await oldPanel.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-status')), true);
  assert.equal(await oldChart.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-chart')), true);
  assert.equal(await oldContent.evaluate((node) => node.isConnected && node === document.querySelector('.auto-sleep-chart-content')), true);
  assert.equal(await oldSnapshot.evaluate((node) => node.isConnected), true);
  assert.equal(await oldScroll.evaluate((node) => node.isConnected && node.scrollTop), 75);
  assert.equal(await sourceRow.evaluate((node) => document.activeElement === node), true);
  assert.equal(await page.evaluate(() => window.pageYOffset), beforePageY);
  assert.deepEqual(await page.evaluate(() => {
    const chart = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    const system = document.querySelector('[data-config-group="System"]').getBoundingClientRect();
    return { height: chart.height, legendY: legend.y, systemY: system.y };
  }), before);

  sourcePost.resolve(json({ updated: true }));
  for (let attempt = 0; attempt < 20 && historyCalls < 2; attempt++) await page.waitForTimeout(25);
  assert.equal(historyCalls, 2);
  await sourceRow.press('Enter');
  await page.waitForTimeout(50);
  assert.equal(sourcePosts, 1);
  assert.equal(await sourceRow.getAttribute('aria-disabled'), 'true');
  assert.equal(await page.locator('.auto-sleep-lane.source').count(), 20);
  assert.equal(await oldSnapshot.evaluate((node) => node.isConnected), true);
  assert.equal(await oldScroll.evaluate((node) => node.isConnected && node.scrollTop), 75);
  assert.equal(await sourceRow.evaluate((node) => document.activeElement === node), true);
  assert.deepEqual(await page.evaluate(() => {
    const chart = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    const system = document.querySelector('[data-config-group="System"]').getBoundingClientRect();
    return { height: chart.height, legendY: legend.y, systemY: system.y };
  }), before);

  refreshedHistory.resolve(json(autoSleepHistory({ included: false, hours: 24, laneCount: 20 })));
  await overlay.waitFor({ state: 'hidden' });
  await page.getByText('Office ceiling motion · Suppressed').waitFor();
  assert.equal(await oldSnapshot.evaluate((node) => node.isConnected), false);
  assert.equal(await page.locator('.auto-sleep-lane.source').first().getAttribute('aria-disabled'), 'false');
  assert.equal(await page.locator('.auto-sleep-track').nth(1).getAttribute('title'), 'Click to include this source');

  await page.getByRole('button', { name: '6h' }).click();
  await page.getByText('History request failed (HTTP 409).').waitFor();
  assert.equal(await page.locator('.auto-sleep-lane.source').count(), 20);
  assert.equal(requestedHistoryHours.at(-1), '6');
  assert.equal(await page.getByRole('button', { name: '24h' }).getAttribute('aria-pressed'), 'true');
});

browserTest('Auto-sleep restores its cached chart synchronously when disabled and re-enabled', async (t) => {
  let enabled = true;
  let statusCalls = 0;
  const reenabledStatus = deferred();
  const reenabledHistory = deferred();
  let historyCalls = 0;
  const schema = [{ key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true }];
  const harness = await startHarness((path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') {
      if (request.method === 'POST') { enabled = !enabled; return json({}); }
      return json({ settings: { auto_sleep: String(enabled) }, ha_expose: {}, ha_auth: { configured: true } });
    }
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: true, phase: 'assigned', area_name: 'Office' });
    if (path === '/api/v1/auto-sleep') {
      statusCalls++;
      return statusCalls === 1 ? json({ enabled: true, available: true, phase: 'live', area_name: 'Office', source_count: 1 }) : reenabledStatus.promise;
    }
    if (path === '/api/v1/auto-sleep/history') {
      historyCalls++;
      return historyCalls === 1 ? json(autoSleepHistory()) : reenabledHistory.promise;
    }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 1000, height: 800 } });
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.locator('.auto-sleep-lane.source').waitFor();

  await page.locator('#cfg-auto_sleep [role=switch]').click();
  await page.locator('#savebtn').click();
  await page.locator('#auto-sleep-status').waitFor({ state: 'detached' });
  await page.getByText('Home Assistant Area: Office').waitFor();

  await page.evaluate(() => {
    window.__autoSleepInsertionBusy = [];
    new MutationObserver(() => {
      const chart = document.querySelector('#auto-sleep-chart');
      if (chart && !window.__autoSleepInsertionBusy.length) {
        window.__autoSleepInsertionBusy.push({
          busy: chart.getAttribute('aria-busy'),
          overlayHidden: chart.querySelector('.auto-sleep-loading-overlay').hidden,
        });
      }
    }).observe(document.querySelector('#cfg-groups'), { childList: true, subtree: true });
  });
  await page.locator('#cfg-auto_sleep [role=switch]').click();
  await page.locator('#savebtn').click();
  const chart = page.locator('#auto-sleep-chart');
  await chart.waitFor();
  await chart.locator('.auto-sleep-lane.source').waitFor();
  await chart.locator('.auto-sleep-loading-overlay').waitFor({ state: 'visible' });
  assert.deepEqual(await page.evaluate(() => window.__autoSleepInsertionBusy[0]), { busy: 'true', overlayHidden: false });
  const stableGeometry = await page.evaluate(() => {
    const chartBox = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    return { height: chartBox.height, legendY: legend.y };
  });
  await page.waitForTimeout(250);
  assert.deepEqual(await page.evaluate(() => {
    const chartBox = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    return { height: chartBox.height, legendY: legend.y };
  }), stableGeometry);
  assert.equal(await chart.locator('.auto-sleep-loading-overlay').count(), 1);

  reenabledStatus.resolve(json({ enabled: true, available: true, phase: 'live', area_name: 'Office', source_count: 1 }));
  for (let attempt = 0; attempt < 20 && historyCalls < 2; attempt++) await page.waitForTimeout(25);
  assert.equal(historyCalls, 2);
  reenabledHistory.resolve(json(autoSleepHistory({ label: 'Office ceiling motion refreshed' })));
  await chart.locator('.auto-sleep-loading-overlay').waitFor({ state: 'hidden' });
  await page.getByText('Office ceiling motion refreshed').waitFor();
});

browserTest('Auto-sleep source failure keeps the exact focused and scrolled chart snapshot', async (t) => {
  const failedSourcePost = deferred();
  const schema = [{ key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true }];
  const harness = await startHarness((path, request) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({ settings: { auto_sleep: 'true' }, ha_expose: {}, ha_auth: { configured: true } });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: true, phase: 'assigned', area_name: 'Office' });
    if (path === '/api/v1/auto-sleep') return json({ enabled: true, available: true, phase: 'live', area_name: 'Office', source_count: 20 });
    if (path === '/api/v1/auto-sleep/history') return json(autoSleepHistory({ laneCount: 20 }));
    if (path === '/api/v1/auto-sleep/source' && request.method === 'POST') return failedSourcePost.promise;
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage({ viewport: { width: 1000, height: 800 } });
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  const row = page.locator('.auto-sleep-lane.source').first();
  await row.waitFor();
  const snapshot = await page.locator('.auto-sleep-chart-snapshot').elementHandle();
  const scroll = await page.locator('.auto-sleep-source-scroll').elementHandle();
  await row.focus();
  await scroll.evaluate((node) => { node.scrollTop = 61; });
  await row.press('Enter');
  await page.locator('.auto-sleep-loading-overlay').waitFor({ state: 'visible' });
  failedSourcePost.resolve(json({ error: 'failed' }, 500));
  await page.getByText('Could not update this activity source (HTTP 500).').waitFor();
  await page.locator('.auto-sleep-loading-overlay').waitFor({ state: 'hidden' });
  assert.equal(await snapshot.evaluate((node) => node.isConnected), true);
  assert.equal(await scroll.evaluate((node) => node.isConnected && node.scrollTop), 61);
  assert.equal(await row.evaluate((node) => document.activeElement === node), true);
});

for (const staleSourceOutcome of ['success', 'failure']) {
  browserTest(`Auto-sleep ignores stale source ${staleSourceOutcome} after an Area transition`, async (t) => {
    let areaName = 'Office';
    let statusCalls = 0;
    let historyCalls = 0;
    const sourcePost = deferred();
    const studioStatus = deferred();
    const studioHistory = deferred();
    const schema = [{ key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true }];
    const harness = await startHarness((path, request) => {
      if (path === '/api/v1/config/schema') return json(schema);
      if (path === '/api/v1/config') return json({ settings: { auto_sleep: 'true' }, ha_expose: {}, ha_auth: { configured: true } });
      if (path === '/api/v1/apps') return json({ apps: [] });
      if (path === '/api/v1/radio') return json({ present: false });
      if (path === '/api/v1/proximity') return json({ present: false });
      if (path === '/api/v1/auto-sleep/prerequisite') return json({ eligible: true, phase: 'assigned', area_name: areaName });
      if (path === '/api/v1/auto-sleep') {
        statusCalls++;
        return statusCalls === 1
          ? json({ enabled: true, available: true, phase: 'live', area_name: 'Office', source_count: 1 })
          : studioStatus.promise;
      }
      if (path === '/api/v1/auto-sleep/history') {
        historyCalls++;
        return historyCalls === 1 ? json(autoSleepHistory()) : studioHistory.promise;
      }
      if (path === '/api/v1/auto-sleep/source' && request.method === 'POST') return sourcePost.promise;
    });
    const browser = await chromium.launch({ executablePath: chrome, headless: true });
    const page = await browser.newPage();
    page.setDefaultTimeout(2_000);
    t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
    await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
    const row = page.locator('.auto-sleep-lane.source').first();
    await row.waitFor();
    await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Office', { exact: true }).waitFor();
    const snapshot = await page.locator('.auto-sleep-chart-snapshot').elementHandle();
    await row.press('Enter');
    await page.locator('.auto-sleep-loading-overlay').waitFor({ state: 'visible' });

    areaName = 'Studio';
    await page.evaluate(() => window.dispatchEvent(new Event('focus')));
    await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Studio', { exact: true }).waitFor();
    for (let attempt = 0; attempt < 20 && statusCalls < 2; attempt++) await page.waitForTimeout(25);
    assert.equal(statusCalls, 2);
    sourcePost.resolve(staleSourceOutcome === 'success' ? json({ updated: true }) : json({ error: 'failed' }, 500));
    await page.waitForTimeout(100);
    assert.equal(statusCalls, 2);
    assert.equal(historyCalls, 1);
    assert.equal(await snapshot.evaluate((node) => node.isConnected), true);
    assert.equal(await page.locator('.auto-sleep-loading-overlay').isVisible(), true);
    assert.equal(await page.getByText(/Could not update this activity source/).count(), 0);

    studioStatus.resolve(json({ enabled: true, available: true, phase: 'live', area_name: 'Studio', source_count: 1 }));
    for (let attempt = 0; attempt < 20 && historyCalls < 2; attempt++) await page.waitForTimeout(25);
    studioHistory.resolve(json(autoSleepHistory({ label: 'Studio motion', areaName: 'Studio' })));
    await page.getByText('Studio motion').waitFor();
  });
}

browserTest('Auto-sleep replaces stale startup history when the first Area prerequisite arrives', async (t) => {
  const prerequisite = deferred();
  let statusCalls = 0;
  let historyCalls = 0;
  const schema = [{ key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true }];
  const harness = await startHarness((path) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({ settings: { auto_sleep: 'true' }, ha_expose: {}, ha_auth: { configured: true } });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/auto-sleep/prerequisite') return prerequisite.promise;
    if (path === '/api/v1/auto-sleep') {
      statusCalls++;
      return json({ enabled: true, available: true, phase: 'live', area_name: statusCalls === 1 ? 'Area A' : 'Area B', source_count: 1 });
    }
    if (path === '/api/v1/auto-sleep/history') {
      historyCalls++;
      return json(autoSleepHistory({ label: historyCalls === 1 ? 'Area A motion' : 'Area B motion', areaName: historyCalls === 1 ? 'Area A' : 'Area B' }));
    }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.getByText('Area A motion').waitFor();
  const staleSnapshot = await page.locator('.auto-sleep-chart-snapshot').elementHandle();

  prerequisite.resolve(json({ eligible: true, phase: 'assigned', area_name: 'Area B' }));
  await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Area B', { exact: true }).waitFor();
  await page.getByText('Area B motion').waitFor();
  assert.equal(statusCalls, 2);
  assert.equal(historyCalls, 2);
  assert.equal(await staleSnapshot.evaluate((node) => node.isConnected), false);
  assert.equal(await page.getByText('Area A motion').count(), 0);
});

browserTest('Auto-sleep Area refresh retains A until B is complete and converges unassigned to off without losing edits', async (t) => {
  let areaName = 'Area A';
  let statusCalls = 0;
  let historyCalls = 0;
  const areaBHistory = deferred();
  const schema = [
    { key: 'auto_sleep', label: 'Auto sleep', group: 'Behaviour', type: 'BOOL', available: true },
    { key: 'friendly_name', label: 'Panel name', group: 'System', type: 'STRING', available: true },
  ];
  const harness = await startHarness((path) => {
    if (path === '/api/v1/config/schema') return json(schema);
    if (path === '/api/v1/config') return json({ settings: { auto_sleep: 'true', friendly_name: 'Panel' }, ha_expose: {}, ha_auth: { configured: true } });
    if (path === '/api/v1/apps') return json({ apps: [] });
    if (path === '/api/v1/radio') return json({ present: false });
    if (path === '/api/v1/proximity') return json({ present: false });
    if (path === '/api/v1/auto-sleep/prerequisite') return areaName
      ? json({ eligible: true, phase: 'assigned', area_name: areaName })
      : json({ eligible: false, phase: 'unassigned', area_name: null });
    if (path === '/api/v1/auto-sleep') {
      statusCalls++;
      return json({ enabled: true, available: true, phase: 'live', area_name: statusCalls <= 2 ? 'Area A' : 'Area B', source_count: 1 });
    }
    if (path === '/api/v1/auto-sleep/history') {
      historyCalls++;
      if (historyCalls <= 2) return json(autoSleepHistory({ label: 'Area A motion', areaName: 'Area A' }));
      return areaBHistory.promise;
    }
  });
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  page.setDefaultTimeout(2_000);
  t.after(async () => { await browser.close(); await new Promise((resolve) => harness.server.close(resolve)); });
  await page.goto(harness.url, { waitUntil: 'domcontentloaded', timeout: 5_000 });
  await page.getByText('Area A motion').waitFor();
  await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Area A', { exact: true }).waitFor();
  const areaAPanel = await page.locator('#auto-sleep-status').elementHandle();
  const areaAChart = await page.locator('#auto-sleep-chart').elementHandle();
  const areaAContent = await page.locator('.auto-sleep-chart-content').elementHandle();
  const areaASnapshot = await page.locator('.auto-sleep-chart-snapshot').elementHandle();
  const beforeAreaChange = await page.evaluate(() => {
    const chart = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    const system = document.querySelector('[data-config-group="System"]').getBoundingClientRect();
    return { height: chart.height, legendY: legend.y, systemY: system.y };
  });

  areaName = 'Area B';
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await page.locator('#auto-sleep-prerequisite-status').getByText('Home Assistant Area: Area B', { exact: true }).waitFor();
  assert.equal(statusCalls, 2);
  await page.locator('.auto-sleep-loading-overlay').waitFor({ state: 'visible' });
  assert.equal(await areaAPanel.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-status')), true);
  assert.equal(await areaAChart.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-chart')), true);
  assert.equal(await areaAContent.evaluate((node) => node.isConnected && node === document.querySelector('.auto-sleep-chart-content')), true);
  assert.equal(await areaASnapshot.evaluate((node) => node.isConnected), true);
  assert.equal(await page.getByText('Area A motion').count(), 1);
  assert.deepEqual(await page.evaluate(() => {
    const chart = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    const system = document.querySelector('[data-config-group="System"]').getBoundingClientRect();
    return { height: chart.height, legendY: legend.y, systemY: system.y };
  }), beforeAreaChange);
  assert.equal(historyCalls, 1);
  for (let attempt = 0; attempt < 80 && historyCalls < 2; attempt++) await page.waitForTimeout(25);
  assert.equal(historyCalls, 2);
  assert.equal(await areaASnapshot.evaluate((node) => node.isConnected), true);
  assert.equal(await page.getByText('Area A motion').count(), 1);
  for (let attempt = 0; attempt < 240 && historyCalls < 3; attempt++) await page.waitForTimeout(25);
  assert.equal(historyCalls, 3);
  areaBHistory.resolve(json(autoSleepHistory({ label: 'Area B motion', areaName: 'Area B' })));
  await page.getByText('Area B motion').waitFor();
  await page.locator('.auto-sleep-loading-overlay').waitFor({ state: 'hidden' });
  assert.equal(await areaASnapshot.evaluate((node) => node.isConnected), false);
  assert.equal(await areaAPanel.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-status')), true);
  assert.equal(await areaAChart.evaluate((node) => node.isConnected && node === document.querySelector('#auto-sleep-chart')), true);
  assert.equal(await areaAContent.evaluate((node) => node.isConnected && node === document.querySelector('.auto-sleep-chart-content')), true);
  assert.deepEqual(await page.evaluate(() => {
    const chart = document.querySelector('#auto-sleep-chart').getBoundingClientRect();
    const legend = document.querySelector('.auto-sleep-legend').getBoundingClientRect();
    const system = document.querySelector('[data-config-group="System"]').getBoundingClientRect();
    return { height: chart.height, legendY: legend.y, systemY: system.y };
  }), beforeAreaChange);

  await page.locator('#cfg-friendly_name input').fill('Unsaved panel name');
  areaName = null;
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await page.getByText('Assign this panel to a Home Assistant Area before enabling Auto sleep.').waitFor();
  const toggle = page.locator('#cfg-auto_sleep [role=switch]');
  assert.equal(await toggle.getAttribute('aria-checked'), 'false');
  assert.equal(await toggle.getAttribute('aria-disabled'), 'true');
  assert.equal(await toggle.getAttribute('tabindex'), '0');
  assert.equal(await page.locator('#auto-sleep-status').count(), 0);
  assert.equal(await page.locator('#cfg-friendly_name input').inputValue(), 'Unsaved panel name');
  assert.equal(await page.locator('#savebtn').isEnabled(), true);
});
