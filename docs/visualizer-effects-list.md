# 可视化效果列表

> **版本**：v2.38.6（versionCode 175）
> **更新日期**：2026-10-10
> **来源**：`app/src/main/java/com/nasmusic/tv/data/model/AppSettings.kt` — `VisualizerTheme` 枚举

共 **22 个**效果（2026-09-27 移除 11 个：37 → 26，详见 `docs/technical-overview.md` §10.190；2026-09-28 新增 DNA：26 → 27，§10.193；2026-09-28 新增「世界」：27 → 28，§10.196；2026-10-02 新增「星空星轨」：28 → 29，§10.206；2026-10-04 新增「海边」：29 → 30，§10.209；2026-10-05 移除 9 个（见下方「已移除效果」）：30 → **21**，§10.212；2026-10-09 新增「明月」E44：21 → **22**，方案见 `docs/moonlit-visualizer-plan.md`，✅ 该套 T0–T11 **已全部落地并于 2026-10-10 真机验收通过**（⚠️ 月相按真机裁决**固定满月**，不随农历日期变化，偏差 D35））。E41「世界」已于 2026-09-29 重写为 **three-globe 3D 地球版**（WebView + WebGL，完全离线；复用 E41 序号，仍 21 项，§10.197），并于 **2026-10-07 升入 `Tier.ULTRA`**（§10.215，电视端 WebView 只画首帧的回退代价）。⚠️ 旧 2D 海岸线版 `WorldRenderer.kt` **已整体删除**（`546dd7c`，2026-10-06）—— 早期版本这里写的"保留在源码但不再被引用"已不成立。

> ⚠️ **档位标题的措辞已部分过时**：ADV 档原写「需粒子预算，MEDIUM 及以上」，那是 2026-10-01 之前的门控口径。
> 自 §10.205 起 ADV 档的门槛是「该效果**是否真的读取** `ctx.quality.maxParticles`」
> （枚举第 4 参 `needsParticleBudget`），**不是**按 tier 一刀切 ⇒ 本档 **11** 套里有 **10** 套三档全可用，
> 仅 `E14 节拍烟花` 真读预算、LOW 档被挡（`E41 世界` 也读预算，但它已在下方 ULTRA 档）。逐项取值见 `AppSettings.kt` 的枚举 KDoc。
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
| E29 | ORBITAL_RINGS | 太阳系 |
| E39 | PHOTO_WALL | 照片墙 |
| E40 | DNA | DNA 双螺旋 |
| E42 | STAR_TRAILS | 星空星轨 |
| E44 | MOONLIT | 明月 |

## ULTRA 档（`supports()` 要求 `allowFramebuffer` ⇒ 仅 HIGH 画质档提供）

| 序号 | 枚举键 | 显示名 |
|---|---|---|
| E18 | MILKDROP_FEEDBACK | 反馈残像 |
| E41 | WORLD | 世界 |

> ⚠️ **E41 的归档理由与 E18 不同**：`allowFramebuffer` 门控对两者共用，但 E41 提 ULTRA 不是因为帧缓冲，
> 而是 2026-10-06 真机故障 —— **电视端 WebView 只画首帧**（后续帧不再回调），MEDIUM / LOW 档用户会看到一块静止的地球。
> 所有者裁决改为**仅最高画质档提供**（§10.215）。它的 `needsParticleBudget = true` 照实保留
> （`WorldGlobeRenderer` 确实读 `ctx.quality.maxParticles`，`ParticleBudgetGateTest` 的源码扫描要求它为 true）。

## 实现与改造状态（2026-10-08 按源码逐项核对）

> 数据来源：渲染器类头 = `grep -rn ": RendererFx()\|: VisualizerRenderer" app/src/main`；
> 后处理覆盖 = `visualizer/fx/FxCoverageScanTest.kt` 的 `covered`（**15**）/ `exempt`（**7**）两份名单；
> 单测 = `app/src/test/java/com/nasmusic/tv/visualizer/`；
> 改造状态 = `docs/archive/visualizer-texture-upgrade-plan.md` §12.2 矩阵（**按效果**计，与上面「按类头」计的 15 不同口径）。
> ⛔ 表内路径均相对 `app/src/main/java/com/nasmusic/tv/`；行号 = 类头所在行（2026-10-08 实测），会随编辑漂移。

| 序号 | 显示名 | 渲染器（`file:line`） | LOW | 继承基类 | §六 质感改造 | 专用单测 |
|---|---|---|:--:|:--:|---|---|
| E05 | 圆形频谱环 | `renderers/BasicRenderers.kt:28` | ✅ | ✅ | ✅ 批次 A 全部落地 | ⛔ 无（仅 `covered` 名单） |
| E13 | 液态网格 | `renderers/AdvancedRenderers.kt:48` | ✅ | ✅ | ✅ 批次 A 全部落地 | ⛔ 无（仅 `covered` 名单） |
| E14 | 节拍烟花 | `renderers/ParticleRenderers.kt:30` | ⛔ | ✅ | ✅ 批次 B 全部落地 | `BeatFireworkTest` |
| E15 | 液态涟漪 | `renderers/AdvancedRenderers.kt:275` | ✅ | ✅ | ✅ 批次 A 全部落地 | ⛔ 无（仅 `covered` 名单） |
| E16 | 数字雨 | `renderers/AdvancedRenderers.kt:504` | ✅ | ✅ | ✅ 批次 B 全部落地 | `MatrixRainTest` |
| E17 | 星座 | `renderers/AdvancedRenderers.kt:904` | ✅ | ✅ | ✅ 批次 A 全部落地 | ⛔ 无（仅 `LowTierElementBudgetTest`） |
| E18 | 反馈残像 | `renderers/UltraRenderers.kt:70` | ⛔ | ✅ | ✅ 批次 B 全部落地 | `MilkdropTest` |
| E23 | 歌词点阵 | `renderers/LyricsDotMatrixRenderer.kt:63` | ✅ | ✅ | ✅ 批次 B 全部落地 | `LyricsDotMatrixTest` + `LowTierElementBudgetTest` |
| E24 | 心跳 | `renderers/EcgWaveRenderer.kt:78` | ✅ | ✅ | ✅ 批次 A 全部落地 | `EcgWaveformTest` |
| E25 | 催眠 | `renderers/HypnoticFunctionRenderer.kt:70` | ✅ | ✅ | ✅ 批次 B 全部落地 | `Hypnotic{Function,Dissolve,Layout,Phase,Schedule}Test` + `FunctionLibraryTest` |
| E29 | 太阳系 | `renderers/BatchTwoRenderers.kt:92` | ✅ | ⛔ 欠账 | ✅ §C1 六条（含 P0 星野降级）全部落地 | `OrbitalProceduralEnhanceTest` + `OrbitalStarFieldTest` |
| E30 | 雷达 | `renderers/BatchThreeRenderers.kt:48` | ✅ | ✅ | ✅ 批次 A 全部落地 | `RadarSweepTest` |
| E33 | 齿轮 | `renderers/BatchFourRenderers.kt:113` | ✅ | ⛔ 欠账 | ⛔ 未开工（T5.2） | ⛔ 无 |
| E35 | 光轴 | `renderers/BatchFourRenderers.kt:1363` | ✅ | ✅ | ✅ 批次 B 全部落地 | `LightBeamsTest` |
| E37 | 分子 | `renderers/MoleculeRenderer.kt:53` | ✅ | ⛔ 欠账 | ⛔ 未开工（T5.3） | `MoleculeLibraryTest` + `MoleculeMotionTest` |
| E38 | 怀旧 | `renderers/VintageTvRenderer.kt:63` | ✅ | ⛔ 有意排除 | ⛔ 未开工（T5.4） | ⛔ 无（`VintageBatchMathTest` 至今未创建） |
| E39 | 照片墙 | `visualizer/photo/PhotoRenderer.kt:49` | ✅* | ⛔ 有意排除 | ⛔ 未开工（T5.5） | `photo/` **13** 个测试类 |
| E40 | DNA 双螺旋 | `renderers/DnaRenderer.kt:109` | ✅ | ⛔ 欠账 | ⛔ 未开工（T5.6；星野同源改造同样未动） | ⛔ 无（星野由 `OrbitalStarFieldTest` 扫） |
| E41 | 世界 | `renderers/WorldGlobeRenderer.kt:62` | ⛔ | ⛔ 有意排除（View 型） | ⛔ 不改代码（§C7 已整节重评，只做上机验收） | `WorldLogicTest` + `WorldMapDataTest` + `GlobeAssetsHygieneTest` |
| E42 | 星空星轨 | `renderers/StarrySkyRenderer.kt:113` | ✅ | ✅（建档即继承） | —— 本方案范围外 | `StarrySkyTest` |
| E43 | 海边 | `renderers/SeasideRenderer.kt:155` | ✅ | ✅（建档即继承） | —— 本方案范围外 | `SeasideTest` + `SeasideWavesTest` + `SeasideAudioMapTest` + `SeasideOpBudgetTest` |
| E44 | 明月 | `renderers/MoonlitRenderer.kt:104` | ✅ | ✅（建档即继承） | —— 本方案范围外 | ✅ **T0–T11 全部落地，2026-10-10 已在创维 API 22 真机判读通过**（裁决「其他没有问题」）：**月相按真机裁决固定满月**（`MoonlitRenderer.DISPLAY_ILLUM`，偏差 **D35** —— ⛔ 历算层 `MoonPhase` 与它的 G2/G12 一字未动，天平动仍按真实日期驱动圆盘重烘）+ 月盘烘焙（`MoonDiskBake`，含 §5.4 程序化降级）+ 加法过曝芯 + 相位阴影与新月夜极淡边缘 + 夜空/海体色尺（`MoonSeascape`）+ 星野 + 层 3 月晕 + 云场与 `occl`（`MoonClouds`，含 §6.6 确定性过境）+ 光柱/粼光/地平带（`MoonWater`）+ 音频映射（`MoonAudio`）+ 每元素 ops/铺屏账（`MoonOpBudget`，上限 `3.45 / 4.00 / HIGH 不设绝对上限`）。门：`MoonPhaseTest` / `MoonDiskBakeTest` / `MoonOpBudgetTest` / `MoonSeascapeTest` / `MoonlitStarfieldTest` / `MoonlitSkySeaTest` / `MoonlitTest` / `MoonlitWaterTest` / `MoonlitAudioTest` / `MoonlitDtClockTest` / `MoonPhaseShadowTest` / `VisualizerThemeStringsGateTest`（G3 云场对账跑在 `MoonlitCloudBaseline` 再生基线上）。⛔ **未留数的三笔**：U1 fps / U2 首帧延迟 / U3 native 堆增量在真机判读时**未采集**（所有者给的是整体定性裁决），真实铺屏 ≈4.48 屏下的帧率因此仍无数字 ⇒ 若要用它做决策须按 §十三 口径重测。相位阴影与新月夜极淡边缘因 D35 在生产上**自然早退**（⛔ 未删代码、未加开关，解除 D35 即恢复）。已进 `CHANGELOG` v2.38.6 |

> ⚠️ **三个「⛔ 欠账」与「⛔ 有意排除」不是一回事**：E38 / E39 / E41 是**按设计不继承基类**
> （E38 后处理与内容交错 + 画面已定稿、E39 共享后处理会盖住照片、E41 是 `AndroidView` 型、`draw` 空实现）；
> E29 / E33 / E37 / E40 是**批次 C 至今 0 套迁移**留下的欠账 ⇒ 这四套仍各自持有 `lastMs` 自算 `dt`、
> 自己的 `rng` / 内联 LCG、自己的后处理释放。⛔ 别把这四套当成裁决排除写进豁免理由。
>
> 🔴 **验收状态（截至 2026-10-08）**：上表的「✅ 全部落地」= **代码已落盘**，
> 而 `docs/archive/visualizer-texture-upgrade-plan.md` §11.1/§11.2 的 **22 条真机观感判据 0 条打勾**
> ⇒ 改造后的效果**从未在真机逐套看过**（唯一例外是 E16 数字雨，因排查崩溃被反复上机）。
> ⚠️ 该方案文档已于 **2026-10-08 经所有者裁决整篇归档**（「我判断是没啥可做的了」），归档时**这两类欠账原样保留**：
> 批次 C 的 6 套质感改造（T5.2–T5.7）+ 27 条未勾验收判据（§11.1 余 5 条 + §11.2 全 22 条）——
> 逐条清单见 `docs/archive/README.md` 第五轮。⛔ **本表的「§六 质感改造」列因此不再有新行变化**；
> 若日后恢复，判据仍在原 §十一，不必重写。
>
> `*` E39 的三档可用性成立，但它还多一道**列表级**门控 —— 见下「门控的三个特殊项」第 1 条。

## 门控的三个特殊项（容易漏）

1. **E39 照片墙是唯一有「列表级」门控的效果**：`VisualizerTheme.selectable(photoWallAvailable)`
   在三个照片来源开关**全关**时把 `PHOTO_WALL` 从列表里过滤掉（指示器不显示、左右键也切不到，
   `AppSettings.kt:325` / `VisualizerRendererFactory.kt:74`）。其余 21 套恒在列表里（受画质档约束）。
2. **E14 节拍烟花**：ADV 档里唯一 `needsParticleBudget = true` 且非 ULTRA 的效果
   ⇒ `LOW.maxParticles = 0` 时被 `supports()` 挡下（`AppSettings.kt` 的 `VisualQuality.supports`）。
3. **E18 / E41**：ULTRA 档由 `allowFramebuffer` 决定，该字段只有 `HIGH` 为 `true`
   ⇒ MEDIUM / LOW **一律不提供**（与粒子预算无关）。⛔ 没有任何「按设备能力隐藏效果」的过滤逻辑
   （`availableThemes()` 只看画质档 + 照片来源）⇒ E41 在不支持 WebView 上屏的设备上仍会出现于 HIGH 档。

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

> 老用户存过的已移除主题键经 `LEGACY_MAP`（`AppSettings.kt:274-308`，**26** 个键 =
> 5 个改名前的老名 + `AUTO_DIRECTOR` + 两批共 **20** 个已删效果）自动回落到默认 `CIRCULAR_RING`（圆形频谱环）。
> ⚠️ 本仓 `fromKey` 末位 `?: Default` 本来就等价，`LEGACY_MAP` 的价值是**固化迁移意图**（清单靠人工维护：
> `VisualizerThemeTest` 只断言 5 个改名前的老名（`:20-27`）+ **21 项 / `ordinalLabel` 唯一 / 显示名唯一 /
> 档位门控 / 照片墙可见性**（`:51`、`:89-90`、`:96`、`:103`），**不核对已删名册是否全部进表** ⇒ 删效果时必须手工补这一行）。⚠️ 2026-10-05 刚删那 9 项时**没有**同步补录
> （当时靠末位 fallback 兜住，`docs/technical-overview.md` §10 记为"有意的不一致、留待裁决"），
> 2026-10-08 由 `49784b5` 补齐 ⇒ 两批现在口径一致。
> 已移除序号位保留空号（E01/E06/E08/E09/E10/E21/E22/E26/E27/E28/E36 + E03/E07/E11/E12/E19/E20/E31/E32/E34），`ordinalLabel` 唯一性不受影响。
