#!/usr/bin/env bash
# Mutation battery for the log-sink address-consistency contracts.
#
# Each mutation breaks ONE property of the shipped behaviour, runs the focused suite, and records
# which named assertions turned red. A mutation that leaves the suite green means the corresponding
# assertion cannot fail and is not evidence — that outcome is a finding, not a pass.
#
# Never mutates the tree you invoke it from: it copies the sources into a throwaway directory,
# mutates there, and removes it on exit. A killed run therefore cannot leave a mutation applied.
#
# Usage: tools/logship-endpoint/mutation-battery.sh [<source-tree>]
set -uo pipefail

SRC="$(cd "${1:-$(dirname "$0")/../..}" && pwd)"
WT="$(mktemp -d /tmp/logship-mut-XXXXXX)"
trap 'rm -rf "$WT"' EXIT

# Fail closed on a bad copy. A silently empty tree would make every mutation "kill" everything for
# the wrong reason, which is exactly the shape of evidence this script exists to refuse.
cp -a "$SRC/." "$WT/" || { echo "copy of $SRC failed"; exit 2; }
rm -rf "$WT/.git" "$WT/build" "$WT/app/build"
[ -f "$WT/app/src/main/kotlin/io/github/maxlyth/hapaneld/util/LogShipEndpoint.kt" ] ||
  { echo "copied tree is missing the sources under mutation"; exit 2; }

TESTS='*LogShipEndpointTest*|*ConfigTransactionTest*|*SettingRowFormatterTest*'
ENDPOINT="app/src/main/kotlin/io/github/maxlyth/hapaneld/util/LogShipEndpoint.kt"
CONFIG="app/src/main/kotlin/io/github/maxlyth/hapaneld/Config.kt"
FORMATTER="app/src/main/kotlin/io/github/maxlyth/hapaneld/http/SettingRowFormatter.kt"

pass=0
fail=0

run_suite() {
  (cd "$WT" && ./gradlew --offline -q :app:testDebugUnitTest \
    --tests '*LogShipEndpointTest*' \
    --tests '*ConfigTransactionTest*' \
    --tests '*SettingRowFormatterTest*' ) > "$WT/run.log" 2>&1
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

mutate() {
  local name="$1" file="$2" script="$3"
  local target="$WT/$file"
  local before after
  before="$(sha256sum "$target" | cut -d' ' -f1)"
  cp "$target" "$target.orig"
  sed -i "$script" "$target"
  after="$(sha256sum "$target" | cut -d' ' -f1)"

  if [ "$before" = "$after" ]; then
    printf '%-42s MUTATION-ABSENT (sed matched nothing — proves nothing)\n' "$name"
    fail=$((fail + 1))
    mv "$target.orig" "$target"
    return
  fi

  run_suite
  local status=$?
  local red
  red="$(red_assertions)"

  if [ "$status" -eq 0 ]; then
    # Distinguish "the guard is redundant" from "the mutation would not compile", because a
    # compile failure is not evidence that an assertion can fail.
    if grep -q "^e: \|Compilation error" "$WT/run.log"; then
      printf '%-42s MUTATION-DID-NOT-COMPILE (not evidence)\n' "$name"
    else
      printf '%-42s SURVIVED — no assertion covers this\n' "$name"
    fi
    fail=$((fail + 1))
  else
    printf '%-42s killed %s assertion(s)\n' "$name" "$(printf '%s' "$red" | grep -c . || true)"
    printf '%s\n' "$red" | sed 's/^/      /'
    pass=$((pass + 1))
  fi
  mv "$target.orig" "$target"
}

echo "baseline (unmutated) must be green:"
if run_suite; then echo "  baseline green"; else echo "  BASELINE RED — fix before trusting any result"; exit 1; fi
echo

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

echo
printf 'killed %d / survived-or-absent %d\n' "$pass" "$fail"
[ "$fail" -eq 0 ]
