package com.nasmusic.tv.backend.download

import com.nasmusic.tv.backend.download.model.DownloadSettings
import com.nasmusic.tv.backend.download.model.dedupeKey
import com.nasmusic.tv.backend.download.model.downloadKey
import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 播放时自动下载控制器（见方案 §9）
 *
 * 判定链（[onSongChanged]，切歌后延迟 5s 确认用户未切走再触发）：
 * 0. 延迟 5s → 若期间切歌则取消上一次延迟，确认用户在听这首歌
 * 1. 总开关 & 自动下载开关
 * 2. 可下载源（非本地 / 非电台）
 * 3. 是否已下载（songKey / dedupeKey 命中）
 * 4. 配额（countAutoCompleted ≥ limit → 提示 + 5 分钟节流）
 * 5. 空间（hasRoomFor → 不足则提示）
 * 6. 入队自动下载
 *
 * 失败不自动重试（避免失败循环），仅保留 ✕ 供用户手动重试。
 */
class AutoDownloadController(
    private val settings: suspend () -> DownloadSettings,
    private val repo: DownloadRepository,
    private val storage: StorageGuard,
    private val resolver: StreamUrlResolver,
    private val manager: SongDownloadManager,
    private val notify: (String) -> Unit,
    private val scope: CoroutineScope
) {
    companion object {
        private const val NOTIFY_THROTTLE_MS = 5 * 60 * 1000L
        private const val MAX_FILE_SIZE_FALLBACK = 8L * 1024 * 1024
        private const val DELAY_BEFORE_AUTO_DOWNLOAD = 5000L  // 播放≥5s 后才触发自动下载
    }

    /** 当前播放歌曲（切歌时更新，用于 5s 延迟后判断用户是否仍在听同一首） */
    @Volatile
    private var currentSong: Song? = null

    /** 待执行的自动下载延迟协程，切歌时取消上一次 */
    private var pendingAutoDownloadJob: Job? = null

    private var lastQuotaNotifyAt = 0L

    fun onSongChanged(song: Song) {
        currentSong = song
        // 取消上一次的延迟（5s 内切歌 → 放弃上一次的自动下载）
        pendingAutoDownloadJob?.cancel()
        pendingAutoDownloadJob = scope.launch(Dispatchers.IO) {
            delay(DELAY_BEFORE_AUTO_DOWNLOAD)
            // 延迟期间用户切歌 → currentSong 已更新，不再等于 song → 放弃
            if (currentSong != song) return@launch
            // 1. 总开关 + 自动下载开关
            val s = settings()
            if (!s.downloadEnabled || !s.autoDownloadOnPlay) return@launch
            // 2. 可下载源
            if (!resolver.isDownloadable(song)) return@launch
            // 3. 已下载 / 去重命中
            if (repo.get(song.downloadKey) != null) return@launch      // 已下载或失败过，不自动重试
            if (repo.findCompletedByDedupe(song.dedupeKey) != null) return@launch

            // 4. 配额
            val limit = s.autoDownloadLimit
            if (repo.countAutoCompleted() >= limit) {
                val now = System.currentTimeMillis()
                if (now - lastQuotaNotifyAt > NOTIFY_THROTTLE_MS) {
                    lastQuotaNotifyAt = now
                    notify("已达设置的最大下载数量（$limit），可在设置中调整（手动下载不受限制）")
                }
                return@launch
            }
            // 5. 空间
            if (!storage.hasRoomFor(estimateFromSong(song))) {
                storage.notifyFullOnce(notify)
                return@launch
            }
            // 6. 入队自动下载
            manager.enqueue(song, auto = true)
        }
    }

    private fun estimateFromSong(song: Song): Long {
        if (song.durationMs > 0 && song.bitrate > 0) {
            // P1-4 修复（2026-09-16）：bitrate 单位为 kbps，结果为 KB，×1024 对齐到字节
            // （与 SongDownloadManager.estimateFromSong 同步修正）
            return song.durationMs / 1000 * song.bitrate / 8 * 1024L
        }
        return MAX_FILE_SIZE_FALLBACK
    }
}