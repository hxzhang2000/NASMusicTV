package com.nasmusic.tv.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Text
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.R
import com.nasmusic.tv.player.PlaybackService
import com.nasmusic.tv.ui.components.AppRoot
import com.nasmusic.tv.ui.components.ConnectPromptDialog
import com.nasmusic.tv.ui.screens.ExitConfirmDialog
import com.nasmusic.tv.ui.theme.NASMusicTVTheme
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.viewmodel.MainViewModel
import com.nasmusic.tv.ui.viewmodel.Screen
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.MediaKeyHandler
import com.nasmusic.tv.util.NetworkMonitor
import kotlinx.coroutines.launch

/**
 * 主 TV Activity —— NAS Music TV
 */
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    // Level 1: 对话框 BACK 键回调 —— 当对话框（输入对话框、退出确认等）打开时设置
    private val dialogBackHandler: MutableState<(() -> Unit)?> = mutableStateOf(null)
    // Level 2: 页面导航 BACK 键回调 —— 当不在 NowPlaying 页面时设置为导航函数
    private val navigateBackHandler: MutableState<(() -> Unit)?> = mutableStateOf(null)
    // Level 3: 退出确认对话框显示标志 —— 在 NowPlaying 页面时按下 BACK 设为 true
    private val showExitConfirm: MutableState<Boolean> = mutableStateOf(false)
    // 全屏沉浸模式状态 — 由 AppRoot 持有一份引用，同时 Activity.onKeyDown 也需要读取
    private val isImmersiveMode: MutableState<Boolean> = mutableStateOf(false)
    private lateinit var networkMonitor: NetworkMonitor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val settings by viewModel.appSettings.collectAsState(initial = com.nasmusic.tv.data.model.AppSettings())
            NASMusicTVTheme(darkTheme = settings.darkTheme) {
                // 暴露当前 Activity 给子组件，用于注册对话框的 BACK 键处理
                CompositionLocalProvider(
                    LocalDialogBackHandler provides dialogBackHandler,
                    LocalNavigateBackHandler provides navigateBackHandler,
                    LocalShowExitConfirm provides showExitConfirm
                ) {
                    val showConnectPrompt by viewModel.showConnectPrompt.collectAsState(initial = false)
                    val connectMessage by viewModel.connectMessage.collectAsState(initial = null)
                    val errorMessage by viewModel.errorMessage.collectAsState(initial = null)

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(NasMusicColors.Background)
                    ) {
                        AppRoot(
                            viewModel = viewModel,
                            isImmersiveMode = isImmersiveMode,
                            onConnect = { config ->
                                lifecycleScope.launch {
                                    viewModel.connectToServer(config)
                                }
                            }
                        )

                        // Level 3: 退出确认对话框（在 NowPlaying 页面按 BACK 键时显示）
                        if (showExitConfirm.value) {
                            ExitConfirmDialog(
                                onConfirm = {
                                    showExitConfirm.value = false
                                    val app = application as NasMusicApp
                                    // 释放播放器资源
                                    app.playerManager.release()
                                    stopService(Intent(this@MainActivity, PlaybackService::class.java))
                                    // 同步注销 Jellyfin session，确保 HTTP 请求完成后再杀进程
                                    kotlinx.coroutines.runBlocking {
                                        try {
                                            app.backendRegistry.disconnect()
                                            AppLog.d("MainActivity", "exit: backend disconnected")
                                        } catch (e: Exception) {
                                            android.util.Log.w("MainActivity", "exit: disconnect failed", e)
                                        }
                                    }
                                    finishAffinity()
                                    android.os.Process.killProcess(android.os.Process.myPid())
                                },
                                onDismiss = { showExitConfirm.value = false }
                            )
                        }

                        // 启动连接提示对话框
                        if (showConnectPrompt) {
                            ConnectPromptDialog(
                                serverDisplayName = viewModel.serverDisplayName.value,
                                onConfirm = { viewModel.connectToSavedServer() },
                                onDismiss = { viewModel.dismissConnectPrompt() }
                            )
                        }

                        // D-3: 错误提示消息（数据加载/操作失败时显示，5秒后自动清除）
                        if (errorMessage != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.TopCenter)
                                    .padding(top = 80.dp)
                                    .background(
                                        color = NasMusicColors.Danger.copy(alpha = 0.9f),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .padding(horizontal = 32.dp, vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = errorMessage!!,
                                    color = NasMusicColors.TextPrimary,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        // 连接结果提示消息（短时悬浮显示）
                        if (connectMessage != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .align(Alignment.TopCenter)
                                    .padding(top = if (errorMessage != null) 140.dp else 80.dp)
                                    .background(
                                        color = NasMusicColors.Surface.copy(alpha = 0.95f),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .padding(horizontal = 32.dp, vertical = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = connectMessage!!,
                                    color = NasMusicColors.TextPrimary,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            }
        }

        // 分层 BACK 键处理：Level 0 → Level 1 → Level 2 → Level 3
        // Level 0: 沉浸模式 → 退出全屏
        // Level 1: 关闭对话框（由 dialogBackHandler 控制）
        // Level 2: 从其他页面导航回播放页（由 AppRoot 动态设置 navigateBackHandler）
        // Level 3: 在播放页显示退出确认（设置 showExitConfirm = true）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Level 0: 沉浸模式下 BACK → 退出全屏，不往下传递
                if (isImmersiveMode.value) {
                    isImmersiveMode.value = false
                    return
                }

                // Level 1: 如果有对话框打开 → 先关闭对话框
                val dialogHandler = dialogBackHandler.value
                if (dialogHandler != null) {
                    dialogHandler()
                    return
                }

                // Level 2: 如果不在 NowPlaying 页面 → 导航回播放页
                val navHandler = navigateBackHandler.value
                if (navHandler != null) {
                    navHandler()
                    return
                }

                // Level 3: 已经在 NowPlaying 页面 → 显示退出确认
                showExitConfirm.value = true
            }
        })

        // Android TV: 确保窗口可聚焦
        window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 启动播放服务
        startService(Intent(this, PlaybackService::class.java))

        // D-2: 网络状态监听
        networkMonitor = NetworkMonitor(
            context = this,
            onNetworkAvailable = { viewModel.onNetworkAvailable() },
            onNetworkLost = { viewModel.onNetworkLost() }
        )
        networkMonitor.register()
    }

    override fun onDestroy() {
        super.onDestroy()
        networkMonitor.unregister()
        // 兜底清理：确保播放服务和后端连接被释放
        // 正常退出流程（退出对话框）已在 onConfirm 中处理，这里处理异常退出场景
        val app = (application as NasMusicApp)
        // 停止播放服务（如果仍在运行）
        try {
            stopService(Intent(this, PlaybackService::class.java))
        } catch (_: Exception) {}
        // 释放播放器资源（Handler、listener）
        try {
            app.playerManager.release()
        } catch (_: Exception) {}
        // 应用退出时断开后端连接，释放 OkHttp 连接池，防止连接泄漏
        // 使用 applicationScope 确保断开操作在 Activity 销毁后仍能执行
        app.applicationScope.launch {
            try {
                app.backendRegistry.disconnect()
                AppLog.d("MainActivity", "onDestroy: backend disconnected")
            } catch (e: Exception) {
                android.util.Log.w("MainActivity", "onDestroy: disconnect failed", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Android TV: 主动请求窗口焦点
        window.decorView.requestFocus()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // G-1: HDMI-CEC / 蓝牙遥控器媒体键映射
        val handled = MediaKeyHandler.handleKeyEvent(
            keyCode = keyCode,
            event = event,
            viewModel = viewModel,
            isImmersiveMode = isImmersiveMode.value,
            currentScreen = viewModel.currentScreen.value
        )
        if (handled) return true

        // MediaKeyHandler 未处理的键（如沉浸模式下的 OK 键退出全屏）
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (isImmersiveMode.value) {
                isImmersiveMode.value = false
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

}
