#!/usr/bin/env python3
"""Swap the 10 SE-branch 1x1 int8 convs for InnerProduct (#37 Vulkan leg).

Vulkan-int8 hard-errors on dims==1 conv input; InnerProduct is its native
equivalent (exact LOGE suggestion). Weight bytes are layout-compatible
([out][in] row-major both); the 5 convrelu (8=102) top-scales have no
InnerProduct counterpart and are dropped from the .bin.

Usage: swap_se_innerproduct.py --param rec_dyn.param --bin rec_dyn.bin
       --out-param ... --out-bin ...
Validates with a full bin-walk (ends exactly at EOF).
"""
import argparse
import struct
from pathlib import Path

SE_CONVS = {'convrelu_5', 'conv_42', 'convrelu_6', 'conv_52', 'convrelu_7',
            'conv_58', 'convrelu_8', 'conv_64', 'convrelu_9', 'conv_72'}


def align4(n):
    return (n + 3) & ~3


class Walker:
    def __init__(self, data):
        self.d = data
        self.o = 0

    def raw(self, n):
        b = self.d[self.o:self.o + n]
        if len(b) != n:
            raise ValueError('bin overrun')
        self.o += n
        return b

    def tagged(self, n):
        off = self.o
        tag = struct.unpack('<I', self.raw(4))[0]
        if tag == 0x0002C056:
            self.raw(4 * n)
        elif tag == 0x000D4B38:
            self.raw(align4(n))
        elif tag in (0x01306B47, 0x01348B83):
            self.raw(align4(2 * n))
        else:
            self.raw(1024 + align4(n))
        return self.d[off:self.o]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--param', required=True)
    ap.add_argument('--bin', required=True)
    ap.add_argument('--out-param', required=True)
    ap.add_argument('--out-bin', required=True)
    args = ap.parse_args()

    lines = Path(args.param).read_text().splitlines()
    assert lines[0].strip() == '7767517'
    hdr = lines[1]
    data = Path(args.bin).read_bytes()
    w = Walker(data)
    out = bytearray()
    out_lines = []
    swapped = []

    for ln in lines[2:]:
        p = ln.split()
        typ, name, ni, no = p[0], p[1], int(p[2]), int(p[3])
        blobs = p[4:4 + ni + no]
        prm = {}
        for tok in p[4 + ni + no:]:
            k, v = tok.split('=', 1)
            prm[int(k)] = v

        if typ == 'Convolution' and name in SE_CONVS:
            assert prm.get(1) == '1' and int(prm[0]) * 1 == int(prm[0]), ln
            nout = int(prm[0])
            # consume exactly like Convolution.load_model
            out_w = bytearray()
            out_w += w.tagged(int(prm[6]))            # weight
            if int(prm.get(5, 0)):
                out_w += w.raw(4 * nout)              # bias
            t8 = int(prm.get(8, 0))
            if t8:
                out_w += w.raw(4 * nout)              # weight scales
                out_w += w.raw(4)                     # bottom scale
            dropped = b''
            if t8 > 100:
                dropped = w.raw(4)                    # top scale: no IP counterpart
            out += out_w
            # rebuild as InnerProduct: 0=num_out 1=bias 2=weights, keep 8/9/10
            toks = ['InnerProduct', name, str(ni), str(no)] + blobs
            toks += [f'0={prm[0]}', f'1={prm.get(5, 0)}', f'2={prm[6]}']
            for k in (8, 9, 10):
                if k in prm:
                    toks.append(f'{k}={prm[k]}')
            out_lines.append(' '.join(toks))
            swapped.append(f'{name} (dropped {len(dropped)}B top scale)')
            continue

        # generic passthrough walk (same layout rules as prune_ctc_head)
        if typ == 'Convolution':
            out += w.tagged(int(prm[6]))
            if int(prm.get(5, 0)):
                out += w.raw(4 * int(prm[0]))
            t8 = int(prm.get(8, 0))
            if t8:
                out += w.raw(4 * int(prm[0])) + w.raw(4)
            if t8 > 100:
                out += w.raw(4)
        elif typ == 'ConvolutionDepthWise':
            out += w.tagged(int(prm[6]))
            if int(prm.get(5, 0)):
                out += w.raw(4 * int(prm[0]))
            t8 = int(prm.get(8, 0))
            grp = int(prm.get(7, 1))
            if t8 % 100 == 1:
                out += w.raw(4 * grp) + w.raw(4)
            elif t8 % 100 == 2:
                out += w.raw(8)
            if t8 > 100:
                out += w.raw(4)
        elif typ == 'LayerNorm':
            a = int(prm[0])
            out += w.raw(4 * a + 4 * a)
        elif typ == 'Gemm':
            if int(prm.get(4, 0)):
                ka = int(prm[9]) if int(prm.get(2, 0)) == 0 else int(prm[7])
                ma = int(prm[7]) if int(prm.get(2, 0)) == 0 else int(prm[9])
                out += w.tagged(ka * ma)
            if int(prm.get(5, 0)):
                out += w.tagged(int(prm[8]) * int(prm[9]))
            if int(prm.get(6, 0)):
                b = int(prm.get(10, 0))
                n, m = int(prm[8]), int(prm.get(7, 0))
                out += w.tagged({0: 1, 1: m, 2: m, 3: n * m, 4: n}[b])
            if int(prm.get(18, 0)):
                if int(prm.get(4, 0)):
                    out += w.raw(4 * int(prm[7]))
                if int(prm.get(5, 0)):
                    out += w.raw(4)
        out_lines.append(ln)

    if w.o != len(data):
        raise SystemExit(f'walk ended at {w.o}, file {len(data)}')
    assert len(swapped) == 10, swapped
    Path(args.out_param).write_text('7767517\n' + hdr + '\n' + '\n'.join(out_lines) + '\n')
    Path(args.out_bin).write_bytes(bytes(out))
    print(f'swapped {len(swapped)}: ' + ', '.join(swapped))
    print(f'bin {len(data)} -> {len(out)} bytes')


if __name__ == '__main__':
    main()
