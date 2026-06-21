package com.nasmusic.tv.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.nasmusic.tv.ui.theme.NASMusicTVTheme
import com.nasmusic.tv.ui.theme.NasMusicColors
import com.nasmusic.tv.ui.screens.NowPlayingScreen
import com.nasmusic.tv.ui.screens.LibraryScreen
import com.nasmusic.tv.ui.screens.QueueScreen
import com.nasmusic.tv.ui.screens.SettingsScreen
import com.nasmusic.tv.ui.screens.ServerConnectScreen
import com.nasmusic.tv.ui.screens.AlbumDetailScreen
import com.nasmusic.tv.ui.screens.ArtistDetailScreen
import com.nasmusic.tv.ui.screens.EqualizerScreen
import com.nasmusic.tv.ui.screens.PlaylistManagementScreen
import com.nasmusic.tv.ui.screens.ExitConfirmDialog
import com.nasmusic.tv.ui.components.ConnectPromptDialog
import com.nasmusic.tv.ui.viewmodel.MainViewModel
import com.nasmusic.tv.ui.viewmodel.Screen
import com.nasmusic.tv.data.model.ServerConfig
import com.nasmusic.tv.data.model.EqualizerPreset
import com.nasmusic.tv.player.PlaybackService
import com.nasmusic.tv.player.CoverArtManager
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            NASMusicTVTheme {
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
                                    // 退出前断开后端连接，释放资源（同步执行确保完成）
                                    kotlinx.coroutines.runBlocking {
                                        try {
                                            com.nasmusic.tv.backend.BackendRegistry.disconnect()
                                            android.util.Log.d("MainActivity", "exit: backend disconnected")
                                        } catch (_: Exception) {}
                                    }
                                    finish()
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
        registerNetworkCallback()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 应用退出时断开后端连接，释放 OkHttp 连接池，防止连接泄漏
        lifecycleScope.launch {
            try {
                com.nasmusic.tv.backend.BackendRegistry.disconnect()
                android.util.Log.d("MainActivity", "onDestroy: backend disconnected")
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
        return when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                viewModel.playPause()
                true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                viewModel.next()
                true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                viewModel.previous()
                true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                viewModel.playPause()
                true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (isImmersiveMode.value) {
                    // 沉浸模式下 OK → 退出全屏，不触发播放暂停
                    isImmersiveMode.value = false
                    true
                } else if (viewModel.currentScreen.value == Screen.NowPlaying) {
                    // 仅在播放页面时处理 — 否则交给默认焦点系统
                    viewModel.playPause()
                    true
                } else {
                    super.onKeyDown(keyCode, event)
                }
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // D-2: 注册 ConnectivityManager 网络状态回调
    private fun registerNetworkCallback() {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                viewModel.onNetworkAvailable()
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                viewModel.onNetworkLost()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                super.onCapabilitiesChanged(network, capabilities)
                val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                if (hasInternet) {
                    viewModel.onNetworkAvailable()
                } else {
                    viewModel.onNetworkLost()
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        android.util.Log.d("MainActivity", "registerNetworkCallback: registered")
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AppRoot(
    viewModel: MainViewModel,
    isImmersiveMode: MutableState<Boolean>,
    onConnect: (ServerConfig) -> Unit
) {
    val currentScreen by viewModel.currentScreen.collectAsState(initial = Screen.Library)
    val currentSong by viewModel.currentSong.collectAsState(initial = null)
    val isPlaying by viewModel.isPlaying.collectAsState(initial = false)
    val playMode by viewModel.playMode.collectAsState(initial = com.nasmusic.tv.data.model.PlayMode.SEQUENTIAL)
    val progress by viewModel.progress.collectAsState(initial = 0L)
    val duration by viewModel.duration.collectAsState(initial = 0L)
    val lyrics by viewModel.currentLyrics.collectAsState(initial = null)
    val lyricsAvailability by viewModel.lyricsAvailability.collectAsState(initial = com.nasmusic.tv.data.model.LyricsAvailability())
    val albums by viewModel.albums.collectAsState(initial = emptyList())
    val songs by viewModel.songs.collectAsState(initial = emptyList())
    val queue by viewModel.queue.collectAsState(initial = emptyList())
    val currentIndex by viewModel.currentIndex.collectAsState(initial = 0)
    val isLoading by viewModel.isLoading.collectAsState(initial = false)
    val isLibraryLoading by viewModel.isLibraryLoading.collectAsState(initial = false)
    val isConnected by viewModel.isConnected.collectAsState(initial = false)
    val serverDisplayName by viewModel.serverDisplayName.collectAsState(initial = "")
    val serverConfig by viewModel.serverConfig.collectAsState(initial = ServerConfig.Empty)
    val settings by viewModel.appSettings.collectAsState(initial = com.nasmusic.tv.data.model.AppSettings())
    // Level 2: 根据当前屏幕和沉浸模式动态设置导航 BACK 键处理函数
    val navBackHandler = LocalNavigateBackHandler.current
    LaunchedEffect(currentScreen, isImmersiveMode.value) {
        val handler: (() -> Unit)? = when {
            isImmersiveMode.value -> {{ isImmersiveMode.value = false }}
            currentScreen != Screen.NowPlaying -> {{ viewModel.navigateTo(Screen.NowPlaying) }}
            else -> null
        }
        navBackHandler.value = handler
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部导航栏（沉浸模式下隐藏）
        if (!isImmersiveMode.value) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.padding(end = 32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(36.dp)
                            .height(36.dp)
                            .background(
                                NasMusicColors.Primary,
                                RoundedCornerShape(8.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "♪", color = Color.Black, fontSize = 18.sp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(text = "NAS Music", color = NasMusicColors.TextPrimary, fontSize = 18.sp)
                }

                Spacer(modifier = Modifier.weight(1f))

                NavItem(
                    label = "正在播放",
                    selected = currentScreen == Screen.NowPlaying,
                    onClick = { viewModel.navigateTo(Screen.NowPlaying) }
                )
                NavItem(
                    label = "曲库",
                    selected = currentScreen == Screen.Library,
                    onClick = { viewModel.navigateTo(Screen.Library) }
                )
                NavItem(
                    label = "队列",
                    selected = currentScreen == Screen.Queue,
                    onClick = { viewModel.navigateTo(Screen.Queue) }
                )
                NavItem(
                    label = "服务器",
                    selected = currentScreen == Screen.ServerConnect,
                    onClick = { viewModel.navigateTo(Screen.ServerConnect) }
                )
                NavItem(
                    label = "设置",
                    selected = currentScreen == Screen.Settings,
                    onClick = { viewModel.navigateTo(Screen.Settings) }
                )
            }
        }

        // 内容区域
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (currentScreen) {
                Screen.NowPlaying -> {
                    NowPlayingScreen(
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                        playMode = playMode,
                        progressMs = progress,
                        durationMs = duration,
                        lyrics = lyrics,
                        lyricsAvailability = lyricsAvailability,
                        isFavorite = currentSong?.let { viewModel.isFavorite(it.id) } ?: false,
                        isImmersiveMode = isImmersiveMode.value,
                        onToggleImmersive = { isImmersiveMode.value = !isImmersiveMode.value },
                        onPlayPause = { viewModel.playPause() },
                        onNext = { viewModel.next() },
                        onPrevious = { viewModel.previous() },
                        onTogglePlayMode = { viewModel.togglePlayMode() },
                        onSeek = { viewModel.seekTo(it) },
                        onSwitchLyricsSource = { viewModel.switchLyricsSource(it) },
                        onToggleFavorite = if (currentSong != null) {{ viewModel.toggleFavorite(currentSong!!) }} else null
                    )
                }
                Screen.Library -> {
                    val genres by viewModel.genres.collectAsState(initial = emptyList())
                    val favoriteIds by viewModel.favoriteIds.collectAsState(initial = emptySet())
                    val recentSongs = viewModel.getRecentSongs()
                    val recentSongIds = viewModel.recentSongIds.collectAsState(initial = emptyList())
                    val playCounts by viewModel.playCounts.collectAsState(initial = emptyMap())
                    LibraryScreen(
                        albums = albums,
                        songs = songs,
                        isLoading = isLoading || isLibraryLoading,
                        genres = genres,
                        favoriteIds = favoriteIds,
                        recentSongIds = recentSongIds.value,
                        playCounts = playCounts,
                        artistSongsMap = viewModel.artistSongsMap.value,
                        onPlayAlbum = { album ->
                            val albumSongs = songs.filter { it.albumId == album.id }
                            if (albumSongs.isNotEmpty()) {
                                viewModel.playQueue(albumSongs)
                                viewModel.navigateTo(Screen.NowPlaying)
                            }
                        },
                        onPlaySong = { song ->
                            viewModel.playQueue(listOf(song))
                            viewModel.navigateTo(Screen.NowPlaying)
                        },
                        onPlaySongs = { songList ->
                            viewModel.playQueue(songList)
                            viewModel.navigateTo(Screen.NowPlaying)
                        },
                        onPlayAllAlbums = {
                            if (songs.isNotEmpty()) {
                                viewModel.playQueue(songs)
                                viewModel.navigateTo(Screen.NowPlaying)
                            }
                        },
                        onOpenAlbumDetail = { album -> viewModel.openAlbumDetail(album) },
                        onOpenArtistDetail = { artist -> viewModel.openArtistDetail(artist) },
                        onSongsByGenre = { genre, callback -> viewModel.getSongsByGenre(genre, callback) },
                        onSongsByYear = { from, to, callback -> viewModel.getSongsByYearRange(from, to, callback) }
                    )
                }
                Screen.Queue -> {
                    QueueScreen(
                        queue = queue,
                        currentIndex = currentIndex,
                        currentSong = currentSong,
                        isPlaying = isPlaying,
                        playMode = playMode,
                        onPlaySong = { index ->
                            if (index in queue.indices) {
                                viewModel.playQueue(queue, index)
                                viewModel.navigateTo(Screen.NowPlaying)
                            }
                        },
                        onRemoveSong = { index -> viewModel.removeFromQueue(index) },
                        onClearQueue = { viewModel.clearQueue() },
                        onPlayPause = { viewModel.playPause() },
                        onNext = { viewModel.next() },
                        onPrevious = { viewModel.previous() },
                        onMoveItem = { from, to -> viewModel.moveQueueItem(from, to) }
                    )
                }
                Screen.ServerConnect -> {
                    ServerConnectScreen(
                        initialConfig = serverConfig,
                        isConnected = isConnected,
                        serverDisplayName = serverDisplayName,
                        isConnecting = isLoading,
                        onConnect = onConnect,
                        onDisconnect = { viewModel.disconnect() }
                    )
                }
                Screen.Settings -> {
                    SettingsScreen(
                        settings = settings,
                        onToggleDarkTheme = { viewModel.updateDarkTheme(it) },
                        onToggleAnimations = { viewModel.updateAnimationsEnabled(it) },
                        onToggleAutoPlayNext = { viewModel.updateAutoPlayNext(it) },
                        onChangePlayMode = { viewModel.updateDefaultPlayMode(it) },
                        onToggleCacheLyrics = { viewModel.updateCacheLyrics(it) },
                        onToggleCacheCover = { viewModel.updateCacheCover(it) },
                        onChangeLyricsOffset = { viewModel.updateLyricsOffset(it) },
                        onClearLyricsCache = { viewModel.clearLyricsCache() },
                        onClearCoverCache = { viewModel.clearCoverCache() },
                        onOpenEqualizer = { viewModel.navigateTo(Screen.Equalizer) }
                    )
                }
                Screen.AlbumDetail -> {
                    val selectedAlbum by viewModel.selectedAlbum.collectAsState(initial = null)
                    val albumSongs = selectedAlbum?.let { viewModel.getAlbumSongsCache(it.id) } ?: emptyList()
                    if (selectedAlbum != null) {
                        AlbumDetailScreen(
                            album = selectedAlbum!!,
                            songs = albumSongs,
                            onPlaySong = { song ->
                                val albumSongs = selectedAlbum?.let { viewModel.getAlbumSongsCache(it.id) } ?: listOf(song)
                                viewModel.playQueue(albumSongs, albumSongs.indexOf(song).coerceAtLeast(0))
                                viewModel.navigateTo(Screen.NowPlaying)
                            },
                            onPlayAll = { songList ->
                                viewModel.playQueue(songList)
                                viewModel.navigateTo(Screen.NowPlaying)
                            },
                            onBack = { viewModel.navigateTo(Screen.Library) }
                        )
                    }
                }
                Screen.ArtistDetail -> {
                    val selectedArtistName by viewModel.selectedArtistName.collectAsState(initial = null)
                    val artistSongs = selectedArtistName?.let { viewModel.artistSongsMap.value[it] } ?: emptyList()
                    if (selectedArtistName != null) {
                        ArtistDetailScreen(
                            artistName = selectedArtistName!!,
                            songs = artistSongs,
                            onPlaySong = { song ->
                                viewModel.playQueue(artistSongs, artistSongs.indexOf(song).coerceAtLeast(0))
                                viewModel.navigateTo(Screen.NowPlaying)
                            },
                            onPlayAll = { songList ->
                                viewModel.playQueue(songList)
                                viewModel.navigateTo(Screen.NowPlaying)
                            },
                            onBack = { viewModel.navigateTo(Screen.Library) }
                        )
                    }
                }
                Screen.Equalizer -> {
                    val equalizerPreset by viewModel.equalizerPreset.collectAsState(initial = EqualizerPreset.NORMAL)
                    val equalizerBands by viewModel.equalizerBands.collectAsState(initial = emptyList())
                    EqualizerScreen(
                        presets = EqualizerPreset.values().toList(),
                        currentPreset = equalizerPreset,
                        currentBands = equalizerBands,
                        onSelectPreset = { viewModel.setEqualizerPreset(it) },
                        onAdjustBand = { index, value -> viewModel.setEqualizerBand(index, value) },
                        onBack = { viewModel.navigateTo(Screen.Settings) }
                    )
                }
                Screen.PlaylistManagement -> {
                    val playlists by viewModel.playlists.collectAsState(initial = emptyList())
                    val selectedPlaylistSongs by viewModel.selectedPlaylistSongs.collectAsState(initial = emptyList())
                    PlaylistManagementScreen(
                        playlists = playlists,
                        selectedPlaylistSongs = selectedPlaylistSongs,
                        isLoading = false,
                        onSelectPlaylist = { viewModel.selectPlaylist(it) },
                        onCreatePlaylist = { viewModel.showCreatePlaylistDialog() },
                        onDeletePlaylist = { viewModel.deletePlaylist(it) },
                        onPlayPlaylist = { playlist ->
                            viewModel.playPlaylist(playlist)
                            viewModel.navigateTo(Screen.NowPlaying)
                        },
                        onRemoveSong = { songId -> viewModel.removeFromPlaylist(songId) },
                        onBack = { viewModel.navigateTo(Screen.Library) }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NavItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val animScale = remember { Animatable(1f) }

    Surface(
        onClick = onClick,
        modifier = modifier
            .padding(horizontal = 4.dp)
            .defaultMinSize(minHeight = 48.dp)
            .scale(animScale.value)
            .border(
                width = if (isFocused) 2.dp else 0.dp,
                color = if (isFocused) NasMusicColors.FocusRing else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .onFocusChanged { focusState ->
                isFocused = focusState.isFocused
                scope.launch {
                    animScale.animateTo(
                        if (isFocused) 1.12f else 1f,
                        tween(250)
                    )
                }
            },
        shape = ClickableSurfaceDefaults.shape(
            shape = RoundedCornerShape(10.dp),
            focusedShape = RoundedCornerShape(10.dp)
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (selected) NasMusicColors.Primary else NasMusicColors.TextSecondary,
            focusedContainerColor = if (selected) NasMusicColors.Primary.copy(alpha = 0.3f)
                                    else NasMusicColors.Primary.copy(alpha = 0.2f),
            focusedContentColor = NasMusicColors.Primary
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = 1f,
            pressedScale = 0.96f
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = if (selected) 16.sp else 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
    }
}
