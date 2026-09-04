import assert from 'node:assert/strict';
import fs from 'node:fs';
import { test } from 'node:test';
import { chromium } from 'playwright-core';

const chrome = process.env.CHROME || '/usr/bin/chromium';
const entitiesSource = fs.readFileSync('../app/src/main/assets/entities.js', 'utf8');
const i18nSource = fs.readFileSync('../app/src/main/assets/i18n.js', 'utf8');

function table(id, filter) {
  return `<section class="entity-list" data-table="${id}" data-filter="${filter}">
    <table><thead><tr><th><button data-sort="entity_id">Entity</button></th></tr></thead><tbody></tbody></table>
    <button class="entity-prev"></button><button class="entity-next"></button>
    <input class="entity-select-page" type="checkbox"><span class="entity-selected"></span><span class="entity-msg"></span>
    <button data-bulk="pinned"></button><button data-bulk="auto"></button><button data-bulk="forced_exclude"></button>
    ${filter === 'candidate' ? '<button data-all-candidates="true">Pin all</button>' : ''}
  </section>`;
}

const translated = {
  'entities.status.state.learning': 'LOC learning',
  'entities.status.state.observing': 'LOC observing',
  'entities.issue.template-selector.summary': 'LOC template summary',
  'entities.issue.template-selector.reason': 'LOC template reason',
  'entities.issue.template-selector.recommendation': 'LOC template recommendation',
  'entities.issue.selector-broad.summary': 'LOC selector summary',
  'entities.issue.default-broad.reason': 'LOC broad reason',
  'entities.issue.default-broad.recommendation': 'LOC broad recommendation',
  'entities.issue.kio\u0073k-mode-limited-support.summary': 'LOC kiosk summary',
  'entities.issue.kio\u0073k-mode-limited-support.reason': 'LOC kiosk reason',
  'entities.issue.kio\u0073k-mode-limited-support.recommendation': 'LOC kiosk recommendation',
  'entities.issue.view.untitled': 'LOC View {index}',
  'entities.issue.card.kiosk_mode_configuration': 'LOC HACS Kiosk title',
};
const projection = { locale: 'de', strings: translated, languages: Object.fromEntries(Object.keys(translated).map((key) => [key, 'de'])) };

function issue(overrides = {}) {
  return {
    type: 'unbounded_selector', blocking: true, ignored: false, ignorable: false,
    fingerprint: Math.random().toString(16), view_title: 'User view', card_title: '',
    source_locations: ['dashboard.views[0]'], candidate_count: 70, limit: 64,
    rule_summary: 'RAW default summary', reason: 'RAW default reason', recommendation: 'RAW default recommendation',
    ...overrides,
  };
}

function html() {
  const issues = [
    issue({ fingerprint: 'fixed', presentation_code: 'template-selector', rule_summary: 'RAW fixed summary' }),
    issue({ fingerprint: 'mixed', presentation_code: 'selector-broad', rule_summary: 'RAW mixed selector evidence', view_title_index: 2, card_title_hacs_kiosk: true }),
    issue({ fingerprint: 'absent', rule_summary: 'RAW absent summary' }),
    issue({ fingerprint: 'unknown', presentation_code: 'future-code', rule_summary: 'RAW unknown summary' }),
    issue({ fingerprint: 'malformed', presentation_code: 'template-selector', presentation_params: { unexpected: true }, rule_summary: 'RAW malformed summary' }),
    issue({ fingerprint: 'empty-params', presentation_code: 'template-selector', presentation_params: {}, rule_summary: 'RAW present empty params summary' }),
    issue({ fingerprint: 'untranslated', presentation_code: 'dashboard-strategy', rule_summary: 'RAW untranslated summary' }),
    issue({ fingerprint: 'user-title', presentation_code: 'template-selector', view_title: 'View 2', card_title: 'HACS Kiosk Mode configuration', rule_summary: 'RAW authored-title summary' }),
    issue({ fingerprint: 'kiosk', presentation_code: 'kiosk_mode-limited-support', rule_summary: 'RAW kiosk compatibility summary' }),
    issue({ fingerprint: 'hostile', rule_summary: '<img src=x onerror=window.__owned=1>', reason: '<svg onload=window.__owned=2>', recommendation: '</script><script>window.__owned=3</script>', view_title: '<b>hostile title</b>' }),
  ];
  const status = { state: 'learning', sync_running: false, stream_entity_count: 4, stream_mode: 'filtered', catalog_count: 9, suggested_count: 1, blocking_issue_count: 9, ignored_issue_count: 0, unresolved_count: 0, last_sync_at: 0, db_bytes: 8, auto_static: true, auto_runtime: true, apply_required: false };
  const bootstrap = `window.__owned=0;window.__polls=[];window.setInterval=(fn)=>{window.__polls.push(fn);return window.__polls.length};window.__status=${JSON.stringify(status)};window.__issues=${JSON.stringify(issues)};window.fetch=async(url,options={})=>{if((options.method||'GET')==='POST')return{ok:true,status:200,json:async()=>({stream_changed:false,entity_count:4}),text:async()=>''};if(url.includes('/entities/issues'))return{ok:true,status:200,json:async()=>({items:window.__issues,dashboard_issue_count:window.__issues.length,blocking_issue_count:window.__issues.length,ignored_issue_count:0,dynamic_expressions:[]})};if(url.includes('/entities?'))return{ok:true,status:200,json:async()=>({items:[],total:0})};return{ok:true,status:200,json:async()=>window.__status,text:async()=>''}};`;
  return `<!doctype html><html lang="de"><body>
    <script id="ha-i18n" type="application/json">${JSON.stringify(projection)}</script>
    <script>${i18nSource.replaceAll('</script>', '<\\/script>')}</script><script>${bootstrap.replaceAll('</script>', '<\\/script>')}</script>
    <div id="entity-status"></div><input id="entity-search"><div id="entity-search-status"></div>
    <button id="entity-sync"></button><button id="entity-activate"></button><button id="entity-reset"></button><div id="entity-action-result"></div>
    <input id="entity-auto-static" type="checkbox"><input id="entity-auto-runtime" type="checkbox">
    <section id="entity-issues"><div id="entity-issues-summary"></div><div id="entity-issues-list"></div><button id="entity-issues-rescan"></button></section>
    <section id="entity-dynamic" hidden><div id="entity-dynamic-list"></div></section>
    ${table('current', 'subscribed')}${table('suggested', 'candidate')}${table('review', 'review')}
    <script>${entitiesSource.replaceAll('</script>', '<\\/script>')}</script></body></html>`;
}

test('Entities uses typed presentation metadata and exact safe fallback boundaries', { skip: !fs.existsSync(chrome), timeout: 15_000 }, async () => {
  const browser = await chromium.launch({ executablePath: chrome, headless: true, args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage();
    const dialogs = [];
    page.on('dialog', async (dialog) => { dialogs.push(dialog.message()); await dialog.accept(); });
    await page.setContent(html(), { waitUntil: 'load' });
    await page.waitForFunction(() => document.querySelectorAll('.entity-issue').length === 10);

    const rows = Object.fromEntries(await page.locator('.entity-issue').evaluateAll((nodes) => nodes.map((node) => [node.dataset.fingerprint, { text: node.textContent, html: node.innerHTML }])));
    assert.match(rows.fixed.text, /LOC template summary/);
    assert.doesNotMatch(rows.fixed.text, /RAW fixed summary/, 'fixed localized summary must not be duplicated as English evidence');
    assert.match(rows.mixed.text, /LOC selector summary/);
    assert.match(rows.mixed.text, /RAW mixed selector evidence/, 'mixed family retains exact English evidence');
    assert.match(rows.mixed.text, /LOC View 2/);
    assert.match(rows.mixed.text, /LOC HACS Kiosk title/);
    for (const [fingerprint, raw] of [['absent', 'RAW absent summary'], ['unknown', 'RAW unknown summary'], ['malformed', 'RAW malformed summary'], ['empty-params', 'RAW present empty params summary'], ['untranslated', 'RAW untranslated summary']]) {
      assert.match(rows[fingerprint].text, new RegExp(raw));
    }
    assert.match(rows['user-title'].text, /View 2 · HACS Kiosk Mode configuration/);
    assert.doesNotMatch(rows['user-title'].text, /LOC View 2|LOC HACS Kiosk title/, 'English-looking user titles are never inferred as synthetic');
    assert.match(rows.kiosk.text, /LOC kiosk summary/, 'underscore wire code maps explicitly to hyphenated catalogue keys');
    assert.doesNotMatch(rows.kiosk.text, /RAW kiosk compatibility summary/);
    assert.equal(await page.evaluate(() => window.__owned), 0);
    assert.equal(await page.locator('img, svg').count(), 0, 'hostile values remain text nodes');
    assert.match(rows.hostile.text, /<img src=x onerror=window.__owned=1>/);
    assert.equal(await page.locator('[data-fingerprint="mixed"] code[lang="en"]').textContent(), 'RAW mixed selector evidence');
    assert.equal(await page.locator('[data-fingerprint="absent"] .entity-issue-summary').getAttribute('lang'), 'en');

    assert.match(await page.locator('#entity-status').textContent(), /LOC learning/);
    await page.evaluate(async () => { window.__status.state = 'observing'; await window.__polls[0](); });
    await page.waitForFunction(() => document.querySelector('#entity-status').textContent.includes('LOC observing'));
    await page.evaluate(async () => { window.__status.state = 'future_state'; window.__status.error = 'RAW status error'; await window.__polls[0](); });
    await page.waitForFunction(() => document.querySelector('#entity-status').textContent.includes('future_state'));
    assert.match(await page.locator('#entity-status').textContent(), /future_state/);
    assert.equal(await page.locator('#entity-status .hot[lang="en"]').textContent(), 'RAW status error');

    await page.locator('[data-table="suggested"] [data-all-candidates]').click();
    await page.waitForTimeout(20);
    assert.equal(dialogs.at(-1), 'Pin 1 suggested entity?');
    await page.evaluate(async () => { window.__status.suggested_count = 2; await window.__polls[0](); });
    await page.locator('[data-table="suggested"] [data-all-candidates]').click();
    await page.waitForTimeout(20);
    assert.equal(dialogs.at(-1), 'Pin all 2 suggested entities?');
  } finally {
    await browser.close();
  }
});
