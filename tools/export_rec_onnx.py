#!/usr/bin/env python3
"""Reconstruct the lost safetensors -> torchscript export (#29 Track 1, GAP-EXPORT).

Status: RECONSTRUCTION, not a recovered script. The original Wrapper source
is lost (confirmed: no torch/transformers in .venv, no /tmp artifacts, no
modeling_pp_ocrv6* on disk). This script encodes everything known from the
#5 recipe and fails loudly where the record is silent, so the build machine
can validate it via the #5 parity contract instead of trusting it blindly.

Known (verified 2026-09-06 on the build machine / Hub):
  - transformers 5.16.1 + torch 2.13.0
  - class PPOCRV6SmallRecForTextRecognition (explicit import REQUIRED:
    models/archive/PP-OCRv6_small_rec_safetensors/config.json has model_type
    pp_ocrv6_small_rec but no `architectures` key, so AutoModel cannot resolve)
  - checkpoint: PaddlePaddle/PP-OCRv6_small_rec == models/archive/... (local)
  - trace input [1,3,48,W] per W in {64,128,256,480} -> rec_w{W}.pt
  - next: pnnx rec_w{W}.pt inputshape=[1,3,48,W] (pnnx 20260526)

Validation (#5 contract): HF-vs-ORT-vs-ncnn max |logit| 4-8e-5 + 100% CTC
top-1 greedy match on both bench images. If the traced graph trips either,
the Wrapper (preprocessing/normalization inside vs outside trace) is the
prime suspect: runtime feeds gray/127.5-1 NCHW, so the Wrapper must take
raw [0,1] RGB and normalize internally, or take pre-normalized input --
match whatever the original did by checking parity, not by guessing.

Usage:
  python3 tools/export_rec_onnx.py \
    --ckpt models/archive/PP-OCRv6_small_rec_safetensors \
    --widths 64,128,256,480 --out /tmp/pt_models [--logit logits]
"""
import argparse
import os
import sys


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", required=True)
    ap.add_argument("--widths", default="64,128,256,480")
    ap.add_argument("--out", required=True)
    ap.add_argument("--logit", default="logits",
                    help="output attribute holding [1,seq,18710] logits")
    ap.add_argument("--height", type=int, default=48)
    args = ap.parse_args()

    try:
        import torch
    except ImportError:
        sys.exit("REFUSING: torch not installed (need torch==2.13.0 per #5 recipe)")
    try:
        from transformers import PPOCRV6SmallRecForTextRecognition
    except ImportError as e:
        sys.exit(f"REFUSING: {e} (need transformers==5.16.1 per #5 recipe)")

    widths = [int(w) for w in args.widths.split(",")]
    model = PPOCRV6SmallRecForTextRecognition.from_pretrained(
        args.ckpt, dtype=torch.float32)
    model.eval()

    # Probe once, eagerly: show what the model returns so --logit stays honest.
    with torch.no_grad():
        probe = model(torch.zeros(1, 3, args.height, widths[0]))
    print(f"probe type: {type(probe)}")
    if hasattr(probe, "keys"):
        print(f"probe keys: {list(probe.keys())}")
    out0 = getattr(probe, args.logit, None)
    if out0 is None and isinstance(probe, (tuple, list)):
        out0 = probe[0]
    if out0 is None:
        sys.exit(f"REFUSING: no {args.logit!r} on model output; "
                 f"inspect probe above and pass --logit <attr>")
    print(f"probe {args.logit} shape @w{widths[0]}: {tuple(out0.shape)} "
          f"(expect (1, {widths[0] // 8}, 18710))")
    assert out0.shape[-1] == 18710, f"last dim {out0.shape[-1]} != 18710 classes"

    logit_name = args.logit

    class Wrapper(torch.nn.Module):
        def __init__(self, m):
            super().__init__()
            self.m = m

        def forward(self, x):
            o = self.m(x)
            l = getattr(o, logit_name, None)
            return l if l is not None else o[0]

    wrapped = Wrapper(model).eval()
    os.makedirs(args.out, exist_ok=True)
    for w in widths:
        with torch.no_grad():
            traced = torch.jit.trace(wrapped, torch.zeros(1, 3, args.height, w))
        p = os.path.join(args.out, f"rec_w{w}.pt")
        traced.save(p)
        print(f"saved {p}  (pnnx next: inputshape=[1,3,48,{w}])")
    print("OK -- validate with the #5 contract before converting")


if __name__ == "__main__":
    main()
