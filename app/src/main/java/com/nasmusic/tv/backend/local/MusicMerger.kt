package com.nasmusic.tv.backend.local

import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.Song

/**
 * 本地音乐与 NAS 数据合并器
 *
 * 同名艺术家 / 专辑合并管理：若 NAS 后端和本地都有同名的艺术家或专辑，
 * 将其合并为一个条目。
 *
 * 说明：Album / Artist 是不含歌曲列表的纯描述类（歌曲在详情页按 id 分别查询），
 * 因此此处合并是"条目去重 + 计数累加"；本地条目用 local_ 前缀 ID 参与合并，
 * 歌曲明细由详情页分别查询 NAS 与本地后合并展示。
 *
 * 去重键：`name.lowercase().trim()`（大小写不敏感、忽略首尾空白）。
 */
object MusicMerger {

    /**
     * 合并 NAS 专辑与本地专辑（按 albumName 去重）
     */
    fun mergeAlbums(
        nasAlbums: List<Album>,
        localAlbums: List<Album>
    ): List<Album> {
        val albumMap = linkedMapOf<String, Album>()

        nasAlbums.forEach { album ->
            val key = album.name.lowercase().trim()
            if (key.isNotBlank()) albumMap[key] = album
        }

        localAlbums.forEach { album ->
            val key = album.name.lowercase().trim()
            if (key.isBlank()) return@forEach
            if (key in albumMap) {
                val existing = albumMap[key]!!
                albumMap[key] = existing.copy(
                    songCount = existing.songCount + album.songCount,
                    durationMs = existing.durationMs + album.durationMs,
                    coverUrl = existing.coverUrl ?: album.coverUrl
                )
            } else {
                albumMap[key] = album
            }
        }

        return albumMap.values.toList()
    }

    /**
     * 合并 NAS 艺术家与本地艺术家（按 artistName 去重）
     */
    fun mergeArtists(
        nasArtists: List<Artist>,
        localArtists: List<Artist>
    ): List<Artist> {
        val artistMap = linkedMapOf<String, Artist>()

        nasArtists.forEach { artist ->
            val key = artist.name.lowercase().trim()
            if (key.isNotBlank()) artistMap[key] = artist
        }

        localArtists.forEach { artist ->
            val key = artist.name.lowercase().trim()
            if (key.isBlank()) return@forEach
            if (key in artistMap) {
                val existing = artistMap[key]!!
                artistMap[key] = existing.copy(
                    songCount = existing.songCount + artist.songCount,
                    albumCount = existing.albumCount + artist.albumCount,
                    coverUrl = existing.coverUrl ?: artist.coverUrl
                )
            } else {
                artistMap[key] = artist
            }
        }

        return artistMap.values.toList()
    }

    /**
     * 从本地歌曲生成本地专辑列表（按 albumName 去重分组）
     */
    fun buildLocalAlbums(localSongs: List<Song>): List<Album> =
        localSongs
            .filter { it.album.isNotBlank() }
            .groupBy { it.album.lowercase().trim() }
            .map { (_, songs) ->
                val first = songs.first()
                Album(
                    id = "local_album_${first.albumId ?: first.id}",
                    name = first.album,
                    artist = first.artist,
                    coverUrl = first.coverUrl,
                    songCount = songs.size,
                    durationMs = songs.sumOf { it.durationMs }
                )
            }

    /**
     * 从本地歌曲生成本地艺术家列表（按 artistName 去重分组）
     */
    fun buildLocalArtists(localSongs: List<Song>): List<Artist> =
        localSongs
            .filter { it.artist.isNotBlank() }
            .groupBy { it.artist.lowercase().trim() }
            .map { (_, songs) ->
                val first = songs.first()
                Artist(
                    id = "local_artist_${first.artist ?: first.id}",
                    name = first.artist,
                    songCount = songs.size,
                    albumCount = songs.map { it.album }.distinct().size
                )
            }
}