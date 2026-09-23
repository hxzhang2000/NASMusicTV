package com.nasmusic.tv.visualizer.photo.transitions

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.nasmusic.tv.visualizer.photo.PhotoGeometry
import com.nasmusic.tv.visualizer.photo.PhotoTransition
import com.nasmusic.tv.visualizer.photo.PhotoTransitionId

/**
 * 滑动 / 位移类（§14.3 第 3–6 项，P0 的四个方向）
 *
 * 一个实现类 + 四个方向参数，而不是四个类 —— 四个方向的差别只有「新图从哪边进来」，
 * 分开写必然出现「三个改对了、一个忘了」。
 *
 * @param dirX 新图**入场方向**的 X 分量（`+1` = 从右侧进来）
 * @param dirY 同上，Y 分量（`+1` = 从下方进来）
 *
 * ⚠️ 旧图的位移是**相反方向**，且两者位移量必须相等 —— 否则会出现「中间一帧两张图重叠」
 * 或「中间一帧露出背景」。
 */
internal class SlideTransition(
    override val id: PhotoTransitionId,
    private val dirX: Float,
    private val dirY: Float,
) : PhotoTransition {

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        val spanX = geom.canvasW * dirX
        val spanY = geom.canvasH * dirY

        // 旧图：向入场方向的反方向滑出
        drawPhotoAt(a, geom.srcA, geom.dstA, offX = -spanX * p, offY = -spanY * p)
        // 新图：从入场方向外滑到原位
        val remain = 1f - p
        drawPhotoAt(b, geom.srcB, geom.dstB, offX = spanX * remain, offY = spanY * remain)
    }
}
