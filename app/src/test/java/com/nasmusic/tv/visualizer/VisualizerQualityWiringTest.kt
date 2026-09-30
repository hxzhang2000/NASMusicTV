package com.nasmusic.tv.visualizer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §八 G13 伴生门禁 —— T1.6.4「可视化画质」设置页接线（§十三 裁决项 9 = A 的落地验证）。
 *
 * 背景（§2.4）：`VisualQuality` 此前**无任何 UI 入口** ⇒ 档位恒 MEDIUM ⇒
 * E18 反馈残像 / E19 粒子文字 / E20 等离子流场（`Tier.ULTRA`）被 `supports()` 永久过滤，
 * 任何设备上都从未被渲染过。T1.6.4 把设置页三档选择器接到已有的
 * `VisualizerViewModel.setQuality()`（写 DataStore ⇒ `AppSettings.visualizerQuality` 生效）。
 *
 * JVM 单测无法起 Compose 设置页 ⇒ 本门禁做**接线契约**的源码断言：
 *  ① 设置页动作真的调 `visualizerVM.setQuality(...)`（而非旁路 DataStore 或改 supports()）；
 *  ② 选择器遍历 `VisualQuality.entries`（三档全给，防「常量加了 UI 忘了加」漂移）；
 *  ③ 文案 key 在 `values/` 与 `values-en/` **同时存在**（T1.6.4 明确要求同步英文）；
 *  ④ `setQuality()` 内部走 `prefs.setVisualizerQuality`（写 DataStore ⇒ 冷启动后仍生效）；
 *  ⑤ ⛔ 门控未被破坏：`VisualQuality.supports` 的 `Tier.ULTRA -> allowFramebuffer` 原样保留
 *     （那是被否掉的 B 方案，不允许顺手改）。
 *
 * 负向自证：N1 把「旁路 setQuality、直写 State」的破接线样本喂给判据 ⇒ 必须被判不合规。
 * 设备依赖项（§12.4 偏差记录）：V29「E18/E19/E20 能选到且真的看到画面」需真机验证，待上机。
 */
class VisualizerQualityWiringTest {

    private fun mainSourceRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(6) {
            val cand = File(dir, "app/src/main/java/com/nasmusic/tv")
            if (cand.isDirectory) return cand
            dir = dir.parentFile ?: return@repeat
        }
        error("找不到源码根：user.dir = ${System.getProperty("user.dir")}")
    }

    private fun resRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        repeat(6) {
            val cand = File(dir, "app/src/main/res")
            if (cand.isDirectory) return cand
            dir = dir.parentFile ?: return@repeat
        }
        error("找不到 res 根")
    }

    private fun read(path: String): String {
        val f = File(mainSourceRoot(), path)
        assertTrue("文件应存在：$path", f.isFile)
        return f.readText()
    }

    // ── 判据 ──

    /** 合规的接线：调 `visualizerVM.setQuality(...)`。 */
    private fun isWiredViaSetQuality(src: String): Boolean =
        Regex("""visualizerVM\s*\.\s*setQuality\s*\(""").containsMatchIn(src)

    /** 破接线样本（负向自证用）：绕过 setQuality 直写 State / 不落 DataStore。 */
    private val brokenWiringSample = """
        onChangeVisualizerQuality = { q -> viewModel.visualizerVM.quality.let { it } }
        onChangeVisualizerQuality = { q -> viewModel.updateVisualizerQualityDirectly(q) }
    """.trimIndent()

    @Test
    fun `设置页画质动作必须接 visualizerVM setQuality`() {
        val branch = read("ui/components/branches/SettingsBranch.kt")
        assertTrue(
            "SettingsBranch 必须把 onChangeVisualizerQuality 接到 visualizerVM.setQuality(...)（§2.4 方案 A）",
            isWiredViaSetQuality(branch)
        )
    }

    @Test
    fun `选择器必须遍历 VisualQuality entries 三档`() {
        val section = read("ui/screens/settings/PlayerSettingsSection.kt")
        assertTrue(
            "PlayerSettingsSection 必须遍历 VisualQuality.entries（防三档文案漂移）",
            Regex("""VisualQuality\s*\.\s*entries""").containsMatchIn(section)
        )
        assertTrue(
            "PlayerSettingsActions 必须声明 onChangeVisualizerQuality",
            section.contains("onChangeVisualizerQuality")
        )
    }

    @Test
    fun `画质文案必须中英双份`() {
        val key = "settings_visualizer_quality"
        val zh = File(resRoot(), "values/strings.xml").readText()
        val en = File(resRoot(), "values-en/strings.xml").readText()
        assertTrue("values/strings.xml 缺 $key", Regex("""name="$key"""").containsMatchIn(zh))
        assertTrue("values-en/strings.xml 缺 $key（T1.6.4 明确要求同步英文）",
            Regex("""name="$key"""").containsMatchIn(en))
    }

    @Test
    fun `setQuality 必须写 DataStore`() {
        val vm = read("ui/viewmodel/VisualizerViewModel.kt")
        assertTrue(
            "setQuality() 内必须调 prefs.setVisualizerQuality（写 DataStore ⇒ 冷启动仍生效）",
            Regex("""fun\s+setQuality\s*\([\s\S]{0,400}?prefs\s*\.\s*setVisualizerQuality""").containsMatchIn(vm)
        )
    }

    @Test
    fun `档位门控 supports 不得被顺手改掉`() {
        val appSettings = read("data/model/AppSettings.kt")
        assertTrue(
            "⛔ supports() 的 Tier.ULTRA -> allowFramebuffer 是三处共同判据（§2.4）：被否的 B 方案，不得改",
            Regex("""Tier\s*\.\s*ULTRA\s*->\s*allowFramebuffer""").containsMatchIn(appSettings)
        )
    }

    // ── 负向自证 ──

    @Test
    fun `负向N1 破接线样本必须被判不合规`() {
        // 样本本身确实没有 visualizerVM.setQuality 调用（证明判据在看对的东西）
        assertFalse("样本不应误通过判据", isWiredViaSetQuality(brokenWiringSample))
    }
}
