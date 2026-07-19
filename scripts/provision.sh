#!/usr/bin/env bash
# ha-paneld provisioning — install + grant all permissions over adb, no device UI, and (optionally)
# set the panel id + MQTT broker in one shot. Requires adb access; privileged features additionally
# need a route reported by the app's active hardware profile and live provisioning plan.
# For non-root panels, --shizuku installs and starts the pinned manager; approval remains on-panel.
#
# Usage:
#   scripts/provision.sh <panel-ip:5555> [APK] \
#       [--id PANEL_ID] [--mqtt tcp://host:1883] [--mqtt-user U] [--mqtt-pass P] [--apk PATH] \
#       [--mqtt-pass-file FILE]         # preferred: keep the password out of process arguments
#       [--log-host HOST] [--log-port N] [--log-proto syslog|http]   # forward logcat to an aggregator
#       [--shizuku]                    # non-root enhanced access; still needs local approval
#   Built-in WebView renderer (experimental — no HA Companion / kiosk app needed):
#       [--ha-url https://homeassistant.local:8123] [--builtin] \
#       [--ha-token <LLAT>]              # simple: a long-lived access token (a standing credential), OR
#       [--ha-token-file FILE]            # preferred token input: one private text file, OR
#       [--ha-user U --ha-pass P]        # preferred: log in HERE to mint a REFRESH token; the password
#                                        # never reaches the panel, and no 10-year token lives on it.
#       [--ha-user U --ha-pass-file FILE] # keeps the login password out of process arguments
#   scripts/provision.sh <panel-ip:5555> --verify        # check end-state only, make no changes
#
# IDEMPOTENCY (safe to re-run after a cancel/failure — re-running converges to the full state):
#   - install -r / appops allow / pm grant / accessibility_enabled / am start  : no-ops on re-run
#   - enabled_accessibility_services : appends our service only if absent (guarded)
#   - POST /api/v1/config            : partial-merge — only sets the keys you pass
# `set -e` aborts cleanly on any failure; the ERR trap tells you to re-run, and every run ends with a
# self-verify checklist. `--verify` is the standing control: run any time to confirm a panel is set up.
set -euo pipefail
umask 077

# Colours/emoji — only when writing to a terminal, so piped/redirected output stays clean.
if [ -t 1 ]; then
  B=$'\033[1m'; D=$'\033[2m'; X=$'\033[0m'
  RED=$'\033[31m'; GRN=$'\033[32m'; YEL=$'\033[33m'; CYN=$'\033[36m'; MAG=$'\033[35m'
else B=; D=; X=; RED=; GRN=; YEL=; CYN=; MAG=; fi
step() { echo "${CYN}${B}$1${X} $2"; }
warn() { echo "   ${YEL}⚠ $1${X}"; }
# Panel and HTTP responses are untrusted terminal input. Preserve ordinary text,
# tabs and line breaks while removing escape sequences and other control bytes.
sanitize_terminal() { LC_ALL=C tr -d '\000-\010\013\014\016-\037\177'; }
approval_required_response() {
  LC_ALL=C grep -Eq '"error"[[:space:]]*:[[:space:]]*"approval-required"' "$@"
}

usage() {
  cat <<'EOF'
Usage: scripts/provision.sh <panel-ip[:port]> [APK] [options]

Common operations:
  --latest                 Install the latest stable release
  --prerelease             Install the latest release candidate
  --apk FILE               Install a specific APK
  --export FILE            Export config/settings only; never installs unless combined with mutations
  --restore FILE           Import a config JSON export (not a complete .hpb backup)
  --restore-fleet FILE     Import only portable, non-secret keys from a config JSON export
  --verify                 Check the existing installation only; never installs
  --no-tame                Deprecated compatibility no-op; guidance is never auto-applied
  --shizuku                Install/start pinned Shizuku for locally approved non-root access
  --allow-unsigned-helper  Developer-only: allow a privileged helper from an unsigned local APK
  --mqtt-pass-file FILE    Read the MQTT password from a private text file
  --ha-token-file FILE     Read the Home Assistant token from a private text file
  --ha-pass-file FILE      Read the Home Assistant login password from a private text file
  --help                   Show this help

The script installs, starts, and verifies ha-paneld. It exits nonzero if a required step is incomplete.
EOF
}

cleanup_provision_resources() {
  if type stop_root_helper_apk_install >/dev/null 2>&1; then stop_root_helper_apk_install; fi
  if type cleanup_root_helper_lease_guard >/dev/null 2>&1; then cleanup_root_helper_lease_guard; fi
  [ -z "${SHIZUKU_DIR:-}" ] || rm -rf "$SHIZUKU_DIR"
  SHIZUKU_DIR=""
  [ -z "${SECRET_DIR:-}" ] || rm -rf "$SECRET_DIR"
  SECRET_DIR=""
}

handle_provision_signal() {
  local status="$1"
  trap - EXIT INT TERM
  cleanup_provision_resources
  exit "$status"
}

trap 'echo "${RED}${B}✗ provisioning incomplete${X} — re-run the SAME command to finish (it is idempotent)." >&2' ERR
trap 'cleanup_provision_resources' EXIT
trap 'handle_provision_signal 130' INT
trap 'handle_provision_signal 143' TERM

# Fatal with SPECIFIC recovery steps. Disarms the generic re-run trap first: these are the failures
# re-running cannot fix, so the trap's blanket advice would mislead.
fail() {
  if type cleanup_root_helper_lease_guard >/dev/null 2>&1; then cleanup_root_helper_lease_guard; fi
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
RELEASE_HELPER_BUILD_ID=""
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HELPER_DIST_DIR="${HAPANELD_HELPER_DIST_DIR:-$SCRIPT_DIR/../helper/dist}"
A11Y="$PKG/.input.PanelAccessibilityService"
APK=""; APK_RELEASE_TAG=""; PANEL_ID=""; MQTT=""; MQTT_USER=""; MQTT_PASS=""; VERIFY_ONLY=0; LATEST=0; PRERELEASE=0; FORCE=0; PERSIST_ADB=0; STRIP_VENDOR=0; SHIZUKU=0; ALLOW_UNSIGNED_HELPER=0; TOINSTALL_VER=""; VERIFY_DIRECT_GRANTS=0
LOG_HOST=""; LOG_PORT=""; LOG_PROTO=""; LOG_ENABLE=""
EXPORT_FILE=""; RESTORE_FILE=""; RESTORE_MODE=""
HA_URL=""; HA_TOKEN=""; HA_USER=""; HA_PASS=""; HA_REFRESH=""; HA_EXPIRY=""; BUILTIN=0
MQTT_PASS_INPUT_FILE="${HAPANELD_MQTT_PASS_FILE:-}"
HA_TOKEN_INPUT_FILE="${HAPANELD_HA_TOKEN_FILE:-}"
HA_PASS_INPUT_FILE="${HAPANELD_HA_PASS_FILE:-}"
MQTT_PASS_SOURCE_COUNT=0; HA_TOKEN_SOURCE_COUNT=0; HA_PASS_SOURCE_COUNT=0
[ -z "$MQTT_PASS_INPUT_FILE" ] || MQTT_PASS_SOURCE_COUNT=1
[ -z "$HA_TOKEN_INPUT_FILE" ] || HA_TOKEN_SOURCE_COUNT=1
[ -z "$HA_PASS_INPUT_FILE" ] || HA_PASS_SOURCE_COUNT=1
MQTT_PASS_SECRET_FILE=""; HA_TOKEN_SECRET_FILE=""; HA_PASS_SECRET_FILE=""; HA_REFRESH_SECRET_FILE=""
SECRET_DIR=""
HA_LOGIN_FAILED=0
HELPER_REQUIRED=0
ROOT_HELPER_TRANSACTION_KIND=""
ROOT_HELPER_TRANSACTION_SHA256=""
ROOT_HELPER_TRANSACTION_PATH=""
ROOT_HELPER_TRANSACTION_ID=""
ROOT_HELPER_STAGED_HELPER=""
ROOT_HELPER_STAGED_RC=""
ROOT_HELPER_STAGED_HYBRID_RC=""
ROOT_HELPER_STAGED_SERVICE=""
ROOT_HELPER_STAGED_TRANSACTION=""
ROOT_HELPER_TARGET_BUILD_ID=""
ROOT_HELPER_TARGET_SHA256=""
ROOT_HELPER_LEASE_GUARD_PID=""
ROOT_HELPER_LEASE_GUARD_FAILURE=""
ROOT_HELPER_APK_INSTALL_PID=""
ROOT_HELPER_APK_INSTALL_OUTPUT_FILE=""
ADB_INSTALL_OUTPUT=""
SHIZUKU_DIR=""
TARGET_APK_SHA256=""

if [ "${1:-}" ] && [ "${1#--}" = "${1:-}" ]; then APK="$1"; shift; fi
while [ "${1:-}" ]; do
  case "$1" in
    --apk|--release-tag|--id|--mqtt|--mqtt-user|--mqtt-pass|--mqtt-pass-file|--log-host|--log-port|--log-proto|--ha-url|--ha-token|--ha-token-file|--ha-user|--ha-pass|--ha-pass-file|--export|--restore|--restore-fleet)
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
    --mqtt-pass) MQTT_PASS="$2"; MQTT_PASS_SOURCE_COUNT=$((MQTT_PASS_SOURCE_COUNT + 1)); shift 2 ;;
    --mqtt-pass-file) MQTT_PASS_INPUT_FILE="$2"; MQTT_PASS_SOURCE_COUNT=$((MQTT_PASS_SOURCE_COUNT + 1)); shift 2 ;;
    --latest) LATEST=1; shift ;;     # ignore any local build, fetch the newest STABLE GitHub release
    --prerelease|--pre) PRERELEASE=1; LATEST=1; shift ;;   # fetch the newest release incl. release-candidates
    --force) FORCE=1; shift ;;       # skip the same/older-version prompt
    --persist-adb) PERSIST_ADB=1; shift ;;  # keep network adb (tcp 5555) across reboots (opt-in; standing LAN port)
    --strip-vendor) STRIP_VENDOR=1; shift ;; # disable the Tuya vendor apps (TPA10) non-interactively (skips the prompt)
    --no-tame) shift ;;   # deprecated compatibility no-op; profile recommendations are report-only
    --shizuku) SHIZUKU=1; shift ;;   # install/start pinned Shizuku on a non-root panel; permission stays local
    --allow-unsigned-helper) ALLOW_UNSIGNED_HELPER=1; shift ;; # developer acknowledgement for local privileged bytes
    --log-host) LOG_HOST="$2"; LOG_ENABLE=true; shift 2 ;;  # ship logcat to this aggregator (host enables shipping)
    --log-port) LOG_PORT="$2"; shift 2 ;;     # log sink port (default 514 for syslog)
    --log-proto) LOG_PROTO="$2"; shift 2 ;;   # syslog (default) | http
    --log-off) LOG_ENABLE=false; shift ;;     # disable log shipping
    --ha-url) HA_URL="$2"; shift 2 ;;         # Home Assistant URL for the built-in WebView renderer
    --ha-token) HA_TOKEN="$2"; HA_TOKEN_SOURCE_COUNT=$((HA_TOKEN_SOURCE_COUNT + 1)); shift 2 ;;     # compatibility: visible in the invoking process argv
    --ha-token-file) HA_TOKEN_INPUT_FILE="$2"; HA_TOKEN_SOURCE_COUNT=$((HA_TOKEN_SOURCE_COUNT + 1)); shift 2 ;;
    --ha-user) HA_USER="$2"; shift 2 ;;       # HA username — login here to mint a refresh token (no LLAT)
    --ha-pass) HA_PASS="$2"; HA_PASS_SOURCE_COUNT=$((HA_PASS_SOURCE_COUNT + 1)); shift 2 ;;       # compatibility: visible in the invoking process argv
    --ha-pass-file) HA_PASS_INPUT_FILE="$2"; HA_PASS_SOURCE_COUNT=$((HA_PASS_SOURCE_COUNT + 1)); shift 2 ;;
    --builtin) BUILTIN=1; shift ;;            # select ha-paneld's built-in renderer as the dashboard
    --export) EXPORT_FILE="$2"; shift 2 ;;    # save the panel's config bundle (incl. secrets) to FILE
    --restore) RESTORE_FILE="$2"; RESTORE_MODE="restore"; shift 2 ;;      # import config JSON, including device-scoped keys
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
release_helper_name() { printf 'ha-paneld-helper-%s-%s\n' "$1" "$2"; }
release_helper_url() { printf 'https://github.com/%s/releases/download/%s/%s\n' "$REPO" "$1" "$(release_helper_name "$1" "$2")"; }
release_has_helper_assets() {
  local version major minor patch
  version="${1#v}"; version="${version%%-*}"
  IFS=. read -r major minor patch <<< "$version"
  [ "$major" -gt 0 ] || [ "$minor" -gt 9 ] || { [ "$minor" -eq 9 ] && [ "$patch" -ge 4 ]; }
}
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

# Normalize every credential source into an owner-only temporary text file before any panel contact.
# Legacy literal flags remain accepted for compatibility, but all descendant curl operations receive
# only file paths. A trailing text-file line ending is ignored; embedded line breaks and NUL bytes are
# rejected because HA and MQTT credentials are single-line values.
prepare_secret() {
  local label="$1" literal_var="$2" input_var="$3" source_count_var="$4" output_var="$5" filename="$6"
  local literal="${!literal_var}" source="${!input_var}" source_count="${!source_count_var}" bytes value output mode group_other line_feeds last_byte
  [ "$source_count" -le 1 ] || fail "$label source was supplied more than once" \
    "Use one file option only so the credential source is unambiguous."
  if [ -n "$source" ]; then
    [ ! -L "$source" ] || fail "$label file must not be a symbolic link: $source"
    [ -f "$source" ] || fail "$label file is not a regular file: $source"
    [ -r "$source" ] || fail "$label file is not readable: $source"
    bytes="$(wc -c < "$source")"
    [ "$bytes" -gt 0 ] || fail "$label file is empty: $source"
    [ "$bytes" -le 65536 ] || fail "$label file is too large: $source (maximum 65536 bytes)"
    case "$(uname -s 2>/dev/null || true)" in
      MINGW*|MSYS*|CYGWIN*) ;;
      *)
        mode="$(stat -c '%a' "$source" 2>/dev/null || stat -f '%Lp' "$source" 2>/dev/null || true)"
        case "$mode" in *[!0-7]*|'') fail "could not verify owner-only permissions on $label file: $source" ;; esac
        group_other="${mode: -2}"
        [ "$group_other" = 00 ] || fail "$label file is readable by other users: $source" \
          "Protect that file with chmod 600, then retry."
        ;;
    esac
    if od -An -tx1 "$source" 2>/dev/null | tr ' ' '\n' | grep -qx '00'; then
      fail "$label file contains a NUL byte: $source" "Use one plain-text credential line."
    fi
    line_feeds="$(LC_ALL=C tr -cd '\n' < "$source" | wc -c | tr -d ' ')"
    case "$line_feeds" in ''|*[!0-9]*) fail "could not validate the line structure of $label file: $source" ;; esac
    [ "$line_feeds" -le 1 ] || fail "$label file contains more than one text line: $source"
    if [ "$line_feeds" -eq 1 ]; then
      last_byte="$(tail -c 1 "$source" 2>/dev/null | od -An -tx1 | tr -d ' \n')"
      [ "$last_byte" = 0a ] || fail "$label file contains an embedded line break: $source"
    fi
    value="$(LC_ALL=C command cat -- "$source")"
    value="${value%$'\r'}"
    [ -n "$value" ] || fail "$label file contains no credential text: $source"
    case "$value" in *$'\n'*|*$'\r'*) fail "$label file contains more than one text line: $source" ;; esac
  else
    value="$literal"
  fi
  if [ -n "$value" ]; then
    if [ -z "$SECRET_DIR" ]; then
      SECRET_DIR="$(mktemp -d)" || fail "could not create private credential storage"
      chmod 700 "$SECRET_DIR" || fail "could not protect private credential storage"
    fi
    output="$SECRET_DIR/$filename"
    printf '%s' "$value" > "$output"
    chmod 600 "$output"
    printf -v "$literal_var" '%s' "$value"
    printf -v "$output_var" '%s' "$output"
  fi
}

prepare_secret "MQTT password" MQTT_PASS MQTT_PASS_INPUT_FILE MQTT_PASS_SOURCE_COUNT MQTT_PASS_SECRET_FILE mqtt-password
prepare_secret "Home Assistant token" HA_TOKEN HA_TOKEN_INPUT_FILE HA_TOKEN_SOURCE_COUNT HA_TOKEN_SECRET_FILE ha-token
prepare_secret "Home Assistant password" HA_PASS HA_PASS_INPUT_FILE HA_PASS_SOURCE_COUNT HA_PASS_SECRET_FILE ha-password

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

# Every credential/config POST has both a connection deadline and an end-to-end deadline. Keep the
# values overridable for unusually slow LANs and deterministic regression tests, but never allow an
# empty, zero, or non-numeric override to turn a bounded operation back into an unbounded one.
HA_AUTH_CONNECT_TIMEOUT_SECONDS="${HA_AUTH_CONNECT_TIMEOUT_SECONDS:-5}"
HA_AUTH_TIMEOUT_SECONDS="${HA_AUTH_TIMEOUT_SECONDS:-30}"
PANEL_POST_CONNECT_TIMEOUT_SECONDS="${PANEL_POST_CONNECT_TIMEOUT_SECONDS:-5}"
PANEL_POST_TIMEOUT_SECONDS="${PANEL_POST_TIMEOUT_SECONDS:-30}"
PANEL_RESTORE_TIMEOUT_SECONDS="${PANEL_RESTORE_TIMEOUT_SECONDS:-60}"
APK_INSTALL_TIMEOUT_SECONDS="${APK_INSTALL_TIMEOUT_SECONDS:-300}"
for timeout_name in HA_AUTH_CONNECT_TIMEOUT_SECONDS HA_AUTH_TIMEOUT_SECONDS \
    PANEL_POST_CONNECT_TIMEOUT_SECONDS PANEL_POST_TIMEOUT_SECONDS PANEL_RESTORE_TIMEOUT_SECONDS \
    APK_INSTALL_TIMEOUT_SECONDS; do
  timeout_value="${!timeout_name}"
  case "$timeout_value" in
    ''|*[!0-9]*|0)
      echo "${RED}✗ $timeout_name must be a positive whole number of seconds.${X}" >&2
      exit 2
      ;;
  esac
done

# A CLI restore is a config JSON import, whose server envelope is 1 MiB. Validate it before even
# contacting adb so a missing, unreadable, empty, symlinked, or oversized input cannot fail after an
# APK/helper install, permission grant, launch, or other panel mutation has already happened.
if [ -n "$RESTORE_FILE" ]; then
  [ ! -L "$RESTORE_FILE" ] || fail "config import must be a regular file, not a symbolic link: $RESTORE_FILE" \
    "Choose the config JSON file itself, then retry the identical command."
  [ -f "$RESTORE_FILE" ] || fail "config import file not found: $RESTORE_FILE" \
    "--restore accepts JSON created by --export; complete .hpb backups are restored from the panel's Install page."
  [ -r "$RESTORE_FILE" ] || fail "config import file is not readable: $RESTORE_FILE" \
    "Grant this user read access to the config JSON, then retry the identical command."
  restore_bytes="$(wc -c < "$RESTORE_FILE")"
  [ "$restore_bytes" -gt 0 ] || fail "config import file is empty: $RESTORE_FILE" \
    "Export the panel config again and verify the JSON file is non-empty before retrying."
  [ "$restore_bytes" -le 1048576 ] || fail "config import file is too large: $RESTORE_FILE ($restore_bytes bytes; maximum 1048576)" \
    "--restore accepts config JSON only. Restore a complete .hpb backup from the panel's Install page."
  restore_json_runtime=""
  for candidate in python3 python; do
    if command -v "$candidate" >/dev/null 2>&1 &&
       "$candidate" -c 'import sys; raise SystemExit(0 if sys.version_info[0] == 3 else 1)' >/dev/null 2>&1; then
      restore_json_runtime="$candidate"
      break
    fi
  done
  [ -n "$restore_json_runtime" ] || fail "config import validation needs Python 3 before the panel can be changed" \
    "Install Python 3, then retry. Complete .hpb backups are restored from the panel's Install page."
  "$restore_json_runtime" - "$RESTORE_FILE" <<'PY' || fail \
    "config import is not a valid ha-paneld config JSON export: $RESTORE_FILE" \
    "Use JSON created by provision.sh --export. Restore a complete .hpb backup from the panel's Install page."
import json
import sys

try:
    with open(sys.argv[1], "r", encoding="utf-8") as source:
        bundle = json.load(source)
except (OSError, UnicodeError, json.JSONDecodeError):
    raise SystemExit(1)
valid = (
    type(bundle) is dict
    and set(bundle).issubset({"kind", "schema", "exported_at", "exported_by", "values"})
    and bundle.get("kind") == "ha-paneld-config"
    and type(bundle.get("schema")) is int
    and bundle["schema"] >= 1
    and ("exported_at" not in bundle or type(bundle["exported_at"]) is str)
    and ("exported_by" not in bundle or type(bundle["exported_by"]) is str)
    and type(bundle.get("values")) is dict
    and all(type(key) is str and type(value) is str for key, value in bundle["values"].items())
)
if not valid:
    raise SystemExit(1)
PY
fi
HOST="${TARGET%:*}"
[ "$HOST" != "$TARGET" ] || HOST="$TARGET"
URL="http://$HOST:8888"
PROVISION_FAILED=0
PROVISIONING_PLAN_AVAILABLE=0

# The app, not Bash, owns profile parsing, hardware matching and provisioning policy. Bash only waits
# for and renders the app's terminal-safe view. HTTP 503 means profile activation is still settling;
# a paired install must not silently succeed without the endpoint promised by that release. `--verify`
# remains useful against pre-0.9.4 panels: a legacy 404 is advisory and all established GET checks run.
show_provisioning_plan() {
  local required="$1" label="$2"
  local timeout="${PROVISIONING_PLAN_TIMEOUT_SECONDS:-40}" deadline remaining request_timeout code="" body plan_text="" status=unavailable
  case "$timeout" in ''|*[!0-9]*|0) timeout=40 ;; esac
  deadline=$((SECONDS + timeout))
  body="$(mktemp)"
  step "🧭 panel profile" "$label"
  while [ "$SECONDS" -lt "$deadline" ]; do
    remaining=$((deadline - SECONDS))
    request_timeout=4
    [ "$remaining" -lt "$request_timeout" ] && request_timeout="$remaining"
    : > "$body"
    if code="$(curl -sS --max-time "$request_timeout" -o "$body" -w '%{http_code}' \
        "$URL/api/v1/provisioning/plan.txt" 2>/dev/null)"; then
      case "$code" in
        200)
          if [ -s "$body" ]; then
            plan_text="$(tr -d '\r' < "$body" | sanitize_terminal)"
            if [ -n "$plan_text" ]; then
              printf '%s\n' "$plan_text"
              PROVISIONING_PLAN_AVAILABLE=1
              rm -f "$body"
              return 0
            fi
          fi
          status=empty
          ;;
        404)
          rm -f "$body"
          if [ "$required" = 1 ]; then
            warn "the installed app does not provide its paired provisioning-plan endpoint; the install cannot be verified as complete"
            return 1
          fi
          warn "this older ha-paneld does not provide profile-guided provisioning; using the legacy verification checks"
          return 0
          ;;
        503) status=starting ;;
        *) status="HTTP ${code:-unknown}" ;;
      esac
    else
      status=unreachable
    fi
    [ "$SECONDS" -lt "$deadline" ] && sleep 1
  done
  rm -f "$body"
  if [ "$required" = 1 ]; then
    warn "the provisioning plan stayed ${status:-unavailable} for ${timeout}s; the paired app did not finish profile activation"
    return 1
  fi
  warn "profile-guided provisioning stayed ${status:-unavailable}; the installed app could not complete profile verification"
  return 1
}

verify() {
  step "🔎 verifying" "${D}$URL${X}"
  local health diag cfg rc=0 write_settings_state="" a11y_state="" a11y_enabled_state="" a11y_granted=""
  health="$(curl -fsS --max-time 5 "$URL/health" 2>/dev/null || true)"
  diag="$(curl -fsS --max-time 25 "$URL/api/v1/diag" 2>/dev/null || true)"
  if [ -z "$diag" ] && [ -n "$health" ] && [ "$VERIFY_DIRECT_GRANTS" != 1 ]; then
    warn "the agent is healthy but diagnostics are still starting; retrying once"
    sleep 3
    diag="$(curl -fsS --max-time 25 "$URL/api/v1/diag" 2>/dev/null || true)"
  fi
  # A package replacement can start the app before the grants below are applied. Its complete cached
  # support report is therefore not the authority for post-install grant verification: read Android's
  # actual settings back directly, with a short independent bound on each host-side adb operation.
  if [ "$VERIFY_DIRECT_GRANTS" = 1 ]; then
    write_settings_state="$(run_with_deadline 5 "$ADB_COMMAND" -s "$TARGET" shell \
      appops get "$PKG" WRITE_SETTINGS 2>/dev/null || true)"
    a11y_state="$(run_with_deadline 5 "$ADB_COMMAND" -s "$TARGET" shell \
      settings get secure enabled_accessibility_services 2>/dev/null || true)"
    a11y_enabled_state="$(run_with_deadline 5 "$ADB_COMMAND" -s "$TARGET" shell \
      settings get secure accessibility_enabled 2>/dev/null || true)"
    a11y_state="${a11y_state//$'\r'/}"
    a11y_enabled_state="${a11y_enabled_state//$'\r'/}"
    if [ "$a11y_enabled_state" = 1 ]; then
      case ":$a11y_state:" in *":$A11Y:"*) a11y_granted=1 ;; esac
    fi
  fi
  cfg="$(curl -fsS --max-time 3 "$URL/api/v1/config" 2>/dev/null || true)"
  chk() { if printf '%s' "$2" | grep -q "$3"; then echo "   ${GRN}✓${X} $1"; else echo "   ${RED}✗ $1${X}"; rc=1; fi; }
  chk "HTTP server reachable"  "$health" "ha-paneld"
  if [ "$VERIFY_DIRECT_GRANTS" = 1 ]; then
    chk "WRITE_SETTINGS granted" "$write_settings_state" 'WRITE_SETTINGS: allow'
    chk "accessibility enabled"  "$a11y_granted" '^1$'
    case "$write_settings_state" in
      *'WRITE_SETTINGS: allow'*) : ;;
      *) echo "     ${D}Enable Settings → Apps → ha-paneld → Modify system settings, then re-run this command.${X}" ;;
    esac
    if [ "$a11y_granted" != 1 ]; then
      echo "     ${D}Enable Settings → Accessibility → ha-paneld, then re-run this command.${X}"
    fi
  else
    chk "WRITE_SETTINGS granted" "$diag" "write_settings=true"
    chk "accessibility enabled"  "$diag" "a11y=true"
  fi
  # Root helper daemon — installed automatically on every rooted panel by current provisioners.
  if printf '%s' "$diag" | grep -q "daemon=true"; then
    echo "   ${GRN}✓${X} root helper daemon: running"
  elif [ -n "$diag" ]; then
    if [ "$HELPER_REQUIRED" = 1 ]; then
      echo "   ${RED}✗ root helper daemon: not detected after installation${X}"
      rc=1
    elif [ "$PROVISIONING_PLAN_AVAILABLE" != 1 ]; then
      echo "   ${YEL}ℹ${X} root helper daemon: ${YEL}not detected${X} ${D}(needed on sandbox-walled panels — helper/install-daemon.sh)${X}"
    fi
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
    echo "     ${D}the web UI and Back/Recents. Privileged hardware, system maintenance and private-app${X}"
    echo "     ${D}operations remain unavailable.${X}"
  fi
  # panel_id + MQTT (informational — install-only is valid). grep/cut so no python (Git Bash-friendly).
  local broker pid
  broker="$(printf '%s' "$cfg" | grep -o '"mqtt_broker":"[^"]*"' | head -1 | cut -d'"' -f4)"
  pid="$(printf '%s' "$cfg" | grep -o '"panel_id":"[^"]*"' | head -1 | cut -d'"' -f4)"
  broker="$(printf '%s' "$broker" | sanitize_terminal)"
  pid="$(printf '%s' "$pid" | sanitize_terminal)"
  if [ -n "$broker" ]; then echo "   ${GRN}✓${X} MQTT broker: ${B}$broker${X}"
  else echo "   ${YEL}ℹ${X} MQTT broker: ${YEL}not explicitly set${X} ${D}(LAN auto-discovery will be attempted; it can also be set in Configure)${X}"; fi
  echo "   ${YEL}ℹ${X} panel_id: ${B}${pid:-?}${X}"
  return $rc
}

# `adb connect` exits 0 even when it FAILS ("cannot connect"), and a connected device can still sit
# "unauthorized" (RSA dialog waiting on the panel screen) or "offline" (stale adbd session). Verify the
# state explicitly so the failure surfaces HERE with recovery steps, not as an obscure error (or a
# hang) at the first real adb command.
adb_preflight_raw() {
  local state="" i=0
  "$ADB_COMMAND" connect "$TARGET" >/dev/null 2>&1 || true
  while [ "$i" -lt 12 ]; do
    i=$((i + 1))
    state="$("$ADB_COMMAND" devices 2>/dev/null | awk -v t="$TARGET" '$1==t {print $2}')"
    if [ "$state" = "device" ]; then printf 'device\n'; return 0; fi
    # Stale session ("offline"): reset it once, then keep polling.
    if [ "$state" = "offline" ] && [ "$i" = 4 ]; then
      "$ADB_COMMAND" disconnect "$TARGET" >/dev/null 2>&1 || true
      "$ADB_COMMAND" connect "$TARGET" >/dev/null 2>&1 || true
    fi
    sleep 1
  done
  printf '%s\n' "${state:-unreachable}"
  return 1
}

adb_preflight() {
  local state="" status timeout="${ADB_PREFLIGHT_TIMEOUT_SECONDS:-30}"
  case "$timeout" in ''|*[!0-9]*|0) timeout=30 ;; esac
  step "🔌 connecting" "$TARGET"
  if state="$(run_with_deadline "$timeout" adb_preflight_raw)"; then
    return 0
  else
    status=$?
  fi
  if [ "$status" -eq 124 ] || [ "$status" -eq 137 ]; then state=unreachable; fi
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

# Run one host command behind a hard deadline. Bash job control gives the command its own local process
# group so the supervisor can terminate its direct worker and non-detached local descendants, then
# reap the worker before returning. Keeping the supervisor in the provisioner's outer process group
# also makes fleet INT/TERM compositional: the fleet wrapper signals the supervisor, which owns and
# reaps this nested group. This bounds the host adb invocation; it does not claim to kill a device-side
# process after the adb transport is gone. Status 124 is reserved for the deadline so callers can
# distinguish it from a normal command failure.
run_with_deadline() {
  local seconds="$1" command_pid status deadline
  shift

  (
    command_pid=""
    terminate_deadline_command_group() {
      local signal_status="$1" kill_deadline
      trap - INT TERM
      if [ -n "$command_pid" ]; then
        kill -TERM -- "-$command_pid" 2>/dev/null || true
        kill_deadline=$((SECONDS + 2))
        while kill -0 -- "-$command_pid" 2>/dev/null && [ "$SECONDS" -lt "$kill_deadline" ]; do
          sleep 0.1
        done
        kill -KILL -- "-$command_pid" 2>/dev/null || true
        wait "$command_pid" 2>/dev/null || true
        command_pid=""
      fi
      return "$signal_status"
    }
    handle_deadline_signal() {
      local signal_status="$1"
      terminate_deadline_command_group "$signal_status" || true
      exit "$signal_status"
    }
    trap 'handle_deadline_signal 130' INT
    trap 'handle_deadline_signal 143' TERM
    set -m
    "$@" &
    command_pid=$!
    # The asynchronous command keeps the process group assigned above after monitor mode is disabled;
    # disabling it immediately prevents Bash job-completion notices from contaminating captured output.
    set +m
    deadline=$((SECONDS + seconds))
    while kill -0 -- "-$command_pid" 2>/dev/null && [ "$SECONDS" -lt "$deadline" ]; do
      sleep 0.1
    done
    if kill -0 -- "-$command_pid" 2>/dev/null; then
      terminate_deadline_command_group 124 || true
      return 124
    fi
    wait "$command_pid"
    status=$?
    command_pid=""
    return "$status"
  )
}

# Resolve the executable before defining the wrapper so every ordinary adb operation shares the same
# bounded host-command policy. Operations with stronger transaction-specific semantics (notably APK
# install) invoke the absolute executable beneath their own deadline owner instead of nesting owners.
ADB_COMMAND="$(command -v adb 2>/dev/null || true)"
[ -n "$ADB_COMMAND" ] || fail "adb (Android Platform Tools) was not found" \
  "Install adb, then re-run the identical command; no panel changes were made."
ADB_COMMAND_TIMEOUT_SECONDS="${ADB_COMMAND_TIMEOUT_SECONDS:-120}"
case "$ADB_COMMAND_TIMEOUT_SECONDS" in ''|*[!0-9]*|0)
  fail "ADB_COMMAND_TIMEOUT_SECONDS must be a positive whole number of seconds" ;;
esac
adb() {
  run_with_deadline "$ADB_COMMAND_TIMEOUT_SECONDS" "$ADB_COMMAND" "$@"
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
SU_PROBE_TIMED_OUT=0
probe_su_uncached() {
  local u key pre
  u="$("$ADB_COMMAND" -s "$TARGET" shell id 2>/dev/null | tr -d '\r')" || u=""
  case "$u" in uid=0*) printf 'shell\n'; return 0 ;; esac
  for key in su0 suroot; do
    case "$key" in su0) pre="su 0" ;; suroot) pre="su root" ;; esac
    u="$("$ADB_COMMAND" -s "$TARGET" shell "$pre \"id; id\"" 2>/dev/null | tr -d '\r')" || u=""
    case "$u" in *uid=0*) printf '%sjoin\n' "$key"; return 0 ;; esac
    u="$("$ADB_COMMAND" -s "$TARGET" shell "$pre sh -c \"id; id\"" 2>/dev/null | tr -d '\r')" || u=""
    case "$u" in *uid=0*) printf '%sshc\n' "$key"; return 0 ;; esac
  done
  u="$("$ADB_COMMAND" -s "$TARGET" shell "su -c \"id; id\"" 2>/dev/null | tr -d '\r')" || u=""
  case "$u" in *uid=0*) printf 'suc\n'; return 0 ;; esac
  printf 'none\n'
  return 1
}

probe_su() {
  if [ -n "$SU_FORM" ]; then [ "$SU_FORM" != none ]; return; fi
  local result="" status timeout="${PRIVILEGE_INSPECTION_TIMEOUT_SECONDS:-45}"
  case "$timeout" in ''|*[!0-9]*|0) timeout=45 ;; esac
  PRIVILEGE_INSPECTION_TIMEOUT_SECONDS="$timeout"
  SU_PROBE_TIMED_OUT=0
  if result="$(run_with_deadline "$timeout" probe_su_uncached)"; then
    SU_FORM="$result"
    return 0
  else
    status=$?
  fi
  if [ "$status" -eq 124 ] || [ "$status" -eq 137 ]; then
    SU_PROBE_TIMED_OUT=1
    SU_FORM=""
  else
    SU_FORM="${result:-none}"
  fi
  return 1
}

# Quote one command string for the device shell's outer double quotes. Join-style su needs the whole
# command as one argv word, but the unprivileged device shell must not expand its dollars, backticks,
# substitutions, or embedded quotes before su receives it.
quote_root_command() {
  local command="$1"
  command="${command//\\/\\\\}"
  command="${command//\"/\\\"}"
  command="${command//\$/\\\$}"
  command="${command//\`/\\\`}"
  printf '%s\n' "$command"
}

# Run one command string as root via the probed form.
run_root() {
  local command="$1" quoted
  probe_su || return 1
  quoted="$(quote_root_command "$command")"
  case "$SU_FORM" in
    shell)      adb -s "$TARGET" shell "$command" ;;
    su0join)    adb -s "$TARGET" shell "su 0 \"$quoted\"" ;;
    su0shc)     adb -s "$TARGET" shell "su 0 sh -c \"$quoted\"" ;;
    surootjoin) adb -s "$TARGET" shell "su root \"$quoted\"" ;;
    surootshc)  adb -s "$TARGET" shell "su root sh -c \"$quoted\"" ;;
    suc)        adb -s "$TARGET" shell "su -c \"$quoted\"" ;;
  esac
}

# Tuya panels (TPA10) ship a closed vendor stack (launcher, system UI, Tuya IoT, hardware, diagnostics)
# that does nothing for an HA panel and uses CPU/RAM. Offer to disable it — but ONLY once the panel can
# run without it. Reversible: re-enable any package with `adb shell pm enable <pkg>`.
offer_strip_vendor() {
  local brand model
  # This is an optional post-verification offer. A panel can disappear after a fully successful install
  # (or adb can transiently return 255), so every advisory probe must degrade to "not detected" rather
  # than trip the global ERR trap and turn a verified run into a false failure.
  brand="$(adb -s "$TARGET" shell getprop ro.product.brand 2>/dev/null | tr -d '\r' || true)"
  model="$(adb -s "$TARGET" shell getprop ro.product.model 2>/dev/null | tr -d '\r' || true)"
  case "$brand $model" in *[Tt]uya*|*TPA10*) ;; *) return 0 ;; esac   # Tuya/TPA10 only

  # Guard 1 — root (vendor apps are system apps; pm disable-user needs root on the userdebug build).
  if ! probe_su; then
    if [ "$STRIP_VENDOR" = 1 ]; then echo "   ${YEL}⚠ --strip-vendor ignored: no root path found (su or adbd-root).${X}"; fi
    return 0
  fi
  # Guard 2 — persistent adb, else disabling the diagnostics-app adb backdoor can lock you out.
  local adben tcp
  adben="$(adb -s "$TARGET" shell settings get global adb_enabled 2>/dev/null | tr -d '\r' || true)"
  tcp="$(adb -s "$TARGET" shell getprop persist.adb.tcp.port 2>/dev/null | tr -d '\r' || true)"
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

# Fetch the newest signed release APK from GitHub's bounded HTTPS API. Sets APK + TOINSTALL_VER.
download_latest() {
  local dir tag="" url json api record asset expected_url
  dir="$(mktemp -d)"
  # PRERELEASE=1 → newest release of ANY kind (incl. rc); else the newest STABLE. GitHub's
  # /releases/latest EXCLUDES prereleases, so the prerelease path lists all releases and takes the first.
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

verify_release_helper() {
  local helper="$1" tag="$2" abi="$3"
  local proof_dir checksum_file signature_file public_key expected_name record expected_hash actual_hash
  expected_name="$(release_helper_name "$tag" "$abi")"
  step "🔐 authenticating helper" "${D}$tag · $abi · signed checksum and build bytes${X}"
  proof_dir="$(mktemp -d)" || fail "could not create a temporary directory for helper verification" \
    "Free some disk space, then re-run; no panel changes were made."
  checksum_file="$proof_dir/helper.sha256"
  signature_file="$proof_dir/helper.sha256.sig"
  public_key="$proof_dir/release-public-key.pem"
  if ! write_release_public_key "$public_key"; then
    rm -rf "$proof_dir"
    fail "could not prepare the trusted ha-paneld release key" "No panel changes were made. Check free disk space, then re-run."
  fi
  if ! RELEASE_HELPER_SOURCE="$helper" curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 60 \
      "$(release_helper_url "$tag" "$abi").sha256" -o "$checksum_file"; then
    rm -rf "$proof_dir"
    fail "could not download the signed helper checksum for $tag ($abi)" \
      "The release may be incomplete or GitHub may be unavailable. No panel changes were made; check internet access and retry."
  fi
  if ! curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 60 \
      "$(release_helper_url "$tag" "$abi").sha256.sig" -o "$signature_file"; then
    rm -rf "$proof_dir"
    fail "could not download the helper checksum signature for $tag ($abi)" \
      "The release may be incomplete or GitHub may be unavailable. No panel changes were made; check internet access and retry."
  fi
  if ! openssl dgst -sha256 -verify "$public_key" -signature "$signature_file" "$checksum_file" >/dev/null 2>&1; then
    rm -rf "$proof_dir"
    fail "the $tag helper checksum signature is invalid" \
      "The release assets could be incomplete, damaged, or substituted. Nothing was installed, started, or privileged."
  fi
  record="$(cat "$checksum_file")"
  expected_hash="${record%% *}"
  if ! printf '%s\n' "$expected_hash" | grep -Eq '^[0-9A-Fa-f]{64}$' || [ "$record" != "$expected_hash  $expected_name" ]; then
    rm -rf "$proof_dir"
    fail "the signed helper checksum record for $tag is malformed" \
      "Expected one SHA-256 record for $expected_name. Nothing was installed, started, or privileged."
  fi
  if ! actual_hash="$(openssl dgst -sha256 -r "$helper" 2>/dev/null | awk '{print tolower($1)}')" || \
      ! printf '%s\n' "$actual_hash" | grep -Eq '^[0-9a-f]{64}$'; then
    rm -rf "$proof_dir"
    fail "OpenSSL could not calculate the downloaded helper checksum" \
      "No panel changes were made. Check that the helper asset is readable and re-run the installer."
  fi
  expected_hash="$(printf '%s' "$expected_hash" | tr '[:upper:]' '[:lower:]')"
  if [ "$actual_hash" != "$expected_hash" ]; then
    rm -rf "$proof_dir"
    fail "the $tag helper checksum does not match its signed record" \
      "The helper could be incomplete, damaged, or substituted. Nothing was installed, started, or privileged."
  fi
  rm -rf "$proof_dir"
  step "🔐 authenticated" "${D}$tag helper · $abi · signed SHA-256 ${actual_hash:0:12}…${X}"
}

fetch_release_helper_build_id() {
  local tag="$1" proof_dir provisioner checksum_file signature_file public_key expected_name
  local record expected_hash actual_hash build_id
  expected_name="ha-paneld-provision-$tag.sh"
  proof_dir="$(mktemp -d)" || return 1
  provisioner="$proof_dir/$expected_name"
  checksum_file="$proof_dir/$expected_name.sha256"
  signature_file="$checksum_file.sig"
  public_key="$proof_dir/release-public-key.pem"
  write_release_public_key "$public_key" || { rm -rf "$proof_dir"; return 1; }
  curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 60 \
    "https://github.com/$REPO/releases/download/$tag/$expected_name" -o "$provisioner" || { rm -rf "$proof_dir"; return 1; }
  RELEASE_PROVISION_SOURCE="$provisioner" curl -fsSL --proto '=https' --proto-redir '=https' \
    --connect-timeout 15 --max-time 60 \
    "https://github.com/$REPO/releases/download/$tag/$expected_name.sha256" -o "$checksum_file" || {
      rm -rf "$proof_dir"; return 1;
    }
  curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 60 \
    "https://github.com/$REPO/releases/download/$tag/$expected_name.sha256.sig" -o "$signature_file" || {
      rm -rf "$proof_dir"; return 1;
    }
  openssl dgst -sha256 -verify "$public_key" -signature "$signature_file" "$checksum_file" >/dev/null 2>&1 || {
    rm -rf "$proof_dir"; return 1;
  }
  record="$(cat "$checksum_file")"
  expected_hash="${record%% *}"
  if ! printf '%s\n' "$expected_hash" | grep -Eq '^[0-9A-Fa-f]{64}$' || \
     [ "$record" != "$expected_hash  $expected_name" ]; then
    rm -rf "$proof_dir"; return 1
  fi
  actual_hash="$(openssl dgst -sha256 -r "$provisioner" 2>/dev/null | awk '{print tolower($1)}')" || {
    rm -rf "$proof_dir"; return 1;
  }
  expected_hash="$(printf '%s' "$expected_hash" | tr '[:upper:]' '[:lower:]')"
  [ "$actual_hash" = "$expected_hash" ] || { rm -rf "$proof_dir"; return 1; }
  build_id="$(sed -nE 's/^RELEASE_HELPER_BUILD_ID="([0-9a-f]{64})"$/\1/p' "$provisioner")"
  rm -rf "$proof_dir"
  printf '%s\n' "$build_id" | grep -Eq '^[0-9a-f]{64}$' || return 1
  printf '%s\n' "$build_id"
}

host_sha256() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 "$1" | awk '{print $1}'
  else fail "cannot authenticate helper staging" "Install sha256sum (or shasum), then re-run."
  fi
}

host_transaction_id() {
  local id
  id="$(od -An -N16 -tx1 /dev/urandom 2>/dev/null | tr -d ' \n')"
  printf '%s\n' "$id" | grep -Eq '^[0-9a-f]{32}$' || return 1
  printf '%s\n' "$id"
}

ensure_root_path() {
  probe_su && return 0
  [ "$SU_PROBE_TIMED_OUT" = 0 ] || fail "root-access inspection timed out" \
    "adb or a privilege prompt did not respond within ${PRIVILEGE_INSPECTION_TIMEOUT_SECONDS:-45}s." \
    "Nothing was installed. Restore adb responsiveness, dismiss any on-panel root prompt, then re-run."

  # Userdebug panels may expose root only after `adb root`. Unsupported production builds reject this
  # harmlessly; a successful restart briefly drops the transport, so reconnect before probing again.
  adb -s "$TARGET" root >/dev/null 2>&1 || true
  sleep 1
  adb connect "$TARGET" >/dev/null 2>&1 || true
  local state="" attempt=0
  while [ "$attempt" -lt 8 ]; do
    attempt=$((attempt + 1))
    state="$(adb devices 2>/dev/null | awk -v t="$TARGET" '$1==t {print $2}')"
    [ "$state" = device ] && break
    sleep 1
  done
  SU_FORM=""
  probe_su && return 0
  [ "$SU_PROBE_TIMED_OUT" = 0 ] || fail "root-access inspection timed out after adb root" \
    "The panel reconnected, but its root route did not answer within ${PRIVILEGE_INSPECTION_TIMEOUT_SECONDS:-45}s." \
    "Nothing was installed. Check adb and any on-panel privilege prompt, then re-run."
  return 1
}

helper_daemon_reply() {
  local command="$1" install_kind="$2" helper_path="${3:-}"
  case "$command" in PING|COMPANIONCAPS|BUILDID) ;; *) return 1 ;; esac
  if [ -z "$helper_path" ]; then
    case "$install_kind" in
      system) helper_path=/system/bin/hapaneld-helper ;;
      systemless|hybrid) helper_path=/data/adb/hapaneld/hapaneld-helper ;;
      *) return 1 ;;
    esac
  fi
  case "$helper_path" in
    /system/bin/hapaneld-helper|/data/adb/hapaneld/hapaneld-helper|/data/adb/hapaneld/.helper-probe-[0-9a-f]*) ;;
    *) return 1 ;;
  esac
  if [ -n "${HAPANELD_HELPER_PROBE:-}" ]; then
    "$HAPANELD_HELPER_PROBE" "$command"
    return $?
  fi
  run_root 'exec '"$helper_path"' --request '"$command"
}

wait_for_helper_reply() {
  local command="$1" expected="$2" install_kind="$3" helper_path="${4:-}" reply="" attempt=0
  while [ "$attempt" -lt 10 ]; do
    attempt=$((attempt + 1))
    reply="$(helper_daemon_reply "$command" "$install_kind" "$helper_path" 2>/dev/null || true)"
    [ "$reply" = "$expected" ] && return 0
    sleep 1
  done
  return 1
}

prepare_root_helper_probe() {
  local transaction_id="$1" probe_path expected source ready
  printf '%s\n' "$transaction_id" | grep -Eq '^[0-9a-f]{32}$' || return 1
  expected="$ROOT_HELPER_TARGET_SHA256"
  source="$ROOT_HELPER_STAGED_HELPER"
  probe_path="/data/adb/hapaneld/.helper-probe-$transaction_id"
  printf '%s\n' "$expected" | grep -Eq '^[0-9a-f]{64}$' || return 1
  [ "$source" = "/data/local/tmp/hapaneld-helper-$ROOT_HELPER_TRANSACTION_ID" ] || return 1
  ready="$(run_root 'mkdir -p /data/adb/hapaneld || exit 1
    chown 0:0 /data/adb/hapaneld || exit 1
    chmod 700 /data/adb/hapaneld || exit 1
    source='"$source"'
    destination='"$probe_path"'
    expected='"$expected"'
    actual=$(sha256sum $source 2>/dev/null || toybox sha256sum $source 2>/dev/null) || exit 1
    [ ${actual%% *} = $expected ] || exit 1
    cp $source $destination.new || exit 1
    chown 0:0 $destination.new || exit 1
    chmod 700 $destination.new || exit 1
    actual=$(sha256sum $destination.new 2>/dev/null || toybox sha256sum $destination.new 2>/dev/null) || exit 1
    [ ${actual%% *} = $expected ] || exit 1
    mv -f $destination.new $destination || exit 1
    echo PROBE_READY' 2>&1)" || true
  [ "$ready" = PROBE_READY ]
}

rollback_root_helper() {
  local install_kind="$1" transaction_id="${2:-$ROOT_HELPER_TRANSACTION_ID}" target_apk="${3:-$TARGET_APK_SHA256}"
  local target_build="${4:-$ROOT_HELPER_TARGET_BUILD_ID}" target_helper="${5:-$ROOT_HELPER_TARGET_SHA256}" restored probe_path
  probe_path="/data/adb/hapaneld/.helper-probe-$transaction_id"
  prepare_root_helper_probe "$transaction_id" || return 1
  case "$install_kind" in
    system)
      restored="$(run_root_helper_transaction rollback-system "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>&1)" || true
      ;;
    systemless)
      restored="$(run_root_helper_transaction rollback-systemless "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>&1)" || true
      ;;
    hybrid)
      restored="$(run_root_helper_transaction rollback-hybrid "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>&1)" || true
      ;;
    *) return 1 ;;
  esac
  if printf '%s\n' "$restored" | grep -qx ROLLBACK_RESTARTED; then
    if wait_for_helper_reply PING OK "$install_kind" "$probe_path"; then
      run_root 'rm -f '"$probe_path" >/dev/null 2>&1 || true
      finalize_root_helper_rollback "$install_kind" "$transaction_id" "$target_apk" "$target_build" "$target_helper"
      return $?
    fi
    run_root 'rm -f '"$probe_path" >/dev/null 2>&1 || true
    return 1
  else
    run_root 'rm -f '"$probe_path" >/dev/null 2>&1 || true
    if printf '%s\n' "$restored" | grep -Eqx 'ROLLBACK_(EMPTY|LEGACY)'; then
      finalize_root_helper_rollback "$install_kind" "$transaction_id" "$target_apk" "$target_build" "$target_helper"
    else
      printf '%s\n' "$restored" | grep -qx ROLLBACK_UNNEEDED
    fi
  fi
}

finalize_root_helper_rollback() {
  local install_kind="$1" transaction_id="$2" target_apk="$3" target_build="$4" target_helper="$5" finalized
  case "$install_kind" in
    system)
      finalized="$(run_root_helper_transaction finalize-rollback-system "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>&1)" || true
      ;;
    systemless)
      finalized="$(run_root_helper_transaction finalize-rollback-systemless "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>&1)" || true
      ;;
    hybrid)
      finalized="$(run_root_helper_transaction finalize-rollback-hybrid "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>&1)" || true
      ;;
    *) return 1 ;;
  esac
  printf '%s\n' "$finalized" | grep -qx ROLLBACK_FINALIZED
}

commit_root_helper_upgrade() {
  local install_kind="$1" transaction_id="${2:-$ROOT_HELPER_TRANSACTION_ID}" target_apk="${3:-$TARGET_APK_SHA256}"
  local target_build="${4:-$ROOT_HELPER_TARGET_BUILD_ID}" target_helper="${5:-$ROOT_HELPER_TARGET_SHA256}" committed
  case "$install_kind" in
    system) committed="$(run_root_helper_transaction commit-system "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>&1)" || true ;;
    systemless) committed="$(run_root_helper_transaction commit-systemless "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>&1)" || true ;;
    hybrid) committed="$(run_root_helper_transaction commit-hybrid "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>&1)" || true ;;
    *) return 1 ;;
  esac
  printf '%s\n' "$committed" | grep -qx COMMIT_OK
}

renew_root_helper_lease() {
  local install_kind="$1" renewed
  case "$install_kind" in
    system) renewed="$(run_root_helper_transaction lease-system 2>&1)" || true ;;
    systemless) renewed="$(run_root_helper_transaction lease-systemless 2>&1)" || true ;;
    hybrid) renewed="$(run_root_helper_transaction lease-hybrid 2>&1)" || true ;;
    *) return 1 ;;
  esac
  printf '%s\n' "$renewed" | grep -qx LEASE_OK
}

start_root_helper_lease_guard() {
  [ -n "$ROOT_HELPER_TRANSACTION_KIND" ] || return 0
  local owner_pid="$$" interval="${ROOT_HELPER_LEASE_GUARD_INTERVAL_SECONDS:-30}"
  printf '%s\n' "$interval" | grep -Eq '^[0-9]+([.][0-9]+)?$' || interval=30
  ROOT_HELPER_LEASE_GUARD_FAILURE="$(mktemp)"
  (
    sleep_pid=""
    stop_lease_guard_sleep() {
      [ -z "$sleep_pid" ] || kill "$sleep_pid" >/dev/null 2>&1 || true
      [ -z "$sleep_pid" ] || wait "$sleep_pid" >/dev/null 2>&1 || true
      exit 0
    }
    trap 'stop_lease_guard_sleep' INT TERM
    while :; do
      /bin/sleep "$interval" &
      sleep_pid=$!
      [ -z "${ROOT_HELPER_LEASE_GUARD_SLEEP_PID_FILE:-}" ] || printf '%s\n' "$sleep_pid" > "$ROOT_HELPER_LEASE_GUARD_SLEEP_PID_FILE"
      wait "$sleep_pid" || exit 0
      sleep_pid=""
      kill -0 "$owner_pid" >/dev/null 2>&1 || exit 0
      renew_root_helper_lease "$ROOT_HELPER_TRANSACTION_KIND" || {
        printf 'failed\n' > "$ROOT_HELPER_LEASE_GUARD_FAILURE"
        exit 1
      }
    done
  ) &
  ROOT_HELPER_LEASE_GUARD_PID=$!
  [ -z "${ROOT_HELPER_LEASE_GUARD_PID_FILE:-}" ] || printf '%s\n' "$ROOT_HELPER_LEASE_GUARD_PID" > "$ROOT_HELPER_LEASE_GUARD_PID_FILE"
}

stop_root_helper_lease_guard() {
  if [ -n "${ROOT_HELPER_LEASE_GUARD_PID:-}" ]; then
    kill "$ROOT_HELPER_LEASE_GUARD_PID" >/dev/null 2>&1 || true
    wait "$ROOT_HELPER_LEASE_GUARD_PID" >/dev/null 2>&1 || true
    ROOT_HELPER_LEASE_GUARD_PID=""
  fi
}

lease_guard_succeeded() {
  [ -z "${ROOT_HELPER_LEASE_GUARD_FAILURE:-}" ] || [ ! -s "$ROOT_HELPER_LEASE_GUARD_FAILURE" ]
}

cleanup_root_helper_lease_guard() {
  stop_root_helper_lease_guard
  [ -z "${ROOT_HELPER_LEASE_GUARD_FAILURE:-}" ] || rm -f "$ROOT_HELPER_LEASE_GUARD_FAILURE"
  ROOT_HELPER_LEASE_GUARD_FAILURE=""
}

stop_root_helper_apk_install() {
  if [ -n "${ROOT_HELPER_APK_INSTALL_PID:-}" ]; then
    terminate_root_helper_apk_install
    ROOT_HELPER_APK_INSTALL_PID=""
  fi
  [ -z "${ROOT_HELPER_APK_INSTALL_OUTPUT_FILE:-}" ] || rm -f "$ROOT_HELPER_APK_INSTALL_OUTPUT_FILE"
  ROOT_HELPER_APK_INSTALL_OUTPUT_FILE=""
}

terminate_root_helper_apk_install() {
  local kill_deadline
  [ -n "${ROOT_HELPER_APK_INSTALL_PID:-}" ] || return 0
  kill -TERM "$ROOT_HELPER_APK_INSTALL_PID" >/dev/null 2>&1 || true
  kill_deadline=$((SECONDS + 2))
  while kill -0 "$ROOT_HELPER_APK_INSTALL_PID" 2>/dev/null && [ "$SECONDS" -lt "$kill_deadline" ]; do
    sleep 0.1
  done
  kill -KILL "$ROOT_HELPER_APK_INSTALL_PID" >/dev/null 2>&1 || true
  wait "$ROOT_HELPER_APK_INSTALL_PID" >/dev/null 2>&1 || true
}

run_adb_install_attempt() {
  local status deadline
  ROOT_HELPER_APK_INSTALL_OUTPUT_FILE="$(mktemp)"
  "$ADB_COMMAND" -s "$TARGET" install "$@" "$APK" > "$ROOT_HELPER_APK_INSTALL_OUTPUT_FILE" 2>&1 &
  ROOT_HELPER_APK_INSTALL_PID=$!
  deadline=$((SECONDS + APK_INSTALL_TIMEOUT_SECONDS))
  while kill -0 "$ROOT_HELPER_APK_INSTALL_PID" 2>/dev/null && [ "$SECONDS" -lt "$deadline" ]; do
    sleep 0.1
  done
  if kill -0 "$ROOT_HELPER_APK_INSTALL_PID" 2>/dev/null; then
    terminate_root_helper_apk_install
    status=124
  elif wait "$ROOT_HELPER_APK_INSTALL_PID"; then
    status=0
  else
    status=$?
  fi
  ROOT_HELPER_APK_INSTALL_PID=""
  ADB_INSTALL_OUTPUT="$(cat "$ROOT_HELPER_APK_INSTALL_OUTPUT_FILE")"
  if [ "$status" -eq 124 ]; then
    ADB_INSTALL_OUTPUT="${ADB_INSTALL_OUTPUT}${ADB_INSTALL_OUTPUT:+
}ADB_INSTALL_DEADLINE_EXCEEDED after ${APK_INSTALL_TIMEOUT_SECONDS}s"
  fi
  rm -f "$ROOT_HELPER_APK_INSTALL_OUTPUT_FILE"
  ROOT_HELPER_APK_INSTALL_OUTPUT_FILE=""
  return "$status"
}

run_root_helper_transaction() {
  local action="$1" transaction_id="${2:-$ROOT_HELPER_TRANSACTION_ID}" target_apk="${3:-$TARGET_APK_SHA256}"
  local target_build="${4:-$ROOT_HELPER_TARGET_BUILD_ID}" target_helper="${5:-$ROOT_HELPER_TARGET_SHA256}"
  case "$action" in
    install-system|install-systemless|install-hybrid|discover-system|discover-systemless|discover-hybrid|status-system|status-systemless|status-hybrid|lease-system|lease-systemless|lease-hybrid|rollback-system|rollback-systemless|rollback-hybrid|finalize-rollback-system|finalize-rollback-systemless|finalize-rollback-hybrid|commit-system|commit-systemless|commit-hybrid) ;;
    *) return 1 ;;
  esac
  printf '%s\n' "$ROOT_HELPER_TRANSACTION_SHA256" | grep -Eq '^[0-9a-f]{64}$' || return 1
  printf '%s\n' "$ROOT_HELPER_TRANSACTION_ID" | grep -Eq '^[0-9a-f]{32}$' || return 1
  [ "$ROOT_HELPER_TRANSACTION_PATH" = "/data/adb/hapaneld/.helper-transaction-$ROOT_HELPER_TRANSACTION_ID-$ROOT_HELPER_TRANSACTION_SHA256" ] || return 1
  run_root 'txn='"$ROOT_HELPER_TRANSACTION_PATH"'; expected='"$ROOT_HELPER_TRANSACTION_SHA256"'; owner=$(stat -c %u:%g $txn 2>/dev/null || toybox stat -c %u:%g $txn 2>/dev/null) || exit 1; [ $owner = 0:0 ] || exit 1; actual=$(sha256sum $txn 2>/dev/null || toybox sha256sum $txn 2>/dev/null) || exit 1; [ ${actual%% *} = $expected ] || exit 1; sh $txn '"$action $transaction_id $target_apk $target_build $target_helper"
}

cleanup_root_helper_staging() {
  run_root 'rm -f '"${ROOT_HELPER_STAGED_HELPER:-/dev/null} ${ROOT_HELPER_STAGED_RC:-/dev/null} ${ROOT_HELPER_STAGED_HYBRID_RC:-/dev/null} ${ROOT_HELPER_STAGED_SERVICE:-/dev/null} ${ROOT_HELPER_STAGED_TRANSACTION:-/dev/null} $ROOT_HELPER_TRANSACTION_PATH" \
    >/dev/null 2>&1 || true
}

root_helper_transaction_record() {
  case "$1" in
    system) run_root_helper_transaction discover-system 2>/dev/null ;;
    systemless) run_root_helper_transaction discover-systemless 2>/dev/null ;;
    hybrid) run_root_helper_transaction discover-hybrid 2>/dev/null ;;
    *) return 1 ;;
  esac
}

# Return 0 only when the exact APK bytes are installed, 1 for a definitive different/missing package,
# and 2 when adb transport or pulling the installed base APK is inconclusive.
installed_apk_matches_hash() {
  local expected="$1" path_output path dir pulled actual
  path_output="$(adb -s "$TARGET" shell pm path "$PKG" 2>/dev/null)" || return 2
  path="$(printf '%s\n' "$path_output" | tr -d '\r' | sed -n 's/^package://p' | head -1)"
  [ -n "$path" ] || return 1
  dir="$(mktemp -d)" || return 2
  pulled="$dir/installed.apk"
  if ! adb -s "$TARGET" pull "$path" "$pulled" >/dev/null 2>&1 || [ ! -s "$pulled" ]; then
    rm -rf "$dir"
    return 2
  fi
  actual="$(host_sha256 "$pulled" 2>/dev/null || true)"
  rm -rf "$dir"
  [ "$actual" = "$expected" ]
}

reconcile_stale_root_helper() {
  local install_kind="$1" record authenticated journal_version journal_scope transaction_id target_apk target_build target_helper live_state outcome
  record="$(root_helper_transaction_record "$install_kind" || true)"
  journal_version="$(printf '%s\n' "$record" | sed -n 's/^JOURNAL_VERSION=//p')"
  journal_scope="$(printf '%s\n' "$record" | sed -n 's/^JOURNAL_SCOPE=//p')"
  transaction_id="$(printf '%s\n' "$record" | sed -nE 's/^TRANSACTION_ID=([0-9a-f]{32})$/\1/p')"
  target_apk="$(printf '%s\n' "$record" | sed -nE 's/^TARGET_APK_SHA256=([0-9a-f]{64})$/\1/p')"
  target_build="$(printf '%s\n' "$record" | sed -nE 's/^TARGET_BUILD_ID=([0-9a-f]{64})$/\1/p')"
  target_helper="$(printf '%s\n' "$record" | sed -nE 's/^TARGET_HELPER_SHA256=([0-9a-f]{64})$/\1/p')"
  live_state="$(printf '%s\n' "$record" | sed -n 's/^LIVE_STATE=//p')"
  if [ "$journal_version" != 1 ] || [ "$journal_scope" != APK_HELPER ] || \
     ! printf '%s\n' "$transaction_id" | grep -Eq '^[0-9a-f]{32}$' || \
     ! printf '%s\n' "$target_apk" | grep -Eq '^[0-9a-f]{64}$' || \
     ! printf '%s\n' "$target_build" | grep -Eq '^[0-9a-f]{64}$' || \
     ! printf '%s\n' "$target_helper" | grep -Eq '^[0-9a-f]{64}$'; then
    return 2
  fi
  case "$install_kind" in
    system) authenticated="$(run_root_helper_transaction status-system "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>/dev/null || true)" ;;
    systemless) authenticated="$(run_root_helper_transaction status-systemless "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>/dev/null || true)" ;;
    hybrid) authenticated="$(run_root_helper_transaction status-hybrid "$transaction_id" "$target_apk" "$target_build" "$target_helper" 2>/dev/null || true)" ;;
  esac
  [ "$authenticated" = "$record" ] || return 2
  if installed_apk_matches_hash "$target_apk"; then
    wait_for_helper_reply COMPANIONCAPS "COMPANIONCAPS 1 BACKUP RESTORE STATUS JOURNAL" "$install_kind" || return 2
    wait_for_helper_reply BUILDID "BUILDID $target_build" "$install_kind" || return 2
    commit_root_helper_upgrade "$install_kind" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 2
    return 0
  else
    outcome=$?
  fi
  [ "$outcome" -eq 1 ] || return 2
  case "$live_state" in PRE_SWAP|TARGET|TRANSITION) ;; *) return 2 ;; esac
  rollback_root_helper "$install_kind" "$transaction_id" "$target_apk" "$target_build" "$target_helper"
}

resolve_root_helper_install_state() {
  local selected_kind="$1" stale_kind=""
  case "$out2" in
    STALE_SYSTEM_TRANSACTION) stale_kind=system ;;
    STALE_SYSTEMLESS_TRANSACTION) stale_kind=systemless ;;
    STALE_HYBRID_TRANSACTION) stale_kind=hybrid ;;
    MULTIPLE_STALE_TRANSACTIONS)
      fail "more than one root-helper recovery journal is present" \
        "No rollback was attempted because the authoritative prior install location is ambiguous." \
        "Inspect and recover the retained /system, systemless, and hybrid journals before provisioning again." ;;
    FOREIGN_MANUAL_TRANSACTION)
      fail "an incomplete standalone root-helper installation must be recovered first" \
        "Re-run ./helper/install-daemon.sh $TARGET from the same checkout; it owns the separate helper-only journal." \
        "Then re-run this provisioning command. No APK or helper files were changed by this attempt." ;;
    TRANSACTION_BUSY)
      fail "another root-helper transaction is active on the panel" \
        "Wait for the other installer or provisioner to finish, then re-run this command." ;;
    ACTIVE_SYSTEM_TRANSACTION|ACTIVE_SYSTEMLESS_TRANSACTION|ACTIVE_HYBRID_TRANSACTION)
      fail "another provisioner still owns the active root-helper transaction" \
        "Wait up to ten minutes for the owning run to finish or its recovery lease to expire, then re-run." ;;
  esac
  if [ -n "$stale_kind" ]; then
    reconcile_stale_root_helper "$stale_kind" || fail "an incomplete prior root-helper and APK upgrade could not be reconciled safely" \
      "No rollback was attempted because the installed APK and running helper identity could not both be established." \
      "Restore adb connectivity, then re-run this command to reconcile the retained $stale_kind recovery journal."
    out2="$(run_root_helper_transaction "install-$selected_kind" 2>&1)" || true
  fi
  case "$out2" in
    STALE_SYSTEM_TRANSACTION|STALE_SYSTEMLESS_TRANSACTION|STALE_HYBRID_TRANSACTION|MULTIPLE_STALE_TRANSACTIONS|FOREIGN_MANUAL_TRANSACTION|TRANSACTION_BUSY|ACTIVE_SYSTEM_TRANSACTION|ACTIVE_SYSTEMLESS_TRANSACTION|ACTIVE_HYBRID_TRANSACTION)
      fail "root-helper journal state changed while provisioning" \
        "No helper files were replaced by the conflicting transaction attempt." \
        "Wait for any other installer to finish, then re-run this command to reconcile the retained journal." ;;
  esac
}

install_root_helper() {
  local abi helper_dir="" helper="" helper_name="" helper_asset="" build_records="" rc_file="" hybrid_rc_file="" service_file="" transaction_file=""
  local bin_sha256 rc_sha256 hybrid_rc_sha256 service_sha256 transaction_sha256 transaction_ready="" expected_build_id="" out out2 root_ready=0 install_kind=""
  local system_avail_kb="" system_need_kb="" vendor_probe=""
  local access_timeout="${PRIVILEGE_INSPECTION_TIMEOUT_SECONDS:-45}" access_status

  case "$access_timeout" in ''|*[!0-9]*|0) access_timeout=45 ;; esac
  PRIVILEGE_INSPECTION_TIMEOUT_SECONDS="$access_timeout"
  step "🔎 inspecting access" "${D}panel ABI · root route · helper compatibility${X}"
  if abi="$(run_with_deadline "$access_timeout" "$ADB_COMMAND" -s "$TARGET" shell getprop ro.product.cpu.abi 2>/dev/null)"; then
    abi="$(printf '%s' "$abi" | tr -d '\r')"
  else
    access_status=$?
    if [ "$access_status" -eq 124 ] || [ "$access_status" -eq 137 ]; then
      fail "panel-architecture inspection timed out after ${access_timeout}s" \
        "adb did not answer before any helper or APK change. Restore adb responsiveness, then re-run."
    fi
    fail "could not inspect the panel architecture" \
      "adb did not return the panel ABI. Nothing was installed; restore adb responsiveness, then re-run."
  fi

  # A working in-app su route is a read-only probe. If that is absent, authenticate official helper
  # bytes before attempting `adb root`, so no downloaded privileged payload is trusted after a panel
  # privilege transition. Local builds are already operator-controlled and are resolved only if the
  # root-ADB attempt succeeds.
  probe_su && root_ready=1
  [ "$SU_PROBE_TIMED_OUT" = 0 ] || fail "root-access inspection timed out" \
    "adb or a privilege prompt did not respond within ${PRIVILEGE_INSPECTION_TIMEOUT_SECONDS:-45}s." \
    "Nothing was installed. Restore adb responsiveness, dismiss any on-panel root prompt, then re-run."
  case "$abi" in
    armeabi-v7a|arm64-v8a) ;;
    *)
      if [ "$root_ready" = 0 ] && ensure_root_path; then root_ready=1; fi
      if [ "$root_ready" = 0 ]; then
        echo "   ${YEL}ℹ${X} no root path available — continuing without the root helper"
        return 0
      fi
      fail "this rooted panel reports unsupported ABI '${abi:-unknown}'" \
        "Official helper assets are available for armeabi-v7a and arm64-v8a. Nothing was installed." ;;
  esac
  if [ -n "$APK_RELEASE_TAG" ] && release_has_helper_assets "$APK_RELEASE_TAG"; then
    helper_dir="$(mktemp -d)" || fail "could not create a temporary directory for the root helper" \
      "Free some disk space, then re-run; no panel changes were made."
    helper_name="$(release_helper_name "$APK_RELEASE_TAG" "$abi")"
    helper="$helper_dir/$helper_name"
    step "⬇️  root helper" "${D}downloading $helper_name${X}"
    if ! curl -fsSL --proto '=https' --proto-redir '=https' --connect-timeout 15 --max-time 300 \
        "$(release_helper_url "$APK_RELEASE_TAG" "$abi")" -o "$helper"; then
      rm -rf "$helper_dir"
      fail "could not download the $APK_RELEASE_TAG root helper for $abi" \
        "The release may be incomplete or GitHub may be unavailable. No panel changes were made."
    fi
    verify_release_helper "$helper" "$APK_RELEASE_TAG" "$abi"
    if [ "$root_ready" = 0 ] && ensure_root_path; then root_ready=1; fi
  elif [ -n "$APK_RELEASE_TAG" ]; then
    # Releases before the helper became a sealed release asset retain their historical direct-su
    # behaviour. Current releases always enter the authenticated branch above.
    echo "   ${YEL}ℹ${X} $APK_RELEASE_TAG predates automatic root-helper assets"
    return 0
  else
    if [ "$root_ready" = 0 ] && ensure_root_path; then root_ready=1; fi
    if [ "$root_ready" = 0 ]; then
      echo "   ${YEL}ℹ${X} no root path available — continuing without the root helper"
      return 0
    fi
    if [ "$ALLOW_UNSIGNED_HELPER" != 1 ]; then
      fail "the local APK is not an authenticated release, so its embedded root helper was not installed" \
        "If you built and trust this APK, re-run with --allow-unsigned-helper." \
        "The APK and existing helper were left unchanged. Official --latest/--prerelease installs do not need this flag."
    fi
    helper_dir="$(mktemp -d)" || fail "could not create a temporary directory for the root helper" \
      "Free some disk space, then re-run; no panel changes were made."
    case "$abi" in
      armeabi-v7a) helper_asset="assets/hapaneld-helper-arm" ;;
      arm64-v8a) helper_asset="assets/hapaneld-helper-arm64" ;;
    esac
    step "🧰 root helper" "${D}inspecting the $abi helper bundled in the local APK${X}"
    helper="$helper_dir/hapaneld-helper"
    if ! command -v unzip >/dev/null 2>&1; then
      rm -rf "$helper_dir"
      fail "unzip is required to read the root helper embedded in a local APK" \
        "Install unzip, then re-run this provisioning command." \
        "No helper or APK was replaced, so the panel remains on its previous working version."
    fi
    if ! unzip -p "$APK" "$helper_asset" > "$helper" 2>/dev/null || [ ! -s "$helper" ]; then
      rm -rf "$helper_dir"
      fail "the local APK does not contain its $abi root helper" \
        "Build the complete APK again, then re-run this provisioning command." \
        "No helper or APK was replaced, so the panel remains on its previous working version."
    fi
    build_records="$(grep -aoE 'BUILDID [0-9a-f]{64}' "$helper" 2>/dev/null || true)"
    expected_build_id="$(printf '%s\n' "$build_records" | sed -nE 's/^BUILDID ([0-9a-f]{64})$/\1/p')"
    if [ "$(printf '%s\n' "$expected_build_id" | grep -Ec '^[0-9a-f]{64}$')" -ne 1 ]; then
      rm -rf "$helper_dir"
      fail "the root helper embedded in the local APK has an invalid build identity" \
        "Build the complete APK again, then re-run. No helper or APK was replaced."
    fi
  fi
  if [ "$root_ready" = 0 ]; then
    [ -z "$helper_dir" ] || rm -rf "$helper_dir"
    echo "   ${YEL}ℹ${X} no root path available — continuing without the root helper"
    return 0
  fi
  HELPER_REQUIRED=1
  expected_build_id="$RELEASE_HELPER_BUILD_ID"
  if [ -z "$expected_build_id" ] && [ -n "$APK_RELEASE_TAG" ]; then
    step "🔎 helper compatibility" "${D}checking the authenticated provisioner's build identity${X}"
    expected_build_id="$(fetch_release_helper_build_id "$APK_RELEASE_TAG" || true)"
  elif [ -z "$expected_build_id" ] && [ -x "$SCRIPT_DIR/../helper/source-id.sh" ]; then
    expected_build_id="$("$SCRIPT_DIR/../helper/source-id.sh" 2>/dev/null || true)"
  fi
  if ! printf '%s\n' "$expected_build_id" | grep -Eq '^[0-9a-f]{64}$'; then
    if [ -n "$APK_RELEASE_TAG" ]; then
      fail "the expected root-helper build identity is unavailable" \
        "The signed versioned provisioner did not provide a valid helper identity. No APK or helper was replaced."
    fi
    fail "the expected root-helper build identity is unavailable" \
      "Run ./helper/build.sh from a complete checkout, then retry. No APK or helper was replaced."
  fi
  ROOT_HELPER_TRANSACTION_ID="$(host_transaction_id)" || fail "could not create a unique root-helper transaction identity" \
    "No helper or APK files were changed. Check host access to /dev/urandom, then retry."
  ROOT_HELPER_STAGED_HELPER="/data/local/tmp/hapaneld-helper-$ROOT_HELPER_TRANSACTION_ID"
  ROOT_HELPER_STAGED_RC="$ROOT_HELPER_STAGED_HELPER.rc"
  ROOT_HELPER_STAGED_HYBRID_RC="$ROOT_HELPER_STAGED_HELPER.hrc"
  ROOT_HELPER_STAGED_SERVICE="$ROOT_HELPER_STAGED_HELPER.svc"
  ROOT_HELPER_STAGED_TRANSACTION="$ROOT_HELPER_STAGED_HELPER.txn"
  ROOT_HELPER_TARGET_BUILD_ID="$expected_build_id"

  rc_file="$(mktemp)"
  hybrid_rc_file="$(mktemp)"
  service_file="$(mktemp)"
  cat > "$rc_file" <<'EOF'
service hapaneld_helper /system/bin/hapaneld-helper
    class main
    user root
    group root
    seclabel u:r:su:s0
EOF
  cat > "$hybrid_rc_file" <<'EOF'
service hapaneld_helper /data/adb/hapaneld/hapaneld-helper
    class main
    user root
    group root
    seclabel u:r:su:s0
EOF
  cat > "$service_file" <<'EOF'
#!/system/bin/sh
while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 3; done
/system/bin/stop hapaneld_helper 2>/dev/null
/system/bin/stop hapaneld_ledd 2>/dev/null
/system/bin/pkill -x hapaneld-helper 2>/dev/null
/system/bin/pkill -x hapaneld-ledd 2>/dev/null
/data/adb/hapaneld/hapaneld-helper >/dev/null 2>&1 &
EOF
  transaction_file="$(mktemp)"
  cat > "$transaction_file" <<'EOF'
#!/system/bin/sh
set -u

hash_matches() {
  [ "$(file_sha256 "$2")" = "$1" ]
}

file_sha256() {
  actual=$(sha256sum "$1" 2>/dev/null || toybox sha256sum "$1" 2>/dev/null) || return 1
  printf '%s\n' "${actual%% *}"
}

root_owned() {
  owner=$(stat -c %u:%g "$1" 2>/dev/null || toybox stat -c %u:%g "$1" 2>/dev/null) || return 1
  [ "$owner" = 0:0 ]
}

boot_id() {
  cat /proc/sys/kernel/random/boot_id 2>/dev/null
}

uptime_seconds() {
  value=$(cat /proc/uptime 2>/dev/null) || return 1
  value=${value%%.*}
  case "$value" in ''|*[!0-9]*) return 1 ;; esac
  printf '%s\n' "$value"
}

lease_active() {
  marker=$1
  recorded_boot=$(sed -n 's/^LEASE_BOOT_ID=//p' "$marker")
  lease_until=$(sed -n 's/^LEASE_UNTIL_UPTIME=//p' "$marker")
  current_boot=$(boot_id) || return 1
  now=$(uptime_seconds) || return 1
  case "$lease_until" in ''|*[!0-9]*) return 1 ;; esac
  [ -n "$recorded_boot" ] && [ "$recorded_boot" = "$current_boot" ] && [ "$now" -lt "$lease_until" ]
}

valid_transaction_identity() {
  marker=$1 transaction_id=$2 target_apk=$3 target_build=$4 target_helper=$5
  grep -qx "TRANSACTION_ID=$transaction_id" "$marker" &&
    grep -qx "TARGET_APK_SHA256=$target_apk" "$marker" &&
    grep -qx "TARGET_BUILD_ID=$target_build" "$marker" &&
    grep -qx "TARGET_HELPER_SHA256=$target_helper" "$marker"
}

refresh_lease() {
  marker=$1
  current_boot=$(boot_id) || return 1
  now=$(uptime_seconds) || return 1
  lease_until=$((now + 600))
  sed '/^LEASE_BOOT_ID=/d; /^LEASE_UNTIL_UPTIME=/d' "$marker" > "$marker.lease-new" || return 1
  echo LEASE_BOOT_ID=$current_boot >> "$marker.lease-new"
  echo LEASE_UNTIL_UPTIME=$lease_until >> "$marker.lease-new"
  chown 0:0 "$marker.lease-new" || return 1
  chmod 600 "$marker.lease-new" || return 1
  sync || return 1
  mv -f "$marker.lease-new" "$marker" || return 1
  sync || return 1
}

snapshot() {
  live=$1 recovery=$2 mode=$3
  rm -f "$recovery" || return 1
  if [ ! -f "$live" ]; then
    echo "0 -"
    return 0
  fi
  cp -p "$live" "$recovery" || return 1
  chown 0:0 "$recovery" || return 1
  chmod "$mode" "$recovery" || return 1
  cmp -s "$live" "$recovery" || return 1
  root_owned "$recovery" || return 1
  recovery_sha=$(file_sha256 "$recovery") || return 1
  echo "1 $recovery_sha"
}

flag() {
  grep -q "^$1=1$" "$2"
}

valid_recovery() {
  name=$1 recovery=$2 marker=$3
  expected=$(sed -n "s/^${name}_SHA256=//p" "$marker")
  case "$expected" in ''|*[!0-9a-f]*) return 1 ;; esac
  [ "${#expected}" -eq 64 ] || return 1
  [ -f "$recovery" ] && root_owned "$recovery" && [ "$(file_sha256 "$recovery")" = "$expected" ]
}

live_matches_recorded() {
  name=$1 live=$2 marker=$3
  if flag "$name" "$marker"; then
    expected=$(sed -n "s/^${name}_SHA256=//p" "$marker")
    [ -f "$live" ] && root_owned "$live" && [ "$(file_sha256 "$live")" = "$expected" ]
  else
    [ ! -e "$live" ]
  fi
}

live_matches_recorded_or_target() {
  name=$1 live=$2 target_sha=$3 marker=$4
  if live_matches_recorded "$name" "$live" "$marker"; then
    return 0
  fi
  if [ "$target_sha" = - ]; then
    [ ! -e "$live" ]
  else
    [ -f "$live" ] && root_owned "$live" && hash_matches "$target_sha" "$live"
  fi
}

classify_system() {
  marker=/system/bin/.hapaneld-helper-upgrade
  # A no-op helper upgrade can match both the recorded old state and the desired target. Prefer
  # TARGET so a successful APK install can commit instead of stranding its recovery journal.
  if hash_matches @BIN_SHA256@ /system/bin/hapaneld-helper &&
       hash_matches @RC_SHA256@ /system/etc/init/hapaneld-helper.rc &&
       [ ! -e /system/bin/hapaneld-ledd ] && [ ! -e /system/etc/init/hapaneld-ledd.rc ] &&
       [ ! -e /vendor/etc/init/hapaneld-helper.rc ] &&
       [ ! -e /data/adb/hapaneld/hapaneld-helper ] && [ ! -e /data/adb/service.d/hapaneld-helper.sh ]; then
    echo TARGET
  elif live_matches_recorded OLD_BIN /system/bin/hapaneld-helper "$marker" &&
     live_matches_recorded OLD_SERVICE /system/etc/init/hapaneld-helper.rc "$marker" &&
     live_matches_recorded LEGACY_BIN /system/bin/hapaneld-ledd "$marker" &&
     live_matches_recorded LEGACY_SERVICE /system/etc/init/hapaneld-ledd.rc "$marker" &&
     live_matches_recorded ALT_BIN /data/adb/hapaneld/hapaneld-helper "$marker" &&
     live_matches_recorded ALT_SERVICE /data/adb/service.d/hapaneld-helper.sh "$marker"; then
    echo PRE_SWAP
  else
    echo UNKNOWN
  fi
}

classify_systemless() {
  marker=/data/adb/hapaneld/.helper-upgrade.marker
  if hash_matches @BIN_SHA256@ /data/adb/hapaneld/hapaneld-helper &&
       hash_matches @SERVICE_SHA256@ /data/adb/service.d/hapaneld-helper.sh &&
       [ ! -e /vendor/etc/init/hapaneld-helper.rc ]; then
    echo TARGET
  elif live_matches_recorded OLD_BIN /data/adb/hapaneld/hapaneld-helper "$marker" &&
     live_matches_recorded OLD_SERVICE /data/adb/service.d/hapaneld-helper.sh "$marker"; then
    echo PRE_SWAP
  else
    echo UNKNOWN
  fi
}

hybrid_matches_recorded() {
  marker=/data/adb/hapaneld/.helper-hybrid-upgrade.marker
  live_matches_recorded OLD_BIN /data/adb/hapaneld/hapaneld-helper "$marker" &&
    live_matches_recorded OLD_RC /vendor/etc/init/hapaneld-helper.rc "$marker" &&
    live_matches_recorded SYS_RC /system/etc/init/hapaneld-helper.rc "$marker" &&
    live_matches_recorded SYS_BIN /system/bin/hapaneld-helper "$marker" &&
    live_matches_recorded LEGACY_BIN /system/bin/hapaneld-ledd "$marker" &&
    live_matches_recorded LEGACY_SERVICE /system/etc/init/hapaneld-ledd.rc "$marker" &&
    live_matches_recorded ALT_SERVICE /data/adb/service.d/hapaneld-helper.sh "$marker"
}

classify_hybrid() {
  marker=/data/adb/hapaneld/.helper-hybrid-upgrade.marker
  if hash_matches @BIN_SHA256@ /data/adb/hapaneld/hapaneld-helper &&
       root_owned /data/adb/hapaneld/hapaneld-helper &&
       hash_matches @HYBRID_RC_SHA256@ /vendor/etc/init/hapaneld-helper.rc &&
       root_owned /vendor/etc/init/hapaneld-helper.rc &&
       [ ! -e /system/bin/hapaneld-helper ] && [ ! -e /system/etc/init/hapaneld-helper.rc ] &&
       [ ! -e /system/bin/hapaneld-ledd ] && [ ! -e /system/etc/init/hapaneld-ledd.rc ] &&
       [ ! -e /data/adb/service.d/hapaneld-helper.sh ]; then
    echo TARGET
  elif hybrid_matches_recorded; then
    echo PRE_SWAP
  elif live_matches_recorded_or_target OLD_BIN /data/adb/hapaneld/hapaneld-helper @BIN_SHA256@ "$marker" &&
     live_matches_recorded_or_target OLD_RC /vendor/etc/init/hapaneld-helper.rc @HYBRID_RC_SHA256@ "$marker" &&
     live_matches_recorded_or_target SYS_RC /system/etc/init/hapaneld-helper.rc - "$marker" &&
     live_matches_recorded_or_target SYS_BIN /system/bin/hapaneld-helper - "$marker" &&
     live_matches_recorded_or_target LEGACY_BIN /system/bin/hapaneld-ledd - "$marker" &&
     live_matches_recorded_or_target LEGACY_SERVICE /system/etc/init/hapaneld-ledd.rc - "$marker" &&
     live_matches_recorded_or_target ALT_SERVICE /data/adb/service.d/hapaneld-helper.sh - "$marker"; then
    echo TRANSITION
  else
    echo UNKNOWN
  fi
}

restore_or_remove() {
  name=$1 recovery=$2 live=$3 mode=$4 marker=$5
  if flag "$name" "$marker"; then
    cp -p "$recovery" "$live" || return 1
    chown 0:0 "$live" || return 1
    chmod "$mode" "$live" || return 1
    cmp -s "$recovery" "$live" || return 1
  else
    rm -f "$live" || return 1
  fi
}

acquire_helper_lock() {
  lock=/dev/.hapaneld-helper-transaction.lock
  if ! mkdir "$lock" 2>/dev/null; then
    holder=$(cat "$lock/pid" 2>/dev/null || true)
    case "$holder" in
      ''|*[!0-9]*) return 1 ;;
      *) [ ! -d "/proc/$holder" ] || return 1 ;;
    esac
    rm -rf "$lock" 2>/dev/null || return 1
    mkdir "$lock" 2>/dev/null || return 1
  fi
  echo $$ > "$lock/pid" || { rm -rf "$lock"; return 1; }
  trap 'rm -rf /dev/.hapaneld-helper-transaction.lock' 0 1 2 3 15
}

helper_journal_state() {
  system=0
  systemless=0
  hybrid=0
  [ ! -f /system/bin/.hapaneld-helper-upgrade ] || system=1
  [ ! -f /data/adb/hapaneld/.helper-upgrade.marker ] || systemless=1
  [ ! -f /data/adb/hapaneld/.helper-hybrid-upgrade.marker ] || hybrid=1
  if [ -f /system/bin/.hapaneld-helper-manual-upgrade ] || \
     [ -f /data/adb/hapaneld/.helper-manual-upgrade.marker ]; then
    echo FOREIGN_MANUAL_TRANSACTION
  elif [ $((system + systemless + hybrid)) -gt 1 ]; then
    echo MULTIPLE_STALE_TRANSACTIONS
  elif [ "$system" = 1 ]; then
    if lease_active /system/bin/.hapaneld-helper-upgrade; then echo ACTIVE_SYSTEM_TRANSACTION; else echo STALE_SYSTEM_TRANSACTION; fi
  elif [ "$systemless" = 1 ]; then
    if lease_active /data/adb/hapaneld/.helper-upgrade.marker; then echo ACTIVE_SYSTEMLESS_TRANSACTION; else echo STALE_SYSTEMLESS_TRANSACTION; fi
  elif [ "$hybrid" = 1 ]; then
    if lease_active /data/adb/hapaneld/.helper-hybrid-upgrade.marker; then echo ACTIVE_HYBRID_TRANSACTION; else echo STALE_HYBRID_TRANSACTION; fi
  else
    echo NO_STALE_TRANSACTION
  fi
}

install_system() {
  mount -o rw,remount / 2>/dev/null
  mount -o rw,remount /system 2>/dev/null
  marker=/system/bin/.hapaneld-helper-upgrade
  state=$(helper_journal_state)
  [ "$state" = NO_STALE_TRANSACTION ] || { echo "$state"; return 2; }
  [ ! -e /vendor/etc/init/hapaneld-helper.rc ] || return 1
  rm -f /system/bin/hapaneld-helper.hapaneld-recovery \
    /system/etc/init/hapaneld-helper.rc.hapaneld-recovery \
    /system/bin/hapaneld-ledd.hapaneld-recovery \
    /system/etc/init/hapaneld-ledd.rc.hapaneld-recovery \
    /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery \
    /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery

  cp @STAGED_HELPER@ /system/bin/hapaneld-helper.new || return 1
  hash_matches @BIN_SHA256@ /system/bin/hapaneld-helper.new || return 1
  chown 0:0 /system/bin/hapaneld-helper.new || return 1
  chmod 755 /system/bin/hapaneld-helper.new || return 1
  chcon u:object_r:system_file:s0 /system/bin/hapaneld-helper.new 2>/dev/null
  cp @STAGED_RC@ /system/etc/init/hapaneld-helper.rc.new || return 1
  hash_matches @RC_SHA256@ /system/etc/init/hapaneld-helper.rc.new || return 1
  chown 0:0 /system/etc/init/hapaneld-helper.rc.new || return 1
  chmod 644 /system/etc/init/hapaneld-helper.rc.new || return 1
  chcon u:object_r:system_file:s0 /system/etc/init/hapaneld-helper.rc.new 2>/dev/null

  old_bin_record=$(snapshot /system/bin/hapaneld-helper /system/bin/hapaneld-helper.hapaneld-recovery 755) || return 1
  old_service_record=$(snapshot /system/etc/init/hapaneld-helper.rc /system/etc/init/hapaneld-helper.rc.hapaneld-recovery 644) || return 1
  legacy_bin_record=$(snapshot /system/bin/hapaneld-ledd /system/bin/hapaneld-ledd.hapaneld-recovery 755) || return 1
  legacy_service_record=$(snapshot /system/etc/init/hapaneld-ledd.rc /system/etc/init/hapaneld-ledd.rc.hapaneld-recovery 644) || return 1
  alt_bin_record=$(snapshot /data/adb/hapaneld/hapaneld-helper /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery 755) || return 1
  alt_service_record=$(snapshot /data/adb/service.d/hapaneld-helper.sh /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery 755) || return 1
  old_bin=${old_bin_record%% *}; old_bin_sha=${old_bin_record#* }
  old_service=${old_service_record%% *}; old_service_sha=${old_service_record#* }
  legacy_bin=${legacy_bin_record%% *}; legacy_bin_sha=${legacy_bin_record#* }
  legacy_service=${legacy_service_record%% *}; legacy_service_sha=${legacy_service_record#* }
  alt_bin=${alt_bin_record%% *}; alt_bin_sha=${alt_bin_record#* }
  alt_service=${alt_service_record%% *}; alt_service_sha=${alt_service_record#* }
  current_boot=$(boot_id) || return 1
  now=$(uptime_seconds) || return 1
  lease_until=$((now + 600))

  {
    echo JOURNAL_VERSION=1
    echo JOURNAL_SCOPE=APK_HELPER
    echo TRANSACTION_ID=@TRANSACTION_ID@
    echo TARGET_APK_SHA256=@APK_SHA256@
    echo TARGET_BUILD_ID=@BUILD_ID@
    echo TARGET_HELPER_SHA256=@BIN_SHA256@
    echo OLD_BIN=$old_bin
    echo OLD_BIN_SHA256=$old_bin_sha
    echo OLD_SERVICE=$old_service
    echo OLD_SERVICE_SHA256=$old_service_sha
    echo LEGACY_BIN=$legacy_bin
    echo LEGACY_BIN_SHA256=$legacy_bin_sha
    echo LEGACY_SERVICE=$legacy_service
    echo LEGACY_SERVICE_SHA256=$legacy_service_sha
    echo ALT_BIN=$alt_bin
    echo ALT_BIN_SHA256=$alt_bin_sha
    echo ALT_SERVICE=$alt_service
    echo ALT_SERVICE_SHA256=$alt_service_sha
    echo LEASE_BOOT_ID=$current_boot
    echo LEASE_UNTIL_UPTIME=$lease_until
  } > "$marker.new" || return 1
  chown 0:0 "$marker.new" || return 1
  chmod 600 "$marker.new" || return 1
  sync || return 1
  mv -f "$marker.new" "$marker" || return 1
  sync || return 1

  stop hapaneld_helper 2>/dev/null
  stop hapaneld_ledd 2>/dev/null
  pkill -x hapaneld-helper 2>/dev/null
  pkill -x hapaneld-ledd 2>/dev/null
  rm -f /system/bin/hapaneld-helper /system/etc/init/hapaneld-helper.rc \
    /system/bin/hapaneld-ledd /system/etc/init/hapaneld-ledd.rc \
    /data/adb/hapaneld/hapaneld-helper /data/adb/service.d/hapaneld-helper.sh
  mv -f /system/bin/hapaneld-helper.new /system/bin/hapaneld-helper || return 1
  mv -f /system/etc/init/hapaneld-helper.rc.new /system/etc/init/hapaneld-helper.rc || return 1
  sync || return 1
  echo INSTALL_OK
}

install_systemless() {
  mkdir -p /data/adb/service.d /data/adb/hapaneld || return 1
  chown 0:0 /data/adb/hapaneld || return 1
  chmod 700 /data/adb/hapaneld || return 1
  marker=/data/adb/hapaneld/.helper-upgrade.marker
  state=$(helper_journal_state)
  [ "$state" = NO_STALE_TRANSACTION ] || { echo "$state"; return 2; }
  [ ! -e /vendor/etc/init/hapaneld-helper.rc ] || return 1
  rm -f /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery \
    /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery

  cp @STAGED_HELPER@ /data/adb/hapaneld/hapaneld-helper.new || return 1
  hash_matches @BIN_SHA256@ /data/adb/hapaneld/hapaneld-helper.new || return 1
  chown 0:0 /data/adb/hapaneld/hapaneld-helper.new || return 1
  chmod 755 /data/adb/hapaneld/hapaneld-helper.new || return 1
  cp @STAGED_SERVICE@ /data/adb/service.d/hapaneld-helper.sh.new || return 1
  hash_matches @SERVICE_SHA256@ /data/adb/service.d/hapaneld-helper.sh.new || return 1
  chown 0:0 /data/adb/service.d/hapaneld-helper.sh.new || return 1
  chmod 755 /data/adb/service.d/hapaneld-helper.sh.new || return 1

  old_bin_record=$(snapshot /data/adb/hapaneld/hapaneld-helper /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery 755) || return 1
  old_service_record=$(snapshot /data/adb/service.d/hapaneld-helper.sh /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery 755) || return 1
  old_bin=${old_bin_record%% *}; old_bin_sha=${old_bin_record#* }
  old_service=${old_service_record%% *}; old_service_sha=${old_service_record#* }
  current_boot=$(boot_id) || return 1
  now=$(uptime_seconds) || return 1
  lease_until=$((now + 600))
  {
    echo JOURNAL_VERSION=1
    echo JOURNAL_SCOPE=APK_HELPER
    echo TRANSACTION_ID=@TRANSACTION_ID@
    echo TARGET_APK_SHA256=@APK_SHA256@
    echo TARGET_BUILD_ID=@BUILD_ID@
    echo TARGET_HELPER_SHA256=@BIN_SHA256@
    echo OLD_BIN=$old_bin
    echo OLD_BIN_SHA256=$old_bin_sha
    echo OLD_SERVICE=$old_service
    echo OLD_SERVICE_SHA256=$old_service_sha
    echo LEASE_BOOT_ID=$current_boot
    echo LEASE_UNTIL_UPTIME=$lease_until
  } > "$marker.new" || return 1
  chown 0:0 "$marker.new" || return 1
  chmod 600 "$marker.new" || return 1
  sync || return 1
  mv -f "$marker.new" "$marker" || return 1
  sync || return 1

  stop hapaneld_helper 2>/dev/null
  stop hapaneld_ledd 2>/dev/null
  pkill -x hapaneld-helper 2>/dev/null
  pkill -x hapaneld-ledd 2>/dev/null
  rm -f /data/adb/hapaneld/hapaneld-helper /data/adb/service.d/hapaneld-helper.sh
  mv -f /data/adb/hapaneld/hapaneld-helper.new /data/adb/hapaneld/hapaneld-helper || return 1
  mv -f /data/adb/service.d/hapaneld-helper.sh.new /data/adb/service.d/hapaneld-helper.sh || return 1
  sync || return 1
  echo INSTALL_OK
}

# Keep the init-owned boot vector on /vendor while the sealed helper binary and every recovery
# artifact live on /data. This is used on stock panels whose writable /system cannot allocate the
# helper bytes. An existing vendor vector must already be the exact managed definition; unknown
# content is never adopted or overwritten.
install_hybrid() {
  mount -o rw,remount / 2>/dev/null
  mount -o rw,remount /system 2>/dev/null
  mount -o rw,remount /vendor 2>/dev/null
  mkdir -p /data/adb/hapaneld || return 1
  chown 0:0 /data/adb/hapaneld || return 1
  chmod 700 /data/adb/hapaneld || return 1
  marker=/data/adb/hapaneld/.helper-hybrid-upgrade.marker
  state=$(helper_journal_state)
  [ "$state" = NO_STALE_TRANSACTION ] || { echo "$state"; return 2; }
  if [ -e /vendor/etc/init/hapaneld-helper.rc ]; then
    root_owned /vendor/etc/init/hapaneld-helper.rc || return 1
    hash_matches @HYBRID_RC_SHA256@ /vendor/etc/init/hapaneld-helper.rc || return 1
  fi
  rm -f /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery \
    /data/adb/hapaneld/hapaneld-helper.vrc.hapaneld-recovery \
    /data/adb/hapaneld/hapaneld-helper.sysrc.hapaneld-recovery \
    /data/adb/hapaneld/hapaneld-helper.sysbin.hapaneld-recovery \
    /data/adb/hapaneld/hapaneld-ledd.sysbin.hapaneld-recovery \
    /data/adb/hapaneld/hapaneld-ledd.rc.hapaneld-recovery \
    /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery || return 1

  cp @STAGED_HELPER@ /data/adb/hapaneld/hapaneld-helper.new || return 1
  hash_matches @BIN_SHA256@ /data/adb/hapaneld/hapaneld-helper.new || return 1
  chown 0:0 /data/adb/hapaneld/hapaneld-helper.new || return 1
  chmod 755 /data/adb/hapaneld/hapaneld-helper.new || return 1
  cp @STAGED_HYBRID_RC@ /vendor/etc/init/hapaneld-helper.rc.new || return 1
  hash_matches @HYBRID_RC_SHA256@ /vendor/etc/init/hapaneld-helper.rc.new || return 1
  chown 0:0 /vendor/etc/init/hapaneld-helper.rc.new || return 1
  chmod 644 /vendor/etc/init/hapaneld-helper.rc.new || return 1
  chcon u:object_r:vendor_configs_file:s0 /vendor/etc/init/hapaneld-helper.rc.new 2>/dev/null

  old_bin_record=$(snapshot /data/adb/hapaneld/hapaneld-helper /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery 755) || return 1
  old_rc_record=$(snapshot /vendor/etc/init/hapaneld-helper.rc /data/adb/hapaneld/hapaneld-helper.vrc.hapaneld-recovery 644) || return 1
  sys_rc_record=$(snapshot /system/etc/init/hapaneld-helper.rc /data/adb/hapaneld/hapaneld-helper.sysrc.hapaneld-recovery 644) || return 1
  sys_bin_record=$(snapshot /system/bin/hapaneld-helper /data/adb/hapaneld/hapaneld-helper.sysbin.hapaneld-recovery 755) || return 1
  legacy_bin_record=$(snapshot /system/bin/hapaneld-ledd /data/adb/hapaneld/hapaneld-ledd.sysbin.hapaneld-recovery 755) || return 1
  legacy_service_record=$(snapshot /system/etc/init/hapaneld-ledd.rc /data/adb/hapaneld/hapaneld-ledd.rc.hapaneld-recovery 644) || return 1
  alt_service_record=$(snapshot /data/adb/service.d/hapaneld-helper.sh /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery 755) || return 1
  old_bin=${old_bin_record%% *}; old_bin_sha=${old_bin_record#* }
  old_rc=${old_rc_record%% *}; old_rc_sha=${old_rc_record#* }
  sys_rc=${sys_rc_record%% *}; sys_rc_sha=${sys_rc_record#* }
  sys_bin=${sys_bin_record%% *}; sys_bin_sha=${sys_bin_record#* }
  legacy_bin=${legacy_bin_record%% *}; legacy_bin_sha=${legacy_bin_record#* }
  legacy_service=${legacy_service_record%% *}; legacy_service_sha=${legacy_service_record#* }
  alt_service=${alt_service_record%% *}; alt_service_sha=${alt_service_record#* }
  current_boot=$(boot_id) || return 1
  now=$(uptime_seconds) || return 1
  lease_until=$((now + 600))

  {
    echo JOURNAL_VERSION=1
    echo JOURNAL_SCOPE=APK_HELPER
    echo TRANSACTION_ID=@TRANSACTION_ID@
    echo TARGET_APK_SHA256=@APK_SHA256@
    echo TARGET_BUILD_ID=@BUILD_ID@
    echo TARGET_HELPER_SHA256=@BIN_SHA256@
    echo OLD_BIN=$old_bin
    echo OLD_BIN_SHA256=$old_bin_sha
    echo OLD_RC=$old_rc
    echo OLD_RC_SHA256=$old_rc_sha
    echo SYS_RC=$sys_rc
    echo SYS_RC_SHA256=$sys_rc_sha
    echo SYS_BIN=$sys_bin
    echo SYS_BIN_SHA256=$sys_bin_sha
    echo LEGACY_BIN=$legacy_bin
    echo LEGACY_BIN_SHA256=$legacy_bin_sha
    echo LEGACY_SERVICE=$legacy_service
    echo LEGACY_SERVICE_SHA256=$legacy_service_sha
    echo ALT_SERVICE=$alt_service
    echo ALT_SERVICE_SHA256=$alt_service_sha
    echo LEASE_BOOT_ID=$current_boot
    echo LEASE_UNTIL_UPTIME=$lease_until
  } > "$marker.new" || return 1
  chown 0:0 "$marker.new" || return 1
  chmod 600 "$marker.new" || return 1
  sync || return 1
  mv -f "$marker.new" "$marker" || return 1
  sync || return 1

  stop hapaneld_helper 2>/dev/null
  stop hapaneld_ledd 2>/dev/null
  pkill -x hapaneld-helper 2>/dev/null
  pkill -x hapaneld-ledd 2>/dev/null
  rm -f /system/etc/init/hapaneld-helper.rc /system/bin/hapaneld-helper \
    /system/bin/hapaneld-ledd /system/etc/init/hapaneld-ledd.rc \
    /data/adb/service.d/hapaneld-helper.sh || return 1
  [ ! -e /system/etc/init/hapaneld-helper.rc ] && [ ! -e /system/bin/hapaneld-helper ] && \
    [ ! -e /system/bin/hapaneld-ledd ] && [ ! -e /system/etc/init/hapaneld-ledd.rc ] && \
    [ ! -e /data/adb/service.d/hapaneld-helper.sh ] || return 1
  mv -f /data/adb/hapaneld/hapaneld-helper.new /data/adb/hapaneld/hapaneld-helper || return 1
  mv -f /vendor/etc/init/hapaneld-helper.rc.new /vendor/etc/init/hapaneld-helper.rc || return 1
  sync || return 1
  echo INSTALL_OK
}

rollback_system() {
  mount -o rw,remount / 2>/dev/null
  mount -o rw,remount /system 2>/dev/null
  marker=/system/bin/.hapaneld-helper-upgrade
  [ -f "$marker" ] || { echo ROLLBACK_UNNEEDED; return 0; }
  grep -q ^JOURNAL_VERSION=1$ "$marker" || return 1
  grep -q ^JOURNAL_SCOPE=APK_HELPER$ "$marker" || return 1
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  state=$(classify_system)
  [ "$state" = PRE_SWAP ] || [ "$state" = TARGET ] || return 1
  flag OLD_BIN "$marker" && valid_recovery OLD_BIN /system/bin/hapaneld-helper.hapaneld-recovery "$marker" || ! flag OLD_BIN "$marker" || return 1
  flag OLD_SERVICE "$marker" && valid_recovery OLD_SERVICE /system/etc/init/hapaneld-helper.rc.hapaneld-recovery "$marker" || ! flag OLD_SERVICE "$marker" || return 1
  flag LEGACY_BIN "$marker" && valid_recovery LEGACY_BIN /system/bin/hapaneld-ledd.hapaneld-recovery "$marker" || ! flag LEGACY_BIN "$marker" || return 1
  flag LEGACY_SERVICE "$marker" && valid_recovery LEGACY_SERVICE /system/etc/init/hapaneld-ledd.rc.hapaneld-recovery "$marker" || ! flag LEGACY_SERVICE "$marker" || return 1
  flag ALT_BIN "$marker" && valid_recovery ALT_BIN /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery "$marker" || ! flag ALT_BIN "$marker" || return 1
  flag ALT_SERVICE "$marker" && valid_recovery ALT_SERVICE /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery "$marker" || ! flag ALT_SERVICE "$marker" || return 1

  probe=/data/adb/hapaneld/.helper-probe-$transaction_id
  rm -f "$probe"
  hash_matches @BIN_SHA256@ @STAGED_HELPER@ || return 1
  cp @STAGED_HELPER@ "$probe" || return 1
  chown 0:0 "$probe" || return 1
  chmod 700 "$probe" || return 1
  hash_matches @BIN_SHA256@ "$probe" || return 1

  stop hapaneld_helper 2>/dev/null
  stop hapaneld_ledd 2>/dev/null
  pkill -x hapaneld-helper 2>/dev/null
  pkill -x hapaneld-ledd 2>/dev/null
  restore_or_remove OLD_BIN /system/bin/hapaneld-helper.hapaneld-recovery /system/bin/hapaneld-helper 755 "$marker" || return 1
  restore_or_remove OLD_SERVICE /system/etc/init/hapaneld-helper.rc.hapaneld-recovery /system/etc/init/hapaneld-helper.rc 644 "$marker" || return 1
  restore_or_remove LEGACY_BIN /system/bin/hapaneld-ledd.hapaneld-recovery /system/bin/hapaneld-ledd 755 "$marker" || return 1
  restore_or_remove LEGACY_SERVICE /system/etc/init/hapaneld-ledd.rc.hapaneld-recovery /system/etc/init/hapaneld-ledd.rc 644 "$marker" || return 1
  restore_or_remove ALT_BIN /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery /data/adb/hapaneld/hapaneld-helper 755 "$marker" || return 1
  restore_or_remove ALT_SERVICE /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery /data/adb/service.d/hapaneld-helper.sh 755 "$marker" || return 1
  sync || return 1

  if [ -x /system/bin/hapaneld-helper ] && [ -f /system/bin/hapaneld-helper.hapaneld-recovery ]; then
    start hapaneld_helper 2>/dev/null || /system/bin/hapaneld-helper >/dev/null 2>&1 &
    result=ROLLBACK_RESTARTED
  elif [ -x /data/adb/hapaneld/hapaneld-helper ] && [ -f /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery ]; then
    /data/adb/hapaneld/hapaneld-helper >/dev/null 2>&1 &
    result=ROLLBACK_RESTARTED
  elif [ -x /system/bin/hapaneld-ledd ]; then
    start hapaneld_ledd 2>/dev/null || /system/bin/hapaneld-ledd >/dev/null 2>&1 &
    result=ROLLBACK_LEGACY
  else
    result=ROLLBACK_EMPTY
  fi
  echo "$result"
}

rollback_systemless() {
  marker=/data/adb/hapaneld/.helper-upgrade.marker
  [ -f "$marker" ] || { echo ROLLBACK_UNNEEDED; return 0; }
  grep -q ^JOURNAL_VERSION=1$ "$marker" || return 1
  grep -q ^JOURNAL_SCOPE=APK_HELPER$ "$marker" || return 1
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  state=$(classify_systemless)
  [ "$state" = PRE_SWAP ] || [ "$state" = TARGET ] || return 1
  flag OLD_BIN "$marker" && valid_recovery OLD_BIN /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery "$marker" || ! flag OLD_BIN "$marker" || return 1
  flag OLD_SERVICE "$marker" && valid_recovery OLD_SERVICE /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery "$marker" || ! flag OLD_SERVICE "$marker" || return 1
  probe=/data/adb/hapaneld/.helper-probe-$transaction_id
  rm -f "$probe"
  hash_matches @BIN_SHA256@ @STAGED_HELPER@ || return 1
  cp @STAGED_HELPER@ "$probe" || return 1
  chown 0:0 "$probe" || return 1
  chmod 700 "$probe" || return 1
  hash_matches @BIN_SHA256@ "$probe" || return 1
  stop hapaneld_helper 2>/dev/null
  stop hapaneld_ledd 2>/dev/null
  pkill -x hapaneld-helper 2>/dev/null
  pkill -x hapaneld-ledd 2>/dev/null
  restore_or_remove OLD_BIN /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery /data/adb/hapaneld/hapaneld-helper 755 "$marker" || return 1
  restore_or_remove OLD_SERVICE /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery /data/adb/service.d/hapaneld-helper.sh 755 "$marker" || return 1
  sync || return 1
  if [ -x /data/adb/hapaneld/hapaneld-helper ] && [ -f /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery ]; then
    /data/adb/hapaneld/hapaneld-helper >/dev/null 2>&1 &
    result=ROLLBACK_RESTARTED
  elif [ -x /system/bin/hapaneld-helper ]; then
    start hapaneld_helper 2>/dev/null || /system/bin/hapaneld-helper >/dev/null 2>&1 &
    result=ROLLBACK_RESTARTED
  elif [ -x /system/bin/hapaneld-ledd ]; then
    start hapaneld_ledd 2>/dev/null || /system/bin/hapaneld-ledd >/dev/null 2>&1 &
    result=ROLLBACK_LEGACY
  else
    result=ROLLBACK_EMPTY
  fi
  echo "$result"
}

rollback_hybrid() {
  mount -o rw,remount / 2>/dev/null
  mount -o rw,remount /system 2>/dev/null
  mount -o rw,remount /vendor 2>/dev/null
  marker=/data/adb/hapaneld/.helper-hybrid-upgrade.marker
  [ -f "$marker" ] || { echo ROLLBACK_UNNEEDED; return 0; }
  grep -q ^JOURNAL_VERSION=1$ "$marker" || return 1
  grep -q ^JOURNAL_SCOPE=APK_HELPER$ "$marker" || return 1
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  state=$(classify_hybrid)
  [ "$state" = PRE_SWAP ] || [ "$state" = TARGET ] || [ "$state" = TRANSITION ] || return 1
  flag OLD_BIN "$marker" && valid_recovery OLD_BIN /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery "$marker" || ! flag OLD_BIN "$marker" || return 1
  flag OLD_RC "$marker" && valid_recovery OLD_RC /data/adb/hapaneld/hapaneld-helper.vrc.hapaneld-recovery "$marker" || ! flag OLD_RC "$marker" || return 1
  flag SYS_RC "$marker" && valid_recovery SYS_RC /data/adb/hapaneld/hapaneld-helper.sysrc.hapaneld-recovery "$marker" || ! flag SYS_RC "$marker" || return 1
  flag SYS_BIN "$marker" && valid_recovery SYS_BIN /data/adb/hapaneld/hapaneld-helper.sysbin.hapaneld-recovery "$marker" || ! flag SYS_BIN "$marker" || return 1
  flag LEGACY_BIN "$marker" && valid_recovery LEGACY_BIN /data/adb/hapaneld/hapaneld-ledd.sysbin.hapaneld-recovery "$marker" || ! flag LEGACY_BIN "$marker" || return 1
  flag LEGACY_SERVICE "$marker" && valid_recovery LEGACY_SERVICE /data/adb/hapaneld/hapaneld-ledd.rc.hapaneld-recovery "$marker" || ! flag LEGACY_SERVICE "$marker" || return 1
  flag ALT_SERVICE "$marker" && valid_recovery ALT_SERVICE /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery "$marker" || ! flag ALT_SERVICE "$marker" || return 1

  probe=/data/adb/hapaneld/.helper-probe-$transaction_id
  rm -f "$probe"
  hash_matches @BIN_SHA256@ @STAGED_HELPER@ || return 1
  cp @STAGED_HELPER@ "$probe" || return 1
  chown 0:0 "$probe" || return 1
  chmod 700 "$probe" || return 1
  hash_matches @BIN_SHA256@ "$probe" || return 1

  stop hapaneld_helper 2>/dev/null
  stop hapaneld_ledd 2>/dev/null
  pkill -x hapaneld-helper 2>/dev/null
  pkill -x hapaneld-ledd 2>/dev/null
  restore_or_remove OLD_BIN /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery /data/adb/hapaneld/hapaneld-helper 755 "$marker" || return 1
  restore_or_remove OLD_RC /data/adb/hapaneld/hapaneld-helper.vrc.hapaneld-recovery /vendor/etc/init/hapaneld-helper.rc 644 "$marker" || return 1
  restore_or_remove SYS_RC /data/adb/hapaneld/hapaneld-helper.sysrc.hapaneld-recovery /system/etc/init/hapaneld-helper.rc 644 "$marker" || return 1
  restore_or_remove SYS_BIN /data/adb/hapaneld/hapaneld-helper.sysbin.hapaneld-recovery /system/bin/hapaneld-helper 755 "$marker" || return 1
  restore_or_remove LEGACY_BIN /data/adb/hapaneld/hapaneld-ledd.sysbin.hapaneld-recovery /system/bin/hapaneld-ledd 755 "$marker" || return 1
  restore_or_remove LEGACY_SERVICE /data/adb/hapaneld/hapaneld-ledd.rc.hapaneld-recovery /system/etc/init/hapaneld-ledd.rc 644 "$marker" || return 1
  restore_or_remove ALT_SERVICE /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery /data/adb/service.d/hapaneld-helper.sh 755 "$marker" || return 1
  sync || return 1

  if [ -x /data/adb/hapaneld/hapaneld-helper ] && [ -f /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery ]; then
    /data/adb/hapaneld/hapaneld-helper >/dev/null 2>&1 &
    result=ROLLBACK_RESTARTED
  elif [ -x /system/bin/hapaneld-helper ]; then
    start hapaneld_helper 2>/dev/null || /system/bin/hapaneld-helper >/dev/null 2>&1 &
    result=ROLLBACK_RESTARTED
  elif [ -x /system/bin/hapaneld-ledd ]; then
    start hapaneld_ledd 2>/dev/null || /system/bin/hapaneld-ledd >/dev/null 2>&1 &
    result=ROLLBACK_LEGACY
  else
    result=ROLLBACK_EMPTY
  fi
  echo "$result"
}

finalize_rollback_system() {
  marker=/system/bin/.hapaneld-helper-upgrade
  mount -o rw,remount / 2>/dev/null
  mount -o rw,remount /system 2>/dev/null
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  [ "$(classify_system)" = PRE_SWAP ] || return 1
  rm -f "$marker" || return 1
  sync || return 1
  rm -f /system/bin/hapaneld-helper.hapaneld-recovery \
    /system/etc/init/hapaneld-helper.rc.hapaneld-recovery \
    /system/bin/hapaneld-ledd.hapaneld-recovery \
    /system/etc/init/hapaneld-ledd.rc.hapaneld-recovery \
    /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery \
    /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery 2>/dev/null || true
  sync 2>/dev/null || true
  echo ROLLBACK_FINALIZED
}

finalize_rollback_systemless() {
  marker=/data/adb/hapaneld/.helper-upgrade.marker
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  [ "$(classify_systemless)" = PRE_SWAP ] || return 1
  rm -f "$marker" || return 1
  sync || return 1
  rm -f /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery \
    /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery 2>/dev/null || true
  sync 2>/dev/null || true
  echo ROLLBACK_FINALIZED
}

finalize_rollback_hybrid() {
  marker=/data/adb/hapaneld/.helper-hybrid-upgrade.marker
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  # Do not use classify_hybrid here: a no-op helper update can be both TARGET and the exact
  # journaled pre-swap state. Finalization is safe only after the latter is proved directly.
  hybrid_matches_recorded || return 1
  rm -f "$marker" || return 1
  sync || return 1
  rm -f /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery \
    /data/adb/hapaneld/hapaneld-helper.vrc.hapaneld-recovery \
    /data/adb/hapaneld/hapaneld-helper.sysrc.hapaneld-recovery \
    /data/adb/hapaneld/hapaneld-helper.sysbin.hapaneld-recovery \
    /data/adb/hapaneld/hapaneld-ledd.sysbin.hapaneld-recovery \
    /data/adb/hapaneld/hapaneld-ledd.rc.hapaneld-recovery \
    /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery 2>/dev/null || true
  sync 2>/dev/null || true
  echo ROLLBACK_FINALIZED
}

commit_system() {
  marker=/system/bin/.hapaneld-helper-upgrade
  mount -o rw,remount / 2>/dev/null
  mount -o rw,remount /system 2>/dev/null
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  [ "$(classify_system)" = TARGET ] || return 1
  rm -f "$marker" || return 1
  sync || return 1
  rm -f /system/bin/hapaneld-helper.hapaneld-recovery \
    /system/etc/init/hapaneld-helper.rc.hapaneld-recovery \
    /system/bin/hapaneld-ledd.hapaneld-recovery \
    /system/etc/init/hapaneld-ledd.rc.hapaneld-recovery \
    /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery \
    /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery 2>/dev/null || true
  sync 2>/dev/null || true
  echo COMMIT_OK
}

commit_systemless() {
  marker=/data/adb/hapaneld/.helper-upgrade.marker
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  [ "$(classify_systemless)" = TARGET ] || return 1
  rm -f "$marker" || return 1
  sync || return 1
  rm -f /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery \
    /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery 2>/dev/null || true
  sync 2>/dev/null || true
  echo COMMIT_OK
}

commit_hybrid() {
  marker=/data/adb/hapaneld/.helper-hybrid-upgrade.marker
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  [ "$(classify_hybrid)" = TARGET ] || return 1
  rm -f "$marker" || return 1
  sync || return 1
  rm -f /data/adb/hapaneld/hapaneld-helper.hapaneld-recovery \
    /data/adb/hapaneld/hapaneld-helper.vrc.hapaneld-recovery \
    /data/adb/hapaneld/hapaneld-helper.sysrc.hapaneld-recovery \
    /data/adb/hapaneld/hapaneld-helper.sysbin.hapaneld-recovery \
    /data/adb/hapaneld/hapaneld-ledd.sysbin.hapaneld-recovery \
    /data/adb/hapaneld/hapaneld-ledd.rc.hapaneld-recovery \
    /data/adb/service.d/hapaneld-helper.sh.hapaneld-recovery 2>/dev/null || true
  sync 2>/dev/null || true
  echo COMMIT_OK
}

discover_system() {
  cat /system/bin/.hapaneld-helper-upgrade
  echo LIVE_STATE=$(classify_system)
}

discover_systemless() {
  cat /data/adb/hapaneld/.helper-upgrade.marker
  echo LIVE_STATE=$(classify_systemless)
}

discover_hybrid() {
  cat /data/adb/hapaneld/.helper-hybrid-upgrade.marker
  echo LIVE_STATE=$(classify_hybrid)
}

status_system() {
  marker=/system/bin/.hapaneld-helper-upgrade
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  discover_system
}

status_systemless() {
  marker=/data/adb/hapaneld/.helper-upgrade.marker
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  discover_systemless
}

status_hybrid() {
  marker=/data/adb/hapaneld/.helper-hybrid-upgrade.marker
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  discover_hybrid
}

lease_system() {
  marker=/system/bin/.hapaneld-helper-upgrade
  mount -o rw,remount / 2>/dev/null
  mount -o rw,remount /system 2>/dev/null
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  refresh_lease "$marker" || return 1
  echo LEASE_OK
}

lease_systemless() {
  marker=/data/adb/hapaneld/.helper-upgrade.marker
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  refresh_lease "$marker" || return 1
  echo LEASE_OK
}

lease_hybrid() {
  marker=/data/adb/hapaneld/.helper-hybrid-upgrade.marker
  valid_transaction_identity "$marker" "$transaction_id" "$target_apk" "$target_build" "$target_helper" || return 1
  refresh_lease "$marker" || return 1
  echo LEASE_OK
}

acquire_helper_lock || { echo TRANSACTION_BUSY; exit 75; }

transaction_id=${2:-}
target_apk=${3:-}
target_build=${4:-}
target_helper=${5:-}

case "${1:-}" in
  install-system) install_system ;;
  install-systemless) install_systemless ;;
  install-hybrid) install_hybrid ;;
  rollback-system) rollback_system ;;
  rollback-systemless) rollback_systemless ;;
  rollback-hybrid) rollback_hybrid ;;
  finalize-rollback-system) finalize_rollback_system ;;
  finalize-rollback-systemless) finalize_rollback_systemless ;;
  finalize-rollback-hybrid) finalize_rollback_hybrid ;;
  commit-system) commit_system ;;
  commit-systemless) commit_systemless ;;
  commit-hybrid) commit_hybrid ;;
  discover-system) discover_system ;;
  discover-systemless) discover_systemless ;;
  discover-hybrid) discover_hybrid ;;
  status-system) status_system ;;
  status-systemless) status_systemless ;;
  status-hybrid) status_hybrid ;;
  lease-system) lease_system ;;
  lease-systemless) lease_systemless ;;
  lease-hybrid) lease_hybrid ;;
  *) exit 2 ;;
esac
EOF
  bin_sha256="$(host_sha256 "$helper")"
  rc_sha256="$(host_sha256 "$rc_file")"
  hybrid_rc_sha256="$(host_sha256 "$hybrid_rc_file")"
  service_sha256="$(host_sha256 "$service_file")"
  sed -e "s/@BIN_SHA256@/$bin_sha256/g" \
      -e "s/@RC_SHA256@/$rc_sha256/g" \
      -e "s/@HYBRID_RC_SHA256@/$hybrid_rc_sha256/g" \
      -e "s/@SERVICE_SHA256@/$service_sha256/g" \
      -e "s/@APK_SHA256@/$TARGET_APK_SHA256/g" \
      -e "s/@BUILD_ID@/$expected_build_id/g" \
      -e "s/@TRANSACTION_ID@/$ROOT_HELPER_TRANSACTION_ID/g" \
      -e "s|@STAGED_HELPER@|$ROOT_HELPER_STAGED_HELPER|g" \
      -e "s|@STAGED_RC@|$ROOT_HELPER_STAGED_RC|g" \
      -e "s|@STAGED_HYBRID_RC@|$ROOT_HELPER_STAGED_HYBRID_RC|g" \
      -e "s|@STAGED_SERVICE@|$ROOT_HELPER_STAGED_SERVICE|g" \
      "$transaction_file" > "$transaction_file.ready"
  mv "$transaction_file.ready" "$transaction_file"
  transaction_sha256="$(host_sha256 "$transaction_file")"
  ROOT_HELPER_TARGET_SHA256="$bin_sha256"
  ROOT_HELPER_TRANSACTION_SHA256="$transaction_sha256"
  ROOT_HELPER_TRANSACTION_PATH="/data/adb/hapaneld/.helper-transaction-$ROOT_HELPER_TRANSACTION_ID-$transaction_sha256"

  if run_root '[ ! -f /system/bin/.hapaneld-helper-manual-upgrade ] && [ ! -f /data/adb/hapaneld/.helper-manual-upgrade.marker ]' \
      >/dev/null 2>&1; then
    :
  else
    [ -z "$helper_dir" ] || rm -rf "$helper_dir"
    rm -f "$rc_file" "$hybrid_rc_file" "$service_file" "$transaction_file"
    fail "an incomplete standalone root-helper installation must be recovered first" \
      "Re-run ./helper/install-daemon.sh $TARGET $abi from the same checkout; it will verify and recover its separate journal." \
      "Then re-run this provisioning command. No APK or helper files were changed by this attempt."
  fi

  # A managed hybrid boot vector makes the layout sticky: updates keep using /vendor + /data instead
  # of attempting an implicit migration back to /system. Unknown content at the reserved vendor path
  # is never overwritten. Capacity must be an unambiguous numeric value before selecting /system.
  out="$(run_root '
    expected='"$hybrid_rc_sha256"'
    vendor_rc=/vendor/etc/init/hapaneld-helper.rc
    if [ -e $vendor_rc ]; then
      owner=$(stat -c %u:%g $vendor_rc 2>/dev/null || toybox stat -c %u:%g $vendor_rc 2>/dev/null) || owner=
      actual=$(sha256sum $vendor_rc 2>/dev/null || toybox sha256sum $vendor_rc 2>/dev/null) || actual=
      if [ "$owner" = 0:0 ] && [ "${actual%% *}" = "$expected" ]; then
        echo VENDOR_RC_MANAGED
      else
        echo VENDOR_RC_UNEXPECTED
      fi
    fi
    mount -o rw,remount / 2>/dev/null; mount -o rw,remount /system 2>/dev/null
    if touch /system/.rw_probe 2>/dev/null && rm /system/.rw_probe 2>/dev/null; then
      echo SYSTEM_RW
      # HAPANELD_CAPACITY_PROBE_BEGIN
      capacity_output=
      if command -v df >/dev/null 2>&1 && command -v sed >/dev/null 2>&1; then
        capacity_output=$(df -P -k /system 2>/dev/null) || capacity_output=
      fi
      capacity_row=
      capacity_extra=unknown
      if [ -n "$capacity_output" ]; then
        capacity_row=$(printf "%s\n" "$capacity_output" | sed -n "2p") || capacity_row=
        capacity_extra=$(printf "%s\n" "$capacity_output" | sed -n "3,\$p") || capacity_extra=unknown
      fi
      available=
      if [ -n "$capacity_row" ] && [ -z "$capacity_extra" ]; then
        set -f
        set -- $capacity_row
        set +f
        if [ "$#" -eq 6 ]; then
          case "$4" in
            ""|*[!0-9]*) ;;
            *) available=$4 ;;
          esac
        fi
      fi
      if [ -n "$available" ]; then echo SYSTEM_AVAIL_KB=$available; else echo SYSTEM_CAPACITY_UNKNOWN; fi
      # HAPANELD_CAPACITY_PROBE_END
    else
      echo SYSTEM_RO
      if command -v magisk >/dev/null 2>&1 || [ -x /data/adb/magisk/busybox ] || [ -x /data/adb/ksu/bin/busybox ] || [ -x /data/adb/ap/bin/busybox ]; then
        echo SYSTEMLESS_RUNNER
      else
        echo NO_SYSTEMLESS_RUNNER
      fi
    fi
  ' 2>&1)" || true

  if printf '%s\n' "$out" | grep -qx VENDOR_RC_UNEXPECTED; then
    [ -z "$helper_dir" ] || rm -rf "$helper_dir"
    rm -f "$rc_file" "$hybrid_rc_file" "$service_file" "$transaction_file"
    fail "unexpected existing /vendor/etc/init/hapaneld-helper.rc" \
      "The file was not created by this provisioner, so it was left unchanged. No helper or APK was installed."
  fi

  if printf '%s\n' "$out" | grep -qx VENDOR_RC_MANAGED; then
    install_kind=hybrid
  elif printf '%s\n' "$out" | grep -qx SYSTEM_RW; then
    system_avail_kb="$(printf '%s\n' "$out" | sed -n 's/^SYSTEM_AVAIL_KB=//p')"
    if [ "$(printf '%s\n' "$system_avail_kb" | grep -Ec '^[0-9]+$')" -ne 1 ]; then
      [ -z "$helper_dir" ] || rm -rf "$helper_dir"
      rm -f "$rc_file" "$hybrid_rc_file" "$service_file" "$transaction_file"
      fail "system capacity could not be determined safely" \
        "The helper and APK were left unchanged. Check the panel's df output, then re-run."
    fi
    system_need_kb=$(( ( $(wc -c < "$helper") * 2 + 1023 ) / 1024 + 64 ))
    [ "$system_need_kb" -ge 1024 ] || system_need_kb=1024
    if [ "$system_avail_kb" -lt "$system_need_kb" ]; then
      install_kind=hybrid
    else
      install_kind=system
    fi
  elif printf '%s\n' "$out" | grep -qx SYSTEMLESS_RUNNER; then
    install_kind=systemless
  else
    [ -z "$helper_dir" ] || rm -rf "$helper_dir"
    rm -f "$rc_file" "$hybrid_rc_file" "$service_file" "$transaction_file"
    fail "the panel has read-only /system and no verified systemless boot-service runner" \
      "The helper was not installed and the previous APK was left in place." \
      "Install a supported Magisk, KernelSU, or APatch service.d environment, or use firmware with a writable /system init path, then re-run."
  fi

  if [ "$install_kind" = hybrid ]; then
    vendor_probe="$(run_root '
      mount -o rw,remount /vendor 2>/dev/null
      probe=/vendor/etc/init/.hapaneld-rw-probe-'"$ROOT_HELPER_TRANSACTION_ID"'
      rm -f $probe
      if printf hapaneld-vendor-write-probe > $probe 2>/dev/null && [ -s $probe ] && rm $probe 2>/dev/null; then
        echo VENDOR_INIT_RW
      else
        rm -f $probe 2>/dev/null
      fi
    ' 2>&1)" || true
    if ! printf '%s\n' "$vendor_probe" | grep -qx VENDOR_INIT_RW; then
      [ -z "$helper_dir" ] || rm -rf "$helper_dir"
      rm -f "$rc_file" "$hybrid_rc_file" "$service_file" "$transaction_file"
      fail "/vendor/etc/init is not writable for the hybrid root helper" \
        "The helper and APK were left unchanged. Restore vendor-partition writability, then re-run."
    fi
    if [ -n "$system_avail_kb" ]; then
      echo "   ${YEL}ℹ${X} /system has ${system_avail_kb}KB free; ${system_need_kb}KB is required for a transactional helper upgrade"
    else
      echo "   ${YEL}ℹ${X} keeping the existing hybrid root-helper layout"
    fi
    echo "     ${D}init service in /vendor/etc/init; sealed helper in /data/adb/hapaneld${X}"
  fi

  step "🧰 root helper" "${D}installing or upgrading $abi privileged service${X}"
  adb -s "$TARGET" push "$helper" "$ROOT_HELPER_STAGED_HELPER" >/dev/null
  adb -s "$TARGET" push "$rc_file" "$ROOT_HELPER_STAGED_RC" >/dev/null
  adb -s "$TARGET" push "$hybrid_rc_file" "$ROOT_HELPER_STAGED_HYBRID_RC" >/dev/null
  adb -s "$TARGET" push "$service_file" "$ROOT_HELPER_STAGED_SERVICE" >/dev/null
  adb -s "$TARGET" push "$transaction_file" "$ROOT_HELPER_STAGED_TRANSACTION" >/dev/null
  rm -f "$rc_file" "$hybrid_rc_file" "$service_file" "$transaction_file"
  transaction_ready="$(run_root 'mkdir -p /data/adb/hapaneld || exit 1
    chown 0:0 /data/adb/hapaneld || exit 1
    chmod 700 /data/adb/hapaneld || exit 1
    expected='"$ROOT_HELPER_TRANSACTION_SHA256"'
    source='"$ROOT_HELPER_STAGED_TRANSACTION"'
    destination='"$ROOT_HELPER_TRANSACTION_PATH"'
    source_hash=$(sha256sum $source 2>/dev/null || toybox sha256sum $source 2>/dev/null) || exit 1
    [ ${source_hash%% *} = $expected ] || exit 1
    cp $source $destination.new || exit 1
    chown 0:0 $destination.new || exit 1
    chmod 700 $destination.new || exit 1
    destination_hash=$(sha256sum $destination.new 2>/dev/null || toybox sha256sum $destination.new 2>/dev/null) || exit 1
    [ ${destination_hash%% *} = $expected ] || exit 1
    mv -f $destination.new $destination || exit 1
    sync || exit 1
    echo TRANSACTION_READY' 2>&1)" || true
  if ! printf '%s\n' "$transaction_ready" | grep -qx TRANSACTION_READY; then
    run_root 'rm -f '"$ROOT_HELPER_STAGED_HELPER $ROOT_HELPER_STAGED_RC $ROOT_HELPER_STAGED_HYBRID_RC $ROOT_HELPER_STAGED_SERVICE $ROOT_HELPER_STAGED_TRANSACTION $ROOT_HELPER_TRANSACTION_PATH"'.new' \
      >/dev/null 2>&1 || true
    [ -z "$helper_dir" ] || rm -rf "$helper_dir"
    fail "the root-helper transaction could not be promoted into protected storage" \
      "No privileged transaction script was executed and the APK was not replaced."
  fi

  case "$install_kind" in
    system)
      out2="$(run_root_helper_transaction install-system 2>&1)" || true
      resolve_root_helper_install_state "$install_kind"
      if ! printf '%s\n' "$out2" | grep -qx INSTALL_OK; then
        if rollback_root_helper "$install_kind"; then
          fail "/system root-helper install failed; the prior helper was preserved or restored" \
            "The previous APK and helper remain active. Re-run after checking writable-system capacity and permissions."
        fi
        fail "/system root-helper install failed and rollback could not be verified" \
          "The APK was not replaced. Restore the helper manually before relying on privileged operations."
      fi
      run_root '
        stop hapaneld_ledd 2>/dev/null; stop hapaneld_helper 2>/dev/null
        pkill -x hapaneld-ledd 2>/dev/null; pkill -x hapaneld-helper 2>/dev/null
        start hapaneld_helper 2>/dev/null
        /system/bin/hapaneld-helper --request PING >/dev/null 2>&1 ||
          ( /system/bin/hapaneld-helper >/dev/null 2>&1 & )
      ' >/dev/null 2>&1 || true
      ;;
    hybrid)
      out2="$(run_root_helper_transaction install-hybrid 2>&1)" || true
      resolve_root_helper_install_state "$install_kind"
      if ! printf '%s\n' "$out2" | grep -qx INSTALL_OK; then
        if rollback_root_helper "$install_kind"; then
          fail "hybrid root-helper install failed; the prior helper was preserved or restored" \
            "The previous APK and helper remain active. Re-run after checking /vendor/etc/init and /data/adb."
        fi
        fail "hybrid root-helper install failed and rollback could not be verified" \
          "The APK was not replaced. Restore the helper manually before relying on privileged operations."
      fi
      # A newly written init definition is loaded on the next boot. Launch the exact data helper for
      # this session instead of trusting `start`, which can report success for an unknown service.
      run_root '
        stop hapaneld_ledd 2>/dev/null; stop hapaneld_helper 2>/dev/null
        pkill -x hapaneld-ledd 2>/dev/null; pkill -x hapaneld-helper 2>/dev/null
        /data/adb/hapaneld/hapaneld-helper >/dev/null 2>&1 &
      ' >/dev/null 2>&1 || true
      ;;
    systemless)
      out2="$(run_root_helper_transaction install-systemless 2>&1)" || true
      resolve_root_helper_install_state "$install_kind"
      if ! printf '%s\n' "$out2" | grep -qx INSTALL_OK; then
        if rollback_root_helper "$install_kind"; then
          fail "systemless root-helper install failed; the prior helper was preserved or restored" \
            "The previous APK and helper remain active. Re-run after checking /data capacity and service.d permissions."
        fi
        fail "systemless root-helper install failed and rollback could not be verified" \
          "The APK was not replaced. Restore the helper manually before relying on privileged operations."
      fi
      run_root '
        stop hapaneld_helper 2>/dev/null
        pkill -x hapaneld-helper 2>/dev/null
        /data/adb/hapaneld/hapaneld-helper >/dev/null 2>&1 &
      ' >/dev/null 2>&1 || true
      ;;
  esac

  if ! wait_for_helper_reply COMPANIONCAPS "COMPANIONCAPS 1 BACKUP RESTORE STATUS JOURNAL" "$install_kind"; then
    if rollback_root_helper "$install_kind"; then
      [ -z "$helper_dir" ] || rm -rf "$helper_dir"
      fail "new root helper failed its capability check; the prior helper was restored" \
        "The previous APK and helper remain active. Re-run after checking the helper logs."
    fi
    [ -z "$helper_dir" ] || rm -rf "$helper_dir"
    fail "new root helper failed its capability check and rollback could not be verified" \
      "The APK was not replaced. Restore the helper manually before relying on privileged operations."
  fi
  if ! wait_for_helper_reply BUILDID "BUILDID $expected_build_id" "$install_kind"; then
    if rollback_root_helper "$install_kind"; then
      [ -z "$helper_dir" ] || rm -rf "$helper_dir"
      fail "new root helper failed its exact build-identity check; the prior helper was restored" \
        "The previous APK and helper remain active. Rebuild or re-download the matching helper, then retry."
    fi
    [ -z "$helper_dir" ] || rm -rf "$helper_dir"
    fail "new root helper failed its exact build-identity check and rollback could not be verified" \
      "The APK was not replaced. Restore the helper manually before relying on privileged operations."
  fi
  renew_root_helper_lease "$install_kind" || fail "the root-helper transaction lease could not be renewed after validation" \
    "The APK was not replaced. Re-run after any competing provisioner finishes; this journal remains recoverable."
  ROOT_HELPER_TRANSACTION_KIND="$install_kind"
  [ -z "$helper_dir" ] || rm -rf "$helper_dir"
  echo "   ${GRN}✓${X} root helper running with Companion-data protocol 1; recovery retained until APK installation succeeds"
}

# Give a useful version transition before install. Android's package manager remains the portable,
# authoritative downgrade guard; avoiding GNU `sort -V` keeps the supported macOS path working.
version_guard() {
  [ "$FORCE" = 1 ] && return 0
  [ -n "$TOINSTALL_VER" ] || return 0
  local installed raw status timeout="${VERSION_INSPECTION_TIMEOUT_SECONDS:-20}"
  case "$timeout" in ''|*[!0-9]*|0) timeout=20 ;; esac
  step "🔎 inspecting version" "${D}reading the installed ha-paneld package${X}"
  if raw="$(run_with_deadline "$timeout" "$ADB_COMMAND" -s "$TARGET" shell dumpsys package "$PKG" 2>/dev/null)"; then
    installed="$(printf '%s\n' "$raw" | grep -m1 versionName | sed 's/.*versionName=//' | tr -d '\r ' || true)"
  else
    status=$?
    if [ "$status" -eq 124 ] || [ "$status" -eq 137 ]; then
      fail "installed-version inspection timed out after ${timeout}s" \
        "Android's package manager or adb is not responding. Nothing was installed or privileged." \
        "Restore adb/package-manager responsiveness, then re-run the same command."
    fi
    warn "could not read the installed ha-paneld version; Android will still enforce install compatibility"
    return 0
  fi
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
  local destination="$1" directory basename temporary http_status
  directory="$(dirname "$destination")"
  basename="$(basename "$destination")"
  [ -d "$directory" ] || fail "config export destination directory does not exist" \
    "Create $directory first, then run the same --export command again."
  [ ! -L "$destination" ] || fail "refusing to replace a symlink as a secret config export destination" \
    "Choose a regular file path owned by you, then run the same --export command again."
  temporary="$(mktemp "$directory/.${basename}.partial.XXXXXX")" || fail "could not create a secure config export file" \
    "Check that $directory is writable, then run the same --export command again."
  step "📦 exporting config" "${D}→ $destination (includes secrets — protect it)${X}"
  if ! http_status="$(curl -fsS --max-time 30 "$URL/api/v1/config/export?include_secrets=1" \
      -o "$temporary" -w '%{http_code}')"; then
    rm -f "$temporary"
    fail "config export failed; the panel was not changed" \
      "Confirm $URL opens from this computer, then run the same --export command again."
  fi
  if [ "$http_status" = 202 ] && approval_required_response "$temporary"; then
    rm -f "$temporary"
    fail "config export requires approval on the panel; the panel was not changed" \
      "On the panel, open Configure → toolbar overflow → Security mode → Review approvals, approve the config export, then retry the identical --export command from this computer within ten minutes."
  fi
  if [ "$http_status" != 200 ]; then
    rm -f "$temporary"
    fail "config export returned unexpected HTTP $http_status; no config file was saved" \
      "Confirm the panel is running the expected ha-paneld build, then retry the same --export command."
  fi
  if [ ! -s "$temporary" ]; then
    rm -f "$temporary"
    fail "config export was empty; the panel was not changed" \
      "Retry the export. For uninstall recovery, separately create and verify a complete .hpb from the panel's Install page."
  fi
  chmod 600 "$temporary" 2>/dev/null || true
  [ ! -L "$destination" ] || { rm -f "$temporary"; fail "config export destination became a symlink during export" \
    "Choose a regular file path in a directory only you can write, then retry."; }
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

# A config export must happen before any install or configuration mutation. With no other requested
# operation, --export is deliberately read-only and exits here.
if [ -n "$EXPORT_FILE" ]; then export_config "$EXPORT_FILE"; fi
if [ "$VERIFY_ONLY" = 1 ]; then
  show_provisioning_plan 0 "${D}reading installed guidance${X}"
  verify
  exit $?
fi
if export_is_only_operation; then
  echo "${GRN}${B}✅ config export complete${X} — no app, setting, or panel state was changed."
  exit 0
fi

resolve_apk

verify_release_apk

version_guard

TARGET_APK_SHA256="$(host_sha256 "$APK")"
printf '%s\n' "$TARGET_APK_SHA256" | grep -Eq '^[0-9a-f]{64}$' || fail "could not identify the target APK bytes" \
  "The helper and APK transaction was not started. Check that the APK is readable, then retry."

# Current app releases and the root helper are one compatibility unit. On every panel where a root
# path is available, install or upgrade the matching helper before replacing the APK. A helper
# failure therefore leaves the previous app/helper pair running rather than stranding new features.
install_root_helper
if [ "$SHIZUKU" = 1 ] && [ -n "$ROOT_HELPER_TRANSACTION_KIND" ]; then
  start_root_helper_lease_guard
fi

# Try with -g (grant-all) first. Retry without it only for the exact pre-transaction diagnostic that
# an older package manager does not recognize the flag; every other failure may follow a committed
# package-manager request and must be reconciled before any replay.
install_apk() {
  local out helper_recovery="" outcome install_status
  if [ -n "$ROOT_HELPER_TRANSACTION_KIND" ] && ! renew_root_helper_lease "$ROOT_HELPER_TRANSACTION_KIND"; then
    fail "the root-helper transaction is no longer owned by this provisioning run" \
      "The APK was not replaced and no other transaction was committed or rolled back." \
      "Wait for the owning provisioner to finish or its ten-minute lease to expire, then re-run."
  fi
  start_root_helper_lease_guard
  if run_adb_install_attempt -r -g; then install_status=0; else install_status=$?; fi
  out="$ADB_INSTALL_OUTPUT"
  if [ "$install_status" -ne 0 ] && printf '%s\n' "$out" | grep -Eqi '(^|[[:space:]])(Error:[[:space:]]*)?Unknown option:? -g([[:space:]]|$)'; then
    if run_adb_install_attempt -r; then install_status=0; else install_status=$?; fi
    out="$ADB_INSTALL_OUTPUT"
  fi
  stop_root_helper_lease_guard
  if ! lease_guard_succeeded; then
    cleanup_root_helper_lease_guard
    fail "the root-helper transaction lease was lost while Android installed the APK" \
      "The package-manager outcome will be reconciled from exact APK bytes on the next run; no foreign journal was changed."
  fi
  cleanup_root_helper_lease_guard
  [ "$install_status" -ne 0 ] || return 0
  if [ -n "$ROOT_HELPER_TRANSACTION_KIND" ]; then
    if installed_apk_matches_hash "$TARGET_APK_SHA256"; then
      warn "adb install returned an error, but the exact target APK bytes are installed; completing the helper transaction"
      return 0
    else
      outcome=$?
    fi
    if [ "$outcome" -eq 2 ]; then
      printf '%s\n' "$out" | sanitize_terminal | sed 's/^/   /' >&2
      fail "adb install outcome is ambiguous; helper recovery was retained without rollback" \
        "The transport failed before the installed APK bytes could be verified." \
        "Restore adb connectivity and re-run the same command; it will reconcile the APK/helper journal safely."
    fi
    if rollback_root_helper "$ROOT_HELPER_TRANSACTION_KIND"; then
      helper_recovery="The prior root helper was restored and verified; the previous APK remains installed."
    else
      helper_recovery="The previous APK remains installed, but root-helper rollback could not be verified; restore the prior helper manually."
    fi
    ROOT_HELPER_TRANSACTION_KIND=""
  fi
  printf '%s\n' "$out" | sanitize_terminal | sed 's/^/   /' >&2
  case "$out" in
    *ADB_INSTALL_DEADLINE_EXCEEDED*)
      fail "install did not finish within the ${APK_INSTALL_TIMEOUT_SECONDS}s safety deadline" \
        "$helper_recovery" \
        "Android's package manager outcome may be delayed. Restore adb connectivity if needed, then re-run the same command; provisioning will reconcile the installed APK before changing the helper journal." ;;
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*|*"signatures do not match"*)
      fail "install failed: signature mismatch — the ha-paneld already on the panel was signed with a different key (e.g. a local debug build vs a GitHub release)" \
        "$helper_recovery" \
        "1. For complete recovery, open the panel's :8888 page → Install → Backup and verify the downloaded .hpb file is non-empty. --export saves settings only, not profiles, learned entity state, or Companion login." \
        "2. adb -s $TARGET uninstall $PKG    (removes the app AND its on-panel config)" \
        "3. Re-run this command, then restore the .hpb from Install → Restore. Do not pass an .hpb to --restore; that CLI option accepts config JSON only." ;;
    *INSTALL_FAILED_VERSION_DOWNGRADE*)
      fail "install failed: the panel already runs a NEWER version than this APK" \
        "$helper_recovery" \
        "Install the newest release instead (--latest). Before uninstalling to force an older build, make and verify a complete .hpb backup from :8888 → Install → Backup; uninstalling loses on-panel state." ;;
    *INSTALL_FAILED_INSUFFICIENT_STORAGE*)
      fail "install failed: the panel is out of storage" \
        "$helper_recovery" \
        "Free space on the panel (Settings → Storage; or clear app caches: adb -s $TARGET shell pm trim-caches 999G), then re-run." ;;
    *)
      fail "adb install failed (output above)" \
        "$helper_recovery" \
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
  SHIZUKU_INSPECT_TIMEOUT_SECONDS="${SHIZUKU_INSPECT_TIMEOUT_SECONDS:-20}"
  case "$SHIZUKU_INSPECT_TIMEOUT_SECONDS" in
    ''|*[!0-9]*|0) SHIZUKU_INSPECT_TIMEOUT_SECONDS=20 ;;
  esac
  step "🪄 Shizuku" "${D}inspecting the installed manager and signer${X}"
  if SHIZUKU_INSPECT_OUTPUT="$(run_with_deadline "$SHIZUKU_INSPECT_TIMEOUT_SECONDS" \
      "$ADB_COMMAND" -s "$TARGET" shell dumpsys package "$SHIZUKU_PKG" 2>/dev/null)"; then
    SHIZUKU_CURRENT_CODE="$(printf '%s\n' "$SHIZUKU_INSPECT_OUTPUT" \
      | sed -nE 's/.*versionCode=([0-9]+).*/\1/p' | head -1 | tr -d '\r' || true)"
  else
    SHIZUKU_INSPECT_STATUS=$?
    if [ "$SHIZUKU_INSPECT_STATUS" -eq 124 ] || [ "$SHIZUKU_INSPECT_STATUS" -eq 137 ]; then
      fail "Shizuku manager inspection timed out after ${SHIZUKU_INSPECT_TIMEOUT_SECONDS}s" \
        "The ha-paneld APK was not replaced. Restore adb/package-manager responsiveness, then re-run."
    fi
    SHIZUKU_CURRENT_CODE=""
  fi
  if SHIZUKU_INSPECT_OUTPUT="$(run_with_deadline "$SHIZUKU_INSPECT_TIMEOUT_SECONDS" \
      "$ADB_COMMAND" -s "$TARGET" shell pm path "$SHIZUKU_PKG" 2>/dev/null)"; then
    SHIZUKU_CURRENT_PATH="$(printf '%s\n' "$SHIZUKU_INSPECT_OUTPUT" \
      | sed -n 's/^package://p' | head -1 | tr -d '\r' || true)"
  else
    SHIZUKU_INSPECT_STATUS=$?
    if [ "$SHIZUKU_INSPECT_STATUS" -eq 124 ] || [ "$SHIZUKU_INSPECT_STATUS" -eq 137 ]; then
      fail "Shizuku package-path inspection timed out after ${SHIZUKU_INSPECT_TIMEOUT_SECONDS}s" \
        "The ha-paneld APK was not replaced. Restore adb/package-manager responsiveness, then re-run."
    fi
    SHIZUKU_CURRENT_PATH=""
  fi
  SHIZUKU_CURRENT_TRUSTED=0
  if [ -n "$SHIZUKU_CURRENT_PATH" ]; then
    SHIZUKU_CURRENT_APK="$SHIZUKU_DIR/installed-shizuku.apk"
    if run_with_deadline "$SHIZUKU_INSPECT_TIMEOUT_SECONDS" \
        "$ADB_COMMAND" -s "$TARGET" pull "$SHIZUKU_CURRENT_PATH" "$SHIZUKU_CURRENT_APK" >/dev/null 2>&1; then
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
    else
      SHIZUKU_INSPECT_STATUS=$?
      if [ "$SHIZUKU_INSPECT_STATUS" -eq 124 ] || [ "$SHIZUKU_INSPECT_STATUS" -eq 137 ]; then
        fail "installed Shizuku signer inspection timed out after ${SHIZUKU_INSPECT_TIMEOUT_SECONDS}s" \
          "The ha-paneld APK was not replaced. Restore adb/package-manager responsiveness, then re-run."
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
    SHIZUKU_INSTALL_TIMEOUT_SECONDS="${SHIZUKU_INSTALL_TIMEOUT_SECONDS:-180}"
    case "$SHIZUKU_INSTALL_TIMEOUT_SECONDS" in
      ''|*[!0-9]*|0) SHIZUKU_INSTALL_TIMEOUT_SECONDS=180 ;;
    esac
    if run_with_deadline "$SHIZUKU_INSTALL_TIMEOUT_SECONDS" \
        "$ADB_COMMAND" -s "$TARGET" install -r "$SHIZUKU_APK" >/dev/null; then
      :
    else
      SHIZUKU_INSTALL_STATUS=$?
      if [ "$SHIZUKU_INSTALL_STATUS" -eq 124 ] || [ "$SHIZUKU_INSTALL_STATUS" -eq 137 ]; then
        fail "Shizuku installation timed out after ${SHIZUKU_INSTALL_TIMEOUT_SECONDS}s" \
          "The ha-paneld APK was not replaced. Restore adb/package-manager responsiveness, then re-run."
      fi
      fail "Shizuku installation failed" "Fix the package-manager error above, then re-run this command."
    fi
  fi
  # Launch the authenticated manager, then execute its installed native starter directly. Older
  # instructions ran a mutable start.sh from shared external storage, allowing another process with
  # storage access to replace commands between manager verification and service start.
  adb -s "$TARGET" shell monkey -p "$SHIZUKU_PKG" 1 >/dev/null 2>&1 || true
  SHIZUKU_NATIVE_DIR="$(adb -s "$TARGET" shell dumpsys package "$SHIZUKU_PKG" 2>/dev/null \
    | sed -n 's/^[[:space:]]*nativeLibraryDir=//p' | head -1 | tr -d '\r' || true)"
  case "$SHIZUKU_NATIVE_DIR" in
    /data/app/*|/mnt/expand/*/app/*) ;;
    *) SHIZUKU_NATIVE_DIR="" ;;
  esac
  case "$SHIZUKU_NATIVE_DIR" in *[!0-9A-Za-z._/+,:=@~-]*) SHIZUKU_NATIVE_DIR="" ;; esac
  SHIZUKU_STARTER="${SHIZUKU_NATIVE_DIR:+$SHIZUKU_NATIVE_DIR/libshizuku.so}"
  SHIZUKU_START_TIMEOUT_SECONDS="${SHIZUKU_START_TIMEOUT_SECONDS:-30}"
  case "$SHIZUKU_START_TIMEOUT_SECONDS" in
    ''|*[!0-9]*|0) SHIZUKU_START_TIMEOUT_SECONDS=30 ;;
  esac
  if [ -n "$SHIZUKU_STARTER" ] && adb -s "$TARGET" shell test -x "$SHIZUKU_STARTER" >/dev/null 2>&1 && \
      run_with_deadline "$SHIZUKU_START_TIMEOUT_SECONDS" \
        "$ADB_COMMAND" -s "$TARGET" shell "$SHIZUKU_STARTER" >/dev/null 2>&1; then
    echo "   ${GRN}✓${X} Shizuku service started"
  else
    SHIZUKU_START_STATUS=$?
    if [ "$SHIZUKU_START_STATUS" -eq 124 ] || [ "$SHIZUKU_START_STATUS" -eq 137 ]; then
      warn "Shizuku installed, but its service start timed out after ${SHIZUKU_START_TIMEOUT_SECONDS}s. Open Shizuku on the panel and follow its ADB start instructions."
    else
      warn "Shizuku installed, but its service did not start. Open Shizuku on the panel and follow its ADB start instructions."
    fi
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
if [ -n "$ROOT_HELPER_TRANSACTION_KIND" ]; then
  stop_root_helper_lease_guard
  if ! lease_guard_succeeded; then
    cleanup_root_helper_lease_guard
    fail "the root-helper transaction lease was lost before APK installation" \
      "The APK was not replaced. Re-run to reconcile the retained helper recovery journal safely."
  fi
  cleanup_root_helper_lease_guard
fi
step "📦 installing" "${D}$APK${X}"
install_apk
if [ -n "$ROOT_HELPER_TRANSACTION_KIND" ]; then
  if ! commit_root_helper_upgrade "$ROOT_HELPER_TRANSACTION_KIND" && \
     ! commit_root_helper_upgrade "$ROOT_HELPER_TRANSACTION_KIND"; then
    fail "the APK and root helper were installed, but the helper recovery journal could not be committed" \
      "Do not reboot yet. Check panel storage and permissions, then re-run this command to complete recovery cleanup."
  fi
  cleanup_root_helper_staging
  ROOT_HELPER_TRANSACTION_KIND=""
fi

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
if [ "$PROVISION_FAILED" = 0 ]; then
  show_provisioning_plan 1 "${D}waiting for the installed app to resolve this panel${X}" \
    || PROVISION_FAILED=1
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
    fl="$(curl -fsS --connect-timeout "$HA_AUTH_CONNECT_TIMEOUT_SECONDS" --max-time "$HA_AUTH_TIMEOUT_SECONDS" \
          -X POST "${HA_URL%/}/auth/login_flow" -H 'Content-Type: application/json' \
          --data "{\"client_id\":\"$jc\",\"handler\":[\"homeassistant\",null],\"redirect_uri\":\"$jc\"}" 2>/dev/null || true)"
    flow_id="$(printf '%s' "$fl" | grep -o '"flow_id":"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
    if [ -z "$flow_id" ]; then warn "HA login flow failed (is $HA_URL reachable?)"; HA_LOGIN_FAILED=1; else
      HA_LOGIN_BODY_FILE="$SECRET_DIR/ha-login-body.json"
      printf '{"client_id":"%s","username":"%s","password":"%s"}' "$jc" "$ju" "$jp" > "$HA_LOGIN_BODY_FILE"
      chmod 600 "$HA_LOGIN_BODY_FILE"
      res="$(curl -fsS --connect-timeout "$HA_AUTH_CONNECT_TIMEOUT_SECONDS" --max-time "$HA_AUTH_TIMEOUT_SECONDS" \
             -X POST "${HA_URL%/}/auth/login_flow/$flow_id" -H 'Content-Type: application/json' \
             --data-binary "@$HA_LOGIN_BODY_FILE" 2>/dev/null || true)"
      rm -f "$HA_LOGIN_BODY_FILE" "$HA_PASS_SECRET_FILE"
      HA_LOGIN_BODY_FILE=""; HA_PASS_SECRET_FILE=""; HA_PASS=""; jp=""
      code="$(printf '%s' "$res" | grep -o '"result":"[^"]*"' | head -1 | cut -d'"' -f4 || true)"
      if [ -z "$code" ]; then warn "HA login rejected (bad credentials or MFA-enabled account)"; HA_LOGIN_FAILED=1; else
        HA_AUTH_CODE_FILE="$SECRET_DIR/ha-authorization-code"
        printf '%s' "$code" > "$HA_AUTH_CODE_FILE"; chmod 600 "$HA_AUTH_CODE_FILE"
        tok="$(curl -fsS --connect-timeout "$HA_AUTH_CONNECT_TIMEOUT_SECONDS" --max-time "$HA_AUTH_TIMEOUT_SECONDS" \
               -X POST "${HA_URL%/}/auth/token" \
               --data-urlencode "grant_type=authorization_code" \
               --data-urlencode "code@$HA_AUTH_CODE_FILE" --data-urlencode "client_id=$cid" 2>/dev/null || true)"
        rm -f "$HA_AUTH_CODE_FILE"; HA_AUTH_CODE_FILE=""; code=""
        HA_TOKEN="$(printf '%s' "$tok" | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4 || true)"
        HA_REFRESH="$(printf '%s' "$tok" | grep -o '"refresh_token":"[^"]*"' | cut -d'"' -f4 || true)"
        exp="$(printf '%s' "$tok" | grep -o '"expires_in":[0-9]*' | grep -o '[0-9]*' || true)"
        [ -n "$exp" ] && HA_EXPIRY="$(( $(date +%s) + exp ))"
        if [ -n "$HA_TOKEN" ] && [ -n "$HA_REFRESH" ]; then
          HA_TOKEN_SECRET_FILE="$SECRET_DIR/ha-token"
          HA_REFRESH_SECRET_FILE="$SECRET_DIR/ha-refresh-token"
          printf '%s' "$HA_TOKEN" > "$HA_TOKEN_SECRET_FILE"
          printf '%s' "$HA_REFRESH" > "$HA_REFRESH_SECRET_FILE"
          chmod 600 "$HA_TOKEN_SECRET_FILE" "$HA_REFRESH_SECRET_FILE"
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
  HA_AUTH_HEADER_FILE="$SECRET_DIR/ha-authorization-header"
  printf 'Authorization: Bearer %s' "$HA_TOKEN" > "$HA_AUTH_HEADER_FILE"; chmod 600 "$HA_AUTH_HEADER_FILE"
  if curl -fsS --connect-timeout "$HA_AUTH_CONNECT_TIMEOUT_SECONDS" --max-time 10 -H "@$HA_AUTH_HEADER_FILE" \
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
  rm -f "$HA_AUTH_HEADER_FILE"; HA_AUTH_HEADER_FILE=""
fi

ARGS=()
[ -n "$PANEL_ID" ]   && ARGS+=(--data-urlencode "panel_id=$PANEL_ID")
[ -n "$MQTT" ]       && ARGS+=(--data-urlencode "mqtt_broker=$MQTT")
[ -n "$MQTT_USER" ]  && ARGS+=(--data-urlencode "mqtt_user=$MQTT_USER")
[ -n "$MQTT_PASS" ]  && ARGS+=(--data-urlencode "mqtt_password@$MQTT_PASS_SECRET_FILE")
if [ "$HA_LOGIN_FAILED" = 0 ]; then
  [ -n "$HA_URL" ]     && ARGS+=(--data-urlencode "ha_url=$HA_URL")
  [ -n "$HA_TOKEN" ]   && ARGS+=(--data-urlencode "ha_token@$HA_TOKEN_SECRET_FILE")
  [ -n "$HA_REFRESH" ] && ARGS+=(--data-urlencode "ha_refresh_token@$HA_REFRESH_SECRET_FILE")
  [ -n "$HA_EXPIRY" ]  && ARGS+=(--data-urlencode "ha_token_expiry=$HA_EXPIRY")
  [ "$BUILTIN" = 1 ]   && ARGS+=(--data-urlencode "dashboard_package=builtin")
fi
[ -n "$LOG_ENABLE" ] && ARGS+=(--data-urlencode "log_ship_enabled=$LOG_ENABLE")
[ -n "$LOG_HOST" ]   && ARGS+=(--data-urlencode "log_ship_host=$LOG_HOST")
[ -n "$LOG_PORT" ]   && ARGS+=(--data-urlencode "log_ship_port=$LOG_PORT")
[ -n "$LOG_PROTO" ]  && ARGS+=(--data-urlencode "log_ship_protocol=$LOG_PROTO")
PLAN_REFRESH_NEEDED=0
if [ ${#ARGS[@]} -gt 0 ]; then
  PLAN_REFRESH_NEEDED=1
  step "⚙️  configuring" "${D}panel_id / MQTT / renderer / log shipping${X}"
  if CFG_ERR="$(curl -fsS --connect-timeout "$PANEL_POST_CONNECT_TIMEOUT_SECONDS" \
      --max-time "$PANEL_POST_TIMEOUT_SECONDS" -H 'Accept: application/json' -X POST \
      "${ARGS[@]}" "$URL/api/v1/config" 2>&1 >/dev/null)"; then
    echo "   ${GRN}✓${X} applied"
  else
    CFG_ERR="$(printf '%s' "${CFG_ERR:-no response}" | sanitize_terminal)"
    warn "config not applied: $CFG_ERR"
    echo "   ${D}If the agent was still starting, re-running applies it. A 403 means the panel refused the source: its API only accepts LAN addresses — provision from a host on the panel's own network, not via VPN/routed subnet.${X}"
    PROVISION_FAILED=1
  fi
fi
MQTT_PASS=""; HA_TOKEN=""; HA_REFRESH=""; HA_PASS=""

# Config bundle restore (best-effort import: valid keys apply, invalid/unknown are reported + skipped —
# a bundle from different hardware or another ha-paneld version restores what it can). --restore applies
# everything incl. device-scoped keys (same-panel recovery / like-for-like replacement); --restore-fleet
# applies only PORTABLE non-secret keys (cross-panel deployment).
if [ -n "$RESTORE_FILE" ]; then
  PLAN_REFRESH_NEEDED=1
  MODE_Q=""; [ "$RESTORE_MODE" = "fleet" ] && MODE_Q="?mode=fleet"
  step "📦 restoring config" "${D}$RESTORE_FILE (${RESTORE_MODE})${X}"
  if response="$(curl -fsS --connect-timeout "$PANEL_POST_CONNECT_TIMEOUT_SECONDS" \
      --max-time "$PANEL_RESTORE_TIMEOUT_SECONDS" -H 'Content-Type: application/json' --data-binary @"$RESTORE_FILE" \
      -w '\n%{http_code}' "$URL/api/v1/config/import$MODE_Q" 2>&1)"; then
    http_status="${response##*$'\n'}"
    resp="${response%$'\n'*}"
    resp="$(printf '%s' "$resp" | sanitize_terminal)"
    if [ "$http_status" = 202 ] && printf '%s' "$resp" | approval_required_response; then
      echo "   ${YEL}⚠ config import requires approval on the panel; no settings were imported.${X}"
      echo "   ${D}On the panel, open Configure → toolbar overflow → Security mode → Review approvals, approve the config import, then retry the identical command from this computer within ten minutes.${X}"
      PROVISION_FAILED=1
    elif [ "$http_status" != 200 ]; then
      echo "   ${RED}✗ import failed:${X} unexpected HTTP $http_status${resp:+: $resp}"
      PROVISION_FAILED=1
    else
      echo "   ${GRN}✓${X} $resp"
    fi
  else
    resp="$(printf '%s' "$response" | sanitize_terminal)"
    echo "   ${RED}✗ import failed:${X} $resp"
    PROVISION_FAILED=1
  fi
fi

# Configuration can change live observations used by the planner. Re-render the app-owned plan after
# all config/import work; it remains guidance only and never authorises Bash to mutate panel state.
if [ "$PLAN_REFRESH_NEEDED" = 1 ]; then
  show_provisioning_plan 1 "${D}refreshing guidance after configuration${X}" \
    || PROVISION_FAILED=1
fi

echo
VERIFY_DIRECT_GRANTS=1
if verify; then
  [ "$PROVISION_FAILED" = 0 ] && echo "${GRN}${B}✅ provisioned and verified${X} — ${B}$URL/${X}"
else
  PROVISION_FAILED=1
fi
if [ "$PROVISION_FAILED" != 0 ]; then
  echo "${RED}${B}✗ provisioning incomplete${X} — correct the failed item above, then re-run the same command."
fi
if [ "$PROVISIONING_PLAN_AVAILABLE" != 1 ]; then
  echo "${D}   Root helper: needed only for helper-backed controls declared by the active profile, and installable only where live vendor su or root ADB works. Do not infer this from the SoC: some rk3576/PX30 panels are sandboxed, while others expose a usable root path. The app-owned plan is unavailable, so this fallback cannot state this panel's exact need.${X}"
  echo "${D}   From a source checkout, and only on a panel with a proven root path, install it with:  ./helper/build.sh && ./helper/install-daemon.sh $TARGET${X}"

  # Flag a too-old system WebView only for older apps. Current apps own this profile-aware guidance.
  check_webview
fi

# Tuya/TPA10 only: offer to disable the closed vendor app stack for a faster, minimal HA panel (guarded
# on root + persistent adb + a replacement launcher; prompts unless --strip-vendor was passed).
[ "$PROVISION_FAILED" = 0 ] && offer_strip_vendor

exit "$PROVISION_FAILED"
