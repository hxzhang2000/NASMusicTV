package com.nasmusic.tv.visualizer.renderers

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerMath
import kotlin.math.cos
import kotlin.math.sin

// ═══════════════════════════════════════════════════════════════════
// E18 反馈残像（MilkDrop）
// ═══════════════════════════════════════════════════════════════════

/**
 * E18 `MILKDROP_FEEDBACK` — 反馈残像
 *
 * 把上一帧缩放/旋转/平移后回绘，再叠加当前频谱 → 无限递归流光。
 * 经典 Winamp MilkDrop，是所有效果中最"迷幻"的一个。
 *
 * **性能红线**：每帧 3 次离屏 drawImage（3-tap 后）+ 1 次全屏回绘，是 TV 填充率杀手。
 *   - 仅 HIGH 档启用（[com.nasmusic.tv.data.model.VisualQuality.allowFramebuffer]）
 *   - 离屏缓冲降采样到 720p 再放大回绘（省约 55% 填充，视觉几乎无损）
 *
 * ## §B4 质感改造（S4 · T4.4）
 *
 * **① 衰减色调** —— 回绘后叠 1 层纯黑 `alpha = [DECAY_ALPHA]` 的 `drawRect`
 * （**在离屏缓冲内**、全缓冲覆盖；成本 1 次 draw，与 §A4-2 的 E12 衰减层同手法）——
 * 旧实现每帧只做「缩回绘」不做「衰减」，`alpha 0.88–0.94` 使亮度单调累积，
 * 连续播放数分钟必然糊成灰白。
 *
 * **② 3-tap 径向模糊** —— 上一帧 `p` 分 3 次以 [TAP_SCALE]（`1.0 / 1.012 / 1.024`）
 * 叠加绘制，alpha 按 [TAP_ALPHA]（`0.6 / 0.25 / 0.15`）分配 ⇒ 等效轻度模糊，
 * 把"硬拷贝"变成"有柔化的拖影"。⚠️ 两个数组都是**相对于**基准 `scale` / `alpha`
 * 的系数，且 [TAP_ALPHA] **和为 1** ⇒ 单帧回绘总亮度与改造前持平（只多 2 次离屏 draw）。
 *
 * **③ 段落色温** —— `hue` 的**流速**接 [AudioFrame.sectionEnergy]（8s 均值，§四 G10）：
 * 器乐段近乎静止、副歌向蓝端快速流动。`sectionEnergy` 先过一层极慢 EMA
 * （[SECTION_RATE]；**dt 化** ⇒ 与帧率无关），避免逐帧抖动。
 *
 * **④ 双边明暗** —— 频谱环每根条由 1 次 `drawLine` 改为 **2 次**（同 §A2-1）：
 * 先画**暗侧**（`lit − [SIDE_LIT_DARK]`，沿切向偏移 `−[SIDE_OFFSET] × wdt`），
 * 再画**亮侧**（`lit + [SIDE_LIT_BRIGHT]`，偏移 `+[SIDE_OFFSET] × wdt`）——
 * 等效「沿法线的明暗」，条从"扁片"变成"圆柱"。
 * ⚠️ 这是**有意**的观感变化，代价是环的 draw 数翻倍（`barCount` = 64 ⇒ 64 → 128），见 §12.4。
 *
 * **⑤ 后处理** —— `PostFx(vignette = 0.48f, grain = 0.030f)`。
 * ⚠️ §B4 原文**没有后处理项**（其余 9 套批次 B 都以 `drawVignette + drawGrain` 收尾）
 * ⇒ 按 §B 族惯例补齐，使本类可移入 `FxCoverageScanTest.covered`
 * （T4.11 的「A+B 共 21 套」口径）；决策与理由登记在 §12.4。
 * ⛔ `postFx` 必须写**数值字面量** —— 门禁 `numRe` 只认字面量，具名常量会被**静默判为"未覆盖"**。
 *
 * ## 帧率无关（与 E11 / E14 / E16 同一约定）
 * 旧实现 `rotation += 0.3f + mid * 0.5f` 与 `hue += 0.35f + treble * 2f` 都是**每帧**
 * 固定增量 ⇒ 60fps 下的转速 / 变色速度是 30fps 的 2 倍（§四 G13 · 根因⑩）。
 * 现改为 `× fx.dt × [FPS_BASE]`：`FPS_BASE = 60` ⇒ **60fps 下与旧实现逐像素等同**，
 * 30 / 15fps 下不再翻倍。⚠️ §B4 原文未列这一条（§B1-④ 对 E11 列了），
 * 属**同族缺陷的顺带修复**，登记在 §12.4。
 *
 * ⛔ 迁移 [RendererFx] 后 `draw` / `onEnter` / `onExit` 均为 `final` ⇒ 子类只实现
 * `onEnterContent` / `onExitContent` / `drawContent`（**不要**再写 `override fun onEnter`）。
 */
class MilkdropRenderer : RendererFx() {

    override val theme = VisualizerTheme.MILKDROP_FEEDBACK

    // §B4-⑤ 收尾后处理（暗角 + 颗粒）
    override val postFx = PostFx(vignette = 0.48f, grain = 0.030f)

    private var prev: ImageBitmap? = null
    private var curr: ImageBitmap? = null
    /**
     * P1#7（2026-09-14）：与 [prev]/[curr] 一一对应的预分配 Canvas。
     *
     * 原实现每帧 `Canvas(c)` 新建包装对象，违反本项目"绘制循环零分配"铁律。
     * 由于 [prev]/[curr] 每帧互换（末尾 `prev = c; curr = p`），Canvas 必须**跟随
     * 它包装的那个 ImageBitmap 一起互换**，否则会画到错误的缓冲上。
     * 不变式：`currCanvas` 恒包装 `curr`，`prevCanvas` 恒包装 `prev`。
     */
    private var prevCanvas: Canvas? = null
    private var currCanvas: Canvas? = null
    private val paint = androidx.compose.ui.graphics.Paint()

    /** §B4-① 衰减层画笔：纯黑 `alpha = [DECAY_ALPHA]`，构造期建一次（每帧只读） */
    private val decayPaint = androidx.compose.ui.graphics.Paint().apply {
        color = Color.Black.copy(alpha = DECAY_ALPHA)
    }

    private var rotation = 0f
    private var hue = 120f   // 绿系起点（黄60° → 蓝195°区间流动）

    /** §B4-③ 段落色温：`sectionEnergy` 的极慢 EMA（dt 化 ⇒ 帧率无关） */
    private var sectionHue = 0f

    override fun onEnterContent(ctx: RenderContext) {
        // 2026-09-25 审查修复（#12 位图泄漏）：RendererSwapper 在画质变化时会重入 onEnter
        //（RendererSwapper.sync:78-79），旧的双 1280×720 ImageBitmap（约 7.4MB）此前只置 null，
        // API 22-25 上 Bitmap 像素在 native 堆、仅靠 finalizer 延迟回收。重入/退出前先显式 recycle。
        releaseBuffers()
        // 降采样到 720p 离屏
        val w = 1280
        val h = 720
        val a = ImageBitmap(w, h)
        val b = ImageBitmap(w, h)
        prev = a
        curr = b
        // P1#7：两个缓冲各建一个 Canvas，此后不再分配
        prevCanvas = Canvas(a)
        currCanvas = Canvas(b)
        rotation = 0f
        sectionHue = 0f
    }

    /** 释放乒乓双缓冲（审查修复 #12；可安全重复调用，只回收未置空的位图） */
    private fun releaseBuffers() {
        try { prev?.asAndroidBitmap()?.recycle() } catch (_: Exception) {}
        try { curr?.asAndroidBitmap()?.recycle() } catch (_: Exception) {}
        prev = null
        curr = null
        prevCanvas = null
        currCanvas = null
    }

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        val p = prev ?: return
        val c = curr ?: return
        // P1#7：复用与 curr 绑定的预分配 Canvas（原为每帧 Canvas(c)）
        val cb = currCanvas ?: return

        // §B4-③ 段落色温：source 已是 8s 均值，再叠一层极慢 EMA 只取"更慢"的方向
        //（同旧 WorldRenderer 已删实现的每帧固定系数手法；⛔ 这里按 dt 折算，故帧率无关）
        sectionHue += (frame.sectionEnergy - sectionHue) *
            (fx.dt * SECTION_RATE).coerceIn(0f, 1f)

        // 参数安全区间：缩放 1.015–1.03 / 旋转 0.3–0.8°/帧 / alpha 0.88–0.94
        //（"°/帧"按 60fps 折算 ⇒ 代码里写的是"°/秒 ÷ FPS_BASE"）
        val scale = 1.015f + frame.bass * 0.015f
        rotation += (0.3f + frame.mid * 0.5f) * fx.dt * FPS_BASE
        // hue 在黄(60°)→蓝(195°)区间流动；§B4-③ 用段落能量调制**流速**
        //（器乐段近乎静止、副歌快速流向蓝端）—— 同样 dt 化，60fps 下与旧实现等同
        val hueRate = HUE_RATE_BASE + sectionHue * HUE_RATE_SECTION + frame.treble * 2f
        hue = 60f + (hue + hueRate * fx.dt * FPS_BASE - 60f) % 135f
        val alpha = (0.88f + frame.energy * 0.06f).coerceIn(0.88f, 0.94f)
        val shift = if (frame.beat) 6f else 1f

        // ① 上一帧缩放+旋转+位移回绘到当前缓冲（cb 为 onEnter 预分配，见 P1#7）
        //    §B4-②：分 [TAP_COUNT] 个 tap 叠加（3-tap 径向模糊）；每个 tap 独立 save/restore，
        //    ⇒ 三个 tap 的缩放互不累积（否则会变成 1.0 × 1.012 × 1.024）。
        //    ⛔ 循环上界写常量 `0 until TAP_COUNT`，**不是** `TAP_SCALE.indices`
        //    （`.indices` 会让 §7.5 的成本脚本解析不出迭代次数 ⇒ 假性降耗），见常量 KDoc。
        cb.save()
        cb.translate(c.width / 2f, c.height / 2f)
        cb.rotate(rotation)
        for (t in 0 until TAP_COUNT) {
            cb.save()
            val s = scale * TAP_SCALE[t]
            cb.scale(s, s)
            cb.translate(-c.width / 2f - shift, -c.height / 2f - shift)
            paint.alpha = alpha * TAP_ALPHA[t]
            cb.drawImageRect(p,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(p.width, p.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(c.width, c.height),
                paint = paint)
            cb.restore()
        }
        cb.restore()

        // ①' §B4-① 衰减色调：⛔ 必须在 `cb.restore()` **之后** —— 否则黑层会跟着旋转/缩放，
        //     四角露白。整缓冲覆盖 ⇒ 每帧抹掉 [DECAY_ALPHA] 亮度，抑制灰白累积。
        //     ⛔ 用 `drawRect(4 个 float)` 重载 —— `drawRect(Rect(...))` 会每帧堆分配。
        cb.drawRect(0f, 0f, c.width.toFloat(), c.height.toFloat(), decayPaint)

        // ② 叠加当前频谱（极坐标环，§B4-④ 双边明暗）
        paint.alpha = 1f
        val cx = c.width / 2f
        val cy = c.height / 2f
        val n = ctx.quality.barCount
        val r0 = kotlin.math.min(c.width, c.height) * 0.20f
        val maxLen = kotlin.math.min(c.width, c.height) * 0.34f
        for (i in 0 until n) {
            val a = VisualizerMath.rad(i * 360f / n + rotation * 0.5f)
            val v = frame.spectrum.getOrElse(i) { 0f }
            val len = VisualizerMath.barHeight(v, maxLen, 4f)
            val wdt = 3f + v * 5f
            val lit = 0.60f + v * 0.25f
            // 沿切向（−sin, cos）错开 ±SIDE_OFFSET×wdt：先暗侧、再亮侧 ⇒ 圆柱感（§A2-1）
            // ⚠️ Offset 是 @JvmInline value class ⇒ 下面 4 个 Offset(...) 都是零堆分配
            val off = wdt * SIDE_OFFSET
            val tx = -kotlin.math.sin(a) * off
            val ty = kotlin.math.cos(a) * off
            val inner = VisualizerMath.polar(cx, cy, r0, a)
            val outer = VisualizerMath.polar(cx, cy, r0 + len, a)
            val hh = hue + i * 30f / n
            paint.strokeWidth = wdt
            paint.color = VisualizerMath.hsl(hh, 1.0f, lit - SIDE_LIT_DARK)
            cb.drawLine(
                Offset(inner.x - tx, inner.y - ty),
                Offset(outer.x - tx, outer.y - ty),
                paint)
            paint.color = VisualizerMath.hsl(hh, 1.0f, lit + SIDE_LIT_BRIGHT)
            cb.drawLine(
                Offset(inner.x + tx, inner.y + ty),
                Offset(outer.x + tx, outer.y + ty),
                paint)
        }

        // ③ 铺满画布 + 交换
        drawImage(c, dstSize = IntSize(
            size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)))
        prev = c
        curr = p
        // P1#7：Canvas 与其包装的缓冲同步互换，维持「currCanvas 恒包装 curr」不变式
        val tmpCanvas = prevCanvas
        prevCanvas = currCanvas
        currCanvas = tmpCanvas
    }

    override fun onExitContent() {
        // 审查修复（#12）：显式 recycle 而非仅置 null
        releaseBuffers()
    }

    internal companion object {
        /**
         * §B4-① 衰减色调：每帧抹掉 6% 亮度。
         * ⛔ 调大 → 拖影变短；调小 → 抑制灰白变慢。0.06 是"连续 5 分钟不糊"的折中。
         * ⚠️ 2026-10-01 起**不再**与 §A4-2 的 E12 `FADE_ALPHA` 同值：那边压的是**拖尾长度**
         * （改成 0.02 才铺得满 200 行缓冲），这里压的是**3-tap 回绘的累积亮度**，两个量无关。
         */
        const val DECAY_ALPHA = 0.06f

        /**
         * §B4-② 3-tap 的**个数**。
         *
         * ⛔ 必须是**字面量常量**，且循环上界必须写成 `0 until TAP_COUNT`：
         * `renderer_loop_estimate.py` 的 `resolve_bound()` 对 `.indices` **显式返回 `None`**
         * （对 `floatArrayOf(...).size` 同样解析不出）⇒ 调用方 `tally()` 会把循环体内的
         * 调用记进**「未解析」列**而不是「绘制原语」列 ⇒ §7.5 表出现**假性降耗**。
         * 该脚本的符号表收 `const val X = <数字>`，故本常量可被解析。
         * ⚠️ 与 [TAP_SCALE] / [TAP_ALPHA] 的长度一致性由 `MilkdropTest` 断言兜住。
         */
        const val TAP_COUNT = 3

        /** §B4-② 3-tap 的 3 个缩放系数（**相对**基准 `scale`，不是绝对缩放） */
        val TAP_SCALE = floatArrayOf(1.0f, 1.012f, 1.024f)

        /** §B4-② 3 个 tap 的 alpha 系数（**相对**基准 `alpha`；**和为 1** ⇒ 总亮度持平） */
        val TAP_ALPHA = floatArrayOf(0.6f, 0.25f, 0.15f)

        /**
         * §B4-③ 段落色温 EMA 速率（**每秒**；1 / 0.6 ≈ 1.7s 时间常数）。
         * 源信号 `sectionEnergy` 本身已是 8s 均值，这一层只负责"更慢、更稳"。
         */
        const val SECTION_RATE = 0.60f

        /** §B4-③ hue 的基准流速（度/帧 @60fps，与旧实现 `0.35f` 一致） */
        const val HUE_RATE_BASE = 0.35f

        /** §B4-③ 段落能量对 hue 流速的**附加**跨度（度/帧 @60fps；满载时 ≈ 4.4× 基准） */
        const val HUE_RATE_SECTION = 1.20f

        /** §B4-④ 双边明暗：两侧沿切向的偏移 = `wdt × 该系数`（同 §A2-1 的 0.35） */
        const val SIDE_OFFSET = 0.35f

        /** §B4-④ 暗侧明度下探量（同 §A2-1） */
        const val SIDE_LIT_DARK = 0.22f

        /** §B4-④ 亮侧明度上抬量（同 §A2-1） */
        const val SIDE_LIT_BRIGHT = 0.20f

        /**
         * "每帧固定增量"→"每秒速率"的折算基准。
         * `FPS_BASE = 60` ⇒ **60fps 下与旧实现逐像素等同**（与 E16 的 `RAIN_FPS_BASE` 同义）。
         */
        const val FPS_BASE = 60f
    }
}
