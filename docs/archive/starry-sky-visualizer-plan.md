# 星空星轨 频谱效果 开发方案（Star Trails）

> **状态**：**已实施**（2026-10-02）。F1–F7（枚举 / 工厂 / 渲染器 / 门禁 / 7 处计数 / 名单）+ F8–F10（文档）完成，本机 V1–V3 通过（`assembleDebug` + **0 失败** + `lintDebug` **0 Error**）；⛔ **V4–V12 真机验收未做**。**实施期共 14 处偏差登记在 §12.4**，**架构更换（真机四缺陷）登记为 §12.4 ⑮**。
>
> **基线**：`HEAD = 40ca823`（v2.38.0，versionCode 168，2026-10-01）。效果总数 **28 套**（`AppSettings.kt` 枚举 28 项）⇒ 实施后 **29 套**；门禁断言 `VisualizerThemeTest` 7 处硬计数「28/27」、`FxCoverageScanTest` 1 处「28」——均已按 `40ca823` 复核未变并**同步 +1**。`technical-overview.md` 最新小节为 **§10.205**（`:11341`）⇒ 本案新增 **§10.206**。**⛔ 本方案不引用任何未实测的单测总数 / lint 警告数**——计数会漂移，实施前用 `./gradlew.bat testDebugUnitTest --no-daemon -Pkotlin.compiler.execution.strategy=in-process` 复测后回填。
>
> ⛔ **枚举签名已变（`40ca823` 引入）**：`VisualizerTheme` 新增第 4 参 `needsParticleBudget: Boolean = false`（`AppSettings.kt:112-117`），`supports()` 的 ADV 分支改为 `!theme.needsParticleBudget || maxParticles > 0`（`:276`）。⇒ **§十一裁决 1 与 §4.7 降级矩阵已按此重写**，实施前必读。
>
> ⚠️ **§14.3 是签名骨架，不是可编译全文**（v1.3 起明示）；但其中**显式给出的表达式均为定稿**，可直接照抄。
>
> **红线摘要**：纯 `DrawScope` / `nativeCanvas` 渲染，**禁止** shader / AGSL `RuntimeShader` / `RenderEffect` / OpenGL / `BitmapShader`（`docs/archive/visualizer-texture-upgrade-plan.md:8-10,994,3276` 四处明文）。`draw()` 内零分配；每帧增量必须 `× fx.dt`；**不**新增 `ProceduralTexture.Id`。
>
> ⛔ **v1.5：星轨分三层 —— 底环（圆）+ spray 喷溅弧场（密集短弧）+ 英雄亮线段（音频反应）**。底环 `RING_ALPHA` 已降到 `0.10`（几乎隐去）；数百条静态短弧由 `(bandIndex,k)` 的确定性哈希烘焙进 `SPRAY_BUCKETS` 条 `Path`，每帧只在**一个**旋转变换下画 4 次 `drawPath`（⛔ 逐帧逐弧绘制会砍掉一半帧率，见 §4.3）。真机四缺陷（过粗 / 中心死白 / 虚线 / 锯齿）全部源于「降采样双缓冲 + `DST_OUT` 逐帧衰减 + 每帧加性叠加」。现改为**底环（圆）+ 亮线段（线上逐段描出、头亮尾沉回底环）**：⛔ **不得**重新引入 ping-pong 缓冲、⛔ 弧线一律直画在原生分辨率画布上（§4.3 有完整描述，§9 R1 因此基本退役）。

### 文档版本跟踪

| 文档版本 | 日期 | 变更摘要 | 状态 |
|---|---|---|---|
| v1.5 | 2026-10-02 | ⭐ **真机反馈驱动的「分布层」改造**：v1.4 真机确认**亮线本身没问题**（细、不炸、连续、无锯齿，创维 Android 5.1.1 实测 **29.7fps**），但用户判**「星轨分布太均匀」「轨道线条可以不明显，但画出来的亮弧要更多」**。⇒ 三处改动：① **底环几乎隐去**（`RING_ALPHA` `0.26 → 0.10`，只留一丝暗示极坐标结构）；② ⭐ **新增 spray 喷溅弧场** —— 每带 `SPRAY_PER_BAND` 条（FULL 6 / LITE 5 / OFF 3 ⇒ 64×6 = **384** 条）**静态短弧**，半径按带内哈希抖动 ±`SPRAY_RADIUS_JITTER`(0.45)×最近邻间距（**打散同心圆的规整感**）、**28% 被 `SPRAY_CUTOFF` 剔除形成空档**、相位逐弧独立、扫掠 4°–26° 参差、4 个颜色桶（冷蓝/淡白/暖白/柔品红）；③ **英雄亮线段 `SEG_K` 4 → 3**，把省下的 64 次 `drawArc` 换成 spray 的 4 次 `drawPath`（斜坡 `SEG_GAMMA` 与线宽 `SEG_W_*` 完全不动 ⇒ 已认可的观感零回归）。⛔ **spray 必须烘焙**成 `SPRAY_BUCKETS` 条预分配 `Path`，每帧只在**一个** `withTransform` 旋转下画 4 次 `drawPath` —— 逐帧逐弧绘制会把 29.7fps 砍半。门禁：新增 spray 参数族数值段（确定性/半径夹紧/带间不交叉/活跃率 0.60–0.85/桶全覆盖/调色板互异）+「⑧ spray 必须烘焙」源码段（含 `noLiveSprayBake` 负向自证）；`⑧` 零分配判据改为**逐行豁免** `// Perf-exempt`（与 `PerfBudgetContractTest.isExemptLine` 同语义，因 `Path.addArc` 需不可变 `Rect(...)`，仅存在于烘焙期）。§4.1/§4.3/§5.1/§5.4/§6/§7/§4.7/§十四 同步改写。⛔ **真机验收仍未完成**（v1.5 架构只在本机跑过编译/lint/单测）。 | 已实施（v1.5 分布层 + 文档同步完成；⛔ V4–V12 真机验收仍未做） || v1.4 | 2026-10-02 | ⭐ **真机反馈驱动的架构更换**：真机截图暴露四个缺陷 —— ① 拖尾**过粗**；② 中心**烧成死白**；③ 拖尾读成**同心虚线**；④ 拖尾**锯齿**。四者同源于 v1.3 的「ping-pong 累积缓冲」架构（`DST_OUT` 衰减 + 960 宽降采样双缓冲 + 每帧 2 次全屏回绘），而非参数取值。⇒ **整套缓冲机械删除**，换成用户口述的形态「**用细线画圆，然后线上有一段一段的描出来亮线，前面亮后面逐渐与原来的线一样了**」：**Pass A** 烘焙 64 圈**整圈细线**底环（`RING_W`/`RING_ALPHA`，**原生尺寸**位图，每帧 1 次 blit）+ **Pass B** 逐柱画 `SEG_K` 段首尾相接的**亮线段**（alpha 自尾向头按 `SEG_GAMMA` 爬升）。连带：§4.1 流水线步 4–7 改写；§4.3 由「ping-pong + DST_OUT」整节重写为「底环 + 亮线段」；§5.1 `TRAIL_TAU_S` 出、`R_INNER_K` 0.055→**0.10**（中心留暗核）；§5.4 `LINE_MIN_W/LINE_W_GAIN` → `SEG_W_MIN/SEG_W_GAIN`（1.0+0.8·amp，满幅仅 **1.8 dp**）并新增 `RING_W/RING_ALPHA/SEG_K/SEG_GAMMA`；缓冲三档 `TRAIL_W_*` 删除；§十四 14.3 签名同步；§九 **R1 基本退役**（暴露面由「每帧 2 趟全屏缓冲」降为「**无缓冲**」）；§七 G6/G7 判据同步；门禁新增「累积缓冲机械已删除」+「弧线不得进降采样缓冲」两条源码段，删除 `decayAlphaFor`/`TRAIL_TAU_S` 断言。**⛔ 真机验收仍未完成**（本轮同样只在本机跑「编译 + lint + 单测」）。 | 已实施（v1.4 架构 + 文档同步完成；⛔ V4–V12 真机验收仍未做） |
| v1.3 | 2026-10-02 | **实施后回写：把 §14.3「可粘贴代码」里 4 处编译不过 / 门禁判否的写法改正，并把实现期偏差全部登记**。①② **`postFx` 形态错误**（§3.3 / §5.4 / §14.3 三处）：原写 `internal override val postFx: PostFx get() = …`，类型标注的 `:` 使 `FxCoverageScanTest.postFxRe` 整条**不匹配** ⇒ **静默**判「未覆盖」；改为无类型标注的初始化器形式（与 `MilkdropRenderer` 同形），三处加 ⛔ 警示 + 负向自证入 `StarrySkyTest` ⑧。③ **`Path.addArc` 签名错误**（§4.6.1 / §14.3）：`Path` 无 4 参 `addArc`（4 参的是 `arcTo`），且第 2/3 参是**扫掠角**不是终止角；改为 3 参 + `sweepRad = mLen[i] * mDir[i]`（符号即方向）。④ **补 `GLOW_PULSE_K = 0.10f`** 进 §5.4 与 §14.3（背 §4.6 的 `frame.pulse` 静音呼吸行，原表漏项）。另把 §14.3 的重复 `val glow`、`t0Ms = ctx.nowMs`（**会真冻结天极角**，见 §12.4 ④）、`drawSilhouette`→`drawGroundForeground`、缓冲缩放 pivot、`ridgeY` 纯函数化等一并改成与生产实现逐字同形，并在该节顶部声明「它是签名骨架、不是可编译全文」。**§12.4 偏差记录由「（空）」填满 14 行**（含两条会真出 bug 的：④ 时钟不同源、⑨ `Canvas.scale` 轴心）。**§12.3 勾选状态回写**（T1–T4 / T6 完成；⛔ T5.1 / T5.2 真机验收**未做**，§九 R1 仍是未量化风险）。**§14.7 / S9 节号由 §10.201 更正为 §10.206**。 | 已实施（F1–F7 + F8–F10 文档完成；⛔ V4–V12 真机验收未做） |
| v1.2 | 2026-10-01 | **实施前基线复核**：基线由 `0482399` 前移到 **`40ca823`**（v2.38.0，5 个提交）。核实 3 处漂移并就地修正：① ⭐ **`VisualizerTheme` 新增第 4 参 `needsParticleBudget: Boolean = false`**（`AppSettings.kt:112-117`），`supports()` 的 ADV 分支改为 `!theme.needsParticleBudget \|\| maxParticles > 0`（`:276`，2026-10-01 用户裁决）⇒ **§十一裁决 1 重写**：本效果不读 `maxParticles` ⇒ 标 `needsParticleBudget = false` ⇒ `supports()` 恒 true、**三档画质全部可选**，v1.0 的「LOW 档不可见」顾虑消失（§3.4）；② ⭐ **§4.7 降级矩阵 LOW 行由「❌ 不可达」改为「✅ 真实运行路径」**，`TRAIL_W_OFF=640` 与半柱分支（⛔ 恒读 `spectrum.size`）成为必须正确实现的真实路径；③ **`technical-overview` 最新小节由 §10.200 前移到 §10.205**（`:11341`）⇒ F9 新增 §10.206；枚举插入点由 `:174` 后移至 `:185`（`WORLD` 带 `needsParticleBudget = true`）。**复核未变**：效果总数仍 28、`VisualizerThemeTest` 仍 7 处硬计数「28/27」、`FxCoverageScanTest` 仍 `decls.size >= 28`（`:257`）——§七 G1 断言原样有效。 | 已被 v1.3 取代（实施前基线复核部分全部有效） |
| v1.1 | 2026-10-01 | **按浏览器原型 `docs/starry-sky-preview.html` 实测定稿回写**，全文档升至可开发层次。原型经 4 轮视觉迭代（初稿 → 修鱼刺/针叶树/成人像 → 换枯树/小孩 → 删小孩/降地面/移树），本版同步 8 处实质偏离：① **地面由底部 1/3（`0.66h`）降到 1/4（`0.75h`）**（§4.5.1/§5.3）；② **树改为枯树**（无叶、5 主枝、描边+分叉，原「针叶树」规格作废）（§4.5.2）；③ **人物剪影整体删除**（成人像与小孩两版均判失败，参数表不留人物常量）（§4.5.2）；④ ⭐ **流星必须画圆弧而非直线弦**——原型抓到的「鱼刺」根因，星轨本身一直是真 `ctx.arc`，写为硬规则 + 可粘贴代码（§4.6.1）；⑤ **柱方位角由黄金角改为种子伪随机**（黄金角在 64 等距柱上有可察觉规则性），用继承的 `rng` 抽一次（裁决 5）（§4.2）；⑥ **天极加宽高比自适应 + 窄画幅外半径钳制**（裁决 7）（§5.2）；⑦ **`radiusForBar` 取消 `scaleK` 参数**（缩放会连带改变半径比例、竖屏挤扁，改为缓冲等比 + 钳制）（裁决 6）（§4.2）；⑧ **幅值包络不复用 `AudioSmoother`**（其 attack/release 语义与本效果要的 `0.025s/0.24s` 相反且不等，改为自带时间常数）（裁决 4），并新增 `COLOR_SHAPE=0.55`（色指数≠半径指数）。另新增：§4.5.1 `ridgeY(x)` 的「控制点 x 取 1/3、2/3 ⇒ `x(t)` 线性 ⇒ 无需求二次根」设计与 `RIDGE` 控制点表（§5.3）、§5 参数表按原型重排为 5.1–5.4 四段并标 ⭐、§十四 14.3 可粘贴签名全面重写（新增 `poleCenterFor`/`outerRadiusFor`/`treeXFor`/`ridgeY`/包络）、单测门禁 G2/G3 增补对应断言、新增门禁 **G9 `ridgeY` 与路径同源** / **G10 枯树/流星源码约束**、§十二任务 T2.4 拆细。**参数以本版为准，v1.0 的视觉参数已作废。** | 待评审 |
| v1.0 | 2026-10-01 | 初稿。基于参考图（长曝光星轨）完成美术方向拆解 + 全链路现状核实（`RenderContext`/`AudioFrame`/`RendererFx` 真实契约、28 套效果注册链 9 处、单测门禁 4 类硬断言、API 22 设备硬约束与既有无 shader 红线）。产出 15 章完整可开发方案。核心决策：① 效果编号 **E42**、`STAR_TRAILS("星空星轨", Tier.ADV, "42")`；② **不**门控 `allowFramebuffer`（否则默认 MEDIUM 无拖尾，效果失去本体，见 §3.4/§十一裁决 1）；③ 拖尾用「等角扫掠 + 半径差异」自动还原参考图「近极短而密、外圈长而稀」，不加密度函数（§4.2）；④ 修正 `AppSettings.kt:100` KDoc「27 套」陈旧注释（实际 28，新增后为 29，§八 F2）；⑤ 天空色调映射用「双缓存 Brush 交叉淡入」实现零分配变色（§4.4）。含 8 个单测门禁 G1–G8、8 条风险红线 R1–R8（R1 为 API 22 真机填充率实测缺口）、9 步提交顺序 S1–S9、12 条真机验收 V1–V12、3 条裁决、可勾选任务清单，以及 §十四 可粘贴接口签名与逐文件改造点。 | 已被 v1.1 取代 |

> ⛔ 本文件每次修订必须在此表追加一行；文档版本号只增不改。

---

## 一、目标与核心结论

### 1.1 一句话目标

新增一套 **`STAR_TRAILS`「星空星轨」** 频谱效果：长曝光星轨照片的视觉母题（深蓝天幕 + 绕天极的细线圆 + 线上逐段描出的亮线 + 近黑地景剪影），把**频率**映射到**环绕天极的同心圆半径**、把**幅值**映射到**亮线段的扫掠角与线宽**、把**鼓点**映射到**极点闪光与切向流星**、把**整体响度**映射到**天色向亮蓝紫偏移**。静音时退化为缓慢自转的静态星场，绝不全黑。

### 1.2 视觉母题与参考图拆解

参考图（长曝光星轨）确定性视觉要素与参数化落地：

| # | 参考图要素 | 落地实现 |
|---|---|---|
| A | 绕**北天极**的同心圆弧星轨，**近极短而密、外圈长而稀** | 等角扫掠 + 半径差异自动还原（§4.2）；`radiusForBar()` 用 `t^0.72` 向天极聚密 |
| B | **天极偏离画面中心**（约 70% 宽 / 52% 高） | `poleCenterFor()`（§4.2），超宽屏内收、竖屏居中 |
| C | 天顶深靛 → 中段蓝 → 近地亮蓝紫的**多层垂直渐变** + 天极处一团径向辉光 | 5 段 `Brush.verticalGradient`（缓存）+ 天极 `RadialGradient` 辉光（缓存，§4.4） |
| D | 星轨**亮度内高外低**、色温**内暖外冷**（核心白炽、外圈淡蓝） | `segColorArgb(t, α)` / `ringColorArgb(t)` 按半径比 `lerp`「暖白 → 冷蓝」（§4.5） |
| E | 近黑**地景剪影**（起伏山脊 + 一棵**枯树**）+ 地平线上一层辉光 | 不透明 Path 画在星轨**之上**，星轨被地平线自然截止；地面占**底部 1/4**（§4.5.1/§4.5.2） |
| F | 微弱**静止星点**（非拖尾）铺满天空 | 直接复用 `ProceduralTexture.STARFIELD`（§3.2） |
| G | 长曝光**拖尾** = 时间累积 | ⭐ **v1.4 换机制**：不再靠时间累积，改为「细线圆 + 线上逐段描出的亮线」（§4.3） |
| H | 长曝光**固定曝光窗内缓慢旋转** | 天极自转 `ROT_DEG_PER_S=3.2°/s`，从单一时钟 `fx.nowMs` 推导（§4.2） |

### 1.3 九条核心结论（TL;DR 决策表）

| 决策 | 结论 | 依据 |
|---|---|---|
| 效果编号 | **E42** | 枚举末位 `WORLD` 后追加，`ordinalLabel="42"`（§2.1） |
| 枚举名 / 显示名 | `STAR_TRAILS` / 「星空星轨」 | §2.1 |
| 档位 | **`Tier.ADV`** | 双缓冲像素回绘同 `PHOTO_WALL` 风险档（`AppSettings.kt:144` 先例），LOW 老设备不提供 |
| 是否门控 `allowFramebuffer` | **否** | 见 §3.4 —— 否则默认 MEDIUM 无星轨、效果失去本体 |
| 渲染技术 | 纯 `DrawScope` + **底环 + 亮线段**（v1.4；⛔ 无 ping-pong 缓冲） | 无 shader 红线（§2.6） |
| 辉光实现 | `BlurMaskFilter`（软件层）+ 预烘焙径向渐变精灵，**不用** `RenderEffect` | §2.6 / §4.5 |
| 星轨先例 | ⭐ **v1.4 无先例**：E18 的 ping-pong 已弃用（真机四缺陷），本效果自绘环 + 段 | §3.1 / §12.4 ⑮ |
| 复用 `STARFIELD` 纹理 | 是，规则第 1 条「不得新增 `ProceduralTexture.Id`」 | §3.2 |
| 后处理 | `postFx = PostFx(vignette = 0.42f, grain = 0.026f)`，**必须字面量** | §3.3 / §七 G4 |

### 1.4 与既有三套星空类效果的差异化

已有三套效果带星空底或星点，本效果**必须**在视觉上可区分，否则列表里撞脸：

| 效果 | id | 星空元素 | 与本效果的本质区别 |
|---|---|---|---|
| 星座 | E17 `CONSTELLATION` | 星点 + 星座连线网格（O(n²) 连线） | 星座是**连线**，无星轨、无地景、无自转 |
| 轨道 | E29 `ORBITAL_RINGS` | `drawStars` 星点 + 轨道环 | 轨道是**环**，星点无关；无星轨、无天极、无地景 |
| DNA 双螺旋 | E40 `DNA` | 「深空星野底（同轨道风格）」 | 星空仅是衬底，主体是缎带 |
| **星空星轨** | E42 `STAR_TRAILS` | **地平线地景 + 天极自转 + 细线圆上逐段描出的亮线 + 极点辉光** | 唯一有 **地景剪影 + 天极旋转 + 线上亮线段** 的「照片化夜景」；静音态也不一样（缓慢自转星场 vs 静态网格） |

---

## 二、现状盘点（含 file:line）

### 2.1 效果注册链路（全链路 9 处，新增一套必须逐处同步）

| # | 位置 | 现状 | 新增要动什么 |
|---|---|---|---|
| 1 | `data/model/AppSettings.kt:104-175` | `enum class VisualizerTheme` 28 项，末位 `WORLD("世界", Tier.ADV, "41")` at `:174`，`;` at `:175` | 追加 `STAR_TRAILS("星空星轨", Tier.ADV, "42")` |
| 2 | `data/model/AppSettings.kt:100` | KDoc「可视化效果主题（**27 套**手动效果…）」——**本身就是陈旧注释**（实际 28） | 顺手改「29 套」 |
| 3 | `visualizer/VisualizerRendererFactory.kt:51-80` | `when(theme)` 28 分支 | 加 import + `STAR_TRAILS -> StarrySkyRenderer()` |
| 4 | `VisualizerRendererFactory.kt:88-89` | `availableThemes()` | 无改动（走 `selectable` + `supports`） |
| 5 | `data/prefs/AppPreferences.kt:317` | `keyVisualizerTheme` | 无改动（存 enum `name` 字符串） |
| 6 | 测试 `VisualizerThemeTest.kt:51,57,58,90,96,103`（6×`28`）+ `:89`(1×`27`) | 7 处硬计数 | 全部 +1（§七 G1） |
| 7 | 测试 `FxCoverageScanTest.kt:41-63` `covered` + `:257` `decls.size >= 28` | 硬编码名单 + 计数 | 名单加类名 + `28→29`（§七 G3） |
| 8 | `docs/visualizer-effects-list.md:7` | 计数行 + 三档表 | ✅ +1 + ADV 表加 ` E42 | STAR_TRAILS | 星空星轨 |` 行（F8） |
| 9 | `docs/technical-overview.md` | 最新 §10.205（`:11341`） | ✅ 新增 **§10.206**（F9） |

> 主题切换**没有设置页入口**——只经舞台点指示器 / ←→ 键（`VisualizerViewModel.kt:460-478 step()`）切换，故新增枚举无需改任何设置 UI。`app/src/main/java/com/nasmusic/tv/ui/screens/settings/PlayerSettingsSection.kt:187-208` 只有「画质三档」，无主题列表。

### 2.2 渲染契约的真实形状（纠正 5 个极易写错的命名）

> 以下是**真实**接口名，不是常见臆测。写错任何一个名字 = 编译不过或门的 `RendererBaseContractTest` 判失败。

| ❌ 常见臆测 | ✅ 真实 | 位置 |
|---|---|---|
| `RendererBase` / `RendererContext` | `abstract class RendererFx : VisualizerRenderer` / `RenderContext` | `renderers/RendererFx.kt:33` / `RenderContext.kt:15` |
| `Frame` / `RenderFrame` | 复用单例 `AudioFrame`；渲染器自身句柄是 `FxFrame` | `AudioFrame.kt:16` / `RendererFx.kt:177` |
| `onResize` | 无；尺寸每帧经 `DrawScope.size` + `ctx.canvasSize`，渲染器自行 `SizeCache` 缓存 | `RendererFx.kt:198-210` |
| `onRelease` | `onExit()` | `VisualizerRenderer.kt:44` 附近 |
| `onBeat` 回调 | 无；拍点是 `frame.beat / pulse / bassRaw` **数据字段** | `AudioFrame.kt:44-52` |

**唯一抽象方法**（`RendererFx.kt:45-49`，子类只实现它）：

```kotlin
protected abstract fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame)
```

可选钩子：`onEnterContent(ctx)`（`RendererFx.kt:77`）、`onExitContent()`（`:80`）。三个 `final` 模板方法 `draw/onEnter/onExit`（`:53,66,71`）**不得覆盖**（`RendererBaseContractTest` 判定）。`FxFrame` 成员：`fx.dt`（`RendererFx.kt:178`，钳 `[0,0.1]`）、`fx.nowMs`（`:180`）、`fx.seq`（`:182`）、`fx.level`（`:186`，`FxLevel.OFF/LITE/FULL`）。

### 2.3 音频数据契约与双通道法则

`SpectrumContract.kt:9-44`：`BAR_COUNT=64`、`WAVE_POINTS=128`、`BASS_END=39`、`MID_END=55`、`TREBLE_END=63`、`DISPLAY_GAMMA=0.75`、`AGC_DECAY=0.995`、`AGC_FLOOR=0.05`。⛔ **柱数永远读 `frame.spectrum.size`，不得硬编码 64**（`SpectrumContract.kt:5-7`）。

`AudioFrame`（`AudioFrame.kt:16-88`）关键字段：

```kotlin
val spectrum: FloatArray   // 64, 0..1, 已 GAMMA 压缩（显示通道）
val waveform: FloatArray   // 128, -1..1
var bass / mid / treble    // 线性、峰值归一（动态通道）
var energy                 // 线性
var sectionEnergy          // 8s 滑动均
var bassRaw                // ⚠️ 未归一低频，供鼓点强度对比
var beat: Boolean          // 仅持续一帧
var pulse: Float           // 0..1 快攻慢放包络
var bpm: Float             // 0 = 尚不稳定
var timeMs: Long; var seq: Long
```

⛔ **双通道法则**：`spectrum` 是伽马压缩后的**显示**通道，`bass/mid/treble/energy` 是**线性**动态通道。两套混用是历史 BUG ⑨-b（`AudioFrame.kt:11-15`）。本效果：**半径从 `spectrum[bar]` 取（显示），鼓点从 `bassRaw` + `frame.beat` 取（动态），天色从 `sectionEnergy` 取（动态）**——三条各归其位，不交叉。

### 2.4 帧驱动与舞台的硬约束

- 帧驱动：`ui/components/VisualizerStage.kt:231-249` `withFrameNanos` 循环，**无重组**；`tick` 在 Canvas 块内读出强制逐帧重绘（`:361`）。
- 画布分支 `:329-374`：外层 `graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }`（`:337-340`，**无条件**，`BlendMode.Plus/Overlay` 依赖它，`fx/OverlayFx.kt:26-28`）。`frame()` 作为 provider 传入（`VisualizerStage.kt:104-105` 抗撕裂），每帧重取后 `renderCtx.update(...)`（`:348-352`），再 `try/catch { draw(f, renderCtx) }`（`:364-372`）。
- 分辨率：尺寸每帧到位，无需 `RenderContext` 改动；`ProceduralTexture.ensure(w.coerceIn(1,4096), …)` 是既有范式（`BatchFourRenderers.kt:1633-1635`）。
- 释放：`DisposableEffect(Unit)` → `swapper.release()`（`:220-227`）；`RendererSwapper.sync` 在**画质切换时会重新进入 `onEnter`**（`RendererSwapper.kt:79-117`）⇒ 本效果 `onEnterContent` 必须**先释放旧位图再重建**（E18 的同款教训，`UltraRenderers.kt:108-125`）。v1.4 起「旧位图」= **底环缓存位图**（单张），不再是双缓冲。

### 2.5 可复用的既有能力清单

| 能力 | 位置 | 本效果用法 |
|---|---|---|
| `ProceduralTexture.STARFIELD` | `fx/ProceduralTexture.kt:31,241-261` | 静止星点衬底（`starLayout` 给 `[x,y,tier]`，3 档半径/亮度） |
| `OverlayFx.drawVignette/drawGrain` | `fx/OverlayFx.kt:49,87` | 经 `postFx` 声明式启用（§3.3） |
| `AudioSmoother` | `fx/AudioSmoother.kt:12-27` | 幅值/亮度快攻慢放（参数不得重造，§3.5） |
| `VisualizerMath.lerp / polar / rad` | `VisualizerMath.kt`（`lerp` :130 附近, `polar` :120 附近） | 半径/颜色/弧角换算 |
| `SizeCache` | `RendererFx.kt:198-210` | 天空渐变 Brush、天极辉光 Brush 按 (w,h) 缓存 |
| `VisualizerRandom`（继承的 `protected rng`） | `RendererFx.kt:85` | 星点相位/流星相位抖动 |
| E18 ping-pong 模式 | `UltraRenderers.kt:83-236` | ⭐ **v1.4 已弃用**（真机四缺陷）；只借鉴其「显式 recycle + 尺寸变化先释放」的纪律（§3.1 / §4.3） |
| `FxLevel`（`fx.level`） | `fx/FxLevel.kt:13-29` | ⭐ v1.4：**只用于亮线段隔柱**（LOW）+ `OverlayFx` 分档；**不再**用于缓冲分辨率（缓冲已删） |

### 2.6 不可用的能力（⛔ 硬红线）

| 禁物 | 依据 | 替代 |
|---|---|---|
| AGSL `RuntimeShader` / `.glsl` / `.frag` / OpenGL / EGL | `docs/archive/visualizer-texture-upgrade-plan.md:8-10,994,3276,4681`（`RuntimeShader` 需 API 33+，本项目 minSdk 22） | `BlurMaskFilter` + 预烘焙辉光精灵 |
| `RenderEffect`（真高斯模糊） | 从未使用；污点近似已是惯例（`photo/transitions/ZoomP1Transitions.kt:23`、`docs/archive/photo-spectrum-effect-plan.md:3043`） | 软件层 `BlurMaskFilter` 或重复贴图淡入 |
| `BitmapShader`（整块） | 无一处使用；纹理走 `ProceduralTexture` | `ProceduralTexture` 或 `nativeCanvas` 的 `LinearGradient` |
| `BlendMode.Difference` | API 29+，`VisualizerStage.kt:416` 已显式绕行 | 用 `BlendMode.Plus`（底环 blit + 亮线段子弧，均加性） |
| 圆角 `clip` | API 22 三星 hwui 段错误（`VisualizerStage.kt:750-752`，真机 3 次复现） | `drawCircle` / `drawRoundRect` / `Path` 直接绘制 |
| 新增 `ProceduralTexture.Id` | 必须**末尾追加**（`ProceduralTexture.kt:29-30`），且本效果无需新增 | 复用 `STARFIELD` |

---

## 三、最容易搞错的「复用」与「调用点」

### 3.1 ⭐ 拖尾：E18 的 ping-pong 先例**已弃用**（v1.4）

`MilkdropRenderer`（`UltraRenderers.kt:76-236`）是全仓库**唯一**已有帧级像素回绘，它的 3-tap 缩放回绘（` :164-181`）是为「反馈拉伸」服务的，会把历史画面放大/旋转/位移，形成 K 粉酸 pattern。

v1.0–v1.3 的做法是「**搬它的结构**（双缓冲 + 预分配 Canvas + DST_OUT 衰减 + 换缓冲时连 Canvas 一起换）」做长曝光。**v1.4 起不再这么做** —— 真机截图证明这套结构在本效果里会同时产出四个缺陷（过粗 / 中心死白 / 虚线 / 锯齿，见 §12.4 ⑮），根因不是参数而是**「降采样缓冲 + 逐帧同角度加性叠加」这个架构本身**。

保留的可借鉴点只剩两条，且都不再涉及缓冲：

- **`recycle()` 必须显式在 `onExitContent`**（API 22–25 位图像素在 native 堆，`RendererFx.kt:73`）—— 现在用于**底环缓存位图**；
- **画布尺寸变化前先释放再建**（`RendererSwapper.sync` 重入 `onEnter`）—— 同一机制，用于 `releaseResources()`。

⛔ `DST_OUT` / `PorterDuffXfermode` / `prevCanvas`·`currCanvas` / `withScale(bufScale…)` **整套禁回**，`StarrySkyTest` 有专条源码门（`⑧ 累积缓冲机械必须已删除`）锁死。

### 3.2 ProceduralTexture.STARFIELD：直接复用，不得新增 Id

`ProceduralTexture.tile(Id.STARFIELD)` 返回全屏 `ImageBitmap?`，`drawImage(dstSize=IntSize(iw,ih))` 一次贴满。**调用前必须 `ensure()`**，且 `ensure()` **只能在 `onEnterContent` / 尺寸变化时调用，绝不在 `draw()` 里**（`ProceduralTexture.kt:62-64`）。`Id` 是 `enum` 且 `ordinal` 是槽位序号 → **新增要末尾追加**（` :29-30`），本效果用现成的 `STARFIELD`，不新增。未 `ensure` 时 `tile()` 返回 `null`，用 `?.let {}` 静默跳过（既有范式，`BatchFourRenderers.kt:1647-1649`）。

### 3.3 OverlayFx / postFx：字面量陷阱

`RendererFx` 子类要么覆盖 `postFx` 且**至少一个值 >0f**，要么在 `draw` 里显式 `with(OverlayFx){...}`，否则 `FxCoverageScanTest` 判「未覆盖」失败（`FxCoverageScanTest.kt:208-211`）。正则 `"""=\s*([0-9]*\.?[0-9]+)f"""` 只吃**数字字面量**——写 `PostFx(vignette = VIGNETTE_DEFAULT)`（命名常量）会被判定「未覆盖」（先例 `UltraRenderers.kt:64`）。所以：

```kotlin
override val postFx = PostFx(vignette = 0.42f, grain = 0.026f)
```

> ⛔ **v1.3 更正：⛔ 不得写类型标注，⛔ 不得写自定义 `get()`。**
> 本节此前给的是 `internal override val postFx: PostFx get() = PostFx(...)`，
> **那样写门禁直接判「未覆盖」**——`FxCoverageScanTest.kt:154` 的
> `postFxRe` 是 `override\s+val\s+postFx\s*=\s*PostFx\(([^)]*)\)`，
> `postFx` 后面紧跟的是 `:`（类型标注）而不是 `\s*=` ⇒ **整条正则不匹配**
> ⇒ `coveredByPostFx` 拿不到参数表 ⇒ `覆盖名单里的渲染器必须已覆盖后处理` 失败。
> 它是**静默**失效的（`coveredByPostFx` 只 `return false`，不报错），所以必须写成
> **无类型标注的初始化器形式**（类型由 initializer 推断，Kotlin 里 override 沿用基类
> `internal` 可见性），与 `UltraRenderers.kt:81`（`MilkdropRenderer`）逐字同形。
> 负面自证见 `StarrySkyTest` ⑧ `postFx 必须是数值字面量 正负双证`。

`vignette` 对夜景照天然契合（暗角），`grain` 卖「高 ISO 长曝光」噪点感，`scanline=0`。声明式、零额外代码，`draw()` 里不写它。

### 3.4 allowFramebuffer：门控 vs 降级开关（本方案最容易搞错的一条）

`VisualQuality.allowFramebuffer`（`AppSettings.kt:241,250`）=「是否允许**帧缓冲回绘**（MilkDrop 类效果需要）」。`VisualQuality.ULTRA -> allowFramebuffer`（` :263`）；而 `allowFramebuffer` **仅 HIGH=true**（` :252-254`，`MEDIUM/LOW=false`）；且 **`VisualQuality` 当前无任何 UI 入口**，`setQuality()/updateVisualizerQuality()` 全仓库零调用点（死代码，`docs/archive/visualizer-texture-upgrade-plan.md:67,288-289`，`PlayerSettingsSection.kt:182` 自述）⇒ **实际档位恒为 MEDIUM**。

⚠️ 若本效果照抄 E18 把 `allowFramebuffer` 当**门控**（要求 `==true` 才画星轨），则默认档位下**永远只有一圈底环**——亮线段完全不出现，效果退成一块星空衬底，失去本体。因此本效果**不门控 `allowFramebuffer`**，理由：

1. ⭐ **v1.4/v1.5**：本效果**已无任何帧缓冲回绘**（§4.3）—— 每帧只有 1 次 1:1 `drawImage` + `SPRAY_BUCKETS`(4) 次 `drawPath` + 至多 192 次细弧 `drawArc` + 若干路径，成本量级**远低于** E18 的 3-tap 1280×720 回绘（E18 KDoc `UltraRenderers.kt:28-31` 自称「TV 填充率杀手」）。`allowFramebuffer` 这个门本来就管不到本效果。
2. 项目 §2.4 post-mortem 的核心结论就是「**年年看不到效果**」是最大失败（`docs/archive/visualizer-texture-upgrade-plan.md:67` 首行）。
3. ⭐ v1.4：原先「缓冲分辨率按 `fx.level` 三档降」这条兜底**已作废**（无缓冲）；替代兜底为 **LOW 档隔柱**（`LOW_ARC_STRIDE`）与 `SEG_K` 降档（§4.7 / §九 R1）。

结论定为 **`Tier.ADV` + `needsParticleBudget = false` + 不门控 `allowFramebuffer`**，并把「真机实测掉帧 → 降亮线段密度」写成预决兜底（§九 R1 / §十一裁决 1）。

> ⭐ **v1.2 按 `40ca823` 重写**：`supports()` 的 ADV 分支已改为 `!theme.needsParticleBudget || maxParticles > 0`（`AppSettings.kt:276`），门控只消费「该效果是否**真的读取** `ctx.quality.maxParticles`」这一条可核对的事实（同文件 `:104-110` KDoc）。本效果**一颗粒子都不画**（只画圆弧与路径，与 `maxParticles` 无关）⇒ 照实标注 `needsParticleBudget = false` ⇒ **`supports()` 恒 true，MEDIUM/HIGH/LOW 三档全部可选**，v1.0 里「LOW 档不可见」的顾虑**已消失**，且与 `数字雨 / 星座 / DNA / 照片墙` 等同一条规则自然成立（`:270-275` 的用户裁决原话）。这比 v1.0 的 BASIC/ADV 二选一更优，故保留 `Tier.ADV`（表达「同风险档」）而不再纠结档位。

### 3.5 rng / SizeCache / AudioSmoother 的复用红线

- **rng**：继承 `protected val rng = VisualizerRandom()`（`RendererFx.kt:85`），**不得自行声明 `private val rng`**（`RendererBaseContractTest:161-174` 判定，会判「重复 rng」）。
- **SizeCache**：`SizeCache.get(w,h){...}` 返回 `Any?`，以 **w/h 双键**缓存，任一变化即重建（`RendererFx.kt:190-197`）；缓存键必须覆盖**全部依赖维度**（历史 BUG `OverlayFx.kt:65-72`）。
  ⭐ **v1.4**：底环缓存位图**没有**用 `SizeCache`，而是自己写 `ensureRingLayer(w, h, density)` 三键判定 —— 因为它还需要显式 `recycle()`（`SizeCache` 只管重建、不管释放），且 `density` 必须在键里（§4.3）。
- **AudioSmoother**：`AudioSmoother(attack=0.35f, release=0.06f)` 是**项目既定**数值（§7.3「参数不得在渲染器里重造魔数」）。本效果**不**用它（裁决 4，见 §5.1 警示）。

### 3.6 ProceduralTexture.release 的调用方陷阱

`ProceduralTexture.release()`（`fx/ProceduralTexture.kt:143`）**由舞台统一调用**，渲染器**不得调用**（` :17` 自注，且 `RendererFx.onExit` 里调的是 `OverlayFx.release()` 而非它）。本效果 `onExitContent` 只负责 `recycle()` **自己**的底环缓存位图 + 清 `Path`，不动共享纹理。

---

## 四、架构设计

### 4.1 分层与每帧流水线（v1.5：11 步，单次 `DrawScope.draw` 内完成）

每帧在 `drawContent(frame, ctx, fx)` 内，从底到顶：

1. **天空渐变**：5 段 `Brush.verticalGradient`（尺寸变化时重建），双份缓存（暗版/亮版，§4.4）按 `skyMix(sectionEnergy)` 用 alpha 交叉叠加 → 零分配变色。
2. **静止星点**：`ProceduralTexture.tile(STARFIELD)?.let { drawImage(it, dstSize=IntSize(iw,ih), alpha=0.55f) }`。
3. **拍点闪光**：`frame.beat` 触发 `flare = 1f`，指数衰减回落；同时生成 2–4 颗切向流星（§4.6）。
4. ⭐ **底环（圆）**：`drawImage(ringsBitmap, dstSize=IntSize(iw,ih), alpha=RING_ALPHA, blendMode=BlendMode.Plus, filterQuality=FilterQuality.None)` —— 一次 blit 贴出 `RING_BANDS` 圈整圈细线（§4.3）。**v1.5：`RING_ALPHA` 0.26 → 0.10**，圆几乎隐去、只留一丝暗示极坐标结构。
5. ⭐⭐ **spray 喷溅弧场**（v1.5 新增）：`drawSpray(poleAngleDeg(...), sprayAlphaFor(fieldEnergy()))` —— **一个** `withTransform { rotate(rotDeg, pivot = 天极) }` 下，`SPRAY_BUCKETS`(4) 次 `drawPath` 画完整片**数百条静态短弧**（§4.3）。这是「分布不均匀 + 亮弧更多」的答案。
6. ⭐ **亮线段 hero**：逐柱 `drawArc` `SEG_K` 段首尾相接的子弧，alpha 自尾向头按 `SEG_GAMMA` 爬升，`BlendMode.Plus` 加性星芒（§4.3）。**v1.5：`SEG_K` 4 → 3**（省下的 64 次 `drawArc` 换成 spray 的 4 次 `drawPath`）。
7. **切向流星**：沿圆周推进的**圆弧**（§4.6.1），画在主画布（v1.3 起不再是「画进缓冲再合成」）。
8. **天极辉光**：`RadialGradient` 精灵（尺寸变化时烘焙），`glowAlpha = 0.30 + 0.25·sectionEnergy + 0.10·pulse + flare`。
9. **地平辉光带**：地平线上下的垂直渐变条。
10. **地景 + 枯树剪影**：不透明 `Path`（起伏山脊 3 段三次贝塞尔 + 描边枯枝 + 填充树干），画在星轨**之上**（覆盖星轨、自然截止，§4.5.1）。⛔ **不画人物**（§4.5.2）。
11. **后处理**：`applyPostFx`（`RendererFx.kt:66`），核对 `postFx` 非零 ⇒ 基类自动带暗角 + 噪点。

> ⛔ **v1.4 换掉的旧步 4–7**（v1.3 的「拖尾合成 → 复制 + DST_OUT 衰减 → 画新弧 → 翻转」）**整体删除**：不再有缓冲、没有衰减、没有翻转、没有全屏回绘。§9 R1 的填充率缺口因此从「每帧 2 趟全缓冲」降到「**无缓冲**」。
>
> 决策要点：**旋转仍不进流水线**。天极自转通过「每帧把亮线段的**头**画在 `angleNow = baseAngle + ω·(nowMs−t0)` 位置」实现，**零额外 transform、零额外全屏变换**（§4.2）。
> ⭐ **v1.5 的 spray 是唯一用到 `withTransform` 的一笔** —— 但它是**零全屏回绘**的纯坐标变换（`rotate`），不建离屏层、不 `saveLayer`，与 v1.3 被删的「缓冲缩放」性质完全不同（§4.3）。

### 4.2 天极坐标系与几何映射

天极 `P = poleCenterFor(w, h)`（⭐ 含宽高比自适应，见 §5.2），自转角从**单一时钟** `fx.nowMs`（`RendererFx.kt:180`）得出：

```kotlin
// ⭐ 柱相位：种子伪随机（原型定稿），非初版的金角。
// 用**继承的** rng（§3.5）在 onEnterContent 抽一次并缓存进 FloatArray，
// 跨帧/跨 resize 稳定（同一 seed → 同一分布），且不像金角那样规则到能被看出周期。
// onEnterContent:
val az = FloatArray(barCount)
for (i in 0 until barCount) az[i] = rng.next() * 2π   // 弧度
// 每帧:弧 i 的方位角 = az[i] + poleAngleDeg(...)

// 天极时刻角：单一时钟推导，帧率无关（比逐帧累加 dt 更强、无漂移）。
fun poleAngleDeg(nowMs: Long, t0Ms: Long): Float =
    (ROT_DEG_PER_S * ((nowMs - t0Ms) / 1000f)) % 360f
```

⛔ **帧率无关性说明**：本效果有意从 `fx.nowMs` 推导（而非每帧 `+ω·dt` 累加），这比「每帧增量 ×dt」更强，且天然通过 `RendererBaseContractTest` 的 `dt` 一致性门。唯一要保证的是 `t0Ms` 在 `onEnterContent` 记一次（`fx.nowMs` 虽可用，但 `onEnterContent` 拿不到 `fx`——故 `t0Ms` 用 `RenderContext.nowMs` 或 `System.currentTimeMillis()`，与 E18 的 `ctx.nowMs` 用法一致，见 `UltraRenderers.kt` 同款）。

> ⚠️ **与初版的偏离（裁决 5）**：初版用黄金角 `137.508°` 错相位。原型实测定稿改为**种子伪随机**——黄金角在 64 根等距柱上会产生可察觉的规则性（尤其内侧柱挤在很小半径时）。`VisualizerRandom`（`visualizer/VisualizerRandom.kt:24-60`）已有 `next()`，直接用继承的 `rng` 抽即可，**不得自行 new 一个 `VisualizerRandom`**（§3.5）。

**半径映射**（低音贴天极、高音外扩；`t^0.72` 向天极聚密，还原参考图「近极密、外圈疏」）：

```kotlin
fun radiusForBar(bar: Int, barCount: Int, minDim: Float): Float {
    if (barCount <= 1) return minDim * R_INNER_K
    val t = (bar.toFloat() / (barCount - 1)).coerceIn(0f, 1f)
    val shaped = t.pow(RADIUS_SHAPE)                 // RADIUS_SHAPE = 0.72f
    return minDim * (R_INNER_K + (R_OUTER_K - R_INNER_K) * shaped)
}
```

> ⛔ **取消 `scaleK` 参数**（初版有）：原型实测发现**缓冲缩放**会连带改变半径比例，在竖屏下把星环挤扁；v1.4 已无缓冲，改为**外半径钳制**（§5.2）更稳。`radiusForBar` 只吃 `(bar, barCount, minDim)`，无缩放项。

**等角扫掠 = 密度自动还原**：同一 `sweepDeg`（由幅值驱动），外圈半径大 ⇒ 弧**线**长、间距大；近极半径小 ⇒ 弧短而密。参考图的「近极短而密、外圈长而稀」**不加任何密度函数**，纯由 `弧长 = r·θ` 自动涌现。这是本方案的关键几何洞察，必须写进 KDoc。

### 4.3 ⭐ 星轨 = 底环（圆） + spray 喷溅弧场（密集短弧） + 亮线段 hero

> **v1.5 增补 Pass A′（spray）并更新全部数值**。v1.4 已把 ping-pong + `DST_OUT` 架构整体删除（§12.4 ⑮）；v1.5 在「底环 + 亮线段」之间插入一层**静态喷溅弧场**，因为真机确认亮线本身没问题、但**分布太均匀、亮弧不够多**（§12.4 ⑯）。
>
> 用户口述的两轮目标形态：
>
> - v1.4：「**用细线画圆，然后线上有一段一段的描出来亮线，前面亮后面逐渐与原来的线一样了**」（底环 = 圆；亮线段 = 线上逐段描出的亮线）
> - v1.5：「**亮线没问题。但是星轨分布太均匀了。还有就是轨道线条可以不明显，但画出来的亮弧要更多**」

拆成三笔（自底向上）：

#### Pass A —— 底环（圆）· **几乎隐去**

- `RING_BANDS`（= `SpectrumContract.BAR_COUNT` = 64）圈**整圈**细线，半径 = `radiusForRange(i, RING_BANDS, rInner, rOuter)`。
- 线宽 `RING_W` = `1.0f` **dp，恒定** —— ⛔ 不再随幅值调制（用户要「细线」）。
- 颜色 `ringColorArgb(t)`：暖白 → 冷蓝按 `t^COLOR_SHAPE`，**不透明**（alpha = 255）；逐帧的整体亮度**全部**由 `drawImage(alpha = RING_ALPHA)` 施加 ⇒ 底环实际 alpha 恒为 `RING_ALPHA`，**与幅值无关**。
- ⭐ **v1.5：`RING_ALPHA` `0.26f → 0.10f`** —— 用户判词「轨道线条**可以不明显**」⇒ 圆退到只剩一丝暗示极坐标结构，把视线让给 spray 与 hero 亮线。
- **烘焙成一张位图**：原生尺寸（`w.toInt() × h.toInt()`，`MAX_TEX_PX` 钳制），在 `rebuildRingLayer` 里用 `android.graphics.Canvas.drawCircle` 逐圈烘好，**只在尺寸 / `density` 变化时重建**（`ensureRingLayer` 判缓存键）；每帧只做 **1 次 blit**（64 次 `drawCircle` → 1 次 `drawImage`）。
- ⛔ **必须是原生尺寸**：v1.3 的 960 宽降采样缓冲被最近邻放大到 1920 就是**锯齿**的来源；这张位图每帧 1:1 直贴（`filterQuality = FilterQuality.None`）。

#### Pass A′ —— ⭐ spray 喷溅弧场（v1.5 新增，「分布不均匀 + 亮弧更多」的答案）

参考图的实感**不来自整齐的圆**，而来自**几百条短而断续的弧**——疏密不均（有聚簇有空档）、长度参差、颜色各异、整体读作「闪烁的密场」。v1.4 只有 64 圈整齐的底环 + 每圈一段渐变亮线 ⇒ 正好是用户判失败的「太均匀」。

**每带条数**（`sprayPerBandFor(fx.level)`）：FULL 6 / LITE 5 / OFF 3 ⇒ HIGH **384** 条、MEDIUM **320** 条、LOW **192** 条。

**每条弧的全部参数**由 `(bandIndex, k)` 的**确定性哈希**给出（⛔ 非 `Math.random`；逐帧与跨 resize 恒定，否则会看到弧在跳）：

| 参数 | 取法 | 作用 |
|---|---|---|
| **活跃位** | `sprayHash(·,·,0) >= SPRAY_CUTOFF(0.28)` ⇒ 实测活跃率 ≈ **0.78** | 被剔除的 ≈22% **就是空档的来源**；全画出来又变整齐 |
| **半径** | `sprayRadiusFor` = 锚点 ± `SPRAY_RADIUS_JITTER(0.45) × 最近邻间距` | ⭐ **打散同心圆规整感的唯一来源** |
| **相位** | `sprayHash(·,·,2) × 360°` | 逐弧独立 ⇒ 散开而非 64 条径向对齐 |
| **扫掠角** | `4° + hash × 22°` ⇒ `[4°, 26°]` | 长度参差 |
| **颜色桶** | `(sprayHash(·,·,4) × 4)` ⇒ `0..3` | 四色轮换（见下） |

**半径抖动的边界保证**（G2 数值段锁死）：

- `localGapFor(band, …)` 取到**最近邻带**的间距（两侧取较小者 —— 半径向外递增 ⇒ 间距向外**递减**）。
- ⛔ `SPRAY_RADIUS_JITTER < 0.5f` 是「带间半径**顺序恒不交叉**」的来源；`sprayRadiusFor` 再**硬夹紧**到 `锚点 ± J·gap` 作为第二道保证。
- 实测（1080p、64 带）：`带 i 的最外 spray < 带 i+1 的最内 spray`，**最小余量 0.84px** ⇒ 读作「同一族半径的抖动」而非乱网。

**颜色桶调色板**（对应参考图「多冷蓝 / 夹淡白暖白 / 少量品红」）：

| 桶 | Hex | 角色 |
|---|---|---|
| 0 | `#8FA4DF` | 冷蓝（主色） |
| 1 | `#DCE4FF` | 淡白青 |
| 2 | `#FFF7EA` | 暖白 |
| 3 | `#C89BE0` | 柔品红（点缀） |

#### Pass A′′ —— ⭐⭐ **必须烘焙 + 单次旋转变换**（本设计的命门）

⛔ **逐帧逐弧绘制会把真机 29.7fps 直接砍半**（384 次 `drawArc`）。做法：

- **烘焙期**（`rebuildSprayPaths`，只在尺寸 / `density` / **画质档** 变化时）：对每个 `(band, k)` 算好参数，活跃的用 `Path.moveTo(起点)` + `Path.addArc(oval, 相位, 扫掠)` 塞进 `sprayPaths[bucket]`（每色一条 `Path`）。
  - ⚠️ **`moveTo` 不可省**：Compose 的 `Path.addArc(oval, start, sweep)` **没有** `forceMoveTo` 参数（3 参重载，已用 `javap` 核对 1.9.3 签名），它默认**续接当前轮廓** ⇒ 不先 `moveTo` 就会把相邻两段弧连成横穿全场的**长直线**，形似 §4.6.1 判失败的「鱼刺」。
  - ⚠️ `Path.addArc` 收的是**不可变** `androidx.compose.ui.geometry.Rect` ⇒ 每弧必构造一次；这是**烘焙期**的一次性分配，不在每帧路径上，故挂 `// Perf-exempt` 放行（与 `PerfBudgetContractTest.isExemptLine` 语义一致）。
- **每帧**（`drawSpray`）：

  ```kotlin
  withTransform({ rotate(rotDeg, pivot = Offset(poleX, poleY)) }) {
      var b = 0
      while (b < SPRAY_BUCKETS) {
          drawPath(sprayPaths[b], sprayColors[b], alpha = alpha, style = stroke, blendMode = BlendMode.Plus)
          b++
      }
  }
  ```

- ⭐ **恒等**：spray 弧是**静态**的（只有整片绕天极转）⇒「一次变换 + 4 次 `drawPath`」与逐条画**像素等价**，代价低两个数量级。门禁 `⑧ spray 必须烘焙`（含 `noLiveSprayBake` 负向自证）锁死这一点。
- ⭐ **变换绕天极**（`poleX,poleY`）⛔ 不是画布中心：喷溅弧不在同心圆上（半径带抖动），绕中心转会把整片场平移出去、读成「场在飘」。
- **场亮度** `sprayAlphaFor(fieldEnergy())`：`0.18 … 0.55`，随各柱包络均值呼吸。⚠️ **下界不为 0** —— 静默段也留一层底噪，否则音乐一停整片场凭空消失、读成「效果坏了」。

#### Pass B —— 亮线段 hero（亮线）· **v1.5 已认可的观感，保持不变**

- 逐柱（`amp ≤ SILENT_FLOOR` 整柱跳过）：扫掠角 `sweep = sweepForAmp(amp)`（**未改动**），**头**在旋转天极角 `az[slot] + poleAngleDeg(...)` 上，尾沿反方向退开 `sweep` 度。
- ⭐ **v1.5：`SEG_K` `4 → 3`** —— 把省下的 `RING_BANDS × 1 = 64` 次 `drawArc` 换成 spray 的 4 次 `drawPath`（预算中性）。斜坡 `SEG_GAMMA` 与线宽 `SEG_W_MIN`/`SEG_W_GAIN` **完全不动** ⇒ 真机已认可的亮线观感零回归。
- 切成 `SEG_K` 段**首尾相接**的子弧（`strokeCap = BUTT` ⇒ 平头无接缝鼓起；每段 `sweep / SEG_K`，起始角链式推进）。

- 逐柱（`amp ≤ SILENT_FLOOR` 整柱跳过）：扫掠角 `sweep = sweepForAmp(amp)`（**未改动**），**头**在旋转天极角 `az[slot] + poleAngleDeg(...)` 上，尾沿反方向退开 `sweep` 度。
- 切成 `SEG_K` = **3** 段**首尾相接**的子弧（`strokeCap = BUTT` ⇒ 平头无接缝鼓起；每段 `sweep / SEG_K`，起始角链式推进）。
- alpha 自尾向头**严格单调爬升**（`segAlphaAt(k)`）：

  ```kotlin
  alpha_k = RING_ALPHA + (1 − RING_ALPHA) · ((k+1)/SEG_K)^SEG_GAMMA      // SEG_GAMMA = 1.6f
  ```

  `k=0`（尾）≈ `0.340` —— 已沉回底环亮度；`k=3`（头）= `1.0` —— 最亮。`γ > 1` ⇒ 凹上升，爬升集中在头两段。⛔ **与幅值无关**（幅值只调 `SEG_W_MIN`/`SEG_W_GAIN` 线宽），故同一柱的 4 段不会帧间跳变。
- 线宽 `segWidthPx(amp, density) = (SEG_W_MIN + SEG_W_GAIN·amp) × density` dp→px = **`1.0 … 1.8` dp**（v1.3 是 `1.2 + 2.2·amp` dp **且**被 2 倍放大 ⇒ 屏幕上是 2.4–6.8 dp，这就是「过粗」）。
- 画法：⛔ **直接** `DrawScope.drawArc(color, startAngle, sweepAngle, topLeft, size, style=Stroke(w, cap=Butt), blendMode=BlendMode.Plus)` —— 原生分辨率 + 抗锯齿，⛔ 不经任何降采样缓冲。`Plus` 由舞台的 `CompositingStrategy.Offscreen`（`VisualizerStage.kt:337-340`）保证生效。
- ⛔ **不会重新饱和**：同一条环的子弧彼此**相邻不重叠**，不同环半径**互不相同** ⇒ 逐子弧改 alpha 在任何一点都不叠加 —— 这正是 v1.3「几十帧同角度 Plus 叠加」把中心烧成死白的反面。

#### 每帧弧绘制预算（MEDIUM / 64 柱全开）

| 项 | v1.4 | ⭐ **v1.5** | 说明 |
|---|---|---|---|
| 底环 `drawCircle` | 0（烘焙期 64 次） | **0** | 每帧 1 次 `drawImage` |
| ⭐ spray 弧 `drawArc`/`addArc` | —（无此层） | **0**（烘焙期 320 次） | ⛔ 每帧 `SPRAY_BUCKETS` = **4** 次 `drawPath`（1 次 `withTransform` 内） |
| ⭐ spray 弧总数 | — | 320（MEDIUM，活跃 ≈246） | HIGH 384 / MEDIUM 320 / LOW 192 |
| 亮线段子弧 `drawArc` | 256 | **64 × 3 = 192** | `SEG_K` 4→3，满幅扫掠 |
| 流星 `drawPath` | 0–40 | 0–40 | 仅鼓点后存活期内 |
| 地景 `drawPath` | ≤ 48 枝 + 3 | ≤ 48 枝 + 3 | 静止路径 |
| **全屏缓冲回绘** | 0 | **0** | ⛔ 无 ping-pong（§9 R1 因此基本退役） |

⭐ **预算净变化：`−64 drawArc + 4 drawPath + 1 withTransform`** ⇒ 相对 v1.4 实测的 **29.7fps 应基本持平或略好**（但 ⛔ **未在真机复测**，见 §12.4 ⑯）。
LOW 档（`FxLevel.OFF`）亮线段按 `LOW_ARC_STRIDE = 2` 隔柱取样 ⇒ 32 × 3 = 96 次 `drawArc`，spray 192 条（每带 3）。

### 4.4 天空渐变与色调映射（零分配双缓存交叉淡入）

天幕配色（取自参考图，5 段）：

| 段 | Hex | 角色 |
|---|---|---|
| 天顶 | `#0A1026` | 深靛 |
| 上中 | `#16255A` | 过渡 |
| 中 | `#2C3E82` | 主体蓝 |
| 下 | `#56659F` | 近地提亮 |
| 地平 | `#8D97C6` | 地平辉光 |

实现：`SizeCache` 缓存 **两份** `Brush.verticalGradient`——`darkBrush`（上表原值）与 `brightBrush`（每段向亮蓝紫抬升的变体）。每帧：

```kotlin
val m = skyMix(frame.sectionEnergy)     // = (sectionEnergy * 1.15f).coerceIn(0f,1f)
drawRect(darkBrush)                     // 底
drawRect(brightBrush, alpha = m)        // 顶，零分配 —— drawRect(brush, alpha) 的 alpha 是参数
```

⛔ 不用「每帧 new 渐变改色」——`Brush.verticalGradient(vararg colors)` 会分配 vararg 数组，撞 `PerfBudgetContractTest`。

### 4.5 弧线、地景与枯树（API 22 安全）

> 本节已在 v1.1 按 `docs/starry-sky-preview.html` 的实测定稿重写。⛔ 与初版最大的三处差异：**地面由底部 1/3 降到 1/4**、**树改为枯树（无叶、虬枝）**、**人物剪影整体删除**。原型迭代过程见 §十三。

- **弧线**：`currCanvas.drawArc(arcRect, startAngle, sweepDeg, false, trailPaint)`；`trailPaint.style=STROKE`、`strokeCap=ROUND`、`strokeWidth = 线宽`、`color=barColorFor(t)`。`arcRect` 复用，`set(poleX-r, poleY-r, poleX+r, poleY+r)`。
- **线宽**：`lineW = 1.2f + 2.2f * amp`（dp→px 换算在 `onEnterContent` 存 density），上限防 TV 过粗。
- **颜色**：核心 `#FFF7EA`（暖白）→ 外圈 `#8FA4DF`（冷蓝），`VisualizerMath.lerp(暖, 冷, t^COLOR_SHAPE)`；`t` = 柱序号比。亮度再乘 `alpha = 0.5 + 0.5*amp`。
- **辉光（不用 RenderEffect）**：弧线不逐根糊。`HIGH`(`fx.level==FULL`) 靠「天极辉光 + 亮线段头段更亮」近似；`MEDIUM/LOW` 靠底环 + 亮线段的加性叠加本身营造柔光（污点近似已是项目惯例，`photo/transitions/ZoomP1Transitions.kt:23`）。

#### 4.5.1 地平线与山脊（`HORIZON_K = 0.75f`）

- **地平线**：`horizonY = h * HORIZON_K`，地面占**底部 1/4**。星场裁切 `if (y > G.horizonY) continue` 与地平辉光带（原型为 `0.59h→0.79h`）都读这一个值，自动跟随。
- **山脊**：**3 段三次贝塞尔**（初版是 1 条缓丘），起伏山脊让地景不再是死板水平线。控制点表见 §5.3。
- **`ridgeY(x)` 的关键设计**：每段两个控制点的 **x 固定取该段 x 跨度的 1/3 与 2/3** ⇒ `x(t)` 关于 `t` 线性 ⇒ 给定 `x` 可**直接** `t = (x-x0)/(x1-x0)` 后用 Bernstein 公式求 `y`，**不需要解 `x(t)=x` 的二次方程根**。这保证「画脊线路径」与「取树根基点」用的是**同一组控制点**，树根**必然落在脊线上**，不会浮空或陷进地里。

```kotlin
// ridgeY：与画路径同源，纯函数、可单测（§七 G2/G9）
private fun ridgeY(x: Float): Float {
    val u = x / w
    var g = RIDGE[RIDGE.size - 1]
    for (i in RIDGE.indices) if (u <= RIDGE[i].x1) { g = RIDGE[i]; break }
    val t = ((u - g.x0) / (g.x1 - g.x0)).coerceIn(0f, 1f)
    val k = 1f - t
    val dy = k*k*k*g.y0 + 3f*k*k*t*g.c1 + 3f*k*t*t*g.c2 + t*t*t*g.y1
    return horizonY + dy * h
}
```

- 路径用 `nativeCanvas` 的 `Path.cubicTo` 走同样控制点；填充色 `C_GROUND = #0B0B12`，不透明，画在星轨之上 ⇒ 星轨在地平线处**自然截止**（同参考图）。

#### 4.5.2 枯树（无叶、虬枝）

- **树位**（从几何算，不写死）：放在「外圈星环最左端」与「左边框」的中点，即环外左侧的空天。
  ```kotlin
  val ringLeft = px - rOuter
  val treeX = maxOf(ringLeft * 0.5f, TREE_BRANCH_REACH + TREE_EDGE_GAP)
  val treeBase = ridgeY(treeX) + 2f     // 略埋入脊线，防接缝
  ```
  `Math.max` 下限是**必需**的：窄画幅下 `rOuter` 相对 `w` 很大、星环已越过左边界，中点会成负数。原型实测：1280×720 时树心 ≈13.5%w，竖屏 520×900 时下限生效。
- **树干**：单一 `Path`，半宽 `0.032h`(根外扩) → `0.026h`(中) → `0.011h`(分叉处)，分叉高度 `0.40·treeH`，两侧 `quadraticCurveTo` 呈微弓，顶端整体左倾 `0.050·treeH`（中段弯曲系数 `0.38`）。下粗上细的强烈收分。
- **枯枝**：**描边**（`Style.STROKE`, `strokeCap/Join=ROUND`）而非填充三角形，每根 4 段、`lineWidth` 逐段衰减（`wd*(1 - i/SEG*0.72)`）到 0.7px 下限，每段带确定性哈希抖动做「虬曲」结疤；每段把角度 `a *= 0.90` 往回拉，使枝条**向上伸展**而非下垂；`depth>0` 分叉出两根子枝（`len*0.58`, `wd*0.58/0.52`）。
- **5 条主枝布局**（刻意不对称，3 左/2 右；`at` = 从分叉点(0)到树根(1)的归一化出枝高度，让低枝从树干不同高度生出而非全从一个点扇出）：

  | 角度 | len | depth | at | 说明 |
  |---|---|---|---|---|
  | +20° | 0.95 | 0 | 0.00 | 主干上举 |
  | +36° | 0.80 | 1 | 0.00 | 左侧主枝（带分叉） |
  | −44° | 0.66 | 1 | 0.05 | 右侧主枝（带分叉） |
  | +54° | 0.34 | 0 | 0.48 | 低枝 |
  | −32° | 0.30 | 0 | 0.66 | 低枝 |

- ⛔ **全程不用圆角 clip**（`VisualizerStage.kt:750-752` API 22 段错误），不用 `BlendMode.Difference`。枯枝描边的 `Stroke` 须缓存复用（`Stroke` 是 data class）。
- ⛔ **人物剪影已整体删除**（初版成人像、二次迭代的小孩，均被判「不好看/看不出」）。原型证明：一个画不好的小人比没有更伤观感。**不实现、参数表中不留任何人物常量**。

### 4.6 音频映射总表

| 音频 | 通道 | 映射到 | 公式/实现 |
|---|---|---|---|
| `spectrum[bar]` | 显示 | 弧半径 `r` | `radiusForBar(bar, barCount, minDim)`（§4.2） |
| `spectrum[bar]` | 显示 | 弧扫掠角 `sweepDeg` | `sweepForAmp(amp)`（下详） |
| `spectrum[bar]` | 显示 | 线宽 / 亮度 | `lineW`、`alpha = 0.5+0.5·amp` |
| `bassRaw` + `frame.beat` | 动态 | 极点闪光 `flare` | `beat` 时 `flare=1f`，每帧 `flare *= exp(-dt/FLARE_DECAY_S)` |
| `frame.beat` | 动态 | 切向流星 | `beat` 帧 spawn 2–4 颗**沿各自圆弧**的短尾流星（见 4.6.1） |
| `sectionEnergy` | 动态 | 天色 `skyMix` + 天极辉光 `glowAlpha` | `(sectionEnergy*SKY_TINT_GAIN).coerceIn(0,1)` / `GLOW_BASE+GLOW_ENERGY·sectionEnergy+flare` |
| `frame.pulse` | 动态 | 静音呼吸（3s 无鼓点 fallback，`SpectrumRepository.kt:123-127` 已给正弦） | 呼吸驱动天极辉光微涨落，静音**不全黑** |
| 全零频谱 | 显示 | ⭐ 64 圈底环常亮 + 自转（无亮线段） | 天极辉光靠 `pulse` 呼吸保底，不全黑 |

**扫掠角**（§4.2 等角涌现的关键）：

```kotlin
fun sweepForAmp(amp: Float): Float {
    if (amp <= SILENT_FLOOR) return 0f
    val n = ((amp - SILENT_FLOOR) / (1f - SILENT_FLOOR)).coerceIn(0f, 1f)
    return MAX_SWEEP_DEG * n
}
```

**幅值包络**（⭐ 不用 `AudioSmoother`，见 §5.1 裁决 4）：

```kotlin
// attackTau 快攻 / releaseTau 慢放，单位秒；target = amp
val tau = if (target > cur) ATTACK_S else RELEASE_S
cur += (target - cur) * (1f - exp(-dt / tau))
```

#### 4.6.1 ⭐ 切向流星必须画成圆弧（原型抓到的「鱼刺」根因）

原型迭代中出现的「鱼刺」观感，根因**不是星轨**（星轨一直是真 `ctx.arc`），而是**流星把尾巴画成 `moveTo→lineTo` 的直线弦**。流星沿圆周每帧移动 ⇒ 每帧留一根**直线肋骨**，绕极一圈形似鱼骨。**Kotlin 实现必须用圆弧**：

```kotlin
// ⭐ v1.3 更正后的可粘贴形式（生产实现逐字同形）。
// 扫掠角带符号 ⇒ 弧的绘制方向由 m.dir 决定；tail 在头角的反侧。
val sweepRad = mLen[i] * mDir[i]          // 弧跨度（rad），符号 = 方向
val startRad = mAng[i] - sweepRad          // 尾迹角
meteorRect.set(poleX - mR[i], poleY - mR[i], poleX + mR[i], poleY + mR[i])
meteorPath.reset()
meteorPath.addArc(meteorRect, startRad * DEG_PER_RAD, sweepRad * DEG_PER_RAD)
canvas.drawPath(meteorPath, meteorPaint)
```

> ⛔ **v1.3 更正：⛔ `Path` 没有 4 参 `addArc`。**
> 本节此前给的是
> `path.addArc(RectF(...), min(m.ang, tail), max(m.ang, tail), m.dir < 0)`，
> 两处都错，**编译不过**：
> ① `addArc` 只有 `(RectF, startAngle, sweepAngle)` 三参与
>    `(left, top, right, bottom, startAngle, sweepAngle)` 六参两个重载；
>    **带 `forceMoveTo` 的 4 参版本是 `arcTo`，不是 `addArc`**；
> ② 第 2、3 参是**扫掠角**（相对量），不是**终止角**（绝对量）——
>    原型是 Canvas 2D 的 `arc(cx, cy, r, 起始角, 终止角, anticlockwise)`，
>    搬过来时把「终止角当扫掠角」传了。
> 正确写法见上：`start = 尾迹角`、`sweep = ±弧跨度`（符号即方向），
> 几何上与原型的 `min/max + anticlockwise` 等价（都是同一段短弧），
> 但 ⛔ 免掉了 `min`/`max` 与 `dir` 符号的耦合，且 `m.dir` 逐字出现在扫掠角里。

- `RectF` 复用（同 §4.5 的 `arcRect` 做法；⛔ **不得**内联 `RectF(...)`——那是每帧堆分配），`meteorPaint` 成员预建。
- 弧跨度 `mLen` 在 `0.12~0.34 rad`（7°–19.5°）——大半径上短弧视觉上接近直线是**正常的**，但必须**几何上是弧**（v1.3 靠逐帧累积连成流线；v1.4 无累积，但每帧弧仍必须是弧，否则绕极一圈会排成一根根直线肋骨）。
- ⛔ 不得回退成 `lineTo`。
- ⚠️ 流星池是**定长 `FloatArray`**（`mR`/`mAng`/`mDir`/`mSpd`/`mLen`/`mAge`/`mLife`/`mWarm` 各一条 + `mCount`），
  移除用 swap-remove 压末位（⛔ 不用 `removeAt` / `List`）。

**扫掠角**：见上。

### 4.7 降级矩阵

| 画质/`fx.level` | 星点密度(`STARFIELD` 不变) | 星轨弧线数 | 底环 | 辉光 | 是否提供 |
|---|---|---|---|---|---|
| HIGH / FULL | 全 | 64（× `SEG_K`=3 段） | 64 圈整圈 | ⭐ 384 条 spray（6/带） | 天极辉光强 | ✅ |
| MEDIUM / LITE | 全 | 64（×3 段） | 64 圈整圈 | ⭐ 320 条 spray（5/带） | 天极辉光中 | ✅（默认档） |
| LOW / OFF | 全 | 32（`LOW_ARC_STRIDE=2` 隔柱，×3 段） | 64 圈整圈 | ⭐ 192 条 spray（3/带） | 天极辉光弱 | ✅（v1.2：`needsParticleBudget=false` ⇒ `supports()` 恒 true） |

> ⭐ **v1.4**：原表的「缓冲宽 1280/960/640」三档**随缓冲一起删除** —— 不再有离屏缓冲，底环与亮线段都在**原生分辨率**画布上。
> ⭐ **v1.5**：三档之间有两项差异 —— ① 亮线段数量（低档隔柱 `LOW_ARC_STRIDE`）；② ⭐ **spray 弧密度**（6/5/3 每带）。⛔ spray 的 `perBand` 是 `ensureSprayLayer` 的**缓存键之一**，切画质必须重烘焙，否则 LOW 切回 HIGH 会少画一半弧。
>
> ⭐ **v1.2 更正**：v1.0 写「LOW 档 `Tier.ADV` 效果整体消失」——该结论基于旧门控 `ADV -> maxParticles > 0`，在 `40ca823` 改门控后**已失效**。现三档全部可达，因此 LOW 隔柱分支**必须正确实现**（⛔ 恒读 `frame.spectrum.size`，不可写死 64）。
>
> `FxLevel.OFF`（`LOW`）时 `OverlayFx` 各方法**零 draw**（`fx/FxLevel.kt:13-29`），故 LOW 档无暗角/噪点——这是既有行为，非缺陷。

### 4.8 生命周期与资源所有权

- `onEnterContent(ctx)`：① **首行** `releaseResources()`（先释放，防 `RendererSwapper.sync` 重入泄漏）；② 用继承的 `rng` 抽 `az[]` 柱方位角（§4.2，仅首次，`azReady` 守卫）；③ `rebuildGeometry(ctx.canvasSize)` —— 渐变 Brush、山脊 + 枯树 `Path`、`ProceduralTexture.ensure` 的唯一调用点。⛔ **不建任何人物 Path**。⛔ **不在此烘焙底环**（这里拿不到 `Density`，见 §12.4 ⑩ 的同款教训）。
- `drawContent(...)`：§4.1 流水线。底环走 `ensureRingLayer(w, h, density)` —— 命中缓存时只有几次浮点比较（零分配），未命中才 `rebuildRingLayer`。
- `onExitContent()`：`releaseResources()`（`recycle()` try/catch）+ 清 `Path`；**不** `ProceduralTexture.release()`、**不** `OverlayFx.release()`（两者归舞台/基类）。
- 尺寸变化：`drawContent` 的尺寸分支先 `releaseResources()` 再 `rebuildGeometry()`，随后的 `ensureRingLayer` 自动换新底环位图。

---

## 五、关键参数表

> ⛔ **本表已于 v1.1 与 `docs/starry-sky-preview.html`（浏览器原型，已实测定稿）逐项对齐**。带 ⭐ 的是原型实测后**改掉**的初版值，务必按新值实现。

### 5.1 核心物理 / 频率映射

| 参数 | 值 | 类型 | 出处/意义 |
|---|---|---|---|
| `ROT_DEG_PER_S` | `3.2f` | Float | 天极自转角速度 ≈ 360°/112s（时间基准，非逐帧累加） |
| `FLARE_DECAY_S` | `0.18f` | Float | 鼓点闪光时间常数 |
| `MAX_SWEEP_DEG` | `46f` | Float | 满幅扫掠角（**v1.4 未改动**） |
| `SILENT_FLOOR` | `0.012f` | Float | 弧可见门限（低于即不画该柱） |
| `R_INNER_K` | ⭐ `0.10f` | Float | 最内柱半径占 `minDim` 比。**v1.4：`0.055 → 0.10`** —— 0.055 让最内圈几乎压在天极上，与「几十帧同角度 Plus 叠加」一起把中心烧成死白；抬高后中心留下一圈可见暗核（参考图也有） |
| `R_OUTER_K` | `0.78f` | Float | 最外柱半径占 `minDim` 比 |
| `RADIUS_SHAPE` | `0.72f` | Float | 半径聚密指数 `t^0.72`（向天极聚拢） |
| `COLOR_SHAPE` | `0.55f` | Float | ⭐ 弧色 `lerp` 指数 `t^0.55`（**与半径指数不同**：外圈更快转冷） |
| `RING_BANDS` | `64` | Int | 底环圈数 = `SpectrumContract.BAR_COUNT`。⛔ **不是**每帧柱数（每帧恒读 `spectrum.size`）；它同时是底环与亮线段**共用的半径分母**，两笔因此必然同环 |
| `BAR_COUNT` | `64` | Int | ⛔ 实现仍读 `frame.spectrum.size`，此值仅用于原型对拍 |
| `ATTACK_S` | `0.025f` | Float | ⭐ 幅值包络起音（快） |
| `RELEASE_S` | `0.24f` | Float | ⭐ 幅值包络释音（慢 → 平滑尾迹） |

> ⛔ **v1.4 删除**：`TRAIL_TAU_S = 0.75f`（拖尾时间常数）与纯函数 `decayAlphaFor` —— 二者只服务于已删除的 ping-pong 指数衰减（§4.3）。`StarrySkyTest` 的「⑧ 累积缓冲机械必须已删除」源码门禁锁死这两者不得回流。

> ⚠️ **包络参数与既有 `AudioSmoother` 的关系**（裁决 4）：`AudioSmoother(attack=0.35f, release=0.06f)`（`fx/AudioSmoother.kt:12-27`）的语义是「attack 越大越慢」，与本效果要的「0.025s 快攻 / 0.24s 慢放」**方向相反且数值不等**。因此本效果的包络**不复用** `AudioSmoother`，而是自己按时间常数实现（`attackTau`/`releaseTau`），并在 KDoc 写明为何不复用（§3.5 的「不得重造魔数」约束**不适用于此**：这里需要的是物理时间常数，不是相对系数）。

### 5.2 天极 / 画幅自适应

| 参数 | 值 | 意义 |
|---|---|---|
| `POLE_X_K` / `POLE_Y_K` | `0.70f` / `0.52f` | 横屏天极归一化坐标 |
| `LS_SPAN` | `0.8f` | ⭐ 宽高比→居中度的过渡跨度：`ls = clamp((aspect-1)/0.8, 0, 1)` |
| `NARROW_ASPECT` | `1.4f` | ⭐ 低于此宽高比启用外半径钳制 |
| `NARROW_FILL_K` | `0.96f` | ⭐ 钳制时外半径占「天极到最远边」的比例 |

```kotlin
// ⭐ 原型实测定稿：天极按宽高比在「居中 ↔ 右偏 0.70/0.52」之间插值；
// 竖屏/近方形再把外半径钳住，否则星轨飞出屏。
val ls = ((w / h) - 1f) / LS_SPAN
val t = ls.coerceIn(0f, 1f)
val px = w * (0.5f + (POLE_X_K - 0.5f) * t)
val py = h * (0.5f + (POLE_Y_K - 0.5f) * t)
var rOuter = R_OUTER_K * minDim
if (w / h < NARROW_ASPECT) {
    val room = maxOf(px, w - px, py, h - py)      // 天极到最远边的距离
    rOuter = minOf(rOuter, maxOf(rInner + 1f, room * NARROW_FILL_K))
}
```

### 5.3 ⭐ 地景（原型实测后改写，见 §4.5）

| 参数 | 值 | 意义 |
|---|---|---|
| `HORIZON_K` | `0.75f` | ⭐ 地平线 = `0.75h`，地面占**底部 1/4**（初版 `0.66h` = 底部 1/3，用户判「太高」后下调） |
| `RIDGE` | 3 段三次贝塞尔 | ⭐ 起伏山脊（初版是 1 条缓丘），y 为相对 `HORIZON_K·h` 的倍数，负 = 更高 |
| `TREE_H_K` | `0.40f` | ⭐ 枯树高度 = `0.40h` |
| `TREE_TRUNK_HW_BASE/MID/TOP` | `0.032h / 0.026h / 0.011h` | ⭐ 树干半宽：根部外扩 → 中段 → 分叉处（**下粗上细的强烈收分**） |
| `TREE_SPLIT_K` | `0.40f` | ⭐ 分叉高度（占树高） |
| `TREE_LEAN_TOP` | `-0.050·treeH` | ⭐ 树干顶端左倾量 |
| `TREE_LEAN_MID_K` | `0.38f` | ⭐ 中段弯曲系数（比线性中点更偏左 → 微弓） |
| `TREE_BRANCH_REACH` | `0.196h` | ⭐ **实测**最左枝尖伸出树心的距离（用于算树位下限） |
| `TREE_EDGE_GAP` | `0.025w` | ⭐ 枝尖与左边框至少保留的空隙 |
| `TREE_LIMBS` | 5 条（3左/2右，2条二次分叉） | ⭐ 枯枝主干布局（见 §4.5 表） |
| 人物剪影 | **无** | ⭐ **已删除**：初版的成人像、再迭代的小孩，均被判「不好看/看不出」而整体移除 |

**山脊控制点**（相对 `horizonY` 的 `h` 倍数；控制点 x 固定取各段 1/3、2/3）：

| 段 | x0 → x1 | y0 | c1 | c2 | y1 | 形状 |
|---|---|---|---|---|---|---|
| 1 | 0.00 → 0.30 | +0.014 | +0.006 | −0.014 | **−0.020** | 左坡 → 脊顶（在树左侧） |
| 2 | 0.30 → 0.60 | −0.020 | −0.014 | +0.024 | +0.026 | 中部下凹 |
| 3 | 0.60 → 1.00 | +0.026 | +0.018 | −0.002 | −0.006 | 右侧再度抬升 |

> 控制点 x 取 1/3、2/3 ⇒ `x(t)` **关于 t 线性** ⇒ `ridgeY(x)` 直接用 Bernstein 求值，**无需求二次方程根**，且「画路径」与「取基点」用的是同一组控制点，树根必然落在脊线上（见 §4.5 的 `ridgeY`）。

### 5.4 视觉修饰

| 参数 | 值 | 意义 |
|---|---|---|
| `STARFIELD_ALPHA` | `0.55f` | 静止星点最大 alpha |
| `GLOW_BASE` / `GLOW_ENERGY` | `0.30f` / `0.25f` | 天极辉光基础 / 能量增益 |
| `GLOW_PULSE_K` | `0.10f` | ⭐ **v1.3 补**：天极辉光的 `frame.pulse` 呼吸系数（背 §4.6 表最后两行「静音呼吸」，原稿漏了这个常量，§12.4 偏差 ⑧） |
| `SKY_TINT_GAIN` | `1.15f` | 天色映射增益 |
| ⭐ `RING_W` | `1.0f` | dp，底环线宽。**恒定**，⛔ 不再随幅值调制（用户要「细线」）；`× DrawScope.density` 转 px |
| ⭐ `RING_ALPHA` | ⭐ `0.10f` | 底环 alpha（**v1.5：`0.26` → `0.10`**）。**恒定且与幅值无关**（每帧只作 blit 的 alpha；烘焙进位图的颜色不透明）。用户判「轨道线条**可以不明显**」⇒ 圆退到只剩一丝暗示极坐标结构 |
| ⭐ `SPRAY_PER_BAND_FULL/LITE/OFF` | `6 / 5 / 3` | ⭐ **v1.5**：每带的 spray 弧条数，按 `fx.level` 取 ⇒ 总弧数 **384 / 320 / 192**。⛔ 三档必须严格递减（LOW 设备反而更贵 = 反向优化） |
| ⭐ `SPRAY_RADIUS_JITTER` | `0.45f` | ⭐ **v1.5**：半径抖动占「到**最近邻带**的间距」的比例 —— **打散同心圆规整感的唯一来源**。⛔ **必须 < 0.5f**（否则 spray 跨到邻带 ⇒ 半径交叉、读成乱网）；`sprayRadiusFor` 的硬夹紧是第二道保证 |
| ⭐ `SPRAY_CUTOFF` | `0.28f` | ⭐ **v1.5**：活跃门限，`hash < 该值` 的弧不画 ⇒ 实测活跃率 ≈ **0.78**。被剔除的 ≈22% **就是空档的来源**（全画出来又变整齐圆） |
| ⭐ `SPRAY_SWEEP_MIN/MAX_DEG` | `4f` / `26f` | ⭐ **v1.5**：spray 扫掠角区间 ⇒ 长度参差（实测覆盖 4.02°–25.96°） |
| ⭐ `SPRAY_BUCKETS` | `4` | ⭐ **v1.5**：颜色桶数 = **每帧 `drawPath` 次数**（⛔ 越大越贵；4 足够读出「冷蓝/淡白/暖白/品红」） |
| ⭐ `SPRAY_COLORS` | `#8FA4DF`/`#DCE4FF`/`#FFF7EA`/`#C89BE0` | ⭐ **v1.5**：四桶调色板（冷蓝主 / 淡白青 / 暖白 / 柔品红点缀），⛔ 写成 `Color(0xFF…)` 字面量（§12.4 偏差 ⑪） |
| ⭐ `SPRAY_W` | `1.0f` | dp，spray 线宽，单一描边。`× DrawScope.density` 转 px |
| ⭐ `SPRAY_ALPHA_MIN/MAX` | `0.18f` / `0.55f` | ⭐ **v1.5**：场亮度随各柱包络均值呼吸的区间。⚠️ **下界必须 > 0**（静默段也留底噪，否则音乐一停整片场凭空消失） |
| ⭐ `SEG_K` | ⭐ `3` | 亮线段切成几段子弧（「一段一段」）。每子弧 `sweep / SEG_K`，`strokeCap = BUTT` 首尾相接。**v1.5：`4 → 3`**（省下的 64 次 `drawArc` 换成 spray 的 4 次 `drawPath`；斜坡与线宽不动 ⇒ 已认可的观感零回归） |
| ⭐ `SEG_GAMMA` | `1.6f` | 子弧 alpha 爬升指数，**必须 > 1**（凹上升 ⇒ 尾巴早早沉回底环、爬升集中在头两段） |
| ⭐ `SEG_W_MIN` / `SEG_W_GAIN` | `1.0f` / `0.8f` | dp，亮线段线宽 = min + gain·amp ⇒ 全幅 **1.8 dp**（v1.3 是 `LINE_MIN_W=1.2f`/`LINE_W_GAIN=2.2f` = 3.4 dp 且被缓冲放大 2 倍 ⇒ 屏幕上 2.4–6.8 dp，这就是「过粗」）；⛔ 不在 `onEnterContent` 存——那里没有 `Density`（§12.4 偏差 ⑩） |
| `postFx.vignette` | `0.42f` | 暗角（⛔ 须**字面量且不得加类型标注 / 不得写 `get()`**，§3.3） |
| `postFx.grain` | `0.026f` | 噪点（⛔ 同上） |

> ⛔ **v1.4 删除**：缓冲三档 `TRAIL_W_FULL/LITE/OFF`（`1280/960/640`）与 `MIN_TRAIL_PX` —— 随 ping-pong 缓冲一起退场（§4.3）。
> `MAX_TEX_PX = 4096` 保留，但用途从「缓冲/纹理尺寸钳制」收窄为「**底环位图**与 `ProceduralTexture` 的尺寸钳制」。
>
> 全部常量收敛在渲染器文件顶部（`internal companion object`），测试直接引用；**不在 `drawContent` 内出现魔法数**（§九 R6）。
> ⚠️ companion 的 `<clinit>` **会被 JVM 单测触发** ⇒ 里面的 `val` 一律不得调 `Color.toArgb()`
> （其内部走未 mock 的 `android.graphics.Color.argb`）⇒ ARGB 常量写成 `0xFFFFF7EA.toInt()` 字面量（§12.4 偏差 ⑪）。

---

## 六、逐效果优化与几何自检清单

（本案无「多效果批量优化」，此处改为**几何/视觉自检项**，对应 §十 V 系列验收的视觉面）

1. 静音态（`spectrum` 全零）是否保持「自转星场 + 微启呼吸辉光」，而非全黑？（对 §4.6 末行）
2. 单低音持续音（把能量放低频 bin）是否看到**贴天极**的短而亮的密弧？单高音是否**外圈**长而稀的淡弧？（对 §4.2 半径映射）
3. 鼓点是否每次只闪一帧 `frame.beat`、且 `flare` 指数回落不拖第二下？（对 §4.6）
4. 超宽屏（比值 ≥1.9）天极是否内收、星轨不甩出画面？竖屏/方屏是否居中？（对 §4.2 `poleCenterFor`）
5. 星轨是否在水平线处**自然截止**而非穿到地景下方？（对 §4.5.1）
6. 是否与 E17/E29/E40 站一起可一眼区分？（对 §1.4）
7. ⭐ 地面是否只占**底部 1/4**，枯树是否立在**星环左侧的空天**且枝尖不贴边？（对 §5.3 `HORIZON_K`/`treeXFor`）
8. ⭐ 枯枝是否**向上伸展**（而非下垂的「蜘蛛腿」）？是否读作**无叶的树**而非几何塔？（对 §4.5.2）
9. ⭐ 鼓点流星是否与星轨**同心成弧**、绕极一圈无直线肋骨？（对 §4.6.1，若见「鱼刺」即为回归 `lineTo`）
10. ⭐ 画面中**不应出现任何人物剪影**（已裁决删除，出现即为死代码回流）（对 §4.5.2/G10）
11. ⭐ **v1.4 四条真机复核项**（逐条对应 §12.4 ⑮ 的四个缺陷，任一复现即为回归）：① 亮线段线宽是否已细到「线」而不是「带」？② 天极中心是否留有**可见暗核**、不再是一片死白？③ 亮线段是否**连续**（不再读成同心虚线）？④ 底环与亮线段边缘是否**平滑**（不再锯齿）？（对 §4.3）
12. ⭐⭐ **v1.5 两条分布项**（逐条对应 §12.4 ⑯，任一复现即为回归）：① **分布是否还「太均匀」**？—— 判据：同一半径带内不应看到规整等距的弧；近极应明显更密；整片应读作「闪烁的密场」而非「一组同心圆」。② **亮弧是否够多**？—— 判据：底环几乎看不见（只留一丝暗示结构），画面主体是那几百条短弧。（对 §4.3 Pass A′）
13. ⭐ **v1.5 帧率复核**：MEDIUM 档帧率应与 v1.4 实测的 **29.7fps 基本持平**（预算净变化 `−64 drawArc + 4 drawPath`）；若明显掉档，先查是否误把 spray 逐弧绘制了（§4.3 Pass A′′ 的命门）。（对 §十 V4）

---

## 七、单测门禁清单（每类含负向自证）

> 实施时新增 `app/src/test/java/com/nasmusic/tv/visualizer/renderers/StarrySkyTest.kt`，遵循项目三段式（数值段 + 行为段 + 源码段），**绝不**在 JVM 测试里 `new` 渲染器（字段初始化会建 `android.graphics.Path/Bitmap`，`OrbitalStarFieldTest.kt:26-28` 明令禁止）。纯逻辑收敛在渲染器的 `internal companion object`。

### G1 硬计数同步（失败即第一时间暴露）

- `VisualizerThemeTest.kt` 7 处：`28→29`（`:51,57,58,90,96,103`）、`27→28`（`:89`）。
- `FxCoverageScanTest.kt:257` `decls.size >= 28` → `>= 29`。
- 负向自证：改回 28 必须判失败。

### G2 几何纯函数契约（数值段）

- `radiusForBar(0)=R_INNER_K·minDim`、`radiusForBar(63)=R_OUTER_K·minDim`；单调不减；`barCount=1` 不崩；**⛔ 签名只有 `(bar, barCount, minDim)`**（无 `scaleK`，裁决 6）。
- `sweepForAmp(0f)=0`、`sweepForAmp(1f)=MAX_SWEEP_DEG`、`amp≤SILENT_FLOOR→0`、中间单调（**v1.4 未改动**）。
- ⭐ **v1.4 亮线段斜坡**：`segAlphaAt(k)` 严格单调递增（尾→头）、`k=0 ≥ RING_ALPHA`、`k=SEG_K−1 = 1.0`、全部落在 `(0,1]`、`γ>1` ⇒ 尾段低于线性斜坡；⛔ 与 `amp` 无关（签名里没有 `amp`）。
- ⭐ **v1.4 宽度/alpha 边界**：`segWidthPx(amp, 1f)` 在 `amp∈[0,1]` 上恒落在 `[1.0, 1.8]` dp、下界 = `SEG_W_MIN`、上界 = `SEG_W_MIN+SEG_W_GAIN`、越界输入被钳；`ringColorArgb` **不透明** ⇒ 底环实际 alpha 恒为 `RING_ALPHA`。
- ⭐ **v1.4 同环保证**：烘焙侧与亮线侧各自**唯一**的 `radiusForRange` 调用都写死 `RING_BANDS` 作分母；64 个半径严格递增（否则两圈重合 ⇒ 又是叠加热点）。
- ⭐⭐ **v1.5 spray 参数族（G2 数值段，companion 纯函数直调）**：
  - **确定性**：`sprayHash/sprayActiveFor/sprayRadiusFor/sprayPhaseFor/spraySweepFor` 同输入恒同输出；哈希碰撞**不成片**（索引排布 `band·40 + k·5 + n` 单射的退化信号）。
  - **半径夹紧**：每个 `(band,k)` 落在 `锚点 ± J·localGap` 内；**带间不交叉**（`带 i 最外 spray < 带 i+1 最内 spray`，实测最小余量 0.84px）；**确实在抖**（>90% 偏离锚点，否则退回整齐圆）；`localGap` 取两侧较小者、两端有定义、负 gap 被夹到 0。
  - **区间**：`相位 ∈ [0,360)`、`扫掠角 ∈ [4°,26°]`、`桶 ∈ 0..3`；相位高度分散、扫掠参差、**四桶全被用到**。
  - **活跃率**：三档都落在 **0.60–0.85**（实测 ≈0.78）且**确实存在被剔除的弧**（空档）。
  - **场亮度**：`sprayAlphaFor` 单调、落在 `[MIN,MAX]`、越界被钳、`MIN > 0`。
  - **调色板**：长度 = `SPRAY_BUCKETS`、四色**互不相同**、至少一桶冷色（`blue > red`）。
  - **负向**：把 `SPRAY_RADIUS_JITTER` 调到 ≥ 0.5 或去掉 `localGap` 两侧取小 ⇒ 带间交叉断言必须失败。
- `poleCenterFor` 在横屏（16:9 / 超宽 ≥1.9）/ 竖屏 / 方屏四类比值下 x、y 均在画面内；宽高比 →1 时**收敛到正中**（`ls→0` ⇒ `px→0.5w`）；21:9 时接近 `POLE_X_K`。
- `outerRadiusFor`：宽屏（≥1.4）返回 `R_OUTER_K·minDim`；窄屏（<1.4）返回 ≤ 天极到最远边的距离，绝不溢出画面（`px-rOuter ≥ 0` 或至少弧线端点仍在屏内）。
- `treeXFor`：`treeX ≥ TREE_BRANCH_REACH_K·h + TREE_EDGE_GAP_K·w`（**枝展下界恒成立**，窄屏时由下界接管），且 `treeX` 不小于 0。
- 负向：手写错误函数（如 `sweep` 把 `amp` 当线性直接返回、或 `treeXFor` 去掉 `maxOf` 下界）打入应失败。

### G3 帧率无关 + 时基一致性（数值段）

- `poleAngleDeg(nowMs=10000, t0=0)` 与「用 dt 累加 60Hz ×1000 帧」结论同源；`dt=1/30 × 30 帧` vs `dt=1/60 × 60 帧` 到同一 `nowMs` 同值（证明单一时钟推导，与 dt 无关）。
- ⛔ **v1.4 删除**：`decayAlphaFor(0, tau)=0` / `→1` / `tau≤0` 守卫这组断言 —— 纯函数与 `TRAIL_TAU_S` 一起随 ping-pong 退场（§4.3 / §12.4 ⑮）。
- 负向：把 `poleAngleDeg` 改成 `+= ROT*dt` 的累加版后，隔夜 `nowMs` 不同步即失败。

### G4 postFx 覆盖 + 字面量（源码段）

- `FxCoverageScanTest` 通过：本类在 `covered` 名单内，且 `override val postFx = PostFx(…, …)` 含 `>0f` **数字字面量**（§3.3）；若命名常量 → 该门判「未覆盖」。
- 本类既入 `covered`（而非 `exempt`），`stale` 名单检查亦通过。

### G5 三条模板方法不覆盖 + rng 复用 + 时基合规（源码段，`RendererBaseContractTest` 判定）

- 文件内无 `override fun DrawScope.draw(` / `override fun onEnter(` / `override fun onExit(`。
- 无 `ctx.nowMs`（用 `fx.nowMs` / 已记 `t0Ms`）。
- 无 `private val rng = VisualizerRandom()`（用继承 `rng`）。
- `realFxSubclasses()` 能扫到 `StarrySkyRenderer : RendererFx(` 且类头窗口 ≤400 字符（`RendererBaseContractTest:330`）。

### G6 零分配 + 无禁物（源码段，`PerfBudgetContractTest` 判定 + 本效果自加）

- `PerfBudgetContractTest`：draw 可达行内**无字符串模板**、无参数化 `Rect(`、无 `listOf/mutableListOf/mapOf/.map{}。若确需豁免某行，加字面量 `Perf-exempt`（`PerfBudgetContractTest.kt:89-90`）。
- 自加负向检测（本效果 test 内）：文件不含 `RuntimeShader`/`RenderEffect`/`GLSurfaceView`/`BitmapShader`/`BlendMode.Difference`/`RoundedCornerShape`；必须含 `recycle()`、`ProceduralTexture.ensure(`、`tile(ProceduralTexture.Id.STARFIELD)`。
- 负向：把某处静音分支写成 `"star:$i"` 字符串 → 源扫应失败。
- ⭐ **v1.4 新增两条源码门禁**（锁死用户的架构决定，防止静默回流）：
  - **`⑧ 累积缓冲机械必须已删除`**：文件（已剥注释）不得出现 `PorterDuff` / `DST_OUT` / `decayPaint` / `prevCanvas` / `currCanvas` / `TRAIL_TAU_S` / `decayAlphaFor` / `withScale` / `bufScale` / `TRAIL_W_*` / `rebuildBuffers` / `releaseBuffers`；类体不得再声明 `private var prev|curr :`。负向自证：把 DST_OUT / 双缓冲 Canvas / 缓冲缩放 / 缓冲三档片段喂进同一谓词，四条全挂。
  - **`⑧ 弧线必须画在原生分辨率画布上`**：全文无 `withScale` / `scale(` / `saveLayer`；`drawSegments` 必须直接调 `DrawScope.drawArc` 且带 `BlendMode.Plus` 与 `StrokeCap.Butt`；`rebuildRingLayer` 内不得含 `scale` 或 `TRAIL_W_`，必须用 `MAX_TEX_PX` 按画布尺寸裁剪；`drawContent` 恰调一次 `ensureRingLayer`，`rebuildRingLayer` 只允许「1 处声明 + 1 处调用」。
  - ⭐⭐ **`⑧ spray 必须烘焙成 Path 逐帧只画桶`**（v1.5 新增，**本设计的命门**）：`drawContent` / `drawSpray` 内**不得**出现 `addArc(` / `drawArc(` / `arcTo(`（384 次逐弧绘制会把 29.7fps 砍半）；`drawSpray` 必须是「一个 `withTransform` + `rotate(rotDeg, pivot = Offset(poleX, poleY))`（⛔ 不是画布中心）+ 逐桶 `drawPath` + `BlendMode.Plus` + 预烘焙 `style = stroke`」；`rebuildSprayPaths` 的唯一调用点必须是 `ensureSprayLayer`（缓存键含 w/h/density/**perBand** 四项）；烘焙期每段弧**必须先 `moveTo`**（Compose `Path.addArc` 无 `forceMoveTo` 参数）；`releaseResources` 走 `resetSprayPaths`（⛔ `Path` 无 `recycle()`）。负向自证：`noLiveSprayBake` 对「逐帧烘焙」与「逐弧绘制」两份样本都必须判失败。
  - ⭐ **v1.5 `⑧` 零分配判据改为逐行豁免**：放行 `// Perf-exempt:` 标记行，与项目权威门禁 `PerfBudgetContractTest.isExemptLine` 语义一致。存在的理由：`Path.addArc` 收的是**不可变** `androidx.compose.ui.geometry.Rect` ⇒ 烘焙期每弧必构造一次（`PerfBudgetContractTest` 的零分配红线只管 `DrawScope.draw*` 可达的**每帧**路径）。⛔ 不用「整段一刀切」，否则「烘焙期一次性构造」与「每帧路径违规」无法区分，豁免机制形同虚设。
- ⭐ **v1.4 移除的旧断言**：v1.3 要求「必须含 `PorterDuffXfermode(DST_OUT)`」「衰减层必须用 4-float `drawRect`」「缓冲缩放必须带 pivot `0,0`」「不得用无 pivot 的两参 `scale`」—— 四条随缓冲一起作废（与新门禁方向相反）。

### G7 资源回收（源码段）

- `onExitContent` 走 `releaseResources()`，其中对**底环缓存位图** `rings` 调 `recycle()`（try/catch）。⛔ v1.4：**恰好一次** recycle（ping-pong 双缓冲已删，不再是两次）。
- ⭐ **v1.5**：spray 是 `Path`（⛔ **无** `recycle()` 可调 —— 只有位图才有）⇒ 只能 `resetSprayPaths()` 逐桶 `reset()`。门禁断言：`releaseResources` 走 `resetSprayPaths`、且全文**不得**对 `sprayPaths[…]` 调 `recycle()`（API 不存在，真调会编译不过）。
- `onEnterContent` **首行**必须是 `releaseResources()`（`RendererSwapper.sync` 画质切换会重入 `onEnter`）。
- 不出现对 `ProceduralTexture.release()` / `OverlayFx.release()` 的调用（§3.6）。
- 参照 `ProceduralTextureRecycleTest`、`MilkdropTest` 的断言手法。

### G8 复用正确（源码段）

- `ensure()` 调用**不在** `drawContent` 可达行内（只在 `onEnterContent`/尺寸分支）。
- 无新增 `enum class Id` 条目（`ProceduralTexture.Id` 保持不变；如需审计，比对 `Id.entries` 前后一致）。
- 无新增 `VisualizerRandom()` 实例（用继承的 `rng`，§3.5）；无自造的 `AudioSmoother` 参数。

### G9 ⭐ `ridgeY` 与山脊路径同源（数值段，v1.1 新增）

这是「剪影不浮空/不陷地」的**唯一**保证，必须锁死：

- `RIDGE` 每段控制点的 **x 坐标满足 `c1x = x0 + dx/3`、`c2x = x0 + 2dx/3`**（源码断言常量表本身，而非运行时算）。
- 由此 `x(t)` 对 `t` 线性 ⇒ `ridgeY(x)` 用 `t = (x-x0)/(x1-x0)` 直接反解得**精确**命中该段；断言：对每段取 `t ∈ {0, 0.25, 0.5, 0.75, 1}`，`ridgeY(x0 + dx·t)` 与 Bernstein 正算值误差 `< 1e-3 px`。
- **连续性**：段 i 的 `y1` == 段 i+1 的 `y0`（否则脊线出现折角台阶）。
- **边界**：`ridgeY(0)`、`ridgeY(w)` 分别等于首段 `y0`、末段 `y1`；全曲线 `y ∈ [horizonY - 0.020h, horizonY + 0.026h]`，**不越过 `h + 2`**（地面不画到画面外）。
- 负向：把控制点 x 改成 `0.25/0.75`（`x(t)` 不再线性）后，反解断言必须失败——否则 `ridgeY` 会系统性偏离路径。

### G10 ⭐ 枯树 / 流星源码约束（源码段，v1.1 新增）

- **流星必须是圆弧**：`drawMeteors` 里**不得**出现「用 `moveTo/lineTo` 连头尾画流星」的写法；须含 `addArc` 且入参含 `mDir[i]` 决定的方向（§4.6.1）。负向：把流星改回 `lineTo` → 源扫失败。
- **枯枝描边**：枝条绘制须用 `Style.STROKE` + `strokeCap = ROUND` + 逐段衰减线宽，**不得**用堆叠三角形冒充（初版针叶树写法）。
- **无人物剪影**：文件内**不得**出现人形绘制代码（头/肩/腿/`drawChild`/`drawPerson` 等标识）——用户已两轮判失败并要求删除，留死代码等于把已否决的设计带进产品。
- **无圆角 clip**：不得出现 `RoundedCornerShape` / `clipPath` 圆形裁剪（API 22 段错误，`VisualizerStage.kt:750-752`）。
- **枯枝确定性抖动**：resize 后必须**可复现**（同一 seed 同一形状），故抖动只能用确定性哈希（如 `VisualizerRandom` 或 `sin(i)` 哈希），⛔ 不得用 `Math.random()` 式的非确定源（否则每次 resize 树都不一样，且无法写单测）。

---

## 八、逐文件改造清单 + 必须同步的 10 处

| # | 文件 | 改造点 | 风险/备注 |
|---|---|---|---|
| F1 | `data/model/AppSettings.kt` | 枚举追加 `STAR_TRAILS("星空星轨", Tier.ADV, "42", needsParticleBudget = false)`（`WORLD`（`:185`）之后、`:266` 的 `;` 之前），KDoc 与 `WORLD` 同构（含视觉描述 + `Tier.ADV` + **`needsParticleBudget = false` 的理由**：本效果不读 `maxParticles`） | KDoc 内**不得出现 `/*` 字面量**（`RendererBaseContractTest:358-369`） |
| F2 | `data/model/AppSettings.kt:100` | 「27 套」→「29 套」 | 陈腐注释修正，§七 G1 前先改 |
| F3 | `visualizer/VisualizerRendererFactory.kt` | import + `STAR_TRAILS -> StarrySkyRenderer()` | 单行 |
| F4 | **新增** `visualizer/renderers/StarrySkyRenderer.kt` | `class StarrySkyRenderer : RendererFx()`，全文见 §14.3 | 核心 |
| F5 | **新增** `visualizer/renderers/StarrySkyTest.kt` | G1–G8 代码落地 | 三段式 |
| F6 | `test/.../data/model/VisualizerThemeTest.kt` | 7 处计数 +1（§七 G1） | 漏一处必挂 |
| F7 | `test/.../visualizer/fx/FxCoverageScanTest.kt` | `covered` 加 `StarrySkyRenderer` + `decls.size` 29 | §七 G3 |
| F8 | `docs/visualizer-effects-list.md` | 计数行 28 → **29**；ADV 表加 ` E42 | STAR_TRAILS | 星空星轨 |`；版本头 v2.37.6 → v2.38.0 | ✅ |
| F9 | `docs/technical-overview.md` | 新增 `### 10.206`（接在 §10.205 `:11341` 之后） | ✅ 见 §十五 S9 |
| F10 | `CHANGELOG.md` | 当前版本 `### Added` 下加一行（**只写做了什么**，无根因/行号/验证叙述，见 `AGENTS.md` 文风） | ✅ |

---

## 九、风险、降级与红线

| # | 风险 | 等级 | 缓解/兜底 |
|---|---|---|---|
| R1 | ~~API 22 真机对「0.5MP 双缓冲 + 每帧 2 次全屏回绘」的填充率缺口未经实测~~ → ⭐ **v1.4 基本退役** | ~~高~~ **低** | **ping-pong 缓冲整套删除** ⇒ 暴露面由「**每帧 2 趟全屏缓冲回绘**」降为「**无任何缓冲**」，只剩 256 次细弧 `drawArc` + 1 次 1:1 blit + 若干路径。**v1.4 已真机实测 29.7fps**（§十二 ⑯）。⭐ **v1.5 新增 spray 层（每帧 4 次 `drawPath`）并把 `SEG_K` 4 → 3（省 64 次 `drawArc`）⇒ 预算预期持平**，但 ⛔ **v1.5 未在真机复测帧率**。若掉帧，兜底为 `SPRAY_BUCKETS`/`SPRAY_PER_BAND` 降档（⛔ **不是**改回逐弧绘制） |
| R2 | `RendererSwapper.sync` 画质切换重入 `onEnter`，若复用旧位图 → native 堆泄漏/串图 | 中 | `onEnterContent` 首行 `releaseResources()`（E18 同款，§4.8） |
| R3 | API 22 圆角 clip / 大量小 blit 段错误 | 高 | 全程 `Path` 直绘 + `drawCircle`；`postFx.vignette>0` ⇒ `hasFullScreenPass()` 真 ⇒ 基类**不**插入 `drawDamageCoalescer`（`RendererFx.kt:112-113` 的安全网仍兜底） |
| R4 | `IndexOutOfBounds`：`barCount` 异常（LOW 理论 32）或 `spectrum` 尺寸错 | 低 | 恒读 `frame.spectrum.size`，`radiusForBar` 做 `barCount<=1` 守卫 |
| R5 | 夜空变亮过度 → 色彩溢出/观感发灰 | 低 | `skyMix` 硬钳 `[0,1]` + 增益 1.15 保守 |
| R6 | draw 内魔法数泄漏（几何系数/包络时间常数被散落） | 低 | 全部收敛 §五常量表；`PerfBudgetContractTest` + KDoc 复核 |
| R7 | 与 E17/E29/E40 视觉撞脸 | 中 | §1.4 差异化表 + §四 4.5 地景/星轨/天极三件套是唯一标识 |
| R8 | 文档自身陈旧（计数/基线漂移） | 低 | §0 基线块 + 版本表强制；实施后回填实测数 |

⛔ **硬红线**（触犯即推倒重来）：不能在渲染器里写 shader（`RuntimeShader/GLSL/OpenGL/RenderEffect`）；不能覆写 `draw/onEnter/onExit`；不能 `ctx.nowMs`、不能自造 `rng`；不能 `ProceduralTexture.release()`；不能圆角 clip（API 22 段错误）；`PostFx` 不能命名常量（§3.3）。

---

## 十、上机验收清单（可逐条勾 · 真机）

> 验证手段本机仅「编译 + lint + 单测」；**渲染观感只能真机**。设备 `192.168.0.114:5555`（AGENTS.md 已列）。

| # | 验收项 | 命令/方法 | 预期 |
|---|---|---|---|
| V1 | 编译 | `JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew.bat assembleDebug --no-daemon -Pkotlin.compiler.execution.strategy=in-process` | BUILD SUCCESSFUL |
| V2 | 单测全绿 | `./gradlew.bat testDebugUnitTest --no-daemon -Pkotlin.compiler.execution.strategy=in-process` | 0 失败（新 `StarrySkyTest` + 7 处计数同步后） |
| V3 | lint 无新增 Error | `./gradlew.bat lintDebug --no-daemon -Pkotlin.compiler.execution.strategy=in-process` | 无 `NewApi`（新文件 API>22 调用须有守卫） |
| V4 | 真机帧率 | `adb shell dumpsys gfxinfo com.nasmusic.tv framestats`，切到星空星轨跑 30s | 18+ 帧率、无 `Missed Vsync` 雪崩 |
| V5 | 真机画质档 | 面板切 HIGH/MEDIUM/LOW，观察亮线段数量差异（⭐ v1.4：无缓冲分辨率差异，三档只差「亮线段是否隔柱」） | HIGH/MEDIUM 64×4 段、LOW 32×4 段；辉光随 `fx.level` 变化，无崩 |
| V6 | 静音态 | 暂停播放 | 自转星场 + 呼吸辉光，**不全黑** |
| V7 | 单频响应 | 放纯低音/纯高音音源 | 低音贴天极短密、高音外圈长稀（§四 4.2） |
| V8 | 鼓点响应 | 放强鼓点曲 | 极点闪光 + 切向流星，每鼓只闪一帧 |
| V9 | 时基正确 | 切歌/soft-rotate（横竖切换） | 天极角连续无跳变、无漂移加速 |
| V10 | 资源 | 反复进出效果 20 次 | 无 OOM、`dumpsys meminfo` 无斜率增长 |
| V11 | 无 shader 崩溃 | API 22 创维设备跑 10 min | 无 SIGSEGV（v2.5.1 级崩溃不可复现） |
| V12 | 视觉区分度 | 与 E17/E29/E40 并排截图 | 一眼可分（§1.4） |

---

## 十一、裁决记录

| # | 议题 | 裁决 | 理由（可被新证据推翻） |
|---|---|---|---|
| 1 | 档位 + 粒子门控 + 是否门控 `allowFramebuffer` | **`Tier.ADV` + `needsParticleBudget = false` + 不门控** | ① 本效果不读 `maxParticles` ⇒ 照实标 `false` ⇒ `supports()` 恒 true、三档全可选（§3.4，`40ca823` 新门控）；② 门控 `allowFramebuffer` 则默认 MEDIUM 恒无亮线段、效果失去本体（v1.4 更绝对：本效果已无帧缓冲回绘）；③ 掉帧兜底为**降亮线段密度**（LOW 档隔柱 / `SEG_K` 降档）而非改档位 |
| 2 | 旋转实现 | **单一时钟 `fx.nowMs` 推导**，不逐帧累加 | 更强帧率无关 + 零 transform 成本 + 无漂移（§4.2） |
| 3 | 辉光实现 | **天极辉光精灵 + 亮度近似**，不引 `RenderEffect`/全屏糊 | 项目既有「污点近似」惯例 + 无 shader 红线 + 成本可控（§4.5） |
| 4 | 幅值包络 | **不复用 `AudioSmoother`**，自带 `ATTACK_S/RELEASE_S` 时间常数 | 其 `attack=0.35/release=0.06` 语义（越大越慢）与本效果要的 `0.025s/0.24s` **方向相反且数值不等**；本效果需要的是物理时间常数而非相对系数（§5.1） |
| 5 | 柱方位角 | **种子伪随机**（用继承 `rng` 抽一次入 `FloatArray`），非黄金角 | 黄金角在 64 根等距柱上有可察觉规则性（内侧柱挤在小半径时尤其明显）；种子随机仍可复现、可单测（§4.2） |
| 6 | 半径缩放 | **取消 `scaleK`**，`radiusForBar(bar, barCount, minDim)` 不含缩放项 | 缓冲缩放会连带改变半径比例，竖屏下把星环挤扁；v1.4 已无缓冲，直接用外半径钳制（§4.2/§5.2） |
| 7 | 天极位置 | **宽高比自适应 + 窄画幅外半径钳制** | 固定 `0.70/0.52` 在竖屏会把星环甩出屏；`ls` 插值 + `rOuter` 钳制后四类画幅均成立（§5.2，G2 有断言） |

---

## 十二、开发任务清单（可勾选 · 进度追踪）

### 12.1 打勾与回顾规程

- 每完成一项在 `[ ]` 打 `[x]`，并在**本节末尾** `### 12.4 偏差记录` 追加一行（日期 + 偏差 + 处理）。
- 任何「裁决」被推翻或「参数」改动，先回 §十一/§五 改表，再改代码，**不得**只改代码不动文档。

### 12.2 进度总览

- [ ] 阶段 0：本方案评审（§十一 3 裁决确认 / §五参数冻结）
- [ ] 阶段 1：枚举 + 工厂 + 7 计数 + 名单（F1/F2/F3/F6/F7）
- [ ] 阶段 2：`StarrySkyRenderer.kt` 主体（F4）
- [ ] 阶段 3：`StarrySkyTest.kt`（G1–G8，F5）
- [ ] 阶段 4：本机验证（V1–V3）
- [ ] 阶段 5：真机验收（V4–V12）+ 文档回填（F8/F9/F10）

### 12.3 任务清单

- [x] T1.1 枚举 + KDoc（`AppSettings.kt:185` 之后追加）
- [x] T1.2 修正 `AppSettings.kt:100`「27 套」陈腐注释
- [x] T1.3 工厂 `when` 分支 + import
- [x] T1.4 `VisualizerThemeTest` 7 处计数
- [x] T1.5 `FxCoverageScanTest` 名单 + `decls.size`
- [x] T2.1 `StarrySkyRenderer` 骨架（`onEnterContent/onExitContent` + `releaseBuffers`）
- [x] T2.2 天空渐变 + 天极辉光 + STARFIELD 衬底（§4.4/§4.1 步 1–3）
- [x] T2.3 ⭐ **v1.4 已重写**：星轨 = 64 圈整圈细线底环 + 逐柱 4 段亮线段（**ping-pong 拖尾 + 衰减已删除**，§4.3 / §12.4 ⑮）
- [x] T2.4 地景：山脊（`ridgeY` + 3 段三次贝塞尔）+ 枯树（描边枯枝 + 填充树干）+ 地平辉光（步 8–9）。⛔ **不含人物剪影**（§4.5.2）
- [x] T2.5 鼓点闪光 + 切向流星（步 10）
- [x] T2.6 尺寸变化重建（⭐ v1.4：几何 + **底环缓存位图**；画质三档只剩亮线段隔柱，无缓冲分辨率档）
- [x] T3.1 `StarrySkyTest` 数值段（G2/G3）
- [x] T3.2 `StarrySkyTest` 源码段（G4–G8 含负向）
- [x] T4.1 本机构建 + 单测 + lint（V1–V3）
- [ ] T5.1 真机帧率/画质/静音/单频/鼓点（V4–V9）—— ⛔ **未做**
- [ ] T5.2 真机资源/无崩溃/视觉区分（V10–V12）—— ⛔ **未做**
- [x] T5.3 人物剪影是否常驻的视觉终判 —— **已裁决：不实现**（原型两轮验证，见 §十三.2）
- [x] T6.1 `visualizer-effects-list.md` + `technical-overview.md` §10.206 + `CHANGELOG.md`（F8/F9/F10）

> ⚠️ **T5.1 / T5.2 尚未执行**：本轮只做了 V1–V3（本机编译 + 单测 + lint）。
> **渲染观感、API 22 真机帧率均仍未在真机完整验证过**——
> 唯一一次真机运行是 v1.4 之前（ping-pong 架构），且那次运行**直接判了四个缺陷失败**（§12.4 ⑮），
> 现行「底环 + 亮线段」架构**只在本机验证过编译 / lint / 单测**。
> §九 R1 因此虽由「高」降为「低·基本退役」（缓冲已删），**v1.4 已真机实测 29.7fps**；⭐ v1.5 的 spray 层（每帧 4 次 `drawPath`）配 `SEG_K` 4→3（省 64 次 `drawArc`）**预期持平，但仍需 V4/V5 复核**。

### 12.4 偏差记录

> 记法：**方案说** / **实测是** / **改了什么**。v1.2 写的是「（空，待阶段启动后填充）」——
> 实现阶段（2026-10-01/02）一次性填满，其中 ④ 与 ⑨ 是**方案会真的带出 bug** 的两条。

| # | 位置 | 方案说 | 实测是 | 改法 |
|---|---|---|---|---|
| ① | §3.3 / §5.4 / §14.3 `postFx` | `internal override val postFx: PostFx get() = PostFx(vignette = 0.42f, grain = 0.026f)` | **门禁直接判「未覆盖」**：`FxCoverageScanTest.kt:154` 的 `postFxRe` 要求 `postFx` 后紧跟 `\s*=`；类型标注的 `:` 使整条正则**不匹配** ⇒ `coveredByPostFx` 返回 false ⇒ `覆盖名单里的渲染器必须已覆盖后处理` 失败。且是**静默**失效（只 `return false`，不报错） | 改为**无类型标注的初始化器形式** `override val postFx = PostFx(vignette = 0.42f, grain = 0.026f)`（与 `UltraRenderers.kt:81` 的 `MilkdropRenderer` 逐字同形；类型由 initializer 推断，override 沿用基类 `internal`）。§3.3 / §5.4 / §14.3 三处已同步改正，并各加 ⛔ 警示；负向自证入 `StarrySkyTest` ⑧ |
| ② | §4.6.1 流星 | `path.addArc(RectF(...), min(m.ang, tail), max(m.ang, tail), m.dir < 0)` | **编译不过**（两处）：`Path` 无 4 参 `addArc`——带 `forceMoveTo` 的是 `arcTo`；且第 2/3 参是**扫掠角**（相对量）不是**终止角**（绝对量），原型那条 `ctx.arc(…, 起始角, 终止角, anticlockwise)` 搬过来时被当成了扫掠角 | 3 参 `addArc(meteorRect, startRad·DEG_PER_RAD, sweepRad·DEG_PER_RAD)`，`sweepRad = mLen[i] * mDir[i]`（**符号即方向**）、`startRad = mAng[i] - sweepRad`（尾迹在头角反侧）。与原型的 `min/max + anticlockwise` 几何等价，但免掉 `min`/`max` 与 `dir` 的符号耦合，且 `m.dir` 逐字出现在扫掠角里（G10 判据要的「入参含 dir 决定的方向」）。§4.6.1 与 §14.3 已改正 |
| ③ | §4.1 步 2 / §14.3 `drawContent` | `val glow = …` **声明了两次**（步 2 与步 9 各一份） | 同一作用域重名 ⇒ 编译不过 | 只保留一份，放在**拖尾合成之后、地景之前**（与原型 `frame()` 的步 5→6 顺序一致），alpha 由 `GLOW_BASE + GLOW_ENERGY·sectionEnergy + GLOW_PULSE_K·pulse + flare` 一次算出 |
| ④ | §4.2 / §4.8 / §14.3 `t0Ms` | `onEnterContent` 里 `t0Ms = ctx.nowMs`（「与 E18 的 `ctx.nowMs` 用法一致」） | **两重问题，且是会真出错的**：① `RendererBaseContractTest.violationsIn` 对**整个类体**扫 `ctx\.nowMs` ⇒ 判违规；② 即使绕过门控也是真 bug —— `VisualizerStage.kt:193` 在进入瞬间把 `ctx.nowMs` 设为**墙钟** `System.currentTimeMillis()`，而 `frame.timeMs` 是**单调** `SystemClock.uptimeMillis()`（`SpectrumAnalyzer.kt:322`），两者相减 ≈ **−1.7e12 ms** ⇒ `poleAngleDeg` 恒为负、**整个生命周期冻结** | `t0Ms` 改为在 `drawContent` **首帧**由 `fx.nowMs` 惰性记一次（同一时钟域），且 ⛔ 重入 `onEnter`（画质切换）**不复位**。`onEnterContent` 也不再复位 `env` / 流星池 / 柱方位角（否则切画质会看到「画面炸一下」），柱方位角用 `azReady` 标志只抽一次。§14.3 已改正并加 ⛔ 警示 |
| ⑤ | §4.7 / §七 G2「LOW 档 `spectrum.size` = 32」 | 「LOW 档 `VisualQuality.LOW.barCount` = 32，⛔ 恒读 `frame.spectrum.size`」 | **`AudioFrame.spectrum` 恒按 `SpectrumContract.BAR_COUNT` = 64 分配**（`SpectrumRepository.kt:24-25`）；32 是 `VisualQuality.LOW.barCount`，**另一个字段**。⇒ `spectrum.size` 永远是 64，「半柱分支」按原写法不会发生 | 保留「⛔ 恒读 `frame.spectrum.size`、⛔ 不硬编码 64」这条硬约束（`drawNewArcs` 内无字面量 64、无 `BAR_COUNT` 循环上界），LOW 降级改由 **`stride`** 实现：`fx.level == OFF` 时 `stride = 2`（弧线隔柱取样）⇒ 老设备少画一半弧，视觉几乎无损。G2 增补断言显式记录「`BAR_COUNT`=64 而 `LOW.barCount`=32 是两个字段」 |
| ⑥ | §3.2 / G8 `ProceduralTexture.ensure` | 「`ensure()` **只能在 `onEnterContent` / 尺寸变化时调用**」；§14.3 示例写在 `onEnterContent` 内 | `onEnter` 时刻 `ctx.canvasSize` **仍是 `Size.Zero`**（`VisualizerStage.kt:192` 刚用 `Size.Zero` 调过 `renderCtx.update`）⇒ onEnter 内的 `ensure(1,1)` 对全屏纹理毫无意义；转屏 / 软旋转也不重入 `onEnter` ⇒ 只在 onEnter 调用会**漏掉重建** | 唯一调用点放 `rebuildGeometry(w, h)`，由 `onEnterContent` **与** draw 侧「尺寸变化」分支共同到达 —— 正是 `ProceduralTexture.kt:62-64` 自己 KDoc 允许的两个时机。⛔ 绝不进每帧 `drawContent`（G8 断言锁死）。星点 `dstSize` 改用**当前画布**尺寸而非 tile 自身尺寸，兜住 tile 尺寸落后的情况 |
| ⑦ | §4.2 `radiusForBar` vs §5.2 `outerRadiusFor` | `radiusForBar(bar, barCount, minDim)` 内部固定用 `R_INNER_K·minDim` / `R_OUTER_K·minDim` | 该签名**看不见** `outerRadiusFor` 的窄画幅钳制 ⇒ 裁决 7 的钳制成为**死代码**（竖屏 / 方屏星轨照样甩出屏） | `radiusForBar(bar, barCount, minDim)` **逐字保留**（G2 断言的就是这个签名），另加 `radiusForRange(bar, barCount, rInner, rOuter)`，渲染器实际调它并传入**已钳制**的 `rOuter`；G2 增补断言 `radiusForBar ≡ radiusForRange(未钳制)` 锁住两者关系，防止有人日后把渲染器改回调前者 |
| ⑧ | §5.4 表缺项 | §4.6 映射表有「`frame.pulse` → 静音呼吸（辉光微涨落）」一行，但 §5.4 参数表**没有对应常量** | 照抄会在 `drawContent` 里出现魔法数（撞 §九 R6） | 补 `GLOW_PULSE_K = 0.10f` 进 §5.4 与 §14.3 |
| ⑨ | §4.3 缓冲等比缩放 | §14.3 未给缩放代码（隐含「直接用画布坐标画进缓冲」） | 缓冲比画布小 ⇒ 必须缩放。而 `Canvas.scale(sx, sy)` 的**默认轴是缓冲中心**，会把整幅几何平移 `c·(1−s)` ⇒ 弧线与流星**整体偏出缓冲**（只在真机 / 非 1:1 档位看得见，1280 宽以下才有 s<1）；顺带一个编译坑：在 `Canvas` receiver 的 lambda 里 `density` 解析到 `Canvas.getDensity(): Int`，**遮蔽** `DrawScope.density: Float` | `cc.withScale(bufScaleX, bufScaleY, 0f, 0f) { … }`（显式 pivot 原点），且先把 `density` 取到局部量 `strokeDensity` 再进 lambda。G6 增补源码门：必须出现带 pivot 的 `withScale`、⛔ 不得出现无 pivot 的两参 `scale` |
| ⑩ | §4.5 线宽 dp→px | 「`lineW = 1.2f + 2.2f·amp`，dp→px 换算在 `onEnterContent` 存 density」 | `onEnterContent` **拿不到 `Density`**（`RenderContext` 无此字段，引入 `Context`/`DisplayMetrics` 又是新依赖） | `lineWidthPx(amp, density)` 在 draw 期吃 `DrawScope.density`（DrawScope 是 `Density`），逐帧重取（其值恒定，无额外成本） |
| ⑪ | §14.3 调色板常量 | 计划用 Compose `Color` 常量 + `colorFor()` 返回 `Color` | 本效果的拖尾缓冲走 `android.graphics.Canvas/Paint`（`DST_OUT` + `drawBitmap` 都要它），需要 `Int` ARGB；且 companion 的 `<clinit>` **会被任何 JVM 单测触发**（`StarrySkyTest` 直调 `StarrySkyRenderer.RIDGE` 等），而 `Color.toArgb()` 内部调**未 mock** 的 `android.graphics.Color.argb` ⇒ 整个测试类炸掉 | ARGB 常量写成 `0xFFFFF7EA.toInt()` 字面量（⛔ 不用 `toArgb()`），颜色计算走纯算术 `mixArgb(a, b, t)`（⛔ 不碰 `android.graphics`），`Color` 侧另备 `mixColor()` 供渐变构建期用。§14.3 与 §5.4 均加注 |
| ⑫ | §14.3 `ridgeY(x)` | 实例方法，内部读 `size.width` / `size.height` | **测不了** —— JVM 单测不允许构造本渲染器（字段初始化会建 `android.graphics.Path` / `Paint` / `Bitmap` ⇒ "not mocked"）⇒ G9 只能退化成源码扫描，失去「反解与 Bernstein 正算逐点对齐 < 1e-3 px」这条最硬的判据 | 拆成 companion 纯函数 `ridgeYAt(x, w, horizonY, h)` + `ridgeDy(u)`，实例 `ridgeY` 委托之 ⇒ 渲染器与门禁**共用同一份实现**，G9 判的是生产几何。负向自证：把控制点挪到 1/4、3/4 后线性反解必须出现 > 0.5px 偏差（否则这条断言是空转） |
| ⑬ | §14.3 `poleAngleDeg` | `(ROT_DEG_PER_S * dt) % 360f` | `nowMs < t0Ms` 时（时钟回退、或两台设备 t0 不同源）`%` 的结果落在 **(−360, 0]** ⇒ 天极角**反向漂移** | 改用 `deg - floor(deg / 360f) * 360f`（恒归一到 [0,360)）。G3 增补「负时长」断言 |
| ⑭ | §14.7 / §十四 S9 节号 | 目标小节写 `§10.201` | `40ca823` 已把最新小节推到 **§10.205** | 新增 **§10.206**（其余节号引用同步为 §10.206） |
| ⑯ | ⭐ **v1.5 分布层**：§4.1 / §4.3 / §5.4 / §4.7 / §7 G2·G6·G7 / §十四 14.3 | 「星轨 = 64 圈整圈细线底环 + 每圈一段渐变亮线」，分布均匀 | ⭐ **真机（创维 Android 5.1.1）确认 v1.4 的亮线本身完全没问题**（细、不炸、连续、无锯齿，实测 **29.7fps**），但用户判**「星轨分布太均匀了。还有就是轨道线条可以不明显，但画出来的亮弧要更多」** ⇒ 判失败的**不是**形态，而是**密度与规整度**：64 圈整齐同心圆 + 每圈一段亮线 ⇒ 结构上必然「均匀」 | 三处改动：① **底环几乎隐去**（`RING_ALPHA` `0.26 → 0.10`，直接对应「轨道线条可以不明显」）；② ⭐ **新增 spray 喷溅弧场** —— 每带 `SPRAY_PER_BAND` 条静态短弧（6/5/3 ⇒ **384/320/192** 条），全部参数由 `(bandIndex,k)` 的**确定性哈希**给出（半径 ±`SPRAY_RADIUS_JITTER` 0.45 × 最近邻间距 ⇒ 打散同心圆；**28% 被 `SPRAY_CUTOFF` 剔除 ⇒ 空档**；相位逐弧独立；扫掠 4°–26° 参差；4 色桶）；③ **英雄亮线段 `SEG_K` 4 → 3**，省下的 64 次 `drawArc` 换成 spray 的 4 次 `drawPath`（斜坡与线宽不动 ⇒ **已认可的观感零回归**）。⛔ **实现期两个真踩到的坑**：① `Path.addArc(oval, start, sweep)` **没有** `forceMoveTo` 参数（3 参重载，已用 `javap` 核对 1.9.3 签名），默认续接当前轮廓 ⇒ 不先 `moveTo` 会把相邻弧连成横穿全场的**长直线**（形似 §4.6.1 判失败的「鱼刺」）⇒ 烘焙期每段弧前必须 `moveTo` 到自己的起点；② `Path.addArc` 收的是**不可变** `androidx.compose.ui.geometry.Rect` ⇒ 烘焙期每弧必构造一次堆分配，与本类零分配判据的「带参 `Rect(`」命中 ⇒ 改用**逐行豁免**（与 `PerfBudgetContractTest.isExemptLine` 同语义，`⑧` 判据同步改造）。⛔ **spray 必须烘焙**：`rebuildSprayPaths` 只在尺寸/`density`/画质档变化时执行，每帧只有 **1 次 `withTransform` + 4 次 `drawPath`**；逐帧逐弧绘制会把 29.7fps 砍半（`⑧ spray 必须烘焙` 门禁 + `noLiveSprayBake` 负向自证锁死）。**预算净变化 `−64 drawArc + 4 drawPath + 1 withTransform` ⇒ 预期持平或略好，但 ⛔ v1.5 架构只在本机跑过编译/lint/单测，真机帧率与观感尚未复测** |
| ⑮ | ⭐ **v1.4 架构**：§4.1 步 4–7 / §4.3 / §5.1 / §5.4 / §十四 14.3 / §3.1 / §九 R1 | 「拖尾 = 自有 ping-pong 双缓冲 + `DST_OUT` 指数衰减（`TRAIL_TAU_S=0.75s`）+ 缓冲三档 `1280/960/640` + 每帧 `withScale` 把弧线画进降采样缓冲」 | ⭐ **真机（电视）截图暴露四个缺陷**：① 拖尾**过粗**；② 中心**烧成死白**；③ 拖尾读成**同心虚线**；④ 拖尾**锯齿**。逐条归因（**全部是架构问题，不是参数问题**）：① `lineW = 1.2 + 2.2·amp` dp 画进 **960 宽**缓冲再放大 ~2× ⇒ 屏幕上 2.4–6.8 dp；② 自转仅 3.2°/s（≈0.1°/帧）⇒ 每帧新弧与前几十帧**叠在同一角度**，内侧小半径环弧最短 ⇒ 叠得最厚，`BlendMode.Plus` 直接饱和；③ 逐帧短弧 + 指数衰减 ⇒ 老段变暗、新段变亮 ⇒ 同心虚线；④ 960×540 缓冲最近邻放大到 1920×1080 ⇒ 阶梯 | **整套缓冲机械删除**，换成用户口述的形态「用细线画圆，然后线上有一段一段的描出来亮线，前面亮后面逐渐与原来的线一样了」：**Pass A** 64 圈整圈细线底环（`RING_W=1.0`/`RING_ALPHA=0.26`，**原生尺寸**位图烘焙，每帧 1 次 blit）+ **Pass B** 逐柱 `SEG_K=4` 段首尾相接子弧（`strokeCap=Butt`、`BlendMode.Plus`），alpha 自尾向头按 `SEG_GAMMA=1.6` 爬升、线宽 `1.0+0.8·amp` dp（满幅 1.8）。连带：`TRAIL_TAU_S`/`decayAlphaFor`/`TRAIL_W_*`/`decayPaint`/`prevCanvas`·`currCanvas`/`withScale` 全删；`R_INNER_K` 0.055→**0.10**（中心留暗核）；`LINE_MIN_W/LINE_W_GAIN` → `SEG_W_MIN/SEG_W_GAIN`。§4.1/§4.3/§5.1/§5.4/§十四 14.3/§3.1/§4.7/§4.8/§七 G2·G3·G6·G7/§九 R1 全部同步改写。门禁：删除 `decayAlphaFor`/`TRAIL_TAU_S` 断言与 4 条缓冲正向断言，新增两条源码门禁（「累积缓冲机械已删除」「弧线不得进降采样缓冲」）+ 亮线段斜坡/宽度/底环 alpha/同环半径的数值门禁。**§九 R1 由「高」降为「低·基本退役」**（暴露面 = 无缓冲）。⛔ **真机验收仍未做**（V4–V12）——本轮只在本机跑「编译 + lint + 单测」 |

---

## 十三、原型验证记录（⭐ 浏览器原型 `starry-sky-preview.html`）

本方案的**视觉部分已在浏览器原型中实测定稿**，Kotlin 实现应对拍原型，而非重新审美。原型为单文件、零外部依赖、可 `file://` 直接打开。

### 13.1 原型是什么 / 不是什么

| | 原型 | Kotlin 目标 |
|---|---|---|
| 渲染 | Canvas 2D | `DrawScope` + `nativeCanvas`（v1.4：⛔ 无累积缓冲） |
| 音频 | Web Audio `AnalyserNode`（FFT 1024，64 对数频带） | 既有 `AudioFrame`（PCM 通道，64 柱） |
| 缓冲 | 离屏 canvas，`destination-out` 衰减 + `lighter` 叠加 | ⭐ **v1.4 已弃用该做法**（原型即在这一点上误导；§12.4 ⑮）⇒ 改为「原生尺寸底环位图 1:1 blit + 逐段子弧 `BlendMode.Plus`」 |
| 作用 | **只验证视觉**：拖尾质感、配色、地景构图、剪影形态 | 落地为 E42 |

> ⛔ 原型**不是**契约来源：所有「必须怎么写」（`onEnterContent`/`releaseResources`/`recycle`/`postFx` 字面量/门禁）一律以本方案 §三、§七、§十四 为准；原型只提供**数值与形态**。
>
> ⛔ **v1.4**：原型的 `destination-out` + `lighter` 累积做法**未被采纳**（见上表「缓冲」行与 §12.4 ⑮）。`docs/starry-sky-preview.html` **保持原样不动** —— 它是历史原型留痕，不是当前实现的契约来源。

### 13.2 四轮迭代与最终裁决

| 轮次 | 用户反馈 | 处理 | 沉淀到方案 |
|---|---|---|---|
| 初稿 | —— | 针叶树 + 成人像剪影、地面 1/3、金角相位 | v1.0 |
| 第 1 轮 | 「有**鱼刺**的效果，是用短直线合成曲线？直接用短弧线」 | 定位到 `drawMeteors` 用 `moveTo/lineTo` 直线弦画流星尾巴；**星轨本身一直是真 `ctx.arc`**。流星改走同一圆弧 | §4.6.1 硬规则 + G10 |
| 第 1 轮 | 「那棵树太难看」 | 针叶树（3 层纯三角形，1080p 仅 92px）→ 重画 7 层针叶树 | v1.0 §4.5 |
| 第 1 轮 | 「小人也看不出来」 | 成人像放大到 `0.085h`、重画解剖 | v1.0 §4.5 |
| 第 2 轮 | 换参考图（**枯树 + 小孩**） | 针叶树 → **枯树**（无叶虬枝，描边+分叉，`0.40h`）；成人像 → 小孩 | §4.5.2 |
| 第 3 轮 | 「小孩不好看，不要了」「地面降低到画面底部 1/4」「树再往左边挪，放在星环和左侧边的中间」 | **小孩整体删除**；`HORIZON_K` `0.66→0.75`；树位改为从几何算 `max(ringLeft*0.5, 枝展+边距)` | §4.5.1/§4.5.2/§5.3 |

**两条由此产生的硬结论**（写进 KDoc，勿在实现时「顺手优化」掉）：

1. ⛔ **人物剪影不实现**。两版（成人像、小孩）均被判失败。原型给出的教训是：**一个画不好的小人比没有更伤观感**——剪影不像人时，观众读到的是「一个怪东西」，而非「一个人」。参数表因此**不留任何人物常量**，G10 用源码扫描防止死代码回流。
2. ⛔ **地景位置是构图决策，不是随手取值**。`HORIZON_K=0.75`（地面底部 1/4）与树位公式都是用户逐轮调出来的，改动会让星环与地景的相对关系失衡。

### 13.3 原型暴露的两个实现级缺陷（Kotlin 必须避免）

1. **鱼刺**（§4.6.1）：流星用直线弦 → 逐帧留下直线肋骨。
   附带一条原型自查发现的**同类陷阱**：枯枝绘制的「长度预算」曾因符号写反而让所有枝条反向画出（表现为下垂的「蜘蛛腿」）。⇒ Kotlin 实现时**枝条预算的符号方向必须有单测**（G10 源码约束 + G2 数值段可加断言：给定 `budget > 0` 时枝尖 y 必须小于出枝点 y，即**向上伸展**）。
2. **枝展边界的两难**：竖屏（520×900）下 `ringLeft = px - rOuter` 为**负数**——星环本就越过左边界，不存在「环左侧的空天」。原型取舍为**保可见**（树心取下界，绝不出画）。Kotlin 实现须保留这个 `maxOf` 下界（§4.5.2），否则竖屏下树会跑出画面。

### 13.4 对拍方法（实施时）

- 视觉：原型可 `python -m http.server` 起本地（麦克风需安全上下文；演示模式不需要），与 Kotlin 效果**同屏并排截图**比：亮线段扫掠角（`MAX_SWEEP_DEG`）、底环线宽（`RING_W`）、子弧段数（`SEG_K`）、地平线位置（`HORIZON_K`）、树相对星环的位置（`treeXFor`）。⛔ **拖尾长度（`TRAIL_TAU_S`）已随缓冲删除，v1.4 无此对拍项**。
- 数值：§五 参数表即原型常量表（原型文件顶部「参数」块一一对应），逐项核对。
- 原型改动**必须回写本方案**并升版本号（§版本表规矩），否则文档与原型漂移。

---

## 十四、开发实施手册（可开发级）

### 14.1 自检表（开工前勾）

- [ ] 已读完 §2.2 真实契约命名、§2.6 红线、§3 全部复用陷阱
- [ ] 已确认 §十一 3 条裁决、§五 参数表已冻结
- [ ] 已确认 `HEAD=0482399`、无并行改动冲突（§0 基线）

### 14.2 逐文件清单

见 §八 F1–F10 表。

### 14.3 完整接口签名（可直接粘贴）

```kotlin
// app/src/main/java/com/nasmusic/tv/visualizer/renderers/StarrySkyRenderer.kt
package com.nasmusic.tv.visualizer.renderers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.IntSize
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntSize
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.SpectrumContract
import com.nasmusic.tv.visualizer.fx.PostFx
import com.nasmusic.tv.visualizer.fx.ProceduralTexture
import com.nasmusic.tv.visualizer.VisualizerMath
import kotlin.math.exp
import kotlin.math.pow

/**
 * 星空星轨（E42）—— 长曝光星轨照片母题的频谱效果。
 *
 * ⛔ v1.4 渲染红线：纯 DrawScope + **底环 + 亮线段**；⛔ **无** ping-pong 累积缓冲、
 *    ⛔ 无 `DST_OUT`、⛔ 无降采样离屏位图；无 shader、无 RenderEffect。
 * 视觉：深蓝天幕 + 绕天极的细线圆 + 线上逐段描出的亮线 + 近黑地景剪影。
 * 半径 = 频率（低音贴天极）、扫掠角 + 线宽 = 幅值、鼓点 → 极点闪光 + 切向流星、
 * 整体响度 → 天色偏移。
 */
class StarrySkyRenderer : RendererFx() {

    // ⛔ v1.3 更正：⛔ 不得加类型标注、⛔ 不得写 `get()` —— 门禁的 postFxRe 只认
    //    `override val postFx = PostFx(...)`，写成 getter 会**静默**被判「未覆盖」（§3.3）。
    override val postFx = PostFx(vignette = 0.42f, grain = 0.026f)

    // ⭐ v1.4：唯一的离屏位图 = **原生尺寸**的底环缓存（每帧 1:1 blit，⛔ 不 ping-pong）
    private var rings: ImageBitmap? = null
    private var ringW = -1f
    private var ringH = -1f
    private var ringStrokePx = -1f

    /** 烘焙底环用（⛔ 每帧不碰）。`BUTT`：整圈 drawCircle 无端点。 */
    private val ringPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        isAntiAlias = true
    }

    private var t0Ms = 0L
    private var lastW = 0
    private var lastH = 0
    // ⭐ v1.4：无 bufferScaleK（初版有）—— 裁决 6 已取消半径缩放项，v1.4 更直接：底环与亮线段
    //    全部画在**原生分辨率**画布上，无任何缩放；只需外半径钳制
    private var horizonY = 0f
    private val az = FloatArray(SpectrumContract.BAR_COUNT)   // 种子随机的柱方位角（§4.2）
    private val envBuf = FloatArray(SpectrumContract.BAR_COUNT) // 幅值包络
    private val meteorPath = android.graphics.Path()

    private var darkBrush: Brush? = null
    private var brightBrush: Brush? = null
    private var poleX = 0f
    private var poleY = 0f

    override fun onEnterContent(ctx: RenderContext) {
        releaseResources()
        // ⛔ v1.3 更正：⛔ **不要**在此写 `t0Ms = ctx.nowMs`。
        //    ① `RendererBaseContractTest.violationsIn` 对整个类体扫 `ctx.nowMs` ⇒ 判违规；
        //    ② 即使绕过门控也是**真 bug**：`VisualizerStage:193` 在进入瞬间把
        //       `ctx.nowMs` 设为**墙钟** `System.currentTimeMillis()`，而 `frame.timeMs`
        //       是**单调** `SystemClock.uptimeMillis()`（`SpectrumAnalyzer.kt:322`）⇒
        //       两者相减 ≈ −1.7e12 ms ⇒ 天极角恒为负、整个生命周期冻结。
        //    改为在 drawContent 首帧用 `fx.nowMs` 惰性记一次（同一时钟域）。
        // ⚠️ ensure 的唯一调用点在 rebuildGeometry（见下），⛔ 不在 onEnterContent 内。
        // ⚠️ v1.4：底环烘焙**也不在此** —— 这里没有 `Density`，`RING_W`（dp）转不了 px；
        //    改由 draw 侧的 ensureRingLayer 惰性建（同款教训见 §12.4 偏差 ⑩）。
        rebuildSky(ctx)
    }

    override fun onExitContent() { releaseResources() }

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        val w = size.width
        val h = size.height
        if (w != skyW || h != skyH) {
            releaseResources()
            rebuildSky(ctx)          // 渐变 / 山脊 / 枯树 / ensure
        }
        // ⭐ v1.4 底环：命中缓存时只有几次浮点比较，未命中才烘焙（⛔ 不在此无条件重建）
        ensureRingLayer(w, h, density)

        // 1) 天空渐变（双份缓存交叉淡入）
        val m = skyMix(frame.sectionEnergy)
        darkBrush?.let { drawRect(it) }
        if (m > 0f) brightBrush?.let { drawRect(it, alpha = m) }

        // 2) 静止星点（⭐ dstSize 用**当前画布**尺寸而非 tile 自身尺寸：
        //    共享 tile 的键是 (w,h)，转屏后可能仍是旧尺寸，按自身尺寸画会铺不满）
        ProceduralTexture.tile(ProceduralTexture.Id.STARFIELD)?.let {
            drawImage(it, dstSize = IntSize(w.toInt(), h.toInt()), alpha = STARFIELD_ALPHA)
        }

        // 3) 拍点闪光（flare 衰减 + 生成切向流星）
        advanceFlare(frame, fx.dt)

        // 4) ⭐ 底环（原生尺寸缓存位图 ⇒ 1 次 blit 取代 64 次 drawCircle）
        //    ⭐ v1.5：RING_ALPHA 0.10 —— 轨道线只留「隐约的圆」，不与亮弧争视线
        rings?.let {
            drawImage(it, dstSize = IntSize(w.toInt(), h.toInt()),
                alpha = RING_ALPHA, blendMode = BlendMode.Plus,
                filterQuality = FilterQuality.None)   // ⛔ 1:1 直贴不重采样
        }

        // 4′) ⭐⭐ v1.5 spray 喷溅弧场（一次旋转变换 + SPRAY_BUCKETS 次 drawPath）
        //     ⛔⛔ 这里绝不能逐弧 drawArc：弧已烘焙进 sprayPaths，384 次逐弧绘制会砍掉一半帧率
        drawSpray(poleAngleDeg(fx.nowMs, t0Ms), sprayAlphaFor(fieldEnergy()))

        // 5) ⭐ 亮线段 hero（逐柱 SEG_K 段首尾相接子弧，Plus 加性；v1.5 SEG_K = 3）
        drawSegments(frame, fx)

        // 6) 切向流星（⭐ 圆弧，非直线；§4.6.1）
        drawMeteors(drawContext.canvas.nativeCanvas, fx.dt)

        // 7) 天极辉光（缓存精灵，能量 + 拍点闪光 + 静音呼吸；⛔ 只算一次）
        val glow = (GLOW_BASE + GLOW_ENERGY * frame.sectionEnergy
            + GLOW_PULSE_K * frame.pulse + flare).coerceIn(0f, 1f)
        glowBrush?.let {
            drawCircle(it, radius = poleGlowR, center = Offset(poleX, poleY),
                alpha = glow, blendMode = BlendMode.Plus)
        }

        // 8) 地平辉光带
        bandBrush?.let { drawRect(it, topLeft = Offset(0f, bandTop), size = Size(w, bandH),
            blendMode = BlendMode.Plus) }

        // 9) 山脊 + 枯树（不透明，画在星轨之上 → 星轨在地平线处自然截止）
        drawGroundForeground()

        // 10) 后处理由基类 applyPostFx 完成（vignette + grain）
    }

    // ⭐ v1.4：释放「底环位图 + 静态 Path」。⛔ **没有**乒乓双缓冲可释放。
    private fun releaseResources() {
        try { rings?.asAndroidBitmap()?.recycle() } catch (_: Exception) {}
        rings = null; ringW = -1f; ringH = -1f; ringStrokePx = -1f
        ridgePath.reset(); trunkPath.reset(); meteorPath.reset()
        for (i in 0 until limbSegCount) limbPaths[i].reset()
        limbSegCount = 0
    }

    /**
     * ⭐ v1.4 Pass A：把 RING_BANDS 圈**整圈细线**烘焙进**原生尺寸**位图。
     *
     * ⛔ 每帧 64 次 `drawCircle` → 每帧 1 次 blit。
     * ⛔ **原生尺寸**：v1.3 的 960 宽降采样缓冲被最近邻放大就是「锯齿」缺陷的来源。
     * ⛔ 半径分母写死 `RING_BANDS`，与 `drawSegments` 同式 ⇒ 亮线段必落在自己的环上。
     */
    private fun rebuildRingLayer(w: Float, h: Float, density: Float) {
        recycleRingLayer()
        val bw = w.toInt().coerceIn(1, MAX_TEX_PX)
        val bh = h.toInt().coerceIn(1, MAX_TEX_PX)
        if (bw < 2 || bh < 2) return
        val bmp = ImageBitmap(bw, bh)
        val cv = android.graphics.Canvas(bmp.asAndroidBitmap())   // ⛔ 局部变量，不做成员（无需翻转）
        val stroke = RING_W * density
        ringPaint.strokeWidth = stroke
        var i = 0
        while (i < RING_BANDS) {
            ringPaint.color = ringColorArgb(barRatio(i, RING_BANDS))   // 不透明；逐帧 alpha 靠 drawImage
            cv.drawCircle(poleX, poleY, radiusForRange(i, RING_BANDS, rInner, rOuter), ringPaint)
            i++
        }
        rings = bmp; ringW = w; ringH = h; ringStrokePx = stroke
    }

    /** 底环缓存的**唯一**有效性判据（⛔ 每帧只做几次浮点比较，零分配）。`density` 必须参与键。 */
    private fun ensureRingLayer(w: Float, h: Float, density: Float) {
        if (w < 2f || h < 2f) return
        if (rings != null && ringW == w && ringH == h && ringStrokePx == RING_W * density) return
        rebuildRingLayer(w, h, density)
    }

    /**
     * ⭐ v1.4 Pass B：线上逐段描出的**亮线段**（头亮 → 尾沉回底环）。
     *
     * ⛔ **直接**画在 DrawScope 上（原生分辨率 + 抗锯齿），⛔ 不经任何降采样缓冲。
     * ⛔ 柱数恒读 `frame.spectrum.size`；`RING_BANDS` 只准当**半径分母**，不当循环上界。
     */
        /** ⭐ v1.5：把 RING_BANDS × perBand 条 spray 弧按颜色分桶烘焙进 sprayPaths。⛔ 只在尺寸/密度/画质档变化时调用 */
    private fun rebuildSprayPaths(w: Float, h: Float, density: Float, perBand: Int) {
        for (i in 0 until SPRAY_BUCKETS) sprayPaths[i].reset()
        var band = 0
        while (band < RING_BANDS) {
            val anchorR = radiusForRange(band, RING_BANDS, rInner, rOuter)
            val gap = localGapFor(band, RING_BANDS, rInner, rOuter)
            var k = 0
            while (k < perBand) {
                if (sprayActiveFor(band, k)) {
                    val r = sprayRadiusFor(band, k, anchorR, gap)
                    val phase = sprayPhaseFor(band, k)
                    val p = sprayPaths[sprayBucketFor(band, k)]
                    // ⛔ moveTo 到本弧自己的起点（= 强制新轮廓）：Compose 的 Path.addArc
                    //    没有 forceMoveTo 参数，不先 moveTo 会与上一段连线成横穿全场的直线
                    val rad = phase * DEG_PER_RAD_INV
                    p.moveTo(poleX + r * cos(rad), poleY + r * sin(rad))
                    // ⚠️ addArc 收的是**不可变** geometry.Rect ⇒ 烘焙期每弧构造一次（非每帧路径）
                    val oval = Rect(poleX - r, poleY - r, poleX + r, poleY + r) // Perf-exempt: 烘焙期一次性构造
                    p.addArc(oval, phase, spraySweepFor(band, k))
                }
                k++
            }
            band++
        }
        sprayStroke = Stroke(width = SPRAY_W * density, cap = StrokeCap.Butt)
        sprayW = w; sprayH = h; sprayStrokePx = SPRAY_W * density; sprayPerBand = perBand
    }

    /** ⭐ v1.5 spray 的唯一有效性判据（⛔ perBand 也必须在键里：画质切换必须重烘焙） */
    private fun ensureSprayLayer(w: Float, h: Float, density: Float, perBand: Int) {
        if (w < 2f || h < 2f) return
        if (sprayStroke != null && sprayW == w && sprayH == h &&
            sprayStrokePx == SPRAY_W * density && sprayPerBand == perBand
        ) return
        rebuildSprayPaths(w, h, density, perBand)
    }

    /**
     * ⭐⭐ v1.5：整片 spray 场在**一个**旋转变换下画完。
     * ⛔ 这里绝不能出现 drawArc / addArc —— 384 次逐弧绘制会把真机 29.7fps 砍半。
     * ⚠️ 变换绕**天极**而非画布中心：spray 弧不在同心圆上，绕中心转会把整片场平移出去。
     */
    private fun DrawScope.drawSpray(rotDeg: Float, alpha: Float) {
        val stroke = sprayStroke ?: return
        withTransform({ rotate(rotDeg, pivot = Offset(poleX, poleY)) }) {
            var b = 0
            while (b < SPRAY_BUCKETS) {
                drawPath(sprayPaths[b], sprayColors[b], alpha = alpha, style = stroke,
                    blendMode = BlendMode.Plus)
                b++
            }
        }
    }

    /** 各柱包络均值（0..1）⇒ 驱动 spray 场亮度；⛔ 必须在 drawSegments **之后**读 */
    private fun fieldEnergy(): Float {
        var sum = 0f
        var i = 0
        while (i < RING_BANDS) { sum += env[i]; i++ }
        return sum / RING_BANDS
    }

    private fun DrawScope.drawSegments(frame: AudioFrame, fx: FxFrame) {
        val spec = frame.spectrum
        val avail = if (spec.size < RING_BANDS) spec.size else RING_BANDS
        if (avail <= 0) return
        val stride = if (fx.level == FxLevel.OFF) LOW_ARC_STRIDE else 1
        val rotDeg = poleAngleDeg(fx.nowMs, t0Ms)
        val strokeDensity = density
        var slot = 0
        while (slot < avail) {
            val cur = envelopeStep(env[slot], spec[slot], fx.dt)
            env[slot] = cur
            val sweep = sweepForAmp(cur)
            if (sweep > 0f) {
                val r = radiusForRange(slot, RING_BANDS, rInner, rOuter)
                val head = az[slot] * DEG_PER_RAD + rotDeg     // ⭐ 头在天极角上
                val sub = sweep / SEG_K
                val tint = barRatio(slot, RING_BANDS)
                // ⚠️ `Stroke.width` 不可变 ⇒ 每柱新建一个（`StrokeCap.Butt` 让子弧首尾相接无缝）
                val stroke = Stroke(width = segWidthPx(cur, strokeDensity), cap = StrokeCap.Butt)
                var k = 0
                while (k < SEG_K) {
                    drawArc(
                        color = Color(segColorArgb(tint, segAlphaAt(k))),
                        startAngle = head - sweep + sub * k,
                        sweepAngle = sub,
                        useCenter = false,
                        topLeft = Offset(poleX - r, poleY - r),
                        size = Size(r * 2f, r * 2f),
                        alpha = 1f,
                        style = stroke,
                        blendMode = BlendMode.Plus,
                    )
                    k++
                }
            }
            slot += stride
        }
    }

    /** 幅值包络：快攻 0.025s / 慢放 0.24s。⛔ 不复用 AudioSmoother（见 §5.1 裁决 4） */
    private fun envelope(cur: Float, target: Float, dt: Float): Float {
        val tau = if (target > cur) ATTACK_S else RELEASE_S
        return cur + (target - cur) * (1f - exp(-dt / tau))
    }

    /**
     * ⭐ 山脊基线高度：**纯函数**（与画路径同源）。
     *
     * ⭐ v1.3：⛔ 不要写成读 `size.width` / `size.height` 的实例方法。
     *    那样它就**测不了**（JVM 单测不允许构造本渲染器——字段初始化会建
     *    `android.graphics.Path` / `Paint` / `Bitmap` ⇒ "not mocked"），
     *    §七 G9 只能退化成源码扫描、失去「反解与正算逐点对齐」这条最硬的判据。
     *    拆成 companion 的 `ridgeYAt(x, w, horizonY, h)` + `ridgeDy(u)` 后，
     *    渲染器与门禁**共用同一份实现**，G9 断言才是真的在判生产几何。
     */
    private fun ridgeY(x: Float): Float = ridgeYAt(x, skyW, horizonY, skyH)

    // —— 以下纯函数在 internal companion object 中，供测试直调（G2/G3/G9） ——

    companion object {

        // —— ⭐ v1.5 Spray（喷溅弧场）——
        const val SPRAY_PER_BAND_FULL = 6   // HIGH：64 × 6 = 384 条
        const val SPRAY_PER_BAND_LITE = 5   // MEDIUM：64 × 5 = 320 条
        const val SPRAY_PER_BAND_OFF = 3    // LOW：64 × 3 = 192 条
        /** ⛔ 必须 < 0.5f：抖动幅度小于半间距 ⇒ 带间半径顺序恒不交叉 */
        const val SPRAY_RADIUS_JITTER = 0.45f
        /** 活跃门限：`hash < 该值` 的弧不画（实测活跃率 ≈ 0.78）⇒ 被剔除的就是空档 */
        const val SPRAY_CUTOFF = 0.28f
        const val SPRAY_SWEEP_MIN_DEG = 4f  // 最短也要看得见一小段
        const val SPRAY_SWEEP_MAX_DEG = 26f // 长度参差
        const val SPRAY_BUCKETS = 4         // = 每帧 drawPath 次数
        const val SPRAY_W = 1.0f            // dp
        const val SPRAY_ALPHA_MIN = 0.18f   // ⛔ 必须 > 0（静默段也留底噪）
        const val SPRAY_ALPHA_MAX = 0.55f
        /** ⭐ v1.5 四桶调色板：⛔ 写成 `Color(0xFF…)` 字面量（`Color.toArgb()` 走未 mock 的 `android.graphics`） */
        val SPRAY_COLORS = arrayOf(
            Color(0xFF8FA4DF),  // 冷蓝（主色）
            Color(0xFFDCE4FF),  // 淡白青
            Color(0xFFFFF7EA),  // 暖白
            Color(0xFFC89BE0),  // 柔品红（点缀）
        )

        /** 每带几条 spray 弧，按画质档。⛔ 是 `ensureSprayLayer` 的缓存键之一 */
        fun sprayPerBandFor(level: FxLevel): Int = when (level) {
            FxLevel.FULL -> SPRAY_PER_BAND_FULL
            FxLevel.LITE -> SPRAY_PER_BAND_LITE
            FxLevel.OFF -> SPRAY_PER_BAND_OFF
        }

        /**
         * ⭐ `(bandIndex, k)` 的第 `n` 条独立伪随机流（`n ∈ 0..4`）⇒ `[0,1)`。
         *
         * ⛔ 确定性哈希（[hash01] 同款 `sin(i)·43758.5453`），⛔ 不得用 `Math.random()`
         *    —— 那样每次 resize 场都不同、且**无法写单测**。
         * ⚠️ 索引排布 `band·40 + k·5 + n` 是**单射**的（`k·5+n ≤ 29 < 40`）。
         */
        fun sprayHash(bandIndex: Int, k: Int, n: Int): Float = hash01(bandIndex * 40 + k * 5 + n)

        fun sprayActiveFor(bandIndex: Int, k: Int): Boolean = sprayHash(bandIndex, k, 0) >= SPRAY_CUTOFF

        /**
         * ⭐ 本带到**最近邻带**的半径间距（两侧取较小者 —— 半径向外递增 ⇒ 间距向外递减）。
         *
         * 取较小者才能保证「本带最外侧弧」落在「下一带最内侧弧」之内 ⇒ 半径顺序恒不交叉。
         */
        fun localGapFor(bandIndex: Int, barCount: Int, rInner: Float, rOuter: Float): Float {
            if (barCount <= 1) return 0f
            val cur = radiusForRange(bandIndex, barCount, rInner, rOuter)
            val gapIn = if (bandIndex > 0) cur - radiusForRange(bandIndex - 1, barCount, rInner, rOuter) else Float.MAX_VALUE
            val gapOut = if (bandIndex < barCount - 1) radiusForRange(bandIndex + 1, barCount, rInner, rOuter) - cur else Float.MAX_VALUE
            val m = if (gapIn < gapOut) gapIn else gapOut
            return if (m <= 0f || m == Float.MAX_VALUE) 0f else m
        }

        /** ⭐ spray 半径 = 锚点 ± `SPRAY_RADIUS_JITTER × localGap`，⛔ 硬夹紧（不交叉的第二道保证） */
        fun sprayRadiusFor(bandIndex: Int, k: Int, bandRadius: Float, localGap: Float): Float {
            val span = localGap.coerceAtLeast(0f) * SPRAY_RADIUS_JITTER
            val d = (sprayHash(bandIndex, k, 1) - 0.5f) * 2f * span
            return (bandRadius + d).coerceIn(bandRadius - span, bandRadius + span)
        }

        fun sprayPhaseFor(bandIndex: Int, k: Int): Float = sprayHash(bandIndex, k, 2) * 360f

        fun spraySweepFor(bandIndex: Int, k: Int): Float =
            SPRAY_SWEEP_MIN_DEG + sprayHash(bandIndex, k, 3) * (SPRAY_SWEEP_MAX_DEG - SPRAY_SWEEP_MIN_DEG)

        fun sprayBucketFor(bandIndex: Int, k: Int): Int =
            (sprayHash(bandIndex, k, 4) * SPRAY_BUCKETS).toInt().coerceIn(0, SPRAY_BUCKETS - 1)

        /** ⭐ 场亮度随整体能量呼吸；⛔ 下界不为 0（静默段也留底噪，否则整片场凭空消失） */
        fun sprayAlphaFor(energy: Float): Float =
            SPRAY_ALPHA_MIN + (SPRAY_ALPHA_MAX - SPRAY_ALPHA_MIN) * energy.coerceIn(0f, 1f)
        // —— 核心物理（§5.1）——
        internal const val ROT_DEG_PER_S = 3.2f
        internal const val FLARE_DECAY_S = 0.18f
        internal const val MAX_SWEEP_DEG = 46f
        internal const val SILENT_FLOOR = 0.012f
        /** ⭐ v1.4：0.055 → 0.10（中心留暗核，避免最内圈压在极点上被烧白） */
        internal const val R_INNER_K = 0.10f
        internal const val R_OUTER_K = 0.78f
        internal const val RADIUS_SHAPE = 0.72f
        internal const val COLOR_SHAPE = 0.55f
        internal const val ATTACK_S = 0.025f
        internal const val RELEASE_S = 0.24f
        /** ⭐ 底环圈数 = 柱容量。⛔ **不是**每帧循环上界（恒读 spectrum.size）；是底环/亮线段共用的半径分母 */
        internal const val RING_BANDS = SpectrumContract.BAR_COUNT
        // —— 底环（圆）——
        internal const val RING_W = 1.0f         // dp，恒定（⛔ 不随幅值）
        internal const val RING_ALPHA = 0.10f    // ⭐ v1.5：0.26 → 0.10（圆几乎隐去）
        // —— 亮线段（线上逐段描出）——
        internal const val SEG_K = 3            // ⭐ v1.5：4 → 3（省 64 次 drawArc）；⛔ 必须 ≥ 3
        internal const val SEG_GAMMA = 1.6f     // ⛔ 必须 > 1（凹上升）
        internal const val SEG_W_MIN = 1.0f     // dp
        internal const val SEG_W_GAIN = 0.8f    // dp（满幅 1.8dp）
        // ⛔ v1.4：`ALPHA_BASE` 已删除（底环即底色，亮线段只叠加自己的 segAlphaAt 斜坡）

        // —— 天极 / 画幅自适应（§5.2）——
        internal const val POLE_X_K = 0.70f
        internal const val POLE_Y_K = 0.52f
        internal const val LS_SPAN = 0.8f
        internal const val NARROW_ASPECT = 1.4f
        internal const val NARROW_FILL_K = 0.96f

        // —— 地景（§5.3）——
        internal const val HORIZON_K = 0.75f
        internal const val TREE_H_K = 0.40f
        internal const val TREE_SPLIT_K = 0.40f
        internal const val TREE_LEAN_TOP_K = 0.050f
        internal const val TREE_LEAN_MID_K = 0.38f
        internal const val TREE_HW_BASE_K = 0.032f
        internal const val TREE_HW_MID_K = 0.026f
        internal const val TREE_HW_TOP_K = 0.011f
        internal const val TREE_BRANCH_REACH_K = 0.196f
        internal const val TREE_EDGE_GAP_K = 0.025f
        internal const val TREE_NOTCH_K = 0.003f        // 叉口收窄量
        internal const val TREE_BASE_SINK = 2f          // 树根埋入脊线的深度（px）
        // —— 枯枝（§4.5.2）——
        internal const val LIMB_W_K = 0.012f            // 主枝起始粗细（h 倍数）
        internal const val LIMB_INSET_K = 0.010f        // 出枝点内埋量（h 倍数）
        /** ⭐ 「向上伸展」的唯一保证：每段把角度往竖直（0）收回。⛔ 必须 > 0 */
        internal const val LIMB_PULL_BACK = 0.90f
        internal const val LIMB_JITTER = 0.7f
        internal const val LIMB_BOW = 0.18f
        internal const val LIMB_SEGS = 4
        internal const val LIMB_MIN_W = 0.7f            // 线宽下限（px）
        internal const val LIMB_TAPER = 0.72f           // 逐段衰减系数
        internal const val LIMB_CURL = 0.42f
        internal const val LIMB_FORK_SPREAD_BASE = 0.42f
        internal const val LIMB_FORK_SPREAD_SPAN = 0.20f
        internal const val LIMB_FORK_LEN_K = 0.58f
        internal const val LIMB_FORK_LEN_T = 0.86f
        internal const val LIMB_FORK_W_A = 0.58f
        internal const val LIMB_FORK_W_B = 0.52f
        internal const val LIMB_FORK_CURL = 1.2f
        /** 枝条可用上升高度下界；⛔ 必须 ≥ 0（符号写反 ⇒ 全枝反向画出，读成「蜘蛛腿」） */
        internal const val TREE_BUDGET_MIN = 0f
        /** 5 条主枝（3 左 / 2 右）：角度(度) / 长度系数 / 分叉层数 / 种子 / 出枝高度 at */
        internal val LIMBS = arrayOf(
            Limb(20f, 0.95f, 0, 11, 0.00f),
            Limb(36f, 0.80f, 1, 23, 0.00f),
            Limb(-44f, 0.66f, 1, 37, 0.05f),
            Limb(54f, 0.34f, 0, 51, 0.48f),
            Limb(-32f, 0.30f, 0, 67, 0.66f),
        )

        // —— 视觉修饰（§5.4）——
        internal const val STARFIELD_ALPHA = 0.55f
        internal const val GLOW_BASE = 0.30f
        internal const val GLOW_ENERGY = 0.25f
        internal const val GLOW_PULSE_K = 0.10f        // ⭐ v1.3 补：frame.pulse 呼吸
        internal const val SKY_TINT_GAIN = 1.15f
        internal const val POLE_GLOW_R_K = 0.55f    // 天极辉光精灵半径 = 外半径 × 本值
        internal const val BAND_UP_K = 0.16f        // 地平辉光带上沿（h 倍数）
        internal const val BAND_DOWN_K = 0.04f
        internal const val BAND_PEAK_A = 0.42f
        internal const val GROUND_OVERHANG = 2f      // 山脊多画到画面下方（px，防底边发丝缝）
        /** ⛔ v1.4：`LINE_MIN_W`/`LINE_W_GAIN` 已随缓冲删除 → 改 `SEG_W_MIN`/`SEG_W_GAIN`（见「亮线段」段） */
        /** ⛔ v1.4：缓冲三档 `TRAIL_W_FULL/LITE/OFF` 与 `MIN_TRAIL_PX` 已随 ping-pong 删除 */
        internal const val MAX_TEX_PX = 4096         // ⭐ 用途收窄为「底环位图 + ProceduralTexture」尺寸钳制
        internal const val LOW_ARC_STRIDE = 2       // LOW 档（FxLevel.OFF）亮线段隔柱步长
        internal const val LIMB_SEG_MAX = 48        // 4 + 12 + 12 + 4 + 4 = 36，留余量
        internal const val TAU = 6.2831855f
        internal const val DEG_PER_RAD = 57.29578f
        /** 度 → 弧度的倒数（spray 把「相位(度)」换算成 moveTo 的弧点坐标时用） */
        internal const val DEG_PER_RAD_INV = 0.01745329f

        // —— 流星（§4.6.1）——
        internal const val METEOR_MAX = 40
        internal const val METEOR_MIN = 2
        internal const val METEOR_SPREAD = 2
        internal const val METEOR_SPD_MIN = 1.6f
        internal const val METEOR_SPD_MAX = 4.2f
        internal const val METEOR_LEN_MIN = 0.12f   // 弧跨度（rad）7°
        internal const val METEOR_LEN_MAX = 0.34f   // 19.5°
        internal const val METEOR_LIFE_MIN = 0.28f
        internal const val METEOR_LIFE_MAX = 0.58f
        internal const val METEOR_WARM_P = 0.35f
        internal const val METEOR_DRIFT = 0.10f
        internal const val METEOR_W_MIN = 1.0f
        internal const val METEOR_W_GAIN = 1.6f
        internal const val METEOR_FADE_K = 0.85f

        internal val TRAIL_CORE = Color(0xFFFFF7EA)
        internal val TRAIL_COOL = Color(0xFF8FA4DF)
        internal val SKY_TOP = Color(0xFF0A1026)
        internal val SKY_UPPER = Color(0xFF16255A)
        internal val SKY_MID = Color(0xFF2C3E82)
        internal val SKY_LOWER = Color(0xFF56659F)
        internal val SKY_GLOW = Color(0xFF8D97C6)
        internal val GROUND = Color(0xFF0B0B12)
        internal val POLE = Color(0xFF9FB0E8)

        // ⛔ v1.3：ARGB 常量写成 `.toInt()` 字面量，⛔ **不得**用 `Color.toArgb()` ——
        //    后者内部调未 mock 的 `android.graphics.Color.argb`，companion 的 <clinit>
        //    会被任何 JVM 单测触发 ⇒ 整个测试类炸掉（§12.4 偏差 ⑪）。
        internal val TRAIL_CORE_ARGB: Int = 0xFFFFF7EA.toInt()
        internal val TRAIL_COOL_ARGB: Int = 0xFF8FA4DF.toInt()
        internal val POLE_ARGB: Int = 0xFF9FB0E8.toInt()
        internal val GROUND_ARGB: Int = 0xFF0B0B12.toInt()
        internal val METEOR_COLD_ARGB: Int = mixArgb(TRAIL_CORE_ARGB, POLE_ARGB, 0.5f)

        /** 山脊控制点：x0→x1 区间，y* 为相对 horizonY 的 h 倍数，负 = 更高（§5.3） */
        internal class RidgeSeg(
            val x0: Float, val x1: Float,
            val y0: Float, val c1: Float, val c2: Float, val y1: Float,
        )
        /** 一条主枝：角度(度) / 长度系数 / 分叉层数 / 抖动种子 / 出枝高度 at(0=叉口,1=树根) */
        internal class Limb(
            val deg: Float, val len: Float, val depth: Int, val seed: Int, val at: Float,
        )
        internal val RIDGE = arrayOf(
            RidgeSeg(0.00f, 0.30f,  0.014f,  0.006f, -0.014f, -0.020f), // 左坡 → 脊顶
            RidgeSeg(0.30f, 0.60f, -0.020f, -0.014f,  0.024f,  0.026f), // 中部下凹
            RidgeSeg(0.60f, 1.00f,  0.026f,  0.018f, -0.002f, -0.006f), // 右侧抬升
        )

        /** ⭐ 天极中心：宽高比自适应（横屏右偏 0.70/0.52，窄画幅收回中心） */
        internal fun poleCenterFor(w: Float, h: Float, out: FloatArray) {
            val t = (((w / h) - 1f) / LS_SPAN).coerceIn(0f, 1f)
            out[0] = w * (0.5f + (POLE_X_K - 0.5f) * t)
            out[1] = h * (0.5f + (POLE_Y_K - 0.5f) * t)
        }

        /** ⭐ 外半径：窄画幅钳制，防星轨飞出屏 */
        internal fun outerRadiusFor(w: Float, h: Float, px: Float, py: Float, minDim: Float): Float {
            val base = R_OUTER_K * minDim
            if (w / h >= NARROW_ASPECT) return base
            val room = maxOf(px, w - px, py, h - py)
            return minOf(base, maxOf(R_INNER_K * minDim + 1f, room * NARROW_FILL_K))
        }

        /** ⭐ 枯树 x：环外左侧空天的中点，带枝展/边距下界（§4.5.2） */
        internal fun treeXFor(px: Float, rOuter: Float, w: Float, h: Float): Float {
            val ringLeft = px - rOuter
            val floor = TREE_BRANCH_REACH_K * h + TREE_EDGE_GAP_K * w
            return maxOf(ringLeft * 0.5f, floor)
        }

        /**
         * 天极时刻角（度）：单一时钟推导，⛔ **不是**逐帧 `+ω·dt` 累加。
         *
         * ⭐ v1.3：取模改用 `floor` —— `%`/`toInt()` 在 `nowMs < t0Ms`（时钟回退、
         *    或两台设备 t0 不同源）时结果落在 (−360, 0]，角度会**反向漂移**。
         */
        internal fun poleAngleDeg(nowMs: Long, t0Ms: Long): Float {
            val deg = ROT_DEG_PER_S * ((nowMs - t0Ms) / 1000f)
            return deg - floor(deg / 360f) * 360f
        }

        /** ⭐ 无 scaleK：半径只由柱序号 + minDim 决定（§4.2 裁决 6） */
        internal fun radiusForBar(bar: Int, barCount: Int, minDim: Float): Float =
            radiusForRange(bar, barCount, R_INNER_K * minDim, R_OUTER_K * minDim)

        /**
         * ⭐ v1.3 新增：半径 = `rInner + (rOuter − rInner) · t^[RADIUS_SHAPE]`。
         *
         * ⛔ [radiusForBar] 吃 `minDim` ⇒ **看不见** [outerRadiusFor] 的窄画幅钳制
         *    ⇒ 裁决 7 的钳制会成为死代码。渲染器必须调本函数并传入**已钳制**的 `rOuter`；
         *    [radiusForBar] 保持逐字不变只是为了满足 §七 G2 的签名断言。
         *    G2 已加断言 `radiusForBar ≡ radiusForRange(未钳制)` 锁住两者关系。
         */
        internal fun radiusForRange(bar: Int, barCount: Int, rInner: Float, rOuter: Float): Float {
            if (barCount <= 1) return rInner
            val t = (bar.toFloat() / (barCount - 1)).coerceIn(0f, 1f)
            return rInner + (rOuter - rInner) * t.pow(RADIUS_SHAPE)
        }

        internal fun sweepForAmp(amp: Float): Float {
            if (amp <= SILENT_FLOOR) return 0f
            val n = ((amp - SILENT_FLOOR) / (1f - SILENT_FLOOR)).coerceIn(0f, 1f)
            return MAX_SWEEP_DEG * n
        }

        /** 柱序号比（单柱取 0） */
        internal fun barRatio(bar: Int, barCount: Int): Float =
            if (barCount <= 1) 0f else (bar.toFloat() / (barCount - 1)).coerceIn(0f, 1f)

        // ⛔ v1.4：`decayAlphaFor` 已删除（只服务于 ping-pong 的 DST_OUT 指数衰减；§12.4 ⑮）

        /** 极点闪光每帧保留比例（τ ≤ 0 守卫：单帧归零，避免 NaN） */
        internal fun flareDecayFor(dt: Float): Float =
            if (FLARE_DECAY_S <= 0f) 0f else exp(-dt.coerceIn(0f, MAX_DT_S) / FLARE_DECAY_S)

        /** ⭐ 幅值包络（快攻 / 慢放，dt 化）—— ⛔ 不复用 AudioSmoother（裁决 4） */
        internal fun envelopeStep(cur: Float, target: Float, dt: Float): Float {
            val tau = if (target > cur) ATTACK_S else RELEASE_S
            return cur + (target - cur) * (1f - exp(-dt.coerceIn(0f, MAX_DT_S) / tau))
        }

        internal fun skyMix(sectionEnergy: Float): Float =
            (sectionEnergy * SKY_TINT_GAIN).coerceIn(0f, 1f)

        /** ⭐ v1.4 亮线段线宽（px）：`(SEG_W_MIN + SEG_W_GAIN · amp)` dp × DrawScope.density */
        internal fun segWidthPx(amp: Float, density: Float): Float =
            (SEG_W_MIN + SEG_W_GAIN * amp.coerceIn(0f, 1f)) * density

        /**
         * ⭐ v1.4 第 k 段子弧（k=0 最尾、k=SEG_K−1 最头）的 alpha —— **恒定爬升**。
         *
         * ⛔ **与 amp 无关**（幅值只调线宽）⇒ 同一柱的 4 段不会帧间跳变。
         */
        internal fun segAlphaAt(k: Int): Float {
            val t = ((k + 1).toFloat() / SEG_K).coerceIn(0f, 1f)
            return RING_ALPHA + (1f - RING_ALPHA) * t.pow(SEG_GAMMA)
        }

        /** ⭐ v1.4 亮线段颜色：暖白 → 冷蓝按 t^0.55，alpha 由 segAlphaAt 给 */
        internal fun segColorArgb(t: Float, alpha: Float): Int =
            setArgbAlpha(mixArgb(TRAIL_CORE_ARGB, TRAIL_COOL_ARGB, t.coerceIn(0f, 1f).pow(COLOR_SHAPE)),
                (alpha.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255))

        /** ⭐ v1.4 底环颜色：**不透明**（alpha=255）；逐帧 RING_ALPHA 全由 drawImage(alpha=) 施加 */
        internal fun ringColorArgb(t: Float): Int = segColorArgb(t, 1f)

        // ⛔ v1.4：`lineWidthPx` / `trailColorArgb` / `ALPHA_BASE` 已删除（→ `segWidthPx` / `segColorArgb`）

        internal fun meteorColorArgb(baseArgb: Int, fade: Float): Int =
            setArgbAlpha(baseArgb, (METEOR_FADE_K * fade.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255))

        /** 通道插值（纯算术，⛔ 不碰 android.graphics） */
        internal fun mixArgb(a: Int, b: Int, t: Float): Int {
            val k = t.coerceIn(0f, 1f)
            val ar = a shr 16 and 0xFF; val br = b shr 16 and 0xFF
            val ag = a shr 8 and 0xFF;  val bg = b shr 8 and 0xFF
            val ab = a and 0xFF;        val bb = b and 0xFF
            return pack(ar + (br - ar) * k, ag + (bg - ag) * k, ab + (bb - ab) * k)
        }

        /** 纯 Compose 侧颜色插值（渐变构建期用，⛔ 绝不进每帧路径） */
        internal fun mixColor(a: Color, b: Color, t: Float): Color {
            val k = t.coerceIn(0f, 1f)
            return Color(
                red = a.red + (b.red - a.red) * k,
                green = a.green + (b.green - a.green) * k,
                blue = a.blue + (b.blue - a.blue) * k,
                alpha = a.alpha + (b.alpha - a.alpha) * k,
            )
        }

        /** ⭐ 山脊基线高度（px）—— 与画路径**同源**（G9） */
        internal fun ridgeYAt(x: Float, w: Float, horizonY: Float, h: Float): Float =
            horizonY + ridgeDy(if (w > 0f) x / w else 0f) * h

        /** 山脊相对偏移（horizonY 的 h 倍数）—— RIDGE 的 Bernstein 正算 */
        internal fun ridgeDy(u: Float): Float {
            val g = ridgeSegFor(u)
            val t = ((u - g.x0) / (g.x1 - g.x0)).coerceIn(0f, 1f)
            val k = 1f - t
            return k*k*k*g.y0 + 3f*k*k*t*g.c1 + 3f*k*t*t*g.c2 + t*t*t*g.y1
        }

        /** 归一化 x 落在哪一段（越界钳到首/末段） */
        internal fun ridgeSegFor(u: Float): RidgeSeg {
            var i = 0
            while (i < RIDGE.size) { if (u <= RIDGE[i].x1) return RIDGE[i]; i++ }
            return RIDGE[RIDGE.size - 1]
        }

        /** 枝条可用上升高度（px）—— ⛔ 恒 ≥ 0（原型曾在此写反符号 ⇒ 下垂「蜘蛛腿」） */
        internal fun limbBudget(originY: Float, treeBaseY: Float, treeH: Float): Float {
            val b = originY - (treeBaseY - treeH)
            return if (b < TREE_BUDGET_MIN) TREE_BUDGET_MIN else b
        }

        /** 枝条逐段角度推进（⛔ 纯函数，G10 据此判「枝尖高于出枝点」） */
        internal fun nextLimbAngle(angRad: Float, curl: Float, jitter: Float): Float =
            (angRad + curl * (jitter - 0.5f) * LIMB_JITTER) * LIMB_PULL_BACK

        /** 枝尖 y（px，纯函数）—— ⛔ 与 addLimb 逐段步进**同式**（共用 nextLimbAngle） */
        internal fun limbTipY(x0: Float, y0: Float, angRad0: Float, segLen: Float,
                              curl: Float, seed: Int): Float {
            var px = x0; var py = y0; var a = angRad0
            var i = 0
            while (i < LIMB_SEGS) {
                a = nextLimbAngle(a, curl, hash01(seed * 7 + i))
                px += sin(a) * segLen
                py -= cos(a) * segLen
                i++
            }
            return py
        }

        /** 确定性哈希（sin 分数部分）—— 枯枝抖动**唯一**来源；⛔ 不得换 Math.random */
        internal fun hash01(i: Int): Float {
            val v = sin(i * 12.9898f) * 43758.5453f
            return v - floor(v)
        }

        internal const val MAX_DT_S = 0.1f

        // —— 内部小工具（⛔ 都不碰 android.graphics）——
        private fun pack(r: Float, g: Float, b: Float): Int =
            (0xFF shl 24) or (r.toInt().coerceIn(0, 255) shl 16) or
                (g.toInt().coerceIn(0, 255) shl 8) or b.toInt().coerceIn(0, 255)

        private fun setArgbAlpha(rgb: Int, alpha: Int): Int =
            (alpha.coerceIn(0, 255) shl 24) or (rgb and 0x00FFFFFF)
    }
}
```

> ⚠️ **§14.3 是「签名与结构」级别的骨架，不是可直接编译的全文**：字段清单不完整
> （`mR`/`mAng`/`mDir`/… 流星池、`meteorRect`/`meteorPath`、`glowBrush`/`bandBrush`/`poleGlowR`/
> `bandTop`/`bandH`、`Limb` 类、`buildGroundPaths` / `addLimb` / `rebuildGeometry` /
> `recycleRingLayer` / `advanceFlare` 等）分散在各小节，
> 拼装时以**生产文件** `visualizer/renderers/StarrySkyRenderer.kt` 为准。
> ⛔ 但**凡是本节显式给出的表达式**（`postFx`、`drawSegments` 的子弧链、`rebuildRingLayer`、
> `poleAngleDeg`、`radiusForRange`、`segAlphaAt`、`segColorArgb`…）均为逐字定稿，照抄即可 ——
> v1.3 已把其中 4 处编译不过 / 门禁判否的写法改正，v1.4 已把整套缓冲机械换成「底环 + 亮线段」。
>
> ⚠️ **v1.4 遗留的一处已知取舍**：亮线段的 `Stroke` **每柱新建一个**（`Stroke.width` 在
> Compose 1.9 的 `drawscope.Stroke` 上是 `val`，不可变）。量级约 64 个短命小对象/帧，
> 与 `BatchThreeRenderers` 的环形描边同款做法。若日后要做成零分配，用「按 0.1dp 量化的
> `Stroke` 档位表」（`BatchFourRenderers` 的免分配写法）即可 —— ⛔ 但那会让线宽与
> `segWidthPx` 差一档量化误差，**门禁判的就不再是生产线宽了**，故本版不做。

### 14.4 逐文件改造点（file:line）

- `AppSettings.kt:185`（`WORLD(...)` 之后）插入枚举项、`:266` 的 `;` 之前；` :100` KDoc 数字（仍陈旧写「27 套」）。
- `VisualizerRendererFactory.kt:51-80` 的 `when` 加分支；头部 + import。
- `VisualizerThemeTest.kt:51,57,58,89,90,96,103` 计数。
- `FxCoverageScanTest.kt:41-63` `covered` 列表 + `:257` 计数。
- `StarrySkyRenderer.kt` 全文见 §14.3。

### 14.5 既有断言的同步项

即 §七 G1/G3 与 §八 F6/F7——已在对应小节列全，此处不再重复。

### 14.6 实施顺序

严格按 §十二 12.3 T1→T6 顺序（先注册与计数，再渲染器，再测试，再验证，再文档），每一步可独立「编译 + 单测」验证。

### 14.7 待修的陈旧注释清单

- `AppSettings.kt:100`「27 套」→「29 套」（实施 F2）。
- （可选，与本案弱相关）`CHANGELOG.md:18` 声称 600ms crossfade 已启用，实为 `false`（`VisualizerStage.kt:205`）——**不在本案范围**，仅留痕，勿顺手改。

---

## 十五、提交顺序（每步可独立验证）

| 步 | 内容 | 验证 |
|---|---|---|
| S1 | F1+F2+F3（枚举/KDoc/工厂） | `assembleDebug` 编译通过（尚未有渲染器，工厂分支引用 `StarrySkyRenderer` 需**同步** S2，故 S1/S2 合并提交） |
| S2 | F4 渲染器骨架（能画天空+星点，暂无星轨） | 编译 + 手动清单可选中 E42 |
| S3 | F6+F7（计数 + 名单同步） | `testDebugUnitTest` 全绿（否则 G1/G3 挂） |
| S4 | F5 测试全量（G1–G8） | `testDebugUnitTest` + 逐类 `--tests "*StarrySkyTest"` |
| S5 | T2.3–T2.5（星轨 + 地景 + 鼓点完整） | 本机 `lintDebug` 无新 Error |
| ⛔ S5.5 | ⭐ **v1.4 架构更换**（真机四缺陷 ⇒ ping-pong → 底环 + 亮线段） | 本机 `assembleDebug` + `testDebugUnitTest` + `lintDebug` 全绿（**真机复核仍未做**） |
| ⛔ S6 | 真机 V4–V12 | 帧率截图 + 无 SIGSEGV —— **未做**（§12.4 顶部注记） |
| S7 | F8/F9 文档 | 死链检查 |
| S8 | F10 CHANGELOG | 文风检查（只写做了什么） |
| S9 | 合入 + `technical-overview` **§10.206** 收尾 | CI 三 job 全绿 |

> S1 与 S2 因工厂分支引用必须**同提交**（否则中间态编译不过）；其余步可逐条独立提交。
> ⛔ **v1.5 状态**：S1–S5、**S5.5**、**S5.6**、S7–S9 已完成；**S6 真机验收仍未做完** ——
> 真机运行史共两次：① v1.4 之前（ping-pong）判四个缺陷失败 ⇒ 触发 v1.4 架构更换（§12.4 ⑮）；
> ② **v1.4 架构真机通过**（29.7fps，「亮线没问题」）但判「分布太均匀、亮弧不够多」⇒ 触发 v1.5 分布层（§12.4 ⑯）。
> **v1.5 分布层只在本机验证过**（`assembleDebug` + `testDebugUnitTest` **1582 例 / 0 失败** + `lintDebug` **0 Error**），
> 真机帧率与观感**尚未复核**（V4/V5）。
> §九 R1 已由「高」降为「低·基本退役」（缓冲已删、暴露面 = 192 次细弧 + 4 次 `drawPath` + 1 次 1:1 blit）。
> 另：`CHANGELOG.md` / `technical-overview.md §10.206` 属于 §八 F9/F10 的范围，**v1.4 / v1.5 两轮均未在范围内同步**（由调用方决定是否补写）。