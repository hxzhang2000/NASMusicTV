package com.nasmusic.tv.visualizer

import androidx.compose.ui.graphics.Color

/**
 * 封面取色结果。
 *
 * Palette 未就绪或未取到时用 [fallback] 系列，保证任何时刻都能渲染。
 */
data class CoverPalette(
    val accent: Color,
    val secondary: Color,
    val background: Color
) {
    companion object {
        /** 未取色时的中性回落：青绿主色 + 深空背景 */
        val Fallback = CoverPalette(
            accent = Color(0xFF34D399),
            secondary = Color(0xFF60A5FA),
            background = Color(0xFF0A0F14)
        )
    }
}
