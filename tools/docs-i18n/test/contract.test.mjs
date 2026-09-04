import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";
import test from "node:test";

import {
  AUTHORITY_NOTICE_TEMPLATE,
  MAX_SEGMENTS_PER_PACKET,
  MAX_SOURCE_CHARACTERS_PER_PACKET,
  MAX_TARGET_CHARACTERS_PER_SEGMENT,
  PROMOTABLE_STATE,
  applyLocaleReceipt,
  buildLocaleReceipt,
  buildSourceManifest,
  buildTranslationPlan,
  canonicalJson,
  readCanonicalJson,
  sha256,
  sourceManifestSha256,
  validateLocaleReceipt,
  validateRepository,
  validateSourceManifest,
  validateTranslationPlan,
} from "../lib/contract.mjs";
import {
  SUPPORTED_LOCALES,
  confinedOutputPath,
  localizedOutputPath,
  normalizeSourcePath,
} from "../lib/paths.mjs";

const PRIVATE_PROVIDER_NAMES = ["Open" + "AI", "Anth" + "ropic", "Deep" + "L"];
const PRIVATE_PROVIDER_URL = ["https", "://", "provider.example.invalid"].join("");

function command(repository, args) {
  return execFileSync(args[0], args.slice(1), { cwd: repository, encoding: "utf8" }).trim();
}

function write(repository, relative, value) {
  const target = path.join(repository, ...relative.split("/"));
  fs.mkdirSync(path.dirname(target), { recursive: true });
  fs.writeFileSync(target, value);
}

function fixture() {
  const repository = fs.mkdtempSync(path.join(os.tmpdir(), "docs-i18n-contract-"));
  command(repository, ["git", "init", "-q"]);
  command(repository, ["git", "config", "user.email", "test@example.invalid"]);
  command(repository, ["git", "config", "user.name", "Test"]);
  write(repository, "README.md", `# Install

<!-- docs-i18n-language-picker:start -->
**English** · [Deutsch](docs/de/README.md) · [Français](docs/fr/README.md) · [Italiano](docs/it/README.md) · [Español](docs/es/README.md) · [简体中文](docs/zh-Hans/README.md)
<!-- docs-i18n-language-picker:end -->

<a id="stable-install"></a>

Read the [guide](docs/guide.md#install), [this section](#install), and [the stable anchor](#stable-install). Providers ${PRIVATE_PROVIDER_NAMES.join(", ")} are described at [the provider site](${PRIVATE_PROVIDER_URL}).
`);
  write(repository, "docs/guide.md", `# Install

Read the [guide](../README.md). Keep \`Home Assistant\` available.
`);
  command(repository, ["git", "add", "."]);
  command(repository, ["git", "commit", "-qm", "fixture"]);
  const sourceRevision = command(repository, ["git", "rev-parse", "HEAD"]);
  const manifest = buildSourceManifest({
    repository,
    sourceRevision,
    documents: ["README.md", "docs/guide.md"],
  });
  return { repository, sourceRevision, manifest };
}

function localeResults(manifest, locale, repository) {
  const manifestHash = sourceManifestSha256(manifest);
  const plan = buildTranslationPlan(manifest, { repository });
  const planPackets = new Map(plan.packets.map((packet) => [packet.id, packet]));
  return manifest.packets.filter((packet) => packet.locale === locale).map((packet) => ({
    schema: 1,
    locale,
    sourceManifestSha256: manifestHash,
    sourceRevision: manifest.sourceRevision,
    packetId: packet.id,
    packetSha256: sha256(canonicalJson(packet)),
    records: planPackets.get(packet.id).records.map((segment) => {
      return {
        document: segment.document,
        segmentId: segment.segmentId,
        sourceSha256: segment.sourceSha256,
        translation: segment.maskedSource,
        state: PROMOTABLE_STATE,
      };
    }),
  }));
}

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

function rebindReceiptResults(receipt, manifest) {
  const segments = new Map(receipt.documents.flatMap((document) =>
    document.segments.map((segment) => [`${document.sourcePath}\0${segment.segmentId}`, segment])));
  const manifestHash = sourceManifestSha256(manifest);
  receipt.results = manifest.packets.filter((packet) => packet.locale === receipt.locale).map((packet) => {
    const packetSha256 = sha256(canonicalJson(packet));
    const commitment = {
      schema: 1,
      locale: receipt.locale,
      sourceManifestSha256: manifestHash,
      sourceRevision: manifest.sourceRevision,
      packetId: packet.id,
      packetSha256,
      records: packet.owners.map((owner) => {
        const segment = segments.get(`${owner.document}\0${owner.segmentId}`);
        return {
          document: owner.document,
          segmentId: segment.segmentId,
          sourceSha256: segment.sourceSha256,
          targetSha256: segment.targetSha256,
          state: segment.state,
        };
      }),
    };
    return { packetId: packet.id, packetSha256, resultSha256: sha256(canonicalJson(commitment)) };
  });
}

test("canonical source manifest binds fixed schema, parser, locales, outputs, budgets, and ownership", () => {
  const { repository, manifest } = fixture();
  assert.deepEqual(validateSourceManifest(manifest, { repository }), manifest);
  assert.deepEqual(manifest.locales, SUPPORTED_LOCALES);
  assert.deepEqual(manifest.limits, {
    maxSegmentsPerPacket: MAX_SEGMENTS_PER_PACKET,
    maxSourceCharactersPerPacket: MAX_SOURCE_CHARACTERS_PER_PACKET,
    maxTargetCharactersPerSegment: MAX_TARGET_CHARACTERS_PER_SEGMENT,
  });
  assert.equal(manifest.documents[0].outputs.de, "docs/de/README.md");
  assert.equal(manifest.documents[1].outputs.de, "docs/de/guide.md");
  for (const locale of SUPPORTED_LOCALES) {
    const owners = manifest.packets
      .filter((packet) => packet.locale === locale)
      .flatMap((packet) => packet.owners.map((owner) => `${owner.document}\0${owner.segmentId}`));
    const expected = manifest.documents.flatMap((document) =>
      document.segments.map((segment) => `${document.sourcePath}\0${segment.id}`));
    assert.deepEqual(owners, expected);
    assert.equal(new Set(owners).size, owners.length);
  }
});

test("canonical JSON rejects noncanonical bytes and duplicate-key spelling", () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "docs-i18n-json-"));
  const canonical = path.join(directory, "canonical.json");
  fs.writeFileSync(canonical, canonicalJson({ b: 2, a: 1 }));
  assert.deepEqual(readCanonicalJson(canonical), { a: 1, b: 2 });
  const duplicate = path.join(directory, "duplicate.json");
  fs.writeFileSync(duplicate, '{"a":1,"a":1}\n');
  assert.throws(() => readCanonicalJson(duplicate), /canonical form|duplicate/);
});

test("public manifest contains commitments only and private plan exact-expands them", () => {
  const current = fixture();
  const manifestText = canonicalJson(current.manifest);
  for (const forbidden of [...PRIVATE_PROVIDER_NAMES, PRIVATE_PROVIDER_URL, "maskedSource\""]) {
    assert.equal(manifestText.includes(forbidden), false, forbidden);
  }
  assert.equal(manifestText.includes('"bindings"'), false);
  const plan = buildTranslationPlan(current.manifest, { repository: current.repository });
  assert.ok(canonicalJson(plan).includes(PRIVATE_PROVIDER_NAMES[0]));
  assert.ok(canonicalJson(plan).includes(PRIVATE_PROVIDER_URL));
  assert.deepEqual(
    validateTranslationPlan(current.manifest, plan, { repository: current.repository }),
    plan,
  );

  const changedManifest = clone(current.manifest);
  changedManifest.documents[0].segments[0].maskedSourceSha256 = "0".repeat(64);
  assert.throws(
    () => validateTranslationPlan(changedManifest, plan, { repository: current.repository }),
    /canonical rebuilt manifest|commitment/i,
  );

  const changedPlan = clone(plan);
  changedPlan.packets[0].records[0].maskedSource += "tamper";
  assert.throws(
    () => validateTranslationPlan(current.manifest, changedPlan, { repository: current.repository }),
    /not exactly equal/,
  );
  const changedBinding = clone(plan);
  const recordWithBinding = changedBinding.packets[0].records.find((record) => record.bindings.length > 0);
  assert.ok(recordWithBinding);
  recordWithBinding.bindings[0].value += "tamper";
  assert.throws(
    () => validateTranslationPlan(current.manifest, changedBinding, { repository: current.repository }),
    /not exactly equal/,
  );
});

for (const [name, mutate] of [
  ["extra root field", (manifest) => { manifest.extra = true; }],
  ["parser version", (manifest) => { manifest.parser.unified = "0.0.0"; }],
  ["packet limit", (manifest) => { manifest.limits.maxSegmentsPerPacket += 1; }],
  ["packet owner", (manifest) => { manifest.packets[0].owners[0].segmentId += "-forged"; }],
  ["packet character count", (manifest) => { manifest.packets[0].sourceCharacters += 1; }],
  ["output path", (manifest) => { manifest.documents[0].outputs.de = "docs/fr/README.md"; }],
  ["binding commitment", (manifest) => { manifest.documents[0].segments[0].bindingsSha256 = "0".repeat(64); }],
]) {
  test(`canonical manifest rebuild rejects ${name}`, () => {
    const { repository, manifest } = fixture();
    const changed = clone(manifest);
    mutate(changed);
    assert.throws(() => validateSourceManifest(changed, { repository }));
  });
}

test("source revision must exist, be ancestral, byte-current, and regular", () => {
  const current = fixture();
  assert.throws(
    () => buildSourceManifest({
      repository: current.repository,
      sourceRevision: "a".repeat(40),
      documents: ["README.md"],
    }),
    /revision|object|Command failed/i,
  );
  write(current.repository, "later.md", "later\n");
  command(current.repository, ["git", "add", "later.md"]);
  command(current.repository, ["git", "commit", "-qm", "later"]);
  const descendant = command(current.repository, ["git", "rev-parse", "HEAD"]);
  assert.throws(
    () => buildSourceManifest({
      repository: current.repository,
      sourceRevision: descendant,
      documents: ["README.md"],
      head: current.sourceRevision,
    }),
    /not an ancestor/,
  );
  const admittedReadme = fs.readFileSync(path.join(current.repository, "README.md"));
  fs.writeFileSync(path.join(current.repository, "README.md"), admittedReadme.toString("utf8").replace("# Install", "# Changed"));
  command(current.repository, ["git", "add", "README.md"]);
  command(current.repository, ["git", "commit", "-qm", "change selected source"]);
  fs.writeFileSync(path.join(current.repository, "README.md"), admittedReadme);
  assert.throws(
    () => validateSourceManifest(current.manifest, { repository: current.repository }),
    /differs between sourceRevision and selected HEAD/,
  );
  const dirty = fixture();
  fs.appendFileSync(path.join(dirty.repository, "README.md"), "drift\n");
  assert.throws(
    () => validateSourceManifest(dirty.manifest, { repository: dirty.repository }),
    /working source differs/,
  );
});

test("source and output paths reject traversal, localized sources, and symlink ancestors", () => {
  assert.throws(() => normalizeSourcePath("../README.md"), /unsafe/);
  assert.throws(() => normalizeSourcePath("docs/de/README.md"), /localized output/);
  const current = fixture();
  write(current.repository, "docs/README.md", "# Collision\n");
  command(current.repository, ["git", "add", "docs/README.md"]);
  command(current.repository, ["git", "commit", "-qm", "colliding source"]);
  assert.throws(
    () => buildSourceManifest({
      repository: current.repository,
      sourceRevision: command(current.repository, ["git", "rev-parse", "HEAD"]),
      documents: ["README.md", "docs/README.md"],
    }),
    /colliding localized output paths/,
  );
  fs.mkdirSync(path.join(current.repository, "outside"));
  fs.symlinkSync(path.join(current.repository, "outside"), path.join(current.repository, "docs/de"));
  assert.throws(
    () => confinedOutputPath(current.repository, "de", "README.md"),
    /symlinked path ancestor/,
  );
  fs.symlinkSync("../README.md", path.join(current.repository, "docs/source-link.md"));
  command(current.repository, ["git", "add", "docs/source-link.md"]);
  command(current.repository, ["git", "commit", "-qm", "symlink source"]);
  assert.throws(
    () => buildSourceManifest({
      repository: current.repository,
      sourceRevision: command(current.repository, ["git", "rev-parse", "HEAD"]),
      documents: ["README.md", "docs/source-link.md"],
    }),
    /regular Git blob/,
  );
});

test("segment IDs are cross-document unique and stable after an unrelated earlier insertion", () => {
  const current = fixture();
  const before = new Map(current.manifest.documents.flatMap((document) =>
    document.segments.map((segment) => [`${document.sourcePath}\0${segment.sourceSha256}`, segment.id])));
  const readme = fs.readFileSync(path.join(current.repository, "README.md"), "utf8");
  write(current.repository, "README.md", `Unrelated preface.\n\n${readme}`);
  command(current.repository, ["git", "add", "README.md"]);
  command(current.repository, ["git", "commit", "-qm", "preface"]);
  const sourceRevision = command(current.repository, ["git", "rev-parse", "HEAD"]);
  const after = buildSourceManifest({
    repository: current.repository,
    sourceRevision,
    documents: ["README.md", "docs/guide.md"],
  });
  const afterSegments = after.documents.flatMap((document) => document.segments);
  for (const document of after.documents) {
    for (const segment of document.segments) {
      const key = `${document.sourcePath}\0${segment.sourceSha256}`;
      if (before.has(key)) assert.equal(segment.id, before.get(key));
    }
  }
  const ids = afterSegments.map((segment) => segment.id);
  assert.equal(new Set(ids).size, ids.length);
});

for (const [name, mutate] of [
  ["wrong locale", (results) => { results[0].locale = "zh-Hans"; }],
  ["wrong manifest hash", (results) => { results[0].sourceManifestSha256 = "0".repeat(64); }],
  ["wrong packet hash", (results) => { results[0].packetSha256 = "0".repeat(64); }],
  ["missing record", (results) => { results[0].records.pop(); }],
  ["wrong owner", (results) => { results[0].records[0].segmentId += "-forged"; }],
  ["unreviewed state", (results) => { results[0].records[0].state = "machine-draft"; }],
  ["oversized translation", (results) => {
    results[0].records[0].translation = "x".repeat(MAX_TARGET_CHARACTERS_PER_SEGMENT + 1);
  }],
]) {
  test(`locale reconciliation rejects ${name}`, () => {
    const current = fixture();
    const results = localeResults(current.manifest, "de", current.repository);
    mutate(results);
    assert.throws(() => buildLocaleReceipt(current.manifest, "de", results, {
      repository: current.repository,
    }));
  });
}

test("cross-locale packet results cannot be applied under another locale", () => {
  const current = fixture();
  const chinese = localeResults(current.manifest, "zh-Hans", current.repository);
  assert.throws(
    () => buildLocaleReceipt(current.manifest, "de", chinese, { repository: current.repository }),
    /binding mismatch/,
  );
});

test("localized links relocate selected documents and translated heading fragments", () => {
  const current = fixture();
  const results = localeResults(current.manifest, "de", current.repository);
  const heading = current.manifest.documents[0].segments.find((segment) => segment.ownerType === "heading");
  const record = results.flatMap((result) => result.records).find((item) => item.segmentId === heading.id);
  record.translation = record.translation.replace("Install", "Installieren");
  const built = buildLocaleReceipt(current.manifest, "de", results, { repository: current.repository });
  const readme = built.outputs.find((output) => output.path === "docs/de/README.md").content;
  assert.match(readme, /\[guide\]\(guide\.md#install\)/);
  assert.match(readme, /\[this section\]\(#installieren\)/);
  assert.match(readme, /\[the stable anchor\]\(#stable-install\)/);
  const guide = built.outputs.find((output) => output.path === "docs/de/guide.md").content;
  assert.match(guide, /\[guide\]\(README\.md\)/);
});

test("a missing source fragment is rejected before a localized receipt is produced", () => {
  const current = fixture();
  const readmePath = path.join(current.repository, "README.md");
  fs.writeFileSync(
    readmePath,
    fs.readFileSync(readmePath, "utf8").replace("#install)", "#missing-heading)"),
  );
  command(current.repository, ["git", "add", "README.md"]);
  command(current.repository, ["git", "commit", "-qm", "break fragment"]);
  const sourceRevision = command(current.repository, ["git", "rev-parse", "HEAD"]);
  const manifest = buildSourceManifest({
    repository: current.repository,
    sourceRevision,
    documents: ["README.md", "docs/guide.md"],
  });
  assert.throws(
    () => buildLocaleReceipt(manifest, "de", localeResults(manifest, "de", current.repository), {
      repository: current.repository,
    }),
    /fragment does not name a heading/,
  );
});

test("receipt apply is confined, no-clobber, banner-bound, hash-bound, and replay-safe", () => {
  const current = fixture();
  const results = localeResults(current.manifest, "de", current.repository);
  const receipt = applyLocaleReceipt({
    repository: current.repository,
    manifest: current.manifest,
    locale: "de",
    results,
  });
  assert.equal(receipt.documents[0].targetPath, localizedOutputPath("de", "README.md"));
  const output = path.join(current.repository, receipt.documents[0].targetPath);
  assert.ok(fs.readFileSync(output, "utf8").startsWith(AUTHORITY_NOTICE_TEMPLATE.replace(
    "{SOURCE_LINK}",
    "../../README.md",
  )));
  assert.doesNotThrow(() => validateLocaleReceipt(current.manifest, "de", receipt, {
    repository: current.repository,
  }));
  for (const [name, mutate] of [
    ["manifest binding", (changed) => { changed.sourceManifestSha256 = "0".repeat(64); }],
    ["result binding", (changed) => { changed.results[0].resultSha256 = "0".repeat(64); }],
    ["target path", (changed) => { changed.documents[0].targetPath = "../README.md"; }],
    ["target hash", (changed) => { changed.documents[0].targetSha256 = "0".repeat(64); }],
    ["notice hash", (changed) => { changed.documents[0].noticeSha256 = "0".repeat(64); }],
    ["picker hash", (changed) => { changed.documents[0].languagePickerSha256 = "0".repeat(64); }],
    ["segment hash", (changed) => { changed.documents[0].segments[0].targetSha256 = "0".repeat(64); }],
    ["segment state", (changed) => { changed.documents[0].segments[0].state = "machine-draft"; }],
  ]) {
    const changed = clone(receipt);
    mutate(changed);
    assert.throws(
      () => validateLocaleReceipt(current.manifest, "de", changed, {
        repository: current.repository,
      }),
      undefined,
      name,
    );
  }
  const original = fs.readFileSync(output);
  const changedContent = original.toString("utf8").replace("Read the [guide]", "Changed [guide]");
  assert.notEqual(changedContent, original.toString("utf8"));
  fs.writeFileSync(output, changedContent);
  const forged = clone(receipt);
  forged.documents[0].targetSha256 = sha256(changedContent);
  assert.throws(
    () => validateLocaleReceipt(current.manifest, "de", forged, {
      repository: current.repository,
    }),
    /target segment hash mismatch/,
  );
  fs.writeFileSync(output, original);
  const oversizedTranslation = "x".repeat(MAX_TARGET_CHARACTERS_PER_SEGMENT + 1);
  const oversizedContent = original.toString("utf8").replace(
    "Read the [guide]",
    `${oversizedTranslation} [guide]`,
  );
  assert.notEqual(oversizedContent, original.toString("utf8"));
  fs.writeFileSync(output, oversizedContent);
  const oversizedReceipt = clone(receipt);
  oversizedReceipt.documents[0].targetSha256 = sha256(oversizedContent);
  const privatePlan = buildTranslationPlan(current.manifest, { repository: current.repository });
  const paragraphRecord = privatePlan.packets.find((packet) => packet.locale === "de").records.find(
    (record) => record.document === "README.md" && record.maskedSource.includes("Read the"),
  );
  assert.ok(paragraphRecord);
  const paragraphIndex = current.manifest.documents[0].segments.findIndex(
    (segment) => segment.id === paragraphRecord.segmentId,
  );
  assert.notEqual(paragraphIndex, -1);
  const oversizedMasked = paragraphRecord.maskedSource.replace(
    "Read the",
    oversizedTranslation,
  );
  assert.ok(oversizedMasked.includes(oversizedTranslation));
  oversizedReceipt.documents[0].segments[paragraphIndex].targetSha256 = sha256(oversizedMasked);
  rebindReceiptResults(oversizedReceipt, current.manifest);
  assert.throws(
    () => validateLocaleReceipt(current.manifest, "de", oversizedReceipt, {
      repository: current.repository,
    }),
    /target segment exceeds the fixed target character limit/,
  );
  fs.writeFileSync(output, original);
  assert.throws(
    () => applyLocaleReceipt({
      repository: current.repository,
      manifest: current.manifest,
      locale: "de",
      results,
    }),
    /already exists/,
  );
  fs.appendFileSync(output, "tamper\n");
  assert.throws(
    () => validateLocaleReceipt(current.manifest, "de", receipt, {
      repository: current.repository,
    }),
    /target hash mismatch/,
  );
});

test("atomic multi-document apply rolls back every linked output after a later failure", () => {
  const current = fixture();
  fs.mkdirSync(path.join(current.repository, "docs/de"), { recursive: true });
  fs.writeFileSync(path.join(current.repository, "docs/de/guide.md"), "occupied\n");
  assert.throws(
    () => applyLocaleReceipt({
      repository: current.repository,
      manifest: current.manifest,
      locale: "de",
      results: localeResults(current.manifest, "de", current.repository),
    }),
    /already exists/,
  );
  assert.equal(fs.existsSync(path.join(current.repository, "docs/de/README.md")), false);
  assert.equal(fs.existsSync(path.join(current.repository, "docs/i18n/locales/de.json")), false);
});

test("atomic apply removes already-linked outputs when a later link operation fails", () => {
  const current = fixture();
  const realLink = fs.linkSync;
  let calls = 0;
  fs.linkSync = (...arguments_) => {
    calls += 1;
    if (calls === 2) throw new Error("injected link failure");
    return realLink(...arguments_);
  };
  try {
    assert.throws(
      () => applyLocaleReceipt({
        repository: current.repository,
        manifest: current.manifest,
        locale: "de",
        results: localeResults(current.manifest, "de", current.repository),
      }),
      /injected link failure/,
    );
  } finally {
    fs.linkSync = realLink;
  }
  assert.equal(fs.existsSync(path.join(current.repository, "docs/de/README.md")), false);
  assert.equal(fs.existsSync(path.join(current.repository, "docs/de/guide.md")), false);
  assert.equal(fs.existsSync(path.join(current.repository, "docs/i18n/locales/de.json")), false);
});

test("an unselected Markdown fragment must still exist at the selected HEAD", () => {
  const current = fixture();
  const manifest = buildSourceManifest({
    repository: current.repository,
    sourceRevision: current.sourceRevision,
    documents: ["README.md"],
  });
  write(current.repository, "docs/guide.md", "# Renamed\n");
  command(current.repository, ["git", "add", "docs/guide.md"]);
  command(current.repository, ["git", "commit", "-qm", "rename unselected heading"]);
  assert.throws(
    () => buildLocaleReceipt(manifest, "de", localeResults(manifest, "de", current.repository), {
      repository: current.repository,
    }),
    /fragment is absent from selected HEAD/,
  );
});

test("localized links require non-symlink targets at sourceRevision and existing targets at HEAD", () => {
  const repository = fs.mkdtempSync(path.join(os.tmpdir(), "docs-i18n-links-"));
  command(repository, ["git", "init", "-q"]);
  command(repository, ["git", "config", "user.email", "test@example.invalid"]);
  command(repository, ["git", "config", "user.name", "Test"]);
  write(repository, "README.md", `# Install

<!-- docs-i18n-language-picker:start -->
**English** · [Deutsch](docs/de/README.md) · [Français](docs/fr/README.md) · [Italiano](docs/it/README.md) · [Español](docs/es/README.md) · [简体中文](docs/zh-Hans/README.md)
<!-- docs-i18n-language-picker:end -->

[Asset](asset.txt)
`);
  write(repository, "asset.txt", "asset\n");
  command(repository, ["git", "add", "."]);
  command(repository, ["git", "commit", "-qm", "linked asset"]);
  const sourceRevision = command(repository, ["git", "rev-parse", "HEAD"]);
  const manifest = buildSourceManifest({ repository, sourceRevision, documents: ["README.md"] });
  fs.unlinkSync(path.join(repository, "asset.txt"));
  command(repository, ["git", "add", "asset.txt"]);
  command(repository, ["git", "commit", "-qm", "delete linked asset"]);
  assert.throws(
    () => buildLocaleReceipt(manifest, "de", localeResults(manifest, "de", repository), { repository }),
    /link target is absent/,
  );
  fs.symlinkSync("README.md", path.join(repository, "asset.txt"));
  command(repository, ["git", "add", "asset.txt"]);
  command(repository, ["git", "commit", "-qm", "symlink linked asset"]);
  const symlinkRevision = command(repository, ["git", "rev-parse", "HEAD"]);
  const symlinkManifest = buildSourceManifest({
    repository,
    sourceRevision: symlinkRevision,
    documents: ["README.md"],
  });
  assert.throws(
    () => buildLocaleReceipt(
      symlinkManifest,
      "de",
      localeResults(symlinkManifest, "de", repository),
      { repository },
    ),
    /not a regular blob or tree/,
  );
});

test("repository validation requires the exact manifest, receipt set, and output tree", () => {
  const current = fixture();
  write(current.repository, "docs/i18n/manifest.json", canonicalJson(current.manifest));
  assert.throws(
    () => validateRepository({ repository: current.repository }),
    /must select exactly README\.md/,
  );
  const manifest = buildSourceManifest({
    repository: current.repository,
    sourceRevision: current.sourceRevision,
    documents: ["README.md"],
  });
  write(current.repository, "docs/i18n/manifest.json", canonicalJson(manifest));
  for (const locale of SUPPORTED_LOCALES) {
    applyLocaleReceipt({
      repository: current.repository,
      manifest,
      locale,
      results: localeResults(manifest, locale, current.repository),
    });
  }
  assert.deepEqual(validateRepository({
    repository: current.repository,
    manifestPath: path.join(current.repository, "docs/i18n/manifest.json"),
  }), manifest);
  write(current.repository, "docs/de/unowned.txt", "not declared\n");
  assert.throws(
    () => validateRepository({ repository: current.repository }),
    /output tree differs/,
  );
});
