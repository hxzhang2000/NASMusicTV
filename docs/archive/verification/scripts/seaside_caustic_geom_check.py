#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""5c 验证：焦散「连通胞壁网」(v3) vs「孤立直线短划」(v2)。

把 HTML 里 buildCausticNet() 的数学原样移植（含 hash32/hash2 的 JS 语义），
用 PIL 以 2x 超采样栅格化后降采样（模拟浏览器抗锯齿），1:1 目检：
  · v2 孤立短划  -> 预期读作「划痕」
  · v3 连通胞壁网 -> 预期读作「水面光网」（格 + 水从格中透过）

输出: output/caustic_v2_old.png / caustic_v3_net.png / caustic_cmp.png
"""
import math, os
from PIL import Image, ImageDraw

W, H = 1600, 900
SEA_BOTTOM_K = 0.620 + 0.20          # 与 HTML 一致: SHORE_K + 0.20
SS = 2                                # 超采样倍数

# ---- JS 语义的 hash32 / hash2（与 HTML 逐字对应）----
def imul(a, b):
    return (a * b) & 0xFFFFFFFF        # 取低 32 位
def to_i32(x):
    x &= 0xFFFFFFFF
    return x - 0x100000000 if x >= 0x80000000 else x
def u32(x):
    return x & 0xFFFFFFFF
def shr(x, n):                        # JS x >>> n（逻辑右移）
    return u32(x) >> n
def shl(x, n):                        # JS x << n
    return u32(to_i32(x) << n)
def add32(a, b):
    return u32(to_i32(a) + to_i32(b))
def hash32(x):
    x = u32(x)
    x = u32(x ^ 61) ^ shr(x, 16)
    x = add32(x, shl(x, 3))
    x = imul(x, 0x27d4eb2d)
    x = u32(x) ^ shr(x, 15)
    return x / 4294967296.0
def hash2(i, salt):
    return hash32(u32(imul(i + 1, 2654435761) ^ imul(salt + 7, 40503)))

# ---- 常量（与 HTML 一致）----
CNX, CNY = 20, 9
PAL_CAUSTIC = (234, 246, 255)

def build_caustic_net(W=W, H=H):
    seaH = H * SEA_BOTTOM_K
    nx = [0.0] * (CNY * CNX)
    ny = [0.0] * (CNY * CNX)
    # 去规整化（与 HTML buildCausticNet 逐行对应）：列/行各过一次低频平滑 warp
    # ⇒ 胞格宽窄不均；每行一个哈希横向错切 ⇒ 行与行的壁角度不同。
    wC = [(hash2(3100 + k, 301) - 0.5) * 2 for k in range(4)]
    wR = [(hash2(3200 + k, 303) - 0.5) * 2 for k in range(4)]
    def warp(arr, v):
        s = tot = 0.0
        amp = 1.0
        for o in range(4):
            x = v * (1 << o)
            i0 = math.floor(x)
            fr = x - i0
            u = fr * fr * (3 - 2 * fr)
            a = arr[o]
            b = arr[(o + 1) & 3]
            s += (a + (b - a) * u) * amp
            tot += amp
            amp *= 0.55
        return s / tot
    cw = W / CNX
    for cy in range(CNY):
        fy = (cy + 0.5) / CNY
        ry = fy + warp(wR, fy * 2.0) * 0.30 / CNY
        shear = (hash2(3300 + cy, 305) - 0.5) * 0.22
        for cx in range(CNX):
            i = cy * CNX + cx
            fx = (cx + 0.5) / CNX
            rx = fx + warp(wC, fx * 2.0) * 0.30 / CNX
            nx[i] = W * (rx + shear * (ry - 0.5) * 0.5) + (hash2(2000 + i, 201) - 0.5) * 0.30 * cw
            ny[i] = seaH * (ry + (hash2(2000 + i, 203) - 0.5) * 0.30 / CNY)
    edges = []
    for cy in range(CNY):
        for cx in range(CNX):
            i0 = cy * CNX + cx
            k2 = i0 * 2
            if cx < CNX - 1:
                edges.append(dict(a=i0, b=i0 + 1,
                                  bow=(hash2(k2, 205) - 0.5) * 2,
                                  ph=hash2(k2, 207) * 6.2832,
                                  lw=0.55 + 0.60 * hash2(k2, 209),
                                  tr=hash2(k2, 211)))
            if cy < CNY - 1:
                edges.append(dict(a=i0, b=i0 + CNX,
                                  bow=(hash2(k2 + 1, 205) - 0.5) * 2,
                                  ph=hash2(k2 + 1, 207) * 6.2832,
                                  lw=0.55 + 0.60 * hash2(k2 + 1, 209),
                                  tr=hash2(k2 + 1, 211)))
    return nx, ny, edges

def sea_bg(w, h):
    """近似海水底色（远海 #2E9AA8 -> 近海 #1E7A8C 的垂直渐变）"""
    img = Image.new("RGB", (w, h), (0x2E, 0x9A, 0xA8))
    d = ImageDraw.Draw(img)
    far, near = (0x2E, 0x9A, 0xA8), (0x1E, 0x7A, 0x8C)
    for y in range(h):
        f = y / max(1, h - 1)
        d.line([(0, y), (w, y)],
               fill=tuple(int(round(far[c] + (near[c] - far[c]) * f)) for c in range(3)))
    return img

def qpoints(p0, p1, p2, n=26):
    """二次贝塞尔采样"""
    out = []
    for i in range(n + 1):
        t = i / n
        u = 1 - t
        out.append((u * u * p0[0] + 2 * u * t * p1[0] + t * t * p2[0],
                    u * u * p0[1] + 2 * u * t * p1[1] + t * t * p2[1]))
    return out

def render_v3(W=W, H=H, t=0.0, a_base=0.075, energy=0.6, crop=None):
    nx, ny, edges = build_caustic_net(W, H)
    seaH = H * SEA_BOTTOM_K
    a_base = a_base * (0.30 + 0.70 * energy)
    cellW = W / CNX
    base = sea_bg(W * SS, H * SS)
    for tier in range(3):
        ov = Image.new("RGBA", base.size, (0, 0, 0, 0))
        d = ImageDraw.Draw(ov)
        lw = (1.15 if tier == 1 else (0.85 if tier == 2 else 0.6)) * SS
        alpha = int(round(255 * a_base * (0.62 if tier == 1 else (0.44 if tier == 2 else 0.28))))
        any_ = False
        for ed in edges:
            if int(ed["tr"] * 3) != tier:
                continue
            ax, ay = nx[ed["a"]], ny[ed["a"]]
            bx, by = nx[ed["b"]], ny[ed["b"]]
            mx, my = (ax + bx) * 0.5, (ay + by) * 0.5
            nxv, nyv = -(by - ay), (bx - ax)
            nl = math.hypot(nxv, nyv) or 1.0
            wob = ed["bow"] * (0.55 + 0.45 * math.sin(t * 0.00007 + ed["ph"])) * cellW * 0.30
            pts = qpoints((ax, ay), (mx + nxv / nl * wob, my + nyv / nl * wob), (bx, by))
            d.line([(x * SS, y * SS) for x, y in pts], fill=PAL_CAUSTIC + (alpha,),
                   width=max(1, int(round(lw))), joint="curve")
            any_ = True
        if any_:
            base = Image.alpha_composite(base.convert("RGBA"), ov).convert("RGB")
    if crop:
        base = base.crop((crop[0] * SS, crop[1] * SS, crop[2] * SS, crop[3] * SS))
    return base.resize((base.width // SS, base.height // SS), Image.LANCZOS)

def render_v2(W=W, H=H, t=0.0, a_base=0.075, energy=0.6, crop=None):
    """v2：12 条孤立直线短划（被 5c 废弃的那版），忠实复现以便对比"""
    seaH = H * SEA_BOTTOM_K
    a_base = a_base * (0.30 + 0.70 * energy)
    base = sea_bg(W * SS, H * SS)
    ov = Image.new("RGBA", base.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(ov)
    for i in range(12):
        idv = 700 + i
        y = seaH * (0.15 + 0.70 * hash2(idv, 63))
        x = W * hash2(idv, 65)
        ln = W * (0.03 + 0.05 * hash2(idv, 67))
        lw = (0.5 + 0.5 * hash2(idv, 71)) * SS
        al = int(round(255 * a_base * 0.55 * (0.30 + 0.70 * hash2(idv, 73))))
        d.line([(x * SS, y * SS), ((x + ln) * SS, (y + 5 * math.sin(t * 0.00009 + hash2(idv, 69) * 6.2832)) * SS)],
               fill=PAL_CAUSTIC + (al,), width=max(1, int(round(lw))))
    base = Image.alpha_composite(base.convert("RGBA"), ov).convert("RGB")
    if crop:
        base = base.crop((crop[0] * SS, crop[1] * SS, crop[2] * SS, crop[3] * SS))
    return base.resize((base.width // SS, base.height // SS), Image.LANCZOS)

if __name__ == "__main__":
    # 仓库根 = docs/archive/verification/scripts/ 上溯四级
    root = os.path.abspath(os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                      '..', '..', '..', '..'))
    out = os.path.join(root, "output")
    os.makedirs(out, exist_ok=True)
    # 焦散只画在海水区 (0 .. seaH)，以下都裁到该区域
    seaH = int(H * SEA_BOTTOM_K)
    crop = (0, 0, W, seaH)
    # ⛔ 诊断增益：真实 alpha 仅 0.03 量级，肉眼在静图上不可辨。
    #    本脚本只用来判**几何形态**（刻痕 vs 胞网），故放大到可见。
    #    ⛔ 该增益绝不写回 HTML —— 渲染强度仍由 CAUSTIC_A / energy 决定。
    GAIN = 12.0
    def boosted(fn, **kw):
        return fn(a_base=0.075 * GAIN, **kw)
    boosted(render_v2, crop=crop).save(os.path.join(out, "caustic_v2_old.png"))
    boosted(render_v3, crop=crop).save(os.path.join(out, "caustic_v3_net.png"))
    # 1:1 局部裁切（看单根格壁的形态：直硬线 or 弯弓）
    z = (200, 120, 200 + 560, 120 + 400)
    boosted(render_v2, crop=z).resize((1120, 800), Image.NEAREST).save(os.path.join(out, "caustic_v2_zoom.png"))
    boosted(render_v3, crop=z).resize((1120, 800), Image.NEAREST).save(os.path.join(out, "caustic_v3_zoom.png"))
    # 并排对比：左 v2 孤立短划（刻痕） / 右 v3 连通胞壁网
    a = boosted(render_v2, crop=crop).resize((800, int(800 * seaH / W)), Image.LANCZOS)
    b = boosted(render_v3, crop=crop).resize((800, int(800 * seaH / W)), Image.LANCZOS)
    cmp_img = Image.new("RGB", (1600, a.height), (0, 0, 0))
    cmp_img.paste(a, (0, 0)); cmp_img.paste(b, (800, 0))
    cmp_img.save(os.path.join(out, "caustic_cmp.png"))
    print("wrote %s  (GAIN=%.0f for geometry inspection only)" % (out, GAIN))
    for f in ("caustic_v2_old.png", "caustic_v3_net.png", "caustic_cmp.png", "caustic_v2_zoom.png", "caustic_v3_zoom.png"):
        p = os.path.join(out, f)
        print("  %-22s %6d bytes" % (f, os.path.getsize(p)))
