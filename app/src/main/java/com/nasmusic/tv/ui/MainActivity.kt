package com.nasmusic.tv.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

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
import com.nasmusic.tv.ui.theme.FontSize
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.viewmodel.MainViewModel
import com.nasmusic.tv.data.model.Screen
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.MediaKeyHandler
import com.nasmusic.tv.util.NetworkMonitor
import kotlinx.coroutines.launch

/**
 * 主 TV Activity —— NAS Music TV
 */
// UnstableApi 属 androidx @RequiresOptIn 机制，须用 androidx.annotation.OptIn。
@androidx.annotation.OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    // Level 1: 对话框 BACK 键回调 —— 当对话框（输入对话框、退出确认等）打开时设置
    private val dialogBackHandler: MutableState<(() -> Unit)?> = mutableStateOf(null)
    // Level 1.5: 列表回到顶部回调 —— 列表已滚动时按 BACK 先回顶
    private val listBackHandler: MutableState<(() -> Boolean)?> = mutableStateOf(null)
    // Level 2: 页面导航 BACK 键回调 —— 当不在 NowPlaying 页面时设置为导航函数
    private val navigateBackHandler: MutableState<(() -> Unit)?> = mutableStateOf(null)
    // Level 3: 退出确认对话框显示标志 —— 在 NowPlaying 页面时按下 BACK 设为 true
    private val showExitConfirm: MutableState<Boolean> = mutableStateOf(false)
    // 全屏沉浸模式状态 — 由 AppRoot 持有一份引用，同时 Activity.onKeyDown 也需要读取
    private val isImmersiveMode: MutableState<Boolean> = mutableStateOf(false)
    private lateinit var networkMonitor: NetworkMonitor

    // SAF 树选择器（§8.8.4）：导出到外接设备时启动系统文件夹选择器
    private val exportTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val app = application as NasMusicApp
        if (uri != null) {
            app.exportCoordinator.onTreeGranted(uri)
        } else {
            app.exportCoordinator.onTreeUnavailable()
        }
    }

    /**
     * 在每次 Activity 创建（含 recreate）时应用存储的语言设置。
     * resources.updateConfiguration() 仅影响 Application 级别资源，
     * 通过 attachBaseContext 创建带正确 locale 的 Context，确保 Compose 使用正确的资源配置。
     */
    override fun attachBaseContext(newBase: Context) {
        val lang = com.nasmusic.tv.data.prefs.AppPreferences.getInstance(newBase).getLanguageSync()
        val locale = when (lang) {
            "zh" -> java.util.Locale.SIMPLIFIED_CHINESE
            "en" -> java.util.Locale.US
            else -> com.nasmusic.tv.NasMusicApp.getSystemLocale() // 跟随系统：读取真正的系统 locale
        }
        val config = Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        val updatedContext = newBase.createConfigurationContext(config)
        super.attachBaseContext(updatedContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 手机端：隐藏系统栏（状态栏 + 导航栏）实现真正全屏——主题 windowFullscreen 只隐藏状态栏，
        // Android 12+ 强制 edge-to-edge 后下方会露出白色导航栏。TV 无系统栏，无需处理。
        // 与下方 setContent 内判断一致：leanback 或 television 特性任一即视为 TV
        // （很多非认证 TV 盒子只上报 android.hardware.type.television）
        val isTVDevice = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                packageManager.hasSystemFeature("android.hardware.type.television")
        if (!isTVDevice) {
            try {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } catch (e: Exception) {
                AppLog.e("MainActivity", "system bars hide failed", e)
            }
        }

        // 手机端：检查电池优化白名单，确保后台播放稳定
        com.nasmusic.tv.player.BatteryOptimizationHelper.checkAndRequest(this)

        // 修复（M-5）：Android 13+ 通知运行时权限——媒体通知/下载通知依赖 POST_NOTIFICATIONS
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            try {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2001
                )
            } catch (e: Exception) {
                AppLog.w("MainActivity", "request POST_NOTIFICATIONS failed", e)
            }
        }

        // 修复：频谱可视化依赖 RECORD_AUDIO——Android 10+ 构建 Visualizer 需要该权限，
        // 缺失时 attach() 抛 SecurityException，此前只记日志、不降级，导致所有效果静止。
        // 这里主动请求（拒绝也不阻塞：SpectrumAnalyzer 会自动降级到 PCM 通道）。
        if (android.os.Build.VERSION.SDK_INT >= 23 &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.RECORD_AUDIO
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            try {
                androidx.core.app.ActivityCompat.requestPermissions(
                    this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 2002
                )
            } catch (e: Exception) {
                AppLog.w("MainActivity", "request RECORD_AUDIO failed", e)
            }
        }

        // SAF 树选择器（§8.8.4）：注入到 ExportCoordinator，导出时启动系统文件夹选择器
        (application as NasMusicApp).exportCoordinator.treePickLauncher = {
            exportTreeLauncher.launch(null)
        }

        setContent {
            val settings by viewModel.appSettings.collectAsState(initial = com.nasmusic.tv.data.model.AppSettings())
            // 手机端：默认横屏使用（TV 不干预）
            val isTVDevice = remember {
                packageManager.hasSystemFeature("android.software.leanback") ||
                packageManager.hasSystemFeature("android.hardware.type.television")
            }
            LaunchedEffect(isTVDevice) {
                if (!isTVDevice) {
                    requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }
            // 密度缩放：手机端紧凑 UI 缩小（density * 0.82），TV 端字号由 FontSize.xx() 函数返回 +6sp 的 TV 值
            val baseDensity = androidx.compose.ui.platform.LocalDensity.current
            val uiDensity = if (isTVDevice) androidx.compose.ui.unit.Density(
                                density = baseDensity.density,
                                fontScale = baseDensity.fontScale
                            )
                            else androidx.compose.ui.unit.Density(
                                density = baseDensity.density * com.nasmusic.tv.ui.theme.CompactSizes.PHONE_UI_SCALE,
                                fontScale = baseDensity.fontScale
                            )
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides uiDensity,
                com.nasmusic.tv.ui.theme.LocalPhoneCompact provides !isTVDevice,
                com.nasmusic.tv.ui.theme.LocalFontAdjustment provides settings.fontAdjustment
            ) {
            NASMusicTVTheme(darkTheme = settings.darkTheme) {
                // 暴露当前 Activity 给子组件，用于注册对话框的 BACK 键处理
                CompositionLocalProvider(
                    LocalDialogBackHandler provides dialogBackHandler,
                    LocalListBackHandler provides listBackHandler,
                    LocalNavigateBackHandler provides navigateBackHandler,
                    LocalShowExitConfirm provides showExitConfirm
                ) {
                    val showConnectPrompt by viewModel.serverVM.showConnectPrompt.collectAsState(initial = false)
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
                                    // 注销 Jellyfin session，确保 HTTP 请求完成后再杀进程。
                                    // 修复（M-1）：logout 网络请求限时 1.5s——OkHttp 超时最长 15s，
                                    // 无限等待会在网络异常时造成主线程 ANR；超时仍照常退出。
                                    // P3：整段移出主线程（原 runBlocking 会让 UI 冻结至多 1.5s），
                                    // 改为 IO 协程等待完成，再回主线程做收尾。
                                    lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        try {
                                            kotlinx.coroutines.withTimeout(1500) {
                                                app.backendRegistry.disconnect()
                                            }
                                            AppLog.d("MainActivity", "exit: backend disconnected")
                                        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                                            AppLog.w("MainActivity", "exit: disconnect timeout (1.5s), exiting anyway")
                                        } catch (e: Exception) {
                                            AppLog.w("MainActivity", "exit: disconnect failed", e)
                                        } finally {
                                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                finishAffinity()
                                                android.os.Process.killProcess(android.os.Process.myPid())
                                            }
                                        }
                                    }
                                },
                                onDismiss = { showExitConfirm.value = false }
                            )
                        }

                        // 启动连接提示对话框
                        if (showConnectPrompt) {
                            ConnectPromptDialog(
                                serverDisplayName = viewModel.serverVM.serverDisplayName.value,
                                onConfirm = { viewModel.connectToSavedServer() },
                                onDismiss = { viewModel.serverVM.dismissConnectPrompt() }
                            )
                        }

                        // D-3: 错误提示消息（数据加载/操作失败时显示，5秒后自动清除）
                        errorMessage?.let { msg ->
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
                                    text = msg,
                                    color = NasMusicColors.TextPrimary,
                                    fontSize = FontSize.button(),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        // 连接结果提示消息（短时悬浮显示）
                        connectMessage?.let { msg ->
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
                                    text = msg,
                                    color = NasMusicColors.TextPrimary,
                                    fontSize = FontSize.button(),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
}
            }
        }
    }

    // 分层 BACK 键处理：Level 0 → Level 1 → Level 1.5 → Level 2 → Level 3
        // Level 0: 沉浸模式 → 退出全屏
        // Level 1: 关闭对话框（由 dialogBackHandler 控制）
        // Level 1.5: 列表回到顶部（由 listBackHandler 控制，返回 true 表示已消费）
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

                // Level 1.5: 列表已滚动 → 先滚动到顶部
                val listHandler = listBackHandler.value
                if (listHandler != null && listHandler()) {
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

        // F2-2 / L3：通知栏"播放模式"按钮接线——service 发出的事件转交 PlayerViewModel。
        // 切换动作必须由 UI 侧执行：playMode 的真相在 PlayerViewModel（B-13：不归
        // PlayerManager），服务侧独立完成会与界面显示脱节。
        // 用 lifecycleScope 而**不是** repeatOnLifecycle(STARTED)：本事件在 Activity 退到
        // 后台（按 Home）后仍需可用——那种场景下用户正是通过通知栏控制播放，若按 STARTED
        // 退订会让按钮静默失效（相比旧实现反而是退化）。lifecycleScope 随 Activity 销毁
        // 自动取消 → 自动退订，无需在 onDestroy 手动解绑，也不会像旧闭包那样被 Application
        // 长期持有上一个 Activity 的 ViewModel。
        lifecycleScope.launch {
            (application as NasMusicApp).playModeToggleEvents.collect {
                viewModel.playerVM.togglePlayMode()
            }
        }

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
        // 修复（M-2）：仅在本页真正结束（isFinishing，如退出确认/finishAffinity）时才清理；
        // 配置重建（旋转/主题切换/分屏）isFinishing=false，原无条件 release+stopService 会误杀后台播放；
        // 从最近任务划掉应用同样保留播放（通知栏可控），符合媒体类应用预期
        if (!isFinishing) return
        val app = (application as NasMusicApp)
        // L3：此处**不再**需要手动解绑播放模式回调——订阅跑在 lifecycleScope 中，
        // onDestroy 时作用域自动取消、SharedFlow 自动退订，时序窗口与残留引用一并消失
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
                AppLog.w("MainActivity", "onDestroy: disconnect failed", e)
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
            currentScreen = viewModel.navVM.currentScreen.value
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
