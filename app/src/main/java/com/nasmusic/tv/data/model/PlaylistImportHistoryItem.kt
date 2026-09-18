package com.nasmusic.tv.data.model

/**
 * 歌单导入历史条目（存于 DataStore，见 AppPreferences.playlistImportHistory）
 *
 * - 存最近 20 条，超出按 importedAt 淘汰最旧
 * - 不提供单独删除入口（2026-09-18 用户决策）：删除歌单时由
 *   consumeHistoryIfDeleted 联动清理，避免历史列表死链
 * - Gson 序列化（与 LocalPlaylist / BackupData 同一体系，data.model 已在 ProGuard keep）
 */
data class PlaylistImportHistoryItem(
    val playlistId: String,
    val playlistName: String,
    val importedAt: Long,
    val importedCount: Int,
)