#!/usr/bin/env bash
# Rebuild the ncnn tree this project quantizes with and ships.
#
# Source of truth is the vgf89/ncnn fork at a pinned commit (FORK_PIN).
# Required tree content is enforced by marker grep, not by patch
# application — any tree (pinned or dirty `--src`) missing a marker fails
# fast, so a lib built without e.g. the fused-GELU patch can never ship
# silently again (that exact failure: dense rec garbage, blank collapse).
#
# Required markers and why:
#   - `bottom_blob_3d = bottom_blob_unbordered` (int8 1x1 conv on flattened
#     1D blobs, SE branches)
#   - `int8_scale_term % 100` (ConvolutionDepthWise int8 load, scale 201/202)
#   - `activation_ss` (fused GELU activation type 7 for int8 epilogues —
#     required by rec_dyn.param's 13x `9=7` conv layers, #41)
#
# Outputs:
#   <out>/host/bin/{ncnn2table,ncnn2int8,ncnnoptimize}  (host quantization tools)
#   <out>/android-arm64/{lib/libncnn.a,include/}  (vendored into
#     app/src/main/cpp/ncnn/{lib/arm64-v8a,include})
#
# Requirements: cmake >= 3.22, a C++17 compiler, ANDROID_NDK set for the
# android leg (minSdk 30 -> android-30 platform).
#
# Usage:
#   tools/build_ncnn.sh --out /tmp/ncnn_build [--skip-android | --skip-host]
#   tools/build_ncnn.sh --out /tmp/ncnn_build --src ~/repos/ncnn [--allow-dirty]
#     --src uses an existing tree as-is (for testing fork changes); a dirty
#     tree is refused unless --allow-dirty. Markers are verified either way.
set -euo pipefail

FORK=https://github.com/vgf89/ncnn.git
FORK_PIN=a2b8507f6a3449e80f1c2585a127e3c08e3a9f3f # fork master; bump to follow
HERE=$(cd "$(dirname "$0")/.." && pwd)

OUT=""
SKIP_HOST=0
SKIP_ANDROID=0
SRC_OVERRIDE=""
ALLOW_DIRTY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="$2"; shift 2;;
    --skip-host) SKIP_HOST=1; shift;;
    --skip-android) SKIP_ANDROID=1; shift;;
    --src) SRC_OVERRIDE="$2"; shift 2;;
    --allow-dirty) ALLOW_DIRTY=1; shift;;
    *) echo "unknown arg: $1" >&2; exit 1;;
  esac
done
[ -n "$OUT" ] || { echo "--out required" >&2; exit 1; }

if [ -n "$SRC_OVERRIDE" ]; then
  SRC="$SRC_OVERRIDE"
  [ -d "$SRC/src/layer" ] || { echo "ERROR: $SRC is not an ncnn tree" >&2; exit 1; }
  if [ -n "$(git -C "$SRC" status --porcelain 2>/dev/null)" ]; then
    if [ "$ALLOW_DIRTY" -eq 0 ]; then
      echo "ERROR: $SRC is dirty; refusing (pass --allow-dirty to test local changes)" >&2
      exit 1
    fi
    echo "WARNING: building from dirty tree $SRC" >&2
  fi
  echo "tree: $(git -C "$SRC" log --oneline -1) (override, no checkout)"
else
  SRC="$OUT/src"
  if [ ! -d "$SRC" ]; then
    git clone "$FORK" "$SRC"
  fi
  git -C "$SRC" fetch --quiet origin "$FORK_PIN" 2>/dev/null || true
  git -C "$SRC" checkout --quiet "$FORK_PIN"
  # Pinned builds reset: the tree is always exactly FORK_PIN.
  git -C "$SRC" reset --quiet --hard "$FORK_PIN"
  echo "tree: $(git -C "$SRC" log --oneline -1)"
fi

# Marker gate: every required fix must be present in the tree, pinned or dirty.
# (Compared as variables, not `git log | grep -q`: grep -q can SIGPIPE git
# and trip `pipefail` nondeterministically.)
require_marker() {
  local token="$1" desc="$2"
  if grep -rqF "$token" "$SRC/src/layer"; then
    echo "marker ok: $desc"
  else
    echo "ERROR: missing '$token' ($desc) in $SRC" >&2
    exit 1
  fi
}
require_marker "bottom_blob_3d = bottom_blob_unbordered" "int8 1x1 on flattened 1D (SE)"
require_marker "int8_scale_term % 100" "depthwise int8 scale 201/202"
require_marker "activation_ss" "fused GELU type 7 (rec 9=7)"

if [ "$SKIP_HOST" -eq 0 ]; then
  cmake -S "$SRC" -B "$OUT/build-host" -DCMAKE_BUILD_TYPE=Release \
    -DNCNN_VULKAN=OFF -DNCNN_BUILD_TOOLS=ON \
    -DNCNN_BUILD_EXAMPLES=OFF -DNCNN_BUILD_TESTS=OFF -DNCNN_BUILD_BENCHMARK=OFF
  cmake --build "$OUT/build-host" -j"$(nproc)" --target ncnn2table ncnn2int8 ncnnoptimize
  mkdir -p "$OUT/host/bin"
  cp "$OUT/build-host/tools/quantize/ncnn2table" "$OUT/host/bin/"
  cp "$OUT/build-host/tools/quantize/ncnn2int8" "$OUT/host/bin/"
  cp "$OUT/build-host/tools/ncnnoptimize" "$OUT/host/bin/"
  echo "host tools -> $OUT/host/bin"
fi

if [ "$SKIP_ANDROID" -eq 0 ]; then
  : "${ANDROID_NDK:?set ANDROID_NDK to an r2x NDK root (see runbook)}"
  cmake -S "$SRC" -B "$OUT/build-android-arm64" -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-30 \
    -DNCNN_VULKAN=OFF -DNCNN_BUILD_TOOLS=OFF \
    -DNCNN_BUILD_EXAMPLES=OFF -DNCNN_BUILD_TESTS=OFF -DNCNN_BUILD_BENCHMARK=OFF \
    -DNCNN_SHARED_LIB=OFF
  cmake --build "$OUT/build-android-arm64" -j"$(nproc)" --target ncnn
  mkdir -p "$OUT/android-arm64/lib"
  cp "$OUT/build-android-arm64/src/libncnn.a" "$OUT/android-arm64/lib/"
  # Artifact gate: the fused-GELU code must be compiled in (grep directly on
  # the archive — no pipe, so no pipefail/SIGPIPE hazard — a lib without it
  # mis-infers rec, see marker gate above).
  if grep -q "activation_ss" "$OUT/android-arm64/lib/libncnn.a"; then
    echo "artifact ok: fused GELU compiled into libncnn.a"
  else
    echo "ERROR: libncnn.a lacks fused GELU (activation_ss)" >&2
    exit 1
  fi
  cmake --install "$OUT/build-android-arm64" --prefix "$OUT/android-arm64"
  echo "android lib -> $OUT/android-arm64"
fi
echo OK
