package com.nasmusic.tv.visualizer.photo.transitions

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.nasmusic.tv.visualizer.VisualizerRandom
import com.nasmusic.tv.visualizer.photo.PhotoGeometry
import com.nasmusic.tv.visualizer.photo.PhotoTransition
import com.nasmusic.tv.visualizer.photo.PhotoTransitionId

/**
 * 滑动 / 位移类 P1（§14.3）—— 与 P0 四方向滑动的差异在**旧图动不动**：
 *
 * | 效果 | 旧图 | 新图 | 特征 |
 * |---|---|---|---|
 * | [PushTransition] `PUSH` | 同向滑出 | 同向滑入 | 两图**无缝推挤**，边界始终贴合 |
 * | [CoverTransition] `COVER` | **不动** | 从边外滑入盖住 | 最省（旧图只画一次整幅） |
 * | [RevealTransition] `REVEAL` | 向边外滑出 | **不动**（垫底） | 揭示出底下已经放着的新图 |
 * | [DiagonalSlideTransition] `SLIDE_DIAGONAL` | 反向滑出 | 对角滑入 | 4 个对角方向**每次切换随机** |
 */
internal class PushTransition(
    private val dirX: Float,
    private val dirY: Float,
) : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.PUSH

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        // 推挤：两图位移完全同步（同一方向、同一位移量），边界贴合无缝
        val offX = geom.canvasW * dirX * p
        val offY = geom.canvasH * dirY * p
        drawPhotoAt(a, geom.srcA, geom.dstA, offX = -offX, offY = -offY)
        drawPhotoAt(b, geom.srcB, geom.dstB, offX = geom.canvasW * dirX - offX, offY = geom.canvasH * dirY - offY)
    }
}

internal class CoverTransition(
    private val dirX: Float,
    private val dirY: Float,
) : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.COVER

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)
        val remainX = (1f - p) * geom.canvasW * dirX
        val remainY = (1f - p) * geom.canvasH * dirY
        drawPhotoAt(b, geom.srcB, geom.dstB, offX = remainX, offY = remainY)
    }
}

internal class RevealTransition(
    private val dirX: Float,
    private val dirY: Float,
) : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.REVEAL

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        // 新图垫底（先画），旧图向边外滑出揭示它
        drawPhoto(b, geom.srcB, geom.dstB)
        drawPhotoAt(a, geom.srcA, geom.dstA, offX = -geom.canvasW * dirX * p, offY = -geom.canvasH * dirY * p)
    }
}

/**
 * 对角滑动：4 个对角方向**每次切换随机取 1**（同 `WIPE_LINEAR` 的「方向在 [onSwapStart] 抽」）。
 * 复用 [SlideTransition] 的画法，只是方向向量是对角的。
 */
internal class DiagonalSlideTransition(
    private val random: VisualizerRandom = VisualizerRandom(),
) : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.SLIDE_DIAGONAL

    /** 对角方向对（dirX, dirY）：↘ ↙ ↗ ↖ */
    private val dirs = arrayOf(
        floatArrayOf(1f, 1f),
        floatArrayOf(-1f, 1f),
        floatArrayOf(1f, -1f),
        floatArrayOf(-1f, -1f),
    )

    private var dir = 0

    override fun onSwapStart() {
        dir = (random.next() * dirs.size).toInt().coerceIn(0, dirs.size - 1)
    }

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        val dirX = dirs[dir][0]
        val dirY = dirs[dir][1]
        val spanX = geom.canvasW * dirX
        val spanY = geom.canvasH * dirY
        drawPhotoAt(a, geom.srcA, geom.dstA, offX = -spanX * p, offY = -spanY * p)
        val remain = 1f - p
        drawPhotoAt(b, geom.srcB, geom.dstB, offX = spanX * remain, offY = spanY * remain)
    }
}
