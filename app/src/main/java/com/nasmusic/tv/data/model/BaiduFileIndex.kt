package com.nasmusic.tv.data.model

/**
 * 百度网盘本地索引（缓存网盘目录扫描结果，避免每次进 Tab 都等 listall）
 *
 * @param rootPath   索引建立时的扫描根目录（变更后索引失效）
 * @param lastSyncAt 最近一次同步时间戳（毫秒）
 * @param entries    文件条目列表
 */
data class BaiduFileIndex(
    val rootPath: String,
    val lastSyncAt: Long,
    val entries: List<BaiduIndexEntry>
)

data class BaiduIndexEntry(
    val fsId: Long,
    val path: String,
    val filename: String,
    val title: String,
    val artist: String?,
    val size: Long,
    val serverMtime: Long,
    /** 文件分类（[com.nasmusic.tv.backend.network.baidu.BaiduNetdiskConfig] CATEGORY_*），
     *  默认 AUDIO 保持与早期索引兼容 */
    val category: Int = com.nasmusic.tv.backend.network.baidu.BaiduNetdiskConfig.CATEGORY_AUDIO
) {
    /** 转 Song（与 BaiduFile.toSong 等价，但不重新解析文件名——索引已缓存） */
    fun toSong(durationMs: Long = 0L, coverUrl: String? = null): Song {
        return Song(
            id = "ntwk_baidu_$fsId",
            title = title,
            artist = artist ?: "",
            coverUrl = coverUrl,
            streamUrl = null,
            durationMs = durationMs,
            isNetworkSong = true,
            networkSource = "baidu",
            networkId = fsId.toString(),
            path = path
        )
    }

    /** 转 [BaiduFile]（供 [BaiduMvFileService] 构建搜索结果） */
    fun toBaiduFile(): BaiduFile = BaiduFile(
        fsId = fsId,
        path = path,
        serverFilename = filename,
        isDir = false,
        size = size,
        category = category,
        md5 = null,
        serverMtime = serverMtime
    )
}
