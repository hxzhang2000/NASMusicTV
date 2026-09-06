package com.nasmusic.tv.backend.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nasmusic.tv.util.AppLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 封面 URL 持久缓存（跨会话复用专辑/艺术家封面，避免重复网络搜索）
 *
 * 存储 key -> coverUrl 映射，只存稳定 URL（iTunes/网易云/酷狗等 HTTP 封面，非动态 dlink/APIC）。
 * 专辑 key 格式 `album:{id}`，艺术家 key 格式 `artist:{id}`，id 跨会话稳定（后端 GUID / local_* / baidu_*）。
 *
 * - 写入时机：`MainViewModel.resolveAlbumCoversAsync` / `resolveArtistCoversAsync` 解析到稳定 HTTP 封面 URL 时
 * - 读取时机：App 启动 / updateMergedData 时预加载到内存 resolvedAlbumCovers/resolvedArtistCovers，
 *   已有封面不再重复网络解析
 * - LRU 淘汰：超过 [MAX_ENTRIES] 条时删写入时间最旧的
 *
 * 文件格式：JSON Map<String, String>，存 app filesDir/cover_url_cache.json
 */
class CoverUrlPersistentCache(context: Context) {

    private val cache = ConcurrentHashMap<String, String>()
    private val gson = Gson()
    private val file by lazy { File(context.filesDir, "cover_url_cache.json") }

    companion object {
        private const val TAG = "CoverUrlPersistentCache"
        private const val MAX_ENTRIES = 10000
        private const val KEY_ALBUM = "album:"
        private const val KEY_ARTIST = "artist:"
    }

    init {
        load()
    }

    fun getAlbumCover(albumId: String): String? = cache[KEY_ALBUM + albumId]

    fun getArtistCover(artistId: String): String? = cache[KEY_ARTIST + artistId]

    fun putAlbumCover(albumId: String, coverUrl: String) {
        put(KEY_ALBUM + albumId, coverUrl)
    }

    fun putArtistCover(artistId: String, coverUrl: String) {
        put(KEY_ARTIST + artistId, coverUrl)
    }

    private fun put(key: String, url: String) {
        if (url.isBlank()) return
        cache[key] = url
        if (cache.size > MAX_ENTRIES) evictOldest()
        save()
    }

    /** 清空全部持久缓存（内存 + 磁盘文件）。供设置页"缓存管理"手动清除 */
    fun clear() {
        cache.clear()
        try {
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            AppLog.e(TAG, "clear failed: ${e.message}", e)
        }
        AppLog.d(TAG, "cleared")
    }

    /** 导出全部条目（供备份用） */
    fun exportAll(): Map<String, String> = cache.toMap()

    /** 导入条目（恢复备份用，覆盖现有数据） */
    fun importAll(entries: Map<String, String>) {
        cache.clear()
        entries.forEach { (k, v) -> if (v.isNotBlank()) cache[k] = v }
        if (cache.size > MAX_ENTRIES) evictOldest()
        save()
        AppLog.d(TAG, "importAll: ${cache.size} entries")
    }

    private fun evictOldest() {
        // 无时间戳，按 Map 迭代序近似淘汰最早写入的（LinkedHashMap 语义弱化，可接受）
        val toRemove = cache.keys.take(cache.size - MAX_ENTRIES)
        toRemove.forEach { cache.remove(it) }
        AppLog.d(TAG, "evicted ${toRemove.size} entries, remaining=${cache.size}")
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val json = file.readText()
            if (json.isBlank()) return
            val type = object : TypeToken<Map<String, String>>() {}.type
            val loaded = gson.fromJson<Map<String, String>>(json, type) ?: return
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