package com.nasmusic.tv.visualizer.renderers

import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerMath
import com.nasmusic.tv.visualizer.VisualizerRenderer
import com.nasmusic.tv.visualizer.fx.FxLevel
import com.nasmusic.tv.visualizer.fx.ProceduralTexture
import com.nasmusic.tv.visualizer.fx.Shading2D
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

// ═══════════════════════════════════════════════════════════════════
// E11 星系螺旋
// ═══════════════════════════════════════════════════════════════════

/**
 * E11 `GALAXY_SPIRAL` — 星系螺旋
 *
 * 十六个旋臂等分圆周，臂上的"星"绕中心旋转；
 * 亮度较高的低频柱走亮臂、其余走暗臂，形成旋转的星系。
 *
 * ## §B1 五条（阶段 4 · 批次 B）
 * ① 星点按 `v` 分 **4 桶**（原 2 桶）+ **远臂纵深**（`t > FAR_T` ⇒ 半径 ×0.6 **且降一档桶**）；
 * ② **尘埃带**：16 条对数螺线合批 **1 条** `Path`（`alpha 0.10`、`darken(accent, 0.4f)`、
 *    `strokeWidth = maxR × 0.10`）—— 1 次 `drawPath` 就有"星系"结构；
 * ③ **中心核**改径向渐变（`shadeBrushCached` + 专属盐）+ 1 层 `towardWhite` 内高光；
 * ④ **`dt` 化旋转**（⛔ 原 `rotation += 0.15f + bpm / 1200f` 是**每帧常量** ⇒ 与帧率绑定：
 *    同一首歌 60fps 与 30fps 下转速差 **2 倍**）；
 * ⑤ 后处理 `vignette 0.46` + `grain 0.028`（声明式 `postFx`）。
 *
 * ## 性能红线
 * 16 臂 × 90 星 = 1440 个点，按桶合并为 **4 条 `Path`**（Android 5.1 hwui region 合并 SIGSEGV）。
 * ⚠️ **所有 `Path` / `Stroke` 预分配** —— 原实现每帧 `Path()` ×2 是 §四 G15 的违规项（本轮修掉）。
 *
 * ## ⛔ 三处「别改回去」
 * - **渐变半径不含 `energy`**：`shadeBrushCached` 的键若含 `coreR`，核脉动时每帧都会 miss
 *   ⇒ 每帧新建 `Brush`（分配 `Pair` + 两个 `List`）⇒ 违反零分配红线。这里让 **brush 半径固定**、
 *   只让 `drawCircle` 的 `radius` 随 `energy` 变 ⇒ 观感是"核越大、边缘越暗"，物理上也成立。
 * - **`E11_KEY_SALT`**：`Shading2D` 是 Kotlin **object** ⇒ 它的 16 槽 Brush 缓存**进程级共享**，
 *   不带盐会与 E03 / E07 / E13 / E15 / E32 撞键（拿到别人的 `center` / `radius` / `contrast`）。
 * - **尘埃带描边按档位预分配**（[dustStrokes] + [dustTier]）：宽度 = `maxR × DUST_W_K` 依赖画布
 *   短边，若在 `drawContent` 内 `Stroke(width = …)` 就是**每帧一次堆分配**（`Stroke` 是普通 class）。
 *   这与 E15 的 `inStrokes` / `outStrokes` 是同一范式。
 */
class GalaxySpiralRenderer : RendererFx() {

    override val theme = VisualizerTheme.GALAXY_SPIRAL

    /** §B1-⑤ 收尾后处理（暗角 0.46 ≥ §13.5-D9 下限 0.42） */
    override val postFx = PostFx(vignette = 0.46f, grain = 0.028f)

    /** §B1-④ `fx.dt` 累加相位（**度**，与原实现同单位） */
    private var rotation = 0f

    /** §B1-① 星点 [BUCKETS] 桶；远臂"降一档桶"复用同一批 Path ⇒ 不额外增加 draw 次数 */
    private val starPaths = Array(BUCKETS) { Path() }

    /** §B1-② 尘埃带：16 条螺线合批 1 条 `Path`（1 次 `drawPath`） */
    private val dustPath = Path()

    /** §B1-② 尘埃带描边：按 [DIM_TIERS] 档位**预分配** ⇒ `drawContent` 内零分配 */
    private val dustStrokes = Array(DIM_TIERS.size) { i ->
        Stroke(width = DIM_TIERS[i] * MAX_R_K * DUST_W_K)
    }

    internal companion object {
        /** 旋臂数（16 臂等分圆周） */
        const val ARMS = 16
        const val ARM_DEG = 360f / ARMS

        /** §B1-① 星点亮度桶数（原 2 桶 ⇒ 4 桶） */
        const val BUCKETS = 4

        /** 星点半径 = `STAR_R0 + v × STAR_RV`（沿用原 `2.6f + v × 6.5f`） */
        const val STAR_R0 = 2.6f
        const val STAR_RV = 6.5f

        /** §B1-① 远臂门限与纵深系数 */
        const val FAR_T = 0.62f
        const val FAR_R_K = 0.6f

        /** 各桶「向白」系数与 alpha（下标越大越亮） */
        val STAR_WHITE = floatArrayOf(0.25f, 0.45f, 0.62f, 0.80f)
        val STAR_ALPHA = floatArrayOf(0.45f, 0.60f, 0.75f, 1.00f)

        /** 对数螺线参数（与原实现一致：`r = a0 × exp(B_PARAM × theta × 6)`） */
        const val THETA_MAX = 4.2f
        const val B_PARAM = 0.18f
        const val A0_K = 0.05f
        const val MAX_R_K = 0.58f

        /** 度 → 弧度换算里的 57.3（原实现直接写死；`VisualizerMath.rad` 只做弧度化） */
        const val RAD2DEG = 57.3f

        /**
         * 每臂星点数。
         *
         * ⚠️ **低画质必须单独一档**（v1.47 真机低画质全效果扫描：本套 11 fps）。
         * 本套的 4 条星点 `Path` 只是**提交次数**少，**元素数**并没有少 —— 低画质取 55 时
         * 每帧仍有 16 × 55 = 880 个互不相连的小椭圆，而本机实测
         * **≈500 个零散小矩形 ≈ 7.6 fps / ≈24 个 ≈ 59 fps**（§11.3.6 P-1，数字雨合并前后两个实测点）。
         * 取 16 ⇒ 256 个，落在同一曲线上。
         */
        const val PER_ARM_HIGH = 90
        const val PER_ARM_MEDIUM = 55
        const val PER_ARM_LOW = 16

        /** §B1-② 尘埃带 */
        const val DUST_SEG = 24
        const val DUST_ALPHA = 0.10f
        const val DUST_W_K = 0.10f
        const val DUST_DARKEN = 0.40f

        /**
         * 画布短边档位（px）—— 尘埃带描边宽度 = `档位 × MAX_R_K × DUST_W_K`。
         * 档位取「最小的 ≥ `minDim` 的档」，超出末档则用末档（4K 及以上封顶）。
         */
        val DIM_TIERS = floatArrayOf(720f, 1080f, 1440f, 2160f)

        /** §B1-③ 中心核（`CORE_R_K` 同时是**渐变半径**与核基准半径） */
        const val CORE_R_K = 0.07f
        const val CORE_CONTRAST = 0.50f
        const val CORE_HL_K = 0.42f
        const val CORE_HL_ALPHA = 0.55f
        const val CORE_HL_WHITE = 0.85f

        /** §B1-④ 旋转速率：「每帧增量（60fps 基准）→ 每秒速率」的换算基准 */
        const val ROT_BASE_DEG = 0.15f
        const val BPM_DIV = 1200f
        const val FPS_REF = 60f

        /**
         * 按档位选每臂星点数（**纯函数**，门禁可直接调用）。
         * 判据是**元素总数**（`ARMS × perArm`），不是提交次数 —— 见 [PER_ARM_LOW] 的说明。
         */
        internal fun perArmFor(
            quality: com.nasmusic.tv.data.model.VisualQuality
        ): Int = when (quality) {
            com.nasmusic.tv.data.model.VisualQuality.HIGH -> PER_ARM_HIGH
            com.nasmusic.tv.data.model.VisualQuality.MEDIUM -> PER_ARM_MEDIUM
            com.nasmusic.tv.data.model.VisualQuality.LOW -> PER_ARM_LOW
        }

        /** ⛔ 见类 KDoc：`Shading2D` 的 Brush 缓存进程级共享 ⇒ 每个调用点必须有自己的盐 */
        const val E11_KEY_SALT = 0x11111111L

        /** 按画布短边选尘埃带描边档位（**纯函数**，门禁可直接调用；`drawContent` 内不构造对象） */
        internal fun dustTier(minDim: Float): Int {
            var i = 0
            while (i < DIM_TIERS.size - 1 && minDim > DIM_TIERS[i]) i++
            return i
        }

        /**
         * §B1-① 星点桶位（**纯函数**，门禁可直接调用）：`v` 分 [BUCKETS] 档；
         * `far = true`（远臂）时**降一档**（等价于"降 alpha"，且不增加 `Path` 条数）。
         */
        internal fun starBucket(v: Float, far: Boolean): Int {
            val b = (v * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
            return if (far) (b - 1).coerceAtLeast(0) else b
        }

        /**
         * §B1-④ 旋转速率（**度/秒**，**纯函数**）—— 「帧率无关」的唯一来源。
         *
         * 原实现 `rotation += 0.15f + bpm / 1200f` 是**每帧增量** ⇒ 与帧率绑定。
         * 这里把它解释为 **60fps 基准的每帧增量**（[FPS_REF]），换算成每秒速率后
         * 由 `rotation += rotRateDegPerSec(bpm) * fx.dt` 积分 ⇒ 30fps / 60fps 转速一致。
         */
        internal fun rotRateDegPerSec(bpm: Float): Float =
            (ROT_BASE_DEG + bpm / BPM_DIV) * FPS_REF
    }

    override fun onEnterContent(ctx: RenderContext) {
        rotation = 0f
    }

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return

        val cx = w / 2f
        val cy = h / 2f
        val minDim = ctx.minDim
        val accent = ctx.palette.accent
        val n = ctx.quality.barCount
        val perArm = perArmFor(ctx.quality)
        val maxR = minDim * MAX_R_K
        val a0 = minDim * A0_K

        // ── §B1-④ dt 化旋转 ──
        // 原实现是「每帧 +（0.15 + bpm/1200）度」⇒ 与帧率绑定。这里把该常量解释为
        // **60fps 基准的每帧增量**，换算成「度/秒」后乘 `fx.dt` ⇒ 30fps / 60fps 转速一致。
        rotation += rotRateDegPerSec(frame.bpm) * fx.dt

        dustPath.rewind()
        var k = 0
        while (k < BUCKETS) {
            starPaths[k].rewind()
            k++
        }

        // ── §B1-② 尘埃带（16 条螺线 → 1 条 Path → 1 次 drawPath）──
        // 与星点走**同一条对数螺线**（同 a0 / B_PARAM / THETA_MAX）⇒ 带子正好压过旋臂。
        // `r` 随 `t` 单调递增 ⇒ 越界用 `break`（不是 `continue`）。
        var arm = 0
        while (arm < ARMS) {
            val armOffset = arm * ARM_DEG
            var first = true
            var i = 0
            while (i <= DUST_SEG) {
                val t = i.toFloat() / DUST_SEG
                val theta = t * THETA_MAX
                val r = a0 * exp(B_PARAM * theta * 6f)
                if (r > maxR) break
                val ang = VisualizerMath.rad(armOffset + theta * RAD2DEG + rotation * (1f - t * 0.55f))
                val p = VisualizerMath.polar(cx, cy, r, ang)
                if (first) {
                    dustPath.moveTo(p.x, p.y)
                    first = false
                } else {
                    dustPath.lineTo(p.x, p.y)
                }
                i++
            }
            arm++
        }
        drawPath(
            path = dustPath,
            color = VisualizerMath.darken(accent, DUST_DARKEN),
            alpha = DUST_ALPHA,
            style = dustStrokes[dustTier(minDim)]
        )

        // ── §B1-① 星点：16 臂 × perArm，按 `v` 分 4 桶合批 ──
        var a = 0
        while (a < ARMS) {
            val armOffset = a * ARM_DEG
            var i = 0
            while (i < perArm) {
                val t = i.toFloat() / perArm
                val theta = t * THETA_MAX
                val r = a0 * exp(B_PARAM * theta * 6f)
                if (r > maxR) break
                // 越远角速度越慢（差速旋转）
                val ang = VisualizerMath.rad(armOffset + theta * RAD2DEG + rotation * (1f - t * 0.55f))
                val freqIdx = (t * n).toInt().coerceIn(0, n - 1)
                val v = frame.spectrum.getOrElse(freqIdx) { 0f }
                val p = VisualizerMath.polar(cx, cy, r, ang)
                val far = t > FAR_T
                var pr = STAR_R0 + v * STAR_RV
                if (far) pr *= FAR_R_K              // §B1-① 远臂纵深：半径 ×0.6
                val bucket = starBucket(v, far)     // §B1-① 远臂同时降一档桶（不增加 Path 条数）
                // T1.6.2（§四 G15）：addOval 走 android.graphics.Path 的 float 重载，零 Rect 分配
                starPaths[bucket].asAndroidPath().addOval(
                    p.x - pr, p.y - pr, p.x + pr, p.y + pr, android.graphics.Path.Direction.CCW)
                i++
            }
            a++
        }
        k = 0
        while (k < BUCKETS) {
            drawPath(
                path = starPaths[k],
                color = VisualizerMath.towardWhite(accent, STAR_WHITE[k]),
                alpha = STAR_ALPHA[k]
            )
            k++
        }

        // ── §B1-③ 中心核：径向渐变 + 内高光 ──
        // ⛔ 渐变半径**固定**（不含 energy），否则键每帧都变 ⇒ 每帧重建 Brush（分配）。
        val coreR = minDim * CORE_R_K * (1f + frame.energy * 0.45f)
        drawCircle(
            brush = Shading2D.shadeBrushCached(
                key = (w.toRawBits().toLong() shl 32) xor h.toRawBits().toLong() xor
                    accent.toArgb().toLong() xor E11_KEY_SALT,
                center = Offset(cx, cy),
                radius = minDim * CORE_R_K,
                base = accent,
                contrast = CORE_CONTRAST
            ),
            radius = coreR,
            center = Offset(cx, cy),
            alpha = 0.5f + frame.pulse * 0.5f
        )
        drawCircle(
            color = VisualizerMath.towardWhite(accent, CORE_HL_WHITE),
            radius = coreR * CORE_HL_K,
            center = Offset(cx, cy),
            alpha = CORE_HL_ALPHA
        )
    }
}

// ═══════════════════════════════════════════════════════════════════
// E12 频谱瀑布
// ═══════════════════════════════════════════════════════════════════

/**
 * E12 `SPECTRO_WATERFALL` — 频谱瀑布
 *
 * 横轴=频率、纵轴=时间（自上而下滚动），能量→色相。
 *
 * **性能红线**：必须缓存为 ImageBitmap，每帧整体上移 + 底部画新行。
 * 禁止逐格 drawRect（60×64 = 3840 次调用必崩）。
 *
 * ⛔ 已迁移到 [RendererFx] 基类（§十 **S2**）：只实现 `onEnterContent` / `drawContent` /
 * `onExitContent`；后处理由 `postFx` 声明（§A4 第 6 条）。
 */
class WaterfallRenderer : RendererFx() {

    override val theme = VisualizerTheme.SPECTRO_WATERFALL

    // §A4-6：暗角 0.44（≥ §13.5-D9 下限 0.42）+ 扫描线 0.12（与 CRT 仪器气质一致）。
    // ⚠️ 偏差（§12.4）：§A4 第 4 条另要求「叠 1 张水平细线 tile（alpha 0.10）」，但
    // `ProceduralTexture` 只有**一种** SCANLINE tile（周期固定、不可参数化）⇒ 它与第 6 条
    // 的 `drawScanlines` 是同一张 ⇒ **两者合并**，避免同一张 tile 叠两遍。
    override val postFx = PostFx(vignette = 0.44f, scanline = 0.12f)

    private var prev: ImageBitmap? = null
    private var curr: ImageBitmap? = null
    private val paint = androidx.compose.ui.graphics.Paint()

    /** §A4-2 衰减层画笔：纯黑 `alpha = FADE_ALPHA`，构造期建一次（每帧只写不改） */
    private val fadePaint = androidx.compose.ui.graphics.Paint().apply {
        color = Color.Black.copy(alpha = FADE_ALPHA)
    }

    private val rows = BUFFER_ROWS

    /**
     * §A4-3：底部新行按 [HUE_BUCKETS] 个色阶桶合并 ⇒ **16 次 `drawPath`** 取代 `n × 4` 次 `drawRect`。
     *
     * **桶键为什么可以直接用 `v` 而不是 `hue`**：`hueGradient(60f, 195f, v)` 的最短弧
     * `d = 135°`（既不 `> 180` 也不 `< -180`）⇒ `hue = 60 + 135·v` 是 **`v` 的线性单调函数**
     * ⇒ **`v` 的等分就是 `hue` 的等分**；且桶色与帧无关 ⇒ **构造期算一次，每帧零颜色计算**。
     */
    private val bucketPaths = Array(HUE_BUCKETS) { AndroidPath() }
    private val bucketPaints = Array(HUE_BUCKETS) { AndroidPaint() }

    /** §A4-4：4 条等分垂直参考线（复用单 Path，每帧 `rewind`） */
    private val gridPath = Path()

    override fun onEnterContent(ctx: RenderContext) {
        // §A4-1：横向分辨率 128 → barCount × 4（HIGH / MEDIUM = 256，LOW = 128）
        val w = ctx.quality.barCount * SUB_COLS
        prev = ImageBitmap(w, rows)
        curr = ImageBitmap(w, rows)

        // §A4-3 + §A4-5：桶色 = hue(v_b) 配亮度曲线 `0.34 + pow(v_b, 0.72f) × 0.52f`
        //（原 `0.50 + v × 0.40` ⇒ 低能量段偏亮、动态范围被压扁）
        for (b in 0 until HUE_BUCKETS) {
            val vb = (b + 0.5f) / HUE_BUCKETS
            val hue = VisualizerMath.hueGradient(60f, 195f, vb)   // 黄(60°)→蓝(195°)
            val light = 0.34f + vb.pow(0.72f) * 0.52f
            bucketPaints[b].color = VisualizerMath.hsl(hue, 1.0f, light).toArgb()
        }
    }

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        val p = prev ?: return
        val c = curr ?: return

        val bw = c.width
        val bh = c.height
        val n = ctx.quality.barCount
        val sub = (bw / n).coerceAtLeast(1)
        val y = (bh - 1).toFloat()

        val cb = androidx.compose.ui.graphics.Canvas(c)

        // ① 把上一帧整体上移 1px 画到当前缓冲（乒乓，禁止自我绘制）
        cb.drawImage(p, Offset(0f, -1f), paint)

        // ② §A4-2 衰减：⛔ **顺序不可反** —— 先「上移 + 衰减」、再写底部新行，
        //    否则黑层会压到新行（原方案写的「新行亮度反向补偿 +0.06」与这个顺序互斥，已删）。
        //    每行 ×0.98 ⇒ 历史行按距离衰减，消除「持续高频时底部一片白」。
        //    ⛔ 衰减率与缓冲行数是一对（顶层残留须落在 1%–5%，见 FADE_ALPHA 的 KDoc）：
        //    0.06 那版只让底部 1/3 有内容，上面 2/3 恒为全黑。
        cb.drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), fadePaint)

        // ③ §A4-3 底部新行：n × SUB_COLS 个子列 → 16 条 Path（16 次 drawPath）
        for (b in 0 until HUE_BUCKETS) bucketPaths[b].rewind()
        for (i in 0 until n) {
            val v0 = frame.spectrum.getOrElse(i) { 0f }
            val v1 = frame.spectrum.getOrElse(i + 1) { v0 }
            val x0 = (i * sub).toFloat()
            for (k in 0 until sub) {
                // §A4-1：行内相邻桶线性插值 ⇒ 消除「行间横向条带」
                val v = v0 + (v1 - v0) * (k.toFloat() / sub)
                val b = (v * HUE_BUCKETS).toInt().coerceIn(0, HUE_BUCKETS - 1)
                val x = x0 + k
                bucketPaths[b].addRect(x, y, x + 1f, y + 1f,
                    android.graphics.Path.Direction.CCW)
            }
        }
        val nc = cb.nativeCanvas
        for (b in 0 until HUE_BUCKETS) nc.drawPath(bucketPaths[b], bucketPaints[b])

        // ④ 铺满画布 + 交换缓冲
        drawImage(c, dstSize = androidx.compose.ui.unit.IntSize(
            size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)
        ))
        prev = c
        curr = p

        // ⑤ §A4-4 网格：4 条等分垂直参考线（1 次 drawPath）。
        //    新增 draw 且观感增量小 ⇒ OFF 档跳过（§7.1 预算纪律，与 E03 速度线同规）
        if (fx.level != FxLevel.OFF) {
            val gw = size.width
            val gh = size.height
            gridPath.rewind()
            for (k in 1 until GRID_DIV) {
                val gx = gw * k / GRID_DIV
                gridPath.moveTo(gx, 0f)
                gridPath.lineTo(gx, gh)
            }
            drawPath(
                gridPath,
                VisualizerMath.towardWhite(ctx.palette.accent, 0.55f),
                alpha = 0.12f,
                style = Stroke(width = 1f)
            )
        }
    }

    override fun onExitContent() {
        // 2026-09-26 审查补修（Milkdrop 缓冲 recycle 同族遗漏）：onExit 此前只置 null，
        // API 22-25 上 Bitmap 像素在 native 堆、仅靠 finalizer 延迟回收。显式 recycle。
        releaseBuffers()
    }

    /** 释放乒乓双缓冲（与 MilkdropRenderer 同模式；可安全重复调用，只回收未置空的位图） */
    private fun releaseBuffers() {
        try { prev?.asAndroidBitmap()?.recycle() } catch (_: Exception) {}
        try { curr?.asAndroidBitmap()?.recycle() } catch (_: Exception) {}
        prev = null
        curr = null
    }

    internal companion object {
        /** §A4-1 每个频谱桶横向展开的子列数 */
        const val SUB_COLS = 4

        /** §A4-3 色阶桶数（= hue 桶数，因 `hue` 是 `v` 的线性函数） */
        const val HUE_BUCKETS = 16

        /**
         * §A4-2 每行衰减量（1 - 0.02 = ×0.98）。
         *
         * ⛔ **必须与 [BUFFER_ROWS] 一起定**：拖尾要刚好铺满缓冲，即顶层残留
         * `(1-FADE_ALPHA)^BUFFER_ROWS` 落在「看得见但已接近黑」的 1%–5% 区间。
         * 原值 0.06（配 200 行）⇒ 第 63 行就衰减到 2%、**上面 2/3 屏恒为全黑**，
         * v1.47 真机低画质扫描时用户反馈「上面都是黑色的」。
         * ⚠️ 该缺陷**三档皆然**，只是低画质此前根本进不到这套效果，没人看见过。
         * ⛔ 与 E18 `MilkdropRenderer.DECAY_ALPHA` 的"同值同义"关系**自此解除**：
         * E18 的衰减层压的是「3-tap 回绘累积成灰白」，与这里的拖尾长度不是同一个量。
         */
        const val FADE_ALPHA = 0.02f

        /**
         * 乒乓缓冲的行数（每帧上移 1 行、底部写 1 新行 ⇒ 缓冲 = 屏幕上的拖尾历史长度）。
         * 与 [FADE_ALPHA] 的耦合见上，门禁 `WaterfallTrailFadeTest` 守着。
         */
        const val BUFFER_ROWS = 200

        /** §A4-4 垂直参考线等分数（4 条线 ⇒ 5 等分） */
        const val GRID_DIV = 5
    }
}

// ═══════════════════════════════════════════════════════════════════
// E13 液态网格
// ═══════════════════════════════════════════════════════════════════

/**
 * E13 `LIQUID_GRID` — 液态网格
 *
 * 网格顶点被三频正弦叠加推动，形成液体表面（波纹 shader 的 2D 离散平替）。
 * 密度按画质档位控制——这是本套效果唯一的性能风险点。
 *
 * ## §A5 质感改造
 * - **⛔ 相位红线（§10.176）**：原写法 `t = frame.timeMs`，`t * 0.002f` 在 1e7 量级基数下
 *   float 精度丢失（长时间运行波纹冻结/跳变）⇒ 改为 `elapsed += fx.dt`（`dt` 由基类钳 0.1s），
 *   与太阳系 / `DnaRenderer` / `WorldRenderer` 一致；
 * - **受光网格**：连线按顶点高度分 4 桶，颜色
 *   `lerp(darken(accent, 0.50f), towardWhite(accent, 0.35f), h)` —— 高处的线更亮；
 * - **镜面反光**：顶点由圆点改为沿「光向垂线」（`LIGHT_ANGLE_DEG + 90°`）拉长的椭圆
 *   （长轴 = 短轴 × 2.4）。⚠️ 用**参数方程 8 段多边形**而非 `drawOval`：
 *   `Path.addOval` 只能生成**轴对齐**椭圆，无法表达 45° 旋转（见 §12.4 偏差记录）；
 * - **三段式背景（G9）**：径向纵深渐变 + `WATER` 水纹 tile + `STARFIELD` 远景微粒 tile；
 * - **水下焦散**：`CAUSTIC` tile 缓慢漂移（`dstOffset` 负向偏移 + `dstSize` 同步放大 ⇒ 永不露边）；
 * - **后处理**：`PostFx(vignette = 0.46f, grain = 0.032f)`。
 */
class LiquidGridRenderer : RendererFx() {

    override val theme = VisualizerTheme.LIQUID_GRID

    // §A5-6 收尾后处理（暗角 0.46 ≥ §13.5-D9 下限 0.42）
    override val postFx = PostFx(vignette = 0.46f, grain = 0.032f)

    private var cols = 24
    private var rows = 14
    private var xs = FloatArray(0)
    private var ys = FloatArray(0)

    /** §A5-2：顶点高度（`wave`）—— 连线按它分 4 桶着色 */
    private var ws = FloatArray(0)

    /** §A5-1：`fx.dt` 累加相位。⛔ 不得改回 `frame.timeMs × 系数` */
    private var elapsed = 0f

    // 连线按高度分 4 桶（§A5-2）+ 顶点按列分 4 段 hue（沿用原结构）；
    // 合并进 Path 是硬要求（Android 5.1 hwui region 合并 SIGSEGV）
    private val linePaths = Array(4) { Path() }
    private val ptPaths = Array(4) { Path() }
    private val lineStroke = Stroke(width = 1f)

    /** 光向的**垂线** = 反光长轴方向（`LIGHT_ANGLE_DEG + 90°`）：屏幕极角系里把 [Shading2D.lightDir] 旋 90° */
    private val perpX = -Shading2D.lightDir.y
    private val perpY = Shading2D.lightDir.x

    /** 参数方程 8 段的 cos/sin 表（构造期算一次 ⇒ draw 期零三角函数、零分配） */
    private val cosSeg = FloatArray(SEG) { cos(TWO_PI * it / SEG) }
    private val sinSeg = FloatArray(SEG) { sin(TWO_PI * it / SEG) }

    override fun onEnterContent(ctx: RenderContext) {
        cols = ctx.quality.gridCols
        rows = ctx.quality.gridRows
        val count = cols * rows
        if (xs.size < count) {
            xs = FloatArray(count)
            ys = FloatArray(count)
            ws = FloatArray(count)
        }
        elapsed = 0f
    }

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        if (xs.isEmpty()) onEnterContent(ctx)
        val w = size.width
        val h = size.height
        val iw = w.toInt().coerceIn(1, 4096)
        val ih = h.toInt().coerceIn(1, 4096)
        val accent = ctx.palette.accent
        val scale = 1f + (if (frame.beat) 0.06f else 0f)

        elapsed += fx.dt

        // ── §A5-4 三段式背景（G9）：① 径向纵深 ② 水纹 tile ③ 远景微粒 tile ──
        // 背景三段**不随 FxLevel 关闭**（与已落地的 E07 一致）：它承担"不再浮在纯黑上"，
        // 而 vignette / grain 这类"胶片感"后处理才按档位关（见 postFx）。
        ProceduralTexture.ensure(iw, ih)
        drawRect(brush = Shading2D.shadeBrushCached(
            key = (w.toRawBits().toLong() shl 32) xor h.toRawBits().toLong() xor
                accent.toArgb().toLong() xor E13_KEY_SALT,
            center = Offset(w / 2f, h * 0.46f),
            radius = maxOf(w, h) * 0.62f,
            base = VisualizerMath.darken(accent, 0.55f),
            contrast = 0.10f))
        ProceduralTexture.tile(ProceduralTexture.Id.WATER)?.let {
            drawImage(it, dstSize = IntSize(iw, ih), alpha = 0.18f)
        }
        ProceduralTexture.tile(ProceduralTexture.Id.STARFIELD)?.let {
            drawImage(it, dstSize = IntSize(iw, ih), alpha = 0.16f)
        }

        // ── §A5-5 水下焦散：低频驱动的缓慢漂移宽条纹（1 次 drawImage）──
        // 漂移 = dstOffset 负向偏移 + dstSize 同步放大 ⇒ 永不露边、无需第二次绘制。
        ProceduralTexture.tile(ProceduralTexture.Id.CAUSTIC)?.let { c ->
            val ox = (abs(sin(elapsed * 0.13f)) * iw * 0.04f).toInt()
            val oy = (abs(cos(elapsed * 0.09f)) * ih * 0.04f).toInt()
            drawImage(
                image = c,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(c.width, c.height),
                dstOffset = IntOffset(-ox, -oy),
                dstSize = IntSize(iw + ox, ih + oy),
                alpha = 0.06f + frame.bass * 0.08f
            )
        }

        // 计算顶点位移（§A5-1：相位一律来自 elapsed）
        var k = 0
        for (gy in 0 until rows) {
            for (gx in 0 until cols) {
                val x = gx.toFloat()
                val y = gy.toFloat()
                val wave =
                    sin(x * 0.3f + elapsed * 2f) * frame.bass * 40f +
                        sin(y * 0.5f + elapsed * 3f) * frame.mid * 28f +
                        sin((x + y) * 0.8f + elapsed * 10f) * frame.treble * 12f
                val px = (gx.toFloat() / (cols - 1).coerceAtLeast(1)) * w
                val py = (gy.toFloat() / (rows - 1).coerceAtLeast(1)) * h * 0.85f + h * 0.07f
                val cx0 = w / 2
                val cy0 = h / 2
                xs[k] = cx0 + (px - cx0) * scale + wave * 0.6f
                ys[k] = cy0 + (py - cy0) * scale + wave
                ws[k] = wave
                k++
            }
        }

        // ── §A5-2 受光网格：连线按顶点高度分 4 桶着色（4 次 drawPath）──
        // 低画质**不再跳过连线**（v1.47 真机：低档实测 29 fps，省这 4 次 drawPath 换不回帧率，
        // 丢的却是这套效果唯一的结构感；该分支在低画质能进本效果之前是死代码，从没被验证过）
        for (p in linePaths) p.reset()
        for (gy in 0 until rows) {
            val rowBase = gy * cols
            for (gx in 0 until cols - 1) {
                val i = rowBase + gx
                val b = heightBucket((ws[i] + ws[i + 1]) * 0.5f)
                linePaths[b].moveTo(xs[i], ys[i])
                linePaths[b].lineTo(xs[i + 1], ys[i + 1])
            }
        }
        for (gx in 0 until cols) {
            for (gy in 0 until rows - 1) {
                val i = gy * cols + gx
                val j = i + cols
                val b = heightBucket((ws[i] + ws[j]) * 0.5f)
                linePaths[b].moveTo(xs[i], ys[i])
                linePaths[b].lineTo(xs[j], ys[j])
            }
        }
        val lo = VisualizerMath.darken(accent, 0.50f)
        val hi = VisualizerMath.towardWhite(accent, 0.35f)
        val lineAlpha = 0.32f + frame.energy * 0.12f
        for (b in 0 until 4) {
            drawPath(
                linePaths[b],
                lerp(lo, hi, (b + 0.5f) * 0.25f),
                alpha = lineAlpha,
                style = lineStroke
            )
        }

        // ── §A5-3 顶点 = 镜面反光：沿"光向垂线"拉长的椭圆（长轴 = 短轴 × 2.4）──
        // 参数方程：P(θ) = c + aMaj·cosθ·u + bMin·sinθ·v，u = (perpX, perpY)、v = (-perpY, perpX)。
        // ⛔ 不调 close()：填充路径按 Skia 语义隐式闭合 ⇒ 省 1 次 native 调用/顶点。
        for (p in ptPaths) p.reset()
        val spec = frame.spectrum
        for (i in 0 until k) {
            val v = spec.getOrElse(i % spec.size) { 0f }
            val r = (2.6f + v * 4f).coerceAtLeast(1.2f)
            val seg = ((i % cols) * 4 / cols).coerceIn(0, 3)
            val p = ptPaths[seg]
            val x0 = xs[i]
            val y0 = ys[i]
            val aMaj = r * 1.2f     // 半长轴（沿 u）
            val bMin = r            // 半短轴（沿 v）
            for (s in 0 until SEG) {
                val c = cosSeg[s]
                val sn = sinSeg[s]
                val dx = aMaj * c * perpX - bMin * sn * perpY
                val dy = aMaj * c * perpY + bMin * sn * perpX
                if (s == 0) p.moveTo(x0 + dx, y0 + dy) else p.lineTo(x0 + dx, y0 + dy)
            }
        }
        val hueBase = 60f   // 黄(60°)→绿(105°)→蓝(150°)→亮蓝(195°)
        val ptAlpha = 0.65f + frame.energy * 0.30f
        for (s in 0 until 4) {
            drawPath(ptPaths[s], VisualizerMath.hsl(hueBase + s * 45f, 1.0f, 0.72f), alpha = ptAlpha)
        }
    }

    /**
     * 顶点高度 → 桶号（0..3）。`wave` 的理论幅度上界 = `40 + 28 + 12 = 80`
     * ⇒ `h = (wave / 80 + 1) / 2`，`bucket = (h * 4).toInt()` = `(wave * 0.025 + 2).toInt()`。
     */
    private fun heightBucket(wave: Float): Int =
        (wave * 0.025f + 2f).toInt().coerceIn(0, 3)

    private companion object {
        /** 反光椭圆的多边形段数（`r ≈ 1.2–6.6px` ⇒ 8 段最大偏差 ≈ 0.08·r ≤ 0.5px，肉眼不可辨） */
        const val SEG = 8
        val TWO_PI = 2f * PI.toFloat()

        /**
         * 与其它渲染器的 `shadeBrushCached` 键区分（§四 G4「缓存键维度 ⊇ 依赖维度」/ §12.4）。
         *
         * ⛔ `Shading2D` 是 Kotlin **object** ⇒ Brush 缓存**进程级共享**。本类与 E03 / E07
         * 原先都用 `(w, h, accent)` 三元键 ⇒ 撞键 ⇒ **切换效果后复用错 `center` / `radius` /
         * `contrast` 的 Brush**（E15 早已因此加了 `E15_KEY_SALT`，本次补齐）。
         */
        const val E13_KEY_SALT = 0x13131313L
    }
}

// ═══════════════════════════════════════════════════════════════════
// E15 液态涟漪
// ═══════════════════════════════════════════════════════════════════

/**
 * E15 `LIQUID_RIPPLE` — 液态涟漪
 *
 * 低频产生大波纹、高频产生小涟漪，多组同心圆扩散叠加（比 E13 更"水"）。
 *
 * ## §A6 质感改造
 * - **双边水波（G2）**：每环 2 次 `drawCircle` —— 内圈 `darken(accent, 0.45f)`（半径 `r`）
 *   + 外圈 `towardWhite(accent, 0.45f)`（半径 `r + wIn`，与内圈相接）⇒ 波峰受光 / 波谷背光；
 * - **扩散变薄**：`width = base × (1 - progress)`（`progress = 1 - life`）。
 *   ⚠️ 量化到 4 档以复用**预分配** `Stroke` —— 否则每帧 80 次 `Stroke` 分配（见 §12.4 偏差）；
 * - **水面底纹（G9）**：径向纵深渐变 + `WATER` 水纹 tile（`alpha 0.14f`）；
 * - **中心高光**：`beat` 时中心 1 次径向渐变白亮光斑（半径 `minDim × 0.10f`，随 `pulse` 衰减），
 *   原来的 5 个同心细环减为 2 个；
 * - **节流统一**：`frame.timeMs % 8 < 2` → `fx.dt` 累加（与 `dt` 体系一致，§A6-⑤）；
 * - **后处理**：`PostFx(vignette = 0.44f, grain = 0.030f)`。
 */
class LiquidRippleRenderer : RendererFx() {

    override val theme = VisualizerTheme.LIQUID_RIPPLE

    // §A6-5 收尾后处理
    override val postFx = PostFx(vignette = 0.44f, grain = 0.030f)

    // x, y, r, life, kind
    private var ripples = FloatArray(40 * 5)
    private var head = 0

    /** §A6-⑤：低频大波纹的节流改 `dt` 累加（原 `frame.timeMs % 8 < 2` 与 `dt` 体系不一致） */
    private var bassSpawnAcc = 0f

    /** §A6-4：中心光斑的径向渐变 Brush —— 只在 (w, h, accent) 变化时重建 ⇒ 每帧零分配 */
    private var glowW = -1f
    private var glowH = -1f
    private var glowKey = 0L
    private var glowBrush: Brush? = null

    // §A6-1/2：双边水波的两档预分配 Stroke（[kind][level]；kind 0 = 小涟漪 / 1 = 大波纹）
    private val inStrokes = Array(2) { k -> Array(W_LEVELS) { q -> Stroke(width = widthAt(k, q)) } }
    private val outStrokes = Array(2) { k -> Array(W_LEVELS) { q -> Stroke(width = widthAt(k, q) * 0.8f) } }

    override fun onEnterContent(ctx: RenderContext) {
        ripples.fill(0f)
        head = 0
        bassSpawnAcc = 0f
    }

    override fun onExitContent() {
        glowBrush = null
        glowKey = 0L
    }

    private fun spawn(x: Float, y: Float, life: Float, kind: Float) {
        val o = head * 5
        ripples[o] = x
        ripples[o + 1] = y
        ripples[o + 2] = 0f
        ripples[o + 3] = life
        ripples[o + 4] = kind
        head = (head + 1) % 40
    }

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        val w = size.width
        val h = size.height
        val iw = w.toInt().coerceIn(1, 4096)
        val ih = h.toInt().coerceIn(1, 4096)
        val accent = ctx.palette.accent
        val maxR = ctx.minDim * 0.80f

        // ── §A6-3 水面底纹（G9）：径向纵深 + 水纹 tile ──
        ProceduralTexture.ensure(iw, ih)
        drawRect(brush = Shading2D.shadeBrushCached(
            key = (w.toRawBits().toLong() shl 32) xor h.toRawBits().toLong() xor
                accent.toArgb().toLong() xor E15_KEY_SALT,
            center = Offset(w / 2f, h / 2f),
            radius = maxOf(w, h) * 0.62f,
            base = VisualizerMath.darken(accent, 0.55f),
            contrast = 0.10f))
        ProceduralTexture.tile(ProceduralTexture.Id.WATER)?.let {
            drawImage(it, dstSize = IntSize(iw, ih), alpha = 0.14f)
        }

        // 生成：高频小涟漪
        if (frame.treble > 0.20f) {
            spawn(rng.next() * w, rng.next() * h, 1f, 0f)
        }
        // 生成：低频大波纹（§A6-⑤ 节流统一到 dt 累加）
        if (frame.bass > 0.35f) {
            bassSpawnAcc += fx.dt
            if (bassSpawnAcc >= BASS_SPAWN_PERIOD) {
                bassSpawnAcc = 0f
                spawn(w / 2, h / 2, 1f, 1f)
            }
        } else {
            bassSpawnAcc = 0f
        }
        // 生成：节拍（§A6-4：5 个同心细环 → 2 个，其余冲击感交给中心光斑）
        if (frame.beat) {
            for (i in 0 until 2) spawn(w / 2, h / 2, 1f, 1f)
        }

        // ── §A6-1/2 双边水波：内圈（背光）+ 外圈（受光），扩散中变细 ──
        val inDark = VisualizerMath.darken(accent, 0.45f)
        val outLight = VisualizerMath.towardWhite(accent, 0.45f)
        for (i in 0 until 40) {
            val o = i * 5
            val life = ripples[o + 3]
            if (life <= 0f) continue
            val kind = if (ripples[o + 4] == 1f) 1 else 0
            val speed = if (kind == 1) 8f + frame.bass * 10f else 5f + frame.treble * 7f
            ripples[o + 2] += speed
            ripples[o + 3] -= 0.008f
            val r = ripples[o + 2]
            if (r > maxR) { ripples[o + 3] = 0f; continue }
            // 扩散变薄：宽度按 life 量化到 4 档（复用预分配 Stroke ⇒ 零每帧分配）
            val q = (life * W_LEVELS).toInt().coerceIn(0, W_LEVELS - 1)
            val wIn = widthAt(kind, q)
            val cx = ripples[o]
            val cy = ripples[o + 1]
            val a = life * (if (kind == 1) 0.45f else 0.24f)
            drawCircle(
                color = inDark,
                radius = r,
                center = Offset(cx, cy),
                alpha = a,
                style = inStrokes[kind][q]
            )
            drawCircle(
                color = outLight,
                radius = r + wIn,
                center = Offset(cx, cy),
                alpha = a,
                style = outStrokes[kind][q]
            )
        }

        // ── §A6-4 中心高光：beat 冲击感的来源 ──
        if (frame.pulse > 0.02f) {
            val rr = ctx.minDim * 0.10f
            val key = (w.toRawBits().toLong() shl 32) xor h.toRawBits().toLong() xor
                accent.toArgb().toLong()
            if (glowBrush == null || glowW != w || glowH != h || glowKey != key) {
                val bright = VisualizerMath.towardWhite(accent, 0.92f)
                glowBrush = Brush.radialGradient(
                    0f to bright,
                    1f to bright.copy(alpha = 0f),
                    center = Offset(w / 2f, h / 2f),
                    radius = rr
                )
                glowW = w
                glowH = h
                glowKey = key
            }
            drawCircle(
                brush = glowBrush!!,
                radius = rr,
                center = Offset(w / 2f, h / 2f),
                alpha = frame.pulse * 0.35f
            )
        }
    }

    private companion object {
        /** §A6-2：`width = base × (1 - progress)` 的量化档数 */
        const val W_LEVELS = 4

        /** 两档基础线宽（下标 = kind：0 小涟漪 / 1 大波纹），沿用原 `1.2f / 3f` */
        val W_BASE = floatArrayOf(1.2f, 3f)

        /** §A6-⑤：低频大波纹的生成周期（秒） */
        const val BASS_SPAWN_PERIOD = 0.10f

        /** 与 E13 的 `shadeBrushCached` 键区分（否则切换效果后会复用错半径的 Brush） */
        const val E15_KEY_SALT = 0x5F5F5F5FL

        fun widthAt(kind: Int, level: Int): Float = W_BASE[kind] * (level + 1) / W_LEVELS
    }
}

// ═══════════════════════════════════════════════════════════════════
// E16 数字雨
// ═══════════════════════════════════════════════════════════════════

/**
 * E16 `MATRIX_RAIN` — 数字雨
 *
 * **二进制字符雨**：字符集固定为 `0` / `1`（设计本意 —— 勿扩为 0-9 或十六进制；
 * 历史上 v2.30.4 曾误扩为 0-9，已撤销，见 §13.5-D1）。
 * 0/1 列下落，绿色系（白热头部 → 亮白绿 → 亮绿 → 中绿 → 暗绿），
 * 速度/亮度由该列绑定频段能量驱动。
 *
 * **性能优化**：字形（2 字符 × 5 档绿 = 10 张）在尺寸首次确定时预渲染成 Bitmap，
 * 每帧改用 nativeCanvas.drawBitmap 快速 blit —— 纹理 blit 远快于逐字符 drawText
 * 的文本排布度量，显著降低 TV 弱 GPU 上的每帧开销。每列高度由 perCol 控制，
 * 列数随画质档位调整，整体保持在小幅 draw 预算内。
 * ⚠️ 10 张字形**不直接上屏**：先合成 2 张「整列条带」（见下方 ⑤），每帧每列只 1 次 blit。
 *
 * ## §B3 质感改造（T4.3）
 *
 * **① 头部光晕** —— ⛔ **不额外发 draw**：光晕**预渲染进「白热头部」字形 Bitmap**
 * （`RadialGradient`，半径 `字形高 × [GLOW_R_RATIO]`、中心 alpha `[GLOW_ALPHA]`）。
 * §B3 原方案是「每列 1 次 `drawCircle(brush=)`（HIGH 48 列 = 48 draw）或按亮度分 4 桶合并」，
 * 本实现改为**烘进贴图**：径向衰减是**真的**（4 桶合并只能给平涂色块），
 * 且**额外 draw 数 = 0** —— E16 已是全库 draw 数第 2 高的效果（456 原语/帧，§7.5），
 * 再加 48 次 draw 会直接与 S1.7/T1.7.4「降 blit 数」的目标对撞。
 *
 * **② 垂直拖影** —— 逐格 [trailFade] **修正为「从头部向外线性衰减」**：
 * 旧实现写 `1 - k/perCol` ⇒ **越远离头部越亮**，与 KDoc 声明的
 * 「亮白绿头部 → 亮绿 → 中绿 → 暗绿」**方向相反**，观感是头部上方先暗一截、
 * 再亮起来（"断成两截"）。⛔ 同时**未新增 per-column `drawRect(brush=)`**（48 次/帧），
 * 理由同 ①；格间过渡由「修正后的逐格 fade + 字形内部垂直渐变（③）」共同承担。
 *
 * **③ 字形质感** —— 预渲染时每张字形 = **深绿外描边**（`Paint.Style.STROKE`）
 * + **中心偏白的垂直渐变填充**（`LinearGradient` 三停靠
 * `base → towardWhite(base, GLYPH_HIGHLIGHT) → base`）；档位 **4 → 5**
 * （新增「白热头部」`rgb(235,255,235)`）⇒ 张数 **8 → 10**，
 * ⛔ **每帧 draw 次数与 blit 路径完全不变**。
 *
 * **④ 后处理** —— `PostFx(vignette = 0.50f, grain = 0.030f, scanline = 0.16f)`。
 *
 * **⑤ 列条合并（P-1 第二步，§11.3.6）** —— ①–③ 都是「把成本搬进预渲染」，
 * 但**每帧仍然 14 次 blit/列**。本机实测成本 ≈ **0.45 ms/op**（P-1 的成本模型）
 * ⇒ MEDIUM 的 448 个 op 把帧率压到个位数。现按 [columnStrips] 的不变式把一整列
 * **预合成成 1 张条带位图** ⇒ 每帧每列 **1 次** blit
 * （LOW 336 → 24、MEDIUM 448 → 32、HIGH 672 → 48 个绘制 op）。
 * ⚠️ 代价：条带比单列内容宽 `2 × glowPad`（头部光晕本来就会盖到邻列，必须保住），
 * 像素吞吐上升、native 堆多 ≈1.3 MB —— 本机瓶颈是 **op 数**而非像素，故划算。
 * ⚠️ 与逐格 blit 的差异只有两处且都不可辨：每格行位取整到整像素（≤0.5 px）、
 * 中间 8bit 缓冲的 alpha 舍入（源合成满足结合律，绘制顺序与旧实现逐格一致）。
 * ⛔ **数字每 300 ms 翻转的招牌观感保留** —— 翻转由「取哪一条色带的条带」完成，
 * 不是重建条带（这也是 2 张条带就够的原因）。
 *
 * ## 帧率无关（与 E11 / E14 同一约定）
 * 旧实现 `colY += speed`（**每帧**固定增量）⇒ 60fps 的雨速是 30fps 的 2 倍。
 * 现改为 `colY += speed × fx.dt × [RAIN_FPS_BASE]`：`RAIN_FPS_BASE = 60` ⇒
 * **60fps 下与旧的「每帧 + speed」逐像素等同**，30/15fps 下速度不再翻倍。
 *
 * ⛔ 迁移 [RendererFx] 后 `draw` / `onEnter` / `onExit` 均为 `final` ⇒ 子类只实现
 * `onEnterContent` / `onExitContent` / `drawContent`（**不要**再写 `override fun onEnter`）。
 */
class MatrixRainRenderer : RendererFx() {

    override val theme = VisualizerTheme.MATRIX_RAIN

    // §B3-④ 收尾后处理（CRT 扫描线 + 暗角 + 颗粒）
    // P-2：暗角边色锁死为深绿 —— 本效果的身份色就是绿，若沿用封面 accent，
    //       播蓝紫封面的歌时 `vignette = 0.50` 会把整幅压成蓝紫，"黑客帝国"感全失。
    override val postFx = PostFx(
        vignette = 0.50f,
        grain = 0.030f,
        scanline = 0.16f,
        vignetteEdge = VIGNETTE_EDGE
    )

    private var colY = FloatArray(0)
    private var colSpeed = FloatArray(0)
    private var cols = 32
    private val perCol = 14

    // 预渲染字形缓存：索引 = shade * 2 + digit（二进制雨，字符集固定 0/1）
    //   shade 0     = 白热头部（**贴图比其余档四周各多 glowPad** —— 烘了光晕环）
    //   shade 1..4  = 亮白绿 / 亮绿 / 中绿 / 暗绿
    // ⇒ 共 10 张 = 5 档 × 2 字符（§B3-③：档位 4 → 5）
    // G13④：internal（非 private）仅供 PerfBudgetContractTest 注入 stub 缓存、验证键三元组
    internal var glyphs: Array<android.graphics.Bitmap>? = null

    // S1.6/T1.6.1（§四 G16 / §八 G13④）：字形缓存键 = 三元组 Int 字段。
    // ⛔ 不得改回字符串模板键 "slot:cell:n" —— 那是每帧一次 String 分配（draw 可达路径）。
    internal var keySlot = 0
    internal var keyCell = 0
    internal var keyN = 0
    private var gW = 0
    private var gH = 0

    /** 头部贴图四周留出的光晕环宽（px）。由 [buildGlyphs] 写入；非头部 blit 不受影响 */
    private var glowPad = 0

    /**
     * P-1 第二步（§11.3.6）：**整列拖影合并位图**，索引 = 头部数字（0 / 1），共 2 张。
     *
     * 为什么 2 张就够（⛔ 改动前必须复核这条不变式，门禁 ⑩ 锁的就是它）：
     *  ① 每格数字 `digitIdx = (i*31 + k*17 + tick) and 1`，**17 是奇数** ⇒ 同一列内数字逐格必然交替
     *    ⇒ 整列的数字形态只由「头部那一格是 0 还是 1」决定；
     *  ② 每格的档位 `shadeFor(k, perCol)` 与 alpha `trailAlpha(k, perCol)` **只依赖 `k`**（静态）。
     * ⇒ 每帧每列从 **14 次 `drawBitmap` 降到 1 次**（LOW 336 → 24、MEDIUM 448 → 32、HIGH 672 → 48 个绘制 op）。
     * ⚠️ 它治的是 P-1 的**卡顿**（实测 ≈0.45 ms/op），**不治 P-3 的崩溃**（脏区 span 数 ≈ 各矩形覆盖行数之和，
     *    合并后基本不变）—— 崩溃由 `RendererFx` 的脏区占位绘制负责。
     * **条带布局**（⛔ 改动必须与 `drawContent` 里的 `stripDx / stripDy` 同步）：
     * 条带 `(0,0)` = 「头部字形位图（含光晕 `pad`）放在第 0 格时它的左上角」
     * ⇒ 格 `k` 的本地坐标：头部 `(0, k×cellH)`、其余 `(pad, k×cellH + pad)`，
     * 尺寸 `(gW + 2pad) × ((perCol-1)×cellH + gH + 2pad)`。
     * ⚠️ 头部光晕半径 `pad = 字形高 × [GLOW_R_RATIO]`（1080p 实测 54 px）**本来就盖到邻列**
     *    （`slot` 只有 60 px）⇒ 条带比 `slot` 宽不是 bug，是旧实现的既有观感，必须保住；
     *    逐列、逐格的绘制先后**与旧实现完全一致**（源合成满足结合律 ⇒ 合并成中间位图不改结果）。
     * ⚠️ 每格行位**取整到整像素**（旧实现逐格浮点定位）⇒ 单格位置差 ≤0.5 px；
     *    整条带仍以浮点坐标 blit，下落动画的平滑度不受影响。
     * ⛔ 与 [glyphs] 同生同灭，且必须一起进 [releaseGlyphs]（API 22–25 位图在 native 堆，§九 R2）。
     */
    private var columnStrips: Array<android.graphics.Bitmap>? = null

    /** **重建路径**专用：合成条带时逐格 alpha（⛔ 每帧 blit 不得用它 —— alpha 会被上一格残留污染） */
    private val blitPaint = AndroidPaint()

    /** **每帧**条带 blit 专用：alpha 恒 255（逐格衰减已烘进条带） */
    private val stripPaint = AndroidPaint()

    override fun onEnterContent(ctx: RenderContext) {
        cols = when (ctx.quality) {
            com.nasmusic.tv.data.model.VisualQuality.HIGH -> 48
            com.nasmusic.tv.data.model.VisualQuality.LOW -> 24
            else -> 32
        }
        colY = FloatArray(cols)
        colSpeed = FloatArray(cols)
        for (i in 0 until cols) {
            colY[i] = rng.next() * 1000f
            colSpeed[i] = 5f + rng.next() * 7f
        }
        releaseGlyphs()
    }

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        if (colY.isEmpty()) onEnterContent(ctx)
        val w = size.width
        val h = size.height
        val n = colY.size
        val slot = w / n
        val cellH = h / perCol
        val textSize = minOf(slot * 0.8f, cellH * 0.9f).coerceIn(10f, 48f)

        // 尺寸/列数变化 → 重建字形缓存（首次进入或画质变化；键比较零分配，G13④）
        val slot10 = (slot * 10).toInt()
        val cell10 = (cellH * 10).toInt()
        // 缓存未建（首次/重建后）由调用点短路；键三元组比较零分配（G13④）
        if (glyphs == null || glyphCacheStale(slot10, cell10, n)) {
            buildGlyphs(textSize, cellH)
            keySlot = slot10
            keyCell = cell10
            keyN = n
        }

        val nc = drawContext.canvas.nativeCanvas
        val st = columnStrips ?: return
        val tick = (frame.timeMs / 300L).toInt()
        val span = h + cellH * perCol
        val pad = glowPad.toFloat()
        // 条带 (0,0) 相对「头部名义格位」的偏移 —— 与 [buildStrips] 的布局同源，⛔ 两处必须一致
        val stripDx = (slot - gW) / 2f - pad
        val stripDy = (cellH - gH) / 2f - pad

        for (i in 0 until n) {
            val v = frame.spectrum.getOrElse((i * frame.spectrum.size / n).coerceAtMost(frame.spectrum.size - 1)) { 0f }
            val speed = colSpeed[i] * (0.5f + v * 2.5f)
            // 帧率无关：× fx.dt × RAIN_FPS_BASE（60fps 下与旧的「每帧 + speed」逐像素等同）
            colY[i] = advanceCol(colY[i], speed, fx.dt, span)
            val headY = colY[i] - cellH * perCol
            // 整列 1 次 blit（P-1 第二步）：取哪一条 = 头部数字，数字翻转不必重建条带
            nc.drawBitmap(st[digitAt(i, perCol - 1, tick)], i * slot + stripDx, headY + stripDy, stripPaint)
        }
    }

    /**
     * G13④：字形缓存键三元组比较 —— 任一维变化 ⇒ 需要重建。零分配。
     * 「缓存未建 ⇒ 重建」不在本函数（untestable：glyphs 是 Bitmap 数组），
     * 由 draw 调用点的 `glyphs == null ||` 短路兜底。
     */
    internal fun glyphCacheStale(slot10: Int, cell10: Int, n: Int): Boolean =
        keySlot != slot10 || keyCell != cell10 || keyN != n

    /**
     * 一次性预渲染字形 Bitmap。
     *
     * 档位 **5**（§B3-③，原 4）：`0` = 白热头部（`rgb(235,255,235)`，**额外烘入径向光晕**）、
     * `1..4` = 亮白绿 / 亮绿 / 中绿 / 暗绿。字符集固定 `0`/`1`（⛔ 不扩，§13.5-D1）。
     * 每张 = **深绿外描边** + **中心偏白的垂直渐变填充**。
     * ⇒ 共 **10 张**；这 10 张**不再直接上屏**，而是由 [buildStrips] 合成为 2 张整列条带（⑤）。
     *
     * ⛔ `cellH` 也必须进签名并传给 [buildStrips] —— 条带的行位取决于格高，
     *    只传 `textSize` 会让条带按旧格高排版（尺寸变化时整列错位）。
     */
    private fun buildGlyphs(textSize: Float, cellH: Float) {
        val digits = charArrayOf('0', '1')
        val measure = AndroidPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            this.textSize = textSize
        }
        val bw = kotlin.math.ceil(measure.measureText("0")).toInt() + 4
        val bh = kotlin.math.ceil(textSize * 1.15f).toInt() + 4
        gW = bw
        gH = bh
        // 头部贴图四周留出的光晕环宽（= 光晕半径），§B3-①
        val pad = (bh * GLOW_R_RATIO).toInt()
        glowPad = pad
        val strokeW = (textSize * STROKE_RATIO).coerceIn(1f, 3f)
        val baseY = (bh - (measure.descent() - measure.ascent())) / 2f - measure.ascent()
        val cx = pad + bw / 2f
        val cy = pad + bh / 2f
        // ⛔ 文字绘制原点不在这里定：头部与非头部的位图尺寸差一个 pad，
        //    统一坐标会让其中一档的字形整体出界被裁（见下方 shade 循环内的说明）。

        // ⛔ Paint / Shader **复用**（只 3 个 Paint + 1 个光晕 shader + 每档 1 个填充 shader）。
        //    每张字形各 new 一遍会让 §7.5 的「分配/帧」列虚高（该列把**重建路径**也计入）。
        val outline = AndroidPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textAlign = android.graphics.Paint.Align.CENTER
            this.textSize = textSize
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = strokeW
            color = OUTLINE_RGB
        }
        val fill = AndroidPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textAlign = android.graphics.Paint.Align.CENTER
            this.textSize = textSize
        }
        // ① 头部光晕 shader：中心/半径只依赖 (pad, bh) ⇒ 与档位无关，只建 **1** 个（§B3-①）
        val glow = AndroidPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.RadialGradient(
                cx, cy, pad.toFloat(),
                android.graphics.Color.argb((GLOW_ALPHA * 255f).toInt(), 0, 255, 100),
                android.graphics.Color.argb(0, 0, 255, 100),
                android.graphics.Shader.TileMode.CLAMP
            )
        }

        val arr = arrayOfNulls<android.graphics.Bitmap>(SHADES * 2)
        for (shade in 0 until SHADES) {
            val isHead = shade == SHADE_HEAD
            val pw = if (isHead) bw + pad * 2 else bw
            val ph = if (isHead) bh + pad * 2 else bh
            val rgb = SHADE_RGB[shade]
            // ⛔⛔ 绘制原点必须随位图尺寸走（2026-09-30 修复「数字雨尾迹整条消失」）
            //   只有头部档位的位图四周留了 pad；非头部的 pw/ph 就是 bw×bh。
            //   若沿用带 pad 的 cx/baseline，文字会整体右下偏移 pad（= bh×0.9，约 0.9 个字高），
            //   超出位图下缘被裁掉 ⇒ 10 张字形里 9 张全白 ⇒ 屏幕上只剩头部那一个数字。
            //   ⛔ 改 pad / bh / textSize / GLOW_R_RATIO 任一时都要复核这三行的联动。
            val ox = if (isHead) pad else 0
            val oy = if (isHead) pad else 0
            val textCx = ox + bw / 2f      // 文字水平中心
            val textBase = oy + baseY      // 文字基线
            // ③ 中心偏白的垂直渐变填充 shader：**每档 1 个**（⛔ 不在字符循环里重建）（§B3-③）
            //    渐变区间同样要跟随 oy，否则非头部的渐变整体下移、字形上下亮度分布被截断。
            fill.shader = android.graphics.LinearGradient(
                0f, oy.toFloat(), 0f, (oy + bh).toFloat(),
                intArrayOf(rgb, glyphHighlightArgb(rgb), rgb),
                floatArrayOf(0f, 0.5f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            for (d in 0 until 2) {
                val bmp = android.graphics.Bitmap.createBitmap(pw, ph, android.graphics.Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(bmp)
                // ① 头部光晕在最底层（中心 alpha GLOW_ALPHA → 边缘 0）；仅头部档位有 pad
                if (isHead) c.drawCircle(cx, cy, pad.toFloat(), glow)
                // ② 深绿外描边（§B3-③）
                c.drawText(digits[d].toString(), textCx, textBase, outline)
                // ③ 中心偏白的垂直渐变填充（§B3-③）
                c.drawText(digits[d].toString(), textCx, textBase, fill)
                arr[shade * 2 + d] = bmp
            }
        }
        glyphs = arr.filterNotNull().toTypedArray()
        buildStrips(cellH)
    }

    /**
     * 把 10 张字形合成为 **2 张整列条带**（⑤ · P-1 第二步），索引 = 头部数字。
     *
     * 依赖 [columnStrips] 的 KDoc 里那条不变式（列内数字只由头部数字决定、
     * 档位与 alpha 只由格号决定）—— 门禁 `MatrixRainTest` ⑩ 锁的就是它，
     * 一旦 `31` / `17` / `perCol` / [shadeFor] 任一改到破坏「同列逐格交替」，⑩ 会直接红。
     *
     * ⛔ 逐格 alpha 用 [blitPaint]（重建路径），每帧 blit 用 [stripPaint] —— 混用会把
     *    最后一格的 alpha 带进每一帧。
     */
    private fun buildStrips(cellH: Float) {
        val g = glyphs ?: return
        val pad = glowPad
        val stripW = gW + pad * 2
        val stripH = kotlin.math.ceil((perCol - 1) * cellH).toInt() + gH + pad * 2
        val arr = arrayOfNulls<android.graphics.Bitmap>(2)
        for (headDigit in 0 until 2) {
            val bmp = android.graphics.Bitmap.createBitmap(
                stripW, stripH, android.graphics.Bitmap.Config.ARGB_8888
            )
            val c = android.graphics.Canvas(bmp)
            // ⛔ 必须按 k 升序绘制（与旧的逐格 blit 同序）：头部光晕与上一格字形重叠，
            //    源合成虽满足结合律，但**顺序**决定谁压在上面。
            val headK = perCol - 1
            for (k in 0 until perCol) {
                val shade = shadeFor(k, perCol)
                val isHead = shade == SHADE_HEAD
                val rowY = if (isHead) {
                    kotlin.math.ceil(k * cellH).toInt()
                } else {
                    kotlin.math.ceil(k * cellH).toInt() + pad
                }
                blitPaint.alpha =
                    if (isHead) 255 else (trailAlpha(k, perCol) * 255f).toInt().coerceIn(0, 255)
                // 不变式：格 k 的数字 = 头部数字 ⊕ 与头部的格距奇偶（⊕ 只在 and 1 上做）
                val d = headDigit xor ((headK - k) and 1)
                c.drawBitmap(g[shade * 2 + d], if (isHead) 0f else pad.toFloat(), rowY.toFloat(), blitPaint)
            }
            arr[headDigit] = bmp
        }
        columnStrips = arr.filterNotNull().toTypedArray()
    }

    override fun onExitContent() {
        releaseGlyphs()
    }

    /** 释放字形与条带缓存：API < 26 上 Bitmap 像素在 native 堆，主动 recycle 更稳 */
    private fun releaseGlyphs() {
        glyphs?.forEach { it.recycle() }
        glyphs = null
        columnStrips?.forEach { it.recycle() }
        columnStrips = null
    }

    internal companion object {
        /**
         * 纯 Kotlin 打包 ARGB。⛔ **不要用 `android.graphics.Color.rgb`** ——
         * 单测 `unitTests.isReturnDefaultValues = true` 下它是 **no-op 返回 0**，
         * 常量表会被静默清成 0（门禁读到的颜色全是透明黑，而编译期毫无提示）。
         */
        internal fun packRgb(r: Int, g: Int, b: Int): Int =
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b

        /** 字形档位数（§B3-③：4 → 5，新增「白热头部」） */
        const val SHADES = 5

        /** 头部档位下标（同时也是 [SHADE_RGB] 里唯一「额外烘光晕」的那档） */
        const val SHADE_HEAD = 0

        /** 5 档绿：0 白热头部 / 1 亮白绿 / 2 亮绿 / 3 中绿 / 4 暗绿（亮度严格递减） */
        val SHADE_RGB = intArrayOf(
            packRgb(235, 255, 235),   // 0 白热头部（§B3-③ 新增档）
            packRgb(200, 255, 200),   // 1 亮白绿
            packRgb(0, 255, 100),     // 2 亮绿
            packRgb(0, 200, 50),      // 3 中绿
            packRgb(0, 130, 30)       // 4 暗绿
        )

        /** 深绿外描边色（§B3-③） */
        val OUTLINE_RGB = packRgb(0, 90, 20)

        /**
         * 暗角边色 = 深绿 `rgb(0, 52, 20)`（§11.3.6 P-2）。
         *
         * 取「比最暗字形 `SHADE_RGB[4] = rgb(0,130,30)` 再暗一档」：暗角负责把底色拉成绿黑，
         * 但⛔ 不得亮过最暗的那一档，否则拖影会被自己的背景吃掉。
         */
        val VIGNETTE_EDGE = Color(0xFF003414)

        /** 字形垂直渐变的高光位置（中心偏白）与强度（§B3-③） */
        const val GLYPH_HIGHLIGHT = 0.55f

        /** 外描边宽度 = `textSize × STROKE_RATIO`（钳在 1..3 px）（§B3-③） */
        const val STROKE_RATIO = 0.055f

        /** 头部光晕半径 = `字形高 × GLOW_R_RATIO`（§B3-①） */
        const val GLOW_R_RATIO = 0.9f

        /** 头部光晕中心 alpha（§B3-①） */
        const val GLOW_ALPHA = 0.30f

        /** 帧率无关基准：`RAIN_FPS_BASE = 60` ⇒ 60fps 下与旧「每帧 + speed」逐像素等同 */
        const val RAIN_FPS_BASE = 60f

        /** 逐格亮度下限（避免最远格 alpha = 0 的无效 blit）（§B3-②） */
        const val TRAIL_ALPHA_FLOOR = 0.08f

        /** 字形中心偏白的高光色（供门禁验证「确实比基色亮」） */
        internal fun glyphHighlightArgb(baseRgb: Int): Int =
            VisualizerMath.towardWhite(Color(baseRgb), GLYPH_HIGHLIGHT).toArgb()

        /**
         * §B3-②：拖影亮度按「**距头部的格距**」线性衰减 —— `k = perCol-1` 是头部（=1.0），
         * `k = 0` 是最远格（=0.0）。⛔ 方向不可反：旧实现写 `1 - k/perCol`
         * ⇒ 越远离头部越亮，与 KDoc「亮白绿头部 → 亮绿 → 中绿 → 暗绿」相反。
         */
        internal fun trailFade(k: Int, perCol: Int): Float = k.toFloat() / perCol

        /** blit alpha（0..1）：[trailFade] 加下限，避免最远格整格不可见 */
        internal fun trailAlpha(k: Int, perCol: Int): Float =
            TRAIL_ALPHA_FLOOR + trailFade(k, perCol) * (1f - TRAIL_ALPHA_FLOOR)

        /**
         * §B3-③：档位映射（纯函数，供门禁直接验证）。头部固定 [SHADE_HEAD]；
         * 其余按 [trailFade] 分 4 段：`> 0.66` 亮白绿 / `> 0.45` 亮绿 / `> 0.22` 中绿 / else 暗绿。
         */
        internal fun shadeFor(k: Int, perCol: Int): Int {
            if (k >= perCol - 1) return SHADE_HEAD
            val f = trailFade(k, perCol)
            return when {
                f > 0.66f -> 1
                f > 0.45f -> 2
                f > 0.22f -> 3
                else -> 4
            }
        }

        /** 帧率无关的列位移（纯函数，供门禁直接验证）：`(y + speed × dt × 60) mod span` */
        internal fun advanceCol(y: Float, speed: Float, dt: Float, span: Float): Float =
            (y + speed * dt * RAIN_FPS_BASE) % span

        /**
         * 格 `(i, k)` 在 `tick` 时刻显示哪个数字（0/1）—— **单一权威定义**，
         * `drawContent`（选条带）与 [buildStrips]（排条带）都走这里，⛔ 不得各写一遍算式。
         *
         * `31` / `17` 皆为奇数 ⇒ 同一列内数字**逐格必然交替**（⑤ 条带合并成立的前提），
         * `i × 31` 的奇偶 = `i` ⇒ 相邻列反相，`tick` 每 300 ms 全体翻转。
         */
        internal fun digitAt(i: Int, k: Int, tick: Int): Int = (i * 31 + k * 17 + tick) and 1
    }
}

// ═══════════════════════════════════════════════════════════════════
// E17 星座
// ═══════════════════════════════════════════════════════════════════

/**
 * E17 `CONSTELLATION` — 星座
 *
 * 节拍生成星点，邻近星点自动连线，随时间淡出。
 *
 * ## §A7 质感改造
 * - **星芒**：`size > 3.2f` 且 `life > 0.45f` 的亮星加十字光芒
 *   （长度 `r × 3.2`、`alpha 0.22`）—— ⚠️ 全部**合批进 1 条 `Path`**（1 次 `drawPath`，
 *   而非 2N 次 `drawLine`）；
 * - **连线分级**：距离 2 档（`d < 0.5 × linkDist` → 亮且宽 2.0f，否则 1.2f）
 *   × `min(lifeI, lifeJ)` 3 桶 alpha ⇒ 6 条 `Path`（6 次 `drawPath`）；
 * - **降复杂度**：`O(n²)` 双重循环（160×160 ≈ 12720 次判定）→ **空间网格分桶**
 *   （`cell ≥ linkDist` ⇒ 只查邻接 9 桶即完备）⇒ 判定次数 ≈ 1440（**9 倍**）。
 *   ⛔ 桶结构全部 `IntArray` 预分配（`List<Int>` 违反零分配）；
 * - **背景星野（G9）**：`STARFIELD` 静态远景星野（`alpha 0.28f`，**不衰减**）；
 * - **低画质降密度**（v1.47）：星点池 160 → 64（[starCapFor]）—— 本套贵在**元素数**而非提交次数，
 *   9 次 `drawPath` 里装着几百条互不相连的细线段，降提交数无从可降；
 * - **后处理**：`PostFx(vignette = 0.50f, grain = 0.026f)`（星座类暗角要重一些）。
 */
class ConstellationRenderer : RendererFx() {

    override val theme = VisualizerTheme.CONSTELLATION

    // §A7-5 收尾后处理
    override val postFx = PostFx(vignette = 0.50f, grain = 0.026f)

    // x, y, life, size
    private var stars = FloatArray(STARS * 4)
    private var head = 0

    /**
     * 本档实际使用的星点池容量（≤ [STARS]，数组仍按 [STARS] 分配 ⇒ 切档不重新分配）。
     * 判据见 [starCapFor]：本套的瓶颈是**连线线段的元素数**，而线段数 ∝ 密度²。
     */
    private var starCap = STARS

    // 连线 [距离档][life 桶] → 6 条 Path；星点 / 星芒各 1 条。
    // 合并进 Path 是硬要求（Android 5.1 hwui region 合并 SIGSEGV 高危）
    private val linkPaths = Array(2) { Array(3) { Path() } }
    private val starPath = Path()
    private val flarePath = Path()
    private val linkStrokes = arrayOf(Stroke(width = 2.0f), Stroke(width = 1.2f))
    private val flareStroke = Stroke(width = 1f)

    // §A7-3 空间网格：桶头链表 + 每星 next 指针（全部预分配 ⇒ 每帧零分配）
    private val cellHead = IntArray(MAX_CELLS)
    private val cellNext = IntArray(STARS)

    override fun onEnterContent(ctx: RenderContext) {
        starCap = starCapFor(ctx.quality)
        stars.fill(0f)
        head = 0
    }

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        val w = size.width
        val h = size.height
        val iw = w.toInt().coerceIn(1, 4096)
        val ih = h.toInt().coerceIn(1, 4096)
        val accent = ctx.palette.accent

        // ── §A7-4 背景星野（G9）：静态远景，不衰减 ⇒ 与动态星立刻分出纵深 ──
        ProceduralTexture.ensure(iw, ih)
        ProceduralTexture.tile(ProceduralTexture.Id.STARFIELD)?.let {
            drawImage(it, dstSize = IntSize(iw, ih), alpha = 0.28f)
        }

        // 生成星点（节拍大量生成 + 平时按能量持续补星，不再是节拍专属）
        val beatBonus = if (frame.beat) 9 else 0
        val ambient = (frame.energy * 3f).toInt()
        val spawn = (1 + beatBonus + ambient).coerceAtMost(14)
        for (it in 0 until spawn) {
            val v = frame.spectrum.getOrElse(
                (head * 7 + it * 13) % (frame.spectrum.size.coerceAtLeast(1))) { 0.3f }
            val o = head * 4
            stars[o] = rng.next() * w
            stars[o + 1] = h * 0.12f + (1f - v) * h * 0.76f
            stars[o + 2] = 1f
            stars[o + 3] = 2.2f + v * 5f
            head = (head + 1) % starCap
        }

        // 更新星点（衰减减慢 → 星点和连线存留更久、更密）
        for (i in 0 until starCap) {
            val oi = i * 4
            val li = stars[oi + 2]
            if (li <= 0f) continue
            stars[oi + 2] = li - 0.0011f
        }

        // ── §A7-3 空间网格分桶：cell ≥ linkDist ⇒ 邻接 9 桶完备 ──
        val linkDist = 75f + frame.energy * 80f
        val cell = maxOf(linkDist, w / GRID_MAX, h / GRID_MAX).coerceAtLeast(1f)
        val gcols = (w / cell).toInt().coerceIn(1, GRID_MAX)
        val grows = (h / cell).toInt().coerceIn(1, GRID_MAX)
        val cells = gcols * grows
        cellHead.fill(-1, 0, cells)
        for (i in 0 until starCap) {
            val oi = i * 4
            if (stars[oi + 2] <= 0f) { cellNext[i] = -1; continue }
            val gx = (stars[oi] / cell).toInt().coerceIn(0, gcols - 1)
            val gy = (stars[oi + 1] / cell).toInt().coerceIn(0, grows - 1)
            val c = gy * gcols + gx
            cellNext[i] = cellHead[c]
            cellHead[c] = i
        }

        // ── §A7-2 连线：每对星只判一次（同桶内 + 桶号更大的邻接桶）──
        for (t in 0 until 2) for (b in 0 until 3) linkPaths[t][b].reset()
        val maxD2 = linkDist * linkDist
        val nearD2 = maxD2 * 0.25f            // (0.5 × linkDist)²
        for (gy in 0 until grows) {
            for (gx in 0 until gcols) {
                val c = gy * gcols + gx
                var i = cellHead[c]
                while (i >= 0) {
                    val oi = i * 4
                    var j = cellNext[i]           // 同桶内：i 之后的结点
                    while (j >= 0) {
                        linkPair(oi, j * 4, maxD2, nearD2)
                        j = cellNext[j]
                    }
                    for (dy in -1..1) {
                        val ny = gy + dy
                        if (ny < 0 || ny >= grows) continue
                        for (dx in -1..1) {
                            val nx = gx + dx
                            if (nx < 0 || nx >= gcols) continue
                            val nc = ny * gcols + nx
                            if (nc <= c) continue       // 只处理桶号更大的 ⇒ 每对恰好一次
                            var k = cellHead[nc]
                            while (k >= 0) {
                                linkPair(oi, k * 4, maxD2, nearD2)
                                k = cellNext[k]
                            }
                        }
                    }
                    i = cellNext[i]
                }
            }
        }
        val linkAlpha = 0.42f + frame.energy * 0.30f
        for (t in 0 until 2) {
            val col = if (t == 0) VisualizerMath.towardWhite(accent, 0.35f) else accent
            for (b in 0 until 3) {
                drawPath(
                    linkPaths[t][b],
                    col,
                    alpha = linkAlpha * LIFE_FACTOR[b],
                    style = linkStrokes[t]
                )
            }
        }

        // ── 星点 + §A7-1 星芒（同一遍扫描，两条 Path）──
        starPath.reset()
        flarePath.reset()
        val starColor = VisualizerMath.towardWhite(accent, 0.70f + frame.pulse * 0.3f)
        for (i in 0 until starCap) {
            val o = i * 4
            val life = stars[o + 2]
            if (life <= 0f) continue
            val r = (stars[o + 3] * (0.6f + life * 0.4f)).coerceAtLeast(0.8f)
            // T1.6.2（§四 G15）：float addOval 零 Rect 分配
            starPath.asAndroidPath().addOval(
                stars[o] - r, stars[o + 1] - r, stars[o] + r, stars[o + 1] + r,
                android.graphics.Path.Direction.CCW)
            if (stars[o + 3] > FLARE_MIN_SIZE && life > FLARE_MIN_LIFE) {
                val l = r * 3.2f
                flarePath.moveTo(stars[o] - l, stars[o + 1])
                flarePath.lineTo(stars[o] + l, stars[o + 1])
                flarePath.moveTo(stars[o], stars[o + 1] - l)
                flarePath.lineTo(stars[o], stars[o + 1] + l)
            }
        }
        drawPath(flarePath, starColor, alpha = 0.22f, style = flareStroke)
        drawPath(starPath, starColor, alpha = 1f)
    }

    /** 判定一对星并按其距离档 / `min(life)` 桶写进对应 Path */
    private fun linkPair(oi: Int, oj: Int, maxD2: Float, nearD2: Float) {
        val dx = stars[oi] - stars[oj]
        val dy = stars[oi + 1] - stars[oj + 1]
        val d2 = dx * dx + dy * dy
        if (d2 >= maxD2) return
        val tier = if (d2 < nearD2) 0 else 1
        val life = minOf(stars[oi + 2], stars[oj + 2])
        val bucket = (life * 3f).toInt().coerceIn(0, 2)
        val p = linkPaths[tier][bucket]
        p.moveTo(stars[oi], stars[oi + 1])
        p.lineTo(stars[oj], stars[oj + 1])
    }

    internal companion object {
        /** 星点池容量上限（§A7：160 颗）；低画质取 [STARS_LOW]，见 [starCapFor] */
        const val STARS = 160
        const val STARS_LOW = 64

        /**
         * 按档位选星点池容量（**纯函数**，门禁可直接调用）。
         *
         * 低画质必须降容量（v1.47 真机低画质全效果扫描：本套 9 fps）。
         * ⚠️ 本套**提交次数已经很少**（9 次 `drawPath`），贵的是**元素数**：
         * 连线段数 ∝ 星点密度²（网格分桶后每星只查邻接 9 桶），
         * 所以容量 160 → 64 时线段数约降 **6.3 倍**（`(64/160)²`），星点本身只降 2.5 倍。
         * 本机实测曲线：≈500 个零散小矩形 ≈ 7.6 fps、≈24 个 ≈ 59 fps（§11.3.6 P-1）。
         */
        internal fun starCapFor(
            quality: com.nasmusic.tv.data.model.VisualQuality
        ): Int = if (quality == com.nasmusic.tv.data.model.VisualQuality.LOW) STARS_LOW else STARS

        /** 空间网格单轴最大桶数 ⇒ 桶总数 ≤ 32×32 */
        const val GRID_MAX = 32

        /** 桶头数组容量 = [GRID_MAX]² */
        const val MAX_CELLS = GRID_MAX * GRID_MAX

        /** §A7-1 星芒门限（尺寸取星点原始 size，非收缩后的 r） */
        const val FLARE_MIN_SIZE = 3.2f
        const val FLARE_MIN_LIFE = 0.45f

        /** life 3 桶系数（暗 → 亮，"两星都亮才亮"） */
        val LIFE_FACTOR = floatArrayOf(0.35f, 0.70f, 1.0f)
    }
}
