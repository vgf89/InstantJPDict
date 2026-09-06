#!/usr/bin/env python3
"""GELU experiments for recDyn (#27).

Two param-only graph edits on rec_dyn.param (or rec_w480.param):

  --mode fast : set 0=1 (tanh approx) on the 13 GELU layers, keep the graph.
                Zero code change; tests the issue's (incorrect) claim that
                "the GELU layer already runs tanh, not erf". It does not:
                bare `GELU` lines default to fast_gelu=0 (erf). erf-vs-tanh
                differ by <=4.8e-4 (numpy, full range), so this variant is
                expected to pass CER parity while cutting erfcf cost.

  --mode fuse : fold each Conv(1x1,int8,8=2) -> GELU -> Conv pattern into the
                producer by setting 9=<activation> on the producer, deleting
                the GELU layer, rewiring the consumer to the producer output
                blob, and renumbering all downstream blob ids. Requires a
                vendored-tree fused-GELU type first (activation_ss ends at 6
                today); until then the output param will NOT load (unknown
                activation). Default --activation 7.

Blob renumbering: each fusion removes exactly one blob (the GELU output).
Old blob ids map to new sequential ids; GELU outputs alias their inputs.
Header `layers blobs` is rewritten. The script prints the new id of the
original `gemm_8` output blob (ncnn_jni.cpp extracts it by id) so the JNI
can be updated when a fused model ships.

Usage:
  python3 tools/fuse_gelu_rec.py --mode fast --param app/src/main/assets/PP-OCRv6_small_ncnn/rec_dyn.param --out /tmp/rec_dyn_fastgelu.param
  python3 tools/fuse_gelu_rec.py --mode fuse --param app/src/main/assets/PP-OCRv6_small_ncnn/rec_dyn.param --out /tmp/rec_dyn_fused.param [--activation 7]
"""
import argparse
import re
import sys
from pathlib import Path

TOKEN_RE = re.compile(r"^(-?\d+)=(.*)$")


def parse_layer(line):
    parts = line.split()
    if len(parts) < 4:
        raise ValueError(f"bad layer line: {line!r}")
    typ, name = parts[0], parts[1]
    n_in, n_out = int(parts[2]), int(parts[3])
    # Blob ids are usually ints, but model I/O use names (in0/out0).
    blobs = parts[4:4 + n_in + n_out]
    if len(blobs) != n_in + n_out:
        raise ValueError(f"bad blob count: {line!r}")
    params = {}
    order = []
    for tok in parts[4 + n_in + n_out:]:
        m = TOKEN_RE.match(tok)
        if not m:
            raise ValueError(f"bad param token {tok!r} in {line!r}")
        k = int(m.group(1))
        params[k] = m.group(2)
        order.append(k)
    return typ, name, n_in, n_out, blobs, params, order


def fmt_layer(typ, name, n_in, n_out, blobs, params, order):
    toks = [typ, name, str(n_in), str(n_out)] + [str(b) for b in blobs]
    seen = set()
    for k in order:
        if k in params and k not in seen:
            toks.append(f"{k}={params[k]}")
            seen.add(k)
    for k in sorted(params):
        if k not in seen:
            toks.append(f"{k}={params[k]}")
    return " ".join(toks)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["fast", "fuse"], required=True)
    ap.add_argument("--param", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--activation", type=int, default=7,
                    help="fused activation id to set as 9= in fuse mode")
    args = ap.parse_args()

    lines = Path(args.param).read_text().splitlines()
    if not lines or lines[0].strip() != "7767517":
        sys.exit(f"refusing: {args.param} is not an ncnn param file")
    n_layers, n_blobs = map(int, lines[1].split())
    raw = lines[2:]
    if len(raw) != n_layers:
        sys.exit(f"refusing: header says {n_layers} layers but found {len(raw)}")

    parsed = [parse_layer(l) for l in raw]
    gelu_at = [i for i, p in enumerate(parsed) if p[0] == "GELU"]
    print(f"layers={n_layers} blobs={n_blobs} gelu={len(gelu_at)}")
    if len(gelu_at) != 13:
        sys.exit(f"refusing: expected 13 GELU layers, found {len(gelu_at)}")

    if args.mode == "fast":
        out = []
        for i, p in enumerate(parsed):
            typ, name, n_in, n_out, blobs, params, order = p
            if typ == "GELU":
                if n_in != 1 or n_out != 1:
                    sys.exit(f"refusing: {name} is not 1->1")
                if 0 in params and params[0] != "0":
                    sys.exit(f"refusing: {name} already has 0={params[0]}")
                params = dict(params)
                if 0 not in params:
                    order = order + [0]
                params[0] = "1"
                # preserve the original column alignment: append to raw line
                out.append(raw[i] + " 0=1")
            else:
                out.append(raw[i])
        Path(args.out).write_text("7767517\n" f"{n_layers} {n_blobs}\n" + "\n".join(out) + "\n")
        print(f"wrote {args.out}: {len(gelu_at)} GELU set to 0=1, graph unchanged")
        return

    # ---- fuse mode ----
    skip = set(gelu_at)
    producer_of = {}  # gelu layer idx -> producer layer idx
    consumer_of = {}  # gelu layer idx -> consumer layer idx
    for gi in gelu_at:
        typ, name, n_in, n_out, blobs, params, order = parsed[gi]
        if n_in != 1 or n_out != 1:
            sys.exit(f"refusing: {name} is not 1->1")
        if params:
            sys.exit(f"refusing: {name} carries params {params}, expected bare GELU")
        gin, gout = blobs
        prod = cons = None
        for j, q in enumerate(parsed):
            if j in skip:
                continue
            _, _, qi, qo, qblobs, _, _ = q
            outs = qblobs[qi:]
            ins = qblobs[:qi]
            if gout in ins and cons is None:
                cons = j
            if gin in outs:
                prod = j
        if prod is None or cons is None:
            sys.exit(f"refusing: {name} in={gin} out={gout} prod={prod} cons={cons}")
        ptyp, pname, _, _, pblobs, pparams, _ = parsed[prod]
        if ptyp != "Convolution":
            sys.exit(f"refusing: {name} producer {pname} is {ptyp}, expected Convolution")
        if 9 in pparams:
            sys.exit(f"refusing: producer {pname} already fused (9={pparams[9]})")
        if pparams.get(8) != "2":
            print(f"WARN: producer {pname} 8={pparams.get(8)} (expected 2=int8 fp32-out)",
                  file=sys.stderr)
        producer_of[gi] = prod
        consumer_of[gi] = cons

    # consumers must take the GELU output as input (single use)
    use_count = {}
    for j, q in enumerate(parsed):
        if j in skip:
            continue
        _, _, qi, _, qblobs, _, _ = q
        for b in qblobs[:qi]:
            use_count[b] = use_count.get(b, 0) + 1
    for gi in gelu_at:
        gout = parsed[gi][4][1]
        if use_count.get(gout, 0) != 1:
            sys.exit(f"refusing: gelu output blob {gout} used "
                     f"{use_count.get(gout, 0)}x, expected exactly 1")

    # old blob -> new blob, in layer order; GELU outputs alias their inputs
    alias = {}
    for gi in gelu_at:
        gin, gout = parsed[gi][4]
        alias[gout] = gin
    new_id = {}
    named = set()
    nxt = [1]  # numeric ids stay 1-based (in0 is the conceptual 0)

    def remap(b):
        while b in alias:
            b = alias[b]
        try:
            int(b)
        except ValueError:
            named.add(b)  # in0/out0: pin, never renumber
            return b
        if b not in new_id:
            new_id[b] = nxt[0]
            nxt[0] += 1
        return new_id[b]

    # every blob id below the declared count must be defined somewhere
    defined = set()
    for j, q in enumerate(parsed):
        if j in skip:
            continue
        _, _, qi, _, qblobs, _, _ = q
        for b in qblobs[qi:]:
            defined.add(b)
    # input blob(s) with no producer (e.g. in0) still get ids via first use

    out_layers = []
    for j, q in enumerate(parsed):
        if j in skip:
            continue
        typ, name, n_in, n_out, blobs, params, order = q
        ins = [remap(b) for b in blobs[:n_in]]
        outs = [remap(b) for b in blobs[n_in:]]
        params = dict(params)
        order = list(order)
        if j in producer_of.values():
            if 9 not in params:
                order = order + [9]
            params[9] = str(args.activation)
        out_layers.append(fmt_layer(typ, name, n_in, n_out, ins + outs, params, order))

    # report new id of the old gemm_8 output (JNI extracts it by id)
    old_gemm_out = None
    for q in parsed:
        if q[0] == "Gemm" and q[1] == "gemm_8":
            old_gemm_out = q[4][q[2]]
    new_blobs = (nxt[0] - 1) + len(named)
    print(f"removed {len(skip)} GELU layers, {n_blobs} -> {new_blobs} blobs")
    if old_gemm_out is not None:
        print(f"gemm_8 out: old={old_gemm_out} new={remap(old_gemm_out)} "
              f"(update ncnn_jni.cpp extract id when this ships)")
    print(f"producers given 9={args.activation}: "
          + ",".join(parsed[j][1] for j in sorted(set(producer_of.values()))))

    Path(args.out).write_text(
        "7767517\n" f"{len(out_layers)} {new_blobs}\n" + "\n".join(out_layers) + "\n")
    print(f"wrote {args.out}")


if __name__ == "__main__":
    main()
