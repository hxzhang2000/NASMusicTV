# -*- coding: utf-8 -*-
"""
E29 ORBITAL_RINGS 星野量化审计（2026-09-29）

用户反馈：「E29 轨道（太阳系），背景的星光太大了，最好能不影响太阳系的主体地位」

本脚本不读观感，只按「显示采样率」把星野的数值路径重放一遍：
  1) 复现 onEnter 的固定种子 LCG，生成 220 颗星的 (x, y, r, alpha)
  2) 复现 extentX / scale（与渲染器同一套算式）
  3) 算星点像素半径，并与 SUN_R / 行星半径 / 卫星半径逐一对比
  4) 算星野的空间分布（落在太阳盘内 / 太阳辉光内 / 水星轨道内 各多少颗）
  5) 算「发光量」口径的总量（Σ alpha·πr²），与太阳对比
  6) 反事实：改分布 / 改上限 / 改数量，各自会变成什么
"""
import math

# ── 渲染器常量（BatchTwoRenderers.kt）───────────────────────────────────────
TILT = 0.5
SCALE_MARGIN = 0.9
NEAR_FAR_K = 0.10
RING_X = 1.75
SUN_R = 0.042
SUN_GLOW_R = 0.070
RADIUS_BASS_GAIN = 0.14
RADIUS_PULSE_GAIN = 0.11
STAR_MAX = 220
STAR_R_MIN, STAR_R_SPAN = 0.0010, 0.0020     # starR = 0.0010 + rand*0.0020
STAR_A_MIN, STAR_A_SPAN = 0.25, 0.65         # starA = 0.25 + rand*0.65

# ── 行星表（name, radius, orbit, [moons: (name, radius, orbit)]）─────────────
PLANETS = [
    ("Mercury", 0.0060, 0.095, []),
    ("Venus",   0.0095, 0.134, []),
    ("Earth",   0.0105, 0.176, [("Moon", 0.0040, 0.0270)]),
    ("Mars",    0.0075, 0.216, [("Phobos", 0.0024, 0.0150), ("Deimos", 0.0028, 0.0218)]),
    ("Jupiter", 0.0225, 0.282, [("Io", 0.0068, 0.0349), ("Europa", 0.0060, 0.0416),
                                ("Ganymede", 0.0078, 0.0484), ("Callisto", 0.0074, 0.0518)]),
    ("Saturn",  0.0195, 0.356, [("Titan", 0.0068, 0.0429)]),
    ("Uranus",  0.0135, 0.417, [("Titania", 0.0040, 0.0209), ("Oberon", 0.0038, 0.0236)]),
    ("Neptune", 0.0125, 0.452, [("Triton", 0.0045, 0.0169)]),
]
SATURN_IDX = 5


def build_extent_x():
    """复现 buildExtentX()"""
    best = 0.0
    for i, (nm, rad, orb, moons) in enumerate(PLANETS):
        local = rad
        for (mn, mr, mo) in moons:
            local = max(local, mo + mr)
        if i == SATURN_IDX:
            local = max(local, rad * RING_X)
        best = max(best, orb + local * (1.0 + NEAR_FAR_K))
    return best


EXTENT_X = build_extent_x()
EXTENT_Y = EXTENT_X * TILT


def scale_for(w, h):
    """复现 ensureLayout(): scale = min(w/2/extentX, h/2/extentY) * SCALE_MARGIN"""
    return min(w * 0.5 / EXTENT_X, h * 0.5 / EXTENT_Y) * SCALE_MARGIN


def gen_stars():
    """复现 onEnter 的固定种子 LCG（UInt32 回绕）"""
    rng = 0x5EEDF00D
    M = 0xFFFFFFFF
    out = []
    for _ in range(STAR_MAX):
        vals = []
        for _k in range(4):
            rng = (rng * 1664525 + 1013904223) & M
            vals.append(((rng >> 8) & 0xFFFFFF) / 16777216.0)
        x, y, rr, aa = vals
        out.append((x, y, STAR_R_MIN + rr * STAR_R_SPAN, STAR_A_MIN + aa * STAR_A_SPAN))
    return out


STARS = gen_stars()


def stat(v):
    s = sorted(v)
    n = len(s)
    return s[0], s[n // 2], s[-1], sum(s) / n


def hr(t):
    print("\n" + "=" * 78)
    print(t)
    print("=" * 78)


# ── 1. 几何基准 ────────────────────────────────────────────────────────────
hr("1. 几何基准（extentX / scale）")
print("extentX = %.5f   extentY = extentX*TILT = %.5f" % (EXTENT_X, EXTENT_Y))
CANVASES = [
    ("电视 1920x1080 (横)", 1920, 1080),
    ("手机 2400x1080 (横)", 2400, 1080),
    ("手机 1080x2400 (竖)", 1080, 2400),
    ("手机 1440x3120 (竖)", 1440, 3120),
]
SC = {}
for nm, w, h in CANVASES:
    s = scale_for(w, h)
    SC[nm] = (w, h, s)
    print("  %-22s scale=%8.1f   太阳盘半径=%6.1f px  辉光半径=%6.1f px"
          % (nm, s, SUN_R * s, SUN_GLOW_R * s))

# ── 2. 星点像素半径 ────────────────────────────────────────────────────────
hr("2. 星点像素半径（starR × scale）—— 核心问题所在")
lo, md, hi, avg = stat([st[2] for st in STARS])
print("世界单位 starR: min=%.4f 中位=%.4f max=%.4f 均值=%.4f" % (lo, md, hi, avg))
print("世界单位 starA: min=%.4f 中位=%.4f max=%.4f 均值=%.4f"
      % stat([st[3] for st in STARS]))
print()
print("  %-22s %-9s %-9s %-9s   %s" % ("画布", "r_min(px)", "r_中位", "r_max(px)", "最大星直径"))
for nm, w, h in CANVASES:
    s = SC[nm][2]
    print("  %-22s %8.2f  %8.2f  %8.2f   %8.2f px"
          % (nm, lo * s, md * s, hi * s, hi * s * 2))
print()
print("  ⚠️ 关键：半径是【均匀分布】且【下限不为 0】⇒ 没有亚像素级暗星做衬底。")
print("     1080p 上最小星半径 %.2f px（直径 %.2f px）—— 已经是一个可见的圆点，"
      % (lo * SC["电视 1920x1080 (横)"][2], lo * SC["电视 1920x1080 (横)"][2] * 2))
print("     而不是「一片几乎看不见的细碎底噪」。")

# ── 3. 与太阳 / 行星 / 卫星的尺寸对比 ──────────────────────────────────────
hr("3. 尺寸对比 —— 星点 vs 太阳 / 行星 / 卫星（同一 scale 下）")
print("  以「世界单位半径」直接比较（同乘 scale，比例与画布无关）：")
print("  %-12s %-10s %-12s %s" % ("天体", "世界半径", "占太阳半径", "占最大星半径"))
print("  %-12s %-10.4f %-12s %s" % ("★ 最大星", hi, "%.1f%%" % (hi / SUN_R * 100),
                                    "100%"))
print("  %-12s %-10.4f %-12s %s" % ("★ 中位星", md, "%.1f%%" % (md / SUN_R * 100),
                                    "%.1f%%" % (md / hi * 100)))
print("  %-12s %-10.4f %-12s %s" % ("★ 最小星", lo, "%.1f%%" % (lo / SUN_R * 100),
                                    "%.1f%%" % (lo / hi * 100)))
print("  %-12s %-10.4f %-12s %s" % ("太阳", SUN_R, "100%", "%.1f%%" % (SUN_R / hi * 100)))
print()
print("  行星（半径 / 占最大星半径）：")
for nm, rad, orb, moons in PLANETS:
    flag = "  ⛔ 比最大星还小" if rad < hi else ""
    print("    %-9s %.4f   最大星是其 %.0f%%%s" % (nm, rad, hi / rad * 100, flag))
print()
print("  卫星（半径 / 占最大星半径）—— ⛔ 这是视觉层级的真正破口：")
bad = []
for nm, rad, orb, moons in PLANETS:
    for (mn, mr, mo) in moons:
        mark = ""
        if mr <= hi:
            mark = "  ⛔⛔ 卫星比最大星更小！"
            bad.append(mn)
        elif mr < hi * 1.5:
            mark = "  ⚠️ 与最大星同量级"
        print("    %-9s %-9s %.4f   最大星是其 %.0f%%%s"
              % (nm, mn, mr, hi / mr * 100, mark))
print()
print("  ⛔ 结论：最大星（0.0030）**大于** Phobos(0.0024) 与 Deimos(0.0028) 两颗卫星，"
      "与 Oberon(0.0038)/Titania(0.0040)/Moon(0.0040) 同量级。")
print("     卫星 alpha = 1.0（drawCircle 未传 alpha ⇒ 默认 1），最大星 alpha 可到 0.90")
print("     ⇒ 亮度也接近。**视觉上「一颗星」和「一颗卫星」分不出来**，这正是"
      "「星光太大、压主体」的量化根因。")

# ── 4. 空间分布：星点落在哪里 ─────────────────────────────────────────────
hr("4. 空间分布 —— 星点是否落在主体区域")
for nm, w, h in CANVASES:
    s = SC[nm][2]
    cx, cy = w * 0.5, h * 0.5
    sun_r = SUN_R * s
    glow_r = SUN_GLOW_R * s
    mercury_px = 0.095 * s        # 水星轨道长半轴（水平）
    mercury_py = mercury_px * TILT
    in_sun = in_glow = in_merc = 0
    for (x, y, rr, aa) in STARS[:140]:        # MEDIUM 档 = 140 颗
        px, py = x * w, y * h
        dx, dy = px - cx, py - cy
        d = math.hypot(dx, dy)
        if d <= sun_r:
            in_sun += 1
        if d <= glow_r:
            in_glow += 1
        # 水星轨道椭圆内（归一化）
        e = (dx / mercury_px) ** 2 + (dy / mercury_py) ** 2
        if e <= 1.0:
            in_merc += 1
    print("  %-22s 太阳盘内 %2d 颗 / 辉光内 %2d 颗 / 水星轨道内 %3d 颗  (共 140)"
          % (nm, in_sun, in_glow, in_merc))
print()
print("  ⚠️ 星野位置 = (starX*w, starY*h) 铺满【整块画布】，包括太阳与内行星所在的中心区。")
print("     真实星野在「太阳系内部」不该有前景亮星（那是行星际空间）。")

# ── 5. 发光量口径的总量 ────────────────────────────────────────────────────
hr("5. 发光量口径（Σ alpha · π r²）—— 星野 vs 太阳")
for nm, w, h in CANVASES:
    s = SC[nm][2]
    for tier_name, cnt in (("LOW 70", 70), ("MEDIUM 140", 140), ("HIGH 220", 220)):
        total = sum(math.pi * (st[2] * s) ** 2 * st[3] for st in STARS[:cnt])
        sun = math.pi * (SUN_R * s) ** 2 * 1.0
        # 太阳盘 + 辉光（辉光渐变 alpha 峰值约 0.61 到 0，粗取等效 0.3）
        sun_glow = math.pi * (SUN_GLOW_R * s) ** 2 * 0.30
        print("  %-22s %-11s 星野发光量 = %8.0f px²·α   = 太阳盘 %5.1f%%   = 太阳盘+辉光 %5.1f%%"
              % (nm, tier_name, total, total / sun * 100, total / (sun + sun_glow) * 100))
    print()

# ── 6. 反事实 ──────────────────────────────────────────────────────────────
hr("6. 反事实 —— 各修法把「最大星直径 / 星野发光量」压到多少")
s = SC["电视 1920x1080 (横)"][2]
base_total = sum(math.pi * (st[2] * s) ** 2 * st[3] for st in STARS[:140])
base_maxd = hi * s * 2


def report(tag, rs, alphas, cnt=140):
    maxd = max(rs) * s * 2
    tot = sum(math.pi * (r * s) ** 2 * a for r, a in zip(rs, alphas))
    print("  %-42s 最大星直径 %5.2f px (%.0f%%)  发光量 %6.0f (%.0f%%)"
          % (tag, maxd, maxd / base_maxd * 100, tot, tot / base_total * 100))


report("现状：r∈[0.0010,0.0030] 均匀, a∈[0.25,0.90]", [st[2] for st in STARS[:140]],
       [st[3] for st in STARS[:140]])
# ① 幂律（平方）：少量亮星 + 大量暗星
pow_r = [0.00035 + (st[2] / 0.0030) ** 2 * 0.00115 for st in STARS[:140]]
pow_a = [0.10 + (st[3] / 0.90) ** 2 * 0.45 for st in STARS[:140]]
report("① 幂律分布（亮星少、暗星多）+ alpha 同步降", pow_r, pow_a)
# ② 只降上限（保持均匀分布）
cap_r = [min(st[2], 0.0016) for st in STARS[:140]]
report("② 只封顶：r ≤ 0.0016（= 太阳半径 3.8%）", cap_r, [st[3] for st in STARS[:140]])
# ③ 只降 alpha
report("③ 只降亮度：a ∈ [0.08, 0.42]", [st[2] for st in STARS[:140]],
       [0.08 + (st[3] - 0.25) / 0.65 * 0.34 for st in STARS[:140]])
# ④ 只降数量
report("④ 只降数量：MEDIUM 140 → 90", [st[2] for st in STARS[:90]],
       [st[3] for st in STARS[:90]], 90)
# ⑤ ①②③ 合起来（推荐档）
rec_r = [min(r, 0.0015) for r in pow_r]
rec_a = [a * 0.85 for a in pow_a]
report("⑤ 推荐：幂律 + 封顶 0.0015 + alpha ≤0.42", rec_r, rec_a)
print()
print("  ⚠️ 注意 ④：只减数量不减尺寸 —— 单颗星的「块头」没变，主体仍被同等大小的圆点包围，")
print("     所以「看起来还是太大」。**尺寸分布才是主要矛盾，数量是次要的。**")

# ── 7. 峰值星 / 太阳对比（视觉重心）────────────────────────────────────────
hr("7. 视觉重心对比（1080p）")
s = SC["电视 1920x1080 (横)"][2]
sun_d = SUN_R * s * 2
top = sorted(STARS[:140], key=lambda st: st[2] * st[3], reverse=True)[:5]
print("  太阳盘直径 = %.1f px（律动时 ×1.25 → %.1f px）" % (sun_d, sun_d * 1.25))
print("  亮度最高的 5 颗星（半径×alpha 排序）：")
for (x, y, rr, aa) in top:
    print("     r=%5.2f px  d=%5.2f px  alpha=%.2f  面积×alpha=%6.1f px²  占太阳盘面积 %.1f%%"
          % (rr * s, rr * s * 2, aa, math.pi * (rr * s) ** 2 * aa,
             math.pi * (rr * s) ** 2 * aa / (math.pi * (SUN_R * s) ** 2) * 100))
print()
print("  ⚠️ 单颗星的面积·alpha 只有太阳盘的 ~1% ⇒ 「总量」上不构成威胁；")
print("     但**人眼对孤立硬边亮点的敏感度远高于面积占比**：")
print("     140 个 4–11 px 的硬边白/蓝圆铺在纯黑底上，每一颗都是强对比边缘 ⇒ 抢夺注意力。")
print("     ⇒ 修法的重点不是「减总量」，而是「削掉硬边大点 + 建立暗星衬底 + 让中心区安静」。")


# ══════════════════════════════════════════════════════════════════════════════
# 8. 跨效果星野横向对比（5 套有星野：E17 星座 / E29 轨道 / E33 齿轮 / E40 DNA / E41 世界）
# ══════════════════════════════════════════════════════════════════════════════
hr("8. 跨效果星野横向对比（同一画布 1920x1080 下的「最大星」像素值）")

# (名称, 文件:行, 星数 LOW/MED/MAX, R_MIN, R_MAX, A_MIN, A_MAX, 半径基准, 有漂移, Plus)
FIELDS = [
    ("E29 轨道", "BatchTwoRenderers.kt:261-262", (70, 140, 220),
     0.0010, 0.0030, 0.25, 0.90, "scale", False, False),
    ("E33 齿轮", "BatchFourRenderers.kt:473-474", (40, 70, 110),
     0.0010, 0.0028, 0.14, 0.40, "minDim", True, True),
    ("E40 DNA", "DnaRenderer.kt:461-462", (40, 70, 220),
     0.0010, 0.0030, 0.25, 0.90, "scale", False, False),
    ("E41 世界", "WorldRenderer.kt:1843-1846", (40, 70, 110),
     0.0009, 0.0020, 0.10, 0.28, "minDim", True, True),
]
W, H = 1920, 1080
BASIS = {"scale": scale_for(W, H), "minDim": float(min(W, H))}
print("  %-9s %-30s %-11s %-13s %-11s %-13s %s"
      % ("效果", "常量出处", "半径基准", "最大星 r / d (px)", "alpha 上限",
         "单颗峰值 面积·α", "漂移"))
print("  " + "-" * 108)
base_peak = None
for (nm, src, cnt, rmin, rmax, amin, amax, basis, drift, plus) in FIELDS:
    b = BASIS[basis]
    rpx, dpx = rmax * b, rmax * b * 2
    peak = math.pi * rpx * rpx * amax
    if base_peak is None:
        base_peak = peak
    print("  %-9s %-30s %-11s %6.2f / %5.2f   %-11.2f %-13.0f %s"
          % (nm, src, "%s=%.0f" % (basis, b), rpx, dpx, amax, peak,
             ("有" if drift else "无") + (" +Plus" if plus else "")))
print()
e29 = [f for f in FIELDS if f[0].startswith("E29")][0]
for other in FIELDS:
    if other[0] == e29[0]:
        continue
    b1, b2 = BASIS[e29[7]], BASIS[other[7]]
    r_ratio = (e29[4] * b1) / (other[4] * b2)
    a_ratio = e29[6] / other[6]
    print("  E29 vs %-9s  半径比 %5.2f×  alpha 比 %5.2f×  单颗峰值亮度比 %6.1f×"
          % (other[0], r_ratio, a_ratio, r_ratio ** 2 * a_ratio))
print()
print("  ⛔ 结论：E29 是 5 套星野里**最激进**的一档 ——")
print("     ① 半径基准用 `scale`（1080p = 1817）而 E33/E41 用 `minDim`（1080）⇒ 同样的世界半径，")
print("        E29 画出来大 **1.68×**；")
print("     ② alpha 上限 0.90（E33 0.40 / E41 0.28）；")
print("     ③ 两者相乘 ⇒ **E29 最亮星的单颗发光量是 E41 的 %.0f 倍**。" % ((e29[4] * BASIS[e29[7]]) ** 2 * e29[6] / ((FIELDS[3][4] * BASIS[FIELDS[3][7]]) ** 2 * FIELDS[3][6])))
print("     ⚠️ E40 DNA 的参数与 E29 **逐字相同**（`0.0010+0.0020` / `0.25+0.65`，KDoc `:331` 写「同太阳系」）")
print("        ⇒ 同一份代码被复制粘贴，**同一缺陷也在 DNA 上**（本方案顺带修，见 §C6）。")

# ══════════════════════════════════════════════════════════════════════════════
# 9. 方案落地后的 E29 数值（可直接写进文档）
# ══════════════════════════════════════════════════════════════════════════════
hr("9. 推荐方案落地后的 E29（新 LCG 消耗顺序：u → x → y → jitter）")


def gen_stars_new():
    rng = 0x5EEDF00D
    M = 0xFFFFFFFF
    out = []
    for _ in range(STAR_MAX):
        vals = []
        for _k in range(4):
            rng = (rng * 1664525 + 1013904223) & M
            vals.append(((rng >> 8) & 0xFFFFFF) / 16777216.0)
        u, x, y, j = vals
        r = NEW_R_MIN + u ** 3 * NEW_R_SPAN
        a = NEW_A_MIN + (u * u * 0.75 + j * 0.25) * NEW_A_SPAN
        out.append((x, y, r, a))
    return out


NEW_R_MIN, NEW_R_SPAN = 0.00035, 0.00115        # 上限 0.00150 = Phobos 半径的 62.5%
NEW_A_MIN, NEW_A_SPAN = 0.12, 0.38              # 上限 0.50
QUIET_R, QUIET_FLOOR = 0.15, 0.25

NEW = gen_stars_new()
s1080 = scale_for(1920, 1080)
for cnt in (70, 140, 220):
    sub = NEW[:cnt]
    rmax = max(st[2] for st in sub)
    rmed = sorted(st[2] for st in sub)[len(sub) // 2]
    amax = max(st[3] for st in sub)
    amed = sorted(st[3] for st in sub)[len(sub) // 2]
    tot = sum(math.pi * (st[2] * s1080) ** 2 * st[3] for st in sub)
    print("  %3d 星：r_max=%5.2f px (d=%5.2f)  r_中位=%4.2f px (d=%4.2f)  "
          "alpha_max=%.2f alpha_中位=%.2f  发光量=%6.0f"
          % (cnt, rmax * s1080, rmax * s1080 * 2, rmed * s1080, rmed * s1080 * 2,
             amax, amed, tot))

print()
print("  ── 与改造前（MEDIUM 140 星 · 1080p）逐项对照 ──")
old = STARS[:140]
old_rmax, old_rmed = max(st[2] for st in old), sorted(st[2] for st in old)[70]
old_amax, old_amed = max(st[3] for st in old), sorted(st[3] for st in old)[70]
old_tot = sum(math.pi * (st[2] * s1080) ** 2 * st[3] for st in old)
new = NEW[:140]
new_rmax, new_rmed = max(st[2] for st in new), sorted(st[2] for st in new)[70]
new_amax, new_amed = max(st[3] for st in new), sorted(st[3] for st in new)[70]
new_tot = sum(math.pi * (st[2] * s1080) ** 2 * st[3] for st in new)
rows = [
    ("最大星半径", old_rmax * s1080, new_rmax * s1080, "px"),
    ("最大星直径", old_rmax * s1080 * 2, new_rmax * s1080 * 2, "px"),
    ("中位星直径", old_rmed * s1080 * 2, new_rmed * s1080 * 2, "px"),
    ("alpha 上限", old_amax, new_amax, ""),
    ("alpha 中位", old_amed, new_amed, ""),
    ("星野总发光量", old_tot, new_tot, "px²·α"),
]
for (nm, o, n, u) in rows:
    print("    %-14s %9.2f → %8.2f %-6s  (%+.0f%%)" % (nm, o, n, u, (n / o - 1) * 100))
print()
print("  ── 视觉层级不变式（世界单位半径，与画布无关）──")
print("    太阳 0.0420 > 最大行星 Jupiter 0.0225 > 最小行星 Mercury 0.0060")
print("      > 最小卫星 Phobos 0.0024 > **最大星 %.5f**（改造前 0.0030 ⇒ 越界 125%%）"
      % new_rmax)
print("    ✅ 改造后最大星 = Phobos 的 %.1f%% ⇒ 层级恢复「星 < 卫星 < 行星 < 太阳」"
      % (new_rmax / 0.0024 * 100))
print()
print("  ── 中心静默区（半轴 0.15 × scale / 0.075 × scale，alpha 线性降到 25%）──")
for nm, w, h in CANVASES:
    s = SC[nm][2]
    cx, cy = w * 0.5, h * 0.5
    qx, qy = 0.15 * s, 0.15 * s * TILT
    aff = 0
    for (x, y, r, a) in new:
        e = ((x * w - cx) / qx) ** 2 + ((y * h - cy) / qy) ** 2
        if e < 1.0:
            aff += 1
    print("  %-22s 静默区内 %2d 颗（受 alpha 压制）；静默区半轴 = %5.1f × %5.1f px"
          % (nm, aff, qx, qy))
print()
print("  ⚠️ 静默区半径 0.15 世界单位 —— 介于金星轨道（0.134）与地球轨道（0.176）之间，")
print("     即「内太阳系」范围内不出现前景亮星（真实行星际空间也没有）。")
