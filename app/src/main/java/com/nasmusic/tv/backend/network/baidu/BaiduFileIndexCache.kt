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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

    /** 内存缓存：避免每次 allSongs() 都从磁盘反序列化 */
    @Volatile
    private var cachedIndex: BaiduFileIndex? = null
    private val cacheLock = Any()

    /** 目录索引：parentDir → entries，惰性构建，searchByDirectory 用它将 O(N) 扫描降为 O(D) */
    @Volatile
    private var dirIndex: Map<String, List<BaiduIndexEntry>>? = null

    /** 扫描进度回调 */
    interface ProgressCallback {
        /** @param scanned 已扫描文件数 */
        fun onProgress(scanned: Int)
        /** 扫描完成 */
        fun onComplete(total: Int)
        /** 扫描失败/中断（已扫描部分已保留） */
        fun onFailed(message: String)
    }

    /** 加载索引（带内存缓存） */
    fun load(): BaiduFileIndex? {
        cachedIndex?.let { return it }
        return try {
            if (!file.exists()) return null
            val json = file.readText()
            val type = object : TypeToken<BaiduFileIndex>() {}.type
            val index = gson.fromJson<BaiduFileIndex>(json, type)
            synchronized(cacheLock) {
                cachedIndex = index
                dirIndex = null  // 失效目录索引，下次 searchByDirectory 时重建
            }
            index
        } catch (e: Exception) {
            AppLog.w(TAG, "load error", e)
            null
        }
    }

    fun save(index: BaiduFileIndex) {
        try {
            // 修复（H-5）：原子写盘——先写临时文件再 rename 替换。
            // 原实现 writeText 先截断后写，写盘中途被杀会导致索引 JSON 损坏，
            // load() 返回 null 等效全库丢失并触发整盘重扫。
            val tmp = java.io.File(file.parentFile, file.name + ".tmp")
            tmp.writeText(gson.toJson(index))
            if (!tmp.renameTo(file)) {
                // rename 失败（罕见）时退回覆盖写
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            synchronized(cacheLock) {
                cachedIndex = index
                dirIndex = null
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "save error", e)
        }
    }

    /** 读取索引中已持久化的稳定封面 URL（iTunes/网络搜索的 HTTP URL，非动态 dlink/APIC） */
    fun getCoverUrl(fsId: Long): String? {
        val index = load() ?: return null
        return index.entries.firstOrNull { it.fsId == fsId }?.coverUrl
    }

    /**
     * 将解析到的稳定封面 URL 写入索引并落盘（内存 + JSON 文件）。
     * 仅写 HTTP 网络封面；动态 dlink（8h 过期）与 APIC data URI（过大）不落盘。
     * @return 是否写入成功
     */
    fun setCoverUrl(fsId: Long, coverUrl: String): Boolean = synchronized(cacheLock) {
        // 读-改-写必须整体持锁：原实现 load()/save() 各自持锁，并发调用（iTunes 单写路径）
        // 会交错覆盖彼此的更新（lost update）
        val index = load() ?: return@synchronized false
        val idx = index.entries.indexOfFirst { it.fsId == fsId }
        if (idx < 0) return@synchronized false
        val old = index.entries[idx].coverUrl
        if (old == coverUrl) return@synchronized false // 无变化不落盘，避免频繁写文件
        val newEntries = index.entries.toMutableList().apply {
            this[idx] = index.entries[idx].copy(coverUrl = coverUrl)
        }
        save(index.copy(entries = newEntries))
        AppLog.d(TAG, "setCoverUrl fsId=$fsId -> ${coverUrl.take(60)}")
        true
    }

    /**
     * 批量写入封面 URL（单次文件写入）。
     *
     * 用于全量解析完成后一次性持久化所有封面，避免逐条写入时反复序列化整个索引（O(n²)）。
     * 仅更新 coverUrl 有变化的条目。
     *
     * @param updates fsId → coverUrl 映射
     * @return 实际更新的条目数
     */
    fun setCoverUrls(updates: Map<Long, String>): Int {
        if (updates.isEmpty()) return 0
        val index = load() ?: return 0
        val entries = index.entries.toMutableList()
        var changed = 0
        for ((fsId, coverUrl) in updates) {
            val idx = entries.indexOfFirst { it.fsId == fsId }
            if (idx < 0) continue
            val old = entries[idx].coverUrl
            if (old == coverUrl) continue
            entries[idx] = entries[idx].copy(coverUrl = coverUrl)
            changed++
        }
        if (changed > 0) {
            save(index.copy(entries = entries))
            AppLog.d(TAG, "setCoverUrls batch: $changed/${updates.size} entries updated")
        }
        return changed
    }

    fun clear() {
        try { if (file.exists()) file.delete() } catch (e: Exception) {
            AppLog.w(TAG, "clear error", e)
        }
        synchronized(cacheLock) {
            cachedIndex = null
            dirIndex = null  // 修复（M-14c）：清库须同时清目录倒排，否则旧目录搜索结果仍可命中
        }
    }

    /** 本地搜索（标题或艺术家包含 keyword） */
    /**
     * 获取本地索引中的全部歌曲（用于拼音搜索客户端过滤）
     */
    fun allSongs(): List<Song> {
        val index = load() ?: return emptyList()
        return index.entries
            .distinctBy { it.fsId }  // 索引中同一 fsId 可能出现多次（扫描重复），先按 fsId 去重
            .map { it.toSong(coverUrl = it.coverUrl) }
    }

    /**
     * 按艺术家过滤索引条目，只对匹配项创建 Song 对象。
     *
     * 与 [allSongs] + 客户端 filter 的区别：
     * - 不创建全部 N 个 Song 对象（典型 38000+），只创建匹配的 ~50 个
     * - 预计算 normalizeKey(artistName) 一次，不在循环里重复 NFKC 归一化
     * - 直接在 raw entry 上过滤，避免 Song 对象的中间分配
     */
    fun songsByArtist(artistName: String): List<Song> {
        val index = load() ?: return emptyList()
        val artistKey = com.nasmusic.tv.util.ArtistSplitter.normalizeKey(artistName)
        if (artistKey.isBlank()) return emptyList()
        val seen = mutableSetOf<Long>()
        return index.entries
            .filter { seen.add(it.fsId) }  // 按 fsId 去重
            .filter { entry ->
                val rawArtist = entry.artist ?: ""
                if (rawArtist.isBlank()) return@filter false
                com.nasmusic.tv.util.ArtistSplitter.containsArtistWithKey(rawArtist, artistKey)
            }
            .map { it.toSong(coverUrl = it.coverUrl) }
    }

    /**
     * 按专辑名（目录名）过滤索引条目，只对匹配项创建 Song 对象。
     *
     * 匹配逻辑与 [MainViewModel.filterSongsByAlbumName] 一致：
     * - song.album 精确匹配（忽略大小写）
     * - album 字段为空时，取 path 倒数第二段目录名匹配
     *
     * @param albumName 专辑名（原始大小写，内部统一 lowercase 比较）
     */
    fun songsByAlbumName(albumName: String): List<Song> {
        val index = load() ?: return emptyList()
        val name = albumName.lowercase().trim()
        if (name.isBlank()) return emptyList()
        val seen = mutableSetOf<Long>()
        return index.entries
            .filter { seen.add(it.fsId) }
            .filter { entry ->
                // entry 没有 album 字段，专辑名只能从 path 推断
                val path = entry.path.trim('/')
                val segments = path.split("/")
                if (segments.size < 2) return@filter false
                val dirName = segments.getOrNull(segments.size - 2)
                dirName?.lowercase()?.trim() == name
            }
            .map { it.toSong(coverUrl = it.coverUrl) }
    }

    /**
     * 按关键词搜索索引条目（标题/艺术家），只对匹配项创建 Song 对象。
     *
     * 与 [search] 的区别：返回 Song 而非 BaiduIndexEntry，且支持拼音匹配（PinyinUtils）。
     * 用于 SearchAggregator 的拼音搜索场景，避免全量 allSongs() + 客户端 filter。
     *
     * @param keyword 搜索关键词
     * @param pinyinMatch 为 true 时用 PinyinUtils.matches() 做拼音模糊匹配
     * @param limit 最多返回条数（0=不限）
     */
    fun searchSongs(keyword: String, pinyinMatch: Boolean = false, limit: Int = 0): List<Song> {
        val index = load() ?: return emptyList()
        val k = keyword.trim().lowercase()
        if (k.isBlank()) return emptyList()
        val seen = mutableSetOf<Long>()
        return index.entries.asSequence()
            .filter { seen.add(it.fsId) }
            .filter { entry ->
                if (pinyinMatch) {
                    com.nasmusic.tv.util.PinyinUtils.matches(entry.title, k) ||
                        (entry.artist?.let { com.nasmusic.tv.util.PinyinUtils.matches(it, k) } == true)
                } else {
                    entry.title.lowercase().contains(k) ||
                        (entry.artist?.lowercase()?.contains(k) == true)
                }
            }
            .let { stream -> if (limit > 0) stream.take(limit) else stream }
            .toList()
            .map { it.toSong(coverUrl = it.coverUrl) }
    }

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
        return matched.map { it.toSong(coverUrl = it.coverUrl) }
    }

    /**
     * 目录感知搜索（发现页专用）：优先按"目录名"匹配，目录命中则返回该目录下全部歌曲。
     *
     * 网盘常按"粤语 / 经典老歌 / 民谣"等目录组织音乐，目录名本身就是标签。
     * 这里先匹配 path 中的目录段；若某目录名包含 keyword，把该目录下所有音频条目都列出；
     * 无目录命中时回退到 [search]（按文件名/歌手匹配）。
     *
     * 性能：使用预建的 [dirIndex]（parentDir → entries 映射），将目录匹配从 O(N) 降为 O(D)
     * （D = 唯一目录数，典型 100-500，远小于 N=38000+）。目录命中后直接按 key 取值，
     * 不再对全量 entries 做第二轮 O(N×M) 扫描。
     *
     * @param keyword 搜索词（可能是目录名或文件名片段）
     * @return 匹配的歌曲；按目录聚合，同目录歌曲归在一起
     */
    fun searchByDirectory(keyword: String, limit: Int = 0): List<Song> {
        val index = load() ?: return emptyList()
        val k = keyword.trim().lowercase()
        if (k.isBlank()) return emptyList()

        val di = getOrBuildDirIndex()

        // 双向 contains 匹配"直接父目录名"：
        // - 目录名包含关键词（如目录"粤语经典"匹配搜"粤语"）
        // - 关键词包含目录名（如关键词"粤语歌"匹配目录"粤语"——发现页传展开后的关键词）
        // 这样发现页选"粤语"维度时，传来的"粤语歌/粤语歌曲"也能匹配到目录"粤语"
        val matchedDirKeys = di.keys.filter { dirKey ->
            val dir = dirKey.lowercase()
            dir.contains(k) || k.contains(dir)
        }

        if (matchedDirKeys.isNotEmpty()) {
            // 命中目录：直接从目录映射取值，不再全量扫描 entries
            val seen = mutableSetOf<Long>()
            val result = matchedDirKeys.flatMap { dirKey ->
                di[dirKey].orEmpty().filter { seen.add(it.fsId) }
            }
            val songs = result.map { it.toSong(coverUrl = it.coverUrl) }
            return if (limit > 0) songs.take(limit) else songs
        }

        // 无目录命中：回退文件名/歌手匹配
        return search(keyword, limit)
    }

    /**
     * 获取或构建目录索引：parentDir → entries。
     * 惰性构建，首次 [searchByDirectory] 调用时 O(N) 遍历一次，后续调用复用。
     * 索引 [save]/[load] 时自动失效重建。
     */
    private fun getOrBuildDirIndex(): Map<String, List<BaiduIndexEntry>> {
        dirIndex?.let { return it }
        val index = cachedIndex ?: return emptyMap()
        val map = index.entries.groupBy { entry ->
            // parentDir = path 去掉尾部文件名后的目录路径
            entry.path.substringBeforeLast('/')
        }
        synchronized(cacheLock) {
            if (dirIndex == null) dirIndex = map
        }
        return dirIndex!!
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
     * 全量扫描建索引（listall 单次递归获取全部音频 + 缩略图）。
     *
     * 使用 [BaiduPanApi.listAllAudioPaged] 替代 BFS 逐目录扫描：
     * - 1 次分页请求获取全部音频（~5 次 API 调用 vs 数百次 listDir）
     * - 同时返回 thumbs 缩略图 URL，直接作为封面
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
        var scanned = 0

        // ---- Pass 1: listall 递归获取全部音频文件 ----
        try {
            val audioFiles = api.listAllAudioPaged(rootPath) { count ->
                scanned = count
                if (scanned % 100 == 0) onProgress?.onProgress(scanned)
            }
            for (f in audioFiles) {
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
                        category = f.category,
                        coverUrl = f.coverThumb  // listall+web=1 直接返回缩略图
                    )
                )
                scanned++
            }
            onProgress?.onProgress(scanned)
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
     * BFS 逐目录扫描音频文件（保留供未来可能的增量更新使用）。
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

    // ---- APIC 后台提取 ----

    /** APIC 提取进度回调 */
    interface ApicProgressCallback {
        fun onProgress(extracted: Int, total: Int)
        fun onComplete(totalExtracted: Int)
        fun onFailed(message: String)
    }

    /**
     * 后台并发提取 APIC 封面。
     *
     * 扫描完成（fullScan/incrementalUpdate）后调用，遍历索引中 coverUrl 为空的音频条目，
     * 并发提取内嵌 ID3 APIC 帧封面，写入索引。
     *
     * @param coverProvider APIC 提取器
     * @param concurrency 并发数（默认 5，过高可能触发百度限流）
     * @param batchSize 每批写入索引的条数（默认 20）
     * @param onProgress 进度回调（可为 null）
     */
    suspend fun extractApicInBackground(
        coverProvider: BaiduCoverProvider,
        concurrency: Int = 5,
        batchSize: Int = 20,
        onProgress: ApicProgressCallback? = null
    ) = withContext(Dispatchers.IO) {
        val index = load() ?: run {
            onProgress?.onFailed("索引不存在")
            return@withContext
        }
        // 筛选：音频文件（非视频）且 coverUrl 为空
        val pending = index.entries
            .filter { it.coverUrl == null && it.category != BaiduNetdiskConfig.CATEGORY_VIDEO }
        val total = pending.size
        if (total == 0) {
            onProgress?.onComplete(0)
            return@withContext
        }
        AppLog.d(TAG, "extractApicInBackground: $total entries pending")

        var extracted = 0
        val updates = mutableMapOf<Long, String>()
        val lock = Any()

        // 按 concurrency 分批，并发提取
        pending.chunked(concurrency).forEach { chunk ->
            kotlinx.coroutines.coroutineScope {
                val results = chunk.map { entry ->
                    async {
                        try {
                            val coverUrl = coverProvider.extractApicOnly(entry.fsId)
                            if (coverUrl != null) {
                                synchronized(lock) { updates[entry.fsId] = coverUrl }
                            }
                            coverUrl != null
                        } catch (e: Exception) {
                            AppLog.d(TAG, "APIC extract failed for ${entry.filename}: ${e.message}")
                            false
                        }
                    }
                }
                results.awaitAll()
            }
            extracted += chunk.size

            // 每 batchSize 条或最后一批时写入索引
            if (updates.size >= batchSize || extracted == total) {
                synchronized(lock) {
                    if (updates.isNotEmpty()) {
                        setCoverUrls(updates.toMap())
                        updates.clear()
                    }
                }
            }

            onProgress?.onProgress(extracted, total)
        }

        onProgress?.onComplete(total)
        AppLog.d(TAG, "extractApicInBackground done: $total entries processed")
    }
}
