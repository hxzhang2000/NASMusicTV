package com.nasmusic.tv.backend.download.model

import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.RadioStation

/**
 * 歌曲下载相关扩展属性（不改 Song 数据类本身）。
 *
 * 设计要点：
 * - [downloadKey]：稳定主键，跨源不冲突（local_/nas_/ntwk_ 前缀分流）。
 * - [dedupeKey]：跨源去重键（标题+艺术家规范化），用于"同曲跨平台不重复下载"。
 */

/**
 * 下载主键。
 *
 * - 本地歌曲：`local_$id`（无需下载，仅作幂等键）
 * - 网络歌曲：`ntwk_${networkSource}_${networkId ?: id}` —— 即使同 id 跨源也不冲突
 * - NAS 歌曲：`nas_$id`
 */
val Song.downloadKey: String
    get() = when {
        isLocalSong -> "local_$id"
        isNetworkSong -> "ntwk_${networkSource ?: "unknown"}_${networkId ?: id}"
        else -> "nas_$id"
    }

/**
 * 跨源去重键：标题+艺术家规范化（小写 + 去除内部空白）。
 *
 * 用于"同一首歌不同平台 sourceId 不同"时避免重复下载。
 * 命中后即使 [downloadKey] 不同也视为已下载（UI 显示 ✓，不再发起下载）。
 */
val Song.dedupeKey: String
    get() = listOf(title, artist)
        .joinToString("|") { it.trim().lowercase().replace(Regex("\\s+"), "") }

/** RadioStation 源标识（与 RadioStation.SOURCE_ID 一致，但避免循环依赖） */
private const val RADIO_SOURCE = "weather"

/**
 * 是否可下载。
 *
 * 不可下载的源：
 * - 本地歌曲（已经在本地，无需下载）
 * - 天气电台（纯流媒体，无歌曲文件）
 */
fun isDownloadableSong(song: Song): Boolean =
    !song.isLocalSong && song.networkSource != RADIO_SOURCE

/** 下载运行时配置（来自 AppSettings） */
data class DownloadSettings(
    val downloadEnabled: Boolean,
    val autoDownloadOnPlay: Boolean,
    val autoDownloadLimit: Int,
    val downloadLocation: String        // 当前仅 "INTERNAL"，CUSTOM 为 P1 预留
)
