# INT8 investigation notes (issue #16, rec-first) — UNCOMMITTED scratch, do not ship

Host harnesses in this dir (build against /tmp/ncnn_host_build/src/libncnn.a):
- `rec_compare.cpp` — fp32 vs quant model, per-timestep maxAbs + argmax agreement over a filelist.
- `rec_textcmp.cpp` — same, plus CTC-greedy text exact-match + CER-like score.
- `rec_blobcmp2.cpp` — rec_compare + argv[8] blob name + NaN/Inf counters + sorted-multiset compare.
- `mini_cmp.cpp` / `mini_dconv.cpp` — single-layer minimal repros.

Calibration: `tools/gen_rec_calib_npy.py` (committed) → /tmp/rec_calib/w{64,128,256,480}/ (exact runtime
preprocessing `gray/127.5-1`, zero-pad to bucket). w64/w128 topped up with sliced fragments (mid-char
cuts pollute parity metrics there — use w256/w480 numbers for decisions).

## Proven, shipped (eca9734, installed, user-verified on commute)

Mixed INT8: w256+w480 quant (KL) with 10 SE-branch 1x1 convs kept FP16
(`convrelu_5/6/7/8/9`, `conv_42/52/58/64/72` — delete weight+blob rows from table;
NOTE `ncnn2int8` does NOT honor `#` comments, rows must be DELETED).
w64/w128 stay FP16. w480: 95% text-exact CER 0.006, w256: 88% CER 0.027,
end-to-end sample texts identical to FP16. Device w480 p50 235→100ms.

## Kernel track (/home/holopengin/repos/ncnn, 2 local commits, NO PR per owner policy)

1. `4897b1db` — `ConvolutionDepthWise::load_model` crashed on scale terms 201/202
   (ncnn2int8 double requantize fuse via split fanout). Fix: `% 100` residue,
   mirrors `Convolution::load_model`. Proven: 201-model loads (was SIGSEGV at load).
2. `bcca5e06` — int8 1x1-conv on flattened 1D blobs (SE squeeze branches) misread pack
   groups as spatial width (e.g. fp32 `3x1x1`, int8 `12x1x3`). Fix: mirror the float
   1D-compat reshape-to-3D-and-recurse in x86/arm/base `forward_int8`.
   Proven: w480-FULL-int8 (zero exclusions) 95% exact CER 0.004 host.

## RESOLVED (2026-09-04): w256 "all-blank" was double quantization

`ncnn2table`/`ncnn2int8` had been run on the already-quantized MIXED model
instead of the FP16 source → int8-packed weights read as floats → weight
scales like `convdw_125_param_0 = 7.6e34` (sane: 869) → net decodes blank.
Re-quantizing from `/tmp/rec_w{w}.param/.bin` (FP16 originals) + existing
KL tables fixed it instantly. Both kernel commits are correct and needed.
Full-INT8 shipped for all 4 buckets (339a950): w64 44 / w128 55 / w256 80 /
w480 124ms min-of-3; parity w256 85%/0.034, w480 93%/0.007; texts match.
ALWAYS quantize from FP16 source. Also: Pixel 7a p50s swing ±50% run to
run (DVFS/charging/sleep) — min-of-3 same-session A/B only.

## Current phone state

Full-INT8 build (339a950) installed via `installBenchmark`; all-bucket bench verified.
Bench p50s swing ±50% with DVFS/charging/sleep — min-of-3 same-session A/B only.
