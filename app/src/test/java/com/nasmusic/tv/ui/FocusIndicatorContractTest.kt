package com.nasmusic.tv.ui

import android.view.InputDevice
import android.view.KeyEvent
import com.nasmusic.tv.ui.components.isDirectionalNavigationKey
import com.nasmusic.tv.ui.components.isRemoteNavigationDevice
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
 * ② **判据能力不足**：仅靠 `PackageManager.hasSystemFeature(leanback / type.television)` 的
 *    **静态特征嗅探**不够 —— 非 Google 认证的电视盒子上常常双双 false；唯一的兜底
 *    「按过一次方向键」又必须先有一次按键才翻转，正好是用户拒绝的行为
 *    （「切到页面要立刻知道焦点在哪，而不是先按一下才看到」）。
 *    ⇒ 必须存在**基于输入能力**的判据：`InputDevice` 里有没有声明 `SOURCE_DPAD` 的设备。
 *    遥控器本身就是注册在系统里的 input device，盒子瞒不掉它的能力。
 *
 * ## 判定规则
 *
 * 1. `FocusableSurface.kt` / `UnifiedSongRow.kt` 内，`.focusRing(` 必须出现在 `.background(` **之后**
 *    —— 否则会被容器色盖住。
 * 2. 焦点视觉的判据必须是 `shouldShowFocusVisuals()`，不得再直接用 `isTVDevice()`。
 * 3. 四条核心判据（无触摸屏 / TV feature / **D-PAD 输入设备** / 按键兜底）一条都不能被删，
 *    且 D-PAD 探测必须真的 or 进 `shouldShowFocusVisuals()` 的返回值、且必须被缓存。
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

    // ─────────────────── D-PAD 输入设备探测（核心需求：切页面即见） ───────────────────

    @Test
    fun `判据源码必须包含 SOURCE_DPAD 输入设备探测`() {
        // 这条是「切到页面立刻看到焦点环」的唯一保证：前两条静态特征嗅探在刻意隐瞒
        // leanback/television 的盒子上会双双落空，而按键兜底必须先按一次 —— 正是被拒绝的行为。
        val text = uiSourceRoot().resolve("components/FocusIndicator.kt").readText()
        for (required in listOf("InputDevice.SOURCE_DPAD", "InputDevice.getDeviceIds()", "supportsSource")) {
            assertTrue(
                "FocusIndicator.kt 缺少 D-PAD 能力探测的 $required" +
                    "（删掉它焦点环就会退回「先按一下才显示」）",
                text.contains(required),
            )
        }
    }

    @Test
    fun `D-PAD 探测结果必须真的参与 shouldShowFocusVisuals 的返回`() {
        // 光有探测函数不够：必须真的 or 进返回值，否则探测是死代码
        val text = uiSourceRoot().resolve("components/FocusIndicator.kt").readText()
        assertTrue(
            "dpadInputDevicePresent 未参与 shouldShowFocusVisuals 的判定",
            text.contains("dpadInputDevicePresent ||"),
        )
    }

    @Test
    fun `四条核心判据一条都不能被删`() {
        val text = uiSourceRoot().resolve("components/FocusIndicator.kt").readText()
        val required = mapOf(
            "无触摸屏（FEATURE_TOUCHSCREEN）" to "FEATURE_TOUCHSCREEN",
            "电视特征 FEATURE_LEANBACK" to "FEATURE_LEANBACK",
            "电视特征 type.television" to "android.hardware.type.television",
            "D-PAD 输入设备能力" to "dpadInputDevicePresent",
            "按键兜底 directionalNavigationSeen" to "DpadInputTracker.directionalNavigationSeen",
        )
        val missing = required.filterValues { !text.contains(it) }.keys
        if (missing.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("焦点视觉判据被删减：$missing")
                    appendLine()
                    appendLine("四条判据任一成立即显示焦点环，删掉任何一条都会让某类设备退回")
                    appendLine("「看不见焦点」。尤其 D-PAD 探测是「切页面立刻可见」的唯一保证，")
                    appendLine("按键兜底必须先按一次键才会翻转 —— 那是用户明确拒绝的行为。")
                    appendLine()
                    appendLine("详见 docs/conventions-adaptive-ui.md §11。")
                }
            )
        }
    }

    @Test
    fun `InputDevice 查询必须被缓存且只存在于探测函数里`() {
        // shouldShowFocusVisuals 被 135+ 处组合期调用，而 getDeviceIds() 是 binder 调用。
        // 缓存必须是全进程一次（`by lazy` 委托给探测函数），不是每个调用点各自的 remember。
        val text = uiSourceRoot().resolve("components/FocusIndicator.kt").readText()
        assertTrue("未找到 InputDevice.getDeviceIds() 调用", text.contains("InputDevice.getDeviceIds()"))

        // ① 缓存必须存在，且其初始化体调用探测函数
        assertTrue(
            "InputDevice 探测未缓存（应有 `by lazy { probeDpadInputDevices() }`，" +
                "否则 135+ 个组合期调用点各做一次 binder 调用）",
            Regex("""by\s+lazy\s*\{[^}]*probeDpadInputDevices\(\)""").containsMatchIn(text),
        )

        // ② binder 查询只允许出现在 probeDpadInputDevices 函数体内，不得泄漏进组合函数
        val composableBody = functionBody(text, "fun shouldShowFocusVisuals()")
        assertTrue(
            "InputDevice.getDeviceIds() 出现在 shouldShowFocusVisuals 里 ⇒ 每次重组都打 binder",
            !composableBody.contains("InputDevice.getDeviceIds()") &&
                !composableBody.contains("probeDpadInputDevices()"),
        )
    }

    @Test
    fun `缓存失效的替代方案必须在按键路径上有兜底`() {
        // 判据 3 缓存后对「运行中才接入」的设备会过期，这是接受的取舍 ——
        // 但必须确认兜底（判据 4 的按键跟踪）确实还在，否则动态接入就彻底没救。
        val text = uiSourceRoot().resolve("components/FocusIndicator.kt").readText()
        val composableBody = functionBody(text, "fun shouldShowFocusVisuals()")
        assertTrue(
            "shouldShowFocusVisuals 必须读 directionalNavigationSeen（覆盖运行中接入的键盘/手柄）",
            composableBody.contains("DpadInputTracker.directionalNavigationSeen"),
        )
    }

    // ─────────────────────────── D-PAD 探测的纯逻辑（误判面） ───────────────────────────

    @Test
    fun `遥控器这类独立 D-PAD 设备应判定为具备导航能力`() {
        // 遥控器 / 蓝牙键盘 / 手柄：独立 device，声明 DPAD 但不是触摸数字化器
        assertTrue("独立 DPAD 设备应命中", isRemoteNavigationDevice(hasDpad = true, isTouchDigitizer = false))
    }

    @Test
    fun `触摸数字化器即使顺带支持 DPAD 也不得命中`() {
        // 部分 ROM / 模拟器给内建触摸设备多报 source 位 —— 那不是遥控器，
        // 命中它会让裸机手机误判成远控设备（v2.36.0 P2-34 的回归形态）
        assertFalse(
            "TOUCHSCREEN + DPAD 不得命中（防手机误判）",
            isRemoteNavigationDevice(hasDpad = true, isTouchDigitizer = true),
        )
    }

    @Test
    fun `裸机手机的典型设备组合一律不命中`() {
        // 手机上通常只挂着触摸数字化器（无 DPAD）；键盘类设备只报 KEYBOARD 不报 DPAD
        val phoneCases = listOf(
            Triple("触摸数字化器", false, true),
            Triple("纯触摸", false, false),
            Triple("无任何能力", false, false),
            Triple("软键盘（只报 KEYBOARD）", false, false),
        )
        val wrong = phoneCases.filter { (label, dpad, touch) ->
            isRemoteNavigationDevice(hasDpad = dpad, isTouchDigitizer = touch)
        }
        assertTrue("裸机手机不应被判为具备导航能力：${wrong.map { it.first }}", wrong.isEmpty())
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

    // ─────────────── functionBody 的自证（它服务于上面两条「缓存」门禁） ───────────────

    @Test
    fun `负向自证 functionBody 能正确取出块体函数`() {
        val text = """
            fun outer() {
                val nested = mapOf("a" to 1)
                if (nested.isNotEmpty()) { println(nested) }
            }
            fun after() = 1
        """.trimIndent()
        val body = functionBody(text, "fun outer()")
        assertTrue("块体函数应含其嵌套大括号", body.contains("println(nested)"))
        assertFalse("块体函数不得吞进下一个函数", body.contains("fun after()"))
    }

    @Test
    fun `负向自证 functionBody 能正确取出表达式体函数`() {
        // `fun f(): Boolean = expr` 没有大括号 —— 按「找下一个 { 」切会切错
        val text = """
            fun expr(): Boolean = looksLike || dpadPresent
            fun later() {
                val x = 1
            }
        """.trimIndent()
        val body = functionBody(text, "fun expr(): Boolean")
        assertTrue("表达式体应被取出", body.contains("dpadPresent"))
        assertFalse("表达式体不得吞进后面的块体函数", body.contains("val x = 1"))
    }

    @Test
    fun `负向自证 functionBody 跳过字符串字面量里的括号`() {
        val text = """
            fun withString(): Boolean {
                val q = "}"
                return q.isNotEmpty()
            }
        """.trimIndent()
        val body = functionBody(text, "fun withString(): Boolean")
        assertTrue("字面量里的 } 不应提前截断函数体", body.contains("q.isNotEmpty()"))
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

/**
 * 取出某个函数声明的函数体（含签名那一行），按括号深度配平。
 *
 * ⛔ 不能用「下一个空行」或「下一个 `fun `」切段 —— Kotlin 表达式体函数
 * （`fun f() = expr`）没有大括号，而块体函数的 body 里常含嵌套 lambda。
 * 按深度配平才既覆盖两种形态、又不把嵌套 lambda 提前截断。
 */
private fun functionBody(text: String, signature: String): String {
    val start = text.indexOf(signature)
    require(start >= 0) { "找不到函数签名：$signature" }
    val open = text.indexOf('{', start)
    // 表达式体：`fun f(): Boolean = …` 到行尾为止（不跨行，避免误吞下一段）
    if (open < 0 || text.substring(start, open).contains('=')) {
        val lineEnd = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        return text.substring(start, lineEnd)
    }
    var depth = 0
    var i = open
    while (i < text.length) {
        val ch = text[i]
        // 跳过字符串字面量，避免字面量里的括号把深度带偏
        if (ch == '"') {
            i++
            while (i < text.length && text[i] != '"') i += if (text[i] == '\\') 2 else 1
        } else {
            if (ch == '{') depth++ else if (ch == '}') {
                depth--
                if (depth == 0) return text.substring(start, i + 1)
            }
        }
        i++
    }
    return text.substring(start)
}