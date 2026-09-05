// Squish-curve probe (#24 task 3): single rec model, pairs of <refnpy> <testnpy> <tag>
// with ARBITRARY (C,H,W) shapes. CTC-argmax-collapse to class ids, edit distance.
// Usage: rec_squish model.param model.bin pairs.txt
// pairs.txt line: <ref_npy_path> <test_npy_path> <tag>   (tag e.g. f080, horiz/f080)
// Output per line: <tag> ed=<e> alen=<a> blen=<b> ref=<ref>   + TOTAL per tag on stderr.
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <cmath>
#include <vector>
#include <string>
#include <fstream>
#include <sstream>
#include <map>
#include "net.h"

static bool load_npy(const std::string& path, std::vector<float>& out, std::vector<size_t>& shape) {
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) return false;
    char magic[6]; fread(magic, 1, 6, f);
    if (memcmp(magic, "\x93NUMPY", 6) != 0) { fclose(f); return false; }
    unsigned char vmaj, vmin; fread(&vmaj, 1, 1, f); fread(&vmin, 1, 1, f);
    uint16_t hlen; fread(&hlen, 2, 1, f);
    std::string header(hlen, ' ');
    fread(&header[0], 1, hlen, f);
    auto p = header.find('(');
    auto q = header.find(')');
    std::string dims = header.substr(p + 1, q - p - 1);
    shape.clear();
    std::string cur;
    for (char c : dims + ",") {
        if (c == ',' || c == ' ' || c == '(' || c == ')') {
            if (!cur.empty() && cur != " ") { shape.push_back(std::stoul(cur)); cur.clear(); }
        } else cur += c;
    }
    size_t n = 1; for (auto s : shape) n *= s;
    out.resize(n);
    size_t r = fread(out.data(), 4, n, f);
    fclose(f);
    return r == n;
}

static int run_net(ncnn::Net& net, const std::vector<float>& data, const std::vector<size_t>& shape, std::vector<float>& out) {
    // shape is numpy (C,H,W)
    if (shape.size() != 3 || shape[0] != 3 || shape[1] != 48) return -2;
    int w = (int)shape[2], h = (int)shape[1];
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

static std::vector<int> ctc_collapse(const std::vector<float>& logits, int nc) {
    int seq = (int)logits.size() / nc;
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
    if (argc != 4) { fprintf(stderr, "usage: %s model.param model.bin pairs.txt\n", argv[0]); return 1; }
    const int NC = 18710;
    ncnn::Net net;
    ncnn::Option opt; opt.num_threads = 8; opt.use_packing_layout = true;
    net.opt = opt;
    if (net.load_param(argv[1]) || net.load_model(argv[2])) { fprintf(stderr, "load failed\n"); return 1; }
    std::ifstream pf(argv[3]);
    std::string line;
    std::map<std::string, long> edSum, chSum, nPair, nExact;
    while (std::getline(pf, line)) {
        if (line.empty()) continue;
        std::istringstream ss(line);
        std::string rp, tp, tag;
        ss >> rp >> tp >> tag;
        std::vector<float> rd, td; std::vector<size_t> rs, ts;
        if (!load_npy(rp, rd, rs) || !load_npy(tp, td, ts)) { fprintf(stderr, "npy load failed %s\n", line.c_str()); continue; }
        std::vector<float> ro, to;
        if (run_net(net, rd, rs, ro) || run_net(net, td, ts, to)) { fprintf(stderr, "infer failed %s\n", line.c_str()); continue; }
        auto a = ctc_collapse(ro, NC), b = ctc_collapse(to, NC);
        if (a.empty()) continue; // no reference signal
        int e = edit_dist(a, b);
        printf("%s ed=%d alen=%zu blen=%zu ref=%s\n", tag.c_str(), e, a.size(), b.size(), rp.c_str());
        edSum[tag] += e; chSum[tag] += a.size(); nPair[tag]++; if (e == 0) nExact[tag]++;
    }
    for (auto& kv : edSum)
        fprintf(stderr, "TOTAL %s pairs=%ld exact=%.1f%% CER=%.4f\n", kv.first.c_str(), nPair[kv.first],
            100.0 * nExact[kv.first] / nPair[kv.first], (double)kv.second / chSum[kv.first]);
    return 0;
}
