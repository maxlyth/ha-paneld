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
  return `<!doctype html><html><body>
    <span id="hardened-approval-description"></span><span id="hardened-approval-conditional-description"></span>
    <button id="tab-basic"></button><button id="tab-adv"></button>
    <p id="cfg-msg"></p><p id="cfg-status"></p><div id="cfg-groups"></div>
    <div id="proximity-learning-mount"></div><div id="savebar" hidden><button id="savebtn" onclick="cfgSave()"></button></div>
    <script>window.CardColumnAlignment={attach:()=>({schedule(){}})};</script>
    <script src="/configure.js"></script><script src="/proximity-learning.js"></script>
  </body></html>`;
}

async function startHarness(routes) {
  const server = createServer(async (request, response) => {
    const path = new URL(request.url, 'http://panel.test').pathname;
    if (path === '/') return response.end(fixture());
    if (['/configure.js', '/proximity-learning.js'].includes(path)) {
      response.setHeader('content-type', 'application/javascript');
      return response.end(await readFile(join(root, path.slice(1)), 'utf8'));
    }
    const result = await routes(path, request);
    response.writeHead(result?.status || 404, result?.headers);
    response.end(result?.body || 'not found');
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return { server, url: `http://127.0.0.1:${server.address().port}` };
}

const browserTest = existsSync(chrome) ? test : test.skip;

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
