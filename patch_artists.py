# -*- coding: utf-8 -*-
"""一次性补丁：艺术家同名合并 + 合唱艺术家拆分生效。"""
import io, re, sys

ROOT = r"D:\hxzhang\MyGithubSoftware\NasAudio\NASMusicTV\app\src\main\java\com\nasmusic\tv"

def read(p):
    with io.open(p, encoding="utf-8", newline="") as f:
        return f.read()

def write(p, s):
    with io.open(p, "w", encoding="utf-8", newline="") as f:
        f.write(s)

def rep(path, old, new, expect=1, label=""):
    s = read(path)
    n = s.count(old)
    assert n == expect, "%s: expect %d got %d for %r" % (label or path, expect, n, old[:60])
    s = s.replace(old, new)
    write(path, s)
    print("OK  %s (%s)" % (label or path, n))

# ---------------------------------------------------------------- MusicMerger
mm = ROOT + r"\backend\local\MusicMerger.kt"
rep(mm,
    "            val key = artist.name.lowercase().trim()\n",
    "            val key = ArtistSplitter.normalizeKey(artist.name)\n",
    expect=3, label="MusicMerger.mergeArtists 归一化键")

s = read(mm)
# buildLocalArtists / buildBaiduArtists 改为复用 buildArtistsFromSongs
for fn, arg, prefix in (
    ("buildLocalArtists", "localSongs", "local_artist_"),
    ("buildBaiduArtists", "baiduSongs", "baidu_artist_"),
):
    pat = re.compile(
        r"    fun " + fn + r"\(" + arg + r": List<Song>\): List<Artist> \{.*?\n    \}\n",
        re.S,
    )
    s2, n = pat.subn(
        "    fun " + fn + "(" + arg + ": List<Song>): List<Artist> =\n"
        "        buildArtistsFromSongs(" + arg + ', "' + prefix + '")\n',
        s,
        count=1,
    )
    assert n == 1, fn
    s = s2
print("OK  MusicMerger: buildLocalArtists/buildBaiduArtists 复用 buildArtistsFromSongs")

new_fn = '''
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
'''
anchor = "    /**\n     * 双键拼音排序"
assert s.count(anchor) == 1
s = s.replace(anchor, new_fn.lstrip("\n") + anchor)
write(mm, s)
print("OK  MusicMerger: 新增 buildArtistsFromSongs")

# ---------------------------------------------------------------- MainViewModel
vm = ROOT + r"\ui\viewmodel\MainViewModel.kt"

rep(vm,
    """            for (song in newSongs) {
                val artists = ArtistSplitter.split(song.artist)
                songMap[song.id] = artists
                for (name in artists) {
                    artistMap.getOrPut(name) { mutableListOf() }.add(song)
                }
            }""",
    """            for (song in newSongs) {
                val artists = ArtistSplitter.split(song.artist)
                songMap[song.id] = artists
                for (name in artists) {
                    // 归一化键：同一个人的不同写法（全角/空白/大小写）合并到一块
                    val key = ArtistSplitter.normalizeKey(name)
                    if (key.isBlank()) continue
                    artistMap.getOrPut(key) { mutableListOf() }.add(song)
                }
            }""",
    label="MainViewModel.buildArtistMapsIncremental 归一化键")

rep(vm,
    """                // 合并重复艺术家（同一名字可能来自独立条目和拆分条目）
                val merged = splitArtists.groupBy { it.name }.map { (name, group) ->
                    group.first().copy(
                        songCount = group.maxOf { it.songCount },
                        albumCount = group.sumOf { it.albumCount }
                    )
                }""",
    """                // 合并重复艺术家（同一名字可能来自独立条目和拆分条目）
                // 归一化去重：NFKC + trim + 折叠空白 + 小写，
                // 避免 "古天乐" 与 "古天乐 " 这类肉眼同名、字符串不同的条目变成两块
                val mergedMap = linkedMapOf<String, Artist>()
                for (item in splitArtists) {
                    val key = ArtistSplitter.normalizeKey(item.name)
                    if (key.isBlank()) continue
                    val existing = mergedMap[key]
                    mergedMap[key] = if (existing == null) item else existing.copy(
                        songCount = maxOf(existing.songCount, item.songCount),
                        albumCount = existing.albumCount + item.albumCount,
                        coverUrl = existing.coverUrl ?: item.coverUrl
                    )
                }
                val merged = mergedMap.values.toList()""",
    label="MainViewModel.loadArtists 归一化合并")

rep(vm,
    """            val names = com.nasmusic.tv.util.ArtistSplitter.split(song.artist)
            for (name in names) {
                val key = name.lowercase().trim()""",
    """            val names = ArtistSplitter.split(song.artist)
            for (name in names) {
                val key = ArtistSplitter.normalizeKey(name)""",
    label="MainViewModel.updateArtistSongCounts 拆分键")

rep(vm,
    """                val key = artist.name.lowercase().trim()
                val countedSongs = artistSongCounts[key]""",
    """                val key = ArtistSplitter.normalizeKey(artist.name)
                val countedSongs = artistSongCounts[key]""",
    label="MainViewModel.updateArtistSongCounts 回填键")

rep(vm,
    """                    val rawMatchingIds = _rawArtistList
                        .filter { artistName in ArtistSplitter.split(it.name) }
                        .map { it.id }
                        .distinct()
                        .ifEmpty {
                            // fallback: 从拆分后的列表中提取原始 ID
                            val artists = _artists.value.dataOrNull() ?: emptyList()
                            val artist = artists.find { it.name == artistName }
                            if (artist != null) listOf(artist.id.substringBefore("|", artist.id)) else emptyList()
                        }""",
    """                    val artistKey = ArtistSplitter.normalizeKey(artistName)
                    val rawMatchingIds = _rawArtistList
                        .filter { ArtistSplitter.containsArtist(it.name, artistName) }
                        .map { it.id }
                        .distinct()
                        .ifEmpty {
                            // fallback: 从拆分后的列表中提取原始 ID
                            val artists = _artists.value.dataOrNull() ?: emptyList()
                            val artist = artists.find { ArtistSplitter.normalizeKey(it.name) == artistKey }
                            if (artist != null) listOf(artist.id.substringBefore("|", artist.id)) else emptyList()
                        }""",
    label="MainViewModel.loadArtistSongs 原始条目匹配")

rep(vm,
    """                    // 将返回的歌曲按 ArtistSplitter 拆分后，只取包含该艺术家的歌曲
                    val matchingSongs = allSongs.filter { song ->
                        artistName in ArtistSplitter.split(song.artist)
                    }
                    AppLog.d("NASMusic", "  matchingSongs=${matchingSongs.size} (raw=${allSongs.size})")
                    _artistDetailSongsCache.value = _artistDetailSongsCache.value.toMutableMap().apply {
                        put(artistName, matchingSongs)
                    }
                    // 同时按拆分后的艺术家名更新 artistSongsMap 缓存
                    buildArtistMapsIncremental(matchingSongs)""",
    """                    // 将返回的歌曲按 ArtistSplitter 拆分后，只取包含该艺术家的歌曲
                    val matchingSongs = allSongs.filter { song ->
                        ArtistSplitter.containsArtist(song.artist, artistName)
                    }
                    AppLog.d("NASMusic", "  matchingSongs=${matchingSongs.size} (raw=${allSongs.size})")
                    // 后端返回为空时的兜底：从本地已加载歌曲（NAS 分页 + 本地设备 + 百度）
                    // 按拆分名过滤，避免合唱艺术家详情页一片空白
                    val localMatched = (_songsPaging.value.songs + _localSongs.value + baiduIndexCache.allSongs())
                        .filter { ArtistSplitter.containsArtist(it.artist, artistName) }
                    val finalSongs = (matchingSongs + localMatched).distinctBy { it.id }
                    AppLog.d("NASMusic", "  finalSongs=${finalSongs.size} (backend=${matchingSongs.size}, local=${localMatched.size})")
                    _artistDetailSongsCache.value = _artistDetailSongsCache.value.toMutableMap().apply {
                        put(artistName, finalSongs)
                    }
                    // 同时按拆分后的艺术家名更新 artistSongsMap 缓存
                    buildArtistMapsIncremental(finalSongs)""",
    label="MainViewModel.loadArtistSongs 本地兜底")

rep(vm,
    """                    val matchingSongs = result.allResults.map { it.song }.filter { song ->
                        artistName in ArtistSplitter.split(song.artist)
                    }""",
    """                    val matchingSongs = result.allResults.map { it.song }.filter { song ->
                        ArtistSplitter.containsArtist(song.artist, artistName)
                    }""",
    label="MainViewModel.loadArtistSongs 多源分支")

# ---------------------------------------------------------------- LibraryScreen
ls = ROOT + r"\ui\screens\LibraryScreen.kt"
rep(ls,
    "import com.nasmusic.tv.util.PinyinUtils\n",
    "import com.nasmusic.tv.util.ArtistSplitter\nimport com.nasmusic.tv.util.PinyinUtils\nimport com.nasmusic.tv.backend.local.MusicMerger\n",
    label="LibraryScreen import")

rep(ls,
    """    // ARTISTS Tab：搜索时用多源搜索结果按艺术家聚合；无搜索时用本地加载的艺术家
    val filteredArtists by remember(filterQuery, artists, searchResults) {
        derivedStateOf {
            if (filterQuery.isBlank()) artists
            else {
                // 多源搜索结果中提取艺术家（按艺术家名去重）
                val searchArtists = searchResults
                    .groupBy { it.artist.lowercase() }
                    .map { (_, songs) ->
                        val artistName = songs.first().artist
                        Artist(id = artistName.lowercase(), name = artistName, songCount = songs.size)
                    }
                // 合并本地过滤的艺术家
                val localFiltered = artists.filter {
                    PinyinUtils.matches(it.name, filterQuery)
                }
                val seen = mutableSetOf<String>()
                (localFiltered + searchArtists).filter { artist ->
                    val key = artist.name.lowercase()
                    seen.add(key)
                }
            }
        }
    }""",
    """    // ARTISTS Tab：搜索时用多源搜索结果按艺术家聚合；无搜索时用本地加载的艺术家
    val filteredArtists by remember(filterQuery, artists, searchResults) {
        derivedStateOf {
            if (filterQuery.isBlank()) artists
            else {
                // 多源搜索结果中提取艺术家：
                // 必须先按 ArtistSplitter 拆分合唱名（"古天乐/萱萱" → 古天乐、萱萱），
                // 否则合唱名会变成一个独立艺术家块，且详情页按整串匹配永远查不到歌
                val searchArtists = MusicMerger.buildArtistsFromSongs(searchResults, "search_artist_")
                    .filter { PinyinUtils.matches(it.name, filterQuery) }
                // 合并本地过滤的艺术家
                val localFiltered = artists.filter {
                    PinyinUtils.matches(it.name, filterQuery)
                }
                // 归一化去重（NFKC + trim + 小写），同名不同写法只保留一块
                val seen = mutableSetOf<String>()
                (localFiltered + searchArtists).filter { artist ->
                    seen.add(ArtistSplitter.normalizeKey(artist.name))
                }
            }
        }
    }""",
    label="LibraryScreen.filteredArtists 拆分+归一化")

rep(ls,
    """    // 艺术家搜索时的歌曲映射：多源搜索结果按艺术家分组，与本地 artistSongsMap 合并
    val displayArtistSongsMap by remember(filterQuery, searchResults, artistSongsMap) {
        derivedStateOf {
            if (filterQuery.isBlank()) artistSongsMap
            else {
                val searchMap = searchResults.groupBy { it.artist }
                // 合并：本地数据 + 搜索结果
                val merged = artistSongsMap.toMutableMap()
                for ((artist, songs) in searchMap) {
                    val existing = merged[artist].orEmpty()
                    // 去重合并
                    val existingIds = existing.map { it.id }.toSet()
                    merged[artist] = existing + songs.filter { it.id !in existingIds }
                }
                merged
            }
        }
    }""",
    """    // 艺术家搜索时的歌曲映射：多源搜索结果按「拆分后的艺术家名」分组，与本地 artistSongsMap 合并
    val displayArtistSongsMap by remember(filterQuery, searchResults, artistSongsMap) {
        derivedStateOf {
            if (filterQuery.isBlank()) artistSongsMap
            else {
                val merged = artistSongsMap.toMutableMap()
                for (song in searchResults) {
                    for (name in ArtistSplitter.split(song.artist)) {
                        val key = ArtistSplitter.normalizeKey(name)
                        if (key.isBlank()) continue
                        val existing = merged[key].orEmpty()
                        // 去重合并
                        if (song.id !in existing.map { it.id }.toSet()) {
                            merged[key] = existing + song
                        }
                    }
                }
                merged
            }
        }
    }""",
    label="LibraryScreen.displayArtistSongsMap 拆分聚合")

rep(ls,
    "                    val listed = filteredArtists.flatMap { displayArtistSongsMap[it.name].orEmpty() }",
    "                    val listed = filteredArtists.flatMap { displayArtistSongsMap[ArtistSplitter.normalizeKey(it.name)].orEmpty() }",
    label="LibraryScreen.playAllSongs 归一化查表")

rep(ls,
    "                            val artistSongs = artistSongsMap[artist.name] ?: emptyList()",
    "                            val artistSongs = artistSongsMap[ArtistSplitter.normalizeKey(artist.name)] ?: emptyList()",
    label="LibraryScreen.ArtistsTab 卡片查表")

# ---------------------------------------------------------------- AppRoot
ar = ROOT + r"\ui\components\AppRoot.kt"
rep(ar,
    """                    val selectedArtist = selectedArtistName?.let { name ->
                        artistsState.dataOrNull()?.find { it.name == name }
                    }""",
    """                    val selectedArtist = selectedArtistName?.let { name ->
                        val key = com.nasmusic.tv.util.ArtistSplitter.normalizeKey(name)
                        artistsState.dataOrNull()?.find {
                            com.nasmusic.tv.util.ArtistSplitter.normalizeKey(it.name) == key
                        }
                    }""",
    label="AppRoot 详情页头部艺术家匹配")

print("ALL PATCHES APPLIED")
