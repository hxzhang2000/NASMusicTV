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
    data class Completed(val path: String) : DownloadState

    /** 失败（✕），点击重试，[reason] 用于提示 */
    data class Failed(val reason: String?) : DownloadState
}
