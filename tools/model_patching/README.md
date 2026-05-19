# Model Patching Tools

These scripts are used to modify the base **Meiki OCR** models to support high-precision alternative candidate extraction in the InstantJPDict app.

## Requirements

Ensure you have the necessary Python packages installed:
```bash
pip install onnx
```

## Scripts

### 1. `patch_models.py`
This script modifies the `.onnx` model files to expose intermediate tensors that are normally hidden.
- **`logits`**: Exposes the full Sigmoid probability distribution for all 4,372 characters.
- **`indices`**: Exposes the `TopK` indices, which are required to correctly map detections back to their query slots in the probability matrix.

**Usage:**
```bash
python3 patch_models.py
```
*Outputs: `...with_logits.onnx` files in the app assets folder.*

### 2. `extract_vocab.py`
The model uses internal vocabulary indices (0-4371). This script extracts the **Character Code Lookup Table (LUT)** from the model's weights so the Android app can translate those indices into Unicode characters.

**Usage:**
```bash
python3 extract_vocab.py
```
*Output: `char_vocab.json` in the app assets folder.*
