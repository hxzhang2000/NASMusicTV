package com.nasmusic.tv.visualizer.renderers

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.Easing
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerRenderer
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 「世界」（`VisualizerTheme.WORLD`）— 一颗会呼吸的星球。
 *
 * ## 视觉概念
 *
 * 深空里悬着一颗 Robinson 投影的世界地图。陆地是极淡的深蓝墨迹，32 座城市是发光的点，
 * 真实 UTC 晨昏线每 24h 绕地球转一圈、把夜侧压暗，而**主干 / 支线 / 毛细**三级航线
 * 像光丝一样在大陆之间生长。整体审美只有一条铁律：
 *
 * > **任意一帧单看都必须像一张海报。**
 *
 * 由此推出本文件全部的设计取舍：
 * - **无文字**。[WorldCity.name] / [WorldCity.pinyin] 只进单测报错信息，永不上屏 ——
 *   32 个汉字落在小光点上不可读，且任何标注都会把「海报」降格成「仪表盘」。
 * - **无交互**。本类不实现任何 pointer / click / zoom 回调，效果被规格定义为纯观赏。
 * - **无 3D / 无 shader / 无 framebuffer / 无高斯模糊**。辉光一律用「多层半透明描边 +
 *   径向渐变」伪造，与 [DnaRenderer] / [ConcentricGearsRenderer] 同款做法。
 *
 * ## 图层顺序（[draw] 内严格按此序，⛔ 不可调换）
 *
 * | # | 层 | 实现要点 |
 * |---|---|---|
 * | 1 | 近黑基底 | 整屏 `drawRect` |
 * | 2 | 径向纵深 | 中心 `#0A1220` → 透明，半对角线为半径，[ensureLayout] 缓存 |
 * | 3 | 暗角 vignette | 纯径向渐变，无模糊 |
 * | 4 | 颗粒 grain | 128/256px 预渲染 tile 位图平铺，**LOW 省略** |
 * | 5 | 星野 | 固定种子生成、极暗、41s 椭圆漂移 |
 * | 6 | 陆地填充 + 大气环 | 单条陆地 [Path]（[landFillPath]，⛔ 不切缝合），单一中性色；大气环是绕地图中心的径向渐变环 |
 * | 7 | 陆地描边 | **两层**：内层细亮线 + 外层宽淡辉光；按平滑 bass 极缓呼吸；⛔ 用另一套 [Path]（[landPath]）—— 切缝合 + 丢极点；单一中性色 |
 * | 8 | 昼夜晨昏线 | 96 条纵向色带，被 `landAll` 裁剪 ⇒ 只压暗陆地 |
 * | 9 | 城市光点 | 真·径向渐变光晕（32 支缓存 Brush）+ 1–2 层外晕 + 亮核 |
 * | 10 | 航线弧 | 每槽一条 [Path]，主干/支线/毛细 3 档线宽与亮度 |
 * | 11 | 行进光 + 彗尾 | 5 段渐隐 `drawLine`（宽淡 + 窄亮）+ 头部光点 |
 * | 12 | 涟漪 | 3 圈同心扩散环，颜色按 tier |
 *
 * 6–12 层全部处在**同一组 canvas 能量微缩放**之内（`save/translate/scale/translate/restore`，
 * 见 [draw]），所以半径 / 线宽 / 渐变几何一行都不用重算。
 *
 * ## 航线模型（本效果的核心机制）
 *
 * 每条航线是**两座真实城市之间的大圆**：
 * 1. 生成期（[buildArc]，每次 spawn 一次）把两端经纬度转成 3D 单位向量，沿**球面
 *    插值（slerp）**采样 [ARC_PTS] 个点，逐点反解回 (lon, lat) 存进 [arcLon] / [arcLat]。
 *    ⛔ 逐帧**不**重算 slerp —— 它与帧无关。
 * 2. 逐帧（[projectArcs]）只做「投影 + 弧高」：Robinson 投影后，在归一化 y 上加一个
 *    `lift × sin(π·t)` 的垂直隆起（[arcSinPiT] 预烘焙，逐帧零三角函数）。
 *    两端 `sin(0) = sin(π) = 0` ⇒ 起点终点严格落在城市光点上，隆起天然是「一条弓」。
 * 3. 绘制：头部下标 [fHead] 随年龄增长（[Easing.easeOutCubic]，起步快、收尾稳），
 *    折线从起点逐点延伸到头部 ⇒ 视觉上就是「一束光在飞过去」。
 * 4. 抵达：`to` 城市 visit-glow 拉满 + 播一圈涟漪 + 沿航线播一圈行进涟漪。
 *
 * 采样数 [ARC_PTS] = 56（规格 48–64 取中）。Robinson 的大圆弯曲在高纬被压扁，
 * 56 点在 1920×1080 上相邻投影间距 ≈ 10–18px，肉眼看不出折线棱角。
 *
 * ### 主干 / 支线 / 毛细（`WorldRouteClass`，ordinal 即粗细）
 *
 * | 等级 | 线宽系数 | 亮度系数 | 弧高系数 | 寿命 |
 * |---|---|---|---|---|
 * | TRUNK | 1.00 | 1.00 | 1.00 | 14–22s |
 * | REGIONAL | 0.66 | 0.72 | 0.62 | 8–14s |
 * | FEEDER | 0.44 | 0.50 | 0.38 | 5–9s |
 *
 * 线宽/亮度倍率取自 [WorldNetwork.arcBoost] / [WorldNetwork.trunkBrightness]；
 * 线宽系数是本文件自己定的一档更陡的曲线（1 : 0.66 : 0.44 ≈ 2.3 : 1.5 : 1），
 * 因为**线宽的感知比亮度更线性**，用亮度那档 1 : 0.72 : 0.5 看不出层级。
 * 寿命长 ⇒ 画面恒有一批长命的骨架航线，短时间内不会「全灭」。
 *
 * **端点配额**：[cityRoutes] + [cityCapOk] 让
 * [WorldCities.activeFlightRange] 那张表真的生效（Tier1 ≤8 / Tier2 ≤5 / Tier3 ≤3 /
 * Tier4 ≤1 条同时挂载）。这层配额是「欧美东亚密、非洲南美疏」的**第二重保险**：
 * 第一重是 [WorldNetwork] 的 `nearestHub` 偏置（决定航线跨不跨洲），第二重就是它
 * （决定叶端最多能被挂几条）。两层叠加后 Tier4 城市的画面永远只有一粒小点 + 最多
 * 一条细支线。
 *
 * ## 跨反子午线（±180°）的缝合处理
 *
 * 投影把经度压进 `[-180, 180]`，于是**任何跨日期变更线的折线都会在屏幕上「瞬移」**，
 * 朴素 `lineTo` 会把它连成一条横贯整张地图的直线。本文件有**两个**这样的断点，
 * 实测（`logs_temp/world_seam`，真实 `WorldMapData` LOD_MEDIUM + `WorldProjection`）：
 *
 * | 位置 | 规模 | 跳变 | 修法 |
 * |---|---|---|---|
 * | 南极洲环 #284（207 点） | 1 / 285 环 | 投影 x **426.8 px**（阈值 259.9） | 描边丢弃极点 + `moveTo` 断笔 |
 * | 大圆航线 | **3 / 24** 条 | 经度跨 ±180° | 逐段比 `arcLon`，`moveTo` 断笔 |
 *
 * - 陆地：填充与描边**故意走两条不同的路径**（[landFillPath] / [landPath]），
 *   完整推理（尤其是「为什么填充**不能**切开」）见 [rebuildLandPaths] 的 §填充。
 * - 航线：辉光与主芯共用同一条 [Path]，一次断笔两条都干净；彗尾的跨缝合段被跳过，
 *   头部光点的跨缝合瞬移是**正确的**（真航班就是从一侧出图、另一侧入图）。
 * - 昼夜层**天然没有这个问题**：它是 96 条纵向色带（[drawNightSide]），不画任何折线 ——
 *   当初选「色带」而不是「晨昏线多边形」正是为了躲开这个坑。
 *
 * ## 音频 → 视觉映射（分频段，⛔ 全部强平滑 + 硬幅值上限）
 *
 * | 输入 | 去向 | 幅度上限 |
 * |---|---|---|
 * | [AudioFrame.energy] | 整图微缩放（[MICRO_MIN]..[MICRO_MAX]） | **±3%**，硬钳 |
 * | [AudioFrame.bass] | 陆地描边呼吸、城市光点半径呼吸 | 描边宽度 ±8%、光点半径 ±22% |
 * | [AudioFrame.mid] | 航线弧高、生成速率 | 弧高 +45%、间隔 ÷1.6 |
 * | [AudioFrame.treble] | 彗尾亮度、光点外晕 | 彗尾 +30%、外晕 α +40% |
 * | [AudioFrame.sectionEnergy] | 段落「下沉 / 抬升」+ 并发航线数 | 目标 22 → 9 条 |
 * | [AudioFrame.bassRaw] | **只**喂 [BeatClassifier] | 见下 |
 *
 * ⛔ **为什么视觉映射用 `bass` 而节拍分级用 `bassRaw`**：见 [BeatClassifier] 的类 KDoc ——
 * `bass` 走峰值跟随归一化，鼓点瞬间恒等于 1.0，**强弱差异被完全抹平**；「让描边跟着
 * 强弱一起鼓」用 `bass` 恰好没问题（它已在 0..1），但要判「这一拍是 MID 还是 STRONG」
 * 必须用未归一化的 `bassRaw`。两者分工不同，不是笔误。
 *
 * **克制的实现方式**（三条同时成立才算数）：
 * 1. **强指数平滑**：系数来自 [WorldSensitivity.alpha]（标准档 0.075 ⇒ 30fps 下约
 *    0.43s 时间常数），远快于「一鼓一跳」所需，但慢于任何可见抖动。
 * 2. **硬幅值上限**：每个参数都有 `coerceIn` / `coerceAtMost` 的硬边界，代码里以
 *    常量名带 `MIN` / `MAX` / `CAP` 标出。
 * 3. **档位阶梯**：见下节，拍点只让画面「长一长」，不甩。
 *
 * ## 节拍阶梯（[BeatClassifier] + [applyBeat]）
 *
 * ```
 * WEAK   → Tier4 光点轻微闪烁（twinkle 标量，Tier 权重 {0.10,0.22,0.55,1.00}）
 * MID    → 生成 1 条 REGIONAL 航线
 * STRONG → 生成 1 条 TRUNK 航线 + 起点城市一圈涟漪
 * ```
 * 两道限流：共享冷却 [BEAT_SPAWN_COOLDOWN]（900ms）压掉连发；
 * 并发数硬钳在 `activeTarget × [BEAT_TARGET_SLACK]`（+25%）内，堵死「一串强拍炸出
 * 40 条航线」。[BeatClassifier] 自身已有 110ms 冷却，这里是第二道、更宽的闸。
 *
 * ## 焦点轮换
 *
 * 每 [FOCUS_MIN_MS]..[FOCUS_MAX_MS] 随机换一座 Tier1/Tier2 城市。切换**必须**是
 * 4 秒交叉淡入，绝不跳变 —— 做法是 [focusWeight]（32 个 float，恒在 0..1，逐帧向
 * 「当前焦点=1，其余=0」缓动，时间常数 [FOCUS_TAU] = 1.3s ⇒ 3τ = 3.9s 达 95%）。
 * 焦点城市同时拿到：更亮的光点、更粗的航线、更频繁的涟漪、更高的并发目标。
 * ⛔ [focusWeight] **只由 [updateFocus] 推进**，[ensureLayout] 绝不触碰。
 *
 * ## 静默 / 器乐段
 *
 * 航线**永不归零**：[activeTarget] 以 [SILENCE_FLOOR] = 0.42 为地板（22 → 9 条，
 * 仍 ≥ [WorldNetwork.MIN_ACTIVE_FLIGHTS] = 6）。地图与光点的呼吸另有两条**与音频无关**
 * 的自主正弦包络（[MICRO_BREATH] 9s、[LAND_BREATH] 13s），所以静音时画面依然活着。
 * 低能量段整图「下沉」（[SINK_GAIN] 0.012，把微缩放往 [MICRO_MIN] 拉），
 * 副歌「抬升」—— 只靠 8s 均值 [AudioFrame.sectionEnergy] 驱动，不看瞬时能量。
 *
 * ## 布局 / LOD
 *
 * - 地图**恒完整可见、恒不裁切、恒不畸变**：缩放由短边经
 *   [WorldProjection.mapScale] 求得，且先用 `safeAreaPx`（overscan 5%）扣边 ——
 *     `usable = minDim/2 − safeAreaPx`，`mapScale = (1−0.10) × usable`
 *   实测 1920×1080：`usable = 486` ⇒ `mapScale = 437.4`，地图 742.5 × 874.8 px，
 *   上下各余 102.6px、左右各余 588.8px —— 这就是「海报」的留白比例。
 *   1080×1920 竖屏 `minDim` 同为 1080 ⇒ `mapScale` **同值**，地图完整居中。
 *   Robinson 半宽 0.8487 < 半高 1.0，地图竖向更高，所以约束方恒是竖直方向 vs 短边。
 * - 画布尺寸 / 安全边 / LOD 任一变化才进 [ensureLayout]（重建陆地 [Path]、
 *   32 支光点 Brush、背景 3 支 Brush、线宽档）。⛔ 它**绝不**触碰 elapsed、UTC 时钟、
 *   任何音频平滑值、航线年龄、涟漪年龄、[focusWeight]、随机源状态 ⇒ 转屏 / 改窗口
 *   动画完全连续。
 * - LOD 按画质档：[lodFor] 用 `maxParticles` 阈值（≤0 粗 / ≤150 中 / 其余细），
 *   与 [WorldNetwork.maxActiveFlights] 同一套阈值口径。地图**只解码一次**
 *   （[ensureData] 调 `decodeWorld`，`WorldMapData` 内部按 lod 缓存同一实例），
 *   档位变化时重投影一次。
 *
 * ## 性能红线（draw 内零分配）
 *
 * - `Path` / `Stroke` / `Brush` / `ImageBitmap` / 所有数组**全是成员**，draw 内只读或
 *   `rewind()`。⛔ 特别地：Compose 1.6 的 `Stroke` 是**普通类**且 `width` 只读，
 *   每次 `drawPath(style = Stroke(w))` 都会**装箱一次** —— 故陆地描边宽度走
 *   [LAND_STROKE_BUCKETS] 档预先建好的实例，航线则宽度固定（只随布局变）、
 *   亮度随节拍变。
 * - 航线折线用 `drawLine(strokeWidth = …)` 的**直接参数**重载（`CanvasDrawScope`
 *   复用同一个 `strokePaint`），彗尾 8 段 × 34 条 ≈ 272 次调用全部零分配。
 * - `Offset` / `Color` 是 `@JvmInline value class`；⛔ 绝不装进 `List`，
 *   逐点坐标一律走预分配 `FloatArray` 载体（[arcSX] / [arcSY] / [citySX] / [citySY]）。
 * - 无 `List` 遍历、无捕获 lambda、无 `Triple`、无装箱、无字符串拼接、
 *   无逐帧 `Brush`/`PathMeasure`/`ImageBitmap` 构造。
 * - 池容量固定：航线 [MAX_FLIGHTS] = `WorldNetwork.MAX_ACTIVE_FLIGHTS` = 34、
 *   涟漪 [RIPPLE_MAX] = 24，运行期**永不增长**。
 *
 * ## ⚠️ 与原始规格的有意偏离
 *
 * 1. **不使用 `AudioRecord` / `Visualizer` API / `RECORD_AUDIO` 权限。**
 *    原规格设想让效果自带 `Visualizer` 采集。本项目**已有**一条完整的
 *    `SpectrumAnalyzer → AudioFrame` 分析层（分频段能量 / 节拍 / BPM / 8s 段落能量），
 *    且 v2.38 的一次重构**主动移除了** `RECORD_AUDIO`。若本效果另开一路采集，等于
 *    把刚拆掉的权限又装回来，且两路分析会互相污染。故：**复用 [AudioFrame]，零新增权限**。
 * 2. **灵敏度预设（安静 / 标准 / 激烈）只以常量实现，不做设置 UI。**
 *    三个档位定义在 [WorldSensitivity]，切换点是 [WorldRenderer.sensitivity] 一个字段。
 *    本效果被规格定义为**零交互**（无文字、无面板、无手势），加一个设置项会立刻破坏
 *    「任意一帧都是海报」的一致性。档位随时可接线：改一个字段即可。
 * 3. **地图是离线预烘焙的简化轮廓，不是运行时 GeoJSON 解析器。**
 *    `WorldMapData` 内嵌三档 Natural Earth 110m 轮廓的量化字符串（Douglas–Peucker
 *    简化到 0.55°/0.22°/0.08°）。运行时解析 GeoJSON 意味着冷启动前跑一遍 JSON 解析 +
 *    堆一整座中间对象图，而这份数据在 App 生命周期内**完全静态** —— 不值得。
 * 4. **minSdk 保持 22，未按规格建议提到 24。** 本文件用到的 API
 *    （`Bitmap` / `kotlin.math` 三角 / `System.currentTimeMillis`）在 API 22 上全部可用；
 *    规格里的 24 大概来自 `java.time`，而本实现**刻意不用**它（同 [WorldTerminator]
 *    的理由：minSdk 22 上不可用）。抬高 minSdk 会砍掉 Android 5.x 真机，不做。
 * 5. **大气辉光是绕地图中心的径向渐变环，不是严格贴着海岸线的气体层。**
 *    Robinson 地图的外轮廓是「赤道 ±0.8487 / 极点 ±1.0」的形状，圆形环必然在两极
 *    内切、在赤道外溢。用一圈极淡的径向渐变（[ATMO_RADIUS] = 1.06 半高）包住整张图，
 *    读作「星球大气」比逐点偏移更自然，也省掉每帧一次的重投影。
 * 6. **昼夜压暗用 96 条纵向色带（裁剪到陆地并集），不是解析式的晨昏线多边形。**
 *    晨昏线在 Robinson 下跨越 ±180° 时会在图上「横穿」一次，多边形需要拆成两块并处理
 *    NaN（极昼/极夜区无解）。本效果只要一个**极淡**的夜侧压暗，用赤道处的连续
 *    [WorldTerminator.sunFactor] 采样 96 条色带、步进 α ≈ 0.001，视觉上完全平滑。
 * 7. **陆地填充用 `FillType` 默认的 NonZero 叠加。** 简化后若出现「同向嵌套环」
 *    （莱索托之于南非这类飞地）会按并集填实；反之（环向不一致）会变成一个针孔大的空洞。
 *    取舍理由：面积影响 ≤ 单个飞地，且 NonZero 是唯一能同时处理「多岛一洲」的填法。
 */
class WorldRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.WORLD

    // ══ 灵敏度（唯一开关点）══════════════════════════════════════════════════

    /**
     * 当前灵敏度档。**这是全文件唯一的调参开关**：改这一个字段即可在
     * 安静 / 标准 / 激烈 之间切换，无需触碰任何绘制代码。
     */
    private var sensitivity = WorldSensitivity.STANDARD

    // ══ 配色（构造期一次；draw 只读）═══════════════════════════════════════

    /** ① 近黑基底：带一丝冷调（纯黑在 OLED 上会「发死」） */
    private val bgBase = Color(BG_BASE)

    /** ② 中心纵深色（极淡深灰蓝，把视线收到画面中心） */
    private val depthCore = Color(BG_CORE)

    /** ⑧ 夜侧压暗色：几乎纯黑，带一点蓝以免死黑 */
    private val nightMask = Color(NIGHT_COLOR)

    /** 星野两色（每 5 颗一颗偏蓝，与齿轮款同款） */
    private val starWhite = Color(0xFFA8BCD4)
    private val starBlue = Color(0xFF7488C0)

    /** 城市光点亮核：近白带一点点暖调（比纯白更像「人造光源」） */
    private val dotCore = Color(0xFFFFF3E0)

    /**
     * ⑥⑦ 陆地本色：**全文件唯一一支**，填充与描边共用（构造期一次，draw 只读）。
     *
     * 低饱和中性蓝灰。规格只要「只画陆地轮廓线，不填充实色」，「不同大洲**可用**极淡的
     * 不同色温」一直是**可选**的，而它已被**主动删掉**（理由见类 KDoc 偏离项 8）。
     * 本色**不带**自己的 α —— α 全部留在各层原有的低值常量上（填充 [LAND_FILL_ALPHA]、
     * 描边 [LAND_STROKE_ALPHA] / [LAND_GLOW_ALPHA]），逐帧只改 `alpha` 参数，
     * 色相与明度恒定，所以两层描边结构与宽度档一行都没动。
     */
    private val landColor = Color(LAND_INK)

    /** ⑥ 大气辉光本色（构造期一次；逐帧只改 α ⇒ 渐变几何永不变） */
    private val atmoColor = Color(ATMO_COLOR)

    /** ⑩ 航线本色（索引 = [WorldRouteClass].ordinal），亮度另由 [WorldNetwork.trunkBrightness] 调制 */
    private val routeColor: Array<Color> = arrayOf(
        Color(ROUTE_TRUNK),
        Color(ROUTE_REGIONAL),
        Color(ROUTE_FEEDER)
    )

    /** ⑫ 涟漪色（索引 = tier − 1），与城市光点同族但更冷 */
    private val rippleColor: Array<Color> = arrayOf(
        Color(0xFFFFE0B0),
        Color(0xFFBFE6FF),
        Color(0xFF9CC4E8),
        Color(0xFF7C93B4)
    )

    /**
     * [WorldRouteClass] 的 ordinal → 枚举实例表（构造期建一次）。
     * ⛔ 存在的唯一理由：`values()` **每次调用都新建一个数组** —— 逐帧调 34 次
     * 就是 34 次分配，直接踩爆「draw 内零分配」红线。
     */
    private val routeClassArr: Array<WorldRouteClass> = WorldRouteClass.values()

    /**
     * 每座城市**当前作为端点**的活跃航线数（[WorldCities.activeFlightRange] 的执行体）。
     *
     * 有了它 [WorldCities.activeFlightRange] 那张表才真的生效：Tier4 最多同时挂 1 条
     * 支线（它的光点只有 1.6px，两条支线叠上去就糊成一团），Tier1 最多 8 条（骨干
     * 本来就该是所有航线的汇聚点）。没有这张计数，「末端节点很稀疏」只能靠
     * [WorldNetwork] 的 `nearestHub` 偏置间接实现，密度仍会偏满。
     */
    private val cityRoutes = IntArray(WorldCities.COUNT)

    /**
     * ⑨ 每座城市的「到访辉光」（0..1）：航线抵达时拉满，之后线性衰减。
     * 与 [focusWeight] 一样**只由动画推进**，[ensureLayout] 绝不触碰。
     */
    private val visitGlow = FloatArray(WorldCities.COUNT)

    // ══ 路径（成员；draw 内只 rewind + lineTo）═══════════════════════════════

    /**
     * ⑦ 陆地**描边**路径，**单条**（构造期建好永不再建）。
     *
     * 数据源是纯自然陆地层（Natural Earth 110 m land），所以这条路径里只有**海岸线**，
     * 一个内部国界都没有 —— 「按大洲分组成 7 条路径」的时代已随分洲染色一起结束。
     *
     * ⛔ 与 [landFillPath] **刻意不同**：本路径**丢弃退化极点点**、并在跨 ±180° 缝合处
     * 用 `moveTo` 断笔（见 [rebuildLandPaths] 的完整推理）。只有描边需要断笔。
     */
    private val landPath = Path()

    /**
     * ⑥ 陆地**填充**路径，**单条**（全部海岸线 + 岛屿，与 [landAll] 同几何）。
     *
     * ⚠️ **这里故意不切缝合、故意保留极点上的点。** 推理见 [rebuildLandPaths]：
     * 南极洲外环在 lat = −90 处从 lon 180 连到 lon −180，在 Robinson 里那**不是**退化弦，
     * 而是地图最下边缘上一条真实存在的有限线段 —— 闭合它得到的多边形**正是正确的
     * 南极形状**。把填充也切开反而会得到一个假的「楔形空洞」。实测（真实
     * `WorldMapData` LOD_MEDIUM，自然陆地层）：含极点点的那个南极外环承担了南极洲
     * 内部的绝大部分面积，其余南极环加起来不到 1,000 px²（都是小岛）—— 一旦把它
     * 排除出填充，**整个南极洲的填充都会消失**。所以：断笔只作用于描边。
     */
    private val landFillPath = Path()

    /** ⑧ 全部陆地环的并集（单条 [Path]），只作 `canvas.clipPath` 的裁剪区（几何 = 填充几何） */
    private val landAll = Path()

    /** ⑩ 每个航线槽一条 [Path]（逐帧 `rewind()` 后按头部下标重建折线） */
    private val routePath: Array<Path> = Array(MAX_FLIGHTS) { Path() }

    /**
     * [rebuildLandPaths] 的陆地点投影暂存（[0]=x、[1]=y，逐点）。
     *
     * 为什么需要它：描边必须**先投影完整个环、发现缝合跳变之后**才知道该不该断笔，
     * 而填充要的是「未过滤的整环」。一趟投影同时喂两件事，只此一块缓冲即可，
     * 免掉第二趟投影（LOD_FINE 8,741 点 × 2 = 约 70KB 常驻成员内存）。
     * ⛔ 只在 [ensureLayout] 路径上按需扩容，`draw` 内只读不写不分配。
     */
    private var landScratch = FloatArray(0)

    // ══ 描边实例（⛔ 绝不在 draw 内 new，见类 KDoc「性能红线」）═════════════

    /**
     * ⑦ 陆地描边**宽度档**（[LAND_STROKE_BUCKETS] 个实例）。
     * ⛔ 存在的唯一理由：Compose 1.6 的 `Stroke` 是普通类且 `width` 只读，
     * 每次 `drawPath(style = …)` 都要装箱。改用 5 档预建实例按平滑 bass 选档，
     * 档间 4% 的宽度差在任何分辨率下都不可见。
     */
    private val landStroke: Array<Stroke> = Array(LAND_STROKE_BUCKETS) { Stroke(1f) }

    /** ⑦ 陆地描边外层宽而淡的辉光（同样分档） */
    private val landGlowStroke: Array<Stroke> = Array(LAND_STROKE_BUCKETS) { Stroke(1f) }

    /** ⑩ 航线线宽实例（索引 = [WorldRouteClass].ordinal；两层：外辉光 + 内芯） */
    private val routeStroke: Array<Stroke> = Array(ROUTE_CLASSES) { Stroke(1f) }
    private val routeGlowStroke: Array<Stroke> = Array(ROUTE_CLASSES) { Stroke(1f) }

    // ══ 星野（固定种子生成一次 → 零闪烁）═════════════════════════════════════

    private val starX = FloatArray(STAR_MAX)
    private val starY = FloatArray(STAR_MAX)
    private val starR = FloatArray(STAR_MAX)
    private val starA = FloatArray(STAR_MAX)
    private val starPh = FloatArray(STAR_MAX)
    private var starCount = 0

    // ══ 颗粒（唯一重资源；onExit 显式回收）══════════════════════════════════

    private var grainBitmap: ImageBitmap? = null
    private var grainTilePx = 0

    // ══ 城市屏幕坐标（ensureLayout 一次投影，逐帧只读）═══════════════════════

    private val citySX = FloatArray(WorldCities.COUNT)
    private val citySY = FloatArray(WorldCities.COUNT)

    // ══ 光点光晕 Brush（32 支，ensureLayout 一次重建）═══════════════════════

    private val dotBrush: Array<Brush?> = arrayOfNulls(WorldCities.COUNT)
    private val dotGlowR = FloatArray(WorldCities.COUNT)

    // ══ 航线池（SoA，容量恒 [MAX_FLIGHTS] = 34）═════════════════════════════

    private val fActive = BooleanArray(MAX_FLIGHTS)
    private val fFrom = IntArray(MAX_FLIGHTS)
    private val fTo = IntArray(MAX_FLIGHTS)
    private val fKlass = IntArray(MAX_FLIGHTS)
    private val fAge = FloatArray(MAX_FLIGHTS)
    private val fDur = FloatArray(MAX_FLIGHTS)
    private val fGrow = FloatArray(MAX_FLIGHTS)
    private val fAlpha = FloatArray(MAX_FLIGHTS)
    private val fLift = FloatArray(MAX_FLIGHTS)
    private val fWeight = FloatArray(MAX_FLIGHTS)
    private val fHead = IntArray(MAX_FLIGHTS)
    private val fArrived = BooleanArray(MAX_FLIGHTS)

    /** 大圆采样（生成期定，逐帧不变）：经度，度。容量 [MAX_FLIGHTS] × [ARC_PTS] */
    private val arcLon = FloatArray(MAX_FLIGHTS * ARC_PTS)

    /** 大圆采样（生成期定，逐帧不变）：纬度，度 */
    private val arcLat = FloatArray(MAX_FLIGHTS * ARC_PTS)

    /** 大圆采样（逐帧重投影）：屏幕 x，px */
    private val arcSX = FloatArray(MAX_FLIGHTS * ARC_PTS)

    /** 大圆采样（逐帧重投影）：屏幕 y，px */
    private val arcSY = FloatArray(MAX_FLIGHTS * ARC_PTS)

    private var flightCount = 0

    /**
     * 弧高包络 `sin(π·t)`，构造期烘焙（[ARC_PTS] 个）。
     * ⛔ 逐帧用零三角函数 —— 这是本效果能每秒省下 ~1200 次 `sin` 的关键。
     * 端点 `sin(0) = 0`、`sin(π) ≈ 1.2e-16` ⇒ 弧线严格起于起点、止于终点。
     */
    private val arcSinPiT = FloatArray(ARC_PTS) { sin(PI_F * it / (ARC_PTS - 1f)) }

    // ══ 涟漪池（SoA，容量恒 [RIPPLE_MAX]）═══════════════════════════════════

    private val rActive = BooleanArray(RIPPLE_MAX)
    private val rCity = IntArray(RIPPLE_MAX)
    private val rOnRoute = BooleanArray(RIPPLE_MAX)
    private val rSlot = IntArray(RIPPLE_MAX)
    private val rT = FloatArray(RIPPLE_MAX)
    private val rAge = FloatArray(RIPPLE_MAX)
    private val rDur = FloatArray(RIPPLE_MAX)
    private val rTier = IntArray(RIPPLE_MAX)
    private val rAmp = FloatArray(RIPPLE_MAX)
    private var rippleHead = 0

    // ══ 焦点（⛔ ensureLayout 绝不触碰）═════════════════════════════════════

    private val focusWeight = FloatArray(WorldCities.COUNT)
    private var focusCity = -1
    private var focusSwapAtMs = 0L
    private var focusLevel = 0f

    // ══ 逐帧状态（⛔ ensureLayout 绝不触碰）═══════════════════════════════════

    private var lastMs = 0L
    private var lastFrameMs = 0L
    private var lastUtcCheckMs = 0L

    /** 统一动画时钟（秒，Double，逐帧 dt 累加 —— ⛔ 禁 `nowMs × 速率`） */
    private var elapsed = 0.0

    /** 真实 UTC 毫秒（供 [WorldTerminator]）：onEnter 用墙钟播种，逐帧 dt 推进 */
    private var utcMs = 0L

    private var bassS = 0f
    private var midS = 0f
    private var trebleS = 0f
    private var energyS = 0f
    private var sectionS = 0f

    /** 平滑帧时（毫秒），喂 [WorldNetwork.maxActiveFlights] 做帧率降级 */
    private var frameMsAvg = NOMINAL_FRAME_MS

    /** WEAK 拍点触发的光点闪烁包络（0..1，指数衰减） */
    private var twinkle = 0f

    private var spawnAccum = 0f
    private var beatCooldown = 0f
    private var activeTarget = DEFAULT_ACTIVE_FLIGHTS

    /** 画质档 0/1/2（0 = LOW） */
    private var tier = 1

    private val beatClassifier = BeatClassifier()

    /** 航线随机源（时间派生种子 ⇒ 每次进效果一套全新航线；⛔ 绝不用 `kotlin.random`） */
    private val rnd = WorldRng()

    // ══ 布局缓存（只由 ensureLayout 写）═════════════════════════════════════

    private var layoutW = -1f
    private var layoutH = -1f
    private var layoutSafe = -1f
    private var mapScale = 0f
    private var centerX = 0f
    private var centerY = 0f
    private var mapL = 0f
    private var mapR = 0f
    private var mapT = 0f
    private var mapB = 0f
    private var dataLod = -1
    private var land: WorldLandmass? = null
    private var bgDepthBrush: Brush? = null
    private var vignetteBrush: Brush? = null
    private var atmoBrush: Brush? = null
    private var atmoRadius = 0f

    // ══ 生命周期 ═══════════════════════════════════════════════════════════

    override fun onEnter(ctx: RenderContext) {
        lastMs = 0L
        lastFrameMs = 0L
        lastUtcCheckMs = 0L
        elapsed = 0.0
        // 真实 UTC：墙钟播种，之后逐帧 dt 推进。⛔ 不用 ctx.nowMs —— 它在
        // VisualizerStage 三个调用点语义不一致（有的给墙钟、有的给单调钟），
        // 语义不明的时钟会让晨昏线停转或乱转。
        utcMs = System.currentTimeMillis()

        bassS = 0f
        midS = 0f
        trebleS = 0f
        energyS = 0f
        sectionS = 0f
        frameMsAvg = NOMINAL_FRAME_MS
        twinkle = 0f
        spawnAccum = 0f
        beatCooldown = 0f
        activeTarget = DEFAULT_ACTIVE_FLIGHTS
        focusLevel = 0f
        rippleHead = 0

        var i = 0
        while (i < WorldCities.COUNT) {
            focusWeight[i] = 0f
            visitGlow[i] = 0f
            cityRoutes[i] = 0
            i++
        }
        var r = 0
        while (r < RIPPLE_MAX) {
            rActive[r] = false
            r++
        }
        var s = 0
        while (s < MAX_FLIGHTS) {
            fActive[s] = false
            fAge[s] = 0f
            fAlpha[s] = 0f
            routePath[s].rewind()
            s++
        }

        tier = tierFor(ctx.quality)
        val lod = lodFor(ctx.quality)
        ensureData(lod)
        layoutW = -1f // 强制 ensureLayout 首帧重建
        buildStars()

        // 初始焦点：立刻给一座，避免开场 20s 内没有任何城市被特别对待
        focusCity = pickFocusCity()
        focusWeight[focusCity] = 1f
        focusLevel = 1f
        focusSwapAtMs = utcMs + focusDelayMs()
    }

    override fun onExit() {
        releaseGrain()
    }

    // ══ 绘制 ═══════════════════════════════════════════════════════════════

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return

        // ── 时钟：逐帧 dt 累加（⛔ 禁 `nowMs × 系数`，见类 KDoc）──────────────
        val now = ctx.nowMs
        if (lastMs == 0L) lastMs = now
        val dtMs = (now - lastMs).coerceIn(0L, 100L)
        lastMs = now
        val dt = dtMs / 1000f
        elapsed += dt.toDouble()
        advanceUtc(dtMs)

        // ── 帧时（供 WorldNetwork 帧率降级；由 frame.timeMs 差分）───────────
        if (lastFrameMs == 0L) lastFrameMs = frame.timeMs
        val rawMs = frame.timeMs - lastFrameMs
        lastFrameMs = frame.timeMs
        if (rawMs > 0L && rawMs < 200L) {
            frameMsAvg += ((rawMs.toFloat()) - frameMsAvg) * FRAME_MS_EMA
        }

        // ── 音频：强指数平滑（系数来自灵敏度档）+ 各自信噪门 ────────────────
        val a = sensitivity.alpha
        val gate = sensitivity.gain
        bassS += (unit(frame.bass) - bassS) * a
        midS += (unit(frame.mid) - midS) * a
        trebleS += (unit(frame.treble) - trebleS) * a
        energyS += (unit(frame.energy) - energyS) * a
        // 段落能量：source 已是 8s 均值，再叠一层极慢 EMA（≈3s @30fps）只取「更慢」的方向
        sectionS += (unit(frame.sectionEnergy) - sectionS) * SECTION_EMA

        // ── LOD / 数据（⛔ 地图只解码一次，实例由 WorldMapData 内部缓存）──
        ensureLayout(w, h, ctx.minDim, ctx.safeAreaPx, ctx.quality)

        // ── 焦点轮换（⛔ 唯一改 focusWeight 的地方）──────────────────────────
        updateFocus(utcMs, dt)

        // ── 节拍：分级只喂 BeatClassifier，视觉层另用 treble 抖动 ────────────
        val strength = beatClassifier.update(frame.bassRaw, frame.timeMs, frame.beat)
        if (strength == BeatStrength.WEAK) {
            twinkle = 1f
        } else {
            twinkle -= dt * TWINKLE_DECAY
            if (twinkle < 0f) twinkle = 0f
        }

        // ── 目标并发数（静默段降到 9，但永不为 0）────────────────────────────
        val cap = WorldNetwork.maxActiveFlights(ctx.quality.maxParticles, frameMsAvg)
        val density = SILENCE_FLOOR + (1f - SILENCE_FLOOR) * sectionS
        val want = (DEFAULT_ACTIVE_FLIGHTS * density *
            (1f + FOCUS_TARGET_GAIN * focusLevel) * spawnGain()).roundToInt()
        activeTarget = want.coerceIn(WorldNetwork.MIN_ACTIVE_FLIGHTS, cap)

        // ── 推进 + 生成 + 投影（顺序固定：年龄 → 到访衰减 → 涟漪 → 生成 → 投影）─
        advanceFlights(dt)
        decayVisits(dt)
        advanceRipples(dt)
        if (beatCooldown > 0f) beatCooldown -= dt
        applyBeat(strength)
        topUp(dt, cap)
        evictExcess()
        projectArcs()

        // ══ ①–⑤ 背景空间感（整屏底层，画在能量微缩放之外）═════════════════
        drawRect(bgBase)
        val depth = bgDepthBrush
        if (depth != null) drawRect(depth)
        val vig = vignetteBrush
        if (vig != null) drawRect(vig)
        drawGrain()
        drawStars(w, h)

        // ══ ⑥–⑫ 地图本体（同一组能量微缩放之内）══════════════════════════
        val breathPh = ((elapsed % MICRO_PERIOD) / MICRO_PERIOD * TAU_D).toFloat()
        val landPh = ((elapsed % LAND_BREATH_PERIOD) / LAND_BREATH_PERIOD * TAU_D).toFloat()
        val sink = SINK_GAIN * (1f - sectionS)
        val micro = (1f + MICRO_GAIN * energyS * gate +
            MICRO_BREATH * sin(breathPh) - sink)
            .coerceIn(MICRO_MIN, MICRO_MAX)

        val cvs = drawContext.canvas
        cvs.save()
        cvs.translate(centerX, centerY)
        cvs.scale(micro, micro)
        cvs.translate(-centerX, -centerY)

        // ⑥ 大气辉光（最外圈，压在陆地之下）
        val atmo = atmoBrush
        if (atmo != null) {
            drawCircle(atmo, atmoRadius, Offset(centerX, centerY), alpha = atmoAlpha(energyS, landPh, gate))
        }

        // ⑥ 陆地填充：单条 landFillPath，深蓝极淡。
        //    ⛔ 用 landFillPath（未切缝合、含极点）—— 见 rebuildLandPaths 的 §填充 推理。
        val landFillA = (LAND_FILL_ALPHA + LAND_FILL_ENERGY * energyS * gate)
            .coerceAtMost(LAND_FILL_ALPHA_MAX)
        drawPath(landFillPath, landColor, alpha = landFillA)

        // ⑧ 昼夜：96 条纵向色带，被陆地并集裁剪 ⇒ 只压暗夜侧的陆地
        val dec = WorldTerminator.subsolarLat(utcMs)
        drawNightSide(dec, utcMs, landPh)

        // ⑦ 陆地描边：内层细亮 + 外层宽淡（按平滑 bass 选宽度档），单一中性色
        val bw = 1f + (LAND_BREATH * bassS * gate + LAND_BREATH_DRIFT * sin(landPh))
        val bIdx = ((bw - (1f - LAND_BREATH_BUCKET)) / (2f * LAND_BREATH_BUCKET) * (LAND_STROKE_BUCKETS - 1) + 0.5f)
            .toInt().coerceIn(0, LAND_STROKE_BUCKETS - 1)
        val glowA = LAND_GLOW_ALPHA * (0.82f + 0.18f * sin(landPh))
        val lineA = LAND_STROKE_ALPHA + LAND_STROKE_ENERGY * bassS * gate
        drawPath(landPath, landColor, alpha = glowA, style = landGlowStroke[bIdx])
        drawPath(landPath, landColor, alpha = lineA, style = landStroke[bIdx])

        // ⑨ 城市光点（真·径向渐变光晕 + 外晕 + 亮核）
        drawCities(dec, utcMs)

        // ⑩⑪⑫ 航线折线 → 行进光/彗尾 → 涟漪
        drawRoutes(trebleS, gate)
        drawRipples()

        cvs.restore()
    }

    // ══ ⑤ 星野 ═══════════════════════════════════════════════════════════

    private fun DrawScope.drawStars(w: Float, h: Float) {
        if (starCount == 0) return
        val t = ((elapsed % STAR_DRIFT_PERIOD) / STAR_DRIFT_PERIOD * TAU_D).toFloat()
        val pulse = 0.88f + 0.12f * sin(((elapsed % STAR_PULSE_PERIOD) / STAR_PULSE_PERIOD * TAU_D).toFloat())
        val dx = STAR_DRIFT * w
        val dy = STAR_DRIFT * h
        var s = 0
        while (s < starCount) {
            val ph = starPh[s]
            drawCircle(
                color = if ((s % 5) == 0) starBlue else starWhite,
                radius = starR[s] * minOf(w, h),
                center = Offset(
                    starX[s] * w + cos(t + ph) * dx,
                    starY[s] * h + sin(t + ph) * dy
                ),
                alpha = starA[s] * pulse,
                blendMode = BlendMode.Plus
            )
            s++
        }
    }

    private fun buildStars() {
        // 固定种子（⛔ 不用 rnd）：星野要的是「每次进入都一样」而非「每次都不一样」，
        // 转屏 / 交叉淡入时两层星点重合反而是好事（同相位）。
        var sr = 0x5EEDF00Du
        var s = 0
        while (s < STAR_MAX) {
            sr = sr * 1664525u + 1013904223u
            starX[s] = 0.03f + (sr shr 8).toFloat() / 16777216f * 0.94f
            sr = sr * 1664525u + 1013904223u
            starY[s] = 0.03f + (sr shr 8).toFloat() / 16777216f * 0.94f
            sr = sr * 1664525u + 1013904223u
            starR[s] = STAR_R_MIN + (sr shr 8).toFloat() / 16777216f * (STAR_R_MAX - STAR_R_MIN)
            sr = sr * 1664525u + 1013904223u
            starA[s] = STAR_A_MIN + (sr shr 8).toFloat() / 16777216f * (STAR_A_MAX - STAR_A_MIN)
            sr = sr * 1664525u + 1013904223u
            starPh[s] = (sr shr 8).toFloat() / 16777216f * TAU_F
            s++
        }
        starCount = when (tier) {
            0 -> STAR_LOW
            1 -> STAR_MED
            else -> STAR_MAX
        }
    }

    // ══ ④ 颗粒 ═══════════════════════════════════════════════════════════

    private fun buildGrainTile(tile: Int) {
        releaseGrain()
        val px = IntArray(tile * tile)
        var g = 0x9E3779B9u
        var i = 0
        while (i < px.size) {
            g = g * 1664525u + 1013904223u
            val r = (g shr 8).toFloat() / 16777216f
            px[i] = ((r * r * 255f).toInt().coerceIn(0, 255) shl 24) or 0x00FFFFFF
            i++
        }
        val bmp = Bitmap.createBitmap(tile, tile, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, tile, 0, 0, tile, tile)
        grainBitmap = bmp.asImageBitmap()
        grainTilePx = tile
    }

    private fun releaseGrain() {
        try {
            grainBitmap?.asAndroidBitmap()?.recycle()
        } catch (_: Exception) {
            // recycle 在某些 ROM 上会抛 IllegalStateException（已被 GC 回收）；忽略
        }
        grainBitmap = null
        grainTilePx = 0
    }

    private fun DrawScope.drawGrain() {
        val bmp = grainBitmap ?: return
        val tile = grainTilePx
        if (tile <= 0) return
        val a = if (tier >= 2) GRAIN_ALPHA_HIGH else GRAIN_ALPHA_MED
        var y = 0f
        while (y < size.height) {
            var x = 0f
            while (x < size.width) {
                drawImage(bmp, dstOffset = IntOffset(x.roundToInt(), y.roundToInt()), alpha = a, blendMode = BlendMode.SrcOver)
                x += tile
            }
            y += tile
        }
    }

    // ══ ⑧ 昼夜 ═══════════════════════════════════════════════════════════

    /**
     * 夜侧压暗。[NIGHT_STRIPS] = 96 条纵向色带，α 由赤道处的连续
     * [WorldTerminator.sunFactor] 给出；整段用 [landAll] 裁剪 ⇒ 天空与海洋不受影响。
     *
     * ⚠️ 用赤道值代表整条纬线是**有意的简化**：晨昏线在高纬会显著弯折
     * （[WorldTerminator.terminatorLonAt] 在极昼/极夜区甚至无解），而本层只是
     * 「极淡的夜侧压暗」，不值得为它做多边形裁剪。96 条色带在 742px 宽的地图上
     * 每条 7.7px，α 步进 ≈ 0.001 ⇒ 完全看不出台阶。
     */
    private fun DrawScope.drawNightSide(dec: Float, utc: Long, landPh: Float) {
        val base = NIGHT_ALPHA * (0.90f + 0.10f * sin(landPh))
        val sw = (mapR - mapL) / NIGHT_STRIPS
        val sh = mapB - mapT
        val cvs = drawContext.canvas
        cvs.save()
        cvs.clipPath(landAll, ClipOp.Intersect)
        var j = 0
        var x = mapL
        while (j < NIGHT_STRIPS) {
            val lon = -180f + 360f * (j + 0.5f) / NIGHT_STRIPS
            val night = 1f - WorldTerminator.sunFactor(lon, 0f, dec, utc)
            if (night > 0.004f) {
                drawRect(nightMask, Offset(x, mapT), Size(sw + 1f, sh), alpha = base * night)
            }
            x += sw
            j++
        }
        cvs.restore()
    }

    // ══ ⑨ 城市光点 ═════════════════════════════════════════════════════════

    private fun DrawScope.drawCities(dec: Float, utc: Long) {
        val gate = sensitivity.gain
        val bass = bassS * gate
        val tr = trebleS * gate
        var i = 0
        while (i < WorldCities.COUNT) {
            val city = WorldCities.ALL[i]
            val t1 = city.tier - 1
            val baseR = mapScale * DOT_CORE_R[t1]

            // 昼夜：连续 sunFactor（⛔ 不用 isDay 二值，夜侧该平滑变暗而不是一刀切）
            val sun = WorldTerminator.sunFactor(city.lon, city.lat, dec, utc)
            // 焦点 / 拍点闪烁 / 高频闪烁 / 到访辉光
            val f = focusWeight[i]
            val tw = 1f + twinkle * TWINKLE_W[t1] + tr * SPARKLE_GAIN
            val alpha = (DOT_BASE_ALPHA *
                (DOT_NIGHT_ALPHA_LO + (1f - DOT_NIGHT_ALPHA_LO) * sun) *
                (1f + FOCUS_ALPHA_GAIN * f) * tw * (1f + VISIT_GLOW_GAIN * visitGlow[i]))
                .coerceAtMost(DOT_ALPHA_MAX)

            val cx = citySX[i]
            val cy = citySY[i]
            val c = Offset(cx, cy)

            // 光晕：真·径向渐变（Brush 几何在 ensureLayout 定，逐帧只改 α）
            val b = dotBrush[i]
            if (b != null) {
                drawCircle(b, dotGlowR[i], c, alpha = alpha * DOT_GLOW_ALPHA)
            }
            // 外晕两层：半径随 treble 微抖（不是缩放，是「多长出来一点光」）
            drawCircle(dotCore, baseR * HALO2_R, c, alpha = alpha * HALO2_ALPHA * (0.70f + 0.30f * tr))
            if (tier >= 1) {
                drawCircle(dotCore, baseR * HALO3_R, c, alpha = alpha * HALO3_ALPHA)
            }
            // 亮核：半径随平滑 bass 呼吸（±[DOT_BREATH]）+ 到访时轻微胀大
            drawCircle(
                dotCore,
                baseR * (1f + DOT_BREATH * bass + DOT_VISIT_BREATH * visitGlow[i]),
                c,
                alpha = alpha
            )

            i++
        }
    }

    // ══ ⑩⑪ 航线 ═══════════════════════════════════════════════════════════

    /**
     * 航线折线（⑩）+ 行进光（⑪）。每条航线一条预建 [Path]，逐帧 `rewind()` 后重建。
     *
     * ## 跨 ±180° 缝合（大圆航线约 3/24 会命中）
     *
     * 实测（`logs_temp/world_seam`，真实 `WorldMapData` + `WorldProjection`）：
     * 24 条采样航线里 **3 条**的大圆跨越日期变更线（纽约→北京、纽约→广州、
     * 东京→亚特兰大 —— 都是北半球高纬长途）。朴素的 `lineTo` 会把它们画成一条
     * **横贯地图中部的水平线**，比南极洲那道尖刺更显眼。
     *
     * 修法：逐段比 [arcLon] 的差，超过 [SEAM_LON_JUMP_DEG]（180°）就 `moveTo` 断笔。
     * - **为什么用「烘焙好的经度」判、而不是投影后的 x**：航线与世界地图的区别在于
     *   **屏幕 x 会随画布尺寸变**（转屏 / 换窗口），而缝合是**数据属性**、与尺寸无关。
     *   烘焙判据因此在转屏后依然正确，不需要失效重算。
     * - **为什么不用「每条航线一个 bool 标记」**：本循环每帧每采样点本来就跑
     *   `robinsonX` + `robinsonY`（约 30 次浮点运算），多加一次
     *   `FloatArray` 读 + 一次比较（约 2 次运算）≈ **6% 增量**；换来的是
     *   少 1,904 字节状态、少一个「标记在转屏后是否还有效」的失效分支，
     *   而且天然支持「一条航线跨多次缝合」的病态情形（标记方案只存一个索引时会漏）。
     * - **辉光 / 主芯共用同一条 [Path]** ⇒ 一次断笔两条描边都干净，不存在
     *   「主芯断了、辉光还横穿」的半吊子状态。
     * - **头部光点跨缝合时会在一帧内从地图一端移到另一端** —— 这是**正确**的：
     *   真的航班就是从左侧出图、从右侧入图。头部只画一个点、不画连线，所以
     *   不会产生任何弦。彗尾的跨缝合段则由 [drawTrail] 跳过。
     */
    private fun DrawScope.drawRoutes(treble: Float, gate: Float) {
        val tr = treble * gate
        var i = 0
        while (i < MAX_FLIGHTS) {
            if (!fActive[i]) {
                i++
                continue
            }
            // ⛔ 下面 k 被直接拿去索引 4 个成员数组 ⇒ 必须先钳位。正常路径恒 0..2
            //    （只由 WorldRouteClass.ordinal 写入），这里是防御性的。
            val k = fKlass[i].coerceIn(0, ROUTE_CLASSES - 1)
            val a = fAlpha[i] * ROUTE_ALPHA * WorldNetwork.trunkBrightness(routeClassOf(k))
            if (a > 0.004f) {
                val p = routePath[i]
                val base = i * ARC_PTS
                val head = fHead[i]
                val col = routeColor[k]
                p.rewind()
                p.moveTo(arcSX[base], arcSY[base])
                var s = 1
                while (s <= head) {
                    val j = base + s
                    // 跨 ±180° 缝合：改 moveTo 断笔，绝不 lineTo（否则是一条横贯全图的弦）。
                    // 因为辉光与主芯共用同一条 Path，一次断笔两条描边都干净。
                    if (abs(arcLon[j] - arcLon[j - 1]) > SEAM_LON_JUMP_DEG) {
                        p.moveTo(arcSX[j], arcSY[j])
                    } else {
                        p.lineTo(arcSX[j], arcSY[j])
                    }
                    s++
                }
                // 两层：外层宽而淡的辉光 + 内层细而亮的芯
                drawPath(p, col, alpha = a * ROUTE_GLOW_ALPHA, style = routeGlowStroke[k])
                drawPath(p, col, alpha = a, style = routeStroke[k])
                drawTrail(i, k, a, tr)
            }
            i++
        }
    }

    /**
     * 行进光：头部光点 + 4 段渐隐彗尾（每段两层：宽淡 + 窄亮）。
     *
     * ⚠️ **跨缝合的尾段直接跳过**（`continue` 而不是 `break`）：光点在地图一端、
     * 尾巴可能延伸到另一端，跳过那一段而继续画更靠后的段，才能同时得到
     * 「尾巴在两侧都可见」。头部光点本身照常画（见 [drawRoutes] 的 KDoc）。
     */
    private fun DrawScope.drawTrail(slot: Int, k: Int, a: Float, tr: Float) {
        val base = slot * ARC_PTS
        val head = fHead[slot]
        val col = routeColor[k]
        val w0 = mapScale * HEAD_R_K * HEAD_R_CLASS[k] * (1f + HEAD_BREATH * tr)
        val boost = 1f + TRAIL_TREBLE_GAIN * tr
        val hx = arcSX[base + head]
        val hy = arcSY[base + head]
        var s = 0
        while (s < TRAIL_SEGS) {
            val k1 = head - s * TRAIL_STEP
            val k0 = head - (s + 1) * TRAIL_STEP
            if (k0 < 0) break
            // 跨缝合的尾段不画（否则彗尾会横穿整张地图）
            if (abs(arcLon[base + k0] - arcLon[base + k1]) <= SEAM_LON_JUMP_DEG) {
                val fade = 1f - (s + 1).toFloat() / (TRAIL_SEGS + 1)
                val x0 = arcSX[base + k0]
                val y0 = arcSY[base + k0]
                val x1 = arcSX[base + k1]
                val y1 = arcSY[base + k1]
                val w = w0 * (1f - s * TRAIL_TAPER)
                val aa = a * fade * boost
                drawLine(col, Offset(x0, y0), Offset(x1, y1), strokeWidth = w * TRAIL_WIDE_K, alpha = aa * TRAIL_WIDE_ALPHA, cap = StrokeCap.Round)
                drawLine(col, Offset(x0, y0), Offset(x1, y1), strokeWidth = w, alpha = aa, cap = StrokeCap.Round)
            }
            s++
        }
        drawCircle(col, w0 * 1.5f, Offset(hx, hy), alpha = (a * HEAD_ALPHA * boost).coerceAtMost(1f))
        drawCircle(dotCore, w0 * 0.7f, Offset(hx, hy), alpha = (a * HEAD_CORE_ALPHA * boost).coerceAtMost(1f))
    }

    // ══ ⑫ 涟漪 ═══════════════════════════════════════════════════════════

    private fun DrawScope.drawRipples() {
        var i = 0
        while (i < RIPPLE_MAX) {
            if (rActive[i]) {
                val t = rAge[i] / rDur[i]
                var cx: Float
                var cy: Float
                if (rOnRoute[i]) {
                    val slot = rSlot[i]
                    if (!fActive[slot]) {
                        rActive[i] = false
                        i++
                        continue
                    }
                    val k = (rT[i] * (ARC_PTS - 1f)).toInt().coerceIn(0, ARC_PTS - 1)
                    cx = arcSX[slot * ARC_PTS + k]
                    cy = arcSY[slot * ARC_PTS + k]
                } else {
                    val c = rCity[i].coerceIn(0, WorldCities.COUNT - 1)
                    cx = citySX[c]
                    cy = citySY[c]
                }
                val env = (1f - t) * (1f - t)
                // 缓出：半径从 0 加速后趋近 [RIPPLE_MAX_R]（归一化 ⇒ 终值恰为最大半径）
                val f = (t + t * t * (RIPPLE_EASE - 1f)) / RIPPLE_EASE
                val rad = mapScale * RIPPLE_MAX_R * f
                val col = rippleColor[rTier[i].coerceIn(0, WorldCities.TIER_COUNT - 1)]
                val amp = rAmp[i] * env
                // 前沿（最外）环最亮 ⇒ 读作向外推的冲击波
                drawCircle(col, rad, Offset(cx, cy), alpha = amp * RIPPLE_ALPHA_LEAD)
                drawCircle(col, rad * RIPPLE_MID_R, Offset(cx, cy), alpha = amp * RIPPLE_ALPHA_MID)
                drawCircle(col, rad * RIPPLE_CORE_R, Offset(cx, cy), alpha = amp * RIPPLE_ALPHA_TAIL)
            }
            i++
        }
    }

    // ══ 航线推进 / 生成 ════════════════════════════════════════════════════

    private fun advanceFlights(dt: Float) {
        var i = 0
        while (i < MAX_FLIGHTS) {
            if (fActive[i]) {
                fAge[i] += dt
                val t = fAge[i] / fDur[i]
                if (t >= 1f) {
                    killRoute(i)
                } else {
                    // 头部推进：起步快、收尾稳（easeOutCubic），下标 1..ARC_PTS−1
                    val g = (t / GROW_FRAC).coerceIn(0f, 1f)
                    fGrow[i] = Easing.easeOutCubic(g)
                    fHead[i] = 1 + (fGrow[i] * (ARC_PTS - 2f) + 0.5f).toInt()
                        .coerceIn(0, ARC_PTS - 2)
                    // 包络：极短的淡入（RISE_FRAC）+ 尾部淡出
                    val rise = (t / RISE_FRAC).coerceIn(0f, 1f)
                    fAlpha[i] = rise * (1f - smoothstep(FADE_START, 1f, t))
                    // 抵达：头部走完全程的那一帧触发一次（to 城市到访辉光 + 两圈涟漪）
                    if (!fArrived[i] && t >= GROW_FRAC) {
                        fArrived[i] = true
                        onArrival(i)
                    }
                }
            }
            i++
        }
    }

    /**
     * 航线抵达：终点城市到访辉光拉满 + 终点一圈涟漪 + **沿航线再播一圈行进涟漪**
     * （规格「涟漪也可从起点沿航线传播到终点」—— 行进涟漪的中心取航线采样点
     * [rT]，随时间从 0 走到 1，读作「能量顺着航线灌进城市」）。
     */
    private fun onArrival(i: Int) {
        val to = fTo[i].coerceIn(0, WorldCities.COUNT - 1)
        visitGlow[to] = 1f
        val k = fKlass[i]
        val amp = (0.55f + 0.45f * WorldNetwork.trunkBrightness(routeClassOf(k)))
            .coerceIn(0f, 1f)
        spawnRipple(-1, false, to, 0f, amp)
        spawnRipple(i, true, to, 0f, amp * ARRIVE_TRAIL_RIPPLE)
    }

    /** 到访辉光按 [VISIT_DECAY] **线性**衰减（1.4/s ⇒ 一次抵达约 0.7s 衰减完）。
     *  ⛔ 刻意不用指数：指数尾巴很长，0.7s 后仍有 5% 残留，多条航线叠加会让枢纽城市
     *  常驻在一个说不清的半亮状态（分不清是「刚到访」还是「一直亮」）。 */
    private fun decayVisits(dt: Float) {
        val k = VISIT_DECAY * dt
        var i = 0
        while (i < WorldCities.COUNT) {
            val v = visitGlow[i]
            if (v > 0f) {
                visitGlow[i] = if (v - k < 0f) 0f else v - k
            }
            i++
        }
    }

    private fun killRoute(i: Int) {
        fActive[i] = false
        flightCount--
        val from = fFrom[i]
        val to = fTo[i]
        if (from in 0 until WorldCities.COUNT && cityRoutes[from] > 0) cityRoutes[from]--
        if (to in 0 until WorldCities.COUNT && cityRoutes[to] > 0) cityRoutes[to]--
        routePath[i].rewind()
        // 连在它身上的行进涟漪一起收掉（否则会停在最后一帧的旧坐标上）
        var r = 0
        while (r < RIPPLE_MAX) {
            if (rActive[r] && rOnRoute[r] && rSlot[r] == i) rActive[r] = false
            r++
        }
    }

    /**
     * 城市 [city] 作为航线端点是否还有余量 —— 即
     * `cityRoutes[city] < WorldCities.activeFlightRange(tier).last`
     * （Tier1 8 / Tier2 5 / Tier3 3 / Tier4 1）。
     */
    private fun cityCapOk(city: Int): Boolean {
        if (city !in 0 until WorldCities.COUNT) return false
        val range = WorldCities.activeFlightRange(WorldCities.tierOf(city))
        if (range.isEmpty()) return false
        return cityRoutes[city] < range.last
    }

    /**
     * 帧率 / 静默把目标并发数压低时，按 [WorldNetwork.routeWeight] **升序**淘汰 ——
     * 该函数 KDoc 明确要求调用方这么做，否则「只降数量」会把主干全砍光、
     * 剩下密密麻麻的毛细航线。
     */
    private fun evictExcess() {
        while (flightCount > activeTarget) {
            var worst = -1
            var worstW = Float.MAX_VALUE
            var i = 0
            while (i < MAX_FLIGHTS) {
                if (fActive[i] && fWeight[i] < worstW) {
                    worstW = fWeight[i]
                    worst = i
                }
                i++
            }
            if (worst < 0) return
            killRoute(worst)
        }
    }

    private fun topUp(dt: Float, cap: Int) {
        spawnAccum += dt
        // 目标间隔：让平均寿命 × 目标条数 ≈ 并发数（10.6s × 22 ≈ 233 ⇒ 每 10.6s 补 22 条）
        val avg = AVG_DURATION
        var interval = avg * spawnGain() / (activeTarget * (1f + MID_DENSITY_GAIN * midS * sensitivity.gain))
        if (interval < MIN_SPAWN_INTERVAL) interval = MIN_SPAWN_INTERVAL
        if (spawnAccum < interval) return
        spawnAccum = 0f
        if (flightCount >= activeTarget || flightCount >= cap) return
        spawnRoute(BeatStrength.NONE, 0f)
    }

    private fun applyBeat(strength: BeatStrength) {
        if (strength == BeatStrength.NONE || strength == BeatStrength.WEAK) return
        if (beatCooldown > 0f) return
        val cap = (activeTarget * (1f + BEAT_TARGET_SLACK)).roundToInt()
        if (flightCount >= cap) return
        // 上面已 return 掉 NONE / WEAK ⇒ 这里的 when 由智能转换判为**穷尽**，
        // 写 else 反而会被编译器报 "when is exhaustive so 'else' is redundant"。
        when (strength) {
            BeatStrength.MID -> {
                spawnRoute(BeatStrength.MID, 0f)
                beatCooldown = BEAT_SPAWN_COOLDOWN
            }
            BeatStrength.STRONG -> {
                spawnRoute(BeatStrength.STRONG, beatClassifier.lastStrength())
                beatCooldown = BEAT_SPAWN_COOLDOWN
            }
        }
    }

    /** 生成系数（灵敏度档 → 疏密）。激烈档略密、安静档略疏，但都只动 ±25%。 */
    private fun spawnGain(): Float = (1f + (sensitivity.gain - 1f) * SPAWN_GAIN_SPAN)
        .coerceIn(SPAWN_GAIN_MIN, SPAWN_GAIN_MAX)

    private fun spawnRoute(beat: BeatStrength, amp: Float) {
        var slot = -1
        var i = 0
        while (i < MAX_FLIGHTS) {
            if (!fActive[i]) {
                slot = i
                break
            }
            i++
        }
        if (slot < 0) return
        val focus = focusCity
        // 端点配额：重抽 [ROUTE_RETRY] 次以躲开「已挂满」的城市，仍不满足就接受这一条
        // （否则并发数高时会因为无处可挂而彻底停止生成，画面反而会僵住）。
        var spec = WorldNetwork.pickRoute(rnd, focus, beat)
        var tries = 0
        while (tries < ROUTE_RETRY && !(cityCapOk(spec.from) && cityCapOk(spec.to))) {
            spec = WorldNetwork.pickRoute(rnd, focus, beat)
            tries++
        }
        fFrom[slot] = spec.from
        fTo[slot] = spec.to
        cityRoutes[spec.from]++
        cityRoutes[spec.to]++
        fKlass[slot] = spec.klass.ordinal
        fWeight[slot] = spec.weight
        fAge[slot] = 0f
        fAlpha[slot] = 0f
        fHead[slot] = 1
        fArrived[slot] = false
        fDur[slot] = durationFor(spec.klass.ordinal)
        fActive[slot] = true
        flightCount++

        // 弧高：等级基线（WorldNetwork.arcBoost）× 短程加权（长程本身就有大圆弯度）
        val distFrac = angDistFrac(spec.from, spec.to)
        val jitter = 0.85f + rnd.next() * 0.30f
        val distBoost = LIFT_DIST_FLOOR + (1f - LIFT_DIST_FLOOR) * (1f - distFrac)
        fLift[slot] = spec.arcBoost * LIFT_BASE * distBoost * jitter *
            (1f + LIFT_MID_GAIN * midS * sensitivity.gain)
        buildArc(slot, spec.from, spec.to)

        // 起点城市一圈涟漪（STRONG 拍点必发；其余按概率）
        if (beat == BeatStrength.STRONG || rnd.next() < DEPART_RIPPLE_P) {
            spawnRipple(-1, false, spec.from, 0f, 0.55f + 0.45f * amp)
        }
    }

    private fun durationFor(klass: Int): Float = when (klass) {
        WorldRouteClass.TRUNK.ordinal -> DUR_TRUNK_MIN + rnd.next() * (DUR_TRUNK_MAX - DUR_TRUNK_MIN)
        WorldRouteClass.REGIONAL.ordinal -> DUR_REGIONAL_MIN + rnd.next() * (DUR_REGIONAL_MAX - DUR_REGIONAL_MIN)
        else -> DUR_FEEDER_MIN + rnd.next() * (DUR_FEEDER_MAX - DUR_FEEDER_MIN)
    }

    /**
     * 生成一条航线的 [ARC_PTS] 个大圆采样点（**只在 spawn 时跑一次**）。
     *
     * slerp：`p(t) = (sin((1−t)ω)·A + sin(tω)·B) / sin(ω)`，两端是单位向量。
     * 逐点反解回 (lon, lat) 存进 [arcLon] / [arcLat] —— 后续逐帧只做投影，
     * 所以这里的 ~170 次三角函数/条只在生成时付一次。
     *
     * ⚠️ `sin(ω) ≈ 0`（两城重合或正好对跖）时 slerp 退化，走经纬线性插值兜底。
     * 32 座城市里没有对跖点对，重合也不会被 [WorldNetwork.pickRoute] 选中
     * （它保证 `from != to`），所以这只是防御性分支。
     */
    private fun buildArc(slot: Int, from: Int, to: Int) {
        val a = WorldCities.ALL[from]
        val b = WorldCities.ALL[to]
        val lat1 = Math.toRadians(a.lat.toDouble())
        val lon1 = Math.toRadians(a.lon.toDouble())
        val lat2 = Math.toRadians(b.lat.toDouble())
        val lon2 = Math.toRadians(b.lon.toDouble())
        val cl1 = cos(lat1)
        val cl2 = cos(lat2)
        val ax = cl1 * cos(lon1)
        val ay = cl1 * sin(lon1)
        val az = sin(lat1)
        val bx = cl2 * cos(lon2)
        val by = cl2 * sin(lon2)
        val bz = sin(lat2)
        val dotv = (ax * bx + ay * by + az * bz).coerceIn(-1.0, 1.0)
        val omega = acos(dotv)
        val sinOmega = sin(omega)
        val base = slot * ARC_PTS
        val deg = 180.0 / Math.PI
        var k = 0
        while (k < ARC_PTS) {
            val t = k / (ARC_PTS - 1f).toDouble()
            var x: Double
            var y: Double
            var z: Double
            if (sinOmega < 1e-4) {
                x = ax + (bx - ax) * t
                y = ay + (by - ay) * t
                z = az + (bz - az) * t
            } else {
                val s1 = sin((1.0 - t) * omega)
                val s2 = sin(t * omega)
                x = s1 * ax + s2 * bx
                y = s1 * ay + s2 * by
                z = s1 * az + s2 * bz
            }
            val lx = atan2(y, x) * deg
            val lz = asin(z.coerceIn(-1.0, 1.0)) * deg
            arcLon[base + k] = lx.toFloat()
            arcLat[base + k] = lz.toFloat()
            k++
        }
    }

    /**
     * 逐帧把 [arcLon] / [arcLat] 投到屏幕，并叠加弧高隆起（`lift × sin(π·t)`，[arcSinPiT] 预烘焙）。
     * ⛔ 这里是**唯一**对航线重投影的地方；静态陆地绝不参与。
     */
    private fun projectArcs() {
        val s = mapScale
        val cx = centerX
        val cy = centerY
        var i = 0
        while (i < MAX_FLIGHTS) {
            if (fActive[i]) {
                val base = i * ARC_PTS
                val lift = fLift[i]
                var k = 0
                while (k < ARC_PTS) {
                    val lo = arcLon[base + k]
                    val la = arcLat[base + k]
                    val nx = WorldProjection.robinsonX(lo, la)
                    val ny = WorldProjection.robinsonY(lo, la) + lift * arcSinPiT[k]
                    arcSX[base + k] = WorldProjection.toScreenX(nx, s, cx)
                    arcSY[base + k] = WorldProjection.nyToScreenY(ny, s, cy)
                    k++
                }
            }
            i++
        }
    }

    /** 两城大圆角距归一化到 0..1（0 = 同点，1 = 对跖） */
    private fun angDistFrac(from: Int, to: Int): Float {
        val d = WorldCities.distanceKm(from, to) / (WorldCities.EARTH_RADIUS_KM * PI_F)
        return if (d.isFinite()) d.coerceIn(0f, 1f) else 0f
    }

    // ══ 涟漪推进 / 生成 ════════════════════════════════════════════════════

    private fun advanceRipples(dt: Float) {
        var i = 0
        while (i < RIPPLE_MAX) {
            if (rActive[i]) {
                rAge[i] += dt
                if (rAge[i] >= rDur[i]) {
                    rActive[i] = false
                } else if (rOnRoute[i]) {
                    // 行进涟漪：中心沿航线从 t=0 走到 t=1（⛔ 逐帧零三角函数）
                    val t = rAge[i] / rDur[i]
                    rT[i] = if (t < 1f) t else 1f
                }
            }
            i++
        }
    }

    /**
     * 播一圈涟漪。[city] ≥ 0 ⇒ 城市涟漪（抵达时播，中心固定在该城市）；
     * [onRoute] = true ⇒ 沿航线行进的涟漪（中心取航线采样点 [rT]）。
     */
    private fun spawnRipple(slot: Int, onRoute: Boolean, city: Int, t: Float, amp: Float) {
        if (!onRoute && city < 0) return
        val i = rippleHead
        rippleHead = (rippleHead + 1) % RIPPLE_MAX
        rActive[i] = true
        rOnRoute[i] = onRoute
        rSlot[i] = slot
        rCity[i] = city
        rT[i] = t
        rAge[i] = 0f
        rDur[i] = RIPPLE_DURATION
        rTier[i] = WorldCities.tierOf(if (onRoute) fFrom[slot.coerceIn(0, MAX_FLIGHTS - 1)] else city)
        rAmp[i] = amp.coerceIn(0f, 1f)
    }

    // ══ 焦点 ══════════════════════════════════════════════════════════════

    /**
     * 焦点轮换 + [focusWeight] 缓动。⛔ **本函数是 [focusWeight] 的唯一写者**。
     *
     * 交叉淡入：目标恒为「当前焦点 = 1、其余 = 0」，逐帧按
     * `k = 1 − exp(−dt/τ)`（τ = [FOCUS_TAU] = 1.3s ⇒ 3τ ≈ 3.9s 达 95% ≈ 规格的 4s）
     * 逼近 ⇒ 旧焦点淡出、新焦点淡入，**任何一帧都不会跳变**。
     */
    private fun updateFocus(now: Long, dt: Float) {
        if (now >= focusSwapAtMs) {
            focusCity = pickFocusCity()
            focusSwapAtMs = now + focusDelayMs()
        }
        val k = (1.0 - exp(-(dt / FOCUS_TAU).toDouble())).toFloat()
        var i = 0
        var mx = 0f
        while (i < WorldCities.COUNT) {
            val target = if (i == focusCity) 1f else 0f
            val v = focusWeight[i] + (target - focusWeight[i]) * k
            focusWeight[i] = if (v < 0f) 0f else if (v > 1f) 1f else v
            if (focusWeight[i] > mx) mx = focusWeight[i]
            i++
        }
        focusLevel = mx
    }

    /** 在 Tier1 / Tier2（16 座）里按 [WorldCities.weightOf] 抽一座，跳过当前焦点 */
    private fun pickFocusCity(): Int {
        var total = 0f
        var c = 0
        while (c < WorldCities.COUNT) {
            if (WorldCities.tierOf(c) <= FOCUS_MAX_TIER) total += WorldCities.weightOf(c)
            c++
        }
        if (total <= 0f) return 0
        var target = rnd.next() * total
        var acc = 0f
        var best = -1
        var j = 0
        while (j < WorldCities.COUNT) {
            if (WorldCities.tierOf(j) <= FOCUS_MAX_TIER) {
                acc += WorldCities.weightOf(j)
                if (best < 0) best = j
                if (acc >= target && j != focusCity) return j
            }
            j++
        }
        // 兜底：找一个不是当前焦点的 Tier≤2
        var k = 0
        while (k < WorldCities.COUNT) {
            if (WorldCities.tierOf(k) <= FOCUS_MAX_TIER && k != focusCity) return k
            k++
        }
        return if (best >= 0) best else 0
    }

    private fun focusDelayMs(): Long =
        (FOCUS_MIN_MS + rnd.next() * (FOCUS_MAX_MS - FOCUS_MIN_MS)).toLong()

    // ══ 布局（⛔ 绝不触碰 elapsed / UTC / 音频平滑 / 航线年龄 / focusWeight / rnd）══

    /**
     * 画布尺寸 / 安全边 / LOD 任一变化时重建：投影参数、陆地 [Path]、
     * 32 支光点 Brush、背景 3 支 Brush、描边宽度、颗粒 tile。
     *
     * ⛔ **不得触碰**：elapsed、utcMs、bassS/midS/trebleS/energyS/sectionS、frameMsAvg、
     * fAge / rAge、focusWeight、rnd、beatClassifier。否则转屏会让动画倒退或重置。
     */
    private fun ensureLayout(w: Float, h: Float, minDim: Float, safe: Float, q: VisualQuality) {
        val lod = lodFor(q)
        if (lod != dataLod) ensureData(lod)
        if (layoutW == w && layoutH == h && layoutSafe == safe && land != null) return
        layoutW = w
        layoutH = h
        layoutSafe = safe

        // ── 投影参数：先扣 overscan 安全边，再按 WorldProjection.mapScale 定 scale ──
        centerX = WorldProjection.mapCenterX(w)
        centerY = WorldProjection.mapCenterY(h)
        val usable = minDim * 0.5f - safe
        mapScale = WorldProjection.mapScale(usable * 2f, usable * 2f, MAP_MARGIN)
        mapL = centerX - WorldProjection.HALF_WIDTH * mapScale
        mapR = centerX + WorldProjection.HALF_WIDTH * mapScale
        mapT = centerY - mapScale
        mapB = centerY + mapScale

        // ── 背景 / 大气 Brush（绝对坐标 ⇒ 必须随尺寸重建）────────────────────
        val halfDiag = sqrt(w * w + h * h) * 0.5f
        bgDepthBrush = Brush.radialGradient(
            0f to depthCore,
            BG_CENTER_STOP to depthCore.copy(alpha = 0.55f),
            1f to depthCore.copy(alpha = 0f),
            center = Offset(centerX, centerY),
            radius = halfDiag
        )
        vignetteBrush = Brush.radialGradient(
            VIG_START to Color.Black.copy(alpha = 0f),
            1f to Color.Black.copy(alpha = VIG_EDGE_ALPHA),
            center = Offset(centerX, centerY),
            radius = halfDiag
        )
        atmoRadius = mapScale * ATMO_RADIUS
        atmoBrush = Brush.radialGradient(
            0f to Color.Blue.copy(alpha = 0f),
            ATMO_INNER to Color.Blue.copy(alpha = 0f),
            ATMO_PEAK to atmoColor.copy(alpha = 1f),
            1f to atmoColor.copy(alpha = 0f),
            center = Offset(centerX, centerY),
            radius = atmoRadius
        )

        // ── 陆地路径（静态地图 ⛔ 只在这里投影一次）─────────────────────────
        val lm = land
        if (lm != null) rebuildLandPaths(lm)

        // ── 城市屏幕坐标 + 光点渐变 Brush ─────────────────────────────────
        var i = 0
        while (i < WorldCities.COUNT) {
            val c = WorldCities.ALL[i]
            val nx = WorldProjection.robinsonX(c.lon, c.lat)
            val ny = WorldProjection.robinsonY(c.lon, c.lat)
            val x = WorldProjection.toScreenX(nx, mapScale, centerX)
            val y = WorldProjection.nyToScreenY(ny, mapScale, centerY)
            citySX[i] = x
            citySY[i] = y
            val coreR = mapScale * DOT_CORE_R[c.tier - 1]
            val gr = coreR * DOT_GLOW_R[c.tier - 1]
            dotGlowR[i] = gr
            // 渐变几何固定在「无 bass 呼吸」的基准半径上 ⇒ 逐帧只改 α，不改几何。
            // Bass 呼吸改由亮核半径 + 外晕半径承担（见 drawCities）。
            dotBrush[i] = Brush.radialGradient(
                0f to dotCore.copy(alpha = 0.95f),
                DOT_GLOW_MID to dotCore.copy(alpha = 0.30f),
                1f to dotCore.copy(alpha = 0f),
                center = Offset(x, y),
                radius = gr
            )
            i++
        }

        // ── 描边宽度档（⛔ Stroke 只读 ⇒ 只能预先建实例，按平滑 bass 选档）──
        val strokeBase = (mapScale * LAND_STROKE_K).coerceAtLeast(LAND_STROKE_MIN)
        val glowBase = (mapScale * LAND_GLOW_K).coerceAtLeast(LAND_GLOW_MIN)
        var b = 0
        while (b < LAND_STROKE_BUCKETS) {
            val f = 1f - LAND_BREATH_BUCKET +
                2f * LAND_BREATH_BUCKET * b / (LAND_STROKE_BUCKETS - 1)
            landStroke[b] = Stroke(strokeBase * f)
            landGlowStroke[b] = Stroke(glowBase * f)
            b++
        }
        var k = 0
        while (k < ROUTE_CLASSES) {
            val wBase = (mapScale * ROUTE_STROKE_K * ROUTE_STROKE_CLASS[k]).coerceAtLeast(ROUTE_STROKE_MIN)
            routeStroke[k] = Stroke(wBase)
            routeGlowStroke[k] = Stroke(wBase * ROUTE_GLOW_W)
            k++
        }

        // ── 颗粒 tile（边长只由「短边档」定 ⇒ 图案与画布尺寸无关，档不变不重建）──
        if (tier > 0) {
            val tile = if (minDim >= GRAIN_TILE_LARGE_MIN) GRAIN_TILE_HI else GRAIN_TILE
            if (tile != grainTilePx) buildGrainTile(tile)
        }
    }

    /** LOD 档（0 粗 / 1 中 / 2 细）；阈值与 [WorldNetwork.maxActiveFlights] 同口径 */
    private fun lodFor(q: VisualQuality): Int = when {
        q.maxParticles <= LOW_PARTICLE_BUDGET -> WorldMapData.LOD_COARSE
        q.maxParticles <= MED_PARTICLE_BUDGET -> WorldMapData.LOD_MEDIUM
        else -> WorldMapData.LOD_FINE
    }

    private fun tierFor(q: VisualQuality): Int = when {
        q.maxParticles <= LOW_PARTICLE_BUDGET -> 0
        q.maxParticles <= MED_PARTICLE_BUDGET -> 1
        else -> 2
    }

    /** 地图**只解码一次**：[decodeWorld] 内部按 lod 缓存同一实例，重复调用零成本 */
    private fun ensureData(lod: Int) {
        dataLod = lod
        land = decodeWorld(lod)
    }

    /**
     * 重建全部陆地的**填充**路径 [landFillPath]、**描边**路径 [landPath]、
     * 以及裁剪用的并集 [landAll]。
     * ⛔ 静态地图的**唯一**投影点（LOD_FINE 8,741 点，一趟投影喂三处，只在尺寸/LOD 变化时跑）。
     *
     * ## 为什么要区分「填充」与「描边」两条路径
     *
     * 真实 `WorldMapData`（LOD_MEDIUM，285 环 / 5,728 点）里 **1 个环**存在环内
     * ±180° 缝合跳变：南极洲的 #284，207 点，跳变在第 1 个点之后，
     * 投影 x 跳 **426.8 px**（阈值 = 0.35 × 地图宽 742.4 = **259.9 px**），
     * 对应源码点 `[lon 180, lat −90] → [lon −180, lat −90]`。
     * 用朴素的 `moveTo/lineTo/close` 走这个环，会在画面底部画出一条横贯整个南极洲的
     * **亮线**（外加两侧各一段竖直掉落），读起来像个「箱子」而不是海岸线。
     *
     * ### 填充：⛔ **不切缝合、保留极点上的点**（这是本函数最关键的决定）
     *
     * 直觉会说「填充分开了才不会长出假楔形」。**在这里是反的。** 推理：
     *
     * 1. 缝合段 `[180,−90] → [−180,−90]` 在**任何**投影里都不是退化弦 ——
     *    经度在极点上是退化的，但 lat = −90 这一整**条线**在 Robinson 里是地图的
     *    最下边缘 `y = cy + mapScale`，是**有限、可见、真实**的边界。
     * 2. 因此「含这 2 个极点点的闭合环」投影出的多边形，**正是正确的南极形状**：
     *    海岸线 + 一条平直的底边，正如任何 Robinson 世界地图上南极洲的样子。
     * 3. 若把填充也切开：环变成**开**子路径，而 Skia 填充开子路径时会**隐式闭合** ——
     *    于是自动补上的那条直线，恰恰横跨 426.8 px 回到自己。**用切开来避免楔形，
     *    反而制造了同一个楔形。**
     * 4. 若把该环整个排除出填充路径：更糟。实测每个环的包围盒面积 ——
     *    #284 = **97,120 px²**（它才是扛起整个南极洲的环），而另外 7 个南极环
     *    分别是 351 / 98 / 264 / 47 / 32 / 26 / 108 px²（全是小岛碎片）。
     *    排除 #284 ⇒ **整个南极洲的填充消失**。已用 Java2D 对照图肉眼确认。
     *
     * 结论：**只有描边需要断笔。填充保持朴素闭合。**
     *
     * ### 描边：丢弃退化极点 + 缝合处 `moveTo` 断笔
     *
     * - **丢弃极点**（`|lat| ≥ [POLAR_LAT]` = 89.9°）：极点上「经度」没有意义，
     *   把 `[180,−90]` 画成 `x = +197 px`、`[−180,−90]` 画成 `x = −197 px` 会多出
     *   两段各 45 px 的竖直掉落。丢掉后 0.1° 的缺口在 1080p 上 ≈ 0.85 px，不可见。
     * - **断笔**：`moveTo` 起新子路径代替 `lineTo`，所以既没有横贯全图的弦，
     *   也不会触发隐式闭合。
     * - **收尾用显式 `lineTo(首点)` 而不是 `close()`**：环是隐式闭合的，最后一点到
     *   第一点是**真实海岸线**的一小段（对 #284 来说就在 lon ≈ +180 附近），要画。
     *   但对被切开的环调用 `close()` 会闭合**最后一个子路径**（kept[1..n−1]），
     *   它的首点恰在 lon = −180 ⇒ 又是一条 426.8 px 的弦。显式 `lineTo` 两者都对。
     */
    private fun rebuildLandPaths(lm: WorldLandmass) {
        landPath.rewind()
        landFillPath.rewind()
        landAll.rewind()
        val s = mapScale
        val cx = centerX
        val cy = centerY
        val jump = (mapR - mapL) * SEAM_JUMP_FRAC
        var r = 0
        while (r < lm.ringCount) {
            val stroke = landPath
            val fill = landFillPath
            val st = lm.ringStart[r]
            val len = lm.ringLength[r]
            if (len < 2) {
                r++
                continue
            }
            if (landScratch.size < len * 2) landScratch = FloatArray(len * 2 + 64)

            // ── 第 1 趟（单趟投影）：填充吃全部点；描边只吃非极点，投影结果存暂存 ──
            var n = 0
            var k = 0
            while (k < len) {
                val idx = st + k
                val lo = lm.lon[idx]
                val la = lm.lat[idx]
                val x = WorldProjection.toScreenX(WorldProjection.robinsonX(lo, la), s, cx)
                val y = WorldProjection.nyToScreenY(WorldProjection.robinsonY(lo, la), s, cy)
                if (k == 0) {
                    fill.moveTo(x, y)
                    landAll.moveTo(x, y)
                } else {
                    fill.lineTo(x, y)
                    landAll.lineTo(x, y)
                }
                if (la > -POLAR_LAT && la < POLAR_LAT) {
                    landScratch[n * 2] = x
                    landScratch[n * 2 + 1] = y
                    n++
                }
                k++
            }
            // 填充：朴素闭合（见上方 §填充）
            fill.close()
            landAll.close()

            // ── 第 2 趟：走暂存画描边，遇跳变 moveTo 断笔 ──
            if (n >= 2) {
                var j = 1
                var pen = false
                var pxx = 0f
                while (j < n) {
                    val x = landScratch[j * 2]
                    val y = landScratch[j * 2 + 1]
                    if (!pen) {
                        stroke.moveTo(x, y)
                        pen = true
                    } else if (abs(x - pxx) > jump) {
                        stroke.moveTo(x, y)   // 断笔：绝不用 lineTo，否则横贯全图
                    } else {
                        stroke.lineTo(x, y)
                    }
                    pxx = x
                    j++
                }
                // 环的闭合边（末点 → 首点）：是真实海岸线的一小段，要画。
                // ⚠️ 必须**显式 lineTo**、且**单独判跳变**：对被切开的环调用 close()
                //    会闭合最后一个子路径（kept[1..n−1]），而它的首点在 lon = −180
                //    ⇒ 又补回一条 426.8 px 的弦。
                if (pen && abs(landScratch[0] - pxx) <= jump) {
                    stroke.lineTo(landScratch[0], landScratch[1])
                }
            }
            r++
        }
    }

    // ══ 小工具（⛔ 全部零分配）══════════════════════════════════════════════

    /** 钳到 0..1 且滤掉 NaN / 负值（音频通道偶发越界与异常值） */
    private fun unit(v: Float): Float = if (v.isNaN()) 0f else v.coerceIn(0f, 1f)

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        if (e1 <= e0) return if (x >= e1) 1f else 0f
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun routeClassOf(ordinal: Int): WorldRouteClass =
        if (ordinal in 0 until ROUTE_CLASSES) routeClassArr[ordinal] else routeClassArr[WorldRouteClass.FEEDER.ordinal]

    /** ⑥ 大气辉光 α：随能量缓升 + 13s 自主正弦（能量缺失时也呼吸） */
    private fun atmoAlpha(energy: Float, landPh: Float, gate: Float): Float =
        (ATMO_ALPHA_BASE + ATMO_ALPHA_ENERGY * energy * gate +
            ATMO_ALPHA_DRIFT * sin(landPh)).coerceIn(ATMO_ALPHA_MIN, ATMO_ALPHA_MAX)

    /**
     * 推进真实 UTC 时钟。
     *
     * 逐帧 `+= dtMs`（与动画时钟同源 ⇒ 晨昏线转得平滑、不受系统时钟毛刺影响）；
     * 每 [UTC_CHECK_INTERVAL_MS] 最多比对一次墙钟，漂移超 [UTC_RESYNC_MS] 就硬对齐 ——
     * 覆盖「应用被挂起后恢复」这类 dt 被 clamp 掉大量时间的情形。
     */
    private fun advanceUtc(dtMs: Long) {
        utcMs += dtMs
        if (utcMs - lastUtcCheckMs < UTC_CHECK_INTERVAL_MS) return
        lastUtcCheckMs = utcMs
        val wall = System.currentTimeMillis()
        if (abs(wall - utcMs) > UTC_RESYNC_MS) utcMs = wall
    }

    // ══ 常量（全部可调参数的唯一入口）═════════════════════════════════════

    private companion object {

        /** Double 版 2π（elapsed 全程 Double，先取模再转 Float ⇒ 三角输入恒 < 2π） */
        const val TAU_D = Math.PI * 2.0

        /** Float 版 2π（星野相位等「只在这一帧算一次」的场合用它，省一次 Double 转换） */
        const val TAU_F = 6.2831855f

        /** Float 版 π（弧高包络、角距归一化用） */
        const val PI_F = 3.1415927f

        /**
         * **灵敏度档**（工程要求：安静 / 标准 / 激烈）。⛔ 只以常量实现，**不做设置 UI** ——
         * 本效果被规格定义为零交互（无文字、无面板、无手势），加设置项会立刻破坏
         * 「任意一帧都是海报」的一致性。切换点 = [WorldRenderer.sensitivity] 一个字段。
         *
         * @property alpha 音频 EMA 平滑系数（**越小越钝**）。标准档 0.075 ⇒ 30fps 下时间
         *   常数 ≈ `−dt/ln(1−α)` = **0.43s**；安静档 0.045 ⇒ 0.69s（近乎凝固）；
         *   激烈档 0.115 ⇒ 0.28s。三档都远慢于「逐拍跳变」，满足克制的硬要求。
         * @property gain 响应幅度倍率（**乘在所有音频→视觉增益上**）。安静 0.55 把所有
         *   幅度压到标准档的一半多；激烈 1.45 放大 45%，但每个增益的**硬上限常量不变**
         *   —— 激烈档只是「更早顶到上限」，不会突破 [MICRO_MAX] 等任何硬边界。
         */
        enum class WorldSensitivity(val alpha: Float, val gain: Float) {
            /** 安静：几乎只剩自主呼吸，音频只在最深处微微露头 */
            CALM(0.045f, 0.55f),

            /** 标准（默认）：0.43s 平滑 + 全部幅度打到基准 */
            STANDARD(0.075f, 1.00f),

            /** 激烈：0.28s 平滑 + 幅度 ×1.45（仍受硬上限约束） */
            INTENSE(0.115f, 1.45f)
        }

        // ── 画质档阈值（与 WorldNetwork 同口径，避免两处漂移）────────────────
        const val LOW_PARTICLE_BUDGET = 0
        const val MED_PARTICLE_BUDGET = 150

        // ── 池容量（⛔ 运行期永不增长）──────────────────────────────────────
        /** 航线池 = [WorldNetwork.MAX_ACTIVE_FLIGHTS] = 34（规格要求 20–40，硬上限由它兜） */
        const val MAX_FLIGHTS = WorldNetwork.MAX_ACTIVE_FLIGHTS

        /** 航线等级数 = [WorldRouteClass] 的枚举个数（TRUNK/REGIONAL/FEEDER） */
        const val ROUTE_CLASSES = 3

        /** 涟漪池容量：24 ≫ 单帧生成数（≤3），round-robin 覆盖式复用，永不扩容 */
        const val RIPPLE_MAX = 24

        // ── 跨 ±180° 反子午线缝合 ───────────────────────────────────────
        /**
         * 陆地环的**投影 x 跳变**阈值，占地图宽的比例。超过它即判定为跨日期变更线的
         * 缝合段，描边必须 `moveTo` 断笔，否则是一条横贯全图的直线。
         *
         * 实测标定（LOD_MEDIUM，真实数据）：唯一命中的缝合段跳 **426.8 px**，
         * 地图宽 **742.4 px** ⇒ 比值 0.575；而 1920×1080 下最长的一条**非**缝合
         * 海岸线段不足 20 px（比值 < 0.03）。0.35 落在两者之间近 1 个数量级的空档里 ——
         * 取值几乎不影响结果，只要落在 (0.03, 0.575) 即可。
         * 1920×1080：`jump = 0.35 × 742.4 = 259.9 px`。
         */
        const val SEAM_JUMP_FRAC = 0.35f

        /**
         * 航线弧的**经度**跳变阈值（度）。与 [SEAM_JUMP_FRAC] 判定的是同一现象，
         * 但用在 [arcLon]（烘焙好的原始经度）上 —— 航线跨屏与否只取决于经度连续性，
         * 与画布尺寸无关，故转屏后无需重判。
         * 180° = 「相邻两点分处反子午线两侧」的严格判据；大圆总经度扫角 < 180°，
         * 所以一条合法最短大圆弧最多命中它一次。
         */
        const val SEAM_LON_JUMP_DEG = 180f

        /**
         * 退化极点的判定纬度（度）。`lat == ±90` 是**几何极点**：那里经度没有意义，
         * 而 Robinson 会把 lon 180 与 lon −90 画成相距 `2 × 0.8487 × 0.5322 ≈ 0.90`
         * 个归一化单位（1920×1080 上 394 px）的两点，凭空多出一条横线。
         *
         * 90.0 附近的 Robinson y 变化极慢：|lat| 从 89.9 到 90.0 只差
         * 0.1/5 × 0.0974 ≈ 0.0019 归一化单位 ⇒ 1080p 上 **0.85 px**，肉眼不可见；
         * 而横向误差 394 px 极大。故 89.9 是一个「零成本」的安全阈值。
         *
         * ⛔ 只作用于**描边**；填充仍保留极点上的点（见 [rebuildLandPaths] 的 §填充）。
         */
        const val POLAR_LAT = 89.9f

        /**
         * 大圆采样点数 = 56（规格 48–64 取中）。
         * 1920×1080 下相邻投影间距 ≈ 10–18px，肉眼读不出折线棱角；再多只是白烧 CPU。
         * 池大小 = 34 × 56 = 1,904 个采样 × 4 个 FloatArray ≈ 30KB 成员内存。
         */
        const val ARC_PTS = 56

        /** 彗尾段数（每段 2 层描边 = 8 次 `drawLine`/航线；34 条 ≈ 272 次，零分配） */
        const val TRAIL_SEGS = 4

        /** 彗尾每段跨几个采样点：4 × 3 = 12 个采样 ≈ 规格的「最近 ~10 个」 */
        const val TRAIL_STEP = 3

        /** 昼夜色带条数：地图宽 742px ⇒ 每条 7.7px，α 步进 ≈0.001，肉眼无台阶 */
        const val NIGHT_STRIPS = 96

        /** 陆地描边宽度档数：5 档覆盖 ±[LAND_BREATH]，档间 4% 宽度差不可见 */
        const val LAND_STROKE_BUCKETS = 5

        /** 星野数组上限 */
        const val STAR_MAX = 110

        // ── 布局 ───────────────────────────────────────────────────────────
        /**
         * 地图在「扣掉 overscan 后的半短边」里再留 10%。
         * 1920×1080：`usable = 540 − 54 = 486` ⇒ `mapScale = 437.4`，
         * 地图 742.5 × 874.8 px，上下各余 102.6px ⇒ 约 9.5% 的上下留白，正是海报比例。
         */
        const val MAP_MARGIN = 0.10f

        /**
         * 能量 → 整图微缩放。**硬钳在 [MICRO_MIN] 0.99 .. [MICRO_MAX] 1.02** ——
         * 规格明确要求 1%–2%，任何音频都不许让地球「胀大」到失真。
         * 1920×1080 下 2% = 17.5px 的最大位移，缓慢到几乎察觉不到。
         */
        const val MICRO_GAIN = 0.015f

        /** 自主呼吸幅度（±0.8%，[MICRO_PERIOD] 9s 一周）—— 静音时地图也不会死 */
        const val MICRO_BREATH = 0.008f
        const val MICRO_PERIOD = 9.0
        const val MICRO_MIN = 0.99f
        const val MICRO_MAX = 1.02f

        /**
         * 段落能量 → 整图「下沉」：把微缩放往 [MICRO_MIN] 拉。
         * 0.012 = 1.2%（1920×1080 下 10.5px）—— 器乐段沉、副歌抬，
         * ⛔ 只看 8s 均值 [AudioFrame.sectionEnergy]，不看瞬时能量。
         */
        const val SINK_GAIN = 0.012f

        // ── 背景 ①②③④ ───────────────────────────────────────────────────
        const val BG_BASE = 0xFF01030AL
        const val BG_CORE = 0xFF0A1220L
        const val BG_CENTER_STOP = 0.42f
        const val VIG_START = 0.52f
        const val VIG_EDGE_ALPHA = 0.46f
        const val GRAIN_TILE = 128
        const val GRAIN_TILE_HI = 256
        const val GRAIN_TILE_LARGE_MIN = 1440f
        const val GRAIN_ALPHA_MED = 0.024f
        const val GRAIN_ALPHA_HIGH = 0.032f

        // ── ⑤ 星野 ───────────────────────────────────────────────────────
        const val STAR_LOW = 40
        const val STAR_MED = 70
        const val STAR_R_MIN = 0.0009f
        const val STAR_R_MAX = 0.0020f
        const val STAR_A_MIN = 0.10f
        const val STAR_A_MAX = 0.28f
        const val STAR_DRIFT = 0.010f
        const val STAR_DRIFT_PERIOD = 41.0
        const val STAR_PULSE_PERIOD = 13.0

        // ── ⑥ 陆地填充 + 大气环 ──────────────────────────────────────────
        const val LAND_FILL_ALPHA = 0.26f
        const val LAND_FILL_ENERGY = 0.05f
        const val LAND_FILL_ALPHA_MAX = 0.34f

        /** 大气环半径（× mapScale）：1.06 ⇒ 环在两极外切、赤道外溢 = 「大气层」而非「国界」 */
        const val ATMO_RADIUS = 1.06f
        const val ATMO_INNER = 0.55f
        const val ATMO_PEAK = 0.94f
        const val ATMO_COLOR = 0xFF4A86D8L
        const val ATMO_ALPHA_BASE = 0.055f
        const val ATMO_ALPHA_ENERGY = 0.045f
        const val ATMO_ALPHA_DRIFT = 0.012f
        const val ATMO_ALPHA_MIN = 0.02f
        const val ATMO_ALPHA_MAX = 0.115f

        // ── ⑦ 陆地描边 + 填充（单一中性色，无大洲色温）────────────────────
        /**
         * 陆地本色（[landColor] 的唯一来源）：低饱和中性蓝灰，填充与描边共用。
         * 原「3 主色 + 1 中性」的分洲色温已随按大洲分组绘制一并删除，只留这一支。
         */
        const val LAND_INK = 0xFF7C8899L

        /** 内层细亮线：mapScale × 0.0030 ⇒ 1920×1080 上 1.31px，4K 上 2.63px（等比） */
        const val LAND_STROKE_K = 0.0030f
        const val LAND_STROKE_MIN = 1.0f
        const val LAND_STROKE_ALPHA = 0.42f
        const val LAND_STROKE_ENERGY = 0.10f

        /** 外层宽而淡的辉光：4.2× 内层宽（1920×1080 上 5.5px），α 只有内层的 1/4 */
        const val LAND_GLOW_K = 0.0125f
        const val LAND_GLOW_MIN = 3.0f
        const val LAND_GLOW_ALPHA = 0.11f

        /** 描边「呼吸」半幅 ±8%（外加 13s 自主正弦 ±5%）—— 细到只能感到「在动」 */
        const val LAND_BREATH = 0.08f
        const val LAND_BREATH_DRIFT = 0.05f
        const val LAND_BREATH_PERIOD = 13.0

        /**
         * 宽度档的**映射半幅** ±0.16（比 [LAND_BREATH] + [LAND_BREATH_DRIFT] = 0.13 多留
         * 0.03 余量），保证 [draw] 里算出的 bIdx 几乎永不撞档位夹取。
         * 5 档跨 0.32 ⇒ 档间 8%：1920×1080 上内层 1.31px ⇒ 每档 0.105px，不可见。
         */
        const val LAND_BREATH_BUCKET = 0.16f

        // ── ⑧ 昼夜 ───────────────────────────────────────────────────────
        const val NIGHT_COLOR = 0xFF000206L
        const val NIGHT_ALPHA = 0.34f

        // ── ⑨ 城市光点 ───────────────────────────────────────────────────
        /**
         * 光点**亮核半径**（× mapScale，tier 1..4）。
         * 1920×1080（mapScale 437.4）：**3.94 / 2.97 / 2.19 / 1.57 px**；
         * 4K 等比放大到 7.9 / 5.9 / 4.4 / 3.1 px。Tier4 刻意压到 1.5px ——
         * 末端节点就该是「几乎看不见的小点」。
         */
        val DOT_CORE_R = floatArrayOf(0.0090f, 0.0068f, 0.0050f, 0.0036f)

        /** 径向渐变光晕半径倍率（× 亮核半径）：3.4 → Tier1 光晕直径 26.7px */
        val DOT_GLOW_R = floatArrayOf(3.4f, 2.9f, 2.4f, 2.0f)

        /** 渐变中段位置：0.32 处 α 0.30，边缘渐隐到 0 ⇒ 亮核 + 柔和外缘 */
        const val DOT_GLOW_MID = 0.32f
        const val DOT_GLOW_ALPHA = 0.55f
        const val DOT_BASE_ALPHA = 0.88f
        const val DOT_NIGHT_ALPHA_LO = 0.72f
        const val DOT_ALPHA_MAX = 1f

        /** 外晕两层（半径倍率 + α）：1 层常在、2 层 MEDIUM 起 */
        const val HALO2_R = 2.1f
        const val HALO2_ALPHA = 0.16f
        const val HALO3_R = 3.3f
        const val HALO3_ALPHA = 0.07f

        /** Bass → 亮核半径呼吸 ±22%（1920×1080 上 Tier1 呼吸幅度 ±0.87px，可感不刺眼） */
        const val DOT_BREATH = 0.22f

        /** Treble → 「多长出一点光」+40%（⛔ 不是缩放，缩放会让光点看起来在抖） */
        const val SPARKLE_GAIN = 0.40f

        /** WEAK 拍点闪烁的 tier 权重：Tier4 满额、Tier1 仅 1/10（"末端节点最活跃"） */
        val TWINKLE_W = floatArrayOf(0.10f, 0.22f, 0.55f, 1.00f)

        /** 焦点城市的额外亮度 +30% */
        const val FOCUS_ALPHA_GAIN = 0.30f

        /** Twinkle 衰减 1.8/s ⇒ 一次 WEAK 拍点的闪烁约 0.55s 衰减完 */
        const val TWINKLE_DECAY = 1.8f

        // ── ⑩ 航线 ───────────────────────────────────────────────────────
        const val ROUTE_TRUNK = 0xFFBFE0FFL
        const val ROUTE_REGIONAL = 0xFF8FC0E8L
        const val ROUTE_FEEDER = 0xFF6E8FB8L

        /** 线宽：mapScale × 0.0044 ⇒ 1920×1080 上主干 1.92px（层级比 1 : 0.66 : 0.44） */
        const val ROUTE_STROKE_K = 0.0044f
        const val ROUTE_STROKE_MIN = 0.9f
        val ROUTE_STROKE_CLASS = floatArrayOf(1.00f, 0.66f, 0.44f)

        /** 外层辉光 3.2× 宽、1/4.5 α（与陆地描边同款两层结构） */
        const val ROUTE_GLOW_W = 3.2f
        const val ROUTE_GLOW_ALPHA = 0.22f
        const val ROUTE_ALPHA = 0.72f

        /**
         * 弧高基线（归一化地图单位，× [WorldNetwork.arcBoost]）。
         * 0.075 ⇒ 主干最大隆起 = 1920×1080 上 **32.8px**；毛细 = 0.38 × 0.075 = 12.5px
         * （贴地飞行）。⛔ 端点严格为 0（`sin(0) = sin(π) = 0`）⇒ 弧线起于起点、止于终点。
         */
        const val LIFT_BASE = 0.075f

        /** Mid → 弧高 +45% */
        const val LIFT_MID_GAIN = 0.45f

        /** 短程加权：长航线本身已有大圆弯度，隆起反而要压（[LIFT_DIST_FLOOR]×）；短航线要 1.0× */
        const val LIFT_DIST_FLOOR = 0.45f

        /**
         * 生命周期三个阶段占寿命的比例：`GROW_FRAC` 生长（弓形延伸）→ 抵达 →
         * 余下 `[FADE_START, 1]` 淡出。抵达帧（t 越过 [GROW_FRAC]）触发终点城市到访辉光。
         * 0.62 / 0.72 ⇒ 淡出占 28%，主干 18s 时淡出约 5s（够长，读得出「余晖」）。
         */
        const val GROW_FRAC = 0.62f
        const val FADE_START = 0.72f

        /** 淡入段占寿命 6%：主干 18s ⇒ 1.08s 淡入。⛔ 必须够长，否则航线「啪」地冒出来 */
        const val RISE_FRAC = 0.06f

        /** 航线寿命（秒）：主干 14–22 / 支线 8–14 / 毛细 5–9（规格区间原样） */
        const val DUR_TRUNK_MIN = 14f
        const val DUR_TRUNK_MAX = 22f
        const val DUR_REGIONAL_MIN = 8f
        const val DUR_REGIONAL_MAX = 14f
        const val DUR_FEEDER_MIN = 5f
        const val DUR_FEEDER_MAX = 9f

        // ── ⑪ 行进光 / 彗尾 ─────────────────────────────────────────────
        /** 头部光点半径：mapScale × 0.0034 ⇒ 1920×1080 上主干 1.49px */
        const val HEAD_R_K = 0.0034f
        val HEAD_R_CLASS = floatArrayOf(1.00f, 0.74f, 0.56f)

        /** Treble → 头部半径微胀 +18% */
        const val HEAD_BREATH = 0.18f

        /** Treble → 彗尾/头部亮度 +30% */
        const val TRAIL_TREBLE_GAIN = 0.30f

        /** 每段向尾部收窄 22%（4 段后仍剩 34% ⇒ 尾巴自然尖） */
        const val TRAIL_TAPER = 0.22f

        /** 外层宽淡描边倍率与 α */
        const val TRAIL_WIDE_K = 1.8f
        const val TRAIL_WIDE_ALPHA = 0.30f
        const val HEAD_ALPHA = 0.85f
        const val HEAD_CORE_ALPHA = 0.95f

        // ── ⑫ 涟漪 ───────────────────────────────────────────────────────
        /** 最大半径 = mapScale × 0.17 ⇒ 1920×1080 上 74.3px（够大但不盖住地图） */
        const val RIPPLE_MAX_R = 0.17f
        const val RIPPLE_EASE = 1.55f
        const val RIPPLE_DURATION = 2.6f
        const val RIPPLE_MID_R = 0.74f
        const val RIPPLE_CORE_R = 0.50f

        /** 前沿（最外）环最亮：0.30 / 0.18 / 0.09 —— 读作向外推的冲击波 */
        const val RIPPLE_ALPHA_LEAD = 0.30f
        const val RIPPLE_ALPHA_MID = 0.18f
        const val RIPPLE_ALPHA_TAIL = 0.09f

        /** 抵达时沿航线行进的那圈涟漪，强度是城市涟漪的 45%（次要层，不抢主角） */
        const val ARRIVE_TRAIL_RIPPLE = 0.45f

        /** 每次生成有 [DEPART_RIPPLE_P] 的概率在起点播一圈（非 STRONG 时） */
        const val DEPART_RIPPLE_P = 0.25f

        // ── ⑨ 到访辉光 ───────────────────────────────────────────────────
        /** 航线抵达时终点城市的额外亮度 +55%（远强于焦点的 +30% ⇒ 「刚刚落了架」一眼可辨） */
        const val VISIT_GLOW_GAIN = 0.55f

        /** 到访辉光的亮核额外胀大幅度 +35%（1920×1080 上 Tier1 ≈ +1.4px） */
        const val DOT_VISIT_BREATH = 0.35f

        /** 到访辉光线性衰减 1.4/s ⇒ 一次抵达约 0.7s 衰减完（不长不短，「刚发生过」的感觉） */
        const val VISIT_DECAY = 1.4f

        /**
         * 生成时因「端点已挂满」而重抽的次数上限（[ROUTE_RETRY] = 4）。
         * 4 次内找不到合法端点就接受这一条（两个端点合计最多 44 个挂点，
         * 配额总和 Tier1 64 + Tier2 40 + Tier3 30 + Tier4 6 = 140，正常绝不会用尽；
         * 这里只是防御性上限，保证**生成永不因为配额而死锁**）。
         */
        const val ROUTE_RETRY = 4

        // ── 并发数 / 疏密 ────────────────────────────────────────────────
        /** 默认并发航线数 ≈ 22（规格「默认 ≈22」，上限由 maxActiveFlights 收） */
        const val DEFAULT_ACTIVE_FLIGHTS = 22

        /**
         * 静默段的地板比例 0.42 ⇒ 目标 22 → 9.2 ≈ **9 条**（≥ [MIN_ACTIVE_FLIGHTS] 6）。
         * ⛔ 绝不归零 —— 航线全灭会让画面瞬间「死掉」。
         */
        const val SILENCE_FLOOR = 0.42f

        /** 平均寿命（秒），只用于把「生成间隔」换算成「并发数」：10.6 × 22 ≈ 233 ⇒ 10.6s 补 22 条 */
        const val AVG_DURATION = 10.6f

        /** 目标生成间隔下限 0.14s（激烈档 + 高 mid 时最快 ~7 条/秒，仍远低于池容量） */
        const val MIN_SPAWN_INTERVAL = 0.14f

        /** Mid → 生成速率 +60% */
        const val MID_DENSITY_GAIN = 0.60f

        /** 灵敏度对疏密的影响只取 (±gain−1) 的 40%：安静 ×0.82、激烈 ×1.18 —— 绝不喧宾夺主 */
        const val SPAWN_GAIN_SPAN = 0.40f
        const val SPAWN_GAIN_MIN = 0.7f
        const val SPAWN_GAIN_MAX = 1.35f

        /** 焦点城市把目标并发数抬高 22%（22 → 27） */
        const val FOCUS_TARGET_GAIN = 0.22f

        // ── 节拍阶梯 ─────────────────────────────────────────────────────
        /** 拍点生成的共享冷却（秒）。[BeatClassifier] 已有 110ms，这里是第二道更宽的闸。 */
        const val BEAT_SPAWN_COOLDOWN = 0.9f

        /** 拍点可把并发数推高到目标的 (1 + 0.25) 倍，之后 [topUp] 就不再补货 */
        const val BEAT_TARGET_SLACK = 0.25f

        // ── 焦点轮换 ─────────────────────────────────────────────────────
        const val FOCUS_MAX_TIER = 2
        const val FOCUS_MIN_MS = 20_000L
        const val FOCUS_MAX_MS = 30_000L

        /**
         * 焦点交叉淡入的时间常数（秒）。1.3s ⇒ 3τ = 3.9s 达 95% ⇒ 规格的「≈4s 交叉淡入」。
         * ⛔ 刻意**不**写成固定 alpha/帧 —— 那样 30fps 和 60fps 的淡入时长会差一倍。
         */
        const val FOCUS_TAU = 1.3f

        // ── 平滑 / 时钟 ──────────────────────────────────────────────────
        /** 段落能量的第二级慢 EMA（比 [WorldSensitivity.alpha] 更慢：30fps 下 ≈3s） */
        const val SECTION_EMA = 0.010f

        /** 平滑帧时（喂 maxActiveFlights 做降级），EMA 系数 0.05 ⇒ ≈0.66s @30fps */
        const val FRAME_MS_EMA = 0.05f

        /** 初始帧时假设 60fps */
        const val NOMINAL_FRAME_MS = 16.7f

        /** UTC 墙钟比对间隔 5s（`System.currentTimeMillis()` 不是免费调用，别每帧调） */
        const val UTC_CHECK_INTERVAL_MS = 5_000L

        /** 漂移超 30s 就硬对齐（覆盖「应用挂起后恢复」时 dt 被 clamp 掉的时间） */
        const val UTC_RESYNC_MS = 30_000L
    }
}
