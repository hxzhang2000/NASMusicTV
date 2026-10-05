package com.nasmusic.tv.visualizer

import android.content.Context
import com.nasmusic.tv.data.model.VisualQuality
import com.nasmusic.tv.data.model.VisualizerTheme
import com.nasmusic.tv.visualizer.renderers.BeatFireworkRenderer
import com.nasmusic.tv.visualizer.renderers.CircularRingRenderer
import com.nasmusic.tv.visualizer.renderers.ConstellationRenderer
import com.nasmusic.tv.visualizer.renderers.LiquidGridRenderer
import com.nasmusic.tv.visualizer.renderers.LiquidRippleRenderer
import com.nasmusic.tv.visualizer.renderers.MatrixRainRenderer
import com.nasmusic.tv.visualizer.renderers.MilkdropRenderer
import com.nasmusic.tv.visualizer.renderers.LyricsDotMatrixRenderer
import com.nasmusic.tv.visualizer.renderers.EcgWaveRenderer
import com.nasmusic.tv.visualizer.renderers.HypnoticFunctionRenderer
import com.nasmusic.tv.visualizer.renderers.OrbitalRingsRenderer
import com.nasmusic.tv.visualizer.renderers.RadarGridRenderer
import com.nasmusic.tv.visualizer.renderers.SeasideRenderer
import com.nasmusic.tv.visualizer.renderers.ConcentricGearsRenderer
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
     * WorldRenderer 已整文件删除归档（M11 修复，2026-10-06，原 2106 行零实例化死代码）。
     */
    fun create(theme: VisualizerTheme, context: Context): VisualizerRenderer = when (theme) {
        VisualizerTheme.CIRCULAR_RING -> CircularRingRenderer()
        VisualizerTheme.LIQUID_GRID -> LiquidGridRenderer()
        VisualizerTheme.BEAT_FIREWORK -> BeatFireworkRenderer()
        VisualizerTheme.LIQUID_RIPPLE -> LiquidRippleRenderer()
        VisualizerTheme.MATRIX_RAIN -> MatrixRainRenderer()
        VisualizerTheme.CONSTELLATION -> ConstellationRenderer()
        VisualizerTheme.MILKDROP_FEEDBACK -> MilkdropRenderer()
        VisualizerTheme.LYRICS_DOT_MATRIX -> LyricsDotMatrixRenderer()
        VisualizerTheme.ECG_WAVE -> EcgWaveRenderer()
        VisualizerTheme.HYPNOTIC_FUNCTION -> HypnoticFunctionRenderer()
        VisualizerTheme.ORBITAL_RINGS -> OrbitalRingsRenderer()
        VisualizerTheme.RADAR_GRID -> RadarGridRenderer()
        VisualizerTheme.CONCENTRIC_GEARS -> ConcentricGearsRenderer()
        VisualizerTheme.LIGHT_BEAMS -> LightBeamsRenderer()
        VisualizerTheme.MOLECULE -> MoleculeRenderer()
        VisualizerTheme.VINTAGE_TV -> VintageTvRenderer()
        VisualizerTheme.PHOTO_WALL -> PhotoRenderer()
        VisualizerTheme.DNA -> DnaRenderer()
        VisualizerTheme.WORLD -> WorldGlobeRenderer(context)
        VisualizerTheme.STAR_TRAILS -> StarrySkyRenderer()
        VisualizerTheme.SEASIDE -> SeasideRenderer()
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
