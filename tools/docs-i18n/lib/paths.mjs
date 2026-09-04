import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";

export const SUPPORTED_LOCALES = Object.freeze(["de", "es", "fr", "it", "zh-Hans"]);
export const EXACT_COMMIT_RE = /^[0-9a-f]{40}$/;
export const EXACT_SHA256_RE = /^[0-9a-f]{64}$/;

const LOCALIZED_SOURCE_RE = new RegExp(
  `^docs/(?:${SUPPORTED_LOCALES.map(escapeRegExp).join("|")})(?:/|$)`,
);

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

export function git(repository, args, options = {}) {
  return execFileSync("git", ["-C", repository, ...args], {
    encoding: options.encoding === null ? null : "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  });
}

export function normalizeRepository(repository) {
  if (typeof repository !== "string" || !repository) {
    throw new TypeError("repository must be a non-empty path");
  }
  const requested = fs.realpathSync(repository);
  const top = fs.realpathSync(git(requested, ["rev-parse", "--show-toplevel"]).trim());
  if (requested !== top) {
    throw new Error("repository must be the exact Git worktree root");
  }
  return top;
}

export function normalizeSourcePath(relative) {
  if (
    typeof relative !== "string" ||
    !relative ||
    relative.includes("\\") ||
    relative.includes("\0") ||
    path.posix.isAbsolute(relative)
  ) {
    throw new Error(`unsafe source path: ${String(relative)}`);
  }
  const normalized = path.posix.normalize(relative);
  if (normalized !== relative || normalized === ".." || normalized.startsWith("../")) {
    throw new Error(`unsafe source path: ${relative}`);
  }
  if (!/^[A-Za-z0-9._/-]+$/.test(relative)) {
    throw new Error(`source path contains unsupported characters: ${relative}`);
  }
  if (relative !== "README.md" && !/^docs\/.+\.md$/.test(relative)) {
    throw new Error(`source path is outside the admitted Markdown roots: ${relative}`);
  }
  if (relative === "docs/i18n/manifest.json" || relative.startsWith("docs/i18n/")) {
    throw new Error(`translation metadata cannot be a source document: ${relative}`);
  }
  if (LOCALIZED_SOURCE_RE.test(relative)) {
    throw new Error(`localized output cannot be a source document: ${relative}`);
  }
  return relative;
}

export function normalizeLocale(locale) {
  if (!SUPPORTED_LOCALES.includes(locale)) {
    throw new Error(`unsupported locale: ${String(locale)}`);
  }
  return locale;
}

export function localizedOutputPath(locale, sourcePath) {
  normalizeLocale(locale);
  const source = normalizeSourcePath(sourcePath);
  if (source === "README.md") return `docs/${locale}/README.md`;
  return `docs/${locale}/${source.slice("docs/".length)}`;
}

export function localeReceiptPath(locale) {
  normalizeLocale(locale);
  return `docs/i18n/locales/${locale}.json`;
}

export function bindSourceRevision(repository, sourceRevision, head = "HEAD") {
  const root = normalizeRepository(repository);
  if (!EXACT_COMMIT_RE.test(sourceRevision)) {
    throw new Error("sourceRevision must be an exact lowercase 40-hex commit ID");
  }
  const resolved = git(root, ["rev-parse", "--verify", `${sourceRevision}^{commit}`]).trim();
  if (resolved !== sourceRevision) throw new Error("sourceRevision did not resolve exactly");
  const resolvedHead = git(root, ["rev-parse", "--verify", `${head}^{commit}`]).trim();
  try {
    git(root, ["merge-base", "--is-ancestor", sourceRevision, resolvedHead]);
  } catch {
    throw new Error("sourceRevision is not an ancestor of the selected worktree revision");
  }
  return { repository: root, sourceRevision, headRevision: resolvedHead };
}

export function readTreeSource(repository, sourceRevision, sourcePath) {
  const root = normalizeRepository(repository);
  const relative = normalizeSourcePath(sourcePath);
  const entry = git(root, ["ls-tree", sourceRevision, "--", relative]).trim();
  if (!entry) throw new Error(`source document is absent from sourceRevision: ${relative}`);
  const [mode, type] = entry.split(/\s+/, 2);
  if (mode === "120000" || type !== "blob") {
    throw new Error(`source document must be a regular Git blob: ${relative}`);
  }
  const bytes = git(root, ["show", `${sourceRevision}:${relative}`], { encoding: null });
  const decoded = bytes.toString("utf8");
  if (!Buffer.from(decoded, "utf8").equals(bytes) || decoded.includes("\r")) {
    throw new Error(`source document must be canonical UTF-8 with LF endings: ${relative}`);
  }
  return { path: relative, bytes, source: decoded };
}

export function assertCurrentSource(repository, sourceRevision, sourcePath, expectedSha256, sha256, head = "HEAD") {
  const root = normalizeRepository(repository);
  const bound = bindSourceRevision(root, sourceRevision, head);
  const tree = readTreeSource(root, sourceRevision, sourcePath);
  if (sha256(tree.bytes) !== expectedSha256) {
    throw new Error(`manifest source hash does not match sourceRevision: ${tree.path}`);
  }
  const headTree = readTreeSource(root, bound.headRevision, sourcePath);
  if (!headTree.bytes.equals(tree.bytes)) {
    throw new Error(`selected source differs between sourceRevision and selected HEAD: ${tree.path}`);
  }
  const currentPath = confinedWorkingPath(root, tree.path, { mustExist: true, allowFile: true });
  const current = fs.readFileSync(currentPath);
  if (!current.equals(tree.bytes)) {
    throw new Error(`working source differs from admitted sourceRevision: ${tree.path}`);
  }
  return tree;
}

export function confinedWorkingPath(repository, relative, options = {}) {
  const root = normalizeRepository(repository);
  if (
    typeof relative !== "string" ||
    !relative ||
    relative.includes("\\") ||
    relative.includes("\0") ||
    path.posix.isAbsolute(relative) ||
    path.posix.normalize(relative) !== relative ||
    relative === ".." ||
    relative.startsWith("../")
  ) {
    throw new Error(`unsafe repository path: ${String(relative)}`);
  }
  const target = path.join(root, ...relative.split("/"));
  if (target !== root && !target.startsWith(`${root}${path.sep}`)) {
    throw new Error(`repository path escaped the worktree: ${relative}`);
  }
  let cursor = root;
  for (const component of relative.split("/").slice(0, -1)) {
    cursor = path.join(cursor, component);
    if (fs.existsSync(cursor) && fs.lstatSync(cursor).isSymbolicLink()) {
      throw new Error(`symlinked path ancestor is forbidden: ${relative}`);
    }
  }
  if (fs.existsSync(target) && fs.lstatSync(target).isSymbolicLink()) {
    throw new Error(`symlinked target is forbidden: ${relative}`);
  }
  if (options.mustExist && !fs.existsSync(target)) {
    throw new Error(`repository path does not exist: ${relative}`);
  }
  if (options.allowFile && fs.existsSync(target) && !fs.lstatSync(target).isFile()) {
    throw new Error(`repository path is not a regular file: ${relative}`);
  }
  return target;
}

export function confinedOutputPath(repository, locale, sourcePath) {
  const relative = localizedOutputPath(locale, sourcePath);
  return {
    relative,
    absolute: confinedWorkingPath(repository, relative),
  };
}
