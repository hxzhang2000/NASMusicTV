package com.nasmusic.tv.data.model

/**
 * 存储类型（本地音乐专用）
 */
enum class StorageType {
    INTERNAL,   // 内置存储
    EXTERNAL,   // 外部 SD 卡
    USB,        // USB 存储
    UNKNOWN
}

/**
 * 增量扫描结果
 *
 * 由 [com.nasmusic.tv.backend.local.LocalMusicRepository.incrementalScan] 返回，
 * 描述本次扫描发现的新增、删除、更新歌曲列表。
 */
data class ScanResult(
    val newSongs: List<Song>,
    val deletedPaths: List<String>,
    val updatedSongs: List<Song>
) {
    fun hasChanges(): Boolean =
        newSongs.isNotEmpty() || deletedPaths.isNotEmpty() || updatedSongs.isNotEmpty()
}

/**
 * 存储设备信息（用于 USB / SD 卡插拔监听）
 */
data class StorageDevice(
    val path: String,
    val name: String,
    val type: StorageType,
    val isMounted: Boolean,
    val availableSpace: Long
)
