# 网络搜索歌曲功能 — 技术方案 v2

## 一、方案概述

在现有 NAS 音乐播放器基础上，增加网络歌曲搜索与播放功能。本方案经过架构审阅后修订，主要解决双模型冲突、歌词加载冲突、Meting-API 端点和直联区分、拼音搜索预期对齐等问题。

---

## 二、架构设计

```
                                        现有层
    ┌─────────────────────────────────────────────────────────┐
    │  NasMusicApp (手动 DI 容器)                                │
    │  ├─ backendRegistry: BackendRegistry     ← 已有           │
    │  ├─ appPreferences: AppPreferences       ← 已有           │
    │  ├─ playerManager: PlayerManager         ← 已有           │
    │  └─ networkMusicManager: NetworkMusicManager  ← 新增      │
    └─────────────────────────────────────────────────────────┘

                                         新增层
    ┌─────────────────────────────────────────────────────────┐
    │  NetworkMusicManager (单例，在 NasMusicApp.onCreate 初始化) │
    │  ├─ services: Map<String, NetworkMusicService>             │
    │  ├─ getDefaultSource() / setDefaultSource()                │
    │  ├─ search(keyword) → List<Song>                      │
    │  ├─ resolvePlayUrl(song) → String? (播放时实时解析)          │
    │  ├─ resolveCoverUrl(song) → String? (封面URL，通常用song.coverUrl) │
    │  └─ resolveLyrics(song) → String?                          │
    ├─────────────────────────────────────────────────────────┤
    │  NetworkMusicService (接口)                                │
    │  ├─ MetingApiServiceImpl (首选)                            │
    │  ├─ AlApiServiceImpl (备选)                                │
    │  └─ JioSaavnServiceImpl (补充)                             │
    ├─────────────────────────────────────────────────────────┤
    │  AppPreferences.kt 扩展 (DataStore 网络收藏存储)          │
    └─────────────────────────────────────────────────────────┘

                                         融合层
    ┌─────────────────────────────────────────────────────────┐
    │  MainViewModel                                           │
    │  ├─ searchNetworkSongs(keyword) ← 搜索网络               │
    │  ├─ playSong(song) ← 已有方法，直播网络 Song               │
    │  ├─ toggleNetworkFavorite(song) ← 收藏状态切换            │
    │  └─ loadLyrics() ← 修改后识别网络歌曲                     │
    └─────────────────────────────────────────────────────────┘
    ┌─────────────────────────────────────────────────────────┐
    │  LibraryScreen                                          │
    │  └─ Network Tab (始终可用，不依赖 NAS 连接状态)            │
    └─────────────────────────────────────────────────────────┘
```

### 关键设计决策

**放弃双模型**：不创建 `NetworkSong`，所有内容以统一的 `Song` 模型承载，新增 `isNetworkSong` 和 `networkSource` 两个字段区分来源。

**播放和歌词不走弯路**：`PlayerManager.playSong()` 传入的 `Song` 对象的 `streamUrl` 就是播放链接，`LyricsManager` 感知 `isNetworkSong` 时直接走网络歌词获取，不经过 `BackendAdapter`。

---

## 三、核心数据模型变更

### 3.1 Song 模型扩展

```kotlin
// data/model/Song.kt
data class Song(
    val id: String,
    val title: String,
    val artist: String = "",
    val artistId: String? = null,
    val album: String = "",
    val albumId: String? = null,
    val coverUrl: String? = null,
    val streamUrl: String? = null,
    val durationMs: Long = 0L,
    val trackNumber: Int = 0,
    val discNumber: Int = 1,
    val year: Int? = null,
    val genre: String? = null,
    val bitrate: Int = 0,
    // ↓↓↓ 网络歌曲扩展（新增，默认值保持向后兼容）↓↓↓
    val isNetworkSong: Boolean = false,
    val networkSource: String? = null,   // "meting" | "alapi" | "jiosaavn"
    val networkId: String? = null          // 在源平台的原始 ID
)
```

**说明**：
- `id` 格式：网络歌曲为 `"ntwk_${source}_${sourceId}"`（如 `"ntwk_meting_12345"`），NAS 歌曲保持不变
- `streamUrl`：网络歌曲播放时在 `playNetworkSong()` 中解析赋值，不持久化
- `coverUrl`：Meting-API 返回的是**端点 URL**（`/api?type=pic&id=xxx`），Coil 可自动处理 302，但搜索响应中的 `pic` URL 也可直接用作封面

### 3.2 AppSettings 扩展

```kotlin
// data/model/AppSettings.kt
data class AppSettings(
    val darkTheme: Boolean = true,
    val animationsEnabled: Boolean = true,
    val autoPlayNext: Boolean = true,
    val defaultPlayMode: PlayMode = PlayMode.SEQUENTIAL,
    val cacheLyrics: Boolean = true,
    val cacheCover: Boolean = true,
    val lyricsOffsetMs: Long = 0L,
    // ↓↓↓ 网络音乐设置（新增）↓↓↓
    val defaultNetworkSource: String = "meting"
)
```

---

## 四、网络音乐服务层

### 4.1 接口定义

```kotlin
// backend/network/NetworkMusicService.kt
interface NetworkMusicService {
    /** 来源标识 "meting" / "alapi" / "jiosaavn" */
    val sourceId: String

    /** 搜索歌曲（limit 由各实现自行控制，接口层不暴露） */
    suspend fun search(keyword: String): List<Song>

    /**
     * 解析播放链接
     * Meting-API 返回的是 302 端点，需要跟随重定向拿到直联 URL；
     * 部分 API 可能在 search 结果中直接返回。
     */
    suspend fun resolvePlayUrl(song: Song): String?

    /** 获取歌词（可为空，回退到 LyricsNetworkProvider） */
    suspend fun resolveLyrics(song: Song): String?

    /** 获取封面 URL（如不需要额外解析则返回 null，使用 song.coverUrl） */
    suspend fun resolveCoverUrl(song: Song): String? = null
}
```

### 4.2 Meting-API 实现

> **v2.2.0 适配**：
> - OkHttpClient 使用守护线程池（`isDaemon = true`），防止阻止进程退出（与 JellyfinAdapter/NavidromeAdapter 一致）
> - 日志使用 `AppLog`（Release 构建自动抑制调试日志），不用 `Log.w`
> - JSON 解析统一用 Gson（与现有 Adapter 一致），不用 `org.json.JSONArray`

```kotlin
// backend/network/MetingApiService.kt
class MetingApiService : NetworkMusicService {

    override val sourceId = "meting"

    /**
     * 守护线程池：防止 OkHttp 非守护线程阻止进程退出
     * 与 JellyfinAdapter / NavidromeAdapter 保持一致
     */
    private val daemonExecutor = java.util.concurrent.Executors.newCachedThreadPool { r ->
        Thread(r, "Meting-OkHttp").apply { isDaemon = true }
    }

    private val client = OkHttpClient.Builder()
        .dispatcher(okhttp3.Dispatcher(daemonExecutor))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 不跟随重定向，用于获取 302 Location */
    private val noRedirectClient = OkHttpClient.Builder()
        .dispatcher(okhttp3.Dispatcher(daemonExecutor))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    private val gson = com.google.gson.Gson()

    companion object {
        private const val BASE = "https://meting.mikus.ink/api"
    }

    /**
     * 搜索歌曲
     *
     * 注意：Meting-API 搜索响应中的 url / pic / lrc 字段是 API 端点，不是直联 URL。
     * url → 需要再请求获取 302 Location（real mp3 URL）
     * pic → Coil 自动处理 302，可以直接用端点 URL
     * lrc → 实际就是歌词文本
     */
    override suspend fun search(keyword: String): List<Song> {
        return withContext(Dispatchers.IO) {
            try {
                val url = "$BASE?server=netease&type=search&id=${URLEncoder.encode(keyword, "UTF-8")}"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: return@withContext emptyList()
                parseSongs(body)
            } catch (e: Exception) {
                AppLog.w("MetingApiService", "search failed", e)
                emptyList()
            }
        }
    }

    /**
     * 解析播放链接
     * Meting-API 返回 302 重定向，需要获取 Location header
     */
    override suspend fun resolvePlayUrl(song: Song): String? {
        val netId = song.networkId ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val url = "$BASE?server=netease&type=url&id=$netId"
                val request = Request.Builder().url(url).build()
                val response = noRedirectClient.newCall(request).execute()
                when (response.code) {
                    302 -> response.header("Location")
                    else -> response.body?.string()
                }
            } catch (e: Exception) {
                AppLog.w("MetingApiService", "resolvePlayUrl failed", e)
                null
            }
        }
    }

    /**
     * 获取歌词
     * Meting-API 的 lrc 端点直接返回 LRC 文本
     */
    override suspend fun resolveLyrics(song: Song): String? {
        val netId = song.networkId ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val url = "$BASE?server=netease&type=lrc&id=$netId"
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()
                response.body?.string()
            } catch (e: Exception) {
                AppLog.w("MetingApiService", "resolveLyrics failed", e)
                null
            }
        }
    }

    /**
     * 封面 URL：直接用搜索返回的端点 URL，Coil 处理 302
     */
    override suspend fun resolveCoverUrl(song: Song): String? {
        val netId = song.networkId ?: return null
        return "$BASE?server=netease&type=pic&id=$netId"
    }

    private fun parseSongs(json: String): List<Song> {
        return try {
            // 使用 Gson 解析，与现有 Adapter 一致
            val arrType = object : com.google.gson.reflect.TypeToken<List<MetingSongItem>>() {}.type
            val items: List<MetingSongItem> = gson.fromJson(json, arrType) ?: emptyList()
            items.map { item ->
                Song(
                    id = "ntwk_meting_${item.id}",
                    title = item.name,
                    artist = item.artist ?: "",
                    album = item.album ?: "",
                    // 端点 URL，Coil 可跟随 302
                    coverUrl = item.pic?.takeIf { it.isNotBlank() },
                    isNetworkSong = true,
                    networkSource = "meting",
                    networkId = item.id
                )
            }
        } catch (e: Exception) {
            AppLog.w("MetingApiService", "parseSongs failed", e)
            emptyList()
        }
    }

    /** Meting-API 响应数据模型（Gson 解析用） */
    private data class MetingSongItem(
        val id: String,
        val name: String,
        val artist: String? = null,
        val album: String? = null,
        val pic: String? = null
        // 注：响应中还包含 url / lrc 字段，均为端点 URL（需二次请求），
        // 本 data class 故意不接收，由 resolvePlayUrl() / resolveLyrics() 单独处理
    )
}
```

### 4.3 多来源容错说明

用户配置了默认源（如 "meting"），搜索时**优先使用默认源**。如果默认源返回空列表或抛出异常，则按固定顺序自动回退到其他源（alapi → jiosaavn）。

- 设置页面可以随时切换默认源，改变优先尝试的源
- 自动 fallback 是内置行为，不需要用户手动触发
- 如果所有源都失败，返回空列表并在 UI 提示用户

---

## 五、网络音乐管理器

> **v2.2.0 适配**：通过 `NasMusicApp` 手动 DI 容器注入，不再使用 `AppPreferences.getInstance()`（v2.2.0 已移除）。

```kotlin
// backend/network/NetworkMusicManager.kt
class NetworkMusicManager(
    private val prefs: AppPreferences  // 由 NasMusicApp 注入
) {

    private val services: Map<String, NetworkMusicService> = mapOf(
        "meting" to MetingApiService(),
        "alapi" to AlApiService(),
        "jiosaavn" to JioSaavnService()
    )

    // 复用 LyricsNetworkProvider 实例（避免每次歌词解析都新建 OkHttpClient）
    private val lyricsFallback = LyricsNetworkProvider()

    /** 所有可用源 */
    fun getAvailableSources(): List<String> = services.keys.toList()

    /** 当前默认源 */
    fun getDefaultSource(): String = prefs.getDefaultNetworkSourceSync()

    /** 设置默认源 */
    suspend fun setDefaultSource(source: String) = prefs.setDefaultNetworkSource(source)

    /** 搜索（默认源优先，失败时按优先级依次回退） */
    suspend fun search(keyword: String): List<Song> {
        val orderedSources = buildSourcePriorityOrder()
        for (source in orderedSources) {
            val service = services[source] ?: continue
            try {
                val results = service.search(keyword)
                if (results.isNotEmpty()) return results
            } catch (e: Exception) {
                AppLog.w("NetworkMusicManager", "search failed for source=$source", e)
            }
        }
        return emptyList()
    }

    /** 构造源优先级：默认源 → 其他源（固定 fallback 顺序） */
    private fun buildSourcePriorityOrder(): List<String> {
        val default = getDefaultSource()
        val fallbackOrder = listOf("meting", "alapi", "jiosaavn")
        return listOf(default) + fallbackOrder.filter { it != default }
    }

    /** 解析播放链接（按歌曲的 networkSource 路由，不回退） */
    suspend fun resolvePlayUrl(song: Song): String? {
        // 不实现多源回退：每首歌曲有明确的 networkSource（创建时即确定），
        // 若该源失败，尝试其他源会拿到错误歌曲的链接，无意义。
        // 用户可通过设置页切换默认源，影响后续搜索结果的网络来源。
        val src = song.networkSource ?: return null
        return services[src]?.resolvePlayUrl(song)
    }

    /** 解析封面 URL（按歌曲的 networkSource 路由，返回 null 时使用 song.coverUrl） */
    suspend fun resolveCoverUrl(song: Song): String? {
        val src = song.networkSource ?: return null
        return services[src]?.resolveCoverUrl(song)
    }

    /** 获取歌词文本（失败时回退到现有 LyricsNetworkProvider） */
    suspend fun resolveLyrics(song: Song): String? {
        val src = song.networkSource ?: return null
        return services[src]?.resolveLyrics(song)
            ?: lyricsFallback.fetchLyrics(song.title, song.artist) // fallback
    }
}
```

### 5.1 AppPreferences 扩展实现

> **依赖顺序**：本节依赖 3.2 节（`AppSettings` data class 添加 `defaultNetworkSource` 字段）。实施时需先扩展 `AppSettings`，再修改 `appSettings` Flow 映射。

`NetworkMusicManager` 依赖的 `getDefaultNetworkSourceSync()` 和 `setDefaultNetworkSource()` 方法需要在 `AppPreferences.kt` 中实现：

```kotlin
// data/prefs/AppPreferences.kt 中新增

// 1. 新增 PreferencesKey
private val keyDefaultNetworkSource = stringPreferencesKey("settings_default_network_source")

// 2. 在 appSettings Flow 映射中读取新字段（依赖 3.2 节已添加的 defaultNetworkSource）
val appSettings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
    AppSettings(
        darkTheme = prefs[keyDarkTheme] ?: true,
        animationsEnabled = prefs[keyAnimations] ?: true,
        autoPlayNext = prefs[keyAutoPlayNext] ?: true,
        defaultPlayMode = PlayMode.fromOrdinal(prefs[keyPlayMode] ?: 0),
        cacheLyrics = prefs[keyCacheLyrics] ?: true,
        cacheCover = prefs[keyCacheCover] ?: true,
        lyricsOffsetMs = prefs[keyLyricsOffset] ?: 0L,
        defaultNetworkSource = prefs[keyDefaultNetworkSource] ?: "meting"  // 新增（依赖 3.2）
    )
}

// 3. 新增同步获取方法（供 NetworkMusicManager 使用）
fun getDefaultNetworkSourceSync(): String {
    return runBlocking {
        try {
            context.dataStore.data.first().let { prefs ->
                prefs[keyDefaultNetworkSource] ?: "meting"
            }
        } catch (e: Exception) {
            "meting"
        }
    }
}

// 4. 新增设置方法
suspend fun setDefaultNetworkSource(source: String) {
    context.dataStore.edit { prefs ->
        prefs[keyDefaultNetworkSource] = source
    }
}
```

**说明**：
- 默认值为 `"meting"`，与 `AppSettings.defaultNetworkSource` 的默认值保持一致
- `getDefaultNetworkSourceSync()` 使用 `runBlocking` 同步读取，仅供初始化时使用
- `setDefaultNetworkSource()` 为 suspend 函数，符合 DataStore 异步写入规范

### 5.2 NasMusicApp 注册

```kotlin
// NasMusicApp.kt
class NasMusicApp : Application() {
    lateinit var backendRegistry: BackendRegistry
        private set
    lateinit var appPreferences: AppPreferences
        private set
    lateinit var playerManager: PlayerManager
        private set
    lateinit var networkMusicManager: NetworkMusicManager   // 新增
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        appPreferences = AppPreferences(this)
        backendRegistry = BackendRegistry()
        playerManager = PlayerManager()
        networkMusicManager = NetworkMusicManager(appPreferences)  // 新增
    }
    ...
}
```

### 5.3 MainViewModel 获取实例

```kotlin
// MainViewModel.kt
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val nasMusicApp = app as NasMusicApp
    private val playerManager = nasMusicApp.playerManager
    private val prefs = nasMusicApp.appPreferences
    private val backendRegistry = nasMusicApp.backendRegistry
    private val networkMusicManager = nasMusicApp.networkMusicManager  // 新增
    ...
}
```

---

## 六、歌词加载 — 消除冲突（关键修复）

### 6.1 问题根源

现有代码中（`MainViewModel.init()` 里的 `currentSong.collect` 块）：

```kotlin
// 监听 currentSong 变化，自动切歌时重新加载歌词
viewModelScope.launch {
    currentSong.collect { song ->
        if (song != null) {
            loadLyricsForCurrentSong()  // → 走 LyricsManager → BackendAdapter.getLyrics()
        }
    }
}
```

当播放网络歌曲时，这个 collector 会自动触发 `loadLyricsForCurrentSong()`。而 `playNetworkSong()` 中手动调用的 `loadNetworkLyrics()` 是第二次，会覆盖第一次结果。

### 6.2 修复方案

修改 `loadLyricsForCurrentSong()`，在 `try` 块开头加 `isNetworkSong` 分支：

```kotlin
// MainViewModel.kt
private fun loadLyricsForCurrentSong() {
    lyricsLoadJob?.cancel()
    _currentLyrics.value = null
    _lyricsAvailability.value = LyricsAvailability()
    val song = currentSong.value ?: return
    AppLog.d("NASMusic", "loadLyrics: loading for ${song.title} by ${song.artist}")
    lyricsLoadJob = viewModelScope.launch {
        try {
            if (song.isNetworkSong) {
                // 网络歌曲 → 不走 BackendAdapter，直接走服务层
                val text = networkMusicManager.resolveLyrics(song)
                if (text != null && LrcParser.isValidLrc(text)) {
                    // LrcParser.parse() 返回 Lyrics 对象，copy(source = ...) 设置来源
                    _currentLyrics.value = LrcParser.parse(text, song.id)
                        .copy(source = LyricsSource.NETWORK)
                }
            } else {
                // NAS 歌曲 → 原有逻辑（保持不变）
                val availability = lyricsManager.checkAvailability(song)
                _lyricsAvailability.value = availability
                val lyrics = availability.backend ?: availability.network
                _currentLyrics.value = lyrics
            }
        } catch (e: Exception) {
            AppLog.e("NASMusic", "loadLyrics failed", e)
            showError("加载歌词失败: ${e.message?.take(50)}")
        }
    }
}
```

**关键点**：
- 复用现有 `LyricsSource.NETWORK` 枚举项（已有 `"网络匹配"`），**不需要新增枚举**
- 网络歌曲分支不走 `lyricsManager.checkAvailability()`，避免触发 `BackendAdapter.getLyrics()`（网络歌曲没有 NAS 后端）
- `playNetworkSong()` 只需要调用 `playerManager.playSong()`，歌词由 `currentSong.collect` 统一触发，**不再需要手动 `loadNetworkLyrics()`**，完全消除冲突

### 6.3 两条歌词路径的关系说明

网络歌曲歌词获取存在两条独立路径：

**路径 1：自动加载（播放时触发）**
```
currentSong.collect → loadLyricsForCurrentSong() → networkMusicManager.resolveLyrics()
                                                    ├─ 优先：MetingApiService.resolveLyrics()
                                                    └─ 回退：LyricsNetworkProvider.fetchLyrics()
```

**路径 2：手动切换（用户主动选择）**
```
switchLyricsSource(LyricsSource.NETWORK) → lyricsManager.getLyricsFromSource()
                                          └─ LyricsNetworkProvider.fetchLyrics()
```

**两条路径的关系**：
- **互补关系**：路径 1 优先使用 Meting-API（速度快），失败时回退到 LyricsNetworkProvider（酷狗/网易云）
- **路径 2 独立**：用户手动切换时直接使用 LyricsNetworkProvider，不经过 Meting-API
- **设计意图**：自动加载追求速度（Meting-API 国内快），手动切换提供稳定备选（酷狗/网易云成熟）

### 6.4 LyricsNetworkProvider 改造（✅ 已完成，Phase 4）

现有 `LyricsNetworkProvider.kt` 作为歌词 fallback 路径，需要改造以对齐 v2.2.0 规范：

**改造项**：
1. **OkHttpClient 守护线程池**：当前 OkHttpClient（第 20-29 行）使用默认线程池，会阻止进程退出。改为：
   ```kotlin
   private val daemonExecutor = Executors.newCachedThreadPool { r ->
       Thread(r, "LyricsNetwork-OkHttp").apply { isDaemon = true }
   }
   private val client = OkHttpClient.Builder()
       .dispatcher(Dispatcher(daemonExecutor))
       .connectTimeout(15, TimeUnit.SECONDS)
       .readTimeout(15, TimeUnit.SECONDS)
       .build()
   ```

2. **日志统一为 AppLog**：当前大量使用 `Log.w` / `Log.e`（第 53、55、76、80 等行），改为 `AppLog.w` / `AppLog.e`

3. **JSON 解析统一为 Gson**：当前使用 `org.json.JSONObject`（第 157 行），改为 Gson

**改造时机**：可在 Phase 4（优化阶段）实施，不影响核心功能。

---

## 七、播放流程

```kotlin
// MainViewModel.kt

fun playNetworkSong(song: Song) {
    viewModelScope.launch {
        try {
            if (!song.isNetworkSong) {
                playSong(song) // 走原有逻辑
                return@launch
            }

            // 1. 解析播放链接（部分 API 搜索返回的就是直联，部分需要额外请求）
            val playUrl = networkMusicManager.resolvePlayUrl(song)
                ?: run {
                    showError("无法获取播放链接，请检查网络或切换音乐源")
                    return@launch
                }

            // 2. 解析封面（如果需要额外请求）
            val coverUrl = song.coverUrl
                ?: networkMusicManager.resolveCoverUrl(song)

            // 3. 组装完整 Song 对象播放
            val playable = song.copy(
                streamUrl = playUrl,
                coverUrl = coverUrl
            )
            playerManager.playSong(playable)

            // 4. 记录播放历史（复用现有 prefs.recordPlay，让网络歌曲也进入"最近播放"）
            prefs.recordPlay(playable.id)

            // 5. 自动跳转到播放页
            navigateTo(Screen.NowPlaying)

            // 歌词由 currentSong.collect 统一触发 loadLyricsForCurrentSong()，
            // 不需要在这里手动调用

        } catch (e: Exception) {
            AppLog.e("NASMusic", "playNetworkSong failed", e)
            showError("播放失败: ${e.message?.take(50)}")
        }
    }
}
```

### 播放流程对比

```
NAS 歌曲：点击 → PlayerManager.playSong(song) → 歌词 via currentSong.collect → BackendAdapter
网络歌曲：点击 → resolvePlayUrl → PlayerManager.playSong(song) → 歌词 via currentSong.collect → NetworkMusicService
```

Net positive: 两个路径完全平行，互不干扰。

---

## 八、收藏功能

### 8.1 设计

> **v2.2.0 适配**：使用 DataStore + Gson 序列化存储（与现有 `recentSongIds` / `playCounts` 模式一致），**不引入 Room**，避免增加 APK 体积和 KAPT 编译复杂度。网络收藏数据量预期 < 500 条，DataStore 足够。

```kotlin
// data/model/NetworkFavoriteItem.kt
data class NetworkFavoriteItem(
    // songId 字段直接复用 Song.id（如 "ntwk_meting_12345"），便于与 Song 关联查询
    val songId: String,
    val title: String,
    val artist: String,
    val album: String,
    val coverUrl: String?,     // 端点 URL 或直联 URL
    val networkSource: String, // "meting"
    val networkId: String,     // 原始平台 ID
    val addedAtMs: Long = System.currentTimeMillis()
)
```

```kotlin
// data/prefs/AppPreferences.kt 中新增
private val keyNetworkFavorites = stringPreferencesKey("network_favorites")

/** 网络收藏列表 Flow */
val networkFavorites: Flow<List<NetworkFavoriteItem>> = context.dataStore.data.map { prefs ->
    val json = prefs[keyNetworkFavorites] ?: "[]"
    try {
        gson.fromJson(json, object : TypeToken<List<NetworkFavoriteItem>>() {}.type)
    } catch (e: Exception) { emptyList() }
}

/** 同步获取（用于 isFavorite 判断） */
fun getNetworkFavoritesSync(): List<NetworkFavoriteItem> {
    return runBlocking {
        try {
            context.dataStore.data.first().let { prefs ->
                val json = prefs[keyNetworkFavorites] ?: "[]"
                gson.fromJson(json, object : TypeToken<List<NetworkFavoriteItem>>() {}.type) ?: emptyList()
            }
        } catch (e: Exception) { emptyList() }
    }
}

/** 切换收藏状态 */
suspend fun toggleNetworkFavorite(item: NetworkFavoriteItem) {
    context.dataStore.edit { prefs ->
        val json = prefs[keyNetworkFavorites] ?: "[]"
        val list = try {
            gson.fromJson(json, object : TypeToken<MutableList<NetworkFavoriteItem>>() {}.type)
                ?: mutableListOf()
        } catch (e: Exception) { mutableListOf<NetworkFavoriteItem>() }

        val mutable = list.toMutableList()
        val existing = mutable.indexOfFirst { it.songId == item.songId }
        if (existing >= 0) {
            mutable.removeAt(existing)  // 取消收藏
        } else {
            mutable.add(0, item)  // 添加收藏
        }
        prefs[keyNetworkFavorites] = gson.toJson(mutable)
    }
}
```

> **注意**：不存储 `streamUrl`（播放链接有时效性），每次播放时重新解析。

### 8.2 ViewModel 集成

```kotlin
// MainViewModel.kt
private val _networkFavorites = MutableStateFlow<List<NetworkFavoriteItem>>(emptyList())

// 供 UI 使用：转换为 Song 对象列表（设置 isNetworkSong 等标记字段）
val networkFavoriteSongs: StateFlow<List<Song>> = _networkFavorites.map { favorites ->
    favorites.map { item ->
        Song(
            id = item.songId,
            title = item.title,
            artist = item.artist,
            album = item.album,
            coverUrl = item.coverUrl,
            isNetworkSong = true,
            networkSource = item.networkSource,
            networkId = item.networkId
        )
    }
}.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

init {
    // 在现有 init 块末尾追加（与其他 prefs Flow collector 放在一起）
    viewModelScope.launch {
        prefs.networkFavorites.collect { favorites ->
            _networkFavorites.value = favorites
        }
    }
}

fun toggleNetworkFavorite(song: Song) {
    if (!song.isNetworkSong) return
    viewModelScope.launch {
        val item = NetworkFavoriteItem(
            songId = song.id,
            title = song.title,
            artist = song.artist,
            album = song.album,
            coverUrl = song.coverUrl,
            networkSource = song.networkSource ?: return@launch,
            networkId = song.networkId ?: return@launch
        )
        prefs.toggleNetworkFavorite(item)
    }
}

fun isNetworkFavorite(songId: String): Boolean {
    return _networkFavorites.value.any { it.songId == songId }
}
```

### 8.3 收藏后播放

收藏列表中的歌曲点击 → 走 `playNetworkSong()` → `resolvePlayUrl()` 重新获取链接，无需再次搜索。

⚠️ 注意事项：如果 Meting-API 下架，收藏列表中的歌曲将无法播放——这是外部依赖的生命周期问题，代码层面无法解决。

---

## 九、UI 实现

### 9.1 Library Screen - Network Tab

```
┌─────────────────────────────────────────────────────────────────┐
│  ◀ 曲库                                                          │
│  [专辑] [艺术家] [歌曲] [风格] [年代] [收藏] [最近] [●网络●]       │
├─────────────────────────────────────────────────────────────────┤
│  网络音乐                                    [🔍 搜索网络音乐]    │
│  ─────────────────────────────────────────────────────────────── │
│                                                                 │
│  （未搜索状态：显示收藏列表）                                      │
│  ┌── 我的收藏 ─────────────────────────────────────────────────┐ │
│  │ [封面] 稻香                           METING  ♡            │ │
│  │        周杰伦  ·  叶惠美                                    │ │
│  │ [封面] 青花瓷                         METING  ♥            │ │
│  │        周杰伦  ·  我很忙                                    │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                 │
│  （搜索中状态）                                                   │
│  ┌── 搜索结果: "稻香"                           [✕ 清空] ──────┐ │
│  │         ⏳ 搜索中...                                        │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                 │
│  （搜索失败状态）                                                 │
│  ┌── 搜索结果: "xxx"                           [✕ 清空] ──────┐ │
│  │  ❌ 搜索失败：网络超时                     [重试]            │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                 │
│  （搜索无结果状态）                                               │
│  ┌── 搜索结果: "xxx"                           [✕ 清空] ──────┐ │
│  │     🔍 未找到相关歌曲                                       │ │
│  └────────────────────────────────────────────────────────────┘ │
│                                                                 │
│  （空状态：无收藏、未搜索）                                       │
│                    🔍                                            │
│           搜索并收藏你喜欢的网络歌曲                                │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**关键行为**：
- Network Tab **不依赖 NAS 连接状态**，始终可见可用
- 顶部现有全局 SearchBar **仅用于 NAS 本地搜索**，不用于网络搜索
- Network Tab 内部有**独立搜索按钮**，点击弹出 `TextInputDialog`（复用现有虚拟键盘组件）
- 搜索结果中每行显示来源标签（`METING` / `ALAPI` / `JIOSAAVN`）和可点击的 ♡/♥ 收藏按钮
- 清空搜索或按返回键 → 切换回收藏列表
- 网络歌曲列表使用独立的 `NetworkSongRow` 组件（不复用 NAS SongRow，因无时长/播放次数信息）

### 9.2 未连接 NAS 时的特殊处理

现有 `LibraryScreen` 在 `!isConnected` 时显示全屏"请先配置服务器"，所有 Tab 不可见。需修改此逻辑：

```kotlin
// LibraryScreen.kt 状态判断修改
} else if (!isConnected && activeTab != LibraryTab.NETWORK) {
    // 非 NETWORK Tab 且未连接 → 原有提示
    Box(...) { "请先在「服务器」页面配置 NAS 音乐服务" }
} else if (albums.isEmpty() && songs.isEmpty() && activeTab != LibraryTab.NETWORK) {
    // 已连接但库为空
    Box(...) { "曲库为空" }
} else {
    when (activeTab) {
        // ... 现有 Tab 渲染（仅在 isConnected 时可见）
        LibraryTab.NETWORK -> NetworkTabContent(...)  // 始终可用
    }
}
```

### 9.3 LibraryTab 枚举扩展

现有 `LibraryTab` 枚举位于 [LibraryScreen.kt](file:///E:/temp/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/LibraryScreen.kt)（共 7 项），新增 `NETWORK`：

```kotlin
private enum class LibraryTab(val titleRes: Int) {
    ALBUMS(R.string.library_albums),
    ARTISTS(R.string.library_artists_alt),
    SONGS(R.string.library_songs),
    GENRES(R.string.library_genres),
    YEARS(R.string.library_years),
    FAVORITES(R.string.library_favorites),
    RECENT(R.string.library_recent),
    NETWORK(R.string.library_network)   // 新增，始终可见
}
```

**按需加载说明**：现有 `LaunchedEffect(activeTab)` 会在 Tab 切换时预加载数据。`NETWORK` Tab 只加载收藏列表，不触发搜索：

```kotlin
LaunchedEffect(activeTab) {
    when (activeTab) {
        LibraryTab.SONGS -> onLoadSongsFirstPage()
        LibraryTab.ARTISTS -> onLoadArtists()
        LibraryTab.YEARS -> onLoadYears()
        LibraryTab.RECENT -> onLoadRecentSongs()
        LibraryTab.NETWORK -> {
            onLoadNetworkFavorites()   // 加载收藏列表
            // 不自动触发搜索，搜索由用户点击搜索按钮发起
        }
        else -> {}
    }
}
```

**LibraryScreen 函数签名扩展**：

> **注意**：`networkFavoriteSongs` 由 ViewModel 将 `NetworkFavoriteItem` 转换为 `Song` 对象（设置 `isNetworkSong=true`、`networkSource`、`networkId`、`coverUrl` 等字段），UI 层无需了解 `NetworkFavoriteItem` 类型。`isNetworkFavorite(songId)` 传入 `song.id`（格式为 `ntwk_{source}_{id}`，与 NetworkFavoriteItem.songId 一致）。

```kotlin
@Composable
fun LibraryScreen(
    // ... 现有参数保持不变
    // 新增网络音乐相关参数
    networkFavoriteSongs: List<Song> = emptyList(),          // 收藏的网络歌曲（已转为Song）
    networkSearchQuery: String = "",                          // 当前搜索关键词（用于显示）
    networkSearchResults: UiState<List<Song>> = UiState.Success(emptyList()),
    isNetworkFavorite: (String) -> Boolean = { false },      // 传入 song.id 判断是否已收藏
    onLoadNetworkFavorites: () -> Unit = {},
    onOpenNetworkSearch: () -> Unit = {},                      // 打开搜索对话框
    onSearchNetwork: (String) -> Unit = {},                     // 执行搜索
    onRetryNetworkSearch: () -> Unit = {},                      // 重试上次搜索
    onClearNetworkSearch: () -> Unit = {},                       // 清空搜索结果
    onPlayNetworkSong: (Song) -> Unit = {},                     // 播放网络歌曲
    onToggleNetworkFavorite: (Song) -> Unit = {},               // 收藏/取消收藏
    // ...
)
```

### 9.4 搜索交互流程

**搜索入口**：Network Tab 内容区顶部的"🔍 搜索网络音乐"按钮（`FocusableSurface`，与 `ButtonChip` 样式一致）。

**搜索对话框**：复用现有 `TextInputDialog`，参数：
- `title = "搜索网络音乐"`
- `hint = "输入歌名或歌手名（支持中文/英文）"`
- `initialValue = networkSearchQuery`（保留上次搜索词）
- 确认 → `onSearchNetwork(keyword)`，关闭对话框
- 取消 → 关闭对话框，不改变当前状态

**搜索状态展示**（在 `NetworkTabContent` 内根据 `networkSearchResults` 渲染）：

| UiState | 展示内容 |
|---------|---------|
| `Loading` | 居中显示"⏳ 搜索中..."，无重试 |
| `Success(emptyList())` | 居中显示"🔍 未找到相关歌曲"，下方"✕ 清空"按钮 |
| `Success(results)` | LazyVerticalGrid(2列) 显示 NetworkSongRow 列表，顶部"搜索结果: xxx [✕ 清空]" |
| `Error(msg)` | 居中显示错误信息 + "重试"按钮（重新执行上次搜索）+ "✕ 清空"按钮 |

**清空搜索**：点击"✕ 清空"按钮 → `onClearNetworkSearch()` → 收藏列表自动显示（Flow 更新）。

**网络搜索不支持拼音**：搜索框 hint 明确提示"支持中文/英文"，不做本地拼音过滤。

### 9.5 网络歌曲列表项（NetworkSongRow）

新建独立 Composable，不复用 NAS `SongRow`（SongRow 有时长/播放次数字段，网络歌曲无此信息）。

```
┌─────────────────────────────────────────────────────────────┐
│  ┌──────┐  稻香                                METING  ♡   │
│  │ 封面  │  周杰伦 · 叶惠美                                  │
│  │ 56dp │                                                    │
│  └──────┘                                                    │
└─────────────────────────────────────────────────────────────┘
```

**布局规格**（参考现有 `SongRow` 模式）：
- 整体：`FocusableSurface`，focusedScale = 1.02f，pressedScale = 0.98f，整行点击 = 播放
- 左侧：56dp 正方形封面（Coil AsyncImage，无封面时用灰色占位符+音符图标）
- 中间：歌曲名（白色，14sp，单行省略）+ 艺术家·专辑名（灰色，12sp，单行省略）
- 右侧：来源标签（小胶囊样式，8sp 文字 + 主色半透明背景）+ 收藏按钮
- **收藏按钮**：独立 `FocusableSurface`（♥/♡），与 NowPlayingScreen 的 `FavoriteButton` 样式一致，focusedScale = 1.12f，点击拦截（不触发整行播放）

**焦点导航**：列表项整行可聚焦播放，行内收藏按钮是独立焦点目标。D-pad 右键从行内容移到收藏按钮。

### 9.6 BACK 键行为（含"回到顶部"功能）

#### 9.6.1 BACK 键分层优先级（v2）

按下 BACK 键时按以下优先级逐层处理，每层检查是否消费事件，消费则停止传递：

| 优先级 | 层级 | 条件 | 行为 |
|-------|------|------|------|
| Level 0 | 沉浸模式 | `isImmersiveMode == true` | 退出全屏沉浸模式 |
| Level 1 | 对话框 | `dialogBackHandler != null`（TextInputDialog/ConnectPromptDialog/ExitConfirmDialog 打开时） | 关闭对话框 |
| **Level 1.5（新增）** | **列表回到顶部** | 当前在任意列表页（专辑/艺术家/歌曲/收藏/最近/网络收藏/网络搜索结果/播放队列）**且列表已向下滚动** | **平滑滚动到列表顶部 + 焦点跳转到第一项** |
| Level 2 | 网络搜索清空 | Network Tab 且正在显示搜索结果（`networkSearchQuery.isNotBlank()`） | 清空搜索结果，切换回收藏列表 |
| Level 2.5 | 页面导航 | 不在 NowPlaying 页面 | 跳转到 NowPlaying 页面 |
| Level 3 | 退出确认 | 在 NowPlaying 页面 | 显示退出确认对话框 |

**典型操作序列示例**（以网络Tab搜索"稻香"并浏览搜索结果为例）：
```
浏览搜索结果（已滚动到第5首）→ BACK → 滚动到顶部，焦点在第1首结果
再次BACK → 清空搜索，显示收藏列表
（收藏列表在顶部）→ BACK → 跳转到NowPlaying
NowPlaying → BACK → 显示退出确认
```

**典型操作序列示例**（专辑Tab浏览到下方）：
```
专辑列表（滚动到第3行）→ BACK → 滚动到顶部，焦点在第1张专辑
再次BACK → 跳转到NowPlaying
```

#### 9.6.2 架构改动：MainActivity 新增 Level 1.5

在 [MainActivity.kt](file:///E:/temp/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/MainActivity.kt) 的三层 BACK 处理中插入 Level 1.5：

```kotlin
// MainActivity.kt
class MainActivity : ComponentActivity() {
    // Level 1: 对话框 BACK 键回调
    private val dialogBackHandler: MutableState<(() -> Unit)?> = mutableStateOf(null)
    // Level 1.5: 列表回到顶部回调（返回 true 表示已消费 BACK 事件）
    private val listBackHandler: MutableState<(() -> Boolean)?> = mutableStateOf(null)
    // Level 2: 页面导航 BACK 键回调
    private val navigateBackHandler: MutableState<(() -> Unit)?> = mutableStateOf(null)
    // Level 3: 退出确认
    private val showExitConfirm: MutableState<Boolean> = mutableStateOf(false)
    // ...

    override fun onCreate(savedInstanceState: Bundle?) {
        // ...
        setContent {
            // ...
            CompositionLocalProvider(
                LocalDialogBackHandler provides dialogBackHandler,
                LocalListBackHandler provides listBackHandler,   // 新增
                LocalNavigateBackHandler provides navigateBackHandler,
                LocalShowExitConfirm provides showExitConfirm
            ) {
                // ...
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Level 0
                if (isImmersiveMode.value) {
                    isImmersiveMode.value = false
                    return
                }
                // Level 1: 对话框
                val dialogHandler = dialogBackHandler.value
                if (dialogHandler != null) { dialogHandler(); return }
                // Level 1.5（新增）: 列表回到顶部
                val listHandler = listBackHandler.value
                if (listHandler != null && listHandler()) { return }
                // Level 2: 页面导航
                val navHandler = navigateBackHandler.value
                if (navHandler != null) { navHandler(); return }
                // Level 3: 退出确认
                showExitConfirm.value = true
            }
        })
    }
}
```

新增 `CompositionLocal`（合并到现有 [DialogBackHandler.kt](file:///E:/temp/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/DialogBackHandler.kt) 中，与 Level 1/2/3 的定义放在一起）：

```kotlin
// ui/DialogBackHandler.kt 中新增
/**
 * Level 1.5: 列表回到顶部回调 —— 当前列表已向下滚动时，按 BACK 先滚动到顶部。
 * 返回 true 表示已消费（已滚动），false 表示已在顶部（让事件继续传递到 Level 2）。
 */
val LocalListBackHandler = staticCompositionLocalOf<MutableState<(() -> Boolean)?>> {
    mutableStateOf(null)
}
```

#### 9.6.3 列表页面注册"回到顶部"处理

每个包含 `LazyVerticalGrid`/`LazyColumn` 的Tab/页面，通过 `LocalListBackHandler` 注册处理函数。处理函数返回 `Boolean`：`true` 表示消费了 BACK（已滚动到顶部），`false` 表示已在顶部让事件继续传递。

**通用模式**（以 AlbumsTab 为例，其他Tab完全相同）：

```kotlin
@Composable
private fun AlbumsTab(
    albums: List<Album>,
    songs: List<Song>,
    onPlayAlbum: (Album) -> Unit,
    onOpenAlbumDetail: ((Album) -> Unit)? = null
) {
    val listState = rememberLazyGridState()                           // 每个列表独立 gridState
    val firstItemFocusRequester = remember { FocusRequester() }      // 第一项焦点
    val scope = rememberCoroutineScope()
    val listBackHandler = LocalListBackHandler.current

    // 注册/注销回到顶部处理
    DisposableEffect(listState) {
        listBackHandler.value = {
            val isScrolled = listState.firstVisibleItemIndex > 0 ||
                             listState.firstVisibleItemScrollOffset > 0
            if (isScrolled) {
                scope.launch {
                    listState.animateScrollToItem(0)
                    delay(50)
                    firstItemFocusRequester.requestFocus()
                }
                true  // 消费 BACK 事件
            } else {
                false // 已在顶部，Bubble up 到 Level 2（页面导航）
            }
        }
        onDispose { listBackHandler.value = null }
    }

    Column {
        Text(text = "专辑 (${albums.size})", ...)
        LazyVerticalGrid(
            state = listState,
            columns = GridCells.Fixed(6),
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(albums, key = { _, album -> album.id }) { index, album ->
                AlbumCard(
                    album = album,
                    onClick = { onOpenAlbumDetail?.invoke(album) ?: onPlayAlbum(album) },
                    onPlay = { onPlayAlbum(album) },
                    focusRequester = if (index == 0) firstItemFocusRequester else null
                )
            }
        }
    }
}
```

**需要改造的列表Tab（共6个现有Tab + 2个网络Tab列表）**：

| Tab/页面 | 列表组件 | 当前是否有listState | 改造内容 |
|----------|---------|-------------------|---------|
| AlbumsTab | LazyVerticalGrid(6列) | ❌ 无 | 添加 rememberLazyGridState + FocusRequester + DisposableEffect |
| ArtistsTab | LazyVerticalGrid(5列) | ❌ 无 | 同上 |
| SongsTab | LazyVerticalGrid(2列) | ✅ 已有 | 添加 FocusRequester + DisposableEffect（复用现有listState） |
| GenresTab | LazyVerticalGrid(4列) | ❌ 无 | 添加 rememberLazyGridState + FocusRequester + DisposableEffect |
| YearsTab | LazyVerticalGrid(5列) | ❌ 无 | 同上 |
| FavoritesTab | LazyVerticalGrid(2列) | ❌ 无 | 同上 |
| RecentTab | LazyVerticalGrid(2列) | ❌ 无 | 同上 |
| NetworkTab-收藏 | LazyVerticalGrid(2列) | 新增 | 新建时即包含 listState + FocusRequester + DisposableEffect |
| NetworkTab-搜索结果 | LazyVerticalGrid(2列) | 新增 | 同上 |
| QueueScreen | LazyColumn | ❌ 无 | 添加 rememberLazyListState + FocusRequester + DisposableEffect |

> **注意**：当网络Tab切换（搜索结果 ↔ 收藏列表）时，两个列表是互斥显示的（`if/else`），切换时旧列表的 `onDispose` 会清空 `listBackHandler`，新列表的 `DisposableEffect` 会设置自己的 handler，不会冲突。

#### 9.6.4 LibraryScreen 的 BACK 处理（网络搜索清空）

Level 1.5 由各列表Tab自行注册，LibraryScreen 只需处理 **Level 2：网络搜索清空** 和 **Level 2.5：页面导航**。由于 Level 1.5 在 MainActivity 中统一处理，LibraryScreen 的 DisposableEffect 简化为：

```kotlin
// 在 LibraryScreen 中
val navBackHandler = LocalNavigateBackHandler.current
val listBackHandler = LocalListBackHandler.current

DisposableEffect(activeTab, networkSearchQuery) {
    if (activeTab == LibraryTab.NETWORK && networkSearchQuery.isNotBlank()) {
        // 网络搜索状态下 BACK 行为：Level 1.5（由列表注册）若未消费（已在顶部），则清空搜索
        val prevHandler = navBackHandler.value
        navBackHandler.value = {
            onClearNetworkSearch()
            // 清空后 focus 会自动由 NetworkTabContent 的 LaunchedEffect 处理（见9.10.5）
        }
        onDispose { navBackHandler.value = prevHandler }
    } else {
        onDispose { }
    }
}
```

**网络搜索状态下的BACK流程**：
1. 用户浏览搜索结果滚动到下方 → BACK → Level 1.5 消费：滚动到顶部+焦点第一项
2. 结果列表在顶部 → BACK → Level 1.5 返回 false（已在顶部）→ Level 2：清空搜索，显示收藏列表
3. 收藏列表在顶部 → BACK → Level 1.5 返回 false → Level 2.5：跳转到 NowPlaying

#### 9.6.5 AlbumCard/ArtistCard 等卡片组件支持 focusRequester

现有 [AlbumCard](file:///E:/temp/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/LibraryScreen.kt) 等卡片组件内部使用 `FocusableSurface`，需要增加 `focusRequester` 参数透传：

```kotlin
@Composable
private fun AlbumCard(
    album: Album,
    onClick: () -> Unit,
    onPlay: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null  // 新增
) {
    var isFocused by remember { mutableStateOf(false) }
    // ...
    FocusableSurface(
        onClick = onClick,
        focusRequester = focusRequester,    // 透传
        // ...
    ) {
        // ...
    }
}
```

同样适用于 `ArtistCard`、`GenreCard`、`YearCard`、`SongRow`（现有NAS歌曲行）、`NetworkSongRow`（新增）。

### 9.7 搜索状态 ViewModel 实现

> **v2.2.0 适配**：复用现有 `UiState<T>` 密封类（Loading/Success/Error），与曲库加载逻辑一致。

```kotlin
// MainViewModel.kt
private val _networkSearchQuery = MutableStateFlow("")
val networkSearchQuery: StateFlow<String> = _networkSearchQuery.asStateFlow()

private val _networkSearchResults = MutableStateFlow<UiState<List<Song>>>(UiState.Success(emptyList()))
val networkSearchResults: StateFlow<UiState<List<Song>>> = _networkSearchResults.asStateFlow()

fun searchNetworkSongs(keyword: String) {
    val trimmed = keyword.trim()
    _networkSearchQuery.value = trimmed
    if (trimmed.isBlank()) {
        _networkSearchResults.value = UiState.Success(emptyList())
        return
    }
    _networkSearchResults.value = UiState.Loading
    viewModelScope.launch {
        try {
            val results = networkMusicManager.search(trimmed)
            _networkSearchResults.value = if (results.isEmpty()) {
                UiState.Success(emptyList())  // 无结果
            } else {
                UiState.Success(results)
            }
        } catch (e: Exception) {
            AppLog.e("MainViewModel", "searchNetworkSongs failed", e)
            _networkSearchResults.value = UiState.Error("搜索失败: ${e.message?.take(50)}")
        }
    }
}

fun clearNetworkSearch() {
    _networkSearchQuery.value = ""
    _networkSearchResults.value = UiState.Success(emptyList())
}

fun retryNetworkSearch() {
    val query = _networkSearchQuery.value
    if (query.isNotBlank()) {
        searchNetworkSongs(query)
    }
}

fun updateDefaultNetworkSource(source: String) {
    viewModelScope.launch {
        networkMusicManager.setDefaultSource(source)
    }
}
```

### 9.8 播放页（NowPlayingScreen）

**主要复用**：NowPlayingScreen 主体无需修改。歌词由 `currentSong.collect` 统一触发，经由改造后的 `loadLyricsForCurrentSong()` 走网络路径。

**需补充的小调整**：
- 网络歌曲播放时，歌曲标题旁可显示小标签（如"NET"或音乐源标识），帮助用户区分 NAS 歌曲和网络歌曲
- 此为 Phase 4 优化项，Phase 1 可不做

**歌词来源标签说明**：网络歌曲歌词复用 `LyricsSource.NETWORK` 枚举，NowPlayingScreen 显示"网络匹配"。Phase 1 文案暂不修改；Phase 4 可优化为更准确的"在线歌词"。

### 9.9 收藏交互

**收藏按钮位置**：
- NetworkSongRow 右侧（列表中直接切换）
- NowPlayingScreen 现有 ♥/♡ 按钮（需扩展支持网络歌曲）

**NowPlayingScreen 收藏按钮扩展**：现有 `toggleFavorite()` 仅调用后端 API。需增加 `isNetworkSong` 判断：
- NAS 歌曲 → 走现有 `toggleFavorite(song)` → 后端 API
- 网络歌曲 → 走 `toggleNetworkFavorite(song)` → DataStore

**收藏状态同步**：
- `_networkFavorites: StateFlow<List<NetworkFavoriteItem>>` 通过 Flow 自动更新 UI
- `isNetworkFavorite(songId)` 在 NetworkSongRow 和 NowPlayingScreen 中判断
- 收藏/取消收藏后通过 Snackbar 样式提示（短暂显示"已收藏"/"已取消收藏"）或不提示（与现有 NAS 收藏一致，现有收藏无 Toast 提示）

---

### 9.10 遥控器焦点行为规范

#### 9.10.1 现有页面焦点行为分析（基线）

**整体架构**：AppRoot 顶部导航栏（[NavItem](file:///E:/temp/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/components/AppRoot.kt#L369-L389)）始终在 composition 中，不随页面切换销毁。内容区通过 `when(currentScreen)` 切换，离开的页面完全从 composition 移除。

| 页面 | 首次进入默认焦点 | 返回/切换回来时焦点 | 是否记忆焦点 |
|------|----------------|-------------------|-------------|
| NowPlaying | 无显式设置，由 Compose TV 自动分配（通常落在第一个控制按钮） | 同首次进入 | ❌ 不记忆（页面切换时整个 Screen 离开 composition） |
| Library | 焦点在顶部导航"曲库"NavItem（点击后停留），DPAD_DOWN 进入 Tab 行首个 Tab"专辑" | 焦点在顶部导航"曲库"NavItem | ❌ 不记忆 |
| Library 内 Tab 切换 | 点击 Tab 后焦点停留在该 Tab chip，DPAD_DOWN 进入内容区第一个可聚焦元素 | 切换 Tab 后旧 Tab 离开 composition，新 Tab 内容是全新组合 | ❌ 不记忆（Tab 间用 `when(activeTab)` 切换，旧 Tab 移除 composition） |
| Queue | 焦点在顶部导航"队列"NavItem，DPAD_DOWN 进入列表第一项 | 同首次进入 | ❌ 不记忆 |
| Settings | 焦点在顶部导航"设置"NavItem，DPAD_DOWN 进入左侧栏"通用" | 同首次进入 | ❌ 不记忆 |
| TextInputDialog | `requestFocusOnLaunch = true` 显式聚焦"确认"按钮 | 对话框关闭后焦点返回触发打开对话框的元素（Compose 自动恢复） | ✅ 对话框关闭时 Compose 自动恢复到之前焦点 |

**关键结论**：
- 现有代码库**不做跨页面/跨Tab的焦点记忆**，页面和Tab切换时内容从composition移除，状态全部重置
- 对话框（Dialog）是Overlay，不替换底层composition，关闭时Compose自动恢复焦点到触发元素
- 初始焦点依靠 `FocusableSurface` 的 `requestFocusOnLaunch = true` + `FocusRequester` 显式设置
- 顶部NavItem是稳定的"焦点锚点"——从内容区回到顶部导航总是通过DPAD_UP到达NavItem行

#### 9.10.2 网络Tab各状态的默认焦点

```
┌─────────────────────────────────────────────────────────────────┐
│  ◀ 曲库                                                          │
│  [专辑] [艺术家] [歌曲] [风格] [年代] [收藏] [最近] [●网络●] ← Tab行 │
├─────────────────────────────────────────────────────────────────┤
│  网络音乐                                    [🔍 搜索网络音乐] ← ① │
│  ─────────────────────────────────────────────────────────────── │
│                                                                 │
│  ┌── 我的收藏 ─────────────────────────────────────────────────┐ │
│  │ [封面] 稻香                           METING  ♡            │←②│
│  │        周杰伦  ·  叶惠美                                    │ │
│  │ [封面] 青花瓷                         METING  ♥            │←③│
│  │        周杰伦  ·  我很忙                                    │ │
│  └────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

| 状态 | 默认焦点位置 | 理由 |
|------|------------|------|
| **首次进入Network Tab**（点击Tab chip后） | 焦点停留在"网络"Tab chip上（与其他Tab行为一致） | 用户需要DPAD_DOWN进入内容区 |
| **DPAD_DOWN进入内容区**（收藏列表非空） | ② 第一首收藏歌曲（NetworkSongRow） | 收藏歌曲是主要操作目标；搜索按钮在右上角，不是用户进入Tab后的第一需求 |
| **DPAD_DOWN进入内容区**（收藏列表为空，未搜索） | ① "🔍 搜索网络音乐"按钮 | 空状态下搜索是唯一可操作元素 |
| **点击搜索按钮 → 弹出TextInputDialog** | "确认"按钮（TextInputDialog已有requestFocusOnLaunch） | 复用现有对话框行为 |
| **搜索完成 → 显示结果列表** | 列表第一项搜索结果（NetworkSongRow） | 用户搜索后最可能直接播放第一首歌 |
| **搜索失败（Error状态）** | "重试"按钮 | 错误恢复是首要操作 |
| **搜索无结果** | "✕ 清空"按钮 | 清空后返回收藏列表 |
| **搜索中（Loading状态）** | 无焦点元素（居中显示加载中，无法操作） | 等结果返回后自动请求焦点到结果项 |
| **点击"✕ 清空"按钮清空搜索** | 若收藏非空→第一项收藏歌曲；若收藏为空→"🔍 搜索网络音乐"按钮 | 回到初始浏览状态 |
| **按BACK键清空搜索** | 与点击"✕ 清空"一致 | 行为一致 |

#### 9.10.3 焦点转移路径图

**路径A：进入Network Tab → 搜索 → 播放**
```
[NavItem"曲库"] → DPAD_DOWN → [Tab"网络"] → DPAD_DOWN → [第一首收藏歌曲]
                                                    ↑ (无收藏时)
                                                [搜索按钮] → DPAD_CENTER → [弹窗"确认"] → DPAD_CENTER(确认搜索)
                                                                                              ↓ (弹窗关闭，焦点回到搜索按钮)
                                                                                         [搜索按钮] (等待结果)
                                                                                              ↓ (搜索完成)
                                                                                         [第一项搜索结果] → DPAD_CENTER → 播放 → 跳NowPlaying
```

**路径B：搜索结果中收藏歌曲**
```
[第一项搜索结果] → DPAD_RIGHT → [♥收藏按钮] → DPAD_CENTER(收藏) → 焦点留在收藏按钮
```
（NetworkSongRow整行可聚焦播放，收藏按钮是**独立焦点目标**，与NowPlayingScreen收藏按钮一致）

**路径C：BACK键清空搜索**
```
[搜索结果列表某首歌] → BACK(清空搜索) → [第一项收藏歌曲] (或 [搜索按钮] 若无收藏)
```

**路径D：弹窗关闭（取消搜索）**
```
[弹窗] → BACK(取消) → 弹窗关闭 → [搜索按钮] (Compose自动恢复焦点到触发弹窗的元素)
```

#### 9.10.4 是否需要焦点记忆

**结论：Phase 1 不需要跨离开/返回的焦点记忆**，理由：

1. **与现有代码库一致**：所有现有Tab（专辑/艺术家/歌曲等）切换时都不记忆焦点和滚动位置，Network Tab遵循同样模式即可
2. **Tab切换场景极少**：用户在同一Tab内操作，切换Tab通常意味着改变浏览意图，不需要回到上次位置
3. **Compose TV默认行为已够用**：从顶部NavItem DPAD_DOWN进入内容区后，焦点落在Tab行第一个Tab或内容区第一个元素，符合TV操作直觉
4. **对话框场景自动恢复**：TextInputDialog关闭时Compose自动恢复焦点到触发按钮，无需额外处理

**Phase 4 可考虑的优化**（非必须）：
- 如果后续发现用户需要在搜索结果↔收藏之间频繁切换且列表很长，可以用 `rememberLazyGridState()` 记住收藏列表和搜索结果各自的滚动位置（但不做焦点记忆，焦点仍落到第一项可见元素）
- 此优化与现有其他Tab保持差异，建议Phase 1不做

#### 9.10.5 焦点实现要点

```kotlin
// NetworkTabContent 中使用 FocusRequester 控制各状态的焦点
val firstResultFocusRequester = remember { FocusRequester() }
val firstFavoriteFocusRequester = remember { FocusRequester() }
val retryButtonFocusRequester = remember { FocusRequester() }
val clearButtonFocusRequester = remember { FocusRequester() }
val searchButtonFocusRequester = remember { FocusRequester() }

// 收藏列表和搜索结果各自独立的 gridState（支持 BACK 键回到顶部）
val favoritesListState = rememberLazyGridState()
val resultsListState = rememberLazyGridState()
val scope = rememberCoroutineScope()
val listBackHandler = LocalListBackHandler.current

// 搜索结果状态变化时请求焦点
LaunchedEffect(networkSearchResults) {
    val results = networkSearchResults
    when {
        results is UiState.Success && results.data.isNotEmpty() -> {
            delay(100) // 等待LazyVerticalGrid布局完成
            firstResultFocusRequester.requestFocus()
        }
        results is UiState.Error -> {
            delay(100)
            retryButtonFocusRequester.requestFocus()
        }
        results is UiState.Success && networkSearchQuery.isNotBlank() && results.data.isEmpty() -> {
            // 无结果状态 → 焦点到"清空"按钮
            delay(100)
            clearButtonFocusRequester.requestFocus()
        }
    }
}

// 搜索被清空（BACK键或点击清空按钮）时，焦点回到收藏列表第一项或搜索按钮
LaunchedEffect(networkSearchQuery) {
    if (networkSearchQuery.isBlank()) {
        delay(100)
        if (networkFavoriteSongs.isNotEmpty()) {
            firstFavoriteFocusRequester.requestFocus()
        } else {
            searchButtonFocusRequester.requestFocus()
        }
    }
}

// 注册 Level 1.5 BACK 处理：收藏列表/搜索结果列表回到顶部
// 搜索状态时用 resultsListState，非搜索状态时用 favoritesListState
DisposableEffect(networkSearchQuery, networkSearchResults) {
    val activeListState = if (networkSearchQuery.isNotBlank() &&
                               networkSearchResults is UiState.Success &&
                               networkSearchResults.data.isNotEmpty()) {
        resultsListState
    } else if (networkSearchQuery.isBlank() && networkFavoriteSongs.isNotEmpty()) {
        favoritesListState
    } else null

    if (activeListState != null) {
        listBackHandler.value = {
            val isScrolled = activeListState.firstVisibleItemIndex > 0 ||
                             activeListState.firstVisibleItemScrollOffset > 0
            if (isScrolled) {
                scope.launch {
                    activeListState.animateScrollToItem(0)
                    delay(50)
                    val requester = if (networkSearchQuery.isNotBlank())
                        firstResultFocusRequester else firstFavoriteFocusRequester
                    requester.requestFocus()
                }
                true
            } else {
                false
            }
        }
    }
    onDispose { listBackHandler.value = null }
}

// 搜索按钮（空状态默认焦点）
FocusableSurface(
    onClick = onOpenNetworkSearch,
    focusRequester = searchButtonFocusRequester,
    requestFocusOnLaunch = networkFavoriteSongs.isEmpty() && networkSearchQuery.isBlank(),
) { ... }

// 收藏列表（带 listState）
LazyVerticalGrid(
    state = favoritesListState,
    columns = GridCells.Fixed(2),
    ...
) {
    itemsIndexed(networkFavoriteSongs) { index, song ->
        NetworkSongRow(
            song = song,
            isFavorite = true,
            onPlay = { onPlayNetworkSong(song) },
            onToggleFavorite = { onToggleNetworkFavorite(song) },
            focusRequester = if (index == 0) firstFavoriteFocusRequester else null
        )
    }
}

// 搜索结果列表（带 listState）
val results = (networkSearchResults as? UiState.Success)?.data ?: emptyList()
LazyVerticalGrid(
    state = resultsListState,
    columns = GridCells.Fixed(2),
    ...
) {
    itemsIndexed(results) { index, song ->
        NetworkSongRow(
            song = song,
            isFavorite = isNetworkFavorite(song.id),
            onPlay = { onPlayNetworkSong(song) },
            onToggleFavorite = { onToggleNetworkFavorite(song) },
            focusRequester = if (index == 0) firstResultFocusRequester else null
        )
    }
}
```

**NetworkSongRow焦点结构**：
- 整行是一个 `FocusableSurface`（可点击播放）
- 收藏♥按钮是嵌套的独立 `FocusableSurface`
- D-pad RIGHT 从行内容移到收藏按钮，D-pad LEFT 返回行内容
- 收藏按钮点击消费事件，不冒泡到整行播放

---

## 十、设置页面扩展

在现有 `SettingsScreen` 的 `NETWORK` 分区（当前仅有网络连通性测试）下方添加默认音乐源选择器。

```
┌─────────────────────────────────────────┐
│  设置                                    │
├─────────────────────────────────────────┤
│  ┌──────┬──────────────────────────────┐│
│  │ 通用  │                              ││
│  │ 播放  │                              ││
│  │ 歌词  │                              ││
│  │ 缓存  │                              ││
│  │●网络●│  网络连通性  ✓ 已连接         ││
│  │ 关于  │  ─────────────────────────  ││
│  └──────┘  默认音乐源                   ││
│            [Meting-API] [ALAPI] [JioSaavn]│
│            国内访问快，支持网易云/QQ音乐   ││
│            无需认证                       ││
└─────────────────────────────────────────┘
```

**组件选择**：复用现有 `PlayModeSelector` 模式（横向排列的 `FocusableSurface` 按钮组），不使用下拉弹窗。选中项高亮背景+主色文字，与现有设置项视觉一致。

**选项说明**：
- `Meting-API`（推荐）— 国内访问快，支持网易云/QQ音乐
- `ALAPI` — 国内服务，仅网易云
- `JioSaavn` — 国际音乐，国内访问慢

**选项下方动态说明文字**：切换选项时同步更新说明文字，帮助用户了解各源特点。

---

### 10.1 播放失败提示流程

当 `resolvePlayUrl()` 返回 null 或播放器报错时：

1. **错误提示**：调用 `showError(network_play_failed)` ，复用现有错误提示机制（屏幕中部短暂显示错误文字）
2. **停留页面**：留在当前页面（不自动跳转），用户可自行重试或切换音乐源
3. **不自动跳转**：播放失败不跳转到 NowPlaying（避免跳转到黑屏/错误页）
4. **手动引导**：错误信息中提及"切换音乐源"，引导用户去设置页调整

```kotlin
// playNetworkSong() 中的错误处理
val playUrl = networkMusicManager.resolvePlayUrl(song)
    ?: run {
        showError("无法获取播放链接，请检查网络或切换音乐源")
        return@launch  // 不播放、不跳转
    }
// ... 播放成功后跳转 NowPlaying
```

---

## 十一、字符串资源

```xml
<string name="library_network">网络</string>
<string name="network_search">搜索网络音乐</string>
<string name="network_search_hint">输入歌名或歌手名（支持中文/英文）</string>
<string name="network_no_results">未找到相关歌曲</string>
<string name="network_no_favorites">搜索并收藏你喜欢的网络歌曲</string>
<string name="network_searching">搜索中...</string>
<string name="network_play_failed">无法获取播放链接，请检查网络或切换音乐源</string>
<string name="network_my_favorites">我的收藏</string>
<string name="network_search_results">搜索结果</string>
<string name="network_clear">清空</string>
<string name="common_retry">重试</string>
<string name="network_source_meting">Meting-API</string>
<string name="network_source_alapi">ALAPI</string>
<string name="network_source_jiosaavn">JioSaavn</string>
<string name="settings_network_music">网络音乐</string>
<string name="settings_network_source">默认音乐源</string>
<string name="settings_network_source_desc_meting">国内访问快，支持网易云/QQ音乐，无需认证</string>
<string name="settings_network_source_desc_alapi">国内服务，仅支持网易云音乐</string>
<string name="settings_network_source_desc_jiosaavn">国际音乐曲库，国内访问较慢</string>
```

---

## 十二、新增/修改文件清单

| 文件 | 操作 | 说明 |
|------|------|------|
| `ui/DialogBackHandler.kt` | 修改 | 新增 `LocalListBackHandler` CompositionLocal 定义（Level 1.5） |
| `ui/MainActivity.kt` | 修改 | 新增 Level 1.5 BACK 处理层（列表回到顶部），注册 `LocalListBackHandler` |
| `data/model/Song.kt` | 修改 | 添加 `isNetworkSong`、`networkSource`、`networkId` |
| `data/model/AppSettings.kt` | 修改 | 添加 `defaultNetworkSource` |
| `data/model/NetworkFavoriteItem.kt` | 新增 | 网络收藏数据类 |
| `data/model/LyricsSource.kt` | 无需修改 | 复用现有 `NETWORK` 枚举项（Phase 4 可优化文案） |
| `backend/network/NetworkMusicService.kt` | 新增 | 网络音乐服务接口 |
| `backend/network/MetingApiService.kt` | 新增 | Meting-API 实现 |
| `backend/network/AlApiService.kt` | 新增 | ALAPI 实现（Phase 3） |
| `backend/network/JioSaavnService.kt` | 新增 | JioSaavn 实现（Phase 3） |
| `backend/network/NetworkMusicManager.kt` | 新增 | 管理器（多源路由、搜索容错） |
| `data/prefs/AppPreferences.kt` | 修改 | 添加 `keyNetworkFavorites` / `toggleNetworkFavorite()` / `getDefaultNetworkSourceSync()` / `setDefaultNetworkSource()` |
| `ui/screens/LibraryScreen.kt` | 修改（较大） | Phase 0：所有Tab添加 rememberLazyGridState + FocusRequester + listBackHandler 注册；卡片组件添加 focusRequester 参数；Phase 1：添加 Network Tab、NetworkTabContent、NetworkSongRow、BACK 键搜索清空逻辑 |
| `ui/screens/QueueScreen.kt` | 修改 | 添加 rememberLazyListState + FocusRequester + listBackHandler 注册（支持队列列表回到顶部） |
| `ui/screens/NowPlayingScreen.kt` | 修改（小） | 收藏按钮增加 `isNetworkSong` 判断，网络歌曲调用 `toggleNetworkFavorite` |
| `ui/viewmodel/MainViewModel.kt` | 修改 | 扩展 `searchNetworkSongs()`/`clearNetworkSearch()`/`retryNetworkSearch()`/`playNetworkSong()`/`toggleNetworkFavorite()`/`isNetworkFavorite()`/`networkFavoriteSongs`/`updateDefaultNetworkSource()`，修复 `loadLyricsForCurrentSong()` 网络歌词分支 |
| `ui/screens/SettingsScreen.kt` | 修改 | NETWORK 分区添加默认音乐源选择器（横向按钮组） |
| `NasMusicApp.kt` | 修改 | 初始化 NetworkMusicManager |
| `res/values/strings.xml` | 修改 | 添加新增字符串资源 |

---

## 十三、实施顺序（分阶段）

### Phase 0 — 基础改造：BACK 键回到顶部（适用于所有列表页） ✅ 已完成
1. 修改 [DialogBackHandler.kt](file:///E:/temp/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/DialogBackHandler.kt)：新增 `LocalListBackHandler` CompositionLocal 定义
2. 修改 [MainActivity.kt](file:///E:/temp/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/MainActivity.kt)：新增 Level 1.5 BACK 处理层（在对话框关闭之后、页面导航之前），注册 `LocalListBackHandler`
3. 改造现有列表Tab（每个Tab都需添加 `rememberLazyGridState` + `firstItemFocusRequester` + `DisposableEffect` 注册 `listBackHandler`）：
   - AlbumsTab（添加 rememberLazyGridState）
   - ArtistsTab（添加 rememberLazyGridState）
   - SongsTab（复用已有 listState，添加 FocusRequester）
   - GenresTab（添加 rememberLazyGridState）
   - YearsTab（添加 rememberLazyGridState）
   - FavoritesTab（添加 rememberLazyGridState）
   - RecentTab（添加 rememberLazyGridState）
4. 改造卡片组件支持 `focusRequester` 透传参数：AlbumCard、ArtistCard、GenreCard、YearCard、SongRow（现有NAS歌曲行）
5. 改造 [QueueScreen.kt](file:///E:/temp/NasAudio/NASMusicTV/app/src/main/java/com/nasmusic/tv/ui/screens/QueueScreen.kt)：添加 rememberLazyListState + FocusRequester + listBackHandler 注册
6. 验证：在各列表页滚动到底部，按 BACK 应平滑滚动到顶部并聚焦第一项；再按 BACK 应跳转到 NowPlaying

### Phase 1 — 核心功能（搜索+播放+歌词） ✅ 已完成
1. 修改 `Song` / `AppSettings` 数据模型
2. 实现 `NetworkMusicService` 接口 + `MetingApiService`
3. 实现 `NetworkMusicManager`（含多源搜索框架，Phase 3 补充其他源）
4. 修改 `AppPreferences`：添加 `defaultNetworkSource` 读写方法
5. 修改 `NasMusicApp`：初始化 `NetworkMusicManager`
6. 修改 `MainViewModel`：`searchNetworkSongs()`/`clearNetworkSearch()`/`retryNetworkSearch()`/`playNetworkSong()`/`updateDefaultNetworkSource()`、修复 `loadLyricsForCurrentSong()` 网络歌词分支
7. 修改 `LibraryScreen`：
   - 添加 `NETWORK` Tab（枚举+Tab 按钮）
   - 修改未连接状态判断（NETWORK Tab 始终可用）
   - 添加 `NetworkTabContent` Composable（搜索按钮+状态展示+列表）
   - 添加 `NetworkSongRow` Composable（封面+标题+来源标签+播放）
   - 复用 `TextInputDialog` 实现搜索输入
   - BACK 键搜索状态处理
   - **焦点管理**：FocusRequester 设置（搜索完成→第一项结果、错误→重试按钮、无结果→清空按钮、搜索清空→第一项收藏/搜索按钮）
8. 添加字符串资源

**额外完成（超出 Phase 1 范围）**：
- 搜索输入支持中文（系统输入法切换按钮）— 10.11.2
- Meting-API 字段映射错误修复（title/author vs name/artist）— 10.11.3
- SSL 证书信任失败修复（旧 TV 设备缺少 Let's Encrypt 根 CA）— 10.11.4
- Meting-API 端点可配置（预设端点选择 + 自定义输入）— 10.11.5
- 诊断日志体系（MetingDiag TAG）— 10.11.6
- 搜索状态持久化（keyword 移至 ViewModel StateFlow，跨页面导航保留）
- 加入队列功能（所有歌曲列表页面的 SongRow 添加队列切换按钮）
- 队列按钮焦点修复（Box + focusGroup + clickable 替代嵌套 FocusableSurface）
- 队列页面样式统一（与 SongRow 一致的紧凑样式 + 焦点行为）

### Phase 2 — 收藏 ✅ 已完成
1. 新增 `NetworkFavoriteItem` 数据类
2. 实现 `AppPreferences` 收藏存储（`keyNetworkFavorites`/`toggleNetworkFavorite()`/`networkFavorites` Flow）
3. MainViewModel：`toggleNetworkFavorite()`/`isNetworkFavorite()`/`networkFavoriteSongs` StateFlow（NetworkFavoriteItem→Song 转换）
4. NetworkSongRow：添加可点击收藏按钮（独立焦点目标，D-pad RIGHT 可达）
5. NetworkTabContent：收藏列表展示（未搜索时显示收藏），第一项收藏歌曲焦点
6. NowPlayingScreen：收藏按钮增加 `isNetworkSong` 分支
7. 收藏/搜索结果切换逻辑（含 BACK 键清空搜索后焦点恢复）

**额外完成（超出 Phase 2 范围）**：
- 所有歌曲列表页面统一添加收藏按钮（SongsTab、RecentTab、AlbumDetailScreen、ArtistDetailScreen、FavoritesTab）
- FavoriteButton 组件通用化（本地/网络收藏共用，Box + focusable + clickable 模式）
- 收藏页面 NAS 歌曲也可取消收藏（原方案仅网络歌曲可取消）
- 收藏页面不依赖 NAS 连接状态（FAVORITES Tab 与 NETWORK Tab 同等处理，始终可用）
- 加入队列功能（所有歌曲列表页面的 SongRow 添加队列切换按钮）
- SongRow 焦点架构重构（Box(focusGroup) + 兄弟级 Row + Box(focusable+clickable)，解决嵌套焦点问题）
- 队列页面样式统一（与 SongRow 一致的紧凑样式 + 焦点行为）
- 搜索状态持久化（关键词移至 ViewModel StateFlow，跨页面导航保留）

### Phase 3 — 多端点容错 + 设置 ✅ 已完成（方案调整）
> **方案调整说明**：原方案计划实现 AlApiService、JioSaavnService 作为多源容错。实际实施中，
> 鉴于 Meting-API 已有 3 个可用预设端点（Mikus/Redcha/Qijieya），且 AlAPI/JioSaavn 国内访问
> 不稳定，调整为**端点级自动 fallback**：搜索时当前端点失败自动尝试其他预设端点，用户无感。
> 该方案在 MetingApiService 内部实现，不影响 NetworkMusicManager 的多源路由架构。

1. ~~实现 `AlApiService`、`JioSaavnService`~~（跳过：国内访问不稳定，Meting 3 端点已足够容错）
2. `NetworkMusicManager` 多源容错 fallback 框架（已就绪，供未来扩展其他源）
3. `SettingsScreen`：NETWORK 分区 Meting-API 端点选择器（3 个预设 + 自定义输入）
4. **MetingApiService 搜索端点自动 fallback**（新增）：
   - `search()` 方法优先使用用户选中的端点
   - 失败/无结果时自动遍历其他预设端点
   - `buildEndpointFallbackOrder()` 构造端点优先级（当前端点 → 其他预设端点，去重）
   - `searchWithEndpoint()` 单端点搜索（供 fallback 流程调用）
   - 自定义端点也支持 fallback 到预设端点

### Phase 4 — 优化 ✅ 已完成
1. ✅ `LyricsNetworkProvider` 改造（守护线程 + AppLog + Gson）
2. ✅ 网络歌曲编码修复（`EncodingUtils.fixEncoding()`）
3. ✅ 网络收藏 LRU 上限（500 条，超出自动清理最旧）
4. ✅ NowPlayingScreen 网络歌曲来源标识（"NET" 标签）
5. ✅ 歌词来源标签文案优化（"网络匹配" → "在线歌词"）
6. ✅ 播放链接缓存（5 分钟 TTL，NetworkMusicManager.playUrlCache）
7. ⏳ 用户体验打磨（加载动画、空状态插图等）— 延后实施

### Phase 5 — 队列持久化 ✅ 已完成（额外功能，超出原方案）

> **说明**：此功能不在原开发方案中，是根据用户需求新增的功能。

1. ✅ 上次播放队列保存（DataStore + Gson，streamUrl 置空避免过期链接）
2. ✅ 应用启动自动恢复队列和当前索引（不自动播放，防止意外声音）
3. ✅ `PlayerManager.restoreQueue()` 设置队列和索引但不播放
4. ✅ NAS 歌曲 streamUrl 后端连接后刷新（`adapter.getSongsByIds()`）
5. ✅ 网络歌曲 streamUrl 播放时实时解析（`resolvePlayUrl()`）
6. ✅ 恢复队列后首次播放 streamUrl 解析（`playPause()`/`next()`/`previous()` 检测空 streamUrl）
7. ✅ 自动切歌到网络歌曲 streamUrl 解析（`onMediaItemTransition` 拦截 + `onNeedResolveStreamUrl` 回调）
8. ✅ 清空队列同步清除持久化数据（`clearQueue()` 调用 `prefs.clearLastQueue()`）

### Phase 6 — 输入对话框修复 ✅ 已完成（额外功能，超出原方案）

> **说明**：此功能不在原开发方案中，是根据用户反馈的 BUG 修复。

1. ✅ TextInputDialog 被列表覆盖修复（包裹到 `Dialog` 系统级窗口）
2. ✅ TextInputDialog BACK 键失效修复（Dialog 内部使用 `BackHandler`）
3. ✅ 恢复队列后无法播放修复（`restoreQueue` 加载 MediaItems + `playPause` 检测空 streamUrl）
4. ✅ 恢复队列后网络歌曲无法播放修复（空 streamUrl 跳过 prepare + `onPlayerError` 不级联跳歌）
5. ✅ 自动切歌到网络歌曲播放失败修复（`onMediaItemTransition` 拦截 + `onNeedResolveStreamUrl` 回调）
6. ✅ 歌词加载误报"加载歌词失败"修复（`CancellationException` 重新抛出）

---

## 十四、评估与风险

### 已解决的核心问题

| 问题 | 解决方案 |
|------|---------|
| 双模型冲突 | 统一用 `Song`，靠字段区分 |
| 歌词加载冲突 | `loadLyricsForCurrentSong()` 内部分支处理 |
| Meting-API 端点/直联混淆 | 代码注释和数据流明确区分 |
| Tab 可用性 | Network Tab 独立于 NAS 连接状态，未连接时也可访问 |
| 拼音搜索预期 | 搜索框 hint 明确说明"支持中文/英文" |
| 收藏存储健壮性 | DataStore + Gson 序列化，不引入 Room |
| API 状态反馈 | UiState 密封类（Loading/Success/Error）+ 重试按钮 |
| Gson 一致性 | 统一使用 Gson 解析（现有依赖，不混用 JSONObject） |
| 屏幕切换 | 播放成功后跳转 NowPlaying；播放失败不跳转 |
| 搜索入口冲突 | 现有全局 SearchBar 仅用于 NAS 搜索；NETWORK Tab 内独立搜索按钮 |
| 未连接时 Network Tab | LibraryScreen 状态判断增加 `activeTab != NETWORK` 条件 |
| 收藏按钮交互 | NetworkSongRow 内置独立焦点收藏按钮，NowPlayingScreen 扩展 isNetworkSong 判断 |
| 设置页选择器 | 复用 PlayModeSelector 横向按钮模式，不用下拉弹窗 |
| BACK 键层级 | TextInputDialog → 列表回到顶部 → 清空搜索 → 返回 NowPlaying → 退出确认，多层优先级处理 |
| 列表项复用 | 新建 NetworkSongRow（无时长/次数字段），不复用 NAS SongRow |
| 多源 fallback 标签 | Song.networkSource 字段始终记录实际返回源，UI 正确显示来源标签 |

### 剩余风险

- **Meting-API 下架**：公益服务，无法控制。通过多源容错+设置页切换缓解
- **播放链接时效性**：每次播放实时解析，不缓存。增加一次网络请求但保证链接有效（注：v2.3.0 已添加 5 分钟缓存）
- **歌词获取可能失败**：回退到已有 `LyricsNetworkProvider`（酷狗/网易云），多重保证

### 已解决的风险（v2.3.0 更新）

- ✅ ~~网络歌曲编码问题（待实现）~~：已在 Phase 4 通过 `EncodingUtils.fixEncoding()` 解决
- ✅ ~~网络收藏无大小限制（待优化）~~：已在 Phase 4 添加 LRU 上限 500 条
- ✅ ~~播放链接时效性~~：已添加 5 分钟 TTL 缓存，平衡时效性和网络开销
