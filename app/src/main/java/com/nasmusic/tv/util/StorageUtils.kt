package com.nasmusic.tv.util

import android.os.StatFs
import java.io.File
import java.util.Locale

/**
 * 存储空间工具
 *
 * 抽取自 [com.nasmusic.tv.backend.local.StorageMonitor.getAvailableSpace] 的私有实现，
 * 供下载流程（[com.nasmusic.tv.backend.download.StorageGuard]）与本地扫描共用。
 *
 * 设计要点：
 * - [availableBytes] 取 root 与内部数据分区较小值 —— 应用专属目录在多数设备上由 /data 承载
 *   （fuse/sdcardfs），单点判定会高估可用空间（见 §10.1）
 * - [formatSize] 给 UI 用，按 SI 规则保留两位小数
 */
object StorageUtils {

    /**
     * 单点可用空间（不分卷，仅 [path] 所在分区）
     *
     * 静默异常返回 0（避免极少数 StatFs 不可用设备崩溃）。
     */
    fun availableBytesAt(path: File): Long = runCatching {
        val stat = StatFs(path.absolutePath)
        stat.availableBlocksLong * stat.blockSizeLong
    }.getOrDefault(0L)

    /**
     * 双分区取最小（应用专属外部目录 + 内部 filesDir）。
     *
     * 应用专属目录多由 /data 分区承载（fuse/sdcardfs），单点判定会高估可用空间，
     * 必须取 min(root 所在分区, filesDir 所在分区) 才稳妥。
     */
    fun availableBytesMin(root: File?, filesDir: File?): Long {
        val a = if (root != null && root.exists()) availableBytesAt(root) else 0L
        val b = if (filesDir != null && filesDir.exists()) availableBytesAt(filesDir) else 0L
        return if (a == 0L) b else if (b == 0L) a else minOf(a, b)
    }

    /** 人类可读的字节大小（"1.23 GB"） */
    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var idx = 0
        while (size >= 1024.0 && idx < units.size - 1) {
            size /= 1024.0
            idx++
        }
        return String.format(Locale.US, "%.2f %s", size, units[idx])
    }
}
