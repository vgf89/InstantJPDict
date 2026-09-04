#!/usr/bin/env python3
"""
Convert PP-OCRv6 ONNX models to TFLite using onnx2tf (flatbuffer_direct backend).

Fixes hardcoded Reshape dims that prevent dynamic-width inference.

Usage:
    python3 tools/convert_to_tflite.py

Output:
    app/src/main/assets/PP-OCRv6_small_det_onnx/det_float32.tflite
    app/src/main/assets/PP-OCRv6_small_rec_onnx/rec_float32.tflite
"""

import os
import sys
import shutil
import argparse

# ── Paths (relative to project root) ────────────────────────────────────────
PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
ASSETS_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets")

DET_SRC = os.path.join(ASSETS_DIR, "PP-OCRv6_small_det_onnx", "inference.onnx")
REC_SRC = os.path.join(ASSETS_DIR, "PP-OCRv6_small_rec_onnx", "inference.onnx")

DET_OUT_NAME = "det_float32.tflite"
REC_OUT_NAME = "rec_float32.tflite"

# Model shapes for freezing — detection is 960×960 square, recognition is 48×1920
DET_SHAPE = [1, 3, 960, 960]
REC_SHAPE = [1, 3, 48, 1920]


def ensure_deps():
    """Ensure onnx2tf is available (check existing venv or create one)."""
    import subprocess
    import importlib.util

    for venv_path in ["/tmp/onnx2tf_env", "/tmp/onnx2tf_convert_venv"]:
        python = os.path.join(venv_path, "bin", "python3")
        if os.path.exists(python):
            check = subprocess.run(
                [python, "-c", "import onnx2tf"],
                capture_output=True,
            )
            if check.returncode == 0:
                os.execv(python, [python, __file__] + sys.argv[1:])
                return

    try:
        import onnx2tf
        return
    except ImportError:
        pass

    print("onnx2tf not found — installing into temporary venv …")
    venv = os.path.join("/tmp", "onnx2tf_convert_venv")
    subprocess.check_call([sys.executable, "-m", "venv", "--clear", venv])
    pip = os.path.join(venv, "bin", "pip")
    subprocess.check_call(
        [pip, "install", "onnx2tf", "numpy<2", "sng4onnx", "psutil==5.9.5"],
        stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT,
    )
    os.execv(
        os.path.join(venv, "bin", "python3"),
        [os.path.join(venv, "bin", "python3"), __file__] + sys.argv[1:],
    )


def restore_dynamic_reshape_dims(
    onnx_path: str,
    input_shape: list[int],
    keep_dims: set[int] | None = None,
) -> str:
    """
    Simplify an ONNX model with onnxsim, then restore -1 dims in Reshape ops
    for the spatial/sequence dimension (which depends on input width/height).

    Without this, onnxsim hardcodes the inferred dimension, making the model
    reject non-frozen input sizes after TFLite resizeInput.

    The spatial dim is typically the **second** dim in the model's NCHW layout
    (after the batch dim), which corresponds to the -1 in [0, -1, ...] Reshape
    targets.  We must also handle [0, -1, heads, head_dim] patterns where the
    -1 is at position 1 as well.

    keep_dims: set of (node_name, dim_pos) tuples that should remain dynamic.
    """
    import onnx
    import numpy as np
    from onnxsim import simplify

    model, check = simplify(onnx.load(onnx_path),
                            overwrite_input_shapes={'x': input_shape})
    assert check, f"onnxsim failed on {onnx_path}"

    # Load the ORIGINAL model to check which Reshape dims were -1
    orig = onnx.load(onnx_path)
    orig_reshape_dims: dict[str, list[int]] = {}
    for node in orig.graph.node:
        if node.op_type == 'Reshape' and len(node.input) > 1:
            init_name = node.input[1]
            for init in orig.graph.initializer:
                if init.name == init_name:
                    orig_reshape_dims[node.name] = \
                        list(np.frombuffer(init.raw_data, dtype=np.int64))
                    break

    # Now go through simplified model and restore -1 where it was originally -1
    modified = False
    for node in model.graph.node:
        if node.op_type == 'Reshape' and len(node.input) > 1:
            init_name = node.input[1]
            if node.name not in orig_reshape_dims:
                continue
            orig_dims = orig_reshape_dims[node.name]
            for init in model.graph.initializer:
                if init.name == init_name:
                    curr = np.frombuffer(init.raw_data, dtype=np.int64)
                    changed = False
                    for i, (o, c) in enumerate(zip(orig_dims, curr)):
                        if o == -1 and c != -1:
                            # onnxsim froze this -1 to {c}, restore it
                            curr[i] = -1
                            changed = True
                            print(f"    Restored -1 at {node.name}[{i}] "
                                  f"(was {c}, orig was -1)")
                    if changed:
                        init.raw_data = curr.tobytes()
                        modified = True
                    break

    out_path = onnx_path.replace('.onnx', '_fixed.onnx')
    onnx.save(model, out_path)
    return out_path


def convert_one(
    onnx_path: str,
    out_dir: str,
    out_name: str,
    *,
    verbose: bool = False,
    output_float16: bool = False,
    input_shape: list[int] | None = None,
    skip_simplify: bool = False,
):
    """Run onnx2tf conversion for a single model, place result in out_dir."""
    import sys, glob

    site_packages = os.path.join(os.path.dirname(sys.executable), "..",
                                 "lib", f"python{sys.version_info.major}.{sys.version_info.minor}",
                                 "site-packages")
    sp = os.path.abspath(site_packages)
    if os.path.isdir(sp) and sp not in sys.path:
        sys.path.insert(0, sp)

    from onnx2tf.onnx2tf import convert as _convert

    model_path = onnx_path
    if not skip_simplify and input_shape is not None:
        print(f"  Simplifying {os.path.basename(onnx_path)} at {input_shape} …")
        model_path = restore_dynamic_reshape_dims(onnx_path, input_shape)
    else:
        print(f"  Using {os.path.basename(onnx_path)} as-is …")

    tmp_dir = "/tmp/onnx2tf_tmp"
    if os.path.exists(tmp_dir):
        shutil.rmtree(tmp_dir)

    print(f"  Converting …")
    sys.stdout.flush()

    _convert(
        input_onnx_file_path=model_path,
        output_folder_path=tmp_dir,
        tflite_backend="flatbuffer_direct",
        non_verbose=not verbose,
        output_dynamic_range_quantized_tflite=False,
        output_integer_quantized_tflite=False,
    )

    stem = os.path.splitext(os.path.basename(onnx_path))[0]
    float32_src = os.path.join(tmp_dir, f"{stem}_float32.tflite")
    if not os.path.exists(float32_src):
        matches = glob.glob(os.path.join(tmp_dir, "*_float32.tflite"))
        if matches:
            float32_src = matches[0]
        else:
            raise FileNotFoundError(f"No float32 tflite in {tmp_dir}")

    os.makedirs(out_dir, exist_ok=True)
    dst = os.path.join(out_dir, out_name)
    shutil.copy2(float32_src, dst)
    print(f"    → {dst}  ({os.path.getsize(dst) / 1024 / 1024:.1f} MB)")

    if output_float16:
        float16_src = float32_src.replace("_float32.", "_float16.")
        if os.path.exists(float16_src):
            dst16 = os.path.join(out_dir, out_name.replace("_float32.", "_float16."))
            shutil.copy2(float16_src, dst16)
            print(f"    → {dst16}  ({os.path.getsize(dst16) / 1024 / 1024:.1f} MB)")

    shutil.rmtree(tmp_dir, ignore_errors=True)

    # Verify dynamic reshape works
    try:
        verify_dynamic_resize(dst)
    except Exception as e:
        print(f"    ⚠  Dynamic resize check: {e}")


def verify_dynamic_resize(tflite_path: str):
    """Test that the TFLite model accepts resizeInput at multiple widths."""
    import ai_edge_litert as _lrt
    import numpy as np

    interp = _lrt.Interpreter(model_path=tflite_path)
    interp.allocate_tensors()
    orig_in = interp.get_input_details()[0]['shape'].tolist()
    orig_out = interp.get_output_details()[0]['shape'].tolist()
    print(f"    Original:  {orig_in} → {orig_out}")

    for w in [64, 128, 320, 640, 960]:
        interp.resize_tensor_input(0, [1, orig_in[1], w, orig_in[-1]])
        interp.allocate_tensors()
        inp = np.zeros([1, orig_in[1], w, orig_in[-1]], dtype=np.float32)
        interp.set_tensor(0, inp)
        try:
            interp.invoke()
            out_shape = list(interp.get_tensor(0).shape)
            print(f"    Width {w:4d}: input [1,{orig_in[1]},{w},{orig_in[-1]}] → output {out_shape}")
        except Exception as e:
            cl = '\n' if '\n' in str(e) else ''
            print(f"    Width {w:4d}: FAILED{cl}{e}")


def main():
    parser = argparse.ArgumentParser(description="Convert PP-OCR ONNX → TFLite")
    parser.add_argument("--verbose", action="store_true", help="Show onnx2tf output")
    parser.add_argument("--float16", action="store_true", help="Also export float16 variant")
    parser.add_argument("--det-only", action="store_true", help="Only convert detection model")
    parser.add_argument("--rec-only", action="store_true", help="Only convert recognition model")
    args = parser.parse_args()

    ensure_deps()

    det_out_dir = os.path.join(ASSETS_DIR, "PP-OCRv6_small_det_onnx")
    rec_out_dir = os.path.join(ASSETS_DIR, "PP-OCRv6_small_rec_onnx")

    print("=" * 60)
    print("PP-OCRv6 ONNX → TFLite conversion")
    print("=" * 60)

    if not args.rec_only:
        print("\n[1/2] Detection model")
        convert_one(DET_SRC, det_out_dir, DET_OUT_NAME, verbose=args.verbose,
                    input_shape=DET_SHAPE)

    if not args.det_only:
        print("\n[2/2] Recognition model")
        convert_one(REC_SRC, rec_out_dir, REC_OUT_NAME, verbose=args.verbose,
                    output_float16=args.float16, input_shape=REC_SHAPE)

    print("\nDone.")


if __name__ == "__main__":
    main()
