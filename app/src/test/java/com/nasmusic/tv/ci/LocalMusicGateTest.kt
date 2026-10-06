package com.nasmusic.tv.ci

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 权限与签名统一方案 B 线门控契约的门禁测试（G3，方案 §7.3）。
 *
 * 用「源码文本扫描」断言门控点存在 —— 与 [MediaSessionAccessPolicyTest] /
 * `VisualizerQualityWiringTest` 同款手法：这些门控点（开关观察者、搜索门控、
 * Manifest 权限瘦身）编译期无类型约束，任何一次「顺手重构」都可能无声拆掉，
 * 只有文本门禁能锁住。
 *
 * ## 断言口径（方案 §7.3）
 *
 * | # | 内容 |
 * |---|---|
 * | L1 | `MainViewModel` 存在 `startLocalMusicRuntime()`（启动块被开关包住的结构锚点） |
 * | L2 | 存在 pref `distinctUntilChanged()` 的运行期观察者（防「开关打开后不扫描」回归） |
 * | L3 | `SearchAggregator` 的 `LOCAL` 分支条件含 `localMusicEnabled()` |
 * | L4 | `AndroidManifest.xml` 不再出现三条被删权限（A/C 线验收判据 U14，含注释 0 命中） |
 * | L5 | `MainActivity.kt` 不再出现 `ActivityCompat.requestPermissions`（开机零弹窗） |
 * | L6 | 负向自证：把权限塞回 Manifest 文本，L4 必须挂 |
 *
 * ⚠️ L4 是**全文匹配、不剥离注释** —— U14 的判据就是 grep 0 命中（注释里提及同样算违规：
 * 陈旧注释会误导下一个会话以为权限仍在）。
 * ⚠️ 断言一律 `assertTrue` / `assertFalse`：Kotlin 的 `assert` 在测试 JVM 上是空操作。
 */
class LocalMusicGateTest {

    // ─────────────────── L1：启动块被开关包住的结构锚点 ───────────────────

    @Test
    fun `MainViewModel 必须存在 startLocalMusicRuntime`() {
        val source = mainSourceFile("ui/viewmodel/MainViewModel.kt")
        // 空转自证：读到的确实是 MainViewModel
        assertTrue(
            "MainViewModel.kt 里找不到 `class MainViewModel` —— 可能读错文件",
            source.contains("class MainViewModel"),
        )
        assertTrue(
            "MainViewModel 里没有 `fun startLocalMusicRuntime()` ——\n" +
                "启动块不再是「被开关包住、可启停」的结构。v2.38.3 把本地音乐运行时" +
                "（增量扫描 + USB 观察）抽成 startLocalMusicRuntime/stopLocalMusicRuntime，\n" +
                "由 localMusicEnabled 观察者驱动 —— 若被内联回无条件启动块，\n" +
                "「关掉开关仍然扫描/申请权限」的缺陷就会复发（方案 §5.2 M2/M3）。",
            source.contains("fun startLocalMusicRuntime()"),
        )
    }

    // ─────────────────── L2：运行期观察者 ───────────────────

    @Test
    fun `必须存在开关的运行期观察者`() {
        val source = mainSourceFile("ui/viewmodel/MainViewModel.kt")
        assertTrue(
            "MainViewModel 里没有 `map { it.localMusicEnabled }` —— 开关观察者被拆了？",
            source.contains("map { it.localMusicEnabled }"),
        )
        assertTrue(
            "开关观察者缺 `distinctUntilChanged()` ——\n" +
                "后果：DataStore 每次无关字段写入都会重触发 collect，本地音乐被反复停止/重启；\n" +
                "更糟的是没有观察者的话，「设置页关掉开关后回到曲库页不刷新」的回归会复发" +
                "（方案 §5.2，防「开关打开后不扫描」）。",
            source.contains("distinctUntilChanged()"),
        )
    }

    // ─────────────────── L3：搜索聚合器门控 ───────────────────

    @Test
    fun `SearchAggregator 的 LOCAL 分支必须判开关`() {
        val source = mainSourceFile("backend/SearchAggregator.kt")
        // 空转自证
        assertTrue(
            "SearchAggregator.kt 里找不到 `class SearchAggregator` —— 可能读错文件",
            source.contains("class SearchAggregator"),
        )
        assertTrue(
            "SearchAggregator 的 LOCAL 分支没有 `localMusicEnabled()` 门控 ——\n" +
                "后果：开关关闭时聚合搜索仍会把本机/U 盘歌曲混进结果，点播后解析失败。\n" +
                "门控是 suspend provider（方案 §5.2 M7），若被误删请恢复：\n" +
                "`MusicSourceType.LOCAL in sources && localMusicRepository != null && localMusicEnabled()`。",
            source.contains("localMusicEnabled()"),
        )
    }

    // ─────────────────── L4：Manifest 三条权限 0 命中 ───────────────────

    @Test
    fun `Manifest 不得再出现三条被删权限`() {
        val hits = forbiddenPermissionHits(manifestText())
        assertTrue(
            "AndroidManifest.xml 里仍出现被删权限：$hits\n" +
                "A/C 线已删除 POST_NOTIFICATIONS / WRITE_EXTERNAL_STORAGE /" +
                " REQUEST_IGNORE_BATTERY_OPTIMIZATIONS（MediaStyle 通知有平台豁免、\n" +
                "本地音乐改按需申请、电池优化跳转整删）。\n" +
                "⚠️ 注释里提及同样算命中（U14）—— 陈旧注释会误导后人以为权限仍在。\n" +
                "详见 docs/permission-and-signing-plan.md §四/§六。",
            hits.isEmpty(),
        )
    }

    // ─────────────────── L5：开机零弹窗 ───────────────────

    @Test
    fun `MainActivity 不得再调用 ActivityCompat 点播权限申请`() {
        val source = mainSourceFile("ui/MainActivity.kt")
        assertTrue(
            "MainActivity.kt 里仍出现 `ActivityCompat.requestPermissions` ——\n" +
                "开机申请块已删（A 线），音乐权限现在只由「本地音乐」总开关驱动" +
                "（launcher 注入，方案 §5.5）。\n" +
                "若确实需要新的运行期权限申请，请走 ActivityResultLauncher（避免回到" +
                "启动即弹窗的旧模式），并同步更新 docs/permission-and-signing-plan.md。",
            !source.contains("ActivityCompat.requestPermissions"),
        )
    }

    // ─────────────────── L6：负向自证 ───────────────────

    /** 负向自证：把权限塞回 Manifest 文本（模拟回归），L4 的检查必须命中。 */
    @Test
    fun `把权限塞回 Manifest 后 L4 必须判负`() {
        val sabotaged = manifestText() +
            "\n    <uses-permission android:name=\"android.permission.POST_NOTIFICATIONS\" />"
        assertTrue(
            "护栏失效：塞回 POST_NOTIFICATIONS 后仍 0 命中 —— L4 在空转",
            forbiddenPermissionHits(sabotaged).isNotEmpty(),
        )
    }

    // ─────────────────── 辅助 ───────────────────

    /** 三条被删权限在文本中的命中行号（1-based，含注释行 —— U14 判据就是全文 0 命中）。 */
    private fun forbiddenPermissionHits(text: String): List<Int> {
        val forbidden = listOf(
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        )
        return text.lines().withIndex()
            .filter { (_, line) -> forbidden.any { line.contains(it) } }
            .map { it.index + 1 }
    }

    private fun mainSourceFile(relativePath: String): String {
        val file = mainSourceRoot().resolve(relativePath)
        assertTrue("找不到源码文件：${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private fun manifestText(): String {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"), // user.dir = app/
            File("app/src/main/AndroidManifest.xml"), // user.dir = 仓库根
        )
        val found = candidates.firstOrNull { it.isFile }
        assertTrue(
            "找不到 AndroidManifest.xml（user.dir=${File("").absolutePath}）；" +
                "已尝试：${candidates.joinToString { it.absolutePath }}",
            found != null,
        )
        return found!!.readText()
    }

    private fun mainSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv"),
            File("app/src/main/java/com/nasmusic/tv"),
            File("../app/src/main/java/com/nasmusic/tv"),
        )
        val found = candidates.firstOrNull { it.isDirectory }
        assertTrue(
            "找不到源码目录（user.dir=${File("").absolutePath}）；" +
                "已尝试：${candidates.joinToString { it.absolutePath }}",
            found != null,
        )
        return found!!
    }
}
