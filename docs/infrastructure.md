# Project infrastructure

ha-paneld runs on a panel and talks to your Home Assistant instance. The app does not talk to the project. This page documents the static project-operated infrastructure that exists, so that anyone who wants to check what it does can do so without taking anyone's word for it.

**The ha-paneld app never contacts `ha-paneld.com`.** Installing the app through the optional F-Droid repository causes the separate F-Droid client to fetch repository indexes and APKs from `fdroid.ha-paneld.com`. Once installed, ha-paneld works identically with the domain unreachable or gone.

## What exists

Documentation images are stored in a Cloudflare R2 bucket published at `assets.ha-paneld.com`. The optional F-Droid repository is stored in a separate R2 bucket published at `fdroid.ha-paneld.com`. There is no application, database, login or form on either host; they serve static files only.

Every published documentation image is listed in [`docs/img/manifest.txt`](img/manifest.txt) with its SHA-256 hash and its provenance, and the tooling that publishes them is [`tools/docs-assets/`](../tools/docs-assets/) in this repository. The signed F-Droid indexes enumerate APKs copied byte-for-byte from stable GitHub release assets. Between those records and the publishing tools, what is served can be enumerated and checked rather than taken on trust.

## Why images are not in this repository

Product photography for the panels this project supports comes from manufacturers and marketplace listings whose re-use terms are unstated, and this repository's history is effectively permanent: it is never force-pushed, it is mirrored elsewhere, and release tags and F-Droid packages are anchored to specific commits. Serving those images from project-controlled storage keeps content of unverifiable licensing out of the repository entirely. Hotlinking a vendor's listing instead would guarantee broken documentation once the listing disappeared, and would expose every reader's address and referrer to a third party the project does not control.

Screenshots of ha-paneld's own interface stay committed here as normal. They carry no such uncertainty, and they are genuinely tied to the release they depict.

## What is recorded when you load a page

Requests reach Cloudflare, which reports aggregates: request volume, referring pages, approximate geography and IP-derived visitor estimates. They are used to operate these static hosts and to judge whether the documentation is being read and where a single maintainer's limited time is best spent.

No cookies are set, no JavaScript or tracking pixel is served, no third-party analytics or advertising is present, no cross-site identifier is used, and no attempt is made to build a profile of anyone or follow them between sites. The hosts serve static files only, so there is no page on them for a beacon to live in.

Two caveats rather than a claim of purity. IP addresses are personal data under GDPR, so this is data processing however aggregate the use of it. And when you read this documentation on GitHub, GitHub proxies the images through its own servers, so the project sees GitHub rather than you. The numbers are a rough signal, not a measurement.
