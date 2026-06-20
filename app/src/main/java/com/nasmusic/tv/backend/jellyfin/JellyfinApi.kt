package com.nasmusic.tv.backend.jellyfin

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Jellyfin API 接口定义
 */
interface JellyfinApi {

    @GET("System/Info/Public")
    suspend fun getSystemInfo(): Response<JellyfinSystemInfo>

    @GET("Users")
    suspend fun getUsers(
        @Header("X-Emby-Token") token: String
    ): Response<List<JellyfinUser>>

    @GET("Items")
    suspend fun getItems(
        @Header("X-Emby-Token") token: String,
        @Query("UserId") userId: String,
        @Query("IncludeItemTypes") itemTypes: String? = null,
        @Query("ParentId") parentId: String? = null,
        @Query("Recursive") recursive: Boolean = true,
        @Query("SortBy") sortBy: String? = null,
        @Query("SortOrder") sortOrder: String = "Ascending",
        @Query("Limit") limit: Int? = null,
        @Query("StartIndex") startIndex: Int? = null,
        @Query("SearchTerm") searchTerm: String? = null,
        @Query("Fields") fields: String = "PrimaryImageAspectRatio,BasicSyncInfo,CanDelete,MediaSourceCount,Overview"
    ): Response<JellyfinItemResult>

    @GET("Items/{id}")
    suspend fun getItem(
        @Header("X-Emby-Token") token: String,
        @Path("id") id: String,
        @Query("UserId") userId: String
    ): Response<JellyfinItem>

    @GET("Users/{userId}/Items/Latest")
    suspend fun getLatestItems(
        @Header("X-Emby-Token") token: String,
        @Path("userId") userId: String,
        @Query("Limit") limit: Int = 20,
        @Query("Fields") fields: String = "PrimaryImageAspectRatio,BasicSyncInfo"
    ): Response<List<JellyfinItem>>

    companion object {
        const val ITEM_TYPE_AUDIO = "Audio"
        const val ITEM_TYPE_MUSIC_ALBUM = "MusicAlbum"
        const val ITEM_TYPE_MUSIC_ARTIST = "MusicArtist"
    }
}
