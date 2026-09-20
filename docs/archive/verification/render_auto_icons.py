"""渲染 4 个 Android Auto tab 图标的矢量路径，用于目视核对形状。

只支持本批图标用到的绝对指令 M/L/Z（全部为多边形），够用即可。
高倍绘制后 LANCZOS 缩小，得到抗锯齿边缘；白图标画在深色底上（模拟车机深色界面）。
"""
import re
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageDraw

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
RES = Path("app/src/main/res/drawable")
BG = (24, 26, 32)

ICONS = [
    ("ic_auto_queue", "当前播放"),
    ("ic_auto_download", "离线下载"),
    ("ic_auto_favorite", "收藏"),
    ("ic_auto_playlist", "歌单"),
]

TOKEN = re.compile(r"[MLZmlz]|-?\d+(?:\.\d+)?")


def subpaths(path_data):
    """把 pathData 解析成若干多边形点列。"""
    tokens = TOKEN.findall(path_data)
    polys, cur = [], []
    i = 0
    while i < len(tokens):
        cmd = tokens[i]
        if cmd in ("M", "L"):
            cur.append((float(tokens[i + 1]), float(tokens[i + 2])))
            i += 3
        elif cmd in ("m", "l"):
            x0, y0 = cur[-1]
            cur.append((x0 + float(tokens[i + 1]), y0 + float(tokens[i + 2])))
            i += 3
        elif cmd in ("Z", "z"):
            if cur:
                polys.append(cur)
                cur = []
            i += 1
        else:
            raise ValueError(f"unsupported token {cmd!r}")
    if cur:
        polys.append(cur)
    return polys


def render(name, size=240, scale=8):
    root = ET.parse(RES / f"{name}.xml").getroot()
    vw = float(root.get(ANDROID_NS + "viewportWidth"))
    vh = float(root.get(ANDROID_NS + "viewportHeight"))

    hi = Image.new("RGB", (size * scale, size * scale), BG)
    draw = ImageDraw.Draw(hi)

    for path in root.findall("path"):
        fill = path.get(ANDROID_NS + "fillColor", "#FFFFFFFF")
        rgb = tuple(int(fill[i:i + 2], 16) for i in (3, 5, 7))
        for poly in subpaths(path.get(ANDROID_NS + "pathData")):
            pts = [(x / vw * size * scale, y / vh * size * scale) for x, y in poly]
            draw.polygon(pts, fill=rgb)

    return hi.resize((size, size), Image.LANCZOS)


def main():
    sheet = Image.new("RGB", (240 * len(ICONS), 240), BG)
    for idx, (name, label) in enumerate(ICONS):
        sheet.paste(render(name), (idx * 240, 0))
        print(f"{name:20s} {label}")
    out = Path("output/auto-tab-icons-preview.png")
    out.parent.mkdir(exist_ok=True)
    sheet.save(out)
    print("saved:", out)


if __name__ == "__main__":
    main()
