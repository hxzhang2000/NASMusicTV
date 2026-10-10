# E44「明月」视觉化效果开发方案

> **状态**：**原型定稿 → 可开工**（v1.0 起草 + 同日增补 §3.0 技术路线 / T0 浏览器原型；
> **2026-10-08 所有者宣布「html 效果定稿」** ⇒ §3.0(e) 的观感闸门**已通过**，本文随定稿回填为
> **v1.1**（版本跟踪见文末，最新 **v1.8**）。**T1–T11 解除阻塞**（曾有的**唯一例外：T2 需先拍
> §十六 Q4 + Q8** —— 定稿实测的填充越过本文自己提的上限，见 §9.2 —— 已于 2026-10-09 由所有者
> 裁决关闭并落地，⇒ ⛔ **§十六 Q1–Q9 自此全部关闭**）；⛔ 但 T4 起仍**必须所有者上机逐条判读**
> （§十三），因为原型只证明了**观感**与**算法**，从未证明过 **API 22 创维的填充率**。
> ⚠️ 另一个定稿自带的盲区：七轮**全部在"锁定满月"下调参**（`phaseLock:'full'`），
> 所以**非满月的观感从未被任何人看过** —— V1/V2 是复判而不是走流程，详见 §4.4 末与 R13。
>
> **实现进度**（2026-10-10，逐条落地记录见 §十一）：**T0 / T1 / T2 / T3 / T4 / T5 / T6 / T7 / T8 / T9 ✅**
> —— 亦即"真实月相"这条需求（1）**已经完整生效**（历算 + 天平动驱动的圆盘重烘 + 相位阴影 +
> 新月夜的极淡边缘），⚠️ 但**从未上机看过**；**T6** 把"夜空本身"补齐（§3.1 色尺单一来源
> `MoonSeascape.kt` + 层 1 两块矩形 + 层 2 星野 1:1 源区裁剪 + 层 10 冷蓝暗角边 + **G11**）；
> **T7** 把需求 2 补齐并让需求 3 的**同源性成立**（`MoonClouds.kt` 云场全部数学 + `occl`
> 五个消费点接线 + 层 3 月晕 + §6.6 确定性过境 + **G3 `MoonlitTest` 28 例**，
> 基线由 `docs/archive/verification/scripts/moonlit_cloud_golden.js` 从**原型原文**跑出）；
> **T8** 把需求 3 的**水面那一半**真的画出来（`MoonWater.kt` 光柱 / 粼光 / 地平带全部数学 +
> 层 7 三件接线 + **G14 `MoonlitWaterTest` 18 例** —— 水面三行预算自此**不再由探针打印值手抄**，
> 由生产几何逐颗重算对账；落表口径修正四条 **D26–D29**、不变式实测更正一条 **D30**）。
> **T9** 把需求 4（律动）接上（`MoonAudio.kt` = §八 那七把尺，纯函数零 Android import + 渲染器侧
> 四个 `AudioSmoother` **每帧各推进一次**（`updateDt` 的**首个生产调用方**）+ 门禁
> **G15 `MoonlitAudioTest` 20 例**）。音频自此 ⛔ 不驱动月盘亮度、⛔ 不读 gamma 频谱 /
> `maxParticles`；偏离四条 **D31–D34** —— 其中 **D34**（HALO 按 `k²`、RIPPLE 由探针典型帧改
> 生产几何最坏帧）让 MED 合计越过 `3.95`，由所有者**二次裁决**「抬 MED 上限到 `4.00`」收口
> （⛔ 不是把两行的数改小，裁决留痕见 **D5**）。⭐ 无声帧逐像素等于 T8（G15 ⑯b），
> 所以"接了音频"在静态帧上读不出来是**设计**，不是漏接。
> ⚠️ T7 顺带**推翻了一条写进定稿的事实**：`occl` 的自然上限**不是 0.33**，自然场就能遮满
> （实测分布见 §6.2 的 as-built 块，偏差 **D24**）—— 定稿那句"唯一没解决的是自然云场遮不满月"
> 已被证伪，§6.6 提供的自此只有**时长与周期性**，不是"能不能吞月"。
> 全量实测 **1919 例 / 0 失败 / 0 错误** + `lintDebug` `BUILD SUCCESSFUL`（计数链：T8d 的 **1899**
> + G15 的 **20** = **1919**，`logs_temp/t9d_full.log`，4m30s；T8e 之后代码侧只有注释改动，
> 已复跑受影响的三套门 **54 例全绿**，`logs_temp/t8e_comment_check.log`；T9e 回填时又改了七处
> **门禁编号交叉引用**（`MoonAudio.kt` / `MoonlitRenderer.kt` / `MoonOpBudget.kt` 的 KDoc 里
> `G15 ⑧` 之类指错了例子），同样**只动注释**。T9e 后的复跑分两步，⚠️ 口径要写清：
> `assembleDebug + lintDebug`（`logs_temp/t9e_full.log`，`BUILD SUCCESSFUL in 5m 6s`，36 tasks
> 只有 4 条 executed）里 `compileDebugKotlin` **重编了**，但 `testDebugUnitTest` 判
> **`UP-TO-DATE`** —— 注释不进字节码 ⇒ `.class` 逐位不变，那一轮的 1919 是**继承值不是实测值**；
> 因此另跑了一次强制 `--rerun`（`logs_temp/t9e_tests_rerun.log`），**实测 1919 例 / 0 失败 / 0 错误**
> `BUILD SUCCESSFUL in 1m 6s`）。
> ✅ **2026-10-10 已本地提交**：`f697c93`（代码 + 本文档 + 原型 + 4 个验证脚本，39 文件 / +15543 行）、
> `69b7ed7`（`CHANGELOG` v2.38.6 五条）。版本号升到 **v2.38.6 / versionCode 175**，`assembleRelease`
> 已出包（`NASMusicTV-release-v2-38-6.apk`，24.3 MiB，R8 + `lintVitalRelease` 全过）。⛔ **未推送、未打 tag**。
> ⚠️ 但 **E44 从未上机** —— 真机验收 T10 归所有者，且按所有者指示
> 「**`docs/technical-overview.md` 等测试完再写**」⇒ **T11 只做了一半**（`CHANGELOG` + `visualizer-effects-list.md`
> 已落，`technical-overview.md` 那条**故意不写**，草稿暂存 `logs_temp/v2386_10224_draft.md`，⚠️ 那是临时目录，
> 上机后须据此回填并**取当时候的空闲 §10 编号** —— 初稿写的 §10.220 已被 E29 真机回访占用，现为 §10.224）。
> ⚠️ `CHANGELOG` 在 V 系列之前落地，与 §十一 T10 行那条「⛔ 未过 V 系列不得进 `CHANGELOG`」相冲，
> 是按所有者的发布指示覆盖的 ⇒ **若真机返工（尤其帧率不达标需砍元素），该条目必须回改。**
> 剩下的是 **T10 真机验收**（§十三 V/R/U 系列，⛔ 需所有者上机，含"真实铺屏 ≈3.33/3.88/**4.48** 屏
> 下的帧率"这条从未被任何探针证明过的账，见 §9.2 判定行）与 **T11 的后半**（`technical-overview.md` 条目）。
> 定稿前的七轮迭代（每轮的判据与被否掉的写法）已并入 §三/§五/§六/§七/§九 正文，
> ⛔ 实现期以**本文**为准，不要再回头读 `docs/moonlit-preview.html` 的注释找依据
> （那是原型自己的史，本文才是交付规格）。
>
> **需求原文**（所有者 2026-10-08）：
> 1. 要用**真实的月球图**，要**根据当前日期显示真实月相**；
> 2. 要有**云**的效果，云要**运动**；
> 3. 水面要有**倒影**，但要**考虑云遮月**的情况；
> 4. 参考图 4 张（见 §一）。
>
> **本文的三条硬约束**（继承全仓红线，⛔ 不可协商）：
> - 只用 `DrawScope` + `nativeCanvas`，**零 GLSL / 零 OpenGL / 零 AGSL `RuntimeShader`**
>   （AGSL 需 API 33+，本项目 minSdk 22）；E41 的 WebView 是**已落地的唯一例外**，
>   ⛔ 不推广到其他效果；
> - ⛔ 全项目禁用 `clipPath(`（API 22 创维在 `Region::createTJunctionFreeRegion` 原生
>   SIGSEGV，见 `docs/archive/visualizer-texture-upgrade-plan.md` §11.3 与
>   `technical-overview.md` 的 P-1/P-2/P-3 记录）；
> - ⛔ **不要用「≈0.45 ms/op」做成本估算** —— 该模型已被真机证伪（E43 预算表预测 ≈30 fps，
>   实测 4.1–7.3 fps，`SeasideOpBudget.kt:486` 的 KDoc 原文）。本方案的 §九 预算表是
>   **纪律工具**（防失控），⛔ 不是帧率预测器；E44 的真实开销只能由 §十三 的上机测量给出。

---

## 一、需求 → 规格逐条对应

四张参考图给出的**不是同一个月夜**，本文把它们拆成**可分别判定的观感条款**，
并明确各自落在哪一层。⚠️ 参考图**不入库**（会话附件），下表是它们被转写后的**唯一留痕**，
实现期以本表为准，⛔ 不要再回头找原图对齐（E43 的 `seaside-preview.html` 行号锚点之痛的同类）。

| # | 需求 | 参考图给出的具体规格 | 落点 |
|---|---|---|---|
| 1 | 真实月球图 + 真实月相 | 图 4：满月盘面**必须有环形山与月海**（明暗斑块），不是纯色圆 | §五（`moon.jpg` 正射烘焙） |
| | | 月相**由日期决定**，⛔ 不是装饰性的固定圆 | §四（`MoonPhase`） |
| | | 图 4：盘面外围一圈**大而柔的晕**（halo），亮度远低于盘面但面积大 | §3.2 层 3 |
| | | 图 2：背景**星野**（暗、密、不与云争） | §3.2 层 1 |
| 2 | 云 + 云要运动 | 图 4：云带**横穿盘面下部**，云在月**前** ⇒ 盘面被部分遮挡 | §六（前层云） |
| | | 图 4：被月照亮的**云边缘发亮**（逆光镶边），云体本身仍偏暗 | §6.4 |
| | | 图 3：**厚云带压住大半个月空**，月小而高、几乎被吞 | §6.2（`occl → 1` 的极端态） |
| | | 图 2：云为**成团的软块**，不是硬边几何 | ⚠️ 定稿推翻：所有者第三轮判「形状还是不对，**应该是烟雾的样子**」⇒ 云的模型从"扁椭圆团"改为**沿蛇形脊线排布的拉长软斑缕**（烟），见 §6.1 |
| 3 | 水面倒影 + 考虑云遮月 | 图 1：水面有一条**纵向拉长的碎金粼光路**，从月正下方一直伸到画面底 | §7.3 |
| | | ~~图 1：粼光**是断开的短横划**，不是连续光柱~~ ⚠️ **定稿推翻**：参考图（真机复量）读作**一条连续的光带（glade）上骑着一堆断开的浪脊划**——⛔ 孤划列不是目标，"连续但每一行都错开、永不错成一根柱子"才是。见 §7.2/§7.3 | §7.2 + §7.3 |
| | | ⭐ 定稿新增（图 1 复量，原表未列）：**水面上看不见月盘的像**，只有光柱 ⇒ §7.2 的"镜像位图"整条路线已废弃 | §7.2 |
| | | 图 3：云厚时**水面明显更暗** ⇒ 倒影与遮挡必须同源 | §7.4（`occl` 单一真源） |
| | | 图 2：水面**没有月**（月被云吞）时仍需有可读的海 | §7.2（本体海面与月的贡献分离） |
| 4 | 构图 | 图 1：月**低**、色**暖**（琥珀）；图 3：月**高**、色**冷**（近白蓝） | §3.3（色温随画面高度） |
| | | 图 1/3：海平面在**画面下部约 1/3** | §3.1（`HORIZON_K`） |
| | | ⚠️ 四张图的月盘**直径差 3 倍以上** ⇒ 本文**定一个固定值**，不随音频缩放 | §3.1（`MOON_R_K`） |

**刻意不做的**（避免实现期跑偏，详见 §十四）：月出月落方位、地平线处的月球视直径增大
（"月亮错觉"）、地平折射、真实大气消光公式、观星地点与季节星座、云的光学散射模型。

---

## 二、现状核实（全部 `file:line`，2026-10-08 实测）

### 2.1 注册链只有两处 + 一份双语字符串

| 位置 | 现状 | E44 要做 |
|---|---|---|
| `data/model/AppSettings.kt:126-133` | `enum class VisualizerTheme(displayNameRes, displayName, tier, ordinalLabel, needsParticleBudget = false)` | 新增一项 |
| `AppSettings.kt:264` | `SEASIDE(..., "海边", Tier.BASIC, "43", needsParticleBudget = false)` 是**最后一项**，最大 `ordinalLabel` = `"43"` | `"44"` **空闲**（E41 的旧 2D 版已删，编号留空位不回收） |
| `AppSettings.kt:113` | 头注释写「21 套手动效果」 | → 22 套 |
| `visualizer/VisualizerRendererFactory.kt:44` | `fun create(theme, context)` 的单 `when`，`:65 SEASIDE -> SeasideRenderer()` | 加 import + 分支 |
| `VisualizerRendererFactory.kt:74` | `availableThemes(...) = selectable(...).filter { quality.supports(it) }` | ⛔ **零改动** |
| `res/values/strings.xml:1286-1287` | `visualizer_theme_seaside` / `visualizer_theme_star_trails` | 新增 `visualizer_theme_moonlit` |
| `res/values-en/strings.xml:1278-1279` | 同上英文 | **两份都必须加** |

⚠️ **`displayNameRes` 缺失 = 运行时崩溃，且没有任何单测会红**（`AppSettings` 只存资源 ID，
渲染列表时才解析）。⇒ §十 把「双语存在」列为**独立门禁**，不要靠手测。

**全仓 `SEASIDE` / `SeasideRenderer` 的命中只有 5 处**（上表 3 处 + `VisualizerStage.kt:315/374`
的两句注释）⇒ 新增一套效果的**生产代码改动面 = 2 个文件**，其余全是测试与文档。

### 2.2 档位与 `needsParticleBudget`（本效果的选型依据）

`AppSettings.kt:360-370`：

```kotlin
fun supports(theme: VisualizerTheme): Boolean = when (theme.tier) {
    Tier.BASIC -> true
    Tier.ADV   -> !theme.needsParticleBudget || maxParticles > 0   // :368，2026-10-01「方案 C」裁决
    Tier.ULTRA -> allowFramebuffer
}
```

`VisualQuality`（`AppSettings.kt:346-356`）：`HIGH(…, 350, …, true)` / `MEDIUM(…, 150, …, false)` /
`LOW(32, 1, false, 0, 16, 10, false)`。

⇒ **`Tier.ADV` + `needsParticleBudget = false` 在三档全部可用**（E42 星空星轨就是这个组合，
`AppSettings.kt:233`）。本效果一个粒子都不画、粼光划的数量由 `FxLevel` 而非 `maxParticles` 决定
⇒ 选 **`ADV` + `false`**：档位上如实标注"比 BASIC 重"，又不被粒子预算门误挡（误挡的历史症状：
"能渲染却不可选，切走即永久回不来"，`AppSettings.kt:360-368` 注释）。

⚠️ 一旦渲染器**真的**去读 `ctx.quality.maxParticles`，`ParticleBudgetGateTest.kt:89` 会拿源码扫描
结果与枚举标注对撞 ⇒ **必须同步改成 `true`**（该门的正则见 `:51`）。本方案 §九 明确 ⛔ 不读它。

### 2.3 基类契约（`RendererFx.kt`，本轮逐行读过）

| 行 | 事实 | 对 E44 的含义 |
|---|---|---|
| `:46` | `protected abstract fun DrawScope.drawContent(frame, ctx, fx)` | 只实现这个 |
| `:54` | `final override fun draw`：`clock.advance` → `fx.level = FxBudget.of(ctx.quality)` → `drawContent` → 非 OFF 才 `applyPostFx` → `needsDamageCoalescer` 补一次不可见全屏 | 后处理与脏区合并**绕不过** |
| `:43` | `internal open val postFx: PostFx get() = PostFx.NONE` | 覆写即"有意改观感" |
| `:72` | `final onExit` 内部 `OverlayFx.release()` + `ProceduralTexture.release()` | ⛔ 子类不要再 release 共享纹理 |
| `:87/:90` | `onEnterContent` / `onExitContent` | 自己的位图在这里建/销 |
| `:95` | `protected val rng`（每渲染器一个实例） | ⛔ 不要再 `private val rng = VisualizerRandom()` |
| `:98` | `private val sizeCache = SizeCache()` 是 **private** | 子类**自己声明一个** |
| `:132-154` | `PostFx` 三值必须是**数字字面量**（覆盖扫描测试用正则读它） | `vignette = 0.42f` ✅，`VIGNETTE_STRENGTH` 常量 ❌ |
| `:187-198` | `FxFrame{ dt, nowMs, seq, level }`，`MAX_DT_MS = 100L` | 时基只走这里 |

### 2.4 时基与墙钟（本效果**特有**的坑，⚠️ 必读）

`RenderContext` **没有任何可用的墙上时钟**：`:20 canvasSize` 在 `onEnter` 瞬间是 `Size.Zero`，
`:23 nowMs` 被 `RendererBaseContractTest.kt:167` 用正则 `ctx\.nowMs` **判违规**（负向自证在 `:220-222`）。
`RenderContext.kt` 里也没有 `Context` —— 但工厂有：`VisualizerRendererFactory.kt:44` 的第二形参
`context: Context`，当前**只**给了 `WORLD -> WorldGlobeRenderer(context)`。

E43 把同一个坑写成了条款（`SeasideRenderer.kt:136-141`）：

> 零时刻在**首帧**由 `fx.nowMs` 记一次 —— ⛔ 不在 `onEnterContent` 里记：那里拿不到 `fx`，而唯一
> 可用的 `RenderContext.nowMs` 在进入瞬间是**墙钟**（`System.currentTimeMillis()`），与
> `frame.timeMs` 的**单调**时钟不同源，两者相减得约 −1.7e12 ms ⇒ 整个生命周期冻结（E42 同一个坑）。

⇒ E44 必须把**两件事严格分开**：

1. **动画时基**：⛔ 只用 `fx.dt` 累加（`PhotoTransitionClock.kt:17` 的铁律：绝不用
   `nowMs × 系数`，那会把每帧抖动放大千万倍）；
2. **日历时刻**：`System.currentTimeMillis()` —— `RendererBaseContractTest` 扫的是 `ctx.nowMs`，
   ⛔ 不是 `System.currentTimeMillis()`，所以直接读**合法**。但它**只许**喂给 §四 的纯函数
   `MoonPhase`，⛔ 绝不许进任何相位/位移系数。

### 2.5 真实月球图**已经在 APK 里**

```
app/src/main/assets/globe/moon.jpg   324,830 B   JPEG SOF0 1024×512（比例恰为 2:1）
```

- 1024×512 = 2:1 ⇒ 它是**等距圆柱投影（equirectangular）**全球月面图，⛔ 不是"一张月的照片"。
  这是本方案能同时满足「真实月球图」与「真实月相」的**唯一前提**：有了全球纹理，
  就能按观察几何重投影出**任意朝向、任意遮挡下的那半个月亮**。
- 目前它**只**被 E41 使用：`WorldGlobeRenderer.kt:217`
  `appContext.assets.open("globe/$assetName")` 把字节流交给 WebView。⇒ 复用 = **APK 零增量**。
- ⚠️ `GlobeAssetsHygieneTest` 守的是"别把构建输入/node_modules 混进 assets"，
  按文件名逐个查（`:60/:84/:139`），⛔ 它**不是**"文件数必须为 9"的计数门 ⇒ 复用现有文件不触发它。
- **全仓没有 asset→`Bitmap` 的先例**（grep 确认：`assets.open` 只有 `WorldGlobeRenderer` 一处）。
  最近的可抄手法是 `visualizer/photo/PhotoBuffer.kt:271-295 decodeWithFactory(...)`：
  `BitmapFactory.Options` + `inJustDecodeBounds` 探边 + `inSampleSize` + `inPreferredConfig`
  （`:272` 按 `allowRgb565` 切 `RGB_565`/`ARGB_8888`），并在 `:45-48` 记录了「API < 26 位图在
  native 堆」的理由。

### 2.6 可复用清单 / 不可用清单

**✅ 可直接用（同包/同模块，已核签名）**

| 工具 | 位置 | 用途 |
|---|---|---|
| `VisualizerMath.lerp/map/envelope/polar/rad/hsl/hueGradient` | `visualizer/VisualizerMath.kt:44/:47/:36/:56/:60/:66/:137` | 共享纯数学，⛔ 不要复制一份 |
| `VisualizerRandom.next/nextSigned/nextIndex` | `visualizer/VisualizerRandom.kt`（基类 `rng` 已持有实例） | 云团播种，零分配 |
| `AudioSmoother.updateDt(target, dtSec)` | `fx/AudioSmoother.kt:32` | ⭐ **当前生产代码零调用方**；E44 用它驱动平滑律动即为其首个真实消费方 |
| `OverlayFx.drawVignette` | `fx/OverlayFx.kt:49`（支持 `edgeOverride`，`:52`） | 暗角身份色用冷蓝而非封面 accent |
| `ProceduralTexture.ensureTiled()` + `ensureFullscreenOnly(Id.STARFIELD, iw, ih)` | `fx/ProceduralTexture.kt:257/:223` | 星野层 |
| 纯历法算式的**写法范本** | `renderers/WorldTerminator.kt:211-241 dayOfYear()` | Hinnant `civil_from_days` 整数反解 |

**⛔ 明确不用**

| 项 | 理由 |
|---|---|
| `ProceduralTexture.Id.FOG`（`fx/ProceduralTexture.kt:33` 枚举第 8 项，`fogRow` 在 `:708`） | 它是**全屏**纹理，⛔ 不能横向滚动（无缝性靠正弦而非周期格点），移动的云必须自己烘或走向量 |
| 新增 `ProceduralTexture.Id` | 加一项要同步 6 处（含 `StarrySkyTest.kt:1069-1076`、`ProceduralTextureRecycleTest.kt:177-180`），且 `Id` 的 `ordinal` 是 `slots/keys` 下标基准（`:33` 的 KDoc 明写 ⛔ 只能追加） ⇒ 不值 |
| `OffscreenFx.drawBloom` | 仅 `FxLevel.FULL`，且阶段 6 的屏幕级 bloom **接线未做**（已归档方案的遗留 ④）；E44 的"大柔晕"用 3 层径向渐变自绘，⛔ 不等它 |
| `SeasideWaves` 的纯函数（`clamp/lerp/smoothstep/hash32/noise1/fbm1/fbm_norm/vnoise2/fsin`，`:211/213/215/236/260/271/290/317/334`） | 技术上同模块 `internal` 可调，但**跨效果耦合**：E43 一旦调整或删除，E44 一起塌。⇒ 需要的新噪声在 E44 内自带（先例：E42 的 `hash01` 就在 `StarrySkyRenderer.kt:1531` 本地） |
| `BitmapShader` | ⛔ 全项目禁用，唯一例外是所有者裁决的 `SeasideRenderer.drawSand`（`technical-overview.md` §C 记录） |
| `Brush.verticalGradient(vararg)` 出现在 `draw*` 函数体内 | 会分配 vararg；`PerfBudgetContractTest` **不查这个**，E42 曾踩中（`SeasideRenderer.kt:130-133` 的 KDoc），E43 靠自建扫描补洞 ⇒ ⛔ 只许在烘焙函数里用 |

---

## 三、总体设计

### 3.0 技术路线（选型 → 落点 → 每帧数据流 → 验证路线）

前面 §二 核的是"仓库现状"，后面 §四–§九 给的是"每个元素怎么算"。本节补的是中间那层缺失的
东西：**这套效果整体走哪条渲染路线、代码落在哪几个文件、一帧内的数据往哪流、开发前先用什么验证**。
⚠️ 实现期若对"该不该引某个库/某条 API"有疑问，答案在本节，⛔ 不要边写边选型。

#### (a) 渲染路线选型

| 候选路线 | 判定 | 理由 |
|---|---|---|
| **Compose `DrawScope` + `nativeCanvas`，全部图元走 Canvas 基础绘制** | ✅ **采用** | 本效果的 7 层元素（渐变矩形、径向渐变晕、软椭圆云、闭合 `Path` 相位阴影、一次位图圆盘、短横划粼光）**没有一样需要着色器**；零新依赖、零新权限、三档共用同一条码路（⛔ 三档砍的是**数量**不是分辨率，§十四）。⭐ 定稿补充：原型把除"两块基矩形 + 晕 + 暗角"以外的**每一个**柔边元素都收敛成了**同一个图元**（§3.0(f) 的 `softEllipse`），Kotlin 侧照此只做一个 helper，元素之间差的是**参数**不是**代码路径** |
| WebView + three-globe（E41 路线） | ⛔ 不用 | 那条路线的**存在理由**是真实 3D 球面光照与可旋转地球；E44 的球面只需要"按 `f`/`pa` 重投影出那半个月亮"，一个 `Path` 就够。引 WebView 的代价是实例 + JS 桥 + `evalJs` 注入 + 首帧延迟 + 双份内存，且 E41 已是**已落地的唯一例外**，头部红线明令 ⛔ 不推广 |
| AGSL `RuntimeShader` / `RenderEffect` / OpenGL | ⛔ 不可能 | 分别需 API 33 / 31 / GL 通路，与 minSdk 22 冲突（头部红线） |
| 采样雾纹理 `Id.FOG` 当云 | ⛔ 不用 | 见 §2.6：全屏、不可无缝横向滚动；更致命的是**遮挡标量必须可解析计算**（§6.2），走纹理就得每帧回读像素 |
| 离屏渲染 + bloom（`OffscreenFx`） | ⛔ 不等 | 仅 `FxLevel.FULL` 且屏幕级 bloom 接线未做（§2.6）；E44 的大柔晕用 3 层径向渐变自绘，⛔ 不为它等一个未完成的通路 |
| 二次场景渲染求真实现面镜像 | ⛔ 不用 | §7.1：提交数与填充翻倍，且云/星/晕的倒影在参考图里几乎看不见 |

#### (b) 代码落点（新增 3 个文件 + 生产改动 2 处，其余全是测试与文档）

| 文件 | 职责 | 新增/改动 | 依赖方向 |
|---|---|---|---|
| `visualizer/renderers/MoonPhase.kt` | `internal object MoonPhase`，§4.2/4.3 的六个纯函数 + `of(utcMs) → MoonState`。**⛔ 零 UI、零 `android.*` 导入**，所以能脱离渲染单测（G2） | 新增 | 只依赖 `kotlin.math` 与 `VisualizerMath` |
| `visualizer/renderers/MoonOpBudget.kt` | §九 的三档 ops / 填充 / native 像素三张表 + 上限常量，仿 `SeasideOpBudget` | 新增（⚠️ **必须早于渲染器**，T2） | 无 |
| `visualizer/renderers/MoonlitRenderer.kt` | 唯一渲染器：继承 `RendererFx`，只实现 `drawContent`（`RendererFx.kt:46`）+ `onEnterContent`/`onExitContent`；圆盘烘焙、云场、`occlusion()`、水面全在这一个文件内（先例：`SeasideRenderer.kt` 单文件） | 新增 | → `MoonPhase`、`MoonOpBudget`、`fx/*` |
| `data/model/AppSettings.kt` | 枚举项 `MOONLIT(..., Tier.ADV, "44", needsParticleBudget = false)` + `:113` 头注释 21→22（§2.1/§2.2） | 改动 | — |
| `visualizer/VisualizerRendererFactory.kt` | 单 `when` 加分支，⚠️ 必须 `MoonlitRenderer(context.applicationContext)`（G6 防 Activity 泄漏） | 改动 | → `MoonlitRenderer` |
| `res/values/strings.xml` + `res/values-en/strings.xml` | `visualizer_theme_moonlit`，**两份都必须有**（缺失 = 运行时崩溃且无测试会红，§2.1） | 改动 | — |

⛔ **不新增**：`ProceduralTexture.Id`、任何 assets 文件（月面图复用 `globe/moon.jpg`，G7）、
任何 gradle 依赖、任何 `renderers/` 之外的新文件。✅ **复用而不复制**：`VisualizerMath`、
`VisualizerRandom`、`AudioSmoother.updateDt`、`OverlayFx.drawVignette`、`ProceduralTexture.ensureTiled`
+ `Id.STARFIELD`、历法算式的写法范本 `WorldTerminator.kt:211-241`（全部签名见 §2.6）。

#### (c) 每帧数据流（一条链，⛔ 不许长出第二套真源）

```
System.currentTimeMillis() ──只在 onEnter 锚定一次（§4.5）──┐
fx.nowMs（单调）/ fx.dt    ──动画时基 t = Σ dt / 1000 ──────┤
                                                            ▼
                     每 60 s 才重算一次：MoonPhase.of(utcMs)
                            → MoonState(f, e, waxing, pa, libW, libB)
                    ┌───────────────────────────┴────────────────────────┐
                    ▼                                                    ▼
   圆盘位图（§五）：仅当 尺寸 / 天平动(0.5° 量化)          7 层绘制（§3.2，⛔ 顺序即契约）
   / 色相 变化才重烘，且走增量分帧步长（§5.2）              层 6 近层云 → occlusion() → occl（§6.2）
                    → 层 5 一次 drawImage                              ↓
                                                    diskA / haloA / rimA / reflA / glitA（§6.3 五点）
                                                                          ↓
   AudioFrame 线性律动通道（⛔ gamma spectrum，§八）                       │
     → AudioSmoother.updateDt(target, fx.dt/1000)                          │
       bass→晕半径与海面亮度 · treble→粼光 · mid→云速 ·                      │
       pulse→涟漪 · beat→逆光边 · energy→暗角 ◄──────────────────────────────┘
```

三条**不变式**（可单测，⭐ 需求 3 的结构保证就在这三条上）：

1. 天上与水面共用**同一个 `occl` 标量**，⛔ 水面绝不二次求遮挡（否则会出现"月被云吞了、
   水里却有一条完整金路"）；
2. 月盘亮度只有 `diskA` 一个乘子，⛔ 不再叠一个 `f` 系数 —— 照度 `f` 已经体现在阴影**几何**里。
   ⚠️ 原型实测踩过：两者相乘时 5% 照度的残月直接变成看不见的灰斑；
3. 位移/相位系数只由 `fx.dt` 累加出来的 `t` 驱动，⛔ `ctx.nowMs` 与墙钟不进任何
   `× 系数`/`+ 位移` 表达式（G5 正则扫描）。

#### (d) 位图路线（全仓第一个 asset→`Bitmap`；细节在 §五，本节只锁阶段）

```
assets/globe/moon.jpg（1024×512 等距圆柱，已在包内 ⇒ APK 零增量，§2.5）
  ① 解码   一次性；⛔ 不在 onEnterContent（那时 canvasSize 还是 Size.Zero，§3.1）⇒ 首帧尺寸分支里做
  ② 反解   §5.1 UV 反解 + 天平动 → 正射圆盘位图（圆外 alpha 恒 0 ⇒ ⛔ 不需要 clipPath）
  ③ 烘焙   §5.2 增量分帧（五个常量抄 E43），未烘完按已烘行绘制 ⇒ ⛔ 不闪白、⛔ 不整盘同步重烘
  ④ 绘制   每帧一次位图绘制，⛔ 每帧分配（G8 扫 createBitmap 调用点 ≤ 2）
  ⑤ 释放   onExitContent 回收；native 堆记账见 §9.3（1080p 余量 7.9%，⚠️ 破点在 1440p → R3 / D18）
```

#### (e) 验证路线（⛔ 先于 T1，所有者 2026-10-08 指示）

> 指示原文：「你先生成一个 html，作为实现验证，我看效果满意后，再进行开发」。

- **载体**：`docs/moonlit-preview.html`（自包含单文件，与 E43 的 `docs/archive/seaside-preview.html`
  同一套视觉语言与控制台布局）。打开方式：仓库根 `python -m http.server 8901 --bind 127.0.0.1`
  后访问 `http://127.0.0.1:8901/docs/moonlit-preview.html`；用 `file://` 也能跑，但画布被污染
  取不到真实月面像素 ⇒ 自动走 §5.4 程序化降级，**不要用 file:// 判读需求 1**。
- **它验的是结论，不是代码**。已实测通过：§4.6 A 的 6 个日月食锚点 6/6（残差 ≤2.7°）、
  B 的 8 条连续向量逐项复现、D1–D4 负向自证**确实会失败**（改错速率偏 25.9°/26.0°、
  改错历元偏 19.9°/17.8°）；9 个日期跨整个朔望月的「盘上实测亮区面积占比 vs 算法照度 `f`」
  差值恒为 ±0.023（= §4.2 末三层软化带的面积，⛔ 不是误差）；盈月亮边在右 / 亏月在左；
  五消费点与 `occl` 同源；云漂移每帧 `occl` 跳变 ≤0.0015（⛔ 没被鼓点"跳"起来）；
  §九 的 ops/填充/native 逐档对账。
  ⚠️ 该列表里**「粼光测得 9 个亮簇、最长簇 9 行 ⇒ 断开的碎金」这一条已被第六轮推翻**——
  当时判的是"⛔ 不是光柱"，但真机复量参考图后确认目标是**连续光带上骑断划**（§7.3），
  验收口径随之外移，见 §十三 V7 的定稿改写。
- **观感回路（定稿实际靠这套跑起来的，⛔ 不是靠人眼猜）**。原型每一轮都走同一条闭环，
  工具全部在 `docs/archive/verification/scripts/`：

  | 工具 | 干什么 | 关键约定 |
  |---|---|---|
  | `moonlit_visual_driver.mjs` | 无头 Edge + CDP 驱动**真实页面代码**：`snap`/`probe`/`series`/`cost`/`flow`/`pass`/`disk` 七个命令 | ⚠️ **tier 恒为第 1 参**；`snap <TIER> <帧号> <名字>` 的第 3 参是**后缀**不是文件名（传全名会得到 `snap_MED_480_snap_MED_480_xxx.png`） |
  | `moonlit_ref_match_check.py` | ⭐ **「像不像」的客观门**：对参考图与候选图同一套量窗，打印 `R/H`、盘内均值、盘 p95、水面 2% 档、水面 20% 档、六个深度处的点亮宽度/R | 它把候选几何**从 HTML 的 §3.1 常量正则读回**，⛔ 不接受手传参数（防止"测的和跑的不是同一份"） |
  | `moonlit_water_zoom.py` | 水面**并排放大对照**（上=参考、下=候选，各自按自己的地平线裁、同缩放到 700 px 高） | 用法 `python -X utf8 … <候选png> [输出名]` → 写 `output/moonlit_frames/<输出名>.png`。"倒影不像"这类**只能看结构**的判据靠它 |
  | `html_syntax_check.js` | 语法 + 脚本字符数哨兵（改一轮确认没改坏） | 定稿态 `SYNTAX OK, script chars: 510412` |

  ⚠️ **中文控制台输出一律 `python -X utf8`**，否则 CP936 乱码（AGENTS.md 的同族坑）。
  ⚠️ `probe` 的输出**不是纯 JSON**（前面有一行 `贴图通路:`），直接 `json.loads` 会 `Extra data`；
  稳妥做法是先落盘到 `logs_temp/`，再用 `awk 'length($0)<300'` 滤掉内嵌月面图的超长行。
  ⚠️ **两个脚本都把原型路径写死成 `docs/moonlit-preview.html`**（`_match_check.py:29`、
  `_driver.mjs:24`）⇒ 日后若要按文档生命周期把原型 `git mv` 进 `docs/archive/`（E43 的
  `seaside-preview.html` 就是这么走的），**必须同批改这两处常量**，否则客观门会静默读到空文件。
  截图/候选图一律落 gitignore 的 `output/moonlit_frames/`（AGENTS.md 仓库卫生），
  ⛔ **定稿截图本身不入库** —— 复现方式是重跑 `snap MED 480 <后缀>`，不是找旧文件。
- ⛔ **不移植清单**（原型专属，Kotlin 侧没有对应物，抄过来就是走样）：
  `window.__dbg` 验收钩子与手动 `step()` 步帧、JS `Date.UTC()`/`Date`（Kotlin 走
  `System.currentTimeMillis()` + 纯算式，§4.5）、canvas 的 `clip()`/`ellipse()`/`toDataURL()`
  （⛔ `clipPath(` 是红线）、DOM 控件与 HUD 对账表、`performance.now()` 的烘焙耗时探针、
  `globalCompositeOperation`（走 `Paint.blendMode`，见 (f)）。
- ✅ 原型实测提出的修订项**已随本轮（v1.1）全部回填**：§4.2 末的轴符号统一为
  `v = R(1−2f)`、§9.1 的 `TERMINATOR` 改记 `≤3`、§9.2 换成实测三档合计、
  §七 整章按 glade 模型重写、§六 换成烟缕模型、§3.3 换成解像素得到的金色 ramp。
  ⚠️ **定稿当时留下的唯一未决项"自然云场遮不满月"已于 T7 证伪**（2026-10-09，偏差 **D24**）：
  那个"峰值 ≈0.33"是**探针自己的错**（`minDim` 遮蔽了上下文全局 ⇒ 缕尺寸按 0 算），
  按生产几何重跑 8000 帧的实测分布是 **`peak = 1`、`mean ≈ 0.306`、`occl > 0.5` 占 26%**
  （§6.2 的 as-built 块）。所以定稿的答案由"**不解决**"改写成"**无需解决**"：`occl` 由云场几何
  自然决定这一点没变（`S.occlManual` / `S.cloudPassT` 那两个演示钩子仍只在原型里存在，
  Kotlin 侧 ⛔ 没有"强制过月云"），而 §6.6 的确定性过境买到的是**时长与周期性**，不是"能不能吞月"。

#### (f) 原型 → Kotlin 的移植契约（定稿新增，⚠️ T4–T8 逐条照此落地）

这一节是**原型跑出来的、写在代码注释里会随原型一起烂掉的东西**，必须搬到规格里才活得下去。

1. **全效果只有一个柔边图元**：`softEllipse(cx, cy, rx, ry, alpha, colIn, colOut, coreK, coreA)`
   —— 内部 = `save` + `translate` + `scale(1, ry/rx)` + **一次** `RadialGradient` 填充圆 + `restore`。
   Kotlin 侧对应 `nativeCanvas` 上同款 transform + `Paint(Shader = RadialGradient(...))`，
   ⛔ 不需要 `drawOval`、⛔ 不需要 `Path`、⛔ 不需要 `clipPath`。晕的**渐变内圈半径 ≠ 0**
   （原型取 `rx·0.10`），这是"芯是否实心"的第一开关。
   - 语义钉死（原型实测，别再猜）：**峰值 alpha 就是 `alpha` 形参在 stop 0 上的值**；
     `coreA` 只是"到 `coreK` 处还剩多少"；`colOut` **必须 alpha=0**；
     **高 `coreK`+高 `coreA` = 实心芯**（浪脊、过曝芯要它），
     **低 `coreK`（≈0.10）= 看不见拐点 = 平滑雾**（底光带要它）。
   - 第二图元 `softRing`（芯透明的四段渐变）只用于**涟漪**，⛔ 不用于云银边（原因在 §6.4）。
2. **颜色必须是 `rgb(整数,整数,整数)`**：原型的 `withA()` 用正则
   `/^rgb\((\d+),(\d+),(\d+)\)$/` 拼 alpha，别的写法（浮点、`#rrggbb`、`hsl()`）静默原样返回
   ⇒ 颜色"看着设了其实没设"。**Kotlin 端没有这个正则坑，但也因此没有这个哨兵**——
   移植时把"色串格式"降级成直接构造 `ARGB Int`，⛔ 不要把 JS 的字符串拼装带过来。
3. **加法混合只有一个地方需要**：月盘的过曝芯（§5.2 末）与星野（层 2）。
   Compose 的 `DrawScope.drawCircle` **没有 `blendMode` 形参** ⇒ 落地走
   `nativeCanvas.drawXxx` + `Paint.blendMode = BlendMode.Plus`（与星野层同一手段）。
   ⛔ 不许退化成 `source-over` 然后说"目测一样"——实测不一样：覆盖会把月海刷成一块死白。
4. **成本模型（⭐ 定稿最重要的三条，全部可核对）**：
   - `op()` 的椭圆记账用 **`2π·a·b`**（真椭圆面积是 `π·a·b`）⇒ **有意签成 2×**，
     与 E43 同口径，⛔ 不要"顺手修正"成 π，那会让所有历史对比失效。
     粼光划的记账是 `len·hh`（**包围盒**，比真面积高约 1.27×）。
   - ⛔ **`op()` 只按面积记账，不看 alpha**：把 α 设成 0 **不回收预算**，
     图元照样被计一次全屏。⇒ 原型的实验"关掉雾看省下多少"一开始读数没动，就是这个原因。
     **Kotlin 侧必须显式 `if (a < 0.004) return`**（原型在粼光循环里有这条，
     但 REFL/云没有 ⇒ 移植时**给每个图元补上**，这是免费的帧率）。
   - ⭐ **改 α / `coreK` / `coreA` 是免费的，只有改几何（rx/ry/数量）才花填充率**。
     推论①：要"更多东西"时优先**加密小图元**，⛔ 别放大大图元。
     推论②：一条宽光带的成本是它**覆盖的面积**，⛔ 叠多颗只会为同一片面积重复付费
     （§7.2 实测：5 颗扁椭圆花 0.521 屏只买到 +9 亮度；换成 1~3 颗**竖长**椭圆花 0.20）。
5. **动画积分红线**：位移 = `x += speedK · spdMul · dtSec`（**累加增量**）。
   ⛔ 绝不写 `绝对时间 × 逐帧随音频变化的系数`——原型那样写过，摆幅随运行时长线性放大，
   `spdMul` 一回落云就整段倒退，所有者判为**"一下向左一下向右，有些抽搐"**（第五轮根因）。
   同一理由适用于一切"呼吸/翻滚"项：**只能是有界正弦**（`WOB`/`BOIL`/`DRIFT_Y`/`wavK` 全按此写）。
6. **确定性种子**：原型 `reseedClouds(20261008)`（`seededRng` 是 LCG）。
   ⚠️ 改**常数取值**而不改 `rng()` 的**调用次数**，云位不变 ⇒ 可以用同一份种子做 A/B 对照
   （第七轮的形状改动就是这么验证的）；改**调用次数**会让整场云重排，观感对比作废。
7. ⚠️ **JS 优先级坑（只在原型里，移植时别再犯）**：`hash1` 曾写成 `x | 0 ^ K`，
   JS 里 `|` 优先级**高于** `^` ⇒ 折成 `x | (0 ^ K)` = `x | K` ⇒ K 中为 1 的位被 OR 掉，
   不同 `x` 撞进同值，**星野实测长出规则点阵**。正确写法 `(x | 0) ^ K`（定稿即此）。
   Kotlin 侧 `Int` 溢出天然等价 `|0`，**没有这个坑**，但位序必须逐字对齐
   `Math.imul((x|0) ^ 0x9E3779B9, 0x85EBCA77)` → `h ^= h ushr 13` → `imul(h, 0xC2B2AE3D)` →
   `h ^= h ushr 16` → `h ushr 0 / 4294967296f`（先例：`SeasideWaves.kt:224-225` 的等价性论证）。
   ⭐ **as-built（T6）的口径修正**：E44 的星位**不由本效果自算哈希** —— 层 2 复用共享纹理
   `Id.STARFIELD`，星点来自 `ProceduralTexture.starLayout` 的 **LCG**
   （`st = st·1664525 + 1013904223`、`((st shr 8) and 0xFFFFFF)/2^24`、种子 `0x5EEDF00D`），
   所以"位序逐字对齐"在生产码里**没有标的**。G11 的处置见 **D20**：无格点半边判在真实星位上
   （四组画幅 `dup ≤ 2 / maxCol ≤ 6 / maxRow ≤ 6 / distinct == n`），位序半边降级为
   "有牙夹具 + 前进钩子"（`MoonlitStarfieldTest` ③⑤），⛔ 不假装断言、也不整条删掉。

### 3.1 构图常量（**固定构图**，不随歌曲变化）

以 `minDim`（`RenderContext.kt` 的 `minDim = min(width, height)`）为基准：

| 常量 | 值 | 依据 |
|---|---|---|
| `HORIZON_K` | `0.640f` | 图 1/3 的海平面位置；⚠️ **刻意不同于** E43 的 `SeasideWaves.SHORE_K = 0.620`（那是"浪能冲上来"的岸线，这里是不动的分界线） |
| `MOON_CX_K` | `0.280f`（× w） | 图 1 的月偏左；偏离中心以留出粼光路的对角呼吸 |
| `MOON_CY_K` | `0.200f`（× h） | 图 3 的月高；⛔ 不许进画面顶部 `safeAreaPx`（`RenderContext.kt:22`） |
| `MOON_R_K` | `0.150f`（× minDim） | 1080p ⇒ R = 162 px。参考图直径差 3×，本文取中位并**锁死**（§十四：不做缩放呼吸） |
| `HALO_R_K` | ~~`1.35 / 1.90 / 2.60`（× R）~~ → 定稿改为 **(半径系数, 峰值 α 系数) 表**：LOW `[[2.60, 0.42]]` / MED `[[1.62, 0.46], [3.20, 0.16]]` / HIGH `[[1.50, 0.46], [2.40, 0.24], [4.00, 0.11]]` | 图 4 的晕直径约为盘的 5 倍 ⇒ 最外 4.0 R × 2 = 8 R（⚠️ 定稿比原方案更宽更分层的"内层亮、外层淡"）。⭐ **内圈半径必须推到 `moonR·0.96`**（原方案/前四轮用 `0.55·R`，晕画在层 3 而盘画在层 5 ⇒ 高能量区全被不透明盘盖掉，所有者判「发光层被月亮贴图完全盖住了，没有出现光晕」，这是第五轮的直接根因） |
| `SEA_BOTTOM_K` | `1.000f` | 海面铺到画面底 |
| `GLITTER_W_K` | `0.275f`（× minDim） | §7.3 粼光路**底宽**。⚠️ 原方案只给了包络式没给绝对值 ⇒ 这是定稿补的自由量，随 §7.3 的透视一起调 |
| `DISK_GAIN` | `1.34f` | 圆盘烘焙的整体亮度增益（乘在 `tintMul × lumK` 上）。⚠️ **不能靠它提亮**，见 §5.2 的"先降反差再谈增益"条款；配套对比曲线 `gain = 0.72 + 0.55·g` |
| `OCCL_PAD` | `0.6f` | §6.2 的 60% 缓冲（半遮也算部分遮挡） |
| `TERM_SOFT_K` | 见 §4.2 末 | 三层嵌套阴影的层间偏移；满月（`f ≥ 0.995`）**整段跳过**，理由见 §9.1 的 `≤3` |

⭐ **定稿新增的"两个海/天色基准"**（Kotlin 侧必须逐字对齐，否则水面所有暗带都错）：
天空 `#03050c → #060b18 → rgb(9+16·altT, 15+12·altT, 30+6·altT)`（顶 → 0.62 → 地平线），
海水 `rgb(10,17,29)·sb → rgb(5,9,17)·sb @0.35 → #02040a`（地平线 → 底），
其中 `sb = 1 + 0.10·(aBass − 0.4)` 是 §八 的海面亮度调制。
⛔ §7.2 底光带与暗槽取的"所在深度的海水色"**必须用同一把尺**（原型的 `seaC(t)`）算，
写死常数会刷出矩形亮块。

⭐ **as-built（T6，2026-10-09）**：这把尺落成
[`MoonSeascape.kt`](../app/src/main/java/com/nasmusic/tv/visualizer/renderers/MoonSeascape.kt)
（零 Android/Compose 类型，颜色一律打包成 ARGB `Int` ⇒ 纯 JVM 可测），它是本效果**唯一**持有
海/天色端点的地方 —— `MoonlitRenderer.kt` 全文**一个 `0xFFRRGGBB` 都没有**（由
`MoonlitSkySeaTest.①` 钉死，防的就是上面那句"刷出矩形亮块"）。三条落地口径：
- **固定构图下真正上屏的天空地平档是 `rgb(20, 23, 34)`**，⛔ 不是上文那个 `rgb(9, 15, 30)` ——
  后者是尺在 `altT = 0`（月贴地平）时的**下端**。`altT = (HORIZON_K − MOON_CY_K)/HORIZON_K
  = 0.6875` 是**常数**（分子分母同量纲 ⇒ 归一化比例与像素同值），上屏值由它算出。
  ⚠️ 判读截图时拿 `#090f1e` 对色会误报"色阶错了"。
- **`altT` 只有一份**（`MoonSeascape.ALT_T`）：盘色温（`MoonDiskBake` 的 `hue/sat/lit` 三行）、
  天空地平档、暗角边色**全部引用它**（§3.3+ 记录的"端点重抄后各自漂移"由结构消除，
  `MoonlitSkySeaTest.③b` 断言 `MoonDiskBake` 里不得再声明 `ALT_T`）。
- **暗角边色 = `sea(0f, sb=1)`**（即 `rgb(10,17,29)`），⛔ 不沿用封面 accent，也不重抄成
  `0xFF0A111D` 字面量。
⚠️ 与书面式的一处口径差登记为 **D19**（上文海水第三档 `#02040a` 写作不乘 `sb`，
原型的 `seaC(t)` 三档**一律**乘 `sb` ⇒ 实现按函数，`sb = 1` 时逐位相同）。

⚠️ `onEnterContent` 里**不能**按尺寸建任何位图（`ctx.canvasSize` 此刻是 `Size.Zero`，
`RenderContext.kt:20`；同 E43 的 `SeasideRenderer.kt` 生命周期条款）⇒ 圆盘烘焙在**首帧
`drawContent` 的尺寸分支**里启动，并按 §五 增量进行。

### 3.2 绘制顺序（7 层，⛔ 顺序即契约）

```
层 1  夜空底色        两块不相交矩形：天空渐变 [0, horizonY) + 海体渐变 [horizonY, h)
层 2  星野            ProceduralTexture.Id.STARFIELD 一次带纹理绘制，裁剪到天空矩形（⛔ 不铺全屏）
                      ⭐ as-built：裁剪靠 `drawImage` 的**源区**（`srcSize == dstSize == (w, skyH)`
                      的 1:1 拷贝，海面的星从未提交），⛔ 不靠 `clipPath`（全项目禁用）
层 3  月晕 halo       1/2/3 层（`HALO_SPEC`，§3.1）径向渐变填充，内圈半径 `0.96·R`（⛔ 不是 0.55·R）
                      ⭐ 归属裁定（T6 段）：**与云同批落在 T7** —— `haloA = haloBase·(1 − 0.75·occl)`
                      的 `occl` 是 §6.2（T7）的量，提前接只会多钉一个"occl=0"的刻意常量
层 4  云后层          远层烟缕软椭圆（在月**之背**）：只有轮廓与暗部，不参与遮挡计算
层 5  月球盘面        一次位图绘制（§五 烘好的正射圆盘贴图，圆盘外 alpha = 0）
                      → ⭐ `BLOOM` 加法过曝芯（§5.2+ ④，`BlendMode.Plus`，⛔ 必须在阴影**之前**）
                      → 相位阴影 3 条嵌套闭合 `Path`（§4.2 末，`f ≥ 0.995` 整段跳过）
层 6  云前层 + 逆光边  近层烟缕软椭圆（在月**之前**）；被照亮的斑补一条银边（§6.4，斑级 `softEllipse`）
                      ⇒ §6.2 的 occl **只由这一层**计算
层 7  水面            地平线压暗带（§7.6）→ 月**光柱 glade**（§7.2，⛔ 水面上不出现月盘）
                      → 粼光浪脊划（§7.3）→ 节拍涟漪（§7.5，可选）
末尾  后处理          基类按 postFx 施加暗角（⛔ 不加颗粒，见 §9.2 的填充账）
```

⛔ **层 4 与层 6 的"远/近"不是深度，而是遮挡记账的分界**：只有近层能遮月。
若把**远层**也计入 `occl`，就会出现"天上云没盖住月、水里却看不见月"的**不一致**——那正是需求 3 要避免的。
（⚠️ 本条原文误写作"若把近层也计入"，v1.1 修正：被禁的是**远层**。）

### 3.3 色温随画面高度（把图 1 与图 3 统一进一套代码）

真实大气消光的**定性替身**：月越贴地平线越暖越暗。

```kotlin
// t = 0（贴地平）→ 1（天顶），纯函数、可单测
val t = ((horizonY - moonCy) / horizonY).coerceIn(0f, 1f)
```

⚠️ 这是**观感映射**，⛔ 不是大气质量公式（`1/cos(z)` 在近地平发散，且我们没有真实高度角）。
差异已登记 §十五 偏差候选 D3。

#### 3.3+ 定稿色温（原方案的 `52°/0.10` 已被推翻，⛔ 不许回去）

原方案给的是「低处琥珀、**高处近白**（hue 52°、sat 0.10、light 0.94）」。第六轮所有者判
「根据参考图，月亮的亮光要更亮一些」，实测**根因在色相不在亮度**：高处的 `sat 0.10` 把月亮
画成了**灰白**，而参考图的"亮"有一半是**金**（hue ≈ 38–38.5°、sat ≈ 1.00）。
⇒ **定稿的 ramp 是直接解参考图像素得到的**，不是调参：

| 量 | 定稿值 | 怎么来的（PIL 复量，可复核） |
|---|---|---|
| `moonHue` | `lerp(36, 38.5, altT)` | 盘内均值 `rgb(210,169,98)` → 归一化比值 `(1, 0.805, 0.469)`，代回 hsl→rgb 在 hue 38° 时**唯一**对应 S=1.00 / L≈0.735 |
| `moonSat` | `lerp(0.99, 1.00, altT)` | 同上（旧 `S=0.72 / L=0.80` 解出 `(1, 0.898, 0.695)` ⇒ 就是截图里"发灰"的根因） |
| `moonLit` | `lerp(0.70, 0.745, altT)` | 同上；盘心过曝峰另测得 `rgb(254,223,153)` |
| `tintMul` | `moonHsl / max(moonHsl)` | ⭐ **色相归一**：把纹理最大通道归到 1.0，亮度**另走** alpha/增益 ⇒ 两者不互相污染 |
| `lumK` | `clamp(0.58 + 0.42·(moonLit / 0.745), 0.4, 1)` | ⚠️ **必须引用同一个 `moonLit` 变量**。旧写法在这里**又抄了一遍两个端点**（0.66/0.80），改 ramp 时它悄悄没跟着改 ⇒ ramp 与亮度标度分家。**Kotlin 侧写成引用而非重抄常量**，这是防漂移的结构措施 |
| `HOT_K` | `255 / max(moonHsl)` | 把最大通道**正好**顶到 255 的系数 ⇒ 本月相的"最纯最亮"版本；再往上乘就是往纯白脱色 |
| `whiteOf(t)` | `moonHsl + (255 − moonHsl)·t` | ⭐ **镜面提白**（见下） |

⭐ **本效果最贵的一条经验：颜色会封死亮度，⛔ 加 α 顶不动。**
PIL 实测参考图**最亮水波** `rgb(248,220,150)` 比**盘内均值** `rgb(210,169,98)` 还淡一档
（比值 `(1,0.89,0.60)` vs `(1,0.81,0.47)`）——因为波峰是**过曝的镜面**，不是"更亮的月盘色"。
拿月盘色去刷水，亮度被**色相**封死在 lum≈192（实测卡在 178 上不去），
此时继续加 α 只会把暗海水一起染亮 ⇒ **发灰**。提白必须走 `whiteOf(t)`。

推论（同样已实测）：⛔ **不要靠"乘增益"提亮月盘**。`tintMul` 已把最大通道归一到 1.0，
再乘只是把环形山一起提亮、盘体仍是灰的；要"过曝的白芯"必须**加一层**高 `coreA` 的软椭圆
并走**加法混合**（§5.2 末的 bloom）。

---

## 四、真实月相：`renderers/MoonPhase.kt`

### 4.1 选型与精度（已实测验证）

**采用**：低精度太阳/月球平黄道坐标 + 黄经差（ elongation ），⛔ **不用**《天文年鉴》的
朔望截断级数（Meeus 缩短级数）。

> ⚠️ 这是本轮**实测踩出来的结论，不是偏好**。我先按朔望级数实现（`2451550.09766 +
> 29.530588861k` 加 12 项修正），对 2026 年的四个已发表天象锚点复算，误差达 **0.3–1.0 天**
> ——远超标称的 0.03 天，且方向不一（级数项抄写/适用性存疑）。改用下面的日月黄经差后，
> 六个锚点**全部命中**（表见 4.2）。⇒ ⛔ 后来者不要再回到朔望级数。

**精度**（对独立锚点的残差，本文实测）：

| 锚点（时刻 = 已发表的天象极大时刻） | 本文算法的日月黄经差 | 应为 |
|---|---|---|
| 2000-01-06 18:14 UT（新月） | **0.07°** | 0° |
| 2024-04-08 18:17 UT（4·8 全食带日全食） | **0.11°** | 0° |
| 2026-02-17 17:00 UT（环食） | **2.68°** | ≈0° |
| 2026-03-03 11:04 UT（月全食） | **179.75°** | 180° |
| 2026-08-12 17:50 UT（日全食） | **0.00°** | 0° |
| 2026-08-28 04:30 UT（月偏食） | **180.08°** | 180° |

残差 ≤ 2.7° ⇒ 折合时刻误差 ≤ 2.7/12.19 ≈ **0.22 天**；对应照度误差：
在**上/下弦前后**（照度变化最快处）最坏约 **±1.5%**，在满月前后 < 0.3%。
⛔ 这就是本效果的精度上限，⛔ 不要对外宣称"精确到时刻"。

**为什么够用**：需求是"按日期显示真实月相"。肉眼分辨月相的临界是照度差 ~5%；
本算法误差 ~1.5%，且月相本身一晚只变 ~0.03–6%/天（§4.5），⛔ 任何一场播放里都不会被看出偏差。

### 4.2 公式（⛔ 全部纯函数、`Double` 内部、无对象分配、不用 `java.time`）

```
d = (epochMillis − 2451545.0J2000) / 86400000          // 自 J2000.0（2000-01-01 12:00 UT）起的天数
L  = 218.31617 + 13.17639648·d      // 月球平黄经
M  = 134.96315 + 13.06499300·d      // 月球平近点角
Ms = 357.52910 +  0.98560026·d      // 太阳平近点角
D  = 297.85016 + 12.19074898·d      // 平距角
F  =  93.27200 + 13.22935000·d      // 纬度幅角

λ_m = L + 6.289 sinM + 1.274 sin(2D−M) + 0.658 sin2D + 0.214 sin2M − 0.186 sinMs
          − 0.114 sin2F − 0.058 sin(2D−Ms) − 0.057 sin(2D+M) + 0.053 sin(2D+Ms)
          + 0.046 sin(2D−M−Ms) + 0.041 sin(M−Ms) − 0.035 sinD − 0.031 sin(M+Ms)
          − 0.015 sin(2F−M) − 0.012 sin(D+Ms)
β_m = 5.128 sinF + 0.281 sin(M+F) + 0.278 sin(M−F) + 0.173 sin(2D−F) − 0.159 sin(2D−M)
          − 0.110 sin(2D+M−F) + 0.062 sin(2D+Ms) + 0.060 sin(2D−Ms)
          − 0.058 sin(2D+Ms−F) − 0.048 sin(2D−Ms+F) − 0.034 sin(M−F) − 0.032 sin(M+F)
          − 0.021 sin(2D−F+Ms)
λ_s = 280.46646 + 0.98564760·d + 1.9150 sinMs + 0.0200 sin2Ms − 0.00062

e   = wrap180(λ_m − λ_s)                     // 黄经差 = 距角，−180..180
f   = (1 − cos e) / 2                        // 照度分数 0..1
waxing = e > 0                               // e>0 ⇒ 月在前 ⇒ 盈（上半月）
age = wrap360(e) / 360 × 29.530588853   // 月龄（日）：e=0 即朔 ⇒ wrap360，⚠️ 见 4.4 的退化说明
```

> ⚠️ **两个曾经实测踩到的坑**（写进单测的负向自证）：
> ① `L` 的速率必须是 **13.17639648 °/日**（朔望级数体系里的 `L′` 常量易与别的表混淆；
>    本文第一版误用 13.179056 ⇒ 26 年后偏差 ~24°，锚点检查直接暴露）；
> ② 这一组常数配 **J2000.0 = 2000-01-01 12:00 UT** 历元（⛔ 不是 Jan 0.0 = 1999-12-31 00:00）；
>    混用会让所有相位整体偏 ~16°，锚点同样立刻暴露。

⚠️ **v1.1 修正一处初版的自相矛盾**：初版此处写作 `((e + 180) % 360)/360 × SYNODIC`，
与 §4.6 B 表自己的实测列（`e = −26.0° ⇒ 月龄 27.39 日`）不符——代进去得 12.62 日。
正确式是 **`wrap360(e)/360`**（`e = 0` 即朔 ⇒ 月龄 0），原型已按此复现 §4.6 B 的 8 条向量。
⇒ `MoonPhaseTest`（G2）里**必须**有一条用 B 表原值做断言的用例，⛔ 别让这类"文档内部不一致"活到实现期。

**明暗界线（terminator）的椭圆**（`f` 决定形状，`pa` 决定朝向）：

```
半短轴 b = R × (2f − 1)         // f=0.5 → 0（一条直线，弦月）；f→1 → +R；f→0 → −R
```

⚠️ **定稿统一写法**：原型取的是「+X 指向太阳」的局部系，暗区**顶点**
`v = R·(1 − 2f)`（f=0 全黑 ⇒ v=+R；f=1 无暗区 ⇒ v=−R），与本式 `b = R(2f−1)`
是**同一量在相反轴向**上的写法。⇒ Kotlin 落地时**必须先选定一个**再写，
⛔ 不要一个文件里出现两种（原型注释已明确记着这条差异）。本文**以 `v = R(1−2f)` 为准**，
因为它能直接把三层软化写成 `v − i·TERM_SOFT_K·R·sign(1−2f)`。

⚠️ **`f → 1` 时 `sign(1−2f)` 翻号** ⇒ 三层软化带会**从盘面外侧长回来**（满月变暗斑）。
定稿的处理：`f ≥ 0.995` **整段跳过阴影**。⇒ §9.1 的 `TERMINATOR` 是**条件项**，
满月夜实际为 **0**，预算必须按 `≤3` 记账。

绘制：`Path` = 「暗侧的半个圆弧（`addArc`，180°）」+ 「`quadTo`/`arcTo` 折回的半椭圆」
→ `drawPath(fill)`，⛔ **不用 `clipPath`**（红线）。半椭圆的 x 缩放用
`canvas.save / scale(…,) / draw / restore` 实现（变换写法先例：`VintageTvRenderer.kt:455-459`、
`BatchFourRenderers.kt:1147-1151`），⛔ 不许逐帧新建 `Matrix`（复用字段）。
三层**同 α**（原型取 `0.62`）⇒ 核心区合成不透明度 `1 − 0.38³ = 0.945`，⛔ 不要写成递减。

⚠️ **T5 落地时的机制偏离**（登记 §十五 D12）：闭合形状没有走"`save/scale` 画单位圆再横向缩放"，
而是**两次 `arcTo`**（`Path.arcTo(oval, startAngle, sweepAngle, forceMoveTo = false)`：
先 `−90°` 起扫 `−180°` 画暗侧半圆，再以 `|v|` 为半短轴扫回半椭圆）。理由有三：
① 缩放写法每层要一对 `save/scale/restore`，而 `arcTo` 直接在复用 `RectF` 上给半径，⛔ 不需要 `Matrix`；
② `forceMoveTo` 必须为 `false`（`true` 会断开轮廓 ⇒ 填充区不是闭合暗区）；
③ **`|v_i| ≤ R` 在全程成立**（`MoonPhaseShadowTest` 的 A7 逐 0.005 扫 `f ∈ [0, 0.995)` 证它），
⇒ 暗区**天生落在盘内**，这条红线不是"绕开了禁用 API"，而是**几何上不需要裁剪**
（与 §5.2 圆盘靠"圆外 alpha=0"免裁剪同思路）。`|v| < 0.5 px` 时改走 `lineTo` 直线弦，
⛔ 零宽椭圆会画出毛刺。

⭐ **轴向有闭式判别式**（T5 用它写单测，未来改几何请沿用）：这个闭合形状 = 「−X 侧半圆盘」+
「`rx=|v|`、`ry=R` 的半椭圆」，⇒ **暗区面积 / 整盘 = `(R + v) / (2R)`**，
⇒ 未软化的层 0 恒给 **`1 − f`**。本效果按**真实日期**走月相（需求 1），⛔ 不能指望"验收那晚正好
半月"来发现写反：2026-10-09 实测 `f ≈ 0.05`，写反轴向的画面表现是"整盘亮"，读起来像
"月相没生效"而不像"几何错了"。软化的 `sign` 只服务一件事：**最外圈永远只被一层盖到**
（少了它，`f > 0.5` 一侧的软带整体往暗区**内部**收 ⇒ 界线变硬）。

### 4.3 签名（`internal object MoonPhase`，仿 `WorldTerminator.kt:55`）

```kotlin
internal object MoonPhase {
    /** @param utcMs Unix 毫秒（UTC）。纯函数：⛔ 不读时区、不读 Calendar、不读 ctx */
    fun illum(utcMs: Long): Float                 // 0..1 照度分数 f
    fun elongDeg(utcMs: Long): Float              // −180..180 距角 e
    fun isWaxing(utcMs: Long): Boolean            // e > 0
    /** 亮 limb 的位置角：自**天球北**起、向东为正，度；退化见 §4.4 */
    fun brightLimbPaDeg(utcMs: Long): Float
    /** 天平动：经度 w、纬度 b（度），供 §5.1 的 UV 反解 */
    fun librationLonDeg(utcMs: Long): Float       // = λ_m − L（截断摄动和，实测范围 −6.2..+4.8）
    fun librationLatDeg(utcMs: Long): Float       // = −3.85 sin F − 1.37 sin(M−F)
    fun ageDays(utcMs: Long): Float               // 仅用于日志/验收，⛔ 不上屏
}
```

**`brightLimbPaDeg` 的实现**：把 `(λ, β)` 转赤道坐标（ε = 23.439280°），再取
太阳相对月球的**位置角**标准式：

```
tan PA = cos δ_s · sin(α_s − α_m) / [ sin δ_s · cos δ_m − cos δ_s · sin δ_m · cos(α_s − α_m) ]
```

（用 `atan2` 取象限；返回 −180..180。）

### 4.4 朔、望附近的**退化**（必须处理，否则会看到"抽风"）

`PA` 是"过月心与日心的大圆"方向。当 `e → 0`（两点重合）或 `e → 180`（两点对至）时，
**过两点的大圆不唯一** ⇒ PA 数学上失稳。实测（北京 21:00，2026 年 10 月）：

| 日期 | 照度 | e | PA | 说明 |
|---|---|---|---|---|
| 10-24 | 96.5% | 158.5° | −105.1° | 正常 |
| 10-25 | 99.4% | 171.5° | −86.5° | 开始加速 |
| **10-26** | **99.8%** | **184.8°** | **+26.0°** | ⚠️ 一夜跳 112° |
| 10-27 | 97.5% | 198.3° | +59.8° | 仍在快转 |

⛔ 这不是 bug，是几何事实。**但它在画面上不可接受**（盘面会一夜之间转 112°）。
处理：**`PA` 的影响强度按 `|sin e|` 加权**，退化时刻形状本身已是整圆/整暗 ⇒ 加权后无痕。

```kotlin
// 阴影旋转角 = 平滑参考角 + (PA − 平滑参考角) × |sin e|
val w = abs(sin(rad(e)))          // w→0 于朔望，w→1 于弦月
val tiltDeg = baseTilt + wrap180(pa − baseTilt) * w
```

`baseTilt` 取 `0f`（本文选择）或上一帧值（时间相关）。⛔ 选 `0f` 以保证 `MoonPhase` **是纯函数**
（时变状态会让单测失去判别力）。

⭐ **定稿补齐：`tiltDeg` → 画布旋转的换算**（初版 §4.4 只给了角度，没给"怎么用到 `canvas.rotate` 上"，
实现期一定会卡在这里）。原型实测写法（`moonlit-preview.html` 的 `tilt` 变量）：

```kotlin
// 度 → 弧度，且 ⚠️ 取负 + 加 90°：屏幕 y 轴向下、PA 自北向东为正 ⇒ 两者旋向相反
val rotRad = -(tiltDeg + 90f) * DEG_TO_RAD
```

`+90` 是因为 `shadowHalf` 的暗区顶点定义在 **+X** 轴上（"局部系 +X 指向太阳"），
而 PA=0 表示亮 limb 朝**北**（画布上方）。⛔ 这两个偏置漏一个，月牙朝向就会整晚错 90°
或左右镜像。验收：§十三 V3（若左右反了，根因在坐标系约定，**修法是取反 `pa`，⛔ 不是调参**）。

⚠️ **T5 落地时补齐的单位**（登记 §十五 D11）：上面那行的 `× DEG_TO_RAD` 是**原型 API 的差异**，
不是规则的一部分 —— `android.graphics.Canvas.rotate` 收**度数**，只有 HTML
`ctx.rotate` 收弧度。Kotlin 侧因此停在 `MoonPhase.terminatorRotDeg(tiltDeg) = -(tiltDeg + 90f)`，
⛔ **不要**为了"和文档一样"再乘回 π/180（乘了 90° 变 1.57°，月牙看起来"几乎不倾斜"，
`MoonPhaseShadowTest` 的 B2 专门有一条量级自证挡这个）。规则本身一字未改：**取负 + 加 90°**。

⚠️ 另需登记一条定稿事实：原型为把满月观感先调到位，加了演示用的 **`phaseLock: 'full'`** 开关
（所有者 2026-10-08 指示「先不搞月相吧，先把满月效果调好再说」）。
⇒ **§七/§六/§3.3 的所有观感参数都是在 `f=1` 下定的**，Kotlin 侧 ⛔ 不要把这个开关带进生产
（它属于 (f) 的不移植清单），且 §十三 V1/V2 必须在 `f` 为真实值时**重新判读一次**——
尤其 `haloBase`/`reflBase`/`glitBase` 三处都乘了 `st.f` 的钳制项，满月时它们取的是上界。

**验收含义**：⛔ 不要在满月/新月夜判读"月牙朝向"——那时没有朝向可判。朝向判读必须在
`f ∈ [0.15, 0.85]` 的夜（§十三 V3）。

### 4.5 墙钟接入规范（唯一允许的读法）

```kotlin
// MoonlitRenderer 字段（全部在 onEnterContent 建立）
private var wallAnchorMs = 0L      // 进入时的墙钟
private var monoAnchorMs = 0L      // 进入时的 fx.nowMs（单调）
private var phaseUtcMs = 0L        // 上次重算月相所用的 UTC 毫秒
private var phaseCache = MoonState.ZERO

override fun onEnterContent(ctx: RenderContext) {
    releaseResources()                                  // ⛔ 重入安全，见 E43 生命周期条款
    wallAnchorMs = System.currentTimeMillis()           // ✅ 合法：只喂日历纯函数
    monoAnchorMs = -1L                                  // 首帧再记（onEnter 拿不到 fx）
}

override fun DrawScope.drawContent(frame, ctx, fx) {
    if (monoAnchorMs < 0L) monoAnchorMs = fx.nowMs      // ⛔ 不在 onEnter 里记（§2.4）
    val utcMs = wallAnchorMs + (fx.nowMs - monoAnchorMs) // 单调推进，⛔ 不回头读墙钟
    if (utcMs - phaseUtcMs >= REPHASE_INTERVAL_MS) { phaseUtcMs = utcMs; phaseCache = MoonPhase.of(utcMs) }
}

private companion object { const val REPHASE_INTERVAL_MS = 60_000L }
```

- **为什么 60 s**：照度日变化率在 `e≈90°` 附近最大 ≈ 6%/天 ⇒ 60 s = 0.004%/次，肉眼零意义，
  但足以让"跨小时播放"仍与真实时间一致。⛔ 不必每帧算（每帧要 30 次三角函数）。
- ⚠️ **设备时钟可能错**（电视常年不通 NTP / 手动设过）。表现：月相与真实日历不符。
  本文**不做**任何兜底（无网络时间源可依赖），但 §十三 的 V1 判据要求所有者**先在电视上
  核对系统日期**——否则 V1 的失败会被误读成算法错。
- ⛔ 绝不允许的写法：`ctx.nowMs`（`RendererBaseContractTest:167`）、
  `System.currentTimeMillis()` 参与动画位移、每帧 `Calendar.getInstance()`（分配 + 受时区影响，
  `WorldTerminator.kt:219` 的同款理由）、`java.time`（minSdk 22 无脱糖，⛔ 只在真机上
  `NoSuchMethodError`，编译期不报错）。
- 时区：⛔ **不参与月相计算**（`MoonPhase` 只吃 UTC 毫秒）。时区只影响"哪一晚算哪一天"，
  而本效果按**时刻**而非按"日"取相位 ⇒ 无需 `TimeZone`，也就没有跨时区/夏令时的分配与不确定性。

### 4.6 测试向量（**实现 `MoonPhaseTest` 时逐条照抄**）

**A. 天象锚点（`illum` 与 `elongDeg`）** —— 即 §4.1 表，容差：`|elongDeg − 应为| ≤ 3.0°`。

**B. 连续向量（北京 21:00 = UTC 13:00，2026 年 10 月；`f` 容差 ±0.005，PA 容差 ±2°）**

| 日期 | 照度 f | e | PA | 月龄 | libW | libB |
|---|---|---|---|---|---|---|
| 10-05 | 0.280 | −63.9 | +106.1 | 24.29 | +3.44 | −0.35 |
| **10-08**（本文起草日） | **0.051** | **−26.0** | **+109.6** | 27.39 | +4.74 | +2.26 |
| 10-11 | 0.008 | +10.2 | −46.8 | 0.84 | +4.46 | +4.34 |
| 10-14 | 0.146 | +44.9 | −74.8 | 3.68 | +2.54 | +4.93 |
| 10-18 | 0.488 | +88.6 | −100.1 | 7.27 | −2.48 | +3.05 |
| 10-21 | 0.765 | +122.0 | −110.5 | 10.00 | −5.67 | +0.48 |
| 10-26 | 0.998 | +184.8 | +26.0 ⚠️退化 | 15.16 | −3.78 | −2.65 |
| 10-29 | 0.849 | +225.7 | +81.7 | 18.51 | +0.62 | −2.42 |

> ⚠️ **v1.1 修正 `e` 列的口径**（初版此处说明与本表实际不符）。`elongDeg()` 返回的是
> **wrap180 后的 −180..180**，而下表是**混合口径**：前六行（−63.9 … +122.0）落在这个区间内，
> 但 10-26 的 **184.8** 与 10-29 的 **225.7** 是"望之后继续单调增长"的记法
> （wrap180 后分别为 **−175.2** 与 **−134.3**）。⇒ ⛔ **绝不要把本表值直接当 `elongDeg()` 的期望值断言**，
> 那会在望后两天永久红。**比较必须写成角差**：
> `abs(wrap180(elongDeg − 表中值)) ≤ 1.5°`（原型即此式，8/8 命中）。
> 同理 10-08 的 −26.0 与"前文 +334.0"是同一个数（wrap 差异）。

⚠️ **PA 列对 10-26 有退化豁免**（§4.4）：原型对这一天**不参与 ±2° 判定**，
Kotlin 侧照此写（`|| ds == "2026-10-26"`），否则这条向量永远红。
月龄容差 ±0.05 日、天平动容差 ±0.30°（原型实测口径，G2 直接采用）。
> PA 列另有 10-26 的**退化豁免**（§4.4）：原型对这一天放宽到"不参与 ±2° 判定"，
> Kotlin 侧照此写，否则这条向量永远红。

**C. 2026 年朔望对照表（北京时区 UTC+8，本文算法值）**

| 北京时刻 | 类型 | 北京时刻 | 类型 |
|---|---|---|---|
| 01-03 18:10 | 望 | **07-29 22:30** | 望 |
| 01-19 03:40 | 朔 | 08-13 01:50 | 朔 |
| 02-02 06:10 | 望 | 08-28 12:30 | 望 |
| 02-17 19:50 | 朔 | 09-11 11:50 | 朔 |
| 03-03 19:40 | 望 | 09-27 01:00 | 望 |
| 03-19 09:10 | 朔 | 10-11 00:30 | 朔 |
| 04-02 10:20 | 望 | 10-26 12:30 | 望 |
| 04-17 19:40 | 朔 | 11-09 15:40 | 朔 |
| 05-02 01:30 | 望 | 11-24 23:20 | 望 |
| 05-17 04:00 | 朔 | 12-09 09:20 | 朔 |
| 05-31 16:50 | 望 | 12-24 09:40 | 望 |
| 06-15 11:00 | 朔 | 06-30 07:50 | 望 |
| 07-14 18:00 | 朔 | | |

> ⚠️ **v1.1 修正初版的 `07-26 03:00 望`**（T1 写 G2 的 C 表断言时才暴露：按本文算法复算，
> 北京 07-26 03:00 的 `e = +137.8°`、`f = 0.870`、月龄 11.3 日 —— 距同一张表自己的
> `07-14 18:00 朔` 只有 **11.4 日**，⛔ 不可能是望）。本文算法的七月望为
> **北京 07-29 22:30（= UTC 07-29 14:30）**，与已发表的 2026 年七月满朔日期一致；
> 上表已改为该值。教训：⛔ 逐日期核对**相邻朔望间隔**（应在 14.0–15.5 日之间），
> 单看一行是看不出来的。

其中 **02-17 朔 / 03-03 望 / 08-12–13 朔 / 08-28 望** 四项必须落在已发表的日食/月食日
（08-12 的"北京 08-13 01:50"对应 **UTC 08-12 17:50**，⛔ 别被日期换行误判成不一致 —— 这正是
§十三 V2 要防的读图错误）。

**D. 负向自证**（⛔ 缺了这套测试就没有判别力）
1. 把 `L` 速率改成 `13.179056` ⇒ 2026 锚点必须失败；
2. 把历元改成 `Jan 0.0` ⇒ 必须失败；
3. `f` 对 `e` 的对称性：`illum(t)` 与 `illum(t)` 在 `e → −e` 时必须相等（盈亏不影响照度）；
4. `PA` 权重：`w = |sin e|` 在 e=0/180 时必须 ≈ 0（否则 §4.4 的退化保护是空话）。

> ⚠️ **v1.1 把 D1/D3 的口径写死**（T1 实现时两处都会踩）：
> - **D1 判据只覆盖四个 2026 锚点**，⛔ 不是六个。`2000-01-06` 距历元仅 **5.26 日**，
>   速率差 `0.00266 °/日` 在此只累积 0.014°（该锚点实测错速率下读 0.09°，⛔ 仍 < 3°），
>   用它断言"必须失败"会把一条正确的代码判红。
>   实测角差：`2000-01-06` 0.09 / `2024-04-08` 23.7 / `02-17` 28.1 / `03-03` 25.2 /
>   `08-12` 25.9 / `08-28` 26.0（后五个全部 > 3° ⇒ 判别力成立）。
> - **D3 是"对 e 对称"，不是"对时间对称"**。⛔ 别写成 `illum(t₀+Δ) == illum(t₀−Δ)`：
>   月轨有偏心率、`e(t)` 非奇函数，实测 `Δ=3 日` 两侧 `e` 为 `+39.03/−40.92`、
>   照度差 **0.0106**（`Δ=7 日` 差 0.074），会把正确实现判红。
>   正确做法：在盈/亏两个单调支上各**解出** `e = +A` 与 `e = −A` 的时刻再比 `f`
>   （G2 用二分法解，A 取 10..90，九对实测差值恒为 **0**）。
> - ⚠️ 承上：二分的**端点必须取本文算法自己的朔望过零**，⛔ 不能取"已发表时刻"。
>   实测本文算法的八月望过零于 **08-28 04:20:46 UT**（比已发表的 `04:30` 早 **9.2 分钟**）、
>   八月的朔过零于 **09-11 03:48:10 UT**（比 `03:50` 早 **1.8 分钟**）—— 这就是锚点残差
>   +0.076° / +0.016° 的时间形态。拿发表值当端点会让区间**两端同号**，二分直接报
>   "不变号"（G2 首跑即此挂法）。
>   另：`e` 在望处从 +180 跳到 −180，二分必须先转成 **0..360 的连续距角**再求交；
>   而连续距角在**朔**处同样断掉（360 → 0），所以右端点取 `朔 + 29 日`（实测 354.7°）
>   比取"下一个朔"安全。

---

## 五、真实月球图：从等距圆柱纹理到正射圆盘

### 5.1 UV 反解（含天平动）

对目标圆盘位图 `T × T`（`T = 2·R_tex`），像素 `(px, py)`：

```
X = (px − c) / R_tex          // 东向，−1..1
Y = (c − py) / R_tex          // 北向，−1..1（位图 y 向下）
r2 = X·X + Y·Y
if (r2 > 1f) { out = 0x00000000; continue }        // ✅ 圆外透明 ⇒ 贴上去就是圆盘，⛔ 不需要 clipPath
z = sqrt(1f − r2)                                   // 指向观测者的分量
// 先按天平动纬度 b0 绕 X 轴、再按天平动经度 w0 绕 Y 轴旋转
lat = asin( Y·cos b0 + z·sin b0 )                   // 月面纬度
lon = atan2( X, z·cos b0 − Y·sin b0 ) + w0          // 月面经度（相对中央经线）
u = ((lon_deg + 180f) % 360f) / 360f · 1024f        // 等距圆柱：经度 → x
v = (90f − lat_deg) / 180f · 512f                   //          纬度 → y（北在上）
```

- 采样：**双线性**（4 邻域）。源图 1024×512 到 `R_tex ≈ 256` 是**降采样**，最近邻会出马赛克与
  闪烁；⛔ 不做抗锯齿以外的任何滤波（高斯在烘焙里等于自杀）。
- `w0/b0` 来自 §4.3 的 `libration*Deg`。实测范围：`libW ∈ [−6.2°, +4.8°]`、
  `libB ∈ [−2.8°, +4.9°]` ⇒ **一个可见半球会缓慢摆动**，这是"真实"最容易被认出来的地方
  （大月海会在 limb 附近进出视野）。

### 5.2 烘焙节奏与预算（抄 E43 的自适应步长，含它踩过的单位坑）

| 常量 | 值 | 来源/理由 |
|---|---|---|
| `DISK_TEX_R` | `clamp(ceil(moonR × 1.25), 192, 512)` | 1.25 给 1080p→4K 与轻缩放留余量；512 上限受源图 512 高的行采样限制 |
| `DISK_BAKE_ROWS_MIN` | `1` | ⛔ 无论多慢都必须前进，否则永久停在平色（E43 `SeasideRenderer.kt:677/:3157` 同条） |
| `DISK_BAKE_ROWS_MAX` | `64` | 对齐 `:689` |
| `DISK_BAKE_FIRST_ROWS` | `4` | 首段实测单行耗时用（`:698`），⛔ 首段不能太长，否则进入即冻结 |
| `DISK_BAKE_BUDGET_MS` | `34` | 逐字取 `:725`（一帧 60 fps 的预算余量） |
| 步长公式 | `rowsPerFrame = clamp(BUDGET / singleRowMs, MIN, MAX)` | `:3150`；⚠️ **`singleRowMs` 必须是毫秒** —— `:3133/:3143` 记录过一次"纳秒当毫秒"导致步长恒为 MIN 的真 bug |

- 未完成期间的显示：⛔ **不要**先画一个纯白圆再"突然变成有环形山的月"（切换感 = 缺陷）。
  做法：位图从**顶行开始**逐段 `drawBitmap`（`src` 取已烘行区间，`dst` 同比例），未烘部分留空，
  整盘约 1–2 帧内完成（512 行 ÷ 64 行/帧 ⇒ ≤ 8 帧，1080p 实测会更少）。
- **重烘条件**：`quantize(libW, 0.5°)` 或 `quantize(libB, 0.5°)` 或 `R_tex` 变化。
  天平动速率峰值 ≈ 1.3°/天 ⇒ 0.5° 量化 ≈ 每 9 小时一次，⛔ 一场播放里通常 0 次。
  ⚠️ 重烘必须走**同一套增量步长**，⛔ 不许在 `drawContent` 里同步烘整盘（那会造成一次
  80–150 ms 的硬冻结）。
- 实测预估（待 §十三 校正）：512×512 圆盘 = 196,608 圆内像素，双线性 + 1 次 `sqrt` +
  1 次 `asin` + 1 次 `atan2` ⇒ 按 E43 沙纹理实测 4.92 ms/行（1080 宽）折算，本烘焙
  ≈ **每 512 行 0.5–1.5 s**，分帧后每帧 ≤ 34 ms ⇒ 与 E43 同量级。⛔ 该数字是**外推**，
  上机必须实测（U-档判据）。

#### 5.2+ 烘焙的色彩曲线（定稿实测，⚠️ 两处都会让人重复踩）

**① 单位红线（原型真炸过）**：源像素 `rgb[]` **已经是 0..255**，而对比量 `g` 是归一化亮度 0..1。
旧写法 `rgb × g × tint × ld × 255` 等于把 0..255 又乘了 255 ⇒ **整盘顶格 255**，
月面变成一张**死白圆片**（原型实测：圆盘直方图 **78.4%** 落在 224–255 桶、中心 5×5 全 255）。
⇒ 对比曲线只当**增益**用，⛔ 绝不额外乘 255。Kotlin 侧同样：`getPixel`/`IntArray` 取到的就是 0..255。

**② 先降反差，再谈增益**（`DISK_GAIN` 提不亮的原因）：
定稿曲线 `gain = 0.72 + 0.55·g`（前几轮是 `0.45 + 0.95·g`）。判据是**参考图月盘其实是"平"的**：
PIL 复量盘内均值 `rgb(210,169,98)`、盘心过曝峰 254 ⇒ **峰/均只有 1.21**。
而旧曲线配 `DISK_GAIN ≥ 1.5` 时三通道**全部顶格** ⇒ 高地纯白、月海仍灰，峰/均 1.49，
截图读作"一张发白的圆片"（实测盘内均值掉到 160）。⛔ **提亮月盘直接乘增益只会更白**。

**③ limb darkening 替身**：`ld = 1 − 0.16·(r/R)²`（边缘轻微减亮），⛔ 不要做真实的临边昏暗公式。

**④ 过曝芯（`BLOOM`，层 5 内、相位阴影**之前**）**：定稿新增的第 15 个 op。
`softEllipse(moonCx, moonCy, R·1.28, R·1.28, bloomA, hotOf(HOT_K), hotAOf(HOT_K·0.90, 0), 0.30, 0.70)`
在**加法混合**下绘制，`bloomA = clamp(0.16 + 0.20·(1 − occl), 0, 1) × diskA`。
四条已实测的约束，⛔ 一条都别改回去：
- ⛔ 必须走**加法**（`BlendMode.Plus`，§3.0(f) 第 3 条）。普通覆盖会把月海一起刷成一块死白。
- ⛔ 颜色乘数**不得超过 1.0**（`HOT_K` 已经是"最大通道正好 255"）。第六轮三次修就是栽在
  乘了 1.16 ⇒ 单通道自己顶到 255 ⇒ G/B 一起抬 ⇒ 盘体从金色比值 `(1,0.81,0.47)` 脱色成
  `(1,0.94,0.70)` 的**白饼**。**色相一旦被顶格就永久丢失。**
- α 只走 `diskA`，⛔ 不再乘照度 `f`（§3.0(c) 不变式 2 / §5.4 的"双重变暗"老坑）。
- 画在相位阴影**之前** ⇒ 月相仍能压住它，只有满月/近满月才吃到这口过曝。

⚠️ 定稿同时把 §5.1 的**色温乘在烘焙期**（`tint` 进像素）而非叠加矩形 ⇒
省掉一次全屏混合，也天然免掉 `clipPath`（圆盘外像素由 UV 反解保证）。
⭐ **这个 `tint` 是三个系数的乘积**（原型 `:976`，T4 落地实测补齐的口径）：
`tint = tintMul × lumK × DISK_GAIN`，满月构图下 = `(1.3294, 1.0643, 0.6156)`。
只乘 `tintMul`（= 把最大通道归一到 1.0）时盘内 R 均值实测 **134**，整盘发暗 ——
`lumK` 是照度标度、`DISK_GAIN` 是盘体增益，两个都漏不得（偏离记录见 §十一 T4 ②）。

### 5.3 解码通路（全仓第一个 asset→Bitmap，⚠️ 需要所有者知情的两点）

```kotlin
// onEnterContent（⛔ 不在 drawContent；也不在工厂构造里做 IO）
private fun decodeMoonMap(appCtx: Context): Bitmap? {
    val bytes = try { appCtx.assets.open("globe/moon.jpg").use { it.readBytes() } } catch (t: Throwable) { null }
        ?: return null
    return try {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888     // ⛔ 不用 RGB_565，见下
        })
    } catch (t: Throwable) { null }                          // 解码失败 ⇒ 走 §5.4 降级
}
```

- **必须 `appCtx.applicationContext`**：`RendererSwapper.kt:35` 的 `context` 来自
  `VisualizerStage.kt:155` 的 `LocalContext.current`（Activity 派生）。渲染器生命周期比
  Composable 长 ⇒ ⛔ 直接持有会泄漏。工厂分支写成
  `MOONLIT -> MoonlitRenderer(context.applicationContext)`。
  ⚠️ 这是**第一个**持 `Context` 的 Canvas 渲染器，§十 为它单独设一条 grep 门禁。
- **`ARGB_8888` 而非 `RGB_565`**：月面是低对比明暗斑块，565 的红/蓝 5 bit 会在暗部出
  明显条带（`PhotoBuffer.kt:272` 用 565 是**照片墙**的内存取舍，那里的图像对比高、条带被噪声掩盖）。
  代价：1024×512×4 = **2.0 MiB**（API < 26 在 native 堆，`PhotoBuffer.kt:45-48`）。
- **IO 线程**：⚠️ 本文选择**在 `onEnter` 同步解码**（2.0 MiB JPEG，本地 asset，
  预计 15–40 ms）。⛔ 不引入 `Executor`/`Handler` 三件套（`PhotoBuffer.kt` 那套是为**网络图**
  与 40 MiB 预算设计的）。若 §十三 实测进入耗时 > 80 ms，改异步并把 `Executor` 实例
  在 `onExitContent` 关掉（⛔ 不要留线程池）。
- **释放**：`onExitContent` 里 `recycle()` 源图与圆盘位图、置 `null`。
  ⛔ 不要依赖 GC（API 22 native 位图不自愈，H4 修复的同款理由，`RendererFx.kt:72-83`）。
  共享纹理（`OverlayFx` / `ProceduralTexture`）由基类释放，⛔ 子类不要再调 `release()`。

### 5.4 降级（asset 缺失 / OOM / 解码失败）

`map == null` 时退化为**程序化月面**：`Shading2D` 的 `shadeBrushCached` +
`ProceduralTexture` 的 fbm 斑块画 5–7 块深色月海椭圆（保留"有细节的盘"这一身份），
照度与阴影逻辑**完全不变**。⛔ 不许退化成纯色圆盘（那就把需求 1 丢了），
⛔ 也不许因此跳过绘制（会黑屏）。该分支必须有单测（§十 G9）。

> ⚠️ **T4 落地时的机制偏离**（2026-10-09，见 §十一 T4 ④）：实际做法不是"用 `ProceduralTexture`
> 直接画一张盘"，而是**程序化生成一张 256×128 的等距圆柱源图**（`MoonDiskBake.synthesizeFallback`：
> 高地基色 + 单八度 `SeasideWaves.vnoise2` 斑驳 + 6 块按真实位置摆放的月海椭圆 + 2 处亮射纹斑），
> 然后走**与真素材逐字相同**的 §5.1 UV 反解 / §5.2+ 曲线 / 贴图 / `BLOOM` 管线。
> 理由：① 本节明令"照度与阴影逻辑完全不变"，共用管线才是**字面意义上**的不变（另起一条绘制分支
> 必然要复刻色温、临边昏暗与后续相位几何）；② `ProceduralTexture` 新增槽位受"不得新增纹理 Id"
> 的红线约束，而复用它的 `fbm` 也拿不到"按经纬度摆月海"的形状；③ 分辨率取 1/4 边长是因为
> 它跑在 `onEnterContent` 的**同步**路径上（每像素 6 次月海判定 + 1 次噪声）。
> ⚠️ 亮度基线（实测补齐）：真素材灰度 mean **130.6** / p50 138 / p95 207，程序化基色必须同量级
> （定稿 `118 + 56·噪声`）；抬到 172 级会让降级盘过完曲线后顶格占比越过 G10 的 8% ⇒ 变成白盘。

---

## 六、云：漂移的**烟缕场**与「云遮月」的单一真源

> ⚠️ **章名与模型在定稿时改过**：初版设计的是"扁椭圆团"（积云配方：平底 + 参差顶 + 上亮下暗）。
> 所有者第三轮判「云的形状还是不对，**应该是烟雾的样子**」⇒ 第四/五轮整体改为
> **沿蛇形脊线排布的拉长软斑缕**（烟）。⛔ 不要再按"团"实现，也不要把积云配方当"细节补充"加回来
> （它已被明确否掉，且第四轮那三趟"受光上穹 / 云底暗带"就是被否的东西）。

### 6.1 烟缕场定义（⛔ 不采样任何纹理场）

**层级结构**：`缕（plume）→ 沿脊排布的软斑（puff）`。**数量的单位是"缕"，⛔ 不是"斑"**
（⚠️ 这条口径直接影响预算表与 §2.2 的可核对性，初版没区分，定稿钉死）：

| 量 | LOW | MED | HIGH | 含义 |
|---|---|---|---|---|
| `CLOUD_FAR` | 2 | 3 | 3 | 远层**缕**数（在月背，⛔ 不参与 `occl`） |
| `CLOUD_NEAR` | 2 | 3 | 4 | 近层**缕**数（可遮月） |
| `BLOB_FAR` | 4 | 5 | 6 | 远层每缕的**软斑数**（沿脊截断） |
| `BLOB_NEAR` | 7 | 10 | 11 | 近层每缕的**软斑数** |

⚠️ **斑数按档截断**（`blobs` 恒按 `BLOB_NEAR.HIGH` 播种、绘制时只取前 `np` 个）
⇒ ⛔ 切画质档不重播种，观感连续、也不产生"切档云闪一下"。

```kotlin
private class CloudPuff(          // 一缕沿脊的一个软斑：定稿的"最小可画单元"
    val t: Float,                 // 沿脊站位 0..1（定死 ⇒ 切档只是截断）
    val rK: Float,                // 斑半径 / 局部半厚     定稿 0.85 + rng·0.75
    val sq: Float,                // **横纵比**            定稿 2.30 + rng·1.80
    val off: Float,               // 垂直于脊的偏移(×半厚)  定稿 (rng·2−1)·0.22
    val spd: Float,               // 该斑的相位速度        定稿 0.85 + rng·0.30
    val aK: Float,                // 该斑相对缕 α 的系数    定稿 0.40 + rng·0.60
    val ph: Float, val ph2: Float,
)
private class CloudPlume(
    var x: Float,                 // 归一化站位 0..1，**累加推进**（见下）
    val bandY: Float,             // 带位（相对天空高度）near 0.14+rng·0.62 / far 0.42+rng·0.46
    val lenK: Float,              // 一缕**全长** / minDim   near 0.50+rng·0.30 / far 0.44+rng·0.26
    val thickK: Float,            // 中段**半厚** / minDim   near 0.040+rng·0.028 / far 0.033+rng·0.022
    val tilt: Float,              // 脊的纵倾(×半厚/半长)     (rng·2−1)·(near 0.34 / far 0.20)
    val wavK: Float,              // 蛇形幅度(×半厚)          0.30 + rng·0.48
    val wavN: Int,                // 脊上的波数 1..2
    val speedK: Float,            // (near?SPD_NEAR:SPD_FAR)·(0.80 + rng·0.44)
    val alphaK: Float,            // min(CLOUD_A_MAX, (near?0.52:0.26) + rng·(near?0.30:0.20))
    val wobPhase: Float,
    val puffs: Array<CloudPuff>,
    // 运行时字段（每帧写）：cx, cy, gw(半长), gh(半厚), w/h(blob 包络半轴，occl 用)
)
```

**位置积分（⭐ 定稿红线，⛔ 不许回到绝对时间）**：

```kotlin
b.x = wrap01(b.x + b.speedK * spdMul * dtSec)    // ✅ 速度只作用在**增量**上
b.cx = (b.x * 1.3f - 0.15f) * w                  // 1.3 倍画面宽 ⇒ 进出场都在屏外
b.cy = horizonY * b.bandY
```

⛔ **旧写法 `wrap01(baseX + speedK · t · spdMul)` 是真 bug**：把**绝对时间**乘上**逐帧随音频变化**的
系数 ⇒ 摆幅 = `speedK·ΔspdMul·t`，**跑得越久晃越大**，`spdMul` 一回落云就**整段倒退**
（所有者原话：「云行进的路线为何来回变，一下向左一下向右，有些抽搐」）。
累加增量后 `dtSec` 恒 > 0 ⇒ ⛔ 永远不会倒退。同一理由适用于所有"呼吸/翻滚/蛇形"项：
**只能是有界正弦**，⛔ 任何随 `t` 线性放大的纵向项都会变成抽搐。

**速度定稿值**（横穿时间按 1.3·W 算）：`SPD_FAR = 0.0070 /s`（≈143 s 穿一遍）、
`SPD_NEAR = 0.0165 /s`（≈61 s；1600 宽实测 ≈35 px/s）。
⚠️ 这两值是**所有者三轮连续要求放慢**的结果（远 0.020→0.011→0.0070，近 0.050→0.026→0.0165），
⛔ 不要以初版的 0.006/0.014 为准，也不要"顺手调快一点看起来更有动感"。
音频调制仍是 ±25%（§八 `mid`），⛔ 不许被鼓点"跳"。

**形状：脊线 + 两端收尖 + 呼吸**（`layoutPuffs`，逐斑）：

```
sy   = cy + gh·( tilt·u·2.2                                   // 脊的纵倾（u = (t−0.5)·2 ∈ −1..1）
               + wavK·sin(TAU·SPINE_HZ·tSec·spd + ph + u·PI·wavN)   // 蛇形，相位随时间慢走 ⇒ "烟在扭"
               + DRIFT_Y·sin(TAU·DRIFT_HZ·tSec + ph2) )       // 整缕纵向漂移（有界）
px   = cx + u·gw
taper = 0.26 + 0.74·sin(PI·t^0.78)                            // ⛔ 两端不收尖会读作"一条带子"
boil  = 1 + BOIL·sin(TAU·BOIL_HZ·tSec·spd + ph2)              // 斑自身翻滚
rad   = rK·gh·taper·boil ;  rx = rad·sq ;  ry = rad           // 横长竖短 ⇒ 烟丝被风拽长
py    = sy + off·gh·taper
puffAlpha = 1 − (1 − alphaK·aK)^(1/BLOB_OVERLAP)              // ⭐ 见下
```

**定稿的时间频率常量**：`WOB = 0.10, WOB_HZ = 0.055`（缕宽呼吸）·
`BOIL = 0.20, BOIL_HZ = 0.19`（比积云更慢更柔）· `SPINE_HZ = 0.045` ·
`DRIFT_Y = 0.045, DRIFT_HZ = 0.031`。
⚠️ `DRIFT_Y` 第七轮从 0.075 收到 0.045，理由是一条**耦合律**：`thickK` 抬了 1.35 倍 ⇒
漂移的**绝对像素**（`DRIFT_Y·gh`）跟着一起涨 ⇒ 必须同时回收系数，否则"上下幅度太大"。
⛔ 改 `thickK` / `lenK` 时必须重新结算这几个常量的像素产物，不能只看归一化值。

`wavK` 定稿 **0.30~0.78**（初版/第四轮是 0.70~2.00）。所有者：「云上下运动的幅度太大了，
一般只有很小的上下运动」——旧值平均 `1.35·gh`，`gh≈38 px` ⇒ 每斑纵向走 **±51 px**，读作"上下翻跟头"。
⛔ 但**不能归零**：蛇形是"烟"区别于"横条"的**唯一纵向内容**，归零后一缕就是一根直线。
定稿的分工是：**纵向体量**交给 `thickK`，**纵向运动**只留这条小摆幅。

**⭐ `sq`（斑横纵比）与"横条"根因**：所有者第七轮「现在云的形状全都是横条，要拉高一些」。
根因不是 `sq` 单独太大，是**前几轮的"大"全加在长度上**（`lenK` 0.62~0.98 ≈ 半屏宽）而厚度只有 0.03
⇒ 一缕的横纵比 **20:1**，配上 5.5:1 的斑自然读作拉丝。定稿同时改三处：
**`lenK ×0.82`、`thickK ×1.35`、`sq 5.5→3.2`** ⇒ 斑面积变化 `1.35² × 3.2/5.5 = 0.96` ≈ **不变**
⇒ ⭐ **这一改不吃填充率**（§3.0(f) 成本模型的应用：改形状靠**等面积重排**，⛔ 不是放大）。
⛔ 不要只抬 `thickK` 不动 `sq`：斑本身是扁透镜，加厚后会变成"一摞飞碟"。

**⭐ 单斑 α 的反解（`BLOB_OVERLAP = 2.0`）**：一缕的软斑**平均叠 2 层**，
所以 ⛔ 不能直接拿缕 α 去画每个斑（那会合成成实心白块）。定稿用
`puffAlpha = 1 − (1 − alphaK·aK)^(1/2)` 反解 ⇒ **合成后 ≈ 缕 α**。
⚠️ 这是"把观感目标写成可解表达式"的例子，Kotlin 侧照抄即可，⛔ 不要退回"经验系数"。
`CLOUD_A_MAX = 0.78`（⛔ 别用 0.62：第五轮实测 0.62 配稀疏散布**直接看不见云**；
也别用积云时代的 0.86，烟是半透的）。

**远层必须比近层暗得多**（定稿实测）：远层画的是 `rgb(26,33,50)`、α 再乘 `0.55`，
读作"略亮于天底的一层纱"。⛔ 不要按近层的亮度画远层——上一版那样做，天上排成一串
**发光泡泡**。带位也为此重排：远层从 `0.07~0.67` 收到 **`0.50~0.90`（贴地平线）**，
因为旧写法把"远"画成了"高" ⇒ 天顶挂着一根根淡柱子，既不像云也不像雾（真实夜里的远低空才有一带薄云）。

**为什么走向量而不是走向场纹理**：① `Id.FOG` 不可无缝滚动（§二 表）；② **遮挡标量必须可解析
计算**（§6.2），走纹理就得每帧读像素（`nativeCanvas.drawBitmap` 之外无回读通路，回读 = 灾难）；
③ 需求 2 只要求"云在动、会遮月"，不要求云有真实纹理。

⚠️ **Brush 缓存**：渐变必须按 `(w, h, sizeBucket)` 缓存（`OverlayFx.kt:23-26` 的键规则：
⛔ 缓存键维度必须 ⊇ 对象真实依赖的维度），否则每帧为每个斑分配一个 `RadialGradient`
——本效果每帧 MED 有 15+30 = 45 个斑，这是**唯一**会产生 GC 压力的地方。

⭐ **as-built（T7）**：这条"唯一 GC 压力"的处置比原方案更省 —— 软椭圆着色器建在**单位圆**上
（半径 1），尺寸走 `canvas.translate + canvas.scale(rx, ry)`（`MoonlitRenderer.kt:796-804`）
⇒ **尺寸根本不进缓存键**，逐帧只有 `paint.alpha` 一个变更；缓存只剩**颜色**一个维度，
按 `(k, altT, back)` 分 `16 × 4 × 16` 桶（⚠️ 这是 **D23**，与上面那条 `(w, h, sizeBucket)` 键规则
是同一件事的两半：把尺寸烤进着色器会让 44 个斑各建一张、且画幅一变全废）。
`coreK/coreA` **不进键**：云体用常量 `CLOUD_CORE_K/CLOUD_CORE_A`、银边用 `RIM_CORE_K/RIM_CORE_A`
⇒ 远层与银边各只需**一张** shader（色本身是常量 `farRgb()`/`rimRgb()`），只有近层云体因为
颜色随 `(k, altT, back)` 连续变化才需要那张 1024 槽的分桶缓存（实际在场 ≤ 44 且缓漂）。
⚠️ 亮度**只走 paint alpha**（Skia 用 paint α 调制着色器输出），⛔ 不改色停。
其余逐字落地：`SEED = 20261008` 的播种次序（`lenK→thickK→tilt→wavK→wavN→11×(rK,sq,off,spd,aK,ph,ph2)→
x→bandY→speedK→alphaK→wobPhase`）、`MoonLcg`（`s = s·1664525 + 1013904223` 靠 `Int` 自然溢出等价于 JS 的 `| 0`，输出把 32 位状态
**按无符号读**再除 `2³²`，即 `(s.toLong() and 0xFFFFFFFFL) / 4294967296.0`，⛔ 不是 `abs(s)/2³¹`）
与 JS 侧**逐位一致** ⇒ G3 ①② 能以 `delta = 0.0` 对账；斑沿脊站位定死为 `(i+0.5)/11`
（`PUFF_SEED_COUNT = PUFF_NEAR_HIGH`）⇒ **切档只是把读取上界调小**，尾部斑的运行时字段原封不动（⑧）。
⚠️ 一处**同名不同物**要防：`MoonPuff.altT` 是**斑相对本缕脊线的高度**（受光坐标），
⛔ 与 §3.3 月空地平夹角的 `MoonSeascape.ALT_T = 0.6875`（固定构图常量）无关，原型里两个都叫 `altT`。
⚠️ 形态偏离（**D25**）：§6.1 的数据类草图写 `Float`，as-built 一律 `Double` —— 对账基线是 JS 的
float64，`Float` 会在第 7 位有效数字上舍入，①② 的 `delta = 0.0` 直接红。


### 6.2 `occl`：云遮月的**唯一真源**（需求 3 的核心）

```kotlin
/** 纯函数 ⇒ 可单测。输入近层云与月心几何，输出 0..1 的遮挡率 */
private fun occlusion(moonCx: Float, moonCy: Float, moonR: Float): Float {
    var sum = 0f
    for (i in 0 until nearCount) {
        val b = nearBlobs[i]
        val dx = (b.cx - moonCx) / (b.w + moonR * 0.6f)      // 60% 缓冲 ⇒ 半遮也算部分遮挡
        val dy = (b.cy - moonCy) / (b.h + moonR * 0.6f)
        val d2 = dx * dx + dy * dy
        if (d2 < 1f) sum += b.alphaK * (1f - d2)             // 平滑核，⛔ 二值门
    }
    return sum.coerceIn(0f, 1f)
}
```

⚠️ **必须走平滑核**，⛔ 不要写成"云在月上就遮、不在就不遮"的二值门 —— E43 的
`drawWetWash`/`drawSheen` 正是被提交数逼成二值门而掉保真（`SeasideOpBudget.kt:486` KDoc 的
自我批评），不要把同一个错误带进来。

⭐ **定稿补一条几何一致性要求**：这里的 `b.w`/`b.h` **必须是"该缕全部软斑的 AABB 包络"**，
在 `layoutPuffs` 里**铺斑的同一趟**回写（`w = max|px−cx| + rx`，`h = max|py−cy| + ry`）。
理由：⛔ 不能出现"遮了但没画"或"画了但没遮"——包络若与绘制几何分家（例如另写一个
`lenK·minDim·0.5` 的解析式），斑的 `taper`/`boil`/`off` 一项都不在里面，`occl` 就会与实际
像素脱钩，需求 3 的同源性被静默破坏。`OCCL_PAD = 0.6f`。

⚠️ **定稿写的"自然上限 ≈0.33"是错的**（T7 更正，偏差 **D24**）：那个数出自一个把自己量废的
探针 —— 它用 `const minDim` **遮蔽**了 vm 沙箱里的同名全局 ⇒ 传进缕几何的 `gw/gh` 恒为 0，
整场云退化成一个点。⛔ 这类"探针错、规格抄"的事故之所以能进定稿，是因为当时**没有可复跑的脚本**。

⭐ **as-built（2026-10-09，T7）**：口径改由 **`docs/archive/verification/scripts/moonlit_cloud_golden.js`**
承载 —— 它从 `docs/moonlit-preview.html` **原文抽出** `makeCloud`/`layoutPuffs`/`stepClouds`/`occlusion`
在 `vm` 沙箱里跑（⛔ 不手抄公式，那等于拆掉哨兵），并把结果再生成
`app/src/test/…/MoonlitCloudBaseline.kt` 供 **G3** 逐位对账。生产几何
（1920×1080 定稿构图、HIGH 档 3 远 + 4 近缕 × 11 斑、`dt = 0.1`、`spdMul = 1`）跑
**8000 帧 ≈ 800 s** 的实测**分布**：

| 态 | `peak` | `mean` | `occl > 0.5` | `occl ≥ 0.999` | `occl > 0.001` |
|---|---|---|---|---|---|
| 自然态 | **1.0** | 0.3059 | **26.0%** | **1.71%** | 69.1% |
| 过境态（§6.6，钉近层 0 号缕） | 1.0 | 0.4618 | 46.9% | **9.98%** | 81.7% |

⇒ **自然云场本来就遮得满月**，只是"遮满"是偶发事件（触顶占比 1.71%）；§6.6 的确定性过境
买到的不是"能不能吞月"，而是**触顶时长与周期性**（触顶占比 **5.8×**、`occl > 0.5` 从 26% → 47%）。
⚠️ 均值 0.306 意味着**大部分时间月盘是干净的**，这是云场的正常形态，不是缺陷。
⛔ 这五个数**只许住在基线文件里**：`MoonClouds` 曾另存一份 `OCCL_NATURAL_MAX = 0.53` 手调常数，
T7 已删 —— 两处各自的数迟早漂移（正是 D24 的成因）。
⛔ 也**不要**把 `occl = 1` 说成"自然到不了的值"：G3 推它到 1 靠的是纯函数，但几何本身也会自然到达。

这条曾是 §十六 **Q7** 的题面。Q7 已拍板（2026-10-09：「Q7：选 3」）⇒ 走**确定性过境**，
规格见新增的 **§6.6**（✅ 已随 T7 落地）。⛔ 不是把 `occlManual` / `cloudPassT` 两个演示开关
移植过来（R13）—— §6.6 是**纯几何**：指定一缕近层的 `cy` 钉在月心高度，它按自然速度绕屏一圈
就必然过一次盘。✅ D24 之后该裁决**依然成立且不必重开**：它的目的从来是"让厚云态可预期地出现"，
而不是"补一个遮不满的洞"。

### 6.3 `occl` 的**五个**消费点（同帧同值 ⇒ 天空与海面必然一致）

| 消费点 | **定稿式** | 效果 |
|---|---|---|
| ① 盘面 alpha | `diskA = 1 − 0.92·occl` | 云遮月：月被压到 8% 亮度（⛔ 不是 0，见 6.5） |
| ② 晕 alpha | `haloBase = clamp(f·1.15 + 0.05, 0.05, 1) · (0.55 + 0.45·altT)`；`haloA = haloBase·(1 − 0.75·occl)` | 晕先消失、月后消失（图 3：厚云下只剩一团乳白光）。⚠️ 定稿给 `haloBase` 补了 **altT 项**（月低时晕更大），初版没有 |
| ③ 逆光云边 | `rimA = 0.35 · clamp(occl·1.6, 0, 1) · (1 − occl)` | 半遮时最亮（全遮时无光可散）——⚠️ `·(1−occl)` 是**物理必需**，不是调参。⭐ 定稿新增 `clamp(occl·1.6)`：没有它 `rimA` 的解析极大只有 `0.35·0.25 = 0.0875`，加上增益后极大 **0.14**（仍在 `occl = 0.5`）⇒ 银边亮 **1.6 倍**。⚠️ 定稿当年把这条写成"因为自然 `occl` 只到 ≈0.33"，该前提已被 **D24** 证伪；但**公式一字未改**（所有者裁决「只改判据，不动观感」），因为增益的作用区间本来就在**半遮**（`occl ≈ 0.1..0.5`）而不是触顶 |
| ④ 倒影亮度 | `reflBase = 0.44·clamp(f·1.3, 0.06, 1)`；`reflA = reflBase · diskA` | ⭐ **需求 3**：水面随天上的遮月同步变暗 |
| ⑤ 粼光路 | `glitBase = 0.62·clamp(f·1.45, 0.04, 1)`；`glitA = glitBase · diskA² · (1 + 0.25·aTreb)` | 平方 ⇒ 粼光比倒影更早消失（真实水面：碎金路对光照最敏感） |

⚠️ **定稿把初版 §7.4 说的"晕先消失"降级了**：按上式，`occl → 1` 时 `haloA → 0.25·haloBase`
而 `diskA → 0.08` ⇒ **晕其实比盘更晚归零**。初版"晕先消失、月后消失"的文字与自己的系数**互相矛盾**
（原型实测暴露的 8 处之一）。定稿的读法是：**晕的能量先散**（面积大、α 低，视觉上门先没），
但**标量上它归零得最晚**。⛔ 不要为了对齐那句文字去改系数——那会让图 3 的"乳白扩散"（V9）消失。

⛔ **禁止**为水面另算一套遮挡（例如"水面云影"再引一个噪声场）—— 那会产生
"月被云吞了、水里却有一条完整金路"的**不一致**，正是需求 3 点名的情况。

⭐ **as-built（T7）**：五个消费点全部落在 `MoonClouds.kt` 的纯函数上（`diskA` / `bloomA` /
`darkEdgeA` / `haloA` / `reflA` / `glitA`），渲染器**只调不写公式** —— T6 留下的三个刻意常量
（`DISK_A = 1f`、`BLOOM_A = 0.36f`、`DARK_EDGE_A = 0.10f`）自此**全部删除**。
`occl = 1` 那端的实测值：`diskA = 0.08`、`bloomA = 0.0128`、`darkEdgeA = 0`、
`haloA = 0.25·haloBase`、`reflA(1,1) = 0.0352`、`glitA(1,1,0) = 0.003968`。
⚠️ 最后一个是**结构性事实**：满遮时粼光的 α 落在 `MIN_ALPHA = 0.004` **之下**，而 T7 落地的
`softEllipse` 入口就有 `alpha < MIN_ALPHA ⇒ return`（`MoonlitRenderer.kt:796`）⇒ T8 的粼光只要
走这条唯一图元，云吞月的同帧它就**一条也不提交**，⛔ 不需要（也不许）再为它写第二条门。
G3 除逐点数值外，还钉**同源比恒等式** `reflA(f,o)/reflA(f,0) ≡ diskA(o)`、
`glitA(f,o,0)/glitA(f,0,0) ≡ diskA(o)²`（对 `f` 取任意值都成立 ⇒ 水面"跟不跟天上的云"
不再取决于某个取样点的巧合）。
✅ **T8 兑现**：粼光**只**走 `softEllipse` 这一条图元（G14 ② 钉条数、⑧ 钉层序），满遮帧"一条也不提交"
由入口早退**自动**发生 ⇒ ⛔ 水面侧没有为此新增第二条门，本段那句禁令被实测确认。
⚠️ 但**别把它读成"云吞月 ⇒ 水面变空"**：G14 ⑤b 查清了存在门的实际作用域 —— 光柱只在
「**新月 ∧ 满遮**」那个角点才闭合（`reflA(0,1)=0.002112 < MIN_ALPHA`），满月在云后仍有
`reflA(1,1)=0.0352` ⇒ 云遮月的帧是**粼光熄、光柱在**。

### 6.4 逆光边缘（图 4 的关键细节）—— 定稿改为**斑级软椭圆**，⛔ 不用 `drawArc`

初版设计的是"`drawArc` 一段朝向月心的弧 + `Stroke`"。定稿换成了**沿朝月轮廓摆亮斑**，
理由是实测：⛔ **闭合环（`softRing`）压在云体里的半圈会被吃掉、露在背光侧的半圈反而完整**
⇒ 月盘下方挂出一对**亮括号**（原型截图判读）。而描边弧在 API 22 上还要 `Stroke` 缓存，
换成软斑后**与云体共用同一个图元**（§3.0(f) 第 1 条），零新代码路径。

```kotlin
// 近层每个斑：先算"压在盘上的程度"back，再决定要不要补银边
val rimK = (rimA / 0.175f).coerceIn(0f, 1f)                     // ⭐ 归一化，见下
if (level != FxLevel.OFF && rimK > 0.10f && back > 0.34f) {
    softEllipse(px + ux·rx·0.42f, py + uy·ry·0.42f,            // ux/uy = 朝月单位向量
                rx·0.55f, ry·0.70f,
                (back·(0.10f + 0.90f·rimK)·(0.60f + 0.40f·rimBeat)).coerceAtMost(0.50f),
                CLOUD_RIM /* rgb(238,228,206) */, 透明同款, 0.24f, 0.40f)
}
```

- ⭐ **亮度主项必须用 `rimA / 0.175` 归一化**（`rimA = 0.35·1.6·occl·(1−occl)` 的解析极大值就是
  `0.35·1.6·0.25 = 0.14`，实测峰值 ≈0.175）。初版写成常数式 `0.14 + 0.85·rimA` ⇒
  **被常数项主导** ⇒ "云一挨着盘就长边"，无论遮多少都亮。归一化后银边**只在半遮时出现**。
- `back ∈ 0..1` = 该斑压在月盘上的程度，用斑心到月心的距离、在**边缘留 `rx·0.6` 过渡带**算：
  `back = clamp(1 − (pd − rx·0.6)/(R·1.15), 0, 1)`（⛔ 硬边会切出一条亮轮廓线）。
- 数量：`LOW 0` / `MED 9` / `HIGH 15` 个斑（是"画了银边的**斑**数"，不是缕数）。
  ⚠️ LOW 档 `FxLevel.OFF` ⇒ 整段跳过，这也让 §9.1 的 `CLOUD_RIM` 成为**条件项**（应记 `≤`）。

⭐ **背光剪影（本效果的"云不像"主因，⛔ 别漏）**：云画在盘**之后**（层 6 在层 5 之后合成），
压在月盘上的斑物理上是**背光**的 ⇒ 它该**比夜空还暗**，⛔ 不能跟着"靠月越近越亮"的 `lit`
一起提亮。初版正是错在这里（云一到月盘前就变成一团白雾 ⇒ 所有者「云不像，太不像了」）。
定稿的双色模型（`cloudCol(k, altT, back)`）：
- 侧照云：`CLOUD_DARK rgb(44,52,70) → CLOUD_LIT rgb(206,196,176)`，靠月越近越亮；
  ⚠️ 亮面第六轮随月色**改金**（旧 `196,198,208` 是蓝灰，配金月读作"两块不同的光源"）。
- 剪影端：`CLOUD_SIL rgb(7,9,15)`（比夜空底 `#03050c` 略深一档），按 `back` 线性插值压过去。
- 暖偏：`warm = 0.25 + 0.55·(1 − altT)` ⇒ R +16·f·warm / G +6·f·warm / **B −24·f·warm**。
- 环境光地板：`lit = clamp(0.56 + 0.44·(1 − d²·0.55), 0, 1)`。⛔ 必须有这 0.56 的地板——
  整条天空都被月光照亮，离月远的云也在散射天光；旧写法 `lit` 直接归零 ⇒ 满屏云**只在月旁才现身**。

### 6.5 新月夜读什么（⛔ 不要把正确结果当成 bug）

`f < 0.03`（本文算法下 ≈ 朔前后 ±0.7 天）时，真实夜空**几乎看不见月**。本效果的处理：
盘面画**暗盘 + 极淡边缘**（照度 3% 时 diskA 仍为 1，但盘本身已因 `f` 而几乎全黑），
晕按 `f` 线性压低。**不要**为了"画面好看"给一个不存在的月牙 —— 那会把需求 1 变成装饰。
§十三 V5 会专门在新月夜判读这一点。

⚠️ **阈值以原型为准：`f < 0.06`**（上面那句 0.03 是回填时的偏离，登记 §十五 D13；
原型 `moonlit-preview.html:1098` 取 0.06，实现落在 `DARK_EDGE_MAX_F = 0.06f`，
2026-10-09 的 T5 交付里那条边缘连同颜色/线宽（`:1099`：`moonHsl × 0.9`、
`α = 0.10·(1−occl)`、`max(1, minDim·0.0012)`）一起落地）。

---

### 6.6 ⭐ 确定性过境（Q7③ 裁决的落地，✅ 已随 **T7** 落地）

需求 3 要的是"云把月吞掉、水面只剩漫射亮带"。⚠️ 定稿把这条写成"自然场 `occl` 峰值只有 0.33
所以吞不了月"，该前提已由 **D24** 证伪（自然态实测 `peak = 1`、触顶占 1.71%）⇒ 本节的**必要性**
随之改写为：**让厚云态可预期地出现**，而不是"补一个遮不满的洞"。
所有者 2026-10-09 裁决 **「Q7：选 3」** ⇒ 加一条**确定性过境周期**（该裁决在 D24 之后**依然成立、
不必重开**：D24 只说明"能不能"，从没说明"什么时候来"）。

**设计（⛔ 不是钩子，全程只有几何）**：近层里指定一缕（`TRANSIT_INDEX = 0`）当"过境缕"，
把它那一条的**带位**从播种随机值改为常量：

```kotlin
/** 月心在天空区里的相对高度 —— 与画布尺寸无关的常量（H 约掉）。 */
private const val TRANSIT_BAND_Y = MOON_CY_K / HORIZON_K     // 0.200 / 0.640 = 0.3125

// stepClouds 内，仅 i == TRANSIT_INDEX 且本圈armed 时：
b.cy = horizonY * TRANSIT_BAND_Y        // 否则走自然 `horizonY * b.bandY`
```

- ✅ **`0.3125` 落在近层带 `0.14..0.76` 之内** ⇒ 不是越界特例，观感上它就是"一缕恰好在月的高度"。
- ✅ **横穿周期 = `1 / speedK`**，`speedK = SPD_NEAR·(0.80..1.24)` ⇒ **49~76 s 一圈**
  （`SPD_NEAR = 0.0165`，原型 `:301`）。Q7③ 那句"每 ~90 s"是量级示例，**实测就是这个量级**。
- ⛔ **不改这一缕的速度、尺寸、α** ⇒ 零新增 op、零新增填充、`spdMul` 的时基红线（G5 ③′）一字不动。
  过境缕与同胞缕的唯一差别是 `cy`。

**频率闸门（可选，⚠️ 只在换圈时切）**：若真机判读嫌"每分钟都遮一次太密"，加
`TRANSIT_EVERY_CYCLES = 2`（≈122 s 一次）。实现必须走**换圈判定**：

```kotlin
if (b.x < prevX) cycle++                     // wrap01 归零 = 这一缕刚在屏幕左侧出画
armed = (cycle % TRANSIT_EVERY_CYCLES == 0)
```

⛔ 不许用"到点强制 `cx = 月左`"这种瞬移（会在屏内产生跳变），⛔ 不许用随机事件表。
换圈点 `cx = (x·1.3 − 0.15)·W = −0.15W` 在屏外 ⇒ **切换 `cy` 不可见**，这是该闸门免费的前提。

**⚠️ 与演示钩子的关系（⛔ 别混淆）**：原型的 `cloudPassT`（`:942-952`）做了同一件事但**多做了两步** ——
`b.cx` 用绝对时间线性推（⛔ 违反增量红线）、`b.gw *= 1.10 / b.gh *= 1.25`（放大）。
R13 已判那两个开关**不移植**；本节只取"钉 `cy`"这一步。
⇒ ⚠️ 因此 §9.2 那组半遮态实测（`passcost` 走的就是演示钩子）**含放大**，是**上界**：
过境缕 v1 **不放大** ⇒ 实际铺屏只会更低。若真机嫌遮得不够戏剧，再把 `1.10/1.25` 作为
**两个单常量**加回来，⚠️ 且必须重跑 `passcost` 记账（第七轮的"加粗必须还债"条款对它同样成立）。

**判据**：`occlusion()` 在过境窗内必须**非零地爬到顶再落**（曲线，不是台阶）。
真机侧新增 **V14**：按 `TRANSIT_EVERY_CYCLES` 数得出下一次过境的墙钟时刻，⛔ 不要靠等；
判读点 = ①确实发生了 ②盘面变暗与水面变暗**同帧**（G3 的同源性）③银边只在半遮出现。

⭐ **as-built（T7 已落地）**：`MoonClouds.TRANSIT_BAND_Y = MOON_CY_K / HORIZON_K = 0.3125`、
`TRANSIT_INDEX = 0`，钉在 `MoonCloudField.step` 里**只改 `b.cy`**（`MoonClouds.kt:725`）；
`TRANSIT_OFF = -1` 是**测试接缝**（⛔ 生产路径恒为 `TRANSIT_INDEX`）—— G3 的逐字几何对账必须能
把过境关掉，否则"过境缕只改 `cy`"这件事无法与"其它缕逐位不动"分开证。
**`TRANSIT_EVERY_CYCLES` 闸门没有实现**：它是本节标注的**可选项**，触发条件是"真机判读嫌太密"
（§十三 V14），⛔ 不是实现期的自由裁量。
✅ 实测兑现：G3 ⑩ 钉住"过境态 vs 自然态"的触顶占比 **9.98% vs 1.71%（5.8×）**，
并另证过境缕**只动 `cy`** —— `w` 与未钉时**逐位相同**（同尺寸同噪声），`h` 差 **5 ulp**
（`(cy + e) − cy` 的舍入路径，⛔ 不是几何改了；G3 ⑩ 因此对 `h` 用相对容差、对 `w` 保持 `delta = 0.0`）。

---

## 七、水面：倒影与粼光路

### 7.1 ⛔ 不做二次场景渲染

真实镜像 = 把 1–6 层再倒着画一遍 ⇒ 提交数与填充**翻倍**（§九 的账立刻爆），
且云/星/晕的倒影在参考图里几乎看不见。**改为解析式**：海面本体是独立的两层渐变，
水面上只画"月贡献的光"。

⚠️ **定稿把初版那句"只镜像月盘 + 晕"删掉了**——它正是第五轮之前的错误模型（§7.2）。

### 7.2 月倒影 = **光柱（glade）**，⛔ 水面上不出现月盘

> ⛔ **初版本节（`N` 段切片的镜像位图绘制 + `MIRROR_K = 0.30` + `scaleY = 0.55` +
> 每段横向抖动 + `PhotoDraw.drawImage` 子区域）已整体废弃。** 这是本效果**代价最大的一次返工**
> （前四轮的整条思路都是错的），必须写清楚根因，否则实现期极可能"顺手补个镜像盘"。

**为什么废**：前四轮的思路一直是"把月盘画进水里再打碎"。截图实测它**始终读作一块浮在水面的
暗椭圆**（所有者：「水面倒影不像」）。根因⛔ 不是参数，是**模型**——
月盘纹理的平均亮度**并不比被月光照亮的海水高多少**，画到水上就是个**比海还暗的盘子**，
越修边越像石板。真实夜景里水面上**看不见月盘的像**，看见的是一根从地平线往观者方向
**摊开的光柱**：贴地平线处最亮最窄，越近越宽越碎，末端散成一堆横划。

**定稿模型**（两个部件，全是 §3.0(f) 的同一个 `softEllipse`）：

```
③ 柱头（整根柱子上最亮处，紧贴地平线下方）
   softEllipse(moonCx, horizonY + seaH·0.030,
               moonR·0.50, seaH·0.055,
               clamp(reflA·2.10·lumK, 0, 0.95),
               whiteOf(0.22), gladeOut, coreK 0.40, coreA 0.88)
   // 复量依据：参考图地平线处**全宽** ≈0.79·R，画面下缘 ≈3.67·R

② 底光带（1~3 颗**竖长**椭圆，纵向盖满整条光带）
   NFOG = LOW 1 / MED 2 / HIGH 3
   FOG  = [[0.30, 0.85, 1.55], [0.72, 1.70, 1.10], [0.98, 2.30, 0.75]]   // [中心·seaH, 半宽·R, α标度]
   rx = moonR·rxK ;  ry = seaH·(i==0 ? 0.42 : 0.40)                       // ⭐ 竖长，⛔ 不是扁透镜
   cx 带缓慢横摆：moonCx + (noise1(i·2.1 + t·0.35) − 0.5)·moonR·0.30
   α  带呼吸：reflA·lumK·aK·(0.85 + 0.30·noise1(i·3.7 + t·0.30))
   色 = whiteOf(0.10)，coreK 0.10 / coreA 0.55（低 coreK ⇒ 看不见拐点 ⇒ 平滑雾）
   gladeOut = rgba(月色×0.55/0.55/0.60, 0)                                // 外沿往海色脱
```

- 门控：`if (reflA > 0.004f && tSec > 0f)`（§3.0(f) 第 4 条：α≈0 **不回收预算**，
  所以必须在**几何之前**跳出，⛔ 不要画一个 α=0 的椭圆）。
- ⛔ 三样东西**都不要**：`drawImage` 月盘、横向切片列、把倒影画成"椭圆盘 + 边缘高光"。
- ⭐ **`ry` 为什么必须"竖长"**（这是本节最贵的一条实测）：判据是**水面 20% 档**
  （`moonlit_ref_match_check.py`：水窗最亮 20% 像素的均值）。参考图 **202**，候选只有 **78**
  而 2% 档已到 207 ⇒ **缺的不是亮度，是面积**。
  ⛔ 但底光**不能**用"一排逐层变宽的扁椭圆"叠：5 颗 × `2π·rx·ry` 实测吃掉 **0.521 屏**
  （= MED 整份预算的 14%，直接把 3.60 的上限顶穿到 4.06），而对 20% 档**只买到 +9**（185→193）。
  换成 1~3 颗竖长椭圆后填充 **0.20**，同样的观感 ⇒ ⭐ **省回 0.32 屏**。
  附带好处：一颗纵向盖满 ⇒ ⛔ 不会出现"一摞透镜"的横向接缝。
- 倒影消失顺序不变：`reflA ∝ diskA`，`f` 很小时自动随照度退场，⛔ 不需要特判。

⭐ **as-built（T8 已落地）**：本节两件套落在 `MoonWater.kt`（`head*` / `fog*` / `gladeVisible` /
`headAlpha`，⛔ **零 Android import**，与 `MoonClouds` 同一条分层纪律 ⇒ G14 能跑纯 JVM 单测）；
渲染器只调不写公式。三条实测把本节的两处书面口径改正了：

1. ⚠️ **填充按解析几何重算**（口径 = 原型自己的记账 `2π·rx·ry`，见 §9.2 口径 1）：
   1080p 固定构图下逐颗为 柱头 `0.005248` + 雾片 `0.068134 / 0.129779 / 0.175578` ⇒ 累计
   **`0.073383 / 0.203162 / 0.378740`**，且**与画幅尺度无关**（每一项都是 `minDim·seaH / (W·H)`）。
   表按 3 位**向上**取整 ⇒ **`0.074 / 0.204 / 0.379`**；⚠️ 旧表 `0.073 / 0.203` 是探针打印值的
   **就近取整**，比解析几何各低 `0.0004 / 0.0002` ⇒ **表落到了实测以下**，G4「表是上界」当场作废
   （与下面 `GLITTER` 的 MED 是同一处分量级错，登记在 **D28** 里一并更正）。
2. ⭐ **门闭合只在"新月 + 满遮"这一角**：`reflA(0, 1) = 0.44 × 0.06 × 0.08 = 0.002112 < MIN_ALPHA`
   ⇒ 光柱**整段不出场**；而 `reflA(1, 1) = 0.0352` **仍在场**。⚠️ 也就是说"云遮月"**不会**让水面变空
   —— 那是照度 `f` 的职责，不是 `occl` 的（§7.4 那句"倒影随盘面同源"讲的是**同源**，不是**同灭**）。
   本条由 **G14 ⑤b** 钉住，⛔ 没有为此改 `reflA` 的任何系数。
3. **提交数是恒等式不是抄数**：`1 柱头 + NFOG(1/2/3)` = **`2 / 3 / 4`**，G14 ⑤ 逐颗重算椭圆和并与
   `MoonOpItem.REFLECTION` 的 ops/fill 两列对账（`fogCount` 的顺序与 `FOG` 表的 `cy` 递增也同例校验，
   ⛔ 表若被倒着抄会红在"越近的雾片越靠上"）。

### 7.3 粼光路（图 1 的碎金）—— 定稿改为 **ROWS × 深度分档 PER** 两层循环

初版给的是单层 `M = 10/24/48` 条划 + `w(y)` 线性包络 + `len` 噪声调制。定稿保留了思路，
但**结构、透视指数、包络地板、划厚与条数分档**全部按 PIL 对照重做（第六轮四次的结构性重写）。

```
行数   ROWS = LOW 24 / MED 46 / HIGH 60
近景每行条数 PER = LOW 3 / MED 4 / HIGH 5
列中心 x = moonCx + sway，sway = sin(TAU·0.055·t)·minDim·0.006 + (aBass − 0.4)·minDim·0.004

for i in 0 until ROWS:
  fy    = ((i + 0.5)/ROWS) ^ 1.70                     // ⭐ 透视：靠地平线的行更密（初版 2.30，见下）
  y     = horizonY + seaH·fy
  Δy    = seaH·1.70·((i+0.5)/ROWS)^0.70 / ROWS        // 本行与下一行的行距
  env   = GLITTER_W_K·minDim·(0.42 + 0.58·fy)         // 越近越宽（地板 0.22→0.42，见下）
  hh    = max(1.2·DPR, env·(0.018 + 0.050·fy²))       // ⚠️ hh/Δy 不变式，见下
  jitY  = (hash1(i·13+7) − 0.5)·Δy·0.55               // ⭐ 整行纵向错开
  per   = if (fy < 0.28) 1 else if (fy < 0.62) 2 else PER     // ⭐ 按深度分档
  for k in 0 until per:
    seed = i·31 + k·7 + 3                              // 每条划**独立**随机源
    len  = env·(0.14 + 1.35·fbm1(seed·0.37 + t·0.8, 5))     // 0.14=发丝 … 1.49=长波
    lane = hash1(seed·31+5)·2 − 1                      // 站在光柱哪一侧（逐条独立）
    wide = 0.30 + 1.05·hash1(seed·41+9)^1.4            // 外沿余量
    off  = (lane·wide + (noise1(seed·1.77 + t·1.35) − 0.5)·0.35)·env·0.95
    fall = exp(−0.55·(off/(env·0.90))²)               // 高斯外沿（指数 1.15→0.55 ⇒ 放宽）
    α    = clamp(glitA·(0.55+0.45·hash1(seed·7+3))·(0.40+0.85·fy)·6.0·fall, 0, 1)·lumK
    if (α < 0.004f) continue                            // ⛔ α=0 也要跳过，别画（§3.0(f) 第 4 条）
    softEllipse(moonCx + sway + off,  y + jitY + (hash1(seed·17+11) − 0.5)·hh·1.2,
                len/2, hh/2, α, whiteOf(0.35), 透明同款, coreK 0.66, coreA 0.95)
```

**七条已实测的定稿结论，⛔ 一条都是不能省的**：

1. ⭐ **`per` 必须按深度分档**（远 1 / 中 2 / 近 `PER`）。行距在远端只有 1~2 px，
   一条就**连成带子**，多给纯属浪费 op；近端行距 ≈15 px，必须一行多条才填得满。
   ⚠️ **MED（`ROWS = 46`）的分档行数是 `22 / 13 / 11`（不是原文的 `21 / 13 / 12`）⇒ `22 + 26 + 44 = ` 92 条**
   （阈值切在 `fy = 0.28 / 0.62`，登记 **D27**；表值 92 本来就没错，错的是这句拆账），
   比均摊的 `ROWS × PER` 更省（MED `184`、LOW `72`、HIGH `300`）。
   ⇒ ⭐ **同样的预算从远端挪到近端**，这是 §3.0(f) 成本模型推论①的直接应用。
   机器化：**G14 ②** 断言 `structuralStreaks(level)`（行结构精确求和）== `MoonOpItem.GLITTER.ops*`，
   ②b 负向自证"每行均摊 `PER` 条 ⇒ LOW 从 43 抬到 72 ⇒ `78 − 43 + 72 = 107 > OPS_MAX_LOW 90`"。
2. ⭐ **"每行只有一条"是"几笔灰杠"的成因**：PIL 对照参考图判出的不是亮度差、是**结构差**——
   参考图的亮场是"每一深度行上**散落着好几条**短划"。单层 `M` 循环（M 行 = M 条孤划）
   会让扇形大部分是空的。
3. ⚠️ **`hh/Δy` 不变式**（⛔ 改 `ROWS` / 指数 `1.70` / `GLITTER_W_K` 时**必须同步重算**）：
   划厚不能远大于行距（否则相邻行互相吞掉糊成一坨），但**下缘比值要 ≈1.2**——
   参考图的近景**就是**波峰互相搭接连成一片的"浪脊堆"，比值 <1 会露出黑底、读作**"梯子"**。
   暗槽靠每条划自己的 α 抖动（式里的 `0.55 + 0.45·hash1`）留，⛔ 不靠缩小 `hh`。
   ⚠️ **as-built 三点实测**（1080p / density 1.5，`hhToDyRatio` 取 远端 / 中景 / 下缘 三个行号）：
   LOW `1.227 / 0.244 / 0.692`、MED `3.705 / 0.462 / 1.364`、HIGH `5.819 / 0.601 / 1.791`
   ⇒ 三个方向**都与这句直觉不同**：远端比值**最大**（透视把行距压到 1~2 px，划互相搭着 ⇒
   那正是结论 6 要的"贴地平线一条宽细闪带"，是**设计**不是事故）；**中景永远最疏**（`<1`，
   这才是暗槽的真正来源，另一半是 `JIT_ROW_K` 的整行错开）；**下缘只有 LOW 低于 1**（0.692）。
   ⇒ 登记 **D30**：⛔ 不为此改 `ROWS` / 指数 `1.70` / `GLITTER_W_K`（观感三件套，且原型七轮
   就是在这三个数上判读通过的），只把"下缘 ≈1.2"更正为**分档实测 + 三点钉死**（G14 ⑨）。
4. ⭐ **`jitY`（整行纵向错开）治的是"梯子"**：行与行严丝合缝对齐正是梯子感的成因。
   同理，横向偏移的主项**必须逐行逐条独立**（用 `seed` 的 hash），⛔ 不要拿 `y` 的连续函数当主项——
   第四轮就栽在 `noise1(y·k)`：相邻行的 `y` 只差几像素 ⇒ 噪声值几乎相同 ⇒ 整列仍然对齐成**一条竖线**。
   时间演化只许叠**一个小摆幅**（式里的 `wob·0.35`），否则又变回静态。
5. **透视指数 2.30 → 1.70**：2.30 把行几乎全堆到地平线附近 ⇒ 画面**下缘 1/3 没有划**（近景是黑的），
   而参考图的光带**一直铺到下缘**。
6. **包络地板 0.42**（原 `0.22`）：参考图**贴地平线就有一条宽细闪带**（实测 t=0.25 处亮场全宽
   1.45·R），旧地板让远端只有 0.55·R ⇒ 远端读作"一条线"。⚠️ 只抬地板、**底宽不变**（`0.42+0.58=1`）。
7. **`coreK 0.66 / coreA 0.95` = 实心浪脊**（旧值 0.42）：划要读作"一道波峰的镜面"就必须有芯；
   雾（§7.2 底光带）反过来要 `coreK 0.10`。**同一个图元，两种芯**，这是 §3.0(f) 语义的直接用途。
8. ⚠️ **划长的跨度要够大**：`0.14 + 1.35·fbm1` ⇒ 均值 ≈0.81·env，下缘 ≈1.5·R，
   但**既有发丝也有长波**。⛔ 不要写定长满宽（那才是"横杠"），也不要用旧值 `0.30+1.50`
   （一行三条满宽划会并成一条横杠）。
9. ⭐ **色必须走 `whiteOf(0.35)`（镜面提白），⛔ 不能用月盘色**：理由见 §3.3+ 的"颜色会封死亮度"。

⚠️ **定稿推翻的判据**：初版与 §一 表都写"粼光是**断开的短横划**，⛔ 不是连续光柱"。
定稿的目标是**连续光带（§7.2）上骑着断开的浪脊划**——`fall` 的高斯外沿 + `env` 的连续包络
使亮场在**每个深度都是连续的**，而每一行内部仍是断的。V7 已按此改写（§十三）。

⭐ **as-built（T8 已落地）**：`MoonWater.kt` 落本节全部算术（`hash1/noise1/fbm1` + `fyOf/dyRowOf/
envOf/hhOf/perOf/sway/hhToDyRatio` + 嵌套类 `MoonGlitterFrame`），⛔ 零 Android import；
渲染器 `drawGlitter` 只调不写。四条实测结论：

1. **哈希三件套与原型逐位对账**（G14 ①，期望值由 node 跑原型 `:354-358` 原文得，⛔ 不是凑的）：
   `hash1(0.0) = 0.76327984989620745`、`hash1(1.0) = 0.51736302836798131`；JS 的 `x | 0` 是**向零截断**
   ⇒ `hash1(0.0) == hash1(−0.3) == hash1(0.5)` 必须同值（Kotlin 侧用 `.toInt()` 同语义）；
   `noise1` 在整数格点退化为 `hash1`（`noise1(4.0) == hash1(4.0)`）。
   ⚠️ **位序坑有牙**：①b 把 `xor` 写成 `or` ⇒ 300 个站位只剩**几十种**（正确位序 300 种），
   这就是 §3.0(f) 第 7 条那条"星野长出方阵"的同一个坑，在粼光上的版本。
2. **填充按最坏帧，不按均值**（**D28**）：表口径 `f=1 / occl=0 / aTreb=1` ⇒ `glitA = 0.775`，
   此时**没有一条划被 `α < 0.004` 剔掉** ⇒ 在场条数**正好等于**结构条数（43/92/137），
   ops 列与 fill 列自此是**同一帧**的口径。沿时间扫 1000 s（步长 0.25 s、4001 帧）取 max =
   **`0.038962 / 0.087361 / 0.136744`**（argmax `96.5 / 552.5 / 312.5 s`）、mean =
   `0.0335 / 0.0793 / 0.1256` ⇒ 旧表 `0.035 / 0.080 / 0.129` 记的是均值。
   ⚠️ 表按 3 位**向上**取整（`0.087361 → 0.088`，就近会得到 `0.087` **低于**实测 ⇒ G4「表是上界」作废）。
   ③ 还显式钉住 **argmax 的时间戳**（`delta = 0.0`）：粼光无状态，换帧意味着**分布**变了，
   ⛔ 不许"把表改大"当成对账通过。
3. ⚠️ **本表有一个参照系**（**D29**）：填充与**画幅尺度**无关（④ 在 900p/1080p/1440p/2160p 四档
   **逐位同**），但与 **`density` 有关** —— `hh` 的地板 `1.2·density` 设备像素会顶住最薄的划。
   触发条件写得出来：最薄一行自然厚 `0.002079·minDim`（900p `1.87` / 1080p `2.25` / 2160p `4.49`），
   地板 `1.2·density` ⇒ 只有 `density > 0.00173·minDim` 才顶到（1080p 要 ≥2、2160p 要 >3.7）。
   ⇒ **表守 `density ≤ 1.5`**（含验收机 1920×1080 / 240dpi），⛔ 不按最坏设备落（地板是线性的，
   `density → ∞` 时该行无上界，抄进去等于宣布这一行没有口径）；高密度那一截由 ④b 按生产几何
   重算**合计**并对着 `OVERDRAW_MAX_*` / 棘轮判（网格最坏 900p×3：`0.040063 / 0.089332 / 0.139536`）。
   ⛔ 也不许为了"表 ≥ 一切设备"去改 `1.2` 地板 —— 它是"最薄的划别细到消失"的观感底线。
4. **取色走 `whiteOf`**（结论 9 的机器形态，⑦）：划 `whiteOf(0.35) = rgb(255, 222, 166)`、
   柱头 `0.22`、雾片 `0.10`，三者都从 `MoonDiskBake.whiteOf` 取，⛔ 渲染器里不出现第二份月色。
   ⚠️ 这条对账的单调性**只能钉 G 通道**：定稿的 `LIT = (254.7856, 203.9869, 117.9925)` ⇒
   R 在 `t = 0` 就已经是 255（`whiteOf(0f) ≡ hotOf(1f) ≡ rgb(255,204,118)`），拿 R 判"越白越亮"恒真。

### 7.4 与 `occl` 的耦合（需求 3 的最终形态）

`reflA`/`glitA` 全部 `× diskA`（§6.3 ④⑤），而 `diskA` 与天上月的亮度是**同一个标量**。
⇒ 云压过来的完整过程：`晕先暗 → 粼光路先散 → 倒影变暗 → 月隐`，与真实夜海同序。
⛔ 这个顺序不许改：它比任何单帧保真度都更像"云遮月"。

⚠️ **定稿对这句顺序做了一个必要更正**：按 §6.3 的实际系数，**标量上**晕归零得最晚
（`haloA → 0.25·haloBase` vs `diskA → 0.08`），"晕先消失"说的是**观感上的能量先散**。
Kotlin 侧 ⛔ 不要为了让这句话成立去改系数（详见 §6.3 的降级说明）。
**粼光最先消失**（`diskA²`）与**倒影随盘面同源**（`diskA`）这两条是真的，必须保持。

### 7.5 节拍涟漪（`RIPPLE`，定稿实测的两条约束）

```
RIPPLE ≤  LOW 0 / MED 2 / HIGH 4 环（FxLevel.OFF ⇒ LOW 整段跳过 ⇒ 记 ≤）
生成      audio.beat 为真的那一帧 push 一个 { y = horizonY + seaH·(0.30..0.75), t = 0, sp = 0.55..1.05 }
寿命      t/sp 到 1.8 s 结束；p = t/1.8；半径 rr = moonR·lerp(0.1, 0.9, p)
绘制      softRing(moonCx, y, rr·1.6, rr·0.40,
                   clamp(0.075·(1 − p)·diskA·glitA, 0, 1), rgb(214,206,180), inK 0.58, pk 0.86)
```

- ⭐ **必须用 `softRing`（芯透明的软环），⛔ 不要 `drawOval(Stroke)` 描边**：
  描边环是一条 1.3 px 的**等亮硬线**，在水面上读作"铁丝圈"（原型截图判读）。
  软环芯透、缘透 ⇒ 才像扩散的水纹。Kotlin 侧仍是**一次** `RadialGradient`（多两个 `addColorStop`）。
- ⚠️ **亮度上限 0.075 是刻意的极低值**，别"顺手调亮"：截图实测这条环原本**正好贴在倒影下缘**，
  一亮就**读作"盘子的投影"** ⇒ 直接坐实"水面上浮着一块石头"（也就是 §7.2 那个被废弃的模型的样子）。
  环本身没问题，是它太亮。α 还要 `× diskA × glitA` ⇒ 遮月时涟漪自然一起退场。
- 队列长度硬上限 = 档位值（`if (size > max) size = max`），⛔ 不许无界增长。
  与 §3.0(f) 第 5 条同源：**只累加 `dtSec`**，⛔ 不用墙钟算 `p`。

⭐ **as-built（T9 已落地）**：`MoonWater` 的 `ripple*` 段（`ripplePMax` / `rippleR` /
`rippleRx` / `rippleRy` / `rippleAlpha` / `rippleFillFraction`）+ 渲染器 `drawRipples` /
`ringShaderOf`。四条与账面有关的落地事实：

1. **生成条件用 `frame.beat` 而不是本节上面写的 `audio.beat`** —— 与 §八 第 6 行是同一个通道，
   规格那句 `pulse` 已在 §八 as-built 第 5 条登记为偏差 **D33**（⛔ 不是漏接）。
2. ⛔ **描边椭圆已被实测否掉**，实现是**四停位软环**（3 个 `TRANSPARENT` + 芯透明），
   且 ⛔ 不得出现 `drawOval` / `Style.STROKE`（G15 ⑫b 双向钉）。
3. ⭐ **`§9.2` 的 RIPPLE 填充行不再是探针数**（偏差 **D34** 的第二笔，也是 **D26 / D28**
   那条错法的第三次犯）：`passcost` 读回的 `0.017 / 0.049` 是**典型帧**（几环诞生于**不同拍**
   ⇒ 同场环的半径互不相同），而本表口径是**最坏帧**（每一槽都在最大可见半径）。表改由
   `MoonWater.rippleFillFraction` 用**生产几何**真算：`rr = rippleR(moonR, ripplePMax())`
   ⇒ 单环 `2π·rx·ry = 0.036350` 屏 @1080p（椭圆按两倍真面积记，与 `REFLECTION`/`GLITTER` 同口径）
   → MED `×2 = 0.072700` / HIGH `×4 = 0.145400` → 3 位**向上**取整 = 表里的 **`0.073 / 0.146`**。
4. ⚠️ **"最大可见半径"不是 `p = 1`**：α 在 `pMax = 0.9312` 处已落到 `MIN_ALPHA(0.004)` 以下，
   那一段一个像素都不铺 ⇒ 记账半径取 `pMax`（`ripplePMax()` 与早退判据**同源**，
   G15 ⑭ 断言 `rippleAlpha(pMax) == MIN_ALPHA`）。拿 `p = 1` 记账是**多记**（单环 `0.041175`），
   与"表是上界"并不冲突，但那会守一个**不存在的帧** —— 同 D28 的反方向纪律。
   ⇒ 这一行因此**不再是手抄数**：G15 ⑭ 逐档重算并与表对账，含「按探针口径落表 ⇒ 低于几何」
   与「`p=1` ⇒ 高于表」两条**反方向**自证。

### 7.6 地平线压暗带（`HORIZON_BAND` = 1 op，定稿参数）

一次线性渐变矩形，`y ∈ [horizonY − 0.10h, horizonY + 0.06h]`，
stops `透明 → rgba(2,4,9, 0.30 + 0.22·(1 − altT)) @0.55 → rgba(月色×0.8, 0.05·(1 − occl)) @0.72 → 透明`。
- ⚠️ 中间那一停用**月色且 α 随 `(1 − occl)`** ⇒ 云遮月时地平线那条微光一起没，
  与 §6.3 同源（⛔ 这里若写死常数，遮月时地平线会留下一条不属于任何光源的亮线）。
- `altT` 项的依据：真实大气在近地平处最厚 ⇒ **月低时压暗带更深**。

⭐ **as-built（T8 已落地）**：`MoonWater.kt` 的 `BAND_*` 段（常量 + `bandDarkA/bandLitA/
bandOcclBucket/bandLitABucket/bandDarkRgb/bandLitRgb`）+ 渲染器 `drawHorizonBand` / `bandBrushOf`。
三条：

1. ⭐ **填充按几何 = `0.160` 屏，⛔ 不是探针的 `0.102`**（**D26**）：原型探针那行写的是
   `op('HZ', 0.16 * S.horizonK)`，而它画的矩形是 `fillRect(0, horizonY − 0.10h, W, 0.16h)` ——
   **通铺全宽**、且**横跨地平线两侧**（`0.10h` 天空 + `0.06h` 海面）。乘 `HORIZON_K` 把一条压暗带
   折成了"只有天空那一份"，与 `SKY_BASE` 的两块**不相交**矩形不是一回事（那是**分割**，这是**重叠**）。
   ⇒ 表改由 `MoonOpBudget.horizonBandFillGeometry()` **真算**（含上下越界钳制），三档同值
   （带高恒 `0.16h` ⇒ 与画质档无关），`MoonOpBudgetTest` 的负向自证「地平带按探针口径落表 ⇒
   低于真实矩形面积」钉住方向；几何系数 `0.10 / 0.06` 在两文件各有一份名字 ⇒ ⑥ 留了**漂移哨兵**。
2. **只有月色标随 `occl` 变** ⇒ 整条渐变可按桶缓存（`BAND_OCCL_BUCKETS = 16`，与 **D23** 同一套路）：
   压暗标的 α 吃 `altT`，而 `altT` 由**固定构图**（`MoonSeascape.ALT_T = 0.6875`）决定、逐帧恒等
   （实测 `bandDarkA(ALT_T) = 0.36875`，⛔ 渲染侧不许写死这个数）。量化误差是**解析式**
   `BAND_LIT_A / (2·桶数) = 0.05/32 = 0.0015625`，最坏点落在桶边界；⑥b 同时钉解析值与
   "刻度砍半（8 桶 ⇒ `0.003125`）就越线"的负向自证 ⇒ 16 不是随手挑的。
   ⚠️ 误差**不许**用扫描网格当上界：1001 点扫出来是 `0.0015375`（网格碰不到桶边界）。
3. ⚠️ 缓存入口**必须**是不带 `DrawScope` 接收者的 `bandBrushOf`：`Brush.verticalGradient(vararg)`
   分配 vararg 数组与四个 `Pair`，而 `PerfBudgetContractTest` ③ 扫的是**整个** `fun DrawScope.draw*`
   函数体 —— 与它实际只在未命中时跑无关（承 `headShaderOf` / `glitterShaderOf` 与 E43 的同一条）。
   这一条把 G13 那句"`Brush.verticalGradient(` 全文恰 **2** 处"合法地改成了 **3** 处（sky / sea /
   地平带），`MoonlitSkySeaTest` ②c 的断言已同步升级，⛔ 判据实质未变：**任何** `draw*` 体内
   不得构造 Brush，且第三条必须落在**按桶缓存**的 `bandBrushOf` 里而不是 `drawHorizonBand` 里。

---

## 八、音频映射（克制；场景是静的）

| 输入 | 出处 | 驱动 | 幅度 | 备注 |
|---|---|---|---|---|
| `frame.bass` | `AudioFrame.kt` 律动通道（线性） | 晕的半径 | ±6% | 经 `AudioSmoother.updateDt(_, fx.dt/1000)`（`fx/AudioSmoother.kt:32`，⛔ 别用 `update` 的定率式） |
| `frame.bass` | 同上 | 海面整体亮度 | ±10% | ⛔ 不驱动月盘亮度（真实月不因音乐变亮） |
| `frame.bass` | 同上 | **粼光路的横向摆移** | `(aBass − 0.4)·minDim·0.004` | ⭐ 定稿新增（与 `sin(TAU·0.055·t)·minDim·0.006` **相加**，见 §7.3 的 `sway`）。⚠️ 这是本效果**唯一**让音频移动**几何**的地方，幅度刻意只有 ~4 px @1080p；⛔ 别加大，否则光柱会跟着鼓点晃 |
| `frame.treble` | 同上 | 粼光 alpha | +25% | 高频 → 细碎反光，最直观（定稿位置：§6.3 ⑤ 的 `glitA` 式里） |
| `frame.mid` | 同上 | 云速度 | ×(0.85..1.25) | ⛔ 不做 alpha 抖动（云"闪"是廉价的）。⚠️ 定稿红线：这个乘子**只能作用在 `dtSec` 增量上**（§3.0(f) 第 5 条），写成 `× 绝对时间` 就是"抽搐"那个 bug |
| `frame.pulse` | 快起慢落包络 | 涟漪环（仅 MED/HIGH） | 1 环/拍 | 半径 = 0.1R..0.9R 展开（定稿细节与**必须极暗**的理由见 §7.5） |
| `frame.beat` | 单帧 true | 逆光云边亮度 | ×1.25 | ⚠️ 只用一帧，⛔ 不要自造计时器 |
| `frame.energy` | 线性总能 | 暗角强度 | 0.42 → 0.36 | 高潮时略微"打开"画面（定稿 `lerp(vignHigh=0.36, vignLow=0.42, clamp(aEner·1.4, 0, 1))`，⚠️ 参数名与方向易写反） |
| `frame.spectrum[band]` | 显示通道（已 gamma） | **不使用** | — | ⛔ 不要画频谱柱，那会把夜海变成频谱仪 |

⭐ **定稿补充：`altT`（月地高度比，§3.3）不是一个音频输入，但它和上面这些一起进了同一个
亮度/宽度式**（`haloBase` 的 `0.55 + 0.45·altT`、§5.2 的 `ld`、§7.5 的压暗带深度、云色的 `warm`）。
⇒ 它是"构图常量"，⛔ 不要误接成随音频变化的量（否则晕会随鼓点变色）。

规则：
- ⛔ 律动**只用线性通道**（`AudioFrame.kt:14-16` 的铁律：拿 gamma 后的 `spectrum` 驱律动会把
  强弱压扁，历史 BUG ⑨-b）。
- ⚠️ 需要"鼓点强弱差异"时用 `bassRaw` 并自行归一化（`AudioFrame.kt` 的 `bassRaw` KDoc：
  峰值跟随使 `bass` 在鼓点帧**恒为 1.0**）。本表不依赖鼓点强弱，故用 `bass` 即可。
- ⛔ **不读 `ctx.quality.maxParticles`**（保持 `needsParticleBudget = false` 的可核对性）。
- ⛔ 不加"频谱环/柱"任何元素。

⭐ **as-built（T9 已落地，2026-10-10）**：`MoonAudio.kt`（§八 这把尺，⛔ 零 Android import ⇒
G4/G15 都能纯 JVM 直接调）+ `MoonlitRenderer.drawContent` 开头的**四行音频头**（`aBass`/`aMid`/
`aTreb`/`smEner`，每个频段**每帧恰好推进一次**）+ 门禁 **G15 `MoonlitAudioTest` 20 例**。
下表那九行的落点与四处偏离：

1. ⭐ **`AudioSmoother.updateDt` 的首个生产调用方**（类头那条"新效果一律用本变体"自本批起有了
   真实指向）。⛔ 全文不得出现定率式 `.update(` —— 那条隐含 60fps，电视掉帧时包络跟着漂。
   推进**次数**在这里是语义不是风格：状态量调两次 = 同一帧吃两遍衰减（G15 ①②）。
2. **偏差 D31（单位）**：本表第一行写的 `updateDt(_, fx.dt/1000)` **照抄会错**。`FxFrame.dt`
   由 `FrameClock.advance` 产出 `dtMs / 1000f`（`RendererFx.kt:205`），**本身已经是秒**
   ⇒ 传 `fx.dt` 本身。判据双向钉：渲染器内 ⛔ 不得再出现 `/ 1000`，且 `RendererFx.kt` 全文
   **恰一处** `dtMs / 1000f`（谁再除一次，这两条会同时抓住 —— G15 ③）。
3. **`energy` 那条包络单独更慢**：`AudioSmoother(0.28f, 0.05f)`（原型 `moonlit-preview.html:792`）
   而其余三条用默认 `(0.35, 0.06)`（`:791`）。系数住在 `MoonAudio.ENERGY_ATTACK/RELEASE`，
   ⛔ 不在绘制现场写魔数（`AudioSmoother` 类头第 §7.3 那条）。理由不是洁癖：暗角是**全屏叠加**，
   包络快一档等于画面每拍眨一下。
4. **偏差 D32（方向）**：原型那行 `lerp(AMP.vignHigh, AMP.vignLow, k)` 配的是
   `vignHigh: 0.36` / `vignLow: 0.42` ⇒ **字面实现**得到的是"越吵、暗角越**重**"，与本表该行
   明写的「高潮时略微"打开"画面」正相反（本表那句「⚠️ 参数名与方向易写反」正是为此而留）。
   实现取**文字意图**（`vignetteA` 单调递减：`0.42 → 0.36`，`aEner ≥ 1/1.4 = 0.714` 饱和）。
   ⭐ 另一端的钉法更重要：`vignetteA(0) == postFx.vignette == 0.42f` ⇒ **无声帧逐像素等于 T8**，
   而 `postFx` 里那个 `0.42f` **字面量必须保留**（`FxCoverageScanTest` 只读字面量，具名常量读不到
   ⇒ 换成 `MoonAudio.VIGN_QUIET` 会静默丢覆盖，G15 ⑩⑪）。
5. **偏差 D33（通道）**：本表第 6 行写 `frame.pulse` 驱涟漪，as-built 用 **`frame.beat`**。
   `pulse` 是快起慢落包络（连续多帧为真），拿它 push ⇒ 一次拍在队列里留下**一串残影环**；
   而 §7.5 的档位槽位 `MED 2 / HIGH 4` 在"2 s 节拍 × 1.8 s 寿命"下本就接近占空比饱和（**D17**
   已经为此把 MED 从 `≤1` 更正为 2），再加一层残影等于把**事件**误记成**电平**。
   `beat` 才是"一拍一环"。判据：`frame.pulse` 全文 **0** 次、`frame.beat` **2** 次
   （银边 `rimBeatK` 一次、涟漪 `if (beat) ripples.push(` 一次），且队列上限 `0/2/4`
   == `MoonOpItem.RIPPLE.ops`（G15 ⑫）。⚠️ 本表那行**不改**，以本条为准（文档留痕）。
6. ⚠️ **`±6%` 的值域是 `[0.976, 1.036]`，⛔ 不是对称的 `[0.964, 1.036]`** —— 本轮**唯一被门抓到的
   文档错**（KDoc 原写 `[0.964, 1.036]`，G15 ④ 的端点断言把它判红；**公式一字未动**，改的是注释）。
   中性点是 `0.4` 而非法幅区间 `[0, 1]` 的中点是 `0.5` ⇒ `aBass = 0` 那一端只走到 **−2.4%**
   （要 −6% 得 `aBass = −0.2`，取不到）。§9.2 的 HALO 行按**上沿** `1.036` 收费 ⇒ **账不受影响**；
   但 ⛔ 不要据此以为"晕会缩 6%"。
7. **几何调制一律分桶缓存**（承 **D23 / D26** 同一条零分配红线）：`bass` 动的两处里，海面亮度长在
   `Brush` 的色停上、晕半径长在渐变的半径上 ⇒ 逐帧建 `Brush` 违反 `PerfBudgetContractTest` ③。
   两把刻度各自收费：`SEA_SB_BUCKETS = 32`（解析误差 `0.10/(2·32) = 0.0015625 ≤ 0.002`，
   砍半到 16 ⇒ `0.003125` **越线**）、`HALO_R_BUCKETS = 8`（`0.06/16 = 0.00375 ≤ 0.005`，
   砍半到 4 ⇒ `0.0075` 越线 ⇒ 桶数不是随手挑的，G15 ⑦）。⚠️ 上界**只能取解析式**：
   等距网格给不出它（网格取 **997** 与桶数 32 **互质**才演示得出来 —— 1001 点会恰好落在
   `0.125` 那类桶边界上、把误差做到等于上界，那就不是反例了）。缓存**容量必须取 `MoonAudio` 的常量**
   （⛔ 字面量 `8/24/32`：改尺不改缓存就是越界崩溃，G15 ⑧）。晕的**重建键**是
   `haloLevel/haloMoonR/haloDirty` 三件，⛔ 不含桶号（含了就退化成逐帧重建，G15 ⑧b）。
8. **`mid → 云速` 接到 `clouds.step(fx.dt.toDouble(), MoonAudio.cloudSpdMul(aMid), …)`**，
   乘子只作用在**增量**上（§3.0(f) 第 5 条 / **G5 ③** 那条"抽搐"红线，本批是它的兑现处）。
   ⚠️ 注意 `cloudSpdMul(0) = 0.85 ≠ 1`：这是 §八 里**唯一**一条"静止 ≠ 乘子 1"的行
   （中性点在 `aMid = 0.4`，`aMid = 0` 时云是**放慢**的）。这恰是 ⛔ 不能用 `× 绝对时间`
   糊过去的原因 —— 那条捷径会让这一行"看起来也对"，实际把时基改成随包络漂移（G15 ⑨ 用
   `MoonClouds` 内"每个 `spdMul` 使用行必须含 `dtSec`" + 一条 `* tSec` 夹具钉住）。
9. ⛔ **未读的通道**：`frame.spectrum` / `frame.waveform` / `frame.bassRaw` /
   `ctx.quality.maxParticles` 在渲染器里各 **0** 次（G15 ⑯，四条**独立**夹具而非一条 `and`，
   这样一条失效不会被另一条掩盖）。`altT` 仍按本表 ⭐ 那句**不接音频**。
   ⚠️ 顺带一条**记账口径**变更（**D34**）：本表第 1 行（晕半径）与 §7.5 的涟漪从此是
   **收费项** ⇒ §9.2 的 `HALO` / `RIPPLE` 两行按生产几何重算，见该节 as-built。

---

## 九、画质档与元素预算（`MoonOpBudget`，仿 `SeasideOpBudget`）

### 9.1 提交数（ops）—— ⭐ 定稿实测表（⛔ 下面的"初版计划"列仅供追溯）

**实测方式**：`node docs/archive/verification/scripts/moonlit_visual_driver.mjs probe <TIER> 480`
（无头 Edge + CDP 跑真实页面代码，`__dbg.opsMap()` 读回本帧实际提交数）。
⛔ 这张表是**测出来的**，不是设计出来的 —— 定稿过程中它被推翻了三次。

| # | `MoonOpItem` | 初版计划 | **定稿实测 L/M/H** | 定稿说明 |
|---|---|---|---|---|
| 1 | `SKY_BASE` | 2 | **2 / 2 / 2** | 天空 + 海体两块**不相交**矩形（⛔ 不相交是 P-1 崩溃规避的一部分） |
| 2 | `STARS` | 1 | **1 / 1 / 1** | `Id.STARFIELD` 一次，裁剪到天空区 |
| 3 | `HALO` | 1 / 2 / 3 | **1 / 2 / 3** | 层数见 §3.1 的 `HALO_SPEC` |
| 4 | `CLOUD_FAR` | 3 / 5 / 7 | **8 / 15 / 18** | = `CLOUD_FAR × BLOB_FAR`（2×4 / 3×5 / 3×6）。⚠️ 单位是**斑**，不是缕（§6.1） |
| 5 | `CLOUD_NEAR` | 3 / 5 / 7 | **14 / 30 / 44** | = `CLOUD_NEAR × BLOB_NEAR`（2×7 / 3×10 / 4×11） |
| 6 | `BLOOM` | — | **1 / 1 / 1** | ⭐ 定稿新增项（§5.2+ ④ 的加法过曝芯），初版预算里没有 |
| 7 | `CLOUD_RIM` | 0 / 4 / 8 | **≤0 / ≤9 / ≤15** | 斑级银边（§6.4）；`LOW` 因 `FxLevel.OFF` 恒 0 |
| 8 | `DISK` | 1 | **1 / 1 / 1** | 一次位图绘制 |
| 9 | `TERMINATOR` | 3 | **≤3 / ≤3 / ≤3** | ⚠️ **条件项**：`f ≥ 0.995` 整段跳过（§4.2 末）⇒ 满月夜实测为 **0** |
| 10 | `HORIZON_BAND` | 1 | **1 / 1 / 1** | §7.6 |
| 11 | `REFLECTION` | 4 / 8 / 14 | **2 / 3 / 4** | ⛔ 不再是切片数 = `1 柱头 + NFOG(1/2/3)`（§7.2）。初版的 14 段镜像随模型一起废弃 |
| 12 | `GLITTER` | 10 / 24 / 48 | **43 / 92 / 137** | ⭐ **实测值**（不是 `ROWS×PER` 的名义上限）：`per` 的深度分档 + `α<0.004` 早退让实际条数落在 43/92/137。⛔ 记账必须按实测，名义值 60×5=300 会把预算表带偏。✅ **T8d 更正口径**：这三数其实是**行结构的精确求和**（`11+14+18` / `22+26+44` / `28+34+75`），且在**表口径那一帧一条都不剔** ⇒ ops 列与 fill 列同帧（§7.3 as-built 2、G14 ②③）；⚠️ §7.3 结论 1 原文那句"MED `21+13+12 ⇒ ≈95`"是拆账抄错（真实 `22/13/11 ⇒ 92`，**D27**） |
| 13 | `RIPPLE` | 0 / 2 / 4 | **0 / 2 / 4** | 节拍条件项（§7.5）。⚠️ **MEDIUM 是 2，不是初判的 `≤1`** —— `passcost` 半遮态实测到同帧 2 环（偏差 **D17**）；填 `≤1` 会把表写成下界 |
| 14 | `POST_FX` | 0 / 1 / 1 | **0 / 1 / 1** | LOW 为 `FxLevel.OFF` ⇒ 基类跳过 |
| 15 | `DAMAGE_COALESCER` | 1 / 0 / 0 | **1 / 0 / 0** | LOW 无全屏后处理 ⇒ 基类补一次（`RendererFx` ④）。⚠️ **探针测不到它**（它在基类里），§9.2 的 LOW 合计必须手工 +1.00 |
| | **合计** | 34 / 59 / 100 | **74 / 150 / 217** | 实测（`opsTotal`，满月锁定态 ⇒ TERM=0）；若含 `TERM ≤3` 则 **77 / 153 / 220** |

**上限不变**（对齐 `SeasideOpBudget.kt:474/:476/:507`）：`OPS_MAX = 90 / 200 / 320`
⇒ 实测余量 **LOW 1.22× / MED 1.33× / HIGH 1.47×**（⛔ 不是初版算的 2.6/3.4/3.2×）。
⇒ ⚠️ **ops 这一维是安全的，本效果的压力全在填充上**（§9.2）。

⭐ **2026-10-09 半遮态补测**（`passcost`，与 §9.2 同批）把 ops 的最坏值抬到
**74 / 159 / 229**（抬来源 = `RIM` 6~13 个 + `RIP` 抖动，⚠️ **不是**满月锁定态那组的 74/150/217
⇒ **T2 落表必须取这一组**，否则门禁的输入就不是最坏帧）。
⇒ 余量改为 **1.22× / 1.26× / 1.40×** ⇒ 200 这一档变紧了，⚠️ MED 只剩 41 个 op 的空间
（加第 4 片近层云 = +11 op，勉强够；再加就顶到 200）。
`OPS_MAX` 三值本次**未动**（所有者只裁了填充）。

⭐ **T2 实际落表（`MoonOpBudget.kt`，2026-10-09）**：在上面这组实测之上**再叠条件项上界**
（`TERM +3`、`RIM` 取 `9/15` 上界、`RIP` 取 `2/4`）⇒ **78 / 163 / 235**，
对 90/200/320 的余量 **1.15× / 1.23× / 1.36×**。
⛔ 表必须 ≥ 实测（`MoonOpBudgetTest` 的「表是半遮态实测的上界」钉这条）——
门守的是**最坏帧**，而实测锚是"这一帧真的出现过"的证据。

### 9.2 填充（overdraw，单位「屏」）—— ⭐ 定稿实测（自然态 + 半遮态），⚠️ 曾越上限、✅ 已由裁决处置

同一批 `probe` 的 `fillBy`（frame 480，`occl = 0` 的最亮态；遮挡时各项 α 下降但**面积不变**
⇒ 填充率**不随遮挡降低**，⛔ 别指望云遮月来救帧率）：

⚠️ **口径补一句（T7，D24）**：`occl = 0` 那一帧不是"罕见的晴天"，而是**分布的低端**——自然态实测
只有 31% 的帧 `occl ≤ 0.001`、26% 的帧过半遮。但本表**一个数都不用改**：遮挡只动 α、不动几何，
而"α=0 不回收预算"（§3.0(f) 第 4 条）正是这张表口径 2 的立身之本。⇒ 落表仍取**半遮态**（下界同理）。

| 项 | LOW | MED | HIGH | 说明 |
|---|---|---|---|---|
| `SKY_BASE` | 1.000 | 1.000 | 1.000 | 两块拼接，⛔ 不是 2 屏 |
| `STARS` | 0.640 | 0.640 | 0.640 | 天空区 `horizonY/h = 0.64` 屏 |
| `HALO` | **0.075** | **0.141** | **0.262** | 比初版估的 0.07/0.22/0.49 **更省**（`HALO_SPEC` 的半径/α 一起定稿了）。⚠️ **T9 起这三格含 `bass`**（偏差 **D34** 第一笔）：中性实测锚 `0.069 / 0.131 / 0.244` × `haloRadiusK(1)² = 1.036² = 1.0733` ⇒ `0.074057 / 0.140602 / 0.261884` ⇒ 3 位**向上**取整。⛔ 就近取整会把 LOW 折成 `0.074 < 0.074057` ⇒ 表不再是上界（**D28** 那处分量级错的同款）。半径是**几何**量 ⇒ 音频调制在这里**收费**（§3.0(f) 第 3 条）。⚠️ 值域 `k ∈ [0.976, 1.036]` 是**不对称**的（中性点 0.4 离下界更近，见 §八 as-built 第 6 条），但本行按**上沿**收费 ⇒ 不受该不对称影响；表按**连续**上界落，⛔ 不按最高桶桶中心 `0.9375`（那是**缓存粒度**，按它落表会让账跟着 `HALO_R_BUCKETS` 漂）。由 `MoonlitAudioTest` ⑬ 用 `haloFillAtMaxBass` 逐档重算对账 |
| `CLOUD_FAR` | 0.043 | 0.110 | 0.147 | |
| `CLOUD_NEAR` | 0.172 | 0.308 | 0.436 | |
| `CLOUD_RIM` | **0.000** | **0.037** | **0.064** | ⭐ **2026-10-09 补测**（原"未测"）。⛔ 上一版写的补测办法是**错的**：钉 `occlManual = 0.5` 量不到它 —— 银边门槛是**逐斑** `back > 0.34`（`moonlit-preview.html:1141`），钉标量不改几何 ⇒ `rimA` 能到满值 0.14 而 `RIM` 一行仍缺席。正确办法是 `passcost`（真推一缕近云过盘，`occl` 与 `back` 同步）。实测 **MED 6~8 op / 0.0367 屏**、**HIGH 12~13 op / 0.0639 屏**（§9.1 的 `≤9 / ≤15` 上界成立）；**LOW 恒 0 是代码事实**（`S.tier !== 'LOW'` 那道门） |
| `BLOOM` | 0.024 | 0.024 | 0.024 | `(1.28R)²` 的一口 |
| `DISK` | 0.062 | 0.062 | 0.062 | ⭐ 几何式吻合：`π·(TEX_SCALE·MOON_R_K)²·(h/w) = 0.0621` @16:9（含 1.25× 位图外扩） |
| `TERMINATOR` | **0.119** | **0.119** | **0.119** | ⭐ **解析值，实测拿不到**（原型七轮全在 `phaseLock:'full'` ⇒ `f ≥ 0.995` 整段跳过 ⇒ `fillBy` 里根本没有这一行）。算式 = `整盘真实面积 × 层数` = `(0.062 / 1.25²) × 3 = 0.1190`；⛔ **不按"半月只遮一半"记 `0.5×`** —— 本表口径是最坏帧，`f → 0` 时阴影 path 确实铺满整个盘轮廓 |
| `HORIZON_BAND` | **0.160** | **0.160** | **0.160** | ⚠️ 初版记 0.01，探针记 **0.102**（10 倍），**探针自己也错**（⭐ T8d 更正，**D26**）：它写 `op('HZ', 0.16·horizonK)`，而真实矩形 `fillRect(0, horizonY−0.10h, W, 0.16h)` **通铺全宽**且**横跨地平线两侧** ⇒ 带高恒 `0.16h` = **0.160 屏**。与画质档无关 ⇒ 三档同值。表改由 `horizonBandFillGeometry()` **真算**（含上下越界钳制），⛔ 不再引用 `HORIZON_K` 当系数 |
| `REFLECTION` | **0.074** | **0.204** | 0.379 | ⭐ 定稿重做的直接产物（初版 0.01/0.02/0.03 完全失真的原因：它按 14 个小切片估，而真实模型是**盖满整条光带的大椭圆**）。✅ **T8d 按解析几何重算** = 柱头 `0.005248` + 雾片 `0.068134 / 0.129779 / 0.175578` ⇒ `0.073383 / 0.203162 / 0.378740`，旧表 `0.073 / 0.203` 是探针打印值的**就近取整**、比几何**低**（**D28** 的取整部分）⇒ 表按 3 位**向上**取整 |
| `GLITTER` | **0.039** | **0.088** | **0.137** | `len·hh` 包围盒口径（≈真面积 ×1.27）。⚠️ **T8d 换口径**（**D28**）：旧值 `0.035 / 0.080 / 0.129` 是**均值**，表改取**最坏帧**（生产几何 4001 帧扫描 `max = 0.038962 / 0.087361 / 0.136744`，`mean = 0.0335 / 0.0793 / 0.1256`）；⚠️ 本行另有一个**参照系**：与画幅尺度无关、与 `density` 有关（`1.2·density` 地板，**D29** ⇒ 表守 `density ≤ 1.5`） |
| `RIPPLE` | 0.000 | **0.073** | **0.146** | ⚠️ **MED 由 0.001 补测为 0.017**（偏差 **D17**）：`passcost` 半遮态实测同帧 **2 环**（§7.5 / §9.1 第 13 行），初判的「MED `≤1` 环」把 ops 与 fill 一起低算了。⭐ **T9 再把两格按生产几何重算为 `0.073 / 0.146`**（偏差 **D34** 第二笔）：探针的 `0.017 / 0.049` 是**典型帧**（几环诞生于不同拍 ⇒ 半径互不相同），而本表口径是**最坏帧**（每槽都在最大可见半径）⇒ 反解单环 `2π·rx·ry = 0.036350` 屏 @1080p，记账半径取 `pMax = 0.9312`（`MIN_ALPHA` 早退点，⛔ 不是 `p = 1`：那段一个像素都不铺，按 `p=1` 记 `0.08235` 是**多记**）。`MoonlitAudioTest` ⑭ 逐档重算对账 |
| `POST_FX` | 0.000 | 1.000 | 1.000 | 暗角 = 一次全屏 `drawRect`（⛔ 不能记 0，见下） |
| `DAMAGE_COALESCER` | **1.000** | 0.000 | 0.000 | ⚠️ **`probe` 读不到它**（在基类里），LOW 档必须手工补上 |
| **探针合计**（`fill`） | **2.221** | **3.662** | **4.213** | 自然态 `occl = 0`（frame 480） |
| **半遮态合计**（⭐ 新测） | **2.263** | **3.759** | **4.306** | `passcost` 70 s / 140 帧采样取 **max**（云真过盘 ⇒ RIM 在场、`RIP` 与 `GLIT` 抖动同帧）。⚠️ **这三个数是探针账本的原样**，其中地平带那行按 `0.16·horizonK = 0.102` 记 ⇒ 比真实光栅化面积**少 0.058**（**D26**）。⛔ **锚不抬**：实测锚的价值就在于它是"当时那台探针 printed 的数"，改了就再没有东西能发现**账本与几何分家** ⇒ 表比实测高出的那一截里，有 0.058 是这个**已知口径差**（`MEASURED_FILL_*` 的 KDoc 同记） |
| **真实每帧合计** | **3.26** | **3.76** | **4.31** | LOW 含 coalescer +1.00；⚠️ **判预算只取半遮态**（自然态是它的下界） |
| **落表合计**（⭐ T2 落码、T8d 更正水面三行、T9 更正 `HALO`/`RIPPLE`，`MoonOpBudget.overdrawEstimate`） | **3.40804** | **3.96604** | **4.57604** | = 上表 `fill*` 逐行相加（⛔ 这三个数**只住在 `MoonOpBudgetTest`**，别处再钉一份就是第二真源，改表必漂 —— `MoonlitAudioTest` ⑮ 因此刻意不重抄合计）。⚠️ 它**必然大于**实测：条件项（`CLOUD_RIM` / `RIPPLE` / `TERMINATOR`）按**上界**落表 ⇒ 门守护**最坏帧**而非典型帧。涨历：T2 `3.33904/3.83304/4.39504` → T8d 水面三行按几何/最坏帧 `+0.063/+0.067/+0.066` → **T9 两笔几何账 `+0.006/+0.066/+0.115`**（`HALO` 含 `bass` 的 `k²` **+0.006/+0.010/+0.018**，`RIPPLE` 典型帧→最坏帧 **+0/0.056/0.097**，偏差 **D34**）。余量 `3.45−3.40804 = 0.042` / `4.00−3.96604 = 0.034` / 棘轮 `4.7366−4.57604 = 0.161`（⚠️ T8d 时是 `0.048/0.050/0.276`，D26/D28 之前是 `0.111/0.117` —— 每一轮口径修正都在收余量，这是**记账变准**不是**开销变大**；对照 ops 的 `90−78 = 12` / `200−163 = 37` / `320−235 = 85`，**ops 一列 T9 仍未动**）。⛔ 反方向的判据同样存在：`G4 表是半遮态实测的上界` 逐档断言「表 ≥ 实测」，把任何一行偷偷改小都会红 |
| `OVERDRAW_MAX` | **3.45** | **4.00** | **⛔ 不设上限** | ⭐ 2026-10-09 所有者裁决（Q4/Q8/Q9）：「**将上限值调整合适，保证 HIGH 能体现所有效果，实际上 HIGH 不应该有上限**」⇒ 旧的 3.30/3.60/3.50 作废（含 Q9 那条 `MAX_HIGH < MAX_MED` 的符号错），那一轮定的是 `3.45 / 3.95`。⭐ **2026-10-10 二连裁决把 MED 抬到 4.00**（「抬 MED 上限到 4.00」），动因是 **D34** 那两笔**几何**账把表推到 `3.96604` ⇒ 越过 `3.95` 达 `0.016`。⚠️ 处置方向由裁决定：**抬上限，⛔ 不改 `HALO`/`RIPPLE` 两行的数**（它们由生产几何与尺产生，改小就是重新犯 D10/D26/D28）。LOW / HIGH **一个都没动**。"有后果"这件事本身有判据：⑮ 断言 `med > 3.95f` ⇒ 假如表本来就够小，这条改裁决就成了走过场，那条断言会红 |
| **判定** | ✅ **3.327 < 3.45** | ✅ **3.883 < 4.00** | ✅ **无上限**（走棘轮 `4.7366`，`4.479` 在内，见下） | ⛔ **越限问题由裁决消解，不是被优化掉的** —— 三条杠杆（砍云 / 早退 / 改观感）全部**不执行**，水面保真一字不退。⚠️ **给 T10 的一句（T9 更新）**：把 `D26` 那 `0.058` 与 `D34` 那三笔（`+0.006/+0.010/+0.018` 与 `+0/0.056/0.097`）都补回几何后，真实铺屏约 **3.33 / 3.88 / 4.48**（LOW 含 coalescer；T8d 那句写的 `3.321/3.817/4.364` 只覆盖 D26 一笔，自此作废），三档仍分别 ≤ `3.45 / 4.00 / 棘轮 4.7366` ⇒ 判定不变，但**真机判读时按这组数看帧率**，⛔ 别再拿探针账本当上界 |

⭐ **HIGH 档的门怎么还成立（⛔ 不是"删掉判据"）**：没有绝对上限 ≠ 没有判据。`MoonOpBudgetTest`
对 HIGH 改跑两条**真实**断言：
1. **单调方向**：`fill(HIGH) ≥ fill(MED) ≥ fill(LOW)` —— HIGH 必须是铺得最满的一档（防"高画质反而偷工"）。
2. **棘轮**（ratchet）：`fill(HIGH) ≤ FILL_REF_HIGH × 1.10`，`FILL_REF_HIGH = 4.306` 是**实测参考值**。
   它**不是**画质天花板（要超出只需把这一行常量改上去，且必须写理由），但 ⛔ **不许静默增长** ——
   任何让 HIGH 多铺 10% 的改动都会把门判红，这正是"记账失控"唯一能被机器抓住的时刻。
   LOW/MED 仍是**绝对上限**（那两档是真有老设备在下面接）。

⭐ **越上限的构成（这是结论，⛔ 实现期照此取舍）**：初版表里那三行"看起来很省"的
`REFLECTION`/`GLITTER`/`HORIZON_BAND` 才是**全部超出来源** —— 初版合计 0.02 + 0.02 + 0.01 = 0.05，
定稿实测 0.203 + 0.080 + 0.102 = **0.385**（MED，净增 **+0.335**）。
也就是说：**水面从"点缀"变成了"主角"，预算必须跟着重写，而观感不能退回**。
（MED 若把这三项退回初版值就是 3.33，✅ 达标 ⇒ 越限**不是**别处溢出造成的。）
⚠️ **T8d 补正（as-built，仅供读账，⛔ 不构成新的处置依据 —— R12 已裁"不砍"）**：三项按几何/最坏帧
重算后是 MED `0.204 + 0.088 + 0.160 = ` **0.452**（净增 **+0.402**，比定稿那句 0.385 又贵 `0.067`：
`HORIZON_BAND` 的 `+0.058`（**D26**）+ 粼光均值→最坏帧 `+0.008` + 光柱取整 `+0.001`（**D28**））
⇒ 水面这个"主角"比定稿记账时**还贵一点**。上面那句"退回初版值就是 3.33"是分析用对照，
按 as-built 表退这三项是 `3.96604 − 0.452 + 0.05 ≈ 3.56`（仍 < 4.00 ⇒ 结论方向不变；
⚠️ T9 后 `HALO` 那一行也含水面无关的 `bass` 调制，退水面三项**不**动它，故这里减的是
`REFLECTION + GLITTER + HORIZON_BAND` 的 `0.452`）。

⇒ **唯一还有弹性的项是云**（`FAR + NEAR` = MED 0.418 / HIGH 0.583）。其余是地板：
`SKY 1.00 + STARS 0.64 + POST 1.00 = 2.64` 屏**动不得**（STARFIELD 可缩区域，暗角可关但那要所有者点头）。
⚠️ **且 HIGH 光靠砍云达不到**：要降 0.71 而云全砍光只有 0.583 ⇒
必须**同时**动 `POST_FX`（省 1.00 屏，一步达标，但那是**改观感**不是改预算）
或退回水面保真（= 推翻 §7.2/§7.3）。⛔ **两条路都必须所有者拍板**（§十六 Q4 / Q8），实现期不要自作主张。
✅ **还有一条免费的路**：§3.0(f) 第 4 条 / 本表口径 2 —— 给 REFL 与云补上 `α < ε 就跳过`
（原型只有 GLIT 有），按各档 `occl` 分布**预计**省 0.3~0.5 屏，⚠️ 但这是**估算**、
必须在**半遮态实测**后才能算数。

⭐ **2026-10-09 裁决落地后，上面这两段的地位变了**（分析继续有效，**处置作废**）：所有者选择
"**将上限调整合适，保证 HIGH 能体现所有效果**" ⇒ 三条杠杆**一条都不执行**。
由此连带三个结论，实现期照此办：
1. ⛔ **不砍云、不关暗角、不退水面保真**（R12 的"严禁私自"自此有了正面依据：不必砍）。
2. `α < ε 早退`**仍然要移植**（口径 2 的纪律不变），但 ⛔ **不再拿它当"够不够"的判据** ——
   那条 0.3~0.5 屏的估算**永久降级为未采纳的备选**，⛔ 别再为它补测或引用它做决策。
   （实测半遮态已经把 `CLOUD_RIM` 那一行补上了，见上表 —— 该补的数是几何的，不是这条估算的。）
3. **填充越限不再是待办**，本效果的压力回到**唯一真正未证明的东西**：⚠️ 原型从未证明
   API 22 创维在 4.3 屏铺屏下的**帧率**（⇒ §十三 V/R 系列 + T10 真机判读，那才是裁决的兑现处；
   若真机不达标，处置由所有者重裁，⛔ 实现期不要提前退回保真）。

⚠️ **记账口径三条，⛔ 一条都别改**：
1. 椭圆按 `2π·a·b` 计（**2× 真面积**，E43 的既有约定）；粼光划按 `len·hh` **包围盒**计（≈1.27× 真面积）。
   ⇒ 本表与 E43 的表**可比**，但与"真实像素覆盖"不可比。
2. ⛔ **面积记账不看 α**（§3.0(f) 第 4 条）：α≈0 的图元照样吃掉整块面积
   ⇒ Kotlin 侧每个图元**必须在几何之前早退**，这是**免费的**降填充手段（原型的 GLIT 有这条、
   REFL 与云没有 ⇒ **移植时补齐**，HIGH 档预计能省 0.3~0.5 屏）。
3. ⚠️ **`POST_FX.fill = 1.00` 不能记 0** —— `SeasideOpBudget.kt` 的 KDoc 明确记着这是
   "记账漏洞"（晕影是一次全屏 `drawRect`）。
4. ⚠️ **`DAMAGE_COALESCER` 只有 LOW 有**，且 ⛔ 不在探针可读的 `fillBy` 里（`RendererFx.kt:54` 的基类行为）。

⇒ 本效果的**填充比 E43 更"平"**（场景本身铺满全屏），所以不能照抄 E43 的上限。
⭐ **定稿的上限（2026-10-09 所有者裁决，Q4/Q8/Q9 一并解决）**：`OVERDRAW_MAX_LOW/MED/HIGH
= 3.45 / 3.95 / 不设上限`。
⚠️ **其中 MED 已被 2026-10-10 的二连裁决抬为 `4.00`**（「抬 MED 上限到 4.00」）—— 动因不是"表太大"，
而是 **D34** 把 `HALO`/`RIPPLE` 两行按生产几何补正后合计到了 `3.96604`；LOW 与 HIGH 那两条
**一字未动**（HIGH 本来就没有绝对上限）。本节其余关于"3.95"的算术按 `4.00` 读，
**上面那句裁决原文保留不改**（它是 2026-10-09 那一次的留痕）。
- ⚠️ 与 E43 的 `2.0 / 3.89 / 3.96`（`SeasideOpBudget.kt:515/:531/:561`）**不同** —— 那组建立在
  "海只占下半屏 + 大量区域留空"的构图上，照抄会让 E44 一开工就红（这条差异 = 偏差 **D5，已签字**）。
- ⚠️ 旧的 `3.30 / 3.60 / 3.50` **已被本裁决作废**，含 `MAX_HIGH < MAX_MED` 那个符号错（**Q9**）。
  追溯：它不是设计，是 **v1.0 按"设计值 + 约 0.2 余量"凑的** —— v1.0 的 fill 设计值是
  3.09 / **3.37** / **3.23**（D10 有记录），HIGH 的设计值本就低于 MED，上限跟着低。
  ⇒ 教训与 D10 同一条：⛔ **预算表与设计值都不该拿来当上限的上游**，上限必须由实测最坏帧推出。
- ✅ 唯一能为 `MAX_HIGH < MAX_MED` 辩护的读法（"HIGH 用提交数买保真、不许用填充买"，
  E43 的 `OPS_MAX_HIGH 200→320` 正是这条路）**未被采纳** —— 所有者明确选择 HIGH 铺满。
  该读法由上面的**单调方向断言**接替：HIGH 必须仍是铺得最满的一档。


### 9.3 native 堆（位图像素总数，`NATIVE_PX_MAX` 同 E43 = 3,000,000）

| 位图 | 尺寸（as-built） | 像素 |
|---|---|---|
| `moon.jpg` 解码源图 | 1024×512 | 524,288 |
| 圆盘位图 | 边长 = `2 · texRadius(minDim·MOON_R_K)`，`texRadius(r) = clamp(round(r·1.25), 192, 512)` | 1080p：**406² = 164,836** |
| `Id.STARFIELD` 全屏（共享，**不降采样**） | `w × h` | 1080p：2,073,600 |
| `Id.GRAIN ×8`（本文**刻意不开**，§9.4） | 128²×8 | 131,072（不计入） |
| **合计 @1920×1080** | | **2,762,724** ✅ < 3,000,000（相对**门槛**的余量 7.9%） |

⛔ **本文原写作「4K 必然突破」是错的 —— 真正的破点在 1440p**（偏差 **D18**，2026-10-09 落码时算出）：
`STARFIELD` 项 = `w·h` 随分辨率**平方**增长，圆盘位图被 `TEX_R_MAX` 钳住不再涨，所以破点完全由
STARFIELD 单项决定：

| 画幅 | `w·h`（STARFIELD） | 圆盘 | 合计 | 判定 |
|---|---|---|---|---|
| 1280×720 | 921,600 | 384²=147,456（撞 `TEX_R_MIN`） | 1,593,344 | ✅ |
| 1920×1080 | 2,073,600 | 164,836 | 2,762,724 | ✅ |
| **2560×1440** | **3,686,400** | 540²=291,600 | **4,502,288** | ❌ **STARFIELD 单项即已越限** |
| 3840×2160 | 8,294,400 | 810²=656,100 | 9,474,788 | ❌ |

⇒ **本门禁的适用区间是 ≤1080p 的 16:9 画幅**，`MoonOpBudget` 里 accordingly 记为
`NATIVE_PX_OK_MAX_LONG_EDGE = 1920` / `NATIVE_PX_BREAK_LONG_EDGE = 2560`，并有一条**哨兵单测**
`G4 native 的破点是 1440p 哨兵` 显式断言"2560×1440 会破"。⚠️ 它故意红不了、也⛔**不许改成"通过"**：
它的价值是——若日后给 STARFIELD 加了降采样、把 1440p 救回来，这条会**变绿**，那就是修复的机器证据
（反过来，把阈值调大让它继续红是作弊）。1440p/4K 的真机处置属于 §十三 U 系列，⛔ 不由本门决定。

对策（✅ 已落码，与本文初判一致）：圆盘位图**按屏比例而不是绝对值**（`texRadius` 的两道钳
192/512 就是它），加上 §十 的"只分配这两张位图"镜像门禁（G8，源码扫描 `Bitmap.createBitmap` ≤ 2 处）。
⚠️ 与 E43 的 `SAND_TEX_MAX_PX` 冲突同族（`SeasideOpBudget.kt` 的 `NATIVE_PX_MAX` KDoc 记着"待裁定"），
E44 这边的处置是**把适用区间写进门里**而不是抬门槛。

### 9.4 ⛔ 颗粒（grain）刻意不开

`postFx.grain = 0f`。理由：颗粒是**第二次全屏** `drawRect`（+1.00 屏），叠在 §9.2 半遮态实测
HIGH **4.306** 上直接到 **5.31 屏** ——⛔ **撞破棘轮**（`4.306 × 1.10 = 4.7366`，§9.2 的 ⭐ 块），
等于让 HIGH 一开档就多铺 **23%** 的屏只为一层噪点；且 E43 已有同向裁决
（`FxCoverageScanTest.kt:56` 注：「只保留暗角 0.30，去掉颗粒」）。
星空的"噪点感"由 STARFIELD 纹理自己提供。
✅ 这条决定在门里有**负向自证**看管：单测 `负向自证 开颗粒则 HIGH 撞破棘轮` 用
`MoonOpBudget.GRAIN_EXTRA_FILL = 1.00` **显式注入**颗粒后必须判红 ——⛔ 本表没有 `fillLegacy` 列
（E44 不存在"改造前"形态，§9.1 已记），所以负向自证一律靠这类注入常量，不靠历史列。
✅ 正因为用的是**注入**而不是抄数，T8d 把表涨到 `4.46104`、T9 再涨到 **`4.57604`** 之后
这条结论**只会更强**（`4.57604 + 1.00 = 5.58` 屏，超出棘轮 `4.7366` 达 `0.84` 屏）；
`MoonlitSkySeaTest` ④b 已改为**不引用任何合计**（它只钉"没开"这个事实，那笔账由
`MoonOpBudgetTest` 用 `overdraw(HIGH) + GRAIN_EXTRA_FILL` 机器化 —— 手抄合计屏数会随每次涨表漂，
这正是 D10 那类"两处各自说"的第三次犯）。

---

## 十、门禁与测试清单（⛔ 一条都不能省，全部为本轮实测）

### 10.1 现有门禁**必然变红**的点位（新增第 22 套）

| 文件:行 | 现值 | 改成 |
|---|---|---|
| `data/model/VisualizerThemeTest.kt:51` | `assertEquals(21, entries.size)` | 22 |
| 同上 `:57` `:58` | `assertEquals(21, selectable.size/distinct)` | 22 |
| 同上 `:89` | `assertEquals(20, off.size)` | 21 |
| 同上 `:90` | `assertEquals(21, on.size)` | 22 |
| 同上 `:96` `:103` | `21`（label 唯一 / 显示名唯一） | 22 |
| `visualizer/renderers/StarrySkyTest.kt:1039` | `21`（"效果总数（门禁硬计数）"） | 22 |
| `fx/FxCoverageScanTest.kt:41-56` | `covered` 14 项 | **加入 `"MoonlitRenderer"`**（本效果走 `postFx` 暗角） |
| `fx/FxCoverageScanTest.kt:252` | `decls.size >= 21` | 文案与阈值 → `>= 22` |
| `fx/FxCoverageScanTest.kt:262-266` | 未归属/stale 双向断言 | 无改动，但**必须真的红一次**再修（见 10.2） |
| `AGENTS.md:56` | 「`FxCoverageScanTest` gate expects 21 renderer classes」 | 22 |
| `docs/visualizer-effects-list.md`（`:7/:11/:59/:64-87/:105-107`） | 30 项/21 套口径 | 加 E44 行，三档计数同步 |
| `docs/technical-overview.md` | 最大 §10.219（写本表时） | 新增一条 ⇒ ⚠️ **§10.220–10.223 已被占用**，写时取当时候空闲号（2026-10-10 为 **§10.224**） |

✅ 本表除最后一行外，已于 **2026-10-09 随 §十一 T3 全部落地**，逐项实测见该条。
⚠️ 最后一行（`technical-overview.md` 那条）按所有者指示**等真机测完再写**（2026-10-10），草稿见头部说明。

⚠️ `StarrySkyTest.kt:1051` 有一条**穷举扫描**：每个枚举都必须有字面 `VisualizerTheme.X ->` 分支；
`ParticleBudgetGateTest.kt:89` 会拿"源码里读 `maxParticles` 的渲染器集合"对撞枚举标注 ⇒
只要 §八 的规则（不读 `maxParticles`）被遵守，这两条自然通过。

⚠️ **双语字符串缺失 = 运行时崩溃、无测试会红** ⇒ 新增 **G1**：
读 `values/strings.xml` 与 `values-en/strings.xml`，断言每个 `displayNameRes` 名在**两份**里都存在。
（现在没有任何测试覆盖这件事。）

### 10.2 新增测试

| 门 | 内容 | 关键要求 |
|---|---|---|
| **G2 `MoonPhaseTest`** | §四 A/B/C/D 全部向量 | ⛔ 含 D 的 4 条**负向自证**（改错速率/历元必须红）；⚠️ B 表**逐字断言**（定稿改过一次月龄式 `wrap360(e)/360 × 29.530588853`，旧式把 10-08 算成 12.62 天而 B 表写 27.39 —— 不逐字断言就发现不了又漂移一次）；e 列按 §4.6 B 的**混记法**条款比较（`abs(wrap180(elongDeg − 表中值)) ≤ 1.5°`，⛔ 直接断言会红） |
| **G3 `MoonlitTest`** ✅ **28 例已落（T7）** | ①–②b 播种与几何**与原型逐字对账**（`delta = 0.0`，基线来自 `moonlit_cloud_golden.js`）；③–④b `occl` **只由近层**算 + 平滑核单调不越界；⑤ 包络 = 斑椭圆 AABB；⑥–⑥b 满遮态五个消费点到设计下限 + **同源比恒等式**；⑦–⑦b `occl` **分布**五项对账（自然态 vs 过境态）；⑧ 切档不重播种；⑨–⑨b 位移只由增量累加（倒退即红）；⑩–⑩b §6.6 过境只改 `cy`；⑪–⑪b 银边只在半遮 + `back` 除零防线；⑫–⑫c 着色器分桶量化误差；⑬–⑬e 渲染器接线（层序、刻意常量不复活、只用矩形裁剪、Shader 不入逐帧、云场零 Android）；⑭ 提交数 == 几何恒等式 | ⭐ 这就是需求 3 的**可执行定义**。**口径的三条纪律**（都是踩出来的）：① ⛔ 判据不许手抄原型公式 —— 基线由 `docs/archive/verification/scripts/moonlit_cloud_golden.js` 在 `vm` 沙箱里**跑原型原文**再生成 `MoonlitCloudBaseline.kt`，手抄等于拆哨兵；② 分布类判据一律跑**生产几何**（脚本自带两条防退化自检：`minDim` 没赋进上下文全局就抛、包络半轴全 0 就抛 —— **D24 就是没有这两条时发生的**）；③ ⚠️ 原判据那句"必须断言 `occl = 1` 这个自然场到不了的值"**已作废**（D24：自然场**能**到 1，触顶占 1.71%），改为断言**过境态的触顶时长显著更长**（9.98% vs 1.71%，5.8×）+ 一条 ⑦b 负向自证"远层混进 `occl` ⇒ 触顶占比与均值必须上升"。另：⑫ 的钉住值取**实测上界**（生产可达域单通道 **18** / 合计 **46**，全立方 **22** / **51**；判据钉 `≤20/≤50` 与 `≤28/≤62`），配 ⑫b"刻度砍半就越线"证明 16 桶不是随手挑的 |
| **G4 `MoonOpBudgetTest`** ✅ **已落地（2026-10-09，T2；T8d 补正水面三行、T9 补正 HALO/RIPPLE 两行 ⇒ 23 例）** | 三档 ops `78 / 163 / 235` ≤ 90/200/320；fill **`3.40804 / 3.96604 / 4.57604`**（T2 初落 `3.33904 / 3.83304 / 4.39504`；T8d 按几何/最坏帧更正水面三行 → `3.40204 / 3.90004 / 4.46104`；T9 再把 HALO 按最坏帧乘 `k²`（`+0.006/+0.010/+0.018`）、RIPPLE 由探针典型帧改生产几何最坏帧（`+0.056/+0.097`）⇒ 现值，见 **D26 / D28 / D34**）≤ **3.45 / 4.00 / 棘轮 `4.7366`**（MED 上限 3.95→4.00 是 T9 的**所有者裁决**，⛔ 不是把两行的数改小；LOW 上限与 HIGH 的"不设绝对上限"一个都没动）；余量 `0.042 / 0.034 / 0.161`；native `2,762,724` < 3 M。**23 例 / 0 失败**（纯 JVM 单测，⛔ 不挂 Robolectric） | ⛔ 不要指望 `LowTierElementBudgetTest`（`SeasideOpBudget.kt:460-466` 明写它只扫三个老效果）。判据形态：① **单调方向** + **棘轮**（`≤ 4.306 × 1.10`）接替 HIGH 的绝对上限，且门的**存在性**本身也有断言（`overdrawMax(HIGH).isInfinite()` **且** `fillRatchetMax().isFinite()` ⇒ 用一个"很大的有限数"假装不设上限会红）；② `G4 表是半遮态实测的上界` 逐档断言 表 ≥ 实测（74/159/229 op、3.26/3.76/4.31 屏），⛔ 拿满月锁定态落表就红在这里；②b ⭐ **三档合计的具体数只住在 `MoonOpBudgetTest:158-160`** —— G15 ⑮ 只钉"三档都在门内 + MED 的上限是被裁决抬起来的"，⛔ 重抄合计就是第二份真源，改表时它会先漂；`MEASURED_FILL_*`（3.263/3.759/4.306）是**探针实测锚**，T9 让表涨了 0.006/0.066/0.115 而 ⛔ 锚一律不动（锚动 = 伪造实测）；③ **六条负向自证**：`粼光按名义条数 300 落表 ⇒ 398 > 320`、`开颗粒 ⇒ 撞破棘轮`、`全屏 pass 记 0 ⇒ 表掉到实测以下`、`旧上限 3.30/3.60/3.50 ⇒ 逐档判红`、`圆盘按整屏方图烘 ⇒ 7,263,488`、⭐ **`地平带按探针口径落表 ⇒ 低于真实矩形面积`**（T8a 新增，钉 **D26** 的方向：拿 `0.16·HORIZON_K` 当面积会把一条通铺全宽的压暗带折成"只有天空那份"）；④ native 侧**破点哨兵**（D18）+ `G4 圆盘边长与烘焙层同源`（`diskSidePx` 直接调 `MoonDiskBake.texRadius` ⇒ 烘焙层改钳位，门自动跟着动，不靠抄数）+ `几何恒等式行与几何式吻合` / `预算常数与渲染器常数不漂移`（水面两行的上下沿系数、`HORIZON_K` 与 `MOON_HORIZON_FILL`） |
| **G5 `MoonlitDtClockTest`** | ① ⛔ `ctx.nowMs`；② ⛔ `System.currentTimeMillis()` 出现在任何 `× 系数`/`+ 位移` 表达式里（只许出现在 `onEnterContent` 的锚定赋值与 `MoonPhase` 实参）；③ ⭐ **`t * spdMul` 形态**（绝对时间乘音频调制）—— 位移必须写成 `x += speedK·spdMul·dtSec` 的增量累加 | 正则扫描，仿 `RendererBaseContractTest:167`。③ 是第四轮"云随鼓点抽搐"的根因，光有时钟门抓不到 |
| **G6 `MoonlitContextLeakTest`** | 工厂分支必须是 `MoonlitRenderer(context.applicationContext)`；渲染器字段类型是 `Context` 且**只**用于 `assets` | 防 Activity 泄漏（§5.3） |
| **G7 `MoonlitNoNewAssetTest`** | 断言 `app/src/main/assets` 下**没有**新增月相关文件（只许复用 `globe/moon.jpg`） | 与 `GlobeAssetsHygieneTest` 互补；APK 体积是硬约束 |
| **G8 圆盘只烘两张** | 扫描渲染器：`createBitmap` 调用点 ≤ 2（源图 + 圆盘），⛔ 循环内分配 | E29/E43 的同类镜像门禁 |
| **G9 降级路径** | `map == null` 时不黑屏、不抛、画出程序化月面 | ⛔ 不许用"返回不画"糊过 |
| **G10 烘焙单位（定稿新增 · ⚠️ 判据经实测修正，2026-10-09）** | 用**真素材** `moon.jpg` 走完整曲线烘一张 `R_tex=203` 圆盘，断言 ① 通道钳位生效（判据是 **alpha 域**：`coerceIn` 之后"≤255"恒成立 = 空转，而没钳位会 `shl` 溢出进 alpha）；② **不存在整行三通道全 255**；③ ⭐ **全盘顶格占比 ≤ 8%**；④ **盘内 R 均值 ≥ 140**；⑤ `p95/均值 ∈ [1.55, 1.72]`，⛔ 只当**分布形状哨兵** | ⛔ 原文的 ②「盘内 p95/均值 ≤ 1.25」实测**既不可满足、方向又是反的**：定稿在纹理层就是 **1.639**，而单位坑（顶格 100%）把它压到 **1.000** ⇒ 越曝越"合格"。换成 ③+④ 后四类形态彼此可分：定稿 `5.4% / 155.5`、单位坑 `100% / 255.0`、旧曲线+GAIN1.5 `13.6% / 162.5`、漏乘 GAIN `0.2% / 117.9`。⭐ ③ 抓过曝、④ 抓发暗，缺一不可（④ 单独存在时，"漏乘 DISK_GAIN"这类缺陷 ③ 完全看不见）。另记：喂进烘焙的 tint 是 `tintMul × lumK × DISK_GAIN`（原型 `:976`），⛔ 只乘 `tintMul` 整盘 R 均值掉到 134 |
| **G11 星野哈希位序（定稿新增 · ⚠️ 位序半边经实测降级，2026-10-09，`MoonlitStarfieldTest` 5 例）** | ✅ **无格点半边**：判在**真实星位**（`ProceduralTexture.starLayout`）上，四组画幅 960×540 / 1280×720 / 1920×1080 / 3840×2160 逐条断言 **`dup ≤ 2`、`maxCol ≤ 6`、`maxRow ≤ 6`、`distinct == n`**（实测 `dup=0 / maxCol≤3 / maxRow≤4 / distinct=n`）；另断言星数 = `w·h/9000`（与生产**同式**）与坐标域、档位 ∈{0,1,2}。⚠️ **位序半边**：E44 星位来自共享 **LCG**、生产码无 murmur ⇒ 降级为「有牙夹具 + 前进钩子」（见 **D20**） | 原型实测：写错优先级会让星野长出一张规则网格（"星星排成方阵"），是那种一眼看穿但单测永远抓不到的错。⭐ **两条负向自证**证明判据真的有牙：③ 同一把尺下 murmur 位序坑 `x \| K` 判红（实测 `dup=195 / maxCol=maxRow=22 / distinct=35`，与正确位序差一个数量级）而正确 `x xor K` 判绿；④ **纯方阵**夹具 `dup=0 / distinct=n` ⇒ 单靠"重复点"判据**必漏**，列/行载荷才是抓它的那只手 ⇒ **三度量必须并用**。⑤ 一旦生产码出现 `0x9E3779B9`，同一行判据要求它以 `xor` 施加（坑写法夹具判红做自证） |
| **G12 相位阴影几何（T5 落地新增，`MoonPhaseShadowTest`）** | A 组：闭式判别式 `暗区/整盘 = (R+v)/(2R)` ⇒ **层 0 恒等于 `1−f`**（A3，⛔ 只在 `f=0.5` 一个点相等，写反轴向在那里看不出来）；`f=0.25` 暗区 ∈ [0.70,0.80]；`f=0.5` 半短轴 = 0；三层嵌套方向随 `sign` 翻转且软带宽恰为 `TERM_SOFT_K`；`f ≥ 0.995` 跳过 + **负向自证**（先证明该几何在 `f=1` 真会长出 ≥4.5% 盘的暗斑，"画淡一点"救不了，面积下限与 α 无关）；` \|v_i\| ≤ R` 全程成立。B 组：`e=180°` 时 `tiltDeg` 与 6 个 PA 无关、弦月处**吃满** PA；度数换算 `−(tilt+90)` 含"疑似又乘了 π/180"的量级自证；§4.6 B 表 8 个真实历元**喂进几何**后自洽（挡量纲被改成百分数）。C 组：渲染器源码扫描 | ⛔ 扫描一律跑在**剥掉注释**的源码上：本类 KDoc 原文引用了 `clipPath(` / `ctx.nowMs` 等被禁写法，不剥注释是自己判自己红（承 E43 的同类教训）。C 组查：零裁剪拼写 + 闭合 `Path` + 两次 `arcTo` + 零 `Matrix` + `Path`/`RectF`/`ANTI_ALIAS Paint` 的**构造点计数**（复用字段而非逐帧新建）、`drawContent` 的**层序**、阴影里 ⛔ 不再乘 `f`、§6.5 边缘必须按阈值早退 |
| **G13 天空与海面接线（T6 落地新增，`MoonlitSkySeaTest` 13 例 + 色尺 `MoonSeascapeTest` 11 例）** | ⛔ 渲染器源码 `0xFFRRGGBB` **0 处** 而 `MoonSeascape` 必含 `0xFF03050C`/`0xFF060B18`（= §3.1「同一把尺」的结构化版本）；两块**不相交**矩形（`topLeft = Offset(0f, horizonY`、`Size(w, h - horizonY)`、`drawRect(` 恰 2）；`skyBrush`/`seaBrush` 四件齐 + 海尺三档端点逐字 + `to Color(` 恰 3；`Brush.verticalGradient(` 全文恰 **3** 处（sky / sea / 地平带，T8 落 §7.6 时的**合法升级**，⛔ 第三条必须落在按桶缓存的 `bandBrushOf` 里而非 `drawHorizonBand`）且**不在任何 `draw*` 体内**；⭐ **T9 后又升了一档但处数没变**：海体那条从字面 `seaBrush` 变成按桶缓存的 `seaBrushOf`（`seaBrushes[bucket] = built` 的缓存写入也被钉），②c 现要求三条渐变分别落在 `skyBrush` / `seaBrushOf` / `bandBrushOf` 三个**非 `draw*`** 函数里（判据形态与 G14 ⑥ 的桶缓存同源）；暗角 `VIGNETTE_EDGE = Color(MoonSeascape.sea(0f, SB_NEUTRAL))` + `vignette = 0.42f` —— ⚠️ T9 接上 `vignetteOverride` 后这个字面量**仍必须留着**（`FxCoverageScanTest` 只读 `postFx` 里的**数值字面量**，换成表达式它就数到 0 个；真正的无声帧等式由 G15 ⑯b 的 `vignetteA(0) == 0.42f` 钉）；`ALT_T` 全文只有一份（`MoonDiskBake` 里 ⛔ 不得再声明）；层序 sky→sea→starfield→disk→bloom→shadow；星野 1:1 部分源区裁剪 + `?: return` + 恰 1 次提交 + 零 `clipPath(`/`clip(`/`RoundedCornerShape`；`STAR_A = 0.62f` + `BlendMode.Plus`；与 `MoonOpItem.STARS` 的 1 op / `MOON_HORIZON_FILL` 对账；`postFxRe`/`numRe` 的**正负双证**；不开 `grain`/`scanline` 且不调 `ensureTiled`（D21 的反向钉） | ⚠️ 扫描一律跑在**剥掉注释**的源码上（承 G12 同类教训）。⚠️ 两条本机真实踩到的判据缺陷：① 拿 `initializerOf` 取到的 `Color(…)` **括号内**片段去断言 `Color(MoonSeascape.sea(` **永不成立** ⇒ 必须用整条声明正则；② 单参 `assertTrue("…")` 编不过（本仓用 `org.junit.Assert` 的 **message-first** 双参式，⛔ 不是 `kotlin.test`）。⭐ 这条门的存在意义 = 把 §3.3+ 记录过的"抄第二份色值然后各自漂移"从**散文约定**变成**会红的判据** |
| **G14 `MoonlitWaterTest`** ✅ **已落地（2026-10-09，T8d，18 例）** | 水面三行（`HORIZON_BAND` / `REFLECTION` / `GLITTER`）从**探针打印值手抄**改为**由生产几何重算**：① `hash1`/`noise1`/`fbm1` 与原型**逐位**对账（含 JS `x\|0` 向零截断 ⇒ `hash1(0.0)==hash1(−0.3)==hash1(0.5)`、`noise1(4.0)==hash1(4.0)`）；②②b 粼光**结构条数** `43/92/137` == `MoonOpItem.GLITTER` 的 ops（分档拆账 far/mid/near = `11/7/6`、`22/13/11`、`28/17/15`）；③③b 粼光填充 == 生产几何**最坏帧**（`0.038962/0.087361/0.136744`，argmax `t=96.5/552.5/312.5 s`，扫 `t=0..1000 s` 步长 `0.25 s` 4001 帧），且「表 − 实测 ≤ 千分之一屏」；④④b 尺度无关性 **与** density 参照系（D29 矩阵：1080p@1.0/1.5 逐位相同、@2.0/@3.0 与 900p@2.0/@3.0 各记一格，高密度那一截**仍落在档位余量内**）；⑤⑤b 光柱 ops 恒等式 `1+fogCount = 2/3/4` + 存在门只在「新月 ∧ 满遮」闭合（`reflA(0,1)=0.002112 < MIN_ALPHA`、`reflA(1,1)=0.0352` ⇒ 云遮月**不会**让水面变空）；⑥⑥b 地平带几何系数与表一致（通铺全宽 `0.160`，D26）**且**只有月色标随 `occl` 变 ⇒ 16 桶缓存的解析误差上界 `0.05/(2·16)=0.0015625`；⑦ 水色走 `whiteOf` 不走盘色（单调性钉 **G** 通道，R 在 `t=0` 已 255）；⑧–⑧d 层 7 顺序（带→柱→划，排在云之后）、水面**不重算遮挡**（与盘同帧变暗靠同一个 `occl`）、时基只吃 `dt` 且不逐帧分配、`MoonWater` **零 Android 依赖**（G4 能纯 JVM 重算的前提）；⑨ `hh/Δy` 三档三点实测（D30） | ⭐ 这就是「表与几何各说各话」（**D10 / D24** 病根）在水面侧的封口：渲染器画的和账本记的是**同一份代码**。**三条纪律**：① 表 ≥ 实测且按 **3 位向上取整**（就近取整当场被 `GLITTER` MED 与 `REFLECTION` LOW/MED 抓到三次 ⇒ ③b/⑤ 各配一条"上界也不能松成一屏的千分之一"）；② 口径必须写明**参照系**（粼光与画幅尺度无关、与 `density` **有关** ⇒ ④ 钉尺度、④b 钉密度，⛔ 不许合成一条"分辨率无关"了事）；③ **负向自证必须真的会塌**：①b（`or` 替 `xor` ⇒ 300 个站位塌成几十种）、②b（每行均摊 `PER` 条 ⇒ LOW 的 ops `78−43+72=107 > 90`）、⑥b（分桶刻度砍半 ⇒ α 量化误差越界）、④b（`1.2·density` 地板**确实**触发，否则 D29 那段叙述是空判据）。⚠️ 全部期望值来自 `docs/moonlit-preview.html:354-379 / :1221-1273` 的同一套算术（JS float64 ⇒ Kotlin `Double`）；改云场/粼光任何一段数学都可能让 **argmax 换帧** ⇒ 红在 ③ 的时间戳断言上，这是**有意的**（粼光无状态，换帧意味着分布变了，⛔ 不能只把表值改大当"对账通过"）。⚠️ 扫描同样跑在**剥掉注释**的源码上 |
| **G15 `MoonlitAudioTest`** ✅ **已落地（2026-10-10，T9d，20 例：①②③④④b⑤⑥⑦⑧⑧b⑨⑩⑪⑫⑫b⑬⑭⑮⑯⑯b）** | §八 那七把尺**全部**住在 `MoonAudio.kt`（纯函数、零 Android import），本门直接调它们 ⇒ 数字不落抄。两类判据：⭐ **夹具式**（①② 推进次数与"全仓只有这四行"、③ `dt` 单位（把文档原文当夹具喂进去必须判红）、⑨ `mid` 只改云速**增量**（`* tSec` 夹具判红）、⑪ 暗角随能度**单调变浅**（原型的 `lerp(vignHigh, vignLow, k)` 写法作夹具判红）、⑫⑫b 涟漪只吃 `beat` 那一帧且是四停位软环、⑧⑧b 缓存容量与尺同源 / 晕的重建键 ⛔ 不含桶号 —— 靠**构造错误写法**判红）与 ⭐ **口径式**（⑬ HALO 行 = `中性实测 × k²` 向上取整、⑭ RIPPLE 行 = `MoonWater.rippleFillFraction` 的**生产几何最坏帧** —— 靠**重算**判绿，与 G14 同一封口思路）。④/④b/⑤/⑥/⑦ 是**尺本身**的逐行对账与"刻度砍半就越线"。钉住的偏差：**D31**（`fx.dt` 已是秒，⛔ 不是 `dt/1000`）、**D32**（暗角方向以 §八 文字为准：`0.42 → 0.36` 变亮，原型字面是反的）、**D33**（`pulse` 从未接线，`beat` 驱动涟漪）、**D34**（HALO 面积按平方收费 + RIPPLE 探针典型帧冒充最坏帧）。⭐ **⑯b 是本批改动的观感闸门**：七把尺一律写成 `(a − 0.4)` 偏移形态 ⇒ `NEUTRAL = 0.4` 处乘子恒为 1，**无声帧逐像素等于 T8 行为**（四个中性钉 `SB_NEUTRAL`/`SPD_MUL_NEUTRAL`/`TREB_NEUTRAL`/`RIM_BEAT_NEUTRAL` 逐一复现）。⑯ 钉 §八 末行四条"不做"（⛔ 不读 gamma 频谱、⛔ 不读 `bassRaw`、⛔ 不读 `maxParticles`） | ⚠️ 三处容易写错的口径，本门都用**负向自证**钉住：① `haloRadiusK` 的值域是 **`[0.976, 1.036]`**，⛔ 不是 `±6%` 的对称区间 —— 中性点 `0.4` 不是合法区间中点 `0.5`，`aBass = 0` 那端只走到 −2.4%（要 −6% 得 `aBass = −0.2`，取不到）；落表按上沿 `1.036` 收费，④ 钉两端。② RIPPLE 的"最大可见半径"不是 `p = 1` 而是 `ripplePMax() = 0.9312`：α 在那一点**恰好**贴到 `MIN_ALPHA`（⑭ 有等式断言 ⇒ 早退判据与记账口径同源），按 `p = 1` 记账会**多**记成 `0.08235`（MED 表 `0.073`），那段一个像素都不铺。③ 分桶误差必须是**解析式** `SPAN/(2·桶数)`：海面 32 桶 → `0.0015625 ≤ 0.002`、晕 8 桶 → `0.00375 ≤ 0.005`，⑦ 各配一条"刻度砍半（16 / 4 桶）就越线"，⛔ 不拿取整步长当判据（海色最亮通道只有 29，那条判据**永不会塌**）。④ ⭐ **表旁不留第二份口径**：`HALO` 的换算住在 `MoonOpBudget.haloFillAtMaxBass` 里（`:660` 直接调 `MoonAudio.haloRadiusK`），`RIPPLE` 的几何住在 `MoonWater.rippleFillFraction` 里 ⇒ 门（⑬⑭）**调用**这两个生产函数逐档重算再与表对账，⛔ 测试与表两边都不重抄公式；其余四把尺（`seaBright` / `vignetteA` / `cloudSpdMul` / `rimBeatK`）由 ④⑩⑪ 与 §八 逐行对账 ⇒ 改尺即改判据。⑤ ⑮ 只钉"在门内 + 上限来源是裁决"，⛔ 不重抄 G4 的合计 |

### 10.3 零分配红线（`PerfBudgetContractTest.kt`，函数体内禁）

`:128-129` 字符串模板、`:132-133` 参数化 `Rect(`、`:136-138` `listOf/mutableListOf/mapOf/.map{/.sortedBy`。
豁免写法：行尾 `// Perf-exempt: 理由`。
⚠️ 该门**不查** `Brush.*Gradient(vararg)`（§2.6），故 §十一 T6 要自建扫描（抄 E43 的补洞做法）。
✅ **T6 已补**：`MoonlitSkySeaTest` ②c 断言全文 `Brush.verticalGradient(` 恰 2 处且**不落在任何
`draw*` 函数体内**（逐字 G13）。
⚠️ **T8 起该处数合法升到 3**（sky / sea / 地平带 `bandBrushOf`）：②c 的**实质判据从未变** ——
⛔ 任何 `fun DrawScope.draw*` 体内都不得出现 `Brush.verticalGradient`，第三条渐变**必须**落在
按 `occl` 分桶缓存的入口里；判据已同步为「恰 3 处 + 第三条必须在 `bandBrushOf`」
（见 §7.6 的 as-built 块与 **D26**）。
✅ **T9 起处数仍为 3，但第二条**从字面 `seaBrush` 变成按 `sb` 分桶缓存的 `seaBrushOf`
（§八 的 `bass → 海面亮度` 长在 `Brush` 里 ⇒ 逐帧新建即违反本红线；做法与 §7.6/D23 **同一套路**，
桶数 32、解析误差 `0.10/(2·32)=0.0015625`，判据见 G15 ⑦）。②c 的实质判据依旧未变，
新增的是「海体那条必须落在 `seaBrushOf` 且缓存写入 `seaBrushes[bucket] = built` 存在」。

---

## 十一、实施步骤

- [x] **T0 浏览器原型（`docs/moonlit-preview.html`，§3.0(e)）** —— 2026-10-08 交付，经**七轮**迭代后
      所有者宣布「html 效果定稿」⇒ **技术路线、算法、观感三项全过**。
      算法自校验：月相链 6/6 锚点 + 8/8 向量 + D1–D4 负向自证全绿，9 个日期的亮区面积实测 vs `f` 差 ±0.023。
      观感最终由客观门把住（`moonlit_ref_match_check.py` 的六窗对比 + `moonlit_water_zoom.py` 的放大对照，§3.0(e)）。
      ⛔ **原型代码不移植**（不移植清单见 §3.0(e)），移植的是**常量与结论**。
      ⚠️ 定稿证明的是**观感**与**算法**，⛔ **从未证明 API 22 创维的填充率** ——
      该项仍全归 §十三 U 系列，且 §9.2 实测 MED/HIGH 要铺 **3.76 / 4.31 屏**（⛔ 这是**未证明**，
      不是**待处置** —— 曾挂的"越上限怎么办"已由 2026-10-09 裁决关闭，见 §十六 Q4/Q8/Q9 与 D5）。
- [x] **T1 `MoonPhase.kt`（纯函数，零 UI）** —— §4.2/4.3 的六个函数 + `of(utcMs)` 返回
      不可变 `MoonState(f, e, waxing, pa, libW, libB)`。⛔ 不碰渲染。
      ⚠️ 月龄式必用 §4.2 的修正版 `wrap360(e)/360 × 29.530588853`（旧写法与 B 表自相矛盾）。
      产出：`MoonPhaseTest`（G2）全绿，含 D 的 4 条负向自证 + **B 表逐字断言**。
      ✅ **2026-10-09 已交付**：`MoonPhase.kt` + `MoonPhaseTest`（17 例，本机
      `testDebugUnitTest --tests "*MoonPhaseTest"` **0 失败**）。A 锚点最大残差 2.68°、
      B 表 8 行逐字命中（含 10-26 的 PA 豁免）、C 表 25 组全部 ≤0.094°、D1–D6 全绿。
      ⚠️ 实现期新暴露的三处口径，已回写 §4.6（初版写法会把正确实现判红）：
      ① C 表的 `07-26 03:00 望` 是错的（距同表 `07-14 18:00 朔` 仅 11.4 日），
      本文算法的七月望为 **北京 07-29 22:30**；② D1 的判据只能覆盖 2024 年之后的锚点；
      ③ D3 的"对称"是**对 e** 而非**对时间**，且二分求交点的端点必须用**算法自身的**
      朔望过零（望比发表值早 9.2 分钟、朔早 1.8 分钟，拿发表值当端点会"不变号"）。
- [x] **T2 `MoonOpBudget.kt` + `MoonOpBudgetTest`（G4）** ✅ **2026-10-09 落地，22 例 / 0 失败**（T8a 补一条地平带方向的负向自证后为 **23 例**）—— §九 的表先落码，⛔ 先于渲染器，
      否则实现期会边写边突破预算。
      ✅ **两个前置都清了（2026-10-09）**：
      ① **Q4/Q8/Q9 已拍板** ⇒ 上限取 **`3.45 / 3.95 / HIGH 不设上限`**（§9.2 的裁决块）。
         ⛔ 三条杠杆（砍云 / 早退省屏 / 关暗角退保真）一条都不写；⛔ 观感不退。
      ② **`CLOUD_RIM` 已实测** ⇒ MED **0.037 屏 / 6~8 op**、HIGH **0.064 屏 / 12~13 op**、LOW **恒 0**。
         ⚠️ 上一版写的办法"把 `occlManual` 推到 0.4~0.6 复测"**是错的**（银边门槛是**逐斑**
         `back > 0.34`，钉标量不改几何 ⇒ RIM 那一行根本不出现在 `fillBy`）。
         正确命令是驱动新增的 **`passcost`**（真推一缕近云过盘）。⇒ 登记为偏差 **D15**。
      ⭐ **落表口径（⛔ 必须按这一组，不是满月锁定态那组）**：
      ops `74 / 159 / 229`（上限 90/200/320），fill `3.26 / 3.76 / 4.31`。
      HIGH 的门禁形态 = **单调方向**（`fill(HIGH) ≥ fill(MED) ≥ fill(LOW)`）
      **+ 棘轮**（`≤ FILL_REF_HIGH 4.306 × 1.10`，改参考值必须写理由），⛔ 不是"没有判据"。
      ⭐ **实际落码结果（表 ≥ 实测 ⇒ 条件项按上界记）**：ops **`78 / 163 / 235`**（余量 12 / 37 / 85），
      fill **`3.33904 / 3.83304 / 4.39504`**，native **`2,762,724` @1920×1080**。
      ⚠️ 本行是 **T2 当时的落码值**，水面三行已在 **T8d** 按生产几何/最坏帧更正
      （`→ 3.40204 / 3.90004 / 4.46104`，见 **D26 / D27 / D28** 与 §9.2），ops 一列未动。
      15 个元素逐项系数 + 每行 KDoc 标了**来源类型**（几何恒等式 / `passcost` 实测 / 解析式），
      ⛔ 三张表**零 Android/Compose import**（`FxLevel` 是 value class、`Color` 是 inline class ⇒
      挂进纯 JVM 单测会 `not mocked`），所以自建 `internal enum class MoonLevel { LOW, MEDIUM, HIGH }`
      一次性映射 `FxLevel.OFF/LITE/FULL`。
      ⚠️ **两处与原计划的偏差**（都按"Strengthen gates, not thresholds"处置）：
      **D17** —— MED 的 `RIPPLE` 实测 **2 op / 0.017 屏**，不是 §7.5 初判的 `≤1 / 0.001`；
      填 `≤1` 会把表写成**下界**（同一条错误在 ops 上已犯过一次，D10）。
      **D18** —— §9.3 原写"native 只在 4K 破"，落码算账发现 **1440p 就破**（STARFIELD 单项
      3,686,400 > 3,000,000）⇒ 门里写明适用区间 `≤1080p`，并放一条**破点哨兵**单测**断言它会破**
      （日后若给 STARFIELD 加降采样，这条会**变绿**，那才是修复的机器证据；⛔ 调大阈值让它继续红是作弊）。
- [x] **T3 注册链**（2026-10-09 落地）—— `AppSettings` 枚举项 `MOONLIT(R.string.visualizer_theme_moonlit,
      "明月", Tier.ADV, "44", needsParticleBudget = false)`、工厂分支
      `MoonlitRenderer(context.applicationContext)`、双语 strings（`values` + `values-en`，按字母序插在
      `molecule` 之后）、`AppSettings.kt:113` 注释 21→22；§10.1 的 12 处硬计数全部同步
      （`VisualizerThemeTest:51/57/58/89/90/96/103`、`StarrySkyTest:1039`、`FxCoverageScanTest:41-56 + :252`、
      `AGENTS.md:56`、`docs/visualizer-effects-list.md` 的 22 个 / ADV 11 套含 10 套三档全可用 /
      `covered` **15** / 「其余 21 套」/ 实现表新增 E44 行）。
      ⭐ **顺序要求已按字面执行**：先跑门 ⇒ **恰好红 7 例**（`VisualizerThemeTest` 5 例 + `StarrySkyTest` ⑥ +
      `FxCoverageScanTest`「在册渲染器全部有归属」），其中 Fx 那条的原文是
      `下列渲染器既不在覆盖名单也不在豁免名单（新加渲染器必须显式归属）：[MoonlitRenderer]`
      —— 这条红**同时**自证了扫描器真的解析到了新类（单行类头 + `: RendererFx(` 判据成立，
      不是"没扫到所以不报"），之后才把 `"MoonlitRenderer"` 加进 `covered`、阈值 `>= 21` 改 `>= 22`。
      ⭐ 同批新建 **G1** `data/model/VisualizerThemeStringsGateTest`（3 例：正向上限 + G1-N1 摘 key 必须报
      「缺中文/双语同缺报 2 条」+ G1-N2 抽取条数必须等于 `entries.size`）——本门是**全仓库级**的，
      对 22 套逐一比对，不止 E44。
      ⚠️ 新建时踩到一次**自己的空转**：抽取正则漏写 `(?m)` ⇒ `^` 只锚字符串首 ⇒ 抽出 **0 条** ⇒
      子集断言恒真。正是 N2 的「条数 == `entries.size`」把它抓出来（`expected:<22> but was:<0>`），
      ⇒ 这条负向自证**不是装饰**，别删。
      ⛔ **本步不含任何真实月相绘制**：渲染器只落 §3.2 **层 1**（天空 `#03050c→#060b18→rgb(9,15,30)`
      与海体 `rgb(10,17,29)→rgb(5,9,17)→#02040a` 两块**不相交**渐变矩形，`HORIZON_K = 0.640f` 进
      companion）+ `override val postFx = PostFx(vignette = 0.42f)`（**纯数字字面量**，§2.3 `:132-154`）。
      目的是"在列表里可选、上屏不是黑屏"（E41 黑屏教训），⚠️ 因此**现在的「明月」只有一片夜空和海面**，
      月盘 / 星野 / 云 / 光柱要等 T4–T7。[context] 字段当前**未使用**（T4 才拿它解 `assets/globe/moon.jpg`）。
      实测：`testDebugUnitTest` **1756 例 / 0 失败 / 0 错误**，`lintDebug` 无新增 error（2026-10-09，
      `logs_temp/e44_t3_full.log`）。
- [x] **T4 素材通路与圆盘烘焙（§五）** —— 解码 / UV 反解 / 增量步长 / `onExitContent` 释放 /
      §5.4 降级。此步结束时应能在**空场景**里看到"带环形山的满月盘"。
      ⚠️ 必须一次做对两处**单位**：`rgb[]` 已是 0..255（⛔ 别再乘 255，§5.2+ ①）；
      对比曲线取 §5.2+ ② 的 `0.72 + 0.55·g`（⛔ 不是 `0.45 + 0.95·g`，后者会把盘烘成白纸）。
      ⭐ 同批交付 `BLOOM` 加法过曝芯（§5.2+ ④，1 op，乘子 ≤ 1.0 是硬约束）。
      产出：G6/G7/G8/**G10** + 单测「`f=1` 时盘是全亮圆」「圆外像素 alpha = 0」「盘不过曝也不发暗」。
      ✅ **2026-10-09 已交付**：`MoonDiskBake.kt`（§五 纯计算核，零 Android 类型）+
      `MoonlitRenderer.kt`（§5.3 解码 / §5.4 降级 / §5.2 自适应增量烘焙 / 部分行 `drawBitmap` /
      `BLOOM` 走 `Brush.radialGradient` + `BlendMode.Plus`）+ `MoonDiskBakeTest.kt`（17 例，
      含 G6/G7/G8 源码扫描与 G9 降级）。实测：`testDebugUnitTest` **1776 例 / 0 失败 / 0 错误**，
      `lintDebug` 无新增 error（2026-10-09，`logs_temp/moon_t4_full.log`）。
      ⚠️ **三处规格偏离**（都是实现期实测出来的，⛔ 不是笔误）：
      ① **G10 的 ② 条被推翻并重写**（详见 §10.2 G10 行）：原判据"盘内 p95/均值 ≤ 1.25"在纹理层
         不可满足（定稿实测 1.639），且对两类历史过曝缺陷**反鉴别**（越曝越接近 1）⇒ 换成
         "顶格占比 ≤ 8% + 盘内 R 均值 ≥ 140"双判据，p95/均值降级为分布形状哨兵。
         所有者 2026-10-09 裁决「按建议来」= **加强门禁**，⛔ 不是放宽阈值把它涂绿。
         本管线自己的实测读数（`R_tex=203`、真素材、写进测试 stdout）：顶格 **5.384%**、
         盘内 R 均值 **155.5**、p95/均值 **1.639**。
      ② **喂进烘焙的 tint 不是 `tintMul`**：原型 `:976` 实际传的是 `tintMul × lumK × DISK_GAIN(1.34)`
         = `(1.3294, 1.0643, 0.6156)`。§5.2+ 的文字只写了"色温乘在烘焙期"，没写这三个系数；
         只乘 `tintMul` 时盘内 R 均值实测 134（该到 155.5），整盘发暗 —— 是**漏乘常数**，不是观感偏好。
      ③ **§5.4 程序化月面的基色**从"高地 172 + 40·噪声"下调到 **`118 + 56·噪声`**：真素材灰度
         实测 mean 130.6 / p50 138，而 172 基色过完同一条曲线后顶格占比 >8% ⇒ 降级路径自己就是一张
         白盘。§5.4 原文只约束"不许平色、不许不画"，没约束亮度量级，这条补齐。
      ④ **§5.4 的实现机制**改为"程序化生成等距圆柱源图 → 共用 §5.1/§5.2 管线"（256×128，
         `SeasideWaves.vnoise2` + 6 块月海椭圆 + 2 处射纹斑），⛔ 没有直接画一张
         `ProceduralTexture` 盘 —— 理由与实测依据见 §5.4 的「T4 落地时的机制偏离」注。
         附带收益：降级与正常两条路**只有一份**色彩/几何代码，G9 也才能用与 G10 同一套判据量。
      ⚠️ 尚未验证项（⛔ 归 §十三 U 系列，需上机）：本批只做到"编译 + 单测 + lint 全绿"，
         **没有**在 API 22 创维上看过画面；进入耗时（同步解码 15–40 ms 的预估）与每帧烘焙步长
         的实测值都还是空白（U-档判据）。
- [x] **T5 相位阴影（§4.2 末 + §4.4）** —— 半椭圆闭合 `Path` + 3 层软化 + `|sin e|` 加权旋转。
      ⚠️ 三处定稿口径：轴为 `v = R(1−2f)`；画布旋转 `-(tiltDeg + 90f)`（⛔ 少 +90 就转 90°）；
      `f ≥ 0.995` **整段跳过**（否则软带从外侧重新长回来）。
      产出：单测「`f=0.25` 时暗区面积 ∈ [0.70, 0.80] 屏盘」、「`f=0.5` 时半短轴 = 0」、
      「`e=180°` 时 `tiltDeg` 与 PA 无关」（退化保护）。
      ✅ **2026-10-09 已交付**：`MoonPhase.kt` 加几何四件（`shadowVertexPx` / `darkAreaFraction` /
      `skipPhaseShadow` / `terminatorRotDeg` + 五个定稿常量 `TERM_SOFT_K`·`SHADOW_ALPHA`·
      `SHADOW_LAYERS`·`SHADOW_SKIP_F`·`SHADOW_VERTEX_EPS`）；`MoonlitRenderer.kt` 落 **层 5c**
      （复用 `Path` + 复用 `RectF`，`save/translate/rotate` + 三层 `drawPath`，⛔ 零 `clipPath`、
      零 `Matrix`）与 **§6.5 极淡边缘**，同时接上 **§4.5 墙钟**（`wallAnchorMs` 只在
      `onEnterContent` 读一次、首帧记 `monoAnchorMs`、60 s 一档重算）⇒ **天平动不再恒 0**，
      §5.2 的 0.5° 重烘判定从此由真实历算驱动（T4 留的钩子已收）。
      新增 **G5 `MoonlitDtClockTest`**（10 例）+ **G12 `MoonPhaseShadowTest`**（14 例），
      `MoonPhaseTest` 的 D5 随度数改写。⚠️ 三条产出**全绿**：A1（`f=0.25` 并集 0.75 ∈ [0.70,0.80]）、
      A2（`f=0.5` 顶点 = 0 且并集仍为半盘）、B1（`e=180°` 时六个 PA 都给 `tiltDeg = 0`）。
      实测：`testDebugUnitTest` **1800 例 / 0 失败 / 0 错误**（T4 的 1776 + G12 的 14 + G5 的 10），
      `lintDebug` 无新增 error（2026-10-09，`logs_temp/e44_t5_full.log`）。
      ⚠️ **四处规格偏离**（登记 §十五 D11–D14）：① 画布旋转停在**度数**（原型的 `× DEG_TO_RAD`
         是 HTML API 差异，规则"取负 + 加 90°"一字未改）；② 闭合走**两次 `arcTo`**而不是
         `save/scale`（省掉 `Matrix`，且 `|v_i| ≤ R` 全程成立 ⇒ 几何上不需要裁剪）；
         ③ §6.5 的照度阈值取 **0.06**（原型 `:1098` 定稿值，§6.5 正文原写 0.03）；
         ④ 相位阴影几何落在 `MoonPhase.kt`（§4.3 签名表只列了六个历算函数）—— 与 §4.4 的
         `terminatorRot*` 同处，G12 才能**脱离图形设备**直接断言形状。
      ⚠️ **op 记账本步没做**：`TERMINATOR ≤ 3` 的账要落进 `MoonOpBudget.kt`，而 **T2 仍被
      §十六 Q4/Q8/Q9 卡住**（⛔ 不许为把它涂绿而砍云或降上限，R12）。只记一句结论：
      该项是**条件项**，满月夜为 **0**（`f ≥ 0.995` 跳过），今天这种新月夜为 **3**。
      ⛔ 尚未验证（归 §十三，需上机）：V1/V2 的暗面观感、V3 的月牙朝向、V5 的新月夜读盘。
      ⚠️ 提醒所有者：**2026-10-09 真实 `f ≈ 0.05`**（§4.6 B：10-11 `f=0.008`、10-14 `f=0.146`）
      ⇒ 这几晚上屏会看到"暗盘 + 极淡边缘"而不是月牙；**V3 朝向判读要等 `f ∈ [0.15, 0.85]`
      的夜（本轮历算下约 10-15 之后）**，⛔ 别把正确的月相当成渲染 bug 报回来（§6.5 同条）。
- [x] **T6 天空与海面本体（§3.2 层 1/2/10 + 色温 §3.3）** —— 两块矩形 + STARFIELD
      （⚠️ `ensureTiled()` **不能省**，先例 `AdvancedRenderers.kt:115-117/:951-952`）+ 暗角
      `edgeOverride` 冷蓝。⚠️ 天空/海体渐变色阶逐字照 §3.1 定稿值，
      且 §7.2/§7.3 取"所在深度的海水色"**必须走同一把尺**（⛔ 写死常数会刷出矩形亮块）。
      产出：`postFx` 数字字面量、`FxCoverageScanTest` 绿、**G11**（星野哈希位序 + 无规则格点）。
      ✅ **2026-10-09 已交付**：色尺全部收进 **`MoonSeascape.kt`**（`altitude` / `ALT_T` /
      `skyHorizon` / `SEA_SPLIT` / `sea` / `pack` 六件，⛔ 渲染器里**零**个 `0xFFRRGGBB` —— ① 号
      结构判据把它钉成门，堵的就是 §3.3+ 记录过的"抄第二份然后各自漂移"）；渲染器落 **层 1**
      （两块**不相交**竖向渐变矩形，⛔ 合并成全屏矩形）、**层 2** 星野、**层 10** 暗角
      `vignetteEdge = VIGNETTE_EDGE = Color(MoonSeascape.sea(0f, SB_NEUTRAL))`。
      新增 **`MoonSeascapeTest`**（11 例，纯色尺 + 三条负向自证）、**`MoonlitStarfieldTest`**
      （5 例，G11）、**`MoonlitSkySeaTest`**（13 例，接线扫描 —— ⚠️ 本段括号里出现的 ①/③/④/④b/⑤/⑥c
      全部是**这两个测试类自己的用例号**，⛔ 不是本文的条款号；本文的条款改用 (a)–(d)）
      = **本批 29 例**。
      ⚠️ **(a) 上屏色 ≠ §3.1 端点色**：`altT = (HORIZON_K − MOON_CY_K)/HORIZON_K = 0.6875`，
      天空地平档取的是**这条尺的 0.6875 处** ⇒ 实测 `rgb(20,23,34)`，⛔ 不是端点 `rgb(9,15,30)`
      （判据按 `ALT_T` 求值，写死端点会红在这里）。
      ⚠️ **(b) 星野裁剪没有 `clipPath`**：靠 1:1 **部分源区** blit
      （`skyH = horizonY.toInt()`、`sh = skyH.coerceAtMost(tex.height)`，`ensureFullscreenOnly`
      ⛔ 不是 `ensure()`），记账严格等于 `MoonOpItem.STARS` 的 1 op / `MOON_HORIZON_FILL = 0.640`
      = `HORIZON_K` 恒等式（⑥c 与预算表对账）。
      ⚠️ **(c) 原文"⛔ `ensureTiled()` 不能省"被推翻**（偏离 **D21**）：`RendererFx.applyPostFx`
      只在 `grain>0f` / `scanline>0f` 时读平铺槽（`RendererFx.kt:107-108`），E44 两者皆 0
      （颗粒由棘轮裁决：`4.39504 + 1.00 > 4.7366`；⚠️ T8d 落表后为 `4.46104`，结论**只会更强**）⇒ 省掉并由 ④b 一条扫描门反向钉住
      "不开 grain/scanline 且不调 `ensureTiled`"。**若日后开颗粒必须同时恢复调用。**
      ⚠️ **(d) 层 3 月晕改判归 T7**（偏离 **D22**）：`haloA = haloBase·(1 − 0.75·occl)` 的 `occl`
      是 T7 云场的输出，提前接只会再钉死一个刻意常量。
      **G11 落地口径**（原型的 murmur **位序坑**在 E44 没有生产对应物 ⇒ 偏离 **D20**，半段降级为
      "有牙夹具 + 前进钩子"）：阈值不是拍的，是 node 实测定死的（`logs_temp/g11_probe.js` /
      `g11_lcg.js`）—— 真实星位在 960×540 / 1280×720 / 1920×1080 / 3840×2160 四个画幅
      `dup = 0`、`maxCol ≤ 3`、`maxRow ≤ 4`、`distinct = n`；坑写法 `x | K` 是
      `dup 195 / maxCol = maxRow = 22 / distinct 35`；正确 `x xor K` 与真实星位同量级
      ⇒ 钉 **`dup ≤ 2 / maxCol ≤ 6 / maxRow ≤ 6 / distinct == n`**。两条负向自证缺一不可：
      坑夹具判红（③），**纯 23×10 方阵 `dup` 恒 0 且 `distinct = n`**（④）⇒ 单看重复率必漏，
      三度量必须并用；⑤ 前进钩子扫生产码：出现 `9E3779B9` 的行必须走 `xor`（剥注释后判定）。
      实测：`testDebugUnitTest` **1851 例 / 0 失败 / 0 错误 / skipped 0**、`lintDebug` 无新增
      error（2026-10-09，`logs_temp/e44_t6_full.log`，`BUILD SUCCESSFUL in 7m 17s`）。
      计数链闭合：T5 的 1800 + T2(G4) 的 22 = **1822**（v1.3 行），+ 本批 29 = **1851**。
      ⚠️ 三条本机踩坑（写进 `MoonSeascape.kt` 文件头）：`0xFF03050C` 超 `Int` 范围会被 Kotlin
      判成 **Long** ⇒ 色值常数只能是 `val …toInt()`，⛔ 不能 `const`（而 `const` 又不接受
      `.toInt()`）；负 `Int` 与正 `Long` 的 `==` **恒 false** ⇒ 测试期望值一律走 `argb(r,g,b)` 助手；
      反引号测试名禁 `..`（JUnit 报 `Name contains illegal characters`）。
      ⛔ 尚未验证（归 §十三 U/V 系列，需上机）：低档天空/海带的色带过渡是否有 banding、
      星野 `Plus` 混合在创维上的实际亮度。
- [x] **T7 云场 + `occl`（§六）** ✅ **2026-10-09 落地** —— **缕**（plume）播种、`fx.dt` 累加漂移、
      软椭圆缓存 Brush、`occlusion()` 纯函数、斑级逆光边（§6.4，⛔ 不用 `drawArc`/`softRing`）。
      ⚠️ 播种按 HIGH 数量一次生成、按档截断（⛔ 切档不重播，否则云会瞬移）。
      ⛔ 原型里的 `occlManual`/`cloudPassT` 演示钩子**没有**移植（§十六 Q7 / R13）。
      ✅ **层 3 月晕已接**（自 T6 改判，D22）：同心径向渐变 `1/2/3` 层，α 走
      `haloBase·(1 − 0.75·occl)`（§6.3 ②），层序落在**星野之后、月盘之前**，实测记账
      `0.069/0.131/0.244` 与 §9.1 吻合。
      ✅ **T6 留下的三个刻意常量已全部删掉**（`DISK_A = 1f`、`BLOOM_A = 0.36f`、
      `DARK_EDGE_A = 0.10f` ⇒ 逐帧实算），并由 G3 ⑬b 一条判据反向钉住"这类常量不得复活"。
      **这一步是需求 2 的完成点**（云存在 + 云会动），同时让需求 3 的**同源性成立**
      （`occl` 一个标量同时驱动盘面 / 晕 / 银边 / 倒影 / 粼光）。
      ⚠️ **G3 不"把 `occl` 推到 1 来判水面到下限"了** —— 那个前提（自然场到不了 1）是 D24，
      已作废；现在的口径是**分布五项对账** + 满遮态五个下限值 + 同源比恒等式（§6.3 as-built）。
      ⚠️ **落码形态与原文的两处差异**（记为偏离，见 §十五）：**D23** 云色在渲染层走
      `16 × 4 × 16` 分桶的 Shader 缓存（⛔ 逐斑现算 Shader 会在每帧 44 个斑上各分配一次），
      误差实测单通道 ≤ **18**（生产可达域）/ **22**（全立方）；**D24** `occl` 自然上限的更正。
      ✅ 播种的 **rng 调用次序**照 §3.0(f) 第 6 条保持（`lenK→thickK→tilt→wavK→wavN→
      11×(rK,sq,off,spd,aK,ph,ph2)→x→bandY→speedK→alphaK→wobPhase`），所以 ①② 能与原型逐位对上。
      ⚠️ **状态与绘制分家**（`MoonClouds.kt` ⛔ 零 Android import）是 G3 能跑纯 JVM 单测的**前提**，
      由 ⑬e 一条扫描门钉住；渲染器只调不写公式。
      实测：**1880 例 / 0 失败 / 0 错误 / skipped 0**、`lintDebug` `BUILD SUCCESSFUL`（0 error）。
      计数链闭合：T6 的 1851 + 本批 **29**（`MoonlitTest` 28 + `MoonPhaseShadowTest` 的 C4b 1）= **1880**。
      ⚠️ 三条本机/门禁踩坑：① 判据一律跑在**剥掉注释**的源码上，且**新增红线时要连带复查旧门禁的
      形态** —— T5 的 C4「`ANTI_ALIAS Paint` 恰好 2 处」被本批正当新增的 `softPaint` 判红，
      那是**数量代理**冒充**位置判据**，已升级为"只能出现在字段声明位"并配夹具自证（改的判据，⛔ 不是加的 Paint）；
      ② 分布类断言里的占比必须 `x.toDouble()/frames`，Int/Int 整除会**恒 0** 而判绿；
      ③ ⛔ 切档重播种的隐蔽形态是"尾部斑没被写"，所以 ⑧ 既比播种字段、也比**数组长度恒 11**
      （截断靠上界，⛔ 不靠重建数组）。
      ⛔ 尚未验证（归 §十三 U/V 系列，需上机）：云的**观感**（烟雾感、第七轮面积守恒在屏上的实际形状）、
      过境态在真机上的可见节奏（49~76 s）、银边只在半遮出现（V14 判读点 ③）、
      `CLOUD_RIM`/`HALO` 大面积柔边填充在 API 22 创维上的帧率（R1）。
- [x] **T8 光柱 + 粼光浪脊（§七）** ✅ **2026-10-09 落地**（分五小步：T8a 水面预算口径核对 →
      T8b `MoonWater.kt` 纯计算 → T8c 渲染器层 7 接线 → T8d 水面门禁 + 全量绿 → T8e 本文档回填）——
      glade（§7.2，⛔ **水面上不出现月盘**：镜像位图路线废弃，光柱由「亮.head + 渐隐 fog 椭圆」
      `Plus` 混合堆出）+ ROWS×分档 PER 的浪脊划（§7.3）+ `× diskA` / `× diskA²` 耦合（§6.3 同源）。
      **落码形态**：`MoonWater.kt` ⛔ **零 Android import**（G14 ⑧d 钉，这是 G4 能纯 JVM 重算的**前提**）
      —— 哈希三件套 `hash1/noise1/fbm1`、粼光 `fyOf/dyRowOf/sway/hhOf/lenOf/α` + 结构条数
      `structuralStreaks` + 无状态帧 `MoonGlitterFrame(begin/rowAt/streakAt/fillFraction)`、
      光柱 `gladeVisible/head*/fog*`、地平带 `BAND_*` + `bandDarkA/bandLitA/bandOcclBucket/…`。
      渲染器层 7 顺序 **带 → 柱 → 划，整段排在云之后**（G14 ⑧）；Brush/Shader 一律走**非 `DrawScope`**
      的缓存入口（`bandBrushOf` / `headShaderOf` / `fogShaderOf` / `glitterShaderOf`，
      ⛔ 不进任何 `draw*` 体 —— `PerfBudgetContractTest` ③ + G14 ⑧c 双钉）。
      ⚠️ **`α < MIN_ALPHA(0.004)` 早退保留**（§9.2 唯一"免费"的降填充手段），但由 G14 ⑤b 查清了
      它的**实际作用域**：光柱的存在门只在「**新月 ∧ 满遮**」这个角点闭合（`reflA(0,1)=0.002112`），
      满月在云后仍出（`reflA(1,1)=0.0352`）⇒ **云遮月不会让水面变空**，⛔ 没改任何系数。
      ⚠️ **定稿那条"不变量"被实测更正**：`hh/Δy` 下缘比 ≈1.2 **只在 MED/HIGH 成立**，
      LOW 下缘实测 **0.692**（远端比值最大是设计意图、中景永远最疏才是暗槽的来源）⇒ 处置是
      **把 ⑨ 改成钉三档三点实测**，⛔ 不改 `ROWS` / `PERSP_K=1.70` / `GLITTER_W_K`（见 **D30**）。
      ✅ **G3 已在 T7 跑通**（原文"必须在此步之后"的先后关系作废）；本步的完成点改为
      **需求 3 的"水面保真一字不退"**：三行 fill 由探针口径改成**生产几何重算**
      （`HORIZON_BAND` `0.102→0.160` **D26**、`REFLECTION` 就近取整改向上 **D28**、
      `GLITTER` 均值改最坏帧 **D28**）⇒ 合计 **3.33904/3.83304/4.39504 → 3.40204/3.90004/4.46104**，
      三档余量 `0.048 / 0.050 / 棘轮 0.276`，**ops 一字未动**（78/163/235）。
      门禁：**新增 G14 `MoonlitWaterTest` 18 例**（§10.2）+ G4 补 1 例（`地平带按探针口径落表 ⇒
      低于真实矩形面积`，22→**23**）+ G13 ②c 的 `Brush.verticalGradient(` 处数 2→**3**（合法升级）。
      实测：**1899 例 / 0 失败 / 0 错误 / skipped 0**、`lintDebug` `BUILD SUCCESSFUL`（6m56s，
      `logs_temp/t8d_full.log`）。计数链闭合：T7 的 1880 + 本批 **19**（G14 18 + G4 1）= **1899**。
      ⚠️ **T8e 只动文档与注释**：代码侧改的是 `MoonOpBudget.kt` 里**注释中的算数**
      （余量 `0.049/0.052/0.43` → `0.048/0.050/0.276`、增量 `+0.001/0.002/0.003` →
      `+0.0011/+0.0013/+0.0025`、D27 那句拆账的出处与口径）、`MoonWater.kt:62` 那句桶误差口径
      （⛔ 1001 点扫描值 `0.0015375` 不能当解析上界 `0.0015625` 用），⛔ 零语义变更。
      仍**复跑一次以证明编译与判据没被注释改动带偏**：`MoonlitWaterTest` 18 +
      `MoonOpBudgetTest` 23 + `MoonlitSkySeaTest` 13 = **54 例 / 0 失败**
      （`logs_temp/t8e_comment_check.log`，1m40s）；全量 **1899** 沿用 T8d 那一轮
      （`logs_temp/t8d_full.log`，本轮再跑一次全量的代价不值一次注释改动）。
      ⛔ 尚未验证（归 §十三 U/V 系列，需上机）：粼光在创维上的**实际可见度**（原型是按 1080p
      显示器判的）、`fog` 层数 1/2/3 递减的观感、**4.36 屏**真实铺屏下的帧率（R1，
      ⛔ 不是探针账本的 4.306。⚠️ **T9 已把该估计更正为 ≈4.48**，连同 LOW/MED 的 `3.33/3.88`，
      依据与算式见 §9.2「判定」行那句"给 T10 的一句"），`density ≥ 2.0` 设备上 `1.2·density` 地板触发后的观感（D29）。
- [x] **T9 音频映射（§八）** —— `AudioSmoother.updateDt` 接入（⚠️ 它是**首个**生产调用方）、
      克制幅度、`RIPPLE` 按档（⛔ 用 `softRing` 不用描边椭圆，§7.5）。
      ⚠️ `mid → 云速` 的乘子必须作用在 `dtSec` 上（⛔ 不能乘绝对时间，否则切档/暂停恢复会"抽搐"，
      第四轮实测根因）。
      ✅ **2026-10-10 已交付（T9a 接线 + T9d 门禁 + T9e 回填）**：§八 那七把尺全部落在
      `renderers/MoonAudio.kt`（纯函数、零 Android import），渲染器侧四个 `AudioSmoother`
      （`bass/mid/treble` 用默认 `(0.35, 0.06)`，`energy` 用 `(0.28, 0.05)`）**每帧各推进一次**；
      门禁 **G15 `MoonlitAudioTest` 20 例**（§10.2）。四处偏离 **D31–D34** 全部登记，其中
      **D34**（HALO 按 `k²`、RIPPLE 改生产几何最坏帧）让三档合计涨 `+0.006/+0.066/+0.115`
      ⇒ MED 越过 `3.95` ⇒ **所有者二次裁决「抬 MED 上限到 4.00」**（见 **D5**，⛔ 不是把两行的数改小）。
      ⭐ 本批改动的**观感闸门**是 G15 ⑯b：七把尺一律写成 `(a − NEUTRAL)` 偏移形态 ⇒ 无声帧
      乘子恒为 1 ⇒ **逐像素等于 T8 行为**（四个中性钉 `SB_NEUTRAL`/`SPD_MUL_NEUTRAL`/
      `TREB_NEUTRAL`/`RIM_BEAT_NEUTRAL` 逐一复现），所以"接了音频"这一步在静态帧上不可见是**设计**。
      验证：全量 `testDebugUnitTest` + `lintDebug` **1919 例 / 0 失败 / 0 错误**
      （链：T8d 的 **1899** + G15 的 **20** = **1919**），`BUILD SUCCESSFUL in 4m 30s`
      （`logs_temp/t9d_full.log`；计数由结果 XML 汇总，见 `logs_temp/t9e_count.txt`
      —— ⛔ 不读控制台中文，GBK 控制台会把 CJK 打成乱码或直接抛 `UnicodeEncodeError`）。
      T9e 的七处注释改动后又跑了一轮：`assembleDebug + lintDebug` 全绿，但 `compileDebugKotlin`
      重编出的 `.class` 逐位不变 ⇒ `testDebugUnitTest` 判 `UP-TO-DATE`（**继承值不算实测**），
      故另用 `--rerun` 强制跑了一次 ⇒ **1919 例 / 0 失败 / 0 错误**（`logs_temp/t9e_tests_rerun.log`，1m 6s）。
      ⛔ 尚未验证（归 §十三 V 系列，需上机）：六个幅度在**真实音乐**下的可读性（±6% 晕半径、
      ±10% 海面亮度、+25% 粼光 α、`0.85~1.25` 云速、×1.25 逆光云边、`0.42→0.36` 暗角）、
      以及律动**手感**（G5 ③ 钉的是"乘子只作用在增量上"这个**形态**，"跟不跟得上拍"是另一件事）。
- [ ] **T10 真机验收（§十三）** —— 所有者上机逐条判读；⛔ **未过 V 系列不得进 `CHANGELOG`**。
- [ ] **T11 文档回填** —— ⚠️ **2026-10-10 半完成**，剩的一条按所有者指示**等真机测完再写**。
      ✅ 已落：`visualizer-effects-list.md` 的 E44 行（改成 T0–T9 全量 as-built + 类头行号 `:104`）、
      本文 §十五 偏差表（**D1–D34**）、版本号 **v2.38.6 / versionCode 175**、`CHANGELOG` v2.38.6 五条（`69b7ed7`）。
      ⛔ 未落：`technical-overview.md` 那一条（所有者：「这个等我测试完后再写」）⇒ 草稿暂存
      `logs_temp/v2386_10224_draft.md`（⚠️ 该目录不入库，上机后须据此回填并**取当时候空闲编号**，
      现为 **§10.224**，⛔ 不是初稿预期的 §10.220 —— §10.220–10.223 已被 E29 真机回访与焦点/飞牛三条占用）。

---

## 十二、风险

| # | 风险 | 缓解 | 判定 |
|---|---|---|---|
| R1 | **创维 API 22 的 HWUI 填充率**：本效果每帧铺满 **3.26~4.31 屏**（§9.2 **半遮态**实测，⚠️ 不是自然态的 3.22~4.21 —— Q7③ 落地后半遮态是**常规帧**），且 halo/云是大面积柔边填充 | LOW 档 halo 1 层、云 8+14 斑、粼光 43 划（⛔ 这些是**记账值不是安全值**）；`FxLevel` 三档砍的是**数量**不是分辨率 | U1（实测 fps） |
| R2 | **进入黑屏**：源图解码 + 圆盘烘焙集中在 `onEnter`/首帧 | 解码 ≤ 2 MiB 单张；烘焙**分帧**；未烘完按已烘行绘制（不闪白） | U2（切换耗时） |
| R3 | **`STARFIELD` 单项在 1440p 就突破 native 堆**（⚠️ 本文初判写作"4K 必破"，落码算账后提前一档，偏差 **D18**） | §9.3 的按比例钳制（`texRadius` 两道钳 192/512）+ G8 镜像门禁 + 门里写明适用区间 ≤1080p、并放一条**破点哨兵**单测 | U3（1440p/4K 机或 `dumpsys meminfo`） |
| R4 | **天平动重烘抖动** | 0.5° 量化 + 走同一增量步长；⛔ 同步整盘重烘 | G4 |
| R5 | **电视系统时钟错** ⇒ "月相不对"的误报 | §4.5 明写；V1 要求先核对电视日期 | V1 |
| R6 | **PA 在朔望快转**（§4.4 实测一夜 112°） | `\|sin e\|` 加权 + 朝向判读限定 `f ∈ [0.15,0.85]` | V3 |
| R7 | **天空遮月、水面不遮**（需求 3 的反面） | `occl` 单一标量 + G3 的"同源"断言 | G3 |
| R8 | 云看起来像"灰色椭圆"（⚠️ **第三轮真实发生过**） | 定稿的解法是**换模型**不是调参：烟缕（蛇形脊线上排拉长软斑）+ ⭐ **逆光剪影双色**（`CLOUD_SIL` 暗体 / `CLOUD_LIT` 亮边，§6.4）+ 斑级软椭圆银边。⛔ `drawArc` 银边会画出"月盘下挂亮括号" | V6 |
| R9 | 水面读成**两种相反的失败态**：连成一根光柱 ⛔，或散成一列**孤零零的横条** ⛔ —— 定稿两头都实测踩过 | ROWS × **深度分档 `per`**（远 1 / 中 2 / 近 PER）+ `jitRow` 行抖动 + 每行独立 `seed` 哈希 + `env` 随 `fy` 展宽（§7.3 的 9 条实测结论）。⚠️ **`hh/Δy` 比是结构不变量**，改 `ROWS`/`k`/`GLITTER_W_K` 必须同步重算 | V7 + `moonlit_water_zoom.py` 对照 |
| R10 | 跨效果复制 E43 的**保真退化错误**（二值门、平均 alpha） | §6.2 的 ⛔ 条款 + G3 的单调性断言 | G3 |
| R11 | 预算表被当成帧率保证 | §头部 ⛔ 条款（0.45 ms/op 已证伪）+ 本文一律以实测为准 | U1 |
| R12 | ~~**⚠️ §9.2 实测 MED +0.06 / HIGH +0.71 越上限**，而唯一有弹性的云（HIGH 0.583）**不足以填 HIGH 的缺口**~~ ⛔ **本行作废（2026-10-09）**：所有者裁"上限调整合适、HIGH 不设上限"⇒ 不存在需要"涂绿"的缺口 | ⛔ **禁令本身继续有效且更强**：既然缺口已由裁决定掉，实现期就更没有理由砍云 / 关暗角 / 退水面保真 —— 三条杠杆**一条都不许写**。真机帧率不达标时的处置是**降铺屏并留痕**，⛔ 不是提前预支 | G4（上限 3.45/3.95/无）+ T10 真机 |
| R13 | **定稿参数全部在"满月锁定态"（`phaseLock:'full'`）下调出**，⛔ 非满月观感从未判读过（§4.4 末） | V1/V2 用**真实 `f`** 复判；⛔ `phaseLock` 不移植。⚠️ 已知风险点：`haloBase`/`reflBase`/`glitBase` 三个都含 `clamp(f·k, …)`，满月时取的是**上界** ⇒ 残月夜水面会比定稿截图**明显更暗** | V1 / V2 |
| R14 | ~~**自然云场的 `occl` 实测上限 ≈ 0.33** ⇒ "云吞月"可能长时间不出现~~ ⛔ **两层都已作废**：(a) §6.6 消解（Q7 拍板选 ③，已落地）；(b) ⚠️ 那个 0.33 **本身就是错的**（**D24**：自然态实测 `peak = 1`、触顶占 1.71%、过半遮占 26%）⇒ 本条**不再是"观感缺陷的风险"**，只剩"节奏太疏或太密"的判读风险 | **确定性过境**：近层一缕的 `bandY` 钉成常量 `MOON_CY_K / HORIZON_K` ⇒ 每圈（49~76 s）必然过一次盘，零新增 op/填充。⛔ 仍不为此加"强制瞬移/随机事件表"（§6.6 的换圈判定条款）；⛔ `occlManual` / `cloudPassT` 两个演示开关不移植（R13）。**若真机嫌"每分钟都遮一次太密"** ⇒ 走 §6.6 的 `TRANSIT_EVERY_CYCLES`（换圈判定，T7 **未实现**，等 V14 判读结论） | G3（正确性）+ **V14**（观感兑现） |

---

## 十三、真机验收判据

> 基线设备：创维 Android 5.1.1 / **API 22**（与 E42/E43 同一台）。
> 装机由**所有者**执行（`docs/archive/visualizer-texture-upgrade-plan.md` §11.3.2：
> ⛔ Agent 不得自行安装/启动），帧耗时的采集三条口径同见该文件 §11.3.3
> （同设备以电视为准 / 同画质档 / 同曲同进度）；fps 读电视开发者选项的
> "GPU 渲染模式报告/帧率"或 `VisualizerStage.kt:183` 已经在读的 `Settings.Global` FPS 开关。
> ⚠️ **截图只进 `output/`**（该文件 §11.3.4 + AGENTS.md 仓库卫生），⛔ 不落仓库根。

**通用（U）**

- [ ] **U1** 三档各稳态 ≥ 25 fps（LOW）/ ≥ 20 fps（MED）/ ≥ 15 fps（HIGH），
      连续 60 s 无下滑；**实测数字写回 §十五**。
- [ ] **U2** 进入本效果的**首帧延迟 ≤ 500 ms**，期间 ⛔ 无纯黑帧、无白色圆盘闪现。
- [ ] **U3** `DUMPsys meminfo` 的 native 堆增量 ≤ 8 MB，且**切走 30 s 后回落**。
- [ ] **U4** 切到别的特效再切回：观感一致（无状态残留）、⛔ 不崩、内存不累积。
- [ ] **U5** 暂停播放：云停、粼光停、月不动（`fx.dt = 0` ⇒ 全冻结），恢复无跳变。
- [ ] **U6** 手机（触屏、任意方向）与电视同帧观感一致；竖屏不被月盘顶出画面。
- [ ] **U7** LOW 档在 API 22 **无原生崩溃**（碎矩形 + 无全屏绘制的组合是已知 SIGSEGV 触发条件；
      本效果的 `DAMAGE_COALESCER` 与两块全屏基矩形应把它压住 —— ⚠️ 这正是必须实测的原因）。

**逐效果观感（V）**

- [ ] **V1** 先在电视「设置→日期与时间」核对系统日期无误，再对照手机日历当天照度：
      本效果的明暗比例**肉眼一致**（容差 ±5%）。⚠️ 起草日 2026-10-08 的真实值 = **残月 5.1%**，
      应当看到一弯极细的暗天边缘月，⛔ 不是半月或满月。
      ⚠️ **本条是定稿的最大盲区**：原型七轮全部在 `phaseLock:'full'` 下调参（§4.4 末），
      残月观感**从未被任何人看过**。⛔ 若 V1 判"太暗/看不见"，那是**保真缺失**（`f` 项系数），
      不是参数漂移 —— 修法见 R13，别去动 §3.3 的色温。
- [ ] **V2** 手动把设备日期改到本文 §4.6 C 表的**任一满月日**（如 2026-10-26）：应看到**整盘**；
      改到**朔日**（2026-10-11）：应看到**几乎全黑盘 + 极淡边缘**（§6.5）。
      ⚠️ 判读时用 UTC/北京区分（C 表是北京时刻）。
      ⭐ 满月日同时是 **V4 的过曝检查**日（定稿截图态 = `phaseLock:'full'`，可直接对表）。
- [ ] **V3** 在 `f ∈ [0.15,0.85]` 的任一夜：月牙朝向**整晚稳定**，且与 V1 的盈/亏一致
      （盈月月牙朝西、亏月朝东 —— 由所有者按现场朝向判读，⚠️ 若左右反了，根因在 §4.4 的
      屏幕坐标系约定，修法是取反 `pa` 而不是调参）。
      ⚠️ 另一个必查项：`canvasRot` 的 **+90 偏移**（§4.4）。少了它月牙会**横躺**，
      这个错在原型上出现过，是"朝向"类观感问题里最难靠调参发现的一种。
- [ ] **V4** 盘面上能分辨**月海**（暗斑）与**至少 3 个明亮环形山**，且不糊成一片。
      ⛔ **反向判据同样重要：盘面不能是"白纸/白饼"**。定稿为此定了两条量化口径
      （§5.2+ ②：参考盘是**平**的，峰值/均值 ≈ 1.21；旧曲线在 `GAIN ≥ 1.5` 会到 1.49 ⇒ 白盘），
      上机若"亮是亮了但没细节"，先量 `盘内 p95 / 盘内均值` 再谈加亮。
- [ ] **V5** 新月夜：画面**仍然可读**（海平线、星野、云都在），⛔ 不是"坏掉了的黑屏"。
      ⚠️ 已有前车之鉴：E41 电视端黑屏（`technical-overview.md` §10.201）与 E42 首帧黑屏（§10.207）。
      本效果的等价风险是 `diskA` 与 `f` **相乘**导致 5% 照度的盘看不见（不变式 2，§3.0(c)）
      —— 单测抓不到，只能上机判。
- [ ] **V6** 云：看得出是**缓慢横移的烟缕**（⛔ 不是"灰色椭圆团"、⛔ 不是横条），
      有前后两层速度差（远 143 s / 近 61 s 一圈，§6.1），
      且**上下摆幅很小**（`DRIFT_Y` 近层 0.045 / 远层 0.031 —— 第七轮就是被"摆幅太大"打回的）；
      云过月时**云体本身是暗的（剪影）、只有朝向月的一侧发亮**（§6.4 的双色模型）。
- [ ] **V7** 水面 ⭐（定稿口径，⚠️ **与 v1.0 相反**）：月正下方是一条**连续的金光带（glade）**，
      带子上**骑着断开的浪脊划**；⛔ 水面上**看不见月盘的像**（出现盘形 = §7.2 的路线又回来了，失败）；
      带子**在最贴地平线处最亮最窄**，越靠画面底越宽越碎。
      判读方式：与定稿截图并排对照（重新生成：`node docs/archive/verification/scripts/moonlit_visual_driver.mjs snap MED 480 final`），
      结构看不清就用 `python -X utf8 docs/archive/verification/scripts/moonlit_water_zoom.py <电视截图> tv`
      做水面放大对照。⚠️ **参考图本身不入库**（带视觉中国水印，只在 gitignore 的
      `output/moonlit_frames/ref_input_gold.png`），所以 `moonlit_ref_match_check.py`
      在干净仓库上会打印 `SKIP` —— 那是预期行为，⛔ 不是脚本坏了。
      定稿基线数字（同脚本，⛔ 只在与参考图同在时可复量）：
      `R/H 0.149`、盘内均值 `[199,169,99]`、盘 p95 `206.3`、水面 2% 档 `[239,209,157]`、
      水面 20% 档 `[195,170,129]`、点亮宽度/R `[0.30,0.62,3.12,2.41,3.08,3.08]`。
      ⚠️ **T8 as-built 给判读的两条**：① 层 7 的上屏顺序是 **地平带 → 光柱 → 粼光**（整段排在云
      之后，G14 ⑧ 钉死），所以"骑在带子上"是**叠在雾光之上**的划，不是先画划再蒙雾；
      ② **LOW 档的下缘可能比 MED/HIGH 疏**（`hh/Δy` 实测 LOW `0.692` / MED `1.364` / HIGH `1.791`，
      **D30**）⇒ 若真机在 LOW 判出"下 1/3 读作梯子"或"黑底"，那是**这条实测差异的兑现**，
      处置路径是重开 `ROWS`/`PERSP_K` 并**重跑 G14 ③⑨**（⛔ 不是把 ⑨ 的九点容差放松）。
- [ ] **V8** ⭐ **云遮月一致性**：云压住月时，**光柱与浪脊划同帧变暗**（`reflA`/`glitA` 都 `× diskA`）；
      云完全遮住时水面**只剩微弱的漫射亮带**。⛔ 出现"天上没月、水里一条金路"即**失败**。
      ⚠️ **定稿那句"真机上可能很难触发（自然上限 ≈0.33）"已作废**（**D24**）：自然态实测
      `occl > 0.5` 占 **26%**、`≥ 0.999` 占 **1.7%**，加上 §6.6 的确定性过境（触顶占比 5.8×）
      ⇒ "完全遮住"在真机上是**会规律出现**的，判读时**等一次过境**即可（V14 给时刻算法），
      ⛔ 不要为了看到它去改云量或 `thickK`（那会同时改 §9.2 的填充）。
      正确性由 G3 保证（满遮态五个下限值 + 同源比恒等式，⛔ 不再是"把自然到不了的 `occl` 推到 1"）。
      ✅ **T8 把"只剩微弱的漫射亮带"落成了可核对的数**（G14 ⑤b）：满遮帧 `glitA(1,1,0) = 0.003968`
      **落在 `MIN_ALPHA = 0.004` 之下** ⇒ 粼光走 `softEllipse` 入口**一条也不提交**，而光柱仍有
      `reflA(1,1) = 0.0352` ⇒ 判读时看到的应当是**"粼熄柱在"**；只有**新月 ∧ 满遮**才整段光柱也不出
      （`reflA(0,1) = 0.002112`）。⛔ 若真机在满月夜被云全遮时水面**全黑**，那是接线错（`occl`
      没同帧传给水面，G14 ⑧b 那条同源判据在单测里已挡，上机若复现要查渲染器而不是查系数）。
- [ ] **V9** 月被云半遮时：晕**整体变暗变柔**（`haloA = haloBase·(1−0.75·occl)`，§6.3），
      同时**云朝向盘的一侧被点亮**（§6.4 银边），⛔ 不是硬边、⛔ 不是"月盘下挂一个亮括号"
      （那是 `drawArc`/`softRing` 的产物，定稿已禁）。
      ⚠️ 与 V8 同因、同作废（**D24**）：半遮态不是"难得一遇"而是**常见**（自然态 `occl > 0.5`
      占 26% 的帧），判读时不要为了看到它去改云量（那会同时改 §9.2 的填充）。
- [ ] **V10** 音频：安静段落与高潮段落的差异**能察觉但不刺眼**；
      ⛔ 无随鼓点"闪烁"的元素；关掉音频反应（若走全局开关）时画面仍完整。
      ⚠️ 本效果唯一的**音频驱动几何**是 `bass → 光柱摆动`（§八，1080p 下约 4 px），
      判读点是"看得出海在呼吸，但光柱不会跟着鼓点跳"。
- [ ] **V11** 三档切换（HIGH↔LOW）无跳变、无残影、⛔ 不触发重新黑屏。
      ⭐ **定稿新增的具体口径**：云是按 HIGH 数量一次播种、按档截断（§6.1）⇒
      切档后**已在场的云必须停在原地**，⛔ 不能整场重排位置（重播会瞬移，一眼可见）。
- [ ] **V12** 连续 10 分钟播放无卡顿累积、内存曲线平稳、⛔ 无周期性"抖一下"（烘焙重烘的痕迹）。
- [ ] **V13** ⭐ **定稿基线对照**：与原型定稿态并排看**构图**（月盘相对画面与地平线的比例）。
      重生成命令：`node docs/archive/verification/scripts/moonlit_visual_driver.mjs snap MED 480 final`。
      已知偏差：候选 `R/H = 0.149` vs 参考图 `0.227`（盘偏小）—— ✅ **Q2 已拍板：单档 `MOON_R_K = 0.150`，
      该偏差是**有意的**（要大月须另一次裁决：重出 §9.2 整表 + 重跑客观门）**⇒
      ⛔ 不要在上机判读时顺手改（它同时动 `BLOOM`/`HALO`/`REFLECTION` 三项填充）。
- [ ] **V14** ⭐ **确定性过境兑现**（§6.6 / Q7③ 的判读，⛔ 只看这一幕是否发生过）：
      按 `1/speedK` 数出下一次过境的**时刻**（49~76 s 一圈，`TRANSIT_EVERY_CYCLES` 再乘倍数），
      到点判读三件事：① `occl` 真的**爬升再回落**（曲线，不是台阶）；
      ② 盘面变暗与水面变暗**同帧**（G3 同源性的真机版 —— 不同帧就是 `occl` 分了家）；
      ③ 银边只在**半遮**出现（`occl → 1` 时 `rimA → 0` 是**正确**行为，⛔ 不是 bug，见 §6.4）。
      ⚠️ 若这一幕"太频繁"，处置是调 `TRANSIT_EVERY_CYCLES`（单常量，且只能在换圈点切），
      ⛔ 不是删掉过境（那等于把 Q7 的裁决推翻）。

---

## 十四、明确不做

- ⛔ 月出/月落时刻与地平高度（需要观测点经纬度 + 地平坐标系全套，且会引出"该不该显示月"）；
- ⛔ 月亮错觉（近地平视觉变大）、大气折射、真实大气消光公式（§3.3 用的是**观感映射**）；
- ⛔ 月球方位角随时间的旋转、天球北与月面北的位置角（§4.4 已把盘面固定为天球北向上，
  偏差登记 D1/D2）；
- ⛔ 地球反照照月（"新月抱旧月"的暗盘细节只在 §6.5 用一条极淡边缘示意）；
- ⛔ 星座、银河、行星、卫星轨迹；
- ⛔ 频谱柱/环等"音乐可视化直译"元素；
- ⛔ 离屏层 bloom / `OffscreenFx`（阶段 6 接线未完成）。⚠️ **别和定稿新增的 `BLOOM` 混淆**：
  §5.2+ ④ 的"过曝芯"是**同一个 `softEllipse` 图元 + `BlendMode.Plus`**，直接在主画布上加法混合，
  ⛔ 不建离屏层、不引第二个渲染目标（成本 = 0.024 屏，§9.2）；
- ⛔ 水面上月盘的**镜像位图**（定稿推翻：真实夜景水面读作**光柱 glade**，§7.2）；
- ⛔ `clipPath`、`BitmapShader`、AGSL、`RenderEffect`、GL（红线）；
- ⛔ 新的 `ProceduralTexture.Id`、任何新增 asset 文件；
- ⛔ 用户设置项（月相/云量/构图都可配置会摧毁可判定性；若日后要"云量"开关，
  走 §十六 Q3 而非直接加 UI）；
- ⛔ 原型的三个**演示钩子**：`S.occlManual`（强制云遮月）、`S.cloudPassT`（让一朵云定时过月）、
  `S.phaseLock`（锁定满月）—— 它们只为七轮判读存在，⛔ 不进 Kotlin、不借道成设置项
  （`phaseLock` 的后果见 R13，`occlManual` 的后果见 Q7）。

---

## 十五、偏差记录（实现期回填，⛔ 每条要写"规格值 → 实际值 + 理由"）

| # | 规格 | 实际 | 理由 |
|---|---|---|---|
| D1 | 亮 limb 朝向应含**月面北的位置角** | 盘面固定北朝上，仅阴影按 `pa` 旋转 | 需要月面自转轴的位置角级数（含交点退行项），本文未实现且未验证；`pa` 的误差上界 ≈ ±10°，只影响月牙倾斜度，不影响照度 |
| D2 | 真实夜空的朝向含**视差角**（地平系 vs 赤道系） | 屏幕上方 = 天球北（不含观测点纬度与恒星时） | 无观测点来源；且需求 1 说的是"月相"而非"月位" |
| D3 | 色温应来自大气质量公式 `1/cos z` | 线性映射画面高度（§3.3） | `z` 在近地平发散；本效果无高度角 |
| D4 | 阴影软化应用连续遮罩 | 3 条嵌套闭合 `Path`（§九 `TERMINATOR ≤ 3`，满月整段跳过 ⇒ 实测 0） | 无离屏 mask 通路；⛔ `clipPath` 是红线 |
| D5 | `OVERDRAW_MAX_*` 应沿用 E43 的 2.0/3.89/3.96 | **3.45 / 4.00 / HIGH 不设上限**（⭐ 2026-10-09 裁决值 MED 为 `3.95`，2026-10-10 T9 复裁为 `4.00`） | 构图本身铺满全屏（§9.2 的算术）；照抄会让实现寸步难行。✅ **已由所有者签字**（「将上限值调整合适，保证 HIGH 能体现所有效果，实际上 HIGH 不应该有上限」）⇒ 旧的 3.30/3.60/3.50 作废，`MAX_HIGH < MAX_MED` 的符号错连同它的上游（拿 v1.0 **设计值** + 0.2 当上限输入）一起由该裁决终结，见 **Q4/Q8/Q9**。⚠️ **2026-10-10 二次裁决**：T9 把 `HALO`（×`k²`）与 `RIPPLE`（生产几何最坏帧）两行按 **D34** 重算后，MED 合计从 `3.90004` 涨到 `3.96604` ⇒ **越过**原 `3.95`。所有者裁「**抬 MED 上限到 4.00**」（⚠️ 该题一共四个候选，被否决的三个 —— 晕半径只降不升 / 抬涟漪早退门槛 / 涟漪减到 1 环 —— 逐条留痕在 **§十六** 的"二次裁决"块），明确 ⛔ 不把两行的数改小（表是**上界**，改小就是假账 —— 与 **D28** 同一条错法的反向）、⛔ 不动 LOW 的 `3.45` 与 HIGH 的"不设绝对上限"。机器留痕在 **G15 ⑮**：它额外断言 `med > 3.95f` ⇒ 这次"抬上限"是**有后果**的裁决，不是"表本来就够小"的假绿；同一例又断言 `OVERDRAW_MAX_LOW == 3.45f` 与 `overdrawMax(HIGH).isInfinite()`，把另外两端的"未动"也钉住 |
| D6 | 朔望级数（Meeus 缩短级数） | 日月黄经差（§4.1） | 前者对 2026 年四个已发表天象实测误差 0.3–1.0 天，后者 ≤ 0.22 天（6/6 锚点命中） |
| D11 | §4.4 末写作 `rotRad = -(tilt+90) × DEG_TO_RAD` | `MoonPhase.terminatorRotDeg(tilt) = -(tilt + 90f)`，**停在度数** | 那个 `× DEG_TO_RAD` 是**原型 API 的差异**（HTML `ctx.rotate` 收弧度），`android.graphics.Canvas.rotate` 收度数 ⇒ 规则"取负 + 加 90°"一字未改。⛔ 别为"和文档一样"乘回 π/180（90° 变 1.57°，月牙看着几乎不倾斜）；G12 的 B2 有专门一条量级自证挡它。先例：文档写作时的 `terminatorRotRad` 已在 T5 删除（零调用点） |
| D12 | §4.2 末的绘制写法 = `canvas.save / scale(v, R) / draw / restore`（先例 `VintageTvRenderer.kt:455`） | **两次 `arcTo`**：`arcTo(circle, −90°, −180°)` 画暗侧半圆 + `arcTo(ellipse, 90°, ±180°)` 折回；`\|v\| < 0.5 px` 改 `lineTo` 直线弦 | ① 缩放写法每层要一对 `save/scale/restore`，`arcTo` 直接在**复用 `RectF`** 上给半径 ⇒ ⛔ 不需要 `Matrix`；② `forceMoveTo` 必须 `false`（`true` 断开轮廓，填充区就不是暗区）；③ 附带证明了一条更强的性质：**`\|v_i\| ≤ R` 在会被绘制的全程成立**（G12 的 A7 逐 0.005 扫 `f ∈ [0, 0.995)`）⇒ 暗区**天生落在盘内**，`clipPath` 红线在这里不是"绕开"而是**几何上不需要** —— 与 §5.2 圆盘靠"圆外 alpha=0"免裁剪同思路 |
| D13 | §6.5 正文写"照度 `f < 0.03` 时画极淡边缘" | `DARK_EDGE_MAX_F = 0.06f`（原型 `moonlit-preview.html:1098` 原值） | 定稿以原型实测为准，§6.5 的 0.03 是回填时的笔误级偏离（该阈值决定"新月夜还有没有轮廓"，⛔ 取 0.03 会让盘在朔前后约 ±0.35 天内**完全消失**，而 §十三 V5 要求判读"暗盘 + 极淡边缘"）。颜色/线宽照 `:1099`：`moonHsl × 0.9`、`α = 0.10·(1−occl)`、`max(1, minDim·0.0012)` |
| D14 | §4.3 的 `MoonPhase` 签名表只列六个历算函数 | 相位阴影的**纯几何**（`shadowVertexPx` / `darkAreaFraction` / `skipPhaseShadow` / `terminatorRotDeg` + 五个定稿常量）也放进 `MoonPhase.kt`；§6.5 的极淡边缘**提前到 T5** | ① 这些量与 §4.4 的 `tilt → 旋转` 换算是同一套（文件头已声明"除历算之外还带几何"），⛔ 不进 `MoonlitRenderer` 才能让 G12 **脱离图形设备**判形状（`MoonDiskBake` 的先例）；② 极淡边缘提前是因为 V5 的判读对象就是"`f` 极小时画面读什么"，留着 T7 就没法单独验收新月夜。⚠️ 它现在用的是 `occl = 0` 的钉死 α（`DARK_EDGE_A = 0.10f`），与 `DISK_A`/`BLOOM_A` 同批在 T7 换成实算 |
| D15 | §9.2 / §十一 T2 写的 RIM 补测办法：「`probe` 时把 `occlManual` 推到 0.4~0.6」 | 该办法**测不到 RIM**；正确办法是驱动新增的 **`passcost`**（真推一缕近云过盘） | 2026-10-09 实测照抄了自己的建议 ⇒ 钉 `occl = 0.5` 后 `rimA` 确实到满值 0.14，但 `fillBy` 里**仍然没有 RIM 行**：银边的门槛是**逐斑** `back > 0.34`（原型 `:1141`），钉住标量 `occl` 不改动任何几何 ⇒ `back` 恒≈0。⇒ ①判据要跑在**产生该量的几何**上，不能跑在它的手工替身；②已把 `occlManual` 路线在驱动文件头标注为"测不到 RIM"，防止下一个人再踩 |
| D16 | Q7③ 的"每 ~90 s 过境一次"按**新增调度器**实现 | 只把近层一缕的 `bandY` 由播种随机值改为常量 `MOON_CY_K / HORIZON_K = 0.3125`（§6.6），周期**自然** = `1/speedK` ≈ **49~76 s** | "确定性过境"不需要新机制：近层缕本来就会绕屏一圈，错过月盘**只是因为 `cy` 不在月的高度**。⇒ 零新增 op、零新增填充、零新常量表。嫌太密时的 `TRANSIT_EVERY_CYCLES` 闸门也必须走**换圈判定**（屏外切 `cy`），⛔ 不许瞬移 |
| D17 | §7.5 / §9.1 初判 `RIPPLE` 的 MEDIUM = **`≤1` 环 / 0.001 屏** | **2 环 / 0.017 屏**（HIGH `4 / 0.049`、LOW `0 / 0` 不变） | 2026-10-09 `passcost` 半遮态实测同帧在场 2 环。⚠️ 填 `≤1` 会把表写成**下界**而非上界 —— 与 **D10**（预算表按设计值记）是同一条错误的第二次犯：条件项的档位数字**只能实测**，"节拍驱动的稀疏事件"直觉上总像"一次一个"，而 2 秒节拍 × 环寿命 ~3.4 s 的占空比本来就 >1。⇒ 已同步改 §9.1 第 13 行、§9.2 `RIPPLE` 行、`MoonOpItem.RIPPLE` 的 `opsMed/fillMed` |
| D18 | §9.3 写作"native 堆余量 4.9%，**4K 必然突破**" | **1440p 就破**（2,560×1,440 合计 4,502,288，其中 `STARFIELD` 单项 3,686,400 已 > 3,000,000）；1080p 的实际合计是 **2,762,724**（圆盘被 `texRadius` 钳到 406²，非原表写的 512²），相对门槛余量 **7.9%**（原表那个"4.9%"是拿 139,968 除**合计**而非除 3 M，口径也不同） | 落码时逐分辨率算账才发现：原表按"圆盘 512² + STARFIELD 1080p"的**典型值**写了一行合计，然后把破点外推成"4K"。⛔ 破点不由圆盘决定（它被 `TEX_R_MAX=512` 钳住了），而由**唯一随分辨率平方增长**的 `w·h` 项决定。处置按"Strengthen gates, not thresholds"：门里写明适用区间 `NATIVE_PX_OK_MAX_LONG_EDGE=1920 / BREAK=2560`，并加一条**破点哨兵**单测**断言 1440p 会破** —— ⛔ 没有抬门槛、没有把 STARFIELD 记小；这条哨兵将来若变绿，就是"STARFIELD 已降采样"的机器证据 |
| D19 | §3.1 定稿把海水尺第三档写作**不乘 `sb`** 的字面量 `#02040a`（前两档才带 `·sb`） | `MoonSeascape.sea(depth, sb)` 对**三档一律**乘 `sb`（`pack(r·sb, g·sb, b·sb)`），`sb = 1` 时与书面字面量**逐位相同**（`argb(2, 4, 10)`） | 原型的 `seaC(t)`（`moonlit-preview.html:1168-1173`）本来就是三档全乘，书面式是把 `sb=1` 那一帧的结果当成了特例抄进去。⛔ 实现按**函数**而不是按抄来的常数：这把尺要被**三类**消费者读（层 1 矩形、§7.2 压暗带、§7.3 粼光深度色），两份端点迟早各自漂移（§3.3+ 的 `lumK` 已经发生过一次）。`sb ≠ 1` 时两者才可分辨，差值 ≤ `0.15·(aBass−0.4)` 量级 ⇒ 肉眼为零。判据：`MoonSeascapeTest`「sb 是三通道的同一乘子」+ 负向自证「sb 只乘一个通道会被同乘子判红」 |
| D20 | §10.2 G11 原文要求「断言 `Id.STARFIELD` 相关哈希取整的**位序**与 §3.0(f) 第 7 条一致」 | **无格点**半边判在真实星位上（四组画幅 `dup ≤ 2 / maxCol ≤ 6 / maxRow ≤ 6 / distinct == n`，实测 `0 / ≤3 / ≤4 / n`）；**位序**半边降级为「测试内有牙夹具 + 生产码前进钩子」 | E44 的星位**不由本效果自算哈希** —— 层 2 复用共享纹理 `Id.STARFIELD`，星点来自 `ProceduralTexture.starLayout` 的 **LCG**（种子 `0x5EEDF00D`），生产码里**没有 murmur 位序可断言**。⛔ 两条都不许走：整条删掉 = 把原型踩过的坑重新设为不可见；照着文档"断言位序"写 = 断言一个不存在的东西（空转判据）。处置：夹具那侧用**同一把尺**证明能抓坑（`x \| K` ⇒ `dup=195 / distinct=35/230`，`x xor K` ⇒ 判绿），生产码那侧留一条**出现即生效**的行判据。⚠️ 另记一条度量并用的依据：**纯方阵**夹具 `dup=0`、`distinct=n` ⇒ 只看"有没有重复点"永远抓不到方阵，列/行载荷才是那只手（`MoonlitStarfieldTest` ④） |
| D21 | §十一 T6 行原文「⚠️ `ensureTiled()` **不能省**，先例 `AdvancedRenderers.kt:115-117/:951-952`」 | E44 **不调** `ensureTiled()`（`MoonlitSkySeaTest.④b` 把它与"不开 grain/scanline"**双向**钉住） | 那条红线的**适用条件**是"效果配了 `postFx.grain` / `scanline`"：`OverlayFx.drawGrain` 读不到平铺槽会**静默不画**（E42 就这样坏过一次，根因记录在 `ProceduralTexture.kt:241-247`）。而 `RendererFx.applyPostFx` 只在 `grain > 0f` / `scanline > 0f` 时才读平铺槽（`RendererFx.kt:107-108`），E44 两者皆 **0**（颗粒由 §9.4 的棘轮账裁决不开：`4.39504 + 1.00 = 5.40 > 4.7366`；⚠️ **T8d 落表后为 `4.46104 + 1.00 = 5.46`，结论只会更强**）⇒ 平铺槽一张也不会被读，调它反而多付一次 `GRAIN 8×128×128 + SCANLINE` 的烘焙。⚠️ **前提变了就必须同步改**：日后若给 E44 配上 grain，本条作废且 `ensureTiled()` 要一起加回来 |
| D22 | §3.2 的层 3（月晕 `HALO`）在 §十一 的批次表里**没有归属步骤**（T6 只写"层 1/2/10"，T7 只写"云场 + `occl`"） | 明确划给 **T7**，与云同批落 | `haloA = haloBase · (1 − 0.75·occl)` 的 `occl` 是 §6.2（T7）才存在的量。⛔ 在 T6 提前接只会多钉一个"occl = 0"的刻意常量（与 `DISK_A`/`BLOOM_A`/`DARK_EDGE_A` 同批，那几个已经在 T7 的替换清单里）。层序不变：T7 落 halo 时仍插在**层 2 星野之后、层 5 月盘之前** |
| D23 | §6.4 / §3.0(f) 第 1 条的云色是**连续三元组**（原型每斑现算一次 `fillStyle` 字符串再交给 `softEllipse`） | `MoonClouds.cloudRgb(k, altT, back)` **保持连续**（数学核不变），但**渲染层**把 `(k, altT, back)` 按 **`16 × 4 × 16`** 三把刻度分桶（`kBucket`/`altBucket`/`backBucket` → `shaderIndex` → `cloudRgbAtBucket`），一个桶对应一个缓存 `Shader`（`CLOUD_SHADER_SLOTS = 1024` 槽，实际在场 ≤ 44 且缓漂） | 原型的"每斑现算颜色"在 canvas 上只是**拼一个字符串**，Kotlin 侧对应的开销是**新建 `RadialGradient` Shader 对象**：近层 HIGH 44 斑/帧 ⇒ 每帧 44 次 native 着色器分配，是 `PerfBudgetContractTest` ①（⛔ 逐帧分配）与 G8（圆盘只烘两张）反复拦的那类错误。⚠️ 索引必须**纯算术**、⛔ 不许字符串键。**代价已量化并钉住**（G3 ⑫，生产口径 = `altT = MoonSeascape.ALT_T = 0.6875` 且 `tone ≤ 0.8·(1−back)`，256 步全扫）：最坏单通道 **18**、三通道合计 **46**（钉 `≤20 / ≤50`）；宽容口径整个立方（17 档 `altT` × 64 步）是 **22 / 51**（钉 `≤28 / ≤62`）⇒ 肉眼为零（`255` 上 8% 以内的单通道台阶，且它乘在 `α ≤ 0.78` 的柔边上）。⛔ **不是随手挑 16**：⑫b 用同一把尺把刻度砍到 8 桶，单通道误差立刻**越线**（判据显式断言 `> 20`），这就是"桶数收费"的证据。⚠️ **判据分层**是本条的真正教训：量化是**渲染层**的实现选择，所以 ⑫ 测的是"分桶后的色 vs `cloudRgb`"，⛔ 不要去改 `cloudRgb` 让它"先量化" —— 那会让 §6.1 的逐字对账（①②）失去连续真源 |
| D24 | §6.2 / Q7 / R14 / V8 / V14 / §10.2 G3 行一致记载"自然烟缕场 `occl` **峰值只到 ≈0.33**"，T7 落码时又据此手调了一个 `MoonClouds.OCCL_NATURAL_MAX = 0.53` 常数（一度还写过 0.5218 的"实测峰值"） | ⛔ **该结论作废**：生产几何实测自然态 **`peak = 1`**、`mean = 0.3059`、`occl > 0.5` 占 **26.0%**、`occl ≥ 0.999` 占 **1.71%**、`occl > 0.001` 占 **69.1%**（8000 帧 / `dt = 0.1` / HIGH 全量 3 远 + 4 近缕 × 11 斑 / 1920×1080）；`OCCL_NATURAL_MAX` 常数**已删除**，五个数只住在 `MoonlitCloudBaseline.kt` | **根因是一个把自己量废的探针**：它写 `const minDim = …` 声明在 `vm` 沙箱的**函数作用域**里，**遮蔽**了脚本 `runInContext` 注入的同名全局 ⇒ 缕尺寸 `gw/gh` 恒为 0，整场云退化成一个点，`occl` 当然顶不上去。所以"0.33 / 0.5218 / 0.026 均值"三代人抄的都是**同一个 bug 的输出**（均值比真值低 **12 倍**）。⚠️ 它能在定稿里活这么久，是因为**没有可复跑的脚本** —— 口径一旦只以"文档里的一句数"存在，下一次就没人能复核。**处置**（所有者 2026-10-09 裁决「**只改判据，不动观感**」+「**删掉，改由基线承载**」）：① 判据形态从"把 `occl` 推到自然到不了的 1"改为**分布五项对账**，⛔ **没有放松任何阈值**；② 口径唯一真源改为 `docs/archive/verification/scripts/moonlit_cloud_golden.js`（`vm` 沙箱跑**原型原文**、再生成基线 Kotlin），并加**两条防退化自检**（`minDim` 没赋进上下文全局就抛、包络半轴全 0 就抛）；③ `MoonClouds` 侧只留事实陈述，⛔ 不留第二份数。**连带更正**：§3.0(e)"唯一没解决的是自然云场遮不满月"改为"无需解决"、§6.3 ③ 那句 `clamp(occl·1.6)` 增益的依据改写（**公式一字未动**，它的作用区间本来在半遮）、R14/V8/V14 的"真机上难得一遇"降级为"偶发但常见（26% 帧过半遮）"。⚠️ **Q7 的裁决不重开**：§6.6 确定性过境买的从来是**时长与周期性**（实测触顶占比 9.98% vs 1.71% = **5.8×**），D24 只推翻了"能不能"，没推翻"什么时候来" |
| D25 | §6.1 的 `CloudPuff` / `CloudPlume` 草图：`Float` 字段 + 构造期不可变（运行时字段另在注释里列） | as-built `MoonPuff` / `MoonPlume`：**一律 `Double`**、普通 `class` + `var`，播种字段与运行时字段（`cx/cy/gw/gh/w/h`、斑的 `px/py/rx/ry/a/altT`）**同置一类**；斑数组定长 `Array(PUFF_SEED_COUNT = 11)` | ① **对账基线是 JS 的 float64**：G3 ①② 用 `delta = 0.0` 逐位比播种与 125 帧后的几何，`Float` 在第 7 位有效数字上舍入 ⇒ 一落地就红，而这条对账正是"改 rng 次序整场重排会被抓住"的唯一手段；② 缕与斑的几何**每帧重写**（`layoutPuffs` 写 6 个斑字段 × 11 斑 × 7 缕），照草图做成不可变 data class 就等于**每帧新建 77 个对象** —— 那恰好是本节末尾"Brush 缓存"要防的那类 GC 压力，⛔ 不能为了形状好看而把分配请回来；③ 数组恒 11 是**切档不重播种**的实现前提（截断靠读取上界，⛔ 不靠重建数组，G3 ⑧ 同时比播种字段与数组长度） |
| D26 | §7.6 / §9.2 的 `HORIZON_BAND` 填充：初版记 **0.01**，原型探针记 **0.102**（10 倍），两者都被抄进过规格 | 表按**真实矩形几何**落 **0.160 屏**（三档同值，与画质档无关），由 `MoonWater.horizonBandFillGeometry()` **真算**（含上下越界钳制），⛔ 不再引用 `HORIZON_K` 当系数 | 探针那行写的是 `op('HZ', 0.16·horizonK)` —— 它把"带高 0.16h"又乘了一次 `horizonK = 0.640`，而真实矩形 `fillRect(0, horizonY − 0.10h, W, 0.16h)` **通铺全宽**且**横跨地平线两侧** ⇒ 面积恒 `0.16W·h` = **0.160 屏**。⚠️ 这与 §9.1 的"不相交矩形"（sky/sea 各占一份）**不是同一件事**：那是**分割**（和为 1），这是**重叠**（额外铺 0.16 屏）。**代价已量化**：本行一改，LOW/MED/HIGH 各 +0.058，直接吃掉两档三分之二的余量（`0.111/0.117 → 0.048/0.050`）。⛔ **实测锚不跟着抬**（`MEASURED_FILL_*` 保持探针原样 `3.263/3.759/4.306`）：锚的价值在于它是"那台探针 printed 的数"，抬了就再没有东西能发现**账本与几何分家**；表高于实测的那一截里有 0.058 是这个**已知口径差**，两处 KDoc 同记。方向由 G4 的负向自证 `地平带按探针口径落表 ⇒ 低于真实矩形面积` 钉住（T8a 新增），几何本身由 G14 ⑥ 钉 |
| D27 | §7.3 结论 1 原文把 **MED**（`ROWS = 46`）的粼光三带拆账写成 `21 / 13 / 12` 行 ⇒ `21 + 26 + 48 ≈ 95 条` | 生产几何的**结构求和**是 `11 + 14 + 18 = 43`（LOW）、`22 + 26 + 44 = 92`（MED）、`28 + 34 + 75 = 137`（HIGH）；三带的**行数**拆账为 `11/7/6`、`22/13/11`、`28/17/15`（各自加满恰为 `ROWS = 24/46/60`） | ⚠️ 表值 `43/92/137` **本来就没错**（T2 就是按实测落的），错的是这句**拆账**：它既不是行数（MED 远带 **22** 行，不是 21）也不是条数，`≈95` 与 MED 的真实 92 差 3 —— 而"约等于"的拆账正是 D10 那类"表与几何各说各话"的温床。处置：⛔ 只改文档叙述，代码一行不动；把拆账**机器化**成 G14 ②（`structuralStreaks` 逐带重算 == `MoonOpItem.GLITTER.ops`，同帧一条不剔），并配 ②b 负向自证「每行均摊 `ROWS×PER` ⇒ LOW 的 ops `78 − 43 + 72 = 107 > 90` 顶穿门」—— 名义均摊值 `72/184/300` 与真实 `43/92/137` 差最多 **2.3 倍**，这就是 §9.1 第 12 行那句"记账必须按实测"的量化依据 |
| D28 | §9.2 落表口径：`GLITTER` 三档填充分量记 **0.035 / 0.080 / 0.129**（均值），`REFLECTION` 记 **0.073 / 0.203 / 0.379**（探针就近取整），`HORIZON_BAND` 见 **D26** | `GLITTER` 改取**最坏帧** **0.039 / 0.088 / 0.137**（生产几何 4001 帧扫描 `max = 0.038962 / 0.087361 / 0.136744`，`mean = 0.0335 / 0.0793 / 0.1256`，argmax 时刻 `t = 96.5 / 552.5 / 312.5 s`）；`REFLECTION` 按解析几何 `0.073383 / 0.203162 / 0.378740` 重算后落 **0.074 / 0.204 / 0.379** | 两处是同一个病：**表的职责是上界，却按"典型值"落**。⛔ 门守护的是**最坏帧**（与 §9.1 条件项按上界落表同一条纪律），拿均值落表 ⇒ 真实最坏帧**就在表外面**，涨表时还会被当成"新增开销"误读。⚠️ 取整方向同样收费：`0.073 < 0.073383`、`0.203 < 0.203162`、`0.080` 那格是就近取整把 0.087361 折到了 0.088 以下（三行里有**三格**低于几何）。处置一律是**把表向上改**（3 位**向上**取整），⛔ 不是把阈值放松：判据一侧由 G14 ③b/⑤ 各配一条**反方向**上界「表 − 实测 ≤ 千分之一屏」，防止有人把"向上取整"理解成"多记点更保险"。**argmax 时刻也被钉住**（③ 断言时间戳）：粼光无状态，换帧 = 分布变了 ⇒ 改了数学就会红在这里，⛔ 不能只把表值改大当"对账通过" |
| D29 | §9.2 水面三行的**参照系**未写明（原文只说"与画幅尺度无关"） | 口径补全为：**与画幅尺度无关**（G14 ④ 在 900p/1080p/1440p/2160p 四档逐位相同）、**与 `density` 有关**（`1.2·density` 设备像素地板）⇒ ⛔ **不抬表、不改地板系数**，高密度那一截改由 ④b 按**合计**判 | 划厚 `hh` 有一条 `max(hh, 1.2·density)` 地板（防亚像素划在高分屏上消失），所以 `density > 0.00173·minDim` 时地板**确实**触发（自然最薄 `0.002079·minDim`：900p 1.87 / 1080p 2.25 / 2160p 4.49 px）。实测矩阵：1080p@1.0 与 @1.5 **逐位相同**（验收机 dpr=1.5 落在不触发的那侧）；@2.0 `0.038981/0.087397/0.136795`、@3.0 `0.039533/0.088381/0.138179`、900p@2.0 `0.039142/0.087689/0.137203`、900p@3.0（网格最坏）`0.040063/0.089332/0.139536`、2160p@3 不变 ⇒ 增量 LOW `+0.0011` / MED `+0.0013` / HIGH `+0.0025`（⚠️ **相对表值**，不是相对未取整的实测最坏帧），三档**换成该值重算合计**后余量仍有 `0.047 / 0.049 / 0.273` ⇒ 结论是"表守 `density ≤ 1.5`，更高密度不另开表行、由合计门守着"。⚠️ 这是"口径必须写明参照系"的由来：④ 与 ④b **不能合成一条"分辨率无关"了事**，否则 D29 那段叙述就成了空判据 —— 故 ④b 还额外要求地板**真的触发过**（断言矩阵里存在被抬的格子） |
| D30 | §7.3 结论 3 的"定稿不变量"：⛔ 划厚不能远大于行距，但**下缘比值要 ≈1.2**（比值 <1 会读作"梯子"） | 该式只在 **MED/HIGH 的下缘**成立（1.364 / 1.791）；as-built **三档三点实测**（1080p / dpr 1.5，取 远端 / 中景 / 下缘 三个行号）：LOW `1.227 / 0.244 / **0.692**`、MED `3.705 / 0.462 / 1.364`、HIGH `5.819 / 0.601 / 1.791` ⇒ 判据改为**钉死这三档九点**（G14 ⑨），⛔ **不改** `ROWS` / 透视指数 `1.70` / `GLITTER_W_K` | 三个方向**都与那句直觉不同**，而且都是**设计**：① **远端比值最大**（1.2~5.8）—— 透视把行距压到 1~2 px、划互相搭着，那正是结论 6 要的"贴地平线一条宽细闪带"；② **中景永远最疏**（`<1`）—— 这才是暗槽的真正来源（另一半是 `JIT_ROW_K` 的整行错开），不是缺陷；③ **下缘只有 LOW 低于 1**（0.692）—— 因为 LOW 的 `ROWS=24` 行摊在同一段 `Δy` 上，行距本来就宽。⚠️ 为什么不动观感三件套：那三个数正是原型**七轮判读**通过的那组，且 §十六 裁决「只改判据，不动观感」是本轮通则；把 LOW 的 `ROWS` 抬到让下缘 ≈1.2 会同时改变**整场分布**（argmax 换帧、填充表重算、低档铺屏上涨），用一次观感回归去换一条**写在文档里的直觉**不划算。⛔ 但也不许把那句直觉改成"下缘可以小于 1"糊过去 —— 判据换成**实测**，真机上若 V 系列判出"梯子感"，处置是重开 `ROWS`/`PERSP_K` 并**重跑 G14 ③⑨**，不是放松 ⑨ |
| D31 | §二 数据流图（`:257`）与 §八 表第 1 行都写着 `AudioSmoother.updateDt(target, fx.dt/1000)` | 传 **`fx.dt` 本身**（渲染器内 `/ 1000` **0** 次），而 `RendererFx.kt` 全文**恰一处** `dtMs / 1000f` | `FxFrame.dt` 由 `FrameClock.advance` 产出，`RendererFx.kt:205` 已经做过 `dtMs / 1000f` ⇒ **它本身就是秒**。照抄文档等于喂 `1/60000 s`：`k = 1 − (1−base)^(dt·60)` 在 `base = 0.35` 下从 **0.35 掉到 0.000431**（每帧只走到正确量的 **1/813**，时间常数被拉长约一千倍）⇒ 包络几乎不动（`aBass` 常驻 ≈0 ⇒ 晕恒按 `k=0.976` 画、海面恒 `sb=0.96`）。⚠️ 这类错**不崩、不红、不报错**，只读成"画面跟音乐没关系"——恰是 `AudioSmoother` 类头那条 M10 修复（定率式隐含 60fps）反过来咬人的形态。判据做成**双向**（G15 ③）：渲染器 ⛔ 不得再出现 `/ 1000`（并把文档那行原文当**夹具**喂进去，确认它判红），**且** `RendererFx.kt` 那一处必须还在 —— 防止有人为了凑绿把唯一那次除法删掉 |
| D32 | §八 第 5 行的文字：`energy → 暗角`「高潮时略微**打开**画面」（`0.42 → 0.36`）；原型 `moonlit-preview.html` 那行写 `lerp(AMP.vignHigh, AMP.vignLow, k)`，配 `vignHigh: 0.36` / `vignLow: 0.42` | `MoonAudio.vignetteA(aEner) = VIGN_QUIET + (VIGN_CLIMAX − VIGN_QUIET)·clamp(aEner·1.4)` ⇒ **单调递减**（越吵越亮），`aEner ≥ 1/1.4 = 0.714` 饱和 | 原型按字面实现得到的是「越吵、暗角越**重**」，与 §八 自己那行**正相反**（该表「⚠️ 参数名与方向易写反」这句警告正是为此而留 —— 文档已预见到，⛔ 实现时代码仍然照抄错过）。取**文字意图**为准。⛔ 另有两个"顺手就改"会把门弄哑：① `postFx.vignette = 0.42f` 必须保持**数值字面量**，`FxCoverageScanTest` 只读 `postFx` 里的数字，换成 `MoonAudio.VIGN_QUIET` 它就读到 0 个覆盖项（静默丢，⛔ 不会红）；② 无声帧等式 `vignetteA(0) == postFx.vignette == 0.42f` 由 **G15 ⑯b** 钉 —— 它是本批"改尺不改观感"的**唯一**观感闸门 |
| D33 | §八 第 6 行写 `frame.pulse → 涟漪`（入水事件） | 用 **`frame.beat`**：`frame.pulse` 在渲染器里 **0** 次，`frame.beat` **2** 次（银边 `MoonAudio.rimBeatK(frame.beat)` 一次、涟漪 `if (beat) ripples.push(` 一次） | `pulse` 是**快起慢落的包络**（连续多帧为真），拿它 push ⇒ 一次拍在队列里留下**一串残影环**；而 §7.5 的档位槽位 `MED 2 / HIGH 4` 在"2 s 节拍 × ~1.8 s 环寿命"下本就接近占空比饱和（**D17** 已经为此把 MED 从 `≤1` 更正为 2），再叠残影等于把**事件**误记成**电平**。`beat` 才是"一拍一环"。判据：两个计数 + 队列上限 `0/2/4` **== `MoonOpItem.RIPPLE.ops`**（G15 ⑫）⇒ 换通道不是注释层面的选择，它直接改表。⚠️ §八 那行**不改**，以本条为准（文档留痕 > 文档整洁） |
| D34 | §9.2 的 `HALO` 行按**中性态实测**落表（`0.069 / 0.131 / 0.244`）、`RIPPLE` 行按 `passcost` **探针典型帧**落表（`0 / 0.017 / 0.049`） | `HALO` = 中性 × **`k²`**（`k = 1.036`、`k² = 1.0733`）向上取整 ⇒ **`0.075 / 0.141 / 0.262`**；`RIPPLE` = `MoonWater.rippleFillFraction` 的**生产几何最坏帧** ⇒ **`0.000 / 0.073 / 0.146`**（1080p 单环 `0.036350` × 槽位 2/4，记账半径 `ripplePMax() = 0.9312`） | 两处都是 **D28**（"表的职责是上界，却按典型值落"）在 T9 的续集，且各自**新添一个方向性错误**：① `bass` 一动**半径**就是按**平方**收费（晕是径向渐变，面积 ∝ `r²`），⛔ 只乘一次 `k` ⇒ 三档全部**低于**最坏帧几何；② "最大可见半径"**不是**寿命终点 `p = 1`，而是 α 恰好贴到 `MIN_ALPHA`（`0.004`）的那一点 `pMax` —— 按 `p=1` 记会**多**记到 `0.08235`（MED 表 `0.073`），那一段一个像素都不铺 ⇒ **上界也不能松**（⑭ 用等式 `rippleAlpha(pMax) == MIN_ALPHA` 让早退判据与记账口径**同源**）。三档合计因此 `+0.006 / +0.066 / +0.115` ⇒ MED 越过 `3.95` ⇒ 触发 **D5** 的二次裁决。⛔ `MEASURED_FILL_*`（3.263/3.759/4.306）**一个都不动**（锚是探针 printed 的那个数，见 **D26**）。四条负向自证钉方向（G15 ⑬⑭）：按中性实测落表 ⇒ 低于几何、按最高桶桶中心落表 ⇒ 低于连续上界、按探针典型帧落表 ⇒ 低于最坏帧、按 `p=1` 记账 ⇒ 多于表值。换算式与半径常量由 `MoonOpBudget` **直接调用** `MoonAudio.haloRadiusK` / `MoonWater.rippleFillFraction`（⛔ 抄数，承 **D10 / D24** 的封口） |

**D7–D10：原型七轮对 v1.0 规格的推翻**（⚠️ 这几条**不是实现期偏差**，是"设计被观测打回"的记录，
留在这里是为了让未来读 v1.0 的人不再把旧模型当成待办）

| # | v1.0 规格 | 定稿实际 | 被推翻的原因 |
|---|---|---|---|
| D7 | 云 = **扁椭圆软团**（`CLOUD_FAR/NEAR` 3/5/7 团，§6.1 旧版） | 云 = **烟缕场**：`缕 × 斑` 两级（`CLOUD_FAR 2/3/3 × BLOB_FAR 4/5/6`、`CLOUD_NEAR 2/3/4 × BLOB_NEAR 7/10/11`，§6.1） | 所有者第三轮：「形状还是不对，**应该是烟雾的样子**」。软团无论怎么调 α 都读成"灰椭圆贴纸"；改成蛇形脊线上排拉长斑之后一次通过 |
| D8 | 水面 = **月盘镜像位图切片**（§7.2 旧版 4/8/14 段镜像） | 水面 = **光柱 glade**（`1 柱头 + NFOG 1/2/3` 高椭圆），⛔ 水面上**不出现月盘的像**（§7.2） | 第六轮「水面倒影不像」。连画四轮"盘+打碎"都读作**一块浮在水面的暗板**；根因是**盘并不比月光照亮的海面更亮**，而真实夜景水面本来就是光柱。改为按面积铺光柱后客观门一次达标（§3.0(e) 定稿基线） |
| D9 | 粼光 = 每行一划、`len` 由 0.35–1.0 噪声调制（§7.3 旧版） | **ROWS 24/46/60 × 深度分档 `per` 1/2/(3/4/5)**，`fy = ((i+0.5)/ROWS)^1.70`，实测 43/92/137 条（§7.3） | 「每行一划」结构性地必然刷成**灰色横条梯子**；同时旧判据「⛔ 不能是连续光柱」方向也是错的 —— 参考图真机复量是**连续光带上骑断划**（见 D10） |
| D10 | 预算表按设计值记（ops 34/59/100、fill 3.09/3.37/3.23） | **实测** ops 74/150/217、fill 3.22/3.66/4.21（§9.1/§9.2，`probe` 读回） | ① 云换了模型 ⇒ 斑数暴涨；② 水面从点缀变主角 ⇒ 单项填充 ×10；③ `HORIZON_BAND` 当初凭感觉记 0.01，实测 0.102（⚠️ **那个 0.102 后来也被更正**：探针把带高又乘了一次 `horizonK`，真实矩形通铺全宽 ⇒ **0.160**，见 **D26**）。**结论：⛔ 预算表必须测，不能设计**（⚠️ T8d 再加一句：**测了还要核对探针自己的口径** —— 这一行的两次错都出在"抄打印值"，⛔ 出在几何本身） |
| | *（待实现期追加：帧率实测、烘焙耗时、色/亮度调参）* | | |

---

## 十六、待裁决项（✅ **2026-10-09 全部拍板完毕，T2 解除阻塞**）

> ⭐ **所有者的裁决原文（2026-10-09，逐字保留）**：
> 「**Q4/Q8/Q9：将上限值调整合适，保证 High 能体现所有效果，实际上 high 不应该有上限**」
> 「**Q7：选 3**」「**其他问题按建议实施**」
>
> ⇒ 落地口径（实现期照此，⛔ 不要再回去找"越限怎么办"）：
> **Q4/Q8/Q9** = `OVERDRAW_MAX = 3.45 / 3.95 / 不设上限`，HIGH 的门改为
> 单调方向 + 棘轮（§9.2），**三条杠杆（砍云 / 早退省屏 / 关暗角退保真）全部不执行**；
> **Q7 = ③** ⇒ 云场加**确定性过境周期**（新增规格见 §6.6，⚠️ 这条**要写代码**，不是"不做"）；
> **Q1/Q2/Q3/Q5/Q6 = 建议** ⇒ 不做隐藏 / 单档 `MOON_R_K = 0.150` / 不做云量开关 /
> 保持 `Tier.ADV` 三档全开 / 不引入月相日历 UI。
> ⚠️ 裁决把"填充越限"从**待办**换成了**未证明项**：真正的风险现在是 **API 22 创维在 4.31 屏下的帧率**
> （§9.2 结论 3），兑现处是 T10 真机判读，⛔ 不是实现期提前砍。
>
> ⭐ **二次裁决（2026-10-10，T9 的 D34 触发，逐字保留）**：
> 「**抬 MED 上限到 4.00（推荐）**」⇒ `OVERDRAW_MAX_MEDIUM` 从 `3.95` 改为 **`4.00`**。
> 起因是 T9 把 §八 的两处几何调制落表后（HALO 涨 6% 半径 = 涨 7.3% **面积**、RIPPLE 的
> `0.017/0.049` 来自探针**典型帧**而非最坏帧），MED 合计越过 `3.95`。
> ⚠️ 该题一共给了**四个**候选，被选的是第一个；另外三个全部**否决**并留在此处，因为它们是
> "用改表/改观感去凑绿"的标准样本：**②晕半径只降不升**（`k ∈ [0.94, 1.00]` ⇒ HALO 行不进账，
> 代价是 §八 的「±6%」缩水成「−6%~0」，高潮时晕不再鼓起来 = **动观感换绿**）；
> **③抬涟漪早退门槛**（`MIN_ALPHA 0.004 → ~0.02` ⇒ 环在 `p≈0.73` 就停画，寿命肉眼变短，
> 且违背"α 早退点由几何决定、不由凑数决定"这条口径）；
> **④涟漪减到 1 环**（⛔ 与 **D17** 的实测直接冲突 —— 探针半遮态实测同帧 2 环，减到 1 就是
> 把表重新写成**下界**，正是 §十四 R12 明令禁止的"砍元素保绿"）。
> ⇒ 落地口径补一句：**LOW 的 `3.45` 与 HIGH 的"不设绝对上限"（棘轮 `4.7366`）一个都不动**，
> 实测锚 `3.263/3.759/4.306` 也不动（**D26** 那条纪律继续有效）。
> 机器留痕：**G15 ⑮** 断言 `med > 3.95f`（证明这次改裁决**有后果**）+ `OVERDRAW_MAX_LOW == 3.45f`
> + `overdrawMax(HIGH).isInfinite()`（证明另外两端**未被顺手改动**）。详见 **D5** / **D34**。

> ⛔ ~~**两个例外，不能"按建议先写着"**：**Q4 + Q8** 卡住 **T2**（`MoonOpBudget.kt` 一落码，
> `MoonOpBudgetTest` 就因为 §9.2 的实测越限而红 —— 那不是可以自行"按建议"抹平的偏差，
> 三条杠杆里有两条**改的是观感**）；**Q9** 与 Q4 是同一组常量的正负号问题。~~
> ✅ 该阻塞随 2026-10-09 裁决解除（三条本来就是一起拍的）。
> 其余（Q1/Q2/Q3/Q5/Q6）按"建议"实现并在 §十五 留痕；**Q7 不是"不做"，是要加代码**（选 ③）。

- **Q1 ✅ 已拍板：不做**。**时间一致性**：新月前后的亏月/蛾眉月其实在**后半夜/白天**才升。
  本效果是否按"夜晚播放"这一隐含前提，⛔ 不做"此时月在地平线下"的隐藏？
  → 建议：不做。隐藏会让约每月 5 天整个效果是黑的，且需要观测点与月位算法（§十四）。
  **裁决 = 建议** ⇒ §十四 的"不做"清单里加一条正面对应：**月位高度角 / 观测点 永不在本效果出现**。
- **Q2 ✅ 已拍板：单档 `MOON_R_K = 0.150`**。**月盘大小**：参考图直径差 3×。是否要"巨大贴地月"变体（图 1）？
  → 建议：v1 只出一档 `MOON_R_K = 0.150`，若所有者要"大月"直接改常量（单点）。
  ⚠️ **定稿给了这条一个实测依据**：客观门量出候选 `R/H = 0.149` vs 参考图 `0.227`
  —— 即**月盘直径相对画面高度小约 1.5 倍**。⚠️ 但两张图幅面不同（参考图 4:3、原型 16:9），
  参考图的"巨大贴地感"一部分来自**月更贴地平线**（`MOON_CY_K` 0.200 是定稿固定构图），
  不只是半径大。`MOON_R_K` 也 ⛔ **不是单点**：它同时乘进 `BLOOM (1.28R)²`、`HALO`（最外 4.0 R）、
  `REFLECTION` 柱头宽、`TERMINATOR` 面积 ⇒ 改到 0.22 大致是**填充 ×1.4**。
  ⚠️ **该条的"会推大缺口"理由随 Q4/Q8/Q9 裁决而失效**（HIGH 已无上限），
  但 ⛔ **结论不变**：V13 判读时不要因为"盘看着小"就顺手改（§十三 V13 的 ⛔ 条款）。
  真要"大月"是**另一次裁决**（要重出 §9.2 整表 + 重跑客观门），⛔ 不是改一个数。
- **Q3 ✅ 已拍板：不做**。**云量是否给用户开关**（0=无云 .. 1=厚云）？
  → 建议：不做。`occl` 由云场几何自然决定，加开关会引入"用户设 0 却要看到云"的观感争议。
  **裁决 = 建议** ⇒ 与 Q7③ 一起构成完整口径：**观感由几何给，不由用户给**。
- **Q4 ✅ 已拍板（与 Q8/Q9 同批）：上限调整为 `3.45 / 3.95 / 不设上限`**，
  依据 2026-10-09 的半遮态实测（LOW 3.26 / MED 3.76 / HIGH 4.31，§9.2）。
  历史：v1.0 只需认"上限取 3.30/3.60/3.50"；定稿实测越过它 ⇒ 曾签成"这三个数超了怎么办"。
  ⛔ **实现期不要再回去找越限处置** —— 水面保真一字不退，D5 至此**签字完成**。
- **Q5 ✅ 已拍板：保持 `Tier.ADV`（三档全可用）**。**`Tier` 选型**：`ADV` + `needsParticleBudget = false`。
  若所有者认为 LOW 档（老电视）不该有它，需改成 `ULTRA`
  （`ULTRA` 的门槛是 `allowFramebuffer`，只有 HIGH 有 ⇒ 等效"仅最高画质"，同 E41 的做法）。
  ⚠️ 定稿那条判据**已被新上限缓和**：原判据是"LOW 余量只有 0.08 屏（3.22 vs 3.30）"，
  现按 `3.45` 与半遮态 3.26 ⇒ 余量 **0.19 屏**。⚠️ 但**老电视的实际帧率仍与 `OVERDRAW_MAX` 无关**
  ⇒ 若 T10 真机 LOW 档不达标，处置是**降 `MoonlitRenderer` 的铺屏**（§十五 留痕），
  ⛔ 不是回头改 `Tier`（`supports()` 的历史教训见 `ParticleBudgetGateTest` 那次 option C 裁决）。
- **Q6 ✅ 已拍板：不引入**。**"月相日历"辅助 UI**（设置里显示今日照度/月龄）：
  与本效果无关的产品决策，登记以免日后误加到渲染器里。
- **Q7 ✅ 已拍板：选 ③（确定性过境）**。云遮月的**戏剧性够不够**？
  自然云场的 `occl` 实测上限 ≈ **0.33**（§6.2），也就是说真机上"云把月吞掉、水面只剩漫射亮带"
  这一幕**可能长时间不出现** —— 而需求 3 原话点名的正是这个场景。三条路：
  ① 维持现状（`occl` 由几何自然决定，观感偏"薄云飘过"，⛔ 无强制过月云）；
  ② 提高云量/加大斑半径把 `occl` 顶上去（⛔ 会推高填充；本次虽有裁决兜底，仍**未采纳**）；
  ③ ⭐ **给云场加一条确定性过境周期**（例如每 ~90 s 有一缕必过月盘，仍是几何、不是钩子）。
  ⇒ **规格落在 §6.6**（本裁决新增的唯一"要写代码"的条目，随 **T7** 实现）。✅ **T7 已落地**
  （`TRANSIT_INDEX = 0` / `TRANSIT_BAND_Y = 0.3125`，只钉 `cy`；`TRANSIT_EVERY_CYCLES` 闸门按
  §6.6 的"可选"身份**未实现**，等 V14 真机判读结论）。
  ⚠️ **题面依据更正**（2026-10-09，**D24**）：那句"自然上限 ≈0.33 ⇒ 这一幕可能长时间不出现"
  出自一个把 `minDim` 量成 0 的坏探针 ⇒ 自然态实测**能**触顶（`peak = 1`，占 1.71% 的帧）。
  ⛔ **但裁决不因此重开**：③ 买的是**触顶时长与周期性**（过境把触顶占比从 1.71% 抬到 9.98%，
  并把"何时发生"变成可计算的 49~76 s 一圈），这跟"能不能吞月"是两件事；选项 ① 当年被否的
  理由（观感偏薄云飘过、无从判读 V14）依然成立。
  ⚠️ 附带后果：半遮态从"可能不出现"变成"**必然周期性出现**" ⇒ §9.2 的半遮态实测自此成为
  **常规帧**的预算依据（⛔ 以后再拿自然态 `occl = 0` 那组数落表就是错的）。
- **Q8 ✅ 已拍板：三条杠杆一条都不执行**（裁决落在"调整上限"这一侧）。曾列的缺口是 MED
  **+0.06** / HIGH **+0.71**（§9.2 旧上限口径），三个杠杆的实测代价登记如下**作为备选史**：
  ① **砍云**：HIGH 云场总共只有 0.583 屏 ⇒ ⛔ 单独不够，砍光了还差 0.13 —— **未采纳**；
  ② **补 `α < ε 就跳过`**（§3.0(f) 口径 2 的免费路径，原型只在 GLIT 做了）：预计 0.3~0.5 屏 ——
    ⚠️ **该估算永久留在估算状态**：⛔ 不要再为它补测、⛔ 不要引用它做决策（§9.2 结论 2）。
    ✅ 但**早退本身仍要移植**（那是记账纪律，与省屏无关：α≈0 的图元照样吃面积）；
  ③ **动改观感的项**：关 HIGH 暗角（省 1.00 屏）或退回 v1.0 水面保真（省 0.335，等于推翻
    §7.2/§7.3 与 D8/D9）—— **未采纳，且自此没有采纳的必要**。
  → 落地：**HIGH 不设上限**、MED/LOW 上限按半遮态实测抬到够用（§9.2），
  ⛔ **观感不退、云不砍、暗角不关**；风险转移到真机帧率（T10 / R1 / V 系列）。
- **Q9 ✅ 已拍板：`MAX_HIGH` 不再是数（不设上限）**。原问题是
  `LOW 3.30 / MED 3.60 / HIGH 3.50` 里 `MAX_HIGH < MAX_MED` —— 画质越高、允许铺的屏反而越少，
  ⛔ 说不通（E43 原表是 `2.0 / 3.89 / 3.96` 单调，`SeasideOpBudget.kt:515/:531/:561`）。
  ⚠️ **来源更正**（2026-10-09 追查）：那组数**不是抄 E43 抄错的**，而是 **v1.0 用自己的
  "设计值 + 约 0.2 余量"凑的** —— v1.0 的 fill 设计值 3.09 / **3.37** / **3.23**，HIGH 本就低于
  MED，上限跟着低于 MED。⇒ 真正的错在**上游**（拿设计值当上限的输入，同 D10），
  不在符号上；只把 HIGH 抬成单调值仍是错的。
  ⚠️ **原建议 `3.30 / 3.60 / 4.30` 也未采纳** —— 它建立在自然态 4.21 之上（余量仅 0.09），
  而实测半遮态已到 **4.306** ⇒ 那个建议一落地就红。
  → 落地：**HIGH 无绝对上限**，改由**单调方向断言 + 棘轮**接管（§9.2 的 ⭐ 块），
  LOW/MED 取 `3.45 / 3.95`。

---

## 版本跟踪

| 版本 | 日期 | 变更 | 状态 |
|---|---|---|---|
| v1.0 | 2026-10-08 | **初版**。① 需求四条 + 参考图 4 张逐条转写为可判定条款（§一，原图不入库，本表为唯一留痕）；② 现状核实全部 `file:line` 实测：注册链只有 2 个生产文件（§2.1）、`ADV + needsParticleBudget=false` 三档全可用的推导（§2.2）、`RendererFx` 基类契约逐行（§2.3）、**`assets/globe/moon.jpg` = 1024×512 等距圆柱真实月面图已在包里**（`SOF0 1024×512` / 324,830 B，本轮实测；复用 = APK 零增量，§2.5）、全仓**没有** asset→Bitmap 先例（只有 `WorldGlobeRenderer.kt:217` 的 `assets.open` 给 WebView），最近可抄是 `PhotoBuffer.kt:271-295`；③ **月相算法选型经 6 个已发表天象锚点实测**：朔望截断级数误差 0.3–1.0 天 ⇒ **弃用**；改日月黄经差（15+16+4 项），6/6 命中、残差 ≤ 2.7°（偏差 D6）；期间**修掉两个自己造的错**：历元误用 Jan 0.0（应为 J2000.0 = 2000-01-01 12:00 UT，混用致全体偏 ~16°）、`L` 速率误用 13.179056（应为 **13.17639648**，26 年偏 ~24°），两者都写成 §4.2 的 ⚠️ 条款与 G2 的负向自证；④ §四 给出 `MoonPhase` 六个纯函数签名 + **北京 2026-10 逐日 f/e/PA/月龄/libration 向量表**（含起草日 10-08 = **残月 5.1%**）+ 2026 全年朔望表；⑤ §4.4 **朔望位置角退化**（实测 10-25→10-26 一夜 PA 跳 112°）的 `\|sin e\|` 加权对策，⛔ 不是调参掩盖；⑥ §五 等距圆柱→正射圆盘的 UV 反解（含天平动，实测范围 libW −6.2..+4.8° / libB −2.8..+4.9°）+ **增量分帧烘焙逐字复用 E43 的五个常量**（`SeasideRenderer.kt:677/689/698/725` + `:3133/3143` 的"纳秒当毫秒"真 bug 教训）；⑦ §六 云走**软椭圆向量场**而非雾纹理（`Id.FOG` 是全屏且不可无缝滚动，新 `Id` 要同步 6 处）+ **`occl` 单一标量的五个消费点** ⇒ 需求 3 从"特效"变成**结构性保证**，并明令 ⛔ 二值门（E43 被提交数逼出二值门的自我批评 `SeasideOpBudget.kt:486` 不得重演）；⑧ §七 ⛔ 二次场景渲染，改**切片镜像 + 碎金路**，`reflA/glitA` 全部 `× diskA` 同源；⑨ §八 音频映射表（只用线性律动通道、需强弱差异时走 `bassRaw`、⛔ 不读 `maxParticles`、⭐ 首个用 `AudioSmoother.updateDt` 的生产效果）；⑩ §九 预算表：ops **34/59/100**（上限 90/200/320）、fill **3.09/3.37/3.23 屏** ⇒ E44 自带一组上限（**偏差 D5，需签字**）、native **2,860,032 px**（余量仅 4.9%，4K 必破）；⛔ 全篇不按 ≈0.45 ms/op 估算（已被真机证伪）；⑪ §十 实测列出新增第 22 套**必然变红的 12 处**（`VisualizerThemeTest.kt:51/57/58/89/90/96/103`、`StarrySkyTest.kt:1039`、`FxCoverageScanTest.kt:41-56/252`、`AGENTS.md:56`、两份文档），⚠️ 双语 strings 缺失**无测试会红** ⇒ 新设 G1；另 G2–G9 八条新门（G3 = 需求 3 的可执行定义、G5 扫墙钟滥用、G6 防 Activity 泄漏、G7 防新增 asset）；⑫ §十三 U1–U7 通用 + V1–V12 逐效果判据，含 ⭐ V8「天上没月、水里一条金路 = 失败」；⑬ §十四 明确不做 9 条、§十五 偏差 D1–D6、§十六 待裁决 **Q1–Q6**（⛔ 均未拍板）。⛔ **本版不改任何源码**，纯设计文档 | **待评审**（T1–T3 可开工；§十六 6 项待拍板；§十三 全 19 条判据未跑） |
| v1.0 增补 | 2026-10-08 | 所有者指出**初版缺"技术路线"** ⇒ 新增 **§3.0 技术路线**（初版只有逐条算法/参数规格，没有"为什么走这条路、代码落在哪、边界在哪"）：(a) 渲染路线选型表（选中 `DrawScope + nativeCanvas`；否决 WebView/three-globe、AGSL/`RenderEffect`/OpenGL、`Id.FOG` 采样、`OffscreenFx` bloom、二次场景镜像，逐条给否决理由）；(b) 代码落点表（新增 `renderers/MoonPhase.kt` / `MoonOpBudget.kt` / `MoonlitRenderer.kt`，改动只有 §2.1 的 2 个生产文件 + 双语 strings，⛔ 不新增 `Id`、不新增 asset、不新增依赖）；(c) 每帧数据流图 + 三条不变式（单一 `occl`、`diskA` 是圆盘唯一亮度乘子、动画时基只来自 `fx.dt`）；(d) 位图路线五阶段（解码→反解→烘焙→绘制→释放）；(e) **验证路线**：先出浏览器原型 `docs/moonlit-preview.html` 由所有者判观感，通过后才动 Kotlin。同步：§十一 新增 **T0**（已打勾，⚠️ 只代表算法与路线成立，不代表观感通过）、§3.2 修掉悬空引用 `（§4.7）`→`（§4.2 末）`、原型实测**暴露 8 处文档缺陷**（§9.2 三档 fill 合计与自身行不符且 HIGH 已越上限 ⇒ D5/Q4 需重裁；§7.4「晕先消失」与 §6.3 系数矛盾；§4.2 月龄公式与 §4.6 B 的 27.39 天自相矛盾；§4.6 B 的 e 列越界；§4.4 缺 PA→画布旋转换算 `canvasRot = −(PA·\|sin e\| + 90)`；§4.2 `b = R(2f−1)` 轴向反号；§9.1 RIM/RIP 条件项应写作 `≤`；观感项：自然 `occl` 上限 ≈0.33，"云吞月"戏剧性不足）**⛔ 均未回填，待所有者逐条裁决**（⇒ 8 处已全部在 **v1.1** 回填，见下一行）。⛔ 本轮同样不改任何源码 | **待评审**（T0 ✅ 技术验证通过、观感闸门在所有者；§十六 6 项 + 8 处实测缺陷待拍板） |
| v1.1 | 2026-10-08 | **原型定稿回填**（所有者：「好，html 效果定稿，再将内容更新回开发文档中」⇒ 观感闸门通过）。`docs/moonlit-preview.html` 经**七轮**判读（轮次即任务 #3~#7），每轮的判据与被否写法全部并入正文；**⛔ 本文自此是实现期的唯一依据**，原型 HTML 里的注释只是原型自己的史。**① 模型级推翻 4 条**（登记为 D7–D10）：云从"扁椭圆软团"改**烟缕场**（第三轮「应该是烟雾的样子」）；水面从"月盘镜像位图切片"改**光柱 glade**、⛔ 水面上不再出现盘（第六轮「倒影不像」，根因 = 盘并不比月光照亮的海面更亮，连画四轮"盘+打碎"都读成浮在水面的暗板）；粼光从"每行一划"改 **ROWS × 深度分档 `per`**（每行一划结构性刷成灰色横条梯子）；预算从"设计值"改**实测值**。**② 单一图元收敛**：新增 **§3.0+ (f) 移植契约** 7 条 —— 除两块基矩形/晕/暗角外**每一个柔边元素**都是同一个 `softEllipse(α, colIn, colOut, coreK, coreA)`（元素之间差参数不差代码路径）、`BlendMode.Plus` 的两个加法点、⛔ 字符串拼色、**三条记账规则**（椭圆 `2π·a·b`；⛔ `op()` 只看面积不看 α ⇒ **α=0 不回收预算**，几何前必须早退；改 α/coreK 免费、只有几何收费）、`x += speedK·spdMul·dtSec` 的时基红线（第四轮"抽搐"根因是把绝对时间乘音频乘子）、确定性播种与 A/B 方法、JS `x \| 0 ^ K` 优先级坑（星野长出格子）及 Kotlin 侧的位序。**③ 算法/公式修正**：月龄式 `wrap360(e)/360 × 29.530588853`（旧写法与 B 表自己的 27.39 天矛盾）；阴影轴统一 `v = R(1−2f)` 且 **`f ≥ 0.995` 整段跳过**（`sign(1−2f)` 在 f→1 翻号会让软带从外侧长回来）；PA→画布旋转补 **`-(tiltDeg + 90f)`** 的 +90；§3.2 修"若把**近层**计入 occl"的**反写**（被禁的是远层）；§7.4「晕先消失」降级为**观感陈述**（标量上晕其实最后归零）；§4.6 B 的 e 列**混记法**披露（越界值是单调 past-full，⛔ 不得直接对 `elongDeg()` 断言）。**④ 亮度/色彩**：新增 **§3.3+ 定稿色温**（解像素得到的 `moonHue/moonLit/tintMul/lumK/HOT_K/whiteOf` 六件套，含"颜色会封死亮度 ⇒ 加 α 顶不动"与"⛔ 别靠乘增益提亮月盘"）；**§5.2+ 烘焙色彩曲线**（单位红线 `rgb[]` 已是 0..255，旧式多乘 255 把整盘冲到 78.4% 落在 224-255 桶；**先降反差再谈增益** `0.45+0.95g → 0.72+0.55g`，因参考盘是**平的**、峰值/均值 1.21）+ ⭐ 新增 `BLOOM` 加法过曝芯（1 op / 0.024 屏，⛔ 乘子 >1.0 会在削顶处永久丢色相：旧的 1.16 把金 `(1,0.80,0.47)` 烧成白饼 `(1,0.94,0.70)`）。**⑤ 云章重写**：`缕 × 斑` 两级计数、`layoutPuffs` 全部公式、第七轮的**面积守恒**变形（`lenK×0.82`、`thickK×1.35`、`sq 5.5→3.2` ⇒ 面积 ×0.96 = 免费）；速度 `SPD_FAR 0.0070`(~143 s)/`SPD_NEAR 0.0165`(~61 s)；`DRIFT_Y 0.075→0.045`（⚠️ 加粗必须还债，别只比归一化值）；⭐ **逆光剪影双色模型**（`CLOUD_DARK/CLOUD_LIT/CLOUD_SIL` + `warm` + 环境底光，"云不像"的真根因）；§6.4 银边改**斑级 `softEllipse`**、⛔ `drawArc`/`softRing`（闭环比半埋的那半会被吃掉、亮的半截挂成"月盘下的亮括号"）。**⑥ 水面**：§7.2 glade 定稿（⛔ 5 个扁椭圆 = 0.521 屏只换 +9 op ⇒ 改 1–3 个**高**椭圆 0.20 屏）、§7.3 完整伪代码 + **9 条实测结论**（深度分档、`hh/Δy` ≈1.2 不变量、`jitRow` 与逐 seed 哈希治"梯子"、指数 2.30→1.70 因下 1/3 全黑、底宽下限 0.22→0.42、`whiteOf` 上色规则）、§7.5 涟漪 `softRing`（⛔ 描边椭圆读作"铁丝圈"；α 上限 0.075 是刻意的，更亮会读作"盘子的投影"= 重新造出 D8 推翻的暗板）、§7.6 地平线带参数。**⑦ 预算换实测**：ops **34/59/100 → 74/150/217**（余量 2.6/3.4/3.2× → **1.22/1.33/1.47×**）；fill **3.09/3.37/3.23 → 3.22/3.66/4.21** ⇒ ⚠️ **MED 越 +0.06、HIGH 越 +0.71，定稿没通过自己的填充表**；超出全部来自水面三兄弟（初版 0.05 → 实测 0.385）与 `HORIZON_BAND`（记 0.01、实 **0.102**，10 倍）；`CLOUD_RIM` **未测**（`occl=0` 时不画）。**⑧ 判读工具链入库**（`docs/archive/verification/scripts/`）：`moonlit_visual_driver.mjs`（无头 Edge+CDP，`snap/probe/series/cost/flow/pass/disk`，⚠️ tier 恒为第 1 参、`snap` 第 3 参是后缀）、⭐ `moonlit_ref_match_check.py`（**"像不像"的客观门**，几何从 HTML 常量回读防"测跑两张皮"；参考图带水印⛔ 不入库 ⇒ 干净仓库打印 `SKIP` 是预期）、`moonlit_water_zoom.py`（水面放大对照）、`html_syntax_check.js`（定稿态 510412 字符）。定稿基线：`R/H 0.149`、盘内 `[199,169,99]`、盘 p95 `206.3`、水面 2% `[239,209,157]`、20% `[195,170,129]`、点亮宽度/R `[0.30,0.62,3.12,2.41,3.08,3.08]`。**⑨ 判据与决策**：§十三 V6/V7/V8 按定稿改写（V7 ⚠️ **与 v1.0 判据方向相反**）、新增 V13 定稿基线对照、R1 换实测铺屏数、新增 R12（越限严禁私自砍云）/R13（⛔ **七轮全部在 `phaseLock:'full'` 下调参，非满月观感从未判读**，且三个 `clamp(f·k)` 都取在上界）/R14（`occl` 自然上限 0.33）、T0 打勾改为"观感已过"、T2 增加 **Q4/Q8 前置依赖** 与 RIM 补测、§十一 T4/T5/T6/T8/T9 各补定稿口径；§十六 **Q2 补实测依据**、Q4 改为"签越限处置"、新增 **Q7（云遮月戏剧性）/ Q8（越限走哪条杠杆，⛔ 阻塞 T2）/ Q9（`MAX_HIGH 3.50 < MAX_MED 3.60` 符号反了）**。**⛔ 本版仍不改任何源码**（纯文档回填）；实现期红线与三条不变式未动 | **可开工**（T1–T11 解除阻塞；⚠️ **T2 被 Q4/Q8 卡住**；§十六 **Q1–Q9（⛔ 均未拍板）**；§十三 U1–U7 + V1–V13 全 20 条判据未跑，且 ⛔ 原型从未证明 API 22 创维的填充率） |
| v1.2 | 2026-10-09 | **实现期前四批落地**（T1 / T3 / T4 / T5，⛔ T2 仍卡 Q4/Q8/Q9）。**T1** `MoonPhase.kt`（六个历算纯函数 + `of()`）+ G2 `MoonPhaseTest` 17 例：A 锚点最大残差 2.68°、B 表 8 行逐字、C 表 25 组 ≤0.094°、D1–D6 负向自证全绿；实现期回写 §4.6 三处口径（C 表 `07-26 望` 是错的、D1 只覆盖 2024 之后、D3 的对称是对 `e` 不是对时间）。**T3** 注册链 2 个生产文件 + 双语 strings + `MoonlitRenderer` 骨架（只落 §3.2 层 1 两块不相交渐变矩形 + `postFx` 字面量），`FxCoverageScanTest` 计数 21→22；踩到一次**自己的空转**（抽取正则漏 `(?m)` ⇒ 抽出 0 条、子集断言恒真，被 N2 的"条数 == `entries.size`"抓住）。**T4** `MoonDiskBake.kt`（§五 纯计算核，零 Android 类型）+ 解码/§5.4 降级/§5.2 自适应增量烘焙/部分行贴图/`BLOOM` 加法过曝芯 + `MoonDiskBakeTest` 20 例。**G10 的 ② 条被实测推翻并重写**（原判据"p95/均值 ≤ 1.25"在纹理层不可满足且**方向反了** ⇒ 换"顶格占比 ≤ 8% + 盘内 R 均值 ≥ 140"双判据，p95 降级为分布哨兵；所有者裁决「按建议来」= **加强门禁**，⛔ 不是放宽阈值涂绿），另登记两处定稿偏离（喂进烘焙的 tint 实为 `tintMul × lumK × DISK_GAIN`；§5.4 降级机制改为"程序化等距圆柱源图 → 共用 §5.1/§5.2 管线"）。**T5** 相位阴影（§4.2 末 + §4.4）+ §6.5 极淡边缘 + §4.5 墙钟接入 ⇒ **天平动不再恒 0**、需求 1 完整生效；`MoonPhase.kt` 加几何四件，`MoonlitRenderer` 落层 5c（复用 `Path`/`RectF`/`Paint`，`save/translate/rotate` + 三层 `drawPath`，⛔ 零 `clipPath` 零 `Matrix`）；新增 **G5 `MoonlitDtClockTest`**（10 例，含"判据跑在剥注释的源码上"的自证）与 **G12 `MoonPhaseShadowTest`**（14 例，用闭式判别式 `暗区/整盘 = (R+v)/(2R)` 证轴向）。新增偏差 **D11–D14**（弧度→度数、`save/scale`→两次 `arcTo`、§6.5 阈值 0.03→**0.06** 以原型 `:1098` 为准、几何落 `MoonPhase.kt` 且极淡边缘提前到 T5）；§4.2 末/§4.4/§6.5 正文同步补齐。实测 **1800 例 / 0 失败 / 0 错误**（T3 1756 → T4 1776 → T5 1800），`lintDebug` 无新增 error | **T1/T3/T4/T5 ✅（编译 + 单测 + lint 全绿，⛔ 未上机）**；⚠️ **T2 仍被 Q4/Q8/Q9 卡住**；下一个可开工 **T6**；§十三 U1–U7 + V1–V13 全 20 条判据未跑 |
| v1.3 | 2026-10-09 | **§十六 全部关闭 + T2（G4）落地**。**① 裁决**：所有者「Q4/Q8/Q9：将上限值调整合适，保证 HIGH 能体现所有效果，实际上 HIGH 不应该有上限 / Q7：选 3 / 其他问题按建议实施」⇒ `OVERDRAW_MAX = 3.45 / 3.95 / 不设上限`（旧 3.30/3.60/3.50 作废，含 `MAX_HIGH < MAX_MED` 的符号错与其"设计值当上游"的根，Q9 一并终结）；**三条杠杆一条都不写**（砍云 / `α<ε` 省屏 / 关暗角退水面保真），R12 由"严禁私自"升级为"不必砍"；`α<ε 早退`**仍作记账纪律移植**，但那条 0.3~0.5 屏估算**永久降级为未采纳备选**。**② Q7③ 落成 §6.6 确定性过境**（近层 `TRANSIT_INDEX=0` 一缕 `bandY` 钉为 `MOON_CY_K/HORIZON_K = 0.3125`，周期自然 = `1/speedK` ≈ 49~76 s，⛔ 不用原型的 `cloudPassT`，零新增 op/填充）⇒ 半遮态自此是**常规帧**，落表只能取半遮态那组。偏差新增 **D15**（`occlManual` 测不到 RIM：银边门槛是**逐斑** `back>0.34`，钉标量不改几何 ⇒ 改用驱动新增的 `passcost`）、**D16**（确定性过境不需要新调度器）。**③ T2 落码** `MoonOpBudget.kt`（15 元素 × ops/fill 两套三档系数 + native 堆，⛔ 零 Android/Compose import ⇒ 自建 `MoonLevel` 映射 `FxLevel`；几何常量必须**顶层 `const` 且声明在枚举之前**）+ `MoonOpBudgetTest` **22 例全绿**。表按条件项**上界**落 ⇒ ops **78/163/235**（余量 12/37/85）、fill **3.33904/3.83304/4.39504**、native **2,762,724 @1080p**。HIGH 的判据形态 = **单调方向 + 棘轮**（`≤ 4.306 × 1.10`），且门的存在性本身有断言（`isInfinite()` **且** `fillRatchetMax().isFinite()`）。五条负向自证（名义 300 条粼光 ⇒ 398、开颗粒 ⇒ 撞棘轮、全屏 pass 记 0 ⇒ 表掉到实测以下、旧上限 ⇒ 逐档红、圆盘按整屏方图烘 ⇒ 7,263,488）+ 一条**双向**判据 `表 ≥ 半遮态实测`。⚠️ 本表**无 `opsLegacy/fillLegacy` 列**（E44 不存在"改造前"形态），负向自证一律靠显式注入常量。**④ 两处新偏差**：**D17** MED `RIPPLE` 实测 **2 op / 0.017 屏**（原 `≤1 / 0.001` 会把表写成下界，同 D10 的错第二次犯）；**D18** native **破点在 1440p 而非 4K**（`STARFIELD = w·h` 单项 3,686,400 已越 3 M，圆盘被 `TEX_R_MAX` 钳住不背锅）⇒ 门里写明适用区间 ≤1080p 并放一条**破点哨兵**单测**断言它会破**，⛔ 未抬门槛。**⑤ 文档同步**：§9.1/§9.2 补 RIM 与 RIPPLE 实测行 + `TERMINATOR` 解析值行（原型七轮全在 `phaseLock:'full'` ⇒ `fillBy` 里根本没有它，按 `f→0` 最坏帧记 `0.062/1.25²×3 = 0.119`）+ 落表合计行；§9.3 整节按 as-built 重写；§9.4 颗粒理由改挂棘轮；R1/R3/G4 行与层序图同步 | **T1/T2/T3/T4/T5 ✅**（实测 **1822 例 / 0 失败 / 0 错误**（T5 的 1800 + 本批 22）、`lintDebug` 无新增 error，⛔ 未上机）；§十六 **Q1–Q9 全部关闭**；下一个可开工 **T6**；§十三 U1–U7 + V1–V13 全 20 条判据未跑，⛔ 原型从未证明 API 22 创维在 4.3 屏铺屏下的帧率（⇒ T10） |
| v1.4 | 2026-10-09 | **T6 落地：天空与海面本体（§3.2 层 1/2/10 + §3.3 色温 + G11）**。**① 色尺收敛为单一来源** `MoonSeascape.kt` 六件（`altitude`/`ALT_T`/`skyHorizon`/`SEA_SPLIT`/`sea`/`pack`），并把"⛔ 写第二份"钉成**结构判据**：渲染器源码里 `0xFFRRGGBB` 必须 **0 处**（`MoonlitSkySeaTest` 用例 ①），而 `MoonSeascape` 必含 `0xFF03050C`/`0xFF060B18` ⇒ §3.3+ 记录过的"两处各自漂移"从此有门。**② T6 接线扫描 13 例**（`MoonlitSkySeaTest`）：两块**不相交**矩形恰 2 次 `drawRect`（⛔ 合并成全屏矩形，P-1 崩溃规避）、`skyBrush`/`seaBrush` 各四件 + 海尺三档端点逐字、`Brush.verticalGradient(` 全文 2 处且**不在任何 `draw*` 体内**（补 `PerfBudgetContractTest` 不查 Brush 的洞，做法照 E43 `SeasideTest:103`）、暗角边色 = `Color(MoonSeascape.sea(0f, SB_NEUTRAL))` 整条声明正则 + `vignette = 0.42f` 字面量（该类用例 ④ 给正负双证：具名数值 ⇒ `numRe.none()`）、层序 sky→sea→starfield→disk→bloom→shadow、零 `clipPath(`/`clip(`/`RoundedCornerShape`。**③ 星野无裁剪实现**：1:1 **部分源区** blit（`ensureFullscreenOnly` ⛔ 非 `ensure()`、`sh = horizonY.toInt().coerceAtMost(tex.height)`、`BlendMode.Plus`、`STAR_A = 0.62f`），记账与 `MoonOpItem.STARS` 的 1 op / `MOON_HORIZON_FILL = HORIZON_K = 0.640` **恒等式对账**（用例 ⑥c）。**④ G11 阈值全部由 node 实测定死**（`logs_temp/g11_probe.js`/`g11_lcg.js`，⛔ 不是拍的）：真实星位四画幅 `dup 0 / maxCol ≤3 / maxRow ≤4 / distinct=n` ⇒ 钉 `≤2/≤6/≤6/==n`；两条负向自证缺一不可（位序坑夹具 `dup 195 / distinct 35` 判红；**纯 23×10 方阵 `dup` 恒 0 且 `distinct=n`** ⇒ 单度量必漏，三度量并用）；该类用例 ⑤ 的前进钩子扫含 `9E3779B9` 的行必须 `xor`（⚠️ 一律跑在**剥掉注释**的源码上，承 E43/G12 同类教训）。**⑤ 四条新偏差 D19–D22**：**D19** §3.1 书面把海水第三档写作不乘 `sb` 的 `#02040a`，原型 `seaC(t)` 三档一律乘 ⇒ 按函数实现（`sb=1` 逐位相同）；**D20** E44 星位来自共享 `ProceduralTexture.starLayout`（LCG，生产码**无 murmur**）⇒ G11 位序半段降级为"有牙夹具 + 前进钩子"；**D21** 原文"⛔ `ensureTiled()` 不能省"**被推翻**（`applyPostFx` 只在 `grain/scanline>0` 读平铺槽 `RendererFx.kt:107-108`，E44 两者皆 0 ⇒ 颗粒由棘轮裁决 `4.39504+1.00 > 4.7366`），反向由用例 ④b 一条扫描门钉住"不开颗粒且不调 `ensureTiled`"，**日后开颗粒必须同时恢复调用**；**D22** **层 3 月晕改判归 T7**（`haloA = haloBase·(1−0.75·occl)` 依赖 T7 的 `occl`，提前接只多钉一个刻意常量）。**⑥ 本机三坑入文**（写进 `MoonSeascape.kt` 文件头）：`0xFF03050C` 超 `Int` 范围被 Kotlin 判成 **Long** ⇒ 色值常数只能 `val …toInt()` 且 ⛔ 不能 `const`；负 `Int` 与正 `Long` 的 `==` **恒 false** ⇒ 测试期望值一律走 `argb(r,g,b)`；反引号测试名禁 `..`。**⑦ 文档同步**：§3.1 补 as-built 块（⚠️ 上屏天空地平档实测 `rgb(20,23,34)` = 尺在 `altT = 0.6875` 处的取值，⛔ 不是端点 `rgb(9,15,30)`）、§3.2 层 2 改"源区裁剪"与层 3 归属裁定、§3.0(f) 第 7 条按 D20 改口径、§10.2 G11 整行重写 + **新增 G13（天空与海面接线 / 色尺单一来源）**、§10.3 那条 `Brush.*Gradient(vararg)` 补洞标 ✅ 已落（用例 ②c）、§十一 T6 打勾 / T7 补层 3 与待替换常量清单 | **T0–T6 ✅**（新增 **29 例**：`MoonSeascapeTest` 11 + `MoonlitStarfieldTest` 5 + `MoonlitSkySeaTest` 13；全量 **1851 例 / 0 失败 / 0 错误 / skipped 0**，计数链 `1800 + 22(G4) = 1822 → +29 = 1851`；`lintDebug` `BUILD SUCCESSFUL in 7m 17s` 无新增 error，⛔ **未上机**）；下一个可开工 **T7 云场 + `occl`（需求 2 完成点）**；§十三 U1–U7 + V1–V13 全 20 条判据仍未跑，且低档色带 banding 与星野 `Plus` 实际亮度**只能上机判**（⇒ T10） |
| v1.5 | 2026-10-09 | **T7 落地：云场 + `occl` + 层 3 月晕 + §6.6 过境 + G3 ⇒ 需求 2 完成、需求 3 的同源性成立**。**① 状态与绘制分家**：新增 `MoonClouds.kt`（§六 全部数学：数量表 / 播种 / `layoutPuffs` / `step` / `occlusion` / 五个消费点 / 云色 / 晕规格 / 过境），⛔ **零 Android import**（`MoonOpBudget` 先例）—— 这是 G3 能跑**纯 JVM 单测**的前提，由 ⑬e 一条扫描门反向钉住；渲染器只调不写公式。**② 播种与几何与原型逐位对账**：`SEED = 20261008`、rng 调用次序照 §3.0(f) 第 6 条（改**次数**整场重排 ⇒ A/B 作废）、`MoonLcg` 与 JS float64 逐位一致 ⇒ ①② 用 `delta = 0.0`；斑沿脊站位定死 `(i+0.5)/11` ⇒ **切档只改读取上界**，尾部斑运行时字段原封不动（⑧，另比数组长度）。**③ 五个消费点接实数**：T6 的三个刻意常量（`DISK_A`/`BLOOM_A`/`DARK_EDGE_A`）删除并由 ⑬b 钉"不得复活"；`occl = 1` 端实测 `diskA 0.08 / bloomA 0.0128 / darkEdgeA 0 / haloA 0.25·base / reflA 0.0352 / glitA 0.003968`，⚠️ 末项**落在 `MIN_ALPHA` 之下** ⇒ 满遮帧的粼光走 `softEllipse` 入口自己就不提交；同源升级为**恒等式** `reflA(f,o)/reflA(f,0) ≡ diskA(o)`、`glitA(f,o,0)/glitA(f,0,0) ≡ diskA(o)²`（⑥b）。**④ §6.6 过境落地**：`TRANSIT_INDEX = 0` / `TRANSIT_BAND_Y = MOON_CY_K/HORIZON_K = 0.3125` / `TRANSIT_OFF = -1`（⚠️ 测试接缝，⛔ 生产恒为 `TRANSIT_INDEX`），只钉 `b.cy` ⇒ ⑩ 证 `w` **逐位不变**、`h` 差 **5 ulp**（舍入路径，非几何改动，故 `w` 用 `delta=0`、`h` 用相对容差）；`TRANSIT_EVERY_CYCLES` 作为"嫌太密"的**可选项未实现**，等 V14。**⑤ 层 3 月晕**（D22 兑现）：同心径向渐变 `1/2/3` 层，α 走 `haloBase·(1−0.75·occl)`，层序落在星野之后、月盘之前。**⑥ ⭐ 本轮最大事件是 D24 的更正**：定稿/Q7/R14/V8/V9/G3 行一致记载的"自然 `occl` 上限 ≈0.33"**被证伪** —— 根因是一个 `const minDim` **遮蔽**了 `vm` 沙箱全局的坏探针（缕尺寸按 0 算 ⇒ 整场云退化成一个点，均值低 **12 倍**）。按所有者裁决「**只改判据，不动观感**」+「**删掉，改由基线承载**」处置：⛔ **没有放松任何阈值**，改为**分布五项对账**（自然态 `peak 1 / mean 0.3059 / >0.5 占 26.0% / ≥0.999 占 1.71% / >0.001 占 69.1%`，过境态 `0.4618 / 46.9% / 9.98% / 81.7%` ⇒ 触顶时长 **5.8×**）；手调常数 `OCCL_NATURAL_MAX = 0.53` **删除**，五个数只住在基线文件；口径唯一真源改为 `docs/archive/verification/scripts/moonlit_cloud_golden.js`（`vm` 沙箱跑**原型原文**再再生 `MoonlitCloudBaseline.kt`，⛔ 不手抄公式 = 不拆哨兵），并加**两条防退化自检**（`minDim` 没进全局就抛、包络半轴全 0 就抛）。§3.0(e)/§6.2/§6.3 ③/§6.6/§9.2/§10.2/R14/V8/V9/§十六 Q7/§十一 T7 **十一处**同步更正，⚠️ Q7 的裁决**不重开**（买的是时长与周期性，不是"能不能吞月"）。**⑦ 三条新偏差**：**D23** 近层云色在渲染层按 `16×4×16` 分桶缓存 Shader（⛔ 逐斑现算 = 每帧 44 次 native 着色器分配），误差实测**生产可达域单通道 18 / 合计 46**、全立方 `22 / 51`（钉 `≤20/≤50`、`≤28/≤62`），⑫b 把刻度砍到 8 桶立刻**越线** ⇒ 16 不是随手挑；同批把 §6.1 那条 `(w, h, sizeBucket)` 缓存键规则**改为单位圆 shader + `canvas.scale`**（尺寸不进键）。**D24** 见上。**D25** §6.1 草图的 `Float`/不可变 data class ⇒ as-built `Double` + 可变 class + 定长 `Array(11)`（逐位对账要 float64；每帧重写 77 个字段不能靠新建对象）。**⑧ 门禁形态的一处升级**：T5 的 C4 原判据"`Paint(Paint.ANTI_ALIAS_FLAG)` 恰好 2 处"是**数量代理**，被本批正当新增的 `softPaint` 判红 ⇒ 升级为**位置判据**（只许出现在字段声明位）+ 新增 **C4b** 夹具负向自证（局部 `val framePaint` 必须被抓到），⛔ 改的是判据、不是多写的 Paint。**⑨ 本轮踩到的四个空转/假绿**：⑦ 的前提本身是错的（见 D24）；⑧ 把每帧推进的 `x` 算进"切档后逐位不变" ⇒ 摘掉 index 0 并加一条"确实在推进"的正对照；占比断言必须 `x.toDouble()/frames`（Int/Int 整除 ⇒ **恒 0 而判绿**）；判据一律跑在**剥掉注释**的源码上（渲染器 KDoc 原文引用了 `clipPath(` 这些被禁写法）。实测：新增 **29 例**（`MoonlitTest` 28 + `MoonPhaseShadowTest` C4b 1），全量 **1880 例 / 0 失败 / 0 错误 / skipped 0**，计数链 `1851 + 29 = 1880`；`lintDebug` `BUILD SUCCESSFUL`（0 新增 error）| **T0–T7 ✅**（编译 + 单测 + lint 全绿，⛔ **从未上机**）；需求 1（真实月相）与需求 2（云在动）已完整生效、需求 3 的**同源性**已有机器证据；下一个可开工 **T8 光柱 + 粼光（§七，需求 3 的水面那一半才真的画出来）**；§十三 U1–U7 + V1–V14 判据仍未跑，⛔ 云的**观感**（烟雾感、过境节奏 49~76 s 是否太密、银边只在半遮）只能上机判（⇒ T10） |
| v1.6 | 2026-10-09 | **T8 落地：光柱 + 粼光 + 地平带（§七）⇒ 需求 3 的水面那一半真的画出来了，且水面三行的账本从"手抄探针"改为"生产几何重算"**。**① 状态与绘制分家（承 `MoonClouds` / `MoonOpBudget` 先例）**：新增 `MoonWater.kt`（§7.2 光柱 + §7.3 粼光 + §7.6 地平带全部算术：`hash1/noise1/fbm1` 哈希三件套、`fyOf/dyRowOf/sway/hhOf/lenOf` 与无状态帧 `MoonGlitterFrame`、`gladeVisible/head*/fog*`、`BAND_*` + `bandDarkA/bandLitA/bandOcclBucket` + `horizonBandFillGeometry()`），⛔ **零 Android import**（由 G14 ⑧d 一条扫描门钉住 —— 这正是 G4 能纯 JVM 重算的前提）；另落 `whiteOf` 水面色尺（§7.3 结论 9「⛔ 不能用月盘色」）。渲染器层 7 **带 → 柱 → 划，整段排在云之后**，Brush/Shader 一律走**非 `DrawScope`** 的缓存入口（`bandBrushOf` / `headShaderOf` / `fogShaderOf` / `glitterShaderOf`）。**② ⭐ 本轮最大事件是四条落表口径修正（D26–D29）**：`HORIZON_BAND` 探针那行 `0.16·horizonK = 0.102` **自己算错**（真实矩形 `fillRect(0, horizonY−0.10h, W, 0.16h)` 通铺全宽且跨两侧 ⇒ **0.160 屏**，与画质档无关），REFLECTION/GLITTER 由解析几何与 4001 帧最坏扫描重算 ⇒ 合计 `3.33904/3.83304/4.39504 → **3.40204/3.90004/4.46104**`，三档余量从 `0.111/0.117` 缩到 **`0.048 / 0.050 / 棘轮 0.276`**（⛔ 反方向把实测锚抬 0.058 去找齐是**禁止**的：锚的价值就是"那台探针 printed 的数"）。**③ 三条纪律入文**：表 ≥ 实测且按 **3 位向上取整**（就近取整**当场被抓到三次**：`GLITTER` MED 与 `REFLECTION` LOW/MED ⇒ ③b/⑤ 各配一条"也不能多记千分之一屏"的反向上界）；口径必须写明**参照系**（粼光与画幅尺度无关、与 `density` **有关** ⇒ ④ 钉尺度、④b 钉密度矩阵，⛔ 不许合成一条"分辨率无关"）；负向自证必须**真的会塌**（①b `or` 替 `xor`、②b 均摊 `PER` ⇒ LOW ops `107 > 90`、⑥b 桶刻度砍半、④b 地板**确实**触发）。**④ ⚠️ 一条写进定稿的"不变量"被实测更正（D30）**：`hh/Δy` "下缘 ≈1.2" 只在 MED/HIGH 成立，LOW 下缘实测 **0.692**；远端比值最大（1.2~5.8）与中景永远 `<1` **都是设计**（前者是贴地平线的宽细闪带，后者才是暗槽的真正来源）。按通则「只改判据，不动观感」处置 ⇒ ⑨ 改钉**三档九点实测**，⛔ 不动 `ROWS` / `PERSP_K 1.70` / `GLITTER_W_K`（那三个数是原型七轮判读通过的组，且为 LOW 抬 `ROWS` 会改**整场分布**：argmax 换帧、fill 重算、低档铺屏上涨）。**⑤ `α < MIN_ALPHA(0.004)` 早退**保留为记账纪律，但 G14 ⑤b 查清了它的**实际作用域**：光柱存在门只在「**新月 ∧ 满遮**」角点闭合（`reflA(0,1)=0.002112` / `reflA(1,1)=0.0352`）⇒ **云遮月不会让水面变空**，⛔ 没改任何系数。**⑥ 门禁**：新增 **G14 `MoonlitWaterTest` 18 例**（§10.2）；G4 补 1 例 `地平带按探针口径落表 ⇒ 低于真实矩形面积`（22→**23**，钉 D26 的方向）；G13 ②c 的 `Brush.verticalGradient(` 处数 **2→3**（第三条**必须**落在按 `occl` 分桶的 `bandBrushOf`，⛔ 判据实质"任何 `draw*` 体内不得构造 Brush"一字未动，§10.3 同步）；§9.4 的颗粒负向自证在涨表后**结论更强**（`4.46104+1.00 = 5.46`，超棘轮 0.72 屏）。**⑦ 文档回填（T8e）**：§7.2 / §7.3 / §7.6 各补 as-built 块、§9.1 第 12 行更正口径、§9.2 三行与两行合计 + 判定行补"给 T10 的真实铺屏 **3.321 / 3.817 / 4.364**"、§10.2 G4/G13 行重写 + 新增 G14 行、§10.3、§十五 **D26–D30**、§十一 T8 打勾。⚠️ 代码侧只改**注释里的算数与出处**（`MoonOpBudget.kt` 的余量/增量数字与 D27 那句拆账的出处口径、`MoonWater.kt:62` 桶误差那句口径 —— 1001 点扫描值 `0.0015375` ⛔ 不能当解析上界 `0.0015625` 用），⛔ 无语义变更 ⇒ 全量 **1899** 沿用 T8d 那一轮，另复跑三套受影响的水面/接线门（18 + 23 + 13 = **54 例全绿**，`logs_temp/t8e_comment_check.log`）以证明注释改动没碰坏编译与判据。**⑧ 本机踩到的一处判据形态坑**：粼光填充与**表口径同帧**（`f=1 / occl=0 / aTreb=1` ⇒ `glitA = 0.775`，一条不剔），而 §7.3 结论 1 那句 MED 拆账 `21+13+12 ≈ 95` 是**抄错**（真实 `22/13/11 ⇒ 92`，**D27**）—— 表值本来没错，错的是拆账叙述，处置只改文档 + 把拆账**机器化**成 ②。实测：新增 **19 例**（G14 18 + G4 1），全量 **1899 例 / 0 失败 / 0 错误 / skipped 0**，计数链 `1880 + 19 = 1899`；`lintDebug` `BUILD SUCCESSFUL in 6m 56s`（0 新增 error，`logs_temp/t8d_full.log`） | **T0–T8 ✅**（编译 + 单测 + lint 全绿，⛔ **从未上机**，全部工作**未提交**）；需求 1/2/3 三条在代码里齐了（月相真实、云在动、云遮月时水面同帧变暗且光柱粼光由同一条几何算出）；下一个可开工 **T9 音频映射**（§八：`AudioSmoother.updateDt` 首个生产调用方 + `RIPPLE` 按档）；§十三 U1–U7 + V1–V14 判据仍未跑，⛔ 粼光实际可见度、`fog` 层数递减观感、**4.36 屏**真实铺屏下的帧率、`density ≥ 2.0` 设备的地板触发只能上机判（⇒ T10） |
| v1.7 | 2026-10-10 | **T9 落地 + T9e 回填：§八 音频映射接上（需求 4）⇒ `AudioSmoother.updateDt` 有了首个生产调用方，预算表两处口径改由生产几何重算，MED 上限由所有者二次裁决抬到 `4.00`**。**① 尺与绘制分家**：新增 `MoonAudio.kt`（§八 那七把尺全部纯函数：`seaBright ±10%` / `haloRadiusK ±6%` / `GLIT_A_SPAN +25%`（乘子住在 `MoonClouds.glitA` 里，本常量只做**对账锚**）/ `cloudSpdMul 0.85~1.25` / `rimBeatK ×1.25` / `vignetteA 0.42→0.36` + 两把刻度 `sbBucket 32` / `haloBucket 8`），⛔ 零 Android import ⇒ G4 与 G15 都能**直接调**它（⛔ 抄数 = **D10 / D24** 病根）。渲染器侧四个 `AudioSmoother`（`bass/mid/treble` 用默认 `(0.35, 0.06)`、`energy` 单独 `(0.28, 0.05)` 且系数住 `MoonAudio.ENERGY_*`，⛔ 不在绘制现场写魔数 —— 暗角是全屏叠加，包络快一档等于画面每拍眨一下），**每个每帧恰好推进一次**（状态量调两次 = 同一帧吃两遍衰减，G15 ①②）。**② 四条新偏差**：**D31** 单位 —— `FxFrame.dt` 由 `RendererFx.kt:205` 的 `dtMs / 1000f` 产出，**本身就是秒**，文档那句 `updateDt(_, fx.dt/1000)` 照抄等于喂 `1/60000 s` ⇒ `k = 1−(1−base)^(dt·60)` 在 `base 0.35` 下从 `0.35` 掉到 **`0.000431`**（每帧只走到正确量的 **1/813**），包络几乎不动，⚠️ **不崩、不红、不报错**，只读成"画面跟音乐没关系"（判据做成**双向**：渲染器内 ⛔ 不得再出现 `/ 1000`，**且** `RendererFx.kt` 那一处必须还在）；**D32** 方向 —— 原型 `lerp(AMP.vignHigh, AMP.vignLow, k)` 配 `vignHigh 0.36 / vignLow 0.42`，字面实现得到"越吵、暗角越**重**"，与 §八 该行明写的「高潮时略微"打开"画面」正相反（文档那句「⚠️ 参数名与方向易写反」正是为此而留，⛔ 实现时仍照抄错过）⇒ 取**文字意图**（单调递减，`aEner ≥ 0.714` 饱和）；**D33** 通道 —— `pulse` 是快起慢落包络（连续多帧为真）⇒ 一拍在队列里留**一串残影环**，槽位 `MED 2 / HIGH 4` 在"2 s 节拍 × ~1.8 s 寿命"下已近占空比饱和（**D17** 正是为此把 MED 从 `≤1` 更正为 2），再叠残影 = 把**事件**误记成**电平** ⇒ 改 `beat`（`pulse` **0** 次 / `beat` **2** 次 / 队列上限 `0/2/4` **== `RIPPLE.ops`**）；**D34** 口径 —— `bass` 一动**半径**就按**平方**收费 ⇒ HALO 行 = 中性 × `k²`（`k=1.036`、`k²=1.0733`）→ `0.075/0.141/0.262`；RIPPLE 的"最大可见半径"**不是**寿命终点 `p=1`，而是 α 恰好贴到 `MIN_ALPHA` 的那点 `ripplePMax() = 0.9312`（按 `p=1` 记会**多**记到 `0.08235`，那段一个像素都不铺 ⇒ ⛔ **上界也不能松**）。**③ ⭐ 表涨了就必须重裁**：D34 让三档合计 `+0.006 / +0.066 / +0.115` ⇒ **`3.40804 / 3.96604 / 4.57604`**，MED **越过** `3.95` ⇒ 所有者裁决「**抬 MED 上限到 4.00**」（⛔ 不把两行的数改小 = 假账、⛔ 不动 LOW 的 `3.45` 与 HIGH 的"不设绝对上限"、⛔ 不动实测锚 `3.263/3.759/4.306`）；G15 ⑮ 把"这次裁决**有后果**"写成断言 `med > 3.95f`，并逐条断言另外两端未动（`OVERDRAW_MAX_LOW == 3.45f`、`overdrawMax(HIGH).isInfinite()`）。§9.4 颗粒复算 `4.57604 + 1.00 = 5.58` 超棘轮 **0.84 屏** ⇒ "不开颗粒"的结论**只会更强**。**④ 零分配**：`bass` 动的两处都长在 `Brush` 里 ⇒ 海体渐变从字面 `seaBrush` 改按 `sb` **32 桶缓存** `seaBrushOf`（②c 新增"海体那条必须落在 `seaBrushOf`"且缓存写入 `seaBrushes[bucket] = built` 存在），晕按 `haloBucket` 8 桶缓存且**重建键不含桶号**（含了就退化成逐帧重建，⑧b）；`Brush.verticalGradient(` 处数**仍为 3**，实质判据一字未动（§10.3 同步）。**⑤ 分桶误差只认解析式**：`0.10/(2·32) = 0.0015625 ≤ 0.002`、`0.06/(2·8) = 0.00375 ≤ 0.005`，⑦ 各配一条"刻度砍半就越线"（16 桶 `0.003125` / 4 桶 `0.0075`）⇒ 桶数**收费**；⚠️ 演示"网格给不出上界"时取值必须与桶数**互质**（本门取 **997**；1001 点会恰好踩在 `0.125` 那类桶边界上、把误差做到**等于**上界 ⇒ 反例失效）。**⑥ 本轮唯一被门抓到的文档错**：`haloRadiusK` 的 KDoc 写 `[0.964, 1.036]`，G15 ④ 的端点断言判红 ⇒ 中性点 `0.4` 不是合法区间 `[0,1]` 的中点 `0.5`，真值域 **`[0.976, 1.036]`**（`aBass=0` 只到 −2.4%，要 −6% 得 `aBass = −0.2`，取不到）；**公式一字未动**，改的是注释，且 §9.2 按上沿 `1.036` 收费 ⇒ **账不受影响**。**⑦ 单真源纪律**：三档合计的具体数**只**钉在 `MoonOpBudgetTest:158-160` ⇒ G15 ⑮ / G13 ④b / §9.4 一律不再重抄（重抄就是第二份真源，改表时它先漂）。**⑧ 门禁与回填**：新增 **G15 `MoonlitAudioTest` 20 例**（§10.2，**夹具式**③⑨⑪⑫ 与**口径式**⑬⑭ 两类分工）；T9e 复核更正**七处门禁编号交叉引用**（`MoonAudio.kt:18/:128`、`MoonlitRenderer.kt:112/:467/:1486`、`MoonOpBudget.kt:141/:295` —— 四处 `G15 ⑧` 实为 ⑩/⑯b、一处指向并不存在的 `G15 ⑤b`、两处 `MoonlitAudioTest ④/⑥` 实为 ⑬/⑭），⛔ 只改引用不改事实。实测：全量 **1919 例 / 0 失败 / 0 错误 / skipped 0**，计数链 `1899 + 20 = 1919`；`testDebugUnitTest` + `lintDebug` `BUILD SUCCESSFUL in 4m 30s`（`logs_temp/t9d_full.log`；计数由**结果 XML** 汇总 `logs_temp/t9e_count.txt` —— ⚠️ 本机控制台是 GBK，中文测试名直接打印会 `UnicodeEncodeError`，⛔ 不拿控制台文本当计数源）；T9e 的七处注释改动后**复跑**：`assembleDebug + lintDebug` 全绿（`logs_temp/t9e_full.log`，5m 6s，`compileDebugKotlin` 重编但 `testDebugUnitTest` 判 **`UP-TO-DATE`** ⇒ 注释不进字节码、`.class` 逐位不变，那一轮的 1919 是**继承值**），另跑强制 `--rerun` 取**实测**（`logs_temp/t9e_tests_rerun.log`：**1919 例 / 0 失败 / 0 错误**，1m 6s） | **T0–T9 ✅**（编译 + 单测 + lint 全绿，⛔ **从未上机**，全部工作**未提交**）；四条需求在代码里齐了（月相真实 / 云在动 / 云遮月同帧变暗 / 律动克制）；下一个 **T10 真机验收**（归所有者，§十三 V/R/U 系列）与 **T11 文档回填**（`technical-overview.md` §10.220 + `visualizer-effects-list.md` E44 行 + `CHANGELOG`）；⛔ 只能上机判：六个幅度在**真实音乐**下的可读性、律动**手感**（G5 ③ 钉的是"乘子只作用在增量上"这个**形态**，不是"跟不跟得上拍"）、真实铺屏 **≈3.33 / 3.88 / 4.48 屏**下的帧率（⚠️ 已从 T8d 那句 `3.321/3.817/4.364` 更正，依据见 §9.2「判定」行）、`density ≥ 2.0` 设备的 `1.2·density` 地板（D29） |
| v1.8 | 2026-10-10 | **发布准备**（所有者：「版本升到 v2.38.6，然后本地提交，然后编译 release」）。**① 版本号** `2.38.5 / 174` → **`2.38.6 / 175`**（`app/build.gradle.kts:47-48`）。**② 本地提交两个**：`f697c93` = E44 全套 **39 文件 / +15543 行**（8 个生产文件 + 12 个测试类 + `MoonlitCloudBaseline` 再生基线 + 本文档 v1.7 + 原型 HTML + 4 个验证脚本 + 注册链 / `AGENTS.md` / 效果列表）；`69b7ed7` = `CHANGELOG` v2.38.6 五条。**⛔ 未推送、未打 tag**。**③ `assembleRelease` 出包**：`NASMusicTV-release-v2-38-6.apk` **25,521,094 字节（≈24.3 MiB）**，`BUILD SUCCESSFUL in 5m 10s`，R8 与 `lintVitalRelease` 全过；`aapt2 dump badging` 实测 `versionCode='175' versionName='2.38.6'`、三 ABI、五个 `uses-feature-not-required`（电视+手机口径未变）；`apksigner` 证书 `CN=NASMusicTV`（真签名，非 `ci-keystore` 兜底）；APK 内 `assets/globe/` 恒 **9** 文件含 `moon.jpg` 324,830 字节，⛔ 无 `node_modules` 泄漏。**④ ⚠️ 两处纪律被所有者的发布指示覆盖，必须留痕**：(a) `CHANGELOG` 落在 §十三 V 系列**之前**（原口径「未过 V 系列不得进 `CHANGELOG`」）⇒ **真机返工须回改该条目**；(b) `docs/technical-overview.md` 那条按所有者「等我测试完后再写」**故意不写**，草稿暂存 `logs_temp/v2386_10224_draft.md`（⚠️ 该目录不入库，回填后作废），写时**取当时候空闲编号** —— 初稿预期的 §10.220 已被 E29 真机回访占用，2026-10-10 的空闲号是 **§10.224**。**⑤ 顺带更正一处与代码矛盾的过期叙述**：`visualizer-effects-list.md` 的 E44 行原写「只落了注册链 + 夜空/海体底色，`MoonOpBudget` 被 Q4/Q8/Q9 卡住」⇒ 改为 T0–T9 全量 as-built，类头行号 `:61 → :104`，文档头版本同步 v2.38.6 / 175 | **T0–T9 ✅，已提交、已出包**（⛔ **仍从未上机**）；下一个 **T10 真机验收**（归所有者，V/R/U 全量未勾）；T11 只剩 `technical-overview.md` 一条（等 T10 之后写） |
