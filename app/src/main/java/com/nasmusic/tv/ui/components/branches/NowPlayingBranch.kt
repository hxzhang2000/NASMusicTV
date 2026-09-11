package com.nasmusic.tv.ui.components.branches

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.ui.viewmodel.MainViewModel
import com.nasmusic.tv.data.model.*
import com.nasmusic.tv.ui.screens.*
import com.nasmusic.tv.ui.components.MvPlaybackScreen
import com.nasmusic.tv.ui.screens.library.*
import com.nasmusic.tv.ui.screens.settings.*
import com.nasmusic.tv.ui.screens.netdisk.*
import com.nasmusic.tv.ui.screens.stats.*
import com.nasmusic.tv.ui.viewmodel.*
/**
 * F2-2b：NowPlaying 分支提取（AppRoot 方法过大 MethodTooLargeException 修复）。
 * 原分支体逐行搬迁，仅把外层共享状态改为参数注入。
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun NowPlayingBranch(
    viewModel: MainViewModel,
    isTV: Boolean,
    isImmersiveMode: androidx.compose.runtime.MutableState<Boolean>,
    currentSong: Song?,
    isPlaying: Boolean,
    playMode: com.nasmusic.tv.data.model.PlayMode,
    coverCandidates: List<String>,
    coverFilterEnabled: Boolean,
    coverFilterBlurRadius: Float,
    coverFilterDarkOverlay: Float,
    settings: com.nasmusic.tv.data.model.AppSettings,
    showMv: Boolean,
    mvState: com.nasmusic.tv.ui.viewmodel.MvAvailability?,
    isConnected: Boolean
) {
                    val lyrics by viewModel.currentLyrics.collectAsState(initial = null)
                    val lyricsAvailability by viewModel.lyricsAvailability.collectAsState(initial = com.nasmusic.tv.data.model.LyricsAvailability())
                    val lyricsHighlightMode by viewModel.lyricsHighlightMode.collectAsState(initial = com.nasmusic.tv.data.model.LyricsHighlightMode.LINE_BY_LINE)
                    val showKaraoke by viewModel.vocalVM.showKaraoke.collectAsState(initial = false)
                    // 修复（H-3）：20fps 频谱流只在本页收集，不再驱动 AppRoot 全树重组
                    val spectrumData by viewModel.playerVM.spectrumData.collectAsState(initial = FloatArray(0))
                    val lyricsFontScale by viewModel.prefs.lyrics.lyricsFontScale.collectAsState(initial = 1.0f)
                    val vocalRemovalEnabled by viewModel.vocalVM.vocalRemovalEnabled.collectAsState()
                    val pitchSemitones by viewModel.vocalVM.pitchSemitones.collectAsState()
                    val playbackSpeed by viewModel.vocalVM.playbackSpeed.collectAsState()
                    val separationMode by viewModel.vocalVM.separationMode.collectAsState()
                    val separating by viewModel.vocalVM.separating.collectAsState()
                    val separationProgress by viewModel.vocalVM.separationProgress.collectAsState()
                    val hqError by viewModel.vocalVM.hqError.collectAsState()
                    val hqSuccess by viewModel.vocalVM.hqSuccess.collectAsState()
                    val modelDownloaded by viewModel.downloadVM.modelDownloaded.collectAsState()
                    // === F2-2b 睡眠定时器（常驻按钮状态，本页内收集） ===
                    val sleepTimerSt by viewModel.playerVM.sleepTimerState.collectAsState()
                    // C7 修复：收藏状态建立订阅，点收藏后星标即时刷新。
                    // 原实现直读 isFavorite(song.id) 不订阅，点收藏后需切歌才刷新。
                    val favoriteIds by viewModel.favoriteIds.collectAsState(initial = emptySet())
                    val mvReady = mvState as? com.nasmusic.tv.ui.viewmodel.MvAvailability.Ready
                    if (showMv && mvReady != null) {
                        // MTV 音乐视频全屏页（独立播放器，退出时 MainViewModel 恢复主播放器）
                        MvPlaybackScreen(
                            mv = mvReady.mv,
                            lyrics = lyrics,
                            alternatives = mvReady.alternatives,
                            onExit = { viewModel.mvVM.exitMvMode() },
                            onPlaybackError = { viewModel.onMvPlaybackError() },
                            onPlaybackEnded = { viewModel.onMvPlaybackEnded() },
                            onSwitchOrResearch = { viewModel.onSwitchOrResearch() },
                            onSearchBilibili = { viewModel.onSearchBilibili() },
                            onPreviousMv = { viewModel.onMvPrevious() },
                            onNextMv = { viewModel.onMvNext() },
                            mvMessage = viewModel.mvVM.mvMessage.collectAsState().value,
                            // 手机端无需"手机遥控"二维码（自身即控制端）
                            remoteControlUrl = if (isTV) viewModel.remoteControlUrl.collectAsState().value else null
                        )
                    } else {
                        // F-2：progress/duration 在本分支内收集，播放期间的每秒重组
                        // 只影响 NowPlayingScreen，不再驱动 AppRoot 全树
                        val progress by viewModel.playerVM.progress.collectAsState(initial = 0L)
                        val duration by viewModel.playerVM.duration.collectAsState(initial = 0L)
                        NowPlayingScreen(
                            currentSong = currentSong,
                            isPlaying = isPlaying,
                            playMode = playMode,
                            progressMs = progress,
                            durationMs = duration,
                            lyrics = lyrics,
                            lyricsAvailability = lyricsAvailability,
                            coverCandidates = coverCandidates,
                            highlightMode = lyricsHighlightMode,
                            lyricsFontScale = lyricsFontScale,
                            onLyricsFontScaleChange = { viewModel.updateLyricsFontScale(it) },
                            coverFilterEnabled = coverFilterEnabled,
                            coverFilterBlurRadius = coverFilterBlurRadius,
                            coverFilterDarkOverlay = coverFilterDarkOverlay,
                            // 统一用合并后的 favoriteIds（NAS + 网络/本地），无需按歌曲类型分流
                            isFavorite = currentSong?.let { song -> song.id in favoriteIds } ?: false,
                            isImmersiveMode = isImmersiveMode.value,
                            onToggleImmersive = { isImmersiveMode.value = !isImmersiveMode.value },
                            onPlayPause = { viewModel.playerVM.playPause() },
                            onNext = { viewModel.playerVM.next() },
                            onPrevious = { viewModel.playerVM.previous() },
                            onTogglePlayMode = { viewModel.playerVM.togglePlayMode() },
                            // === KARAOKE 人声消除 ===
                            vocalRemovalEnabled = vocalRemovalEnabled,
                            onToggleVocalRemoval = { viewModel.vocalVM.toggleVocalRemoval() },
                            // === K 歌页面状态 ===
                            showKaraoke = showKaraoke,
                            onEnterKaraoke = { viewModel.vocalVM.enterKaraoke() },
                            onExitKaraoke = { viewModel.vocalVM.exitKaraoke() },
                            // === MTV 音乐视频 ===
                            mvAvailable = mvState is com.nasmusic.tv.ui.viewmodel.MvAvailability.Ready,
                            onEnterMv = { viewModel.enterMvMode() },
                            // 手机端不启动 HTTP 遥控服务（自身即控制端）
                            remoteControlUrl = if (isTV) viewModel.remoteControlUrl.collectAsState().value else null,
                            onSeek = { viewModel.playerVM.seekTo(it) },
                            onSwitchLyricsSource = { viewModel.switchLyricsSource(it) },
                            onChangeHighlightMode = { viewModel.setLyricsHighlightMode(it) },
                            // 统一走 toggleNetworkFavorite：内部按歌曲类型分流（NAS→adapter，其他→DataStore）
                            onToggleFavorite = currentSong?.let { song ->
                                { viewModel.toggleNetworkFavorite(song) }
                            },
                            technicalInfo = viewModel.songTechnicalInfo.collectAsState(initial = null).value,
                            onLoadTechnicalInfo = { viewModel.loadSongTechnicalInfo() },
                            spectrumData = spectrumData,
                            spectrumEnabled = settings.spectrumEnabled,
                            visualizerTheme = settings.visualizerTheme,
                            onSearchArtist = { keyword ->
                                // 跳转到曲库 SEARCH Tab 并触发跨源搜索（NAS+网络+百度+Jamendo+本地）
                                viewModel.selectLibraryTab(LibraryTab.SEARCH)
                                viewModel.setLibrarySearchKeyword(keyword)
                                viewModel.navVM.navigateTo(Screen.Library)
                            },
                            onSearchSong = { keyword ->
                                viewModel.selectLibraryTab(LibraryTab.SEARCH)
                                viewModel.setLibrarySearchKeyword(keyword)
                                viewModel.navVM.navigateTo(Screen.Library)
                            },
                            // === K 歌页面：升降调 / 变速 ===
                            pitchSemitones = pitchSemitones,
                            playbackSpeed = playbackSpeed,
                            onSetPitch = { viewModel.vocalVM.setPitchSemitones(it) },
                            onSetSpeed = { viewModel.vocalVM.setPlaybackSpeed(it) },
                            onResetPitch = { viewModel.vocalVM.resetPitch() },
                            onResetSpeed = { viewModel.vocalVM.resetSpeed() },
                            // === 分离模式（快速/高质量） ===
                            isHighQualityMode = separationMode == com.nasmusic.tv.data.prefs.AppPreferences.SeparationMode.HIGH_QUALITY,
                            isSeparating = separating,
                            separationProgress = separationProgress,
                            hqError = hqError,
                            onToggleSeparationMode = { viewModel.vocalVM.toggleSeparationMode() },
                            onClearHqError = { viewModel.vocalVM.clearHqError() },
                            hqSuccess = hqSuccess,
                            onClearHqSuccess = { viewModel.vocalVM.clearHqSuccess() },
                            // 高质量分离模型是否已下载（未下载时 K 歌页禁用高质量切换）
                            modelDownloaded = modelDownloaded,
                            // === F2-2b 睡眠定时器（常驻按钮：未启动"-"，运行中剩余分钟，点击弹档位） ===
                            sleepTimerState = sleepTimerSt,
                            onSleepTimerStart = { viewModel.playerVM.startSleepTimer(it) },
                            onSleepTimerCancel = { viewModel.playerVM.cancelSleepTimer() },
                            // === F2-3 智能电台（NAS 已连接且当前歌非网络歌曲时显示） ===
                            onEnterSmartRadio = currentSong?.takeIf { isConnected && !it.isNetworkSong }?.let {
                                { viewModel.startSmartRadioFromCurrent() }
                            }
                        )
                    }
}
