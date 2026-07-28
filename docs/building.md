# Building ha-paneld from source

You don't need to build ha-paneld to use it — `scripts/install.sh` fetches a signed release (see the [README](../README.md#install)). This page is for contributors and forkers. For the VS Code devcontainer workflow and signing local release builds for in-place fleet updates, see also [local-builds.md](local-builds.md).

## Option A — Docker (no toolchain, no CI access needed)

Only Docker is required. The script builds a version-pinned image (JDK 17 + Android SDK 37 + NDK + CMake, matching CI) and runs Gradle inside it; the APK lands in your working tree.

```sh
./tools/build/build.sh                       # debug APK -> app/build/outputs/apk/debug/
./tools/build/build.sh :app:assembleRelease  # any Gradle task(s) instead
```

The image is built once and cached; Gradle caches persist in a named Docker volume, so repeat builds are fast. See [`tools/build/`](../tools/build/) (and the `HOST_WORKDIR` note in `build.sh` if you run from inside a container talking to an outer Docker daemon).

## Option B — local toolchain

```sh
./gradlew :app:assembleDebug      # debug APK -> app/build/outputs/apk/debug/
./gradlew :app:assembleRelease    # release APK (unsigned unless signing configured)
```

Requires **JDK 17** and an Android SDK with **platform 37.0, Build-Tools 36.0.0, NDK 27.0.12077973, and CMake 3.22.1** (for the native `/dev/ledjni` LED driver). The Gradle wrapper pins the Gradle version.

When provisioning a local APK onto a rooted panel, also run `./helper/build.sh` first. The app and both ABI-specific helpers embed the same deterministic identity derived from every helper source file, header and command definition. The provisioner installs the matching helper and verifies that identity plus the required protocol before replacing the APK, so a local app build cannot silently depend on stale privileged code.

## Toolchain note

The build uses AGP 9.1.1 with built-in Kotlin 2.4 and Gradle 9.6.1. Kotlin's compatibility table names AGP 9.1.0 and Gradle 9.5 as its upper tested versions. AGP 9.1.1 supplies Android API 37 support, while Gradle 9.6.1 reduces CI filesystem overhead. Both exceptions are guarded by the project's debug, release and oldest-Android test gates. Dependency versions live in [`gradle/libs.versions.toml`](../gradle/libs.versions.toml).

## Signing — what forkers need to know

You don't need to configure signing to build and run ha-paneld.

Two cases:

- **Dev / fork debug builds** use the committed `debug.keystore` (password `android`) and remain directly installable without maintainer secrets. Fork maintainers may configure and consistently retain their own private release key for devices they manage. The official maintainer fleet separately requires the official release signer.
- **Official releases** are signed with a private key held in GitHub Actions secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`). The release workflow fails closed if any credential is absent; it never publishes a debug-signed APK as a release. Forks can still build and install the normal debug APK through local builds or CI, or configure their own four signing secrets before creating tagged releases.

> [!IMPORTANT]
> Android refuses to update an installed app with an APK signed by a **different** key. Before uninstalling, create a **full encrypted `.hpb` backup** from **Install → Backup**, verify that the file was saved, and note that a `provision.sh --export` JSON file is only a settings clone—not a full recovery backup. Then uninstall (`adb uninstall io.github.maxlyth.hapaneld`), install the other build, and restore the `.hpb` from **Install → Restore**. Uninstalling otherwise permanently removes profiles, learned state/history and any included Companion login.
>
> **Legacy device-admin (builds ≤ 0.5.0 only):** 0.5.1 removed the device admin entirely, so fresh installs never hit this. But if you'd activated it on an older build, uninstall fails with `DELETE_FAILED_DEVICE_POLICY_MANAGER` until you deactivate it — **Settings → Security → Device admin apps → turn off "ha-paneld"**, then `adb uninstall io.github.maxlyth.hapaneld`. (`dpm remove-active-admin` does *not* work — Android refuses to remove a non-test admin via the CLI.)

**Signing your own fork's releases (optional):**

```sh
keytool -genkeypair -storetype PKCS12 -keystore release.jks -alias ha-paneld \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=ha-paneld"
base64 -w0 release.jks   # -> the ANDROID_KEYSTORE_BASE64 repo secret
```

Use one password for both `ANDROID_KEYSTORE_PASSWORD` and `ANDROID_KEY_PASSWORD`, and `ha-paneld` (your alias) for `ANDROID_KEY_ALIAS`. Back up `release.jks` and the password safely — losing them means you can never publish an in-place update again. Never commit the keystore (`*.jks` is gitignored).
