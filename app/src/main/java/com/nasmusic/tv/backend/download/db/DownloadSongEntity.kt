package com.nasmusic.tv.backend.download.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 下载歌曲索引实体（downloads.db / download_songs）
 *
 * - [songKey]：稳定主键，由 [com.nasmusic.tv.backend.download.model.downloadKey] 推导
 *   （local/nas/ntwk + songId），跨源稳定且不与网络源 id 冲突
 * - [dedupeKey]：跨源去重键（标题+艺术家规范化），用于"同曲跨平台不重复下载"
 *
 * 关键字段：
 * - [audioPath]：完成后的音频文件绝对路径（用于播放 / 删除 / 媒体扫描）
 * - [tmpPath]：下载中 .part 临时文件路径（崩溃恢复时清理）
 * - [embedded]：封面/歌词是否已成功内嵌进音频；false 表示走了旁路（同目录 .jpg/.lrc）
 */
@Entity(
    tableName = "download_songs",
    indices = [
        Index(value = ["songKey"], unique = true),
        Index(value = ["dedupeKey"]),
        Index(value = ["status"]),
        Index(value = ["songId"])          // v2.35.0 多码率：查询"该曲所有已下载档"
    ]
)
data class DownloadSongEntity(
    @PrimaryKey val songKey: String,
    val dedupeKey: String,
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val sourceType: String,                // NAS / NETWORK_MUSIC / BAIDU_PAN / JAMENDO / WEATHER_RADIO
    val networkSource: String? = null,

    val audioPath: String? = null,
    val tmpPath: String? = null,           // .part 临时文件路径（仅 DOWNLOADING/PENDING 时存在）
    val coverPath: String? = null,         // 旁路封面（内嵌时 null）
    val lyricPath: String? = null,         // 旁路歌词
    val embedded: Boolean = false,         // 是否已内嵌封面/歌词

    val fileSize: Long = 0L,
    val durationMs: Long = 0L,
    val bitrate: Int = 0,                   // 媒体文件**真实**码率（NAS 歌曲会填），勿与 quality 混用
    val containerExt: String = "",          // mp3/flac/...，决定能否内嵌
    /**
     * v2.35.0 多码率：音质**档位值**（0/128/192/320/999）。
     *
     * ⚠️ 与 [bitrate] 是两个概念：
     * - [bitrate] 是文件真实比特率（如 320、1411）；
     * - [quality] 是档位标识，其中 999 是 FLAC 约定标识符、**不是真实码率**。
     * 两者禁止互相赋值（把 999 写进 bitrate 会让 FLAC 显示成 "999 kbps"）。
     *
     * 记录的是**实际落盘档位**（静默降级后 = 降级到的档位，非请求档位）。
     */
    val quality: Int = 0,

    val status: String = DownloadStatus.PENDING.name,
    val progress: Int = 0,
    val errorMsg: String? = null,
    val retryCount: Int = 0,

    val autoDownloaded: Boolean = false,    // 配额只统计 true 且 COMPLETED
    val createdAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null
)
