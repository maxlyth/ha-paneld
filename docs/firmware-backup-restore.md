# Firmware backup & restore (button-less Rockchip panels)

Wall panels are **button-less** — no volume or power keys — so the ~99% of Android recovery guides on
the internet that start with "hold Volume-Down + Power to enter fastboot/recovery" are **non-starters**.
This page documents how to take a *usable* backup and restore it on these panels, using open-source,
Linux-native tooling — no Windows, no vendor portal.

> [!IMPORTANT]
> **Status: carefully researched, not yet verified end-to-end on hardware.** The live-backup path
> (Method A) is low-risk and safe to run today. The loader/maskrom restore paths (Methods B/C) are
> documented from the Rockchip tool source + device inspection but have **not** been brick-tested here.
> Read the [Safety](#safety) section before writing any partition. Per-panel maskrom entry points and
> loader binaries are the open gaps — contributions welcome.

## Why this is different

Every supported panel is a **Rockchip** SoC (px30 / rk3566 / rk3576) booting from **eMMC**. Two
consequences:

- **Rockchip does not use fastboot.** Its USB recovery protocol is *rockusb*, entered via **Loader** or
  **Maskrom** mode. `adb reboot bootloader` does **not** give you a fastboot prompt here.
- **The official tooling is awkward** — old, largely Windows-only (`RKDevTool`/`AndroidTool`), and
  thinly documented. The open-source **`rkdeveloptool`** replaces all of it and runs on Linux (including
  inside the Home Assistant SSH add-on, where `adb` already lives).

The unlock for button-less panels: **Loader mode has a software entry — `adb reboot loader` — that
needs no buttons at all**, and **Maskrom is an un-brickable hardware fallback** baked into the SoC ROM.

## The confidence path (do these in order)

1. **Make a live backup today** (Method A) — zero brick risk, no mode change, no buttons, **and no USB
   or physical access**: it runs over the network (`adb` over TCP) from your HA server or any LAN
   machine. This is the most important step and you can do it remotely, right now.
2. **Back up the bootloader/loader specifically** — this is what makes a *maskrom* restore possible.
3. **Disable OTA** so the vendor can't silently overwrite your rooted/modified setup.
4. Only then consider firmware changes, knowing restore is possible.

> [!IMPORTANT]
> **Backup is remote; restore is local.** Everything that *reads* (Method A's `adb`+`dd`, and entering
> Loader mode) happens over the network — no cable. Only the `rkdeveloptool` stages (Methods B/C) need a
> **physical USB link**, which means a **laptop carried to the panel** (you may have to unmount the panel
> to reach its USB port). `rkdeveloptool` has no network mode — rockusb is USB-only. So: back up from
> anywhere; keep a laptop for the rare restore/un-brick.

## The two Rockchip USB modes

| Mode | What it is | Entry (button-less) | `rkdeveloptool ld` shows |
| --- | --- | --- | --- |
| **Loader** | U-Boot's rockusb; eMMC driver already running | **`adb reboot loader`** (software) | `Loader` |
| **Maskrom** | SoC ROM; runs when no valid bootloader, or via a test-point short | hardware short / pin-hole (panel-specific) | `Maskrom` |

Loader mode is what you'll use normally (it requires a *working* bootloader). Maskrom is the rescue mode
when the device won't boot — it works even with a wiped/corrupt bootloader, but because it starts with
**no DRAM or storage init**, you must first push a loader to it (`db <loader>`) before you can touch the
eMMC. **That means a maskrom rescue requires the matching loader binary — so back it up while healthy.**

## Tooling

```bash
# rkdeveloptool — open-source, built on libusb. Build from source:
git clone https://github.com/rockchip-linux/rkdeveloptool && cd rkdeveloptool
autoreconf -i && ./configure && make
sudo cp rkdeveloptool /usr/local/bin/
sudo cp 99-rk-rockusb.rules /etc/udev/rules.d/ && sudo udevadm control --reload   # Linux only
```

**Host requirements — not Linux-specific.** `rkdeveloptool` talks to the panel through **libusb**, so
any host where libusb can claim the Rockchip USB device (VID `0x2207`) works — it's a USB-access
requirement, not a POSIX one:

| Host | Works? | How |
| --- | --- | --- |
| **Linux** | ✅ native | the `99-rk-rockusb.rules` udev rule (above) grants device access |
| **macOS** | ✅ | `brew install automake autoconf libusb`, then the same `autoreconf/configure/make`; libusb claims the device directly |
| **Windows / WSL2** | ✅ via `usbipd-win` | `usbipd bind` → `usbipd attach --wsl` forwards the USB device into WSL2, then run `rkdeveloptool` in the WSL Linux |
| **Windows native** | ⚠️ legacy | `RKDevTool.exe` + Rockchip WinUSB driver (Zadig/DriverAssistant) — the old Windows-only path; WSL2 is the cleaner route |

> [!TIP]
> These panels do **`adb` over TCP** (network, port 5555), so the `adb reboot loader` step needs **no
> USB at all**. USB is required only for the `rkdeveloptool` rockusb/maskrom stage — so on WSL2 you only
> need `usbipd` to forward the rockusb device, sidestepping the known USB-adb passthrough quirks.

### Running rkdeveloptool in Docker (avoids building it)

If you don't want to build the tool, run it from a container. The catch is **USB passthrough**:

- **Linux host (incl. a Linux laptop): works well.** The container gets raw USB access:

    ```dockerfile
    # Dockerfile
    FROM debian:stable-slim
    RUN apt-get update && apt-get install -y --no-install-recommends \
          build-essential autoconf automake pkg-config libusb-1.0-0-dev git ca-certificates \
     && git clone https://github.com/rockchip-linux/rkdeveloptool /src \
     && cd /src && autoreconf -i && ./configure && make && cp rkdeveloptool /usr/local/bin/ \
     && apt-get purge -y build-essential git && apt-get autoremove -y \
     && rm -rf /var/lib/apt/lists/* /src
    ENTRYPOINT ["rkdeveloptool"]
    ```

    ```bash
    docker build -t rkdeveloptool .
    # --privileged + mounting the whole usb bus survives the device re-enumerating when it
    # switches into Loader/Maskrom mode (its /dev path changes):
    docker run --rm -it --privileged -v /dev/bus/usb:/dev/bus/usb rkdeveloptool ld
    ```

- **macOS / Windows host: a container does NOT solve USB.** Docker Desktop runs the engine inside a
  Linux VM, which has no path to the host's USB bus — so `--privileged -v /dev/bus/usb` has nothing to
  forward. Use the native routes from the host table instead: **macOS** → build with Homebrew `libusb`;
  **Windows** → WSL2 + `usbipd-win` (and you can run the Docker image *inside* that WSL2 distro once the
  device is `usbipd attach`ed to it).

Net: the easiest portable setup is a **Linux laptop** (native or the container above) carried to the
panel. On a Mac/Windows laptop, go native / WSL2 — containers won't bridge USB for you.

Verified command set (from the tool's own `--help`; run `rkdeveloptool -h` to confirm your build):

| Command | Purpose |
| --- | --- |
| `ld` | list connected rockusb devices (and whether each is `Loader` or `Maskrom`) |
| `rfi` / `rid` / `rcb` | read flash info / flash ID / capability (sector count) |
| `ppt` | print the partition table (names + start LBA + size) |
| `rl <BeginSec> <SectorLen> <File>` | **read** an LBA range to a file (backup) |
| `wl <BeginSec> <File>` | write a file at a raw LBA (advanced) |
| `wlx <PartitionName> <File>` | **write** a file to a *named* partition (restore — safer) |
| `db <Loader>` | download a loader to a **Maskrom** device (inits DRAM/eMMC) |
| `ul <Loader>` | upgrade the on-flash loader |
| `gpt <table>` | write a GPT partition table |
| `ef` | erase flash |
| `rd [subcode]` | reset the device (reboot) |

You also need a physical **USB connection** from your host (Linux / macOS / WSL2 — see table above) to
the panel — locate the port before you start (see [Per-panel reference](#per-panel-reference)).

## Method A — Live backup over adb (recommended first; no mode change)

The lowest-risk backup: read each partition straight off the running device. No reboot, no loader, no
maskrom — nothing that can brick. Requires root (`su 0`), which the rk3566/rk3576 panels have; on
NSPanel Pro use [seaky's roottool](#nspanel-pro--pro120-px30).

```bash
IP=192.168.1.50         # your panel's IP
mkdir -p backup && cd backup
# enumerate name -> block device, then dd each partition to /sdcard and pull it
adb -s $IP:5555 shell 'su 0 sh -c "for f in /dev/block/by-name/*; do echo \$(basename \$f) \$(readlink -f \$f); done"' \
  | while read name dev; do
      [ -z "$dev" ] && continue
      echo "backing up $name ($dev)"
      adb -s $IP:5555 shell "su 0 dd if=$dev of=/sdcard/$name.img bs=1M 2>/dev/null"
      adb -s $IP:5555 pull /sdcard/$name.img "./$name.img"
      adb -s $IP:5555 shell "rm /sdcard/$name.img"
    done
```

What to keep:

- **Firmware/bootloader** (`uboot`, `trust`, `security`, the loader in `mmcblk*boot0`/`idblock`) — small,
  and the bit that makes maskrom rescue possible. **Back these up first.**
- **System** (`boot`, `recovery`, `dtbo`, `vbmeta`, `super`, `logo`/`baseparameter`, `misc`).
- **`userdata`** is your data/config — large (tens of GB on rk3576). Back up separately/occasionally;
  it's not "firmware".

> [!TIP]
> The Rockchip loader/IDB lives at the start of the eMMC (and a copy in the `mmcblk*boot0` hardware boot
> area), not always in a tidy `by-name` partition. Also dump the first few MB of the raw eMMC
> (`dd if=/dev/block/mmcblk2 of=/sdcard/idb_head.img bs=1M count=8`) so you capture it.

## Method B — Loader-mode backup/restore with rkdeveloptool (no buttons)

For a host-side image (and the basis for restore), with the panel still bootable:

```bash
adb -s $IP:5555 reboot loader      # software entry — NO buttons
rkdeveloptool ld                    # expect: <id>  Loader
rkdeveloptool ppt                   # note each partition's start sector + length
# Backup a partition (use the start/len from ppt):
rkdeveloptool rl 0x4000 0x20000 boot.img
# Restore a partition by NAME (no sector maths — preferred):
rkdeveloptool wlx boot boot.img
rkdeveloptool rd                    # reboot back to Android
```

`wlx <PartitionName>` is safer than `wl <sector>` — it targets the partition by its GPT label, so you
can't miscalculate an offset and clobber the wrong region.

## Method C — Maskrom rescue (device won't boot)

When the bootloader is dead and `adb reboot loader` is unreachable:

1. Put the panel in **maskrom** (panel-specific hardware step — pin-hole / test-point short while
   powering; see [Per-panel reference](#per-panel-reference)).
2. `rkdeveloptool ld` → expect `Maskrom`.
3. `rkdeveloptool db <loader.bin>` — push the **matching** loader (this is why you backed it up).
4. Then `ppt` / `wlx` as in Method B, or flash a full vendor image with `upgrade_tool`.

> [!CAUTION]
> Maskrom needs the **device-specific** loader (`MiniLoaderAll.bin` / `*_loader_*.bin`). Without it the
> SoC can't init DRAM and you cannot proceed. If you have no loader file and no live backup, maskrom can
> see the chip but not rescue it. **Sourcing/extracting the per-panel loader is the single most important
> prerequisite — do it before you ever modify firmware.**

## OTA opt-out (stop the vendor overwriting your setup)

Enabling adb often means you want to *freeze* firmware so a vendor OTA doesn't revert your changes.
Disable the updater (reversible — `pm enable` restores it; don't hard-uninstall a system app):

```bash
# WF1589T (rk3576) — dedicated updater present:
adb -s $IP:5555 shell 'pm disable-user --user 0 com.elclcd.otaupdater'
# (optionally) adb ... 'pm disable-user --user 0 com.google.android.configupdater'
```

- **WF1589T:** OTA app is `com.elclcd.otaupdater` (confirmed by inspection 2026-06-07).
- **TPA10:** no dedicated OTA package was found (only `com.tuya.devicetest`); the Tuya update path is
  unclear — monitor for vendor pushes rather than assuming it's safe.

## Per-panel reference

Partition tables below were read live (2026-06-07) and are model facts, not device secrets.

### TPA10 (rk3566, Android 11, 7.28 GB eMMC `mmcblk2`)

- Root: `su 0` available. Maskrom entry: the **pin-hole next to the USB-C port** is the candidate —
  documented as maskrom/reset; **confirm reset-vs-maskrom with a test before relying on it.** USB: the
  USB-C port.
- Partitions: `security uboot trust misc dtbo vbmeta boot recovery backup cache metadata logo frp upgrade super userdata`.

### WF1589T (rk3576, Android 14, 58.24 GB eMMC `mmcblk1`)

- Root: `su 0` available. OTA app: `com.elclcd.otaupdater`. Maskrom entry: test-point (TBD — not yet
  located). USB: TBD.
- Partitions: `security uboot trust misc dtbo vbmeta boot recovery backup cache metadata frp baseparameter updatekey super userdata`.

### NSPanel Pro / Pro120 (px30)

Use **seaky's** battle-tested tooling rather than re-deriving — it predates this guide and is proven on
the px30:

- [seaky/nspanel_pro_roottool_apk](https://github.com/seaky/nspanel_pro_roottool_apk) — rooting.
- [seaky/nspanel_pro_tools_apk](https://github.com/seaky/nspanel_pro_tools_apk) — tools incl. firmware
  restore.

The `rkdeveloptool` loader/maskrom method here also applies to the px30, but seaky's tools are the
recommended path for that model.

## Safety

- Prefer **`wlx <name>`** over `wl <sector>`; let the tool resolve the offset from the GPT.
- Raw **full-image** writes have a rockusb LBA-offset caveat (writes shifted by `0x2000` sectors in
  loader mode) — for offset-0 raw images use maskrom. Per-partition `wlx`/`rl` is unaffected; **prefer
  per-partition operations** and avoid raw full-disk writes unless you understand this.
- **Never** write `uboot`/`trust`/loader without (a) a verified backup of them and (b) the loader file in
  hand for maskrom rescue.
- If you have a spare unit, **test the restore path on it first**. Don't let a panel in a wall be your
  first restore attempt.
- Keep `userdata` backups separate and current — it holds your app config.

## Open gaps (help wanted)

- Physically confirm the **maskrom entry** per panel (TPA10 pin-hole = reset or maskrom? WF1589T
  test-point location).
- Document **loader binary** sourcing/extraction per panel — the gating prerequisite for maskrom rescue.
- End-to-end **brick-and-restore test** on a spare to validate Methods B/C.
