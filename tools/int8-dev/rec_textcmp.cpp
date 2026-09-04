// Text-level parity: CTC-greedy decode both models with vocab, compare strings + CER vs ground truth.
// Usage: rec_textcmp a.param a.bin b.param b.bin vocab.json filelist.txt W H [gt_dir]
// filelist lines: /path/to/calib_000.npy ; gt file = <basename without calib_>.txt? We map via --gtmap file.
// Simpler: each npy was generated from a source image; we store map npy->src in listfile2.
// For now: optional 8th arg = text file with lines "<npy> <srcimg>", 9th arg = dir with <srcbase>.txt ground truth.
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <cmath>
#include <vector>
#include <string>
#include <fstream>
#include <map>
#include "net.h"

static bool load_npy(const std::string& path, std::vector<float>& out) {
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) return false;
    char magic[6]; fread(magic, 1, 6, f);
    if (memcmp(magic, "\x93NUMPY", 6) != 0) { fclose(f); return false; }
    unsigned char vmaj, vmin; fread(&vmaj, 1, 1, f); fread(&vmin, 1, 1, f);
    uint16_t hlen; fread(&hlen, 2, 1, f);
    fseek(f, hlen, SEEK_CUR);
    size_t n = 0; fseek(f, 0, SEEK_END); long end = ftell(f);
    // recompute: total file - header
    n = 0;
    // caller knows size; just read rest
    fseek(f, 10 + hlen, SEEK_SET);
    // determine count from file size
    n = (end - (10 + hlen)) / 4;
    out.resize(n);
    size_t r = fread(out.data(), 4, n, f);
    fclose(f);
    return r == n;
}

static int run_net(ncnn::Net& net, const std::vector<float>& data, int w, int h, std::vector<float>& out) {
    ncnn::Mat in(w, h, 3);
    const float* src = data.data();
    for (int c = 0; c < 3; c++) memcpy(in.channel(c), src + (size_t)c * h * w, (size_t)h * w * 4);
    ncnn::Extractor ex = net.create_extractor();
    ex.set_light_mode(true);
    ex.input("in0", in);
    ncnn::Mat o;
    if (ex.extract("out0", o)) return -1;
    int total = (int)o.total();
    out.assign((float*)o.data, (float*)o.data + total);
    return 0;
}

// ctc greedy decode to class-id string
static std::vector<int> ctc_argmax_collapse(const std::vector<float>& logits, int seq, int nc) {
    std::vector<int> res;
    int prev = 0;
    for (int t = 0; t < seq; t++) {
        const float* s = &logits[(size_t)t * nc];
        int best = 0; float bv = s[0];
        for (int c = 1; c < nc; c++) if (s[c] > bv) { bv = s[c]; best = c; }
        if (best == 0) { prev = 0; continue; }
        if (best == prev) continue;
        res.push_back(best);
        prev = best;
    }
    return res;
}

// edit distance over class-id vectors
static int edit_dist(const std::vector<int>& a, const std::vector<int>& b) {
    int n = a.size(), m = b.size();
    std::vector<int> dp((n + 1) * (m + 1), 0);
    for (int i = 0; i <= n; i++) dp[i * (m + 1)] = i;
    for (int j = 0; j <= m; j++) dp[j] = j;
    for (int i = 1; i <= n; i++) for (int j = 1; j <= m; j++)
        dp[i * (m + 1) + j] = std::min({dp[(i-1) * (m+1) + j] + 1, dp[i * (m+1) + j-1] + 1, dp[(i-1) * (m+1) + j-1] + (a[i-1] == b[j-1] ? 0 : 1)});
    return dp[n * (m + 1) + m];
}

int main(int argc, char** argv) {
    if (argc < 8) { fprintf(stderr, "usage: %s a.param a.bin b.param b.bin vocab.json filelist.txt W H\n", argv[0]); return 1; }
    int W = atoi(argv[7]), H = atoi(argv[8]);
    const int NC = 18710;
    ncnn::Net A, B;
    ncnn::Option opt; opt.num_threads = 8; opt.use_packing_layout = true;
    A.opt = opt; B.opt = opt;
    if (A.load_param(argv[1]) || A.load_model(argv[2])) return 1;
    if (B.load_param(argv[3]) || B.load_model(argv[4])) return 1;
    std::ifstream lf(argv[6]);
    std::string path;
    long files = 0, match = 0, distSum = 0, lenSum = 0;
    while (std::getline(lf, path)) {
        if (path.empty()) continue;
        std::vector<float> data;
        if (!load_npy(path, data)) { fprintf(stderr, "npy fail %s\n", path.c_str()); continue; }
        std::vector<float> oa, ob;
        if (run_net(A, data, W, H, oa) || run_net(B, data, W, H, ob)) { fprintf(stderr, "infer fail\n"); continue; }
        if (oa.size() != ob.size()) { fprintf(stderr, "size mismatch\n"); continue; }
        int seq = oa.size() / NC;
        auto ca = ctc_argmax_collapse(oa, seq, NC);
        auto cb = ctc_argmax_collapse(ob, seq, NC);
        int d = edit_dist(ca, cb);
        distSum += d; lenSum += ca.size() ? ca.size() : 1; files++;
        if (d == 0) match++;
        else printf("DIFF %s alen=%zu blen=%zu ed=%d\n", path.c_str(), ca.size(), cb.size(), d);
    }
    printf("TEXT files=%ld exact=%ld (%.1f%%) CERlike=%ld/%ld=%.3f\n", files, match, 100.0 * match / (files ? files : 1), distSum, lenSum, (double)distSum / (lenSum ? lenSum : 1));
    return 0;
}
