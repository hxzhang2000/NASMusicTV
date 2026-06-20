package com.nasmusic.tv.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Level 1: 对话框 BACK 键回调 —— 任何对话框（输入对话框、退出确认等）打开时设置。
 * 优先级最高，按下 BACK 键首先关闭打开的对话框。
 * 对话框关闭时，将此状态重置为 null。
 */
val LocalDialogBackHandler = staticCompositionLocalOf<MutableState<(() -> Unit)?>> {
    mutableStateOf(null)
}

/**
 * Level 2: 页面导航 BACK 键回调 —— 当不在 NowPlaying 页面时，设置为导航到 NowPlaying 的 lambda。
 * 由 AppRoot 根据当前屏幕状态动态设置；当在 NowPlaying 页面时设置为 null。
 */
val LocalNavigateBackHandler = staticCompositionLocalOf<MutableState<(() -> Unit)?>> {
    mutableStateOf(null)
}

/**
 * Level 3: 退出确认对话框显示标志 —— 当在 NowPlaying 页面且无对话框时，
 * 按下 BACK 键将此值设为 true，Compose 树据此渲染退出确认对话框。
 */
val LocalShowExitConfirm = staticCompositionLocalOf<MutableState<Boolean>> {
    mutableStateOf(false)
}
