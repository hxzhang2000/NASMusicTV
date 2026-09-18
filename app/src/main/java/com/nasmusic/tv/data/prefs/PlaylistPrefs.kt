package com.nasmusic.tv.data.prefs

import com.nasmusic.tv.data.model.PlaylistImportHistoryItem
import com.nasmusic.tv.data.model.Song

/**
 * R-4 本地歌单域子 Prefs（JSON 序列化；键不迁移）。
 */
class PlaylistPrefs internal constructor(private val prefs: AppPreferences) {

    val localPlaylists = prefs.localPlaylists

    suspend fun createLocalPlaylist(name: String) = prefs.createLocalPlaylist(name)
    suspend fun renameLocalPlaylist(id: String, newName: String) = prefs.renameLocalPlaylist(id, newName)
    suspend fun deleteLocalPlaylist(id: String) = prefs.deleteLocalPlaylist(id)
    suspend fun addSongToPlaylist(playlistId: String, song: Song): Boolean =
        prefs.addSongToPlaylist(playlistId, song)
    suspend fun removeSongFromPlaylist(playlistId: String, songId: String) =
        prefs.removeSongFromPlaylist(playlistId, songId)
    suspend fun replaceSongInPlaylist(playlistId: String, oldSongId: String, newSong: Song) =
        prefs.replaceSongInPlaylist(playlistId, oldSongId, newSong)

    // --- 歌单导入（R：playlist-import-feature-plan）---
    val playlistImportHistory = prefs.playlistImportHistory
    suspend fun recordPlaylistImport(item: PlaylistImportHistoryItem) =
        prefs.recordPlaylistImport(item)
    suspend fun deletePlaylistImportHistory(playlistId: String) =
        prefs.deletePlaylistImportHistory(playlistId)

    // --- URL 可达性（旁路状态）---
    val songReachability = prefs.songReachability
    suspend fun getSongReachability() = prefs.getSongReachability()
    suspend fun markSongUnreachable(songId: String) = prefs.markSongUnreachable(songId)
    suspend fun clearSongUnreachable(songId: String) = prefs.clearSongUnreachable(songId)
}
