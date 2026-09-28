# 可视化效果列表

> **版本**：v2.37.6（versionCode 168）
> **更新日期**：2026-09-28
> **来源**：`app/src/main/java/com/nasmusic/tv/data/model/AppSettings.kt` — `VisualizerTheme` 枚举

共 **28 个**效果（2026-09-27 移除 11 个：37 → 26，详见 `docs/technical-overview.md` §10.190；2026-09-28 新增 DNA：26 → 27，§10.193；2026-09-28 新增「世界」：27 → 28，§10.196）。

## BASIC 档（画质全档可用）

| 序号 | 枚举键 | 显示名 |
|---|---|---|
| E03 | TUNNEL_FLY | 隧道穿越 |
| E05 | CIRCULAR_RING | 圆形频谱环 |
| E07 | FREQUENCY_MOUNTAIN | 频率山峦 |
| E24 | ECG_WAVE | 心跳 |
| E25 | HYPNOTIC_FUNCTION | 催眠 |
| E30 | RADAR_GRID | 雷达 |
| E32 | STAIRCASE_WAVE | 阶梯 |
| E33 | CONCENTRIC_GEARS | 齿轮 |
| E34 | FRACTAL_TREE | 分形 |
| E35 | LIGHT_BEAMS | 光轴 |
| E37 | MOLECULE | 分子 |
| E38 | VINTAGE_TV | 怀旧 |

## ADV 档（需粒子预算，MEDIUM 及以上）

| 序号 | 枚举键 | 显示名 |
|---|---|---|
| E11 | GALAXY_SPIRAL | 星系螺旋 |
| E12 | SPECTRO_WATERFALL | 频谱瀑布 |
| E13 | LIQUID_GRID | 液态网格 |
| E14 | BEAT_FIREWORK | 节拍烟花 |
| E15 | LIQUID_RIPPLE | 液态涟漪 |
| E16 | MATRIX_RAIN | 数字雨 |
| E17 | CONSTELLATION | 星座 |
| E23 | LYRICS_DOT_MATRIX | 歌词点阵 |
| E29 | ORBITAL_RINGS | 轨道 |
| E31 | ORIGAMI_POLY | 折纸 |
| E39 | PHOTO_WALL | 照片墙 |
| E40 | DNA | DNA 双螺旋 |
| E41 | WORLD | 世界 |

## ULTRA 档（需帧缓冲，仅 HIGH）

| 序号 | 枚举键 | 显示名 |
|---|---|---|
| E18 | MILKDROP_FEEDBACK | 反馈残像 |
| E19 | PARTICLE_TEXT | 粒子文字 |
| E20 | PLASMA_FLOW | 等离子流场 |

## 已移除效果（v2.37.6）

| 序号 | 枚举键 | 显示名 |
|---|---|---|
| E01 | IMMERSIVE_BLOOM | 沉浸辉光 |
| E06 | RADIAL_BURST | 径向星芒 |
| E08 | PARTICLE_STORM | 粒子风暴 |
| E09 | PARTICLE_GALAXY | 粒子银河 |
| E10 | MIRROR_KALEIDO | 万花筒 |
| E21 | PRISM_HOLO | 棱镜彩虹 |
| E22 | AURORA | 极光 |
| E26 | VECTOR_WAVES | 声弦 |
| E27 | PULSING_POLYGONS | 几何环 |
| E28 | BAUHAUS_SHAPES | 构成 |
| E36 | FERMAT_SPIRAL | 螺旋 |

> 老用户存过的已移除主题键经 `LEGACY_MAP` 自动回落到默认 `CIRCULAR_RING`（圆形频谱环）。
> 已移除序号位保留空号（E01/E06/E08/E09/E10/E21/E22/E26/E27/E28/E36），`ordinalLabel` 唯一性不受影响。
