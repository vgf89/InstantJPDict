#!/usr/bin/env sh
# ------------------------------------------------------------
# Patch, pre-process, and then quantize every ONNX model in models/archive,
# storing patched/pre-processed models in tools/temp and quantized models in app/src/main/assets.
# ------------------------------------------------------------

# Directories (relative to this script's location)
BASE_DIR="$(cd "$(dirname "$0")/.." && pwd)"  # InstantJPDict root
ARCHIVE_DIR="${BASE_DIR}/models/archive"
TEMP_DIR="${BASE_DIR}/tools/temp"
ASSETS_DIR="${BASE_DIR}/app/src/main/assets"

# Ensure directories exist
mkdir -p "$TEMP_DIR"
mkdir -p "$ASSETS_DIR"

# Loop over each .onnx file in the archive
for src_path in "$ARCHIVE_DIR"/*.onnx; do
  # Get just the filename (e.g., meiki.text.rec.v0.960x32.onnx)
  filename="$(basename "$src_path")"

  # 1. Patching
  # Patched model path (same name, but with .with_logits.onnx) in temp
  patched_path="${TEMP_DIR}/${filename%.onnx}.with_logits.onnx"

  echo "▶ Patching $filename → $patched_path"
  cd "$BASE_DIR/tools"
    uv run model_patching/patch_models.py \
      --input "$src_path" \
      --output "$patched_path"
    cd - > /dev/null

  # 2. Pre-processing
  # Determine the input shape from the patched model
  echo "▶ Determining input shape for $patched_path"
  input_shape=$(python3 -c "
import onnx
model = onnx.load('$patched_path')
inp = model.graph.input[0]
shape = []
for dim in inp.type.tensor_type.shape.dim:
    if dim.HasField('dim_value') and dim.dim_value > 0:
        shape.append(str(dim.dim_value))
    else:
        shape.append('1')
print(','.join(shape))
")

  # Fix dynamic shapes to avoid "Incomplete symbolic shape inference" error
  fixed_path="${TEMP_DIR}/${filename%.onnx}.with_logits.fixed.onnx"
  echo "▶ Fixing dynamic shapes → $fixed_path (shape=$input_shape)"
  uv run -m onnxruntime.tools.make_dynamic_shape_fixed \
    --input_name images \
    --input_shape $input_shape \
    "$patched_path" \
    "$fixed_path"

  # Pre-processed model path (adds .pre.onnx) in temp
  preprocessed_path="${TEMP_DIR}/${filename%.onnx}.with_logits.pre.onnx"

  echo "▶ Pre-processing $fixed_path → $preprocessed_path"
  uv run -m onnxruntime.quantization.preprocess \
    --input "$fixed_path" \
    --output "$preprocessed_path"

  # 3. Quantization
  # Quantized model path (adds .quant before .onnx) in assets
  quant_path="${ASSETS_DIR}/${filename%.onnx}.with_logits.quant.onnx"

  echo "▶ Quantizing $preprocessed_path → $quant_path"
  uv run "$BASE_DIR/tools/quantize_models.py" \
    --input_files "$preprocessed_path" \
    --output_dir "$ASSETS_DIR"

  # The quantizer writes the file with the same name as the input,
  # so rename it to include the .quant suffix for clarity.
  if [ -f "${ASSETS_DIR}/$(basename "$preprocessed_path")" ]; then
    mv "${ASSETS_DIR}/$(basename "$preprocessed_path")" "$quant_path"
  fi
done

echo "✅ All models patched, pre-processed, and quantized."
