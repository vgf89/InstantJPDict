import onnx
from collections import Counter

m = onnx.load('tools/temp/tiny/inference.onnx')
g = m.graph
print('inputs:', [(i.name, [d.dim_value if d.HasField('dim_value') else d.dim_param for d in i.type.tensor_type.shape.dim]) for i in g.input])
print('outputs:', [(o.name, [d.dim_value if d.HasField('dim_value') else d.dim_param for d in o.type.tensor_type.shape.dim]) for o in g.output])
ops = Counter(n.op_type for n in g.node)
print('nodes:', len(g.node), 'initializers:', len(g.initializer))
print('top ops:', ops.most_common(14))
print('has Loop/Scan:', any(n.op_type in ('Loop', 'Scan') for n in g.node))
print('opsets:', [(o.domain, o.version) for o in m.opset_import])

# freeze at [1,3,48,480] for a fair FLOP comparison with the small model
try:
    import onnxsim
    sim, ok = onnxsim.simplify(m, overwrite_input_shapes={'x': [1, 3, 48, 480]})
    print('onnxsim ok:', ok)
    if ok:
        onnx.save(sim, 'tools/temp/tiny/rec_w480.onnx')
        g2 = sim.graph
        print('frozen output:', [(o.name, [d.dim_value for d in o.type.tensor_type.shape.dim]) for o in g2.output])
except ImportError:
    print('onnxsim not installed')
