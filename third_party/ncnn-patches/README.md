# ncnn int8 fixes (vendored, Track 1 of #29)

The two no-PR int8 fixes that lived only in the local tree at
`/home/holopengin/repos/ncnn`, now mirrored at `git@github.com:vgf89/ncnn.git`
(`master @ 0c9625b0`) and vendored here so a fresh clone can rebuild.

- `ncnn-int8-fixes.mbox` — `git format-patch` of the 2 commits, applies onto
  pinned upstream `Tencent/ncnn @ 6a1bf000` (the fork point, upstream PR #6960):
  1. `8d190be8` — `ConvolutionDepthWise::load_model` crashed on int8 scale
     terms `201/202` (double requantize fuse via split fanout); handle by
     `% 100` residue, mirroring `Convolution::load_model`.
  2. `0c9625b0` — int8 1x1 conv on flattened 1D blobs (SE squeeze branches)
     misread pack groups as spatial width; mirror the float path's
     reshape-to-3D-and-recurse detour in x86/arm/base `forward_int8`.

Rebuild the exact tree with `tools/build_ncnn.sh` (clone base, `am` the mbox,
verify subjects, build host `ncnn2table`/`ncnn2int8` + `arm64-v8a libncnn.a`
with `NCNN_VULKAN=OFF`). Verified here: script output `src/` is byte-identical
(`diff -r`) to the fork working tree. Compile leg not yet run on this machine
(no cmake) — first full build happens with the nuke-and-pave run.
