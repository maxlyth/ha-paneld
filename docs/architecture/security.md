# Security posture

ha-paneld runs a foreground agent on a wall panel exposing an HTTP API (`:8888`), an MQTT control plane, a root helper daemon, and an opt-in CDP/DevTools relay. **The model is LAN-trust with a turnkey, no-token pairing UX**: the panel and Home Assistant are assumed to sit on a trusted network, so the unauthenticated `:8888` API is an accepted residual risk, with network segmentation (and a future HA-auth upgrade path) as the controls. This note records the threat model and the decisions taken; it's the reference for hardening work.

## Trust model

The panel and Home Assistant sit on a **trusted LAN**, and the app is built around a **turnkey, no-token pairing UX**. A root/file-level attacker on the panel itself is out of scope (they already own the device).

> [!WARNING]
> The notable **accepted residual risk**: a LAN-local actor without credentials can reach the unauthenticated `:8888` API (e.g. `POST /config` to repoint MQTT). Under the LAN-trust model this is accepted; restricting reach is delegated to network segmentation ([decision 2](#decisions)), with HA-auth ([decision 3](#decisions)) as the upgrade path if/when ha-paneld is deployed on a less-trusted network.

## Attack surface

Review dated 2026-06-05.

| Surface | Exposure | Notes |
| --- | --- | --- |
| **HTTP API `:8888`** | unauthenticated; binds dual-stack (`::`) | state-changing routes are the risk — see below |
| **CDP / WebView DevTools relay `:9222`** | opt-in (started via `/inspect/start`), binds `0.0.0.0`, unauthenticated | full dashboard-session access if started |
| **MQTT** | governed by the broker | the intended control plane; inherent to MQTT discovery |
| **Root helper daemon `:8889`** | loopback-only, command-whitelisted, char-sanitised | Sound |
| **Network ADB** | opt-in switch; opens `5555` to the LAN when enabled | documented, off by default |
| **Accessibility service** | key-capture only (no window-content retrieval) | Sound |
| **su call-sites** | CPU governor regex-sanitised; relay/adb/cdp use constants; Zigbee role now allowlisted | the Zigbee role was the one un-validated interpolation; no remaining injection paths |

The state-changing routes on `:8888` are where the exposure concentrates:

- `POST /config` — sets MQTT broker + credentials + panel_id. Unauthenticated ⇒ a LAN actor can repoint the panel at a rogue broker and take over every entity. **Top risk.**
- `POST /play` — downloads + plays an arbitrary URL with TLS verification disabled (LAN self-signed convenience) ⇒ audio injection / open fetch relay.
- `POST /inspect/start` — starts the CDP relay (the `:9222` surface above).
- `GET /diag` — device/capability info (recon). Intentionally a support tool (issue template).

## Decisions

1. **No bespoke API token.** A per-panel secret is throwaway and breaks the easy UX. (Rejected.)
2. **No in-app network allowlist.** Restricting *who* can reach `:8888` is delegated to the network/infrastructure layer (router / VLAN / firewall segmentation) rather than reinvented in the app — consistent with leaning on existing platform/infra capabilities. The in-app upgrade path, when warranted, is the HA-auth model (decision 3).[^ha-ipban]
3. **Future auth: integrate with HA's auth model**, not a custom scheme. When real auth is warranted, require `Authorization: Bearer <token>` on state-changing endpoints and **validate the token against the configured HA instance** (cached `GET /api/`). This reuses HA's token lifecycle (issue/revoke in the HA UI), works with HA `rest_command` bearer headers, and **maps directly onto a future custom integration** (which would authenticate via HA natively) — so it survives a possible MQTT→custom migration instead of being discarded.
4. **Credential-at-rest encryption descoped.** MQTT creds live in app SharedPreferences; encrypting them only defends against a root/file attacker who already owns the device, at the cost of a deprecated `security-crypto` dependency and a live-fleet cred migration. Low value for the cost.
5. **TLS-disabled `/play`** stays (LAN self-signed convenience) under the LAN-trust model; revisit if HA-auth (decision 3) lands.

## Done

- Zigbee `setRole()` allowlists the role before shell interpolation (no arbitrary-string injection).

[^ha-ipban]: HA's own IP allowlist / `ip_ban` secures HA's HTTP server, **not** the panel's separate `:8888`, so it doesn't apply directly; network segmentation is the "use what exists" control here.

> [!NOTE]
> For how the device-specific su call-sites and helper-daemon paths are organised per platform, see the [device-profile architecture](device-profiles.md).
