package com.nasmusic.tv.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VisualizerTheme / VisualQuality 枚举契约测试。
 *
 * 重点覆盖 BUG ⑮：老用户 DataStore 里存的是 `COLOR_FLOW` / `NEON_PULSE` /
 * `CLASSICAL_WAVE` / `SONIC_TERRAIN` / `CIRCULAR_NEBULA` 五个旧枚举名，
 * 新 34 值枚举必须平滑迁移而不是回落默认值，否则升级后主题静默变样。
 */
class VisualizerThemeTest {

    @Test
    fun `legacy enum names migrate to the new themes`() {
        assertEquals(VisualizerTheme.CIRCULAR_RING, VisualizerTheme.fromKey("COLOR_FLOW"))
        assertEquals(VisualizerTheme.IMMERSIVE_BLOOM, VisualizerTheme.fromKey("NEON_PULSE"))
        assertEquals(VisualizerTheme.CIRCULAR_RING, VisualizerTheme.fromKey("CLASSICAL_WAVE"))
        assertEquals(VisualizerTheme.CIRCULAR_RING, VisualizerTheme.fromKey("SONIC_TERRAIN"))
        assertEquals(VisualizerTheme.CIRCULAR_RING, VisualizerTheme.fromKey("CIRCULAR_NEBULA"))
    }

    @Test
    fun `legacy lookup is case insensitive`() {
        assertEquals(VisualizerTheme.CIRCULAR_RING, VisualizerTheme.fromKey("color_flow"))
        assertEquals(VisualizerTheme.CIRCULAR_RING, VisualizerTheme.fromKey("Color_Flow"))
    }

    @Test
    fun `exact enum name resolves`() {
        VisualizerTheme.entries.forEach { t ->
            assertEquals(t, VisualizerTheme.fromKey(t.name))
        }
    }

    @Test
    fun `null and unknown keys fall back to the default`() {
        assertEquals(VisualizerTheme.CIRCULAR_RING, VisualizerTheme.fromKey(null))
        assertEquals(VisualizerTheme.CIRCULAR_RING, VisualizerTheme.fromKey(""))
        assertEquals(VisualizerTheme.CIRCULAR_RING, VisualizerTheme.fromKey("NO_SUCH_THEME"))
        assertEquals(VisualizerTheme.CIRCULAR_RING, VisualizerTheme.Default)
    }

    @Test
    fun `theme library is all concrete effects, no auto mode`() {
        assertEquals(35, VisualizerTheme.entries.size)
    }

    @Test
    fun `selectable list equals all themes, no auto mode`() {
        val selectable = VisualizerTheme.selectable
        assertEquals(35, selectable.size)
        assertEquals(35, selectable.distinct().size)
    }

    @Test
    fun `ordinal labels are unique`() {
        val labels = VisualizerTheme.entries.map { it.ordinalLabel }
        assertEquals(35, labels.distinct().size)
    }

    @Test
    fun `display names are non blank and unique`() {
        val names = VisualizerTheme.entries.map { it.displayName }
        assertTrue(names.none { it.isBlank() })
        assertEquals(35, names.distinct().size)
    }

    @Test
    fun `quality tiers gate advanced and ultra effects`() {
        // BASIC 三档全支持
        VisualQuality.entries.forEach { q ->
            assertTrue("$q should support BASIC", q.supports(VisualizerTheme.IMMERSIVE_BLOOM))
            assertTrue("$q should support HYPNOTIC_FUNCTION", q.supports(VisualizerTheme.HYPNOTIC_FUNCTION))
        }

        // ADV 需要粒子预算：LOW 的 maxParticles = 0 → 禁用
        assertFalse(VisualQuality.LOW.supports(VisualizerTheme.PARTICLE_STORM))
        assertTrue(VisualQuality.MEDIUM.supports(VisualizerTheme.PARTICLE_STORM))
        assertTrue(VisualQuality.HIGH.supports(VisualizerTheme.PARTICLE_STORM))

        // ULTRA 需要帧缓冲：只有 HIGH 允许
        assertFalse(VisualQuality.LOW.supports(VisualizerTheme.MILKDROP_FEEDBACK))
        assertFalse(VisualQuality.MEDIUM.supports(VisualizerTheme.MILKDROP_FEEDBACK))
        assertTrue(VisualQuality.HIGH.supports(VisualizerTheme.MILKDROP_FEEDBACK))
    }

    @Test
    fun `quality parameters shrink with the tier`() {
        assertEquals(64, VisualQuality.HIGH.barCount)
        assertEquals(64, VisualQuality.MEDIUM.barCount)
        assertEquals(32, VisualQuality.LOW.barCount)

        assertEquals(0, VisualQuality.LOW.maxParticles)
        assertTrue(VisualQuality.MEDIUM.maxParticles in 1..VisualQuality.HIGH.maxParticles)
        assertTrue(VisualQuality.MEDIUM.gridCols < VisualQuality.HIGH.gridCols)

        assertFalse(VisualQuality.MEDIUM.allowFramebuffer)
        assertTrue(VisualQuality.HIGH.allowFramebuffer)
    }

    @Test
    fun `quality fromKey is case insensitive and falls back to default`() {
        assertEquals(VisualQuality.HIGH, VisualQuality.fromKey("HIGH"))
        assertEquals(VisualQuality.HIGH, VisualQuality.fromKey("high"))
        assertEquals(VisualQuality.Default, VisualQuality.fromKey(null))
        assertEquals(VisualQuality.Default, VisualQuality.fromKey("NOPE"))
        assertEquals(VisualQuality.MEDIUM, VisualQuality.Default)
    }
}
