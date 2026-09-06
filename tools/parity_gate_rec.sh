#!/usr/bin/env bash
# Host parity gate for rec INT8 (#16 numbers, #29 Track 1).
#
# Compares reference (FP) vs quantized model per bucket with
# tools/int8-dev/rec_textcmp (CTC-greedy text-exact + CER-like over the
# calib filelists -- the same comparison the shipped #16 numbers came from).
#
# Inputs:
#   --ncnn-src   ncnn source tree (tools/build_ncnn.sh $OUT/src; provides
#                headers + lib built with the vendored int8 fixes)
#   --ncnn-build host build dir with src/libncnn.a ($OUT/build-host)
#   --ref-dir    reference models: rec_w{64,128,256,480}.param/.bin (FP)
#   --int8-dir   candidate models: rec_w{64,128,256,480}.param/.bin (INT8)
#   --calib      calib dir with filelist_w{64,128,256,480}.txt
#   --vocab      vocab.json (accepted by the harness, unused in scoring)
#
# Gates (from docs/ncnn-conversion.md #16: w480 93.3%/0.007, w256 85%/0.034;
# thresholds held slightly below so a healthy re-quant passes with margin):
#   w480: exact >= 88%, CER <= 0.020
#   w256: exact >= 78%, CER <= 0.060
#   w64/w128: report only (sliced fragments + mid-char cuts pollute these).
set -euo pipefail

NCNN_SRC=""; NCNN_BUILD=""; REF=""; INT8D=""; CALIB=""; VOCAB=""; WIDTHS="480"
while [ $# -gt 0 ]; do
  case "$1" in
    --ncnn-src) NCNN_SRC="$2"; shift 2;;
    --ncnn-build) NCNN_BUILD="$2"; shift 2;;
    --ref-dir) REF="$2"; shift 2;;
    --int8-dir) INT8D="$2"; shift 2;;
    --calib) CALIB="$2"; shift 2;;
    --vocab) VOCAB="$2"; shift 2;;
    # Gate defaults to w480 (the only width that ships, via rec_dyn).
    # Pass --widths 256 (or 64,128,256,480) for extra canaries; w64/w128
    # stay report-only (sliced-fragment pollution).
    --widths) WIDTHS="$2"; shift 2;;
    *) echo "unknown arg: $1" >&2; exit 1;;
  esac
done
for v in NCNN_SRC NCNN_BUILD REF INT8D CALIB VOCAB; do
  [ -n "${!v}" ] || { echo "--${v,,} required" >&2; exit 1; }
done
HERE=$(cd "$(dirname "$0")" && pwd)

g++ -O2 -std=c++17 -fopenmp -o /tmp/rec_textcmp \
  "$HERE/int8-dev/rec_textcmp.cpp" \
  -I"$NCNN_SRC/src" -I"$NCNN_BUILD/src" \
  "$NCNN_BUILD/src/libncnn.a" -lpthread -fopenmp
echo "harness built"

FAIL=0
check() { # bucket exact cer min_exact max_cer
  local W=$1 exact=$2 cer=$3 mine=$4 maxc=$5
  echo "-- w$W: exact=${exact}% CER=${cer} (gate: >=${mine}% / <=${maxc})"
  local ok=1
  python3 -c "import sys; sys.exit(0 if $exact >= $mine and $cer <= $maxc else 1)" || ok=0
  [ "$ok" = 1 ] || { echo "GATE FAIL w$W"; FAIL=1; }
}
for W in $(echo "$WIDTHS" | tr ',' ' '); do
  OUT=$(/tmp/rec_textcmp "$REF/rec_w${W}.param" "$REF/rec_w${W}.bin" \
    "$INT8D/rec_w${W}.param" "$INT8D/rec_w${W}.bin" \
    "$VOCAB" "$CALIB/filelist_w${W}.txt" "$W" 48 2>/dev/null | tail -1)
  echo "$OUT"
  exact=$(echo "$OUT" | sed -n 's/.*exact=[0-9]* (\([0-9.]*\)%).*/\1/p')
  cer=$(echo "$OUT" | sed -n 's/.*CERlike=[0-9]*\/[0-9]*=\([0-9.]*\).*/\1/p')
  case "$W" in
    480) check "$W" "$exact" "$cer" 88 0.020;;
    256) check "$W" "$exact" "$cer" 78 0.060;;
    *) echo "-- w$W report-only (fragment-polluted)";;
  esac
done
[ "$FAIL" = 0 ] && echo "PARITY GATE PASS" || { echo "PARITY GATE FAIL"; exit 1; }
