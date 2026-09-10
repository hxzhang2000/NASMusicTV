package com.nasmusic.tv.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nasmusic.tv.NasMusicApp
import com.nasmusic.tv.R
import com.nasmusic.tv.data.model.LocalPlaylist
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 本地歌单域 ViewModel（R-1 拆分自 MainViewModel）：
 * 「我的」Tab 的本地歌单 CRUD（DataStore 持久化，可混合 NAS/网络歌曲）。
 *
 * 依赖：AppPreferences（经 NasMusicApp 取）。播放动作经 [LibraryEvent.PlayRequested] 路由。
 */
class PlaylistViewModel(app: Application) : AndroidViewModel(app) {

    private val nasMusicApp = app as NasMusicApp
    private val prefs = nasMusicApp.appPreferences

    private val _localPlaylists = MutableStateFlow<List<LocalPlaylist>>(emptyList())
    val localPlaylists: StateFlow<List<LocalPlaylist>> = _localPlaylists.asStateFlow()

    init {
        // 监听本地歌单变化（DataStore 持久化，响应式更新）
        viewModelScope.launch {
            prefs.playlist.localPlaylists.collect { playlists ->
                _localPlaylists.value = playlists
            }
        }
    }

    /**
     * 创建本地歌单（空名称忽略）
     */
    fun createLocalPlaylist(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                prefs.playlist.createLocalPlaylist(name)
            } catch (e: Exception) {
                AppLog.e("PlaylistViewModel", "createLocalPlaylist failed", e)
                showError(getApplication<Application>().getString(R.string.create_playlist_error, e.message?.take(50)))
            }
        }
    }

    /**
     * 重命名本地歌单
     */
    fun renameLocalPlaylist(id: String, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            try {
                prefs.playlist.renameLocalPlaylist(id, newName)
            } catch (e: Exception) {
                AppLog.e("PlaylistViewModel", "renameLocalPlaylist failed", e)
                showError(getApplication<Application>().getString(R.string.rename_playlist_error, e.message?.take(50)))
            }
        }
    }

    /**
     * 删除本地歌单
     */
    fun deleteLocalPlaylist(id: String) {
        viewModelScope.launch {
            try {
                prefs.playlist.deleteLocalPlaylist(id)
            } catch (e: Exception) {
                AppLog.e("PlaylistViewModel", "deleteLocalPlaylist failed", e)
                showError(getApplication<Application>().getString(R.string.delete_playlist_error, e.message?.take(50)))
            }
        }
    }

    /**
     * 添加歌曲到本地歌单（已存在则提示）
     */
    fun addSongToPlaylist(playlistId: String, song: Song) {
        viewModelScope.launch {
            try {
                val added = prefs.playlist.addSongToPlaylist(playlistId, song)
                if (!added) {
                    showError(getApplication<Application>().getString(R.string.song_already_in_playlist, ""))
                }
            } catch (e: Exception) {
                AppLog.e("PlaylistViewModel", "addSongToPlaylist failed", e)
                showError(getApplication<Application>().getString(R.string.add_to_playlist_error, e.message?.take(50)))
            }
        }
    }

    /**
     * 从本地歌单移除歌曲
     */
    fun removeSongFromPlaylist(playlistId: String, songId: String) {
        viewModelScope.launch {
            try {
                prefs.playlist.removeSongFromPlaylist(playlistId, songId)
            } catch (e: Exception) {
                AppLog.e("PlaylistViewModel", "removeSongFromPlaylist failed", e)
                showError(getApplication<Application>().getString(R.string.remove_from_local_playlist_error, e.message?.take(50)))
            }
        }
    }

    // ---- 常规错误消息（沿用 MainViewModel 的 errorMessage 通道）----
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private fun showError(msg: String) {
        _errorMessage.value = msg
    }

    /** 消费错误消息（UI 显示后调用，由 MainViewModel 的 errorMessage 消费方统一接管时可移除） */
    fun consumeErrorMessage() {
        _errorMessage.value = null
    }
}
