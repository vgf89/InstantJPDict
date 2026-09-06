#!/usr/bin/env bash
# Rebuild the ncnn tree this project quantizes with and ships.
#
# Reproduces vgf89/ncnn @ 0c9625b0 from pinned upstream + vendored patches:
#   1. Tencent/ncnn @ 6a1bf000 (upstream master tip, PR #6960)
#   2. third_party/ncnn-patches/ncnn-int8-fixes.mbox (2 no-PR int8 fixes)
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
set -euo pipefail

BASE=6a1bf000   # Tencent/ncnn base == vgf89/ncnn fork point
UPSTREAM=https://github.com/Tencent/ncnn.git
HERE=$(cd "$(dirname "$0")/.." && pwd)
MBOX="$HERE/third_party/ncnn-patches/ncnn-int8-fixes.mbox"

OUT=""
SKIP_HOST=0
SKIP_ANDROID=0
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="$2"; shift 2;;
    --skip-host) SKIP_HOST=1; shift;;
    --skip-android) SKIP_ANDROID=1; shift;;
    *) echo "unknown arg: $1" >&2; exit 1;;
  esac
done
[ -n "$OUT" ] || { echo "--out required" >&2; exit 1; }
[ -f "$MBOX" ] || { echo "missing $MBOX" >&2; exit 1; }

SRC="$OUT/src"
if [ ! -d "$SRC" ]; then
  git clone "$UPSTREAM" "$SRC"
fi
git -C "$SRC" fetch --quiet origin "$BASE" 2>/dev/null || true
git -C "$SRC" checkout --quiet "$BASE"
# Idempotent: reset any previous patch application, then apply.
git -C "$SRC" reset --quiet --hard "$BASE"
# Hermetic identity for `git am` (fresh containers have none configured).
git -C "$SRC" config user.email "repro@instantjpdict.local"
git -C "$SRC" config user.name "InstantJPDict repro"
git -C "$SRC" apply --check "$MBOX"
git -C "$SRC" am --quiet "$MBOX"
echo "tree: $(git -C "$SRC" log --oneline -1)"
# am rewrites committer identity, so SHAs differ from the fork; check subjects instead.
# (Compared as variables, not `git log | grep -q`: grep -q can SIGPIPE git
# and trip `pipefail` nondeterministically.)
EXPECT1="fix int8 1x1 conv on flattened 1D blobs (SE branches)"
EXPECT2="fix ConvolutionDepthWise int8 load for scale terms 201/202"
SUBJECTS=$(git -C "$SRC" log --format=%s -2)
echo "$SUBJECTS" | grep -qxF "$EXPECT1" \
  && echo "$SUBJECTS" | grep -qxF "$EXPECT2" \
  && echo "patches applied (subjects verified)" \
  || { echo "ERROR: applied subjects do not match" >&2; exit 1; }

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
  cmake --install "$OUT/build-android-arm64" --prefix "$OUT/android-arm64"
  echo "android lib -> $OUT/android-arm64"
fi
echo OK
