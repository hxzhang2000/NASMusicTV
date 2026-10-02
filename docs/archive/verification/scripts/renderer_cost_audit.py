#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
渲染器每帧成本审计（2026-09-29）

目的：量出「每套效果在 draw() 被调用一次时，会发出多少条绘制原语 / 分配多少对象」，
      而不是靠读代码猜。

方法：
  1. 把每个 .kt 文件切成 class 块（支持 `class X`, `internal class X`, `abstract class X`）；
  2. 在每个 class 内切出函数（按 `fun name(` 定位 + 花括号配平）；
  3. 从 draw 入口（`override fun DrawScope.draw(` 或 `override fun draw(`）做**类内调用图 BFS**，
     把所有可达函数收进来（含 `onEnter` 不算 —— 它不在每帧路径上，单独标注）；
  4. 在可达代码里统计：
       - 绘制原语调用点（drawLine / drawCircle / ... / nativeCanvas.drawXxx）
       - 每条原语是否位于 `for` / `while` 循环体内，以及循环头文本
       - 每帧分配嫌疑（Bitmap.createBitmap / Path() / Rect( / Brush.xxx / Paint() / IntArray( ...）
  5. 输出 markdown 表 + 明细，落到 stdout。

用法：
  "C:/Users/hxzha/.workbuddy-ai/binaries/python/versions/3.13.12/python.exe" \
      logs_temp/renderer_cost_audit.py > logs_temp/renderer_cost_audit.md
"""

import os
import re
import sys

SRC_DIRS = [
    r"D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/visualizer/renderers",
    r"D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/visualizer/photo",
]

# 绘制原语（含 nativeCanvas / android.graphics.Canvas 上的方法）
PRIMITIVES = [
    "drawLine", "drawCircle", "drawRect", "drawRoundRect", "drawOval", "drawArc",
    "drawPath", "drawImage", "drawImageRect", "drawPoints", "drawText",
    "drawContext", "drawIntoCanvas", "drawRawPoints", "drawBitmap", "drawColor",
]

# 每帧分配嫌疑
ALLOC_PATTERNS = [
    (r"\bBitmap\.createBitmap\s*\(", "Bitmap.createBitmap"),
    (r"\bImageBitmap\s*\(", "ImageBitmap()"),
    (r"\bPath\s*\(\s*\)", "Path()"),
    (r"\bRect\s*\(", "Rect() [data class 必分配]"),
    (r"\bRoundRect\s*\(", "RoundRect() [必分配]"),
    (r"\bPaint\s*\(\s*\)", "Paint()"),
    (r"\bAndroidPaint\s*\(\s*\)", "AndroidPaint()"),
    (r"\bBrush\.\w+", "Brush.* [内部建 List]"),
    (r"\bBlurMaskFilter\s*\(", "BlurMaskFilter"),
    (r"\bRadialGradient\s*\(", "RadialGradient"),
    (r"\bLinearGradient\s*\(", "LinearGradient"),
    (r"\bSweepGradient\s*\(", "SweepGradient"),
    (r"\bIntArray\s*\(", "IntArray()"),
    (r"\bFloatArray\s*\(", "FloatArray()"),
    (r"\bArray\s*<", "Array<>"),
    (r"\bmutableListOf\s*\(", "mutableListOf"),
    (r"\bArrayList\s*<", "ArrayList<>"),
    (r"\bgetPixel\s*\(", "getPixel [逐像素 JNI]"),
    (r"\bgetPixels\s*\(", "getPixels"),
    (r"\bwithTransform\s*\(", "withTransform"),
    (r"\bclipPath\s*\(", "clipPath"),
    (r"\bsaveLayer\s*\(", "saveLayer"),
    (r"\bOffset\s*\(", "Offset()（value class 不分配）"),
    (r"\bSize\s*\(", "Size()（value class 不分配）"),
    (r"\bColor\s*\(", "Color()（value class 不分配）"),
]

# 非每帧路径（进入/退出/配置变化）—— 单独标注，不计入每帧
LIFECYCLE_FUNCS = {"onEnter", "onExit", "onViewAttached", "onViewDetached",
                   "createView", "releaseBuffers", "ensureBuffers", "reset",
                   # §5.5 基类 `RendererFx` 的子类钩子（同样不在每帧路径上）
                   "onEnterContent", "onExitContent"}


def strip_comments_and_strings(src: str) -> str:
    """把注释与字符串字面量替换成等长空白，保持行号与花括号配平不变。"""
    out = []
    i = 0
    n = len(src)
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            j = src.find("\n", i)
            if j < 0:
                j = n
            out.append(" " * (j - i))
            i = j
        elif c == "/" and i + 1 < n and src[i + 1] == "*":
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            for k in range(i, j):
                out.append("\n" if src[k] == "\n" else " ")
            i = j
        elif c == '"':
            if src.startswith('"""', i):
                j = src.find('"""', i + 3)
                j = n if j < 0 else j + 3
            else:
                j = i + 1
                while j < n and src[j] != '"':
                    if src[j] == "\\":
                        j += 1
                    j += 1
                j = min(j + 1, n)
            for k in range(i, j):
                out.append("\n" if src[k] == "\n" else " ")
            i = j
        else:
            out.append(c)
            i += 1
    return "".join(out)


def line_of(text: str, pos: int) -> int:
    return text.count("\n", 0, pos) + 1


def find_matching_brace(text: str, open_pos: int) -> int:
    depth = 0
    i = open_pos
    n = len(text)
    while i < n:
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return n


def split_classes(text: str):
    """返回 [(class_name, start_pos, end_pos, body)]"""
    out = []
    for m in re.finditer(r"^(?:internal\s+|private\s+|abstract\s+|open\s+|sealed\s+)*class\s+(\w+)",
                         text, re.M):
        name = m.group(1)
        brace = text.find("{", m.end())
        if brace < 0:
            continue
        end = find_matching_brace(text, brace)
        out.append((name, m.start(), end, text[brace + 1:end]))
    return out


def split_functions(text: str, base_pos: int):
    """返回 {func_name: (body, abs_start_line)}"""
    funcs = {}
    for m in re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?(?:\w+(?:\.\w+)*\.)?(\w+)\s*\(", text):
        name = m.group(1)
        brace = text.find("{", m.end())
        if brace < 0:
            continue
        # 排除表达式体函数 / 抽象声明（在 { 之前先遇到 '=' 或 ')' 结尾）
        seg = text[m.end():brace]
        if "=" in seg and "->" not in seg.split("=")[0]:
            # 可能是 `= expr` 表达式体；但如果中间有 ')' 才算，简化处理：跳过
            if re.search(r"\)\s*=", seg):
                continue
        end = find_matching_brace(text, brace)
        body = text[brace + 1:end]
        funcs.setdefault(name, []).append((body, line_of(text, base_pos + brace)))
    return funcs


def find_loops(body: str):
    """返回 [(loop_header, start, end)] —— 用花括号配平找循环体范围"""
    loops = []
    for m in re.finditer(r"\b(for|while)\s*\(", body):
        # 找循环的 `{`
        p = m.end() - 1
        depth = 0
        i = p
        n = len(body)
        while i < n:
            if body[i] == "(":
                depth += 1
            elif body[i] == ")":
                depth -= 1
                if depth == 0:
                    break
            i += 1
        brace = body.find("{", i)
        if brace < 0:
            continue
        end = find_matching_brace(body, brace)
        header = " ".join(body[m.start():i + 1].split())
        loops.append((header, brace, end))
    return loops


def analyse_class(cname, body, abs_start_line):
    funcs = split_functions(body, 0)
    if not funcs:
        return None
    entry = None
    # ⛔ 入口同时接受 `draw` 与基类迁移后的 `drawContent`（只认 `draw` 会让
    #    自 S1.5 起迁到 `RendererFx` 的子类整类消失 ⇒ 静默漏统计）
    for cand in ("draw", "drawContent"):
        if cand in funcs:
            entry = cand
            break
    if entry is None:
        return None

    # 类内调用图 BFS（从 draw / drawContent 可达）
    reachable = set()
    queue = [entry]
    while queue:
        fn = queue.pop()
        if fn in reachable:
            continue
        reachable.add(fn)
        for body_text, _ in funcs.get(fn, []):
            for m in re.finditer(r"\b(\w+)\s*\(", body_text):
                callee = m.group(1)
                if callee in funcs and callee not in reachable:
                    queue.append(callee)

    per_frame = sorted(f for f in reachable if f not in LIFECYCLE_FUNCS)
    lifecycle = sorted(f for f in reachable if f in LIFECYCLE_FUNCS)

    stats = {
        "primitives": [],      # (func, line, prim, in_loop_header_or_None)
        "allocs": [],          # (func, line, kind)
        "loops": [],           # (func, line, header)
    }

    for fn in per_frame:
        for body_text, start_line in funcs[fn]:
            loops = find_loops(body_text)

            for p in PRIMITIVES:
                for m in re.finditer(r"(?:\.|\b)" + re.escape(p) + r"\s*\(", body_text):
                    pos = m.start()
                    in_loop = None
                    for header, ls, le in loops:
                        if ls < pos < le:
                            in_loop = header
                            break
                    stats["primitives"].append(
                        (fn, start_line + body_text.count("\n", 0, pos), p, in_loop))

            for pat, kind in ALLOC_PATTERNS:
                for m in re.finditer(pat, body_text):
                    pos = m.start()
                    in_loop = None
                    for header, ls, le in loops:
                        if ls < pos < le:
                            in_loop = header
                            break
                    stats["allocs"].append(
                        (fn, start_line + body_text.count("\n", 0, pos), kind, in_loop))

            for header, ls, le in loops:
                stats["loops"].append((fn, start_line + body_text.count("\n", 0, ls), header))

    return {
        "class": cname,
        "entry_line": funcs[entry][0][1],
        "per_frame_funcs": per_frame,
        "lifecycle_funcs": lifecycle,
        "stats": stats,
    }


def main():
    results = []
    for d in SRC_DIRS:
        if not os.path.isdir(d):
            continue
        for fn in sorted(os.listdir(d)):
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(d, fn)
            raw = open(path, "r", encoding="utf-8", errors="replace").read()
            text = strip_comments_and_strings(raw)
            for cname, s, e, body in split_classes(text):
                r = analyse_class(cname, body, s)
                if r:
                    r["file"] = fn
                    results.append(r)

    print("# 渲染器每帧成本审计（自动生成）\n")
    print("> 生成脚本 `logs_temp/renderer_cost_audit.py`。"
          "统计口径：从 `draw()` 出发、类内调用图可达的全部函数；"
          "`onEnter`/`onExit` 等生命周期函数不计入每帧。\n")
    print(f"共扫描到 **{len(results)}** 个带 `draw()` 的类。\n")

    print("## 总表\n")
    print("| 类 | 文件 | 原语调用点 | 其中在循环内 | 循环数 | 分配嫌疑 | 分配在循环内 | 每帧可达函数 |")
    print("|---|---|---:|---:|---:|---:|---:|---|")
    for r in results:
        prims = r["stats"]["primitives"]
        allocs = r["stats"]["allocs"]
        loops = r["stats"]["loops"]
        in_loop = sum(1 for p in prims if p[3])
        alloc_loop = sum(1 for a in allocs if a[3])
        print(f"| `{r['class']}` | {r['file']} | {len(prims)} | {in_loop} | {len(loops)} "
              f"| {len(allocs)} | {alloc_loop} | {len(r['per_frame_funcs'])} |")

    print("\n\n## 明细（按类）\n")
    for r in results:
        print(f"### `{r['class']}`（{r['file']}）\n")
        print(f"- 每帧可达：{', '.join('`'+f+'`' for f in r['per_frame_funcs'])}")
        if r["lifecycle_funcs"]:
            print(f"- 生命周期（非每帧）：{', '.join('`'+f+'`' for f in r['lifecycle_funcs'])}")
        loops = r["stats"]["loops"]
        if loops:
            print("\n**循环：**\n")
            for fn, ln, header in loops:
                print(f"- `{r['file']}:{ln}` `{fn}` → `{header}`")
        prims = r["stats"]["primitives"]
        if prims:
            print("\n**绘制原语：**\n")
            for fn, ln, p, in_loop in prims:
                tag = f" ⟵ 循环内 `{in_loop}`" if in_loop else ""
                print(f"- `{r['file']}:{ln}` `{p}`（{fn}）{tag}")
        allocs = r["stats"]["allocs"]
        if allocs:
            print("\n**分配嫌疑：**\n")
            for fn, ln, kind, in_loop in allocs:
                tag = f" ⟵ 循环内 `{in_loop}`" if in_loop else ""
                print(f"- `{r['file']}:{ln}` `{kind}`（{fn}）{tag}")
        print()


if __name__ == "__main__":
    main()
