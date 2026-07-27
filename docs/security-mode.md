# Security mode

ha-paneld's HTTP and MQTT controls are designed for a trusted home network. **Relaxed mode** is the default: existing automations, provisioning tools and browser actions continue to work without an extra prompt.

For a panel on a network shared with less-trusted clients, optional **Hardened mode** adds physical-presence approval to selected high-impact network operations. It is an additional safety boundary, not a replacement for a VLAN or firewall and not general API authentication. Read-only endpoints and routine panel controls remain available under the normal trusted-LAN model.

**Protected high-impact remote actions in Hardened mode require physical access to the panel. They cannot proceed until someone approves them on the panel's screen, and they cannot be approved remotely.**

The web interface marks affected actions with a small shield in both Relaxed and Hardened modes. This shows which workflows cross the physical-approval boundary before Hardened mode is enabled. Update-policy settings carry the same marker because enabling installation authority, or changing an active update channel, can require approval; ordinary changes made with the same Save button do not.

## Enable Hardened mode

This setting can be changed only on the panel itself:

1. Open ha-paneld's on-panel **Configure** screen.
2. Open the toolbar overflow menu and choose **Security mode**.
3. Choose **Enable Hardened mode**.

Classic network ADB and Android Wireless debugging must be off first because either can let a remote ADB client inject input into the approval screen. Before committing the change, ha-paneld checks every supported Android property for classic TCP ADB, persistent TCP ADB, explicit ADB listen addresses and Wireless-debugging TLS state. It also stops any active LAN WebView developer-tools relay and verifies that both its process and listener are gone. If it cannot establish that these remote-control paths are inactive, it refuses to enable Hardened mode. Turn ADB and Wireless debugging off in Android's developer settings, then try again. If relay verification fails, restart the panel before repeating the mode change.

The setting is device-local. It is not included in configuration exports or full backups and cannot be changed through HTTP, MQTT, restore, configuration import or a fleet update. Upgrading an existing panel therefore leaves it in Relaxed mode unless someone enables Hardened mode locally.

Use the same menu to return to Relaxed mode. Doing so clears pending approval requests.

## Approve a network request

When Hardened mode intercepts a protected HTTP request, the first attempt returns HTTP `202 Accepted` with an `approval-required` response, for example:

```json
{
  "ok": false,
  "error": "approval-required",
  "approval_id": "…",
  "message": "Approve this request on the panel, then retry it."
}
```

Complete the operation as follows:

1. On the panel, open **Configure → toolbar overflow → Security mode → Review approvals**.
2. Check the operation, summary and requesting peer, then approve or deny it.
3. Within ten minutes of the original request, repeat the identical request from the same peer.

Approval is bound to the operation, peer and protected request payload. It can be used once. Changing the request, sending it from another address, waiting more than ten minutes or restarting ha-paneld requires a new approval. An approval is consumed only by the matching retry; it is not a temporary general bypass.

MQTT does not expose a trustworthy publisher identity to subscribers. MQTT approvals are therefore bound to the shared MQTT command channel and exact command payload rather than an individual publisher. Keep publish access to the panel's command topics restricted with broker ACLs; Hardened mode is not a substitute for broker authentication or authorization.

Calls over loopback from software already running on the panel do not require network approval. Hardened mode therefore treats local Android applications as trusted; it does not isolate one on-panel app from another. Keep untrusted applications off the panel.

## Protected operations

Hardened mode requires on-panel approval before a network client can:

- install an uploaded APK or a managed ha-paneld, Companion or System WebView component, enable an automatic-update policy, or change an update channel when that would start an immediate update;
- uninstall an application or change a vendor package's enabled, running or overlay state;
- export a full backup or a configuration bundle containing secrets;
- import configuration, restore a stored configuration revision or restore a panel backup;
- activate or roll back a hardware profile;
- play media fetched from a remote URL;
- reload the dashboard renderer or reboot the panel;
- repair Home Assistant Companion configuration or clear the built-in dashboard's browsing data;
- change system display density or font scaling;
- disable the configured keep-awake or prevent-idle-dim reachability guard through Configure or Home Assistant;
- repair panel power safety; or
- hide one exact unchanged manual-only power-safety caution. This acknowledgement changes presentation only; the underlying assessment, diagnostics and installer result remain unchanged.

A remote Hardened Configure request must save a power-safety reduction separately from package-taming or software-installation policy changes because those actions require different approval classes. Relaxed-mode and loopback combined saves remain direct.

Hardened mode rejects non-loopback tap injection rather than allowing it to approve its own pending requests. It also refuses to enable classic network ADB, Android Wireless debugging or the LAN WebView developer-tools relay. Switch the panel back to Relaxed mode locally before using those remote-control paths.

The approval boundary does not make the rest of `:8888` authenticated. In particular, diagnostic and status reads and routine controls retain the normal trusted-LAN behavior. Keep the panel API away from untrusted networks even when Hardened mode is enabled; see the detailed [security posture](architecture/security.md).

An automatic-update setting that was already enabled when Hardened mode was selected remains standing device policy, so its scheduled background checks can install an authenticated update without a new prompt. A network client needs on-panel approval to enable that policy or expand it through a channel change that starts an immediate update. Disabling automatic updates remains direct.

## Changing Home Assistant or MQTT endpoints

Hardened mode prevents a saved credential from silently following a changed endpoint:

- changing the **MQTT broker** without entering a password in the same save clears the previous MQTT password;
- changing the **Home Assistant URL** without entering replacement credentials in the same save clears the previous access token, refresh token and related session identity.

Enter the credentials for the new destination in the same Save changes operation. This behavior applies only when the endpoint changes in Hardened mode; ordinary edits and Relaxed mode retain the normal blank-means-keep behavior.

## Provisioning official and local builds

The one-line installer and `provision.sh --latest` or `--prerelease` authenticate official release helper assets automatically. No additional acknowledgement is needed.

A locally built APK is controlled by its builder rather than authenticated as a published release. If provisioning that APK also needs to install its embedded root helper, explicitly acknowledge the local privileged bytes:

```sh
./helper/build.sh
scripts/provision.sh <panel-ip:5555> \
  --apk app/build/outputs/apk/debug/app-debug.apk \
  --allow-unsigned-helper
```

The flag applies only to the helper embedded in a local APK. Local APK provisioning requires it whenever the panel exposes a usable root or helper path, including a first helper installation. A genuinely unrooted panel skips helper work. The flag does not weaken verification for official release downloads.
