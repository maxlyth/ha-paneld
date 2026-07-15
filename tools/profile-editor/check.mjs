import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { buildProfileEditor, bundlePath, licensePath, noticePath, toolDirectory } from "./bundle.mjs";

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

const packageJson = JSON.parse(await readFile(resolve(toolDirectory, "package.json"), "utf8"));
const packageLock = JSON.parse(await readFile(resolve(toolDirectory, "package-lock.json"), "utf8"));
const directDependencies = { ...packageJson.dependencies, ...packageJson.devDependencies };
const exactVersion = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?$/;

assert(packageLock.lockfileVersion === 3, "package-lock.json must use lockfileVersion 3");
assert(packageLock.packages?.[""]?.name === packageJson.name, "package-lock.json root package does not match package.json");
assert(/^npm@\d+\.\d+\.\d+$/.test(packageJson.packageManager), "packageManager must pin an exact npm version");
for (const group of ["dependencies", "devDependencies"]) {
  assert(JSON.stringify(packageLock.packages[""][group] || {}) === JSON.stringify(packageJson[group] || {}), `package-lock.json root ${group} do not match package.json`);
}

for (const [name, version] of Object.entries(directDependencies)) {
  assert(exactVersion.test(version), `direct dependency ${name} is not pinned exactly: ${version}`);
  const locked = packageLock.packages[`node_modules/${name}`];
  assert(locked?.version === version, `locked version for ${name} does not match package.json`);
  assert(locked.resolved?.startsWith("https://registry.npmjs.org/"), `${name} is not locked to the npm registry`);
  assert(locked.integrity?.startsWith("sha512-"), `${name} is missing a sha512 lockfile integrity`);
}

for (const [path, locked] of Object.entries(packageLock.packages)) {
  if (!path || locked.link) continue;
  assert(locked.version, `${path} is missing a locked version`);
  assert(locked.resolved?.startsWith("https://registry.npmjs.org/"), `${path} is not locked to the npm registry`);
  assert(locked.integrity?.startsWith("sha512-"), `${path} is missing a sha512 lockfile integrity`);
}

const runtimePackages = Object.entries(packageLock.packages)
  .filter(([path, locked]) => path.startsWith("node_modules/") && !locked.dev)
  .map(([path, locked]) => ({ name: path.slice("node_modules/".length), version: locked.version }))
  .sort((left, right) => left.name < right.name ? -1 : left.name > right.name ? 1 : 0);
const expectedNoticeLines = runtimePackages.map(({ name, version }) => `${name} ${version}`);
const notice = await readFile(noticePath, "utf8");
const actualNoticeLines = notice.split("\n").filter((line) => /^(?:@[^ ]+|[^@ ][^ ]*) \d+\.\d+\.\d+/.test(line));
assert(JSON.stringify(actualNoticeLines) === JSON.stringify(expectedNoticeLines), "NOTICE.txt package inventory does not match the locked runtime dependency set");

const collectedLicense = await readFile(licensePath, "utf8");
for (const { name } of runtimePackages) {
  const directory = resolve(toolDirectory, "node_modules", name);
  const metadata = JSON.parse(await readFile(resolve(directory, "package.json"), "utf8"));
  assert(metadata.license === "MIT", `${name} no longer declares the expected MIT license`);
  const upstreamLicense = await readFile(resolve(directory, "LICENSE"), "utf8");
  const copyrightLines = upstreamLicense.split("\n").filter((line) => line.startsWith("Copyright "));
  assert(copyrightLines.length > 0, `${name} license has no copyright notice`);
  for (const line of copyrightLines) assert(collectedLicense.includes(line), `LICENSE.txt is missing ${name} notice: ${line}`);
}
assert(collectedLicense.includes("Permission is hereby granted, free of charge"), "LICENSE.txt is missing the MIT permission grant");
assert(collectedLicense.includes('THE SOFTWARE IS PROVIDED "AS IS"'), "LICENSE.txt is missing the MIT warranty disclaimer");

const generated = await buildProfileEditor({ write: false });
const generatedBundle = generated.outputFiles?.find((file) => file.path === bundlePath);
assert(generatedBundle, "esbuild did not return the expected profile-editor bundle");
const committedBundle = await readFile(bundlePath);
assert(Buffer.compare(committedBundle, generatedBundle.contents) === 0, "committed codemirror.js is stale; run npm run build");

console.log(`profile editor verified: ${Object.keys(directDependencies).length} exact direct pins, ${Object.keys(packageLock.packages).length - 1} integrity-locked packages, ${runtimePackages.length} licensed runtime packages, deterministic bundle`);
