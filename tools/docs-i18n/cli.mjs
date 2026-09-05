#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";

import {
  applyLocaleReceipt,
  buildSourceManifest,
  buildTranslationPlan,
  canonicalJson,
  readCanonicalJson,
  validateRepository,
} from "./lib/contract.mjs";

const COMMANDS = new Set(["plan", "export-plan", "apply", "validate"]);

function usage() {
  return [
    "Usage:",
    "  node cli.mjs plan --repository ROOT --source-revision SHA --output docs/i18n/manifest.json",
    "  node cli.mjs export-plan --repository ROOT --manifest docs/i18n/manifest.json --output PRIVATE_FILE",
    "  node cli.mjs apply --repository ROOT --manifest docs/i18n/manifest.json --locale LOCALE --results FILE",
    "  node cli.mjs validate --repository ROOT --manifest docs/i18n/manifest.json",
  ].join("\n");
}

export function parseArguments(argv) {
  if (argv.length === 0 || argv.includes("--help") || argv.includes("-h")) {
    return { help: true };
  }
  const [command, ...tokens] = argv;
  if (!COMMANDS.has(command)) throw new Error(`unknown command: ${command}`);
  if (tokens.length % 2 !== 0) throw new Error("every option requires one value");
  const options = {};
  for (let index = 0; index < tokens.length; index += 2) {
    const option = tokens[index];
    const value = tokens[index + 1];
    if (!/^--[a-z-]+$/.test(option) || !value || value.startsWith("--")) {
      throw new Error(`invalid option/value pair: ${option} ${value ?? ""}`);
    }
    const key = option.slice(2);
    if (key in options) throw new Error(`duplicate option: ${option}`);
    options[key] = value;
  }
  const required = {
    plan: ["repository", "source-revision", "output"],
    "export-plan": ["repository", "manifest", "output"],
    apply: ["repository", "manifest", "locale", "results"],
    validate: ["repository", "manifest"],
  }[command];
  if (Object.keys(options).some((key) => !required.includes(key))) {
    throw new Error(`unsupported option for ${command}`);
  }
  for (const key of required) if (!(key in options)) throw new Error(`missing --${key}`);
  return { command, options };
}

function canonicalManifestPath(repository, requested) {
  const root = fs.realpathSync(repository);
  const resolved = path.resolve(requested);
  if (resolved !== path.join(root, "docs", "i18n", "manifest.json")) {
    throw new Error("manifest output must be docs/i18n/manifest.json below the repository root");
  }
  return { root, resolved };
}

function writeExclusive(file, content) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, content, { encoding: "utf8", flag: "wx", mode: 0o644 });
}

function isWithin(root, candidate) {
  return candidate === root || candidate.startsWith(`${root}${path.sep}`);
}

function physicalOutputPath(repositoryRoot, requested) {
  const output = path.resolve(requested);
  if (isWithin(repositoryRoot, output)) {
    throw new Error("expanded translation plans contain source text and must stay outside the public repository");
  }

  const parsed = path.parse(output);
  const components = path.relative(parsed.root, output).split(path.sep).filter(Boolean);
  let existing = parsed.root;
  let existingComponents = 0;
  for (const component of components) {
    const candidate = path.join(existing, component);
    try {
      const entry = fs.lstatSync(candidate);
      if (entry.isSymbolicLink()) {
        throw new Error(`private translation plan output cannot use symlinked path components: ${candidate}`);
      }
      existing = candidate;
      existingComponents += 1;
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
      break;
    }
  }

  const physicalExisting = fs.realpathSync(existing);
  const physicalOutput = path.join(physicalExisting, ...components.slice(existingComponents));
  if (isWithin(repositoryRoot, physicalOutput)) {
    throw new Error("expanded translation plans contain source text and must physically stay outside the public repository");
  }
  return output;
}

function writePrivatePlanExclusive(repositoryRoot, file, content) {
  const output = physicalOutputPath(repositoryRoot, file);
  fs.mkdirSync(path.dirname(output), { recursive: true });
  // Re-resolve after directory creation so the final parent is also covered by
  // the physical boundary immediately before the exclusive write.
  physicalOutputPath(repositoryRoot, output);
  fs.writeFileSync(output, content, { encoding: "utf8", flag: "wx", mode: 0o644 });
}

export function main(argv = process.argv.slice(2)) {
  const parsed = parseArguments(argv);
  if (parsed.help) {
    process.stdout.write(`${usage()}\n`);
    return 0;
  }
  const { command, options } = parsed;
  if (command === "plan") {
    const target = canonicalManifestPath(options.repository, options.output);
    const manifest = buildSourceManifest({
      repository: target.root,
      sourceRevision: options["source-revision"],
      documents: ["README.md"],
    });
    writeExclusive(target.resolved, canonicalJson(manifest));
    process.stdout.write(`documentation localization plan: ${manifest.documents[0].segments.length} segments, ${manifest.packets.length} packets\n`);
    return 0;
  }
  if (command === "export-plan") {
    const root = fs.realpathSync(options.repository);
    const output = physicalOutputPath(root, options.output);
    const manifest = readCanonicalJson(path.resolve(options.manifest));
    const plan = buildTranslationPlan(manifest, { repository: root });
    writePrivatePlanExclusive(root, output, canonicalJson(plan));
    process.stdout.write(`private translation plan: ${plan.packets.length} packets\n`);
    return 0;
  }
  if (command === "apply") {
    const manifest = readCanonicalJson(path.resolve(options.manifest));
    const results = readCanonicalJson(path.resolve(options.results));
    const receipt = applyLocaleReceipt({
      repository: options.repository,
      manifest,
      locale: options.locale,
      results,
    });
    process.stdout.write(`localized documentation applied: ${receipt.locale}, ${receipt.documents.length} document(s)\n`);
    return 0;
  }
  const manifest = validateRepository({
    repository: options.repository,
    manifestPath: path.resolve(options.manifest),
  });
  process.stdout.write(`localized documentation valid: ${manifest.documents.length} document(s), ${manifest.locales.length} locale(s)\n`);
  return 0;
}

const invoked = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;
if (invoked) {
  try {
    process.exitCode = main();
  } catch (error) {
    process.stderr.write(`docs-i18n: ${error.message}\n`);
    process.exitCode = 1;
  }
}
