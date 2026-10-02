package com.nasmusic.tv.visualizer.renderers

import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.IntSize
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.SpectrumContract
import com.nasmusic.tv.visualizer.VisualizerMath
import com.nasmusic.tv.visualizer.fx.FxLevel
import com.nasmusic.tv.visualizer.fx.ProceduralTexture
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/**
 * E42 [VisualizerTheme.STAR_TRAILS]「星空星轨」—— 长曝光星轨照片母题的频谱效果。
 *
 * ## 视觉结构（自底向上，全部在同一次 [drawContent] 内完成）
 * 1. **天幕**：⭐ **两层 + 中间「虚化平台」**的 9 档垂直渐变（暗版底 + 亮版按响度交叉淡入
 *    + 地平线大气径向）。⛔ **「虚化」不是 `RenderEffect` / `BlurMaskFilter` / shader**（本项目
 *    四处明文禁止，且 `RenderEffect` 是 API 31+ 而 minSdk 22）—— 它的实现方式是**色标间距**：
 *    上层（0.00–0.38）与下层（0.63–0.71）色标**紧**（读起来"实"），虚化带（0.38–0.63，约 25%
 *    画高）色标**宽且每步色差小**（读起来"糊"）。三张 `Brush` 只在 `rebuildGeometry` 烘一次
 *    —— ⛔ 不每帧 `new`（`Brush.verticalGradient(vararg)` 会分配 vararg 数组）。
 * 2. **静止星点**：复用 [ProceduralTexture.Id.STARFIELD]（⛔ 不新增 `ProceduralTexture.Id`）。
 * 3. **底环（圆）**：[RING_BANDS] 圈**整圈细线**（[RING_W] dp / [RING_ALPHA]），**原生分辨率**
 *    一次性烘焙进缓存位图（[rebuildRingLayer]），每帧只做 **1 次 blit**。
 * 4. ⭐ **喷溅弧场（spray，v1.5）**：数百条**短而断续**的静态弧，烘焙进 [SPRAY_BUCKETS] 条
 *    预分配 [Path]（每色一条），每帧**只**在**一个** [withTransform] 旋转下画 [SPRAY_BUCKETS] 次
 *    `drawPath`。半径按带内哈希抖动（打散同心圆的规整感）、**28% 被 [SPRAY_CUTOFF] 剔除**
 *    形成空档、相位逐弧独立 ⇒ 读作「密集闪烁场」而非整齐的同心圆。
 * 5. **亮线段（hero）**：每条环上逐帧画一段「**前亮后暗**」的亮线 —— [SEG_K] 段首尾相接的子弧，
 *    alpha 自尾向头单调爬升（[segAlphaAt]）。这是唯一的音频反应项。
 * 6. **切向流星**：鼓点帧生成，沿圆周推进的**圆弧**（§4.6.1 硬规则）。
 * 7. **极点辉光**：预烘焙 [Brush.radialGradient] 精灵，alpha 由段落响度 / 拍点闪光 / `pulse` 呼吸调制。
 * 8. **地平线辉光带**：地平线上下的一条垂直渐变亮带。
 * 9. **地景剪影**：起伏山脊（3 段三次贝塞尔）+ 一棵**枯树**，不透明、画在星轨**之上**
 *    ⇒ 星轨在地平线处自然截止。⛔ **画面中不出现任何人物剪影**（两轮原型均判失败后整体删除）。
 * 10. **后处理**：[postFx]（暗角 + 高 ISO 颗粒），由基类 [RendererFx] 在 `drawContent` 之后统一施加。
 *
 * ## ⛔ v1.4：**已删除 ping-pong 累积缓冲**
 * v1.3 用「自有双缓冲 + `PorterDuffXfermode(PorterDuff.Mode.DST_OUT)` 指数衰减」做长曝光拖尾。
 * 真机截图暴露**四个**缺陷，全部源于该架构而非参数：
 * ① 线宽画进 **960 宽降采样**缓冲再放大 ≈ 2× ⇒ 屏幕上 2.4–6.8 dp，过粗；
 * ② 自转仅 3.2°/s（≈0.1°/帧）⇒ 每帧新弧与前几十帧**叠在同一角度**，
 *    `BlendMode.Plus` 在**内侧小半径环**（弧最短 ⇒ 叠得最厚）直接饱和 ⇒ 中心一片死白；
 * ③ 逐帧短弧 + 指数衰减 ⇒ 老段变暗、新段变亮 ⇒ 读成**同心虚线**；
 * ④ 960×540 缓冲以最近邻放大到 1920×1080 ⇒ **锯齿**。
 *
 * ⇒ 架构整体换成用户口述的形态：「**用细线画圆，然后线上有一段一段的描出来亮线，
 * 前面亮后面逐渐与原来的线一样了**」（底环 = 圆；亮线段 = 线上逐段描出的亮线）。
 * `DST_OUT` / 双缓冲 / 每帧 2 次全缓冲回绘**整套删除**，附带把 §九 R1（API 22 填充率缺口）
 * 的暴露面从「每帧 2 趟全屏缓冲」降到「**无任何缓冲**」。
 *
 * ## ⛔ v1.5：分布层 —— 「喷溅弧场」（spray）
 * v1.4 真机确认**亮线本身没问题**（细、不炸、连续、无锯齿，29.7fps），但用户判**分布太均匀**、
 * **亮弧不够多**：「轨道线条可以不明显，但画出来的亮弧要更多」。
 * ⇒ **底环几乎隐去**（[RING_ALPHA] `0.26 → 0.10`），另加一层**静态喷溅弧场**：
 * 每带 [SPRAY_PER_BAND] 条短弧（FULL 6 / LITE 5 / OFF 3 ⇒ 64×6 = **384** 条），
 * 全部参数由 `(bandIndex, k)` 的**确定性哈希**给出（⛔ 非 `Math.random`，跨帧/跨 resize 稳定）。
 *
 * ⛔ **必须烘焙，绝不逐帧画**：`drawContent` 若对每条 spray 弧调一次 `drawArc`（≈384 次）
 *    会直接把 29.7fps 砍半。做法是「**一条 [Path] 装一个颜色桶的全部弧 + 一次旋转变换画完**」：
 *    烘焙期 [SPRAY_BUCKETS] 次 `Path.addArc`，每帧 [SPRAY_BUCKETS] 次 `drawPath`。
 *    ⛔ **恒等设计**：spray 弧是**静态**的（只有整体绕天极转），所以「一次变换 + 4 次 drawPath」
 *    与逐条画**像素等价**，代价却低两个数量级。门禁 `⑧ spray 必须烘焙` 锁死这一点。
 *
 * ## 渲染红线（本类逐条受门禁约束）
 * - ⛔ 无 shader / 无 AGSL `RuntimeShader` / 无 `RenderEffect` / 无 OpenGL / 无 `BitmapShader` /
 *   无 `BlendMode.Difference` / 无圆角 `clip`（API 22 三星 hwui 段错误）。
 * - ⛔ **不得**重新引入 ping-pong 累积缓冲（`DST_OUT` / `PorterDuffXfermode` / `prev`·`curr`
 *   双位图）—— 那四个缺陷的共同根因，门禁有专门的「机械已删除」源码段锁死。
 * - ⛔ **弧线一律直接画在 [DrawScope] 画布上（原生分辨率 + 抗锯齿）**：⛔ 不得把弧线画进任何
 *   **降采样**离屏位图。唯一的离屏位图是 [rings] 缓存的**底环**，且它必须是**原生尺寸**。
 * - ⛔ **天幕的「虚化」只能靠渐变色标间距表达**：⛔ 不得引入 `RenderEffect` / `BlurMaskFilter` /
 *   `RuntimeShader` / 带着色器的 `ShaderBrush` —— 项目红线，且 `RenderEffect` 是 API 31+（minSdk 22）。
 * - ⛔ `drawContent` 内**零逐帧容器分配**：所有 `Paint` / `Path` / `RectF` / `FloatArray` 均为成员，
 *   渐变、地景 `Path`、底环位图只在 `onEnterContent` / 尺寸变化时重建。
 *   （唯一逐帧新建的是每柱一个的 `Stroke` —— `Stroke.width` 不可变，只能新建；
 *   与 `BatchThreeRenderers` 的环形描边同款，量级 ~64 个短命小对象/帧。）
 * - ⛔ 柱数**恒读** `frame.spectrum.size`（LOW 档另有隔柱取样），⛔ 不硬编码 64。
 * - ⛔ 缓存的底环位图在 `onEnterContent` 首行 `releaseResources()`（`RendererSwapper.sync`
 *   画质切换会重入 `onEnter`）并在 `onExitContent` 显式 `recycle()`（API 22–25 位图像素在 native 堆）。
 * - ⛔ **不**调用 `ProceduralTexture.release()` / `OverlayFx.release()`（前者归舞台、后者归基类）。
 *
 * ## 时基说明（与其它效果不同的一处刻意取舍）
 * 天极角走**单一时钟** [fx.nowMs]（比逐帧 `× dt` 累加强：天然帧率无关、无漂移）。零时刻
 * [t0Ms] 在**首帧**由 `fx.nowMs` 记一次 —— ⛔ 不在 `onEnterContent` 里记：那里拿不到 `fx`，而
 * 唯一可用的 `RenderContext.nowMs` 在进入瞬间是**墙钟**（`System.currentTimeMillis()`），
 * 与 `frame.timeMs` 的**单调**时钟（`SystemClock.uptimeMillis()`）不同源，两者相减会得到约
 * −1.7e12 ms ⇒ 天极角恒为负、整个生命周期冻结。
 */
class StarrySkyRenderer : RendererFx() {

    override val theme = VisualizerTheme.STAR_TRAILS

    /**
     * 暗角（夜景照天然契合）+ 高 ISO 长曝光颗粒。
     *
     * ⛔ **必须写数值字面量** —— 门禁 `FxCoverageScanTest` 的正则
     * `override val postFx = PostFx(...)` + `= ([0-9]*\.?[0-9]+)f` 只认字面量；写成具名常量
     * （或加类型标注 / 写成自定义 getter）会被**静默判为「未覆盖后处理」**。
     */
    override val postFx = PostFx(vignette = 0.42f, grain = 0.026f)

    // ── 底环缓存位图（原生尺寸；⛔ 不是拖尾缓冲，绝不做 ping-pong）────────
    private var rings: ImageBitmap? = null
    private var ringW = -1f
    private var ringH = -1f
    private var ringStrokePx = -1f

    // ── Spray 弧场缓存（v1.5）─────────────────────────────────────────────
    // ⛔ 这不是「拖尾缓冲」：spray 弧是**静态**几何，烘焙后每帧只旋转 + 画 [SPRAY_BUCKETS] 次。
    /** 每个颜色桶一条 [Path]，桶内全部弧以独立轮廓填入（⛔ 烘焙期建，逐帧只 `drawPath`）。 */
    private val sprayPaths = Array(SPRAY_BUCKETS) { Path() }
    /** spray 的描边样式（宽度恒定 ⇒ 只需在烘焙期建一次，逐帧零分配）。 */
    private var sprayStroke: Stroke? = null
    private var sprayW = -1f
    private var sprayH = -1f
    private var sprayStrokePx = -1f
    private var sprayPerBand = -1

    // ── 预分配绘制对象（draw 内零分配）──────────────────────────────────
    /**
     * 烘焙底环用（**只**在 [rebuildRingLayer] 用，每帧不碰）。
     *
     * `strokeCap = BUTT`：整圈是 [android.graphics.Canvas.drawCircle]，无端点；
     * 亮线段另有自己的 `Stroke`。
     */
    private val ringPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        isAntiAlias = true
    }
    private val meteorPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        isAntiAlias = true
    }
    private val groundPaint = Paint().apply { style = Paint.Style.FILL }
    private val branchPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }
    private val meteorRect = RectF()
    private val meteorPath = android.graphics.Path()
    private val ridgePath = android.graphics.Path()
    private val trunkPath = android.graphics.Path()
    private val limbPaths = Array(LIMB_SEG_MAX) { android.graphics.Path() }
    private val limbWidths = FloatArray(LIMB_SEG_MAX)
    /** Spray 每色桶的描边色（companion 的调色板，构造期拷一份便于逐帧数组下标取值）。 */
    private val sprayColors = SPRAY_COLORS
    
    private var limbSegCount = 0
    /** 天极中心（[poleCenterFor] 的输出载体，避免每次返回新数组） */
    private val poleOut = FloatArray(2)

    // ── 柱数据（容量 = [SpectrumContract.BAR_COUNT]；⛔ 每帧实际柱数读 spectrum.size）──
    private val az = FloatArray(SpectrumContract.BAR_COUNT)
    private val env = FloatArray(SpectrumContract.BAR_COUNT)

    // ── 流星池（定长 FloatArray，⛔ 不用 List）────────────────────────────
    private val mR = FloatArray(METEOR_MAX)
    private val mAng = FloatArray(METEOR_MAX)
    private val mDir = FloatArray(METEOR_MAX)
    private val mSpd = FloatArray(METEOR_MAX)
    private val mLen = FloatArray(METEOR_MAX)
    private val mAge = FloatArray(METEOR_MAX)
    private val mLife = FloatArray(METEOR_MAX)
    private val mWarm = FloatArray(METEOR_MAX)
    private var mCount = 0

    // ── 缓存的渐变 / 几何（按画布尺寸重建）──────────────────────────────
    private var darkBrush: Brush? = null
    private var brightBrush: Brush? = null
    private var poleGlowBrush: Brush? = null
    private var skyW = -1f
    private var skyH = -1f
    private var poleX = 0f
    private var poleY = 0f
    private var rInner = 0f
    private var rOuter = 0f
    private var horizonY = 0f
    private var poleGlowR = 0f

    // ── 时基与拍点 ─────────────────────────────────────────────────────
    private var t0Ms = 0L
    private var t0Set = false
    private var flare = 0f
    /** 柱方位角是否已抽过（重入 `onEnter` 时**不**重抽，见 [onEnterContent]）。 */
    private var azReady = false

    // ═══════════════════════════ 生命周期 ═══════════════════════════

    override fun onEnterContent(ctx: RenderContext) {
        // ⛔ 必须是**首行**：`RendererSwapper.sync` 在画质切换时会重入 `onEnter`
        //   （RendererSwapper.kt:89-90），不先释放 ⇒ native 堆泄漏 / 串图。
        releaseResources()
        // ⛔ **不要**在此复位 t0Ms / 幅值包络 / 流星池 / 柱方位角：
        //   画质切换的重入不是「重新进入效果」—— 复位会让天极角跳回 0°、包络清零、
        //   柱方位角整体重抽（星轨位置全变），用户看到的是「切画质后画面炸了一下」。
        //   新实例首次进入时这些字段本就是零值，无需显式清。
        // 柱方位角：首次进入时用**继承的** rng 抽一次并缓存（⛔ 不得自建 VisualizerRandom）。
        // 种子伪随机而非黄金角 —— 黄金角在等距柱上有可察觉的规则性（内侧柱挤在小半径时尤其明显）。
        if (!azReady) {
            for (i in az.indices) az[i] = rng.next() * TAU
            azReady = true
        }
        rebuildGeometry(ctx.canvasSize.width, ctx.canvasSize.height)
        // ⚠️ 底环缓存**不在**这里建：它需要 `density` 把 [RING_W]（dp）转 px，
        //   而 `onEnterContent` 拿不到 `Density` ⇒ 改为 draw 侧的 [ensureRingLayer] 惰性建
        //   （几何已由上面的 `rebuildGeometry` 就位）。⛔ 绝不进每帧构建路径。
    }

    override fun onExitContent() {
        // ⛔ 显式 recycle（API 22–25 位图像素在 native 堆，仅靠 finalizer 会延迟到 OOM）
        releaseResources()
    }

    /**
     * 释放底环缓存位图 + 清空静态 `Path`。
     *
     * ⛔ v1.4：**没有**乒乓双缓冲可释放（原先的 `prev`/`curr` 两张位图 + 两个 Canvas 已删除）。
     * ⛔ **不**调 `ProceduralTexture.release()`（归 `VisualizerStage`）、
     * ⛔ **不**调 `OverlayFx.release()`（归 [RendererFx.onExit]）。
     */
    private fun releaseResources() {
        try { rings?.asAndroidBitmap()?.recycle() } catch (_: Exception) {}
        rings = null
        ringW = -1f
        ringH = -1f
        ringStrokePx = -1f
        resetSprayPaths()
        ridgePath.reset()
        trunkPath.reset()
        meteorPath.reset()
        for (i in 0 until limbSegCount) limbPaths[i].reset()
        limbSegCount = 0
    }

    // ═══════════════════════════ 几何重建 ═══════════════════════════

    /**
     * 依画布尺寸重建**全部**尺寸相关量：渐变 Brush、山脊 / 枯树 `Path`、天极与半径。
     *
     * 调用点只有两处：[onEnterContent] 与 `drawContent` 的「尺寸变化」分支。
     */
    private fun rebuildGeometry(w: Float, h: Float) {
        if (w < 2f || h < 2f) return
        skyW = w
        skyH = h
        val minDim = if (w < h) w else h
        poleCenterFor(w, h, poleOut)
        poleX = poleOut[0]
        poleY = poleOut[1]
        rInner = R_INNER_K * minDim
        rOuter = outerRadiusFor(w, h, poleX, poleY, minDim)
        horizonY = h * HORIZON_K
        poleGlowR = kotlin.math.max(1f, rOuter * POLE_GLOW_R_K)

        // —— 天幕（暗版 3 档低色差渐变；纵向跨度即画面全高，水平无限延伸）——
        darkBrush = Brush.verticalGradient(*skyStopsDim())
        // —— 天幕（亮版；**同样的 3 个位置**，每色向淡青紫提亮 ⇒ 整层再乘 skyMix(sectionEnergy)
        //    即完成「响度 → 天色」交叉淡入，逐帧零分配）——
        brightBrush = Brush.verticalGradient(*skyStopsBright())
        // ⛔⛔ v1.7：**地平线辉光带与大气纵深两层加性光已整体删除**。
        //    它们是「读作海面」的**主要来源**（比色标表更直接）：
        //      · 辉光带：`BlendMode.Plus`、`alpha 0.42`、色 `#8C99E0`（近淡青紫），
        //        覆盖 `horizonY − 0.16h → +0.04h`（即 0.59h–0.79h，**横跨地平线**）
        //        ⇒ 在暗天空上叠出一条**又亮又在地平线上**的横带，正是「海面反光」的形状；
        //      · 大气纵深：径向 `Plus`、`alpha 0.18`、中心 `(0.5w, horizonY)`、半径 `0.5h`
        //        ⇒ 把地平线上方整体抬高。
        //    ⛔ 不是"调弱"而是"删掉"：主人原话「天空下面是不是一片海？干脆不要了」。
        //    ⇒ 逐帧天幕从 3 次 draw 降到 **2 次**（暗版 + 响度亮版）。
        // —— 天极辉光精灵（预烘焙，⛔ 不是逐帧 new）——
        poleGlowBrush = Brush.radialGradient(
            0f to POLE.copy(alpha = 1f),
            0.20f to POLE.copy(alpha = 0.55f),
            0.55f to POLE.copy(alpha = 0.16f),
            1f to POLE.copy(alpha = 0f),
            center = Offset(poleX, poleY),
            radius = poleGlowR,
        )

        buildGroundPaths(w, h)
        // ⛔ `ProceduralTexture.ensure` 的**唯一**调用点。它绝不进每帧 `drawContent`；
        //    这里同时覆盖「进入效果」与「画布尺寸变化」两条路径 —— 也正是 ProceduralTexture
        //    自己 KDoc 允许的时机（「必须在 onEnter 或尺寸变化时调用」）。
        //
        // ⭐ 用 `ensureFullscreenOnly(STARFIELD)` 而不是 `ensure`：本效果**只画 STARFIELD**，
        //    而 `ensure` 会把 6 张全屏纹理全生成（≈1240 万像素 Kotlin 逐像素 + 6480 次 JNI
        //    setPixels）。因 `ctx.canvasSize` 在 `onEnter` 时还是 `Size.Zero`，这一步必然
        //    落在**首帧**上 ⇒ 真机实测冷启动首帧黑屏 6369 ms。只生成 1 张即降到约 1/6。
        ProceduralTexture.ensureFullscreenOnly(
            ProceduralTexture.Id.STARFIELD,
            w.toInt().coerceIn(1, MAX_TEX_PX),
            h.toInt().coerceIn(1, MAX_TEX_PX),
        )
    }

    /**
     * 底环缓存位图的**唯一**有效性判据。⛔ 每帧只做几次浮点比较（零分配）。
     *
     * ⛔ [density] 必须参与缓存键：线宽是 dp（[RING_W]），改显示字号会让 px 线宽变。
     */
    private fun ensureRingLayer(w: Float, h: Float, density: Float) {
        if (w < 2f || h < 2f) return
        if (rings != null && ringW == w && ringH == h && ringStrokePx == RING_W * density) return
        rebuildRingLayer(w, h, density)
    }

    /**
     * 把 [RING_BANDS] 圈**整圈细线**烘焙进一张**原生尺寸**位图。
     *
     * ⛔ **原生尺寸**，⛔ 不是降采样缓冲：这张位图每帧 1:1 blit 回主画布，
     *    抗锯齿边不会被最近邻放大成锯齿（v1.3 的 960 宽缓冲正是锯齿的来源）。
     * ⛔ 每圈半径与 [drawSegments] 里亮线段用的是**同一个** [radiusForRange] 调用
     *    （分母同为 [RING_BANDS]）⇒ 亮线段必然精确落在自己的底环上。
     */
    private fun rebuildRingLayer(w: Float, h: Float, density: Float) {
        recycleRingLayer()
        val bw = w.toInt().coerceIn(1, MAX_TEX_PX)
        val bh = h.toInt().coerceIn(1, MAX_TEX_PX)
        if (bw < 2 || bh < 2) return
        val bmp = ImageBitmap(bw, bh)
        val cv = android.graphics.Canvas(bmp.asAndroidBitmap())
        val stroke = RING_W * density
        ringPaint.strokeWidth = stroke
        var i = 0
        while (i < RING_BANDS) {
            ringPaint.color = ringColorArgb(barRatio(i, RING_BANDS))
            cv.drawCircle(poleX, poleY, radiusForRange(i, RING_BANDS, rInner, rOuter), ringPaint)
            i++
        }
        rings = bmp
        ringW = w
        ringH = h
        ringStrokePx = stroke
    }

    /** 只释放底环位图（不清 `Path`）。 */
    private fun recycleRingLayer() {
        try { rings?.asAndroidBitmap()?.recycle() } catch (_: Exception) {}
        rings = null
        ringW = -1f
        ringH = -1f
        ringStrokePx = -1f
    }

    // ── Spray 弧场：烘焙（尺寸 / 密度 / 画质档变化时）────────────────────

    /**
     * Spray 弧场的**唯一**有效性判据（与 [ensureRingLayer] 同一套守卫，⛔ 逐帧只做几次比较）。
     *
     * ⛔ [perBand] 也必须是键的一部分：它随 `fx.level` 变（FULL/LITE/OFF 三档），
     * 切画质必须重烘焙，否则 LOW 切回 HIGH 会少画一半弧。
     */
    private fun ensureSprayLayer(w: Float, h: Float, density: Float, perBand: Int) {
        if (w < 2f || h < 2f) return
        if (sprayStroke != null && sprayW == w && sprayH == h &&
            sprayStrokePx == SPRAY_W * density && sprayPerBand == perBand
        ) {
            return
        }
        rebuildSprayPaths(w, h, density, perBand)
    }

    /**
     * 把 `RING_BANDS × perBand` 条 spray 弧按颜色分桶烘焙进 [sprayPaths]。
     *
     * ⛔ **只在尺寸 / 密度 / 画质档变化时调用**（[ensureSprayLayer] 守卫）—— 每帧重建
     *    等于把 384 次 `addArc` 塞进绘制路径，会砍掉一半帧率。
     *
     * 每条弧的半径 / 活跃位 / 相位 / 扫掠角 / 颜色桶**全部**由 `(bandIndex, k)` 的
     * 确定性哈希给出 ⇒ 逐帧、跨 resize 完全稳定（门禁直调 companion 纯函数验证）。
     *
     * ⚠️ **每段弧前 ⛔ 必须先 `moveTo`**：Compose 的 `Path.addArc(oval, start, sweep)`
     * **没有** `forceMoveTo` 参数（3 参重载，已用 `javap` 核对 1.9.3 的实际签名），
     * 它默认**续接当前轮廓** ⇒ 不先 `moveTo` 就会把本弧的起点与上一段弧的终点连成一条
     * 横穿全场的**长直线**，形似 §4.6.1 判失败的「鱼刺」缺陷。`moveTo` 到本弧自己的
     * 起点即等价于 `forceMoveTo = true`，且**零分配**（`Path` 无 `new` 调用）。
     */
    private fun rebuildSprayPaths(w: Float, h: Float, density: Float, perBand: Int) {
        for (i in 0 until SPRAY_BUCKETS) sprayPaths[i].reset()
        var band = 0
        while (band < RING_BANDS) {
            val anchor = radiusForRange(band, RING_BANDS, rInner, rOuter)
            val gap = localGapFor(band, RING_BANDS, rInner, rOuter)
            var k = 0
            while (k < perBand) {
                if (sprayActiveFor(band, k)) {
                    val r = sprayRadiusFor(band, k, anchor, gap)
                    val phase = sprayPhaseFor(band, k)
                    val p = sprayPaths[sprayBucketFor(band, k)]
                    // ⛔ moveTo 到本弧自己的起点（= 强制新轮廓），否则 addArc 会与上一段连线
                    val rad = phase * DEG_PER_RAD_INV
                    p.moveTo(poleX + r * cos(rad), poleY + r * sin(rad))
                    // ⚠️ `Path.addArc` 只收 `androidx.compose.ui.geometry.Rect`（不可变 data class，
                    //   ⛔ 没有可复用的可变载体）⇒ 每弧必构造一次。这是**烘焙期**的一次性堆分配，
                    //   与 `PerfBudgetContractTest` 的「零分配」红线**作用域不同**（那条只管
                    //   `DrawScope.draw*` 可达的每帧路径）⇒ 挂 Perf-exempt 放行。
                    val oval = Rect(poleX - r, poleY - r, poleX + r, poleY + r) // Perf-exempt: 烘焙期一次性构造，非每帧路径
                    p.addArc(oval, phase, spraySweepFor(band, k))
                }
                k++
            }
            band++
        }
        sprayStroke = Stroke(width = SPRAY_W * density, cap = StrokeCap.Butt)
        sprayW = w
        sprayH = h
        sprayStrokePx = SPRAY_W * density
        sprayPerBand = perBand
    }

    /** 清空 spray 路径（`Path` 无 `recycle()` 可调，只能 `reset()` 释放轮廓数据）。 */
    private fun resetSprayPaths() {
        for (i in 0 until SPRAY_BUCKETS) sprayPaths[i].reset()
        sprayStroke = null
        sprayW = -1f
        sprayH = -1f
        sprayStrokePx = -1f
        sprayPerBand = -1
    }

    // ═══════════════════════════ 每帧绘制 ═══════════════════════════

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return
        if (w != skyW || h != skyH) {
            releaseResources()
            rebuildGeometry(w, h)
        }
        // 底环惰性烘焙（⛔ 命中缓存时只有几次浮点比较；绝不每帧重建）
        ensureRingLayer(w, h, density)
        // ⭐ Spray 弧场惰性烘焙（⛔ 同上；perBand 随画质档变，故也是缓存键）
        ensureSprayLayer(w, h, density, sprayPerBandFor(fx.level))
        val iw = w.toInt()
        val ih = h.toInt()

        // 零时刻只记一次（onEnterContent 拿不到 fx；且 ctx.nowMs 与 frame.timeMs 不同源，见类 KDoc）
        if (!t0Set) {
            t0Ms = fx.nowMs
            t0Set = true
        }

        // ① 天幕（**一整片**低色差垂直渐变：暗版底 + 亮版按响度交叉淡入）
        //    ⛔ 两张 Brush 都只在 rebuildGeometry 里烘一次；本段逐帧只有 2 次 drawRect
        //    ⛔ v1.7：地平线大气 drawCircle 与地平线辉光带已删除（见 rebuildGeometry 的说明）
        val sky = skyMix(frame.sectionEnergy)
        darkBrush?.let { drawRect(it) }
        if (sky > 0f) brightBrush?.let { drawRect(it, alpha = sky) }

        // ② 静止星点（复用共享 STARFIELD；⛔ 未 ensure 时 tile() 返回 null，静默跳过）
        ProceduralTexture.tile(ProceduralTexture.Id.STARFIELD)?.let {
            drawImage(it, dstSize = IntSize(iw, ih), alpha = STARFIELD_ALPHA)
        }

        // ③ 拍点闪光（dt 化的指数衰减；`beat` 只持续一帧 ⇒ 每次鼓点只闪一下）
        if (frame.beat) {
            flare = 1f
            spawnMeteors(frame.bassRaw)
        } else {
            flare *= flareDecayFor(fx.dt)
        }

        // ④ 底环（原生尺寸缓存位图 ⇒ 每帧 **1 次** blit 取代 64 次 drawCircle）
        //    ⭐ v1.5：RING_ALPHA 降到 0.10 —— 轨道线只留「隐约的圆」，不与亮弧争视线
        rings?.let {
            drawImage(
                image = it,
                dstSize = IntSize(iw, ih),
                alpha = RING_ALPHA,
                blendMode = BlendMode.Plus,
                // ⚠️ 1:1 直贴 ⛔ 不做双线性重采样（既省一次采样，也保证细线不被糊粗）
                filterQuality = FilterQuality.None,
            )
        }

        // ⑤ ⭐ Spray 弧场（一次旋转变换 + SPRAY_BUCKETS 次 drawPath；亮度随整体能量呼吸）
        drawSpray(poleAngleDeg(fx.nowMs, t0Ms), sprayAlphaFor(fieldEnergy()))

        // ⑥ 亮线段 hero（逐柱逐段；原生分辨率 + 抗锯齿 + BlendMode.Plus 加性星芒）
        drawSegments(frame, fx)

        // ⑦ 切向流星（圆弧）
        drawMeteors(drawContext.canvas.nativeCanvas, fx.dt)

        // ⑧ 天极辉光（预烘焙精灵；能量 + 拍点闪光 + 静音呼吸）
        val glow = (GLOW_BASE + GLOW_ENERGY * frame.sectionEnergy + GLOW_PULSE_K * frame.pulse + flare)
            .coerceIn(0f, 1f)
        poleGlowBrush?.let {
            drawCircle(it, radius = poleGlowR, center = Offset(poleX, poleY), alpha = glow, blendMode = BlendMode.Plus)
        }

        // ⑨ ⛔ v1.7：地平线辉光带已删除（`BlendMode.Plus` + `alpha 0.42` + 近淡青紫 + 横跨地平线
        //    ⇒ 真机读作「海面反光」）。天极辉光是画面里**唯一**允许的亮部。

        // ⑩ 地景剪影（山脊 + 枯树）—— 画在星轨之上 ⇒ 星轨在地平线处自然截止
        drawGroundForeground()
        // ⑪ 后处理（vignette + grain）由基类 applyPostFx 统一施加，本类不重复实现
    }

    /**
     * ⭐ v1.5 Spray 弧场：把整片「数百条短弧」在**一个**旋转变换下画完。
     *
     * ⛔ **这里绝不能出现 `drawArc` / `addArc`** —— 384 次逐弧绘制会把真机 29.7fps 砍半。
     *    弧已在 [rebuildSprayPaths] 里烘焙进 [sprayPaths]（每色一条），本函数只做
     *    「绕天极旋转 [rotDeg] 度 + 每桶一次 [drawPath]」。
     *
     * ⚠️ 变换绕**天极**（[poleX],[poleY]）而非画布中心：喷溅弧不在同心圆上（半径带抖动），
     * 绕中心转会把整片场平移出去、读成「场在飘」；绕天极转才是「星轨在自转」。
     */
    private fun DrawScope.drawSpray(rotDeg: Float, alpha: Float) {
        val stroke = sprayStroke ?: return
        withTransform({ rotate(rotDeg, pivot = Offset(poleX, poleY)) }) {
            var b = 0
            while (b < SPRAY_BUCKETS) {
                drawPath(
                    path = sprayPaths[b],
                    color = sprayColors[b],
                    alpha = alpha,
                    style = stroke,
                    blendMode = BlendMode.Plus,
                )
                b++
            }
        }
    }

    /**
     * 各柱幅值包络的均值（0..1）—— 驱动 spray 场亮度。
     *
     * ⛔ 必须在 [drawSegments] **之后**调用：`env[]` 正是由它逐帧推进的，
     *    早读会拿到上一帧的值（1 帧延迟，在这个平滑量上不可见，但语义上要写对）。
     */
    private fun fieldEnergy(): Float {
        var sum = 0f
        var i = 0
        while (i < RING_BANDS) {
            sum += env[i]
            i++
        }
        return sum / RING_BANDS
    }

    /**
     * 逐柱画「亮线段」—— 用户口述的第二笔：**线上有一段一段的描出来亮线，前面亮、后面逐渐
     * 与原来的线一样了**。
     *
     * - **头**在旋转天极角 `az[slot] + poleAngleDeg(...)` 上，尾巴沿反方向退开 [sweepForAmp] 度；
     * - 拆成 [SEG_K] 段**首尾相接**的子弧（`cap = Butt` —— 圆头会在接缝处鼓起一小段，
     *   平头才严丝合缝，这正是「一段一段描出来」仍读作一条连续亮线的前提），
     *   alpha 自尾向头按 [segAlphaAt] 单调爬升；
     * - ⛔ **直接画在 [DrawScope] 上**（原生分辨率 + 抗锯齿）：⛔ 不经任何降采样缓冲。
     *
     * 同一条环的子弧彼此**相邻不重叠**、不同环半径**互不相同** ⇒ 逐子弧改 alpha
     * 不可能像 v1.3 的逐帧叠加那样在同一点饱和。
     */
    private fun DrawScope.drawSegments(frame: AudioFrame, fx: FxFrame) {
        // ⛔ 不改本函数的签名：门禁正按 (frame, fx) 切它的体来断言「恒读 spectrum.size」
        val spec = frame.spectrum
        // ⛔ 恒读 frame.spectrum.size（LOW 档 32 柱同样成立），再夹到数组容量内
        val avail = if (spec.size < RING_BANDS) spec.size else RING_BANDS
        if (avail <= 0) return
        // LOW 档隔柱取样：老设备少画一半亮线，视觉几乎无损
        val stride = if (fx.level == FxLevel.OFF) LOW_ARC_STRIDE else 1
        val rotDeg = poleAngleDeg(fx.nowMs, t0Ms)
        val strokeDensity = density
        var slot = 0
        while (slot < avail) {
            val cur = envelopeStep(env[slot], spec[slot], fx.dt)
            env[slot] = cur
            val sweep = sweepForAmp(cur)
            if (sweep > 0f) {
                // ⛔ 半径分母恒为 RING_BANDS（与烘焙底环时同式）⇒ 亮线段精确落在自己的环上
                val r = radiusForRange(slot, RING_BANDS, rInner, rOuter)
                val head = az[slot] * DEG_PER_RAD + rotDeg
                val sub = sweep / SEG_K
                val tint = barRatio(slot, RING_BANDS)
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

    /**
     * 推进并绘制切向流星。
     *
     * ⛔ 尾巴必须画成**圆弧**（[android.graphics.Path.addArc]），⛔ 不得退回 `moveTo`/`lineTo`
     * 的直线弦：流星沿圆周逐帧移动，直线弦会绕极一圈排成一根根**直线肋骨**（原型抓到的「鱼刺」）。
     */
    private fun drawMeteors(cb: android.graphics.Canvas, dt: Float) {
        var i = 0
        while (i < mCount) {
            mAge[i] += dt
            if (mAge[i] >= mLife[i]) {
                removeMeteorAt(i)
                continue
            }
            mAng[i] += mDir[i] * mSpd[i] * dt
            mR[i] += mDir[i] * mSpd[i] * mR[i] * METEOR_DRIFT * dt
            val fade = 1f - mAge[i] / mLife[i]
            // 尾迹在头角的反侧；扫掠角带符号 ⇒ 弧的绘制方向由 dir 决定
            val sweepRad = mLen[i] * mDir[i]
            val startRad = mAng[i] - sweepRad
            meteorRect.set(poleX - mR[i], poleY - mR[i], poleX + mR[i], poleY + mR[i])
            meteorPath.reset()
            // ⚠️ 用 3 参 addArc(RectF, start, sweep)：`Path` **没有** 4 参
            // `addArc(..., forceMoveTo)` 那个重载（4 参的是 `arcTo`）。路径刚 reset 过，
            // `addArc` 自成一段新轮廓，与 `arcTo(..., false)` 等价。
            meteorPath.addArc(meteorRect, startRad * DEG_PER_RAD, sweepRad * DEG_PER_RAD)
            meteorPaint.color = if (mWarm[i] > 0.5f) {
                meteorColorArgb(TRAIL_CORE_ARGB, fade)
            } else {
                meteorColorArgb(METEOR_COLD_ARGB, fade)
            }
            meteorPaint.strokeWidth = METEOR_W_MIN + METEOR_W_GAIN * fade
            cb.drawPath(meteorPath, meteorPaint)
            i++
        }
    }

    /** 鼓点帧生成 2–4 颗流星（强度由 [AudioFrame.bassRaw] 给，⛔ 不用恒为 1 的 `bass`）。 */
    private fun spawnMeteors(strength: Float) {
        val n = METEOR_MIN + (METEOR_SPREAD * strength.coerceIn(0f, 1f)).toInt()
        var k = 0
        while (k < n && mCount < METEOR_MAX) {
            val i = mCount
            val r = rInner + rng.next() * (rOuter - rInner)
            mR[i] = r
            mAng[i] = rng.next() * TAU
            mDir[i] = if (rng.next() < 0.5f) -1f else 1f
            // 近天极者角速度更快（保持线速度相近 ⇒ 视觉上「贴着星轨滑行」）
            mSpd[i] = (METEOR_SPD_MIN + rng.next() * (METEOR_SPD_MAX - METEOR_SPD_MIN)) * (rOuter / r)
            mLen[i] = METEOR_LEN_MIN + rng.next() * (METEOR_LEN_MAX - METEOR_LEN_MIN)
            mAge[i] = 0f
            mLife[i] = METEOR_LIFE_MIN + rng.next() * (METEOR_LIFE_MAX - METEOR_LIFE_MIN)
            mWarm[i] = if (rng.next() < METEOR_WARM_P) 1f else 0f
            mCount = i + 1
            k++
        }
    }

    /** O(1) 移除：与末位交换后缩短（⛔ 不用 `removeAt` / `List`）。 */
    private fun removeMeteorAt(i: Int) {
        val last = mCount - 1
        if (i != last) {
            mR[i] = mR[last]; mAng[i] = mAng[last]; mDir[i] = mDir[last]; mSpd[i] = mSpd[last]
            mLen[i] = mLen[last]; mAge[i] = mAge[last]; mLife[i] = mLife[last]; mWarm[i] = mWarm[last]
        }
        mCount = last
    }

    /** 地景：不透明山脊 + 枯树（⛔ 无人物剪影）。画在主画布、星轨之上。 */
    private fun DrawScope.drawGroundForeground() {
        val nc = drawContext.canvas.nativeCanvas
        groundPaint.color = GROUND_ARGB
        nc.drawPath(ridgePath, groundPaint)
        nc.drawPath(trunkPath, groundPaint)
        if (limbSegCount <= 0) return
        branchPaint.color = GROUND_ARGB
        var i = 0
        while (i < limbSegCount) {
            branchPaint.strokeWidth = limbWidths[i]
            nc.drawPath(limbPaths[i], branchPaint)
            i++
        }
    }

    // ═══════════════════════════ 地景 / 枯树构建 ═══════════════════════════

    /**
     * 构建山脊 + 树干 + 全部枯枝段（一次性，尺寸变化时重建 ⇒ resize 后**完全可复现**）。
     *
     * ⛔ 抖动只用确定性哈希 [hash01]（⛔ 不得用 `Math.random()` 之类的非确定源：否则每次 resize
     * 树都不一样，且无法写单测）。
     */
    private fun buildGroundPaths(w: Float, h: Float) {
        ridgePath.reset()
        ridgePath.moveTo(0f, horizonY + RIDGE[0].y0 * h)
        var i = 0
        while (i < RIDGE.size) {
            val g = RIDGE[i]
            val dx = g.x1 - g.x0
            ridgePath.cubicTo(
                (g.x0 + dx / 3f) * w, horizonY + g.c1 * h,
                (g.x0 + dx * 2f / 3f) * w, horizonY + g.c2 * h,
                g.x1 * w, horizonY + g.y1 * h,
            )
            i++
        }
        ridgePath.lineTo(w, h + GROUND_OVERHANG)
        ridgePath.lineTo(0f, h + GROUND_OVERHANG)
        ridgePath.close()

        // —— 枯树（无叶、虬枝）：树心取「外圈星环最左端」与左边框的中点，并带枝展下界 ——
        val treeX = treeXFor(poleX, rOuter, w, h)
        val treeH = h * TREE_H_K
        val treeBase = ridgeYAt(treeX, w, horizonY, h) + TREE_BASE_SINK
        val splitY = treeBase - treeH * TREE_SPLIT_K
        val midY = treeBase + (splitY - treeBase) * 0.5f
        val leanTop = -treeH * TREE_LEAN_TOP_K
        val leanMid = leanTop * TREE_LEAN_MID_K
        val hwBase = h * TREE_HW_BASE_K
        val hwMid = h * TREE_HW_MID_K
        val hwTop = h * TREE_HW_TOP_K

        trunkPath.reset()
        trunkPath.moveTo(treeX - hwBase, treeBase)
        trunkPath.quadTo(treeX + leanMid - hwMid, midY, treeX + leanTop - hwTop, splitY)
        trunkPath.quadTo(treeX + leanTop, splitY - h * TREE_NOTCH_K, treeX + leanTop + hwTop, splitY)
        trunkPath.quadTo(treeX + leanMid + hwMid, midY, treeX + hwBase, treeBase)
        trunkPath.close()

        limbSegCount = 0
        val branchW = h * LIMB_W_K
        i = 0
        while (i < LIMBS.size) {
            val l = LIMBS[i]
            val angRad = VisualizerMath.rad(l.deg)
            val t = l.at
            // 出枝点沿树干曲线插值（0 = 叉口、1 = 树根）⇒ 低位侧枝不挤在同一个叉口
            val ox = (treeX + leanTop) * (1f - t) + treeX * t - sin(angRad) * h * LIMB_INSET_K
            val oy = splitY * (1f - t) + treeBase * t + cos(angRad) * h * LIMB_INSET_K
            addLimb(ox, oy, angRad, limbBudget(oy, treeBase, treeH) * l.len, branchW, l.depth, l.seed, LIMB_CURL)
            i++
        }
    }

    /**
     * 一节裸枝：沿 [angRad] 方向（0 = 竖直向上，正 = 顺时针）画 [len] 长，分 [LIMB_SEGS] 段描边。
     *
     * 每段：轻微确定性抖动（出虬结）+ 向竖直方向收回（[nextLimbAngle] 的 [LIMB_PULL_BACK]）
     * ⇒ 枝条一路**向上**伸展，而不是下垂的「蜘蛛腿」。
     * [depth] > 0 时在末端分叉出两条更细的子枝。
     */
    private fun addLimb(
        x0: Float, y0: Float, angRad: Float, len: Float, wd: Float,
        depth: Int, seed: Int, curl: Float,
    ) {
        val segLen = len / LIMB_SEGS
        var px = x0
        var py = y0
        var a = angRad
        var i = 0
        while (i < LIMB_SEGS) {
            if (limbSegCount >= LIMB_SEG_MAX) return
            a = nextLimbAngle(a, curl, hash01(seed * 7 + i))
            val ex = px + sin(a) * segLen
            val ey = py - cos(a) * segLen
            val bow = segLen * LIMB_BOW * (hash01(seed * 13 + i) - 0.5f) * 2f
            val p = limbPaths[limbSegCount]
            p.reset()
            p.moveTo(px, py)
            p.quadTo(
                (px + ex) * 0.5f + cos(a) * bow,
                (py + ey) * 0.5f + sin(a) * bow,
                ex, ey,
            )
            limbWidths[limbSegCount] =
                kotlin.math.max(LIMB_MIN_W, wd * (1f - (i.toFloat() / LIMB_SEGS) * LIMB_TAPER))
            limbSegCount++
            px = ex
            py = ey
            i++
        }
        if (depth > 0) {
            val spread = LIMB_FORK_SPREAD_BASE + hash01(seed * 5) * LIMB_FORK_SPREAD_SPAN
            val cl = len * LIMB_FORK_LEN_K
            val child = depth - 1
            addLimb(px, py, a - spread, cl, wd * LIMB_FORK_W_A, child, seed * 3 + 1, curl * LIMB_FORK_CURL)
            addLimb(px, py, a + spread, cl * LIMB_FORK_LEN_T, wd * LIMB_FORK_W_B, child, seed * 3 + 2, curl * LIMB_FORK_CURL)
        }
    }

    // ═══════════════════════════ 纯函数（供门禁直调，⛔ 不在 JVM 里构造本渲染器）═══════════════════

    /** 山脊的一段三次贝塞尔；y 均为相对 `horizonY` 的 **h 倍数**，负 = 更高。 */
    internal class RidgeSeg(
        val x0: Float, val x1: Float,
        val y0: Float, val c1: Float, val c2: Float, val y1: Float,
    )

    /** 5 条主枝：角度(度) / 长度系数 / 分叉层数 / 抖动种子 / 出枝高度（0 = 叉口、1 = 树根）。 */
    internal class Limb(
        val deg: Float, val len: Float, val depth: Int, val seed: Int, val at: Float,
    )

    internal companion object {

        // —— 核心物理 / 频率映射 ——
        /** 天极自转角速度（度/秒）。⛔ 时间基准推导，不是每帧固定增量。 */
        const val ROT_DEG_PER_S = 3.2f
        /** 鼓点极点闪光时间常数（秒）。 */
        const val FLARE_DECAY_S = 0.18f
        /** 满幅扫掠角（度）。 */
        const val MAX_SWEEP_DEG = 46f
        /** 弧可见门限（低于此不画该柱）。 */
        const val SILENT_FLOOR = 0.012f
        /**
         * 最内柱半径系数。
         *
         * ⛔ v1.4：`0.055 → 0.10`。v1.3 的 0.055 让最内圈几乎压在天极上，
         * 与「几十帧同角度 Plus 叠加」一起把中心烧成死白；抬高后中心留下一圈可见的暗核。
         */
        const val R_INNER_K = 0.10f
        const val R_OUTER_K = 0.78f
        /** 半径聚密指数 `t^0.72`（向天极聚拢）。 */
        const val RADIUS_SHAPE = 0.72f
        /**
         * 弧色 `lerp` 指数 `t^0.55` —— ⛔ **与 [RADIUS_SHAPE] 不同**：色相要更快转冷，
         * 否则外圈仍是暖白、读不出「核心白炽 / 外圈淡蓝」的层次。
         */
        const val COLOR_SHAPE = 0.55f
        /** 幅值包络起音时间常数（快攻）。 */
        const val ATTACK_S = 0.025f
        /** 幅值包络释音时间常数（慢放 ⇒ 平滑尾迹）。 */
        const val RELEASE_S = 0.24f

        /**
         * 底环圈数 = 柱容量（⛔ **不是**每帧循环上界：每帧柱数恒读 `frame.spectrum.size`）。
         *
         * 底环与亮线段共用它作 [radiusForRange] 的分母 ⇒ 两笔几何**必然同环**。
         */
        const val RING_BANDS = SpectrumContract.BAR_COUNT

        // —— 底环（圆）——
        /** ⭐ 底环线宽（dp）。⛔ **恒定**：用户要求「用细线画圆」，⛔ 不再随幅值调制。 */
        const val RING_W = 1.0f
        /**
         * ⭐ 底环 alpha。⛔ **恒定且与幅值无关**（每帧只作为 blit 的 alpha）。
         *
         * **v1.5：`0.26 → 0.10`** —— 真机确认亮线本身没问题，但「分布太均匀、亮弧不够多」。
         * 用户判词是「轨道线条可以不明显」⇒ 圆退到几乎看不见，只留一丝暗示极坐标结构。
         */
        const val RING_ALPHA = 0.10f

        // —— ⭐ Spray（喷溅弧场，v1.5）——
        /** 每带几条 spray 弧：HIGH / `FxLevel.FULL`。 */
        const val SPRAY_PER_BAND_FULL = 6
        /** 每带几条 spray 弧：MEDIUM / `FxLevel.LITE`。 */
        const val SPRAY_PER_BAND_LITE = 5
        /** 每带几条 spray 弧：LOW / `FxLevel.OFF`。 */
        const val SPRAY_PER_BAND_OFF = 3
        /**
         * ⭐ 半径抖动占「到**最近邻带**的间距」的比例 —— **打散同心圆规整感的唯一来源**。
         *
         * ⛔ **必须 < 0.5f**：抖动幅度 `J·gap` 小于半间距，才能保证「本带最外侧的弧」
         * 仍然落在「下一带最内侧的弧」之内 ⇒ 带与带的半径顺序**恒不交叉**（门禁逐带断言）。
         */
        const val SPRAY_RADIUS_JITTER = 0.45f
        /**
         * ⭐ 活跃门限：哈希值 `< 该值` 的弧**不画**。
         *
         * 被剔除的那些弧**就是「空档」的来源** —— 全画出来就又变成规整的 64 圈了。
         * `0.28` ⇒ 实测活跃率 ≈ 0.78（门禁断言 0.60–0.85）。
         */
        const val SPRAY_CUTOFF = 0.28f
        /** spray 弧扫掠角下界（度）：最短也要看得见一小段。 */
        const val SPRAY_SWEEP_MIN_DEG = 4f
        /** spray 弧扫掠角上界（度）：长度参差 ⇒ 「有的长、有的短」。 */
        const val SPRAY_SWEEP_MAX_DEG = 26f
        /** 颜色桶数 = 每帧 [drawPath] 次数（⛔ 越大越贵，4 足够读出「冷蓝/淡白/暖白/品红」）。 */
        const val SPRAY_BUCKETS = 4
        /** spray 线宽（dp），单一描边。 */
        const val SPRAY_W = 1.0f
        /** spray 场最低亮度（静默段也要留一点底，否则整场凭空消失）。 */
        const val SPRAY_ALPHA_MIN = 0.18f
        /** spray 场最高亮度（满能量）。 */
        const val SPRAY_ALPHA_MAX = 0.55f

        /**
         * ⭐ Spray 调色板（对应 [SPRAY_BUCKETS]），取自参考图：
         * 多为冷蓝 / 淡青白，夹几笔暖白与少量柔品红。
         *
         * ⛔ 写成 `Color(0xFFxxxxxx)` 字面量而 ⛔ **不用** `Color.toArgb()`：
         * 后者内部调未 mock 的 `android.graphics.Color.argb`，companion 的 `<clinit>`
         * 会被任何 JVM 单测触发 ⇒ 整个测试类炸掉（同 §12.4 偏差 ⑪）。
         */
        val SPRAY_COLORS = arrayOf(
            Color(0xFF8FA4DF),  // 冷蓝（主色）
            Color(0xFFDCE4FF),  // 淡白青
            Color(0xFFFFF7EA),  // 暖白
            Color(0xFFC89BE0),  // 柔品红（点缀）
        )

        // —— 亮线段 hero ——
        /**
         * ⭐ 每条亮线段切成几段子弧（「一段一段」）。
         *
         * **v1.5：`4 → 3`** —— 把省下的 `RING_BANDS × 1` 次 `drawArc`（64 次），
         * 换成 spray 弧场的 `SPRAY_BUCKETS` 次 `drawPath`。斜坡（[SEG_GAMMA]）与
         * 线宽（[SEG_W_MIN]/[SEG_W_GAIN]）完全不动 —— 真机已认可的亮线观感不受影响。
         */
        const val SEG_K = 3
        /** ⭐ 子弧 alpha 爬升指数（>1 ⇒ 越靠头越陡）。 */
        const val SEG_GAMMA = 1.6f
        /** ⭐ 亮线段线宽下界（dp）。 */
        const val SEG_W_MIN = 1.0f
        /** ⭐ 亮线段线宽增益（dp / 满幅）⇒ 全幅也只有 `1.8f` dp。 */
        const val SEG_W_GAIN = 0.8f

        // —— 天极 / 画幅自适应 ——
        const val POLE_X_K = 0.70f
        const val POLE_Y_K = 0.52f
        /** 宽高比 → 居中度的过渡跨度。 */
        const val LS_SPAN = 0.8f
        /** 低于此宽高比启用外半径钳制。 */
        const val NARROW_ASPECT = 1.4f
        /** 钳制时外半径占「天极到最远边」的比例。 */
        const val NARROW_FILL_K = 0.96f

        // —— 地景 ——
        /** 地平线 = 0.75h（地面占底部 1/4）。 */
        const val HORIZON_K = 0.75f
        const val TREE_H_K = 0.40f
        const val TREE_SPLIT_K = 0.40f
        const val TREE_LEAN_TOP_K = 0.050f
        const val TREE_LEAN_MID_K = 0.38f
        const val TREE_HW_BASE_K = 0.032f
        const val TREE_HW_MID_K = 0.026f
        const val TREE_HW_TOP_K = 0.011f
        /** 叉口收窄量（h 的倍数）。 */
        const val TREE_NOTCH_K = 0.003f
        /** 实测最左枝尖伸出树心的距离（h 的倍数）—— 树位下界要用。 */
        const val TREE_BRANCH_REACH_K = 0.196f
        /** 枝尖与左边框之间至少保留的空隙（w 的倍数）。 */
        const val TREE_EDGE_GAP_K = 0.025f
        /** 树根略埋入脊线的深度（px），防接缝。 */
        const val TREE_BASE_SINK = 2f
        /** 枝条起始粗细（h 的倍数）。 */
        const val LIMB_W_K = 0.012f
        /** 出枝点沿枝向的内埋量（h 的倍数），保证与树干无缝。 */
        const val LIMB_INSET_K = 0.010f
        /** ⭐ 枯枝**向上伸展**的唯一保证：每段把角度往竖直（0）方向收回。⛔ 必须 > 0。 */
        const val LIMB_PULL_BACK = 0.90f
        const val LIMB_JITTER = 0.7f
        const val LIMB_BOW = 0.18f
        const val LIMB_SEGS = 4
        const val LIMB_MIN_W = 0.7f
        /** 逐段线宽衰减系数（段末保留 1 − 该值 的比例）。 */
        const val LIMB_TAPER = 0.72f
        const val LIMB_CURL = 0.42f
        const val LIMB_FORK_SPREAD_BASE = 0.42f
        const val LIMB_FORK_SPREAD_SPAN = 0.20f
        const val LIMB_FORK_LEN_K = 0.58f
        const val LIMB_FORK_LEN_T = 0.86f
        const val LIMB_FORK_W_A = 0.58f
        const val LIMB_FORK_W_B = 0.52f
        const val LIMB_FORK_CURL = 1.2f
        /**
         * ⭐ 枝条可用**上升高度**下界系数：`budget = oy − (treeBase − treeH)`。
         * ⛔ 必须 > 0（符号写反会让全部枝条反向画出，表现为下垂的「蜘蛛腿」）。
         */
        const val TREE_BUDGET_MIN = 0f

        // ═════════════ v1.5 Spray 参数（全部纯函数，门禁直调，⛔ 不构造渲染器）═════════════

        /**
         * 每带几条 spray 弧，按画质档。
         *
         * ⛔ 这是 `ensureSprayLayer` 的缓存键之一 —— 切画质必须重烘焙，
         *    否则 LOW 切回 HIGH 会少画一半弧（视觉直接缺一块）。
         */
        fun sprayPerBandFor(level: FxLevel): Int = when (level) {
            FxLevel.FULL -> SPRAY_PER_BAND_FULL
            FxLevel.LITE -> SPRAY_PER_BAND_LITE
            FxLevel.OFF -> SPRAY_PER_BAND_OFF
        }

        /**
         * `(bandIndex, k)` 的第 `n` 条独立伪随机流（`n ∈ 0..4`），返回 `[0,1)`。
         *
         * ⛔ **必须是确定性哈希**（[hash01] 同款 `sin(i)·43758.5453` 技巧），
         *    ⛔ 不得用 `Math.random()` / `kotlin.random` —— 那样每次 resize 场都不同、
         *    且**无法写单测**（同 [hash01] 的理由）。
         *
         * ⚠️ 索引排布 `band·40 + k·5 + n` 是**单射**的（`k·5+n ≤ 29 < 40`），
         *    保证三条不同的 `(band,k,n)` 不会算到同一个哈希输入。
         *
         * ⚠️ [hash01] 在 Float 下只能给出约 3e3 个可分辨值（`v = sin(…)·43758.5453f`
         *    的尾数精度所限），故**不同三元组可能取到同一个值**。这是可接受的：
         *    四条流（活跃位/半径/相位/扫掠/桶）**各自独立**，多条同时撞车的概率极低，
         *    而「场看起来散不散」由半径 + 相位 + 长度的组合决定，不要求单个值唯一。
         */
        fun sprayHash(bandIndex: Int, k: Int, n: Int): Float =
            hash01(bandIndex * 40 + k * 5 + n)

        /** 该 `(band, k)` 是否画出（实测活跃率 ≈ 0.78）。被剔除的就是「空档」的来源。 */
        fun sprayActiveFor(bandIndex: Int, k: Int): Boolean =
            sprayHash(bandIndex, k, 0) >= SPRAY_CUTOFF

        /**
         * 本带到**最近邻带**的半径间距 —— spray 抖动的作用域。
         *
         * 取两侧间距的**较小者** ⛔ 而不是只取内侧那一个：半径按 `t^[RADIUS_SHAPE]` 递增，
         * 间距**向外递减**，所以内侧间距更大。取较小者才能保证「本带抖动后的最外侧弧」
         * 仍在「下一带抖动后的最内侧弧」之内 ⇒ **带间顺序恒不交叉**（门禁逐带断言）。
         *
         * ⛔ 单带（`barCount ≤ 1`）返回 `0f` ⇒ 抖动幅度为 0，弧正好落在锚点上。
         */
        fun localGapFor(bandIndex: Int, barCount: Int, rInner: Float, rOuter: Float): Float {
            if (barCount <= 1) return 0f
            val cur = radiusForRange(bandIndex, barCount, rInner, rOuter)
            val gapIn = if (bandIndex > 0) {
                cur - radiusForRange(bandIndex - 1, barCount, rInner, rOuter)
            } else {
                Float.MAX_VALUE
            }
            val gapOut = if (bandIndex < barCount - 1) {
                radiusForRange(bandIndex + 1, barCount, rInner, rOuter) - cur
            } else {
                Float.MAX_VALUE
            }
            val m = if (gapIn < gapOut) gapIn else gapOut
            return if (m <= 0f || m == Float.MAX_VALUE) 0f else m
        }

        /**
         * spray 弧半径 = 本带锚点 ± `SPRAY_RADIUS_JITTER × [localGapFor]`。
         *
         * ⛔ **硬夹紧**到 `[锚点 − J·gap, 锚点 + J·gap]`：夹紧是「带间不交叉」这条性质的
         *    **最后一道保证** —— 即使有人把 [SPRAY_RADIUS_JITTER] 调到 ≥ 0.5，也不会
         *    让某一带的弧跑到邻带 territory 上（只会退化成「不抖动的整齐圆」）。
         */
        fun sprayRadiusFor(bandIndex: Int, k: Int, bandRadius: Float, localGap: Float): Float {
            val span = localGap.coerceAtLeast(0f) * SPRAY_RADIUS_JITTER
            val d = (sprayHash(bandIndex, k, 1) - 0.5f) * 2f * span
            return (bandRadius + d).coerceIn(bandRadius - span, bandRadius + span)
        }

        /** spray 相位（度）：逐弧独立 ⇒ 整场散开，而不是 64 条对齐的径向线。 */
        fun sprayPhaseFor(bandIndex: Int, k: Int): Float = sprayHash(bandIndex, k, 2) * 360f

        /** spray 扫掠角（度）：[SPRAY_SWEEP_MIN_DEG]…[SPRAY_SWEEP_MAX_DEG] 随机 ⇒ 长度参差。 */
        fun spraySweepFor(bandIndex: Int, k: Int): Float =
            SPRAY_SWEEP_MIN_DEG + sprayHash(bandIndex, k, 3) * (SPRAY_SWEEP_MAX_DEG - SPRAY_SWEEP_MIN_DEG)

        /** spray 颜色桶（`0 until SPRAY_BUCKETS`）—— 决定进哪条烘焙 `Path`。 */
        fun sprayBucketFor(bandIndex: Int, k: Int): Int =
            (sprayHash(bandIndex, k, 4) * SPRAY_BUCKETS).toInt().coerceIn(0, SPRAY_BUCKETS - 1)

        /**
         * spray 场亮度：随整体包络能量从 [SPRAY_ALPHA_MIN] 呼吸到 [SPRAY_ALPHA_MAX]。
         *
         * ⚠️ **下界不为 0**：静默段也要留一层底噪，否则音乐一停整片场凭空消失、
         *    读成「效果坏了」而不是「安静下来了」。
         */
        fun sprayAlphaFor(energy: Float): Float =
            SPRAY_ALPHA_MIN + (SPRAY_ALPHA_MAX - SPRAY_ALPHA_MIN) * energy.coerceIn(0f, 1f)

        /** 5 条主枝（3 左 / 2 右，刻意不对称；低位枝从树干不同高度生出）。 */
        val LIMBS = arrayOf(
            Limb(20f, 0.95f, 0, 11, 0.00f),   // 偏左的顶梢（最高）
            Limb(36f, 0.80f, 1, 23, 0.00f),   // 主左枝，二次分叉
            Limb(-44f, 0.66f, 1, 37, 0.05f),  // 主右枝，二次分叉
            Limb(54f, 0.34f, 0, 51, 0.48f),   // 低伏的左扫枝（半腰出）
            Limb(-32f, 0.30f, 0, 67, 0.66f),  // 低伏的右扫枝（近根出）
        )
        /**
         * 山脊控制点：**控制点 x 固定取各段的 1/3 与 2/3** ⇒ `x(t)` 关于 `t` 线性
         * ⇒ [ridgeYAt] 直接 `t = (u−x0)/(x1−x0)` 反解得精确命中，⛔ 无需求二次方程根，
         * 且「画路径」与「取树根基点」用的是同一组控制点 ⇒ 树根必然落在脊线上。
         */
        val RIDGE = arrayOf(
            RidgeSeg(0.00f, 0.30f, 0.014f, 0.006f, -0.014f, -0.020f),   // 左坡 → 树下的脊顶
            RidgeSeg(0.30f, 0.60f, -0.020f, -0.014f, 0.024f, 0.026f),   // 中部下凹
            RidgeSeg(0.60f, 1.00f, 0.026f, 0.018f, -0.002f, -0.006f),    // 右侧再度抬升
        )

        // —— 视觉修饰 ——
        const val STARFIELD_ALPHA = 0.55f
        const val GLOW_BASE = 0.30f
        const val GLOW_ENERGY = 0.25f
        /** `pulse` 驱动的辉光呼吸（静音时的兜底，防止「死黑」）。 */
        const val GLOW_PULSE_K = 0.10f
        const val SKY_TINT_GAIN = 1.15f
        /**
         * ⭐ 天极辉光精灵半径 = 外半径 × 本值。
         *
         * **v1.6：`0.55 → 0.40`** —— 当时的理由是「大半径的加性辉光会淹掉虚化平台」。
         * **v1.7 平台已整体删除**，但 **0.40 保留**：天极辉光是本效果**唯一**允许的亮部
         * （⛔ 主人明确要求保留，它是识别特征），背后换成暗天空后 0.40 已经很克制；
         * ⛔ 不要再往回调 —— 回调会让画面中心重新出现一团与"海面"无关的孤立亮斑。
         */
        const val POLE_GLOW_R_K = 0.40f
        // ⛔ v1.7 删除：`BAND_UP_K` / `BAND_DOWN_K` / `BAND_PEAK_A`（地平线辉光带，随该层一起删）。
        // ⛔ v1.6 删除：`BRIGHT_LOW_A` / `BRIGHT_HIGH_A`。
        //    旧亮版靠「每档自带 alpha（0 → 0.55 → 0.85）+ 顶层透明」做出地平线更亮；
        //    新亮版改为**与暗版同 9 个位置、逐档提亮**，整层亮度统一由
        //    `drawRect(brightBrush, alpha = skyMix(sectionEnergy))` 给 ⇒ 不再需要逐档 alpha。
        /** 山脊多画到画面下方的量（px，防底边发丝缝）。 */
        const val GROUND_OVERHANG = 2f

        // —— 流星 ——
        const val METEOR_MAX = 40
        const val METEOR_MIN = 2
        const val METEOR_SPREAD = 2
        const val METEOR_SPD_MIN = 1.6f
        const val METEOR_SPD_MAX = 4.2f
        /** 弧跨度（rad）：7°–19.5°。大半径上短弧视觉接近直线是正常的，但**几何上必须是弧**。 */
        const val METEOR_LEN_MIN = 0.12f
        const val METEOR_LEN_MAX = 0.34f
        const val METEOR_LIFE_MIN = 0.28f
        const val METEOR_LIFE_MAX = 0.58f
        const val METEOR_WARM_P = 0.35f
        /** 半径的轻微外漂系数。 */
        const val METEOR_DRIFT = 0.10f
        const val METEOR_W_MIN = 1.0f
        const val METEOR_W_GAIN = 1.6f
        const val METEOR_FADE_K = 0.85f

        const val MAX_TEX_PX = 4096
        /** LOW 档（[FxLevel.OFF]）的亮线段隔柱步长。 */
        const val LOW_ARC_STRIDE = 2
        /** 5 条主枝的描边段总数上限（含二次分叉）：4 + 12 + 12 + 4 + 4。 */
        const val LIMB_SEG_MAX = 48

        const val TAU = 6.2831855f
        const val DEG_PER_RAD = 57.29578f
        /** 度 → 弧度的倒数（spray 弧把「相位(度)」换算成 `moveTo` 的弧点坐标时用）。 */
        const val DEG_PER_RAD_INV = 0.01745329f

        // —— 天幕：⭐ **一整片**低色差的垂直渐变，3 个色标 ——
        //
        // 位置是**画高比例** ⇒ 任何画幅下层次关系自动成立（⛔ 无需按宽高比调参）。
        //
        // ⭐⭐ **设计契约（三条，全部由 `StarrySkyTest` 逐条断言）**：
        //   ① **单调**：自上而下亮度**严格递增**，⛔ 无局部凹陷、无平台、无回落
        //      （`skyStopsDim` 的相邻两档亮度必须严格 `>`）。
        //   ② **总色差小**：全片顶→底亮度比 ≤ [SKY_MAX_LIGHT_RATIO]（实测 **3.10×**）
        //      —— 这就是所有者说的「渐变的色差不用太大」。**「虚化」在整片渐变里
        //      表达为"整体色差小"，⛔ 不是局部色带。**
        //   ③ **山脊以上必须是暗的**：地景剪影遮住 [HORIZON_K] 以下，但 `0.75 → 1.00`
        //      **仍有一部分可见**，它的 WCAG 线性相对亮度必须 < [SKY_MAX_VISIBLE_LIN]
        //      （实测 **0.0347**，门限 0.15 有 4.3× 余量）⇒ 读作**天空**而不是**水/海面**。
        //
        // ⛔⛔ **为什么底部不能亮**（真机打回两次的硬教训，写死在这里防止回流）：
        //   所有者原话「天空下面是不是一片海？干脆不要了」。此前 9 档 / 11 档两版表都把
        //   最亮点放在 `#7E88B6…#98A1CE`（gamma 亮度 0.49→0.64、线性 0.21→0.37），
        //   而且集中在画面**下缘** 0.68–0.82 ⇒ 读作「地平线以下一片海面」，
        //   ⛔ 而非夜空。现表底部压到 `#26325C`（gamma 0.198、线性 0.0347），
        //   比旧底部**暗 3.2×**（线性 **11×**）⇒ 画面里再没有"亮底"。
        //   ⛔ 唯一允许的亮部是**天极辉光**（[POLE] + `GLOW_BASE`/`GLOW_ENERGY`）——
        //   它是本效果的识别特征，且因为背后是暗天空才终于干净。
        //
        // 3 档的位置（天顶 / 半高 / 画底）：
        //   0.00 天顶 `#0A1026` · 0.50 仅略亮一档 `#141E44` · 1.00 画底 `#26325C`（**永不明亮**）
        //
        // ⛔ **无 shader 红线**（本项目四处明文禁止，`RenderEffect` 是 API 31+ / minSdk 22）：
        //   「虚化」只能靠**色标间距**表达，⛔ 不得出现 `RenderEffect` / `BlurMaskFilter` /
        //   `RuntimeShader` / `ShaderBrush`。
        val SKY_STOP_POS = floatArrayOf(SKY_TOP_AT, SKY_MID_AT, SKY_BOT_AT)

        // 暗版 3 档（⛔ 每个色标一个**具名常量**，门禁逐档核对；⛔ 不得在 builder 里写字面量）
        val SKY_TOP = Color(0xFF0A1026) // 0.00 天顶：最深
        val SKY_MID = Color(0xFF141E44) // 0.50 半高：只比天顶亮一档（小色差即"虚化"）
        val SKY_BOT = Color(0xFF26325C) // 1.00 画底：**最亮也只到这**（山脊以上可见的那一段）

        // 亮版 3 档：**同样 3 个位置**，每档向淡青紫 `#C9D4F2` 提亮。
        // 逐档**相对**提亮自上而下递减（+18.4% → +13.1% → +9.8%）⇒ 响度大时
        // 「上面先亮起来」，与旧版「地平线先亮」相反 —— 后者正是「海」的读法之一。
        // ⚠️ 8bit 量化下最暗那档的相对粒度约 6%，故百分比取到能稳定复现的档位为止。
        val SKY_TOP_LIT = Color(0xFF0D1329)
        val SKY_MID_LIT = Color(0xFF182248)
        val SKY_BOT_LIT = Color(0xFF2B3760)

        const val SKY_TOP_AT = 0.00f
        const val SKY_MID_AT = 0.50f
        const val SKY_BOT_AT = 1.00f

        /**
         * 全片顶→底亮度比上限（gamma luma 口径，见 [relativeLuminance]）。
         *
         * 实测 **3.095×**（`#0A1026` 0.0640 → `#26325C` 0.1980）。
         * ⛔ 旧 11 档表在同口径下是 **12.70×**、旧 9 档是 **10.89×** ⇒ 门限卡在两者之间，
         * 任何"把底部重新调亮"的改动都会立刻判失败。
         */
        const val SKY_MAX_LIGHT_RATIO = 3.25f

        /**
         * 山脊以上（`HORIZON_K → 1.00`）可见段的 WCAG **线性**相对亮度上限。
         *
         * 实测最亮处（画底）**0.0347**（线性）/ 0.1980（gamma luma）。
         * ⛔ 这里用**线性**而非 gamma 编码：判据是"绝对够不够暗"而不是"两档差多少"，
         * 线性口径下 0.15 有 4.3× 余量；gamma 编码下旧表的 0.21–0.37 会被"看起来很亮"
         * 地放大，反而掩盖差距。（⛔ 但**做差值比较时必须用 gamma 编码**，见 [relativeLuminance]。）
         */
        const val SKY_MAX_VISIBLE_LIN = 0.15f

        val GROUND = Color(0xFF0B0B12)       // 近黑地景
        val TRAIL_CORE = Color(0xFFFFF7EA)   // 核心暖白
        val TRAIL_COOL = Color(0xFF8FA4DF)   // 外圈冷蓝
        val POLE = Color(0xFF9FB0E8)         // 天极浅紫蓝

        // ⛔ v1.7：`SKY_MID` / `SKY_GLOW` 及旧 `SKY_*_AT`（5 段平滑渐变）已删除；
        //    v1.6 的 11 档「两层 + 虚化平台」与它的 `SKY_LAYER_TOP` / `SKY_RAMP_IN` /
        //    `SKY_HAZE_A|B|C` / `SKY_RAMP_OUT` / `SKY_LOWER` / `SKY_LOWER_PEAK` / `SKY_TAIL`
        //    **整套删除**（真机判「读作海面」）。天幕现为 [SKY_STOP_POS] 的 3 档低色差渐变。
        //    ⚠️ `SKY_MID` 这个名字被**复用**为新表的半高档，与 v1.6 的已删常量无关。

        // ⛔ 下列 ARGB 常量写成 `.toInt()` 而**不**用 `Color.toArgb()`：
        //   `toArgb()` 内部调 `android.graphics.Color.argb`，在纯 JVM 单测里是未 mock 的
        //   平台方法 ⇒ 本 companion 的 <clinit> 会连带炸掉整个测试类。
        val TRAIL_CORE_ARGB: Int = 0xFFFFF7EA.toInt()
        val TRAIL_COOL_ARGB: Int = 0xFF8FA4DF.toInt()
        val POLE_ARGB: Int = 0xFF9FB0E8.toInt()
        val GROUND_ARGB: Int = 0xFF0B0B12.toInt()
        /** 冷色流星 = 暖白与天极色各半。 */
        val METEOR_COLD_ARGB: Int = mixArgb(TRAIL_CORE_ARGB, POLE_ARGB, 0.5f)

        // ═════════════════════ 纯函数 ═════════════════════

        /** ⭐ 天幕暗版 3 档（顺序即自上而下）。仅在 `rebuildGeometry` 调用（构建期分配）。 */
        fun skyStopsDim(): Array<Pair<Float, Color>> = arrayOf(
            SKY_TOP_AT to SKY_TOP,
            SKY_MID_AT to SKY_MID,
            SKY_BOT_AT to SKY_BOT,
        )

        /** ⭐ 天幕亮版 3 档：**位置与暗版逐档相同**，颜色为同位置的提亮版。 */
        fun skyStopsBright(): Array<Pair<Float, Color>> = arrayOf(
            SKY_TOP_AT to SKY_TOP_LIT,
            SKY_MID_AT to SKY_MID_LIT,
            SKY_BOT_AT to SKY_BOT_LIT,
        )

        /** 色标总数（两版共用同一份位置表 ⇒ 必须相等）。 */
        const val SKY_STOP_COUNT = 3

        /**
         * ⭐ 全片顶→底亮度比（`lums` 的末档 ÷ 首档，gamma luma 口径）。
         *
         * 这就是「渐变的色差不用太大」的**可算形式**：比值越小越"平"。
         * 实测暗版 **3.095×**、亮版 **2.869×**，均 < [SKY_MAX_LIGHT_RATIO] (3.25)。
         * ⛔ 旧 11 档表同口径 **12.70×**、旧 9 档 **10.89×** ⇒ 这条判据正是拦住
         * 「底部调亮 ⇒ 读成海面」的那道闸。
         */
        fun skyLightRatio(lums: FloatArray): Float {
            if (lums.size < 2) return 0f
            val top = lums[0]
            // ⛔ 顶档亮度为 0 会除出 Inf/NaN；渐变顶端恒 > 0，这里只是兜底。
            if (top <= 0f) return Float.MAX_VALUE
            return lums[lums.size - 1] / top
        }

        /** sRGB 感知亮度（gamma 编码通道的加权平均，纯算术 ⛔ 不碰 `android.graphics`）。 */
        fun relativeLuminance(c: Color): Float =
            0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

        /**
         * WCAG **线性**相对亮度（0..1，纯算术 ⛔ 不碰 `android.graphics`）。
         *
         * ⛔ **只用于「绝对够不够暗」这一个判据**（山脊以上可见段，见 [SKY_MAX_VISIBLE_LIN]）。
         * ⛔ **绝不用于两档相减/相除**：本表最暗的 `#0A1026` 线性值只有 **0.00575**，
         *    Float 下相减会严重丢精度（这正是 [relativeLuminance] 存在的理由）。
         */
        fun linearLuminance(c: Color): Float =
            0.2126f * linearChannel(c.red) +
                0.7152f * linearChannel(c.green) +
                0.0722f * linearChannel(c.blue)

        /** sRGB 传输函数的反函数（gamma 编码 0..1 → 线性 0..1）。 */
        private fun linearChannel(v: Float): Float =
            if (v <= 0.04045f) v / 12.92f
            else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

        /**
         * 天极中心：宽高比自适应（宽屏右偏 [POLE_X_K]/[POLE_Y_K]，窄画幅收回中心）。
         *
         * @param out 长度 ≥ 2 的输出载体（`out[0] = x`、`out[1] = y`）；⛔ 不返回新数组。
         */
        fun poleCenterFor(w: Float, h: Float, out: FloatArray) {
            val t = (((w / h) - 1f) / LS_SPAN).coerceIn(0f, 1f)
            out[0] = w * (0.5f + (POLE_X_K - 0.5f) * t)
            out[1] = h * (0.5f + (POLE_Y_K - 0.5f) * t)
        }

        /** 外半径：宽屏取 [R_OUTER_K] × 短边；窄画幅（< [NARROW_ASPECT]）钳到「天极到最远边」。 */
        fun outerRadiusFor(w: Float, h: Float, px: Float, py: Float, minDim: Float): Float {
            val base = R_OUTER_K * minDim
            if (w / h >= NARROW_ASPECT) return base
            val room = kotlin.math.max(kotlin.math.max(px, w - px), kotlin.math.max(py, h - py))
            return kotlin.math.min(base, kotlin.math.max(R_INNER_K * minDim + 1f, room * NARROW_FILL_K))
        }

        /**
         * 枯树 x：外圈星环最左端与左边框的中点（树落在「环外左侧的空天」），
         * 并以「枝展 + 边距」为**下界** —— 窄画幅下 `ringLeft` 会变负数（星环本就越过左边界），
         * ⛔ 没有这个 `maxOf` 树就会跑出画面。
         */
        fun treeXFor(px: Float, rOuter: Float, w: Float, h: Float): Float {
            val ringLeft = px - rOuter
            val floor = TREE_BRANCH_REACH_K * h + TREE_EDGE_GAP_K * w
            return kotlin.math.max(ringLeft * 0.5f, floor)
        }

        /** 恒读 `minDim` 的半径映射（宽屏路径；⛔ 签名里**没有**缩放项）。 */
        fun radiusForBar(bar: Int, barCount: Int, minDim: Float): Float =
            radiusForRange(bar, barCount, R_INNER_K * minDim, R_OUTER_K * minDim)

        /** 半径 = `rInner + (rOuter − rInner) · t^[RADIUS_SHAPE]`（`rOuter` 可被窄画幅钳制）。 */
        fun radiusForRange(bar: Int, barCount: Int, rInner: Float, rOuter: Float): Float {
            if (barCount <= 1) return rInner
            val t = (bar.toFloat() / (barCount - 1)).coerceIn(0f, 1f)
            return rInner + (rOuter - rInner) * t.pow(RADIUS_SHAPE)
        }

        /** 幅值 → 扫掠角；低于 [SILENT_FLOOR] 一律 0（那一柱不画）。 */
        fun sweepForAmp(amp: Float): Float {
            if (amp <= SILENT_FLOOR) return 0f
            val n = ((amp - SILENT_FLOOR) / (1f - SILENT_FLOOR)).coerceIn(0f, 1f)
            return MAX_SWEEP_DEG * n
        }

        /** 柱序号比（单柱时取 0）。 */
        fun barRatio(bar: Int, barCount: Int): Float =
            if (barCount <= 1) 0f else (bar.toFloat() / (barCount - 1)).coerceIn(0f, 1f)

        /**
         * 天极时刻角（度）：**由单一时钟推导**，不是逐帧 `+ω·dt` 累加
         * ⇒ 帧率无关、无漂移，且天然满足 `dt` 一致性门。
         */
        fun poleAngleDeg(nowMs: Long, t0Ms: Long): Float {
            val deg = ROT_DEG_PER_S * ((nowMs - t0Ms) / 1000f)
            // ⛔ 必须用 floor 而不是 toInt()：nowMs < t0Ms 时 deg 为负，
            //   toInt() 向零截断会让结果落在 (-360, 0]，角度反向漂移。
            return deg - floor(deg / 360f) * 360f
        }

        /** 极点闪光每帧保留比例（⛔ `tau ≤ 0` 守卫：单帧即归零，避免 NaN）。 */
        fun flareDecayFor(dt: Float): Float {
            if (FLARE_DECAY_S <= 0f) return 0f
            return exp(-dt.coerceIn(0f, MAX_DT_S) / FLARE_DECAY_S)
        }

        /**
         * 幅值包络：快攻 [ATTACK_S] / 慢放 [RELEASE_S]。
         *
         * ⛔ **不复用** `AudioSmoother` —— 它的 `attack`/`release` 语义是「越大越慢」且
         * 数值是 0.35/0.06，与本效果要的 0.025s/0.24s **方向相反且不等**；这里要的是物理
         * 时间常数，不是相对系数。
         */
        fun envelopeStep(cur: Float, target: Float, dt: Float): Float {
            val tau = if (target > cur) ATTACK_S else RELEASE_S
            return cur + (target - cur) * (1f - exp(-dt.coerceIn(0f, MAX_DT_S) / tau))
        }

        /** 段落响度 → 天色抬升量（硬钳 0..1，防夜空过曝发灰）。 */
        fun skyMix(sectionEnergy: Float): Float =
            (sectionEnergy * SKY_TINT_GAIN).coerceIn(0f, 1f)

        /** 亮线段线宽（px）：`(SEG_W_MIN + SEG_W_GAIN · amp)` dp × `DrawScope.density`。 */
        fun segWidthPx(amp: Float, density: Float): Float =
            (SEG_W_MIN + SEG_W_GAIN * amp.coerceIn(0f, 1f)) * density

        /**
         * ⭐ 第 `k` 段子弧（`k = 0` 最尾、`k = SEG_K−1` 最头）的 alpha —— **恒定爬升**。
         *
         * `alpha_k = RING_ALPHA + (1 − RING_ALPHA) · ((k+1)/SEG_K)^[SEG_GAMMA]`
         *
         * - 头段恰为 1.0（最亮）；尾段落在 [RING_ALPHA] 与 1 之间 ⇒ **已沉回底环的亮度**；
         * - ⛔ **与 `amp` 无关**（幅值只调制 [segWidthPx]）⇒ 同一柱的 4 段不会因为
         *   帧间变化而互相跳变。
         */
        fun segAlphaAt(k: Int): Float {
            val t = ((k + 1).toFloat() / SEG_K).coerceIn(0f, 1f)
            return RING_ALPHA + (1f - RING_ALPHA) * t.pow(SEG_GAMMA)
        }

        /**
         * 亮线段颜色：暖白 → 冷蓝按 `t^[COLOR_SHAPE]` 插值，alpha 由 [segAlphaAt] 给。
         *
         * ⛔ **不再**用 `ALPHA_BASE + ALPHA_BASE·amp` 那套亮度：底环已经是底色，
         *   亮线段只需在此之上**叠加**出自己的 alpha 爬升。
         */
        fun segColorArgb(t: Float, alpha: Float): Int {
            val k = t.coerceIn(0f, 1f).pow(COLOR_SHAPE)
            val rgb = mixArgb(TRAIL_CORE_ARGB, TRAIL_COOL_ARGB, k)
            return setArgbAlpha(rgb, (alpha.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255))
        }

        /**
         * 底环颜色：**不透明**（alpha = 255），逐帧的 [RING_ALPHA] 由 `drawImage(alpha=)` 施加。
         *
         * ⇒ 底环的实际 alpha **恒为 [RING_ALPHA] 且与幅值无关**，门禁可直读本函数 + 常量判住。
         */
        fun ringColorArgb(t: Float): Int = segColorArgb(t, 1f)

        /** 流星颜色（冷色基 = 暖白与天极色各半，暖色基 = 纯暖白）。 */
        fun meteorColorArgb(baseArgb: Int, fade: Float): Int =
            setArgbAlpha(baseArgb, (METEOR_FADE_K * fade.coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255))

        /** 通道插值（纯算术，⛔ 不碰 `android.graphics`）。 */
        fun mixArgb(a: Int, b: Int, t: Float): Int {
            val k = t.coerceIn(0f, 1f)
            val ar = a shr 16 and 0xFF
            val br = b shr 16 and 0xFF
            val ag = a shr 8 and 0xFF
            val bg = b shr 8 and 0xFF
            val ab = a and 0xFF
            val bb = b and 0xFF
            return pack(
                ar + (br - ar) * k,
                ag + (bg - ag) * k,
                ab + (bb - ab) * k,
            )
        }

        /**
         * 纯 Compose 侧的颜色插值（渐变构建期用，⛔ 绝不进每帧路径）。
         *
         * ⛔ **v1.6 起生产代码已无调用点**（旧亮版天幕靠它插值，新亮版直接用 `*_LIT` 具名色标）。
         * 保留是因为它是**通用工具**且门禁会核对它的插值语义；若确认无外部价值可一并删除。
         */
        fun mixColor(a: Color, b: Color, t: Float): Color {
            val k = t.coerceIn(0f, 1f)
            return Color(
                red = a.red + (b.red - a.red) * k,
                green = a.green + (b.green - a.green) * k,
                blue = a.blue + (b.blue - a.blue) * k,
                alpha = a.alpha + (b.alpha - a.alpha) * k,
            )
        }

        /**
         * 山脊基线高度（px）—— ⛔ **与画路径同源**（见 [RIDGE] 的 1/3、2/3 控制点说明）。
         *
         * 这是「树根既不浮空也不陷地」的唯一保证，因此单独做成纯函数并由门禁锁死。
         */
        fun ridgeYAt(x: Float, w: Float, horizonY: Float, h: Float): Float =
            horizonY + ridgeDy(if (w > 0f) x / w else 0f) * h

        /** 山脊相对偏移（`horizonY` 的 h 倍数）—— [RIDGE] 的 Bernstein 正算。 */
        fun ridgeDy(u: Float): Float {
            val g = ridgeSegFor(u)
            val t = ((u - g.x0) / (g.x1 - g.x0)).coerceIn(0f, 1f)
            val k = 1f - t
            return k * k * k * g.y0 + 3f * k * k * t * g.c1 + 3f * k * t * t * g.c2 + t * t * t * g.y1
        }

        /** 归一化 x 落在哪一段（⛔ 越界钳到首/末段，保证函数全域有定义）。 */
        fun ridgeSegFor(u: Float): RidgeSeg {
            var i = 0
            while (i < RIDGE.size) {
                if (u <= RIDGE[i].x1) return RIDGE[i]
                i++
            }
            return RIDGE[RIDGE.size - 1]
        }

        /**
         * 枝条可用上升高度（px）—— 恒 > 0（见 [TREE_BUDGET_MIN] 的说明）。
         *
         * 单独抽成纯函数：原型迭代时正是这里**符号写反**让全部枝条反向画出、读成下垂的
         * 「蜘蛛腿」，必须能被单测直接判住。
         */
        fun limbBudget(originY: Float, treeBaseY: Float, treeH: Float): Float {
            val budget = originY - (treeBaseY - treeH)
            return if (budget < TREE_BUDGET_MIN) TREE_BUDGET_MIN else budget
        }

        /**
         * 枝条逐段角度推进（⛔ 纯函数，门禁据此判「枝条向上伸展」）。
         *
         * `[LIMB_PULL_BACK]` 把角度往竖直（0）方向收回 —— 这正是「向上伸展而非下垂」的来源。
         */
        fun nextLimbAngle(angRad: Float, curl: Float, jitter: Float): Float =
            (angRad + curl * (jitter - 0.5f) * LIMB_JITTER) * LIMB_PULL_BACK

        /**
         * 枝尖 y（px，纯函数）—— ⛔ 与 [addLimb] 的逐段步进**同式**（共用 [nextLimbAngle]），
         * 因此门禁用它判「枝尖高于出枝点」是真的在判生产几何。
         */
        fun limbTipY(x0: Float, y0: Float, angRad0: Float, segLen: Float, curl: Float, seed: Int): Float {
            var px = x0
            var py = y0
            var a = angRad0
            var i = 0
            while (i < LIMB_SEGS) {
                a = nextLimbAngle(a, curl, hash01(seed * 7 + i))
                px += sin(a) * segLen
                py -= cos(a) * segLen
                i++
            }
            return py
        }

        /**
         * 确定性哈希（`sin` 分数部分）—— 枯枝抖动的**唯一**来源。
         *
         * ⛔ 不得换成 `Math.random()` 之类的非确定源：否则每次 resize 树都不一样，且无法写单测。
         */
        fun hash01(i: Int): Float {
            val v = sin(i * 12.9898f) * 43758.5453f
            return v - floor(v)
        }

        /** 存活批次窗口：柱容量 = [SpectrumContract.BAR_COUNT]（⛔ 每帧实际柱数另读 `spectrum.size`）。 */
        const val MAX_DT_S = 0.1f

        // —— 内部小工具（⛔ 都不碰 android.graphics）——
        private fun pack(r: Float, g: Float, b: Float): Int =
            (0xFF shl 24) or
                (r.toInt().coerceIn(0, 255) shl 16) or
                (g.toInt().coerceIn(0, 255) shl 8) or
                b.toInt().coerceIn(0, 255)

        private fun setArgbAlpha(rgb: Int, alpha: Int): Int =
            (alpha.coerceIn(0, 255) shl 24) or (rgb and 0x00FFFFFF)
    }
}