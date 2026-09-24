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
 * 新枚举必须平滑迁移而不是回落默认值，否则升级后主题静默变样。
 *
 * 另含**门禁 G7**（§14.4）：`PHOTO_WALL` 的可见性由三来源开关派生（§7.4）。
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
        assertEquals(36, VisualizerTheme.entries.size)
    }

    @Test
    fun `selectable list contains every theme when the photo wall is available`() {
        val selectable = VisualizerTheme.selectable(photoWallAvailable = true)
        assertEquals(36, selectable.size)
        assertEquals(36, selectable.distinct().size)
        assertTrue(selectable.contains(VisualizerTheme.PHOTO_WALL))
    }

    /**
     * 门禁 G7（§14.4）—— `PHOTO_WALL` 的可见性由三来源开关**派生**（§7.4）。
     *
     * ⛔ 这条必须**两个分支都测**：只测「关时不含」的话，一个「永远过滤掉 `PHOTO_WALL`」
     * 的恒假实现也能通过；只测「开时含」则漏掉「三关时仍出现」。两条一起才有判别力。
     */
    @Test
    fun `photo wall visibility is derived from the three source switches`() {
        val off = VisualizerTheme.selectable(photoWallAvailable = false)
        val on = VisualizerTheme.selectable(photoWallAvailable = true)

        assertFalse(
            "三来源全关时不应出现 PHOTO_WALL",
            off.contains(VisualizerTheme.PHOTO_WALL)
        )
        assertTrue(
            "至少一个来源开启时 PHOTO_WALL 必须在列表里",
            on.contains(VisualizerTheme.PHOTO_WALL)
        )

        // 两份列表应当**只差这一项** —— 防止日后顺手多过滤 / 少过滤
        // ⚠️ JUnit 4 的 `assertEquals` 是**消息在前**（与 JUnit 5 相反）
        assertEquals(
            "开 / 关两份列表应当只差 PHOTO_WALL 一项",
            listOf(VisualizerTheme.PHOTO_WALL),
            on.filter { it !in off.toSet() }
        )
        assertEquals(35, off.size)
        assertEquals(36, on.size)
    }

    @Test
    fun `ordinal labels are unique`() {
        val labels = VisualizerTheme.entries.map { it.ordinalLabel }
        assertEquals(36, labels.distinct().size)
    }

    @Test
    fun `display names are non blank and unique`() {
        val names = VisualizerTheme.entries.map { it.displayName }
        assertTrue(names.none { it.isBlank() })
        assertEquals(36, names.distinct().size)
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

    /**
     * 照片墙的画质档可用性（⚠️ 2026-09-23 用户裁决**放宽**后反转）。
     *
     * 历史背景：`PHOTO_WALL` 曾被 `maxParticles > 0` 门控挡在 LOW 档之外
     * （本用例当时叫「photo wall is an advanced effect so the low tier cannot offer it」，
     * 用意就是「将来若放宽，本用例变红逼改动者回来显式更新」—— 它如愿变红了）。
     *
     * 裁决结果：照片墙**不走粒子预算门控**（没有粒子，内存由 `PhotoBuffer` 的
     * LOW 档降级兜住：RGB_565 + 长边 1280），但**其余 ADV 效果仍然要求粒子预算** ——
     * 后半段断言防止放宽时顺手把整个 ADV 门控拆掉。
     */
    @Test
    fun `photo wall is offered on every tier while other advanced effects still require particles`() {
        assertEquals(VisualizerTheme.Tier.ADV, VisualizerTheme.PHOTO_WALL.tier)
        assertTrue(
            "LOW 档也应提供照片墙（2026-09-23 用户裁决；内存风险由解码降级兜住）",
            VisualQuality.LOW.supports(VisualizerTheme.PHOTO_WALL)
        )
        assertTrue(VisualQuality.MEDIUM.supports(VisualizerTheme.PHOTO_WALL))
        assertTrue(VisualQuality.HIGH.supports(VisualizerTheme.PHOTO_WALL))

        // ⛔ 放宽只限照片墙：粒子类 ADV 在 LOW 档仍然必须被挡
        assertFalse(
            "放宽不得波及其他 ADV 效果（LOW 的 maxParticles 仍为 0）",
            VisualQuality.LOW.supports(VisualizerTheme.PARTICLE_STORM)
        )
        assertFalse(VisualQuality.LOW.supports(VisualizerTheme.MILKDROP_FEEDBACK))
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
