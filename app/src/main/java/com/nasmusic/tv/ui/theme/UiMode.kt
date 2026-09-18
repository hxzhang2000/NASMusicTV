package com.nasmusic.tv.ui.theme

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 形态因子（Form Factor）—— 竖屏 UI 方案 §3.1。
 *
 * - [TV]：电视端（含只上报 `android.hardware.type.television` 的非认证盒子），永不进竖屏分支
 * - [PhonePortrait]：**唯一**走竖屏新界面（PhoneTopBar + MiniPlayer + PhoneNavBar + 两级设置页）的形态
 * - [PhoneLandscape]：手机横屏，**复用 TV 横屏布局的现状代码路径**（不重构，见 D6 / B1）
 *
 * ⚠️ 硬规则（v1.4 B1）：分支谓词一律写成 `uiMode == UiMode.PhonePortrait` /
 * `uiMode != UiMode.PhonePortrait`，**不要**写 `== / != UiMode.TV` ——
 * `else` 分支的语义是「原样保留现状」，而现状里手机横屏与 TV 本就不同
 * （`LocalPhoneCompact` 在横屏手机也为 true、`adaptiveColumns` 走 `>=600` 那一支）。
 */
enum class UiMode { TV, PhonePortrait, PhoneLandscape }

/** 当前形态因子（由 `MainActivity` 提供，默认 TV 以保持既有测试/预览行为） */
val LocalUiMode = staticCompositionLocalOf { UiMode.TV }

/**
 * 纯函数：设备类型 + 屏幕方向 → 形态因子。
 *
 * ⚠️ TV 判定优先 —— TV 永不进竖屏分支（即使某个 TV 盒子上报了 PORTRAIT）。
 *
 * 不依赖 Compose / Android 运行时（`Configuration.ORIENTATION_*` 是编译期常量），
 * 因此可在纯 JVM 单测中直接断言。
 */
fun deriveUiMode(isTV: Boolean, orientation: Int): UiMode = when {
    isTV -> UiMode.TV
    orientation == Configuration.ORIENTATION_PORTRAIT -> UiMode.PhonePortrait
    else -> UiMode.PhoneLandscape
}

/**
 * 纯函数：全局策略（L1 pref）+ 是否全屏页 → `requestedOrientation`。
 *
 * | pref | 结果 | 说明 |
 * |------|------|------|
 * | `"portrait"` | `USER_PORTRAIT` | 强制竖屏，但**尊重系统旋转锁**（API 18+，minSdk 22 安全） |
 * | `"landscape"` | `USER_LANDSCAPE` | 同上，横屏 |
 * | 其它 / 空串 | `UNSPECIFIED` | 跟随传感器 + 尊重系统旋转锁（防御 DataStore 脏数据） |
 *
 * ⚠️ 不要用 `SENSOR` / `FULL_SENSOR` —— 两者都会忽略用户的系统旋转锁（方案 C6）。
 *
 * @param isFullScreenPage MTV / K 歌 / 可视化舞台等强制横屏页
 */
fun resolveOrientation(pref: String, isFullScreenPage: Boolean): Int = when {
    isFullScreenPage -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    pref == "portrait" -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
    pref == "landscape" -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
    else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
}

/** 屏幕方向偏好取值（[resolveOrientation] 的输入域） */
object ScreenOrientationPref {
    const val AUTO = "auto"
    const val PORTRAIT = "portrait"
    const val LANDSCAPE = "landscape"

    /**
     * L2 顶部栏方向按钮：在「竖屏 ⟷ 横屏」二态间循环（**无长按**，D1 / D8）。
     * "自动" 态点一下 → 落 "竖屏"（最常见选择）；想回自动只能进设置项（L1）。
     */
    fun nextOnToggle(current: String): String = when (current) {
        PORTRAIT -> LANDSCAPE
        LANDSCAPE -> PORTRAIT
        else -> PORTRAIT
    }
}
