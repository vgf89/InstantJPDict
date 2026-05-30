#!/usr/bin/env bash
# ------------------------------------------------------------
# run_patch_and_quant.sh
#   1️⃣ Patch models to add logits (FP32).
#   2️⃣ Run the static‑quantisation pipeline for the three OCR models.
#
#   The onnx_quantization package lives in ./tools/onnx_quantization
#   and the Python dependencies are managed with uv (project root).
# ------------------------------------------------------------
set -euo pipefail

# ---- 0️⃣ Determine project root (one level up from this script) ----
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

# Directories
ARCHIVE_DIR="${PROJECT_ROOT}/models/archive"
TEMP_DIR="${PROJECT_ROOT}/tools/temp"
ASSETS_DIR="${PROJECT_ROOT}/app/src/main/assets"

mkdir -p "$TEMP_DIR"
mkdir -p "$ASSETS_DIR"
mkdir -p tools/calibration_data/detect
mkdir -p tools/calibration_data/recognize_horiz
mkdir -p tools/calibration_data/recognize_vert

# ---- 1️⃣ Patching (Logits Modification) -------------------------
# We patch the raw models from the archive to add logits before quantization.

patch_model() {
    local src="$1"
    local filename=$(basename "$src")
    local patched="${TEMP_DIR}/${filename%.onnx}.with_logits.onnx"

    echo "▶ Patching $filename → $patched" >&2
    uv run tools/model_patching/patch_models.py \
        --input "$src" \
        --output "$patched" > /dev/null 2>&1
    if [[ ! -f "$patched" ]]; then
        echo "ERROR: Patched model not created at $patched" >&2
        exit 1
    fi
    echo "$patched"
}

# Patch the three specific models
DETECT_FP32="$ARCHIVE_DIR/meiki.text.detect.v0.1.960x544.onnx"
REC_HORIZ_FP32=$(patch_model "$ARCHIVE_DIR/meiki.text.rec.v0.960x32.onnx")
REC_VERT_FP32=$(patch_model "$ARCHIVE_DIR/meiki.text.rec.v0.vertical.32x480.onnx")

# ---- 2️⃣ Run the quantisation scripts ----------------------------
# Use uv to execute the Python scripts.
#

# Detect model
echo "▶ Quantizing Detect model..."
uv run python tools/onnx_quantization/calibrate_detect.py \
    --fp32 "$DETECT_FP32" \
    --output "$ASSETS_DIR/meiki.text.detect.v0.1.960x544.quant.onnx" \
    --calib_dir tools/onnx_quantization/calibration_data/detect

# Horizontal recogniser
echo "▶ Quantizing Horizontal recogniser..."
uv run python tools/onnx_quantization/calibrate_rec_horiz.py \
    --fp32 "$REC_HORIZ_FP32" \
    --output "$ASSETS_DIR/meiki.text.rec.v0.960x32.with_logits.quant.onnx" \
    --calib_dir tools/onnx_quantization/calibration_data/recognize_horiz

# Vertical recogniser
echo "▶ Quantizing Vertical recogniser..."
uv run python tools/onnx_quantization/calibrate_rec_vert.py \
    --fp32 "$REC_VERT_FP32" \
    --output "$ASSETS_DIR/meiki.text.rec.v0.vertical.32x480.with_logits.quant.onnx" \
    --calib_dir tools/onnx_quantization/calibration_data/recognize_vert

echo "✅ Patching and Quantisation pipeline completed successfully."
