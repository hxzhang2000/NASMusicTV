package com.nasmusic.tv.ui

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 「新增 Screen 必须有 UiMode 分支」的**门禁测试**（方案 §9 P1-32 后半）。
 *
 * v2.36.0 起**同一份 APK 同时服务 TV / 手机竖屏 / 手机横屏**（形态因子 `UiMode`）。
 * 新增一个 Screen 却忘了读 `LocalUiMode`，手机竖屏就会直接套用 TV 版式而溢出 ——
 * 这类问题**编译过、单测过**，只有真机才看得见，所以需要一道自动门禁。
 *
 * ## 为什么是单测而不是自定义 lint 规则
 *
 * 方案原文建议放 `tools/lint/`。实测在**当前工具链（AGP 9.2.1 + `com.android.tools.lint` 32.2.1）**
 * 下不可行：自定义 check jar 的类由 `com.intellij.util.lang.UrlClassLoader` 加载，而
 * `SourceCodeScanner` 由 `java.net.URLClassLoader` 加载 —— 两个类加载器各持一份 `lint-api`，
 * 导致 `PortraitScreenUiModeDetector cannot be cast to SourceCodeScanner`，
 * `:app:lintAnalyzeDebug` 直接失败（不是配置错误：`lintChecks` 依赖树只有 `project :tools:lint`，
 * check jar 里也只有自己的类 + `META-INF/services`，没有打包 lint-api）。
 *
 * 与其引入一个在该工具链下不可靠的构建模块，这里用**同等强度**的单测门禁替代：
 * 跑在已有的 `testDebugUnitTest` 里（CI 已阻塞），零新增依赖、零类加载风险。
 * 判定逻辑与当初设计的 lint 规则**逐条一致**（同一套 marker、同一个豁免标记），
 * 将来若工具链修好，可原样搬回 `tools/lint/`。
 *
 * ## 判定规则
 *
 * 文件同时满足 ① 有顶层 `fun XxxScreen(`；② 含 `@Composable`；
 * ③ 没有出现任一 marker；④ 没有豁免标记 —— 即判违规。
 *
 * ⚠️ 这是**启发式**护栏（只看 marker 出现与否，不校验是否真被调用），
 * 目的是「别忘写」，不是版式正确性证明。护栏本身的有效性由本文件末尾的负向用例守住。
 *
 * 约定见 `docs/conventions-adaptive-ui.md` §9。
 */
class ScreenUiModeCoverageTest {

    // ─────────────────────────── 真实源码扫描 ───────────────────────────

    @Test
    fun `每个 Screen 组合函数都必须处理手机竖屏形态`() {
        val root = uiSourceRoot()
        val violations = mutableListOf<String>()
        var scanned = 0

        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                if (!isScreenFile(text)) return@forEach
                // scanned 统计的是「命中 Screen 的文件数」（含豁免项），
                // 用来区分「真的扫过」与「正则失配导致空转」
                scanned++
                val reason = screenUiModeViolation(file.relativeTo(root).path.replace('\\', '/'), text)
                    ?: return@forEach
                violations += reason
            }

        assertTrue(
            "没有扫到任何 Screen —— 源目录定位可能失败：${root.absolutePath}",
            scanned > 0,
        )

        if (violations.isNotEmpty()) {
            fail(
                "以下 Screen 未引用任何自适应布局 API，手机竖屏会直接套用 TV 版式：\n" +
                    violations.joinToString("\n") { "  • $it" } +
                    "\n\n修法：读 `LocalUiMode` 加竖屏分支，或使用 `AdaptiveLayout` / " +
                    "`adaptiveColumns` / `responsiveDialogSize` / `portraitTouchTarget`。\n" +
                    "确实不需要竖屏分支的（如被 `isFullScreenPage` 强制横屏的全屏页），" +
                    "在文件顶部加 `$EXEMPT_MARKER: <理由>` 注释。\n" +
                    "约定见 docs/conventions-adaptive-ui.md §9。",
            )
        }
    }

    // ─────────────────── 负向用例：护栏本身必须有效 ───────────────────
    //
    // 没有这组用例，"扫描通过"可能只是因为扫描逻辑空转（例如正则失配）。

    @Test
    fun `无 marker 的 Screen 必须被判违规`() {
        val text = """
            package p

            import androidx.compose.runtime.Composable

            @Composable
            fun FooScreen() {
                Box(modifier = Modifier.fillMaxSize())
            }
        """.trimIndent()

        assertNotNull(
            "护栏失效：无任何 marker 的 Screen 没被判违规",
            screenUiModeViolation("FooScreen.kt", text),
        )
    }

    @Test
    fun `带任一 marker 的 Screen 放行`() {
        val markers = listOf(
            "val isPhonePortrait = LocalUiMode.current == UiMode.PhonePortrait",
            "AdaptiveLayout(phonePortrait = {}, tv = {})",
            "adaptiveColumns(tv = 6, phonePortrait = 3)",
            "adaptiveColumnsOf(widthDp, 6, 3, 6)",
            "responsiveDialogSize(480.dp)",
            "Modifier.size(portraitTouchTarget(48.dp))",
            "Modifier.size(PHONE_TOUCH_TARGET)",
        )
        markers.forEach { marker ->
            val text = """
                package p

                import androidx.compose.runtime.Composable

                @Composable
                fun FooScreen() {
                    $marker
                }
            """.trimIndent()
            assertNull("marker `$marker` 未被识别", screenUiModeViolation("FooScreen.kt", text))
        }
    }

    @Test
    fun `带豁免标记的 Screen 放行`() {
        val text = """
            // $EXEMPT_MARKER: 全屏页被强制横屏，永远不在竖屏渲染

            package p

            import androidx.compose.runtime.Composable

            @Composable
            fun FooScreen() { Box {} }
        """.trimIndent()

        assertNull("豁免标记未被识别", screenUiModeViolation("FooScreen.kt", text))
    }

    @Test
    fun `非 Screen 函数与嵌套函数不参与判定`() {
        // 顶层但名字不是 *Screen
        assertNull(
            "非 *Screen 的顶层函数不该被判定",
            screenUiModeViolation(
                "Foo.kt",
                """
                package p

                import androidx.compose.runtime.Composable

                @Composable
                fun Foo() { Box {} }
                """.trimIndent(),
            ),
        )
        // 名字像 Screen 但**有缩进**（嵌套私有小函数），不该被判定
        assertNull(
            "嵌套（有缩进）的 *Screen 函数不该被判定",
            screenUiModeViolation(
                "Bar.kt",
                """
                package p

                import androidx.compose.runtime.Composable

                @Composable
                fun Bar() {
                    @Composable
                    fun innerScreen() { Box {} }
                }
                """.trimIndent(),
            ),
        )
        // 有 *Screen 但没有 @Composable（普通函数），不该被判定
        assertNull(
            "非 @Composable 的 *Screen 函数不该被判定",
            screenUiModeViolation(
                "Baz.kt",
                """
                package p

                fun BazScreen(): Int = 1
                """.trimIndent(),
            ),
        )
    }

    // ─────────────────────────── 内部实现 ───────────────────────────

    /**
     * 定位 `ui/` 源码根目录。
     *
     * AGP 单测的 `user.dir` 是**模块目录**（`…/app`），但为稳妥起见同时尝试仓库根与相对上级。
     * 定位失败时**直接失败**而不是跳过 —— 否则这道门禁会被静默禁用。
     */
    private fun uiSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv/ui"),
            File("app/src/main/java/com/nasmusic/tv/ui"),
            File("../app/src/main/java/com/nasmusic/tv/ui"),
        )
        val found = candidates.firstOrNull { it.isDirectory }
        assertNotNull(
            "找不到 UI 源码目录（user.dir=${File("").absolutePath}）；" +
                "已尝试：${candidates.joinToString { it.absolutePath }}",
            found,
        )
        return found!!
    }
}

/** 豁免标记：写在文件顶部注释里，后跟理由 */
private const val EXEMPT_MARKER = "NasScreenUiMode-exempt"

/**
 * 顶层 `fun XxxScreen(`。
 *
 * 不允许前导缩进 —— Screen 组合函数是**顶层**函数，这样可避免把嵌套的
 * 私有小函数也算进来。
 */
private val SCREEN_FUN = Regex("""(?m)^(?:internal |private |public )?fun ([A-Za-z0-9_]*Screen)\(""")

/** 出现任意一个即视为「已处理竖屏形态」，与约定文档 §3~§6 的 API 一一对应 */
private val ADAPTIVE_MARKERS = listOf(
    "LocalUiMode",
    "UiMode.",
    "AdaptiveLayout(",
    "adaptiveColumns(",
    "adaptiveColumnsOf(",
    "responsiveDialogSize(",
    "portraitTouchTarget(",
    "PHONE_TOUCH_TARGET",
)

/** 是否为「Screen 组合函数所在文件」：有顶层 `fun XxxScreen(` 且含 `@Composable` */
private fun isScreenFile(text: String): Boolean =
    SCREEN_FUN.containsMatchIn(text) && text.contains("@Composable")

/**
 * **纯函数**：判定单个文件是否违规。
 *
 * @return `null` = 通过（不是 Screen / 已处理竖屏 / 已豁免）；否则返回 `"相对路径 → 函数名"`
 */
private fun screenUiModeViolation(relativePath: String, text: String): String? {
    if (!isScreenFile(text)) return null
    val match = SCREEN_FUN.find(text) ?: return null
    if (text.contains(EXEMPT_MARKER)) return null
    if (ADAPTIVE_MARKERS.any { text.contains(it) }) return null
    return "$relativePath → ${match.groupValues[1]}"
}
