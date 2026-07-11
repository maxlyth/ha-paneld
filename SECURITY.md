# Security policy

## Supported versions

ha-paneld is pre-1.0. Only the **latest release** receives security fixes; please reproduce on it before reporting.

| Version | Supported |
| --- | --- |
| latest release | ✅ |
| older | ❌ |

## Reporting a vulnerability

Please **report privately** — do not open a public issue for a suspected vulnerability.

Use GitHub **Private Vulnerability Reporting**: the repository's [**Security → Report a vulnerability**](https://github.com/maxlyth/ha-paneld/security/advisories/new) form. It's private to the maintainers and integrates with GitHub Security Advisories.

Please include the panel hardware, the ha-paneld version, and the panel's `/diag` output (it contains no credentials) where relevant. You'll get an acknowledgement, and coordinated disclosure once a fix or mitigation is agreed.

## Scope — know the trust model first

ha-paneld is designed as a **LAN-trust appliance**: the panel and Home Assistant are assumed to share a trusted network. Some behaviour is therefore **by design, not a vulnerability**:

- The HTTP API on `:8888` is **unauthenticated** — restricting who can reach it is delegated to the network layer (VLAN / firewall). See the full threat model and decisions in [docs/architecture/security.md](docs/architecture/security.md).
- A root/file-level attacker already on the panel is out of scope (they own the device).

Reports of genuine issues *within* that model are very welcome — e.g. an unauthenticated path that escapes the LAN-trust boundary, a command injection, a credential leak off-device, or anything that affects a panel from outside its LAN.
