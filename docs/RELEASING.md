# Releasing ha-paneld

A release is cut by pushing a `vX.Y.Z` tag; the [Release workflow](../.github/workflows/release.yml)
then builds, signs and publishes the APK, taking the human-readable notes from the matching
`CHANGELOG.md` section. Run this checklist **before** tagging.

> [!IMPORTANT]
> **Releases are contenders until approved.** Pushing a tag publishes a GitHub release (and a CI run)
> that persists publicly — so prepare and verify a release *before* tagging, and **tag only on explicit
> approval**. Pushing dev commits to `main` is fine; the **tag push is the gate**. Don't eager-tag and
> then force-move the tag to absorb late fixes — fold late changes into the still-untagged contender
> instead. (Force-moving a published tag rewrites a release others may have pulled.)

## Pre-tag checklist

1. **README — always check it.** Open `README.md` → *Status & roadmap* and reconcile it with what
   actually shipped:
   - Move every item that's now done **out of "Planned"**.
   - Add a `New in X.Y.Z` block summarising the release.
   - Fix any entity names / behaviour the release changed elsewhere in the README.
   - Refresh screenshots in `docs/img/` if the UI changed (the on-panel launcher shot is **480×480**).
2. **CHANGELOG.md** — there is a `## vX.Y.Z - <date>` section for the tag (the workflow extracts it
   verbatim; no section → empty notes). Group entries under **Added / Changed / Fixed / Docs** (only the
   groups with content) — see the format note at the top of `CHANGELOG.md`.
3. **Version bump** — `app/build.gradle.kts` `versionName` matches the tag and `versionCode` is
   incremented. (A higher `versionCode` lets panels `install -r` in place.) Also bump the **static
   release badge** in `README.md` (`img.shields.io/badge/release-vX.Y.Z-blue`) — it's static on purpose
   (the dynamic shields GitHub badge flaked constantly with "invalid"/token-pool errors), so it won't
   update itself.
4. **Docs** — any new capability has a matching `docs/` entry (hardware page, recipe, etc.).
5. **Build + sanity-test** on at least one real panel (`scripts/update-fleet.sh --apk <built.apk> -- <ip>`).
6. **No-attribution / no-secrets gate** — the published history must contain no AI/Claude attribution
   and no keystore/credentials. Scan the pending commits before pushing.

## Tag + publish

```sh
git push origin main
git tag vX.Y.Z && git push origin vX.Y.Z   # CI builds, signs, publishes the release
```

## After release

- Roll the fleet: `scripts/update-fleet.sh --latest -- <ip> <ip> …` (installs **and** launches each
  panel — a bare `adb install -r` loop leaves them installed-but-dead).
- Confirm panels report the new version and reappear in HA.
