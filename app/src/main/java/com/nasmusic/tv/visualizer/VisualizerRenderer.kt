package com.nasmusic.tv.visualizer

import androidx.compose.ui.graphics.drawscope.DrawScope
import com.nasmusic.tv.data.model.VisualizerTheme

/**
 * 可视化效果渲染器（可插拔）。
 *
 * 新增一套效果 = 新增一个实现类 + 一个枚举值，音频分析层零改动。
 *
 * **性能红线**：[draw] 内禁止任何对象分配（Paint / Path / Color 提到成员变量）。
 */
interface VisualizerRenderer {

    val theme: VisualizerTheme

    /** 进入效果：分配缓冲、重置状态 */
    fun onEnter(ctx: RenderContext) {}

    /** 每帧绘制。[frame] 为复用单例，不得跨帧持有 */
    fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext)

    /** 退出：释放 ImageBitmap 等重资源 */
    fun onExit() {}
}
