import onnx
from onnx import helper, shape_inference
import os

def update_model(in_path, out_path):
    print(f'Processing {in_path}...')
    if not os.path.exists(in_path):
        print(f'Error: {in_path} not found.')
        return

    model = onnx.load(in_path)

    # 1. Remove existing custom outputs and nodes if they exist (to allow re-running)
    to_remove_out = [o for o in model.graph.output if o.name in ['logits', 'indices']]
    for o in to_remove_out:
        model.graph.output.remove(o)

    to_remove_node = [n for n in model.graph.node if n.name in ['logits_ext', 'indices_ext']]
    for n in to_remove_node:
        model.graph.node.remove(n)

    # 2. Run shape inference to populate value_info for internal tensors
    model = shape_inference.infer_shapes(model)

    # 3. Add 'logits' output (the full flattened Sigmoid distribution)
    # This is mapped to '/Flatten_output_0' in the Meiki models
    logits_target = '/Flatten_output_0'
    try:
        vi = next(v for v in model.graph.value_info if v.name == logits_target)
        logits_info = helper.make_tensor_value_info(
            'logits',
            vi.type.tensor_type.elem_type,
            [-1] + [d.dim_value for d in vi.type.tensor_type.shape.dim[1:]]
        )
        model.graph.output.append(logits_info)
        model.graph.node.append(helper.make_node('Identity', [logits_target], ['logits'], name='logits_ext'))
    except StopIteration:
        print(f'Warning: Could not find {logits_target} in graph.')

    # 4. Add 'indices' output (The TopK indices from the model head)
    # This is mapped to '/TopK_output_1' in the Meiki models
    indices_target = '/TopK_output_1'
    try:
        vi_idx = next(v for v in model.graph.value_info if v.name == indices_target)
        indices_info = helper.make_tensor_value_info(
            'indices',
            vi_idx.type.tensor_type.elem_type,
            [-1] + [d.dim_value for d in vi_idx.type.tensor_type.shape.dim[1:]]
        )
        model.graph.output.append(indices_info)
        model.graph.node.append(helper.make_node('Identity', [indices_target], ['indices'], name='indices_ext'))
    except StopIteration:
        print(f'Warning: Could not find {indices_target} in graph.')

    # 5. Save the modified model as .onnx
    onnx.save(model, out_path)
    print(f'Successfully updated: {out_path}')

if __name__ == '__main__':
    models_dir = '../../models/archive'
    assets_dir = '../../app/src/main/assets'
    models = [
        ('meiki.text.rec.v0.960x32.onnx', 'meiki.text.rec.v0.960x32.with_logits.onnx'),
        ('meiki.text.rec.v0.vertical.32x480.onnx', 'meiki.text.rec.v0.vertical.32x480.with_logits.onnx')
    ]

    for src, dst in models:
        update_model(os.path.join(models_dir, src), os.path.join(assets_dir, dst))
