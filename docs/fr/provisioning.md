> [!IMPORTANT]
> Ce document est généré automatiquement et fait l’objet d’une vérification croisée automatique, mais il n’a pas été systématiquement relu par des locuteurs de cette langue. La documentation en anglais fait foi. [Consulter la source en anglais](../provisioning.md) ou [signaler une correction de traduction](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Provisionnement et mises à jour du parc

Le programme d’installation téléchargeable peut configurer un panneau via adb sans extraire le dépôt. Il installe ou met à jour ha-paneld, applique les réglages demandés, accorde les autorisations Android disponibles via adb et vérifie que l’application s’exécute avant de terminer. Cette page présente d’abord la procédure habituelle pour un seul panneau, puis les réglages partagés et les mises à jour de l’ensemble du parc.

Pour l’installation interactive la plus courte, consultez le [README](README.md#installation). Pour en savoir plus sur les protections de la base de données, des paquets et des utilitaires qui sous-tendent ces commandes, consultez [Sécurité et récupération du provisionnement](../provisioning-safety.md).

> [!NOTE]
> Il s’agit de commandes `bash` et `adb`. Sous **Windows**, exécutez-les dans **Git Bash** (fourni avec [Git for Windows](https://gitforwindows.org/)) ou **WSL**, et non dans PowerShell, avec `adb` dans le `PATH` (`winget install Google.PlatformTools`). Sous macOS et Linux, exécutez-les telles quelles.

## Installer ou mettre à jour un panneau

Remplacez l’adresse d’exemple par celle du panneau, puis collez la commande complète dans Git Bash, WSL, macOS Terminal ou un terminal Linux :

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555
```

The installer downloads and authenticates the matching signed release and provisioner. It connects to the panel, installs the ABI-matched root helper where the firmware permits it, installs the APK, grants the required permissions, starts ha-paneld and finishes with a self-check. The running app then reports any guidance derived from the active hardware profile and live panel state.

Provisioning is idempotent. If a step is interrupted or fails, correct the problem and run the same command again. Optional or manual recommendations remain visible without turning a successful installation into a failure.

### Définir l’identité et les connexions du panneau

Ajoutez les options de provisionnement après l’adresse. Cet exemple attribue un identifiant au panneau et définit un broker MQTT :

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --id kitchen --mqtt tcp://192.168.1.10:1883
```

Utilisez `--prerelease` avant `--provision` pour suivre la version publiée la plus récente, y compris les versions candidates. Une version stable plus récente reste prioritaire. Sans cette option, le programme d’installation suit uniquement les versions stables.

Les options courantes comprennent `--force`, `--builtin`, `--ha-url`, `--ha-token-file`, `--ha-user`, `--ha-pass-file`, `--home-dashboard` et `--entity-filter`. Exécutez le programme d’installation avec `--help` pour afficher les instructions d’utilisation et les points d’entrée avancés. Une extraction du code source fournit également la référence complète de `scripts/provision.sh --help`.

### Keep credentials out of the command line

Pass credentials through owner-only files so they do not appear in shell history or child-process command lines. Each file must contain one credential line. A trailing line ending is accepted, but embedded line breaks are rejected.

Create a Home Assistant password file without echoing the password:

```bash
umask 077
read -rsp "Home Assistant password: " HA_PASSWORD; echo
printf '%s' "$HA_PASSWORD" > ha-password.txt
unset HA_PASSWORD
```

Use `--ha-pass-file ha-password.txt`, `--ha-token-file ha-token.txt` or `--mqtt-pass-file mqtt-password.txt`, then remove the file when provisioning finishes. The older `--ha-pass`, `--ha-token` and `--mqtt-pass` value flags remain available for compatibility, but their values are visible in the original shell command and process list. Do not use them on a shared computer.

These file options protect the credential on the computer running the installer. They do not encrypt the panel's management API. ha-paneld uses `http://<panel>:8888` on a trusted LAN, so MQTT credentials and Home Assistant tokens cross that connection as cleartext HTTP. Provision only from a trusted, segmented network. The Home Assistant password is sent from this computer to Home Assistant and never to the panel; use an `https://` Home Assistant URL so the login is encrypted in transit.

### Vérifier ou exporter une installation existante

These read-only operations do not download or install an APK:

```bash
# Export secret-inclusive settings.
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --export panel-config.json

# Check the existing installation without changing it.
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --verify
```

Protect an exported config like a credential. It contains settings and secrets, but it is not a complete panel backup.

## Provisionner le moteur de rendu de tableau de bord intégré

The easiest setup is on the panel's `:8888` **Configure** page. Under **Home Assistant connection**, enter the Home Assistant URL and choose **Browser sign-in**. Open the short-lived link in an administrator's browser and complete the sign-in, then select **Built-in renderer** in the Dashboard card. A long-lived access token remains available for automated or compatibility setup, but it is not needed for the normal interactive journey.

Le moteur de rendu intégré nécessite Home Assistant 2026.4.2 ou une version ultérieure, ainsi qu'une version actuelle et compatible d'Android System WebView. Consultez les [exigences du moteur de rendu et les réglages d'apparence](../built-in-renderer.md#requirements-and-compatibility).

For unattended provisioning, `--builtin` selects the renderer and signs in to Home Assistant from this computer, so nothing is typed on the panel:

```bash
# Username/password: the login happens here and mints a revocable refresh token.
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | \
  bash -s -- --provision 192.168.1.50:5555 --builtin \
  --ha-url https://homeassistant.example.com --ha-user your-user --ha-pass-file ha-password.txt

# Or use a long-lived access token instead of a login.
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | \
  bash -s -- --provision 192.168.1.50:5555 --builtin \
  --ha-url https://homeassistant.example.com --ha-token-file ha-token.txt
```

### Choisir le tableau de bord et le filtre d'entités

Une installation sans surveillance ne peut pas poser les questions de la configuration guidée. Par défaut, le panneau ouvre le tableau de bord par défaut du compte Home Assistant. Sur un compte volumineux, il s'agit souvent du tableau de bord disponible le plus lent, et un panneau ancien peut prendre beaucoup de temps pour l'afficher. `--home-dashboard` et `--entity-filter` répondent aux deux questions avant le premier rendu :

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | \
  bash -s -- --provision 192.168.1.50:5555 --builtin \
  --ha-url https://homeassistant.example.com --ha-user your-user --ha-pass-file ha-password.txt \
  --home-dashboard /panel-dashboard/kitchen --entity-filter on
```

`--home-dashboard` accepte un tableau de bord, un onglet précis d'un tableau de bord tel que `/panel-dashboard/kitchen`, ou `auto` pour suivre la valeur par défaut du compte. `--entity-filter` accepte `on` ou `off`. Son activation limite le flux d'états de Home Assistant aux entités utilisées par le tableau de bord, ce qui peut produire l'amélioration la plus importante sur un panneau ancien. Ces deux options s'appliquent au moteur de rendu intégré de ha-paneld ; elles nécessitent donc `--builtin` dans la même commande ou un panneau qui l'utilise déjà.

Fournir l'une ou l'autre option répond également à la question correspondante de la configuration guidée. Une modification ultérieure sur le panneau prévaut, et une option indiquée dans la commande prévaut sur un paquet `--restore` de la même commande. Si Home Assistant ne répertorie pas actuellement le tableau de bord indiqué, le programme d'installation l'enregistre et vous en informe ; cela permet de provisionner un panneau avant que le tableau de bord prévu existe. Un chemin que Home Assistant ne pourrait jamais résoudre est rejeté ; la configuration guidée pose donc toujours la question.

Pour une installation interactive, omettez `--builtin` et les arguments d'identification Home Assistant. Le programme d'installation affiche l'adresse de l'étape attendue par le panneau, généralement `http://<panel>:8888/setup`. Vous pouvez y poursuivre la configuration depuis un ordinateur ou un téléphone, ou appuyer sur **Configuration** sur le panneau. Les deux méthodes suivent le même parcours. Les panneaux existants ayant importé une session Companion conservent cette connexion comme solution de compatibilité.

Pour revenir à l'application Companion, sélectionnez le paquet Home Assistant Companion installé comme application de tableau de bord dans l'onglet Configurer. Le moteur de rendu intégré ne fournit ni Voice Assistant ni notifications ; conservez donc Companion lorsque ces fonctionnalités sont importantes.

## Recommencer la configuration d'un panneau

`--reset-config` erases ha-paneld's data and starts guided setup as a genuine first run:

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --reset-config
```

**Reset is irreversible and makes no backup.** If you may need the fullest supported recovery, stop and use the separate **Install → Backup** operation on the panel's `:8888` page. Verify that the downloaded `.hpb` is non-empty before continuing. Use `--export FILE` as a separate command first only when a settings-only export is sufficient.

Reset removes settings, learned entity data, proximity and ambient history, and the panel's on-panel revision history. It does not remove the app, root helper or any other app on the panel. The command asks you to type `RESET`; `--force` does not bypass that confirmation. Set `HAPANELD_RESET_CONFIRM=RESET` only when an unattended reset is deliberate.

Fleet updates refuse `--reset-config`. Reset panels one at a time.

The CLI `--restore FILE` and `--restore-fleet FILE` options import a config JSON export and require Python 3 on the computer running the installer. It does not accept an `.hpb` backup. Restore an `.hpb` through **Install → Restore** on the same panel page.

Pour comprendre la différence entre les exportations de configuration, les sauvegardes `.hpb` prises en charge et les copies automatiques de dernier recours de la base de données, consultez [Sécurité et récupération du provisionnement](../provisioning-safety.md#backups-and-recovery).

## Déployer des réglages partagés dans une flotte

Use `--restore-fleet` when several panels should share the same portable settings. First export the configured source panel:

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --export fleet-config.json
```

The export contains secrets, so store it like a credential. On each target, restore the portable settings and supply that panel's identity and credentials explicitly:

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | \
  bash -s -- --provision 192.168.1.51:5555 \
  --id kitchen --restore-fleet fleet-config.json \
  --mqtt tcp://192.168.1.10:1883 --mqtt-user ha-paneld --mqtt-pass-file mqtt-password.txt \
  --builtin --ha-url https://homeassistant.example.com --ha-token-file ha-token.txt
```

Repeat the command for each target, changing its address and `--id`. Add `--prerelease` before `--provision` when the panels should follow the newest published release, including a current release candidate.

`--restore-fleet` applies only **PORTABLE, non-secret** settings. It leaves the panel ID, other device-specific settings and every credential unchanged. If MQTT reports `auth-failed` after a bundle-only restore, check that `--mqtt-user` and `--mqtt-pass-file` were supplied. That result does not by itself mean the broker is down.

## Mise à jour de toute une flotte

Whole-fleet updates currently require a source checkout. From the repository root, run [`scripts/update-fleet.sh`](../../scripts/update-fleet.sh) with the panel addresses. The script downloads the release once, authenticates one APK before any worker starts, then installs, launches and verifies each panel:

<!-- source-checkout-only -->
```bash
scripts/update-fleet.sh --latest -- 192.168.1.10 192.168.1.11:5555

# At most four panels run at once by default. Set a smaller bounded pool when required.
scripts/update-fleet.sh --jobs 2 --latest -- 192.168.1.10 192.168.1.11:5555

# Follow the newest published release, whether stable or a release candidate.
scripts/update-fleet.sh --prerelease -- 192.168.1.10 192.168.1.11:5555

# A host list can also come from standard input.
printf '%s\n' 192.168.1.10 192.168.1.11 | scripts/update-fleet.sh --latest
```

Four panels run concurrently by default. Set `--jobs 1..32` to change the bounded pool, or use `HAPANELD_FLEET_JOBS` as the default when `--jobs` is omitted. The command-line option takes precedence.

Each worker prints the panel's provisioning guidance, but fleet updates never accept hardware-profile recommendations automatically. Options that describe one panel are refused before any worker starts. Run `--reset-config`, `--export FILE`, `--id` and device-specific `--restore FILE` through `scripts/provision.sh` one panel at a time. `--restore-fleet FILE` is the supported way to apply portable settings across several panels.

Fleet updates require Android SDK Build-Tools containing `apksigner` and either `aapt` or `aapt2`. The wrapper checks the selected APK before the workers start, and each panel's provisioner verifies its input again before changing the panel. The [technical provisioning page](../provisioning-safety.md#fleet-update-boundaries) records the signer rules and remaining fleet safeguards.

## Accès exceptionnel et modes de sécurité

Hardware-profile recommendations are report-only. Choosing a profile is not consent to disable packages, persist ADB, install privileged software or change display settings. The old `--no-tame` option remains as a compatibility no-op. Packages already present in the configured tame blocklist still reapply at boot.

### Solution de secours Shizuku pour les panneaux non rootés

[Shizuku](https://shizuku.rikka.app/) is a separate open-source app whose service runs with Android's shell identity (UID 2000). ha-paneld can use it as a last resort on a genuinely unrooted panel whose profile names a concrete supported use. Shell is not root: operations that need genuine root still fail closed, and none of the root-only hardware features become available. Do not set it up on a panel that already has working `su` or the root helper.

`provision.sh --shizuku` downloads the curated Shizuku Manager, verifies its exact checksum and starts the service, but it cannot approve ha-paneld. Approval happens on the panel, in **Configure → toolbar overflow → Enhanced access → Enable**, and has no remote path through the installer, the web UI, MQTT, a backup restore or a fleet push. The consent is stored only on the panel and is never exported or restored; replacing the Manager, revoking the permission or stopping the service makes the dependent operations fail closed. A service started through ADB normally needs to be started again after a reboot.

[Hardened security mode](../security-mode.md) requires physical access for selected high-impact remote actions. Someone must approve them on the panel's screen, and they cannot be approved remotely. It is enabled only from the panel and is not copied by provisioning or a fleet update. Network ADB cannot coexist with Hardened security mode, so return the panel to Relaxed mode locally before an ADB-based installation or fleet update.

## Provisionnement d'un build local

Ce workflow de développement nécessite une extraction du code source. Compilez l'APK depuis la racine du dépôt, puis fournissez-le à l'outil de provisionnement :

<!-- source-checkout-only -->
```bash
./gradlew :app:assembleDebug
scripts/provision.sh <panel-ip:5555> \
  --apk app/build/outputs/apk/debug/app-debug.apk \
  --allow-unsigned-helper
```

`--allow-unsigned-helper` acknowledges that the helper embedded in a local APK is controlled by the local builder instead of authenticated as a published release. It is required whenever a local APK is sent to a panel with a usable root or helper route, including the first helper installation. A genuinely unrooted panel skips helper work. Official `--latest` and `--prerelease` installs authenticate the release helper automatically and do not use this flag.

Android SDK Build-Tools containing `apksigner` are required to update a panel that already has ha-paneld installed, whatever the APK source, because the provisioner compares the installed and candidate signers before it changes anything. A first installation on a panel without ha-paneld does not need them. Local `--apk` provisioning additionally needs either `aapt` or `aapt2`. Before any upgrade backup or panel change, the provisioner verifies the package and exactly one valid signer. Self-built APKs may use the builder's consistent signing key. Add `--require-release-signer` only when the local file should carry the official release certificate.

Le plan adapté au profil indique quand les pilotes sélectionnés nécessitent l'assistant. De nombreux panneaux rk3576 et PX30 peuvent exécuter `su` dans l'application, tandis que les panneaux rootés exécutés dans un bac à sable utilisent l'assistant pour les opérations privilégiées. Un panneau réellement non rooté conserve les fonctionnalités Android standard, sauf si son profil déclare une solution distincte et documentée pour une fonctionnalité précise.

## Amorçage d'adb

On some Tuya TPA10 and Smatek panels, adb initially works only over USB. Connect the USB cable, then enable network adb before running the normal provisioning command:

```bash
adb devices                   # accept the on-screen RSA prompt if shown
adb root                      # if this firmware supports it
adb tcpip 5555                # expose adb on the network; this resets on reboot
adb connect 192.168.1.50:5555 # replace this address with the panel's address
```

If a panel has no adb but does have a browser or file manager, download the [release APK](https://github.com/maxlyth/ha-paneld/releases/latest), allow installation from that app and tap the APK. Grant the required permissions by hand under Android Settings, then follow ha-paneld's setup screen. This route is not available on a locked-down panel with no browser or file manager.

## Autorisations Android

| Autorisation | Utilisation | Octroi lors du provisionnement |
|------------|----------|--------------------|
| `POST_NOTIFICATIONS` | Notification du service de premier plan | Autorisation d’exécution ou `pm grant` |
| `WRITE_SETTINGS` | Luminosité de l’écran | `appops set <pkg> WRITE_SETTINGS allow` |
| `SYSTEM_ALERT_WINDOW` | Barre de navigation logicielle | `appops set <pkg> SYSTEM_ALERT_WINDOW allow` |
| Capture des touches d’accessibilité | Événements des boutons matériels | `settings put secure enabled_accessibility_services …` |

Screen-off does not use Android device administrator. The active hardware profile may power off a physical backlight, send Android sleep and wake key events, or fall back to brightness zero. The profile route determines whether root, the authenticated helper or standard Android access is required. Builds up to 0.5.0 included an optional device administrator; 0.5.1 removed it. See the [build and signing notes](../local-builds.md) if you are upgrading from a build where it was enabled.
