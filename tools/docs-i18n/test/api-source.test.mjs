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
  const overlay = JSON.parse(fs.readFileSync(
    "/workspaces/ha-paneld-sidecar/docs/multilingual-review/api-risk-2026-09-06/overlay.json",
    "utf8",
  ));
  const translatedIds = overlay.segments
    .filter((segment) => segment.requiredState === "machine-cross-checked")
    .map((segment) => segment.segmentId)
    .sort();
  const fallbackIds = overlay.segments
    .filter((segment) => segment.requiredState === "english-fallback")
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
