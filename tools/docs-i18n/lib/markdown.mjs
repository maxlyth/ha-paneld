import crypto from "node:crypto";
import path from "node:path";

import GithubSlugger from "github-slugger";
import * as parse5 from "parse5";
import remarkGfm from "remark-gfm";
import remarkParse from "remark-parse";
import { unified } from "unified";

export const PARSER_VERSIONS = Object.freeze({
  unified: "11.0.5",
  remarkParse: "11.0.0",
  remarkGfm: "4.0.1",
  parse5: "7.3.0",
});

const parser = unified().use(remarkParse).use(remarkGfm);
const TOKEN = /<x id="m\d{4}"\/>/g;
const TOKEN_SOURCE = /<x id="m\d{4}"\/>/;
const ALERT = /^\[!([A-Z]+)\](?:\n|$)/;
const ALERT_KINDS = new Set(["NOTE", "TIP", "IMPORTANT", "WARNING", "CAUTION"]);
const HTML_TRANSLATED_ATTRIBUTES = new Set(["alt", "title", "aria-label"]);
const HTML_FROZEN_TEXT = new Set(["script", "style", "pre", "code"]);

export function sha256(value) {
  return crypto.createHash("sha256").update(value).digest("hex");
}

function canonical(value) {
  if (Array.isArray(value)) return value.map(canonical);
  if (value && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonical(value[key])]));
  }
  return value;
}

function canonicalBytes(value) {
  return Buffer.from(JSON.stringify(canonical(value)));
}

function exactKeys(value, expected, label) {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error(`${label} must be an object`);
  }
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  if (JSON.stringify(actual) !== JSON.stringify(wanted)) {
    throw new Error(`${label} keys mismatch: ${actual.join(",")}`);
  }
}

function validateDocument(document) {
  if (typeof document !== "string" || !document || document.includes("\\") || document.startsWith("/")) {
    throw new Error(`unsafe document path: ${document}`);
  }
  if (path.posix.normalize(document) !== document || document === ".." || document.startsWith("../") || !document.endsWith(".md")) {
    throw new Error(`unsafe document path: ${document}`);
  }
}

function validateSource(source) {
  if (typeof source !== "string" || Buffer.from(source).toString("utf8") !== source) {
    throw new Error("Markdown source must be valid UTF-8 text");
  }
  if (source.startsWith("\uFEFF") || source.includes("\r") || source.includes("\0")) {
    throw new Error("Markdown source must be BOM-free UTF-8 with LF line endings and no NUL");
  }
  if (TOKEN_SOURCE.test(source)) throw new Error("Markdown source collides with the reserved binding token syntax");
}

function offsets(node) {
  const start = node.position?.start?.offset;
  const end = node.position?.end?.offset;
  if (!Number.isInteger(start) || !Number.isInteger(end) || start < 0 || end < start) {
    throw new Error(`Markdown node lacks exact source offsets: ${node.type}`);
  }
  return [start, end];
}

function visit(node, parents, callback) {
  callback(node, parents);
  for (const child of node.children || []) visit(child, [...parents, node], callback);
}

function parseHtml(raw) {
  const errors = [];
  const tree = parse5.parseFragment(raw, {
    sourceCodeLocationInfo: true,
    onParseError: (error) => errors.push(error),
  });
  if (errors.length) throw new Error(`HTML parse error: ${errors[0].code}`);
  return tree;
}

function visitHtml(node, callback, frozen = false) {
  const nowFrozen = frozen || HTML_FROZEN_TEXT.has(node.tagName);
  callback(node, nowFrozen);
  for (const child of node.childNodes || []) visitHtml(child, callback, nowFrozen);
}

function htmlVisibleRanges(raw, baseOffset) {
  const ranges = [];
  visitHtml(parseHtml(raw), (node, frozen) => {
    if (node.nodeName === "#text" && !frozen && node.value.trim()) {
      const location = node.sourceCodeLocation;
      if (!location) throw new Error("visible HTML text lacks exact source offsets");
      ranges.push({
        start: baseOffset + location.startOffset,
        end: baseOffset + location.endOffset,
        kind: "html-text",
      });
    }
    if (!node.attrs || !node.sourceCodeLocation?.attrs) return;
    for (const attribute of node.attrs) {
      if (!HTML_TRANSLATED_ATTRIBUTES.has(attribute.name) || !attribute.value.trim()) continue;
      const location = node.sourceCodeLocation.attrs[attribute.name];
      const attributeSource = raw.slice(location.startOffset, location.endOffset);
      const match = /^[^=]+\s*=\s*(["'])([\s\S]*)\1$/.exec(attributeSource);
      if (!match || match[2] !== attribute.value) {
        throw new Error(`unsupported HTML attribute quoting for ${attribute.name}`);
      }
      const localOffset = attributeSource.indexOf(match[2]);
      ranges.push({
        start: baseOffset + location.startOffset + localOffset,
        end: baseOffset + location.startOffset + localOffset + match[2].length,
        kind: `html-${attribute.name}`,
      });
    }
  });
  return ranges;
}

function githubAlertKind(node, parents) {
  const parent = parents.at(-1);
  if (node.type !== "paragraph" || parent?.type !== "blockquote" || parent.children?.[0] !== node) return null;
  const first = node.children?.[0];
  if (first?.type !== "text") return null;
  const match = ALERT.exec(first.value);
  if (!match) return null;
  if (!ALERT_KINDS.has(match[1])) throw new Error(`unsupported GitHub alert directive: ${match[1]}`);
  return match[1];
}

function textRanges(source, node, omitAlertMarker) {
  const [start, end] = offsets(node);
  const raw = source.slice(start, end);
  const pieces = node.value.split("\n");
  const ranges = [];
  let cursor = 0;
  for (let index = 0; index < pieces.length; index += 1) {
    const piece = pieces[index];
    if (!piece) continue;
    const found = raw.indexOf(piece, cursor);
    if (found < 0) throw new Error(`normalized or escaped Markdown text is unsupported at offset ${start}`);
    cursor = found + piece.length;
    if (omitAlertMarker && index === 0 && ALERT_KINDS.has(ALERT.exec(piece)?.[1])) continue;
    ranges.push({ start: start + found, end: start + found + piece.length, kind: "text" });
  }
  return ranges;
}

function imageAltRange(source, node) {
  if (!node.alt) return [];
  const [start, end] = offsets(node);
  const raw = source.slice(start, end);
  const match = /^!\[([^\]\n]*)\]/.exec(raw);
  if (!match || match[1] !== node.alt) throw new Error(`unsupported image alt syntax at offset ${start}`);
  return [{ start: start + 2, end: start + 2 + match[1].length, kind: "image-alt" }];
}

function imageReferenceAltRange(source, node) {
  if (!node.alt || node.referenceType !== "full") return [];
  const [start, end] = offsets(node);
  const raw = source.slice(start, end);
  const match = /^!\[([^\]\n]*)\]\[[^\]\n]+\]$/.exec(raw);
  if (!match || match[1] !== node.alt) throw new Error(`unsupported image reference alt syntax at offset ${start}`);
  return [{ start: start + 2, end: start + 2 + match[1].length, kind: "image-alt" }];
}

function insideInseparableReference(source, parents) {
  return parents.some((parent) =>
    (parent.type === "linkReference" || parent.type === "imageReference")
      && parent.referenceType !== "full") || parents.some((parent) => {
    if (parent.type !== "link") return false;
    const [start, end] = offsets(parent);
    const raw = source.slice(start, end);
    return raw.startsWith("<") && raw.endsWith(">");
  });
}

function ownerVisibleRanges(source, owner, parents) {
  const ranges = [];
  const alertKind = githubAlertKind(owner, parents);
  visit(owner, [], (node, localParents) => {
    if (node.type === "text" && !insideInseparableReference(source, localParents)) {
      ranges.push(...textRanges(source, node, Boolean(alertKind) && node === owner.children?.[0]));
    } else if (node.type === "image") {
      ranges.push(...imageAltRange(source, node));
    } else if (node.type === "imageReference") {
      ranges.push(...imageReferenceAltRange(source, node));
    } else if (node.type === "html") {
      const [start, end] = offsets(node);
      ranges.push(...htmlVisibleRanges(source.slice(start, end), start));
    }
  });
  ranges.sort((left, right) => left.start - right.start || left.end - right.end);
  for (let index = 1; index < ranges.length; index += 1) {
    if (ranges[index - 1].end > ranges[index].start) throw new Error("overlapping Markdown visible ranges");
  }
  return { alertKind, ranges };
}

function visibleHash(source, owner, ranges) {
  return sha256(canonicalBytes({
    rendered: renderedVisibleIdentity(owner),
    translatable: ranges.map((range) => source.slice(range.start, range.end)),
  }));
}

function makeSegment(document, source, owner, parents, ancestry, occurrences) {
  const { alertKind, ranges } = ownerVisibleRanges(source, owner, parents);
  if (!ranges.length) return null;
  const [start, end] = offsets(owner);
  let cursor = start;
  let maskedSource = "";
  const bindings = [];
  for (const range of ranges) {
    if (range.start < cursor || range.end > end) throw new Error("visible Markdown range escaped its owner");
    if (range.start > cursor) {
      const token = `<x id="m${String(bindings.length + 1).padStart(4, "0")}"/>`;
      const value = source.slice(cursor, range.start);
      bindings.push({ token, value, start: cursor, end: range.start, sha256: sha256(value) });
      maskedSource += token;
    }
    maskedSource += source.slice(range.start, range.end);
    cursor = range.end;
  }
  if (cursor < end) {
    const token = `<x id="m${String(bindings.length + 1).padStart(4, "0")}"/>`;
    const value = source.slice(cursor, end);
    bindings.push({ token, value, start: cursor, end, sha256: sha256(value) });
    maskedSource += token;
  }
  if (bindings.length > 9999) throw new Error("Markdown segment requires too many protected bindings");
  const sourceSha256 = visibleHash(source, owner, ranges);
  const ancestryKey = ancestry.length
    ? ancestry.map((entry) => `${entry.depth}:${entry.sourceSha256}`).join("/")
    : "root";
  const identityBase = `${document}::${ancestryKey}::${owner.type}::${sourceSha256}`;
  const occurrence = (occurrences.get(identityBase) || 0) + 1;
  occurrences.set(identityBase, occurrence);
  return {
    document,
    segmentId: `${identityBase}::${occurrence}`,
    sectionAncestry: ancestry.map((entry) => ({ ...entry })),
    ownerType: owner.type,
    occurrence,
    start,
    end,
    sourceSha256,
    maskedSource,
    bindings,
    visibleRanges: ranges,
    githubAlert: alertKind,
  };
}

function htmlProjection(raw) {
  function project(node, frozen = false) {
    const nowFrozen = frozen || HTML_FROZEN_TEXT.has(node.tagName);
    if (node.nodeName === "#comment") return { type: "comment", value: node.data };
    if (node.nodeName === "#text") return { type: "text", value: nowFrozen ? node.value : null };
    const projected = { type: node.nodeName };
    if (node.attrs) {
      projected.attributes = node.attrs.map((attribute) => ({
        name: attribute.name,
        value: HTML_TRANSLATED_ATTRIBUTES.has(attribute.name) ? null : attribute.value,
      }));
    }
    if (node.childNodes) projected.children = node.childNodes.map((child) => project(child, nowFrozen));
    return projected;
  }
  return project(parseHtml(raw));
}

export function structuralProjection(source) {
  validateSource(source);
  const tree = parser.parse(source);
  function project(node, parents = []) {
    const projected = { type: node.type };
    for (const key of ["depth", "ordered", "start", "spread", "checked", "align", "identifier", "label", "referenceType"]) {
      if (node[key] !== undefined) projected[key] = node[key];
    }
    if (["code", "inlineCode", "definition"].includes(node.type)) projected.value = node.value;
    if (["link", "image", "definition"].includes(node.type)) {
      projected.url = node.url;
      projected.title = node.title;
    }
    const alertKind = githubAlertKind(node, parents);
    if (alertKind) projected.githubAlert = alertKind;
    if (node.type === "html") projected.html = htmlProjection(node.value);
    if (node.children) projected.children = node.children.map((child) => project(child, [...parents, node]));
    return projected;
  }
  return project(tree);
}

function htmlRenderedText(raw) {
  let text = "";
  visitHtml(parseHtml(raw), (node, frozen) => {
    if (node.nodeName === "#text" && !frozen) text += node.value;
  });
  return text;
}

function htmlVisibleIdentity(raw) {
  const values = [];
  visitHtml(parseHtml(raw), (node, frozen) => {
    if (node.nodeName === "#text" && !frozen && node.value) values.push(["text", node.value]);
    for (const attribute of node.attrs || []) {
      if (HTML_TRANSLATED_ATTRIBUTES.has(attribute.name) && attribute.value) {
        values.push([`attribute:${attribute.name}`, attribute.value]);
      }
    }
  });
  return values;
}

function renderedVisibleIdentity(node) {
  if (node.type === "text" || node.type === "inlineCode") return [[node.type, node.value]];
  if (node.type === "image" || node.type === "imageReference") return [[node.type, node.alt || ""]];
  if (node.type === "html") return htmlVisibleIdentity(node.value);
  if (node.type === "break") return [["break", "\n"]];
  return (node.children || []).flatMap(renderedVisibleIdentity);
}

function headingRenderedText(node) {
  if (node.type === "text" || node.type === "inlineCode") return node.value;
  if (node.type === "image" || node.type === "imageReference") return node.alt || "";
  if (node.type === "html") return htmlRenderedText(node.value);
  if (node.type === "break") return " ";
  if (node.children) return node.children.map(headingRenderedText).join("");
  throw new Error(`unsupported node in Markdown heading anchor: ${node.type}`);
}

export function headingAnchors(source) {
  validateSource(source);
  const slugger = new GithubSlugger();
  const headings = [];
  visit(parser.parse(source), [], (node) => {
    if (node.type !== "heading") return;
    const [start, end] = offsets(node);
    headings.push({ anchor: slugger.slug(headingRenderedText(node)), start, end });
  });
  return headings;
}

function markdownDestinationRange(source, node) {
  const [start, end] = offsets(node);
  const raw = source.slice(start, end);
  if (node.type === "link" && raw === `<${node.url}>`) {
    return { start: start + 1, end: end - 1 };
  }
  let searchFrom = 0;
  if (node.type === "definition") {
    const colon = raw.indexOf(":");
    if (colon < 0) throw new Error(`definition destination lacks a delimiter at offset ${start}`);
    searchFrom = colon + 1;
  } else if (node.children?.length) {
    searchFrom = offsets(node.children.at(-1))[1] - start;
  } else {
    const labelEnd = raw.indexOf("]");
    if (labelEnd < 0) throw new Error(`link destination lacks a label delimiter at offset ${start}`);
    searchFrom = labelEnd + 1;
  }
  const local = raw.indexOf(node.url, searchFrom);
  if (local < 0) throw new Error(`link destination lacks an exact source range at offset ${start}`);
  return { start: start + local, end: start + local + node.url.length };
}

function htmlDestinationRanges(raw, baseOffset) {
  const ranges = [];
  visitHtml(parseHtml(raw), (node) => {
    if (!node.attrs || !node.sourceCodeLocation?.attrs) return;
    for (const attribute of node.attrs) {
      if (!["href", "src", "srcset"].includes(attribute.name) || !attribute.value) continue;
      const location = node.sourceCodeLocation.attrs[attribute.name];
      const attributeSource = raw.slice(location.startOffset, location.endOffset);
      if (attribute.name !== "srcset") {
        const local = attributeSource.indexOf(attribute.value);
        if (local < 0 || attributeSource.indexOf(attribute.value, local + attribute.value.length) >= 0) {
          throw new Error(`HTML ${attribute.name} lacks an unambiguous source range`);
        }
        ranges.push({
          kind: `html-${attribute.name}`,
          url: attribute.value,
          start: baseOffset + location.startOffset + local,
          end: baseOffset + location.startOffset + local + attribute.value.length,
        });
        continue;
      }
      let searchFrom = 0;
      for (const candidate of attribute.value.split(",").map((item) => item.trim().split(/\s+/)[0]).filter(Boolean)) {
        const local = attributeSource.indexOf(candidate, searchFrom);
        if (local < 0) throw new Error("HTML srcset candidate lacks an exact source range");
        ranges.push({
          kind: "html-srcset",
          url: candidate,
          start: baseOffset + location.startOffset + local,
          end: baseOffset + location.startOffset + local + candidate.length,
        });
        searchFrom = local + candidate.length;
      }
    }
  });
  return ranges;
}

export function linkDestinations(source) {
  validateSource(source);
  const destinations = [];
  visit(parser.parse(source), [], (node) => {
    if (["link", "image", "definition"].includes(node.type) && node.url) {
      const range = markdownDestinationRange(source, node);
      destinations.push({ kind: node.type, url: node.url, title: node.title ?? null, ...range });
    }
    if (node.type === "html") {
      const [start, end] = offsets(node);
      destinations.push(...htmlDestinationRanges(source.slice(start, end), start));
    }
  });
  destinations.sort((left, right) => left.start - right.start || left.end - right.end);
  for (let index = 1; index < destinations.length; index += 1) {
    if (destinations[index - 1].end > destinations[index].start) throw new Error("overlapping Markdown link destinations");
  }
  return destinations.map((destination, index) => ({ linkIndex: index, ...destination }));
}

function projectionWithoutDestinations(value) {
  if (Array.isArray(value)) return value.map(projectionWithoutDestinations);
  if (!value || typeof value !== "object") return value;
  const output = {};
  for (const [key, item] of Object.entries(value)) {
    if (key === "url") output[key] = null;
    else if (key === "attributes") {
      output[key] = item.map((attribute) => ({
        ...attribute,
        value: ["href", "src", "srcset"].includes(attribute.name) ? null : attribute.value,
      }));
    } else output[key] = projectionWithoutDestinations(item);
  }
  return output;
}

export function rewriteLinkDestinations(source, resolver) {
  validateSource(source);
  if (typeof resolver !== "function") throw new Error("Markdown link resolver must be a function");
  const destinations = linkDestinations(source);
  const replacements = destinations.map((destination) => {
    const replacement = resolver({ ...destination });
    if (typeof replacement !== "string" || !replacement || replacement.includes("\r") || replacement.includes("\0")) {
      throw new Error(`invalid replacement for Markdown link ${destination.linkIndex}`);
    }
    return { ...destination, replacement };
  });
  let body = source;
  for (const replacement of [...replacements].reverse()) {
    body = body.slice(0, replacement.start) + replacement.replacement + body.slice(replacement.end);
  }
  const targetDestinations = linkDestinations(body);
  if (targetDestinations.length !== replacements.length) throw new Error("Markdown link topology changed during rewrite");
  targetDestinations.forEach((target, index) => {
    if (target.kind !== replacements[index].kind || target.url !== replacements[index].replacement) {
      throw new Error(`Markdown link rewrite did not reparse exactly at ${index}`);
    }
  });
  const sourceProjection = projectionWithoutDestinations(structuralProjection(source));
  const targetProjection = projectionWithoutDestinations(structuralProjection(body));
  if (!canonicalBytes(sourceProjection).equals(canonicalBytes(targetProjection))) {
    throw new Error("Markdown structure changed outside rewritten link destinations");
  }
  return {
    body,
    bodySha256: sha256(Buffer.from(body)),
    rewrites: replacements.map(({ linkIndex, kind, url, replacement }) => ({ linkIndex, kind, source: url, replacement })),
  };
}

function validateRequestedExclusions(excludedRanges) {
  if (!Array.isArray(excludedRanges)) throw new Error("excludedRanges must be an array");
  let previousEnd = 0;
  const labels = new Set();
  return excludedRanges.map((range, index) => {
    exactKeys(range, ["start", "end", "label"], "Markdown excluded range");
    if (!Number.isInteger(range.start) || !Number.isInteger(range.end) || range.start < previousEnd || range.end <= range.start) {
      throw new Error(`invalid or overlapping Markdown excluded range at ${index}`);
    }
    if (typeof range.label !== "string" || !/^[a-z][a-z0-9-]{0,63}$/.test(range.label)) {
      throw new Error(`invalid Markdown exclusion label at ${index}`);
    }
    if (labels.has(range.label)) throw new Error(`duplicate Markdown exclusion label: ${range.label}`);
    labels.add(range.label);
    previousEnd = range.end;
    return { ...range };
  });
}

function extractSegments(document, source, requestedExclusions) {
  const tree = parser.parse(source);
  const owners = [];
  visit(tree, [], (node, parents) => {
    githubAlertKind(node, parents);
    if (["paragraph", "heading", "tableCell"].includes(node.type)) {
      if (!parents.some((parent) => ["paragraph", "heading", "tableCell"].includes(parent.type))) {
        owners.push({ node, parents });
      }
    } else if (node.type === "html" && !parents.some((parent) => ["paragraph", "heading", "tableCell", "html"].includes(parent.type))) {
      owners.push({ node, parents });
    }
  });
  owners.sort((left, right) => offsets(left.node)[0] - offsets(right.node)[0]);
  const exclusions = validateRequestedExclusions(requestedExclusions);
  const ownerByRange = new Map(owners.map((owner) => {
    const [start, end] = offsets(owner.node);
    return [`${start}:${end}`, owner];
  }));
  for (const exclusion of exclusions) {
    const owner = ownerByRange.get(`${exclusion.start}:${exclusion.end}`);
    if (!owner) throw new Error(`Markdown exclusion is not aligned to one translation owner: ${exclusion.label}`);
    exclusion.ownerType = owner.node.type;
    exclusion.sourceSha256 = sha256(source.slice(exclusion.start, exclusion.end));
    exclusion.exclusionId = `${document}::excluded::${exclusion.label}::${exclusion.sourceSha256}`;
  }
  const exclusionsByRange = new Map(exclusions.map((item) => [`${item.start}:${item.end}`, item]));
  const ancestry = [];
  const occurrences = new Map();
  const segments = [];
  let previousEnd = 0;
  for (const owner of owners) {
    const [start, end] = offsets(owner.node);
    if (start < previousEnd) throw new Error("overlapping Markdown translation owners");
    const excluded = exclusionsByRange.has(`${start}:${end}`);
    if (!excluded) {
      const segment = makeSegment(document, source, owner.node, owner.parents, ancestry, occurrences);
      if (segment) segments.push(segment);
    }
    previousEnd = end;
    if (owner.node.type === "heading") {
      const headingRanges = ownerVisibleRanges(source, owner.node, owner.parents).ranges;
      const entry = { depth: owner.node.depth, sourceSha256: visibleHash(source, owner.node, headingRanges) };
      while (ancestry.length && ancestry.at(-1).depth >= owner.node.depth) ancestry.pop();
      ancestry.push(entry);
    }
  }
  return { segments, exclusions };
}

export function inventoryMarkdown(document, source, options = { excludedRanges: [] }) {
  validateDocument(document);
  validateSource(source);
  exactKeys(options, ["excludedRanges"], "Markdown inventory options");
  const extracted = extractSegments(document, source, options.excludedRanges);
  // Force exact destination discovery during planning so unsupported link syntax
  // fails before any translation packet can be sent.
  linkDestinations(source);
  const body = {
    schemaVersion: 1,
    parser: PARSER_VERSIONS,
    document,
    sourceSha256: sha256(Buffer.from(source)),
    source,
    segments: extracted.segments,
    excludedOwners: extracted.exclusions,
    structuralProjection: structuralProjection(source),
  };
  return { ...body, inventorySha256: sha256(canonicalBytes(body)) };
}

export function validateInventory(inventory) {
  exactKeys(inventory, [
    "schemaVersion", "parser", "document", "sourceSha256", "source", "segments",
    "excludedOwners", "structuralProjection", "inventorySha256",
  ], "Markdown inventory");
  const rebuilt = inventoryMarkdown(inventory.document, inventory.source, {
    excludedRanges: inventory.excludedOwners.map(({ start, end, label }) => ({ start, end, label })),
  });
  if (!canonicalBytes(rebuilt).equals(canonicalBytes(inventory))) {
    throw new Error("Markdown inventory differs from its canonical rebuild");
  }
  return rebuilt;
}

function validateRecords(inventory, records) {
  if (!Array.isArray(records) || records.length !== inventory.segments.length) {
    throw new Error("Markdown translation record coverage mismatch");
  }
  records.forEach((record, index) => {
    exactKeys(record, ["document", "segmentId", "sourceSha256", "translation"], "Markdown translation record");
    const expected = inventory.segments[index];
    for (const key of ["document", "segmentId", "sourceSha256"]) {
      if (record[key] !== expected[key]) throw new Error(`Markdown translation record mismatch at ${index}: ${key}`);
    }
    if (typeof record.translation !== "string" || !record.translation.trim()) {
      throw new Error(`empty Markdown translation: ${record.segmentId}`);
    }
  });
}

function restoreParts(segment, translation) {
  const actualTokens = translation.match(TOKEN) || [];
  const expectedTokens = segment.bindings.map((binding) => binding.token);
  if (JSON.stringify(actualTokens) !== JSON.stringify(expectedTokens)) {
    throw new Error(`binding sequence mismatch: ${segment.segmentId}`);
  }
  const parts = [];
  let cursor = 0;
  for (const binding of segment.bindings) {
    const index = translation.indexOf(binding.token, cursor);
    if (index < 0 || translation.indexOf(binding.token, index + binding.token.length) >= 0) {
      throw new Error(`binding occurrence mismatch: ${segment.segmentId}`);
    }
    parts.push({ kind: "translated", value: translation.slice(cursor, index) });
    if (sha256(binding.value) !== binding.sha256) throw new Error(`binding payload drift: ${segment.segmentId}`);
    parts.push({
      kind: "frozen",
      value: binding.value,
      sourceStart: binding.start,
      sourceEnd: binding.end,
      sourceSha256: binding.sha256,
    });
    cursor = index + binding.token.length;
  }
  parts.push({ kind: "translated", value: translation.slice(cursor) });
  return parts;
}

function byteLength(value) {
  return Buffer.byteLength(value, "utf8");
}

function appendPart(state, part, sourceStart = null, sourceEnd = null) {
  const outputStart = byteLength(state.body);
  state.body += part.value;
  const outputEnd = byteLength(state.body);
  if (part.kind === "translated" || part.kind === "deterministic") {
    state.changedRanges.push({ kind: part.kind, outputStart, outputEnd });
  } else {
    const actual = state.body.slice(state.body.length - part.value.length);
    if (actual !== part.value || sha256(actual) !== part.sourceSha256) throw new Error("frozen Markdown chunk changed during splicing");
    state.frozenChunks.push({
      sourceStart: sourceStart ?? part.sourceStart,
      sourceEnd: sourceEnd ?? part.sourceEnd,
      outputStart,
      outputEnd,
      sha256: part.sourceSha256,
    });
  }
}

function validateDeterministicReplacements(inventory, replacements) {
  if (!Array.isArray(replacements) || replacements.length !== inventory.excludedOwners.length) {
    throw new Error("deterministic Markdown replacement coverage mismatch");
  }
  return replacements.map((replacement, index) => {
    exactKeys(replacement, ["exclusionId", "sourceSha256", "replacement"], "deterministic Markdown replacement");
    const expected = inventory.excludedOwners[index];
    if (replacement.exclusionId !== expected.exclusionId || replacement.sourceSha256 !== expected.sourceSha256) {
      throw new Error(`deterministic Markdown replacement mismatch at ${index}`);
    }
    if (typeof replacement.replacement !== "string" || !replacement.replacement.trim()) {
      throw new Error(`empty deterministic Markdown replacement: ${expected.exclusionId}`);
    }
    validateSource(replacement.replacement);
    return replacement;
  });
}

function constructBody(inventory, records, replacements) {
  const state = { body: "", changedRanges: [], frozenChunks: [] };
  const edits = [
    ...inventory.segments.map((segment, index) => ({ type: "segment", start: segment.start, end: segment.end, segment, record: records[index] })),
    ...inventory.excludedOwners.map((exclusion, index) => ({ type: "excluded", start: exclusion.start, end: exclusion.end, exclusion, replacement: replacements[index] })),
  ].sort((left, right) => left.start - right.start);
  let cursor = 0;
  for (const edit of edits) {
    if (edit.start < cursor) throw new Error("overlapping Markdown reconstruction edits");
    const outside = inventory.source.slice(cursor, edit.start);
    appendPart(state, { kind: "frozen", value: outside, sourceSha256: sha256(outside) }, cursor, edit.start);
    if (edit.type === "segment") {
      for (const part of restoreParts(edit.segment, edit.record.translation)) appendPart(state, part);
    } else {
      appendPart(state, { kind: "deterministic", value: edit.replacement.replacement });
    }
    cursor = edit.end;
  }
  const tail = inventory.source.slice(cursor);
  appendPart(state, { kind: "frozen", value: tail, sourceSha256: sha256(tail) }, cursor, inventory.source.length);
  return state;
}

export function reconstructMarkdown(inventory, records, options = { deterministicReplacements: [] }) {
  const canonicalInventory = validateInventory(inventory);
  validateRecords(canonicalInventory, records);
  exactKeys(options, ["deterministicReplacements"], "Markdown reconstruction options");
  const replacements = validateDeterministicReplacements(canonicalInventory, options.deterministicReplacements);
  const state = constructBody(canonicalInventory, records, replacements);
  const identityRecords = canonicalInventory.segments.map((segment) => ({
    document: segment.document,
    segmentId: segment.segmentId,
    sourceSha256: segment.sourceSha256,
    translation: segment.maskedSource,
  }));
  const expectedBody = constructBody(canonicalInventory, identityRecords, replacements).body;

  const projection = structuralProjection(state.body);
  if (!canonicalBytes(projection).equals(canonicalBytes(structuralProjection(expectedBody)))) {
    throw new Error(`Markdown structural projection changed: ${canonicalInventory.document}`);
  }
  const result = {
    document: canonicalInventory.document,
    sourceSha256: canonicalInventory.sourceSha256,
    body: state.body,
    bodySha256: sha256(Buffer.from(state.body)),
    changedRanges: state.changedRanges.filter((range) => range.outputStart !== range.outputEnd),
    frozenChunks: state.frozenChunks,
    frozenChunksSha256: sha256(canonicalBytes(state.frozenChunks)),
    structuralProjection: projection,
  };
  verifyFrozenByteProof(canonicalInventory, result);
  return result;
}

export function verifyFrozenByteProof(inventory, result) {
  const canonicalInventory = validateInventory(inventory);
  exactKeys(result, [
    "document", "sourceSha256", "body", "bodySha256", "changedRanges", "frozenChunks",
    "frozenChunksSha256", "structuralProjection",
  ], "Markdown reconstruction result");
  if (result.document !== canonicalInventory.document || result.sourceSha256 !== canonicalInventory.sourceSha256) {
    throw new Error("frozen Markdown proof has the wrong source identity");
  }
  const bodyBytes = Buffer.from(result.body);
  if (sha256(bodyBytes) !== result.bodySha256) throw new Error("frozen Markdown proof has a stale body hash");
  if (sha256(canonicalBytes(result.frozenChunks)) !== result.frozenChunksSha256) {
    throw new Error("frozen Markdown proof manifest hash mismatch");
  }
  for (const chunk of result.frozenChunks) {
    exactKeys(chunk, ["sourceStart", "sourceEnd", "outputStart", "outputEnd", "sha256"], "frozen Markdown chunk");
    if (![chunk.sourceStart, chunk.sourceEnd, chunk.outputStart, chunk.outputEnd].every(Number.isInteger)) {
      throw new Error("frozen Markdown chunk lacks exact offsets");
    }
    const sourceValue = canonicalInventory.source.slice(chunk.sourceStart, chunk.sourceEnd);
    const outputValue = bodyBytes.subarray(chunk.outputStart, chunk.outputEnd);
    if (sha256(sourceValue) !== chunk.sha256 || sha256(outputValue) !== chunk.sha256) {
      throw new Error("frozen Markdown byte proof failed");
    }
  }
  const coverage = [
    ...result.frozenChunks.map((chunk) => ({ start: chunk.outputStart, end: chunk.outputEnd })),
    ...result.changedRanges.map((range) => ({ start: range.outputStart, end: range.outputEnd })),
  ].filter((range) => range.start !== range.end).sort((left, right) => left.start - right.start);
  let cursor = 0;
  for (const range of coverage) {
    if (!Number.isInteger(range.start) || !Number.isInteger(range.end) || range.start !== cursor || range.end < range.start) {
      throw new Error("frozen Markdown allowlist has a gap or overlap");
    }
    cursor = range.end;
  }
  if (cursor !== bodyBytes.length) throw new Error("frozen Markdown allowlist does not cover the output");
  return true;
}

export function preflightMarkdown(inventory, options = {}) {
  const records = inventory.segments.map((segment) => ({
    document: segment.document,
    segmentId: segment.segmentId,
    sourceSha256: segment.sourceSha256,
    translation: segment.maskedSource,
  }));
  const deterministicReplacements = options.deterministicReplacements
    || inventory.excludedOwners.map((owner) => ({
      exclusionId: owner.exclusionId,
      sourceSha256: owner.sourceSha256,
      replacement: inventory.source.slice(owner.start, owner.end),
    }));
  const result = reconstructMarkdown(inventory, records, { deterministicReplacements });
  if (!options.deterministicReplacements && (result.body !== inventory.source || result.bodySha256 !== inventory.sourceSha256)) {
    throw new Error(`Markdown identity reconstruction failed: ${inventory.document}`);
  }
  return result;
}
