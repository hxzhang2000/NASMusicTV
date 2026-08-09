package com.nasmusic.tv.backend.network.mv

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nasmusic.tv.data.model.MvCacheEntry
import com.nasmusic.tv.util.AppLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * MV 持久缓存（跨会话复用 bvid，避免重复搜索）
 *
 * 存储 songId -> MvCacheEntry 映射，只存 bvid（稳定不变）不存直链（过期）。
 * 播放时 [MvSearchManager] 先查此缓存拿到 bvid，再 [MvSearchService.resolveMv] 获取新鲜直链。
 *
 * - MV 播完时调 [markCompleted] 写入/更新条目（playCount++，说明用户认可这个版本）
 * - 用户切换 MV 后播完 -> 覆盖旧 bvid（更新为用户最终认可的版本）
 * - LRU 淘汰：超过 [MAX_ENTRIES] 条时删 lastPlayedAt 最旧的
 *
 * 文件格式：JSON Map<songId, MvCacheEntry>，存 app filesDir/mv_cache.json
 */
class MvPersistentCache(context: Context) {

    private val cache = ConcurrentHashMap<String, MvCacheEntry>()
    private val gson = Gson()
    private val file by lazy { File(context.filesDir, "mv_cache.json") }

    companion object {
        private const val TAG = "MvPersistentCache"
        private const val MAX_ENTRIES = 500
    }

    init {
        load()
    }

    fun get(songId: String): MvCacheEntry? = cache[songId]

    fun put(entry: MvCacheEntry) {
        cache[entry.songId] = entry
        if (cache.size > MAX_ENTRIES) evictOldest()
        save()
    }

    /**
     * 标记某首歌的某支 MV 播放完成（用户认可这个版本）。
     * 已有条目则 playCount++ + 更新 bvid/lastPlayedAt；无则新建。
     */
    fun markCompleted(songId: String, songTitle: String, songArtist: String, bvid: String, mvTitle: String) {
        val existing = cache[songId]
        val entry = MvCacheEntry(
            songId = songId,
            songTitle = songTitle,
            songArtist = songArtist,
            bvid = bvid,
            mvTitle = mvTitle,
            lastPlayedAt = System.currentTimeMillis(),
            playCount = (existing?.playCount ?: 0) + 1
        )
        cache[songId] = entry
        if (cache.size > MAX_ENTRIES) evictOldest()
        save()
        AppLog.d(TAG, "markCompleted: '$songTitle' -> bvid=$bvid playCount=${entry.playCount}")
    }

    fun remove(songId: String) {
        cache.remove(songId)
        save()
    }

    private fun evictOldest() {
        val sorted = cache.entries.sortedBy { it.value.lastPlayedAt }
        val toRemove = sorted.take(cache.size - MAX_ENTRIES)
        toRemove.forEach { cache.remove(it.key) }
        AppLog.d(TAG, "evicted ${toRemove.size} entries, remaining=${cache.size}")
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val json = file.readText()
            if (json.isBlank()) return
            val type = object : TypeToken<Map<String, MvCacheEntry>>() {}.type
            val loaded = gson.fromJson<Map<String, MvCacheEntry>>(json, type) ?: return
            cache.putAll(loaded)
            AppLog.d(TAG, "loaded ${cache.size} entries")
        } catch (e: Exception) {
            AppLog.e(TAG, "load failed: ${e.message}", e)
        }
    }

    private fun save() {
        try {
            val json = gson.toJson(cache.toMap())
            file.writeText(json)
        } catch (e: Exception) {
            AppLog.e(TAG, "save failed: ${e.message}", e)
        }
    }
}
