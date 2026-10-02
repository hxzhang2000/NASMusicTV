#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
渲染器每帧「绘制原语调用次数」估算（2026-09-29）

在上一步 renderer_cost_audit.py 的结构统计之上，加一层**循环次数解析**：
  - 从同文件里抓 `val NAME = <整数>` / `const val NAME = <整数>` 建符号表；
  - `ctx.quality.<prop>` 按 MEDIUM 档展开（barCount=64 / glowLayers=2 /
    maxParticles=150 / gridCols=24 / gridRows=14）；
  - 其余常见符号给保守上界（见 BOUNDS）；
  - 解析不出的一律标 `?`，**不计入估算**（宁可低估，不可高估）。

输出：每类一行 —— 已解析的「每帧绘制原语调用次数」下界 + 未解析循环数。
"""

import os
import re

SRC_DIRS = [
    r"D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/visualizer/renderers",
    r"D:/hxzhang/MyGithubSoftware/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/visualizer/photo",
]

PRIMITIVES = [
    "drawLine", "drawCircle", "drawRect", "drawRoundRect", "drawOval", "drawArc",
    "drawPath", "drawImage", "drawImageRect", "drawPoints", "drawText",
    "drawIntoCanvas", "drawRawPoints", "drawBitmap", "drawColor",
]

# 路径顶点写入 / 文本度量 —— 每次都是一次 JNI（与 draw 调用同量级），单独统计
PATH_OPS = [
    "moveTo", "lineTo", "cubicTo", "quadTo", "addOval", "addRect", "addRoundRect",
    "addPath", "arcTo", "relativeLineTo", "measureText", "getTextBounds", "rewind",
    "reset", "drawTextOnPath",
]

# MEDIUM 档（= 当前唯一可达档位，见 §2.x）
QUALITY_MEDIUM = {
    "barCount": 64, "glowLayers": 2, "maxParticles": 150,
    "gridCols": 24, "gridRows": 14,
}

# 保守上界（宁可低估）
BOUNDS = {
    "perCol": 14, "RINGS": 24, "SCANLINE_COUNT": 240, "RAYS": 12, "RING_STEPS": 24,
    "SEGMENTS": 14, "BEAMS": 6, "MAX_DEPTH": 8, "ARC_JAG": 3, "CP_N": 24,
    "STROKE_BUCKETS": 6, "TRAIL_SEGS": 8, "RIPPLE_MAX": 12, "NIGHT_STRIPS": 24,
    "MAX_FLIGHTS": 34, "ROUTE_CLASSES": 3, "LAND_STROKE_BUCKETS": 6,
    "COLS_MED": 28, "COLS_LOW": 20, "COLS_HIGH": 36, "STEPS": 24,
    "starCount": 110, "N_MAIN": 13, "satCount": 9, "gearCount": 22,
    "atomCount": 12, "rungs": 40, "elCount": 80, "segCount": 240,
    "triCount": 48, "cols": 28, "rows": 14, "count": 200,
    "cap": 150, "n": 64, "k": 64, "steps": 24, "STEPS_MAX": 24,
    "TRAIL": 8, "pulse": 1, "seg": 8, "planes": 8, "tickCount": 6,
}
BOUNDS["WorldCities.COUNT"] = 32
BOUNDS["p.count"] = 150
BOUNDS["planets.size"] = 10

# 局部量覆盖：脚本看不到 `val n = colY.size` 这类定义，按 MEDIUM 档实测值补
CLASS_SYMBOL_OVERRIDE = {
    "MatrixRainRenderer": {"n": 32, "cols": 32},      # colY.size = cols = 32（MEDIUM）
    "LiquidGridRenderer": {"cols": 24, "rows": 14},   # ctx.quality.gridCols/gridRows
}

ALLOC_PATTERNS = [
    (r"\bBitmap\.createBitmap\s*\(", "Bitmap.createBitmap"),
    (r"\bImageBitmap\s*\(", "ImageBitmap()"),
    (r"\bPath\s*\(\s*\)", "Path()"),
    (r"\bRect\s*\(", "Rect()"),
    (r"\bRoundRect\s*\(", "RoundRect()"),
    (r"\bPaint\s*\(\s*\)", "Paint()"),
    (r"\bAndroidPaint\s*\(\s*\)", "AndroidPaint()"),
    (r"\bBrush\.\w+", "Brush.*"),
    (r"\bBlurMaskFilter\s*\(", "BlurMaskFilter"),
    (r"\bRadialGradient\s*\(", "RadialGradient"),
    (r"\bLinearGradient\s*\(", "LinearGradient"),
    (r"\bSweepGradient\s*\(", "SweepGradient"),
    (r"\bIntArray\s*\(", "IntArray()"),
    (r"\bFloatArray\s*\(", "FloatArray()"),
    (r"\bgetPixel\s*\(", "getPixel"),
    (r"\bwithTransform\s*\(", "withTransform"),
    (r"\bclipPath\s*\(", "clipPath"),
    (r"\bsaveLayer\s*\(", "saveLayer"),
    (r"\"[^\"]*\$", "字符串模板"),
]

LIFECYCLE = {"onEnter", "onExit", "onViewAttached", "onViewDetached", "createView",
             "releaseBuffers", "ensureBuffers", "reset", "release",
             # §5.5 基类 `RendererFx` 的子类钩子（同样不在每帧路径上）
             "onEnterContent", "onExitContent"}


def strip_comments_and_strings(src):
    out = []
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == "/" and i + 1 < n and src[i + 1] == "/":
            j = src.find("\n", i)
            j = n if j < 0 else j
            out.append(" " * (j - i)); i = j
        elif c == "/" and i + 1 < n and src[i + 1] == "*":
            j = src.find("*/", i + 2)
            j = n if j < 0 else j + 2
            out.append("".join("\n" if src[k] == "\n" else " " for k in range(i, j))); i = j
        elif c == '"':
            if src.startswith('"""', i):
                j = src.find('"""', i + 3); j = n if j < 0 else j + 3
            else:
                j = i + 1
                while j < n and src[j] != '"':
                    if src[j] == "\\":
                        j += 1
                    j += 1
                j = min(j + 1, n)
            out.append("".join("\n" if src[k] == "\n" else " " for k in range(i, j))); i = j
        else:
            out.append(c); i += 1
    return "".join(out)


def find_matching_brace(text, open_pos):
    depth, i, n = 0, open_pos, len(text)
    while i < n:
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return n


def split_classes(text):
    out = []
    for m in re.finditer(r"^(?:internal\s+|private\s+|abstract\s+|open\s+|sealed\s+)*class\s+(\w+)", text, re.M):
        brace = text.find("{", m.end())
        if brace < 0:
            continue
        end = find_matching_brace(text, brace)
        out.append((m.group(1), m.start(), text[brace + 1:end]))
    return out


def match_paren(text, open_pos):
    """从 `open_pos`（必须是 `(`）出发，返回配对 `)` 的下标；找不到返回 -1。"""
    depth, i, n = 0, open_pos, len(text)
    while i < n:
        c = text[i]
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def split_functions(text):
    """返回 `name -> [body, ...]`（只收**有 `{}` 体**的函数）。

    ⛔ **单表达式函数必须跳过**（`fun f(...): T = expr`）。老实现用
    `text.find("{", m.end())` 直接找 `{` 当函数体，对单表达式函数会**一路吃到后面某个
    函数的 `{`**，把中间整段代码（含**其它函数的调用**）当成本函数的体：
      · 虚增计数；
      · 在调用图里造出**不存在的边** —— 实测 `OrbitalRingsRenderer.project`（`): Offset =`）
        被误判为**自调用**，加权口径下把该类的次数顶到饱和上限。
    老实现想用 `re.search(r"\\)\\s*=", seg)` 兜住，但**带显式返回类型**时 `)` 后面是
    `: Offset =`，`\\)\\s*=` **匹配不上** ⇒ 漏网（这就是本次修掉的 bug）。

    正确判据：**先匹配参数表的右括号，再看它之后先遇到 `{` 还是 `=`**。
    """
    funcs = {}
    for m in re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?(?:\w+(?:\.\w+)*\.)?(\w+)\s*\(", text):
        lp = m.end() - 1                       # 正则以 `\s*\(` 收尾 ⇒ m.end()-1 就是 `(`
        rp = match_paren(text, lp)
        if rp < 0:
            continue
        i, n = rp + 1, len(text)
        while i < n and text[i].isspace():
            i += 1
        if i < n and text[i] == ":":           # 跳过显式返回类型
            i += 1
            depth = 0
            while i < n:
                c = text[i]
                if c == "(":
                    depth += 1
                elif c == ")":
                    depth -= 1
                elif depth == 0 and c in "{=":
                    break
                i += 1
        while i < n and text[i].isspace():
            i += 1
        if i < n and text[i] == "=":
            continue                           # 单表达式函数 ⇒ 跳过（宁可低估）
        brace = text.find("{", i)
        if brace < 0:
            continue
        if "=" in text[i:brace]:               # 防御：仍像单表达式 ⇒ 跳过
            continue
        end = find_matching_brace(text, brace)
        funcs.setdefault(m.group(1), []).append(text[brace + 1:end])
    return funcs


def _selftest():
    """`split_functions` 的正/负向自证。返回失败条数。"""
    fails = []

    def check(name, cond, detail=""):
        print(("  PASS  " if cond else "  FAIL  ") + name + ("" if cond else "   " + detail))
        if not cond:
            fails.append(name)

    print("[selftest] split_functions 函数体切分")
    cases = {
        "block_with_type": "fun f(a: Int): Int { drawRect(1f) }",
        "block_no_type": "fun f() { drawCircle(1f) }",
        "block_unit": "fun f(): Unit { drawLine() }",
        "block_generic_ret": "fun f(): Map<String, List<Int>> { drawPath() }",
        "block_funtype_ret": "fun f(): () -> Unit { drawOval() }",
        "block_default_eq": "fun f(a: Int = 3) { drawArc() }",
        "block_default_lambda": "fun f(a: () -> Unit = {}) { drawImage() }",
        "block_ws_before_paren": "fun f (a: Int) { drawPoints() }",
    }
    for name, src in cases.items():
        f = split_functions(src)
        check(name, len(f) == 1 and len(f.get("f", [])) == 1, repr(f))

    # 负向：单表达式函数**必须不被收进来**，且不得吞掉后面函数的体
    tail = "\nfun h() { drawCircle(9f) }\n"
    expr_cases = {
        "expr_no_type": "fun g() = 1f" + tail,
        "expr_with_type": "fun g(): Float = 1f" + tail,
        "expr_with_generic": "fun g(): List<Int> = listOf()" + tail,
        "expr_lambda": "fun g(): () -> Unit = {}" + tail,
    }
    for name, src in expr_cases.items():
        f = split_functions(src)
        ok = ("g" not in f) and ("h" in f) and ("drawCircle" in f["h"][0])
        check(name, ok, repr(f))

    # ⭐ 负向自证：**老实现**在同一输入上必须出错（证明这条判据不是空转）
    def old_split_functions(text):
        funcs = {}
        for m in re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?(?:\w+(?:\.\w+)*\.)?(\w+)\s*\(", text):
            brace = text.find("{", m.end())
            if brace < 0:
                continue
            seg = text[m.end():brace]
            if re.search(r"\)\s*=", seg):
                continue
            end = find_matching_brace(text, brace)
            funcs.setdefault(m.group(1), []).append(text[brace + 1:end])
        return funcs

    bad = "fun g(): Float = 1f" + tail
    of = old_split_functions(bad)
    check("NEG 老实现会把单表达式函数体切错", ("g" in of) or ("drawCircle" in str(of)),
          "老实现竟然也切对了 —— 那么本修法没有证据支撑，须重新取证：" + repr(of))
    nf = split_functions(bad)
    check("NEG 新实现同输入下判对", ("g" not in nf) and ("drawCircle" in nf.get("h", [""])[0]),
          repr(nf))

    print("[selftest] 结果：%s（%d 条失败）" % ("全过" if not fails else "有失败", len(fails)))
    return len(fails)


def find_loops(body):
    loops = []
    for m in re.finditer(r"\b(for|while)\s*\(", body):
        p = m.end() - 1
        depth, i, n = 0, p, len(body)
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
        loops.append((body[m.start():i + 1], brace, end))
    return loops


def resolve_bound(header, symbols):
    """从循环头解析迭代次数；解析不出返回 None。"""
    h = " ".join(header.split())
    m = re.search(r"\buntil\s+(.+?)\)?$", h)
    expr = None
    if m:
        expr = m.group(1).strip().rstrip(")")
    else:
        m = re.search(r"\.\.\s*(.+?)\)?$", h)
        if m:
            expr = m.group(1).strip().rstrip(")")
        else:
            m = re.search(r"<\s*([\w.]+)", h)
            if m:
                expr = m.group(1)
    if not expr:
        return None
    # while (i < N) 形式：i 从 0 起 ⇒ N
    expr = expr.strip()
    if expr.endswith("indices"):
        return None
    # ctx.quality.X
    mq = re.fullmatch(r"ctx\.quality\.(\w+)", expr)
    if mq:
        return QUALITY_MEDIUM.get(mq.group(1))
    if expr in symbols:
        return symbols[expr]
    if expr in BOUNDS:
        return BOUNDS[expr]
    # 简单算术：把已知符号替换后 eval
    e = expr
    for k, v in sorted(BOUNDS.items(), key=lambda kv: -len(kv[0])):
        e = re.sub(r"\b" + re.escape(k) + r"\b", str(v), e)
    for k, v in symbols.items():
        e = re.sub(r"\b" + re.escape(k) + r"\b", str(v), e)
    e = e.replace("Math.PI.toFloat()", "3.14")
    if re.fullmatch(r"[0-9+\-*/ ().]+", e):
        try:
            val = eval(e, {"__builtins__": {}}, {})
            if isinstance(val, (int, float)) and 0 < val < 20000:
                return int(val)
        except Exception:
            return None
    return None


def main():
    rows = []
    for d in SRC_DIRS:
        for fn in sorted(os.listdir(d)):
            if not fn.endswith(".kt"):
                continue
            raw = open(os.path.join(d, fn), encoding="utf-8", errors="replace").read()
            text = strip_comments_and_strings(raw)
            symbols = {}
            for m in re.finditer(r"\bval\s+(\w+)\s*(?::\s*Int)?\s*=\s*(\d+)\b", text):
                symbols[m.group(1)] = int(m.group(2))
            for m in re.finditer(r"\bconst\s+val\s+(\w+)\s*(?::\s*Int)?\s*=\s*(\d+)\b", text):
                symbols[m.group(1)] = int(m.group(2))
            for cname, _, cbody in split_classes(text):
                funcs = split_functions(cbody)
                # ⛔ 入口必须同时接受旧的 `override fun DrawScope.draw(` 与基类迁移后的
                #    `override fun DrawScope.drawContent(` —— 只认 `draw` 的话，
                #    自 S1.5 起迁到 `RendererFx` 的子类会**整类从表里消失**
                #    （静默漏统计，比没有这张表更糟）。踩过：T3.6 时发现 9 套已迁移渲染器全丢。
                if "draw" in funcs:
                    entry = "draw"
                elif "drawContent" in funcs:
                    entry = "drawContent"
                else:
                    continue
                sym = dict(symbols)
                sym.update(CLASS_SYMBOL_OVERRIDE.get(cname, {}))
                reach, queue = set(), [entry]
                while queue:
                    f = queue.pop()
                    if f in reach:
                        continue
                    reach.add(f)
                    for b in funcs.get(f, []):
                        for m in re.finditer(r"\b(\w+)\s*\(", b):
                            if m.group(1) in funcs and m.group(1) not in reach:
                                queue.append(m.group(1))
                per_frame = [f for f in reach if f not in LIFECYCLE]

                prim_calls = 0
                unresolved = 0
                alloc_calls = 0
                alloc_unresolved = 0
                path_calls = 0
                path_unresolved = 0
                for f in per_frame:
                    for body in funcs[f]:
                        loops = find_loops(body)
                        bounds = []
                        for header, ls, le in loops:
                            b = resolve_bound(header, sym)
                            bounds.append((ls, le, b))

                        def tally(pos):
                            mult, ok = 1, True
                            for ls, le, b in bounds:
                                if ls < pos < le:
                                    if b is None:
                                        ok = False
                                    else:
                                        mult *= b
                            return mult, ok

                        for p in PRIMITIVES:
                            for m in re.finditer(r"(?:\.|\b)" + re.escape(p) + r"\s*\(", body):
                                mult, ok = tally(m.start())
                                if ok:
                                    prim_calls += mult
                                else:
                                    unresolved += 1
                        for p in PATH_OPS:
                            for m in re.finditer(r"\." + re.escape(p) + r"\s*\(", body):
                                mult, ok = tally(m.start())
                                if ok:
                                    path_calls += mult
                                else:
                                    path_unresolved += 1
                        for pat, _kind in ALLOC_PATTERNS:
                            for m in re.finditer(pat, body):
                                mult, ok = tally(m.start())
                                if ok:
                                    alloc_calls += mult
                                else:
                                    alloc_unresolved += 1
                rows.append((cname, fn, prim_calls, unresolved, path_calls,
                             path_unresolved, alloc_calls, alloc_unresolved))

    rows.sort(key=lambda r: -(r[2] + r[4]))
    print("# 每帧绘制原语 / 路径顶点写入次数估算（MEDIUM 档 · 自动生成）\n")
    print("> 脚本 `logs_temp/renderer_loop_estimate.py`。"
          "口径：`draw()` 类内调用图可达函数中的调用点 × 可解析的循环次数；"
          "**解析不出循环次数的调用点不计入（宁可低估）**，另列「未解析」列。"
          "「路径/文本」列 = `moveTo/lineTo/addOval/measureText/...` —— 每次同样是一次 JNI。\n")
    print("| 类 | 文件 | 绘制原语/帧 | 未解析 | 路径·文本/帧 | 未解析 | 分配/帧 | 未解析 |")
    print("|---|---|---:|---:|---:|---:|---:|---:|")
    for c, f, p, u, pa, pau, a, au in rows:
        print(f"| `{c}` | {f} | {p} | {u} | {pa} | {pau} | {a} | {au} |")
    print("\n**合计**：绘制原语 %d 次/帧（未解析 %d 处）；路径·文本 %d 次/帧（未解析 %d 处）；"
          "分配 %d 次/帧（未解析 %d 处）。"
          % (sum(r[2] for r in rows), sum(r[3] for r in rows),
             sum(r[4] for r in rows), sum(r[5] for r in rows),
             sum(r[6] for r in rows), sum(r[7] for r in rows)))


if __name__ == "__main__":
    import sys

    if "--selftest" in sys.argv:
        sys.exit(1 if _selftest() else 0)
    main()
