#!/usr/bin/env bash
# Mutation battery for the log-sink address-consistency contracts.
#
# Each mutation breaks ONE property of the shipped behaviour, runs the focused suite, and records which
# named assertions turned red. A mutation that leaves the suite green means the corresponding assertion
# cannot fail and is not evidence — that outcome is a finding, not a pass.
#
# Two properties matter and are why this is not just a loop:
#
#   1. It never mutates the tree you invoke it from. Sources are copied into a throwaway directory and
#      mutated there, so a killed run cannot leave a mutation applied.
#   2. A non-zero suite exit is NOT accepted as a kill. A mutation that fails to compile, or a run that
#      dies for an infrastructure reason, also exits non-zero while proving nothing about any assertion.
#      A kill therefore requires BOTH a clean compile and at least one named red assertion in the JUnit
#      XML. The `syntax` and `absent` negative controls below exercise those rejection paths on every
#      run, so the classifier itself is evidence rather than an assumption.
#
# Usage: tools/logship-endpoint/mutation-battery.sh [<source-tree>]
set -uo pipefail

SRC="$(cd "${1:-$(dirname "$0")/../..}" && pwd)"
WT="$(mktemp -d /tmp/logship-mut-XXXXXX)"
trap 'rm -rf "$WT"' EXIT

# Fail closed on a bad copy. A silently empty tree would make every mutation "kill" everything for the
# wrong reason, which is exactly the shape of evidence this script exists to refuse.
cp -a "$SRC/." "$WT/" || { echo "copy of $SRC failed"; exit 2; }
rm -rf "$WT/.git" "$WT/build" "$WT/app/build"
[ -f "$WT/app/src/main/kotlin/io/github/maxlyth/hapaneld/util/LogShipEndpoint.kt" ] ||
  { echo "copied tree is missing the sources under mutation"; exit 2; }

ENDPOINT="app/src/main/kotlin/io/github/maxlyth/hapaneld/util/LogShipEndpoint.kt"
CONFIG="app/src/main/kotlin/io/github/maxlyth/hapaneld/Config.kt"
FORMATTER="app/src/main/kotlin/io/github/maxlyth/hapaneld/http/SettingRowFormatter.kt"

pass=0
fail=0

compiles() {
  (cd "$WT" && ./gradlew --offline -q :app:compileDebugKotlin :app:compileDebugUnitTestKotlin) \
    > "$WT/compile.log" 2>&1
}

run_suite() {
  rm -rf "$WT/app/build/test-results/testDebugUnitTest"
  (cd "$WT" && ./gradlew --offline -q :app:testDebugUnitTest \
    --tests '*LogShipEndpointTest*' \
    --tests '*ConfigTransactionTest*' \
    --tests '*SettingRowFormatterTest*' \
    --tests '*HaAreaProtocolTest*') > "$WT/run.log" 2>&1
}

red_assertions() {
  python3 - "$WT" <<'PY'
import glob, sys, xml.etree.ElementTree as ET
names = []
for p in glob.glob(sys.argv[1] + '/app/build/test-results/testDebugUnitTest/TEST-*.xml'):
    for tc in ET.parse(p).getroot().iter('testcase'):
        if tc.find('failure') is not None or tc.find('error') is not None:
            names.append(tc.get('name'))
print('\n'.join(sorted(names)))
PY
}

# verdict ∈ absent | no-compile | infrastructure | survived | killed
classify() {
  local target="$1" before="$2"
  [ "$before" = "$(sha256sum "$target" | cut -d' ' -f1)" ] && { echo absent; return; }
  compiles || { echo no-compile; return; }
  run_suite
  local status=$? red
  red="$(red_assertions)"
  if [ -n "$red" ]; then echo killed
  elif [ "$status" -ne 0 ]; then echo infrastructure
  else echo survived
  fi
}

mutate() {
  local name="$1" file="$2" script="$3" expect="${4:-killed}"
  local target="$WT/$file" before verdict
  before="$(sha256sum "$target" | cut -d' ' -f1)"
  cp "$target" "$target.orig"
  sed -i "$script" "$target"

  verdict="$(classify "$target" "$before")"
  local red; red="$(red_assertions)"

  if [ "$verdict" = "$expect" ]; then
    if [ "$verdict" = killed ]; then
      printf '%-44s killed %s assertion(s)\n' "$name" "$(printf '%s' "$red" | grep -c . || true)"
      printf '%s\n' "$red" | sed 's/^/      /'
    else
      printf '%-44s %s (expected — classifier path proved)\n' "$name" "$verdict"
    fi
    pass=$((pass + 1))
  else
    printf '%-44s %s — EXPECTED %s\n' "$name" "$verdict" "$expect"
    [ "$verdict" = survived ] && printf '      no assertion covers this; write the case or drop the guard\n'
    fail=$((fail + 1))
  fi
  mv "$target.orig" "$target"
}

echo "baseline (unmutated) must be green:"
if compiles && run_suite && [ -z "$(red_assertions)" ]; then
  echo "  baseline green"
else
  echo "  BASELINE RED — fix before trusting any result"; exit 1
fi
echo

# --- negative controls: prove the classifier rejects non-evidence ---------------------------------
mutate "control: sed matches nothing" "$ENDPOINT" \
  's/this_token_appears_nowhere_in_the_file/x/' absent

mutate "control: mutation does not compile" "$ENDPOINT" \
  's/^object LogShipEndpoint {/object LogShipEndpoint { fun ( = broken syntax here/' no-compile

# --- real mutations --------------------------------------------------------------------------------
mutate "canonicalUpdate never fires" "$ENDPOINT" \
  's/if (ADDRESS_KEYS.none { it in update }) return null/if (true) return null/'

mutate "separate port outranks embedded port" "$ENDPOINT" \
  's/"log_ship_port" to endpoint.port.toString()/"log_ship_port" to (update["log_ship_port"] ?: endpoint.port.toString())/'

mutate "separate protocol outranks embedded scheme" "$ENDPOINT" \
  's/"log_ship_protocol" to endpoint.protocol/"log_ship_protocol" to (update["log_ship_protocol"] ?: endpoint.protocol)/'

mutate "only a host edit reconciles the triple" "$ENDPOINT" \
  's/if (ADDRESS_KEYS.none { it in update }) return null/if ("log_ship_host" !in update) return null/'

mutate "stored fallbacks ignored for absent keys" "$ENDPOINT" \
  's/update\["log_ship_port"\]?.trim()?.toIntOrNull() ?: storedPort/update["log_ship_port"]?.trim()?.toIntOrNull() ?: 514/'

mutate "config staging drops the port field" "$CONFIG" \
  's/editor.putInt("log_ship_port", fields.getValue("log_ship_port").toInt())//'

mutate "config staging drops the protocol field" "$CONFIG" \
  's/editor.putString("log_ship_protocol", fields.getValue("log_ship_protocol"))//'

mutate "every spec accepts a formatter" "$FORMATTER" \
  's/fun formattable(spec: SettingSpec): Boolean = !spec.secret \&\& spec.type != SettingType.BOOL/fun formattable(spec: SettingSpec): Boolean = true/'

mutate "secret specs accept a formatter" "$FORMATTER" \
  's/fun formattable(spec: SettingSpec): Boolean = !spec.secret \&\& spec.type != SettingType.BOOL/fun formattable(spec: SettingSpec): Boolean = spec.type != SettingType.BOOL/'

mutate "row binding is not enforced" "$FORMATTER" \
  's/require(rowKey == key) { "formatter for .\$key. used on the .\$rowKey. row" }//'

echo
printf 'as expected %d / unexpected %d\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
