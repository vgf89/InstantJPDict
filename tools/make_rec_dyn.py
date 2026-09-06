#!/usr/bin/env python3
"""rec_w480 INT8 -> rec_dyn surgery (#23, #29 Track 1).

One dynamic-width model from the static w480 INT8 build: every
sequence-length slot (60 = 480/8 timesteps) becomes runtime-resolved:
  Reshape lines : full-token `=60` -> `=-1`
                  (reshape_103..107 seq slot, flatten_138 0, reshape_108..118)
  Gemm lines    : drop ` 7=60` (7=M resolves from the input blob at runtime)
The .bin is copied verbatim (no weight layout change).

Any `=60` full-token found outside a Reshape/Gemm-7= site fails loudly --
a new slot the rule doesn't know about must be reviewed, not guessed.

Postconditions asserted: no `=60` tokens remain, flatten_138 has `0=-1`,
>=16 Reshape lines edited (the documented 16-line surgery).

Usage: python3 tools/make_rec_dyn.py --param rec_w480_int8.param
       --bin rec_w480_int8.bin --out-dir /tmp/rec_dyn
"""
import argparse
import re
import shutil
import sys

SEQ = 60


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--param", required=True, help="rec_w480 INT8 .param")
    ap.add_argument("--bin", required=True, help="rec_w480 INT8 .bin")
    ap.add_argument("--out-dir", required=True)
    args = ap.parse_args()

    with open(args.param) as f:
        lines = f.read().splitlines()

    n_reshape = 0
    n_gemm = 0
    out = []
    for ln in lines:
        toks = ln.split()
        if not toks:
            out.append(ln)
            continue
        kind = toks[0]
        if kind == "Reshape" and re.search(r"=60(\s|$)", ln):
            ln = re.sub(r"=60(\s|$)", r"=-1\1", ln)
            n_reshape += 1
        elif kind == "Gemm" and re.search(r" 7=60(\s|$)", ln):
            ln = re.sub(r" 7=60(\s|$)", r"\1", ln).rstrip()
            n_gemm += 1
        elif re.search(r"=60(\s|$)", ln):
            sys.exit(f"REFUSING: unexpected =60 site outside Reshape/Gemm-7=: {ln}")
        out.append(ln)

    text = "\n".join(out) + "\n"
    if n_reshape == 0 and n_gemm == 0:
        sys.exit("REFUSING: no =60 sites found -- input looks already dynamic")
    assert not re.search(r"=60(\s|$)", text), "postcondition: =60 remains"
    assert re.search(r"flatten_138\s+1 1 \d+ \d+ 0=-1 1=120", text), \
        "postcondition: flatten_138 0=-1 missing"
    # 17 dynamic seq slots total (16 Reshape + flatten_138). Older pnnx baked
    # all 17 as =60 (16 edits here); newer pnnx already emits 103..107 as -1,
    # leaving 12 edits. Either way the total must be 17.
    n_dyn = sum(1 for ln in out
                if ln.split()[:1] == ["Reshape"] and "-1" in ln)
    assert n_dyn == 17, f"postcondition: {n_dyn} dynamic reshapes (!= 17)"
    print(f"Reshape edits: {n_reshape} (pre-dynamic: {n_dyn - n_reshape}), "
          f"Gemm 7= drops: {n_gemm}")

    import os
    os.makedirs(args.out_dir, exist_ok=True)
    with open(os.path.join(args.out_dir, "rec_dyn.param"), "w") as f:
        f.write(text)
    shutil.copyfile(args.bin, os.path.join(args.out_dir, "rec_dyn.bin"))
    print(f"OK -> {args.out_dir}/rec_dyn.param/.bin")


if __name__ == "__main__":
    main()
