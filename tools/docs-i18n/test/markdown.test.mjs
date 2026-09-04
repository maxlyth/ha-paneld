import assert from "node:assert/strict";
import test from "node:test";

import {
  PARSER_VERSIONS,
  headingAnchors,
  htmlAnchors,
  inventoryMarkdown,
  linkDestinations,
  preflightMarkdown,
  reconstructMarkdown,
  rewriteLinkDestinations,
  sha256,
  structuralProjection,
  validateInventory,
  verifyFrozenByteProof,
} from "../lib/markdown.mjs";

function records(inventory, change = (source) => source) {
  return inventory.segments.map((segment) => ({
    document: segment.document,
    segmentId: segment.segmentId,
    sourceSha256: segment.sourceSha256,
    translation: change(segment.maskedSource, segment),
  }));
}

function segmentContaining(inventory, text) {
  const segment = inventory.segments.find((candidate) => candidate.maskedSource.includes(text));
  assert.ok(segment, `missing segment containing ${text}`);
  return segment;
}

test("uses the exact production parser pins and identity-preflights losslessly", () => {
  assert.deepEqual(PARSER_VERSIONS, {
    unified: "11.0.5",
    remarkParse: "11.0.0",
    remarkGfm: "4.0.1",
    parse5: "7.3.0",
  });
  const source = "# Heading\n\nParagraph with `code` and **weight**.\n";
  const inventory = inventoryMarkdown("docs/example.md", source);
  assert.deepEqual(validateInventory(inventory), inventory);
  assert.equal(preflightMarkdown(inventory).body, source);
});

test("stable IDs exclude offsets and survive an earlier byte insertion", () => {
  const before = inventoryMarkdown("docs/example.md", "# Heading\n\nKeep this paragraph.\n");
  const after = inventoryMarkdown("docs/example.md", "Intro before the heading.\n\n# Heading\n\nKeep this paragraph.\n");
  const oldSegment = segmentContaining(before, "Keep this paragraph.");
  const newSegment = segmentContaining(after, "Keep this paragraph.");
  assert.equal(newSegment.segmentId, oldSegment.segmentId);
  assert.ok(!newSegment.segmentId.includes(`:${newSegment.start}:`));
});

test("stable ancestry includes frozen visible inline code and does not globally renumber sections", () => {
  const source = "# Option `A`\n\nSame child.\n\n# Option `B`\n\nSame child.\n";
  const inventory = inventoryMarkdown("docs/options.md", source);
  const children = inventory.segments.filter((segment) => segment.maskedSource === "Same child.");
  assert.equal(children.length, 2);
  assert.notEqual(children[0].segmentId, children[1].segmentId);
  const inserted = inventoryMarkdown("docs/options.md", "# Option `X`\n\nSame child.\n\n" + source);
  const after = inserted.segments.filter((segment) => segment.maskedSource === "Same child.");
  assert.deepEqual(after.slice(1).map((segment) => segment.segmentId), children.map((segment) => segment.segmentId));
});

test("rejects newly injected Markdown structure", () => {
  const inventory = inventoryMarkdown("docs/example.md", "Read this safely.\n");
  const malicious = records(inventory, (source) => `${source} [malicious](https://attacker.invalid)`);
  assert.throws(() => reconstructMarkdown(inventory, malicious), /HTML parse error|structural projection changed/);
});

test("rejects removal of protected link structure", () => {
  const inventory = inventoryMarkdown("docs/example.md", "Read [the guide](guide.md).\n");
  const changed = records(inventory);
  changed[0].translation = changed[0].translation.replace(/<x id="m\d{4}"\/>/, "");
  assert.throws(() => reconstructMarkdown(inventory, changed), /binding sequence mismatch/);
});

test("protects and safely rewrites a balanced-parenthesis destination", () => {
  const source = "See [image](asset_(dark).png).\n";
  const inventory = inventoryMarkdown("docs/example.md", source);
  const segment = segmentContaining(inventory, "image");
  assert.ok(segment.bindings.some((binding) => binding.value.includes("asset_(dark).png")));
  assert.equal(preflightMarkdown(inventory).body, source);
  const rewritten = rewriteLinkDestinations(source, ({ url }) => `../${url}`);
  assert.equal(rewritten.body, "See [image](../asset_(dark).png).\n");
  assert.throws(
    () => rewriteLinkDestinations(source, () => "broken destination) [injected](https://bad.invalid"),
    /topology changed|did not reparse|structure changed/,
  );
});

test("parse5 protects quoted angle brackets and rejects HTML injection", () => {
  const source = "Use <span title=\"a > b\">visible</span>.\n";
  const inventory = inventoryMarkdown("docs/example.md", source);
  assert.equal(preflightMarkdown(inventory).body, source);
  const combined = inventory.segments.map((segment) => segment.maskedSource).join("\n");
  assert.ok(!combined.includes(' b">'));
  const malicious = records(inventory, (masked) => masked.replace("visible", "<script>bad()</script>"));
  assert.throws(() => reconstructMarkdown(inventory, malicious), /HTML parse error|structural projection changed/);
});

test("preserves all GitHub alert markers and rejects unknown directives", () => {
  for (const kind of ["NOTE", "TIP", "IMPORTANT", "WARNING", "CAUTION"]) {
    const source = `> [!${kind}]\n> Keep safe.\n`;
    const inventory = inventoryMarkdown("docs/alert.md", source);
    assert.equal(inventory.segments[0].githubAlert, kind);
    assert.ok(!inventory.segments[0].maskedSource.includes(`[!${kind}]`));
    const result = reconstructMarkdown(inventory, records(inventory, (masked) => masked.replace("Keep safe.", "Sicher bleiben.")));
    assert.ok(result.body.includes(`[!${kind}]`));
  }
  assert.throws(
    () => inventoryMarkdown("docs/alert.md", "> [!DANGER]\n> Unsafe.\n"),
    /unsupported GitHub alert directive/,
  );
});

test("preserves task-list marker bytes while translating labels", () => {
  for (const marker of ["[ ]", "[x]", "[X]"]) {
    const source = `- ${marker} Check the panel\n`;
    const inventory = inventoryMarkdown("docs/tasks.md", source);
    const result = reconstructMarkdown(inventory, records(inventory, (masked) => masked.replace("Check the panel", "Panel prüfen")));
    assert.ok(result.body.startsWith(`- ${marker} `));
    assert.equal(structuralProjection(result.body).children[0].children[0].checked, marker !== "[ ]");
  }
});

test("protects the complete footnote reference graph", () => {
  const source = "Text[^safety].\n\n[^safety]: Explanatory text.\n";
  const inventory = inventoryMarkdown("docs/footnote.md", source);
  assert.ok(inventory.segments.every((segment) => !segment.maskedSource.includes("[^safety]")));
  const result = reconstructMarkdown(inventory, records(inventory, (masked) => masked.replace("Text", "Texte").replace("Explanatory text", "Explication")));
  assert.match(result.body, /Text.*\[\^safety\]/);
  assert.equal(preflightMarkdown(inventory).body, source);
});

test("freezes shortcut and collapsed reference identifiers but translates full labels", () => {
  const source = "See [guide], [manual][], and [visible label][stable].\n\n[guide]: guide.md\n[manual]: manual.md\n[stable]: stable.md\n";
  const inventory = inventoryMarkdown("docs/references.md", source);
  const segment = segmentContaining(inventory, "visible label");
  assert.ok(!segment.maskedSource.includes("[guide]"));
  assert.ok(!segment.maskedSource.includes("[manual][]"));
  const result = reconstructMarkdown(inventory, records(inventory, (masked) => masked.replace("visible label", "sichtbare Bezeichnung")));
  assert.ok(result.body.includes("[guide], [manual][], and [sichtbare Bezeichnung][stable]"));
});

test("preserves Setext heading underline bytes and heading depth", () => {
  const source = "Visible title\n=============\n";
  const inventory = inventoryMarkdown("docs/setext.md", source);
  const result = reconstructMarkdown(inventory, records(inventory, (masked) => masked.replace("Visible title", "Sichtbarer Titel")));
  assert.equal(result.body, "Sichtbarer Titel\n=============\n");
  const changed = records(inventory);
  changed[0].translation = changed[0].translation.replace(/<x id="m\d{4}"\/>/, "");
  assert.throws(() => reconstructMarkdown(inventory, changed), /binding sequence mismatch/);
});

test("freezes HTML comments and rejects injected comments", () => {
  const source = "Visible prose.\n\n<!-- source-checkout-only -->\n";
  const inventory = inventoryMarkdown("docs/comments.md", source);
  assert.ok(inventory.segments.every((segment) => !segment.maskedSource.includes("source-checkout-only")));
  assert.ok(reconstructMarkdown(inventory, records(inventory, (masked) => masked.replace("Visible prose", "Sichtbarer Text"))).body.includes("<!-- source-checkout-only -->"));
  assert.throws(
    () => reconstructMarkdown(inventory, records(inventory, (masked) => `${masked}\n<!-- injected -->`)),
    /structural projection changed/,
  );
});

test("binds exact owner exclusions and proves deterministic shell changes separately", () => {
  const source = "# Guide\n\nEnglish · [Deutsch](de/README.md)\n\nTranslate this paragraph.\n";
  const plain = inventoryMarkdown("README.md", source);
  const shell = segmentContaining(plain, "English");
  const inventory = inventoryMarkdown("README.md", source, {
    excludedRanges: [{ start: shell.start, end: shell.end, label: "language-selector" }],
  });
  const excluded = inventory.excludedOwners[0];
  const result = reconstructMarkdown(
    inventory,
    records(inventory, (masked) => masked.replace("Translate this paragraph.", "Diesen Absatz übersetzen.")),
    {
      deterministicReplacements: [{
        exclusionId: excluded.exclusionId,
        sourceSha256: excluded.sourceSha256,
        replacement: "[English](../../README.md) · **Deutsch**",
      }],
    },
  );
  assert.ok(result.body.includes("[English](../../README.md) · **Deutsch**"));
  assert.ok(result.changedRanges.some((range) => range.kind === "deterministic"));
  assert.ok(result.changedRanges.some((range) => range.kind === "translated"));
  assert.equal(result.frozenChunksSha256.length, 64);
  assert.equal(verifyFrozenByteProof(inventory, result), true);
  const tampered = structuredClone(result);
  tampered.body = tampered.body.replace("# Guide", "! Guide");
  tampered.bodySha256 = sha256(tampered.body);
  assert.throws(() => verifyFrozenByteProof(inventory, tampered), /byte proof failed/);
});

test("discovers Markdown and HTML link ranges without regex truncation", () => {
  const source = "[Page](asset_(dark).png)\n\n<img src=\"image.png\" srcset=\"small.png 1x, large.png 2x\">\n";
  assert.deepEqual(linkDestinations(source).map(({ kind, url }) => ({ kind, url })), [
    { kind: "link", url: "asset_(dark).png" },
    { kind: "html-src", url: "image.png" },
    { kind: "html-srcset", url: "small.png" },
    { kind: "html-srcset", url: "large.png" },
  ]);
  const rewritten = rewriteLinkDestinations(source, ({ url }) => `../${url}`);
  assert.ok(rewritten.body.includes('src="../image.png"'));
  assert.ok(rewritten.body.includes('srcset="../small.png 1x, ../large.png 2x"'));
});

test("derives exact GitHub-compatible anchors including duplicate suffixes", () => {
  const source = "# Panels & support status\n\n## Über panel!\n\n## Über panel!\n";
  assert.deepEqual(headingAnchors(source), [
    { anchor: "panels--support-status", start: 0, end: 25 },
    { anchor: "über-panel", start: 27, end: 41 },
    { anchor: "über-panel-1", start: 43, end: 57 },
  ]);
});

test("discovers exact raw HTML anchor ids but ignores code and text lookalikes", () => {
  const source = [
    '<a id="stable-anchor"></a>',
    '',
    '<div><a class="target" id="nested-anchor">Target</a></div>',
    '',
    '`<a id="inline-code-fake"></a>`',
    '',
    '```html',
    '<a id="fenced-code-fake"></a>',
    '```',
    '',
    '\\<a id="escaped-text-fake"></a>',
    '',
    '<!-- <a id="comment-fake"></a> -->',
    '',
  ].join("\n");
  const anchors = htmlAnchors(source);
  assert.deepEqual(anchors.map(({ anchor }) => anchor), ["stable-anchor", "nested-anchor"]);
  assert.equal(source.slice(anchors[0].start, anchors[0].end), '<a id="stable-anchor">');
  assert.equal(source.slice(anchors[1].start, anchors[1].end), '<a class="target" id="nested-anchor">Target</a>');
});

test("rejects noncanonical sources, reserved tokens, forged inventory, and incomplete results", () => {
  assert.throws(() => inventoryMarkdown("../outside.md", "Text.\n"), /unsafe document path/);
  assert.throws(() => inventoryMarkdown("docs/x.md", "Text.\r\n"), /LF line endings/);
  assert.throws(() => inventoryMarkdown("docs/x.md", '<x id="m0001"/>\n'), /reserved binding token/);
  const inventory = inventoryMarkdown("docs/x.md", "Text.\n");
  const forged = structuredClone(inventory);
  forged.segments[0].segmentId += "-forged";
  assert.throws(() => validateInventory(forged), /canonical rebuild/);
  assert.throws(() => reconstructMarkdown(inventory, []), /coverage mismatch/);
  assert.equal(sha256("Text.\n"), inventory.sourceSha256);
});

test("rejects duplicate exclusion labels even for byte-identical owners", () => {
  const source = "Same.\n\nSame.\n";
  const plain = inventoryMarkdown("docs/x.md", source);
  assert.throws(() => inventoryMarkdown("docs/x.md", source, {
    excludedRanges: plain.segments.map((segment) => ({ start: segment.start, end: segment.end, label: "shell" })),
  }), /duplicate Markdown exclusion label/);
});
