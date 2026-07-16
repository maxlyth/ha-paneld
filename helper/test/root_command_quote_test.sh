#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

extract_quote_function() {
  awk '
    /^quote_root_command\(\) \{/ { copying=1 }
    copying { print }
    copying && /^}/ { exit }
  ' "$1"
}

verify_script() {
  local script="$1" original quoted
  eval "$(extract_quote_function "$script")"
  original='marker=/protected/journal; actual=$(sha256sum "$marker"); literal=`id`; [ "${actual%% *}" = "$expected" ]'
  quoted="$(quote_root_command "$original")"

  eval "set -- su 0 \"$quoted\""
  [ "$#" -eq 3 ]
  [ "$1" = su ]
  [ "$2" = 0 ]
  [ "$3" = "$original" ]

  eval "set -- su 0 sh -c \"$quoted\""
  [ "$#" -eq 5 ]
  [ "$1" = su ]
  [ "$2" = 0 ]
  [ "$3" = sh ]
  [ "$4" = -c ]
  [ "$5" = "$original" ]
}

verify_script "$ROOT/scripts/provision.sh"
verify_script "$ROOT/helper/install-daemon.sh"
echo "root command quoting tests passed"
