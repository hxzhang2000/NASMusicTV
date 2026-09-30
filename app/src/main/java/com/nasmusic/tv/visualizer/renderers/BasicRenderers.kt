package com.nasmusic.tv.visualizer.renderers

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntSize
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerMath
import com.nasmusic.tv.visualizer.fx.FxLevel
import com.nasmusic.tv.visualizer.fx.ProceduralTexture
import com.nasmusic.tv.visualizer.fx.Shading2D
import kotlin.math.cos
import kotlin.math.sin

// ═══════════════════════════════════════════════════════════════════
// E03 隧道穿越
// ═══════════════════════════════════════════════════════════════════

/**
 * E03 `TUNNEL_FLY` — 隧道穿越
 *
 * 同心环沿 Z 轴迎面飞来。WebGL 霓虹隧道的 2D 平替，性价比最高。
 *
 * ⛔ 已迁移到 [RendererFx] 基类（§5.5 S1.5 试点）：`postFx` 默认 `NONE`
 * ⇒ 迁移逐像素不变；只实现 `onEnterContent` / `drawContent`。
 */
class TunnelRenderer : RendererFx() {

    override val theme = VisualizerTheme.TUNNEL_FLY

    // §A1-6：观感收尾 —— vignette(0.46) + grain(0.030)（§7.1「精致/电影感」区间上半段）
    override val postFx = PostFx(vignette = 0.46f, grain = 0.030f)

    private var offset = 0f
    // §A1-2：3 桶 → 6 桶（远端真正淡出），替代最多 ~192 次独立 drawCircle
    private val dotPaths = Array(6) { Path() }
    // §A1-3：壁灯光晕 = 同几何 2.1× 放大的低 alpha 点组。
    // ⚠️ 偏差记录（§12.4）：§A1 字面写「每点 1 次 shadeBrush」——但 Brush 的 center
    // 编码在实例内，位置逐点不同 ⇒ 每帧 192 次 Brush 分配，违反零分配红线；
    // 「放大实心点」是单 alpha 无台阶层，视觉等效点周微光、零新增分配。
    // shadeBrush 的字面实现用在位置固定的 §A1-5 中心光源上。
    private val glowPaths = Array(6) { Path() }

    override fun onEnterContent(ctx: RenderContext) { offset = 0f }

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        offset += 1.2f + frame.bass * 6f
        if (frame.beat) offset += 8f

        val cx = size.width / 2
        val cy = size.height / 2
        val baseR = ctx.minDim * 0.36f
        val maxZ = RINGS * 60f
        val accent = ctx.palette.accent

        // §A1-5：隧道尽头的光 —— shadeBrushCached 按 (w,h,accent) 三维键缓存
        //（⛔ Brush 依赖 center/radius/base ⇒ 键维度必须全覆盖，§15.4-A4）；
        // 颜色随 pulse 的呼吸用 alpha 表达（Brush 本体不动）。
        val light = Shading2D.shadeBrushCached(
            key = (size.width.toRawBits().toLong() shl 32) xor
                size.height.toRawBits().toLong() xor accent.toArgb().toLong() xor E03_KEY_SALT,
            center = Offset(cx, cy),
            radius = size.minDimension * 0.30f,
            base = VisualizerMath.towardWhite(accent, 0.55f),
        )
        drawCircle(
            brush = light,
            radius = size.minDimension * 0.30f,
            center = Offset(cx, cy),
            alpha = 0.14f + frame.pulse * 0.10f,
        )

        // §A1-1：环纵深色 —— 近端提亮 / 远端入雾（lerp 零分配）
        val nearCol = VisualizerMath.towardWhite(accent, 0.20f)
        val farCol = VisualizerMath.darken(accent, 0.15f)

        for (p in dotPaths) p.reset()
        for (p in glowPaths) p.reset()
        val dotColor = VisualizerMath.towardWhite(accent, 0.4f + frame.pulse * 0.4f)
        for (i in 0 until RINGS) {
            var z = (i * 60f + offset) % maxZ
            if (z < 1f) z = 1f
            val s = VisualizerMath.scaleAt(z, 320f)
            val fade = 1f - z / maxZ
            if (fade <= 0.02f) continue

            val r = baseR * s
            drawCircle(
                color = lerp(farCol, nearCol, fade),
                radius = r,
                center = Offset(cx, cy),
                alpha = fade * (0.5f + frame.treble * 0.5f),
                // §A1-1：远端环宽收细到 1px，形成"雾化"（近端维持 3px 主体）
                style = Stroke(width = if (fade < 0.35f) 1f else (3f * s).coerceIn(0.6f, 4f))
            )
            // 频谱驱动的隧道壁起伏
            if (i % 3 == 0) {
                val n = 24
                for (k in 0 until n) {
                    val a = k * 6.2831853f / n
                    val v = frame.spectrum.getOrElse((k * 2) % frame.spectrum.size) { 0f }
                    val rr = r * (1f + v * 0.18f)
                    val px = cx + cos(a) * rr
                    val py = cy + sin(a) * rr
                    val rDot = 3f * s + v * 3f
                    // T1.6.2（§四 G15）：float addOval 零 Rect 分配（真圆不变）
                    val b = (fade * 6f).toInt().coerceIn(0, 5)
                    dotPaths[b].asAndroidPath().addOval(
                        px - rDot, py - rDot, px + rDot, py + rDot,
                        android.graphics.Path.Direction.CCW)
                    // §A1-3：光晕点（2.1×，压在实心点下层）
                    val rGlow = rDot * 2.1f
                    glowPaths[b].asAndroidPath().addOval(
                        px - rGlow, py - rGlow, px + rGlow, py + rGlow,
                        android.graphics.Path.Direction.CCW)
                }
            }
        }
        // §A1-2：6 桶中心 alpha 用 fade² 近似（远端真淡出；原 3 桶线性远端与近端一样实）
        // 恢复原始的 SrcOver：合并前是逐点 drawCircle，没有 Plus 叠加（Plus 会让重叠处过曝）
        for (b in 0 until 6) {
            val a = (b + 0.5f) / 6f
            drawPath(glowPaths[b], dotColor, alpha = a * a * 0.30f)
            drawPath(dotPaths[b], dotColor, alpha = a * a * 0.7f)
        }

        // §A1-4：速度线 —— 8 条固定方向径向短线（不随环转 ⇒ 气流参照系稳定），长度随 bass。
        // 新增 draw 且观感增量小 ⇒ LOW/OFF 档跳过（§7.1 预算纪律）
        if (fx.level != FxLevel.OFF) {
            val flowA = 0.06f + frame.bass * 0.10f
            val flowLen = baseR * (0.10f + frame.bass * 0.14f)
            val r0 = baseR * 0.55f
            for (k in 0 until 8) {
                val a = k * 6.2831853f / 8f + 0.3927f
                drawLine(
                    nearCol,
                    Offset(cx + cos(a) * r0, cy + sin(a) * r0),
                    Offset(cx + cos(a) * (r0 + flowLen), cy + sin(a) * (r0 + flowLen)),
                    strokeWidth = 1.2f,
                    alpha = flowA,
                )
            }
        }
    }

    private companion object {
        const val RINGS = 24

        /**
         * 与其它渲染器的 `shadeBrushCached` 键区分（§四 G4「缓存键维度 ⊇ 依赖维度」/ §12.4）。
         *
         * ⛔ `Shading2D` 是 Kotlin **object** ⇒ 它内部的 Brush 缓存是**进程级共享**的。
         * E03 / E07 / E13 原先都用 `(w, h, accent)` 三元键 ⇒ **撞键**：后画的渲染器会拿到
         * 先画者的 `Brush`，而 `Brush` 的 `center` / `radius` / `base` / `contrast` 全在实例内
         * ⇒ **切换效果后隧道尽头的光会复用别人的半径与基色**（E15 早已因此加了 `E15_KEY_SALT`，
         * 本次把漏掉的三处补齐）。
         */
        const val E03_KEY_SALT = 0x03030303L
    }
}

// ═══════════════════════════════════════════════════════════════════
// E05 圆形频谱环（默认主题）
// ═══════════════════════════════════════════════════════════════════

/**
 * E05 `CIRCULAR_RING` — 圆形频谱环（默认主题）
 *
* 中心霓虹圆盘 → 频谱条紧贴圆盘**向外辐射** → 外围细线环 → **长条刺破外圈**。
 * 「约束 + 突破」的张力：常态被环收住，鼓点一来穿刺而出。
 */
class CircularRingRenderer : RendererFx() {

    override val theme = VisualizerTheme.CIRCULAR_RING

    // §A2-6：收尾后处理（§13.5-D9：暗角 ≥ 0.42 下限，取 0.44）
    override val postFx = PostFx(vignette = 0.44f, grain = 0.028f)

    private var rotation = 0f
    private var peaks = FloatArray(64)
    // 峰帽按 hue 分 8 桶合并为 Path，替代 ~64 次 drawCircle。
    // 桶数越多色差越小：8 桶时与所在条的 hue 最多差约 22.5°/2，肉眼基本无色阶断层
    private val peakPaths = Array(8) { Path() }
    // §A2-5：仅 hue 最高档（蓝端）的峰帽有辉光 —— 单独 1 条 Path（单色可行；8 桶全加
    // 则 8 个 alpha 无法共用一条 Path，另开 8 条又超预算 —— §A2 选项 ②，draw 8 → 9）
    private val peakGlowPath = Path()
    // §A2-3：sweep 渐变环 Brush 按 (w,h) 缓存（sweepGradient 构造分配 List，⛔ 不可每帧）
    private val ringBrushCache = SizeCache()
    // §A2-4：中心盘径向渐变 Brush 按 (w,h,量化hue) 缓存 —— hue 连续值会打爆缓存，
    // 量化到 12 档（相邻档差 30°，能量渐变时逐帧跨档 ≈ 平滑；同档内零分配）
    /** sweep 环 Brush 的构造器（构造期捕获常量 ⇒ get() 调用点零 lambda 分配，§15.4-A4） */
    private val ringBrushBuilder: (Float, Float) -> Brush = { bw, bh ->
        val stops = Array(9) { k: Int ->
            val tt = k / 8f
            val hh = VisualizerMath.hueGradient(60f, 195f, tt)
            tt to VisualizerMath.hsl(hh, 1.0f, 0.78f)
        }
        Brush.sweepGradient(*stops, center = Offset(bw / 2f, bh / 2f))
    }

    override fun onEnterContent(ctx: RenderContext) {
        rotation = 0f
        if (peaks.size < ctx.quality.barCount) peaks = FloatArray(ctx.quality.barCount)
        peaks.fill(0f)
    }

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        rotation += 0.15f + frame.bass * 0.9f
        if (frame.beat) rotation += 2.5f

        val D = ctx.minDim
        val w = size.width
        val h = size.height
        val cx = size.width / 2
        val cy = size.height / 2
        // 基准半径（不含节拍缩放）——整体放大 1.2×
        val rCover = D * 0.16f
        val rStart = rCover + D * 0.008f
        val rRing = D * 0.36f
        val maxLen = (rRing - rStart) * 1.5f      // 能量 0.67 时触环，>0.67 刺破
        val scale = 1f + frame.pulse * 0.08f

        val n = ctx.quality.barCount
        for (p in peakPaths) p.reset()
        peakGlowPath.reset()

        // 用户指定色系：hue 随角度流动：黄(60°)→绿(90°)→蓝(195°)，加白色高光
        val baseHue0 = 60f
        val baseHue1 = 195f
        val sat = 1.0f
        val lit = 0.55f + frame.pulse * 0.30f

        // ① 频谱条（画在环之下）—— §A2-1 圆柱化 + §A2-2 假光晕删除
        for (i in 0 until n) {
            val a = VisualizerMath.rad(i * 360f / n + rotation)
            val v = frame.spectrum.getOrElse(i) { 0f }
            val len = VisualizerMath.barHeight(v, maxLen, 3f) * scale
            val inner = VisualizerMath.polar(cx, cy, rStart, a)
            val outer = VisualizerMath.polar(cx, cy, rStart + len, a)
            val cosA = cos(VisualizerMath.rad(i * 360f / n + rotation))
            val sinA = sin(VisualizerMath.rad(i * 360f / n + rotation))
            val wdt = 3f + frame.bass * 3f

            // 本根条的角度占比 → 渐变 hue；亮度随音量微调
            val t = (i.toFloat() / n).coerceIn(0f, 1f)
            val hue = VisualizerMath.hueGradient(baseHue0, baseHue1, t)
            val barColor = VisualizerMath.hsl(hue, sat, lit)
            val highlight = VisualizerMath.hsl(hue, sat, minOf(lit + 0.25f, 1f))
            // §A2-1：圆柱 = 沿切向错开 ±0.35×wdt 的暗侧/亮侧两笔（G2①「沿法线明暗」的 2-draw 等价）
            val tx = -sinA
            val ty = cosA
            val toff = wdt * 0.35f
            val darkCol = VisualizerMath.hsl(hue, sat, (lit - 0.22f).coerceAtLeast(0.04f))
            val litCol = VisualizerMath.hsl(hue, sat, minOf(lit + 0.20f, 1f))

            val outerR = rStart + len
            val barEnd = if (outerR > rRing) {
                val t2 = ((rRing - rStart) / len).coerceIn(0f, 1f)
                VisualizerMath.polar(cx, cy, rStart + len * t2, a)
            } else outer
            // §A2-2：原 glowLayers 循环（同色加宽描边 ×N，G5 假光晕）整段删除 ⇒
            // 换 1 次宽笔低 alpha 光晕；⛔ LOW 档不画（省 draw）
            if (fx.level != FxLevel.OFF) {
                drawLine(barColor, inner, barEnd, wdt * 2.6f, StrokeCap.Round,
                    alpha = 0.10f + frame.pulse * 0.18f)
            }
            drawLine(darkCol,
                Offset(inner.x - tx * toff, inner.y - ty * toff),
                Offset(barEnd.x - tx * toff, barEnd.y - ty * toff),
                wdt, StrokeCap.Round, alpha = 0.9f)
            drawLine(litCol,
                Offset(inner.x + tx * toff, inner.y + ty * toff),
                Offset(barEnd.x + tx * toff, barEnd.y + ty * toff),
                wdt, StrokeCap.Round, alpha = 1f)
            if (outerR > rRing) {
                // 刺破段保留（更细更亮，锐利如针）
                val split = barEnd
                drawLine(highlight, split, outer, wdt * 0.6f, StrokeCap.Round, alpha = 1f)
            }

            // 峰值帽（极坐标版）——近似所在条的颜色，按 hue 分 8 桶合并为 Path
            peaks[i] = if (v >= peaks[i]) v else maxOf(v, peaks[i] * 0.985f - 0.004f)
            val pr = rStart + VisualizerMath.barHeight(peaks[i], maxLen, 3f) * scale
            val bucket = ((t * 8).toInt()).coerceIn(0, 7)
            // T1.6.2（§四 G15）：float addOval 零 Rect 分配（真圆不变）
            peakPaths[bucket].asAndroidPath().addOval(
                cx + cosA * pr - 2.5f, cy + sinA * pr - 2.5f,
                cx + cosA * pr + 2.5f, cy + sinA * pr + 2.5f,
                android.graphics.Path.Direction.CCW)
            if (bucket == 7) {
                // §A2-5：最高档峰帽的辉光（同几何 5px 半径，低 alpha 单独一层）
                peakGlowPath.asAndroidPath().addOval(
                    cx + cosA * pr - 6f, cy + sinA * pr - 6f,
                    cx + cosA * pr + 6f, cy + sinA * pr + 6f,
                    android.graphics.Path.Direction.CCW)
            }
        }

        // ①.5 峰值帽 → 8 条 Path 一次绘制（颜色按桶内中间 hue）+ 最高档辉光层（压在帽下）
        drawPath(peakGlowPath,
            VisualizerMath.hsl((baseHue0 + baseHue1) / 2f, sat, 0.85f), alpha = 0.18f)
        for (s in 0 until 8) {
            val hue = baseHue0 + (s + 0.5f) * (baseHue1 - baseHue0) / 8f
            drawPath(peakPaths[s],
                VisualizerMath.hsl(hue, sat, minOf(lit + 0.25f, 1f)), alpha = 0.75f)
        }

        // ② 外圈细线环 —— §A2-3：8 段 drawArc 拼接 → 1 次 sweepGradient + Stroke
        //（段间色阶台阶消除，draw 8 → 1；Brush 按 (w,h) 缓存 ⛔ 不可每帧新建）
        val ringR = rRing * (1f + frame.energy * 0.02f) * scale
        val ringBrush = ringBrushCache.get(w, h, ringBrushBuilder)
        drawCircle(
            brush = ringBrush,
            radius = ringR,
            center = Offset(cx, cy),
            alpha = 0.30f + frame.pulse * 0.45f,
            style = Stroke(width = 1.5f + frame.pulse * 1.5f)
        )

        // ③ 节拍爆环 —— 亮白冲击感
        if (frame.beat) {
            drawCircle(VisualizerMath.hsl(60f, 1.0f, 0.98f),
                ringR * (1f + frame.pulse * 0.4f), Offset(cx, cy),
                alpha = 0.65f, style = Stroke(width = 4.5f))
        }

        // ④ 中央圆盘 —— §A2-4：3 层同心圆（同心台阶）→ 1 次径向渐变 + 0.5px 亮边
        // glowHue 连续依赖 energy ⇒ 量化 12 档进缓存键（连续值会打爆 Brush 缓存），
        // 呼吸感由 alpha 承担；「0.85r 透明」的收边由 shadeBrushCached 的 1f 端 darken 表达
        val glowHueRaw = VisualizerMath.hueGradient(baseHue0, baseHue1, (frame.energy * 0.7f).coerceIn(0f, 1f))
        val glowHue = (glowHueRaw / 30f).toInt().coerceIn(0, 11) * 30f
        val coverR = rCover * (1f + frame.pulse * 0.03f)
        // 键 = (w,h,量化hue) 三维；lit 恒 0.55（pulse 呼吸交给 alpha）⇒ 键空间 12 ≤ 16 槽
        val discBrush = Shading2D.shadeBrushCached(
            key = (w.toRawBits().toLong() shl 32) xor h.toRawBits().toLong() xor
                glowHue.toRawBits().toLong(),
            center = Offset(w / 2f, h / 2f),
            radius = minOf(w, h) * 0.20f,
            base = VisualizerMath.hsl(glowHue, sat, 0.55f),
            contrast = 0.50f)
        drawCircle(brush = discBrush, radius = coverR * 1.12f, center = Offset(cx, cy),
            alpha = 0.40f + frame.pulse * 0.3f)
        drawCircle(brush = discBrush, radius = coverR, center = Offset(cx, cy),
            alpha = 0.60f + frame.pulse * 0.3f)
        drawCircle(
            color = VisualizerMath.towardWhite(VisualizerMath.hsl(glowHue, sat, lit), 0.45f),
            radius = coverR, center = Offset(cx, cy),
            alpha = 0.65f + frame.energy * 0.35f,
            style = Stroke(width = 0.5f))

        // 后处理已由基类按 `postFx` 在 drawContent 之后统一施加（§A2-6）
    }

    /** 角向渐变细线环已由 §A2-3 的 sweepGradient 环取代（原 8 段 drawArc 版删除） */
}
// E07 频率山峦
// ═══════════════════════════════════════════════════════════════════

/**
 * E07 `FREQUENCY_MOUNTAIN` — 频率山峦
 *
 * 半透明多层填充面积图叠加，极光/山峦感（比 E02 更柔和、更氛围）。
 */
class FrequencyMountainRenderer : RendererFx() {

    override val theme = VisualizerTheme.FREQUENCY_MOUNTAIN

    // §A3-6：收尾后处理（暗角 0.44 ≥ §13.5-D9 下限 0.42）
    override val postFx = PostFx(vignette = 0.44f, grain = 0.030f)

    private val path = Path()          // 山体填充 + 主描边复用
    private val ridgePath = Path()     // §A3-4 山脊高光线（同形曲线，y+1.2px）
    // §A3-5 接触阴影：垂直渐变 Brush（铺整屏、山体路径即遮罩）。
    // 颜色依赖 (accent, secondary) ⇒ 手写键缓存（换歌/换尺寸才重建，每帧零分配）
    private var gradW = -1f
    private var gradH = -1f
    private var gradKey = 0L
    private val gradBrushes = arrayOfNulls<Brush>(5)

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        val w = size.width
        val h = size.height
        val accent = ctx.palette.accent
        val secondary = ctx.palette.secondary
        val layers = 5
        val baseY = h * 0.76f
        val n = ctx.quality.barCount
        val iw = w.toInt().coerceIn(1, 4096)
        val ih = h.toInt().coerceIn(1, 4096)

        // ── §A3-1 三段式背景（G9）：① 径向纵深 ② 纸纹 tile ③ 星野 tile（共 3 draw）──
        // ⚠️ 偏差（§12.4）：「色相偏冷 8°」在 shadeBrushCached（亮度对比）里不可表达，
        //    以中心提亮/边缘压暗近似纵深；冷相色调由后处理 vignette 统一压暗承担。
        ProceduralTexture.ensure(iw, ih)
        drawRect(brush = Shading2D.shadeBrushCached(
            key = (w.toRawBits().toLong() shl 32) xor h.toRawBits().toLong() xor
                accent.toArgb().toLong() xor E07_KEY_SALT,
            center = Offset(w / 2f, h * 0.45f),
            radius = maxOf(w, h) * 0.62f,
            base = VisualizerMath.darken(accent, 0.55f),
            contrast = 0.10f))
        ProceduralTexture.tile(ProceduralTexture.Id.PAPER)?.let {
            drawImage(it, dstSize = IntSize(iw, ih), alpha = 0.10f)
        }
        ProceduralTexture.tile(ProceduralTexture.Id.STARFIELD)?.let {
            drawImage(it, dstSize = IntSize(iw, ih), alpha = 0.20f)
        }

        // §A3-5 缓存维护：键 = (w, h, accent, secondary)
        val palKey = accent.toArgb().toLong() * 31L + secondary.toArgb().toLong()
        if (gradW != w || gradH != h || gradKey != palKey) {
            for (l in 0 until layers) {
                val col = if (l % 2 == 0) accent else secondary
                gradBrushes[l] = Brush.verticalGradient(
                    0f to col.copy(alpha = 0.20f),
                    1f to col.copy(alpha = 0.02f))
            }
            gradW = w; gradH = h; gradKey = palKey
        }

        for (l in 0 until layers) {
            val t = l / layers.toFloat()
            val col = if (l % 2 == 0) accent else secondary
            // §A3-3 层间透视：baseY 按 t 上移 + 幅度双重调制 ⇒ 近山（低、大）→ 远山（高、小）
            val layerY = baseY - t * h * 0.03f
            val amp = h * 0.38f * (1f - t * 0.25f) * (0.65f + t * 0.35f) * (1f + frame.bass * 0.5f)

            // ── §A3-2 山脊平滑：中点二次贝塞尔（DnaRenderer 范式）──
            // 每相邻点对 1 次 quadraticTo（n-2 段 + 首尾直线），单 Path、零分配；
            // 判据「放大后无锯齿台阶」成立（硬折 lineTo 全部消除）
            path.reset()
            ridgePath.reset()
            var lastX = 0f
            var lastY = 0f
            var havePrev = false
            for (i in 0 until n) {
                val xi = if (n > 1) w * i / (n - 1) else 0f
                val v = frame.spectrum.getOrElse(i) { 0f }
                val yi = layerY - v * amp
                if (!havePrev) {
                    path.moveTo(xi, yi)
                    ridgePath.moveTo(xi, yi + 1.2f)
                    lastX = xi; lastY = yi
                    havePrev = true
                    continue
                }
                val mx = (lastX + xi) / 2f
                val my = (lastY + yi) / 2f
                path.quadraticBezierTo(lastX, lastY, mx, my)
                ridgePath.quadraticBezierTo(lastX, lastY + 1.2f, mx, my + 1.2f)
                lastX = xi; lastY = yi
            }
            path.lineTo(lastX, lastY)
            ridgePath.lineTo(lastX, lastY + 1.2f)
            path.lineTo(w, layerY + h)
            path.lineTo(0f, layerY + h)
            path.close()

            // §A3-5 接触阴影（渐变填充 0.20→0.02，雾气下沉）→ §A3-4 主线 + 高光线
            drawPath(path, gradBrushes[l]!!)
            drawPath(path, col, alpha = 0.30f, style = Stroke(width = 1.6f))
            drawPath(ridgePath, VisualizerMath.towardWhite(col, 0.5f),
                alpha = 0.22f, style = Stroke(width = 1f))
        }

        // 后处理已由基类按 `postFx` 在 drawContent 之后统一施加（§A3-6）
    }

    private companion object {
        /**
         * 与其它渲染器的 `shadeBrushCached` 键区分（§四 G4 / §12.4）—— 理由同 E03 的
         * `E03_KEY_SALT`：`Shading2D` 的 Brush 缓存是**进程级共享**的，
         * 撞键 ⇒ 切换效果后背景径向渐变会复用别人的 `center` / `radius` / `contrast`。
         */
        const val E07_KEY_SALT = 0x07070707L
    }
}
