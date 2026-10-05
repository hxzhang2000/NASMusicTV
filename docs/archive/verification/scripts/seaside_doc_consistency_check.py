import re, io, sys

HTML = r"D:\hxzhang\MyGithubSoftware\NasAudio\NASMusicTV\docs\seaside-preview.html"
DOC  = r"D:\hxzhang\MyGithubSoftware\NasAudio\NASMusicTV\docs\archive\seaside-visualizer-plan.md"

html = io.open(HTML, encoding="utf-8").read()
doc  = io.open(DOC,  encoding="utf-8").read()

# 只取活代码（去掉注释），避免注释里的旧值造成误判
code = re.sub(r'/\*.*?\*/', '', html, flags=re.S)
code = re.sub(r'//[^\n]*', '', code)

# 文档断言的关键常量 -> 期望值
EXPECT = {
    "SHORE_K": "0.620", "SAND_TEX_TOP": "0.46", "SWASH_REACH": "0.105",
    "WAVE_SPAWN_DEPTH": "0.66", "WAVE_SPAWN_FADE_ADV": "0.20",
    "WAVE_POOL": "6", "WAVE_GAP_MS": "2360", "WAVE_T_MIN": "4200", "WAVE_T_MAX": "5800",
    "FOLLOW_T_SLOW": "2.0", "WAVE_FOLLOW_Y": "0.62", "WAVE_SPAWN_RAMP_MS": "700",
    "WAVE_FADE_MS": "900", "SWASH_LEAD_ADV": "0.85", "SWASH_RUNUP_MS": "900",
    "SWASH_RETREAT_MS": "440", "SWASH_RETREAT_MAX": "1600", "MORPH_START": "0.55",
    "CREST_DRIFT_ADV": "0.35", "FOAM_FAR_POW": "1.6", "W_REACH_Y": "1.00",
    "CNX": "20", "CNY": "9", "HOLE_MAX": "18", "HOLE_PERIOD_MS": "1100",
}

print("=== 常量：HTML 活代码里的实际值 vs 期望 ===")
bad = 0
for k, v in EXPECT.items():
    m = re.search(r'\bconst\s+' + k + r'\s*=\s*([-\d.]+)', code)
    actual = m.group(1) if m else "(未找到)"
    ok = actual == v
    if not ok:
        bad += 1
    print("  %-20s 期望 %-8s 实际 %-10s %s" % (k, v, actual, "OK" if ok else "<<< 不一致"))

print()
print("=== 已删除的标识符：不应出现在 HTML 活代码里 ===")
for dead in ["waveFrontY", "waveDepthK", "WAVE_SPACING_Y", "MIN_ARRIVE_GAP_MS",
             "CONVERGE_K", "crestK", "WAVE_REACH_Y"]:
    n = len(re.findall(r'\b' + dead + r'\b', code))
    print("  %-20s 活代码引用 %d %s" % (dead, n, "OK" if n == 0 else "<<< 仍在"))

print()
print("=== 已删除的标识符：文档不应再作为「现有机制」引用 ===")
for dead in ["waveFrontY", "waveDepthK", "WAVE_SPACING_Y", "MIN_ARRIVE_GAP_MS", "CONVERGE_K"]:
    n = len(re.findall(r'\b' + dead + r'\b', doc))
    print("  %-20s 文档出现 %d %s" % (dead, n, "(应仅在死常量/教训语境)" if n else "OK"))

print()
print("=== 关键机制串是否在 HTML 活代码里 ===")
MUST = [
    ("破碎线终点为 shoreYs", r'lerp\(spawnFar, shoreYs\[i\], tAdv\)'),
    ("tAdv 重参数化", r'const tAdv = adv \+ \(1 - adv\) \* morph'),
    ("绘制前浪只看 state", r'const isLead = \(w\.state === W_REACHED \|\| w\.state === W_FADING\)'),
    ("rag 判据已统一", r'const rag = \(\(lane & 1\) === 0\) \? ragA : ragB'),
    ("lane 按 serial", r'const lane = 1 \+ \(w\.serial % 3\)'),
    ("wiS 按 serial", r'const wiS = w\.serial & 3'),
    ("带宽下限 0.30", r'Math\.max\(0\.30, frayK \* envK \* slK\)'),
    ("非领头浪 kA 不吃音频", r'clamp\(amp \* 0\.55 \* fade, 0, 1\)'),
    ("非领头浪距离律 0.35 幂", r'isLead \? FOAM_FAR_POW : 0\.35'),
    ("冲流 swashT 累积", r'if \(w\.y >= SWASH_LEAD_ADV\) w\.swashT \+= dms'),
    ("交接排除滩上 owner", r'const incoming = \(owner && !ownerBeach\) \? owner : null'),
    ("退水守卫", r'if \(!stillBeach && retreatT < 0\) retreatT = 0'),
    ("出生淡入含行程", r'clamp\(w\.y / WAVE_SPAWN_FADE_ADV, 0, 1\)'),
]
for label, pat in MUST:
    print("  %-26s %s" % (label, "OK" if re.search(pat, code) else "<<< 缺失"))

print()
print("常量不一致数: %d" % bad)
