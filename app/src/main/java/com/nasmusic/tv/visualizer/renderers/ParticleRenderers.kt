package com.nasmusic.tv.visualizer.renderers

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.toArgb
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.ParticlePool
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerMath
import com.nasmusic.tv.visualizer.VisualizerRandom
import com.nasmusic.tv.visualizer.VisualizerRenderer
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
 * **minSdk 22 兼容说明**：
 * `ImageBitmap.readPixels()` 需 API 29、`Path.getSegment()` 需 API 24，二者在 minSdk 22 下均不可用；
 * ⚠️ 但这**不等于"只能用 `getPixel()`"** —— `Bitmap.getPixels(pixels, offset, stride, x, y, w, h)`
 * **自 API 1 就可用**（同项目 `backend/photo/YuNetFaceDetector.kt` 已在用），逐行批读即可把 JNI
 * 从"每像素一次"降到"每行一次"。当前用逐像素 `getPixel()` 全图扫描（约 16,280 次 JNI / 8–20 ms，
 * 且发生在 `draw` 内）是**待优化的热点**，见 `docs/visualizer-texture-upgrade-plan.md` §G11 / §B5。
 * 采样仅在进入/文本变化时执行一次，未完成前先渲染普通粒子。
 */
class ParticleTextRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.PARTICLE_TEXT

    /** P1#5：本渲染器独立的随机源 */
    private val rng = VisualizerRandom()

    private var pool: ParticlePool? = null
    private var targets: FloatArray? = null
    /** 缩放到画布后的目标点（预分配，避免每帧分配） */
    private var scaled: FloatArray? = null
    private var lastCaption: String? = null
    private var sampled = false
    /** 采样到的有效点数 */
    private var sampledCount = 0

    override fun onEnter(ctx: RenderContext) {
        val cap = ctx.quality.maxParticles
        pool = if (cap > 0) ParticlePool(cap, rng) else null
        targets = FloatArray(cap * 2)
        scaled = FloatArray(cap * 2)
        sampled = false
        sampledCount = 0
        lastCaption = null
    }

    /** 采样文字轮廓 → 目标点。仅在文本变化时执行 */
    private fun sample(text: String, poolCap: Int) {
        // T8 修复（2026-09-13）：提前 null check，避免 targets 为 null 时仍分配 Bitmap
        // （原 `val t = targets ?: return` 在 createBitmap 之后，形成必然泄漏路径）
        val t = targets ?: return
        val size = 220
        val bmp = Bitmap.createBitmap(size * 3, size, Bitmap.Config.ARGB_8888)
        try {
            val canvas = AndroidCanvas(bmp)
            val paint = AndroidPaint().apply {
                isAntiAlias = true
                textSize = 150f
                color = android.graphics.Color.WHITE
            }
            canvas.drawText(text, 20f, size * 0.78f, paint)

            var n = 0
            val step = 3
            for (y in 0 until size step step) {
                for (x in 0 until bmp.width step step) {
                    if (n >= poolCap) break
                    if (bmp.getPixel(x, y) and 0xFF000000.toInt() != 0) {
                        t[n * 2] = x.toFloat()
                        t[n * 2 + 1] = y.toFloat()
                        n++
                    }
                }
                if (n >= poolCap) break
            }
            // 未填满时循环复用已有点位,避免粒子数量减半
            if (n > 0) {
                for (i in n until poolCap) {
                    t[i * 2] = t[(i % n) * 2]
                    t[i * 2 + 1] = t[(i % n) * 2 + 1]
                }
            }
            sampledCount = n
            sampled = n > 0
        } finally {
            bmp.recycle()
        }
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val p = pool ?: return
        val cap = p.capacity
        val caption = ctx.caption?.takeIf { it.isNotBlank() } ?: "NASMusicTV"

        if (caption != lastCaption) {
            lastCaption = caption
            sample(caption, cap)
// 补齐粒子
            p.clear()
            for (i in 0 until cap) {
                p.spawn(
                    x = rng.next() * size.width,
                    y = rng.next() * size.height,
                    vx = 0f, vy = 0f, life = 1f, hue = 190f
                )
            }
        }

        val t = targets
        val sc = scaled
        if (sampled && t != null && sc != null) {
            // 缩放到画布：文字居中（复用预分配数组，零分配）
            val scale = (size.width * 0.78f) / (220f * 3f)
            val offX = size.width * 0.11f
            val offY = size.height * 0.5f - 220f * scale * 0.5f
            for (i in 0 until cap) {
                sc[i * 2] = t[i * 2] * scale + offX
                sc[i * 2 + 1] = t[i * 2 + 1] * scale + offY
            }
            p.updateAttract(sc, 0.02f + frame.energy * 0.08f, frame.treble * 6f)

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

val accent = ctx.palette.accent
        val d = p.data
        for (i in 0 until p.count) {
            val o = i * ParticlePool.STRIDE
            drawCircle(
                color = accent,
                radius = 2.4f + frame.pulse * 2.6f,
                center = Offset(d[o + ParticlePool.X], d[o + ParticlePool.Y]),
                alpha = 0.35f + frame.energy * 0.5f,
                blendMode = BlendMode.Plus
            )
        }
    }

    override fun onExit() {
        pool?.clear(); pool = null; targets = null; sampled = false
    }
}
