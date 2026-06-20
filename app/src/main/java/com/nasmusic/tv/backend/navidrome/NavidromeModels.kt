package com.nasmusic.tv.backend.navidrome

import com.google.gson.annotations.SerializedName

/**
 * Subsonic/Navidrome API 数据模型
 */

data class SubsonicResponse(
    @SerializedName("subsonic-response") val subsonicResponse: SubsonicInnerResponse? = null
)

data class SubsonicInnerResponse(
    @SerializedName("status") val status: String,
    @SerializedName("version") val version: String? = null,
    @SerializedName("error") val error: SubsonicError? = null,
    @SerializedName("albumList2") val albumList: AlbumList? = null,
    @SerializedName("album") val album: AlbumDetail? = null,
    @SerializedName("artists") val artists: ArtistsIndex? = null,
    @SerializedName("artist") val artist: ArtistDetail? = null,
    @SerializedName("searchResult3") val searchResult: SearchResult? = null,
    @SerializedName("lyrics") val lyrics: LyricsData? = null,
    @SerializedName("starred2") val starred: StarredData? = null,
    @SerializedName("randomSongs") val randomSongs: RandomSongs? = null
)

data class SubsonicError(
    @SerializedName("code") val code: Int,
    @SerializedName("message") val message: String
)

data class AlbumList(
    @SerializedName("album") val albums: List<SubsonicAlbum>? = null
)

data class SubsonicAlbum(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("artist") val artist: String? = null,
    @SerializedName("artistId") val artistId: String? = null,
    @SerializedName("coverArt") val coverArt: String? = null,
    @SerializedName("songCount") val songCount: Int? = null,
    @SerializedName("duration") val duration: Int? = null,
    @SerializedName("year") val year: Int? = null,
    @SerializedName("genre") val genre: String? = null
)

data class AlbumDetail(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("artist") val artist: String? = null,
    @SerializedName("artistId") val artistId: String? = null,
    @SerializedName("coverArt") val coverArt: String? = null,
    @SerializedName("song") val songs: List<SubsonicSong>? = null,
    @SerializedName("songCount") val songCount: Int? = null,
    @SerializedName("duration") val duration: Int? = null,
    @SerializedName("year") val year: Int? = null
)

data class SubsonicSong(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("album") val album: String? = null,
    @SerializedName("albumId") val albumId: String? = null,
    @SerializedName("artist") val artist: String? = null,
    @SerializedName("artistId") val artistId: String? = null,
    @SerializedName("track") val track: Int? = null,
    @SerializedName("year") val year: Int? = null,
    @SerializedName("genre") val genre: String? = null,
    @SerializedName("coverArt") val coverArt: String? = null,
    @SerializedName("duration") val duration: Int? = null,
    @SerializedName("bitRate") val bitRate: Int? = null,
    @SerializedName("suffix") val suffix: String? = null,
    @SerializedName("contentType") val contentType: String? = null,
    @SerializedName("path") val path: String? = null
)

data class ArtistsIndex(
    @SerializedName("index") val indexes: List<ArtistIndex>? = null
)

data class ArtistIndex(
    @SerializedName("name") val name: String,
    @SerializedName("artist") val artists: List<SubsonicArtist>? = null
)

data class SubsonicArtist(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("coverArt") val coverArt: String? = null,
    @SerializedName("albumCount") val albumCount: Int? = null
)

data class ArtistDetail(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    @SerializedName("coverArt") val coverArt: String? = null,
    @SerializedName("albumCount") val albumCount: Int? = null,
    @SerializedName("album") val albums: List<SubsonicAlbum>? = null
)

data class SearchResult(
    @SerializedName("song") val songs: List<SubsonicSong>? = null,
    @SerializedName("album") val albums: List<SubsonicAlbum>? = null,
    @SerializedName("artist") val artists: List<SubsonicArtist>? = null
)

data class LyricsData(
    @SerializedName("artist") val artist: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("value") val value: String? = null
)

data class StarredData(
    @SerializedName("song") val songs: List<SubsonicSong>? = null,
    @SerializedName("album") val albums: List<SubsonicAlbum>? = null,
    @SerializedName("artist") val artists: List<SubsonicArtist>? = null
)

data class RandomSongs(
    @SerializedName("song") val songs: List<SubsonicSong>? = null
)
