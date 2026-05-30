import argparse
import os

from onnxruntime.quantization import QuantFormat, QuantType, quantize_static
from static_calibrator import StaticCalibrator


def main():
    parser = argparse.ArgumentParser(
        description="Static-calibrate & quantize the HORIZONTAL recogniser"
    )
    parser.add_argument(
        "--fp32", required=True, help="Path to the original fp32 recogniser model"
    )
    parser.add_argument(
        "--output", required=True, help="Path where the quantised model will be written"
    )
    parser.add_argument(
        "--calib_dir",
        default=os.path.join("calibration_data", "recognize_horiz"),
        help="Folder containing representative horizontal line images",
    )
    args = parser.parse_args()

    dr = StaticCalibrator(args.calib_dir, args.fp32)
    quantize_static(
        model_input=args.fp32,
        model_output=args.output,
        calibration_data_reader=dr,
        quant_format=QuantFormat.QDQ,
        per_channel=False,
        weight_type=QuantType.QInt8,
    )
    print(f"✅ Quantised horizontal recogniser saved to: {args.output}")


if __name__ == "__main__":
    main()
