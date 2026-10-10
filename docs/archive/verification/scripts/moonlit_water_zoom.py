"""明月原型：水面区域并排放大对照（候选 vs 参考图）。

用法：python -X utf8 docs/archive/verification/scripts/moonlit_water_zoom.py <候选png> [输出名]
参考图固定为 output/moonlit_frames/ref_input_gold.png（缺失则报错）。
"""
import sys, os
import numpy as np
from PIL import Image

REF = 'output/moonlit_frames/ref_input_gold.png'
out_name = sys.argv[2] if len(sys.argv) > 2 else 'zoom_water'
cand = sys.argv[1]

def water(img, hz):
    """裁出水面（hz 为地平线占高比例），返回 PIL 图。"""
    W, H = img.size
    return img.crop((0, int(H * hz), W, H))

def scale_to(img, target_h):
    W, H = img.size
    return img.resize((int(W * target_h / H), target_h), Image.LANCZOS)

c = Image.open(cand).convert('RGB')
r = Image.open(REF).convert('RGB')
# 候选地平线 0.640（§3.1 HORIZON_K），参考图实测 0.55
cw, rw = water(c, 0.640), water(r, 0.55)
TARGET_H = 700
cw2, rw2 = scale_to(cw, TARGET_H), scale_to(rw, TARGET_H)
gap = 24
canvas = Image.new('RGB', (max(cw2.size[0], rw2.size[0]) + gap, TARGET_H * 2 + gap * 3), (20, 20, 20))
canvas.paste(rw2, (0, gap))
canvas.paste(cw2, (0, TARGET_H + gap * 2))
dst = f'output/moonlit_frames/{out_name}.png'
canvas.save(dst)
print(f'上=参考图水面  下=候选水面  候选={os.path.basename(cand)}')
print(f'✓ {dst}')
