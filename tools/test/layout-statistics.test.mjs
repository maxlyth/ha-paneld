import assert from 'node:assert/strict';
import test from 'node:test';
import {
  parseRunCount,
  requireBaselineRunCount,
  summarizeSamples,
} from './layout-statistics.mjs';

test('parseRunCount defaults to three and accepts bounded odd values', () => {
  assert.equal(parseRunCount(undefined), 3);
  assert.equal(parseRunCount('1'), 1);
  assert.equal(parseRunCount('5'), 5);
  assert.equal(parseRunCount('21'), 21);
});

test('parseRunCount rejects values without an unambiguous representative median', () => {
  for (const value of ['0', '2', '22', '1.5', 'many']) {
    assert.throws(() => parseRunCount(value), /RUNS must be an odd integer/);
  }
});

test('baseline updates require at least five samples', () => {
  assert.doesNotThrow(() => requireBaselineRunCount(5));
  assert.doesNotThrow(() => requireBaselineRunCount(21));
  for (const runs of [1, 3]) {
    assert.throws(
      () => requireBaselineRunCount(runs),
      /baseline updates require RUNS to be at least 5/,
    );
  }
});

test('summarizeSamples returns the whole median run and observed range', () => {
  const samples = [
    { cls: 0.042, by: { slow: 0.042 } },
    { cls: 0.010, by: { low: 0.010 } },
    { cls: 0.021, by: { representative: 0.021 } },
  ];

  assert.deepEqual(summarizeSamples(samples), {
    cls: 0.021,
    by: { representative: 0.021 },
    minCls: 0.010,
    maxCls: 0.042,
  });
  assert.deepEqual(samples.map((sample) => sample.cls), [0.042, 0.010, 0.021]);
});

test('summarizeSamples requires a non-empty odd sample set', () => {
  assert.throws(() => summarizeSamples([]), /at least one CLS sample/);
  assert.throws(
    () => summarizeSamples([{ cls: 0.01 }, { cls: 0.02 }]),
    /odd number of CLS samples/,
  );
});
