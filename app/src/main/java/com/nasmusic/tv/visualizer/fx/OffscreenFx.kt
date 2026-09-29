package com.nasmusic.tv.visualizer.fx

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection

/**
 * 离屏后处理（**仅 `FxLevel.FULL`**）。
 *
 * 代价参照 `UltraRenderers.kt:28-31` 的实测结论：全屏离屏回绘是 TV 填充率杀手
 * ⇒ 必须降采样（[downscale] ≥ 2）、且必须 `onExit` 显式 `recycle`。
 *
 * **不变式（与 `MilkdropRenderer` 同）**：`aCanvas` 恒包装 `a`、`bCanvas` 恒包装 `b`、
 * `sceneCanvas` 恒包装 `scene`；重建（`ensure` 尺寸变化）与释放（`onExit`）同步交换/清空。
 *
 * ⚠️ buffer→buffer 的绘制（阈值提取 / Kawase 滤波）**必须经 [CanvasDrawScope] 画到
 * 对应的离屏 Canvas 上** —— 写在主 `DrawScope` 里会画到主画布（错层）。
 *
 * 用法（§15.4-A6，S6 接线，屏幕级只做一次）：
 * ```
 * // VisualizerStage 的 DrawScope 内：
 * val sc = offscreen.sceneCanvas(w, h) ?: return 直接画（降级）
 * CanvasDrawScope().draw(density, layoutDirection, sc, size) { renderer.draw(frame, ctx) }
 * drawSceneToScreen()          // 或 drawSceneChroma(offsetPx) 做真通道分离
 * drawBloom(...)               // 亮度提取 + 降采样双滤波 + 加法合成（仅 FULL 档到达这里）
 * ```
 */
class OffscreenFx(private val downscale: Int = 3) {

    private var scene: ImageBitmap? = null      // 场景缓冲（全分辨率）
    private var sceneCanvas: Canvas? = null
    private var a: ImageBitmap? = null          // bloom 工作缓冲（1/downscale）
    private var b: ImageBitmap? = null
    private var aCanvas: Canvas? = null
    private var bCanvas: Canvas? = null
    private var w = 0
    private var h = 0

    private val cds = CanvasDrawScope()
    private val unitDensity = Density(1f)

    /** 画布尺寸变化时重建；尺寸未变则 no-op */
    fun ensure(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (w == width && h == height && scene != null && a != null) return
        releaseBuffers()
        w = width
        h = height
        scene = ImageBitmap(width, height)
        sceneCanvas = Canvas(scene!!)
        val aw = (width / downscale).coerceAtLeast(1)
        val ah = (height / downscale).coerceAtLeast(1)
        a = ImageBitmap(aw, ah)
        b = ImageBitmap(aw, ah)
        // 不变式：canvas 恒包装对应位图（交换/重建时同步）
        aCanvas = Canvas(a!!)
        bCanvas = Canvas(b!!)
    }

    /** 场景画布（供 `CanvasDrawScope().draw(...)` 捕获场景内容）。未 ensure 返回 null */
    fun sceneCanvas(): Canvas? = sceneCanvas

    /** 场景 → 主画布（1 次 drawImage；替代"直接画到主画布"的唯一接线点） */
    fun DrawScope.drawSceneToScreen(alpha: Float = 1f) {
        val s = scene ?: return
        drawImage(
            image = s,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(s.width, s.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            alpha = alpha,
            blendMode = BlendMode.SrcOver
        )
    }

    /** 真通道分离色差（R 向 +x、B 向 −x），替代 `VintageTvRenderer` 的整层 tint 近似 */
    fun DrawScope.drawChroma(offsetPx: Float, alpha: Float = 0.55f) {
        val s = scene ?: return
        val dw = size.width.toInt()
        val dh = size.height.toInt()
        val off = offsetPx.toInt()
        drawImage(
            image = s, srcOffset = IntOffset.Zero, srcSize = IntSize(s.width, s.height),
            dstOffset = IntOffset.Zero, dstSize = IntSize(dw, dh),
            alpha = 1f, blendMode = BlendMode.SrcOver, colorFilter = channelFilter(gOnly = true)
        )
        drawImage(
            image = s, srcOffset = IntOffset.Zero, srcSize = IntSize(s.width, s.height),
            dstOffset = IntOffset(off, 0), dstSize = IntSize(dw, dh),
            alpha = alpha, blendMode = BlendMode.Plus, colorFilter = channelFilter(rOnly = true)
        )
        drawImage(
            image = s, srcOffset = IntOffset.Zero, srcSize = IntSize(s.width, s.height),
            dstOffset = IntOffset(-off, 0), dstSize = IntSize(dw, dh),
            alpha = alpha, blendMode = BlendMode.Plus, colorFilter = channelFilter(bOnly = true)
        )
    }

    /** 亮度阈值提取 + 降采样双滤波 + 加法合成。场景必须已捕获（[sceneCanvas] 已被绘制） */
    fun DrawScope.drawBloom(
        threshold: Float = 0.72f,
        strength: Float = 0.55f,
        radiusK: Float = 0.035f
    ) {
        val src = scene ?: return
        val ab = a ?: return
        val bb = b ?: return
        val ac = aCanvas ?: return
        val bc = bCanvas ?: return
        val passes = (radiusK * 100f).toInt().coerceIn(2, 3)

        // ① 亮度阈值提取 + 降采样到 1/downscale（一次 drawImage 完成；ColorMatrix 免逐像素 JNI）
        ab.asAndroidBitmap().eraseColor(0)
        blit(src, ac, ab, thresholdFilter(threshold))

        // ② Kawase 双滤波：down/up 交替（双线性平滑）共 passes 轮
        var from = ab
        var fromCanvas = ac
        var to = bb
        var toCanvas = bc
        repeat(passes) {
            blit(from, toCanvas, to, null)
            val tBmp = from; from = to; to = tBmp
            val tCvs = fromCanvas; fromCanvas = toCanvas; toCanvas = tCvs
        }

        // ③ 加法合成回主画布（放大回全分辨率）
        drawImage(
            image = from, srcOffset = IntOffset.Zero, srcSize = IntSize(from.width, from.height),
            dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            alpha = strength, blendMode = BlendMode.Plus, filterQuality = FilterQuality.Medium
        )
    }

    /** buffer→buffer blit：经 [CanvasDrawScope] 画到 [toCanvas]（离屏）上，而非主画布 */
    private fun blit(from: ImageBitmap, toCanvas: Canvas, to: ImageBitmap, colorFilter: ColorFilter?) {
        cds.draw(
            unitDensity, LayoutDirection.Ltr, toCanvas,
            Size(to.width.toFloat(), to.height.toFloat())
        ) {
            drawImage(
                image = from,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(from.width, from.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(to.width, to.height),
                alpha = 1f,
                blendMode = BlendMode.SrcOver,
                colorFilter = colorFilter,
                filterQuality = FilterQuality.Medium
            )
        }
    }

    /** `onExit` 显式 `recycle` 全部缓冲（API 22-25 上 Bitmap 像素在 native 堆） */
    fun onExit() = releaseBuffers()

    private fun releaseBuffers() {
        scene?.asAndroidBitmap()?.recycle(); scene = null; sceneCanvas = null
        a?.asAndroidBitmap()?.recycle(); a = null; aCanvas = null
        b?.asAndroidBitmap()?.recycle(); b = null; bCanvas = null
        w = 0; h = 0
    }

    private var cachedThreshold: Float = Float.NaN
    private var cachedThresholdFilter: ColorFilter? = null
    private var cachedChannel: Int = 0   // 1=R 2=G 3=B
    private var cachedChannelFilter: ColorFilter? = null

    /** 阈值提取：out = (in − t) × 1/(1−t)，逐通道（ColorMatrix 实现，免逐像素 JNI） */
    private fun thresholdFilter(t: Float): ColorFilter {
        if (cachedThresholdFilter != null && cachedThreshold == t) return cachedThresholdFilter!!
        val s = 1f / (1f - t).coerceAtLeast(1e-3f)
        val m = ColorMatrix(floatArrayOf(
            s, 0f, 0f, 0f, -t * s,
            0f, s, 0f, 0f, -t * s,
            0f, 0f, s, 0f, -t * s,
            0f, 0f, 0f, 1f, 0f
        ))
        cachedThreshold = t
        cachedThresholdFilter = ColorFilter.colorMatrix(m)
        return cachedThresholdFilter!!
    }

    private fun channelFilter(rOnly: Boolean = false, gOnly: Boolean = false, bOnly: Boolean = false): ColorFilter {
        val which = when {
            rOnly -> 1
            gOnly -> 2
            else -> 3
        }
        if (cachedChannelFilter != null && cachedChannel == which) return cachedChannelFilter!!
        val m = ColorMatrix(floatArrayOf(
            if (rOnly) 1f else 0f, 0f, 0f, 0f, 0f,
            0f, if (gOnly) 1f else 0f, 0f, 0f, 0f,
            0f, 0f, if (bOnly) 1f else 0f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        ))
        cachedChannel = which
        cachedChannelFilter = ColorFilter.colorMatrix(m)
        return cachedChannelFilter!!
    }
}
