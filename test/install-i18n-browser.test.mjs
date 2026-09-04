import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { existsSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { chromium } from 'playwright-core';

const asset = fileURLToPath(new URL('../app/src/main/assets/install.js', import.meta.url));
const powerAsset = fileURLToPath(new URL('../app/src/main/assets/power-safety.js', import.meta.url));
const chrome = process.env.CHROME || '/usr/bin/chromium';
const browserTest = existsSync(chrome) ? test : test.skip;

function objectLiteral(source, name) {
  const marker = `var ${name} = Object.freeze(`;
  const start = source.indexOf(marker);
  assert.notEqual(start, -1, `${name} declaration is missing`);
  const bodyStart = start + marker.length;
  let depth = 0, quote = '', escaped = false;
  for (let index = bodyStart; index < source.length; index += 1) {
    const char = source[index];
    if (quote) {
      if (escaped) escaped = false;
      else if (char === '\\') escaped = true;
      else if (char === quote) quote = '';
      continue;
    }
    if (char === '"' || char === "'") quote = char;
    else if (char === '{' || char === '[') depth += 1;
    else if (char === '}' || char === ']') depth -= 1;
    else if (char === ')' && depth === 0) return source.slice(bodyStart, index);
  }
  assert.fail(`${name} declaration is unterminated`);
}

function frozenObject(source, name) {
  return Function(`"use strict";return (${objectLiteral(source, name)});`)();
}

function digest(value) {
  return createHash('sha256').update(JSON.stringify(value)).digest('hex');
}

test('Install browser copies the exact frozen v3 presentation and direct-token tables', async () => {
  const source = await readFile(asset, 'utf8');
  const presentations = frozenObject(source, 'PRESENTATIONS');
  assert.equal(Object.keys(presentations).length, 112);
  assert.equal(digest(presentations), 'bb452cf6d3cf29deb0a0b0fad0180258cd534ab8ab9d727db6eb373f5a39d3b3');
  assert.equal(digest(frozenObject(source, 'COMPONENT_STATUS')), '73563afe880f7bccf8b8c31d582a29405b8a498724a97cda7b54686a56f9530e');
  assert.equal(digest(frozenObject(source, 'APK_STATUS')), '2849cf9486f18979fe79465df4c9c76e11c558ef03ae3e98faa1f013b34f9cd8');
  assert.equal(digest(frozenObject(source, 'RESTORE_OUTCOME')), '69226bae3ec367703099c6136b4ff7b7ef264d8ad6efe6a02428bb155e7662b1');
  assert.equal(digest(frozenObject(source, 'RADIO_STATE')), '9e383eec164a6462adc21aceab958e6e023503e21ddcb16a1a4d3671ebfeec9d');
  assert.equal(digest(frozenObject(source, 'CONFIG_IMPORT_STATUS')), 'b28e9c65c69c405fc4576d46a08920a3272b303624d436fa00883ecf8298ea23');
});

const samples = Object.freeze({
  owner: 'paneld', component: 'paneld', channel: 'stable', count: '2', package: 'io.example.app',
  version: '1.2.3', current: '1.0', latest: '1.2', cap: '1.1', current_engine: 'WebView 130',
  target_chromium: '130', from_schema: '8', to_schema: '7', release_url: 'https://example.invalid/release',
  usable_bytes: '1024', total_bytes: '2048', used_percent: '50.0', database_bytes: '512', wal_bytes: '16',
  failure: 'io', operation: 'database-checkpoint', bound_ip: '192.0.2.1', lan_ip: '192.0.2.2',
  attempts: '2', reason_code: 'no-response',
});

async function rig(t, options = {}) {
  const source = await readFile(asset, 'utf8');
  const presentations = frozenObject(source, 'PRESENTATIONS');
  const params = frozenObject(source, 'PARAMS');
  const strings = {};
  const languages = {};
  for (const [code, spec] of Object.entries(presentations)) {
    const keys = typeof spec === 'string' ? [spec] : [spec.one, spec.other];
    for (const key of keys) {
      strings[key] = `LOC:${code}:${Object.keys(samples).map((name) => `{${name}}`).join('|')}`;
      languages[key] = 'zh-Hans';
    }
  }
  Object.assign(strings, options.extraStrings || {});
  Object.keys(options.extraStrings || {}).forEach((key) => { languages[key] = 'zh-Hans'; });
  if (options.untranslated) delete languages[typeof presentations[options.untranslated] === 'string' ? presentations[options.untranslated] : presentations[options.untranslated].other];
  const payload = JSON.stringify({ locale: 'zh-Hans', strings, languages }).replaceAll('<', '\\u003c');
  const status = options.status || { warnings: [], warning_presentations: [] };
  const server = createServer((request, response) => {
    const requestUrl = new URL(request.url, 'http://panel.test');
    const path = requestUrl.pathname;
    if (options.route && options.route(request, response, requestUrl)) return;
    if (path === '/install.js') { response.writeHead(200, { 'content-type': 'application/javascript' }); response.end(source); return; }
    if (path === '/api/v1/radio') { response.writeHead(200, { 'content-type': 'application/json' }); response.end('{"present":false}'); return; }
    if (path === '/api/v1/status') { response.writeHead(200, { 'content-type': 'application/json' }); response.end(JSON.stringify(status)); return; }
    const helper = options.noHelper ? '' : `<script>window.HaI18n={locale:'zh-Hans',t:(key,fallback,values)=>{const all=${JSON.stringify(strings)};return String(Object.prototype.hasOwnProperty.call(all,key)?all[key]:fallback).replace(/\\{([A-Za-z][A-Za-z0-9_]*)\\}/g,(token,name)=>values&&Object.prototype.hasOwnProperty.call(values,name)?String(values[name]):token);}};</script>`;
    response.writeHead(200, { 'content-type': 'text/html' });
    response.end(`<!doctype html><html lang="zh-Hans"><body><script id="ha-i18n" type="application/json">${payload}</script>${helper}${options.html || '<div id="audit-out"></div><div id="bk-msg"></div>'}<script>window.CardColumnAlignment={attach:()=>()=>{}};</script><script src="/install.js"></script></body></html>`);
  });
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  t.after(async () => { await browser.close(); server.closeAllConnections?.(); await new Promise((done) => server.close(done)); });
  await page.goto(`http://127.0.0.1:${server.address().port}/${options.query || ''}`, { waitUntil: 'domcontentloaded' });
  return { page, presentations, params };
}

browserTest('Install accepts every frozen presentation code with its exact parameter shape', async (t) => {
  const { page, presentations, params } = await rig(t);
  const cases = Object.keys(presentations).map((code) => {
    const rule = params[code] || {};
    return { code, params: Object.fromEntries([...(rule.required || []), ...(rule.optional || [])].map((name) => [name, samples[name]])) };
  });
  const outcomes = await page.evaluate((items) => items.map((envelope) => window.HaPaneldInstallPresentation.present(envelope, `RAW:${envelope.code}`)), cases);
  outcomes.forEach((outcome, index) => {
    assert.equal(outcome.fallback, false, `${cases[index].code} unexpectedly fell back`);
    assert.match(outcome.text, new RegExp(`^LOC:${cases[index].code}:`));
  });
});

browserTest('Install rejects malformed, unknown, unsafe and untranslated metadata exactly', async (t) => {
  const { page } = await rig(t, { untranslated: 'managed-up-to-date' });
  const fallback = '<b>exact compatibility</b>';
  const cases = [
    null, [], {}, { code: 'future-code', params: {} }, { code: 'managed-up-to-date', params: { component: 'paneld', current: '1' } },
    { code: 'managed-apk-missing', params: { component: 'paneld' } },
    { code: 'managed-apk-missing', params: { component: 'paneld', version: '1', extra: 'x' } },
    { code: 'managed-apk-missing', params: { component: 7, version: '1' } },
    { code: 'managed-apk-missing', params: { component: 'paneld', version: 'x'.repeat(513) } },
    { code: 'status-update-available', params: { component: 'paneld', current: '1', latest: '2', release_url: 'javascript:alert(1)' } },
    { code: 'status-mdns-unresponsive', params: { attempts: '02', reason_code: 'no-response' } },
    { code: 'version-install', params: {}, extra: true },
  ];
  const outcomes = await page.evaluate(({ cases, fallback }) => cases.map((item) => window.HaPaneldInstallPresentation.present(item, fallback)), { cases, fallback });
  assert.ok(outcomes.every((item) => item.fallback && item.text === fallback));
});

browserTest('Install presentation rendering keeps hostile values as text, raw evidence English and HTTPS links explicit', async (t) => {
  const { page } = await rig(t);
  const outcome = await page.evaluate(() => {
    const hostile = document.createElement('div'); document.body.appendChild(hostile);
    window.HaPaneldInstallPresentation.set(hostile, { code: 'managed-apk-missing', params: { component: 'paneld', version: '<img src=x onerror=window.__owned=1>' } }, 'RAW hostile');
    const raw = document.createElement('div'); document.body.appendChild(raw);
    window.HaPaneldInstallPresentation.set(raw, { code: 'package-uninstall-failed', params: { package: 'io.example.app' } }, '<img src=x onerror=window.__owned=2>');
    const linked = document.createElement('div'); document.body.appendChild(linked);
    window.HaPaneldInstallPresentation.set(linked, { code: 'status-update-available', params: { component: 'paneld', current: '1', latest: '2', release_url: 'https://example.invalid/release' } }, 'fallback');
    return { hostile: hostile.textContent, raw: raw.textContent, rawLang: raw.querySelector('[lang="en"]')?.textContent, images: document.querySelectorAll('img').length, href: linked.querySelector('a')?.href, owned: window.__owned };
  });
  assert.match(outcome.hostile, /<img src=x/);
  assert.equal(outcome.rawLang, '<img src=x onerror=window.__owned=2>');
  assert.equal(outcome.images, 0);
  assert.equal(outcome.href, 'https://example.invalid/release');
  assert.equal(outcome.owned, undefined);
});

browserTest('Install restore result localizes closed outcomes and isolates arbitrary English detail', async (t) => {
  const { page } = await rig(t);
  const outcome = await page.evaluate(() => {
    window.HaPaneldInstallPresentation.renderRestoreResult({
      message: 'completed',
      presentation: { code: 'restore-completed', params: {} },
      result: {
        config: { status: 'succeeded', items: 3 },
        companion: { status: 'partial', detail: '<img src=x onerror=window.__restoreOwned=1>' },
      },
    });
    const node = document.getElementById('bk-msg');
    return {
      text: node.textContent,
      parentLang: node.getAttribute('lang'),
      english: Array.from(node.querySelectorAll('[lang="en"]')).map((item) => item.textContent),
      images: node.querySelectorAll('img').length,
      owned: window.__restoreOwned,
    };
  });
  assert.match(outcome.text, /^LOC:restore-completed:/);
  assert.match(outcome.text, /Config: succeeded/);
  assert.match(outcome.text, /Companion: partial/);
  assert.equal(outcome.parentLang, null);
  assert.deepEqual(outcome.english, ['<img src=x onerror=window.__restoreOwned=1>']);
  assert.equal(outcome.images, 0);
  assert.equal(outcome.owned, undefined);
});

browserTest('Install warning overlay localizes valid entries and preserves exact per-item English fallback', async (t) => {
  const status = {
    warnings: ['<b>legacy known</b>', '<i>legacy unknown</i>'],
    warning_presentations: [{ code: 'status-no-renderer', params: {} }, null],
  };
  const { page } = await rig(t, { status });
  await page.evaluate(() => healthAudit({ disabled: false }));
  await page.waitForFunction(() => document.querySelectorAll('#audit-out .setup').length === 2);
  const rows = await page.locator('#audit-out .setup').evaluateAll((nodes) => nodes.map((node) => ({ text: node.textContent, lang: node.getAttribute('lang'), html: node.innerHTML })));
  assert.match(rows[0].text, /^LOC:status-no-renderer:/);
  assert.equal(rows[0].lang, null);
  assert.equal(rows[1].text, 'legacy unknown');
  assert.equal(rows[1].lang, 'en');
  assert.equal(await page.locator('#audit-out i').count(), 1, 'legacy fallback retains its byte-compatible server HTML path');
});

browserTest('Install remains usable in exact English when the shared helper is absent', async (t) => {
  const { page } = await rig(t, { noHelper: true });
  const value = await page.evaluate(() => window.HaPaneldInstallPresentation.present({ code: 'managed-up-to-date', params: { component: 'paneld', current: '1' } }, 'Exact English fallback'));
  assert.equal(value.text, 'Exact English fallback');
});

browserTest('Install APK and uninstall results localize their controlled summary while retaining exact legacy evidence', async (t) => {
  const route = (request, response, url) => {
    if (url.pathname === '/api/v1/packages') {
      response.writeHead(200, { 'content-type': 'application/json' }); response.end('{"packages":[{"pkg":"io.example.app","label":"Example"}]}'); return true;
    }
    if (url.pathname === '/api/v1/install/apk/commit') {
      response.writeHead(200, { 'content-type': 'application/json' }); response.end('{"status":"started"}'); return true;
    }
    if (url.pathname === '/api/v1/install/status') {
      response.writeHead(200, { 'content-type': 'application/json' });
      response.end(JSON.stringify({ running: false, message: '<b>exact APK result</b>', presentation: { code: 'managed-install-committed', params: { component: 'apk', version: '1.2.3' } } })); return true;
    }
    if (url.pathname === '/api/v1/uninstall') {
      response.writeHead(200, { 'content-type': 'application/json' });
      response.end(JSON.stringify({ ok: true, presentation: { code: 'package-uninstalled', params: { package: 'io.example.app' } } })); return true;
    }
    return false;
  };
  const { page } = await rig(t, {
    route,
    extraStrings: { 'install.apk.dynamic.result_label': 'LOC result:' },
    html: '<div id="apk-msg"></div><div id="apk-preview"><button data-token="token"></button></div><select id="uninst-pkg"><option value="io.example.app">Example</option></select><div id="uninst-msg"></div>',
  });
  await page.evaluate(() => window.apkInstall(document.querySelector('#apk-preview button')));
  await page.waitForFunction(() => document.querySelector('#apk-msg [lang="en"]'));
  assert.match(await page.locator('#apk-msg').textContent(), /^LOC result: LOC:managed-install-committed:/);
  assert.equal(await page.locator('#apk-msg [lang="en"]').textContent(), '<b>exact APK result</b>');
  assert.equal(await page.locator('#apk-msg b').count(), 0, 'raw APK evidence must remain inert text');

  page.on('dialog', (dialog) => dialog.accept());
  await page.evaluate(() => window.doUninstall(document.createElement('button')));
  await page.waitForFunction(() => document.querySelector('#uninst-msg [lang="en"]'));
  assert.match(await page.locator('#uninst-msg').textContent(), /^LOC:package-uninstalled:/);
  assert.equal(await page.locator('#uninst-msg [lang="en"]').textContent(), 'Uninstalled io.example.app');
});

browserTest('Install protected-form success keeps the supported explicit language in its card redirect', async (t) => {
  const route = (_request, response, url) => {
    if (url.pathname !== '/api/v1/display/density') return false;
    response.writeHead(200, { 'content-type': 'application/json' });
    response.end(JSON.stringify({ ok: true, message: 'Applied.' })); return true;
  };
  const { page } = await rig(t, {
    route,
    query: '?lang=zh-Hans',
    html: '<form action="/api/v1/display/density"><button id="density-submit" type="submit">Apply</button></form>',
  });
  await page.click('#density-submit');
  await page.waitForURL(/\/install\?lang=zh-Hans#cfg-display$/, { timeout: 3_000 });
  assert.equal(new URL(page.url()).searchParams.get('lang'), 'zh-Hans');
});

browserTest('Install Backup, Export and Import failures localize prefixes and isolate exact diagnostics', async (t) => {
  const route = (_request, response, url) => {
    if (url.pathname === '/api/v1/backup') {
      response.writeHead(500, { 'content-type': 'application/json' }); response.end('{"message":"<img src=x onerror=window.__backupOwned=1>"}'); return true;
    }
    if (url.pathname === '/api/v1/config/export') {
      response.writeHead(500, { 'content-type': 'application/json' }); response.end('{"error":"<img src=x onerror=window.__exportOwned=1>"}'); return true;
    }
    if (url.pathname === '/api/v1/config/import') {
      response.writeHead(400, { 'content-type': 'application/json' }); response.end('{"status":"<img src=x onerror=window.__importOwned=1>"}'); return true;
    }
    return false;
  };
  const { page } = await rig(t, {
    route,
    extraStrings: {
      'install.backup.dynamic.backup_failed_prefix': 'LOC backup failed:',
      'install.backup.dynamic.export_failed_prefix': 'LOC export failed:',
      'install.backup.dynamic.import_failed_prefix': 'LOC import failed:',
    },
    html: '<input id="bk-pw" value="secret"><input id="bk-plain" type="checkbox"><input id="bk-comp" type="checkbox"><div id="bk-msg"></div><div id="cfg-export-result"></div><div id="cfg-import-result"></div><input id="cfg-import" type="file" onchange="configImport(this)">',
  });
  await page.evaluate(() => window.doBackup(document.createElement('button')));
  await page.waitForFunction(() => document.querySelector('#bk-msg [lang="en"]'));
  await page.evaluate(() => window.configExport(false, document.createElement('button')));
  await page.waitForFunction(() => document.querySelector('#cfg-export-result [lang="en"]'));
  await page.locator('#cfg-import').setInputFiles({ name: 'config.json', mimeType: 'application/json', buffer: Buffer.from('{}') });
  await page.waitForFunction(() => document.querySelector('#cfg-import-result [lang="en"]'));
  const outcomes = await page.evaluate(() => ['bk-msg', 'cfg-export-result', 'cfg-import-result'].map((id) => {
    const node = document.getElementById(id);
    return { text: node.textContent, raw: node.querySelector('[lang="en"]')?.textContent, images: node.querySelectorAll('img').length };
  }));
  assert.deepEqual(outcomes, [
    { text: 'LOC backup failed: <img src=x onerror=window.__backupOwned=1>', raw: '<img src=x onerror=window.__backupOwned=1>', images: 0 },
    { text: 'LOC export failed: <img src=x onerror=window.__exportOwned=1>', raw: '<img src=x onerror=window.__exportOwned=1>', images: 0 },
    { text: 'LOC import failed: <img src=x onerror=window.__importOwned=1>', raw: '<img src=x onerror=window.__importOwned=1>', images: 0 },
  ]);
  assert.deepEqual(await page.evaluate(() => ({
    backup: typeof window.__backupOwned,
    export: typeof window.__exportOwned,
    import: typeof window.__importOwned,
  })), { backup: 'undefined', export: 'undefined', import: 'undefined' });
});

browserTest('Install power-safety alerts localize repair and acknowledgement states', async (t) => {
  const source = await readFile(powerAsset, 'utf8');
  const strings = {
    'runtime.power_safety.repair.applying': 'LOC applying',
    'runtime.power_safety.repair.partial': 'LOC partial',
    'runtime.power_safety.button.hide': 'LOC hide caution',
    'runtime.power_safety.button.hide_title': 'LOC hide title',
    'runtime.power_safety.ack.saving': 'LOC saving',
    'runtime.power_safety.ack.not_hidden': 'LOC not hidden',
  };
  const projection = JSON.stringify({ locale: 'de', strings, languages: Object.fromEntries(Object.keys(strings).map((key) => [key, 'de'])) });
  const calls = [];
  const server = createServer((request, response) => {
    const path = new URL(request.url, 'http://panel.test').pathname;
    if (path === '/power-safety.js') { response.writeHead(200, { 'content-type': 'application/javascript' }); response.end(source); return; }
    if (path === '/api/v1/power-safety/repair') {
      calls.push(path); response.writeHead(200, { 'content-type': 'application/json' });
      response.end(JSON.stringify({ status: 'partial', message: 'raw partial detail', power_safety: { acknowledge_available: true, acknowledgement_fingerprint: 'fingerprint-1' } })); return;
    }
    if (path === '/api/v1/power-safety/acknowledge') {
      calls.push(path); response.writeHead(409, { 'content-type': 'application/json' });
      response.end(JSON.stringify({ acknowledged: false, message: 'raw acknowledgement detail' })); return;
    }
    response.writeHead(200, { 'content-type': 'text/html' });
    response.end(`<!doctype html><html lang="de"><body><script id="ha-i18n" type="application/json">${projection}</script>
      <script>window.HaI18n={locale:'de',t:(key,fallback)=>(${JSON.stringify(strings)})[key]||fallback};</script>
      <div data-power-safety-banner><form action="/api/v1/power-safety/repair" data-power-safety-repair>
      <button type="submit">Repair</button><span class="power-safety-repair-result"></span></form></div>
      <script src="/power-safety.js"></script></body></html>`);
  });
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  t.after(async () => { await browser.close(); server.closeAllConnections?.(); await new Promise((done) => server.close(done)); });
  await page.goto(`http://127.0.0.1:${server.address().port}/`, { waitUntil: 'domcontentloaded' });
  await page.click('button[type="submit"]');
  await page.waitForFunction(() => document.querySelector('.power-safety-acknowledge-result')?.textContent === 'LOC partial');
  assert.equal(await page.locator('button[type="submit"]').textContent(), 'LOC hide caution');
  assert.equal(await page.locator('button[type="submit"]').getAttribute('title'), 'LOC hide title');
  await page.click('button[type="submit"]');
  await page.waitForFunction(() => document.querySelector('.power-safety-acknowledge-result')?.textContent === 'LOC not hidden');
  assert.deepEqual(calls, ['/api/v1/power-safety/repair', '/api/v1/power-safety/acknowledge']);
});
