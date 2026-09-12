import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

import { sha256 } from "../lib/contract.mjs";
import { inventoryMarkdown, reconstructMarkdown } from "../lib/markdown.mjs";

const toolRoot = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const repository = path.dirname(path.dirname(toolRoot));
const expected = new Map([
  ["docs/hardware/README.md", { owners: 207, fallback: 26 }],
  ["docs/hardware/nspanel-pro.md", { owners: 166, fallback: 55 }],
  ["docs/hardware/tpa10.md", { owners: 104, fallback: 43 }],
  ["docs/hardware/wf1589t.md", { owners: 69, fallback: 16 }],
]);

test("hardware guides freeze the reviewed 406-translation and 140-fallback boundary", () => {
  const policy = JSON.parse(fs.readFileSync(
    path.join(repository, "docs/i18n/consequential-segments.json"),
    "utf8",
  ));
  let owners = 0;
  let fallback = 0;

  for (const [document, counts] of expected) {
    const source = fs.readFileSync(path.join(repository, document), "utf8");
    const inventory = inventoryMarkdown(document, source);
    const entry = policy.documents.find((candidate) => candidate.document === document);
    assert.ok(entry, document);
    assert.equal(entry.sourceSha256, sha256(Buffer.from(source, "utf8")), document);
    assert.equal(inventory.segments.length, counts.owners, document);
    assert.equal(entry.segmentCount, counts.owners, document);
    assert.equal(entry.consequentialSegments.length, counts.fallback, document);
    assert.deepEqual(entry.consequentialSegments, [...entry.consequentialSegments].sort(), document);
    assert.ok(entry.consequentialSegments.every((id) =>
      inventory.segments.some((segment) => segment.segmentId === id)), document);
    owners += counts.owners;
    fallback += counts.fallback;
  }

  assert.equal(owners, 546);
  assert.equal(fallback, 140);
  assert.equal(owners - fallback, 406);
});

test("hardware guide identity migration preserves every byte", () => {
  for (const [document] of expected) {
    const source = fs.readFileSync(path.join(repository, document), "utf8");
    const inventory = inventoryMarkdown(document, source);
    const records = inventory.segments.map((segment) => ({
      document,
      segmentId: segment.segmentId,
      sourceSha256: segment.sourceSha256,
      translation: segment.maskedSource,
    }));
    assert.equal(reconstructMarkdown(inventory, records).body, source, document);
  }
});
