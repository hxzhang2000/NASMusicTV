package com.nasmusic.tv.visualizer

import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.VisualizerTheme

/**
 * `AUTO_DIRECTOR` — 自动导演
 *
 * **不是第 21 套效果**，而是叠加在手动切换之上的一个调度档位：
 * 按音乐能量自动切换场景（前奏→主歌→副歌）。
 *
 * **必须加滞回**，否则能量在阈值附近抖动会导致疯狂跳变：
 *   - 最小驻留 8s
 *   - 升档 0.80 / 降档 0.65（回差）
 *   - 场景切换需交叉淡入（由 UI 层处理，本类只负责决策）
 */
class AutoDirector {

    private var current: VisualizerTheme = VisualizerTheme.CIRCULAR_RING
    private var lastSwitchMs = 0L

    /**
     * 评估当前应显示的主题。
     *
     * @return 应显示的主题；若处于驻留期内则返回当前主题
     */
    fun evaluate(frame: AudioFrame, quality: VisualQuality, nowMs: Long): VisualizerTheme {
        val e = frame.energy
        val target = when {
            e > UP_THRESHOLD && frame.beat -> pick(quality, EXPLOSION)
            e > MID_THRESHOLD -> pick(quality, TUNNEL)
            e < DOWN_THRESHOLD -> pick(quality, RING)
            else -> current
        }

        if (target != current && nowMs - lastSwitchMs >= MIN_DWELL_MS) {
            current = target
            lastSwitchMs = nowMs
        }
        return current
    }

    /** 在候选列表中挑一个当前画质支持的主题 */
    private fun pick(quality: VisualQuality, candidates: Array<VisualizerTheme>): VisualizerTheme =
        candidates.firstOrNull { quality.supports(it) } ?: VisualizerTheme.CIRCULAR_RING

    fun reset() {
        current = VisualizerTheme.CIRCULAR_RING
        lastSwitchMs = 0L
    }

    /** 当前主题（供 UI 显示效果名） */
    val active: VisualizerTheme get() = current

    private companion object {
        const val UP_THRESHOLD = 0.80f
        const val MID_THRESHOLD = 0.50f
        const val DOWN_THRESHOLD = 0.65f
        const val MIN_DWELL_MS = 8_000L

        val EXPLOSION = arrayOf(
            VisualizerTheme.BEAT_FIREWORK,
            VisualizerTheme.PARTICLE_GALAXY,
            VisualizerTheme.PARTICLE_STORM
        )
        val TUNNEL = arrayOf(
            VisualizerTheme.TUNNEL_FLY,
            VisualizerTheme.GALAXY_SPIRAL
        )
        val RING = arrayOf(
            VisualizerTheme.CIRCULAR_RING,
            VisualizerTheme.IMMERSIVE_BLOOM
        )
    }
}
