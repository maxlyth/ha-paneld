# Profile editor toolchain

This directory builds the vendored CodeMirror runtime used by the panel Profile editor. Android and Gradle builds consume the committed `app/src/main/assets/vendor/profile-editor/codemirror.js` bundle and do not run npm or download packages.

## Rebuild and verify

Use Node.js 20.18.1 and npm 10.8.2. The direct dependencies are exact pins and `package-lock.json` records registry URLs plus sha512 integrity for the complete dependency graph. The supported clean install deliberately disables package lifecycle scripts; esbuild loads its integrity-locked platform package directly, and the verification step executes esbuild so an unsupported or incomplete installation fails closed.

```bash
cd tools/profile-editor
npm ci --ignore-scripts --audit=false --fund=false
npm run build
npm run check
```

`npm run check` validates direct pins, every lockfile package integrity, the exact runtime package inventory in `NOTICE.txt`, the collected upstream MIT copyright notices in `LICENSE.txt`, and a byte-for-byte rebuild of the committed browser bundle. When dependencies change, review the new lockfile graph and upstream license files before rebuilding and committing `package.json`, `package-lock.json`, `LICENSE.txt`, `NOTICE.txt`, and `codemirror.js` together.
