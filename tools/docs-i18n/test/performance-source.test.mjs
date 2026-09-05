import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const toolRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const repository = path.dirname(path.dirname(toolRoot));

test("performance guide binds current UI labels and collector measurement contracts", () => {
  const guide = fs.readFileSync(path.join(repository, "docs/performance.md"), "utf8");
  const catalogue = JSON.parse(fs.readFileSync(
    path.join(repository, "app/src/main/assets/i18n/en.json"),
    "utf8",
  )).strings;
  const collector = fs.readFileSync(
    path.join(repository, "scripts/measure-dashboard-performance.py"),
    "utf8",
  );

  for (const key of [
    "configure.renderer.builtin",
    "dashboard.camera.title",
    "dashboard.card.performance",
    "dashboard.card.remote_webview",
    "dashboard.card.top_processes",
    "dashboard.diagnostics_dump.link",
    "entities.control.reset",
    "entities.control.scan",
    "settings.dashboard_entity_learning.label",
  ]) {
    assert.ok(guide.includes(`**${catalogue[key].text}**`), key);
  }
  assert.match(guide, /requested frame rate beside the delivered frame rate/);
  assert.match(guide, /delivered bitrate beside the encoder bitrate cap/);
  assert.match(collector, /if len\(samples\) < 3:/);
  assert.match(collector, /if summary\["system_cpu_pct"\]\["count"\] < 3:/);
  assert.match(collector, /if summary\["renderer_main_pct"\]\["count"\] < 3:/);
  assert.match(guide, /fewer than three samples, fewer than three whole-panel CPU samples or fewer than three native renderer-main CPU samples/);

  assert.equal(guide.includes("**Automatic dashboard entity filter**"), false);
  assert.equal(guide.includes("the frame rate and bitrate the stream was asked for"), false);
});
