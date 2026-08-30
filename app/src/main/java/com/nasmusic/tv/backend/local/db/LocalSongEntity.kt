package com.nasmusic.tv.backend.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 本地音乐索引表
 *
 * 持久化本地音乐扫描结果，供启动加载 / 增量更新 / 索引搜索使用。
 *
 * @param mediaStoreId MediaStore ID 或文件路径 hashCode（USB 文件无 MediaStore ID 时）
 * @param path 文件绝对路径（唯一，用于增量扫描比对 + LRC 歌词查找）
 * @param contentUri 序列化的 content:// 或 file:// URI（ExoPlayer 直接播放）
 * @param storageType StorageType.name（"INTERNAL"/"EXTERNAL"/"USB"）
 * @param lastModified 文件最后修改时间（增量扫描判断变更用）
 * @param coverPath 提取的内嵌封面缓存路径（可选，延迟填充）
 */
@Entity(tableName = "local_songs", indices = [Index(value = ["path"], unique = true)])
data class LocalSongEntity(
    @PrimaryKey val mediaStoreId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,
    val size: Long,
    val dateAdded: Long,
    val mimeType: String,
    val contentUri: String,
    val volumeName: String,
    val storageType: String,
    val path: String,
    val lastModified: Long,
    val coverPath: String? = null
)