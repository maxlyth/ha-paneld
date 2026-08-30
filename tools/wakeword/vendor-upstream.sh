#!/usr/bin/env bash
# Re-vendor the micro-wake-word native subset and the bundled wake-word models.
#
# The Android build must not touch the network (the release build runs from a git archive), so the
# exact subset of tflite-micro / kissfft / flatbuffers / gemmlowp / ruy that the engine compiles is
# committed under app/src/main/cpp/microwakeword/third_party/. This script makes that subset
# reproducible:
#
#   vendor-upstream.sh apply            copy the files listed in vendor-manifest.txt from the pinned
#                                       upstream tarballs (sha256-verified) into the repo
#   vendor-upstream.sh derive           rebuild vendor-manifest.txt by compiling the engine against
#                                       the full upstream trees (host toolchain plus every Android ABI
#                                       the NDK can target) and collecting the headers actually used
#   vendor-upstream.sh check            re-derive into a temp dir and diff against the committed tree
#
# Bumping an upstream: change the pins below (keep the tflite-micro / flatbuffers / gemmlowp / ruy set
# consistent with tflite-micro's own third_party pins), run `derive`, then `apply`, then commit.
#
# Requires: curl, tar, cmake (>= 3.22), ninja, clang (tflite-micro does not compile under GCC). `derive` additionally uses the
# Android NDK when ANDROID_NDK (or ANDROID_HOME/ndk/<ndkVersion from app/build.gradle.kts>) exists.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
MWW_DIR="$REPO_ROOT/app/src/main/cpp/microwakeword"
THIRD_PARTY="$MWW_DIR/third_party"
MODELS_DEST="$REPO_ROOT/app/src/main/assets/wakeword"
ASSETS_DIR="$REPO_ROOT/app/src/main/assets/wakeword"
MANIFEST="$REPO_ROOT/tools/wakeword/vendor-manifest.txt"
CACHE="${VENDOR_CACHE:-${TMPDIR:-/tmp}/ha-paneld-wakeword-vendor}"

# ---- pins ----------------------------------------------------------------------------------
# name | GitHub repo | commit | tarball sha256
PINS=(
  "tflite-micro tensorflow/tflite-micro 2747abd5c82a95fb1624106a946fc671c31f16e8 6ac0c8ef35f267cf3baf05dc094d4fd158864d4cc830d490b752994e58add8c8"
  "kissfft mborgerding/kissfft 7bce4153c6bc8aba2db0e889e576f9d00505cbe1 7ad1124648a46977b16ddde03bf243bcd52fc452516c57016584ab4b4f2baadc"
  "flatbuffers google/flatbuffers 0100f6a5779831fa7a651e4b67ef389a8752bd9b 85db3520acc4010b21984e2fb5ead3ec0d2c48df8009b614cb73562a82846554"
  "gemmlowp google/gemmlowp fda83bdc38b118cc6b56753bd540caa49e570745 0f990732a0d541be514dfc5c1c45969626e9f349faa851baec01b9a9dcb4ae4b"
  "ruy google/ruy 54774a7a2cf85963777289193629d4bd42de4a59 91993e7eb2aa56e62e9f4abc1158b2b46333574aa81f1a7431b06df80a42b7fa"
  "micro-wake-word-models esphome/micro-wake-word-models 05b65922cc433c9df13e98e32a7fe520758c837e 71176f2e11e81237bbe5ca32351fc09ac54a58ae3e34aa0f3b658da583de51b7"
)
MODELS=(okay_nabu hey_jarvis hey_mycroft alexa)
# Licence files copied verbatim beside each vendored subset.
LICENSE_FILES=(
  "tflite-micro/LICENSE"
  "kissfft/COPYING"
  "kissfft/LICENSES/BSD-3-Clause"
  "flatbuffers/LICENSE"
  "gemmlowp/LICENSE"
  "ruy/LICENSE"
)

log() { printf '%s\n' "$*" >&2; }
die() { log "error: $*"; exit 1; }

fetch_all() {
  mkdir -p "$CACHE/tarballs" "$CACHE/src"
  local entry name repo commit sha tarball
  for entry in "${PINS[@]}"; do
    read -r name repo commit sha <<<"$entry"
    tarball="$CACHE/tarballs/$name-$commit.tar.gz"
    if [ ! -f "$tarball" ]; then
      log "fetching $repo@$commit"
      curl -sSL -o "$tarball.part" "https://github.com/$repo/archive/$commit.tar.gz"
      mv "$tarball.part" "$tarball"
    fi
    printf '%s  %s\n' "$sha" "$tarball" | sha256sum -c --quiet - || die "sha256 mismatch for $name"
    # Keyed by the pin, not just the name: a bumped commit with a warm cache would otherwise reuse the
    # previous extraction and verify the new tarball while vendoring the old source.
    if [ ! -d "$CACHE/src/$name-$commit" ]; then
      rm -rf "$CACHE/src/$name-$commit.tmp"
      mkdir -p "$CACHE/src/$name-$commit.tmp"
      tar -xzf "$tarball" -C "$CACHE/src/$name-$commit.tmp" --strip-components=1
      mv "$CACHE/src/$name-$commit.tmp" "$CACHE/src/$name-$commit"
    fi
    eval "SRC_${name//-/_}=\"\$CACHE/src/\$name-\$commit\""

  done
}

# Lay the full upstream trees out in the vendored directory shape (symlinks; nothing copied).
stage_full_layout() {
  local stage="$1"
  rm -rf "$stage"
  mkdir -p "$stage/kissfft/tools"
  ln -s "${SRC_tflite_micro}" "$stage/tflite-micro"
  ln -s "${SRC_flatbuffers}" "$stage/flatbuffers"
  ln -s "${SRC_gemmlowp}" "$stage/gemmlowp"
  ln -s "${SRC_ruy}" "$stage/ruy"
  # The TFLite micro frontend includes "tools/kiss_fftr.h"; upstream kissfft keeps it at the root.
  local f
  for f in kiss_fft.h kiss_fft_log.h _kiss_fft_guts.h kiss_fft.c; do ln -s "${SRC_kissfft}/$f" "$stage/kissfft/$f"; done
  for f in kiss_fftr.c kiss_fftr.h; do ln -s "${SRC_kissfft}/$f" "$stage/kissfft/tools/$f"; done
  ln -s "${SRC_kissfft}/COPYING" "$stage/kissfft/COPYING"
  ln -s "${SRC_kissfft}/LICENSES" "$stage/kissfft/LICENSES"
}

find_ndk() {
  if [ -n "${ANDROID_NDK:-}" ] && [ -d "$ANDROID_NDK" ]; then printf '%s' "$ANDROID_NDK"; return; fi
  local version sdk
  version="$(sed -n 's/^[[:space:]]*ndkVersion = "\([^"]*\)".*/\1/p' "$REPO_ROOT/app/build.gradle.kts" | head -1)"
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" /opt/android-sdk; do
    if [ -n "$sdk" ] && [ -d "$sdk/ndk/$version" ]; then printf '%s' "$sdk/ndk/$version"; return; fi
  done
}

find_cmake() {
  if command -v cmake >/dev/null 2>&1; then printf 'cmake'; return; fi
  local sdk c
  for sdk in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" /opt/android-sdk; do
    for c in "$sdk"/cmake/*/bin/cmake; do
      if [ -x "$c" ]; then printf '%s' "$c"; return; fi
    done
  done
  die "cmake not found"
}

# Build the engine in a scratch copy of the microwakeword dir whose third_party/ is the full
# upstream layout, then list every third_party file the compiler read.
derive_manifest() {
  local out="$1" work="$2"
  local cmake ndk src build abi
  cmake="$(find_cmake)"
  ndk="$(find_ndk || true)"
  rm -rf "$work"
  mkdir -p "$work"
  src="$work/microwakeword"
  cp -R "$MWW_DIR" "$src"
  rm -rf "$src/third_party"
  stage_full_layout "$src/third_party"
  # A minimal top-level project so the subdirectory builds stand-alone.
  cat >"$work/CMakeLists.txt" <<'CM'
cmake_minimum_required(VERSION 3.22.1)
project(mww_derive C CXX)
add_subdirectory(microwakeword)
CM

  : >"$work/deps.txt"
  local -a configs=("host")
  if [ -n "$ndk" ]; then configs+=("arm64-v8a" "armeabi-v7a" "x86_64"); else log "NDK not found: deriving from the host toolchain only"; fi
  for abi in "${configs[@]}"; do
    build="$work/build-$abi"
    log "configuring $abi"
    if [ "$abi" = host ]; then
      # tflite-micro is written for clang (GCC rejects its placement-new over a private delete).
      local -a hostcc=()
      if command -v clang++ >/dev/null 2>&1; then hostcc=(-DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++); fi
      "$cmake" -G Ninja -S "$work" -B "$build" -DCMAKE_BUILD_TYPE=Release "${hostcc[@]}" >/dev/null
    else
      "$cmake" -G Ninja -S "$work" -B "$build" -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_TOOLCHAIN_FILE="$ndk/build/cmake/android.toolchain.cmake" \
        -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-26 >/dev/null
    fi
    log "building $abi"
    ninja -C "$build" >/dev/null
    # `ninja -t deps` prints every header each object depended on (absolute or build-relative).
    ninja -C "$build" -t deps | sed -n 's/^[[:space:]]\{1,\}//p' >>"$work/deps.txt"
  done

  # Map the paths back to third_party-relative form, resolving the staging symlinks, and add
  # the compiled sources themselves (the CMake file lists them by path).
  {
    grep -F "$src/third_party/" "$work/deps.txt" || true
    grep -oE "\\\$\{[A-Z_]+\}/[^ )]+\.(c|cc)" "$MWW_DIR/CMakeLists.txt" \
      | sed -e "s#\${TFLITE_FRONTEND}#$src/third_party/tflite-micro/tensorflow/lite/experimental/microfrontend/lib#" \
            -e "s#\${TFLM_ROOT}#$src/third_party/tflite-micro#" \
            -e "s#\${TFLM}#$src/third_party/tflite-micro/tensorflow/lite/micro#" \
            -e "s#\${TFL}#$src/third_party/tflite-micro/tensorflow/lite#"
  } | sed -e "s#^$src/third_party/##" -e 's#/\./#/#g' | python3 -c '
import os, sys
seen = set()
for line in sys.stdin:
    p = os.path.normpath(line.strip())
    if p and not p.startswith(".."):
        seen.add(p)
for p in sorted(seen):
    print(p)
' >"$out"
  local n
  n="$(wc -l <"$out")"
  [ "$n" -gt 0 ] || die "derived an empty manifest"
  log "manifest: $n files -> $out"
}

apply_manifest() {
  local manifest="$1" dest="$2" stage="$3"
  rm -rf "$dest"
  mkdir -p "$dest"
  local rel
  while IFS= read -r rel; do
    [ -n "$rel" ] || continue
    [ -f "$stage/$rel" ] || die "manifest entry missing upstream: $rel"
    mkdir -p "$dest/$(dirname "$rel")"
    normalize_text "$stage/$rel" "$dest/$rel"
  done <"$manifest"
  for rel in "${LICENSE_FILES[@]}"; do
    mkdir -p "$dest/$(dirname "$rel")"
    normalize_text "$stage/$rel" "$dest/$rel"
  done
}

# Every vendored file is source or licence text. Line endings become LF and trailing blanks are
# dropped: the repository's admission gate rejects both, and neither changes what the compiler
# sees. `check` applies the same normalisation before comparing, so the committed tree still
# proves byte-for-byte reproducibility from the pinned tarballs.
normalize_text() {
  # The second sed collapses any run of blank lines at end of file (a "new blank line at EOF").
  sed -e 's/\r$//' -e 's/[[:blank:]]*$//' "$1" | sed -e :a -e '/^\n*$/{$d;N;ba' -e '}' >"$2"
}

apply_models() {
  local dest="$1"
  mkdir -p "$dest"
  local m
  for m in "${MODELS[@]}"; do
    cp "${SRC_micro_wake_word_models}/models/v2/$m.tflite" "$dest/$m.tflite"
    cp "${SRC_micro_wake_word_models}/models/v2/$m.json" "$dest/$m.json"
  done
  cp "${SRC_micro_wake_word_models}/LICENSE" "$dest/LICENSE.txt"
}

report() {
  local dir="$1"
  log "$(find "$dir" -type f | wc -l) files, $(du -sk "$dir" | cut -f1) KB in $dir"
}

cmd="${1:-}"
case "$cmd" in
  derive)
    fetch_all
    derive_manifest "$MANIFEST" "$CACHE/derive"
    ;;
  apply)
    fetch_all
    [ -f "$MANIFEST" ] || die "run derive first: $MANIFEST is missing"
    stage_full_layout "$CACHE/stage"
    apply_manifest "$MANIFEST" "$THIRD_PARTY" "$CACHE/stage"
    apply_models "$ASSETS_DIR"
    report "$THIRD_PARTY"
    report "$ASSETS_DIR"
    ;;
  check)
    fetch_all
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT
    derive_manifest "$tmp/manifest.txt" "$CACHE/derive"
    diff -u "$MANIFEST" "$tmp/manifest.txt" || die "manifest drift: run derive + apply"
    stage_full_layout "$CACHE/stage"
    apply_manifest "$MANIFEST" "$tmp/third_party" "$CACHE/stage"
    diff -r "$tmp/third_party" "$THIRD_PARTY" || die "vendored tree drift: run apply"
    # The models and their licence ship in the APK, so they are part of what "matches upstream" has to
    # mean. Verifying only the C++ tree would let a model or a licence drift silently.
    apply_models "$tmp/models"
    m=""
    for m in "${MODELS[@]}"; do
      cmp -s "$tmp/models/$m.tflite" "$MODELS_DEST/$m.tflite" || die "model drift: $m.tflite"
      cmp -s "$tmp/models/$m.json" "$MODELS_DEST/$m.json" || die "model manifest drift: $m.json"
    done
    cmp -s "$tmp/models/LICENSE.txt" "$MODELS_DEST/LICENSE.txt" || die "model licence drift: LICENSE.txt"
    # The KissFFT notice is packaged as an asset because the source it covers is compiled, not shipped.
    grep -q 'KissFFT' "$MODELS_DEST/THIRD_PARTY_LICENSES.txt" \
      || die "packaged third-party notice is missing the KissFFT attribution"
    cmp -s <(sed -n '/^--- KissFFT/,$p' "$MODELS_DEST/THIRD_PARTY_LICENSES.txt" | tail -n +3) \
      "$THIRD_PARTY/kissfft/COPYING" \
      || die "packaged KissFFT licence text does not match the vendored COPYING"
    log "vendored tree, packaged models and licences match the pinned upstreams"
    ;;
  *)
    log "usage: $0 derive|apply|check"
    exit 2
    ;;
esac
