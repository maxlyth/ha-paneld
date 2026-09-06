import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { canonicalJson, sha256 } from "../lib/contract.mjs";
import { inventoryMarkdown, reconstructMarkdown } from "../lib/markdown.mjs";

const toolRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const repository = path.dirname(path.dirname(toolRoot));
const document = "docs/adaptive-brightness.md";

test("adaptive brightness guide freezes the reviewed ten-fallback and nine-translation boundary", () => {
  const source = fs.readFileSync(path.join(repository, document), "utf8");
  const inventory = inventoryMarkdown(document, source);
  const policy = JSON.parse(fs.readFileSync(
    path.join(repository, "docs/i18n/consequential-segments.json"),
    "utf8",
  ));
  const entry = policy.documents.find((candidate) => candidate.document === document);
  const translatedOrdinals = new Set([1, 2, 3, 4, 5, 6, 9, 14, 18]);
  const translatedIds = inventory.segments
    .filter((_segment, index) => translatedOrdinals.has(index + 1))
    .map((segment) => segment.segmentId)
    .sort();
  const fallbackIds = inventory.segments
    .filter((_segment, index) => !translatedOrdinals.has(index + 1))
    .map((segment) => segment.segmentId)
    .sort();

  assert.equal(
    sha256(Buffer.from(source, "utf8")),
    "d31a42cf5d8d68e4ab6ae9588d305e98a81b7c2c616b1c59e50a229cc1965bdc",
  );
  assert.equal(inventory.inventorySha256, "48d1f3006a64f45108c8ab2c7e75c21f9d1870d1bfb429cee03f3429a9eee95c");
  assert.equal(inventory.segments.length, 19);
  assert.equal(translatedIds.length, 9);
  assert.equal(fallbackIds.length, 10);
  assert.ok(entry, document);
  assert.equal(entry.sourceSha256, sha256(Buffer.from(source, "utf8")));
  assert.equal(entry.segmentCount, 19);
  assert.equal(entry.consequentialSegments.length, 10);
  assert.deepEqual(entry.consequentialSegments, fallbackIds);
  assert.ok(translatedIds.every((id) => !entry.consequentialSegments.includes(id)));
});

test("adaptive brightness guide identity migration preserves every byte and structure", () => {
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
  assert.equal(
    sha256(canonicalJson(reconstructed.structuralProjection)),
    "3dd34a22845f4dd8d4d1a8a2a92be0f8b5bf03fe7f0fbad2958952bbd93d8d1a",
  );
  assert.deepEqual(reconstructed.structuralProjection, inventory.structuralProjection);
});
