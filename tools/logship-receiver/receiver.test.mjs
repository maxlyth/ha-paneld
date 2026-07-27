import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import test from 'node:test'
import { fileURLToPath } from 'node:url'
import { parseSyslog, report, reportNdjson, terminalSafe } from './receiver.mjs'

function captureStdout (action) {
  const original = process.stdout.write
  let output = ''
  process.stdout.write = (chunk) => { output += String(chunk); return true }
  try { action() } finally { process.stdout.write = original }
  return output
}

test('terminalSafe renders ANSI, line controls, C1 controls and bidi controls visibly', () => {
  const input = '\u001b[2J\rforged\n\u009b31m\u202eright-to-left'
  const safe = terminalSafe(input)
  assert.equal(safe, '\\x1b[2J\\x0dforged\\x0a\\x9b31m\\u202eright-to-left')
  assert.doesNotMatch(safe, /[\u0000-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/)
})

test('human syslog output cannot inject terminal controls or forged lines', () => {
  const frame = '<14>1 2026-07-27T00:00:00Z host ha-paneld - - - marker\u001b[2J\rFORGED'
  const output = captureStdout(() => report('udp', 'peer\u001b[H', Buffer.from(frame), { quiet: false, json: false }))
  assert.match(output, /peer\\x1b\[H/)
  assert.match(output, /marker\\x1b\[2J\\x0dFORGED/)
  assert.doesNotMatch(output, /\u001b|\r/)
})

test('human and JSON HTTP output sanitise attacker-controlled fields', () => {
  const body = JSON.stringify({ timestamp: 'now', host: 'host\u009b31m', app: 'app', message: 'msg\u001b[2J\u202eflip' })
  for (const json of [false, true]) {
    const output = captureStdout(() => reportNdjson('peer\u001b[H', body, { quiet: false, json }))
    assert.match(output, /\\x1b/)
    assert.match(output, /\\x9b/)
    assert.match(output, /\\u202e/)
    assert.doesNotMatch(output, /\u001b|\u009b|\u202e/)
  }
})

test('argument errors cannot inject terminal controls', () => {
  const receiver = fileURLToPath(new URL('./receiver.mjs', import.meta.url))
  const result = spawnSync(process.execPath, [receiver, '\u001b[2J'], { encoding: 'utf8' })
  assert.equal(result.status, 2)
  assert.match(result.stderr, /\\x1b\[2J/)
  assert.doesNotMatch(result.stderr, /\u001b/)
})

test('RFC5424 parser handles adjacent structured-data elements and escaped brackets', () => {
  const parsed = parseSyslog('<14>1 now host app proc msg [meta value="a\\]b"][more key="value"] payload')
  assert.deepEqual(parsed, {
    ok: true,
    facility: 'user',
    severity: 'info',
    pri: 14,
    version: 1,
    timestamp: 'now',
    hostname: 'host',
    appName: 'app',
    procId: 'proc',
    msgId: 'msg',
    structuredData: '[meta value="a\\]b"][more key="value"]',
    message: 'payload',
  })
})

test('RFC5424 parser rejects adversarial structured data in linear time', () => {
  const frame = `<14>1 now host app proc msg ${'[]'.repeat(100_000)}!`
  const started = performance.now()
  assert.equal(parseSyslog(frame).ok, false)
  assert.ok(performance.now() - started < 1_000, 'malformed structured data should be rejected promptly')
})
