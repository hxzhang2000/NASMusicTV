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
        /**
         * 未取色时的中性回落：亮蓝 + 亮黄 + 深空黑。
         * 亮蓝主色 + 亮黄副色 + 深空黑背景，高饱和、明亮、黑底对比鲜明。
         */
        val Fallback = CoverPalette(
            accent = Color(0xFF00BFFF),     // 亮蓝（主色）
            secondary = Color(0xFFFFFF4D),  // 亮黄（副色）
            background = Color(0xFF070716)  // 深空黑
        )
    }
}
