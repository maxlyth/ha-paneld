import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { existsSync } from 'node:fs';
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { chromium } from 'playwright-core';

const asset = fileURLToPath(new URL('../app/src/main/assets/install.js', import.meta.url));
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
  if (options.untranslated) delete languages[typeof presentations[options.untranslated] === 'string' ? presentations[options.untranslated] : presentations[options.untranslated].other];
  const payload = JSON.stringify({ locale: 'zh-Hans', strings, languages }).replaceAll('<', '\\u003c');
  const status = options.status || { warnings: [], warning_presentations: [] };
  const server = createServer((request, response) => {
    const path = new URL(request.url, 'http://panel.test').pathname;
    if (path === '/install.js') { response.writeHead(200, { 'content-type': 'application/javascript' }); response.end(source); return; }
    if (path === '/api/v1/radio') { response.writeHead(200, { 'content-type': 'application/json' }); response.end('{"present":false}'); return; }
    if (path === '/api/v1/status') { response.writeHead(200, { 'content-type': 'application/json' }); response.end(JSON.stringify(status)); return; }
    const helper = options.noHelper ? '' : `<script>window.HaI18n={locale:'zh-Hans',t:(key,fallback,values)=>{const all=${JSON.stringify(strings)};return String(Object.prototype.hasOwnProperty.call(all,key)?all[key]:fallback).replace(/\\{([A-Za-z][A-Za-z0-9_]*)\\}/g,(token,name)=>values&&Object.prototype.hasOwnProperty.call(values,name)?String(values[name]):token);}};</script>`;
    response.writeHead(200, { 'content-type': 'text/html' });
    response.end(`<!doctype html><html lang="zh-Hans"><body><script id="ha-i18n" type="application/json">${payload}</script>${helper}<div id="audit-out"></div><script>window.CardColumnAlignment={attach:()=>()=>{}};</script><script src="/install.js"></script></body></html>`);
  });
  await new Promise((done) => server.listen(0, '127.0.0.1', done));
  const browser = await chromium.launch({ executablePath: chrome, headless: true });
  const page = await browser.newPage();
  t.after(async () => { await browser.close(); server.closeAllConnections?.(); await new Promise((done) => server.close(done)); });
  await page.goto(`http://127.0.0.1:${server.address().port}/`, { waitUntil: 'domcontentloaded' });
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
