# Local & devcontainer builds

Build ha-paneld off-CI — on a laptop, ideally in the VS Code **devcontainer** so the Android SDK/NDK never touch bare metal.

## Devcontainer (VS Code)

[`.devcontainer/`](../.devcontainer/) pins the same toolchain as CI (JDK 17, compileSdk 37, Build-Tools 36.0.0, NDK 27.0.12077973, CMake 3.22.1). Open the repo in VS Code → **Reopen in Container**, then:

```bash
./gradlew assembleDebug      # installable development APK using the public debug signer
./gradlew assembleRelease    # release-signed if keystore.properties is present (below), else unsigned
```

Output lands in `app/build/outputs/apk/`. A public checkout needs no project release secret to build and install its development APK. Fleet updates that download an official ha-paneld release pin its public certificate automatically; add `--require-release-signer` to a local APK deployment only when that official signer is expected.

## Local release signing (optional)

Release builds are unsigned by default — CI post-signs official artifacts with `apksigner` from Actions secrets. A locally signed `assembleRelease` installs in place only over an APK signed with the same key; Android rejects an in-place update when the installed and replacement signers differ. To sign your own builds consistently, copy the template and fill it in:

```bash
cp keystore.properties.example keystore.properties
```

```properties
storeFile=release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Both `keystore.properties` and `*.jks` are gitignored.

> [!CAUTION]
> **Never commit `keystore.properties` or the `.jks`.** The release private key can sign in-place updates for every ha-paneld user. Keep the keystore on your own build machine, with tight file permissions.

With the file present, `assembleRelease` is release-signed; without it (e.g. CI, or a fresh clone), gradle leaves the release APK unsigned and the rest of the pipeline is unaffected.

> [!NOTE]
> A panel currently running a **debug-signed** build needs one attended migration before its first release-signed install because the signature changes. First create a full encrypted `.hpb` backup from **Install → Backup** and verify that the file was saved; a `provision.sh --export` JSON file is only a settings clone, not a full recovery backup. Then uninstall, install the release-signed APK and restore the `.hpb` from **Install → Restore**. Public self-build users may remain consistently on their own development signer; official releases use the project's private release key.
