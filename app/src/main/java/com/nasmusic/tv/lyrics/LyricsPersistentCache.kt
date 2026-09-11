package com.nasmusic.tv.lyrics

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nasmusic.tv.data.model.LyricsCacheEntry
import com.nasmusic.tv.util.AppLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 网络歌词持久化缓存（跨会话复用，避免重复请求网络歌词 API）
 *
 * 索引文件 + 独立 LRC 文件，避免单 JSON 体积过大：
 * - `filesDir/lyrics_cache.json` → 索引（仅 metadata，不含 lrcText）
 * - `filesDir/lyrics_cache/{songId}.lrc` → 纯 LRC 文本
 *
 * 写入时机：用户主动切换到网络歌词来源（[MainViewModel.switchLyricsSource]）
 * LRU 淘汰：超过 [MAX_ENTRIES] 条时删 lastPlayedAt 最旧的，同时删除对应的 .lrc 文件
 */
class LyricsPersistentCache(context: Context) {

    /** 索引条目（不包含 LRC 文本，仅用于索引） */
    private data class IndexEntry(
        val songId: String,
        val songTitle: String,
        val songArtist: String,
        val lastPlayedAt: Long = 0L
    )

    private val index = ConcurrentHashMap<String, IndexEntry>()

    /** 命中计数：每 [TOUCH_SAVE_INTERVAL] 次命中落盘一次索引，避免每次 get 都写文件 */
    private val touchCounter = java.util.concurrent.atomic.AtomicInteger()
    private val gson = Gson()
    private val indexFile by lazy { File(context.filesDir, "lyrics_cache.json") }
    private val lrcDir by lazy { File(context.filesDir, "lyrics_cache").apply { mkdirs() } }

    companion object {
        private const val TAG = "LyricsPersistentCache"
        private const val MAX_ENTRIES = 2000
        /** get 命中后累积多少次再落盘一次索引（真 LRU 访问时间刷新） */
        private const val TOUCH_SAVE_INTERVAL = 16
    }

    init {
        loadIndex()
    }

    /** 按 songId 查询缓存，返回完整条目（含 LRC 文本） */
    fun get(songId: String): LyricsCacheEntry? {
        val idx = index[songId] ?: return null
        val lrcFile = lrcFile(songId)
        if (!lrcFile.exists()) {
            // .lrc 文件丢失，清理索引
            index.remove(songId)
            saveIndex()
            return null
        }
        val lrcText = lrcFile.readText()
        // 真 LRU：命中即刷新访问时间。原实现只在 put 时写 lastPlayedAt，
        // 导致「高频读取但从未重写」的旧条目反而先被淘汰，退化为 FIFO。
        val touched = idx.copy(lastPlayedAt = System.currentTimeMillis())
        index[songId] = touched
        maybePersistTouch()
        return LyricsCacheEntry(
            songId = touched.songId,
            songTitle = touched.songTitle,
            songArtist = touched.songArtist,
            lrcText = lrcText,
            lastPlayedAt = touched.lastPlayedAt
        )
    }

    /** 命中若干次后落盘一次索引（内存中的 lastPlayedAt 已即时更新，此处只做持久化节流） */
    private fun maybePersistTouch() {
        if (touchCounter.incrementAndGet() >= TOUCH_SAVE_INTERVAL) {
            touchCounter.set(0)
            saveIndex()
        }
    }

    /**
     * 写入缓存条目（更新 lastPlayedAt 时间戳）。
     * 超过 [MAX_ENTRIES] 时自动淘汰最旧的条目，同时删除对应的 .lrc 文件。
     */
    fun put(entry: LyricsCacheEntry) {
        val now = System.currentTimeMillis()
        // 写 LRC 文件
        val lrcFile = lrcFile(entry.songId)
        lrcFile.writeText(entry.lrcText)
        // 更新索引
        index[entry.songId] = IndexEntry(
            songId = entry.songId,
            songTitle = entry.songTitle,
            songArtist = entry.songArtist,
            lastPlayedAt = now
        )
        if (index.size > MAX_ENTRIES) evictOldest()
        saveIndex()
        AppLog.d(TAG, "put: '${entry.songTitle}' by ${entry.songArtist}, id=${entry.songId}")
    }

    /** 删除某首歌的缓存（索引 + .lrc 文件） */
    fun remove(songId: String) {
        index.remove(songId)
        lrcFile(songId).delete()
        saveIndex()
    }

    /** 清除所有缓存 */
    fun clear() {
        index.clear()
        lrcDir.listFiles()?.forEach { it.delete() }
        indexFile.delete()
        AppLog.d(TAG, "cleared all entries")
    }

    /** 缓存条目数 */
    fun size(): Int = index.size

    /** 导出全部条目（供备份用，从 .lrc 文件读取完整数据） */
    fun exportAll(): List<LyricsCacheEntry> {
        return index.values.mapNotNull { idx ->
            val lrcFile = lrcFile(idx.songId)
            if (!lrcFile.exists()) {
                index.remove(idx.songId)
                null
            } else {
                LyricsCacheEntry(
                    songId = idx.songId,
                    songTitle = idx.songTitle,
                    songArtist = idx.songArtist,
                    lrcText = lrcFile.readText(),
                    lastPlayedAt = idx.lastPlayedAt
                )
            }
        }.also { saveIndex() }
    }

    /** 导入条目（恢复备份用，覆盖现有数据） */
    fun importAll(entries: List<LyricsCacheEntry>) {
        index.clear()
        lrcDir.listFiles()?.forEach { it.delete() }
        entries.forEach { entry ->
            lrcFile(entry.songId).writeText(entry.lrcText)
            index[entry.songId] = IndexEntry(
                songId = entry.songId,
                songTitle = entry.songTitle,
                songArtist = entry.songArtist,
                lastPlayedAt = entry.lastPlayedAt
            )
        }
        if (index.size > MAX_ENTRIES) evictOldest()
        saveIndex()
        AppLog.d(TAG, "importAll: ${index.size} entries")
    }

    /** 淘汰最久未访问的条目，同时删除对应的 .lrc 文件 */
    private fun evictOldest() {
        val sorted = index.entries.sortedBy { it.value.lastPlayedAt }
        val toRemove = sorted.take(index.size - MAX_ENTRIES)
        toRemove.forEach { (songId, _) ->
            lrcFile(songId).delete()
            index.remove(songId)
        }
        AppLog.d(TAG, "evicted ${toRemove.size} entries, remaining=${index.size}")
    }

    /** 加载索引文件 */
    private fun loadIndex() {
        try {
            if (!indexFile.exists()) return
            val json = indexFile.readText()
            if (json.isBlank()) return
            val type = object : TypeToken<Map<String, IndexEntry>>() {}.type
            val loaded = gson.fromJson<Map<String, IndexEntry>>(json, type) ?: return
            index.putAll(loaded)
            AppLog.d(TAG, "loaded ${index.size} entries")
        } catch (e: Exception) {
            AppLog.e(TAG, "loadIndex failed: ${e.message}", e)
        }
    }

    /** F-5：saveIndex 写互斥锁——put/remove/clear/exportAll 各方法并发调用时（切歌与备份
     *  并发），防止全量覆盖写交错（ConcurrentHashMap 只保证内存安全，文件写无互斥） */
    private val saveLock = Any()

    /** 保存索引文件（F-5：synchronized 互斥 + H-5 原子写盘语义保持） */
    private fun saveIndex() {
        synchronized(saveLock) {
            try {
                val json = gson.toJson(index.toMap())
                indexFile.writeText(json)
            } catch (e: Exception) {
                AppLog.e(TAG, "saveIndex failed: ${e.message}", e)
            }
        }
    }

    /** 获取 songId 对应的 .lrc 文件路径 */
    private fun lrcFile(songId: String): File {
        val safeName = songId.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return File(lrcDir, "$safeName.lrc")
    }
}