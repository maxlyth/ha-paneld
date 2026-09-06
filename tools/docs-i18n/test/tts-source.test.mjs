import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { canonicalJson, sha256 } from "../lib/contract.mjs";
import { inventoryMarkdown, reconstructMarkdown } from "../lib/markdown.mjs";

const toolRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const repository = path.dirname(path.dirname(toolRoot));
const document = "docs/tts.md";

test("TTS guide freezes the reviewed nine-fallback and ten-translation boundary", () => {
  const source = fs.readFileSync(path.join(repository, document), "utf8");
  const inventory = inventoryMarkdown(document, source);
  const policy = JSON.parse(fs.readFileSync(
    path.join(repository, "docs/i18n/consequential-segments.json"),
    "utf8",
  ));
  const entry = policy.documents.find((candidate) => candidate.document === document);
  const translatedOrdinals = new Set([1, 3, 4, 6, 8, 9, 10, 12, 13, 15]);
  const translatedIds = inventory.segments
    .filter((_segment, index) => translatedOrdinals.has(index + 1))
    .map((segment) => segment.segmentId)
    .sort();
  const fallbackIds = inventory.segments
    .filter((_segment, index) => !translatedOrdinals.has(index + 1))
    .map((segment) => segment.segmentId)
    .sort();

  assert.equal(sha256(Buffer.from(source, "utf8")), "e1c3e419b91ed2d90d6f054ee40af677d94347a1ce46be89ebe60c3efa350ea9");
  assert.equal(inventory.inventorySha256, "f829ed8c8773abf615fc7141f2892fdb97c2dff4e06ba7ed5b3cdf6ec8f01b27");
  assert.equal(inventory.segments.length, 19);
  assert.equal(translatedIds.length, 10);
  assert.equal(fallbackIds.length, 9);
  assert.ok(entry, document);
  assert.equal(entry.sourceSha256, sha256(Buffer.from(source, "utf8")));
  assert.equal(entry.segmentCount, 19);
  assert.equal(entry.consequentialSegments.length, 9);
  assert.deepEqual(entry.consequentialSegments, fallbackIds);
  assert.ok(translatedIds.every((id) => !entry.consequentialSegments.includes(id)));
});

test("TTS guide identity migration preserves every byte and structure", () => {
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
  assert.equal(sha256(canonicalJson(reconstructed.structuralProjection)), "8a153905263c8f17deaa2b69d163dfb565b41bb3c8a06bb4b47916f3ef6bb40c");
  assert.deepEqual(reconstructed.structuralProjection, inventory.structuralProjection);
});
