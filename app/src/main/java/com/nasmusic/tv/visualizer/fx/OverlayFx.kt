package com.nasmusic.tv.visualizer.fx

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.nasmusic.tv.visualizer.RenderContext
import com.nasmusic.tv.visualizer.VisualizerMath

/**
 * **零离屏**叠加式后处理。全部为 `DrawScope` 扩展，在渲染器 `draw` 末尾调用。
 *
 * 四条约束：
 *  ① 单次调用 ≤ 3 个 draw 指令（暗角 1 / 颗粒 1 / 扫描线 1 / 纹理 1）；
 *  ② `Brush` 与 `Shader` 按 `(w,h)` 等完整键缓存（`Brush.radialGradient` 会分配，见 §15.4-A4；
 *     ⛔ **缓存键的维度必须 ⊇ 被缓存对象实际依赖的维度** —— 暗角颜色依赖 `palette.accent`，
 *     键里必须带它，否则切歌换色后仍是旧颜色的暗角）；
 *  ③ 档位 `OFF` 时**全部方法立即 return**（LOW 档零成本，由 `FxBudgetTest` 护住）；
 *  ④ ⚠️ 依赖主 Canvas 的 `CompositingStrategy.Offscreen`
 *     （`VisualizerStage.kt:305-306`）—— 否则 `Overlay`/`Plus` 在部分 API 版本
 *     退化为 `SrcOver`（见 §15.4-A11），⛔ 不得移除那两行。
 */
object OverlayFx {

    // 颗粒：8 个 tile 各一个平铺 Shader（tile 是 128×128 固定尺寸，与画布大小无关 ⇒ 一次性缓存）
    private val grainBrushes = arrayOfNulls<ShaderBrush>(ProceduralTexture.VARIANTS)

    // 扫描线：1×3 tile，同样与画布无关
    private var scanlineBrush: ShaderBrush? = null

    // 暗角：径向渐变，依赖 (w, h, accent 颜色, strength) ⇒ 全部进键
    private var vignetteBrush: Brush? = null
    private var vignetteKey = 0L

    /** 暗角：径向渐变，中心透明 → 边缘压暗 + 轻微偏冷（[coolShiftDeg]，8° = 全量） */
    fun DrawScope.drawVignette(
        ctx: RenderContext,
        strength: Float = 0.42f,
        coolShiftDeg: Float = 8f
    ) {
        if (FxBudget.of(ctx.quality) == FxLevel.OFF) return
        val key = (size.width.toLong() shl 32) or size.height.toLong() or
            (ctx.palette.accent.toArgb().toLong() shl 40) or
            ((strength.toRawBits().toLong() and 0xFFFFL) shl 8) or
            (coolShiftDeg.toRawBits().toLong() and 0xFFL)
        if (vignetteBrush == null || vignetteKey != key) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val edge0 = VisualizerMath.darken(ctx.palette.accent, 0.20f)
            // 轻微偏冷：R 压一点、B 提一点（程度由 coolShiftDeg/8 归一）
            val k = (coolShiftDeg / 8f).coerceIn(0f, 1f)
            val edge = Color(
                red = edge0.red * (1f - 0.15f * k),
                green = edge0.green * (1f - 0.05f * k),
                blue = (edge0.blue + 0.10f * k).coerceAtMost(1f),
                alpha = 1f
            ).copy(alpha = strength)
            vignetteBrush = Brush.radialGradient(
                0.62f to Color.Transparent,
                1.00f to edge,
                center = c,
                radius = size.minDimension * 0.72f
            )
            vignetteKey = key
        }
        drawRect(brush = vignetteBrush!!, topLeft = Offset.Zero, size = size)
    }

    /** 颗粒：`ProceduralTexture.tile(GRAIN, (seq and 7))` + `ImageShader` 平铺（逐帧换 tile） */
    fun DrawScope.drawGrain(ctx: RenderContext, seq: Long, intensity: Float = 0.030f) {
        if (FxBudget.of(ctx.quality) == FxLevel.OFF) return
        val bmp = ProceduralTexture.tile(ProceduralTexture.Id.GRAIN, (seq and 7L).toInt()) ?: return
        val v = (seq and 7L).toInt()
        var brush = grainBrushes[v]
        if (brush == null) {
            // ⚠️ ImageShader(image, tileModeX, tileModeY) —— 1.6.1 只有 3 个参数，
            //    没有 filterQuality（1.7+ 才有），见 §15.4-A2
            brush = ShaderBrush(ImageShader(bmp, TileMode.Repeated, TileMode.Repeated))
            grainBrushes[v] = brush
        }
        drawRect(
            brush = brush,
            topLeft = Offset.Zero,
            size = size,
            alpha = intensity,
            blendMode = BlendMode.Overlay   // ⛔ 依赖主 Canvas 的 Offscreen 合成策略
        )
    }

    /** 扫描线：`ProceduralTexture.tile(SCANLINE)` + `ImageShader` 平铺（替代 N 次 drawLine） */
    fun DrawScope.drawScanlines(ctx: RenderContext, dark: Float = 0.14f) {
        if (FxBudget.of(ctx.quality) == FxLevel.OFF) return
        val bmp = ProceduralTexture.tile(ProceduralTexture.Id.SCANLINE) ?: return
        var brush = scanlineBrush
        if (brush == null) {
            brush = ShaderBrush(ImageShader(bmp, TileMode.Repeated, TileMode.Repeated))
            scanlineBrush = brush
        }
        drawRect(brush = brush, topLeft = Offset.Zero, size = size, alpha = dark)
    }

    /** 通用纹理平铺：把 [ProceduralTexture] 的 tile/全屏纹理以给定 alpha 铺满画布 */
    fun DrawScope.drawTexture(
        ctx: RenderContext,
        id: ProceduralTexture.Id,
        alpha: Float,
        blendMode: BlendMode = BlendMode.SrcOver
    ) {
        if (FxBudget.of(ctx.quality) == FxLevel.OFF) return
        val bmp = ProceduralTexture.tile(id) ?: return
        when (id) {
            // 平铺型：走 ImageShader（与画布尺寸无关的固定 tile）
            ProceduralTexture.Id.GRAIN -> {
                var brush = grainBrushes[0]
                if (brush == null) {
                    brush = ShaderBrush(ImageShader(bmp, TileMode.Repeated, TileMode.Repeated))
                    grainBrushes[0] = brush
                }
                drawRect(brush = brush, topLeft = Offset.Zero, size = size, alpha = alpha, blendMode = blendMode)
            }
            ProceduralTexture.Id.SCANLINE -> {
                var brush = scanlineBrush
                if (brush == null) {
                    brush = ShaderBrush(ImageShader(bmp, TileMode.Repeated, TileMode.Repeated))
                    scanlineBrush = brush
                }
                drawRect(brush = brush, topLeft = Offset.Zero, size = size, alpha = alpha, blendMode = blendMode)
            }
            // 全屏型：与画布同尺寸，直接 drawImage
            else -> drawImage(
                image = bmp,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(bmp.width, bmp.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                alpha = alpha,
                blendMode = blendMode
            )
        }
    }

    /**
     * 曝光归一：`Plus` 叠加层数多时压低整体亮度，避免死白（§G8 / Reinhard 近似）。
     * 层数 < 3 时不补偿（直接 return，零成本）。
     */
    fun DrawScope.drawExposureCompensation(ctx: RenderContext, accumulatedLayers: Int) {
        if (FxBudget.of(ctx.quality) == FxLevel.OFF) return
        if (accumulatedLayers < 3) return
        val gain = 1f - 1f / accumulatedLayers          // ≈ 1/N 的压暗（Reinhard 一阶近似）
        drawRect(Color.Black, topLeft = Offset.Zero, size = size, alpha = gain.coerceIn(0f, 0.5f))
    }

    /** 释放内部缓存（`Brush` / `Shader`）。由舞台/基类调一次；⛔ 不回收位图（归 [ProceduralTexture] 管） */
    fun release() {
        for (i in grainBrushes.indices) grainBrushes[i] = null
        scanlineBrush = null
        vignetteBrush = null
        vignetteKey = 0L
    }
}
