import onnx
from onnx import helper, shape_inference
import os

def fix_quantized_model(in_path, out_path):
    print(f'Fixing quantized model {in_path}...')
    if not os.path.exists(in_path):
        print(f'Error: {in_path} not found.')
        return

    model = onnx.load(in_path)
    graph = model.graph

    # Run shape inference to populate value_info for internal tensors
    model = shape_inference.infer_shapes(model)

    # We are looking for Mul nodes that are used for quantization output scaling
    # These typically have names like '..._quant_output_scale_mul'
    nodes_to_patch = [n for n in graph.node if n.op_type == 'Mul' and 'quant_output_scale_mul' in n.name]

    if not nodes_to_patch:
        print('No problematic Mul nodes found.')
        onnx.save(model, out_path)
        return

    print(f'Found {len(nodes_to_patch)} nodes to patch.')

    for mul_node in nodes_to_patch:
        # The Mul node has two inputs: [matmul_output, scale_tensor]
        matmul_output_name = mul_node.input[0]

        # Get the rank of the MatMul output
        vi = next((v for v in graph.value_info if v.name == matmul_output_name), None)

        # Skip if the scale is a small initializer (common cause of broadcasting issues)
        scale_tensor_name = mul_node.input[1]
        scale_init = next((i for i in graph.initializer if i.name == scale_tensor_name), None)
        if scale_init:
             print(f"  Checking {mul_node.name}: Scale Initializer={scale_init.name}, Dims={scale_init.dims}")
             if len(scale_init.dims) <= 2:
                 print(f"    Skipping {mul_node.name} due to small initializer.")
                 continue
        else:
             print(f"  Checking {mul_node.name}: Scale tensor {scale_tensor_name} is not a direct initializer.")


        print(f"  Patching {mul_node.name}: MatMul Output={matmul_output_name}")
        if vi and vi.type.tensor_type.shape.dim:
            dims = [d.dim_value for d in vi.type.tensor_type.shape.dim]
            print(f"    Inferred Rank: {len(dims)}, Dims: {dims}")
            rank = len(dims)
        else:
            print(f"    WARNING: Inferred Shape is UNKNOWN or empty, defaulting to rank 4.")
            rank = 4

        # Create a shape tensor for Reshape: [1] * rank
        shape_name = f"{mul_node.name}_shape"
        shape_vals = [1] * rank
        shape_tensor = helper.make_tensor(
            name=shape_name,
            data_type=onnx.TensorProto.INT64,
            dims=[rank],
            vals=shape_vals
        )
        graph.initializer.append(shape_tensor)

        # Create Reshape node to make the scale broadcastable
        reshaped_scale_name = f"{mul_node.input[1]}_reshaped"
        reshape_node = helper.make_node(
            'Reshape',
            inputs=[mul_node.input[1], shape_name],
            outputs=[reshaped_scale_name],
            name=f"{mul_node.name}_reshape"
        )
        graph.node.append(reshape_node)

        # Update Mul node to use the reshaped scale
        mul_node.input[1] = reshaped_scale_name

    onnx.save(model, out_path)
    print(f'Successfully fixed: {out_path}')

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
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", help="Path to the input ONNX model")
    parser.add_argument("--output", required=True, help="Path to save the patched ONNX model")
    parser.add_argument("--fix-quantized", help="Path to the quantized ONNX model to fix")
    args = parser.parse_args()

    if args.fix_quantized:
        fix_quantized_model(args.fix_quantized, args.output)
    elif args.input:
        update_model(args.input, args.output)
    else:
        parser.error("Either --input or --fix-quantized must be provided.")
