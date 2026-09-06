import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { canonicalJson, sha256 } from "../lib/contract.mjs";
import { inventoryMarkdown, reconstructMarkdown } from "../lib/markdown.mjs";

const toolRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const repository = path.dirname(path.dirname(toolRoot));
const document = "docs/provisioning-safety.md";

test("provisioning safety guide freezes the reviewed 32-fallback and ten-translation boundary", () => {
  const source = fs.readFileSync(path.join(repository, document), "utf8");
  const inventory = inventoryMarkdown(document, source);
  const policy = JSON.parse(fs.readFileSync(
    path.join(repository, "docs/i18n/consequential-segments.json"),
    "utf8",
  ));
  const entry = policy.documents.find((candidate) => candidate.document === document);
  const translatedOrdinals = new Set([1, 2, 3, 7, 10, 19, 26, 30, 36, 40]);
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
    "46ebc912fdd3abdbfca8e86cb45ff775cab4543ce3bfcb0e20fcda2367c3eba7",
  );
  assert.equal(inventory.segments.length, 42);
  assert.equal(translatedIds.length, 10);
  assert.equal(fallbackIds.length, 32);
  assert.equal(inventory.segments[1].ownerType, "paragraph");
  assert.ok([...translatedOrdinals]
    .filter((ordinal) => ordinal !== 2)
    .every((ordinal) => inventory.segments[ordinal - 1].ownerType === "heading"));
  assert.ok(entry, document);
  assert.equal(entry.sourceSha256, sha256(Buffer.from(source, "utf8")));
  assert.equal(entry.segmentCount, 42);
  assert.equal(entry.consequentialSegments.length, 32);
  assert.deepEqual(entry.consequentialSegments, fallbackIds);
  assert.ok(translatedIds.every((id) => !entry.consequentialSegments.includes(id)));
});

test("provisioning safety guide identity migration preserves every byte and structure", () => {
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
    "7f97dd3f4168db33873fa335493e6f22ba38f1c9f298396d46aa012b1ae0162b",
  );
  assert.deepEqual(reconstructed.structuralProjection, inventory.structuralProjection);
});
