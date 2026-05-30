# Static Quantization for InstantJPDict OCR Models

## Directory Structure
```
InstantJPDict/onnx_quantization/
├── calibrate_detect.py          # Calibrator for detection model
├── calibrate_rec_horiz.py       # Calibrator for horizontal recognition
├── calibrate_rec_vert.py        # Calibrator for vertical recognition
├── static_calibrator.py         # Reusable calibration helper
├── data_reader.py              # Copy from ailia-ai repo (unchanged)
└── calibration_data/
    ├── detect/                  # Detection images
    │   ├── img_001.jpg
    │   └── img_002.jpg
    ├── recognize_horiz/          # Horizontal line images
    │   ├── line_001.jpg
    │   └── line_002.jpg
    └── recognize_vert/          # Vertical line images
        ├── column_001.jpg
        └── column_002.jpg
```

## Setup

1. Install required Python packages:
```bash
python -m venv venv
source venv/bin/activate
pip install onnx onnxruntime pillow
```

2. Copy your FP32 models to a folder (e.g., `models_fp32/`):
```
meiki.text.detect.v0.1.960x544.with_logits.onnx
meiki.text.rec.v0.960x32.with_logits.onnx
meiki.text.rec.v0.vertical.32x480.with_logits.onnx
```

3. Add representative images to each calibration folder:
- `calibration_data/detect/` - screenshots or images with Japanese text
- `calibration_data/recognize_horiz/` - cropped horizontal text lines
- `calibration_data/recognize_vert/` - cropped vertical text lines

## Usage

Run each calibrator script (replace paths as needed):

```bash
# Detect model
python calibrate_detect.py \
    --fp32 models_fp32/meiki.text.detect.v0.1.960x544.with_logits.onnx \
    --output app/src/main/assets/meiki.text.detect.v0.1.960x544.with_logits.quant.onnx

# Horizontal recogniser
python calibrate_rec_horiz.py \
    --fp32 models_fp32/meiki.text.rec.v0.960x32.with_logits.onnx \
    --output app/src/main/assets/meiki.text.rec.v0.960x32.with_logits.quant.onnx

# Vertical recogniser
python calibrate_rec_vert.py \
    --fp32 models_fp32/meiki.text.rec.v0.vertical.32x480.with_logits.onnx \
    --output app/src/main/assets/meiki.text.rec.v0.vertical.32x480.with_logits.quant.onnx
```

## Key Settings

- **QuantFormat.QDQ**: Uses Quantize-Dequantize format (works on Android)
- **per_channel=False**: Static per-tensor quantization (most stable)
- **optimize_model=False**: Prevents shape inference errors
- **weight_type=QuantType.QInt8**: 8-bit signed integer weights

## Result

Replace the quantized models in `app/src/main/assets/` with the generated `.quant.onnx` files. The app should now load without the `MatMul_quant_output_scale_mul` error.