import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const toolRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const repository = path.dirname(path.dirname(toolRoot));

test("built-in renderer guide names the exact current UI controls and status surfaces", () => {
  const guide = fs.readFileSync(path.join(repository, "docs/built-in-renderer.md"), "utf8");
  const catalogue = JSON.parse(fs.readFileSync(
    path.join(repository, "app/src/main/assets/i18n/en.json"),
    "utf8",
  )).strings;
  const nativeStrings = fs.readFileSync(
    path.join(repository, "app/src/main/res/values/strings.xml"),
    "utf8",
  );
  const externalBus = fs.readFileSync(
    path.join(repository, "app/src/main/kotlin/io/github/maxlyth/hapaneld/ExternalBus.kt"),
    "utf8",
  );

  for (const key of [
    "settings.dashboard_entity_learning.label",
    "settings.dashboard_zoom.label",
    "dashboard.card.runtime_diagnostics",
  ]) {
    assert.ok(guide.includes(`**${catalogue[key].text}**`), key);
  }
  const reconnecting = /<string name="dashboard_reconnecting">([^<]+)<\/string>/.exec(nativeStrings)?.[1];
  assert.equal(reconnecting, "Reconnecting to Home Assistant…");
  assert.ok(guide.includes(`**${reconnecting}**`));
  assert.match(externalBus, /\.put\("hasSettingsScreen", true\)/);
  assert.match(guide, /\*\*App settings\*\*/);

  for (const stale of [
    "**Automatic dashboard entity filter**",
    "**Dashboard zoom**",
    '"Reconnecting…"',
    "**App Configuration**",
    "the Runtime card",
  ]) {
    assert.equal(guide.includes(stale), false, `stale renderer term remains: ${stale}`);
  }
});
