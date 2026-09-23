package com.nasmusic.tv.visualizer.photo

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.nasmusic.tv.backend.photo.PhotoScaleMode
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.visualizer.RenderContext
import kotlin.math.hypot

/**
 * 转场实现机制（§4.1）
 *
 * 决定「怎么把两张图叠出过渡」，也决定性能边界：
 * 只有 [M4_TILES] 有**块数红线**，只有 [M5_SHADER] 需要 API 33+（老平台走降级）。
 */
enum class PhotoMechanism {
    /** 裁剪 / 擦除（几何裁剪，最省） */
    M1_CLIP,

    /** 变换（位移 / 缩放 / 旋转） */
    M2_TRANSFORM,

    /** 遮罩位图（预生成 256×256 灰度图，运行时仅 `BlendMode` 合成，零逐像素成本） */
    M3_MASK_BITMAP,

    /** 分块（每块 1 次 `drawImage`，⚠️ 有块数红线） */
    M4_TILES,

    /** AGSL shader（需 API 33+，老平台降级到 M3 近似） */
    M5_SHADER,
}

/**
 * 可变矩形（`Float`，零分配复用）
 *
 * ⛔ **刻意不用 `android.graphics.Rect`**：① 它是 `Int`，照片缩放在大屏上会累积取整误差
 * （表现为缓慢抖动）；② 用它就要为「纯几何计算」引入 Robolectric，而几何正是最该被
 * 纯 JVM 单测覆盖的部分（门禁 G6）。转换到 `Int` 只在最后交给 `drawImage` 那一刻做。
 */
class PhotoRect {

    var left = 0f
    var top = 0f
    var right = 0f
    var bottom = 0f

    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun set(l: Float, t: Float, r: Float, b: Float) {
        left = l
        top = t
        right = r
        bottom = b
    }

    fun reset() = set(0f, 0f, 0f, 0f)

    /** 是否已算出有效区域（未 update 过 / 尺寸非法时为 false） */
    val isValid: Boolean get() = width > 0f && height > 0f

    override fun toString(): String = "PhotoRect($left, $top, $right, $bottom)"
}

/**
 * 一次绘制所需的几何（复用实例，**零分配**）
 *
 * ## A / B 各有一组 src + dst
 *
 * `drawImage` 需要「源矩形 + 目标矩形」：
 * - [PhotoScaleMode.CROP]（默认，满屏）：**dst = 整个画布**，src = 按画布宽高比**居中裁切**出的子矩形
 * - [PhotoScaleMode.FIT]（完整）：**src = 整图**，dst = 按图片宽高比**内接**于画布
 *
 * ⚠️ **两张图的 src 可能不同**（尺寸 / 宽高比不同），所以 A、B 各存一份 ——
 * 文档 §14.2.3 只写了单个 `src`（「恒为整图」），那只在 FIT 下成立，CROP 下必须裁切。
 *
 * ⚠️ 安全边距（overscan）由调用方的 `Canvas` padding 负责，本类**不再二次扣减** ——
 * 否则会与 `VisualizerStage` 的 `padding(horizontal = 24.dp)` 叠加，画面两侧出现双倍留白。
 */
class PhotoGeometry {

    var canvasW = 0f
        private set
    var canvasH = 0f
        private set
    var minDim = 0f
        private set
    var safeAreaPx = 0f
        private set

    /** A（当前图）的目标矩形 */
    val dstA = PhotoRect()

    /** B（下一张）的目标矩形 */
    val dstB = PhotoRect()

    /** A 的源矩形（CROP 时是居中裁切后的子矩形；FIT 时是整图） */
    val srcA = PhotoRect()

    /** B 的源矩形 */
    val srcB = PhotoRect()

    /** 画布对角线的一半（`IRIS_CIRCLE` 的终态半径） */
    val diagonalHalf: Float get() = hypot(canvasW, canvasH) * 0.5f

    /**
     * 当前帧频谱（64 段，`0..1`）—— 只有「形态由音频驱动」的转场需要（如 `SPECTRUM_WIPE`）。
     *
     * ⚠️ 搭在几何对象上是个**妥协**：[PhotoTransition.render] 的签名（§14.2.3）没有频谱参数，
     * 为它单独改接口会动到全部转场实现，而这里只是「多一个输入」。
     * 由 `PhotoRenderer.draw` 每帧从 `AudioFrame.spectrum` 填入（**不复制**，只存引用）。
     */
    var spectrum: FloatArray? = null

    /** 频谱段数（`0` = 本帧没有频谱数据） */
    val spectrumBars: Int get() = spectrum?.size ?: 0

    /**
     * 重算全部矩形。
     *
     * @param aW / [aH] A 的像素尺寸
     * @param bW / [bH] B 的像素尺寸；默认与 A 相同（单图自转场时 `b == a`）
     */
    fun update(
        ctx: RenderContext,
        scaleMode: PhotoScaleMode,
        aW: Int,
        aH: Int,
        bW: Int = aW,
        bH: Int = aH,
    ) {
        canvasW = ctx.canvasSize.width
        canvasH = ctx.canvasSize.height
        minDim = minOf(canvasW, canvasH)
        safeAreaPx = ctx.safeAreaPx
        fill(srcA, dstA, scaleMode, aW, aH)
        fill(srcB, dstB, scaleMode, bW, bH)
    }

    private fun fill(src: PhotoRect, dst: PhotoRect, mode: PhotoScaleMode, w: Int, h: Int) {
        if (w <= 0 || h <= 0 || canvasW <= 0f || canvasH <= 0f) {
            src.reset()
            dst.reset()
            return
        }
        val imgAspect = w.toFloat() / h.toFloat()
        val canvasAspect = canvasW / canvasH

        when (mode) {
            PhotoScaleMode.CROP -> {
                // 铺满：目标恒为整个画布，超出部分从源里裁掉（居中）
                dst.set(0f, 0f, canvasW, canvasH)
                if (imgAspect > canvasAspect) {
                    // 图比画布更「宽」⇒ 左右裁
                    val srcW = h * canvasAspect
                    val left = (w - srcW) * 0.5f
                    src.set(left, 0f, left + srcW, h.toFloat())
                } else {
                    // 图更「高」⇒ 上下裁
                    val srcH = w / canvasAspect
                    val top = (h - srcH) * 0.5f
                    src.set(0f, top, w.toFloat(), top + srcH)
                }
            }

            PhotoScaleMode.FIT -> {
                // 完整：源恒为整图，目标按图片比例内接（不足处留黑边）
                src.set(0f, 0f, w.toFloat(), h.toFloat())
                if (imgAspect > canvasAspect) {
                    // 图更宽 ⇒ 以画布宽为准，上下留边
                    val dstH = canvasW / imgAspect
                    val top = (canvasH - dstH) * 0.5f
                    dst.set(0f, top, canvasW, top + dstH)
                } else {
                    // 图更高 ⇒ 以画布高为准，左右留边
                    val dstW = canvasH * imgAspect
                    val left = (canvasW - dstW) * 0.5f
                    dst.set(left, 0f, left + dstW, canvasH)
                }
            }
        }
    }
}

/**
 * 一种照片转场效果
 *
 * 一次切换 = **一个**转场实例驱动「旧图退出 + 新图进入」（§5.9）——
 * 不是「入场抽一个 + 退出抽一个」。所以 [render] 同时拿到 `a`（旧图）与 `b`（新图）。
 *
 * ## 三条硬约束
 *
 * 1. ⛔ **[render] 内零分配**：不得 `new`、不得创建集合、不得装箱、不得字符串拼接。
 *    `Paint` / `Path` / `Matrix` / 缓存的 `Color` 一律提到成员变量，在 [prepare] 里初始化。
 * 2. ⛔ **[prepare] 只在「切转场 / 切画质 / 尺寸变化」时被调用**，稳态每帧不调用 ——
 *    所以允许在那里分配（`MaskCache` 的遮罩位图也只在这里取）。
 * 3. ⛔ **[release] 必须释放遮罩 / 位图引用**（`MaskCache` 是全进程共享的，别在这里 `recycle` 它）。
 */
interface PhotoTransition {

    val id: PhotoTransitionId

    /**
     * 预分配缓冲 / 取遮罩。尺寸或画质变化时会被重新调用。
     *
     * @param masks 全进程共享的遮罩缓存（**只读**，不得 `release` 其内容）
     */
    fun prepare(geom: PhotoGeometry, quality: VisualQuality, masks: MaskCache) {}

    /**
     * **每次切换开始时**调用一次（在 [prepare] 之后、本次切换的第一帧 [render] 之前）。
     *
     * 供「每次切换重新随机」的转场用 —— 例如 `WIPE_LINEAR` 的 8 个方向、
     * `SHAPE_RANDOM` 的形状。⚠️ 不要在 [prepare] 里做这件事：`prepare` 只在
     * **切换转场 / 切画质**时被调用，固定转场模式下连续多次切换不会重新调用它，
     * 方向就会永远不变。
     *
     * ⚠️ 仍然不要在这里分配（把缓冲留在 [prepare]）。
     */
    fun onSwapStart() {}

    /**
     * 绘制这一帧。
     *
     * @param p **已缓动**的进度 `0..1`（缓动在 `PhotoTransitionClock` 里做，转场只管画）
     */
    fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry)

    /** 释放本实例持有的资源（在切走转场 / 退出效果时调用） */
    fun release() {}
}
