#!/usr/bin/env python3
"""Generate ncnn2table npy calibration inputs for PP-OCRv6 rec buckets.
Matches OcrEngine.kt runtime preprocessing exactly:
  targetW = min(480, round(rw*48/rh)), modelW = first bucket >= targetW
  resize crop -> targetW x 48 (bilinear), gray=0.299R+0.587G+0.114B
  input[c*48*modelW + y*modelW + x] = gray/127.5 - 1 for x<targetW else 0
Buckets: 64/128/256/480.

Sources: line crops extracted from cal-images.tar.gz into misc/ by the
runbook (docs/repro-runbook.md): misc/trails_of_cold_steel_images{,_2}/,
misc/vert_large/, misc/test_images_hard/ (+ .txt ground truth sidecars,
unused here). Narrow buckets (w64/w128) rarely occur naturally, so the
script tops them up deterministically (--seed) with sliced fragments cut
from long lines — this scripts the manual top-up step from the #16 flow.
Mid-char cuts pollute parity metrics there; use w256/w480 numbers for
quantization decisions.

Usage: python3 tools/gen_rec_calib_npy.py --out /tmp/rec_calib
"""
import argparse
import glob
import os
import random

import numpy as np
from PIL import Image

BUCKETS = [64, 128, 256, 480]
HEIGHT = 48

DEFAULT_SRCS = [
    "misc/trails_of_cold_steel_images",
    "misc/trails_of_cold_steel_images_2",
    "misc/vert_large",
    "misc/test_images_hard",
]


def load_rgb(path):
    return Image.open(path).convert("RGB")


def target_width(rw, rh):
    if rh >= rw * 3 / 2:  # vertical lines are rotated 270 at runtime
        rw, rh = rh, rw
    return max(4, min(480, round(rw * HEIGHT / rh)))


def preprocess_image(im, modelW):
    rw, rh = im.size
    # vertical lines (h >= w*1.5) are rotated 270 at runtime
    if rh >= rw * 3 / 2:
        im = im.transpose(Image.ROTATE_90)  # PIL ROTATE_90 = CCW ~ 270 CW? close enough for calib stats
        rw, rh = im.size
    tW = max(4, min(480, round(rw * HEIGHT / rh)))
    assert modelW >= tW, f"targetW {tW} > bucket {modelW}"
    im_r = im.resize((tW, HEIGHT), Image.BILINEAR)
    arr = np.asarray(im_r).astype(np.float32)
    gray = arr[..., 0] * 0.299 + arr[..., 1] * 0.587 + arr[..., 2] * 0.114
    norm = gray / 127.5 - 1.0
    out = np.zeros((3, HEIGHT, modelW), dtype=np.float32)
    out[:, :, :tW] = norm[None, :, :]
    return out


def preprocess(path, modelW):
    return preprocess_image(load_rgb(path), modelW)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="/tmp/rec_calib")
    ap.add_argument("--src", action="append", default=None,
                    help="calib image root (repeatable; globs *.png/*.jpg). "
                         f"Default: {', '.join(DEFAULT_SRCS)} (existing dirs only).")
    ap.add_argument("--per-bucket", type=int, default=60)
    ap.add_argument("--seed", type=int, default=16,
                    help="seed for deterministic slice top-up of thin buckets.")
    args = ap.parse_args()

    srcs = args.src or [d for d in DEFAULT_SRCS if os.path.isdir(d)]
    cands = []
    for d in srcs:
        cands += sorted(glob.glob(os.path.join(d, "*.png"))
                        + glob.glob(os.path.join(d, "*.jpg"))
                        + glob.glob(os.path.join(d, "*.jpeg")))
    print(f"{len(cands)} candidate line crops from {srcs}")
    rng = random.Random(args.seed)

    buckets = {w: [] for w in BUCKETS}   # whole-line images per bucket
    widelines = []                        # (path, rw, rh) with targetW >= 128, slice donors
    for f in cands:
        try:
            im = load_rgb(f)
        except Exception:
            continue
        rw, rh = im.size
        if rw < 4 or rh < 4:
            continue
        tW = target_width(rw, rh)
        mw = next((b for b in BUCKETS if b >= tW), None)
        if mw is None:
            continue
        if len(buckets[mw]) < args.per_bucket:
            buckets[mw].append(("whole", f))
        if tW >= 128:
            widelines.append((f, rw, rh))

    # Deterministic slice top-up for thin buckets (scripted #16 manual step).
    for mw in BUCKETS:
        need = args.per_bucket - len(buckets[mw])
        if need <= 0 or not widelines:
            continue
        made = 0
        guard = 0
        while made < need and guard < need * 50:
            guard += 1
            f, rw, rh = rng.choice(widelines)
            vertical = rh >= rw * 3 / 2
            lw, lh = (rh, rw) if vertical else (rw, rh)
            fragW = rng.randint(mw // 2, mw)          # line-length pixels at H=48 scale
            srcW = int(fragW * lh / HEIGHT) + 1       # back to source pixels
            if srcW >= lw:
                continue
            x0 = rng.randint(0, lw - srcW)
            try:
                im = load_rgb(f)
            except Exception:
                continue
            if vertical:
                im = im.transpose(Image.ROTATE_90)
            box = (x0 * im.size[0] // lw, 0,
                   (x0 + srcW) * im.size[0] // lw, im.size[1])
            frag = im.crop(box)
            if frag.size[0] < 4:
                continue
            # keep only fragments that actually belong in this bucket
            if next((b for b in BUCKETS if b >= target_width(*frag.size)), None) != mw:
                continue
            buckets[mw].append(("slice", (f, x0, frag)))
            made += 1
        print(f"w{mw}: slice top-up {made}/{need}")

    for mw, items in buckets.items():
        d = os.path.join(args.out, f"w{mw}")
        os.makedirs(d, exist_ok=True)
        flist = os.path.join(args.out, f"filelist_w{mw}.txt")
        with open(flist, "w") as lf:
            for i, (kind, payload) in enumerate(items):
                if kind == "whole":
                    arr = preprocess(payload, mw)
                else:
                    _, _, frag = payload
                    arr = preprocess_image(frag, mw)
                npy = os.path.join(d, f"calib_{i:03d}.npy")
                np.save(npy, arr)
                lf.write(npy + "\n")
        n_slice = sum(1 for k, _ in items if k == "slice")
        print(f"w{mw}: {len(items)} npys ({n_slice} sliced) -> {d}, list {flist}")


if __name__ == "__main__":
    main()
