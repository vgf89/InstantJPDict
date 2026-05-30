import onnx
import sys

def inspect_model(model_path):
    model = onnx.load(model_path)
    graph = model.graph

    print(f"Inspecting {model_path}...")

    # Check for Mul nodes related to quantization scaling
    mul_nodes = [n for n in graph.node if n.op_type == 'Mul' and 'quant' in n.name.lower()]

    for node in mul_nodes:
        print(f"\nFound Mul node: {node.name}")
        print(f"  Inputs: {node.input}")

        # Check shapes if possible
        for inp in node.input:
            # Look in value_info
            vi = next((v for v in graph.value_info if v.name == inp), None)
            if vi:
                print(f"  Input {inp} shape: {vi.type.tensor_type.shape}")
            else:
                # Might be an initializer or node output
                init = next((i for i in graph.initializer if i.name == inp), None)
                if init:
                    print(f"  Input {inp} is initializer, shape: {init.dims}")
                else:
                    print(f"  Input {inp} shape: [unknown]")

if __name__ == "__main__":
    if len(sys.argv) > 1:
        inspect_model(sys.argv[1])
    else:
        print("Usage: python inspect_model.py <path_to_model>")
