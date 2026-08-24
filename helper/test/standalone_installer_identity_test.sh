#!/usr/bin/env bash
# Which helper the standalone installer promises the panel will be running afterwards.
#
# `helper/install-daemon.sh` stages `$HAPANELD_HELPER_DIST_DIR/<abi>/hapaneld-helper` and then, after
# the privileged swap, requires the daemon answering the socket to state that exact identity. It used
# to take that expectation from `helper/source-id.sh` — the sources sitting beside the running script.
# For the documented flow (`./helper/build.sh` writes `helper/dist` from those same sources) the two
# answers are identical, which is precisely why the defect was invisible: override the dist directory
# and the bytes come from one build while the expectation still describes another, so a CORRECTLY
# installed foreign helper fails its identity check and a good install is rolled back and reported as
# a build-identity failure. `scripts/provision.sh` had the same defect on its own staging path and
# reads the identity out of the artifact it stages for exactly this reason.
#
# The record's terminator is not fixed across builds — here `dispatch.c` puts the newline inside the
# replied literal, or store a bare `BUILD_ID_RECORD[]` and append the newline at reply time, leaving
# only a NUL in the artifact. Both forms have shipped in this project. Cases below cover both, because a parser
# that assumes either one silently reads a truncated prefix on the other.
#
# So every case here overrides the dist directory with a build whose stamped identity is deliberately
# NOT the checkout's, and asserts that the installer is judged by the bytes it is about to stage:
# a valid foreign build installs against its own identity, and a build that cannot state exactly one
# identity is refused before anything is staged, replaced or privileged.
#
# What this does not cover: device-side file identity, permissions and operation ordering stay in
# install_daemon_security_test.sh, and transaction/lease/rollback behaviour in
# standalone_installer_transaction_test.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
FIXTURES="$ROOT/scripts/tests/fixtures"
INSTALLER="$ROOT/helper/install-daemon.sh"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# The real artifacts. `make test` builds both before running this file; the identities are the ones
# helper/Makefile compiles into them. They exist because every synthetic mock below was green while an
# earlier parser could not read an actual artifact at all: the record's terminator is a property of
# how `version.c`/`dispatch.c` store it, and a mock written with `printf '…\n'` cannot notice when that
# changes. The bytes the project actually compiles are therefore part of the fixture set.
REAL_OLD="$ROOT/helper/build/hapaneld-helper-old"
REAL_NEW="$ROOT/helper/build/hapaneld-helper-new"
REAL_OLD_BUILD_ID=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
REAL_NEW_BUILD_ID=bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb
for real in "$REAL_OLD" "$REAL_NEW"; do
  if [ ! -f "$real" ]; then
    echo "missing $real — build it first: make -C helper build/$(basename "$real")" >&2
    exit 1
  fi
done

# The checkout's own identity. Nothing the installer sends to the panel may contain it, because the
# staged bytes never came from this checkout.
CHECKOUT_BUILD_ID="$(PATH=/usr/bin:/bin "$ROOT/helper/source-id.sh")"
FOREIGN_BUILD_ID=facade00facade00facade00facade00facade00facade00facade00facade00
OTHER_BUILD_ID=0f1e2d3c4b5a69780f1e2d3c4b5a69780f1e2d3c4b5a69780f1e2d3c4b5a6978
for id in "$FOREIGN_BUILD_ID" "$OTHER_BUILD_ID" "$REAL_OLD_BUILD_ID" "$REAL_NEW_BUILD_ID"; do
  if [ "$id" = "$CHECKOUT_BUILD_ID" ]; then
    echo "a fixture identity collides with the checkout identity; this file would prove nothing" >&2
    exit 1
  fi
done

mkdir -p "$TMP/dist/arm64-v8a" "$TMP/state"
export PATH="/usr/bin:/bin"
export MOCK_TARGET="panel.test:5555"
export MOCK_CALL_LOG="$TMP/calls.log"
export MOCK_STATE_DIR="$TMP/state"
export MOCK_ABI=arm64-v8a
export MOCK_ROOT=1
export MOCK_ADB_ROOT=1
export MOCK_SYSTEM_WRITABLE=1
export HAPANELD_HELPER_DIST_DIR="$TMP/dist"

failures=0
check() {
  local what="$1"
  if [ "$2" = ok ]; then
    printf '  ok   %s\n' "$what"
  else
    printf '  FAIL %s\n' "$what"
    sed 's/^/       | /' "$TMP/out.txt"
    failures=$((failures + 1))
  fi
}
assert() {
  local what="$1"; shift
  if "$@"; then check "$what" ok; else check "$what" bad; fi
}
refute() {
  local what="$1"; shift
  if "$@"; then check "$what" bad; else check "$what" ok; fi
}

# `stage <content...>` writes the mock dist binary line by line; `run_installer` drives the real
# installer against the shared adb fixture and captures its status.
stage() {
  printf '%s\n' "$@" > "$TMP/dist/arm64-v8a/hapaneld-helper"
}
run_installer() {
  : > "$MOCK_CALL_LOG"
  rm -rf "$MOCK_STATE_DIR"; mkdir -p "$MOCK_STATE_DIR"
  set +e
  PATH="$FIXTURES:/usr/bin:/bin" bash "$INSTALLER" "$MOCK_TARGET" > "$TMP/out.txt" 2>&1
  INSTALLER_STATUS=$?
  set -e
}

# An identity the installer cannot read from the bytes must stop the run before the panel is touched:
# no helper pushed, no staging path created, no journal written, no file replaced.
#
# It must also stop before the panel is asked for PRIVILEGE. Which helper this run would install is
# decided from local files and one unprivileged property read, so a refusal owes the panel nothing:
# restarting adbd as root, or raising an on-screen root prompt, for a run that was never going to
# proceed is a cost paid on someone's wall panel for a defect on this host.
assert_untouched_panel() {
  local what="$1"
  refute "$what: nothing is pushed to the panel" grep -Eq '^adb .* push ' "$MOCK_CALL_LOG"
  refute "$what: no helper staging path is created" grep -Fq 'hapaneld-helper.probe-' "$MOCK_CALL_LOG"
  refute "$what: no recovery journal is written" grep -Fq 'TARGET_BUILD_ID=' "$MOCK_CALL_LOG"
  refute "$what: no live helper file is replaced" grep -Fq 'hapaneld-helper.new' "$MOCK_CALL_LOG"
  refute "$what: adbd is never restarted as root" grep -Eq '^adb .* root$' "$MOCK_CALL_LOG"
  refute "$what: the panel is never asked for root" grep -Eq '^adb .* shell (su |id$)' "$MOCK_CALL_LOG"
}

stage_real() {
  cp "$1" "$TMP/dist/arm64-v8a/hapaneld-helper"
}
stage_nul() {
  printf 'mock helper\nBUILDID %s\0%s\n' "$1" "${2:-trailing payload}" \
    > "$TMP/dist/arm64-v8a/hapaneld-helper"
}

echo "== standalone installer helper identity =="

# 0. REAL COMPILED ARTIFACTS, both of them, each judged by its own stamped identity. A parser that
#    cannot read these is broken no matter how many mocks agree with it.
for real in "$REAL_OLD:$REAL_OLD_BUILD_ID" "$REAL_NEW:$REAL_NEW_BUILD_ID"; do
  real_path="${real%%:*}"; real_expected="${real##*:}"
  stage_real "$real_path"
  MOCK_HELPER_BUILD_ID="$real_expected" run_installer
  assert "the real $(basename "$real_path") ELF installs against its own stamped identity" \
    test "$INSTALLER_STATUS" = 0
  assert "the journal records the real artifact's identity" \
    grep -Fq "TARGET_BUILD_ID=$real_expected" "$MOCK_CALL_LOG"
  refute "the checkout's source identity never reaches the panel" \
    grep -Fq "$CHECKOUT_BUILD_ID" "$MOCK_CALL_LOG"
done

# 0b. A record whose terminator is a NUL rather than a newline is the other layout this project has
#     shipped, and it must read identically. Bytes after that NUL are not part of the record.
stage_nul "$FOREIGN_BUILD_ID"
MOCK_HELPER_BUILD_ID="$FOREIGN_BUILD_ID" run_installer
assert "a NUL-terminated record reads the same as a newline-terminated one" test "$INSTALLER_STATUS" = 0
assert "bytes after the record's NUL do not disqualify it" \
  grep -Fq "TARGET_BUILD_ID=$FOREIGN_BUILD_ID" "$MOCK_CALL_LOG"

# 0c. A NUL INSIDE the identity is the opposite case. The record ended there, so it never stated 64
#     hex; reading past it would splice two unrelated runs of bytes into an answer.
printf 'mock helper\nBUILDID %s\0%s\n' "${FOREIGN_BUILD_ID:0:32}" "${FOREIGN_BUILD_ID:32}" \
  > "$TMP/dist/arm64-v8a/hapaneld-helper"
MOCK_HELPER_BUILD_ID="$FOREIGN_BUILD_ID" run_installer
assert "an identity interrupted by a NUL is refused" test "$INSTALLER_STATUS" != 0
assert "the interrupted identity is named as an identity failure" \
  grep -Fq 'does not state a single valid build identity' "$TMP/out.txt"
assert_untouched_panel "NUL-interrupted identity"

# 1. THE CASE THE FIX EXISTS FOR. The dist directory is overridden with a valid foreign build, and the
#    panel's daemon answers that foreign build's identity — which is what a correct install of these
#    bytes looks like. Against the old expectation (the checkout's sources) this exact run failed its
#    post-swap identity check and rolled a good install back.
stage 'mock helper' "BUILDID $FOREIGN_BUILD_ID"
MOCK_HELPER_BUILD_ID="$FOREIGN_BUILD_ID" run_installer
assert "a valid foreign distribution installs against its own stamped identity" \
  test "$INSTALLER_STATUS" = 0
assert "the journal records the staged binary's identity" \
  grep -Fq "TARGET_BUILD_ID=$FOREIGN_BUILD_ID" "$MOCK_CALL_LOG"
assert "the replacement daemon is asked to state its identity after the swap" \
  grep -Fq -- '--request BUILDID' "$MOCK_CALL_LOG"
refute "the checkout's source identity never reaches the panel" \
  grep -Fq "$CHECKOUT_BUILD_ID" "$MOCK_CALL_LOG"

# 1b. The same bytes against a panel answering the CHECKOUT's identity must fail. Without this the
#     case above would still pass if the expectation quietly came from anywhere that happened to
#     agree with the mock daemon; here the two authorities are forced apart in the other direction.
MOCK_HELPER_BUILD_ID="$CHECKOUT_BUILD_ID" run_installer
assert "a daemon answering the checkout identity is rejected, not accepted" \
  test "$INSTALLER_STATUS" != 0
assert "the mismatch is reported as a build-identity failure" \
  grep -Fq 'failed its exact build-identity check' "$TMP/out.txt"

# 2. A binary that carries no identity record cannot say who it is. Installing it would leave a
#    daemon nobody can recognise afterwards, so the run stops while the panel is still untouched.
stage 'mock helper with no identity record at all'
MOCK_HELPER_BUILD_ID="$FOREIGN_BUILD_ID" run_installer
assert "a distribution with no build identity is refused" test "$INSTALLER_STATUS" != 0
assert "the refusal names the missing identity" \
  grep -Fq 'does not state a single valid build identity' "$TMP/out.txt"
assert "the refusal names the rebuild that fixes it" \
  grep -Fq './helper/build.sh' "$TMP/out.txt"
assert_untouched_panel "no build identity"

# 3. Malformed records are refused rather than parsed loosely. Each of these looks approximately
#    right, and approximately right is what a loose parser turns into a confident wrong answer.
#
#    The last three are the sharpest, and they are one defect wearing three costumes: the record runs
#    on past the identity. A pattern that stops at 64 characters, or at the first character outside
#    some identity alphabet, reads the leading 64 and reports them as what this artifact says it is.
#    That answer was never stated by anything; it was manufactured by where the reader chose to stop,
#    and once the helper is installed nothing distinguishes it from a correct one. The shipped record
#    is newline-terminated, so a record is only whole at the end of its line.
while IFS='|' read -r label malformed; do
  [ -n "$label" ] || continue
  stage 'mock helper' "$malformed"
  MOCK_HELPER_BUILD_ID="$FOREIGN_BUILD_ID" run_installer
  assert "an identity that is $label is refused" test "$INSTALLER_STATUS" != 0
  assert "an identity that is $label is not parsed loosely" \
    grep -Fq 'does not state a single valid build identity' "$TMP/out.txt"
  assert_untouched_panel "$label identity"
done <<EOF
one-character-short|BUILDID ${FOREIGN_BUILD_ID%?}
one-character-long|BUILDID ${FOREIGN_BUILD_ID}0
upper-case|BUILDID $(printf '%s' "$FOREIGN_BUILD_ID" | tr 'a-f' 'A-F')
non-hexadecimal|BUILDID not-a-hex-identity
empty|BUILDID
punctuation-suffixed|BUILDID ${FOREIGN_BUILD_ID}!garbage
separator-suffixed|BUILDID ${FOREIGN_BUILD_ID} and then some
single-punctuation-suffixed|BUILDID ${FOREIGN_BUILD_ID}.
EOF

# 3b. The fourth costume, and the only one that arrives through the ENVIRONMENT rather than through
#     the pattern: a record whose suffix is not text at all. `.` is defined over characters, so a
#     multibyte locale has to decide what an invalid byte sequence is, and in a stripped binary those
#     are ordinary payload. Under a `.UTF-8` locale an unpinned reader stops at one, ends the record
#     early and hands back its leading 64 characters — the same truncation, reached without touching
#     the pattern at all. Written with `printf` because the byte has to be raw to test anything.
printf 'mock helper\nBUILDID %s\377junk\n' "$FOREIGN_BUILD_ID" > "$TMP/dist/arm64-v8a/hapaneld-helper"
MOCK_HELPER_BUILD_ID="$FOREIGN_BUILD_ID" run_installer
assert "an identity suffixed with bytes that are not text is refused" test "$INSTALLER_STATUS" != 0
assert "a non-text suffix is not read as the end of the record" \
  grep -Fq 'does not state a single valid build identity' "$TMP/out.txt"
assert_untouched_panel "non-text suffix"

# 4. Two records is two answers. Choosing either one would be a guess about which build these bytes
#    really are, and the guess is unfalsifiable once the helper is installed.
stage 'mock helper' "BUILDID $FOREIGN_BUILD_ID" 'and again, differently' "BUILDID $OTHER_BUILD_ID"
MOCK_HELPER_BUILD_ID="$FOREIGN_BUILD_ID" run_installer
assert "contradictory identities are refused rather than guessed between" test "$INSTALLER_STATUS" != 0
assert "the contradiction is named" \
  grep -Fq 'does not state a single valid build identity' "$TMP/out.txt"
assert_untouched_panel "contradictory identities"

# 4a. One valid record beside one malformed record is the case a validity-only check waves through:
#     filter for well-formed identities first and exactly one survives, so the artifact looks like it
#     stated a single answer when it actually stated two and one of them was unreadable. Counting
#     records by shape BEFORE judging them is what catches this.
stage 'mock helper' "BUILDID $FOREIGN_BUILD_ID" 'and something that is not one' 'BUILDID zzzz'
MOCK_HELPER_BUILD_ID="$FOREIGN_BUILD_ID" run_installer
assert "a valid record beside an unreadable one is refused" test "$INSTALLER_STATUS" != 0
assert "the second, unreadable record is not silently filtered away" \
  grep -Fq 'does not state a single valid build identity' "$TMP/out.txt"
assert_untouched_panel "valid record beside an unreadable one"

# 4b. Two IDENTICAL records are refused on the same rule. A binary built from these sources carries
#     exactly one, so a second copy means something else produced this artifact and the assumption
#     that the record describes the whole binary no longer holds.
stage 'mock helper' "BUILDID $FOREIGN_BUILD_ID" 'and a second copy' "BUILDID $FOREIGN_BUILD_ID"
MOCK_HELPER_BUILD_ID="$FOREIGN_BUILD_ID" run_installer
assert "a repeated identity record is refused too" test "$INSTALLER_STATUS" != 0
assert "the repeated record is named as an identity failure" \
  grep -Fq 'does not state a single valid build identity' "$TMP/out.txt"
assert_untouched_panel "repeated identity record"

# 4c. The real artifact is a stripped ELF binary, and the record sits in its .rodata surrounded by
#     bytes that are not text. A reader that treats the file as text finds nothing in it and refuses
#     every genuine install, which is a refusal nobody could act on. So one case stages bytes that
#     make the file binary rather than assuming a text mock stands in for one.
printf 'mock helper\0\377\376 payload \0BUILDID %s\n\0\377 more payload\n' "$FOREIGN_BUILD_ID" \
  > "$TMP/dist/arm64-v8a/hapaneld-helper"
MOCK_HELPER_BUILD_ID="$FOREIGN_BUILD_ID" run_installer
assert "the identity is read out of a binary artifact, not just a text one" \
  test "$INSTALLER_STATUS" = 0
assert "the binary artifact's own identity is what the journal records" \
  grep -Fq "TARGET_BUILD_ID=$FOREIGN_BUILD_ID" "$MOCK_CALL_LOG"

# 5. The expectation is read from the ABI-selected binary, not from whatever else the dist holds.
#    A wrong-ABI sibling with a different identity must have no influence.
mkdir -p "$TMP/dist/armeabi-v7a"
printf 'wrong-abi helper\nBUILDID %s\n' "$OTHER_BUILD_ID" > "$TMP/dist/armeabi-v7a/hapaneld-helper"
stage 'mock helper' "BUILDID $FOREIGN_BUILD_ID"
MOCK_HELPER_BUILD_ID="$FOREIGN_BUILD_ID" run_installer
assert "the identity comes from the ABI-selected binary" test "$INSTALLER_STATUS" = 0
refute "a wrong-ABI sibling's identity is never used" \
  grep -Fq "$OTHER_BUILD_ID" "$MOCK_CALL_LOG"
rm -rf "$TMP/dist/armeabi-v7a"

if [ "$failures" != 0 ]; then
  echo "standalone installer identity tests FAILED ($failures)" >&2
  exit 1
fi
echo "standalone installer identity tests passed"
