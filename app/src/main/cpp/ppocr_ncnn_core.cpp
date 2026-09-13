// Platform-independent PP-OCRv6 ncnn inference core.
// See ppocr_ncnn_core.h — keep this file byte-identical with the PC copy in
// InstantJPDictDecky/accessibility_daemon/native/ppocr_ncnn/.
#include "ppocr_ncnn_core.h"

#include <cfloat>
#include <cstring>

#include "ncnn/benchmark.h"
#include "ncnn/cpu.h"
#include "ncnn/mat.h"
#include "ncnn/option.h"

#ifdef __ANDROID__
#include <android/log.h>
#define PPOCR_LOG_TAG "PpocrNcnn"
#define PPOCR_LOGI(...) __android_log_print(ANDROID_LOG_INFO, PPOCR_LOG_TAG, __VA_ARGS__)
#define PPOCR_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, PPOCR_LOG_TAG, __VA_ARGS__)
#else
#include <cstdio>
#define PPOCR_LOGI(...)                    \
    do {                                   \
        std::fprintf(stderr, "[ppocr_ncnn] " __VA_ARGS__); \
        std::fprintf(stderr, "\n");        \
    } while (0)
#define PPOCR_LOGE(...)                              \
    do {                                             \
        std::fprintf(stderr, "[ppocr_ncnn ERROR] " __VA_ARGS__); \
        std::fprintf(stderr, "\n");                  \
    } while (0)
#endif

namespace ppocr_ncnn {

namespace {

constexpr int REC_HEAD_K = 15; // must match OcrEngine.TOP_K on both apps

// Class count of the CTC output, derived from the extracted tensor itself.
//
// This used to be the hardcoded constant 13193, which is exactly the kind of thing a head
// re-prune leaves behind silently: the old guard compared totals with `<`, so a *wider*
// tensor still "matched", and then every timestep after t=0 was scanned at the wrong row
// stride. That window straddles two rows, and its max is a confident class out of the
// neighbouring row's band — never blank — so the decoded string comes out one garbage
// character per timestep, length equal to the timestep count. It reads like a broken
// recogniser, not like a stale constant. Derive the width instead; never hardcode it.
//
// Layout is [w=numClasses, h=seqLen] with w innermost (one float per class per timestep).
// Do NOT compare against total() == h*w: ncnn's Mat::total() carries up to 3 elements of
// height padding, so that test rejects every tensor whose timestep count is not a multiple
// of 4 (measured: h=63 -> total = h*w+1, h=45 -> h*w+3, h=40 and h=52 -> exact). Check the
// dims and that the storage can hold the rows we are about to read; refuse anything else.
int recClassWidth(const ncnn::Mat& out, int seqLen) {
    if (seqLen <= 0 || out.w <= 0) return 0;
    if (!(out.dims == 2 || (out.dims == 3 && out.c == 1))) return 0;
    if (out.h < seqLen) return 0;
    if ((size_t)out.total() < (size_t)seqLen * (size_t)out.w) return 0;
    return out.w;
}

// Shared NCHW upload: `data` is [1,3,h,w] flattened as [c*h*w + y*w + x].
void fill_input(ncnn::Mat& in, const float* data, int w, int h) {
    for (int c = 0; c < 3; c++) {
        float* ptr = in.channel(c);
        long cOffset = (long)c * h * w;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                ptr[y * w + x] = data[cOffset + y * w + x];
            }
        }
    }
}

} // namespace

int rec_top_k() { return REC_HEAD_K; }

RecNet* rec_create(const char* paramPath, const char* binPath, int targetW, int numThreads) {
    RecNet* rec = new RecNet();
    rec->targetW = targetW;
    rec->seqLen = targetW / 8;

    ncnn::Option opt;
    // Thread count is a runtime tunable (#58); 1 won on every bucket back in
    // #20 (w64 10 vs 65ms …), narrow seq models being sync-overhead-dominated.
    opt.num_threads = numThreads > 0 ? numThreads : 1;
    opt.use_fp16_packed = false;
    opt.use_fp16_storage = false;
    opt.use_fp16_arithmetic = false;
    opt.use_packing_layout = true;
    opt.use_bf16_storage = false;
    rec->net.opt = opt;

    // Enable big cores
    ncnn::set_cpu_powersave(0);

    int ret = rec->net.load_param(paramPath);
    if (ret != 0) {
        PPOCR_LOGE("load_param failed %d %s", ret, paramPath);
        delete rec;
        return nullptr;
    }
    ret = rec->net.load_model(binPath);
    if (ret != 0) {
        PPOCR_LOGE("load_model failed %d %s", ret, binPath);
        delete rec;
        return nullptr;
    }

    PPOCR_LOGI("RecNet created W=%d seq=%d threads=%d param=%s", targetW, rec->seqLen, opt.num_threads, paramPath);
    return rec;
}

void rec_destroy(RecNet* rec) {
    if (rec) {
        rec->net.clear();
        delete rec;
    }
}

bool rec_infer(RecNet* rec, const float* data, size_t dataFloats, int w, int h, std::vector<float>& out) {
    if (!rec) return false;

    long expectedFloats = 1L * 3 * h * w;
    if ((long)dataFloats < expectedFloats) {
        PPOCR_LOGE("buffer too small %ld vs %ld", (long)dataFloats, expectedFloats);
        return false;
    }

    // Create ncnn Mat: w, h=48, c=3
    ncnn::Mat in(w, h, 3);
    fill_input(in, data, w, h);

    ncnn::Extractor ex = rec->net.create_extractor();
    ex.set_light_mode(true);
    ex.input("in0", in);
    ncnn::Mat outMat;
    // Skip softmax_30: extract gemm_8 logits directly (blob 191 in the fused
    // graph, 204 pre-fusion; see rec_dyn.param tail). #25, fused #41.
    // Argmax/top-15 order is identical (softmax monotonic); scores become logits, which no
    // consumer reads absolutely (blankThreshold path is relative, default 0 = pure greedy).
    // Lazy eval never runs softmax — saves ~4% (its exp/sum over 13193×seq post-prune). #25
    int ret = ex.extract(191, outMat);
    if (ret != 0) {
        PPOCR_LOGE("extract out0 failed %d", ret);
        return false;
    }

    // out shape: [w=numClasses, h=seqLen], w innermost. Never assume the width: the head is
    // re-pruned from the keep list (#44 moved it 13193 -> 13353) and this file is not part of
    // that regeneration. Log the dims and take the width off the tensor.
    PPOCR_LOGI("ncnn out dims=%d w=%d h=%d c=%d total=%d", outMat.dims, outMat.w, outMat.h, outMat.c, (int)outMat.total());
    // Dynamic width (#23): sequence length comes from the ACTUAL input width, not the
    // create-time targetW. Kotlin always passes a multiple of 8 (zero-padded exact width).
    int seqLen = w / 8;
    int numClasses = recClassWidth(outMat, seqLen);
    if (numClasses <= 0) {
        PPOCR_LOGE("rec out unusable dims=%d w=%d h=%d c=%d total=%d seqLen=%d",
                   outMat.dims, outMat.w, outMat.h, outMat.c, (int)outMat.total(), seqLen);
        return false; // fail closed: a wrong-stride read renders as garbage text
    }

    // ncnn stores as c * h * w contiguous with w innermost, so row t starts at
    // outData + t*numClasses.
    const float* outData = (const float*)outMat.data;
    out.assign(outData, outData + (size_t)seqLen * (size_t)numClasses);
    return true;
}

bool rec_infer_topk(RecNet* rec, const float* data, size_t dataFloats, int w, int h, std::vector<float>& out) {
    if (!rec) return false;

    long expectedFloats = 1L * 3 * h * w;
    if ((long)dataFloats < expectedFloats) {
        PPOCR_LOGE("buffer too small %ld vs %ld", (long)dataFloats, expectedFloats);
        return false;
    }

    ncnn::Mat in(w, h, 3);
    fill_input(in, data, w, h);

    int64_t t0 = (int64_t)(ncnn::get_current_time() * 1000);
    ncnn::Extractor ex = rec->net.create_extractor();
    ex.set_light_mode(true);
    ex.input("in0", in);
    ncnn::Mat outMat;
    int ret = ex.extract(191, outMat);
    if (ret != 0) {
        PPOCR_LOGE("extract out0 failed %d", ret);
        return false;
    }
    int64_t t1 = (int64_t)(ncnn::get_current_time() * 1000);

    int seqLen = w / 8;
    const int K = REC_HEAD_K; // must match OcrEngine.TOP_K
    // Width comes off the tensor, and the total must match exactly. The old form was a
    // hardcoded 13193 checked with `<`, so a *wider* head (the #44 re-prune) passed and every
    // timestep was scanned at the stale stride — the bug this guard now cannot miss.
    const int numClasses = recClassWidth(outMat, seqLen);
    if (numClasses <= 0) {
        PPOCR_LOGE("recTopK out unusable dims=%d w=%d h=%d c=%d total=%d seqLen=%d",
                   outMat.dims, outMat.w, outMat.h, outMat.c, (int)outMat.total(), seqLen);
        return false;
    }
    const float* outData = (const float*)outMat.data;

    // Packed top-K, written straight into the output vector:
    // out[(t*K + k)*2 + 0] = class idx, [(t*K + k)*2 + 1] = value.
    // (Staging is seqLen*30 floats ≈ 15KB @250 steps vs 13MB full logits.)
    out.resize((size_t)seqLen * K * 2);
    for (int t = 0; t < seqLen; t++) {
        const float* row = outData + (long)t * numClasses;
        // top[] kept descending; insertion scan over ascending class ids with
        // strict > keeps lowest id on ties (Java argmax parity).
        int topIdx[15];
        float topVal[15];
        for (int k = 0; k < K; k++) { topIdx[k] = -1; topVal[k] = -FLT_MAX; }
        for (int c = 0; c < numClasses; c++) {
            float v = row[c];
            if (v > topVal[K - 1]) {
                int p = K - 1;
                while (p > 0 && v > topVal[p - 1]) { topVal[p] = topVal[p - 1]; topIdx[p] = topIdx[p - 1]; p--; }
                topVal[p] = v; topIdx[p] = c;
            }
        }
        for (int k = 0; k < K; k++) {
            out[(t * K + k) * 2 + 0] = (float)topIdx[k];
            out[(t * K + k) * 2 + 1] = topVal[k];
        }
    }
    int64_t t2 = (int64_t)(ncnn::get_current_time() * 1000);
    PPOCR_LOGI("recTopK w=%d seq=%d extract=%.1fms topk=%.1fms out=%d floats (full would be %d)",
               w, seqLen, (t1 - t0) / 1000.0, (t2 - t1) / 1000.0,
               (int)out.size(), seqLen * numClasses);
    return true;
}

DetNet* det_create(const char* paramPath, const char* binPath) {
    DetNet* det = new DetNet();
    ncnn::Option opt;
    // 1 thread + fp16 throughout (#25 det tune): min-of-3 same-session —
    // jpg 1522→892ms, screenshot 1396→855ms vs 4-thread fp32; box IoU ≥0.95
    // holds (mean 0.99+, worst single-box 0.63). Det bin is fp16-storage already.
    opt.num_threads = 1;
    opt.use_fp16_packed = true;
    opt.use_fp16_storage = true;
    opt.use_fp16_arithmetic = true;
    PPOCR_LOGI("DetNet threads=1 fp16=1");
    opt.use_packing_layout = true;
    det->net.opt = opt;
    ncnn::set_cpu_powersave(0);
    int ret = det->net.load_param(paramPath);
    if (ret != 0) {
        PPOCR_LOGE("Det load_param failed %d %s", ret, paramPath);
        delete det;
        return nullptr;
    }
    ret = det->net.load_model(binPath);
    if (ret != 0) {
        PPOCR_LOGE("Det load_model failed %d %s", ret, binPath);
        delete det;
        return nullptr;
    }
    PPOCR_LOGI("DetNet created param=%s", paramPath);
    return det;
}

void det_destroy(DetNet* det) {
    if (det) {
        det->net.clear();
        delete det;
    }
}

bool det_infer(DetNet* det, const float* data, size_t dataFloats, int w, int h, std::vector<float>& out) {
    if (!det) return false;
    long expectedFloats = 1L * 3 * h * w;
    if ((long)dataFloats < expectedFloats) {
        PPOCR_LOGE("Det buffer too small %ld vs %ld", (long)dataFloats, expectedFloats);
        return false;
    }
    ncnn::Mat in(w, h, 3);
    fill_input(in, data, w, h);
    PPOCR_LOGI("Det input in0 w=%d h=%d c=3 total=%d mean0=%.3f", w, h, (int)in.total(), in.channel(0)[0]);

    ncnn::Extractor ex = det->net.create_extractor();
    ex.set_light_mode(true);
    int ret = ex.input("in0", in);
    if (ret != 0) {
        PPOCR_LOGE("Det input in0 failed %d", ret);
        return false;
    }
    ncnn::Mat outMat;
    ret = -1;
    // Cached output name — try it first to avoid 6 extracts per infer (2-5ms)
    if (!det->cachedOutName.empty()) {
        ret = ex.extract(det->cachedOutName.c_str(), outMat);
        if (ret == 0) {
            PPOCR_LOGI("Det extract cached %s ok dims=%d w=%d h=%d c=%d total=%d", det->cachedOutName.c_str(), outMat.dims, outMat.w, outMat.h, outMat.c, (int)outMat.total());
        } else {
            PPOCR_LOGE("Det cached extract %s failed %d, trying others", det->cachedOutName.c_str(), ret);
            ret = -1;
        }
    }
    if (ret != 0) {
        const char* tryNames[] = {"out0", "sigmoid_0", "sigmoid", "sigmoid_62", "602", "603", "601", nullptr};
        for (int i = 0; tryNames[i]; i++) {
            ret = ex.extract(tryNames[i], outMat);
            PPOCR_LOGI("Det try %s ret=%d", tryNames[i], ret);
            if (ret == 0) {
                det->cachedOutName = tryNames[i];
                PPOCR_LOGI("Det extract %s ok dims=%d w=%d h=%d c=%d total=%d (cached)", tryNames[i], outMat.dims, outMat.w, outMat.h, outMat.c, (int)outMat.total());
                break;
            }
        }
    }
    if (ret != 0) {
        PPOCR_LOGE("Det extract failed for all names");
        return false;
    }
    // out should be 960x960x1 or 1x960x960, total = w*h
    int total = (int)outMat.total();
    if (total <= 0 || outMat.data == nullptr) {
        PPOCR_LOGE("Det out empty total=%d", total);
        return false;
    }
    // DB outputs 1 channel, but may be w*h*1
    const float* outData = (const float*)outMat.data;
    out.assign(outData, outData + total);
    PPOCR_LOGI("Det infer ok w=%d h=%d outTotal=%d", w, h, total);
    return true;
}

} // namespace ppocr_ncnn
