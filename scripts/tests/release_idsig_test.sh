#!/usr/bin/env bash
# Exercise the pinned real apksigner so its V4 sidecar cannot drift out of the
# exact release inventory unnoticed by the fixture-based workflow tests.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUILD_TOOLS="${ANDROID_HOME:?ANDROID_HOME is required}/build-tools/36.0.0"
UNSIGNED_APK="$ROOT/app/build/outputs/apk/release/app-release-unsigned.apk"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

for tool in aapt apksigner zipalign; do
  if [ ! -x "$BUILD_TOOLS/$tool" ]; then
    printf 'Missing pinned Android Build-Tools executable: %s\n' "$BUILD_TOOLS/$tool" >&2
    exit 1
  fi
done
if [ ! -f "$UNSIGNED_APK" ] || [ -L "$UNSIGNED_APK" ]; then
  printf 'Release APK fixture is not one regular nofollow file: %s\n' "$UNSIGNED_APK" >&2
  exit 1
fi

KEYSTORE="$TMP/test-release.p12"
PASSWORD=release-contract-password
SIGNED_APK="$TMP/ha-paneld-v0.0.0-release-contract.apk"
IDSIG="$SIGNED_APK.idsig"

keytool -genkeypair \
  -alias release-contract \
  -keyalg RSA \
  -keysize 2048 \
  -dname 'CN=ha-paneld V4 release contract' \
  -validity 2 \
  -storetype PKCS12 \
  -keystore "$KEYSTORE" \
  -storepass "$PASSWORD" \
  -keypass "$PASSWORD" \
  -noprompt >/dev/null 2>&1

"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-pass "pass:$PASSWORD" \
  --ks-key-alias release-contract \
  --key-pass "pass:$PASSWORD" \
  --out "$SIGNED_APK" \
  "$UNSIGNED_APK"

if [ ! -f "$SIGNED_APK" ] || [ -L "$SIGNED_APK" ] || \
   [ ! -f "$IDSIG" ] || [ -L "$IDSIG" ]; then
  printf 'Pinned apksigner did not create one APK and one regular V4 sidecar.\n' >&2
  exit 1
fi
idsig_size="$(stat --format='%s' "$IDSIG")"
if [ "$idsig_size" -le 0 ] || [ "$idsig_size" -gt 1048576 ]; then
  printf 'Pinned apksigner V4 sidecar size is outside the release bound: %s\n' "$idsig_size" >&2
  exit 1
fi

"$BUILD_TOOLS/apksigner" verify \
  --v4-signature-file "$IDSIG" \
  --verbose \
  --print-certs \
  "$SIGNED_APK" >/dev/null

CORRUPT_IDSIG="$TMP/corrupt.idsig"
cp "$IDSIG" "$CORRUPT_IDSIG"
printf '\377' | dd of="$CORRUPT_IDSIG" bs=1 seek=0 count=1 conv=notrunc status=none
if "$BUILD_TOOLS/apksigner" verify \
  --v4-signature-file "$CORRUPT_IDSIG" \
  "$SIGNED_APK" >"$TMP/corrupt.out" 2>"$TMP/corrupt.err"; then
  printf 'Pinned apksigner accepted a corrupt V4 sidecar.\n' >&2
  exit 1
fi
"$BUILD_TOOLS/zipalign" -c -P 16 4 "$SIGNED_APK"
badging="$("$BUILD_TOOLS/aapt" dump badging "$SIGNED_APK")"
grep -Fq "package: name='io.github.maxlyth.hapaneld'" <<<"$badging"
printf 'Real apksigner V4 sidecar contract passed (%s bytes).\n' "$idsig_size"
