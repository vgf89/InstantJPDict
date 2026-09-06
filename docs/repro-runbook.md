# Zero-to-APK Repro Runbook (#29 Track 1)

One command per step, from a clean checkout to a byte-sane APK + passing
parity gate. Every step is scripted except where marked **[GAP]** -- those
are tribal-knowledge holes found during the Track 1 audit; each lists exactly
what the first nuke-and-pave run must pin down.

## 0. Prerequisites (pins)

| Tool | Pin | Source |
|---|---|---|
| Gradle | 9.4.1 | `gradle/wrapper/gradle-wrapper.properties` (wrapper -- no install) |
| Android Gradle Plugin / compileSdk / targetSdk / minSdk | 35 / 35 / 30 | `app/build.gradle.kts` |
| CMake (JNI) | 3.22.1 | `app/build.gradle.kts` (`externalNativeBuild`) |
| ABI | `arm64-v8a` only | `app/build.gradle.kts` (`ndk.abiFilters`) |
| NDK platform for libncnn.a | `android-30` | = minSdk; `tools/build_ncnn.sh` |
| pnnx (ONNX->ncnn) | `20260526` | `docs/ncnn-conversion.md`; release binary from Tencent/pnnx |
| ncnn base (lib + host tools) | `Tencent/ncnn @ 6a1bf000` + `third_party/ncnn-patches/` | `tools/build_ncnn.sh` |
| ncnn conversion target | `2130e00`, `NCNN_VULKAN=OFF` | `docs/ncnn-conversion.md` |
| Python | `>=3.14`, `uv`-managed (`pyproject.toml`: onnx 1.21.0, ort 1.26.0, pillow 11.2.1) | `pyproject.toml` |
| git-lfs | any recent | required: all `*.bin`/`*.safetensors`/`*.onnx`/tarball are LFS |
| JDK | OpenJDK 26.0.2.1 on PATH | verified on build machine 2026-09-06 |
| AGP / Kotlin / KSP | 9.2.1 / 2.2.10 / 2.3.2; code targets Java 21 (`source/targetCompatibility`, `jvmTarget 21`) | build machine + `app/build.gradle.kts` |
| Gradle toolchain resolver | foojay (`settings.gradle.kts`) -- first compile may download a JDK; needs network to `google()` + `mavenCentral()` | build machine |
| Android SDK | `platforms/android-35`, `build-tools;35.0.0`, `cmake;3.22.1`, `platform-tools` | build machine (`sdkmanager --list_installed`) |
| NDK | **`28.2.13676358`**, pinned via `ndkVersion` in `app/build.gradle.kts` | build machine (`CMakeCache.txt` showed AGP resolving this); `nav_graph_core/build_nav_graph.sh` prefers it, honors `ANDROID_NDK_HOME` first |
| Rust (optional) | 1.96.0, uniffi 0.28 (`nav_graph_core/Cargo.toml`) | build machine. Optional for first compile: `buildNavGraphCore` has `isIgnoreExitValue=true` and `libnav_graph_core.so` is committed under `app/src/main/jniLibs/` |
| ktlint | missing on build machine -- non-fatal warning only | -- |

## 1. Clone

```bash
git clone <repo> && cd InstantJPDict
git checkout wayfinder-ncnn-port   # or main, once merged
git lfs install --local && git lfs pull
git lfs ls-files  # every *.bin/*.safetensors/*.onnx/cal-images.tar.gz must be real bytes, not 133-byte pointers
```

## 2. ncnn tree + host tools + Android lib

```bash
tools/build_ncnn.sh --out /tmp/ncnn_build
# -> /tmp/ncnn_build/host/bin/{ncnn2table,ncnn2int8,ncnnoptimize}
# -> /tmp/ncnn_build/android-arm64/{lib/libncnn.a,include/...}
cp /tmp/ncnn_build/android-arm64/lib/libncnn.a app/src/main/cpp/ncnn/lib/arm64-v8a/
cp -r /tmp/ncnn_build/android-arm64/include/. app/src/main/cpp/ncnn/include/
```

Verifies itself: applied tree is `diff -r`-identical to `vgf89/ncnn @ 0c9625b0`.

## 3. Calibration inputs

```bash
tar xzf cal-images.tar.gz -C /tmp/cal && mkdir -p misc
cp -r /tmp/cal/rec-cal/trails_of_cold_steel_images \
      /tmp/cal/rec-cal/trails_of_cold_steel_images_2 \
      /tmp/cal/rec-cal/vert_large \
      /tmp/cal/rec-cal/test_images_hard misc/
python3 tools/gen_rec_calib_npy.py --out /tmp/rec_calib
# expect: w64/w128 filled by deterministic slice top-up (seed 16),
# w256/w480 from whole lines; all npys (3,48,W) float32 in [-1,1]
python3 tools/gen_det_calib_npy.py --out /tmp/det_calib
```

Det sources are committed (`tools/onnx_quantization/calibration_data/detect/`);
rec sources come from the LFS tarball (game screenshots, not for distribution).

## 4. safetensors -> torchscript -> pnnx -> ncnn (FP32)

Exporter: `tools/export_rec_onnx.py` (**reconstruction**, not recovered --
the original Wrapper source is lost; confirmed nowhere on disk, no
torch/transformers in `.venv`). Recipe from #5, verified on the build
machine 2026-09-06:

```bash
python3 tools/export_rec_onnx.py \
  --ckpt models/archive/PP-OCRv6_small_rec_safetensors \
  --widths 64,128,256,480 --out /tmp/pt_models
# needs torch==2.13.0 + transformers==5.16.1 (explicit
# PPOCRV6SmallRecForTextRecognition import -- local config.json has no
# `architectures` key so AutoModel cannot resolve; class confirmed on Hub)
```

What the script does: loads the checkpoint float32, probes the output once
(printing type/keys/shape -- `--logit` defaults to `logits`, asserted to end
in 18710 classes), traces a thin Wrapper per width at `[1,3,48,W]` ->
`rec_w{W}.pt`.

Then per width + det (pnnx 20260526 from pip, single call each -- the same
binary parses, optimizes, fuses, and emits ncnn; verified: rec census
179 layers / 206 blobs with GELU 13 / Swish 5 / LayerNorm 5 / SDPA 2, bins
10.56 MB; det 217 layers, layer sequence md5-identical to shipped, det.bin
byte-identical to shipped):

```bash
pnnx /tmp/pt_models/rec_w{W}.pt inputshape=[1,3,48,{W}] \
  ncnnparam=rec_w{W}.param ncnnbin=rec_w{W}.bin
# -> rec_w{W}.param/.bin FP32, zero hand-edits (no separate converter step)
pnnx models/archive/PP-OCRv6_small_det_onnx/inference.onnx \
  inputshape=[1,3,960,960] ncnnparam=det.param ncnnbin=det.bin
```

Contract (#5, the arbiter -- not trust): HF-vs-ORT-vs-ncnn max |logit|
4-8e-5, 100% CTC top-1 greedy match on both bench images
(`app/src/androidTest/assets/benchmark/`). If the fresh Wrapper trips it,
the normalize-inside-vs-outside-trace boundary is the prime suspect
(runtime feeds gray/127.5-1 NCHW).

FP16-storage (was **[GAP-FP16]**, now **resolved by experiment** 2026-09-06):
quantizing straight from the FP32 pnnx output reproduces the shipped #16
numbers exactly (w480 93.3%/0.007, INT8 bins 5.38 MB) -- no FP16 intermediate
needed, consistent with the doc's note that fp32->fp16->int8 is measurably
identical to fp32->int8. `ncnnoptimize in.param in.bin out.param out.bin 1`
remains available in the `build_ncnn.sh` tree if a future flow wants it.
Note: the pnnx `fp16=1` flag emits same-size bins, i.e. it does NOT produce
the historical FP16-storage form -- do not use it for this purpose.

## 5. Quantize rec to INT8

```bash
tools/quantize_rec_int8.sh --fp32-dir /tmp/fp32_models --calib /tmp/rec_calib \
  --tools /tmp/ncnn_build/host/bin --out /tmp/int8_models
```

Runs `ncnn2table` (KL, `shape=[W,48,3]`, `type=1` npy) + the scale sanity gate
(refuses absurd scales like `7.6e34` = double quantization, #16 lesson) +
`ncnn2int8` per bucket. KL tables land in `/tmp/int8_models/table_w{W}.txt`:
commit them or keep them regenerable -- the gate in step 7 is the arbiter.

## 6. rec_dyn surgery

```bash
python3 tools/make_rec_dyn.py --param /tmp/int8_models/rec_w480.param \
  --bin /tmp/int8_models/rec_w480.bin --out-dir /tmp/rec_dyn
# expect: "Reshape edits: 16, Gemm 7= drops: 9"; refuses already-dynamic input
```

Verified: inverts byte-identically to the shipped `rec_dyn.param` (#23).

## 7. Parity gate (host)

```bash
tools/parity_gate_rec.sh --ncnn-src /tmp/ncnn_build/src \
  --ncnn-build /tmp/ncnn_build/build-host --ref-dir /tmp/fp32_models \
  --int8-dir /tmp/int8_models --calib /tmp/rec_calib \
  --vocab app/src/main/assets/PP-OCRv6_small_ncnn/vocab.json
# gates: w480 exact>=88% CER<=0.020; w256 exact>=78% CER<=0.060
# (shipped #16 numbers: 93.3%/0.007 and 85%/0.034; w64/w128 report-only)
```

## 8. Assemble + install

```bash
cp /tmp/rec_dyn/rec_dyn.param /tmp/rec_dyn/rec_dyn.bin \
   app/src/main/assets/PP-OCRv6_small_ncnn/
# det stays FP16 (INT8 det failed parity, #22): conversion output -> assets
./gradlew :app:assembleDebug :app:assembleBenchmark
```

## 9. On-device verification (Pixel 7a)

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.holopengin.instantjpdict.OcrBenchmarkTest
# benchRec + smokeDetOnly; min-of-3 same-session (p50s swing +-50% with DVFS)
```

Acceptance: APK installs, bench sample texts match the #16/#23 record,
`smokeDetOnly` passes the <15s gate.

## Known wart: legacy meiki blobs vs LFS rule

`models/archive/meiki.text.{detect,rec,rec.vertical}*.onnx` are committed as
raw git blobs (14-18 MB) even though `.gitattributes` routes
`models/archive/**/*.onnx` through LFS. Every fresh checkout prints
`Encountered 3 files that should have been pointers, but weren't` and leaves
them `M` in `git status`. They are pre-PP-OCRv6 legacy baselines, unused by
this runbook -- ignore the noise, or repair by committing proper pointers /
exempting `meiki*` from the filter (owner decision, touches representation).

## Open holes for the first nuke-and-pave run

1. **Exporter validation**: `tools/export_rec_onnx.py` is a reconstruction --
   validated 2026-09-06 in the Arch container (probe exact, census exact,
   det.bin byte-identical, parity reproduced). Remaining: HF-vs-ORT-vs-ncnn
   max-|logit| 4-8e-5 check if stricter proof is wanted.
2. **FP16 verdict**: closed -- FP32 source reproduces shipped numbers; no
   FP16 step needed.
3. First full `tools/build_ncnn.sh` compile -- **done** 2026-09-06 in the
   Arch container (host tools + android arm64 libncnn.a, `NCNN_VULKAN=OFF`).
4. Commit `table_w{W}.txt` KL tables or bless regen-every-time (gate decides).
5. The nuke-and-pave itself: clean checkout, this runbook only, ending with a
   byte-sane APK + passing parity gate.
