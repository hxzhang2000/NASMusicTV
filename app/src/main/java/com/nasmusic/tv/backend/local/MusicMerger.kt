package com.nasmusic.tv.backend.local

import com.nasmusic.tv.data.model.Album
import com.nasmusic.tv.data.model.Artist
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.ArtistSplitter
import com.nasmusic.tv.util.PinyinUtils

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
 * 去重键：`ArtistSplitter.normalizeKey(name)`（NFKC 全角转半角 + 忽略首尾空白 +
 * 折叠内部空白 + 大小写不敏感）。
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
        // 去重键 -> 来源 id 列表：保留合并前各源的原始 id，详情页据此多源分别取数
        val sourceIdsMap = linkedMapOf<String, MutableList<String>>()

        fun putSource(key: String, id: String) {
            sourceIdsMap.getOrPut(key) { mutableListOf() }.apply {
                if (id !in this) add(id)
            }
        }

        fun mergeInto(key: String, album: Album) {
            val existing = albumMap[key]
            if (existing == null) {
                albumMap[key] = album
            } else {
                albumMap[key] = existing.copy(
                    songCount = existing.songCount + album.songCount,
                    durationMs = existing.durationMs + album.durationMs,
                    coverUrl = existing.coverUrl ?: album.coverUrl
                )
            }
            putSource(key, album.id)
        }

        nasAlbums.forEach { album ->
            val key = album.name.lowercase().trim()
            if (key.isNotBlank()) mergeInto(key, album)
        }
        localAlbums.forEach { album ->
            val key = album.name.lowercase().trim()
            if (key.isNotBlank()) mergeInto(key, album)
        }
        baiduAlbums.forEach { album ->
            val key = album.name.lowercase().trim()
            if (key.isNotBlank()) mergeInto(key, album)
        }

        // 回填 sourceIds：让每个合并条目携带全部来源 id（含 NAS / 本地 / 百度），
        // 详情页 loadAlbumSongs 据此分别取数再拼接，从根上解决"合并后只取 NAS 歌"的丢歌问题
        val merged = albumMap.map { (key, album) ->
            album.copy(sourceIds = sourceIdsMap[key].orEmpty())
        }
        return sortByPinyin(merged) { it.name }
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
            val key = ArtistSplitter.normalizeKey(artist.name)
            if (key.isNotBlank()) artistMap[key] = artist
        }

        localArtists.forEach { artist ->
            val key = ArtistSplitter.normalizeKey(artist.name)
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
            val key = ArtistSplitter.normalizeKey(artist.name)
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

        return sortByPinyin(artistMap.values.toList()) { it.name }
    }

    /**
     * 从本地歌曲生成本地专辑列表（按 albumName 去重分组）
     *
     * 封面优先级：专辑内第一首有 coverUrl 的歌曲 → null（留给上层异步解析）
     */
    fun buildLocalAlbums(localSongs: List<Song>): List<Album> =
        localSongs
            .filter { it.album.isNotBlank() }
            .groupBy { it.album.lowercase().trim() }
            .map { (key, songs) ->
                val first = songs.first()
                Album(
                    id = "local_album_$key",
                    name = first.album,
                    artist = first.artist,
                    coverUrl = songs.firstOrNull { it.coverUrl != null }?.coverUrl,
                    songCount = songs.size,
                    durationMs = songs.sumOf { it.durationMs }
                )
            }

    /**
     * 从本地歌曲生成本地艺术家列表（按 artistName 去重分组）
     *
     * 合唱艺术家拆分：用 ArtistSplitter 将 "张三/李四" 拆为 "张三"、"李四"，
     * 合唱歌曲在每个拆分后的艺术家下都列出（songCount 累加）。
     */
    fun buildLocalArtists(localSongs: List<Song>): List<Artist> =
        buildArtistsFromSongs(localSongs, "local_artist_")

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
                    coverUrl = songs.firstOrNull { it.coverUrl != null }?.coverUrl,
                    songCount = songs.size,
                    durationMs = songs.sumOf { it.durationMs }
                )
            }
    }

    /**
     * 从百度网盘歌曲列表构建艺术家列表
     *
     * 合唱艺术家拆分：用 ArtistSplitter 将 "张三/李四" 拆为 "张三"、"李四"，
     * 合唱歌曲在每个拆分后的艺术家下都列出。
     */
    fun buildBaiduArtists(baiduSongs: List<Song>): List<Artist> =
        buildArtistsFromSongs(baiduSongs, "baidu_artist_")

    /**
     * 从任意歌曲列表构建艺术家列表（按拆分后的艺术家名去重分组）。
     *
     * 合唱艺术家拆分：用 ArtistSplitter 将 "张三/李四" 拆为 "张三"、"李四"，
     * 合唱歌曲在每个拆分后的艺术家下都列出（songCount 累加），
     * 因此搜索结果、本地歌曲、百度歌曲都不会再出现 "张三/李四" 这样的合唱块。
     *
     * 去重键为 [ArtistSplitter.normalizeKey]（NFKC + trim + 折叠空白 + 小写），
     * 保证 "古天乐" 与 "古天乐 " 不会被当成两个艺术家。
     *
     * @param songs 歌曲列表（NAS 全量 / 本地设备 / 百度网盘 / 多源搜索结果均可）
     * @param idPrefix 生成 Artist.id 的前缀，便于区分来源
     */
    fun buildArtistsFromSongs(songs: List<Song>, idPrefix: String): List<Artist> {
        // key = 归一化艺术家名，value = (展示名, 歌曲列表)
        val artistMap = linkedMapOf<String, Pair<String, MutableList<Song>>>()
        for (song in songs) {
            if (song.artist.isBlank()) continue
            for (name in ArtistSplitter.split(song.artist)) {
                val key = ArtistSplitter.normalizeKey(name)
                if (key.isBlank()) continue
                val pair = artistMap.getOrPut(key) { name to mutableListOf() }
                pair.second.add(song)
            }
        }
        return artistMap.map { (key, pair) ->
            val (displayName, artistSongs) = pair
            Artist(
                id = "$idPrefix$key",
                name = displayName,
                songCount = artistSongs.size,
                albumCount = artistSongs.map { it.album }.filter { it.isNotBlank() }.distinct().size
            )
        }
    }

    /**
     * 双键拼音排序：主键 groupLetter（A→Z, #排最后），副键 fullPinyin（同组内全拼排序）
     *
     * # 组使用 "{" 作为 sort key（ASCII 码在 'Z' 之后），保证 # 排在所有字母组之后。
     */
    private fun <T> sortByPinyin(items: List<T>, nameSelector: (T) -> String): List<T> {
        return items.sortedWith(compareBy(
            { val letter = PinyinUtils.getGroupLetter(nameSelector(it)); if (letter == '#') '{' else letter },
            { PinyinUtils.toPinyin(nameSelector(it)) }
        ))
    }
}