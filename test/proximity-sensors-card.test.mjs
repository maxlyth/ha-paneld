import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

const source = await readFile(process.argv[2] || new URL('../app/src/main/assets/info.js', import.meta.url), 'utf8');
const context = vm.createContext({ i18nText: (_key, text, args = {}) => text.replace(/\{(\w+)\}/g, (_, key) => String(args[key])) });
vm.runInContext(source.slice(source.indexOf('function proximityPhase('), source.indexOf('function sensorsCard(')), context);
const reading = value => JSON.parse(JSON.stringify(context.proximityReading(value)));

test('calibrated ranged source displays API level and raw value even while far', () => {
  assert.deepEqual(reading({mode:'ranged', health:'healthy', presenceReady:true, near:false, level:11, raw:971}), {val:'11%', suf:'raw 971 · far'});
  assert.deepEqual(reading({mode:'ranged', health:'healthy', presenceReady:true, near:true, level:89, raw:1200}), {val:'89%', suf:'raw 1200 · near'});
});
test('uncalibrated and active setup show actual scalar readings without provisional percentage', () => {
  assert.deepEqual(reading({mode:'ranged', health:'healthy', phase:'calibration_required', raw:971}), {val:'raw 971', suf:'calibration required'});
  assert.equal(reading({mode:'ranged', health:'healthy', presenceReady:true, sessionActive:true, phase:'calibrating', level:80, raw:1100}).val, 'raw 1100');
});
test('unavailable source never presents retained scalar readings as current', () => {
  assert.deepEqual(reading({mode:'ranged',health:'source_unavailable',presenceReady:true,near:true,level:99,raw:1300}), {val:'· unavailable',suf:''});
});
test('binary source keeps its state representation and never fabricates a distance', () => {
  assert.deepEqual(reading({mode:'binary',health:'healthy',near:true,raw:0,level:100,presenceReady:true}), {val:'near',suf:'raw 0'});
});
