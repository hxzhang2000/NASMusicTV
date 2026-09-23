package com.nasmusic.tv.visualizer.photo.transitions

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import com.nasmusic.tv.visualizer.photo.PhotoGeometry
import com.nasmusic.tv.visualizer.photo.PhotoRect
import com.nasmusic.tv.visualizer.photo.PhotoTransition
import com.nasmusic.tv.visualizer.photo.PhotoTransitionId

/**
 * 缩放 / 深度类（§14.3 第 7–8 项）
 *
 * @param aFrom / [aTo] 旧图的缩放（围绕自身中心）
 * @param bFrom / [bTo] 新图的缩放
 *
 * 两者**方向相反**才是「推拉」的感觉：
 * - `ZOOM_IN`：旧图放大出画（1.00→1.15）+ 新图放大入画（0.85→1.00）
 * - `ZOOM_OUT`：旧图缩小（1.00→0.85）+ 新图缩小到位（1.15→1.00）
 *
 * ⚠️ 必须 `clipRect` 到画布：旧图放大后会**超出画布**，不裁剪就会画到相邻 UI 上
 * （`VisualizerStage` 的 Canvas 有 `padding`，越界内容会盖住 padding 区）。
 * 缩小的情况同理 —— 露出的画布边缘靠底层的暗底兜住，不会串到别的界面。
 *
 * ⚠️ 新图的 alpha 用 `p` 渐显（旧图保持不透明）⇒ 与 `CROSSFADE` 一样，全程无暗场。
 */
internal class ZoomTransition(
    override val id: PhotoTransitionId,
    private val aFrom: Float,
    private val aTo: Float,
    private val bFrom: Float,
    private val bTo: Float,
) : PhotoTransition {

    /** ⚠️ 两个独立实例：`scaleRect` 的输入输出不能是同一个对象（见其 KDoc） */
    private val tmpA = PhotoRect()
    private val tmpB = PhotoRect()

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        clipRect(0f, 0f, geom.canvasW, geom.canvasH) {
            scaleRect(geom.dstA, lerpF(aFrom, aTo, p), tmpA)
            drawPhoto(a, geom.srcA, tmpA)

            scaleRect(geom.dstB, lerpF(bFrom, bTo, p), tmpB)
            drawPhoto(b, geom.srcB, tmpB, alpha = p)
        }
    }
}
