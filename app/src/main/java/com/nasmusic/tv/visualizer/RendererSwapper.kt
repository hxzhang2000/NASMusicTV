package com.nasmusic.tv.visualizer

import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.VisualizerTheme

/**
 * 渲染器交换器 —— 承载「自动导演」场景切换的 **600ms 交叉淡入**。
 *
 * 为什么需要单独一层：`AUTO_DIRECTOR` 每 8s 驻留后按能量换场景，
 * 若直接重建 renderer 就是**硬切**（旧效果瞬间消失、新效果瞬间铺满），
 * 在 10-foot 观看距离下非常突兀。开发方案 §4.21 明确要求「交叉淡入 600ms，禁止硬切」。
 *
 * **手动切换仍是硬切**（←/→、设置页选效果、切档位）——用户按键后需要即时反馈，
 * 600ms 淡入反而显得迟钝。
 *
 * 实现方式：UI 层同时持有 [current] / [previous] 两个渲染器，
 * 分别以 `t` / `1-t` 的透明度叠绘两层 Canvas；600ms 结束后释放 [previous]，
 * 避免长期双份绘制（粒子类是主要开销）。
 *
 * 本类**不触碰 Compose**（只依赖渲染器与 [RenderContext]），便于单测。
 */
class RendererSwapper(
    private val durationMs: Long = CROSSFADE_MS,
    private val factory: (VisualizerTheme) -> VisualizerRenderer = { VisualizerRendererFactory.create(it) }
) {

    /** 当前主题的渲染器（首次 [sync] 前为 null） */
    var current: VisualizerRenderer? = null
        private set

    /** 正在淡出的上一个渲染器（仅交叉淡入期间非 null） */
    var previous: VisualizerRenderer? = null
        private set

    /** 当前渲染器对应的主题 */
    var currentTheme: VisualizerTheme? = null
        private set

    /** 交叉淡入起始时刻（单调时钟 ms） */
    private var startMs = 0L

    /** 上一次进入 onEnter 时使用的画质档位 */
    private var lastQuality: VisualQuality? = null

    /** 是否处于交叉淡入过程中 */
    var isCrossfading: Boolean = false
        private set

    /** 新层透明度：0→1；非淡入期恒为 1 */
    fun currentAlpha(nowMs: Long): Float =
        if (!isCrossfading) 1f
        else ((nowMs - startMs).toFloat() / durationMs).coerceIn(0f, 1f)

    /** 旧层透明度：1→0；非淡入期恒为 0 */
    fun previousAlpha(nowMs: Long): Float =
        if (!isCrossfading) 0f else 1f - currentAlpha(nowMs)

    /**
     * 把渲染器同步到目标 (theme, quality)。
     *
     * - **主题变化**：创建新渲染器；[crossfade] 为 true 时保留旧渲染器淡出
     * - **仅画质变化**：对现有渲染器重新 [VisualizerRenderer.onEnter] ——
     *   多数效果（Terrain / LiquidGrid / MatrixRain / 粒子类）在 onEnter 里
     *   按 [VisualQuality] 预分配缓冲，不重进会导致缓冲尺寸与绘制参数不一致
     *
     * @return true 表示发生了实际变更（调用方需同步 UI 侧的透明度状态）
     */
    fun sync(
        theme: VisualizerTheme,
        quality: VisualQuality,
        crossfade: Boolean,
        ctx: RenderContext,
        nowMs: Long
    ): Boolean {
        if (theme == currentTheme) {
            if (quality == lastQuality) return false
            lastQuality = quality
            current?.onEnter(ctx)
            previous?.onEnter(ctx)
            return true
        }

        val old = current
        val fresh = factory(theme)
        fresh.onEnter(ctx)

        current = fresh
        currentTheme = theme
        lastQuality = quality

        if (crossfade && old != null) {
            // 极端情况下上一次淡入尚未结束就被新场景覆盖：直接丢弃更早的旧层
            previous?.onExit()
            previous = old
            startMs = nowMs
            isCrossfading = true
        } else {
            old?.onExit()
            previous = null
            isCrossfading = false
        }
        return true
    }

    /** 每帧推进：淡入结束即释放旧渲染器，避免长期双份绘制 */
    fun advance(nowMs: Long) {
        if (!isCrossfading) return
        if (nowMs - startMs < durationMs) return
        isCrossfading = false
        previous?.onExit()
        previous = null
    }

    /** 离开舞台：释放全部渲染器（含淡出中的旧层） */
    fun release() {
        current?.onExit()
        previous?.onExit()
        current = null
        previous = null
        currentTheme = null
        lastQuality = null
        isCrossfading = false
    }

    companion object {
        /** 交叉淡入时长（开发方案 §4.21：600ms，禁止硬切） */
        const val CROSSFADE_MS = 600L
    }
}
