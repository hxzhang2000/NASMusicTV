package com.nasmusic.tv.visualizer.renderers

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.ParticlePool
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerMath
import com.nasmusic.tv.visualizer.VisualizerRenderer

// ═══════════════════════════════════════════════════════════════════
// E08 粒子风暴
// ═══════════════════════════════════════════════════════════════════

/**
 * E08 `PARTICLE_STORM` — 粒子风暴
 *
 * 底部发射器，低频超阈值时批量喷发；粒子受重力、带拖尾。
 * 观感：瀑布/喷泉。
 */
class ParticleStormRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.PARTICLE_STORM

    private var pool: ParticlePool? = null

    override fun onEnter(ctx: RenderContext) {
        val cap = ctx.quality.maxParticles
        pool = if (cap > 0) ParticlePool(cap) else null
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val p = pool ?: return
        val w = size.width
        val h = size.height
val accent = ctx.palette.accent
        val hueBase = 195f

// 发射：低频超阈值 + 节拍爆发
        val emitCount = (frame.energy * 6f + frame.bass * 8f).toInt()
        repeat(emitCount.coerceAtMost(20)) {
            p.spawn(
                x = VisualizerMath.nextRandom() * w,
                y = h * 0.92f,
                vx = VisualizerMath.nextRandomSigned() * 3.2f,
                vy = -(4.5f + frame.bass * 16f) * (0.5f + VisualizerMath.nextRandom()),
                life = 1f,
                hue = hueBase + VisualizerMath.nextRandomSigned() * 40f
            )
        }
        if (frame.beat) {
            p.spawnBurst(w / 2, h * 0.85f, 60, 14f + frame.bass * 18f, hueBase, 60f)
        }

        p.update(
            speedScale = 1f + frame.treble * 2f,
            gravity = 0.26f + frame.bass * 0.12f,
            decay = 0.010f,
            drag = 0.995f
        )

        // 绘制：叠加发光
        val d = p.data
        for (i in 0 until p.count) {
            val o = i * ParticlePool.STRIDE
            val life = d[o + ParticlePool.LIFE]
drawCircle(
                color = accent,
                radius = 3f + life * 5.5f,
                center = Offset(d[o + ParticlePool.X], d[o + ParticlePool.Y]),
                alpha = life * 0.85f,
                blendMode = BlendMode.Plus
            )
        }
    }

    override fun onExit() { pool?.clear(); pool = null }
}

// ═══════════════════════════════════════════════════════════════════
// E09 粒子银河
// ═══════════════════════════════════════════════════════════════════

/**
 * E09 `PARTICLE_GALAXY` — 粒子银河
 *
 * 中心径向发射 + 螺旋初速 + `BlendMode.Plus` 叠加发光。
 * 与 E08 区别：E08 是底部+重力（瀑布感），E09 是中心+螺旋（星系感）。
 */
class ParticleGalaxyRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.PARTICLE_GALAXY

    private var pool: ParticlePool? = null
    private var phase = 0f

    override fun onEnter(ctx: RenderContext) {
        val cap = ctx.quality.maxParticles
        pool = if (cap > 0) ParticlePool(cap) else null
        phase = 0f
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val p = pool ?: return
        val cx = size.width / 2
        val cy = size.height / 2
        val accent = ctx.palette.accent
        phase += 0.02f + frame.bpm / 6000f

// 发射：数量由总能量决定
        val count = (frame.energy * 20f).toInt().coerceAtMost(12)
        repeat(count) {
            val a = VisualizerMath.nextRandom() * 6.2831853f + phase
            val sp = 1.8f + VisualizerMath.nextRandom() * 4f
            p.spawnRadial(cx, cy, a, sp, 1f, 195f + VisualizerMath.nextRandomSigned() * 80f, swirl = sp * 0.5f)
        }
        if (frame.beat) {
            p.spawnBurst(cx, cy, (200 * 0.7f).toInt(), 13f + frame.bass * 16f, 195f, 60f)
        }

        p.update(
            speedScale = 1f + frame.treble * 3f,
            gravity = frame.bass * 0.2f - 0.1f,      // 低频向心引力
            decay = 0.012f,
            drag = 0.985f
        )

        val d = p.data
        for (i in 0 until p.count) {
            val o = i * ParticlePool.STRIDE
            val life = d[o + ParticlePool.LIFE]
            val hue = d[o + ParticlePool.HUE]
            drawCircle(
                color = VisualizerMath.hsl(hue, 1.0f, 0.68f),
                radius = 2.5f + life * 4.5f + frame.bass * 3f,
                center = Offset(d[o + ParticlePool.X], d[o + ParticlePool.Y]),
                alpha = life * 0.9f,
                blendMode = BlendMode.Plus
            )
        }
        // 星系核心
        drawCircle(accent, ctx.minDim * 0.05f * (1f + frame.energy * 0.5f),
            Offset(cx, cy), alpha = 0.45f + frame.pulse * 0.4f, blendMode = BlendMode.Plus)
    }

    override fun onExit() { pool?.clear(); pool = null }
}

// ═══════════════════════════════════════════════════════════════════
// E14 节拍烟花
// ═══════════════════════════════════════════════════════════════════

/**
 * E14 `BEAT_FIREWORK` — 节拍烟花
 *
 * **刻意"留白"**：安静时近乎空屏，鼓点一到炸开满屏。
 * 静动反差是冲击力最强的手法，且平时极省电。
 */
class BeatFireworkRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.BEAT_FIREWORK

    private var pool: ParticlePool? = null
    private var lastBeatMs = 0L
    private var idlePhase = 0f

    // 常态底部频谱底纹：同 accent 同 alpha，合并为单 Path（替代 ~128 次独立 drawRect）
    private val bgPath = Path()

    override fun onEnter(ctx: RenderContext) {
        val cap = ctx.quality.maxParticles
        pool = if (cap > 0) ParticlePool(cap) else null
        lastBeatMs = 0L
        idlePhase = 0f
    }

    override fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext) {
        val p = pool ?: return
        val w = size.width
        val h = size.height
        val accent = ctx.palette.accent

        // 常态：极暗频谱底纹（留白）—— 合并为单 Path 一次绘制
        val n = ctx.quality.barCount
        val slot = w / n
        bgPath.reset()
        for (i in 0 until n) {
            val v = frame.spectrum.getOrElse(i) { 0f }
            val bh = v * h * 0.14f
            val x0 = i * slot
            val x1 = x0 + slot * 0.6f
            bgPath.addRect(Rect(x0, h - bh, x1, h))
        }
        drawPath(bgPath, accent, alpha = 0.12f)

        // 爆发：方向由低频最强柱决定
        if (frame.beat) {
            var maxIdx = 0
            var maxV = 0f
            for (i in 0 until minOf(40, n)) {
                val v = frame.spectrum.getOrElse(i) { 0f }
                if (v > maxV) { maxV = v; maxIdx = i }
            }
            val a = maxIdx * 6.2831853f / n
            val ex = w / 2 + kotlin.math.cos(a) * w * 0.25f
            val ey = h / 2 + kotlin.math.sin(a) * h * 0.22f
            val cnt = (220 + frame.bass * 180).toInt().coerceAtMost(420)
            // 色相映射到用户指定色系：黄(60°)→蓝(195°)
            val hueBase = 60f + (maxIdx.toFloat() / minOf(40, n)) * 135f
            p.spawnBurst(ex, ey, cnt, 9f + frame.bass * 22f, hueBase, 60f)
            lastBeatMs = frame.timeMs
        } else if (frame.timeMs - lastBeatMs > 6000L && lastBeatMs > 0L) {
            // 无鼓点曲目兜底：避免长时间完全空屏
            idlePhase += 1f
            if (idlePhase >= 60f) {
                idlePhase = 0f
                p.spawnBurst(w * 0.5f, h * 0.5f, 80, 10f, 120f, 60f)
            }
        }

        p.update(
            speedScale = 1f + frame.treble * 2.5f,
            gravity = 0.18f,
            decay = 0.011f,
            drag = 0.98f
        )

        val d = p.data
        for (i in 0 until p.count) {
            val o = i * ParticlePool.STRIDE
            val life = d[o + ParticlePool.LIFE]
            val hue = d[o + ParticlePool.HUE]
            drawCircle(
                color = VisualizerMath.hsl(hue, 1.0f, 0.68f),
                radius = 3.5f + life * 6.0f,
                center = Offset(d[o + ParticlePool.X], d[o + ParticlePool.Y]),
                alpha = life * 0.95f,
                blendMode = BlendMode.Plus
            )
        }
    }

    override fun onExit() { pool?.clear(); pool = null }
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
 * `ImageBitmap.readPixels()` 需 API 29、`Path.getSegment()` 需 API 24，
 * 二者在 minSdk 22 下均不可用 → 只能退回 `Bitmap.getPixel()` 全图扫描。
 * 采样仅在进入/文本变化时执行一次（约 5–15ms），未完成前先渲染普通粒子。
 */
class ParticleTextRenderer : VisualizerRenderer {

    override val theme = VisualizerTheme.PARTICLE_TEXT

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
        pool = if (cap > 0) ParticlePool(cap) else null
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
                    x = VisualizerMath.nextRandom() * size.width,
                    y = VisualizerMath.nextRandom() * size.height,
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
                    d[o + ParticlePool.VX] += VisualizerMath.nextRandomSigned() * 22f
                    d[o + ParticlePool.VY] += VisualizerMath.nextRandomSigned() * 22f
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
