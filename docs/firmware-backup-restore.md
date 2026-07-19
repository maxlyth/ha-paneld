# Firmware backup & restore (rooted Rockchip wall panels)

The TPA10 and WF1589T do not provide the usual Android volume-and-power recovery combination. Standard guides that start with "hold Volume-Down + Power" therefore do not apply: these verified panels use Rockchip's Loader protocol instead.

A partition backup is read-only but can be large: it runs over the network with no mode change, needs root, and stages each image temporarily on the panel's shared storage ([Back up now](#back-up-now)). Confirm that the panel has enough free space for its largest partition before starting. Restoring is more involved, needs a USB cable and a laptop, and remains experimental until an end-to-end recovery has been proved on a spare unit.

The normal Loader-mode path uses the open-source, cross-platform `rkdeveloptool`; the NSPanel Pro uses separate model-specific tooling described under [Per-panel notes](#per-panel-notes).[^tools]

> [!NOTE]
> The commands below are scoped to the rooted TPA10 and WF1589T layouts recorded in [Per-panel notes](#per-panel-notes). Do not use them on an unrooted SMT1019 or ZX-SMT156, on a MediaTek Shelly Wall Display, or on an uncharacterised Generic profile. The restore steps have not been brick-tested, and Maskrom recovery is not yet documented because a verified model-specific loader file has not been established.

## Back up now

Read every partition straight off the running panel, over the network. These commands do not write partitions or change boot mode; you only need the panel's IP and [`adb`](#setting-up-rkdeveloptool).[^adb]

```bash
IP=192.168.1.50         # your panel's IP
mkdir -p backup && cd backup
adb -s $IP:5555 shell 'su 0 sh -c "for f in /dev/block/by-name/*; do echo \$(basename \$f) \$(readlink -f \$f); done"' \
  | while read name dev; do
      [ -z "$dev" ] && continue
      echo "backing up $name ($dev)"
      adb -s $IP:5555 shell "su 0 dd if=$dev of=/sdcard/$name.img bs=1M 2>/dev/null"
      adb -s $IP:5555 pull /sdcard/$name.img "./$name.img"
      adb -s $IP:5555 shell "rm /sdcard/$name.img"
    done
```

Keep the folder somewhere safe. Then capture the first 8 MiB of the eMMC as additional boot-layout evidence. This raw flash head is **not** a Rockchip MiniLoader file and must not be passed to `rkdeveloptool db`:

```bash
adb -s $IP:5555 shell "su 0 dd if=/dev/block/mmcblk2 of=/sdcard/idb_head.img bs=1M count=8"   # mmcblk2 on TPA10; mmcblk1 on WF1589T
adb -s $IP:5555 pull /sdcard/idb_head.img ./idb_head.img && adb -s $IP:5555 shell "rm /sdcard/idb_head.img"
```

> [!NOTE]
> **What you just saved.** Every named partition that completed successfully: the bootloader (`uboot`, `trust`, `security`), the system (`boot`, `recovery`, `super`, …) and your data (`userdata`, which can be very large). Compare every pulled file with `blockdev --getsize64` for its source partition before treating the backup as complete. These example commands need the verified `su 0` route on the TPA10 or WF1589T; SoC family alone does not imply root. NSPanel Pro uses [seaky's model-specific tooling](#per-panel-notes).

## Stop unwanted updates

If you've enabled adb you probably want to stop the vendor pushing an update that reverts your changes. Disable the updater app (reversible — `pm enable` puts it back):

```bash
adb -s $IP:5555 shell 'pm disable-user --user 0 com.elclcd.otaupdater'   # WF1589T
```

> [!NOTE]
> The updater app differs per panel — **WF1589T** is `com.elclcd.otaupdater`; **TPA10** has no dedicated updater (just watch for Tuya pushes). See [Per-panel notes](#per-panel-notes).

## If you ever need to restore

Restoring *writes* to the panel, so unlike backup it needs a **USB cable to a computer** and the `rkdeveloptool` program. Bring a laptop to the panel; you may have to unmount the panel to reach the USB port. One-time setup is in [Setting up rkdeveloptool](#setting-up-rkdeveloptool). The supported action depends on whether Android still boots:

**The panel still boots** — switch it to flashing mode over the network, write the partition back:

```bash
adb -s $IP:5555 reboot loader       # software entry — no buttons
rkdeveloptool ld                     # expect: <id>  Loader
rkdeveloptool wlx boot boot.img      # restore a partition by name
rkdeveloptool rd                     # reboot
```

**The panel won't boot** — stop here. Rockchip Maskrom can still enumerate a device, but writing from Maskrom first requires a valid model- and memory-specific MiniLoader. That file has not yet been sourced and restore-tested for these panels:

```bash
# after entering the model-specific hardware mode, observation only:
rkdeveloptool ld                     # expect: <id>  Maskrom
# Do not run `db`, `wl`, `wlx`, `gpt`, `ul` or `ef` without a verified MiniLoader and recovery plan.
```

> [!CAUTION]
> The raw `idb_head.img` captured during backup is not a substitute for a Rockchip MiniLoader. Do not rename it to `loader.bin` or pass it to `rkdeveloptool db`. Until a loader is extracted or obtained, authenticated and proved on a spare unit, only the software-entered Loader-mode restore path above is documented.

---

## Reference

Everything below is background and look-up — you don't need it to take a backup.

### Setting up rkdeveloptool

Only needed for [restore](#if-you-ever-need-to-restore). `rkdeveloptool` is the open-source replacement for the old Windows-only Rockchip tools; it talks to the panel over USB via [libusb](https://libusb.info), so it runs on whatever laptop you already have:

| Your computer | Works? | How |
| --- | --- | --- |
| **macOS** | ✅ | `brew install automake autoconf libusb`, then build (below) — this is what most people will use |
| **Windows** | ✅ via [WSL2](https://learn.microsoft.com/windows/wsl/about) | install [`usbipd-win`](https://github.com/dorssel/usbipd-win), then `usbipd bind` + `usbipd attach --wsl` to hand the USB device to Linux, and build below inside WSL2 |
| **Linux** | ✅ native | build below; the `99-rk-rockusb.rules` udev rule grants USB access |
| Windows native | ⚠️ legacy | the old `RKDevTool.exe` GUI + a special driver — avoid; use WSL2 instead |

Build it (same three commands on every platform):

```bash
git clone https://github.com/rockchip-linux/rkdeveloptool && cd rkdeveloptool
autoreconf -i && ./configure && make
sudo cp rkdeveloptool /usr/local/bin/
sudo cp 99-rk-rockusb.rules /etc/udev/rules.d/ && sudo udevadm control --reload   # Linux/WSL2 only
```

<details>
<summary>Prefer Docker? (Linux hosts only)</summary>

On a **Linux** host you can run it from a container instead of building:

```dockerfile
# Dockerfile
FROM debian:stable-slim
RUN apt-get update && apt-get install -y --no-install-recommends \
      build-essential autoconf automake pkg-config libusb-1.0-0-dev git ca-certificates \
 && git clone https://github.com/rockchip-linux/rkdeveloptool /src \
 && cd /src && autoreconf -i && ./configure && make && cp rkdeveloptool /usr/local/bin/ \
 && apt-get purge -y build-essential git && apt-get autoremove -y && rm -rf /var/lib/apt/lists/* /src
ENTRYPOINT ["rkdeveloptool"]
```

```bash
docker build -t rkdeveloptool .
docker run --rm -it --privileged -v /dev/bus/usb:/dev/bus/usb rkdeveloptool ld
```

Docker Desktop on macOS/Windows can't reach USB (it runs in a VM), so the container route is Linux-only.

</details>

> [!NOTE]
> Backup and switching to Loader mode use [`adb`](https://developer.android.com/tools/adb) over the network (port 5555) — **no USB**. USB is needed only for the `rkdeveloptool` steps, which is why those are the only part requiring a laptop at the panel.

### rkdeveloptool commands

From the tool's own `--help` (run `rkdeveloptool -h` to confirm your build):

| Command | Purpose |
| --- | --- |
| `ld` | list connected devices (and whether each is `Loader` or `Maskrom`) |
| `rfi` / `rid` / `rcb` | read flash info / flash ID / capacity |
| `ppt` | print the partition table (names + start [LBA](https://en.wikipedia.org/wiki/Logical_block_addressing) + size) |
| `rl <BeginSec> <SectorLen> <File>` | **read** an LBA range to a file (host-side backup) |
| `wl <BeginSec> <File>` | write a file at a raw LBA (advanced) |
| `wlx <PartitionName> <File>` | **write** a file to a *named* partition (restore — safer) |
| `db <Loader>` | load a loader onto a **Maskrom** device |
| `ul <Loader>` | upgrade the on-flash loader |
| `gpt <table>` | write a [GPT](https://en.wikipedia.org/wiki/GUID_Partition_Table) partition table |
| `ef` | erase flash |
| `rd [subcode]` | reset (reboot) the device |

### Why these panels need special steps

The TPA10 and WF1589T covered by the generic procedure are [Rockchip](https://en.wikipedia.org/wiki/Rockchip) SoCs (rk3566 / rk3576) booting from [eMMC](https://en.wikipedia.org/wiki/MultiMediaCard#eMMC). This does not describe every panel supported by ha-paneld. On these two models the usual Android advice fails because:

- **No buttons.** Guides that say "hold Volume-Down + Power for [fastboot](https://en.wikipedia.org/wiki/Fastboot)/recovery" can't apply.
- **Not fastboot.** Rockchip uses its own USB protocol, *rockusb*, with two [flashing modes](https://wiki.radxa.com/Rock5/install/rockchip-flash-tools):
    - **Loader** — entered in software with `adb reboot loader` (no buttons). Normal restores use this.
    - **Maskrom** — a rescue mode baked into the chip's ROM that runs even with a wiped bootloader. It starts with no RAM set up, so a valid model-specific MiniLoader is required before partition access. The raw eMMC head is not that loader, and this guide does not yet provide a write procedure from Maskrom.

### Per-panel notes

Partition tables read from live units.

**TPA10** (rk3566, Android 11, 7.28 GB eMMC `mmcblk2`) — root `su 0`; maskrom entry: the pin-hole by the USB-C port is the candidate (confirm reset-vs-maskrom before relying on it); USB-C port. Partitions: `security uboot trust misc dtbo vbmeta boot recovery backup cache metadata logo frp upgrade super userdata`.

**WF1589T** (rk3576, Android 14, 58.24 GB eMMC `mmcblk1`) — root `su 0`; updater `com.elclcd.otaupdater`; maskrom test-point not yet located. Partitions: `security uboot trust misc dtbo vbmeta boot recovery backup cache metadata frp baseparameter updatekey super userdata`.

**NSPanel Pro 86P** (px30) / **120P** (rk3326-S) — use seaky's proven tooling rather than rkdeveloptool: [roottool](https://github.com/seaky/nspanel_pro_roottool_apk) · [tools incl. firmware restore](https://github.com/seaky/nspanel_pro_tools_apk). Key facts distilled from seaky's issue threads (second-hand, not hardware-verified):

- **Never touch Rockchip vendor storage** (`/dev/vendor_storage`): slot **7** holds the licence string (items 4–5 are the two MACs), slot **8** the product id (`SN-RKPX30-NSP-01`). Wiping it boots the panel to a Chinese factory/QR screen. (roottool#1)
- **QR-screen unbrick *without* reflash:** sideload a launcher over adb → join Wi-Fi → reopen the eWeLink panel app → tap **Activate** a few times → the licence re-provisions online and the panel recovers. (roottool#9)
- **5× power-cycle** (power off at the Sonoff boot logo, repeated ×5) factory-resets to the *recovery partition's* firmware — often the **shipped version** (e.g. reverts a 2.3 unit to 1.7; OTA upgrades are not written to recovery). (roottool#1/#8)
- **No downgrade** (Android OTA restriction); **dev-mode/root is permanent** and survives factory reset (eWeLink-registered rooted devices also lose some cloud features permanently). (roottool#1)
- **Dead-eMMC signs:** backlight-on-then-off boot loop with recovery unresponsive, or a verify-fail when flashing a known-good file = eMMC fault (or a deleted system cert) — usually unrecoverable. (roottool#8/#12)
- Raw reflash = **RKDumper + Rockchip AndroidTool** over USB (maskrom; px30 needs a USB-driver tweak). Recovery-mode adb identity = `product/model/device = px30_evb`, shown as `rockchipplatform … recovery`. (roottool#2, tools#87)
- ⚠️ Cross-flashing **Sonoff OTA onto a Tuya T6E/S6E clone bricks it** (no `stop` binary, recovery-stuck). (tools#87)

### Safety notes

- Restore by partition **name** (`wlx`), not raw sector — this avoids manual offset mistakes. Confirm the exact partition name, image and panel model before writing.
- Avoid raw full-disk writes (`wl` at sector 0) — they hit a rockusb `0x2000`-sector offset quirk; stick to per-partition `wlx`/`rl`.
- Don't write `uboot`, `trust`, a loader or a partition table without a verified backup, an authenticated model-specific MiniLoader and a restore plan already proved on a spare.
- Rehearse a restore on a spare unit before doing it on a panel in a wall, if you can.

### Open gaps (help wanted)

- Confirm the **maskrom entry** per panel (TPA10 pin-hole = reset or maskrom? WF1589T test-point).
- Document **loader-file** sourcing/extraction per panel — the one prerequisite for maskrom rescue.
- One end-to-end **brick-and-restore test** on a spare to validate the restore steps.

[^tools]: The old vendor tools (`RKDevTool` / `AndroidTool`) are Windows-only GUIs. This guide uses `rkdeveloptool`, which is open-source and runs on Linux, macOS, or Windows (via WSL2) — see [Setting up rkdeveloptool](#setting-up-rkdeveloptool).

[^adb]: `adb` (Android Debug Bridge) is already in the Home Assistant SSH add-on. The panels expose it on TCP port 5555, so backup and entering Loader mode happen entirely over the network — no USB.
