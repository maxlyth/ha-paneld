const DEFAULT_RUNS = 3;
const MAX_RUNS = 21;

export function parseRunCount(value) {
  const runs = value == null || value === '' ? DEFAULT_RUNS : Number(value);
  if (!Number.isInteger(runs) || runs < 1 || runs > MAX_RUNS || runs % 2 === 0) {
    throw new Error(`RUNS must be an odd integer from 1 to ${MAX_RUNS}`);
  }
  return runs;
}

export function requireBaselineRunCount(runs) {
  if (runs < 5) {
    throw new Error('baseline updates require RUNS to be at least 5');
  }
}

export function summarizeSamples(samples) {
  if (!Array.isArray(samples) || samples.length === 0) {
    throw new Error('at least one CLS sample is required');
  }
  if (samples.length % 2 === 0) {
    throw new Error('an odd number of CLS samples is required');
  }
  const ordered = [...samples].sort((a, b) => a.cls - b.cls);
  const representative = ordered[Math.floor(ordered.length / 2)];
  return {
    ...representative,
    minCls: ordered[0].cls,
    maxCls: ordered[ordered.length - 1].cls,
  };
}
