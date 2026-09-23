package com.nasmusic.tv.visualizer.photo.transitions

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.visualizer.photo.PhotoGeometry
import com.nasmusic.tv.visualizer.photo.PhotoTransition
import com.nasmusic.tv.visualizer.photo.PhotoTransitionId

/**
 * 淡化类 P1（§14.3）
 *
 * - [FadeWhiteTransition]：白闪（**串行**，与 [FadeBlackTransition] 同族，只是「中场」是白）
 * - [FadeColorTransition]：主题色过渡（交叉 + 主题色纱幕在中点最浓）
 * - [ExposureFlashTransition]：曝光闪白（交叉 + 白色曝光叠加）
 */
internal class FadeWhiteTransition : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.FADE_WHITE

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        // 纱幕浓度 = 1 − |2p − 1|：中点全白，两端全无
        val veil = VEIL_ALPHA * (1f - kotlin.math.abs(p * 2f - 1f))
        if (p < HALF) {
            drawPhoto(a, geom.srcA, geom.dstA, alpha = 1f - p * 2f)
        } else {
            drawPhoto(b, geom.srcB, geom.dstB, alpha = (p - HALF) * 2f)
        }
        if (veil > 0.01f) {
            drawRect(
                color = Color.White.copy(alpha = veil),
                topLeft = Offset.Zero,
                size = Size(geom.canvasW, geom.canvasH),
            )
        }
    }

    private companion object {
        const val HALF = 0.5f

        /** 全白会让中点变成「瞎一帧」，压到 0.85 保留一点轮廓感 */
        const val VEIL_ALPHA = 0.85f
    }
}

/**
 * 主题色过渡：交叉淡化 + 主题色纱幕（浓度在中点最浓）。
 *
 * ⚠️ 实现期偏差：`RenderContext` / [PhotoGeometry] 都没有「当前主题色」这个输入
 * （§14.2.3 的签名里没有），为了不为此改全部转场的接口，纱幕用**固定的品牌色**
 * [NasMusicColors.Primary]（静态值，非绘制路径分配）。
 */
internal class FadeColorTransition : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.FADE_COLOR

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)
        drawPhoto(b, geom.srcB, geom.dstB, alpha = p)

        val veil = VEIL_ALPHA * (1f - kotlin.math.abs(p * 2f - 1f))
        if (veil > 0.01f) {
            drawRect(
                color = NasMusicColors.Primary.copy(alpha = veil),
                topLeft = Offset.Zero,
                size = Size(geom.canvasW, geom.canvasH),
            )
        }
    }

    private companion object {
        const val VEIL_ALPHA = 0.55f
    }
}

/**
 * 曝光闪白：交叉淡化 + 白色「曝光」叠加（与白闪的区别：这里**画面始终可见**，
 * 白只是叠加在上面的过曝感；串行白闪的中点是完全看不见照片的）。
 */
internal class ExposureFlashTransition : PhotoTransition {

    override val id: PhotoTransitionId = PhotoTransitionId.EXPOSURE_FLASH

    override fun DrawScope.render(a: ImageBitmap, b: ImageBitmap, p: Float, geom: PhotoGeometry) {
        drawPhoto(a, geom.srcA, geom.dstA)
        drawPhoto(b, geom.srcB, geom.dstB, alpha = p)

        // 过曝曲线：4p(1−p) 在中点达峰 1
        val flash = FLASH_ALPHA * 4f * p * (1f - p)
        if (flash > 0.01f) {
            drawRect(
                color = Color.White.copy(alpha = flash),
                topLeft = Offset.Zero,
                size = Size(geom.canvasW, geom.canvasH),
            )
        }
    }

    private companion object {
        const val FLASH_ALPHA = 0.65f
    }
}
