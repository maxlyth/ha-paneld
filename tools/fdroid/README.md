# ha-paneld F-Droid repository (maintainer notes)

A self-hosted [F-Droid](https://f-droid.org/) repository so users can install **and auto-update**
ha-paneld directly on a panel — no Google Play, no PC/adb for the app itself. (Full functionality still
needs root/su on the panel; F-Droid only solves distribution. See the [NSPanel Pro firmware v4.0.0
note](../../docs/hardware/nspanel-pro.md#firmware-v400--official-f-droid-app-install).)

## How it works

[`.github/workflows/fdroid.yml`](../../.github/workflows/fdroid.yml) is **stateless**:

1. After the **Release** workflow succeeds for a full-version tag (via `workflow_run` — `on: release`
   can't be used because releases created by another workflow's `GITHUB_TOKEN` don't emit triggering
   events), or on a manual `workflow_dispatch`, it downloads the release-signed `*.apk` asset from
   every non-prerelease release into `repo/`.
2. `fdroid update` builds + signs the repo index.
3. The result is published to the dedicated `ha-paneld-fdroid` Cloudflare R2 bucket at `https://fdroid.ha-paneld.com/fdroid/repo`.

Because the served APKs are the **same release-signed binaries** as the GitHub Releases (same key), the
F-Droid client updates panels in place — no signature clash with builds installed via `update-fleet.sh`.

Pre-releases (`…-rcN`) are excluded by the successful Release-workflow tag filter, so the user repo
only ever carries stable versions.

## Identity / signing

The repo index is signed with the **same keystore that signs the release APKs** (`ANDROID_KEYSTORE_*`
secrets, reused from `release.yml`). So the repo's pinning fingerprint == the app signing certificate.
No keystore material lives in this directory — CI appends the keystore path + passwords to a runtime
copy of `config.yml` only.

## One-time setup

1. Create the `ha-paneld-fdroid` R2 bucket, connect `fdroid.ha-paneld.com` as its custom domain and leave its `r2.dev` URL disabled.
2. Create a protected GitHub environment named `fdroid`. Set `FDROID_R2_ACCOUNT_ID` as an environment variable and add `FDROID_R2_ACCESS_KEY_ID` and `FDROID_R2_SECRET_ACCESS_KEY` as environment secrets. The access key must have Object Read & Write access to this bucket only.
3. Confirm the `ANDROID_KEYSTORE_BASE64` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` /
   `ANDROID_KEY_PASSWORD` secrets exist (they already do — `release.yml` uses them).
4. Trigger once via **Actions → F-Droid repo → Run workflow** (or cut a full release).
5. From the run log (or the [published landing page](https://fdroid.ha-paneld.com/index.html)), copy the **add-repo URL**:
   `https://fdroid.ha-paneld.com/fdroid/repo?fingerprint=<FP>`
6. Verify the landing page, signed indexes and at least one APK through the custom hostname. Then set **Settings → Pages → Build and deployment → Source** to **None** so the previous GitHub Pages site is deleted and the top-level Pages resource is free.

The publisher uploads versioned APKs before the mutable repository indexes and writes signed `entry.jar` last. It refuses to replace an existing APK with different bytes, does not delete old objects, restores the prior signed index set after an ordinary publication failure and verifies newly uploaded APKs through the custom domain.

## Local test

```bash
pip install fdroidserver
cd tools/fdroid
mkdir -p repo && cp /path/to/ha-paneld-vX.Y.Z.apk repo/
# point config.yml at a throwaway keystore for a dry run:
fdroid update --create-metadata --pretty --verbose
python3 -c "import json; print(json.load(open('repo/index-v2.json'))['repo']['fingerprint'])"
```

## Tracked vs generated

- **Tracked:** `config.yml` (public fields), `metadata/io.github.maxlyth.hapaneld.yml`, `site-index.html`.
- **Generated (gitignored):** `repo/`, `archive/`, `tmp/`, and the CI `_site/`.
