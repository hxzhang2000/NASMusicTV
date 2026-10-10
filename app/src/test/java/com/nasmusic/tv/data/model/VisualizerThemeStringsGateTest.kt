package com.nasmusic.tv.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 门禁 **G1**（`docs/moonlit-visualizer-plan.md` §10.1）：每个效果的 `displayNameRes`
 * 必须**同时**存在于 `values/strings.xml` 与 `values-en/strings.xml`。
 *
 * ## 为什么要有这条门
 * `VisualizerTheme` 只存**资源 ID**（`R.string.xxx` 编译期恒存在，⛔ 缺 key 不会编译失败），
 * 显示名要到渲染效果列表时才解析。⇒ 少加一份语言的字符串是**运行时崩溃**，
 * 而 G1 之前**没有任何测试**覆盖这件事 —— 新增一套效果时最容易漏的正是这两行。
 *
 * ## 实现口径
 * 纯源码/资源扫描（不起 Robolectric，也不解析 `R` 类）：
 * - 从 `AppSettings.kt` 的枚举声明里正则抽出「枚举名 → `R.string.<key>`」；
 * - 从两份 `strings.xml` 抽出 `name="…"` 集合；
 * - 断言前者是后两者的子集。
 *
 * ## 负向自证（§八 开头规矩）
 *  G1-N1 从中文集合里摘掉一个**真实存在**的 key ⇒ 判据必须报违规
 *      （证明这份门真的在对撞两份表，而不是"两边都缺所以两边一致"）；
 *  G1-N2 抽取器漏类自证：枚举声明抽到的条数必须等于 `VisualizerTheme.entries.size`
 *      （抽取正则一旦失配就会**静默返回空集** ⇒ 子集断言恒真、门禁空转）。
 */
class VisualizerThemeStringsGateTest {

    // ── 源码/资源定位（AGP 单测的 user.dir 是模块目录 `…/app`，同时兜底仓库根）──

    private fun appDir(): File {
        val cand = listOf(File("src/main"), File("app/src/main"))
        return cand.firstOrNull { it.isDirectory }
            ?: error("找不到 src/main（user.dir=${File("").absolutePath}）")
    }

    /** 抽 `strings.xml` 里全部 `name="…"`（只取本门关心的 `visualizer_theme_` 前缀） */
    private fun themeKeysIn(resourceFile: File): Set<String> {
        assertTrue("${resourceFile.path} 不存在", resourceFile.isFile)
        return Regex("""<string\s+name="(visualizer_theme_\w+)"""")
            .findAll(resourceFile.readText(charset = Charsets.UTF_8))
            .map { it.groupValues[1] }
            .toSet()
    }

    /**
     * 抽 `AppSettings.kt` 枚举项的「枚举名 → 资源 key」。
     * 声明形态固定为 `NAME(R.string.key, "中文名", Tier.X, "NN"…)` ⇒ 只吃前两参，
     * ⛔ 不吃换行（构造参数跨行的项在本枚举里不存在，真出现时由 N2 的条数对撞兜住）。
     */
    private fun themeResKeys(): Map<String, String> {
        val src = File(appDir(), "java/com/nasmusic/tv/data/model/AppSettings.kt")
        assertTrue("$src 不存在", src.isFile)
        return Regex("""(?m)^[ \t]+([A-Z][A-Z0-9_]*)\(R\.string\.(\w+),""")
            .findAll(src.readText(charset = Charsets.UTF_8))
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** 纯判据：给定「枚举名 → key」与两份语言表，返回缺失清单 */
    private fun violations(
        keys: Map<String, String>,
        zh: Set<String>,
        en: Set<String>,
    ): List<String> = keys.flatMap { (name, key) ->
        listOfNotNull(
            if (key !in zh) "$name 缺中文（$key 不在 values/strings.xml）" else null,
            if (key !in en) "$name 缺英文（$key 不在 values-en/strings.xml）" else null,
        )
    }

    @Test
    fun `每个效果的显示名资源必须同时存在于双语 strings`() {
        val keys = themeResKeys()
        val zh = themeKeysIn(File(appDir(), "res/values/strings.xml"))
        val en = themeKeysIn(File(appDir(), "res/values-en/strings.xml"))

        val v = violations(keys, zh, en)
        assertTrue("显示名双语缺失（运行时崩溃，编译期不报错）:\n" + v.joinToString("\n"), v.isEmpty())
    }

    @Test
    fun `负向G1N1 摘掉一个真实存在的 key 必须报违规`() {
        val keys = themeResKeys()
        val zh = themeKeysIn(File(appDir(), "res/values/strings.xml"))
        val en = themeKeysIn(File(appDir(), "res/values-en/strings.xml"))
        assertTrue("夹具本身应零违规", violations(keys, zh, en).isEmpty())

        // 只删中文那份 ⇒ 必须且只报中文缺失；两边都删则报两条
        val moonlitKey = keys.getValue("MOONLIT")
        assertTrue("真实表里应含 $moonlitKey", moonlitKey in zh && moonlitKey in en)
        val onlyZhMissing = violations(keys, zh - moonlitKey, en)
        assertEquals("应恰好 1 条违规: $onlyZhMissing", 1, onlyZhMissing.size)
        assertTrue("应指向中文表: $onlyZhMissing", onlyZhMissing[0].contains("缺中文"))
        assertEquals("双语同缺应报 2 条", 2, violations(keys, zh - moonlitKey, en - moonlitKey).size)
    }

    @Test
    fun `负向G1N2 抽取器不得静默漏项`() {
        val keys = themeResKeys()
        // 抽取条数必须与枚举条数逐一对撞 —— 正则失配会返回空集，让上面的子集断言恒真
        assertEquals(
            "抽取到的枚举项数应与 VisualizerTheme 一致（漏类即门禁空转）",
            VisualizerTheme.entries.size,
            keys.size
        )
        VisualizerTheme.entries.forEach { keys[it.name] ?: error("抽取器漏了 ${it.name}") }
        // 空转自证
        assertTrue("扫描结果非空", keys.isNotEmpty())
    }
}
