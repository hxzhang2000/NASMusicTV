package com.nasmusic.tv.backend.download

import com.nasmusic.tv.backend.download.model.DownloadSettings
import com.nasmusic.tv.backend.download.model.dedupeKey
import com.nasmusic.tv.backend.download.model.downloadKey
import com.nasmusic.tv.backend.network.QualityTiers
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
    private val scope: CoroutineScope,
    /**
     * v2.35.0 多码率：全局默认音质档位（同步读取）。
     * 自动下载统一用该档位，**不消费单曲覆盖**（方案 §2.3.2）。
     */
    private val qualityTierProvider: () -> Int = { QualityTiers.AUTO }
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

            // 3. 档位：自动下载统一用**全局默认档位**，不消费单曲覆盖（方案 §2.3.2）
            val requested = qualityTierProvider()

            // 3.1 前置去重（按请求档）：已下载该档 → 直接跳过，连解析请求都不发
            if (repo.isDownloaded(song, requested)) return@launch

            // 3.2 解析直链（走完整降级链），拿到**实际命中档位**
            //     注意：这里不能用 repo.get(song.downloadKey) 做粗判 —— 那会把
            //     "已下载 128k" 误判为"320k 也已完成"，导致无损自动下载永久跳过。
            val result = resolver.resolveDetailed(song, requested)
            if (!result.isSuccess) return@launch      // 无源可降 → 放弃，不落 FAILED

            // 3.3 ⚠️ 二次去重（按**实际**档位）：降级后可能与已下载档重合，
            //     否则下次运行会再次降级到同档 → 插入同 songKey → 主键冲突（方案 §4.5）
            val actual = result.actualQuality
            if (repo.isDownloaded(song, actual)) return@launch

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
            // 6. 入队自动下载（传实际档位，与落库 key 一致）
            manager.enqueue(song, auto = true, quality = actual)
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