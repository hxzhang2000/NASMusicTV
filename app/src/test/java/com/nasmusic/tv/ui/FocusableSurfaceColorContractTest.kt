package com.nasmusic.tv.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 门禁：`FocusableSurface` 必须把内容色下发给子组件 —— **含 `androidx.tv.material3.LocalContentColor`**。
 *
 * ## 为什么需要这道门禁
 *
 * `androidx.tv.material3.LocalContentColor` 的默认值是 **`Color.Black`**
 * （`androidx/tv/material3/ContentColor.kt`：`compositionLocalOf { Color.Black }`），而
 * - `Icon(imageVector, contentDescription, modifier, tint = LocalContentColor.current)`
 * - `Text(..., color = Color.Unspecified)` → `style.color.takeOrElse { LocalContentColor.current }`
 *
 * 两者都会回退到它。`FocusableSurface` 早期**只**提供自定义的 `LocalFocusableContentColor`，
 * 于是内部凡是没显式写 `tint =` / `color =` 的 `Icon` / `Text` 全部画成**黑色** ——
 * 压在深色底（`Surface #162032`）上就是用户反馈的「按钮看不清」。
 * 这个缺陷**编译过、单测过**，且在 TV 上同样存在（黑字压在 `SurfaceVariant #1E2D42` 上几乎不可见），
 * 属于典型的"只有肉眼能发现"的问题，因此需要一道自动门禁。
 *
 * ## 判定规则
 *
 * `components/FocusableSurface.kt` 的源码必须同时出现：
 * 1. `LocalFocusableContentColor provides` —— 项目内既有约定（多处直接用 `LocalFocusableContentColor.current`）
 * 2. `LocalContentColor provides` —— 本轮补上的 tv-material3 标准语义
 *
 * ⚠️ 这是**启发式**护栏（只看 `provides` 是否出现，不校验作用域），目的是「别删」。
 * 护栏本身的有效性由本文件末尾的负向用例守住。
 *
 * 约定见 `docs/conventions-adaptive-ui.md` §10。
 */
class FocusableSurfaceColorContractTest {

    // ─────────────────────────── 真实源码扫描 ───────────────────────────

    @Test
    fun `FocusableSurface 必须同时下发 LocalFocusableContentColor 与 LocalContentColor`() {
        val file = uiSourceRoot().resolve("components/FocusableSurface.kt")
        assertTrue(
            "找不到 FocusableSurface.kt（源目录定位可能失败）：${file.absolutePath}",
            file.isFile,
        )
        val missing = missingProvides(file.readText())
        if (missing.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("FocusableSurface 缺少内容色下发：$missing")
                    appendLine()
                    appendLine("后果：tv-material3 的 `LocalContentColor` 默认值是 `Color.Black`，")
                    appendLine("而 `Icon` / `Text` 都会回退到它 —— 内部没写 tint/color 的图标与文字")
                    appendLine("会在深色底上画成黑色（用户反馈的「按钮看不清」）。")
                    appendLine()
                    appendLine("修法：在 content() 外层用 CompositionLocalProvider 同时提供")
                    appendLine("`LocalFocusableContentColor provides targetContentColor` 与")
                    appendLine("`LocalContentColor provides targetContentColor`。")
                    appendLine("详见 docs/conventions-adaptive-ui.md §10。")
                }
            )
        }
    }

    @Test
    fun `真实文件里不应残留未下发的组合`() {
        val text = uiSourceRoot().resolve("components/FocusableSurface.kt").readText()
        // 正向自证：确实扫到了那个文件、且它现在满足约束
        assertTrue(
            "扫描内容异常（没读到 FocusableSurface 的实现体）",
            text.contains("fun FocusableSurface("),
        )
        assertEquals(emptyList<String>(), missingProvides(text))
    }

    // ─────────────────────────── 负向自证（护栏有效性） ───────────────────────────

    @Test
    fun `负向：两者都缺时必须全部判缺失`() {
        val text = """
            CompositionLocalProvider(LocalSomeOtherColor provides targetContentColor) {
                content()
            }
        """.trimIndent()
        assertEquals(REQUIRED_PROVIDES, missingProvides(text))
    }

    @Test
    fun `负向：只缺 LocalContentColor 时必须判缺失`() {
        val text = "CompositionLocalProvider(LocalFocusableContentColor provides c) { content() }"
        assertEquals(listOf("LocalContentColor provides"), missingProvides(text))
    }

    @Test
    fun `正向：两者齐备时判定为空`() {
        val text = """
            CompositionLocalProvider(
                LocalFocusableContentColor provides c,
                LocalContentColor provides c,
            ) { content() }
        """.trimIndent()
        assertEquals(emptyList<String>(), missingProvides(text))
    }

    @Test
    fun `负向：仅出现类名但没有 provides 不算通过`() {
        // 防止"只 import 了 LocalContentColor 但没用"这种假通过
        val text = """
            import androidx.tv.material3.LocalContentColor
            import com.nasmusic.tv.ui.components.LocalFocusableContentColor
        """.trimIndent()
        assertEquals(REQUIRED_PROVIDES, missingProvides(text))
    }

    // ─────────────────────────── 工具 ───────────────────────────

    private fun uiSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java/com/nasmusic/tv/ui"),
            File("app/src/main/java/com/nasmusic/tv/ui"),
            File("../app/src/main/java/com/nasmusic/tv/ui"),
        )
        val found = candidates.firstOrNull { it.isDirectory }
        assertTrue(
            "找不到 UI 源码目录（user.dir=${File("").absolutePath}）；" +
                "已尝试：${candidates.joinToString { it.absolutePath }}",
            found != null,
        )
        return found!!
    }
}

/** 必须同时出现的两条内容色下发语句 */
private val REQUIRED_PROVIDES = listOf(
    "LocalFocusableContentColor provides",
    "LocalContentColor provides",
)

/** 返回缺失的下发语句（空列表 = 通过）。抽成纯函数以便负向自证。 */
private fun missingProvides(text: String): List<String> =
    REQUIRED_PROVIDES.filterNot { text.contains(it) }
