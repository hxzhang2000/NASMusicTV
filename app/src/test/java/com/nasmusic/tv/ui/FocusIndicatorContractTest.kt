package com.nasmusic.tv.ui

import android.view.KeyEvent
import com.nasmusic.tv.ui.components.isDirectionalNavigationKey
import com.nasmusic.tv.ui.components.nextDirectionalSeen
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * 门禁：焦点指示器的**绘制顺序**与**设备判据** —— 真机反馈「遥控器导航完全看不到焦点在哪」。
 *
 * ## 根因（两道独立的，都必须防住）
 *
 * ① **绘制顺序**：Compose 的绘制顺序 = 修饰符链顺序，越靠前越靠下。
 *    `FocusableSurface` 此前是 `border(...)` → `background(...)`，不透明容器色把焦点环整个盖住。
 *    焦点环只在容器色为 `Color.Transparent` 的少数组件上侥幸可见，而所有卡片/列表行用的都是
 *    不透明 `NasMusicColors.Surface` ⇒ 全应用看不见焦点框。这条**编译过、单测过**，
 *    只有肉眼能发现，必须门禁。
 *
 * ② **设备判据**：`PackageManager.hasSystemFeature(leanback / type.television)` 在非 Google 认证的
 *    电视盒子上经常双双 false，一旦为 false，`activeFocus` 恒为 false ⇒ 缩放、焦点环、容器色、
 *    内容色全部关闭（同一个"只靠按确认键反推焦点位置"的现象）。
 *
 * ## 判定规则
 *
 * 1. `FocusableSurface.kt` / `UnifiedSongRow.kt` 内，`.focusRing(` 必须出现在 `.background(` **之后**
 *    —— 否则会被容器色盖住。
 * 2. 焦点视觉的判据必须是 `shouldShowFocusVisuals()`，不得再直接用 `isTVDevice()`。
 *
 * ⚠️ 与 [FocusableSurfaceColorContractTest] 同款：这是**启发式**护栏（只看出现次序），
 * 目的是「别删 / 别挪」。护栏本身的有效性由末尾的负向用例守住。
 *
 * 约定见 `docs/conventions-adaptive-ui.md` §11。
 */
class FocusIndicatorContractTest {

    // ─────────────────────────── 真实源码扫描 ───────────────────────────

    @Test
    fun `focusRing 必须画在 background 之后，否则会被不透明容器色盖住`() {
        val offenders = FOCUS_RING_SITES
            .map { it to uiSourceRoot().resolve(it) }
            .filter { (_, file) -> focusRingBeforeBackground(file.readText()) }
            .map { it.first }

        if (offenders.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("焦点环被画在不透明背景**之下**（看不到焦点）：$offenders")
                    appendLine()
                    appendLine("Compose 的绘制顺序 = 修饰符链顺序，越靠前越靠下；")
                    appendLine("`.background(...)` 会把排在其前面的 `.focusRing(...)` 整个盖住。")
                    appendLine()
                    appendLine("修法：把 `.focusRing(...)` 移到链尾（在 `.background(...)` 之后）。")
                    appendLine("详见 docs/conventions-adaptive-ui.md §11。")
                }
            )
        }
    }

    @Test
    fun `焦点视觉的判据必须是 shouldShowFocusVisuals 而不是 isTVDevice`() {
        val file = uiSourceRoot().resolve("components/FocusableSurface.kt")
        assertTrue("找不到 FocusableSurface.kt：${file.absolutePath}", file.isFile)
        val text = file.readText()
        assertTrue(
            "FocusableSurface 未使用 shouldShowFocusVisuals() 作为焦点视觉判据",
            text.contains("shouldShowFocusVisuals()"),
        )
        assertTrue(
            "focusRing 必须挂在链尾（visible 由 showFocusBorder 与 activeFocus 相与决定）",
            text.contains("visible = showFocusBorder && activeFocus"),
        )
    }

    @Test
    fun `UnifiedSongRow 的行焦点判据已切换到 shouldShowFocusVisuals`() {
        val file = uiSourceRoot().resolve("components/song/UnifiedSongRow.kt")
        assertTrue("找不到 UnifiedSongRow.kt：${file.absolutePath}", file.isFile)
        val text = file.readText()
        assertTrue("UnifiedSongRow 未使用 shouldShowFocusVisuals()", text.contains("shouldShowFocusVisuals()"))
        assertTrue(
            "UnifiedSongRow 不应再 import isTVDevice（焦点视觉改走共享判据）",
            !text.contains("import com.nasmusic.tv.ui.components.isTVDevice"),
        )
    }

    @Test
    fun `FocusIndicator 必须提供共享的焦点环与判据`() {
        val text = uiSourceRoot().resolve("components/FocusIndicator.kt").readText()
        for (required in listOf("fun Modifier.focusRing(", "fun shouldShowFocusVisuals()", "object DpadInputTracker")) {
            assertTrue("FocusIndicator.kt 缺少 $required", text.contains(required))
        }
    }

    // ─────────────────────────── 方向键判据（纯逻辑） ───────────────────────────

    @Test
    fun `方向键与确认键算作按键导航`() {
        val navigation = listOf(
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A,
        )
        val missed = navigation.filterNot { isDirectionalNavigationKey(it) }
        assertTrue("这些键应被识别为方向键导航：$missed", missed.isEmpty())
    }

    @Test
    fun `媒体键与音量键不算方向键导航`() {
        // 媒体键由 MediaKeyHandler 处理；把它们算进来会让「调个音量 ⇒ 焦点此后常亮」误触发
        val notNavigation = listOf(
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_A,
            KeyEvent.KEYCODE_UNKNOWN,
        )
        val wrong = notNavigation.filter { isDirectionalNavigationKey(it) }
        assertTrue("这些键不应被识别为方向键导航：$wrong", wrong.isEmpty())
    }

    @Test
    fun `只有按下才翻转标志，且一旦翻转就粘住`() {
        assertTrue(
            "ACTION_DOWN 的方向键应翻转标志",
            nextDirectionalSeen(false, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_DOWN),
        )
        assertFalse(
            "ACTION_UP 不应翻转标志",
            nextDirectionalSeen(false, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_UP),
        )
        assertFalse(
            "ACTION_MULTIPLE 不应翻转标志",
            nextDirectionalSeen(false, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.ACTION_MULTIPLE),
        )
        assertFalse(
            "非方向键不应翻转标志",
            nextDirectionalSeen(false, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.ACTION_DOWN),
        )
        assertTrue(
            "已翻转后必须保持 true（粘住）",
            nextDirectionalSeen(true, KeyEvent.KEYCODE_BACK, KeyEvent.ACTION_UP),
        )
    }

    // ─────────────────────────── 负向自证（护栏有效性） ───────────────────────────

    @Test
    fun `负向：focusRing 排在 background 之前必须判违规`() {
        val text = """
            Box(
                modifier = Modifier
                    .focusRing(shape = shape, color = c, visible = focused)
                    .background(containerColor, shape)
            )
        """.trimIndent()
        assertTrue(focusRingBeforeBackground(text))
    }

    @Test
    fun `负向：只有裸 border 且排在 background 之前同样判违规`() {
        val text = """
            Box(
                modifier = Modifier
                    .border(width = 2.dp, color = focusBorderColor, shape = shape)
                    .background(targetContainerColor, shape)
            )
        """.trimIndent()
        assertTrue(focusRingBeforeBackground(text))
    }

    @Test
    fun `正向：focusRing 排在 background 之后必须判通过`() {
        val text = """
            Box(
                modifier = Modifier
                    .background(targetContainerColor, shape)
                    .combinedClickable(...)
                    .focusRing(shape = shape, color = c, visible = focused)
            )
        """.trimIndent()
        assertFalse(focusRingBeforeBackground(text))
    }

    @Test
    fun `正向：完全没有焦点环修饰符不算违规（避免对无焦点组件误报）`() {
        assertFalse(focusRingBeforeBackground("Modifier.fillMaxWidth().padding(8.dp)"))
    }

    @Test
    fun `负向：两条链里只要有一条把焦点环排在背景之前就判违规`() {
        // 每条链各自成段判定 ⇒ 不能只比「文件里第一个 .background(」
        val text = """
            val a = Modifier.background(c, shape).focusRing(shape, c, true)
            val b = Modifier.focusRing(shape, c, true).background(c, shape)
        """.trimIndent()
        assertTrue(focusRingBeforeBackground(text))
    }

    @Test
    fun `负向：lambda 体夹在中间也不得把焦点环与背景切散`() {
        // 深度跟踪的意义：`.onFocusChanged { … }` 内部有不带点的行，
        // 按行首字符切会漏掉「ring 在 background 之前」的违规
        val text = """
            Box(
                modifier = Modifier
                    .focusRing(shape, c, true)
                    .onFocusChanged { state ->
                        isFocused = state.hasFocus
                        scope.launch { animScale.animateTo(1.08f, tween(200)) }
                    }
                    .background(c, shape)
            )
        """.trimIndent()
        assertTrue(focusRingBeforeBackground(text))
    }

    @Test
    fun `正向：lambda 体夹在中间且顺序正确仍判通过`() {
        val text = """
            Box(
                modifier = Modifier
                    .background(c, shape)
                    .onFocusChanged { state ->
                        isFocused = state.hasFocus
                        scope.launch { animScale.animateTo(1.08f, tween(200)) }
                    }
                    .focusRing(shape, c, true)
            )
        """.trimIndent()
        assertFalse(focusRingBeforeBackground(text))
    }

    @Test
    fun `正向：注释里的括号不应把链切碎`() {
        // `// ……（见 §9）` 之类若被计入深度，会让后续真实代码并进上一段造成漏判
        val text = """
            // 说明（见 §9）：这一段注释里有括号 ( 与 半角括号 )
            val b = Modifier.focusRing(shape, c, true).background(c, shape)
        """.trimIndent()
        assertTrue("仍应检出第二条链的违规", focusRingBeforeBackground(text))
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

/** 画焦点环的源码文件（相对 `ui/` 源目录） */
private val FOCUS_RING_SITES = listOf(
    "components/FocusableSurface.kt",
    "components/song/UnifiedSongRow.kt",
)

/** 焦点环的两种写法（都要查，否则换个写法就能绕过门禁） */
private val FOCUS_RING_MARKERS = listOf(".focusRing(", ".border(")

/** 该文件里是否存在「焦点环排在不透明背景之前」的写法。逐条修饰符链判定。 */
private fun focusRingBeforeBackground(text: String): Boolean =
    modifierChains(text).any { chain ->
        val backgroundAt = chain.indexOf(".background(")
        if (backgroundAt < 0) return@any false
        firstIndexOfAny(chain, FOCUS_RING_MARKERS) in 0 until backgroundAt
    }

/**
 * 把源码切成「修饰符链」片段。
 *
 * 续行判定用**括号深度**而不是行首字符：`.onFocusChanged { state -> … }` 的 lambda 体里有
 * 缩进但不带点的行（`isFocused = state.hasFocus`、`tween(200)`、`}`），按行首字符切会把
 * `焦点环` 和 `.background(` 切到两段去 ⇒ 漏判。深度回到 0 且新行不以 `.` 开头时才另起一段。
 */
private fun modifierChains(text: String): List<String> {
    val chains = mutableListOf<StringBuilder>()
    var current = StringBuilder()
    var depth = 0
    for (raw in text.lines()) {
        if (depth <= 0 && current.isNotEmpty() && !raw.trimStart().startsWith(".")) {
            chains += current
            current = StringBuilder()
        }
        current.append(raw).append('\n')
        depth += depthDelta(raw)
    }
    if (current.isNotEmpty()) chains += current
    return chains.map { it.toString() }
}

/** 一行带来的括号深度变化；先剥掉 `//` 行注释与字符串，避免注释里的括号把深度带偏。 */
private fun depthDelta(raw: String): Int {
    val line = raw.substringBefore("//")
    var inString = false
    var delta = 0
    for (ch in line) {
        when (ch) {
            '"' -> inString = !inString
            '{', '(', '[' -> if (!inString) delta++
            '}', ')', ']' -> if (!inString) delta--
        }
    }
    return delta
}

private fun firstIndexOfAny(text: String, needles: List<String>): Int =
    needles.map { text.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: -1