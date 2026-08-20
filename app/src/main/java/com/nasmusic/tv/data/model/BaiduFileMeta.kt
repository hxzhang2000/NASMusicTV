package com.nasmusic.tv.data.model

/**
 * filemetas 响应条目（含 dlink + 媒体元数据）
 *
 * 所有字段可空：百度 API 字段可能缺失或随版本演进，解析用 Gson 容错
 * （缺失字段不崩，记录 [com.nasmusic.tv.util.AppLog.w] 警告）。
 *
 * @param dlink       下载直链（有效期短，小时级）；dlink=1 未传或 API 变更时可能缺失
 * @param durationSec 顶层 duration（秒）
 * @param durationMs  media_info.duration_ms（毫秒，优先于 durationSec）
 * @param bitrate     kbps（不保证稳定返回）
 */
data class BaiduFileMeta(
    val fsId: Long,
    val dlink: String?,
    val filename: String?,
    val size: Long,
    val durationSec: Long?,
    val durationMs: Long?,
    val bitrate: Int?,
    val thumbs: BaiduThumbs?
) {
    /** 统一毫秒时长：优先 durationMs，其次 durationSec × 1000 */
    val duration: Long get() = durationMs ?: (durationSec?.times(1000) ?: 0L)
}

data class BaiduThumbs(
    val url: String?,
    val icon: String?
) {
    /** 缩略图优先级：url > icon */
    val bestUrl: String? get() = url ?: icon
}
