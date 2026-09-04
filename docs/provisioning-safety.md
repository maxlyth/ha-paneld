# Provisioning safety and recovery

This page records the checks and recovery boundaries behind ha-paneld's installer and fleet updater. Most users only need the commands in [Provisioning and fleet updates](provisioning.md). The detail here is for anyone deciding whether an unattended update is safe enough for their panels or diagnosing why the installer stopped.

## Trust and credential boundaries

Credential files keep secrets out of shell history and child-process command lines on the provisioning computer. They do not add transport encryption to the panel's management API. ha-paneld's `http://<panel>:8888` API follows the project's trusted-LAN model, so MQTT credentials and Home Assistant tokens cross that connection as cleartext HTTP. Run provisioning only on a trusted, segmented network.

The Home Assistant username and password flow is different. The password goes from the provisioning computer to Home Assistant's login endpoint and never reaches the panel. Use an `https://` Home Assistant URL so the login is encrypted in transit; an `http://` URL sends the password without transport encryption. The panel receives the resulting revocable refresh token.

Config exports contain secrets and are written with owner-only permissions. Treat them as credentials. The automatic settings and database copies made before upgrades also use an owner-only directory and mode-600 files.

## Release and package authentication

The checkout-free installer downloads the matching release APK and provisioner. It authenticates published APKs and helper binaries against release-key-signed SHA-256 records before changing the panel. It also inspects the APK package and signer with Android Build-Tools. Those are optional for a first installation on a panel that does not yet carry ha-paneld, where Android performs its own signature check during installation, and required to update a panel that already does: the provisioner compares the installed and candidate signers before it changes anything, and refuses rather than guessing. A mismatched signer would otherwise retire the running helper and only then fail to install. The provisioner selects the helper for the panel's reported ABI.

A local APK follows a separate developer path. It must still contain the ha-paneld package and exactly one valid signer, but it may use the builder's consistent signing key. `--require-release-signer` tightens that check when the local file is expected to be an official build. `--allow-unsigned-helper` is a separate acknowledgement for helper binaries controlled by the local builder rather than authenticated as release assets.

## Backups and recovery

Three different files serve different recovery jobs:

- A config JSON export contains settings and secrets. `--export FILE` creates one explicitly, while the installer also attempts an owner-only automatic export before an ordinary upgrade. It does not contain the runtime profile catalog, profile selection, learned entity state, history or Home Assistant Companion login.
- An `.hpb` from **Install → Backup** is the fullest supported backup. It carries settings, durable panel state, the profile catalog and an eligible Companion login. The learned entity catalog and proximity and ambient histories are relearned instead of restored. Restore the `.hpb` through **Install → Restore** on the same panel page. The CLI `--restore FILE` endpoint accepts config JSON, not `.hpb` archives.
- An automatic `…break-glass.db` is a last-resort copy of the panel's internal database. Nothing restores it automatically. It is valid only for the same panel and the same app version it came from.

When `--export FILE` is combined with installation or configuration options, the verified export is written before any panel change. If it cannot be produced and verified, the run stops. Use `--export FILE` as a separate command first when you want the export without any installation.

The automatic settings export before an ordinary upgrade is best-effort. If it cannot be created, the installer removes the partial file, records that no settings export exists and continues with the package replacement.

Before an uninstall, app-data clear or other destructive recovery, use the separate **Install → Backup** operation and verify that the downloaded `.hpb` is non-empty. Those operations destroy app data. An ordinary `adb install -r` retains it.

`--reset-config` has a deliberately different contract. **Reset is irreversible and makes no backup.** The command requires its own confirmation, then removes the complete ha-paneld data set. The app, helper and other Android packages remain installed.

## Automatic database snapshot

Before every ordinary upgrade, the installer attempts to capture the panel's database. This database contains configuration, the entity catalog, proximity and ambient history, and the on-panel revision history. The snapshot is a recovery aid for a later uninstall or another destructive repair; Android's normal in-place package replacement leaves the original database in place.

On a current build, the installer sends one ADB-only upgrade-preparation request to the running service. The service stops accepting new work, drains persistence owners, quiesces database writers, flushes application state, checkpoints the SQLite WAL and closes the database. It acknowledges the exact request only after recording the old process, app version, byte count, host-verifiable SHA-256, schema version and state-row count.

The installer copies the closed database directly from private app storage. It does not stage a second database on the panel. The host file is accepted only when its size and mandatory host-side SHA-256 match the acknowledgement and local SQLite integrity, schema and row checks pass.

If the installed app does not return the exact acknowledgement, the installer falls back once to SQLite's live `.backup`. That produces the same single self-contained database without WAL or journal sidecars. The fallback covers older builds that predate the handshake as well as a timed-out or malformed response. A panel with no root route cannot expose its private database, so only the settings export may be available.

Database-backup availability is best-effort for an ordinary in-place upgrade. A failed or contradictory candidate is removed, the installer prints a prominent warning, and package replacement may continue with the original app data untouched. An invalid copy is never retained or labelled as a successful backup.

There is no fixed free-space threshold for making this backup. The normal path does not stage a database on the panel, and the single fallback lets the real capture determine whether it fits. Storage pressure, a failed database health check, an unreachable status endpoint, a malformed reply or an unrecognised state is reported before and after installation. None blocks an ordinary package replacement because the new build may be the only useful recovery attempt for an unhealthy or unreachable old build. Standalone `--verify` reports those conditions as a failure when a strict pass or fail answer is required.

## Time zone and first-install checks

Before changing the panel, the installer compares its time zone with the provisioning computer's time zone. A mismatch is a warning, not a gate, because panels can legitimately use a different zone. The comparison uses zone names instead of the current UTC offset, and it resolves known aliases through the computer's time-zone database. This catches panels left on a factory-default zone that would otherwise timestamp logs and run schedules at the wrong local hour.

The check stays silent if either value cannot be established. A missing response, an invalid zone name or an alias absent from the computer's time-zone database does not fail or alter the installation.

A clean panel has no ha-paneld status endpoint, which is also true of some broken installations. The installer asks Android's package manager whether ha-paneld is installed before deciding which case it has. A definite “not installed” result begins a normal first installation. A package manager or ADB query that does not answer stops the run before the panel is changed.

## Root helper upgrade transaction

Published releases include sealed `armeabi-v7a` and `arm64-v8a` helper binaries. On a panel with vendor `su` or root ADB, the provisioner authenticates the selected binary with the release key, verifies it after staging and installs or upgrades it atomically. It checks the protocol capabilities and deterministic build identity needed by the APK before replacing the package.

The previous root-owned helper and service remain available until the APK installation succeeds. A helper installation, helper startup, identity or capability failure restores the previous working pair before the APK is replaced. If the ADB connection disappears while Android is installing the APK, the provisioner retains its recovery journal. Running the same command again authenticates the installed APK and running helper, then commits or rolls back the interrupted upgrade.

Recovery snapshots are root-owned, authenticated by their recorded digest and synchronized before live files are retired. The standalone `helper/install-daemon.sh` uses a separate helper-only journal. Each installer refuses to overwrite the other's incomplete transaction and identifies the command that must be rerun.

The provisioner prefers a writable `/system` init service when enough space can be verified. If `/system` is read-only, it uses a verified Magisk, KernelSU or APatch `/data/adb/service.d` runner. A panel with writable but crowded `/system` can keep the helper and recovery files under `/data/adb/hapaneld` while placing only the startup service in `/vendor/etc/init`. Later updates retain that verified hybrid layout. Provisioning stops before replacing the APK when it cannot establish storage capacity, startup ownership or the state of an existing transaction.

The APK carries the matching helper as an update backstop. If an older direct-`su` installation updates from the Install tab, MQTT update button or automatic update setting before it is reprovisioned, the first start verifies the required helper protocol and can launch a root-owned `/data/local` copy. A sandboxed rooted panel cannot safely make an old helper replace itself, so it fails closed and asks for reprovisioning with the authenticated external provisioner.

## Fleet update boundaries

`update-fleet.sh` downloads or accepts one APK, then verifies its package and signer and records its SHA-256 digest before starting a worker. It passes that file path to each worker, and each panel's provisioner performs its applicable checks again before changing the panel. A release downloaded into the wrapper's private temporary directory remains tied to its signed checksum. If you supply a local APK path, do not replace that file while `update-fleet.sh` is running. The concurrency pool is bounded between one and 32 panels.

Panel-specific options are rejected before any worker starts. This includes `--reset-config`, `--export FILE`, `--id` and device-specific `--restore FILE`. `--restore-fleet FILE` is allowed because it imports only portable, non-secret settings. Each panel's identity remains unchanged. Credentials also remain unchanged unless they are supplied as shared pass-through arguments; provision panels separately when their credentials differ.

Profile recommendations stay advisory during a fleet run. The updater does not automatically disable packages, persist ADB, install optional privileged software or change display settings merely because a hardware profile recommends it.

## What can stop a run

The provisioner stops with a nonzero result when it cannot safely establish a requirement for the requested operation. Examples include a package query that does not answer, an APK with the wrong package or signer, a required helper transaction that cannot be recovered, a requested config export that cannot be verified, or a required post-install self-check that fails.

Other findings are warnings because stopping would make recovery harder. An unavailable automatic database backup, a storage-health warning or a failed pre-upgrade status call does not by itself prevent an ordinary in-place package replacement. The installer states that boundary explicitly instead of presenting a partial or invalid recovery file as successful.
