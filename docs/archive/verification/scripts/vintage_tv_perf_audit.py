# -*- coding: utf-8 -*-
"""
E38 VINTAGE_TV 怀旧 —— 每帧绘制成本审计（逐字复算 VintageTvRenderer.kt 的 draw 路径）

用户要求：**效果不再调整**（已多次定稿），只做性能优化 ⇒ 优化必须"画面不变/不可辨"。
本脚本量化两件事：
  T1  每帧 draw 调用数（hwui op 数）—— 现状 vs 优化后
  T2  每帧填充率（MPix）—— 现状 vs 优化后
  T3  "画面区收敛"的收益（依据：左右条带被不透明片基完全覆盖）
  T4  合批的 alpha 量化误差（是否达到"不可辨"）
"""
import math

W, H = 1920.0, 1080.0
ASPECT = 4.0 / 3.0

PICTURE_W = min(W, H * ASPECT)          # 1440
SIDE = (W - PICTURE_W) / 2.0            # 240
LEFT = SIDE

SCANLINE_COUNT = 240
SCANLINE_OPACITY = 0.12
SCANLINE_ALPHA_GATE = 0.01              # if (alpha > 0.01f)
NOISE_DENSITY = 0.0015
NOISE_CAP = 2000
NOISE_ALPHA_MIN, NOISE_ALPHA_MAX = 0.12, 0.40

ROLL_BAND_FRACTION = 0.07
ROLL_DUR_MIN, ROLL_DUR_MAX = 4000.0, 5500.0
ROLL_INT_MIN, ROLL_INT_MAX = 6000.0, 10000.0

FILM_PITCH_FRAC = 0.60
FILM_HOLE_W_FRAC = 0.30
FILM_HOLE_H_FRAC = 0.30
FILM_SEP_FRAC = 0.009375

# 歌词位图（按 §C4 代码推算：fontSize = h*0.08，2 行，padding 0.5/0.3 字号）
FONT = H * 0.08
FONT_H = math.ceil(FONT * 1.17)
LINE_H = math.ceil(FONT_H * 1.3)
MAX_LINE_W = PICTURE_W * 0.9
PAD_X = math.ceil(FONT * 0.5)
PAD_Y = math.ceil(FONT * 0.3)
BMP_W = max(math.ceil(MAX_LINE_W + PAD_X * 2), 100)
BMP_H = max(2 * LINE_H + PAD_Y * 2, 50)


def scanline_active():
    """alpha = 0.12*(0.5+0.5*sin) > 0.01 ⇒ sin > -0.83333"""
    thr = (SCANLINE_ALPHA_GATE / SCANLINE_OPACITY - 0.5) / 0.5
    thr = max(-1.0, min(1.0, thr))
    span = math.pi + 2 * math.asin(-thr)          # 满足区间的弧度长度
    return SCANLINE_COUNT * (span / (2 * math.pi))


def noise_count():
    return max(100, min(NOISE_CAP, round(W * H * NOISE_DENSITY)))


def roll_active_ratio():
    d = (ROLL_DUR_MIN + ROLL_DUR_MAX) / 2
    i = (ROLL_INT_MIN + ROLL_INT_MAX) / 2
    return d / (d + i)


def t1():
    act = scanline_active()
    nc = noise_count()
    pitch = SIDE * FILM_PITCH_FRAC
    seps = math.ceil(H / pitch) + 1
    holes = math.ceil(H / pitch) + 1
    per_side = 1 + seps + holes * 3 + 2 + 1
    roll = roll_active_ratio()

    print("=" * 84)
    print(f"T1  每帧 draw 调用数（hwui op 数）  —— 屏幕 {int(W)}x{int(H)}，画面区 {int(PICTURE_W)}，条带 {int(SIDE)}/侧")
    print("=" * 84)
    rows = [
        ("① 背景 drawRect", 1, 1, "O1 收敛到画面区"),
        ("② 扫描线 drawLine", round(act, 1), 16, "O6 按 alpha 分 16 档 → 16 次 drawPath"),
        ("③ 噪点 drawRect", nc, 8, "O5 按 alpha 分 8 档 → 8 次 drawPath"),
        ("④ 滚动暗带", round(roll, 2), round(roll, 2), "O1 只改宽（不减少调用）"),
        ("⑤ 歌词 drawImage", 3, 3, "不动（色差 3 层是设计）"),
        ("⑥ vignette + 圆角遮罩", 2, 2, "O1/O2 只改区域与缓存键"),
        ("⑦ 胶片条带（两侧）", per_side * 2, 16, "O4 分隔线 8.5→1、切孔 24→3 ⇒ 每侧 36.5→8"),
        ("⑧ OSD drawImage", 0.5, 0.5, "不动（1s 亮 1s 灭）"),
    ]
    print(f"  {'项':<22}{'现状':>10}{'优化后':>10}   依据")
    tot_a = tot_b = 0.0
    for name, a, b, why in rows:
        tot_a += a
        tot_b += b
        print(f"  {name:<22}{a:>10.1f}{b:>10.1f}   {why}")
    print(f"  {'合计':<22}{tot_a:>10.1f}{tot_b:>10.1f}")
    print(f"  ⇒ 降幅 {(1 - tot_b / tot_a) * 100:.1f}%")
    print(f"\n  细分：扫描线实际绘制 {act:.1f}/{SCANLINE_COUNT} 条；"
          f"噪点 {nc} 个；胶片每侧 {per_side} 次（片基1 + 分隔线{seps} + 切孔{holes}×3 + 框线2 + 高光1）")


def t2():
    act = scanline_active()
    nc = noise_count()
    e_size2 = (2.0 ** 3 - 1.0 ** 3) / 3.0          # size ∈ [1,2] 均匀 ⇒ E[size²]
    roll = roll_active_ratio()
    pitch = SIDE * FILM_PITCH_FRAC
    seps = math.ceil(H / pitch) + 1
    holes = math.ceil(H / pitch) + 1
    hole_w = SIDE * FILM_HOLE_W_FRAC
    hole_h = SIDE * FILM_HOLE_H_FRAC
    sep_h = max(1.0, SIDE * FILM_SEP_FRAC)

    bg = W * H
    scan = act * W * (H / SCANLINE_COUNT) * 0.4
    noise = nc * e_size2
    band = roll * W * (H * ROLL_BAND_FRACTION)
    lyric = 3 * BMP_W * BMP_H
    vig = W * H
    r = min(PICTURE_W, H) * 0.04
    corner = 4 * r * r * (1 - math.pi / 4)
    film_base = 2 * SIDE * H
    film_sep = 2 * seps * SIDE * sep_h
    film_hole = 2 * holes * 3 * hole_w * hole_h
    film_frame = 2 * (SIDE * 0.025 * H + SIDE * 0.00625 * H)
    film_edge = 2 * max(1.0, SIDE * 0.01) * H
    osd = 0.5 * 110 * 70

    print("\n" + "=" * 84)
    print("T2  每帧填充率（像素）")
    print("=" * 84)
    items = [
        ("① 背景", bg, PICTURE_W * H),
        ("② 扫描线", scan, act * PICTURE_W * (H / SCANLINE_COUNT) * 0.4),
        ("③ 噪点", noise, noise),
        ("④ 滚动暗带", band, roll * PICTURE_W * (H * ROLL_BAND_FRACTION)),
        ("⑤ 歌词 ×3 层", lyric, lyric),
        ("⑥ vignette", vig, PICTURE_W * H),
        ("⑥ 圆角遮罩", corner, corner),
        ("⑦ 胶片条带", film_base + film_sep + film_hole + film_frame + film_edge,
         film_base + film_sep + film_hole + film_frame + film_edge),
        ("⑧ OSD", osd, osd),
    ]
    print(f"  {'项':<18}{'现状(px)':>14}{'优化后(px)':>14}{'省':>12}")
    ta = tb = 0.0
    for name, a, b in items:
        ta += a
        tb += b
        print(f"  {name:<18}{a:>14,.0f}{b:>14,.0f}{a - b:>12,.0f}")
    print(f"  {'合计':<18}{ta:>14,.0f}{tb:>14,.0f}{ta - tb:>12,.0f}")
    print(f"  ⇒ 现状 {ta/1e6:.2f} MPix/帧（30fps = {ta*30/1e6:.0f} MPix/s，60fps = {ta*60/1e6:.0f} MPix/s）")
    print(f"  ⇒ 优化 {tb/1e6:.2f} MPix/帧（30fps = {tb*30/1e6:.0f} MPix/s，60fps = {tb*60/1e6:.0f} MPix/s）")
    print(f"  ⇒ 降幅 {(1 - tb / ta) * 100:.1f}%")
    print(f"\n  歌词位图估算 {BMP_W}x{BMP_H} = {BMP_W*BMP_H:,} px × 3 层 = {lyric:,.0f} px（占现状 {lyric/ta*100:.0f}%）")


def t3():
    print("\n" + "=" * 84)
    print("T3  画面区收敛（O1）的依据与边界")
    print("=" * 84)
    print(f"  画面区 = [{LEFT:.0f}, {LEFT+PICTURE_W:.0f}] × [0, {H:.0f}]；左右条带各 {SIDE:.0f}px")
    print("  依据：胶片条带第 ① 层是 **不透明纯色片基** FILM_BASE_FLAT = 0xFF17150F")
    print("        （VintageTvRenderer.kt:736-737 `filmPaint.color = FILM_BASE_FLAT; nc.drawRect(x0,0,x1,h,paint)`）")
    print("        ⇒ 条带区域被完全覆盖，其下任何层都不可见 ⇒ 收敛绘制区域 **画面逐像素不变**")
    print("\n  各层能否收敛：")
    print("    ① 背景      ✅ 全屏 → 画面区")
    print("    ② 扫描线    ✅ x∈[0,w] → [left,left+pictureW]")
    print("    ④ 滚动暗带  ✅ 宽 w → pictureW（translate 加 left）")
    print("    ⑥ vignette  ✅ 全屏 → 画面区（brush 坐标是绝对的，裁剪不影响渐变采样）")
    print("    ⑥ 圆角遮罩  — 本来就是画面区定界（`:624` pictureW = minOf(w, h*ASPECT)）")
    print("    ⑦ 胶片      ❌ 本身就在条带区")
    print("    ③ 噪点      ⛔ **不收敛**：x = rng.next()*w，若改成 left+rng.next()*pictureW")
    print("                   ⇒ 同样 2000 个点铺在更小面积 ⇒ 密度 +78% ⇒ 观感变化")
    print("    ⑤ 歌词      — 位图居中在屏幕中心 = 画面区中心 ⇒ 本就在画面区内")
    print("\n  ⚠️ 竖屏（h*ASPECT > w ⇒ side ≤ 1f）无条带 ⇒ **必须保持全屏**，收敛逻辑要按 side 分支")


def t4():
    print("\n" + "=" * 84)
    print("T4  合批的 alpha 量化误差（是否「不可辨」）")
    print("=" * 84)
    bgc = (219, 185, 142)      # #DBB98E
    noise_c = (110, 96, 68)    # #6E6044
    print("  噪点（O5，8 档）：色 #6E6044 叠在 #DBB98E 上，alpha ∈ [0.12, 0.40]")
    step = (NOISE_ALPHA_MAX - NOISE_ALPHA_MIN) / 8
    print(f"    档宽 = {step:.4f}，最大量化误差 = ±{step/2:.4f}（半档）")
    d = max(abs(bgc[i] - noise_c[i]) for i in range(3)) * step / 2
    print(f"    最大亮度差 = {d:.2f}/255  ⇒ 单个 1–2px 方块上{'不可辨' if d < 5 else '可能可辨'}")
    print(f"    （对照：现状每个噪点 alpha 连续随机，观感 = 「随机深浅纸纹」；")
    print(f"      量化成 8 级后仍是随机深浅，只是级数从 ∞ → 8）")
    print("\n  扫描线（O6，16 档）：黑色叠在 #DBB98E 上，alpha ∈ [0, 0.12]")
    sstep = SCANLINE_OPACITY / 16
    print(f"    档宽 = {sstep:.5f}，最大量化误差 = ±{sstep/2:.5f}")
    d2 = 219 * sstep / 2
    print(f"    最大亮度差 = {d2:.3f}/255  ⇒ {'不可辨' if d2 < 2 else '可能可辨'}")
    print("\n  胶片条带（O4）：**完全不变** —— 同样的矩形/圆角矩形、同样的 paint，只是合并进 Path")
    print("  O2/O3/O7/O8/O9：**画面完全不变**（缓存键修复 / 状态设置位置 / 内存复用）")


if __name__ == "__main__":
    t1()
    t2()
    t3()
    t4()
