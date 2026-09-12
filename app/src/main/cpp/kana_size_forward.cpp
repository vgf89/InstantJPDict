// Direct implementation of the kanadiff kana-size model — no ONNX, no ncnn, no runtime.
//
// The ncnn conversion produced degenerate output twice (a degenerate `Permute 0=1` that never
// reordered, then a weight stream shifted by a layer removed from the param while its bytes
// stayed in the bin), so the model is implemented directly. The arithmetic below is the same
// code that was verified against the model author's ten published validation vectors on the
// host: worst deviation 2.86e-06 against their ORT logits, 0/10 mismatches.
//
//   win  : 40 byte values -> byte_emb (256x32)         -> [40,32]
//   cat  : concat([emb, pos_block (40,16)], axis=1)    -> [40,48]
//   t    : transpose                                   -> [48,40]  channels-first
//   conv1: 48->64, k=5, pad=2, relu ; conv2: 64->64, k=3, pad=1, relu
//   max  : max over 40 positions                       -> [64]
//   z    : concat([max, base_emb[base] (20x16)])       -> [80]
//   fc1  : 80->128 + relu ; fc2: 128->1 -> logit       (sigmoid gives p(big))
//
// The tables and weights arrive as direct ByteBuffers built on the Kotlin side from the APK
// assets; sizes are asserted rather than assumed, because reading past a short buffer would
// produce plausible numbers instead of an error.
#include <jni.h>
#include <android/log.h>

#include <cmath>
#include <cstring>
#include <vector>

#define LOG_TAG "KanaSize"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int SEQ = 40, EMB = 32, BLK = 16, CH = EMB + BLK;   // 48
constexpr int C1O = 64, K1 = 5, P1 = 2;
constexpr int C2O = 64, K2 = 3, P2 = 1;
constexpr int F1O = 128;

constexpr size_t TABLES_FLOATS = 256 * EMB + 20 * BLK + SEQ * BLK;   // byte_emb | base_emb | pos_block
constexpr size_t WEIGHTS_FLOATS =
        (size_t) C1O * CH * K1 + C1O +           // conv1.weight, conv1.bias
        (size_t) C2O * C1O * K2 + C2O +          // conv2.weight, conv2.bias
        (size_t) F1O * (C2O + BLK) + F1O +       // fc1.weight, fc1.bias
        (size_t) F1O + 1;                        // fc2.weight, fc2.bias

struct Model {
    std::vector<float> byte_emb, base_emb, pos_block;
    std::vector<float> c1w, c1b, c2w, c2b, f1w, f1b, f2w, f2b;
};

// cross-correlation, as ONNX Conv: out[o][l] = sum_c sum_k w[o][c][k] * x[c][l + k - pad]
void conv1d(const float* x, int cin, int L, const float* w, const float* b,
            int cout, int K, int pad, float* out) {
    for (int o = 0; o < cout; o++) {
        for (int l = 0; l < L; l++) {
            float acc = b[o];
            for (int c = 0; c < cin; c++) {
                for (int k = 0; k < K; k++) {
                    int j = l + k - pad;
                    if (j < 0 || j >= L) continue;
                    acc += w[((size_t) o * cin + c) * K + k] * x[(size_t) c * L + j];
                }
            }
            out[(size_t) o * L + l] = acc;
        }
    }
}

float logitFor(const Model& m, const int* win, int base) {
    // embedding + constant block, transposed to channels-first
    std::vector<float> t((size_t) CH * SEQ, 0.0f);
    for (int s = 0; s < SEQ; s++) {
        int byte = win[s] & 0xFF;
        for (int e = 0; e < EMB; e++) t[(size_t) e * SEQ + s] = m.byte_emb[(size_t) byte * EMB + e];
        for (int e = 0; e < BLK; e++) t[(size_t) (EMB + e) * SEQ + s] = m.pos_block[(size_t) s * BLK + e];
    }

    std::vector<float> h1((size_t) C1O * SEQ), h2((size_t) C2O * SEQ);
    conv1d(t.data(), CH, SEQ, m.c1w.data(), m.c1b.data(), C1O, K1, P1, h1.data());
    for (auto& v : h1) v = v > 0 ? v : 0.0f;
    conv1d(h1.data(), C1O, SEQ, m.c2w.data(), m.c2b.data(), C2O, K2, P2, h2.data());
    for (auto& v : h2) v = v > 0 ? v : 0.0f;

    std::vector<float> z(C2O + BLK);
    for (int o = 0; o < C2O; o++) {
        float mx = h2[(size_t) o * SEQ];
        for (int l = 1; l < SEQ; l++) mx = std::max(mx, h2[(size_t) o * SEQ + l]);
        z[o] = mx;
    }
    base = (base < 0 || base >= 20) ? 0 : base;
    for (int e = 0; e < BLK; e++) z[C2O + e] = m.base_emb[(size_t) base * BLK + e];

    std::vector<float> a(F1O, 0.0f);
    for (int o = 0; o < F1O; o++) {
        float acc = m.f1b[o];
        for (int c = 0; c < C2O + BLK; c++) acc += m.f1w[(size_t) o * (C2O + BLK) + c] * z[c];
        a[o] = acc > 0 ? acc : 0.0f;
    }
    float logit = m.f2b[0];
    for (int c = 0; c < F1O; c++) logit += m.f2w[c] * a[c];
    return logit;
}

std::vector<float> readFloats(JNIEnv* env, jobject buf, jint floats, size_t expected, const char* what) {
    if (floats < 0 || (size_t) floats != expected) {
        LOGE("%s: got %d floats, expected %zu", what, floats, expected);
        return {};
    }
    void* p = env->GetDirectBufferAddress(buf);
    if (p == nullptr) {
        LOGE("%s: not a direct buffer", what);
        return {};
    }
    std::vector<float> out(expected);
    memcpy(out.data(), p, expected * sizeof(float));
    return out;
}

Model* asModel(jlong h) { return reinterpret_cast<Model*>(h); }

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_holopengin_instantjpdict_KanaSizeNcnn_create(
        JNIEnv* env, jclass, jobject tablesBuf, jint tablesFloats,
        jobject weightsBuf, jint weightsFloats) {
    std::vector<float> tables = readFloats(env, tablesBuf, tablesFloats, TABLES_FLOATS, "tables");
    std::vector<float> weights = readFloats(env, weightsBuf, weightsFloats, WEIGHTS_FLOATS, "weights");
    if (tables.empty() || weights.empty()) return 0;

    auto* m = new Model();
    size_t o = 0;
    m->byte_emb.assign(tables.begin() + o, tables.begin() + o + 256 * EMB); o += 256 * EMB;
    m->base_emb.assign(tables.begin() + o, tables.begin() + o + 20 * BLK); o += 20 * BLK;
    m->pos_block.assign(tables.begin() + o, tables.begin() + o + SEQ * BLK);

    o = 0;
    auto take = [&](size_t n) { std::vector<float> v(weights.begin() + o, weights.begin() + o + n); o += n; return v; };
    m->c1w = take((size_t) C1O * CH * K1); m->c1b = take(C1O);
    m->c2w = take((size_t) C2O * C1O * K2); m->c2b = take(C2O);
    m->f1w = take((size_t) F1O * (C2O + BLK)); m->f1b = take(F1O);
    m->f2w = take(F1O); m->f2b = take(1);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "model loaded: 48x40 window, %d params",
                        (int) (TABLES_FLOATS + WEIGHTS_FLOATS));
    return reinterpret_cast<jlong>(m);
}

JNIEXPORT void JNICALL
Java_com_holopengin_instantjpdict_KanaSizeNcnn_destroy(JNIEnv*, jclass, jlong handle) {
    delete asModel(handle);
}

/** Logits for [n] windows: win is n*40 byte values, base is n pair indices (0..19). */
JNIEXPORT jfloatArray JNICALL
Java_com_holopengin_instantjpdict_KanaSizeNcnn_batch(
        JNIEnv* env, jclass, jlong handle, jintArray winArr, jintArray baseArr, jint n) {
    Model* m = asModel(handle);
    if (m == nullptr || n <= 0) return nullptr;
    if (env->GetArrayLength(winArr) < n * SEQ || env->GetArrayLength(baseArr) < n) {
        LOGE("batch: win/base shorter than n=%d", n);
        return nullptr;
    }
    std::vector<int> win((size_t) n * SEQ);
    std::vector<int> bases(n);
    env->GetIntArrayRegion(winArr, 0, n * SEQ, win.data());
    env->GetIntArrayRegion(baseArr, 0, n, bases.data());

    std::vector<float> out(n);
    for (int i = 0; i < n; i++) out[i] = logitFor(*m, win.data() + (size_t) i * SEQ, bases[i]);

    jfloatArray res = env->NewFloatArray(n);
    if (res != nullptr) env->SetFloatArrayRegion(res, 0, n, out.data());
    return res;
}

}  // extern "C"
