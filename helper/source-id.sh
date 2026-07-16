#!/usr/bin/env bash
# Deterministic identity of the helper implementation compiled into every app/release artifact.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
export LC_ALL=C

if command -v sha256sum >/dev/null 2>&1; then
  HASH=(sha256sum)
else
  HASH=(shasum -a 256)
fi

{
  printf '%s\0' 'contract:android-api=26;optimization=O2;strip=true'
  for path in helper/src/*; do
    case "$path" in
      *.c|*.h|*.def) ;;
      *) continue ;;
    esac
    size="$(wc -c < "$path" | tr -d '[:space:]')"
    printf '%s\0%s\0' "$path" "$size"
    command cat "$path"
    printf '\0'
  done
} | "${HASH[@]}" | awk '{print $1}'
