#!/usr/bin/env python3
# 明月原型「像不像参考图」的**客观判读闸门**。
#
# 为什么需要：所有者第六轮的三句话（"倒影不像"/"亮光要更亮"）全是观感词，
# 靠肉眼看截图会来回摇摆（同一张图我连着判出"太稀"和"糊成一坨"两种结论）。
# 这里把观感翻成**参考图自己量出来的数**，每轮跑一次就能判断有没有真的靠近。
#
# 用法（仓库根）：
#   python -X utf8 docs/archive/verification/scripts/moonlit_ref_match_check.py <candidate.png>
# 参考图不进仓库（带视觉中国水印），只放在 gitignore 的 output/ 下：
#   output/moonlit_frames/ref_input_gold.png   —— 缺失时打印 SKIP 并退出 0
#
# ⚠️ 几何**不从截图里猜**：月盘是画面里唯一"又亮又连续"的东西，但**晕和云也亮**，
#   用「最亮 1% 的包围盒」量原型会把 R 量小 3 倍（实测 R/H 0.053 vs 真值 0.150），
#   用「最长亮弦」同样失败（盘内亮度只有 186，够不上阈值 ⇒ 只量到过曝芯）。
#   所以：候选图的几何**回读 HTML 常量**（§3.1 那一组 K 值），参考图的几何一次性实测后写死。
#
# 所有量都以**月盘半径 R** 和**地平线以下深度比例 t** 为尺度，⛔ 不用绝对像素：
# 参考图是 4:3、原型是 16:9，只有归到 R 才能对表。
import os
import re
import sys

import numpy as np
from PIL import Image

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..', '..'))
REF = os.path.join(ROOT, 'output', 'moonlit_frames', 'ref_input_gold.png')
HTML = os.path.join(ROOT, 'docs', 'moonlit-preview.html')
# 参考图实测（PIL，2026-10-08）：月盘 >205 亮区包围盒 x 1147..2000 / y 227..1065，画面 2500x1875
REF_GEOM = dict(cxk=0.6294, cyk=0.3445, rk=0.2275, hzk=0.55)   # rk 占 H（参考图近 4:3，minDim=H）


def load(p):
    return np.asarray(Image.open(p).convert('RGB')).astype(int)


def html_consts():
    """回读 §3.1 的 K 值，⛔ 不要抄第二份（抄了就会和代码各说各话）。"""
    with open(HTML, encoding='utf-8') as f:
        src = f.read()
    out = {}
    for k in ('HORIZON_K', 'MOON_CX_K', 'MOON_CY_K', 'MOON_R_K'):
        m = re.search(r'^const ' + k + r'\s*=\s*([0-9.]+)', src, re.M)
        if not m:
            raise SystemExit(f'HTML 里找不到 const {k}')
        out[k] = float(m.group(1))
    return out


def stats(arr, cx, cy, R, hz, tag):
    H, W, _ = arr.shape
    lum = arr.mean(2)
    sea = H - hz
    lo, hi = max(0, int(cx - 3.6 * R)), min(W, int(cx + 3.6 * R))
    y0 = hz + int(sea * 0.10)
    px = arr[y0:hz + sea, lo:hi].reshape(-1, 3)
    v = lum[y0:hz + sea, lo:hi].reshape(-1)
    yy, xx = np.mgrid[0:H, 0:W]
    inside = (yy - cy) ** 2 + (xx - cx) ** 2 <= (0.72 * R) ** 2
    dp = arr[inside]
    out = {'R/H': round(R / H, 3), '盘内': dp.mean(0).round(1), '盘p95': round(float(np.percentile(dp.mean(1), 95)), 1)}
    for frac in (0.02, 0.20):
        k = max(1, int(v.size * frac))
        out[f'水{int(frac*100)}%'] = px[np.argsort(v)[-k:]].mean(0).round(1)
    rows = []
    for t in (0.10, 0.25, 0.45, 0.65, 0.85, 0.98):
        y = hz + int(sea * t)
        idx = np.where(lum[y, lo:hi] > 60)[0]
        rows.append('  --  ' if len(idx) == 0 else f'{(idx.max()-idx.min())/R:.2f}')
    out['宽/R'] = rows
    print(f'[{tag}] R/H={out["R/H"]}  盘内={out["盘内"]} p95={out["盘p95"]}  '
          f'水面2%={out["水2%"]}  水面20%={out["水20%"]}  点亮宽度/R(t=.10/.25/.45/.65/.85/.98)={rows}')
    return out


def main():
    if not os.path.exists(REF):
        print('SKIP: 参考图缺失（output/moonlit_frames/ref_input_gold.png，水印图不入库）')
        return 0
    cand = sys.argv[1]
    ref = load(REF)
    h, w, _ = ref.shape
    a = stats(ref, int(w * REF_GEOM['cxk']), int(h * REF_GEOM['cyk']),
              int(h * REF_GEOM['rk']), int(h * REF_GEOM['hzk']), 'REF ')
    mine = load(cand)
    H, W, _ = mine.shape
    K = html_consts()
    min_dim = min(H, W)
    b = stats(mine, int(W * K['MOON_CX_K']), int(H * K['MOON_CY_K']),
              int(min_dim * K['MOON_R_K']), int(H * K['HORIZON_K']), 'MINE')
    print('--- 判读（目标：盘内 R≈244 / 水面 20% 档 R≥215 / 点亮宽度逐行不为空且随 t 递增）---')
    print(f"  月盘:  mine={b['盘内']} p95={b['盘p95']}   ref={a['盘内']} p95={a['盘p95']}")
    for k in ('水2%', '水20%'):
        print(f'  {k}: mine={b[k]}  ref={a[k]}  Δ={(b[k]-a[k]).round(1)}')
    print(f"  点亮宽度/R: mine={b['宽/R']}  ref={a['宽/R']}")
    return 0


if __name__ == '__main__':
    sys.exit(main())
