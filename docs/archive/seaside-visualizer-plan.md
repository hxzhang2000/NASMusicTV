# 海边 频谱效果 开发方案（Seaside）

> **状态**：待开工。方案可直接进入实施。**原型**：`docs/archive/seaside-preview.html`（同目录）已迭代至「反馈八·10」定稿，本文档自 **v1.2** 起**以该原型为准**——架构、常量、机制全部对齐原型（§4/§5/§7/§9/§10/§12/§14 已重写为原型形态）；Kotlin 端口照原型逐函数搬移，不再按旧架构猜测。⭐⭐ **本方案的一条浪带两个位置量**（破碎线 breaker / 冲流线 swash，各管一件事）是理解全文的钥匙，见 §4.3.2。
> ⛔ **两处「看起来是 bug 的正确写法」**（照抄时最容易改回去）：① 后浪**必须全程自由推进**，⛔ 任何位置钳位都会让它永远到不了滩；② 退水**必须与泡沫消散并行**，⛔ 先后次序会让「浪花消失」与「海水回退」脱节。§九 列出 9 条浪机制专项红线。
> ⚠️ **实测 vs 提案的区分**：本文档里所有 `harness 实测` 数值来自 `docs/archive/verification/scripts/` 下的帧驱动脚本（**只给几何/时序，不给画面**）；所有「读作『…』」的判断来自 **owner 目测**（浏览器全程不可用）；标 `⚠️ 未解决 / 未验证` 的项目见 §12.5，⛔ 不得在别处写成已完成。
>
> **基线**：`HEAD = bb3a603`（v2.38.0，versionCode 169）。效果总数 **29 套**（`AppSettings.kt` 枚举 29 项，末位 `STAR_TRAILS` `:210`）；门禁断言 `VisualizerThemeTest` 7 处硬计数「29/28」、`FxCoverageScanTest` 1 处「29」——**均按 `bb3a603` 复核**。`technical-overview.md` 最新小节 **§10.207** ⇒ 本案新增 **§10.208**。`docs/archive/starry-sky-visualizer-plan.md` 是**已归档**的同类方案，其教训见 §三，本方案不复用其文档。
>
> **红线摘要**：纯 `DrawScope` + `nativeCanvas`，⛔ **禁止** shader / AGSL `RuntimeShader` / `RenderEffect` / OpenGL / `BitmapShader`（`docs/visualizer-texture-upgrade-plan.md:8-10,994,3276`）。海浪必须是**真实动态**（§4.3），⛔ 不得用静态纹理平移冒充。`draw()` 内零分配；每帧增量必须 `× fx.dt`；⛔ 不得覆盖 `RendererFx` 的三个 `final` 模板方法。

### 文档版本跟踪

| 文档版本 | 日期 | 变更摘要 | 状态 |
|---|---|---|---|
| v1.3 | 2026-10-04 | **⭐ 新增 §4.9「API 22 提交预算与合批架构」——本轮唯一的高风险裁决，优先级高于任何视觉微调**。起因：owner 授权自主决策后，agent 第一次真正看到真实帧并量出桌面 Edge 下 `avgMs 44.22`/帧（超 60fps 预算 3 倍），随即对 Kotlin 端口做独立架构评审，结论是**按原稿实现不可行**：① **标定**——E42 星空是本项目唯一真机判通过的同类，实测 **≈200 提交/帧 ≈2.0 屏填充 = 29.7 fps**，这才是可信预算（并据此判定 `LowTierElementBudgetTest.ABS_BUDGET=400` 其实只是「不许个位数」门、**不是** 30fps 门，且该测试硬编码只扫 3 个渲染器 ⇒ 对本效果**零覆盖**）；本方案原稿 HIGH 档实测 **≈830 提交 / 6.9 屏 ⇒ 推算 8–10 fps**。② ⭐ **Dalvik 前提**——API 22 电视默认无 JIT，§4.3.6 那个「97 `createLinearGradient` = +0.1ms」是桌面数字、**作废**；须同时守**提交数**与**解释调用数**两个预算。③ **四处结构性必改**：`drawWetWash`+`drawSheen` 194→**1**（一条 path + 缓存竖向渐变，逐列 `wetEdge` 变下轮廓、水线变上轮廓）；飞沫 180 + 残沫 52×3 的 ~500 次极小点 → **`nativeCanvas.drawPoints`** 共 **4** 次（E42 先例，⛔ 不烘位图，为保留逐点 alpha）；`drawDisturbance` **全档删除** + 外海泡沫 22→8 且合批成一条 path；焦散射线与 56 亮结**仅 HIGH**、⛔ 补上 §4.7 LOW 行漏掉的焦散=0。改造后 HIGH **≈95 提交 / 2.6 屏**，落进 E42 已验证包络。④ ⛔ **`clipPath` 禁令范围纠错**——原稿只禁圆角 clip，但本项目在创维 rtd299o 真机三次复现 hwui SIGSEGV，**禁令是整个 `clipPath(`**；5 处波浪形水线 clip 改填充几何。⑤ ⭐ **四条会让门禁静默失效的陷阱**（E42 已踩）：绘制辅助函数必须声明 `DrawScope.` 接收者否则**约 15 个函数逃过零分配扫描**；类头必须写一行否则 `RendererBaseContractTest` **不报错地**把本渲染器剔除出扫描；禁 `sortedBy`/`map`；`addOval` 只用 float 重载（否则每帧 378 个 `Rect`）。⑥ ⭐ **新增 G11 提交预算 / G12 填充预算 / G13 native 堆**三道**纯函数**门（含负向自证）——因真机本轮不可达（`adb connect` 超时 10060）且渲染器无法在 JVM 中 `new`，把计数做成纯函数才能让性能在**构建期失败**而非真机目测。偏差 D1–D6 记入 §12.4，其中 D1 = **拆出 `SeasideWaves.kt`**（模拟核心纯 Kotlin、零 Android import，可直接单测、可与视觉层并行实施）。 | 待评审 |
| v1.2 | 2026-10-03 | **同步至 `docs/archive/seaside-preview.html` 原型定稿（反馈八·10），并把先前只改头部、正文仍描述已删除架构的部分补齐**：① ⭐ **浪从「多正弦水位场 + 固定泳道破碎带」改为「离散浪队列」**——`WAVE_POOL=6` 槽位状态机（spawn→ADVANCING→REACHED→FADING→空槽），出生淡入、到滩消散；⛔ **无中途被追上/顶替路径**（构造上不可能：两档 `T` 区间不重叠，⭐ 本轮根据已从「散布 < 出浪间隔」换成「领队 4200–5800ms vs 跟随 8400–11600ms ⇒ 到达间隔恒 ≥2600ms」）；② ⭐ **冲流推进量改为队列级标量** `waterlineAdvance`，⛔ 不是「领头浪驱动」；③ ⭐ **自走时钟**——时间轴只吃 `dt`，音频完全出局、只驱动外观，⛔ 原「`bassRaw`+`beat` 打 `SWELL_LAYERS[0]` 推进冲量」的映射已删，`setImp` 仅加成领头浪振幅；④ ⭐ **S 曲线随机浪脊**——域扭曲 fBm `crestProfile` 取代三正弦（八度比 2.07/衰减 0.60、`fbmNorm` 拉满、相邻浪**共用同一条剖面**只在 `CREST_LANE_SHIFT` 上错开）+ 破碎前缘三件套；⑤ ⭐ **swash 周期**——上涌→到滩→消散→水线回退露湿沙→逐列干燥，干燥常数**挂在出浪间隔上**：`dryTau = clamp(2360·0.60, 420, 2600) = 1416ms`；湿痕**只在到滩那一瞬**写入；⑥ ⭐ **泡沫律**（概念移植自《WebGL Insights》ch.11 的 `foam_shore`，⛔ 不是代码移植——我们无 shader 无法线，用浪脊横向斜率作代理）：`slopeLaw`（`FOAM_SLOPE_GAIN 0.62 × 4.0` = 有效 2.48/px）为主 × `farLaw` 为次，⛔ 距离律**只作用在 alpha 上、绝不折进带宽**；⑦ ⭐ **烘焙 4 层沙纹理** `buildSandTexture`（湿→干渐变 / 宽柔起伏带 / 潮湿斑 / 像素级颗粒，每帧裁剪 blit）；⑧ ⭐ **泡沫蕾丝** `drawFoamLace`（u-v 网拓扑 resize 时烘好、端点共享 ⇒ 连成网眼）+ **焦散连通胞壁网** `buildCausticNet`（`CNX 20 × CNY 9`，⛔ 不周期环绕）；⑨ ⭐ **补浪调度按前浪进度**（`WAVE_FOLLOW_Y`）⇒ 稳态恒为「1 条在最前 + 1 条排队」，「两槽同框」帧占比 16% → **95%**；⑩ ⭐⭐⭐ **【本轮核心：反馈八·10 / 方案 3】一条浪带两个位置量，各管一件事**——**破碎线 breaker**（`spawnFar = h·(SHORE_K − WAVE_SPAWN_DEPTH=0.66) = −0.04h` → `shoreYs[i]`，行程 ≈**636px**，画白浪带、是「第二条浪全程可见」的依据）与**冲流线 swash**（`swashFrontY` → `wlineFull`，行程 ≈**81px** = `h·SWASH_REACH`，驱动湿沙/退水/干燥）。⭐ **交接不是位置突变，而是职责增加**：破碎线推进到 `adv=1` 时位置本就等于 `shoreYs`，且它**不消失**、只是开始同时定义冲流线（`y ≥ SWASH_LEAD_ADV=0.85` 起 `swashT` 累加 ⇒ 「破碎线抵滩」与「冲流满位」同一帧发生）；⛔⛔ **两种做法被所有者明确否掉**：⛔ **位置钳位**（曾用 `WAVE_SPACING_Y=0.55` 冻结后浪 ⇒ 其 `adv` **从未超过 0.6**、永远到不了滩、**67% 的绘制调用因 `kA` 过低被 cull**）与 ⛔ **把后浪压进水线空间**（方案 B：行程压到 81px ⇒ 整个藏进领头浪的带里（领头浪 `bwj` 中位 **1.17** + `SHORE_FOAM_BOOST` **1.55**）⇒ owner 报「**彻底看不到**」，尽管其 `kA` 0.29~0.38、`bwj` 0.35~0.41 **全部正常**——「数值全对、画面全无」）；⭐ 前缘参数化 `tAdv = adv + (1−adv)·morph`（`morph = smoothstep(MORPH_START=0.55, 1, adv)`）单调、终点 1、前段 ≡ `adv`；⛔ 不可写成 `lerp(spawnFar+crest, shoreYs, morph)`（那样前 55% 画面几乎不动 ⇒ owner 报「后浪浮现后等待在原位置，过一会突然快速冲出去」）；⛔ **`crest` 与 fray 项必须乘 `(1−morph)` 被吸收**（`shoreYs` 内已含前浪自己的曲线与 fray，**系数同为 0.42**），否则重复计入 ⇒ 交接又跳变；⑪ ⭐ **绘制上的「前浪」改为 `state ∈ {REACHED, FADING}`**（⛔ 旧写法 `w === leadWave()` 会让还在外海的浪提前切到水线尺度 ⇒ 整条带瞬移，实测交接跳变中位 **187px** / 最大 **387px**）；⑫ ⭐⭐ **交接比较必须在同一参数化空间**：`a` 与 `nextPush` **都取冲流 `adv`**；⛔⛔ 须排除**滩上** owner（否则改为「抵滩即起退水」后第一帧就取消退水），⛔⛔ 但⛔ **不得**写成 `front !== owner`（后浪进上涌区后自己成为 owner ⇒ 交接永不触发 ⇒ `retreatT` 永不重置 ⇒ 所有起退水守卫全被挡住 ⇒ 整轮退水被吞，实测 90 秒只剩 1 次）；⑬ ⭐ **退水双触发点 + 自适应时长**：① 抵滩即起（与消散**并行**，owner：「浪花消失同时海水回退」）+ ② 滩上最后一条消散完的兜底（⛔ 不可省，否则整轮退水被吞），⛔⛔ **两处守卫都必须含 `retreatT < 0`**（否则水线从已退回的 `meanAdv 0.0685` 被瞬间拽回满位 `0.9611` ⇒ **78.79px 单帧跳变**）；`SWASH_RETREAT_MS=440` 由**定值改为下限**，`retreatDur = max(440, min(1600, eta))`（实测退水 **14 次/90s**、中位 **1567ms / 83.7px** = 满程 94.5px 的 **89%**）；⑭ ⭐ **身份索引取代绘制次序下标**：`wiS = serial & 3` / `lane = 1 + serial % 3`（实测 **14/15** 条浪的 `wi` 会变；⛔⛔ `env` 的 hash 种子 `8100 + wi·53` 随 `wi` 变 ⇒ 噪声实现整个换掉 ⇒ 带宽花纹整片换掉 ⇒ 画面跳变，**主因**；改后泳道跳变 **0**）；⑮ ⭐⭐ **浪带宽度硬下限 `shrink = max(0.30, frayK·envK·slK)`**——无下限时 `envK = 0.02 + 0.98·pow(fbmNorm, 2.4)` 可趋 0 ⇒ 那一帧整条带宽度为 0 ⇒ 读作「浪突然消失」（实测塌陷率在 `adv 80~90%` 曾达 **48.5%**、平均 `bwj` 从外海 0.251 缩到 0.113，修后 **0%**；⚠️ 下限必须加在**乘积**上——最初只钳了最轻的 `frayK` 而漏掉真正的元凶）；⑯ ⭐ **非领头浪的 `kA = clamp(amp·0.55·fade)`**（⛔ 不吃音频门控、不吃近岸斜坡——它的浪花是**位置指示器**；`amp` 基线 0.30→0.46、系数 0.55 刻意低于领头浪的 `SHORE_FOAM_BOOST` 以保住主次；距离感改由**带宽**与**逐块 alpha** 承担）；`layerAlpha` 近shore项下限同时 0.22→0.32；⑰ ⭐ **出生淡入改为「时间 × 行程」双淡入**（`WAVE_SPAWN_RAMP_MS × WAVE_SPAWN_FADE_ADV=0.20`）——纯时间 700ms 在 ~10s 的一生里只占 7% ⇒ 读作「凭空出现」；⑱ ⭐ **`SAND_TEX_TOP` 0.62 → 0.46**：必须高于水线最大摆动 `SHORE_K − CREST_AMP_SHORE − 潮汐 = 0.484h`，否则退水时那段露出海水底色——owner 报的「沙与底色的交界」与「退水退到快中间就不退了」**两条是同一根因**；⑲ ⭐ 常量/表达式更新：`WAVE_SPAWN_DEPTH`（`waveDepthK` 第一项已删，名字**不带** `_K`）、`MORPH_START 0.55`、`SWASH_LEAD_ADV 0.85`、`SWASH_RUNUP_MS 900`、`SWASH_RETREAT_MAX 1600`、`FOLLOW_T_SLOW 2.0`、`WAVE_FOLLOW_Y 0.30→0.62`、`FOAM_FAR_POW 2.4→1.6`（非领头浪另用 `0.35` 幂）、`CREST_DRIFT_ADV 0.35`（浪脊相位漂移由全局时间改挂**该浪自己的 `adv`**）；⑳ ⭐⭐ **新增 9 条浪机制专项红线**（§九：位置钳位 / 压进水线空间 / 量化时间换种子 / 取模回绕 / 带宽归零 / 依赖 `wi` / `SAND_TEX_TOP` / 交接同空间 / 退水守卫）**+ 5 条附带红线**（`leadWave()` 判定、`crest` 不乘 `(1−morph)`、`lerp` 旧式、`front !== owner`、兜底触发点②）；㉑ ⭐ **新增门禁 G10「两个位置量的结构不变式」**，扩充 G2（破碎线前缘映射 / 后浪自由推进 / 带宽不归零 / 外观不依赖 `wi` / 非领头浪 `kA` 不吃音频 / 交接同空间 / 退水双触发点 / 两档 `T` 区间不重叠）、G6（⛔ 无位置钳位、⛔ 起退水守卫含 `retreatT < 0` 两条源码扫）；⭐ **新增 V17 帧间数值清单**；㉒ ⭐ **验证脚本已归档**到 `docs/archive/verification/scripts/`（`seaside_wave_harness.js` / `seaside_hole_continuity_check.js` / `seaside_caustic_geom_check.py` / `seaside_visual_driver.mjs` / `html_syntax_check.js`），§7 路径全部改指新位置，⛔ 并**如实标注** harness 的 5b/5c 判定已失效、单列尖峰 10.77px 成因未定位、「后浪前锋泡沫」未验证、以及**全部画面结论由 owner 目测确认**（新开 §12.5）。**重写**：文档头部状态段、§1.2 C 行、§1.3 决策表、§4.1 L1–L5、§4.3（4.3.1 队列与自由推进 / ⭐**4.3.2 两个位置量**含 4.3.2.1 前缘映射、4.3.2.2 冲流推进量、4.3.2.3 退水双触发点 / 4.3.3 时钟（补 `swashT`）/ 4.3.4 浪脊与撕裂场 / 4.3.5 泡沫律 / 4.3.7 沙纹理 / 4.3.8 swash / ⭐**4.3.9 绘制侧三条硬不变量**）、§4.6、§4.7、§5.1/§5.2/§5.3/§5.5（常量表逐项核对原型 + 死常量表扩充）、§6 自检清单（新增 3b/3c/3d/3e）、§7（脚本路径迁移 + G2/G6 扩充 + ⭐G10 新增）、§9（⭐9+5 条专项红线）、§10（⭐V17 数值清单）、§11 裁决 1、§12.2/§12.3（T2.2/T2.3/T2.5/T2.6/T2.7/T2.12/T2.13/T2.16/T2.17 改写 + 新增 T2.13b/T2.18/T3.3）/ ⭐**§12.5 未解决清单**、§14.3 前言、§14.3.2（新增 `breakerFrontY`/`swashFullY` 与「两个位置量 → Kotlin 字段」的对应表）、§14.3.3、§14.3.4、§14.3.8、§15 S2/S3。**移除**（全部进 §5.5 死常量/死代码表，⛔ 端口不得复活）：`WAVE_SPACING_Y` / `MIN_ARRIVE_GAP_MS` / `waveFrontY` / `waveDepthK` / `CONVERGE_K` / `crestK` / `WAVE_REACH_Y`（与 `W_REACH_Y` 重复定义）/ `reachK[]`（无读取方）/ `drawSwellBands` 内的 `foamK` 局部量，以及先前轮次已清的 `SWELL_LAYERS` / `LAYER_PHASE_GAP` / `SWELL_PERIOD_MS` / `SWELL_TRAVEL` / `swellPhase` / `swashCurve` / `WAVE_SPAWN_K` / 三正弦族（`WAVE_K/WAVE_W/WAVE_A/WAVE_SGN/WAVE_PHI/WAVE_K4/WAVE_K5`）/ `LIP_A` / `CAUSTIC_SHORE_A` / `SWELL_BODY_FAR_POW` / 固定泳道 / 相位三角。**保留不变**：v1.1/v1.0 两行历史记录原样（其中提到的旧机制按「已删除」理解）。 | 待评审 |
| v1.1 | 2026-10-02 | **按第二、三张参考图补齐 5 个要素**并改正 1 处设计错误：① ⭐ **浪带 3 层 → 4 层**（图3 可见 4–5 道浪压叠），新增 `LAYER_PHASE_GAP` 使各层相位错开形成层叠、而非同时进退（§5.1/§4.3.2）；② ⭐ **改正层序强度方向**——初版写成「越靠海越强」，图3 实为**越靠岸越强**，⛔ 这是初版的实质设计错误，`layerAlpha` 已改为随层号递增，并加负向自证（G2）；③ ⭐ **浪心半透明双峰轮廓**（图2/图3）：带中部 alpha 必须低于前缘，⛔ 不可做成整条均匀白带，新增 `FOAM_CORE_A` + `foamCoreAt` + 单测断言（G2/§4.3.2）；④ ⭐ **飞沫与残沫**（图2/图3 独有）：浪向岸甩出细碎泡沫斑点 + 干沙零散孤立白点，新增 §4.3.4 与 `splashPoint`/`residuePoint` 确定性纯函数（⛔ 残沫不得连成纹路，否则退化为沙纹）；⑤ ⭐ **水色横向起伏**（图2 独有）：水面非单一垂直渐变，用 `waveFrontY` 的横向偏差调底色深浅，⛔ 否则读成「一整块塑料」（§4.3.1）；⑥ 干沙色按三图取中间调（`#C9A063`/`#C4A876`/`#D9BE8C`）。同步更新 §1.2 要素表（重编为 A–K）、§4.4、§4.6、§4.7 降级矩阵、§5 参数表、§6 自检清单（新增 5 条）、§七 G2、§12.3 任务（拆为 T2.4–T2.10）、§14.3 签名。 | 待评审 |
| v1.0 | 2026-10-02 | 初稿。基于参考图（俯拍海岸线）完成美术方向拆解 + 全链路现状核实。产出 15 章可开发方案。核心决策：① 效果编号 **E43**、枚举名 `SEASIDE`、显示名「海边」、`ordinalLabel="43"`、归 `Tier.BASIC`（理由见 §3.4）；② **浪是真实动态的**——采用「多正弦叠加的水位场 + 逐帧推进的破碎带（swell band）」双层机制，⛔ 不用静态纹理平移（§4.3）；③ 直接复用既有 `ProceduralTexture.WATER` / `CAUSTIC` 两张程序纹理（`fx/ProceduralTexture.kt:31,363,380`），**不新增 `Id`**（§3.2）；④ 与既有 3 套水/流体效果的差异化定位（§1.4）；⑤ 8 条单测门禁 G1–G8、8 条风险 R1–R8、真机验收 V1–V12、9 步提交顺序 S1–S9、4 条裁决、可勾选任务清单，以及 §十四 可粘贴接口签名与逐文件改造点。 | 待评审 |

> ⛔ 本文件每次修订必须在此表追加一行；文档版本号只增不改。

---

## 一、目标与核心结论

### 1.1 一句话目标

新增一套 **`SEASIDE`「海边」** 频谱效果：俯拍视角的海岸线——青绿海水在画面上部、**一条条离散推进并破碎的白浪**斜向铺满中段、金黄干沙占据下部。把**低频**映射到**浪的涌入强度（涌得多高、浪带多宽多白）**、**中频**映射到**泡沫密度与破碎粒度**、**高频**映射到**沙面细纹与飞沫**、**鼓点**映射到**贴岸领头浪的一次加白加宽（`setImp`，不改变浪的时序）**、**整体响度**映射到**水色深浅与外海弱浪的显形阈值**。静默时退化为缓慢起伏的浅涌与粼光，**绝不静止**（用户明确要求「海浪要动态的」）。

### 1.2 视觉母题与参考图拆解

参考图（**俯拍海岸线**，三张图互补）确定性视觉要素与参数化落地：

| # | 参考图要素 | 落地实现 |
|---|---|---|
| A | 上部**青蓝海水**（图1 `#2E9AA8`→图2 `#3E8FA6`→图3 `#2C7A8C`，含更深的蓝），有细密波纹 | 自绘水体场：三档互不成整数比的横条纹 + fBm 扭曲 + 行/列级粗糙度，低分辨率场每帧 blit；自绘粼光网（⛔ v1.2 不复用 `WATER`/`CAUSTIC`，§4.2） |
| A2 | ⭐ **图2独有**：水色并非单一渐变，而是**横向也有明暗起伏**（涌浪的阴影带） | 水体场逐像素的 `rowAmp`/`roughX`/`roughY` 亮度场（含 fBm 扭曲的 `rowPhase`）⇒ 横向明暗带（§4.2/§4.3.4） |
| B | **白浪带**由海向陆**斜向推进**，前缘呈**破碎的不规则状**（大瓣→碎瓣→细丝） | 浪脊前缘 = fBm **域扭曲**剖面 `crestProfile`（⛔ 非正弦叠加）+ 三八度分形破碎 `fillFray` + `fillTears` 逐列带宽抖动 ⇒ 自相似破碎参差前缘（§4.3.4） |
| C | ⭐ **图3独有**：白浪**分多层次**（可达 4–5 道），一道压着一道，**越靠海越弱、越靠岸越强** | **离散浪队列**：稳态同时在场 **1 条在最前 + 1 条排队**（由 `WAVE_FOLLOW_Y = 0.62` 的补浪时机落定，⛔ 不写死「几条浪」的常量、⛔ **不做位置钳位**，§4.3.1），**没有固定泳道**；强度按**离岸进度**递增——领头浪 `layerAlpha(adv)`，非领头浪走恒定系数（§4.3.9 ③） |
| D | 浪带与浪带之间是**较深的水**，形成明暗交替的条带 | 队列中浪与浪之间露出海水；外海浪没有白沫时仍有**非泡沫浪体** `drawSwellBody`（迎光亮/背光暗体积感），距离衰减远慢于泡沫（§4.3.5） |
| E | ⭐ **图2/图3独有**：浪**不是纯白**，而是**半透明、能透出下面的水色**；浪心偏青、浪缘偏白 | 泡沫剖面 = **前缘唇（实白）→ 浪心（半透明青 `#CBE7E3`）→ 拖尾（归零）**三段合成 `foamTargetAt`（`FOAM_EDGE_A=0.85` / `FOAM_CORE_A=0.42`）；低 alpha `source-over` + **evenodd 破洞**（`punchHoles`）让水透出（§4.3.5） |
| F | ⭐ **图3独有**：浪的**飞溅/抛沫**——白浪向岸一侧甩出细碎泡沫斑点 | **确定性烘焙飞沫点表**（`SPLASH_MAX=180`，⛔ 不用 `Math.random()`），高频段驱动点数/alpha，沿前缘向岸抛物线甩出（§4.4） |
| G | ⭐ **图2独有**：干沙上有**零散孤立的白点**（残留泡沫/贝壳碎屑），孤立不连成纹 | **确定性残沫点**（`RESIDUE_MAX=52`，LCG + 最小间距拒绝采样 ⇒ 孤立不连纹），球体感三笔绘制（§4.4） |
| H | 白浪**冲刷过湿沙**留下一条更深的**湿沙带**，带边缘有残留泡沫网 | 湿沙 = 逐列湿润记忆（`wetMark`/`wetAmt`/`wetEdge`，§4.3.8）逐列渐变条，色 `#B08A5E` 系；边缘残留 = `drawResidualStreaks` 羽状丝缕（§4.3.8） |
| I | 下部**金黄干沙**（图1 `#D9BE8C`、图2 更饱和的 `#C9A063`、图3 `#C4A876`），近浪处偏湿偏深 | **烘焙沙纹理** `buildSandTexture`（湿→干渐变 + 起伏带 + 潮湿斑 + 颗粒）每帧 blit（§4.3.7/§4.4） |
| J | 沙面有**极细的横向纹**（风吹的沙纹，图2 可见） | 低 alpha **断段**浅色调亮线（`SAND_LINES=26`，每条断成 2–4 段，三档权重 `0.29/0.44/0.58`），随高频轻微流动（§4.4） |
| K | ⭐ **图2/图3共性**：浪是**半透明叠加**，能隐约透出下面的水/沙色 | 泡沫低 alpha `source-over` 填充 + evenodd 破洞露水；仅湿沙镜面高光用 `lighter` 加色（§4.3.5/§4.3.8） |

### 1.3 九条核心结论（TL;DR 决策表）

| 决策 | 结论 | 依据 |
|---|---|---|
| 效果编号 | **E43** | 枚举末位 `STAR_TRAILS`（`"42"`）之后（§2.1） |
| 枚举名 / 显示名 | `SEASIDE` / 「海边」 | §2.1 |
| 档位 | **`Tier.BASIC`** | 无粒子、无帧缓冲、纯 Canvas，且 BASIC 是**唯一三档画质全可见**的档（§3.4） |
| 浪的动态性 | ⭐ **真实动态**：**自走时钟**（时间轴只吃 `dt`，音频只驱动外观）+ **离散浪队列**（`WAVE_POOL=6` 槽位状态机 spawn→ADVANCING→REACHED→FADING→空槽；⭐ **一条浪两个位置量**——破碎线 `spawnFar(−0.04h) → shoreYs`（约 636px，画白浪带）与冲流线 `swashFrontY → wlineFull`（约 81px，驱动湿沙/退水/干燥）；⭐ 补浪时机 `WAVE_FOLLOW_Y = 0.62` 使稳态恒为「1 条在最前 + 1 条排队」；⛔ **无位置钳位**（曾用 `WAVE_SPACING_Y` 冻结后浪 ⇒ 其 `adv` 从未超过 0.6、永远到不了滩）；⛔ **无中途被追上/顶替路径**） | 用户明确要求「浪是离散的个体、一轮一轮替换」——「一轮一轮替换」由**到滩即消散 + 下一条接替冲流**实现；静态平移/相位三角都会被真机判失败（§4.3） |
| 程序纹理 | ⛔ **不复用任何程序纹理**，也不新增 `Id` | 原型的海水/粼光/泡沫/沙面全部自绘或自烘（`ProceduralTexture.Id` 一个都不碰，同时避开 ordinal 下标与源码扫描两个坑，§3.2） |
| 纹理烘焙 | 自烘：`buildSandTexture`（4 层，`ImageData`）+ 6 张 256² 泡沫贴图 + 蕾丝网拓扑 | ⛔ 不调 `ProceduralTexture.ensure()`（一次生成 6 张全屏纹理 ≈ 1240 万像素运算，E42 踩坑，§3.3） |
| 构图 | 横向分层（海 → 浪 → 湿沙 → 干沙），浪前缘**斜向** | 参考图是俯拍岸线，非侧视（§4.1） |
| 后处理 | `postFx = PostFx(vignette = 0.30f, grain = 0.020f)`，**必须字面量** | §3.5 / 门禁 G4 |
| 白浪叠加 | 泡沫低 alpha `source-over` + **evenodd 破洞**露水（⛔ 非 `BlendMode.Plus`）；仅湿沙镜面高光用 `lighter` 加色 | 参考图的浪是半透明、能透出下面水/沙色（§4.3.5） |

### 1.4 与既有 3 套水/流体效果的差异化

本效果必须能一眼与下列效果区分，否则列表里撞脸：

| 效果 | id | 视觉 | 本效果的本质区别 |
|---|---|---|---|
| 频谱瀑布 | E12 `SPECTRO_WATERFALL` | 频谱柱**向下坠落**成瀑 | 瀑布是**纵向坠落柱**；本效果是**横向分层的岸线**，无柱体 |
| 液态网格 | E13 `LIQUID_GRID` | 网格面片起伏 | 网格是**规则网格**；本效果是**有机泡沫/浪带** |
| 液态涟漪 | E15 `LIQUID_RIPPLE` | 同心圆涟漪扩散 | 涟漪是**同心圆**；本效果是**斜向推进的破碎带**，无同心圆结构 |

---

## 二、现状盘点（含 file:line）

### 2.1 效果注册链路（全链路 9 处，新增一套必须逐处同步）

| # | 位置 | 现状 | 新增要动什么 |
|---|---|---|---|
| 1 | `data/model/AppSettings.kt` 枚举 | 29 项，末位 `STAR_TRAILS("星空星轨", Tier.ADV, "42", needsParticleBudget = false)` at `:210` | 追加 `SEASIDE("海边", Tier.BASIC, "43")` |
| 2 | 同文件 `:100` 附近 KDoc | 「可视化效果主题（**29 套**手动效果…）」 | 顺手改「30 套」 |
| 3 | `visualizer/VisualizerRendererFactory.kt` | `when(theme)` 29 分支 | 加 import + `SEASIDE -> SeasideRenderer()` |
| 4 | 同文件 `:88-89` `availableThemes()` | 走 `selectable` + `supports` | 无改动 |
| 5 | `data/prefs/AppPreferences.kt` | `keyVisualizerTheme` 存 enum `name` | 无改动 |
| 6 | 测试 `VisualizerThemeTest.kt` | 7 处硬计数「29/28」 | 全部 +1（门禁 G1） |
| 7 | 测试 `FxCoverageScanTest.kt` | `covered` 名单 + 类数下限「29」 | 加类名 + 下限 +1（G3） |
| 8 | `docs/visualizer-effects-list.md` | 计数行 + 三档表 | +1 + BASIC 表新行 |
| 9 | `docs/technical-overview.md` | 最新 §10.207 `:11446` | 新增 `### 10.208` |

> 主题切换**无设置页入口**——只经舞台点指示器 / ←→ 键（`VisualizerViewModel.kt:460-478 step()`），故新增枚举无需改任何设置 UI。

### 2.2 渲染契约的真实形状（写错名字即编译不过）

> 以下是 E42 实测确认的真实契约，⛔ 不是常见臆测名。

| ❌ 常见臆测 | ✅ 真实 | 位置 |
|---|---|---|
| `RendererBase` | `abstract class RendererFx : VisualizerRenderer` | `renderers/RendererFx.kt:33` |
| `RendererContext` | `RenderContext` | `RenderContext.kt:15` |
| `Frame` | 复用单例 `AudioFrame`；渲染器自身句柄是 `FxFrame` | `AudioFrame.kt:16` / `RendererFx.kt:177` |
| `onResize` | ⛔ 无；尺寸每帧经 `DrawScope.size` + `ctx.canvasSize`，自行 `SizeCache` | `RendererFx.kt:198-210` |
| `onRelease` | `onExit()` | `VisualizerRenderer.kt` 契约 |
| `onBeat` 回调 | ⛔ 无；拍点是 `frame.beat` / `pulse` / `bassRaw` **数据字段** | `AudioFrame.kt:44-52` |

**唯一抽象方法**（`RendererFx.kt:45-49`，子类只实现它）：

```kotlin
protected abstract fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame)
```

可选钩子：`onEnterContent(ctx)`（`:77`）、`onExitContent()`（`:80`）。三个 `final` 模板方法 `draw/onEnter/onExit`（`:53,66,71`）⛔ 不得覆盖。`FxFrame`：`fx.dt`（钳 `[0,0.1]`）、`fx.nowMs`、`fx.seq`、`fx.level`（`FxLevel.OFF/LITE/FULL`）。

### 2.3 音频数据契约与双通道法则

`SpectrumContract.kt:9-44`：`BAR_COUNT=64`、`BASS_END=39`(20–250Hz)、`MID_END=55`(250Hz–3kHz)、`TREBLE_END=63`(3k–20k)、`DISPLAY_GAMMA=0.75`。⛔ **柱数永远读 `frame.spectrum.size`**（`SpectrumContract.kt:5-7`）。

`AudioFrame`（`AudioFrame.kt:16-88`）：`spectrum`(64, 0..1, 已伽马压缩)、`waveform`、`bass/mid/treble/energy`(线性)、`sectionEnergy`(8s 均值)、`bassRaw`(**未归一**，供鼓点强度对比)、`beat`(仅一帧)、`pulse`(快攻慢放)、`bpm`、`timeMs`、`seq`。

⛔ **双通道法则**：`spectrum` 是**显示**通道（伽马压缩），`bass/mid/treble/energy` 是**线性动态**通道，混用是历史 BUG ⑨-b（`AudioFrame.kt:11-15`）。本效果（v1.2 对齐原型）：音频**完全出局、只驱动外观**——时间轴（出浪节拍 / 浪的行程 / 消散 / 水线回退 / 逐列干燥）**只吃 `dt`**；音频只调 `swashReachNow`（涌多高 = `SWASH_REACH·(0.62+0.48·sLow)`）、`bandAmp`（泡沫强度）、`layerAlpha` 的显形阈值、`setImp`（**仅加成领头浪的泡沫振幅**，τ=500ms，⛔ 不产生额外浪、不推进行程）、飞沫/沙纹/残沫密度与水色深浅（§4.6）。

### 2.4 帧驱动与舞台的硬约束

- `ui/components/VisualizerStage.kt:231-249` `withFrameNanos` 循环，无重组；`tick` 在 Canvas 块内读出强制逐帧重绘（`:361`）。
- 画布分支 `:329-374`：外层 `graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }`（`:337-340`，**无条件**，`BlendMode.Plus/Overlay` 依赖它）。
- 帧内绘制被 try/catch 包住（`:364-372`）⇒ ⚠️ **draw 内抛异常会静默变黑屏**，真机才会发现，务必本地跑通。
- `ReleaseContext.update(...)` 每帧刷新（`:348-352`）。
- ⛔ `onEnterContent` 时 `ctx.canvasSize` 仍是 `Size.Zero`（`VisualizerStage.kt:192`）⇒ **任何按尺寸烘焙的重活都不能只放 onEnter**，必须落在逐帧的尺寸守卫分支里（E42 踩坑，§3.3）。

### 2.5 可复用的既有能力

| 能力 | 位置 | 本效果用法 |
|---|---|---|
| `ProceduralTexture.WATER` | `fx/ProceduralTexture.kt:363` `waterRow` | ⚠️ **v1.2 不复用**：原型自绘水色场（§4.2 垂直渐变 + 3 档噪声相位），不叠程序纹理 |
| `ProceduralTexture.CAUSTIC` | `:380` `causticRow` | ⚠️ **v1.2 不复用**：原型的粼光/泡沫为自绘（蕾丝丝网 + 烘焙贴图，§4.3.6/§4.3.7） |
| `ensureFullscreenOnly(id,w,h)` | `:112` | ⚠️ v1.2 不再调它——原型用**烘焙 `ImageData` 纹理**（沙纹理 `buildSandTexture` + 6 张 256² 泡沫贴图）替代全屏程序纹理；若沿用需逐张烘焙避免 `ensure()` 的 6 张全屏代价 |
| `OverlayFx.drawVignette/drawGrain` | `fx/OverlayFx.kt:49,87` | 经 `postFx` 声明式启用（v1.2 保留；原型另有 sheen/grain 后处理，§4.2） |
| `AudioSmoother` | `fx/AudioSmoother.kt:12-27` | 音频平滑：原型 attack **55ms** / release **260ms** 一阶低通、`bassAvg` τ=**700ms**、`setImp` τ=500ms（§4.6） |
| `VisualizerMath.lerp/polar` | `visualizer/VisualizerMath.kt` | 颜色与坐标换算 |
| `SizeCache` | `RendererFx.kt:198-210` | 水色渐变 Brush 按尺寸缓存 |
| `VisualizerRandom`（继承 `rng`） | `RendererFx.kt:85` | 泡沫粒子的抖动（⛔ 不自建） |

### 2.6 不可用的能力（⛔ 硬红线）

| 禁物 | 依据 | 替代 |
|---|---|---|
| AGSL / `.glsl` / OpenGL / EGL | `visualizer-texture-upgrade-plan.md:8-10,994,3276`（`RuntimeShader` 需 API 33+，minSdk 22） | `Brush` 渐变 + `Path` 描边 |
| `RenderEffect`（真高斯模糊） | 同上；API 31+ | 重复贴图低 alpha 叠加 |
| `BitmapShader` | 全仓库零使用 | 自烘 `ImageData` 位图 + `drawImage`（沙纹理 / 泡沫贴图） |
| 圆角 `clip` | API 22 三星 hwui 段错误真机复现 3 次（`VisualizerStage.kt:750-752`） | `drawPath` / `drawCircle` |
| 新增 `ProceduralTexture.Id` | ⛔ 必须末尾追加且会改 `ordinal` 下标基准（`:29`）；本效果**不需要** | ⛔ 一张程序纹理都不取：`Brush` 渐变 + `Path` 描边 + 自烘 `ImageData` |

---

## 三、最容易搞错的「复用」与「调用点」

> 本节每一条都是 E42 实施中**实际踩到并修复过**的坑（记录在 `technical-overview.md` §10.206/§10.207 的踩坑清单里），不是假想。

### 3.1 `t0Ms` 绝不能取 `ctx.nowMs`（E42 的真 bug）

`VisualizerStage.kt:193` 写入的是 `System.currentTimeMillis()`（**墙钟**），而 `frame.timeMs` 是 `SystemClock.uptimeMillis()`（**单调**）。相减 ≈ **−1.7e12 ms**，会让所有基于时间差的量永久冻结。且 `RendererBaseContractTest` **禁止**类体出现 `ctx.nowMs`。

✅ 正解：首次 `drawContent` 用 `fx.nowMs` 惰性捕获起点：

```kotlin
if (!t0Set) { t0Ms = fx.nowMs; t0Set = true }
```

⛔ 且**不要**在 `onEnterContent` 复位 `t0Ms`（画质切换会重入 onEnter，复位会让浪的相位突然跳变）。E42 的 KDoc 明确记了这条。

### 3.2 `WATER` / `CAUSTIC` 已存在，⛔ 不要新增 `Id`（且 v1.2 不再复用）

`fx/ProceduralTexture.kt:31` 的 `enum class Id { GRAIN, SCANLINE, STARFIELD, PAPER, WATER, CAUSTIC, PLASMA, FOG }` **已含 `WATER` 与 `CAUSTIC`**，且两者都有测试覆盖（`ProceduralTextureRecycleTest` 断言 `ensureFullscreen(Id.FOG, w, h, fullKey)` / `Id.PLASMA` 逐项存在）。新增 `Id` 会：① 必须追加到最后（改 `ordinal` 下标基准）；② 触发 `LightBeamsTest` / `PlasmaFlowTest` 的源码扫描门禁。

⚠️ **v1.2（对齐原型）已不再复用这两张纹理**——原型的海水（水色横向起伏 + 3 档噪声相位场，§4.2）与粼光/泡沫（蕾丝丝网 + 烘焙贴图，§4.3.6/§4.3.7）全部自绘，§2.5 已标「不复用」。但「⛔ 不新增 `Id`」的结论**依然成立**：本效果**不需要**任何程序纹理，不触碰 `Id` 枚举即可同时避开 ordinal 下标与源码扫描门禁两个坑。

### 3.3 绝不能调 `ProceduralTexture.ensure()`（E42 的 6× 代价）

`ensure(w,h)`（`:66-94`）一次生成 **6 张**全屏纹理（`STARFIELD/PAPER/WATER/CAUSTIC/PLASMA/FOG`），1920×1080×6 ≈ **1240 万像素** Kotlin 逐像素运算 + 6480 次 JNI `setPixels`，同步发生在**首帧** ⇒ 真机实测冷启动黑屏 6369 ms（E42 修完降到 5617 ms）。

✅ 正解：用 `ensureFullscreenOnly(id, w, h)`（`:112`）**逐张**烘焙，本效果只要 2 张 ⇒ 像素量降为 1/3。

⛔ 且 `ensure` 的记账 `ensuredW/ensuredH` 是**全局共享**的：一旦某张以尺寸 A 烘过，同尺寸下 `ensureFullscreenOnly` 会命中缓存直接返回。调用点放在逐帧尺寸守卫分支（`rebuildGeometry(w,h)`）里，该函数已有 `if (w<2f||h<2f) return` 守卫。

### 3.4 档位：`Tier.BASIC`，不是 ADV

`VisualQuality.supports()`（`AppSettings.kt`）的现行规则（2026-10-01 用户裁决）是 `ADV -> !theme.needsParticleBudget || maxParticles > 0`。本效果**不读** `maxParticles`，故即便标 `needsParticleBudget = false` 也三档全可见。但选 **BASIC** 的理由是**更简单也更稳**：

- BASIC 是**无条件全可见**（`BASIC -> true`），语义上也准确——本效果真的「什么都不消耗」。
- ⚠️ **不要**因此推断 `allowFramebuffer`：`ULTRA -> allowFramebuffer`，而本效果**不用帧缓冲**，故与该字段无关。

### 3.5 `postFx` 必须是纯字面量、无类型标注、无 getter

`FxCoverageScanTest.kt:155` 的正则是 `override\s+val\s+postFx\s*=\s*PostFx\(`。写成 `internal override val postFx: PostFx get() = PostFx(...)` 会因类型标注的 `:` 而**完全不匹配** ⇒ 静默判「未覆盖」⇒ **门禁失败但不报错**（E42 实际踩过）。

✅ 正解：`override val postFx = PostFx(vignette = 0.30f, grain = 0.020f)`（照 `MilkdropRenderer` 形状）。且命名常量会被正则判为「未覆盖」⇒ ⛔ 必须**数字字面量**。

### 3.6 其他复用红线

- **rng**：继承 `protected val rng = VisualizerRandom()`（`RendererFx.kt:85`），⛔ 不得 `private val rng = VisualizerRandom()`（`RendererBaseContractTest` 判定）。
- **零分配**：`draw()` 内禁止任何分配（`VisualizerRenderer` 性能红线），`PerfBudgetContractTest` 机械扫描：**无字符串模板**、**无带参 `Rect(`**、**无 `listOf/mutableListOf/mapOf/.map{`/`sortedBy`**。
- **`recycle()`**：API 22–25 位图像素在 native 堆，`onExitContent` 必须显式 `recycle()`（try/catch），⛔ 不能只置 null。
- **`Path.addArc` 只有 3 参**（无 `forceMoveTo`，E42 用 `javap` 核对 1.9.3）⇒ 每个轮廓前必须显式 `moveTo` 到自己的起点，否则相邻轮廓被直线连起来。
- **确定性抖动**：泡沫/沙纹的抖动只能用确定性 `hash`（⛔ 不用 `Math.random()`，否则每次 resize 形态都变且无法写测）。

---

## 四、架构设计

### 4.1 构图与分层（俯拍岸线，非侧视）

参考图是**俯拍**：海在画面上部、岸线斜向、干沙在下部。分层（自上而下）：

| 层 | 内容 | 垂直范围（占 h） | 实现（原型） |
|---|---|---|---|
| L1 | 远海水（青绿） | `0.00 – SEA_BOTTOM_K = 0.820` | 自绘水色场（§4.2）：三段渐变 `#2E9AA8→#1E7A8C→#14586B` + 3 档噪声相位场（`8/19/43`）+ fBm 扭曲的 `rowPhase` + 行/列级粗糙度 + `swashFrontY` 岸线 |
| L2 | **离散浪队列的破碎线**（稳态 1 条在最前 + 1 条排队） | 破碎线出生 `−0.04h`（画外）→ 上滩 `wlineFull ≈ 0.62h + swashReachNow` | 队列状态机 + ⭐ 自由推进（⛔ 无位置钳位）+ ⭐ 前缘映射 `spawnFar + (shoreYs − spawnFar)·tAdv + crest·(1−morph)` + fBm 域扭曲浪脊 `crestProfile`（§4.3.2 / §4.3.1） |
| L3 | **冲流线 + 抵达滩上的领头浪** | 冲流 `swashFrontY` → `wlineFull`，行程 ≈ `h·SWASH_REACH` ≈ 81px | 破碎浪脊 + 泡沫阶梯/蕾丝/破洞/破碎唇 + 非泡沫浪本体（§4.3.5/§4.3.6） |
| L4 | 湿沙带（浪刚冲过） | 岸线下方逐列（`wetMark`–`wetEdge`） | 逐列湿润记忆 + 干燥（`dryTau = clamp(WAVE_GAP_MS·0.60, 420, 2600)` = **1416ms**，§4.3.8） |
| L5 | 干沙（金黄） | `SAND_TEX_TOP = 0.46 – 1.00`（⛔ 必须高于水线最大摆动 `0.484h`，§九 红线 7） | **烘焙沙纹理** `buildSandTexture` + 沙纹 + 残沫（§4.3.7/§4.4） |

⛔ **不是侧视海平线**——没有「地平线 + 天空」结构（那是 E42 的做法）。本效果**没有天空**。

### 4.2 水体与粼光（L1，自绘，不复用程序纹理）

- **底色**：逐像素在 `#2E9AA8`（远海）↔ `#14586B`（深海）之间按深度插值：画面顶部（外海）更深更饱和，越靠近浪线越浅越透；⛔ 不得只画一条垂直渐变（会读成「一整块塑料」）。**浅滩带**（浪线前方）额外压向 `#A6D8C4`，宽度随响度 `0.30 + 0.22·energy`。
- **3 档噪声相位场**（⭐ 图2 的水色横向起伏由此而来）：沿 x/y 各 3 档（大波 `N_BIG = 8`、中波 `N_MID = 19`、细波 `N_FINE = 43`，`colPhase`/`colMid`/`colFine` + `rowPhase`/`rowMid`/`rowFine`），互不成整数比的频率 + 时间相位 ⇒ 永不循环。⛔ `rowPhase` 必须先经低频 fBm **扭曲**再取模（`11/29/61` + 完美等距 `v·TAU·N` = 一排贯穿全幅的规则梳子）。⛔ 原型的 `WAVE_K/WAVE_W/WAVE_A/WAVE_SGN/WAVE_PHI/WAVE_K4/WAVE_K5` 是**死常量**（定义后仅出现在注释里），Kotlin 端口**直接删掉**（§5.5 清单）。
- **粗糙度场**（反馈六·4）：`roughX` / `roughY`（二维「大块平静区」）、`rowAmp`（行级浪脊对比度）各一份 O(fw)+O(fh) 场，破坏「数学上平」的区域；亮度 `light` 乘三者之积 ⇒ 不同位置、不同时间的浪脊强弱可差约 4 倍。⚠️ 必须用 `fbmNorm`（⛔ 写 `0.28 + 0.92·(0.5+0.5·fbm1)` 只有 `0.54..0.94`，空档做不出来）；另有 `MOTTLE_A = 0.030` 的 16×16 晶格低频斑驳兜底（⛔ 不含高频 ⇒ 无摩尔纹风险）。
- ⭐ **粼光网**（自绘，非 `abs(sin)` 相乘），三层叠在海水裁剪区里：
  1. **两族交叉斜向射线**（↗/↘ 共 `RAYS = 30` 根，每根只画 5–9 段短划、斜率逐根不同、随时间漂移 + 摆动，每 5 根里 1 根刻意拉陡）。
  2. ⭐⭐ **连通胞壁网**（反馈八·5c，取代原先的 12 段孤立短横划）。
  3. **56 颗闪烁亮结**（交叉处的小亮点，随能量与各自相位明灭）。
  `CAUSTIC_A = 0.075`，整体乘 `0.30 + 0.70·energy`；⛔ 只在海水裁剪区内绘制、⛔ **不整屏叠**。（⛔ 原型的 `CAUSTIC_SHORE_A = 0.10` 是**死常量**——岸线处的亮线由 `drawWetLine` 与贴岸各层负责，§5.5 清单。）

  **为什么必须是「胞壁网」**：那 12 段孤立短横划之所以读作「划痕」，根源不是斜率，而是**没有「格」**——真实水面焦散的本质是**胞壁**：格壁连成多边形、水从格中透过。补上胞形与连通性即可，不必靠斜率躲避。（原型里前两版依次是 15 条近水平虚线 ⇒ 横贯全幅的平行线（刻痕）；12 条孤立直线短划 ⇒ 仍是刻痕，只是变短了。）

  - **`buildCausticNet()` 在 resize 时把拓扑烘好**（与 `buildLaceNet` 同一套路）：海水区里一张抖动网格 `CNX = 20` × `CNY = 9`（原型 1600×900 下约 80×82px 一格），每格只连**右**与**下**两个邻居 ⇒ 一次得到连通的多边形胞格，边界胞由画面裁切自然闭合。
  - ⛔ **不做周期环绕**：环绕会在右/下边界生成横贯全幅的长线 ⇒ 又回到刻痕。
  - ⭐ **去规整化**（否则读成方格纸）：① 列/行坐标各过一次**低频平滑值噪声 warp**（4 阶、衰减 0.55）⇒ 胞格宽窄不均；② 每行一个哈希 `shear` ⇒ 行与行的壁角度不同（同行内仍近似平行）；③ 节点再叠 `±0.30` 格 的逐节点 hash 抖动。⛔ **幅度一律压在 ±0.3 格以内**——超过这个量相邻胞格会翻转自交，网直接破掉。
  - **每帧把格壁画成二次曲线**：控制点沿**垂直于壁**的方向弓起（静偏 + 随时间缓慢摆动 ⇒ 壁是弯的且会呼吸），⛔ 绝不出现笔直硬线。
  - ⭐ **按亮度分 3 档批量描边**（alpha `0.62 / 0.44 / 0.28`、线宽 `1.15 / 0.85 / 0.6`）⇒ 全网只 **3 次 `stroke()`**，而不是每壁一次（每壁一次在 331 条壁时会成为热点）。
- **渲染形态（性能关键）**：水体烘进**低分辨率 `fieldCvs`**（`ImageData` 逐像素 + `sin` 查表），每帧一次「裁剪 + `drawImage` blit」到 `H·SEA_BOTTOM_K = 0.82h`，代价与分辨率固定、与帧率无关；分辨率自适应 `scale = clamp(ceil(√(W·seaPx / 52000)), 4, 14)`。Kotlin 端口照此做低分辨率场 + 放大 blit。
- ⛔ **按画质降级**：`LOW`/`OFF` 档（`fx.level == OFF`）只留底色渐变，噪声场/粼光跳过（省 1 次全屏 blit）；`TIME_SCALE` 低档 0.35× 放缓（§4.7）。

### 4.3 ⭐ 浪的动态机制（本方案的核心，用户第一诉求）

⛔ **不用静态纹理平移冒充海浪**——那会被真机判为「海浪不动态」。

采用**离散浪队列（discrete wave queue）**：定长槽位池，每个槽位就是**一条独立的浪**，从外海出生、走到滩上、原地消散、槽位回收。⛔ **不再是**「连续水位场 + 若干条相位错开的破碎带」——相位三角（sin 曲线）做不到「后退 / 前缘追上」的离散交接，而那正是本效果要的节奏。

> 所有者诉求原文：**「浪是离散的个体、一轮一轮替换」**。判据不是「有没有白浪」，而是**能不能看出这是一条一条的浪，各自走完一生**。

#### 4.3.1 离散浪队列：槽位、状态机、生命周期

**数据结构**：定长池 `WAVE_POOL = 6` 个槽位，每槽一条浪：

| 字段 | 含义 |
|---|---|
| `state` | 生命周期状态（`W_EMPTY=0` / `W_ADVANCING=1` / `W_REACHED=2` / `W_FADING=3`） |
| `y` | **破碎线行程**（adv 空间）`0` = 出生，`W_REACH_Y = 1.00` = 到达滩上。⛔ `y` **不是**像素位置，像素位置由 §4.3.2 的前缘映射现算 |
| `v` | 推进速度（每 ms 的 `Δy`，**出生时定死、途中不变**） |
| `reach` | 这条浪自己的涌高系数（`0.62 + 0.78·fbmNorm` ⇒ `0.62…1.40`）。⚠️ **当前原型里它已被逐列 `reachK` 取代、而 `reachK` 又不再参与 `shoreYs`** ⇒ 这条字段事实上不参与几何（见 §5.5 死代码行），Kotlin 端口可保留字段但不必强求其效果 |
| `seed` | 这条浪自己的 hash 种子（`3300 + serial·733`）——**破碎线浪脊形状的唯一来源** |
| `serial` | 出生序号，**每次出生 +1**。⭐ 槽位会回收复用 ⇒ 对象引用会把「下一条浪」误认成同一条；身份索引（`wiS`/`lane`，§4.3.9）与单浪跟踪全靠它 |
| `aliveT` | 出生后已过的毫秒（泡沫时间淡入用） |
| `fadeT` | 消散已进行的毫秒 |
| `hit` | 到过滩上？（= 会留湿沙） |
| `swashT` | ⭐ **冲流计时**：`y ≥ SWASH_LEAD_ADV = 0.85` 起累加 `dms`。⚠️ 对 `REACHED`/`FADING` **也继续累加**（它们 `y` 恒为 `1.0 ≥ 0.85`）⇒ 「破碎线抵滩」与「冲流满位」因此**同一帧发生**，交接处位置天然连续 |
| `peak[]` | 到达滩上那一瞬、逐列的水线 y（= 湿沙高水位，`COLS+1` 长） |

**唯一允许的状态边**（原型的 `stepWaves` / `finishWaves` / `commitWetMarks` 三段）：

```
(空槽) ──spawn──▶ ADVANCING              出浪：y = 0；v / reach / seed / serial 定死
ADVANCING ──y ≥ W_REACH_Y──▶ REACHED     到达滩上（仅当滩上无浪，见下「防御闸门」）
REACHED  ──同一帧──▶ FADING                消散（⭐ **留**湿沙：hit = true；同时**起退水**，见 §4.3.2）
FADING   ──fadeT ≥ WAVE_FADE_MS──▶ DEAD   淡出完毕 → 回写 W_EMPTY（DEAD 即空槽，无独立常量）
DEAD     ──▶ (空槽)
```

- **出生淡入** `waveFade(w)` = `clamp(aliveT / WAVE_SPAWN_RAMP_MS, 0, 1) × clamp(y / WAVE_SPAWN_FADE_ADV, 0, 1)`（`700ms × 20% 行程`）⇒ **no pop**，且 ⭐ 在**最远端那 20% 行程里逐渐清晰化**。⛔ 纯时间淡入不可用：后浪一生约 `10s`，`700ms` 只占 **7%** ⇒ 读作「凭空出现」（owner 原话：「后浪是凭空出现的吗？应该从最远端逐渐清晰化出现」）。`FADING` 时再乘 `clamp(1 − fadeT / WAVE_FADE_MS, 0, 1)`。
- ⭐ **补浪调度不是纯定时器**（`stepWaves` 每帧检查队列）：

  | 条件（按序判定） | 是否补浪 |
  |---|---|
  | 滩上有 `REACHED` 或 `FADING` | ❌ 不补——「最前」这个槽已被占住，再补就成 3 条 |
  | `advN == 0`（海上一条 `ADVANCING` 都没有） | ✅ 补，但受**最小间隔闸**约束：`spawnAcc ≥ WAVE_GAP_MS = 2360` |
  | `advN == 1` 且前浪 `front.y ≥ WAVE_FOLLOW_Y = 0.62` | ✅ 补一条**排在它后面**（⛔ 不受 `WAVE_GAP_MS` 限制） |
  | 其余 | ❌ 不补 |

  命中且 `spawnWave()` 真的占到了槽位（返回 `Boolean`，池满返回 `false`）才把 `spawnAcc` 清零；池满则**这一拍不出浪**（不丢帧、不跳变）。
  ⛔ `spawnAcc` / `WAVE_GAP_MS` 只是「海面空着」那条路径的最小间隔闸，不决定常态浪数。稳态出浪间隔 ≈ `0.62 × T_领队` ⇒ **约 2.6–3.6s**（导出量，不是常量）。
- ⭐ **行程时间 `T` 按「是否已有在推进的浪」分两档**（`spawnWave` 内，`follower` 判据 = 池里存在任一 `W_ADVANCING`，⛔ **必须在写 `w.state` 之前判**，否则会把自己算成跟随者）：

  ```
  T = lerp(WAVE_T_MIN, WAVE_T_MAX, fbmNorm(serial·1.37, sd, 3)) · (follower ? FOLLOW_T_SLOW : 1)
  ```

  | 身份 | `T` 区间 | 依据 |
  |---|---|---|
  | 领队浪（发出时海面空） | `4200 … 5800 ms` | `WAVE_T_MIN/MAX` |
  | 跟随浪（发出时海面已有 1 条） | `8400 … 11600 ms`（`× FOLLOW_T_SLOW = 2.0`） | owner：「第二条浪走的太快了，前浪还没来得及回退」 |

- ⛔ **补浪只在 `stepWaves` 里做**，`finishWaves` **不补**：`REACHED → FADING` 那一帧顺手再发一条会**绕过「滩上不补」的约束** ⇒ 海上多出一条。
- **绘制序**：把在册的浪按 `y` 升序（外海先画），抵达滩上的那条最后压上去。⚠️ ⛔ **绘制次序下标 `wi` 不得进入任何外观表达式**（§4.3.9 / 红线 6）。
- **⛔ 帧内调用顺序不可换**：`stepWaves(dt)` → `leadWave()` → `waterlineAdvance(dt·1000)` → 逐列填 `peak[]` → `finishWaves(dt)` → `commitWetMarks()`。早先把 `REACHED→FADING` 放在 `stepWaves` 里，`peak[]` 永远是空的 ⇒ **湿沙一列都没被写过**。

⛔ **没有「中途被追上顶替」这条路径**，且这是**构造上不可能**，不是没实现：一条浪只有走到 `y ≥ W_REACH_Y` 才算终点，`ADVANCING` 的浪**不会被任何东西消耗**。理由是 `T` 的两档区间**不重叠**：

```
min(T_跟随) = 4200 × 2.0 = 8400 ms   >   max(T_领队) = 5800 ms      ⇒ 恒有 ≥ 2600ms 到达间隔
```

⇒ **到达顺序恒等于出浪顺序**，后面那条永远追不上前面那条。「离散个体 + 一轮一轮替换」由**到滩即消散**实现，不需要顶替。
⚠️ **这条根据与早先那版不同**：早先是「`WAVE_T_MAX − WAVE_T_MIN = 1600 < WAVE_GAP_MS = 2360` ⇒ 至少隔 760ms」；引入 `FOLLOW_T_SLOW` 后保证变强（≥2600ms），但**根据也从「散布 < 间隔」换成了「两档区间不重叠」**——门禁 G2 里那条断言必须跟着改，否则锁的是一条已不成立的根据。

#### 4.3.2 ⭐⭐ 两个位置量：破碎线 breaker / 冲流线 swash（反馈八·10 · 方案 3）

**一条浪带两个位置量，各管一件事。** 这是本方案的核心架构决策，取代此前「把前缘深度与水线推进量塞进同一个 `adv`」的做法。

| 量 | 定义（像素） | 行程 @1600×900 | 职责 |
|---|---|---|---|
| **破碎线 breaker** | `spawnFar = h·(SHORE_K − WAVE_SPAWN_DEPTH)` = `h·(0.620 − 0.66)` = **`−0.04h`（画面顶边上方 36px）** → 终点 `shoreYs[i]`（≈ `0.62h + swashReachNow`） | **≈ 636px** | 画出来的**白浪带**（「第二条浪全程可见」的依据） |
| **冲流线 swash** | `swashFrontY` → `wlineFull = swashFrontY + h·swashReachNow` | **≈ 81px**（`h·SWASH_REACH·(0.62…1.10)`） | 水在沙滩上的**边缘**，驱动湿沙 / 退水 / 干燥 |

⭐ **交接不是位置突变，而是职责增加**：破碎线推进到 `adv = 1` 时，它的位置**本来就等于** `shoreYs[i]`（因为前缘映射的终点就是 `shoreYs`）⇒ 无缝；而它**不消失**，只是从这一刻起**开始同时定义冲流线**（`y ≥ SWASH_LEAD_ADV` 起 `swashT` 累加 ⇒ 冲流开始爬升）。

**⛔⛔ 两种做法被所有者明确否掉，务必写进红线（§九 红线 1 / 2）**

| 被否做法 | 实测后果 |
|---|---|
| ⛔ **位置钳位**（曾用 `WAVE_SPACING_Y = 0.55` 把后浪冻在固定行程） | 后浪 `adv` **从未超过 0.6**、永远到不了滩；**67% 的绘制调用因 `kA` 过低被 cull** ⇒ 读作「走到中间就消失」。owner：「中间不停顿」「应该一直推进到沙滩」 |
| ⛔ **把后浪压进水线空间**（方案 B：两端都按 `h·SWASH_REACH` 尺度定位） | 后浪行程被压到 **81px** ⇒ 它整个藏进领头浪的带里（领头浪 `bwj` 中位 **1.17** + `SHORE_FOAM_BOOST` **1.55**，直接把后浪盖死）⇒ owner 报「**彻底看不到**」，尽管它的 `kA` 0.29~0.38、`bwj` 0.35~0.41 **全部正常**。⚠️ 这条是最有教育价值的一次失败：**数值全对、画面全无**，说明「第二条浪可见」不能靠调 `kA`/`bwj` 解决，只能靠给它**独立的位置空间** |

##### 4.3.2.1 绘制侧的前缘映射（`drawSwellBands` 内，唯一产出 `bfy[i]` 的地方）

```kotlin
val isLead  = (w.state == W_REACHED || w.state == W_FADING)   // ⛔ 不是 (w === lead)
val spawnFar = h * (SHORE_K - WAVE_SPAWN_DEPTH)                 // = −0.04h
val morph    = smoothstep(MORPH_START, 1, adv)                  // MORPH_START = 0.55
val tAdv     = adv + (1 - adv) * morph                          // 单调、终点 1、前段 ≡ adv
val uC       = x * 0.00340f * kScale + adv * CREST_DRIFT_ADV - lane * CREST_LANE_SHIFT
val crest    = crestProfile(uC, w.seed) * h * CREST_AMP * (1 - morph)
bfy[i] = (if (isLead) shoreYs[i]
          else spawnFar + (shoreYs[i] - spawnFar) * tAdv + crest)
       + (if (isLead) 0f else (1 - morph) * fray[i] * W0 * FRAY_FRONT * 0.42f)
```

- ⛔ **绘制上的「前浪」= 真正抵达滩上的那条**（`REACHED`/`FADING`），**不是** `leadWave()`。旧写法 `isLead = (w === lead)` 的病：`leadWave()` 在**滩上无浪**时返回「`adv` 最大的推进中浪」，而那条还在外海（`adv 0.41~0.77`）、它的带是按**破碎线尺度**画的；一旦被判定为前浪，`bfy` 立刻改用 `shoreYs`（**水线尺度**）⇒ **整条带瞬移**，实测交接跳变回到**中位 187px / 最大 387px**。
  ⛔ `leadWave()` 在当前原型里**只**被 `computeShore` 用（取 `seedLag` 与写 `peak[]`），`drawSwellBands` 里那个 `lead` 局部量已是**残留未用变量**。
- ⛔ **`crest` 与 fray 项都必须乘 `(1 − morph)`**：`shoreYs[i]` 里**已经包含**前浪自己的曲线（`swashFrontY` 内的 `crestProfile(u, 2711)·h·CREST_AMP_SHORE`）与 fray（**同一系数 `0.42`**）。⇒ 这两项必须随 `morph` 被**吸收**，⛔ 不能叠加在插值结果之上，否则**重复计入** ⇒ 交接处又出现跳变。
- ⛔ **不可写成 `lerp(spawnFar + crest, shoreYs, morph)`**（早先写法）：`adv < MORPH_START` 时 `morph = 0` ⇒ `bfy = spawnFar + crest`，而 `spawnFar` 是**常数**、`crest` 一生只漂移约 103px ⇒ **前 55% 的旅程画面上几乎不动**，之后 `morph` 启动、一次性从 `spawnFar` 扫到 `shoreYs`。owner：「后浪浮现后，就等待在原位置，过一会就会以较快的速度冲出去」。⭐ `tAdv = adv + (1−adv)·morph` 修掉它：单调、终点为 1、且在 `adv < MORPH_START` 时**恒等于 `adv`** ⇒ 全程可见移动。
- ⭐ **`CREST_DRIFT_ADV = 0.35` 取代全局时间漂移**：⛔ 早先挂在 `t·0.0000060` ⇒ 后浪一生约 10s 期间 `u` 仅变 0.06 ⇒ 读作**固定曲线**（owner：「后浪的曲线现在是固定的，应该随着前进方向一直在变化，但变化不要太大」）。⭐ 挂在该浪自己的 `adv` 上 ⇒ 整条旅程 `u` 走 0.35（等效 ≈103px），每帧 ≈0.10px。
- ⭐ **`WAVE_SPAWN_DEPTH = 0.66` 的来由**：owner：「后浪直接浮现在上面 1/6 左右的位置，而不是从边缘进入」⇒ 出生点必须落在**画面顶边略上方**。⛔ `0.42` 时 `spawnFar = 0.20h ≈ 180px`，浮在顶部 1/5 处；⭐ `0.66` ⇒ `−0.04h`，顺带把破碎线行程从 ~459px 拉长到 **~636px**，海面铺得更开。

##### 4.3.2.2 冲流推进量 `waterlineAdvance(dms) ∈ [0,1]`

**拥有者（owner）** 的选取：滩上的浪（`REACHED`/`FADING`）**优先**；否则取**破碎线已进入上涌区**（`swashT > 0`）的浪中 `y` 最大的那条。`push = clamp(owner.swashT / SWASH_RUNUP_MS, 0, 1)`。

| 情形 | 条件 | 返回 |
|---|---|---|
| ① 有拥有者、不在退水 | `owner != null && retreatT < 0` | `push`（`= clamp(owner.swashT / 900, 0, 1)`） |
| ② 退水中 | `retreatT >= 0` | `a = max(0, 1 − retreatT / retreatDur)`；若**非滩上** owner 的 `nextPush ≥ a` ⇒ 交接（`retreatT = −1`，返回 `nextPush`）；否则返回 `a` |
| ③ 无拥有者 | `owner == null` | `0`（冲流完全退回） |

- ⛔⛔ **交接比较必须在同一参数化空间内**。旧写法 `front.y >= a` 是拿**破碎线 `adv`（636px 尺度）**比**冲流 `adv`（81px 尺度）——两个空间不可比。⭐ 现在 `a` 与 `nextPush` **都是冲流 `adv`**（`0` = 完全退回、`1` = 满位），交叉即交接 ⇒ 水线连续。实测交接 `|Δ bfy|` **中位 1.1px / 最大 3.0px，且全部发生在 `adv = 1.000`**。
- ⛔⛔ 交接必须拿**非滩上**的 owner 比（`incoming = if (owner && !ownerBeach) owner else null`）：退水改为「抵滩即起」后，起退水时 owner 正是那条刚抵滩的浪、`swashT` 已满 ⇒ `push = 1`，而 `a` 起始也是 `1` ⇒ `push >= a` 立刻成立 ⇒ **退水在第一帧就被取消**。
- ⛔⛔ 但⛔ **不能**写成 `front !== owner`：后浪进上涌区后自己就成了 owner ⇒ 条件永不成立 ⇒ **交接永不触发** ⇒ `retreatT` 永不重置为 `−1` ⇒ 之后所有起退水的守卫（`retreatT < 0`）全被挡住 ⇒ **整轮退水被吞**。原型实测症状：**90 秒只剩 1 次退水**。

##### 4.3.2.3 ⭐ 退水的双触发点与自适应时长

**触发点①（主）**：`REACHED → FADING` 那一帧（`finishWaves`），**仅当滩上没有其他浪** ⇒ `retreatT = 0`。
⭐ 这是 ⭐ **并行**而非先后：泡沫在 `WAVE_FADE_MS = 900ms` 里淡出、退水在 `retreatDur` 里回落，**同时发生**。owner 原话：「然后浪花消失同时海水回退」；⛔ 早先是**先后**（抵滩后先淡出 900ms，等浪死透才起退水 ⇒ 泡沫早已消失，退水才开始）。

**触发点②（兜底）**：滩上**最后一条** `FADING` 淡出完毕（`fadeT ≥ WAVE_FADE_MS`）那一帧，若已无任何滩上浪 ⇒ `retreatT = 0`。
⛔ **不可省**：一条浪在**前一条还在滩上时**抵滩，触发点①会被 `stillBeach` 守卫跳过；而它的 `REACHED` 转换**已经发生过、不会再来一次** ⇒ 整轮退水被吞（症状同上：90 秒只剩 1 次）。

⛔⛔ **两个触发点的守卫里都必须有 `retreatT < 0`**：否则退水进行中被重置 `retreatT = 0`，下一帧推进量会从「已接近完全退回」（实测 `meanAdv 0.0685`）被**瞬间拽回满位**（`0.9611`）⇒ **水线一帧跳 78.79px**。

⭐ **退水时长自适应**（`SWASH_RETREAT_MS` 现在是**下限**，不再是定值）：

```
eta        = front ? max(0, (SWASH_LEAD_ADV - front.y) / front.v) : 0   // v 是 adv/ms ⇒ 直接是 ms
retreatDur = max(SWASH_RETREAT_MS, min(SWASH_RETREAT_MAX, eta))          // 440 … 1600 ms
```

- owner：「前浪回退不应该退到中间就停止了，应该继续回退，直到碰到后浪」。
- ⛔ 固定 `440ms` 的病：水线 440ms 就退完，然后**静止等**后浪（实测等 ~2s）⇒ 视觉上就是「退到一半停住不动了」。
- ⛔ 完全按后浪 ETA 自适应的病：会把退水拉到约 5.7s（`94.5px / 5.7s ≈ 17px/s`），实测**中位后退只剩 6.1px**——那已经不叫退水了。⇒ 在 `[下限, 上限]` 间自适应。
- ⭐ 实测结果：**90s 内退水 14 次**，中位 **1567ms / 83.7px**（满程 94.5px 的 **89%**），逐帧水线均值位移 **6.45px**。

- **退到海缘而浪还很远 ⇒ 钉在海缘等待**，交接判定持续生效。
- 逐列：`adv = clamp(push − lag·SWASH_LAG, 0, 1)`，`lag ∈ [−1,1]` 来自确定性 fBm（种子 `4409`/`4523`，`SWASH_LAG = 0.16`）⇒ 有的岸段已冲上滩、有的还在半路 ⇒ **水线成舌、参差成一道前缘**（⛔ 不是「撕碎」：`SWASH_LAG` 上调会变成沙上的深缺口/咬痕）。
- ⭐ **`shoreYs[i]` 的定义**（`computeShore` 内，水线的唯一权威）：

  ```
  base   = swashFrontY(x, t, h) + fray[i]·W0·FRAY_FRONT·0.42
  shoreYs[i] = base + h·swashReachNow·adv
  ```

  ⛔ **不再对 `y` 做 `smoothstep`/三角**（`adv` 本身就是推进量）；⛔ **不再有逐列涌高偏移**（早先的 `reachK·adv` 会破坏交接连续性，逐浪差异改由 `T`（速度）、`w.seed`（浪脊相位/形状）与 `env`/`rag`/`fray` 噪声承担）。

**swash 周期**（原型 `swashStageNow`）= ① 上涌/滩上有浪 → ② 退水（`retreating`）→ ③ 裸露干燥。**节奏源就是「退水 + 干燥锋面」**：到滩 →（并行）消散 + 起退水 → 后退露湿沙 → 后浪进上涌区接管冲流 → 重新上涌。

#### 4.3.3 ⭐ 自走时钟（音频完全出局）

⛔ **音频不在时间轴里**。时间轴上每一个量都是纯 `dt` 的累加/积分：

| 量 | 时基 |
|---|---|
| `spawnAcc`（最小间隔闸，⛔ 不再是浪数决定者，§4.3.1） | `+dt` |
| 每条浪的破碎线行程 `y` | `+= v·dt`（`v` 出生定死） |
| 每条浪的冲流计时 `swashT` | `y ≥ SWASH_LEAD_ADV` 时 `+= dt`（含 `REACHED`/`FADING`） |
| 消散 `fadeT` | `+= dt` |
| 退水 `retreatT` | `+= dt` |
| 逐列干燥 `wetAmt` | `×= exp(−dt·1000/dryTau)` |

原型在 `stepWaves` / `finishWaves` / `waterlineAdvance` / `waveFade` 的函数体里**一个 `A.*` 都不出现**；`computeShore` 里唯一的音频是 `swashReachNow`，而它在 `stepWaves(dt)` 与 `waterlineAdvance(dt·1000)` **之后**才计算 ⇒ 结构上无法影响时序。音频只决定**外观**（涌多高 / 泡沫多强 / 水色深浅 / 飞沫·沙纹·残沫密度，§4.6）。
⚠️ **例外要说清**：非领头浪的 `kA` 现在**不吃任何音频**（§4.6），所以音频对外海浪的影响面比 v1.2 更小；⛔ 这不代表音频可以进时间轴——`v`（⇒ `T`）与 `y` 永远只由 `dt` 与 `hash` 决定。

⛔ 与「把时间量化成 `frame.seq`」的禁令同源：原型暂停时 `dt` 传 `0`，**暂停会真正冻结整个队列**（含退水不再干、湿沙不再变干）。

#### 4.3.4 ⭐ S 曲线随机浪脊（fBm 域扭曲，取代正弦叠加）

⛔ 正弦叠加在视觉上是「一排等距平行波 / 碗里的涟漪」。原型用**沿shore轴的域扭曲 fBm**：

```
crestProfile(u, seed)
  wx = fbm1(u·0.83, seed+511, 2)      // 两个低频 fBm 偏移采样坐标
  wy = fbm1(u·0.61, seed+733, 2)
  v  = u + wx·1.70 + wy·0.85          // ① 域扭曲 ⇒ 浪脊自己拐弯、成 S
  env = 0.12 + 0.88·(0.5 + 0.5·fbm1(u·0.37, seed+613, 2))   // ③ 包络
  return (0.62·fbm1(v, seed+101, 4) + 0.38·fbm1(v·2.9, seed+211, 2)) · env   // ② 4+2 八度
```

- **八度比 2.07、衰减 0.60**（⛔ 不是 0.5：衰减 0.5 时能量几乎全在最低八度 ⇒ 浪脊波长很长、幅度很小，读起来仍是「几条平缓的带子」）。
- **`fbmNorm` 拉满**：`clamp(0.5 + 1.45·fbm1(...), 0, 1)`。⛔ 直接用 `0.5 + 0.62·fbm1` 会把所有包络压在 `0.26..0.74`——既到不了 0（做不出「局地浪列 / 平静空档」）也到不了 1（最强的也不够强）。
- **包络下限 0.12**（不是 0.30）：0.30~1.00 时所有岸段强度差不多，仍读成「一条平缓的带」。
- ⭐ **每条浪用自己的 `seed`**，⛔ 不再是固定 `811`：破碎线的 S 形起伏必须逐浪不同，否则两条浪同形。相邻浪仍然**共用同一条剖面函数**，只靠 `- lane·CREST_LANE_SHIFT = -0.18` 在 `u` 上错开 ⇒ 仍是一组**相干**浪列（真实涌浪如此）；用独立 seed 会让相邻带交叉出**透镜状伪影**。
- **漂移挂在该浪自己的 `adv` 上**：`u = x·0.00340·kScale + adv·CREST_DRIFT_ADV(0.35) − lane·CREST_LANE_SHIFT`。⛔ 早先挂全局时间 `t·0.0000060`：后浪一生 ~10s 期间 `u` 仅变 0.06 ⇒ 读作固定曲线。⭐ 挂 `adv` ⇒ 整条旅程走 0.35u（等效 ≈103px），每帧 ≈0.10px——「变化但不大」。`kScale = 1920/W` 让扇贝数跨窗口尺寸稳定。
  ⚠️ **例外**：岸线基线 `swashFrontY` 用的仍是**全局时间**慢漂移 `t·0.0000105`（`CREST_AMP_SHORE` 那一侧）——它是水线自身的呼吸，与逐浪漂移是两件事。
- **纵向起伏幅度**：`CREST_AMP = 0.220`（外海/破碎线，峰峰 ≈ 0.6~0.9 ⇒ ~164px @1600×900）/ `CREST_AMP_SHORE = 0.130`（岸线，峰峰 ~97px）。⚠️ 上限受 `CREST_LANE_SHIFT` 约束：相邻带前缘不能交叉。
- **岸线基线** `swashFrontY(x,t,h)` = `h·SHORE_K` + 极慢潮汐 `h·0.0060·sin(t·0.000042)` + `crestProfile(x·0.00310·kScale + t·0.0000105, 2711)·h·CREST_AMP_SHORE`。⚠️ 潮汐振幅刻意克制在 ±0.02·h（早先按 `WAVE_A` 原样叠加出 ±0.10·h，岸线像山脉）。
- ⭐⭐ **`CREST_AMP_SHORE = 0.130` 直接定住 `SAND_TEX_TOP` 的下界**：水线最小 y = `SHORE_K − CREST_AMP_SHORE − 潮汐` = `0.620 − 0.130 − 0.006` = **`0.484h`**。⇒ `SAND_TEX_TOP` 必须 < `0.484h`（现取 `0.46`，留 `0.024h ≈ 22px` 余量）。⛔ 早先取 `0.62 = SHORE_K` ⇒ 退水时 `0.484h…0.62h` 这段在裁剪区内却没有纹理、**露出海水底色**，owner 同时报了两条看似无关的故障——「沙滩颜色与底色的交界」与「海水回退退到快中间就不退了」——**两条是同一根因**（§九 红线 7）。

**破碎前缘（三件套，缺一就读成光滑正弦边缘 / CG 感）**：

| 手段 | 实现 | 作用 |
|---|---|---|
| `fillFray(t, L)` | 三八度 `FRAY_K = [0.0090, 0.0261, 0.0592]`，`FRAY_A = [0.55, 0.26, 0.12]`（≈0.47 逐级衰减）+ 每列固定 hash 抖动 `FRAY_JIT = 0.055`；纵向位移 = 抖动 × `FRAY_FRONT = 0.35` × 浪带宽 | 大瓣 → 碎瓣 → 细丝的自相似破碎 |
| `fillTears(L)` | 两套慢/快正弦的逐列带宽倍率（`ragA` 慢 / `ragB` 快），按泳道号 `L` 选择 | 带宽逐列不齐 ⇒ **参差前缘** |
| 逐列 `lag` | §4.3.2 的 `SWASH_LAG` | **逐列行程不齐**，前缘成舌 |

⛔ `FRAY_W = [0.00016, −0.00034, 0.00064]` 已整体放缓 ~4.5×；`FRAY_WIDTH = 0.30` 让整条带的厚度一起缩放（保证窄带边界不交叉）。

✅ **已修（2026-10-03，原型与本文档同步）** —— 这一处曾让外观间接依赖绘制次序，是本轮最后一个同类缺陷。原始错位：`fillTears(L)` 按 `slow = (L & 1) === 0` 写 `ragA`（`L` 偶）或 `ragB`（`L` 奇），而取用是 `rag = (lane == 0 || lane == 1) ? ragA : ragB`；⛔ 但 `lane = 1 + (serial % 3)` **恒不取 0** ⇒ 实际配对是 `lane 1 → 读 ragA（ragA 由 lane 2 写）`、`lane 2 → 读 ragB（由 lane 1/3 写）`、`lane 3 → 读 ragB（自己也写 ragB ✓）`。
⇒ 后果：一条浪的 `rag[]` 内容**取决于同帧里别的浪的绘制次序**（双浪场景下两条浪互换了撕裂图案），违反红线 6。
⭐ **修法**：把取用判据改成与写入判据**完全一致** —— `const rag = ((lane & 1) === 0) ? ragA : ragB;`。⛔ 教训保留为红线：**任何"按 lane 写、按 lane 读"的缓冲数组，两个判据必须是同一个表达式**；写成两个看似等价但实际不等价的条件，就会退化成「外观依赖绘制次序」。**Kotlin 端口建议直接按 `lane` 选一套该浪自有的撕裂场**（`ragOf(lane)` 返回自算的 `FloatArray`），⛔ 不要照抄这两个共享数组。

#### 4.3.5 ⭐ 泡沫律（概念移植，⛔ 不是代码移植）

泡沫强度由**两个乘子**决定（逐列，⛔ 二者**都不得**折进带宽）：

```
foamK[i]  = slopeLaw[i] · farLaw[i]                        // 只用于 alpha（见下「作用点」）
slopeLaw[i] = clamp(|Δy/Δx 每像素| · FOAM_SLOPE_GAIN · 4.0, 0, 1)      // 0.62 × 4.0 = 2.48
farLaw[i]   = ( clamp(1 − (SHORE_K·h − bfy[i]) / (FOAM_FAR_SPAN·h), 0, 1) ) ^ P
             P = isLead ? FOAM_FAR_POW(1.6) : 0.35
if (isLead) { slopeLaw[i] = 1; farLaw[i] = 1 }             // ⭐ 水线处永远满泡沫
```

- ⭐ **概念移植自《WebGL Insights》ch.11 的 `foam_shore = 1.25·dot(normal, shore_dir) − 0.1`**：那本书是 3D WebGL，有法线有 shader；本项目 `minSdk 22`、⛔ 无 shader / 无 OpenGL / 无 `BitmapShader` ⇒ **没有法线可用**。`slopeLaw` 用**浪脊线的横向斜率 `|dy/dx|` 作代理**——斜率大 ⇒ 浪面朝岸倾斜 ⇒ 破碎 ⇒ 泡沫峰值；斜率小 ⇒ 几乎水平 ⇒ 无泡沫。`farLaw` 是**次要**乘子，只负责「最远处与海水无异」（`0.62h → 0.12h` 覆盖整个海域）。**这是概念移植，不是代码移植。**
- ⭐ **`FOAM_FAR_POW` 从 `2.4` 降到 `1.6`**（harness 按 `adv` 分桶实测）：`pow 2.4` 时第二条浪 `adv 0…0.2` 的 `farLaw` 只有 **0.043**、`kA` **0.061** ⇒ **67% 的绘制调用**被外海泡沫/扰动的 `kA < 0.12` cull ⇒ 它整条读作「没有白沫」；领头浪因 `farLaw`/`slopeLaw` 被硬编码为 1，`kA` 是它的 5~6 倍。⇒ `2.4` 太陡：`1.6` 让起步段 `0.043→0.08`、中段 `0.16→0.30`，**最外侧仍趋 0**（保留 owner「最远处与海水同样」的诉求），但中段浪全程可见。
- ⭐ **非领头浪另用 `0.35` 幂**（与 `drawDisturbance` 里的同款处理一致）：⛔ 早先统一用 `1.6` 仍把它的起步段 `kA` 压到 0.08、**80.9%** 的绘制调用被丢弃 ⇒ 读作「走到中间就没了」。⚠️ **副作用如实记录**：owner 早先「浪线越到远处越浅、最远处与海水同样」那条诉求，对**第二条浪**就此作废——两条要求作用在同一批浪上，只能取一（owner 明确选了「我才知道第二条浪走到哪里了」）。
- ⛔ **距离律只作用在 alpha 上，绝不折进带宽**。两者都折进 `bwj` 时：破碎线出生处（`−0.04h`）`farLaw ≈ 0` ⇒ 带宽被乘到 0 ⇒ 整片海上只剩一条极淡的线。反馈要的是「泡沫看不见」，不是「浪看不见」。
- **作用点**（`foamK` 落在哪里，决定「泡沫看不见」还是「浪看不见」）：
  - 白沫晕 `drawSeaFoamWash`：分 8 段、每段按自己那段列的 `fk = slopeLaw·farLaw` 调 alpha（`0.06 + 0.94·fk`）。
  - 外海泡沫贴图 `drawOpenSeaFoam`：**逐块**按那一列的 `fk` 调 alpha（`0.10 + 0.90·fk`），⛔ 不吸附到列栅格（吸附会得到一排规则花纹）。
  - 扰动前锋 `drawDisturbance`：`slopeLaw · farLaw^0.35`（⛔ 不能用 `FOAM_FAR_POW`：破碎线出生处 `farLaw ≈ 0`，前置扰动整条归零、实测全帧零像素差）。
  - 浪唇 `drawCrestLip`：吃**列平均** `farMean`（唇只有单一 alpha）。
  - ⛔ **带宽路径只用 `slopeLaw`**（`slK = 0.30 + 0.70·slopeLaw`，见 §4.3.9），⛔ 不含 `farLaw`。
  - ⚠️ `drawSwellBody` 用的是**自己的**更平缓距离律 `(0.35 + 0.65·slopeLaw)·1/(1 + 3.2·(1 − farLaw))`，⛔ 不是 `farLaw` 本身。
- ⭐ **因此必须有非泡沫的浪本体** `drawSwellBody`（`SWELL_BODY_A = 0.55`）：俯拍看下去，一道涌浪是「迎光面亮 + 背光面暗」的体积感（用 `PAL.trough` / `PAL.ridge`，与海面自身的明暗语言一致）。它的距离衰减**远比泡沫慢**（≈ pow 0.55 而非 1.6）⇒ **远处的浪仍有形状，只是没有白**。这正是 `foamK` 要造出的那个梯度。

**泡沫剖面**（`foamTargetAt(n)`，`n` = 离前缘距离 / 浪带宽）= 三段合成，量化成 `FOAM_EDGES` 的 14 段互不重叠窄带（外侧弱浪用隔一取二的 `FOAM_LADDER_COARSE`）：

| 段 | 函数 | 峰值 / 边界 |
|---|---|---|
| 前缘唇（实白） | `foamEdgeAt` | `FOAM_EDGE_A·0.94`，保持 `CREST_HOLD = 0.14` 宽才衰减，`CREST_OUT = 0.42` 归零 |
| 浪心（半透明青） | `foamCoreAt` | `FOAM_CORE_A = 0.42`（⛔ 峰值不得超过它），`HEART_IN 0.20 → HEART_FULL 0.45 → HEART_HOLD 0.62 → HEART_OUT 0.88`，**两端都低于中部** ⇒ 帐篷形 ⇒ 与前缘合成**双峰** |
| 拖尾 | `foamTailAt` | `TAIL_IN = 0.66 → TAIL_OUT = 1.25`，二次渐隐正好归零 |

- ⛔ **不做圆形泡沫胞**。前缘早期版本从 `n = 0` 就开始掉，前段只剩几像素亮线，整体读成「玻璃带」——这才是「不真实」的主因之一。
- **破碎唇** `drawCrestLip`：贴着前缘的一条极窄高光带（`LIP_W = 0.16` 带宽倍数）+ 紧贴其后的暗带，两 pass。这是「破碎」与「丝绸」的分界。⛔ 只对**非领头浪**画（领头浪的前缘就是水线本身）。
- **分形破洞** `punchHoles`：在每条窄带上按 `HOLE_MAX = 18` 挖 evenodd 椭圆孔（⛔ 太窄的条带 `gap < 0.07` 挖不动会破形；⛔ `ry < 1.5 || rx < 2.5` 的孔直接丢弃 ⇒ 亚像素孔不画，省开销也避免脏边）。早先只有 8×2px 的细缝 ⇒ 读成划痕而不是孔洞。
  - ⭐ **洞场是「连续相位」，不是「每周期重算的随机场」**（原型本轮第四版，⛔ 旧写法已删）：每个洞有一个**由 hash 固定的相位** `ph = hash2(h, 700 + strip·31 + L·7)`，再由 `u = ((t / HOLE_PERIOD_MS) + ph) % 1`（⛔ **绝不取整**）驱动三件事——沿条带推进 `hn = n0 + gap·(0.12 + 0.76·u)`、半径 `hr = gap·(0.06 + 0.30·grow)·(0.70 + 0.60·hash2(h, 720 + strip·17 + L·5))`、显形包络 `grow = sin(π·u)`（`0 → 1 → 0`）。⭐ 半径在 `u` 两端收缩到**地板值** `gap·0.06·(0.70+0.60·h)`（原型代码是地板项、⛔ 不是严格 0）——⭐ 按实测结论（「`u` 回绕帧的位移被判为不可见」）该地板落在 `ry < 1.5` 的丢弃阈值**之下**（⚠️ 这是从该结论反推的，harness 没有单独打印地板半径）⇒ 每个洞是「长出来 / 缩回去」而不是「啪一下出现 / 消失」，**`u` 的回绕无害**（回绕那一帧的孔已被丢弃）。各洞 `ph` 错开 ⇒ ⛔ 不会同帧齐变。
  - ⛔⛔ **两条硬红线**（owner 报障「前浪的白色浪花一直存在，现在老是闪烁」后确立）：
    1. ⛔ **逐帧程序化场不得用量化时间换随机种子**——⛔ 不得写 `hash2(h, 710 + Math.floor(t / HOLE_PERIOD_MS) + …)` 之类。破洞是 **evenodd 挖进白沫路径**、与条带轮廓**一次 `fill`** 的 ⇒ 每个周期边界上**所有洞的位置与半径在同一帧整体瞬移**，白沫图案整帧闪一次；⛔ 且所有洞同帧变化 = **一起眨眼**，最刺眼。⚠️ 上一轮只把周期从 260ms 放慢到 `1100ms`：**只治了频率、没治跳变**，所以闪烁活了下来（周期越长跳变越稀疏、每次更突兀——⚠️ 这一项本轮未单独测量）。原型实测（`docs/archive/verification/scripts/seaside_hole_continuity_check.js`：抽取 HTML 里**真实的** `hash32`/`hash2` 而非重写，6 个周期 @60fps，取「可见洞」（`ry ≥ 1.5`）逐帧**中心或半径**位移的最坏值）：改前 **6.2px ＝ 自身半径的 2.6×**（读作瞬移）→ 改后 **0.74px ＝ 0.31×**（连续），**8.4×**。⛔ 该指标**必须按可见性加权**（用代码里同一个 `ry ≥ 1.5px` 阈值）：未加权的原始最大值是 **9.0px**，全部来自 `u` 回绕那一帧——那时的半径同时在阈值之下、根本不可见，不加权就报假阳性。
    2. ⛔ **取模回绕 ＝ 不连续**。横漂必须是**有界振荡**：`hx = clamp(ph + 0.13·sin(t·0.00021 + ph·6.2832), 0.02, 0.98)`。⛔ 不可写成 `(ph + 0.13·sin(..) + 1) % 1`——原型第一版正是如此，`% 1` 让 `hx` 从 ~1.0 跳回 ~0.0，洞**每回绕一帧就横穿整屏**：harness 实测 **1599px/帧 ≈ 整屏宽**，比它要修的那个 bug 差 **~270×**。`clamp` 是有界振荡、边界处导数归零 ⇒ 连续。⭐ 推广成通则：**在逐帧程序化场里，取模回绕就是不连续；用 `clamp` 或有界三角函数。**
  - ⛔ **为什么这条机制只对 evenodd 破洞是硬伤**：同一个随机场若只是**逐像素采样**（每帧独立取值），闪烁会轻得多——没有「拓扑」把整帧的取值差异放大成图案级突变。**evenodd 挖孔把场的变化直接变成白沫路径的拓扑变化** ⇒ 同一个场在这里是硬视觉伪影。⛔ 改 `punchHoles` 时必须连带想清楚填充方式（`ctx.fill(p, 'evenodd')`，单次填充）。门禁 G9、V16。

#### 4.3.6 ⭐ 泡沫蕾丝 `drawFoamLace`（三次重写才对）

⛔ 依次否掉的三个版本：① 一堆**圆**当泡沫胞 ⇒ 泡泡浴；② 沿行进方向拉长的**竖直丝** ⇒ 一排名副其实的竖直短毛；③ 322 根互不共享端点的**短横丝** ⇒ 散落的苍白毛发。

✅ 现在是一张**沿岸铺开的网**，关键是**「丝是连通的」而不是「丝的形状」**：

- **`buildLaceNet()` 在 resize 时把网的拓扑烘好**（u-v 空间的节点 + 边），每帧只把每条边的两个端点映射到浪几何上：端点**共享** ⇒ 网永不散架。
  - `10×4` 抖动网格；4 行的 `v` = `0.03 / 0.22 / 0.40 / 0.58`（占浪带宽）。
  - 沿岸长丝每行 9 条（各横跨 ~10% 屏宽）+ **2 条环绕屏边的包边丝**（`xs = ±1`）⇒ 整行绕屏一圈无缝。
  - 行间 **~72% 竖直短筋** + ~42% / ~22% 斜筋连成网眼；每格再放一根更细的短丝当网眼填充。
  - 边的属性（`ph / f1 / amp / rate / wj / aj`）全部来自 `hash2`。
- **每帧**：端点 → `bfy[col] + dir·v·bwj[col]·W0`；内部点叠**缓慢漂移的正弦**，但两端 `sin(0) = sin(π) = 0` ⇒ **端点钉死**。
- **描边而非图元**：`stroke` 天然给出细线，粗细就是丝的粗细（`lineWidth = (0.55 + 0.85·wj)·bandW·kindW`）。
- **两个线尺度**：3 个「离前缘距离」档（`bandA = [1.00, 0.62, 0.30]` / `bandW = [0.85, 1.10, 1.30]`）近前缘密而亮、后段散而淡；4 类线（长丝 / 竖筋 / 斜筋 / 网眼填充）的 `kindW`/`kindA` 各不相同。成熟度 `dens`：领头浪 `1.30`，外侧浪 `0.62 + 0.30·(1 − L/3)`。
  ⚠️ `L` 现在是**泳道号** `lane = 1 + (serial % 3)`（⛔ 不再是「第几条带」的下标，也**恒不取 0**）⇒ 该式实际落在 `0.72 / 0.62 / 0.52`。Kotlin 端口按 `lane` 直算即可，⛔ 不要恢复成「下标 0/1/2/3」的假设。
- `dir`：`+1` 向岸 / `−1` 向海——贴岸那条带的主体在它**海侧**。
- ⭐ **破洞与蕾丝是同一件事的两半**：`punchHoles` 负责「洞」、`drawFoamLace` 负责「网」，两者合起来才把窄带打散成有机白沫（⛔ 早先试过把亮带沿 x 切成 3 段重叠子路径、各给不同 alpha 来造「断开感」——source-over 下重叠段会**叠得更亮**，屏幕上是一块块硬边矩形，已删）。⭐ 因此 §4.3.5 的**洞场连续性**要求必须与蕾丝一起看：蕾丝端点钉死在共享节点上、拓扑本身不闪，但**破洞是 evenodd 挖进同一条路径、再单次 `fill` 的** ⇒ 洞场跳变会以「白沫图案整帧一闪」的形式盖过蕾丝的稳定性，owner 读作「老是闪烁」。⛔ 凡是 punch 进 evenodd 路径的场（洞，以及将来任何挖孔/擦除）都必须逐帧连续；⛔ 只被逐像素采样的场没这个要求（§4.3.5 末条）。

#### 4.3.7 ⭐ 烘焙沙纹理 `buildSandTexture`（反馈三：沙几乎不动，烘一次）

沙本身几乎不动，所以**整块烘一次**，之后每帧只做一次「裁剪 + `drawImage` blit」，**代价与帧率无关**。逐像素 `ImageData`，4 层**确定性**图层：

| 层 | 内容 | 常量 |
|---|---|---|
| ① 湿→干底色渐变 | 靠水 `#C9A063` → 中 `#C4A876` → 下缘 `#D9BE8C`，`v^0.86`、在 `v = 0.42` 折点；另有整体湿→干微渐变 `1 − 0.10·(1−v)` | — |
| ② 宽而柔的沿岸起伏带 | 两个 `vnoise2` 八度扭曲后的 `sin((v·N + warp)·2π)` ⇒ **不是等距直线** | `SAND_RIPPLE_N = 7`，`SAND_RIPPLE_A = 0.040` |
| ③ 潮湿斑块 | 两个 `vnoise2` 合成 `smoothstep(0.54, 0.82, …)`，近水处更多、整块干沙也有零星几片，压向 `#8E7048` | `SAND_DAMP_A = 0.30` |
| ④ 像素级细颗粒（主导纹理） | 两个 `hash32(x, y)` 逐像素白噪声，相加 | `SAND_GRAIN_A = 0.062` + `SAND_GRAIN2_A = 0.030` |

- **开销闸门**：像素超过 320 万就按比例降采样（`k = min(1, sqrt(3.2e6 / (tw·th)))`）。
- ⭐⭐ **起点 `SAND_TEX_TOP = 0.46`**（v1.2 写的是 `0.62`，⛔ 已错）：必须**高于水线的最大摆动**。水线 = `swashFrontY + h·swashReachNow·adv`，而 `swashFrontY = h·SHORE_K + 潮汐 + crestProfile·h·CREST_AMP_SHORE` ⇒ 最小 y = `0.620 − 0.130 − 0.006` = **`0.484h`**。`0.46` 留 `0.024h ≈ 22px` 余量。
  ⛔ 早先取 `0.62`（= `SHORE_K`）⇒ 退水时 `0.484h…0.62h` 这段在裁剪区内却**没有纹理** ⇒ 露出**海水底色**，owner 同时报了「沙滩颜色与底色的交界」与「海水回退退到快中间就不退了」——**两条是同一根因**（视觉上「沙」从 `0.62h` 才开始 ⇒ 退到一半就像「退不动了」）。§九 红线 7。
- ⛔ blit 的**源矩形必须是 `(0, 0, texW, texH)`**：把 `sandTexTop` 当源 `y` 偏移会采到纹理外面，整块沙变成灰暗的条纹。
- ⛔ 干沙底色**不再用渐变**（渐变已烘进纹理）；⛔ 湿沙也**不再用固定渐变**（§4.3.8 逐列现场生成）。

#### 4.3.8 ⭐ swash 周期 / 湿沙与逐列干燥

一条完整的 swash 周期（`swashStageNow`）：

```
上涌（冲流 adv 0→1，逐列 lag ⇒ 水舌参差）
  → 破碎线进上涌区（adv ≥ SWASH_LEAD_ADV = 0.85）⇒ swashT 开始累加、冲流开始爬
  → 到滩（REACHED，⭐ 唯一写湿痕的时刻；⭐ **同时起退水**，与消散并行）
  → 消散（WAVE_FADE_MS，原地淡出；水线从满位开始回落）
  → 退水（retreatDur = 440…1600ms 自适应，1→0，露出湿沙；后浪**照常推进**，
          交接由「非滩上 owner 的 nextPush ≥ a」同空间比较决定，§4.3.2.2）
  → 裸露干燥（dryTau 指数衰减，干燥锋面跟着 wetEdge 内收）
  → 后浪的破碎线进上涌区 ⇒ 它成为冲流拥有者 ⇒ 水线重新上涌
```

**逐列记忆**（`COLS + 1 = 97` 个 `FloatArray`）：

| 数组 | 语义 |
|---|---|
| `wetMark` | 该列**历史上**被淹到的最内陆 y（**只增不减** = 历史最高水位）。⛔ 必须初始化为 `-1e9`；早先写 `h` ⇒ `y > wetMark` 恒 false ⇒ 湿区一路撑到画面底边（一条 246px 的假水渍） |
| `wetAmt` | 该列当前湿润度 `0..1`，退水后按时间常数衰减 |
| `wetEdge` | 本帧用于渲染的湿区外沿（随干燥向水线收拢） |
| `dryK` | 该列干燥速率系数（`0.52 + 0.96·fbmNorm` ⇒ `0.52..1.48` ⇒ 洼地存水久、沙脊干得快） |
| `swashPos` | 该列当前冲流推进量 `0..1` |
| `reachK` | ⛔ **当前原型里已无读取方**（逐列涌高系数曾参与 `shoreYs`，交接连续化时被移除，见 §4.3.2.1）⇒ 分配 + 写入都还在，但**不参与任何几何**。Kotlin 端口可**直接不实现**（§5.5 死代码行） |

- ⭐ **湿痕只在到滩那一瞬写入**（`commitWetMarks`：⛔ 只处理 `FADING && hit`，⛔ **上冲途中一个字都不写**——那里的沙还在水下）。它在帧内的位置是 `finishWaves` **之后**（`REACHED→FADING` 必须先发生，`hit` 才会被写上）。
- **干燥**：`dryTau = clamp(WAVE_GAP_MS · SWASH_DRY_FRAC, SWASH_DRY_MIN, SWASH_DRY_MAX)` = `clamp(1416, 420, 2600)` = **1416ms**。⭐ 干燥时间常数**挂在出浪间隔上**（两个浪之间裸露的时间就是 `WAVE_GAP_MS`），而不是挂一个固定周期。
- ⭐ **变干的判据是「这一列此刻是否裸露」**，不是「全体有没有浪在推进」：`wetMark[i] > y + 0.5` 才乘衰减。早先写 `adv <= 0.004` ⇒ 只要海上还有任何一条浪在推进，全岸的干燥一起暂停，湿沙永远降不下来。
- **沿岸 3 抽头平滑**（`0.25 / 0.50 / 0.25`）：逐列记忆物理上没错，但**相邻列不该差这么多**——一列湿 1.0 而邻居 0.3 就是一道竖直硬边。平滑后偏差回到 ~0.35 lum，而干燥锋面与水舌的参差完全保留。
- **湿沙渲染**（`drawWetWash`）：⛔ 沿岸分 N 段共用一条渐变 ⇒ 阶跃色带；⛔ 全幅共用一条竖直渐变 + 逐列 alpha ⇒ 深棕平板 + 阶梯下沿；⛔ 逐列 `createLinearGradient`（原型的 97 个/帧，实测 +0.1ms，**Kotlin 端口可照做**）。✅ 现状是**逐列自己的渐变**，从最深走到全透明、终点正好落在 `wetEdge[i]`；⛔ x 用浮点、不取整也不 `+1px`（取整留 0.33px 缝、重叠二次合成，两种都会变成竖线）；顶端抬到水线之上 `WET_OVER = 16px` 让 clip 独自决定上沿（抬 4px 不够，列间 `shoreYs` 可差 7~8px ⇒ 水线上出现一列列阶梯）。
- **其余覆层**：镜面高光 `drawSheen`（`lighter` 加色、`SHEEN_A = 0.19`，越干越弱）、洼地水洼 `drawPuddles`（`PUDDLE_MAX = 16`，⛔ 反光必须是压扁椭圆、早先的 `fillRect` 亮线在 100% 下清楚得能看见边）、退水残沫 `drawResidualStreaks`（3 pass 羽状丝缕，`RESIDUE_STREAK_A = 0.075`，⛔ 必须短——太长就成了「等高线」）、岸线细亮湿线 `drawWetLine`（`WET_LINE_A = 0.42`，退水期最亮 ×1.0 / 上涌期 ×0.55 / 干燥期 ×0.30）、浪花手指 `drawSwashFingers`（`FINGER_MAX = 30`，vigor = 上涌 1.0 / 退水 0.50 / 干燥 0.10，⛔ 位置按 hash 大幅抖动 + 只有 ~45% 真的伸出去，等距栅格会读成装饰花边）。

#### 4.3.9 ⭐ 绘制侧的三条硬不变量（身份索引 / 带宽不归零 / 非领头浪的 kA）

这三项都在 `drawSwellBands` 的逐浪循环里，共同保证「第二条浪全程可见、且交接时纹理连续」。

##### ① ⛔⛔ 任何外观量不得依赖绘制次序下标 `wi`

`order` 是**按 `y` 排序**的绘制次序数组，下标 `wi` 会随别的浪出生/回收而改变。harness 实测 **14/15 条浪的 `wi` 一生会变一次**（`0↔1`），而 `wi` 曾泄漏进三处：

| 泄漏点 | 影响 |
|---|---|
| `bandAmp(1 + wi % 2)` | `amp` 跳 `0.0042`（折算 `W0` 仅 0.09px，可忽略） |
| `nb1` 的相位 `wi·2.13` / `nb2` 的 `wi·0.77` | 带宽花纹相位跳变 |
| ⛔⛔ `env` 的 `wi·7.3` 与 **hash 种子 `8100 + wi·53`** | **主因**：种子随 `wi` 变 ⇒ `env` 换成**完全不同的噪声实现** ⇒ `envK` 直接乘进 `bwj`（带宽）⇒ `wi` 翻转时**带宽花纹整个换掉** ⇒ 画面跳变 |

✅ **改为按身份取的稳定索引**：

```
wiS  = w.serial & 3              // 只由身份决定、终身固定，同时保留「逐浪不同」
lane = 1 + (w.serial % 3)        // ⭐ 泳道号只由身份决定，与「是不是前浪」无关；不用 0
```

⛔ 早先的 `lane = isLead ? 0 : (wi & 1) + 1` 有两个问题：`wi` 会变；且 `isLead` 翻转时 `lane` 从 1/2 跳到 0（或跳回）⇒ harness 实测**每条浪一生恰好跳变 2 次**，正是「成为前浪」与「不再是前浪」那两帧——位置那时已经连续了，但 `fillFray`/`fillTears`/蕾丝/泡沫贴图的**噪声实现整套切换** ⇒ owner 报「后浪还是会跳变」。
✅ **已修（2026-10-03）**：撕裂场 `ragA`/`ragB` 曾因写入判据与取用判据不等价，让外观依赖绘制次序（§4.3.4 末），原型已改为同一表达式。⛔ 教训保留为红线：**按 lane 写、按 lane 读的缓冲数组，两个判据必须是同一个表达式**。Kotlin 端口照此写，或按 §4.3.9 改为每条浪自有的一份。

##### ② ⭐ 带宽不允许归零（硬下限 `0.30`）

```
frayK  = 1 + fray[i]·FRAY_WIDTH
envK   = isLead ? 1 : 0.02 + 0.98·env          // env = pow(fbmNorm(...), 2.4)
slK    = isLead ? 1 : 0.30 + 0.70·slopeLaw[i]  // ⛔ 用倾角律，不用距离律
shrink = max(0.30, frayK·envK·slK)             // ⭐ 下限加在**乘积**上
bwj[i] = (0.84 + 0.30·nb1 + 0.14·nb2) · rag[i] · shrink
```

- ⛔ 早先**没有任何下限**：三项衰减因子连乘即可把 `bwj` 乘到 `0` ⇒ 那一帧整条浪带宽度为 0 ⇒ 读作「**浪突然消失**」。
- ⛔ 最初以为元凶是 `1 + fray·FRAY_WIDTH`（只钳住了三项里最轻的一项）；⛔⛔ 真正的元凶是 **`0.02 + 0.98·env`**：`env = pow(fbmNorm(...), 2.4)`，而 `fbmNorm` 偏小处被 2.4 次幂压到趋 0 ⇒ 该因子趋 `0.02`。⇒ **下限必须加在乘积上，不能加在单项上。**
- harness 实测（修复前，非领头浪）：`bwj` 中位 **0.076** / 最低 **0.005**；**塌陷率在 `adv 80~90%` 达 48.5%**，平均 `bwj` 从外海 `0.251` 缩到 `0.113` —— 正是 owner 说的「靠近前浪时突然消失」。修后塌陷率 **0%**。
- ⚠️ 下限 `0.30` 的含义是「破碎缺口不再把带宽**清零**」，⛔ 不是「取消破碎感」：缺口仍让带宽在 `0.30~1.0` 之间起伏。
- ⛔ **距离律只作用在非领头浪**——因为领头浪的 `slopeLaw`/`farLaw` 被硬编码为 1（§4.3.5）。这不是巧合：**距离律本来只该作用于离岸浪，也就是第二条浪**。

##### ③ ⭐ 非领头浪的 `kA`：不吃音频门控、不吃近岸斜坡

```
amp = isLead ? (0.55 + 0.45·bandAmp(0))
             : (0.46 + 0.45·bandAmp(1 + (wiS % 2)))
kA  = isLead ? clamp(amp · layerAlpha(adv, A.sEnergy) · fade, 0, 1)
             : clamp(amp · 0.55 · fade, 0, 1)          // ⭐ 只剩 amp × 系数 × 淡入
```

- ⭐ owner 原话：「第二条浪不应该抑制浪花啊，应该一直保持浪花，我才能知道第二条浪走到哪里了」——它的浪花是**位置指示器**，不是音强的表达。
- ⛔ 早先非领头浪吃满 `layerAlpha`：那里有两项**叠乘**——`nearness`（外海 `0.32`）× `appear`（音频能量门控，静音趋 `0.30`）= `0.096`，× `amp 0.698` ⇒ `kA ≈ 0.067` ⇒ **82%** 的绘制调用被 `kA < 0.12` 丢弃。⛔ 只软化距离律（`pow 0.35`）几乎无效（39.2% → 38.2%），因为抑制来自叠乘而非单幂。
- ⭐ **距离感仍然保留**，只是改由**带宽**（`slK`，§②）与**逐块 alpha**（`foamK`，§4.3.5）承担 ⇒ 它外海时是「更窄、更淡」而不是「没有」——这正是「能看见它在哪」。
- ⚠️ 系数 `0.55` 是**刻意低于领头浪**的：领头浪另有 `SHORE_FOAM_BOOST = 1.55`，两条浪的主次关系不能反过来。
- ⭐ 非领头浪的 `amp` **基线从 `0.30` 抬到 `0.46`**：harness 实测领头浪 `amp ≈ 0.90`、第二条浪 `≈ 0.59`，第二条浪的 `kA` 因此只有领头浪的约 1/5——它是「海上第二条浪」不是「附带的余波」。⚠️ 只抬基线、不改增益斜率。
- `layerAlpha` 的近shore项下限同时从 `0.22` 抬到 **`0.32`**（⚠️ 与「非领头浪不吃 `layerAlpha`」是**两处独立修改**，各自有各的实测依据：`0.22` 时第二条浪起步段 `kA` 只有 0.055、87.7% 调用被丢弃；见 §4.6）。

### 4.4 沙面（L5）、沙纹与残沫

- **干沙底色**：⛔ **不用渐变**——三图干沙色差明显，全部烘进 `buildSandTexture` 的第①层（`#C9A063` 近浪处 → `#C4A876` → `#D9BE8C` 下缘，`v^0.86`、在 `v = 0.42` 折点），另有第②层起伏带、第③层潮湿斑、第④层像素颗粒（§4.3.7）。
- **沙纹**：极细的**横向断段**线（图2 可见风吹沙纹），⚠️ **不得形成摩尔纹** ⇒ 条数固定 `SAND_LINES = 26`（只有 18% 的线靠 hash 控制**可见性**，不改条数）、alpha 上限 `SAND_LINE_A = 0.055`、三档取其 `0.29 / 0.44 / 0.58`。⭐ 三档**都是浅色调**（`#EEDAB4` / `#F7E8CB` / `#FFF5E2`）——浅沙上的深色 1px 线根本不可见。每组整体缓慢漂移（⛔ 不循环 ⇒ 无闪烁），随高频 `sHigh` 调 alpha。
- ⭐ **飞沫**（`drawSplash`）：沿领头浪前缘向岸抛物线甩出的细碎斑点，**确定性烘焙点表**（`SPLASH_MAX = 180`，⛔ 不用 `Math.random()`），点数与 alpha 由高频驱动，越过前缘后短暂滞留再消散。
- ⭐ **残沫白点**（`drawResidue`）：干沙上**零散孤立**的白点，⛔ **不得连成纹路**（连成纹就退化成沙纹）⇒ LCG + 最小间距 `0.092` 拒绝采样、`v` 偏向湿沙边缘、alpha 极低；三笔球体感（软阴影 → 亮芯 → 左上缘高光）。⛔ 与沙纹**形态不同**（点 vs 线）、分层绘制，避免读成噪点。

### 4.5 动态参数的帧率无关性

⛔ 所有随时间演化的量都必须写成 `f(fx.nowMs)` 或 `x × fx.dt`（累加量）。门禁 G3 会用「dt=1/30×30 帧 vs dt=1/60×60 帧到同一 `nowMs` 应同值」来判。⛔ **禁止**任何「每帧 +1」的固定步进（那会随帧率变快变慢）。

本效果的时间轴**全部是 `dt` 的累加/积分**（补浪最小间隔闸 `spawnAcc`、行程 `y`、消散 `fadeT`、退水 `retreatT`、干燥衰减），而**随机性全部来自确定性 hash**（`hash32`/`hash2` + fBm），⛔ 没有任何量依赖帧数。`nowMs` 只用于**绝对相位**（浪脊慢漂移、潮汐、沙纹漂移、**破洞场相位**、粼光壁的呼吸）——⭐ 这里的「相位」必须是**连续**的：⛔ 任何一处写成「按 `nowMs` 重新播种」的随机场，都会经 evenodd 挖孔放大成整帧闪烁（§4.3.5 / G9）。

### 4.6 音频映射总表

⛔ **第一原则：音频不碰时间轴**。没有「鼓点给浪一个推进冲量」这种东西（原型已删）。`stepWaves` / `finishWaves` / `waterlineAdvance` / `waveFade` / `commitWetMarks` 的函数体里**一个音频量都不出现**；`computeShore` 里唯一的音频是 `swashReachNow`，而它在 `stepWaves(dt)` 与 `waterlineAdvance(dt·1000)` **之后**才算 ⇒ 结构上无法影响时序。音频只决定**外观**。

| 音频 | 通道 | 映射到 | 说明 |
|---|---|---|---|
| 低频段 `A.sLow`（≤250Hz，显示通道） | 显示 | **涌高 `swashReachNow = SWASH_REACH·(0.62 + 0.48·sLow)`**（0.065..0.115h） | ⛔ 只改「水线最高能爬多高」，**不改水线什么时候爬**。低频 = 涌得多猛 |
| 低频段 `A.sLow` + 中频 `A.sMid` | 显示 | **浪的泡沫振幅 `bandAmp`**：`e = (领头 ? sLow : 0.70·sLow + 0.30·sMid)`，`a = 0.26 + 0.74·e` + 领头浪再加 `setImp·0.55`，钳 `0..1.35` | 低频 = 浪高/带宽（`W0 = h·SWELL_BAND_W·(0.62 + 0.62·amp)·(领头 ? 1.0 : 0.92)`），中频 = 破碎程度。⚠️ `computeShore` 喂给 `fray` 的那个 `swashW0` 用的是**另一条公式** `h·SWELL_BAND_W·(0.55 + 0.75·min(1.35, bandAmp(0)))`（恒按领头浪算，因为它算的是水线） |
| ⭐ `bassRaw` + `frame.beat` | 动态 | **只做贴岸领头浪的振幅加成**：`setImp ∈ 0..1`，τ = `SET_TAU_MS = 500ms`，判据 `rawLow > bassAvg·1.42 + 0.055`（`bassAvg` τ = 700ms） | ⚠️ 早先版本的「齐涌冲量」打在时间轴上，**已删除**。现在鼓点只是让**正在消散的那条浪更白更宽**——**不改变队列、不产生额外浪、不推进行程** |
| 响度 `A.sEnergy` | 动态 | ⭐ **只领头浪**：`layerAlpha(adv, sEnergy)` 的 `appear` 项 | `strength = 0.32 + 0.68·nearness`（下限由 `0.22` 抬到 `0.32`）、`thresh = ENERGY_TO_LAYERS·0.5·(1 − nearness)`、`appear = 0.30 + 0.70·smoothstep(thresh, 1, energy)`。⛔ **非领头浪的 `kA` 不吃音频**（§4.3.9 ③）——它的浪花是位置指示器 |
| 响度 `A.sEnergy` | 动态 | **水色深浅**：`shadeBoost = 1 + 0.28·e`、`depthBias = 0.10 + 0.55·ENERGY_TO_SHADE·e`（`ENERGY_TO_SHADE = 0.45`）、浅滩带宽度 `0.30 + 0.22·e` | 高潮 ⇒ 更深更饱和；安静 ⇒ 更浅更透 |
| 响度 `A.sEnergy` | 动态 | **粼光网强度** `CAUSTIC_A·(0.30 + 0.70·e)` | ⛔ 不整屏叠，只在海水裁剪区内 |
| 高频段 `A.sHigh` | 显示 | **飞沫点数** `floor(SPLASH_MAX·clamp(0.15 + 0.85·sHigh))` + alpha、**沙纹 alpha**、**残沫白点数** `ceil(RESIDUE_MAX·clamp(0.40 + 0.60·sHigh))` | 高频 = 碎沫与沙面细纹（§4.4） |
| 中频 `A.sMid` | 显示 | **浪花手指长度** `(0.35 + 0.65·sMid)` + alpha 用 `0.4 + 0.6·sLow` | 浪爬沙的「舌头」长度 |
| 全零频谱 | 显示 | 队列仍按 `WAVE_FOLLOW_Y` 补浪（`WAVE_GAP_MS` 只当海面空时的最小闸）、水线仍涌退、浪脊仍慢漂移、⭐ **非领头浪的白浪花完全不受影响** | 静默态**也必须是动态的**（用户硬要求）；`bassAvg`/`setImp` 归零不影响任何时序 |

⚠️ **诚实标注**：非领头浪的 `kA` 现在**与音频完全无关**，所以 V11「低频→浪更宽更白」在真机上**只能观察到领头浪**的变化；⛔ 不要把「第二条浪随音乐变强/变弱」写进验收项——那已被 owner 否掉（他要的是位置可见性）。

**音频读取与平滑**（对应 Kotlin 侧 `AudioSmoother`）：分频取 `≤250Hz / ≤3kHz / 其余`；显示增益 `sLow = rawLow·1.00`、`sMid = rawMid·1.15`、`sHigh = rawHigh·1.95`、`sEnergy = rawAll·1.35`；一阶低通 **attack 55ms / release 260ms**；`bassAvg` τ=700ms；`setImp` τ=500ms。⚠️ 平滑器**两端都要钳**（`dt` 为负时 `1−exp(−dt·1000/τ)` 会让一极点发散到 ±1e81，污染下游每一个值）。

### 4.7 降级矩阵

原型**没有**画质档位（浏览器），它的降级是**按帧内 `kA`（该浪的合成泡沫强度）与逐列湿润度自动短路**的；Kotlin 端口再叠加 `fx.level`。两张表分开列，勿混：

**① 原型实测的自动降级（照搬）**

| 门槛 | 效果 |
|---|---|
| `amp ≤ SILENCE_FLOOR (0.012)` | 跳过该浪（**在 `kA` 之前**，`drawSwellBands` 顶部） |
| `fade ≤ 0.02` | 跳过该浪（`waveFade`，同样在 `kA` 之前） |
| `kA < 0.06` | 跳过非泡沫浪本体 `drawSwellBody` |
| `kA < 0.10` | 跳过白沫晕 `drawSeaFoamWash` 与蕾丝 `drawFoamLace` |
| `kA < 0.12` | 跳过外海泡沫贴图 `drawOpenSeaFoam` 与扰动前锋 `drawDisturbance` |
| `fillStrip` 内 `ladder[s].a · kA < 0.015` | 该窄带跳过 |
| 窄带 `a < 0.25` | 该段按 `step2 = 2` **隔列半分辨率**采样 |
| 非领头浪 | 用 `FOAM_LADDER_COARSE`（隔一取二的 7 段粗阶梯）而不是 14 段精阶梯 |
| 无列 `wetAmt > 0.012` | 整个湿沙层跳过（`drawWetWash`） |
| 少于 4 列 `wetAmt > 0.05` | 镜面高光跳过（`drawSheen`） |
| 平均 `wetAmt < 0.06` / `< 0.05` | 水洼 / 退水残沫跳过 |
| `vigor < 0.05`（干燥期） | 浪花手指跳过（干燥期 `vigor = 0.10`） |
| 像素预算 | 水体场 `scale = clamp(ceil(sqrt(W·seaPx / 52000)), 4, 14)`；沙纹理 >320 万像素按比例降采样 |
| `prefers-reduced-motion` | `TIME_SCALE = 0.35`（整体放慢；Kotlin 端口对应低档 0.35×） |
| 暂停 | `dt` 传 `0` ⇒ **队列/退水/干燥全部冻结** |

⚠️ **上面最前面两条要特别注意**：cull 发生在 `amp`/`fade` 上，而**非领头浪的 `fade` 含行程淡入**（`clamp(y / WAVE_SPAWN_FADE_ADV, 0, 1)`，`WAVE_SPAWN_FADE_ADV = 0.20`）⇒ **出生的那一段里 `fade` 本就趋 0**。harness 实测非领头浪 `kA < 0.12` 的帧占 **5.3%**，且**集中在起步清晰化段**——这是设计内的，不是缺陷；领头浪的 `kA < 0.12` 占 **28.4%**，全部来自抵滩后 `WAVE_FADE_MS = 900ms` 的消散期，同理。两条都**不得**通过下调 cull 阈值来「修」。

**② Kotlin 端口的 `fx.level` 映射（端口自有，原型未验证，需真机校准）**

| `fx.level` | 浪队列 | 泡沫层 | 蕾丝 / 破洞 | 沙纹 / 飞沫 / 残沫 | 水体场 | 是否提供 |
|---|---|---|---|---|---|---|
| HIGH / FULL | 全部在册浪（`WAVE_POOL = 6` 上限） | 精阶梯 14 段 + 贴岸唇 | 全量 + `punchHoles` | 全密度 | 全分辨率场 | ✅ |
| MEDIUM / LITE | 全部在册浪 | 精阶梯 + 外侧浪改粗阶梯；`kA` 门槛整体抬高 | `punchHoles` 上限减半，蕾丝隔类跳画 | 半密度 | 分辨率 ×0.7 | ✅（默认） |
| LOW / OFF | 全部在册浪（⛔ **不减少条数**——队列是机制不是特效），⛔ 但**离岸最远的浪按 `fade` 的行程淡入自动淡出**（非领头浪 `kA` 不吃音频，所以「按 `kA` 淡出」对它是恒定的，只剩行程淡入这一条） | 粗阶梯；跳过贴图泡沫 / 扰动前锋 / 白沫晕 | 跳过蕾丝（留唇 + 窄带） | 沙纹与残沫 1/4，飞沫关闭 | 只留底色渐变（省一次全屏 blit） | ✅（BASIC 三档全可见） |

⛔ **降级绝不改队列语义**：补浪时机、破碎线行程、消散、退水交接与画质档无关——那会让同一首歌在不同画质下呈现不同的海况。

### 4.8 生命周期与资源所有权

- `onEnterContent(ctx)`：① ⛔ **不在此按尺寸烘焙**（`ctx.canvasSize` 还是 `Size.Zero`）；② ⛔ **不复位时间原点**（画质切换会重入 `onEnter`，复位会让浪的行程与水线突然跳变）；③ 只重置不依赖尺寸的状态。
- `drawContent(...)`：若尺寸变化 ⇒ 调 `rebuildGeometry(w, h)`——建逐列场 + `SizeCache` + `buildGrain` + `buildSandTexture` + `buildFoamTiles` + `buildLaceNet` + **`buildCausticNet`** + **`resetWaves()`**（⛔ 换尺寸时不该留半个队列）。⚠️ 这一步在**首帧**执行；开销闸门 = 沙纹理按 320 万像素降采样 + 6 张 256² 泡沫贴图 + 128px 颗粒图案 + 蕾丝拓扑 + 焦散胞壁拓扑（`20×9 = 180` 个节点 / `(CNX−1)·CNY + CNX·(CNY−1) = 331` 条边，只烘坐标与属性，无像素运算），可接受（⚠️ 真机实测首帧时长，V5）。
- `onExitContent()`：显式 `recycle()` 本类自持的位图（沙纹理 / 6 张泡沫贴图 / 湿沙与高光小条），try/catch；清 `Path`；⛔ 不调 `ProceduralTexture.release()` / `OverlayFx.release()`（归舞台与基类）。
- ⛔ 尺寸守卫分支里**不得**放任何 `ProceduralTexture.ensure*` 调用（§3.3），也⛔ 不需要——本效果一张程序纹理都不取。

### 4.9 ⛔ API 22 提交预算与合批架构（2026-10-04 性能裁决，**优先级高于任何视觉微调**）

> 本节是 §4.1–§4.8 的**硬约束前置条件**。§4.7 降级矩阵按原稿写完后实测不可行（见下「实测超标」），本节的合批改造是**实施前必须先做的**。

#### 4.9.0 ⭐⭐ 保真度第一原则（所有者 2026-10-04 追加，**凌驾本节全部条款之上**）

> **目标效果 = `docs/archive/seaside-preview.html`（所有者已确认定稿）。**
> **HIGH 档必须与 HTML 一致；允许偏离的只有 MEDIUM / LOW。**

**为什么必须有偏离**：原型实测 **≈830 提交/帧**，而 E42 星空（本项目唯一真机判通过的同类）实测 **≈200**。两者不能兼得 ⇒ 偏离是必然的，**问题只是偏离被放在哪一档**。任何削减若发生在 HIGH 档，就是错的。

**⛔ 提交预算不是砍画面的授权。** 合批（多次提交合并为一次批量）是**保真**的优化；删图层、多 pass 归一、精度降级是**减损**的优化，只能落在 MEDIUM / LOW。

**当前已发生的保真度代价登记**（实施时逐条核销）：

| 类别 | 项 | 所在档 | 判定 |
|---|---|---|---|
| A 档位降级（可接受，本就是分档的本意） | 焦散射线 + 亮结仅 HIGH；`drawDisturbance` 全档删除；低档 `fillStrip` 不打 evenodd 破洞 | MED / LOW | 保留 |
| **B 静默丢图层（发生在 HIGH，判为过头）** | `drawResidualStreaks` **3 pass 归 1**（只取 pass 0）；`drawWetLine` **2 pass 归 1**（只取 pass 0）；`drawPuddles` **本体/反光两色归 1**；焦散射线/亮结**逐个 alpha 与线宽各归一档** | **HIGH** | 必须在批 3b 回退到多 pass，或明确降级到 MEDIUM / LOW |
| C 精度损失（可接受，须留痕） | 蕾丝 `wj` 逐边变化折成代表值（已由加权均值改为 **4 类 × 3 档 = 12 支**，仍非逐边）；4K 上沙纹理 >320 万降采样；`SeaOpItem` 内若干元素归一到单档 | 全档 | 保留但留痕 |

**B 类的处置原则**：多 pass 本身就是「同一元素画多次」，每次各只 1 次提交（`drawLines` / `drawPath`），代价极低，却直接决定画面层次。⇒ **优先回退 pass 数，而不是接受画面变单薄。**

#### 4.9.1 预算标定（唯一可信锚点 = E42 真机实测）

| 参照 | 每帧提交数 | 每帧填充 | 实测帧率 |
|---|---|---|---|
| **E42 星空星轨**（唯一真机判通过的同类） | **≈200** | ≈2.0 屏 | **29.7 fps** |
| `LowTierElementBudgetTest.ABS_BUDGET` | 400 | — | ⚠️ 折算≈**7–10 fps**，它其实是「不许个位数」门，**不是** 30fps 门 |
| **本方案原稿（HIGH）** | **≈830** | ≈6.9 屏 | **推算 8–10 fps ❌** |

⇒ **本效果的可提交预算：LOW ≤ 90、HIGH/MEDIUM ≤ 200**，对齐 E42 的已验证包络。

⚠️ **§4.3.6 记的「97 `createLinearGradient`/帧 = +0.1ms」是桌面 Canvas 2D 的数字，不可移植**。API 22 电视默认跑 **Dalvik**（无 JIT／无内联／无标量替换）：每次 Kotlin 调用都是一个解释帧，每个 `Float` 读都是真实 `iaget`。所以要同时守**两个**预算：**提交数**（HWUI display list）与**解释调用数**。97 个原生 `LinearGradient`/帧在 API 22 上既是 97 次提交、又是 97 次 native 堆着色器分配。

#### 4.9.2 实测超标项与四处结构性必改

原稿按元素统计的每帧提交数（稳态＝2 条浪）：

| 元素 | 原稿 HIGH | 性质 | 裁决 |
|---|---|---|---|
| ⭐ `drawWetWash` + `drawSheen` | **194** | 97 个逐列小渐变 quad | ⛔ **改为 1 次提交**：一条 `wetRegionPath` + 一个缓存的竖向渐变。`wetEdge[i]` 成为该 path 的**下轮廓**、水线成为**上轮廓** ⇒ ⛔ 不需要 clip、不需要逐列着色器，原稿的 `WET_OVER=16px` 技巧被 subsume |
| ⭐ `SPLASH_MAX=180` + `RESIDUE_MAX=52`×3 笔 | **336–516** | 极小点 | ⛔ **改为 `nativeCanvas.drawPoints(FloatArray, 缓存Paint)`**（E42 先例 `StarrySkyRenderer.kt:511`）：飞沫 **1 次**提交（缓存 `FloatArray(360)` + `Paint.Cap.ROUND`）、残沫三笔 **3 次**（3 个缓存 `FloatArray(104)`）。⛔ **不要烘成位图**——保留逐点 alpha 且零分配 |
| ⭐ `SEA_FOAM_PATCH=22` + `DISTURB_PATCH=18` | **62** 次 alpha blit（最高 ~4 Mpx） | 小 blit | ⛔ `drawDisturbance` **全档删除**；外海泡沫 22 → **8**，且用 u-v 空间预烘轮廓合成**一条 path 一次 fill**（⛔ 不是 8 次 `drawImage`） |
| ⭐ 焦散 30 射线 + 56 亮结 | **86** | 极小点 | ⛔ 射线与亮结**仅 HIGH 档**，MEDIUM 及以下全删；56 个 `drawCircle` 折成同 3 条 path 里的线段。**§4.7② 的 LOW 行漏了焦散 ⇒ 补上「LOW = 0」** |
| 蕾丝网 3 档×4 类 | 24 | 细描边 | ⚠️ 线宽逐边变化（`lineWidth=(0.55+0.85·wj)·bandW·kindW`）而 `Stroke.width` 不可变 ⇒ ⛔ **`wj` 量化**，缓存 **12 个 `Stroke`** ⇒ 12 次批量 stroke（HIGH）/ 3（MED）/ 0（LOW）。**保留「端点共享」**——那才是它读作泡沫的原因，不是形状 |
| 沙纹 26×3 档 / 浪花手指 30 / 螃蟹 | 78 / 14 / 35 | 细线 | 合并为 `drawLines`：**3 / 1 / 2 次**提交 |
| `postFx` 双通道 | 2 全屏 | 2.07 Mpx | ⛔ **只保留一个通道**：`PostFx(vignette = 0.30f)`，⛔ 去掉 `grain` |

**改造后**：HIGH **≈95 提交 / 2.6 屏**（⇒ 落在 E42 已验证的 29.7fps 包络内且有余量）、MEDIUM ≈65、LOW ≈25。

⛔ **保持不变**（这些既便宜又是效果本体）：浪队列机制、每条浪带 1 条 path、蕾丝网**连通**拓扑、沙面烘焙、水体场。**以上改造一律不碰时间轴 ⇒ R1「动态性」不受任何影响。**

#### 4.9.3 ⛔ `clipPath` 禁令范围纠错（本条与原稿 §9 冲突，以本条为准）

原稿 `:149` / `:1090` 只禁**圆角** clip。但本项目在 Android 5.1（创维 rtd299o）真机**三次复现** hwui `Region::createTJunctionFreeRegion` 段错误（RenderThread SIGSEGV），⛔ **禁用范围是整个 `clipPath(`，不分圆角与否**。

⇒ **⛔ 全文件零 `clipPath(`、零 `clip(RoundRect`、零 `RoundedCornerShape`。** 替代方案：
- `drawSand` 只用 `clipRect(SAND_TEX_TOP..h)`（矩形，可接受）；
- 波浪形水线 clip 的 5 处（`drawSand` / `drawWetWash` / `drawRipples` / `drawResidue` / `drawCausticNet`）**改为填充几何**：水线上缘由「湿沙填充 + 之后画的泡沫带」自然覆盖；
- `drawRipples` / `drawResidue` ⛔ **删掉 `clip: Path` 参数**，改用逐列 `wetAmt` 门控（这本来就是 clip 的用途，已在设计里）。

#### 4.9.4 ⛔ 四条会让门禁**静默失效**的陷阱（E42 已踩，务必先读）

| 陷阱 | 后果 | 正确写法 |
|---|---|---|
| ⛔ `PerfBudgetContractTest:100` 正则只匹配 `fun DrawScope\.drawXxx(` | 方案 §14.3.4 的签名（如 `private fun drawFoamLace(layer, w0, kA, t, dir)`）**没有 `DrawScope.` 接收者 ⇒ 约 15 个每帧绘制函数整体逃过零分配 / `Rect(` / 容器分配扫描**——而那正是这些东西最容易长进来的地方 | **每个每帧绘制辅助函数一律声明为 `private fun DrawScope.drawXxx(...)`**。零成本，把 15 个函数从「无门禁」变成「有门禁」 |
| ⛔ `RendererBaseContractTest:330-333` 对类头 400 字符做 `window.indexOf('{')` | 若 `: RendererFx(` 之前出现 `{`（如 lambda 默认参数），**`SeasideRenderer` 被静默剔除出 G5 扫描**；`real.size >= 5` 仍通过（别的类还在）⇒ 门禁形同虚设且**不报错** | 类头**写成一行**：`class SeasideRenderer : RendererFx() {`。`FxCoverageScanTest:194-198` 同一陷阱 |
| ⛔ `PerfBudgetContractTest:137` 禁 `.sortedBy {` / `.map {` | 浪带要「按 y 升序」⇒ 用 E42/Dna 写法：预分配 `IntArray` + 插入排序，零分配 | 禁 `sortedBy`/`map`/`filter`/`toList` |
| ⛔ `punchHoles` 用 `addOval(Rect(...))` 而非 float 重载 | 每帧 **378 个 `Rect` 分配**，并直接触发 `hasAllocRect` | 一律 `addOval(l,t,r,b)` / `addRect(l,t,r,b)` **float 四参重载** |

⚠️ `PerfBudgetContractTest` 的 `hasStringTemplate`/`hasAllocRect`/`hasContainerAlloc` **不检查 `Brush.verticalGradient(...)` 的逐帧 vararg 分配**（E42 KDoc 正好踩中）。⇒ `SeasideTest` 需**自建** `Brush\.` / `ShaderBrush(` 源码扫描补这个洞。

⚠️ `PerfBudgetContractTest:140` 只 `walkTopDown` `renderers/` + `photo/` ⇒ **`SeasideWaves.kt` 必须放在 `visualizer/renderers/`**（放 `visualizer/` 根目录则完全不被扫描）。但**无 `DrawScope` 接收者的普通模拟函数仍然不可见** ⇒ `SeasideTest` 需**单独**为模拟文件加源码扫描（零分配 / 无 `Rect(` / `FloatArray(` 不在初始化路径 / 无 `listOf`/`.sortedBy`）。
⚠️ `RendererBaseContractTest:170` 的「禁自建 rng」只扫 `RendererFx` 子类 ⇒ **`SeasideWaves` 里若藏 `VisualizerRandom()` 是看不见的**。保持纯 hash（`hash32`/`hash2`，§14.3.1）并**自建源码断言**。

#### 4.9.5 ⭐ 新增三道纯函数门（把性能从「真机目测」变成**构建期失败**）

⚠️ 真机在本轮**不可达**（`adb connect 192.168.0.114:5555` 超时 10060），所以性能守门**必须**落在 JVM 单测里。且渲染器因字段初始化会建 `Path`/`Paint`/`Bitmap` 而**无法在 JVM 中 `new`** ⇒ 门禁的唯一可行形态是：**把计数做成纯函数、而不是源码抓取**。

| 门 | 纯函数 | 断言 | 负向自证（必须） |
|---|---|---|---|
| **G11 提交预算** | `SeasideOpBudget.estimate(level, waveCount, …): Int`，逐元素一项 | `LOW ≤ 90`；`MEDIUM ≤ 200`；`HIGH ≤ 200` | 注入**改造前**的常量（97 湿沙列 / 180 飞沫 / 52×3 残沫 / 22+18 贴图）⇒ 必须失败 |
| **G12 填充预算** | `overdrawEstimate(w, h, level): Float`（单位「屏」） | `LOW ≤ 2.0`；`MEDIUM ≤ 2.8`；`HIGH ≤ 2.90` | 同上，必须失败 |
| **G13 native 堆** | `nativePxBudget(w, h, level): Int` | `≤ 3_000_000`；且断言沙纹理按 `texH = h − SAND_TEX_TOP·h` 而非 `h` 烘 | 同上，必须失败 |

> ⚠️ **`OVERDRAW_MAX_HIGH = 2.90` 是补的**（2026-10-04，orchestrator 裁决）：本节初稿只给了 LOW/MEDIUM 阈值，而 §4.9.2 的「改造后 HIGH ≈2.6 屏」**从它自己的元素表算不出来**。诚实模型算得 HIGH = 0.540（干沙 blit）+ 0.820（远海底色渐变，§4.3.1 L1）+ 0.820（水体场 blit，§14.3.7）+ 0.116（湿沙）+ 0.050（焦散）+ 0.398（逐浪族×2）+ 0.070（杂项）= **2.815 屏**，其中三项是**几何恒等式**、无法靠调参消掉 ⇒ 2.6 少算约 0.2 屏。取 2.90（高于诚实值 3% 余量、远低于改造前的 **8.081**）。`LOW ≤ 2.0` 与 `MEDIUM ≤ 2.8` 一字未改。改造前 HIGH 提交 **920**、填充 **8.081 屏**，负向自证以这两个数为锚。

> ⚠️ **`postFx` 的填充计 0、G11 仍计 1 次提交**（2026-10-04 裁决）：口径必须与 E42 标定一致——oracle 引的 E42 实测「≈2.0 屏 = 29.7 fps」是**不含 postFx** 的口径，而 `postFx` 是所有效果共有的固定成本。若把 seaside 的 postFx 计入而 E42 不计，会造成 2 屏对比 3 屏的口径错位。⇒ `overdrawEstimate` 中 postFx 的 `fill*` 定为 0（理由不是「它由舞台画」，而是**口径对齐**）；`estimate` 中仍计 1 次提交（提交数才是 `478 小矩形 ⇒ 7.6fps` 那个标定的驱动量）。

> ⚠️ **G13 与 §4.7① 在 4K 上互相冲突（未解决，记入 §12.5 U8）**：`3840×2160` 下沙纹理 `3840 × 1166.4 = 4,478,976` 被 §4.7① 的 320 万钳住，加其余 ≈ 0.44M ⇒ **3,644,781 > 3,000,000**。两条规格无法同时成立。当前裁决：**不动 §4.7① 的 320 万**，单测只断言到 2560×1440，4K 留作待裁定。

⭐ **当前已钉死的实测基线**（2026-10-04，`waveCount = 2`；`SeasideOpBudgetTest` 用**精确合计**断言，任何系数漂移都会在那里暴露而不是躲在门限后）：

| 档 | 提交数 | 门限 | 余量 | 填充（屏） | 门限 | 余量 |
|---|---|---|---|---|---|---|
| LOW | **29** | 90 | 61（68%） | **1.7213** | 2.00 | 0.2787（13.9%） |
| MEDIUM | **44** | 200 | 156（78%） | **2.7585** | 2.80 | ⚠️ **0.0415（1.48%）** |
| HIGH | **81** | 200 | 119（60%） | **2.8106** | 2.90 | 0.0894（3.1%） |
| 改造前 | 29 / 802 / 885 | — | — | 8.0771 | — | — |

⛔ **MEDIUM 填充余量仅 1.48%（≈68px @1080p）** —— 下一轮往 MEDIUM 加**任何**全屏元素前**必须先重算 G12**。⚠️ 不得为腾空间把某元素的 MEDIUM 填充算成 0 来绕过门限（`fill*` 必须与 `ops*` 同口径：焦散已在 D9 里把 `fillMed` 与 `opsMed` 绑成一步到位）。

⚠️ **预算表与渲染层有一处待对齐（D9 遗留）**：`CAUSTIC.opsMed` 已放开为 3（胞壁网 MEDIUM 保留），但 `SeasideRenderer.drawCausticNet` 仍是 `if (seaLevel != SeaLevel.HIGH) return` ⇒ **预算表已授权、渲染层未跟上**，下一轮必须对齐（同时清掉 `SeasideRenderer.kt` 第 46 / 855 / 1225–1230 行三处已过时的 KDoc）。

配套源码扫描断言（同样要负向自证）：
- `drawWetWash`/`drawSheen` 函数体：无 `createLinearGradient`、无 `Brush.verticalGradient`、无 `drawRect(brush =`，**恰好 1 个 `drawPath(`**；
- `renderers/Seaside*.kt` 全文件：**零** `clipPath(`、零 `clip(RoundRect`、零 `RoundedCornerShape`；`drawRipples`/`drawResidue` 签名不含 `clip: Path`；
- 每个 `private fun draw*(` 都是 `fun DrawScope.draw*(`；
- 蕾丝缓存 `Stroke` 实例 **≤ 12**；
- G7 已规定：每个 `Bitmap` 字段在 `onExitContent` 里 try/catch `recycle()`（做扫描断言）。

⚠️ `LowTierElementBudgetTest` **硬编码只扫 Galaxy/Constellation/LyricsDotMatrix ⇒ 对 seaside 零覆盖**。上面的 G11/G12 就是它的替代品，不要指望它。

---

## 五、关键参数表

> ⭐ **v1.2：本表数值全部取自 `docs/archive/seaside-preview.html`（原型定稿）的实际定义**，Kotlin 端口逐项照搬即可。仍需**真机调优确认**（E42 教训：多项参数经真机返工），但**结构与量纲不要再猜**。
> ⛔ §5.5 末的「死常量」小节列出原型里**定义后仅出现在注释里**的常量——Kotlin 端口**直接删掉**，不要照搬。

### 5.1 构图与岸线

| 参数 | 值 | 意义 |
|---|---|---|
| `SHORE_K` | `0.620` | 岸线**平均**位置（占 h）。反馈七·2 所有者裁决：`0.740 → 0.620`（海略过半屏，同时留够沙滩给颗粒） |
| `SEA_BOTTOM_K` | `SHORE_K + 0.20` = `0.820` | 水体场（`drawSeaField`）与粼光网的裁剪下界。必须 ≥ 水线的**最高**位置：`swashFrontY` 峰值（`0.620 + 0.006 + CREST_AMP_SHORE·0.3 ≈ 0.665h`）+ fray 项（≈3px）+ `h·swashReachNow` 上限 `0.105·1.10 = 0.1155h` ⇒ 约 `0.784h`，留破碎余量到 `0.820`。⚠️ 早先的注释按旧的 `SHORE_K = 0.740` 与已废弃的逐浪涌高 `0.105·1.40` 推算，本行已按当前表达式重算 |
| `SWASH_REACH` | `0.105` | 浪完全涌上滩时水线高出平均岸线的距离（占 h）。`0.095 → 0.105`（沙滩从 `0.26h` 变 `0.38h`，多了 1.46 倍，故上调），留 `0.275h` 干沙滩 |
| `SWELL_BAND_W` | `0.042` | 浪带宽度基数（占 h） |
| `REACH_LO` / `REACH_HI` | `0.55` / `0.85` | 逐列涌高系数区间（`pow(fbmNorm, 1.6)`） |
| `SWASH_LAG` | `0.16` | 逐列冲流滞后（占 y）⇒ 水线参差成舌 |
| ⭐ `WAVE_SPAWN_DEPTH` | `0.66` | **破碎线的出生水深**（占 h，`spawnFar = h·(SHORE_K − 0.66) = −0.04h`）。⛔ 名字**不是** `WAVE_SPAWN_DEPTH_K`，原型里没有带 `_K` 的版本；`0.42` ⇒ `spawnFar = 0.20h ≈ 180px`（浮在顶部 1/5 处，owner 报「不是从边缘进入」）；`0.66` ⇒ 出生点在顶边上方 36px、破碎线行程从 ~459px 拉长到 **~636px** |
| `COLS` | `96` | 横向采样列数（每条浪 + 岸线共用）⇒ `COLS + 1 = 97` 个逐列状态 |
| `CREST_AMP` | `0.220` | 破碎线浪脊横向起伏幅度（占 h，峰峰 ~164px @1600×900） |
| `CREST_AMP_SHORE` | `0.130` | 岸线浪脊起伏幅度（峰峰 ~97px）。⭐ 与 `SAND_TEX_TOP` 一起定住水线最小 y = `0.484h` |
| `CREST_LANE_SHIFT` | `0.18` | 相邻浪在剖面 `u` 上的错开量（**共用同一条** `crestProfile`，靠 `lane` 错开） |
| ⭐ `CREST_DRIFT_ADV` | `0.35` | 破碎线浪脊相位随**该浪自己的 `adv`** 漂移的总量（u 单位）。⛔ 早先挂全局时间 `t·0.0000060` ⇒ 一生 ~10s 只变 0.06 ⇒ 读作固定曲线；⭐ 挂 `adv` ⇒ 整条旅程走 0.35u（≈103px），每帧 ≈0.10px |
| ⭐ `MORPH_START` | `0.55` | **交接地形态连续化**的起点（adv）。后浪从它起把**整个位置**连续插值到 `shoreYs` ⇒ 位置与曲线形态一起变成前浪的，`adv = 1` 时严格相等。⛔ 早先的做法是在最后 15%（`SWASH_LEAD_ADV = 0.85`）才用 `crestK` 把 198px 的起伏淡出到 0 ⇒ owner 报「接近前浪时曲线形状跳变」。⚠️ 取 `0.55` ⇒ 过渡占行程 45% |
| ⭐ `SAND_TEX_TOP` | `0.46` | 沙纹理起点。⛔ v1.2 写的 `0.62` 是错的：必须**高于水线最大摆动** `SHORE_K − CREST_AMP_SHORE − 潮汐 = 0.484h`，否则退水时 `0.484h…0.62h` 露出海水底色（owner 同时报「沙与底色的交界」+「退水退到快中间就不退了」，同一根因）。`0.46` 留 `0.024h ≈ 22px` 余量 |

### 5.2 离散浪队列（浪的动态核心）

| 参数 | 值 | 意义 |
|---|---|---|
| `WAVE_POOL` | `6` | 同时在册的**槽位上限**；稳态只用 2 个（1 在最前 + 1 排队）⇒ 余量留给「过渡帧两条同框」。harness 实测海面浪数分布 `{0: 141, 1: 1501, 2: 3757}` 帧 ⇒ **2 条占 95%** |
| `WAVE_GAP_MS` | `2360` | **最小出浪间隔**——⛔ 只作「海上一条浪都没有」那条路径的闸门，不决定稳态浪数（改 `WAVE_FOLLOW_Y` 才改浪数）。稳态实际出浪间隔 ≈ `0.62 × T_领队` ⇒ **约 2.6–3.6s**（导出量，不是常量） |
| ⭐ `WAVE_FOLLOW_Y` | `0.62` | **补浪时机**：滩上无浪、且**恰好一条** `ADVANCING` 浪已走过 `y ≥ 0.62` 时，补一条排在它后面。⭐ 纯定时器补浪时「两个槽位同时存在」只占 **16%** 的帧（读作「海上只有 1 条浪」），按进度补后升到 **95%** ⇒ 稳态恒为「**1 条在最前 + 1 条排队**」（所有者裁决，本轮先说「2-3 条就够」，后修正为「只留 1 条排队」） |
| `WAVE_T_MIN` / `WAVE_T_MAX` | `4200` / `5800` | **领队浪**从出生跑到滩上的行程时间（`v = 1/T`） |
| ⭐ `FOLLOW_T_SLOW` | `2.0` | **跟随浪**（海面上第二条）的行程倍率 ⇒ `T` ∈ `8400…11600ms`。owner：「第二条浪走的太快了，前浪还没来得及回退」。⚠️ 调大幅度会同时拉长整个周期 |
| `W_REACH_Y` | `1.00` | `y` 到 1.0 = 「到达滩上」⇒ 消散。⛔ 原型里另有一个同值的 `WAVE_REACH_Y = 1.00`（§5.5 死常量行） |
| `WAVE_SPAWN_RAMP_MS` | `700` | 出生时泡沫**时间**淡入（no pop） |
| ⭐ `WAVE_SPAWN_FADE_ADV` | `0.20` | 出生时泡沫**行程**淡入（占 adv）。⭐ 与上一条**相乘**才构成完整 `waveFade`；⛔ 只留时间淡入时后浪读作「凭空出现」（`700ms` 在 ~10s 的一生里只占 7%） |
| `WAVE_FADE_MS` | `900` | 消散时泡沫原地淡出（与退水**并行**，§4.3.2.3） |
| ⭐ `SWASH_LEAD_ADV` | `0.85` | 破碎线走到这个 `adv` 就**开始驱动冲流**（`swashT` 起累加）。⚠️ 对 `REACHED`/`FADING` 也累加 ⇒ 「破碎线抵滩」与「冲流满位」同一帧发生 |
| ⭐ `SWASH_RUNUP_MS` | `900` | 冲流从 0 爬到满位所需时间（`push = clamp(owner.swashT / 900, 0, 1)`） |
| `SWASH_RETREAT_MS` | `440` | 退水时长**下限**（⛔ 不再是定值！owner：「前浪回退不应该退到中间就停止了」） |
| ⭐ `SWASH_RETREAT_MAX` | `1600` | 退水时长**上限**。⛔ 完全按后浪 ETA 自适应会把退水拉到约 5.7s（`94.5px/5.7s ≈ 17px/s`），实测中位后退只剩 **6.1px**——那已经不叫退水了 |
| `retreatDur`（导出量，非常量） | `max(440, min(1600, eta))`，`eta = max(0, (0.85 − front.y) / front.v)` | ⭐ 让退水**恰好在后浪进入上涌区那一刻收尾**。实测退水 **14 次/90s**，中位 **1567ms / 83.7px**（满程 94.5px 的 89%） |
| `SWASH_DRY_FRAC` | `0.60` | 干燥时间常数挂在出浪间隔上的比例 |
| `SWASH_DRY_MIN` / `SWASH_DRY_MAX` | `420` / `2600` | 干燥时间常数的钳位（ms） |
| `dryTau`（导出量，非常量） | `clamp(2360·0.60, 420, 2600)` = **1416 ms** | ⭐ **不写死**：挂在出浪间隔上 ⇒ 改 `WAVE_GAP_MS` 时干燥自动跟随 |
| 逐浪涌高系数（表达式，非常量） | `0.62 + 0.78·fbmNorm` ⇒ `0.62..1.40` | ⚠️ 当前**不参与任何几何**（逐列 `reachK` 已被移除，见 §4.3.2.1）；Kotlin 端口可保留字段但不必强求效果 |

**状态常量**：`W_EMPTY = 0` / `W_ADVANCING = 1` / `W_REACHED = 2` / `W_FADING = 3`。⛔ **没有 `DEAD`**——淡出完毕直接回写 `W_EMPTY`（槽位回收）。

### 5.3 泡沫律与泡沫剖面

| 参数 | 值 | 意义 |
|---|---|---|
| `FOAM_SLOPE_GAIN` | `0.62` | 倾角律增益；代码里再乘 `4.0` ⇒ **有效 2.48 / px** |
| `FOAM_FAR_SPAN` | `0.50` | 距离律覆盖的离岸距离跨度（占 h，`0.62h → 0.12h`） |
| ⭐ `FOAM_FAR_POW` | `1.6` | 距离律指数（**领头浪用不到**——它的 `farLaw` 被硬编码为 1）。⛔ v1.2 写的 `2.4` 已降：实测 `pow 2.4` 时第二条浪起步段 `farLaw` 仅 **0.043**、`kA` **0.061**，**67% 的绘制调用被 cull**。⭐ **非领头浪另用 `0.35` 幂**（与 `drawDisturbance` 同款）⇒ 起步段仍全程可见，最外侧仍趋 0 |
| `SHORE_FOAM_BOOST` | `1.55` | ⭐ 贴岸领头浪带的泡沫额外加成（反馈七·3「水线处白色更多」） |
| `FOAM_EDGE_A` | `0.85` | 前缘峰值（`foamEdgeAt` 内再乘 `0.94`） |
| `FOAM_CORE_A` | `0.42` | ⭐ 浪心（双峰的第二个峰）峰值，⛔ 不得超过它 |
| `CREST_OUT` / `CREST_HOLD` | `0.42` / `0.14` | 前缘高光：保持全亮到 `0.14` 宽才衰减，到 `0.42` 归零 |
| `HEART_IN / FULL / HOLD / OUT` | `0.20 / 0.45 / 0.62 / 0.88` | 浪心帐篷的四个边界，⛔ 两端都低于中部 |
| `TAIL_IN` / `TAIL_OUT` | `0.66` / `1.25` | 拖尾二次渐隐，正好归零 |
| `FOAM_EDGES` | 14 段 `[0,.035,.075,.125,.185,.255,.335,.425,.525,.635,.765,.905,1.07,1.25]` | 剖面量化的互不重叠窄带（14 段 rmsErr 0.026；只用 10 段会升到 0.038，浪带内部显出「等高线」分层）。外侧弱浪用隔一取二的 `FOAM_LADDER_COARSE` |
| ⭐ 带宽下限 `shrink` | `0.30`（代码里是**字面量**、无命名常量） | ⭐ **硬不变量**：必须加在 `max(0.30, frayK·envK·slK)` 的**乘积**上。⚠️ ⛔ 不加 ⇒ `envK = 0.02 + 0.98·pow(fbmNorm, 2.4)` 可趋 0 ⇒ 那一帧整条带宽度为 0 ⇒ 读作「浪突然消失」（实测塌陷率 `adv 80~90%` 曾达 48.5%）。§4.3.9 ② / 红线 5 |
| ⭐ 非领头浪 `kA` 系数 | `0.55`（字面量） | ⭐ 非领头浪的 `kA = clamp(amp·0.55·fade)` —— ⛔ 不吃音频门控、不吃近岸斜坡。⚠️ 刻意低于领头浪的 `SHORE_FOAM_BOOST = 1.55`，两条浪的主次关系不能反过来。§4.3.9 ③ |
| ⭐ 非领头浪 `amp` 基线 / 增益 | `0.46` / `0.45`（字面量） | `amp = 0.46 + 0.45·bandAmp(1 + wiS % 2)`。⚠️ 基线由 `0.30` 抬到 `0.46`（实测领头浪 `amp ≈ 0.90`、第二条浪 `≈ 0.59`，后者 `kA` 因此只有前者的约 1/5）；只抬基线、**不改增益斜率** |
| `LIP_W` | `0.16` | 破碎唇宽度（浪带宽的倍数）；两 pass 的偏移 `LIP_W·0.35 / LIP_W·0.95`、线宽 `0.9 / 2.5`、alpha `0.85 / 0.42` 写死 |
| `SEA_FOAM_WASH_A` | `0.34` | 波面大白沫晕强度 |
| `SEA_FOAM_WASH_BACK` / `SEA_FOAM_WASH_FRONT` | `2.4` / `0.30` | 白沫晕往后拖几个带宽 / 前缘往前出几个带宽 |
| `SEA_FOAM_WASH_FRONT_NL` | `0.42` | 非领头浪的前出距离（+40%，峰值压到前缘线上） |
| `SEA_FOAM_A` / `SEA_FOAM_PATCH` / `SEA_FOAM_TILES` | `0.46` / `22` / `6` | 外海泡沫贴图强度 / 每浪贴图块数 / 烘出的贴图张数（每张 256×256） |
| `DISTURB_A` / `DISTURB_PATCH` / `DISTURB_SIZE` | `2.40` / `18` / `1.35` | 扰动前锋强度 / 块数 / 贴图尺寸系数 |
| `DISTURB_DEP0` / `DISTURB_DEP1` | `0.30` / `1.45` | 扰动块落在浪脊**前缘前方** `0.30~1.45` 个带宽处 |
| `HOLE_MAX` / `HOLE_PERIOD_MS` | `18` / `1100` | 每条窄带的 evenodd 破洞数上限 / 洞场的**相位推进周期**（白沫「沸腾」；⛔ 260ms 太快，已放缓 4.2×——⚠️ 周期只决定闪烁的**频率**、⛔ 不决定单次**跳变幅度**，连续性由每洞的连续相位 + `grow` 保证，§4.3.5 / G9）。破洞另有丢弃阈值（`ry < 1.5` 或 `rx < 2.5`）与横向拉伸 `rx = ry·(1.1 + 1.5·hash2(…))` |
| `SWELL_BODY_A` / `_BACK` / `_FRONT` | `0.55` / `2.1` / `0.55` | ⭐ 非泡沫浪本体强度 / 背光面后拖 / 迎光面前出（距离衰减 ≈ pow 0.55，**远比泡沫慢**） |
| `FRAY_K` / `FRAY_A` | `[0.0090,0.0261,0.0592]` / `[0.55,0.26,0.12]` | 破碎场三八度频率 / 振幅（≈0.47 逐级衰减） |
| `FRAY_W` | `[0.00016,−0.00034,0.00064]` | 三八度漂移速率（⛔ 已整体放缓约 4.5×，反馈六·1「海浪太快了」） |
| `FRAY_JIT` / `FRAY_FRONT` / `FRAY_WIDTH` | `0.055` / `0.35` / `0.30` | 每列固定 hash 抖动 / 前缘纵向位移 = 抖动 × `FRAY_FRONT` × 带宽 / 整条带厚度一起缩放（保证窄带边界不交叉） |

### 5.4 沙、纹理与沙纹

| 参数 | 值 | 意义 |
|---|---|---|
| `SAND_GRAIN_A` | `0.062` | 像素级细颗粒（**主导纹理**） |
| `SAND_GRAIN2_A` | `0.030` | 第二层更细的砂纸感 |
| `SAND_RIPPLE_A` / `SAND_RIPPLE_N` | `0.040` / `7` | 宽而柔的沿岸起伏带强度 / 带数（被低频噪声扭曲 ⇒ 不是等距直线） |
| `SAND_DAMP_A` | `0.30` | 深色潮湿斑块的最大压暗量（压向 `#8E7048`） |
| 沙纹理像素预算 | `3_200_000` | 一次性开销闸门，超出按比例降采样（`k = min(1, √(budget / (tw·th)))`） |
| `SAND_LINES` | `26` | 沙纹线数（固定，⛔ 不得随帧变；只有 18% 的线靠 hash 控制**可见性**，不改条数） |
| `SAND_LINE_A` | `0.055` | ⛔ 防摩尔纹的 alpha **上限**；实际三档取其 `0.29 / 0.44 / 0.58`（浅沙上深色 1px 线不可见 ⇒ 三档都改用浅色调） |
| `RESIDUE_MAX` / `RESIDUE_A` | `52` / `0.17` | ⭐ 残沫点数 / alpha。LCG + 最小间距 `0.092` 拒绝采样 ⇒ 孤立不连纹；`v` 偏向湿沙边缘；三笔球体感（软阴影 → 亮芯 → 左上缘高光） |
| `SPLASH_MAX` / `SPLASH_A` | `180` / `0.85` | ⭐ 飞沫点上限 / 强度（实际点数 = `floor(SPLASH_MAX · clamp(0.15 + 0.85·sHigh))`） |
| `MOTTLE_A` | `0.030` | 低频斑驳后处理（16×16 晶格双线性值噪声，⛔ 不含任何高频 ⇒ 无摩尔纹风险） |
| `WET_LINE_A` | `0.42` | 岸线上那道细亮湿线（退水期最亮 ×1.0；上涌期 ×0.55；干燥期 ×0.30） |
| `RESIDUE_STREAK_A` | `0.075` | 退水留在湿沙上的羽状残沫（3 pass 递减） |
| `SHEEN_A` | `0.19` | 湿沙镜面高光强度（`lighter` 加色，越干越弱） |
| `PUDDLE_MAX` | `16` | 洼地小水洼上限 |
| `FINGER_MAX` | `30` | 浪花手指数量（位置按 hash 抖动 ±0.85 格，只有约 45% 真的伸出去） |
| `WET_OVER` | `16 px` | 湿沙 / 高光顶端抬到水线之上的量（⛔ 抬 4px 不够，列间 `shoreYs` 可差 7~8px ⇒ 水线上出现一列列阶梯） |

### 5.5 水体场、音频增益与死常量

| 参数 | 值 | 意义 |
|---|---|---|
| `N_BIG` / `N_MID` / `N_FINE` | `8` / `19` / `43` | 水体场三档互不成整数比的横条纹（`11/29/61` 是规则梳子，已降；间距与对比度另被噪声打散） |
| `CAUSTIC_A` | `0.075` | 粼光网 alpha（再乘 `0.30 + 0.70·energy`；⛔ 不整屏叠，只在海水裁剪区内） |
| ⭐ `CNX` / `CNY` | `20` / `9` | **焦散胞壁网**的胞格数（原型分辨率下约 70–90px 一格：1600×900 ⇒ `1600/20 = 80px` × `900·0.82/9 ≈ 82px`；1920×1080 ⇒ ≈ 96×98px）。每格只连右/下邻居 ⇒ 连通多边形；⛔ **不做周期环绕**（环绕 ⇒ 边界长墙 ⇒ 又读成刻痕）；去规整化幅度一律 ≤ **±0.3 格**（再大相邻胞格自交、网破）。Kotlin 端口按 `W / CNX` 与 `H·SEA_BOTTOM_K / CNY` 现算即可 |
| `SILENCE_FLOOR` | `0.012` | 低于此振幅直接跳过该浪 |
| `SET_TAU_MS` | `500` | ⭐ 鼓点 `setImp` 的指数回落（**只作用于领头浪的泡沫振幅**，⛔ 不碰时间轴） |
| `ENERGY_TO_SHADE` | `0.45` | 响度 → 水色变深 |
| `ENERGY_TO_LAYERS` | `0.60` | 响度 → 弱浪显形阈值（`thresh = 0.60·0.5·(1 − nearness)`，离岸越远门槛越高） |
| 音频平滑 | attack **55ms** / release **260ms** | 一阶低通；⛔ 两端都要钳（负 `dt` 会让一极点发散到 ±1e81，污染下游每一个值） |
| `bassAvg` 时间常数 | **700ms** | 鼓点判据的基线 |
| 鼓点判据 | `rawLow > bassAvg·1.42 + 0.055` | 命中则 `setImp += (rawLow − bassAvg)·2.4`，钳 `0..1` |
| 显示增益 | `sLow ×1.00` / `sMid ×1.15` / `sHigh ×1.95` / `sEnergy ×1.35` | 显示通道（伽马压缩后） |

**已删除的死常量 / 死代码**（原型里定义后仅出现在注释中、或零读取方，Kotlin 端口直接删）：

| 死常量 / 死代码 | 原值 | 说明 |
|---|---|---|
| ⛔ `WAVE_SPACING_Y` | `0.55` | **位置钳位已整体删除**（反馈八·10）。实测后浪 `adv` **从未超过 0.6**、永远到不了滩，**67% 的绘制调用被 cull** ⇒ owner：「中间不停顿」「应该一直推进到沙滩」。⚠️ 原型里只余注释残留，**无定义** |
| ⛔ `MIN_ARRIVE_GAP_MS` | `1300` | 「强行保证后浪比前浪晚到 ≥1300ms」的硬到达间隔，已删。owner 指出两条浪本来就该**独立**（「来得早就退得短、来得晚就退得长」正是要保留的自然变化），强行等间隔既抹掉变化、又把后浪顶到离滩很近处 ⇒ 退水恒为 117ms/20.7px。⭐ 它的**替代物是 `FOLLOW_T_SLOW = 2.0`**（只把后浪的 `T` 分布整体后移，⛔ 不是硬约束）。⚠️ 原型注释里仍在描述它，属残留 |
| ⛔ `waveFrontY(x, t, baseY, h, layer)` | — | 非领头浪的**破碎线尺度**前缘（行程约 340px）。⛔ 已删除：它与领头浪用的**水线尺度**（≈81px）互不兼容，而旧交接判据 `front.y >= a` 拿这两个空间的 `adv` 直接比较 ⇒ 交接瞬移 **150~267px**。⚠️ 端口**不得复活** |
| ⛔ `waveDepthK(w, adv)` | `0.42·(1−adv) + swashReachNow·adv·w.reach` | 把出生水深与上滩距离解耦的那一项，撑起「破碎线尺度」。同上，随 `waveFrontY` 一并删除 |
| ⛔ `CONVERGE_K` / `crestK` | — | 早先用 `crestK = 1 − smoothstep(SWASH_LEAD_ADV, 1, adv)` 把破碎线的起伏幅度**淡出到 0** 来换位置连续。⛔ 已删（198px 的幅度在最后 15% 行程里塌掉 ⇒ owner 报「接近前浪时曲线形状跳变」）；⭐ 改为 `MORPH_START` + `tAdv` 把**整个位置**连续插值到 `shoreYs` |
| `reachK[]`（`FloatArray(COLS+1)`） | `0.55 + 0.85·pow(fbmNorm, 1.6)` | 逐列涌高系数。⛔ 交接连续化时从 `shoreYs` 里移除 ⇒ **数组仍分配、仍每帧写入，但无任何读取方**。Kotlin 端口可直接不实现 |
| `foamK`（`drawSwellBands` 内的局部量） | `slopeLaw[i]·farLaw[i]` | ⛔ 计算了但**不再用于带宽**（带宽改走 `slK`/`envK`/`shrink`）⇒ 该局部量是残留。`foamK` 在 `drawSeaFoamWash` / `drawOpenSeaFoam` 里**仍然活跃**（用于 alpha），别一起删 |
| `WAVE_REACH_Y` | `1.00` | 与真正使用的 `W_REACH_Y` 同值的**重复定义**，零引用 ⇒ 端口只留 `W_REACH_Y` |
| `WAVE_K` / `WAVE_W` / `WAVE_A` / `WAVE_SGN` / `WAVE_PHI` | `[0.0060,0.0125,0.0240]` / `[0.00055,0.00091,0.00148]` / `[0.020,0.017,0.011]` / `[1,−1,1]` / `[0,2.10,4.37]` | 三正弦叠加已被 `crestProfile`（域扭曲 fBm）与水体场的三档噪声相位取代；**零引用** |
| `WAVE_K4` / `WAVE_A4` / `WAVE_K5` / `WAVE_A5` | `0.0405` / `0.0055`、`0.0730` / `0.0034` | 第四、五组谐波，同样零引用 |
| `LIP_A` | `0.55` | `drawCrestLip` 的两 pass alpha 写死为 `0.85` / `0.42`，零引用 |
| `CAUSTIC_SHORE_A` | `0.10` | 岸线处粼光改由 `drawWetLine` 与贴岸各层负责，零引用 |
| `SWELL_BODY_FAR_POW` | `0.55` | 只在函数末尾被 `void` 掉；实际衰减由 `1 / (1 + 3.2·(1 − farLaw))` 内联 |
| `WAVE_SPAWN_K` | — | 旧「水位场/相位」架构里给正弦用的频率系数，新架构下**连定义都不存在**。⛔ 端口不得复活 |
| `SWELL_PERIOD_MS` / `swellPhase` / `SWELL_LAYERS` / `LAYER_PHASE_GAP` / `SWELL_TRAVEL` | — | 反馈二/三的相位三角 + 固定泳道整套，已随架构变更删净（原型全文 0 命中） |
| `SWASH_UP_END` / `SWASH_BACK_END` / `swashCurve` | — | 旧的手写涌浪曲线，推进量改由浪队列提供后删净 |

### 5.6 调色板

| 用途 | 色值 |
|---|---|
| 远海 / 近海 / 深海 | `#2E9AA8` / `#1E7A8C` / `#14586B` |
| 浪脊亮 / 槽底暗 / 浅滩 | `#6FD0CC` / `#0E4A5C` / `#A6D8C4`（与海面明暗语言同一套，供 `drawSwellBody` / `shoalW` 用） |
| 湿沙 | `#B08A5E`（渲染时压向棕/琥珀 `rgba(74,50,26,0.76)` → `rgba(106,84,54,0)`） |
| 干沙（近浪/中/下缘） | `#C9A063` / `#C4A876` / `#D9BE8C`（⭐ 三图色差，取中间调） |
| 沙纹三档（⛔ 都是浅色调） | `#EEDAB4` / `#F7E8CB` / `#FFF5E2` |
| 浪白（前缘 / 唇） | `#F2F7F5` / `#FFFFFF` |
| 浪心（半透明青） | `#CBE7E3`（`PAL.foamCore`；alpha 峰值锁 `FOAM_CORE_A`，⛔ 不用纯白） |
| 泡沫网 / 焦散 | `#EAF6FF` |
| 沙面潮湿斑 | `#8E7048` |

### 5.7 后处理

| 参数 | 值 |
|---|---|
| `postFx.vignette` | `0.30f`（⛔ 字面量，§3.5） |
| `postFx.grain` | `0.020f`（⛔ 字面量） |

> 全部常量收敛在渲染器文件顶部的 `internal companion object`，⛔ 不在 `drawContent` 内出现魔法数（`PerfBudgetContractTest` + 门禁 G6）。

---

## 六、视觉自检清单（真机验收的视觉面）

1. **浪是否动态**：静默（`spectrum` 全零）时画面**仍在缓慢起伏**、浪**仍在一条条向岸推进**——⛔ 不能静止（用户第一诉求）。
2. **破碎前缘**：浪的前缘是否呈**破碎的不规则状**（大瓣 → 碎瓣 → 细丝）而非平直？是否随时间缓慢漂移？
3. **多条离散浪**：是否看得出「**1 条在最前 + 1 条在后头排队**」这一前一后两条浪？⛔ 若读成「一排等距平行白缎带」即失败（四条固定泳道的典型症状）；⛔ 若只看见**一条**浪在推进、后浪始终缺席，即失败（那是补浪时机没按前浪进度走）；⛔ 若**从头到尾都看不见第二条浪**，几乎必然是「把后浪压进了水线空间」或「带宽被 `envK` 乘到 0」（§4.3.2 / §4.3.9）。
3b. ⭐ **第二条浪必须全程可见**：从它在画面顶边上方浮出、到它抵达滩上，**是否每一段都能指出它在哪**？（harness 判据：海面浪数 `2` 占 **95%** 的帧；第二条浪到滩 **15/15**）⛔ 「走到中间就消失」「靠近前浪时突然消失」都是失败——前者是 `farLaw` 太陡 / `kA` 被音频门控压掉，后者是 `shrink` 无下限。
3c. ⭐ **退水深度与并行性**：前浪消散到后浪接管之间，水线是否有一段**看得见的回卷**（往海里退一小段再重新上涌）？且「浪花消失」与「海水回退」是否**同时**发生（⛔ 先后次序即失败）？⛔ 退水窗口短到读作闪动即失败（判据见 §10 V17 的 harness 数值：中位 1567ms / 83.7px）。
3d. ⭐ **交接无跳变**：后浪接管水线的那一刻，带子的位置与**纹理**是否都连续？⛔ 位置跳一下、或纹理整片换掉（泳道号在那一刻翻转），都是失败（§4.3.9 ①②）。
3e. ⭐ **沙面底色不得露出**：把水线退到最低，看沙面与水的交界——是否整片都是**沙的颜色**、没有任何「海水底色」楔子？⛔ 出现「退到快中间就不退了」的观感也是同一根因（`SAND_TEX_TOP` 太高，§4.3.7 / §九 红线 7）。
4. **前缘锐利**：白浪是否**前缘实、向后迅速拖尾**（而非一整条均匀白带）？
5. **半透明**：白浪是否**能隐约透出**下面的水色？⛔ 不能是纯白填充。
6. ⭐ **浪心双峰**（图2/图3）：白浪是否**前缘亮、浪心半透明、尾部归零**？⛔ 若读成「一整条均匀白带」即失败。
7. ⭐ **强度方向**（图3/§14.3.8）：是否**越靠岸的浪越强、越靠海的越弱**？⛔ 反了就失败。⚠️ 注意这一条**只适用于领头浪**：非领头浪的 `kA` 是位置指示器（恒定系数），它的「远」由**带宽更窄 + 逐块 alpha 更淡**表达（§4.3.9 ③）。
8. ⭐ **水色横向起伏**（图2）：水面是否有**横向明暗带**？⛔ 读成「一整块塑料色」即失败。
9. ⭐ **飞沫与残沫**：浪向岸一侧是否有**细碎泡沫斑点**？干沙上是否有**零散孤立白点**？⛔ 残沫**不得连成纹路**（否则退化成沙纹）。
10. ⭐ **swash 周期**：是否看得出「涌上 → 到滩消散 → 水线后退露湿沙 → 下一条浪追上再涌」？退水时是否**露出湿沙**、随后逐渐变干？⛔ 湿沙必须**只在到滩之后**出现。
11. ⭐ **泡沫蕾丝**：白沫是否读成**沿岸铺开的网**（长丝 + 网眼）？⛔ 读成「泡泡浴」或「散落的苍白毛发」即失败。
12. ⭐ **远处浪仍有形状**：最外那条浪是否**没有白沫、但仍有明暗体积感**？⛔ 完全消失即失败（那是把距离律错折进带宽的典型症状）。
13. **沙纹**：干沙上是否有**极细**的横纹？⛔ 不得出现摩尔纹/闪烁。
14. **低频响应**：放强鼓点曲，水线是否**涌得更高**、领头浪带更宽更白？⛔ **不应**看到「浪突然加速前推」或「凭空多出一条浪」——那是音频碰了时间轴。⚠️ ⛔ **不要期待第二条浪随音乐变强/变弱**——它的 `kA` 有意不吃音频（§4.6）。
15. **静音响应**：放纯高频（打镲）曲目，浪是否**只**在沙纹/飞沫/残沫上有反应，浪高不动？⭐ 另需确认：**第二条浪的白浪花仍然全程在**（静音时 `appear → 0.30` 只影响领头浪）。
16. **响度变色**：安静段落 vs 高潮段落，水色是否可见**变深/变浅**？领头浪的显形阈值是否随之变化（`appear` 项）？
17. **可区分性**：与频谱瀑布 / 液态涟漪 / 液态网格并排，是否一眼可分（§1.4）？
18. **无摩尔纹/闪烁**：全频段扫一遍，画面**任何区域**都不得出现摩尔纹、闪烁或色带断裂。
19. ⭐ **焦散网不得读成划痕**：海面上的粼光是否读作**连通的胞格网**（格壁围出多边形、水从格中透过）？⛔ 读成「横贯全幅的平行横线」或「一堆孤立短划」即失败——那正是孤立无胞形短划的典型症状；也 ⛔ 不得读成「方格纸」（去规整化幅度太小）。

---

## 七、单测门禁清单（每类含负向自证）

> 实施时新增 `app/src/test/java/com/nasmusic/tv/visualizer/renderers/SeasideTest.kt`，遵循项目三段式（数值段 / 行为段调用 `internal companion object` 纯函数 / 源码段去注释扫描）。⛔ **绝不**在 JVM 测试里 `new` 渲染器（字段初始化会建 `Path`/`Paint`/`Bitmap`，`OrbitalStarFieldTest.kt:26-28` 明令禁止）。

> ⭐⭐ **本方案的量化门禁来自一组已归档的验证脚本**（2026-10-03 归档到 `docs/archive/verification/scripts/`，⛔ 不再放 `logs_temp/`）：
>
> | 脚本 | 作用 | 产出 |
> |---|---|---|
> | `seaside_wave_harness.js` | ⭐ **帧驱动 harness**（Node + `vm`，桩掉 DOM/canvas/`Path2D`/`ImageData`，桩 `requestAnimationFrame` 驱动**真实页面代码**，在 `frame()` 尾部与 `drawSwellBands` 循环体内**注入探针**读浪队列闭包状态）。⚠️ 共 **5 个探针**：① 队列状态（`swashStageNow`/`swashPos`/`shoreYs`/`waves`/`retreatT`）② 逐浪绘制参数（`amp`/`fade`/`kA`/`lane`/`wiS`，⭐ **挂在 cull 之前**）③ 逐列几何 `bfy[]`（⭐ **挂在逐列循环之后**，否则读到上一帧领头浪的残留值）④ `kA` 计算之后的 cull 判据 ⑤ `spawnWave` 内的 `T`/`front` 输入 | §10 V17 全部数值、§4.3.1/§4.3.2.3/§4.3.9 的全部实测数字、G9 的负向自证 |
> | `seaside_hole_continuity_check.js` | 抽取 HTML 里**真实的** `hash32`/`hash2`，6 周期 @60fps，量「可见洞（`ry ≥ 1.5`）」逐帧中心/半径位移 | `6.2px → 0.74px`（**8.4×**），G9 的阈值依据 |
> | `seaside_caustic_geom_check.py` | 焦散胞壁网的几何断言（单调性、出界边、边数、warp 幅度） | G8 的焦散断言 |
> | `seaside_visual_driver.mjs` | 真浏览器 CDP 驱动（子命令 `snap` / `series` / `waterline` / `cost`，rAF 打桩 + 确定性推进） | 截图与逐帧序列 |
> | `html_syntax_check.js` | HTML 内联 JS 的语法检查 | 改原型后必跑 |
>
> ⛔ **harness 的两个坑**（移植时必读）：① 页面把**所有渲染**都 gate 在「点击开始」的 `started` 标志后面 ⇒ 必须先调 `begin()`，否则一帧都不画、读到的是空状态；② **探针必须挂在目标变量赋值之后**——挂错作用域会读到上一帧的残留值，量出「间距恒等于 0」这类假结论（真实发生过）。
> ⚠️ **`seaside_wave_harness.js` 的第 5b / 5c 两段判定已失效**（它们仍在检查绘制次序下标 `wi`，而 `wi` 已被 `wiS` 取代）：5b 现在测到的是音频自身的漂移而非泄漏；5c 的 `0.02` 阈值与实测 `0.0220` 同量级，会误报「带宽仍在跳」。⇒ **要么改成检查 `wiS`/`lane`，要么在引用本方案时标注失效**（§10 V17 只引用 1/2/6/8/9 段）。
>
> ⭐ **Kotlin 侧照此写纯 JUnit 即可，⛔ 不需要任何图形设备**：harness 测的全是纯函数语义（位置映射、噪声连续性、cull 阈值、每帧位移上限、状态机不变式）。做法：把场计算抽成返回图元列表的纯函数，放在 `internal companion object` 里直调（§七 G9 的 `holeField` 就是模板）。**门禁的判定口径必须与 harness 一致**，否则移植出的阈值没有可比性。

### G1 硬计数同步（失败即第一时间暴露）

- `VisualizerThemeTest.kt` 7 处：`29→30`（6 处）、`28→29`（1 处）。
- `FxCoverageScanTest.kt` 类数下限 `29 → 30`。
- 负向自证：改回 29 必须判失败。

### G2 纯函数契约（数值段）—— 逐条对应 §4.3 / §5 的原型行为

- **确定性 hash**：`hash32(x)` / `hash2(i, salt)` 对同一入参**多次调用结果相同**、恒在 `[0,1)`；⛔ 非确定性即失败。
- **fBm 归一化**：`fbmNorm` 输出恒在 `[0,1]`（⛔ 未 clamp 或用 `0.5 + 0.62·fbm1` 的低增益写法必须失败——那会把所有包络压在 `0.26..0.74`，既到不了 0 也到不了 1）；`fbmSigned` 输出恒在 `[-1,1]`。抽样验证 p10..p90 **覆盖 0..1**。
- **浪脊剖面**：`crestProfile(u, seed)` 对 `u ∈ [0,10]` 有界；相邻 `u` 的输出**连续**（⛔ 不出现与 `u` 无关的跳变）；`env` 下限为 `0.12`（断言存在 `env < 0.20` 的 `u` ⇒ ⛔ 下限写回 `0.30` 必须失败）。
- **有界性**：`swashFrontY(x, t, h)` 遍历 `x ∈ [0,w]` 结果始终**有界**、不越出 `[−h, 2h]`；且**不依赖 dt**（纯 `t` 的函数）。⛔ 断言里**不再有** `waveFrontY`（已删，§5.5）。
- ⭐ **`CREST_LANE_SHIFT` 相干性**：破碎线前缘在 `lane = 1` 与 `lane = 2` 两条不同的浪上、在任何 `x` 处**不相交**（共用同一条剖面、只在 `u` 上错开 ⇒ 构造上不可能交叉）。负向：给每条浪一个独立 `seed` ⇒ 断言失败（独立 seed 会让相邻带交叉出透镜状伪影）。
- ⭐ **不循环观感**：`fbm1` 的八度比 `2.07` 与衰减 `0.60` 是硬编码值；断言不同八度的频率**互不成整数比**。
- ⭐⭐ **破碎线前缘映射**（数值段，本方案核心）：构造 `h = 900`，断言
  1. `bfy[i](adv = 0) ≈ spawnFar = −0.04h`（**在画外**，即后浪从顶边上方滑入）；
  2. `bfy[i](adv = 1) == shoreYs[i]` **逐列严格相等**（⭐ 这就是交接无缝的**根据**）；
  3. `tAdv` 在 `[0,1]` 上**单调递增**、终点 `= 1`，且 `adv ≤ MORPH_START` 时 `tAdv == adv`（⛔ 若退化成 `lerp(spawnFar+crest, shoreYs, morph)`，前 55% 行程 `tAdv ≡ 0` ⇒ 该断言失败——这正是 owner 报的「后浪浮现后等待在原位置，过一会突然快速冲出去」）；
  4. `crest` 与 fray 项都**含 `(1 − morph)` 因子**（源码段或数值段均可）：把它们改成不衰减 ⇒ 交接处 `|Δ bfy| > 3px` 的断言失败。
- ⭐⭐ **绘制上的「前浪」判定**：断言 `isLead == (state == W_REACHED || state == W_FADING)`，⛔ **不是** `w === leadWave()`。负向：换回 `w === lead` 并构造「滩上无浪 + 一条 `adv = 0.5` 的推进中浪」⇒ 交接跳变断言失败（原型实测中位 **187px** / 最大 **387px**）。
- ⭐ **泡沫双峰**：`foamTargetAt(n)` 在前缘段（`n ≈ 0.1`）**高于**浪心段（`n ≈ 0.45`）；`foamCoreAt(n)` 在两端（`HEART_IN` / `HEART_OUT`）为 `0`、中段为正 ⇒ 帐篷形；`foamCoreAt` 峰值 **≤ `FOAM_CORE_A`**；`foamTailAt(TAIL_OUT) = 0`；`foamTargetAt` 在 `n ∈ [0, TAIL_OUT]` 上有界且非负。⛔ 若实现退化为单调递减，前缘/浪心那条断言必须失败。
- ⭐ **泡沫律方向**（§14.3.8，**单一方向无歧义**）：
  - `layerAlpha(adv, energy)` 在足够大的 `energy` 下对 `adv` **单调递增**（越靠岸越强）；且 `layerAlpha(0, ·) < layerAlpha(1, ·)`；断言 `layerAlpha(0, 1) ≥ 0.32`（近shore项下限）。
  - `farLaw` 在水线处 `= 1`，向外海**单调递减**，`FOAM_FAR_SPAN` 外为 `0`；`FOAM_FAR_POW = 1.6` 时最外那条 `< 0.1`。⭐ **非领头浪用 `0.35` 幂**：断言它在破碎线出生处**不趋 0**（否则起步段 `kA` 被 cull，§4.3.5）。
  - 领头浪的 `slopeLaw == 1 && farLaw == 1`（源码段或数值段断言）。
  - `slopeLaw` 在 `dy/dx = 0`（平缓浪面）时 `= 0`，在 `|dy/dx| ≥ 1/2.48`（每像素）时 `= 1`（钳位）。
  - 负向：把 `layerAlpha` 写成随 `adv` **递减**（初版缺陷）、或把 `farLaw` 改成随离岸**递增**，都必须失败。
- ⭐ **距离律绝不折进带宽**：断言带宽表达式里**只出现** `slopeLaw`、⛔ 不出现 `farLaw`。负向：把 `0.30 + 0.70·slopeLaw` 改成 `0.30 + 0.70·foamK`（含 `farLaw`）必须失败。
- ⭐⭐ **带宽不归零**（数值段 + 负向）：遍历整条非领头浪的一生，断言 `shrink = max(0.30, frayK·envK·slK) ≥ 0.30`，且 `bwj[i] > 0` 恒成立；⭐ 再断言**塌陷率**（`bwj` 低于无下限时的实测最低值 0.005 的帧占比）为 **0%**。
  - 负向：去掉 `max(0.30, …)` ⇒ 断言失败（原型实测塌陷率在 `adv 80~90%` 曾达 **48.5%**，平均 `bwj` 从 0.251 缩到 0.113）。⭐ 特别断言 `envK = 0.02 + 0.98·env` 是**乘积里的一项**、⛔ 不能被单独钳位——最初就是只钳了 `frayK` 而漏了它。
- ⭐⭐ **外观量不依赖绘制次序**（结构性断言）：断言身份索引只由 `serial` 决定：`wiS == serial & 3`、`lane == 1 + serial % 3`。⭐ 再断言：`lane` 在一条浪的**整个生命周期内恒定**（harness 实测跳变 **0**；⛔ 旧写法 `isLead ? 0 : (wi & 1) + 1` 会让它一生跳 **2** 次），且 `lane != 0`。
  - 负向：把 `wiS` 改回绘制次序下标 ⇒ 断言失败（原型实测 **14/15** 条浪的 `wi` 会变；`env` 的 hash 种子 `8100 + wi·53` 随之换掉 ⇒ **带宽花纹整片换掉**）。
- ⭐⭐ **非领头浪的 `kA` 不吃音频**（数值段）：跑两遍绘制参数计算，一遍喂全零音频、一遍喂满音频，断言**非领头浪的 `kA` 逐帧完全相同**；同时断言领头浪的 `kA` **确实变了**（否则这条断言会因「什么都没接音频」而假通过）。
  - 负向：把 `layerAlpha` 重新乘进非领头浪的 `kA` ⇒ 断言失败（原型实测静音下 `nearness 0.32 × appear 0.30 × amp 0.698 ⇒ kA ≈ 0.067`，**82%** 调用被 `kA < 0.12` 丢弃）。
- ⭐ **水线交接连续性**（数值段模拟）：构造「前浪抵滩 ⇒ 并行起退水」与「后浪进上涌区」的时序，断言
  1. 交接那一帧 `waterlineAdvance` 的返回值 **等于** `nextPush`（按构造连续，无跳变）；
  2. 退水期间返回值在 `[0,1]` 内**单调递减**；
  3. ⭐ **交接比较在同一空间**：断言代码里比较的两个量**都是冲流 `adv`**（`0..1`）。⛔ 负向：换回 `front.y >= a`（破碎线 `adv` 比冲流 `adv`）⇒ 断言失败——原型实测交接发生在 `adv 0.41~0.77`、整条带瞬移 **150~387px**。
  4. ⭐ **必须排除滩上 owner**：`incoming = if (owner && !ownerBeach) owner else null`。⛔ 负向：直接用 `owner` ⇒ 第一帧 `push = 1 >= a = 1` ⇒ **退水在第一帧就被取消**（原型症状：退水只剩 1 次）。
  5. ⭐⭐ **不能写成 `front !== owner`**：⛔ 负向 —— 断言「后浪进上涌区后它自己成为 owner 时，交接**仍能**触发」。写错时交接永不触发 ⇒ `retreatT` 永不重置为 `−1` ⇒ 之后所有起退水守卫全被挡住 ⇒ **90 秒只剩 1 次退水**。
- ⭐ **退水双触发点**（数值段）：
  1. 构造「前浪还在滩上 ⇒ 第二条浪抵滩」：断言该条转为 `FADING` 时 `retreatT` **未被重置**（触发点①被 `stillBeach` 守卫跳过）；
  2. 再让滩上最后一条淡出完毕：断言此时 `retreatT` 被置 `0`（⭐ **兜底触发点② 不可省**，否则整轮退水被吞）；
  3. ⭐ **守卫必须含 `retreatT < 0`**：置 `retreatT` 为退水中的值并让一条浪抵滩，断言 `retreatT` **保持不变**。⛔ 负向：去掉 `retreatT < 0` ⇒ 水线一帧从 `meanAdv 0.0685` 跳回 `0.9611` ⇒ **78.79px 单帧跳变**，断言失败。
  4. `retreatDur == max(SWASH_RETREAT_MS, min(SWASH_RETREAT_MAX, eta))`，且 `eta == max(0, (SWASH_LEAD_ADV − front.y) / front.v)`（`v` 是 `adv/ms` ⇒ 直接是 ms）。⛔ 负向：换回固定 `SWASH_RETREAT_MS` ⇒ 退水时长方差断言失败（原型实测中位 1567ms vs 固定 440ms）。
- ⭐ **不可能 overtake**（结构性断言，⚠️ **根据已换**）：断言**两档 `T` 区间不重叠**——`WAVE_T_MIN × FOLLOW_T_SLOW = 8400 > WAVE_T_MAX = 5800` ⇒ 任意两条浪的**到达顺序恒等于出浪顺序**、到达间隔恒 `≥ 2600ms`。
  ⛔ **不要再断言旧根据** `WAVE_T_MAX − WAVE_T_MIN < WAVE_GAP_MS`（`1600 < 2360` ⇒ 间隔 760ms）——它在 `FOLLOW_T_SLOW` 引入后已不是最强的保证，但作为**下界仍然成立**，可作为附加断言保留；⚠️ 主断言必须是「区间不重叠」。
- ⭐⭐ **后浪自由推进**（数值段，取代旧的「间距不变式」）：置一条后浪在任意 `adv`（含 `0.9`），跑一帧，断言它的 `y` **严格增大**。
  - ⛔ **负向 1**：加回位置钳位 `w.y = min(w.y + v·dms, o.y − WAVE_SPACING_Y)` ⇒ 断言失败（原型实测后浪 `adv` **从未超过 0.6**、永远到不了滩）。
  - ⛔ **负向 2**：加退水冻结闸 `if (retreatT >= 0 && retreatT < retreatDur) continue` ⇒ 断言失败（owner：「中间不停顿」）。
- ⭐ **音频不进时间轴**（数值段）：从同一个初态跑两遍队列推进，一遍喂真实音频值、一遍喂全零，断言 `stepWaves` / `finishWaves` / `waterlineAdvance` 的返回值与队列状态**逐字段相同**（原型 `resetWaves` 就是为这个 harness 准备的）。
- ⭐ **湿痕只在到滩写入**：断言 `commitWetMarks` 对 `ADVANCING` 状态的浪**不写**任何 `wetMark`/`wetAmt`；`FADING && hit` 的浪逐列取 `max`（历史最高水位只增不减）。负向：去掉 `hit` 判据 → 断言失败。
- **干燥判据**：断言 `wetMark[i] > y + 0.5` 才乘衰减（⛔ 用「全体有没有浪在推进」当判据 → 断言失败，那会让湿沙永远降不下来）。
- **`dryTau`**：断言 `dryTau == clamp(WAVE_GAP_MS·SWASH_DRY_FRAC, SWASH_DRY_MIN, SWASH_DRY_MAX)`（当前 `2360·0.60 = 1416ms`，落在钳位内 ⇒ 等于 `1416`）；且它**不是常量**而是每次由这三个量算出。
- ⭐ **`SAND_TEX_TOP` 高于水线最大摆动**（数值段 + 常量断言）：断言 `SAND_TEX_TOP < SHORE_K − CREST_AMP_SHORE − 0.006`（当前 `0.46 < 0.484`）。⛔ 负向：写回 `0.62` ⇒ 断言失败。⭐ 这是**唯一**能同时挡住「沙与海水底色的交界」与「退水退到快中间就不退了」两条症状的断言（两者同根因）。
- ⭐ **飞沫/残沫确定性**：`buildSplashTable()` / `buildResidueTable()` 两次调用结果**逐字段相同**（⛔ 非确定性即失败）；残沫任意两点最小间距 ≥ `0.092`（保证「点稀疏、不连成纹」）；残沫点数 `RESIDUE_MAX` **远小于** `SAND_LINES`。
- **`buildLadder`**：断言 `FOAM_EDGES` **严格递增**（`edges[i+1] - edges[i] > 0` ⇒ 窄带互不重叠）；`foamTargetAt(mid) < 0.006` 的窄带被丢弃；精阶梯 14 段、粗阶梯 7 段。
- **死常量不得复活**：断言源码里**不存在** `SWELL_LAYERS` / `LAYER_PHASE_GAP` / `SWELL_PERIOD_MS` / `SWELL_TRAVEL` / `swellPhase` / `swashCurve` / 三正弦参数（`WAVE_K` / `WAVE_W` / `WAVE_A` / `WAVE_SGN` / `WAVE_PHI` / `WAVE_K4` / `WAVE_K5`）/ `WAVE_SPAWN_K` / `LIP_A` / `CAUSTIC_SHORE_A` / `SWELL_BODY_FAR_POW` / `WAVE_SPACING_Y` / `MIN_ARRIVE_GAP_MS` / `CONVERGE_K` / `crestK` / `waveFrontY` / `waveDepthK` / `WAVE_REACH_Y`（§5.5 清单）。⭐ 顺带断言**不出现** `reachK`（无读取方的数组）与 `drawSwellBands` 内的 `foamK` 局部量。

### G3 帧率无关 + 时基一致性（数值段）

- 时间原点惰性捕获：验证**不依赖** `ctx.nowMs`（源码段）。
- 「dt=1/30 × 30 帧」与「dt=1/60 × 60 帧」到同一时刻，**队列状态与水线推进量**同值（`spawnAcc` / `y` / `fadeT` / `retreatT` / `wetAmt` 全部逐字段比对）。
- 负向：把某量改成「每帧 + 固定步进」或改用帧计数（`fx.seq`）应判失败。
- 暂停 ⇒ `dt = 0` ⇒ 队列、退水、干燥**全部冻结**（断言两次采样状态完全相同）。

### G4 `postFx` 覆盖 + 字面量（源码段）

- `FxCoverageScanTest` 通过：本类入 `covered` 名单，且 `override val postFx = PostFx(…, …)` 含 `>0f` **数字字面量**（⛔ 无类型标注、无 `get()`、⛔ 非命名常量，§3.5）。
- 负向：写成 `get()` 形式或命名常量 → 源扫判失败。

### G5 三条模板方法不覆盖 + rng 复用（源码段，`RendererBaseContractTest` 判定）

- 无 `override fun DrawScope.draw(` / `onEnter(` / `onExit(`。
- 无 `ctx.nowMs`。
- 无 `private val rng = VisualizerRandom()`（用继承 `rng`）。

### G6 零分配 + 无禁物（源码段）

- `PerfBudgetContractTest`：draw 可达行内**无字符串模板**、**无带参 `Rect(`**、**无 `listOf/mutableListOf/mapOf/.map{`/`sortedBy`**。
- 自加禁物扫描：⛔ 无 `RuntimeShader`/`RenderEffect`/`GLSurfaceView`/`BitmapShader`/`BlendMode.Difference`/`RoundedCornerShape`；⛔ 无新增 `ProceduralTexture.Id`（比对 `Id.entries` 前后一致）。
- ⭐ **逐列数组不得每帧新建**：断言 `FloatArray(...)` 只出现在尺寸守卫的 `rebuildGeometry` / 初始化路径，⛔ 不出现在 `drawContent` 可达行。
- ⭐ **海浪动态性门禁**（本方案特有，两条）：
  1. `drawContent` 可达路径中存在**随 `nowMs` 演化的调用**（`swashFrontY(…, nowMs)` / `crestProfile(u, …)` / `fillFray(t, lane)`），⛔ 不存在把时间量化成帧计数（如 `fx.seq`）的写法——后者会让浪的推进速度随帧率变化。负向：把时间参数换成 `fx.seq` → 源扫应失败。
  2. **时间轴函数零音频**：`stepWaves` / `finishWaves` / `waterlineAdvance` / `waveFade` / `commitWetMarks` 的函数体内**不得出现任何音频字段**（`frame.spectrum` / `frame.bass*` / `frame.beat` / `frame.pulse` / `envelope` 缓冲）。负向：在 `stepWaves` 里读 `envelope[LOW_BAR]` 加一个推进倍率 → 源扫应失败。
  3. ⭐ **无位置钳位**（源码段）：扫描 `stepWaves`，⛔ 不得出现对 `w.y` 的 `min(…, o.y − …)` 形式的写入、⛔ 不得出现任何「前浪行程作为上限」的表达式。负向：加回 `WAVE_SPACING_Y` 钳位 → 源扫判失败。
  4. ⭐ **起退水守卫含 `retreatT < 0`**（源码段）：两处 `retreatT = 0`（`REACHED→FADING` 与 `FADING` 结束）都必须在 `if` 条件里出现 `retreatT < 0`。负向：去掉它 → 源扫判失败（78.79px 单帧跳变的根因）。

### G7 资源回收（源码段）

- 本类持有位图（沙纹理 / 泡沫贴图 / 湿沙与高光小条）⇒ `onExitContent` 内 `recycle()`（try/catch）。
- ⛔ 不出现 `ProceduralTexture.release()` / `OverlayFx.release()`（归舞台/基类）。
- ⛔ 不出现 `ProceduralTexture.ensure(` / `ensureFullscreenOnly(`（本效果一张程序纹理都不取，§3.2/§3.3）——这条也要源码扫描锁死。

### G8 烘焙正确（源码段）

- `buildSandTexture` / `buildFoamTiles` / `buildLaceNet` / `buildCausticNet` / `buildGrain` 的调用**只在**尺寸守卫的 `rebuildGeometry` 内，⛔ 不在 `drawContent` 每帧路径。
- 确定性抖动：⛔ 无 `Math.random()` / `Random()`；抖动仅用 `hash32` / `hash2` / 有种子的 LCG。
- ⛔ 泡沫贴图的边缘渐隐外径 **<** 贴图边长的一半（早先 `0.52·TS` ⇒ 边界残留 alpha ⇒ 渲染出可见的淡方框）。
- ⛔ 沙纹理 blit 的源矩形是 `(0, 0, texW, texH)`，⛔ 不把 `sandTexTop` 当源 `y` 偏移（否则整块沙变灰暗条纹）。
- ⭐ **焦散胞壁网拓扑**（`buildCausticNet`）：两次调用结果**逐字段相同**（确定性 hash）；断言**出界边不存在**（末列无右壁、末行无下壁 ⇒ ⛔ 无周期环绕）；断言每行节点横坐标、每列节点纵坐标**单调**（warp/shear 幅度 ≤ 0.3 格 ⇒ 相邻胞格不自交）；断言边数 = `(CNX−1)·CNY + CNX·(CNY−1)`。
- `Path`/`FloatArray` 全部为成员变量并复用，⛔ 每帧 `new`。

- ⭐ **破洞连续性**：`docs/archive/verification/scripts/seaside_hole_continuity_check.js`（抽取 HTML 里**真实的** `hash32`/`hash2`，6 个周期 @60fps）。见 V16 的判据与数值。
- ⭐ **焦散胞壁网几何**：`docs/archive/verification/scripts/seaside_caustic_geom_check.py`。
- ⭐⭐ **逐帧连续性（`punchHoles` 的）**：见 G9。

### G9 ⭐ 逐帧程序化场的连续性（数值段 + 负向自证）

> 通用命题（§4.3.5 / §4.3.6 / §14.3.4）：**任何 punch 进 evenodd 路径的逐帧场，相邻两帧之间每个「两帧都可见」的图元，其中心或半径的位移都必须远小于它自己的半径**。这条就是 owner 报障「前浪的白色浪花一直存在，现在老是闪烁」的守门断言；⛔ 光靠 V12/V16 的目视抓不住它（60fps 下人眼分辨不出单帧 0.74px 的位移，但**能**看出 6.2px 的整帧闪）。

- **抽出纯函数**：Kotlin 侧把洞场抽成 `holeField(layer, strip, n0, n1, t): List<Hole>`，每洞只要 `(cx, cy, rx, ry)`（⛔ 别在测试里 `new` 渲染器，`Path` 建不出来）。⭐ **可见性阈值必须与实现同源**：`ry >= 1.5f && rx >= 2.5f`——就是 `punchHoles` 里那条丢弃子句的同一对数（⛔ 门禁自己另定一个阈值 ⇒ 实现改了阈值而门禁不知情）。
- **时间扫描**：`t` 从 `0f` 步进到 `6 × HOLE_PERIOD_MS`（`1100ms` ⇒ 6600ms，覆盖 6 个周期 ⛔ 不足 2 个周期会漏掉 wrap 帧），步长 `1/60 s`（⛔ 不要用 `1/30`：跳变会被稀释掉），洞按 `h` 序号跨帧配对。
- ⭐ **主断言（可见性加权）**：
  1. 对每对相邻帧、每个**两帧都可见**的洞，断言 `max(|Δcx|, |Δcy|, |Δrx|, |Δry|) < 0.75f × 该洞自身 ry`。
  2. 断言全扫描的**最大比值 `< 0.75f`**；⭐ 原型实测值 **0.31**（0.74px / 半径）⇒ `0.75` 是自定的门禁余量，留了一倍以上空间。⛔ 超过 `0.75` 即读作瞬移，判失败。
  3. ⛔ **不要断言未加权的原始最大值**：原型上是 **9.0px**，全部来自 `u` 回绕那一帧、而那帧半径同时已在阈值之下（不可见）。⛔ 拿它当门禁就是假阳性，会反过来逼实现去做「连不可见的洞也不许跳」这种无谓优化。
- ⭐ **生灭必须发生在不可见处**（否则成对比较会被绕过）：对每个 `h`，若它在帧 `k` 可见而帧 `k+1` 不可见（反之亦然），断言它在**可见的那一帧** `ry < 2 × 1.5f = 3.0f`——即它是「缩回阈值以下」才不见的，⛔ 而不是满尺寸时凭空出现/消失。⛔ 漏掉这条 ⇒ 「洞被删掉/被凭空加出来」这条路径完全绕过主断言，门禁形同虚设。
- **洞数与相位**：断言 `count` 在整个扫描中**恒定**（`count = round(HOLE_MAX · kA · clamp(1.15 − gap·1.6, 0, 1))`，`HOLE_MAX = 18`）⇒ ⛔ 若有人改成「每周期重抽 `count`」，洞会凭空增减，本条判失败。
- ⛔ **负向自证（三条都必须真的判失败）**：
  1. 把洞场换回旧实现（`tb = floor(t / HOLE_PERIOD_MS)`，`hn` / `hr` 由 `hash2(h, 710 + tb + …)` / `hash2(h, 720 + tb + …)` 决定）⇒ 断言必须失败：实测最坏跳变 **6.2px ＝ 2.6× 自身半径**，远越 `0.75`。
  2. 把 `hx` 换回 `(ph + 0.13·sin(..) + 1) % 1` ⇒ 断言必须失败：实测 **1599px/帧**（≈整屏宽，比它要修的 bug 差 ~270×）。
  3. 去掉 `grow = sin(π·u)`（半径改成随 `u` 线性，或换成 `0.5 + 0.5·sin` 这种**两端不归零**的包络）⇒ 断言必须失败：半径在 `u` 两端不收缩 ⇒ 洞在**可见状态下**瞬移，且上面「生灭必须发生在不可见处」那条也会同时判失败。
- ⭐ **源码段补充**：扫描 `punchHoles` 函数体，⛔ 不得出现 `floor`（任何形式）；⛔ 不得出现 `% 1` 形式作用于**横向位置**的表达式（`u` 里的 `% 1` 是允许的、且必需——那里半径已收缩到阈值下）。负向：把 `hx` 改成 `% 1` 形式 ⇒ 源扫判失败（即便数值段被绕过）。
- ⭐⭐ **本门禁的三条适用范围要分清**（避免把 G9 错扩成「所有场都要连续」）：

  | 场 | 是否受 evenodd 放大 | 门禁归属 |
  |---|---|---|
  | `punchHoles` 破洞场 | ✅ **是**（punch 进同一条 `Path2D`、与条带轮廓**一次** `fill(p, 'evenodd')`） | **G9 主断言** |
  | `drawFoamLace` 蕾丝网 | ❌ 否（端点钉死在烘好的共享节点上，拓扑本身不闪） | 仅需断言「端点共享、每次采样自同一张网」 |
  | 逐像素采样的场（沙颗粒、水体场噪声） | ❌ 否 | 无需连续性门禁 |

  ⛔ 同一个随机场若只是**逐像素采样**（每帧独立取值），闪烁会轻得多——没有「拓扑」把整帧的取值差异放大成图案级突变。**evenodd 挖孔把场的变化直接变成白沫路径的拓扑变化** ⇒ 同一个场在这里是硬视觉伪影。⭐ 凡 punch 进 evenodd 路径的场（洞，以及将来任何挖孔/擦除）都必须逐帧连续。

### G10 ⭐ 两个位置量的结构不变式（数值段 + 负向自证）

> 本门禁对应 §4.3.2 的架构决策。它与 G9 互补：G9 管「场逐帧连续」，G10 管「两个位置量各自独立、且交接处严格相等」。

- ⭐ **破碎线与冲流线必须是两条独立的映射**：断言存在两个可分别求值的量——`breakerY(adv, i)`（终点 `shoreYs[i]`，起点 `spawnFar`）与 `swashFrontY(x, t, h)`（起点 `h·SHORE_K + …`），且 `breakerY(1, i) == shoreYs[i]` 严格成立。
- ⭐⭐ **交接点在 `adv == 1.000` 发生**：跑一整轮交接，断言 `|Δ bfy|` 的最大值出现在 `adv == 1.000` 的那一帧附近（原型实测：**中位 1.1px / 最大 3.0px，全部发生在 `adv 1.000`**）。
  - ⛔ 负向：换回 `leadWave()` 判定 ⇒ 最大跳变出现在 `adv 0.41~0.77`（交接提前）且幅度升到 **中位 187px / 最大 387px** ⇒ 断言失败。
- ⭐ **`spawnFar` 必须在画外**（`spawnFar < 0`）：断言 `SHORE_K − WAVE_SPAWN_DEPTH < 0`（当前 `0.620 − 0.66 = −0.04`）。⛔ 负向：写回 `0.42`（⇒ `spawnFar = 0.20h`）⇒ 断言失败——owner 报「后浪直接从浮现在上面 1/6 左右的位置，而不是从边缘进入」。
- ⭐ **两个空间的量不得互相比较**（源码段）：扫描交接判据，⛔ 不得出现把「破碎线 `adv`」与「冲流 `adv`」直接比较的表达式（形如 `front.y >= a`）。负向：加回 `front.y >= a` ⇒ 源扫判失败。
- ⭐ **`shoreYs` 只由冲流线定义**：断言 `shoreYs[i] == swashFrontY(x, t, h) + fray[i]·W0·FRAY_FRONT·0.42 + h·swashReachNow·adv`，⛔ **不含任何破碎线插值项**。⭐ 同时断言破碎线的 `crest`/`fray` 项**都乘 `(1 − morph)`**——因为 `shoreYs` 内已含前浪自己的曲线与 fray（**同系数 `0.42`**），不吸收就是重复计入。
  - ⛔ 负向：把 `(1 − morph)` 去掉 ⇒ 交接帧 `|Δ bfy| > 3px` 的断言失败。

---

## 八、逐文件改造清单

| # | 文件 | 改造点 | 备注 |
|---|---|---|---|
| F1 | `data/model/AppSettings.kt` | 枚举追加 `SEASIDE("海边", Tier.BASIC, "43")`；KDoc 含视觉描述 + `Tier.BASIC` 理由 | KDoc ⛔ 不得出现 `/*` 字面量（`RendererBaseContractTest:358-369`） |
| F2 | 同文件 `:100` 附近 KDoc | 「29 套」→「30 套」 | 陈旧注释顺手修 |
| F3 | `visualizer/VisualizerRendererFactory.kt` | import + `SEASIDE -> SeasideRenderer()` | 单行 |
| F4 | **新增** `visualizer/renderers/SeasideRenderer.kt` | `class SeasideRenderer : RendererFx()`，全文见 §14.3 | 核心 |
| F5 | **新增** `test/.../renderers/SeasideTest.kt` | G1–G10 | 三段式（数值 / 源码 / 逐帧连续性） |
| F6 | `test/.../data/model/VisualizerThemeTest.kt` | 7 处计数 +1 | 漏一处必挂 |
| F7 | `test/.../visualizer/fx/FxCoverageScanTest.kt` | `covered` 加类名 + 类数下限 +1 | |
| F8 | `docs/visualizer-effects-list.md` | 计数 +1、BASIC 表加行 | |
| F9 | `docs/technical-overview.md` | 新增 `### 10.208` | 只记**已核实**的变更 |
| F10 | `CHANGELOG.md` | 当前版本 `### Added` 加一行（⛔ 只写做了什么，无根因/行号/验证叙述） | |

---

## 九、风险、降级与红线

| # | 风险 | 等级 | 缓解/兜底 |
|---|---|---|---|
| R1 | ⛔ **动态性被真机判失败**：若实现偏向静态纹理平移或固定泳道，海浪看起来是「贴图在动」而非「一条条浪在走完一生」 | **高** | §4.3 的**离散浪队列 + 自走时钟**（出浪/推进/到滩/消散/退水交接全吃 `dt`）；门禁 G6 有「时间量化」与「时间轴零音频」两条源码门 |
| R2 | **逐列采样的锯齿/摩尔纹**：`COLS` 太少则前缘呈折线，太多则开销上升 | 中 | `COLS = 96` 起点（弧长用 `smoothRun` 的过中点二次曲线，天然圆滑）；真机看前缘；必要时提高到 128/降 64 |
| R3 | **沙纹摩尔纹**：横向细线在全屏易与像素栅格干涉闪烁 | 中 | alpha ≤`SAND_LINE_A`（实际 `0.029/0.044/0.058`）、条数固定 26、整组缓慢漂移**不循环**、三档都是浅色调；V12 专项验收 |
| R4 | **首帧黑屏**：`buildSandTexture`（4 层逐像素）+ 6 张 256² 泡沫贴图 + 蕾丝拓扑 + 颗粒图案都在**首帧**同步烘 | 中 | 沙纹理有 320 万像素降采样闸门；V5 真机实测首帧时长，超标则先砍泡沫贴图张数 / 推迟蕾丝烘焙 |
| R5 | **`t0Ms` 冻结**：误用 `ctx.nowMs` ⇒ 浪永久静止（E42 的真 bug） | 中 | §3.1 硬规则 + 门禁 G5 |
| R6 | **`postFx` 静默失败**：类型标注导致门禁不匹配却**不报错** | 中 | §3.5 + 门禁 G4 |
| R7 | **与既有水/流体效果撞脸** | 低 | §1.4 差异化 + 自检第 17 条 |
| R8 | **参数未经真机校准**：§五 全表为初值 | 中 | 每条参数在 V1–V16 中逐项对照；调参不动结构 |

⛔ **硬红线**（触犯即推倒重来）：shader/AGSL/RenderEffect/OpenGL；覆盖 `draw/onEnter/onExit`；`ctx.nowMs`；自建 `rng`；`ProceduralTexture.release()`；圆角 clip；`ProceduralTexture.ensure()`；`postFx` 带类型标注或 getter；把时间量化成帧计数；⛔ **任何音频量进入时间轴**（出浪间隔/行程/消散/退水/干燥）；⛔ 复活 `SWELL_LAYERS`/相位三角/固定泳道/三正弦水位场；⛔ **退水期间冻结后浪**（`retreatT` 闸 ⇒ 后浪停顿，违背「中间不停顿」）；⛔ **补浪改回纯 `WAVE_GAP_MS` 定时器**或**在 `finishWaves` 里补浪**（⇒ 读作「海上只有 1 条浪」/ 多出一条浪）；⛔ 焦散退回「孤立短横划」或给胞壁网加**周期环绕**（⇒ 又读成划痕）；⛔ **逐帧程序化场用量化时间（`floor(t / period)`）换随机种子**（⇒ 破洞场在周期边界上所有洞同帧整体瞬移，经 evenodd 单次 fill 放大成白沫整帧闪烁；原型实测跳变 **6.2px ＝ 自身半径 2.6×**，改连续相位后 **0.74px ＝ 0.31×**，G9）；⛔ **在逐帧场里用取模回绕实现漂移/循环**（`(x + 1) % 1` 会从 ~1.0 跳回 ~0.0 ⇒ 图元横穿整屏；原型实测 **1599px/帧**。⭐ 通则：**逐帧程序化场里取模回绕 ＝ 不连续，用 `clamp` 或有界三角函数**）。

#### ⛔ 浪机制专项红线（9 条，逐条带实测数字）

> 这 9 条是本方案**最贵**的教训——每一条都对应一次「数值全对、画面全错」的失败。括号里是违反后的**实测症状**，不是理论推演。

| # | 红线 | 违反后的实测症状 |
|---|---|---|
| **1** | ⛔ **不得在浪之间做位置钳位**——后浪必须**全程自由推进**（`w.y += w.v·dms`，无任何上限）。owner：「中间不停顿」「应该一直推进到沙滩」 | 曾用 `WAVE_SPACING_Y = 0.55` 冻结后浪：它的 `adv` **从未超过 0.6**、永远到不了滩，**67% 的绘制调用因 `kA` 过低被 cull** ⇒ 读作「走到中间就消失」 |
| **2** | ⛔ **不得把后浪压进水线空间**——破碎线与冲流线必须各有独立的位置空间（§4.3.2） | 方案 B 把后浪行程压到 **81px** ⇒ 它整个藏进领头浪的带里（领头浪 `bwj` 中位 **1.17** + `SHORE_FOAM_BOOST` **1.55**）⇒ owner 报「**彻底看不到**」，尽管它的 `kA` 0.29~0.38、`bwj` 0.35~0.41 **全部正常**。⚠️ 「数值全对、画面全无」这类失败**不可能**靠调 `kA`/`bwj` 解决 |
| **3** | ⛔ **逐帧程序化场不得用量化时间换随机种子**（`floor(t / period)` 之类） | 破洞场因此在每个周期边界**整帧闪一次**：可见洞逐帧位移 **6.2px ＝ 自身半径的 2.6×**；且所有洞同帧变化 ＝ 一起眨眼。改每洞连续相位后 **0.74px ＝ 0.31×**，**8.4×** 改善（G9） |
| **4** | ⛔ **取模回绕 ＝ 不连续**。逐帧场里的漂移/循环必须用 `clamp` 或有界三角函数 | `(x + 1) % 1` 曾让洞每帧横穿整屏 **1599px**（≈屏宽）。⭐ 通则：**逐帧程序化场里取模回绕就是不连续** |
| **5** | ⛔ **浪带宽度不允许归零**：下限 `0.30` 必须加在**乘积** `frayK·envK·slK` 上，⛔ 不是加在单项上 | 无下限时三项衰减连乘可把 `bwj` 乘到 `0` ⇒ 那一帧整条带宽度为 0 ⇒ 读作「浪突然消失」。实测塌陷率在 `adv 80~90%` 曾达 **48.5%**，平均 `bwj` 从外海 0.251 缩到 0.113。⚠️ 最初只钳了最轻的 `frayK`，⛔ 漏掉的正是 `0.02 + 0.98·env` |
| **6** | ⛔ **任何外观量不得依赖绘制次序下标 `wi`**；用 `wiS = serial & 3` / `lane = 1 + serial % 3` 这类**按身份取的稳定索引** | `wi` 曾泄漏进 `bandAmp(1 + wi%2)`、`nb1/nb2` 的相位、以及 ⛔⛔ `env` 的 hash 种子 `8100 + wi·53`。**种子变 ⇒ 噪声实现整个换掉 ⇒ 带宽花纹整片换掉 ⇒ 画面跳变**（主因）。实测 **14/15** 条浪的 `wi` 会变 |
| **7** | ⛔ **`SAND_TEX_TOP` 必须高于水线的最大摆动**：`SAND_TEX_TOP < SHORE_K − CREST_AMP_SHORE − 潮汐 = 0.484h`（当前 `0.46`） | 早先取 `0.62 = SHORE_K` ⇒ 退水时 `0.484h…0.62h` 露出**海水底色**。owner 同时报了两条看似无关的故障——「沙与底色的交界」与「退水退到快中间就不退了」——⭐ **两条是同一根因**（视觉上「沙」从 `0.62h` 才开始，退到一半就像「退不动了」） |
| **8** | ⛔ **交接比较必须在同一参数化空间内**：破碎线 `adv` 与冲流 `adv` **不可比** | 拿 636px 尺度的破碎线 `adv` 比 81px 尺度的冲流 `adv` ⇒ 交接提前到 `adv 0.41~0.77`、整条带瞬移 **150~387px**（另一处实现下 150~267px）。⭐ 正确做法：两个量**都取冲流 `adv`**（`a` 与 `nextPush`） |
| **9** | ⛔ **起退水的守卫必须含 `retreatT < 0`**（两处触发点都要） | 退水进行中被重置 `retreatT = 0` ⇒ 水线从已退回的位置（实测 `meanAdv 0.0685`）被瞬间拽回满位（`0.9611`）⇒ **78.79px 单帧跳变**。⚠️ 拿掉守卫是「顺手加固」时最容易做的事 |

**附：三条「看起来是加固、实际是踩坑」的附带红线**（同源于交接/退水，一并记在这里）

- ⛔ 交接必须拿**非滩上** owner 的 `push` 比（排除 `ownerBeach`）——否则退水改为「抵滩即起」后，**退水在第一帧就被取消**。
- ⛔ 但⛔ **不得**写成 `front !== owner`——后浪进上涌区后自己成为 owner ⇒ 交接永不触发 ⇒ `retreatT` 永不重置 ⇒ 之后所有起退水守卫全被挡住 ⇒ **整轮退水被吞（实测 90 秒只剩 1 次）**。
- ⛔ 退水的**兜底触发点②不可省**（滩上最后一条消散完时补一次）——一条浪在前一条还在滩上时抵滩，①会被 `stillBeach` 守卫跳过，而它的 `REACHED` 转换已发生、不再触发 ⇒ 同样吞掉整轮退水。
- ⛔ 前缘映射**不可**写成 `lerp(spawnFar + crest, shoreYs, morph)`（旧写法）——`adv < MORPH_START` 时 `morph = 0` ⇒ `bfy = spawnFar + crest`，而 `spawnFar` 是**常数**、`crest` 一生只漂移约 103px ⇒ **前 55% 旅程画面上几乎不动**，之后一次性扫过去。owner：「后浪浮现后，就等待在原位置，过一会就会以较快的速度冲出去」。⭐ 正确做法是 `tAdv = adv + (1 − adv)·morph`。
- ⛔ 绘制上的「前浪」**不可**用 `leadWave()`（`isLead = (w === lead)`）——它在滩上无浪时返回还在外海（`adv 0.41~0.77`）的那条，`bfy` 立刻从破碎线尺度切到水线尺度 ⇒ 实测交接跳变回到**中位 187px / 最大 387px**。⭐ 正确做法是 `isLead = (state == W_REACHED || state == W_FADING)`。

---

## 十、上机验收清单（可逐条勾 · 真机）

> 本机仅「编译 + lint + 单测」；**渲染观感与帧率只能真机**。设备 `192.168.0.114:5555`（创维 Android 5.1.1 / API 22）。

| # | 验收项 | 方法 | 预期 |
|---|---|---|---|
| V1 | 编译 | `assembleDebug --no-daemon -Pkotlin.compiler.execution.strategy=in-process` | SUCCESSFUL |
| V2 | 单测全绿 | `testDebugUnitTest`（⛔ 加 `--rerun`，否则可能 UP-TO-DATE 假绿） | 0 失败 |
| V3 | lint 无新增 Error | `lintDebug`（E42 实测：0 errors / 281 warnings 为基线） | 0 Error |
| V4 | ⭐ **浪是动态的** | 暂停播放，盯住浪带 | **画面仍在起伏、浪带仍在向岸推进**；⛔ 不得静止 |
| V5 | ⭐ **首帧时长** | `force-stop` → `am start` → 轮询 `screencap` 尺寸变化计时 | 不应出现秒级黑屏（对照：E42 修前 6369ms） |
| V6 | **真机帧率** | `adb shell settings put global nasasmusic_fps 1` 后看角标，或 `dumpsys gfxinfo` | ≥18 fps（API 22 基准） |
| V7 | 破碎前缘 | 目视 | 前缘呈大瓣→碎瓣→细丝的自相似破碎，非平直折线 |
| V8 | 多条离散浪 + 退水交接 | 目视 | 「1 条在最前 + 1 条排队」两条浪，**后浪全程可见**（从浮出到抵滩每一段都能指出它在哪）；前浪消散到后浪接管之间有一段**看得见的回卷**（非闪动）；⛔ 不得读成「一排等距平行白缎带」；⛔ 不得「走到中间就消失」或「靠近前浪时突然消失」 |
| V9 | 半透明叠加 | 目视 | 白浪能透出下层水色，非纯白填充 |
| V10 | ⭐ swash 周期 + 湿沙 + 沙纹 | 目视（录屏慢放） | 看得出「涌上→到滩消散**与**水线后退**同时**发生→露湿沙→后浪接管再涌」；湿沙只在退水后出现并逐渐变干；沙纹极细不闪 |
| V11 | 低频/高频分离 | 放强鼓点曲 vs 纯打镲曲 | 鼓点→**领头浪**更宽更白（⛔ **不加速前推、不多出浪**）；打镲→只沙纹/飞沫/残沫反应；⭐ **第二条浪的白浪花不随音频强弱变化**（它的 `kA` 有意不吃音频） |
| V12 | ⭐ **无摩尔纹/闪烁** | 全频段扫一遍，静帧细看 | 任何区域无摩尔纹、无色带断裂 |
| V13 | 三档画质 | 切低/中/高 | 各档均可见且不崩；⛔ 三档的**浪队列语义一致**（同样的出浪节奏），低档只是自动降细节 |
| V14 | 进出切换 | 反复进出 20 次 + 反复切画质 | 无 OOM、无黑屏残留、无「浪位置突然跳变」 |
| V15 | 可区分性 | 与瀑布/涟漪/网格并排 | 一眼可分（§1.4） |
| V16 | ⭐ **前浪白色不闪（破洞场连续性）** | ⛔ **目视不可靠**——60fps 下人眼分辨不出单帧 0.74px 的位移（却能看出 6.2px 的整帧闪）⇒ 必须跑**帧间数值检查**：Kotlin 侧 = 门禁 **G9**；原型侧 = `docs/archive/verification/scripts/seaside_hole_continuity_check.js`（抽取 HTML 里**真实的** `hash32`/`hash2`，6 个周期 @60fps） | 「可见洞（`ry ≥ 1.5px`）逐帧**中心或半径**位移 ÷ 自身半径」的全局最大值 **< 0.75**。⭐ 原型实测 **0.31（0.74px）**；改前（`floor` 量化重播种）是 **2.6（6.2px）** ⇒ **8.4×**。⛔ 该指标**必须按可见性加权**（与代码同一 `ry ≥ 1.5px` 阈值）：未加权原始最大值 **9.0px** 全部来自 `u` 回绕那一帧——那帧半径已在阈值之下、不可见 |
| V17 | ⭐⭐ **浪机制专项（帧间数值，⛔ 不可只靠目视）** | Kotlin 侧 = 门禁 **G2 的「破碎线前缘映射」「后浪自由推进」「带宽不归零」「外观量不依赖绘制次序」「非领头浪 `kA` 不吃音频」「水线交接连续性」「退水双触发点」「不可能 overtake」** + **G10 全部**；原型侧 = `docs/archive/verification/scripts/seaside_wave_harness.js`（⚠️ 只引用其第 1/2/6/8/9 段——**第 5b/5c 段判定已失效**，它们仍在检查已被 `wiS` 取代的 `wi`） | 见下方 V17 数值表 |

**V17 数值清单**（harness 实测：60fps / 90s / 1600×900；`seaside_wave_harness.js`）：

| 指标 | 实测值 | 判据 | 说明 |
|---|---|---|---|
| 逐帧水线均值位移 | **6.45 px** | **< 8 px** | 单帧可见跳变阈值 |
| 逐帧水线**最大**位移 | ⚠️ **10.77 px**（个别列，单帧） | ⛔ **未解决** | 均值正常；**成因未定位**。见 §12.4 待办 |
| 退水次数 / 时长 / 幅度 | **14 次 / 90s**，中位 **1567 ms / 83.7 px** | 可见且自然变化 | 满程 94.5px 的 **89%**；固定 `440ms` 会被压到 440/94.5 ⇒ 必须自适应 |
| 交接 `\|Δ bfy\|` | 中位 **1.1 px** / 最大 **3.0 px**，**全部发生在 `adv 1.000`** | 无缝 | ⛔ 对照：旧 `leadWave()` 判定下为中位 **187px** / 最大 **387px** |
| 海面浪数分布 | `{0: 141, 1: 1501, 2: 3757}` 帧 ⇒ **2 条占 95%** | 1 在前 + 1 排队 | ⛔ 对照：纯定时器补浪时仅 16% |
| 第二条浪到滩 | **15 / 15** | 必经 | ⛔ 若为 0，多半是位置钳位（红线 1） |
| 第二条浪 `kA < 0.12` 占比 | **5.3%** | 集中在起步清晰化段 | ✅ **设计内**（`WAVE_SPAWN_FADE_ADV = 0.20` 的行程淡入），⛔ 不得下调 cull 阈值去「修」 |
| 领头浪 `kA < 0.12` 占比 | **28.4%** | 抵滩后 900ms 消散期 | ✅ 设计内，同上 |
| 泳道跳变次数 | **0** | 每浪 `lane` 终身固定 | ⛔ 对照：旧 `isLead ? 0 : (wi&1)+1` 为每浪一生 **2** 次 |
| non-finite `T` 计数 | **0 / 15** | — | ⛔ 若非 0：`spawnWave` 里扫「前浪」必须**按身份排除自身**且在写 `state` 之前算，否则会算出 `T = Infinity` |
| 破洞连续性 | 可见洞逐帧 **0.74 px ＝ 0.31× 半径** | **8.4×** 优于旧（2.6×） | V16 同源 |

---

## 十一、裁决记录

| # | 议题 | 裁决 | 理由 |
|---|---|---|---|
| 1 | 浪的动态性实现 | **离散浪队列 + 一条浪两个位置量（破碎线 / 冲流线）+ 自走时钟**（`WAVE_POOL` 槽位状态机；⛔ 无位置钳位、⛔ 无固定泳道；逐列 `Path` 描边） | 用户明确要求「浪是离散的个体、一轮一轮替换」；⛔ 静态纹理平移会被判失败；⛔ 相位三角（sin 曲线）做不到「后退 / 前缘追上」的离散交接；逐像素填带在 1080p 上不可接受（E42 已证其代价）。「一轮一轮替换」由**到滩即消散 + 下一条接替冲流**实现（§4.3）。⭐ 稳态条数由 `WAVE_FOLLOW_Y = 0.62`（补浪时机）落定为「**1 条在最前 + 1 条排队**」（harness 实测 95% 帧）——所有者本轮先说「2-3 条就够」，后修正为「只留 1 条排队，一条在最前面」；⛔ 不为此单写「几条浪」的常量。⭐⭐ **破碎线与冲流线解耦**是反馈八·10 的最终裁决（owner 选方案 3）：⛔ 位置钳位（红线 1）与 ⛔ 压进水线空间（红线 2）都被实测否掉 |
| 2 | 程序纹理 | ⛔ **一张都不取**，也不新增 `Id` | 原型的海水/粼光/泡沫/沙面全部自绘或自烘；`Id` 一个都不碰即同时避开 ordinal 下标与源码扫描两个坑（§3.2） |
| 3 | 档位 | **`Tier.BASIC`** | 什么都不消耗，且 BASIC 无条件三档全可见 |
| 4 | 烘焙入口 | **自烘**（`buildSandTexture` / `buildFoamTiles` / `buildLaceNet` / `buildCausticNet` / `buildGrain`），⛔ 不碰 `ProceduralTexture.ensure*` | `ensure()` 一次烘 6 张 ≈1240 万像素运算，E42 实测拖出秒级黑屏；本方案连 `ensureFullscreenOnly` 都不需要（§3.3/§4.8） |

---

## 十二、开发任务清单（可勾选 · 进度追踪）

### 12.1 打勾与回顾规程

- 每完成一项在 `[ ]` 打 `[x]`，并在 **§12.4 偏差记录** 追加一行（日期 + 偏差 + 处理）。
- 任何「裁决」被推翻或「参数」改动，先回 §十一/§五 改表，再改代码，⛔ 不得只改代码不动文档。
- 真机验收（V4–V15）**必须在提交前完成**（E42 教训：观感问题只有真机暴露）。

### 12.2 进度总览

- [ ] 阶段 0：方案评审（§十一 4 裁决确认 / §五 参数初值确认）
- [ ] 阶段 1：枚举 + 工厂 + 7 计数 + 名单（F1/F2/F3/F6/F7）
- [ ] 阶段 2：`SeasideRenderer.kt` 主体（F4）
- [ ] 阶段 3：`SeasideTest.kt`（G1–G10，F5）
- [ ] 阶段 4：本机验证（V1–V3）
- [ ] 阶段 5：**真机验收（V4–V17）+ 逐项调参**
- [ ] 阶段 6：文档回填（F8/F9/F10）

### 12.3 任务清单

- [ ] T1.1 枚举 + KDoc（`AppSettings.kt:210` 后追加）
- [ ] T1.2 修正 `:100` 附近「29 套」陈旧注释
- [ ] T1.3 工厂 `when` 分支 + import
- [ ] T1.4 `VisualizerThemeTest` 7 处计数
- [ ] T1.5 `FxCoverageScanTest` 名单 + 类数下限
- [ ] T2.1 渲染器骨架（`onEnterContent` / `onExitContent` + 尺寸守卫 `rebuildGeometry`；⛔ 换尺寸时 `resetWaves()`，不留半个队列）
- [ ] T2.2 ⭐ **浪队列**（§4.3.1）：`WAVE_POOL = 6` 槽位 + 状态机 `EMPTY→ADVANCING→REACHED→FADING→EMPTY`；`spawnWave(): Boolean`（`T = lerp(T_MIN,T_MAX,f) · (follower ? FOLLOW_T_SLOW : 1)`，⛔ `follower` 判据在写 `w.state` **之前**算、⛔ 按身份排除自身，否则 `T = Infinity`）/ `stepWaves(dt)`（补浪调度：滩上有浪不补 / 海面空受 `WAVE_GAP_MS` 闸 / 恰好一条 ADVANCING 且 `y ≥ WAVE_FOLLOW_Y` 就补；⭐ **自由推进 `y += v·dms` 无上限**；`y ≥ SWASH_LEAD_ADV` 时 `swashT += dms`）/ `finishWaves(dt)`（⛔ 不补浪）/ `resetWaves()`；槽位含 `y / v / reach / seed / **serial** / aliveT / fadeT / hit / **swashT** / peak[COLS+1]`
- [ ] T2.3 ⭐ **两个位置量 + 冲流状态机**（§4.3.2）：⭐ `breakerFrontY(adv, i, isLead, lane, shoreYs, fray, w0)`（起点 `spawnFar = −0.04h`、终点 `shoreYs[i]`、含 `tAdv = adv + (1−adv)·morph` 与 `crest·(1−morph)`、`fray·(1−morph)·0.42`）+ `swashFullY(x, t, i, push, w0)`（冲流线满位）+ `leadWave()`（含 `FADING`、滩上优先；⛔ **`drawSwellBands` 不得用它判定 `isLead`**）+ `waterlineAdvance(dms)`（**冲流拥有者** = 滩上浪优先，否则 `swashT > 0` 中 `y` 最大者；`push = clamp(owner.swashT / SWASH_RUNUP_MS)`；退水时 `a = max(0, 1 − retreatT / retreatDur)` 并用**同空间**的 `nextPush >= a` 交接）+ `waveFade(w)`（**时间淡入 × 行程淡入**）；⛔ 帧内顺序 `stepWaves → leadWave → waterlineAdvance → 填 peak[] → finishWaves → commitWetMarks` 不可换
- [ ] T2.4 ⭐ **自走时钟自证**（§4.3.3）：`stepWaves`/`finishWaves`/`waterlineAdvance`/`waveFade` 内**零音频引用**；`computeShore` 里 `swashReachNow` 的计算**排在** `stepWaves(dt)` 之后
- [ ] T2.5 ⭐ **S 曲线浪脊**（§4.3.4）：`noise1` / `fbm1`（八度比 2.07、衰减 0.60）/ `fbmNorm`（`0.5 + 1.45·fbm1`）/ `fbmSigned` / `crestProfile`（域扭曲 + 包络下限 0.12）/ `swashFrontY(x, t, h)`；⛔ **不实现**三正弦叠加；⛔ **不实现** `waveFrontY` / `waveDepthK`
- [ ] T2.6 ⭐ **破碎前缘三件套**：`fillFray(t, lane)`（三八度 + 每列 hash 抖动）/ `fillTears(lane)`（⭐ **写阵与取阵用同一个表达式** —— 原型旧版在此错位、已修，⛔ 不得照抄旧写法，§4.3.4 末）/ 逐列 `lag`（`SWASH_LAG`）
- [ ] T2.7 ⭐ **泡沫律**（§4.3.5）：逐列 `slopeLaw`（`|dy/dx|·FOAM_SLOPE_GAIN·4`）+ `farLaw`（领头 `FOAM_FAR_POW=1.6` / 非领头 `^0.35`）+ `foamK`；落到白沫晕分段 alpha、贴图逐块 alpha、扰动前锋（`farLaw^0.35`）、浪唇（列平均）；领头浪两者硬编码为 1；⛔ `farLaw` **绝不折进带宽**
- [ ] T2.8 ⭐ **泡沫剖面与阶梯**：`foamEdgeAt` / `foamCoreAt`（帐篷，⛔ 两端低于中部）/ `foamTailAt` / `foamTargetAt` / `buildLadder(FOAM_EDGES)` + `FOAM_LADDER_COARSE`；非领头浪走粗阶梯、窄带 `a < 0.25` 隔列采样
- [ ] T2.9 ⭐ **泡沫蕾丝**（§4.3.6）：`buildLaceNet()` 烘拓扑（10×4 抖动网格 + 环绕包边丝 + 竖筋/斜筋/网眼填充）+ `drawFoamLace`（端点共享、内部点正弦、两端钉死、描边、3 距离档 × 4 类线；`dens` 按 `lane` 直算）
- [ ] T2.10 **evenodd 破洞** `punchHoles`（`HOLE_MAX`/`HOLE_PERIOD_MS`，⛔ `gap < 0.07` 不挖，⛔ `ry < 1.5` 或 `rx < 2.5` 丢弃）+ ⭐ **洞场用每洞连续相位**（⛔ 不得用 `floor(t / HOLE_PERIOD_MS)` 换种子、⛔ 横漂不得用 `% 1` 回绕、`grow = sin(πu)` 令生灭不突变；G9 / V16）+ **破碎唇** `drawCrestLip`（⛔ 只对非领头浪）+ **非泡沫浪本体** `drawSwellBody`（远衰减远比泡沫慢）
- [ ] T2.11 **外海泡沫贴图** `buildFoamTiles`（6 张 256²，撕碎丝缕 + 软边斑块 + 边缘 alpha 渐隐，⛔ 渐隐外径必须 < `TS/2`）+ `drawOpenSeaFoam` + **扰动前锋** `drawDisturbance`（贴图**前置**到前缘前方）
- [ ] T2.12 ⭐ **烘焙沙纹理**（§4.3.7）：`buildSandTexture` 四层（湿→干渐变 / 宽柔起伏带 / 潮湿斑 / 像素级颗粒）+ 320 万像素降采样闸门 + 每帧裁剪 blit（⛔ 源矩形 `(0,0,texW,texH)`；⭐ **`SAND_TEX_TOP = 0.46`**，⛔ 不得写回 `0.62`）
- [ ] T2.13 ⭐ **swash 周期 / 湿沙 / 干燥**（§4.3.8）：逐列 `wetMark/wetAmt/wetEdge/dryK/swashPos`（⛔ `wetMark` 初始化 `-1e9`；⛔ **`reachK` 直接不实现**，无读取方）+ `commitWetMarks`（⛔ 只在 `FADING && hit`）+ `dryTau = clamp(WAVE_GAP_MS·SWASH_DRY_FRAC, MIN, MAX)` + 沿岸 3 抽头平滑 + `drawWetWash` / `drawSheen` / `drawPuddles` / `drawResidualStreaks` / `drawWetLine` / `drawSwashFingers`
- [ ] T2.13b ⭐ **退水双触发点 + 守卫**（§4.3.2.3）：`REACHED→FADING` 时「滩上无其他浪」⇒ 起退水（**与消散并行**）；`FADING` 结束且滩上无浪 ⇒ 兜底再起一次；⛔ **两处都必须带 `retreatT < 0` 守卫**；`retreatDur = max(SWASH_RETREAT_MS, min(SWASH_RETREAT_MAX, eta))`
- [ ] T2.14 **水体场**（§4.2）：低分辨率 `fieldCvs` + 三档噪声相位（`8/19/43`）+ fBm 扭曲的 `rowPhase`（⛔ 不是完美等距梳子）+ 行/列级粗糙度场 + `MOTTLE_A` 兜底；`buildCausticNet`（`CNX 20 × CNY 9` 抖动节点 + 每格连右/下 + 低频 warp/每行 shear 去规整化，⛔ **不周期环绕**、⛔ 幅度 ≤ ±0.3 格）+ `drawCausticNet`（两族斜向射线 + **连通胞壁网**（二次曲线、3 档批量 stroke）+ 闪烁亮结，⛔ 不整屏叠、⛔ 不得退回孤立短划）
- [ ] T2.15 **飞沫 / 沙纹 / 残沫**（§4.4）：确定性烘焙点表（`SPLASH_MAX = 180` 用 `hash2`，`RESIDUE_MAX = 52` 用 LCG + 最小间距拒绝）；沙纹 26 条断段浅色调（⛔ 不得摩尔纹）；残沫三笔球体感
- [ ] T2.16 **音频接入**（§4.6）：分频取样 + 一阶平滑（attack 55 / release 260，⛔ 两端钳）+ `bassAvg`/`setImp` + `bandAmp`/`layerAlpha`（`0.32 + 0.68·nearness`）；⭐ **非领头浪的 `kA = clamp(amp·0.55·fade)` 不吃音频**；⛔ 其余全部只驱动外观
- [ ] T2.17 **三档画质降级**（§4.7）：`amp`/`fade` 早退、`kA` 门槛、粗阶梯、隔列采样、湿润度早退、水体场分辨率、低档 `TIME_SCALE = 0.35`；⛔ 降级不改队列语义
- [ ] T2.18 ⭐ **绘制侧三条硬不变量**（§4.3.9）：`wiS = serial & 3` / `lane = 1 + serial % 3`（⛔ 任何外观量不得依赖绘制次序 `wi`）；`shrink = max(0.30, frayK·envK·slK)`（⛔ 带宽不归零）；非领头浪 `amp = 0.46 + 0.45·bandAmp(1 + wiS % 2)`、`kA` 不吃音频
- [ ] T2.19 ⭐ **沙滩螃蟹**（owner 2026-10-04：「在沙滩上加一只小螃蟹爬过去，偶尔出现，直到爬出屏幕去」）。纯装饰层，**⛔ 不得参与任何模拟量**、⛔ 不影响浪/冲流/湿沙。绘制顺序：**沙面与其覆盖层之后、`drawSwellBands` 与贴岸泡沫之前** ⇒ 浪真的能把它淹掉。`crabGapMs(i)`（`hash2` ⛔ 禁用 `Math.random`，否则无法回放验证）+ `crabSlot(t)` 推出第几只、出现时刻与年龄；⭐ 首次出现 `CRAB_T0_MS = 6800`，其后每只间隔 `CRAB_GAP_LO/HI = 25000/45000`（25–45 s，哈希抖动 ⇒ 不规律）；`drawCrab(t, dtMs)`：横穿 `CRAB_SPEED = 0.150·h/s`（≈135 px/s @900，逐只 ±`hash2(k,9219)` 抖动）、离岸线的纵向偏移 `CRAB_OFF_LO/HI = 0.030/0.132`（占 h，逐只在浅水边与干沙之间选）、`CRAB_SPAN = 0.0335·h`（≈30px）、步频 `CRAB_GAIT = 0.0150` rad/ms（≈2.4 Hz）、四条腿 `CRAB_LEG_Y = [-0.55,-0.12,0.33,0.72]×R` 且相位差 `CRAB_LEG_SPREAD = π/2`、淡入淡出 `CRAB_FADE = 420ms`；⭐ **贴岸行走**：每帧从 `shoreYs[]` 求 `max(+CRAB_CLEAR = 8px)` 作为地面 ⛔ 不许走进水里；⭐ **换蟹瞬间直接落位、同蟹才平滑**（`crabCy += (goal − crabCy)·(1 − exp(−dt/220))`，⛔ 帧率相关）；⛔ **暂停时传 `dt = 0`** 随画面一起冻结；调色板 7 项（`crabShell #B4603A` / `crabShellHi #D4905F` / `crabRim #6A3520` / `crabLeg #83422A` / `crabLegFar #63301D` / `crabClaw #C97245` / `crabShadow #4A3520`）。⛔ 每帧零分配
- [ ] T3.1 `SeasideTest` 数值段（G2/G3/G10）
- [ ] T3.2 `SeasideTest` 源码段（G4–G8，含「时间量化」「音频不进时间轴」「无位置钳位」「起退水守卫含 `retreatT < 0`」四条负向自证）
- [ ] T3.3 `SeasideTest` 逐帧连续性段（G9）
- [ ] T4.1 本机构建 + 单测（V1–V3）
- [ ] T5.1 真机动态性 + 首帧 + 帧率（V4–V6）⭐ 最关键
- [ ] T5.2 真机视觉自检（V7–V12）
- [ ] T5.3 三档 + 进出 + 可区分性（V13–V15）
- [ ] T5.4 逐项调参并回写 §五
- [ ] T6.1 `visualizer-effects-list.md` + `technical-overview.md` §10.208 + `CHANGELOG.md`

### 12.4 偏差记录

| # | 计划原写法 | 实际改为 | 理由 |
|---|---|---|---|
| D1 | 单文件 `SeasideRenderer.kt` 内含全部状态与纯函数（照 E42 星空 1552 行单文件 + `internal companion object` 约 250 常量的风格） | ⛔ 拆为 **`SeasideWaves.kt`（模拟核心，纯 Kotlin、零 Android import）** + `SeasideRenderer.kt`（绘制层） | ① 模拟核心只持 `FloatArray`，**可在纯 JVM 单测里直接 `new`**，而渲染器不能（字段初始化会建 `Path`/`Paint`/`Bitmap` ⇒ not mocked）⇒ G2/G3/G9/G10 从「只能源码扫」升级为**数值直调**；② 让「纯函数边界」成为真实文件边界而非 250 常量的 companion；③ 模拟层可与视觉层**并行实施**（模拟参数不依赖视觉调参）。⚠️ 文件边界本身在 Dalvik 上零成本（无此运行时概念），真正成本是**解释帧数** ⇒ 逐列算术仍须留在循环体内（§4.9.4）。⚠️ `SeasideWaves.kt` **必须**放 `visualizer/renderers/`——`PerfBudgetContractTest:140` 只 `walkTopDown` 该目录，放 `visualizer/` 根则完全不被扫描 |
| D2 | §9 红线只禁**圆角** clip | ⛔ 禁**整个 `clipPath(`**，不分圆角与否 | 本项目在 Android 5.1（创维 rtd299o）真机**三次复现** hwui `Region::createTJunctionFreeRegion` RenderThread SIGSEGV。原稿 §9 的禁令范围**窄于真实禁令**，属原稿缺陷。5 处波浪形水线 clip 改为填充几何 + 逐列 `wetAmt` 门控。见 §4.9.3 |
| D3 | §4.3.6 记「97 `createLinearGradient`/帧 = +0.1ms」 | ⛔ 该数字**不可移植**，作废 | 那是桌面 Canvas 2D 的测量值。API 22 电视默认 Dalvik，97 个原生 `LinearGradient`/帧既是 97 次提交、又是 97 次 native 堆着色器分配。§4.9.2 已把该层改为 1 次提交 |
| D4 | §4.7 降级矩阵 LOW 行 | ⛔ **补入「焦散 = 0」**，并把焦散 30 射线 + 56 亮结限定为 HIGH-only | 原稿 LOW 行静默漏掉焦散这一项，是矩阵漏洞而非有意降级 |
| D5 | §七门禁 G1–G10 | ⛔ 新增 **G11 提交预算 / G12 填充预算 / G13 native 堆**，全部为**纯函数**门 | 本轮真机**不可达**（`adb connect 192.168.0.114:5555` 超时 10060），且渲染器无法在 JVM 中 `new`。把提交数与填充量做成**纯函数**（而非源码抓取）才能让性能在**构建期**失败，而不是靠真机目测。见 §4.9.5 |
| D6 | §14.3.4 等处绘制辅助函数签名形如 `private fun drawFoamLace(layer, w0, kA, t, dir)` | ⛔ 一律声明为 **`private fun DrawScope.drawXxx(...)`** | `PerfBudgetContractTest:100` 的正则只匹配 `fun DrawScope.drawXxx(` ⇒ 原签名会让**约 15 个每帧绘制函数整体逃过**零分配 / `Rect(` / 容器分配扫描，而那正是这些违规最容易长进来的地方。零成本地把 15 个函数从「无门禁」变成「有门禁」。见 §4.9.4 |
| D7 | §4.9.5 的 G12 只给了 LOW/MEDIUM 阈值；postFx 未说明是否计入填充 | ⛔ 补 `OVERDRAW_MAX_HIGH = 2.90`；**`postFx` 的 `fill*` 定为 0**（`estimate` 中仍计 1 次提交） | ① §4.9.2 的「改造后 HIGH ≈2.6 屏」**从它自己的元素表算不出来**：诚实模型得 2.815 屏，其中「干沙 blit 0.540 + 远海底色渐变 0.820 + 水体场 blit 0.820」三项是**几何恒等式**、无法靠调参消掉。② postFx 计 0 是为**口径对齐**：oracle 引的 E42 实测「≈2.0 屏 = 29.7 fps」本身不含 postFx，而 postFx 是所有效果共有的固定成本； seaside 计入而 E42 不计会造成 2 屏 vs 3 屏的错位。`LOW ≤ 2.0` / `MEDIUM ≤ 2.8` 一字未改，改造前锚点为提交 **920** / 填充 **8.081 屏** |
| D8 | 门禁直接用 `FxLevel`（来自 Compose / material3） | ⛔ 新增**纯 Kotlin 的 `internal enum class SeaLevel { LOW, MEDIUM, HIGH }`**，由渲染层做 `OFF→LOW` / `LITE→MEDIUM` / `FULL→HIGH` 一次性映射 | `SeasideOpBudget` / `SeasideAudioMap` 必须能在**纯 JVM 单测**里跑（渲染器无法 `new`，这已是唯一可行的守门形态）。引用 `FxLevel` 会把 Compose 拖进这两个模块并让单测 not mocked。同理 ARGB 常量必须写 `0xFFxxxxxx.toInt()` 手工打包（`Color.toArgb()` 在纯 JVM 里未 mock） |
| D9 | 预算表 `SeaOpItem.CRAB.perWave = true`；`SeaOpItem.CAUSTIC.opsMed = 0` | ⛔ `CRAB.perWave` 应为 **`false`**；`CAUSTIC.opsMed` 应为 **`3`**（两项均待下一轮落地） | ① **螃蟹是全局单实例**：`crabSlot(t)` 每帧只产出一个在场对象，且 §14.3.19 明确绘制顺序是「沙面覆层之后、`drawSwellBands` 之前」⇒ 同参数画两次在 source-over 下**会叠亮**。预算按 `waveCount` 乘它属**高估**（方向安全但数据不准，浪数越多偏得越远）。② 焦散：§4.9.2 正文的原意是「**射线与亮结**仅 HIGH、**胞壁网 MEDIUM 保留**」，预算表的 `opsMed = 0` 把胞壁网也砍了，**比正文更严**。渲染层按预算表实现（门禁正是按它判负）⇒ 落地时两者必须对齐，以**正文原意**为准 |
| D10 | §14.3.4 的 `fillStrip` | ⛔ 更名 **`drawFoamStrip`** | 原名不带 `draw` 前缀 ⇒ 逃过 `PerfBudgetContractTest:100` 的 `fun DrawScope.draw*(` 扫描面，正是 §4.9.4 陷阱 ① 描述的失效模式。改名零成本、把它纳入零分配门禁 |
| D11 | §4.9.3「⛔ 全文件零 `clipPath`、零 `BitmapShader`」 | ⛔ **`BitmapShader` 在 `drawSand` 单点解禁**（其余位置仍全禁，并加源码门 + 负向自证） | ⛔ **两条禁令的证据强度不对等**：`clipPath` 的禁令有**真机三次复现的 hwui SIGSEGV**（`Region::createTJunctionFreeRegion`，且波浪岸线正是非矩形区域、走同一条路径）⇒ 不可动；`BitmapShader` 的禁令是**项目约定、无 stated mechanism**（其由来是 E42 的纹理统一走 `ProceduralTexture` 池以便集中生命周期），而 `drawSand` 的位图**本来就在自己手里并已手动 `recycle()`** ⇒ 套 shader **不增加任何生命周期负担**，且 path 自身抗锯齿边缘 = **解析覆盖率**，恰与原型 `clip(sandPath)` 一致。⚠️ **由此得出一条结构性事实，未来加禁令前必须知道**：⛔ 零 `clipPath` **且** ⛔ 零 `BitmapShader` **同时成立时，「上沿贴岸线的纹理 blit」无法实现**——`drawImage` 只收矩形，把位图填进任意轮廓**只有 shader 一条路**。且**改用 alpha 烘焙也不成立**：`shore_ys[]` 在 `SeasideWaves.step` 里**每帧重算**（三项全含 `tMs`：潮汐 + 浪脊漂移 + `fill_fray`破碎场），不是 resize 内恒定的；即便恒定也做不到——原型是 canvas2d 的**抗锯齿 clip 边界**（由 `shoreYs[i]` 亚像素相位解析决定），alpha 烘焙给的是**线性 ramp**，⛔ 不是近似误差而是**两种不同的边缘模型** |
| D12 | HTML 的镜面高光条**逐列**锚定在 `shoreYs[i]` 上（`dst` 从 `shoreYs[i]−WET_OVER` 到 `shoreYs[i]+depth`） | 保留 `drawSheen` 独立 `Path` + 独立 `sheenBrush`（**只含高光 ramp**），渐变的 span **与 `wetBrush` 相同** | ⚠️ **记为 HIGH 档具名偏差**：高光的**逐列锚定已由 ribbon 几何精确表达**（path 逐列 top=`shoreYs[i]−WET_OVER`、bottom=`shoreYs[i]+depth`），共享刷只影响**带内 alpha 坡度** ⇒ 表现为**比原型略暗**。⛔ 不采用「紧贴 `depth` 的 span」——满上涌时 `shoreYs` 可达 `0.725h`，超出 span 上界会被 clamp 到 `t=1` ⇒ **高光整条消失**。⛔ 不用「逐列 97 个渐变」——API 22 上那是**每帧 97 次 native 堆着色器分配**（60fps ⇒ ≈5820 次/秒），是 §4.9.2 标定的头号超标项。另：⚠️ **不得复用 `wetBrush`**——它是湿沙+高光的预合成，复用会把 `alpha≈0.76` 的湿沙再叠进高光带 |

### 12.5 ⭐ 仍未解决 / 未验证（**如实记录，⛔ 不得在别处写成已完成**）

| # | 事项 | 现状 | 备注 |
|---|---|---|---|
| U1 | **单列尖峰**：个别列一帧可动 **10.77px**，而均值 6.45px 正常 | ⛔ **成因未定位** | harness 实测（60fps/90s/1600×900）。V17 表里已把它单列并标 ⚠️。可能方向（⛔ **均未验证**）：`fray` 的逐列 hash 抖动 `FRAY_JIT` 在相邻列间的跳变、`punchHoles` 里 `col = round(hx·COLS)` 的**列吸附**、`env` 在 `pow(...,2.4)` 下的陡峭段 |
| U2 | **「后浪从出现就有白浪花」的前半段未实现** | ⛔ **未验证** | traveling 浪的**前锋泡沫**是否从出生就足量存在，没有验证过。涉及非领头浪 `dir = +1` / 前浪 `dir = −1` 的**方向翻转**（发生在成为前浪的瞬间）——owner 报过「靠近前浪时还是会消失」，本轮只从 `kA`/`bwj`/`lane` 三处解释，**方向翻转这一层没动** |
| U3 | ⛔ ~~全部画面结论均由 owner 目测确认~~ **已于 2026-10-04 更正：本 agent 有视觉反馈渠道** | ✅ 已更正 | ⛔ **此前记为「浏览器全程不可用」是错的**。`browser.*` 那组 MCP 工具确实未连桌面端，**但那不是唯一的路、也不是关键的那条**：真正可用的是 `seaside_visual_driver.mjs`（CDP 驱动真实 Edge、rAF 打桩、确定性推进）→ 截图落 `output/seaside_frames/` → **用 `read` 工具打开 PNG 直接看画面**。该链路已端到端验证，并据此**否决了 foam 的第一版修复**（实测见 U10）。⚠️ 教训：不是能力限制，是**建好工具后没有坚持用**、退回了纯几何验证 |
| U4 | `seaside_wave_harness.js` 的 **5b / 5c 段判定已失效** | ⚠️ 待修或标注 | 5b 仍在扫绘制次序下标 `wi` ⇒ 现在测到的是音频自身漂移而非泄漏；5c 的 `0.02` 阈值与实测 `0.0220` 同量级 ⇒ 会误报「带宽仍在跳」。⭐ 要改成检查 `wiS`/`lane`，⛔ 或在引用时标注失效（§7 / V17 已按后者处理） |
| U5 | 原型 `ragA`/`ragB` 与 `lane` 的**配对错位** | ✅ 已修 2026-10-03 | 曾让外观量间接依赖绘制次序（§4.3.4 末）。原型已把取用判据改为与写入判据同一表达式；⛔ 教训保留为红线，Kotlin 端口照此写或按 §4.3.9 每条浪自有 |
| U6 | 原型若干**注释已滞后于代码** | ⚠️ 已记录未修 | 例：① 参数区**头部注释块**仍写破碎线「行程约 **459px**」，而同一块下方 `WAVE_SPAWN_DEPTH = 0.66` 处已给出正确值「从 ~459px 拉长到 ~**636px**」——只有头部那句没跟上；② `spawnWave` / `stepWaves` 里仍在描述已被 `FOLLOW_T_SLOW` 取代的 `MIN_ARRIVE_GAP_MS` 与「至少相隔 **760ms**」（现为 ≥2600ms）；③ `drawSwellBands` 开头的 `const lead = leadWave()` 是**残留未用变量**（该函数已改用 `state` 判 `isLead`）。⭐ **本文档以代码为准，不以这些注释为准** |
| U7 | ⛔ **真机验收（T5.1–T5.3 / V4–V15）完全未执行** | ⛔ **阻塞：设备不可达** | `adb connect 192.168.0.114:5555` → **超时 10060（主机无响应）**，owner 明确要求全程不询问、故未阻塞等待。⇒ V4–V15 全部**未勾**。**替代验证已就位**：① 模拟核心 11 项 harness 基线**逐位复现**（`SeasideWavesTest`，18 例）；② 性能改为**构建期纯函数门** G11/G12/G13（真机不可达时唯一可行形态）；③ 视觉改由 CDP 驱动截图 + agent 读图。⛔ **但这三条都不能替代「在 Android 5.1 弱 GPU 上跑起来」**——填充率、`clipPath` 段错误、`Bitmap` native 堆、`BlendMode` 语义全部只有真机能验。**上机前不得声称效果达标** |
| U8 | **G13 与 §4.7① 在 4K 上互相冲突** | ⚠️ 待裁定 | `3840×2160` 下沙纹理 4,478,976 被 §4.7① 的 320 万钳住，加其余 ≈0.44M ⇒ **3,644,781 > 3,000,000**。当前裁决：**不动 §4.7① 的 320 万**，单测只断言到 2560×1440。改法只能是把 `SAND_TEX_MAX_PX` 收到 ≈2.4M，但那会让 §4.7① 失效 ⇒ **须 owner 裁定** |
| U9 | 两处规格歧义已按「不猜」处理 | ⚠️ 已记录待确认 | ① §4.6 表头写 `bassRaw` + `frame.beat`，但 §5「鼓点判据」行**只**给幅值判据 ⇒ `useBeatFlag` 默认 `false`（按 §5），置 `true` 则严格 AND `frame.beat`；② §4.9.2 逐项表**没有 `drawPuddles`**（§14.3.6 有、§4.7① 把它与退水残沫写在同一条门控上）⇒ 补为第 21 行 |
| U10 | ⛔ **泡沫第一版修复被视觉复核否决** | ✅ 已否决并退回 | 原问题①「泡沫读作细白色等高线/线框」；第一版修复把唇/晕/阶梯/蕾丝四层全部调淡以消除双线，**双线确实消失，但过度矫正成「几乎不可见」**——3× 特写 `crop_200_0.15_f_93103.png` 显示它是**带青调的半透明细丝与划痕**、整幅**几乎没有白色**、右侧还有横跨约 500px 的笔直斜线。⇒ **方向性纠正**：真实泡沫靠「高不透明度的白色团块 + 柔和不规则边界」，不靠丝缕；蕾丝应降为白团**内部**纹理。⚠️ 同时发现**未申报的问题**：③ 的两道全宽发光横带**比修改前更亮**，且海面与泡沫里都有约 150×170px 的**矩形亮度台阶**（与其已修的「per-column 用 float x ⇒ 16px 梳齿」属同一类缺陷，疑有漏网） |
| U11 | ⛔ **泡沫第二版（白团块）仍被否决，且比第一版更糟** | ✅ 已否决，退回做**结构重建** | 第二版把泡沫做成「大而更白更不透明的团块」：致白像素 177 → 1779（×10）、峰值 0.72，③ 横带 19 → 0、矩形台阶与 ④ 橙色边全部修好。⛔ **但 ① 更糟**：`Dfoam_AFTER_crop.png` 显示白团是**各向同性的圆形棉花糖**、下面是**平坦青色水面**、无方向性结构、无浪体、无致密前缘 ⇒ 整幅读作**漂浮的积云**。**根因不在 alpha/size，在形状各向同性**——真实碎浪沫是**沿浪脊切向拉长、彼此融合成带、上缘致密下缘破碎**的结构，圆球串起来只读作一排云。第三轮已下**结构处方**（锚定 `bfy[x]` / 切向拉长 4–8× / 融合成带 / 加致密亮前缘 / **把浪体画出来**给泡沫依附物）。⚠️ 另发现**运行时 `TypeError`**（`无法合成音频帧，已切到内置合成`）——harness 桩掉音频所以 9 项基线照样全绿，**该问题被 harness 掩盖**⇒ 今后凡「harness 全绿」都不能排除运行时错误，必须另有真实浏览器的一帧 |

---

## 十三、附：同类方案与既有资产

- **同类方案文档（体例基准）**：`docs/visualizer-texture-upgrade-plan.md`（在办）、`docs/archive/photo-spectrum-effect-plan.md`（在办）。⛔ `docs/archive/starry-sky-visualizer-plan.md` 已归档，可作**教训**参考（其 §12.4 的 16 行偏差记录是本方案 §三 的直接来源），但**不可作为契约**。
- **既有可复用资产**：`fx/OverlayFx.kt`（`drawVignette`/`drawGrain`，经 `postFx` 声明式启用）、`fx/AudioSmoother.kt`（本效果 attack 55ms / release 260ms、τ=700ms/500ms）、`visualizer/Easing.kt`、`VisualizerMath.kt`、`SizeCache`。
  ⚠️ `fx/ProceduralTexture.kt`（`WATER:363` / `CAUSTIC:380` / `ensureFullscreenOnly:112`）**v1.2 一张都不取用**——原型的海水/粼光/泡沫/沙面全部自绘或自烘（§2.5/§3.2/§4.8）。此处列出仅为记录「它存在、且我们刻意不用」。
- **参考图版权**：⚠️ 参考图为第三方图库素材（昵图网水印）。**方案与代码只取其构图与色彩关系，不复制图像本身**；实现全部为程序化绘制，无任何外部素材入库。

---

## 十四、开发实施手册（可开发级）

### 14.1 自检表（开工前勾）

- [ ] 已读完 §2.2 真实契约命名、§2.6 红线、§3 全部复用陷阱
- [ ] 已确认 §十一 4 条裁决、§五 参数初值
- [ ] 已确认基线 `bb3a603`、工作区无冲突改动

### 14.2 逐文件清单

见 §八 F1–F10。

### 14.3 完整接口签名（与原型逐函数对应）

> ⭐⭐ **v1.2：本节已按 `docs/archive/seaside-preview.html` 的实际代码重写两轮。** 第一轮把「相位三角 + 固定泳道」架构换成「离散浪队列」；⭐ **第二轮（反馈八·10）把「一个 `adv` 管两件事」换成「一条浪两个位置量」**——破碎线 `breakerFrontY`（出生 `−0.04h` → `shoreYs[i]`，行程 ≈636px，画白浪带）与冲流线 `swashFrontY → wlineFull`（行程 ≈81px，驱动湿沙/退水/干燥）。因此 `waveFrontY` / `waveDepthK` / `WAVE_SPACING_Y` / `MIN_ARRIVE_GAP_MS` / `CONVERGE_K` / `crestK` **全部不存在**，⛔ 不要照搬任何旧签名。
> 下面是**原型（JS）**的真实签名，Kotlin 端口**逐函数搬移**：`camelCase` → `snake_case`（项目约定）、`let/const` 数组 → `FloatArray`/`internal const val`、`Math.*` → `kotlin.math.*`、`new Path2D()` → 复用成员 `Path`（⛔ 每帧零分配，`Path2D` 在 Kotlin 侧等价物是 `Path`，而 `Path` 必须预分配）。
> ⭐⭐ **移植顺序建议**：先只做 `swashFrontY` + `waterlineAdvance` + `breakerFrontY`（三个纯函数，无绘制），把 G2/G10 的数值断言跑绿，⛔ 再接绘制层。**两个位置量的正确性用数值断言验证远比目测可靠**——原型上「数值全对、画面全无」的失败有两次。

#### 14.3.1 确定性 hash / 噪声 / 剖面（纯函数，`internal companion object`，G2 直调）

```kotlin
/** 确定性 32bit hash → [0,1)。⛔ 绝不 Math.random / Random() */
internal fun hash32(x: Int): Float

/** 二维 hash（`i` 加盐）→ [0,1)。飞沫/残沫/蕾丝属性的唯一来源 */
internal fun hash2(i: Int, salt: Int): Float

/** 确定性一维值噪声（hash 晶格 + smoothstep 插值）—— 沿shore轴的随机性来源 */
internal fun noise1(x: Float, seed: Int): Float

/** 分形叠加（fBm）。⭐ 八度比 2.07（频率互不成整数比 ⇒ 无可察觉重复）、
 *  ⛔ 衰减 0.60（0.5 会让能量几乎全在最低八度 ⇒ 浪脊波长长、幅度小） */
internal fun fbm1(x: Float, seed: Int, oct: Int): Float

/** ⭐ 把 fbm1 拉满到 0..1 供包络/密度场使用。
 *  ⛔ 0.5 + 1.45·fbm1（不是 0.5 + 0.62·：后者把所有包络压在 0.26..0.74，
 *    既到不了 0（做不出平静空档）也到不了 1） */
internal fun fbmNorm(x: Float, seed: Int, oct: Int): Float

/** 同上但返回 −1..1（逐列双向偏移，例如推进滞后） */
internal fun fbmSigned(x: Float, seed: Int, oct: Int): Float

/** ⭐⭐ 浪脊横向剖面：域扭曲 fBm，⛔ 不是正弦叠加。
 *  wx/wy 两个低频 fBm 偏移采样坐标（浪脊自己拐弯成 S）
 *  env 包络（下限 0.12 ⇒ 有的岸段弧得很高、有的一直很低）
 *  返回典型峰峰 0.6~0.9（再乘 CREST_AMP 得纵向起伏） */
internal fun crestProfile(u: Float, seed: Int): Float

/** 确定性 2D 值噪声（hash 晶格 + 双线性）—— 沙的斑驳与起伏带扭曲 */
internal fun vnoise2(x: Float, y: Float, seed: Int): Float

/** sin 查表（原型 LUT_N = 4096）—— ⛔ Kotlin 侧若热点吃紧再上，真机测过再说 */
internal fun fsin(a: Float): Float
```

#### 14.3.2 浪队列（**实例状态**，⛔ 不可放 companion object）

```kotlin
/** 生命周期状态。⛔ 没有 DEAD —— 淡出完毕直接回写 W_EMPTY */
internal const val W_EMPTY = 0
internal const val W_ADVANCING = 1
internal const val W_REACHED = 2
internal const val W_FADING = 3
internal const val W_REACH_Y = 1.00f
/** ⭐ 补浪时机：滩上无浪 + 恰好一条 ADVANCING 且它已过 y ≥ 0.62 ⇒ 在它后面补一条。
 *  ⛔ spawnAcc / WAVE_GAP_MS 只剩「海面空」那条路径的最小间隔闸 */
internal const val WAVE_FOLLOW_Y = 0.62f
/** ⭐ 跟随者（海面上第二条浪）的行程倍率 >1 即更慢。
 *  ⛔ 这**不是**硬性到达间隔（owner 明确否定过那种约束：它把退水压成恒定的
 *     117ms/20.7px，抹掉了「来得早就退得短、来得晚就退得长」），只是把后浪的
 *     T 分布整体后移。⚠️ 调大幅度会同时拉长整个周期 */
internal const val FOLLOW_T_SLOW = 2.0f

/** 一个槽位 = 一条浪。peak[] 长度 COLS + 1 */
internal class Wave {
    var state = W_EMPTY
    var y = 0f            // ⭐ 破碎线行程（adv 空间）：0 = 出生，1.0 = 到达滩上
                           //    ⛔ 这**不是**像素位置；像素位置由 breakerFrontY 现算
    var v = 0f            // 每 ms 的 Δy，出生定死、途中不变
    var reach = 1f        // 这条浪自己的涌高系数 0.62..1.40（⚠️ 当前不参与几何，§5.5）
    var seed = 0          // 3300 + serial·733 —— ⭐ 破碎线浪脊形状的唯一来源
    var serial = -1       // ⭐ 出生序号，每出生 +1。槽位会复用 ⇒ 身份索引与单浪跟踪全靠它
    var aliveT = 0f       // 出生后已过 ms（时间淡入用）
    var fadeT = 0f        // 消散已过 ms
    var hit = false       // 到过滩上？= 会留湿沙
    var swashT = 0f       // ⭐ 冲流计时：y >= SWASH_LEAD_ADV 起累加（含 REACHED/FADING）
    val peak = FloatArray(COLS + 1)
}

/** 取第一个空槽；池满则这一拍不出浪（不丢帧、不跳变）⇒ 返回是否真的占到了槽位。
 *  ⭐ 调用方据此决定要不要清零 spawnAcc。
 *  ⭐ v = 1 / (lerp(WAVE_T_MIN, WAVE_T_MAX, fbmNorm(...)) * (follower ? FOLLOW_T_SLOW : 1))
 *  ⛔⛔ `follower` 判据（池里是否已有 W_ADVANCING）**必须在写 w.state 之前**算，且
 *     **按身份排除自身**。否则会扫到刚创建的自己：那时 y=0、v 还是上一条浪残留的 0
 *     ⇒ T = Infinity ⇒ v = 0 ⇒ 这条浪永远不动（harness 实测 ser0 T=Infinity v=0，
 *     海上只剩 1 条不会动的浪）
 *  ⭐ 不可能 overtake 的根据：min(T_跟随) = 4200×2.0 = 8400 > max(T_领队) = 5800
 *     ⇒ 两档区间不重叠 ⇒ 到达顺序恒等于出浪顺序、间隔恒 ≥ 2600ms */
private fun spawnWave(): Boolean

/** 第一段：⭐**补浪调度** + 推进 + 到达判定 + swashT 累加。
 *  ⛔ 补浪不是固定定时器（spawnAcc / WAVE_GAP_MS 只当「海面空」的最小间隔闸）：
 *    滩上有 REACHED/FADING ⇒ 不补；advN==0 ⇒ 受 WAVE_GAP_MS 闸；
 *    advN==1 且 front.y >= WAVE_FOLLOW_Y ⇒ 补一条排在它后面。
 *  ⭐⭐ **自由推进，不做位置钳位**（§九 红线 1）：w.y += w.v * dms，无任何上限。
 *    ⛔ 冻结位置（曾用 WAVE_SPACING_Y）实测有害：后浪 adv 从未超过 0.6、永远到不了滩，
 *      67% 的绘制调用因 kA 过低被 cull ⇒ 读作「走到中间就消失」。
 *  ⭐ **退水期间 ADVANCING 的浪照常推进**（⛔ 不得加 retreatT 冻结闸——所有者要求
 *    「一直推进直到遇上回水，中间不停顿」）。
 *  ⭐ w.y >= SWASH_LEAD_ADV ⇒ w.swashT += dms（⚠️ 对 REACHED/FADING 也累加，
 *    它们 y 恒为 1.0 ⇒ 「破碎线抵滩」与「冲流满位」同一帧发生）。
 *  ⛔ 不在这里做 REACHED→FADING（必须等 computeShore 写完 peak[]）
 *  ⛔ 零音频引用。防御闸门：滩上有 REACHED/FADING 时到达的浪不转态（shoreBusy） */
private fun stepWaves(dt: Float)

/** 第二段：REACHED→FADING、淡出、回收、⭐ 起退水的**双触发点**。必须在写完 peak[] 之后调用。
 *  ⛔ **不在这里补浪**（早先在这一帧顺手发一条会绕过「滩上不补」的约束 ⇒ 多一条浪）
 *  ⭐ 触发点①：`REACHED → FADING` 且**滩上没有其他浪** ⇒ retreatT = 0。
 *     ⭐ 这是**并行**而非先后：泡沫在 WAVE_FADE_MS 里淡出、退水在 retreatDur 里回落。
 *        owner：「浪花消失同时海水回退」。
 *  ⭐ 触发点②（兜底，⛔ 不可省）：滩上**最后一条** FADING 淡出完毕那一帧 ⇒ retreatT = 0。
 *     ⛔ 不可省的原因：一条浪在前一条还在滩上时抵滩，①会被 stillBeach 守卫跳过，
 *        而它的 REACHED 转换已发生、不再触发 ⇒ 整轮退水被吞（症状：90 秒只剩 1 次）。
 *  ⛔⛔ **两处守卫都必须含 retreatT < 0**：否则退水进行中被重置 retreatT = 0 ⇒
 *     水线从已退回的位置（实测 meanAdv 0.0685）被瞬间拽回满位（0.9611）
 *     ⇒ **78.79px 单帧跳变**。⛔ 拿掉守卫是「顺手加固」时最容易做的事。 */
private fun finishWaves(dt: Float)

/** 恢复到「海上一条浪都没有」；resize 时调用（换尺寸不留半个队列） */
private fun resetWaves()

/** ⭐ 领头的浪 —— ⛔ **只**在 computeShore 里用（取 seedLag 与写 peak[]）。
 *  ⛔ 必须把 FADING 也算进来（否则泡沫前缘与沙面脱开）；⛔ 滩上的浪优先于海里的浪。
 *  ⛔⛔ **drawSwellBands 不得用它判定 isLead**（§九 附条） */
private fun leadWave(): Wave?

/** ⭐⭐ 冲流推进量 0..1 —— 队列级标量，两个位置量中的**冲流线**。
 *
 *  ① **拥有者（owner）**：滩上的浪（REACHED/FADING）优先；否则取**破碎线已进入上涌区**
 *     （swashT > 0）的浪中 y 最大者。push = clamp(owner.swashT / SWASH_RUNUP_MS, 0, 1)
 *  ② 不在退水 ⇒ 返回 push；无拥有者 ⇒ 返回 0（冲流完全退回）
 *  ③ 退水中（retreatT >= 0）：
 *     retreatDur = max(SWASH_RETREAT_MS, min(SWASH_RETREAT_MAX, eta))，
 *                 eta = max(0, (SWASH_LEAD_ADV - front.y) / front.v)   // v 是 adv/ms
 *     a = max(0, 1 - retreatT / retreatDur)
 *     incoming  = if (owner != null && !ownerBeach) owner else null
 *     nextPush  = clamp(incoming.swashT / SWASH_RUNUP_MS, 0, 1)
 *     if (nextPush > 0 && nextPush >= a) { retreatT = -1; return nextPush }   // 交接
 *     return a
 *
 *  ⛔⛔ **交接比较必须在同一参数化空间内**（§九 红线 8）：a 与 nextPush **都是冲流 adv**
 *     （0 = 完全退回、1 = 满位）。⛔ 不可写成 `front.y >= a` —— 那拿 636px 尺度的破碎线
 *     adv 比 81px 尺度的冲流 adv ⇒ 交接提前到 adv 0.41~0.77、整条带瞬移 150~387px。
 *  ⛔⛔ 必须**排除滩上 owner**：退水改为「抵滩即起」后，起退水时 owner 正是那条刚抵滩的
 *     浪、swashT 已满 ⇒ push = 1，而 a 起始也是 1 ⇒ 退水在第一帧就被取消。
 *  ⛔⛔ 但⛔ **不能**写成 `front !== owner`：后浪进上涌区后自己就成了 owner ⇒ 交接永不
 *     触发 ⇒ retreatT 永不重置为 -1 ⇒ 之后所有起退水守卫全被挡住 ⇒ 整轮退水被吞。
 *  ⭐ 交接点按构造连续：交接那一帧两条曲线交叉；原型实测交接 |Δ bfy| 中位 1.1px /
 *     最大 3.0px，且**全部发生在 adv = 1.000**。 */
private fun waterlineAdvance(dms: Float): Float

/** ⭐ 泡沫可见度 = ⭐ **时间淡入 × 行程淡入**（FADING 时再乘消散项）：
 *  clamp(aliveT / WAVE_SPAWN_RAMP_MS, 0, 1) * clamp(y / WAVE_SPAWN_FADE_ADV, 0, 1)
 *  ⛔ 只用时间淡入不可用：后浪一生约 10s，700ms 只占 7% ⇒ 读作「凭空出现」
 *     （owner：「应该从最远端逐渐清晰化出现」）。⭐ 加上行程淡入后，它在最远端那
 *     20% 行程里逐渐清晰化，到岸附近时 rampIn 已满。 */
private fun waveFade(w: Wave?): Float

/** ⭐ 湿沙高水位**只在浪到达滩上那一瞬**写入。⛔ 上冲途中一个字都不写 */
private fun commitWetMarks()
```

⭐⭐ **破碎线前缘映射**（两个位置量中的**破碎线**）——**这是移植时最容易搞错的一段，单独列出**：

```kotlin
/** ⭐⭐ 破碎线（breaker）前缘 y。这是「画出来的白浪带」，也是「第二条浪全程可见」的依据。
 *
 *  @param isLead  ⭐ **绘制上的前浪 = 真正抵达滩上的那条**（REACHED / FADING）。
 *                 ⛔ 绝不是 `w === leadWave()` —— leadWave() 在滩上无浪时返回还在外海
 *                   （adv 0.41~0.77）的那条，一旦被判定为前浪，前缘立刻从破碎线尺度
 *                   切到水线尺度 ⇒ 整条带瞬移（实测中位 187px / 最大 387px）。
 *  @param lane     泳道号 = 1 + (serial % 3)，⭐ 终身固定、恒 != 0
 *  @param shoreYs  水线（由 computeShore 产出，见下）
 *
 *  出生在远海 spawnFar = h·(SHORE_K - WAVE_SPAWN_DEPTH) = -0.04h（画面顶边上方），
 *  终点 shoreYs[i] ⇒ 行程约 636px @1600×900。 */
private fun breakerFrontY(adv: Float, i: Int, isLead: Boolean, lane: Int,
                          shoreYs: FloatArray, fray: FloatArray, w0: Float): Float {
    if (isLead) return shoreYs[i]                    // ⭐ adv==1 时二者严格相等 ⇒ 交接无缝
    val spawnFar = h * (SHORE_K - WAVE_SPAWN_DEPTH)
    // 位置参数化：⭐ 前 55% 线性推进，后 45% 平滑补足。
    // ⛔⛔ 不可写成 lerp(spawnFar + crest, shoreYs, morph)：那样 adv < MORPH_START 时
    //   morph=0 ⇒ bfy = spawnFar + crest，而 spawnFar 是常数、crest 一生只漂移约 103px
    //   ⇒ 前 55% 旅程画面上几乎不动（owner：「后浪浮现后等待在原位置，过一会突然快速冲出去」）
    val morph = smoothstep(MORPH_START, 1f, adv)   // 0.55
    val tAdv  = adv + (1f - adv) * morph            // 单调、终点 1、adv<MORPH_START 时 ≡ adv
    // 破碎线的 S 形起伏：⭐ 相位漂移挂在**该浪自己的 adv** 上（CREST_DRIFT_ADV = 0.35），
    //   ⛔ 不挂全局时间（挂 t·0.0000060 ⇒ 一生只变 0.06 ⇒ 读作固定曲线）；
    //   `- lane·CREST_LANE_SHIFT` 让每条浪的曲线相位错开（否则两条浪同形）
    val uC = xOf(i) * 0.00340f * kScale + adv * CREST_DRIFT_ADV - lane * CREST_LANE_SHIFT
    // ⛔⛔ crest 与下面的 fray 项**都必须乘 (1 - morph)**：shoreYs[i] 里已经包含前浪
    //   自己的曲线（swashFrontY 内的 crestProfile·h·CREST_AMP_SHORE）与 fray（**同系数 0.42**），
    //   所以这两项必须随 morph 被**吸收**，⛔ 不能叠加在插值结果之上 ⇒ 否则重复计入、
    //   交接处又出现跳变。
    val crest = crestProfile(uC, wave.seed) * h * CREST_AMP * (1f - morph)
    return spawnFar + (shoreYs[i] - spawnFar) * tAdv + crest
         + (1f - morph) * fray[i] * w0 * FRAY_FRONT * 0.42f
}

/** ⭐ 冲流线（swash）满位 wlineFull —— 破碎线的终点，也是 `shoreYs` 的上沿：
 *  swashFrontY(x, t, h) + fray[i]·w0·FRAY_FRONT·0.42 + h·swashReachNow·(冲流 adv)
 *  ⇒ 它的总行程只有 h·swashReachNow ≈ 81px。 */
private fun swashFullY(x: Float, t: Float, i: Int, push: Float, w0: Float): Float
```

**⭐ 两个位置量在 Kotlin 端的对应关系**（移植时照这张表建类/字段，不要合并成一个量）：

| 原型 | Kotlin | 谁读它 |
|---|---|---|
| `w.y`（破碎线行程，adv 空间） | `Wave.y` | `stepWaves` 推进、`waterlineAdvance` 选 owner/算 `eta`、G2/G10 断言 |
| `breakerFrontY(...)`（破碎线，像素） | `breakerFrontY(...)` → 写进 `bfy[i]` | `drawSwellBands` 的逐列循环，以及**所有吃 `bfy`/`bwj` 的绘制层**（`fillStrip`/`punchHoles`/`drawFoamLace`/`drawSwellBody`/`drawSeaFoamWash`/`drawOpenSeaFoam`/`drawDisturbance`/`drawCrestLip`） |
| `swashFrontY(x,t,h)`（冲流线起点） | `swashFrontY(...)` | `computeShore` |
| `shoreYs[i]`（冲流线当前位置 = 水线） | `shoreYs` | 沙面裁剪、湿沙/高光/水洼/残沫/湿线/浪花手指、`peak[]`、以及**领头浪的 `bfy`** |
| `waterlineAdvance(dms)`（冲流推进量 `push`） | `waterlineAdvance(dms)` | `computeShore` → 逐列 `adv = clamp(push - lag·SWASH_LAG, 0, 1)` |

⭐ **结构性要点**：`bfy`（破碎线）与 `shoreYs`（冲流线）是**两个不同的数组**，只在「这条浪已抵达滩上」时**逐列相等**。⛔ 不得为了省一个数组把它们合并——那正是 §4.3.2 早期失败的根源（一个浪对象带两套坐标定义）。

#### 14.3.3 岸线与逐列记忆

```kotlin
/** ⭐⭐ 全场景的唯一基准：fillFray → stepWaves → leadWave → waterlineAdvance
 *  → 逐列算 shoreYs（= 冲流线）并把当帧到滩的 y 写进 lead.peak[] → finishWaves
 *  → commitWetMarks → 沿岸 3 抽头平滑 wetAmt。
 *  ⛔ 顺序不可换（换序 ⇒ peak[] 永远空 ⇒ 湿沙一列都没被写过）
 *  ⛔⛔ 函数体内除 swashReachNow 外零音频；且 swashReachNow 的计算排在
 *     stepWaves / waterlineAdvance **之后** ⇒ 结构上无法影响时序
 *  ⭐ shoreYs[i] = swashFrontY(x,t,h) + fray[i]·w0·FRAY_FRONT·0.42 + h·swashReachNow·adv
 *     其中 adv = clamp(push - lag·SWASH_LAG, 0, 1)
 *     ⛔ **不含任何破碎线插值项**、⛔ 不再对 y 做 smoothstep/三角、⛔ 不再有逐列涌高偏移
 *     （reachK 已被移除；逐浪差异改由 T / w.seed / env·rag·fray 噪声承担）
 *  dt 为秒；暂停时传 0（退水不会干、队列完全冻结） */
private fun computeShore(t: Float, w0: Float, dt: Float)

/** ⭐ 层序强度：按**离岸进度**而非层号 —— 一条浪离岸越近越强
 *  strength = 0.32 + 0.68·nearness（⭐ 下限由 0.22 抬到 0.32）；
 *  thresh = ENERGY_TO_LAYERS·0.5·(1 - nearness)；appear = 0.30 + 0.70·smoothstep(thresh,1,e)
 *  ⛔ **只领头浪用**它；非领头浪的 kA = clamp(amp·0.55·fade) 有意不吃音频（§4.3.9 ③） */
private fun layerAlpha(adv: Float, energy: Float): Float

/** 平滑折线（过中点的二次曲线）；stride 支持半列分辨率而不塌到左半屏 */
private fun smoothRun(path: Path, xs: FloatArray, ys: FloatArray, n: Int,
                       forward: Boolean, moveFirst: Boolean, stride: Int = 1)

/** smoothRun 的区间版本（白沫晕分段用；段与段共用曲线 ⇒ 无竖直接缝） */
private fun smoothRunRange(path: Path, xs: FloatArray, ys: FloatArray, i0: Int, i1: Int)

/** 沿一串 y 填一条带状 path */
private fun ribbon(path: Path, xs: FloatArray, ys: FloatArray, n: Int, stride: Int = 1)
```

逐列数组（全部 `FloatArray(COLS + 1)`，⛔ 每帧不得新建）：`shoreXs` / `shoreYs` / `shoreTmp` / `wetMark`（⛔ 初始化 `-1e9`）/ `wetAmt` / `wetEdge` / `dryK` / `swashPos` / `wetTmp` / `bxs` / `bfy` / `bwj` / `washA` / `washB` / `slopeLaw` / `farLaw` / `fray` / `edgeYs` / `slabLo` / `slabHi`。
⛔ **`reachK` 不列入**（无读取方，§5.5）；⭐ 撕裂场建议按 §4.3.9 改为**每条浪自有**的一份（原型 `ragA`/`ragB` 已修，写/取判据同一表达式，见 §4.3.4 末）。

#### 14.3.4 泡沫与浪的绘制层

```kotlin
/** 前缘唇：保持 CREST_HOLD 宽才衰减（早期从 n=0 就掉 ⇒ 读成「玻璃带」） */
internal fun foamEdgeAt(n: Float): Float

/** 浪心帐篷：⛔ 两端都低于中部 ⇒ 与前缘合成双峰；峰值锁 FOAM_CORE_A */
internal fun foamCoreAt(n: Float): Float

/** 拖尾：先平滑接入浪心，再二次渐隐，正好在 TAIL_OUT 归零 */
internal fun foamTailAt(n: Float): Float

/** 泡沫总剖面 */
internal fun foamTargetAt(n: Float): Float

/** 把剖面量化成若干条**互不重叠**的窄带（a < 0.006 的丢弃） */
internal fun buildLadder(edges: FloatArray): Array<FoamStrip>

/** 分形破碎场：三八度（振幅 ≈0.47 衰减）+ 每列固定 hash 抖动。
 *  ⭐ 参数是**泳道号 lane**（1 + serial%3），⛔ 不是绘制次序下标 */
private fun fillFray(t: Float, lane: Int)

/** 逐列带宽倍率（两套慢/快正弦）⇒ 参差前缘。
 *  ⭐⭐ **按 lane 选/写同一套场**：Kotlin 端口应为每条浪算出自己的一份（可复用 2~3 份
 *     预分配数组按 lane 索引）。⛔ **写阵与取阵必须是同一个表达式** —— 原型曾用
 *     `(L & 1)` 写阵、按 `(lane==0||lane==1)` 取阵，两个判据不等价 ⇒ 一条浪的撕裂场
 *     内容取决于同帧里别的浪的绘制次序（违反红线 6）。该缺陷已于 2026-10-03 修为
 *     `((lane & 1) === 0)` 两侧共用（§4.3.4 末），端口照此写即可。 */
private fun fillTears(lane: Int)

/** evenodd 破洞：⛔ gap < 0.07 不挖（会破形）；⛔ ry < 1.5 || rx < 2.5 的孔丢弃。
 *
 *  ⭐ 洞场 = 每洞**连续相位**驱动的生灭/漂移，⛔⛔ 不是「按周期重算的随机场」：
 *    ph = hash2(h, 700 + strip*31 + layer*7)            // 每洞固定相位
 *    u  = ((t / HOLE_PERIOD_MS) + ph) % 1               // ⛔ 不取整（见下两条红线）
 *    hx = clamp(ph + 0.13*sin(t*0.00021 + ph*6.2832), 0.02, 0.98)   // 有界振荡
 *    grow = sin(PI * u)                                 // 0 → 1 → 0
 *    hn  = n0 + gap * (0.12 + 0.76 * u)                  // 沿条带连续推进
 *    hr  = gap * (0.06 + 0.30 * grow) * (0.70 + 0.60 * hash2(h, 720 + strip*17 + layer*5))
 *
 *  ⛔ 红线 1（量化时间换种子）：⛔ 不得写 hash2(h, 710 + floor(t / HOLE_PERIOD_MS) + …)。
 *    破洞与条带轮廓**一次 evenodd fill** ⇒ 周期边界上所有洞的位置与半径同帧整体瞬移
 *    ⇒ 白沫整帧闪一次，且所有洞一起眨眼。原型实测（docs/archive/verification/scripts/
 *    seaside_hole_continuity_check.js，6 周期 @60fps，可见洞 ry >= 1.5 的逐帧中心/半径
 *    最坏位移）：6.2px（2.6× 自身半径）→ 0.74px（0.31×），8.4×。⛔ 指标必须按
 *    ry >= 1.5 做可见性加权（原始未加权最大值 9.0px 全来自 u 回绕帧——那帧半径同时在
 *    阈值之下、不可见）。
 *  ⛔ 红线 2（取模回绕）：横漂用 clamp 的有界振荡，⛔ 不得写成 (ph + … + 1) % 1 ——
 *    回绕让 hx 从 ~1.0 跳到 ~0.0，洞横穿整屏，原型实测 1599px/帧（≈整屏宽，
 *    比它要修的 bug 差 ~270×）。通则：逐帧程序化场里取模回绕 ＝ 不连续。
 *  ⭐ grow 使半径在 u 两端收缩到地板 gap*0.06*k（按实测「回绕帧不可见」反推：已在 ry < 1.5
 *    之下）⇒ 生灭不突变，u 的回绕无害；⛔ 但若填充方式不再是 evenodd 单次 fill，
 *    本节两条红线的紧迫性下降。 */
private fun punchHoles(path: Path, layer: Int, strip: Int, n0: Float, n1: Float,
                        w0: Float, kA: Float, t: Float, dir: Int)

/** 一条窄带的填充。⛔ 不再沿 x 切子路径各给 alpha（source-over 重叠会叠亮成硬边矩形） */
private fun fillStrip(layer: Int, s: Int, ladder: Array<FoamStrip>, step2: Int,
                      w0: Float, kA: Float, t: Float, dir: Int)

/** ⭐ 泡沫蕾丝：u-v 空间的网拓扑在 resize 时烘好，每帧只映射端点。
 *  ⭐ 端点**共享**是连通的关键；内部点叠正弦但 sin(0)=sin(π)=0 ⇒ 端点钉死
 *  描边而非图元（stroke 天然给出细线）；3 距离档 × 4 类线 */
private fun drawFoamLace(layer: Int, w0: Float, kA: Float, t: Float, dir: Int)

/** ⭐ 非泡沫浪本体：迎光亮 + 背光暗的体积感。距离衰减远比泡沫慢 */
private fun drawSwellBody(layer: Int, w0: Float, kA: Float, dir: Int)

/** 波面大白沫晕：一条 path + 一条竖直渐变，分 8 段各按本段 foamK 调 alpha */
private fun drawSeaFoamWash(layer: Int, w0: Float, kA: Float, dir: Int)

/** 外海泡沫贴图：逐块按该列 foamK 调 alpha（⛔ 不吸附到列栅格） */
private fun drawOpenSeaFoam(layer: Int, w0: Float, kA: Float, t: Float, dir: Int)

/** 扰动前锋：贴图**前置**到前缘前方 0.30~1.45 带宽；距离律用 farLaw^0.35 */
private fun drawDisturbance(layer: Int, w0: Float, kA: Float, t: Float, dir: Int)

/** 锐利的破碎唇（窄高光 + 紧贴的暗带）。⛔ 只对非领头浪画 */
private fun drawCrestLip(w0: Float, kA: Float, dir: Int)

/** ⭐⭐ 队列在场浪全画：按 y 升序（外海先画），抵达滩上的那条最后压上去。
 *  ⛔ 这里没有任何音频，也**没有固定的四条浪带**
 *
 *  逐浪循环里必须按 §4.3.9 的三条硬不变量组织：
 *    val wiS  = w.serial and 3                   // ⛔ 不得用绘制次序下标 wi
 *    val lane = 1 + (w.serial % 3)               // ⭐ 终身固定、恒 != 0
 *    val isLead = (w.state == W_REACHED || w.state == W_FADING)   // ⛔ 不是 w === leadWave()
 *    val amp  = if (isLead) 0.55f + 0.45f * bandAmp(0)
 *               else 0.46f + 0.45f * bandAmp(1 + (wiS % 2))
 *    if (amp <= SILENCE_FLOOR) continue
 *    val fade = waveFade(w); if (fade <= 0.02f) continue
 *    //  ⭐ 非领头浪的 kA 只剩 amp × 系数 × 淡入：不吃音频门控、不吃近岸斜坡
 *    val kA   = if (isLead) clamp(amp * layerAlpha(adv, energy) * fade, 0f, 1f)
 *               else       clamp(amp * 0.55f * fade, 0f, 1f)
 *    val w0   = h * SWELL_BAND_W * (0.62f + 0.62f * amp) * (if (isLead) 1.0f else 0.92f)
 *    fillFray(t, lane); fillTears(lane)
 *    //  逐列循环：bfy[i] = breakerFrontY(...)（或 shoreYs[i]）；slopeLaw/farLaw；
 *    //            env = pow(fbmNorm(..., 8100 + wiS*53, 2), 2.4)（isLead 时 = 1）
 *    //            frayK/envK/slK ⇒ shrink = max(0.30f, ...) ⇒ bwj[i]
 *    //            if (isLead) { slopeLaw[i] = 1f; farLaw[i] = 1f; edgeYs[i] = bfy[i] }
 *    val boost = if (isLead) SHORE_FOAM_BOOST else 1f
 *    val dir   = if (isLead) -1 else 1            // ⭐ 非领头浪向岸、领头浪向海
 *    //  fillStrip 阶梯 → drawSwellBody → drawSeaFoamWash → drawOpenSeaFoam
 *    //  → (非领头浪才 drawDisturbance) → drawFoamLace → (非领头浪才 drawCrestLip) */
private fun drawSwellBands(t: Float)

/** 岸线的**平均**位置（不含冲流推进量）= 冲流线的起点 */
private fun swashFrontY(x: Float, t: Float, h: Float): Float

/** 泡沫振幅（音频只到这里为止）。⛔ 不参与任何时序。
 *  ⭐ L 现在是 lane（1..3）：e = (L == 0) ? sLow : 0.70*sLow + 0.30*sMid —— ⚠️ lane 恒 != 0，
 *     所以实际**永远**走 `0.70*sLow + 0.30*sMid` 那支；Kotlin 端口应显式按 isLead 分支，
 *     ⛔ 别把 lane==0 当成「领头浪」 */
private fun bandAmp(lane: Int): Float
```

⛔ **`waveFrontY` 与 `waveDepthK` 已删除**（§5.5）——⛔ 端口不得复活；破碎线一律走 `breakerFrontY`。

#### 14.3.5 烘焙层（resize 时各跑一次，⛔ 绝不在 `drawContent` 每帧路径）

```kotlin
private fun buildGeometry()          // 尺寸缓存 + 逐列场 + 调 buildGrain/Sand/Foam/Lace + resetWaves
private fun buildGrain()             // 128px 颗粒图案 + 16×16 晶格斑驳图案
private fun buildSandTexture()       // ⭐ 4 层烘焙（§4.3.7），>320 万像素按比例降采样
private fun buildFoamTiles()         // 6 张 256²：软边斑块 + 撕碎丝缕 + 边缘 alpha 渐隐
private fun buildLaceNet()           // ⭐ 蕾丝网拓扑：10×4 抖动节点 + 沿岸长丝 + 环绕包边 + 竖筋/斜筋/网眼填充
private fun buildCausticNet()        // ⭐ 焦散胞壁网拓扑：CNX×CNY 抖动节点 + 每格连右/下邻居
                                    //   + 低频 warp/shear 去规整化（⛔ 幅度 ≤ ±0.3 格、⛔ 不周期环绕）
```

#### 14.3.6 沙与覆层

```kotlin
/** 干沙主体：上边界就是岸线（⛔ 不留硬缝），裁剪后 blit 烘好的纹理 */
private fun drawSand(t: Float): Path

/** 湿沙：逐列自己的渐变（从最深走到全透明、终点落在 wetEdge[i]）。
 *  ⛔ x 用浮点、不取整也不 +1px；顶端抬到水线之上 WET_OVER 让 clip 决定上沿 */
private fun drawWetWash()

/** 镜面高光（lighter 加色，越干越弱） */
private fun drawSheen()

/** 洼地小水洼 + 压扁椭圆反光（⛔ 不用 fillRect 亮线） */
private fun drawPuddles()

/** 退水残沫：3 pass 羽状丝缕，⛔ 必须短（太长就成了「等高线」） */
private fun drawResidualStreaks(t: Float)

/** 岸线细亮湿线（退水期最亮） */
private fun drawWetLine(t: Float)

/** 浪花手指：位置 hash 抖动 ±0.85 格，只有约 45% 真的伸出去 */
private fun drawSwashFingers(t: Float)
```

#### 14.3.7 水体、后处理与主循环

```kotlin
/** 水体场：三档噪声相位 + fBm 扭曲的 rowPhase + 行/列级粗糙度场
 *  → 低分辨率 fieldCvs → 每帧一次「裁剪 + blit」到 H·SEA_BOTTOM_K */
private fun drawSeaField(t: Float, energy: Float)

/** 粼光网：30 根两族交叉斜向射线 + ⭐**连通胞壁网**（二次曲线、3 档 alpha 批量
 *  stroke ⇒ 只 3 次 stroke）+ 56 颗闪烁亮结；⛔ 只在海水裁剪区内。
 *  ⛔ 绝不可退回「孤立短横划」——没有胞形的短线一律读作划痕 */
private fun drawCausticNet(t: Float, energy: Float)

private fun drawSplash(t: Float)      // 飞沫：确定性点表，高频驱动点数/alpha
private fun drawRipples(t: Float, clip: Path)   // 沙纹：26 条断段浅色调
private fun drawResidue(clip: Path)    // 残沫：软阴影 → 亮芯 → 左上缘高光
private fun drawPost()                 // 斑驳 + 颗粒 + 晕影
```

**主循环的固定顺序**（= 「从外海走向内陆」的水/沙剖面，⛔ 不可换）：

```
computeShore → drawSeaField → drawCausticNet → drawSand
  → drawSwellBands → drawSwashFingers → drawResidualStreaks → drawWetLine
  → drawSplash → drawRipples → drawResidue → drawPost
```

#### 14.3.8 ⭐ 浪的强度序（foam ordering）—— 单一方向，无歧义

**近岸最强、向外海单调衰减。** 三条**同向**的机制，不存在任何一条反向：

| 机制 | 表达式 | 方向 |
|---|---|---|
| 浪自身强度 `layerAlpha(adv, energy)`（⭐ **只领头浪**） | `strength = 0.32 + 0.68·clamp(adv, 0, 1)` | `adv` 0 = 最外海，1 = 到滩上 ⇒ **越靠岸越强** |
| 泡沫**距离律** `farLaw[i]` | `(clamp(1 − (SHORE_K·h − bfy[i]) / (FOAM_FAR_SPAN·h), 0, 1)) ^ P`，`P = 1.6`（领头，实际不生效）/ `0.35`（非领头） | 水线处 `= 1`，向外海递减 ⇒ **越靠岸越白** |
| 泡沫**倾角律** `slopeLaw[i]` | `clamp(\|Δy/Δx 每像素\| · 0.62 · 4, 0, 1)` | 浪脊陡（朝岸倾斜 ⇒ 破碎）处才有泡沫；平缓处几乎无 |

**唯一的三处例外，都是「加强近岸」或「让远浪仍可见」而非反转**：

- **领头浪**（在滩上消散的那条）：`slopeLaw = 1`、`farLaw = 1`（水线处永远满泡沫，反馈七·3）+ `SHORE_FOAM_BOOST = 1.55`。
- ⭐ **非领头浪的 `kA` 是位置指示器，不是音强表达**：`kA = clamp(amp·0.55·fade, 0, 1)` —— ⛔ 不吃 `layerAlpha`（⇒ 不吃音频能量门控）、⛔ 不吃近岸斜坡。它的「远」改由**带宽**（`slK`）与**逐块 alpha**（`foamK`）表达。owner：「第二条浪不应该抑制浪花啊，应该一直保持浪花，我才能知道第二条浪走到哪里了」。
- **非泡沫浪本体** `drawSwellBody` 的距离衰减是 `1 / (1 + 3.2·(1 − farLaw))`（≈ pow 0.55，远比泡沫的 pow 1.6 平缓）⇒ **远处的浪仍有形状，只是没有白**。这正是 `foamK` 要造出的梯度，不是「越远越强」。

⛔ **带宽只用倾角律**（`slK = 0.30 + 0.70·slopeLaw`），⛔ **绝不用距离律折带宽**——两者都折时，破碎线出生处（`−0.04h`）`farLaw ≈ 0` ⇒ 带宽被乘到 0 ⇒ 整片海上只剩一条极淡的线。反馈要的是「泡沫看不见」，不是「浪看不见」。
⭐⭐ **且带宽有硬下限**：`shrink = max(0.30, frayK·envK·slK)`（§4.3.9 ②）。下限必须加在**乘积**上——`envK = 0.02 + 0.98·pow(fbmNorm, 2.4)` 是三项里最容易趋零的一项（无下限时实测塌陷率 48.5%）。

#### 14.3.9 渲染器外壳（`RendererFx` 契约不变）

```kotlin
class SeasideRenderer : RendererFx() {
    // ⛔ 必须是这个形状：无类型标注、无 get()、字面量（FxCoverageScanTest 正则要求）
    override val postFx = PostFx(vignette = 0.30f, grain = 0.020f)

    override fun onEnterContent(ctx: RenderContext) {
        // ⛔ 不在此按尺寸烘焙：ctx.canvasSize 此刻仍是 Size.Zero（VisualizerStage:192）
        // ⛔ 不复位时间原点：画质切换会重入 onEnter，复位会让浪的行程/水线突然跳变
    }

    override fun onExitContent() {
        // 回收自持位图（沙纹理 / 泡沫贴图 / 湿沙小条），try/catch，⛔ 不调
        // ProceduralTexture.release() / OverlayFx.release()（归舞台与基类）
    }

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        val w = size.width; val h = size.height
        if (w < 2f || h < 2f) return
        if (w != seaW || h != seaH) { releaseBitmaps(); rebuildGeometry(w, h) }  // 重活只在尺寸变化时
        // 时间原点惰性捕获自 fx.nowMs，⛔ 绝不用 ctx.nowMs（墙钟 vs 单调，时基不同源，§3.1）
        // 时间轴只吃 fx.dt：stepWaves / finishWaves / waterlineAdvance / 干燥衰减
        // 音频只改外观：涌高 / bandAmp / layerAlpha / 水色 / 粼光 / 飞沫 / 沙纹 / 残沫
    }
}
```

### 14.4 逐文件改造点（file:line）

- `AppSettings.kt:210`（`STAR_TRAILS`）之后追加枚举项。
- `VisualizerRendererFactory.kt` 的 `when` 加分支 + import。
- `VisualizerThemeTest.kt` 7 处计数 +1。
- `FxCoverageScanTest.kt` `covered` + 类数下限 +1。
- `SeasideRenderer.kt` 全文见 §14.3。

### 14.5 既有断言的同步项

即 §七 G1/G3 与 §八 F6/F7——已在对应小节列全。

### 14.6 实施顺序

严格按 §12.3 T1→T6（先注册与计数，再渲染器，再测试，本机验证，**真机验收与调参**，最后文档）。⛔ S1/T1.3 与 T2.1 必须同提交（工厂分支引用未存在的类会编译不过）。

### 14.7 待修的陈旧注释清单

- `AppSettings.kt:100` 附近「29 套」→「30 套」（实施 F2）。
- ⛔ 与本案无关、**不要顺手改**：`CHANGELOG.md` 关于 600ms 交叉淡入的描述（实为 `false`，`VisualizerStage.kt:205`）——仅留痕。

---

## 十五、提交顺序（每步可独立验证）

| 步 | 内容 | 验证 |
|---|---|---|
| S1 | F1+F2+F3+F4（枚举/KDoc/工厂/渲染器）+ F6+F7（计数与名单） | `assembleDebug` + `testDebugUnitTest` 全绿（⛔ 加 `--rerun`） |
| S2 | F5 测试全量（G1–G10） | `--tests "*SeasideTest"` + 全量单测 |
| S3 | 真机 V4–V17 + 逐项调参并回写 §五 | 目视清单全勾，帧率达标 |
| S4 | F8/F9/F10 文档 | 死链检查 + 文风检查 |
| S5 | 合入 | CI 三 job 全绿 |

> S1 把「注册 + 渲染器 + 计数」放在一起，是因为工厂分支的引用完整性要求它们同提交；其余步可独立提交。
