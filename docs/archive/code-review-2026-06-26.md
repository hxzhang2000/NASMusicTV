# Code Review — 2026-06-26

审查范围：全项目核心模块（ViewModel、PlayerManager、BackendAdapter、歌词系统、UI、数据持久化）。

> **处理状态**：v2.4.2 已修复 9 项（#1/#2/#3/#7/#8/#9/#10/#11/#12），4 项不修改（#5 用户决定跳过；#6/#4/#13 low 优先级暂不修改）。详见 CHANGELOG.md v2.4.2 节。

---

## HIGH

### 1. `runBlocking` in `AppPreferences` — ANR 风险

- **文件**：`data/prefs/AppPreferences.kt`（第 137、259、273、299、368 行）
- **描述**：5 处 `runBlocking` 调用（`getRecentSongIdsSync`、`getDefaultNetworkSourceSync`、`getMetingApiBaseUrlSync`、`getNetworkFavoritesSync`、`getLastQueueSync`）。这些方法在 `viewModelScope.launch` 或 lambda 中被调用，完全可以用 `suspend` + `first()` 替代。在主线程上调用会导致 ANR。
- **建议**：将 `*Sync` 方法改为 `suspend` 函数，调用方已在协程中，无需阻塞。

### 2. `NetworkMusicManager.playUrlCache` 线程不安全

- **文件**：`backend/network/NetworkMusicManager.kt`（第 49 行）
- **描述**：`mutableMapOf<String, CachedPlayUrl>()` 作为播放链接缓存，`resolvePlayUrl()` 在 `Dispatchers.IO` 上执行，多个协程并发读写会导致数据竞争。
- **建议**：改用 `ConcurrentHashMap` 或加 `synchronized` 块。

### 3. `PlayerManager.seekPending` 缺少 `@Volatile`

- **文件**：`player/PlayerManager.kt`（第 55 行）
- **描述**：`seekPending` 在主线程（`seekTo`）和 ExoPlayer 回调线程（`onIsPlayingChanged`、`onPositionDiscontinuity`）之间共享，没有 `@Volatile` 或其他同步机制，可能导致可见性问题。
- **建议**：添加 `@Volatile` 注解，或改用 `AtomicBoolean`。

---

## MEDIUM

### 4. 多个 OkHttpClient 实例浪费资源

- **文件**：`backend/impl/JellyfinAdapter.kt`、`backend/impl/NavidromeAdapter.kt`、`lyrics/LyricsNetworkProvider.kt`、`backend/network/MetingApiService.kt`
- **描述**：4 处各自创建独立的 `OkHttpClient`，每个都有独立的连接池和线程池。
- **建议**：通过 `NasMusicApp` 注入共享的单例 `OkHttpClient`，减少内存和线程开销。

### 5. `MainViewModel` 是上帝类（~1500 行）

- **文件**：`ui/viewmodel/MainViewModel.kt`
- **描述**：承担了导航、连接管理、曲库加载、播放控制、歌词、收藏、最近播放、搜索、网络音乐、队列管理等全部职责。违反单一职责原则，难以测试。
- **建议**：拆分为 `PlaybackController`、`LibraryLoader`、`ConnectionManager` 等辅助类。

### 6. `LibraryScreen.kt` 62KB — 过大

- **文件**：`ui/screens/LibraryScreen.kt`
- **描述**：单个 Composable 文件过大，包含所有 Tab 的逻辑。
- **建议**：拆分为独立的 Tab 内容组件（`SongsTab`、`AlbumsTab`、`ArtistsTab` 等）。

### 7. 进度轮询间隔与文档不一致

- **文件**：`player/PlayerManager.kt`（第 28 行）
- **描述**：AGENTS.md 记载 "500ms polling loop"，实际使用 `postDelayed(this, 1000)`。
- **建议**：统一文档与代码，确认实际需要的轮询间隔。

### 8. `PlayMode.values()` 已废弃

- **文件**：`ui/viewmodel/MainViewModel.kt`（`togglePlayMode()` 方法）
- **描述**：Kotlin 1.9+ 建议使用 `PlayMode.entries` 代替 `PlayMode.values()`，后者每次创建新数组。
- **建议**：改为 `PlayMode.entries`。

### 9. Jellyfin `getAlbums()` 硬编码 Limit=1000

- **文件**：`backend/impl/JellyfinAdapter.kt`（第 113 行）
- **描述**：`Limit=1000`，超过 1000 张专辑的用户会丢失数据。`getArtists()` 已实现分页循环加载，`getAlbums()` 也应同样处理。
- **建议**：参照 `getArtists()` 的分页循环模式重写 `getAlbums()`。

---

## LOW

### 10. `BackendRegistry` 文档与实现不一致

- **描述**：AGENTS.md 声称 `BackendRegistry` 是 "Kotlin `object` singleton"，但实际是普通类，在 `NasMusicApp.onCreate()` 中实例化。
- **建议**：更新 AGENTS.md 文档。

### 11. 日志混用

- **描述**：代码中混用 `android.util.Log` 和 `AppLog`，部分文件（如 `NetworkMusicManager`）同时使用两者。
- **建议**：统一使用 `AppLog`，Release 构建可自动抑制调试日志。

### 12. `Screen` 枚举和 `SongsPagingState` 定义在 ViewModel 文件中

- **文件**：`ui/viewmodel/MainViewModel.kt`（第 50、1488 行）
- **描述**：应移到独立文件或 `data/model/` 包中，便于复用和维护。

### 13. `EncodingUtils.fixEncoding` 中 30% 阈值是硬编码经验值

- **文件**：`util/EncodingUtils.kt`（第 61 行）
- **描述**：Latin-1 扩展字符占比 >30% 才触发 GBK 回退，可能漏掉短文本或误触发。
- **建议**：考虑增加更精确的编码检测逻辑（如 ICU4J 的 CharsetDetector）。

---

## 总结

代码整体结构清晰，架构分层合理（ViewModel -> BackendAdapter -> 后端实现），歌词系统多源 fallback、队列持久化恢复、网络歌曲播放链接缓存等功能设计得当。主要风险集中在线程安全（`runBlocking`、无同步的共享状态）和文件过大（MainViewModel、LibraryScreen）。

**优先修复**：#1、#2、#3（线程安全问题）。
**其次**：#4、#5、#6（资源浪费和可维护性）。
