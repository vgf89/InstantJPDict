// Minimal depthwise-int8 repro: single 3x3s1 convdw, 96ch, int8 (8=1).
// Usage: mini_dconv param bin
#include <cstdio>
#include <vector>
#include "net.h"
int main(int argc, char** argv) {
    ncnn::Net net;
    ncnn::Option opt; opt.num_threads = 1; opt.use_packing_layout = true; opt.lightmode = false;
    net.opt = opt;
    if (net.load_param(argv[1])) { printf("param fail\n"); return 1; }
    if (net.load_model(argv[2])) { printf("model fail\n"); return 1; }
    int IW=argc>3?atoi(argv[3]):60, IH=argc>4?atoi(argv[4]):48, PACK=argc>5?atoi(argv[5]):1; ncnn::Mat in(IW, IH, 96 / PACK, (size_t)4u, PACK);
    for (size_t i = 0; i < in.total(); i++) ((float*)in.data)[i] = (float)(i % 13) / 13.f - 0.5f;
    ncnn::Extractor ex = net.create_extractor();
    ex.set_light_mode(false);
    ex.input("in0", in);
    ncnn::Mat out;
    int r = ex.extract("out0", out);
    printf("extract=%d out %d %d %d\n", r, out.w, out.h, out.c);
    return 0;
}
