// Thin JNI shell over the shared PP-OCRv6 ncnn core.
//
// All inference logic (net options, blob extraction, tensor-width guard,
// top-K packing) lives in ppocr_ncnn_core.{h,cpp}, which is shared verbatim
// with the PC app (InstantJPDictDecky native/ppocr_ncnn). Keep this file to
// buffer marshalling only — a fix that changes results belongs in the core.
#include <jni.h>
#include <vector>

#include "ppocr_ncnn_core.h"

using ppocr_ncnn::DetNet;
using ppocr_ncnn::RecNet;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_holopengin_instantjpdict_RecNcnn_create(JNIEnv *env, jclass, jstring paramPath_, jstring binPath_, jint targetW, jint numThreads) {
    const char *paramPath = env->GetStringUTFChars(paramPath_, 0);
    const char *binPath = env->GetStringUTFChars(binPath_, 0);

    RecNet *rec = ppocr_ncnn::rec_create(paramPath, binPath, targetW, numThreads);

    env->ReleaseStringUTFChars(paramPath_, paramPath);
    env->ReleaseStringUTFChars(binPath_, binPath);
    return (jlong) rec;
}

JNIEXPORT void JNICALL
Java_com_holopengin_instantjpdict_RecNcnn_destroy(JNIEnv *, jclass, jlong handle) {
    ppocr_ncnn::rec_destroy((RecNet *) handle);
}

JNIEXPORT jfloatArray JNICALL
Java_com_holopengin_instantjpdict_RecNcnn_inferNative(JNIEnv *env, jclass, jlong handle, jobject buffer, jint w, jint h) {
    RecNet *rec = (RecNet *) handle;
    if (!rec) return nullptr;

    float *data = (float *) env->GetDirectBufferAddress(buffer);
    if (!data) return nullptr;
    jlong capacity = env->GetDirectBufferCapacity(buffer);

    std::vector<float> out;
    if (!ppocr_ncnn::rec_infer(rec, data, (size_t) (capacity / 4), w, h, out)) return nullptr;

    jfloatArray jout = env->NewFloatArray((jsize) out.size());
    if (!jout) return nullptr;
    env->SetFloatArrayRegion(jout, 0, (jsize) out.size(), out.data());
    return jout;
}

JNIEXPORT jfloatArray JNICALL
Java_com_holopengin_instantjpdict_RecNcnn_inferTopKNative(JNIEnv *env, jclass, jlong handle, jobject buffer, jint w, jint h) {
    RecNet *rec = (RecNet *) handle;
    if (!rec) return nullptr;

    float *data = (float *) env->GetDirectBufferAddress(buffer);
    if (!data) return nullptr;
    jlong capacity = env->GetDirectBufferCapacity(buffer);

    std::vector<float> out;
    if (!ppocr_ncnn::rec_infer_topk(rec, data, (size_t) (capacity / 4), w, h, out)) return nullptr;

    jfloatArray jout = env->NewFloatArray((jsize) out.size());
    if (!jout) return nullptr;
    env->SetFloatArrayRegion(jout, 0, (jsize) out.size(), out.data());
    return jout;
}

JNIEXPORT jlong JNICALL
Java_com_holopengin_instantjpdict_DetNcnn_create(JNIEnv *env, jclass, jstring paramPath_, jstring binPath_) {
    const char *paramPath = env->GetStringUTFChars(paramPath_, 0);
    const char *binPath = env->GetStringUTFChars(binPath_, 0);

    DetNet *det = ppocr_ncnn::det_create(paramPath, binPath);

    env->ReleaseStringUTFChars(paramPath_, paramPath);
    env->ReleaseStringUTFChars(binPath_, binPath);
    return (jlong) det;
}

JNIEXPORT void JNICALL
Java_com_holopengin_instantjpdict_DetNcnn_destroy(JNIEnv *, jclass, jlong handle) {
    ppocr_ncnn::det_destroy((DetNet *) handle);
}

JNIEXPORT jfloatArray JNICALL
Java_com_holopengin_instantjpdict_DetNcnn_inferNative(JNIEnv *env, jclass, jlong handle, jobject buffer, jint w, jint h) {
    DetNet *det = (DetNet *) handle;
    if (!det) return nullptr;

    float *data = (float *) env->GetDirectBufferAddress(buffer);
    if (!data) return nullptr;
    jlong capacity = env->GetDirectBufferCapacity(buffer);

    std::vector<float> out;
    if (!ppocr_ncnn::det_infer(det, data, (size_t) (capacity / 4), w, h, out)) return nullptr;

    jfloatArray jout = env->NewFloatArray((jsize) out.size());
    if (!jout) return nullptr;
    env->SetFloatArrayRegion(jout, 0, (jsize) out.size(), out.data());
    return jout;
}

} // extern "C"
