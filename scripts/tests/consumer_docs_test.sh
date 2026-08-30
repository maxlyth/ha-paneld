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
  docs/provisioning-safety.md
  docs/built-in-renderer.md
  docs/profiles/unofficial/echo-show-5-gen2.md
  docs/profiles/unofficial/lenovo-thinksmart-view-lineageos.md
  docs/profiles/unofficial/sunworld-yc-sm55p-p76s01.md
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

# Pin consumer prose to behavior-affecting implementation seams rather than inert policy constants.
# The focused provisioner suite mutation-proves these branches; this contract prevents editorial
# changes from silently describing the former staged/capacity-gated behavior again.
backup_doc_section="$(awk '
  /^## Backups and recovery$/ { in_section = 1 }
  in_section && /^## Time zone and first-install checks$/ { exit }
  in_section { print }
' docs/provisioning-safety.md)"

backup_doc_claims=(
  'quiesces database writers'
  'copies the closed database directly'
  "falls back once to SQLite's live \`.backup\`"
  'There is no fixed free-space threshold for making this backup'
  'mandatory host-side SHA-256'
  'Database-backup availability is best-effort for an ordinary in-place upgrade'
  'may continue with the original app data untouched'
  '**Reset is irreversible and makes no backup.**'
  'separate **Install → Backup** operation'
  'Use `--export FILE` as a separate command first'
)
for claim in "${backup_doc_claims[@]}"; do
  if ! grep -Fq -- "$claim" <<< "$backup_doc_section"; then
    printf 'docs/provisioning-safety.md: backup contract is missing %s\n' "$claim" >&2
    failed=1
  fi
done
if ! grep -Fq -- 'Reset is irreversible and makes no backup' scripts/install.sh ||
   grep -Fq -- 'backs the configuration up first' scripts/install.sh; then
  printf 'scripts/install.sh: checkout-free reset help contradicts the no-backup contract\n' >&2
  failed=1
fi

reject_backup_prose() {
  local description="$1"
  local pattern="$2"
  local matches

  matches="$(grep -Ein -- "$pattern" <<< "$backup_prose_for_contradictions" || true)"
  if [ -n "$matches" ]; then
    printf 'docs/provisioning-safety.md: backup contract contradicts %s:\n%s\n' \
      "$description" "$matches" >&2
    failed=1
  fi
}

# These are semantic contradictions, not forbidden vocabulary. For example, the required
# "no fixed free-space threshold" sentence remains valid while a minimum, gate or refusal tied
# to MiB, free space or /data is rejected.
backup_prose_for_contradictions="${backup_doc_section//There is no fixed free-space threshold for making this backup/}"
reject_backup_prose \
  'best-effort capture without a fixed storage admission gate' \
  '((requires?|minimum|at least|fixed|floor|threshold|gate|admission|admit|refus(e|es|ed)|block(s|ed)?|fail(s|ed)?|skip(s|ped)?)[^.!?]*(free[ -]?space|data[ -]?volume|/data|[0-9]+([.][0-9]+)?[[:space:]]*(mib|mb|gib|gb))|(free[ -]?space|data[ -]?volume|/data|[0-9]+([.][0-9]+)?[[:space:]]*(mib|mb|gib|gb))[^.!?]*(minimum|at least|fixed|floor|threshold|gate|admission|admit|refus(e|es|ed)|block(s|ed)?|fail(s|ed)?|skip(s|ped)?))'
reject_backup_prose \
  'mandatory host verification' \
  '(((host(-side)?[[:space:]]+)?(sha-?256|digest|hash|verification))[^.!?]*(optional|best[ -]?effort|degrad(e|ed|ation)|skip(ped)?|unavailable|not required)|(optional|best[ -]?effort|degrad(e|ed|ation)|skip(ped)?|unavailable|not required)[^.!?]*((host(-side)?[[:space:]]+)?(sha-?256|digest|hash|verification)))'
reject_backup_prose \
  'ordinary upgrades continuing when only the database backup is unavailable' \
  '((ordinary|normal|in-place)[^.!?]*(upgrade|install)[^.!?]*(fail(s|ed)?|abort(s|ed)?|refus(e|es|ed)|block(s|ed)?|stop(s|ped)?|cannot continue|will not continue)[^.!?]*(backup|snapshot)|(backup|snapshot)[^.!?]*(unavailable|missing|fail(s|ed)?|cannot be captured)[^.!?]*(fail(s|ed)?|abort(s|ed)?|refus(e|es|ed)|block(s|ed)?|stop(s|ped)?|prevent(s|ed)?)[^.!?]*(ordinary|normal|in-place|upgrade|install|package replacement)|(ordinary|normal|in-place)[^.!?]*(upgrade|install)[^.!?]*(requires?|mandatory|must have)[^.!?]*(backup|snapshot))'
reset_prose_for_contradictions="${backup_prose_for_contradictions//and neither creates nor requires a backup/and bypasses automatic backup}"
if grep -Eiq -- '((--reset-config|reset)[^.!?]*(requires?|must have|will not erase|refus(e|es|ed))[^.!?]*(backup|snapshot)|(backup|snapshot)[^.!?]*(required|mandatory)[^.!?]*(--reset-config|reset))' <<< "$reset_prose_for_contradictions"; then
  printf 'docs/provisioning-safety.md: reset contract contradicts its no-backup behavior\n' >&2
  failed=1
fi

implementation_seams=(
  'io.github.maxlyth.hapaneld.action.PREPARE_UPGRADE'
  'HAPANELD_UPGRADE_READY_V1:'
  'exec-out'
  '".backup '\''@STAGE@/ha-paneld.db'\''"'
  'host_sha="$(host_sha256 "$host_db"'
  'continuing the ordinary in-place upgrade WITHOUT a database restore point'
  '[ "$RESET_CONFIG" = 1 ] || auto_export_before_upgrade'
  '[ "$RESET_CONFIG" = 1 ] || snapshot_panel_database'
)
for seam in "${implementation_seams[@]}"; do
  if ! grep -Fq -- "$seam" scripts/provision.sh; then
    printf 'scripts/provision.sh: shipped backup seam is missing %s\n' "$seam" >&2
    failed=1
  fi
done
if grep -Fq -- 'df -P -k /data' scripts/provision.sh; then
  printf 'scripts/provision.sh: fixed /data capacity gate returned\n' >&2
  failed=1
fi

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
  '`/api/v1/logship/status`'
  '`/api/v1/power-safety`'
  '`/api/v1/auto-sleep`'
  '`/api/v1/config/probe-log-sink`'
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
