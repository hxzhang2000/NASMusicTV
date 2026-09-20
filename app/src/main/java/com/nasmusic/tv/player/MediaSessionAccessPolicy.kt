package com.nasmusic.tv.player

import android.os.Bundle
import android.os.Process
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands

/**
 * MediaSession 连接准入策略（纯逻辑，可 JVM 单测）。
 *
 * ## 为什么单独抽出来
 *
 * 这段逻辑原先内联在 `PlaybackService.onConnect` 里，**没有单测能覆盖**，
 * 结果一个「拒绝连接」的写法把系统侧全部媒体控制打死了（见 `docs/technical-overview.md` §10.165）。
 * 抽成不依赖 `MediaSession` 实例的纯函数后，`testDebugUnitTest` 能直接断言，
 * 并额外用源码扫描门禁锁住「不得再出现 reject」。
 *
 * ## 两条硬规则
 *
 * 1. **连接一律接受，绝不 `reject()`**。
 *    Android 上蓝牙 AVRCP / SystemUI / 车机等系统侧组件，是通过**框架**
 *    `android.media.session.MediaController` 来控制的。这条链路在 Media3 里必然经过
 *    `MediaSessionLegacyStub.tryGetController() → sessionImpl.onConnectOnHandler() → Callback.onConnect`；
 *    一旦这里返回 `reject()`，Media3 会立刻 `onDisconnected` 并**丢弃该命令**
 *    （源码：`MediaSessionLegacyStub.java:757-780` 返回 null，`:644-651` 直接 return）。
 *    表现为「车机暂停/上一曲/下一曲全部无反应」，且日志里只有一行 rejected。
 *
 * 2. **可用会话命令「只增不减」**。
 *    基线取 Media3 给 `MediaLibrarySession` 的默认集合
 *    （`ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS`，见 `MediaSession.java:1604-1607`），
 *    再叠加本应用的两条自定义命令。原因是
 *    `ConnectedControllersManager.isSessionCommandAvailable()` 是**严格集合成员判定**
 *    （源码 `ConnectedControllersManager.java`，非叠加语义）——
 *    若像旧实现那样把默认集合整个替换掉，被接受的控制器会连媒体库浏览命令都拿不到。
 *
 * ## 安全边界放在哪
 *
 * 原来的白名单挂在「是否接受连接」上，既没提供实质保护（`BuildConfig.DEBUG` 直接全放行，
 * 且会话本来就是系统可控制的），又把系统控制链路全部打断。
 * 现在改为：**连接一律接受，安全边界收敛到「专有命令只下发给可信调用方」**——
 * 播放模式 / 睡眠定时是本应用独有的能力，非可信调用方拿不到；
 * 而播放/暂停/上下曲/浏览属于平台标准能力，按 Media3 默认发放。
 *
 * 可信判定仍以调用方 uid / 包名 + Media3 的 automotive / auto-companion 判定为准，
 * 见 [isTrustedCaller]。
 */
// Media3 的 UnstableApi 走 androidx 的 @RequiresOptIn 机制，必须用 androidx.annotation.OptIn。
@androidx.annotation.OptIn(UnstableApi::class)
object MediaSessionAccessPolicy {

    /** 通知栏 / 系统媒体卡片上的「播放模式」按钮动作 */
    const val ACTION_TOGGLE_PLAY_MODE = "com.nasmusic.tv.action.TOGGLE_PLAY_MODE"

    /** 通知栏 / 系统媒体卡片上的「睡眠定时」按钮动作 */
    const val ACTION_SLEEP_TIMER_CYCLE = "com.nasmusic.tv.action.SLEEP_TIMER_CYCLE"

    /** Google 助理（手机端）包名 */
    const val PKG_GOOGLE_ASSISTANT = "com.google.android.googlequicksearchbox"

    /** Gemini / Google 助理（AAOS 端）包名 */
    const val PKG_GOOGLE_ASSISTANT_AUTOMOTIVE = "com.google.android.carassistant"

    /** 除 uid/自身/Media3 判定外，额外放行的包名 */
    private val EXTRA_TRUSTED_PACKAGES = setOf(
        PKG_GOOGLE_ASSISTANT,
        PKG_GOOGLE_ASSISTANT_AUTOMOTIVE,
    )

    /**
     * 调用方身份快照。
     *
     * 刻意做成不依赖 `MediaSession` / `ControllerInfo` 的纯数据类 —— 那两个类型在
     * 纯 JVM 单测里无法构造，抽出来后本策略即可被直接断言。
     *
     * @param uid 调用方 uid（`ControllerInfo.getUid()`）
     * @param packageName 调用方包名（`ControllerInfo.getPackageName()`）
     * @param isSelfPackage 调用方即本应用（通知栏按钮 / 本应用内控制器）
     * @param isMedia3Automotive Media3 判定的 AAOS 控制器（`MediaSession.isAutomotiveController`）
     * @param isMedia3AutoCompanion Media3 判定的 Android Auto 配套应用（`MediaSession.isAutoCompanionController`）
     */
    data class CallerIdentity(
        val uid: Int,
        val packageName: String?,
        val isSelfPackage: Boolean = false,
        val isMedia3Automotive: Boolean = false,
        val isMedia3AutoCompanion: Boolean = false,
    )

    /**
     * 是否可信 —— **仅决定「要不要下发本应用专有的两条自定义命令」**，
     * 不再决定「要不要接受连接」（见类注释规则 1）。
     *
     * @param debug DEBUG 构建全放行：避免白名单不全导致 DHU / 真机调试时
     *   「自定义按钮莫名点不动」。注意这**不再**掩盖任何连接被拒的问题。
     */
    fun isTrustedCaller(caller: CallerIdentity, debug: Boolean): Boolean {
        if (debug) return true
        if (caller.uid == Process.SYSTEM_UID) return true
        if (caller.isSelfPackage || caller.isMedia3Automotive || caller.isMedia3AutoCompanion) return true
        return caller.packageName != null && caller.packageName in EXTRA_TRUSTED_PACKAGES
    }

    /**
     * 该调用方可用的会话命令集合。
     *
     * ⚠️ **只增不减**：始终以 Media3 的 `DEFAULT_SESSION_AND_LIBRARY_COMMANDS` 为基线
     * （`MediaSession.java:1604-1607` 即 `MediaLibrarySession` 的 `AcceptedResultBuilder` 默认值），
     * 可信调用方再叠加两条自定义命令。**不要**改成 `SessionCommands.Builder()` 从空集重建 ——
     * `ConnectedControllersManager.isSessionCommandAvailable()` 是严格成员判定，
     * 从空集重建会把媒体库浏览命令一起丢掉。
     *
     * 播放/暂停/上下曲属于 **player 命令**，由 `availablePlayerCommands` 控制
     * （此处未收窄，保持 `DEFAULT_PLAYER_COMMANDS`），因此与本次改动无关。
     */
    fun availableSessionCommands(trusted: Boolean): SessionCommands {
        val builder = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
        if (trusted) {
            builder.add(SessionCommand(ACTION_TOGGLE_PLAY_MODE, Bundle.EMPTY))
            builder.add(SessionCommand(ACTION_SLEEP_TIMER_CYCLE, Bundle.EMPTY))
        }
        return builder.build()
    }
}
