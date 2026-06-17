# Sonoff NSPanel Pro — firmware & flashing

How Sonoff NSPanel Pro OTA firmware is distributed, how to verify a download URL, and the verified procedure to update a panel (e.g. 3.x → 4.4.0).

> [!NOTE]
> The **live, community-maintained version index** — every verified OTA URL for 86P and 120P, with sizes and CDN indices — lives in a GitHub Discussion, because it changes faster than this repo's release cycle and takes community contributions: **[NSPanel Pro firmware — OTA URL index](https://github.com/maxlyth/ha-paneld/discussions/7)**. This page is the stable scheme + how-to; the Discussion is the current list.

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
- The CDN returns **403** for any object that doesn't exist (no directory listing). A real object returns **206** to a range request, with the total size and the ZIP magic `50 4b 03 04` ("PK..").

Verify any candidate cheaply, without downloading the whole image:

```bash
curl -sS -I -L "<url>"                       # 206 + Content-Range total = exists
curl -sS -r 0-3 -L "<url>" | od -An -tx1     # 50 4b 03 04 = real ZIP
```

## Update-path rule — VERIFIED on hardware (2026-06-17)

Past ~3.0.0 (86P) / ~3.5.0 (120P) the on-device updater is incremental-only for most builds — **except 4.0.12**, which is distributed **full-ROM-only** (no inbound diff exists on either channel) and *is* accepted on-device as a checkpoint. So the path to the latest is **2 steps**, not an all-diff chain:

```text
<your 3.x / 4.0.x build> → 4.0.12 (FULL ROM) → 4.4.0 (diff)
```

Verified on a 120P: **3.7.1 → 4.0.12 full ROM (applied directly) → 4.4.0 diff**. The intermediate 4.0.10 diff is **not** needed. Highest firmware on both models is **4.4.0**; nothing ≥4.5.0 exists.

## Flashing a panel (verified procedure)

This is the fully-remote, root + `adb` method (no USB needed) used to take a 120P from 3.7.1 to 4.4.0. It works because the panel's `/data` is unencrypted (`getprop ro.crypto.state` → `unsupported`), so recovery can apply the on-device zip via a block-map. Recovery has **no network adb**, which is why this on-device command-file method is used rather than `adb sideload`.

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

4. **Verify, then repeat for the diff.** Reconnect and check `getprop ro.product.version`. Then run steps 2–3 again with the `4.0.12→4.4.0` diff (the panel must be on 4.0.12).

**What survives:** `/data` is not touched by these OTAs — apps, settings, adb/USB-debugging, an installed modern WebView, and ha-paneld (including its boot auto-start) all persist. A factory reset would wipe them; this method does not.

## Provenance

- CDN scheme + indices verified 2026-06-17 via range-GET against `global-otadl2bsy.coolkit.cc`; full chain flash-verified on a 120P the same day.
- Local image inventory is kept in a private archive (gitignored; not shipped here).
