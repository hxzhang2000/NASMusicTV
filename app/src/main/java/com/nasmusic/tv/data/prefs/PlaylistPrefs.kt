package com.nasmusic.tv.data.prefs

/**
 * R-4 本地歌单域子 Prefs（JSON 序列化；键不迁移）。
 */
class PlaylistPrefs internal constructor(private val prefs: AppPreferences) {

    val localPlaylists = prefs.localPlaylists

    suspend fun createLocalPlaylist(name: String) = prefs.createLocalPlaylist(name)
    suspend fun renameLocalPlaylist(id: String, newName: String) = prefs.renameLocalPlaylist(id, newName)
    suspend fun deleteLocalPlaylist(id: String) = prefs.deleteLocalPlaylist(id)
    suspend fun addSongToPlaylist(playlistId: String, song: com.nasmusic.tv.data.model.Song): Boolean =
        prefs.addSongToPlaylist(playlistId, song)
    suspend fun removeSongFromPlaylist(playlistId: String, songId: String) =
        prefs.removeSongFromPlaylist(playlistId, songId)
}
