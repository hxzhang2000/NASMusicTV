#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 DemucsSeparator.kt 抽取 LinearResampler / 字节转换函数，生成独立 harness 文件。

按标记定位而非硬编码行号 —— 源码一改行号就漂移，手抄会"测试与源码分叉"。

用法（在 logs_temp/verify_resampler/ 下执行）：
    python extract.py
"""
import io
import os

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.normpath(os.path.join(
    HERE, "..", "..", "app", "src", "main", "java", "com", "nasmusic", "tv", "player", "DemucsSeparator.kt"))


def read_src():
    with io.open(SRC, encoding="utf-8") as f:
        return f.read().split("\n")


def find_kdoc_start(lines, marker):
    """从含 marker 的行往上找最近的 '/**'"""
    i = next(idx for idx, l in enumerate(lines) if marker in l)
    while not lines[i].strip().startswith("/**"):
        i -= 1
    return i


def find_class_end(lines, decl_marker):
    """从 class 声明行起，找第一个恰为 '    }' 的行"""
    i = next(idx for idx, l in enumerate(lines) if decl_marker in l)
    for j in range(i + 1, len(lines)):
        if lines[j] == "    }":
            return j
    raise RuntimeError("class end not found for " + decl_marker)


def dedent(block):
    return "\n".join(l[4:] if l.startswith("    ") else l for l in block)


def extract_function(lines, decl_marker):
    """抽取一个 4 空格缩进的成员函数（到与之匹配的 '    }'）"""
    i = next(idx for idx, l in enumerate(lines) if decl_marker in l)
    for j in range(i + 1, len(lines)):
        if lines[j] == "    }":
            return lines[i:j + 1]
    raise RuntimeError("function end not found for " + decl_marker)


def main():
    lines = read_src()

    # ---- 1) Resampler.kt：KDoc + class LinearResampler ----
    start = find_kdoc_start(lines, "流式线性插值重采样器")
    end = find_class_end(lines, "class LinearResampler(")
    resampler = dedent(lines[start:end + 1])
    resampler = resampler.replace("internal class LinearResampler(", "class LinearResampler(")
    with io.open(os.path.join(HERE, "Resampler.kt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("// 由 extract.py 从 DemucsSeparator.kt 自动抽取（去 4 空格缩进 + 去 private）。\n")
        f.write("// 不要手工编辑；改实现请改源文件后重跑 extract.py。\n")
        f.write(resampler + "\n")
    print("Resampler.kt      <- DemucsSeparator.kt L%d-%d" % (start + 1, end + 1))

    # ---- 2) StubDemucs.kt：包一层 DemucsSeparator，让真实 LinearResamplerTest.kt 可跑 ----
    stub = dedent(lines[start:end + 1])  # 保留 internal class
    with io.open(os.path.join(HERE, "StubDemucs.kt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("// 由 extract.py 自动生成。DemucsSeparator 桩类，内含抽取出的 LinearResampler，\n")
        f.write("// 目的是让真实的 app/src/test/.../LinearResamplerTest.kt 能在独立 JVM 上运行。\n")
        f.write("package com.nasmusic.tv.player\n\n")
        f.write("class DemucsSeparator {\n" + stub + "\n}\n")
    print("StubDemucs.kt     <- 同上")

    # ---- 3) ByteConv.kt：shortToByteArray + putShortLE ----
    s2b = extract_function(lines, "fun shortToByteArray(")
    psl = extract_function(lines, "fun putShortLE(")
    with io.open(os.path.join(HERE, "ByteConv.kt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("// 由 extract.py 自动抽取（去 4 空格缩进 + 去 private），包进 object 便于调用。\n")
        f.write("object ByteConv {\n")
        f.write(dedent(s2b).replace("private fun", "fun") + "\n")
        f.write(dedent(psl).replace("private fun", "fun") + "\n")
        f.write("}\n")
    print("ByteConv.kt       <- shortToByteArray + putShortLE")


if __name__ == "__main__":
    main()
