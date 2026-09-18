package com.nasmusic.tv.backend.download.model

/**
 * 下载状态 UI 模型。
 *
 * 与 [com.nasmusic.tv.backend.download.db.DownloadStatus]（DB 字段）二分：
 * - [DownloadStatus] 持久化于 downloads.db，仅 5 个粗粒度状态
 * - [DownloadState] 是 UI 内存态，承载进度百分比与失败原因
 *
 * 调用链：SongDownloadManager 把 DB 记录映射为 DownloadState 写入 `StateFlow<Map<songKey, DownloadState>>`，
 * UI 列表外层 collect 一次得到 Map 作为参数传入 [com.nasmusic.tv.ui.components.song.UnifiedSongRow]。
 */
sealed interface DownloadState {

    /** 不可下载源（本地歌曲 / 电台）→ 不渲染下载按钮 */
    data object None : DownloadState

    /** 待下载（空闲） */
    data object Idle : DownloadState

    /** 已入队，等待下载槽位（⋯） */
    data object Queued : DownloadState

    /** 下载中（⇣），[progress] 0-100 */
    data class Downloading(val progress: Int) : DownloadState

    /** 已完成（✓），点击删除二次确认 */
    data class Completed(
        val path: String,
        val coverPath: String? = null,
        val lyricPath: String? = null,
        val embedded: Boolean = false,
        /**
         * v2.35.0 多码率：**实际落盘档位**（0/128/192/320/999）。
         *
         * 由 `SongDownloadManager` 从 `DownloadSongEntity.quality` 带入，
         * 供歌曲行显示档位徽标（§5.2.3）。同曲多档并存时，
         * 用户靠它区分"无损 FLAC"与"极高 320"两行，否则会误以为重复下载。
         *
         * [com.nasmusic.tv.backend.network.QualityTiers.AUTO] 表示无档位概念
         * （存量行 / 本地 / NAS / 网盘），此时不渲染徽标。
         */
        val quality: Int = 0
    ) : DownloadState

    /** 失败（✕），点击重试，[reason] 用于提示 */
    data class Failed(val reason: String?) : DownloadState
}
