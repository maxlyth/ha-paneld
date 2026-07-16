#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPT="$ROOT/helper/install-daemon.sh"

bash -n "$SCRIPT"
grep -Fq '/data/adb/hapaneld/hapaneld-helper >/dev/null 2>&1 &' "$SCRIPT"
grep -Fq 'chown 0:0 /data/adb/hapaneld/hapaneld-helper.new' "$SCRIPT"
grep -Fq 'chmod 700 /data/adb/hapaneld' "$SCRIPT"
grep -Fq 'mv -f /data/adb/hapaneld/hapaneld-helper.new /data/adb/hapaneld/hapaneld-helper' "$SCRIPT"
grep -Fq 'sha256sum /data/adb/hapaneld/hapaneld-helper.new' "$SCRIPT"
grep -Fq 'sha256sum /system/etc/init/hapaneld-helper.rc.new' "$SCRIPT"
grep -Fq 'sha256sum /data/adb/service.d/hapaneld-helper.sh.new' "$SCRIPT"
grep -Fq 'mv -f /data/adb/service.d/hapaneld-helper.sh.new /data/adb/service.d/hapaneld-helper.sh' "$SCRIPT"
grep -Fq 'NO_SYSTEMLESS_RUNNER' "$SCRIPT"
grep -Fq '.hapaneld-helper-manual-upgrade' "$SCRIPT"
grep -Fq '.helper-manual-upgrade.marker' "$SCRIPT"
grep -Fq 'hapaneld-helper.hapaneld-manual-recovery' "$SCRIPT"
grep -Fq '[ ! -f /system/bin/.hapaneld-helper-upgrade ] && [ ! -f /data/adb/hapaneld/.helper-upgrade.marker ]' "$SCRIPT"
grep -Fq 'incomplete APK-coupled helper upgrade must be recovered by the provisioner first' "$SCRIPT"
grep -Fq 'JOURNAL_VERSION=1' "$SCRIPT"
grep -Fq 'JOURNAL_SCOPE=HELPER_ONLY' "$SCRIPT"
grep -Fq 'TARGET_BUILD_ID=' "$SCRIPT"
grep -Fq 'TARGET_HELPER_SHA256=' "$SCRIPT"
for recovery_field in OLD_BIN OLD_SERVICE LEGACY_BIN LEGACY_SERVICE ALT_BIN ALT_SERVICE; do
  grep -Fq "${recovery_field}_SHA256=" "$SCRIPT"
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
[ "$state_line" -lt "$selection_line" ]
grep -Fq 'STALE_SYSTEM_TRANSACTION)' "$SCRIPT"
grep -Fq 'rollback_root_helper system || fail' "$SCRIPT"
grep -Fq 'STALE_SYSTEMLESS_TRANSACTION)' "$SCRIPT"
grep -Fq 'rollback_root_helper systemless || fail' "$SCRIPT"
grep -Fq 'both standalone root-helper recovery journals are present' "$SCRIPT"
grep -Fq 'COMPANIONCAPS 1 BACKUP RESTORE STATUS JOURNAL' "$SCRIPT"
grep -Fq 'wait_for_helper_reply COMPANIONCAPS' "$SCRIPT"
grep -Fq 'wait_for_helper_reply BUILDID "BUILDID $BUILD_ID"' "$SCRIPT"
grep -Fq 'exec 9<>"/dev/tcp/127.0.0.1/$port"' "$SCRIPT"
grep -Fq "tr -d '\\r'" "$SCRIPT"
if grep -Fq 'exec {' "$SCRIPT"; then
  echo "installer uses dynamic file descriptors unsupported by the macOS Bash 3.2 baseline" >&2
  exit 1
fi
if grep -Eq '(^|[[:space:]])seq([[:space:]]|$)' "$SCRIPT"; then
  echo "installer depends on seq, which is absent from the default macOS command set" >&2
  exit 1
fi

assert_order() {
  local copy="$1" verify="$2" publish="$3" copy_line verify_line publish_line
  copy_line="$(grep -nF "$copy" "$SCRIPT" | head -1 | cut -d: -f1)"
  verify_line="$(grep -nF "$verify" "$SCRIPT" | head -1 | cut -d: -f1)"
  publish_line="$(grep -nF "$publish" "$SCRIPT" | head -1 | cut -d: -f1)"
  [ "$copy_line" -lt "$verify_line" ] && [ "$verify_line" -lt "$publish_line" ]
}
assert_order \
  'cp /data/local/tmp/hapaneld-helper.rc /system/etc/init/hapaneld-helper.rc.new' \
  'sha256sum /system/etc/init/hapaneld-helper.rc.new' \
  'mv -f /system/etc/init/hapaneld-helper.rc.new /system/etc/init/hapaneld-helper.rc'
assert_order \
  'cp /data/local/tmp/hapaneld-helper.svc /data/adb/service.d/hapaneld-helper.sh.new' \
  'sha256sum /data/adb/service.d/hapaneld-helper.sh.new' \
  'mv -f /data/adb/service.d/hapaneld-helper.sh.new /data/adb/service.d/hapaneld-helper.sh'

assert_text_order() {
  local text="$1" first="$2" second="$3" first_line second_line
  first_line="$(grep -nF "$first" <<<"$text" | head -1 | cut -d: -f1)"
  second_line="$(grep -nF "$second" <<<"$text" | head -1 | cut -d: -f1)"
  [ -n "$first_line" ] && [ -n "$second_line" ] && [ "$first_line" -lt "$second_line" ]
}

system_install_body="$(sed -n '/echo "==> \/system is writable/,/^elif printf.*SYSTEMLESS_RUNNER/p' "$SCRIPT")"
assert_text_order "$system_install_body" \
  'cp -p /system/bin/hapaneld-helper /system/bin/hapaneld-helper.hapaneld-manual-recovery' \
  'mv -f /system/bin/.hapaneld-helper-manual-upgrade.new /system/bin/.hapaneld-helper-manual-upgrade'
assert_text_order "$system_install_body" \
  'cmp -s /data/adb/service.d/hapaneld-helper.sh /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery' \
  'sync || exit 1'
assert_text_order "$system_install_body" \
  'sync || exit 1' \
  'mv -f /system/bin/.hapaneld-helper-manual-upgrade.new /system/bin/.hapaneld-helper-manual-upgrade'
assert_text_order "$system_install_body" \
  'mv -f /system/bin/.hapaneld-helper-manual-upgrade.new /system/bin/.hapaneld-helper-manual-upgrade' \
  'rm -f /data/adb/hapaneld/hapaneld-helper /data/adb/service.d/hapaneld-helper.sh'

systemless_install_body="$(sed -n '/echo "==> \/system not rw-remountable/,/^else$/p' "$SCRIPT")"
assert_text_order "$systemless_install_body" \
  'cp -p /data/adb/hapaneld/hapaneld-helper /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery' \
  'mv -f /data/adb/hapaneld/.helper-manual-upgrade.marker.new /data/adb/hapaneld/.helper-manual-upgrade.marker'
assert_text_order "$systemless_install_body" \
  'cmp -s /data/adb/service.d/hapaneld-helper.sh /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery' \
  'sync || exit 1'
assert_text_order "$systemless_install_body" \
  'sync || exit 1' \
  'mv -f /data/adb/hapaneld/.helper-manual-upgrade.marker.new /data/adb/hapaneld/.helper-manual-upgrade.marker'
assert_text_order "$systemless_install_body" \
  'mv -f /data/adb/hapaneld/.helper-manual-upgrade.marker.new /data/adb/hapaneld/.helper-manual-upgrade.marker' \
  'mv -f /data/adb/hapaneld/hapaneld-helper.new /data/adb/hapaneld/hapaneld-helper'

rollback_body="$(sed -n '/^rollback_root_helper()/,/^commit_root_helper_upgrade()/p' "$SCRIPT")"
rollback_system_body="$(sed -n '/^    system)$/,/^    systemless)$/p' <<<"$rollback_body")"
rollback_systemless_body="$(sed -n '/^    systemless)$/,/^    \*)/p' <<<"$rollback_body")"
assert_text_order "$rollback_system_body" \
  '[ "${actual%% *}" = "$expected" ] || exit 1' \
  'rm -f /system/bin/.hapaneld-helper-manual-upgrade || exit 1'
assert_text_order "$rollback_system_body" \
  'rm -f /system/bin/.hapaneld-helper-manual-upgrade || exit 1' \
  'rm -f /system/bin/hapaneld-helper.hapaneld-manual-recovery /system/etc/init/hapaneld-helper.rc.hapaneld-manual-recovery'
assert_text_order "$rollback_systemless_body" \
  '[ "${actual%% *}" = "$expected" ] || exit 1' \
  'rm -f /data/adb/hapaneld/.helper-manual-upgrade.marker || exit 1'
assert_text_order "$rollback_systemless_body" \
  'rm -f /data/adb/hapaneld/.helper-manual-upgrade.marker || exit 1' \
  'rm -f /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery /data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery'

commit_body="$(sed -n '/^commit_root_helper_upgrade()/,/^# Stage the binary/p' "$SCRIPT")"
commit_system_body="$(sed -n '/^    system)$/,/^    systemless)$/p' <<<"$commit_body")"
commit_systemless_body="$(sed -n '/^    systemless)$/,/^    \*)/p' <<<"$commit_body")"
assert_text_order "$commit_system_body" \
  'rm -f /system/bin/.hapaneld-helper-manual-upgrade || exit 1' \
  'rm -f /system/bin/hapaneld-helper.hapaneld-manual-recovery'
assert_text_order "$commit_systemless_body" \
  'rm -f /data/adb/hapaneld/.helper-manual-upgrade.marker || exit 1' \
  'rm -f /data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery'

grep -Fq '/system/bin/hapaneld-ledd.hapaneld-manual-recovery' "$SCRIPT"
grep -Fq '/system/etc/init/hapaneld-ledd.rc.hapaneld-manual-recovery' "$SCRIPT"
grep -Fq '/data/adb/hapaneld/hapaneld-helper.hapaneld-manual-recovery' "$SCRIPT"
grep -Fq '/data/adb/service.d/hapaneld-helper.sh.hapaneld-manual-recovery' "$SCRIPT"

# The exact fail-closed primitive used by each root block must reject content changed after host hashing.
fixture="$(mktemp -d)"
trap 'rm -rf "$fixture"' EXIT
printf trusted > "$fixture/source"
expected="$(sha256sum "$fixture/source" | awk '{print $1}')"
printf tampered > "$fixture/staged"
if sha256sum "$fixture/staged" | grep -q "^$expected"; then
  echo "tampered root staging unexpectedly passed its host digest" >&2
  exit 1
fi

service_body="$(sed -n "/cat > \"\$SVC\" << 'SVCEOF'$/,/^SVCEOF$/p" "$SCRIPT")"
if grep -Fq '/data/local/tmp/hapaneld-helper' <<<"$service_body"; then
  echo "systemless service still executes the shell-writable adb staging path" >&2
  exit 1
fi
grep -Fq '/system/bin/pkill -x hapaneld-helper' <<<"$service_body"

if grep -Eq '^[[:space:]]*/data/local/tmp/hapaneld-helper >/dev/null' "$SCRIPT"; then
  echo "installer still executes the shell-writable adb staging path" >&2
  exit 1
fi

selection_line="$(grep -nF 'NO_SYSTEMLESS_RUNNER' "$SCRIPT" | tail -1 | cut -d: -f1)"
system_swap_line="$(grep -nF 'mv -f /system/bin/hapaneld-helper.new /system/bin/hapaneld-helper' "$SCRIPT" | tail -1 | cut -d: -f1)"
systemless_swap_line="$(grep -nF 'mv -f /data/adb/hapaneld/hapaneld-helper.new /data/adb/hapaneld/hapaneld-helper' "$SCRIPT" | tail -1 | cut -d: -f1)"
[ "$selection_line" -lt "$system_swap_line" ] && [ "$selection_line" -lt "$systemless_swap_line" ]

echo "helper installer security contract passed"
