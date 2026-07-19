# Releasing ha-paneld

A release is cut by pushing a `vX.Y.Z` tag; the [Release workflow](../.github/workflows/release.yml) then builds, signs and publishes the APK and both supported root-helper binaries, taking the human-readable notes from the matching `CHANGELOG.md` section. Run this checklist **before** tagging.

> [!IMPORTANT]
> **Releases are contenders until approved.** Pushing a tag publishes a GitHub release (and a CI run) that persists publicly — so prepare and verify a release *before* tagging, and **tag only on explicit approval**. Pushing dev commits to `main` is fine; the **tag push is the gate**. Don't eager-tag and then force-move the tag to absorb late fixes — fold late changes into the still-untagged contender instead. (Force-moving a published tag rewrites a release others may have pulled.)

## Pre-tag checklist

1. **Public reference currency — required for every public stable and prerelease build.** Reconcile the complete public reference set with the final contender, not only files touched by the release:
   - Check README capability, support, privilege and release-status claims against the current source.
   - Check `docs/api.md` against the registered HTTP routes, MQTT discovery contracts and generated `/api/v1/openapi.json`; check the profile-format reference against the runtime schema and driver catalog.
   - Check feature, provisioning, security, build and hardware guides against current settings, scripts, profiles and access probes. Run the repository's documentation link/source checks and resolve stale headings as well as missing files.
   - Review every addition or removal in `/diag` instrumentation. Keep only data with concrete issue-triage value; keep it terse; remove duplication with existing status/capability fields; and verify that values, examples and redaction rules do not expose credentials, tokens, network identifiers, panel names, entity IDs or other PII/private deployment context.
   - When release notes or announcement drafts introduce Hardened mode, including GitHub and forum posts, state plainly that it requires physical access and that high-impact remote actions cannot proceed until someone approves them on the panel's screen; they cannot be approved remotely.
   - In **`docs/roadmap.md`**, move every item that's now done **out of "Planned"** (it's shipped — it belongs in `CHANGELOG.md`, not the roadmap).
   - Refresh the **README release-status summary** under *Status & roadmap*. Until a candidate is actually published, label it as under test rather than calling it the latest release. The release badge reads GitHub dynamically; the human summary still needs to match the public state. Keep the "Where it's heading" summary in sync with `docs/roadmap.md`.
   - Refresh screenshots in `docs/img/` from the final combined contender when the UI changed. Use the deterministic capture harness with Roboto loaded and verified, fixed documented viewports and normalized image metadata; the on-panel standing-screen shot is **480×480**.

2. **CHANGELOG.md** — the release workflow looks for a section whose header matches the **exact tag** (`## v0.8.4-rc3`, not `## v0.8.4`). For a missing RC section the workflow **errors** (not just warns) so the CI job fails before a release is published with wrong notes.

   - **RC tags** (`-rc1`, `-rc2`, …): add a `## vX.Y.Z-rcN - <date>` section describing **only what changed since the previous RC** — not a repeat of prior RC content. This curated section is the complete public release body, so include every user-visible change needed to evaluate the candidate without adding maintainer-oriented commit lists.
   - **RC numbers normally track PUBLISHED prereleases only** — the `-rcN` suffix increments when an rc is tagged and pushed to GitHub, while ordinary internal iteration bumps `versionCode` alone. Bump `versionCode` aggressively: it drives in-place upgrades and the `/health` build token. If the maintainer explicitly allocates a numbered local candidate before publication, give it its own delta-only section and preserve every earlier RC section; do not make the newest candidate cumulative.
   - **Stable tags**: add `## vX.Y.Z - <date>` with cumulative notes for the whole version. The workflow falls back to the base-version section only for stable tags, so the stable section is the one place to summarise the full release for users upgrading directly from the previous stable.
   - **The stable section must be SELF-CONTAINED** — it is the canonical summary of everything the RC line delivered, so it must not point at or cite RC notes (no "see the rc sections", no per-bullet `(rcN)` attributions). Keep the separate delta-only `-rcN` sections throughout the prerelease cycle. At stable promotion, merge their user-relevant content into the stable section and remove those superseded RC sections; the RC prereleases and tags are deleted at the same point (next section).

   Group entries under **Added / Changed / Fixed / Docs** (only the groups with content) — see the format note at the top of `CHANGELOG.md`.
3. **Version bump** — `app/build.gradle.kts` `versionName` matches the tag and `versionCode` is incremented. (A higher `versionCode` lets panels `install -r` in place.) Confirm the dynamic release badge in `README.md` still points at this repository's GitHub releases; no version edit is needed for the badge itself.
4. **Docs** — any new capability has a matching `docs/` entry (hardware page, recipe, etc.).
5. **Build + sanity-test** on at least one real panel (`scripts/update-fleet.sh --apk <built.apk> -- <ip>`).
   - For a rooted panel and local APK, provisioning extracts the ABI-matched helper embedded in that exact APK. It treats the APK and helper as one compatibility unit and refuses to replace the APK unless the running helper reports the embedded source-derived build identity and required protocol.
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
