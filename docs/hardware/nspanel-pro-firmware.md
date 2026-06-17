# Sonoff NSPanel Pro — firmware OTA index

Canonical, verified index of Sonoff NSPanel Pro OTA firmware images and the
URLs they live at. Replaces the scattered, ad-hoc excerpts spread across forum
threads (notably seaky/nspanel_pro_tools_apk #262 and
seaky/nspanel_pro_roottool_apk #1) with a single auditable list.

> [!NOTE]
> Every URL here was verified live (HTTP 206 range request → real size + ZIP
> magic) on the date stated in the table footnotes. Entries marked *index TBD*
> are versions confirmed to exist (image held locally) whose CDN index is still
> being mapped by the firmware hunts — they are not yet directly fetchable until
> the index column is filled.

There are two physically distinct models, each on its **own** CDN channel — do
not cross them:

| Model | SoC / panel | CDN channel | Full-ROM filename form |
| --- | --- | --- | --- |
| NSPanel Pro **86P** | PX30 / 480×480 | `nspanel-pro` *(480P — confirmed)* | `CoolKit_Sonoff_480P_<YYYYMMDD>_<ver>-ota.zip` (older: `NSPanel86P_CoolKit_480P_…`) |
| NSPanel Pro **120P** | rk3326-S / 750×1334 | `nspanel-pro-ver120` *(confirmed)* | `SN_3326S_750X1334_4lan_V<ver>_<YYYYMMDD>-ota.zip` |

## CDN URL scheme

Host: `global-otadl2bsy.coolkit.cc` (CoolKit OTA CDN).

```text
Full ROM:  https://global-otadl2bsy.coolkit.cc/<channel>/rom/<INDEX>/<full-rom-filename>
Diff:      https://global-otadl2bsy.coolkit.cc/<channel>/rom-diff/<INDEX>/CK_<from>_<to>[V<apk>]-diff.zip
App (apk): https://global-otadl2bsy.coolkit.cc/<channel>/apk/<INDEX>/<apk-filename>
```

Key facts about the scheme:

- `<INDEX>` is a **per-build serial, NOT sequential** by version — you cannot
  guess it from the version number; it must be discovered/recorded per image. For
  `rom-diff`, the index is **per-target-version**: every diff landing on the same
  target shares one index (e.g. on 86P all `_4.0.10` diffs live at `rom-diff/45`).
- Diff filenames differ by model: **120P** carry a `V<apk>` suffix
  (`CK_4.0.12_4.4.0V228-diff.zip`); **86P** are **bare** (`CK_4.0.12_4.4.0-diff.zip`).
- The CDN returns **HTTP 403** for any object that does not exist (there is no
  directory listing). A real object returns **206** to a range request, with a
  `Content-Range` total size and the ZIP magic `50 4b 03 04` ("PK..").

Verify any candidate cheaply, without downloading the whole image:

```bash
curl -sS -I -L "<url>"                       # 206 + Content-Range total = exists
curl -sS -r 0-3 -L "<url>" | od -An -tx1     # 50 4b 03 04 = real ZIP
```

## Update-path rule (both models)

Past ~3.0.0 (86P) / ~3.5.0 (120P) the on-device updater is **incremental-only**
for most builds — you flash a **chain** of `-diff.zip` images, not a single full
ROM. The one verified exception is the **4.0.12 full ROM**: it is distributed
full-only (no inbound diff exists on the CDN) and *is* accepted on-device — it's
the sanctioned checkpoint you pass through to reach 4.4.0 (confirmed by thib3113
running 4.4.0 on hardware, seaky #262). So a real chain to the latest looks like:

```text
… → 4.0.10 (diff) → 4.0.12 (FULL ROM) → 4.4.0 (diff)
```

> [!NOTE]
> Whether the `→ 4.0.10` diff step is strictly required, or the 4.0.12 full ROM can
> be flashed directly from a 3.x build (skipping 4.0.10), is untested on our
> hardware — the full ROM being the accepted checkpoint suggests the direct 2-step
> path works. A flash test will confirm.

The chain links — and which step is a full ROM vs a diff — are exactly what gets
lost in forum excerpts. Mapping complete chains is the point of this index.

## 120P firmware (`nspanel-pro-ver120`)

### Confirmed (verified URL + index)

| Version | Type | Index | Filename | Size (B) | Verified |
| --- | --- | --- | --- | --- | --- |
| 4.0.12 | full ROM | `rom/21` | `SN_3326S_750X1334_4lan_V4.0.12_20251031-ota.zip` | 865677434 | 2026-06-17 |
| 4.0.10 ← 3.7.1 | diff | `rom-diff/20` | `CK_3.7.1_4.0.10V228-diff.zip` | 239184493 | 2026-06-17 |
| 4.4.0 ← 4.0.12 | diff | `rom-diff/23` | `CK_4.0.12_4.4.0V228-diff.zip` | 311726756 | 2026-06-17 |
| 4.4.0 (app) | apk | `apk/60` | `228V4.4.0.apk` | 139302716 | 2026-06-17 |

Highest known 120P firmware: **4.4.0** (diff-only, forward from 4.0.12). No
≥4.5.0 image exists as of 2026-06-17. Corroborated by seaky #262 (thib3113 ran
4.4.0 on an N120P).

### Other diffs confirmed to exist (CDN index pending)

Additional `from → to` diff pairs verified to exist. Per-image indices not yet
recorded:

`3.0.0→3.8.0`, `3.5.0→{3.7.0, 3.8.7, 3.9.4, 4.0.0}`, `3.5.1→3.7.0`,
`3.6.1→{3.7.0, 3.7.1, 3.8.0, 3.8.7, 3.9.4, 4.0.0}`,
`3.7.0→{3.7.1, 3.8.0, 3.8.7, 3.9.4, 4.0.0}`,
`3.7.1→{3.8.0, 3.8.7, 3.9.4, 4.0.0, 4.0.7}`,
`3.8.0→{3.8.7, 3.9.4, 4.0.0}`, `3.8.7→{3.9.4, 4.0.0}`, `3.9.4→4.0.0`,
`4.0.0→4.0.7`.

### 3.7.1 → 4.4.0 update chain (verified 2026-06-17)

Identical topology to the 86P — **not** all-diff. `4.0.12` is full-ROM-only (no
inbound diff exists: ~10k HEAD probes across idx 1–90 and both filename forms, all
403) and the updater accepts that full ROM as the checkpoint to 4.4.0.

From any 3.x (example 3.7.1) → 4.4.0 — 2 diffs + 1 full ROM:

1. **diff** `rom-diff/20/CK_3.7.1_4.0.10V228-diff.zip` (239184493 B) → 4.0.10 — single hop from base
2. **full ROM** `rom/21/SN_3326S_750X1334_4lan_V4.0.12_20251031-ota.zip` (865677434 B) → 4.0.12
3. **diff** `rom-diff/23/CK_4.0.12_4.4.0V228-diff.zip` (311726756 B) → 4.4.0

Other verified entry diffs onto 4.0.10 (`rom-diff/20`): `4.0.0→4.0.10` (42591720),
`3.9.4→4.0.10` (238898829), `4.0.7→4.0.10` (41814256). Also `4.0.0→4.0.7` at
`rom-diff/19` (41273089).

**Dead-ends (verified):** 4.0.12 has no inbound diff; 4.0.10/4.0.11 have no
outbound diff (4.0.10 is the highest diff-reachable version, then it terminates);
no `4.0.7→4.0.12`, `4.0.x→4.4.0`, `3.7.1→4.0.12`, or `3.7.1→4.4.0` exists — the
only inbound diff to 4.4.0 is from 4.0.12.

## 86P firmware (`nspanel-pro`, 480P)

Channel confirmed `nspanel-pro`. 86P diff filenames are **bare**
(`CK_<from>_<to>-diff.zip`, no `V<apk>` suffix). All indices below live-verified
2026-06-17 (range-GET, 206 + size + PK magic).

### Full ROMs (verified index)

| Version | Date | Index | Filename | Size (B) |
| --- | --- | --- | --- | --- |
| 1.5.0 | 2022-12-13 | `rom/14` | `NSPanel86P_CoolKit_480P_20221213_1.5.0-ota.zip` | — |
| 1.5.6 | 2023-02-17 | `rom/16` | `NSPanel86P_CoolKit_480P_20230217_1.5.6-ota.zip` | — |
| 1.6.0 | 2023-03-11 | `rom/17` | `NSPanel86P_CoolKit_480P_20230311_1.6.0-ota.zip` | — |
| 1.7.0 | 2023-04-11 | `rom/18` | `CoolKit_Sonoff_480P_20230411_1.7.0-ota.zip` | — |
| 1.11.0 | 2023-08-08 | `rom/22` | `CoolKit_Sonoff_480P_20230808_1.11.0-ota.zip` | — |
| 2.2.0 | — | `rom/25` | `CoolKit_Sonoff_480P_…_2.2.0-ota.zip` | — |
| 2.3.0 | 2023-12-08 | `rom/26` | `CoolKit_Sonoff_480P_20231208_2.3.0-ota.zip` | — |
| 3.0.0 | 2024-03-06 | `rom/27` | `CoolKit_Sonoff_480P_20240306_3.0.0-ota.zip` | — |
| 4.0.12 | 2025-10-31 | `rom/46` | `CoolKit_Sonoff_480P_20251031_4.0.12-ota.zip` | 888874130 |

Highest full ROM: **4.0.12** (`rom/46`).

### Diffs (verified) — index is per-target-version

Each `rom-diff/<idx>` holds the long-jump diffs from many source versions onto one
target, so any 3.x start collapses to a single diff hop to that target.

| Target | Index | Notes |
| --- | --- | --- |
| 3.6.1 | `rom-diff/32` | |
| 3.7.1 | `rom-diff/35` | |
| 3.8.0 | `rom-diff/36` | |
| 3.9.3 | `rom-diff/37` | |
| 3.9.4 | `rom-diff/38` | |
| 4.0.0 | `rom-diff/43` | |
| 4.0.7 | `rom-diff/44` | |
| 4.0.10 | `rom-diff/45` | highest diff-reachable; e.g. `CK_3.7.1_4.0.10-diff.zip` (347019387 B) |
| 4.4.0 | `rom-diff/48` | `CK_4.0.12_4.4.0-diff.zip` (229396793 B) — **only** from 4.0.12 |

Highest 86P firmware: **4.4.0** (diff-only, from 4.0.12), mirroring the 120P. No
4.4.0 full ROM; nothing ≥4.5.0 (swept idx 48–90).

### Update chain to 4.4.0

> [!WARNING]
> **4.0.12 is full-ROM-only** — no diff lands on 4.0.12 (every `from`→4.0.12 = 403,
> probed across rom-diff/0–90). Highest *diff-reachable* version is 4.0.10. So the
> path to 4.4.0 **must** flash the 4.0.12 full ROM mid-chain — and the updater does
> accept it (thib3113 reached 4.4.0 on hardware).

From any 3.x (example 3.7.1) → 4.4.0 — 2 diffs + 1 full ROM:

1. **diff** `rom-diff/45/CK_3.7.1_4.0.10-diff.zip` (347019387 B) → 4.0.10
2. **full ROM** `rom/46/CoolKit_Sonoff_480P_20251031_4.0.12-ota.zip` (888874130 B) → 4.0.12
3. **diff** `rom-diff/48/CK_4.0.12_4.4.0-diff.zip` (229396793 B) → 4.4.0

## Provenance

- CDN scheme + 120P indices verified 2026-06-17 via range-GET against
  `global-otadl2bsy.coolkit.cc`.
- Local image inventory: private `vendor-firmwares/sonoff/{ns86p,ns120p}/`
  (gitignored — not part of this repo or any release).
- Forum sources cross-checked: seaky/nspanel_pro_tools_apk #262,
  seaky/nspanel_pro_roottool_apk #1.

This index is the canonical reference to point at from the seaky threads instead
of re-pasting individual URLs.
