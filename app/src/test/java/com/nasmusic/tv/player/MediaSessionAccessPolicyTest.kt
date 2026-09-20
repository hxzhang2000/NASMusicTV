package com.nasmusic.tv.player

import android.os.Bundle
import android.os.Process
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * `MediaSessionAccessPolicy` 的门禁测试 —— 锁住 v2.36.0「车机蓝牙按键全部失效」不再复发。
 *
 * ## 背景（为什么必须有这条门禁）
 *
 * `PlaybackService.onConnect` 原先对白名单外的调用方 `return ConnectionResult.reject()`。
 * 系统侧（蓝牙 AVRCP / SystemUI / 车机）走的是**框架** `android.media.session.MediaController`，
 * 该链路在 Media3 里必然经过 `MediaSessionLegacyStub.tryGetController() → Callback.onConnect`；
 * 一旦拒绝，Media3 立即 `onDisconnected` 并**丢弃命令**（源码 `MediaSessionLegacyStub.java:757-780`
 * 返回 null、`:644-651` 直接 return）→ 暂停/上一曲/下一曲全部静默无效。
 *
 * 这个缺陷**编译过、lint 过、既有单测全绿**，只在上机用 release 包 + 车机蓝牙时才暴露，
 * 且被 `BuildConfig.DEBUG` 全放行掩盖。故按 `SmallTouchTargetScanTest` 同范式固化为门禁。
 *
 * ## 三层自证（缺一不可）
 *
 * 1. **行为断言**：可用会话命令「只增不减」（覆盖 Media3 默认集合），专有命令只给可信调用方；
 * 2. **负向自证**：合成样本里写 `ConnectionResult.reject()` 必须被判违规 —— 证明"该命中时会命中"；
 * 3. **空转自证**：断言真的读到了 `PlaybackService.kt`、且该文件里存在本次修复的标志调用，
 *    否则"扫描通过"可能只是因为读错文件或正则失配。
 *
 * ⚠️ 断言一律用 `assertTrue` / `assertFalse`：Kotlin 的 `assert` 在测试 JVM 上是空操作。
 *
 * 详见 `docs/technical-overview.md` §10.165。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
// Media3 的 UnstableApi 走 androidx @RequiresOptIn 机制，须用 androidx.annotation.OptIn。
@androidx.annotation.OptIn(UnstableApi::class)
class MediaSessionAccessPolicyTest {

    // ─────────────────── 行为：可用会话命令「只增不减」 ───────────────────

    /**
     * `ConnectedControllersManager.isSessionCommandAvailable()` 是**严格集合成员判定**
     * （非叠加语义），所以一旦有人把命令集合改成 `SessionCommands.Builder()` 从空集重建，
     * 被接受的控制器会连 Media3 给 `MediaLibrarySession` 准备的媒体库浏览命令一起丢掉。
     */
    @Test
    fun `可用会话命令必须覆盖 Media3 默认集合`() {
        val defaults = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
        // 空转自证：默认集合本身为空时下面的循环恒真
        assertTrue("Media3 默认会话命令集合为空 —— 本断言可能空转", defaults.commands.isNotEmpty())

        for (trusted in listOf(false, true)) {
            val actual = MediaSessionAccessPolicy.availableSessionCommands(trusted)
            for (cmd in defaults.commands) {
                assertTrue(
                    "trusted=$trusted 时丢失了 Media3 默认会话命令 $cmd —— " +
                        "会话命令是严格成员判定，不能从空集重建",
                    actual.contains(cmd),
                )
            }
        }
    }

    @Test
    fun `专有命令只下发给可信调用方`() {
        val toggle = SessionCommand(MediaSessionAccessPolicy.ACTION_TOGGLE_PLAY_MODE, Bundle.EMPTY)
        val sleep = SessionCommand(MediaSessionAccessPolicy.ACTION_SLEEP_TIMER_CYCLE, Bundle.EMPTY)

        val trusted = MediaSessionAccessPolicy.availableSessionCommands(trusted = true)
        assertTrue("可信调用方拿不到「播放模式」命令", trusted.contains(toggle))
        assertTrue("可信调用方拿不到「睡眠定时」命令", trusted.contains(sleep))

        val untrusted = MediaSessionAccessPolicy.availableSessionCommands(trusted = false)
        assertFalse("不可信调用方不应拿到「播放模式」命令", untrusted.contains(toggle))
        assertFalse("不可信调用方不应拿到「睡眠定时」命令", untrusted.contains(sleep))
    }

    // ─────────────────── 行为：可信判定正负向 ───────────────────

    /**
     * 负向：车机蓝牙链路里的调用方（蓝牙协议栈 / SystemUI / 任意第三方应用）
     * **不得**被判为可信 —— 但这**只影响专有命令**，不影响它们能否连接并控制播放
     * （连接一律接受，见 `PlaybackService.onConnect`）。
     */
    @Test
    fun `蓝牙与第三方调用方不得被判为可信`() {
        val untrustedPackages = listOf(
            "com.android.bluetooth", // 车机蓝牙 AVRCP target
            "com.android.systemui", // 系统媒体控制（非系统 uid 时）
            "com.android.settings",
            "com.tencent.mm",
            null, // 包名不可得
        )
        for (pkg in untrustedPackages) {
            assertFalse(
                "包名 $pkg 不应被判为可信（专有命令不该下发给它）",
                MediaSessionAccessPolicy.isTrustedCaller(
                    MediaSessionAccessPolicy.CallerIdentity(uid = 10123, packageName = pkg),
                    debug = false,
                ),
            )
        }
    }

    @Test
    fun `系统与车机调用方必须被判为可信`() {
        fun trusted(
            uid: Int = 10123,
            pkg: String? = "com.example.other",
            self: Boolean = false,
            automotive: Boolean = false,
            autoCompanion: Boolean = false,
        ) = MediaSessionAccessPolicy.isTrustedCaller(
            MediaSessionAccessPolicy.CallerIdentity(uid, pkg, self, automotive, autoCompanion),
            debug = false,
        )

        assertTrue("系统 uid 应可信", trusted(uid = Process.SYSTEM_UID))
        assertTrue("本应用自身应可信", trusted(self = true))
        assertTrue("AAOS 控制器应可信", trusted(automotive = true))
        assertTrue("Android Auto 配套应用应可信", trusted(autoCompanion = true))
        assertTrue(
            "Google 助理（手机端）应可信",
            trusted(pkg = MediaSessionAccessPolicy.PKG_GOOGLE_ASSISTANT),
        )
        assertTrue(
            "Gemini（AAOS 端）应可信",
            trusted(pkg = MediaSessionAccessPolicy.PKG_GOOGLE_ASSISTANT_AUTOMOTIVE),
        )
    }

    @Test
    fun `DEBUG 构建全放行以便调试自定义按钮`() {
        assertTrue(
            "DEBUG 构建应全放行（DHU / 真机调试自定义按钮）",
            MediaSessionAccessPolicy.isTrustedCaller(
                MediaSessionAccessPolicy.CallerIdentity(uid = 10123, packageName = "com.tencent.mm"),
                debug = true,
            ),
        )
    }

    // ─────────────────── 源码扫描门禁：onConnect 不得拒绝连接 ───────────────────

    @Test
    fun `onConnect 不得拒绝任何控制器`() {
        val file = File(mainSourceRoot(), "player/PlaybackService.kt")
        assertTrue("找不到 PlaybackService.kt：${file.absolutePath}", file.isFile)
        val source = file.readText()

        // 空转自证 ①：必须读到预期文件（含本次修复的标志调用）
        assertTrue(
            "PlaybackService.kt 里没有 MediaSessionAccessPolicy.availableSessionCommands 调用 —— " +
                "读到的可能不是预期文件，扫描结果不可信",
            source.contains("MediaSessionAccessPolicy.availableSessionCommands("),
        )
        // 空转自证 ②：扫描前提成立（确实存在 onConnect 定义）
        assertTrue(
            "PlaybackService.kt 里找不到 `override fun onConnect(` —— 扫描前提不成立",
            source.contains("override fun onConnect("),
        )

        val hits = rejectHits(source.lines())
        assertTrue(
            "onConnect 又出现了拒绝连接的写法，位置：$hits\n" +
                "后果：蓝牙 AVRCP / SystemUI / 车机的媒体控制会被 Media3 直接丢弃，\n" +
                "      表现为「车机暂停/上一曲/下一曲全部无反应」。\n" +
                "正确做法：连接一律接受，安全边界放到 setAvailableSessionCommands\n" +
                "         （见 MediaSessionAccessPolicy 与 docs/technical-overview.md §10.165）。",
            hits.isEmpty(),
        )
    }

    /** 负向自证：真的写了 `reject()` 必须被抓到，否则"扫描通过"只是护栏空转。 */
    @Test
    fun `拒绝连接的写法必须被判违规`() {
        val hits = rejectHits(
            """
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                if (!isTrustedCaller(session, controller)) {
                    return MediaSession.ConnectionResult.reject()
                }
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session).build()
            }
            """.trimIndent().lines(),
        )
        assertTrue("护栏失效：`ConnectionResult.reject()` 没被判违规", hits.isNotEmpty())
    }

    /** 误报自证：注释里提到 `reject()`（本文件的说明注释就大量提及）不得被判违规。 */
    @Test
    fun `注释里提到 reject 不得被误判`() {
        val hits = rejectHits(
            listOf(
                " * ⛔ 本方法绝不能 `reject()` —— 会打断系统控制链路",
                "// 旧实现曾 return MediaSession.ConnectionResult.reject()",
                "/* reject() 说明 */",
            ),
        )
        assertTrue("误报：注释里的 reject 被当成违规（注释剥离失效）", hits.isEmpty())
    }

    // ─────────────────── 辅助 ───────────────────

    /**
     * 扫描「拒绝连接」的写法。先剥离注释再匹配，避免把说明性注释误判为违规
     * （`\breject\s*\(` 会匹配到 `reject()`）。
     *
     * @return 命中的行号（1-based），空表示未命中
     */
    private fun rejectHits(lines: List<String>): List<Int> =
        lines.mapIndexedNotNull { index, raw ->
            val trimmed = raw.trim()
            val isCommentOnly = trimmed.startsWith("//") ||
                trimmed.startsWith("*") ||
                trimmed.startsWith("/*")
            if (isCommentOnly) return@mapIndexedNotNull null
            val code = trimmed.substringBefore("//")
            if (REJECT_CALL.containsMatchIn(code)) index + 1 else null
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

/** 匹配「拒绝连接」的调用（`reject()` / `.reject(`）。 */
private val REJECT_CALL = Regex("""\breject\s*\(""")
