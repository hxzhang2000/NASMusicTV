# 三项功能优化修改方案

> 日期：2026-06-25
> 涉及：逐字歌词刷新、后端封面图获取与轮播、网络歌词切换联动封面图

---

## 一、问题一：逐字歌词刷新频率太低，有跳动的感觉

### 1.1 现象

逐字高亮（WORD_BY_WORD）模式下，文字高亮切换有明显"跳动"感，不够流畅。

### 1.2 根因

逐字高亮依赖 `currentTimeMs` 判断每个字符的播放状态（[LyricsView.kt:203](file:///E:/temp/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/components/LyricsView.kt#L203) `word.startMs <= currentTimeMs`），而 `currentTimeMs` 来自 `PlayerManager.progress`，该进度通过 `Handler.postDelayed` 每 **1000ms** 才更新一次（[PlayerManager.kt:41](file:///E:/temp/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/player/PlayerManager.kt#L41)）。

结果：逐字高亮每秒最多刷新一次，一行 10 个字会被"批量点亮"，视觉上就是跳跃式高亮。

### 1.3 方案：歌词局部高频刷新（不影响进度条）

**核心思路**：进度条保持 1000ms 刷新（避免不必要的 UI 重绘和 CPU 占用），但歌词组件内部用更高频率的时钟驱动逐字高亮刷新。

**修改 1：LyricsView 增加独立高频时钟**

文件：`ui/components/LyricsView.kt`

```kotlin
@Composable
fun LyricsView(
    lyrics: Lyrics?,
    currentTimeMs: Long,
    modifier: Modifier = Modifier,
    highlightMode: LyricsHighlightMode = LyricsHighlightMode.LINE_BY_LINE
) {
    // 新增：逐字模式下使用本地高频时钟插值，平滑过渡
    var lyricTickMs by remember { mutableStateOf(currentTimeMs) }
    if (highlightMode == LyricsHighlightMode.WORD_BY_WORD && lyrics != null) {
        // 仅逐字模式启动高频刷新（~50ms，约 20fps）
        LaunchedEffect(currentTimeMs) {
            val player = ... // 获取 ExoPlayer 实例或通过回调
            while (true) {
                // 基于上次已知 progress + 已流逝时间估算当前进度
                lyricTickMs = currentTimeMs + (System.currentTimeMillis() - anchorTime)
                delay(50)
            }
        }
    }
    // 逐字高亮逻辑使用 lyricTickMs 而非 currentTimeMs
}
```

**实现细节**：
- 仅在 `WORD_BY_WORD` 模式且歌词非空时启动高频时钟
- 时钟基于上次 `currentTimeMs`（1 秒锚点）+ 实际流逝时间插值，估算当前进度
- 刷新间隔 **50ms（20fps）**，肉眼感知流畅
- 进度条等其它 UI 仍用 1000ms 的 `progress`，不受影响

**修改 2：LyricsView 接收 Player 引用用于精确插值（可选优化）**

更精确的方案是让 LyricsView 直接读取 `ExoPlayer.currentPosition`，但会引入耦合。推荐先用方案 1（时间插值），如有偏差再考虑。

**预估影响范围**：
- 文件：`ui/components/LyricsView.kt`（主要修改）
- 文件：`ui/screens/NowPlayingScreen.kt`（传参，可能需要传 isPlaying 状态控制时钟启停）
- 风险：低。只影响逐字高亮的视觉刷新，不影响播放逻辑

---

## 二、问题二：后端封面图获取与多封面轮播

### 2.1 现象

用户反馈封面图可能有问题，且希望多种封面（歌曲/专辑/艺术家）都能取到时，定时轮播展示，而非只显示一张图。

### 2.2 现状分析

#### 封面类型与后端支持

一首歌最多可获取 **3 种封面**，`Song` 模型已携带 `albumId` 和 `artistId`：

| 封面类型 | Jellyfin API | Navidrome API | Song 模型字段 |
|---------|-------------|---------------|--------------|
| **歌曲封面** | `Items/{songId}/Images/Primary`（带 `ImageTags.Primary` tag） | `getCoverArt?id={coverArt}` | `id` + `coverUrl` |
| **专辑封面** | `Items/{albumId}/Images/Primary` | `getCoverArt?id={albumId}` | `albumId` ✅ 已有 |
| **艺术家封面** | `Items/{artistId}/Images/Primary` | `getCoverArt?id={artistId}` | `artistId` ✅ 已有 |

**数据缺口**：[JellyfinAdapter.kt:879-927](file:///E:/temp/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/backend/impl/JellyfinAdapter.kt#L879) 的 `jsonObjectToSong` **没有解析 `artistId`**（Song 模型有 `artistId` 字段但未填充），需要补充解析 `ArtistItems` 数组。

#### 现有 fallback 的问题

**JellyfinAdapter**（[JellyfinAdapter.kt:904-912](file:///E:/temp/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/backend/impl/JellyfinAdapter.kt#L904)）：
1. 歌曲 `ImageTags.Primary` tag 存在 → `buildCoverUrl(songId, imageTag)`
2. tag 不存在但有 `albumId` → `getCoverUrl(albumId)`（**不带 tag 可能 404**）
3. `albumId` 也为空 → `getCoverUrl(songId)`（Jellyfin 返回 404 或默认图）
- **无艺术家封面 fallback**

**NavidromeAdapter**（[NavidromeAdapter.kt:151-159](file:///E:/temp/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/backend/impl/NavidromeAdapter.kt#L151)）：
- `coverArt` 字段为空时直接返回 `null`，**完全无 fallback**

**NowPlayingScreen**（[NowPlayingScreen.kt:341-378](file:///E:/temp/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/NowPlayingScreen.kt#L341)）：
- attempt 1 和 2 都替换为 Backdrop，**逻辑重复**，等于只有 2 级 fallback
- 没有专辑/艺术家兜底

### 2.3 方案：统一候选列表 + 多封面轮播

**核心思路**：后端提供"候选封面 URL 列表"（按优先级排序），UI 层用统一的 `CoverCarousel` 组件轮播展示，每张图加载失败时自动降级到下一张。

#### 轮播时长设计

**推荐：10 秒/张**

| 时长 | 体验 | 评价 |
|------|------|------|
| 3-5 秒 | 切换太频繁，干扰沉浸感 | ❌ 太快 |
| **10 秒** | 一首 3-4 分钟的歌轮播 2-3 张图，节奏舒适 | ✅ 推荐 |
| 15-20 秒 | 一首歌可能只看到 1-2 张，轮播感弱 | 偏慢 |
| 30 秒 | 几乎等于静态展示 | ❌ 太慢 |

**10 秒依据**：
1. 流行歌曲 3-4 分钟，10 秒/张 → 一首歌轮播 18-24 次（3 张图循环），节奏不腻
2. 10 秒足够看清一张封面细节，又不会觉得静止无聊
3. TV 是远距离观看（3-5 米），频繁切换会造成视觉疲劳，10 秒是远距离观看的舒适区间
4. 行业参考：Apple Music TV、网易云音乐的"歌手主页"轮播多为 8-12 秒

**轮播规则**：
- 只有 1 张封面：静态显示，不启动定时器
- 多张封面：10 秒/张循环切换
- **暂停播放时停止轮播**，保持当前封面定格
- 切歌时立即显示第一张，重新开始计时
- 当前 URL 加载失败：自动跳到候选列表下一项（内层 fallback）

#### 优先级设计

```
1. 歌曲封面（songId/Primary tag）   ← 最精确，但大多数歌曲没有独立封面
2. 专辑封面（albumId）               ← 最常见，专辑封面是音乐库的主力封面
3. 艺术家封面（artistId）            ← 兜底，至少有个人/乐队照片
4. ♪ 占位符                          ← 最后兜底
```

歌曲封面优先级最高但实际命中率最低：Jellyfin 中歌曲的 `ImageTags.Primary` 通常为空（音乐文件内嵌封面图归到专辑），但少数歌曲有独立封面（如单曲 EP），这种应该优先用。

#### 修改 1：JellyfinAdapter 补充 artistId 解析

文件：`backend/impl/JellyfinAdapter.kt`

```kotlin
// jsonObjectToSong 中补充解析 ArtistItems 数组取第一个 artistId
val artistItems = obj.getAsJsonArray("ArtistItems")
val artistId = artistItems?.firstOrNull()?.asJsonObject?.get("Id")?.asString

return Song(
    // ... 现有字段
    artistId = artistId,  // 新增填充
    // ...
)
```

#### 修改 2：BackendAdapter 新增 getCoverUrlCandidates 方法

文件：`backend/BackendAdapter.kt`

```kotlin
interface BackendAdapter {
    // ... 现有方法

    /**
     * 获取歌曲的候选封面 URL 列表，按优先级排序。
     * UI 层依次尝试/轮播，第一个加载成功的即为可用封面。
     * 返回空列表表示无可用封面。
     */
    fun getCoverUrlCandidates(song: Song): List<String>
}
```

#### 修改 3：JellyfinAdapter 实现 getCoverUrlCandidates

文件：`backend/impl/JellyfinAdapter.kt`

```kotlin
override fun getCoverUrlCandidates(song: Song): List<String> {
    val urls = mutableListOf<String>()
    // 1. 歌曲封面（已含 tag 的精确 URL，或无 tag 的 Primary URL）
    song.coverUrl?.let { urls.add(it) }
    // 2. 专辑封面
    if (!song.albumId.isNullOrBlank()) {
        urls.add("$baseUrl/Items/${song.albumId}/Images/Primary?maxWidth=512&quality=90&api_key=$apiToken")
    }
    // 3. 艺术家封面
    if (!song.artistId.isNullOrBlank()) {
        urls.add("$baseUrl/Items/${song.artistId}/Images/Primary?maxWidth=512&quality=90&api_key=$apiToken")
    }
    return urls.distinct().filter { it.isNotBlank() }
}
```

#### 修改 4：NavidromeAdapter 实现 getCoverUrlCandidates

文件：`backend/impl/NavidromeAdapter.kt`

```kotlin
override fun getCoverUrlCandidates(song: Song): List<String> {
    val urls = mutableListOf<String>()
    // 1. 歌曲/专辑封面（coverArt 字段或 albumId fallback）
    song.coverUrl?.let { urls.add(it) }
    // albumId 在 Subsonic 中也是合法的 coverArt id
    song.albumId?.takeIf { it.isNotBlank() }?.let {
        val albumCoverUrl = buildCoverUrl(it)
        if (albumCoverUrl !in urls) urls.add(albumCoverUrl)
    }
    // 2. 艺术家封面
    song.artistId?.takeIf { it.isNotBlank() }?.let {
        urls.add(buildCoverUrl(it))
    }
    return urls.distinct().filter { it.isNotBlank() }
}
```

#### 修改 5：新增 CoverCarousel 组件

文件：`ui/components/CoverCarousel.kt`（新建）

```kotlin
/**
 * 封面轮播组件
 *
 * - 多张封面时每 10 秒切换一张
 * - 仅播放时轮播，暂停时定格
 * - 单张封面时静态显示
 * - 当前 URL 加载失败自动尝试候选列表下一项
 */
@Composable
fun CoverCarousel(
    coverCandidates: List<String>,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onAllFailed: () -> Unit = {}  // 所有候选都失败时回调（显示占位符）
) {
    if (coverCandidates.isEmpty()) {
        onAllFailed()
        return
    }
    if (coverCandidates.size == 1) {
        // 单张静态显示（保留 onError fallback）
        AsyncImage(
            model = coverCandidates[0],
            contentDescription = "Album Cover",
            modifier = modifier.fillMaxSize(),
            onError = { onAllFailed() }
        )
        return
    }

    var carouselIndex by remember(coverCandidates) { mutableStateOf(0) }
    var fallbackIndex by remember { mutableStateOf(0) }

    // 仅播放时轮播
    LaunchedEffect(isPlaying, coverCandidates) {
        if (isPlaying) {
            while (true) {
                delay(10_000)  // 10 秒/张
                carouselIndex = (carouselIndex + 1) % coverCandidates.size
                fallbackIndex = 0  // 重置内层 fallback
            }
        }
    }

    val effectiveIndex = (carouselIndex + fallbackIndex) % coverCandidates.size
    val effectiveUrl = coverCandidates.getOrNull(effectiveIndex)

    if (effectiveUrl != null) {
        key(effectiveIndex) {
            AsyncImage(
                model = effectiveUrl,
                contentDescription = "Album Cover",
                modifier = modifier.fillMaxSize(),
                onError = {
                    // 当前 URL 失败，尝试候选列表下一项
                    if (fallbackIndex < coverCandidates.size - 1) {
                        fallbackIndex++
                    } else {
                        onAllFailed()
                    }
                }
            )
        }
    } else {
        onAllFailed()
    }
}
```

#### 修改 6：NowPlayingScreen 用 CoverCarousel 替换现有封面逻辑

文件：`ui/screens/NowPlayingScreen.kt`

替换 [NowPlayingScreen.kt:341-378](file:///E:/temp/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/NowPlayingScreen.kt#L341) 的 3 级 fallback 逻辑：

```kotlin
// 替换为 CoverCarousel
val coverCandidates = remember(currentSong?.id) {
    // 通过 ViewModel 获取候选列表（NAS 歌曲后端 3 类 + 网络封面，网络歌曲 1 张）
    coverCandidatesProvider(currentSong)  // 由 ViewModel 提供的 lambda
}

CoverCarousel(
    coverCandidates = coverCandidates,
    isPlaying = isPlaying,
    modifier = Modifier.fillMaxSize(),
    onAllFailed = {
        // 显示音符占位符
    }
)
```

#### 修改 7：MainViewModel 提供 coverCandidatesProvider

文件：`ui/viewmodel/MainViewModel.kt`

```kotlin
// 新增网络封面状态
private val _networkCoverUrl = MutableStateFlow<String?>(null)
val networkCoverUrl: StateFlow<String?> = _networkCoverUrl.asStateFlow()

/**
 * 获取歌曲的候选封面 URL 列表（统一入口，不区分 NAS/网络歌曲）
 */
fun getCoverCandidates(song: Song): List<String> {
    val candidates = mutableListOf<String>()

    if (song.isNetworkSong) {
        // 网络歌曲：只有 1 张 pic 封面
        song.coverUrl?.let { candidates.add(it) }
    } else {
        // NAS 歌曲：后端 3 类封面
        val adapter = backendRegistry.getAdapter()
        if (adapter != null) {
            candidates.addAll(adapter.getCoverUrlCandidates(song))
        }
        // 如果有网络封面（切换网络歌词时获取），追加到列表
        _networkCoverUrl.value?.let { candidates.add(it) }
    }

    return candidates.distinct().filter { it.isNotBlank() }
}
```

**预估影响范围**：
- 文件：`backend/BackendAdapter.kt`（新增接口方法）
- 文件：`backend/impl/JellyfinAdapter.kt`（实现 + artistId 解析）
- 文件：`backend/impl/NavidromeAdapter.kt`（实现）
- 文件：`ui/components/CoverCarousel.kt`（新建）
- 文件：`ui/screens/NowPlayingScreen.kt`（替换封面逻辑）
- 文件：`ui/viewmodel/MainViewModel.kt`（新增 getCoverCandidates + networkCoverUrl 状态）
- 风险：中。CoverCarousel 是新组件，需验证轮播和 fallback 逻辑

---

## 三、问题三：切换网络歌词时联动获取网络封面图

### 3.1 现象

当前切换歌词来源（如从"内嵌"切到"在线歌词"）时，只切换歌词，封面图不联动。对于 NAS 歌曲，如果后端封面加载失败或为空，用户切换到"在线歌词"获取到了网络歌词，但封面图仍是后端的（可能失效）。

### 3.2 现状分析

- `switchLyricsSource()`（[MainViewModel.kt:1197-1210](file:///E:/temp/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/viewmodel/MainViewModel.kt#L1197)）只切换歌词，不触发封面图更新
- 网络歌曲的 coverUrl 直接用搜索结果的 `pic` 字段（[MetingApiService.kt:343](file:///E:/temp/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/backend/network/MetingApiService.kt#L343)）
- `NetworkMusicManager.resolveCoverUrl()` 始终返回 null（[MetingApiService.kt:285](file:///E:/temp/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/backend/network/MetingApiService.kt#L285)），因为 pic 字段已是可用 URL
- **核心问题**：NAS 歌曲切换到网络歌词来源时，没有机制获取网络封面图参与轮播

### 3.3 统一轮播框架下的网络封面联动

**与问题二的统一框架关系**：

问题二建立了统一的 `CoverCarousel` 组件，候选列表由 `MainViewModel.getCoverCandidates()` 组装。问题三的核心是：**NAS 歌曲切换到"在线歌词"时，获取网络封面并加入候选列表，触发轮播**。

各场景轮播效果：

| 场景 | 候选封面数 | 轮播效果 |
|------|-----------|---------|
| NAS 歌曲，默认（后端歌词） | 1-3 张（后端） | 后端封面轮播 |
| NAS 歌曲，切到在线歌词 | 2-4 张（后端+网络） | 后端+网络封面轮播 |
| NAS 歌曲，切回内嵌歌词 | 1-3 张（后端，网络封面清除） | 后端封面轮播 |
| 网络歌曲 | 1 张（pic） | 静态显示，不轮播 |

**网络封面生命周期**：
- 获取时机：切换到"在线歌词"来源且歌词获取成功
- 清除时机：切换回"内嵌"来源（让封面来源与歌词来源保持语义一致）
- 存储：`_networkCoverUrl` StateFlow，`getCoverCandidates` 自动读取

### 3.4 修改方案

#### 修改 1：MetingApiService 新增 searchCoverUrl 方法

文件：`backend/network/MetingApiService.kt`

```kotlin
/**
 * 按标题+艺术家搜索第一首匹配歌曲的封面 URL。
 * 用于 NAS 歌曲切换到网络歌词时，联动获取网络封面图加入轮播。
 */
suspend fun searchCoverUrl(title: String, artist: String): String? {
    val keyword = if (artist.isNotBlank()) "$title $artist" else title
    val items = search(keyword)  // 复用现有搜索逻辑
    return items.firstOrNull()?.coverUrl
}
```

#### 修改 2：NetworkMusicManager 暴露 searchCoverUrl

文件：`backend/network/NetworkMusicManager.kt`

```kotlin
suspend fun searchCoverUrl(title: String, artist: String): String? {
    val src = getDefaultSource()
    val svc = services[src] ?: return null
    return try {
        svc.searchCoverUrl(title, artist)
    } catch (e: Exception) {
        AppLog.w(TAG, "searchCoverUrl error: ${e.message}", e)
        null
    }
}
```

#### 修改 3：MainViewModel.switchLyricsSource 联动网络封面

文件：`ui/viewmodel/MainViewModel.kt`

```kotlin
fun switchLyricsSource(source: LyricsSource) {
    val song = currentSong.value ?: return
    AppLog.d("NASMusic", "switchLyricsSource: $source")
    viewModelScope.launch {
        try {
            val lyrics = lyricsManager.getLyricsFromSource(song, source)
            _currentLyrics.value = lyrics
            AppLog.d("NASMusic", "switchLyricsSource: source=${lyrics?.source}, lines=${lyrics?.lines?.size}")

            // 联动网络封面：切换到在线歌词时获取，切回内嵌时清除
            if (source == LyricsSource.NETWORK && lyrics != null && !song.isNetworkSong) {
                val networkCover = nasMusicApp.networkMusicManager.searchCoverUrl(song.title, song.artist)
                _networkCoverUrl.value = networkCover
                AppLog.d("NASMusic", "switchLyricsSource: 网络封面=${networkCover?.take(60)}")
            } else {
                // 切回内嵌/本地文件来源，清除网络封面
                _networkCoverUrl.value = null
            }
        } catch (e: Exception) {
            android.util.Log.e("NASMusic", "switchLyricsSource failed", e)
            showError("切换歌词来源失败: ${e.message?.take(50)}")
        }
    }
}
```

**说明**：与之前方案不同，不再直接修改 `currentSong.coverUrl`，而是通过 `_networkCoverUrl` StateFlow 让 `getCoverCandidates()` 自动组装候选列表，由 `CoverCarousel` 组件轮播。这样：
- 不破坏后端封面数据（`currentSong.coverUrl` 保持后端原始 URL）
- 网络封面作为候选列表追加项，参与轮播
- 切回内嵌歌词时清除网络封面，候选列表恢复纯后端

#### 修改 4：LyricsManager.getLyricsFromSource 修复网络歌曲 EMBEDDED 路径

文件：`lyrics/LyricsManager.kt`

[LyricsManager.kt:129-155](file:///E:/temp/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/lyrics/LyricsManager.kt#L129) 中，`EMBEDDED` 分支对网络歌曲走错了路径。修复：

```kotlin
LyricsSource.EMBEDDED -> {
    if (song.isNetworkSong && networkMusicManager != null) {
        // 网络歌曲的"内嵌"歌词走 NetworkMusicManager
        val text = networkMusicManager.resolveLyrics(song)
        if (!text.isNullOrBlank() && LrcParser.isValidLrc(text)) {
            LrcParser.parse(text, song.id).copy(source = LyricsSource.EMBEDDED)
        } else null
    } else {
        val adapter = backendRegistry.getAdapter()
        adapter?.getLyrics(song.id) ...
    }
}
```

#### 修改 5：PlayerManager 新增 updateQueueSong 方法（备用）

文件：`player/PlayerManager.kt`

```kotlin
/**
 * 更新队列中指定位置的歌曲对象（用于封面图等字段更新）。
 * 不影响 ExoPlayer 的 MediaItem，仅更新 _queue StateFlow。
 */
fun updateQueueSong(index: Int, song: Song) {
    val currentQueue = _queue.value.toMutableList()
    if (index in currentQueue.indices) {
        currentQueue[index] = song
        _queue.value = currentQueue
    }
}
```

**预估影响范围**：
- 文件：`backend/network/MetingApiService.kt`（新增 searchCoverUrl）
- 文件：`backend/network/NetworkMusicManager.kt`（暴露 searchCoverUrl）
- 文件：`ui/viewmodel/MainViewModel.kt`（switchLyricsSource 联动 + networkCoverUrl 状态）
- 文件：`lyrics/LyricsManager.kt`（修复网络歌曲 EMBEDDED 路径）
- 文件：`player/PlayerManager.kt`（新增 updateQueueSong，备用）
- 风险：中。网络封面搜索有延迟，需异步处理不阻塞歌词切换

---

## 四、网络歌曲封面兼容性说明

### 网络歌曲的封面限制

[MetingApiService.kt:322-343](file:///E:/temp/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/backend/network/MetingApiService.kt#L322) 搜索返回的字段：

| 字段 | 内容 | 是否可用 |
|------|------|---------|
| `pic` | 封面端点 URL（302 到真实图） | ✅ 已用作 `coverUrl` |
| `album` | Meting-API **不返回**专辑信息 | ❌ 无专辑封面 |
| `artistId` | Meting-API **不返回**艺术家 id | ❌ 无艺术家封面 |

**结论**：网络歌曲只有 1 张封面（`pic` 字段），候选列表只有 1 项，`CoverCarousel` 自动降级为静态显示。统一轮播框架对网络歌曲透明兼容，无需特殊处理。

### 统一轮播框架的兼容性

`MainViewModel.getCoverCandidates()` 统一入口设计：

- **NAS 歌曲**：后端 3 类封面 + 网络封面（切在线歌词时追加）→ 2-4 张轮播
- **网络歌曲**：1 张 pic 封面 → 静态显示
- **框架一致性**：`CoverCarousel` 组件不区分歌曲类型，只看候选列表数量

---

## 五、修改优先级与依赖

| 序号 | 修改项 | 优先级 | 依赖 | 风险 |
|------|--------|--------|------|------|
| 1.1 | LyricsView 独立高频时钟 | 高 | 无 | 低 |
| 2.1 | JellyfinAdapter 补充 artistId 解析 | 高 | 无 | 低 |
| 2.2 | BackendAdapter 新增 getCoverUrlCandidates 接口 + 两个实现 | 高 | 2.1 | 低 |
| 2.3 | 新建 CoverCarousel 组件 | 高 | 2.2 | 中 |
| 2.4 | NowPlayingScreen 替换为 CoverCarousel | 高 | 2.3 | 中 |
| 2.5 | MainViewModel getCoverCandidates + networkCoverUrl 状态 | 高 | 2.2 | 低 |
| 3.1 | MetingApiService searchCoverUrl | 中 | 无 | 低 |
| 3.2 | NetworkMusicManager 暴露 searchCoverUrl | 中 | 3.1 | 低 |
| 3.3 | switchLyricsSource 联动网络封面 | 中 | 2.5 + 3.2 | 中 |
| 3.4 | LyricsManager 修复 EMBEDDED 路径 | 中 | 无 | 低 |
| 3.5 | PlayerManager updateQueueSong（备用） | 低 | 无 | 低 |

**建议实施顺序**：
1. **第一阶段**（低风险见效快）：1.1 逐字歌词刷新
2. **第二阶段**（封面轮播核心）：2.1 → 2.2 → 2.5 → 2.3 → 2.4，完成统一轮播框架
3. **第三阶段**（网络封面联动）：3.1 → 3.2 → 3.3 → 3.4，让 NAS 歌曲切在线歌词时追加网络封面

---

## 六、测试验证点

### 逐字歌词
- [x] 逐字高亮流畅，无跳动（50ms 刷新）
- [x] 逐行高亮模式不受影响（仍是 1000ms）
- [x] seek 后逐字高亮位置正确
- [x] 暂停时逐字高亮停止刷新

### 封面轮播（NAS 歌曲）
- [ ] Jellyfin 歌曲有 songId tag → 歌曲封面优先显示
- [ ] Jellyfin 歌曲无 tag → 正确 fallback 到专辑封面
- [ ] Jellyfin 专辑封面也失败 → fallback 到艺术家封面
- [ ] Navidrome coverArt 为空 → 正确 fallback 到专辑封面
- [ ] 所有封面都失败 → 显示音符占位符
- [ ] 多张封面时 10 秒切换一次
- [ ] 暂停播放时封面定格，不轮播
- [ ] 切歌时立即显示第一张，重新计时
- [ ] 单张封面时静态显示，不启动定时器
- [ ] artistId 正确解析（Jellyfin）

### 网络歌词联动封面
- [ ] NAS 歌曲切换到"在线歌词"后，网络封面加入候选列表参与轮播
- [ ] 网络封面搜索失败时，保持原后端封面候选列表，不报错
- [ ] 切回"内嵌"歌词来源时，网络封面从候选列表移除
- [ ] 网络歌曲切换歌词来源，封面图不变（已用 pic 字段，候选列表 1 张）
- [ ] 网络歌曲切换歌词来源能正确获取歌词（EMBEDDED 路径修复）

### 网络歌曲兼容
- [x] 网络歌曲封面正常显示（单张静态）
- [x] 网络歌曲不触发轮播定时器
- [x] 统一轮播框架对网络歌曲透明

---

## 七、v2.4.1 后续修复（2026-06-26）

三项优化合并后，在实机/模拟器测试中发现以下衍生问题，已在 v2.4.1 内一并修复：

### 7.1 设置页左侧导航栏无法滚动

**现象**：设置页左侧分区列表在模拟器上显示不全，遥控器上下键无法向下推进。

**根因**：`SettingsScreen` 左侧栏用普通 `Column`（不可滚动），6 个 `SettingsSection` + 头部超出可视高度被裁切，焦点移动到不可见项时无滚动机制带入视图。

**修复**：左侧 `Column` 添加 `.verticalScroll(rememberScrollState())`。Compose 的 `BringIntoView` 机制在焦点移动时自动滚动到目标项。

### 7.2 关于页版本号滞后

**现象**：发布 2.4.1 后，关于页仍显示 2.4.0。

**根因**：版本号在两处硬编码——`build.gradle.kts` 的 `versionName`/`versionCode` 与 `NasMusicVersion.kt` 的 `VERSION_NAME`/`VERSION_CODE`，发版时漏改后者。关于页读 `NasMusicVersion.DISPLAY`，所以显示旧版本。

**修复**：`NasMusicVersion.VERSION_NAME`/`VERSION_CODE` 改为 `val get() = BuildConfig.VERSION_NAME`/`VERSION_CODE`（AGP 已启用 `buildConfig = true`），`build.gradle.kts` 成为唯一来源，代码侧自动同步。

### 7.3 切换页面后歌词高亮模式丢失

**现象**：播放页切到逐字高亮 → 进设置页 → 返回播放页，高亮模式变回逐行。

**根因**：`NowPlayingScreen` 用 `remember` 保存 `highlightMode`。`AppRoot` 用 `when (currentScreen)` 切换页面，离开的页面离开 composition，`remember` 状态丢失。返回时重置为默认 `LINE_BY_LINE`，而 `LaunchedEffect(lyrics)` 只在歌词含逐字时间戳时才自动切回 `WORD_BY_WORD`——标准 LRC 歌词（用户手动切到逐字）不会触发，所以变回逐行。`rememberSaveable` 同样无效：没有 NavHost back stack entry 托管 saveable state。

**修复**：按项目约定（搜索状态、歌词来源等均提升到 ViewModel），将 `lyricsHighlightMode` 提升到 `MainViewModel` StateFlow + `setLyricsHighlightMode(mode)` 方法；`NowPlayingScreen` 的 `highlightMode` 改为外部参数 + `onChangeHighlightMode` 回调；`loadLyricsForCurrentSong` 加载歌词后含逐字时间戳则自动切 `WORD_BY_WORD`，否则保留用户上次选择。`LyricsLine.kt` 的 `LyricsHighlightMode.Saver` 回退。
