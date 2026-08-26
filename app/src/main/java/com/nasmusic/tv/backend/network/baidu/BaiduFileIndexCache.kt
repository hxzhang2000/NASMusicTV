package com.nasmusic.tv.backend.network.baidu

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nasmusic.tv.data.model.BaiduFile
import com.nasmusic.tv.data.model.BaiduFileIndex
import com.nasmusic.tv.data.model.BaiduIndexEntry
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.util.BaiduFilenameParser
import com.nasmusic.tv.util.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 百度网盘本地索引缓存
 *
 * 大曲库（数千文件）每次进 Tab 都等 listall 不可接受。首次扫描后建本地索引，
 * 后续浏览走索引 + 增量更新（对比 server_mtime）。
 *
 * 所有权：实例由 [BaiduNetdiskService] 构造注入持有，[com.nasmusic.tv.data.prefs.AppPreferences]
 * 只存配置（rootDir/enabled 等）不存索引。
 *
 * 存储：JSON 文件 app filesDir/baidu_index.json
 *
 * 主路径：BFS 逐目录 list + 60ms 节流（[BaiduPanApi.listDir]）；listall 单请求方案未验证，
 * 实测通过后可在 [BaiduPanApi.listAllAudio] 启用作为可选加速。
 */
class BaiduFileIndexCache(context: Context) {

    private val file: File = File(context.filesDir, "baidu_index.json")
    private val gson = Gson()

    /** 扫描进度回调 */
    interface ProgressCallback {
        /** @param scanned 已扫描文件数 */
        fun onProgress(scanned: Int)
        /** 扫描完成 */
        fun onComplete(total: Int)
        /** 扫描失败/中断（已扫描部分已保留） */
        fun onFailed(message: String)
    }

    /** 加载索引 */
    fun load(): BaiduFileIndex? {
        return try {
            if (!file.exists()) return null
            val json = file.readText()
            val type = object : TypeToken<BaiduFileIndex>() {}.type
            gson.fromJson<BaiduFileIndex>(json, type)
        } catch (e: Exception) {
            AppLog.w(TAG, "load error", e)
            null
        }
    }

    fun save(index: BaiduFileIndex) {
        try {
            file.writeText(gson.toJson(index))
        } catch (e: Exception) {
            AppLog.w(TAG, "save error", e)
        }
    }

    fun clear() {
        try { if (file.exists()) file.delete() } catch (e: Exception) {
            AppLog.w(TAG, "clear error", e)
        }
    }

    /** 本地搜索（标题或艺术家包含 keyword） */
    fun search(keyword: String, limit: Int = 0): List<Song> {
        val index = load() ?: return emptyList()
        val k = keyword.trim().lowercase()
        if (k.isBlank()) return emptyList()
        val matched = index.entries.asSequence()
            .filter {
                it.title.lowercase().contains(k) ||
                    (it.artist?.lowercase()?.contains(k) == true)
            }
            .let { stream -> if (limit > 0) stream.take(limit) else stream }
            .toList()
        return matched.map { it.toSong() }
    }

    /**
     * 目录感知搜索（发现页专用）：优先按"目录名"匹配，目录命中则返回该目录下全部歌曲。
     *
     * 网盘常按"粤语 / 经典老歌 / 民谣"等目录组织音乐，目录名本身就是标签。
     * 这里先匹配 path 中的目录段；若某目录名包含 keyword，把该目录下所有音频条目都列出；
     * 无目录命中时回退到 [search]（按文件名/歌手匹配）。
     *
     * @param keyword 搜索词（可能是目录名或文件名片段）
     * @return 匹配的歌曲；按目录聚合，同目录歌曲归在一起
     */
    fun searchByDirectory(keyword: String, limit: Int = 0): List<Song> {
        val index = load() ?: return emptyList()
        val k = keyword.trim().lowercase()
        if (k.isBlank()) return emptyList()

        // 找出 path 中目录段包含 keyword 的条目
        val dirHits = index.entries.filter { entry ->
            val dirSegments = entry.path.substringBeforeLast('/')
            dirSegments.lowercase().contains(k)
        }

        if (dirHits.isNotEmpty()) {
            // 命中目录：按"所属目录"分组，返回所有命中目录下的全部条目（去重 fsId）
            val matchedDirs = dirHits.map { it.path.substringBeforeLast('/') }.toSet()
            val result = index.entries
                .filter { entry -> matchedDirs.any { dir -> entry.path.startsWith("$dir/") } }
                .distinctBy { it.fsId }
            val songs = result.map { it.toSong() }
            return if (limit > 0) songs.take(limit) else songs
        }

        // 无目录命中：回退文件名/歌手匹配
        return search(keyword, limit)
    }

    /** 按 fs_id 反查 path（MV 同目录同名匹配用） */
    fun getPathByFsId(fsId: Long?): String? {
        if (fsId == null) return null
        return load()?.entries?.firstOrNull { it.fsId == fsId }?.path
    }

    /**
     * 索引中搜索 MV 视频文件（歌手+歌名匹配，按相关性排序）。
     *
     * 仅搜索 [BaiduIndexEntry.category] == CATEGORY_VIDEO 的条目，
     * 按标题/歌手匹配度降序（精确标题 > 包含标题 > 歌手匹配）。
     *
     * @param artist 歌手名（可空，空时仅按 title 匹配）
     * @param title  歌名（可空，空时仅按 artist 匹配）
     * @param limit  最多返回条数（默认 5）
     * @return 匹配的索引条目列表
     */
    fun searchMv(artist: String, title: String, limit: Int = 5): List<BaiduIndexEntry> {
        val index = load() ?: return emptyList()
        val t = title.trim().lowercase()
        val a = artist.trim().lowercase()
        if (t.isBlank() && a.isBlank()) return emptyList()
        return index.entries.asSequence()
            .filter { it.category == BaiduNetdiskConfig.CATEGORY_VIDEO }
            .filter { entry ->
                val et = entry.title.lowercase()
                val ea = entry.artist?.lowercase() ?: ""
                (t.isBlank() || et.contains(t)) &&
                    (a.isBlank() || ea.contains(a))
            }
            .sortedByDescending { entry ->
                val et = entry.title.lowercase()
                val ea = entry.artist?.lowercase() ?: ""
                // 精确标题 > 精确歌手 > 包含标题 > 包含歌手
                (if (et == t) 10 else 0) +
                    (if (ea == a) 5 else 0) +
                    (if (t.isNotBlank() && et.contains(t)) 3 else 0) +
                    (if (a.isNotBlank() && ea.contains(a)) 2 else 0)
            }
            .take(limit)
            .toList()
    }

    /**
     * 全量扫描建索引（BFS 逐目录 + 60ms 节流）。
     *
     * @param rootPath 音乐根目录
     * @param api BaiduPanApi 实例
     * @param mvDir MV 文件目录（可选。非 null 时额外扫描该目录下的视频文件入索引，供 [searchMv] 使用）
     * @param onProgress 进度回调（可为 null）
     * @return 建好的索引；扫描失败返回已扫描的部分索引
     */
    suspend fun fullScan(
        rootPath: String,
        api: BaiduPanApi,
        mvDir: String? = null,
        onProgress: ProgressCallback? = null
    ): BaiduFileIndex = withContext(Dispatchers.IO) {
        val entries = mutableListOf<BaiduIndexEntry>()
        val visited = HashSet<String>()
        val queue = ArrayDeque<String>()
        queue.addLast(rootPath)
        visited.add(rootPath)
        var scanned = 0

        // ---- Pass 1: BFS 扫描 rootPath 内的音频文件 ----
        try {
            scanDirTree(queue, visited, api, entries, scanned, onProgress)
        } catch (e: Exception) {
            AppLog.e(TAG, "fullScan interrupted, partial saved", e)
            val partial = BaiduFileIndex(rootPath = rootPath, lastSyncAt = System.currentTimeMillis(), entries = entries)
            save(partial)
            onProgress?.onFailed(e.message ?: "扫描中断")
            return@withContext partial
        }

        // ---- Pass 2: 如果 mvDir 存在且不同于 rootPath，扫描该目录下的视频文件 ----
        if (mvDir != null && mvDir != rootPath) {
            try {
                val mvVisited = HashSet<String>()
                val mvQueue = ArrayDeque<String>()
                mvQueue.addLast(mvDir)
                mvVisited.add(mvDir)
                while (mvQueue.isNotEmpty()) {
                    val dir = mvQueue.removeFirst()
                    var start = 0
                    while (true) {
                        val result = api.listDir(dir, start = start, limit = BaiduNetdiskConfig.PAGE_SIZE)
                        for (f in result.files) {
                            if (f.isDir) {
                                if (mvVisited.add(f.path)) mvQueue.addLast(f.path)
                            } else if (f.category == BaiduNetdiskConfig.CATEGORY_VIDEO && !f.isDir) {
                                val (artist, title) = BaiduFilenameParser.parse(f.serverFilename)
                                entries.add(
                                    BaiduIndexEntry(
                                        fsId = f.fsId,
                                        path = f.path,
                                        filename = f.serverFilename,
                                        title = title,
                                        artist = artist.ifBlank { null },
                                        size = f.size,
                                        serverMtime = f.serverMtime,
                                        category = BaiduNetdiskConfig.CATEGORY_VIDEO
                                    )
                                )
                                scanned++
                                if (scanned % 50 == 0) onProgress?.onProgress(scanned)
                            }
                        }
                        if (result.hasMore) {
                            start += BaiduNetdiskConfig.PAGE_SIZE
                        } else {
                            break
                        }
                    }
                    kotlinx.coroutines.delay(60)
                }
            } catch (e: Exception) {
                // MV 目录扫描失败不影响已有音频索引
                AppLog.w(TAG, "fullScan mvDir error: ${e.message}", e)
            }
        }

        val index = BaiduFileIndex(
            rootPath = rootPath,
            lastSyncAt = System.currentTimeMillis(),
            entries = entries.sortedBy { it.title.lowercase() }
        )
        save(index)
        onProgress?.onComplete(entries.size)
        index
    }

    /**
     * BFS 逐目录扫描音频文件（提取为方法供 fullScan 复用）。
     */
    private suspend fun scanDirTree(
        queue: ArrayDeque<String>,
        visited: HashSet<String>,
        api: BaiduPanApi,
        entries: MutableList<BaiduIndexEntry>,
        scanned: Int,
        onProgress: ProgressCallback?
    ) {
        var s = scanned
        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            var start = 0
            while (true) {
                val result = api.listDir(dir, start = start, limit = BaiduNetdiskConfig.PAGE_SIZE)
                for (f in result.files) {
                    if (f.isDir) {
                        if (visited.add(f.path)) queue.addLast(f.path)
                    } else if (BaiduPanApi.isAudioFile(f.serverFilename, f.category)) {
                        val (artist, title) = BaiduFilenameParser.parse(f.serverFilename)
                        entries.add(
                            BaiduIndexEntry(
                                fsId = f.fsId,
                                path = f.path,
                                filename = f.serverFilename,
                                title = title,
                                artist = artist.ifBlank { null },
                                size = f.size,
                                serverMtime = f.serverMtime
                            )
                        )
                        s++
                        if (s % 50 == 0) onProgress?.onProgress(s)
                    }
                }
                if (result.hasMore) {
                    start += BaiduNetdiskConfig.PAGE_SIZE
                } else {
                    break
                }
            }
            kotlinx.coroutines.delay(60)
        }
    }

    /**
     * 增量更新：对比 server_mtime，只更新变化的条目。
     *
     * @param mvDir 传给 [fullScan] 的 MV 目录参数
     */
    suspend fun incrementalUpdate(
        rootPath: String,
        api: BaiduPanApi,
        mvDir: String? = null,
        onProgress: ProgressCallback? = null
    ): BaiduFileIndex = withContext(Dispatchers.IO) {
        val current = load()
        // 增量更新实现简化：直接全量重扫（百度 listall 未验证前不冒险）
        // 后续可优化为对比 server_mtime 的 diff 更新
        fullScan(rootPath, api, mvDir, onProgress)
    }

    companion object {
        private const val TAG = "BaiduFileIndexCache"
    }
}
