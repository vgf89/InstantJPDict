import onnx
import numpy as np
from collections import Counter

def concrete(dim):
    if dim.HasField('dim_value') and dim.dim_value > 0:
        return dim.dim_value
    return None  # symbolic

def dims_of(vi):
    return [concrete(d) for d in vi.type.tensor_type.shape.dim]

def run(path, label, input_name='x'):
    m = onnx.load(path)
    g = m.graph
    shape_map = {}
    for vi in list(g.value_info) + list(g.input) + list(g.output):
        shape_map[vi.name] = dims_of(vi)

    init_shapes = {}
    init_bytes = 0
    for init in g.initializer:
        arr = onnx.numpy_helper.to_array(init)
        init_shapes[init.name] = list(arr.shape)
        init_bytes += arr.nbytes
        del arr

    total_macs = 0
    per_type = Counter()
    skipped = 0
    head_macs = 0
    head_name = None
    attn_macs = 0
    conv_rows = []

    def shape_of(name):
        if name in shape_map:
            return shape_map[name]
        if name in init_shapes:
            return init_shapes[name]
        return None

    for n in g.node:
        if n.op_type == 'Conv':
            x_shape = shape_of(n.input[0])
            w_shape = shape_of(n.input[1])
            if x_shape and w_shape:
                groups = 1
                for a in n.attribute:
                    if a.name == 'group':
                        groups = a.i
                out_c = w_shape[0]
                in_c_per_g = w_shape[1]
                kh, kw = w_shape[2], w_shape[3]
                # prefer OUTPUT spatial dims from value_info (correct for strides)
                out_shape = shape_of(n.output[0])
                out_hw = None
                if out_shape and len(out_shape) >= 4 and out_shape[2] and out_shape[3]:
                    out_hw = out_shape[2] * out_shape[3]
                elif len(x_shape) >= 4 and x_shape[2] and x_shape[3]:
                    out_hw = x_shape[2] * x_shape[3]
                if out_hw:
                    macs = out_c * in_c_per_g * kh * kw * out_hw
                    total_macs += macs
                    per_type['Conv'] += macs
                    conv_rows.append((n.name, macs, out_c, in_c_per_g, kh, kw, out_shape or x_shape))
                else:
                    skipped += 1
            else:
                skipped += 1
        elif n.op_type == 'MatMul':
            a_shape = shape_of(n.input[0])
            b_shape = shape_of(n.input[1])
            if a_shape and b_shape and all(d is not None for d in a_shape) and all(d is not None for d in b_shape):
                # a: [..., M, K], b: [..., K, N]
                k = a_shape[-1]
                m = int(np.prod(a_shape[:-1])) if a_shape[:-1] else 1
                n_dim = b_shape[-1] if b_shape[-1] else 0
                macs = m * n_dim * k
                total_macs += macs
                per_type['MatMul'] += macs
                # classifier head: second dim of output == 18710
                outs = shape_of(n.output[0])
                if outs and len(outs) >= 2 and outs[-1] == 18710:
                    head_macs = macs
                    head_name = n.name
            else:
                skipped += 1
        elif n.op_type == 'Gemm':
            a_shape = shape_of(n.input[0])
            b_shape = shape_of(n.input[1])
            if a_shape and b_shape and all(d is not None for d in a_shape) and all(d is not None for d in b_shape):
                macs = a_shape[0] * b_shape[1] * a_shape[1]
                total_macs += macs
                per_type['Gemm'] += macs
            else:
                skipped += 1

    params = sum(v * np.dtype(dtype).itemsize for v in init_shapes.values()) if False else init_bytes
    print(f'=== {label} ===')
    print(f'  params: {init_bytes/1e6:.2f} MB fp32 ({init_bytes/4/1e6:.2f}M weights)')
    print(f'  total MACs: {total_macs/1e6:.1f}M  (≈{total_macs*2/1e9:.2f} GFLOPs)')
    for t, macs in per_type.most_common():
        print(f'    {t:8s}: {macs/1e6:8.1f}M MACs ({macs/total_macs*100:4.1f}%)')
    if head_macs:
        print(f'  classifier head ({head_name}): {head_macs/1e6:.1f}M MACs ({head_macs/total_macs*100:.1f}%)')
    print('  top-5 convs:')
    for name, macs, oc, ic, kh, kw, oshape in sorted(conv_rows, key=lambda r: -r[1])[:5]:
        print(f'    {name:30s} {macs/1e6:8.1f}M  oc={oc:4d} ic={ic:4d} k={kh}x{kw} out={oshape}')
    if skipped:
        print(f'  (skipped {skipped} nodes with unknown shapes)')

import sys

if __name__ == '__main__':
    paths = sys.argv[1:] or [
        'app/src/main/assets/PP-OCRv6_small_rec_onnx/rec_w480.onnx',
        'models/archive/meiki.text.rec.v0.960x32.onnx',
    ]
    labels = sys.argv[1:] or ['PP-OCRv6 small rec @480px (60 ts)', 'meiki rec 960x32']
    for p, l in zip(paths, labels):
        run(p, l)
        print()
