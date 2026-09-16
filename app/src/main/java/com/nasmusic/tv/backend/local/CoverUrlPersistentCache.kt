package com.nasmusic.tv.backend.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nasmusic.tv.util.AppLog
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 封面 URL 持久缓存（跨会话复用专辑/艺术家封面，避免重复网络搜索）
 *
 * 存储 key -> coverUrl 映射，只存稳定 URL（iTunes/网易云/酷狗等 HTTP 封面，非动态 dlink/APIC）。
 * 专辑 key 格式 `album:{id}`，艺术家 key 格式 `artist:{id}`，id 跨会话稳定（后端 GUID / local_* / baidu_*）。
 *
 * - 写入时机：`MainViewModel.resolveAlbumCoversAsync` / `resolveArtistCoversAsync` 解析到稳定 HTTP 封面 URL 时
 * - 读取时机：App 启动 / updateMergedData 时预加载到内存 resolvedAlbumCovers/resolvedArtistCovers，
 *   已有封面不再重复网络解析
 * - 淘汰：超过 [MAX_ENTRIES] 条时按**写入时间**淘汰最早写入的（写入时间 = 最近一次 put；
 *   不随读取刷新，因此是 FIFO-by-write 而非严格 LRU——详见 [evictOldest]）
 *
 * 文件格式：JSON Map<String, String>，存 app filesDir/cover_url_cache.json
 */
class CoverUrlPersistentCache(context: Context) {

    private val cache = ConcurrentHashMap<String, String>()

    /**
     * P3-3（2026-09-16）：key → 最近一次写入时间（毫秒），仅用于淘汰排序。
     *
     * 原 [evictOldest] 直接在 ConcurrentHashMap 上 `keys.take(n)`——CHM 的迭代序与插入序
     * **无关**，实际等价于随机淘汰，与类注释宣称的"删最早写入的"不符：可能把刚刚解析到的
     * 封面立刻淘汰掉（下次启动又要重新联网解析），而很早的冷条目反而留下。
     */
    private val writtenAt = ConcurrentHashMap<String, Long>()
    private val gson = Gson()
    private val file by lazy { File(context.filesDir, "cover_url_cache.json") }

    @Volatile private var dirty = false
    private val saveScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var saveJob: Job? = null
    private val saveLock = Any()

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
        writtenAt[key] = System.currentTimeMillis()
        if (cache.size > MAX_ENTRIES) evictOldest()
        dirty = true
        scheduleSave()
    }

    /** 延迟落盘（2秒 debounce），避免频繁 put 时每次都写文件 */
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = saveScope.launch {
            delay(2000) // 2秒 debounce
            if (dirty) {
                save()
                dirty = false
            }
        }
    }

    /** 关闭缓存，取消延迟落盘协程并强制写盘。供 Application.onTerminate / onTrimMemory 调用 */
    fun close() {
        saveJob?.cancel()
        if (dirty) {
            save()
            dirty = false
        }
        saveScope.cancel()
    }

    /** 清空全部持久缓存（内存 + 磁盘文件）。供设置页"缓存管理"手动清除 */
    fun clear() {
        saveJob?.cancel()
        dirty = false
        cache.clear()
        writtenAt.clear()
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
        saveJob?.cancel()
        cache.clear()
        writtenAt.clear()
        val now = System.currentTimeMillis()
        entries.forEach { (k, v) ->
            if (v.isNotBlank()) {
                cache[k] = v
                writtenAt[k] = now
            }
        }
        if (cache.size > MAX_ENTRIES) evictOldest()
        save()
        dirty = false
        AppLog.d(TAG, "importAll: ${cache.size} entries")
    }

    /**
     * 超限淘汰：按写入时间从早到晚删，直到回到 [MAX_ENTRIES]。
     *
     * P3-3 修复（2026-09-16）：原实现在 ConcurrentHashMap 上 `keys.take(n)`，
     * 迭代序与插入序无关 → 等价随机淘汰（详见 [writtenAt] 注释）。
     * 只在超限时触发（MAX_ENTRIES = 10000，正常远达不到），排序开销可忽略。
     */
    private fun evictOldest() {
        val overflow = cache.size - MAX_ENTRIES
        if (overflow <= 0) return
        val toRemove = writtenAt.entries
            .sortedBy { it.value }
            .take(overflow)
            .map { it.key }
        toRemove.forEach {
            cache.remove(it)
            writtenAt.remove(it)
        }
        AppLog.d(TAG, "evicted ${toRemove.size} entries, remaining=${cache.size}")
    }

    private fun load() {
        try {
            if (!file.exists()) return
            val json = file.readText()
            if (json.isBlank()) return
            val type = object : TypeToken<Map<String, String>>() {}.type
            val loaded = gson.fromJson<Map<String, String>>(json, type) ?: return
            // P3-3：文件里的顺序就是写入顺序（save() 按 toMap() 稳定写出），
            // 按序赋递增时间戳，使「加载后立即超限淘汰」也按真实新旧顺序淘汰。
            // 注：Map 没有 forEachIndexed，必须走 entries。
            val base = System.currentTimeMillis()
            loaded.entries.forEachIndexed { i, e ->
                cache[e.key] = e.value
                writtenAt[e.key] = base + i
            }
            AppLog.d(TAG, "loaded ${cache.size} entries")
        } catch (e: Exception) {
            AppLog.e(TAG, "load failed: ${e.message}", e)
        }
    }

    private fun save() {
        synchronized(saveLock) {
            try {
                val json = gson.toJson(cache.toMap())
                val tmpFile = File(file.path + ".tmp")
                tmpFile.writeText(json)
                // 原子 rename：避免写文件中途崩溃导致缓存损坏
                if (!tmpFile.renameTo(file)) {
                    // rename 失败时回退为直接写
                    file.writeText(json)
                }
            } catch (e: Exception) {
                AppLog.e(TAG, "save failed: ${e.message}", e)
            }
        }
    }
}