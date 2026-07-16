#!/usr/bin/env bash
set -u

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COLLECTOR="$ROOT/scripts/collect-panel-hardware.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin"
ln -s "$ROOT/scripts/tests/fixtures/hardware-adb" "$TMP/bin/adb"
export PATH="$TMP/bin:/usr/bin:/bin"
export MOCK_CALL_LOG="$TMP/calls.log"

passes=0
failures=0

check() {
  local description="$1"; shift
  if "$@"; then
    passes=$((passes + 1)); printf 'ok %d - %s\n' "$passes" "$description"
  else
    failures=$((failures + 1)); printf 'not ok - %s\n' "$description" >&2
  fi
}

run_collector() {
  : > "$MOCK_CALL_LOG"
  rm -f "$TMP/transport-failed"
  MOCK_DEVICE="${MOCK_DEVICE:-rk3566_t}" \
  MOCK_MODEL="${MOCK_MODEL:-rk3566_t}" \
  MOCK_THS_EVENT="${MOCK_THS_EVENT:-7}" \
  MOCK_HUM_EVENT="${MOCK_HUM_EVENT:-8}" \
  MOCK_LIGHT_EVENT="${MOCK_LIGHT_EVENT:-5}" \
  MOCK_AMBIGUOUS="${MOCK_AMBIGUOUS:-0}" \
  MOCK_INPUT_FAIL="${MOCK_INPUT_FAIL:-none}" \
  MOCK_FAIL_GETEVENT="${MOCK_FAIL_GETEVENT:-none}" \
  MOCK_BACKLIGHT_DENIED="${MOCK_BACKLIGHT_DENIED:-0}" \
  MOCK_SAMPLE_STATUS="${MOCK_SAMPLE_STATUS:-ok}" \
  MOCK_SAMPLE_DENIED="${MOCK_SAMPLE_DENIED:-0}" \
  MOCK_LONG_CAPABILITY="${MOCK_LONG_CAPABILITY:-0}" \
  MOCK_IDENTITY_FAIL="${MOCK_IDENTITY_FAIL:-none}" \
  MOCK_STATE_DIR="$TMP" \
  MOCK_SERIAL="${MOCK_SERIAL:-private:5555}" \
    timeout 20 bash "$COLLECTOR" "$@" > "$TMP/output" 2> "$TMP/error"
  STATUS=$?
}

safe_call_log() {
  local line cmd
  while IFS= read -r line; do
    cmd="${line#* adb }"
    if [[ "$cmd" == -s\ * ]]; then
      cmd="${cmd#-s }"
      cmd="${cmd#* }"
    fi
    if [[ "$cmd" =~ ^shell\ getevent\ -lp\ /dev/input/event[0-9]+$ ]] ||
       [[ "$cmd" =~ ^shell\ timeout\ 10\ getevent\ -lt\ -c\ 32\ /dev/input/event[0-9]+$ ]] ||
       [[ "$cmd" =~ ^shell\ test\ -(e|r)\ /dev/input/event[0-9]+$ ]]; then
      continue
    fi
    case "$cmd" in
      get-state|\
      "shell getprop ro.product.model"|\
      "shell getprop ro.product.device"|\
      "shell getprop ro.build.version.release"|\
      "shell getprop ro.build.version.sdk"|\
      "shell cat /proc/bus/input/devices"|\
      "shell settings get system screen_brightness"|\
      "shell cat /sys/class/leds/lcd-backlight/brightness"|\
      "shell cat /sys/class/leds/lcd-backlight/actual_brightness"|\
      "shell cat /sys/class/leds/lcd-backlight/max_brightness"|\
      "shell cat /sys/class/leds/lcd-backlight/bl_power"|\
      "shell cat /sys/class/leds/lcd-backlight/type"|\
      "shell test -e /proc/bus/input/devices"|\
      "shell test -r /proc/bus/input/devices"|\
      "shell test -e /sys/class/leds/lcd-backlight/brightness"|\
      "shell test -e /sys/class/leds/lcd-backlight/actual_brightness"|\
      "shell test -e /sys/class/leds/lcd-backlight/max_brightness"|\
      "shell test -e /sys/class/leds/lcd-backlight/bl_power"|\
      "shell test -e /sys/class/leds/lcd-backlight/type"|\
      "shell test -r /sys/class/leds/lcd-backlight/brightness"|\
      "shell test -w /sys/class/leds/lcd-backlight/brightness") ;;
      *) printf 'unexpected adb call: %s\n' "$cmd" >&2; return 1 ;;
    esac
  done < "$MOCK_CALL_LOG"
}

run_collector
check "passive ZX collection succeeds" test "$STATUS" -eq 0
check "exact climate inputs are mapped" grep -q 'node=/dev/input/event7' "$TMP/output"
check "passive mode does not collect live events" grep -q 'sample_status=not-requested' "$TMP/output"
check "Git Bash path conversion is disabled for every adb call" \
  awk 'index($0,"MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL=*")==1 {next} {exit 1}' "$MOCK_CALL_LOG"
check "touchscreen is never probed" sh -c "! grep -q 'goodix-ts\\|event2' '$MOCK_CALL_LOG'"
check "collector issues no mutating or broad-private commands" \
  sh -c "! grep -Eqi '(^| )(su|sendevent|input|chmod|settings put|logcat|pm list|dumpsys package)( |$)' '$MOCK_CALL_LOG'"
check "passive ZX uses only the exact read-only command allowlist" safe_call_log

MOCK_THS_EVENT=17 MOCK_HUM_EVENT=18 run_collector
check "event renumbering is resolved on each run" grep -q 'node=/dev/input/event17' "$TMP/output"

MOCK_DEVICE=cronos MOCK_LIGHT_EVENT=6 run_collector --observe light
check "Echo backlight evidence is read from the LED class" grep -q 'max_brightness=255' "$TMP/output"
check "Echo live light sample is bounded" grep -q 'sample_limit=32' "$TMP/output"
check "only the exact ALS input is sampled" grep -q 'getevent -lt -c 32 /dev/input/event6' "$MOCK_CALL_LOG"
check "live light uses only the exact read-only command allowlist" safe_call_log

MOCK_DEVICE=cronos run_collector --observe near
check "near observations are structurally identified" grep -q 'observation=near' "$TMP/output"
check "live near uses only the exact read-only command allowlist" safe_call_log

MOCK_DEVICE=rk3566_t MOCK_SAMPLE_STATUS=timeout run_collector --observe climate
check "partial live samples retain timeout status" grep -q 'sample_status=timeout' "$TMP/output"
check "timed-out climate capture remains read-only allowlisted" safe_call_log

MOCK_DEVICE=cronos MOCK_SAMPLE_DENIED=1 run_collector --observe light
check "live input permission denial is explicit" grep -q 'sample_status=denied' "$TMP/output"
check "denied live capture remains read-only allowlisted" safe_call_log

MOCK_DEVICE=rk3566_t MOCK_AMBIGUOUS=1 run_collector --observe climate
check "ambiguous input mappings fail closed" grep -q 'capability_status=ambiguous' "$TMP/output"
check "ambiguous inputs are not sampled" sh -c "! grep -q 'getevent -lt' '$MOCK_CALL_LOG'"

MOCK_DEVICE=rk3566_t MOCK_INPUT_FAIL=denied run_collector
check "input inventory denial is not mislabeled as missing sensors" grep -q 'inventory_status=denied' "$TMP/output"

MOCK_DEVICE=cronos MOCK_BACKLIGHT_DENIED=1 run_collector
check "backlight permission denial is explicit" grep -q 'brightness_status=denied' "$TMP/output"

MOCK_DEVICE=rk3566_t MOCK_FAIL_GETEVENT=transport run_collector --serial private:5555
check "mid-run disconnect is explicit" grep -q 'capability_status=transport-unavailable' "$TMP/output"
check "mid-run adb stderr cannot leak the serial into shared output" sh -c "! grep -q 'private:5555' '$TMP/output'"

MOCK_DEVICE=rk3566_t MOCK_LONG_CAPABILITY=1 run_collector
check "oversized capability output is bounded and labeled" grep -q 'truncated=true' "$TMP/output"

MOCK_DEVICE=unrelated MOCK_AMBIGUOUS=0 run_collector
check "unsupported targets receive no hardware probes" grep -q 'status=unsupported-target' "$TMP/output"
check "unsupported targets do not inspect input inventory or getevent" sh -c "! grep -Eq 'input/devices|getevent' '$MOCK_CALL_LOG'"
check "unsupported targets use only identity and transport reads" safe_call_log

MOCK_IDENTITY_FAIL=device run_collector --serial private:5555
check "identity transport failure is explicit" grep -q 'device_status=transport-unavailable' "$TMP/output"
check "identity failure prevents every hardware probe" sh -c "! grep -Eq 'input/devices|getevent|lcd-backlight' '$MOCK_CALL_LOG'"

MOCK_DEVICE=cronos MOCK_MODEL='Echo\033[31m private:5555' run_collector --serial private:5555
check "terminal control bytes are removed from shared output" sh -c "! grep -q \"$(printf '\\033')\" '$TMP/output'"
check "the adb serial is not included in shared output" sh -c "! grep -q 'private:5555' '$TMP/output'"
check "ANSI sequences are removed before property redaction" grep -q 'model=Echo \[redacted-adb-serial\]' "$TMP/output"

MOCK_DEVICE=cronos MOCK_MODEL=adb MOCK_SERIAL=adb run_collector --serial adb
check "serial replacement cannot rescan its own marker forever" test "$STATUS" -eq 0
check "short serial text is redacted once" grep -q 'model=\[redacted-adb-serial\]' "$TMP/output"

MANY_A="$(printf '%0140d' 0 | tr 0 a)"
MOCK_DEVICE=cronos MOCK_MODEL="$MANY_A" MOCK_SERIAL=a run_collector --serial a
check "repeated serial redaction keeps every shared line bounded" \
  awk 'length($0) > 160 { exit 1 }' "$TMP/output"

run_collector --observe invalid
check "invalid observation mode is rejected" test "$STATUS" -eq 2

printf '1..%d\n' "$((passes + failures))"
if [ "$failures" -ne 0 ]; then
  printf '%d test(s) failed\n' "$failures" >&2
  exit 1
fi
