package com.nasmusic.tv.data.model

import com.nasmusic.tv.visualizer.VisualizerRendererFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 门禁 · 粒子预算门控（2026-10-01 用户裁决，方案 C）。
 *
 * `VisualQuality.supports()` 对 `Tier.ADV` 的门槛是「该效果**是否真的读取**
 * `ctx.quality.maxParticles`」，由 [VisualizerTheme.needsParticleBudget] 承载。
 *
 * ⚠️ 该字段是**可核对的事实**，不是观感分类 ⇒ 必须与源码同步：
 * 本用例扫描 `renderers/` 与 `photo/`，按「工厂映射 → 渲染器类 → 类体内是否读预算」
 * 反推真值集合，与枚举标注逐一对比。
 * 漏标注 ⇒ 新粒子效果在低画质被放行（老设备卡顿 / 崩溃）；
 * 错标注 ⇒ 不耗预算的效果被挡在低画质之外（即本次裁决要修的「能渲染却不可选」）。
 */
class ParticleBudgetGateTest {

    private fun visualizerRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(6) {
            val cand = File(dir, "app/src/main/java/com/nasmusic/tv/visualizer")
            if (cand.isDirectory) return cand
            dir = dir.parentFile ?: return@repeat
        }
        error("找不到 visualizer 源码根：user.dir = ${System.getProperty("user.dir")}")
    }

    /** 行注释 / KDoc 里的 `maxParticles` 不算读取：截掉 `//` 之后的内容，丢掉 `*` 开头的续行。 */
    private fun codeLine(line: String): String? {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("*")) return null
        val cut = line.indexOf("//")
        val code = if (cut >= 0) line.substring(0, cut) else line
        return if (code.isBlank()) null else code
    }

    private fun readsBudget(line: String): Boolean =
        Regex("""(?<![A-Za-z0-9_])maxParticles\b""").containsMatchIn(line) &&
            // 只认「读」：声明形如 `val maxParticles: Int,` 的构造参数行不算
            !Regex("""^\s*(val|var)\s+maxParticles\b""").containsMatchIn(line)

    /** 工厂映射：`VisualizerTheme.X -> YRenderer(…)` ⇒ X → YRenderer */
    private fun themeToRendererClass(): Map<String, String> {
        val src = File(visualizerRoot(), "VisualizerRendererFactory.kt").readText()
        return Regex("""VisualizerTheme\.([A-Z_0-9]+)\s*->\s*([A-Za-z0-9_]+)\s*\(""")
            .findAll(src).associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** 扫描 renderers/ 与 photo/，返回「类体内真读了 maxParticles」的渲染器类名集合 */
    private fun budgetConsumingClasses(): Set<String> {
        val root = visualizerRoot()
        val found = mutableSetOf<String>()
        for (sub in listOf("renderers", "photo")) {
            val dir = File(root, sub)
            assertTrue("扫描目录应存在：$sub", dir.isDirectory)
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                var current: String? = null
                var depth = 0
                for (raw in f.readText().split('\n')) {
                    val code = codeLine(raw) ?: continue
                    // ⛔ 只在 depth == 0 时认新类：否则会把**嵌套类**当成当前类
                    // （WorldGlobeRenderer.kt 里的 `private class Flight` 会让 341 行的预算读取
                    //   被记到 Flight 头上 ⇒ 世界被误判为"不耗预算"，门禁反向放水）
                    Regex("""^\s*(?:internal\s+|private\s+|public\s+)?class\s+([A-Za-z0-9_]+)""")
                        .find(code)?.let { if (depth == 0) current = it.groupValues[1] }
                    depth += code.count { it == '{' } - code.count { it == '}' }
                    if (depth > 0 && current != null && readsBudget(code)) found += current
                }
            }
        }
        return found
    }

    // ── 正向 ──

    @Test
    fun 枚举标注与源码里真正读取粒子预算的效果一致() {
        val mapping = themeToRendererClass()
        assertEquals("工厂应映射全部 ${VisualizerTheme.entries.size} 套效果",
            VisualizerTheme.entries.size, mapping.size)
        val consuming = budgetConsumingClasses()
        val derived = VisualizerTheme.entries.filter { mapping[it.name] in consuming }.toSet()
        val declared = VisualizerTheme.entries.filter { it.needsParticleBudget }.toSet()

        assertEquals(
            "needsParticleBudget 标注与源码不一致（改渲染器读/不读预算，必须同步标注）：\n" +
                "只在源码 = ${derived - declared}，只在标注 = ${declared - derived}",
            derived, declared
        )
    }

    @Test
    fun 低画质只挡真消耗粒子预算的进阶效果() {
        // 放行：数字雨一颗粒子都不用，合并后在 LOW 只有 24 个绘制 op
        assertTrue(VisualQuality.LOW.supports(VisualizerTheme.MATRIX_RAIN))
        // 仍挡：这两套的渲染器真读 maxParticles，LOW 的预算是 0
        assertFalse(VisualQuality.LOW.supports(VisualizerTheme.BEAT_FIREWORK))
        assertFalse(VisualQuality.LOW.supports(VisualizerTheme.WORLD))

        // 其余档位不受影响：MEDIUM / HIGH 提供全部 ADV
        for (q in listOf(VisualQuality.MEDIUM, VisualQuality.HIGH)) {
            VisualizerTheme.entries.filter { it.tier == VisualizerTheme.Tier.ADV }.forEach {
                assertTrue("$q 应提供 $it", q.supports(it))
            }
        }
    }

    @Test
    fun 工厂给出的低画质可选列表包含数字雨且不含粒子型效果() {
        val low = VisualizerRendererFactory.availableThemes(VisualQuality.LOW, true)
        assertTrue("低画质应能选到数字雨", low.contains(VisualizerTheme.MATRIX_RAIN))
        assertFalse(low.contains(VisualizerTheme.BEAT_FIREWORK))
        assertFalse(low.contains(VisualizerTheme.WORLD))
        // ULTRA 三项由帧缓冲门控，与本次裁决无关
        assertFalse(low.contains(VisualizerTheme.MILKDROP_FEEDBACK))
        // 照片墙三来源全关时依然被 selectable 过滤掉
        assertFalse(
            VisualizerRendererFactory.availableThemes(VisualQuality.LOW, false)
                .contains(VisualizerTheme.PHOTO_WALL)
        )
    }

    // ── 负向自证 ──

    @Test
    fun 扫描判据能抓到读取预算的行而不误抓注释与声明() {
        assertTrue(readsBudget("        val cap = ctx.quality.maxParticles"))
        // 注释里提到预算 ⇒ 必须忽略
        assertEquals(null, codeLine("     * 粒子上限见 maxParticles"))
        assertEquals(null, codeLine("        // maxParticles 在这里不生效"))
        // 空扫描（真值集合绝不等于 ADV 全集）⇒ 上面那条一致性断言有判别力
        val adv = VisualizerTheme.entries.filter { it.tier == VisualizerTheme.Tier.ADV }.toSet()
        assertTrue("标注集合应严格小于 ADV 全集，否则门控退化成了按 tier 一刀切",
            VisualizerTheme.entries.filter { it.needsParticleBudget }.toSet().size < adv.size)
    }

    @Test
    fun 旧的按tier一刀切门控会误挡数字雨() {
        // naive：ADV 一律要求 maxParticles > 0（即裁决前的实现）
        val naive = { t: VisualizerTheme ->
            when (t.tier) {
                VisualizerTheme.Tier.BASIC -> true
                VisualizerTheme.Tier.ADV -> t == VisualizerTheme.PHOTO_WALL ||
                    VisualQuality.LOW.maxParticles > 0
                VisualizerTheme.Tier.ULTRA -> VisualQuality.LOW.allowFramebuffer
            }
        }
        // ⇒ 数字雨在 LOW 被挡（真实现放行）；节拍烟花两者都挡（真实现没放水）
        assertFalse("前提：naive 确实挡了数字雨", naive(VisualizerTheme.MATRIX_RAIN))
        assertTrue(VisualQuality.LOW.supports(VisualizerTheme.MATRIX_RAIN))
        assertEquals(false, naive(VisualizerTheme.BEAT_FIREWORK))
        assertEquals(false, VisualQuality.LOW.supports(VisualizerTheme.BEAT_FIREWORK))
    }
}
