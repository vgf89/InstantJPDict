import onnx
from onnx import numpy_helper
import json
import os

def extract_vocab(model_path, out_path):
    print(f'Extracting vocab from {model_path}...')
    if not os.path.exists(model_path):
        print(f'Error: {model_path} not found.')
        return

    model = onnx.load(model_path)

    # Search for the character code lookup table in the initializers
    # In Meiki models, this is named 'char_code_lut'
    for init in model.graph.initializer:
        if 'char_code_lut' in init.name:
            arr = numpy_helper.to_array(init)
            with open(out_path, 'w') as f:
                # Save as a simple JSON list of integers (Unicode codepoints)
                json.dump(arr.tolist(), f)
            print(f'Successfully saved vocab to: {out_path} (Size: {len(arr)})')
            return

    print('Error: Could not find char_code_lut initializer.')

if __name__ == '__main__':
    assets_dir = '../../app/src/main/assets'
    extract_vocab(
        os.path.join(assets_dir, 'meiki.text.rec.v0.960x32.onnx'),
        os.path.join(assets_dir, 'char_vocab.json')
    )
