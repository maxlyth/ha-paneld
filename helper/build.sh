#!/usr/bin/env bash
#
# Build the hapaneld-ledd root helper for Android (armeabi-v7a + arm64-v8a), using the same
# version-pinned Docker toolchain image as the app (tools/build). Only Docker is required.
#
#   ./helper/build.sh
#   -> helper/dist/<abi>/hapaneld-ledd
#
# HOST_WORKDIR override: see tools/build/build.sh (same nested/remote-daemon caveat).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/.." && pwd)"
cd "$REPO"

IMAGE="ha-paneld-build:latest"
HOST_WORK="${HOST_WORKDIR:-$REPO}"

echo ">> Building toolchain image ($IMAGE) if missing/changed…"
docker build -t "$IMAGE" tools/build

echo ">> Compiling hapaneld-ledd for both ABIs…"
docker run --rm -v "$HOST_WORK":/work -w /work "$IMAGE" bash -c '
  set -e
  TC=$ANDROID_SDK_ROOT/ndk/27.0.12077973/toolchains/llvm/prebuilt/linux-x86_64/bin
  mkdir -p helper/dist/armeabi-v7a helper/dist/arm64-v8a
  "$TC/armv7a-linux-androideabi30-clang" -O2 -s -o helper/dist/armeabi-v7a/hapaneld-ledd helper/ledd.c
  "$TC/aarch64-linux-android30-clang"    -O2 -s -o helper/dist/arm64-v8a/hapaneld-ledd  helper/ledd.c
'

echo ">> Built:"
find helper/dist -name hapaneld-ledd
