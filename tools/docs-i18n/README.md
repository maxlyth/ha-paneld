# Documentation localization validator

This tool creates and validates source-bound, provider-neutral localization plans for the maintained Markdown documentation. It translates nothing and has no network or repository-write capability in CI.

Use Node.js 20.18.1 and npm 10.8.2. Direct dependencies are exact pins; `package-lock.json` binds the complete registry dependency graph by integrity hash.

```sh
cd tools/docs-i18n
npm ci --ignore-scripts --audit=false --fund=false
npm test
npm run validate -- --repository ../.. --manifest ../../docs/i18n/manifest.json
```

The committed proof selects the fixed Tier-1 prefix `README.md` followed by `docs/provisioning.md` and derives one localized copy of each document for every supported locale. The manifest binds the exact Git source, parser versions, structural inventory, fixed packet limits, output paths and packet ownership. Packets never span document boundaries, so adding the next document does not mix its source into an existing document's translation packet. The canonical `docs/i18n/consequential-segments.json` policy binds the complete provisioning inventory count and source hash, and identifies the subset whose safety or security meaning requires English fallback. README segments are explicitly grandfathered under the ordinary review state. Locale receipts bind reviewed target hashes and contain no translation-provider identity.

`plan` writes a new manifest only when the canonical target does not already exist and the committed policy matches the selected provisioning source. The manifest contains hashes and structural commitments, not a second copy of the source prose. `export-plan` reconstructs its exact masked packet payload for a separately managed file outside the public repository. `apply` requires each manifest-bound packet result to use its declared state: ordinary records use `machine-cross-checked`, while consequential provisioning records use `english-fallback`. A fallback record is accepted only when its target is byte-for-byte equal to the masked English source, making untranslated consequential prose visible instead of presenting an unqualified machine translation. It creates both locale outputs plus their receipt atomically without overwriting existing files. `validate` performs the read-only CI checks over the exact manifest, policy, source, receipts, links, fragments, fallback text and localized output trees.
