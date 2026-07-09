#!/usr/bin/env bash
# ha-paneld provisioning — install + grant all permissions over adb, no device UI, and (optionally)
# set the panel id + MQTT broker in one shot. Requires adb access and su/adb-root on the panel.
# For non-root panels, use the in-app setup screen instead.
#
# Usage:
#   scripts/provision.sh <panel-ip:5555> [APK] \
#       [--id PANEL_ID] [--mqtt tcp://host:1883] [--mqtt-user U] [--mqtt-pass P] [--apk PATH] \
#       [--log-host HOST] [--log-port N] [--log-proto syslog|http]   # forward logcat to an aggregator
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

trap 'echo "${RED}${B}✗ provisioning incomplete${X} — re-run the SAME command to finish (it is idempotent)." >&2' ERR

# Fatal with SPECIFIC recovery steps. Disarms the generic re-run trap first: these are the failures
# re-running cannot fix, so the trap's blanket advice would mislead.
fail() {
  trap - ERR
  echo "${RED}${B}✗ $1${X}" >&2; shift
  local l; for l in "$@"; do echo "   $l" >&2; done
  exit 1
}

TARGET="${1:?usage: provision.sh <panel-ip:5555> [APK] [--id ID] [--mqtt tcp://host:1883] [--mqtt-user U] [--mqtt-pass P] [--ha-url URL] [--ha-token LLAT | --ha-user U --ha-pass P] [--builtin] [--log-host HOST] [--log-port N] [--log-proto syslog|http] [--log-off] [--export FILE] [--restore FILE] [--restore-fleet FILE] [--latest] [--force] [--persist-adb] [--strip-vendor] [--no-tame] [--verify]}"
shift
REPO="maxlyth/ha-paneld"
LOCAL_APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="io.github.maxlyth.hapaneld"
A11Y="$PKG/.input.PanelAccessibilityService"
APK=""; PANEL_ID=""; MQTT=""; MQTT_USER=""; MQTT_PASS=""; VERIFY_ONLY=0; LATEST=0; FORCE=0; PERSIST_ADB=0; STRIP_VENDOR=0; NO_TAME=0; TOINSTALL_VER=""
LOG_HOST=""; LOG_PORT=""; LOG_PROTO=""; LOG_ENABLE=""
EXPORT_FILE=""; RESTORE_FILE=""; RESTORE_MODE=""
HA_URL=""; HA_TOKEN=""; HA_USER=""; HA_PASS=""; HA_REFRESH=""; HA_EXPIRY=""; BUILTIN=0

if [ "${1:-}" ] && [ "${1#--}" = "${1:-}" ]; then APK="$1"; shift; fi
while [ "${1:-}" ]; do
  case "$1" in
    --apk) APK="$2"; shift 2 ;;
    --id) PANEL_ID="$2"; shift 2 ;;
    --mqtt) MQTT="$2"; shift 2 ;;
    --mqtt-user) MQTT_USER="$2"; shift 2 ;;
    --mqtt-pass) MQTT_PASS="$2"; shift 2 ;;
    --latest) LATEST=1; shift ;;     # ignore any local build, fetch the newest GitHub release
    --force) FORCE=1; shift ;;       # skip the same/older-version prompt
    --persist-adb) PERSIST_ADB=1; shift ;;  # keep network adb (tcp 5555) across reboots (opt-in; standing LAN port)
    --strip-vendor) STRIP_VENDOR=1; shift ;; # disable the Tuya vendor apps (TPA10) non-interactively (skips the prompt)
    --no-tame) NO_TAME=1; shift ;;   # skip the default "tame recommended vendor apps" step (eWeLink + factory test tools)
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
    *) echo "${RED}unknown arg: $1${X}" >&2; exit 2 ;;
  esac
done
IP="${TARGET%%:*}"
URL="http://$IP:8888"

verify() {
  step "🔎 verifying" "${D}$URL${X}"
  local diag cfg rc=0
  # A COLD /diag probes the panel's capabilities (su, daemon, sensors) and takes >12s on a PX30;
  # warm it's instant. 4s here made verify fail on every fresh run — keep the timeout generous.
  diag="$(curl -fsS --max-time 25 "$URL/api/v1/diag" 2>/dev/null || true)"
  cfg="$(curl -fsS --max-time 3 "$URL/api/v1/config" 2>/dev/null || true)"
  chk() { if printf '%s' "$2" | grep -q "$3"; then echo "   ${GRN}✓${X} $1"; else echo "   ${RED}✗ $1${X}"; rc=1; fi; }
  chk "HTTP server reachable"  "$diag" "ha-paneld diagnostics"
  chk "WRITE_SETTINGS granted" "$diag" "write_settings=true"
  chk "accessibility enabled"  "$diag" "a11y=true"
  # Root helper daemon — informational (only sandbox-walled panels need it; see the closing note).
  if printf '%s' "$diag" | grep -q "daemon=true"; then
    echo "   ${GRN}✓${X} root helper daemon: running"
  elif [ -n "$diag" ]; then
    echo "   ${YEL}ℹ${X} root helper daemon: ${YEL}not detected${X} ${D}(needed on sandbox-walled panels — helper/install-daemon.sh)${X}"
  fi
  # panel_id + MQTT (informational — install-only is valid). grep/cut so no python (Git Bash-friendly).
  local broker pid
  broker="$(printf '%s' "$cfg" | grep -o '"mqtt_broker":"[^"]*"' | head -1 | cut -d'"' -f4)"
  pid="$(printf '%s' "$cfg" | grep -o '"panel_id":"[^"]*"' | head -1 | cut -d'"' -f4)"
  if [ -n "$broker" ]; then echo "   ${GRN}✓${X} MQTT broker: ${B}$broker${X}"
  else echo "   ${YEL}ℹ${X} MQTT broker: ${YEL}not set${X} ${D}(set it on the page to enable HA discovery)${X}"; fi
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
  local state="" i
  for i in $(seq 1 12); do
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
  wv="$(adb -s "$TARGET" shell dumpsys webviewupdate 2>/dev/null | grep -m1 'Current WebView package' | grep -oE '[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+' | head -1)"
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
  local dir tag url json
  dir="$(mktemp -d)"
  if command -v gh >/dev/null 2>&1; then
    tag="$(gh release view --repo "$REPO" --json tagName -q .tagName 2>/dev/null || true)"
    gh release download --repo "$REPO" --pattern '*.apk' --dir "$dir" >/dev/null 2>&1 || true
  fi
  if ! ls "$dir"/*.apk >/dev/null 2>&1; then
    json="$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" 2>/dev/null || true)"
    tag="$(printf '%s' "$json" | grep -o '"tag_name": *"[^"]*"' | head -1 | cut -d'"' -f4)"
    url="$(printf '%s' "$json" | grep -o '"browser_download_url": *"[^"]*\.apk"' | head -1 | cut -d'"' -f4)"
    [ -n "$url" ] && curl -fsSL "$url" -o "$dir/ha-paneld-latest.apk"
  fi
  APK="$(ls "$dir"/*.apk 2>/dev/null | head -1 || true)"
  [ -n "$APK" ] || { echo "${RED}could not fetch the latest release APK (need gh, or internet for the GitHub API)${X}" >&2; exit 1; }
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

# Warn (and prompt on a TTY) before reinstalling the same or an older version; --force skips it.
version_guard() {
  [ "$FORCE" = 1 ] && return 0
  [ -n "$TOINSTALL_VER" ] || return 0
  local installed newest
  installed="$(adb -s "$TARGET" shell dumpsys package "$PKG" 2>/dev/null | grep -m1 versionName | sed 's/.*versionName=//' | tr -d '\r ' || true)"
  [ -n "$installed" ] || return 0
  newest="$(printf '%s\n%s\n' "$installed" "$TOINSTALL_VER" | sort -V | tail -1)"
  if [ "$installed" = "$TOINSTALL_VER" ]; then
    echo "${YEL}ℹ panel already on $installed — reinstalling.${X}"
  elif [ "$newest" = "$installed" ]; then
    echo "${YEL}⚠ panel has $installed; about to install OLDER $TOINSTALL_VER (downgrade).${X}"
    if [ -t 0 ]; then printf "   continue? [y/N] "; read -r a; case "$a" in [yY]*) ;; *) echo "aborted."; exit 0 ;; esac; fi
  else
    echo "${GRN}↑ updating $installed → $TOINSTALL_VER${X}"
  fi
  return 0
}

echo "${MAG}${B}🛠  ha-paneld provisioning${X} ${D}→ $TARGET${X}"

if [ "$VERIFY_ONLY" = 1 ]; then
  verify; exit $?
fi

# Preflight the panel BEFORE fetching an APK — an unreachable/unauthorized panel should fail in
# seconds with recovery steps, not after a release download.
adb_preflight

resolve_apk

version_guard

step "📦 installing" "${D}$APK${X}"
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
  for _ in $(seq 1 15); do curl -fsS --max-time 2 "$URL/health" >/dev/null 2>&1 && return 0; sleep 1; done
  return 1
}
step "▶️  starting" "the panel agent"
if ! launch_and_wait; then
  step "▶️  re-starting" "${D}agent didn't answer — retrying${X}"
  launch_and_wait || { warn "web server still not answering on $URL — the app may be running anyway: open $URL in a browser."
                       echo "   ${D}If the page loads there, only THIS machine can't reach the panel on :8888 (firewall/VLAN) — the remaining config steps need that port.${X}"; }
fi

# Built-in-renderer login (optional): when --ha-user/--ha-pass are given, log in to HA *here on this
# machine* (the password never reaches the panel) to mint a refresh token, so the panel holds a
# revocable refresh token rather than a 10-year access token. Sets HA_TOKEN/HA_REFRESH/HA_EXPIRY.
# JSON string escaping for the hand-built login bodies: a quote or backslash in a username/password
# would otherwise produce malformed JSON and a baffling login failure.
json_escape() { printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }

if [ -n "$HA_USER" ] && [ -n "$HA_PASS" ]; then
  if [ -z "$HA_URL" ]; then warn "--ha-user/--ha-pass need --ha-url; skipping login"; else
    step "🔑  HA login" "${D}minting a refresh token for $HA_USER (password stays on this machine)${X}"
    # Every curl / grep-extract below is `|| true`-guarded: under set -euo pipefail a failed request or
    # a no-match grep pipeline would abort the whole provisioning run BEFORE the warn lines could
    # explain what happened — the warn-and-continue guards were dead code without this.
    cid="${HA_URL%/}/"
    ju="$(json_escape "$HA_USER")"; jp="$(json_escape "$HA_PASS")"; jc="$(json_escape "$cid")"
    fl="$(curl -fsS -X POST "${HA_URL%/}/auth/login_flow" -H 'Content-Type: application/json' \
          --data "{\"client_id\":\"$jc\",\"handler\":[\"homeassistant\",null],\"redirect_uri\":\"$jc\"}" 2>/dev/null || true)"
    flow_id="$(printf '%s' "$fl" | grep -o '"flow_id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
    if [ -z "$flow_id" ]; then warn "HA login_flow failed (is $HA_URL reachable?)"; else
      res="$(curl -fsS -X POST "${HA_URL%/}/auth/login_flow/$flow_id" -H 'Content-Type: application/json' \
             --data "{\"client_id\":\"$jc\",\"username\":\"$ju\",\"password\":\"$jp\"}" 2>/dev/null || true)"
      code="$(printf '%s' "$res" | grep -o '"result":"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
      if [ -z "$code" ]; then warn "HA login rejected (bad credentials or MFA-enabled account)"; else
        tok="$(curl -fsS -X POST "${HA_URL%/}/auth/token" \
               --data-urlencode "grant_type=authorization_code" \
               --data-urlencode "code=$code" --data-urlencode "client_id=$cid" 2>/dev/null || true)"
        HA_TOKEN="$(printf '%s' "$tok" | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4 || true)"
        HA_REFRESH="$(printf '%s' "$tok" | grep -o '"refresh_token":"[^"]*"' | cut -d'"' -f4 || true)"
        exp="$(printf '%s' "$tok" | grep -o '"expires_in":[0-9]*' | grep -o '[0-9]*' || true)"
        [ -n "$exp" ] && HA_EXPIRY="$(( $(date +%s) + exp ))"
        if [ -n "$HA_REFRESH" ]; then echo "   ${GRN}✓${X} refresh token minted"; else warn "no refresh token in HA response"; fi
      fi
    fi
  fi
fi

ARGS=()
[ -n "$PANEL_ID" ]   && ARGS+=(--data-urlencode "panel_id=$PANEL_ID")
[ -n "$MQTT" ]       && ARGS+=(--data-urlencode "mqtt_broker=$MQTT")
[ -n "$MQTT_USER" ]  && ARGS+=(--data-urlencode "mqtt_user=$MQTT_USER")
[ -n "$MQTT_PASS" ]  && ARGS+=(--data-urlencode "mqtt_password=$MQTT_PASS")
[ -n "$HA_URL" ]     && ARGS+=(--data-urlencode "ha_url=$HA_URL")
[ -n "$HA_TOKEN" ]   && ARGS+=(--data-urlencode "ha_token=$HA_TOKEN")
[ -n "$HA_REFRESH" ] && ARGS+=(--data-urlencode "ha_refresh_token=$HA_REFRESH")
[ -n "$HA_EXPIRY" ]  && ARGS+=(--data-urlencode "ha_token_expiry=$HA_EXPIRY")
[ "$BUILTIN" = 1 ]   && ARGS+=(--data-urlencode "dashboard_package=builtin")
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
  resp="$(curl -fsS -H 'Content-Type: application/json' --data-binary @"$RESTORE_FILE" "$URL/api/v1/config/import$MODE_Q" 2>&1)"     && echo "   ${GRN}✓${X} $resp"     || echo "   ${RED}✗ import failed:${X} $resp"
fi

# Recommended vendor-app taming — tame the panel profile's `defaultTame` set (eWeLink's over-the-dashboard
# overlay + factory burn-in/test tools) in one call. Guarded app-side: tame() hands the home role to
# ha-paneld's admin launcher and refuses to strand the panel, and it's reversible from the :8888 picker.
# Default ON during provisioning; --no-tame skips. Runs after any restore so it isn't clobbered. Best-effort
# — a profile with no recommendations, or a panel with no privileged path, is a harmless no-op.
if [ "$NO_TAME" != 1 ]; then
  step "🧹 taming" "${D}recommended vendor apps (eWeLink, factory test tools)${X}"
  curl -fsS --data-urlencode "action=recommended" "$URL/api/v1/tame" >/dev/null 2>&1 \
    && echo "   ${GRN}✓${X} recommended set applied (where present + safe)" \
    || echo "   ${D}(nothing to tame / no privileged path)${X}"
fi

# Config bundle export (after any restore/config, so the file reflects the final state). Includes
# secrets — the bundle is a full-recovery artifact; store it like a credential.
if [ -n "$EXPORT_FILE" ]; then
  step "📦 exporting config" "${D}→ $EXPORT_FILE (includes secrets — protect it)${X}"
  curl -fsS "$URL/api/v1/config/export?include_secrets=1" -o "$EXPORT_FILE"     && echo "   ${GRN}✓${X} $(wc -c < "$EXPORT_FILE") bytes"     || echo "   ${RED}✗ export failed${X}"
fi

echo
verify && echo "${GRN}${B}✅ provisioned${X} — ${B}$URL/${X}" || echo "${YEL}↻ re-run the same command to finish (idempotent).${X}"
echo "${D}   Root daemon: required on EVERY sandbox-walled panel (appCanSu=false — TPA10, SMT1019, …), not just LED panels. It's the privileged path for screen-off, density, governor, screenshot, perf and buttons even when ledMechanism=NONE. rk3576/PX30 panels can exec su directly and don't need it.${X}"
echo "${D}   Install it with:  ./helper/build.sh && ./helper/install-daemon.sh $TARGET${X}"

# Flag a too-old system WebView (informational; the dashboard won't render on ancient Chrome).
check_webview

# Tuya/TPA10 only: offer to disable the closed vendor app stack for a faster, minimal HA panel (guarded
# on root + persistent adb + a replacement launcher; prompts unless --strip-vendor was passed).
offer_strip_vendor
