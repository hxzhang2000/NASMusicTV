package com.nasmusic.tv.visualizer.renderers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.fx.AudioSmoother
import com.nasmusic.tv.visualizer.fx.FxLevel
import com.nasmusic.tv.visualizer.fx.ProceduralTexture
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * E44 [VisualizerTheme.MOONLIT]「明月」（`docs/moonlit-visualizer-plan.md`）。
 *
 * ## 本文件的阶段边界（⚠️ 读之前先看这段）
 * 已落地：注册链 + §3.2 层 1（夜空与海体两块**不相交**矩形，色阶由 [MoonSeascape] 这把尺给出）
 * + 层 2（星野，T6）+ 末尾暗角（层 10，T6 起锁冷蓝身份色）+ §五 的素材通路与圆盘烘焙
 * （T4：解码 / §5.4 降级 / 自适应增量烘焙 / 部分行贴图 / §5.2+ ④ `BLOOM` 加法过曝芯）
 * + §4.2 末的**相位阴影**与 §4.5 的**真实月相接入**（T5）
 * + §六 的**云场**（T7：层 3 月晕 / 层 4 远云 / 层 6 近云与云缝银边 / `occl` 单一真源与
 *   §6.3 五个消费点 / §6.6 确定性过境）+ §七 的**水面**（T8：层 7 地平带 / 光柱 glade 与粼光，
 *   数学全部在 [MoonWater]）+ 层 8（§7.5 节拍涟漪）与 §八 的**音频映射**
 *   （T9：`bass/mid/treble/energy` 四路平滑 + `beat` 触发，尺全部在 [MoonAudio]）。
 * ⛔ [drawContent] 里的顺序契约不得重排（层 4 与层 6 的远/近分界就是 `occl` 记账的分界，
 * 重排会直接做错遮挡；水面四笔的**内部顺序**是「带 → 柱 → 划 → 环」—— 划要骑在柱上，
 * 反过来压不出高光，而环在划**之后**是因为它的 α 与划**同源**于 `glitA`，先算后画才拿得到）。
 *
 * ## T9 的**落地方式**（三处不是显然的选择，⛔ 不要"顺手简化"）
 * 1. ⭐ **音频头只有一处**：`aBass / aMid / aTreb` 在 [drawContent] 开头各 `updateDt` 一次，
 *    下面所有消费点读这三个本地量。⛔ 不要在下拉里再取一次 `frame.bass` —— 平滑器是**有状态**的，
 *    同一帧调两次会把衰减推进两遍（`updateDt` 每帧每个频段**恰好一次**由 `MoonlitAudioTest` 数源码）。
 * 2. ⭐ `energy` 不进 `drawContent`：它唯一的消费者是暗角，而暗角在基类末尾施加 ⇒ 走
 *    [vignetteOverride] 在帧末读 [smEner]。⚠️ [postFx] 的字面量 `0.42f` **必须保留**
 *    （类头红线第 3 条：它同时是覆盖门禁的唯一依据、洼地合并的账、静默帧的锚）。
 * 3. ⭐ bass 同时改**海面色阶**与**月晕外半径**，两者都是着色器几何/颜色 ⇒ ⛔ 不能逐帧重建。
 *    落地成**分桶缓存**（[seaBrushOf] 32 桶 / [ensureHaloBucket] 8 桶），量化误差是**解析**的
 *    `SPAN/(2·buckets)` 并由"砍半就越线"的负向自证钉住（同 **D23** 的口径）。
 * ⚠️ `fx.dt` **已经是秒**（`RendererFx.FrameClock`），⛔ 不要再写 `fx.dt / 1000`（**偏差 D31**，
 * 文档 §八 原文按毫秒写）。
 *
 * ## T7 的**新落地方式**（⛔ 不是遗漏）
 * - `spdMul = lerp(0.85, 1.25, aMid)` 与 `beat ? 1.25 : 1` 从 T9 起是**实数**（§八）：前者只乘
 *   [MoonClouds.MoonCloudField.step] 的 `dtSec` 参数，⛔ 不许改成乘绝对时间（G5 第 ③ 条抓的
 *   就是这个）；后者只进 [MoonClouds.rimAlpha] 的 α。T7 那两个钉中性的常量已删。
 * - 软椭圆（云体 / 远云 / 银边）走 [drawSoftEllipse]：`save → translate → scale(rx, ry) →
 *   drawCircle(0, 0, 1)` + **单位半径**着色器 ⇒ 一张着色器 service 全尺寸，逐帧只 `setAlpha`。
 *   这是 [MoonClouds.softStop] 那条换算的落地形态，也是 E43 `SeasideRenderer.kt:1206`
 *   的「归一化渐变 + `Paint.setAlpha`」先例。
 * - ⭐ 近云颜色是 `(k, altT, back)` 的**连续**函数，而着色器必须能缓存 ⇒ 走
 *   [MoonClouds] 的 16×4×16 分桶（**偏差 D23**，最大通道误差由 `MoonlitTest` 扫格钉住）。
 *   ⛔ 不要退化成"只用 `setAlpha` 调亮度"：[MoonClouds.CLOUD_DARK_R] 偏蓝、
 *   [MoonClouds.CLOUD_LIT_R] 偏暖、[MoonClouds.CLOUD_SIL_R] 是剪影 —— **色相**差才是"云不像"的主因。
 * - 云与晕的裁剪走 `nativeCanvas.clipRect`（矩形裁剪），⛔ 不是 `clipPath`（类头第 4 条红线）。
 *
 * ## T4 遗留的**刻意常量**（T7 已全部替换掉）
 * `diskA / bloomA / darkEdgeA` 现在逐帧由 [MoonClouds] 从 `occl` 实算（§6.3）。
 * ⚠️ 天平动**不再**恒 0：T5 起由 [MoonPhase] 按真实 UTC 驱动（§4.5），原型那个
 *   `phaseLock:'full'` 演示开关 ⛔ 不移植（R13）。重烘判定仍按 §5.2 走同一台增量机。
 *
 * ## ⛔ 四条会让门禁**静默失效**的写法（承 E43 `SeasideRenderer` 的同类条款）
 * 1. ⛔ **类头必须单行** `class MoonlitRenderer(…) : RendererFx() {` ——
 *    `FxCoverageScanTest` 与 `RendererBaseContractTest` 都对类头 400 字符做 `indexOf('{')`；
 *    `: RendererFx(` 之前出现 `{` 会把本类**不报错地**踢出扫描。
 * 2. ⛔ 每个每帧绘制辅助函数必须带 `DrawScope.` 接收者 —— `PerfBudgetContractTest` 的正则
 *    只匹配 `fun DrawScope.drawXxx(`，漏掉接收者就整段逃过零分配扫描。
 * 3. ⛔ [postFx] 里的**每一项数值必须是纯数字字面量**、无类型标注、无 getter、无具名常量 ——
 *    `FxCoverageScanTest.postFxRe` 是 `PostFx\(([^)]*)\)` 配 `numRe = =\s*([0-9.]+)f`，
 *    它要求**至少一项**能按字面数字读出；把 `0.42f` 换成 `VIGNETTE_A` 会让本效果**不报错地**
 *    掉出覆盖名单（承 E43 同类条款）。
 *    ⚠️ 唯一例外是 `vignetteEdge = <具名 Color>`：它不是数值项，`postFxRe` 的 `[^)]*` 会在
 *    `Color(` 自己的 `)` 处收尾、`numRe` 仍能从 `vignette = 0.42f` 取到数 —— 先例
 *    `AdvancedRenderers.kt:509-516`（数字雨把边色锁成深绿）。多行书写同样被允许（`\s` 吃换行）。
 * 4. ⛔ 全文件零 `clipPath(`、零 `clip(RoundRect`、零 `RoundedCornerShape` —— API 22 创维真机
 *    三次复现 hwui `Region::createTJunctionFreeRegion` SIGSEGV，禁令**不分圆角与否**。
 *    圆盘的圆形轮廓由 [MoonDiskBake.bakeRows] 写死的**圆外 alpha=0** 保证，不需要任何裁剪。
 *
 * ## 时基
 * ⛔ 不读 `ctx.nowMs`：进入瞬间它是**墙钟**（`System.currentTimeMillis()`），与 `frame.timeMs`
 * 的单调时钟不同源，两者相减得约 −1.7e12 ms ⇒ 整个生命周期冻结（E42 / E43 同一个坑）。
 * 动画相位只能从 `fx.dt`（增量累加）或首帧记一次的 `fx.nowMs` 锚点推出；真实月相的日期
 * 确实要取系统时钟，但 ⛔ 它只许出现在 `MoonPhase` 的实参与 `onEnterContent` 的锚定赋值里
 * （G5 第 ② 条），且 ⛔ **不许** `t * spdMul` 这种「绝对时间乘音频调制」的形态（G5 第 ③ 条）。
 * 烘焙计时用 `elapsedRealtimeNanos()`（与动画时基无关，⚠️ 但它**必须换算成毫秒**，见 [advanceBake]）。
 */
class MoonlitRenderer(private val context: Context) : RendererFx() {

    override val theme = VisualizerTheme.MOONLIT

    /**
     * 暗角 0.42 —— §八 `frame.energy → 暗角` 区间的**静默端**（高潮时略微"打开"到 0.36）。
     *
     * ⭐ T9 已接：逐帧强度走 [vignetteOverride] → [MoonAudio.vignetteA]，而**这一行的字面量**
     * 仍然是那条包的 `aEner = 0` 端（`vignetteA(0) == 0.42f`，G15 ⑩ 钉它 ⇒ 无声帧逐像素不变）。
     * ⚠️ 原型那行 `lerp(vignHigh = 0.36, vignLow = 0.42, k)` 的字面方向与 §八 的文字相反
     * （登记为偏差 **D32**，本实现取**文字意图**：越吵越"打开"）。
     *
     * ⭐ [VIGNETTE_EDGE]（T6）：暗角边色**锁死为本效果的冷蓝身份色**，⛔ 不沿用封面 accent。
     * 本效果的accent 无关构图（§3.1 固定构图）已经够强，但若让 accent 漂移，播蓝紫封面的歌
     * 会把整幅压成蓝紫底、播暖封面的歌又把夜空抬成褐夜 —— 数字雨为同一件事付过学费
     * （`AdvancedRenderers.kt:509-516`，§11.3.6 P-2）。
     * ⚠️ 边色**不吃 `bass`**（海面吃）：暗角是全屏叠加，随鼓点变色调就是"画面每拍闪一下"。
     *
     * ⛔ **不加颗粒**（`grain = 0f`）：颗粒是第二次全屏 `drawRect`（+1.00 屏填充），而 §9.2
     * 落表 HIGH 已是 **4.57604** 屏，棘轮上限 `4.306 × 1.10 = 4.7366` ⇒ 加颗粒 = 5.58 屏，
     * 要多铺 **22%** 的屏才换来一层肉眼近乎不可分的噪点（§9.4 / 单测
     * 「负向自证 开颗粒则 HIGH 撞破棘轮」）。星空的"噪点感"由 STARFIELD 纹理自供
     * （同 E43 §4.9.2 的单向裁决）。
     * ⚠️ 也**正因为** `grain = 0`，[MoonlitRenderer] 不调 `ProceduralTexture.ensureTiled()`
     * —— 那条"不能省"的红线针对的是**配了 grain/scanline** 的效果（`OverlayFx.drawGrain`
     * 读不到平铺槽会静默不画，E42 坏过一次）；本效果的 `applyPostFx` 里 `grain`/`scanline`
     * 两支都不成立（`RendererFx.kt:107-108`），平铺槽一张也不会被读。
     */
    override val postFx = PostFx(
        vignette = 0.42f,
        vignetteEdge = VIGNETTE_EDGE,
    )

    // ── 层 1 的渐变（⛔ 色值一律由 [MoonSeascape] 这把尺给出，见该文件头）──
    // ⛔ 构造期建好：`Brush.verticalGradient(vararg)` 会分配 vararg 数组，逐帧 new 违反零分配红线。
    // 停靠位是**归一化比例** ⇒ 与画布尺寸无关，尺寸变化时无需重建（也不进任何 `draw*` 函数体）。
    // ⚠️ 天空的地平档**吃 altT**（§3.3 定稿：`rgb(9+16t, 15+12t, 30+6t)`）。本效果是**固定构图**
    //   ⇒ altT 恒为 `MoonSeascape.ALT_T = 0.6875`，于是这一档实测是 `rgb(20, 23, 34)`，
    //   ⛔ 不是 §3.1 那句"altT=0 基准色温"里的 `rgb(9, 15, 30)`（那是尺的**下端**，月贴地平时才用）。
    //   T4 曾把这行注释写成"色温随高度由 T7 接"——色温与云无关，T6 就该接，已接。
    private val skyBrush = Brush.verticalGradient(
        0f to Color(MoonSeascape.SKY_TOP),                     // 天顶 #03050c
        MoonSeascape.SKY_MID_STOP to Color(MoonSeascape.SKY_MID),  // 中段 #060b18 @0.62
        1f to Color(MoonSeascape.skyHorizon(MoonSeascape.ALT_T)),  // 地平线档
    )

    // ⚠️ 海面渐变**不是字段**而是 [seaBrushOf] 的桶缓存（§八 `sb = 1 + 0.10·(aBass − 0.4)`）：
    // T8 之前它按中性 `sb = 1` 建一条，那条 `private val seaBrush` 在 T9 必须作废 ——
    // ⛔ 不许"照旧建一条再逐帧改色"（`Brush` 不可变），也不许逐帧新建（零分配红线）。
    // 桶数与量化误差的判据在 [MoonAudio.SEA_SB_BUCKETS]。

    // ── §5.3 等距圆柱源图（解码到 IntArray，⛔ 不长期持有 Bitmap）──
    private var srcPixels: IntArray? = null
    private var srcW = 0
    private var srcH = 0

    // ── §5.1/§5.2 圆盘纹理与增量烘焙状态 ──
    /** 圆盘位图（`T × T`，`T = 2·texR`）；[diskPixels] 是它的像素缓冲，⛔ 两者必须成对释放 */
    private var diskBmp: Bitmap? = null
    private var diskPixels: IntArray? = null
    private var texR = 0
    private var bakedRow = 0
    private var rowsPerFrame = DISK_BAKE_FIRST_ROWS
    private var singleRowMs = 0.0
    private var rowSamples = 0
    private var geomW = 0f
    private var geomH = 0f

    /** 天平动（⚠️ 由 [MoonPhase] 按真实 UTC 驱动，见 [refreshPhase]）；
     *  [libWQ]/[libBQ] 是它们的 0.5° 量化值，用于 §5.2 重烘判定 */
    private var libWDeg = 0f
    private var libBDeg = 0f
    private var libWQ = 0
    private var libBQ = 0

    // ── §4.5 墙钟锚点（⚠️ 唯一合法的读法，G5 扫的就是这三行的形状）──
    /** 进入瞬间的墙钟：⛔ 只喂 [MoonPhase] 的纯函数，⛔ 不参与任何位移/动画表达式 */
    private var wallAnchorMs = 0L

    /** 进入后**首帧**的 `fx.nowMs`（单调）；`-1` 表示还没记（`onEnter` 拿不到 `fx`，§2.4） */
    private var monoAnchorMs = -1L

    /** 上次重算月相所用的 UTC 毫秒 + 缓存（60 s 一档，⛔ 不逐帧算，见 [refreshPhase]） */
    private var phaseUtcMs = 0L
    private var phaseState: MoonState? = null

    /** UV 反解的输出缓冲（2 个 float，⛔ 不在像素循环里分配） */
    private val uvTmp = FloatArray(2)

    // ── 贴图用的复用几何对象（⛔ 每帧新建 Rect/RectF 会破零分配红线）──
    private val blitSrc = Rect()
    private val blitDst = RectF()

    /** `isFilterBitmap`：`T → 2·moonR` 在 4K 上是**放大**，⛔ 关双线性会出阶梯（构造期设一次） */
    private val blitPaint = Paint().apply { isFilterBitmap = true }

    /** §4.2 末 相位阴影：**一条复用 `Path` + 一个复用 `RectF`**（⛔ 逐帧新建 = 逐帧分配） */
    private val shadowPath = Path()
    private val shadowOval = RectF()

    /** 阴影填充色 = `rgba(3,5,11, 0.62)`（与天空顶色 `#03050C` 同族，⛔ 不用纯黑：纯黑盘缘会读作剪贴洞） */
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(
            (MoonPhase.SHADOW_ALPHA * 255f).roundToInt(),
            SHADOW_DARK_R,
            SHADOW_DARK_G,
            SHADOW_DARK_B,
        )
    }

    /**
     * §6.5 新月夜的"极淡边缘"（⛔ 不是装饰月牙：它只标出暗盘的轮廓）。
     * ⚠️ α **不烤进 color**：`darkEdgeA = 0.10·(1 − occl)` 是逐帧量（T7 起），
     * 构造期写死就等于把刻意常量又钉回字段里 —— 改为 [drawDarkLimbEdge] 里 `setAlpha`。
     */
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = android.graphics.Color.argb(
            255,
            (MoonDiskBake.LIT_R * DARK_EDGE_LIT_K).roundToInt().coerceAtMost(255),
            (MoonDiskBake.LIT_G * DARK_EDGE_LIT_K).roundToInt().coerceAtMost(255),
            (MoonDiskBake.LIT_B * DARK_EDGE_LIT_K).roundToInt().coerceAtMost(255),
        )
    }

    /** §5.2+ ④ 过曝芯：预烘焙的径向渐变 + 它的半径（随画幅重建，⛔ 不逐帧构造） */
    private var bloomBrush: Brush? = null
    private var bloomR = 0f

    // ── §六 云场（T7）：状态在 [MoonCloudField]，本文件只持有**绘制资源** ──
    private val clouds = MoonCloudField()

    /**
     * 软椭圆的**唯一** `Paint`：着色器与 α 每次调用前改写（`setShader`/`setAlpha` 都是零分配），
     * ⛔ 不要每种图元建一个 Paint —— 它们的行为除颜色外完全相同。
     */
    private val softPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * ⭐ 近云着色器缓存：索引 = [MoonClouds.shaderIndex]（`k`×`altT`×`back` 三把刻度，
     * 共 [MoonClouds.CLOUD_SHADER_SLOTS] 槽）。**延迟建立**（首次命中该桶才建），
     * ⛔ 不在 `onEnter` 一次建 1024 个 native 着色器。索引是纯算术，⛔ 不许换成字符串键。
     */
    private val puffShaders = arrayOfNulls<RadialGradient>(MoonClouds.CLOUD_SHADER_SLOTS)

    /** 远云 / 银边的颜色各自恒定 ⇒ 一张就够 */
    private var farShader: RadialGradient? = null
    private var rimShader: RadialGradient? = null

    /**
     * 层 3 月晕：按 `档位 × bass 桶` 预建的渐变与它们的半径。
     *
     * ⭐ **扁平索引** `bucket·HALO_LAYERS_MAX + layer`（⛔ 不是 `Array(n) { arrayOfNulls(m) }`：
     * 后者在构造期就要 new 8 个内层数组，而 T9 之前的形态是"一桶一维"⇒ 升桶数时
     * 把二维数组留在字段里更贵，扁平一维既零逐帧分配、又让 [haloLevel] 变化时只作废整段缓存）。
     * 长度 = [MoonAudio.HALO_R_BUCKETS] × [HALO_LAYERS_MAX] = 8 × 3 = 24。
     * ⚠️ 与 [bandBrushes] 同一套**延迟逐桶**记账：⛔ 跨桶不清空（否则 `bass` 在桶边界来回抖时
     * 每帧重建 3 张渐变，等于把 §八 这口调制变成逐帧分配）。
     */
    private val haloBrushes = arrayOfNulls<Brush>(MoonAudio.HALO_R_BUCKETS * HALO_LAYERS_MAX)
    private val haloRadii = FloatArray(MoonAudio.HALO_R_BUCKETS * HALO_LAYERS_MAX)

    /** 晕渐变所按的档位；`null` = 还没建过（首帧必建） */
    private var haloLevel: MoonLevel? = null

    /** 画幅变了 ⇒ 晕的半径与圆心全废，必须连同 [haloLevel] 一起重建 */
    private var haloDirty = false

    /** 建晕渐变时用的月半径：与 [haloLevel] 一起构成**重建键**（半径是几何量，⛔ 不能只当参数传）。 */
    private var haloMoonR = 0f

    /**
     * 海面渐变的桶缓存（[MoonAudio.SEA_SB_BUCKETS] 槽，⛔ 一维之外不再套一层）。
     *
     * ⚠️ 与地平带那 16 桶不同，本缓存**不随画幅作废**：`Brush.verticalGradient` 的停靠位是
     * 归一化比例、也没给 `startY/endY` ⇒ 存的全是"与尺寸无关"的量（`sb` 只改色）。
     * ⇒ 只有 [onExitContent] 会清它（native 着色器随实例走）。
     */
    private val seaBrushes = arrayOfNulls<Brush>(MoonAudio.SEA_SB_BUCKETS)

    // ── §八 音频映射（T9）：四条包络 + 一条涟漪队列 ──

    /**
     * `bass / mid / treble` 的包络平滑器，系数取 `AudioSmoother` 的默认 `(0.35, 0.06)`
     * —— 与原型 `moonlit-preview.html:791` 的 `Smoother(0.35, 0.06)` 逐字同值。
     * ⛔ 只在 [drawContent] 的**音频头**各推进一次，下游全部消费同一份（⛔ 不许二次平滑：
     * 两级 EMA 串起来等效于一档更慢的包络，而那不在定稿里）。
     *
     * ⭐ 本效果是 `updateDt` 的**首个生产调用方**（此前全仓零消费）：固定系数的 `update`
     * 隐含 60fps 基准，而本项目真机会掉帧 ⇒ 包络必须与帧率解耦。
     * ⚠️ `fx.dt` **本身已是秒**（`FrameClock`：`frame.dt = dtMs / 1000f`）⇒ 直接传它，
     * ⛔ 不要再除 1000（§八 与 §十一 T9 行都写成了 `fx.dt/1000`，照抄慢一千倍 —— 偏差 **D31**）。
     */
    private val smBass = AudioSmoother()
    private val smMid = AudioSmoother()
    private val smTreb = AudioSmoother()

    /** `energy` 用的是原型另一档更慢的包络（[MoonAudio.ENERGY_ATTACK]，⚠️ 不是默认值）。 */
    private val smEner = AudioSmoother(MoonAudio.ENERGY_ATTACK, MoonAudio.ENERGY_RELEASE)

    /** §7.5 节拍涟漪的定长队列（⛔ 不是 `MutableList`，理由见 [MoonWater.MoonRippleQueue]）。 */
    private val ripples = MoonWater.MoonRippleQueue()

    // ── §七 水面（T8）：数学全在 [MoonWater]，本文件只做贴图 ──

    /**
     * 粼光的**复用持有者**（一帧一次 [MoonWater.MoonGlitterFrame.begin]，行/条各返回同一个对象）。
     * ⛔ 不逐帧 new：HIGH 档每帧 60 行 × 137 条，逐条分配就是把 GC 拉进绘制路径。
     */
    private val glitter = MoonWater.MoonGlitterFrame()

    /** 粼光的**动画时基**：只由 `fx.dt` 增量累加（G5；⛔ 不是墙钟，也不是 `fx.nowMs` 相减）。 */
    private var waterClockSec = 0.0

    /**
     * 光柱的三张图元着色器：柱头 / 底光带 / 粼光划。
     *
     * 三者**色相恒定**（`whiteOf` 的三个固定档位），亮度全走 paint α ⇒ 各一张就够，
     * 与 [farShader] / [rimShader] 同一套路（延迟建立，⛔ 不在构造期建 native 对象）。
     */
    private var headShader: RadialGradient? = null
    private var fogShader: RadialGradient? = null
    private var glitterShader: RadialGradient? = null

    /**
     * §7.5 涟漪的**软环**着色器：环色恒定、亮度走 paint α ⇒ 也是一张（同上面三张的套路）。
     *
     * ⚠️ 它**不是**第四张"软椭圆"着色器：芯的两个停位都是全透明（[MoonWater.RIPPLE_IN_K] 以内
     * 一点亮度都没有），这正是"环"与"斑"的分工 —— 用 [softShader] 那种三停斑去画，水面就又多
     * 一块实心亮斑（原型 `:1348` 专门为此写了 `softRing`，并留了"⛔ 不要用 softEllipse"的注释）。
     */
    private var ringShader: RadialGradient? = null

    /**
     * §7.6 地平带：按 `occl` 分桶缓存的**纵向渐变**（[MoonWater.BAND_OCCL_BUCKETS] 槽）。
     *
     * ⚠️ 只有月色标那一路随 `occl` 变（压暗标吃 `altT`，而 `altT` 由固定构图恒等，两头全透明）
     * ⇒ 分桶键只需要 `occl`。渐变存的是**绝对像素**的起止 `y` ⇒ 画幅一变必须整组作废
     * （[waterDirty]），否则带会留在上一帧的地平线上。
     */
    private val bandBrushes = arrayOfNulls<Brush>(MoonWater.BAND_OCCL_BUCKETS)

    /** 画幅变了 ⇒ [bandBrushes] 全部作废（着色器三张与尺寸无关，不用重建）。 */
    private var waterDirty = false

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        // ── §八 音频头：四个平滑量**只在这里各算一次**，层 1b/3/6/7/8 全部消费同一份 ──
        // ⚠️ 传 `fx.dt` 本身（已是秒），⛔ 不是 `fx.dt / 1000`（D31）。
        val aBass = smBass.updateDt(frame.bass, fx.dt).toDouble()
        val aMid = smMid.updateDt(frame.mid, fx.dt).toDouble()
        val aTreb = smTreb.updateDt(frame.treble, fx.dt).toDouble()
        smEner.updateDt(frame.energy, fx.dt)     // 值由 vignetteOverride 在本帧末读

        val w = size.width
        val h = size.height
        val horizonY = h * HORIZON_K
        // 层 1a 天空 [0, horizonY)：渐变铺在这块矩形上，⛔ 不是整屏
        drawRect(brush = skyBrush, size = Size(w, horizonY))
        // 层 1b 海体 [horizonY, h)：与天空**不相交** ⇒ 交界处不重复填充（§3.2 层 1）
        // ⭐ `bass → 海面亮度 sb`（§八）走**桶缓存**：`sb` 只改三条色停的亮度，⛔ 不逐帧建 Brush。
        drawRect(
            brush = seaBrushOf(MoonAudio.sbBucket(aBass)),
            topLeft = Offset(0f, horizonY),
            size = Size(w, h - horizonY),
        )
        // 层 2 星野（§3.2）：紧接层 1、在**一切自发光体之前**，⛔ 不得往下挪
        drawStarfield(horizonY)

        val moonCx = w * MOON_CX_K
        val moonCy = h * MOON_CY_K
        val moonR = minOf(w, h) * MOON_R_K

        val st = refreshPhase(fx)
        libWDeg = st.librationLonDeg
        libBDeg = st.librationLatDeg
        if (w != geomW || h != geomH) {
            setupDisk(w, h, moonR)
            haloDirty = true
            // 地平带的渐变存**绝对 y**（贴地平线）⇒ 画幅一变同样整组作废
            waterDirty = true
        }

        // 一次性档位映射（`FxLevel` 只在这里出现 ⇒ [MoonClouds] 与 [MoonOpBudget] 保持零 Android 依赖）
        val level = moonLevelOf(fx.level)
        advanceBake()

        val canvas = drawContext.canvas.nativeCanvas
        val minDim = minOf(w, h)
        val nFar = MoonClouds.plumes(level, false)
        val npFar = MoonClouds.puffs(level, false)
        val nNear = MoonClouds.plumes(level, true)
        val npNear = MoonClouds.puffs(level, true)
        // ⭐ §八 `mid → 云速`：乘子**只作用在增量** `dtSec` 上（⛔ `t * spdMul` = 云随鼓点抽搐，
        //    G5 ③ 抓的就是那个形状；§3.0(f) 第 5 条）
        clouds.step(
            fx.dt.toDouble(), MoonAudio.cloudSpdMul(aMid), w.toDouble(), horizonY.toDouble(),
            minDim.toDouble(), nFar, npFar, nNear, npNear, MoonClouds.TRANSIT_INDEX,
        )
        // §6.2 单一真源：⚠️ `occl` **只由近层算**（远层在盘后、不参与，原型 `:953`）
        val occl = clouds.occlusion(
            moonCx.toDouble(), moonCy.toDouble(), moonR.toDouble(), clouds.near, nNear
        )
        val diskA = MoonClouds.diskA(occl)
        // ⚠️ 这里的 `altT` 是**月的地平夹角**（固定构图 ⇒ 恒量），⛔ 不是斑的 `p.altT`（见 [drawCloudNear] 第 1 条）
        val haloA = MoonClouds.haloA(st.illum.toDouble(), MoonSeascape.ALT_T.toDouble(), occl)

        // 层 3 月晕（偏差 D22：§9.1 初版把它列在层 3、T7 才接）：source-over，⛔ 不是加法
        // ⭐ §八 `bass → 晕半径 ±6%`：半径是**几何**量 ⇒ 按桶建渐变（[ensureHaloBucket]），
        //    收费的平方那一截已经落进 §9.2 的 HALO 行（D34）
        val haloBkt = MoonAudio.haloBucket(aBass)
        ensureHaloBucket(moonCx, moonCy, moonR, level, haloBkt)
        drawHalo(moonCx, moonCy, haloA, level, haloBkt)
        // 层 4 远云：裁剪在天空矩形内、比近层暗得多，⛔ 不参与 occl
        drawCloudFar(canvas, horizonY, nFar, npFar)
        // 层 5a 月盘（§5.2：未烘完时只贴**已烘的行**，⛔ 不画纯白占位圆）
        drawMoonDisk(moonCx, moonCy, moonR, diskA)
        // 层 5b 过曝芯：加法混合，⛔ 必须排在相位阴影**之前**（§3.2：月相要能压住它，
        //        只有满月/近满月才吃到这口过曝）
        drawMoonBloom(moonCx, moonCy, MoonClouds.bloomA(occl))
        // 层 5c 相位阴影（§4.2 末）+ 新月夜的极淡边缘（§6.5）
        drawPhaseShadow(moonCx, moonCy, moonR, st)
        drawDarkLimbEdge(moonCx, moonCy, moonR, st.illum, MoonClouds.darkEdgeA(occl))
        // 层 6 近云 + 云缝银边（§6.4）：在月盘**之后**合成 ⇒ 盘前的斑才是背光剪影
        // ⭐ §八 `beat → 银边 ×1.25`：只在**拍那一帧**（`frame.beat` 本身只亮一帧，⛔ 不自造计时器）
        drawCloudNear(
            canvas, horizonY, moonCx, moonCy, moonR, nNear, npNear, level,
            MoonClouds.rimK(MoonClouds.rimA(occl)), MoonAudio.rimBeatK(frame.beat),
        )

        // ── 层 7 水面（§3.2 的顺序契约：地平带 → 光柱 → 粼光，⛔ 不得重排）──
        // ⚠️ `f` 取**照度** `st.illum`、`occl` 取**同一个近层遮挡**（§6.3：水面不许另算一次），
        //    于是需求 3「云一遮月，水面与盘同帧变暗」是**免费**的：两者共用同一个数。
        waterClockSec += fx.dt.toDouble()
        val seaHTop = h - horizonY
        val illum = st.illum.toDouble()
        drawHorizonBand(horizonY, w, occl)
        val reflA = MoonClouds.reflA(illum, occl)
        if (MoonWater.gladeVisible(reflA, waterClockSec)) {
            drawGlade(
                canvas, reflA, moonCx.toDouble(), moonR.toDouble(),
                horizonY.toDouble(), seaHTop.toDouble(), level,
            )
        }
        // ⭐ `glitA` **算一次**、粼光与层 8 涟漪共用（§7.5 的 α 定义里就是"与粼光同源"这个数）
        val glitA = MoonClouds.glitA(illum, occl, aTreb)
        drawGlitter(
            canvas, waterClockSec, minDim.toDouble(), horizonY.toDouble(),
            seaHTop.toDouble(), moonCx.toDouble(), level, glitA, aBass,
        )
        // 层 8（§7.5 节拍涟漪）：排在粼光**之后**——环是骑在光柱上的软亮线，反过来被划压平
        drawRipples(
            canvas, frame.beat, fx.dt.toDouble(), moonCx.toDouble(), moonR.toDouble(),
            horizonY.toDouble(), seaHTop.toDouble(), level, diskA, glitA,
        )
    }

    /**
     * §八 `energy → 暗角`：静默 [MoonAudio.VIGN_QUIET] = 0.42 → 高潮 [MoonAudio.VIGN_CLIMAX] = 0.36。
     *
     * ⭐ 读的是**本帧音频头刚推进过**的 `smEner`（`applyPostFx` 在 [drawContent] 之后跑，同帧），
     * 所以这里不需要参数也不需要缓存一个 Float —— ⛔ 不要改成逐帧现调 `frame.energy`，
     * 那等于绕过包络直接吃瞬态（暗角会随每一拍抖）。
     * ⚠️ `postFx.vignette` 那个字面量**必须留着**（[MoonAudio.VIGN_QUIET] 与它相等不是巧合，
     * 而是 G15 ⑩ 钉的"无声帧逐像素不变"）；基类条款见 `RendererFx.vignetteOverride`。
     */
    override fun vignetteOverride(fx: FxFrame): Float =
        MoonAudio.vignetteA(smEner.value.toDouble()).toFloat()

    /**
     * 层 1b 海体渐变的**桶缓存入口**（⛔ 不能在 `drawContent` 里建：`Brush.verticalGradient(vararg)`
     * 每次调用分配 vararg 数组与三个 `Pair`，与 [bandBrushOf] 同一条 `PerfBudgetContractTest` ③ 红线，
     * 而 `MoonlitSkySeaTest.②c` 专门补了这条洞）。
     *
     * ⭐ 三条色停**仍全部出自 [MoonSeascape.sea]**（`MoonlitSkySeaTest.②b` 的判据），唯一变化是
     * 亮度系数从"中性 1"换成**桶中心**的 `sb`：`sb` 只改 RGB 三通道、不改停靠位
     * （`0 / SEA_SPLIT / 1` 是归一化比例 ⇒ 与画幅无关，本缓存因此不随尺寸作废）。
     * ⚠️ 必须用 [MoonAudio.seaBrightOfBucket]（桶中心）而不是当帧连续的 `sb`：画的那条渐变
     * 是按桶建的，拿连续值去核对"画了什么"就对不上了（同 [MoonWater.bandLitABucket] 的教训）。
     */
    private fun seaBrushOf(bucket: Int): Brush {
        seaBrushes[bucket]?.let { return it }
        val sb = MoonAudio.seaBrightOfBucket(bucket).toFloat()
        val built = Brush.verticalGradient(
            0f to Color(MoonSeascape.sea(0f, sb)),
            MoonSeascape.SEA_SPLIT to Color(MoonSeascape.sea(MoonSeascape.SEA_SPLIT, sb)),
            1f to Color(MoonSeascape.sea(1f, sb)),
        )
        seaBrushes[bucket] = built
        return built
    }

    /**
     * §4.5 墙钟接入：`utcMs = 进入时的墙钟 + (fx.nowMs − 首帧的 fx.nowMs)`。
     *
     * ⚠️ 三个锚点全部**单调**：`fx.nowMs` 来自 `FrameClock`（只吃 `frame.timeMs`），
     * 墙钟只在 [onEnterContent] 读一次 ⇒ 系统时间中途被改（NTP 校时/手动改表）不会让月相
     * 往回跳，也不会把墙钟混进动画时基（G5 的 ② 条）。
     * ⛔ 不许写成 `System.currentTimeMillis()` 逐帧读 —— 那让月相与画面时基不同源。
     *
     * 60 s 才重算一次：照度日变化率最大 ≈6%/天 ⇒ 60 s 内 0.004%，肉眼零意义，
     * 但足以让跨小时播放仍与真实日期一致；⛔ 不逐帧算（一次约 30 个三角函数 + 1 个对象）。
     */
    private fun refreshPhase(fx: FxFrame): MoonState {
        if (monoAnchorMs < 0L) monoAnchorMs = fx.nowMs
        val utcMs = wallAnchorMs + (fx.nowMs - monoAnchorMs)
        val cached = phaseState
        if (cached != null && utcMs - phaseUtcMs < REPHASE_INTERVAL_MS) return cached
        phaseUtcMs = utcMs
        val fresh = MoonPhase.of(utcMs)
        phaseState = fresh
        return fresh
    }

    // ── §五 素材通路 / 烘焙机（⛔ 一律不带 `DrawScope.` 接收者：它们是低频/一次性路径，
    //    但会分配位图与数组，放进受门禁扫描的绘制函数里等于自己给自己找堵）──

    /**
     * §5.3 解码 + §5.4 降级。在 [onEnterContent] 里**同步**跑一次（2.0 MiB 本地 JPEG，
     * 预计 15–40 ms；⛔ 不引入 `Executor`/`Handler`，那是网络图与 40 MiB 预算的配置）。
     *
     * 解完立刻 `recycle()` 源位图、只留 [IntArray]：位图在 API < 26 落在 **native 堆**
     * （`PhotoBuffer.kt:45-48`），而 IntArray 在 Java 堆、受 GC 正常管辖。
     */
    private fun loadMoonMap() {
        val bytes = try {
            context.assets.open(MOON_ASSET).use { it.readBytes() }
        } catch (t: Throwable) {
            null
        }
        if (bytes != null) {
            val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bmp = try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            } catch (t: Throwable) {
                null
            }
            if (bmp != null) {
                val w = bmp.width
                val h = bmp.height
                if (w > 0 && h > 0) {
                    val px = IntArray(w * h)
                    bmp.getPixels(px, 0, w, 0, 0, w, h)
                    bmp.recycle()
                    srcW = w
                    srcH = h
                    srcPixels = px
                    return
                }
                bmp.recycle()
            }
        }
        // §5.4：⛔ 不是平色圆盘，⛔ 也不是"什么都不画"——程序化月面走的是同一条 UV/曲线/贴图管线
        srcW = MoonDiskBake.FALLBACK_W
        srcH = MoonDiskBake.FALLBACK_H
        val fb = IntArray(srcW * srcH)
        MoonDiskBake.synthesizeFallback(fb, srcW, srcH)
        srcPixels = fb
    }

    /**
     * 按画幅建圆盘位图 + 过曝芯渐变（尺寸变化时重建；⛔ 不在 `drawContent` 的每帧路径上分配，
     * 只在 `w/h` 真的变过的那一帧跑一次）。
     */
    private fun setupDisk(w: Float, h: Float, moonR: Float) {
        geomW = w
        geomH = h
        val r = MoonDiskBake.texRadius(moonR)
        if (r != texR) {
            texR = r
            val t = r * 2
            recycleDisk()
            // G8：本文件（也是全仓）唯一的 `createBitmap` 调用点，⛔ 绝不放进循环
            diskBmp = Bitmap.createBitmap(t, t, Bitmap.Config.ARGB_8888)
            diskPixels = IntArray(t * t)
            bakedRow = 0
            rowsPerFrame = DISK_BAKE_FIRST_ROWS
            singleRowMs = 0.0
            rowSamples = 0
        }
        // §6.5 的线宽随画幅（原型 `max(1, minDim·0.0012)`），⛔ 不在每帧路径上设
        edgePaint.strokeWidth = maxOf(1f, minOf(w, h) * DARK_EDGE_W_K)
        val hot = Color(MoonDiskBake.HOT_COLOR_INT)
        bloomR = moonR * BLOOM_SCALE
        bloomBrush = Brush.radialGradient(
            // 停靠位换算：画布侧 `createRadialGradient(0,0, rx·0.10, 0,0, rx)` 的芯半径 0.10、
            // 衰减点 coreK=0.30，在 Compose 的"0→圆心、1→radius"参数化下是
            // 0.10（等值区）与 0.10 + 0.30·0.90 = **0.37**。
            0f to hot.copy(alpha = 1f),
            0.10f to hot.copy(alpha = 1f),
            0.37f to hot.copy(alpha = BLOOM_CORE_A),
            1f to hot.copy(alpha = 0f),
            center = Offset(w * MOON_CX_K, h * MOON_CY_K),
            radius = bloomR,
        )
    }

    /**
     * 层 3 月晕渐变的**缓存入口**：重建键 = `档位 × 月半径`（画幅变则整段作废），
     * 桶号只做**索引**、不进键。
     *
     * ⚠️ T7 时它是"档位或画幅变化时一次建 1~3 圈"（`buildHalo`）；T9 接上 §八 的
     * `bass → 晕半径 ±6%` 之后，**半径进了渐变** ⇒ 不能照旧在档位变化时全建（那是 8 桶 × 3 圈
     * = 24 张 native 渐变，而一帧只用 3 张）。做法与 [bandBrushOf] 一致：**跨桶才建**，
     * 建的是当前那一桶（延迟、逐桶、常驻 ≤ 在场桶数）。
     * ⛔ 不要因为"半径只是 ±3.6%"就改成 `drawCircle(radius = r·k)` 逐帧乘：那要乘的是
     * **渐变本身的内/外停位**（[MoonClouds.haloStop] 按 `kR` 换算），只乘半径会把整圈渐变拉糊。
     */
    private fun ensureHaloBucket(moonCx: Float, moonCy: Float, moonR: Float, level: MoonLevel, bucket: Int) {
        if (level != haloLevel || moonR != haloMoonR || haloDirty) {
            haloLevel = level
            haloMoonR = moonR
            haloDirty = false
            var i = 0
            while (i < haloBrushes.size) {
                haloBrushes[i] = null
                haloRadii[i] = 0f
                i++
            }
        }
        val base = bucket * HALO_LAYERS_MAX
        // 首圈恒存在（三档的 `haloSpec` 最少 1 圈）⇒ 它的槽位非空就说明**这一桶已经建过**
        if (haloBrushes[base] != null) return
        buildHaloBucket(moonCx, moonCy, moonR, level, bucket)
    }

    /**
     * 建**一桶**（当前 `bass` 桶）的 1~3 圈晕渐变。
     *
     * 画布侧的画法是 `createRadialGradient(cx, cy, moonR·0.96, cx, cy, rr)`（内圈在**盘外**，
     * 第五轮修"发光层被月盘完全盖住"的关键），而 `rr = moonR·kR·(1 + 0.06·(aBass − 0.4))`
     * ⇒ 内圈**不**跟着 bass 走（原型 `:1017` 只有外半径乘 `k`）。Compose 没有内圈参数 ⇒ 按
     * [MoonClouds.haloStop] 把停位换算到"0 → 圆心、1 → 外半径"口径，换算的输入是**含 bass 的**
     * 那个 `kR`；`r` 小于首停位处 Compose 用首色填充，与画布"内圈以内平色"同义
     * （那部分反正被层 5 的盘盖掉）。
     *
     * ⚠️ 每圈的峰值 α 占比 `kA` **烤进停位**，逐帧只乘 `haloA` 一个量。
     * 空槽用 `haloRadii = -1` 标记"这桶建过、这一圈本来就没有"（LOW 只有 1 圈），
     * ⛔ 不要留 `0f` + 非 null brush，也不要靠 `null` 反复重进本函数。
     */
    private fun buildHaloBucket(moonCx: Float, moonCy: Float, moonR: Float, level: MoonLevel, bucket: Int) {
        val spec = MoonClouds.haloSpec(level)
        val k = MoonAudio.haloKOfBucket(bucket)
        val center = Offset(moonCx, moonCy)
        val hot = MoonDiskBake.HOT_K
        val base = bucket * HALO_LAYERS_MAX
        var i = 0
        while (i < HALO_LAYERS_MAX) {
            if (i >= spec.size) {
                haloBrushes[base + i] = null
                haloRadii[base + i] = -1f
            } else {
                val kR = (spec[i].kR * k).toFloat()
                val kA = spec[i].kA
                val outer = moonR * kR
                haloRadii[base + i] = outer
                haloBrushes[base + i] = Brush.radialGradient(
                    0f to Color(MoonDiskBake.hotOf(hot)).copy(alpha = kA),
                    MoonClouds.haloStop(0.0, kR.toDouble()).toFloat()
                        to Color(MoonDiskBake.hotOf(hot)).copy(alpha = kA),
                    MoonClouds.haloStop(MoonClouds.HALO_S1, kR.toDouble()).toFloat()
                        to Color(MoonDiskBake.hotOf((hot * MoonClouds.HALO_K1).toFloat()))
                            .copy(alpha = (kA * MoonClouds.HALO_A1).toFloat()),
                    1f to Color(MoonDiskBake.hotOf((hot * MoonClouds.HALO_K2).toFloat()))
                        .copy(alpha = 0f),
                    center = center,
                    radius = outer,
                )
            }
            i++
        }
    }

    /**
     * §5.2 每帧推进一段烘焙：**自适应步长** + 重烘判定。
     *
     * 步长 `rowsPerFrame = clamp(预算 / 单行毫秒, 1, 64)`，单行耗时按 EMA（权重 1/4）累计，
     * ⇒ 快设备第一帧就烘完（与同步烘**逐像素无差别**），慢设备每帧只烘得起几行。
     * 下限 1 保证无论多慢都收敛，不会永久停在空白（E43 `:677` 同条）。
     */
    private fun advanceBake() {
        val src = srcPixels ?: return
        val bmp = diskBmp ?: return
        val out = diskPixels ?: return
        val t = texR * 2

        // 重烘判定（§5.2）：天平动跨过 0.5° 档 ⇒ 从头增量重烘（⛔ 绝不同步烘整盘）
        val qW = MoonDiskBake.quantizeLibration(libWDeg)
        val qB = MoonDiskBake.quantizeLibration(libBDeg)
        if (qW != libWQ || qB != libBQ) {
            libWQ = qW
            libBQ = qB
            bakedRow = 0
        }
        if (bakedRow >= t) return

        val y0 = bakedRow
        var y1 = y0 + rowsPerFrame
        if (y1 > t) y1 = t
        val rows = y1 - y0
        if (rows <= 0) return

        // ⚠️ `elapsedRealtimeNanos()` 是**纳秒**，而 [DISK_BAKE_BUDGET_MS] 是**毫秒**：
        //   E43 在 `SeasideRenderer.kt:3131-3139` 因去掉这一步除法，步长恒为下限 1、
        //   583 行烘了 105 s。这里 ⛔ 不许再犯同一个单位错。
        val t0 = android.os.SystemClock.elapsedRealtimeNanos()
        MoonDiskBake.bakeRows(
            src, srcW, srcH, texR, y0, y1, libWDeg, libBDeg,
            MoonDiskBake.TINT_R, MoonDiskBake.TINT_G, MoonDiskBake.TINT_B, out, uvTmp,
        )
        bmp.setPixels(out, y0 * t, t, 0, y0, t, rows)
        val stepMs = (android.os.SystemClock.elapsedRealtimeNanos() - t0).toDouble() / 1_000_000.0

        val perRowMs = stepMs / rows
        singleRowMs = if (rowSamples == 0) perRowMs else singleRowMs + (perRowMs - singleRowMs) * 0.25
        rowSamples++
        val want = if (singleRowMs > 0.0) (DISK_BAKE_BUDGET_MS / singleRowMs).toInt() else DISK_BAKE_ROWS_MIN
        rowsPerFrame = when {
            want < DISK_BAKE_ROWS_MIN -> DISK_BAKE_ROWS_MIN
            want > DISK_BAKE_ROWS_MAX -> DISK_BAKE_ROWS_MAX
            else -> want
        }
        bakedRow = y1
    }

    /**
     * 层 2 星野（§3.2）：`Id.STARFIELD` **一次**带纹理绘制，⛔ 不铺全屏
     * （§9.2 记 `0.640` 屏 = `HORIZON_K`，铺满就是 `1.000` ⇒ 白付 0.36 屏）。
     *
     * ## 「裁剪到天空矩形」怎么在 ⛔ 不用 `clipPath` 的前提下做到
     * 纹理按**画布尺寸**烘一张，绘制时 `src` 只取**顶部 `skyH` 行**、`dst` 给**同一个尺寸**
     * ⇒ 1:1 拷贝，海面上的那部分星**从来没被提交过**。
     * ⛔ 两条退路都不许走：① `clipPath`/`clip(Rect)` 是 API 22 创维的 SIGSEGV 红线（类头第 4 条）；
     * ② `src` 取整张、`dst` 压到 64% —— 那是**纵向压缩**，星会挤成扁的、且地平线上方密度翻倍。
     *
     * ⭐ `BlendMode.Plus`：星是**加**在夜空上的光（§3.0(f) 第 3 条点名的两个加法点之一，
     * 原型 `globalCompositeOperation = 'lighter'`）。⛔ 不许退成 `source-over`：暗底上覆盖
     * 半透明星点会把天空那条渐变**压掉**，星不亮、天上还多一块比周围暗的斑。
     *
     * ⚠️ 纹理尺寸钳 `[1, 4096]` 与 E13/E42 同口径；1:1 只在**未触发钳位**时严格成立，
     * 画幅长边 > 4096 时左侧会少画一条星（真机最宽 3840 ⇒ 不可达，登记于此以防日后误判）。
     */
    private fun DrawScope.drawStarfield(horizonY: Float) {
        val iw = size.width.toInt().coerceIn(1, STAR_TEX_EDGE_MAX)
        val ih = size.height.toInt().coerceIn(1, STAR_TEX_EDGE_MAX)
        // 逐张点名（⛔ 不用 `ensure()`：那会一次烘 6 张全屏纹理，真机首帧黑屏 6369 ms 的根因，
        //   见 `ProceduralTexture.ensureFullscreenOnly` 的 KDoc）。本函数只吃 STARFIELD 一张。
        ProceduralTexture.ensureFullscreenOnly(ProceduralTexture.Id.STARFIELD, iw, ih)
        val tex = ProceduralTexture.tile(ProceduralTexture.Id.STARFIELD) ?: return
        val skyH = horizonY.toInt().coerceIn(1, ih)
        val sw = iw.coerceAtMost(tex.width)
        val sh = skyH.coerceAtMost(tex.height)
        drawImage(
            image = tex,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(sw, sh),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(sw, sh),
            alpha = STAR_A,
            blendMode = BlendMode.Plus,
        )
    }

    /**
     * 层 5a：贴已烘好的行。`src = (0,0,T,bakedRow)` → `dst` 同比例，⇒ 圆盘**自上而下长出来**，
     * 未烘部分留空（⛔ 不画纯白圆再"突然"变出环形山，那是切换感缺陷）。
     *
     * ⛔ 亮度只由 `diskA` 决定：照度 `f` 已体现在相位阴影几何里（T5），再乘一个 `f` 就是
     * §5.4 的"双重变暗"（原型实测 5% 的月被压成看不见的灰斑）。
     */
    private fun DrawScope.drawMoonDisk(moonCx: Float, moonCy: Float, moonR: Float, diskA: Double) {
        val bmp = diskBmp ?: return
        val baked = bakedRow
        if (baked <= 0) return
        val t = texR * 2
        blitSrc.set(0, 0, t, baked)
        val dstH = moonR * 2f * (baked.toFloat() / t)
        blitDst.set(moonCx - moonR, moonCy - moonR, moonCx + moonR, moonCy - moonR + dstH)
        blitPaint.alpha = (diskA * 255.0).roundToInt().coerceIn(0, 255)
        drawContext.canvas.nativeCanvas.drawBitmap(bmp, blitSrc, blitDst, blitPaint)
    }

    /**
     * 层 5b：加法过曝芯（§5.2+ ④）。
     *
     * ⛔ 必须走加法（`BlendMode.Plus`）：普通 alpha 覆盖会把月海一起刷成一块死白（原型实测）；
     * ⛔ 颜色乘数不得超过 1.0（`HOT_K` 已是"最大通道正好 255"，再乘就是往纯白脱色、色相永久丢失）。
     * 先用例参照：`StarrySkyRenderer.kt:298-304/:524`（预烘焙 `Brush.radialGradient` + Plus）。
     */
    private fun DrawScope.drawMoonBloom(moonCx: Float, moonCy: Float, bloomA: Double) {
        val brush = bloomBrush ?: return
        drawCircle(
            brush,
            radius = bloomR,
            center = Offset(moonCx, moonCy),
            alpha = bloomA.toFloat(),
            blendMode = BlendMode.Plus,
        )
    }

    /**
     * 层 5c：§4.2 末的**三层嵌套软化相位阴影**。
     *
     * 形状 = 「−X 侧的半圆盘」+「以 `|v|` 为半短轴的半椭圆」折回闭合，⛔ **不用 `clipPath`**
     * （红线：API 22 创维 hwui 的 `Region::createTJunctionFreeRegion` 三次真机 SIGSEGV）——
     * 之所以不需要裁剪，是因为 `|v| ≤ R` 恒成立（见 [shadowPathFor]），整个暗区**天生在盘内**。
     *
     * ⚠️ 三层**同 α**（[MoonPhase.SHADOW_ALPHA]），核心区靠叠加得到 `1−0.38³ = 0.945`；
     * 逐层递减是错的（核心区只剩 0.62，暗面读作半透玻璃）。
     *
     * ⚠️ `f ≥ 0.995` **整段跳过**（[MoonPhase.skipPhaseShadow]）：那时软化项会把本该面积趋于 0
     * 的暗带在盘缘长成一块暗斑，"满月变暗斑"就是这么来的。
     */
    private fun DrawScope.drawPhaseShadow(moonCx: Float, moonCy: Float, moonR: Float, st: MoonState) {
        val f = st.illum
        if (MoonPhase.skipPhaseShadow(f) || moonR <= 0f) return
        // §4.4：PA 的影响按 |sin e| 加权 ⇒ 朔望处退化为"不转"，一夜跳 112° 的 PA 不会把盘面抽风
        val tiltDeg = MoonPhase.shadowTiltDeg(st.brightLimbPaDeg, st.elongDeg)
        val canvas = drawContext.canvas.nativeCanvas
        canvas.save()
        canvas.translate(moonCx, moonCy)
        canvas.rotate(MoonPhase.terminatorRotDeg(tiltDeg))
        for (i in MoonPhase.SHADOW_LAYERS - 1 downTo 0) {
            val v = MoonPhase.shadowVertexPx(f, moonR, i)
            canvas.drawPath(shadowPathFor(v, moonR), shadowPaint)
        }
        canvas.restore()
    }

    /**
     * 把第 [vertex] 顶点的暗区写进**复用**的 [shadowPath]（⛔ 不返回新建 Path，⛔ 不用 Matrix）。
     *
     * 角度约定与 `android.graphics` 一致：从 +X 起、**正角朝 +Y（屏幕下方）为顺时针**，
     * 所以"顶 → 左 → 底"是 `-90°` 起扫 `-180°`。第二段半椭圆：
     * `vertex > 0` 时向 **+X（亮侧）**凸 ⇒ 暗区**超过**半盘（弦月以下）；
     * `vertex < 0` 时向 −X 让回 ⇒ 暗区**不足**半盘（凸月）。
     */
    private fun shadowPathFor(vertex: Float, moonR: Float): Path {
        val p = shadowPath
        p.reset()
        shadowOval.set(-moonR, -moonR, moonR, moonR)
        p.moveTo(0f, -moonR)
        p.arcTo(shadowOval, -90f, -180f, false)         // 暗侧半圆：顶 → 左 → 底
        val rx = abs(vertex)
        if (rx < MoonPhase.SHADOW_VERTEX_EPS) {
            p.lineTo(0f, -moonR)                        // 半短轴 = 0 ⇒ 直线弦（f = 0.5）
        } else {
            shadowOval.set(-rx, -moonR, rx, moonR)
            p.arcTo(shadowOval, 90f, if (vertex > 0f) -180f else 180f, false)
        }
        p.close()
        return p
    }

    /**
     * §6.5：`f < 0.06` 时暗盘几乎全黑，只补一条**极淡边缘**把轮廓标出来。
     *
     * ⛔ 这不是"装饰月牙"——它不给任何照度，只画盘缘一圈 1 px 级描边；照度仍然全由
     * 相位阴影几何决定。少了它，新月夜会在画面上"消失"，而所有者会当成渲染 bug
     * （§十三 V5 专门判读这一点）。
     */
    private fun DrawScope.drawDarkLimbEdge(
        moonCx: Float,
        moonCy: Float,
        moonR: Float,
        f: Float,
        darkEdgeA: Double,
    ) {
        if (f >= DARK_EDGE_MAX_F) return
        edgePaint.alpha = (darkEdgeA * 255.0).roundToInt().coerceIn(0, 255)
        drawContext.canvas.nativeCanvas.drawCircle(moonCx, moonCy, moonR, edgePaint)
    }

    /**
     * 层 3：把预建的 1~3 圈晕依次叠上（source-over，⛔ 不是加法）。
     *
     * ⚠️ 与过曝芯 [drawMoonBloom] 的分工：晕是**盘外的大气散射**（面积大、强度低、覆盖式），
     * 芯是**盘内的过曝溢出**（面积小、加法）。两者都吃 `occl`，但系数不同
     * （[MoonClouds.haloA] 乘 `1−0.75·occl`、[MoonClouds.bloomA] 还带 `diskA`）⇒ 云一挨边
     * **晕先软下去**，盘还能看见。⛔ 不许把两者合成一个"发光圈"。
     */
    private fun DrawScope.drawHalo(
        moonCx: Float,
        moonCy: Float,
        haloA: Double,
        level: MoonLevel,
        bucket: Int,
    ) {
        if (haloA < MoonClouds.MIN_ALPHA) return
        val spec = MoonClouds.haloSpec(level)
        val a = haloA.toFloat()
        val center = Offset(moonCx, moonCy)
        val base = bucket * HALO_LAYERS_MAX
        var i = 0
        while (i < spec.size) {
            val brush = haloBrushes[base + i]
            if (brush != null) drawCircle(brush, radius = haloRadii[base + i], center = center, alpha = a)
            i++
        }
    }

    /**
     * 层 4 远云（§6.4）：一整片**恒定色** `rgb(26,33,50)` 的软斑，α 只到近层的 0.55 倍。
     *
     * 裁剪走 `nativeCanvas.clipRect`（矩形裁剪）—— ⛔ 不是 `clipPath`（类头第 4 条红线），
     * 也 ⛔ 不是"把斑压进天空矩形"那种缩放近似：远层带位最高 `bandY = 0.88`，
     * 加上蛇形摆幅与斑半径会**越到海面以下**（1080p 实测约 780 > 691），不裁就在海面上留一缕纱。
     */
    private fun DrawScope.drawCloudFar(canvas: Canvas, horizonY: Float, n: Int, np: Int) {
        val shader = farShaderOf()
        val w = size.width
        canvas.save()
        canvas.clipRect(0f, 0f, w, horizonY)
        var i = 0
        while (i < n) {
            val b = clouds.far[i]
            var j = 0
            while (j < np) {
                val p = b.puffs[j]
                drawSoftEllipse(canvas, p.px, p.py, p.rx, p.ry, MoonClouds.farAlpha(p.a), shader)
                j++
            }
            i++
        }
        canvas.restore()
    }

    /**
     * 层 6 近云 + 云缝银边（§6.4）**同趟**绘制：原型的两趟就是"每个斑先画体、
     * 紧接着在它朝月的一侧补一条亮边"，⛔ 不要拆成两个循环（拆了边会被后面缕的体压掉）。
     *
     * ⚠️ 三处易错点：
     * 1. `k`（色阶自变量）吃的是**斑相对本缕脊线**的高度 `p.altT`，而 `cloudCol` 的 `altT`
     *    吃的是**月的地平夹角** [MoonSeascape.ALT_T]（原型 `:1134` 用的是 `:902` 那个全局量）——
     *    同名不同物，本效果里后者恒定、前者逐斑变化。
     * 2. `back → 1` 时 `k → 0`（[MoonClouds.toneOf] 带 `(1 − back)`）⇒ 盘前的云是**剪影**，
     *    ⛔ 不跟着 `lit` 一起变亮（"云一到月盘前就变一团白雾"就是这么来的）。
     * 3. `pd` 一次算出、[MoonClouds.backFromPd] 与朝月单位向量**共用**，⛔ 不算两遍 `hypot`。
     */
    private fun DrawScope.drawCloudNear(
        canvas: Canvas,
        horizonY: Float,
        moonCx: Float,
        moonCy: Float,
        moonR: Float,
        n: Int,
        np: Int,
        level: MoonLevel,
        rimK: Double,
        rimBeat: Double,
    ) {
        val showRim = level != MoonLevel.LOW && rimK > MoonClouds.RIM_MIN_K
        val cx = moonCx.toDouble()
        val cy = moonCy.toDouble()
        val r = moonR.toDouble()
        val w = size.width
        canvas.save()
        canvas.clipRect(0f, 0f, w, horizonY)
        var i = 0
        while (i < n) {
            val b = clouds.near[i]
            val lit = MoonClouds.litOf(
                MoonClouds.overlapD2(b.cx, b.cy, b.w, b.h, cx, cy, r)
            )
            var j = 0
            while (j < np) {
                val p = b.puffs[j]
                val pd = MoonClouds.distanceToMoon(p.px, p.py, cx, cy)
                val back = MoonClouds.backFromPd(pd, p.rx, r)
                val tone = MoonClouds.toneOf(lit, p.altT, back)
                drawSoftEllipse(
                    canvas, p.px, p.py, p.rx, p.ry,
                    MoonClouds.nearAlpha(p.a, lit, back), nearShader(tone, back)
                )
                if (showRim && back > MoonClouds.RIM_MIN_BACK) {
                    val ux = (cx - p.px) / pd
                    val uy = (cy - p.py) / pd
                    drawSoftEllipse(
                        canvas,
                        p.px + ux * p.rx * MoonClouds.RIM_OFF_K,
                        p.py + uy * p.ry * MoonClouds.RIM_OFF_K,
                        p.rx * MoonClouds.RIM_RX_K, p.ry * MoonClouds.RIM_RY_K,
                        MoonClouds.rimAlpha(back, rimK, rimBeat), rimShaderOf()
                    )
                }
                j++
            }
            i++
        }
        canvas.restore()
    }

    // ── §七 水面（T8）：地平带 → 光柱 → 粼光 ──

    /**
     * §7.6 地平带：**一次**纵向渐变 `drawRect`，⛔ 通铺全宽（`0.16` 屏填充就是这么来的）。
     *
     * 带高恒为 `0.10·h`（地上）+ `0.06·h`（水下）⇒ 它**跨在地平线上**，⚠️ 不是"贴在地平线下"
     * （D26：探针把这一笔按 `0.16·horizonK` 记成了 `0.102`，而真实矩形是全宽的 `0.16` 屏）。
     * 原型 `:1156-1159` 的两端停都是全透明 ⇒ 两头软着陆，不留硬边。
     *
     * ⭐ 渐变按 `occl` **分桶缓存**（[MoonWater.BAND_OCCL_BUCKETS] 槽，偏差 D23 的同一套路）：
     * 四个色停里只有月色标的 α 逐帧变（`0.05·(1−occl)`）；压暗标吃 `altT`，而本效果是**固定构图**
     * ⇒ 恒等；两头恒透明。于是"变 α"不等于"重建 Brush"，只等于换一个桶号。
     * ⛔ 不要改用 `drawRect` 的 `alpha` 参数去乘：那会把**整条渐变**（含透明两头）一起乘，
     * 与原型"只有月色标变 α"不同形（`MoonlitWaterTest` 钉这条换算）。
     */
    private fun DrawScope.drawHorizonBand(horizonY: Float, w: Float, occl: Double) {
        val h = size.height
        val top = horizonY - h * MoonWater.BAND_ABOVE_K.toFloat()
        val brush = bandBrushOf(MoonWater.bandOcclBucket(occl), horizonY, h)
        drawRect(
            brush = brush,
            topLeft = Offset(0f, top),
            size = Size(w, h * (MoonWater.BAND_ABOVE_K + MoonWater.BAND_BELOW_K).toFloat()),
        )
    }

    /**
     * 地平带渐变的**缓存入口**（⛔ 不能并进 [drawHorizonBand]：`Brush.verticalGradient(vararg)` 会分配
     * vararg 数组与四个 `Pair`，而 `PerfBudgetContractTest` 第 ③ 条扫的是**整个** `fun DrawScope.draw*`
     * 函数体 —— 与它实际只在缓存未命中时跑无关。同 [headShaderOf] / E43 的 `SeasideRenderer.kt:2019`：
     * 绘制函数只读缓存，建缓存的函数不带 `DrawScope.` 接收者。
     */
    private fun bandBrushOf(bucket: Int, horizonY: Float, h: Float): Brush {
        if (waterDirty) {
            var i = 0
            while (i < MoonWater.BAND_OCCL_BUCKETS) {
                bandBrushes[i] = null
                i++
            }
            waterDirty = false
        }
        bandBrushes[bucket]?.let { return it }
        val dark = Color(MoonWater.bandDarkRgb())
            .copy(alpha = MoonWater.bandDarkA(MoonSeascape.ALT_T.toDouble()).toFloat())
        val lit = Color(MoonWater.bandLitRgb())
            .copy(alpha = MoonWater.bandLitABucket(bucket).toFloat())
        val built = Brush.verticalGradient(
            0f to Color.Transparent,
            MoonWater.BAND_STOP_DARK.toFloat() to dark,
            MoonWater.BAND_STOP_LIT.toFloat() to lit,
            1f to Color.Transparent,
            startY = horizonY - h * MoonWater.BAND_ABOVE_K.toFloat(),
            endY = horizonY + h * MoonWater.BAND_BELOW_K.toFloat(),
        )
        bandBrushes[bucket] = built
        return built
    }

    /**
     * §7.2 光柱（glade）：柱头 + 1~3 颗**竖长**底光带椭圆。
     *
     * ⛔ **水面上没有月盘的像** —— §7.2 第六轮整段重做的结论（原型 `:1174-1182`）：真实夜照里
     * 看不见盘的倒影，只看见一根从地平线往观者方向**摊开的光柱**。前四轮"把盘画进水里再打碎"
     * 永远读作"水面浮着一块暗石板"，⛔ 不要"顺手补个镜像圆盘"把它请回来。
     *
     * ⚠️ 调用方已把 [MoonWater.gladeVisible] 那道门（`reflA > 0.004` **且** `tSec > 0`），
     * 本函数只负责画；逐元的隐形剔除由 [drawSoftEllipse] 自己判 α。
     */
    private fun DrawScope.drawGlade(
        canvas: Canvas,
        reflA: Double,
        moonCx: Double,
        moonR: Double,
        horizonY: Double,
        seaH: Double,
        level: MoonLevel,
    ) {
        val lumK = MoonDiskBake.LUM_K.toDouble()
        drawSoftEllipse(
            canvas, moonCx,
            MoonWater.headCy(horizonY, seaH), MoonWater.headRx(moonR), MoonWater.headRy(seaH),
            MoonWater.headAlpha(reflA, lumK), headShaderOf(),
        )
        val n = MoonWater.fogCount(level)
        var i = 0
        while (i < n) {
            drawSoftEllipse(
                canvas,
                MoonWater.fogCx(moonCx, moonR, i, waterClockSec),
                MoonWater.fogCy(horizonY, seaH, i),
                MoonWater.fogRx(moonR, i), MoonWater.fogRy(seaH, i),
                MoonWater.fogAlpha(reflA, lumK, i, waterClockSec), fogShaderOf(),
            )
            i++
        }
    }

    /**
     * §7.3 粼光：`ROWS × 按深度分档的 per` 层软椭圆（LOW 43 / MED 92 / HIGH 137 条）。
     *
     * ⭐ 几何**全部**出自 [MoonWater.MoonGlitterFrame] ⇒ 与 `MoonOpBudget.GLITTER` 那行记账用的是
     * **同一份代码**（单测直接调 [MoonWater.MoonGlitterFrame.fillFraction] 对账，⛔ 不抄数）。
     * `dpr` 取 `density`：原型画布与 `DrawScope` 都画在**设备像素**上，
     * 只有划厚地板 `1.2·dpr` 这一处真需要它（见 [MoonWater] 的「单位口径」）。
     * ⭐ 这两个数由 [MoonAudio] 的尺换算而来、⛔ 不在本函数读 `frame.treble` / `frame.bass`：
     * 全效果的包络只在 [drawContent] 的音频头推进一次，`glitA` 还额外与层 8 涟漪**共用同一个数**
     * （§7.5 的 α 定义要求同帧同值，见 [MoonWater.rippleAlpha]）。
     */
    private fun DrawScope.drawGlitter(
        canvas: Canvas,
        tSec: Double,
        minDim: Double,
        horizonY: Double,
        seaH: Double,
        moonCx: Double,
        level: MoonLevel,
        glitA: Double,
        aBass: Double,
    ) {
        glitter.begin(
            level, tSec, minDim, horizonY, seaH, moonCx,
            glitA,
            MoonDiskBake.LUM_K.toDouble(), density.toDouble(), aBass,
        )
        val shader = glitterShaderOf()
        var i = 0
        while (i < glitter.rows) {
            val r = glitter.rowAt(i)
            var k = 0
            while (k < r.per) {
                val s = glitter.streakAt(i, k)
                drawSoftEllipse(canvas, s.cx, s.cy, s.rx, s.ry, s.alpha, shader)
                k++
            }
            i++
        }
    }

    /**
     * 层 8（§7.5 节拍涟漪）：`beat` 生成一颗 → 推进全场 → 逐环画**软环**。
     *
     * ⛔ **不要 `stroke` 椭圆**：描边环是一条等亮硬线，在水面上读作"铁丝圈"（原型本轮截图实测）。
     * 芯透、缘也透 ⇒ 才像扩散的水纹（两个停位见 [MoonWater.RIPPLE_IN_K] / [MoonWater.RIPPLE_PK_K]，
     * 图元本身见 [ringShaderOf]）。
     * ⚠️ 亮度上限 [MoonWater.RIPPLE_A_MAX] = `0.075` 也别"顺手调亮"：这条环实测**正好贴在倒影
     * 下缘**，一亮就读作"盘子的投影" ⇒ 把 §7.2 那个被第六轮推翻的旧模型请回来。
     * ⭐ `diskA` / `glitA` 用调用方**同一帧的同一份**（遮月时涟漪与水面同帧退场，需求 3）。
     * ⚠️ LOW 档 `max = 0` ⇒ 整段早退。⛔ 不要改成"照样推进队列、只是不画"：那样槽位会被吃掉，
     * 切到 MED 的第一帧就冒出几颗半死的环（原型的 `if (ripMax > 0)` 就是这个意思）。
     */
    private fun DrawScope.drawRipples(
        canvas: Canvas,
        beat: Boolean,
        dtSec: Double,
        moonCx: Double,
        moonR: Double,
        horizonY: Double,
        seaH: Double,
        level: MoonLevel,
        diskA: Double,
        glitA: Double,
    ) {
        val max = MoonWater.rippleMax(level)
        if (max <= 0) return
        // 两个随机量**各抽一次**（原型 `:1280` 是两次独立的 `Math.random()`：生成位与速度**不相关**）
        if (beat) ripples.push(rng.next().toDouble(), MoonWater.rippleSp(rng.next().toDouble()), max)
        ripples.advance(dtSec, max)
        val shader = ringShaderOf()
        var i = 0
        while (i < ripples.count) {
            val p = ripples.pAt(i)
            val rr = MoonWater.rippleR(moonR, p)
            drawSoftEllipse(
                canvas, moonCx, MoonWater.rippleY(horizonY, seaH, ripples.uAt(i)),
                MoonWater.rippleRx(rr), MoonWater.rippleRy(rr),
                MoonWater.rippleAlpha(p, diskA, glitA), shader,
            )
            i++
        }
    }

    /**
     * 柱头 / 底光带 / 粼光三张**单位圆**着色器：色相恒定（`whiteOf` 的三个固定档）⇒ 各一张，
     * 延迟建立（同 [farShaderOf] / [rimShaderOf]）。亮度一律走 paint α，⛔ 不烤进色停。
     */
    private fun headShaderOf(): RadialGradient {
        headShader?.let { return it }
        val built = softShader(
            MoonDiskBake.whiteOf(MoonWater.HEAD_TONE_T.toFloat()),
            MoonWater.HEAD_CORE_K, MoonWater.HEAD_CORE_A,
        )
        headShader = built
        return built
    }

    private fun fogShaderOf(): RadialGradient {
        fogShader?.let { return it }
        val built = softShader(
            MoonDiskBake.whiteOf(MoonWater.FOG_TONE_T.toFloat()),
            MoonWater.FOG_CORE_K, MoonWater.FOG_CORE_A,
        )
        fogShader = built
        return built
    }

    private fun glitterShaderOf(): RadialGradient {
        glitterShader?.let { return it }
        val built = softShader(
            MoonDiskBake.whiteOf(MoonWater.GLIT_TONE_T.toFloat()),
            MoonWater.GLIT_CORE_K, MoonWater.GLIT_CORE_A,
        )
        glitterShader = built
        return built
    }

    /**
     * 软环着色器（原型 [softRing]，`moonlit-preview.html:1348`）：**四个**停位，芯与缘都是透明的。
     *
     * `0 → 透明、IN_K → 透明、PK_K → 峰值、1 → 透明`。峰值写 **α = 1**、亮度一律走 paint α
     * （与 [headShaderOf] / [fogShaderOf] / [glitterShaderOf] 同一条理由：⛔ 不烤进色停）。
     * ⚠️ 停位口径与 [softShader] 一致：画布侧的 `createRadialGradient(0, 0, rx·0.10, 0, 0, rx)`
     * 内圈是 `0.10`，所以原型的停位 `s` 必须经 [MoonClouds.softStop] 换算成
     * Compose 的"0 → 圆心、1 → 外半径"—— ⛔ 直接把 `0.58 / 0.86` 当停位用会把峰推到位移外。
     */
    private fun ringShaderOf(): RadialGradient {
        ringShader?.let { return it }
        val rgb = MoonWater.rippleRgb()
        val built = RadialGradient(
            0f, 0f, 1f,
            intArrayOf(TRANSPARENT, TRANSPARENT, withA(rgb, 1.0), TRANSPARENT),
            floatArrayOf(
                0f,
                MoonClouds.softStop(MoonWater.RIPPLE_IN_K).toFloat(),
                MoonClouds.softStop(MoonWater.RIPPLE_PK_K).toFloat(),
                1f,
            ),
            Shader.TileMode.CLAMP,
        )
        ringShader = built
        return built
    }

    /**
     * 软椭圆的**唯一**绘制入口 = 原型 [softEllipse]（`moonlit-preview.html:1311`）的逐字移植：
     * `save → translate → scale → 半径 1 的圆 → restore`。
     *
     * ⭐ 着色器建在**单位圆**上（半径 1）⇒ 一张 shader service 任意尺寸的斑，
     * 逐帧只有 `setAlpha` 一个变更；若把斑半径烤进着色器，44 个斑就得 44 张、且尺寸一变就全废。
     * ⚠️ 亮度**只走 paint alpha**（Skia 用 paint 的 α 调制着色器输出），
     * ⛔ 不要去改着色器里的色停 —— 那等于每斑重建 native 对象。
     *
     * ⛔ `α < 0.004`（[MoonClouds.MIN_ALPHA]）一个都不提交：原型的 `op()` 不看 α，
     * 记账不会自动回收这些隐形斑（§3.0(f) 第 3 条），Kotlin 侧必须自己挡。
     */
    private fun DrawScope.drawSoftEllipse(
        canvas: Canvas,
        cx: Double,
        cy: Double,
        rx: Double,
        ry: Double,
        alpha: Double,
        shader: RadialGradient,
    ) {
        if (alpha < MoonClouds.MIN_ALPHA || rx <= 0.0 || ry <= 0.0) return
        softPaint.shader = shader
        softPaint.alpha = (alpha * 255.0).roundToInt().coerceIn(0, 255)
        canvas.save()
        canvas.translate(cx.toFloat(), cy.toFloat())
        canvas.scale(rx.toFloat(), ry.toFloat())
        canvas.drawCircle(0f, 0f, 1f, softPaint)
        canvas.restore()
    }

    /**
     * 单位圆软椭圆着色器：三个色停 `1 → coreA → 0`（绝对停位由 [MoonClouds.softStop] 换算）。
     *
     * ⚠️ 只在**缓存未命中**时调用（低频），内部 `intArrayOf`/`floatArrayOf` 各分配一个小数组 ⇒
     * 逐帧建就是把 native 对象当垃圾扔。
     * ⚠️ 透明端写 `0x00000000` 而不是"同色 α=0"：预乘插值下 α=0 的 RGB 无意义，两种写法同值，
     * 取全 0 是因为 Skia 少做一次通道运算。
     */
    private fun softShader(rgb: Int, coreK: Double, coreA: Double): RadialGradient =
        RadialGradient(
            0f, 0f, 1f,
            intArrayOf(rgb, withA(rgb, coreA), TRANSPARENT),
            floatArrayOf(0f, MoonClouds.softStop(coreK).toFloat(), 1f),
            Shader.TileMode.CLAMP,
        )

    /**  opaque ARGB 上覆盖一个 α（对应原型的 `withA`，⛔ 不做加法溢出） */
    private fun withA(rgb: Int, a: Double): Int =
        ((a.coerceIn(0.0, 1.0) * 255.0).roundToInt().coerceIn(0, 255) shl 24) or
            (rgb and RGB_CHANNEL_MASK)

    /** 近云体着色器：按 (k, altT, back) 三把刻度分桶**延迟建立**（偏差 D23）。 */
    private fun nearShader(tone: Double, back: Double): RadialGradient {
        val idx = MoonClouds.shaderIndex(tone, MoonSeascape.ALT_T.toDouble(), back)
        val cached = puffShaders[idx]
        if (cached != null) return cached
        val built = softShader(
            MoonClouds.cloudRgbAtBucket(idx),
            MoonClouds.CLOUD_CORE_K,
            MoonClouds.CLOUD_CORE_A,
        )
        puffShaders[idx] = built
        return built
    }

    private fun farShaderOf(): RadialGradient {
        val cached = farShader
        if (cached != null) return cached
        val built = softShader(
            MoonClouds.farRgb(),
            MoonClouds.CLOUD_CORE_K,
            MoonClouds.CLOUD_CORE_A,
        )
        farShader = built
        return built
    }

    private fun rimShaderOf(): RadialGradient {
        val cached = rimShader
        if (cached != null) return cached
        val built = softShader(
            MoonClouds.rimRgb(),
            MoonClouds.RIM_CORE_K,
            MoonClouds.RIM_CORE_A,
        )
        rimShader = built
        return built
    }

    private fun recycleDisk() {
        val bmp = diskBmp
        if (bmp != null && !bmp.isRecycled) {
            try {
                bmp.recycle()
            } catch (t: Throwable) {
                // 释放失败不能让 onExit 抛出：后面还有基类的共享纹理释放要跑
            }
        }
        diskBmp = null
        diskPixels = null
    }

    override fun onEnterContent(ctx: RenderContext) {
        loadMoonMap()
        // §6.1 播种：⛔ 每次进入都重播（种子固定 ⇒ 同一场云，需求 2 的判读可复现）。
        // ⚠️ 只在**切档**时不要重播 —— 那是 [MoonClouds.plumes] 的截断读取，与本函数无关。
        clouds.reseed()
        // §4.5 锚定：墙钟**只在这里读一次**（G5 允许的两个位置之一，另一处是 `MoonPhase` 实参）。
        // monoAnchor 置 -1 ⇒ 由首帧 drawContent 记（`onEnter` 拿不到 `fx`，§2.4）。
        wallAnchorMs = System.currentTimeMillis()
        monoAnchorMs = -1L
        phaseUtcMs = 0L
        phaseState = null
        // 水面时基归零（§7.2 的 `tSec > 0` 门与粼光的 `fbm1(… + tSec·0.8)` 都吃它）。
        // ⚠️ 必须归零而不是留着：上一段播放的 `tSec` 会让新一次进入的**首帧**就跳过那道门，
        // 于是三颗底光带在 `t=0` 同相位叠成"一摞透镜"的机会又回来了（原型 `:1183` 的注释）。
        waterClockSec = 0.0
        // §八 包络归零：⛔ 不继承上一段的余温。`AudioSmoother.value` 从 0 起跳本来就是"由暗渐亮"，
        // 若留着上一次的 0.9，进入瞬间会先按满音量的晕半径/海面亮度画一帧再落回去（一次可见的闪）。
        smBass.reset()
        smMid.reset()
        smTreb.reset()
        smEner.reset()
        // 层 8 队列清空（上一次进入留下的环跨不过 `MoonCloudField.reseed` 那朵云，⛔ 更不要跨进入）
        ripples.reset()
        // ⛔ 这里不建几何：`ctx.canvasSize` 此刻是 `Size.Zero`（尺寸要到首帧 `drawContent`
        //    才可知），几何与位图一律由 [setupDisk] 在画幅已知/变化时建立。
    }

    /**
     * ⛔ 必须释放：圆盘位图（1080p 档 ≈ 406² ARGB ≈ 0.66 MiB）与源图像素都在**native/大数组**
     * 量级，API 22–25 的位图 native 内存不靠 GC 自愈（H4 修复的同款理由，`RendererFx.kt:72-83`）。
     * 共享纹理（`OverlayFx` / `ProceduralTexture`）由基类 [onExit] 释放，⛔ 子类不要再调。
     */
    override fun onExitContent() {
        recycleDisk()
        srcPixels = null
        srcW = 0
        srcH = 0
        bloomBrush = null
        geomW = 0f
        geomH = 0f
        texR = 0
        bakedRow = 0
        // 晕渐变（Brush 里含 native 着色器）与画幅/档位绑定，离开即废 ⇒ 置 null 让
        // `haloLevel` 判据重新成立（否则下次进入若档位相同，会拿**旧画幅**的圆心与半径去画）
        var i = 0
        while (i < haloBrushes.size) {
            haloBrushes[i] = null
            haloRadii[i] = 0f
            i++
        }
        haloLevel = null
        haloMoonR = 0f
        haloDirty = false
        // 云体着色器：`RadialGradient` 是持 native 指针的 Java 对象，与 `MoonlitRenderer`
        // 实例同生命周期 ⇒ 必须断引用（基类只负责 `OverlayFx`/`ProceduralTexture` 两份共享纹理）
        i = 0
        while (i < MoonClouds.CLOUD_SHADER_SLOTS) {
            puffShaders[i] = null
            i++
        }
        farShader = null
        rimShader = null
        softPaint.shader = null
        // 水面的三张着色器 + 涟漪的软环着色器 + 地平带的 16 桶渐变：同一理由（native 对象随实例走，
        // ⛔ 不留到下次进入）
        headShader = null
        fogShader = null
        glitterShader = null
        ringShader = null
        i = 0
        while (i < MoonWater.BAND_OCCL_BUCKETS) {
            bandBrushes[i] = null
            i++
        }
        // 海面的 32 桶渐变里含 native 着色器 ⇒ 同样必须断引用（⛔ 与 [bandBrushes] 不同，它不随
        // 画幅作废，所以只有这一处清它，见 [seaBrushes] 的字段注）
        i = 0
        while (i < MoonAudio.SEA_SB_BUCKETS) {
            seaBrushes[i] = null
            i++
        }
        waterDirty = false
        waterClockSec = 0.0
    }

    companion object {
        // ── §3.1 定稿构图（⚠️ `internal` 而非 `private`：[MoonDiskBake] 的色温要**引用**这四个
        //    常数而不是重抄小数，§3.1 一改色温自动跟着走 —— §3.3+ 的 `lumK` 漂移就是重抄的后果）──

        /**
         * 海平面位置（§3.1）。⚠️ **刻意不同于** E43 的 `SeasideWaves.SHORE_K = 0.620` ——
         * 那是"浪能冲上来"的岸线，这里是一条不动的分界线。
         */
        internal const val HORIZON_K = 0.640f

        /** 月盘圆心 X（§3.1 左置构图，⛔ 不要"居中更好看"） */
        internal const val MOON_CX_K = 0.280f

        /** 月盘圆心 Y */
        internal const val MOON_CY_K = 0.200f

        /** 月盘半径（相对 `min(w,h)`） */
        internal const val MOON_R_K = 0.150f

        /** §5.3 资产路径（⛔ 新增资产文件即违反 G7，这里只能复用既有的这一张） */
        private const val MOON_ASSET = "globe/moon.jpg"

        /** 层 2 星野 α = **0.62**（原型 `moonlit-preview.html:1002` 的 `globalAlpha`，⛔ 不是 E13 的 0.16/0.28） */
        private const val STAR_A = 0.62f

        /** `Id.STARFIELD` 烘焙边长钳位，与 E13 / E42 同口径（`coerceIn(1, 4096)`） */
        private const val STAR_TEX_EDGE_MAX = 4096

        /**
         * `sb` 的**中性值 1**（= 无信号时的海面亮度）。
         *
         * ⚠️ T9 之后它**只服务暗角边色**（上一条）：层 1b 的海面已改走 [MoonAudio.seaBright] 的
         * 实数与桶缓存（[seaBrushOf]），⛔ 不要把它请回去当"海面的当前值"。
         * 与 [MoonAudio.NEUTRAL]（`aBass = 0.4`）是同一件事的两个写法，此处取换算后的 `sb`。
         */
        private const val SB_NEUTRAL = 1f

        /**
         * 暗角边色 = **本效果的冷蓝身份色**，⭐ 直接取 [MoonSeascape] 的地平海色（`sb = 1`），
         * ⛔ 不在这里重抄 `0xFF0A111D`（色尺改端点时它必须跟着走），⛔ 也不沿用封面 accent
         * （边色漂移会把夜空染成封面色）。`drawVignette` 见 `fx/OverlayFx.kt:44-52`。
         *
         * ⚠️ 边色**刻意不吃 `bass`**（而层 1b 的海面吃）：暗角叠在最上层，让它随鼓点变色调
         * 就是"画面每拍闪一下"；`sb = 1` 取的是尺的**中性端**，与 T8 之前逐像素同色。
         */
        private val VIGNETTE_EDGE = Color(MoonSeascape.sea(0f, SB_NEUTRAL))

        /** §5.2 步长：`预算 / 单行毫秒`，钳 `[MIN, MAX]`；首段 [FIRST_ROWS] 行实测出单行耗时 */
        private const val DISK_BAKE_ROWS_MIN = 1
        private const val DISK_BAKE_ROWS_MAX = 64
        private const val DISK_BAKE_FIRST_ROWS = 4
        private const val DISK_BAKE_BUDGET_MS = 34

        /** §5.2+ ④ 过曝芯半径（相对月盘半径）与芯的衰减保留量 */
        private const val BLOOM_SCALE = 1.28f
        private const val BLOOM_CORE_A = 0.70f

        // §八 `mid → 云速` 的乘子**已换成** [MoonAudio.cloudSpdMul]；`beat → 银边` 换成
        // [MoonAudio.rimBeatK]；`treble → 粼光` 与 `bass → 摆移` 换成 [drawContent] 音频头里的
        // `aTreb` / `aBass`。⛔ 这四个中性钉（`SPD_MUL_NEUTRAL` / `RIM_BEAT_NEUTRAL` /
        // `TREB_NEUTRAL` / `BASS_NEUTRAL`）不得以"先钉回常量再看效果"的形式回来 ——
        // 它们在 [MoonAudio] 上的等价点是 `NEUTRAL = 0.4`，而 G15 ⑯b 已经证明"静止帧逐像素
        // 等于 T8 行为"，钉常量只是把这条判据变成自证。

        /** [MoonClouds.haloSpec] 的层数上界（HIGH 档 3 圈），决定 [haloBrushes] 的长度。 */
        private const val HALO_LAYERS_MAX = 3

        /** 全透明（软椭圆渐变的外沿）。 */
        private const val TRANSPARENT = 0

        /** 取 RGB 三通道、丢掉 α（[withA] 的掩码，⛔ 不是 `0xFF000000` 那种八位色值写法）。 */
        private const val RGB_CHANNEL_MASK = 0xFFFFFF

        /** §4.2 末 阴影填充色 `rgba(3,5,11,·)` 的三通道（⛔ 不是 `0,0,0`：暗盘要与天空同族） */
        private const val SHADOW_DARK_R = 3
        private const val SHADOW_DARK_G = 5
        private const val SHADOW_DARK_B = 11

        /** §4.5 月相重算间隔（照度日变化率最大 ≈6%/天 ⇒ 60 s 内 0.004%，肉眼零意义） */
        private const val REPHASE_INTERVAL_MS = 60_000L

        /** §6.5 极淡边缘的照度上界 / α 的乘子（α 本身 = `0.10·(1−occl)`，逐帧由 [MoonClouds.darkEdgeA] 给）/ 线宽 */
        private const val DARK_EDGE_MAX_F = 0.06f
        private const val DARK_EDGE_LIT_K = 0.9f
        private const val DARK_EDGE_W_K = 0.0012f

        /**
         * `FxLevel → [MoonLevel]` 的**一次性映射**（`MoonOpBudget.kt:13-14` 规定的三行）。
         *
         * ⛔ 不要把它写进 `MoonClouds`/`MoonOpBudget`：那两个文件的前提就是**零 Android import**
         * （纯 JVM 单测可直接调用），一旦 import `FxLevel` 就破了 G3/G4 的可测性。
         * ⚠️ `OFF → LOW`（不是"关掉云"）：云是本效果的主体，LOW 档只是砍到 2 缕 / 7 斑 / 1 圈晕。
         */
        internal fun moonLevelOf(level: FxLevel): MoonLevel = when (level) {
            FxLevel.OFF -> MoonLevel.LOW
            FxLevel.LITE -> MoonLevel.MEDIUM
            FxLevel.FULL -> MoonLevel.HIGH
        }
    }
}
