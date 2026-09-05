import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

import {
  PARSER_VERSIONS,
  headingAnchors,
  htmlAnchors,
  inventoryMarkdown,
  linkDestinations,
  preflightMarkdown,
  reconstructMarkdown,
  rewriteLinkDestinations,
  structuralProjection,
} from "./markdown.mjs";
import {
  EXACT_SHA256_RE,
  SUPPORTED_LOCALES,
  assertCurrentSource,
  bindSourceRevision,
  confinedOutputPath,
  confinedWorkingPath,
  git,
  localeReceiptPath,
  localizedOutputPath,
  normalizeLocale,
  normalizeRepository,
  normalizeSourcePath,
  readTreeMarkdownLinkTarget,
  readTreeSource,
} from "./paths.mjs";

export const SOURCE_MANIFEST_SCHEMA = 3;
export const LOCALE_RESULT_SCHEMA = 1;
export const LOCALE_RECEIPT_SCHEMA = 1;
export const TRANSLATION_PLAN_SCHEMA = 1;
export const MAX_SEGMENTS_PER_PACKET = 20;
export const MAX_SOURCE_CHARACTERS_PER_PACKET = 12_000;
export const MAX_TARGET_CHARACTERS_PER_SEGMENT = 48_000;
export const PROMOTABLE_STATE = "machine-cross-checked";
export const ENGLISH_FALLBACK_STATE = "english-fallback";
export const PRODUCTION_DOCUMENTS = Object.freeze([
  "README.md",
  "docs/provisioning.md",
  "docs/built-in-renderer.md",
]);
export const CONSEQUENTIAL_POLICY_SCHEMA = 2;
export const CONSEQUENTIAL_POLICY_PATH = "docs/i18n/consequential-segments.json";
export const AUTHORITY_NOTICE_VERSION = 2;
export const AUTHORITY_NOTICE_TEMPLATES = Object.freeze({
  de:
    "> [!IMPORTANT]\n" +
    "> Dieses Dokument wurde maschinell erstellt und automatisch gegengeprüft, jedoch nicht systematisch " +
    "von Personen geprüft, die diese Sprache sprechen. Die englische Dokumentation ist maßgeblich. " +
    "[Englisches Original lesen]({SOURCE_LINK}) oder " +
    "[ein Issue zur Übersetzungskorrektur öffnen](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).\n\n",
  es:
    "> [!IMPORTANT]\n" +
    "> Este documento se genera automáticamente y se somete a comprobaciones cruzadas automáticas, pero no " +
    "ha sido revisado sistemáticamente por hablantes de este idioma. La documentación en inglés es la fuente " +
    "de referencia. [Consulta la fuente en inglés]({SOURCE_LINK}) o " +
    "[abre una incidencia para corregir la traducción](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).\n\n",
  fr:
    "> [!IMPORTANT]\n" +
    "> Ce document est généré automatiquement et fait l’objet d’une vérification croisée automatique, mais il " +
    "n’a pas été systématiquement relu par des locuteurs de cette langue. La documentation en anglais fait foi. " +
    "[Consulter la source en anglais]({SOURCE_LINK}) ou " +
    "[signaler une correction de traduction](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).\n\n",
  it:
    "> [!IMPORTANT]\n" +
    "> Questo documento è generato automaticamente e verificato mediante controlli incrociati automatici, ma non " +
    "è stato rivisto sistematicamente da persone che parlano questa lingua. La documentazione in inglese fa fede. " +
    "[Leggi la fonte in inglese]({SOURCE_LINK}) oppure " +
    "[apri una segnalazione per correggere la traduzione](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).\n\n",
  "zh-Hans":
    "> [!IMPORTANT]\n" +
    "> 本文档由机器生成并经过自动交叉核验，但尚未由中文使用者进行系统审阅。英文文档为权威版本。" +
    "[阅读英文原文]({SOURCE_LINK})，或" +
    "[创建翻译更正议题](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml)。\n\n",
});
export const LANGUAGE_PICKER_VERSION = 1;
export const LANGUAGE_PICKER_START = "<!-- docs-i18n-language-picker:start -->";
export const LANGUAGE_PICKER_END = "<!-- docs-i18n-language-picker:end -->";

const LANGUAGE_NAMES = Object.freeze({
  en: "English",
  de: "Deutsch",
  fr: "Français",
  it: "Italiano",
  es: "Español",
  "zh-Hans": "简体中文",
});
const PICKER_ORDER = Object.freeze(["en", "de", "fr", "it", "es", "zh-Hans"]);

const SOURCE_ROOT_KEYS = [
  "schema",
  "sourceRevision",
  "parser",
  "notice",
  "reviewPolicy",
  "limits",
  "locales",
  "documents",
  "packets",
];
const SOURCE_DOCUMENT_KEYS = [
  "sourcePath",
  "sourceSha256",
  "structuralSha256",
  "outputs",
  "segments",
];
const SOURCE_SEGMENT_KEYS = [
  "id",
  "ownerType",
  "sectionPath",
  "occurrence",
  "sourceSha256",
  "sourceCharacters",
  "maskedSourceSha256",
  "bindingsSha256",
  "requiredState",
];
const BINDING_KEYS = ["token", "value", "sha256"];
const PLAN_ROOT_KEYS = ["schema", "sourceManifestSha256", "sourceRevision", "packets"];
const PLAN_PACKET_KEYS = [
  "id",
  "locale",
  "packetSha256",
  "sourceManifestSha256",
  "sourceRevision",
  "records",
];
const PLAN_RECORD_KEYS = [
  "document",
  "segmentId",
  "sourceSha256",
  "sourceCharacters",
  "maskedSource",
  "bindings",
];
const PACKET_KEYS = ["id", "locale", "number", "sourceCharacters", "owners"];
const OWNER_KEYS = ["document", "segmentId"];
const RESULT_KEYS = [
  "schema",
  "locale",
  "sourceManifestSha256",
  "sourceRevision",
  "packetId",
  "packetSha256",
  "records",
];
const RESULT_RECORD_KEYS = [
  "document",
  "segmentId",
  "sourceSha256",
  "translation",
  "state",
];
const RECEIPT_KEYS = [
  "schema",
  "locale",
  "sourceManifestSha256",
  "sourceRevision",
  "results",
  "documents",
];
const RECEIPT_RESULT_KEYS = ["packetId", "packetSha256", "resultSha256"];
const RECEIPT_DOCUMENT_KEYS = [
  "sourcePath",
  "sourceSha256",
  "targetPath",
  "targetSha256",
  "structureSha256",
  "noticeSha256",
  "languagePickerSha256",
  "segments",
];
const RECEIPT_SEGMENT_KEYS = [
  "segmentId",
  "sourceSha256",
  "targetSha256",
  "state",
];

function exactKeys(value, expected, owner) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error(`${owner} must be an object`);
  }
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (JSON.stringify(actual) !== JSON.stringify(wanted)) {
    throw new Error(`${owner} keys mismatch: expected ${wanted.join(",")}, got ${actual.join(",")}`);
  }
}

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])]));
  }
  return value;
}

export function canonicalJson(value) {
  return `${JSON.stringify(canonical(value), null, 2)}\n`;
}

export function sha256(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
}

export function sourceManifestSha256(manifest) {
  return sha256(canonicalJson(manifest));
}

export function readCanonicalJson(file) {
  const bytes = fs.readFileSync(file);
  const text = bytes.toString("utf8");
  if (!Buffer.from(text, "utf8").equals(bytes) || text.includes("\r")) {
    throw new Error(`${file}: JSON must be canonical UTF-8/LF`);
  }
  let parsed;
  try {
    parsed = JSON.parse(text);
  } catch (error) {
    throw new Error(`${file}: invalid JSON: ${error.message}`);
  }
  if (canonicalJson(parsed) !== text) {
    throw new Error(`${file}: JSON is not in canonical form or contains duplicate keys`);
  }
  return parsed;
}

function assertSha(value, owner) {
  if (!EXACT_SHA256_RE.test(value)) throw new Error(`${owner} must be an exact lowercase SHA-256`);
}

function assertSortedUnique(values, owner) {
  if (
    !Array.isArray(values) ||
    values.length === 0 ||
    new Set(values).size !== values.length ||
    JSON.stringify(values) !== JSON.stringify([...values].sort())
  ) {
    throw new Error(`${owner} must be non-empty, unique, and sorted`);
  }
}

function codePointLength(value) {
  return [...value].length;
}

function noticeFor(locale, sourcePath, targetPath) {
  normalizeLocale(locale);
  const sourceLink = path.posix.relative(path.posix.dirname(targetPath), sourcePath) || path.posix.basename(sourcePath);
  return AUTHORITY_NOTICE_TEMPLATES[locale].replace("{SOURCE_LINK}", sourceLink);
}

function sourceLanguagePicker(source) {
  const start = source.indexOf(LANGUAGE_PICKER_START);
  const endMarker = source.indexOf(LANGUAGE_PICKER_END);
  if (
    start < 0 ||
    endMarker < start ||
    source.indexOf(LANGUAGE_PICKER_START, start + 1) >= 0 ||
    source.indexOf(LANGUAGE_PICKER_END, endMarker + 1) >= 0
  ) {
    throw new Error("README.md must contain exactly one ordered language-picker marker pair");
  }
  let end = endMarker + LANGUAGE_PICKER_END.length;
  if (source.slice(end, end + 1) === "\n") end += 1;
  const contentStart = start + LANGUAGE_PICKER_START.length + 1;
  const contentEnd = endMarker - 1;
  if (
    source.slice(start + LANGUAGE_PICKER_START.length, contentStart) !== "\n" ||
    source.slice(contentEnd, endMarker) !== "\n" ||
    contentStart >= contentEnd ||
    source.slice(contentStart, contentEnd).includes("\n")
  ) {
    throw new Error("README.md language picker must contain exactly one LF-terminated paragraph");
  }
  return {
    start,
    end,
    value: source.slice(start, end),
    owner: { start: contentStart, end: contentEnd, label: "language-picker" },
  };
}

function localizedLanguagePickerRow(locale) {
  normalizeLocale(locale);
  const items = PICKER_ORDER.map((itemLocale) => {
    if (itemLocale === locale) return `**${LANGUAGE_NAMES[itemLocale]}**`;
    const destination = itemLocale === "en" ? "../../README.md" : `../${itemLocale}/README.md`;
    return `[${LANGUAGE_NAMES[itemLocale]}](${destination})`;
  });
  return items.join(" · ");
}

function localizedLanguagePicker(locale) {
  return `${LANGUAGE_PICKER_START}\n${localizedLanguagePickerRow(locale)}\n${LANGUAGE_PICKER_END}\n`;
}

function exactPickerRange(source, picker) {
  const start = source.indexOf(picker);
  if (start < 0 || source.indexOf(picker, start + 1) >= 0) {
    throw new Error("localized README must contain exactly one canonical language picker");
  }
  return { start, end: start + picker.length };
}

function splitDestination(url) {
  const match = /^([^?#]*)(\?[^#]*)?(#.*)?$/.exec(url);
  if (!match) throw new Error(`invalid link destination: ${url}`);
  return { pathname: match[1], query: match[2] || "", fragment: match[3] || "" };
}

function mappedFragment(fragment, sourceBody, targetBody, owner) {
  if (!fragment) return "";
  let decoded;
  try { decoded = decodeURIComponent(fragment.slice(1)); } catch {
    throw new Error(`${owner}: malformed percent-encoded fragment`);
  }
  const sourceHeadings = headingAnchors(sourceBody);
  const targetHeadings = headingAnchors(targetBody);
  const index = sourceHeadings.findIndex((heading) => heading.anchor === decoded);
  if (index >= 0) {
    if (!targetHeadings[index]) throw new Error(`${owner}: translated heading topology changed`);
    return `#${targetHeadings[index].anchor}`;
  }
  if (htmlAnchors(sourceBody).some((anchor) => anchor.anchor === decoded)) {
    if (!htmlAnchors(targetBody).some((anchor) => anchor.anchor === decoded)) {
      throw new Error(`${owner}: frozen HTML anchor is absent from the translated target`);
    }
    return fragment;
  }
  throw new Error(`${owner}: source fragment does not name a heading or HTML anchor: ${fragment}`);
}

function assertLinkTarget(repository, revision, target, owner) {
  const lookup = target.endsWith("/") ? target.slice(0, -1) : target;
  let entry;
  try {
    entry = git(repository, ["ls-tree", revision, "--", lookup]).trim();
  } catch {
    entry = "";
  }
  const rows = entry ? entry.split("\n") : [];
  if (rows.length !== 1) throw new Error(`${owner}: link target is absent from ${revision}: ${target}`);
  const [mode, type] = rows[0].split(/\s+/, 2);
  if (mode === "120000" || !["blob", "tree"].includes(type)) {
    throw new Error(`${owner}: link target is not a regular blob or tree: ${target}`);
  }
}

function relocateDocumentLinks(item, allItems, manifest, locale, repository) {
  const sourcePicker = item.sourcePath === "README.md" ? sourceLanguagePicker(item.source).value : null;
  const targetPicker = item.sourcePath === "README.md" ? localizedLanguagePicker(locale) : null;
  const sourcePickerRange = sourcePicker ? exactPickerRange(item.source, sourcePicker) : null;
  const targetPickerRange = targetPicker ? exactPickerRange(item.body, targetPicker) : null;
  const outside = (destination, range) => !range || destination.start < range.start || destination.end > range.end;
  const sourceLinks = linkDestinations(item.source).filter((destination) => outside(destination, sourcePickerRange));
  const targetLinks = linkDestinations(item.body).filter((destination) => outside(destination, targetPickerRange));
  if (sourceLinks.length !== targetLinks.length) {
    throw new Error(`${item.sourcePath}: translated link topology differs from the source`);
  }
  const sourceByTargetIndex = new Map(
    targetLinks.map((target, index) => [target.linkIndex, sourceLinks[index]]),
  );
  const selected = new Map(allItems.map((candidate) => [candidate.sourcePath, candidate]));
  const headRevision = git(repository, ["rev-parse", "--verify", "HEAD^{commit}"]).trim();
  return rewriteLinkDestinations(item.body, (targetLink) => {
    const sourceLink = sourceByTargetIndex.get(targetLink.linkIndex);
    if (!sourceLink) return targetLink.url; // Canonical generated language-picker link.
    const url = sourceLink.url;
    if (/^[A-Za-z][A-Za-z0-9+.-]*:/.test(url)) {
      if (!/^(?:https?|mailto):/.test(url)) throw new Error(`disallowed link scheme: ${url}`);
      return url;
    }
    if (url.startsWith("/") || url.includes("\\") || url.includes("\0")) {
      throw new Error(`${item.sourcePath}: unsafe repository-relative link: ${url}`);
    }
    const split = splitDestination(url);
    let decodedPath;
    try { decodedPath = decodeURIComponent(split.pathname); } catch {
      throw new Error(`${item.sourcePath}: malformed percent-encoded link path: ${url}`);
    }
    if (decodedPath.startsWith("/") || decodedPath.includes("\\") || decodedPath.includes("\0")) {
      throw new Error(`${item.sourcePath}: unsafe decoded repository-relative link: ${url}`);
    }
    const sourceTarget = split.pathname
      ? path.posix.normalize(path.posix.join(path.posix.dirname(item.sourcePath), decodedPath))
      : item.sourcePath;
    if (sourceTarget === ".." || sourceTarget.startsWith("../") || path.posix.isAbsolute(sourceTarget)) {
      throw new Error(`${item.sourcePath}: link escapes the repository: ${url}`);
    }
    assertLinkTarget(repository, manifest.sourceRevision, sourceTarget, item.sourcePath);
    assertLinkTarget(repository, headRevision, sourceTarget, item.sourcePath);
    const selectedTarget = selected.get(sourceTarget);
    const desired = selectedTarget ? selectedTarget.targetPath : sourceTarget;
    let relative = path.posix.relative(path.posix.dirname(item.targetPath), desired);
    if (!split.pathname) relative = "";
    if (!relative && split.pathname) relative = path.posix.basename(desired);
    if (split.pathname.endsWith("/") && !relative.endsWith("/")) relative += "/";
    relative = relative.split("/").map((component) => encodeURIComponent(component)).join("/");
    let fragment = split.fragment;
    if (selectedTarget) {
      fragment = mappedFragment(
        split.fragment,
        selectedTarget.source,
        selectedTarget.body,
        `${item.sourcePath} -> ${url}`,
      );
    } else if (fragment && sourceTarget.endsWith(".md")) {
      let decoded;
      try { decoded = decodeURIComponent(fragment.slice(1)); } catch {
        throw new Error(`${item.sourcePath}: malformed percent-encoded fragment: ${url}`);
      }
      for (const [revisionName, revision] of [
        ["sourceRevision", manifest.sourceRevision],
        ["selected HEAD", headRevision],
      ]) {
        const targetSource = readTreeMarkdownLinkTarget(repository, revision, sourceTarget).source;
        if (
          !headingAnchors(targetSource).some((heading) => heading.anchor === decoded) &&
          !htmlAnchors(targetSource).some((anchor) => anchor.anchor === decoded)
        ) {
          throw new Error(`${item.sourcePath}: link fragment is absent from ${revisionName}: ${url}`);
        }
      }
    }
    return relative + split.query + fragment;
  });
}

function inventoryFor(sourcePath, source) {
  const excludedRanges = sourcePath === "README.md" ? [sourceLanguagePicker(source).owner] : [];
  return inventoryMarkdown(sourcePath, source, { excludedRanges });
}

function parserSegment(segment, document) {
  if (!segment || typeof segment !== "object") throw new Error(`${document}: invalid parser segment`);
  const id = segment.id ?? segment.segmentId;
  const ownerType = segment.ownerType;
  const sectionPath = segment.sectionPath ?? segment.sectionAncestry?.map(
    (entry) => `${entry.depth}:${entry.sourceSha256}`,
  );
  const occurrence = segment.occurrence;
  if (typeof id !== "string" || !id.startsWith(`${document}::`)) {
    throw new Error(`${document}: segment ID must be document-qualified`);
  }
  if (typeof ownerType !== "string" || !ownerType) throw new Error(`${id}: missing ownerType`);
  if (!Array.isArray(sectionPath) || sectionPath.some((part) => typeof part !== "string" || !part)) {
    throw new Error(`${id}: sectionPath must contain non-empty strings`);
  }
  if (!Number.isInteger(occurrence) || occurrence < 1) throw new Error(`${id}: occurrence must be positive`);
  assertSha(segment.sourceSha256, `${id}.sourceSha256`);
  if (typeof segment.maskedSource !== "string" || !segment.maskedSource.trim()) {
    throw new Error(`${id}: maskedSource must be non-empty`);
  }
  if (!Array.isArray(segment.bindings)) throw new Error(`${id}: bindings must be an array`);
  const bindings = segment.bindings.map((binding, index) => {
    const bindingKeys = Object.keys(binding ?? {}).sort();
    const manifestKeys = [...BINDING_KEYS].sort();
    const parserKeys = [...BINDING_KEYS, "start", "end"].sort();
    if (
      JSON.stringify(bindingKeys) !== JSON.stringify(manifestKeys) &&
      JSON.stringify(bindingKeys) !== JSON.stringify(parserKeys)
    ) {
      throw new Error(`${id}.bindings[${index}] keys mismatch`);
    }
    if (
      "start" in binding &&
      (!Number.isInteger(binding.start) || !Number.isInteger(binding.end) || binding.start < 0 || binding.end <= binding.start)
    ) {
      throw new Error(`${id}: invalid parser binding range`);
    }
    if (typeof binding.token !== "string" || !/^<x id="m\d{4}"\/>$/.test(binding.token)) {
      throw new Error(`${id}: invalid binding token`);
    }
    if (typeof binding.value !== "string" || sha256(binding.value) !== binding.sha256) {
      throw new Error(`${id}: binding hash mismatch`);
    }
    return { token: binding.token, value: binding.value, sha256: binding.sha256 };
  });
  if (new Set(bindings.map((binding) => binding.token)).size !== bindings.length) {
    throw new Error(`${id}: duplicate binding token`);
  }
  return {
    id,
    ownerType,
    sectionPath,
    occurrence,
    sourceSha256: segment.sourceSha256,
    maskedSource: segment.maskedSource,
    bindings,
    sourceCharacters: codePointLength(segment.maskedSource),
  };
}

function sourceSegmentCommitment(segment, requiredState = PROMOTABLE_STATE) {
  return {
    id: segment.id,
    ownerType: segment.ownerType,
    sectionPath: segment.sectionPath,
    occurrence: segment.occurrence,
    sourceSha256: segment.sourceSha256,
    sourceCharacters: segment.sourceCharacters,
    maskedSourceSha256: sha256(segment.maskedSource),
    bindingsSha256: sha256(canonicalJson(segment.bindings)),
    requiredState,
  };
}

function readTreeConsequentialPolicy(repository, revision, revisionName) {
  const entry = git(repository, ["ls-tree", revision, "--", CONSEQUENTIAL_POLICY_PATH]).trim();
  if (!entry) throw new Error(`consequential policy is absent from ${revisionName}`);
  const [mode, type] = entry.split(/\s+/, 2);
  if (mode === "120000" || type !== "blob") {
    throw new Error("consequential policy must be a regular Git blob");
  }
  const bytes = git(repository, ["show", `${revision}:${CONSEQUENTIAL_POLICY_PATH}`], { encoding: null });
  const text = bytes.toString("utf8");
  if (!Buffer.from(text, "utf8").equals(bytes) || text.includes("\r")) {
    throw new Error("consequential policy must be canonical UTF-8 with LF endings");
  }
  let policy;
  try {
    policy = JSON.parse(text);
  } catch (error) {
    throw new Error(`consequential policy is invalid JSON: ${error.message}`);
  }
  if (canonicalJson(policy) !== text) {
    throw new Error("consequential policy JSON is not canonical or contains duplicate keys");
  }
  return { bytes, policy };
}

function consequentialPolicy(repository, sourceRevision, headRevision, documents) {
  const policyDocuments = documents.filter((document) =>
    PRODUCTION_DOCUMENTS.slice(1).includes(document.sourcePath));
  if (!policyDocuments.length) {
    return { commitment: { schema: CONSEQUENTIAL_POLICY_SCHEMA, path: null, sha256: null }, ids: new Set() };
  }
  const source = readTreeConsequentialPolicy(repository, sourceRevision, "sourceRevision");
  const head = readTreeConsequentialPolicy(repository, headRevision, "selected HEAD");
  if (!head.bytes.equals(source.bytes)) {
    throw new Error("consequential policy differs between sourceRevision and selected HEAD");
  }
  const currentPath = confinedWorkingPath(repository, CONSEQUENTIAL_POLICY_PATH, {
    mustExist: true,
    allowFile: true,
  });
  if (!fs.readFileSync(currentPath).equals(source.bytes)) {
    throw new Error("working consequential policy differs from sourceRevision");
  }
  exactKeys(
    source.policy,
    ["schema", "documents"],
    "consequential policy",
  );
  if (source.policy.schema !== CONSEQUENTIAL_POLICY_SCHEMA || !Array.isArray(source.policy.documents)) {
    throw new Error("consequential policy source binding mismatch");
  }
  const selectedPaths = policyDocuments.map((document) => document.sourcePath);
  const policyPaths = source.policy.documents.map((document, index) => {
    exactKeys(
      document,
      ["document", "sourceSha256", "segmentCount", "consequentialSegments"],
      `consequential policy document ${index}`,
    );
    return document.document;
  });
  if (JSON.stringify(policyPaths) !== JSON.stringify(selectedPaths)) {
    throw new Error("consequential policy document selection mismatch");
  }
  const ids = new Set();
  for (const [index, policyDocument] of source.policy.documents.entries()) {
    const selected = policyDocuments[index];
    if (
      policyDocument.document !== selected.sourcePath ||
      policyDocument.sourceSha256 !== selected.sourceSha256 ||
      policyDocument.segmentCount !== selected.segments.length ||
      !Array.isArray(policyDocument.consequentialSegments) ||
      policyDocument.consequentialSegments.length === 0 ||
      new Set(policyDocument.consequentialSegments).size !== policyDocument.consequentialSegments.length ||
      JSON.stringify(policyDocument.consequentialSegments) !==
        JSON.stringify([...policyDocument.consequentialSegments].sort())
    ) {
      throw new Error(`consequential policy source binding mismatch for ${selected.sourcePath}`);
    }
    const segmentIds = new Set(selected.segments.map((segment) => segment.id));
    for (const segmentId of policyDocument.consequentialSegments) {
      if (!segmentIds.has(segmentId)) {
        throw new Error(`consequential policy names an unknown segment in ${selected.sourcePath}: ${segmentId}`);
      }
      if (ids.has(segmentId)) {
        throw new Error(`consequential policy repeats a segment: ${segmentId}`);
      }
      ids.add(segmentId);
    }
  }
  return {
    commitment: {
      schema: CONSEQUENTIAL_POLICY_SCHEMA,
      path: CONSEQUENTIAL_POLICY_PATH,
      sha256: sha256(source.bytes),
    },
    ids,
  };
}

function validateSourceSegmentCommitment(segment, document) {
  exactKeys(segment, SOURCE_SEGMENT_KEYS, `${document}.${segment?.id ?? "segment"}`);
  if (typeof segment.id !== "string" || !segment.id.startsWith(`${document}::`)) {
    throw new Error(`${document}: segment ID must be document-qualified`);
  }
  if (typeof segment.ownerType !== "string" || !segment.ownerType) {
    throw new Error(`${segment.id}: missing ownerType`);
  }
  if (!Array.isArray(segment.sectionPath) || segment.sectionPath.some(
    (part) => typeof part !== "string" || !part,
  )) {
    throw new Error(`${segment.id}: sectionPath must contain non-empty strings`);
  }
  if (!Number.isInteger(segment.occurrence) || segment.occurrence < 1) {
    throw new Error(`${segment.id}: occurrence must be positive`);
  }
  if (!Number.isInteger(segment.sourceCharacters) || segment.sourceCharacters < 1) {
    throw new Error(`${segment.id}: sourceCharacters must be positive`);
  }
  for (const key of ["sourceSha256", "maskedSourceSha256", "bindingsSha256"]) {
    assertSha(segment[key], `${segment.id}.${key}`);
  }
  if (![PROMOTABLE_STATE, ENGLISH_FALLBACK_STATE].includes(segment.requiredState)) {
    throw new Error(`${segment.id}: invalid required review state`);
  }
  return segment;
}

function buildPackets(documents, locales) {
  const packets = [];
  for (const locale of locales) {
    let owners = [];
    let sourceCharacters = 0;
    let number = 1;
    const flush = () => {
      if (!owners.length) return;
      packets.push({
        id: `${locale}-${String(number).padStart(4, "0")}`,
        locale,
        number,
        sourceCharacters,
        owners,
      });
      number += 1;
      owners = [];
      sourceCharacters = 0;
    };
    for (const document of documents) {
      for (const segment of document.segments) {
        if (segment.sourceCharacters > MAX_SOURCE_CHARACTERS_PER_PACKET) {
          throw new Error(`${segment.id}: segment exceeds the packet character budget`);
        }
        if (
          owners.length > 0 &&
          (owners.length >= MAX_SEGMENTS_PER_PACKET ||
            sourceCharacters + segment.sourceCharacters > MAX_SOURCE_CHARACTERS_PER_PACKET)
        ) {
          flush();
        }
        owners.push({ document: document.sourcePath, segmentId: segment.id });
        sourceCharacters += segment.sourceCharacters;
      }
      flush();
    }
  }
  return packets;
}

export function buildSourceManifest({ repository, sourceRevision, documents, head = "HEAD" }) {
  const bound = bindSourceRevision(repository, sourceRevision, head);
  if (!Array.isArray(documents)) throw new Error("documents must be an array");
  const sourcePaths = documents.map(normalizeSourcePath);
  if (sourcePaths.length === 0 || new Set(sourcePaths).size !== sourcePaths.length) {
    throw new Error("documents must be non-empty and unique");
  }
  if (sourcePaths[0] !== "README.md") {
    throw new Error("README.md must remain the first selected documentation source");
  }
  const locales = [...SUPPORTED_LOCALES];
  let builtDocuments = sourcePaths.map((sourcePath) => {
    const tree = readTreeSource(bound.repository, sourceRevision, sourcePath);
    const inventory = inventoryFor(sourcePath, tree.source);
    preflightMarkdown(inventory);
    const segments = inventory.segments.map((segment) =>
      sourceSegmentCommitment(parserSegment(segment, sourcePath)));
    if (segments.length === 0 || new Set(segments.map((segment) => segment.id)).size !== segments.length) {
      throw new Error(`${sourcePath}: segment inventory must be non-empty and unique`);
    }
    return {
      sourcePath,
      sourceSha256: sha256(tree.bytes),
      structuralSha256: sha256(canonicalJson(inventory.structuralProjection)),
      outputs: Object.fromEntries(locales.map((locale) => [locale, localizedOutputPath(locale, sourcePath)])),
      segments,
    };
  });
  const policy = consequentialPolicy(bound.repository, sourceRevision, bound.headRevision, builtDocuments);
  builtDocuments = builtDocuments.map((document) => ({
    ...document,
    segments: document.segments.map((segment) => ({
      ...segment,
      requiredState: policy.ids.has(segment.id) ? ENGLISH_FALLBACK_STATE : PROMOTABLE_STATE,
    })),
  }));
  for (const locale of locales) {
    const outputs = builtDocuments.map((document) => document.outputs[locale]);
    if (new Set(outputs).size !== outputs.length) {
      throw new Error(`${locale}: selected sources map to colliding localized output paths`);
    }
  }
  const notice = {
    version: AUTHORITY_NOTICE_VERSION,
    sha256: sha256(canonicalJson(AUTHORITY_NOTICE_TEMPLATES)),
    languagePickerVersion: LANGUAGE_PICKER_VERSION,
    sourceLanguagePickerSha256: sha256(
      sourceLanguagePicker(readTreeSource(bound.repository, sourceRevision, "README.md").source).value,
    ),
  };
  return {
    schema: SOURCE_MANIFEST_SCHEMA,
    sourceRevision,
    parser: PARSER_VERSIONS,
    notice,
    reviewPolicy: policy.commitment,
    limits: {
      maxSegmentsPerPacket: MAX_SEGMENTS_PER_PACKET,
      maxSourceCharactersPerPacket: MAX_SOURCE_CHARACTERS_PER_PACKET,
      maxTargetCharactersPerSegment: MAX_TARGET_CHARACTERS_PER_SEGMENT,
    },
    locales,
    documents: builtDocuments,
    packets: buildPackets(builtDocuments, locales),
  };
}

function validateSourceShape(manifest) {
  exactKeys(manifest, SOURCE_ROOT_KEYS, "source manifest");
  if (manifest.schema !== SOURCE_MANIFEST_SCHEMA) throw new Error("unsupported source manifest schema");
  exactKeys(
    manifest.notice,
    ["version", "sha256", "languagePickerVersion", "sourceLanguagePickerSha256"],
    "source manifest notice",
  );
  exactKeys(manifest.reviewPolicy, ["schema", "path", "sha256"], "source manifest review policy");
  if (manifest.reviewPolicy.schema !== CONSEQUENTIAL_POLICY_SCHEMA) {
    throw new Error("unsupported consequential policy schema");
  }
  const selectsPolicyDocument = manifest.documents.some(
    (document) => PRODUCTION_DOCUMENTS.slice(1).includes(document.sourcePath),
  );
  if (selectsPolicyDocument) {
    if (manifest.reviewPolicy.path !== CONSEQUENTIAL_POLICY_PATH) {
      throw new Error("source manifest does not bind the canonical consequential policy path");
    }
    assertSha(manifest.reviewPolicy.sha256, "reviewPolicy.sha256");
  } else if (manifest.reviewPolicy.path !== null || manifest.reviewPolicy.sha256 !== null) {
    throw new Error("a manifest without a classified production document cannot bind a consequential policy");
  }
  if (
    manifest.notice.version !== AUTHORITY_NOTICE_VERSION ||
    manifest.notice.sha256 !== sha256(canonicalJson(AUTHORITY_NOTICE_TEMPLATES)) ||
    manifest.notice.languagePickerVersion !== LANGUAGE_PICKER_VERSION
  ) {
    throw new Error("authority notice contract mismatch");
  }
  assertSha(manifest.notice.sourceLanguagePickerSha256, "notice.sourceLanguagePickerSha256");
  exactKeys(
    manifest.limits,
    ["maxSegmentsPerPacket", "maxSourceCharactersPerPacket", "maxTargetCharactersPerSegment"],
    "source manifest limits",
  );
  if (
    manifest.limits.maxSegmentsPerPacket !== MAX_SEGMENTS_PER_PACKET ||
    manifest.limits.maxSourceCharactersPerPacket !== MAX_SOURCE_CHARACTERS_PER_PACKET ||
    manifest.limits.maxTargetCharactersPerSegment !== MAX_TARGET_CHARACTERS_PER_SEGMENT
  ) {
    throw new Error("source manifest packet limits are not the fixed production limits");
  }
  if (canonicalJson(manifest.parser) !== canonicalJson(PARSER_VERSIONS)) {
    throw new Error("source manifest parser versions do not match the running tool");
  }
  if (JSON.stringify(manifest.locales) !== JSON.stringify(SUPPORTED_LOCALES)) {
    throw new Error("source manifest locales do not match the fixed release locale set");
  }
  const manifestPaths = manifest.documents.map((document) => document.sourcePath);
  if (
    manifestPaths.length === 0 ||
    new Set(manifestPaths).size !== manifestPaths.length ||
    manifestPaths[0] !== "README.md"
  ) {
    throw new Error("manifest documents must be non-empty and unique with README.md first");
  }
  for (const [documentIndex, document] of manifest.documents.entries()) {
    exactKeys(document, SOURCE_DOCUMENT_KEYS, `documents[${documentIndex}]`);
    normalizeSourcePath(document.sourcePath);
    assertSha(document.sourceSha256, `${document.sourcePath}.sourceSha256`);
    assertSha(document.structuralSha256, `${document.sourcePath}.structuralSha256`);
    exactKeys(document.outputs, SUPPORTED_LOCALES, `${document.sourcePath}.outputs`);
    for (const locale of SUPPORTED_LOCALES) {
      if (document.outputs[locale] !== localizedOutputPath(locale, document.sourcePath)) {
        throw new Error(`${document.sourcePath}: non-canonical output for ${locale}`);
      }
    }
    for (const [segmentIndex, segment] of document.segments.entries()) {
      exactKeys(segment, SOURCE_SEGMENT_KEYS, `${document.sourcePath}.segments[${segmentIndex}]`);
      validateSourceSegmentCommitment(segment, document.sourcePath);
    }
  }
  for (const locale of SUPPORTED_LOCALES) {
    const outputs = manifest.documents.map((document) => document.outputs[locale]);
    if (new Set(outputs).size !== outputs.length) {
      throw new Error(`${locale}: localized output paths must be unique`);
    }
  }
  for (const [packetIndex, packet] of manifest.packets.entries()) {
    exactKeys(packet, PACKET_KEYS, `packets[${packetIndex}]`);
    normalizeLocale(packet.locale);
    for (const [ownerIndex, owner] of packet.owners.entries()) {
      exactKeys(owner, OWNER_KEYS, `${packet.id}.owners[${ownerIndex}]`);
    }
  }
}

export function validateSourceManifest(manifest, { repository, head = "HEAD" }) {
  validateSourceShape(manifest);
  bindSourceRevision(repository, manifest.sourceRevision, head);
  for (const document of manifest.documents) {
    assertCurrentSource(
      repository,
      manifest.sourceRevision,
      document.sourcePath,
      document.sourceSha256,
      sha256,
      head,
    );
  }
  const rebuilt = buildSourceManifest({
    repository,
    sourceRevision: manifest.sourceRevision,
    documents: manifest.documents.map((document) => document.sourcePath),
    head,
  });
  if (canonicalJson(rebuilt) !== canonicalJson(manifest)) {
    throw new Error("source manifest is not exactly equal to the canonical rebuilt manifest");
  }
  return rebuilt;
}

function expandedSourceSegments(manifest, repository) {
  const expanded = new Map();
  for (const document of manifest.documents) {
    const tree = readTreeSource(repository, manifest.sourceRevision, document.sourcePath);
    const inventory = inventoryFor(document.sourcePath, tree.source);
    const segments = inventory.segments.map((segment) => parserSegment(segment, document.sourcePath));
    const requiredStates = new Map(document.segments.map((segment) => [segment.id, segment.requiredState]));
    const commitments = segments.map((segment) =>
      sourceSegmentCommitment(segment, requiredStates.get(segment.id)));
    if (canonicalJson(commitments) !== canonicalJson(document.segments)) {
      throw new Error(`${document.sourcePath}: private source expansion differs from the manifest commitments`);
    }
    expanded.set(document.sourcePath, new Map(segments.map((segment) => [segment.id, segment])));
  }
  return expanded;
}

export function buildTranslationPlan(manifest, { repository }) {
  validateSourceManifest(manifest, { repository });
  const manifestHash = sourceManifestSha256(manifest);
  const expanded = expandedSourceSegments(manifest, repository);
  return {
    schema: TRANSLATION_PLAN_SCHEMA,
    sourceManifestSha256: manifestHash,
    sourceRevision: manifest.sourceRevision,
    packets: manifest.packets.map((packet) => ({
      id: packet.id,
      locale: packet.locale,
      packetSha256: packetHash(packet),
      sourceManifestSha256: manifestHash,
      sourceRevision: manifest.sourceRevision,
      records: packet.owners.map((owner) => {
        const segment = expanded.get(owner.document)?.get(owner.segmentId);
        if (!segment) throw new Error(`${packet.id}: missing expanded owner ${owner.segmentId}`);
        return {
          document: owner.document,
          segmentId: segment.id,
          sourceSha256: segment.sourceSha256,
          sourceCharacters: segment.sourceCharacters,
          maskedSource: segment.maskedSource,
          bindings: segment.bindings,
        };
      }),
    })),
  };
}

export function validateTranslationPlan(manifest, plan, { repository }) {
  exactKeys(plan, PLAN_ROOT_KEYS, "translation plan");
  if (plan.schema !== TRANSLATION_PLAN_SCHEMA || !Array.isArray(plan.packets)) {
    throw new Error("unsupported translation plan schema");
  }
  for (const [packetIndex, packet] of plan.packets.entries()) {
    exactKeys(packet, PLAN_PACKET_KEYS, `translation plan packets[${packetIndex}]`);
    if (!Array.isArray(packet.records)) throw new Error(`${packet.id}: plan records must be an array`);
    for (const [recordIndex, record] of packet.records.entries()) {
      exactKeys(record, PLAN_RECORD_KEYS, `${packet.id}.records[${recordIndex}]`);
      if (!Array.isArray(record.bindings)) throw new Error(`${record.segmentId}: plan bindings must be an array`);
      for (const [bindingIndex, binding] of record.bindings.entries()) {
        exactKeys(binding, BINDING_KEYS, `${record.segmentId}.bindings[${bindingIndex}]`);
      }
    }
  }
  const rebuilt = buildTranslationPlan(manifest, { repository });
  if (canonicalJson(plan) !== canonicalJson(rebuilt)) {
    throw new Error("translation plan is not exactly equal to its manifest-bound Git source expansion");
  }
  return rebuilt;
}

function packetHash(packet) {
  return sha256(canonicalJson(packet));
}

function committedResult(result) {
  return {
    schema: result.schema,
    locale: result.locale,
    sourceManifestSha256: result.sourceManifestSha256,
    sourceRevision: result.sourceRevision,
    packetId: result.packetId,
    packetSha256: result.packetSha256,
    records: result.records.map((record) => ({
      document: record.document,
      segmentId: record.segmentId,
      sourceSha256: record.sourceSha256,
      targetSha256: record.targetSha256 ?? sha256(record.translation),
      state: record.state,
    })),
  };
}

function validateLocaleResults(manifest, locale, results, { repository }) {
  normalizeLocale(locale);
  if (!Array.isArray(results)) throw new Error("locale results must be an array");
  const expectedPackets = manifest.packets.filter((packet) => packet.locale === locale);
  if (results.length !== expectedPackets.length) throw new Error(`${locale}: packet result coverage mismatch`);
  const manifestHash = sourceManifestSha256(manifest);
  const byDocument = new Map(manifest.documents.map((document) => [document.sourcePath, document]));
  const expanded = expandedSourceSegments(manifest, repository);
  const flat = [];
  for (let index = 0; index < expectedPackets.length; index += 1) {
    const expected = expectedPackets[index];
    const result = results[index];
    exactKeys(result, RESULT_KEYS, `${locale} results[${index}]`);
    if (
      result.schema !== LOCALE_RESULT_SCHEMA ||
      result.locale !== locale ||
      result.sourceManifestSha256 !== manifestHash ||
      result.sourceRevision !== manifest.sourceRevision ||
      result.packetId !== expected.id ||
      result.packetSha256 !== packetHash(expected)
    ) {
      throw new Error(`${locale}: result binding mismatch for ${expected.id}`);
    }
    if (!Array.isArray(result.records) || result.records.length !== expected.owners.length) {
      throw new Error(`${expected.id}: result record coverage mismatch`);
    }
    for (let recordIndex = 0; recordIndex < expected.owners.length; recordIndex += 1) {
      const owner = expected.owners[recordIndex];
      const record = result.records[recordIndex];
      exactKeys(record, RESULT_RECORD_KEYS, `${expected.id}.records[${recordIndex}]`);
      const document = byDocument.get(owner.document);
      const segment = document?.segments.find((item) => item.id === owner.segmentId);
      if (
        !segment ||
        record.document !== owner.document ||
        record.segmentId !== owner.segmentId ||
        record.sourceSha256 !== segment.sourceSha256
      ) {
        throw new Error(`${expected.id}: result owner/source mismatch at record ${recordIndex}`);
      }
      if (record.state !== segment.requiredState) {
        throw new Error(`${record.segmentId}: result state must be ${segment.requiredState}`);
      }
      if (typeof record.translation !== "string" || !record.translation.trim()) {
        throw new Error(`${record.segmentId}: translation must be non-empty`);
      }
      if (codePointLength(record.translation) > MAX_TARGET_CHARACTERS_PER_SEGMENT) {
        throw new Error(`${record.segmentId}: translation exceeds the fixed target character limit`);
      }
      if (
        record.state === ENGLISH_FALLBACK_STATE &&
        record.translation !== expanded.get(owner.document)?.get(owner.segmentId)?.maskedSource
      ) {
        throw new Error(`${record.segmentId}: english-fallback must exactly preserve the masked English source`);
      }
      flat.push(record);
    }
  }
  return flat;
}

export function buildLocaleReceipt(manifest, locale, results, { repository }) {
  validateSourceManifest(manifest, { repository });
  const records = validateLocaleResults(manifest, locale, results, { repository });
  const resultBindings = results.map((result) => ({
    packetId: result.packetId,
    packetSha256: result.packetSha256,
    resultSha256: sha256(canonicalJson(committedResult(result))),
  }));
  const items = manifest.documents.map((document) => {
    const documentRecords = records
      .filter((record) => record.document === document.sourcePath)
      .map(({ document: recordDocument, segmentId, sourceSha256, translation, state }) => ({
        document: recordDocument,
        segmentId,
        sourceSha256,
        translation,
        state,
      }));
    const tree = readTreeSource(repository, manifest.sourceRevision, document.sourcePath);
    const inventory = inventoryFor(document.sourcePath, tree.source);
    const requiredStates = new Map(document.segments.map((segment) => [segment.id, segment.requiredState]));
    const canonicalSegments = inventory.segments.map((segment) => {
      const parsed = parserSegment(segment, document.sourcePath);
      return sourceSegmentCommitment(parsed, requiredStates.get(parsed.id));
    });
    if (canonicalJson(canonicalSegments) !== canonicalJson(document.segments)) {
      throw new Error(`${document.sourcePath}: parser inventory differs from the source manifest`);
    }
    const picker = document.sourcePath === "README.md" ? sourceLanguagePicker(tree.source) : null;
    const localizedPicker = picker ? localizedLanguagePicker(locale) : "";
    const reconstructed = reconstructMarkdown(inventory, documentRecords.map(({ state, ...record }) => record), {
      deterministicReplacements: picker ? [{
        exclusionId: inventory.excludedOwners[0].exclusionId,
        sourceSha256: inventory.excludedOwners[0].sourceSha256,
        replacement: localizedLanguagePickerRow(locale),
      }] : [],
    });
    const targetPath = document.outputs[locale];
    return {
      sourcePath: document.sourcePath,
      sourceSha256: document.sourceSha256,
      source: tree.source,
      targetPath,
      body: reconstructed.body,
      records: documentRecords,
      localizedPicker,
    };
  });
  const finalized = items.map((item) => {
    const relocated = relocateDocumentLinks(item, items, manifest, locale, repository);
    return { ...item, body: relocated.body };
  });
  const outputs = [];
  const receiptDocuments = [];
  for (const item of finalized) {
    const targetPath = item.targetPath;
    const notice = noticeFor(locale, item.sourcePath, targetPath);
    const content = notice + item.body;
    const projection = structuralProjection(item.body);
    outputs.push({ path: targetPath, content });
    receiptDocuments.push({
      sourcePath: item.sourcePath,
      sourceSha256: item.sourceSha256,
      targetPath,
      targetSha256: sha256(content),
      structureSha256: sha256(canonicalJson(projection)),
      noticeSha256: sha256(notice),
      languagePickerSha256: sha256(item.localizedPicker),
      segments: item.records.map((record) => ({
        segmentId: record.segmentId,
        sourceSha256: record.sourceSha256,
        targetSha256: sha256(record.translation),
        state: record.state,
      })),
    });
  }
  return {
    receipt: {
      schema: LOCALE_RECEIPT_SCHEMA,
      locale,
      sourceManifestSha256: sourceManifestSha256(manifest),
      sourceRevision: manifest.sourceRevision,
      results: resultBindings,
      documents: receiptDocuments,
    },
    outputs,
  };
}

function validateReceiptShape(receipt, manifest, locale) {
  exactKeys(receipt, RECEIPT_KEYS, `${locale} receipt`);
  if (
    receipt.schema !== LOCALE_RECEIPT_SCHEMA ||
    receipt.locale !== locale ||
    receipt.sourceManifestSha256 !== sourceManifestSha256(manifest) ||
    receipt.sourceRevision !== manifest.sourceRevision
  ) {
    throw new Error(`${locale}: locale receipt binding mismatch`);
  }
  if (!Array.isArray(receipt.documents) || receipt.documents.length !== manifest.documents.length) {
    throw new Error(`${locale}: locale receipt document coverage mismatch`);
  }
  const expectedPackets = manifest.packets.filter((packet) => packet.locale === locale);
  if (!Array.isArray(receipt.results) || receipt.results.length !== expectedPackets.length) {
    throw new Error(`${locale}: locale receipt result coverage mismatch`);
  }
  for (let index = 0; index < expectedPackets.length; index += 1) {
    const actual = receipt.results[index];
    const expected = expectedPackets[index];
    exactKeys(actual, RECEIPT_RESULT_KEYS, `${locale}.results[${index}]`);
    if (actual.packetId !== expected.id || actual.packetSha256 !== packetHash(expected)) {
      throw new Error(`${locale}: receipt packet binding mismatch at ${index}`);
    }
    assertSha(actual.resultSha256, `${locale}.results[${index}].resultSha256`);
  }
  for (let index = 0; index < manifest.documents.length; index += 1) {
    const source = manifest.documents[index];
    const target = receipt.documents[index];
    exactKeys(target, RECEIPT_DOCUMENT_KEYS, `${locale}.documents[${index}]`);
    if (
      target.sourcePath !== source.sourcePath ||
      target.sourceSha256 !== source.sourceSha256 ||
      target.targetPath !== source.outputs[locale]
    ) {
      throw new Error(`${locale}: receipt document binding mismatch at ${index}`);
    }
    for (const key of ["targetSha256", "structureSha256", "noticeSha256", "languagePickerSha256"]) {
      assertSha(target[key], `${locale}.${source.sourcePath}.${key}`);
    }
    if (!Array.isArray(target.segments) || target.segments.length !== source.segments.length) {
      throw new Error(`${locale}: receipt segment coverage mismatch for ${source.sourcePath}`);
    }
    for (let segmentIndex = 0; segmentIndex < source.segments.length; segmentIndex += 1) {
      const expected = source.segments[segmentIndex];
      const actual = target.segments[segmentIndex];
      exactKeys(actual, RECEIPT_SEGMENT_KEYS, `${locale}.${source.sourcePath}.segments[${segmentIndex}]`);
      if (
        actual.segmentId !== expected.id ||
        actual.sourceSha256 !== expected.sourceSha256 ||
        actual.state !== expected.requiredState
      ) {
        throw new Error(`${locale}: receipt segment binding mismatch at ${actual.segmentId}`);
      }
      assertSha(actual.targetSha256, `${locale}.${actual.segmentId}.targetSha256`);
    }
  }
  const receiptSegments = new Map(receipt.documents.flatMap((document) =>
    document.segments.map((segment) => [`${document.sourcePath}\0${segment.segmentId}`, segment])));
  const manifestHash = sourceManifestSha256(manifest);
  for (let index = 0; index < expectedPackets.length; index += 1) {
    const packet = expectedPackets[index];
    const commitment = committedResult({
      schema: LOCALE_RESULT_SCHEMA,
      locale,
      sourceManifestSha256: manifestHash,
      sourceRevision: manifest.sourceRevision,
      packetId: packet.id,
      packetSha256: packetHash(packet),
      records: packet.owners.map((owner) => {
        const segment = receiptSegments.get(`${owner.document}\0${owner.segmentId}`);
        if (!segment) throw new Error(`${locale}: missing receipt segment for ${owner.segmentId}`);
        return {
          document: owner.document,
          segmentId: segment.segmentId,
          sourceSha256: segment.sourceSha256,
          targetSha256: segment.targetSha256,
          state: segment.state,
        };
      }),
    });
    if (sha256(canonicalJson(commitment)) !== receipt.results[index].resultSha256) {
      throw new Error(`${locale}: receipt result commitment mismatch at ${packet.id}`);
    }
  }
}

export function validateLocaleReceipt(manifest, locale, receipt, { repository }) {
  validateSourceManifest(manifest, { repository });
  validateReceiptShape(receipt, manifest, locale);
  const items = receipt.documents.map((document, index) => {
    const sourceDocument = manifest.documents[index];
    const tree = readTreeSource(repository, manifest.sourceRevision, sourceDocument.sourcePath);
    const inventory = inventoryFor(sourceDocument.sourcePath, tree.source);
    const expectedNotice = noticeFor(locale, document.sourcePath, document.targetPath);
    const target = confinedWorkingPath(repository, document.targetPath, { mustExist: true, allowFile: true });
    const content = fs.readFileSync(target);
    if (sha256(content) !== document.targetSha256) {
      throw new Error(`${locale}: localized target hash mismatch: ${document.targetPath}`);
    }
    const targetText = content.toString("utf8");
    if (!Buffer.from(targetText, "utf8").equals(content) || !targetText.startsWith(expectedNotice)) {
      throw new Error(`${locale}: authority notice mismatch: ${document.targetPath}`);
    }
    const targetBody = targetText.slice(expectedNotice.length);
    const targetInventory = inventoryFor(sourceDocument.sourcePath, targetBody);
    if (targetInventory.segments.length !== sourceDocument.segments.length) {
      throw new Error(`${locale}: target segment coverage mismatch: ${document.targetPath}`);
    }
    const translations = targetInventory.segments.map((targetSegment, segmentIndex) => {
      const receiptSegment = document.segments[segmentIndex];
      if (typeof targetSegment.maskedSource !== "string" || !targetSegment.maskedSource.trim()) {
        throw new Error(`${locale}: target segment is empty: ${receiptSegment.segmentId}`);
      }
      if (codePointLength(targetSegment.maskedSource) > MAX_TARGET_CHARACTERS_PER_SEGMENT) {
        throw new Error(`${locale}: target segment exceeds the fixed target character limit: ${receiptSegment.segmentId}`);
      }
      if (sha256(targetSegment.maskedSource) !== receiptSegment.targetSha256) {
        throw new Error(`${locale}: target segment hash mismatch: ${receiptSegment.segmentId}`);
      }
      if (
        receiptSegment.state === ENGLISH_FALLBACK_STATE &&
        targetSegment.maskedSource !== inventory.segments[segmentIndex].maskedSource
      ) {
        throw new Error(`${locale}: english-fallback target differs from masked English source: ${receiptSegment.segmentId}`);
      }
      return targetSegment.maskedSource;
    });
    const reconstructed = reconstructMarkdown(
      inventory,
      document.segments.map((segment, segmentIndex) => ({
        document: sourceDocument.sourcePath,
        segmentId: segment.segmentId,
        sourceSha256: segment.sourceSha256,
        translation: translations[segmentIndex],
      })),
      {
        deterministicReplacements: sourceDocument.sourcePath === "README.md" ? [{
          exclusionId: inventory.excludedOwners[0].exclusionId,
          sourceSha256: inventory.excludedOwners[0].sourceSha256,
          replacement: localizedLanguagePickerRow(locale),
        }] : [],
      },
    );
    return {
      sourcePath: sourceDocument.sourcePath,
      source: tree.source,
      targetPath: document.targetPath,
      body: reconstructed.body,
      content,
      targetBody,
      receipt: document,
    };
  });
  const finalized = items.map((item) => ({
    ...item,
    body: relocateDocumentLinks(item, items, manifest, locale, repository).body,
  }));
  for (const [index, item] of finalized.entries()) {
    const document = item.receipt;
    const expectedNotice = noticeFor(locale, document.sourcePath, document.targetPath);
    const expectedContent = expectedNotice + item.body;
    const content = item.content;
    if (!content.equals(Buffer.from(expectedContent, "utf8")) || sha256(content) !== document.targetSha256) {
      throw new Error(`${locale}: localized target hash mismatch: ${document.targetPath}`);
    }
    const notice = expectedNotice;
    if (document.noticeSha256 !== sha256(notice) || !content.toString("utf8").startsWith(notice)) {
      throw new Error(`${locale}: authority notice mismatch: ${document.targetPath}`);
    }
    const body = content.toString("utf8").slice(notice.length);
    if (document.sourcePath === "README.md") {
      const picker = localizedLanguagePicker(locale);
      if (document.languagePickerSha256 !== sha256(picker) || !body.includes(picker)) {
        throw new Error(`${locale}: localized language picker mismatch: ${document.targetPath}`);
      }
    } else if (document.languagePickerSha256 !== sha256("")) {
      throw new Error(`${locale}: non-README receipt has a language picker`);
    }
    const projectionHash = sha256(canonicalJson(structuralProjection(body)));
    if (
      projectionHash !== document.structureSha256 ||
      document.structureSha256 !== sha256(canonicalJson(structuralProjection(item.body)))
    ) {
      throw new Error(`${locale}: localized structure hash mismatch: ${document.targetPath}`);
    }
    if (document.targetPath !== manifest.documents[index].outputs[locale]) {
      throw new Error(`${locale}: non-canonical localized target order`);
    }
  }
  return receipt;
}

function atomicNoClobber(repository, files, validate) {
  const staged = [];
  const linked = [];
  try {
    for (const file of files) {
      const target = confinedWorkingPath(repository, file.path);
      if (fs.existsSync(target)) throw new Error(`target already exists: ${file.path}`);
      fs.mkdirSync(path.dirname(target), { recursive: true });
      confinedWorkingPath(repository, file.path);
      const temporary = path.join(
        path.dirname(target),
        `.${path.basename(target)}.${crypto.randomUUID()}.tmp`,
      );
      fs.writeFileSync(temporary, file.content, { encoding: "utf8", flag: "wx", mode: 0o644 });
      staged.push({ temporary, target });
    }
    for (const item of staged) {
      fs.linkSync(item.temporary, item.target);
      linked.push(item.target);
    }
    validate();
  } catch (error) {
    for (const target of linked.reverse()) {
      try { fs.unlinkSync(target); } catch {}
    }
    throw error;
  } finally {
    for (const item of staged) {
      try { fs.unlinkSync(item.temporary); } catch {}
    }
  }
}

export function applyLocaleReceipt({ repository, manifest, locale, results }) {
  const root = normalizeRepository(repository);
  validateSourceManifest(manifest, { repository: root });
  const built = buildLocaleReceipt(manifest, locale, results, { repository: root });
  const receiptRelative = localeReceiptPath(locale);
  const receiptTarget = confinedWorkingPath(root, receiptRelative);
  if (fs.existsSync(receiptTarget)) throw new Error(`target already exists: ${receiptRelative}`);
  atomicNoClobber(
    root,
    [
      ...built.outputs,
      { path: receiptRelative, content: canonicalJson(built.receipt) },
    ],
    () => validateLocaleReceipt(manifest, locale, built.receipt, { repository: root }),
  );
  return built.receipt;
}

export function validateRepository({ repository, manifestPath = "docs/i18n/manifest.json" }) {
  const root = normalizeRepository(repository);
  const relativeManifest = path.isAbsolute(manifestPath)
    ? path.relative(root, manifestPath).split(path.sep).join("/")
    : manifestPath;
  if (relativeManifest !== "docs/i18n/manifest.json") {
    throw new Error("manifest must be the canonical docs/i18n/manifest.json path");
  }
  const manifestFile = confinedWorkingPath(root, relativeManifest, { mustExist: true, allowFile: true });
  const manifest = readCanonicalJson(manifestFile);
  validateSourceManifest(manifest, { repository: root });
  if (
    JSON.stringify(manifest.documents.map((document) => document.sourcePath)) !==
    JSON.stringify(PRODUCTION_DOCUMENTS)
  ) {
    throw new Error(`the current localization proof must select exactly ${PRODUCTION_DOCUMENTS.join(", ")}`);
  }
  for (const locale of SUPPORTED_LOCALES) {
    const receiptFile = confinedWorkingPath(root, localeReceiptPath(locale), {
      mustExist: true,
      allowFile: true,
    });
    const receipt = readCanonicalJson(receiptFile);
    validateLocaleReceipt(manifest, locale, receipt, { repository: root });
    const expectedOutputs = new Set(manifest.documents.map((document) => document.outputs[locale]));
    const localeRoot = confinedWorkingPath(root, `docs/${locale}`, { mustExist: true });
    const actualOutputs = [];
    const visit = (directory) => {
      for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
        const absolute = path.join(directory, entry.name);
        const relative = path.relative(root, absolute).split(path.sep).join("/");
        if (entry.isSymbolicLink()) throw new Error(`symlink in localized output tree: ${relative}`);
        if (entry.isDirectory()) visit(absolute);
        else if (entry.isFile()) actualOutputs.push(relative);
        else throw new Error(`non-regular entry in localized output tree: ${relative}`);
      }
    };
    visit(localeRoot);
    actualOutputs.sort();
    if (JSON.stringify(actualOutputs) !== JSON.stringify([...expectedOutputs].sort())) {
      throw new Error(`${locale}: localized output tree differs from the exact manifest selection`);
    }
  }
  const receiptRoot = confinedWorkingPath(root, "docs/i18n/locales", { mustExist: true });
  const receiptFiles = fs.readdirSync(receiptRoot, { withFileTypes: true }).map((entry) => {
    if (!entry.isFile() || entry.isSymbolicLink()) {
      throw new Error(`locale receipt directory contains a non-regular entry: ${entry.name}`);
    }
    return `docs/i18n/locales/${entry.name}`;
  }).sort();
  const expectedReceipts = SUPPORTED_LOCALES.map(localeReceiptPath).sort();
  if (JSON.stringify(receiptFiles) !== JSON.stringify(expectedReceipts)) {
    throw new Error("locale receipt directory differs from the exact release locale set");
  }
  return manifest;
}
