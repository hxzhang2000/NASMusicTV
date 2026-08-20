package com.nasmusic.tv.data.model

import com.nasmusic.tv.util.BaiduFilenameParser

/**
 * 百度网盘文件（list/search 响应条目）
 *
 * `fs_id` 是百度网盘全局唯一文件 ID，**跨重命名/移动保持稳定**（百度官方保证），
 * 适合作唯一值。
 *
 * @param fsId           文件全局唯一 ID
 * @param path           文件绝对路径（如 `/音乐/周杰伦/晴天.mp3`）
 * @param serverFilename 文件名（含扩展名）
 * @param isDir          true=目录
 * @param size           文件大小（字节）
 * @param category       文件类型代码（见 [com.nasmusic.tv.backend.network.baidu.BaiduNetdiskConfig] 的 category 表）
 * @param md5             云端哈希（非文件真实 MD5）
 * @param serverMtime     服务端修改时间（秒级时间戳）
 */
data class BaiduFile(
    val fsId: Long,
    val path: String,
    val serverFilename: String,
    val isDir: Boolean,
    val size: Long,
    val category: Int,
    val md5: String?,
    val serverMtime: Long
) {
    /**
     * 转 [Song]（音频文件）。
     *
     * - id 格式 `ntwk_baidu_${fs_id}`（与现有网络歌曲 `ntwk_${source}_${sourceId}` 约定一致）
     * - streamUrl 为 null（播放时由 [com.nasmusic.tv.backend.network.NetworkMusicManager.resolvePlayUrl] 实时解析）
     * - artist/title 由文件名解析（"歌手 - 歌名"格式），解析失败则 artist 留空、title 用文件名
     */
    fun toSong(durationMs: Long = 0L, coverUrl: String? = null): Song {
        val (artist, title) = BaiduFilenameParser.parse(serverFilename)
        return Song(
            id = "ntwk_baidu_$fsId",
            title = title,
            artist = artist,
            coverUrl = coverUrl,
            streamUrl = null,
            durationMs = durationMs,
            isNetworkSong = true,
            networkSource = "baidu",
            networkId = fsId.toString(),
            path = path
        )
    }
}
