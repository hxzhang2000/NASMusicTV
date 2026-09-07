package com.nasmusic.tv.backend.download.db

/**
 * 下载任务持久化状态（downloads.db 中 download_songs.status 列的值）
 *
 * 与 [com.nasmusic.tv.backend.download.model.DownloadState] 区分：
 * 此枚举持久化于 DB，仅 5 个粗粒度状态，进度等动态字段独立存储于 [DownloadSongEntity.progress]。
 */
enum class DownloadStatus {
    PENDING,      // 已入队但未开始
    DOWNLOADING,  // 下载中
    COMPLETED,    // 已完成
    FAILED,       // 失败
    DELETED       // 已删除（含删除文件 + 删索引）
}
