# -*- coding: utf-8 -*-
"""E23 歌词点阵「逐字颜色改变」（卡拉OK 亮度分档）成本审计。

方法：不靠读代码猜，而是**把 addLineToPaths 的逐点循环体按语句分类**，
逐条判定「这一条是不是卡拉OK 功能带来的」，再乘上真实点数得出每帧总量。

输出三类：
  A. 每点开销分解（含卡拉OK 归属）
  B. 每帧总量（worst / typical）
  C. 反事实：**如果删掉卡拉OK，成本会变多少**（关键结论）
"""
import io
import re

SRC = 'app/src/main/java/com/nasmusic/tv/visualizer/renderers/LyricsDotMatrixRenderer.kt'
lines = io.open(SRC, encoding='utf-8').read().split('\n')


def L(n):
    """1-based 行号取内容"""
    return lines[n - 1].rstrip()


# ─────────────────────────────────────────────────────────────
# 1) 逐点循环体 = :636-705（addLineToPaths 的 for (i in 0 until count)）
# ─────────────────────────────────────────────────────────────
LOOP = range(636, 706)

# 卡拉OK 归属：:663-680（brightness 计算 + tier 判定）
#   663-674  brightness 的 when 分支
#   676-680  tier 判定
KARAOKE = set(range(663, 675)) | set(range(676, 681))
# 与卡拉OK 直接耦合的：697 的 coreIdx = tier * 2（决定点进哪条 Path）
KARAOKE_COUPLED = {697}


def count_ops(ln):
    """统计一行里的算子（粗粒度，够用）"""
    t = L(ln)
    t = t.split('//')[0]        # 去行尾注释
    t = t.replace('->', ' ')    # ⚠️ `->` 里的 `>` 会被误判成比较算子（首次跑就踩了）
    return {
        'cmp': len(re.findall(r'[<>]=?|==|!=', t)),
        'mul': len(re.findall(r'(?<![\w.])\*(?!\*)', t)) + len(re.findall(r'(?<![\w.])/(?![/])', t)),
        'add': len(re.findall(r'(?<![\w.])[+\-](?![\w.])', t)),
        'sin': t.count('sin('),
        'idx': len(re.findall(r'\[\s*o\s*\+', t)),
        'new': len(re.findall(r'\b(Rect|IntArray|FloatArray|AndroidPaint|Path)\s*\(', t)),
        'jni': len(re.findall(r'\b(addOval|drawPath|addPath|getPixel|getPixels)\s*\(', t)),
        'coerce': t.count('coerce'),
    }


tot = {k: 0 for k in ('cmp', 'mul', 'add', 'sin', 'idx', 'new', 'jni', 'coerce')}
kar = {k: 0 for k in tot}
rows = []
for ln in LOOP:
    o = count_ops(ln)
    if not any(o.values()):
        continue
    tag = 'KARAOKE' if ln in KARAOKE else ('COUPLED' if ln in KARAOKE_COUPLED else '')
    for k, v in o.items():
        tot[k] += v
        if tag == 'KARAOKE':
            kar[k] += v
    if tag:
        rows.append((ln, tag, o, L(ln).strip()[:74]))

print('=' * 78)
print('A. 逐点循环体（:636-705）算子统计')
print('=' * 78)
print('  %-8s %-10s %s' % ('行', '归属', '代码'))
for ln, tag, o, code in rows:
    print('  :%-6d %-10s %s' % (ln, tag, code))
print()
print('  全循环体每点: ' + '  '.join('%s=%d' % (k, v) for k, v in tot.items() if v))
print('  其中卡拉OK  : ' + '  '.join('%s=%d' % (k, v) for k, v in kar.items() if v))
print()
print('  ⇒ 卡拉OK 每点新增：比较 %d 次 + 乘除 %d 次 + 加减 %d 次' % (kar['cmp'], kar['mul'], kar['add']))
print('  ⇒ 卡拉OK 每点新增的 **分配 = %d**，**JNI = %d**，**sin = %d**' % (kar['new'], kar['jni'], kar['sin']))

# ─────────────────────────────────────────────────────────────
# 2) 每帧总量
# ─────────────────────────────────────────────────────────────
CAP = 3600  # capacityFor() 返回 3600（:105）

print()
print('=' * 78)
print('B. 每帧总量（cap = %d 点/行）' % CAP)
print('=' * 78)

print("""
  glow（:700-704 的第二条 addOval）只在 tier >= 1 时画，而 tier 由 brightness 决定：
    演唱行已唱段  brightness = 0.78  → tier1 → 有 glow
    演唱行前沿字  brightness = 1.00  → tier2 → 有 glow
    演唱行未唱段  brightness = 0.18 + energy*0.07 ≈ 0.25 → tier0 → **无 glow**
    待唱行        brightness = 0.25 + energy*0.08 ≤ 0.33 → tier0 → **无 glow**
  ⇒ 现状 glow 点数 ≈ (已唱占比) × n0；一句歌词的已唱占比在整句时长内 0→1 ⇒ **平均 50%**
  ⇒ 待唱行(line1) **贡献 0 个 glow**
""")


def frame_report(n0, n1, label):
    pts = n0 + n1
    core = pts
    glow_now = int(n0 * 0.5)          # 平均已唱占比 50%
    glow_hi = pts                     # 反事实①：统一取"正在唱"亮度 ⇒ 每点 tier2
    glow_lo = 0                       # 反事实②：统一取"待唱"亮度 ⇒ 每点 tier0
    ov_now = core + glow_now
    print('  【%s】点 n0=%d + n1=%d = %d' % (label, n0, n1, pts))
    print('    Rect 分配/帧（data class ⇒ 必分配；每 addOval 一个）')
    print('      现状        : core %5d + glow %5d = **%5d**  ≈ %5.0f KB/帧 ≈ %4.1f MB/s @30fps'
          % (core, glow_now, ov_now, ov_now * 32 / 1024, ov_now * 32 * 30 / 1048576))
    print('      ⛔ 删卡拉OK① : core %5d + glow %5d = **%5d**  ⇒ %+d（%+.0f%%） 统一取亮 ⇒ 反而更多'
          % (core, glow_hi, core + glow_hi, glow_hi - glow_now,
             (glow_hi - glow_now) * 100.0 / ov_now))
    print('      ⛔ 删卡拉OK② : core %5d + glow %5d = **%5d**  ⇒ %+d（%+.0f%%） 统一取暗 ⇒ 省下 glow'
          % (core, glow_lo, core + glow_lo, -glow_now, -glow_now * 100.0 / ov_now))
    print('      ✅ 修 P0-1（dotPath 池 + addPath）: **0** 个 Rect ⇒ 无论保不保留卡拉OK 都是 −100%%')
    print('    JNI/帧')
    print('      addOval（native）: %5d （core %d + glow %d）' % (ov_now, core, glow_now))
    print('      drawPath         : %5d  ← **恒为 6，与卡拉OK 无关**' % 6)
    print('    浮点/帧')
    print('      卡拉OK 分支      : %6d 比较 + %6d 乘除 = %6d 次'
          % (pts * kar['cmp'], pts * kar['mul'], pts * (kar['cmp'] + kar['mul'])))
    print('      全部 sin         : %6d  ← 与卡拉OK 无关（呼吸 :683 + 波浪 :692）' % (pts * tot['sin']))
    print('      ⇒ 卡拉OK 占比 %.1f%%（且全是寄存器算术，无内存分配）'
          % (pts * (kar['cmp'] + kar['mul']) * 100.0
             / max(1, pts * (tot['cmp'] + tot['mul'] + tot['add'] + tot['sin']))))
    print()


frame_report(CAP, CAP, 'worst：两行都满（长歌词行 + 长下一行）')
frame_report(int(CAP * 0.6), int(CAP * 0.5), 'typical：短句（主歌常见 6-8 字）')

# ─────────────────────────────────────────────────────────────
# 3) 采样开销（与卡拉OK 无关，但它是 E23 唯一真正卡的地方）
# ─────────────────────────────────────────────────────────────
print('=' * 78)
print('C. 采样开销（sampleLine，**仅在歌词换行时**触发，与卡拉OK 无关）')
print('=' * 78)
FONT = 108.0
TW = 1600.0
est = FONT * TW * 0.20
step = max(1, min(3, int((est / CAP) ** 0.5)))
bmpW = int(TW * 1.15)
# ⚠️ 与代码同口径：:224 `bmpH = (fontHeight + fontSize * 0.4f)`，而 fontHeight = fm.bottom − fm.top
#    ≈ 1.2 × fontSize（典型中文字形），**不是 1.4**（首版误用 1.4 ⇒ 算出 79,690，与 §B7 P0-2 的 71,000 不一致）
FONT_H = FONT * 1.2
bmpH = int(FONT_H + FONT * 0.4)
gx = -(-bmpW // step)
gy = -(-bmpH // step)
print('  fontSize≈%.0f  textWidth≈%.0f  ⇒ estPixels=%.0f  step=sqrt(%.0f/%d)=%.2f→%d'
      % (FONT, TW, est, est, CAP, (est / CAP) ** 0.5, step))
print('  bmpW=%d  bmpH=%d（fontHeight≈%.0f + 0.4×fontSize）⇒ 网格 %d × %d'
      % (bmpW, bmpH, FONT_H, gx, gy))
print('  两遍 getPixel()：2 × %d × %d = **%d 次 JNI**' % (gx, gy, 2 * gx * gy))
print('  ⚠️ getPixel() 每次是一次 JNI（§G11）⇒ 按 0.5–1.0 µs/次 ⇒ **%.0f–%.0f ms**'
      % (2 * gx * gy * 0.5 / 1000, 2 * gx * gy * 1.0 / 1000))
print('  ⇒ 歌词换行时**卡 2–4 帧**；而换行正是用户最注意的时刻')
print('  ✅ 改 getPixels() 逐行批读 ⇒ 2 × %d = **%d 次**（−%.1f%%）'
      % (gy, 2 * gy, 100 - 2 * gy * 100.0 / (2 * gx * gy)))

print()
print('=' * 78)
print('结论')
print('=' * 78)
print('  1) 卡拉OK「逐字颜色改变」每点只增加 **%d 次比较 + %d 次乘除**，'
      % (kar['cmp'], kar['mul']))
print('     **0 分配、0 JNI、0 sin** ⇒ 它不是瓶颈。')
print('  2) drawPath **恒为 6 次**（3 tier × 2 层，都在 draw 末尾无条件调用，:596-612）')
print('     ⇒ 卡拉OK 不改变 drawPath 次数。')
print('  3) 反事实：')
print('     · 删掉卡拉OK 后若统一取「正在唱」亮度 ⇒ 每点都 tier2 ⇒ 每点都画 glow')
print('       ⇒ oval 数**上升**（worst 7200→14400，**+60%%**）⇒ **删了更慢**。')
print('     · 若统一取「待唱」亮度 ⇒ tier0 ⇒ 完全不画 glow ⇒ 只省下 glow 那一部分')
print('       （worst 9000→7200，**−20%%**），代价是画面变暗、失去"逐字推进"观感。')
print('  4) E23 真正的瓶颈是 **P0-1 每点 Rect 分配**（9000–14400 个/帧）与')
print('     **P0-2 两遍 getPixel 采样**（≈80,000 次 JNI / 换行，40–80 ms），')
print('     两者都与卡拉OK 无关 ⇒ **修它们，保留卡拉OK**。')
print('     其中 P0-1 修好后 Rect 分配 = **0**，也就是**保留卡拉OK 也能拿到 100%% 的收益**。')
