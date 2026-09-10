package com.nasmusic.tv.data.stats

import com.nasmusic.tv.data.model.Song

/**
 * 播放统计聚合结果模型（F2-1）
 */
data class ArtistStat(
    val artist: String,
    val playCount: Int,
    val songCount: Int,
    val coverUrl: String? = null
)

data class GenreStat(
    val genre: String,
    val playCount: Int,
    val songCount: Int
)

data class StatsBundle(
    val month: String,
    val totalPlays: Int,
    val distinctSongs: Int,
    val topArtists: List<ArtistStat>,
    val genreDistribution: List<GenreStat>
)

/**
 * 播放统计聚合器（F2-1）——纯函数，可单测。
 *
 * 输入：月度 songId 次数表 + Song 元数据来源（recentSongObjects / PlayRecord 历史等）。
 * 聚合策略（见 docs/feature-dev-plan-2026-09.md §1.6）：
 * - artist 按精确字符串匹配归并（GBK mojibake 变体不强行归并，设计取舍）
 * - genre 为 null/blank 归入"未分类"
 * - 不区分大小写的歌手名归并（"Jay Chou" == "jay chou"）
 */
object PlayStatsAggregator {

    private const val UNTAGGED_GENRE = "未分类"

    fun aggregate(
        month: String,
        playCounts: Map<String, Int>,
        songMeta: List<Song>,
        topArtistLimit: Int = 10,
        genreLimit: Int = 8
    ): StatsBundle {
        val metaById = songMeta.associateBy { it.id }
        var totalPlays = 0

        // artist 归并（小写 key）
        data class ArtistAcc(var plays: Int, var songs: MutableSet<String>, var cover: String?)
        val artistAcc = mutableMapOf<String, ArtistAcc>()
        // genre 归并
        data class GenreAcc(var plays: Int, var songs: MutableSet<String>)
        val genreAcc = mutableMapOf<String, GenreAcc>()
        val countedSongIds = mutableSetOf<String>()

        for ((songId, count) in playCounts) {
            totalPlays += count
            val song = metaById[songId] ?: continue
            countedSongIds.add(songId)

            val artistKey = song.artist.trim().lowercase().ifBlank { "未知歌手" }
            artistAcc.getOrPut(artistKey) { ArtistAcc(0, mutableSetOf(), song.coverUrl) }.apply {
                plays += count
                songs.add(songId)
            }

            val genreKey = song.genre?.trim()?.ifBlank { null } ?: UNTAGGED_GENRE
            genreAcc.getOrPut(genreKey) { GenreAcc(0, mutableSetOf()) }.apply {
                plays += count
                songs.add(songId)
            }
        }

        val topArtists = artistAcc.entries
            .sortedWith(compareByDescending<MutableMap.MutableEntry<String, ArtistAcc>> { it.value.plays }.thenBy { it.key })
            .take(topArtistLimit)
            .map { (_, acc) ->
                ArtistStat(
                    artist = restoreArtistName(songMeta, acc.songs),
                    playCount = acc.plays,
                    songCount = acc.songs.size,
                    coverUrl = acc.cover
                )
            }

        val genres = genreAcc.entries
            .sortedWith(compareByDescending<MutableMap.MutableEntry<String, GenreAcc>> { it.value.plays }.thenBy { it.key })
            .take(genreLimit)
            .map { (key, acc) -> GenreStat(genre = key, playCount = acc.plays, songCount = acc.songs.size) }

        return StatsBundle(
            month = month,
            totalPlays = totalPlays,
            distinctSongs = countedSongIds.size,
            topArtists = topArtists,
            genreDistribution = genres
        )
    }

    /** 从命中的歌曲里恢复原始大小写的歌手名（取播放量最高的那首的歌名展示口径一致） */
    private fun restoreArtistName(songMeta: List<Song>, songIds: Set<String>): String =
        songMeta.firstOrNull { it.id in songIds }?.artist?.trim() ?: "未知歌手"
}
