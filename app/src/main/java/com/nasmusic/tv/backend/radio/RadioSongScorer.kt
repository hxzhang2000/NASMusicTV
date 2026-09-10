package com.nasmusic.tv.backend.radio

import com.nasmusic.tv.data.model.Song
import kotlin.random.Random

/**
 * 智能电台打分器（F2-3）——纯函数，可单测。
 *
 * 打分规则（docs/feature-dev-plan-2026-09.md §3.3）：
 * - 同 artist：+50
 * - 同 genre：+30
 * - 同 album（非当前专辑）：+10
 * - 同年代（year 差 ≤3）：+5
 * - play_counts 高（用户偏好信号）：+ min(count, 20)
 * - 已播历史（excludedIds）：-100（强排除）
 * 分数做权重随机采样。
 */
object RadioSongScorer {

    const val SCORE_SAME_ARTIST = 50
    const val SCORE_SAME_GENRE = 30
    const val SCORE_SAME_ALBUM = 10
    const val SCORE_SAME_ERA = 5
    const val SCORE_EXCLUDED = -100

    /** 单曲得分（零权重保底 1，保证冷门歌也有出场机会） */
    fun score(candidate: Song, seed: Song, playCounts: Map<String, Int> = emptyMap(), excludedIds: Set<String> = emptySet()): Int {
        if (candidate.id == seed.id) return SCORE_EXCLUDED
        if (candidate.id in excludedIds) return SCORE_EXCLUDED

        var s = 0
        if (candidate.artist.isNotBlank() && candidate.artist.equals(seed.artist, ignoreCase = true)) s += SCORE_SAME_ARTIST
        if (!candidate.genre.isNullOrBlank() && candidate.genre.equals(seed.genre, ignoreCase = true)) s += SCORE_SAME_GENRE
        if (candidate.albumId != null && candidate.albumId == seed.albumId) s += SCORE_SAME_ALBUM
        if (candidate.year != null && seed.year != null && kotlin.math.abs(candidate.year - seed.year) <= 3) s += SCORE_SAME_ERA
        s += (playCounts[candidate.id] ?: 0).coerceAtMost(20)
        return s
    }

    /**
     * 批次生成：加权随机采样 [batchSize] 首（分数+1 做权重，负分歌权重 0）。
     * 全部候选被排除时返回空表（调用方降级纯随机）。
     */
    fun generateBatch(
        candidates: List<Song>,
        seed: Song,
        playCounts: Map<String, Int> = emptyMap(),
        excludedIds: Set<String> = emptySet(),
        batchSize: Int = 20,
        random: Random = Random.Default
    ): List<Song> {
        if (candidates.isEmpty() || batchSize <= 0) return emptyList()

        // 预计算权重（分数下限 0；同 seed id / 已排除 = 0 权重）
        val weighted = candidates.map { it to (score(it, seed, playCounts, excludedIds) + 1).coerceAtLeast(0) }
        val pool = weighted.toMutableList()

        val picked = mutableListOf<Song>()
        repeat(batchSize.coerceAtMost(pool.size)) {
            val total = pool.sumOf { it.second }
            if (total <= 0) return picked
            var roll = random.nextInt(total)
            var idx = 0
            while (idx < pool.size) {
                roll -= pool[idx].second
                if (roll < 0) break
                idx++
            }
            if (idx >= pool.size) idx = pool.size - 1
            picked.add(pool[idx].first)
            pool.removeAt(idx)
        }
        return picked
    }
}
