// Shape+parity check: run fp32 vs int8 net on same input, print shapes + maxAbs.
// Usage: mini_cmp a.param a.bin b.param b.bin W H C
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <cmath>
#include <vector>
#include <string>
#include "net.h"
int main(int argc, char** argv) {
    int W = atoi(argv[5]), H = atoi(argv[6]), C = atoi(argv[7]);
    ncnn::Net A, B;
    ncnn::Option opt; opt.num_threads = 1; opt.use_packing_layout = true;
    A.opt = opt; B.opt = opt;
    if (A.load_param(argv[1]) || A.load_model(argv[2])) { printf("load A fail\n"); return 1; }
    if (B.load_param(argv[3]) || B.load_model(argv[4])) { printf("load B fail\n"); return 1; }
    ncnn::Mat in(W, H, C);
    for (size_t i = 0; i < in.total(); i++) ((float*)in.data)[i] = (float)(i % 17) / 17.f - 0.5f;
    ncnn::Extractor ea = A.create_extractor(); ea.set_light_mode(false); ea.input("in0", in);
    ncnn::Mat oa; int ra = ea.extract("out0", oa);
    ncnn::Extractor eb = B.create_extractor(); eb.set_light_mode(false); eb.input("in0", in);
    ncnn::Mat ob; int rb = eb.extract("out0", ob);
    printf("A ret=%d out %dx%dx%d p%d es%zu total=%d\n", ra, oa.w, oa.h, oa.c, (int)oa.elempack, oa.elemsize, (int)oa.total());
    printf("B ret=%d out %dx%dx%d p%d es%zu total=%d\n", rb, ob.w, ob.h, ob.c, (int)ob.elempack, ob.elemsize, (int)ob.total());
    if (ra == 0 && rb == 0 && oa.total() == ob.total()) {
        double mx = 0;
        float* pa = (float*)oa.data; float* pb = (float*)ob.data;
        // handle packed vs unpacked compare crudely via total
        for (size_t i = 0; i < oa.total(); i++) mx = fmax(mx, fabs((double)pa[i] - pb[i]));
        printf("maxAbs=%.6f\n", mx);
    }
    return 0;
}
