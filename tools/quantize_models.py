from onnxruntime.quantization import quantize_static, QuantType
from pathlib import Path
import os
import json

def quantize_models(input_files, output_dir):
    os.makedirs(output_dir, exist_ok=True)
    results = []

    for model_path in input_files:
        p_model = Path(model_path)
        output_path = Path(output_dir) / p_model.name

        try:
            quantize_static(
                model_input=str(p_model),
                model_output=str(output_path),
                #weight_type=QuantType.QUInt8,  # Quantize weights to 8-bit integers
                #per_channel=True,
                #reduce_range=True
            )

            results.append({
                "input": str(model_path),
                "output": str(output_path),
                "quantization": "dynamic_int8"
            })
        except Exception as e:
            results.append({
                "input": str(model_path),
                "error": str(e)
            })

    return results

if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--input_files", nargs="+", required=True)
    parser.add_argument("--output_dir", required=True)
    args = parser.parse_args()

    output = quantize_models(args.input_files, args.output_dir)
    print(json.dumps(output, indent=2))
