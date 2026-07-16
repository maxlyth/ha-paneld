# Releasing ha-paneld

A release is cut by pushing a `vX.Y.Z` tag; the [Release workflow](../.github/workflows/release.yml) then builds, signs and publishes the APK and both supported root-helper binaries, taking the human-readable notes from the matching `CHANGELOG.md` section. Run this checklist **before** tagging.

> [!IMPORTANT]
> **Releases are contenders until approved.** Pushing a tag publishes a GitHub release (and a CI run) that persists publicly — so prepare and verify a release *before* tagging, and **tag only on explicit approval**. Pushing dev commits to `main` is fine; the **tag push is the gate**. Don't eager-tag and then force-move the tag to absorb late fixes — fold late changes into the still-untagged contender instead. (Force-moving a published tag rewrites a release others may have pulled.)

## Pre-tag checklist

1. **Docs — reconcile with what shipped.** The README is intentionally lean; the roadmap and per-release notes live in dedicated files:
   - In **`docs/roadmap.md`**, move every item that's now done **out of "Planned"** (it's shipped — it belongs in `CHANGELOG.md`, not the roadmap).
   - Refresh the **README "Latest release — X.Y.Z"** one-liner under *Status & roadmap*. The release badge reads GitHub dynamically; the human summary still needs to match the release. Keep the "Where it's heading" summary in sync with `docs/roadmap.md`.
   - Fix any entity names / behaviour the release changed elsewhere in the README or `docs/`.
   - Refresh screenshots in `docs/img/` if the UI changed (the on-panel launcher shot is **480×480**).
2. **CHANGELOG.md** — the release workflow looks for a section whose header matches the **exact tag** (`## v0.8.4-rc3`, not `## v0.8.4`). For a missing RC section the workflow **errors** (not just warns) so the CI job fails before a release is published with wrong notes.

   - **RC tags** (`-rc1`, `-rc2`, …): add a `## vX.Y.Z-rcN - <date>` section describing **only what changed since the previous RC** — not a repeat of prior RC content. The workflow appends the auto-generated commit list below it, so you don't need to list every commit; one bullet per user-visible change is enough.
   - **RC numbers track PUBLISHED prereleases only** — the `-rcN` suffix increments when an rc is tagged and pushed to GitHub, never for unpublished/internal builds. Internal iteration bumps `versionCode` alone; bump it aggressively — it drives in-place upgrades and the `/health` build token. `versionName` stays on the next unpublished rc, and that rc's single CHANGELOG section ("`- Unreleased`" until tagged) absorbs everything since the last published tag.
   - **Stable tags**: add `## vX.Y.Z - <date>` with cumulative notes for the whole version. The workflow falls back to the base-version section only for stable tags, so the stable section is the one place to summarise the full release for users upgrading directly from the previous stable.
   - **The stable section must be SELF-CONTAINED** — it is the canonical summary of everything the RC line delivered, so it must not point at or cite RC notes (no "see the rc sections", no per-bullet `(rcN)` attributions). The RC prereleases are **deleted on promote** (next section), so any RC reference in the published stable note dangles. The dated `-rcN` sections may stay in `CHANGELOG.md` as in-repo development history, but the stable note has to read complete without them.

   Group entries under **Added / Changed / Fixed / Docs** (only the groups with content) — see the format note at the top of `CHANGELOG.md`.
3. **Version bump** — `app/build.gradle.kts` `versionName` matches the tag and `versionCode` is incremented. (A higher `versionCode` lets panels `install -r` in place.) Confirm the dynamic release badge in `README.md` still points at this repository's GitHub releases; no version edit is needed for the badge itself.
4. **Docs** — any new capability has a matching `docs/` entry (hardware page, recipe, etc.).
5. **Build + sanity-test** on at least one real panel (`scripts/update-fleet.sh --apk <built.apk> -- <ip>`).
   - For a rooted panel and local APK, run `./helper/build.sh` first. Provisioning treats the APK and ABI-matched helper as one compatibility unit and refuses to replace the APK unless the running helper reports the exact source-derived build identity and required protocol.
   - Tagged builds rerun the helper unit, peer-authentication, filesystem-boundary, installer-security and app/daemon contract checks before producing either privileged binary.
6. **Authorship / no-secrets gate** — verify that commit authors and trailers are intentional and that the published history contains no keystore or credentials. Scan the pending commits before pushing.

## Tag + publish

```sh
git push origin main
git tag vX.Y.Z && git push origin vX.Y.Z   # CI builds, signs, publishes the release
```

## After release

- Roll the fleet: `scripts/update-fleet.sh --latest -- <ip> <ip> …` (installs **and** launches each panel — a bare `adb install -r` loop leaves them installed-but-dead).
- Confirm the release contains both `ha-paneld-helper-<tag>-armeabi-v7a` and `ha-paneld-helper-<tag>-arm64-v8a`, with a `.sha256` and `.sha256.sig` beside each. The provisioner fails closed before APK replacement if the selected helper or proof is absent.
- Confirm panels report the new version and reappear in HA.
