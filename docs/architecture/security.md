# Security posture

ha-paneld runs a foreground agent on a wall panel exposing an HTTP API (`:8888`), an MQTT control plane, a root helper daemon, and an opt-in CDP/DevTools relay. **The model is LAN-trust with a turnkey, no-token pairing UX**: the panel and Home Assistant are assumed to sit on a trusted network, so the unauthenticated `:8888` API is an accepted residual risk, with network segmentation (and a future HA-auth upgrade path) as the controls. This note records the threat model and the decisions taken; it's the reference for hardening work.

## Trust model

The panel and Home Assistant sit on a **trusted LAN**, and the app is built around a **turnkey, no-token pairing UX**. A root/file-level attacker on the panel itself is out of scope (they already own the device).

> [!WARNING]
> The notable **accepted residual risk**: a LAN-local actor without credentials can reach the unauthenticated `:8888` API (e.g. `POST /config` to repoint MQTT). Under the LAN-trust model this is accepted; restricting reach is delegated to network segmentation ([decision 2](#decisions)), with HA-auth ([decision 3](#decisions)) as the upgrade path if/when ha-paneld is deployed on a less-trusted network.

## Attack surface

| Surface | Exposure | Notes |
| --- | --- | --- |
| **HTTP API `:8888`** | unauthenticated; binds dual-stack (`::`) | state-changing routes are the risk — see below |
| **CDP / WebView DevTools relay `:9222`** | opt-in (started via `/inspect/start`), binds `0.0.0.0`, unauthenticated | full dashboard-session access if started |
| **MQTT** | governed by the broker | the intended control plane; inherent to MQTT discovery |
| **Root helper daemon** (abstract UNIX socket `@hapaneld-helper`) | peer-uid authenticated (`SO_PEERCRED`: ha-paneld/root/shell only), command-whitelisted, char-sanitised, bounded parsing, conn caps | Sound — was unauthenticated loopback TCP `:8889`; now no other local app can reach it |
| **Shizuku enhanced access** | optional; enabled and approved only on the panel | Official manager signer is pinned; the UserService must report UID 2000 and protocol v2; methods and inputs are typed and bounded, with no generic command or filesystem access. Shell is not root. Consent is excluded from Android backup, config bundles, restore, and fleet push. ADB-started service may need rearming after reboot. |
| **Network ADB** | opt-in switch; opens `5555` to the LAN when enabled | documented, off by default |
| **Accessibility service** | key-capture only (no window-content retrieval) | Sound |
| **su call-sites** | CPU governor regex-sanitised; relay/adb/cdp use constants; Zigbee role now allowlisted | the Zigbee role was the one un-validated interpolation; no remaining injection paths |

The state-changing routes on `:8888` are where the exposure concentrates:

- `POST /config` — sets MQTT broker + credentials + panel_id. Unauthenticated ⇒ a LAN actor can repoint the panel at a rogue broker and take over every entity. **Top risk.**
- `POST /play` — downloads + plays an arbitrary URL with TLS verification disabled (LAN self-signed convenience) ⇒ audio injection / open fetch relay.
- `POST /inspect/start` — starts the CDP relay (the `:9222` surface above).
- `GET /diag` — device/capability info (recon). Intentionally a support tool (issue template).
- `GET /api/v1/screenshot.png` and `POST /api/v1/input` — view and inject input on the panel. Root/helper routes already expose these; locally approved Shizuku makes the same trusted-LAN controls available on a non-root panel.
- `POST /api/v1/install/component` — can install signer-pinned ha-paneld or minimal Companion builds. Shizuku enables those verified updates on a non-root panel, but does not enable arbitrary APK upload or WebView replacement.

**Browser-mediated attacks are guarded** ([`OriginGuard`](../../app/src/main/kotlin/io/github/maxlyth/hapaneld/http/OriginGuard.kt)):
- **CSRF** — OriginGuard refuses a state-changing request (`POST`/`PUT`/`PATCH`/`DELETE`) whose `Origin`/`Referer` is present and doesn't match the request `Host`, so a malicious LAN web page can't silently drive these endpoints. Same-origin UI `fetch`es and header-less API clients (curl / HA `rest_command`) are unaffected.
- **DNS-rebinding** — the `Host` header must be an IP literal, `localhost`, `*.local` (mDNS), or an operator-configured name (`http_allowed_hosts`); any other hostname is refused (all methods), so an attacker who rebinds their own DNS name to the panel can't pose as same-origin to read secrets (`GET /config/export`) or drive the surface. Reaching a panel by IP — the norm — is always allowed and is inherently rebinding-immune.

Neither authenticates the *caller*; a credentialled LAN actor is still trusted (decision 3 is the upgrade path).

## Decisions

1. **No bespoke API token.** A per-panel secret is throwaway and breaks the easy UX. (Rejected.)
2. **No in-app network allowlist.** Restricting *who* can reach `:8888` is delegated to the network/infrastructure layer (router / VLAN / firewall segmentation) rather than reinvented in the app — consistent with leaning on existing platform/infra capabilities. The in-app upgrade path, when warranted, is the HA-auth model (decision 3).[^ha-ipban]
3. **Future auth: integrate with HA's auth model**, not a custom scheme. When real auth is warranted, require `Authorization: Bearer <token>` on state-changing endpoints and **validate the token against the configured HA instance** (cached `GET /api/`). This reuses HA's token lifecycle (issue/revoke in the HA UI), works with HA `rest_command` bearer headers, and **maps directly onto a future custom integration** (which would authenticate via HA natively) — so it survives a possible MQTT→custom migration instead of being discarded.
4. **Credential-at-rest encryption descoped.** MQTT creds live in app SharedPreferences; encrypting them only defends against a root/file attacker who already owns the device, at the cost of a deprecated `security-crypto` dependency and a credential migration across deployed panels; low value for the cost.
5. **TLS-disabled `/play`** stays (LAN self-signed convenience) under the LAN-trust model; revisit if HA-auth (decision 3) lands.

## Done

- Zigbee `setRole()` allowlists the role before shell interpolation (no arbitrary-string injection).
- **Cross-origin (CSRF) guard** on state-changing `:8888` routes ([`OriginGuard.allowed`](../../app/src/main/kotlin/io/github/maxlyth/hapaneld/http/OriginGuard.kt)) — refuses a browser write whose `Origin`/`Referer` doesn't match the request `Host`; API clients (no `Origin`) and same-origin UI unaffected.
- **DNS-rebinding guard** ([`OriginGuard.hostAllowed`](../../app/src/main/kotlin/io/github/maxlyth/hapaneld/http/OriginGuard.kt)) — pins the `Host` header to IP literals / `localhost` / `*.local` / configured names (`http_allowed_hosts`); other hostnames refused, so a rebound name can't masquerade as same-origin.
- **APK-installer downloads are HTTPS-only** ([`AppInstaller`](../../app/src/main/kotlin/io/github/maxlyth/hapaneld/util/AppInstaller.kt)) — the initial URL and every redirect hop must be `https` (defence-in-depth on top of the post-download signer/package pin, which already blocks installing a substituted APK).

[^ha-ipban]: HA's own IP allowlist / `ip_ban` secures HA's HTTP server, **not** the panel's separate `:8888`, so it doesn't apply directly; network segmentation is the "use what exists" control here.

> [!NOTE]
> For how the device-specific su call-sites and helper-daemon paths are organised per platform, see the [device-profile architecture](device-profiles.md).
