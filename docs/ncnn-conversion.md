# PP-OCRv6 ncnn Conversion — safetensors → PyTorch → pnnx → ncnn

> How the `app/src/main/assets/PP-OCRv6_small_ncnn/*.param/*.bin` models (det `960×960`, rec `48×64/128/256/480`) were produced from the canonical Paddle `model.safetensors`.

Source of truth for #1 wayfinder map, #5, #6, #10, #11. See `CONTEXT.md` for vocab (`PP-OCRv6`, `Bucket`, `CTC`, `OcrEngine`).

---

## Canonical source

- **Rec**: `models/archive/PP-OCRv6_small_rec_safetensors/model.safetensors` (PP-LCNetV4, `hidden 120 / depth 2 / SiLU`, `18710` classes, `0=blank`, `18709=space`) + `config.json` + `preprocessor_config.json`. This is the Hugging Face `PP-OCRv6_small` rec checkpoint, **not** the `PP-OCRv6_small_rec_onnx/inference.onnx` shim.
- **Det**: `models/archive/PP-OCRv6_small_det_onnx/inference.onnx` (DB, `960×960`, `Paddle` export) and `models/archive/PP-OCRv6_small_rec/inference.pdiparams` (Paddle static graph). Both are now archived under `models/archive/` — only `vocab.json` and `ncnn/` remain in `app/src/main/assets` for the APK.

Archived per user request: onnx/pdiparams/safetensors moved from `app/src/main/assets/PP-OCRv6_*` → `models/archive/PP-OCRv6_*` so the APK ships only `PP-OCRv6_small_ncnn/` (`10.56 MB` per rec bucket, `det 4.8 MB`) + `vocab.json`.

---

## Pipeline — rec (4 static buckets)

```
model.safetensors (HF, PP-LCNetV4)
  → PyTorch (transformers/paddle2torch, keep SiLU, depth 2, hidden 120)
  → ONNX (opset 17, dynamic batch, fixed H=48, W in {64,128,256,480})
  → pnnx 20260526 (opt + fuse: SiLU→Swish, GELU chain→GELU, ReduceMean→Reduction, LayerNorm, Pow→Square)
  → ncnn 2130e00 (CPU-only, see below)
```

Validated in #5:

- **Buckets**: `48×64` (`seq 8`), `48×128` (`16`), `48×256` (`32`), `48×480` (`60`) — `179` layers / `206` blobs each, `15.5 KB` param + `10.56 MB` bin, `GELU 13` `Swish 5` `LayerNorm 5` `SDPA 2`.
- **Parity**: `HF (safetensors, torch) vs ORT (onnx) vs ncnn` — `max |logit| 4–8e-5`, `100%` CTC top-1 greedy match on 2 bench images × 4 buckets. Zero manual edits to pnnx output.
- **Outputs**: `app/src/main/assets/PP-OCRv6_small_ncnn/rec_w{64,128,256,480}.param/.bin` (Git LFS, `app/src/main/assets/PP-OCRv6_small_ncnn/*.bin`).

**Why static buckets, not dynamic-W**: pnnx/ncnn dynamic-shape ergonomics (`-1` dim) not yet proven for `W/8` CTC; 4 buckets cover `rw*48/rh ≤ 480` crush case with `w480` chunking (`OcrEngine:508` `>640` split).

### Det — 960×960 DB

- **Source**: `PP-OCRv6_small_det_onnx/inference.onnx` (DB, `FPN` + `DBHead`, `960×960`).
- **PNNX → ncnn**: same `pnnx 20260526 → ncnn 2130e00` (no custom layer, `HardSigmoid`, `GELU`, `Reduction`, `Resize` nearest, `ConvTranspose` all stock — #10 gap `0`).
- **Outputs**: `app/src/main/assets/PP-OCRv6_small_ncnn/det.param` (`19 KB`) / `det.bin` (`4.8 MB`), `242` nodes, input `in0` `[1,3,960,960]` `NCHW` ImageNet `(0.485/0.456/0.406 / 0.229/0.224/0.225)`, output prob map `1×960×960` `sigmoid`.

---

## Pin & build — `Tencent/ncnn` `2130e00` (2026-05-26)

- **Repo pin**: `2130e00` (`227` layers, `110` cmake, `211` pnnx rewriters) — CPU-only `NCNN_VULKAN=OFF`, `app/src/main/cpp/CMakeLists.txt` `externalNativeBuild` + `jniLibs/arm64-v8a/libncnn.a` (`filter=lfs`), like `nav_graph_core`.
- **Graph rewrites** (preferred over fork, #11): `SiLU→Swish`, `GELU` chain→`GELU`, `ReduceMean→Reduction`, `LayerNorm` fuse, `Pow→Square` — all 5 fuses adopted, no custom port, no fork. Raw chains kept as param-level fallback.
- **JNI**: `app/src/main/cpp/ncnn_jni.cpp` — `RecNcnn` (`targetW` → `seqLen=W/8`, `opt.num_threads=4`, `use_packing_layout`) and `DetNcnn` (`in0` `NCHW`, `ex.extract` `out0/sigmoid`).

### Android integration

- `OcrEngine` (`app/src/main/java/com/holopengin/instantjpdict/OcrEngine.kt:27`): `detect` `DetNcnn` `960×960` `NCHW` only (LiteRT removed #15), `recognizeStreaming` `RecNcnn` `4` buckets, `computeCharBoxes` `avgColW=crop/seqLen`.
- `app/build.gradle.kts`: `externalNativeBuild cmake 3.22.1`, `benchmark` `buildType` (`signingConfig debug`, `isMinifyEnabled false`), `litert` + `onnxruntime` + `det_float32.tflite` removed for #15 (`APK -42 MB` post-cutover per #6).
- `RecNcnn.kt` / `DetNcnn.kt`: `ByteBuffer` `allocateDirect` `NCHW`, `System.loadLibrary("ncnn_jni")`.

---

## Correctness contract — #8

- **Per-bucket**: `maxAbs <1e-2` (ORT `1e-3` gate relaxed to `1e-2` for ncnn packing, `w64` `1.7e-3`) + `100%` top-1, single-pass (no looping).
- **Benchmark harness** (#7): `benchmark/Screenshot_20260530-172718.png` (`2400×1080`, `37` boxes) + `f5d7d08735383899.jpg` (`1366×768`, `65` boxes), `3` crops sampled `perCrop 1301 ms` / `2407 ms` (`full 65×2.4s=148s`), `engine_load 1750 ms`, `benchmark` buildType `mainHandler.post`.

---

## Repro — from archive to ncnn

```bash
# 1. Rec: safetensors → PyTorch → ONNX (4 widths)
python tools/convert_safetensors_to_onnx.py \
  --safetensors models/archive/PP-OCRv6_small_rec_safetensors/model.safetensors \
  --config models/archive/PP-OCRv6_small_rec_safetensors/config.json \
  --widths 64,128,256,480 --height 48 --out app/src/main/assets/PP-OCRv6_small_ncnn

# 2. Det: Paddle ONNX → pnnx
pnnx models/archive/PP-OCRv6_small_det_onnx/inference.onnx \
  inputshape=[1,3,960,960] inputshape2=[1,3,960,960]
# → det.pnnx.param / .bin

# 3. pnnx → ncnn (rec buckets + det)
./pnnx rec_w64.pnnx.param rec_w64.pnnx.bin rec_w64.param rec_w64.bin
./pnnx det.pnnx.param det.pnnx.bin det.param det.bin
# copy to app/src/main/assets/PP-OCRv6_small_ncnn/ (LFS)

# 4. Verify parity (HF vs ORT vs ncnn, 4 buckets, 2 images)
python tools/verify_nccn_parity.py --buckets 64,128,256,480 --images benchmark/*.png,*.jpg
# expect maxAbs 4–8e-5, 100% CTC

# 5. Android
./gradlew :app:assembleDebug :app:assembleBenchmark
# benchmark: ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.holopengin.instantjpdict.OcrBenchmarkTest
```

All pnnx outputs are committed verbatim — zero hand-edits (`#5`).

---

## Archive layout

```
models/archive/
  PP-OCRv6_small_det_onnx/ — inference.onnx, inference.pnnxsim.onnx, inference.json/yml
  PP-OCRv6_small_rec/ — inference.pdiparams, inference.json/yml  (Paddle)
  PP-OCRv6_small_rec_safetensors/ — model.safetensors, config.json  (HF canonical)
  meiki.text.*.onnx — legacy Meiki baselines (pre-PP-OCRv6)
app/src/main/assets/
  PP-OCRv6_small_ncnn/ — det.param/bin, rec_w{64,128,256,480}.param/bin, vocab.json (LFS + vocab, shipped, PP-OCR conversion)
```

Do not re-add `*.onnx`/`*.pdiparams`/`*.tflite` to `app/src/main/assets/PP-OCRv6_*` — they belong in `models/archive`.

---
*penned by opencode2 + muse-spark-1.2-contributor*
