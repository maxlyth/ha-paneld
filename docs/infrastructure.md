# Project infrastructure

ha-paneld runs on a panel and talks to your Home Assistant instance. It does not talk to us. This page documents the one piece of project-operated infrastructure that exists — the domain and storage used to serve images in this documentation — so that anyone who wants to check what it does, and what it records, can do so without taking anyone's word for it.

**Panels never contact `ha-paneld.com`.** The app fetches nothing from it, at any point, and the source contains no reference to it. The domain serves documentation images to people reading these pages in a browser. Nothing on a panel depends on it, and ha-paneld works identically with the domain unreachable or gone.

## What exists

`ha-paneld.com` is registered at OVH, with DNS served by Cloudflare. Documentation images are stored in a Cloudflare R2 bucket published at `assets.ha-paneld.com`, behind Cloudflare's cache. There is no application, no database, no login and no form anywhere on the domain — it serves static image files and nothing else.

The published object set is recorded in [`docs/img/manifest.txt`](img/manifest.txt), which lists every object with its SHA-256 hash and its provenance. The tooling that publishes them is [`tools/docs-assets/`](../tools/docs-assets/), in this repository and readable. Between the two, the complete set of what is served can be enumerated and checked against what is claimed.

## Why images are not in this repository

Two reasons, both practical.

Product photography for the panels this project supports comes from manufacturers and marketplace listings whose re-use terms are unstated. The project is not willing to place content of unverifiable licensing into a repository whose history cannot be rewritten — this repository is never force-pushed, is mirrored elsewhere, and has release tags and F-Droid packages anchored to specific commits, so anything committed is effectively permanent. Adding a file later is easy; removing one is not. Serving images from project-controlled storage keeps that content out of the repository entirely.

Second, marketplace listings disappear. Hotlinking a vendor's image would guarantee broken documentation later, and would also expose every reader's IP address and referrer to a third party the project does not control. Taking a copy and serving it ourselves avoids both.

Images the project authors itself — screenshots of ha-paneld's own interface — remain committed in this repository as normal, because they carry no such uncertainty and they are genuinely tied to the release they depict.

## What is recorded when you load a page

Requests to `assets.ha-paneld.com` reach Cloudflare, which logs them and reports aggregates: request volume, referring pages, approximate geography, and unique-visitor estimates derived from IP address. The project uses those aggregates for one purpose — judging whether the documentation is being read and where a single maintainer's limited time is best spent.

What does not happen: no cookies are set, no JavaScript or tracking pixel is served, no third-party analytics or advertising is present, no cross-site identifier is used, and no attempt is made to build a profile of any individual or follow anyone between sites. The host serves image files only, so there is no page on it for a beacon to live in.

Two honest caveats rather than a claim of purity. IP addresses are personal data under GDPR, so this is data processing however aggregate the use of it — the basis is legitimate interest, with no onward sharing and no retention beyond what Cloudflare's own analytics keep. And when you read this documentation on GitHub, GitHub proxies the images through its own servers, so the project sees GitHub rather than you, and the numbers are correspondingly coarse. They are a rough signal, not a measurement.

## Rules for maintaining this

**Published objects are permanent.** Never delete, rename or repoint an object once its URL has appeared in a commit. Git records the reference, not the image, so a commit made today holds a permanent pointer into storage that can still be changed tomorrow — and the pointer in an old tag can never be corrected. Superseding an image means publishing a new object beside the old one.

This matters more than it looks, because the failure is silent. Tidying up images that appear unused is exactly the sort of housekeeping that seems safe: nothing fails to build, no test goes red, and the breakage only shows up as missing pictures on documentation pages at older tags that few people read. The rule is written here with its reason attached precisely so it does not get optimised away later by someone acting reasonably.

**The bucket is public.** Anything placed in it is world-readable from the moment it is uploaded. Never stage anything there that is not intended for publication.

**Publishing is effectively irreversible, and more so than deleting the object suggests.** Objects are served with a one-year immutable cache header, so Cloudflare's edge continues serving a file for up to a year after it has been deleted from the bucket. This was measured, not assumed: a test object removed from R2 still answered with HTTP 200 from the cache immediately afterwards. Deleting from storage is therefore not a withdrawal, and there is no undo available from the publishing tooling — the only way to stop serving something promptly is a cache purge from the Cloudflare dashboard (Caching → Configuration → Purge by URL). Treat every upload as final and check the image before running it, rather than relying on being able to take it back.

**Keys stay under the `docs/` prefix.** Paths cannot be reorganised after publication, for the same permanence reason, so the namespace is partitioned in advance and the bucket root is left unused.

**The credential is a single narrow write token**, scoped to this one bucket. Reading requires nothing, so it does not grant read access. If a second use appears, issue a second token rather than widening this one — widening leaves no trace after the fact.

**Verify published objects periodically**, not only when publishing, using `tools/docs-assets/assets.py verify`. A served object whose hash no longer matches the manifest means the content was substituted, and the manifest is the only thing positioned to notice.

**The domain registration is the one irreversible dependency.** Renewal is a diary item: if it lapses, every image URL in every commit breaks permanently, and no amount of later effort recovers those references.
