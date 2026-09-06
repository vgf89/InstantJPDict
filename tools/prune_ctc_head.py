#!/usr/bin/env python3
"""Prune CTC-head output classes from rec_dyn (#36 prototype, refs #31).

Keeps the classes listed in --keep (one orig class id per line), slices
gemm_8's B rows + bias, rewrites 8=N, writes remap (new_id -> orig_id).

Shipped keep list tools/ctchead_keep_enjp.txt (13,193 incl. blank+space):
EN+JP only — cut = CJK without Unihan kJapaneseKun/On (4,870) + accented
Latin (561) + Greek/Coptic/Regional (86). Regenerate with Unihan.zip
(kJapanese readings) + vocab.json per #36 research if the vocab changes.
Validated by byte-identical keep-all round-trip; prototype parity
98.3%/CER 0.001 w480, x86 e2e -2.9ms (-7.1%). See #36 resolution.

Bin walk implements exact ncnn ModelBin consumption per layer load_model:
  Convolution: tagged(6) [+rawfp32(out) if 5=] [+rawfp32(out)+rawfp32(1) if 8=]
               [+rawfp32(1) if 8>100]
  ConvDW: tagged(6) [+scales per %100 rule] [+rawfp32(1) if >100]
  LayerNorm: rawfp32(affine)+rawfp32(affine)
  Gemm 18=2: tagged(K*N) + tagged-C per 10= + rawfp32(1) B_scale
  others: none.
Tagged payload: u32 magic + body (fp32 raw 0x0002C056 / int8 0x000D4B38
padded to 4B / fp16 0x01306B47 / quant table otherwise).
"""
import argparse
import struct
import sys
from pathlib import Path

FP32 = 0x0002C056
INT8 = 0x000D4B38


def align4(n):
    return (n + 3) & ~3


class Walker:
    def __init__(self, data):
        self.d = data
        self.o = 0

    def raw(self, n):
        b = self.d[self.o:self.o + n]
        if len(b) != n:
            raise ValueError("bin overrun")
        self.o += n
        return b

    def rawfp32(self, n):
        return self.raw(4 * n)

    def tagged(self, n):
        off = self.o
        tag = struct.unpack('<I', self.raw(4))[0]
        if tag == FP32:
            body = self.raw(4 * n)
        elif tag == INT8:
            body = self.raw(align4(n))
        elif tag in (0x01306B47, 0x01348B83):
            body = self.raw(align4(2 * n))
        else:
            # quant table: 256 floats + indices
            body = self.raw(1024 + align4(n))
        return self.d[off:self.o]  # verbatim tag+body for passthrough

    def emit_tagged(self, kind, payload):
        if kind == 'fp32':
            return struct.pack('<I', FP32) + payload
        if kind == 'int8':
            return struct.pack('<I', INT8) + payload
        raise ValueError("emit only fp32/int8, got " + kind)


def parse_params(path):
    lines = Path(path).read_text().splitlines()
    assert lines[0].strip() == '7767517'
    out = []
    for ln in lines[2:]:
        p = ln.split()
        typ, name, ni, no = p[0], p[1], int(p[2]), int(p[3])
        blobs = p[4:4 + ni + no]
        prm = {}
        for tok in p[4 + ni + no:]:
            k, v = tok.split('=', 1)
            prm[int(k)] = v
        out.append([typ, name, ni, no, blobs, prm, ln])
    return lines[1], out


def consume(w, typ, prm):
    """Return list of ('tagged', n) / ('raw', n) reads in load_model order."""
    seq = []
    if typ == 'Convolution':
        seq.append(('tagged', int(prm[6])))
        if int(prm.get(5, 0)):
            out = None
            seq.append(('raw', ('out', prm)))
        t8 = int(prm.get(8, 0))
        if t8:
            seq.append(('raw', ('out', prm)))
            seq.append(('raw', 1))
        if t8 > 100:
            seq.append(('raw', 1))
    elif typ == 'ConvolutionDepthWise':
        seq.append(('tagged', int(prm[6])))
        if int(prm.get(5, 0)):
            seq.append(('raw', ('out', prm)))
        t8 = int(prm.get(8, 0))
        grp = int(prm.get(7, 1))
        if t8 % 100 == 1:
            seq.append(('raw', grp))
            seq.append(('raw', 1))
        elif t8 % 100 == 2:
            seq.append(('raw', 1))
            seq.append(('raw', 1))
        if t8 > 100:
            seq.append(('raw', 1))
    elif typ == 'LayerNorm':
        a = int(prm.get(2, 1)) and int(prm.get(0, 0))
        # affine_size: pd 0 is normalized dim when affine; our layers 0=120
        seq.append(('raw', a))
        seq.append(('raw', a))
    elif typ == 'Gemm':
        if int(prm.get(4, 0)):
            ka = int(prm[9]) if int(prm.get(2, 0)) == 0 else int(prm[7])
            ma = int(prm[7]) if int(prm.get(2, 0)) == 0 else int(prm[9])
            seq.append(('tagged', ka * ma))
        if int(prm.get(5, 0)):
            n, k = int(prm[8]), int(prm[9])
            seq.append(('tagged', k * n))
        if int(prm.get(6, 0)):
            b = int(prm.get(10, 0))
            n, m = int(prm[8]), int(prm.get(7, 0))
            cnt = {0: 1, 1: m, 2: m, 3: n * m, 4: n}.get(b)
            if cnt is None:
                raise ValueError("bias broadcast " + str(b))
            seq.append(('tagged', cnt))
        if int(prm.get(18, 0)):
            if int(prm.get(4, 0)):
                seq.append(('raw', int(prm[7])))
            if int(prm.get(5, 0)):
                seq.append(('raw', 1))
    return seq


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--param', required=True)
    ap.add_argument('--bin', required=True)
    ap.add_argument('--keep', required=True, help='kept orig class ids, one per line')
    ap.add_argument('--out-param', required=True)
    ap.add_argument('--out-bin', required=True)
    ap.add_argument('--remap', required=True, help='new_id -> orig_id per line')
    args = ap.parse_args()

    keep = [int(x) for x in Path(args.keep).read_text().split()]
    assert keep == sorted(keep) and len(set(keep)) == len(keep)
    assert all(0 <= c < 18710 for c in keep)
    hdr, layers = parse_params(args.param)
    data = Path(args.bin).read_bytes()
    w = Walker(data)
    out = bytearray()
    pruned = False

    for typ, name, ni, no, blobs, prm, ln in layers:
        for kind, n in consume(w, typ, prm):
            if isinstance(n, tuple):  # ('out', prm) -> num_output
                n = int(prm[0])
            if name == 'gemm_8' and kind == 'tagged':
                verb = w.tagged(n)
                tag = struct.unpack('<I', verb[:4])[0]
                payload = verb[4:]
                if n == 18710 * 120 and tag == INT8:
                    rowlen = 120
                    rows = [payload[r * rowlen:(r + 1) * rowlen] for r in keep]
                    body = b''.join(rows)
                    out += w.emit_tagged('int8', body + b'\0' * (align4(len(body)) - len(body)))
                elif n == 18710 and tag == FP32:
                    vals = struct.unpack('<%df' % n, payload)
                    body = struct.pack('<%df' % len(keep), *[vals[r] for r in keep])
                    out += w.emit_tagged('fp32', body)
                elif n == 18710 and tag in (0x01306B47, 0x01348B83):
                    import numpy as np
                    dt = np.float16 if tag == 0x01306B47 else None
                    vals = np.frombuffer(payload[:2 * n], dtype=np.float16)
                    body = vals[keep].tobytes()
                    out += struct.pack('<I', tag) + body + b'\0' * (align4(len(body)) - len(body))
                else:
                    raise ValueError(f'unexpected gemm_8 payload tag={tag:#x} n={n}')
            else:
                if kind == 'tagged':
                    out += w.tagged(n)  # verbatim passthrough
                else:
                    out += w.rawfp32(n)

    if w.o != len(data):
        sys.exit(f'bin walk ended at {w.o}, file is {len(data)} — layout mismatch')

    # rewrite param 8= on gemm_8
    nlines = []
    for typ, name, ni, no, blobs, prm, ln in layers:
        if name == 'gemm_8':
            assert prm[8] == '18710'
            ln = ln.replace('8=18710', f'8={len(keep)}')
            pruned = True
        nlines.append(ln)
    assert pruned
    Path(args.out_param).write_text('7767517\n' + hdr + '\n' + '\n'.join(nlines) + '\n')
    Path(args.out_bin).write_bytes(bytes(out))
    Path(args.remap).write_text('\n'.join(map(str, keep)) + '\n')
    print(f'kept {len(keep)}/18710, bin {len(data)} -> {len(out)} bytes')


if __name__ == '__main__':
    main()
