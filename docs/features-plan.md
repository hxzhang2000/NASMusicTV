# NASMusicTV 功能优化方案

> 基于代码库全量分析产出的功能规划，按主题分组。
> 排除项：播放队列持久化、睡眠定时器、播放速度控制、外部播放控制、歌词偏移调节、离线下载。

---

## 目录

- [A. 曲库与浏览增强](#a-曲库与浏览增强)
- [B. 交互体验增强](#b-交互体验增强)
- [C. 播放功能提升](#c-播放功能提升)
- [D. 服务与稳定性](#d-服务与稳定性)
- [E. 代码质量与架构](#e-代码质量与架构)
- [F. 后端扩展](#f-后端扩展)
- [G. 可选新功能](#g-可选新功能)

---

## A. 曲库与浏览增强

### A-1 专辑详情页

**现状**：点击专辑卡片直接开始播放该专辑所有歌曲，无法看到专辑内的曲目列表。

**方案**：新增 AlbumDetailScreen，点击专辑时先导航到详情页，展示：
- 专辑封面（大图）
- 专辑名 + 演唱者 + 年份
- 曲目列表（带序号、时长），可逐首选播
- 底部「播放全部」按钮

**涉及文件**：
| 文件 | 改动 |
|------|------|
| `ui/viewmodel/MainViewModel.kt` | 新增 `Screen.ALBUM_DETAIL` + `selectedAlbum` 状态 |
| `ui/screens/NowPlayingScreen.kt` 旁新增 `AlbumDetailScreen.kt` | 新页面 |
| `ui/MainActivity.kt` | `when(currentScreen)` 新增分支 |
| `backend/BackendAdapter.kt` | 已有 `getAlbumSongs()` 可用，无需改动 |

**复杂度**：中等（~250 行新代码，纯 UI）

---

### A-2 演唱者详情页

**现状**：点击 ArtistCard 直接播放该歌手歌曲，无法浏览歌手的所有专辑/歌曲再选播。

**方案**：新增 ArtistDetailScreen，展示：
- 歌手头像/封面（大图）
- 歌手名 + 歌曲数量
- 该歌手所有歌曲列表，可逐首选播
- 底部「播放全部」按钮

**涉及文件**：
| 文件 | 改动 |
|------|------|
| `ui/viewmodel/MainViewModel.kt` | 新增 `Screen.ARTIST_DETAIL` + `selectedArtist` 状态 |
| 新增 `ArtistDetailScreen.kt` | 新页面 |
| `ui/MainActivity.kt` | `when(currentScreen)` 新增分支 |
| `backend/BackendAdapter.kt` | 已有 `getArtistSongs()` 可用 |

**复杂度**：中等（~200 行新代码）

---

### A-3 曲库过滤增强（流派/年代）

**现状**：搜索仅支持拼音首字母和子串匹配，没有按流派、年代过滤。

**方案**：

在 `LibraryScreen` 的 Tab 栏中增加「流派」和「年代」两个 tab，分别展示：

**「流派」tab**：
- 加载后端返回的流派列表（每个流派显示名称 + 歌曲数）
- 选中某个流派 → 进入该流派的歌曲列表（可复用歌曲列表组件）
- 点击歌曲播放，底部「播放全部」按钮

**「年代」tab**：
- 预定义年代区间：2020s / 2010s / 2000s / 1990s / 1980s / 更早
- 或者从曲库中提取所有歌曲的年份范围，自动分段
- 选中年代区间 → 显示该年代的所有歌曲

1. **BackendAdapter 新增接口**：
   - `getGenres(): List<Genre>` — 获取所有流派（含歌曲数）
   - `getSongsByGenre(genre: String): List<Song>` — 按流派过滤
   - `getSongsByYearRange(fromYear: Int, toYear: Int): List<Song>` — 按年代过滤

2. **Jellyfin 实现**：
   - `GET /Genres` → 流派列表
   - `GET /Items?GenreIds={id}&IncludeItemTypes=Audio` → 按流派过滤
   - `GET /Items?Years={year}&IncludeItemTypes=Audio` → 按年份过滤

3. **Navidrome 实现**：
   - `getGenres.view` → 流派列表
   - `getSongsByGenre.view?genre={name}` → 按流派过滤
   - `getAlbumList2.view?type=byYear&fromYear={y}&toYear={y}` → 按年代过滤

**涉及文件**：
| 文件 | 改动 |
|------|------|
| `backend/BackendAdapter.kt` | 新增接口方法 |
| `backend/impl/JellyfinAdapter.kt` | 实现 |
| `backend/impl/NavidromeAdapter.kt` | 实现 |
| `data/model/` | 新增 `Genre` 模型 |
| `ui/screens/LibraryScreen.kt` | Tab 栏扩展 + 两个新 tab 页面 |

**复杂度**：较高（后端+前端，跨 5 文件）

---

### A-4 多歌唱家拆分展示

**现状**：后端返回的歌曲 artist 字段是原始字符串，如 `"张三 & 李四"`、`"A feat. B"`。曲库的"歌唱家"列表直接按此字符串展示，这类合唱歌曲只会出现在一个组合条目下，不会被分到各个歌唱家名下。

**方案**：

1. **新增工具类 `ArtistSplitter`**（`util/ArtistSplitter.kt`）：
   ```kotlin
   object ArtistSplitter {
       // 分隔符优先级列表，按长度降序（优先匹配长分隔符）
       private val delimiters = listOf(
           Regex("\\s+feat\\.", RegexOption.IGNORE_CASE),   // "A feat. B" 或 "A feat.B"
           Regex("\\s+ft\\.", RegexOption.IGNORE_CASE),      // "A ft. B"
           Regex("\\s+with\\s+", RegexOption.IGNORE_CASE),   // "A with B"
           Regex("\\s*[&/、×]\\s*"),                          // "A&B", "A & B", "A/B", "A、B", "A×B"
           Regex("\\s+vs\\.?\\s+", RegexOption.IGNORE_CASE), // "A vs B", "A vs. B"
       )

       fun split(artist: String): List<String> {
           for (delim in delimiters) {
               val parts = artist.split(delim).map { it.trim() }.filter { it.isNotBlank() }
               if (parts.size > 1) return parts.distinct()
           }
           return listOf(artist.trim())
       }
   }
   ```

2. **ViewModel 改造**（`MainViewModel.kt`）：
   - `loadArtists()` 从后端获取原始艺术家列表后，对每个 artist 调用 `ArtistSplitter.split()` 展开
   - 展开后去重，得到真正的独立歌唱家列表
   - 维护 `songArtistsMap: Map<String, List<String>>`（songId → 拆分后的歌唱家列表）
   - 维护 `artistSongsMap: Map<String, List<Song>>`（歌唱家 → 歌曲列表）
   - `loadSongs()` 中，每首歌的 `artist` 字段展示原始字符串（保留完整信息），但后台 mapping 按拆分后的艺术家进行

3. **歌唱家 tab 变化**（`LibraryScreen.kt`）：
   - 原"歌唱家"列表不再显示 `"张三 & 李四"` 这样的组合条目
   - 只显示拆分后的独立歌唱家：`"张三"`、`"李四"`
   - 每个独立歌唱家的卡片显示该歌手参与的总歌曲数（含独唱和合唱）

4. **歌唱家详情页**（复用 A-2 的 `ArtistDetailScreen`）：
   - 点击张三 → 展示张三参与的所有歌曲
   - 合唱歌曲 `"张三 & 李四"` 同时出现在张三和李四的详情页中
   - 歌曲列表行显示完整的原始 artist 字段（如 `"张三 & 李四"`）

5. **播放页显示**（`NowPlayingScreen.kt`）：
   - 当前显示的 `artist` 字段保持原始字符串不变（`"张三 & 李四"`）

**涉及文件**：
| 文件 | 改动 |
|------|------|
| 新增 `util/ArtistSplitter.kt` | 拆分逻辑 |
| `ui/viewmodel/MainViewModel.kt` | `loadArtists()` / `loadSongs()` 增加拆分展开 |
| `ui/screens/LibraryScreen.kt` | 艺术家列表只展示独立歌唱家 |
| `ui/screens/NowPlayingScreen.kt` | 艺术家显示不变（原始字符串） |
| `data/model/Song.kt` | 不修改，artist 字段保持原始值 |

**复杂度**：中等（~150 行，核心是拆分工具 + ViewModel 映射逻辑）

---

## B. 交互体验增强

### B-1 收藏/喜欢功能

**现状**：没有任何收藏机制，播放页和曲库页没有心形/星标按钮。

**方案**：

1. **BackendAdapter 新增接口**：
   - `suspend fun toggleFavorite(songId: String): Boolean`
   - `suspend fun getFavorites(): List<Song>`

2. **NowPlayingScreen 增加心形按钮**：右下角或标题旁，点击切换状态

3. **曲库增加「我的收藏」入口**：LibraryScreen Tab 栏增加第四个 tab，或在侧边栏增加入口

4. **Jellyfin 实现**：
   - `POST /Items/{id}/Favorite` — 切换收藏
   - `GET /Items?Filters=IsFavorite&IncludeItemTypes=Audio` — 获取收藏

5. **Navidrome 实现**：
   - `POST /rest/star?id={id}` / `POST /rest/unstar?id={id}`
   - `GET /rest/getStarred2.view` — 获取收藏

6. **ViewModel 缓存**：在 `MainViewModel` 中维护 `favoriteIds: Set<String>` 避免频繁请求

**涉及文件**：
| 文件 | 改动 |
|------|------|
| `backend/BackendAdapter.kt` | 新增 `toggleFavorite()` / `getFavorites()` |
| `backend/impl/JellyfinAdapter.kt` | 实现 |
| `backend/impl/NavidromeAdapter.kt` | 实现 |
| `ui/viewmodel/MainViewModel.kt` | 新增 `favoriteIds` 状态 + 方法 |
| `ui/screens/NowPlayingScreen.kt` | 新增心形按钮 |
| `ui/screens/LibraryScreen.kt` | 新增收藏 tab |

**复杂度**：较高（后端+前端，跨 6 文件）

---

### B-2 最近播放 & 播放次数

**现状**：播放记录完全丢失，无历史功能。

**方案**：

1. **DataStore 扩展**：新增 `recentSongs` 和 `playCounts` 偏好
   ```kotlin
   val recentSongs: Flow<List<String>>  // songId 列表，最多 50 条
   val playCounts: Flow<Map<String, Int>>  // songId → 播放次数
   ```

2. **MainViewModel 记录逻辑**：每次 `playSong()` 时更新
   - 添加到最近播放列表（去重 + LRU 50 条）
   - 播放次数 +1

3. **UI 展示**：LibraryScreen 增加「最近播放」或「播放最多」入口

**涉及文件**：
| 文件 | 改动 |
|------|------|
| `data/prefs/AppPreferences.kt` | 新增存储字段 |
| `ui/viewmodel/MainViewModel.kt` | 新增记录逻辑 |
| `ui/screens/LibraryScreen.kt` | 新增入口 |

**复杂度**：中等（纯前端，不涉及后端 API）

---

### B-3 歌词卡拉 OK 逐字高亮

**现状**：歌词按行滚动高亮当前行，没有逐字高亮效果。

**方案**：

`LyricsView.kt` 改造：
- 解析 LRC 中 `<mm:ss.xx>` 标签获取逐字时间戳
- `Canvas` 绘制逐字高亮进度条/填充效果
- 可选两种模式：逐行高亮（现有） / 逐字卡拉 OK

**涉及文件**：
| 文件 | 改动 |
|------|------|
| `ui/components/LyricsView.kt` | 新增逐字高亮绘制 |
| `lyrics/LrcParser.kt` | 解析逐字标签（增强现有解析器） |
| `ui/screens/NowPlayingScreen.kt` | 模式切换按钮 |

**复杂度**：中等（纯 UI，Canvas 绘制）

---

### B-4 均衡器

**现状**：无音频效果调节。

**方案**：

1. **`PlayerManager` 集成**：利用 ExoPlayer 的 `AudioProcessor` 接口或 Android 的 `AudioEffect` API
2. **新增 EqualizerScreen**：TV 友好的 D-pad 滑块（60Hz~16kHz 频段调节）
3. **预置方案**：Normal / Pop / Rock / Classical / Jazz / Custom
4. **持久化**：保存到 DataStore

**注意事项**：
- 部分 Android TV 设备可能不支持 AudioEffect（需要 `hasDiscreteVolumes` 检查）
- 需要 `RECORD_AUDIO` 权限（某些设备）
- 体验差别取决于电视音响硬件

**涉及文件**：
| 文件 | 改动 |
|------|------|
| `player/PlayerManager.kt` | 新增均衡器控制 |
| 新增 `EqualizerScreen.kt` | 新页面 |
| `data/prefs/AppPreferences.kt` | 保存均衡器设置 |
| `ui/screens/SettingsScreen.kt` | 增加均衡器入口 |

**复杂度**：较高（Android 音频 API + UI）

---

### B-5 封面图全屏背景 + 歌词叠加

**现状**：封面图在播放页左侧固定大小显示，右侧显示歌词。

**方案**：在 NowPlayingScreen 中点击封面图或按下确认键 → 切换布局模式：

- **常规模式**（现有布局）：左侧封面 + 右侧歌词
- **沉浸模式**：封面图放大至全屏作为背景（高斯模糊 + 半透明遮罩），歌词叠加在封面之上字滚动
- 再次点击封面/按 BACK 恢复常规布局

**涉及文件**：
| 文件 | 改动 |
|------|------|
| `ui/screens/NowPlayingScreen.kt` | 新增沉浸模式状态 + 布局切换逻辑 |

**复杂度**：低（~80 行，纯 UI 布局切换）

---



## C. 播放功能提升

### C-1 播放队列管理增强

**现状**：`QueueScreen` 支持移除单曲和清空队列，但不能拖动排序。

**方案**：
- 在 QueueScreen 中支持上下键移动曲目顺序（选中 → 按菜单键 → 移动）
- 增加「下一首播放」选项（右键菜单）

**涉及文件**：
| 文件 | 改动 |
|------|------|
| `player/PlayerManager.kt` | 新增 `moveItem(from, to)` 方法 |
| `ui/screens/QueueScreen.kt` | 新增排序交互 |

**复杂度**：低（~100 行）

---

### C-2 无间断播放 & 预加载

**现状**：曲目切换时有短暂停顿，无预加载。

**方案**：

1. **Gapless playback**：ExoPlayer 通过 `setNextMediaItem()` 可在当前曲目播放时预加载下一首
2. **PlayerManager 改造**：`playSong()` 时同步调用 `player.setNextMediaItem(nextSongMediaItem)`
3. **Crossfade**：可选 `CrossfadeMediaSource.Factory` 实现淡入淡出

**涉及文件**：
| 文件 | 改动 |
|------|------|
| `player/PlayerManager.kt` | `playSong()` 增加预加载逻辑 |
| `player/PlaybackService.kt` | ExoPlayer 初始化增加 crossfade 配置 |

**复杂度**：低（~30 行，配置改动）

---

## D. 服务与稳定性

### D-1 后台服务加固 (MediaLibrarySession + 前台通知)

**现状**：
- `PlaybackService` 继承 `MediaLibraryService`，但 `MediaLibrarySession.Callback` 空实现（line 63）
- 无前台通知（Android 14+ 可能杀服务）
- 无 `onGetBrowserRoot()` / `onLoadChildren()`（不能接浏览器/手机控制）

**方案**：

1. **实现 `Callback` 方法**：
   - `onPlay()` / `onPause()` / `onStop()`
   - `onSkipToNext()` / `onSkipToPrevious()`
   - `onSeekTo()`
   - `onSetMediaItems()` — 外部设置播放列表
   - 全部委托给 `PlayerManager`

2. **前台通知**：
   - 创建 `NotificationChannel`（id: `playback_channel`）
   - 构建 `MediaNotification.Provider`
   - 在 `onCreate()` 调用 `startForeground(NOTIFICATION_ID, notification)`

3. **Browser 支持（可选）**：
   - 实现 `onGetBrowserRoot()` 返回根节点
   - 实现 `onLoadChildren()` 返回曲库结构

**涉及文件**：
| 文件 | 改动 |
|------|------|
| `player/PlaybackService.kt` | 核心修改（~150 行） |
| `player/PlayerManager.kt` | 可能暴露更多方法给 Service |
| `AndroidManifest.xml` | 声明前台服务权限 + notification 权限 |

**复杂度**：中等（Media3 规范实现，前后依赖明确）

---

### D-2 网络状态监听 + 自动重连

**现状**：断网后无感知，不会自动恢复。

**方案**：

1. **`MainViewModel` 增加 `ConnectivityManager` 监听**：
   ```kotlin
   val connectivity = context.getSystemService<ConnectivityManager>()
   connectivity.registerNetworkCallback(builder.build(), object : ConnectivityManager.NetworkCallback() {
       override fun onAvailable(network: Network) {
           // 触发自动重连
       }
       override fun onLost(network: Network) {
           // 更新连接状态，UI 显示离线提示
       }
   })
   ```

2. **UI 反馈**：
   - 播放页顶部显示"网络已断开"横幅
   - 网络恢复后自动尝试重连（指数退避，最多 3 次）

3. **曲库缓存**：断网时显示缓存数据（如有），标注"离线模式"

**涉及文件**：
| 文件 | 改动 |
|------|------|
| `ui/viewmodel/MainViewModel.kt` | 新增网络监听 + 重连逻辑 |
| `ui/screens/NowPlayingScreen.kt` | 网络状态横幅 |
| `ui/MainActivity.kt` | 生命周期中注册/注销回调 |

**复杂度**：中等（~200 行，跨层通信）

---

### D-3 错误处理增强

**现状**：后端适配器中所有 `try/catch` 都是 `catch(e: Exception) { emptyList() }`，无日志无重试。

**方案**：

1. **区分错误类型**：`Result<List<T>>` 替代直接返回 `List<T>`
   - 或保留现有签名但增加日志级别：`Log.w(tag, "getAlbums failed", e)`
2. **UI 层处理**：ViewModel 感知加载失败，显示重试按钮而非空白页面
3. **指数退避重试**：关键操作（连接、加载曲库）失败后自动重试 1-3 次

**涉及文件**：
| 文件 | 改动 |
|------|------|
| `backend/impl/JellyfinAdapter.kt` | 所有 catch 块增强日志 |
| `backend/impl/NavidromeAdapter.kt` | 同上 |
| `ui/viewmodel/MainViewModel.kt` | 加载状态增加 Error 分支 |

**复杂度**：中等（散点修改，约 10 处 try/catch）

---

## E. 代码质量与架构

### E-1 状态管理统一

**现状**：`PlayerManager` 是裸单例，`MainViewModel` 镜像暴露其状态。双向依赖和不一致风险。

**方案**：

逐步将播放状态的中心从 `PlayerManager` 转移到 `MainViewModel`：
- `PlayerManager` 降级为纯播放引擎封装（create / play / pause / seek / queue）
- `MainViewModel` 持有所有 StateFlow，成为单一可信源
- `PlayerManager` 不再暴露 StateFlow，改为回调或方法返回值

**涉及文件**：
| 文件 | 改动 |
|------|------|
| `player/PlayerManager.kt` | 重构接口（缩小职责） |
| `ui/viewmodel/MainViewModel.kt` | 接收播放状态管理 |
| `ui/screens/NowPlayingScreen.kt` | 更改状态引用 |
| `ui/components/PlayerControls.kt` | 更改状态引用 |
| `ui/screens/QueueScreen.kt` | 更改状态引用 |

**复杂度**：高（跨 5+ 文件，需仔细避免回归）

---

### E-2 测试

**现状**：零测试。

**方案**：

1. **单元测试（高优先级）**：
   - `util/PinyinUtils.kt` — 拼音转换 + 匹配逻辑（纯函数，易测）
   - `lyrics/LrcParser.kt` — LRC 解析（输入/输出明确）
   - `lyrics/LyricsManager.kt` — 歌词优先级逻辑

2. **依赖注入准备**：当前单例模式导致测试困难。可引入简单的手动 DI 或 Hilt。

**依赖**：
- 添加测试依赖：JUnit 5、MockK、Turbine（Flow 测试）、Coroutines Test

**复杂度**：中等（初始搭建 + 核心逻辑测试）

---

### E-3 清理废弃代码

**现状**：
- `backend/jellyfin/` — 旧 Retrofit Jellyfin 实现（未用）
- `backend/navidrome/` — 旧 Retrofit Navidrome 实现（未用）
- 这两个目录共约 400-500 行死代码

**方案**：直接删除 `backend/jellyfin/` 和 `backend/navidrome/` 两个目录。

**涉及文件**：
| 路径 | 操作 |
|------|------|
| `backend/jellyfin/` | 删除目录 |
| `backend/navidrome/` | 删除目录 |
| `app/build.gradle.kts` | 检查 `implementation("com.squareup.retrofit2:retrofit")` 是否仍有其他引用 |

**复杂度**：低（纯删除）

---

### E-4 缓存管理 UI

**现状**：歌词和封面缓存存在 `cacheDir` 中，用户无法查看或清理。

**方案**：设置页增加「缓存管理」栏目
- 显示歌词缓存大小 + 封面缓存大小
- 「清除歌词缓存」「清除封面缓存」按钮
- 确认弹窗后执行删除

**涉及文件**：
| 文件 | 改动 |
|------|------|
| `ui/screens/SettingsScreen.kt` | 新增缓存管理栏目 |
| `lyrics/LyricsManager.kt` | 暴露 `getCacheSize()` / `clearCache()` 方法 |
| `player/CoverArtManager.kt` | 暴露 `getCacheSize()` / `clearCache()` 方法 |

**复杂度**：低（~100 行）

---

## F. 后端扩展

### F-1 BackendAdapter 接口扩展清单

**现状**：接口缺少 playlist、favorite、genre、scrobble 等 NAS 音乐服务的常见能力。

**新增接口**：

```kotlin
// 播放列表
suspend fun getPlaylists(): List<Playlist>
suspend fun createPlaylist(name: String): Playlist?
suspend fun deletePlaylist(playlistId: String): Boolean
suspend fun addToPlaylist(playlistId: String, songId: String): Boolean
suspend fun removeFromPlaylist(playlistId: String, songId: String): Boolean

// 收藏
suspend fun toggleFavorite(songId: String): Boolean
suspend fun getFavorites(): List<Song>

// 评分
suspend fun setRating(songId: String, rating: Int): Boolean

// 流派
suspend fun getGenres(): List<String>
suspend fun getSongsByGenre(genre: String): List<Song>

// Scrobble（记录播放历史到后端）
suspend fun scrobblePlay(songId: String, timestamp: Long): Boolean

// 随机歌曲
suspend fun getRandomSongs(limit: Int = 20): List<Song>
```

**涉及文件**：
| 文件 | 改动 |
|------|------|
| `backend/BackendAdapter.kt` | 新增接口（~20 行签名） |
| `backend/impl/JellyfinAdapter.kt` | 实现全部方法 |
| `backend/impl/NavidromeAdapter.kt` | 实现全部方法 |

**复杂度**：较高（两套后端独立实现，需了解各自 API）

**Jellyfin 对应 API**：
| 方法 | 端点 |
|------|------|
| getPlaylists | `GET /Items?IncludeItemTypes=Playlist` |
| createPlaylist | `POST /Playlists` |
| deletePlaylist | `DELETE /Playlists/{id}` |
| addToPlaylist | `POST /Items/{songId}/Playlists/{playlistId}/Items` |
| toggleFavorite | `POST /Items/{id}/Favorite` (toggle body: `{ "Favorite": true/false }`) |
| getFavorites | `GET /Items?Filters=IsFavorite&IncludeItemTypes=Audio` |
| setRating | `POST /Items/{id}/Rating` |
| getGenres | `GET /Genres` |
| getRandomSongs | `GET /Items?IncludeItemTypes=Audio&Limit=N&SortBy=Random` |
| scrobblePlay | `POST /Sessions/Playing` |

**Navidrome 对应 API (Subsonic)**：
| 方法 | 端点 |
|------|------|
| getPlaylists | `getPlaylists.view` |
| createPlaylist | `createPlaylist.view` |
| deletePlaylist | `deletePlaylist.view` |
| addToPlaylist | `updatePlaylist.view` (songIdToAdd) |
| toggleFavorite | `star.view` / `unstar.view` |
| getFavorites | `getStarred2.view` |
| setRating | `setRating.view` |
| getGenres | `getGenres.view` |
| getRandomSongs | `getRandomSongs.view` |
| scrobblePlay | `scrobble.view` |

---

## G. 可选新功能

### G-1 HDMI-CEC 集成

利用 Android TV 的 HDMI-CEC 标准按键（播放/暂停/快进/快退），当前 ExoPlayer 已响应部分 CEC 按键。可增强的行为：
- **长按快进/快退**：10 秒跳转（当前是单按 15 秒）
- **暂停时显示屏保**：降低烧屏风险

**复杂度**：低（几个 KeyEvent 映射）

### G-2 播放列表管理 UI

配合 F-1 的后端接口，增加完整的播放列表管理界面：
- 曲库侧边栏增加「播放列表」入口
- 创建/删除/重命名播放列表
- 从曲库和播放页将歌曲加入播放列表
- 浏览播放列表内容并播放

**复杂度**：高（纯 UI，~600 行，但后端已有对应接口后工作量主要是布局）

---

## 优先级建议

### 第一阶段（用户感知最强，改动量小）
1. **B-5 封面图全屏背景 + 歌词叠加** — ~80 行，纯 UI 布局切换
2. **C-2 无间断播放 & 预加载** — ~30 行，配置改动
3. **E-3 清理废弃代码** — 删除目录
4. **C-1 播放队列管理增强** — ~100 行

### 第二阶段（核心体验完善）
5. **A-1 专辑详情页** — 新页面，~250 行
6. **A-2 演唱者详情页** — 新页面，~200 行
7. **A-4 多歌唱家拆分展示** — ~150 行，拆分工具 + ViewModel 映射
8. **B-1 收藏/喜欢功能** — 后端 + UI，跨 6 文件
9. **B-2 最近播放 & 播放次数** — 纯前端，DataStore

### 第三阶段（服务 & 稳定性）
10. **D-1 后台服务加固** — Media3 规范实现
11. **D-2 网络状态监听 + 自动重连**
12. **D-3 错误处理增强**

### 第四阶段（后端拓展 & 架构）
13. **F-1 BackendAdapter 接口扩展**（播放列表、评分、流派等）
14. **E-1 状态管理统一**
15. **E-2 测试**
16. **E-4 缓存管理 UI**

### 可选（按需投入）
17. **A-3 曲库过滤增强（流派/年代）**
18. **B-3 歌词卡拉 OK 逐字高亮**
19. **B-4 均衡器**
20. **G-1 HDMI-CEC 集成**
21. **G-2 播放列表管理 UI**
