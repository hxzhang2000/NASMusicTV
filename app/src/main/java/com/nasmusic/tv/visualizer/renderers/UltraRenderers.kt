package com.nasmusic.tv.visualizer.renderers

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.AudioFrame
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerMath
import com.nasmusic.tv.visualizer.fx.ProceduralTexture
import com.nasmusic.tv.visualizer.fx.Shading2D
import kotlin.math.PI
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
        //（同 WorldRenderer:612 的手法；⛔ 这里按 dt 折算，故帧率无关）
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
// ═══════════════════════════════════════════════════════════════════
// E20 等离子流场
// ═══════════════════════════════════════════════════════════════════

/**
 * E20 `PLASMA_FLOW` — 等离子流场
 *
 * 自实现简化 value noise 生成流场，粒子沿流场运动。
 * 噪声网格每 [NOISE_EVERY] 帧更新一次以摊薄成本。
 *
 * ## §B6 质感改造（S4 · T4.6）
 *
 * **① 网格加密 + fbm 双倍频** —— 网格 `16×9 → [GW]×[GH]`（24×14）⇒ 流场不再呈块状。
 * 更新循环里按 [fbmAt] 算出**两层**叠加后的值写回 [noise]。
 * ⚠️ **与 §B6 原文的偏差**：原文说「在 `sampleFlow` 内加 1 次 fbm，需第二个 `FloatArray`」，
 * 落地改为**在（每 [NOISE_EVERY] 帧一次的）更新循环里合并** ⇒ 不需要第二个常驻数组，
 * 且每粒子只做 **1 次**双线性而非 2 次。两者**数学等价**：双线性插值是**线性算子**，
 * `bilinear(f1 + k·f2) ≡ bilinear(f1) + k·bilinear(f2)`（仅差 float32 舍入）。见 §12.4。
 *
 * **② 等离子底色** —— 两层，均**不随 `FxLevel` 关闭**（与已落地的 E07 / E13 同一约定：
 * 背景承担"不再浮在纯黑上"，按档位关的是 vignette / grain 这类胶片感后处理）：
 * ① `ProceduralTexture.Id.PLASMA` tile（3 通道低频 `sin` 合成；
 *    `alpha = [PLASMA_ALPHA_BASE] + energy × [PLASMA_ALPHA_ENERGY]`）；
 * ② **中心径向渐变**（`Shading2D.shadeBrushCached`）—— 替代原实现"1 个纯色圆"。
 * ⚠️ `ProceduralTexture.Id.PLASMA` 是**本任务新增**的第 7 类 tile（原 6 类）：
 * §B6-② 点名要"等离子 tile"，但 §15.2.3 的生成规格表里没有它（文档内部不一致，
 * 与 §B4 缺后处理同族）⇒ 经用户裁决**新增**而非复用现有 tile。见 §12.4。
 *
 * **③ 粒子短条** —— 原「每粒子 1 次 `drawCircle`」（≈150 次/帧）改为
 * **按色相分 [BUCKETS] 桶合批**（桶号由 [bucketOf] 从 `flow` 求出）：
 * 每粒子按**速度方向**拉长的椭圆写进本桶的 [Path]（单点由 [ellipsePoint] 给出），
 * `aMaj = bMin × [ELONG]`（长轴 = 短轴 × 1.8），[SEG] 段多边形。
 * ⇒ 每帧 draw **151 → ≤ [BUCKETS] + 2**（≤8 条 `drawPath` + 1 tile + 1 渐变）。
 * ⛔ **不用 `drawOval`** —— Compose 的 `drawOval` 只能画**轴对齐**椭圆，表达不了
 * "按速度方向拉长"（与 E13 顶点反光同一坑，见 §12.4）。
 * ⚠️ 两处**有意取舍**（登记 §12.4）：① 桶内 alpha 取**均值**
 * （`Σ life / count × [ALPHA_K]`）⇒ 逐粒子寿命衰减被平均（与 E14 拖尾 8 桶合批同取舍）；
 * ② 桶内重叠粒子按**非零环绕**填充（只覆盖一次）而非逐粒子 `Plus` 累加 ⇒
 * 总亮度低于旧实现（跨桶仍 `Plus` 累加）。
 *
 * **④ 后处理** —— `PostFx(vignette = 0.48f, grain = 0.030f)`。
 * ⛔ 必须写**数值字面量** —— 门禁 `numRe` 只认字面量，具名常量会被**静默判为"未覆盖"**。
 *
 * ## 帧率无关（与 E11 / E14 / E16 / E18 同一约定）
 * 旧实现三处**每帧固定增量**：`evolve += 0.01f + mid × 0.03f`、`life -= 0.006f`、
 * `speed = (1.5f + treble × 5f) / 1000f` ⇒ 60fps 的演化速度是 30fps 的 **2 倍**（§四 G13 · 根因⑩）。
 * 现统一 `× fx.dt × [FPS_BASE]`：`FPS_BASE = 60` ⇒ **60fps 下与旧实现逐像素等同**。
 * ⚠️ `frame.beat` 的 `evolve += [BEAT_KICK]` 是**事件踢**（不是速率）⇒ 保持不折算。
 * ⚠️ §B6 原文未列这一条（§B1-④ 对 E11 列了），属同族缺陷的顺带修复，登记 §12.4。
 *
 * ⛔ 迁移 [RendererFx] 后 `draw` / `onEnter` / `onExit` 均为 `final` ⇒ 子类只实现
 * `onEnterContent` / `onExitContent` / `drawContent`（**不要**再写 `override fun onEnter`）；
 * 也**不要**再声明 `rng`（基类已提供 `protected val rng`）。
 */
class PlasmaFlowRenderer : RendererFx() {

    override val theme = VisualizerTheme.PLASMA_FLOW

    // §B6-④ 收尾后处理（暗角 + 颗粒）
    override val postFx = PostFx(vignette = 0.48f, grain = 0.030f)

    /**
     * §B6-① 流场噪声网格（原 16×9 极粗 ⇒ 流场呈块状）。
     * 存的是**已合并 fbm 两层**的最终值（见 [drawContent] 的更新循环与 [fbmAt]）。
     */
    private val noise = FloatArray(GW * GH)

    private var xs = FloatArray(0)
    private var ys = FloatArray(0)
    private var life = FloatArray(0)

    /** `fx.dt` 累加相位（§B6 未列，同族顺带修复：⛔ 不得改回"每帧固定增量"） */
    private var evolve = 0f

    /** 噪声刷新的帧计数（帧数口径，⛔ 不随 `dt` 折算 —— §B6-① 明确写"每 3 帧"） */
    private var frameTick = 0

    /** §B6-③ 按色相分桶的合批路径（构造期建一次 ⇒ draw 期零分配） */
    private val bucketPaths = Array(BUCKETS) { Path() }

    /** §B6-③ 桶内 life 累加与计数（复用数组 ⇒ 每帧 `fill` 重置，零分配） */
    private val bucketLife = FloatArray(BUCKETS)
    private val bucketCount = IntArray(BUCKETS)

    override fun onEnterContent(ctx: RenderContext) {
        val cap = ctx.quality.maxParticles.coerceAtLeast(MIN_PARTICLES)
        xs = FloatArray(cap)
        ys = FloatArray(cap)
        life = FloatArray(cap)
        for (i in 0 until cap) {
            xs[i] = rng.next()
            ys[i] = rng.next()
            life[i] = rng.next()
        }
        noise.fill(0f)
        evolve = 0f
        frameTick = 0
    }

    override fun onExitContent() {
        xs = FloatArray(0)
        ys = FloatArray(0)
        life = FloatArray(0)
    }

    /**
     * 双线性插值采样流场。
     * ⚠️ **单层** —— fbm 的两层已在更新循环里合并进 [noise]（见类 KDoc 的偏差说明）。
     */
    private fun sampleFlow(u: Float, v: Float): Float {
        val x = (u * (GW - 1)).coerceIn(0f, GW - 1.001f)
        val y = (v * (GH - 1)).coerceIn(0f, GH - 1.001f)
        val x0 = x.toInt()
        val y0 = y.toInt()
        val fx = x - x0
        val fy = y - y0
        val i00 = noise[y0 * GW + x0]
        val i10 = noise[y0 * GW + (x0 + 1).coerceAtMost(GW - 1)]
        val i01 = noise[(y0 + 1).coerceAtMost(GH - 1) * GW + x0]
        val i11 = noise[(y0 + 1).coerceAtMost(GH - 1) * GW + (x0 + 1).coerceAtMost(GW - 1)]
        val a = i00 + (i10 - i00) * fx
        val b = i01 + (i11 - i01) * fx
        return a + (b - a) * fy
    }

    override fun DrawScope.drawContent(frame: AudioFrame, ctx: RenderContext, fx: FxFrame) {
        if (xs.isEmpty()) onEnterContent(ctx)
        val w = size.width
        val h = size.height
        val iw = w.toInt().coerceIn(1, 4096)
        val ih = h.toInt().coerceIn(1, 4096)
        val accent = ctx.palette.accent

        // ── §B6-② 等离子底色（背景层，⛔ 不随 FxLevel 关闭）──
        // ⚠️ `ensure` 必须在这里调（⛔ 不能按 KDoc 挪进 onEnterContent）：
        //    `RendererSwapper.sync()` → `onEnter` 在 VisualizerStage 的**组合体**里跑，
        //    而 `renderCtx.update(…, canvasSize, …)` 在 **Canvas draw 块**里 ⇒
        //    首次组合时 onEnter 拿到的 canvasSize == Size.Zero ⇒ ensure(0,0) 被早返回。
        //    `ensure` 自带 ensuredW/ensuredH 幂等短路 ⇒ 每帧成本仅"首帧 / 换尺寸"一次。
        ProceduralTexture.ensure(iw, ih)
        ProceduralTexture.tile(ProceduralTexture.Id.PLASMA)?.let {
            drawImage(
                image = it,
                dstSize = IntSize(iw, ih),
                alpha = PLASMA_ALPHA_BASE + frame.energy * PLASMA_ALPHA_ENERGY
            )
        }
        drawRect(
            brush = Shading2D.shadeBrushCached(
                key = (w.toRawBits().toLong() shl 32) xor h.toRawBits().toLong() xor
                    accent.toArgb().toLong() xor E20_KEY_SALT,
                center = Offset(w / 2f, h / 2f),
                radius = ctx.minDim * CORE_RADIUS_K,
                base = VisualizerMath.darken(accent, 0.55f),
                contrast = 0.12f
            ),
            alpha = CORE_ALPHA_BASE + frame.pulse * CORE_ALPHA_PULSE
        )

        // ── 帧率无关折算（§B6 未列，同族顺带修复）──
        // ⛔ 60fps 下 `k == 1` ⇒ 与旧实现逐像素等同；30 / 15fps 不再翻倍。
        val k = fx.dt * FPS_BASE
        evolve += (EVOLVE_BASE + frame.mid * EVOLVE_MID) * k
        if (frame.beat) evolve += BEAT_KICK          // 事件踢，⛔ 不是速率 ⇒ 不折算

        // ── §B6-① 噪声每 NOISE_EVERY 帧更新一次（帧数口径），并按 fbmAt 合并两层 ──
        if (frameTick++ % NOISE_EVERY == 0) {
            for (gy in 0 until GH) {
                for (gx in 0 until GW) {
                    noise[gy * GW + gx] = fbmAt(gx, gy, evolve)
                }
            }
        }

        val cap = xs.size
        val speed = (SPEED_BASE + frame.treble * SPEED_TREBLE) / 1000f * k
        val lifeDecay = LIFE_DECAY * k

        // ── §B6-③ 推进 + 按色相分桶写路径 ──
        for (p in bucketPaths) p.reset()
        java.util.Arrays.fill(bucketLife, 0f)
        java.util.Arrays.fill(bucketCount, 0)
        for (i in 0 until cap) {
            val flow = sampleFlow(xs[i], ys[i])
            val ang = flow * TWO_PI * (1f + frame.bass) + evolve
            val ca = cos(ang)
            val sa = sin(ang)
            xs[i] += ca * speed
            ys[i] += sa * speed

            var lf = life[i] - lifeDecay
            // 越界或寿命耗尽 → 重生（与旧实现同语义：重生后**当帧照画**）
            if (lf <= 0f || xs[i] < 0f || xs[i] > 1f || ys[i] < 0f || ys[i] > 1f) {
                xs[i] = rng.next()
                ys[i] = rng.next()
                lf = 1f
            }
            life[i] = lf

            val b = bucketOf(flow)
            bucketLife[b] += lf
            bucketCount[b]++

            val cx = xs[i] * w
            val cy = ys[i] * h
            val r = R_BASE + lf * R_LIFE
            val aMaj = r * ELONG      // 半长轴（沿速度方向 u）
            val bMin = r              // 半短轴
            val p = bucketPaths[b]
            for (s in 0 until SEG) {
                val pt = ellipsePoint(s, cx, cy, aMaj, bMin, ca, sa)
                if (s == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
            }
        }
        // 桶色 = 桶中心色相；桶 alpha = 桶内 life 均值 × ALPHA_K
        for (b in 0 until BUCKETS) {
            val n = bucketCount[b]
            if (n == 0) continue
            drawPath(
                bucketPaths[b],
                VisualizerMath.hsl(HUE_BASE + (b + 0.5f) * HUE_SPAN / BUCKETS, 1.0f, PARTICLE_L),
                alpha = bucketLife[b] / n * ALPHA_K,
                blendMode = BlendMode.Plus
            )
        }
    }

    internal companion object {
        /** §B6-① 流场网格列数（原 16 ⇒ 块状） */
        const val GW = 24

        /** §B6-① 流场网格行数（原 9 ⇒ 块状） */
        const val GH = 14

        /** §B6-① 噪声刷新间隔（**帧**；帧数口径，⛔ 不随 `dt` 折算） */
        const val NOISE_EVERY = 3

        /** §B6-① fbm 第二层权重（`v1 + FBM_K × v2`） */
        const val FBM_K = 0.5f

        /** §B6-① 第一层噪声的网格频率（= 旧实现 `gx × 0.7f` / `gy × 0.9f`） */
        const val N1_FX = 0.7f
        const val N1_FY = 0.9f

        /** §B6-① 第一层噪声的 `evolve` 相位因子（= 旧实现 `- evolve × 0.7f`） */
        const val N1_EP = 0.7f

        /** §B6-① 第二层（细节层）的网格频率 */
        const val N2_FX = 1.3f
        const val N2_FY = 1.1f

        /** §B6-① 第二层的 `evolve` 相位因子 */
        const val N2_EP = 0.9f
        const val N2_EP2 = 0.5f

        /** §B6-② 等离子 tile 的基础 alpha */
        const val PLASMA_ALPHA_BASE = 0.16f

        /** §B6-② 等离子 tile 的 energy 附加 alpha */
        const val PLASMA_ALPHA_ENERGY = 0.10f

        /** §B6-② 中心径向渐变的半径 = `minDim × 该系数` */
        const val CORE_RADIUS_K = 0.62f

        /** §B6-② 中心渐变的 alpha（旧实现"纯色圆"为 `0.06f + pulse × 0.10f`） */
        const val CORE_ALPHA_BASE = 0.10f
        const val CORE_ALPHA_PULSE = 0.10f

        /** §B6-③ 粒子短条：色相桶数（合批 ⇒ 每帧 `drawPath` ≤ 8） */
        const val BUCKETS = 8

        /** §B6-③ 参数方程多边形段数（半长轴 ≤ 14.4px ⇒ 8 段最大偏差 < 0.3px） */
        const val SEG = 8

        /** §B6-③ 长轴 / 短轴比（长轴 = 短轴 × 1.8） */
        const val ELONG = 1.8f

        /** §B6-③ 粒子半短轴基准 / 随 life 的增长量（旧实现 `3f + life × 5f`） */
        const val R_BASE = 3f
        const val R_LIFE = 5f

        /** §B6-③ 桶 alpha = 桶内 life 均值 × 该系数（旧实现逐粒子 `life × 0.8f`） */
        const val ALPHA_K = 0.8f

        /** 色相：`HUE_BASE + (flow × HUE_SPAN + HUE_OFF) % HUE_SPAN`（与旧实现一致） */
        const val HUE_BASE = 60f
        const val HUE_SPAN = 135f
        const val HUE_OFF = 75f

        /** 粒子明度（旧实现 `hsl(…, 1.0f, 0.68f)`） */
        const val PARTICLE_L = 0.68f

        /** "每帧固定增量"→"每秒速率"的折算基准；`60` ⇒ 60fps 下与旧实现逐像素等同 */
        const val FPS_BASE = 60f

        /** `evolve` 的基准流速（旧 `0.01f`）与 mid 附加量（旧 `0.03f`） */
        const val EVOLVE_BASE = 0.01f
        const val EVOLVE_MID = 0.03f

        /** `beat` 事件踢（旧 `evolve += 0.35f`；⛔ 是事件不是速率 ⇒ 不折算） */
        const val BEAT_KICK = 0.35f

        /** 粒子位移速率（旧 `(1.5f + treble × 5f) / 1000f` 每帧） */
        const val SPEED_BASE = 1.5f
        const val SPEED_TREBLE = 5f

        /** 粒子寿命每帧衰减（旧 `0.006f`；≈ 2.8s 生命周期 @60fps） */
        const val LIFE_DECAY = 0.006f

        /** 粒子数下限（旧 `coerceAtLeast(60)`） */
        const val MIN_PARTICLES = 60

        /**
         * 与其它渲染器的 `shadeBrushCached` 键区分。
         * ⛔ `Shading2D` 是 `object` ⇒ Brush 缓存**进程级共享**，撞键会拿到别人的 Brush
         * （`center` / `radius` / `base` / `contrast` 全编码在实例内）。见 §四 G4 / §12.4。
         */
        const val E20_KEY_SALT = 0x20202020L

        /** 参数方程的 2π（构造期算一次） */
        val TWO_PI = 2f * PI.toFloat()

        /** §B6-③ 参数方程 [SEG] 段的 cos/sin 表（构造期算一次 ⇒ draw 期零三角函数） */
        val cosSeg = FloatArray(SEG) { cos(TWO_PI * it / SEG) }
        val sinSeg = FloatArray(SEG) { sin(TWO_PI * it / SEG) }

        /**
         * §B6-① 单格 fbm 值（**两层**叠加）：
         * `v1 + [FBM_K] × v2`，`v1` 用旧实现的网格频率、`v2` 为细节层。
         *
         * 抽成**纯函数**是为了让门禁能做**逐点数值断言**与「双线性可交换」的等价性验证
         * （见 `PlasmaFlowTest`）—— ⛔ 不要在测试里复制一份算法（复制必然漂移）。
         */
        internal fun fbmAt(gx: Int, gy: Int, evolve: Float): Float =
            sin(gx * N1_FX + evolve) * cos(gy * N1_FY - evolve * N1_EP) +
                FBM_K * (sin(gx * N2_FX - evolve * N2_EP) * cos(gy * N2_FY + evolve * N2_EP2))

        /**
         * §B6-③ `flow` → 色相桶号（`0..[BUCKETS]-1`）。
         * ⛔ Kotlin 的 `%` **保留被除数符号** ⇒ 必须先归一到 `[0, [HUE_SPAN])` 再分桶，
         * 否则 `flow < 0` 时桶号为负（`coerceIn` 会把它全压到桶 0 ⇒ 颜色分档失效）。
         */
        internal fun bucketOf(flow: Float): Int {
            val t = (((flow * HUE_SPAN + HUE_OFF) % HUE_SPAN) + HUE_SPAN) % HUE_SPAN / HUE_SPAN
            return (t * BUCKETS).toInt().coerceIn(0, BUCKETS - 1)
        }

        /**
         * §B6-③ 参数方程单点：`P(θ) = c + aMaj·cosθ·u + bMin·sinθ·v`，
         * `u = (ca, sa)` = **速度方向**（长轴），`v = (−sa, ca)` = 其法线（短轴）。
         *
         * ⛔ 为什么不用 `drawOval`：Compose 的 `drawOval` 只能画**轴对齐**椭圆，
         * 表达不了"按速度方向拉长"（与 E13 顶点反光同一坑，见 §12.4）。
         * 抽成纯函数是为了让门禁直接断言「长轴沿速度方向 + 长轴 = 短轴 × [ELONG]」。
         */
        internal fun ellipsePoint(
            s: Int, cx: Float, cy: Float, aMaj: Float, bMin: Float, ca: Float, sa: Float,
        ): Offset {
            val c = cosSeg[s]
            val sn = sinSeg[s]
            return Offset(
                cx + aMaj * c * ca - bMin * sn * sa,
                cy + aMaj * c * sa + bMin * sn * ca,
            )
        }
    }
}
