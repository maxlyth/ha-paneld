> [!IMPORTANT]
> Este documento se genera automáticamente y se somete a comprobaciones cruzadas automáticas, pero no ha sido revisado sistemáticamente por hablantes de este idioma. La documentación en inglés es la fuente de referencia. [Consulta la fuente en inglés](../provisioning.md) o [abre una incidencia para corregir la traducción](https://github.com/maxlyth/ha-paneld/issues/new?template=translation_correction.yml).

# Aprovisionamiento y actualizaciones de flota

El instalador descargable puede configurar un panel mediante adb sin tener que obtener el repositorio. Instala o actualiza ha-paneld, aplica los ajustes solicitados, concede los permisos de Android disponibles mediante adb y verifica la aplicación en ejecución antes de finalizar. Esta página comienza con el procedimiento habitual para un solo panel y después explica los ajustes compartidos y las actualizaciones de toda la flota.

Para realizar la instalación interactiva más breve, consulta el [README](README.md#instalación). Para obtener información sobre las medidas de protección de la base de datos, los paquetes y las herramientas auxiliares en las que se basan estos comandos, consulta [Seguridad y recuperación del aprovisionamiento](../provisioning-safety.md).

> [!NOTE]
> Estos son comandos de `bash` y `adb`. En **Windows**, ejecútalos en **Git Bash** (incluido en [Git for Windows](https://gitforwindows.org/)) o **WSL**, no en PowerShell, con `adb` en `PATH` (`winget install Google.PlatformTools`). En macOS y Linux, ejecútalos tal como aparecen.

## Instalar o actualizar un panel

Sustituye la dirección de ejemplo por la dirección del panel y, a continuación, pega el comando completo en Git Bash, WSL, Terminal de macOS o un terminal de Linux:

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555
```

The installer downloads and authenticates the matching signed release and provisioner. It connects to the panel, installs the ABI-matched root helper where the firmware permits it, installs the APK, grants the required permissions, starts ha-paneld and finishes with a self-check. The running app then reports any guidance derived from the active hardware profile and live panel state.

Provisioning is idempotent. If a step is interrupted or fails, correct the problem and run the same command again. Optional or manual recommendations remain visible without turning a successful installation into a failure.

### Establecer la identidad y las conexiones del panel

Añade las opciones de aprovisionamiento después de la dirección. Este ejemplo asigna un ID de panel y un bróker MQTT:

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --id kitchen --mqtt tcp://192.168.1.10:1883
```

Usa `--prerelease` antes de `--provision` para seguir la versión publicada más reciente, incluidas las versiones candidatas. Una versión estable más reciente sigue teniendo prioridad. Sin esta opción, el instalador solo sigue las versiones estables.

Entre las opciones habituales se incluyen `--force`, `--builtin`, `--ha-url`, `--ha-token-file`, `--ha-user`, `--ha-pass-file`, `--home-dashboard` y `--entity-filter`. Ejecuta el instalador con `--help` para consultar el uso y los puntos de entrada avanzados. Si has obtenido el repositorio de código fuente, también dispondrás de la referencia completa de `scripts/provision.sh --help`.

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

### Comprobar o exportar una instalación existente

These read-only operations do not download or install an APK:

```bash
# Export secret-inclusive settings.
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --export panel-config.json

# Check the existing installation without changing it.
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --verify
```

Protect an exported config like a credential. It contains settings and secrets, but it is not a complete panel backup.

## Aprovisionar el renderizador integrado de paneles de control

The easiest setup is on the panel's `:8888` **Configure** page. Under **Home Assistant connection**, enter the Home Assistant URL and choose **Browser sign-in**. Open the short-lived link in an administrator's browser and complete the sign-in, then select **Built-in renderer** in the Dashboard card. A long-lived access token remains available for automated or compatibility setup, but it is not needed for the normal interactive journey.

El renderizador integrado requiere Home Assistant 2026.4.2 o una versión posterior y una versión actual compatible de Android System WebView. Consulta los [requisitos del renderizador y los controles de apariencia](../built-in-renderer.md#requirements-and-compatibility).

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

### Elegir el panel de control y el filtro de entidades

Una instalación desatendida no puede formular las preguntas de la configuración guiada. De forma predeterminada, el panel abre el panel de control predeterminado de la cuenta de Home Assistant. En una cuenta grande, este suele ser el panel de control más lento disponible y un panel antiguo puede tardar mucho en mostrarlo. `--home-dashboard` y `--entity-filter` responden ambas preguntas antes de la primera representación:

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | \
  bash -s -- --provision 192.168.1.50:5555 --builtin \
  --ha-url https://homeassistant.example.com --ha-user your-user --ha-pass-file ha-password.txt \
  --home-dashboard /panel-dashboard/kitchen --entity-filter on
```

`--home-dashboard` acepta un panel de control, una pestaña específica de un panel de control, como `/panel-dashboard/kitchen`, o `auto` para usar el valor predeterminado de la cuenta. `--entity-filter` acepta `on` o `off`. Al activarlo, se limita el flujo de estados de Home Assistant a las entidades que usa el panel de control, lo que puede suponer la mayor mejora en un panel antiguo. Ambas opciones se aplican al renderizador integrado de ha-paneld, por lo que requieren `--builtin` en el mismo comando o un panel que ya lo esté usando.

Proporcionar cualquiera de las opciones también responde la pregunta correspondiente de la configuración guiada. Un cambio posterior realizado en el panel tiene prioridad, y una opción indicada en el comando tiene prioridad sobre un paquete `--restore` incluido en el mismo comando. Si Home Assistant no muestra actualmente el panel de control indicado, el instalador lo guarda y te informa; esto permite aprovisionar un panel antes de que exista el panel de control previsto. Las rutas que Home Assistant nunca podría resolver se rechazan, por lo que la configuración guiada sigue formulando la pregunta.

Para una instalación interactiva, omite `--builtin` y los argumentos de credenciales de Home Assistant. El instalador muestra la dirección del paso que el panel está esperando, normalmente `http://<panel>:8888/setup`. Puedes continuar allí desde un ordenador o teléfono, o tocar **Configurar** en el panel. Ambas opciones siguen el mismo proceso. Los paneles existentes que hayan importado una sesión de Companion conservan ese inicio de sesión como vía de compatibilidad.

Para volver a la aplicación Companion, selecciona el paquete instalado de Home Assistant Companion como aplicación del panel de control en la pestaña Configurar. El renderizador integrado no proporciona Voice Assistant ni notificaciones, por lo que debes conservar Companion cuando esas funciones sean importantes.

## Empezar de nuevo con un panel

`--reset-config` erases ha-paneld's data and starts guided setup as a genuine first run:

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --reset-config
```

**Reset is irreversible and makes no backup.** If you may need the fullest supported recovery, stop and use the separate **Install → Backup** operation on the panel's `:8888` page. Verify that the downloaded `.hpb` is non-empty before continuing. Use `--export FILE` as a separate command first only when a settings-only export is sufficient.

Reset removes settings, learned entity data, proximity and ambient history, and the panel's on-panel revision history. It does not remove the app, root helper or any other app on the panel. The command asks you to type `RESET`; `--force` does not bypass that confirmation. Set `HAPANELD_RESET_CONFIRM=RESET` only when an unattended reset is deliberate.

Fleet updates refuse `--reset-config`. Reset panels one at a time.

The CLI `--restore FILE` and `--restore-fleet FILE` options import a config JSON export and require Python 3 on the computer running the installer. It does not accept an `.hpb` backup. Restore an `.hpb` through **Install → Restore** on the same panel page.

Para conocer la diferencia entre las exportaciones de configuración, las copias de seguridad `.hpb` compatibles y las copias automáticas de emergencia de la base de datos, consulta [Seguridad y recuperación del aprovisionamiento](../provisioning-safety.md#backups-and-recovery).

## Implementar una configuración compartida en una flota

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

## Actualizar toda una flota

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

## Acceso excepcional y modos de seguridad

Hardware-profile recommendations are report-only. Choosing a profile is not consent to disable packages, persist ADB, install privileged software or change display settings. The old `--no-tame` option remains as a compatibility no-op. Packages already present in the configured tame blocklist still reapply at boot.

### Alternativa con Shizuku para paneles sin root

[Shizuku](https://shizuku.rikka.app/) is a separate open-source app whose service runs with Android's shell identity (UID 2000). ha-paneld can use it as a last resort on a genuinely unrooted panel whose profile names a concrete supported use. Shell is not root: operations that need genuine root still fail closed, and none of the root-only hardware features become available. Do not set it up on a panel that already has working `su` or the root helper.

`provision.sh --shizuku` downloads the curated Shizuku Manager, verifies its exact checksum and starts the service, but it cannot approve ha-paneld. Approval happens on the panel, in **Configure → toolbar overflow → Enhanced access → Enable**, and has no remote path through the installer, the web UI, MQTT, a backup restore or a fleet push. The consent is stored only on the panel and is never exported or restored; replacing the Manager, revoking the permission or stopping the service makes the dependent operations fail closed. A service started through ADB normally needs to be started again after a reboot.

[Hardened security mode](../security-mode.md) requires physical access for selected high-impact remote actions. Someone must approve them on the panel's screen, and they cannot be approved remotely. It is enabled only from the panel and is not copied by provisioning or a fleet update. Network ADB cannot coexist with Hardened security mode, so return the panel to Relaxed mode locally before an ADB-based installation or fleet update.

## Aprovisionar una compilación local

Este flujo de trabajo para desarrolladores requiere una copia de trabajo del código fuente. Compila el APK desde la raíz del repositorio y, a continuación, proporciona el archivo al aprovisionador:

<!-- source-checkout-only -->
```bash
./gradlew :app:assembleDebug
scripts/provision.sh <panel-ip:5555> \
  --apk app/build/outputs/apk/debug/app-debug.apk \
  --allow-unsigned-helper
```

`--allow-unsigned-helper` acknowledges that the helper embedded in a local APK is controlled by the local builder instead of authenticated as a published release. It is required whenever a local APK is sent to a panel with a usable root or helper route, including the first helper installation. A genuinely unrooted panel skips helper work. Official `--latest` and `--prerelease` installs authenticate the release helper automatically and do not use this flag.

Android SDK Build-Tools containing `apksigner` are required to update a panel that already has ha-paneld installed, whatever the APK source, because the provisioner compares the installed and candidate signers before it changes anything. A first installation on a panel without ha-paneld does not need them. Local `--apk` provisioning additionally needs either `aapt` or `aapt2`. Before any upgrade backup or panel change, the provisioner verifies the package and exactly one valid signer. Self-built APKs may use the builder's consistent signing key. Add `--require-release-signer` only when the local file should carry the official release certificate.

El plan basado en perfiles informa de cuándo los controladores seleccionados necesitan el asistente. Muchos paneles rk3576 y PX30 pueden ejecutar `su` dentro de la aplicación, mientras que los paneles con root aislado utilizan el asistente para las operaciones con privilegios. Un panel que realmente no tenga root continúa con las funciones estándar de Android, salvo que su perfil declare una alternativa documentada por separado para una función concreta.

## Preparar adb

On some Tuya TPA10 and Smatek panels, adb initially works only over USB. Connect the USB cable, then enable network adb before running the normal provisioning command:

```bash
adb devices                   # accept the on-screen RSA prompt if shown
adb root                      # if this firmware supports it
adb tcpip 5555                # expose adb on the network; this resets on reboot
adb connect 192.168.1.50:5555 # replace this address with the panel's address
```

If a panel has no adb but does have a browser or file manager, download the [release APK](https://github.com/maxlyth/ha-paneld/releases/latest), allow installation from that app and tap the APK. Grant the required permissions by hand under Android Settings, then follow ha-paneld's setup screen. This route is not available on a locked-down panel with no browser or file manager.

## Permisos de Android

| Permiso | Uso | Concesión durante el aprovisionamiento |
|------------|----------|--------------------|
| `POST_NOTIFICATIONS` | Notificación del servicio en primer plano | Permiso en tiempo de ejecución o `pm grant` |
| `WRITE_SETTINGS` | Brillo de la pantalla | `appops set <pkg> WRITE_SETTINGS allow` |
| `SYSTEM_ALERT_WINDOW` | Barra de navegación por software | `appops set <pkg> SYSTEM_ALERT_WINDOW allow` |
| Captura de teclas de accesibilidad | Eventos de los botones físicos | `settings put secure enabled_accessibility_services …` |

Screen-off does not use Android device administrator. The active hardware profile may power off a physical backlight, send Android sleep and wake key events, or fall back to brightness zero. The profile route determines whether root, the authenticated helper or standard Android access is required. Builds up to 0.5.0 included an optional device administrator; 0.5.1 removed it. See the [build and signing notes](../local-builds.md) if you are upgrading from a build where it was enabled.
