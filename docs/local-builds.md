# Local & devcontainer builds

Build ha-paneld off-CI — on a laptop, ideally in the VS Code **devcontainer** so the Android SDK/NDK never touch bare metal.

## Devcontainer (VS Code)

[`.devcontainer/`](../.devcontainer/) pins the same toolchain as CI (JDK 17, compileSdk 37, Build-Tools 36.0.0, NDK 27.0.12077973, CMake 3.22.1). Open the repo in VS Code → **Reopen in Container**, then:

```bash
./gradlew assembleDebug      # debug-signed APK
./gradlew assembleRelease    # release-signed if keystore.properties is present (below), else unsigned
```

Output lands in `app/build/outputs/apk/`. `adb` is on the container's PATH, so `adb connect <panel-ip>:5555 && adb install -r <apk>` reaches panels over the network.

## Local release signing (optional)

Release builds are unsigned by default — CI post-signs them with `apksigner` from Actions secrets. A locally signed `assembleRelease` installs in place over the public releases, with no uninstall needed. To sign locally with your **real release key**, copy the template and fill it in:

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
> A panel currently running a **debug-signed** build needs one uninstall before its first release-signed install because the signature changes. First create and verify a full encrypted `.hpb` backup from **Install → Backup**; config JSON from `--export` is not a full backup. After reinstalling, restore the `.hpb` from **Install → Restore**. Subsequent same-key `install -r` updates work. The committed `gradle/debug.keystore` keeps all *debug* builds mutually in-place-updatable.
