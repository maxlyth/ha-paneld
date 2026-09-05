> [!IMPORTANT]
> Questo documento è generato automaticamente e verificato mediante controlli incrociati automatici, ma non è stato rivisto sistematicamente da persone che parlano questa lingua. La documentazione in inglese fa fede. [Leggi la fonte in inglese](../provisioning.md) oppure [apri una segnalazione per correggere la traduzione](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Provisioning e aggiornamenti del parco dispositivi

Il programma di installazione scaricabile può configurare un pannello tramite adb senza eseguire il checkout del repository. Installa o aggiorna ha-paneld, applica le impostazioni richieste, concede le autorizzazioni Android disponibili tramite adb e verifica che l'app sia in esecuzione prima di terminare. Questa pagina descrive prima la normale procedura per un singolo pannello, quindi le impostazioni condivise e gli aggiornamenti dell'intero parco dispositivi.

Per la procedura di installazione interattiva più breve, consulta il [README](README.md#installazione). Per informazioni sulle misure di sicurezza relative a database, pacchetti e strumenti ausiliari alla base di questi comandi, consulta [Sicurezza e ripristino del provisioning](../provisioning-safety.md).

> [!NOTE]
> Questi sono comandi `bash` e `adb`. In **Windows**, eseguili in **Git Bash** (incluso in [Git for Windows](https://gitforwindows.org/)) o **WSL**, non in PowerShell, con `adb` incluso nel `PATH` (`winget install Google.PlatformTools`). In macOS e Linux, eseguili così come sono scritti.

## Installare o aggiornare un pannello

Sostituisci l'indirizzo di esempio con quello del pannello, quindi incolla il comando completo in Git Bash, WSL, Terminale di macOS o un terminale Linux:

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555
```

The installer downloads and authenticates the matching signed release and provisioner. It connects to the panel, installs the ABI-matched root helper where the firmware permits it, installs the APK, grants the required permissions, starts ha-paneld and finishes with a self-check. The running app then reports any guidance derived from the active hardware profile and live panel state.

Provisioning is idempotent. If a step is interrupted or fails, correct the problem and run the same command again. Optional or manual recommendations remain visible without turning a successful installation into a failure.

### Impostare l'identità e le connessioni del pannello

Aggiungi le opzioni di provisioning dopo l'indirizzo. Questo esempio assegna un ID pannello e un broker MQTT:

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --id kitchen --mqtt tcp://192.168.1.10:1883
```

Usa `--prerelease` prima di `--provision` per seguire la versione pubblicata più recente, incluse le versioni candidate. Una versione stabile più recente ha comunque la precedenza. Senza questa opzione, il programma di installazione segue solo le versioni stabili.

Le opzioni comuni includono `--force`, `--builtin`, `--ha-url`, `--ha-token-file`, `--ha-user`, `--ha-pass-file`, `--home-dashboard` e `--entity-filter`. Esegui il programma di installazione con `--help` per visualizzare le istruzioni d'uso e i punti di ingresso avanzati. Un checkout del codice sorgente fornisce anche la documentazione completa di `scripts/provision.sh --help`.

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

### Controllare o esportare un'installazione esistente

These read-only operations do not download or install an APK:

```bash
# Export secret-inclusive settings.
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --export panel-config.json

# Check the existing installation without changing it.
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --verify
```

Protect an exported config like a credential. It contains settings and secrets, but it is not a complete panel backup.

## Configurare il renderer integrato della dashboard

The easiest setup is on the panel's `:8888` **Configure** page. Under **Home Assistant connection**, enter the Home Assistant URL and choose **Browser sign-in**. Open the short-lived link in an administrator's browser and complete the sign-in, then select **Built-in renderer** in the Dashboard card. A long-lived access token remains available for automated or compatibility setup, but it is not needed for the normal interactive journey.

Il renderer integrato richiede Home Assistant 2026.4.2 o versioni successive e una versione attuale e compatibile di Android System WebView. Consulta i [requisiti del renderer e i controlli dell'aspetto](../built-in-renderer.md#requirements-and-compatibility).

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

### Scegliere la dashboard e il filtro delle entità

Un'installazione non presidiata non può rispondere alle domande della configurazione guidata. Per impostazione predefinita, il pannello apre la dashboard predefinita dell'account Home Assistant. In un account di grandi dimensioni, questa è spesso la dashboard più lenta disponibile e un pannello meno recente può impiegare molto tempo per visualizzarla. `--home-dashboard` e `--entity-filter` rispondono a entrambe le domande prima del primo rendering:

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | \
  bash -s -- --provision 192.168.1.50:5555 --builtin \
  --ha-url https://homeassistant.example.com --ha-user your-user --ha-pass-file ha-password.txt \
  --home-dashboard /panel-dashboard/kitchen --entity-filter on
```

`--home-dashboard` accetta una dashboard, una scheda specifica della dashboard come `/panel-dashboard/kitchen` oppure `auto` per usare l'impostazione predefinita dell'account. `--entity-filter` accetta `on` o `off`. L'attivazione limita il flusso degli stati di Home Assistant alle entità utilizzate dalla dashboard, producendo potenzialmente il miglioramento più significativo su un pannello meno recente. Entrambe le opzioni si applicano al renderer integrato di ha-paneld, quindi richiedono `--builtin` nello stesso comando oppure un pannello che lo utilizzi già.

Specificando una delle due opzioni si risponde anche alla domanda corrispondente della configurazione guidata. Una modifica successiva sul pannello ha la precedenza e un'opzione specificata nel comando prevale su un pacchetto `--restore` nello stesso comando. Se Home Assistant non elenca attualmente la dashboard indicata, il programma di installazione la salva e ti informa; ciò consente di eseguire il provisioning di un pannello prima che esista la dashboard prevista. Un percorso che Home Assistant non potrebbe mai risolvere viene rifiutato, quindi la configurazione guidata continua a porre la domanda.

Per un'installazione interattiva, ometti `--builtin` e gli argomenti delle credenziali di Home Assistant. Il programma di installazione stampa l'indirizzo del passaggio che il pannello sta aspettando, in genere `http://<panel>:8888/setup`. Puoi continuare da lì da un computer o da un telefono, oppure toccare **Configura** sul pannello. Entrambi seguono lo stesso percorso. I pannelli esistenti che hanno importato una sessione Companion mantengono quell'accesso come percorso di compatibilità.

Per tornare all'app Companion, seleziona il pacchetto Home Assistant Companion installato come app Dashboard nella scheda Configura. Il renderer integrato non fornisce Voice Assistant né notifiche, quindi mantieni Companion dove queste funzionalità sono necessarie.

## Ricominciare la configurazione di un pannello

`--reset-config` erases ha-paneld's data and starts guided setup as a genuine first run:

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --reset-config
```

**Reset is irreversible and makes no backup.** If you may need the fullest supported recovery, stop and use the separate **Install → Backup** operation on the panel's `:8888` page. Verify that the downloaded `.hpb` is non-empty before continuing. Use `--export FILE` as a separate command first only when a settings-only export is sufficient.

Reset removes settings, learned entity data, proximity and ambient history, and the panel's on-panel revision history. It does not remove the app, root helper or any other app on the panel. The command asks you to type `RESET`; `--force` does not bypass that confirmation. Set `HAPANELD_RESET_CONFIRM=RESET` only when an unattended reset is deliberate.

Fleet updates refuse `--reset-config`. Reset panels one at a time.

The CLI `--restore FILE` and `--restore-fleet FILE` options import a config JSON export and require Python 3 on the computer running the installer. It does not accept an `.hpb` backup. Restore an `.hpb` through **Install → Restore** on the same panel page.

Per la distinzione tra esportazioni della configurazione, backup `.hpb` supportati e copie automatiche di emergenza del database, consulta [Sicurezza e recupero del provisioning](../provisioning-safety.md#backups-and-recovery).

## Distribuire impostazioni condivise a un parco dispositivi

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

## Aggiornamento di un'intera flotta

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

## Accesso eccezionale e modalità di sicurezza

Hardware-profile recommendations are report-only. Choosing a profile is not consent to disable packages, persist ADB, install privileged software or change display settings. The old `--no-tame` option remains as a compatibility no-op. Packages already present in the configured tame blocklist still reapply at boot.

### Shizuku come soluzione di ripiego per i pannelli senza root

[Shizuku](https://shizuku.rikka.app/) is a separate open-source app whose service runs with Android's shell identity (UID 2000). ha-paneld can use it as a last resort on a genuinely unrooted panel whose profile names a concrete supported use. Shell is not root: operations that need genuine root still fail closed, and none of the root-only hardware features become available. Do not set it up on a panel that already has working `su` or the root helper.

`provision.sh --shizuku` downloads the curated Shizuku Manager, verifies its exact checksum and starts the service, but it cannot approve ha-paneld. Approval happens on the panel, in **Configure → toolbar overflow → Enhanced access → Enable**, and has no remote path through the installer, the web UI, MQTT, a backup restore or a fleet push. The consent is stored only on the panel and is never exported or restored; replacing the Manager, revoking the permission or stopping the service makes the dependent operations fail closed. A service started through ADB normally needs to be started again after a reboot.

[Hardened security mode](../security-mode.md) requires physical access for selected high-impact remote actions. Someone must approve them on the panel's screen, and they cannot be approved remotely. It is enabled only from the panel and is not copied by provisioning or a fleet update. Network ADB cannot coexist with Hardened security mode, so return the panel to Relaxed mode locally before an ADB-based installation or fleet update.

## Provisioning di una build locale

Questo flusso di lavoro per sviluppatori richiede un checkout del codice sorgente. Compila l'APK dalla radice del repository, quindi forniscilo al provisioner:

<!-- source-checkout-only -->
```bash
./gradlew :app:assembleDebug
scripts/provision.sh <panel-ip:5555> \
  --apk app/build/outputs/apk/debug/app-debug.apk \
  --allow-unsigned-helper
```

`--allow-unsigned-helper` acknowledges that the helper embedded in a local APK is controlled by the local builder instead of authenticated as a published release. It is required whenever a local APK is sent to a panel with a usable root or helper route, including the first helper installation. A genuinely unrooted panel skips helper work. Official `--latest` and `--prerelease` installs authenticate the release helper automatically and do not use this flag.

Android SDK Build-Tools containing `apksigner` are required to update a panel that already has ha-paneld installed, whatever the APK source, because the provisioner compares the installed and candidate signers before it changes anything. A first installation on a panel without ha-paneld does not need them. Local `--apk` provisioning additionally needs either `aapt` or `aapt2`. Before any upgrade backup or panel change, the provisioner verifies the package and exactly one valid signer. Self-built APKs may use the builder's consistent signing key. Add `--require-release-signer` only when the local file should carry the official release certificate.

Il piano basato sul profilo segnala quando i driver selezionati richiedono l'helper. Molti pannelli rk3576 e PX30 possono eseguire `su` all'interno dell'app, mentre i pannelli con root e sandbox usano l'helper per le operazioni con privilegi elevati. Un pannello realmente privo di root continua a usare le funzionalità Android standard, a meno che il relativo profilo non dichiari un'alternativa documentata separatamente per una specifica funzionalità.

## Bootstrap di adb

On some Tuya TPA10 and Smatek panels, adb initially works only over USB. Connect the USB cable, then enable network adb before running the normal provisioning command:

```bash
adb devices                   # accept the on-screen RSA prompt if shown
adb root                      # if this firmware supports it
adb tcpip 5555                # expose adb on the network; this resets on reboot
adb connect 192.168.1.50:5555 # replace this address with the panel's address
```

If a panel has no adb but does have a browser or file manager, download the [release APK](https://github.com/maxlyth/ha-paneld/releases/latest), allow installation from that app and tap the APK. Grant the required permissions by hand under Android Settings, then follow ha-paneld's setup screen. This route is not available on a locked-down panel with no browser or file manager.

## Autorizzazioni Android

| Autorizzazione | Utilizzata per | Concessione durante il provisioning |
|------------|----------|--------------------|
| `POST_NOTIFICATIONS` | Notifica del servizio in primo piano | Autorizzazione di runtime o `pm grant` |
| `WRITE_SETTINGS` | Luminosità dello schermo | `appops set <pkg> WRITE_SETTINGS allow` |
| `SYSTEM_ALERT_WINDOW` | Barra di navigazione software | `appops set <pkg> SYSTEM_ALERT_WINDOW allow` |
| Acquisizione dei tasti di accessibilità | Eventi dei pulsanti hardware | `settings put secure enabled_accessibility_services …` |

Screen-off does not use Android device administrator. The active hardware profile may power off a physical backlight, send Android sleep and wake key events, or fall back to brightness zero. The profile route determines whether root, the authenticated helper or standard Android access is required. Builds up to 0.5.0 included an optional device administrator; 0.5.1 removed it. See the [build and signing notes](../local-builds.md) if you are upgrading from a build where it was enabled.
