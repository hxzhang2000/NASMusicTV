package com.nasmusic.tv.backend.download

import com.nasmusic.tv.backend.download.model.DownloadSettings
import com.nasmusic.tv.backend.download.model.dedupeKey
import com.nasmusic.tv.backend.download.model.downloadKey
import com.nasmusic.tv.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 播放时自动下载控制器（见方案 §9）
 *
 * 判定链（[onSongChanged]，切歌即触发，不等播放进度）：
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
    }

    private var lastQuotaNotifyAt = 0L

    fun onSongChanged(song: Song) {
        scope.launch(Dispatchers.IO) {
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
            return song.durationMs / 1000 * song.bitrate / 8
        }
        return MAX_FILE_SIZE_FALLBACK
    }
}