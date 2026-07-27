#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

# Targeted regression guard for the novice-facing entry points that promise commands requiring no
# source checkout. It intentionally checks fenced literal scripts/... invocations; it is not a
# general Markdown parser or a policy for developer/contributor documentation.
checkout_free_docs=(
  README.md
  docs/provisioning.md
  docs/shizuku.md
  docs/built-in-renderer.md
  docs/profiles/unofficial/echo-show-5-gen2.md
  docs/hardware/zx-smt156.md
  docs/profiles/unofficial/README.md
  docs/api.md
)

failed=0
for doc in "${checkout_free_docs[@]}"; do
  while IFS=: read -r line text; do
    printf '%s:%s: consumer command assumes a source checkout: %s\n' "$doc" "$line" "$text" >&2
    failed=1
  done < <(
    awk '
      /<!-- source-checkout-only -->/ {
        allow_next_fence = 1
        next
      }
      /^```/ {
        if (!in_fence) {
          in_fence = 1
          checkout_only = allow_next_fence
          allow_next_fence = 0
        } else {
          in_fence = 0
          checkout_only = 0
        }
        next
      }
      allow_next_fence && $0 !~ /^[[:space:]]*$/ {
        allow_next_fence = 0
      }
      in_fence && !checkout_only &&
        $0 ~ /(^|[|;&[:space:]])(\.\/)?scripts\/[A-Za-z0-9._\/-]+/ {
          print NR ":" $0
      }
    ' "$doc"
  )
done

if (( failed )); then
  cat >&2 <<'EOF'
Checkout-free entry points must not tell ordinary users to execute a repository-relative script.
Use a checkout-free download command, prefer an on-panel UI, or put
<!-- source-checkout-only --> immediately before a code block that genuinely requires a checkout.
EOF
  exit 1
fi

fleet_recipe="$(awk '
  /^## Deploying shared settings to a fleet$/ { in_section = 1; next }
  in_section && /^## / { exit }
  in_section { print }
' docs/provisioning.md)"

fleet_recipe_requirements=(
  'curl -fsSL'
  '--restore-fleet'
  '--id'
  '--mqtt-user'
  '--mqtt-pass-file'
  '--ha-token-file'
  'PORTABLE, non-secret'
  'auth-failed'
)
for requirement in "${fleet_recipe_requirements[@]}"; do
  if ! grep -Fq -- "$requirement" <<< "$fleet_recipe"; then
    printf 'docs/provisioning.md: fleet restore recipe is missing %s\n' "$requirement" >&2
    failed=1
  fi
done

if (( failed )); then
  exit 1
fi

# Keep the maintained HTTP/MQTT overview aligned with source-level distinctions that are easy to
# flatten into misleading user promises during release editing.
api_contract_requirements=(
  'RGB or brightness-only panel LED'
  'The panel and Companion update buttons are always published.'
  '`/api/v1/restore`'
  '`/api/v1/input`'
  '`/api/v1/perf/history`'
)
for requirement in "${api_contract_requirements[@]}"; do
  if ! grep -Fq -- "$requirement" docs/api.md; then
    printf 'docs/api.md: API currency contract is missing %s\n' "$requirement" >&2
    failed=1
  fi
done

removed_navigation_entities=(
  '`button.<panel>_admin_launcher`'
  '`button.<panel>_back`'
  '`button.<panel>_home`'
  '`button.<panel>_launcher`'
  '`button.<panel>_recents`'
)
for entity in "${removed_navigation_entities[@]}"; do
  if grep -Fq -- "$entity" docs/api.md; then
    printf 'docs/api.md: retired MQTT navigation entity is still documented: %s\n' "$entity" >&2
    failed=1
  fi
done

if grep -Eq '`/(restore|input|perf/history|logs/stream)`' docs/api.md; then
  printf 'docs/api.md: versioned-only endpoint is presented as a root path\n' >&2
  failed=1
fi

if (( failed )); then
  exit 1
fi

echo "checkout-free entry-point command regression: PASS"
