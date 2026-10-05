# 可视化效果列表

> **版本**：v2.38.2（versionCode 171）
> **更新日期**：2026-10-05
> **来源**：`app/src/main/java/com/nasmusic/tv/data/model/AppSettings.kt` — `VisualizerTheme` 枚举

共 **21 个**效果（2026-09-27 移除 11 个：37 → 26，详见 `docs/technical-overview.md` §10.190；2026-09-28 新增 DNA：26 → 27，§10.193；2026-09-28 新增「世界」：27 → 28，§10.196；2026-10-02 新增「星空星轨」：28 → 29，§10.206；2026-10-04 新增「海边」：29 → 30，§10.209；2026-10-05 移除 9 个（见下方「已移除效果」）：30 → **21**，§10.212）。E41「世界」已于 2026-09-29 重写为 **three-globe 3D 地球版**（WebView + WebGL，完全离线；复用 E41 序号，仍 28 项，§10.197）——旧 2D 海岸线版保留在源码但不再被引用。

> ⚠️ **档位标题的措辞已部分过时**：ADV 档原写「需粒子预算，MEDIUM 及以上」，那是 2026-10-01 之前的门控口径。
> 自 §10.205 起 ADV 档的门槛是「该效果**是否真的读取** `ctx.quality.maxParticles`」
> （枚举第 4 参 `needsParticleBudget`），**不是**按 tier 一刀切 ⇒ 本档 11 套里有 9 套三档全可用，
> 仅 `E14 节拍烟花` 与 `E41 世界` 真读预算、LOW 档被挡。逐项取值见 `AppSettings.kt` 的枚举 KDoc。
> ULTRA 档「需帧缓冲，仅 HIGH」仍然成立（由 `allowFramebuffer` 决定，与粒子预算无关）。

## BASIC 档（画质全档可用）

| 序号 | 枚举键 | 显示名 |
|---|---|---|
| E05 | CIRCULAR_RING | 圆形频谱环 |
| E24 | ECG_WAVE | 心跳 |
| E25 | HYPNOTIC_FUNCTION | 催眠 |
| E30 | RADAR_GRID | 雷达 |
| E33 | CONCENTRIC_GEARS | 齿轮 |
| E35 | LIGHT_BEAMS | 光轴 |
| E37 | MOLECULE | 分子 |
| E38 | VINTAGE_TV | 怀旧 |
| E43 | SEASIDE | 海边 |

## ADV 档（画质全档可用；2026-10-01 起按「是否真消耗粒子预算」逐项门控）

| 序号 | 枚举键 | 显示名 |
|---|---|---|
| E13 | LIQUID_GRID | 液态网格 |
| E14 | BEAT_FIREWORK | 节拍烟花 |
| E15 | LIQUID_RIPPLE | 液态涟漪 |
| E16 | MATRIX_RAIN | 数字雨 |
| E17 | CONSTELLATION | 星座 |
| E23 | LYRICS_DOT_MATRIX | 歌词点阵 |
| E29 | ORBITAL_RINGS | 轨道 |
| E39 | PHOTO_WALL | 照片墙 |
| E40 | DNA | DNA 双螺旋 |
| E41 | WORLD | 世界 |
| E42 | STAR_TRAILS | 星空星轨 |

## ULTRA 档（需帧缓冲，仅 HIGH）

| 序号 | 枚举键 | 显示名 |
|---|---|---|
| E18 | MILKDROP_FEEDBACK | 反馈残像 |

## 已移除效果（v2.37.6 移除 11 个；v2.38.2 再移除 9 个）

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
| E03 | TUNNEL_FLY | 隧道穿越 |
| E07 | FREQUENCY_MOUNTAIN | 频率山峦 |
| E11 | GALAXY_SPIRAL | 星系螺旋 |
| E12 | SPECTRO_WATERFALL | 频谱瀑布 |
| E19 | PARTICLE_TEXT | 粒子文字 |
| E20 | PLASMA_FLOW | 等离子流场 |
| E31 | ORIGAMI_POLY | 折纸 |
| E32 | STAIRCASE_WAVE | 阶梯 |
| E34 | FRACTAL_TREE | 分形 |

> 老用户存过的已移除主题键经 `LEGACY_MAP` 自动回落到默认 `CIRCULAR_RING`（圆形频谱环）。
> 已移除序号位保留空号（E01/E06/E08/E09/E10/E21/E22/E26/E27/E28/E36 + E03/E07/E11/E12/E19/E20/E31/E32/E34），`ordinalLabel` 唯一性不受影响。
