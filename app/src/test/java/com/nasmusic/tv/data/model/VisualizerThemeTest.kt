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
        assertEquals(VisualizerTheme.CIRCULAR_RING, VisualizerTheme.fromKey("NEON_PULSE"))
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
        assertEquals(21, VisualizerTheme.entries.size)
    }

    @Test
    fun `selectable list contains every theme when the photo wall is available`() {
        val selectable = VisualizerTheme.selectable(photoWallAvailable = true)
        assertEquals(21, selectable.size)
        assertEquals(21, selectable.distinct().size)
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
        assertEquals(20, off.size)
        assertEquals(21, on.size)
    }

    @Test
    fun `ordinal labels are unique`() {
        val labels = VisualizerTheme.entries.map { it.ordinalLabel }
        assertEquals(21, labels.distinct().size)
    }

    @Test
    fun `display names are non blank and unique`() {
        val names = VisualizerTheme.entries.map { it.displayName }
        assertTrue(names.none { it.isBlank() })
        assertEquals(21, names.distinct().size)
    }

    @Test
    fun `quality tiers gate advanced and ultra effects`() {
        // BASIC 三档全支持
        VisualQuality.entries.forEach { q ->
            assertTrue("$q should support BASIC", q.supports(VisualizerTheme.CIRCULAR_RING))
            assertTrue("$q should support HYPNOTIC_FUNCTION", q.supports(VisualizerTheme.HYPNOTIC_FUNCTION))
        }

        // ADV：只有真消耗粒子预算的效果才需要预算（2026-10-01 用户裁决，方案 C）。
        // 液态网格的渲染器只读 barCount ⇒ LOW 必须可选。
        // ⚠️ 2026-10-05：原样本是频谱瀑布（`SPECTRO_WATERFALL`），随效果删除 ⇒ 换成同为
        //   `Tier.ADV` 且 `needsParticleBudget = false` 的 `LIQUID_GRID`，判据语义不变。
        assertTrue(VisualQuality.LOW.supports(VisualizerTheme.LIQUID_GRID))
        assertTrue(VisualQuality.MEDIUM.supports(VisualizerTheme.LIQUID_GRID))
        assertTrue(VisualQuality.HIGH.supports(VisualizerTheme.LIQUID_GRID))

        // 节拍烟花 / 世界的渲染器真读 ctx.quality.maxParticles ⇒ LOW 的 0 预算仍须挡住
        assertFalse(VisualQuality.LOW.supports(VisualizerTheme.BEAT_FIREWORK))
        assertFalse(VisualQuality.LOW.supports(VisualizerTheme.WORLD))
        assertTrue(VisualQuality.MEDIUM.supports(VisualizerTheme.BEAT_FIREWORK))
        // ⛔ 2026-10-07 所有者裁决：E41「世界」由 `Tier.ADV` 提到 `Tier.ULTRA`
        //   （该机 WebView 只能画首帧，见 WorldRender 的 KDoc 与方案文档 §2.2）
        //   ⇒ **MEDIUM 也不再提供**，原先的 `assertTrue(MEDIUM.supports(WORLD))` 作废。
        //   仍保留这条显式断言而不是删掉：它是本裁决的**正向自证**，
        //   防止日后有人把 WORLD 悄悄降回 ADV。
        assertFalse(
            "E41 由 ULTRA 门控后 MEDIUM 不应再提供（2026-10-07 裁决）",
            VisualQuality.MEDIUM.supports(VisualizerTheme.WORLD)
        )
        assertTrue(VisualQuality.HIGH.supports(VisualizerTheme.WORLD))
        // ⛔ needsParticleBudget 仍须为 true：渲染器确实读 maxParticles，
        //   `ParticleBudgetGateTest` 用源码扫描反推真值集合，标错就会红。
        assertTrue(VisualizerTheme.WORLD.needsParticleBudget)

        // ULTRA 需要帧缓冲：只有 HIGH 允许（与本条裁决无关，一字未动）
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
     * LOW 档降级兜住：RGB_565 + 长边 1280）。
     *
     * ⚠️ 本用例原名「…while other advanced effects still require particles」，
     * 2026-10-01 的"方案 C"把这条**特例**升级成 ADV 档的**通则**
     * （ADV 门槛 = 该效果是否真读 `maxParticles`）⇒ 照片墙不再需要例外分支，
     * 后半段"其余 ADV 仍要求预算"的断言**已作废**，改由 [ParticleBudgetGateTest]
     * 用源码扫描判定"谁真的读预算"。
     */
    @Test
    fun `photo wall is offered on every tier`() {
        assertEquals(VisualizerTheme.Tier.ADV, VisualizerTheme.PHOTO_WALL.tier)
        assertTrue(
            "LOW 档也应提供照片墙（2026-09-23 用户裁决；内存风险由解码降级兜住）",
            VisualQuality.LOW.supports(VisualizerTheme.PHOTO_WALL)
        )
        assertTrue(VisualQuality.MEDIUM.supports(VisualizerTheme.PHOTO_WALL))
        assertTrue(VisualQuality.HIGH.supports(VisualizerTheme.PHOTO_WALL))

        // 放宽靠的是「照片墙不读预算」这条事实，不再靠 supports() 里的特例分支
        assertFalse(VisualizerTheme.PHOTO_WALL.needsParticleBudget)
        assertFalse(
            "真读预算的效果（节拍烟花）在 LOW 档仍然必须被挡",
            VisualQuality.LOW.supports(VisualizerTheme.BEAT_FIREWORK)
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
