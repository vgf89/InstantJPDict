#!/usr/bin/env python3
"""Generate ncnn2table npy calibration inputs for PP-OCRv6 rec buckets.
Matches OcrEngine.kt runtime preprocessing exactly:
  targetW = min(480, round(rw*48/rh)), modelW = first bucket >= targetW
  resize crop -> targetW x 48 (bilinear), gray=0.299R+0.587G+0.114B
  input[c*48*modelW + y*modelW + x] = gray/127.5 - 1 for x<targetW else 0
Buckets: 64/128/256/480. Sources: misc/trails* (horiz) + misc/vert_large (rotated).
Usage: python3 tools/gen_rec_calib_npy.py --out /tmp/rec_calib
"""
import argparse, glob, os
import numpy as np
from PIL import Image

BUCKETS = [64, 128, 256, 480]

def preprocess(path, modelW):
    im = Image.open(path).convert("RGB")
    rw, rh = im.size
    # vertical lines (h >= w*1.5) are rotated 270 at runtime
    if rh >= rw * 3 / 2:
        im = im.transpose(Image.ROTATE_90)  # PIL ROTATE_90 = CCW ~ 270 CW? close enough for calib stats
        rw, rh = im.size
    targetW = max(4, min(480, round(rw * 48 / rh)))
    assert modelW >= targetW, f"{path}: targetW {targetW} > bucket {modelW}"
    im_r = im.resize((targetW, 48), Image.BILINEAR)
    arr = np.asarray(im_r).astype(np.float32)
    gray = arr[..., 0] * 0.299 + arr[..., 1] * 0.587 + arr[..., 2] * 0.114
    norm = gray / 127.5 - 1.0
    out = np.zeros((3, 48, modelW), dtype=np.float32)
    out[:, :, :targetW] = norm[None, :, :]
    return out

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="/tmp/rec_calib")
    ap.add_argument("--per-bucket", type=int, default=60)
    args = ap.parse_args()

    cands = sorted(glob.glob("misc/trails_of_cold_steel_images/*.png")
                   + glob.glob("misc/trails_of_cold_steel_images_2/*.png")
                   + glob.glob("misc/vert_large/*.png"))
    print(f"{len(cands)} candidate line crops")
    buckets = {w: [] for w in BUCKETS}
    for f in cands:
        try:
            im = Image.open(f).convert("RGB")
        except Exception:
            continue
        rw, rh = im.size
        if rh >= rw * 3 / 2:
            rw, rh = rh, rw  # post-rotation dims
        if rw < 4 or rh < 4:
            continue
        targetW = max(4, min(480, round(rw * 48 / rh)))
        mw = next((b for b in BUCKETS if b >= targetW), None)
        if mw is None:
            continue
        if len(buckets[mw]) < args.per_bucket:
            buckets[mw].append(f)

    for mw, files in buckets.items():
        d = os.path.join(args.out, f"w{mw}")
        os.makedirs(d, exist_ok=True)
        flist = os.path.join(args.out, f"filelist_w{mw}.txt")
        with open(flist, "w") as lf:
            for i, f in enumerate(files):
                arr = preprocess(f, mw)
                npy = os.path.join(d, f"calib_{i:03d}.npy")
                np.save(npy, arr)
                lf.write(npy + "\n")
        print(f"w{mw}: {len(files)} npys -> {d}, list {flist}")

if __name__ == "__main__":
    main()
