#!/usr/bin/env bash
# Full-INT8 quantization for PP-OCRv6 rec buckets (#16, #29 Track 1).
#
# Per bucket W in 64/128/256/480, from the FP32 ncnn conversion output:
#   ncnn2table fp32.param fp32.bin filelist_wW.txt table_wW.txt shape=[W,48,3] method=kl type=1
#   <scale sanity gate>
#   ncnn2int8  fp32.param fp32.bin int8_wW.param int8_wW.bin table_wW.txt
#
# Inputs:
#   --fp32-dir  dir with rec_w{64,128,256,480}.param/.bin (FP32 conversion output;
#               MUST be un-quantized — re-quantizing an int8 model reads packed
#               weights as floats and yields a double-quantized all-blank net)
#   --calib     dir with filelist_w{64,128,256,480}.txt (tools/gen_rec_calib_npy.py)
#   --tools     dir with ncnn2table + ncnn2int8 (tools/build_ncnn.sh host leg)
#   --out       output dir (int8 params/bins + tables)
#
# Sanity gate (the #16 double-quant tripwire): weight scales in the KL table
# must be sane magnitudes (e.g. convdw_125_param_0 ~ 869, not 7.6e34).
# Any |scale| > 1e6 fails the run before ncnn2int8.
set -euo pipefail

FP32=""; CALIB=""; TOOLS=""; OUT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --fp32-dir) FP32="$2"; shift 2;;
    --calib) CALIB="$2"; shift 2;;
    --tools) TOOLS="$2"; shift 2;;
    --out) OUT="$2"; shift 2;;
    *) echo "unknown arg: $1" >&2; exit 1;;
  esac
done
[ -n "$FP32" ] && [ -n "$CALIB" ] && [ -n "$TOOLS" ] && [ -n "$OUT" ] \
  || { echo "--fp32-dir --calib --tools --out required" >&2; exit 1; }

TABLE="$TOOLS/ncnn2table"
INT8="$TOOLS/ncnn2int8"
[ -x "$TABLE" ] || { echo "missing $TABLE" >&2; exit 1; }
[ -x "$INT8" ] || { echo "missing $INT8" >&2; exit 1; }
mkdir -p "$OUT"

for W in 64 128 256 480; do
  P="$FP32/rec_w${W}.param"; B="$FP32/rec_w${W}.bin"; F="$CALIB/filelist_w${W}.txt"
  [ -f "$P" ] && [ -f "$B" ] && [ -f "$F" ] \
    || { echo "missing input for w$W" >&2; exit 1; }
  # Refuse already-quantized input: int8 params carry scale-count terms
  # (verified: 0 in FP det.param, 66 in shipped INT8 rec_dyn.param).
  if grep -qE " 8=" "$P"; then
    echo "w$W: input looks already quantized -- refusing (quantize from FP32 source)" >&2
    exit 1
  fi
  echo "== w$W: ncnn2table (KL) =="
  "$TABLE" "$P" "$B" "$F" "$OUT/table_w${W}.txt" "shape=[$W,48,3]" method=kl type=1
  echo "== w$W: scale sanity gate =="
  python3 - "$OUT/table_w${W}.txt" <<'EOF'
import re, sys
bad = []
for line in open(sys.argv[1]):
    for m in re.finditer(r"[-+]?\d[\d.]*e[-+]?\d+", line):
        v = float(m.group(0))
        if abs(v) > 1e6:
            bad.append((line.split()[0] if line.split() else "?", m.group(0)))
if bad:
    print(f"ABSURD SCALES ({len(bad)}), likely double quantization:")
    for name, v in bad[:10]:
        print(f"  {name} = {v}")
    sys.exit(1)
print("scales sane")
EOF
  echo "== w$W: ncnn2int8 =="
  "$INT8" "$P" "$B" "$OUT/rec_w${W}.param" "$OUT/rec_w${W}.bin" "$OUT/table_w${W}.txt"
done
echo "OK -> $OUT"
