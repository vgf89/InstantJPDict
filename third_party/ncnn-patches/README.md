# ncnn fork patches (all merged into vgf89/ncnn — mboxes kept as record)

The fork carries three local fixes on top of upstream, all required by our
models; `tools/build_ncnn.sh` builds the fork at `FORK_PIN` and enforces
each by marker grep (plus an `activation_ss` check on the built archive
and a `verifyNcnnBlob` Gradle preBuild guard), so a lib missing any of
them fails fast instead of mis-inferring silently:

- int8 1x1 conv on flattened 1D blobs (SE squeeze branches) — marker
  `bottom_blob_3d = bottom_blob_unbordered`; without it, pack groups are
  misread as spatial width.
- `ConvolutionDepthWise::load_model` int8 scale terms `201/202` (double
  requantize fuse via split fanout) — marker `int8_scale_term % 100`;
  without it, weight scales stay empty and load crashes in quantize_to_int8.
- Fused GELU activation type 7, tanh approx (#41) — marker `activation_ss`;
  `rec_dyn.param` carries 13 `9=7` conv layers and mis-infers without it
  (dense garbage, blank collapse — diagnosed host-side via `synth/`
  ground truth). `det.param` uses plain `GELU … 0=1` (upstream fast mode)
  and needs no lib patch.

`ncnn-int8-fixes.mbox` is the `git format-patch` of the 2 int8 commits
(applies onto upstream `Tencent/ncnn @ 6a1bf000`, PR #6960);
`gelu-fused-activation.mbox` is the GELU patch. Both are superseded by
building the fork itself, which contains all three.

Rebuild the exact tree with `tools/build_ncnn.sh` (clone fork at the pin,
verify markers, build host `ncnn2table`/`ncnn2int8` + `arm64-v8a libncnn.a`
with `NCNN_VULKAN=OFF`). Verified here: script output `src/` is byte-identical
(`diff -r`) to the fork working tree. Compile leg not yet run on this machine
(no cmake) — first full build happens with the nuke-and-pave run.
