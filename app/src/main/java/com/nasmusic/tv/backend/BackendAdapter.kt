package com.nasmusic.tv.backend

import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Artist
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
     * 初始化连接
     */
    suspend fun initialize(baseUrl: String, apiToken: String, username: String = "", password: String = ""): Boolean

    /**
     * 测试连接是否可用
     */
    suspend fun testConnection(): Boolean

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
     * 获取所有歌曲
     * @param limit 返回的最大歌曲数量，默认 100000（加载全部）
     */
    suspend fun getSongs(limit: Int = 100000): List<Song>

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
     * 获取歌词（如果后端支持）
     */
    suspend fun getLyrics(songId: String): String?
}
