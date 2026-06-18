# Firmware index, availability monitor, and Wayback archiver

Source of truth for the **NSPanel Pro firmware OTA download index** published in
[Discussion #7](https://github.com/maxlyth/ha-paneld/discussions/7), plus the
2-hourly checker that annotates every download link with a 24-hour availability
sparkline, and a weekly job that preserves every firmware file in the Internet
Archive.

## Files

- `fw-120p.dat`, `fw-86p.dat` — the verified link data (one device per file).
- `firmware_index.py` — generator (`render`) and prober (`probe`).
- `wayback_archive.py` — submits the index page + every firmware URL to the Wayback Machine.
- [`.github/workflows/firmware-url-monitor.yml`](../../.github/workflows/firmware-url-monitor.yml)
  runs `probe` then `render` every 2 hours and rewrites the Discussion body.
- [`.github/workflows/firmware-wayback.yml`](../../.github/workflows/firmware-wayback.yml)
  archives to the Internet Archive weekly and on any `fw-*.dat` change.

## Data format

```text
channel    <cdn-channel>                  # nspanel-pro (86P) | nspanel-pro-ver120 (120P)
diffsuffix <suffix-or-empty>              # 120P diffs carry V228; 86P diffs are bare
apkfmt     <apk-filename-prefix>          # 86P "app", 120P "228V"
full|<ver>|<idx>|<filename>|<bytes>
diff|<target-ver>|<idx>|<from-ver>:<bytes>|<from-ver>:<bytes>...
apk|<ver>|<idx>|<bytes>
```

`<idx>` is the per-build serial in the CDN path. The `rom-diff` index is per
**target** version, so one `diff|` line lists every `from` variant that shares it.

URLs are built as:

- Full ROM — `https://global-otadl2bsy.coolkit.cc/<channel>/rom/<idx>/<filename>`
- Diff — `…/<channel>/rom-diff/<idx>/CK_<from>_<to><suffix>-diff.zip`
- APK — `…/<channel>/apk/<idx>/<apkfmt><ver>.apk`

## Usage

```bash
# Regenerate the Discussion body locally (grey squares — no live data):
python tools/firmware-index/firmware_index.py render --out body.md

# Check every URL and append a sample to a history file:
python tools/firmware-index/firmware_index.py probe --history history.json

# Render with that history so the sparklines are populated:
python tools/firmware-index/firmware_index.py render --history history.json --out body.md
```

Stdlib only — no dependencies. Sparkline: 🟩 reachable · 🟥 unreachable · ⬜ no
data yet (12 points = 24h at the 2-hourly cadence, newest on the right).

## Wayback archiving

`wayback_archive.py` preserves the index against the CoolKit CDN going away. It
saves the Discussion page (with `capture_outlinks=1`) and submits every firmware
URL to Save Page Now *explicitly* — `capture_outlinks` caps at 100 links, far
short of the ~196 files, so the page crawler alone is not relied on. Firmware
URLs are immutable, so each is archived once and recorded in the state file;
later runs only submit new ones.

Save Page Now rejects anonymous API calls, so a secret is required:

1. Create a free [archive.org account](https://archive.org/account/signup).
2. Generate S3 keys at [archive.org/account/s3.php](https://archive.org/account/s3.php).
3. Add them as the repo Actions secret **`WAYBACK_S3`** in the form `accesskey:secret`.

Without the secret the workflow skips cleanly (stays green). State lives on the
append-only `wayback-state` branch. Local run:

```bash
WAYBACK_S3="accesskey:secret" python tools/firmware-index/wayback_archive.py \
  --state wayback.json --page-url "https://github.com/maxlyth/ha-paneld/discussions/7" --max 5
```

## Adding or correcting a link

Edit the relevant `.dat` file and commit. The next scheduled run (or a manual
`workflow_dispatch`) republishes the Discussion. The history branch
(`firmware-status`) is machine-managed — don't edit it by hand. The Discussion
body itself is generated; edit the data here, not the post.

To find new builds, range-GET a candidate URL — a live file answers with `206`
and a `Content-Range` total; a missing one returns `403`:

```bash
curl -s -r 0-0 -D - -o /dev/null "<url>"
```
