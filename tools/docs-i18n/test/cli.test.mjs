import assert from "node:assert/strict";
import test from "node:test";

import { parseArguments } from "../cli.mjs";

test("CLI accepts only the fixed provider-neutral interfaces", () => {
  assert.deepEqual(parseArguments([
    "validate", "--repository", "../..", "--manifest", "docs/i18n/manifest.json",
  ]), {
    command: "validate",
    options: { repository: "../..", manifest: "docs/i18n/manifest.json" },
  });
  assert.throws(() => parseArguments(["generate", "--provider", "remote"]), /unknown command/);
  assert.throws(() => parseArguments([
    "validate", "--repository", "../..", "--manifest", "x", "--token", "secret",
  ]), /unsupported option/);
  assert.throws(() => parseArguments([
    "validate", "--repository", "../..", "--repository", ".", "--manifest", "x",
  ]), /duplicate option/);
});

test("CLI plan and apply require exact explicit inputs", () => {
  assert.throws(() => parseArguments(["plan", "--repository", "."]), /missing --source-revision/);
  assert.throws(() => parseArguments([
    "apply", "--repository", ".", "--manifest", "manifest.json", "--locale", "de",
  ]), /missing --results/);
  assert.deepEqual(parseArguments([
    "export-plan", "--repository", ".", "--manifest", "manifest.json", "--output", "/private/plan.json",
  ]).command, "export-plan");
});
