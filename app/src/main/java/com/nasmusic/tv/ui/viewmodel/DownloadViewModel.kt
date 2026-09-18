package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.download.AutoDownloadController
import com.nasmusic.tv.backend.download.DownloadStats
import com.nasmusic.tv.backend.download.QualityProbe
import com.nasmusic.tv.backend.download.SongDownloadManager
import com.nasmusic.tv.backend.download.db.DownloadStatus
import com.nasmusic.tv.backend.download.model.DownloadState
import com.nasmusic.tv.backend.download.model.downloadKey
import com.nasmusic.tv.backend.download.model.downloadKeyOf
import com.nasmusic.tv.backend.download.model.isDownloadableSong
import com.nasmusic.tv.backend.network.QualityTiers
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.StorageType
import com.nasmusic.tv.player.ModelDownloadManager
import com.nasmusic.tv.player.PlayerManager
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.StorageUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 下载域 ViewModel（R-1 拆分自 MainViewModel）：
 * 歌曲下载统计/清除/删除、模型下载状态管理。
 *
 * 依赖：SongDownloadManager、ModelDownloadManager、AutoDownloadController。
 */
class DownloadViewModel(
    app: Application,
    private val songDownloadManager: SongDownloadManager,
    private val modelDownloadManager: ModelDownloadManager,
    private val autoDownloadController: AutoDownloadController
) : AndroidViewModel(app) {

    private val nasMusicApp = app as NasMusicApp
    private val prefs = nasMusicApp.appPreferences
    private val playerManager: PlayerManager = nasMusicApp.playerManager

    private companion object {
        /** Meting 源标识：唯一支持多码率的网络源（方案 §2.5） */
        const val METING_SOURCE = "meting"
    }

    /** 直链解析器（复用 NasMusicApp 中已注入 detailed lambda 的实例） */
    private val resolver = nasMusicApp.downloadResolver

    /** 下载库（档位维度去重查询） */
    private val repo = nasMusicApp.downloadRepository

    // ===== v2.35.0 多码率：单曲下载码率选择（方案 §5.2.1）=====

    /**
     * 单曲下载的码率选择状态。
     *
     * 探测结果决定 UI 形态：多档 → [Pick] 弹面板；单档 → 不进入本状态（直接下载）；
     * 零档 → [None] 报错。探测中显示 [Probing]。
     */
    sealed interface QualityPickerState {
        val song: Song

        /** 正在探测可用音质（≈1.2s 封顶） */
        data class Probing(override val song: Song) : QualityPickerState

        /** 多档可用，弹面板选择。@param downloaded 已下载档位（置灰不可选） */
        data class Pick(
            override val song: Song,
            val available: List<Int>,
            val downloaded: Set<Int>
        ) : QualityPickerState

        /** 零档可用 → 错误提示，不入队 */
        data class None(override val song: Song) : QualityPickerState
    }

    private val _qualityPicker = MutableStateFlow<QualityPickerState?>(null)
    val qualityPicker: StateFlow<QualityPickerState?> = _qualityPicker.asStateFlow()

    /** 需要父级刷新合并数据时的回调（由 MainViewModel 注入，避免子 VM 间直连） */
    var onLocalSongsChanged: (() -> Unit)? = null

    // ===== 歌曲下载 =====

    /**
     * 歌曲下载状态表 songKey → [DownloadState]。
     * 由 [SongDownloadManager] 维护的 StateFlow，UI 列表外层 collect 一次后作为参数传入行组件。
     */
    val songDownloadStates: StateFlow<Map<String, DownloadState>>
        get() = songDownloadManager.downloadStates

    /** 下载统计信息（歌曲数/歌词数/封面数/占用空间），供设置页展示 */
    private val _downloadStats = MutableStateFlow(DownloadStats())
    val downloadStats: StateFlow<DownloadStats> = _downloadStats.asStateFlow()

    /** 刷新下载统计（设置页进入时 + 下载完成时调用） */
    fun refreshDownloadStats() {
        viewModelScope.launch {
            _downloadStats.value = nasMusicApp.downloadRepository.getDownloadStats()
        }
    }

    /** 手动下载单曲（歌曲行 ⬇ 按钮）。受总开关与空间限制，不受自动下载配额限制 */
    fun downloadSong(song: Song) {
        viewModelScope.launch {
            // P1-15: 补齐 downloadNow 中的安全检查（enqueue → executeDownload 会跳过这些）
            // 1. 总开关
            val settings = prefs.appSettings.first()
            if (!settings.downloadEnabled) return@launch
            // 2. 可下载性（本地歌曲 / 天气电台不可下载）
            if (!isDownloadableSong(song)) return@launch

            // v2.35.0 多码率（方案 §5.2.1）：非 Meting 源无码率概念 → 直接按 AUTO 入队
            if (!song.isNetworkSong || song.networkSource != METING_SOURCE) {
                enqueueManual(song, QualityTiers.AUTO)
                return@launch
            }

            // 3. 探测可用档位（决定弹窗/直下/报错）
            _qualityPicker.value = QualityPickerState.Probing(song)
            val available = QualityProbe.probeAvailableQualities(song, resolver)
            if (available.isEmpty()) {
                // 零档可用 → 错误提示，不入队
                _qualityPicker.value = QualityPickerState.None(song)
                return@launch
            }
            if (available.size == 1) {
                // 仅一档 → 不弹窗，直接下载；但给一条轻提示（否则用户点完看不到任何变化）
                _qualityPicker.value = null
                val only = available.first()
                val started = enqueueManual(song, only)
                val label = getApplication<Application>().getString(
                    com.nasmusic.tv.backend.network.QualityTiers.labelResOf(only)
                )
                showMessage(
                    getApplication<Application>().getString(
                        if (started) R.string.quality_download_started else R.string.quality_already_downloaded,
                        label
                    )
                )
                return@launch
            }
            // 多档 → 弹码率选择面板（默认选中最高可用档）
            val downloaded = repo.downloadedQualitiesOf(song.id)
                .filter { it.status == DownloadStatus.COMPLETED.name }
                .map { it.quality }
                .toSet()
            _qualityPicker.value = QualityPickerState.Pick(song, available, downloaded)
        }
    }

    /** 用户在码率面板选定档位后调用 */
    fun downloadWithQuality(song: Song, quality: Int) {
        _qualityPicker.value = null
        viewModelScope.launch {
            val started = enqueueManual(song, quality)
            // v2.35.0 手机端修复：确认后必须给出反馈。
            // 原实现静默 return，用户点了「下载」看不到任何变化，误以为按钮无效。
            // 复用本类既有的 showMessage 通道（与 deleteModel 等操作的反馈一致）。
            val label = getApplication<Application>().getString(
                com.nasmusic.tv.backend.network.QualityTiers.labelResOf(quality)
            )
            showMessage(
                getApplication<Application>().getString(
                    if (started) R.string.quality_download_started else R.string.quality_already_downloaded,
                    label
                )
            )
        }
    }

    /** 关闭码率面板 */
    fun dismissQualityPicker() {
        _qualityPicker.value = null
    }

    /**
     * 内部：幂等检查 + 入队。
     *
     * @return true 表示已入队；false 表示因"已下载 / 下载中 / 已入队"而跳过
     *         （调用方据此给用户反馈，而不是静默无响应）
     */
    private suspend fun enqueueManual(song: Song, quality: Int): Boolean {
        val key = song.downloadKeyOf(quality)
        val state = songDownloadStates.value[key]
        if (state is DownloadState.Completed ||
            state is DownloadState.Downloading ||
            state is DownloadState.Queued
        ) {
            return false
        }
        songDownloadManager.enqueue(song, auto = false, quality = quality)
        return true
    }

    /**
     * 清空所有已下载歌曲：删除文件 + 清空 download_songs + 刷新 local_songs
     */
    fun clearAllDownloads() {
        viewModelScope.launch {
            val app = nasMusicApp
            val repo = app.downloadRepository
            val completed = repo.getCompleted()

            // 0. 无论 DB 是否有记录，都必须清空内存状态 Map
            //    （否则搜索结果仍显示"已下载"）
            app.songDownloadManager.clearAllStates()

            if (completed.isEmpty()) {
                // DB 已空，但 local_songs 可能有残留（路径不匹配导致之前没删干净）
                app.localMusicRepository.deleteByStorageType(StorageType.DOWNLOAD.name)
                onLocalSongsChanged?.invoke()
                _downloadStats.value = DownloadStats()
                return@launch
            }

            // 1. 删除所有音频文件
            var deletedBytes = 0L
            for (e in completed) {
                e.audioPath?.let { path ->
                    val f = java.io.File(path)
                    if (f.exists()) {
                        deletedBytes += f.length()
                        f.delete()
                    }
                }
                // 清理旁路文件
                e.coverPath?.let { java.io.File(it).delete() }
                e.lyricPath?.let { java.io.File(it).delete() }
            }

            // 2. 清空 download_songs 表
            repo.deleteAll()

            // 3. 从 local_songs 移除所有下载类歌曲
            //    用 deleteByStorageType 而非 removeByPaths（后者按 audioPath 匹配，
            //    但 LocalSongEntity.path 存的是 "file://..." URI，与 audioPath 不匹配）
            app.localMusicRepository.deleteByStorageType(StorageType.DOWNLOAD.name)

            // 4. 刷新内存
            onLocalSongsChanged?.invoke()

            // 5. 提示
            // 2026-09-14：原先把 deletedBytes(Long 裸字节数) 直接塞进 %1$s，
            // UI 会显示"已清空全部下载（1234567890）"。改用 StorageUtils.formatSize
            // 输出人类可读体积（"1.18 GB"），同时消除 StringFormatMatches 告警。
            val msg = getApplication<Application>().getString(
                R.string.download_cleared,
                StorageUtils.formatSize(deletedBytes)
            )
            showMessage(msg)

            // 刷新下载统计
            _downloadStats.value = DownloadStats()
        }
    }

    /**
     * 删除单首已下载歌曲：删文件 + 删 download_songs 记录 + 从 local_songs 移除
     */
    fun deleteDownload(song: Song) {
        viewModelScope.launch {
            val app = nasMusicApp
            val repo = app.downloadRepository
            val key = song.downloadKey
            val entity = repo.get(key) ?: return@launch

            // 1. 如果当前正在播放此歌 → 暂停
            val current = playerManager.playerState.value.currentSong
            if (current?.id == song.id) {
                playerManager.pause()
            }

            // 2. 删除文件
            entity.audioPath?.let { path ->
                val f = java.io.File(path)
                if (f.exists()) {
                    f.delete()
                }
            }
            entity.coverPath?.let { java.io.File(it).delete() }
            entity.lyricPath?.let { java.io.File(it).delete() }

            // 3. 删除 download_songs 记录
            repo.delete(key)

            // 4. 从 local_songs 移除（path 存的是 "file://..." URI，需加前缀匹配）
            entity.audioPath?.let { path ->
                app.localMusicRepository.removeByPaths(listOf("file://$path"))
            }

            // 5. 刷新内存
            onLocalSongsChanged?.invoke()

            // 6. 提示
            val msg = getApplication<Application>().getString(R.string.download_deleted, song.title)
            showMessage(msg)

            // 刷新下载统计
            _downloadStats.value = app.downloadRepository.getDownloadStats()
        }
    }

    /** 操作结果消息（接 MainViewModel 的 connectMessage 通道语义） */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private fun showMessage(msg: String) {
        viewModelScope.launch {
            _message.value = msg
            kotlinx.coroutines.delay(2000)
            _message.value = null
        }
    }

    // ===== 模型下载 =====

    private val _modelDownloaded = MutableStateFlow(false)
    val modelDownloaded: StateFlow<Boolean> = _modelDownloaded
    private val _modelDownloading = MutableStateFlow(false)
    val modelDownloading: StateFlow<Boolean> = _modelDownloading
    private val _modelDownloadProgress = MutableStateFlow(0f)
    val modelDownloadProgress: StateFlow<Float> = _modelDownloadProgress
    private val _modelDownloadedMB = MutableStateFlow(0L)
    val modelDownloadedMB: StateFlow<Long> = _modelDownloadedMB
    private val _modelTotalMB = MutableStateFlow(0L)
    val modelTotalMB: StateFlow<Long> = _modelTotalMB
    private val _modelDownloadError = MutableStateFlow<String?>(null)
    val modelDownloadError: StateFlow<String?> = _modelDownloadError
    private val _modelSizeMB = MutableStateFlow(0.0)
    val modelSizeMB: StateFlow<Double> = _modelSizeMB
    private val _modelPath = MutableStateFlow("")
    val modelPath: StateFlow<String> = _modelPath

    /** 刷新模型下载状态（启动时/设置页进入时调用） */
    fun refreshModelStatus() {
        val mgr = modelDownloadManager
        _modelDownloaded.value = mgr.isModelDownloaded()
        _modelSizeMB.value = mgr.getModelSizeMB()
        _modelPath.value = mgr.getModelFile().absolutePath
    }

    /** 下载高质量分离模型（带进度回调） */
    fun downloadModel() {
        if (_modelDownloading.value) return
        val mgr = modelDownloadManager
        _modelDownloading.value = true
        _modelDownloadError.value = null
        _modelDownloadProgress.value = 0f
        _modelDownloadedMB.value = 0L
        _modelTotalMB.value = mgr.getExpectedSizeMB().toLong()
        viewModelScope.launch {
            val error = mgr.downloadModel { downloaded, total ->
                _modelDownloadedMB.value = downloaded / (1024 * 1024)
                _modelTotalMB.value = total / (1024 * 1024)
                _modelDownloadProgress.value = if (total > 0) downloaded.toFloat() / total else 0f
            }
            _modelDownloading.value = false
            if (error == null) {
                _modelDownloaded.value = true
                _modelSizeMB.value = mgr.getModelSizeMB()
                _modelDownloadProgress.value = 1f
            } else {
                _modelDownloadError.value = error
                _modelDownloaded.value = false
            }
        }
    }

    /** 删除已下载的模型文件 */
    fun deleteModel(onModeFallback: () -> Unit) {
        val mgr = modelDownloadManager
        mgr.deleteModel()
        _modelDownloaded.value = false
        _modelSizeMB.value = 0.0
        _modelDownloadProgress.value = 0f
        // 若当前处于高质量模式，回退到快速模式（由调用方 VocalSeparation 域处理）
        onModeFallback()
    }

    /** AutoDownloadController 暴露（切歌判定链由 MainViewModel 的 currentSong 收集器调用） */
    fun onSongChanged(song: Song) {
        autoDownloadController.onSongChanged(song)
    }
}
