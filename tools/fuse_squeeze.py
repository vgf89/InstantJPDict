#!/usr/bin/env python3
"""Delete squeeze_119 by retargeting transpose_123 (Vulkan leg, refs #37).

squeeze_119 (axes=[1]) drops a size-1 dim between add_21 and
transpose_123. transpose_123 order 1 on the squeezed dims-2 (W,C)
equals order 5 on the raw dims-3 (W,1,C): both feed gemm_8 the same
(W/C layout) values. Verified bit-identical (100%/0.000) vs the
squeeze graph at even and odd widths on CPU.

Besides removing a layer everywhere, this deletes the only Squeeze in
the graph, so no Squeeze_vulkan port is needed at all.

Usage: fuse_squeeze.py --param rec_dyn.param --out-param ...
The .bin is untouched (no weights move); blob ids shift down by one
past the deleted layer (gemm_8 extract id 191 -> 190).
"""
import argparse
import sys
from pathlib import Path


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--param', required=True)
    ap.add_argument('--out-param', required=True)
    args = ap.parse_args()

    lines = Path(args.param).read_text().splitlines()
    assert lines[0].strip() == '7767517'
    hdr = lines[1]
    rows = lines[2:]

    sq = [i for i, r in enumerate(rows) if r.split()[0] == 'Squeeze']
    if len(sq) != 1:
        sys.exit(f'refusing: expected 1 Squeeze, found {len(sq)}')
    si = sq[0]
    sp = rows[si].split()
    assert sp[1] == 'squeeze_119', sp
    gin, gout = sp[4], sp[5]

    tp = [r for r in rows if r.split()[1] == 'transpose_123']
    assert len(tp) == 1
    t = tp[0].split()
    assert t[4] == gout, (t, gout)

    new_rows = []
    for i, r in enumerate(rows):
        if i == si:
            continue
        p = r.split()
        if p[1] == 'transpose_123':
            r = f'Permute {p[1]} {p[2]} {p[3]} {gin} {p[5]} 0=5'
        new_rows.append(r)

    gone = int(gout)

    def rn(b):
        try:
            v = int(b)
        except ValueError:
            return b
        return str(v - 1) if v > gone else b

    final = []
    for r in new_rows:
        p = r.split()
        ni, no = int(p[2]), int(p[3])
        blobs = [rn(b) for b in p[4:4 + ni + no]]
        rest = ' '.join(p[4 + ni + no:])
        final.append(' '.join(p[:4] + blobs + ([rest] if rest else [])))

    nl, nb = map(int, hdr.split())
    Path(args.out_param).write_text(
        '7767517\n' f'{nl - 1} {nb - 1}\n' + '\n'.join(final) + '\n')
    print(f'removed squeeze_119, blob {gone} renumbered: {hdr} -> {nl - 1} {nb - 1}')


if __name__ == '__main__':
    main()
