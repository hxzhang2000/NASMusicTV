package com.nasmusic.tv.visualizer

import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.renderers.BloomRenderer
import com.nasmusic.tv.visualizer.renderers.BeatFireworkRenderer
import com.nasmusic.tv.visualizer.renderers.CircularNebulaRenderer
import com.nasmusic.tv.visualizer.renderers.CircularRingRenderer
import com.nasmusic.tv.visualizer.renderers.ConstellationRenderer
import com.nasmusic.tv.visualizer.renderers.FrequencyMountainRenderer
import com.nasmusic.tv.visualizer.renderers.GalaxySpiralRenderer
import com.nasmusic.tv.visualizer.renderers.KaleidoRenderer
import com.nasmusic.tv.visualizer.renderers.LiquidGridRenderer
import com.nasmusic.tv.visualizer.renderers.LiquidRippleRenderer
import com.nasmusic.tv.visualizer.renderers.MatrixRainRenderer
import com.nasmusic.tv.visualizer.renderers.MilkdropRenderer
import com.nasmusic.tv.visualizer.renderers.ParticleGalaxyRenderer
import com.nasmusic.tv.visualizer.renderers.ParticleStormRenderer
import com.nasmusic.tv.visualizer.renderers.ParticleTextRenderer
import com.nasmusic.tv.visualizer.renderers.PlasmaFlowRenderer
import com.nasmusic.tv.visualizer.renderers.RadialBurstRenderer
import com.nasmusic.tv.visualizer.renderers.WaterfallRenderer
import com.nasmusic.tv.visualizer.renderers.TerrainRenderer
import com.nasmusic.tv.visualizer.renderers.TunnelRenderer

/**
 * 渲染器工厂 —— 主题枚举 → 渲染器实现。
 *
 * 新增一套效果只需：① 新增 Renderer 实现；② 在此处加一个 when 分支。
 * 音频分析层零改动。
 */
object VisualizerRendererFactory {

    fun create(theme: VisualizerTheme): VisualizerRenderer = when (theme) {
        VisualizerTheme.IMMERSIVE_BLOOM -> BloomRenderer()
        VisualizerTheme.SONIC_TERRAIN -> TerrainRenderer()
        VisualizerTheme.TUNNEL_FLY -> TunnelRenderer()
        VisualizerTheme.CIRCULAR_NEBULA -> CircularNebulaRenderer()
        VisualizerTheme.CIRCULAR_RING -> CircularRingRenderer()
        VisualizerTheme.RADIAL_BURST -> RadialBurstRenderer()
        VisualizerTheme.FREQUENCY_MOUNTAIN -> FrequencyMountainRenderer()
        VisualizerTheme.PARTICLE_STORM -> ParticleStormRenderer()
        VisualizerTheme.PARTICLE_GALAXY -> ParticleGalaxyRenderer()
        VisualizerTheme.MIRROR_KALEIDO -> KaleidoRenderer()
        VisualizerTheme.GALAXY_SPIRAL -> GalaxySpiralRenderer()
        VisualizerTheme.SPECTRO_WATERFALL -> WaterfallRenderer()
        VisualizerTheme.LIQUID_GRID -> LiquidGridRenderer()
        VisualizerTheme.BEAT_FIREWORK -> BeatFireworkRenderer()
        VisualizerTheme.LIQUID_RIPPLE -> LiquidRippleRenderer()
        VisualizerTheme.MATRIX_RAIN -> MatrixRainRenderer()
        VisualizerTheme.CONSTELLATION -> ConstellationRenderer()
        VisualizerTheme.MILKDROP_FEEDBACK -> MilkdropRenderer()
        VisualizerTheme.PARTICLE_TEXT -> ParticleTextRenderer()
        VisualizerTheme.PLASMA_FLOW -> PlasmaFlowRenderer()
        // 自动导演：回落到默认效果，由 AutoDirector 决定实际主题
        VisualizerTheme.AUTO_DIRECTOR -> CircularRingRenderer()
    }

    /**
     * 在当前画质下实际可用的主题列表。
     * 用于指示器渲染与左右切换时的跳过逻辑。
     */
    fun availableThemes(quality: VisualQuality): List<VisualizerTheme> =
        VisualizerTheme.selectable.filter { quality.supports(it) }
}
