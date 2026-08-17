# Sonoff NSPanel Pro — firmware & flashing

How Sonoff NSPanel Pro OTA firmware is distributed, how to verify a download URL, and the update procedure hardware-verified through 4.4.0, with CDN-verified extensions past it.

> [!NOTE]
> The **community-maintained version index** lives in a GitHub Discussion, because it changes faster than this repo's release cycle and takes community contributions: **[NSPanel Pro firmware — OTA URL index](https://github.com/maxlyth/ha-paneld/discussions/7)**. It carries the recent upgrade targets rather than the whole history, since GitHub caps a Discussion body; every verified OTA URL for 86P and 120P, with sizes, CDN indices and archival status, is in the [complete index](nspanel-pro-firmware-archive.md). This page is the stable scheme and how-to; the Discussion carries the per-version link list. That Discussion body is regenerated from `tools/firmware-index/*.dat` by the scheduled monitor, so it can lag this repo until that job next runs successfully — when the two disagree, the `.dat` files are the authority.

Two physically distinct models, each on its **own** CDN channel — do not cross them:

| Model | SoC / panel | CDN channel | Diff filename form |
| --- | --- | --- | --- |
| NSPanel Pro **86P** | PX30 / 480×480 | `nspanel-pro` | bare `CK_<from>_<to>-diff.zip` |
| NSPanel Pro **120P** | rk3326-S / 750×1334 | `nspanel-pro-ver120` | `CK_<from>_<to>V<apk>-diff.zip` |

## CDN URL scheme

Host: `global-otadl2bsy.coolkit.cc` (CoolKit OTA CDN).

```text
Full ROM:  https://global-otadl2bsy.coolkit.cc/<channel>/rom/<INDEX>/<full-rom>
Diff:      https://global-otadl2bsy.coolkit.cc/<channel>/rom-diff/<INDEX>/<diff>
```

- `<INDEX>` is a **per-build serial, NOT sequential** by version — it must be discovered/recorded per image. For `rom-diff` the index is per *target* version (all diffs onto the same target share one index).
- A real object returns **206** to a range request, with the total size and the ZIP magic `50 4b 03 04` ("PK.."). Anything else returns **403** — but that only means *nothing is at the exact path you tried*. There is no directory listing and `<INDEX>` cannot be derived from the version, so a failed probe rules out one filename at one index, never the existence of a build.

Verify any candidate cheaply, without downloading the whole image:

```bash
curl -sS -I -L "<url>"                       # 206 + Content-Range total = exists
curl -sS -r 0-3 -L "<url>" | od -An -tx1     # 50 4b 03 04 = real ZIP
```

## Update-path rule — hardware-verified through 4.4.0

From ~3.0.0 (86P) / ~3.5.0 (120P) onward, the on-device updater is incremental-only for most builds. The exception is **4.0.12**: it is distributed **full-ROM-only** (no inbound diff was found on either channel) and is accepted on-device as a checkpoint. From a 3.x or early-4.0.x build, CDN inspection indicates this candidate two-step path:

```text
<your 3.x / 4.0.x build> → 4.0.12 (FULL ROM) → <target> (diff)
```

The 4.0.12 step is **not** universal. Which targets are reachable in one hop depends on the model and on the version the panel starts from: several releases carry inbound diffs from 4.4.0, 4.5.1, 4.5.3 or 4.6.0 as well as from 4.0.12, so a panel already past the checkpoint often needs no round trip through it. Some releases are APK-only and carry no ROM diff at all, and a release can be a ROM diff on one model and APK-only on the other — the **4.5.3** release is a ROM diff on 120P but an APK-only update on 86P, and **4.6.2** is indexed as an app-only update with no ROM diff on either channel. The per-model diff tables in the firmware index Discussion list the recent upgrade targets and are the authority on the route; every object ever indexed, including the older releases the Discussion no longer shows, is in the [complete index](nspanel-pro-firmware-archive.md). The intermediate 4.0.10 diff is **not** needed.

Verified on a 120P: **3.7.1 → 4.0.12 full ROM (applied directly) → 4.4.0 diff**. 4.4.0 was the target at the time, and nothing past it has been live-flash verified by this project — later diffs are CDN-verified only. For the most recently indexed releases no vendor documentation was found when this page was last checked (2026-08-14): [Sonoff's public changelog](https://sonoff.tech/en-us/blogs/news/sonoff-nspanel-pro-version-update-information-and-faq) documents up to **4.6.0**, 4.6.2 and 4.8.0 were located only by probing the CDN, and 4.7.0 is discussed only in an [eWeLink user feedback thread](https://forum.ewelink.cc/t/nspanel-pro-v4-7-0-feeback/208789) — a discussion thread, not a release announcement and not official release notes. For **4.8.0** the only vendor statement found is an eWeLink staff post of 2026-07-16 scheduling it for August; no changelog for it has been published. Absence from the index means not-found-by-probe — the CDN cannot be listed, so it is never proof a build does not exist.

> [!WARNING]
> **Community reports describe restart loops on 4.5.1 / 4.5.2** (~10–60 min intervals, both 86P and 120P). For **4.7.0**, the user feedback thread contains reports of sub-device connectivity trouble after updating, some resolved by a reboot and others described as continuing. This project has not reproduced or quantified them, so treat them as unverified user reports rather than a known regression. For **4.8.0** no feedback thread has been found at all, which is an absence of evidence rather than a clean bill of health. Verify any of these on one panel before deploying widely; **4.0.12** remains the conservative full-ROM checkpoint to pin. The firmware index Discussion carries the current community evidence, regenerated from this repo's index whenever the scheduled monitor next runs.

## Flashing a panel (hardware-verified through 4.4.0)

This fully remote root + `adb` method (no USB needed) was used on a 120P from 3.7.1 through 4.0.12 to 4.4.0. The same package shape and CDN inspection establish the diff paths indexed past 4.4.0, but no step past 4.4.0 has been recorded as live flash evidence here. The method works because the panel's `/data` is unencrypted (`getprop ro.crypto.state` → `unsupported`), so recovery can apply the on-device ZIP via a block map. Recovery has **no network adb**, which is why this command-file method is used rather than `adb sideload`.

In every block, `DEV=<PANEL_IP>:5555` and the panel is rooted (`su` works).

> [!CAUTION]
> Back up first, and when imaging partitions do **not** stream them through `adb exec-out`/`adb shell ... dd` — the shell PTY translates `\n`→`\r\n` and silently corrupts the binary. Use `dd`-to-file then `adb pull`, and verify each image against `blockdev --getsize64`.

1. **Identify + back up.** Confirm `getprop ro.product.version` and root; image the partitions (`dd if=/dev/block/by-name/<p> of=/sdcard/_bk.img` → `adb pull` → verify size). The identity-critical small partitions are `STSN`, `keypart`, `smatek`.

2. **Push the firmware zip** to the panel and confirm it landed intact:

   ```bash
   adb -s $DEV shell su -c 'rm -f /data/local/tmp/update.zip /cache/recovery/block.map /cache/recovery/command'
   adb -s $DEV push <firmware.zip> /data/local/tmp/update.zip
   adb -s $DEV shell su -c 'sha256sum /data/local/tmp/update.zip'   # must match the host file
   ```

3. **Apply via recovery** — `uncrypt` builds a block-map (recovery reads the zip by raw blocks, no /data mount), then a command file tells recovery what to apply:

   ```bash
   adb -s $DEV shell su -c 'uncrypt /data/local/tmp/update.zip /cache/recovery/block.map'
   adb -s $DEV shell su -c 'head -2 /cache/recovery/block.map'   # line1=/dev/block/by-name/userdata, line2=<size> 4096
   printf -- '--update_package=@/cache/recovery/block.map\n--locale=en_US\n' > rcmd
   adb -s $DEV push rcmd /sdcard/rcmd
   adb -s $DEV shell su -c 'cp /sdcard/rcmd /cache/recovery/command && chmod 644 /cache/recovery/command && rm -f /sdcard/rcmd'
   adb -s $DEV shell su -c 'sync; reboot recovery'
   ```

   The panel leaves adb, shows a recovery progress UI applying the package (~5 min for the full ROM, ~13 min for a diff), then reboots to Android on its own.

4. **Verify before considering a newer diff.** Reconnect and check `getprop ro.product.version`. Repeating steps 2–3 with the `4.0.12→4.4.0` diff follows the hardware-verified procedure. The diffs indexed past 4.4.0 have only been verified as valid CDN packages by this project; applying one is an experimental step that should first be tried on one recoverable panel. See the firmware index Discussion for the CDN URLs and evidence status.

**What survives:** `/data` is not touched by these OTAs — apps, settings, adb/USB-debugging, an installed modern WebView, and ha-paneld (including its boot auto-start) all persist. A factory reset would wipe them; this method does not.

## Provenance

- CDN scheme + indices verified via range-GET against `global-otadl2bsy.coolkit.cc`; the chain through 4.4.0 was flash-verified on a 120P, while the diffs indexed past it are CDN-verified only.
