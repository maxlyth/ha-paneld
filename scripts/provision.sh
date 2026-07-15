#!/usr/bin/env bash
# ha-paneld provisioning — install + grant all permissions over adb, no device UI, and (optionally)
# set the panel id + MQTT broker in one shot. Requires adb access and su/adb-root on the panel.
# For non-root panels, --shizuku installs and starts the pinned manager; approval remains on-panel.
#
# Usage:
#   scripts/provision.sh <panel-ip:5555> [APK] \
#       [--id PANEL_ID] [--mqtt tcp://host:1883] [--mqtt-user U] [--mqtt-pass P] [--apk PATH] \
#       [--log-host HOST] [--log-port N] [--log-proto syslog|http]   # forward logcat to an aggregator
#       [--shizuku]                    # non-root enhanced access; still needs local approval
#   Built-in WebView renderer (experimental — no HA Companion / kiosk app needed):
#       [--ha-url https://homeassistant.local:8123] [--builtin] \
#       [--ha-token <LLAT>]              # simple: a long-lived access token (a standing credential), OR
#       [--ha-user U --ha-pass P]        # preferred: log in HERE to mint a REFRESH token; the password
#                                        # never reaches the panel, and no 10-year token lives on it.
#   By default provisioning also TAMES the panel profile's recommended vendor apps (eWeLink overlay +
#   factory test tools) — reversible + never strands the panel; pass --no-tame to skip.
#   scripts/provision.sh <panel-ip:5555> --verify        # check end-state only, make no changes
#
# IDEMPOTENCY (safe to re-run after a cancel/failure — re-running converges to the full state):
#   - install -r / appops allow / pm grant / accessibility_enabled / am start  : no-ops on re-run
#   - enabled_accessibility_services : appends our service only if absent (guarded)
#   - POST /api/v1/config            : partial-merge — only sets the keys you pass
# `set -e` aborts cleanly on any failure; the ERR trap tells you to re-run, and every run ends with a
# self-verify checklist. `--verify` is the standing control: run any time to confirm a panel is set up.
set -euo pipefail

# Colours/emoji — only when writing to a terminal, so piped/redirected output stays clean.
if [ -t 1 ]; then
  B=$'\033[1m'; D=$'\033[2m'; X=$'\033[0m'
  RED=$'\033[31m'; GRN=$'\033[32m'; YEL=$'\033[33m'; CYN=$'\033[36m'; MAG=$'\033[35m'
else B=; D=; X=; RED=; GRN=; YEL=; CYN=; MAG=; fi
step() { echo "${CYN}${B}$1${X} $2"; }
warn() { echo "   ${YEL}⚠ $1${X}"; }

usage() {
  cat <<'EOF'
Usage: scripts/provision.sh <panel-ip[:port]> [APK] [options]

Common operations:
  --latest                 Install the latest stable release
  --prerelease             Install the latest release candidate
  --apk FILE               Install a specific APK
  --export FILE            Back up config only; never installs unless combined with install/config options
  --verify                 Check the existing installation only; never installs
  --no-tame                Leave vendor applications unchanged
  --shizuku                Install/start pinned Shizuku for locally approved non-root access
  --help                   Show this help

The script installs, starts, and verifies ha-paneld. It exits nonzero if a required step is incomplete.
EOF
}

trap 'echo "${RED}${B}✗ provisioning incomplete${X} — re-run the SAME command to finish (it is idempotent)." >&2' ERR

# Fatal with SPECIFIC recovery steps. Disarms the generic re-run trap first: these are the failures
# re-running cannot fix, so the trap's blanket advice would mislead.
fail() {
  trap - ERR
  echo "${RED}${B}✗ $1${X}" >&2; shift
  local l; for l in "$@"; do echo "   $l" >&2; done
  exit 1
}

[ "$#" -gt 0 ] || { usage >&2; exit 2; }
case "$1" in -h|--help) usage; exit 0 ;; esac
TARGET="$1"
shift
REPO="maxlyth/ha-paneld"
LOCAL_APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="io.github.maxlyth.hapaneld"
RELEASE_CERT_SHA256="ac6193307fb0b70113aae205d7549406f96e063bc5491b67b1d5694a34b0e339"
A11Y="$PKG/.input.PanelAccessibilityService"
APK=""; APK_RELEASE_TAG=""; PANEL_ID=""; MQTT=""; MQTT_USER=""; MQTT_PASS=""; VERIFY_ONLY=0; LATEST=0; PRERELEASE=0; FORCE=0; PERSIST_ADB=0; STRIP_VENDOR=0; NO_TAME=0; SHIZUKU=0; TOINSTALL_VER=""
LOG_HOST=""; LOG_PORT=""; LOG_PROTO=""; LOG_ENABLE=""
EXPORT_FILE=""; RESTORE_FILE=""; RESTORE_MODE=""
HA_URL=""; HA_TOKEN=""; HA_USER=""; HA_PASS=""; HA_REFRESH=""; HA_EXPIRY=""; BUILTIN=0
HA_LOGIN_FAILED=0

if [ "${1:-}" ] && [ "${1#--}" = "${1:-}" ]; then APK="$1"; shift; fi
while [ "${1:-}" ]; do
  case "$1" in
    --apk|--release-tag|--id|--mqtt|--mqtt-user|--mqtt-pass|--log-host|--log-port|--log-proto|--ha-url|--ha-token|--ha-user|--ha-pass|--export|--restore|--restore-fleet)
      if [ "$#" -lt 2 ] || [ -z "${2:-}" ] || [ "${2#--}" != "${2:-}" ]; then
        echo "${RED}✗ $1 needs a value.${X} Run with --help for examples." >&2
        exit 2
      fi
      ;;
  esac
  case "$1" in
    --apk) APK="$2"; shift 2 ;;
    --release-tag) APK_RELEASE_TAG="$2"; shift 2 ;; # internal: marks --apk as an official release asset
    --id) PANEL_ID="$2"; shift 2 ;;
    --mqtt) MQTT="$2"; shift 2 ;;
    --mqtt-user) MQTT_USER="$2"; shift 2 ;;
    --mqtt-pass) MQTT_PASS="$2"; shift 2 ;;
    --latest) LATEST=1; shift ;;     # ignore any local build, fetch the newest STABLE GitHub release
    --prerelease|--pre) PRERELEASE=1; LATEST=1; shift ;;   # fetch the newest release incl. release-candidates
    --force) FORCE=1; shift ;;       # skip the same/older-version prompt
    --persist-adb) PERSIST_ADB=1; shift ;;  # keep network adb (tcp 5555) across reboots (opt-in; standing LAN port)
    --strip-vendor) STRIP_VENDOR=1; shift ;; # disable the Tuya vendor apps (TPA10) non-interactively (skips the prompt)
    --no-tame) NO_TAME=1; shift ;;   # skip the default "tame recommended vendor apps" step (eWeLink + factory test tools)
    --shizuku) SHIZUKU=1; shift ;;   # install/start pinned Shizuku on a non-root panel; permission stays local
    --log-host) LOG_HOST="$2"; LOG_ENABLE=true; shift 2 ;;  # ship logcat to this aggregator (host enables shipping)
    --log-port) LOG_PORT="$2"; shift 2 ;;     # log sink port (default 514 for syslog)
    --log-proto) LOG_PROTO="$2"; shift 2 ;;   # syslog (default) | http
    --log-off) LOG_ENABLE=false; shift ;;     # disable log shipping
    --ha-url) HA_URL="$2"; shift 2 ;;         # Home Assistant URL for the built-in WebView renderer
    --ha-token) HA_TOKEN="$2"; shift 2 ;;     # long-lived access token (simple path; a standing credential)
    --ha-user) HA_USER="$2"; shift 2 ;;       # HA username — login here to mint a refresh token (no LLAT)
    --ha-pass) HA_PASS="$2"; shift 2 ;;       # HA password — used ONLY on this machine to log in; never sent to the panel
    --builtin) BUILTIN=1; shift ;;            # select ha-paneld's built-in renderer as the dashboard
    --export) EXPORT_FILE="$2"; shift 2 ;;    # save the panel's config bundle (incl. secrets) to FILE
    --restore) RESTORE_FILE="$2"; RESTORE_MODE="restore"; shift 2 ;;      # best-effort import of a bundle (full restore)
    --restore-fleet) RESTORE_FILE="$2"; RESTORE_MODE="fleet"; shift 2 ;;  # apply only PORTABLE keys (cross-panel deploy)
    --verify) VERIFY_ONLY=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "${RED}unknown arg: $1${X}" >&2; exit 2 ;;
  esac
done
valid_release_tag() { printf '%s\n' "$1" | grep -Eq '^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z][0-9A-Za-z.-]*)?$'; }
release_apk_name() { printf 'ha-paneld-%s-manual-setup-required.apk\n' "$1"; }
release_apk_url() { printf 'https://github.com/%s/releases/download/%s/%s\n' "$REPO" "$1" "$(release_apk_name "$1")"; }
release_checksum_url() { printf '%s.sha256\n' "$(release_apk_url "$1")"; }
release_signature_url() { printf '%s.sig\n' "$(release_checksum_url "$1")"; }
write_release_public_key() {
  cat > "$1" <<'EOF'
-----BEGIN PUBLIC KEY-----
MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA3LH+db6kzNld/ERP612x
UOOG6TINFvuKJKinQAWi6Gfm2jCmW4plhw+w4vXgP8B8FpY0SLatUVo3EeAi+f1K
EHj0syPi7Sx781o1oc9LicQG4LjWVZPe+m4AkPl9ByopobQwYTXOjaq6ZFpFgAZe
NwQ44hg5o9iVKtxpnnjHEc/m6o9TBySQvxDWF3RxCDyPLNBqhrsgKsDlAyh+dtA8
aJpQsDUJoX42xsRvA1hkRCpnWdEs1Bwfyv0ztlOxj7MxeFrFxWc3mnUyGhsn6rCT
O+ygQ2m7FHp3D5t1+wFIendluEzUC+y9MpUHmoyq/lFrVuA8EOiy1U+z7Lr1vBWf
LQIDAQAB
-----END PUBLIC KEY-----
EOF
}
if [ -n "$APK_RELEASE_TAG" ]; then
  valid_release_tag "$APK_RELEASE_TAG" || { echo "${RED}✗ invalid release tag: $APK_RELEASE_TAG${X}" >&2; exit 2; }
  [ -n "$APK" ] || { echo "${RED}✗ --release-tag requires --apk.${X}" >&2; exit 2; }
fi
if { [ -n "$HA_USER" ] && [ -z "$HA_PASS" ]; } || { [ -z "$HA_USER" ] && [ -n "$HA_PASS" ]; }; then
  echo "${RED}✗ --ha-user and --ha-pass must be supplied together.${X}" >&2
  exit 2
fi
if [ -n "$HA_TOKEN" ] && [ -n "$HA_USER" ]; then
  echo "${RED}✗ choose either --ha-token or --ha-user/--ha-pass, not both.${X}" >&2
  exit 2
fi
if { [ -n "$HA_USER" ] || [ -n "$HA_TOKEN" ]; } && [ -z "$HA_URL" ]; then
  echo "${RED}✗ --ha-user/--ha-pass and --ha-token require --ha-url.${X}" >&2
  exit 2
fi
if [ "$BUILTIN" = 1 ] && [ -n "$HA_URL" ] && [ -z "$HA_TOKEN" ] && [ -z "$HA_USER" ]; then
  echo "${RED}✗ --builtin with --ha-url also needs --ha-token or --ha-user/--ha-pass.${X}" >&2
  echo "   Use bare --builtin only when intentionally borrowing an existing signed-in Home Assistant Companion login." >&2
  exit 2
fi
HOST="${TARGET%:*}"
[ "$HOST" != "$TARGET" ] || HOST="$TARGET"
URL="http://$HOST:8888"
PROVISION_FAILED=0

verify() {
  step "🔎 verifying" "${D}$URL${X}"
  local health diag cfg rc=0
  # A COLD /diag probes the panel's capabilities (su, daemon, sensors) and takes >12s on a PX30;
  # warm it's instant. 4s here made verify fail on every fresh run — keep the timeout generous.
  health="$(curl -fsS --max-time 5 "$URL/health" 2>/dev/null || true)"
  diag="$(curl -fsS --max-time 25 "$URL/api/v1/diag" 2>/dev/null || true)"
  if [ -z "$diag" ] && [ -n "$health" ]; then
    warn "the agent is healthy but diagnostics are still starting; retrying once"
    sleep 3
    diag="$(curl -fsS --max-time 25 "$URL/api/v1/diag" 2>/dev/null || true)"
  fi
  cfg="$(curl -fsS --max-time 3 "$URL/api/v1/config" 2>/dev/null || true)"
  chk() { if printf '%s' "$2" | grep -q "$3"; then echo "   ${GRN}✓${X} $1"; else echo "   ${RED}✗ $1${X}"; rc=1; fi; }
  chk "HTTP server reachable"  "$health" "ha-paneld"
  chk "WRITE_SETTINGS granted" "$diag" "write_settings=true"
  chk "accessibility enabled"  "$diag" "a11y=true"
  # Root helper daemon — informational (only sandbox-walled panels need it; see the closing note).
  if printf '%s' "$diag" | grep -q "daemon=true"; then
    echo "   ${GRN}✓${X} root helper daemon: running"
  elif [ -n "$diag" ]; then
    echo "   ${YEL}ℹ${X} root helper daemon: ${YEL}not detected${X} ${D}(needed on sandbox-walled panels — helper/install-daemon.sh)${X}"
  fi
  # Root — the single biggest capability divider. Say it PLAINLY at install time so a no-root user
  # knows from the outset they're getting a subset (a panel-permissions shortfall, not ha-paneld bugs).
  if printf '%s' "$diag" | grep -q "su=true"; then
    echo "   ${GRN}✓${X} root (su): available — full feature set"
  elif printf '%s' "$diag" | grep -q "daemon=true"; then
    echo "   ${GRN}✓${X} root: via the helper daemon — full feature set"
  elif printf '%s' "$diag" | grep -q "shizuku=ready"; then
    echo "   ${GRN}✓${X} Shizuku enhanced access: ready — APK updates, screenshots/taps and display sizing enabled"
  elif [ -n "$diag" ]; then
    echo "   ${YEL}⚠ THIS PANEL HAS NO ROOT — ha-paneld runs with a REDUCED feature set.${X}"
    echo "     ${D}Working: HA sensors + MQTT, brightness, screen dim, audio/TTS, the dashboard renderers,${X}"
    echo "     ${D}the web UI, Back/Recents. Shizuku can add APK/self/Companion updates, display sizing,${X}"
    echo "     ${D}and screenshots/taps; true screen-off, LED, vendor taming, system logs and kiosk lock${X}"
    echo "     ${D}still need root. Re-run with --shizuku, then approve Enhanced access on the panel.${X}"
  fi
  # panel_id + MQTT (informational — install-only is valid). grep/cut so no python (Git Bash-friendly).
  local broker pid
  broker="$(printf '%s' "$cfg" | grep -o '"mqtt_broker":"[^"]*"' | head -1 | cut -d'"' -f4)"
  pid="$(printf '%s' "$cfg" | grep -o '"panel_id":"[^"]*"' | head -1 | cut -d'"' -f4)"
  if [ -n "$broker" ]; then echo "   ${GRN}✓${X} MQTT broker: ${B}$broker${X}"
  else echo "   ${YEL}ℹ${X} MQTT broker: ${YEL}not explicitly set${X} ${D}(LAN auto-discovery will be attempted; it can also be set in Configure)${X}"; fi
  echo "   ${YEL}ℹ${X} panel_id: ${B}${pid:-?}${X}"
  return $rc
}

# `adb connect` exits 0 even when it FAILS ("cannot connect"), and a connected device can still sit
# "unauthorized" (RSA dialog waiting on the panel screen) or "offline" (stale adbd session). Verify the
# state explicitly so the failure surfaces HERE with recovery steps, not as an obscure error (or a
# hang) at the first real adb command.
adb_preflight() {
  step "🔌 connecting" "$TARGET"
  # `adb connect` itself can block for MINUTES on a dead IP (TCP retry) — run it in the background
  # and bound the wait; the poll below reads the device state the adb server ends up with.
  adb connect "$TARGET" >/dev/null 2>&1 &
  local cpid=$!
  local state="" i=0
  while [ "$i" -lt 12 ]; do
    i=$((i + 1))
    state="$(adb devices 2>/dev/null | awk -v t="$TARGET" '$1==t {print $2}')"
    if [ "$state" = "device" ]; then kill "$cpid" 2>/dev/null || true; return 0; fi
    # Stale session ("offline"): reset it once, then keep polling.
    if [ "$state" = "offline" ] && [ "$i" = 4 ]; then
      adb disconnect "$TARGET" >/dev/null 2>&1 || true
      ( adb connect "$TARGET" >/dev/null 2>&1 & )
    fi
    sleep 1
  done
  kill "$cpid" 2>/dev/null || true
  case "$state" in
    unauthorized) fail "panel refused adb: unauthorized" \
      "Accept the ADB authorization dialog shown ON THE PANEL'S SCREEN (tick 'always allow'), then re-run." \
      "No dialog visible? Toggle 'ADB debugging' off/on in the panel's Developer options and re-run." ;;
    offline) fail "panel is stuck 'offline' on adb" \
      "Toggle 'ADB debugging' off/on in the panel's Developer options (or power-cycle the panel if you are next to it), then re-run." \
      "A session held by another machine can also cause this — run 'adb disconnect' there first." ;;
    *) fail "cannot reach $TARGET over adb" \
      "Check: the IP is right, network ADB is enabled (Developer options → 'ADB debugging' / 'Network ADB'), the port ($TARGET), and that this machine is on the same network/VLAN as the panel." \
      "Some panels only expose adb on USB until 'adb tcpip 5555' is run once — see docs/provisioning.md ('Bootstrapping adb')." ;;
  esac
}

# Root-path probe — vendor root varies TWICE over: the prefix (`su 0`, `su root`, `su -c`) AND the
# dialect. Join-style su (SuperSU/toolbox) re-joins argv and runs it through its own `sh -c`, so a
# command must be passed as ONE quoted word (`su 0 "cmd a b"`) — adding `sh -c` double-wraps and
# silently STRIPS the quoting ("getprop x" becomes a bare getprop). Execvp-style su (AOSP) execs argv
# directly, so a command string DOES need the `sh -c` wrapper. `"id; id"` only succeeds through a
# shell, so probing with it identifies the wrapping that preserves a multi-word command. Userdebug
# panels can also have a root adbd with NO su at all — probed first. Probe once, cache the winner.
# A su that prompts on-screen (Magisk) can take ~10s to auto-deny a form — the probe tolerates that.
SU_FORM=""
probe_su() {
  if [ -n "$SU_FORM" ]; then [ "$SU_FORM" != none ]; return; fi
  local u key pre
  u="$(adb -s "$TARGET" shell id 2>/dev/null | tr -d '\r')" || u=""
  case "$u" in uid=0*) SU_FORM=shell; return 0 ;; esac
  for key in su0 suroot; do
    case "$key" in su0) pre="su 0" ;; suroot) pre="su root" ;; esac
    u="$(adb -s "$TARGET" shell "$pre \"id; id\"" 2>/dev/null | tr -d '\r')" || u=""
    case "$u" in *uid=0*) SU_FORM="${key}join"; return 0 ;; esac
    u="$(adb -s "$TARGET" shell "$pre sh -c \"id; id\"" 2>/dev/null | tr -d '\r')" || u=""
    case "$u" in *uid=0*) SU_FORM="${key}shc"; return 0 ;; esac
  done
  u="$(adb -s "$TARGET" shell "su -c \"id; id\"" 2>/dev/null | tr -d '\r')" || u=""
  case "$u" in *uid=0*) SU_FORM=suc; return 0 ;; esac
  SU_FORM=none; return 1
}

# Run one command string as root via the probed form. Keep the string free of quotes — it reaches
# the device shell inside double quotes.
run_root() {
  probe_su || return 1
  case "$SU_FORM" in
    shell)      adb -s "$TARGET" shell "$1" ;;
    su0join)    adb -s "$TARGET" shell "su 0 \"$1\"" ;;
    su0shc)     adb -s "$TARGET" shell "su 0 sh -c \"$1\"" ;;
    surootjoin) adb -s "$TARGET" shell "su root \"$1\"" ;;
    surootshc)  adb -s "$TARGET" shell "su root sh -c \"$1\"" ;;
    suc)        adb -s "$TARGET" shell "su -c \"$1\"" ;;
  esac
}

# Tuya panels (TPA10) ship a closed vendor stack (launcher, system UI, Tuya IoT, hardware, diagnostics)
# that does nothing for an HA panel and uses CPU/RAM. Offer to disable it — but ONLY once the panel can
# run without it. Reversible: re-enable any package with `adb shell pm enable <pkg>`.
offer_strip_vendor() {
  local brand model
  brand="$(adb -s "$TARGET" shell getprop ro.product.brand 2>/dev/null | tr -d '\r')"
  model="$(adb -s "$TARGET" shell getprop ro.product.model 2>/dev/null | tr -d '\r')"
  case "$brand $model" in *[Tt]uya*|*TPA10*) ;; *) return 0 ;; esac   # Tuya/TPA10 only

  # Guard 1 — root (vendor apps are system apps; pm disable-user needs root on the userdebug build).
  if ! probe_su; then
    if [ "$STRIP_VENDOR" = 1 ]; then echo "   ${YEL}⚠ --strip-vendor ignored: no root path found (su or adbd-root).${X}"; fi
    return 0
  fi
  # Guard 2 — persistent adb, else disabling the diagnostics-app adb backdoor can lock you out.
  local adben tcp
  adben="$(adb -s "$TARGET" shell settings get global adb_enabled 2>/dev/null | tr -d '\r')"
  tcp="$(adb -s "$TARGET" shell getprop persist.adb.tcp.port 2>/dev/null | tr -d '\r')"
  if [ "$adben" != "1" ] || [ -z "$tcp" ]; then
    echo "   ${YEL}⚠ vendor-strip skipped: re-run with --persist-adb first (persistent adb is required so disabling the Tuya diagnostics backdoor can't lock you out).${X}"
    return 0
  fi
  # Guard 3 — a non-vendor home launcher must exist before we disable the vendor launcher.
  # ha-paneld itself ships an admin launcher (AdminLauncherActivity registers HOME), so it counts.
  local home=""
  for L in io.homeassistant.companion.android.minimal io.homeassistant.companion.android io.github.maxlyth.hapaneld; do
    if adb -s "$TARGET" shell pm path "$L" >/dev/null 2>&1; then home="$L"; break; fi
  done
  if [ -z "$home" ]; then
    echo "   ${YEL}⚠ vendor-strip skipped: install a replacement launcher first (HA Companion, or ha-paneld's admin launcher).${X}"
    return 0
  fi

  # --strip-vendor forces it; otherwise prompt on a TTY (default No).
  if [ "$STRIP_VENDOR" != 1 ]; then
    [ -t 0 ] || return 0
    echo "${MAG}${B}Tuya panel detected.${X} ${D}Its vendor apps (launcher, system UI, Tuya IoT, hardware, diagnostics) are a closed stack with nothing for HA and use CPU/RAM.${X}"
    printf "   Disable them for a faster, minimal panel? Reversible with ${B}pm enable${X}. [y/N] "
    read -r a; case "$a" in [yY]*) ;; *) echo "   keeping the vendor apps."; return 0 ;; esac
  fi

  step "🧹 minimising" "${D}home → $home; disabling Tuya vendor apps${X}"
  adb -s "$TARGET" shell cmd package set-home-activity "$home" >/dev/null 2>&1 || true
  for P in com.smartos.xinch.launcher com.smartos.xinch.systemui com.smartos.xinch.smartiot \
           com.smartos.xinch.hardware com.smartos.xinch.monitor com.smartos.xinch.setting \
           com.smartos.xinch.communicate com.tuya.devicetest; do
    adb -s "$TARGET" shell pm path "$P" >/dev/null 2>&1 || continue
    if adb -s "$TARGET" shell pm disable-user --user 0 "$P" >/dev/null 2>&1; then
      echo "   ${GRN}✓${X} disabled $P"
    else
      echo "   ${YEL}–${X} could not disable $P"
    fi
  done
  echo "   ${D}undo any with: adb -s $TARGET shell pm enable <pkg>${X}"
}

# Warn when the system WebView is too old for a current HA frontend (the dashboard renders blank/broken).
# Informational only — points at the update instructions; does not change anything.
WEBVIEW_DOC="https://github.com/$REPO/blob/main/docs/hardware/tpa10.md#webview--update-this-first"
check_webview() {
  local wv major
  wv="$(adb -s "$TARGET" shell dumpsys webviewupdate 2>/dev/null | grep -m1 'Current WebView package' | grep -oE '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+' | head -1 || true)"
  [ -n "$wv" ] || return 0
  major="${wv%%.*}"
  case "$major" in ''|*[!0-9]*) return 0 ;; esac
  if [ "$major" -lt 110 ]; then
    echo "${YEL}${B}⚠ system WebView is very old ($wv)${X}${YEL} — too old for a current Home Assistant frontend; the dashboard may render blank or broken.${X}"
    echo "   ${CYN}update it: $WEBVIEW_DOC${X}"
    echo "   ${D}(Already swapped in Cromite SystemWebView? It stamps the OEM version, so it still reports $wv here — ignore this.)${X}"
  fi
}

# Fetch the newest signed release APK from GitHub (gh if present, else the API via curl). Sets APK + TOINSTALL_VER.
download_latest() {
  local dir tag url json api record asset expected_url
  dir="$(mktemp -d)"
  # PRERELEASE=1 → newest release of ANY kind (incl. rc); else the newest STABLE. GitHub's
  # /releases/latest EXCLUDES prereleases, so the prerelease path lists all releases and takes the first.
  if command -v gh >/dev/null 2>&1; then
    if [ "$PRERELEASE" = 1 ]; then
      tag="$(gh release list --repo "$REPO" --exclude-drafts --limit 100 --json tagName,isPrerelease --jq 'map(select(.isPrerelease))[0].tagName // empty' 2>/dev/null || true)"
    else
      tag="$(gh release view --repo "$REPO" --json tagName -q .tagName 2>/dev/null || true)"
    fi
    if [ -n "$tag" ] && valid_release_tag "$tag"; then
      asset="$(release_apk_name "$tag")"
      gh release download "$tag" --repo "$REPO" --pattern "$asset" --dir "$dir" >/dev/null 2>&1 || true
    fi
  fi
  [ -z "$tag" ] || valid_release_tag "$tag" || tag=""
  asset="${tag:+$(release_apk_name "$tag")}"
  if [ -z "$asset" ] || [ ! -s "$dir/$asset" ]; then
    if [ "$PRERELEASE" = 1 ]; then
      api="https://api.github.com/repos/$REPO/releases?per_page=100"
    else
      api="https://api.github.com/repos/$REPO/releases/latest"
    fi
    json="$(curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 30 "$api" 2>/dev/null || true)"
    if [ "$PRERELEASE" = 1 ]; then
      record="$(printf '%s' "$json" | tr -d '\r\n' | \
        sed 's#{[[:space:]]*"url":[[:space:]]*"https://api.github.com/repos/maxlyth/ha-paneld/releases/\([0-9][0-9]*\)"#\
&#g' | \
        awk '/"draft":[[:space:]]*false/ && /"prerelease":[[:space:]]*true/ { print; exit }')"
    else
      record="$json"
    fi
    tag="$(printf '%s' "$record" | grep -o '"tag_name": *"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
    url="$(printf '%s' "$record" | grep -o '"browser_download_url": *"[^"]*\.apk"' | head -1 | cut -d'"' -f4 || true)"
    if [ -n "$tag" ] && valid_release_tag "$tag"; then
      asset="$(release_apk_name "$tag")"
      expected_url="$(release_apk_url "$tag")"
      [ "$url" = "$expected_url" ] && curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 300 "$url" -o "$dir/$asset" || true
    fi
  fi
  [ -n "${asset:-}" ] && [ -s "$dir/$asset" ] && APK="$dir/$asset" || APK=""
  [ -n "$APK" ] || { echo "${RED}could not fetch the latest release APK from the expected GitHub release path${X}" >&2; exit 1; }
  APK_RELEASE_TAG="$tag"
  TOINSTALL_VER="${tag#v}"
  step "⬇️  downloaded" "${D}$(basename "$APK")${X} ${B}${tag:-latest}${X}"
}

# Pick the APK: explicit --apk wins; else the local build (unless --latest); else download the latest release.
resolve_apk() {
  if [ -n "$APK" ]; then :
  elif [ "$LATEST" = 1 ]; then download_latest
  elif [ -f "$LOCAL_APK" ]; then APK="$LOCAL_APK"; step "📂 using local build" "${D}$APK${X}"
  else download_latest; fi
  [ -f "$APK" ] || { echo "${RED}APK not found: $APK${X}" >&2; exit 1; }
  if [ -z "$TOINSTALL_VER" ]; then  # local/--apk: read the version via aapt if available (else the guard is skipped)
    for t in aapt aapt2; do
      if command -v "$t" >/dev/null 2>&1; then
        TOINSTALL_VER="$("$t" dump badging "$APK" 2>/dev/null | grep -o "versionName='[^']*'" | head -1 | cut -d"'" -f2 || true)"
        break
      fi
    done
  fi
  return 0  # never let the (possibly non-zero) probe above abort the script under set -e
}

find_android_build_tool() {
  local name="$1" found="" root candidate
  found="$(command -v "$name" 2>/dev/null || true)"
  if [ -n "$found" ]; then printf '%s\n' "$found"; return 0; fi
  for root in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
    [ -n "$root" ] && [ -d "$root/build-tools" ] || continue
    for candidate in "$root"/build-tools/*/"$name"; do [ -x "$candidate" ] && found="$candidate"; done
  done
  [ -n "$found" ] && printf '%s\n' "$found"
}

verify_release_checksum() {
  local openssl_tool proof_dir checksum_file signature_file public_key expected_name record expected_hash actual_hash
  openssl_tool="$(command -v openssl 2>/dev/null || true)"
  if [ -z "$openssl_tool" ] || ! "$openssl_tool" version >/dev/null 2>&1; then
    fail "OpenSSL is required to authenticate this official ha-paneld release" \
      "No panel changes were made. Install OpenSSL, then re-run the same command." \
      "Windows: use a current Git Bash or WSL terminal. macOS: run xcode-select --install, or brew install openssl." \
      "Debian/Ubuntu: sudo apt install openssl · Fedora: sudo dnf install openssl · Arch: sudo pacman -S openssl"
  fi

  proof_dir="$(mktemp -d)" || fail "could not create a temporary directory for release verification" \
    "Free some disk space, then re-run; no panel changes were made."
  checksum_file="$proof_dir/release.sha256"
  signature_file="$proof_dir/release.sha256.sig"
  public_key="$proof_dir/release-public-key.pem"
  if ! write_release_public_key "$public_key"; then
    rm -rf "$proof_dir"
    fail "could not prepare the trusted ha-paneld release key" "No panel changes were made. Check free disk space, then re-run."
  fi
  if ! RELEASE_APK_SOURCE="$APK" curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 60 \
      "$(release_checksum_url "$APK_RELEASE_TAG")" -o "$checksum_file"; then
    rm -rf "$proof_dir"
    fail "could not download the signed checksum for $APK_RELEASE_TAG" \
      "The release may be incomplete or GitHub may be unavailable. No panel changes were made; check internet access and retry."
  fi
  if ! curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 60 \
      "$(release_signature_url "$APK_RELEASE_TAG")" -o "$signature_file"; then
    rm -rf "$proof_dir"
    fail "could not download the checksum signature for $APK_RELEASE_TAG" \
      "The release may be incomplete or GitHub may be unavailable. No panel changes were made; check internet access and retry."
  fi
  if ! "$openssl_tool" dgst -sha256 -verify "$public_key" -signature "$signature_file" "$checksum_file" >/dev/null 2>&1; then
    rm -rf "$proof_dir"
    fail "the $APK_RELEASE_TAG checksum signature is invalid" \
      "The release assets could be incomplete, damaged, or substituted. Nothing was installed, started, or privileged." \
      "Delete the downloaded files and retry from the official GitHub release."
  fi

  expected_name="$(release_apk_name "$APK_RELEASE_TAG")"
  record="$(cat "$checksum_file")"
  expected_hash="${record%% *}"
  if ! printf '%s\n' "$expected_hash" | grep -Eq '^[0-9A-Fa-f]{64}$' || [ "$record" != "$expected_hash  $expected_name" ]; then
    rm -rf "$proof_dir"
    fail "the signed checksum record for $APK_RELEASE_TAG is malformed" \
      "Expected one SHA-256 record for $expected_name. Nothing was installed, started, or privileged."
  fi
  if ! actual_hash="$("$openssl_tool" dgst -sha256 -r "$APK" 2>/dev/null | awk '{print tolower($1)}')" || \
      ! printf '%s\n' "$actual_hash" | grep -Eq '^[0-9a-f]{64}$'; then
    rm -rf "$proof_dir"
    fail "OpenSSL could not calculate the downloaded APK checksum" \
      "No panel changes were made. Check that the APK is readable and re-run the installer."
  fi
  expected_hash="$(printf '%s' "$expected_hash" | tr '[:upper:]' '[:lower:]')"
  if [ "$actual_hash" != "$expected_hash" ]; then
    rm -rf "$proof_dir"
    fail "the $APK_RELEASE_TAG APK checksum does not match its signed record" \
      "The APK could be incomplete, damaged, or substituted. Nothing was installed, started, or privileged." \
      "Delete the downloaded files and retry from the official GitHub release."
  fi
  rm -rf "$proof_dir"
  step "🔐 authenticated" "${D}$APK_RELEASE_TAG · signed SHA-256 ${actual_hash:0:12}…${X}"
}

verify_release_apk() {
  [ -n "$APK_RELEASE_TAG" ] || return 0
  local expected_name signer_tool signer_output signer package_tool package_name
  expected_name="$(release_apk_name "$APK_RELEASE_TAG")"
  [ "$(basename "$APK")" = "$expected_name" ] || fail "release APK filename does not match its tag" \
    "Expected $expected_name for $APK_RELEASE_TAG; nothing was installed."
  verify_release_checksum
  signer_tool="$(find_android_build_tool apksigner || true)"
  if [ -z "$signer_tool" ]; then
    warn "Android Build-Tools were not found, so the optional APK structure inspection was skipped. The APK was still authenticated by the signed checksum above, and Android validates its APK signature during installation."
  else
    signer_output="$("$signer_tool" verify --print-certs "$APK" 2>/dev/null)" || fail "release APK signature verification failed" \
      "The downloaded APK was not installed. Check GitHub access and retry."
    signer="$(printf '%s\n' "$signer_output" | sed -nE 's/^Signer #[0-9]+ certificate SHA-256 digest: *//p' | head -1 | tr -d ':\r' | tr '[:upper:]' '[:lower:]')"
    [ "$signer" = "$RELEASE_CERT_SHA256" ] || fail "release APK signer mismatch" \
      "Expected $RELEASE_CERT_SHA256" "Got      ${signer:-unavailable}" "Nothing was installed, started, or privileged."
  fi
  package_tool="$(find_android_build_tool aapt || true)"
  [ -n "$package_tool" ] || package_tool="$(find_android_build_tool aapt2 || true)"
  if [ -n "$package_tool" ]; then
    package_name="$("$package_tool" dump badging "$APK" 2>/dev/null | sed -nE "s/^package: name='([^']+)'.*/\1/p" | head -1 || true)"
    [ "$package_name" = "$PKG" ] || fail "release APK package mismatch" \
      "Expected $PKG" "Got      ${package_name:-unavailable}" "Nothing was installed, started, or privileged."
  fi
  step "🛡️  verified" "${D}$APK_RELEASE_TAG · package ${package_name:-confirmed after install} · signer ${RELEASE_CERT_SHA256:0:12}…${X}"
}

# Give a useful version transition before install. Android's package manager remains the portable,
# authoritative downgrade guard; avoiding GNU `sort -V` keeps the supported macOS path working.
version_guard() {
  [ "$FORCE" = 1 ] && return 0
  [ -n "$TOINSTALL_VER" ] || return 0
  local installed
  installed="$(adb -s "$TARGET" shell dumpsys package "$PKG" 2>/dev/null | grep -m1 versionName | sed 's/.*versionName=//' | tr -d '\r ' || true)"
  [ -n "$installed" ] || return 0
  if [ "$installed" = "$TOINSTALL_VER" ]; then
    echo "${YEL}ℹ panel already on $installed — reinstalling.${X}"
  else
    echo "${GRN}↻ changing $installed → $TOINSTALL_VER${X}"
    echo "   ${D}Android will safely reject an unsupported downgrade; no app data is removed.${X}"
  fi
  return 0
}

export_config() {
  local destination="$1" temporary="${1}.partial.$$"
  step "📦 exporting config" "${D}→ $destination (includes secrets — protect it)${X}"
  if ! curl -fsS --max-time 30 "$URL/api/v1/config/export?include_secrets=1" -o "$temporary"; then
    rm -f "$temporary"
    fail "config backup failed; the panel was not changed" \
      "Confirm $URL opens from this computer, then run the same --export command again."
  fi
  if [ ! -s "$temporary" ]; then
    rm -f "$temporary"
    fail "config backup was empty; the panel was not changed" \
      "Do not uninstall or replace the app until a non-empty backup has been verified."
  fi
  chmod 600 "$temporary" 2>/dev/null || true
  mv "$temporary" "$destination"
  echo "   ${GRN}✓${X} $(wc -c < "$destination") bytes saved with owner-only permissions"
}

export_is_only_operation() {
  [ -n "$EXPORT_FILE" ] && [ -z "$APK$PANEL_ID$MQTT$MQTT_USER$MQTT_PASS$LOG_HOST$LOG_PORT$LOG_PROTO$LOG_ENABLE" ] &&
    [ -z "$HA_URL$HA_TOKEN$HA_USER$HA_PASS$RESTORE_FILE" ] && [ "$LATEST" = 0 ] && [ "$PERSIST_ADB" = 0 ] &&
    [ "$STRIP_VENDOR" = 0 ] && [ "$BUILTIN" = 0 ] && [ "$SHIZUKU" = 0 ]
}

echo "${MAG}${B}🛠  ha-paneld provisioning${X} ${D}→ $TARGET${X}"

# Preflight the panel BEFORE fetching an APK — an unreachable/unauthorized panel should fail in
# seconds with recovery steps, not after a release download.
adb_preflight

# A backup must happen before any install or configuration mutation. With no other requested
# operation, --export is deliberately read-only and exits here.
if [ -n "$EXPORT_FILE" ]; then export_config "$EXPORT_FILE"; fi
if [ "$VERIFY_ONLY" = 1 ]; then verify; exit $?; fi
if export_is_only_operation; then
  echo "${GRN}${B}✅ backup complete${X} — no app, setting, or panel state was changed."
  exit 0
fi

if [ "$NO_TAME" != 1 ] && [ "$PROVISION_FAILED" = 0 ]; then
  echo "${YEL}ℹ after installation, ha-paneld will reversibly disable this profile's known vendor overlays and factory-test apps.${X}"
  echo "   ${D}Use --no-tame to leave every vendor application unchanged.${X}"
fi

resolve_apk

verify_release_apk

version_guard

# Try with -g (grant-all) first — some vendor builds reject the flag, so retry plain. Capture the
# output: adb's raw INSTALL_FAILED_* codes are cryptic, so classify the common ones into recovery steps.
install_apk() {
  local out
  out="$(adb -s "$TARGET" install -r -g "$APK" 2>&1)" && return 0
  out="$(adb -s "$TARGET" install -r "$APK" 2>&1)" && return 0
  echo "$out" | sed 's/^/   /' >&2
  case "$out" in
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*|*"signatures do not match"*)
      fail "install failed: signature mismatch — the ha-paneld already on the panel was signed with a different key (e.g. a local debug build vs a GitHub release)" \
        "1. If the panel is reachable, back up its config first: re-run with --export FILE (or on the panel's :8888 page → Install → Backup)." \
        "2. adb -s $TARGET uninstall $PKG    (removes the app AND its on-panel config)" \
        "3. Re-run this command; restore the config with --restore FILE." ;;
    *INSTALL_FAILED_VERSION_DOWNGRADE*)
      fail "install failed: the panel already runs a NEWER version than this APK" \
        "Install the newest release instead (--latest), or to force this older build: adb -s $TARGET uninstall $PKG (loses on-panel config), then re-run." ;;
    *INSTALL_FAILED_INSUFFICIENT_STORAGE*)
      fail "install failed: the panel is out of storage" \
        "Free space on the panel (Settings → Storage; or clear app caches: adb -s $TARGET shell pm trim-caches 999G), then re-run." ;;
    *)
      fail "adb install failed (output above)" \
        "Fix the cause shown above, then re-run the SAME command — provisioning is idempotent." ;;
  esac
}

# Optional non-root privilege bootstrap. Keep this dependency curated: never chase "latest" here.
# The exact official APK blob is SHA-pinned, and ha-paneld independently checks the installed signing
# certificate before binding. Shizuku still presents its own permission UI on the panel; provisioning
# cannot silently approve ha-paneld. The adb-started service normally needs rearming after reboot on
# older Android versions, while Shizuku 13.6 can auto-start on supported Android 13+ trusted WLANs.
if [ "$SHIZUKU" = 1 ]; then
  SHIZUKU_PKG="moe.shizuku.privileged.api"
  SHIZUKU_VERSION_CODE=1086
  SHIZUKU_URL="https://github.com/RikkaApps/Shizuku/releases/download/v13.6.0/shizuku-v13.6.0.r1086.2650830c-release.apk"
  SHIZUKU_SHA256="6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f"
  SHIZUKU_CERT_SHA256="268b5590e868fb08bae7e0ac413564cd1ff88f5ccff74af9dbd0dc918e30db30"
  SHIZUKU_DIR="$(mktemp -d)"
  SHIZUKU_APK="$SHIZUKU_DIR/shizuku.apk"
  trap 'rm -rf "${SHIZUKU_DIR:-}"' EXIT
  SHIZUKU_CURRENT_CODE="$(adb -s "$TARGET" shell dumpsys package "$SHIZUKU_PKG" 2>/dev/null \
    | sed -nE 's/.*versionCode=([0-9]+).*/\1/p' | head -1 | tr -d '\r' || true)"
  SHIZUKU_CURRENT_PATH="$(adb -s "$TARGET" shell pm path "$SHIZUKU_PKG" 2>/dev/null \
    | sed -n 's/^package://p' | head -1 | tr -d '\r' || true)"
  SHIZUKU_CURRENT_TRUSTED=0
  if [ -n "$SHIZUKU_CURRENT_PATH" ]; then
    SHIZUKU_CURRENT_APK="$SHIZUKU_DIR/installed-shizuku.apk"
    if adb -s "$TARGET" pull "$SHIZUKU_CURRENT_PATH" "$SHIZUKU_CURRENT_APK" >/dev/null 2>&1; then
      if command -v sha256sum >/dev/null 2>&1; then
        SHIZUKU_CURRENT_SHA="$(sha256sum "$SHIZUKU_CURRENT_APK" | awk '{print $1}')"
      elif command -v shasum >/dev/null 2>&1; then
        SHIZUKU_CURRENT_SHA="$(shasum -a 256 "$SHIZUKU_CURRENT_APK" | awk '{print $1}')"
      else
        SHIZUKU_CURRENT_SHA=""
      fi
      [ "$SHIZUKU_CURRENT_SHA" = "$SHIZUKU_SHA256" ] && SHIZUKU_CURRENT_TRUSTED=1
      if [ "$SHIZUKU_CURRENT_TRUSTED" = 0 ]; then
        APKSIGNER="$(find_android_build_tool apksigner || true)"
        if [ -n "$APKSIGNER" ]; then
          SHIZUKU_CURRENT_CERT="$("$APKSIGNER" verify --print-certs "$SHIZUKU_CURRENT_APK" 2>/dev/null \
            | sed -nE 's/^Signer #[0-9]+ certificate SHA-256 digest: *//p' | head -1 \
            | tr -d ':\r' | tr '[:upper:]' '[:lower:]' || true)"
          [ "$SHIZUKU_CURRENT_CERT" = "$SHIZUKU_CERT_SHA256" ] && SHIZUKU_CURRENT_TRUSTED=1
        fi
      fi
    fi
  fi
  case "$SHIZUKU_CURRENT_CODE" in ''|*[!0-9]*) SHIZUKU_CURRENT_CODE=0 ;; esac
  if [ "$SHIZUKU_CURRENT_CODE" -ge "$SHIZUKU_VERSION_CODE" ]; then
    [ "$SHIZUKU_CURRENT_TRUSTED" = 1 ] || fail "the installed Shizuku manager cannot be trusted" \
      "Its version is the same as or newer than ha-paneld's curated build, but its signer could not be verified." \
      "Install Android SDK Build-Tools (apksigner), or remove the manager and re-run this command."
    step "🪄 Shizuku" "${D}keeping trusted installed manager (versionCode $SHIZUKU_CURRENT_CODE)${X}"
  else
    step "🪄 Shizuku" "${D}installing curated v13.6.0 manager${X}"
    curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 300 \
      "$SHIZUKU_URL" -o "$SHIZUKU_APK" || fail "Shizuku download failed" \
      "Check internet access to github.com, then re-run. ha-paneld was not replaced or stopped."
    if command -v sha256sum >/dev/null 2>&1; then
      SHIZUKU_GOT="$(sha256sum "$SHIZUKU_APK" | awk '{print $1}')"
    elif command -v shasum >/dev/null 2>&1; then
      SHIZUKU_GOT="$(shasum -a 256 "$SHIZUKU_APK" | awk '{print $1}')"
    else
      fail "cannot verify the Shizuku download" "Install sha256sum (or shasum) and re-run."
    fi
    [ "$SHIZUKU_GOT" = "$SHIZUKU_SHA256" ] || fail "Shizuku download checksum mismatch" \
      "Expected $SHIZUKU_SHA256" "Got      $SHIZUKU_GOT" "Nothing was installed."
    adb -s "$TARGET" install -r "$SHIZUKU_APK" >/dev/null || fail "Shizuku installation failed" \
      "Fix the package-manager error above, then re-run this command."
  fi
  # First launch materialises Shizuku's official external start script.
  adb -s "$TARGET" shell monkey -p "$SHIZUKU_PKG" 1 >/dev/null 2>&1 || true
  SHIZUKU_START_WAIT=0
  while [ "$SHIZUKU_START_WAIT" -lt 10 ]; do
    if adb -s "$TARGET" shell test -f "/storage/emulated/0/Android/data/$SHIZUKU_PKG/start.sh"; then break; fi
    SHIZUKU_START_WAIT=$((SHIZUKU_START_WAIT + 1))
    sleep 1
  done
  SHIZUKU_START_SCRIPT="/storage/emulated/0/Android/data/$SHIZUKU_PKG/start.sh"
  if adb -s "$TARGET" shell test -f "$SHIZUKU_START_SCRIPT" >/dev/null 2>&1 && \
      adb -s "$TARGET" shell sh "$SHIZUKU_START_SCRIPT" >/dev/null 2>&1; then
    echo "   ${GRN}✓${X} Shizuku service started"
  else
    warn "Shizuku installed, but its service did not start. Open Shizuku on the panel and follow its ADB start instructions."
    PROVISION_FAILED=1
  fi
  # Enables Shizuku's supported Android 13+ trusted-WLAN auto-start option; the user still chooses
  # whether to turn that option on inside Shizuku. Harmless/ignored on versions that refuse the grant.
  adb -s "$TARGET" shell pm grant "$SHIZUKU_PKG" android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1 || true
  echo "   ${YEL}→${X} On the panel: open ha-paneld Configure, use the toolbar overflow menu → Enhanced access → Enable, then approve the Shizuku prompt."
fi

# Bootstrap Shizuku before replacing ha-paneld. A fatal manager download/signature/install failure
# therefore cannot leave a previously working agent in Android's post-install stopped state. A service
# start failure is non-fatal to the core install: finish and launch ha-paneld, but return nonzero so an
# individual or fleet run cannot report enhanced-access setup as successful.
step "📦 installing" "${D}$APK${X}"
install_apk

step "🔑 permissions" "${D}notifications · WRITE_SETTINGS (brightness/screen) · SYSTEM_ALERT_WINDOW (navbar) · a11y (buttons)${X}"
# Grants degrade gracefully: some vendor builds refuse appops/settings writes from the adb shell.
# A failed grant must not abort the run — the app works with reduced capability, verify() reports the
# true end-state, and each warning names the manual Settings path to finish the job by hand.
adb -s "$TARGET" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
adb -s "$TARGET" shell appops set "$PKG" WRITE_SETTINGS allow >/dev/null 2>&1 \
  || warn "could not grant WRITE_SETTINGS via adb — grant manually: Settings → Apps → ha-paneld → 'Modify system settings'"
# Soft-navbar overlay. SuperSU panels self-grant this at runtime via in-app su, but sandbox-walled
# panels (Tuya TPA10, SELinux-blocked from exec'ing su) can't — so grant it here for every panel.
adb -s "$TARGET" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 \
  || warn "could not grant SYSTEM_ALERT_WINDOW via adb — grant manually: Settings → Apps → ha-paneld → 'Display over other apps'"
# Enable the accessibility service, preserving whatever else is enabled. If the READ fails we must
# NOT write: a blind write would replace the whole list and silently disable any other a11y service.
if EXISTING="$(adb -s "$TARGET" shell settings get secure enabled_accessibility_services 2>/dev/null)"; then
  EXISTING="${EXISTING//$'\r'/}"
  if [ "$EXISTING" = "null" ] || [ -z "$EXISTING" ]; then
    adb -s "$TARGET" shell settings put secure enabled_accessibility_services "$A11Y" >/dev/null 2>&1 \
      || warn "could not enable the accessibility service — enable manually: Settings → Accessibility → ha-paneld"
  else
    case "$EXISTING" in
      *"$A11Y"*) : ;;
      *) adb -s "$TARGET" shell settings put secure enabled_accessibility_services "$EXISTING:$A11Y" >/dev/null 2>&1 \
           || warn "could not enable the accessibility service — enable manually: Settings → Accessibility → ha-paneld" ;;
    esac
  fi
  adb -s "$TARGET" shell settings put secure accessibility_enabled 1 >/dev/null 2>&1 || true
else
  warn "could not read the accessibility settings — skipping a11y enable (hardware buttons). Enable manually: Settings → Accessibility → ha-paneld."
fi

# Opt-in: persist network adb across reboots. Sets the prop only (no adbd restart — that would drop
# this very connection mid-provision); it's already on tcp, so the prop just makes it survive a reboot.
if [ "$PERSIST_ADB" = 1 ]; then
  step "🔌 network adb" "${D}persisting tcp 5555 across reboot${X}"
  run_root 'setprop persist.adb.tcp.port 5555' >/dev/null 2>&1 || true
  # Trust the read-back, not the command: su forms vary per vendor and a failed setprop is silent.
  PERSISTED="$(adb -s "$TARGET" shell getprop persist.adb.tcp.port 2>/dev/null | tr -d '\r' || true)"
  if [ "$PERSISTED" = "5555" ]; then
    echo "   ${GRN}✓${X} persist.adb.tcp.port=5555"
  else
    warn "could not persist network adb (needs root — no working su/adbd-root path). After a reboot, re-enable it on the panel (Developer options) or via USB: adb tcpip 5555"
  fi
fi

# MUST launch after install: `adb install -r` leaves the app in Android's "stopped" state, which does
# NOT auto-start — not even via START_STICKY — until something launches it (or the device reboots). A
# fleet update that installs without this step leaves panels installed-but-dead (their entities go
# `unavailable` in HA). launch_and_wait polls the panel's web server (host-side curl, so it works even
# on panels that ship no `curl` themselves) and is retried once to cover a stopped-state / slow-boot race.
launch_and_wait() {
  adb -s "$TARGET" shell am start -n "$PKG/.MainActivity" >/dev/null 2>&1 || true
  local attempt=0
  while [ "$attempt" -lt 15 ]; do
    attempt=$((attempt + 1))
    curl -fsS --max-time 2 "$URL/health" >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}
step "▶️  starting" "the panel agent"
if ! launch_and_wait; then
  step "▶️  re-starting" "${D}agent didn't answer — retrying${X}"
  launch_and_wait || { warn "web server still not answering on $URL — provisioning is incomplete."
                       echo "   ${D}Open $URL in a browser. If it loads there, this computer cannot reach the panel's :8888 port (firewall/VLAN).${X}"
                       PROVISION_FAILED=1; }
fi

# Built-in-renderer login (optional): when --ha-user/--ha-pass are given, log in to HA *here on this
# machine* (the password never reaches the panel) to mint a refresh token, so the panel holds a
# revocable refresh token rather than a 10-year access token. Sets HA_TOKEN/HA_REFRESH/HA_EXPIRY.
# JSON string escaping for the hand-built login bodies: a quote or backslash in a username/password
# would otherwise produce malformed JSON and a baffling login failure.
json_escape() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }

if [ -n "$HA_USER" ] && [ -n "$HA_PASS" ]; then
  step "🔑  HA login" "${D}minting a refresh token for $HA_USER (password stays on this machine)${X}"
    # Every curl / grep-extract below is `|| true`-guarded: under set -euo pipefail a failed request or
    # a no-match grep pipeline would abort the whole provisioning run BEFORE the warn lines could
    # explain what happened — the warn-and-continue guards were dead code without this.
    cid="${HA_URL%/}/"
    ju="$(json_escape "$HA_USER")"; jp="$(json_escape "$HA_PASS")"; jc="$(json_escape "$cid")"
    fl="$(curl -fsS -X POST "${HA_URL%/}/auth/login_flow" -H 'Content-Type: application/json' \
          --data "{\"client_id\":\"$jc\",\"handler\":[\"homeassistant\",null],\"redirect_uri\":\"$jc\"}" 2>/dev/null || true)"
    flow_id="$(printf '%s' "$fl" | grep -o '"flow_id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
    if [ -z "$flow_id" ]; then warn "HA login flow failed (is $HA_URL reachable?)"; HA_LOGIN_FAILED=1; else
      res="$(curl -fsS -X POST "${HA_URL%/}/auth/login_flow/$flow_id" -H 'Content-Type: application/json' \
             --data "{\"client_id\":\"$jc\",\"username\":\"$ju\",\"password\":\"$jp\"}" 2>/dev/null || true)"
      code="$(printf '%s' "$res" | grep -o '"result":"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
      if [ -z "$code" ]; then warn "HA login rejected (bad credentials or MFA-enabled account)"; HA_LOGIN_FAILED=1; else
        tok="$(curl -fsS -X POST "${HA_URL%/}/auth/token" \
               --data-urlencode "grant_type=authorization_code" \
               --data-urlencode "code=$code" --data-urlencode "client_id=$cid" 2>/dev/null || true)"
        HA_TOKEN="$(printf '%s' "$tok" | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4 || true)"
        HA_REFRESH="$(printf '%s' "$tok" | grep -o '"refresh_token":"[^"]*"' | cut -d'"' -f4 || true)"
        exp="$(printf '%s' "$tok" | grep -o '"expires_in":[0-9]*' | grep -o '[0-9]*' || true)"
        [ -n "$exp" ] && HA_EXPIRY="$(( $(date +%s) + exp ))"
        if [ -n "$HA_TOKEN" ] && [ -n "$HA_REFRESH" ]; then
          echo "   ${GRN}✓${X} refresh token minted"
        else
          warn "Home Assistant returned no usable access/refresh token pair"
          HA_LOGIN_FAILED=1
        fi
      fi
    fi
  if [ "$HA_LOGIN_FAILED" != 0 ]; then
    HA_TOKEN=""; HA_REFRESH=""; HA_EXPIRY=""
    PROVISION_FAILED=1
    echo "   ${RED}The existing dashboard login and renderer selection were left unchanged.${X}"
  fi
fi

# A long-lived token supplied directly is otherwise just opaque text: accepting it and selecting the
# built-in renderer would produce a convincing false-success followed by an on-panel login failure.
# Validate every resulting access token (direct LLAT or freshly minted short-lived token) against HA
# before changing any saved renderer/login settings.
if [ "$HA_LOGIN_FAILED" = 0 ] && [ -n "$HA_TOKEN" ]; then
  step "🔐  checking HA access" "${D}validating the token before changing the panel${X}"
  if curl -fsS --max-time 10 -H "Authorization: Bearer $HA_TOKEN" \
      -H 'Accept: application/json' "${HA_URL%/}/api/" >/dev/null 2>&1; then
    echo "   ${GRN}✓${X} Home Assistant accepted the token"
  else
    warn "Home Assistant rejected the token (or ${HA_URL%/} could not be reached)"
    HA_LOGIN_FAILED=1
    HA_TOKEN=""; HA_REFRESH=""; HA_EXPIRY=""
    PROVISION_FAILED=1
    echo "   ${RED}The existing dashboard login and renderer selection were left unchanged.${X}"
    echo "   ${D}Create a new long-lived access token in Home Assistant → your profile, then re-run.${X}"
  fi
fi

ARGS=()
[ -n "$PANEL_ID" ]   && ARGS+=(--data-urlencode "panel_id=$PANEL_ID")
[ -n "$MQTT" ]       && ARGS+=(--data-urlencode "mqtt_broker=$MQTT")
[ -n "$MQTT_USER" ]  && ARGS+=(--data-urlencode "mqtt_user=$MQTT_USER")
[ -n "$MQTT_PASS" ]  && ARGS+=(--data-urlencode "mqtt_password=$MQTT_PASS")
if [ "$HA_LOGIN_FAILED" = 0 ]; then
  [ -n "$HA_URL" ]     && ARGS+=(--data-urlencode "ha_url=$HA_URL")
  [ -n "$HA_TOKEN" ]   && ARGS+=(--data-urlencode "ha_token=$HA_TOKEN")
  [ -n "$HA_REFRESH" ] && ARGS+=(--data-urlencode "ha_refresh_token=$HA_REFRESH")
  [ -n "$HA_EXPIRY" ]  && ARGS+=(--data-urlencode "ha_token_expiry=$HA_EXPIRY")
  [ "$BUILTIN" = 1 ]   && ARGS+=(--data-urlencode "dashboard_package=builtin")
fi
[ -n "$LOG_ENABLE" ] && ARGS+=(--data-urlencode "log_ship_enabled=$LOG_ENABLE")
[ -n "$LOG_HOST" ]   && ARGS+=(--data-urlencode "log_ship_host=$LOG_HOST")
[ -n "$LOG_PORT" ]   && ARGS+=(--data-urlencode "log_ship_port=$LOG_PORT")
[ -n "$LOG_PROTO" ]  && ARGS+=(--data-urlencode "log_ship_protocol=$LOG_PROTO")
if [ ${#ARGS[@]} -gt 0 ]; then
  step "⚙️  configuring" "${D}panel_id / MQTT / renderer / log shipping${X}"
  if CFG_ERR="$(curl -fsS -H 'Accept: application/json' -X POST "${ARGS[@]}" "$URL/api/v1/config" 2>&1 >/dev/null)"; then
    echo "   ${GRN}✓${X} applied"
  else
    warn "config not applied: ${CFG_ERR:-no response}"
    echo "   ${D}If the agent was still starting, re-running applies it. A 403 means the panel refused the source: its API only accepts LAN addresses — provision from a host on the panel's own network, not via VPN/routed subnet.${X}"
    PROVISION_FAILED=1
  fi
fi

# Config bundle restore (best-effort import: valid keys apply, invalid/unknown are reported + skipped —
# a bundle from different hardware or another ha-paneld version restores what it can). --restore applies
# everything incl. device-scoped keys (same-panel recovery / like-for-like replacement); --restore-fleet
# applies only PORTABLE non-secret keys (cross-panel deployment).
if [ -n "$RESTORE_FILE" ]; then
  [ -f "$RESTORE_FILE" ] || { echo "${RED}✗ bundle not found: $RESTORE_FILE${X}" >&2; exit 2; }
  MODE_Q=""; [ "$RESTORE_MODE" = "fleet" ] && MODE_Q="?mode=fleet"
  step "📦 restoring config" "${D}$RESTORE_FILE (${RESTORE_MODE})${X}"
  if resp="$(curl -fsS -H 'Content-Type: application/json' --data-binary @"$RESTORE_FILE" "$URL/api/v1/config/import$MODE_Q" 2>&1)"; then
    echo "   ${GRN}✓${X} $resp"
  else
    echo "   ${RED}✗ import failed:${X} $resp"
    PROVISION_FAILED=1
  fi
fi

# Recommended vendor-app taming — tame the panel profile's `defaultTame` set (eWeLink's over-the-dashboard
# overlay + factory burn-in/test tools) in one call. Guarded app-side: tame() hands the home role to
# ha-paneld's admin launcher and refuses to strand the panel, and it's reversible from the :8888 picker.
# Default ON during provisioning; --no-tame skips. Runs after any restore so it isn't clobbered. Best-effort
# — a profile with no recommendations, or a panel with no privileged path, is a harmless no-op.
if [ "$NO_TAME" != 1 ] && [ "$PROVISION_FAILED" = 0 ]; then
  step "🧹 taming" "${D}recommended vendor apps (eWeLink, factory test tools)${X}"
  curl -fsS --data-urlencode "action=recommended" "$URL/api/v1/tame" >/dev/null 2>&1 \
    && echo "   ${GRN}✓${X} recommended set applied (where present + safe)" \
    || echo "   ${D}(nothing to tame / no privileged path)${X}"
fi

echo
if verify; then
  [ "$PROVISION_FAILED" = 0 ] && echo "${GRN}${B}✅ provisioned and verified${X} — ${B}$URL/${X}"
else
  PROVISION_FAILED=1
fi
if [ "$PROVISION_FAILED" != 0 ]; then
  echo "${RED}${B}✗ provisioning incomplete${X} — correct the failed item above, then re-run the same command."
fi
echo "${D}   Root daemon: required on EVERY sandbox-walled panel (appCanSu=false — TPA10, SMT1019, …), not just LED panels. It's the privileged path for screen-off, density, governor, screenshot, perf and buttons even when ledMechanism=NONE. rk3576/PX30 panels can exec su directly and don't need it.${X}"
echo "${D}   Install it with:  ./helper/build.sh && ./helper/install-daemon.sh $TARGET${X}"

# Flag a too-old system WebView (informational; the dashboard won't render on ancient Chrome).
check_webview

# Tuya/TPA10 only: offer to disable the closed vendor app stack for a faster, minimal HA panel (guarded
# on root + persistent adb + a replacement launcher; prompts unless --strip-vendor was passed).
[ "$PROVISION_FAILED" = 0 ] && offer_strip_vendor

exit "$PROVISION_FAILED"
