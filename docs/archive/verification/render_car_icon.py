"""渲染 `ic_car_attribution.xml`（阶段 4.1 提供方图标），用于目视核对形状与放大推导。

与 `render_auto_icons.py` 的区别：
- 支持 `Q`（二次贝塞尔）—— 品牌标记的两道声波是 Q 曲线，不是纯多边形
- 支持 `<group>` 的 scale/translate 变换（本项目用 group 做等比放大而非重画路径）
- 顺带打印变换前后的包围盒，用于**数值**校验推导（预期 x∈[12,96] y∈[19,89]，即水平垂直都居中）

渲染策略：高倍绘制 + LANCZOS 缩小得到抗锯齿边缘；白图标画在深色底上（模拟车机深色界面）。
"""

import re
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageDraw

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
RES = Path("app/src/main/res/drawable")
BG = (24, 26, 32)

TARGET = "ic_car_attribution"
Q_STEPS = 48

TOKEN = re.compile(r"[MLQZmlqz]|-?\d+(?:\.\d+)?")
ARG_COUNT = {"M": 2, "L": 2, "Q": 4, "Z": 0}


def subpaths(path_data):
    """把 pathData 解析成若干点列（Q 曲线按 Q_STEPS 段展平成折线）。"""
    tokens = TOKEN.findall(path_data)
    polys, cur = [], []
    i = 0
    while i < len(tokens):
        cmd = tokens[i]
        upper = cmd.upper()
        n = ARG_COUNT[upper]
        args = [float(t) for t in tokens[i + 1:i + 1 + n]]
        i += 1 + n

        if upper == "M":
            if cur:
                polys.append(cur)
            cur = [(args[0], args[1])]
        elif upper == "L":
            x0, y0 = cur[-1]
            cur.append((x0 + args[0], y0 + args[1]) if cmd.islower() else (args[0], args[1]))
        elif upper == "Q":
            x0, y0 = cur[-1]
            if cmd.islower():
                cx, cy = x0 + args[0], y0 + args[1]
                ex, ey = x0 + args[2], y0 + args[3]
            else:
                cx, cy, ex, ey = args
            for s in range(1, Q_STEPS + 1):
                t = s / Q_STEPS
                mt = 1 - t
                # 二次贝塞尔：B(t) = (1-t)²P0 + 2(1-t)t·C + t²P1
                cur.append((
                    mt * mt * x0 + 2 * mt * t * cx + t * t * ex,
                    mt * mt * y0 + 2 * mt * t * cy + t * t * ey,
                ))
        elif upper == "Z":
            if cur:
                polys.append(cur)
                cur = []
        else:
            raise ValueError(f"unsupported token {cmd!r}")
    if cur:
        polys.append(cur)
    return polys


def parse_group(root):
    """返回 (scaleX, scaleY, translateX, translateY)。pivot 未设置即 0,0。"""
    g = root.find("group")
    if g is None:
        return 1.0, 1.0, 0.0, 0.0

    def f(attr):
        v = g.get(ANDROID_NS + attr)
        return float(v) if v is not None else None

    return (
        f("scaleX") or 1.0,
        f("scaleY") or 1.0,
        f("translateX") or 0.0,
        f("translateY") or 0.0,
    )


def render(with_group, size=240, scale=8):
    root = ET.parse(RES / f"{TARGET}.xml").getroot()
    vw = float(root.get(ANDROID_NS + "viewportWidth"))
    vh = float(root.get(ANDROID_NS + "viewportHeight"))
    sx, sy, tx, ty = parse_group(root) if with_group else (1.0, 1.0, 0.0, 0.0)

    hi = Image.new("RGB", (size * scale, size * scale), BG)
    draw = ImageDraw.Draw(hi)
    xs, ys = [], []

    group = root.find("group")
    # 注意：path 都嵌在 <group> 里，所以 raw 情形必须用 iter("path") 才能取到
    paths = group.findall("path") if (with_group and group is not None) else list(root.iter("path"))
    for path in paths:
        fill = path.get(ANDROID_NS + "fillColor", "#FFFFFFFF")
        rgb = tuple(int(fill[i:i + 2], 16) for i in (3, 5, 7))
        for poly in subpaths(path.get(ANDROID_NS + "pathData")):
            # pivot 为 0,0 时 group 变换等价于 (x,y) -> (s·x+tx, s·y+ty)
            pts = [(x * sx + tx, y * sy + ty) for x, y in poly]
            xs += [p[0] for p in pts]
            ys += [p[1] for p in pts]
            draw.polygon([(x / vw * size * scale, y / vh * size * scale) for x, y in pts], fill=rgb)

    print(f"  with_group={with_group}: bbox x∈[{min(xs):.2f},{max(xs):.2f}] y∈[{min(ys):.2f},{max(ys):.2f}]")
    return hi.resize((size, size), Image.LANCZOS)


def main():
    print(f"{TARGET}（视口 108×108）包围盒：")
    before = render(False)
    after = render(True)

    pad = 24
    sheet = Image.new("RGB", (240 * 2 + pad, 240 + 40), BG)
    sheet.paste(before, (0, 40))
    sheet.paste(after, (240 + pad, 40))

    d = ImageDraw.Draw(sheet)
    d.text((8, 12), "RAW (no group transform)", fill=(200, 200, 200))
    sx, sy, tx, ty = parse_group(ET.parse(RES / f"{TARGET}.xml").getroot())
    d.text(
        (240 + pad + 8, 12),
        f"WITH group scale={sx:g} translate=({tx:g},{ty:g})",
        fill=(200, 200, 200),
    )
    # 用品牌青绿画一条线，说明两者形状一致、只是缩放不同
    d.line([(0, 32), (240 * 2 + pad, 32)], fill=(45, 212, 191))

    out = Path("output/car-attribution-preview.png")
    out.parent.mkdir(exist_ok=True)
    sheet.save(out)
    print("saved:", out)


if __name__ == "__main__":
    main()
