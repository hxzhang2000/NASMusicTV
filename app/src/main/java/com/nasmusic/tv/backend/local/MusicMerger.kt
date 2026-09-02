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
     * 合并多源专辑（NAS + 本地 + 百度），按 albumName 去重
     */
    fun mergeAlbums(
        nasAlbums: List<Album>,
        localAlbums: List<Album>,
        baiduAlbums: List<Album> = emptyList()
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

        baiduAlbums.forEach { album ->
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
     * 合并多源艺术家（NAS + 本地 + 百度），按 artistName 去重
     */
    fun mergeArtists(
        nasArtists: List<Artist>,
        localArtists: List<Artist>,
        baiduArtists: List<Artist> = emptyList()
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

        baiduArtists.forEach { artist ->
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

    /**
     * 从百度网盘歌曲列表构建专辑列表
     *
     * 百度网盘 API 不返回 album 字段，只能从 path 目录结构推断：
     * - path = `/音乐/周杰伦/范特西/01.爱在西元前.mp3` → 专辑 = "范特西"
     * - 取 path 倒数第二段目录名作为专辑名
     * - 过滤泛化目录名（"音乐"、"Music"、"收藏"等不太可能是专辑名的词）
     * - 过滤与 artist 同名的目录（如 `/周杰伦/周杰伦/...` 第二层"周杰伦"是 artist 不是专辑）
     * - 通过的目录才认为是专辑；否则该歌曲不归属任何专辑
     */
    fun buildBaiduAlbums(baiduSongs: List<Song>): List<Album> {
        // 泛化目录名黑名单（小写匹配）——这些词不太可能是专辑名
        val genericDirs = setOf(
            "音乐", "music", "歌曲", "songs", "audio", "音频",
            "收藏", "收藏夹", "favorites", "download", "下载",
            "我的音乐", "my music", "全部歌曲", "全部", "all",
            "未分类", "misc", "other", "其他", "tmp", "temp"
        )

        return baiduSongs
            .mapNotNull { song ->
                val segments = song.path?.trim('/')?.split("/") ?: return@mapNotNull null
                // 至少要有 2 层：目录/文件名，否则无法推断专辑
                if (segments.size < 2) return@mapNotNull null
                val dirName = segments.getOrNull(segments.size - 2) ?: return@mapNotNull null
                if (dirName.isBlank()) return@mapNotNull null
                // 过滤泛化目录名
                if (dirName.lowercase().trim() in genericDirs) return@mapNotNull null
                // 过滤与歌手同名的目录（那是艺术家目录，不是专辑）
                if (song.artist.isNotBlank() && dirName.lowercase().trim() == song.artist.lowercase().trim()) return@mapNotNull null
                dirName to song
            }
            .groupBy { (albumName, _) -> albumName.lowercase().trim() }
            .map { (_, entries) ->
                val albumName = entries.first().first
                val songs = entries.map { it.second }
                val first = songs.first()
                Album(
                    id = "baidu_album_${albumName.lowercase().replace(" ", "_")}",
                    name = albumName,
                    artist = first.artist,
                    coverUrl = null,
                    songCount = songs.size,
                    durationMs = songs.sumOf { it.durationMs }
                )
            }
    }

    /**
     * 从百度网盘歌曲列表构建艺术家列表
     *
     * 直接按 Song.artist 分组（百度索引条目已从文件名解析了 artist 字段）。
     */
    fun buildBaiduArtists(baiduSongs: List<Song>): List<Artist> =
        baiduSongs
            .filter { it.artist.isNotBlank() }
            .groupBy { it.artist.lowercase().trim() }
            .map { (_, songs) ->
                val first = songs.first()
                Artist(
                    id = "baidu_artist_${first.artist.lowercase().replace(" ", "_")}",
                    name = first.artist,
                    songCount = songs.size,
                    albumCount = songs.map { it.album }.filter { it.isNotBlank() }.distinct().size
                )
            }
}