package com.nasmusic.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState

/**
 * Level 1: 对话框 BACK 键回调 —— 任何对话框（输入对话框、退出确认等）打开时设置。
 * 优先级最高，按下 BACK 键首先关闭打开的对话框。
 * 对话框关闭时，将此状态重置为 null。
 *
 * ⚠️ 注册请统一走 [RegisterDialogBackHandler]。直接写 `DisposableEffect(onDismiss)`
 * 会以 lambda 为 key，父重组瞬间先 onDispose（置 null）再重新注册，存在 handler
 * 短暂为 null 的竞态窗口——此时按 BACK 会穿透到 Level 3 应用退出确认。
 */
val LocalDialogBackHandler = compositionLocalOf<MutableState<(() -> Unit)?>> {
    mutableStateOf(null)
}

/**
 * Level 1.5: 列表回到顶部回调 —— 当前列表已向下滚动时，按 BACK 先滚动到顶部。
 * 返回 true 表示已消费（已滚动），false 表示已在顶部（让事件继续传递到 Level 2）。
 */
val LocalListBackHandler = compositionLocalOf<MutableState<(() -> Boolean)?>> {
    mutableStateOf(null)
}

/**
 * Level 2: 页面导航 BACK 键回调 —— 当不在 NowPlaying 页面时，设置为导航到 NowPlaying 的 lambda。
 * 由 AppRoot 根据当前屏幕状态动态设置；当在 NowPlaying 页面时设置为 null。
 */
val LocalNavigateBackHandler = compositionLocalOf<MutableState<(() -> Unit)?>> {
    mutableStateOf(null)
}

/**
 * Level 3: 退出确认对话框显示标志 —— 当在 NowPlaying 页面且无对话框时，
 * 按下 BACK 键将此值设为 true，Compose 树据此渲染退出确认对话框。
 */
val LocalShowExitConfirm = compositionLocalOf<MutableState<Boolean>> {
    mutableStateOf(false)
}

/**
 * 统一注册 Level 1 对话框 BACK 回调（本工程对话框 BACK 的唯一入口）。
 *
 * 用 [rememberUpdatedState] 持有最新回调、[DisposableEffect] 以 `Unit` 为 key，
 * 只注册/注销一次 —— 消除「以 lambda 为 key」造成的注册空窗竞态。
 *
 * 适用范围：以 `Box` 覆盖层实现的对话框（BACK 事件经 Activity 的
 * `onBackPressedDispatcher` 派发）。若对话框是真正的 `Dialog {}`（独立窗口，
 * Activity 的 dispatcher 收不到按键），必须在 Dialog 内容内使用
 * `androidx.activity.compose.BackHandler` —— 两者不可互换。
 */
@Composable
fun RegisterDialogBackHandler(onBack: () -> Unit) {
    val handler = LocalDialogBackHandler.current
    val currentOnBack by rememberUpdatedState(onBack)
    DisposableEffect(Unit) {
        handler.value = { currentOnBack() }
        onDispose { handler.value = null }
    }
}
