# Testing and troubleshooting profiles

A valid YAML file is not yet a verified hardware profile. Validation proves the document is structurally safe for ha-paneld to interpret; staged testing establishes whether its facts and selected drivers describe the physical panel.

## Before activation

Keep someone physically able to see and touch the panel whenever a candidate affects screen power, input capture, LEDs, relays, launchers, packages or privileged hardware. Do not reboot, uninstall or flash a remote panel as part of profile testing. Do not reboot a remote PIN-locked panel: Android file-based encryption may prevent ha-paneld from starting before unlock.

Confirm that the current active revision is healthy and that the Profiles page shows the expected recovery target before staging a change. Export both the active revision and the new candidate. Make sure the panel remains reachable through the local management UI and that a normal dashboard is configured before testing screen behavior.

## Staged test sequence

### Stage 1: identity and matching only

Start with metadata, requirements and exact match rules, leaving optional hardware absent. Import, validate and compare without activating. Check that the intended fingerprint matches and close near-misses do not, especially panels sharing a Rockchip reference model. When one profile also has a broad fallback, give that group a lower branch priority and test a cross-profile collision where another product's exact rule must win.

Activate this minimal revision first. Confirm that the service returns, the Profiles page reports it active, Generic-safe capabilities still work, and rollback returns to the prior revision.

### Stage 2: standard Android capabilities

Add descriptive display/sensor facts and ordinary Android mechanisms. Verify brightness, audio, navigation, dashboard rendering, Android-exposed light/proximity sensors and the capability report. A profile should degrade cleanly when Android does not expose an optional sensor.

### Stage 3: passive candidates and read-only evidence

Add candidate paths or firmware variants supported by passive diagnostics. Confirm the declared-versus-live result in diagnostics. A compiled driver or a node name found in firmware is not enough; the target panel must expose and permit the actual runtime interface.

### Stage 4: one user-visible hardware control at a time

Test one LED, button, relay or screen mechanism per revision. Keep the physical result observable and provide a recovery route independent of the new control. For a screen-off candidate, prove local touch wake before relying on Home Assistant or proximity; ha-paneld must never leave the panel blank and unresponsive.

For input devices, confirm the event node, type, code, press/release behavior and whether grabbing it suppresses an essential Android action. Do not grab a power or navigation input merely because its event number looks plausible.

For relays and mains-connected hardware, use an attended, electrically safe test load and verify off as well as on. Never infer a physical relay from phantom sysfs nodes alone.

### Stage 5: authority-dependent behavior

Only after the standard path is healthy should you test Shizuku, vendor `su` or the root helper. Verify the live authority state separately from the profile declaration.

A Shizuku recommendation should show setup guidance while remaining unavailable until the user enables Enhanced access and approves ha-paneld locally. Revoking or stopping Shizuku must make operations fail closed without changing profile state.

A root/helper-backed driver must remain bounded to its compiled operation and validated parameters. Test missing helper, denied `su`, inaccessible node and malformed readback as normal failure cases.

### Stage 6: restart, upgrade and recovery

Restart ha-paneld through the profile activation flow, then test an ordinary device reboot only on attended hardware where reboot is otherwise safe. Confirm that the selected revision returns, authority loss is reported rather than guessed away, and the management UI survives a failed driver probe.

Preview a deliberately invalid or incompatible candidate and prove it cannot be saved or activated through the normal UI. In a development test seam, also exercise an already-installed revision becoming incompatible after a core upgrade and confirm automatic rollback to last-known-good. Verify that changing capabilities removes stale Home Assistant discovery entities as well as adding new ones.

## What to record

Useful evidence for each tested capability includes:

- panel manufacturer/model and exact Android product-version string;
- profile ID, version, revision and content hash;
- ha-paneld version;
- Android version and ABI;
- authority route actually available: standard, locally approved Shizuku, helper or vendor `su`;
- passive diagnostic facts supporting the driver choice;
- observed physical result and recovery result;
- firmware versions tested and important versions not tested;
- any absent, denied or degraded capability.

Do not publish panel addresses, panel IDs, Wi-Fi details, MQTT credentials, Home Assistant URLs/tokens, dashboard content or private package/application data.

## Troubleshooting

### The YAML will not import

Use the error's field path and line/column. Common causes are duplicate keys, indentation, an unknown field, a scalar with the wrong type, an unsupported enum/driver ID, or a document created for a newer schema. Do not work around an unknown field by moving it into another section; validate against the target panel's schema and driver catalog.

### Validation says the profile needs a newer core or missing driver

The current ha-paneld build cannot safely interpret the requested behavior. Update ha-paneld to a compatible release or revise the profile to use only mechanisms it actually needs. Installing a helper or granting root cannot add a driver that was not compiled into the app.

### The profile does not match

Compare the draft fingerprint with the rule exactly, including vendor punctuation and product-version format. Prefer correcting a narrow rule over adding a broad SoC alias. A bundled tie fails closed to Generic; fix the evidence and deliberate exact-before-fallback branch order rather than raising priorities blindly. A local/community revision still requires explicit selection regardless of its match priority.

### The profile validates but a capability is unavailable

Validation confirms the declaration and parameters, not the physical hardware. Check the live probe result and authority state. The node may be absent on this firmware, SELinux may deny the app, the helper may not be installed, Shizuku may be stopped/unapproved, or the retail model may omit hardware present on a related SKU.

### Shizuku is recommended but still unavailable

The profile cannot approve it. Follow [Shizuku enhanced access](../shizuku.md), enable Enhanced access locally and approve ha-paneld in the Manager. An ADB-started Shizuku service may need rearming after reboot.

### Activation returns pending and the page disconnects

This is expected while the service restarts. The Profiles page polls health and the catalog. Reconnect to the same panel and confirm whether the candidate became active, remained pending briefly, or was auto-rolled-back.

### The candidate auto-rolled-back

Keep the last-known-good revision active. Export the failed candidate and inspect its activation diagnostics before editing it into a new revision. Do not repeatedly reactivate the same bytes. Reduce the change to one capability, confirm requirements and authority, and test again.

### A stale save or activation is refused

Another operation changed the profile catalog after your page loaded. Refresh, compare against the current generation, and repeat the operation using the new exact revision/hash. This refusal prevents one browser from activating different bytes than it inspected.

### The local profile catalog is full

Export any revision you still need, then delete obsolete inactive local revisions. The catalog deliberately limits total revisions, revisions under one profile ID and aggregate YAML bytes. Active, pending and last-known-good revisions cannot be deleted; activate and prove a replacement before retiring a protected rollback target.

### Home Assistant still shows a removed entity

Allow the restarted MQTT bridge to reconnect and publish discovery cleanup. If the panel is offline or cannot authenticate to the broker, cleanup cannot reach Home Assistant yet. Confirm the broker connection before deleting entities manually.

### The panel is dark or unresponsive

Use the established local recovery route immediately rather than continuing profile experiments. Restore brightness/backlight and the last-known-good profile. A profile candidate that can produce a non-wakeable dark state is not acceptable even if remote Home Assistant wake works in the normal case.
