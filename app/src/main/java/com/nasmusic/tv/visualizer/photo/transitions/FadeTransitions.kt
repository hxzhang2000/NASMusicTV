package com.nasmusic.tv.visualizer.photo.transitions

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.nasmusic.tv.visualizer.photo.PhotoGeometry
import com.nasmusic.tv.visualizer.photo.PhotoTransition
import com.nasmusic.tv.visualizer.photo.PhotoTransitionId

/**
 * 淡化类（§14.3 第 1–2 项）
 *
 * 两种的**模型不同**，这是最容易被忽略的差异：
 * - [CrossfadeTransition] 走**交叉**（旧图不透明垫底，新图逐渐叠上）⇒ 全程无暗场
 * - [FadeBlackTransition] 走**串行**（旧图淡到黑，新图再从黑淡出）⇒ 中途有全黑瞬间
 *
 * ⚠️ `CROSSFADE` 的正确画法是「旧图 alpha 恒为 1，新图 alpha = p」，
 * **不是**「旧图 1−p + 新图 p」—— 后者在 p=0.5 时两张都是半透明，
 * 底下的暗背景会透出来，看起来像「暗了一下」（明明没选经黑场却出现暗场）。
 */
internal class CrossfadeTransition : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.CROSSFADE

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)
        drawPhoto(b, geom.srcB, geom.dstB, alpha = p)
    }
}

/** 经黑场（串行模型：`p < 0.5` 旧图淡出到黑，`p >= 0.5` 新图从黑淡入） */
internal class FadeBlackTransition : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.FADE_BLACK

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        if (p < HALF) {
            // 前半：旧图渐隐（底层是 PhotoRenderer 的暗底 ⇒ 视觉上「淡到黑」）
            drawPhoto(a, geom.srcA, geom.dstA, alpha = 1f - p * 2f)
        } else {
            // 后半：新图渐显
            drawPhoto(b, geom.srcB, geom.dstB, alpha = (p - HALF) * 2f)
        }
    }

    private companion object {
        const val HALF = 0.5f
    }
}
