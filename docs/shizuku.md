# Shizuku enhanced access

Shizuku is an optional way for ha-paneld to perform a small set of privileged Android operations on a panel whose firmware does **not** provide root access. It is useful on locked-down tablets and wall displays, but it is not required on the vendor-rooted wall panels that make up most existing ha-paneld installations.

If your panel diagnostics already report working `su` or the ha-paneld root helper, keep that normal path. Installing Shizuku will not add the root-only hardware features that those panels already have. ha-paneld does not recommend modifying or rooting a device that was supplied unrooted.

## What Shizuku is

[Shizuku](https://shizuku.rikka.app/) is a separate open-source Android application and service. Its service is started through ADB or Android's supported wireless-debugging facilities and runs with the Android **shell** identity (UID 2000). Apps approved by the user can ask that service to perform defined operations through Android Binder.

Shell is more capable than an ordinary Android app, but it is not root:

- it can use facilities such as `pm install`, `wm density`, screenshots and input injection;
- it can read an exact allowlisted room-climate input layout when the vendor grants the shell identity access;
- it cannot access arbitrary private application data or vendor hardware interfaces reserved for root;
- its service may stop at reboot and need to be started again;
- every client application still needs explicit approval in the Shizuku Manager.

Shizuku therefore fills the gap between ha-paneld's standard Android permissions and a panel whose vendor firmware already supplies `su` or permits installation of the root helper.

## How ha-paneld uses it

ha-paneld does not expose a general Shizuku shell. Its enhanced-access bridge has a deliberately small, typed interface:

1. The checkout-free provisioner with `--shizuku` downloads the curated Shizuku Manager, verifies its exact checksum and installs or retains a trusted same/newer version.
2. The script starts the Shizuku service, but it cannot approve ha-paneld.
3. On the panel, the administrator opens **ha-paneld Configure → toolbar overflow → Enhanced access → Enable** and approves ha-paneld in Shizuku.
4. ha-paneld verifies the Manager's signing certificate, the service's shell UID and its protocol version before declaring enhanced access ready.
5. Each supported operation uses a fixed typed method with bounded inputs, output sizes and timeouts. There is no arbitrary command, argument list, filesystem browser or package-enumeration method.

Consent is stored separately on the panel. It is not exported in a configuration bundle, restored from a backup, enabled through MQTT or the HTTP API, or copied by a fleet update. Replacing the Manager, revoking permission, stopping the service or disabling enhanced access invalidates the binding and the operations fail closed.

When more than one privileged route exists, ha-paneld prefers the established privileged route:

```text
vendor su → ha-paneld root helper → locally approved Shizuku
```

Ordinary Android and Accessibility capabilities continue to handle operations that do not need one of those routes. Shizuku is therefore dormant on a normal rooted wall panel unless the administrator deliberately sets it up, and it does not replace a working root/helper path.

## Capability comparison

| Capability | Standard Android | Shizuku enhanced access | Vendor `su` / root helper |
| --- | --- | --- | --- |
| HA dashboards, MQTT entities, web UI, audio/TTS, brightness and dimming | Yes | Yes | Yes |
| Display density and system text scale | No | Yes | Yes |
| Remote screenshot and privileged key/tap input | No | Yes | Yes |
| Install/update signer-verified ha-paneld and minimal HA Companion | No | Yes | Yes |
| Read exact supported room temperature/humidity input devices | No | Yes, where shell-readable | Yes, where supported |
| Install an arbitrary uploaded APK | No | No | Yes |
| Replace or heal the System WebView | No | No | Yes |
| True backlight-off without invoking Android lock/sleep | No | No | Yes, where supported |
| Vendor LED, relay and other hardware control | No | No | Yes, where supported |
| Reboot, CPU governor and vendor-app taming | No | No | Yes |
| Full system logs or another app's private data | No | No | Direct root only |

Some ordinary input features, such as Back and Recents, can alternatively use ha-paneld's Accessibility service. Hardware availability also varies by panel: having root cannot create an LED or relay interface that the firmware does not expose.

## Rooted vendor panel or Shizuku?

### Vendor-rooted wall panel

Purpose-built panels such as the Sonoff NSPanel Pro commonly ship with a vendor-provided `su`, root ADB, or a firmware arrangement that lets the installer place ha-paneld's small root helper. This is the normal ha-paneld path.

Advantages:

- no extra Manager application or approval lifecycle;
- normally available again immediately after reboot;
- supports the full hardware-specific feature set;
- already exercised by the established ha-paneld fleet.

Trade-offs:

- availability and command behavior depend on the vendor firmware;
- some firmwares isolate applications from `su`, requiring the separately installed root helper;
- root is a broader authority, so ha-paneld keeps root/helper commands allowlisted and bounded.

Use the root route supplied by the panel. Do not install Shizuku merely to duplicate it, and do not root an otherwise locked device solely for ha-paneld.

### Genuinely unrooted panel with Shizuku

Advantages:

- adds the most useful maintenance and display controls without modifying the firmware for root;
- requires visible local approval and can be revoked in the Shizuku Manager;
- ha-paneld exposes only a narrow typed subset of the shell identity;
- enables signed application updates that an ordinary app cannot install silently.

Trade-offs:

- installation and initial service start require ADB or supported wireless debugging;
- the Shizuku Manager is another application and trust dependency to maintain;
- ADB-started services commonly need rearming after reboot, especially on older Android versions;
- it offers fewer capabilities than root and cannot operate vendor hardware or private app data;
- ha-paneld does not auto-update the Shizuku Manager;
- behavior varies across unrooted wall-panel firmware and should be verified on each panel.

### Standard Android only

If the standard feature set is enough, do nothing. Dashboard rendering, the automatic entity filter, MQTT discovery, brightness/dimming, audio, the web UI, backup/restore and most panel behavior do not need Shizuku or root. This is the simplest and least-privileged configuration.

## Installing and enabling it

From a machine with `adb`, replace the example address with the panel's address and paste this complete command into Git Bash, WSL, macOS Terminal or a Linux terminal:

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --shizuku
```

You do not need to download the repository. The provisioner verifies the pinned Manager package, starts its service and prints the remaining local step. On the panel:

1. Open **ha-paneld Configure**.
2. Open the toolbar overflow menu and select **Enhanced access**.
3. Select **Enable**.
4. Approve ha-paneld in the Shizuku permission prompt.
5. Return to Enhanced access and confirm that it reports **Enhanced access is ready**.

The web UI capability report distinguishes whether Enhanced access is disabled in ha-paneld, the Shizuku service is stopped, the Manager is missing or untrusted, local approval is still required, or access is ready. A disabled state points back to the on-panel **Configure → toolbar overflow → Enhanced access → Enable** path; a stopped state means consent is already enabled and the Shizuku service itself must be started. Provisioning details and fleet-install behavior are in [Provisioning and fleet updates](provisioning.md).

## Reboot, recovery and removal

An ADB-started Shizuku service may stop after a reboot. On compatible Android 13+ devices, Shizuku 13.6 offers a trusted-WLAN auto-start option; the administrator chooses whether to enable it in Shizuku. On older panels, reconnect ADB and rerun the authenticated provisioning step:

```bash
curl -fsSL https://raw.githubusercontent.com/maxlyth/ha-paneld/main/scripts/install.sh | bash -s -- --provision 192.168.1.50:5555 --shizuku
```

The provisioner keeps a trusted same-or-newer Manager, resolves and validates its installed native starter, and executes that authenticated file directly. Do not execute a copied `start.sh` from shared external storage.

If permission was denied and Android no longer displays the prompt, open **Shizuku → Authorized applications** and grant ha-paneld there. ha-paneld deliberately does not loop permission prompts.

To stop using the feature, open **Enhanced access** in ha-paneld and choose **Disable**. This clears ha-paneld's local consent and destroys its service binding. The Manager may then be uninstalled using normal Android/ADB tools if no other application uses it. Disabling or removing Shizuku does not remove ordinary ha-paneld configuration and does not affect a working `su` or root-helper route.

## Security and network considerations

Shizuku approval expands what ha-paneld can do through its existing local API: for example, a trusted-LAN caller can request a screenshot, inject input or initiate a signer-verified component update. Relaxed mode assumes the panel is on a trusted network. If the panel shares a network with untrusted clients, isolate access using a VLAN or firewall before enabling privileged routes.

Optional [Hardened mode](security-mode.md) is a separate layer. Shizuku approval grants ha-paneld a bounded Android shell capability; Hardened mode requires someone physically at the panel to approve selected high-impact network requests on its screen, and they cannot be approved remotely. It disables non-loopback tap injection even when Shizuku could perform the tap. Enabling either one does not enable the other, and neither setting is copied by backup, restore or fleet update.

The Shizuku boundary reduces authority compared with root, and Hardened mode protects only its documented operation set; neither makes an untrusted LAN safe. The exact project threat model is documented in [Security posture](architecture/security.md).
