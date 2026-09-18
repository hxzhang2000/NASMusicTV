package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.R
import com.nasmusic.tv.backend.playlist.PlaylistImporter
import com.nasmusic.tv.backend.playlist.PlaylistEnricher
import com.nasmusic.tv.backend.playlist.PlaylistParsers
import com.nasmusic.tv.backend.playlist.UrlReachabilityChecker
import com.nasmusic.tv.data.model.BackupMessage
import com.nasmusic.tv.data.model.PlaylistImportHistoryItem
import com.nasmusic.tv.data.prefs.AppPreferences
import com.nasmusic.tv.util.AppLog
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 歌单导入域 ViewModel（docs/playlist-import-feature-plan.md 阶段5）。
 *
 * 镜像 BackupViewModel 模式：导入历史订阅 / 导入编排 / 消息消费 / 删除联动。
 * 职责：
 * - [importPlaylist]：SAF Uri → PlaylistImporter（读流不落盘）→ 成功后后台批量
 *   UrlReachabilityChecker 测 URL 直链可达性，不可达标 Unreachable（§4.1.8 (3)）
 * - [importHistory]：订阅最近 20 条导入记录（无独立删除入口，歌单删除时联动清理）
 * - [enrichPlaylist]：手动「补全」——对 stub 逐个 enrichAndPersist + 进度回报（§5.3）
 * - init 预热（§4.7.4）：把 24h 窗口内持久化的 songReachability 灌回内存缓存
 */
class PlaylistImportViewModel(
    app: Application,
    private val enricher: PlaylistEnricher,
    private val checker: UrlReachabilityChecker,
) : AndroidViewModel(app) {

    private val nasMusicApp = app as NasMusicApp
    private val prefs: AppPreferences = nasMusicApp.appPreferences
    private val importer = PlaylistImporter(app, prefs, viewModelScope)

    /** 最近导入的歌单（按 importedAt 倒序由 AppPreferences 保证） */
    private val _importHistory = MutableStateFlow<List<PlaylistImportHistoryItem>>(emptyList())
    val importHistory: StateFlow<List<PlaylistImportHistoryItem>> = _importHistory.asStateFlow()

    /** 导入/补全操作结果消息（与 BackupMessage 同构，SettingsScreen 4s 自动消费） */
    private val _importMessage = MutableStateFlow<BackupMessage?>(null)
    val importMessage: StateFlow<BackupMessage?> = _importMessage.asStateFlow()

    /** 手动补全进度（playlistId 匹配的歌单卡片显示进度条；null = 无进行中的补全） */
    private val _enrichProgress = MutableStateFlow<EnrichProgress?>(null)
    val enrichProgress: StateFlow<EnrichProgress?> = _enrichProgress.asStateFlow()

    /**
     * URL 失效标记（songId → 判定，§4.7.4）。订阅持久化 songReachability，
     * 只暴露 24h 判定窗口内的条目（窗口外判定已过期，重启后不再显示失效徽标）。
     */
    val songReachability: StateFlow<Map<String, AppPreferences.ReachabilityEntry>> =
        prefs.songReachability
            .map { map ->
                val cutoff = System.currentTimeMillis() - AppPreferences.songReachabilityWindowMs
                map.filterValues { it.checkedAt >= cutoff }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // 手动补全限流 + 同 stub 去重（与 MainViewModel.triggerEnrichForStubs 同策略；
    // API 22 兼容：ConcurrentHashMap.newKeySet 触发 NewApi lint，用 newSetFromMap）
    private val enrichSemaphore = Semaphore(4)
    private val enrichInFlight: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    init {
        viewModelScope.launch {
            prefs.playlistImportHistory.collect { _importHistory.value = it }
        }
        viewModelScope.launch {
            warmUpReachability()
        }
    }

    /**
     * §4.7.4 启动预热：读持久化 songReachability（只存非 REACHABLE），
     * 24h 窗口内的直接 seed 进 checker 内存缓存（不重复 HEAD）；
     * 窗口外/歌单已不存在（stub 已被替换或删除）的条目自然放弃。
     */
    private suspend fun warmUpReachability() {
        val persisted = prefs.getSongReachability()
        if (persisted.isEmpty()) return
        val now = System.currentTimeMillis()
        val songUrlById = prefs.getLocalPlaylists()
            .flatMap { it.songs }
            .associateBy({ it.id }, { it.streamUrl })
        var seeded = 0
        for ((songId, entry) in persisted) {
            val age = now - entry.checkedAt
            if (age >= AppPreferences.songReachabilityWindowMs) continue
            val url = songUrlById[songId] ?: continue
            val result = runCatching { UrlReachabilityChecker.Result.valueOf(entry.result) }.getOrNull()
                ?: continue
            checker.seedCache(url, result, entry.checkedAt)
            seeded++
        }
        if (seeded > 0) {
            AppLog.d("PlaylistImportViewModel", "warmUpReachability: seeded $seeded entries")
        }
    }

    /**
     * 导入歌单（SAF Uri）。全程读流不落盘；成功后：
     * 1) 后台批量测 URL 直链可达性（并发 4 / 5s 超时 / 局域网不预判）
     * 2) 汇总消息：成功 + 不可达数量
     */
    fun importPlaylist(uri: Uri) {
        viewModelScope.launch {
            val appCtx = getApplication<Application>()
            _importMessage.value = BackupMessage(appCtx.getString(R.string.playlist_importing))
            val summary = try {
                importer.import(
                    uri,
                    onProgress = { pct ->
                        if (pct < 100 && pct % 25 == 0) {
                            _importMessage.value = BackupMessage(
                                appCtx.getString(R.string.playlist_import_progress, pct)
                            )
                        }
                    }
                )
            } catch (e: Exception) {
                AppLog.e("PlaylistImportViewModel", "import failed", e)
                _importMessage.value = BackupMessage(
                    appCtx.getString(R.string.playlist_import_failed, e.message?.take(60) ?: ""),
                    isError = true
                )
                return@launch
            }
            if (summary.playlistId.isEmpty() || summary.unrecognizedFormat) {
                _importMessage.value = BackupMessage(
                    appCtx.getString(R.string.playlist_import_unrecognized),
                    isError = true
                )
                return@launch
            }
            val unreachable = checkUrlsInPlaylist(summary.playlistId)
            _importMessage.value = BackupMessage(
                if (unreachable > 0) {
                    appCtx.getString(
                        R.string.playlist_imported_with_unreachable,
                        summary.playlistName, summary.imported, unreachable
                    )
                } else {
                    appCtx.getString(R.string.playlist_imported, summary.playlistName, summary.imported)
                }
            )
        }
    }

    /**
     * 导入后台可达性测试（§4.1.8 (3)）：对 stub 中 http(s) URL 直链批量 HEAD，
     * 非 REACHABLE → markSongUnreachable（UI 显示「URL 失效」徽标 + 卡片计数）。
     * 局域网 URL 不做预判（isPrivateLanUrl），交给 ExoPlayer 首次播放判定。
     *
     * @return 需要提示用户的不可达数量
     */
    private suspend fun checkUrlsInPlaylist(playlistId: String): Int {
        val playlist = prefs.getLocalPlaylists().firstOrNull { it.id == playlistId } ?: return 0
        val targets = playlist.songs
            .filter { it.id.startsWith(PlaylistParsers.IMPORTED_ID_PREFIX) }
            .mapNotNull { song ->
                song.streamUrl?.takeIf { it.startsWith("http") && !checker.isPrivateLanUrl(it) }
            }
        if (targets.isEmpty()) return 0

        val results = checker.checkBatch(targets)
        var bad = 0
        targets.zip(results).forEach { (url, pair) ->
            val (result, _) = pair
            if (result != UrlReachabilityChecker.Result.REACHABLE) {
                val songId = playlist.songs.firstOrNull { it.streamUrl == url }?.id ?: return@forEach
                prefs.markSongUnreachable(songId, result.name)
                bad++
            }
        }
        return bad
    }

    /** 消费导入结果消息（SettingsScreen 4s 后调用，镜像 backupMessage） */
    fun consumeImportMessage() {
        _importMessage.value = null
    }

    /**
     * 删除歌单联动清理导入历史（2026-09-18 用户决策：历史记录行无独立删除入口，
     * 歌单删除时同步清除对应记录，避免「最近导入」出现死链）。
     */
    fun consumeHistoryIfDeleted(playlistId: String) {
        viewModelScope.launch {
            prefs.deletePlaylistImportHistory(playlistId)
        }
    }

    /**
     * 手动补全（§5.3）：遍历歌单内 stub 逐个 enrichAndPersist。
     * - 命中 → clearSongUnreachable（该 stub 已升级为真 Song，失效标记作废）
     * - 未命中 → 保留 stub（UI 维持「待补全 / URL 失效」双标签）
     * 并发 4 + 同 id 去重（播放触发与手动补全共用同一 inFlight 守卫防重入）。
     */
    fun enrichPlaylist(playlistId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val playlist = prefs.getLocalPlaylists().firstOrNull { it.id == playlistId }
                ?: return@launch
            val stubs = playlist.songs
                .filter { it.id.startsWith(PlaylistParsers.IMPORTED_ID_PREFIX) }
            if (stubs.isEmpty()) return@launch

            var processed = 0
            var enriched = 0
            _enrichProgress.value = EnrichProgress(playlistId, 0, stubs.size, 0)
            try {
                stubs.forEach { stub ->
                    if (!enrichInFlight.add(stub.id)) return@forEach // 播放链路正在补全，跳过
                    try {
                        enrichSemaphore.withPermit {
                            runCatching { enricher.enrichAndPersist(playlistId, stub) }
                                .onSuccess { ok ->
                                    if (ok) {
                                        enriched++
                                        // 升级成功 → 失效标记作废（streamUrl 已指向可用资源）
                                        runCatching { prefs.clearSongUnreachable(stub.id) }
                                    }
                                }
                                .onFailure { e ->
                                    AppLog.w("PlaylistImportViewModel", "enrich '${stub.title}' failed: ${e.message}")
                                }
                        }
                    } finally {
                        enrichInFlight.remove(stub.id)
                        processed++
                        _enrichProgress.value = EnrichProgress(playlistId, processed, stubs.size, enriched)
                    }
                }
            } finally {
                val appCtx = getApplication<Application>()
                _importMessage.value = BackupMessage(
                    appCtx.getString(
                        R.string.playlist_enrich_done,
                        enriched, stubs.size - enriched
                    )
                )
                _enrichProgress.value = null
            }
        }
    }
}

/**
 * 手动补全进度（§4.5 歌单卡片「补全中」进度条数据源）。
 * playlistId 用于匹配当前进行补全的歌单；processed/total 驱动进度条与计数文本。
 */
data class EnrichProgress(
    val playlistId: String,
    val processed: Int,
    val total: Int,
    val enriched: Int,
)