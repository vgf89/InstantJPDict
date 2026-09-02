#include <jni.h>
#include <android/log.h>
#include <string>
#include "ncnn/net.h"
#include "ncnn/mat.h"
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
Java_com_holopengin_instantjpdict_RecNcnn_create(JNIEnv *env, jclass, jstring paramPath_, jstring binPath_, jint targetW) {
    const char *paramPath = env->GetStringUTFChars(paramPath_, 0);
    const char *binPath = env->GetStringUTFChars(binPath_, 0);

    RecNcnn *rec = new RecNcnn();
    rec->targetW = targetW;
    rec->seqLen = targetW / 8;

    ncnn::Option opt;
    opt.num_threads = 4;
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

    env->ReleaseStringUTFChars(paramPath_, paramPath);
    env->ReleaseStringUTFChars(binPath_, binPath);

    LOGI("RecNcnn created W=%d seq=%d param=%s", targetW, rec->seqLen, paramPath);
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
    int ret = ex.extract("out0", out);
    if (ret != 0) {
        LOGE("extract out0 failed %d", ret);
        return nullptr;
    }

    // out shape: [18710, seqLen, 1] or [seqLen, 18710]? Check dims
    // From param tail: Gemm 8 -> Softmax out0 with dims seqLen x 18710?
    // pnnx validation says output [1, W/8, 18710] -> seqLen=8 for w64, 18710 classes
    // ncnn Mat for that would be w=18710, h=seqLen, c=1 or w=seqLen, h=18710?
    // Need to handle both. Log dims.
    LOGI("ncnn out dims=%d w=%d h=%d c=%d total=%d", out.dims, out.w, out.h, out.c, (int)out.total());
    // For w64: seq=8, classes=18710 -> expect out.w=18710, out.h=8, out.c=1 (or transposed)
    // We'll copy as flat float array seqLen * 18710 in row-major seqLen x classes
    int seqLen = rec->seqLen;
    int numClasses = 18710;
    // Allocate output float array
    jfloatArray jout = env->NewFloatArray(seqLen * numClasses);
    if (!jout) return nullptr;

    // Copy data - need to handle layout
    // If out is 2D (w=18710, h=seqLen), data is row-major h * w
    // If out is 3D, handle accordingly
    float *outData = (float *) out.data;
    // out.total() should be seqLen * 18710
    if ((int)out.total() != seqLen * numClasses) {
        LOGE("out total mismatch %d vs %d", (int)out.total(), seqLen * numClasses);
        // Still try to copy min
        int n = std::min((int)out.total(), seqLen * numClasses);
        env->SetFloatArrayRegion(jout, 0, n, outData);
        return jout;
    }

    // ncnn stores as c * h * w contiguous, with w innermost
    // For dims=2, w=18710, h=8 -> data is [h][w]
    // For dims=3, check
    env->SetFloatArrayRegion(jout, 0, seqLen * numClasses, outData);
    return jout;
}

struct DetNcnn {
    ncnn::Net net;
};

JNIEXPORT jlong JNICALL
Java_com_holopengin_instantjpdict_DetNcnn_create(JNIEnv *env, jclass, jstring paramPath_, jstring binPath_) {
    const char *paramPath = env->GetStringUTFChars(paramPath_, 0);
    const char *binPath = env->GetStringUTFChars(binPath_, 0);
    DetNcnn *det = new DetNcnn();
    ncnn::Option opt;
    opt.num_threads = 4;
    opt.use_fp16_packed = false;
    opt.use_fp16_storage = false;
    opt.use_fp16_arithmetic = false;
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
    ncnn::Extractor ex = det->net.create_extractor();
    ex.set_light_mode(true);
    int ret = ex.input("in0", in);
    if (ret != 0) {
        LOGE("Det input in0 failed %d", ret);
        return nullptr;
    }
    ncnn::Mat out;
    // Try common output names: out0, sigmoid_0, 602, etc. PNNX det uses in0->...->sigmoid
    const char* tryNames[] = {"out0", "sigmoid_0", "sigmoid", "601", "602", "603", nullptr};
    ret = -1;
    for (int i = 0; tryNames[i]; i++) {
        ret = ex.extract(tryNames[i], out);
        if (ret == 0) {
            LOGI("Det extract %s ok dims=%d w=%d h=%d c=%d total=%d", tryNames[i], out.dims, out.w, out.h, out.c, (int)out.total());
            break;
        }
    }
    if (ret != 0) {
        // Fallback: try to get any output via dump
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
