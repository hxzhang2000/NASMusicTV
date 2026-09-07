# 专辑/艺术家页面 UI 优化开发方案

## 现状分析

| 页面 | TV (>=1000dp) | 手机横屏 (>=600dp) | 手机竖屏 (<600dp) |
|------|---------------|-------------------|------------------|
| 专辑 | 6 列 | 3 列 | 2 列 |
| 艺术家 | 5 列 | 3 列 | 2 列 |

当前 `LibraryScreen.kt:114 adaptiveColumns()` 按宽度返回列数。专辑/艺术家均未分组、未按拼音排序、无侧边索引。

---

## 任务 1：统一网格列数为 6 列

### 目标
TV/手机横屏均 6 列；手机竖屏保持 3 列（避免卡片过窄）。

### 涉及文件
- `app/src/main/java/com/nasmusic/tv/ui/screens/LibraryScreen.kt`

### 步骤
1. 定位 `AlbumsTab()`（约 612 行）：
```kotlin
// 原：columns = GridCells.Fixed(adaptiveColumns(6, 2, 3))
columns = GridCells.Fixed(adaptiveColumns(6, 3, 6))  // (tv=6, phone=3, phoneLandscape=6)
```
2. 定位 `ArtistsTab()`（约 669 行）：
```kotlin
// 原：columns = GridCells.Fixed(adaptiveColumns(5, 2, 3))
columns = GridCells.Fixed(adaptiveColumns(6, 3, 6))
```

### 验收
- [ ] TV 上专辑/艺术家都是 6 列
- [ ] 手机横屏 6 列、竖屏 3 列
- [ ] 卡片最小宽度 ≥ 140dp（若 3 列仍窄，可在 `adaptiveColumns` 内按 `screenWidthDp / 140` 动态算）

### 工时：15 分钟

---

## 任务 2：按拼音首字母排序（中英统一）+ 组内完整拼音二级排序

### 目标
1. 专辑/艺术家列表按拼音首字母 A-Z 升序（一级排序）
2. 英文自然融入字母序，非字母归入 `#`
3. **同一字母组内**按完整拼音字符串升序二级排序（"周杰伦 zhoujielun" 排在 "张惠妹 zhanghuimei" 之后，因 `zhou` > `zhang`）

### 涉及文件
- `app/src/main/java/com/nasmusic/tv/util/PinyinUtils.kt`（已有 `toPinyin`/`toPinyinInitials`，需补 `getGroupLetter`）
- `app/src/main/java/com/nasmusic/tv/backend/local/MusicMerger.kt`（合并后双键排序）

### 步骤
1. **PinyinUtils.kt** 新增方法（文件末尾 `}` 之前）：
```kotlin
/**
 * 获取文本的分组首字母（用于 A-Z 分组索引）。
 * 中文取拼音首字母大写；英文取首字符大写；数字/符号返回 '#'。
 */
fun getGroupLetter(text: String): Char {
    if (text.isBlank()) return '#'
    val firstChar = text.trim().first()
    return when {
        firstChar.code in 0x4E00..0x9FFF -> {
            val py = Pinyin.toPinyin(firstChar)
            if (py.isNotEmpty()) py.first().uppercaseChar() else '#'
        }
        firstChar.isLetter() -> firstChar.uppercaseChar()
        else -> '#'
    }
}

/** 索引条所有字母列表（A-Z + #） */
fun getAllGroupLetters(): List<Char> = ('A'..'Z').toList() + '#'
```

> `toPinyin(text)` 已存在（"周杰伦" → "zhoujielun"），直接复用作二级排序键。

2. **MusicMerger.kt** 新增双键排序函数（class 内任意位置）：
```kotlin
/**
 * 双键排序：
 * 1. 一级键：分组首字母（A-Z，'#' 排最后用 "{" 保证字典序在字母之后）
 * 2. 二级键：完整拼音字符串（组内按拼音字典序，自然实现"周杰伦" vs "张惠妹"的正确排序）
 *
 * 英文条目：toPinyin 原样返回小写字母串，与中文拼音同序比较。
 */
private fun sortByPinyin(items: List<Album>): List<Album> =
    items.sortedWith(
        compareBy(
            { PinyinUtils.getGroupLetter(it.name).let { c -> if (c == '#') "{" else c.toString() } },
            { PinyinUtils.toPinyin(it.name) }
        )
    )

private fun sortByPinyinArtists(items: List<Artist>): List<Artist> =
    items.sortedWith(
        compareBy(
            { PinyinUtils.getGroupLetter(it.name).let { c -> if (c == '#') "{" else c.toString() } },
            { PinyinUtils.toPinyin(it.name) }
        )
    )
```

3. `mergeAlbums()` 末尾 `return albumMap.values.toList()` 改为：
```kotlin
return sortByPinyin(albumMap.values.toList())
```

4. `mergeArtists()` 同理，用 `sortByPinyinArtists`。

### 排序示例验证

| 输入顺序 | 一级键（组） | 二级键（拼音） | 排序后 |
|---------|-------------|---------------|--------|
| 张惠妹 | Z | zhanghuimei | 1 |
| 周杰伦 | Z | zhoujielun | 2 |
| 郑钧 | Z | zhengjun | 3 |
| Taylor Swift | T | taylor swift | (T 组内) |
| 痛苦的信仰 | T | tongkude xinyang | (T 组内，排在 Taylor 之后) |

> 二级键 `zhang < zheng < zhou`，所以"张惠妹" < "郑钧" < "周杰伦"，符合拼音字典序直觉。

### 验收
- [ ] 中文专辑"周杰伦"排在 Z 组
- [ ] 英文"Taylor Swift"排在 T 组
- [ ] 数字开头/符号开头归入 `#` 组，`#` 排在所有字母之后
- [ ] **同一字母组内按完整拼音字典序排列**：Z 组内"张惠妹" < "郑钧" < "周杰伦"
- [ ] 英文与中文同组时按拼音/字母字典序混排（如 T 组内"Taylor Swift" 在 "痛苦..." 之前，因 `taylor < tong`）
- [ ] 单测：`PinyinUtilsTest.sortByPinyin_zGroup()` 断言"张<郑<周"

### 单测样例（可选加 `app/src/test/`）
```kotlin
@Test fun z_group_orders_by_full_pinyin() {
    val input = listOf("周杰伦", "张惠妹", "郑钧").map { Album(id = it, name = it) }
    val sorted = MusicMerger.mergeAlbums(input, emptyList())  // 走 sortByPinyin
    assertEquals(listOf("张惠妹", "郑钧", "周杰伦"), sorted.map { it.name })
}
```

### 工时：45 分钟（含单测）

---

## 任务 3：A~Z 分组展示 + 分割线（单容器懒加载）

### 目标
列表按首字母分组，每组顶部显示大写字母标题 + 底部细线分割。**单一 `LazyVerticalGrid` 容器**，组标题用 `GridItemSpan(maxLineSpan)` 占满整行，卡片正常渲染——所有项目（标题+卡片）共享同一个懒加载容器，只渲染可见项，数据量上千也流畅。

### 涉及文件
- `app/src/main/java/com/nasmusic/tv/ui/screens/LibraryScreen.kt`

### 核心思路
```
LazyVerticalGrid (columns = 6)
├─ item(span = full)  →  组A标题 + 分割线      ← 只渲染可见的
├─ items(A组专辑)     →  AlbumCard × N
├─ item(span = full)  →  组B标题 + 分割线
├─ items(B组专辑)     →  AlbumCard × N
└─ ...
```

**对比嵌套方案**：嵌套 `LazyColumn { LazyVerticalGrid {} }` 每个组全量渲染内部网格，500+ 专辑卡顿；单容器方案只渲染屏幕可见的 ~12 个 item。

### 步骤
1. **新增数据结构**（文件顶部 `enum class LibraryTab` 之前）：
```kotlin
data class AlbumGroup(val letter: Char, val albums: List<Album>)
data class ArtistGroup(val letter: Char, val artists: List<Artist>)

fun <T> groupByLetter(items: List<T>, name: (T) -> String): List<Pair<Char, List<T>>> {
    return items.groupBy { PinyinUtils.getGroupLetter(name(it)) }
        .toList()
        .sortedBy { (letter, _) -> if (letter == '#') "{" else letter.toString() }
}
```

2. **AlbumsTab 重构**（替换原 `LazyVerticalGrid` 块，约 612 行）：
```kotlin
val columns = adaptiveColumns(6, 3, 6)
val groups = remember(albums) {
    groupByLetter(albums) { it.name }.map { (letter, list) -> AlbumGroup(letter, list) }
}
// 预计算每个组标题在 grid 中的全局 item index（用于侧边索引跳转）
// 标题 index = sum of (1 + 前 N 组卡片数)
val groupHeaderIndices = remember(groups) {
    var acc = 0
    groups.map { g ->
        val idx = acc
        acc += 1 + g.albums.size  // 1 标题 + N 卡片
        idx
    }
}

LazyVerticalGrid(
    state = listState,
    columns = GridCells.Fixed(columns),
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
) {
    groups.forEach { group ->
        // 组标题 + 分割线（占满整行）
        item(span = { GridItemSpan(columns) }, key = "header_${group.letter}") {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                Text(
                    text = group.letter.toString(),
                    color = NasMusicColors.TextPrimary,
                    fontSize = FontSize.h6(),
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp)
                )
                Divider(
                    color = NasMusicColors.Divider,
                    thickness = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        // 组内卡片
        items(group.albums, key = { it.id }) { album ->
            AlbumCard(
                album = album,
                onClick = { onOpenAlbumDetail?.invoke(album) ?: onPlayAlbum(album) },
                onPlay = { onPlayAlbum(album) },
                focusRequester = if (group === groups.first() && album === group.albums.first()) firstItemFocusRequester else null
            )
        }
    }
}
```
> 需 import：`androidx.compose.foundation.lazy.grid.GridItemSpan`（已在文件 import 中）、`androidx.compose.material3.Divider`（或用 `Box(Modifier.height(1.dp).fillMaxWidth().background(NasMusicColors.Divider))` 替代）。

3. **ArtistsTab 同理**，用 `ArtistGroup`，`ArtistCard`。

4. **stickyHeader（可选增强）**：若希望组标题滚动到顶部时固定，用 `LazyVerticalGrid` 的 `item(span = full)` + 自定义 `Modifier stickyHeader` 行为（Compose 1.4+ LazyGrid 支持 `stickyHeader` 实验性 API）。当前实现标题随滚动消失，视觉已足够清晰，可作为 M2+ 增强。

### 性能验证
- 数据量 1000 专辑：滚动应保持 60fps，只渲染可见 ~12 卡片
- 用 `LayoutInfo.visibleItemsInfo` 验证：滚动到 Z 组时，A 组的 item 不在 `visibleItemsInfo` 中
- 内存：`groupHeaderIndices` 是 `List<Int>`（组数 × 4 字节），5000 专辑约 50 组 = 200 字节，可忽略

### 验收
- [ ] 列表按 A、B、C... 分段，每段顶部大写字母 + 底部细线
- [ ] 分割线颜色 `NasMusicColors.Divider`，深浅主题自适应
- [ ] **滚动流畅 60fps**（用 1000+ 专辑压测，Profiler 检查帧率）
- [ ] **只渲染可见项**（`LayoutInfo.visibleItemsInfo.size` ≈ 12-20，不随总数增长）
- [ ] 组标题不重复创建（`key = "header_${letter}"` 保证复用）

### 工时：2 小时（含性能压测）

---

## 任务 4：字母侧边索引条

### 目标
右侧竖向 A-Z + # 索引条：遥控器上下键移动高亮字母 + 确认键跳转分组；触摸点击跳转；当前字母高亮放大。

### 涉及文件
- `app/src/main/java/com/nasmusic/tv/ui/screens/LibraryScreen.kt`（新增 `SideLetterIndex` 组件 + 在 `AlbumsTab`/`ArtistsTab` 中使用）

### 步骤
1. **新增组件**（文件底部）：
```kotlin
@Composable
fun SideLetterIndex(
    letters: List<Char>,                  // 实际有数据的字母列表（过滤空的）
    currentLetter: Char,                   // 当前高亮字母
    onLetterClick: (Char) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    LazyColumn(
        state = rememberLazyListState(),
        modifier = modifier
            .fillMaxHeight()
            .width(28.dp)
            .padding(end = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(letters) { letter ->
            val isCurrent = letter == currentLetter
            Text(
                text = letter.toString(),
                color = if (isCurrent) NasMusicColors.Primary else NasMusicColors.TextSecondary,
                fontSize = if (isCurrent) 14.sp else 11.sp,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier
                    .pointerInput(letter) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Press) {
                                    onLetterClick(letter)
                                }
                            }
                        }
                    }
                    // 遥控器聚焦后确认键触发
                    .focusable()
                    .onKeyEvent {
                        if (it.type == KeyEventType.KeyUp && (it.key == Key.DirectionUp || it.key == Key.DirectionDown)) {
                            // 让 LazyColumn 自然处理上下焦点移动，这里只处理确认
                            false
                        } else if (it.key == Key.Ok || it.key == Key.Enter) {
                            onLetterClick(letter); true
                        } else false
                    }
            )
        }
    }
}
```
> 需 import：`androidx.compose.ui.input.pointer.pointerInput`、`awaitPointerEventScope`、`PointerEventType`、`androidx.compose.ui.focus.focusable`、`androidx.compose.ui.input.key.*`。

2. **AlbumsTab 整合**：把原 `Column { Text(count); LazyVerticalGrid }` 包进 `Row`：
```kotlin
val currentLetter by remember { derivedStateOf {
    // 找当前第一个可见 item 所属的组
    val firstVisible = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key
    // key 格式 "header_X" 或 album.id；从 key 反查组字母
    firstVisible?.let { key ->
        if (key is String && key.startsWith("header_")) key.removePrefix("header_").first()
        else groups.firstOrNull { g -> g.albums.any { it.id == key } }?.letter
    } ?: 'A'
}}

Row {
    Column(modifier = Modifier.weight(1f)) {
        Text(text = stringResource(R.string.library_albums_count, albums.size), ...)
        // 任务 3 的单容器 LazyVerticalGrid
        LazyVerticalGrid(state = listState, columns = GridCells.Fixed(adaptiveColumns(6,3,6)), ...) {
            groups.forEach { group ->
                item(span = { GridItemSpan(columns) }, key = "header_${group.letter}") { ... }
                items(group.albums, key = { it.id }) { ... }
            }
        }
    }
    SideLetterIndex(
        letters = groups.map { it.letter },
        currentLetter = currentLetter,
        onLetterClick = { letter ->
            // 跳转到组标题在 grid 中的全局 index（任务3 预计算的 groupHeaderIndices）
            val targetIndex = groupHeaderIndices[groups.indexOfFirst { it.letter == letter }]
            if (targetIndex >= 0) scope.launch { listState.animateScrollToItem(targetIndex) }
        }
    )
}
```

> **跳转逻辑关键**：`groupHeaderIndices` 是任务 3 预计算的 `List<Int>`——每个组标题在单容器 LazyVerticalGrid 中的全局 item index。跳转时直接 `animateScrollToItem(targetIndex)`，让组标题滚到顶部。

### 验收
- [ ] 右侧显示 A-Z + #，只列出有数据的字母
- [ ] 触摸点击字母 → 列表滚动到对应分组顶部
- [ ] 遥控器上下键在索引条内移动焦点，确认键跳转
- [ ] 列表滚动时索引条当前字母实时高亮（放大+主色）
- [ ] 索引条不遮挡卡片内容（卡片右侧留 28dp padding）

### 工时：2 小时

---

## 任务 5：艺术家头像/封面获取与展示

### 目标
艺术家卡片圆形头像 + 详情页大图。来源优先级：NAS → 百度侧车 → iTunes → 首字母占位。

### 涉及文件
- `app/src/main/java/com/nasmusic/tv/backend/network/baidu/BaiduCoverProvider.kt`（扩展 `getArtistCover`）
- `app/src/main/java/com/nasmusic/tv/backend/local/ArtistImageResolver.kt`（新增，仿 `AlbumCoverResolver`）
- `app/src/main/java/com/nasmusic/tv/NasMusicApp.kt`（注册 `artistImageResolver` 懒加载实例）
- `app/src/main/java/com/nasmusic/tv/ui/viewmodel/MainViewModel.kt`（`updateMergedData` 中调 `resolveArtistImagesAsync`）
- `app/src/main/java/com/nasmusic/tv/ui/screens/LibraryScreen.kt`（`ArtistCard` 已用 `AsyncImage`，确认圆形裁剪）

### 步骤
1. **BaiduCoverProvider.kt** 新增方法：
```kotlin
suspend fun getArtistCover(artistName: String, path: String?): String? = withContext(Dispatchers.IO) {
    // 1. 从 path 推断艺术家目录，找 artist.jpg / folder.jpg
    path?.let { findSidecarArtistCover(it) }?.let { return@withContext it }
    // 2. 返回 null → 上层 iTunes fallback
    null
}

private suspend fun findSidecarArtistCover(songPath: String): String? {
    val parentDir = songPath.substringBeforeLast('/').ifEmpty { "/" }
    val dirResult = api.listDir(parentDir, limit = BaiduNetdiskConfig.PAGE_SIZE)
    val coverFile = dirResult.files.firstOrNull {
        !it.isDir && it.category == BaiduNetdiskConfig.CATEGORY_IMAGE &&
            it.serverFilename.substringBeforeLast('.').lowercase() in ARTIST_COVER_NAMES
    } ?: return null
    val metas = api.fileMetas(listOf(coverFile.fsId))
    val dlink = metas.firstOrNull()?.dlink ?: return null
    return ensureAccessToken(dlink)
}

companion object { private val ARTIST_COVER_NAMES = setOf("artist", "folder", "cover") }
```

2. **ArtistImageResolver.kt**（新增，结构仿 `AlbumCoverResolver`）：
```kotlin
class ArtistImageResolver(
    private val baiduCoverProvider: BaiduCoverProvider?,
    private val client: OkHttpClient
) {
    suspend fun resolveArtistImages(
        artists: List<Artist>,
        baiduSongs: List<Song>,
        onUpdated: (List<Artist>) -> Unit
    ) = withContext(Dispatchers.IO) {
        // 按 artistName 建百度歌曲索引
        val baiduArtistSongs = baiduSongs.groupBy { it.artist }
        var updated = artists
        for (artist in artists) {
            if (artist.coverUrl != null) continue
            // P1 百度侧车
            val baiduPath = baiduArtistSongs[artist.name]?.firstOrNull()?.path
            val baiduCover = baiduCoverProvider?.getArtistCover(artist.name, baiduPath)
            // P2 iTunes
            val itunesCover = baiduCover ?: searchITunesArtist(artist.name)
            if (itunesCover != null) {
                updated = updated.map { if (it.id == artist.id) it.copy(coverUrl = itunesCover) else it }
                onUpdated(updated)
            }
        }
    }

    private suspend fun searchITunesArtist(artist: String): String? {
        val url = "https://itunes.apple.com/search?term=${URLEncoder.encode(artist, "UTF-8")}&entity=musicArtist&limit=1"
        // 复用 AlbumCoverResolver 的 OkHttp 调用 + JSON 解析模式
        // 取 artworkUrl100，把 100x100 替换成 600x600
        ...
    }
}
```

3. **NasMusicApp.kt** 注册：
```kotlin
val artistImageResolver: ArtistImageResolver by lazy {
    ArtistImageResolver(baiduCoverProvider, okHttpClient)
}
```
> 需要拿到 `baiduCoverProvider` 引用（已有，在 `baiduNetdiskService` 内部，可提取为独立字段）和共享 `okHttpClient`（已在 `AlbumCoverResolver` 用）。

4. **MainViewModel.kt** 新增 `resolveArtistImagesAsync()`（仿 `resolveAlbumCoversAsync`）：
```kotlin
private var artistImageJob: Job? = null
private fun resolveArtistImagesAsync() {
    artistImageJob?.cancel()
    artistImageJob = viewModelScope.launch {
        val artists = _mergedArtists.value
        val baiduSongs = baiduIndexCache.allSongs()
        nasMusicApp.artistImageResolver.resolveArtistImages(artists, baiduSongs) { updated ->
            _mergedArtists.value = updated
        }
    }
}
```
在 `updateMergedData()` 末尾调用 `resolveArtistImagesAsync()`。

5. **ArtistCard**（已有 `AsyncImage`，约 1122 行）：确认 `Modifier.size(48.dp).clip(RoundedCornerShape(24.dp))` 是圆形；详情页用 `Modifier.size(120.dp).clip(CircleShape)` 显示大头像。

### 验收
- [ ] 艺术家卡片圆形头像加载，无头像显示首字母占位
- [ ] 详情页顶部大图（120dp+）
- [ ] iTunes 搜索结果 URL 从 100x100 升级到 600x600
- [ ] 百度侧车 artist.jpg 命中时优先用
- [ ] 缓存：同一艺术家不重复请求（Coil 磁盘缓存）

### 工时：3 小时

---

## 任务 6：加载骨架屏

### 目标
`isLoading=true` 时显示 Shimmer 占位卡片，避免列表闪烁。

### 涉及文件
- `app/src/main/java/com/nasmusic/tv/ui/components/Shimmer.kt`（新增）
- `app/src/main/java/com/nasmusic/tv/ui/screens/LibraryScreen.kt`（`AlbumsTab`/`ArtistsTab` 顶部判断）

### 步骤
1. **Shimmer.kt**（新增）：
```kotlin
@Composable
fun Modifier.shimmer(): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(0f, 1000f, infiniteRepeatable(tween(1200), label = "x"))
    background(
        Brush.linearGradient(
            colors = listOf(
                NasMusicColors.SurfaceVariant,
                NasMusicColors.SurfaceVariant.copy(alpha = 0.5f),
                NasMusicColors.SurfaceVariant
            ),
            start = Offset(x, 0f),
            end = Offset(x + 300f, 0f)
        )
    )
}

@Composable
fun AlbumCardSkeleton() {
    Column(modifier = Modifier.padding(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)).shimmer())
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(0.8f).height(12.dp).shimmer())
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth(0.5f).height(10.dp).shimmer())
    }
}
```

2. **AlbumsTab 顶部**：
```kotlin
if (isLoading) {
    LazyVerticalGrid(columns = GridCells.Fixed(6)) {
        items(12) { AlbumCardSkeleton() }
    }
    return
}
```

### 验收
- [ ] 加载时显示 12 个骨架卡片（Shimmer 流动效果）
- [ ] 数据返回后无缝替换为真实卡片，无跳动
- [ ] 空状态优先于骨架屏（`!isLoading && albums.isEmpty()` → EmptyState）

### 工时：1 小时

---

## 任务 7：空状态统一

### 目标
无数据时显示统一插画 + 文案 + 操作引导按钮。

### 涉及文件
- `app/src/main/java/com/nasmusic/tv/ui/components/EmptyState.kt`（已存在 `ListStateIndicators.kt`，扩展为通用）
- `app/src/main/java/com/nasmusic/tv/ui/screens/LibraryScreen.kt`

### 步骤
1. 确认现有 `EmptyState` 签名，扩展为：
```kotlin
@Composable
fun EmptyState(
    icon: ImageVector = Icons.Default.MusicNote,
    title: String,
    message: String,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
)
```

2. **AlbumsTab** 空状态：
```kotlin
if (!isLoading && albums.isEmpty()) {
    EmptyState(
        icon = Icons.Default.Album,
        title = stringResource(R.string.empty_albums_title),
        message = stringResource(R.string.empty_albums_message),
        actionText = if (!isConnected) stringResource(R.string.connect_server) else null,
        onAction = if (!isConnected) onConnectServer else null
    )
    return
}
```
> 需新增 string resources：`empty_albums_title` / `empty_albums_message` / `empty_artists_title` / `empty_artists_message`。

3. 艺术家空状态同理。

### 验收
- [ ] 无 NAS、无本地、无百度时显示空状态
- [ ] 有操作引导按钮（连接服务器 / 扫描本地）
- [ ] 深浅主题适配

### 工时：1 小时

---

## 任务 8：焦点记忆

### 目标
切 Tab/进详情页返回时恢复上次焦点位置。

### 涉及文件
- `app/src/main/java/com/nasmusic/tv/ui/viewmodel/MainViewModel.kt`

### 步骤
1. **MainViewModel** 新增状态保存：
```kotlin
// 每个 Tab 的列表滚动位置
private val _albumListState = MutableStateFlow(LazyListState(0, 0))
val albumListState: StateFlow<LazyListState> = _albumListState.asStateFlow()
// 艺术家同理

// 保存：在 AlbumsTab 的 onScroll callback 中
// 恢复：在 AlbumsTab 进入时读 _albumListState.value，listState.scrollToItem(firstVisibleItem, offset)
```

2. 用 `LaunchedEffect(Unit) { listState.scrollToItem(savedIndex, savedOffset) }` 进入时恢复。

3. 用 `snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }` 监听滚动，存入 ViewModel。

### 注意
数据源变化后 index 可能偏移。按 ID 定位更稳：保存首个可见 item 的 `album.id`，恢复时 `listState.scrollToItem(groups.indexOfFirst { it.albums.any { a -> a.id == savedId } })`。

### 验收
- [ ] 切到艺术家 Tab 再切回专辑，滚动位置不变
- [ ] 进入专辑详情返回，位置不变
- [ ] 数据刷新后位置合理（首个可见 ID 仍在 → 跳到该 ID；不在 → 顶部）

### 工时：1.5 小时

---

## 任务 9：专辑卡片信息密度增强

### 目标
卡片增加：来源徽标、年份+流派、歌曲数+时长、长按菜单。

### 涉及文件
- `app/src/main/java/com/nasmusic/tv/data/model/Album.kt`（加 `sourceType`、`genre`、`totalDurationMs` 字段）
- `app/src/main/java/com/nasmusic/tv/backend/local/MusicMerger.kt`（合并时填充 `genre`/`totalDurationMs`）
- `app/src/main/java/com/nasmusic/tv/ui/screens/LibraryScreen.kt`（`AlbumCard` 重构）
- `app/src/main/java/com/nasmusic/tv/ui/components/AlbumLongPressMenu.kt`（新增）

### 步骤
1. **Album.kt** 扩展：
```kotlin
data class Album(
    ...,
    val sourceType: MusicSourceType? = null,    // NAS / LOCAL / BAIDU
    val genre: String? = null,                  // 主流派
    val totalDurationMs: Long = durationMs      // 别名
)
```

2. **AlbumCard** 布局调整（约 1053 行 `Column` 内）：
```kotlin
Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
    Box(/* 封面 */) {
        AsyncImage(...)
        // 右上角来源徽标
        SourceBadge(sourceType = album.sourceType)  // 小圆点 + 首字母 N/L/B
    }
    Spacer(Modifier.height(4.dp))
    Text(text = album.name, maxLines = 1, overflow = Ellipsis, ...)
    // 副信息行
    Row(verticalAlignment = CenterVertically) {
        if (album.year != null) Text("${album.year}", fontSize = 11.sp, color = TextSecondary)
        if (album.genre != null) Text(" · ${album.genre}", fontSize = 11.sp, color = TextSecondary, maxLines = 1)
    }
    // 底部
    Text("${album.songCount}首 · ${formatDuration(album.durationMs)}", fontSize = 10.sp)
}
```

3. **长按菜单**：`FocusableSurface` 的 `onClick` 之外，增加 `onLongClick`（需用 `combinedClickable` 或 TV 的 `Modifier.onLongPress`）。菜单项：播放、加入队列、收藏、查看详情、删除本地（仅本地源）。

### 验收
- [ ] 卡片显示年份+流派（有则显示，无则省略）
- [ ] 来源徽标在封面右上角
- [ ] 长按弹出操作菜单
- [ ] 卡片高度一致（信息行用固定高度避免参差）

### 工时：2 小时

---

## 任务 10：艺术家卡片增强

### 目标
卡片增加：专辑数+歌曲数、流派标签、来源徽标、长按菜单。

### 涉及文件
- `app/src/main/java/com/nasmusic/tv/ui/screens/LibraryScreen.kt`（`ArtistCard` 约 1089 行）

### 步骤
1. **ArtistCard** 已有 `songCount`，加 `albumCount` 参数：
```kotlin
private fun ArtistCard(
    artist: String,
    coverUrl: String? = null,
    songCount: Int,
    albumCount: Int = 0,
    primaryGenre: String? = null,
    sourceType: MusicSourceType? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    ...
)
```

2. 布局：
```kotlin
Column(horizontalAlignment = CenterHorizontally) {
    Box(/* 圆形头像 + 来源徽标 */) { ... }
    Text(artist, maxLines = 1, overflow = Ellipsis)
    Text("$albumCount 专辑 · $songCount 首", fontSize = 11.sp, color = TextSecondary)
    if (primaryGenre != null) Text(primaryGenre, fontSize = 10.sp, color = TextTertiary)
}
```

3. **ArtistsTab** 传参时聚合 `primaryGenre`：从 `artistSongsMap[artist.name]` 取所有歌曲的 genre 字段众数。

### 验收
- [ ] 卡片显示"5 专辑 · 68 首"
- [ ] 流派标签显示（无则省略）
- [ ] 长按菜单同专辑卡片

### 工时：1.5 小时

---

## 任务 11：横向滑动边缘渐变提示

### 目标
手机竖屏 3 列、卡片导致需横向滚动时，左右边缘渐变提示。

### 涉及文件
- `app/src/main/java/com/nasmusic/tv/ui/screens/LibraryScreen.kt`

### 步骤
1. 在 `LazyVerticalGrid` 外层包 `Box`，叠加渐变：
```kotlin
Box {
    LazyVerticalGrid(...) { ... }
    // 左边缘渐变
    Box(Modifier.width(16.dp).fillMaxHeight().align(Alignment.CenterStart)
        .background(Brush.horizontalGradient(listOf(Background, Color.Transparent))))
    // 右边缘同理
}
```
> 仅当 `canScrollHorizontal` 为 true 时显示（`LazyGridState.canScrollForward` 判断）。

### 验收
- [ ] TV 上不显示（TV 6 列铺满，无横向滚动）
- [ ] 手机竖屏 3 列时显示渐变
- [ ] 数据加载完滚动到底后渐变消失（可选）

### 工时：30 分钟

---

## 任务 12：高对比度/色盲适配

### 目标
响应系统无障碍设置，增强视觉对比。

### 涉及文件
- `app/src/main/java/com/nasmusic/tv/ui/theme/NasMusicColors.kt`（或 Theme.kt）
- `app/src/main/java/com/nasmusic/tv/ui/screens/LibraryScreen.kt`

### 步骤
1. 读取系统无障碍设置：
```kotlin
val isHighContrast = LocalContext.current.resources.configuration.uiMode and ... // 或用 AccessibilityManager
```

2. 高对比度模式：`Divider.thickness = 2.dp`、`Text.color` 强制高对比、`focusBorderWidth = 4.dp`。

3. 色盲模式：收藏状态不只用颜色，加星标形状（`Icons.Default.Star`）。

### 验收
- [ ] 系统开启高对比度时分割线加粗
- [ ] 焦点框更醒目
- [ ] 收藏标记有形状区分

### 工时：1 小时

---

## 任务 13：详情页信息架构优化

### 目标
专辑/艺术家详情页统一布局：顶部大图 + 基本信息 + 操作栏 + 歌曲列表。

### 涉及文件
- `app/src/main/java/com/nasmusic/tv/ui/screens/AlbumDetailScreen.kt`
- `app/src/main/java/com/nasmusic/tv/ui/screens/ArtistDetailScreen.kt`

### 步骤
1. 顶部 `Header`：大图（`Modifier.fillMaxWidth().height(200.dp)`）+ 标题 + 副信息（发行日期/流派/简介）。

2. 操作栏：`播放全部` / `随机播放` / `收藏` / `加入队列` 四个按钮。

3. 歌曲列表：支持多选（`selectedIds: Set<String>`）+ 拖拽排序（`Modifier.draggable`）+ 行内按钮（播放/队列/收藏/更多）。

### 验收
- [ ] 详情页顶部大图 + 标题 + 操作栏
- [ ] 歌曲列表可多选
- [ ] 拖拽排序生效（仅歌单编辑模式）

### 工时：4 小时

---

## 任务 14：遥控器快捷键提示

### 目标
首次进入曲库页显示底部浮层提示，3s 后消失，设置可关闭。

### 涉及文件
- `app/src/main/java/com/nasmusic/tv/ui/screens/LibraryScreen.kt`
- `app/src/main/java/com/nasmusic/tv/data/prefs/AppPreferences.kt`

### 步骤
1. `AppPreferences` 加 `showLibraryShortcutHint: BooleanFlow`（默认 true）。

2. `LibraryScreen` 顶部：
```kotlin
LaunchedEffect(Unit) {
    if (prefs.showLibraryShortcutHint) {
        delay(3000)
        prefs.setShowLibraryShortcutHint(false)
    }
}
if (prefs.showLibraryShortcutHint.value) {
    Box(Modifier.align(Alignment.BottomCenter).padding(20.dp)) {
        Text("方向键导航 | 确认播放 | 长按菜单 | 侧边索引跳转")
    }
}
```

### 验收
- [ ] 首次进入显示 3s
- [ ] 再进不再显示
- [ ] 设置可重置提示

### 工时：30 分钟

---

## 实施顺序与依赖

| 顺序 | 任务 | 依赖 | 工时 |
|------|------|------|------|
| 1 | 任务 1：6 列网格 | 无 | 15min |
| 2 | 任务 2：拼音排序 | 无 | 30min |
| 3 | 任务 3：分组展示（单容器懒加载） | 任务 2 | 2h |
| 4 | 任务 4：侧边索引 | 任务 3 | 2h |
| 5 | 任务 6：骨架屏 | 无 | 1h |
| 6 | 任务 7：空状态 | 无 | 1h |
| 7 | 任务 5：艺术家头像 | 无 | 3h |
| 8 | 任务 9：专辑卡片信息密度 | 无 | 2h |
| 9 | 任务 10：艺术家卡片增强 | 任务 5 | 1.5h |
| 10 | 任务 8：焦点记忆 | 任务 3 | 1.5h |
| 11 | 任务 11：横向滑动提示 | 任务 1 | 30min |
| 12 | 任务 12：高对比度适配 | 无 | 1h |
| 13 | 任务 13：详情页优化 | 任务 5 | 4h |
| 14 | 任务 14：快捷键提示 | 无 | 30min |

**总计：~20 小时（约 2.5 个工作日）**

---

## 风险与对策

| 风险 | 对策 |
|------|------|
| 手机竖屏 6 列卡片过窄 | 任务 1 用 `screenWidthDp / 140` 动态算，竖屏固定 3 列 |
| 拼音排序对生僻字/符号 | 统一归入 `#`，单测覆盖：纯中文、纯英文、中英混合、数字开头、空字符串 |
| **分组网格性能（上千专辑）** | **已用单容器方案解决**：任务 3 用 `LazyVerticalGrid + GridItemSpan`，组标题作为 span=full 的 item 插入，卡片正常渲染。所有项目共享同一个懒加载容器，只渲染可见项（~12-20 个），总数 5000+ 仍 60fps。**不再用嵌套 LazyColumn+LazyVerticalGrid**（嵌套方案每组全量渲染，500+ 卡顿） |
| iTunes 搜索慢/失败 | Coil 超时 5s + 失败回退首字母占位 |
| 侧边索引遥控器焦点与列表焦点冲突 | 索引条用独立 `FocusRequester`，确认后 `listState.animateScrollToItem` 后把焦点交还列表 |
| 长按菜单在 TV 上的交互 | TV 用"菜单键"（`KEYCODE_MENU`）触发，手机用 `combinedClickable` |
| 百度无 artist.jpg | 兜底 iTunes，仍无则首字母占位（已有 `♪` 占位逻辑） |
| **组标题跳转 index 计算** | 任务 3 预计算 `groupHeaderIndices: List<Int>`，每组的标题在全局 grid 中的 item index。`O(1)` 查表跳转，不用遍历 |
| **currentLetter 实时高亮** | 用 `derivedStateOf` 从 `listState.layoutInfo.visibleItemsInfo.firstOrNull()?.key` 反查组字母，避免每帧重组 |

---

## 验收清单

### 核心功能
- [ ] TV/横屏专辑+艺术家 6 列
- [ ] 列表按拼音 A-Z 排序，英文融入
- [ ] A-Z 分组 + 字母标题 + 分割线
- [ ] 右侧字母索引条（遥控器+触摸双模）
- [ ] 艺术家圆形头像 + 详情页大图
- [ ] 无 crash、滚动 60fps（5000+ 专辑压测通过，只渲染可见项）

### 体验优化
- [ ] 加载骨架屏
- [ ] 空状态统一插画
- [ ] 焦点记忆（切 Tab/详情页返回）
- [ ] 专辑卡片：年份/流派/歌曲数/时长/来源徽标/长按菜单
- [ ] 艺术家卡片：专辑数/歌曲数/流派/来源徽标/长按菜单
- [ ] 横向滑动渐变提示
- [ ] 高对比度/色盲适配
- [ ] 详情页信息架构完善
- [ ] 首次快捷键提示

---

## 相关文件清单

```
app/src/main/java/com/nasmusic/tv/ui/screens/LibraryScreen.kt
    ├─ adaptiveColumns()              // 任务 1
    ├─ AlbumsTab()                    // 任务 1/3/4/6/7/8/9/11/13
    ├─ ArtistsTab()                   // 任务 1/3/4/6/7/8/10/11/13
    ├─ AlbumCard()                    // 任务 9
    ├─ ArtistCard()                   // 任务 5/10
    ├─ AlbumGroup/ArtistGroup         // 任务 3
    ├─ groupByLetter()                // 任务 3
    ├─ SideLetterIndex()              // 任务 4
    └─ LibraryShortcutHint()          // 任务 14

app/src/main/java/com/nasmusic/tv/ui/components/
    ├─ Shimmer.kt                     // 任务 6 新增
    ├─ EmptyState.kt                  // 任务 7 扩展
    ├─ AlbumLongPressMenu.kt          // 任务 9 新增
    └─ SourceBadge.kt                 // 任务 9/10 新增

app/src/main/java/com/nasmusic/tv/backend/local/MusicMerger.kt
    ├─ mergeAlbums() + sortByPinyin   // 任务 2
    └─ mergeArtists() + sortByPinyin   // 任务 2

app/src/main/java/com/nasmusic/tv/backend/local/ArtistImageResolver.kt  // 任务 5 新增
app/src/main/java/com/nasmusic/tv/backend/network/baidu/BaiduCoverProvider.kt  // 任务 5 扩展
app/src/main/java/com/nasmusic/tv/util/PinyinUtils.kt  // 任务 2 扩展
app/src/main/java/com/nasmusic/tv/ui/viewmodel/MainViewModel.kt  // 任务 5/8（艺术家头像解析、焦点记忆）
app/src/main/java/com/nasmusic/tv/NasMusicApp.kt       // 任务 5 注册
app/src/main/java/com/nasmusic/tv/ui/theme/NasMusicColors.kt  // 任务 12
app/src/main/java/com/nasmusic/tv/ui/screens/AlbumDetailScreen.kt  // 任务 14
app/src/main/java/com/nasmusic/tv/ui/screens/ArtistDetailScreen.kt  // 任务 14
```

---

## 备注

- 任务 1~4 是核心展示层重构，建议一次性做完（约 4h）再推送验证
- 任务 5（艺术家头像）涉及网络请求，建议单独提交，便于回滚
- 任务 9/10（卡片信息密度）需先加数据字段，再改 UI，分两步
- 所有新增 string resource 统一放 `res/values/strings.xml` 和 `res/values-zh/strings.xml`
- 提交规范：`feat(library): 任务X-简述`，每任务独立 commit 便于回滚
