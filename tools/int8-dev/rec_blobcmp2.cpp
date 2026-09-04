// Compare two ncnn rec models (e.g. fp16 vs int8) on npy calibration inputs.
// Reports per-file maxAbs + timestep argmax agreement, plus totals.
// Usage: rec_compare fp32.param fp32.bin int8.param int8.bin filelist.txt W H
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <cmath>
#include <algorithm>
#include <cfloat>
#include <vector>
#include <string>
#include <fstream>
#include <algorithm>
#include "net.h"
static const char* g_blob = 0;

// minimal npy v1.0 float32 C-order loader
static bool load_npy(const std::string& path, std::vector<float>& out, std::vector<size_t>& shape) {
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) return false;
    char magic[6]; fread(magic, 1, 6, f);
    if (memcmp(magic, "\x93NUMPY", 6) != 0) { fclose(f); return false; }
    unsigned char vmaj, vmin; fread(&vmaj, 1, 1, f); fread(&vmin, 1, 1, f);
    uint16_t hlen; fread(&hlen, 2, 1, f);
    std::string header(hlen, ' ');
    fread(&header[0], 1, hlen, f);
    // parse 'shape': (3, 48, 480)
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

static int run_net(ncnn::Net& net, const std::vector<float>& data, int w, int h, std::vector<float>& out, std::vector<int>& argmax, int numClasses) {
    ncnn::Mat in(w, h, 3);
    const float* src = data.data();
    for (int c = 0; c < 3; c++) {
        float* ptr = in.channel(c);
        memcpy(ptr, src + (size_t)c * h * w, (size_t)h * w * 4);
    }
    ncnn::Extractor ex = net.create_extractor();
    ex.set_light_mode(true);
    ex.input("in0", in);
    ncnn::Mat o;
    int ret = ex.extract(g_blob ? g_blob : "out0", o);
    if (ret != 0) return ret;
    int total = (int)o.total();
    out.assign((float*)o.data, (float*)o.data + total);
    int seq = total / numClasses;
    argmax.assign(seq, 0);
    const float* d = out.data();
    for (int t = 0; t < seq; t++) {
        int best = 0; float bv = d[t * numClasses];
        for (int c = 1; c < numClasses; c++) {
            float v = d[t * numClasses + c];
            if (v > bv) { bv = v; best = c; }
        }
        argmax[t] = best;
    }
    return 0;
}

int main(int argc, char** argv) {
    if (argc > 8) g_blob = argv[8];
    if (argc != 8 && argc != 9) { fprintf(stderr, "usage: %s a.param a.bin b.param b.bin filelist.txt W H\n", argv[0]); return 1; }
    int W = atoi(argv[6]), H = atoi(argv[7]);
    const int NC = 18710;
    ncnn::Net A, B;
    ncnn::Option opt; opt.num_threads = 8; opt.use_packing_layout = true;
    A.opt = opt; B.opt = opt;
    if (A.load_param(argv[1]) || A.load_model(argv[2])) { fprintf(stderr, "load A failed\n"); return 1; }
    if (B.load_param(argv[3]) || B.load_model(argv[4])) { fprintf(stderr, "load B failed\n"); return 1; }
    std::ifstream lf(argv[5]);
    std::string path;
    double maxAbsAll = 0; long agreeT = 0, totalT = 0; int files = 0, filesAgree = 0;
    while (std::getline(lf, path)) {
        if (path.empty()) continue;
        std::vector<float> data; std::vector<size_t> shape;
        if (!load_npy(path, data, shape)) { fprintf(stderr, "npy load failed %s\n", path.c_str()); continue; }
        std::vector<float> oa, ob; std::vector<int> aa, ab;
        if (run_net(A, data, W, H, oa, aa, NC) || run_net(B, data, W, H, ob, ab, NC)) { fprintf(stderr, "infer failed %s\n", path.c_str()); continue; }
        if (oa.size() != ob.size()) { fprintf(stderr, "size mismatch %s %zu vs %zu\n", path.c_str(), oa.size(), ob.size()); continue; }
        double mx = 0; int agree = 0; long nNanA=0,nNanB=0,nInfB=0;
        for (size_t i = 0; i < oa.size(); i++) {
            float a=oa[i], b=ob[i];
            if (a!=a) nNanA++; if (b!=b) nNanB++; else if (!isfinite(b)) nInfB++;
            double d = fabs((double)a - (double)b);
            if (d==d && d > mx) mx = d;
        }
        if (nNanA||nNanB||nInfB) printf("  nanA=%ld nanB=%ld infB=%ld\n", nNanA, nNanB, nInfB);
        {
            std::vector<float> sa = oa, sb = ob;
            std::sort(sa.begin(), sa.end()); std::sort(sb.begin(), sb.end());
            double smx = 0; size_t n = std::min(sa.size(), sb.size());
            for (size_t i = 0; i < n; i++) { double d = fabs((double)sa[i]-sb[i]); if (d==d && d>smx) smx=d; }
            printf("  sortedMaxAbs=%.6f sizes=%zu/%zu\n", smx, sa.size(), sb.size());
        }
        for (size_t t = 0; t < aa.size(); t++) { if (aa[t] == ab[t]) agree++; }
        maxAbsAll = std::max(maxAbsAll, mx);
        agreeT += agree; totalT += aa.size(); files++;
        if (agree == (int)aa.size()) filesAgree++;
        printf("%s maxAbs=%.6f argmax=%d/%zu\n", path.c_str(), mx, agree, aa.size());
    }
    printf("TOTAL files=%d filesFullAgree=%d timesteps=%ld/%ld (%.2f%%) maxAbs=%.6f\n",
        files, filesAgree, agreeT, totalT, totalT ? 100.0 * agreeT / totalT : 0, maxAbsAll);
    return 0;
}
