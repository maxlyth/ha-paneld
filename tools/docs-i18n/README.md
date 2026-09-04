# Documentation localization validator

This tool creates and validates source-bound, provider-neutral localization plans for the maintained Markdown documentation. It translates nothing and has no network or repository-write capability in CI.

Use Node.js 20.18.1 and npm 10.8.2. Direct dependencies are exact pins; `package-lock.json` binds the complete registry dependency graph by integrity hash.

```sh
cd tools/docs-i18n
npm ci --ignore-scripts --audit=false --fund=false
npm test
npm run validate -- --repository ../.. --manifest ../../docs/i18n/manifest.json
```

The committed proof selects the authoritative root `README.md` and derives one localized copy for each supported locale. The manifest binds the exact Git source, parser versions, structural inventory, fixed packet limits, output paths and packet ownership. Locale receipts bind reviewed target hashes and contain no translation-provider identity.

`plan` writes a new manifest only when the canonical target does not already exist. `apply` accepts already reviewed, manifest-bound packet results and creates one locale output plus its receipt atomically without overwriting existing files. `validate` performs the read-only CI checks over the exact manifest, source, receipts, links, fragments and localized output trees.
