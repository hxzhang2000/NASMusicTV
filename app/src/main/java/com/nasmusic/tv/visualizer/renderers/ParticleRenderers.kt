package com.nasmusic.tv.visualizer.renderers

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.ParticlePool
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerMath
import com.nasmusic.tv.visualizer.fx.AudioSmoother
import com.nasmusic.tv.visualizer.fx.Shading2D
import kotlin.math.cos
import kotlin.math.sin

// ═══════════════════════════════════════════════════════════════════
// E14 节拍烟花
// ═══════════════════════════════════════════════════════════════════

/**
 * E14 `BEAT_FIREWORK` — 节拍烟花
 *
 * **刻意"留白"**：安静时近乎空屏，鼓点一到炸开满屏。
 * 静动反差是冲击力最强的手法，且平时极省电。
 */
class BeatFireworkRenderer : RendererFx() {

    override val theme = VisualizerTheme.BEAT_FIREWORK

    /**
     * §B2-④：暗角 `0.44` + 颗粒 `0.030`（"留白"气质 ⇒ 不加扫描线，免得和"安静"打架）。
     *
     * ⛔ **必须写字面量**：覆盖门禁 `FxCoverageScanTest.postFxRe` + `numRe` 只解析
     * `override val postFx = PostFx(vignette = 0.44f, …)` 这种**字面量**形式；
     * 换成 `PostFx(vignette = VIGNETTE, …)` 具名常量会被**静默判为"未覆盖"**
     * ⇒ `覆盖名单里的渲染器必须已覆盖后处理` 挂掉（见 §12.4 偏差）。
     */
    override val postFx = PostFx(vignette = 0.44f, grain = 0.030f)

    private var pool: ParticlePool? = null
    private var lastBeatMs = 0L
    private var idlePhase = 0f

    /** §B2-③ 常态底部频谱底纹：同色同 alpha ⇒ 合批单 `Path`（替代 64 次独立 `drawRect`） */
    private val bgPath = Path()

    /** §B2-① 拖尾：按 hue 分 [TRAIL_BUCKETS] 桶合批 ⇒ ≤ 8 次 `drawPath`（⛔ 不是每粒子一次 `drawLine`） */
    private val trailPaths = Array(TRAIL_BUCKETS) { Path() }

    /** 每桶的 life 累加与计数（算桶均值 alpha；`FloatArray` / `IntArray` 预分配 ⇒ 零分配） */
    private val trailLifeSum = FloatArray(TRAIL_BUCKETS)
    private val trailCount = IntArray(TRAIL_BUCKETS)

    /** 桶色：**构造期算一次**（每帧零颜色计算，与 T2.4「桶色构造期算一次」同范式） */
    private val trailColors = Array(TRAIL_BUCKETS) { b ->
        VisualizerMath.hsl(HUE_MIN + (b + 0.5f) * HUE_SPAN / TRAIL_BUCKETS, 1f, 0.68f)
    }

    /**
     * §B2-① 拖尾描边。⛔ **构造期建一次**：`Stroke` 是**普通 class**
     * （不是 `@JvmInline value class`）⇒ 每帧 `Stroke(width = …)` 就是一次堆分配。
     * 宽度恒定 ⇒ 一个实例足够（对比 E11 尘埃带：那里宽度依赖 `minDim` ⇒ 才需要按档预分配）。
     */
    private val trailStroke = Stroke(width = TRAIL_W)

    /** §B2-② 冲击波环描边（同上，宽度恒定） */
    private val ringStroke = Stroke(width = RING_W)

    /**
     * §B2-② 冲击波相位 `0..1`（`dt` 化累加）。
     *
     * ⛔ **不直接用 `pulse` 当半径**（§B2 原文字面如此），理由见 §12.4：
     * `pulse` 是**快起慢落**包络 ⇒ 拿它当半径会让环"先涨后缩"（与"扩散"相反），
     * 且中途再命中一次 `beat` 会让环**跳变**。`dt` 化相位单调推进 ⇒ 帧率无关且物理正确。
     */
    private var ringPhase = 1f          // 1 = 已结束（不画）
    private var ringX = 0f
    private var ringY = 0f

    /** 最近一次爆炸中心（闪光跟随）。⛔ 每拍都变 ⇒ **绝不能进 `Brush` 缓存键** */
    private var flashX = 0f
    private var flashY = 0f

    /** 是否至少炸过一次 —— 没炸过就不画闪光（否则闪光会画在画布左上角 `(0,0)`） */
    private var burstArmed = false

    internal companion object {
        // ── §B2-① 拖尾 ──
        /** §B2 明文要求「按 hue 分 **8** 桶」 */
        const val TRAIL_BUCKETS = 8

        /** 尾迹终点 = `(x − vx·k, y − vy·k)`（§B2 给的 `k`） */
        const val TRAIL_K = 2.5f

        /** §B2 给的拖尾 alpha 系数（本实现取**该桶 life 均值**，见 §12.4） */
        const val TRAIL_ALPHA_K = 0.35f

        const val TRAIL_W = 1.6f

        /** life 低于此值的粒子不画拖尾 —— 否则会出现"亮尾迹 + 看不见的头" */
        const val TRAIL_MIN_LIFE = 0.12f

        /** 色相区间：与 `spawnBurst` 的 `hueBase` 一致（黄 60° → 蓝 195°） */
        const val HUE_MIN = 60f
        const val HUE_SPAN = 135f

        /** 无鼓点兜底爆发的色相（原实现的字面值 `120f`，提为常量） */
        const val IDLE_HUE = 120f

        // ── §B2-② 冲击波环 + 中心闪光 ──
        /** 环从 0 扩散到 `RING_MAX_K × minDim` 用时（s） */
        const val RING_SEC = 0.55f

        /** §B2 明文：最大半径 = `0.25 × minDim` */
        const val RING_MAX_K = 0.25f
        const val RING_W = 3f
        const val RING_ALPHA = 0.55f
        const val FLASH_R_K = 0.13f
        const val FLASH_ALPHA_K = 0.45f

        /** 闪光基色 = 近白（保留一点 accent 色相） */
        const val FLASH_WHITE = 0.88f
        const val FLASH_CONTRAST = 0.55f

        // ── §B2-③ 静音期底纹（避免全黑）──
        /** §B2 明文：底纹 alpha `0.12 → 0.18` */
        const val BG_ALPHA = 0.18f
        const val BG_RADIAL_DARKEN = 0.62f
        const val BG_RADIAL_CONTRAST = 0.10f

        // ── §B2-④ 后处理：数值写在 `postFx` 字面量里（门禁只认字面量，见上）──

        /**
         * 背景径向纵深的缓存盐（§四 G4 / §15.4-A4）。
         *
         * ⛔ `Shading2D` 是 **object** ⇒ 16 槽 Brush 缓存**进程级共享** ⇒
         * 每个调用点必须有**具名盐**，否则会拿到别人的 `Brush`（`center` / `radius` /
         * `base` / `contrast` 全编码在实例内）。
         *
         * ⚠️ 本类有**两个** `shadeBrushCached` 调用点（背景纵深 / 中心闪光）⇒
         * 必须用**两个不同的盐**：同盐 ⇒ 第二个调用点拿到第一个的 `Brush`，
         * 闪光的 `center` / `radius` / `base` 全错（`logs_temp/s17_brushkey.py` 会抓）。
         */
        const val E14_BG_SALT = 0x14B6B6B6L
        const val E14_FLASH_SALT = 0x14F1F1F1L

        /** §B2-① hue → 桶位（**纯函数**，门禁可直接调用） */
        internal fun trailBucket(hue: Float): Int =
            (((hue - HUE_MIN) / HUE_SPAN) * TRAIL_BUCKETS).toInt().coerceIn(0, TRAIL_BUCKETS - 1)

        /** §B2-① 尾迹端点 x（**纯函数**）：尾迹落在**速度反方向** */
        internal fun trailTailX(x: Float, vx: Float): Float = x - vx * TRAIL_K

        /** §B2-① 尾迹端点 y（**纯函数**） */
        internal fun trailTailY(y: Float, vy: Float): Float = y - vy * TRAIL_K

        /** §B2-② 环半径（**纯函数**）：相位 `0→1` 映射到 `0 → RING_MAX_K × minDim` */
        internal fun ringRadius(phase: Float, minDim: Float): Float =
            phase.coerceIn(0f, 1f) * RING_MAX_K * minDim

        /** §B2-② 环 alpha（**纯函数**）：`0.55` 线性衰减到 `0` */
        internal fun ringAlpha(phase: Float): Float =
            RING_ALPHA * (1f - phase.coerceIn(0f, 1f))
    }

    override fun onEnterContent(ctx: RenderContext) {
        val cap = ctx.quality.maxParticles
        pool = if (cap > 0) ParticlePool(cap, rng) else null
        lastBeatMs = 0L
        idlePhase = 0f
        ringPhase = 1f
        ringX = 0f
        ringY = 0f
        flashX = 0f
        flashY = 0f
        burstArmed = false
    }

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        val p = pool ?: return
        val w = size.width
        val h = size.height
        if (w < 2f || h < 2f) return
        val minDim = ctx.minDim
        val accent = ctx.palette.accent

        // ── §B2-③ 静音期也不全黑：径向纵深（1 次 drawRect(brush=)）──
        // 与 E13 / E15 / E32 同范式：背景**不随 FxLevel 关闭**（它承担"不再浮在纯黑上"）。
        drawRect(
            brush = Shading2D.shadeBrushCached(
                key = (w.toRawBits().toLong() shl 32) xor h.toRawBits().toLong() xor
                    accent.toArgb().toLong() xor E14_BG_SALT,
                center = Offset(w / 2f, h * 0.62f),
                radius = maxOf(w, h) * 0.62f,
                base = VisualizerMath.darken(accent, BG_RADIAL_DARKEN),
                contrast = BG_RADIAL_CONTRAST
            )
        )

        // ── §B2-③ 常态频谱底纹（合批单 Path ⇒ 1 次 drawPath）──
        val n = ctx.quality.barCount
        val slot = w / n
        bgPath.rewind()
        var i = 0
        while (i < n) {
            val v = frame.spectrum.getOrElse(i) { 0f }
            val bh = v * h * 0.14f
            val x0 = i * slot
            // T1.6.2（§四 G15）：android addRect 四 float 重载，零 Rect 分配
            bgPath.asAndroidPath().addRect(
                x0, h - bh, x0 + slot * 0.6f, h, android.graphics.Path.Direction.CCW
            )
            i++
        }
        drawPath(bgPath, accent, alpha = BG_ALPHA)

        // ── §B2-② 冲击波相位推进（每帧无条件推进；`beat` 时复位 ⇒ 从新的爆炸点重新扩散）──
        ringPhase = (ringPhase + fx.dt / RING_SEC).coerceAtMost(1f)

        // ── 爆发：方向由低频最强柱决定 ──
        if (frame.beat) {
            var maxIdx = 0
            var maxV = 0f
            i = 0
            while (i < minOf(40, n)) {
                val v = frame.spectrum.getOrElse(i) { 0f }
                if (v > maxV) { maxV = v; maxIdx = i }
                i++
            }
            val a = maxIdx * 6.2831853f / n
            val ex = w / 2 + cos(a) * w * 0.25f
            val ey = h / 2 + sin(a) * h * 0.22f
            val cnt = (220 + frame.bass * 180).toInt().coerceAtMost(420)
            // 色相映射到用户指定色系：黄(60°)→蓝(195°)
            val hueBase = HUE_MIN + (maxIdx.toFloat() / minOf(40, n)) * HUE_SPAN
            p.spawnBurst(ex, ey, cnt, 9f + frame.bass * 22f, hueBase, 60f)
            lastBeatMs = frame.timeMs
            // §B2-② 冲击波与闪光都锚在**这次爆炸的中心**
            ringPhase = 0f
            ringX = ex
            ringY = ey
            flashX = ex
            flashY = ey
            burstArmed = true
        } else if (frame.timeMs - lastBeatMs > 6000L && lastBeatMs > 0L) {
            // 无鼓点曲目兜底：避免长时间完全空屏
            idlePhase += 1f
            if (idlePhase >= 60f) {
                idlePhase = 0f
                val ex = w * 0.5f
                val ey = h * 0.5f
                p.spawnBurst(ex, ey, 80, 10f, IDLE_HUE, 60f)
                ringPhase = 0f
                ringX = ex
                ringY = ey
                flashX = ex
                flashY = ey
                burstArmed = true
            }
        }

        p.update(
            speedScale = 1f + frame.treble * 2.5f,
            gravity = 0.18f,
            decay = 0.011f,
            drag = 0.98f
        )

        val d = p.data

        // ── §B2-① 拖尾：按 hue 分 8 桶合批 ──
        // ⛔ 每粒子一次 `drawLine` 会把 draw 数从 ~150 抬到 ~300（E14 本就在 §7.5 超限名单里）。
        // 合批后**恒 ≤ 8 次** `drawPath`（桶空则跳过）。
        var b = 0
        while (b < TRAIL_BUCKETS) {
            trailPaths[b].rewind()
            trailLifeSum[b] = 0f
            trailCount[b] = 0
            b++
        }
        i = 0
        while (i < p.count) {
            val o = i * ParticlePool.STRIDE
            val life = d[o + ParticlePool.LIFE]
            if (life > TRAIL_MIN_LIFE) {
                val x = d[o + ParticlePool.X]
                val y = d[o + ParticlePool.Y]
                val bk = trailBucket(d[o + ParticlePool.HUE])
                val tp = trailPaths[bk]
                tp.moveTo(x, y)
                tp.lineTo(
                    trailTailX(x, d[o + ParticlePool.VX]),
                    trailTailY(y, d[o + ParticlePool.VY])
                )
                trailLifeSum[bk] += life
                trailCount[bk]++
            }
            i++
        }
        b = 0
        while (b < TRAIL_BUCKETS) {
            val c = trailCount[b]
            if (c > 0) {
                drawPath(
                    path = trailPaths[b],
                    color = trailColors[b],
                    alpha = (trailLifeSum[b] / c) * TRAIL_ALPHA_K,
                    style = trailStroke
                )
            }
            b++
        }

        // ── §B2-② 冲击波环（1 次 drawCircle(style = Stroke)）──
        if (ringPhase < 1f) {
            val r = ringRadius(ringPhase, minDim)
            val ra = ringAlpha(ringPhase)
            if (r > 0.5f && ra > 0.01f) {
                drawCircle(
                    color = VisualizerMath.towardWhite(accent, 0.65f),
                    radius = r,
                    center = Offset(ringX, ringY),
                    alpha = ra,
                    style = ringStroke
                )
            }
        }

        // ── §B2-② 中心闪光（1 次 drawCircle(brush=)，径向白亮）──
        // 缓存的 `Brush` 中心固定在 `Offset.Zero`（⛔ 键里**不能**含每拍变化的爆炸中心），
        // 用 `translate(flashX, flashY)` 平移**画布** ⇒ 渐变跟着走。
        // 依据：E30 雷达的旋转扇正是靠 `withTransform { rotate(...) }` 让 `SweepGradient`
        // 真正跟着转 —— `Brush` 的着色器在**当前画布坐标系**里求值（`translate` 是 inline，零分配）。
        if (burstArmed) {
            val fa = frame.pulse * FLASH_ALPHA_K
            if (fa > 0.01f) {
                translate(flashX, flashY) {
                    drawCircle(
                        brush = Shading2D.shadeBrushCached(
                            key = (w.toRawBits().toLong() shl 32) xor h.toRawBits().toLong() xor
                                accent.toArgb().toLong() xor E14_FLASH_SALT,
                            center = Offset.Zero,
                            radius = minDim * FLASH_R_K,
                            base = VisualizerMath.towardWhite(accent, FLASH_WHITE),
                            contrast = FLASH_CONTRAST
                        ),
                        radius = minDim * FLASH_R_K,
                        center = Offset.Zero,
                        alpha = fa
                    )
                }
            }
        }

        // ── 粒子（保持原样：纯色圆点 + Plus 叠加；每粒子一次 drawCircle）──
        i = 0
        while (i < p.count) {
            val o = i * ParticlePool.STRIDE
            val life = d[o + ParticlePool.LIFE]
            drawCircle(
                color = VisualizerMath.hsl(d[o + ParticlePool.HUE], 1.0f, 0.68f),
                radius = 3.5f + life * 6.0f,
                center = Offset(d[o + ParticlePool.X], d[o + ParticlePool.Y]),
                alpha = life * 0.95f,
                blendMode = BlendMode.Plus
            )
            i++
        }
    }

    override fun onExitContent() {
        pool?.clear()
        pool = null
    }
}

// ═══════════════════════════════════════════════════════════════════
// E19 粒子文字（ULTRA）
// ═══════════════════════════════════════════════════════════════════

/**
 * E19 `PARTICLE_TEXT` — 粒子文字
 *
 * 把歌名渲染到离屏 Bitmap，采样非透明像素作为粒子目标点；
 * `energy` 高时粒子被吸向目标，`beat` 时炸散。
 *
 * ## §B5 十一条（T4.5）
 *
 * **性能（P）—— 全部为净降耗**
 * - **P1** 死亡改重生（`ParticlePool.updateAttract`）—— 修「≈4.2 s 后画面永久空白」与「目标错位」
 * - **P2** 350 × `drawCircle(Plus)` → **3 × `drawPath`**（`dotPath` 模板 + `addPath` 平移复用）。
 *   ⛔ **零 `Rect` 分配** —— Compose `Path.addOval` 只收 `Rect`，而 `Rect` 是 `data class`
 *   （**非** value class）⇒ 每帧 350 次堆分配（§四 G12）。
 * - **P3** 逐像素 `getPixel()` → **逐行 `getPixels()`**（JNI **16,280 → 220**）
 * - **P4** 照搬 E23 的**两遍 + 按行配额**采样（修「粒子只覆盖字形上部几行」）
 * - **P5** `step` 3 → 1（P3 之后采样已 < 0.5 ms ⇒ 近乎免费，全字形覆盖）
 *
 * **观感（Q）—— 全部零额外 draw / 分配**
 * - **Q1** `measureText` 自适应字号 + `Align.CENTER`
 * - **Q2** 按**墨迹包围盒**适配缩放并居中（任意长度标题都完整、大小一致）
 * - **Q3** 内 / 外圈双 `Path` 出纵深（按到墨迹中心的**椭圆归一化距离**分组）
 * - **Q4** `Plus` 只留给「内圈且 `pulse > 0.5`」的子集（修整片过曝死白）
 * - **Q5** 后处理（暗角 0.46 + 颗粒 0.030）
 * - **Q6** `sectionEnergy` 缓慢色温漂移（`AudioSmoother`，§7.3 参数 0.02 / 0.02）
 *
 * ## minSdk 22 兼容说明
 *
 * `ImageBitmap.readPixels()` 需 API 29、`Path.getSegment()` 需 API 24，二者在 minSdk 22 下均不可用；
 * ⚠️ 但这**不等于"只能用 `getPixel()`"** —— `Bitmap.getPixels(pixels, offset, stride, x, y, w, h)`
 * **自 API 1 就可用**（同项目 `backend/photo/YuNetFaceDetector.kt` 已在用），逐行批读即可把 JNI
 * 从"每像素一次"降到"每行一次"。采样仅在文本变化时执行一次，非每帧路径。
 *
 * ⚠️ 本效果是 `Tier.ULTRA` ⇒ 需要**画质档 = 高**（设置 → 可视化画质）才可见（T1.6.4 已接 UI）。
 */
class ParticleTextRenderer : RendererFx() {

    override val theme = VisualizerTheme.PARTICLE_TEXT

    // §B5-Q5 收尾后处理（暗角 + 颗粒）
    override val postFx = PostFx(vignette = 0.46f, grain = 0.030f)

    private var pool: ParticlePool? = null
    private var targets: FloatArray? = null
    /** 缩放到画布后的目标点（预分配，避免每帧分配） */
    private var scaled: FloatArray? = null
    private var lastCaption: String? = null
    private var sampled = false
    /** 采样到的有效点数 */
    private var sampledCount = 0

    // ── §B5-P3/P4 采样缓冲（首次采样时分配一次，此后复用 ⇒ 零分配）──
    /** 逐行批读缓冲（长度 ≥ [SAMPLE_W]） */
    private var rowBuf: IntArray? = null
    /** 每行有效像素计数（长度 ≥ 行数） */
    private var rowCount: IntArray? = null
    /** 每行配额（长度 ≥ 行数） */
    private var rowQuota: IntArray? = null

    // ── §B5-Q2 墨迹包围盒（位图空间；采样同一趟顺手记下，零额外成本）──
    private var inkMinX = 0f
    private var inkMaxX = 0f
    private var inkMinY = 0f
    private var inkMaxY = 0f

    /** §B5-Q6 段落色温（§7.3：`sectionEnergy` 的 attack / release 均为 0.02） */
    private val sectionSmooth = AudioSmoother(attack = SECTION_ATTACK, release = SECTION_RELEASE)

    /**
     * §B5-Q6 调色板缓存。
     * ⛔ `VisualizerMath.rgbToHsl` 会**分配一个 `Triple`** ⇒ 绝不能每帧调用
     * （同 E23 的 `P1#6` 教训）；只在 `palette.accent` 变化时重算一次
     * （`Color` 是 value class ⇒ `!=` 比较零分配）。
     */
    private var cachedAccent = Color.Unspecified
    private var cachedHue = 0f
    private var cachedSat = 0f
    private var cachedLit = 0f

    // ── §B5-P2/Q3/Q4 合批路径（成员持有 + 每帧 `rewind`，零分配）──
    /** 圆形子路径模板（中心在原点，用 `addPath(dotPath, Offset(x, y))` 平移到各粒子） */
    private val dotPath = Path()
    /** 外圈（`norm > INNER_K`） */
    private val outerPath = Path()
    /** 内圈（`norm <= INNER_K`） */
    private val innerPath = Path()
    /** 内圈高亮子集（`pulse > GLOW_PULSE`），走 `Plus` */
    private val glowPath = Path()
    /** 当前 `dotPath` 的半径（半径变了才重建模板） */
    private var dotRadius = -1f

    /**
     * §B5-Q2 最近一次的墨迹适配缩放（Q3 的椭圆分组基准要用 ⇒ 必须**跨 `if` 块存活**）。
     *
     * ⛔ 不能只在 `if (sampled && …)` 块内声明 —— Q3 的半宽/半高在块外算。
     * 未采样（`sampled == false`）时保持上一次的值（初值 `1f`）。
     */
    private var inkScale = 1f

    override fun onEnterContent(ctx: RenderContext) {
        val cap = ctx.quality.maxParticles
        pool = if (cap > 0) ParticlePool(cap, rng) else null
        targets = FloatArray(cap * 2)
        scaled = FloatArray(cap * 2)
        sampled = false
        sampledCount = 0
        lastCaption = null
        sectionSmooth.reset()
        cachedAccent = Color.Unspecified
        dotRadius = -1f
        inkScale = 1f
    }

    /**
     * 采样文字轮廓 → 目标点。**仅在文本变化时执行**（非每帧路径）。
     *
     * §B5-P3：逐行 `getPixels` 批读（JNI 次数 = 行数 = [SAMPLE_H]）；
     * §B5-P4：**两遍** —— 第一遍数每行有效像素 + 记墨迹包围盒，第二遍按行配额等距抽取。
     */
    private fun sample(text: String, poolCap: Int) {
        // T8 修复（2026-09-13）：提前 null check，避免 targets 为 null 时仍分配 Bitmap
        // （原 `val t = targets ?: return` 在 createBitmap 之后，形成必然泄漏路径）
        val t = targets ?: return
        val bmpW = SAMPLE_W
        val bmpH = SAMPLE_H
        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        try {
            val canvas = AndroidCanvas(bmp)
            val paint = AndroidPaint().apply {
                isAntiAlias = true
                color = android.graphics.Color.WHITE
            }
            // §B5-Q1：自适应字号（长标题不再被裁掉）+ 水平居中
            paint.textSize = TEXT_SIZE_MAX
            val wMax = (bmpW - TEXT_PAD * 2f).coerceAtLeast(1f)
            val wFull = paint.measureText(text).coerceAtLeast(1f)
            if (wFull > wMax) paint.textSize = (TEXT_SIZE_MAX * wMax / wFull).coerceAtLeast(12f)
            paint.textAlign = AndroidPaint.Align.CENTER
            val fm = paint.fontMetrics
            val baseline = (bmpH - (fm.bottom - fm.top)) * 0.5f - fm.top
            canvas.drawText(text, bmpW * 0.5f, baseline, paint)

            val step = SAMPLE_STEP
            val rows = (bmpH + step - 1) / step
            val buf = rowBuf?.takeIf { it.size >= bmpW } ?: IntArray(bmpW).also { rowBuf = it }
            val rc = rowCount?.takeIf { it.size >= rows } ?: IntArray(rows).also { rowCount = it }
            val quota = rowQuota?.takeIf { it.size >= rows } ?: IntArray(rows).also { rowQuota = it }

            // ── 第一遍：逐行批读，统计每行有效像素数 + 墨迹包围盒 ──
            var total = 0
            var minX = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var minY = Int.MAX_VALUE
            var maxY = Int.MIN_VALUE
            var ri = 0
            var y = 0
            while (y < bmpH) {
                bmp.getPixels(buf, 0, bmpW, 0, y, bmpW, 1)
                var cnt = 0
                var x = 0
                while (x < bmpW) {
                    if (buf[x] and 0xFF000000.toInt() != 0) {
                        cnt++
                        if (x < minX) minX = x
                        if (x > maxX) maxX = x
                    }
                    x += step
                }
                rc[ri] = cnt
                total += cnt
                if (cnt > 0) {
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
                ri++
                y += step
            }

            if (total <= 0) {
                sampledCount = 0
                sampled = false
                return
            }

            // ── 第二遍：按行占比配额，行内等距抽取（垂直方向完整覆盖，E23 同款）──
            for (k in 0 until rows) {
                quota[k] = if (rc[k] <= 0) 0
                else (rc[k].toLong() * poolCap / total).toInt().coerceAtLeast(1)
            }
            var n = 0
            var ri2 = 0
            var y2 = 0
            while (y2 < bmpH && n < poolCap) {
                val want = quota[ri2]
                if (rc[ri2] > 0 && want > 0) {
                    bmp.getPixels(buf, 0, bmpW, 0, y2, bmpW, 1)
                    val takeEvery = (rc[ri2].toFloat() / want).coerceAtLeast(1f)
                    var taken = 0
                    var x2 = 0
                    while (x2 < bmpW && n < poolCap) {
                        if (buf[x2] and 0xFF000000.toInt() != 0) {
                            taken++
                            if (((taken - 1).toFloat() % takeEvery) < 0.5f) {
                                t[n * 2] = x2.toFloat()
                                t[n * 2 + 1] = y2.toFloat()
                                n++
                            }
                        }
                        x2++
                    }
                }
                ri2++
                y2 += step
            }

            // 未填满时循环复用已有点位，避免粒子数量减半
            if (n > 0) {
                for (i in n until poolCap) {
                    t[i * 2] = t[(i % n) * 2]
                    t[i * 2 + 1] = t[(i % n) * 2 + 1]
                }
            }
            sampledCount = n
            sampled = n > 0
            // §B5-Q2 墨迹包围盒（第一趟顺手记下 ⇒ 零额外扫描）
            inkMinX = minX.toFloat()
            inkMaxX = maxX.toFloat()
            inkMinY = minY.toFloat()
            inkMaxY = maxY.toFloat()
        } finally {
            bmp.recycle()
        }
    }

    /**
     * §B5-P2 重建圆形子路径模板（**半径变化时才重建**）。
     *
     * ⛔ **不用 `Path.addOval(Rect(...))`** —— Compose 的 `addOval` 只收 `Rect`，而 `Rect` 是
     * `data class`（非 value class）⇒ 每帧 350 次堆分配。改为 `moveTo` + **4 段 `cubicTo`**
     * 手工近似圆（控制点系数 `4/3 · tan(π/8) ≈ 0.5522847`），**零分配**。
     */
    private fun ensureDot(r: Float) {
        if (r == dotRadius) return
        dotRadius = r
        val k = r * CIRCLE_K
        dotPath.rewind()
        dotPath.moveTo(r, 0f)
        dotPath.cubicTo(r, k, k, r, 0f, r)
        dotPath.cubicTo(-k, r, -r, k, -r, 0f)
        dotPath.cubicTo(-r, -k, -k, -r, 0f, -r)
        dotPath.cubicTo(k, -r, r, -k, r, 0f)
        dotPath.close()
    }

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        val p = pool ?: return
        val cap = p.capacity
        val caption = ctx.caption?.takeIf { it.isNotBlank() } ?: "NASMusicTV"

        if (caption != lastCaption) {
            lastCaption = caption
            sample(caption, cap)
            p.clear()
            for (i in 0 until cap) {
                // §B5-P1：初始寿命**错开** —— 否则 350 个粒子会在第 250 帧**同帧全灭**，
                // 一起跳回画布外缘 ⇒ 明显"闪一下"。
                p.spawn(
                    x = rng.next() * size.width,
                    y = rng.next() * size.height,
                    vx = 0f, vy = 0f,
                    life = SPAWN_LIFE_MIN + rng.next() * (1f - SPAWN_LIFE_MIN),
                    hue = 190f
                )
            }
        }

        val t = targets
        val sc = scaled
        if (sampled && t != null && sc != null) {
            // §B5-Q2：按**墨迹包围盒**适配（宽 78% / 高 50% 双约束取小）并居中
            // （复用预分配数组 ⇒ 零分配）
            val inkW = (inkMaxX - inkMinX).coerceAtLeast(1f)
            val inkH = (inkMaxY - inkMinY).coerceAtLeast(1f)
            val scale = kotlin.math.min(size.width * FIT_W_K / inkW, size.height * FIT_H_K / inkH)
            // ⛔ `scale` 是**块内**局部量，而 Q3 的分组基准（墨迹半宽/半高）在 `if` 块**外**算 ⇒
            //    必须存进成员，否则块外 `Unresolved reference 'scale'`（2026-09-30 编译实测）。
            inkScale = scale
            val offX = (size.width - inkW * scale) * 0.5f - inkMinX * scale
            val offY = (size.height - inkH * scale) * 0.5f - inkMinY * scale
            for (i in 0 until cap) {
                sc[i * 2] = t[i * 2] * scale + offX
                sc[i * 2 + 1] = t[i * 2 + 1] * scale + offY
            }
            p.updateAttract(
                sc,
                0.02f + frame.energy * 0.08f,
                frame.treble * 6f,
                size.width,
                size.height
            )

            if (frame.beat) {
                // 节拍炸散
                val d = p.data
                for (i in 0 until p.count) {
                    val o = i * ParticlePool.STRIDE
                    d[o + ParticlePool.VX] += rng.nextSigned() * 22f
                    d[o + ParticlePool.VY] += rng.nextSigned() * 22f
                }
            }
        } else {
            p.update(1f, 0f, 0.01f)
        }

        // ── §B5-Q6 段落色温：把 accent 的色相按段落能量缓慢漂移 ──
        val base = ctx.palette.accent
        if (base != cachedAccent) {
            cachedAccent = base
            val hsl = VisualizerMath.rgbToHsl(base)   // ⛔ 只在调色板变化时（每首歌一次）
            cachedHue = hsl.first
            cachedSat = hsl.second
            cachedLit = hsl.third
        }
        val section = sectionSmooth.update(frame.sectionEnergy)
        val accent = VisualizerMath.hsl(
            cachedHue + (section - 0.5f) * SECTION_HUE_SPAN,
            cachedSat,
            cachedLit
        )

        // ── §B5-P2/Q3/Q4：合批成 3 条 Path（350 次 drawCircle → 3 次 drawPath）──
        val radius = DOT_R_BASE + frame.pulse * DOT_R_PULSE
        ensureDot(radius)
        outerPath.rewind()
        innerPath.rewind()
        glowPath.rewind()

        // §B5-Q3 分组基准：以**墨迹半宽 / 半高**为轴的椭圆归一化距离（角点 ≈ 1）
        val halfW = (inkMaxX - inkMinX).coerceAtLeast(1f) * inkScale * 0.5f
        val halfH = (inkMaxY - inkMinY).coerceAtLeast(1f) * inkScale * 0.5f
        val cx = size.width * 0.5f
        val cy = size.height * 0.5f
        val invHalfW = 1f / halfW.coerceAtLeast(1f)
        val invHalfH = 1f / halfH.coerceAtLeast(1f)
        val glowOn = frame.pulse > GLOW_PULSE
        val d = p.data
        for (i in 0 until p.count) {
            val o = i * ParticlePool.STRIDE
            val px = d[o + ParticlePool.X]
            val py = d[o + ParticlePool.Y]
            val nx = (px - cx) * invHalfW
            val ny = (py - cy) * invHalfH
            val norm = kotlin.math.sqrt(nx * nx + ny * ny) * INV_SQRT2
            val off = Offset(px, py)
            if (norm <= INNER_K) {
                innerPath.addPath(dotPath, off)
                if (glowOn) glowPath.addPath(dotPath, off)
            } else {
                outerPath.addPath(dotPath, off)
            }
        }
        val baseAlpha = 0.35f + frame.energy * 0.5f
        // 外圈：压暗 + 降 alpha ⇒ 边缘沉下去
        drawPath(
            outerPath,
            VisualizerMath.darken(accent, OUTER_DARKEN),
            alpha = (baseAlpha - ALPHA_SHIFT).coerceIn(0f, 1f)
        )
        // 内圈：提亮 + 升 alpha ⇒ 中心浮起来
        drawPath(
            innerPath,
            VisualizerMath.towardWhite(accent, INNER_WHITEN),
            alpha = (baseAlpha + ALPHA_SHIFT).coerceIn(0f, 1f)
        )
        // §B5-Q4：只有"内圈 + 强脉冲"的粒子走 Plus ⇒ 亮部仍有辉光，但不再整片死白
        if (glowOn) {
            drawPath(glowPath, accent, alpha = GLOW_ALPHA, blendMode = BlendMode.Plus)
        }
    }

    override fun onExitContent() {
        pool?.clear()
        pool = null
        targets = null
        scaled = null
        sampled = false
    }

    internal companion object {
        /** §B5-P3 采样位图尺寸（原实现 `size*3 × size`，`size = 220`） */
        const val SAMPLE_W = 660
        const val SAMPLE_H = 220

        /**
         * §B5-P5 采样步长。
         * P3 之后采样已 < 0.5 ms ⇒ 降到 `1`（全字形覆盖），而 `poolCap` 的"填满即停"逻辑保留
         * ⇒ **候选点 ×9，但为凑满 350 点所需扫描的位置数基本不变** ⇒ 近乎免费。
         */
        const val SAMPLE_STEP = 1

        /** §B5-Q1 自适应字号上限与左右留白 */
        const val TEXT_SIZE_MAX = 150f
        const val TEXT_PAD = 20f

        /** §B5-Q2 目标点适配画布的比例（宽 / 高，双约束取小） */
        const val FIT_W_K = 0.78f
        const val FIT_H_K = 0.50f

        /**
         * §B5-Q3 内 / 外圈分界：**椭圆归一化距离**（以墨迹半宽 / 半高为轴，角点 ≈ 1）。
         * `0.42` ⇒ 内圈椭圆半轴 ≈ 0.594 × 墨迹半宽/半高。
         */
        const val INNER_K = 0.42f

        /** `1 / √2`：把"角点为 1"的椭圆半径压到 `[0, 1]` */
        const val INV_SQRT2 = 0.70710678f

        /** §B5-Q3 内圈提亮 / 外圈压暗 */
        const val INNER_WHITEN = 0.25f
        const val OUTER_DARKEN = 0.38f

        /** §B5-Q3 内 / 外圈的 alpha 偏移（相对基准 alpha） */
        const val ALPHA_SHIFT = 0.10f

        /** §B5-Q4 高亮子集（内圈）的 `pulse` 门限与 alpha */
        const val GLOW_PULSE = 0.5f
        const val GLOW_ALPHA = 0.55f

        /** §B5-Q6 段落色温：色相漂移**半幅**（度）与 §7.3 规定的平滑系数 */
        const val SECTION_HUE_SPAN = 40f
        const val SECTION_ATTACK = 0.02f
        const val SECTION_RELEASE = 0.02f

        /** §B5-P2 粒子半径（原文 `2.4 + pulse * 2.6`） */
        const val DOT_R_BASE = 2.4f
        const val DOT_R_PULSE = 2.6f

        /** §B5-P1 初始寿命下限（错开死亡时刻，避免 350 个粒子同帧全灭） */
        const val SPAWN_LIFE_MIN = 0.35f

        /** 4 段三次贝塞尔近似圆的控制点系数 `4/3 · tan(π/8)` */
        const val CIRCLE_K = 0.5522847f
    }
}
