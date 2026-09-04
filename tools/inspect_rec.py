import onnx
from collections import Counter

m = onnx.load('app/src/main/assets/PP-OCRv6_small_rec_onnx/inference.onnx')
g = m.graph
print('=== inputs ===')
for i in g.input:
    dims = [d.dim_value if d.HasField('dim_value') else d.dim_param for d in i.type.tensor_type.shape.dim]
    print(' ', i.name, dims)
print('=== outputs ===')
for o in g.output:
    dims = [d.dim_value if d.HasField('dim_value') else d.dim_param for d in o.type.tensor_type.shape.dim]
    print(' ', o.name, dims)
print('initializers:', len(g.initializer))
ops = Counter(n.op_type for n in g.node)
print('=== node ops (%d nodes) ===' % len(g.node))
for op, c in ops.most_common(50):
    print(f'  {op}: {c}')

# Look for autoregressive / attention patterns
has_loop = any(n.op_type == 'Loop' for n in g.node)
has_scan = any(n.op_type == 'Scan' for n in g.node)
print('=== Loop node:', has_loop, ' Scan:', has_scan)

# find MatMul/Softmax pairs (attention), and any node names hinting at attn/kv
for n in g.node:
    if n.op_type in ('Attention', 'MultiHeadAttention', 'GroupQueryAttention', 'GatherElements'):
        print('ATTN NODE:', n.op_type, n.name)
    low = n.name.lower()
    if any(k in low for k in ('attn', 'kv', 'key', 'query', 'value', 'past')):
        print('KV-ish node:', n.op_type, n.name)

# LSTM/GRU/RNN presence
for n in g.node:
    if n.op_type in ('LSTM', 'GRU', 'RNN'):
        print('RECURRENT NODE:', n.op_type, n.name, 'dir=', [a for a in n.attribute if a.name=='direction'])

# count Conv layers and their channel widths (backbone shape)
convs = [n for n in g.node if n.op_type == 'Conv']
print('=== Conv count:', len(convs))
# print a few conv output shapes via value_info
vinfo = {vi.name: [d.dim_value if d.HasField('dim_value') else d.dim_param for d in vi.type.tensor_type.shape.dim]
         for vi in g.value_info if vi.type.tensor_type.shape.dim}
# sample some tensor shapes along the backbone (names with conv/bn output)
names = [n.output[0] for n in convs]
for nm in names[:12]:
    print(' conv out:', nm, vinfo.get(nm))
