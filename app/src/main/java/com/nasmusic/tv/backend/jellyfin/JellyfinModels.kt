package com.nasmusic.tv.backend.jellyfin

import com.google.gson.annotations.SerializedName

/**
 * Jellyfin API 数据模型
 */

data class JellyfinSystemInfo(
    @SerializedName("Id") val id: String? = null,
    @SerializedName("ServerName") val serverName: String? = null,
    @SerializedName("Version") val version: String? = null,
    @SerializedName("ProductName") val productName: String? = null
)

data class JellyfinUser(
    @SerializedName("Id") val id: String,
    @SerializedName("Name") val name: String
)

data class JellyfinItemResult(
    @SerializedName("Items") val items: List<JellyfinItem>? = null,
    @SerializedName("TotalRecordCount") val totalRecordCount: Int = 0
)

data class JellyfinItem(
    @SerializedName("Id") val id: String,
    @SerializedName("Name") val name: String,
    @SerializedName("Type") val type: String,
    @SerializedName("Album") val album: String? = null,
    @SerializedName("AlbumId") val albumId: String? = null,
    @SerializedName("AlbumArtist") val albumArtist: String? = null,
    @SerializedName("AlbumArtists") val albumArtists: List<JellyfinNameIdPair>? = null,
    @SerializedName("Artists") val artists: List<String>? = null,
    @SerializedName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerializedName("IndexNumber") val indexNumber: Int? = null,
    @SerializedName("ProductionYear") val productionYear: Int? = null,
    @SerializedName("Genres") val genres: List<String>? = null,
    @SerializedName("ParentId") val parentId: String? = null,
    @SerializedName("PrimaryImageTag") val primaryImageTag: String? = null,
    @SerializedName("BackdropImageTags") val backdropImageTags: List<String>? = null,
    @SerializedName("Overview") val overview: String? = null,
    @SerializedName("MediaSources") val mediaSources: List<JellyfinMediaSource>? = null,
    @SerializedName("MediaStreams") val mediaStreams: List<JellyfinMediaStream>? = null,
    @SerializedName("ChildCount") val childCount: Int? = null
)

data class JellyfinNameIdPair(
    @SerializedName("Name") val name: String,
    @SerializedName("Id") val id: String
)

data class JellyfinMediaSource(
    @SerializedName("Id") val id: String,
    @SerializedName("Path") val path: String? = null,
    @SerializedName("Protocol") val protocol: String? = null
)

data class JellyfinMediaStream(
    @SerializedName("Type") val type: String,
    @SerializedName("Codec") val codec: String? = null,
    @SerializedName("Language") val language: String? = null,
    @SerializedName("Title") val title: String? = null
)
