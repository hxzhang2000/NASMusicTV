# -*- coding: utf-8 -*-
"""
E32 STAIRCASE_WAVE 阶梯 —— src 映射数值审计（不靠读代码猜，按实现逐字复算）

现状实现（BatchThreeRenderers.kt:462-470）:
    half = cols / 2f
    src  = (i / half * (n / 2)).toInt()          if i <  half
           ((cols - 1 - i) / half * (n / 2)).toInt()  else

n = frame.spectrum.size = 64 (SpectrumContract.BAR_COUNT)
BASS_END = 39, MID_END = 55, TREBLE_END = 63

输出三张表：
  T1  三种 quality 下每列命中的桶号 -> 不同桶数 / 覆盖区间 / 是否左右镜像
  T2  静音与弱信号下 steps 的分布（16 档量化 + v^0.75 显示通道）-> "不动的柱"占比
  T3  候选修复映射的对照（线性区间均值 / 对数区间均值），验证"列列不同 + 全覆盖"
"""
import math

N = 64
BASS_END, MID_END, TREBLE_END = 39, 55, 63
STEPS = 16

CASES = [("LOW", 20), ("MED", 28), ("HIGH", 36)]


def cur_src(cols, i):
    half = cols / 2.0
    if i < half:
        v = i / half * (N // 2)
    else:
        v = (cols - 1 - i) / half * (N // 2)
    return max(0, min(N - 1, int(v)))


def t1():
    print("=" * 78)
    print("T1  当前映射：每列命中的频谱桶（n=64）")
    print("=" * 78)
    for name, cols in CASES:
        srcs = [cur_src(cols, i) for i in range(cols)]
        uniq = sorted(set(srcs))
        # 镜像自证：src(i) == src(cols-1-i) 是否恒成立
        mirrored = all(srcs[i] == srcs[cols - 1 - i] for i in range(cols))
        # 相邻列是否同桶（成组重复）
        dup_pairs = sum(1 for i in range(cols - 1) if srcs[i] == srcs[i + 1])
        print(f"\n[{name}] cols={cols}  命中不同桶 = {len(uniq)} / {cols} 列")
        print(f"        桶号 = {uniq}")
        print(f"        最大桶 = {max(srcs)}  → 覆盖 bin 0..{max(srcs)}"
              f"（全频段应到 {N-1}，缺失 {N - 1 - max(srcs)} 个桶）")
        print(f"        左右严格镜像 = {mirrored}；相邻列同桶的对数 = {dup_pairs}")
        seg = []
        for i in range(cols):
            s = srcs[i]
            band = "低" if s <= BASS_END else ("中" if s <= MID_END else "高")
            seg.append(f"{i}:{s}{band}")
        print("        逐列 = " + " ".join(seg))


def t2():
    print("\n" + "=" * 78)
    print("T2  16 档垂直量化下，弱信号列的可见高度（steps = int(v*16)）")
    print("=" * 78)
    print("  v 是 spectrum 通道（已 ^0.75 gamma 压缩 + boost 峰值归一化）")
    print(f"  {'v 区间':<16}{'steps':>6}{'高度(格)':>10}   说明")
    rows = [
        (0.000, 0.0625, "steps=0 → 只画 1 格 alpha0.10（静音列样式）"),
        (0.0625, 0.125, "steps=1 → 1 格 alpha0.95"),
        (0.125, 0.1875, "steps=2 → 2 格"),
        (0.1875, 0.25, "steps=3 → 3 格"),
        (0.25, 0.3125, "steps=4 → 4 格"),
    ]
    for lo, hi, note in rows:
        st = int(lo * STEPS)
        print(f"  [{lo:.4f},{hi:.4f})  {st:>4}  {max(st,1):>8}   {note}")
    print("\n  ⛔ 关键结论：v ∈ [0, 0.125) 的所有列，**高度完全相同（1 格）**，")
    print("     只有 alpha 不同（0.10 vs 0.95）。而中高频桶的 v 常年落在这一区间")
    print("     ⇒ 画面上那一片就是「恒定 1 格高的矮柱」，即用户看到的「其他不会动」。")
    print("     16 档 ⇒ 每档 0.0625，小动态被量化吃掉。")


def lin_bands(cols):
    """线性区间划分：第 i 列 -> [i*N/cols, (i+1)*N/cols)"""
    out = []
    for i in range(cols):
        lo = i * N // cols
        hi = max(lo + 1, (i + 1) * N // cols)
        out.append((lo, min(hi, N)))
    return out


def log_bands(cols, k):
    """对数（感知）区间划分：lo = floor(N*(i/cols)^k)，k>1 => 低频占更多列"""
    out = []
    for i in range(cols):
        lo = int(N * (i / cols) ** k)
        hi = int(N * ((i + 1) / cols) ** k)
        if hi <= lo:
            hi = lo + 1
        out.append((lo, min(hi, N)))
    return out


def t3():
    print("\n" + "=" * 78)
    print("T3  候选修复映射对照（要求：列列不同 + 覆盖 0..63）")
    print("=" * 78)
    for name, cols in CASES:
        print(f"\n[{name}] cols={cols}")
        for label, bands in (("线性", lin_bands(cols)),
                             ("对数 k=1.6", log_bands(cols, 1.6)),
                             ("对数 k=2.0", log_bands(cols, 2.0))):
            los = [b[0] for b in bands]
            his = [b[1] for b in bands]
            cover = max(his)
            overlaps = sum(1 for i in range(cols - 1) if bands[i][1] > bands[i + 1][0])
            gaps = sum(1 for i in range(cols - 1) if bands[i][1] < bands[i + 1][0])
            width_lo = sum(1 for b in bands if b[1] - b[0] <= 2)   # 极窄区间（=单桶）
            print(f"  {label:<12} 覆盖 0..{cover:<3} 重叠={overlaps} 空隙={gaps} "
                  f"最窄区间数(<=2桶)={width_lo}")
            print(f"    区间 = {bands}")
        # 对数 k=1.6 的"低频分辨率"
        b16 = log_bands(cols, 1.6)
        first8 = [b for b in b16 if b[0] <= 8]
        print(f"    对数k=1.6：前 8 个桶（0–2.5kHz，含全部基频）占 {len(first8)} 列 / {cols}")


if __name__ == "__main__":
    t1()
    t2()
    t3()
