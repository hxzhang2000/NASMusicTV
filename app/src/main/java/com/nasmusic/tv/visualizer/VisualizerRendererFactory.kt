package com.nasmusic.tv.visualizer

import android.content.Context
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.renderers.BeatFireworkRenderer
import com.nasmusic.tv.visualizer.renderers.CircularRingRenderer
import com.nasmusic.tv.visualizer.renderers.ConstellationRenderer
import com.nasmusic.tv.visualizer.renderers.FrequencyMountainRenderer
import com.nasmusic.tv.visualizer.renderers.GalaxySpiralRenderer
import com.nasmusic.tv.visualizer.renderers.LiquidGridRenderer
import com.nasmusic.tv.visualizer.renderers.LiquidRippleRenderer
import com.nasmusic.tv.visualizer.renderers.MatrixRainRenderer
import com.nasmusic.tv.visualizer.renderers.MilkdropRenderer
import com.nasmusic.tv.visualizer.renderers.ParticleTextRenderer
import com.nasmusic.tv.visualizer.renderers.PlasmaFlowRenderer
import com.nasmusic.tv.visualizer.renderers.LyricsDotMatrixRenderer
import com.nasmusic.tv.visualizer.renderers.WaterfallRenderer
import com.nasmusic.tv.visualizer.renderers.TunnelRenderer
import com.nasmusic.tv.visualizer.renderers.EcgWaveRenderer
import com.nasmusic.tv.visualizer.renderers.HypnoticFunctionRenderer
import com.nasmusic.tv.visualizer.renderers.OrbitalRingsRenderer
import com.nasmusic.tv.visualizer.renderers.RadarGridRenderer
import com.nasmusic.tv.visualizer.renderers.OrigamiPolyRenderer
import com.nasmusic.tv.visualizer.renderers.StaircaseWaveRenderer
import com.nasmusic.tv.visualizer.renderers.ConcentricGearsRenderer
import com.nasmusic.tv.visualizer.renderers.FractalTreeRenderer
import com.nasmusic.tv.visualizer.renderers.LightBeamsRenderer
import com.nasmusic.tv.visualizer.renderers.MoleculeRenderer
import com.nasmusic.tv.visualizer.renderers.VintageTvRenderer
import com.nasmusic.tv.visualizer.renderers.DnaRenderer
import com.nasmusic.tv.visualizer.renderers.StarrySkyRenderer
import com.nasmusic.tv.visualizer.renderers.WorldGlobeRenderer
import com.nasmusic.tv.visualizer.photo.PhotoRenderer

/**
 * 渲染器工厂 —— 主题枚举 → 渲染器实现。
 *
 * 新增一套效果只需：① 新增 Renderer 实现；② 在此处加一个 when 分支。
 * 音频分析层零改动。
 */
object VisualizerRendererFactory {

    /**
     * 主题枚举 → 渲染器实现。
     *
     * [context] 供 View 型渲染器（如 three-globe 的 WebView）创建 View 用；
     * 现有所有 Canvas 渲染器都忽略它，保持原实现。WORLD 分支已切到
     * 3D 版 [WorldGlobeRenderer]（WebView + three-globe）；旧 2D 版
     * [WorldRenderer] 保留在源码中、不再被本工厂引用（隐藏）。
     */
    fun create(theme: VisualizerTheme, context: Context): VisualizerRenderer = when (theme) {
        VisualizerTheme.TUNNEL_FLY -> TunnelRenderer()
        VisualizerTheme.CIRCULAR_RING -> CircularRingRenderer()
        VisualizerTheme.FREQUENCY_MOUNTAIN -> FrequencyMountainRenderer()
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
        VisualizerTheme.LYRICS_DOT_MATRIX -> LyricsDotMatrixRenderer()
        VisualizerTheme.ECG_WAVE -> EcgWaveRenderer()
        VisualizerTheme.HYPNOTIC_FUNCTION -> HypnoticFunctionRenderer()
        VisualizerTheme.ORBITAL_RINGS -> OrbitalRingsRenderer()
        VisualizerTheme.RADAR_GRID -> RadarGridRenderer()
        VisualizerTheme.ORIGAMI_POLY -> OrigamiPolyRenderer()
        VisualizerTheme.STAIRCASE_WAVE -> StaircaseWaveRenderer()
        VisualizerTheme.CONCENTRIC_GEARS -> ConcentricGearsRenderer()
        VisualizerTheme.FRACTAL_TREE -> FractalTreeRenderer()
        VisualizerTheme.LIGHT_BEAMS -> LightBeamsRenderer()
        VisualizerTheme.MOLECULE -> MoleculeRenderer()
        VisualizerTheme.VINTAGE_TV -> VintageTvRenderer()
        VisualizerTheme.PHOTO_WALL -> PhotoRenderer()
        VisualizerTheme.DNA -> DnaRenderer()
        VisualizerTheme.WORLD -> WorldGlobeRenderer(context)
        VisualizerTheme.STAR_TRAILS -> StarrySkyRenderer()
    }

    /**
     * 在当前画质下实际可用的主题列表。
     * 用于指示器渲染与左右切换时的跳过逻辑。
     *
     * @param photoWallAvailable 三来源开关之「或」（§7.4）。三关 ⇒ [VisualizerTheme.PHOTO_WALL] 不出现。
     */
    fun availableThemes(quality: VisualQuality, photoWallAvailable: Boolean): List<VisualizerTheme> =
        VisualizerTheme.selectable(photoWallAvailable).filter { quality.supports(it) }
}
