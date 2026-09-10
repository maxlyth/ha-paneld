import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { canonicalJson, sha256 } from "../lib/contract.mjs";
import { inventoryMarkdown, reconstructMarkdown } from "../lib/markdown.mjs";

const toolRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const repository = path.dirname(path.dirname(toolRoot));
const document = "docs/api.md";

test("API guide freezes the reviewed 62-fallback and 39-translation boundary", () => {
  const source = fs.readFileSync(path.join(repository, document), "utf8");
  const inventory = inventoryMarkdown(document, source);
  const policy = JSON.parse(fs.readFileSync(
    path.join(repository, "docs/i18n/consequential-segments.json"),
    "utf8",
  ));
  const entry = policy.documents.find((candidate) => candidate.document === document);
  const translatedOrdinals = new Set([
    1, 3, 4, 5, 6, 8, 9, 11, 13, 15, 18, 20, 21, 22, 23, 24, 25, 32, 33, 34,
    35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 55, 57, 58, 60, 71, 85, 94, 97, 100,
  ]);
  const translatedIds = inventory.segments
    .filter((_segment, index) => translatedOrdinals.has(index + 1))
    .map((segment) => segment.segmentId)
    .sort();
  const fallbackIds = inventory.segments
    .filter((_segment, index) => !translatedOrdinals.has(index + 1))
    .map((segment) => segment.segmentId)
    .sort();

  assert.equal(sha256(Buffer.from(source, "utf8")), "7f8e845766e9fc7902ea384d6222a1ebddcbb2d1d29437e3d5171c1ce652291c");
  assert.equal(inventory.inventorySha256, "6a3ff7a8d70a8a778dc7a59ec034b2767a8e1d4f52835657a33636769c257419");
  assert.equal(inventory.segments.length, 102);
  assert.equal(translatedIds.length, 39);
  assert.equal(fallbackIds.length, 63);
  assert.ok(entry, document);
  assert.equal(entry.sourceSha256, sha256(Buffer.from(source, "utf8")));
  assert.equal(entry.segmentCount, 102);
  assert.deepEqual(entry.consequentialSegments, fallbackIds);
  assert.ok(translatedIds.every((id) => !entry.consequentialSegments.includes(id)));
});

test("API guide identity migration preserves every byte and structure", () => {
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
  assert.equal(sha256(canonicalJson(reconstructed.structuralProjection)), "277682578013916512a04d3b413fae4cad4d5611cae7c1a8f638a3dc449479a1");
  assert.deepEqual(reconstructed.structuralProjection, inventory.structuralProjection);
});
