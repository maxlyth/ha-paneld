#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPT="$ROOT/helper/install-daemon.sh"
RC="$ROOT/helper/hapaneld-helper.rc"

bash -n "$SCRIPT"
grep -Fqx 'service hapaneld_helper /data/local/hapaneld-helper --supervise' "$RC"
! grep -Eq '^service .* /(system/bin|data/adb/hapaneld)/hapaneld-helper' "$RC"

grep -Fq 'CANONICAL_HELPER_PATH="/data/local/hapaneld-helper"' "$SCRIPT"
grep -Fq 'CANONICAL_CANDIDATE_PATH="/data/local/.hapaneld-helper.manual-$TRANSACTION_ID"' "$SCRIPT"
! grep -Fq 'CANONICAL_CANDIDATE_PATH="/data/local/.hapaneld-helper.new"' "$SCRIPT"
grep -Fq 'chown 0:0 /data/local/hapaneld-helper' "$SCRIPT"
grep -Fq 'chmod 700 /data/local/hapaneld-helper' "$SCRIPT"
grep -Fq 'mv -f "$candidate" /data/local/hapaneld-helper' "$SCRIPT"

grep -Fq 'JOURNAL_VERSION=3' "$SCRIPT"
grep -Fq 'REGISTRATION_KIND=' "$SCRIPT"
grep -Fq 'SWAP_PHASE=PREPARED' "$SCRIPT"
grep -Fq 'SWAP_PHASE=MUTATING' "$SCRIPT"
grep -Fq 'SWAP_PHASE=TARGET' "$SCRIPT"
grep -Fq 'inspect_manual_journal_v1()' "$SCRIPT"
grep -Fq 'inspect_manual_journal_v2()' "$SCRIPT"
grep -Fq 'inspect_manual_journal_v3()' "$SCRIPT"
grep -Fq '1) inspect_manual_journal_v1 "$kind" "$marker"' "$SCRIPT"
grep -Fq '2) inspect_manual_journal_v2 "$kind" "$marker"' "$SCRIPT"
grep -Fq 'rollback_root_helper system "$recovery_id"' "$SCRIPT"
grep -Fq 'rollback_root_helper systemless "$recovery_id"' "$SCRIPT"
grep -Fq 'rollback_root_helper_v3 "$recovery_kind"' "$SCRIPT"
grep -Fq 'LEGACY_V1_SYSTEM_RC_SHA256="b42a66ff435a830390c7f04e66ffa252e3bf4027e68c72a29002df4886f8d4f4"' "$SCRIPT"
grep -Fq 'LEGACY_V1_SYSTEMLESS_SERVICE_SHA256="60ff22aa9b38483cbffd95a653d804d0d9abf682e1b952e8b4519d5c0f3f9493"' "$SCRIPT"
grep -Fq '[ "$transaction_id" != legacy ] || target_registration=' "$SCRIPT"

for field in LIVE_CANONICAL LIVE_SYSTEM_BIN LIVE_SYSTEM_RC LIVE_VENDOR_RC \
  LIVE_SYSTEMLESS_BIN LIVE_SYSTEMLESS_SERVICE LIVE_LEGACY_BIN LIVE_LEGACY_RC; do
  grep -Fq "snapshot $field " "$SCRIPT"
  grep -Fq "recorded $field " "$SCRIPT"
  grep -Fq "recorded_live $field " "$SCRIPT"
done
grep -Fq '[ "${actual%% *}" = "$expected" ] || exit 1' "$SCRIPT"
if grep -Eq '/data/local/tmp/hapaneld-helper\.(expected|actual)' "$SCRIPT"; then
  echo "installer uses predictable root temporary paths for recovery verification" >&2
  exit 1
fi
grep -Fq 'echo $$ > "$lock/pid"' "$SCRIPT"
grep -Fq "''|*[!0-9]*) echo TRANSACTION_BUSY; exit 75" "$SCRIPT"
state_line="$(grep -nF 'manual_journal_state="$(run_root_locked' "$SCRIPT" | head -1 | cut -d: -f1)"
selection_line="$(grep -nF "out=\"\$(run_root '" "$SCRIPT" | tail -1 | cut -d: -f1)"
probe_stage_line="$(grep -nF 'push "$BIN" "$PROBE_STAGING_PATH"' "$SCRIPT" | head -1 | cut -d: -f1)"
[ "$probe_stage_line" -lt "$state_line" ]
[ "$state_line" -lt "$selection_line" ]
grep -Fq 'push "$HERE/hapaneld-helper.rc" "$RC_STAGING_PATH"' "$SCRIPT"
grep -Fq 'push "$SVC" "$SVC_STAGING_PATH"' "$SCRIPT"
if grep -Fq 'push "$BIN" /data/local/tmp/hapaneld-helper' "$SCRIPT"; then
  echo "installer reintroduced a shared helper staging path" >&2
  exit 1
fi
grep -Fq 'STALE_SYSTEM_TRANSACTION\ *)' "$SCRIPT"
grep -Fq 'rollback_root_helper system "$recovery_id" "$recovery_build" "$recovery_helper" "$recovery_service"' "$SCRIPT"
grep -Fq 'STALE_SYSTEMLESS_TRANSACTION\ *)' "$SCRIPT"
grep -Fq 'rollback_root_helper systemless "$recovery_id" "$recovery_build" "$recovery_helper" "$recovery_service"' "$SCRIPT"
grep -Fq 'both standalone root-helper recovery journals are present' "$SCRIPT"
grep -Fq 'COMPANIONCAPS 1 BACKUP RESTORE STATUS JOURNAL' "$SCRIPT"
grep -Fq 'wait_for_helper_reply COMPANIONCAPS' "$SCRIPT"
grep -Fq 'wait_for_helper_reply BUILDID "BUILDID $BUILD_ID" "$INSTALL_KIND"' "$SCRIPT"
# The identity the replacement daemon must answer with comes from the binary this run stages, so it is
# read out of those bytes and never from the sources beside the running script — `HAPANELD_HELPER_DIST_DIR`
# can point the two at different builds. That extraction must also be the thing that stops the run: it
# has to happen before anything is pushed to the panel, or the refusal arrives after the mutation it
# exists to prevent.
grep -Fq 'if ! BUILD_ID="$(extract_helper_build_id "$BIN")"; then' "$SCRIPT"
# Whole record first, validity second. The shipped record is newline-terminated, so collection must
# run to the end of the line. Any narrower pattern reports a PREFIX of the record as the identity:
# `BUILDID [0-9a-f]{64}` truncates a longer hex run, and an identity-alphabet class truncates
# `…<64 hex>!garbage` to its leading 64. Both manufacture an answer the artifact never stated.
#
# Every stage reads in the C locale. `.` is defined over characters, so a multibyte locale decides
# what an invalid byte sequence is, and in a stripped binary those are ordinary payload — a reader
# that declines to match one ends the record early and truncates it exactly as a narrow pattern
# would. Under a `.UTF-8` ambient locale the unpinned collection really does drop a trailing
# `\377junk` and accept the leading 64, so the non-text-suffix fixture is what proves this
# behaviourally; these lines pin the mechanism so it cannot be removed on a host that happens to
# agree with the C locale.
grep -Fq "LC_ALL=C tr '\\0' '\\n' < \"\$file\"" "$SCRIPT"
grep -Fq "LC_ALL=C grep -aoE 'BUILDID .*'" "$SCRIPT"
grep -Fq "LC_ALL=C grep -c '^BUILDID '" "$SCRIPT"
grep -Fq "LC_ALL=C sed -nE 's/^BUILDID ([0-9a-f]{64})\$/\\1/p'" "$SCRIPT"
grep -Fq "LC_ALL=C grep -Ec '^[0-9a-f]{64}\$'" "$SCRIPT"
grep -Fq "[ \"\$records\" = 'BUILDID ' ] || return 1" "$SCRIPT"
grep -Fq "LC_ALL=C grep -aoE '^[0-9a-f]{64}\$'" "$SCRIPT"
if grep -Eq "grep -aoE 'BUILDID (\[[^]]*\][*+]?|\[0-9a-f\]\{64\})'" "$SCRIPT"; then
  echo "installer collects the helper identity with a bounded match, which truncates a longer record" >&2
  exit 1
fi
if grep -Fq '"$HERE/source-id.sh"' "$SCRIPT"; then
  echo "installer takes its helper identity from checkout sources rather than the staged binary" >&2
  exit 1
fi
identity_line="$(grep -nF 'if ! BUILD_ID="$(extract_helper_build_id "$BIN")"; then' "$SCRIPT" | head -1 | cut -d: -f1)"
[ "$identity_line" -lt "$probe_stage_line" ]
# The refusal must also precede every privilege transition. Which helper this run would install is a
# pure function of local files plus one unprivileged property read, so a run that cannot state one
# identity must never have restarted adbd as root or raised an on-panel root prompt to find that out.
adb_root_line="$(grep -nF 'adb -s "$TARGET" root >/dev/null 2>&1 || true' "$SCRIPT" | head -1 | cut -d: -f1)"
su_probe_line="$(grep -nF 'if ! probe_su; then' "$SCRIPT" | head -1 | cut -d: -f1)"
[ "$identity_line" -lt "$adb_root_line" ]
[ "$identity_line" -lt "$su_probe_line" ]
grep -Fq 'run_root '"'"'exec '"'"'"$helper_path"'"'"' --request '"'"'"$command"' "$SCRIPT"
grep -Fq '.helper-manual-probe-$TRANSACTION_ID' "$SCRIPT"
grep -Fq 'wait_for_helper_reply PING OK "$install_kind" "$probe_path"' "$SCRIPT"
grep -Fq 'toybox sha256sum' "$SCRIPT"
grep -Fq 'echo STALE_${kind}_TRANSACTION legacy "$target_build" "$target_helper"' "$SCRIPT"
grep -Fq 'echo STALE_${kind}_TRANSACTION "$transaction_id" "$target_build" "$target_helper" "$target_service"' "$SCRIPT"

install_body="$(sed -n '/^# Select a verified boot-registration route/,/^if ! wait_for_helper_reply COMPANIONCAPS/p' "$SCRIPT")"
retirement_line="$(grep -nF 'while pidof hapaneld-helper' <<<"$install_body" | cut -d: -f1)"
identity_fence_line="$(grep -nF 'recorded_live LIVE_CANONICAL' <<<"$install_body" | cut -d: -f1)"
replacement_line="$(grep -nF '"$candidate" --replacement-safe' <<<"$install_body" | cut -d: -f1)"
rename_line="$(grep -nF 'mv -f "$candidate" /data/local/hapaneld-helper' <<<"$install_body" | cut -d: -f1)"
[ -n "$retirement_line" ] && [ -n "$identity_fence_line" ] &&
  [ -n "$replacement_line" ] && [ -n "$rename_line" ] &&
  [ "$retirement_line" -lt "$identity_fence_line" ] &&
  [ "$identity_fence_line" -lt "$replacement_line" ] &&
  [ "$replacement_line" -lt "$rename_line" ]
grep -Fq 'live helper topology changed after the standalone snapshot' <<<"$install_body"
grep -Fq '[ "$replacement_status" -eq 3 ] && [ "$replacement_reply" = GUARD_ARMED ]' <<<"$install_body"
grep -Fq 'echo REPLACEMENT_AUTHORITY_ACTIVE' <<<"$install_body"
grep -Fq 'echo GUARD_ARMED_ROLLBACK' <<<"$install_body"
grep -Fq 'Guard DB authority is armed; the prior helper topology was restored' <<<"$install_body"
grep -Fq 'APK-coupled helper replacement custody refused standalone helper replacement' <<<"$install_body"
authority_hold="$(sed -n '/\*REPLACEMENT_AUTHORITY_ACTIVE\*)/,/;;/p' <<<"$install_body")"
[ -n "$authority_hold" ]
! grep -Fq 'rollback_root_helper_v3' <<<"$authority_hold"
guard_rollback="$(sed -n '/\*GUARD_ARMED_ROLLBACK\*)/,/;;/p' <<<"$install_body")"
grep -Fq 'rollback_root_helper_v3' <<<"$guard_rollback"

rollback_v3="$(sed -n '/^rollback_root_helper_v3()/,/^finalize_root_helper_rollback_v3()/p' "$SCRIPT")"
for custody in \
    /data/local/.hapaneld-helper.new \
    /data/local/.hapaneld-helper.previous \
    /data/local/.hapaneld-helper.previous.tmp \
    /data/local/.hapaneld-guard-db/replacement.v1 \
    /data/local/.hapaneld-guard-db/.replacement.v1.tmp; do
  [ "$(grep -Fc "$custody" "$SCRIPT")" -ge 3 ]
done
grep -Fq 'echo ROLLBACK_REPLACEMENT_AUTHORITY_ACTIVE' <<<"$rollback_v3"
rollback_retirement_line="$(grep -nF 'while pidof hapaneld-helper' <<<"$rollback_v3" | cut -d: -f1)"
rollback_recheck_line="$(grep -nF 'phase_state_known || { echo ROLLBACK_UNKNOWN; exit 0; }' <<<"$rollback_v3" | tail -1 | cut -d: -f1)"
rollback_restore_line="$(grep -nF 'restore LIVE_CANONICAL /data/local/hapaneld-helper 700' <<<"$rollback_v3" | cut -d: -f1)"
[ -n "$rollback_retirement_line" ] && [ -n "$rollback_recheck_line" ] && [ -n "$rollback_restore_line" ] &&
  [ "$rollback_retirement_line" -lt "$rollback_recheck_line" ] &&
  [ "$rollback_recheck_line" -lt "$rollback_restore_line" ]

# Every historical and v3 rollback publishes through an authenticated same-directory temporary.
# The temporary is exact and durable before atomic rename; the parent is made durable afterwards.
[ "$(grep -Fc 'temporary="$live".hapaneld-manual-"$transaction_id".restore' "$SCRIPT")" = 5 ]
[ "$(grep -Fc 'sync_path "$temporary"' "$SCRIPT")" = 3 ]
[ "$(grep -Fc 'mv -f "$temporary" "$live"' "$SCRIPT")" = 3 ]
[ "$(grep -Fc 'expected=$1; hash_path=$2' "$SCRIPT")" = 2 ]
! grep -Fq 'expected=$1; live=$2' "$SCRIPT"
[ "$(grep -Ec '^[[:space:]]+recorded_exact\(\)' "$SCRIPT")" = 3 ]
[ "$(grep -Ec '^[[:space:]]+publish_rollback_phase\(\)' "$SCRIPT")" = 3 ]
[ "$(grep -Fc 'echo ROLLBACK_PHASE=PUBLISHING >> "$phase_tmp"' "$SCRIPT")" = 3 ]
[ "$(grep -Fc 'sync_path "$phase_tmp"' "$SCRIPT")" = 3 ]
[ "$(grep -Fc 'mv -f "$phase_tmp" "$marker"' "$SCRIPT")" = 3 ]
! grep -Fq 'cp -p "$recovery" "$live"' "$SCRIPT"
! grep -Fq 'cp -p "$snapshot" "$live"' "$SCRIPT"
grep -Fq 'if [ -e "$live" ] || [ -L "$live" ]; then' "$SCRIPT"
! grep -Fq '[ ! -e "$live" ] ;' "$SCRIPT"
! grep -Fq '[ ! -e "$snapshot" ] ;' "$SCRIPT"

# One same-session success launch. The init file and generated service.d script each contain one
# boot-time launch, but the transaction itself must not start init and then race a direct fallback.
[ "$(grep -Fxc '  /data/local/hapaneld-helper --supervise >/dev/null 2>&1 &' <<<"$install_body")" = 1 ]
! grep -Fq 'start hapaneld_helper' <<<"$install_body"
! grep -Eq '/(system/bin|data/adb/hapaneld)/hapaneld-helper --supervise >/dev/null 2>&1 &' <<<"$install_body"

grep -Fq 'REGISTRATION_PATH=/system/etc/init/hapaneld-helper.rc' "$SCRIPT"
grep -Fq 'REGISTRATION_PATH=/vendor/etc/init/hapaneld-helper.rc' "$SCRIPT"
grep -Fq 'REGISTRATION_PATH=/data/adb/service.d/hapaneld-helper.sh' "$SCRIPT"
service_body="$(sed -n "/cat > \"\$SVC\" <<'SVCEOF'/,/^SVCEOF$/p" "$SCRIPT")"
grep -Fq '/data/local/hapaneld-helper --supervise >/dev/null 2>&1 &' <<<"$service_body"
! grep -Eq '/(system/bin|data/adb/hapaneld)/hapaneld-helper' <<<"$service_body"

grep -Fq 'wait_for_helper_reply GUARDCAPS "OK GUARDCAPS 1 PREPARE DEFINE STREAM ACTION HEALTH REFUSAL STATUS EVIDENCE CANCEL RETIRE JOURNAL AUTONOMOUS SUPERVISED TERMINAL_RETIRE"' "$SCRIPT"
grep -Fq 'wait_for_helper_reply GUARDSTATUS "OK GUARDSTATUS 0 EMPTY NONE NONE NONE NONE 0 0 0 NONE NONE 0 0"' "$SCRIPT"
grep -Fq 'wait_for_helper_reply BUILDID "BUILDID $BUILD_ID"' "$SCRIPT"

# Device-executable request paths stay an explicit fixed allowlist.
grep -Fq '/data/local/hapaneld-helper|/data/adb/hapaneld/.helper-manual-probe-[0-9a-f]*|/data/local/.hapaneld-helper-manual-probe-[0-9a-f]*)' "$SCRIPT"

grep -Fq 'PROBE_STAGING_PATH="/data/local/tmp/hapaneld-helper.probe-$TRANSACTION_ID"' "$SCRIPT"
if grep -Fq '/data/local/tmp/hapaneld-helper --supervise' "$SCRIPT"; then
  echo "shell-writable adb staging is executable as the live daemon" >&2
  exit 1
fi
if grep -Fq 'exec {' "$SCRIPT"; then
  echo "installer uses dynamic file descriptors unsupported by Bash 3.2" >&2
  exit 1
fi
if grep -Eq '(^|[[:space:]])seq([[:space:]]|$)' "$SCRIPT"; then
  echo "installer depends on non-default macOS seq" >&2
  exit 1
fi
if grep -Eq '[[:space:]]\+[[:space:]]+/' "$SCRIPT"; then
  echo "installer contains a literal plus argument between fixed cleanup paths" >&2
  exit 1
fi

fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
printf trusted > "$fixture/source"
expected="$(sha256sum "$fixture/source" | awk '{print $1}')"
printf tampered > "$fixture/staged"
! sha256sum "$fixture/staged" | grep -q "^$expected"

echo "helper installer security contract passed"
