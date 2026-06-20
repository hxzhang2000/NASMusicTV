package com.nasmusic.tv.backend.navidrome

import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Navidrome API 接口定义 (Subsonic API compatible)
 */
interface NavidromeApi {

    @GET("rest/ping.view")
    suspend fun ping(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String = "1.16.1",
        @Query("c") client: String = "NASMusicTV"
    ): Response<SubsonicResponse>

    @GET("rest/getAlbumList2.view")
    suspend fun getAlbumList(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("type") type: String = "alphabeticalByName",
        @Query("size") size: Int = 500,
        @Query("offset") offset: Int = 0,
        @Query("v") version: String = "1.16.1",
        @Query("c") client: String = "NASMusicTV"
    ): Response<SubsonicResponse>

    @GET("rest/getAlbum.view")
    suspend fun getAlbum(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("id") id: String,
        @Query("v") version: String = "1.16.1",
        @Query("c") client: String = "NASMusicTV"
    ): Response<SubsonicResponse>

    @GET("rest/getArtists.view")
    suspend fun getArtists(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String = "1.16.1",
        @Query("c") client: String = "NASMusicTV"
    ): Response<SubsonicResponse>

    @GET("rest/getArtist.view")
    suspend fun getArtist(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("id") id: String,
        @Query("v") version: String = "1.16.1",
        @Query("c") client: String = "NASMusicTV"
    ): Response<SubsonicResponse>

    @GET("rest/getSongsByGenre.view")
    suspend fun getSongsByGenre(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("genre") genre: String,
        @Query("v") version: String = "1.16.1",
        @Query("c") client: String = "NASMusicTV"
    ): Response<SubsonicResponse>

    @GET("rest/search3.view")
    suspend fun search(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("query") query: String,
        @Query("songCount") songCount: Int = 50,
        @Query("v") version: String = "1.16.1",
        @Query("c") client: String = "NASMusicTV"
    ): Response<SubsonicResponse>

    @GET("rest/getStarred2.view")
    suspend fun getStarred(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("v") version: String = "1.16.1",
        @Query("c") client: String = "NASMusicTV"
    ): Response<SubsonicResponse>

    @GET("rest/getLyrics.view")
    suspend fun getLyrics(
        @Query("u") username: String,
        @Query("t") token: String,
        @Query("s") salt: String,
        @Query("artist") artist: String,
        @Query("title") title: String,
        @Query("v") version: String = "1.16.1",
        @Query("c") client: String = "NASMusicTV"
    ): Response<SubsonicResponse>
}
