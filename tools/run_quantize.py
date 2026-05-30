import sys
from pathlib import Path
from quantize_models import quantize_models

def parse_args():
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('--input_files', nargs='+', required=True)
    parser.add_argument('--output_dir', required=True)
    return parser.parse_args()

if __name__ == '__main__':
    args = parse_args()
    results = quantize_models(args.input_files, args.output_dir)
    import json
    print(json.dumps(results, indent=2))
