package com.nasmusic.tv.backend

import com.nasmusic.tv.backend.local.LocalMusicRepository
import com.nasmusic.tv.backend.network.JamendoService
import com.nasmusic.tv.backend.network.NetworkMusicManager
import com.nasmusic.tv.backend.network.baidu.BaiduNetdiskService
import com.nasmusic.tv.data.model.MusicSourceType
import com.nasmusic.tv.data.model.RankedSong
import com.nasmusic.tv.data.model.SearchAggregatorResult
import com.nasmusic.tv.data.model.Song
import com.nasmusic.tv.data.model.SongWithPinyin
import com.nasmusic.tv.util.AppLog
import com.nasmusic.tv.util.PinyinMatcher
import com.nasmusic.tv.util.PinyinUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 搜索结果过滤模式
 *
 * - [PRECISE]：精细过滤——只保留标题/歌手/文件名包含关键词的歌曲（搜索页用）
 * - [NONE]：不过滤——各源返回什么就展示什么，仅做同名同歌手去重（发现页用）
 */
enum class FilterMode {
    PRECISE,
    NONE
}

/**
 * 搜索维度类型——决定搜索匹配的字段范围
 *
 * - [SONG_NAME_OR_ARTIST]：歌名 OR 艺术家（搜索页用，最宽）
 * - [ALBUM]：专辑名 OR 专辑艺术家（专辑页用）
 * - [ARTIST]：艺术家名（艺术家页用）
 * - [SONG_NAME_ONLY]：仅歌名（歌曲页用，不含艺术家）
 */
enum class SearchType {
    SONG_NAME_OR_ARTIST,
    ALBUM,
    ARTIST,
    SONG_NAME_ONLY
}

/**
 * 跨源搜索聚合器
 *
 * 并行搜索 NAS、网络音乐、百度网盘、Jamendo、本地音乐五个数据源，
 * 合并去重后返回统一结果。单个源超时或异常不影响其他源。
 *
     * 超时策略：NAS 15s、网络音乐 5s、百度网盘 8s、Jamendo 5s、本地 2s
     */
    class SearchAggregator(
        private val backendRegistry: BackendRegistry?,
        private val networkMusicManager: NetworkMusicManager?,
        private val baiduService: BaiduNetdiskService?,
        private val jamendoService: JamendoService?,
        private val localMusicRepository: LocalMusicRepository? = null,
        private val isTVDevice: Boolean = false
    ) {
        companion object {
            private const val TAG = "SearchAggregator"
            private const val NAS_TIMEOUT = 15_000L
            private const val NETWORK_TIMEOUT = 5_000L
            private const val BAIDU_TIMEOUT = 8_000L
            private const val JAMENDO_TIMEOUT = 5_000L
            private const val LOCAL_TIMEOUT = 2_000L
    }

    /**
     * 并行搜索所有选定源，合并去重后返回结果
     *
     * @param keyword 搜索关键词（给网络/NAS/Jamendo 用）
     * @param sources 要搜索的源集合（默认全部）
     * @param directoryMode 百度源是否用"目录感知"搜索（发现页用 true，搜索页用 false）
     * @param baiduKeyword 百度源专用关键词（null 时用 keyword）。
     *                      发现页传维度标签"粤语"，搜索页传 null（用用户输入）
     * @param filterMode 结果过滤模式：PRECISE=精细过滤（搜索页），NONE=不过滤（发现页）
     * @param searchType 搜索维度：SONG_NAME_OR_ARTIST=歌名+艺术家，ALBUM=专辑，ARTIST=艺术家，SONG_NAME_ONLY=仅歌名
     * @param nasLocalSongs 已加载的 NAS 歌曲本地缓存（搜索页传入，用于拼音搜索时客户端过滤）
     * @param localDeviceSongs 已加载的本地音乐歌曲缓存（搜索页传入，用于拼音搜索时客户端过滤）
     * @param baiduLocalSongs 百度网盘本地索引歌曲（搜索页传入，用于拼音搜索时客户端过滤）
     * @return 聚合搜索结果
     */
    suspend fun search(
        keyword: String,
        sources: Set<MusicSourceType> = MusicSourceType.entries.toSet(),
        directoryMode: Boolean = false,
        baiduKeyword: String? = null,
        filterMode: FilterMode = FilterMode.NONE,
        searchType: SearchType = SearchType.SONG_NAME_OR_ARTIST,
        nasLocalSongs: List<Song> = emptyList(),
        localDeviceSongs: List<Song> = emptyList(),
        baiduLocalSongs: List<Song> = emptyList()
    ): SearchAggregatorResult = coroutineScope {
        val baiduKw = baiduKeyword ?: keyword
        AppLog.i(TAG, "search: keyword='$keyword' baiduKeyword='$baiduKw' sources=${sources.map { it.name }} directoryMode=$directoryMode filterMode=$filterMode")

        if (keyword.isBlank()) {
            return@coroutineScope SearchAggregatorResult(
                allResults = emptyList(),
                sourceBreakdown = emptyMap()
            )
        }

        // 检测关键词是否是纯拼音（仅含英文字母/数字/空格，无中文字符）
        // 纯拼音关键词发给 NAS/百度/本地源的服务端搜索无意义，改用本地缓存客户端过滤
        val isPinyinQuery = keyword.all { it.isLetterOrDigit() || it.isWhitespace() } &&
                keyword.any { it.isLetter() } &&
                keyword.none { it.code in 0x4E00..0x9FFF }

        // 为每个源创建独立协程，并行搜索
        val nasDeferred = async {
            // 实时从 registry 获取当前 adapter：SearchAggregator 在 onCreate 时创建，
            // 此时 NAS 可能未连接（adapter=null）；用户后续连接后需实时拿到新 adapter
            val backendAdapter = backendRegistry?.getAdapter()
            if (MusicSourceType.NAS in sources && backendAdapter != null) {
                try {
                    if (isPinyinQuery && nasLocalSongs.isNotEmpty() && isTVDevice) {
                        // 拼音搜索：NAS 服务端不认拼音，改用本地缓存 + 客户端拼音匹配
                        nasLocalSongs.filter { song ->
                            matchesBySearchType(song, keyword, searchType)
                        }.map { song ->
                            RankedSong(song = song, source = MusicSourceType.NAS)
                        }
                    } else {
                        // 中文/混合关键词：走服务端搜索
                        withTimeoutOrNull(NAS_TIMEOUT) {
                            backendAdapter.searchSongs(keyword)
                                .filter { it.title.isNotBlank() }
                                .map { song ->
                                    RankedSong(song = song, source = MusicSourceType.NAS)
                                }
                        } ?: run {
                            AppLog.w(TAG, "NAS search timed out")
                            emptyList()
                        }
                    }
                } catch (e: Exception) {
                    AppLog.e(TAG, "NAS search failed: ${e.message}", e)
                    emptyList()
                }
            } else emptyList()
        }

        val networkDeferred = async {
            if (MusicSourceType.NETWORK_MUSIC in sources && networkMusicManager != null) {
                try {
                    withTimeoutOrNull(NETWORK_TIMEOUT) {
                        networkMusicManager.search(keyword)
                            .filter { it.title.isNotBlank() }
                            .map { song ->
                                RankedSong(song = song, source = MusicSourceType.NETWORK_MUSIC)
                            }
                    } ?: run {
                        AppLog.w(TAG, "Network search timed out")
                        emptyList()
                    }
                } catch (e: Exception) {
                    AppLog.e(TAG, "Network search failed: ${e.message}", e)
                    emptyList()
                }
            } else emptyList()
        }

        val baiduDeferred = async {
            if (MusicSourceType.BAIDU_PAN in sources && baiduService != null) {
                try {
                    if (isPinyinQuery && baiduLocalSongs.isNotEmpty() && isTVDevice) {
                        // 拼音搜索：百度网盘服务端不认拼音，改用本地索引缓存 + 客户端拼音匹配
                        baiduLocalSongs.filter { song ->
                            matchesBySearchType(song, keyword, searchType)
                        }.map { song ->
                            RankedSong(song = song, source = MusicSourceType.BAIDU_PAN)
                        }
                    } else if (isPinyinQuery && isTVDevice) {
                        // 拼音搜索但无本地索引缓存，返回空（用户搜中文时百度结果正常返回）
                        emptyList()
                    } else {
                        withTimeoutOrNull(BAIDU_TIMEOUT) {
                            val baiduSongs = if (directoryMode) {
                                baiduService.searchByDirectory(baiduKw)
                            } else {
                                baiduService.search(baiduKw)
                            }
                            baiduSongs
                                .filter { it.title.isNotBlank() }
                                .map { song ->
                                    RankedSong(song = song, source = MusicSourceType.BAIDU_PAN)
                                }
                        } ?: run {
                            AppLog.w(TAG, "Baidu search timed out")
                            emptyList()
                        }
                    }
                } catch (e: Exception) {
                    AppLog.e(TAG, "Baidu search failed: ${e.message}", e)
                    emptyList()
                }
            } else emptyList()
        }

        val jamendoDeferred = async {
            if (MusicSourceType.JAMENDO in sources && jamendoService != null) {
                try {
                    withTimeoutOrNull(JAMENDO_TIMEOUT) {
                        jamendoService.search(keyword)
                            .filter { it.title.isNotBlank() }
                            .map { song ->
                                RankedSong(song = song, source = MusicSourceType.JAMENDO)
                            }
                    } ?: run {
                        AppLog.w(TAG, "Jamendo search timed out")
                        emptyList()
                    }
                } catch (e: Exception) {
                    AppLog.e(TAG, "Jamendo search failed: ${e.message}", e)
                    emptyList()
                }
            } else emptyList()
        }

        // 本地音乐搜索
        val localDeferred = async {
            if (MusicSourceType.LOCAL in sources && localMusicRepository != null) {
                try {
                    if (isPinyinQuery && localDeviceSongs.isNotEmpty() && isTVDevice) {
                        // 拼音搜索：本地源 Room LIKE 查询不认拼音，改用客户端过滤
                        localDeviceSongs.filter { song ->
                            matchesBySearchType(song, keyword, searchType)
                        }.map { song ->
                            RankedSong(song = song, source = MusicSourceType.LOCAL)
                        }
                    } else {
                        withTimeoutOrNull(LOCAL_TIMEOUT) {
                            localMusicRepository.search(keyword)
                                .filter { it.title.isNotBlank() }
                                .map { song ->
                                    RankedSong(song = song, source = MusicSourceType.LOCAL)
                                }
                        } ?: run {
                            AppLog.w(TAG, "Local search timed out")
                            emptyList()
                        }
                    }
                } catch (e: Exception) {
                    AppLog.e(TAG, "Local search failed: ${e.message}", e)
                    emptyList()
                }
            } else emptyList()
        }

        // 等待所有协程完成
        val nasResults = nasDeferred.await()
        val networkResults = networkDeferred.await()
        val baiduResults = baiduDeferred.await()
        val jamendoResults = jamendoDeferred.await()
        val localResults = localDeferred.await()

        // 合并所有结果
        val allResults = nasResults + networkResults + baiduResults + jamendoResults + localResults

        // 精细过滤（搜索页）：按 searchType 维度匹配
        // 中文输入走子串匹配，拼音输入走拼音匹配，互不干扰
        // 仅 TV 端启用拼音匹配（手机端触屏输入汉字方便，无需拼音）
        val filtered = if (filterMode == FilterMode.PRECISE) {
            val k = keyword.trim().lowercase()

            // 仅 TV 设备且需要拼音匹配时，提前生成拼音缓存
            val pinyinCache = if (isTVDevice) {
                SongWithPinyin.fromSongs(allResults.map { it.song })
            } else {
                emptyMap()
            }

            allResults.filter { ranked ->
                matchesBySearchTypePrecise(
                    song = ranked.song,
                    keyword = k,
                    searchType = searchType,
                    isTVDevice = isTVDevice,
                    pinyinCache = pinyinCache
                )
            }
        } else {
            allResults
        }

        // 同源内去重：相同 title+artist 只保留第一个
        val deduped = deduplicateWithinSource(filtered)

        // 按来源优先级 + 匹配分排序
        val sorted = RankedSong.sortByPriority(deduped)

        // 各源命中数统计
        val sourceBreakdown = sorted.groupBy { it.source }.mapValues { it.value.size }

        AppLog.i(TAG, "search complete: ${sorted.size} results, breakdown=$sourceBreakdown")

        SearchAggregatorResult(
            allResults = sorted,
            sourceBreakdown = sourceBreakdown
        )
    }

    /**
     * 同源内去重：相同 title+artist 归一化后只保留第一个
     * 跨源不去重（不同源的同一首歌保留多条，各自标注来源）
     */
    private fun deduplicateWithinSource(ranked: List<RankedSong>): List<RankedSong> {
        val seen = mutableSetOf<String>()
        return ranked.filter { item ->
            val key = "${item.source.name}:${normalizeKey(item.song.title, item.song.artist)}"
            if (key in seen) {
                false
            } else {
                seen.add(key)
                true
            }
        }
    }

    /**
     * 归一化歌曲标识：小写 + 去除首尾空白 + 合并连续空格
     */
    private fun normalizeKey(title: String, artist: String): String {
        return "${title.trim().lowercase()}|${artist.trim().lowercase()}"
    }

    /**
     * 按 searchType 维度匹配歌曲（客户端拼音过滤用，PinyinUtils 级别）
     */
    private fun matchesBySearchType(song: Song, keyword: String, searchType: SearchType): Boolean {
        return when (searchType) {
            SearchType.SONG_NAME_OR_ARTIST ->
                PinyinUtils.matches(song.title, keyword) ||
                        PinyinUtils.matches(song.artist, keyword)
            SearchType.ALBUM ->
                PinyinUtils.matches(song.album, keyword) ||
                        PinyinUtils.matches(song.artist, keyword)
            SearchType.ARTIST ->
                PinyinUtils.matches(song.artist, keyword)
            SearchType.SONG_NAME_ONLY ->
                PinyinUtils.matches(song.title, keyword)
        }
    }

    /**
     * 按 searchType 维度精细匹配（PRECISE 过滤用，支持子串 + 拼音全拼 + 首字母）
     */
    private fun matchesBySearchTypePrecise(
        song: Song,
        keyword: String,
        searchType: SearchType,
        isTVDevice: Boolean,
        pinyinCache: Map<String, SongWithPinyin>
    ): Boolean {
        if (keyword.isBlank()) return false

        // 1. 按 searchType 维度的子串匹配
        if (matchesSubstringBySearchType(song, keyword, searchType)) return true

        // 2. 非 TV 设备：仅子串匹配
        if (!isTVDevice) return false

        // 3. 拼音匹配（按 searchType 维度）
        val pinyin = pinyinCache[song.id] ?: SongWithPinyin(song)
        return matchesPinyinBySearchType(pinyin, keyword, searchType)
    }

    /**
     * 按 searchType 维度的子串匹配
     */
    private fun matchesSubstringBySearchType(song: Song, keyword: String, searchType: SearchType): Boolean {
        return when (searchType) {
            SearchType.SONG_NAME_OR_ARTIST ->
                song.title.lowercase().contains(keyword) ||
                        song.artist.lowercase().contains(keyword) ||
                        song.path?.lowercase()?.contains(keyword) == true
            SearchType.ALBUM ->
                song.album.lowercase().contains(keyword) ||
                        song.artist.lowercase().contains(keyword)
            SearchType.ARTIST ->
                song.artist.lowercase().contains(keyword)
            SearchType.SONG_NAME_ONLY ->
                song.title.lowercase().contains(keyword)
        }
    }

    /**
     * 按 searchType 维度的拼音匹配（全拼 + 首字母）
     */
    private fun matchesPinyinBySearchType(pinyin: SongWithPinyin, keyword: String, searchType: SearchType): Boolean {
        return when (searchType) {
            SearchType.SONG_NAME_OR_ARTIST ->
                pinyin.pinyin.contains(keyword) ||
                        pinyin.artistPinyin.contains(keyword) ||
                        pinyin.initials.contains(keyword) ||
                        pinyin.artistInitials.contains(keyword)
            SearchType.ALBUM ->
                pinyin.albumPinyin.contains(keyword) ||
                        pinyin.artistPinyin.contains(keyword) ||
                        pinyin.albumInitials.contains(keyword) ||
                        pinyin.artistInitials.contains(keyword)
            SearchType.ARTIST ->
                pinyin.artistPinyin.contains(keyword) ||
                        pinyin.artistInitials.contains(keyword)
            SearchType.SONG_NAME_ONLY ->
                pinyin.pinyin.contains(keyword) ||
                        pinyin.initials.contains(keyword)
        }
    }
}
