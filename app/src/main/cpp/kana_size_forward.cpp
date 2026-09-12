// Direct implementation of the kanadiff forward pass — no ONNX, no ncnn, no runtime.
//
// The ncnn conversion produced degenerate output twice (a degenerate Permute 0=1 that never
// reordered, then a shifted weight stream), so the model is implemented directly instead. The
// op semantics were proven separately in numpy first: that reference reproduces ORT to 3.34e-06
// on the ten published vectors, which is what makes a hand port safe to write at all.
//
//   win  : 40 byte values -> byte_emb (256x32)         -> [40,32]
//   cat  : concat([emb, pos_block (40,16)], axis=1)    -> [40,48]
//   t    : transpose                                   -> [48,40]  channels-first
//   conv1: 48->64, k=5, pad=2, relu ; conv2: 64->64, k=3, pad=1, relu
//   max  : max over 40 positions                       -> [64]
//   z    : concat([max, base_emb[base] (20x16)])       -> [80]
//   fc1  : 80->128 + relu ; fc2: 128->1 -> logit
//
//   kanadiff_direct <assets-dir> <cases-dir> <n>
// Assets: byte_emb.f32, base_emb.f32, pos_block.f32, plus the eight weight tensors in
// weights.bin (conv1.w, conv1.b, conv2.w, conv2.b, fc1.w, fc1.b, fc2.w, fc2.b, in that order).
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

static const int SEQ = 40, EMB = 32, BLK = 16, CH = EMB + BLK;   // 48
static const int C1O = 64, K1 = 5, P1 = 2, C2O = 64, K2 = 3, P2 = 1;
static const int F1O = 128;

static std::vector<float> load(const std::string& path, size_t n) {
    std::vector<float> v(n);
    FILE* fp = fopen(path.c_str(), "rb");
    if (!fp) { fprintf(stderr, "cannot open %s\n", path.c_str()); exit(1); }
    if (fread(v.data(), sizeof(float), n, fp) != n) { fprintf(stderr, "short read %s\n", path.c_str()); exit(1); }
    fclose(fp);
    return v;
}

static std::vector<float> load_tail(FILE* fp, size_t n) {
    std::vector<float> v(n);
    if (fread(v.data(), sizeof(float), n, fp) != n) { fprintf(stderr, "short read weights\n"); exit(1); }
    return v;
}

// cross-correlation, as ONNX Conv: out[o][l] = sum_c sum_k w[o][c][k] * x[c][l + k - pad]
static void conv1d(const std::vector<float>& x, int cin, int L,
                   const std::vector<float>& w, const std::vector<float>& b,
                   int cout, int K, int pad, std::vector<float>& out) {
    out.assign((size_t)cout * L, 0.0f);
    for (int o = 0; o < cout; o++)
        for (int l = 0; l < L; l++) {
            float acc = b[o];
            for (int c = 0; c < cin; c++)
                for (int k = 0; k < K; k++) {
                    int j = l + k - pad;
                    if (j < 0 || j >= L) continue;
                    acc += w[((size_t)o * cin + c) * K + k] * x[(size_t)c * L + j];
                }
            out[(size_t)o * L + l] = acc;
        }
}

int main(int argc, char** argv) {
    if (argc != 4) { fprintf(stderr, "usage: %s assets cases n\n", argv[0]); return 1; }
    std::string A = argv[1];
    std::vector<float> byte_emb = load(A + "/byte_emb.f32", 256 * EMB);
    std::vector<float> base_emb = load(A + "/base_emb.f32", 20 * BLK);
    std::vector<float> pos_block = load(A + "/pos_block.f32", SEQ * BLK);

    // weights come from the fixture directory (argv[2]); only the three lookup tables live in
    // the app's asset directory (argv[1])
    FILE* fp = fopen((std::string(argv[2]) + "/weights.bin").c_str(), "rb");
    if (!fp) { fprintf(stderr, "cannot open weights.bin\n"); return 1; }
    std::vector<float> c1w = load_tail(fp, (size_t)C1O * CH * K1), c1b = load_tail(fp, C1O);
    std::vector<float> c2w = load_tail(fp, (size_t)C2O * C1O * K2), c2b = load_tail(fp, C2O);
    std::vector<float> f1w = load_tail(fp, (size_t)F1O * (C1O + BLK)), f1b = load_tail(fp, F1O);
    std::vector<float> f2w = load_tail(fp, F1O), f2b = load_tail(fp, 1);
    fclose(fp);

    int n = atoi(argv[3]);
    for (int k = 0; k < n; k++) {
        char p[512];
        snprintf(p, sizeof(p), "%s/case_%02d_win.bin", argv[2], k);
        std::vector<float> win = load(p, SEQ);           // 40 byte values, as floats
        snprintf(p, sizeof(p), "%s/case_%02d_base.bin", argv[2], k);
        std::vector<float> base = load(p, BLK);

        // embedding + constant block, transposed to channels-first
        std::vector<float> t((size_t)CH * SEQ, 0.0f);
        for (int s = 0; s < SEQ; s++) {
            int byte = (int)lrintf(win[s]);
            for (int e = 0; e < EMB; e++) t[(size_t)e * SEQ + s] = byte_emb[(size_t)byte * EMB + e];
            for (int e = 0; e < BLK; e++) t[(size_t)(EMB + e) * SEQ + s] = pos_block[(size_t)s * BLK + e];
        }

        std::vector<float> h1, h2;
        conv1d(t, CH, SEQ, c1w, c1b, C1O, K1, P1, h1);
        for (auto& v : h1) v = v > 0 ? v : 0.0f;
        conv1d(h1, C1O, SEQ, c2w, c2b, C2O, K2, P2, h2);
        for (auto& v : h2) v = v > 0 ? v : 0.0f;

        std::vector<float> z(C2O + BLK, -1e30f);
        for (int o = 0; o < C2O; o++) {
            float m = h2[(size_t)o * SEQ];
            for (int l = 1; l < SEQ; l++) m = std::max(m, h2[(size_t)o * SEQ + l]);
            z[o] = m;
        }
        for (int e = 0; e < BLK; e++) z[C2O + e] = base[e];

        std::vector<float> a(F1O, 0.0f);
        for (int o = 0; o < F1O; o++) {
            float acc = f1b[o];
            for (int c = 0; c < C2O + BLK; c++) acc += f1w[(size_t)o * (C2O + BLK) + c] * z[c];
            a[o] = acc > 0 ? acc : 0.0f;
        }
        float logit = f2b[0];
        for (int c = 0; c < F1O; c++) logit += f2w[c] * a[c];
        printf("%d\t%.10f\n", k, logit);
    }
    return 0;
}
