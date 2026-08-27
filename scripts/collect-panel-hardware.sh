#!/usr/bin/env bash
# Bounded, read-only evidence collector for the preliminary ZX-SMT156 profile.
set -u

SERIAL=""
OBSERVE="none"
TRUNCATED="false"

usage() {
  cat <<'EOF'
Usage: scripts/collect-panel-hardware.sh [--serial SERIAL] [--observe none|climate]

Writes a public-reviewable, read-only hardware report to stdout. Progress and errors go to stderr.
Live input sampling is opt-in, limited to exact known sensor names, 10 seconds and 32 events.
EOF
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --serial)
      [ "$#" -ge 2 ] || { echo "error: --serial needs a value" >&2; exit 2; }
      SERIAL="$2"; shift 2
      ;;
    --observe)
      [ "$#" -ge 2 ] || { echo "error: --observe needs a value" >&2; exit 2; }
      OBSERVE="$2"; shift 2
      ;;
    -h|--help) usage; exit 0 ;;
    *) echo "error: unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

case "$OBSERVE" in
  none|climate) ;;
  *) echo "error: --observe must be none or climate" >&2; exit 2 ;;
esac

command -v adb >/dev/null 2>&1 || {
  echo "error: adb is not on PATH" >&2
  exit 1
}

ADB=(adb)
[ -z "$SERIAL" ] || ADB+=(-s "$SERIAL")

# The blanket form is correct HERE and only here: this collector reads, and never hands adb a path on
# the host, so switching Git Bash's path conversion off wholesale costs nothing. scripts/provision.sh
# and helper/install-daemon.sh deliberately use a narrow MSYS2_ARG_CONV_EXCL prefix list instead,
# because they push, pull and install host files that adb.exe can only open once converted. Do not
# "harmonise" the two in either direction.
adb_run() {
  MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' "${ADB[@]}" "$@"
}

if ! adb_run get-state >/dev/null 2>&1; then
  echo "error: select one connected, authorized adb device (or pass --serial)" >&2
  exit 1
fi

sanitize_line() {
  local text prefix rest sequence ansi_tail_re='^[0-9;?]*[[:alpha:]]'
  text="$(tr -d '\r')"
  while [[ "$text" == *$'\033['* ]]; do
    prefix="${text%%$'\033['*}"
    rest="${text#*$'\033['}"
    if [[ "$rest" =~ $ansi_tail_re ]]; then
      sequence="${BASH_REMATCH[0]}"
      text="${prefix}${rest#"$sequence"}"
    else
      text="${prefix}${rest}"
    fi
  done
  text="$(printf '%s' "$text" | tr '\n\t' '  ' | tr -cd '[:print:]' | sed 's/[[:space:]]*$//' | cut -c1-140)"
  redact_serial "$text" | cut -c1-140
}

redact_serial() {
  local remaining="$1" output="" prefix
  if [ -n "$SERIAL" ]; then
    while [[ "$remaining" == *"$SERIAL"* ]]; do
      prefix="${remaining%%"$SERIAL"*}"
      output="${output}${prefix}[redacted-adb-serial]"
      remaining="${remaining#*"$SERIAL"}"
    done
  fi
  printf '%s%s' "$output" "$remaining"
}

probe_property() {
  local key="$1" value
  if value="$(adb_run shell getprop "$key" 2>/dev/null)"; then
    value="$(printf '%s' "$value" | sanitize_line)"
    if [ -n "$value" ]; then printf 'ok\t%s' "$value"; else printf 'missing\tunknown'; fi
  elif ! transport_ok; then
    printf 'transport-unavailable\tunknown'
  else
    printf 'read-failed\tunknown'
  fi
}

print_bounded() {
  local text="$1"
  text="$(redact_serial "$text")"
  if [ "${#text}" -gt 8192 ] || printf '%s\n' "$text" | awk 'length($0) > 160 { found=1 } END { exit !found }'; then
    TRUNCATED="true"
  fi
  printf '%s\n' "$text" | LC_ALL=C tr -cd '\11\12\40-\176' | cut -c1-160 | head -c 8192
}

transport_ok() {
  adb_run get-state >/dev/null 2>&1
}

map_input_nodes() {
  local wanted="$1"
  awk -v wanted="$wanted" '
    /^N: Name="/ {
      name=$0
      sub(/^N: Name="/, "", name)
      sub(/"$/, "", name)
    }
    /^H: Handlers=/ && name == wanted {
      for (i=1; i<=NF; i++) {
        handler=$i
        sub(/^Handlers=/, "", handler)
        if (handler ~ /^event[0-9]+$/) print "/dev/input/" handler
      }
    }
  ' <<<"$INPUTS" | sort -u
}

load_inputs() {
  if INPUTS="$(adb_run shell cat /proc/bus/input/devices 2>/dev/null)"; then
    INPUT_STATUS="ok"
  elif ! transport_ok; then
    INPUTS=""
    INPUT_STATUS="transport-unavailable"
  elif ! adb_run shell test -e /proc/bus/input/devices >/dev/null 2>&1; then
    INPUTS=""
    INPUT_STATUS="missing"
  elif ! adb_run shell test -r /proc/bus/input/devices >/dev/null 2>&1; then
    INPUTS=""
    INPUT_STATUS="denied"
  else
    INPUTS=""
    INPUT_STATUS="read-failed"
  fi
}

sample_requested() {
  [ "$OBSERVE" = "climate" ]
}

print_input() {
  local name="$1" nodes node count capability status sample sample_rc sample_status
  printf '\n[input %s]\nobservation=%s\n' "$name" "$OBSERVE"
  if [ "$INPUT_STATUS" != "ok" ]; then
    printf 'inventory_status=%s\nnode=unknown\ncapability_status=not-available\nsample_status=not-available\n' \
      "$INPUT_STATUS"
    return
  fi
  nodes="$(map_input_nodes "$name")"
  count="$(printf '%s\n' "$nodes" | sed '/^$/d' | wc -l | tr -d ' ')"

  if [ "$count" -eq 0 ]; then
    printf 'node=missing\ncapability_status=missing\nsample_status=not-available\n'
    return
  fi
  if [ "$count" -ne 1 ]; then
    printf 'node=ambiguous\ncapability_status=ambiguous\nsample_status=not-available\n'
    return
  fi

  node="$nodes"
  case "$node" in
    /dev/input/event[0-9]*) ;;
    *) printf 'node=rejected\ncapability_status=rejected\nsample_status=not-available\n'; return ;;
  esac

  printf 'node=%s\n' "$node"
  capability="$(adb_run shell getevent -lp "$node" 2>/dev/null)"
  if [ "$?" -eq 0 ] && [ -n "$capability" ]; then
    status="ok"
  elif ! transport_ok; then
    status="transport-unavailable"
  elif ! adb_run shell test -e "$node" >/dev/null 2>&1; then
    status="missing"
  elif ! adb_run shell test -r "$node" >/dev/null 2>&1; then
    status="denied"
  else
    status="probe-failed"
  fi
  printf 'capability_status=%s\ncapability_begin\n' "$status"
  print_bounded "$capability"
  printf '\ncapability_end\n'

  if ! sample_requested; then
    printf 'sample_status=not-requested\n'
    return
  fi

  echo "Observe '$name' now; capture stops after 10 seconds or 32 events." >&2
  sample="$(MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*' \
    timeout 12 "${ADB[@]}" shell timeout 10 getevent -lt -c 32 "$node" 2>/dev/null)"
  sample_rc=$?
  case "$sample_rc" in
    0) sample_status="ok" ;;
    124) sample_status="timeout" ;;
    *)
      if ! transport_ok; then sample_status="transport-unavailable"
      elif ! adb_run shell test -e "$node" >/dev/null 2>&1; then sample_status="missing"
      elif ! adb_run shell test -r "$node" >/dev/null 2>&1; then sample_status="denied"
      else sample_status="unsupported-or-failed"; fi
      ;;
  esac
  printf 'sample_status=%s\nsample_seconds=10\nsample_limit=32\nsample_begin\n' "$sample_status"
  print_bounded "$sample"
  printf '\nsample_end\n'
}

MODEL_RESULT="$(probe_property ro.product.model)"
MODEL_STATUS="${MODEL_RESULT%%$'\t'*}"
MODEL="${MODEL_RESULT#*$'\t'}"
DEVICE_RESULT="$(probe_property ro.product.device)"
DEVICE_STATUS="${DEVICE_RESULT%%$'\t'*}"
DEVICE="${DEVICE_RESULT#*$'\t'}"
ANDROID_RESULT="$(probe_property ro.build.version.release)"
ANDROID_STATUS="${ANDROID_RESULT%%$'\t'*}"
ANDROID="${ANDROID_RESULT#*$'\t'}"
SDK_RESULT="$(probe_property ro.build.version.sdk)"
SDK_STATUS="${SDK_RESULT%%$'\t'*}"
SDK="${SDK_RESULT#*$'\t'}"
INPUTS=""
INPUT_STATUS="not-requested"

printf 'ha-paneld hardware evidence — schema 1 collector 1\n'
printf '[captured] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
printf '\n[target]\nmodel=%s\nmodel_status=%s\ndevice=%s\ndevice_status=%s\n' \
  "$MODEL" "$MODEL_STATUS" "$DEVICE" "$DEVICE_STATUS"
printf 'android=%s (API %s)\nandroid_status=%s\nsdk_status=%s\nauthority=adb-shell\n' \
  "$ANDROID" "$SDK" "$ANDROID_STATUS" "$SDK_STATUS"

if [ "$DEVICE_STATUS" != "ok" ]; then
  printf '\n[collector]\nstatus=identity-unavailable\n'
  printf 'note=exact target identity could not be read; no hardware probes were run\n'
elif [ "$DEVICE" = "rk3566_t" ]; then
  load_inputs
  print_input "sun-ths"
  print_input "sun-hum"
else
  printf '\n[collector]\nstatus=unsupported-target\n'
  printf 'note=only the exact rk3566_t target has allowlisted probes\n'
fi

printf '\n[limits]\nper_block_bytes=8192\ntruncated=%s\n' "$TRUNCATED"
