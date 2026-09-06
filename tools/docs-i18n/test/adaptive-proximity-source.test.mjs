import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { canonicalJson, sha256 } from "../lib/contract.mjs";
import { inventoryMarkdown, reconstructMarkdown } from "../lib/markdown.mjs";

const toolRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const repository = path.dirname(path.dirname(toolRoot));
const document = "docs/adaptive-proximity.md";

test("adaptive proximity guide freezes the reviewed one-fallback and sixteen-translation boundary", () => {
  const source = fs.readFileSync(path.join(repository, document), "utf8");
  const inventory = inventoryMarkdown(document, source);
  const policy = JSON.parse(fs.readFileSync(
    path.join(repository, "docs/i18n/consequential-segments.json"),
    "utf8",
  ));
  const entry = policy.documents.find((candidate) => candidate.document === document);
  const fallbackId = inventory.segments[8].segmentId;

  assert.equal(sha256(Buffer.from(source, "utf8")), "cb5df3dc051ba5351465323e3c7f2f66a232c10b08a3fc7fb12f8e9017509e82");
  assert.equal(inventory.inventorySha256, "d16c2246991a8aaa3ce8e58ed0f8444d240d6dcd1f260a7f54ac6a6e17c4cedd");
  assert.equal(inventory.segments.length, 17);
  assert.ok(entry, document);
  assert.equal(entry.sourceSha256, sha256(Buffer.from(source, "utf8")));
  assert.equal(entry.segmentCount, 17);
  assert.deepEqual(entry.consequentialSegments, [fallbackId]);
  assert.ok(inventory.segments.filter((_segment, index) => index !== 8)
    .every((segment) => !entry.consequentialSegments.includes(segment.segmentId)));
});

test("adaptive proximity guide identity migration preserves every byte and structure", () => {
  const source = fs.readFileSync(path.join(repository, document), "utf8");
  const inventory = inventoryMarkdown(document, source);
  const records = inventory.segments.map((segment) => ({
    document,
    segmentId: segment.segmentId,
    sourceSha256: segment.sourceSha256,
    translation: segment.maskedSource,
  }));
  const reconstructed = reconstructMarkdown(inventory, records);

  assert.equal(reconstructed.body, source);
  assert.equal(sha256(canonicalJson(reconstructed.structuralProjection)), "bcb83b2051480695135a145e1ba2da43928de9262fd493b43ae40c61a8059cb5");
  assert.deepEqual(reconstructed.structuralProjection, inventory.structuralProjection);
});
