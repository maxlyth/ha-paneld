import { mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const toolDirectory = dirname(fileURLToPath(import.meta.url));
const packageLockPath = resolve(toolDirectory, "package-lock.json");
const defaultOutputPath = resolve(toolDirectory, "build/profile-editor-runtime.cdx.json");

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function compare(left, right) {
  return left < right ? -1 : left > right ? 1 : 0;
}

function packageNameFromLockPath(path) {
  const marker = "node_modules/";
  const start = path.lastIndexOf(marker);
  assert(start >= 0, `unexpected package-lock path: ${path}`);
  return path.slice(start + marker.length);
}

function packageUrl(name, version) {
  if (name.startsWith("@")) {
    const slash = name.indexOf("/");
    assert(slash > 1 && slash < name.length - 1, `invalid scoped npm package name: ${name}`);
    return `pkg:npm/${encodeURIComponent(name.slice(0, slash))}/${encodeURIComponent(name.slice(slash + 1))}@${version}`;
  }
  return `pkg:npm/${encodeURIComponent(name)}@${version}`;
}

function integrityHash(integrity, path) {
  assert(integrity?.startsWith("sha512-"), `${path} is missing a sha512 package-lock integrity`);
  const value = Buffer.from(integrity.slice("sha512-".length), "base64");
  assert(value.length === 64, `${path} has an invalid sha512 package-lock integrity`);
  return value.toString("hex");
}

function resolveLockedDependency(packages, sourcePath, dependencyName) {
  let cursor = sourcePath;
  while (cursor) {
    const nested = `${cursor}/node_modules/${dependencyName}`;
    if (packages[nested]) return nested;
    const parent = cursor.lastIndexOf("/node_modules/");
    cursor = parent < 0 ? "" : cursor.slice(0, parent);
  }
  const root = `node_modules/${dependencyName}`;
  return packages[root] ? root : null;
}

export async function createProfileEditorSbom() {
  const packageLock = JSON.parse(await readFile(packageLockPath, "utf8"));
  const packages = packageLock.packages || {};
  const root = packages[""];
  assert(packageLock.lockfileVersion === 3, "package-lock.json must use lockfileVersion 3");
  assert(root?.name && root?.version, "package-lock.json is missing root package identity");

  const runtimeEntries = Object.entries(packages)
    .filter(([path, locked]) => path && !locked.dev && !locked.link)
    .sort(([left], [right]) => compare(left, right));
  const referenceByPath = new Map();
  const componentByReference = new Map();

  for (const [path, locked] of runtimeEntries) {
    const name = packageNameFromLockPath(path);
    assert(locked.version, `${path} is missing a locked version`);
    assert(locked.resolved?.startsWith("https://registry.npmjs.org/"), `${path} is not locked to the npm registry`);
    const reference = packageUrl(name, locked.version);
    referenceByPath.set(path, reference);
    if (!componentByReference.has(reference)) {
      const slash = name.startsWith("@") ? name.indexOf("/") : -1;
      componentByReference.set(reference, {
        type: "library",
        ...(slash > 0 ? { group: name.slice(0, slash) } : {}),
        name: slash > 0 ? name.slice(slash + 1) : name,
        version: locked.version,
        hashes: [{ alg: "SHA-512", content: integrityHash(locked.integrity, path) }],
        purl: reference,
        "bom-ref": reference,
        externalReferences: [{ type: "distribution", url: locked.resolved }]
      });
    } else {
      const existing = componentByReference.get(reference);
      assert(existing.hashes[0].content === integrityHash(locked.integrity, path), `${reference} resolves to inconsistent package contents`);
      assert(existing.externalReferences[0].url === locked.resolved, `${reference} resolves to inconsistent registry URLs`);
    }
  }

  const rootReference = `urn:ha-paneld:embedded-profile-editor:${root.version}`;
  const dependencyMap = new Map([[rootReference, new Set()]]);
  for (const [name] of Object.entries(root.dependencies || {})) {
    const path = resolveLockedDependency(packages, "", name);
    assert(path && referenceByPath.has(path), `root runtime dependency ${name} is missing from the locked runtime graph`);
    dependencyMap.get(rootReference).add(referenceByPath.get(path));
  }

  for (const [path, locked] of runtimeEntries) {
    const reference = referenceByPath.get(path);
    if (!dependencyMap.has(reference)) dependencyMap.set(reference, new Set());
    for (const [name] of Object.entries(locked.dependencies || {})) {
      const dependencyPath = resolveLockedDependency(packages, path, name);
      assert(dependencyPath, `${path} dependency ${name} is missing from package-lock.json`);
      const dependencyReference = referenceByPath.get(dependencyPath);
      assert(dependencyReference, `${path} dependency ${name} is build-only but required by the runtime graph`);
      dependencyMap.get(reference).add(dependencyReference);
    }
  }

  return {
    $schema: "http://cyclonedx.org/schema/bom-1.6.schema.json",
    bomFormat: "CycloneDX",
    specVersion: "1.6",
    version: 1,
    metadata: {
      component: {
        type: "application",
        name: root.name,
        version: root.version,
        "bom-ref": rootReference,
        properties: [
          { name: "ha-paneld:embedded-path", value: "app/src/main/assets/vendor/profile-editor/codemirror.js" },
          { name: "ha-paneld:inventory-scope", value: "Integrity-locked npm runtime dependency graph used to build the embedded profile editor; build-only packages are excluded." }
        ]
      }
    },
    components: [...componentByReference.values()].sort((left, right) => compare(left["bom-ref"], right["bom-ref"])),
    dependencies: [...dependencyMap.entries()]
      .map(([reference, dependencies]) => ({ ref: reference, dependsOn: [...dependencies].sort() }))
      .sort((left, right) => compare(left.ref, right.ref))
  };
}

function outputPathFromArguments(arguments_) {
  if (arguments_.length === 0) return defaultOutputPath;
  assert(arguments_.length === 2 && arguments_[0] === "--output" && arguments_[1], "usage: npm run sbom -- [--output PATH]");
  return resolve(process.cwd(), arguments_[1]);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const outputPath = outputPathFromArguments(process.argv.slice(2));
  const sbom = await createProfileEditorSbom();
  await mkdir(dirname(outputPath), { recursive: true });
  await writeFile(outputPath, `${JSON.stringify(sbom, null, 2)}\n`);
  console.log(`profile editor runtime SBOM: ${sbom.components.length} components -> ${outputPath}`);
}
