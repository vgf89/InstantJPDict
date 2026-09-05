#!/usr/bin/env python3
"""Generate ncnn2table npy calibration inputs for PP-OCRv6 det (DB, 960x960).
Matches OcrEngine.kt detect() runtime preprocessing exactly:
  scale = 960 / max(origW, origH), resize bilinear to resizeW x resizeH
  letterbox centered on 960x960 gray (128,128,128)
  NCHW float32 ImageNet norm: (px/255 - mean)/std,
  mean=[0.485,0.456,0.406] std=[0.229,0.224,0.225], R->ch0
Sources: tools/onnx_quantization/calibration_data/detect/ (committed, per #22).
Output npy shape: (3,960,960) float32 — ncnn2table shape=[960,960,3] type=1.
Usage: python3 tools/gen_det_calib_npy.py --out /tmp/det_calib
"""
import argparse
import glob
import os

import numpy as np
from PIL import Image

MODEL = 960
MEAN = np.array([0.485, 0.456, 0.406], dtype=np.float32)
STD = np.array([0.229, 0.224, 0.225], dtype=np.float32)


def preprocess(path):
    im = Image.open(path).convert("RGB")
    rw, rh = im.size
    if rw < 4 or rh < 4:
        raise ValueError(f"too small: {path} {rw}x{rh}")
    scale = MODEL / max(rw, rh)
    resize_w = max(round(rw * scale), 32)
    resize_h = max(round(rh * scale), 32)
    im_r = im.resize((resize_w, resize_h), Image.BILINEAR)
    # letterbox centered on 128 gray
    canvas = Image.new("RGB", (MODEL, MODEL), (128, 128, 128))
    canvas.paste(im_r, ((MODEL - resize_w) // 2, (MODEL - resize_h) // 2))
    arr = np.asarray(canvas).astype(np.float32) / 255.0
    norm = (arr - MEAN[None, None, :]) / STD[None, None, :]
    out = np.transpose(norm, (2, 0, 1)).astype(np.float32)  # (3,960,960)
    assert out.shape == (3, MODEL, MODEL), out.shape
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="/tmp/det_calib")
    ap.add_argument("--calib_dir", default="tools/onnx_quantization/calibration_data/detect")
    args = ap.parse_args()

    cands = sorted(
        glob.glob(os.path.join(args.calib_dir, "*"))
    )
    # keep only image extensions PIL can open (skip .ppm? PIL handles ppm too)
    cands = [f for f in cands if os.path.isfile(f)
             and f.lower().endswith((".png", ".jpg", ".jpeg", ".bmp", ".webp", ".ppm"))]
    print(f"{len(cands)} candidate det images in {args.calib_dir}")

    os.makedirs(args.out, exist_ok=True)
    flist = os.path.join(args.out, "filelist_det.txt")
    ok = 0
    with open(flist, "w") as lf:
        for i, f in enumerate(cands):
            try:
                arr = preprocess(f)
            except Exception as e:
                print(f"skip {f}: {e}")
                continue
            npy = os.path.join(args.out, f"calib_{ok:03d}.npy")
            np.save(npy, arr)
            lf.write(npy + "\n")
            ok += 1
    print(f"det: {ok} npys -> {args.out}, list {flist}")
    print("ncnn2table shape=[960,960,3] type=1 (npy is (3,960,960) C,H,W)")


if __name__ == "__main__":
    main()
