# Documentation image pipeline

Hardware photographs and vendor product shots used in the documentation are served from `assets.ha-paneld.com` rather than committed to this repository. This directory holds the tooling that publishes them, and [`docs/img/manifest.txt`](../../docs/img/manifest.txt) records every published object so the served set stays enumerable and verifiable.

Why images are hosted rather than committed is covered in [`docs/infrastructure.md`](../../docs/infrastructure.md).

## Requirements

`python3` with [Pillow](https://pypi.org/project/Pillow/) for image handling, and [rclone](https://rclone.org/) configured with an R2 remote for publishing. Reading published images needs nothing, because the bucket is public.

Originals and published bytes live in `$HA_PANELD_DOCS_ASSETS_DIR`, which defaults to `.docs-assets/` beside the checkout and is ignored by git. It holds the only copy of the untouched source images, so keep it somewhere durable.

## Commands

```
python3 tools/docs-assets/assets.py ingest <file> --name <kebab-case> --provenance own|vendor|contributor
python3 tools/docs-assets/assets.py check
python3 tools/docs-assets/assets.py upload [--dry-run]
python3 tools/docs-assets/assets.py verify
```

`ingest` converts a source image to sRGB WebP, strips every metadata chunk, resizes to a maximum width, hashes the result, stores the untouched original and the published bytes outside this repository, and records the object in the manifest. The object key is derived from the content hash, so re-ingesting identical content is a no-op and different content always gets a different key.

`check` verifies the manifest and the staged objects agree in both directions: every manifest entry has a local file whose hash matches, and every staged file appears in the manifest. It also rejects any key outside the `docs/` prefix. `upload` refuses to run while `check` reports problems.

`upload` publishes staged objects to R2. It probes for each key first and passes `--immutable` to rclone, so an object that already exists is never modified. It preflights the remote before uploading, because a failed listing would otherwise be indistinguishable from an empty bucket.

`verify` fetches every published object over HTTPS and confirms the served bytes match the manifest hash, the content type is correct, and the immutable cache header is present. A hash mismatch on an already-published object means the served content was substituted, which the manifest is the only thing positioned to detect.

## Two rules the tooling enforces

**Published objects are permanent.** A URL that has appeared in a commit must keep resolving to the same bytes indefinitely, because git records the reference and not the image. Superseding an image means publishing a new object beside the old one, and `upload` will not modify a key that already exists.

**Everything lives under the `docs/` prefix.** Object paths cannot be reorganised after publication for the same reason, so the namespace is partitioned up front and the bucket root is left free. `check` rejects any key outside `docs/`.

## Metadata stripping

Camera EXIF carries GPS coordinates and camera body serial numbers, so stripping it is a hard gate rather than a tidiness step. Ingest inspects the encoded file's RIFF chunks and refuses to record anything still carrying `EXIF`, `XMP ` or `ICCP`, rather than trusting the encoder to have dropped them. A non-sRGB source is converted to sRGB before its colour profile is discarded, so removing the profile does not silently shift colour.
