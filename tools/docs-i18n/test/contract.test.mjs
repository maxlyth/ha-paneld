import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";
import test from "node:test";

import {
  ALL_DOC_LOCALES,
  AUTHORITY_NOTICE_TEMPLATES,
  ENGLISH_FALLBACK_STATE,
  MAX_SEGMENTS_PER_PACKET,
  MAX_SOURCE_CHARACTERS_PER_PACKET,
  MAX_TARGET_CHARACTERS_PER_SEGMENT,
  PROMOTABLE_STATE,
  PRODUCTION_DOCUMENTS,
  REVIEW_DISPOSITION_DOCUMENTS,
  REVIEW_DISPOSITIONS_PATH,
  LANGUAGE_NAMES,
  PICKER_ORDER,
  applyLocaleReceipt,
  buildLocaleReceipt,
  buildSourceManifest,
  buildTranslationPlan,
  canonicalJson,
  readCanonicalJson,
  sha256,
  sourceManifestSha256,
  validateLocaleReceipt,
  validateLanguagePickerPolicy,
  validateRepository,
  validateSourceManifest,
  validateTranslationPlan,
  validateReviewDispositions,
} from "../lib/contract.mjs";
import {
  SUPPORTED_LOCALES,
  confinedOutputPath,
  localizedOutputPath,
  normalizeSourcePath,
  readTreeMarkdownLinkTarget,
} from "../lib/paths.mjs";
import { inventoryMarkdown } from "../lib/markdown.mjs";

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

function consequentialFixture(policyMutation) {
  const current = fixture();
  const provisioning = "# Provisioning\n\nReset erases panel data.\n\nContinue normally.\n";
  const renderer = "# Built-in renderer\n\nA failed login stops retries.\n\nContinue normally.\n";
  const performance = "# Performance\n\nChanging the filter can hide required entities.\n\nContinue normally.\n";
  const security = "# Security mode\n\nChanging a protected setting requires physical approval.\n\nContinue normally.\n";
  const hardware = new Map([
    ["docs/hardware/README.md", "# Hardware\n\nDisconnect power before opening the panel.\n\nContinue normally.\n"],
    ["docs/hardware/nspanel-pro.md", "# NSPanel Pro\n\nDo not flash an unverified image.\n\nContinue normally.\n"],
    ["docs/hardware/tpa10.md", "# TPA10\n\nKeep a recovery path available.\n\nContinue normally.\n"],
    ["docs/hardware/wf1589t.md", "# WF1589T\n\nVerify the system bar before hiding it.\n\nContinue normally.\n"],
  ]);
  write(current.repository, "docs/provisioning.md", provisioning);
  write(current.repository, "docs/built-in-renderer.md", renderer);
  write(current.repository, "docs/performance.md", performance);
  write(current.repository, "docs/security-mode.md", security);
  for (const [document, source] of hardware) write(current.repository, document, source);
  const provisioningInventory = inventoryMarkdown("docs/provisioning.md", provisioning);
  const rendererInventory = inventoryMarkdown("docs/built-in-renderer.md", renderer);
  const performanceInventory = inventoryMarkdown("docs/performance.md", performance);
  const securityInventory = inventoryMarkdown("docs/security-mode.md", security);
  const consequential = provisioningInventory.segments.find((segment) => segment.maskedSource.includes("Reset erases"));
  const rendererConsequential = rendererInventory.segments.find(
    (segment) => segment.maskedSource.includes("failed login"),
  );
  const performanceConsequential = performanceInventory.segments.find(
    (segment) => segment.maskedSource.includes("hide required entities"),
  );
  const securityConsequential = securityInventory.segments.find(
    (segment) => segment.maskedSource.includes("requires physical approval"),
  );
  const policy = {
    schema: 2,
    documents: [
      {
        document: "docs/provisioning.md",
        sourceSha256: sha256(Buffer.from(provisioning, "utf8")),
        segmentCount: provisioningInventory.segments.length,
        consequentialSegments: [consequential.segmentId],
      },
      {
        document: "docs/built-in-renderer.md",
        sourceSha256: sha256(Buffer.from(renderer, "utf8")),
        segmentCount: rendererInventory.segments.length,
        consequentialSegments: [rendererConsequential.segmentId],
      },
      {
        document: "docs/performance.md",
        sourceSha256: sha256(Buffer.from(performance, "utf8")),
        segmentCount: performanceInventory.segments.length,
        consequentialSegments: [performanceConsequential.segmentId],
      },
      {
        document: "docs/security-mode.md",
        sourceSha256: sha256(Buffer.from(security, "utf8")),
        segmentCount: securityInventory.segments.length,
        consequentialSegments: [securityConsequential.segmentId],
      },
      ...[...hardware].map(([document, source]) => {
        const inventory = inventoryMarkdown(document, source);
        return {
          document,
          sourceSha256: sha256(Buffer.from(source, "utf8")),
          segmentCount: inventory.segments.length,
          consequentialSegments: [inventory.segments[1].segmentId],
        };
      }),
    ],
  };
  policyMutation?.(policy);
  write(current.repository, "docs/i18n/consequential-segments.json", canonicalJson(policy));
  command(current.repository, ["git", "add", "."]);
  command(current.repository, ["git", "commit", "-qm", "add consequential policy"]);
  const sourceRevision = command(current.repository, ["git", "rev-parse", "HEAD"]);
  const manifest = buildSourceManifest({
    repository: current.repository,
    sourceRevision,
    documents: PRODUCTION_DOCUMENTS,
  });
  return { repository: current.repository, sourceRevision, manifest, consequential, rendererConsequential, performanceConsequential, securityConsequential };
}

function localeResults(manifest, locale, repository) {
  const manifestHash = sourceManifestSha256(manifest);
  const plan = buildTranslationPlan(manifest, { repository });
  const planPackets = new Map(plan.packets.map((packet) => [packet.id, packet]));
  const requiredStates = new Map(manifest.documents.flatMap((document) =>
    document.segments.map((segment) => [segment.id, segment.requiredState])));
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
        state: requiredStates.get(segment.segmentId),
      };
    }),
  }));
}

function translatedLocaleResults(manifest, locale, repository) {
  const results = localeResults(manifest, locale, repository);
  for (const record of results.flatMap((result) => result.records)) {
    if (record.state === PROMOTABLE_STATE) record.translation += ` translated-${locale}`;
  }
  return results;
}

function writeReviewDispositions(repository, { reviewFallbacks = [], unchangedTargets = [] } = {}) {
  write(repository, REVIEW_DISPOSITIONS_PATH, canonicalJson({ schema: 1, reviewFallbacks, unchangedTargets }));
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

function expectedPickerRow(locale) {
  return PICKER_ORDER.map((itemLocale) => {
    if (itemLocale === locale) return `**${LANGUAGE_NAMES[itemLocale]}**`;
    const destination = locale === "en"
      ? `docs/${itemLocale}/README.md`
      : itemLocale === "en" ? "../../README.md" : `../${itemLocale}/README.md`;
    return `[${LANGUAGE_NAMES[itemLocale]}](${destination})`;
  }).join(" · ");
}

test("documentation picker policy exactly and uniquely covers the supported locale authority", () => {
  assert.deepEqual(ALL_DOC_LOCALES, ["en", ...SUPPORTED_LOCALES]);
  assert.deepEqual(Object.keys(LANGUAGE_NAMES).sort(), [...ALL_DOC_LOCALES].sort());
  assert.deepEqual([...PICKER_ORDER].sort(), [...ALL_DOC_LOCALES].sort());
  assert.equal(validateLanguagePickerPolicy(), true);

  const names = { ...LANGUAGE_NAMES };
  const order = [...PICKER_ORDER];
  const notices = { ...AUTHORITY_NOTICE_TEMPLATES };
  const mutations = [
    ["missing name", { languageNames: Object.fromEntries(Object.entries(names).slice(0, -1)) }],
    ["extra name", { languageNames: { ...names, nl: "Nederlands" } }],
    ["blank name", { languageNames: { ...names, de: "" } }],
    ["duplicate name", { languageNames: { ...names, es: names.de } }],
    ["missing picker locale", { pickerOrder: order.slice(0, -1) }],
    ["extra picker locale", { pickerOrder: [...order, "nl"] }],
    ["duplicate picker locale", { pickerOrder: [...order.slice(0, -1), order[1]] }],
    ["duplicate supported locale", { supportedLocales: [...SUPPORTED_LOCALES, SUPPORTED_LOCALES[0]] }],
    ["missing notice", { authorityNoticeTemplates: Object.fromEntries(Object.entries(notices).slice(0, -1)) }],
    ["extra notice", { authorityNoticeTemplates: { ...notices, nl: "Nederlands {SOURCE_LINK}" } }],
    ["replaced notice key", { authorityNoticeTemplates: {
      ...Object.fromEntries(Object.entries(notices).slice(1)),
      nl: "Nederlands {SOURCE_LINK}",
    } }],
    ["blank notice", { authorityNoticeTemplates: { ...notices, de: "  " } }],
    ["duplicate notice", { authorityNoticeTemplates: { ...notices, es: notices.de } }],
    ["missing source link", { authorityNoticeTemplates: { ...notices, de: notices.de.replace("{SOURCE_LINK}", "") } }],
    ["duplicate source link", { authorityNoticeTemplates: { ...notices, de: `${notices.de}{SOURCE_LINK}` } }],
  ];
  for (const [name, mutation] of mutations) {
    const baseline = mutation.supportedLocales ? SUPPORTED_LOCALES
      : mutation.languageNames ? names
        : mutation.pickerOrder ? order : notices;
    assert.notDeepEqual(
      mutation.supportedLocales ?? mutation.languageNames ?? mutation.pickerOrder ?? mutation.authorityNoticeTemplates,
      baseline,
      name,
    );
    assert.throws(() => validateLanguagePickerPolicy(mutation), undefined, name);
  }

  assert.throws(
    () => validateLanguagePickerPolicy({ authorityNoticeTemplates: { ...notices, de: "  " } }),
    /nonblank strings/,
  );
  assert.throws(
    () => validateLanguagePickerPolicy({ authorityNoticeTemplates: { ...notices, es: notices.de } }),
    /must be unique/,
  );
  assert.throws(
    () => validateLanguagePickerPolicy({
      authorityNoticeTemplates: { ...notices, de: notices.de.replace("{SOURCE_LINK}", "") },
    }),
    /exactly one \{SOURCE_LINK\}/,
  );
  assert.throws(
    () => validateLanguagePickerPolicy({ authorityNoticeTemplates: { ...notices, de: `${notices.de}{SOURCE_LINK}` } }),
    /exactly one \{SOURCE_LINK\}/,
  );

  const tierB = Object.freeze({
    nl: "Nederlands",
    pl: "Polski",
    cs: "Čeština",
    "pt-BR": "Português (BR)",
    uk: "Українська",
  });
  const nextSupported = [...SUPPORTED_LOCALES, ...Object.keys(tierB)];
  const nextNames = { ...names, ...tierB };
  const nextOrder = [...order, ...Object.keys(tierB)];
  const nextNotices = {
    ...notices,
    ...Object.fromEntries(Object.keys(tierB).map((locale) => [
      locale,
      `Future authority notice for ${locale}: {SOURCE_LINK}`,
    ])),
  };
  assert.equal(validateLanguagePickerPolicy({
    supportedLocales: nextSupported,
    languageNames: nextNames,
    pickerOrder: nextOrder,
    authorityNoticeTemplates: nextNotices,
  }), true);
  assert.deepEqual(SUPPORTED_LOCALES, ["de", "es", "fr", "it", "zh-Hans"]);
  assert.deepEqual(LANGUAGE_NAMES, names);
  assert.deepEqual(PICKER_ORDER, order);
  assert.deepEqual(AUTHORITY_NOTICE_TEMPLATES, notices);
  assert.throws(() => validateLanguagePickerPolicy({ supportedLocales: nextSupported }), /exactly cover/);
});

test("documentation locale authority rejects noncanonical and malformed region or script tags", () => {
  const cases = [
    ["noncanonical region", "pt-br"],
    ["noncanonical script", "zh-hans"],
    ["malformed region", "pt-BRZ"],
    ["malformed script", "zh-Han"],
  ];
  for (const [name, locale] of cases) {
    const supportedLocales = [...SUPPORTED_LOCALES, locale];
    const languageNames = { ...LANGUAGE_NAMES, [locale]: `Fixture ${name}` };
    const pickerOrder = [...PICKER_ORDER, locale];
    const authorityNoticeTemplates = {
      ...AUTHORITY_NOTICE_TEMPLATES,
      [locale]: `Fixture notice for ${name}: {SOURCE_LINK}`,
    };
    assert.notDeepEqual(supportedLocales, SUPPORTED_LOCALES, name);
    assert.throws(
      () => validateLanguagePickerPolicy({
        supportedLocales,
        languageNames,
        pickerOrder,
        authorityNoticeTemplates,
      }),
      /canonical.*BCP 47/,
      name,
    );
  }
});

test("source README picker is the exact canonical ordered locale map", () => {
  const canonical = expectedPickerRow("en");
  const items = canonical.split(" · ");
  const mutations = [
    ["omitted locale", items.slice(0, -1).join(" · ")],
    ["duplicate locale", [...items, items[1]].join(" · ")],
    ["extra locale", [...items, "[Nederlands](docs/nl/README.md)"].join(" · ")],
    ["reordered locale", [items[0], items[2], items[1], ...items.slice(3)].join(" · ")],
    ["wrong destination", canonical.replace("docs/de/README.md", "docs/es/README.md")],
    ["wrong English self", canonical.replace("**English**", "[English](README.md)")],
    ["wrong bold self", canonical.replace("[Deutsch](docs/de/README.md)", "**Deutsch**")],
  ];
  for (const [name, row] of mutations) {
    assert.notEqual(row, canonical, name);
    const current = fixture();
    const readme = fs.readFileSync(path.join(current.repository, "README.md"), "utf8");
    write(current.repository, "README.md", readme.replace(canonical, row));
    command(current.repository, ["git", "add", "README.md"]);
    command(current.repository, ["git", "commit", "-qm", name]);
    const sourceRevision = command(current.repository, ["git", "rev-parse", "HEAD"]);
    assert.throws(
      () => buildSourceManifest({ repository: current.repository, sourceRevision, documents: ["README.md"] }),
      /exact documentation locale policy/,
      name,
    );
  }
});

test("every localized README picker has one self-bold entry and deterministic destinations", () => {
  const current = fixture();
  for (const locale of SUPPORTED_LOCALES) {
    const built = buildLocaleReceipt(
      current.manifest,
      locale,
      localeResults(current.manifest, locale, current.repository),
      { repository: current.repository },
    );
    const readme = built.outputs.find((output) => output.path === `docs/${locale}/README.md`).content;
    const text = readme.toString("utf8");
    const expected = `<!-- docs-i18n-language-picker:start -->\n${expectedPickerRow(locale)}\n<!-- docs-i18n-language-picker:end -->\n`;
    assert.equal(text.split(expected).length - 1, 1, locale);
    assert.equal((expectedPickerRow(locale).match(/\*\*/g) ?? []).length, 2, locale);
    for (const itemLocale of PICKER_ORDER.filter((candidate) => candidate !== locale)) {
      const destination = itemLocale === "en" ? "../../README.md" : `../${itemLocale}/README.md`;
      assert.ok(expected.includes(`[${LANGUAGE_NAMES[itemLocale]}](${destination})`), `${locale}/${itemLocale}`);
    }
  }
});

test("localized README picker drift is rejected even when its receipt hashes are forged", () => {
  const mutations = [
    ["omitted locale", (items) => items.slice(0, -1).join(" · ")],
    ["duplicate locale", (items) => [...items, items[1]].join(" · ")],
    ["extra locale", (items) => [...items, "[Nederlands](../nl/README.md)"].join(" · ")],
    ["reordered locale", (items) => [items[0], items[2], items[1], ...items.slice(3)].join(" · ")],
    ["missing self-bold", (items, locale) => items.join(" · ").replace(
      `**${LANGUAGE_NAMES[locale]}**`,
      `[${LANGUAGE_NAMES[locale]}](../${locale}/README.md)`,
    )],
  ];
  for (const [locale, [name, mutate]] of SUPPORTED_LOCALES.map((locale, index) => [locale, mutations[index]])) {
    const current = fixture();
    const receipt = applyLocaleReceipt({
      repository: current.repository,
      manifest: current.manifest,
      locale,
      results: localeResults(current.manifest, locale, current.repository),
    });
    const document = receipt.documents.find((candidate) => candidate.sourcePath === "README.md");
    const target = path.join(current.repository, document.targetPath);
    const canonicalRow = expectedPickerRow(locale);
    const changedRow = mutate(canonicalRow.split(" · "), locale);
    assert.notEqual(changedRow, canonicalRow, name);
    const changedContent = fs.readFileSync(target, "utf8").replace(canonicalRow, changedRow);
    fs.writeFileSync(target, changedContent);
    document.targetSha256 = sha256(changedContent);
    document.languagePickerSha256 = sha256(
      `<!-- docs-i18n-language-picker:start -->\n${changedRow}\n<!-- docs-i18n-language-picker:end -->\n`,
    );
    assert.throws(
      () => validateLocaleReceipt(current.manifest, locale, receipt, { repository: current.repository }),
      /canonical language picker/,
      `${locale}: ${name}`,
    );
  }
});

test("canonical source manifest binds fixed schema, parser, locales, outputs, budgets, and ownership", () => {
  const { repository, manifest } = fixture();
  assert.equal(manifest.schema, 5);
  assert.deepEqual(PRODUCTION_DOCUMENTS, [
    "README.md",
    "docs/provisioning.md",
    "docs/built-in-renderer.md",
    "docs/performance.md",
    "docs/security-mode.md",
    "docs/hardware/README.md",
    "docs/hardware/nspanel-pro.md",
    "docs/hardware/tpa10.md",
    "docs/hardware/wf1589t.md",
  ]);
  assert.deepEqual(validateSourceManifest(manifest, { repository }), manifest);
  assert.deepEqual(manifest.locales, SUPPORTED_LOCALES);
  assert.deepEqual(Object.keys(AUTHORITY_NOTICE_TEMPLATES).sort(), [...SUPPORTED_LOCALES].sort());
  assert.equal(manifest.notice.version, 2);
  assert.equal(manifest.notice.sha256, sha256(canonicalJson(AUTHORITY_NOTICE_TEMPLATES)));
  assert.deepEqual(manifest.limits, {
    maxSegmentsPerPacket: MAX_SEGMENTS_PER_PACKET,
    maxSourceCharactersPerPacket: MAX_SOURCE_CHARACTERS_PER_PACKET,
    maxTargetCharactersPerSegment: MAX_TARGET_CHARACTERS_PER_SEGMENT,
  });
  assert.equal(manifest.documents[0].outputs.de, "docs/de/README.md");
  assert.equal(manifest.documents[1].outputs.de, "docs/de/guide.md");
  assert.deepEqual(manifest.reviewPolicy, { schema: 2, path: null, sha256: null });
  assert.ok(manifest.documents.flatMap((document) => document.segments).every(
    (segment) => segment.requiredState === PROMOTABLE_STATE,
  ));
  for (const locale of SUPPORTED_LOCALES) {
    const owners = manifest.packets
      .filter((packet) => packet.locale === locale)
      .flatMap((packet) => packet.owners.map((owner) => `${owner.document}\0${owner.segmentId}`));
    const expected = manifest.documents.flatMap((document) =>
      document.segments.map((segment) => `${document.sourcePath}\0${segment.id}`));
    assert.deepEqual(owners, expected);
    assert.equal(new Set(owners).size, owners.length);
    for (const packet of manifest.packets.filter((candidate) => candidate.locale === locale)) {
      assert.equal(new Set(packet.owners.map((owner) => owner.document)).size, 1);
    }
  }
});

test("appending a document preserves every prior document commitment and packet object", () => {
  const current = fixture();
  write(
    current.repository,
    "README.md",
    `${fs.readFileSync(path.join(current.repository, "README.md"), "utf8")}Read the [third guide](docs/third.md).\n`,
  );
  write(current.repository, "docs/third.md", "# Third\n\nAn appended document.\n");
  command(current.repository, ["git", "add", "README.md", "docs/third.md"]);
  command(current.repository, ["git", "commit", "-qm", "append third document"]);
  const sourceRevision = command(current.repository, ["git", "rev-parse", "HEAD"]);
  const prefix = buildSourceManifest({
    repository: current.repository,
    sourceRevision,
    documents: ["README.md", "docs/guide.md"],
  });
  const extended = buildSourceManifest({
    repository: current.repository,
    sourceRevision,
    documents: ["README.md", "docs/guide.md", "docs/third.md"],
  });
  const prefixPlan = buildTranslationPlan(prefix, { repository: current.repository });
  const extendedPlan = buildTranslationPlan(extended, { repository: current.repository });
  assert.deepEqual(extended.documents.slice(0, prefix.documents.length), prefix.documents);
  const prefixBuilt = buildLocaleReceipt(
    prefix,
    "de",
    localeResults(prefix, "de", current.repository),
    { repository: current.repository },
  );
  const extendedBuilt = buildLocaleReceipt(
    extended,
    "de",
    localeResults(extended, "de", current.repository),
    { repository: current.repository },
  );
  const prefixReceipt = prefixBuilt.receipt;
  const extendedReceipt = extendedBuilt.receipt;
  const [prefixReadme, prefixGuide] = prefixReceipt.documents;
  const [extendedReadme, extendedGuide] = extendedReceipt.documents;
  assert.deepEqual(extendedReadme.segments, prefixReadme.segments);
  assert.notEqual(extendedReadme.targetSha256, prefixReadme.targetSha256);
  assert.notEqual(extendedReadme.structureSha256, prefixReadme.structureSha256);
  assert.deepEqual(
    { ...extendedReadme, targetSha256: null, structureSha256: null },
    { ...prefixReadme, targetSha256: null, structureSha256: null },
  );
  assert.deepEqual(extendedGuide, prefixGuide);
  const prefixReadmeBody = prefixBuilt.outputs.find((output) => output.path === "docs/de/README.md").content;
  const extendedReadmeBody = extendedBuilt.outputs.find((output) => output.path === "docs/de/README.md").content;
  assert.equal(extendedReadmeBody.replace("(third.md)", "(../third.md)"), prefixReadmeBody);
  for (const locale of SUPPORTED_LOCALES) {
    const priorPackets = prefix.packets.filter((packet) => packet.locale === locale);
    const extendedPackets = extended.packets.filter((packet) => packet.locale === locale);
    assert.deepEqual(extendedPackets.slice(0, priorPackets.length), priorPackets);
    const priorInputs = prefixPlan.packets.filter((packet) => packet.locale === locale);
    const extendedInputs = extendedPlan.packets.filter((packet) => packet.locale === locale);
    assert.deepEqual(
      extendedInputs.slice(0, priorInputs.length).map((packet) => packet.records),
      priorInputs.map((packet) => packet.records),
    );
    const appendedPackets = extendedPackets.slice(priorPackets.length);
    assert.ok(appendedPackets.length > 0);
    assert.deepEqual(
      appendedPackets.flatMap((packet) => packet.owners),
      extended.documents[2].segments.map((segment) => ({
        document: extended.documents[2].sourcePath,
        segmentId: segment.id,
      })),
    );
    assert.ok(appendedPackets.every(
      (packet) => packet.owners.every((owner) => owner.document === "docs/third.md"),
    ));
  }
});

test("consequential policy binds every selected production inventory and grandfathers README", () => {
  const current = consequentialFixture();
  const policyBytes = fs.readFileSync(path.join(
    current.repository,
    "docs/i18n/consequential-segments.json",
  ));
  assert.deepEqual(current.manifest.reviewPolicy, {
    schema: 2,
    path: "docs/i18n/consequential-segments.json",
    sha256: sha256(policyBytes),
  });
  const readme = current.manifest.documents.find((document) => document.sourcePath === "README.md");
  assert.ok(readme.segments.every((segment) => segment.requiredState === PROMOTABLE_STATE));
  const provisioning = current.manifest.documents.find(
    (document) => document.sourcePath === "docs/provisioning.md",
  );
  assert.equal(
    provisioning.segments.find(
      (segment) => segment.id === current.consequential.segmentId,
    ).requiredState,
    ENGLISH_FALLBACK_STATE,
  );
  assert.ok(provisioning.segments.filter(
    (segment) => segment.id !== current.consequential.segmentId,
  ).every((segment) => segment.requiredState === PROMOTABLE_STATE));
  const performance = current.manifest.documents.find(
    (document) => document.sourcePath === "docs/performance.md",
  );
  assert.equal(
    performance.segments.find(
      (segment) => segment.id === current.performanceConsequential.segmentId,
    ).requiredState,
    ENGLISH_FALLBACK_STATE,
  );
  assert.ok(performance.segments.filter(
    (segment) => segment.id !== current.performanceConsequential.segmentId,
  ).every((segment) => segment.requiredState === PROMOTABLE_STATE));
  const renderer = current.manifest.documents.find(
    (document) => document.sourcePath === "docs/built-in-renderer.md",
  );
  assert.equal(
    renderer.segments.find(
      (segment) => segment.id === current.rendererConsequential.segmentId,
    ).requiredState,
    ENGLISH_FALLBACK_STATE,
  );
  const security = current.manifest.documents.find(
    (document) => document.sourcePath === "docs/security-mode.md",
  );
  assert.equal(
    security.segments.find(
      (segment) => segment.id === current.securityConsequential.segmentId,
    ).requiredState,
    ENGLISH_FALLBACK_STATE,
  );

  const forged = clone(current.manifest);
  forged.documents[0].segments[0].requiredState = ENGLISH_FALLBACK_STATE;
  assert.throws(
    () => validateSourceManifest(forged, { repository: current.repository }),
    /canonical rebuilt manifest/,
  );
});

test("consequential policy fails closed for missing, incomplete, and unknown inventories", () => {
  const missing = fixture();
  write(missing.repository, "docs/provisioning.md", "# Provisioning\n\nReset erases data.\n");
  command(missing.repository, ["git", "add", "docs/provisioning.md"]);
  command(missing.repository, ["git", "commit", "-qm", "add provisioning without policy"]);
  assert.throws(
    () => buildSourceManifest({
      repository: missing.repository,
      sourceRevision: command(missing.repository, ["git", "rev-parse", "HEAD"]),
      documents: ["README.md", "docs/provisioning.md"],
    }),
    /consequential policy is absent/,
  );
  assert.throws(
    () => consequentialFixture((policy) => { policy.documents[0].segmentCount -= 1; }),
    /consequential policy source binding mismatch/,
  );
  assert.throws(
    () => consequentialFixture((policy) => { policy.documents[0].consequentialSegments[0] += "-unknown"; }),
    /unknown segment in docs\/provisioning\.md/,
  );
  assert.throws(
    () => consequentialFixture((policy) => { policy.documents.reverse(); }),
    /document selection mismatch/,
  );
  assert.throws(
    () => consequentialFixture((policy) => { policy.documents.pop(); }),
    /document selection mismatch/,
  );
  assert.throws(
    () => consequentialFixture((policy) => { policy.documents[1].consequentialSegments.push("duplicate"); }),
    /unknown segment in docs\/built-in-renderer\.md/,
  );
  assert.throws(
    () => consequentialFixture((policy) => { policy.documents[1].consequentialSegments = []; }),
    /source binding mismatch for docs\/built-in-renderer\.md/,
  );
  assert.throws(
    () => consequentialFixture((policy) => {
      const segment = policy.documents[1].consequentialSegments[0];
      policy.documents[1].consequentialSegments = [`${segment}z`, segment];
    }),
    /source binding mismatch for docs\/built-in-renderer\.md/,
  );
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

test("english-fallback accepts only the exact masked English source", () => {
  const current = consequentialFixture();
  const results = localeResults(current.manifest, "de", current.repository);
  const record = results
    .flatMap((result) => result.records)
    .find((candidate) => candidate.segmentId === current.consequential.segmentId);
  const fallback = buildLocaleReceipt(current.manifest, "de", results, {
    repository: current.repository,
  });
  const receiptSegment = fallback.receipt.documents
    .flatMap((document) => document.segments)
    .find((segment) => segment.segmentId === current.consequential.segmentId);
  assert.equal(receiptSegment.state, ENGLISH_FALLBACK_STATE);
  record.translation += " changed";
  assert.throws(
    () => buildLocaleReceipt(current.manifest, "de", results, { repository: current.repository }),
    /english-fallback must exactly preserve the masked English source/,
  );
  record.translation = current.consequential.maskedSource;
  record.state = PROMOTABLE_STATE;
  assert.throws(
    () => buildLocaleReceipt(current.manifest, "de", results, { repository: current.repository }),
    /result state must be english-fallback/,
  );
});

test("review dispositions are exact, source-bound, unique, sorted, disjoint, and hardware-only", () => {
  const current = consequentialFixture();
  assert.deepEqual(REVIEW_DISPOSITION_DOCUMENTS, [
    "docs/hardware/README.md",
    "docs/hardware/nspanel-pro.md",
    "docs/hardware/tpa10.md",
    "docs/hardware/wf1589t.md",
  ]);
  const document = current.manifest.documents.find(
    (candidate) => candidate.sourcePath === "docs/hardware/README.md",
  );
  const segment = document.segments.find((candidate) => candidate.requiredState === PROMOTABLE_STATE);
  const entry = {
    locale: "de",
    document: document.sourcePath,
    segmentId: segment.id,
    sourceSha256: segment.sourceSha256,
  };
  const dispositions = { schema: 1, reviewFallbacks: [], unchangedTargets: [entry] };
  assert.deepEqual(
    validateReviewDispositions(current.manifest, dispositions, { unchangedTargets: [entry] }),
    dispositions,
  );

  assert.throws(
    () => validateReviewDispositions(
      current.manifest,
      { schema: 1, reviewFallbacks: [], unchangedTargets: [] },
      { unchangedTargets: [entry] },
    ),
    /missing unchanged target disposition/,
  );
  assert.throws(
    () => validateReviewDispositions(current.manifest, dispositions, { unchangedTargets: [] }),
    /listed unchanged target disposition was not used/,
  );
  assert.throws(
    () => validateReviewDispositions(
      current.manifest,
      { schema: 1, reviewFallbacks: [], unchangedTargets: [entry, entry] },
    ),
    /duplicate unchangedTargets entry/,
  );
  assert.throws(
    () => validateReviewDispositions(current.manifest, {
      schema: 1,
      reviewFallbacks: [],
      unchangedTargets: [{ ...entry, sourceSha256: "0".repeat(64) }],
    }),
    /binding mismatch/,
  );
  assert.throws(
    () => validateReviewDispositions(current.manifest, {
      schema: 1,
      reviewFallbacks: [],
      unchangedTargets: [{ ...entry, document: "README.md" }],
    }),
    /outside the four hardware documents/,
  );
  assert.deepEqual(
    validateReviewDispositions(
      current.manifest,
      { schema: 1, reviewFallbacks: [], unchangedTargets: [] },
      { unchangedTargets: [{ ...entry, document: "README.md" }] },
    ),
    { schema: 1, reviewFallbacks: [], unchangedTargets: [] },
  );
  assert.throws(
    () => validateReviewDispositions(current.manifest, {
      schema: 1,
      reviewFallbacks: [],
      unchangedTargets: [{ ...entry, locale: "xx" }],
    }),
    /unsupported locale/,
  );
  assert.throws(
    () => validateReviewDispositions(current.manifest, {
      schema: 1,
      reviewFallbacks: [],
      unchangedTargets: [
        { ...entry, locale: "fr" },
        entry,
      ],
    }),
    /must be sorted/,
  );
  assert.throws(
    () => validateReviewDispositions(current.manifest, {
      schema: 1,
      reviewFallbacks: [entry],
      unchangedTargets: [entry],
    }),
    /cannot be both fallback and unchanged/,
  );
});

test("locale apply accepts only exact listed review fallbacks with unchanged masked English", () => {
  const current = consequentialFixture();
  const results = localeResults(current.manifest, "de", current.repository);
  const record = results.flatMap((result) => result.records).find(
    (candidate) =>
      candidate.document === "docs/hardware/README.md" &&
      candidate.state === PROMOTABLE_STATE,
  );
  const entry = {
    locale: "de",
    document: record.document,
    segmentId: record.segmentId,
    sourceSha256: record.sourceSha256,
  };
  record.state = ENGLISH_FALLBACK_STATE;
  writeReviewDispositions(current.repository, { reviewFallbacks: [entry] });
  const receipt = applyLocaleReceipt({
    repository: current.repository,
    manifest: current.manifest,
    locale: "de",
    results,
  });
  assert.equal(
    receipt.documents.flatMap((document) => document.segments)
      .find((segment) => segment.segmentId === record.segmentId).state,
    ENGLISH_FALLBACK_STATE,
  );

  writeReviewDispositions(current.repository);
  assert.throws(
    () => buildLocaleReceipt(current.manifest, "de", results, { repository: current.repository }),
    /result state must be machine-cross-checked/,
  );
  writeReviewDispositions(current.repository, { reviewFallbacks: [entry] });
  record.state = PROMOTABLE_STATE;
  assert.throws(
    () => buildLocaleReceipt(current.manifest, "de", results, { repository: current.repository }),
    /listed de review fallback disposition was not used/,
  );
  record.state = ENGLISH_FALLBACK_STATE;
  record.translation += " changed";
  assert.throws(
    () => buildLocaleReceipt(current.manifest, "de", results, { repository: current.repository }),
    /english-fallback must exactly preserve the masked English source/,
  );
  record.translation = record.translation.replace(" changed", "");
  writeReviewDispositions(current.repository, { reviewFallbacks: [{ ...entry, locale: "fr" }] });
  assert.throws(
    () => buildLocaleReceipt(current.manifest, "de", results, { repository: current.repository }),
    /result state must be machine-cross-checked/,
  );
  writeReviewDispositions(current.repository, {
    reviewFallbacks: [{ ...entry, sourceSha256: "0".repeat(64) }],
  });
  assert.throws(
    () => buildLocaleReceipt(current.manifest, "de", results, { repository: current.repository }),
    /reviewFallbacks binding mismatch/,
  );
});

test("receipt validation rejects altered text committed as english-fallback", () => {
  const current = consequentialFixture();
  const results = localeResults(current.manifest, "de", current.repository);
  const receipt = applyLocaleReceipt({
    repository: current.repository,
    manifest: current.manifest,
    locale: "de",
    results,
  });
  const receiptDocument = receipt.documents.find(
    (document) => document.sourcePath === "docs/provisioning.md",
  );
  const receiptSegment = receipt.documents
    .flatMap((document) => document.segments)
    .find((segment) => segment.segmentId === current.consequential.segmentId);
  const target = path.join(current.repository, receiptDocument.targetPath);
  const changedContent = fs.readFileSync(target, "utf8").replace(
    current.consequential.maskedSource,
    "Zurücksetzen löscht Paneldaten.",
  );
  fs.writeFileSync(target, changedContent);
  receiptDocument.targetSha256 = sha256(changedContent);
  receiptSegment.targetSha256 = sha256("Zurücksetzen löscht Paneldaten.");
  rebindReceiptResults(receipt, current.manifest);
  assert.throws(
    () => validateLocaleReceipt(current.manifest, "de", receipt, { repository: current.repository }),
    /english-fallback target differs from masked English source/,
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

test("localized image destinations prefer a locale blob added after planning and otherwise fall back", () => {
  const current = fixture();
  const readmePath = path.join(current.repository, "README.md");
  fs.appendFileSync(readmePath, "\n![Panel](docs/img/panel.png?raw=1#preview)\n");
  write(current.repository, "docs/img/panel.png", "english image\n");
  command(current.repository, ["git", "add", "README.md", "docs/img/panel.png"]);
  command(current.repository, ["git", "commit", "-qm", "add image reference"]);
  const sourceRevision = command(current.repository, ["git", "rev-parse", "HEAD"]);
  const manifest = buildSourceManifest({
    repository: current.repository,
    sourceRevision,
    documents: ["README.md", "docs/guide.md"],
  });

  write(current.repository, "docs/img/de/panel.png", "localized image\n");
  command(current.repository, ["git", "add", "docs/img/de/panel.png"]);
  command(current.repository, ["git", "commit", "-qm", "add localized image after planning"]);
  assert.equal(command(current.repository, [
    "git", "ls-tree", sourceRevision, "--", "docs/img/de/panel.png",
  ]), "");

  const german = applyLocaleReceipt({
    repository: current.repository,
    manifest,
    locale: "de",
    results: localeResults(manifest, "de", current.repository),
  });
  const germanReadme = fs.readFileSync(path.join(current.repository, "docs/de/README.md"), "utf8");
  assert.match(germanReadme, /!\[Panel\]\(\.\.\/img\/de\/panel\.png\?raw=1#preview\)/);
  assert.doesNotThrow(() => validateLocaleReceipt(manifest, "de", german, {
    repository: current.repository,
  }));

  const french = applyLocaleReceipt({
    repository: current.repository,
    manifest,
    locale: "fr",
    results: localeResults(manifest, "fr", current.repository),
  });
  const frenchReadme = fs.readFileSync(path.join(current.repository, "docs/fr/README.md"), "utf8");
  assert.match(frenchReadme, /!\[Panel\]\(\.\.\/img\/panel\.png\?raw=1#preview\)/);
  assert.doesNotMatch(frenchReadme, /img\/fr\/panel\.png/);
  assert.doesNotThrow(() => validateLocaleReceipt(manifest, "fr", french, {
    repository: current.repository,
  }));

  fs.mkdirSync(path.join(current.repository, "docs/img/es"), { recursive: true });
  fs.symlinkSync("../panel.png", path.join(current.repository, "docs/img/es/panel.png"));
  command(current.repository, ["git", "add", "docs/img/es/panel.png"]);
  command(current.repository, ["git", "commit", "-qm", "add invalid localized image symlink"]);
  assert.throws(
    () => buildLocaleReceipt(manifest, "es", localeResults(manifest, "es", current.repository), {
      repository: current.repository,
    }),
    /localized image target is not a regular blob/,
  );

  fs.unlinkSync(path.join(current.repository, "docs/img/panel.png"));
  command(current.repository, ["git", "add", "docs/img/panel.png"]);
  command(current.repository, ["git", "commit", "-qm", "delete authoritative image"]);
  assert.throws(
    () => buildLocaleReceipt(manifest, "de", localeResults(manifest, "de", current.repository), {
      repository: current.repository,
    }),
    /link target is absent/,
  );
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

test("every locale receives its exact localized authority notice", () => {
  for (const locale of SUPPORTED_LOCALES) {
    const current = fixture();
    const receipt = applyLocaleReceipt({
      repository: current.repository,
      manifest: current.manifest,
      locale,
      results: localeResults(current.manifest, locale, current.repository),
    });
    for (const document of receipt.documents) {
      const sourceLink = path.posix.relative(
        path.posix.dirname(document.targetPath),
        document.sourcePath,
      ) || path.posix.basename(document.sourcePath);
      const expected = AUTHORITY_NOTICE_TEMPLATES[locale].replace("{SOURCE_LINK}", sourceLink);
      assert.ok(fs.readFileSync(path.join(current.repository, document.targetPath), "utf8").startsWith(expected));
      assert.equal(document.noticeSha256, sha256(expected));
    }
  }
});

test("a notice from every other supported locale is rejected", () => {
  for (const locale of SUPPORTED_LOCALES) {
    for (const substitutedLocale of SUPPORTED_LOCALES.filter((candidate) => candidate !== locale)) {
      const current = fixture();
      const receipt = applyLocaleReceipt({
        repository: current.repository,
        manifest: current.manifest,
        locale,
        results: localeResults(current.manifest, locale, current.repository),
      });
      const document = receipt.documents[0];
      const target = path.join(current.repository, document.targetPath);
      const sourceLink = path.posix.relative(
        path.posix.dirname(document.targetPath),
        document.sourcePath,
      ) || path.posix.basename(document.sourcePath);
      const expected = AUTHORITY_NOTICE_TEMPLATES[locale].replace("{SOURCE_LINK}", sourceLink);
      const substituted = AUTHORITY_NOTICE_TEMPLATES[substitutedLocale].replace("{SOURCE_LINK}", sourceLink);
      const content = fs.readFileSync(target, "utf8");
      assert.ok(content.startsWith(expected));
      const forgedContent = substituted + content.slice(expected.length);
      fs.writeFileSync(target, forgedContent);
      const forgedReceipt = clone(receipt);
      forgedReceipt.documents[0].targetSha256 = sha256(forgedContent);
      forgedReceipt.documents[0].noticeSha256 = sha256(substituted);
      assert.throws(
        () => validateLocaleReceipt(current.manifest, locale, forgedReceipt, {
          repository: current.repository,
        }),
        /authority notice mismatch/,
        `${locale} accepted ${substitutedLocale}`,
      );
    }
  }
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
  assert.ok(fs.readFileSync(output, "utf8").startsWith(AUTHORITY_NOTICE_TEMPLATES.de.replace(
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

test("root Markdown link fragments are checked at sourceRevision and selected HEAD without source admission", () => {
  const current = fixture();
  const readmePath = path.join(current.repository, "README.md");
  fs.appendFileSync(readmePath, "\nRead the [security policy](SECURITY.md#reporting-a-vulnerability).\n");
  write(current.repository, "SECURITY.md", "# Reporting a vulnerability\n");
  command(current.repository, ["git", "add", "README.md", "SECURITY.md"]);
  command(current.repository, ["git", "commit", "-qm", "link root policy"]);
  const sourceRevision = command(current.repository, ["git", "rev-parse", "HEAD"]);
  const manifest = buildSourceManifest({
    repository: current.repository,
    sourceRevision,
    documents: ["README.md"],
  });

  assert.throws(() => normalizeSourcePath("SECURITY.md"), /outside the admitted Markdown roots/);
  const built = buildLocaleReceipt(manifest, "de", localeResults(manifest, "de", current.repository), {
    repository: current.repository,
  });
  assert.match(
    built.outputs[0].content,
    /\[security policy\]\(\.\.\/\.\.\/SECURITY\.md#reporting-a-vulnerability\)/,
  );

  write(current.repository, "SECURITY.md", "# Security\n");
  command(current.repository, ["git", "add", "SECURITY.md"]);
  command(current.repository, ["git", "commit", "-qm", "rename policy heading"]);
  assert.throws(
    () => buildLocaleReceipt(manifest, "de", localeResults(manifest, "de", current.repository), {
      repository: current.repository,
    }),
    /fragment is absent from selected HEAD/,
  );
});

test("root Markdown fragment checks cannot be satisfied only by selected HEAD", () => {
  const current = fixture();
  const readmePath = path.join(current.repository, "README.md");
  fs.appendFileSync(readmePath, "\nRead the [security policy](SECURITY.md#reporting-a-vulnerability).\n");
  write(current.repository, "SECURITY.md", "# Security\n");
  command(current.repository, ["git", "add", "README.md", "SECURITY.md"]);
  command(current.repository, ["git", "commit", "-qm", "link missing root policy heading"]);
  const sourceRevision = command(current.repository, ["git", "rev-parse", "HEAD"]);
  const manifest = buildSourceManifest({
    repository: current.repository,
    sourceRevision,
    documents: ["README.md"],
  });
  write(current.repository, "SECURITY.md", "# Reporting a vulnerability\n");
  command(current.repository, ["git", "add", "SECURITY.md"]);
  command(current.repository, ["git", "commit", "-qm", "add policy heading at head"]);

  assert.throws(
    () => buildLocaleReceipt(manifest, "de", localeResults(manifest, "de", current.repository), {
      repository: current.repository,
    }),
    /fragment is absent from sourceRevision/,
  );
});

test("Markdown link-target reads reject traversal, non-Markdown files, and symlinks", () => {
  const current = fixture();
  fs.symlinkSync("README.md", path.join(current.repository, "SECURITY.md"));
  command(current.repository, ["git", "add", "SECURITY.md"]);
  command(current.repository, ["git", "commit", "-qm", "symlink policy"]);
  const revision = command(current.repository, ["git", "rev-parse", "HEAD"]);

  assert.throws(
    () => readTreeMarkdownLinkTarget(current.repository, revision, "../SECURITY.md"),
    /unsafe Markdown link target/,
  );
  assert.throws(
    () => readTreeMarkdownLinkTarget(current.repository, revision, "SECURITY\n.md"),
    /unsafe Markdown link target/,
  );
  assert.throws(
    () => readTreeMarkdownLinkTarget(current.repository, revision, "asset.txt"),
    /unsafe Markdown link target/,
  );
  assert.throws(
    () => readTreeMarkdownLinkTarget(current.repository, revision, "SECURITY.md"),
    /must be a regular Git blob/,
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
  const current = consequentialFixture();
  const nonProductionManifest = buildSourceManifest({
    repository: current.repository,
    sourceRevision: current.sourceRevision,
    documents: ["README.md", "docs/guide.md"],
  });
  write(current.repository, "docs/i18n/manifest.json", canonicalJson(nonProductionManifest));
  assert.throws(
    () => validateRepository({ repository: current.repository }),
    /must select exactly README\.md, docs\/provisioning\.md, docs\/built-in-renderer\.md, docs\/performance\.md/,
  );
  const manifest = current.manifest;
  write(current.repository, "docs/i18n/manifest.json", canonicalJson(manifest));
  for (const locale of SUPPORTED_LOCALES) {
    applyLocaleReceipt({
      repository: current.repository,
      manifest,
      locale,
      results: translatedLocaleResults(manifest, locale, current.repository),
    });
  }
  assert.throws(
    () => validateRepository({ repository: current.repository }),
    /review-dispositions\.json/,
  );
  writeReviewDispositions(current.repository);
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

test("repository validation requires exact hardware fallback and unchanged-target dispositions", () => {
  const current = consequentialFixture();
  const manifest = current.manifest;
  write(current.repository, "docs/i18n/manifest.json", canonicalJson(manifest));
  const hardwareDocument = manifest.documents.find(
    (document) => document.sourcePath === "docs/hardware/README.md",
  );
  const unchangedSegment = hardwareDocument.segments.find(
    (segment) => segment.requiredState === PROMOTABLE_STATE,
  );
  const fallbackDocument = manifest.documents.find(
    (document) => document.sourcePath === "docs/hardware/nspanel-pro.md",
  );
  const fallbackSegment = fallbackDocument.segments.find(
    (segment) => segment.requiredState === PROMOTABLE_STATE,
  );
  const exception = {
    locale: "de",
    document: hardwareDocument.sourcePath,
    segmentId: unchangedSegment.id,
    sourceSha256: unchangedSegment.sourceSha256,
  };
  const fallbackEntry = {
    locale: "de",
    document: fallbackDocument.sourcePath,
    segmentId: fallbackSegment.id,
    sourceSha256: fallbackSegment.sourceSha256,
  };
  writeReviewDispositions(current.repository, {
    reviewFallbacks: [fallbackEntry],
    unchangedTargets: [exception],
  });
  for (const locale of SUPPORTED_LOCALES) {
    const results = translatedLocaleResults(manifest, locale, current.repository);
    if (locale === "de") {
      const record = results.flatMap((result) => result.records).find(
        (candidate) => candidate.segmentId === exception.segmentId,
      );
      record.translation = record.translation.replace(" translated-de", "");
      const fallbackRecord = results.flatMap((result) => result.records).find(
        (candidate) => candidate.segmentId === fallbackEntry.segmentId,
      );
      fallbackRecord.translation = fallbackRecord.translation.replace(" translated-de", "");
      fallbackRecord.state = ENGLISH_FALLBACK_STATE;
      const grandfatheredRecord = results.flatMap((result) => result.records).find(
        (candidate) => candidate.document === "README.md" && candidate.state === PROMOTABLE_STATE,
      );
      grandfatheredRecord.translation = grandfatheredRecord.translation.replace(" translated-de", "");
    }
    applyLocaleReceipt({ repository: current.repository, manifest, locale, results });
  }
  const manifestBytes = fs.readFileSync(path.join(current.repository, "docs/i18n/manifest.json"));
  const receiptSegments = Object.fromEntries(SUPPORTED_LOCALES.map((locale) => {
    const receiptFile = path.join(current.repository, `docs/i18n/locales/${locale}.json`);
    return [locale, readCanonicalJson(receiptFile).documents.map((document) => document.segments)];
  }));
  assert.deepEqual(validateRepository({ repository: current.repository }), manifest);
  assert.ok(fs.readFileSync(path.join(current.repository, "docs/i18n/manifest.json")).equals(manifestBytes));
  for (const locale of SUPPORTED_LOCALES) {
    const receiptFile = path.join(current.repository, `docs/i18n/locales/${locale}.json`);
    assert.deepEqual(readCanonicalJson(receiptFile).documents.map((document) => document.segments), receiptSegments[locale]);
  }

  writeReviewDispositions(current.repository, { reviewFallbacks: [fallbackEntry] });
  assert.throws(
    () => validateRepository({ repository: current.repository }),
    /missing unchanged target disposition/,
  );
  writeReviewDispositions(current.repository, { reviewFallbacks: [fallbackEntry], unchangedTargets: [{
    ...exception,
    document: "docs/hardware/tpa10.md",
    segmentId: manifest.documents
      .find((document) => document.sourcePath === "docs/hardware/tpa10.md")
      .segments.find((segment) => segment.requiredState === PROMOTABLE_STATE).id,
    sourceSha256: manifest.documents
      .find((document) => document.sourcePath === "docs/hardware/tpa10.md")
      .segments.find((segment) => segment.requiredState === PROMOTABLE_STATE).sourceSha256,
  }] });
  assert.throws(
    () => validateRepository({ repository: current.repository }),
    /missing unchanged target disposition|listed unchanged target disposition was not used/,
  );
});

test("repository validation rejects every non-production document selection", () => {
  const current = consequentialFixture();
  for (const documents of [
    ["README.md"],
    ["README.md", "docs/guide.md"],
  ]) {
    const manifest = buildSourceManifest({
      repository: current.repository,
      sourceRevision: current.sourceRevision,
      documents,
    });
    write(current.repository, "docs/i18n/manifest.json", canonicalJson(manifest));
    assert.throws(
      () => validateRepository({ repository: current.repository }),
      /must select exactly README\.md, docs\/provisioning\.md, docs\/built-in-renderer\.md, docs\/performance\.md/,
      documents.join(","),
    );
  }
});
