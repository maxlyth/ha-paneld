import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";
import test from "node:test";

import { main, parseArguments } from "../cli.mjs";
import { buildSourceManifest, canonicalJson } from "../lib/contract.mjs";

function command(repository, args) {
  return execFileSync(args[0], args.slice(1), { cwd: repository, encoding: "utf8" }).trim();
}

function exportFixture() {
  const temporary = fs.mkdtempSync(path.join(os.tmpdir(), "docs-i18n-cli-"));
  const repository = path.join(temporary, "repository");
  fs.mkdirSync(repository);
  command(repository, ["git", "init", "-q"]);
  command(repository, ["git", "config", "user.email", "test@example.invalid"]);
  command(repository, ["git", "config", "user.name", "Test"]);
  fs.writeFileSync(path.join(repository, "README.md"), `# Read me

<!-- docs-i18n-language-picker:start -->
**English** · [Deutsch](docs/de/README.md) · [Français](docs/fr/README.md) · [Italiano](docs/it/README.md) · [Español](docs/es/README.md) · [简体中文](docs/zh-Hans/README.md)
<!-- docs-i18n-language-picker:end -->

Private plan test.
`);
  command(repository, ["git", "add", "README.md"]);
  command(repository, ["git", "commit", "-qm", "fixture"]);
  const sourceRevision = command(repository, ["git", "rev-parse", "HEAD"]);
  const manifest = buildSourceManifest({ repository, sourceRevision, documents: ["README.md"] });
  const manifestPath = path.join(temporary, "manifest.json");
  fs.writeFileSync(manifestPath, canonicalJson(manifest));
  return { manifestPath, repository, temporary };
}

function exportArguments(current, output) {
  return [
    "export-plan",
    "--repository", current.repository,
    "--manifest", current.manifestPath,
    "--output", output,
  ];
}

test("CLI accepts only the fixed provider-neutral interfaces", () => {
  assert.deepEqual(parseArguments([
    "validate", "--repository", "../..", "--manifest", "docs/i18n/manifest.json",
  ]), {
    command: "validate",
    options: { repository: "../..", manifest: "docs/i18n/manifest.json" },
  });
  assert.throws(() => parseArguments(["generate", "--provider", "remote"]), /unknown command/);
  assert.throws(() => parseArguments([
    "validate", "--repository", "../..", "--manifest", "x", "--token", "secret",
  ]), /unsupported option/);
  assert.throws(() => parseArguments([
    "validate", "--repository", "../..", "--repository", ".", "--manifest", "x",
  ]), /duplicate option/);
});

test("CLI plan and apply require exact explicit inputs", () => {
  assert.throws(() => parseArguments(["plan", "--repository", "."]), /missing --source-revision/);
  assert.throws(() => parseArguments([
    "apply", "--repository", ".", "--manifest", "manifest.json", "--locale", "de",
  ]), /missing --results/);
  assert.deepEqual(parseArguments([
    "export-plan", "--repository", ".", "--manifest", "manifest.json", "--output", "/private/plan.json",
  ]).command, "export-plan");
});

test("export-plan creates a private plan exclusively outside the repository", () => {
  const current = exportFixture();
  const output = path.join(current.temporary, "private", "plan.json");
  assert.equal(main(exportArguments(current, output)), 0);
  const original = fs.readFileSync(output, "utf8");
  assert.match(original, /"maskedSource"/);
  assert.throws(() => main(exportArguments(current, output)), /EEXIST/);
  assert.equal(fs.readFileSync(output, "utf8"), original);
});

test("export-plan rejects external-looking symlinks that physically enter the repository", () => {
  const current = exportFixture();
  const captured = path.join(current.repository, "captured");
  fs.mkdirSync(captured);

  const ancestorLink = path.join(current.temporary, "private-link");
  fs.symlinkSync(captured, ancestorLink);
  const ancestorOutput = path.join(ancestorLink, "plan.json");
  assert.throws(
    () => main(exportArguments(current, ancestorOutput)),
    /cannot use symlinked path components/,
  );
  assert.equal(fs.existsSync(path.join(captured, "plan.json")), false);

  const destinationLink = path.join(current.temporary, "destination-link.json");
  fs.symlinkSync(path.join(current.repository, "README.md"), destinationLink);
  const sourceBefore = fs.readFileSync(path.join(current.repository, "README.md"), "utf8");
  assert.throws(
    () => main(exportArguments(current, destinationLink)),
    /cannot use symlinked path components/,
  );
  assert.equal(fs.readFileSync(path.join(current.repository, "README.md"), "utf8"), sourceBefore);
});

test("export-plan rejects symlinked parents even when their destination remains private", () => {
  const current = exportFixture();
  const physicalPrivate = path.join(current.temporary, "physical-private");
  fs.mkdirSync(physicalPrivate);
  const privateLink = path.join(current.temporary, "private-link");
  fs.symlinkSync(physicalPrivate, privateLink);
  const output = path.join(privateLink, "plan.json");

  assert.throws(
    () => main(exportArguments(current, output)),
    /cannot use symlinked path components/,
  );
  assert.equal(fs.existsSync(path.join(physicalPrivate, "plan.json")), false);
});
