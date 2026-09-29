package com.nasmusic.tv.visualizer

import android.content.Context
import android.view.View
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.nasmusic.tv.data.model.VisualizerTheme

/**
 * 可视化效果渲染器（可插拔）。
 *
 * 新增一套效果 = 新增一个实现类 + 一个枚举值，音频分析层零改动。
 *
 * **性能红线**：[draw] 内禁止任何对象分配（Paint / Path / Color 提到成员变量）。
 *
 * **View 型渲染器旁路**：渲染器可以不走 [draw]（DrawScope，主线程每帧重绘），
 * 而是返回一个 [View]（如 three-globe 的 WebView）由舞台用 `AndroidView` 承载。
 * 旁路开启后 [draw] **不再被调用**，舞台改为托管 [createView] 的返回值，
 * 并用 [onViewAttached] / [onViewDetached] 驱动 View 的启动 / 停止生命周期。
 */
interface VisualizerRenderer {

    val theme: VisualizerTheme

    /** 进入效果：分配缓冲、重置状态 */
    fun onEnter(ctx: RenderContext) {}

    /** 每帧绘制。[frame] 为复用单例，不得跨帧持有 */
    fun DrawScope.draw(frame: AudioFrame, ctx: RenderContext)

    /** 退出：释放 ImageBitmap 等重资源 */
    fun onExit() {}

    /** View 型渲染器旁路：true 时舞台用 AndroidView 承载，draw 不再被调用 */
    val isViewBased: Boolean get() = false

    /** isViewBased=true 时返回要嵌入的 View；Compose 首次组合时在主线程调用一次 */
    fun createView(context: Context): View? = null

    /** View 已 attach 到舞台（可启动定时器/加载数据） */
    fun onViewAttached() {}

    /** View 即将 detach（停止定时器/释放） */
    fun onViewDetached() {}
}
