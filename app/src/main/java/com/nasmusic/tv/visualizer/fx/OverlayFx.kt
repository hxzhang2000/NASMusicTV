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

    /**
     * 暗角：径向渐变，中心透明 → 边缘压暗 + 轻微偏冷。
     *
     * [edgeOverride]（§11.3.6 P-2）：非 null 时**完全取代**封面 accent，并且**跳过** [coolShiftDeg]
     * —— 覆盖色是效果自己挑定的身份色，再套一层冷相偏移等于偷偷改它。
     * ⚠️ 默认 null ⇒ 其余 20 套效果逐像素不变。
     */
    fun DrawScope.drawVignette(
        ctx: RenderContext,
        strength: Float = 0.42f,
        coolShiftDeg: Float = 8f,
        edgeOverride: Color? = null
    ) {
        if (FxBudget.of(ctx.quality) == FxLevel.OFF) return
        val base = edgeOverride ?: VisualizerMath.darken(ctx.palette.accent, 0.20f)
        // 轻微偏冷：R 压一点、B 提一点（程度由 coolShiftDeg/8 归一）
        val k = if (edgeOverride == null) (coolShiftDeg / 8f).coerceIn(0f, 1f) else 0f
        val edge = Color(
            red = base.red * (1f - 0.15f * k),
            green = base.green * (1f - 0.05f * k),
            blue = (base.blue + 0.10f * k).coerceAtMost(1f),
            alpha = 1f
        ).copy(alpha = strength)
        // ⚠️ 键必须 ⊇ 全部依赖维度（§15.4-A4）。旧写法用 `shl 32` / `shl 40` 拼位段，
        //    而 accent 段（40..71）与 width 段（32..63）**重叠** ⇒ 不同 (w, accent) 组合会撞键、
        //    换歌后沿用旧颜色的暗角。改乘性混合，不再切位段。
        var key = size.width.toRawBits().toLong()
        key = key * 31 + size.height.toRawBits()
        key = key * 31 + edge.toArgb()
        key = key * 31 + strength.toRawBits()
        key = key * 31 + coolShiftDeg.toRawBits()
        if (vignetteBrush == null || vignetteKey != key) {
            val c = Offset(size.width / 2f, size.height / 2f)
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

    /**
     * **脏区合并占位绘制**（§11.3.6 P-3）。⚠️ 它**不是后处理**：不受画质档位、不受 [PostFx] 配置影响。
     *
     * 真机实测（创维 / API 22 / 1080p）：LOW 档 E16 数字雨每帧 **336 个互不相连的小 blit 矩形**、
     * 且本帧**没有任何一次全屏绘制** ⇒ 约 **0.6–1.0 s** 内原生 SIGSEGV
     * （`libui!android::Region::createTJunctionFreeRegion`，崩在 `RenderThread`，栈里无 app 帧）；
     * 同一效果 **MEDIUM 档 448 个矩形反而不崩** —— 暗角/颗粒/扫描线是 **3 次全屏 `drawRect`**，
     * 脏区被并成一整块矩形，系统那段"消除 T 型交叉"的碎矩形处理根本走不到。
     * ⇒ 本帧若无任何全屏绘制，就补一次**不可见**（alpha = 1/255，肉眼与截图均无差）的全屏 `drawRect`。
     *
     * ⛔ **必须是整画布矩形**：`topLeft` / `size` 任一写小、或把 alpha 写成 0 期待"空绘制"，
     *    脏区就不再被并掉，P-3 的闪退会立刻回来。调用条件由
     *    `RendererFx.needsDamageCoalescer` 决定（有全屏后处理项时不重复画，零额外开销）。
     */
    fun DrawScope.drawDamageCoalescer() {
        drawRect(
            Color.Black,
            topLeft = Offset.Zero,
            size = size,
            alpha = DAMAGE_COALESCER_ALPHA
        )
    }

    /** 释放内部缓存（`Brush` / `Shader`）。由舞台/基类调一次；⛔ 不回收位图（归 [ProceduralTexture] 管） */
    fun release() {
        for (i in grainBrushes.indices) grainBrushes[i] = null
        scanlineBrush = null
        vignetteBrush = null
        vignetteKey = 0L
    }
}

/** [OverlayFx.drawDamageCoalescer] 的不透明度：1/255 ⇒ 参与脏区计算但视觉不可辨（§11.3.6 P-3） */
private const val DAMAGE_COALESCER_ALPHA = 1f / 255f
