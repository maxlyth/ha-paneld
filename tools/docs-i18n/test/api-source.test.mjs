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
    35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 54, 56, 57, 59, 70, 84, 93, 96, 99,
  ]);
  const translatedIds = inventory.segments
    .filter((_segment, index) => translatedOrdinals.has(index + 1))
    .map((segment) => segment.segmentId)
    .sort();
  const fallbackIds = inventory.segments
    .filter((_segment, index) => !translatedOrdinals.has(index + 1))
    .map((segment) => segment.segmentId)
    .sort();

  assert.equal(sha256(Buffer.from(source, "utf8")), "8e118341b75660f93b763f2966f39e8f94ab43289c8748f9d40d4f4658495fba");
  assert.equal(inventory.inventorySha256, "9afa5d133459b142c1f7b5c75a2a1ae5da7c3d211270c06166bd925d43c9277f");
  assert.equal(inventory.segments.length, 101);
  assert.equal(translatedIds.length, 39);
  assert.equal(fallbackIds.length, 62);
  assert.ok(entry, document);
  assert.equal(entry.sourceSha256, sha256(Buffer.from(source, "utf8")));
  assert.equal(entry.segmentCount, 101);
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
  assert.equal(sha256(canonicalJson(reconstructed.structuralProjection)), "b34dbf39b9b921107ab7fae6e9cb9cfdd332254e20c66930bb4d35a25a332384");
  assert.deepEqual(reconstructed.structuralProjection, inventory.structuralProjection);
});
