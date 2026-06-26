package com.nasmusic.tv.backend

import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.Genre
import com.nasmusic.tv.data.model.Playlist
import com.nasmusic.tv.data.model.Song

/**
 * 后端适配器接口
 * 统一封装不同NAS音乐后端的API差异
 */
interface BackendAdapter {

    /**
     * 后端类型标识
     */
    val backendType: String

    /**
     * 服务端显示名称（登录后由后端返回的实际名称）
     */
    val serverName: String

    /**
     * 初始化连接
     */
    suspend fun initialize(baseUrl: String, apiToken: String, username: String = "", password: String = ""): Boolean

    /**
     * 测试连接是否可用
     */
    suspend fun testConnection(): Boolean

    /**
     * 断开后端会话连接，释放服务端 session 资源。
     * Jellyfin：POST /Sessions/Logout 使 token 失效。
     * Navidrome：无状态认证，不需要显式登出。
     * 默认空实现，子类按需覆盖。
     */
    suspend fun logout() {}

    /**
     * 释放底层网络资源（OkHttp 连接池）。
     * logout() 负责使服务端 session 失效，
     * close() 负责释放客户端连接，防止连接泄漏。
     */
    fun close() {}

    /**
     * 获取所有专辑
     */
    suspend fun getAlbums(): List<Album>

    /**
     * 获取专辑中的歌曲
     */
    suspend fun getAlbumSongs(albumId: String): List<Song>

    /**
     * 获取所有歌手
     */
    suspend fun getArtists(): List<Artist>

    /**
     * 获取歌手的歌曲
     */
    suspend fun getArtistSongs(artistId: String): List<Song>

    /**
     * 获取歌曲（支持分页）
     * @param limit 每批返回的最大歌曲数量
     * @param offset 起始位置（用于分页）
     */
    suspend fun getSongs(limit: Int = 500, offset: Int = 0): List<Song>

    /**
     * 获取歌曲总数（利用 Jellyfin 的 TotalRecordCount）
     * 用于分页显示 "已加载 N / 共 M 首"
     */
    suspend fun getSongsTotalCount(): Int = 0

    /**
     * 按 ID 批量查询歌曲
     * 用于最近播放、收藏等场景，避免依赖全量歌曲列表
     * @param ids 歌曲 ID 列表
     */
    suspend fun getSongsByIds(ids: List<String>): List<Song> = emptyList()

    /**
     * 获取所有年份列表（从服务端提取，避免依赖全量歌曲）
     */
    suspend fun getYears(): List<Int> = emptyList()

    /**
     * 搜索歌曲
     */
    suspend fun searchSongs(query: String): List<Song>

    /**
     * 获取最近添加的歌曲
     */
    suspend fun getRecentSongs(): List<Song>

    /**
     * 获取歌曲流地址
     */
    fun getStreamUrl(songId: String): String

    /**
     * 获取歌曲封面地址
     */
    fun getCoverUrl(songId: String): String

    /**
     * 获取歌曲的候选封面 URL 列表，按优先级排序（歌曲→专辑→艺术家）。
     * UI 层依次尝试/轮播，第一个加载成功的即为可用封面。
     * 返回空列表表示无可用封面。
     */
    fun getCoverUrlCandidates(song: Song): List<String> = emptyList()

    /**
     * 获取歌词（如果后端支持）
     */
    suspend fun getLyrics(songId: String): String?

    // ========== F-1 扩展接口 ==========
    //
    // 以下方法均带有 Kotlin 接口默认实现（返回空值），
    // 新适配器可按需覆盖，不必强制实现全部接口。
    // 已有覆盖的适配器不受影响（override 优先于默认值）。

    // --- 播放列表 ---
    suspend fun getPlaylists(): List<Playlist> = emptyList()
    suspend fun createPlaylist(name: String): Playlist? = null
    suspend fun deletePlaylist(playlistId: String): Boolean = false
    suspend fun addToPlaylist(playlistId: String, songId: String): Boolean = false
    suspend fun removeFromPlaylist(playlistId: String, songId: String): Boolean = false

    // --- 收藏 ---
    suspend fun toggleFavorite(songId: String): Boolean = false
    suspend fun getFavorites(): List<Song> = emptyList()

    // --- 评分 ---
    suspend fun setRating(songId: String, rating: Int): Boolean = false

    // --- 流派 ---
    suspend fun getGenres(): List<Genre> = emptyList()
    suspend fun getSongsByGenre(genre: String): List<Song> = emptyList()
    suspend fun getSongsByYearRange(fromYear: Int, toYear: Int): List<Song> = emptyList()

    // --- Scrobble ---
    suspend fun scrobblePlay(songId: String, timestamp: Long): Boolean = false

    // --- 随机歌曲 ---
    suspend fun getRandomSongs(limit: Int = 20): List<Song> = emptyList()
}
