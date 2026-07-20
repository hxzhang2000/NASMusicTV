package com.nasmusic.tv.data.model

/**
 * 歌曲技术信息（编码格式、比特率、采样率等）
 */
data class SongTechnicalInfo(
    val codec: String = "",          // 编码格式: "MP3", "FLAC", "AAC", "ALAC", "WAV"
    val bitrate: Int = 0,            // 比特率 (kbps)
    val sampleRate: Int = 0,         // 采样率 (Hz): 44100, 48000, 96000
    val channels: Int = 0,           // 声道数: 1=单声道, 2=立体声, 6=5.1, 8=7.1
    val fileSize: Long = 0L,         // 文件大小 (bytes)
    val durationMs: Long = 0L,       // 时长 (ms)
    val format: String = ""          // 容器格式: "MPEG Audio", "FLAC", "Matroska"
)
