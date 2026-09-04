import onnx
from collections import Counter

m = onnx.load('app/src/main/assets/PP-OCRv6_small_rec_onnx/inference.onnx')
g = m.graph
print('=== MatMul / Softmax / Transpose neighborhood ===')
for n in g.node:
    if n.op_type in ('MatMul', 'Softmax', 'Transpose', 'ReduceMean'):
        ins = ','.join(n.input[:3])
        print(f'{n.op_type:10s} {n.name:44s} <- {ins}')

print()
print('=== static rec_w480 ===')
m2 = onnx.load('app/src/main/assets/PP-OCRv6_small_rec_onnx/rec_w480.onnx')
g2 = m2.graph
print('inputs:', [(i.name, [d.dim_value for d in i.type.tensor_type.shape.dim]) for i in g2.input])
print('outputs:', [(o.name, [d.dim_value for d in o.type.tensor_type.shape.dim]) for o in g2.output])
print('nodes:', len(g2.node), 'opsets:', [(o.domain, o.version) for o in m2.opset_import])
ops2 = Counter(n.op_type for n in g2.node)
print('loop/scan/attn in static:', any(n.op_type in ('Loop', 'Scan', 'Attention') for n in g2.node))
