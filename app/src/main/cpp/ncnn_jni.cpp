#include <jni.h>
#include <android/log.h>
#include <cfloat>
#include <string>
#include "ncnn/net.h"
#include "ncnn/mat.h"
#include "ncnn/benchmark.h"
#include "ncnn/option.h"
#include "ncnn/cpu.h"

#define LOG_TAG "NcnnJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct RecNcnn {
    ncnn::Net net;
    int targetW;
    int seqLen;
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_holopengin_instantjpdict_RecNcnn_create(JNIEnv *env, jclass, jstring paramPath_, jstring binPath_, jint targetW, jint numThreads) {
    const char *paramPath = env->GetStringUTFChars(paramPath_, 0);
    const char *binPath = env->GetStringUTFChars(binPath_, 0);

    RecNcnn *rec = new RecNcnn();
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
        LOGE("load_param failed %d %s", ret, paramPath);
        delete rec;
        env->ReleaseStringUTFChars(paramPath_, paramPath);
        env->ReleaseStringUTFChars(binPath_, binPath);
        return 0;
    }
    ret = rec->net.load_model(binPath);
    if (ret != 0) {
        LOGE("load_model failed %d %s", ret, binPath);
        delete rec;
        env->ReleaseStringUTFChars(paramPath_, paramPath);
        env->ReleaseStringUTFChars(binPath_, binPath);
        return 0;
    }

    LOGI("RecNcnn created W=%d seq=%d threads=%d param=%s", targetW, rec->seqLen, opt.num_threads, paramPath);

    env->ReleaseStringUTFChars(paramPath_, paramPath);
    env->ReleaseStringUTFChars(binPath_, binPath);

    return (jlong) rec;
}

JNIEXPORT void JNICALL
Java_com_holopengin_instantjpdict_RecNcnn_destroy(JNIEnv *, jclass, jlong handle) {
    RecNcnn *rec = (RecNcnn *) handle;
    if (rec) {
        rec->net.clear();
        delete rec;
    }
}

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
// Any other shape is refused: no text beats wrong text.
static int recClassWidth(const ncnn::Mat &out, int seqLen) {
    if (seqLen <= 0 || out.w <= 0) return 0;
    if ((out.dims == 2 || (out.dims == 3 && out.c == 1)) && out.h == seqLen &&
        (int)out.total() == seqLen * out.w) {
        return out.w;
    }
    return 0;
}

JNIEXPORT jfloatArray JNICALL
Java_com_holopengin_instantjpdict_RecNcnn_inferNative(JNIEnv *env, jclass, jlong handle, jobject buffer, jint w, jint h) {
    RecNcnn *rec = (RecNcnn *) handle;
    if (!rec) return nullptr;

    // buffer is direct ByteBuffer with float data [1,3,48,W] NCHW
    float *data = (float *) env->GetDirectBufferAddress(buffer);
    if (!data) {
        LOGE("GetDirectBufferAddress null");
        return nullptr;
    }
    jlong capacity = env->GetDirectBufferCapacity(buffer);
    // capacity in bytes, convert to floats
    long expectedFloats = 1 * 3 * 48 * w;
    if (capacity < expectedFloats * 4) {
        LOGE("buffer too small %ld vs %ld", capacity, expectedFloats*4);
        return nullptr;
    }

    // Create ncnn Mat: w=64, h=48, c=3
    ncnn::Mat in( w, 48, 3);
    // Fill per channel: data is NCHW 1x3x48xW flattened as [c*48*W + y*W + x]
    for (int c = 0; c < 3; c++) {
        float *ptr = in.channel(c);
        long cOffset = c * 48 * w;
        for (int y = 0; y < 48; y++) {
            for (int x = 0; x < w; x++) {
                ptr[y * w + x] = data[cOffset + y * w + x];
            }
        }
    }

    ncnn::Extractor ex = rec->net.create_extractor();
    ex.set_light_mode(true);
    ex.input("in0", in);
    ncnn::Mat out;
    // Skip softmax_30: extract gemm_8 logits directly (blob 191 in the fused
    // graph, 204 pre-fusion; see rec_dyn.param tail). #25, fused #41.
    // Argmax/top-15 order is identical (softmax monotonic); scores become logits, which no
    // consumer reads absolutely (blankThreshold path is relative, default 0 = pure greedy).
    // Lazy eval never runs softmax — saves ~4% (its exp/sum over 13193×seq post-prune). #25
    int ret = ex.extract(191, out);
    if (ret != 0) {
        LOGE("extract out0 failed %d", ret);
        return nullptr;
    }

    // out shape: [w=numClasses, h=seqLen], w innermost. Never assume the width: the head is
    // re-pruned from the keep list (#44 moved it 13193 -> 13353) and this file is not part of
    // that regeneration. Log the dims and take the width off the tensor.
    LOGI("ncnn out dims=%d w=%d h=%d c=%d total=%d", out.dims, out.w, out.h, out.c, (int)out.total());
    // Dynamic width (#23): sequence length comes from the ACTUAL input width, not the
    // create-time targetW. Kotlin always passes a multiple of 8 (zero-padded exact width).
    int seqLen = w / 8;
    int numClasses = recClassWidth(out, seqLen);
    if (numClasses <= 0) {
        LOGE("rec out unusable dims=%d w=%d h=%d c=%d total=%d seqLen=%d",
             out.dims, out.w, out.h, out.c, (int)out.total(), seqLen);
        return nullptr;   // fail closed: a wrong-stride read renders as garbage text
    }
    jfloatArray jout = env->NewFloatArray(seqLen * numClasses);
    if (!jout) return nullptr;

    // ncnn stores as c * h * w contiguous with w innermost, so row t starts at
    // outData + t*numClasses.
    float *outData = (float *) out.data;
    env->SetFloatArrayRegion(jout, 0, seqLen * numClasses, outData);
    return jout;
}

// Top-K per CTC timestep, computed natively (#42 leftover: kill the multi-MB
// logits download). Same input/extract path as inferNative, but instead of
// copying seqLen×numClasses floats to Java it partial-selects the top 15 per
// timestep and returns packed pairs: [idx0,val0, idx1,val1, ...] per timestep
// (idx stored as float; exact for ids < 2^24). Ties keep the lowest class id,
// matching the Java argmax scan (strict >) and top-15 intent. Layout: out row t
// lives at outData + t*numClasses, numClasses taken off the tensor (w innermost),
// same as above.
JNIEXPORT jfloatArray JNICALL
Java_com_holopengin_instantjpdict_RecNcnn_inferTopKNative(JNIEnv *env, jclass, jlong handle, jobject buffer, jint w, jint h) {
    RecNcnn *rec = (RecNcnn *) handle;
    if (!rec) return nullptr;
    float *data = (float *) env->GetDirectBufferAddress(buffer);
    if (!data) {
        LOGE("GetDirectBufferAddress null");
        return nullptr;
    }
    jlong capacity = env->GetDirectBufferCapacity(buffer);
    long expectedFloats = 1 * 3 * 48 * w;
    if (capacity < expectedFloats * 4) {
        LOGE("buffer too small %ld vs %ld", capacity, expectedFloats*4);
        return nullptr;
    }

    ncnn::Mat in(w, 48, 3);
    for (int c = 0; c < 3; c++) {
        float *ptr = in.channel(c);
        long cOffset = c * 48 * w;
        for (int y = 0; y < 48; y++) {
            for (int x = 0; x < w; x++) {
                ptr[y * w + x] = data[cOffset + y * w + x];
            }
        }
    }

    int64_t t0 = (int64_t)(ncnn::get_current_time() * 1000);
    ncnn::Extractor ex = rec->net.create_extractor();
    ex.set_light_mode(true);
    ex.input("in0", in);
    ncnn::Mat out;
    int ret = ex.extract(191, out);
    if (ret != 0) {
        LOGE("extract out0 failed %d", ret);
        return nullptr;
    }
    int64_t t1 = (int64_t)(ncnn::get_current_time() * 1000);

    int seqLen = w / 8;
    const int K = 15;             // must match OcrEngine.TOP_K
    // Width comes off the tensor, and the total must match exactly. The old form was a
    // hardcoded 13193 checked with `<`, so a *wider* head (the #44 re-prune) passed and every
    // timestep was scanned at the stale stride — the bug this guard now cannot miss.
    const int numClasses = recClassWidth(out, seqLen);
    if (numClasses <= 0) {
        LOGE("recTopK out unusable dims=%d w=%d h=%d c=%d total=%d seqLen=%d",
             out.dims, out.w, out.h, out.c, (int)out.total(), seqLen);
        return nullptr;
    }
    float *outData = (float *) out.data;

    jfloatArray jout = env->NewFloatArray(seqLen * K * 2);
    if (!jout) return nullptr;
    // Fill via a host-side staging buffer, then one SetFloatArrayRegion.
    // (Staging is seqLen*30 floats ≈ 15KB @250 steps vs 13MB full logits.)
    int stageN = seqLen * K * 2;
    float *stage = new (std::nothrow) float[stageN];
    if (!stage) return nullptr;
    for (int t = 0; t < seqLen; t++) {
        const float *row = outData + (long)t * numClasses;
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
            stage[(t * K + k) * 2 + 0] = (float)topIdx[k];
            stage[(t * K + k) * 2 + 1] = topVal[k];
        }
    }
    int64_t t2 = (int64_t)(ncnn::get_current_time() * 1000);
    env->SetFloatArrayRegion(jout, 0, stageN, stage);
    delete[] stage;
    int64_t t3 = (int64_t)(ncnn::get_current_time() * 1000);
    LOGI("recTopK w=%d seq=%d extract=%.1fms topk=%.1fms copy=%.1fms out=%d floats (full would be %d)",
         w, seqLen, (t1 - t0) / 1000.0, (t2 - t1) / 1000.0, (t3 - t2) / 1000.0,
         stageN, seqLen * numClasses);
    return jout;
}

struct DetNcnn {
    ncnn::Net net;
    std::string cachedOutName;
};

JNIEXPORT jlong JNICALL
Java_com_holopengin_instantjpdict_DetNcnn_create(JNIEnv *env, jclass, jstring paramPath_, jstring binPath_) {
    const char *paramPath = env->GetStringUTFChars(paramPath_, 0);
    const char *binPath = env->GetStringUTFChars(binPath_, 0);
    DetNcnn *det = new DetNcnn();
    ncnn::Option opt;
    // 1 thread + fp16 throughout (#25 det tune): min-of-3 same-session —
    // jpg 1522→892ms, screenshot 1396→855ms vs 4-thread fp32; box IoU ≥0.95
    // holds (mean 0.99+, worst single-box 0.63). Det bin is fp16-storage already.
    opt.num_threads = 1;
    opt.use_fp16_packed = true;
    opt.use_fp16_storage = true;
    opt.use_fp16_arithmetic = true;
    LOGI("DetNcnn threads=1 fp16=1");
    opt.use_packing_layout = true;
    det->net.opt = opt;
    ncnn::set_cpu_powersave(0);
    int ret = det->net.load_param(paramPath);
    if (ret != 0) {
        LOGE("Det load_param failed %d %s", ret, paramPath);
        delete det;
        env->ReleaseStringUTFChars(paramPath_, paramPath);
        env->ReleaseStringUTFChars(binPath_, binPath);
        return 0;
    }
    ret = det->net.load_model(binPath);
    if (ret != 0) {
        LOGE("Det load_model failed %d %s", ret, binPath);
        delete det;
        env->ReleaseStringUTFChars(paramPath_, paramPath);
        env->ReleaseStringUTFChars(binPath_, binPath);
        return 0;
    }
    env->ReleaseStringUTFChars(paramPath_, paramPath);
    env->ReleaseStringUTFChars(binPath_, binPath);
    LOGI("DetNcnn created param=%s", paramPath);
    return (jlong) det;
}

JNIEXPORT void JNICALL
Java_com_holopengin_instantjpdict_DetNcnn_destroy(JNIEnv *, jclass, jlong handle) {
    DetNcnn *det = (DetNcnn *) handle;
    if (det) {
        det->net.clear();
        delete det;
    }
}

JNIEXPORT jfloatArray JNICALL
Java_com_holopengin_instantjpdict_DetNcnn_inferNative(JNIEnv *env, jclass, jlong handle, jobject buffer, jint w, jint h) {
    DetNcnn *det = (DetNcnn *) handle;
    if (!det) return nullptr;
    float *data = (float *) env->GetDirectBufferAddress(buffer);
    if (!data) {
        LOGE("Det GetDirectBufferAddress null");
        return nullptr;
    }
    jlong capacity = env->GetDirectBufferCapacity(buffer);
    long expectedFloats = 1L * 3 * h * w;
    if (capacity < expectedFloats * 4) {
        LOGE("Det buffer too small %ld vs %ld", capacity, expectedFloats*4);
        return nullptr;
    }
    ncnn::Mat in(w, h, 3);
    for (int c = 0; c < 3; c++) {
        float *ptr = in.channel(c);
        long cOff = c * h * w;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                ptr[y * w + x] = data[cOff + y * w + x];
            }
        }
    }
    LOGI("Det input in0 w=%d h=%d c=3 total=%d mean0=%.3f", w, h, (int)in.total(), in.channel(0)[0]);
    ncnn::Extractor ex = det->net.create_extractor();
    ex.set_light_mode(true);
    int ret = ex.input("in0", in);
    if (ret != 0) {
        LOGE("Det input in0 failed %d", ret);
        return nullptr;
    }
    ncnn::Mat out;
    ret = -1;
    // Cached output name — try it first to avoid 6 extracts per infer (2-5ms)
    if (!det->cachedOutName.empty()) {
        ret = ex.extract(det->cachedOutName.c_str(), out);
        if (ret == 0) {
            LOGI("Det extract cached %s ok dims=%d w=%d h=%d c=%d total=%d", det->cachedOutName.c_str(), out.dims, out.w, out.h, out.c, (int)out.total());
        } else {
            LOGE("Det cached extract %s failed %d, trying others", det->cachedOutName.c_str(), ret);
            ret = -1;
        }
    }
    if (ret != 0) {
        const char* tryNames[] = {"out0", "sigmoid_0", "sigmoid", "sigmoid_62", "602", "603", "601", nullptr};
        for (int i = 0; tryNames[i]; i++) {
            ret = ex.extract(tryNames[i], out);
            LOGI("Det try %s ret=%d", tryNames[i], ret);
            if (ret == 0) {
                det->cachedOutName = tryNames[i];
                LOGI("Det extract %s ok dims=%d w=%d h=%d c=%d total=%d (cached)", tryNames[i], out.dims, out.w, out.h, out.c, (int)out.total());
                break;
            }
        }
    }
    if (ret != 0) {
        LOGE("Det extract failed for all names");
        return nullptr;
    }
    // out should be 960x960x1 or 1x960x960, total = w*h
    int total = (int)out.total();
    int expected = w * h;
    // DB outputs 1 channel, but may be w*h*1
    jfloatArray jout = env->NewFloatArray(total);
    if (!jout) return nullptr;
    float *outData = (float*)out.data;
    // Handle 3 dims case where c=1, h=960, w=960
    env->SetFloatArrayRegion(jout, 0, total, outData);
    // If total is w*h*? but we need w*h, and if out is smaller (e.g., 240*240 due to stride), we still return it and let Kotlin handle scaling
    LOGI("Det infer ok w=%d h=%d outTotal=%d", w, h, total);
    return jout;
}

} // extern "C"
