package com.nasmusic.tv.visualizer.fx

import com.nasmusic.tv.data.model.VisualQuality

/**
 * 后处理档位。
 *
 * ⛔ 刻意**不**给 [VisualQuality] 新增字段：该枚举有 7 个构造参数、被
 * `VisualizerThemeTest.kt:158`（`assertEquals(32, VisualQuality.LOW.barCount)`）等
 * 按名断言；且 `RenderContext.update(...)` 有 16 个位置参数与 3 个调用点
 * （`RenderContext.kt:47-51` 的 KDoc 明写这是刻意设计）。现有字段已足够区分三档。
 */
enum class FxLevel { OFF, LITE, FULL }

object FxBudget {

    /**
     * 由既有字段推导（源码依据 `data/model/AppSettings.kt:252-254`）：
     * ```
     * HIGH(barCount=64, glowLayers=3, trail=true,  maxParticles=350, …, allowFramebuffer=true)
     * MEDIUM(64, 2, true, 150, …, false)
     * LOW(32, 1, false, 0,  …, false)
     * ```
     */
    fun of(quality: VisualQuality): FxLevel = when {
        quality.allowFramebuffer -> FxLevel.FULL
        quality.glowLayers >= 2 -> FxLevel.LITE
        else -> FxLevel.OFF
    }
}
