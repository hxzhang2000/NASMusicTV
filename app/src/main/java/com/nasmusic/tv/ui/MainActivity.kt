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

    // ── 照片墙（阶段 9，§9）─────────────────────────────────────────────────
    // ⛔ 权限对话框**不在这里触发** —— 它由图库开关驱动（§6.2 / §9.4 官方要求：
    //    "Request these permissions when the app needs storage access, instead of at startup."）。
    //    本字段只负责「注册 + 把结果转交」，绝不放进 onCreate 里无条件 launch。

    /** 照片权限对话框结果 */
    private val photoPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _: Map<String, Boolean> ->
        // ⛔ 刻意**忽略**回调里的 Map：Android 14+「仅选择照片」下 `READ_MEDIA_IMAGES`
        // 可能是 granted 却只是**会话级**授权（§9.6）⇒ 一律以重新读取的三态为准。
        viewModel.visualizerVM.onPhotoPermissionResult()
    }

    /** 照片目录选择器（§6.3 路线 B）：与导出同款契约，结果走照片墙自己的通道 */
    private val photoDirectoryLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        viewModel.visualizerVM.onPhotoDirectoryPicked(uri)
    }

    /**
     * 在每次 Activity 创建（含 recreate）时应用存储的语言设置。
     * resources.updateConfiguration() 仅影响 Application 级别资源，
     * 通过 attachBaseContext 创建带正确 locale 的 Context，确保 Compose 使用正确的资源配置。
     *
     * ⛔ **覆盖配置里只能放 locale —— 这是「手机横屏仍渲染竖屏 UI」的根因（v2.36.0 回归）**：
     * `createConfigurationContext(x)` 的入参 x 会成为该 Context 的 Resources **override 配置**，而
     * `ResourcesManager.applyConfigurationToResourcesLocked()` 在**每一次**全局配置变更时都会执行
     * `tmpConfig.setTo(全局配置); tmpConfig.updateFrom(override 配置)` ——
     * 而 `Configuration.updateFrom()` 是**逐字段**判定「非 undefined 才写入」，
     * 于是 override 里凡是非 undefined 的字段都会被**重新写回旧值**。
     *
     * 早期实现传的是整份 `Configuration(newBase.resources.configuration)` 拷贝 →
     * `orientation` / `screenWidthDp` / `screenHeightDp` / `densityDpi` / `screenLayout` / `uiMode` /
     * `windowConfiguration` 全部被**钉死在 Activity 启动那一刻**。
     * 又因为 Manifest 声明了 `configChanges="orientation|screenSize|…"`（旋转**不重建** Activity），
     * 这份覆盖配置**永不刷新** → `LocalConfiguration.current.orientation` 永远是启动值 →
     * `deriveUiMode()` 恒返回 `UiMode.PhonePortrait` → 手机横屏下依旧渲染竖屏 UI
     * （底部 `MiniPlayer` + `PhoneNavBar` 都还在）＝ 真机反馈的「横屏下还有下方的 mini 播放条」。
     *
     * 因此这里**只构造「仅含 locale」的覆盖配置**：其余字段一律保持 undefined
     * （`Configuration()` 的默认值已是 `ORIENTATION_UNDEFINED` / `SCREEN_WIDTH_DP_UNDEFINED` /
     * `DENSITY_DPI_UNDEFINED` / `SCREENLAYOUT_UNDEFINED`，且其 `WindowConfiguration` 的
     * 空边界在 `updateFrom()` 中是 no-op），方向与屏幕尺寸一律跟随系统全局配置。
     * ⚠️ `fontScale` 是唯一的例外：`Configuration()` 的默认值是 **1**（不是 undefined），
     * 必须显式置 **0**（0 即 `Configuration.unset()` 采用的「未设置」语义），
     * 否则会把系统字体缩放钉死在启动值。
     */
    override fun attachBaseContext(newBase: Context) {
        val lang = com.nasmusic.tv.data.prefs.AppPreferences.getInstance(newBase).getLanguageSync()
        val locale = when (lang) {
            "zh" -> java.util.Locale.SIMPLIFIED_CHINESE
            "en" -> java.util.Locale.US
            else -> com.nasmusic.tv.NasMusicApp.getSystemLocale() // 跟随系统：读取真正的系统 locale
        }
        // ⛔ 只下发 locale：其余字段必须保持 undefined，否则会钉死方向/屏幕尺寸/密度（见上方 KDoc）
        val localeOverride = Configuration().apply {
            setLocale(locale)
            fontScale = 0f
        }
        super.attachBaseContext(newBase.createConfigurationContext(localeOverride))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 手机端系统栏策略（v2.36.0 D9）：
        // - 竖屏（PhonePortrait）：**显示**状态栏/导航栏 —— 否则 statusBarsPadding()/navigationBarsPadding()
        //   返回 0，刘海会压住顶部栏、上滑唤出系统栏时内容跳动（方案 §5.5(9)）
        // - 横屏（PhoneLandscape）：维持现状 hide()，保持改前行为（§3.1 B1 硬规则）
        // TV 无系统栏，无需处理。
        // 与下方 setContent 内判断一致：leanback 或 television 特性任一即视为 TV
        // （很多非认证 TV 盒子只上报 android.hardware.type.television）
        val isTVDevice = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
                packageManager.hasSystemFeature("android.hardware.type.television")
        if (!isTVDevice) {
            try {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    if (portrait) show(WindowInsetsCompat.Type.systemBars())
                    else hide(WindowInsetsCompat.Type.systemBars())
                }
            } catch (e: Exception) {
                AppLog.e("MainActivity", "system bars setup failed", e)
            }
        }

        // v2.36.0（方案 §5.5(1)(8) / D5）：首帧方向初值 —— 必须在 setContent 之前同步设置，
        // 否则组合前会有一帧处于 manifest 声明值（`unspecified`）→ 视觉方向闪动。
        // 同步读走 @Volatile 镜像（零 IO、零 runBlocking，见 DisplayPrefs.getScreenOrientationSync）。
        // 首帧 Screen 固定按 Home 计算（冷启动默认路径）。
        if (!isTVDevice) {
            try {
                requestedOrientation = com.nasmusic.tv.ui.theme.resolveOrientation(
                    pref = (application as NasMusicApp).appPreferences.display.getScreenOrientationSync(),
                    isFullScreenPage = false
                )
            } catch (e: Exception) {
                AppLog.w("MainActivity", "init requestedOrientation failed", e)
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

        // 照片墙权限链路（阶段 9）：ViewModel 不能自己 registerForActivityResult，
        // 只能由 Activity 注入（与上一行的导出做法完全一致）。
        // ⚠️ 这里只是**接线**，不发起任何请求 —— 请求由「图库」开关触发（§6.2）。
        viewModel.visualizerVM.photoPermissionLauncher = {
            photoPermissionLauncher.launch(
                com.nasmusic.tv.util.PermissionHelper.getPhotoPermissions()
            )
        }
        viewModel.visualizerVM.photoDirectoryLauncher = {
            photoDirectoryLauncher.launch(null)
        }

        setContent {
            val settings by viewModel.appSettings.collectAsState(initial = com.nasmusic.tv.data.model.AppSettings())
            val isTVDevice = remember {
                packageManager.hasSystemFeature("android.software.leanback") ||
                packageManager.hasSystemFeature("android.hardware.type.television")
            }
            // v2.36.0（方案 §3.2 / C4）：形态因子 —— 直接读 LocalConfiguration.current。
            // ⚠️ 不能用 `remember { derivedStateOf { configuration.orientation } }`：
            // `configuration` 不是 State，remember 会永久读到初值，方向变化永不生效。
            val configuration = androidx.compose.ui.platform.LocalConfiguration.current
            val uiMode = com.nasmusic.tv.ui.theme.deriveUiMode(isTVDevice, configuration.orientation)

            // 屏幕方向（L1 全局策略）：设置项是唯一真相之源；L2 顶部栏按钮只是快捷改它（D1）
            val orientationPref by (application as NasMusicApp).appPreferences.display.screenOrientation
                .collectAsState(initial = (application as NasMusicApp).appPreferences.display.getScreenOrientationSync())

            // 全屏页状态：方向是**窗口级**属性，归 Activity 管（StateFlow 支持多订阅者，无副作用）
            val showMv by viewModel.mvVM.showMv.collectAsState(initial = false)
            val showKaraoke by viewModel.vocalVM.showKaraoke.collectAsState(initial = false)
            val showVisualizer by viewModel.visualizerVM.showVisualizer.collectAsState(initial = false)

            // 方案 §5.4：全局策略 + 全屏页覆盖 → requestedOrientation。
            // 全屏页退出后自动恢复（key 含 showMv/showKaraoke/showVisualizer），无需手动记状态。
            LaunchedEffect(orientationPref, showMv, showKaraoke, showVisualizer, isTVDevice) {
                if (isTVDevice) return@LaunchedEffect
                requestedOrientation = com.nasmusic.tv.ui.theme.resolveOrientation(
                    pref = orientationPref,
                    isFullScreenPage = showMv || showKaraoke || showVisualizer
                )
            }

            // v2.36.0 D9：竖屏显示系统栏（沉浸模式 / 全屏页仍隐藏），横屏手机保持现状
            LaunchedEffect(uiMode, isImmersiveMode.value, showMv, showKaraoke, showVisualizer, isTVDevice) {
                if (isTVDevice) return@LaunchedEffect
                try {
                    val controller = WindowInsetsControllerCompat(window, window.decorView)
                    val showBars = uiMode == com.nasmusic.tv.ui.theme.UiMode.PhonePortrait &&
                        !isImmersiveMode.value && !showMv && !showKaraoke && !showVisualizer
                    if (showBars) controller.show(WindowInsetsCompat.Type.systemBars())
                    else controller.hide(WindowInsetsCompat.Type.systemBars())
                } catch (e: Exception) {
                    AppLog.w("MainActivity", "system bars toggle failed", e)
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
                com.nasmusic.tv.ui.theme.LocalUiMode provides uiMode,
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
        // 2026-09-25 审查修复（#10）：launcher 闭包捕获 Activity，真退出时置空断开引用
        //（配置重建 isFinishing=false 走不到这里，onCreate 会重新注入，不影响功能）
        runCatching {
            viewModel.visualizerVM.photoPermissionLauncher = null
            viewModel.visualizerVM.photoDirectoryLauncher = null
            // 2026-09-26 审查补修（#5 遗留）：ExportCoordinator 由 Application 持有、跨 Activity
            // 存活，其 treePickLauncher 闭包同样捕获本 Activity ⇒ 真退出时对称置空断开引用
            //（配置重建 isFinishing=false 走不到这里，onCreate 会重新注入）
            app.exportCoordinator.treePickLauncher = null
        }
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
        // 阶段 9（§6.2 / §9.6）：权限可能在 onStart / onResume 之间被用户改掉（App 不重启）
        // ⇒ 每次回到前台都重判三态：撤销了就把「图库」开关回弹并提示，
        //   SAF 目录授权失效就清掉目录设置（否则设置页显示「已选目录」却读不到）。
        // ⚠️ 不能只在启动判一次 —— 那正是官方点名要避免的写法。
        try {
            viewModel.visualizerVM.refreshPhotoAccess()
        } catch (e: Exception) {
            AppLog.w("MainActivity", "refreshPhotoAccess failed", e)
        }
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
