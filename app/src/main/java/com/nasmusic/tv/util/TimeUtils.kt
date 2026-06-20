package com.nasmusic.tv.util

/**
 * 时间格式化工具
 */
object TimeUtils {

    /**
     * 将毫秒格式化为 mm:ss
     */
    fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    /**
     * 将毫秒格式化为 mm:ss.SSS
     */
    fun formatDurationWithMillis(ms: Long): String {
        val minutes = ms / 60000
        val seconds = (ms % 60000) / 1000
        val millis = ms % 1000
        return String.format("%d:%02d.%03d", minutes, seconds, millis)
    }
}
