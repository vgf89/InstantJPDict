// Platform-independent PP-OCRv6 ncnn inference core.
//
// This file is the single source of truth for the DetNcnn/RecNcnn kernel and
// is compiled by BOTH apps:
//   - Android (InstantJPDict): app/src/main/cpp/ncnn_jni.cpp is a thin JNI
//     wrapper over this core.
//   - PC (InstantJPDictDecky): native/ppocr_ncnn/ppocr_ncnn_core.* is a copy
//     of this file; ppocr_ncnn_capi.cpp exposes it to Rust.
//
// Keep this file and ppocr_ncnn_core.cpp byte-identical between the two
// repositories (PC: tools/sync_ppocr_core.sh can check/update the copy).
#pragma once

#include <cstddef>
#include <string>
#include <vector>

#include "ncnn/net.h"

namespace ppocr_ncnn {

struct RecNet {
    ncnn::Net net;
    int targetW;
    int seqLen;
};

struct DetNet {
    ncnn::Net net;
    std::string cachedOutName;
};

/// Load the dynamic-width CTC recognizer. Returns nullptr on failure.
/// seqLen is targetW / 8; per-call sequence length is derived from the
/// actual input width instead (dynamic width, #23).
RecNet* rec_create(const char* paramPath, const char* binPath, int targetW, int numThreads);

void rec_destroy(RecNet* rec);

/// Run one [1,3,48,w] NCHW float input (dataFloats must be 3*48*w).
/// Full-logits path: fills `out` with seqLen * numClasses floats, row-major
/// with classes innermost. Returns false on failure.
bool rec_infer(RecNet* rec, const float* data, size_t dataFloats, int w, int h, std::vector<float>& out);

/// Run one [1,3,48,w] NCHW float input (dataFloats must be 3*48*w).
/// Top-K path (#42): fills `out` with seqLen * K * 2 floats, packed
/// [idx0,val0, idx1,val1, ...] per timestep (indices stored as float, exact
/// for ids < 2^24). K is [rec_top_k]. Returns false on failure.
bool rec_infer_topk(RecNet* rec, const float* data, size_t dataFloats, int w, int h, std::vector<float>& out);

/// Top-K per CTC timestep emitted by rec_infer_topk; must match
/// OcrEngine.TOP_K on the Kotlin side.
int rec_top_k();

/// Load the DB segmentation detector. Returns nullptr on failure.
DetNet* det_create(const char* paramPath, const char* binPath);

void det_destroy(DetNet* det);

/// Run one [1,3,h,w] NCHW float input (dataFloats must be 3*h*w).
/// Fills `out` with the raw probability map (w*h floats). Returns false on
/// failure.
bool det_infer(DetNet* det, const float* data, size_t dataFloats, int w, int h, std::vector<float>& out);

} // namespace ppocr_ncnn
