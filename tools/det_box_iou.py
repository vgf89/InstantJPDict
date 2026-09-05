#!/usr/bin/env python3
"""Box-level IoU parity: fp16 det vs int8 det prob maps (960x960).
Mirrors OcrEngine.kt detect() steps 6-11 in 960 output space
(thresh=0.3, unclip=1.5, xOverlap=0.4), geometry identical for both
sides so 960-space comparison is fair.
Usage: python3 tools/det_box_iou.py --dir /tmp/det_prob --n 26
"""
import argparse
import glob
import os
from collections import deque

import numpy as np

THRESH = 0.3
UNCLIP = 1.50
X_OVERLAP = 0.40
SIZE = 960


def components(prob, thresh=THRESH):
    mask = prob > thresh
    visited = np.zeros_like(mask, dtype=bool)
    boxes = []
    H, W = mask.shape
    for y in range(H):
        for x in range(W):
            if visited[y, x] or not mask[y, x]:
                continue
            q = deque([(x, y)])
            visited[y, x] = True
            minx = maxx = x
            miny = maxy = y
            cnt = 0
            while q:
                cx, cy = q.popleft()
                cnt += 1
                minx = min(minx, cx); maxx = max(maxx, cx)
                miny = min(miny, cy); maxy = max(maxy, cy)
                for dy in (-1, 0, 1):
                    for dx in (-1, 0, 1):
                        if dx == 0 and dy == 0:
                            continue
                        nx, ny = cx + dx, cy + dy
                        if 0 <= nx < W and 0 <= ny < H and not visited[ny, nx] and mask[ny, nx]:
                            visited[ny, nx] = True
                            q.append((nx, ny))
            if cnt < 3:
                continue
            # unclip in 960-space
            bw = float(maxx + 1 - minx); bh = float(maxy + 1 - miny)
            area = bw * bh
            per = 2 * (bw + bh)
            exp = area * UNCLIP / per if per > 0 else 0.0
            ux = max(0, round(minx - exp)); uy = max(0, round(miny - exp))
            ux2 = min(SIZE, round(maxx + 1 + exp)); uy2 = min(SIZE, round(maxy + 1 + exp))
            if ux2 - ux < 4 or uy2 - uy < 4:
                continue
            boxes.append((ux, uy, ux2, uy2))
    return boxes


def should_merge(a, b):
    ix, iy = max(a[0], b[0]), max(a[1], b[1])
    ix2, iy2 = min(a[2], b[2]), min(a[3], b[3])
    if ix >= ix2 or iy >= iy2:
        return False
    inter = float((ix2 - ix) * (iy2 - iy))
    mina = min((a[2] - a[0]) * (a[3] - a[1]), (b[2] - b[0]) * (b[3] - b[1]))
    if mina <= 0:
        return False
    if inter / mina < X_OVERLAP:
        return False
    ydiff = abs((a[1] + a[3]) / 2 - (b[1] + b[3]) / 2)
    avgh = ((a[3] - a[1]) + (b[3] - b[1])) / 2
    return ydiff <= avgh


def merge_boxes(boxes):
    if len(boxes) < 2:
        return list(boxes)
    order = sorted(range(len(boxes)), key=lambda i: -((boxes[i][2] - boxes[i][0]) * (boxes[i][3] - boxes[i][1])))
    handled = [False] * len(boxes)
    out = []
    for ii, i in enumerate(order):
        if handled[i]:
            continue
        cur = boxes[i]
        handled[i] = True
        for j in order[ii + 1:]:
            if handled[j]:
                continue
            if should_merge(cur, boxes[j]):
                b = boxes[j]
                cur = (min(cur[0], b[0]), min(cur[1], b[1]), max(cur[2], b[2]), max(cur[3], b[3]))
                handled[j] = True
        out.append(cur)
    return out


def postprocess(prob, thresh=THRESH):
    boxes = components(prob, thresh)
    boxes = merge_boxes(boxes)
    boxes = [b for b in boxes if b[2] - b[0] >= 10 and b[3] - b[1] >= 10]
    shrunk = []
    for (l, t, r, b) in boxes:
        if (b - t) > (r - l):
            s = round((r - l) * 0.05)
            shrunk.append((l + s, t, r - s, b))
        else:
            shrunk.append((l, t, r, b))
    # split overlapping horizontals at overlap midpoint
    horiz_idx = [i for i, bb in enumerate(shrunk) if (bb[2] - bb[0]) >= (bb[3] - bb[1])]
    out = list(shrunk)
    for ii in range(len(horiz_idx)):
        for jj in range(ii + 1, len(horiz_idx)):
            ai, bi = horiz_idx[ii], horiz_idx[jj]
            a, b = out[ai], out[bi]
            if min(a[2], b[2]) - max(a[0], b[0]) <= 0:
                continue
            upper, lower = (ai, bi) if a[1] <= b[1] else (bi, ai)
            if out[upper][3] > out[lower][1]:
                mid = (out[lower][1] + min(out[upper][3], out[lower][3])) // 2
                u = out[upper]; lo = out[lower]
                out[upper] = (u[0], u[1], u[2], max(mid, u[1] + 1))
                out[lower] = (lo[0], out[upper][3], lo[2], lo[3])
    return out


def iou(a, b):
    ix = max(0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    aa = (a[2] - a[0]) * (a[3] - a[1])
    bb = (b[2] - b[0]) * (b[3] - b[1])
    u = aa + bb - inter
    return inter / u if u > 0 else 0.0


def match_iou(ba, bb):
    # greedy best-match; returns list of matched IoUs + counts
    rem = list(bb)
    ious = []
    for a in sorted(ba, key=lambda b: -((b[2] - b[0]) * (b[3] - b[1]))):
        best, bi = -1.0, -1
        for j, b in enumerate(rem):
            v = iou(a, b)
            if v > best:
                best, bi = v, j
        if bi >= 0:
            ious.append(best)
            rem.pop(bi)
    return ious


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default="/tmp/det_prob")
    ap.add_argument("--n", type=int, default=26)
    ap.add_argument("--thresh-a", type=float, default=THRESH)
    ap.add_argument("--thresh-b", type=float, default=None)
    args = ap.parse_args()
    all_ious = []
    n_a = n_b = 0
    tb = args.thresh_b if args.thresh_b is not None else args.thresh_a
    for i in range(args.n):
        pa = os.path.join(args.dir, f"prob_a_{i:03d}.npy")
        pb = os.path.join(args.dir, f"prob_b_{i:03d}.npy")
        if not (os.path.exists(pa) and os.path.exists(pb)):
            print(f"missing pair {i}, stop")
            break
        ba = postprocess(np.load(pa), args.thresh_a)
        bb = postprocess(np.load(pb), tb)
        n_a += len(ba); n_b += len(bb)
        ious = match_iou(ba, bb)
        all_ious.extend(ious)
        mi = sum(ious) / len(ious) if ious else 1.0 if not ba and not bb else 0.0
        print(f"img {i:03d}: fp16={len(ba)} int8={len(bb)} matched={len(ious)} meanIoU={mi:.4f} "
              + (" ".join(f"{v:.2f}" for v in ious[:12]) if ious else "-"))
    if all_ious:
        arr = np.array(all_ious)
        print(f"TOTAL matched={len(all_ious)} fp16Boxes={n_a} int8Boxes={n_b} "
              f"meanIoU={arr.mean():.4f} p50={np.median(arr):.4f} "
              f"frac>=0.5={(arr >= 0.5).mean():.3f} frac>=0.95={(arr >= 0.95).mean():.3f}")
    else:
        print("no matched pairs")


if __name__ == "__main__":
    main()
