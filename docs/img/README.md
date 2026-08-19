# Documentation images

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="https://assets.ha-paneld.com/docs/logo/wordmark-dark-c957b6e2.webp">
  <img src="https://assets.ha-paneld.com/docs/logo/wordmark-light-e0f5bab8.webp" width="360" alt="ha-paneld">
</picture>

The wordmark above is served from `assets.ha-paneld.com` rather than from this repository, and is the working example of everything described below: content-addressed key, transparency preserved, metadata stripped, recorded in [`manifest.txt`](manifest.txt) with its SHA-256, and paired light and dark variants selected by `prefers-color-scheme`.

This directory holds images that are committed to the repository, plus [`manifest.txt`](manifest.txt), which records the images that are not.

**Committed here:** screenshots of ha-paneld's own interface. They are the project's own work, they carry no third-party rights, and they are tied to the release they depict — a screenshot of the Configure tab from one version is misleading against another, so having git version them alongside the code is correct. [`docs/RELEASING.md`](../RELEASING.md) covers refreshing them when the interface changes.

**Served from `assets.ha-paneld.com`:** hardware photographs and vendor product shots. These are not committed. [`docs/infrastructure.md`](../infrastructure.md) explains why and what the host records about readers. Every published object is listed in `manifest.txt` with its SHA-256 hash, so the served set can be enumerated and checked rather than taken on trust.

## Adding an image

Use [`tools/docs-assets/assets.py`](../../tools/docs-assets/assets.py) rather than uploading by hand — it strips metadata, which is a hard requirement and not a formality. Camera EXIF carries GPS coordinates and camera serial numbers, and photographs of hardware are generally taken at home.

```
python3 tools/docs-assets/assets.py ingest photo.jpg --name nspanel-pro-front --provenance own
python3 tools/docs-assets/assets.py check
python3 tools/docs-assets/assets.py upload
```

Record where each image came from. For your own photographs that is nothing more than `--provenance own`. For anything else, `--source-url` is required, and the source is recorded in the manifest as a courtesy note — it is expected to stop resolving eventually, because marketplace listings disappear, and nothing depends on it. The copy the project serves is the durable artefact.

Contributed photographs are welcome and are the best answer for hardware the project does not own. A photograph you took yourself is your own work and can be submitted under the repository licence like any other contribution — see [`CONTRIBUTING.md`](../../CONTRIBUTING.md). Please do not submit images scraped from a vendor's site or a marketplace listing.

## Published objects are permanent

Once an image's URL appears in a commit it must keep working indefinitely, because git records the reference rather than the image itself. Never delete, rename or replace a published object; publish a new one beside it instead. Deleting an image that looks unused breaks documentation at older tags silently — nothing fails to build and no test goes red.

## Licensing

Images the project authors — interface screenshots and photographs taken by contributors — are covered by the repository's [Apache-2.0 licence](../../LICENSE), like the rest of the project.

Manufacturer product photography is not, and is not relicensed by appearing here. All product names, trademarks and registered trademarks are the property of their respective owners, and all such images are used for identification purposes only.
