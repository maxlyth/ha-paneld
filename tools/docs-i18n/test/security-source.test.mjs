import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { canonicalJson, sha256 } from "../lib/contract.mjs";
import { inventoryMarkdown, PARSER_VERSIONS, reconstructMarkdown } from "../lib/markdown.mjs";

const toolRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const repository = path.dirname(path.dirname(toolRoot));

test("parser provenance matches the exact installed parse5 dependency", () => {
  const lock = JSON.parse(fs.readFileSync(path.join(toolRoot, "package-lock.json"), "utf8"));
  assert.equal(lock.packages["node_modules/parse5"].version, "8.0.1");
  assert.equal(PARSER_VERSIONS.parse5, lock.packages["node_modules/parse5"].version);
});

test("security guide freezes the reviewed 47-fallback and six-heading boundary", () => {
  const source = fs.readFileSync(path.join(repository, "docs/security-mode.md"), "utf8");
  const inventory = inventoryMarkdown("docs/security-mode.md", source);
  const policy = JSON.parse(fs.readFileSync(
    path.join(repository, "docs/i18n/consequential-segments.json"),
    "utf8",
  ));
  const security = policy.documents.find((document) => document.document === "docs/security-mode.md");
  const translatedOwners = new Set([1, 6, 14, 23, 44, 49]);
  const fallbackIds = inventory.segments
    .filter((segment, index) => !translatedOwners.has(index + 1))
    .map((segment) => segment.segmentId)
    .sort();

  assert.equal(sha256(Buffer.from(source, "utf8")), "284f809f1b1500a4e2102cbef5579bd510773e1f21e8daf9868d64e3fd1218e3");
  assert.equal(inventory.segments.length, 53);
  assert.equal(security.segmentCount, 53);
  assert.equal(security.consequentialSegments.length, 47);
  assert.deepEqual(security.consequentialSegments, fallbackIds);
  assert.ok([...translatedOwners].every((owner) => inventory.segments[owner - 1].ownerType === "heading"));
  assert.equal(inventory.segments[15].maskedSource, "Complete the operation as follows:");
  assert.ok(security.consequentialSegments.includes(inventory.segments[15].segmentId));
});

test("security guide identity migration preserves every byte and link", () => {
  const source = fs.readFileSync(path.join(repository, "docs/security-mode.md"), "utf8");
  const inventory = inventoryMarkdown("docs/security-mode.md", source);
  const records = inventory.segments.map((segment) => ({
    document: "docs/security-mode.md",
    segmentId: segment.segmentId,
    sourceSha256: segment.sourceSha256,
    translation: segment.maskedSource,
  }));
  const reconstructed = reconstructMarkdown(inventory, records);
  assert.equal(reconstructed.body, source);
  assert.equal(
    sha256(canonicalJson(reconstructed.structuralProjection)),
    sha256(canonicalJson(inventory.structuralProjection)),
  );
});
