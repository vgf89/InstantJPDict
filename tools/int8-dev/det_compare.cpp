// Compare two ncnn det models (fp16 vs int8) on npy calibration inputs.
// Reports per-file prob-map maxAbs/meanAbs + thresh-0.3 mask agreement.
// Usage: det_compare a.param a.bin b.param b.bin filelist.txt [dump_dir]
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <cmath>
#include <vector>
#include <string>
#include <fstream>
#include <algorithm>
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

static int run_net(ncnn::Net& net, const std::vector<float>& data, int w, int h, std::vector<float>& out) {
    ncnn::Mat in(w, h, 3);
    const float* src = data.data();
    for (int c = 0; c < 3; c++) {
        float* ptr = in.channel(c);
        memcpy(ptr, src + (size_t)c * h * w, (size_t)h * w * 4);
    }
    ncnn::Extractor ex = net.create_extractor();
    ex.set_light_mode(false);
    ex.input("in0", in);
    ncnn::Mat o;
    int ret = ex.extract("out0", o);
    if (ret != 0) {
        // fallback: try sigmoid blob name
        ret = ex.extract("sigmoid_62", o);
        if (ret != 0) return ret;
    }
    int total = (int)o.total();
    out.assign((float*)o.data, (float*)o.data + total);
    return 0;
}

int main(int argc, char** argv) {
    if (argc < 6) { fprintf(stderr, "usage: %s a.param a.bin b.param b.bin filelist.txt [dump_dir]\n", argv[0]); return 1; }
    const int W = 960, H = 960;
    const float THRESH = 0.3f;
    std::string dump_dir = argc >= 7 ? argv[6] : "";
    ncnn::Net A, B;
    ncnn::Option opt; opt.num_threads = 8; opt.use_packing_layout = true;
    A.opt = opt; B.opt = opt;
    if (A.load_param(argv[1]) || A.load_model(argv[2])) { fprintf(stderr, "load A failed\n"); return 1; }
    if (B.load_param(argv[3]) || B.load_model(argv[4])) { fprintf(stderr, "load B failed\n"); return 1; }
    std::ifstream lf(argv[5]);
    std::string path;
    double maxAbsAll = 0; long agreeP = 0, totalP = 0; int files = 0;
    double sumAbsAll = 0; long totalEls = 0;
    int idx = 0;
    while (std::getline(lf, path)) {
        if (path.empty()) continue;
        std::vector<float> data; std::vector<size_t> shape;
        if (!load_npy(path, data, shape)) { fprintf(stderr, "npy load failed %s\n", path.c_str()); continue; }
        std::vector<float> oa, ob;
        if (run_net(A, data, W, H, oa) || run_net(B, data, W, H, ob)) { fprintf(stderr, "infer failed %s\n", path.c_str()); continue; }
        if (oa.empty() || ob.empty()) { fprintf(stderr, "empty out %s\n", path.c_str()); continue; }
        if (oa.size() != ob.size()) { fprintf(stderr, "size mismatch %s %zu vs %zu\n", path.c_str(), oa.size(), ob.size()); continue; }
        double mx = 0, sum = 0; long agree = 0;
        float aMin = 1e9, aMax = -1e9, bMin = 1e9, bMax = -1e9;
        for (size_t i = 0; i < oa.size(); i++) {
            double d = fabs((double)oa[i] - (double)ob[i]);
            mx = std::max(mx, d); sum += d;
            aMin = std::min(aMin, oa[i]); aMax = std::max(aMax, oa[i]);
            bMin = std::min(bMin, ob[i]); bMax = std::max(bMax, ob[i]);
            bool am = oa[i] > THRESH, bm = ob[i] > THRESH;
            if (am == bm) agree++;
        }
        maxAbsAll = std::max(maxAbsAll, mx);
        sumAbsAll += sum; totalEls += oa.size();
        agreeP += agree; totalP += oa.size(); files++;
        printf("%s maxAbs=%.6f meanAbs=%.6f maskAgree=%.2f%% aMin=%.3f aMax=%.3f bMin=%.3f bMax=%.3f\n",
            path.c_str(), mx, sum / oa.size(), 100.0 * agree / oa.size(), aMin, aMax, bMin, bMax);
        if (!dump_dir.empty()) {
            char pa[1024], pb[1024];
            snprintf(pa, sizeof(pa), "%s/prob_a_%03d.npy", dump_dir.c_str(), idx);
            snprintf(pb, sizeof(pb), "%s/prob_b_%03d.npy", dump_dir.c_str(), idx);
            // dump raw float32 prob maps with minimal npy header for python postprocess
            auto dump = [](const char* p, const std::vector<float>& v, int h, int w) {
                FILE* f = fopen(p, "wb");
                if (!f) return;
                char hdr[128];
                int hlen = snprintf(hdr, sizeof(hdr), "{'descr': '<f4', 'fortran_order': False, 'shape': (%d, %d), }", h, w);
                int pad = 64 - ((10 + hlen) % 64);
                std::string header = std::string(hdr, hlen) + std::string(pad, ' ') + "\n";
                uint16_t hl = header.size();
                fwrite("\x93NUMPY", 1, 6, f);
                fputc(1, f); fputc(0, f);
                fwrite(&hl, 2, 1, f);
                fwrite(header.data(), 1, header.size(), f);
                fwrite(v.data(), 4, v.size(), f);
                fclose(f);
            };
            dump(pa, oa, H, W);
            dump(pb, ob, H, W);
        }
        idx++;
    }
    printf("TOTAL files=%d maskAgree=%ld/%ld (%.3f%%) maxAbs=%.6f meanAbs=%.6f\n",
        files, agreeP, totalP, totalP ? 100.0 * agreeP / totalP : 0, maxAbsAll,
        totalEls ? sumAbsAll / totalEls : 0);
    return 0;
}
