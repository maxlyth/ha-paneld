# Security posture

ha-paneld runs a foreground agent on a wall panel exposing an HTTP API (`:8888`), an MQTT control
plane, a root helper daemon, and an opt-in CDP/DevTools relay. This note records the threat model and
the security decisions taken; it's the reference for hardening work.

## Trust model

The panel and Home Assistant sit on a **trusted LAN**. The primary adversary is a **LAN-local actor
without credentials**; a root/file-level attacker on the panel itself is out of scope (they already own
the device). The design goal is to keep the **turnkey, no-token pairing UX** while removing the
remote-takeover paths a LAN actor could use.

## Attack surface (2026-06-05 review)

- **HTTP API `:8888`** — unauthenticated; binds dual-stack (`::`). State-changing routes are the risk:
  - `POST /config` — sets MQTT broker + credentials + panel_id. Unauthenticated ⇒ a LAN actor can
    repoint the panel at a rogue broker and take over every entity. **Top risk.**
  - `POST /play` — downloads + plays an arbitrary URL with TLS verification disabled (LAN self-signed
    convenience) ⇒ audio injection / open fetch relay.
  - `POST /inspect/start` — starts the CDP relay (below).
  - `GET /diag` — device/capability info (recon). Intentionally a support tool (issue template).
- **CDP / WebView DevTools relay `:9222`** — opt-in (started via `/inspect/start`), binds `0.0.0.0`,
  unauthenticated ⇒ full dashboard-session access if started.
- **MQTT** — the intended control plane; access is governed by the broker. Inherent to MQTT discovery.
- **Root helper daemon `:8889`** — loopback-only, command-whitelisted, char-sanitised. Sound.
- **Network ADB** — opt-in switch; opens `5555` to the LAN when enabled (documented, off by default).
- **Accessibility service** — key-capture only (no window-content retrieval). Sound.
- **su call-sites** — CPU governor is regex-sanitised; relay/adb/cdp use constants; the Zigbee role is
  now allowlisted (was the one un-validated interpolation). No remaining injection paths.

## Decisions

1. **No bespoke API token.** A per-panel secret is throwaway and breaks the easy UX. (Rejected.)
2. **Near-term control: client subnet/IP allowlist** for the HTTP API (and the CDP relay). Default
   permissive (turnkey preserved); the operator can restrict access to e.g. the HA server IP or the
   LAN subnet. One network-layer control that uniformly gates `/config`, `/play`, `/inspect`/CDP and
   `/diag` — preferred over per-endpoint hacks (which would, e.g., break the no-adb DevTools feature or
   gut `/diag`'s support value).
3. **Future auth: integrate with HA's auth model**, not a custom scheme. When real auth is warranted,
   require `Authorization: Bearer <token>` on state-changing endpoints and **validate the token against
   the configured HA instance** (cached `GET /api/`). This reuses HA's token lifecycle (issue/revoke in
   the HA UI), works with HA `rest_command` bearer headers, and **maps directly onto a future custom
   integration** (which would authenticate via HA natively) — so it survives a possible MQTT→custom
   migration instead of being discarded.
4. **Credential-at-rest encryption descoped.** MQTT creds live in app SharedPreferences; encrypting
   them only defends against a root/file attacker who already owns the device, at the cost of a
   deprecated `security-crypto` dependency and a live-fleet cred migration. Low value for the cost.
5. **TLS-disabled `/play`** stays (LAN self-signed convenience) but is covered by the subnet allowlist
   (only trusted clients can trigger fetches); revisit if auth (3) lands.

## Done

- Zigbee `setRole()` allowlists the role before shell interpolation (no arbitrary-string injection).
