import onnx
import sys
import json
from pathlib import Path
import os

def analyze_onnx_model(model_path):
    try:
        model = onnx.load(model_path)
    except Exception as e:
        return {"error": str(e)}
    # collect operator types
    ops = set()
    has_quantize = False
    has_dequantize = False
    for node in model.graph.node:
        ops.add(node.op_type)
        if node.op_type == "QuantizeLinear":
            has_quantize = True
        if node.op_type == "DequantizeLinear":
            has_dequantize = True
    # Determine quantization level
    quantization_level = "unknown"
    if has_quantize or has_dequantize:
        # Look for scale and zero_point initializers
        scale_init = None
        zp_init = None
        for init in model.graph.initializer:
            if "scale" in init.name.lower():
                scale_init = init
            if "zero_point" in init.name.lower():
                zp_init = init
        # Helper to map TensorProto.DataType to string
        def initializer_dtype(init):
            dt = init.data_type
            mapping = {
                onnx.TensorProto.FLOAT: "float32",
                onnx.TensorProto.DOUBLE: "float64",
                onnx.TensorProto.INT32: "int32",
                onnx.TensorProto.INT8: "int8",
                onnx.TensorProto.UINT8: "uint8",
                onnx.TensorProto.INT16: "int16",
                onnx.TensorProto.UINT16: "uint16",
                onnx.TensorProto.BOOL: "bool",
            }
            return mapping.get(dt.name, "unknown")
        scale_dtype = initializer_dtype(scale_init) if scale_init else "none"
        zp_dtype = initializer_dtype(zp_init) if zp_init else "none"
        # Heuristic for int8 quantization
        if scale_init and zp_init and "int8" in zp_dtype:
            quantization_level = "int8"
        elif scale_init and zp_init and "uint8" in zp_dtype:
            quantization_level = "uint8"
        else:
            quantization_level = "unknown"
    else:
        quantization_level = "float32 (no quantization nodes)"
    return {
        "file": str(model_path),
        "operators": sorted(list(ops)),
        "quantization": quantization_level,
        "has_quantize_nodes": has_quantize,
        "has_dequantize_nodes": has_dequantize,
    }

# Find all .onnx files under app/src/main/assets
assets_dir = "app/src/main/assets"
onnx_files = []
for root, dirs, files in os.walk(assets_dir):
    for file in files:
        if file.lower().endswith(".onnx"):
            onnx_files.append(Path(root) / file)
onnx_files = sorted(onnx_files)
results = []
for f in onnx_files:
    res = analyze_onnx_model(f)
    results.append(res)

# Output JSON
print(json.dumps(results, indent=2))
