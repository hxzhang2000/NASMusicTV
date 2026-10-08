# -*- coding: utf-8 -*-
"""把文档里所有 `Xxx.kt:NNN` / `Xxx.kt:NNN-MMM` 引用抽出来，打印对应源码的真实那一行。
用途：肉眼比对「文档行号 vs 源码行号」是否漂移。

用法：
    python logs_temp/verify_linerefs.py                # 全部
    python logs_temp/verify_linerefs.py AdvancedRenderers   # 只看某文件
"""
import io
import os
import re
import sys

sys.stdout.reconfigure(encoding="utf-8")

ROOT = "."
DOC = "docs/archive/visualizer-texture-upgrade-plan.md"

# 建 index: basename -> [fullpath...]
idx = {}
for dp, dn, fn in os.walk(os.path.join(ROOT, "app", "src")):
    for f in fn:
        if f.endswith(".kt"):
            idx.setdefault(f, []).append(os.path.join(dp, f))

cache = {}


def src_lines(path):
    if path not in cache:
        cache[path] = io.open(path, encoding="utf-8").read().split("\n")
    return cache[path]


doc = io.open(DOC, encoding="utf-8").read()
doclines = doc.split("\n")

# 版本表行区间（这些行是"自述修了什么"，会引用旧行号，跳过）
ver_spans = []
for m in re.finditer(r"^\| v1\.\d+ \|.*$", doc, re.M):
    ver_spans.append((m.start(), m.end()))


def in_ver(pos):
    return any(a <= pos < b for a, b in ver_spans)


# 行号引用：`File.kt:123` 或 `File.kt:123-456`（允许反引号包裹）
PAT = re.compile(r"([A-Za-z][A-Za-z0-9_]*\.kt):(\d+)(?:\s*[-–]\s*(\d+))?")

only = sys.argv[1] if len(sys.argv) > 1 else None

# 逐行扫描，记录 (行号, 列, 文件名, 起始, 结束)
records = []
pos = 0
for ln_no, ln in enumerate(doclines, 1):
    for m in PAT.finditer(ln):
        if in_ver(pos + m.start()):
            continue
        records.append((ln_no, m.group(1), int(m.group(2)),
                        int(m.group(3)) if m.group(3) else None, ln))
    pos += len(ln) + 1

missing_file = set()
n_checked = 0
for ln_no, fn, a, b, ln in records:
    if only and only not in fn:
        continue
    paths = idx.get(fn)
    if not paths:
        missing_file.add(fn)
        continue
    if len(paths) > 1:
        print("!! 文件重名 %s -> %s" % (fn, paths))
        continue
    lines = src_lines(paths[0])
    n_checked += 1
    hi = b if b else a
    def at(n):
        return lines[n - 1].strip() if 1 <= n <= len(lines) else "<<越界(文件仅 %d 行)>>" % len(lines)
    print("L%-5d %-26s :%-5d %s" % (ln_no, fn, a, at(a)[:110]))
    if b and b != a:
        print("      %-26s :%-5d %s" % ("", b, at(b)[:110]))

print()
print("== 共校验 %d 处行号引用；未找到的源文件：%s" % (n_checked, sorted(missing_file) or "无"))
