> [!IMPORTANT]
> Dieses Dokument wurde maschinell erstellt und automatisch gegengeprüft, jedoch nicht systematisch von Personen geprüft, die diese Sprache sprechen. Die englische Dokumentation ist maßgeblich. [Englisches Original lesen](../provisioning.md) oder [ein Issue zur Übersetzungskorrektur öffnen](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Bereitstellung und Flottenaktualisierungen

Das herunterladbare Installationsprogramm kann ein Panel über adb einrichten, ohne dass das Repository ausgecheckt werden muss. Es installiert oder aktualisiert ha-paneld, wendet die angeforderten Einstellungen an, gewährt die über adb verfügbaren Android-Berechtigungen und überprüft vor dem Abschluss die laufende App. Diese Seite beginnt mit dem üblichen Ablauf für ein einzelnes Panel und behandelt anschließend gemeinsame Einstellungen und Aktualisierungen der gesamten Flotte.

Die kürzeste interaktive Installation finden Sie in der [README](README.md#installation). Informationen zu den Schutzvorkehrungen für Datenbank, Paket und Hilfsprogramm hinter diesen Befehlen finden Sie unter [Sicherheit und Wiederherstellung bei der Bereitstellung](../provisioning-safety.md).

> [!NOTE]
> Dies sind `bash`- und `adb`-Befehle. Führen Sie sie unter **Windows** in **Git Bash** (aus [Git for Windows](https://gitforwindows.org/)) oder **WSL** aus, nicht in PowerShell. Dabei muss sich `adb` im `PATH` befinden (`winget install Google.PlatformTools`). Unter macOS und Linux können sie unverändert ausgeführt werden.

## Ein Panel installieren oder aktualisieren

Ersetzen Sie die Beispieladresse durch die Adresse des Panels und fügen Sie anschließend den vollständigen Befehl in Git Bash, WSL, das macOS-Terminal oder ein Linux-Terminal ein:

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555
```

The installer downloads and authenticates the matching signed release and provisioner. It connects to the panel, installs the ABI-matched root helper where the firmware permits it, installs the APK, grants the required permissions, starts ha-paneld and finishes with a self-check. The running app then reports any guidance derived from the active hardware profile and live panel state.

Provisioning is idempotent. If a step is interrupted or fails, correct the problem and run the same command again. Optional or manual recommendations remain visible without turning a successful installation into a failure.

### Panelidentität und Verbindungen festlegen

Fügen Sie die Bereitstellungsoptionen nach der Adresse hinzu. Dieses Beispiel weist eine Panel-ID und einen MQTT-Broker zu:

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --id kitchen --mqtt tcp://192.168.1.10:1883
```

Verwenden Sie `--prerelease` vor `--provision`, um dem neuesten veröffentlichten Release einschließlich Release Candidates zu folgen. Ein neueres stabiles Release hat weiterhin Vorrang. Ohne diese Option folgt das Installationsprogramm nur stabilen Releases.

Zu den häufig verwendeten Optionen gehören `--force`, `--builtin`, `--ha-url`, `--ha-token-file`, `--ha-user`, `--ha-pass-file`, `--home-dashboard` und `--entity-filter`. Führen Sie das Installationsprogramm mit `--help` aus, um Hinweise zur Verwendung und die erweiterten Einstiegspunkte anzuzeigen. Ein Quellcode-Checkout stellt außerdem über `scripts/provision.sh --help` die vollständige Referenz bereit.

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

### Vorhandene Installation überprüfen oder exportieren

These read-only operations do not download or install an APK:

```bash
# Export secret-inclusive settings.
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --export panel-config.json

# Check the existing installation without changing it.
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --verify
```

Protect an exported config like a credential. It contains settings and secrets, but it is not a complete panel backup.

## Den integrierten Dashboard-Renderer bereitstellen

The easiest setup is on the panel's `:8888` **Configure** page. Under **Home Assistant connection**, enter the Home Assistant URL and choose **Browser sign-in**. Open the short-lived link in an administrator's browser and complete the sign-in, then select **Built-in renderer** in the Dashboard card. A long-lived access token remains available for automated or compatibility setup, but it is not needed for the normal interactive journey.

Der integrierte Renderer erfordert Home Assistant 2026.4.2 oder neuer sowie eine kompatible aktuelle Version von Android System WebView. Weitere Informationen finden Sie unter [Anforderungen und Darstellungsoptionen des Renderers](built-in-renderer.md#anforderungen-und-kompatibilität).

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

### Dashboard und Entitätsfilter auswählen

Eine unbeaufsichtigte Installation kann die Fragen der geführten Einrichtung nicht stellen. Standardmäßig öffnet das Panel das Standard-Dashboard des Home Assistant-Kontos. Bei einem großen Konto ist dies häufig das langsamste verfügbare Dashboard, und ein älteres Panel kann für die Darstellung sehr lange benötigen. `--home-dashboard` und `--entity-filter` beantworten beide Fragen vor der ersten Darstellung:

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | \
  bash -s -- --provision 192.168.1.50:5555 --builtin \
  --ha-url https://homeassistant.example.com --ha-user your-user --ha-pass-file ha-password.txt \
  --home-dashboard /panel-dashboard/kitchen --entity-filter on
```

`--home-dashboard` akzeptiert ein Dashboard, einen bestimmten Dashboard-Tab wie `/panel-dashboard/kitchen` oder `auto`, um dem Standardwert des Kontos zu folgen. `--entity-filter` akzeptiert `on` oder `off`. Durch das Aktivieren wird der Zustandsdatenstrom von Home Assistant auf die vom Dashboard verwendeten Entitäten beschränkt. Dies kann bei einem älteren Panel den größten Unterschied bewirken. Beide Optionen gelten für den integrierten Renderer von ha-paneld. Daher erfordern sie `--builtin` im selben Befehl oder ein Panel, das diesen Renderer bereits verwendet.

Durch die Angabe einer der beiden Optionen wird auch die entsprechende Frage der geführten Einrichtung beantwortet. Eine spätere Änderung auf dem Panel hat Vorrang, und eine im Befehl angegebene Option hat Vorrang vor einem `--restore`-Paket im selben Befehl. Wenn Home Assistant das angegebene Dashboard derzeit nicht auflistet, speichert das Installationsprogramm es und informiert Sie darüber. So kann ein Panel bereitgestellt werden, bevor das dafür vorgesehene Dashboard vorhanden ist. Ein Pfad, den Home Assistant niemals auflösen könnte, wird abgelehnt, sodass die geführte Einrichtung weiterhin danach fragt.

Lassen Sie bei einer interaktiven Installation `--builtin` und die Argumente für die Home Assistant-Anmeldedaten weg. Das Installationsprogramm gibt die Adresse für den Schritt aus, auf den das Panel wartet, normalerweise `http://<panel>:8888/setup`. Sie können dort von einem Computer oder Smartphone aus fortfahren oder auf dem Panel auf **Einrichten** tippen. Beide Wege führen durch denselben Ablauf. Bei vorhandenen Panels, die eine Companion-Sitzung importiert haben, bleibt diese Anmeldung als Kompatibilitätsoption erhalten.

Um zur Companion-App zurückzukehren, wählen Sie im Tab „Konfiguration“ das installierte Home Assistant Companion-Paket als Dashboard-App aus. Der integrierte Renderer unterstützt weder Voice Assistant noch Benachrichtigungen. Verwenden Sie daher weiterhin Companion, wenn diese Funktionen benötigt werden.

## Ein Panel neu einrichten

`--reset-config` erases ha-paneld's data and starts guided setup as a genuine first run:

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --reset-config
```

**Reset is irreversible and makes no backup.** If you may need the fullest supported recovery, stop and use the separate **Install → Backup** operation on the panel's `:8888` page. Verify that the downloaded `.hpb` is non-empty before continuing. Use `--export FILE` as a separate command first only when a settings-only export is sufficient.

Reset removes settings, learned entity data, proximity and ambient history, and the panel's on-panel revision history. It does not remove the app, root helper or any other app on the panel. The command asks you to type `RESET`; `--force` does not bypass that confirmation. Set `HAPANELD_RESET_CONFIRM=RESET` only when an unattended reset is deliberate.

Fleet updates refuse `--reset-config`. Reset panels one at a time.

The CLI `--restore FILE` and `--restore-fleet FILE` options import a config JSON export and require Python 3 on the computer running the installer. It does not accept an `.hpb` backup. Restore an `.hpb` through **Install → Restore** on the same panel page.

Informationen zum Unterschied zwischen Konfigurationsexporten, unterstützten `.hpb`-Sicherungen und automatischen Datenbankkopien für Notfälle finden Sie unter [Sicherheit und Wiederherstellung bei der Bereitstellung](../provisioning-safety.md#backups-and-recovery).

## Gemeinsame Einstellungen auf einer Flotte bereitstellen

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

## Eine gesamte Flotte aktualisieren

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

## Zugriff in Ausnahmefällen und Sicherheitsmodi

Hardware-profile recommendations are report-only. Choosing a profile is not consent to disable packages, persist ADB, install privileged software or change display settings. The old `--no-tame` option remains as a compatibility no-op. Packages already present in the configured tame blocklist still reapply at boot.

### Shizuku-Ausweichlösung für Panels ohne Root-Zugriff

[Shizuku](https://shizuku.rikka.app/) is a separate open-source app whose service runs with Android's shell identity (UID 2000). ha-paneld can use it as a last resort on a genuinely unrooted panel whose profile names a concrete supported use. Shell is not root: operations that need genuine root still fail closed, and none of the root-only hardware features become available. Do not set it up on a panel that already has working `su` or the root helper.

`provision.sh --shizuku` downloads the curated Shizuku Manager, verifies its exact checksum and starts the service, but it cannot approve ha-paneld. Approval happens on the panel, in **Configure → toolbar overflow → Enhanced access → Enable**, and has no remote path through the installer, the web UI, MQTT, a backup restore or a fleet push. The consent is stored only on the panel and is never exported or restored; replacing the Manager, revoking the permission or stopping the service makes the dependent operations fail closed. A service started through ADB normally needs to be started again after a reboot.

[Hardened security mode](../security-mode.md) requires physical access for selected high-impact remote actions. Someone must approve them on the panel's screen, and they cannot be approved remotely. It is enabled only from the panel and is not copied by provisioning or a fleet update. Network ADB cannot coexist with Hardened security mode, so return the panel to Relaxed mode locally before an ADB-based installation or fleet update.

## Einen lokalen Build bereitstellen

Dieser Entwickler-Workflow erfordert einen Checkout des Quellcodes. Erstellen Sie die APK im Stammverzeichnis des Repositorys und übergeben Sie sie anschließend an die Bereitstellungsroutine:

<!-- source-checkout-only -->
```bash
./gradlew :app:assembleDebug
scripts/provision.sh <panel-ip:5555> \
  --apk app/build/outputs/apk/debug/app-debug.apk \
  --allow-unsigned-helper
```

`--allow-unsigned-helper` acknowledges that the helper embedded in a local APK is controlled by the local builder instead of authenticated as a published release. It is required whenever a local APK is sent to a panel with a usable root or helper route, including the first helper installation. A genuinely unrooted panel skips helper work. Official `--latest` and `--prerelease` installs authenticate the release helper automatically and do not use this flag.

Android SDK Build-Tools containing `apksigner` are required to update a panel that already has ha-paneld installed, whatever the APK source, because the provisioner compares the installed and candidate signers before it changes anything. A first installation on a panel without ha-paneld does not need them. Local `--apk` provisioning additionally needs either `aapt` or `aapt2`. Before any upgrade backup or panel change, the provisioner verifies the package and exactly one valid signer. Self-built APKs may use the builder's consistent signing key. Add `--require-release-signer` only when the local file should carry the official release certificate.

Der profilabhängige Plan meldet, wenn ausgewählte Treiber den Helfer benötigen. Viele rk3576- und PX30-Panels können `su` innerhalb der App ausführen, während gerootete Panels mit Sandbox den Helfer für privilegierte Vorgänge verwenden. Ein tatsächlich nicht gerootetes Panel arbeitet mit den standardmäßigen Android-Funktionen weiter, sofern sein Profil nicht für eine bestimmte Funktion eine separat dokumentierte Alternative angibt.

## adb-Zugriff initialisieren

On some Tuya TPA10 and Smatek panels, adb initially works only over USB. Connect the USB cable, then enable network adb before running the normal provisioning command:

```bash
adb devices                   # accept the on-screen RSA prompt if shown
adb root                      # if this firmware supports it
adb tcpip 5555                # expose adb on the network; this resets on reboot
adb connect 192.168.1.50:5555 # replace this address with the panel's address
```

If a panel has no adb but does have a browser or file manager, download the [release APK](https://github.com/maxlyth/ha-paneld/releases/latest), allow installation from that app and tap the APK. Grant the required permissions by hand under Android Settings, then follow ha-paneld's setup screen. This route is not available on a locked-down panel with no browser or file manager.

## Android-Berechtigungen

| Berechtigung | Verwendet für | Erteilung bei der Bereitstellung |
|------------|----------|--------------------|
| `POST_NOTIFICATIONS` | Benachrichtigung des Vordergrunddienstes | Laufzeitberechtigung oder `pm grant` |
| `WRITE_SETTINGS` | Bildschirmhelligkeit | `appops set <pkg> WRITE_SETTINGS allow` |
| `SYSTEM_ALERT_WINDOW` | Software-Navigationsleiste | `appops set <pkg> SYSTEM_ALERT_WINDOW allow` |
| Erfassung von Bedienungshilfetasten | Ereignisse von Hardwaretasten | `settings put secure enabled_accessibility_services …` |

Screen-off does not use Android device administrator. The active hardware profile may power off a physical backlight, send Android sleep and wake key events, or fall back to brightness zero. The profile route determines whether root, the authenticated helper or standard Android access is required. Builds up to 0.5.0 included an optional device administrator; 0.5.1 removed it. See the [build and signing notes](../local-builds.md) if you are upgrading from a build where it was enabled.
