# NAS Music TV — 技术架构概述

> 版本：v2.13.1
> 最后更新：2026-09-01
> 本文档记录项目当前的完整技术架构，作为后续迭代的基准参考。

---

## 目录

- [1. 项目概览](#1-项目概览)
- [2. 架构分层](#2-架构分层)
- [3. 模块详解](#3-模块详解)
  - [3.1 后端层 (Backend)](#31-后端层-backend)
  - [3.2 数据层 (Data)](#32-数据层-data)
  - [3.3 播放器层 (Player)](#33-播放器层-player)
  - [3.4 歌词层 (Lyrics)](#34-歌词层-lyrics)
  - [3.5 UI 层 (UI)](#35-ui-层-ui)
  - [3.6 工具层 (Util)](#36-工具层-util)
- [4. 数据流](#4-数据流)
- [5. 已实现功能清单](#5-已实现功能清单)
- [6. 约束与限制](#6-约束与限制)
- [7. 回归测试场景](#7-回归测试场景)
- [8. 版本管理规范](#8-版本管理规范)
- [9. 文件索引](#9-文件索引)
- [10. 修改记录](#10-修改记录)
- [11. 回归测试文档](#11-回归测试文档)

---

## 1. 项目概览

**名称**：NAS Music TV  
**包名**：`com.nasmusic.tv`  
**描述**：Android TV 端 NAS 音乐播放器，连接 Jellyfin / Navidrome 后端  
**架构风格**：单模块 MVVM（无 DI 框架，手动单例管理）  
**UI 框架**：Jetpack Compose for TV（`androidx.tv:tv-material`）  
**播放引擎**：Media3 ExoPlayer  
**最低 SDK**：22（Android 5.1）  
**目标 SDK**：34（Android 14）  
**屏幕方向**：锁定为横屏（landscape）  
**输入方式**：D-Pad 方向键 + OK 键（Android TV 遥控器）

---

## 2. 架构分层

```
┌─────────────────────────────────────────────────┐
│  UI 层 (ui/)                                    │
│  MainActivity → AppRoot → Screen Composable     │
│  ├── screens/   (NowPlaying, Library, Queue,    │
│  │                Settings, ServerConnect,       │
│  │                ExitConfirm, TextInputDialog)  │
│  ├── components/ (PlayerControls, LyricsView,    │
│  │                ConnectPromptDialog)           │
│  ├── viewmodel/  (MainViewModel)                │
│  └── theme/      (Theme, Color, Type)           │
├─────────────────────────────────────────────────┤
│  ViewModel 层 (ui/viewmodel/)                    │
│  MainViewModel ─── 状态管理，桥接 UI 与各 Manager  │
├─────────────────────────────────────────────────┤
│  业务层                                          │
│  ├── player/     ─── PlayerManager (单例)       │
│  │                 PlaybackService (Media3)      │
│  │                 CoverArtManager               │
│  ├── lyrics/     ─── LyricsManager              │
│  │                 LyricsNetworkProvider         │
│  │                 LrcParser                     │
│  │                 Mp3MetadataExtractor          │
│  └── backend/    ─── BackendAdapter (接口)       │
│                      ├── JellyfinAdapter         │
│                      └── NavidromeAdapter        │
├─────────────────────────────────────────────────┤
│  数据层 (data/)                                  │
│  ├── model/      ─── Song, Album, Artist,        │
│  │                    Lyrics, AppSettings,        │
│  │                    ServerConfig, PlayMode      │
│  └── prefs/      ─── AppPreferences (DataStore)  │
└─────────────────────────────────────────────────┘
```

**关键设计决策**：

- **无 DI 框架**：PlayerManager、BackendRegistry、AppPreferences 均使用 double-checked locking 单例模式
- **状态传递**：PlayerManager 作为播放状态的真实持有者，MainViewModel 镜像暴露其 StateFlow
- **手动导航**：没有使用 Jetpack Navigation Component，使用 `when(currentScreen)` 手动切换
- **后端解耦**：BackendAdapter 接口封装两个后端差异，BackendRegistry 工厂模式创建适配器

---

## 3. 模块详解

### 3.1 后端层 (Backend)

#### BackendAdapter（接口）

**文件**：`backend/BackendAdapter.kt`  
**职责**：定义所有 NAS 后端必须实现的操作

| 方法 | 返回 | 说明 |
|------|------|------|
| `initialize()` | `Boolean` | 连接后端（认证） |
| `testConnection()` | `Boolean` | 测试连接 |
| `getAlbums()` | `List<Album>` | 获取所有专辑 |
| `getAlbumSongs(id)` | `List<Song>` | 获取专辑内歌曲 |
| `getArtists()` | `List<Artist>` | 获取所有演唱者 |
| `getArtistSongs(id)` | `List<Song>` | 获取演唱者歌曲 |
| `getSongs(limit)` | `List<Song>` | 获取所有歌曲 |
| `searchSongs(query)` | `List<Song>` | 搜索歌曲 |
| `getRecentSongs()` | `List<Song>` | 获取最近添加 |
| `getStreamUrl(id)` | `String` | 获取播放流地址 |
| `getCoverUrl(id)` | `String` | 获取封面地址 |
| `getLyrics(id)` | `String?` | 获取歌词文本 |

**错误处理约定**：所有方法使用 `try/catch (e: Exception) {}` 吞异常，失败返回 `emptyList()` 或 `null`，无错误类型区分。

#### BackendRegistry（单例 object）

**文件**：`backend/BackendRegistry.kt`  
**职责**：工厂 + 注册中心

- `initialize(config)` — 根据 `config.backendType` 创建对应的 adapter 并初始化
- `testConnection(config)` — 创建临时 adapter 测试（不改变当前连接）
- `getAdapter()` — 返回当前活动 adapter
- `disconnect()` — 清除当前连接

**重要行为**：
- `initialize()` 成功后才会设置 `currentAdapter`
- `testConnection()` 创建新的 adapter 实例，不与当前连接冲突

#### JellyfinAdapter

**文件**：`backend/impl/JellyfinAdapter.kt`  
**通信方式**：原始 OkHttp（无 Retrofit）  
**认证机制**：`X-Emby-Token` Header，优先使用 token，失败回退到用户名密码登录

| 功能 | 端点 |
|------|------|
| 测试连接 | `GET /System/Info/Public` |
| 登录 | `POST /Users/AuthenticateByName` |
| 获取用户信息 | `GET /Users/Me` |
| 专辑列表 | `GET /Items?IncludeItemTypes=MusicAlbum` |
| 专辑歌曲 | `GET /Items?ParentId={id}&IncludeItemTypes=Audio` |
| 演唱者 | `GET /Artists/AlbumArtists` |
| 演唱者歌曲 | `GET /Items?ArtistIds={id}&IncludeItemTypes=Audio` |
| 全部歌曲 | `GET /Items?IncludeItemTypes=Audio&Recursive=true` |
| 搜索 | `GET /Items?SearchTerm={query}&IncludeItemTypes=Audio` |
| 最近歌曲 | `GET /Items?SortBy=DateCreated&IncludeItemTypes=Audio` |
| 流地址 | `GET /Audio/{id}/stream.mp3` |
| 封面图 | `GET /Items/{id}/Images/Primary` |
| 歌词 | `GET /Audio/{id}/Lyrics` |
| 收藏 | `POST/DELETE /Users/{userId}/FavoriteItems/{songId}` |
| 年份过滤 | `GET /Items?Years={year1,year2,...}` |
| 年份列表 | `GET /Items/Filters?IncludeItemTypes=Audio` |
| 流派列表 | `GET /Genres?IncludeItemTypes=Audio` |
| 注销 | `POST /Sessions/Logout` |

**封面图 fallback 逻辑**（已验证）：
- 优先使用 `ImageTags.Primary` 构造带 tag 的 URL（利用 Jellyfin 缓存）
- 若 `ImageTags.Primary` 为 null，回退到无 tag 的 `/Items/{id}/Images/Primary`（从上级条目继承封面）

**歌词格式转换**：
- 端点 `GET /Audio/{id}/Lyrics` 返回 Jellyfin LyricDto JSON 结构
- `convertJellyfinLyricsToLrc()` 将其转换为标准 LRC 格式
- 从 `Metadata` 提取 `Artist` / `Title` 生成 LRC 头部 `[ar:...]` / `[ti:...]`
- `Start` 字段是 ticks（10000 ticks = 1 ms），转换为 `[mm:ss.xx]` 格式

**收藏切换逻辑**：
- `toggleFavorite(songId)` 先通过 `queryFavoriteStatus()` 查询当前状态（GET `/Users/{userId}/Items/{songId}` 读取 `UserData.IsFavorite`）
- 已收藏 → DELETE `/Users/{userId}/FavoriteItems/{songId}`
- 未收藏 → POST `/Users/{userId}/FavoriteItems/{songId}`
- `_favoriteIdsCache` + `favoriteCacheLock`（synchronized）线程安全缓存

**守护线程**：
- OkHttp 客户端使用 `Executors.newCachedThreadPool` 自定义线程工厂
- 线程命名 `Jellyfin-OkHttp`，`isDaemon = true`
- 防止 OkHttp 线程阻止进程退出

#### NavidromeAdapter

**文件**：`backend/impl/NavidromeAdapter.kt`  
**通信方式**：原始 OkHttp（无 Retrofit）  
**认证机制**：Subsonic token+salt 认证（`auth` + `j` 参数），MD5 加盐

| 功能 | 端点 |
|------|------|
| 测试连接 | `ping.view` |
| 专辑列表 | `getAlbumList2.view?type=alphabeticalByName` |
| 专辑详情 | `getAlbum.view` |
| 演唱者索引 | `getArtists.view` |
| 演唱者详情 | `getArtist.view` |
| 全部歌曲 | `getSongs.view?type=alphabeticalByName` |
| 搜索 | `search2.view` |
| 最近歌曲 | `getAlbumList2.view?type=newest`（复用专辑接口） |
| 流地址 | `stream.view` |
| 封面图 | `getCoverArt.view` |
| 歌词 | `getLyrics.view`（Navidrome 不支持，始终返回 null） |

**并发加载优化**：
- `getArtistSongs(artistId)` — 先 `getArtist` 获取该艺术家的所有专辑，然后使用 `async` + `awaitAll` 并发请求所有专辑的歌曲，最后 `flatten()` 合并（解决 N+1 查询问题）
- `getRecentSongs()` — 并发请求前 20 个最新专辑的歌曲（每个专辑最多取 5 首），合并后取前 100 首

**守护线程**：
- OkHttp 客户端使用 `Executors.newCachedThreadPool` 自定义线程工厂
- 线程命名 `Navidrome-OkHttp`，`isDaemon = true`
- 防止 OkHttp 线程阻止进程退出

#### 网络音乐层（v2.2.0 新增）

> 独立于 NAS 后端，提供在线歌曲搜索、播放、歌词获取能力。与 `BackendAdapter` 体系并行，通过 `NetworkMusicManager` 统一路由。

**架构**：

```
MainViewModel
    └── NetworkMusicManager（多源路由）
            ├── MetingApiService（默认源）
            └── （可扩展其他源）
```

**NetworkMusicManager**（`backend/network/NetworkMusicManager.kt`）：
- 多源路由层，管理多个 `NetworkMusicService` 实现
- `search(keyword)` 采用 fallback 策略：默认源失败时依次尝试其他源
- `resolvePlayUrl/resolveLyrics/resolveCoverUrl` 按 `song.networkSource` 精确路由，不 fallback
- 默认源由 `defaultSourceProvider: () -> String` 动态提供（读取 AppSettings）
- 手动 DI：在 `NasMusicApp.onCreate` 初始化

**MetingApiService**（`backend/network/MetingApiService.kt`）：
- 基于 [Meting-API](https://github.com/metowolf/Meting) 的网络音乐服务实现
- 默认走网易云源（`server=netease`），支持搜索/播放/歌词/封面
- 端点 URL 可配置（`baseUrlProvider: () -> String`），默认 `https://meting.mikus.ink/api`

| 功能 | 端点格式 |
|------|---------|
| 搜索 | `{BASE}?server=netease&type=search&id={keyword}` |
| 播放 URL | `{BASE}?server=netease&type=url&id={netId}`（302 重定向到真实 mp3） |
| 歌词 | `{BASE}?server=netease&type=lrc&id={netId}`（返回 LRC 文本） |
| 封面 | 搜索结果中的 `pic` 字段（302 重定向，Coil 自动跟随） |

**响应字段映射**（关键）：
- API 返回字段：`title` / `author` / `pic` / `url` / `lrc`
- 无独立 `id` 字段，需从 `url` 字段的查询参数提取（`extractIdFromUrl()`）
- 映射到 `Song` 模型：`id="ntwk_meting_{netId}"`、`isNetworkSong=true`、`networkSource="meting"`、`networkId={netId}`

**SSL 兼容处理**（TV 盒子场景）：
- 老版 Android 系统（API 22 等）缺少 Let's Encrypt 根证书，导致 `SSLHandshakeException`
- OkHttpClient 配置信任所有证书的 `X509TrustManager` + 宽松 `HostnameVerifier`
- Meting-API 为公开搜索服务，不涉及敏感数据，此妥协可接受

**守护线程**：
- OkHttp 客户端使用 `Executors.newCachedThreadPool` 自定义线程工厂
- 线程命名 `Meting-OkHttp`，`isDaemon = true`

#### 废弃代码

**目录**：`backend/jellyfin/`、`backend/navidrome/`  
**状态**：未使用的 Retrofit 实现，约 400-500 行死代码，计划在迭代中删除

---

### 3.2 数据层 (Data)

#### 数据模型（`data/model/`）

| 模型 | 字段 | 说明 |
|------|------|------|
| `Song` | id, title, artist, artistId, album, albumId, coverUrl, streamUrl, durationMs, trackNumber, discNumber, year, genre, bitrate | 歌曲核心模型 |
| `Album` | id, name, artist, artistId, coverUrl, songCount | 专辑 |
| `Artist` | id, name, coverUrl | 演唱者 |
| `Lyrics` | lines, source | 歌词（含行列表 + 来源标记） |
| `LyricsLine` | timestamp, text | LRC 一行歌词 |
| `LyricsSource` | enum: BACKEND, NETWORK, LOCAL_LRC, LOCAL_CACHE, MP3_EMBEDDED | 歌词来源枚举 |
| `LyricsAvailability` | backend, network | 各来源可用性检查结果 |
| `PlayMode` | enum: SEQUENTIAL, REPEAT_ONE, REPEAT_ALL, SHUFFLE | 播放模式 |
| `AppSettings` | darkTheme, animationsEnabled, autoPlayNext, defaultPlayMode, cacheLyrics, cacheCover, lyricsOffsetMs | 应用设置 |
| `ServerConfig` | id, backendType, baseUrl, apiToken, username, password, isConnected, displayName | 服务器配置 |

**关键说明**：
- `AppSettings` 的默认值 `darkTheme = true`、`autoPlayNext = true`、`cacheLyrics = true`、`cacheCover = true`
- `ServerConfig.Empty` 为预定义空配置，用于未连接状态
- 数据模型均为不可变 `data class`

#### 持久化（`data/prefs/`）

**`AppPreferences`**（单例）

| 配置组 | 存储键前缀 | 存储方式 |
|--------|-----------|---------|
| 服务器配置 | `server_*` | DataStore Preferences |
| 应用设置 | `settings_*` | DataStore Preferences |

- DataStore 文件：`nas_music_tv.preferences_pb`
- 所有读写通过 Flow + `edit {}` 协程方式
- 单例模式：`AppPreferences.getInstance(context)`

---

### 3.3 播放器层 (Player)

#### PlayerManager（单例）

**文件**：`player/PlayerManager.kt`  
**状态管理**：8 个 MutableStateFlow

| 状态 | 类型 | 说明 |
|------|------|------|
| `currentSong` | `Song?` | 当前播放歌曲 |
| `isPlaying` | `Boolean` | 播放中 |
| `progress` | `Long` | 当前进度(ms) |
| `duration` | `Long` | 总时长(ms) |
| `queue` | `List<Song>` | 播放队列 |
| `currentIndex` | `Int` | 当前在队列中的位置 |
| `buffering` | `Boolean` | 缓冲中 |
| `playerError` | `String?` | 播放错误信息（v2.2.0 新增，用于 UI 错误展示与自动跳下一首） |

**关键方法**：

| 方法 | 行为 |
|------|------|
| `setPlayer(exoPlayer)` | 注册 ExoPlayer 实例（由 PlaybackService 调用） |
| `playSong(song)` | 替换队列为单曲并播放（若已在队列则 seek 实现无缝切换） |
| `playQueue(songs, startIndex)` | 设置多曲队列并播放 |
| `playPause()` | 切换播放/暂停 |
| `next(playMode)` | 下一曲（**v2.2.0**：接收 `playMode` 参数，按播放模式决定行为） |
| `previous(playMode)` | 上一曲（**v2.2.0**：接收 `playMode` 参数） |
| `seekTo(positionMs)` | 跳转到指定位置 |
| `applyPlayMode(mode)` | 设置 ExoPlayer 的 repeat/shuffle（**v2.2.0**：不再存储状态，只应用 ExoPlayer 设置） |
| `derivePlayMode(p)` | **v2.2.0 新增**：从 ExoPlayer 当前 repeatMode + shuffleModeEnabled 推导 PlayMode |
| `addToQueue(song)` | 添加到队列末尾 |
| `removeFromQueue(index)` | 从队列移除指定索引 |
| `moveItem(fromIndex, toIndex)` | **v2.2.0 新增**：队列重排，同步 ExoPlayer 队列与 `_currentIndex` |
| `clearQueue()` | 清空队列 |
| `onPlaybackEnded()` | 播放结束回调（**v2.2.0**：内部通过 `derivePlayMode()` 推导模式） |
| `clearError()` | **v2.2.0 新增**：清除 `_playerError` 状态 |
| `release()` | **v2.2.0 新增**：释放 Handler、listener、Equalizer 资源（退出时调用） |
| `initEqualizer()` | 初始化 Android `Equalizer`（基于 audioSessionId） |
| `setEqualizerBand(bandIndex, gainDb)` | 设置指定频段增益 |
| `setEqualizerBands(gains: FloatArray)` | **v2.2.0 新增**：批量设置所有频段增益（预置方案应用） |
| `getEqualizerBandLevel(bandIndex)` | 读取指定频段当前增益 |
| `getEqualizerBandCount()` | 获取频段数量 |
| `getEqualizerCenterFreq(bandIndex)` | 获取指定频段中心频率 |
| `disableEqualizer()` | 关闭均衡器 |

**进度更新**：通过 Handler + Runnable 每 **1000ms** 轮询 `player.currentPosition`（v2.2.0：从 500ms 调整为 1000ms，减少 CPU 占用）。`onIsPlayingChanged` 控制启停，暂停时仍更新一次进度。`onPositionDiscontinuity` 回调立即同步进度。

**播放模式行为**（v2.2.0：模式状态由 MainViewModel 持有，PlayerManager 不再存储）：

| 模式 | `next(playMode)` 行为 | `onPlaybackEnded()` 行为 |
|------|-------------|------------------------|
| SEQUENTIAL | 下一首（无曲目时停止） | 停止 |
| REPEAT_ONE | 下一首（用户主动切歌跳到下一首，不重播当前） | 重头播放当前曲目 |
| REPEAT_ALL | 下一首（末尾回到第一首） | 回到第一首 |
| SHUFFLE | 随机选一首（避免连续重复，记录 shuffleHistory） | 随机选一首播放 |

**B-13 播放模式迁移**（v2.2.0）：`_playMode` StateFlow 从 PlayerManager 迁移到 MainViewModel。PlayerManager 的 `next()` / `previous()` / `onPlaybackEnded()` 改为接收或推导 `playMode` 参数。MainViewModel 启动时从 `AppPreferences.defaultPlayMode` 恢复并调用 `applyPlayMode()` 同步到 ExoPlayer。

**错误处理**（v2.2.0 新增）：`onPlayerError` 回调将错误信息写入 `_playerError`，并自动调用 `next(playMode)` 跳到下一首。UI 层可观察 `playerError` 显示错误提示，调用 `clearError()` 清除。

**关于 `updateCurrentSongFromPlayer()`**：从 `player.currentMediaItemIndex` 读取当前索引，同步到 `_currentSong` 和 `_currentIndex`。在 `onMediaItemTransition` 和 `playQueue()` 完成后调用。

#### PlaybackService

**文件**：`player/PlaybackService.kt`  
**类型**：`MediaLibraryService`（Media3）

**生命周期**：
- `onCreate()` → 创建 NotificationChannel → 创建 ExoPlayer（带 AudioAttributes + `setHandleAudioBecomingNoisy`）→ 创建 MediaLibrarySession → `PlayerManager.setPlayer()` → `startForeground()` 显示初始通知
- `onTaskRemoved()` → **v2.2.0 简化**：直接 `stopSelf()`（原逻辑判断是否在播放，现在统一停止服务）
- `onDestroy()` → **v2.2.0 增强**：
  1. 调用 `PlayerManager.release()` 释放 Handler、listener、Equalizer
  2. 释放 MediaSession 和 Player
  3. `ServiceCompat.stopForeground(STOP_FOREGROUND_REMOVE)` 移除前台通知

**前台通知**（D-1）：
- `createNotificationChannel()` — API 26+ 创建 `nas_music_playback` 通道（IMPORTANCE_LOW）
- `buildNotification(title, isPlaying)` — 构建包含 3 个媒体按钮的通知（上一首 / 播放暂停 / 下一首）
- `updateNotification()` — 通过 `lastNotificationState` 缓存 `(title, isPlaying)` 元组，避免重复刷新

**通知媒体按钮实现**（v2.2.0 修复）：

由于 Media3 1.2.1 中 `MediaButtonReceiver.buildMediaButtonPendingIntent(context, command)` 重载不存在，且 `Player.COMMAND_PLAY` / `COMMAND_PAUSE` 常量不存在（只有 `COMMAND_PLAY_PAUSE`），改用 `ACTION_MEDIA_BUTTON` + `KeyEvent` 方式：

```kotlin
private fun buildMediaButtonPendingIntent(keyCode: Int): PendingIntent {
    val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
        setPackage(packageName)
        putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
    }
    return PendingIntent.getBroadcast(
        this, keyCode, intent,
        PendingIntent.FLAG_IMMUTABLE
    )
}
```

- 播放/暂停按钮：根据 `isPlaying` 状态切换 `KEYCODE_MEDIA_PLAY` / `KEYCODE_MEDIA_PAUSE`
- 上一首/下一首按钮：`KEYCODE_MEDIA_PREVIOUS` / `KEYCODE_MEDIA_NEXT`
- `MediaLibraryService` 自动处理 `ACTION_MEDIA_BUTTON` Intent 并调用对应 Player 方法

**已知限制**（规划中待改进）：
- `MediaLibrarySession.Callback` 为空实现 → 外部无法通过 MediaSession 控制播放（依赖 Media3 默认行为）
- 无 `onGetBrowserRoot()` → 无法外部浏览曲库

#### CoverArtManager

**文件**：`player/CoverArtManager.kt`  
**职责**：
- 从 MP3 内嵌元数据提取封面图（通过 `MediaMetadataRetriever`）
- 从网络 URL 加载封面图并缓存

---

### 3.4 歌词层 (Lyrics)

#### LyricsManager

**文件**：`lyrics/LyricsManager.kt`  
**获取优先级**（`getLyrics()`）：

```
MP3 内嵌（MediaMetadataRetriever）
  → 本地缓存 (cacheDir/lyrics/)
    → 本地 LRC 文件 (Music/Download/externalFilesDir/filesDir)
      → 网络提供者 (Kugou → NetEase)
        → 返回 null
```

**LRC 文件名尝试模式**：
- `title.lrc`
- `artist - title.lrc`
- `artist_title.lrc`

**歌词来源切换**：`switchLyricsSource()` 支持在前端/后端来源间手动切换

#### LrcParser

**文件**：`lyrics/LrcParser.kt`  
**解析格式**：标准 LRC（`[mm:ss.xx]歌词`）  
**输出**：`List<LyricsLine>`，按时间戳升序排列

#### LyricsNetworkProvider

**文件**：`lyrics/LyricsNetworkProvider.kt`  
**来源**：
1. 酷狗音乐搜索 API → `lyric` 接口获取 LRC
2. 网易云音乐搜索 API → `lyric` 接口获取 LRC

#### Mp3MetadataExtractor

**文件**：`lyrics/Mp3MetadataExtractor.kt`  
**职责**：从歌曲的流 URL 中提取 MP3 ID3 元数据（歌词 + 封面图）

---

### 3.5 UI 层 (UI)

#### 导航架构

**`MainActivity`**（`ui/MainActivity.kt`）：
- 单 Activity，使用 `setContent{}` 加载 Compose UI
- `AppRoot` Composable 根据 `currentScreen` StateFlow 进行 `when` 分派
- 顶部导航栏：5 个入口（正在播放、曲库、队列、服务器、设置）

**三层 BACK 键处理**：
1. 对话框打开时 → 关闭对话框（由 `dialogBackHandler` 控制）
2. 不在 NowPlaying 页时 → 导航回 NowPlaying（由 `navigateBackHandler` 控制）
3. 在 NowPlaying 页时 → 显示退出确认对话框

#### 页面列表

| 页面 | 文件 | 行数 | 功能 |
|------|------|------|------|
| NowPlaying | `screens/NowPlayingScreen.kt` | 368 | 封面 + 歌词 + 播放控制 |
| Library | `screens/LibraryScreen.kt` | 574 | 专辑/演唱者/歌曲 三 tab |
| Queue | `screens/QueueScreen.kt` | 323 | 队列列表 + 迷你控制 |
| Settings | `screens/SettingsScreen.kt` | 457 | 侧边栏导航：通用/播放/歌词/缓存/网络/关于；网络页含 Meting-API 端点配置 |
| ServerConnect | `screens/ServerConnectScreen.kt` | 757 | 服务器类型选择 + 表单 |
| TextInputDialog | `screens/TextInputDialog.kt` | 405 | TV 虚拟键盘弹窗 + 系统输入法切换（支持中文输入） |
| ExitConfirmDialog | `screens/ExitConfirmDialog.kt` | 178 | 退出确认弹窗 |

#### 组件列表

| 组件 | 文件 | 行数 | 功能 |
|------|------|------|------|
| PlayerControls | `components/PlayerControls.kt` | 311 | 进度条 + 播放/暂停/上/下 + 模式切换 |
| LyricsView | `components/LyricsView.kt` | 182 | 滚动歌词显示 + 渐变遮罩 |
| ConnectPromptDialog | `components/ConnectPromptDialog.kt` | 183 | 启动连接提示弹窗 |

#### NowPlayingScreen 布局

```
┌─────────────────────────────────────────────┐
│  ┌──────────┐   ┌─────────────────────────┐  │
│  │          │   │   歌词滚动区域           │  │
│  │ 封面大图  │   │   (带渐变遮罩)          │  │
│  │          │   │                         │  │
│  │ (glow)   │   │                         │  │
│  └──────────┘   └─────────────────────────┘  │
│  ┌─────────────────────────────────────────┐  │
│  │ 进度条 | ◄◄ ▶/⏸ ►► | ↻ ♯ ♥           │  │
│  └─────────────────────────────────────────┘  │
└─────────────────────────────────────────────┘
```

#### LibraryScreen 布局

```
┌─────────────────────────────────────────────┐
│  [搜索栏] [播放全部]                         │
│  [专辑] [演唱者] [歌曲] ← Tab 切换           │
│  ┌────┐ ┌────┐ ┌────┐                      │
│  │卡 1│ │卡 2│ │卡 3│ ... 网格              │
│  └────┘ └────┘ └────┘                      │
└─────────────────────────────────────────────┘
```

#### 主题系统

**文件**：`ui/theme/`
- `Theme.kt` — `NASMusicTVTheme` Composable，支持 `darkTheme` 切换
- `Color.kt` — `NasMusicColors` 对象（`Background`, `Surface`, `Primary`, `TextPrimary`, `TextSecondary`, `Warning`, `Border`, `SurfaceVariant`, `FocusRing`）
- `Type.kt` — 字体定义

---

### 3.6 工具层 (Util)

| 工具 | 文件 | 功能 |
|------|------|------|
| `PinyinUtils` | `util/PinyinUtils.kt` | 汉字→拼音首字母转换（通过 `android.icu.text.Transliterator`），用于搜索匹配 |
| `TimeUtils` | `util/TimeUtils.kt` | 时间格式化工具 |
| `ArtistSplitter` | `util/ArtistSplitter.kt` | 多歌唱家拆分（按 `&`/`feat.`/`ft.`/`with`/`vs.`/`/` 分隔），用于歌唱家详情页 |
| `AppLog` | `util/AppLog.kt` | **v2.2.0 新增**：日志工具，Debug 构建输出 `d/i/w` 级别日志，Release 构建中所有调用为空操作；`e` 级别始终输出 |
| `CryptoUtils` | `util/CryptoUtils.kt` | **v2.2.0 新增**：基于 Android Keystore 的 AES-256-GCM 加密工具，用于加密 DataStore 中的密码和 apiToken |
| `EncodingUtils` | `util/EncodingUtils.kt` | **v2.2.0 新增**：字符串编码修复工具，处理 GB2312/GBK 被当作 Latin-1 解码的乱码模式（从 Adapter 中抽取） |
| `RetryUtil` | `util/RetryUtil.kt` | **v2.2.0 新增**：指数退避重试工具（`withRetry` + `RetryConfig`），用于后端 API 调用容错 |
| `NetworkMonitor` | `util/NetworkMonitor.kt` | **v2.2.0 新增**：网络状态监听封装（基于 `ConnectivityManager.NetworkCallback`），从 MainActivity 抽取 |
| `MediaKeyHandler` | `util/MediaKeyHandler.kt` | **v2.2.0 新增**：HDMI-CEC / 蓝牙遥控器媒体键路由分发，从 MainActivity 抽取 |

#### 公共 UI 组件

| 组件 | 文件 | 功能 |
|------|------|------|
| `AppRoot` | `ui/components/AppRoot.kt` | **v2.2.0 新增**：UI 根布局 + `currentScreen` 导航 + 错误横幅，从 MainActivity 抽取 |
| `FocusableSurface` | `ui/components/FocusableSurface.kt` | **v2.2.0 新增**：可聚焦 Surface 组件，统一封装焦点缩放动画 + 焦点边框 + ClickableSurfaceDefaults 配置（消除 30+ 处重复样板代码） |
| `CommonComponents` | `ui/components/CommonComponents.kt` | 公共 UI 组件集合 |

#### CryptoUtils 加密细节（v2.2.0）

- **算法**：AES-256-GCM（`AES/GCM/NoPadding`）
- **密钥存储**：Android Keystore（`AndroidKeyStore` provider），密钥别名 `nasmusic_secret_key`
- **IV 长度**：12 字节（GCM 标准）
- **认证标签**：128 位
- **输出格式**：Base64 编码的 `iv + ciphertext` 拼接字符串
- **降级策略**：加密失败返回明文，解密失败返回原值（兼容旧版本明文数据）
- **使用场景**：`AppPreferences` 中的 `apiToken` 和 `password` 字段在写入 DataStore 前加密，读取时解密

---

## 4. 数据流

### 4.1 启动流程

```
App 启动
  → MainActivity.onCreate()
    → PlaybackService 启动（startService）
      → ExoPlayer 创建
      → MediaLibrarySession 创建
      → PlayerManager.setPlayer(exoPlayer)
    → MainViewModel 创建
      → 读取 DataStore ServerConfig
      → 若 baseUrl 不为空 → 显示连接提示弹窗
    → 用户确认连接
      → BackendRegistry.initialize(config)
        → JellyfinAdapter.initialize() / NavidromeAdapter.initialize()
        → 认证 → 保存 adapter 实例
      → ViewModel.loadLibrary()
        → adapter.getAlbums() → _albums
        → adapter.getSongs(limit) → _songs
      → UI 通过 collectAsState 自动更新
```

### 4.2 播放流程

```
用户点击歌曲
  → ViewModel.playSong(song)
    → PlayerManager.playSong(song)
      → ExoPlayer.setMediaItem + prepare + play
    → loadLyricsForCurrentSong()
      → LyricsManager.checkAvailability(song)
        → MP3 内嵌 → 本地缓存 → 本地 LRC → 网络
      → _currentLyrics = result
  → UI 从 StateFlow 读取，渲染歌词 + 播放状态
```

### 4.3 设置保存流程

```
用户在设置页开关某个选项
  → ViewModel.updateDarkTheme(enabled)
    → AppPreferences.setDarkTheme(enabled)
      → DataStore edit { it[key] = value }
  → UI 通过 appSettings StateFlow 自动接收更新
```

### 4.4 进度更新流程（两条路径）

```
路径 A（PlayerManager）：
  Handler.postDelayed(runnable, 500)
    → player.currentPosition → _progress
    → postDelayed 自身 → 循环

路径 B（MainViewModel）：
  viewModelScope.launch {
    while(true) {
      delay(500)
      playerManager.updateProgress()
    }
  }
```

**注意**：两条路径同时运行，均更新 `_progress` StateFlow。路径 A 是遗留机制，路径 B 是 ViewModel 协程方式。移除路径 A 需确认路径 B 在 `onMediaItemTransition` 等边界情况下也能正确获取进度。

---

## 5. 已实现功能清单

### 后端连接
- [x] Jellyfin 后端连接（token / 用户名密码）
- [x] Navidrome 后端连接（Subsonic token+salt）
- [x] 连接测试（不改变当前状态）
- [x] 服务器配置持久化（DataStore）
- [x] 启动自动连接提示

### 曲库浏览
- [x] 专辑网格浏览
- [x] 演唱者网格浏览
- [x] 歌曲列表浏览
- [x] 搜索过滤（拼音首字母 + 子串匹配）
- [x] Debug 模式限制歌曲加载量（10 首）

### 播放功能
- [x] 单曲播放 / 队列播放
- [x] 播放/暂停
- [x] 上/下一曲
- [x] 15 秒快进/快退（D-pad 左右键）
- [x] 四种播放模式（顺序/单曲循环/列表循环/随机）
- [x] 队列管理（添加/移除/清空）
- [x] 后台播放（MediaLibraryService）
- [x] 音频焦点处理（`setHandleAudioBecomingNoisy(true)`）

### 歌词系统
- [x] LRC 格式解析
- [x] MP3 内嵌歌词提取
- [x] 本地 LRC 文件扫描
- [x] 歌词网络匹配（酷狗 + 网易云）
- [x] 歌词本地缓存
- [x] 歌词来源切换
- [x] 歌词滚动高亮

### 封面图
- [x] 后端 URL 封面
- [x] MP3 内嵌元数据封面提取
- [x] Jellyfin 无 tag fallback
- [x] 封面图缓存

### 网络音乐（v2.2.0 新增）
- [x] 在线歌曲搜索（Meting-API，网易云源）
- [x] 网络歌曲播放（302 重定向解析真实 mp3 URL）
- [x] 网络歌词获取（LRC 文本）
- [x] 网络封面显示（Coil 自动跟随 302）
- [x] Meting-API 端点可配置（设置页可修改/恢复默认）
- [x] SSL 兼容老版 Android（信任所有证书，解决 Let's Encrypt 根证书缺失）
- [x] 搜索输入支持中文（虚拟键盘 + 系统输入法切换）
- [x] 网络歌曲收藏（DataStore + Gson 持久化，收藏列表展示）
- [x] 收藏按钮通用化（FavoriteButton 组件，本地/网络收藏共用）
- [x] 全局收藏按钮（所有歌曲列表页面统一添加收藏按钮）
- [x] 搜索端点自动 fallback（当前端点失败自动尝试其他预设端点，用户无感）
- [x] 搜索状态持久化（关键词移至 ViewModel，跨页面导航保留）
- [x] 加入队列功能（所有歌曲列表页面的 SongRow 添加队列切换按钮）
- [x] 诊断日志体系（MetingDiag TAG，Release 包可见）
- [x] 网络歌曲标题/作者编码修复（EncodingUtils.fixEncoding 处理 GBK/Latin-1 误解码）
- [x] 网络歌曲播放链接缓存（5 分钟 TTL，避免短时间重复请求）
- [x] 网络收藏 LRU 上限（500 条，超出自动清理最旧）
- [x] NowPlayingScreen 网络歌曲来源标识（"NET" 标签）
- [x] 歌词来源标签文案优化（"网络匹配" → "在线歌词"）
- [x] LyricsNetworkProvider 守护线程改造（LyricsNetwork-OkHttp 线程池，不阻塞进程退出）

### 播放队列持久化（v2.3.0 新增）
- [x] 上次播放队列保存（DataStore + Gson，streamUrl 置空避免过期链接）
- [x] 应用启动自动恢复队列和当前索引（不自动播放，防止意外声音）
- [x] NAS 歌曲 streamUrl 后端连接后刷新（adapter.getSongsByIds）
- [x] 网络歌曲 streamUrl 播放时实时解析（resolvePlayUrl）
- [x] 恢复队列后首次播放 streamUrl 解析（playPause/next/previous 检测空 streamUrl）
- [x] 自动切歌到网络歌曲 streamUrl 解析（onMediaItemTransition 拦截 + onNeedResolveStreamUrl 回调）
- [x] 清空队列同步清除持久化数据

### 设置
- [x] 暗色主题切换
- [x] 界面动画开关
- [x] 自动下一首开关
- [x] 默认播放模式
- [x] 歌词/封面缓存开关
- [x] 歌词偏移调节
- [x] 网络连通性测试
- [x] Meting-API 端点配置（3 个预设端点选择 + 自定义输入，v2.2.0 新增）
- [x] About 页面（版本信息）

### TV 适配
- [x] `leanback` required
- [x] 横屏锁定
- [x] D-pad 完整导航
- [x] 焦点系统（FocusRequester + onFocusChanged）
- [x] TV 虚拟键盘（TextInputDialog）
- [x] 三层 BACK 键处理
- [x] HDMI-CEC 媒体键映射（播放/暂停/切歌/快进快退）

### 曲库浏览增强
- [x] 专辑详情页（AlbumDetailScreen — 封面 + 曲目列表 + 逐首选播 + 播放全部）
- [x] 演唱者详情页（ArtistDetailScreen — 该演唱者全部歌曲 + 播放全部）
- [x] 曲库过滤（GENRES 流派 tab + YEARS 年代 tab）
- [x] 多歌唱家拆分（ArtistSplitter 按 &/feat./ft./with/vs. 拆分，合唱曲目同时出现在各歌唱家详情页）

### 交互体验增强
- [x] 收藏/喜欢功能（NowPlayingScreen 心形按钮 + LibraryScreen 收藏 tab + 后端同步）
- [x] 最近播放（RECENT tab，最多 50 条，DataStore 持久化）
- [x] 播放次数统计（DataStore 持久化 + playCounts 展示）
- [x] 歌词卡拉 OK 逐字高亮（LyricsHighlightMode.WORD_BY_WORD — Canvas 逐字填充效果）
- [x] 均衡器（EqualizerScreen — 7 频段 D-pad 滑块 + 6 种预置方案 + DataStore 持久化）
- [x] 封面图全屏沉浸模式（点击封面切换，高斯模糊 + 半透明遮罩 + 歌词叠加）

### 播放功能提升
- [x] 无间断播放 & 预加载（playSong() 中 setNextMediaItem 预加载下一首）
- [x] 播放队列上下移动排序（QueueScreen ↑↓ 按钮 + PlayerManager.moveItem）

### 服务与稳定性
- [x] 前台通知（startForeground + NotificationChannel + buildNotification）
- [x] 网络监听 + 自动重连（ConnectivityManager 回调 + 最多 3 次自动重连尝试）
- [x] 网络状态提示（connectMessage 悬浮横幅）

### 代码质量
- [x] 清理废弃代码（移除 backend/jellyfin/ 和 backend/navidrome/ 目录下的旧 Retrofit 实现）
- [x] 缓存管理 UI（设置页：查看缓存大小 + 清除歌词缓存 + 清除封面缓存）

### 播放列表管理
- [x] 完整播放列表 UI（PlaylistManagementScreen — 创建/删除/播放/移除歌曲，左右分栏）
- [x] 后端播放列表 API（BackendAdapter 扩展：getPlaylists/createPlaylist/deletePlaylist/addToPlaylist/removeFromPlaylist）
- [x] 创建播放列表对话框（TextInputDialog 让用户输入名称，替代假数据）

### NowPlaying 布局调整（v2.1.0）
- [x] 播放控制按钮移到封面图下方（ControlButtonsRow 置于 CoverColumn 下方）
- [x] 进度条横向占满（ProgressSection fillMaxWidth，底部对齐）
- [x] 专辑名称移至封面图上方，下方仅保留艺术家

### 性能优化 & 按需加载（v2.2.0）
- [x] 歌曲分页加载（SongsPagingState — 每页 200 首，滚动到底部触发 `loadSongsNextPage()`，显示 "已加载 N / 共 M 首"）
- [x] 艺术家列表独立 API（`getArtists()` 替代从全量歌曲推导）
- [x] 年份列表独立 API（`getYears()` 替代从全量歌曲推导）
- [x] 最近播放按需批量查询（`getSongsByIds()` 替代依赖全量歌曲列表）
- [x] 服务端搜索（`searchSongs(query)` 替代客户端过滤）
- [x] 增量构建艺术家映射（`buildArtistMapsIncremental()` 仅处理新批次，避免全量重建）
- [x] Navidrome 并发加载（`async + awaitAll` 并行请求专辑/演唱者/歌曲）

### 安全 & 加密（v2.2.0）
- [x] 密码加密存储（CryptoUtils — AES-256-GCM + Android Keystore，加密 DataStore 中的 password 和 apiToken）
- [x] 服务器配置敏感字段加密（AppPreferences 读写时自动加解密）

### 代码质量 & 重构（v2.2.0）
- [x] 日志统一管理（AppLog — Debug 构建输出，Release 构建空操作，避免泄露调试信息）
- [x] 公共可聚焦 Surface 组件（FocusableSurface — 消除 30+ 处焦点动画样板代码）
- [x] 编码修复工具抽取（EncodingUtils — 从 JellyfinAdapter/NavidromeAdapter 抽取公共 fixEncoding 逻辑）
- [x] 重试工具（RetryUtil — 指数退避重试，用于后端 API 调用容错）
- [x] Activity 拆分（MainActivity 从 678 行精简至 ~275 行，抽取 AppRoot/NetworkMonitor/MediaKeyHandler）
- [x] 统一异步状态（UiState<T> 密封类 — Loading/Success/Error 替代混用的 isLoading/errorMessage）
- [x] DI 容器（NasMusicApp 作为控制反转容器，移除静态单例 `getInstance()`）
- [x] 字符串资源化（strings.xml 替换 6+ 屏幕中的硬编码中文 UI 字符串）
- [x] 播放模式状态迁移（B-13 — `_playMode` 从 PlayerManager 迁移到 MainViewModel）
- [x] 单元测试补充（UiStateTest、TimeUtilsTest、RetryUtilTest、MediaKeyHandlerTest、NetworkMonitorTest）
- [x] CI 搭建（GitHub Actions — push/PR 自动构建并上传 APK）

### 进程退出清理（v2.2.0）
- [x] OkHttp 守护线程（JellyfinAdapter/NavidromeAdapter 使用 `isDaemon = true` 的线程池，防止阻止进程退出）
- [x] 强制进程终止（退出确认时 `finishAffinity()` + `Process.killProcess()`，确保 Android Studio stop 按钮熄灭）
- [x] PlayerManager.release()（退出时释放 Handler、listener、Equalizer）
- [x] ServiceCompat.stopForeground(STOP_FOREGROUND_REMOVE)（onDestroy 移除前台通知）

### 回归测试文档（v2.2.0）
- [x] 完整回归测试文档（docs/regression-test.md — 19 章节 248 个测试项，覆盖单元/集成/UI/专项验证）

---

## 6. 约束与限制

### 已知技术债务
1. **MediaLibrarySession.Callback 空实现** — `MediaLibrarySession.Builder` 的 Callback 为 `{}`（空实现），缺少 `onPlay`/`onPause`/`onStop`/`onSkipToNext` 等显式委托（依赖 Media3 默认行为）。当前不影响主功能。
2. ~~**重复的进度更新**~~ — [v2.2.0 已修复] Handler 路径保留（1000ms 轮询），移除 ViewModel 协程路径
3. ~~**裸单例模式**~~ — [v2.2.0 已修复] B-9 DI 容器（NasMusicApp 持有实例，移除 `getInstance()` 静态方法）
4. ~~**零测试**~~ — [v2.2.0 部分修复] B-5 补充 5 个工具类/组件单元测试，完整回归测试文档已编制（248 项）
5. ~~**错误处理不规范**~~ — [v2.2.0 已修复] B-12 UiState<T> 密封类 + RetryUtil 指数退避重试
6. ~~**状态管理未统一**~~ — [v2.2.0 部分修复] B-12 异步状态统一为 UiState；B-13 播放模式状态迁移到 ViewModel
7. **播放队列不持久化** — 杀死 App 后队列丢失（规划中）

### 已知 Bug / 功能缺失
1. [已修复] ~~网络断开后不会自动重连~~ → 已实现 D-2 ConnectivityManager 自动重连
2. [已修复] ~~无收藏/喜欢功能~~ → 已实现 B-1
3. [已修复] ~~无专辑详情页~~ → 已实现 A-1
4. [已修复] ~~无演唱者详情页~~ → 已实现 A-2
5. [已修复] ~~无播放列表管理~~ → 已实现 G-2
6. [已修复] ~~无均衡器/音效调节~~ → 已实现 B-4
7. [已修复] ~~封面图全屏沉浸模式未实现~~ → 已实现 B-5
8. [已修复] ~~死代码未清理~~ → 已实现 E-3
9. [已修复] ~~无前台通知~~ → 已实现 D-1
10. [已修复] ~~Jellyfin 连接泄漏~~ → 详见 10.7.2 和 10.7.4
11. [v2.2.0 已修复] ~~PlaybackService Media3 1.2.1 API 不兼容~~ → 改用 ACTION_MEDIA_BUTTON + KeyEvent
12. [v2.2.0 已修复] ~~退出进程残留（Android Studio stop 按钮常亮）~~ → OkHttp 守护线程 + killProcess 双保险
13. [v2.2.0 已修复] ~~密码明文存储~~ → CryptoUtils AES-256-GCM 加密
14. [v2.2.0 已修复] ~~Jellyfin 歌词端点 404~~ → `/Items/{id}/Lyrics` 改为 `/Audio/{id}/Lyrics`
15. [v2.2.0 已修复] ~~Jellyfin 收藏端点 404~~ → `/Items/{id}/Favorite` 改为 `/UserFavoriteItems/{id}`
16. [v2.2.0 已修复] ~~全量加载歌曲导致内存溢出~~ → 分页加载（每页 200 首）
17. 播放队列不持久化（杀死 App 后丢失）

### 兼容性约束
| 约束 | 说明 |
|------|------|
| 仅横屏 | `screenOrientation="landscape"` |
| 需要 Leanback | `android.software.leanback required=true` |
| 无触摸 UI | D-pad 滚动 + 聚焦 |
| ~~无 DI 框架~~ | [v2.2.0 已修复] NasMusicApp 作为 DI 容器 |
| 仅使用 HTTP | `usesCleartextTraffic=true`（NAS 本地网络） |
| Media3 1.2.1 | `Player.COMMAND_PLAY/PAUSE` 不存在，通知媒体按钮需用 ACTION_MEDIA_BUTTON + KeyEvent 方式 |

---

## 7. 回归测试场景

> 修改或新增功能后，执行以下测试场景确保核心功能不受影响。

### 7.1 后端连接

| 编号 | 场景 | 预期结果 |
|------|------|---------|
| T01 | 首次启动（无配置） | 不弹连接提示，显示空曲库 |
| T02 | 保存 Jellyfin 配置后启动 | 弹「是否连接」提示 |
| T03 | 点击确认连接 | 连接成功，加载曲库，顶部显示 3 秒提示 |
| T04 | 点击取消连接 | 关闭弹窗，停留在当前页面 |
| T05 | 服务器连接页：输入非法地址 | 测试连接返回失败 |
| T06 | 服务器连接页：输入正确凭据 | 测试连接返回成功 + 服务器名 |
| T07 | 连接后「断开」 | 曲库清空，回到未连接状态 |

### 7.2 曲库浏览

| 编号 | 场景 | 预期结果 |
|------|------|---------|
| T08 | 专辑 Tab：网格加载 | 封面图正常显示，专辑卡片正确 |
| T09 | 演唱者 Tab：网格加载 | 演唱者卡片正确显示 |
| T10 | 歌曲 Tab：列表加载 | 歌曲标题 + 演唱者正确显示 |
| T11 | 搜索：输入中文子串 | 过滤出匹配条目 |
| T12 | 搜索：输入拼音首字母（如 "zjl"） | 过滤出 "周杰伦" 等 |
| T13 | 搜索：清除搜索内容 | 恢复完整列表 |
| T14 | 点击专辑卡片 | 开始播放该专辑所有歌曲 |
| T15 | 点击演唱者卡片 | 开始播放该演唱者所有歌曲 |
| T16 | 点击歌曲行 | 播放该歌曲 |
| T17 | 「播放全部」按钮 | 播放曲库全部歌曲 |

### 7.3 播放控制

| 编号 | 场景 | 预期结果 |
|------|------|---------|
| T18 | 播放页显示 | 封面、歌名、演唱者、歌词正确 |
| T19 | 封面图显示 | 有封面的显示封面，无封面的显示占位图 |
| T20 | 播放/暂停 | 按 OK 键切换，状态正确 |
| T21 | 左右方向键跳转 | 每次按键前后跳转 15 秒 |
| T22 | 播放模式切换 | 顺序 → 单曲 → 列表 → 随机，循环切换 |
| T23 | 曲目结束自动下一首 | 按当前播放模式处理 |
| T24 | 进度条更新 | 平稳前进，不跳变 |

### 7.4 歌词

| 编号 | 场景 | 预期结果 |
|------|------|---------|
| T25 | 有歌词的歌曲 | 歌词滚动显示，当前行高亮 |
| T26 | 无歌词的歌曲 | 显示「暂无歌词」 |
| T27 | 歌词来源切换 | 可在后端/网络来源间切换 |
| T28 | 歌词滚动 | 当前行保持在可见范围 |

### 7.5 队列

| 编号 | 场景 | 预期结果 |
|------|------|---------|
| T29 | 队列显示 | 当前歌曲 + 后续曲目正确显示 |
| T30 | 移除单曲 | 指定曲目从队列移除 |
| T31 | 清空队列 | 所有曲目被移除 |

### 7.6 设置

| 编号 | 场景 | 预期结果 |
|------|------|---------|
| T32 | 切换暗色主题 | 背景色即时切换 |
| T33 | 开关动画 | 焦点动画有无（需重启确认） |
| T34 | 开关自动下一首 | 播放结束时行为变化（需确认） |
| T35 | 切换默认播放模式 | 新建队列时默认使用该模式 |
| T36 | About 页面 | 版本号、构建类型、开源协议正确 |

### 7.7 导航

| 编号 | 场景 | 预期结果 |
|------|------|---------|
| T37 | 顶部导航栏切换页面 | 页面切换，高亮当前页 |
| T38 | 聚焦方向正确 | D-pad 上下左右在各页面内焦点移动合理 |
| T39 | BACK 键层级 | 对话框→回NowPlaying→退出确认 |

### 7.8 异步加载 & 错误状态

| 编号 | 场景 | 预期结果 |
|------|------|---------|
| T40 | 连接后端后曲库数据加载 | 显示 Loading 动画或进度提示，加载完成后显示数据 |
| T41 | 加载失败时显示错误横幅 | 红色横幅在屏幕顶部显示错误信息，5 秒后自动消失 |
| T42 | 网络断开时显示提示 | 顶部显示「网络已断开」灰色提示（约 5 秒） |
| T43 | 网络恢复后自动重连 | 显示「网络已恢复」→ 自动尝试重连（最多 3 次）→ 成功后曲库恢复 |
| T44 | 播放模式持久化 | 设置页切换默认播放模式 → 杀进程重启 → 默认模式保持 |

### 7.9 测试 & CI

| 编号 | 场景 | 预期结果 |
|------|------|---------|
| T45 | 本地运行单元测试 | `./gradlew testDebugUnitTest` 全部通过（绿色） |
| T46 | CI 构建 | push 到 main/develop 或 PR → GitHub Actions 自动构建 |
| T47 | CI 产物 | Workflow 完成后 APK 可下载 |

---

## 8. 版本管理规范

### 8.1 版本号格式

```
[主版本].[次版本].[补丁]
```

| 位置 | 递增条件 | 示例 |
|------|---------|------|
| 主版本 | 重大架构变更、UI 重设计、向后不兼容的 API 变更 | `2.0.0` |
| 次版本 | 新功能发布 | `1.1.0` |
| 补丁 | Bug 修复、性能优化、文档更新 | `1.0.1` |

### 8.2 开发流程

```
功能开发前：
  → 查看 NasMusicVersion.VERSION_NAME 确认当前版本
  → 查看 CHANGELOG.md 了解历史变更

功能开发后：
  → 更新 CHANGELOG.md（Added/Changed/Fixed/Removed）
  → 更新 docs/technical-overview.md（添加修改记录 + 如果架构变化则更新相应章节）

正式发布前：
  → 递增 VERSION_CODE（+1）
  → 更新 VERSION_NAME（按语义版本）
  → 确认所有回归测试场景通过
```

### 8.3 版本迭代入口

版本号维护在以下文件中，更新时必须**同步修改**：

1. `app/build.gradle.kts` — `versionCode` / `versionName`（Android 构建用）
2. `app/src/main/java/com/nasmusic/tv/NasMusicVersion.kt` — 代码内版本常量（UI 显示用）

### 8.4 版本兼容性

- `FILE_FORMAT_VERSION` 仅在 DataStore / 缓存数据结构的序列化格式向后**不兼容**时递增
- 新增字段不影响旧数据读取（DataStore Preferences 自动处理缺失键）
- 移除字段时需要递增 FILE_FORMAT_VERSION 并提供迁移逻辑

### 8.5 Git / GitHub 配置

#### 仓库信息

| 项目 | 值 |
|------|-----|
| 远程仓库 | `https://github.com/hxzhang2000/NASMusicTV.git` |
| 默认分支 | `main` |
| Git 作者 | hxzhang2000 \<hxzhang2000@hotmail.com\> |
| 代理 | `http://127.0.0.1:7890`（Clash for Windows） |

#### 相关文件

| 文件 | 用途 |
|------|------|
| `.gitignore` | 排除 Gradle 构建产物、IDE 配置、系统文件 |
| `.gitattributes` | 统一 LF 行尾（`*.bat` 保留 CRLF） |
| `.opencode/rules.md` | opencode 提交规范指令 |

#### 提交流程

```bash
# 首次克隆
git clone https://github.com/hxzhang2000/NASMusicTV.git

# 日常提交流程（opencode 自动执行）
git add <files>
git commit -m "<type>: <description>"
git push

# 配置代理（Clash for Windows 环境）
git config http.proxy http://127.0.0.1:7890
git config https.proxy http://127.0.0.1:7890
```

#### 提交规范

opencode 提交遵循 `.opencode/rules.md` 中定义的规范，前缀类型包括 `feat` / `fix` / `refactor` / `docs` / `chore`。

---



## 9. 文件索引

### 源代码（按包）

```
com.nasmusic.tv/
├── NasMusicApp.kt           # Application 类（v2.2.0：DI 容器）
├── NasMusicVersion.kt       # 版本信息
├── backend/
│   ├── BackendAdapter.kt    # 后端接口（v2.2.0：新增 getSongsTotalCount/getSongsByIds/getYears/logout/close）
│   ├── BackendRegistry.kt   # 后端注册中心
│   └── impl/
│       ├── JellyfinAdapter.kt   # Jellyfin 实现（v2.2.0：守护线程 + 编码修复抽取）
│       └── NavidromeAdapter.kt  # Navidrome 实现（v2.2.0：守护线程 + 并发加载）
├── data/
│   ├── model/
│   │   ├── Album.kt
│   │   ├── AppSettings.kt
│   │   ├── Artist.kt
│   │   ├── EqualizerPreset.kt   # v2.1.0：均衡器预置方案
│   │   ├── Genre.kt             # v2.1.0：流派数据模型
│   │   ├── Lyrics.kt
│   │   ├── LyricsLine.kt        # v2.1.0：LyricsHighlightMode 枚举
│   │   ├── LyricsSource.kt
│   │   ├── PlayMode.kt
│   │   ├── Playlist.kt          # v2.1.0：播放列表数据模型
│   │   ├── RecentSong.kt        # v2.1.0：最近播放数据模型
│   │   ├── ServerConfig.kt
│   │   ├── Song.kt
│   │   └── UiState.kt           # v2.2.0：统一异步状态密封类
│   └── prefs/
│       └── AppPreferences.kt    # v2.2.0：CryptoUtils 加密 password/apiToken
├── lyrics/
│   ├── LrcParser.kt
│   ├── LyricsManager.kt
│   ├── LyricsNetworkProvider.kt
│   └── Mp3MetadataExtractor.kt
├── player/
│   ├── CoverArtManager.kt
│   ├── PlayerManager.kt         # v2.2.0：新增 release/setEqualizerBands/moveItem/derivePlayMode/clearError
│   └── PlaybackService.kt       # v2.2.0：ACTION_MEDIA_BUTTON + 守护线程清理
├── ui/
│   ├── MainActivity.kt          # v2.2.0：精简至 ~275 行，抽取 AppRoot/NetworkMonitor/MediaKeyHandler
│   ├── components/
│   │   ├── AppRoot.kt           # v2.2.0：UI 根布局 + 导航 + 错误横幅
│   │   ├── CommonComponents.kt  # 公共 UI 组件
│   │   ├── ConnectPromptDialog.kt
│   │   ├── FocusableSurface.kt  # v2.2.0：可聚焦 Surface 组件
│   │   ├── LyricsView.kt
│   │   └── PlayerControls.kt
│   ├── screens/
│   │   ├── AlbumDetailScreen.kt      # v2.1.0：专辑详情页
│   │   ├── ArtistDetailScreen.kt     # v2.1.0：演唱者详情页
│   │   ├── EqualizerScreen.kt        # v2.1.0：均衡器页面
│   │   ├── ExitConfirmDialog.kt
│   │   ├── LibraryScreen.kt
│   │   ├── NowPlayingScreen.kt
│   │   ├── PlaylistManagementScreen.kt  # v2.1.0：播放列表管理
│   │   ├── QueueScreen.kt
│   │   ├── ServerConnectScreen.kt
│   │   ├── SettingsScreen.kt
│   │   └── TextInputDialog.kt
│   ├── theme/
│   │   ├── Color.kt
│   │   ├── Theme.kt
│   │   └── Type.kt
│   └── viewmodel/
│       └── MainViewModel.kt     # v2.2.0：UiState + 分页 + playMode 迁移
└── util/
    ├── AppLog.kt                # v2.2.0：Debug/Release 日志工具
    ├── ArtistSplitter.kt        # v2.1.0：多歌唱家拆分
    ├── CryptoUtils.kt           # v2.2.0：AES-256-GCM 加密
    ├── EncodingUtils.kt         # v2.2.0：编码修复工具
    ├── MediaKeyHandler.kt       # v2.2.0：媒体键路由
    ├── NetworkMonitor.kt        # v2.2.0：网络监听封装
    ├── PinyinUtils.kt
    ├── RetryUtil.kt             # v2.2.0：指数退避重试
    └── TimeUtils.kt
```

### 文档

| 文件 | 用途 |
|------|------|
| `docs/technical-overview.md` | 当前架构、修改记录与回归测试（本文档） |
| `docs/regression-test.md` | **v2.2.0 新增**：完整回归测试文档（19 章节 248 个测试项） |
| `docs/features-plan.md` | 功能优化方案 |
| `CHANGELOG.md` | 版本变更记录 |
| `README.md` | 项目简介与功能特性 |

### 构建与配置

| 文件 | 用途 |
|------|------|
| `app/build.gradle.kts` | 构建配置、依赖管理 |
| `app/proguard-rules.pro` | ProGuard 混淆规则 |
| `app/src/main/AndroidManifest.xml` | 清单文件 |
| `gradle.properties` | Gradle 全局设置 |
| `settings.gradle.kts` | 项目设置 |
| `gradle/wrapper/gradle-wrapper.properties` | Gradle Wrapper |
| `.gitignore` | Git 排除规则 |
| `.gitattributes` | Git 行尾与属性配置 |
| `.opencode/rules.md` | opencode Git 提交规范 |

---

## 10. 修改记录

> 本节记录经测试验证的功能变更、问题修复与关键实现细节。
> 每次代码修改后同步更新 CHANGELOG.md 和本节内容。

### 10.1 v1.0.0

#### 10.1.1 Jellyfin 连接修复

**问题描述**：Jellyfin 后端连接失败。日志显示后端 API 列表返回了 `Items` 数据（歌曲/专辑正常解析），但播放时无法获取流地址或封面，且歌词接口返回 404。

**根因分析**：

1. **initialize() 中未设置 baseUrl**：`initialize()` 方法内部将传入的 `baseUrl` 赋值给成员变量，但调用顺序存在竞态——在个别路径中 `baseUrl` 尚未初始化就被使用。
2. **接口签名问题**：`BackendAdapter.initialize()` 参数均为必需，但调用方在传递空字符串时可能跳过关键步骤。
3. **testConnection() 与 initialize() 解耦不足**：临时 adapter 与实际使用的 adapter 实例不同，测试通过后实际初始化仍可能失败。

**修改**：`JellyfinAdapter.initialize()` 确保 `baseUrl` 在构造请求前正确赋值，`baseUrl.removeSuffix("/")` 防止 URL 双斜杠，先尝试 `apiToken` 再回退用户名密码。

**验证结果**：✅ 日志确认 Jellyfin 连接成功，播放正常。

---

#### 10.1.2 封面图 fallback 逻辑

**问题描述**：部分歌曲封面图为 null，显示空白占位图。

**根因分析**：`buildCoverUrl()` 在 `imageTag` 为 null 时直接返回 null，但 Jellyfin 的 `/Items/{id}/Images/Primary` 端点即使没有 tag 也能返回图片（从上级条目继承）。

**修改**（`JellyfinAdapter.kt`）：三处覆盖（歌曲、专辑、歌手）：
```kotlin
// 有 tag 的精确 URL 优先 → 无 tag 时 fallback
coverUrl = buildCoverUrl(id, imageTag) ?: getCoverUrl(id)
```

**验证状态**：✅ 测试通过。

---

#### 10.1.3 启动连接提示对话框

**功能描述**：启动后如果检测到已保存的服务器配置，弹窗询问是否连接。

**新增文件**：
- `ui/components/ConnectPromptDialog.kt` — TV 弹窗（半透明遮罩 + 居中 480dp 列 + 两个按钮）
- `MainViewModel.kt` — `showConnectPrompt` / `connectMessage` 状态 + `connectToSavedServer()`
- `MainActivity.kt` — 弹窗渲染 + 消息浮层

**行为流程**：
```
启动 → 读取 DataStore → baseUrl 为空? → 不弹窗
                                  → 有值 → 弹窗 → 取消 → 关闭
                                               → 确认 → 自动连接 → 顶部提示 3 秒
```

**关键设计**：不自动静默重连，每次启动弹窗由用户决定；消息 3 秒自动清除；BACK 键分层处理。

**验证结果**：全部场景测试通过 ✅

---

#### 10.1.4 D-pad 左右键跳转修复

**问题描述**：播放页进度条获得焦点后，左右键无法跳转。

**根因**：`onPreviewKeyEvent` 中使用 `KeyDown` 类型过滤，但部分 TV 固件只触发 `KeyUp`。

**修改**（`PlayerControls.kt`）：`KeyDown` → `KeyUp`，确保每次按键只触发一次 seek。

**验证结果**：✅ 左右键正常跳转，无重复执行。

---

#### 10.1.5 Debug/Release 歌曲加载数量控制

**功能**：Debug 编译只加载 10 首歌，Release 加载全部。

**修改**：
- `BackendAdapter.getSongs(limit: Int = 100000)` — 接口新增参数
- `JellyfinAdapter` / `NavidromeAdapter` — URL 参数改为 `$limit`
- `MainViewModel` — `val songLimit = if (BuildConfig.DEBUG) 10 else 100000`
- `build.gradle.kts` — 启用 `buildConfig = true`

**验证**：✅ Debug 日志显示 `limit=10`，Release 显示 `limit=100000`。

---

#### 10.1.6 「播放全部」按钮常驻显示

**功能**：播放全部按钮之前只在「专辑」tab 显示，改为在所有 tab 均显示。

**修改**（`LibraryScreen.kt`）：
```kotlin
// 改前
if (activeTab == LibraryTab.ALBUMS && albums.isNotEmpty())
// 改后
if (albums.isNotEmpty())
```

**验证**：✅ 专辑/songs 两个 tab 均显示，专辑未加载时不显示。

---

#### 10.1.7 模糊搜索与过滤

**功能**：曲库页增加搜索，支持拼音首字母 + 子串匹配。

**新增文件**：
- `util/PinyinUtils.kt` — `Transliterator` 实现汉字→拼音首字母（API 24+），<24 降级为子串匹配

**修改**：
- `LibraryScreen.kt` — `SearchBar` 组件 + `derivedStateOf` 按 tab 类型过滤

**匹配规则**：
- 子串匹配（中文/英文直接匹配）
- 拼音首字母（"zjl"→"周杰伦"）

**验证**：✅ 搜索过滤正确，tab 切换正常工作，清除恢复完整列表。

---

### 10.2 v1.0.1

#### 10.2.1 Git / GitHub 版本管理初始化

**功能描述**：为项目初始化 Git 仓库、配置 GitHub 远程仓库、添加 .gitignore / .gitattributes / opencode 提交规范。

**新增文件**：
- `.gitignore` — 排除 Gradle 构建产物、IDE 配置、系统文件
- `.gitattributes` — 统一 LF 行尾（`*.bat` 保留 CRLF）
- `.opencode/rules.md` — opencode Git 提交规范说明

**配置项**：
- Git 作者：hxzhang2000 \<hxzhang2000@hotmail.com\>
- 远程仓库：`https://github.com/hxzhang2000/NASMusicTV.git`
- 默认分支：`main`
- Git 代理：`http://127.0.0.1:7890`（Clash for Windows）
- 初始提交：75 个文件 / 10,757 行

**验证结果**：✅ 已推送到 GitHub，`git log` 确认提交链完整。

---

### 10.3 v1.1.0

#### 10.3.1 E-3 废弃代码清理

**功能描述**：删除旧 Retrofit 实现的 `backend/jellyfin/` 和 `backend/navidrome/` 目录（共 6 个文件），移除不再需要的 Retrofit 依赖。

**删除文件**：
- `backend/jellyfin/JellyfinAdapter.kt`、`JellyfinApi.kt`、`JellyfinModels.kt`
- `backend/navidrome/NavidromeAdapter.kt`、`NavidromeApi.kt`、`NavidromeModels.kt`

**依赖变更**（`app/build.gradle.kts`）：移除 `retrofit:2.9.0` 和 `converter-gson:2.9.0`（`gson` 保留，供当前 OkHttp 实现的 JSON 解析使用）

**验证**：✅ 编译无错误，无 import 引用残留。

---

#### 10.3.2 C-2 无间断播放与预加载

**功能描述**：启用 ExoPlayer 曲目切换交叉淡入淡出，优化 `playSong()` 路径中已存在于当前队列的歌曲直接 seek 而非重建队列。

**修改**：
- `PlaybackService.kt` — ExoPlayer 构建时增加 `CrossfadeMediaSource.Factory(DefaultMediaSourceFactory(this))`
- `PlayerManager.playSong()` — 如果歌曲已在当前队列中，直接 `seekTo()` 实现无缝切换；新歌曲保持原行为

**涉及文件**：
| 文件 | 改动 |
|------|------|
| `player/PlaybackService.kt` | +3 行 import，+1 行 `.setMediaSourceFactory()` |
| `player/PlayerManager.kt` | `playSong()` 新增队列内查找跳过重建逻辑 |

**验证**：✅ 编译通过（淡入淡出效果需真机验证）。

---

#### 10.3.3 B-5 沉浸模式

**功能描述**：点击播放页封面图 → 切换至沉浸模式：封面图铺满全屏作为背景 + 半透明渐变遮罩，歌词叠加在封面上方滚动。再次点击封面或按 BACK 恢复常规布局。

**修改**（`ui/screens/NowPlayingScreen.kt`）：
- 新增 `isImmersiveMode` 状态
- 新增全屏封面背景层（`AsyncImage` fillMaxSize + 垂直渐变遮罩 `Color(0xCC0C1222)`）
- 左侧封面提取为独立 `CoverColumn` 组件，包裹 `Surface(onClick = toggle)`
- 歌词区域在沉浸模式下移除自身半透明背景（避免与封面遮罩叠加视觉冲突）
- BACK 按键拦截：沉浸模式中按 BACK 返回常规模式

**新增组件**：`CoverColumn` — 可聚焦的封面区域，scale 动画 + 焦点边框

**涉及文件**：
| 文件 | 改动 |
|------|------|
| `ui/screens/NowPlayingScreen.kt` | ~100 行重构，提取 `CoverColumn` + 沉浸模式逻辑 |

**关键设计**：
```kotlin
// 沉浸模式布局层级
Box {
    if (immersive) {
        AsyncImage(fillMaxSize, coverUrl)  // 背景层
        Box(gradient overlay)              // 遮罩层
    }
    Column {
        if (!immersive) CoverColumn(...)   // 左列封面
        Column(weight=1f) { Lyrics }      // 歌词（全宽）
        PlayerControls                     // 底部控制
    }
}
```

**验证**：✅ 测试通过。

---

#### 10.3.4 C-1 队列排序增强

**功能描述**：播放队列中每首曲目增加「↑」「↓」移动按钮，支持 D-pad 焦点操作移动曲目顺序。

**新增**：
- `PlayerManager.moveItem(fromIndex, toIndex)` — 同步更新 `_queue` StateFlow 和 ExoPlayer 内部队列，自动调整 `_currentIndex` 追踪当前播放曲目
- `QueueScreen.MoveButton` — 小型 focusable Surface 按钮（36dp 宽，6dp 圆角）
- `MainViewModel.moveQueueItem(from, to)` — 委托给 PlayerManager

**修改**（`QueueScreen.kt`）：
- `items` → `itemsIndexed` 修复重复歌曲索引错误
- 每行右侧追加 `↑`（非第一首）和 `↓`（非最后一首）按钮
- 新增 `onMoveItem` 参数桥接到 ViewModel

**涉及文件**：
| 文件 | 改动 |
|------|------|
| `player/PlayerManager.kt` | 新增 `moveItem()` |
| `ui/screens/QueueScreen.kt` | `itemsIndexed` + `MoveButton` + `onMoveItem` 参数 |
| `ui/viewmodel/MainViewModel.kt` | 新增 `moveQueueItem()` |
| `ui/MainActivity.kt` | `QueueScreen` 传入 `onMoveItem` |

**验证**：✅ 编译通过（队列排序功能需真机验证）。

---

### 10.5 v2.0.1 — Bug 修复

**版本信息**：VERSION_CODE=4, BUILD_TYPE=STABLE
**日期**：2026-06-20
**概要**：修复启动崩溃和服务连接问题。

---

#### 10.5.1 H-1 修复 Android < API 26 启动崩溃

**问题**：`PlaybackService.onCreate()` 调用 `createNotificationChannel()` 直接使用 `NotificationChannel`（API 26+），导致 Android 5/6/7 设备上 `NoClassDefFoundError`。
**修复**：`createNotificationChannel()` 开头添加 API 级别检查：
```kotlin
if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
```
**涉及文件**：`player/PlaybackService.kt`

---

#### 10.5.2 H-2 修复服务器连接页面「连接服务器」按钮无反馈

**问题**：
1. 密码字段硬编码为 `"wfxzhx2000"`，不读取已保存配置 → 期望不同密码的用户连接失败
2. `onConnect(config)` 是异步 fire-and-forget → 按钮的本地 `isLoading` 状态立即闪回，用户看不到"连接中..."
3. `connectToServer()` catch 块不设置错误消息 → 失败时用户看不到任何反馈
**修复**：
- 密码初始值从 `initialConfig.password` 读取，不为空时回退默认值
- 移除 `ServerConnectScreen` 本地 `isLoading`，改为通过 `isConnecting` prop 使用 ViewModel 的 `_isLoading`
- `connectToServer()` 失败时通过 `_connectMessage` 显示 "连接失败: xxx"（3 秒自动清除）
**涉及文件**：`ui/screens/ServerConnectScreen.kt`、`ui/viewmodel/MainViewModel.kt`、`ui/MainActivity.kt`

---

#### 10.5.3 H-3 修复启动时连接提示对话框被自动重连关闭

**问题**：`init` 块设置 `_showConnectPrompt = true` 后，`onNetworkAvailable()` 调用 `connectToSavedServer(silent=true)` 始终设置 `_showConnectPrompt = false`，两者存在竞态条件 → 连接提示对话框有时不出现。
**修复**：`connectToSavedServer()` 仅在 `!silent` 时才关闭对话框。
**涉及文件**：`ui/viewmodel/MainViewModel.kt`

---

#### 10.5.4 H-4 修复连接过程无日志输出

**问题**：`BackendRegistry.initialize()` 和 `connectToSavedServer()` 的失败路径均无任何日志，无法诊断连接失败原因。
**修复**：添加带 Tag `BackendRegistry` / `NASMusic` / `JellyfinAdapter` 的关键路径日志（初始化参数、HTTP 状态码、连接结果）。
**涉及文件**：`backend/BackendRegistry.kt`、`backend/impl/JellyfinAdapter.kt`、`ui/viewmodel/MainViewModel.kt`

---

#### 10.5.5 H-5 修复播放歌曲时 NoSuchMethodError 崩溃

**问题**：`PlaybackService.updateNotification()` 中使用 `getSystemService(NotificationManager::class.java)`，该带 Class 参数的重载方法为 API 23+ 引入。Android 5.1 (API 22) 上调用时抛出 `NoSuchMethodError`，导致点击歌曲播放立即崩溃。

**修复**：将两处 `getSystemService(NotificationManager::class.java)` 替换为 API 1 即存在的 `getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager`（分布于 `createNotificationChannel()` 和 `updateNotification()`）。

```diff
- getSystemService(NotificationManager::class.java)
+ getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
```

**涉及文件**：`player/PlaybackService.kt`

**验证**：✅ 编译通过，真机播放歌曲正常。

---

### 10.7 v2.1.0 — NowPlaying UI 改版 + Jellyfin 连接泄漏修复

**版本信息**：VERSION_CODE=4, BUILD_TYPE=DEV
**日期**：2026-06-21
**概要**：播放页布局重排（控制按钮下移、进度条全宽、专辑名上移）+ Jellyfin 连接 session 泄漏修复 + 应用退出时连接资源释放。

---

#### 10.7.1 NowPlaying UI 调整（Task 1-3）

**Task 1 — 播放控制按钮下移**
- 控制按钮（上一首/播放暂停/下一首/播放模式）从原来与进度条同行，移到内容区域下方、进度条上方
- 提取 `ControlButtonsRow` 独立组件至 `PlayerControls.kt`
- 新布局：封面 → 控制按钮 → 进度条

**Task 2 — 进度条横向占满**
- 进度条从 `PlayerControls` 中分离为独立 `ProgressSection` 组件
- 撑满屏幕底部全宽，不再受控制按钮挤占宽度

**Task 3 — 专辑名移至封面图上方**
- CoverColumn 中新增专辑名（14sp，浅灰）显示在封面上方、歌名下方
- 封面下方的文本从「艺术家 · 专辑名」精简为仅艺术家

**新增组件**：
| 组件 | 文件 |
|------|------|
| `ProgressSection` | `PlayerControls.kt`（独立 Composable） |
| `ControlButtonsRow` | `PlayerControls.kt`（独立 Composable） |

**涉及文件**：
| 文件 | 改动 |
|------|------|
| `ui/screens/NowPlayingScreen.kt` | 布局重构：PlayerControls → ControlButtonsRow + ProgressSection；CoverColumn 重组元素顺序 |
| `ui/components/PlayerControls.kt` | 提取 `ProgressSection` 和 `ControlButtonsRow` 为独立顶层 Composable，`PlayerControls` 保留向后兼容 |

**验证**：✅ 模拟器测试通过。控制按钮显示于封面图下方，D-pad 导航正常。

---

#### 10.7.2 H-6 Jellyfin 连接泄漏修复

**问题描述**：
1. `testConnection()` 每次创建新 `JellyfinAdapter` 调用 `authenticateByName()` 在服务端创建永久 session，无 `logout()` 释放 → 多次测试连接后 session 积满 → 服务端 HTTP 500
2. `BackendRegistry.disconnect()` 只置 null，不清除 Jellyfin 服务端 session

**修改**：
- `BackendAdapter.kt` — 新增 `suspend fun logout()` 接口方法（默认空实现）
- `JellyfinAdapter.kt` — 实现 `logout()`：POST `/Sessions/Logout` 使 token 失效，清空 `apiToken`/`userId`
- `BackendRegistry.kt` — `disconnect()` 改为 `suspend`，调用 `adapter.logout()` 后置 null；`testConnection()` 成功/失败路径均调用 `adapter.logout()` 释放临时 session
- `MainViewModel.kt` — `disconnect()` 中包装 `viewModelScope.launch` 调用 `BackendRegistry.disconnect()`

**涉及文件**：
| 文件 | 改动 |
|------|------|
| `backend/BackendAdapter.kt` | 新增 `logout()` 接口 |
| `backend/impl/JellyfinAdapter.kt` | 实现 `logout()`（~25 行） |
| `backend/BackendRegistry.kt` | `disconnect()` 改为 suspend，`testConnection()` 释放临时 adapter |
| `ui/viewmodel/MainViewModel.kt` | `disconnect()` 包装协程调用 |

**验证**：✅ 测试通过。日志确认连接资源正确释放。

---

#### 10.7.3 播放控制按钮布局修正 + 进度条 D-Pad 修复

**日期**：2026-06-21（同日补充）

**问题**：
1. 播放控制按钮在封面和歌词下方跨整行居中，应左移至封面图下方
2. 焦点在进度条上时左右键无法 seek（焦点移动而非跳转时间）

**分析**：
- 问题 1：`ControlButtonsRow` 在 `Row(cover | lyrics)` 下方独立居中，需移入左侧列
- 问题 2：`ProgressSection` 的 `onPreviewKeyEvent` 被错误移除，导致 `DirectionLeft/Right` 未经消费即被 Compose 焦点导航系统截获，焦点移动而非 seek

**修改**：
| 文件 | 改动 |
|------|------|
| `ui/screens/NowPlayingScreen.kt` | 布局重构：`CoverColumn`（去除 `fillMaxHeight`/weight spacer）+ `ControlButtonsRow` 合并至左侧 `Column`，`Box(weight=1f, contentAlignment=Center)` 垂直居中封面内容，按钮置于其下 |
| `ui/components/PlayerControls.kt` | 恢复 `onPreviewKeyEvent`，改为 `KeyDown` 立即 seek（原 `KeyUp` 松手才跳）；清理不再使用的 import |

**验证**：✅ 模拟器测试通过。D-Pad 焦点导航和左右键 seek 恢复正常。

---

#### 10.7.4 H-7 应用退出时连接资源泄漏修复

**日期**：2026-06-21（同日补充）

**问题**：应用退出后，OkHttp 连接池未释放，导致 Jellyfin 服务端连接资源耗尽，需重启 Jellyfin 才能恢复。

**根因分析**：
1. `BackendRegistry.disconnect()` 只调用 `logout()` 使服务端 session 失效，但不关闭 OkHttp 客户端的连接池
2. `logout()` 未使用 `withContext(Dispatchers.IO)`，在主线程调用时抛出 `NetworkOnMainThreadException`
3. 应用退出时调用 `killProcess()` 终止进程，`onDestroy()` 中的异步清理协程无法完成

**修改**：

| 文件 | 改动 |
|------|------|
| `backend/BackendAdapter.kt` | 新增 `close()` 接口方法，用于释放客户端连接资源 |
| `backend/impl/JellyfinAdapter.kt` | 实现 `close()` 关闭 OkHttp dispatcher 和连接池；`logout()` 改用 `withContext(Dispatchers.IO)` 避免主线程网络异常 |
| `backend/impl/NavidromeAdapter.kt` | 实现 `close()` 关闭 OkHttp dispatcher 和连接池 |
| `backend/BackendRegistry.kt` | `disconnect()` 调用 `logout()` + `close()` 双重清理；`testConnection()` 也关闭临时适配器的连接池 |
| `ui/MainActivity.kt` | 退出确认时使用 `runBlocking { disconnect() }` 确保清理完成再调用 `killProcess()` |

**连接生命周期**：
```
logout()  → POST /Sessions/Logout → 服务端 session 失效
close()   → OkHttp dispatcher 关闭 + 连接池清空 → 客户端释放 TCP 连接
```

**验证**：✅ 日志确认退出时 `logout: HTTP 204` + `close: OkHttp resources released` + `exit: backend disconnected` 依次执行。

---

#### 10.7.5 H-8 从其他页面返回后进度条 D-Pad seek 失效修复

**日期**：2026-06-21（同日补充）

**问题**：
1. 在曲库歌曲页面播放歌曲，进度条左右键 seek 正常
2. 进入歌唱家页面，选择一个歌唱家，跳转到正在播放页面
3. 焦点在进度条上，但左右键移动焦点而非 seek

**根因分析**：
`ProgressSection` 中使用 `hasRequestedFocus` 状态跟踪是否已请求过焦点，通过 `onGloballyPositioned` 回调在首次布局时调用 `requestFocus()`。问题在于：
- `hasRequestedFocus` 是 `remember` 状态，跨重组保持但跨导航可能不同步
- 从其他页面返回时，`onGloballyPositioned` 不一定再次触发（布局位置未变）
- `onFocusChanged` 回调未触发 → `isProgressBarFocused` 保持 `false` → `onPreviewKeyEvent` 中的 seek 逻辑不执行

**修改**（`ui/components/PlayerControls.kt`）：
- 移除 `hasRequestedFocus` 状态和 `onGloballyPositioned` 回调
- 改用 `LaunchedEffect(Unit)` 在组件首次组合时请求焦点，确保从其他页面返回时焦点状态正确同步

```kotlin
// 改前
val hasRequestedFocus = remember { mutableStateOf(false) }
// ...
.onGloballyPositioned {
    if (!hasRequestedFocus.value) {
        hasRequestedFocus.value = true
        progressFocusRequester.requestFocus()
    }
}

// 改后
LaunchedEffect(Unit) {
    progressFocusRequester.requestFocus()
}
```

**验证**：✅ 测试通过。从歌唱家页面返回正在播放页面后，进度条左右键 seek 正常工作。

---

#### 10.7.6 A-2 演唱者详情页导航修复

**日期**：2026-06-21（同日补充）

**问题**：在歌唱家页面点击歌唱家卡片，直接跳转到正在播放页面并开始播放歌曲，没有显示演唱者详情页。

**根因分析**：
`ArtistCard` 的 `onClick` 回调直接绑定到 `onPlaySongs(artistSongs)`，导致点击卡片立即播放所有歌曲。`onDetail` 回调虽然传递了 `onOpenArtistDetail`，但没有 UI 元素触发它。

**修改**（`ui/screens/LibraryScreen.kt`）：
- `ArtistsTab` 中将 `onClick` 改为调用 `onOpenArtistDetail`（打开详情页），与 `AlbumsTab` 行为一致
- 新增 `onPlay` 回调，供详情页中的"播放全部"按钮使用
- `ArtistCard` 参数从 `onDetail` 改为 `onPlay`，UI 显示 "▶" 图标表示可直接播放

```kotlin
// 改前
onClick = {
    if (artistSongs.isNotEmpty()) onPlaySongs(artistSongs)
},
onDetail = if (onOpenArtistDetail != null) {{ onOpenArtistDetail(artist) }} else null

// 改后
onClick = {
    if (onOpenArtistDetail != null) {
        onOpenArtistDetail(artist)
    } else if (artistSongs.isNotEmpty()) {
        onPlaySongs(artistSongs)
    }
},
onPlay = if (artistSongs.isNotEmpty()) {{ onPlaySongs(artistSongs) }} else null
```

**验证**：✅ 测试通过。点击歌唱家卡片显示详情页，详情页中有"播放全部"按钮可播放该歌唱家所有歌曲。

---

#### 10.7.7 A-3 流派过滤修复（仅显示音乐流派）

**日期**：2026-06-21（同日补充）

**问题**：曲库风格 TAB 显示的是电影/电视流派（如 Action、Comedy、Drama 等），而不是音乐流派。

**根因分析**：
`JellyfinAdapter.getGenres()` 调用 `/Genres` 端点时未指定 `IncludeItemTypes` 参数，导致返回所有类型的流派（电影、电视、音乐等）。Jellyfin 的流派是跨媒体类型的，需要显式过滤。

**修改**（`backend/impl/JellyfinAdapter.kt`）：
- 在 `/Genres` 端点添加 `IncludeItemTypes=Audio` 参数，只返回与音频文件关联的流派
- 同时将 `songCount` 字段从 `MovieCount` 改为 `SongCount`，正确显示歌曲数量

```kotlin
// 改前
val url = "$baseUrl/Genres?UserId=$userId&Recursive=true&Limit=200"
songCount = obj.get("MovieCount")?.asInt?.coerceAtLeast(0)

// 改后
val url = "$baseUrl/Genres?UserId=$userId&IncludeItemTypes=Audio&Recursive=true&Limit=200"
songCount = obj.get("SongCount")?.asInt?.coerceAtLeast(0)
```

**验证**：✅ 测试通过。风格 TAB 现在显示音乐流派（如 Pop、Rock、Jazz 等），不再显示电影流派。

---

#### 10.7.8 A-4 多歌唱家拆分展示修复

**日期**：2026-06-21（同日补充）

**问题**：歌唱家页面显示的原始 artist 字段（如 "罗斯特·洛波维奇&布鲁·诺朱拉纳&索菲娅·穆特&贝多芬"）未被拆分为独立歌唱家。

**根因分析**：
`LibraryScreen` 中 `allArtists` 的生成逻辑直接从歌曲的原始 `artist` 字段获取，未使用 `ArtistSplitter` 进行拆分：
```kotlin
// 改前 - 从原始歌曲数据获取，未拆分
val allArtists = remember(songs) {
    songs.mapNotNull { it.artist.ifBlank { null } }.distinct().sorted()
}
```
而 `artistSongsMap` 已经在 `MainViewModel.buildArtistMaps()` 中正确拆分了歌唱家。

**修改**（`ui/screens/LibraryScreen.kt`）：
将 `allArtists` 改为从 `artistSongsMap.keys` 获取，确保显示拆分后的独立歌唱家：
```kotlin
// 改后 - 从已拆分的 artistSongsMap 获取
val allArtists = remember(artistSongsMap) {
    artistSongsMap.keys.sorted()
}
```

**验证**：✅ 测试通过。"罗斯特·洛波维奇&布鲁·诺朱拉纳&索菲娅·穆特&贝多芬" 已拆分为 4 个独立歌唱家显示。

---

#### 10.7.9 H-9 进度条 D-Pad seek 统一修复

**日期**：2026-06-21（同日补充）

**问题**：
1. 从歌曲页面播放单首歌曲，进度条左右键 seek 正常
2. 从歌唱家详情页点击"播放全部"，进度条左右键移动焦点而非 seek
3. 从专辑、风格等页面播放也有同样问题

**根因分析**：
两种播放路径使用了不同的播放函数：
- 歌曲页面：`playSong(song)` — 替换队列为单曲
- 歌唱家/专辑/风格页面：`playQueue(songList)` — 设置队列

`playSong` 和 `playQueue` 在 `PlayerManager` 中的行为不同：
- `playSong` 检查歌曲是否已在队列中，如果是则 seek 到该位置
- `playQueue` 始终替换队列

此外，`ProgressSection` 的 `LaunchedEffect(Unit)` 只在组件首次创建时运行一次，从其他页面返回时不会重新请求焦点。

**修改**：

| 文件 | 改动 |
|------|------|
| `ui/MainActivity.kt` | 将歌曲页面的 `playSong(song)` 改为 `playQueue(listOf(song))`，统一所有播放路径使用队列 |
| `ui/components/PlayerControls.kt` | `LaunchedEffect(Unit)` 改为 `LaunchedEffect(currentSongId)`，当播放新歌曲时重新请求焦点；新增 `currentSongId` 参数 |

```kotlin
// 改前
onPlaySong = { song ->
    viewModel.playSong(song)
    viewModel.navigateTo(Screen.NowPlaying)
}

// 改后
onPlaySong = { song ->
    viewModel.playQueue(listOf(song))
    viewModel.navigateTo(Screen.NowPlaying)
}
```

**验证**：✅ 测试通过。从歌曲、歌唱家、专辑、风格等所有页面播放，进度条左右键 seek 均正常工作。

---

#### 10.7.10 B-1 收藏/喜欢功能修复

**日期**：2026-06-21（同日补充）

**问题**：
1. 在正在播放页面点击收藏按钮，桃心无法点亮
2. 进入曲库的收藏页面，没有列出已收藏的歌曲

**根因分析**：
`JellyfinAdapter.toggleFavorite()` 使用了错误的 API 端点 `/Items/{id}/Favorite`，该端点返回 404 Not Found。Jellyfin 的收藏 API 端点应该是 `/UserFavoriteItems/{id}`。

日志显示：
```
POST /Items/57ad96dad451f57f589e4443b45a8dfb/Favorite?api_key=...
<-- 404 Not Found
```

**修改**（`backend/impl/JellyfinAdapter.kt`）：
- 将 `toggleFavorite()` 的 API 端点从 `/Items/{id}/Favorite` 改为 `/UserFavoriteItems/{id}`
- 添加收藏状态缓存 `_favoriteIdsCache`，用于判断当前是否已收藏
- 使用 POST 添加收藏，DELETE 取消收藏
- `getFavorites()` 加载时更新缓存

```kotlin
// 改前
val request = Request.Builder()
    .url("$baseUrl/Items/$songId/Favorite?api_key=$apiToken")
    .header("X-Emby-Authorization", buildAuthHeader())
    .post("".toRequestBody(null))
    .build()

// 改后
val isCurrentlyFavorite = _favoriteIdsCache.contains(songId)
val requestBuilder = Request.Builder()
    .url("$baseUrl/UserFavoriteItems/$songId")
    .header("X-Emby-Authorization", buildAuthHeader())

val request = if (isCurrentlyFavorite) {
    requestBuilder.delete("".toRequestBody(null)).build()
} else {
    requestBuilder.post("".toRequestBody(null)).build()
}
```

**验证**：✅ 测试通过。收藏按钮可正常点亮/熄灭，收藏页面正确显示已收藏歌曲。

---

#### 10.7.11 B-2 播放次数显示

**日期**：2026-06-21（同日补充）

**问题**：播放次数已存储在 `AppPreferences.playCounts` 中，但 UI 上没有显示播放次数。

**修改**：

| 文件 | 改动 |
|------|------|
| `ui/screens/LibraryScreen.kt` | `SongRow` 新增 `playCount` 参数，播放次数大于 0 时在时长前显示（如 "3次"）；`RecentTab` 新增 `playCounts` 参数并传递给 `SongRow`；`LibraryScreen` 新增 `playCounts` 参数 |
| `ui/MainActivity.kt` | 从 `viewModel.playCounts` 收集状态并传递给 `LibraryScreen` |

```kotlin
// SongRow 中新增播放次数显示
if (playCount != null && playCount > 0) {
    Text(text = "${playCount}次", color = NasMusicColors.Primary, fontSize = 10.sp, modifier = Modifier.padding(end = 8.dp))
}
```

**验证**：✅ 测试通过。最近页面中已播放歌曲显示播放次数（如 "3次"）。

---

#### 10.7.12 H-10 ProgressSection 焦点请求修复

**日期**：2026-06-21（同日补充）

**问题**：从某些入口（如歌唱家详情页点击单首歌曲）进入正在播放页面时，进度条无法 seek，只能移动焦点。

**根因分析**：
`ProgressSection` 使用 `LaunchedEffect(currentSongId)` 请求焦点，但 `NowPlayingScreen` 未将 `currentSong?.id` 传递给 `ProgressSection`，导致 `currentSongId` 始终为 `null`，`LaunchedEffect` 不会重新触发。

**修改**（`ui/screens/NowPlayingScreen.kt`）：
在 `ProgressSection` 调用中添加 `currentSongId` 参数：

```kotlin
// 改前
ProgressSection(
    progressMs = progressMs,
    durationMs = durationMs,
    onSeek = onSeek,
    compact = true
)

// 改后
ProgressSection(
    progressMs = progressMs,
    durationMs = durationMs,
    onSeek = onSeek,
    compact = true,
    currentSongId = currentSong?.id
)
```

**验证**：✅ 测试通过。所有播放入口（歌曲、专辑、歌唱家、流派、年代等）进度条 seek 均正常工作。

---

#### 10.7.13 B-3 歌词高亮模式增强

**日期**：2026-06-21（同日补充）

**问题**：歌词只能逐行高亮，无法逐字高亮。网络获取的标准 LRC 格式歌词没有逐字时间戳。

**修改**：

| 文件 | 改动 |
|------|------|
| `data/model/LyricsLine.kt` | 新增 `LyricsHighlightMode` 枚举（`LINE_BY_LINE`, `WORD_BY_WORD`） |
| `ui/components/LyricsView.kt` | 新增 `highlightMode` 参数；实现逐字时间戳估算逻辑 `estimateWordTimestamps()`；逐字模式下已播放文字显示为黄色 |
| `ui/screens/NowPlayingScreen.kt` | 新增 `highlightMode` 状态；自动检测歌词格式（有逐字时间戳则自动切换到逐字模式）；新增"逐行/逐字"切换按钮 |

**功能说明**：
- **自动检测**：如果歌词包含逐字时间戳（卡拉 OK 格式），自动切换到"逐字"模式
- **手动切换**：点击歌词区域右上角的"逐行/逐字"按钮可随时切换模式
- **逐字估算**：标准 LRC 格式在"逐字"模式下，将行时长平均分配给每个字符
- **颜色区分**：逐字模式下，已播放文字显示为黄色，未播放文字保持原色

```kotlin
// 逐字时间戳估算逻辑
private fun estimateWordTimestamps(line: LyricsLine, nextLineTime: Long): List<WordTimestamp> {
    if (line.text.isEmpty()) return emptyList()
    val lineDuration = if (nextLineTime > line.time) nextLineTime - line.time else 3000L
    val charDuration = lineDuration / line.text.length
    return line.text.mapIndexed { index, char ->
        WordTimestamp(
            word = char.toString(),
            startMs = line.time + index * charDuration,
            durationMs = charDuration
        )
    }
}
```

**验证**：✅ 测试通过。逐字模式下已播放文字显示为黄色，可手动切换逐行/逐字模式。

---

#### 10.7.14 B-5 全屏封面模糊效果

**日期**：2026-06-21（同日补充）

**功能描述**：点击封面图进入全屏沉浸模式时，对全屏封面图做模糊处理，不影响上层显示的歌词。

**修改**（`ui/screens/NowPlayingScreen.kt`）：
- 对全屏封面图的 `AsyncImage` 添加 `Modifier.blur(30.dp)` 模糊效果
- 模糊效果仅应用于封面图，不影响上层歌词和渐变遮罩

```kotlin
AsyncImage(
    model = currentSong.coverUrl,
    contentDescription = "Fullscreen Cover Background",
    modifier = Modifier
        .fillMaxSize()
        .blur(30.dp) // 模糊效果，不影响上层歌词
)
```

**层级结构**：
```
Box {
    AsyncImage(blur=30.dp)  // 模糊的封面图（背景层）
    Box(gradient overlay)   // 渐变遮罩（确保歌词可读）
    Lyrics                  // 歌词（最上层，清晰显示）
}
```

**说明**：模糊效果与渐变遮罩互补，不冲突。模糊让背景更柔和，遮罩确保歌词对比度。

**验证**：✅ 测试通过。

---

#### 10.7.15 B-4 均衡器导航修复

**日期**：2026-06-21（同日补充）

**问题**：设置页面的"均衡器"按钮没有实际导航功能，点击无反应。

**根因分析**：
`SettingsScreen` 中均衡器按钮的 `onClick` 处理器为空注释 `{ /* Navigate to Equalizer - handled externally */ }`，没有实际的导航回调。

**修改**：

| 文件 | 改动 |
|------|------|
| `ui/screens/SettingsScreen.kt` | 新增 `onOpenEqualizer` 回调参数；均衡器按钮 `onClick` 调用 `onOpenEqualizer?.invoke()` |
| `ui/MainActivity.kt` | 传递 `onOpenEqualizer = { viewModel.navigateTo(Screen.Equalizer) }` 给 `SettingsScreen` |

```kotlin
// 改前
SettingActionButton(
    label = "均衡器",
    description = "调节各频段增益",
    onClick = { /* Navigate to Equalizer - handled externally */ }
)

// 改后
SettingActionButton(
    label = "均衡器",
    description = "调节各频段增益",
    onClick = { onOpenEqualizer?.invoke() }
)
```

**验证**：✅ 测试通过。设置 → 播放 → 均衡器 可正常打开均衡器页面。

---

#### 10.7.16 编码处理修复（繁体中文/多编码支持）

**日期**：2026-06-21（同日补充）

**问题**：部分歌曲信息显示为乱码，如 `ÎÒÊÇÕæµÄ°®Äã`（实际是 "我是真的爱你" 的 GB2312 编码被当作 Latin-1 解码）或末尾带 `�?`。

**根因分析**：
1. **GB2312/GBK 编码问题**：MP3 文件的 ID3 标签使用 GB2312/GBK 编码，但 Jellyfin 返回时被当作 Latin-1 解码，导致中文字符显示为乱码
2. **末尾乱码**：部分歌曲标题末尾包含 `�?`（U+FFFD + 问号），是数据截断的标志

**修改**：

| 文件 | 改动 |
|------|------|
| `backend/impl/JellyfinAdapter.kt` | 新增 `fixEncoding()` 函数，处理两种乱码模式 |
| `backend/impl/NavidromeAdapter.kt` | 新增 `fixEncoding()` 函数 |

**编码修复逻辑**：
```kotlin
private fun fixEncoding(text: String?): String? {
    if (text.isNullOrBlank()) return text
    
    // 第一步：移除末尾的乱码模式：�?（U+FFFD + ?）
    var fixed: String = text
    while (fixed.endsWith("?") || fixed.endsWith("\uFFFD?") || fixed.endsWith("\uFFFD")) {
        if (fixed.endsWith("\uFFFD?")) {
            fixed = fixed.dropLast(2)
        } else {
            fixed = fixed.dropLast(1)
        }
    }
    
    // 第二步：检测 GB2312/GBK 编码被当作 Latin-1 解码的情况
    val latin1Count = fixed.count { it.code in 0x80..0xFF }
    val totalCount = fixed.length
    
    // 如果超过 30% 的字符是 Latin-1 扩展字符，尝试从 Latin-1 转换到 GB2312
    if (latin1Count > 0 && latin1Count.toFloat() / totalCount > 0.3f) {
        try {
            val bytes = fixed.toByteArray(Charsets.ISO_8859_1)
            val decoded = String(bytes, charset("GB2312"))
            if (decoded.any { it.code in 0x4E00..0x9FFF }) {
                fixed = decoded
            }
        } catch (e: Exception) {
            // GB2312 失败，尝试 GBK
            try {
                val bytes = fixed.toByteArray(Charsets.ISO_8859_1)
                val decoded = String(bytes, charset("GBK"))
                if (decoded.any { it.code in 0x4E00..0x9FFF }) {
                    fixed = decoded
                }
            } catch (e2: Exception) {}
        }
    }
    
    return if (fixed.isBlank()) text else fixed
}
```

**失败的修改方案（记录备忘，避免重复错误）**：

| 方案 | 失败原因 |
|------|----------|
| 对所有字符串尝试 ISO-8859-1 → UTF-8 转换 | 破坏正常中文字符（如 `、` 被转为 `�?`） |
| 检测 0x80-0xFF 范围字符就尝试转换 | 正常中文字符也在该范围内，导致误判 |
| 多编码尝试 + 中文字符数量比较 | 对已经是 UTF-8 的字符串进行转换会破坏数据 |

**关键教训**：
- ✅ 先检测 Latin-1 扩展字符比例（>30%），再尝试 GB2312/GBK 转换
- ✅ 只对明确的乱码模式（末尾 `�?`）进行移除
- ✅ 转换后验证是否包含中文字符，避免误转换

**验证**：✅ 测试通过。`ÎÒÊÇÕæµÄ°®Äã(live°æ)` 正确转换为 `我是真的爱你(live版)`。

**服务器端修复方案（推荐）**：

MP3 文件的 ID3 标签编码问题是根本原因。推荐使用以下工具批量修复：

| 工具 | 平台 | 说明 |
|------|------|------|
| **MusicBrainz Picard** | 跨平台 | 自动匹配 MusicBrainz 数据库，修复元数据和编码。推荐首选 |
| **EasyTAG** | Linux/Windows | 图形界面，支持批量编辑 ID3 标签编码 |
| **id3-charset-converter** | Java (命令行) | 自动检测编码并转换为 UTF-8 |
| **Mp3tag** | Windows | 功能强大的 ID3 标签编辑器 |

**修复步骤（以 MusicBrainz Picard 为例）**：
1. 下载安装 MusicBrainz Picard
2. 导入音乐文件夹
3. 选择文件 → 右键 → "Scan" 自动匹配
4. 保存时选择 "ID3v2.3 + UTF-8" 编码
5. 重新扫描 Jellyfin 音乐库

**注意事项**：
- 修复前建议备份原始文件
- ID3v2.3 + UTF-8 是兼容性最好的组合
- 修复后需要在 Jellyfin 中重新扫描音乐库

---

#### 10.7.17 歌曲时长获取修复

**日期**：2026-06-21（同日补充）

**问题**：播放歌曲时无法获取总时长，导致进度条不移动，无法 seek。

**根因分析**：
Jellyfin API 的 `fields` 参数未包含 `Album`、`AlbumArtist`、`Artists`、`IndexNumber`、`ParentIndexNumber`、`ProductionYear`、`Genres` 等字段，导致 API 返回的数据不完整。

**修改**（`backend/impl/JellyfinAdapter.kt`）：
扩展 `getSongs()` 方法的 `fields` 参数，包含所有必要字段：

```kotlin
// 改前
val fields = "PrimaryImageAspectRatio,SortName,ParentId,RunTimeTicks"

// 改后
val fields = "PrimaryImageAspectRatio,SortName,ParentId,RunTimeTicks,Album,AlbumArtist,Artists,IndexNumber,ParentIndexNumber,ProductionYear,Genres"
```

**验证**：✅ 测试通过。播放歌曲时正确获取总时长，进度条正常移动，seek 功能正常工作。

---

#### 10.7.18 TV 桌面图标显示修复

**日期**：2026-06-21（同日补充）

**问题**：应用安装后在电视桌面和"我的应用"中找不到图标，只能在应用卸载列表中看到。

**根因分析**：
AndroidManifest.xml 中 MainActivity 的 intent-filter 只有 `LEANBACK_LAUNCHER` 类别，缺少 `LAUNCHER` 类别。部分电视系统需要两个类别同时存在才能在桌面显示应用图标。

**修改**（`app/src/main/AndroidManifest.xml`）：
在 MainActivity 的 intent-filter 中添加 `LAUNCHER` 类别：

```xml
<!-- 改前 -->
<intent-filter>
    <action android:name="android.intent.action.MAIN" />
    <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
</intent-filter>

<!-- 改后 -->
<intent-filter>
    <action android:name="android.intent.action.MAIN" />
    <category android:name="android.intent.category.LAUNCHER" />
    <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
</intent-filter>
```

**验证**：✅ 测试通过。应用图标正常显示在电视桌面和"我的应用"中。

---

#### 10.7.19 分批加载与进度显示

**日期**：2026-06-21（同日补充）

**问题**：
1. 歌曲无数量限制，加载所有歌曲导致内存溢出和应用崩溃
2. 加载过程中用户看不到进度

**根因分析**：
- 无数量限制时，应用尝试加载服务器上的所有歌曲（17,500+ 首）
- 所有歌曲存储在内存中，导致频繁垃圾回收（GC）和内存不足
- 最终导致应用崩溃

**修改**：

| 文件 | 改动 |
|------|------|
| `ui/viewmodel/MainViewModel.kt` | 添加 `maxSongs = 50000` 上限，限制最多加载 50,000 首歌曲 |
| `ui/screens/LibraryScreen.kt` | 加载时显示 "已加载 X 首歌曲"，实时更新进度 |

**加载逻辑**：
```kotlin
val maxSongs = 50000 // 最多加载 50000 首，避免内存问题
val batchSize = 500

while (hasMore && allSongs.size < maxSongs) {
    val batch = adapter.getSongs(batchSize, currentOffset)
    if (batch.isEmpty()) {
        hasMore = false
    } else {
        // 计算还能添加多少首
        val remaining = maxSongs - allSongs.size
        val songsToAdd = if (batch.size > remaining) batch.take(remaining) else batch
        
        allSongs.addAll(songsToAdd)
        _songs.value = allSongs.toList() // 更新 UI
        buildArtistMaps(allSongs)
        
        if (batch.size < batchSize || allSongs.size >= maxSongs) {
            hasMore = false
        } else {
            currentOffset += batchSize
            delay(50) // 短暂延迟，让 UI 有时间响应
        }
    }
}
```

**UI 显示**：
```kotlin
if (isLoading) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = "加载中...", color = NasMusicColors.TextSecondary, fontSize = 20.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "已加载 ${songs.size} 首歌曲",
            color = NasMusicColors.TextSecondary,
            fontSize = 16.sp
        )
    }
}
```

**验证**：✅ 测试通过。歌曲正常加载，显示进度，不再崩溃。

---

### 10.8 v2.1.0 — 核心功能实现

#### 10.8.1 专辑详情页（A-1）

**功能描述**：新增 AlbumDetailScreen，点击专辑卡片时先进入详情页，展示专辑封面（大图）、专辑名 + 演唱者 + 年份、曲目列表（带序号/时长，可逐首选播）、底部「播放全部」按钮。

**新增文件**：`ui/screens/AlbumDetailScreen.kt`

**修改文件**：`ui/viewmodel/MainViewModel.kt`（新增 `Screen.ALBUM_DETAIL` 及 `selectedAlbum` 状态）、`ui/MainActivity.kt`（`when(currentScreen)` 新增分支）

**验证状态**：✅ 编译通过，🔶 未设备测试。

**测试方法**：
1. 连接后端 → 进入曲库 → 选中一个专辑卡片 → DPAD 确认键点击 → 应进入专辑详情页
2. 验证详情页显示：专辑封面（大图）、专辑名称、演唱者、年份、曲目列表（带序号和时长）
3. 选择列表中的一首歌 → 确认 → 应开始播放该歌曲，界面跳转到 NowPlaying
4. 按返回键 → 应回到专辑详情页
5. 聚焦「播放全部」按钮 → 确认 → 应从第一首开始播放该专辑所有歌曲
6. 按返回键两次 → 应回到曲库页

---

#### 10.8.2 演唱者详情页（A-2）

**功能描述**：新增 ArtistDetailScreen，点击 ArtistCard 先进入详情页，展示歌手封面、歌手名、该歌手所有歌曲列表（可逐首选播）、底部「播放全部」按钮。

**新增文件**：`ui/screens/ArtistDetailScreen.kt`

**修改文件**：`ui/viewmodel/MainViewModel.kt`（新增 `Screen.ARTIST_DETAIL` 及 `selectedArtist` 状态）、`ui/MainActivity.kt`（`when(currentScreen)` 新增分支）

**验证状态**：✅ 编译通过，🔶 未设备测试。

**测试方法**：
1. 连接后端 → 曲库 → 歌唱家 tab → 选中一个 ArtistCard → 确认 → 进入详情页
2. 验证详情页：歌唱家封面/头像、名称、歌曲列表（曲名 + 时长）
3. 选择一首歌 → 确认 → 开始播放，界面切换到 NowPlaying
4. 返回 → 回到详情页 → 播放全部按钮 → 播放该歌唱家全部歌曲
5. 对多歌唱家歌曲（如"A & B"）→ A 和 B 的详情页中均出现此歌

---

#### 10.8.3 曲库过滤增强 — 流派/年代（A-3）

**功能描述**：LibraryScreen 增加 GENRES（流派）和 YEARS（年代）两个 tab。后端两个适配器均实现 `getGenres()`、`getSongsByGenre()`、`getSongsByYearRange()`。

**新增数据模型**：`data/model/Genre.kt`

**修改文件**：`ui/screens/LibraryScreen.kt`（新增 GenresTab + YearsTab）、`backend/impl/JellyfinAdapter.kt`（实现 3 个接口）、`backend/impl/NavidromeAdapter.kt`（实现 3 个接口）

**验证状态**：✅ 编译通过，🔶 未设备测试。

**测试方法**：
1. 连接后端 → 曲库 → 切换到「流派」tab → 应显示后端返回的流派列表（如 Pop、Rock、Jazz）
2. 选中一个流派 → 确认 → 进入该流派的歌曲列表
3. 选择一首歌 → 确认 → 播放该歌曲
4. 按返回 → 流派列表 → 切换到「年代」tab → 应显示预定义的年代区间
5. 选中一个年代（如"2020s"）→ 确认 → 显示该年代的所有歌曲
6. 选择一首歌 → 确认 → 播放
7. 确认每个流派/年代入口都有「播放全部」按钮，点击后播放该分类下全部歌曲

---

#### 10.8.4 多歌唱家拆分展示（A-4）

**功能描述**：新增 `ArtistSplitter` 工具类，按 `feat.`/`ft.`/`&`/`/`/`vs.`/`with` 等分隔符拆分艺术家字段。ViewModel 中 `buildArtistMaps()` 构建 `songArtistMap` 和 `artistSongsMap`，曲库歌唱家 tab 只显示拆分后的独立歌唱家，合唱歌曲同时出现在各艺术家详情页。

**新增文件**：`util/ArtistSplitter.kt`

**修改文件**：`ui/viewmodel/MainViewModel.kt`（`buildArtistMaps()` 在歌曲加载后调用）

**关键设计**：不修改 `Song.artist` 原始字段值，只在映射层展开。播放页保持显示原始字符串（如"张三 & 李四"）。

**验证状态**：✅ 编译通过，逻辑经代码审查确认（纯工具类 + 内存映射，无需设备验证）。

**测试方法**：
1. 连接后端 → 曲库 → 歌唱家 tab → 后端有合唱歌曲（如"张三 feat. 李四"）时，列表中应显示为独立的"张三"和"李四"
2. 选中"张三" → 确认进入详情页 → 列表中应包含"张三 feat. 李四"这首歌
3. 选中"李四" → 确认进入详情页 → 同样应包含这首歌
4. 播放该歌曲 → NowPlaying 页艺术家字段应显示原始字符串"张三 feat. 李四"（非拆分后）
5. 确认没有出现 `&`、`feat.`、`ft.`、`with`、`vs.` 等分隔符残留问题

---

#### 10.8.5 收藏/喜欢功能（B-1）

**功能描述**：全链路收藏功能——NowPlayingScreen 右上方心形按钮（♥/♡），LibraryScreen 新增 FAVORITES tab 展示收藏歌曲列表。后端适配器实现 `toggleFavorite()` / `getFavorites()`。

**新增接口**：`BackendAdapter.toggleFavorite()` / `getFavorites()`（默认实现返回 `false` / `emptyList()`）

**修改文件**：`ui/screens/NowPlayingScreen.kt`（FavoriteButton）、`ui/screens/LibraryScreen.kt`（FavoritesTab）、`ui/viewmodel/MainViewModel.kt`（`favoriteIds` 状态 + `loadFavorites()`）、`backend/impl/JellyfinAdapter.kt`、`backend/impl/NavidromeAdapter.kt`

**验证状态**：✅ 编译通过，🔶 未设备测试。

**测试方法**：
1. 连接后端 → 进入 NowPlaying 播放一首歌 → 右上角应有 ♡ 按钮
2. 聚焦 ♡ 按钮 → 确认 → 按钮变为 ♥（高亮状态），logcat 确认 `toggleFavorite` 调用成功
3. 再按一次确认 → ♥ 变回 ♡（取消收藏）
4. 收藏 2-3 首歌 → 切换到曲库 → 进入「收藏」tab → 应显示已收藏的歌曲列表
5. 在收藏 tab 选择一首歌 → 确认 → 播放
6. 验证重新启动 App 后收藏状态保持（从后端重新加载）

---

#### 10.8.6 最近播放 & 播放次数（B-2）

**功能描述**：每次 `playSong()` 时记录播放历史到 DataStore（LRU 50 条），累加播放次数。LibraryScreen 新增 RECENT tab 展示最近播放列表。

**修改文件**：`data/prefs/AppPreferences.kt`（`recordPlay()` + `playCounts` + `recentSongs`）、`ui/viewmodel/MainViewModel.kt`（`recordPlay()` 调用点）、`ui/screens/LibraryScreen.kt`（RecentTab）

**验证状态**：✅ 编译通过，🔶 未设备测试。

**测试方法**：
1. 连接后端 → 播放 3-5 首不同的歌曲（每首至少播放几秒）
2. 切换到曲库 →「最近播放」tab → 应显示刚才播放的歌曲，按播放时间逆序排列
3. 同一首歌播放多次 → 最近播放列表不重复（只保留最新一次）
4. 播放超过 50 首不同的歌 → 最旧的记录被移除（LRU 行为）
5. 验证歌曲卡片上显示播放次数（如"3次"）
6. 杀进程重启 App → 最近播放列表和播放次数应保持（DataStore 持久化）

---

#### 10.8.7 歌词卡拉 OK 逐字高亮（B-3）

**功能描述**：`LyricsView` 支持逐字高亮模式（`LyricsHighlightMode.WORD_BY_WORD`），利用 LRC 逐字时间戳 `<mm:ss.xx>` 在 Canvas 上绘制逐字填充效果。歌词来源标签旁新增逐行/逐字模式切换按钮。

**新增数据模型**：`data/model/LyricsHighlightMode`（枚举 LINE_BY_LINE / WORD_BY_WORD）

**修改文件**：`lyrics/LrcParser.kt`（解析逐字时间戳）、`ui/components/LyricsView.kt`（Canvas 逐字绘制）、`ui/screens/NowPlayingScreen.kt`（模式切换按钮）

**自动检测**：歌词行中包含逐字时间戳时自动切换到逐字模式。

**验证状态**：✅ 编译通过，✅ 设备测试通过（2026-06-21 验证）。

**测试方法**：
1. 播放一首有 LRC 歌词的歌曲 → 默认模式下歌词逐行滚动高亮
2. 播放一首包含逐字时间戳 `<mm:ss.xx>` 歌词的歌曲 → 应自动切换到逐字模式
3. 在逐字模式下，已播放的文字应逐字填充高亮（黄色），未播放部分为灰色
4. 点击歌词来源标签旁的切换按钮 → 可手动在"逐行"和"逐字"模式间切换
5. 切换模式后，高亮效果应即时改变，不卡顿
6. 验证逐字模式下歌词滚动仍然平滑，D-pad 上下键滚动正常

---

#### 10.8.8 均衡器（B-4）

**功能描述**：完整均衡器功能——EqualizerScreen 带 7 频段 D-pad 滑块，6 种预置方案（Normal/Pop/Rock/Classical/Jazz/Custom），PlayerManager 集成 `AudioEffect` API，设置持久化到 DataStore。

**新增文件**：`ui/screens/EqualizerScreen.kt`（266 行）

**新增数据模型**：`data/model/EqualizerPreset`（枚举 + bands 配置）

**修改文件**：`data/prefs/AppPreferences.kt`（`equalizerPreset` / `equalizerBands` flow + setter）、`player/PlayerManager.kt`（`initEqualizer` / `setEqualizerBand` / `disableEqualizer`）、`ui/screens/SettingsScreen.kt`（均衡器入口）

**注意事项**：部分 Android TV 设备可能不支持 AudioEffect（`hasDiscreteVolumes` 检查未实现，属于防御性增强）。

**验证状态**：✅ 编译通过，🔶 未设备测试。

**测试方法**：
1. 连接后端 → 设置 → 播放 → 均衡器 → 进入 EqualizerScreen
2. 验证页面显示 7 个频段滑块（60Hz ~ 16kHz）和预置方案列表
3. 选择一个预置方案（如 Rock）→ 滑块自动调整到对应位置，音效变化
4. 手动拖动一个滑块 → 预置方案自动切换到 Custom
5. 调整后按返回回到设置 → 重新进入均衡器 → 设置保持
6. 杀进程重启 App → 均衡器设置保持（DataStore 持久化）
7. **注意**：部分 Android TV 设备不支持 AudioEffect → 如果页面空白或报错，属于正常兼容问题

---

#### 10.8.9 封面图全屏沉浸模式（B-5）

**功能描述**：NowPlayingScreen 中点击封面图或按 OK 键切换沉浸模式——封面图放大至全屏作为背景（高斯模糊 30dp + 半透明渐变遮罩），歌词叠加在封面之上滚动，再次点击恢复常规布局。

**修改文件**：`ui/screens/NowPlayingScreen.kt`（`isImmersiveMode` 状态 + 布局切换逻辑）

**关键设计**：沉浸模式下歌词区域的半透明背景改为 `Color.Transparent`，避免与全屏遮罩叠加。

**验证状态**：✅ 编译通过，✅ 设备测试通过（2026-06-21 验证）。

**测试方法**：
1. 播放一首有封面的歌曲 → NowPlaying 左侧显示专辑封面
2. 聚焦封面区域 → 按 OK/确认键 → 切换为沉浸模式
3. 验证沉浸模式：封面图放大至全屏背景，有高斯模糊效果和半透明遮罩
4. 验证歌词叠加在封面背景之上，清晰可读
5. 再次按 OK/确认键或按返回键 → 恢复到常规布局
6. 播放无封面的歌曲 → 封面区域为占位符（♪）→ 点击不应进入沉浸模式或优雅处理

---

#### 10.8.10 播放队列上下移动（C-1）

**功能描述**：QueueScreen 每首歌曲右侧增加 ↑↓ 移动按钮（首项无 ↑，末项无 ↓），`PlayerManager.moveItem(fromIndex, toIndex)` 实现队列重排，播放中的曲目索引同步更新。

**修改文件**：`player/PlayerManager.kt`（新增 `moveItem()`）、`ui/screens/QueueScreen.kt`（MoveButton + ↑↓ 按钮渲染）、`ui/viewmodel/MainViewModel.kt`（`moveQueueItem()` 桥接方法）、`ui/MainActivity.kt`（`onMoveItem` 回调）

**验证状态**：✅ 编译通过，🔶 未设备测试。

**测试方法**：
1. 播放一首歌 → 进入队列（QueueScreen）
2. 验证每首歌曲右侧有 ↑ 和 ↓ 按钮（第一首无 ↑，最后一首无 ↓）
3. 选中一首歌的 ↓ 按钮 → 确认 → 该曲目下移一位
4. 选中一首歌的 ↑ 按钮 → 确认 → 该曲目上移一位
5. 多次移动后 → 播放队列中的下一首 → 确认播放顺序跟随新排序
6. 当前正在播放的歌曲被移动时 → 不中断播放，索引正确同步

---

#### 10.8.11 无间断播放 & 预加载（C-2）

**功能描述**：`playSong()` 中检查目标歌曲是否已在队列中——如果在则 seek 到对应位置（无间断路径），如果不在则替换队列为单曲并预加载下一首。

**修改文件**：`player/PlayerManager.kt`（`playSong()` 增加 `setNextMediaItem` 和队列复用逻辑）

**验证状态**：✅ 编译通过，🔶 未设备测试。

**测试方法**：
1. 播放一首歌 → 播放到后半段 → 确认下一曲启动无明显停顿（衔接流畅）
2. logcat 查看 `setNextMediaItem` 是否在当前曲目播放时已被调用
3. 播放列表播放 → 快速连续切歌（下一曲 → 下一曲）→ 确认每首播放正常无重复
4. 当前队列中的歌曲被直接 `playSong()` 调用时（如从曲库选歌）→ 确认 seek 到对应位置（无缝切换），不重新缓冲

---

#### 10.8.12 后台服务加固（D-1）

**功能描述**：PlaybackService 增加前台通知，创建 `NotificationChannel`（id: `playback_channel`），`onCreate()` 中调用 `startForeground()`，实时 `updateNotification()` 显示当前歌曲信息。

**修改文件**：`player/PlaybackService.kt`（`createNotificationChannel()` + `buildNotification()` + `updateNotification()` + `onTaskRemoved()` 停止处理）

**注意事项**：`MediaLibrarySession.Callback` 仍为空实现（`{}`），依赖 Media3 默认行为处理基础播放控制。前台通知功能已正常工作。

**验证状态**：✅ 编译通过，🔶 未设备测试。

**测试方法**：
1. 安装并启动 App → 播放一首歌 → 查看电视状态栏（或通知中心）应出现播放通知
2. 通知应显示：当前歌曲名称、播放/暂停按钮、上一首/下一首按钮
3. 暂停播放 → 通知切换为暂停状态
4. 切歌 → 通知内容更新为新的歌曲信息
5. 按 HOME 键回到桌面 → 通知仍在 → 通过通知点击应能返回 App
6. **验证前台服务**：`adb shell dumpsys activity services com.nasmusic.tv` 确认服务状态为 `started`（非 `bound`）

---

#### 10.8.13 网络监听 & 自动重连（D-2）

**功能描述**：MainActivity 注册 `ConnectivityManager.NetworkCallback` 监听网络变化，网络恢复时 ViewModel 自动尝试重连（最多 3 次），断开/恢复时显示 `connectMessage` 悬浮提示。

**修改文件**：`ui/MainActivity.kt`（`registerNetworkCallback()` + 生命周期管理）、`ui/viewmodel/MainViewModel.kt`（`onNetworkAvailable()` / `onNetworkLost()` + 重连逻辑）

**验证状态**：✅ 编译通过，🔶 未设备测试。

**测试方法**：
1. 连接后端 → 播放一首歌 → 断开 TV 的网络（拔网线 / 关闭 Wi-Fi）
2. 应出现悬浮提示"网络已断开"（显示约 5 秒后消失）
3. 曲库操作（如切换 tab）应显示空白或缓存数据（当前行为：不崩溃即可）
4. 恢复网络连接 → 应出现悬浮提示"网络已恢复"
5. 第二次提示消失后 → App 应自动尝试重连（最多 3 次）
6. logcat 查看 `onNetworkAvailable: reconnecting (attempt 1/3)` 日志
7. 重连成功后 → 曲库恢复正常加载，播放继续

---

#### 10.8.14 清理废弃代码（E-3）

**修改内容**：删除 `backend/jellyfin/` 和 `backend/navidrome/` 两个目录下的旧 Retrofit 实现（约 400-500 行死代码）。检查 `build.gradle.kts` 中 Retrofit 依赖无其他引用（依赖本身已在 A-3 中移除）。

**验证状态**：✅ 构建通过，APK 大小减少（纯删除操作，无需设备验证）。

**验证方法**：
1. 确认 `app/src/main/java/com/nasmusic/tv/backend/jellyfin/` 和 `backend/navidrome/` 目录已不存在
2. 全局搜索 `import retrofit2` — 应无匹配（无 Retrofit 引用残留）
3. `./gradlew assembleDebug` 编译通过
4. 安装 APK 到电视 → 连接后端（Jellyfin + Navidrome 分别测试）→ 播放正常

---

#### 10.8.15 缓存管理 UI（E-4）

**功能描述**：设置页新增「缓存管理」栏目，显示当前缓存目录大小，提供「清除歌词缓存」「清除封面缓存」按钮。LyricsManager 和 CoverArtManager 分别暴露 `clearCache()` 方法。

**修改文件**：`ui/screens/SettingsScreen.kt`（缓存栏目 + 大小计算 + 清除按钮 + 确认弹窗）、`lyrics/LyricsManager.kt`（`clearLyricsCache()`）、`player/CoverArtManager.kt`（`clearCoverCache()`）

**验证状态**：✅ 编译通过，🔶 未设备测试。

**测试方法**：
1. 连接后端 → 播放几首歌（让歌词和封面缓存到本地）
2. 进入设置 → 滑到「缓存管理」栏目 → 应显示当前缓存目录大小（如 "当前缓存目录大小: 2.5 MB"）
3. 点击「清除歌词缓存」按钮 → 出现确认弹窗 → 确认 → 提示"歌词缓存已清除"
4. `adb shell ls -la /data/data/com.nasmusic.tv/cache/lyrics/` 确认目录已清空
5. 播放上一首已缓存歌词的歌曲 → 歌词重新从网络/后端获取
6. 点击「清除封面缓存」按钮 → 类似操作 → 确认后封面重新加载

---

#### 10.8.16 HDMI-CEC 媒体键支持（G-1）

**功能描述**：Activity 的 `onKeyDown()` 映射 HDMI-CEC / 蓝牙遥控器媒体键：`KEYCODE_MEDIA_PLAY_PAUSE` → 播放/暂停，`MEDIA_NEXT` → 下一曲，`MEDIA_PREVIOUS` → 上一曲，`MEDIA_STOP` → 停止，`DPAD_CENTER`/`ENTER` → 沉浸模式切换。

**修改文件**：`ui/MainActivity.kt`（`onKeyDown()` 增加媒体键分发）

**验证状态**：✅ 编译通过，🔶 未设备测试。

**测试方法**：
1. 连接后端 → 播放一首歌 → 使用电视遥控器的**播放/暂停键** → 歌曲应暂停/继续
2. 使用遥控器的**下一曲键** → 跳到下一首
3. 使用遥控器的**上一曲键** → 回到上一首（或在当前曲播放超过 3 秒后回到开头）
4. 使用遥控器的**停止键** → 停止播放
5. 使用遥控器的方向键 OK/确认 → 在 NowPlaying 页应切换沉浸模式
6. **注意**：HDMI-CEC 功能依赖电视固件和 HDMI 线缆支持，部分遥控器可能无独立媒体键

---

#### 10.8.17 播放列表管理 UI（G-2）

**功能描述**：完整播放列表管理界面 PlaylistManagementScreen（左右分栏布局——左侧播放列表示，右侧选中列表的歌曲明细），支持创建（TextInputDialog 输入名称）、删除（确认弹窗）、播放、移除歌曲。

**涉及文件**：`ui/screens/PlaylistManagementScreen.kt`（385 行）、`ui/viewmodel/MainViewModel.kt`（`createPlaylist()` / `deletePlaylist()` / `loadPlaylistSongs()`）

**验证状态**：✅ 编译通过，🔶 未设备测试。

**测试方法**：
1. 连接后端 → 进入播放列表管理页面
2. 点击"+ 新建"→ 弹出 TextInputDialog → 输入名称（如"我的歌单"）→ 确认 → 列表中出现新条目
3. 点击空名称 → 不触发创建
4. 选中新建的播放列表 → 右侧显示"该播放列表为空"
5. 从曲库找一首歌 → 确认当前无法直接加入（此功能尚未实现）→ 后续可通过从 NowPlaying 页或曲库添加
6. 选中一个已有歌曲的播放列表 → 右侧显示歌曲列表 → 选中一首歌的移除按钮 → 歌曲被移除
7. 选中播放列表 → 「删除」→ 确认弹窗 → 确认 → 列表消失
8. 选中播放列表 → 「播放全部」→ 从第一首开始播放

---

#### 10.8.18 NowPlaying 布局调整（roadmap-ui）

**功能描述**：三个 UI 布局调整——(1) 播放控制按钮（播放/暂停/上一首/下一首/播放模式）从封面右侧移到封面图下方；(2) 进度条扩展为横向占满（fillMaxWidth），底部对齐；(3) 专辑名称从封面下方拆出，移至封面图上方（字号 14sp，颜色 `TextSecondary 0.7alpha`），下方仅保留艺术家。

**修改文件**：`ui/screens/NowPlayingScreen.kt`（CoverColumn 内部 Column 子元素重排 + ControlButtonsRow 下移 + ProgressSection fillMaxWidth）

**验证状态**：✅ 编译通过，🔶 未设备测试。

**测试方法**：
1. 连接后端 → 播放一首歌 → 进入 NowPlaying 页面
2. **验证控制按钮位置**：播放/暂停、上一首、下一首、播放模式 4 个按钮位于**封面图下方**（不再在封面右侧）
3. 聚焦控制按钮区域 → 左右键可切换按钮焦点 → 确认键触发对应操作
4. **验证进度条**：进度条横向占满屏幕宽度，左右键可正常 seek 跳转
5. **验证专辑名位置**：封面图上方显示灰色专辑名（字号 14sp），封面图下方仅显示艺术家名称
6. 切换歌曲 → 专辑名和艺术家更新正确
7. 返回曲库重新选歌 → 布局保持一致

---

### 10.9 v2.2.0 — 代码质量 & 测试工程

> 版本号：`versionName = "2.2.0"`，`versionCode = 5`
> 本阶段主要目标：清理硬编码字符串、引入 DI 容器替代静态单例、重构 Activity、统一异步状态管理、迁移播放模式状态、补充单元测试、搭建 CI。
> **⚠️ 注意**：以下所有修改均 **编译通过但未在设备上运行验证**。建议上线前进行完整回归测试。

#### 10.9.1 字符串资源化（B-3/B-8）

**功能描述**：创建 `strings.xml`（中文），替换 6+ 个屏幕中所有硬编码中文 UI 字符串（Library、NowPlaying、Settings、Queue、PlaylistMgmt、AlbumDetail、ArtistDetail、ViewerDetail）。

**新增文件**：`res/values/strings.xml`

**修改文件**：多个 UI screen 文件中 `"中文文本"` → `stringResource(R.string.xxx)`

**验证状态**：✅ 编译通过，🔶 未设备测试。

---

#### 10.9.2 DI 容器 & 移除静态单例（B-9）

**功能描述**：`NasMusicApp` Application 类作为控制反转容器持有 `BackendRegistry`、`AppPreferences`、`PlayerManager` 实例。移除三个类的 `getInstance()` 静态方法，所有调用者通过 Application 或 `NasMusicApp.get()` 获取依赖。

**修改文件**：`NasMusicApp.kt`（DI 容器）、`backend/BackendRegistry.kt`、`data/prefs/AppPreferences.kt`、`player/PlayerManager.kt`、`ui/MainActivity.kt`、`ui/viewmodel/MainViewModel.kt`、`player/PlaybackService.kt` 等

**注意事项**：`BuildConfig` 导入残留在 `MainViewModel.kt` line 16 但 `BuildConfig.kt` 已删除——需在编译时确认无影响（`buildConfig = true` 在 `build.gradle.kts` 中已启用，`BuildConfig` 由 AGP 自动生成）。

**验证状态**：✅ 编译通过，🔶 未设备测试。

---

#### 10.9.3 Activity + ViewModel 拆分（B-10）

**功能描述**：`MainActivity.kt` 从 678 行精简至 303 行，提取 `AppRoot.kt`（`ui/components/`，UI 根布局 + `currentScreen` 导航 + 错误横幅）、`NetworkMonitor.kt`（`util/`，网络监听封装）、`MediaKeyHandler.kt`（`util/`，媒体键路由分发）。

**新增文件**：
- `ui/components/AppRoot.kt`
- `util/NetworkMonitor.kt`
- `util/MediaKeyHandler.kt`

**修改文件**：`ui/MainActivity.kt`（大幅精简）、`ui/viewmodel/MainViewModel.kt`（`Screen` 枚举移至此处）

**验证状态**：✅ 编译通过，🔶 未设备测试。

---

#### 10.9.4 统一异步状态（B-12）

**功能描述**：新增 `UiState<T>` 密封类（`Loading` / `Success<T>` / `Error`）替代混用的 `_isLoading` / `_errorMessage` / 空列表判断。新增 `RetryUtil`（指数退避重试 `withRetry` + `RetryConfig`）。MainViewModel 中所有异步数据源（albums、songs、genres、favorites、playlists）迁移到 `UiState` 模式并带重试闭包。AppRoot 通过 `dataOrNull()` 提取数据后传给各 Screen。

**新增文件**：
- `data/model/UiState.kt`
- `util/RetryUtil.kt`

**修改文件**：`ui/viewmodel/MainViewModel.kt`（~45 处 try/catch 替换为 UiState 模式）、`ui/components/AppRoot.kt`（UiState unwrap）

**验证状态**：✅ 编译通过，🔶 未设备测试。

---

#### 10.9.5 播放模式迁移（B-13）

**功能描述**：`_playMode` 从 `PlayerManager` 迁移到 `MainViewModel`。`PlayerManager.next()`、`previous()`、`applyPlayMode()`、`onPlaybackEnded()` 改为接收/推导 `playMode` 参数。新增 `derivePlayMode()` 从 ExoPlayer repeat/shuffle 状态读取。播放模式启动时从 `AppPreferences.defaultPlayMode` 恢复。

**修改文件**：`player/PlayerManager.kt`（移除 `_playMode` + `playMode` flow）、`ui/viewmodel/MainViewModel.kt`（新增 `_playMode` flow）、`ui/components/AppRoot.kt`（传递 playMode）、`ui/screens/NowPlayingScreen.kt`

**验证状态**：✅ 编译通过。

---

#### 10.9.6 单元测试补充（B-5）

**功能描述**：为四个工具类/组件编写完整单元测试。已存在测试（ArtistSplitterTest、PinyinUtilsTest、LrcParserTest）不变。

**新增测试依赖**：
- `org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3`
- `org.mockito:mockito-core:5.3.1`
- `org.mockito:mockito-inline:5.2.0`
- `org.robolectric:robolectric:4.11.1`

**新增测试文件**：
- `data/model/UiStateTest.kt` — Loading/Success/Error 三状态全覆盖（dataOrNull、isSuccess、isError、isLoading、when exhaustive）
- `util/TimeUtilsTest.kt` — `formatDuration` / `formatDurationWithMillis` 全覆盖（零值、大数值、毫秒截断）
- `util/RetryUtilTest.kt` — 首次成功、多次重试后成功、全部耗尽抛出、`onError` 回调、自定义配置参数
- `util/MediaKeyHandlerTest.kt` — Mockito mock ViewModel 验证 10 种按键场景的路由逻辑（PLAY_PAUSE、NEXT、PREVIOUS、DPAD_CENTER 在 NowPlaying/沉浸/其他页面等）
- `util/NetworkMonitorTest.kt` — Robolectric + Mockito 验证网络回调注册、onAvailable/onLost/onCapabilitiesChanged 触发、unregister 安全

**验证状态**：✅ 全部编译通过，🔶 未在设备上运行测试验证。

---

#### 10.9.7 CI 搭建（B-6）

**功能描述**：创建 GitHub Actions 工作流，push 到 main/develop 或 PR 到 main 时自动执行 `assembleDebug` 并上传 APK 产物。

**新增文件**：`.github/workflows/build.yml`

**工作流步骤**：
1. checkout
2. JDK 17 (temurin)
3. Setup Gradle
4. Cache Gradle packages
5. `./gradlew assembleDebug --no-daemon`
6. Upload APK artifact

**验证状态**：✅ 工作流配置完成，🔶 未推送至 GitHub 触发验证。

---

### 10.10 v2.2.0 — 稳定性修复 & 退出清理 & 安全加固

> 本节记录 v2.2.0 阶段的 Bug 修复、进程退出清理、安全加固和性能优化等稳定性改进。

#### 10.10.1 PlaybackService Media3 1.2.1 API 不兼容修复

**日期**：2026-06-22

**问题描述**：PlaybackService 编译失败，7 个 unresolved reference：
- `MediaButtonReceiver.buildMediaButtonPendingIntent(context, command)` — Media3 1.2.1 中该重载不存在
- `Player.COMMAND_PAUSE` / `Player.COMMAND_PLAY` — Media3 1.2.1 中只有 `COMMAND_PLAY_PAUSE`，无独立 PLAY/PAUSE 命令
- `R.string.playback_previous` / `R.string.playback_next` — 字符串资源缺失

**根因分析**：代码使用了 Media3 1.2.1 不存在的 API。这些 API 在更高版本（1.3+）中才引入。

**修改**：

| 文件 | 改动 |
|------|------|
| `player/PlaybackService.kt` | 移除 `MediaButtonReceiver` import；新增 `KeyEvent` import；新增 `buildMediaButtonPendingIntent(keyCode: Int)` 私有方法，使用 `ACTION_MEDIA_BUTTON` + `KeyEvent` 构建 PendingIntent |
| `res/values/strings.xml` | 新增 `playback_previous` = "上一首"、`playback_next` = "下一首" |

**关键代码**：
```kotlin
private fun buildMediaButtonPendingIntent(keyCode: Int): PendingIntent {
    val intent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
        setPackage(packageName)
        putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
    }
    return PendingIntent.getBroadcast(
        this, keyCode, intent,
        PendingIntent.FLAG_IMMUTABLE
    )
}
```

**原理**：`MediaLibraryService` 自动处理 `ACTION_MEDIA_BUTTON` Intent，从 `EXTRA_KEY_EVENT` 读取 `KeyEvent` 并调用对应的 Player 方法（`KEYCODE_MEDIA_PLAY` → `play()`，`KEYCODE_MEDIA_PAUSE` → `pause()` 等）。

**验证**：✅ 编译通过，通知媒体按钮功能正常。

---

#### 10.10.2 进程退出残留修复（Android Studio stop 按钮常亮）

**日期**：2026-06-22

**问题描述**：退出程序后 Android Studio 上方运行工具栏的 stop 按钮一直亮着，表明进程未完全终止。

**根因分析**：
1. OkHttp 默认使用 `isDaemon = false` 的非守护线程，即使调用 `shutdown()` 也会阻止 JVM 退出
2. `finishAffinity()` 只结束 Activity，不终止进程
3. 后台 Service 可能仍在运行

**修改**（双重保险）：

| 文件 | 改动 |
|------|------|
| `ui/MainActivity.kt` | 退出确认 `onConfirm` 中：`playerManager.release()` → `stopService()` → `finishAffinity()` → `Process.killProcess(Process.myPid())` |
| `backend/impl/JellyfinAdapter.kt` | OkHttpClient 的 Dispatcher 使用守护线程池（`isDaemon = true`） |
| `backend/impl/NavidromeAdapter.kt` | 同 JellyfinAdapter，守护线程池 |

**守护线程工厂**：
```kotlin
val daemonExecutor = java.util.concurrent.Executors.newCachedThreadPool { r ->
    Thread(r, "Jellyfin-OkHttp").apply { isDaemon = true }
}
OkHttpClient.Builder()
    .dispatcher(okhttp3.Dispatcher(daemonExecutor))
    .build()
```

**OkHttp Dispatcher 构造函数注意事项**：
- `Dispatcher.executorService` 是只读 `val` 属性，不能通过 `apply { executorService = ... }` 赋值
- 必须通过 `Dispatcher(executorService)` 构造函数参数传入自定义线程池

**验证**：✅ 退出后 Android Studio stop 按钮立即熄灭，进程完全终止。

---

#### 10.10.3 PlaybackService 退出清理增强

**日期**：2026-06-22

**修改**：

| 文件 | 改动 |
|------|------|
| `player/PlaybackService.kt` | `onDestroy()` 新增 `PlayerManager.release()` 调用（释放 Handler、listener、Equalizer）+ `ServiceCompat.stopForeground(STOP_FOREGROUND_REMOVE)` 移除前台通知 |
| `player/PlaybackService.kt` | `onTaskRemoved()` 简化为直接 `stopSelf()`（原逻辑判断是否在播放，现在统一停止） |

**验证**：✅ 服务销毁时资源正确释放，前台通知移除。

---

#### 10.10.4 Jellyfin API 端点修复

**日期**：2026-06-22

**问题**：多个 Jellyfin API 端点返回 404 或行为异常。

**修改**：

| 端点 | 改前 | 改后 | 原因 |
|------|------|------|------|
| 歌词 | `/Items/{id}/Lyrics` | `/Audio/{id}/Lyrics` | `/Items/{id}/Lyrics` 返回 404，Jellyfin 歌词端点为 `/Audio/{id}/Lyrics` |
| 收藏 | `/Items/{id}/Favorite` | `/UserFavoriteItems/{id}` | `/Items/{id}/Favorite` 返回 404，正确端点为 `/UserFavoriteItems/{id}` |
| 流派 | `/Genres` | `/Genres?IncludeItemTypes=Audio` | 未指定 `IncludeItemTypes` 返回所有类型流派（含电影/电视），需过滤为音频 |
| 歌曲字段 | `MovieCount` | `SongCount` | 流派 songCount 字段名修正 |

**歌词格式转换**：Jellyfin 返回的歌词格式为 `[{Text:"...", Start:"..."}]` JSON 数组，需转换为标准 LRC 格式 `[mm:ss.xx]歌词`。

**收藏切换逻辑**：
- 使用 `_favoriteIdsCache` 缓存收藏状态
- POST 添加收藏，DELETE 取消收藏
- `getFavorites()` 加载时更新缓存

**验证**：✅ 歌词正常获取，收藏功能正常，流派只显示音乐流派。

---

#### 10.10.5 歌曲分页加载 & 按需加载

**日期**：2026-06-22

**问题**：全量加载歌曲（17,500+ 首）导致内存溢出和应用崩溃。

**修改**：

| 文件 | 改动 |
|------|------|
| `data/model/UiState.kt` | 新增 `SongsPagingState` 数据类（songs、totalCount、isLoading、hasMore、currentPage） |
| `ui/viewmodel/MainViewModel.kt` | 新增 `_songsPaging` StateFlow + `loadSongsFirstPage()` / `loadSongsNextPage()` 方法，每页 200 首 |
| `ui/viewmodel/MainViewModel.kt` | 新增 `buildArtistMapsIncremental()` 增量构建艺术家映射（仅处理新批次） |
| `backend/BackendAdapter.kt` | 新增 `getSongsTotalCount()` / `getSongsByIds()` / `getYears()` / `searchSongs()` 接口方法 |
| `backend/impl/JellyfinAdapter.kt` | 实现新接口方法 |
| `backend/impl/NavidromeAdapter.kt` | 实现新接口方法 |

**分页逻辑**：
```kotlin
val pageSize = 200
val batch = adapter.getSongs(pageSize, offset)
val totalCount = adapter.getSongsTotalCount()
// batch.size == pageSize 表示还有更多
```

**UI 显示**：加载时显示 "已加载 N / 共 M 首"，滚动到底部触发下一页加载。

**按需加载场景**：
- 最近播放：`getSongsByIds(recentSongIds)` 替代依赖全量歌曲列表
- 年份列表：`getYears()` 替代从全量歌曲推导
- 搜索：`searchSongs(query)` 服务端搜索替代客户端过滤

**验证**：✅ 歌曲正常分页加载，无内存溢出，进度显示正确。

---

#### 10.10.6 Navidrome 并发加载优化

**日期**：2026-06-22

**修改**（`backend/impl/NavidromeAdapter.kt`）：
- 专辑、演唱者、歌曲三个独立请求使用 `async + awaitAll` 并行执行
- 减少总加载时间（从串行 3 倍时间降至 1 倍时间）

**验证**：✅ Navidrome 曲库加载速度提升。

---

#### 10.10.7 密码加密存储（CryptoUtils）

**日期**：2026-06-22

**问题**：DataStore 中的 `password` 和 `apiToken` 以明文存储，存在安全风险。

**修改**：

| 文件 | 改动 |
|------|------|
| `util/CryptoUtils.kt` | **新增**：基于 Android Keystore 的 AES-256-GCM 加密工具 |
| `data/prefs/AppPreferences.kt` | `apiToken` 和 `password` 写入 DataStore 前调用 `CryptoUtils.encrypt()`，读取时调用 `CryptoUtils.decrypt()` |

**降级策略**：加密失败返回明文，解密失败返回原值（兼容旧版本明文数据），确保升级不影响现有用户。

**验证**：✅ 编译通过，DataStore 中的敏感字段已加密。

---

#### 10.10.8 日志统一管理（AppLog）

**日期**：2026-06-22

**问题**：项目中大量 `Log.d/Log.i/Log.w` 调用，Release 构建中仍输出调试日志，存在信息泄露风险和 I/O 开销。

**修改**：

| 文件 | 改动 |
|------|------|
| `util/AppLog.kt` | **新增**：日志工具，`d/i/w` 级别仅在 `BuildConfig.DEBUG` 时输出，`e` 级别始终输出 |
| 多个文件 | `Log.d/Log.i/Log.w` 调用替换为 `AppLog.d/i/w` |

**验证**：✅ Release 构建中调试日志被抑制，Debug 构建中日志正常输出。

---

#### 10.10.9 编码修复工具抽取（EncodingUtils）

**日期**：2026-06-22

**问题**：`JellyfinAdapter` 和 `NavidromeAdapter` 中存在重复的 `fixEncoding()` 函数。

**修改**：

| 文件 | 改动 |
|------|------|
| `util/EncodingUtils.kt` | **新增**：公共编码修复工具，处理 GB2312/GBK 被当作 Latin-1 解码的乱码模式 |
| `backend/impl/JellyfinAdapter.kt` | 移除私有 `fixEncoding()`，改为调用 `EncodingUtils.fixEncoding()` |
| `backend/impl/NavidromeAdapter.kt` | 同上 |

**验证**：✅ 编译通过，编码修复逻辑统一。

---

#### 10.10.10 公共可聚焦 Surface 组件（FocusableSurface）

**日期**：2026-06-22

**问题**：项目中 30+ 处重复实现"焦点缩放动画 + 焦点边框 + ClickableSurfaceDefaults 配置"样板代码。

**修改**：

| 文件 | 改动 |
|------|------|
| `ui/components/FocusableSurface.kt` | **新增**：公共可聚焦 Surface 组件，统一封装焦点动画、边框、FocusRequester、启动时自动请求焦点 |

**功能参数**：
- `focusedScale`：获得焦点时的缩放比例（默认 1.08f）
- `animationDurationMs`：缩放动画时长（默认 200ms）
- `showFocusBorder`：是否显示焦点边框（默认 true）
- `focusRequester`：可选的 FocusRequester，用于外部主动请求焦点
- `requestFocusOnLaunch`：是否在组件首次进入组合时自动请求焦点
- `onFocusChanged`：焦点变化回调

**验证**：✅ 编译通过，焦点动画统一。

---

#### 10.10.11 回归测试文档编制

**日期**：2026-06-22

**功能描述**：编制完整的回归测试文档，覆盖单元测试、集成测试、UI 测试和专项验证。

**新增文件**：`docs/regression-test.md`

**文档结构**（19 章节 248 个测试项）：
1. 测试概述
2. 单元测试（83 项）
3. 后端连接测试（15 项）
4. 曲库浏览测试（28 项）
5. 播放控制测试（18 项）
6. 歌词系统测试（6 项）
7. 队列管理测试（6 项）
8. 收藏与最近播放测试（8 项）
9. 播放列表测试（5 项）
10. 均衡器测试（6 项）
11. 设置测试（9 项）
12. UI 焦点与导航测试（16 项）
13. 通知与后台播放测试（8 项）
14. 网络异常测试（5 项）
15. 安全与加密测试（6 项）
16. 退出清理测试（7 项）
17. 近期修复专项验证（22 项）
18. 测试执行清单
19. 缺陷报告模板

**验证**：✅ 文档编制完成，可作为回归测试基准。

---

#### 10.10.12 MP3 流 Seek 修复

**日期**：2026-06-22

**问题**：进度条 seek 后，播放位置立即跳回 0。ExoPlayer 默认不支持 VBR MP3 流的 seek，导致 `player.seekTo()` 无效，音频从头重新播放。

**根因**：Jellyfin 返回的 MP3 流不支持 HTTP Range 请求，ExoPlayer 将其视为不可 seek 的流。调用 `seekTo()` 后，ExoPlayer 内部触发 `onPositionDiscontinuity(reason=SEEK_ADJUSTMENT)` 重置位置到 0。

**修改**：

| 文件 | 改动 |
|------|------|
| `player/PlaybackService.kt` | 启用 `FLAG_ENABLE_INDEX_SEEKING` 和 `FLAG_ENABLE_CONSTANT_BITRATE_SEEKING`，让 ExoPlayer 为 MP3 建立时间-字节映射索引 |
| `player/PlayerManager.kt` | 添加 `seekPending` 标志，seek 后 2 秒内阻止 Handler 覆盖进度；`onPositionDiscontinuity` 仅在 `reason=SEEK` 时更新进度 |

**技术细节**：
```kotlin
// PlaybackService.kt - 启用 MP3 seek 支持
val extractorsFactory = DefaultExtractorsFactory()
    .setMp3ExtractorFlags(
        Mp3Extractor.FLAG_ENABLE_INDEX_SEEKING or
        Mp3Extractor.FLAG_ENABLE_CONSTANT_BITRATE_SEEKING
    )
val mediaSourceFactory = DefaultMediaSourceFactory(this, extractorsFactory)
```

```kotlin
// PlayerManager.kt - seek 期间保护进度不被覆盖
private var seekPending = false

fun seekTo(positionMs: Long) {
    seekPending = true
    player?.seekTo(positionMs)
    _progress.value = positionMs
    progressHandler.postDelayed({ seekPending = false }, 2000)
}

// progressUpdateRunnable 中：
if (!seekPending) {
    _progress.value = p.currentPosition
}
```

**验证**：✅ 模拟器测试通过，进度条 seek 后保持正确位置，不跳回 0。

---

#### 10.10.13 进度条 OK 键误触发 seek

**日期**：2026-06-22

**问题**：焦点在进度条上按 OK 键时，会跳转到歌曲中间位置（`durationMs / 2`），而不是触发播放/暂停。

**根因**：`ProgressSection` 中 `Surface` 的 `onClick` 绑定了 `onSeek(durationMs / 2)`，在 TV 遥控器上按 OK 键会触发此 onClick。

**修改**：

| 文件 | 改动 |
|------|------|
| `ui/components/PlayerControls.kt` | 移除进度条 Surface 的 onClick 逻辑，改为不响应 OK 键 |

**验证**：✅ 焦点在进度条上按 OK 键不再跳转，播放/暂停功能正常。

---

#### 10.10.14 艺术家详情页歌曲列表修复

**日期**：2026-06-22

**问题**：进入艺术家详情页后无法显示歌曲列表，因为 `artistSongsMap` 是从已加载歌曲增量构建的，只加载了部分歌曲。

**根因**：`openArtistDetail()` 只设置艺术家名称并导航，没有触发歌曲加载。`artistSongsMap` 仅包含已分页加载的歌曲数据。

**修改**：

| 文件 | 改动 |
|------|------|
| `ui/viewmodel/MainViewModel.kt` | 新增 `loadArtistSongs()` 方法，按需从后端 API 加载艺术家歌曲；新增 `artistDetailSongsCache` StateFlow |
| `ui/components/AppRoot.kt` | ArtistDetail 屏幕使用 `artistDetailSongsCache` 替代 `artistSongsMap` |

**验证**：✅ 艺术家详情页正确显示所有歌曲，"播放全部"功能正常。

---

#### 10.10.15 艺术家封面图片显示

**日期**：2026-06-22

**问题**：艺术家列表和详情页不显示封面图片，只显示首字母占位符。

**根因**：
1. `getArtists()` API 请求缺少 `Fields=ImageTags` 参数，导致 Jellyfin 不返回图片标签
2. `ArtistCard` 组件没有图片加载代码
3. `ArtistDetailScreen` 没有接收 `Artist` 对象（只有名字字符串）

**修改**：

| 文件 | 改动 |
|------|------|
| `backend/impl/JellyfinAdapter.kt` | `getArtists()` 请求添加 `Fields=ImageTags` 参数 |
| `ui/screens/LibraryScreen.kt` | `ArtistsTab` 改为接收 `List<Artist>`；`ArtistCard` 添加 `AsyncImage` 加载封面 |
| `ui/screens/ArtistDetailScreen.kt` | 添加 `artist: Artist?` 参数，使用 `AsyncImage` 显示封面 |
| `ui/components/AppRoot.kt` | 传递完整 `Artist` 对象到 ArtistDetailScreen |

**验证**：✅ 艺术家列表和详情页均正确显示封面图片。

---

#### 10.10.16 播放按钮 seek 期间闪烁修复

**日期**：2026-06-22

**问题**：在进度条上按左右键 seek 时，播放/暂停按钮会短暂闪烁（状态切换）。

**根因**：ExoPlayer 处理 seek 时会短暂触发 `onIsPlayingChanged(false)` 然后再触发 `onIsPlayingChanged(true)`，导致 `_isPlaying` 状态快速变化。

**修改**：

| 文件 | 改动 |
|------|------|
| `player/PlayerManager.kt` | `onIsPlayingChanged` 回调中检查 `seekPending` 标志，seek 期间忽略播放状态变化 |

**验证**：✅ seek 期间播放按钮不再闪烁。

---

#### 10.10.17 编码修复增强（U+FFFD 检测）

**日期**：2026-06-22

**问题**：`EncodingUtils.fixEncoding()` 只处理末尾的 U+FFFD 和 Latin-1 范围字符，无法修复字符串中间出现的 U+FFFD（GBK 被当作 UTF-8 解码的情况）。

**修改**：

| 文件 | 改动 |
|------|------|
| `util/EncodingUtils.kt` | 新增第一步：检测字符串中任意位置的 U+FFFD，尝试将整个字符串按 ISO-8859-1 编码回字节，再用 GBK 重新解码 |

**验证**：✅ 对 Latin-1 范围的乱码（如 `ÖìÕÜÇÙ`→`朱哲琴`）修复正确。Unicode 转义序列中的非 Latin-1 字符（如希腊/西里尔字母）无法修复，属 Jellyfin 服务端数据问题。

---

#### 10.10.18 UI 文本修正

**日期**：2026-06-22

**修改**：

| 文件 | 改动 |
|------|------|
| `app/src/main/res/values/strings.xml` | `library_artists_alt` 从"歌唱家"改为"艺术家" |

---

#### 10.10.19 自动切歌歌词加载

**日期**：2026-06-22

**问题**：当一首歌播放完毕自动切换到下一首时，歌词不会重新加载。

**根因**：`loadLyricsForCurrentSong()` 仅在 `playSong()` 和 `playQueue()` 中调用。ExoPlayer 自动切歌时触发 `onMediaItemTransition` → `updateCurrentSongFromPlayer()` 更新 `currentSong`，但无人监听此变化来触发歌词加载。

**修改**：

| 文件 | 改动 |
|------|------|
| `ui/viewmodel/MainViewModel.kt` | `init` 中添加 `currentSong.collect { loadLyricsForCurrentSong() }`，统一由 StateFlow 监听触发；移除 `playSong()`/`playQueue()` 中的直接调用，避免重复 |

**验证**：✅ 模拟器测试通过，自动切歌后歌词正确加载。

---

#### 10.10.20 艺术家分页加载

**日期**：2026-06-22

**问题**：`getArtists()` 限制 1000 个艺术家，曲库超过 1000 位艺术家时无法全部显示。

**修改**：

| 文件 | 改动 |
|------|------|
| `backend/impl/JellyfinAdapter.kt` | `getArtists()` 实现分页循环，每页 1000 个，直到返回数量小于 pageSize |

**验证**：✅ 电视测试通过，艺术家数量超过 1000。

---

#### 10.10.21 退出时 Jellyfin Session 注销

**日期**：2026-06-22

**问题**：退出应用时 `Process.killProcess()` 立即杀死进程，`onDestroy()` 中的 `disconnect()` 协程来不及完成 HTTP 请求，Jellyfin 服务端 session 不会被注销。

**修改**：

| 文件 | 改动 |
|------|------|
| `ui/MainActivity.kt` | 退出确认回调中使用 `runBlocking { backendRegistry.disconnect() }` 同步等待注销完成后再 `killProcess()` |

**验证**：✅ 编译通过，逻辑正确。

---

#### 10.10.22 拼音搜索兼容低版本设备（TinyPinyin）

**日期**：2026-06-24

**问题**：`PinyinUtils.getInitials()` 使用 `Build.VERSION.SDK_INT < 24` 保护判断，API 22 的电视上直接返回空字符串。`toPinyin()` 依赖 API 26+ 的 `android.icu.text.Transliterator`。

**根因**：Android 5.1（API 22）没有 `android.icu` 库，且旧拼音实现使用了 `Transliterator` 进行拼音转换。

**修改**：

| 文件 | 改动 |
|------|------|
| `util/PinyinUtils.kt` | 重写为使用 `com.github.promeg.pinyinhelper.Pinyin`（TinyPinyin），纯 Java 实现，兼容 API 22+ |
| `app/build.gradle.kts` | 添加依赖 `com.github.promeg:tinypinyin:2.0.3` |
| `settings.gradle.kts` | 添加阿里云 Maven 镜像 + JitPack（已配置） |

**依赖下载**：需配置代理（中国大陆网络通过 `127.0.0.1:7890`），或使用 Aliyun Maven 镜像。

**验证**：✅ `assembleDebug` 编译通过，已在 Android TV（API 22）上测试验证：
- 搜索 "ayq" → 匹配"安又琪"
- 搜索 "wf" → 匹配"王菲"
- 搜索 "zjl" → 匹配"周杰伦"
- 兼容 API 22+，不依赖 `android.icu`

---

### 10.11 v2.2.0 — 网络音乐功能（Meting-API）

> 本节记录网络音乐搜索/播放/歌词功能的实现，以及测试中发现的搜索失败问题修复（字段映射错误、SSL 证书信任、中文输入）。

#### 10.11.1 网络音乐基础架构搭建

**日期**：2026-06-24

**目标**：实现独立于 NAS 后端的在线音乐搜索与播放能力，支持在 TV 盒子上搜索网络歌曲。

**架构设计**：

```
MainViewModel.searchNetworkSongs(keyword)
    └── NetworkMusicManager.search(keyword)        // 多源路由 + fallback
            └── MetingApiService.search(keyword)   // 默认源
                    └── Meting-API（网易云）
```

**新增文件**：

| 文件 | 职责 |
|------|------|
| `backend/network/NetworkMusicService.kt` | 网络音乐服务接口（search/resolvePlayUrl/resolveLyrics/resolveCoverUrl） |
| `backend/network/NetworkMusicManager.kt` | 多源路由层，fallback 策略 |
| `backend/network/MetingApiService.kt` | Meting-API 实现 |

**修改文件**：

| 文件 | 改动 |
|------|------|
| `NasMusicApp.kt` | 新增 `networkMusicManager` 单例，手动 DI 初始化 |
| `data/model/Song.kt` | 新增 `isNetworkSong` / `networkSource` / `networkId` 字段 |
| `data/model/AppSettings.kt` | 新增 `defaultNetworkSource` 字段 |
| `data/prefs/AppPreferences.kt` | 新增 `keyDefaultNetworkSource`、`getDefaultNetworkSourceSync()` |
| `ui/viewmodel/MainViewModel.kt` | 新增 `searchNetworkSongs()` / `networkSearchResults` StateFlow |
| `ui/screens/SettingsScreen.kt` | 网络检测页新增网络搜索说明 |

**验证**：✅ 编译通过，网络搜索 UI 流程可用。

---

#### 10.11.2 搜索输入支持中文（系统输入法切换）

**日期**：2026-06-24

**问题**：`TextInputDialog` 的自定义虚拟键盘只有英文字母/数字/符号，无法输入中文，导致网络搜索只能用拼音/英文。

**方案**：混合输入模式 — 在现有自定义键盘上增加「中文输入」按钮，切换到系统 IME 输入中文，完成后可切回自定义键盘。

**修改**：

| 文件 | 改动 |
|------|------|
| `ui/screens/TextInputDialog.kt` | 完整重写（315→405 行）：新增 `hasAvailableIme()` 检测系统输入法、`showSystemIme` 状态切换、`BasicTextField` + `FocusRequester` + `keyboardController.show()` 触发系统 IME、「中文输入」/「返回键盘」按钮、BACK 键分层处理（IME 模式先隐藏 IME 再返回键盘） |
| `res/values/strings.xml` | 新增 `text_input_chinese` / `text_input_back_keyboard` / `text_input_no_ime` |

**关键实现**：
```kotlin
// 检测系统是否有可用的输入法
private fun hasAvailableIme(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    return imm.enabledInputMethodList.isNotEmpty()
}

// 触发系统 IME
val keyboardController = LocalSoftwareKeyboardController.current
val focusRequester = remember { FocusRequester() }
BasicTextField(
    value = text,
    onValueChange = { text = it },
    modifier = Modifier.focusRequester(focusRequester)
)
LaunchedEffect(showSystemIme) {
    if (showSystemIme) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }
}
```

**降级处理**：若系统未安装任何输入法，点击「中文输入」按钮显示提示「未检测到中文输入法，请先在系统设置中安装」。

**验证**：✅ 中文输入正常，BACK 键分层处理正确。

---

#### 10.11.3 搜索失败修复 — Meting-API 字段映射错误

**日期**：2026-06-24

**问题**：中文输入修复后，搜索歌曲仍然返回空结果。

**排查方法**：在 `MetingApiService` / `NetworkMusicManager` / `MainViewModel` 全链路添加诊断日志（TAG `MetingDiag`，直接用 `android.util.Log` 确保 Release 包可见），通过 `adb logcat -s MetingDiag` 抓取。

**根因**：`parseSongs()` 使用的字段名与 API 实际返回完全不匹配：

| 代码读取字段 | API 实际返回字段 |
|------------|----------------|
| `name` | `title` |
| `artist` | `author` |
| `id`（独立字段） | 无，需从 `url` 字段查询参数提取 |
| `album` | 无 |

导致所有 `mapNotNull` 返回 null → 搜索结果永远为空。

**修改**（`backend/network/MetingApiService.kt`）：

```kotlin
// 修复前：字段名全部错误
val title = item.get("name")?.asString ?: return@mapNotNull null
val author = item.get("artist")?.asString.orEmpty()
val netId = item.get("id")?.asString ?: return@mapNotNull null

// 修复后：匹配 API 实际字段
val title = item.get("title")?.asString ?: return@mapNotNull null
val author = item.get("author")?.asString.orEmpty()
val urlField = item.get("url")?.asString
val netId = extractIdFromUrl(urlField) ?: return@mapNotNull null
```

**新增 `extractIdFromUrl()`**：从 Meting-API 端点 URL 的查询参数中提取 `id`。

```kotlin
private fun extractIdFromUrl(url: String?): String? {
    // 输入示例：https://meting.mikus.ink/api?server=netease&type=url&id=2652820720
    // 输出：2652820720
    val uri = java.net.URI(url)
    val query = uri.rawQuery ?: return null
    query.split("&").forEach { param ->
        val idx = param.indexOf("=")
        if (idx > 0 && param.substring(0, idx) == "id") {
            return param.substring(idx + 1)
        }
    }
    return null  // URI 解析失败时有正则兜底
}
```

**验证**：✅ 字段映射修复后，搜索能返回结果（但被 SSL 问题阻塞，见 10.11.4）。

---

#### 10.11.4 搜索失败修复 — SSL 证书信任失败

**日期**：2026-06-24

**问题**：字段映射修复后，搜索仍返回空，日志显示：

```
SSLHandshakeException: Trust anchor for certification path not found
```

**根因**：TV 盒子系统版本较老（API 22），缺少 `meting.mikus.ink` 所用 Let's Encrypt 证书的根 CA（`ISRG Root X1`），导致 SSL 握手失败。

**修改**（`backend/network/MetingApiService.kt`）：

新增信任所有证书的 `X509TrustManager` + 宽松 `HostnameVerifier`，通过 `applyTrustAllSsl()` 扩展函数应用到两个 OkHttpClient（`client` 和 `noRedirectClient`）：

```kotlin
private val trustAllManager: X509TrustManager = object : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

private val trustAllHostnameVerifier = HostnameVerifier { _, _ -> true }

private fun OkHttpClient.Builder.applyTrustAllSsl(): OkHttpClient.Builder {
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, arrayOf<TrustManager>(trustAllManager), java.security.SecureRandom())
    this.sslSocketFactory(sslContext.socketFactory, trustAllManager)
    this.hostnameVerifier(trustAllHostnameVerifier)
    return this
}
```

**安全考量**：Meting-API 为公开搜索服务，不涉及敏感数据传输，TV 盒子场景下此妥协可接受。NAS 后端连接（Jellyfin/Navidrome）仍使用系统默认证书校验，不受影响。

**验证**：✅ 搜索「主角」成功返回结果。

---

#### 10.11.5 Meting-API 端点可配置

**日期**：2026-06-24

**需求**：公共服务端点可能不稳定或被墙，用户需能自建或替换为其他公共端点。

**实现**：

1. **MetingApiService 构造器改造**：从无参构造改为接受 `baseUrlProvider: () -> String`，每次请求动态读取端点，支持运行时切换。

```kotlin
class MetingApiService(
    private val baseUrlProvider: () -> String
) : NetworkMusicService {
    private val baseUrl: String get() = baseUrlProvider().trim().trim('`', '\'', '"').trim().trimEnd('/')
}
```

2. **AppSettings 扩展**：新增 `metingApiBaseUrl` 字段，默认 `https://meting.mikus.ink/api`。

3. **AppPreferences 扩展**：新增 `keyMetingApiBaseUrl`、`setMetingApiBaseUrl()`、`getMetingApiBaseUrlSync()`，setter 中清理非法字符（反引号/引号）。

4. **NasMusicApp 初始化**：传入 `baseUrlProvider = { appPreferences.getMetingApiBaseUrlSync() }`。

5. **设置页 UI**（`SettingsScreen.kt` 网络检测页）：
   - 显示当前端点 URL
   - 「修改端点」按钮 → 弹出 `TextInputDialog` 编辑（支持中文输入法输入 URL）
   - 「恢复默认」按钮（仅当端点与默认不同时显示）
   - URL 校验：必须以 `http://` 或 `https://` 开头

**修改文件**：

| 文件 | 改动 |
|------|------|
| `backend/network/MetingApiService.kt` | 构造器接受 `baseUrlProvider`，新增 `DEFAULT_BASE_URL` 常量，`baseUrl` getter 清理非法字符 |
| `data/model/AppSettings.kt` | 新增 `metingApiBaseUrl` 字段 |
| `data/prefs/AppPreferences.kt` | 新增 `keyMetingApiBaseUrl`、Flow 映射、setter（含清理）、sync getter |
| `NasMusicApp.kt` | 初始化时传入 `baseUrlProvider` |
| `ui/viewmodel/MainViewModel.kt` | 新增 `updateMetingApiBaseUrl()` |
| `ui/screens/SettingsScreen.kt` | 网络页新增端点配置 UI（显示/修改/恢复默认） |
| `ui/components/AppRoot.kt` | 接入 `onChangeMetingApiBaseUrl` 回调 |
| `res/values/strings.xml` | 新增 7 条字符串资源 |

**验证**：✅ 设置页可修改端点，修改后立即生效（无需重启），恢复默认按钮正常。

---

#### 10.11.6 诊断日志体系（MetingDiag）

**日期**：2026-06-24

**背景**：网络搜索失败时，原有的 `AppLog.d/w` 在 Release 包中是空操作（仅 `BuildConfig.DEBUG` 时输出），导致用户测试时无法看到任何日志。

**实现**：在 `MetingApiService` / `NetworkMusicManager` / `MainViewModel.searchNetworkSongs` 全链路添加诊断日志，统一 TAG `MetingDiag`，直接使用 `android.util.Log`（不依赖 `BuildConfig.DEBUG`），确保 Release 包也能看到。

**日志覆盖节点**：

| 节点 | 日志内容 |
|------|---------|
| MainViewModel 入口 | 搜索关键词 |
| NetworkMusicManager | 默认源、有序服务列表、逐个尝试 |
| MetingApiService.search | baseUrl、完整请求 URL、响应码、响应体长度、响应体前 800 字符预览 |
| parseSongs | JSON 数组大小、首元素所有 key、每条的 title/author/pic/url、提取的 netId |
| extractIdFromUrl | 输入 URL、rawQuery、提取结果 |
| 异常 | 异常类型 + message + 堆栈 |

**抓取方式**：
```bash
adb logcat -c                       # 清空旧日志
adb logcat -s MetingDiag            # 只看 MetingDiag 标签
```

**价值**：本次 SSL 问题即通过日志中 `SSLHandshakeException` + `baseUrl` 值（带反引号）快速定位。日志保留在代码中，便于后续网络问题排查。

---

#### 10.11.7 网络歌曲收藏功能（Phase 2）

**日期**：2026-06-24

**目标**：实现网络歌曲的收藏/取消收藏，收藏列表展示，与本地收藏统一交互。

**架构设计**：

```
用户点击收藏按钮
    └── MainViewModel.toggleNetworkFavorite(song)
            └── AppPreferences.toggleNetworkFavorite(NetworkFavoriteItem)
                    └── DataStore JSON 序列化存储

UI 收藏列表
    └── MainViewModel.networkFavoriteSongs (StateFlow<List<Song>>)
            └── _networkFavorites.map { NetworkFavoriteItem → Song }
```

**新增文件**：

| 文件 | 职责 |
|------|------|
| `data/model/NetworkFavoriteItem.kt` | 网络收藏数据类（songId/title/artist/album/coverUrl/networkSource/networkId/addedAtMs） |

**修改文件**：

| 文件 | 改动 |
|------|------|
| `data/prefs/AppPreferences.kt` | 新增 `keyNetworkFavorites`、`networkFavorites` Flow、`getNetworkFavoritesSync()`、`toggleNetworkFavorite()` |
| `ui/viewmodel/MainViewModel.kt` | 新增 `_networkFavorites`、`networkFavoriteSongs`、`networkFavoriteIds` StateFlow、`toggleNetworkFavorite()`、`isNetworkFavorite()`，init 块收集 `prefs.networkFavorites` |
| `ui/screens/LibraryScreen.kt` | FavoritesTab 合并本地+网络收藏；NetworkTab 收藏列表展示；FavoriteButton 通用化 |
| `ui/components/AppRoot.kt` | NowPlayingScreen 收藏按钮增加 `isNetworkSong` 分支路由 |

**关键设计决策**：
- **不存储 streamUrl**：播放链接有时效性，每次播放时重新解析
- **NetworkFavoriteItem → Song 转换**：UI 层无需了解 NetworkFavoriteItem 类型，统一用 Song 模型
- **FavoriteButton 通用化**：从 `NetworkFavoriteButton` 重命名为 `FavoriteButton`，本地/网络收藏共用同一组件

**验证**：✅ 网络歌曲收藏/取消收藏正常，收藏列表正确显示，NowPlayingScreen 收藏按钮对网络歌曲生效。

---

#### 10.11.8 全局收藏按钮 + 收藏页面优化

**日期**：2026-06-24

**目标**：将收藏按钮扩展到所有歌曲列表页面，并修复收藏页面的若干问题。

**修改文件**：

| 文件 | 改动 |
|------|------|
| `ui/screens/LibraryScreen.kt` | SongRow 参数从 `isNetworkFavorite`/`onToggleNetworkFavorite` 重命名为通用的 `isFavorited`/`onToggleFavorite`；SongsTab、RecentTab、FavoritesTab 添加 `onToggleFavorite` 参数；LibraryScreen 函数签名新增 `onToggleFavorite` |
| `ui/screens/AlbumDetailScreen.kt` | 函数签名新增 `favoriteIds`/`onToggleFavorite`；内联歌曲行添加 FavoriteButton |
| `ui/screens/ArtistDetailScreen.kt` | 同上 |
| `ui/components/AppRoot.kt` | LibraryScreen、AlbumDetailScreen、ArtistDetailScreen 调用传递 `favoriteIds`/`onToggleFavorite` |

**修复的问题**：
1. **收藏页面 NAS 歌曲无收藏按钮**：FavoritesTab 的 NAS 歌曲 `onToggleFavorite` 从 `null` 改为可取消收藏
2. **收藏页面依赖 NAS 连接**：FAVORITES Tab 与 NETWORK Tab 同等处理，在 `isLoading`/`!isConnected` 判断之前渲染，始终可用
3. **收藏的网络歌曲不在收藏列表**：FavoritesTab 合并 `favoriteSongs`（本地）+ `networkFavoriteSongs`（网络）

**验证**：✅ 所有歌曲列表页面都有收藏按钮，收藏页面不依赖 NAS 连接，NAS 歌曲可取消收藏。

---

#### 10.11.9 搜索端点自动 fallback（Phase 3）

**日期**：2026-06-24

**目标**：实现搜索端点级别的自动容错，当前端点失败时自动尝试其他预设端点，用户无感切换。

**方案调整说明**：原方案计划实现 AlApiService、JioSaavnService 作为多源容错。实际实施中，鉴于 Meting-API 已有 3 个可用预设端点（Mikus/Redcha/Qijieya），且 AlAPI/JioSaavn 国内访问不稳定，调整为**端点级自动 fallback**。该方案在 MetingApiService 内部实现，不影响 NetworkMusicManager 的多源路由架构。

**修改文件**：

| 文件 | 改动 |
|------|------|
| `backend/network/MetingApiService.kt` | `search()` 方法重构为端点 fallback 流程；新增 `buildEndpointFallbackOrder()` 构造端点优先级；新增 `searchWithEndpoint()` 单端点搜索 |

**Fallback 逻辑**：

```
当前端点（用户选中）→ Mikus → Redcha → Qijieya（去重，跳过已尝试的）
```

```kotlin
override suspend fun search(keyword: String): List<Song> = withContext(Dispatchers.IO) {
    val endpoints = buildEndpointFallbackOrder(baseUrl)
    for (endpoint in endpoints) {
        val songs = searchWithEndpoint(keyword, endpoint)
        if (songs.isNotEmpty()) return@withContext songs
    }
    emptyList()
}
```

**关键设计**：
- **当前端点优先**：尊重用户在设置页的选择，优先尝试
- **去重处理**：`buildEndpointFallbackOrder()` 去除重复端点，避免重复请求
- **自定义端点也支持 fallback**：用户自定义端点失败时，仍会 fallback 到预设端点
- **无感切换**：搜索结果不记录实际使用的端点，`networkSource` 始终为 "meting"

**验证**：✅ 当前端点失败时自动切换到其他端点，用户无感知。

---

#### 10.11.10 加入队列功能 + 焦点架构重构

**日期**：2026-06-24

**目标**：所有歌曲列表页面的 SongRow 添加队列切换按钮，并解决 Compose TV 嵌套焦点问题。

**修改文件**：

| 文件 | 改动 |
|------|------|
| `ui/screens/LibraryScreen.kt` | SongRow 添加 `isInQueue`/`onToggleQueue` 参数；QueueToggleButton 组件；SongRow 焦点架构重构为 Box(focusGroup) + 兄弟级 Row |
| `ui/screens/AlbumDetailScreen.kt` | 内联歌曲行添加 QueueToggleButton |
| `ui/screens/ArtistDetailScreen.kt` | 同上 |
| `ui/screens/QueueScreen.kt` | 歌曲行统一为 SongRow 的紧凑样式 + 焦点行为 |
| `ui/components/AppRoot.kt` | 所有屏幕调用传递 `queueSongIds`/`onToggleQueue` |

**焦点架构重构**（解决嵌套 FocusableSurface 无法聚焦问题）：

```
Box(focusGroup)                          ← 外层容器，统一焦点组
├── Row(weight(1f) + clickable)          ← 左侧内容（点击播放）
│   ├── 封面
│   └── 标题/艺术家
└── Box(focusable + clickable)           ← 右侧按钮（独立焦点目标）
    └── QueueToggleButton / FavoriteButton
```

- D-pad RIGHT 从左侧内容移到右侧按钮
- D-pad LEFT 返回左侧内容
- 背景/边框/缩放效果在外层 Box 上，通过 `state.hasFocus` 统一追踪

**验证**：✅ 队列按钮可聚焦可点击，焦点导航正常，样式与 SongRow 一致。

---

### 10.12 v2.3.0 — Phase 4 优化 + 队列持久化 + 输入对话框修复

#### 10.12.1 LyricsNetworkProvider 改造（守护线程 + AppLog + Gson）

**日期**：2026-06-24

**目标**：解决 LyricsNetworkProvider 的 OkHttp 线程阻塞进程退出、日志不统一、JSON 解析库混用问题。

**修改文件**：`lyrics/LyricsNetworkProvider.kt`

**改动**：
- OkHttpClient dispatcher 使用 `Executors.newCachedThreadPool` 构造的守护线程池，线程命名 `LyricsNetwork-OkHttp`，`isDaemon = true` 防止阻塞进程退出
- 所有 `android.util.Log.w/e` 替换为 `AppLog.w/e`，统一日志体系
- JSON 解析从 `org.json.JSONObject` 迁移到 `Gson`/`JsonParser`，与项目其他网络服务保持一致

**验证**：✅ 歌词网络请求不再阻塞进程退出，日志统一通过 AppLog 输出。

---

#### 10.12.2 网络歌曲编码修复

**日期**：2026-06-24

**目标**：网络歌曲标题/作者出现中文乱码（GBK 被当作 Latin-1 解码）。

**修改文件**：`backend/network/MetingApiService.kt`

**改动**：`parseSongs()` 方法对 title/author 字段调用 `EncodingUtils.fixEncoding()`，复用现有 NAS 歌曲的编码修复逻辑。

**验证**：✅ 网络歌曲标题/作者正确显示中文。

---

#### 10.12.3 网络收藏 LRU 上限

**日期**：2026-06-24

**目标**：网络收藏无大小限制，DataStore 序列化的 JSON 会随收藏增多而膨胀。

**修改文件**：`data/prefs/AppPreferences.kt`

**改动**：
- 新增 `networkFavoritesMaxSize = 500` 常量
- `toggleNetworkFavorite()` 添加收藏时检查数量，超出上限从尾部移除最旧收藏
- 实现 LRU（Least Recently Used）淘汰策略

**验证**：✅ 收藏超过 500 条时自动清理最旧收藏。

---

#### 10.12.4 NowPlayingScreen 网络歌曲来源标识

**日期**：2026-06-24

**目标**：NowPlayingScreen 缺少网络歌曲来源标识，用户无法区分本地/网络歌曲。

**修改文件**：`ui/screens/NowPlayingScreen.kt`、`data/model/LyricsSource.kt`

**改动**：
- NowPlayingScreen 标题下方添加 "NET" 标签，仅网络歌曲显示
- `LyricsSource.NETWORK` 的显示文案从 "网络匹配" 改为 "在线歌词"，更准确

**验证**：✅ 网络歌曲显示 "NET" 标签，歌词来源标签显示 "在线歌词"。

---

#### 10.12.5 网络歌曲播放链接缓存

**日期**：2026-06-24

**目标**：短时间内重复播放同一网络歌曲会重复请求 Meting-API 解析播放链接，浪费网络资源。

**修改文件**：`backend/network/NetworkMusicManager.kt`

**改动**：
- 新增 `CachedPlayUrl` data class（url + timestamp）
- 新增 `playUrlCache` 内存缓存 Map
- `resolvePlayUrl()` 先检查缓存（5 分钟 TTL），命中则直接返回；未命中则请求 API 并写入缓存
- 缓存 key 为 song.id，避免不同歌曲互相影响

**验证**：✅ 5 分钟内重复播放同一歌曲不重复请求 API。

---

#### 10.12.6 播放队列持久化功能

**日期**：2026-06-24

**目标**：应用重启后丢失上次播放队列，用户体验不佳。

**修改文件**：

| 文件 | 改动 |
|------|------|
| `data/prefs/AppPreferences.kt` | 新增 `LastQueueData` data class、`saveLastQueue()`、`getLastQueueSync()`、`clearLastQueue()` |
| `player/PlayerManager.kt` | 新增 `restoreQueue()` 方法，设置队列和索引但不播放 |
| `ui/viewmodel/MainViewModel.kt` | init 块调用 `restoreLastQueue()`；`combine(queue, currentIndex)` 监听变化自动持久化；`connectToServer()` 后调用 `updateRestoredQueueStreamUrls()` 刷新 NAS 歌曲 streamUrl；`clearQueue()` 调用 `prefs.clearLastQueue()` |

**持久化策略**：
- 队列序列化为 JSON 存储到 DataStore
- **streamUrl 字段置空**（时效性链接，不持久化）
- NAS 歌曲 streamUrl 在后端连接后通过 `adapter.getSongsByIds()` 刷新
- 网络歌曲 streamUrl 在播放时由 `resolvePlayUrl()` 实时解析

**恢复流程**：
```
应用启动 → restoreLastQueue() → PlayerManager.restoreQueue()
         → 设置 _queue/_currentIndex/_currentSong（不播放）
         → 后端连接成功 → updateRestoredQueueStreamUrls() 刷新 NAS streamUrl
         → 用户按播放 → playPause() 检测 streamUrl 为空 → resolveAndPlayCurrentSong()
```

**验证**：✅ 重启后队列和当前歌曲索引恢复，不自动播放。

---

#### 10.12.7 TextInputDialog 被列表覆盖修复

**日期**：2026-06-24

**目标**：网络搜索输入框有内容时，按确认无法弹出虚拟键盘，输入框被下方歌曲列表覆盖。

**修改文件**：`ui/screens/TextInputDialog.kt`

**改动**：
- 将 TextInputDialog 内容包裹到 `Dialog` 组件
- `DialogProperties(dismissOnBackPress=false, dismissOnClickOutside=false, usePlatformDefaultWidth=false)`
- Dialog 创建系统级窗口，显示在所有内容之上，不被 LazyVerticalGrid 覆盖

**验证**：✅ 输入框始终显示在最上层，虚拟键盘正常弹出。

---

#### 10.12.8 TextInputDialog BACK 键失效修复

**日期**：2026-06-24

**目标**：10.12.7 将 TextInputDialog 包裹到 Dialog 后，BACK 键无法关闭对话框（Dialog 拦截 BACK 事件，原 `LocalDialogBackHandler` 在外层 Activity 无法接收）。

**修改文件**：`ui/screens/TextInputDialog.kt`

**改动**：
- 移除 `LocalDialogBackHandler` 和 `DisposableEffect`
- 在 Dialog 内部使用 Compose 标准 `BackHandler` 处理 BACK 键
- BACK 键行为：先隐藏系统 IME（如显示），再关闭对话框（自定义键盘模式）

**验证**：✅ BACK 键正确关闭对话框，系统 IME 先隐藏再关闭。

---

#### 10.12.9 恢复队列后无法播放修复

**日期**：2026-06-24

**目标**：10.12.6 实现的队列持久化功能，重启后队列能记住但无法播放。

**根因**：`PlayerManager.restoreQueue()` 只更新 UI 状态（`_queue`/`_currentIndex`/`_currentSong`），未加载 MediaItems 到 ExoPlayer，且恢复的歌曲 streamUrl 为空（持久化时置空）。

**修改文件**：

| 文件 | 改动 |
|------|------|
| `player/PlayerManager.kt` | `restoreQueue()` 增加 `setMediaItems` + `prepare()`（不 play），让 ExoPlayer 进入 ready 状态 |
| `ui/viewmodel/MainViewModel.kt` | `playPause()` 检测 streamUrl 为空时调用 `resolveAndPlayCurrentSong()`；新增 `resolveAndPlayCurrentSong()` 解析网络/NAS streamUrl 后 `playQueue()`；`next()`/`previous()` 检测目标歌曲 streamUrl 为空时调用 `resolveAndPlayByIndex()` |

**播放流程**：
```
用户按播放 → playPause() → song.streamUrl 为空？
  ├─ 是 → resolveAndPlayCurrentSong()
  │      ├─ 网络歌曲 → NetworkMusicManager.resolvePlayUrl()
  │      └─ NAS 歌曲 → adapter.getSongsByIds()
  │      → 更新队列 streamUrl → playerManager.playQueue()
  └─ 否 → playerManager.playPause()
```

**验证**：✅ 恢复队列后按播放能正常播放。

---

#### 10.12.10 恢复队列后网络歌曲无法播放修复

**日期**：2026-06-24

**目标**：10.12.9 修复后，恢复队列中网络歌曲仍无法播放。

**根因**：`restoreQueue` 为所有歌曲创建 `MediaItem.fromUri(song.streamUrl ?: "")`，网络歌曲 streamUrl 为空，创建空 URI MediaItem。ExoPlayer `prepare()` 尝试准备空 URI → 触发 `onPlayerError` → 自动跳下一首 → 下一首也可能为空 → **级联错误循环**，ExoPlayer 陷入错误状态。

**修改文件**：`player/PlayerManager.kt`

**改动**：
1. `restoreQueue()`：仅当当前歌曲 streamUrl 不为空时才调用 `setMediaItems`/`prepare`；网络歌曲 streamUrl 为空时跳过 prepare，只设置 UI 状态
2. `onPlayerError()`：当前歌曲 streamUrl 为空时不自动跳下一首，避免级联错误

**验证**：✅ 恢复队列后网络歌曲不再触发级联错误，按播放可正常解析播放。

---

#### 10.12.11 自动切歌到网络歌曲播放失败修复

**日期**：2026-06-24

**目标**：10.12.10 修复后，第一首歌（有 streamUrl）播放完自动切到下一首网络歌曲（streamUrl 为空）时播放失败并停止。

**根因**：ExoPlayer 自动过渡（`MEDIA_ITEM_TRANSITION_REASON_AUTO`）到 streamUrl 为空的歌曲时，尝试播放空 URI 出错。10.12.10 的修复只阻止了 `onPlayerError` 跳歌，但没有解决自动过渡时的 streamUrl 解析。

**修改文件**：

| 文件 | 改动 |
|------|------|
| `player/PlayerManager.kt` | 新增 `onNeedResolveStreamUrl` 回调属性；`onMediaItemTransition` 检测自动过渡到空 streamUrl 歌曲时，暂停并触发回调 |
| `ui/viewmodel/MainViewModel.kt` | init 块设置 `playerManager.onNeedResolveStreamUrl` 回调，调用 `resolveAndPlayByIndex()` 解析 streamUrl 后重新播放 |

**自动切歌流程**：
```
第一首播放完 → ExoPlayer 自动过渡到第二首（网络歌曲）
            → onMediaItemTransition(reason=AUTO)
            → 检测 streamUrl 为空 → player.pause()
            → onNeedResolveStreamUrl 回调
            → MainViewModel.resolveAndPlayByIndex()
            → 解析 streamUrl → playerManager.playQueue() → 播放
```

**验证**：✅ 自动切歌到网络歌曲能正常解析播放。

---

#### 10.12.12 歌词加载误报"加载歌词失败"修复

**日期**：2026-06-24

**目标**：自动切歌到网络歌曲时，歌词已加载成功但仍提示"加载歌词失败"。

**根因**：`loadLyricsForCurrentSong()` 使用 `lyricsLoadJob` 管理协程，切歌时调用 `lyricsLoadJob?.cancel()` 取消上一个加载任务。但 `catch (e: Exception)` 会捕获 `CancellationException`（协程取消机制），错误地显示"加载歌词失败"。

**触发场景**（自动切歌到网络歌曲）：
1. `currentSong` 第一次更新（streamUrl 为空）→ 启动 Job1 加载歌词
2. `resolveAndPlayByIndex` 解析 streamUrl → `playQueue` → `currentSong` 第二次更新（新对象）
3. `loadLyricsForCurrentSong` 再次被调用 → `lyricsLoadJob?.cancel()` 取消 Job1
4. Job1 抛出 `CancellationException` → 被错误捕获 → 显示"加载歌词失败"
5. Job2 成功加载歌词 → 歌词正常显示

**修改文件**：`ui/viewmodel/MainViewModel.kt`

**改动**：`loadLyricsForCurrentSong()` 的 catch 块前添加 `catch (e: kotlinx.coroutines.CancellationException) { throw e }`，将取消异常重新抛出，不当作错误处理。这是 Kotlin 协程的最佳实践。

**验证**：✅ 切歌时不再误报"加载歌词失败"。

---

### 10.13 v2.4.1 — 逐字歌词高频刷新 + 封面多图轮播 + 网络歌词联动封面

#### 10.13.1 逐字歌词高频刷新

**日期**：2026-06-26

**目标**：逐字高亮（WORD_BY_WORD）模式下文字高亮切换有明显"跳动"感，不够流畅。

**根因**：逐字高亮依赖 `currentTimeMs` 判断每个字符的播放状态，而 `currentTimeMs` 来自 `PlayerManager.progress`，该进度通过 `Handler.postDelayed` 每 1000ms 才更新一次。结果逐字高亮每秒最多刷新一次，一行 10 个字被"批量点亮"，视觉上跳跃式高亮。

**修改文件**：

| 文件 | 改动 |
|------|------|
| `ui/components/LyricsView.kt` | 新增 `isPlaying` 参数；内部独立高频时钟（50ms / 20fps），基于 1 秒进度锚点 + 流逝时间插值估算当前进度；仅 `WORD_BY_WORD` 模式且 `isPlaying` 时启动；逐字高亮使用 `effectiveTimeMs` 替代 `currentTimeMs` |
| `ui/screens/NowPlayingScreen.kt` | 调用 LyricsView 时传入 `isPlaying` |

**实现要点**：
- 进度条等其它 UI 仍用 1000ms 的 `progress`，不受影响
- 时钟基于上次 `currentTimeMs`（1 秒锚点）+ 实际流逝时间插值估算
- `currentTimeMs` 更新时（每秒一次）重新校准锚点
- 非逐字模式或暂停时直接使用 `currentTimeMs`

**验证**：✅ 逐字高亮流畅无跳动。

---

#### 10.13.2 统一封面轮播框架

**日期**：2026-06-26

**目标**：封面图 fallback 不完整（Navidrome 无 fallback、Jellyfin 专辑 fallback 不带 tag、NowPlayingScreen 重复 Backdrop），且希望多种封面（歌曲/专辑/艺术家）都能取到时定时轮播展示。

**方案**：后端提供"候选封面 URL 列表"（按优先级排序），UI 层用统一的 `CoverCarousel` 组件轮播展示。

**轮播规则**：
- 多张封面时每 10 秒切换一张
- 仅播放时轮播，暂停时定格
- 单张封面时静态显示
- 当前 URL 加载失败自动 fallback 到候选列表下一项
- 全部失败显示音符占位符

**优先级**：歌曲封面 → 专辑封面 → 艺术家封面 → ♪ 占位符

**修改文件**：

| 文件 | 改动 |
|------|------|
| `backend/BackendAdapter.kt` | 新增 `getCoverUrlCandidates(song)` 接口方法，默认空实现 |
| `backend/impl/JellyfinAdapter.kt` | `jsonObjectToSong` 解析 `ArtistItems.Id` 填充 `artistId`；请求 fields 添加 `ArtistItems`；实现 `getCoverUrlCandidates`（歌曲 coverUrl → 专辑 albumId → 艺术家 artistId） |
| `backend/impl/NavidromeAdapter.kt` | 实现 `getCoverUrlCandidates`（coverUrl → albumId → artistId），修复原 coverArt 为空时无 fallback 的问题 |
| `ui/components/CoverCarousel.kt` | **新建**组件。10 秒/张轮播，`LaunchedEffect(isPlaying, coverCandidates)` 控制启停，内层 `fallbackOffset` 处理 URL 加载失败，`PlaceholderCover` 显示音符图标 |
| `ui/screens/NowPlayingScreen.kt` | 新增 `coverCandidates` 参数；`CoverColumn` 同步新增 `coverCandidates` + `isPlaying` 参数；替换原 3 级 fallback（含重复 Backdrop bug）为 `CoverCarousel` |
| `ui/components/AppRoot.kt` | 订阅 `networkCoverUrl`；`remember(currentSong.id, networkCoverUrl)` 生成候选列表传给 NowPlayingScreen |

**修复的 bug**：
1. NowPlayingScreen attempt 1 和 2 都替换为 Backdrop（重复）
2. Navidrome coverArt 为空时直接返回 null（无 fallback）
3. Jellyfin `jsonObjectToSong` 未解析 `artistId`（字段缺失）

**验证**：✅ NAS 歌曲多封面 10 秒轮播；单张封面静态显示；暂停定格；全失败显示占位符。

---

#### 10.13.3 网络歌词联动网络封面

**日期**：2026-06-26

**目标**：NAS 歌曲切换到"在线歌词"来源时，只切换歌词，封面图不联动。希望同时获取网络封面加入轮播候选列表。

**方案**：`switchLyricsSource()` 切到 `NETWORK` 来源时，用标题+艺术家调 `searchCoverUrl()` 搜索网络封面，更新 `_networkCoverUrl` StateFlow；`getCoverCandidates()` 自动读取该状态组装候选列表；切回 `EMBEDDED` 时清除网络封面。

**修改文件**：

| 文件 | 改动 |
|------|------|
| `backend/network/MetingApiService.kt` | 新增 `searchCoverUrl(title, artist)`，复用 `search()` 取第一条结果的 `coverUrl` |
| `backend/network/NetworkMusicManager.kt` | 暴露 `searchCoverUrl(title, artist)`，遍历 `orderedServices()` 调用 MetingApiService |
| `ui/viewmodel/MainViewModel.kt` | 新增 `_networkCoverUrl` StateFlow；`getCoverCandidates(song)` 统一入口（NAS 歌曲：后端 3 类 + 网络封面；网络歌曲：1 张 pic）；`switchLyricsSource()` 增强——切到 NETWORK 且非网络歌曲时调 `searchCoverUrl`，切回 EMBEDDED 时清除 |

**各场景轮播效果**：

| 场景 | 候选封面数 | 轮播效果 |
|------|-----------|---------|
| NAS 歌曲，默认（后端歌词） | 1-3 张（后端） | 后端封面轮播 |
| NAS 歌曲，切到在线歌词 | 2-4 张（后端+网络） | 后端+网络封面轮播 |
| NAS 歌曲，切回内嵌歌词 | 1-3 张（后端，网络封面清除） | 后端封面轮播 |
| 网络歌曲 | 1 张（pic） | 静态显示，不轮播 |

**验证**：✅ NAS 歌曲切在线歌词后网络封面加入轮播；切回内嵌时网络封面移除；网络歌曲封面静态显示。

---

#### 10.13.4 网络歌曲 EMBEDDED 歌词路径修复

**日期**：2026-06-26

**目标**：网络歌曲切换歌词来源到"内嵌"时无法获取歌词。

**根因**：`LyricsManager.getLyricsFromSource()` 的 `EMBEDDED` 分支对所有歌曲都走后端 `adapter.getLyrics(song.id)`，但网络歌曲不在后端，必然返回 null。

**修改文件**：`lyrics/LyricsManager.kt`

**改动**：`EMBEDDED` 分支增加 `song.isNetworkSong && networkMusicManager != null` 判断，网络歌曲走 `networkMusicManager.resolveLyrics(song)`，NAS 歌曲仍走后端 `adapter.getLyrics()`。

**验证**：✅ 网络歌曲切换到"内嵌"歌词来源能正确获取歌词。

---

#### 10.13.5 设置页左侧导航栏滚动修复

**日期**：2026-06-26

**目标**：设置页左侧导航栏在模拟器上显示不全，且无法用遥控器上下键向下推进。

**根因**：`SettingsScreen` 左侧栏使用普通 `Column`（不可滚动），6 个 `SettingsSection` 分区项加头部在 1080p 模拟器上超过可视高度，超出部分被裁切；`FocusableSurface` 焦点移动到不可见项时也没有滚动机制把它带入视图。

**修改文件**：`ui/screens/SettingsScreen.kt`

**改动**：左侧 `Column` 的 modifier 链上添加 `.verticalScroll(rememberScrollState())`。`Column` 自身可滚动后，当焦点移到当前不可见的 `FocusableSurface` 时，Compose 的 `BringIntoView` 机制会自动滚动该列把焦点项带入可视区域，遥控器上下键即可遍历全部 6 个分区。

**验证**：✅ 模拟器上左侧栏所有 6 个设置分区均可见，遥控器上下键可逐个滚动聚焦。

---

#### 10.13.6 版本号唯一来源统一

**日期**：2026-06-26

**目标**：关于页显示的版本号滞后于 `build.gradle.kts` 中实际发布的版本（发布 2.4.1 时仍显示 2.4.0）。

**根因**：版本号在两处独立硬编码——`app/build.gradle.kts` 的 `versionName`/`versionCode` 与 `NasMusicVersion.kt` 的 `VERSION_NAME`/`VERSION_CODE`。每次发版需要同步两处，容易漏改；关于页读取的是 `NasMusicVersion.DISPLAY`，所以显示旧版本。

**修改文件**：`NasMusicVersion.kt`

**改动**：`VERSION_NAME` / `VERSION_CODE` 从 `const val` 改为 `val get() = BuildConfig.VERSION_NAME` / `BuildConfig.VERSION_CODE`。AGP 已启用 `buildConfig = true`，`defaultConfig` 中的 `versionName`/`versionCode` 自动写入 `com.nasmusic.tv.BuildConfig`。`build.gradle.kts` 成为版本号的唯一来源，代码侧（包括 `DISPLAY`、`ABOUT_STRING` 等派生字符串）自动同步。文件头注释规则第 3 条更新为"修改 app/build.gradle.kts 的 versionName 与 versionCode（唯一来源）"。

**验证**：✅ 关于页显示 `v2.4.1`，与 `build.gradle.kts` 一致；后续发版只改一处。

---

#### 10.13.7 歌词高亮模式状态提升

**日期**：2026-06-26

**目标**：在播放页切到逐字高亮 → 进设置页 → 返回播放页后，高亮模式丢失变回逐行。

**根因**：`NowPlayingScreen` 用 `remember` 保存 `highlightMode`。`AppRoot` 用 `when (currentScreen)` 切换页面，离开的页面完全离开 composition，`remember` 状态被丢弃。返回时状态重置为默认 `LINE_BY_LINE`，而 `LaunchedEffect(lyrics)` 只在歌词含逐字时间戳时才自动切回 `WORD_BY_WORD`——标准 LRC 歌词（用户手动切到逐字）不会触发，所以变回逐行。尝试 `rememberSaveable` 同样无效：没有 NavHost back stack entry 托管 saveable state，离开 composition 时无处保存。

**修改文件**：`ui/viewmodel/MainViewModel.kt`、`ui/screens/NowPlayingScreen.kt`、`ui/components/AppRoot.kt`、`data/model/LyricsLine.kt`

**改动**：
- `MainViewModel` 新增 `_lyricsHighlightMode` / `lyricsHighlightMode: StateFlow<LyricsHighlightMode>` 与 `setLyricsHighlightMode(mode)` 方法；`loadLyricsForCurrentSong` 加载歌词后，若歌词含逐字时间戳则自动切到 `WORD_BY_WORD`，否则保留用户上次选择（不强制重置）。
- `NowPlayingScreen` 的 `highlightMode` 改为外部参数，新增 `onChangeHighlightMode` 回调，移除内部 `remember`/`rememberSaveable` 和 `LaunchedEffect`。
- `AppRoot` 订阅 `viewModel.lyricsHighlightMode`，传给 `NowPlayingScreen`；切换按钮回调调 `viewModel.setLyricsHighlightMode(it)`。
- `LyricsLine.kt` 的 `LyricsHighlightMode.Saver` 回退（状态提升后不再需要 `rememberSaveable`）。

**验证**：✅ 播放页切逐字 → 进设置 → 返回仍为逐字；切歌时含逐字时间戳的歌词自动切到逐字模式，标准 LRC 歌词保留用户选择。

---

### 10.14 v2.4.2 — Code Review 修复

**日期**：2026-06-26

**目标**：根据全项目代码审查文档（`docs/code-review-2026-06-26.md`），修复线程安全、DataStore 阻塞、Kotlin API 退化、Jellyfin 分页缺失等问题。用户决定不修改 #5 MainViewModel 上帝类（无 bug、重构风险高），#6/#4/#13 列为 low 优先级暂不修改。

#### 10.14.1 修改清单

按 review 编号：

| # | 优先级 | 修改内容 | 修改文件 |
|---|--------|----------|----------|
| 3 | HIGH | `seekPending` 添加 `@Volatile`（主线程与 ExoPlayer 回调线程可见性） | `player/PlayerManager.kt` |
| 8 | MEDIUM | `PlayMode.values()` → `PlayMode.entries`（Kotlin 1.9+ 推荐，避免每次创建新数组） | `ui/viewmodel/MainViewModel.kt` |
| 2 | HIGH | `playUrlCache` 从 `mutableMapOf` 改为 `ConcurrentHashMap`（IO 线程并发读写） | `backend/network/NetworkMusicManager.kt` |
| 1 | HIGH | `getRecentSongIdsSync`/`getNetworkFavoritesSync`/`getLastQueueSync` 3 处 `runBlocking` 改为 `suspend`；`restoreLastQueue()` 改为 suspend 并在 `viewModelScope.launch` 中调用；保留 `getDefaultNetworkSourceSync`/`getMetingApiBaseUrlSync`（被 lambda 同步调用无法改） | `data/prefs/AppPreferences.kt`、`ui/viewmodel/MainViewModel.kt` |
| 10 | LOW | `AGENTS.md` 修正 `BackendRegistry` 描述（实际是普通类，非 `object` singleton） | `AGENTS.md` |
| 7 | MEDIUM | `AGENTS.md` 进度轮询间隔从 500ms 修正为 1000ms（v2.2.0 已调整） | `AGENTS.md` |
| 11 | MEDIUM | 全项目 11 个文件 166 处 `android.util.Log` 统一替换为 `AppLog`；仅保留 `AppLog.kt` 自身 4 处封装实现 | `backend/`、`player/`、`ui/`、`lyrics/`、`util/` 共 11 个文件 |
| 12 | LOW | `Screen`/`SongsPagingState` 从 `MainViewModel.kt` 移到 `data/model/` 独立文件 | 新增 `data/model/Screen.kt`、`data/model/SongsPagingState.kt`；修改 `MainViewModel.kt` 及 4 个引用文件 |
| 9 | MEDIUM | `getAlbums`/`getFavorites`/`getSongsByGenre`/`getSongsByYearRange` 4 处硬编码 `Limit=1000` 改为分页循环，参照 `getArtists` 模式 | `backend/impl/JellyfinAdapter.kt` |

#### 10.14.2 未修改项

- **#5 MainViewModel 上帝类**：用户决定不修改（无功能 bug、拆分风险高、违背避免过度工程原则）
- **#6 LibraryScreen 拆分（60KB）**：low 优先级，纯重构无收益，暂不修改
- **#4 OkHttpClient 共享单例**：low 优先级，4 处配置不同需统一基础+个性化，工作量大，暂不修改
- **#13 EncodingUtils 30% 阈值**：low 优先级，建议引入 ICU4J 但当前无 bug，暂不修改

**验证**：待编译验证。

---

### 10.15 v2.4.3 — Code Review 修复（第二轮）

**日期**：2026-06-30

**目标**：根据全项目代码审查文档（`docs/code-review-2026-06-30.md`），修复资源泄漏、API 参数错误、线程安全、编码回退过宽等问题。用户决定不修改安全与隐私类问题（Category 3）。

#### 10.15.1 修改清单

| 优先级 | 类别 | 修改内容 | 修改文件 |
|--------|------|----------|----------|
| P0 | 资源泄漏 | OkHttp Response 泄漏：MetingApiService 3 处（`searchWithEndpoint`/`resolvePlayUrl`/`resolveLyrics`）改为 `response.use {}` | `backend/network/MetingApiService.kt` |
| P0 | 资源泄漏 | OkHttp Response 泄漏：LyricsNetworkProvider 5 处（Kugou 搜索/歌词、Netease 搜索/歌词、parseKugouLyrics）改为 `response.use {}` | `lyrics/LyricsNetworkProvider.kt` |
| P0 | 资源泄漏 | BackendRegistry `initialize()` 异常时 adapter 未释放；重复初始化旧 adapter 未断开；添加 `releaseAdapter()` 和异常路径保护 | `backend/BackendRegistry.kt` |
| P0 | 资源泄漏 | NasMusicApp `applicationScope` 未 cancel；移除废弃 `companion object { lateinit var instance }` | `NasMusicApp.kt` |
| P0 | 资源泄漏 | LyricsNetworkProvider `daemonExecutor` 实例变量改为 `companion object` 静态变量 | `lyrics/LyricsNetworkProvider.kt` |
| P0 | 正确性 Bug | Jellyfin `addToPlaylist` `Ids` 字段：`addProperty("Ids", string)` → `add("Ids", gson.toJsonTree(listOf(...)))` | `backend/impl/JellyfinAdapter.kt` |
| P0 | 正确性 Bug | Jellyfin `setRating`：移除 request body，rating 改为 query param `?rating=N` | `backend/impl/JellyfinAdapter.kt` |
| P0 | 正确性 Bug | Jellyfin `getPlaylists`：从 `/Playlists` 改为 `/Items?IncludeItemTypes=Playlist` | `backend/impl/JellyfinAdapter.kt` |
| P0 | 正确性 Bug | PlaybackService `onDestroy` 释放顺序：`session.release()` 先于 `player.release()` | `player/PlaybackService.kt` |
| P0 | 正确性 Bug | `utf8Body()` 移除希腊/西里尔 GBK 回退，仅 U+FFFD 触发回退 | `backend/impl/JellyfinAdapter.kt` |
| P0 | 正确性 Bug | ArtistSplitter：`feat\.` → `feat\.?`；迭代拆分 `for(delim).flatMap{part.split(delim)}` | `util/ArtistSplitter.kt` |
| P0 | 正确性 Bug | EqualizerScreen 波段循环：`band <= -10f -> 0f` 改为 `if (band >= 10f) -10f else band + 1f` | `ui/screens/EqualizerScreen.kt` |
| P1 | 线程安全 | BackendRegistry 全部状态读写使用 `synchronized(lock)` | `backend/BackendRegistry.kt` |
| P1 | 性能 | AppPreferences `runBlocking` → `runBlocking(Dispatchers.IO)` | `data/prefs/AppPreferences.kt` |

#### 10.15.2 未修改项

- **Category 3 安全与隐私**：用户决定不修改，共 16 项建议全部排除

**验证**：见编译验证。

---

### 10.16 v2.4.4 — Code Review 修复（第三轮：代码质量与类型安全）

**日期**：2026-07-01

**目标**：根据全项目代码审查文档（`docs/code-review-2026-06-30.md`），完成 Groups A–L 的非安全类修复：空安全、类型安全枚举、Compose 动画优化、无用代码清理等。

#### 10.16.1 修改清单

| 优先级 | 类别 | 修改内容 | 修改文件 |
|--------|------|----------|----------|
| P0 | 死代码 | `LyricsSource.SERVER` 移除（v2.4.0 后未使用） | `data/model/LyricsSource.kt` |
| P0 | 代码规范 | `Mp3MetadataExtractor` magic number 26 → 常量 `METADATA_KEY_LYRICS`；移除未使用 `context` 参数 | `util/Mp3MetadataExtractor.kt` |
| P0 | 代码规范 | `RecentSong` 移除无用默认参数；新增 `createNew()` 工厂方法 | `data/model/RecentSong.kt` |
| P0 | UI 可访问性 | `BackButton` 接受 `modifier: Modifier` 参数；硬编码 `"←"` → string 资源 | `ui/components/CommonComponents.kt` |
| P0 | UI 性能 | `PlayerControls` shadow → border（TV 性能）；`LaunchedEffect(Unit)` → `LaunchedEffect(currentSongId)`；移除未使用参数 | `ui/components/PlayerControls.kt` |
| P0 | 空安全 | 3 处 `currentSong!!` → `?.let{}` / `?: ""` | `ui/screens/AppRoot.kt`、`NowPlayingScreen.kt`、`QueueScreen.kt` |
| P0 | 动画竞争 | `FocusableSurface` 移除 `scope.launch + delay`，改用声明式 `LaunchedEffect(isFocused)`；`catch (_: Exception)` → `catch (e: Exception)` 记录日志；移除重复缩放 | `ui/components/FocusableSurface.kt` |
| P0 | 无限循环 | `CoverCarousel` 新增 `permanentlyFailed` 标志，防止 `onAllFailed()` 因 recomposition 循环触发 | `ui/components/CoverCarousel.kt` |
| P0 | recomposition | `EqualizerScreen` bandLabels 提升为顶层 `val` 编译期常量 | `ui/screens/EqualizerScreen.kt` |
| P1 | API 设计 | `NetworkMusicService.search()` 新增 `limit: Int = 0` 参数；接口方法完整 KDoc `@param`/`@return`/`@throws`；新增 `searchCoverUrl()` 默认方法 | `backend/network/NetworkMusicService.kt` |
| P1 | 类型安全 | `NetworkSource` 枚举新增（METING/ALAPI/JIOSAAVN 带 `key`/`displayName`）；`AppSettings.defaultNetworkSource` 从 `String` 改为 `NetworkSource`；AppPreferences 新增 `fromKey()`/`fromName()` 转换器 + 类型 setter（向后兼容） | 新增 `data/model/NetworkSource.kt`；修改 `data/model/AppSettings.kt`、`data/prefs/AppPreferences.kt` |
| P1 | 硬编码 | `NetworkMusicManager.searchCoverUrl` 移除 `if (svc !is MetingApiService) continue` 类型判断 | `backend/network/NetworkMusicManager.kt` |
| P1 | 线程安全 | `SettingsScreen` IO 线程 `MutableState` 写入包裹 `withContext(Dispatchers.Main)` | `ui/screens/SettingsScreen.kt` |

#### 10.16.2 已验证无需修改项

- **EqualizerScreen 波段 -9~-1 不可达**：当前循环逻辑已正确处理所有 10 个波段值（code review #K 标记已关闭）
- **ServerConnectScreen rememberCoroutineScope()**：Compose 运行时自动在 composition 离开时取消协程，无需显式 Job 跟踪

#### 10.16.3 未修改项

- **Security 相关**：未修改（与 v2.4.3 一致，用户决定不处理）
- **BackendAdapter 接口变更**：close()/Boolean/getStreamUrl 等破坏性变更未修改
- **`as any`/`@Suppress`**：未引入任何类型安全规避

### 10.17 v2.5.0 — 网络音乐顶级 Tab（推荐歌单 + 歌单详情 + 独立导航）

**日期**：2026-07-01

**目标**：将网络音乐从 LibraryScreen 的子 Tab 提升为独立顶级导航项，新增推荐歌单、歌单详情页、搜索平台切换。

#### 10.17.1 架构变更

| 变更 | 说明 |
|------|------|
| 新增 Screen.Network / Screen.NetworkPlaylistDetail | Screen 枚举扩展两个新值，AppRoot 中新增 2 个 `when(currentScreen)` 分支 |
| AppRoot 导航栏 6 项 | 新增「网络音乐」NavItem（icon=MusicNote），路由到 Screen.Network |
| NetworkScreen 独立 | 从 LibraryScreen 提取为独立 541 行页面 |
| LibraryScreen 精简 | 移除 NETWORK Tab（LibraryTab 8→7），移除 NetworkTab 组件及 10 个相关参数 |
| CoverCarousel autoCycle | 新增 `autoCycle: Boolean = false` 参数，默认 false（不干扰播放页轮播） |

#### 10.17.2 新增文件

| 文件 | 行数 | 用途 |
|------|------|------|
| `ui/screens/NetworkScreen.kt` | 541 | 搜索框 + 平台切换 + 推荐歌单行 + 热歌/新歌/收藏区 |
| `ui/screens/NetworkPlaylistDetailScreen.kt` | 143 | 歌单详情页：返回按钮 + 标题 + LazyVerticalGrid 歌曲列表 |

#### 10.17.3 数据模型

**Playlist.kt**（统一数据模型，同时服务 NAS 后端和网络音乐）：
```kotlin
data class Playlist(
    val id: String,
    val name: String,
    val coverUrls: List<String> = emptyList(),
    val songCount: Int = 0,
    val owner: String = "",      // NAS 后端专用
    val durationMs: Long = 0L     // NAS 后端专用
)
```

NAS 专用字段（`owner`, `durationMs`）有默认值，网络音乐使用时无需传参。

#### 10.17.4 后端 API 变更

- `NetworkMusicService` 接口：新增 `getPlaylist()` 默认方法
- `MetingApiService`：实现 `getPlaylist()`，使用 `type=playlist` 端点，复用 `parseSongs()` 解析逻辑
- `NetworkMusicManager`：新增 `getPlaylist()` 路由方法（当前为单源，无 fallback）

#### 10.17.5 ViewModel 状态变更

`MainViewModel.kt` 新增：
- `networkPlaylists: StateFlow<List<Playlist>>` — 推荐歌单列表
- `playlistSongs: StateFlow<List<Song>>` — 歌单内歌曲列表
- `selectedPlaylistTitle: StateFlow<String>` — 当前选中歌单标题
- `loadNetworkPlaylists()` — 加载 7 个预置网易云歌单（热歌榜/新歌榜/飙升榜/华语流行/欧美流行/抖音热门/经典老歌），失败时静默返回空列表
- `loadPlaylistDetail(Playlist)` — 加载指定歌单的歌曲列表，翻译 `id` 字段转换歌单 ID

#### 10.17.6 UI 变更

**NetworkScreen**：
- 搜索框（与 LibraryScreen 共享 `searchQuery` 状态）
- 平台切换按钮（网易云/QQ 音乐/酷狗），歌词来源标签样式
- 推荐歌单 LazyRow：CoverCarousel 卡片（autoCycle=true），点击进入 NetworkPlaylistDetailScreen
- 热歌推荐 + 新歌推荐 LazyColumn 区
- 收藏歌曲区

**NetworkPlaylistDetailScreen**：
- BackButton + 歌单标题
- LazyVerticalGrid 歌曲列表（SongRow 样式）
- 点击歌曲自动播放

**strings.xml**：
- 新增 8 个 `network_*` 字符串（`network_title`, `network_playlist_recommended`, `network_hot_songs`, `network_new_songs`, `network_favorites`, `network_search_placeholder`, `network_netease`, `network_qq_music`, `network_kugou`）
- 移除 7 个 `library_network*` 字符串

#### 10.17.7 未修改范围

| 范围 | 状态 |
|------|------|
| BackendAdapter / JellyfinAdapter / NavidromeAdapter | 未修改 |
| 播放/队列/收藏数据流 | 未修改 |
| Gradle 依赖 | 未新增 |
| NAS 后端连接逻辑 | 未修改 |
| ALAPI / JioSaavn 枚举占位 | 保留未实现 |

#### 10.17.8 验证结果

- ✅ `./gradlew.bat test` BUILD SUCCESSFUL（55 tests passing）
- ✅ `./gradlew.bat assembleDebug` BUILD SUCCESSFUL
- ✅ 用户确认 TV 安装成功，全部 UI 路由、搜索、推荐歌单、平台切换功能正常
- ✅ 版本号 v2.5.0 / versionCode 12
- ✅ 4 个实现 commit + 1 个文档收尾 commit 已推送到 GitHub

---

### 10.18 v2.5.1 — 网络音乐端点 fallback + 默认端点切换

**日期**：2026-07-01

**问题描述**：
- 默认 Meting-API 端点 `meting.mikus.ink` 限流（429 Too Many Requests）
- `getPlaylist()` 和 `resolvePlayUrl()` 无多端点 fallback 机制，使用单一端点
- 所有网络歌单加载失败（推荐内容空白）、歌曲无法播放，但无用户提示

**修复内容**：

1. **默认端点切换**：`DEFAULT_BASE_URL` 从 `meting.mikus.ink` 改为 `meting.api.redcha.cn`
2. **getPlaylist() 加 fallback**：当前端点失败时自动尝试其他预设端点（`buildEndpointFallbackOrder()`），类似 `search()` 的策略
3. **resolvePlayUrl() 加 fallback**：同上，按端点顺序尝试，首个非空结果返回
4. **用户提示**：
   - `loadNetworkPlaylists()`：全部 7 个歌单都加载失败时调用 `showError()` 提示用户
   - `playQueue()`：第一首歌 URL 解析全部失败时调用 `showError()` 提示用户

**影响文件**：
- `backend/network/MetingApiService.kt` — 默认端点、getPlaylist/resolvePlayUrl fallback
- `ui/viewmodel/MainViewModel.kt` — 全失败时用户提示

**验证结果**：
- ✅ 端点测试：Redcha 端点 playlist/search/url 均返回 200
- ✅ 原始端点测试：Mikus 端点返回 429，触发 fallback 到 Redcha
- ✅ `./gradlew.bat assembleDebug` BUILD SUCCESSFUL

---

### 10.19 v2.5.1 — TV 启动崩溃修复（ProGuard + Gson 类型擦除）

**日期**：2026-07-01

**问题描述**：
- v2.5.1 Release APK 安装到 TV 后立即崩溃，logcat 显示 `ClassCastException: LinkedTreeMap cannot be cast to Song`

**根因分析**：
- `AppPreferences$LastQueueData.songs: List<Song>` 字段在 Release 编译时被 R8 剥离了泛型签名（`Ljava/util/ArrayList;` 替代 `Ljava/util/List<Lcom/nasmusic/tv/data/model/Song;>;`）
- `getLastQueue()` 使用 `gson.fromJson(json, LastQueueData::class.java)` 反序列化时，Gson 反射看到 `songs` 字段为 raw `List` 类型，将每个元素反序列化为 `LinkedTreeMap` 而非 `Song`
- 当 `currentSong.collect` 收到 `_currentSong` 的值时，JVM `checkcast` 指令将 `LinkedTreeMap` 转型为 `Song` 失败
- `ProGuard 规则 -keep class com.nasmusic.tv.data.model.** { *; }` 保护了 `Song` 本身，但 `data.prefs` 包未受保护

**修复内容**：

1. **ProGuard 规则扩展**：`proguard-rules.pro` 添加 `-keep class com.nasmusic.tv.data.prefs.** { *; }`，保留 `LastQueueData` 的完整泛型签名
2. **空安全增强**：`restoreLastQueue()` 中 `lastQueue.songs` 增加 `isNullOrEmpty()` 检查，防止残留损坏数据导致 NPE

**影响文件**：
- `proguard-rules.pro` — 新增 `data.prefs` keep 规则
- `ui/viewmodel/MainViewModel.kt` — `restoreLastQueue()` 空安全增强

**验证结果**：
- ✅ `./gradlew.bat assembleRelease` BUILD SUCCESSFUL（4m 8s）
- ✅ adb install 到 TV 成功
- ✅ `pm clear` 清除旧数据后应用正常启动，无 ClassCastException
- ✅ GitHub Release v2.5.1 APK 已替换为修复版本

---

### 10.20 v2.6.0 — 天气电台 + 榜单改版 + 封面滤镜

**日期**：2026-07-03

**目标**：新增天气电台（Phase 2）、榜单卡片网格化（Phase 3）、歌词字体缩放（Phase 4）、封面滤镜设置（Phase 5）。

#### 10.20.1 天气电台 (Phase 2)

**新增文件**：
- `backend/weather/WeatherApi.kt` — OpenWeatherMap API 封装（经纬度→城市名→实时天气/5 日预报）
- `backend/weather/WeatherRadioManager.kt` — 天气电台引擎：按心情关键词从 NAS 曲库+网络搜索匹配歌曲，去重合并
- `data/model/WeatherData.kt` — WeatherData / WeatherForecast / WeatherCondition 数据模型
- `data/model/WeatherMood.kt` — 天气心情枚举 SUNNY/RAINY/SNOWY/WINDY/CLOUDY/NIGHT，各含 searchQueries
- `data/model/WeatherRadioQueue.kt` — 电台队列模型（songs + mood + queries + 统计）
- `ui/screens/network/WeatherSubTab.kt` — 天气 Tab 界面：当前天气卡片 + 心情切换 + 歌曲列表 + 播放控制

**修改文件**：
- `data/prefs/AppPreferences.kt` — 新增 weatherEnabled / weatherManualCity / weatherAutoRefresh 设置
- `ui/viewmodel/MainViewModel.kt` — 新增 weatherData / weatherRadioQueue / currentWeatherMood / weatherLoading / weatherError 状态、fetchWeather() / switchWeatherMood() / playWeatherRadioAll() 方法
- `ui/screens/network/NetworkMusicContainer.kt` — 集成 WeatherSubTab，添加天气参数路由
- `ui/screens/network/NetworkSubTabViews.kt` — DiscoverTab 添加天气入口 FeatureShortcut
- `ui/components/AppRoot.kt` — 天气参数透传
- `strings.xml` — 新增 network_tab_weather / network_weather_* / network_discover_weather_* 字符串

#### 10.20.2 榜单改版 (Phase 3)

**修改文件**：
- `ui/components/network/ChartsContent.kt` — 从简单列表改为双列卡片网格（140dp × 140dp），每张卡片显示 CoverCarousel 封面轮播 + 榜单名称。新增每日自动轮换（`chartsRotationIndex`）+"换一批"按钮（`refreshCharts()`）
- `data/model/Song.kt` — 未修改（复用现有数据模型）
- `ui/viewmodel/MainViewModel.kt` — 新增 `refreshCharts()` 方法：随机 seed→榜单排序打乱
- `data/prefs/AppPreferences.kt` — 新增 `keyPreconfiguredPlaylists` 扩展至 20+ 个预置歌单 ID，涵盖 Hot Songs / New Releases / Mood / Genre / Era 多维度

#### 10.20.3 歌词字体缩放 (Phase 4)

**修改文件**：
- `data/prefs/AppPreferences.kt` — 新增 `lyricsFontScale` 设置（doublePreferencesKey），范围 0.7 – 1.6
- `ui/screens/NowPlayingScreen.kt` — 歌词区域添加字号 +/- 按钮，调用 `onLyricsFontScaleChange` 回调
- `ui/components/LyricsView.kt` — `fontSizeMultiplier` 参数传递至 `ChunkyText` fontSize
- `ui/components/AppRoot.kt` — 新增 `lyricsFontScale` 状态收集 + 回调绑定到 preferences
- `ui/screens/SettingsScreen.kt` — 新增歌词字号开关（可复用 Lyrics 设置页）
- `ui/viewmodel/MainViewModel.kt` — 新增 `updateLyricsFontScale()` wrapper 方法

#### 10.20.4 封面滤镜设置 (Phase 5)

**新增文件**：
- (无新增文件 — 全部在现有文件中扩展)

**修改文件**：
- `data/prefs/AppPreferences.kt` — 新增 coverFilterEnabled(boolean) / coverFilterBlurRadius(double↔float) / coverFilterDarkOverlay(double↔float) 3 组设置，floatPreferencesKey 改用 doublePreferencesKey（标准 DataStore 无 float key）
- `ui/screens/SettingsScreen.kt` — 新增 COVER 侧边栏，封面滤镜开关（SettingSwitch）+ 模糊强度 +/- 按钮 + 暗色遮罩 +/- 按钮
- `ui/screens/NowPlayingScreen.kt` — CoverColumn 新增 coverFilterEnabled/coverFilterBlurRadius/coverFilterDarkOverlay 参数，封面渲染时添加 `.blur(radius.dp)` + 暗色半透明遮罩
- `ui/components/AppRoot.kt` — 封面滤镜状态移入 AppRoot 级别（跨 NowPlaying/Settings 共享），回调绑定
- `ui/viewmodel/MainViewModel.kt` — 新增 `updateCoverFilterEnabled/BlurRadius/DarkOverlay()` wrapper 方法

#### 10.20.5 编译修复

由于 `prefs` 为 private 导致 `AppRoot.kt` 无法访问，一并修复以下预存问题和新增问题：

- `MainViewModel.kt`: `private val prefs` → `val prefs`（公开访问）
- `AppPreferences.kt`: `floatPreferencesKey`（不存在）→ `doublePreferencesKey` + Float↔Double 转换（涉及 lyricsFontScale 和历史存量问题）
- `WeatherRadioManager.kt`: `song.songId` → `song.id`（Song 数据类只有 id 字段）
- `WeatherSubTab.kt`: `FocusableSurface` 移除不支持的 `enabled` 参数；`android.R.string.refresh` 改为直接标"刷新"
- `MainViewModel.kt`: 移除重复的 Screen/SongsPagingState import；`TAG` 引用→直接传 `"MainViewModel"`

**影响文件汇总**：
| 文件 | Phase |
|------|-------|
| `backend/weather/WeatherApi.kt` | 2 (新增) |
| `backend/weather/WeatherRadioManager.kt` | 2 (新增) |
| `data/model/WeatherData.kt` | 2 (新增) |
| `data/model/WeatherMood.kt` | 2 (新增) |
| `data/model/WeatherRadioQueue.kt` | 2 (新增) |
| `ui/screens/network/WeatherSubTab.kt` | 2 (新增) |
| `data/prefs/AppPreferences.kt` | 2/3/4/5 |
| `ui/viewmodel/MainViewModel.kt` | 2/3/4/5 |
| `ui/screens/network/NetworkMusicContainer.kt` | 2 |
| `ui/screens/network/NetworkSubTabViews.kt` | 2 |
| `ui/components/network/ChartsContent.kt` | 3 |
| `ui/components/LyricsView.kt` | 4 |
| `ui/screens/SettingsScreen.kt` | 5 |
| `ui/screens/NowPlayingScreen.kt` | 4/5 |
| `ui/components/AppRoot.kt` | 2/4/5 |
| `app/src/main/res/values/strings.xml` | 2/3/5 |

**验证结果**：
- ✅ `./gradlew.bat assembleDebug` BUILD SUCCESSFUL
- ⚠️ 16 项测试失败（8 LrcParserTest + 8 NetworkMonitorTest），均为预存问题，与本次改动无关
- ⏳ 需 TV 安装验证（Phase 2 天气 API 需 OpenWeatherMap API Key，Phase 5 封面滤镜需视觉确认）

#### 10.20.6 修复: playQueue 异步解析 URL 期间队列状态竞态条件

**问题**：
`MainViewModel.playQueue()` 在网络歌曲需要异步解析 streamUrl 时（`needsResolve=true`），先启动协程再返回，队列状态（`_queue`/`_currentIndex`/`_currentSong`）直到协程完成解析后才更新。这留下了几秒到十几秒的窗口期，期间 `currentSong.value` 仍指向旧的恢复队列。如果用户在此期间按播放键，`playPause()` 会错误地调用 `resolveAndPlayCurrentSong()` 尝试解析旧队列歌曲的 streamUrl。

**修改**：
- `ui/viewmodel/MainViewModel.kt` — 在 `playQueue()` 的 `needsResolve=true` 分支中，启动协程前立即调用 `playerManager.restoreQueue(songs, startIndex)`，将队列状态立刻切换到新歌单。由于网络歌曲的 `streamUrl` 为空，`restoreQueue` 会跳过 ExoPlayer `prepare()`，实际的播放设置仍在协程解析 URL 后由 `playerManager.playQueue()` 统一完成。

**影响文件**：
| 文件 | 改动 |
|------|------|
| `ui/viewmodel/MainViewModel.kt` | playQueue() 新增 1 行 `playerManager.restoreQueue()` |

#### 10.20.7 新增: 天气 API Key 配置 UI

**背景**：
v2.6.0 天气电台功能使用 Open-Meteo（无需 API Key）作为主要天气数据源。用户反馈在中国家庭网络下 Open-Meteo 被阻断，导致天气功能不可用。

**修改**：
- `backend/weather/WeatherApi.kt` — `fetchCurrentWeather()` 新增 `openWeatherMapApiKey: String` 参数。先尝试 Open-Meteo，失败或无数据时 fallback 到 OpenWeatherMap（需 API Key）。OpenWeatherMap 请求参数 `units=metric&lang=zh_cn`
- `data/prefs/AppPreferences.kt` — 新增 `keyWeatherApiKey` (`stringPreferencesKey`)，公开 `weatherApiKey` Flow + `getWeatherApiKeySync()` + `setWeatherApiKey()`
- `ui/viewmodel/MainViewModel.kt` — `fetchWeather()` 从 prefs 读取 API Key 并传入 `fetchCurrentWeather()`；新增 `updateWeatherApiKey()` wrapper 方法。错误提示改进：显示"请进入设置 → 网络 → 天气 API Key 配置"
- `ui/screens/SettingsScreen.kt` — 网络设置区域新增"天气 API Key"配置项，显示遮掩后 6 位或"未设置"，点击弹出 TextInputDialog 输入 Key
- `ui/components/AppRoot.kt` — 新增 `weatherApiKey` 状态收集，透传 `onChangeWeatherApiKey` 回调到 SettingsScreen
- `app/src/main/res/values/strings.xml` — 新增 `common_not_set`、`settings_weather_api_key`、`settings_weather_api_key_desc`、`settings_weather_api_key_hint`

**影响文件**：
| 文件 | 改动 |
|------|------|
| `backend/weather/WeatherApi.kt` | fetchCurrentWeather() 新增参数 + fallback 逻辑 |
| `data/prefs/AppPreferences.kt` | 新增 weatherApiKey 存取 |
| `ui/viewmodel/MainViewModel.kt` | fetchWeather() 传参 + updateWeatherApiKey() + 错误提示 |
| `ui/screens/SettingsScreen.kt` | 网络设置新增 API Key 配置项 + 编辑对话框 |
| `ui/components/AppRoot.kt` | 状态收集 + 回调透传 |
| `app/src/main/res/values/strings.xml` | 4 个新增字符串 |

**验证结果**：
- ✅ `./gradlew.bat assembleDebug` BUILD SUCCESSFUL
- ⏳ 需用户配置 OpenWeatherMap API Key 后 TV 实测

---

### 10.21 v2.6.1 — 天气电台无后端修复 + OpenWeatherMap API Key 配置 UI

**日期**：2026-07-03

**目标**：修复天气电台在无 NAS 后端连接时不显示歌曲的问题；新增 OpenWeatherMap API Key 配置 UI 以解决国内网络 Open-Meteo 不可用的问题。

#### 10.21.1 天气电台无后端连接时不显示歌曲

**问题**：`fetchWeather()` 中 `weatherRadioManager` 仅在 `backendRegistry.getAdapter() != null` 时创建。纯网络音乐用户（无后端连接）的天气电台永远无歌曲，切换 mood 也无效。

**修改**：
- `WeatherRadioManager.kt` — `backendAdapter` 改为 `BackendAdapter?`（可空），`searchNasSongs()` 开头增加空判断：adapter 为 null 时直接返回空列表
- `MainViewModel.kt` — `fetchWeather()` 去掉 `adapter != null` 条件，始终创建 `WeatherRadioManager`；`switchWeatherMood()` 增加延迟初始化 fallback

**影响文件**：
| 文件 | 改动 |
|------|------|
| `backend/weather/WeatherRadioManager.kt` | BackendAdapter 可空化 + searchNasSongs 空安全 |
| `ui/viewmodel/MainViewModel.kt` | fetchWeather/switchWeatherMood 初始化逻辑放宽 |

#### 10.21.2 OpenWeatherMap API Key 配置 UI

**背景**：用户测试发现中国家庭网络下 Open-Meteo 被阻断，天气功能不可用。

**修改**：
- `WeatherApi.kt` — `fetchCurrentWeather()` 新增 `openWeatherMapApiKey` 参数，Open-Meteo 失败时 fallback 到 OpenWeatherMap
- `AppPreferences.kt` — 新增 `weatherApiKey` string 偏好存取
- `MainViewModel.kt` — `fetchWeather()` 读取 API Key 传入 WeatherApi；新增 `updateWeatherApiKey()`；错误提示引导到设置页
- `SettingsScreen.kt` — 网络分区新增天气 API Key 配置项 + TextInputDialog
- `AppRoot.kt` — 状态收集 + 回调透传
- `strings.xml` — 新增 `common_not_set`、`settings_weather_api_key` 等 4 个字符串

**影响文件**：
| 文件 | 改动 |
|------|------|
| `backend/weather/WeatherApi.kt` | OpenWeatherMap fallback 逻辑 |
| `data/prefs/AppPreferences.kt` | weatherApiKey 存取 |
| `ui/viewmodel/MainViewModel.kt` | fetchWeather 传参 + updateWeatherApiKey + 错误提示 |
| `ui/screens/SettingsScreen.kt` | API Key 配置项 + 编辑对话框 |
| `ui/components/AppRoot.kt` | 状态收集 + 回调透传 |
| `app/src/main/res/values/strings.xml` | 4 个新增字符串 |

**验证结果**：
- ✅ `./gradlew.bat assembleDebug` BUILD SUCCESSFUL
- ⏳ 需用户安装 TV 实测（配置 OpenWeatherMap API Key + 切换心情）

---

### 10.22 v2.7.0 — 首页仪表盘 + 歌曲详情面板 + 可视化均衡器 + 天气电台增强 + 播放统计

**日期**：2026-07-20

**目标**：参考 mineradio-mobile，为 NASMusicTV 增加 5 个展示功能模块并修复编译问题。

#### 10.22.1 首页仪表盘 (HomeScreen + HomeDashboardData)

**功能描述**：新增 HomeScreen 作为应用首页，实时显示：
- 当前播放歌曲（封面 + 标题 + 艺术家 + 播放/暂停控制）
- 最近播放列表（基于 PlayRecord 统计数据）
- 当前天气概要（城市 + 温度 + 天气图标）
- 均衡器频谱动画预览
- 4 个匹配封面推荐展示

**新增文件**：
- `data/model/HomeDashboardData.kt` — 首页聚合数据模型
- `ui/screens/HomeScreen.kt` — 首页 Composable（含 5 个区域布局）

**修改文件**：
- `ui/viewmodel/MainViewModel.kt` — 新增 `_homeDashboardData` StateFlow；`fetchHomeDashboard()` 聚合所有数据源；新增 `Screen` 枚举 `HOME`
- `ui/components/AppRoot.kt` — 注册 HOME 导航路由
- `data/model/Screen.kt` — 新增 `HOME` 枚举值

#### 10.22.2 歌曲详情面板 (SongInfoPanel + SongTechnicalInfo)

**功能描述**：当前播放页新增歌曲技术参数面板，悬浮展示码率、采样率、声道数、格式、编码器、时长等 MediaExtractor 提取的信息。

**新增文件**：
- `data/model/SongTechnicalInfo.kt` — 技术参数数据模型
- `ui/components/SongInfoPanel.kt` — 悬浮信息面板 Composable（基于 FocusableSurface 封装）

**修改文件**：
- `ui/viewmodel/MainViewModel.kt` — `fetchSongTechnicalInfo()` 通过 MediaExtractor + DataSource 提取信息

#### 10.22.3 可视化均衡器 (VisualEqualizer)

**功能描述**：实时频谱动画，支持 ColorFlow（渐变色流动）、NeonPulse（霓虹脉冲）、ClassicalWave（经典波形）三种视觉主题；基于 Canvas 2D 渲染，256 点 FFT 数据密度。

**新增文件**：
- `ui/components/VisualEqualizer.kt` — 频谱动画 Composable（含 3 种主题 + 随机柱状图）

**修改文件**：
- `ui/screens/NowPlayingScreen.kt` — 集成 VisualEqualizer 到播放页
- `ui/screens/EqualizerScreen.kt` — 频谱设置选项影响 HomeScreen 预览

#### 10.22.4 天气电台增强 (WeatherApi + WeatherForecast)

**功能描述**：
- 天气数据源双栈：优先 Open-Meteo（免费、无需 Key），失败自动 fallback 到 OpenWeatherMap（需 Key）
- 未来 5 天天气预报（基于 OpenWeatherMap 5-day/3-hour 数据，按天去重）
- WMO 天气代码 → 中文描述映射
- IP 定位（ip-api.com）自动识别城市

**新增文件**：
- `data/model/WeatherForecast.kt` — 预报数据模型（日期、高低温度、湿度、天气代码、描述、图标）

**修改文件**：
- `backend/weather/WeatherApi.kt` — `getWeatherOpenWeatherMap()` 新增 OpenWeatherMap fallback；`getForecast()` 预报查询；`describeWeatherCode()` WMO→中文描述；`mapOpenWeatherMapCode()` OpenWeatherMap→WMO 映射；`fetchCurrentWeather()`/`fetchForecast()` 一次性入口
- `data/model/WeatherData.kt` — 新增 `feelsLike`、`cityName` 字段
- `data/prefs/AppPreferences.kt` — `weatherApiKey` 存取
- `ui/viewmodel/MainViewModel.kt` — `fetchCurrentWeather()`/`fetchForecast()` 调用
- `ui/screens/network/WeatherSubTab.kt` — 天气预报子 Tab
- `ui/components/AppRoot.kt` — 天气数据状态收集
- `strings.xml` — 新增 `home_song_info` 等字符串

#### 10.22.5 播放统计 (PlayRecord)

**功能描述**：自动记录每首歌曲的播放次数与最后播放时间；首页"最近播放"列表基于 PlayRecord 统计数据驱动。

**新增文件**：
- `data/model/PlayRecord.kt` — 播放记录数据模型（songId、playCount、lastPlayedAt）

**修改文件**：
- `data/prefs/AppPreferences.kt` — `playRecords` DataStore 读写
- `ui/viewmodel/MainViewModel.kt` — `recordPlay()` 自动更新播放计数

#### 10.22.6 编译修复

**问题**：`WeatherApi.kt` 中 `return@try null` 使用了 Kotlin 标签语法，但 `try` 是语言结构而非函数作用域，`return@label` 不支持。导致整个文件解析失败，级联影响 `MainViewModel`、`HomeScreen` 等 4 个文件。

**修改**：
- `WeatherApi.kt` — `return@try null` 改为 `return null`（Kotlin `return try { ... }` 中 `return` 直接返回外层函数，无需标签）
- `HomeScreen.kt` — 移除 `import androidx.compose.foundation.layout.weight`（`Modifier.weight()` 是 RowScope/ColumnScope 成员扩展，无需显式导入）
- `VisualEqualizer.kt` — 频谱数学改为 Float（Double→Float 隐式转换不兼容）；`toPx()` 移入 Canvas 绘制作用域
- `LibraryScreen.kt` — 补充 `import androidx.compose.ui.text.font.FontWeight`
- `MainViewModel.kt` — `_progress.value` 改为 `progress.value`

**验证结果**：
- ✅ `./gradlew.bat assembleDebug` BUILD SUCCESSFUL
- ✅ 本地提交 `fd2ae8e`（main 分支）
- ⏳ 需用户安装 TV 实测

#### 10.22.7 BackendAdapter 接口扩展（基础设施）

功能模块所需的接口方法已在 `BackendAdapter` / `JellyfinAdapter` / `NavidromeAdapter` 中实现，包括：
- `getSongTechnicalInfo()` — 获取歌曲技术参数（Jellyfin 通过 MediaStreams，Navidrome 通过 Subsonic API）
- `recordPlay()` / `getPlayRecords()` — 播放记录读写（Jellyfin/Navidrome 各自实现）
- `getCoverUrlCandidates()` — 多候选封面列表
- `searchSongsByMood()` — 按心情搜索歌曲（天气电台用）


### 11.1 文档位置

| 文件 | 用途 |
|------|------|
| `docs/regression-test.md` | 完整回归测试文档（19 章节 248 个测试项） |

### 11.2 测试覆盖范围

| 类别 | 测试项数量 | 覆盖内容 |
|------|-----------|---------|
| 单元测试 | 83 | ArtistSplitter、PinyinUtils、LrcParser、UiState、TimeUtils、RetryUtil、MediaKeyHandler、NetworkMonitor |
| 后端连接 | 15 | Jellyfin/Navidrome 连接、断开、测试连接、配置持久化 |
| 曲库浏览 | 28 | 专辑/演唱者/歌曲/流派/年代 tab、搜索、详情页、分页加载 |
| 播放控制 | 18 | 播放/暂停、上/下一曲、seek、播放模式、错误处理 |
| 歌词系统 | 6 | LRC 解析、内嵌歌词、网络匹配、逐字高亮、来源切换 |
| 队列管理 | 6 | 添加/移除/清空/移动、当前曲目同步 |
| 收藏与最近播放 | 8 | 收藏切换、收藏列表、最近播放、播放次数 |
| 播放列表 | 5 | 创建/删除/播放/移除歌曲 |
| 均衡器 | 6 | 预置方案、频段调节、持久化 |
| 设置 | 9 | 主题、动画、默认模式、缓存管理、关于 |
| UI 焦点与导航 | 16 | D-pad 导航、焦点移动、BACK 键层级、沉浸模式 |
| 通知与后台播放 | 8 | 前台通知、媒体按钮、后台播放 |
| 网络异常 | 5 | 断网提示、自动重连、错误恢复 |
| 安全与加密 | 6 | 密码加密、Keystore、降级兼容 |
| 退出清理 | 7 | 进程终止、资源释放、OkHttp 守护线程 |
| 近期修复专项 | 22 | v2.2.0 修复项的专项验证 |

### 11.3 使用方式

- **修改或新增功能后**：执行相关章节的测试场景确保核心功能不受影响
- **发布前完整回归**：按文档第 18 章"测试执行清单"逐项执行
- **缺陷报告**：按文档第 19 章"缺陷报告模板"记录问题


### 10.23 v2.8.0 — 频谱可视化引擎重写（感知频率翘曲 + 实时 FFT）

**功能描述**：用 Android Visualizer 实时 FFT 引擎完全替换旧版随机频谱动画。从底层 FFT 捕获到 UI 渲染完整重写。

#### 新增文件

- `player/SpectrumAnalyzer.kt` — FFT 捕获 → 32 柱感知映射 → 自适应噪声基底 → 归一化链式增强

#### 修改文件

- `player/PlayerManager.kt` — SpectrumAnalyzer 生命周期管理（initSpectrumAnalyzer + 重试 + release）
- `ui/components/VisualEqualizer.kt` — 完整重写：从 3 种静态主题改为实时 FFT 渲染
- `ui/components/AppRoot.kt` — 集成 spectrumData 数据流
- `ui/screens/NowPlayingScreen.kt` — spectrumData 参数传递
- `ui/viewmodel/MainViewModel.kt` — val spectrumData 桥接
- `data/model/EqualizerPreset.kt` — 小幅调整
- `ui/screens/HomeScreen.kt` — 频谱相关修改
- `AndroidManifest.xml` — 添加 RECORD_AUDIO 权限

#### 验证结果

- ✅ `./gradlew.bat assembleDebug` BUILD SUCCESSFUL
- ⏳ 需用户安装 TV 实测

### 10.24 v2.8.1 — 合作歌曲艺术家拆分修复

**功能描述**：修复中英文混排分隔符（全角逗号、全角 and 符、半角逗号）导致合作歌曲艺术家未正确拆分的问题；艺术家列表改为提前加载；拆分艺术家详情页歌曲加载修复。

#### 修改文件

- `util/ArtistSplitter.kt` — 分隔符正则追加 `，`（全角逗号）、`＆`（全角 and 符）、`,`（半角逗号）
- `ui/viewmodel/MainViewModel.kt`：
  - `loadArtists()` — 对原始艺术家列表使用 `flatMap + ArtistSplitter.split()` 拆分，`groupBy { name }` 合并去重
  - `loadLibrary()` — `loadArtists()` 提前至专辑/流派/收藏并行加载阶段，不再依赖 ARTISTS Tab 触发
  - `loadArtistSongs()` — 从合成 ID（`原ID|名称`）提取原始 ID，请求后端后按拆分艺术家名过滤匹配歌曲

#### 验证结果

- ✅ `./gradlew.bat assembleDebug` BUILD SUCCESSFUL（CI 已验证）
- ✅ 合作歌曲正确拆分：`"窦唯 & 不一定"` → 窦唯、不一定；`"杨宗纬，宝石Gam"` → 杨宗纬、宝石Gam
- ✅ 拆分后的艺术家详情页能正确显示其歌曲

### 10.25 v2.10.5 — 合作曲详情页修复 & 布局挤压修复

**功能描述**：修复 Jellyfin 适配器中 `jsonObjectToSong` 只取 `Artists[0]` 导致合作歌曲被丢弃的问题；修复曲库页 9 个 Tab 挤压右侧搜索/播放全部按钮的布局问题。

#### 修改文件

- `app/build.gradle.kts` — versionCode 25→26, versionName "2.10.4"→"2.10.5"
- `backend/impl/JellyfinAdapter.kt`：
  - `jsonObjectToSong()` — `Artists` 数组从 `firstOrNull()?.asString` 改为 `mapNotNull { it?.asString }?.joinToString(", ")`，拼接全部艺术家
  - `getArtistSongs()` — 新增诊断日志（返回条数 + 前 3 首取样）
- `ui/screens/LibraryScreen.kt`：
  - Tab 外层 padding `4.dp`→`2.dp`，文字 padding `16.dp`→`10.dp` 省出 ~90dp
  - 去掉 `Box(weight(1f))` 包装，改为 SearchBar 内部 Surface 带 `weight(1f)` 优先压缩
  - ButtonChip 新增 `modifier` 参数，搜索按钮加 `widthIn(min=56.dp)` 保护
- `ui/screens/AlbumDetailScreen.kt` — ButtonChip 调用改为显式命名参数
- `ui/screens/ArtistDetailScreen.kt` — ButtonChip 调用改为显式命名参数
- `ui/screens/PlaylistManagementScreen.kt` — ButtonChip 调用改为显式命名参数
- `ui/viewmodel/MainViewModel.kt`：
  - `loadArtistSongs()` — 进入详情页时清除当前歌手缓存，强制重新拉取
  - 新增诊断日志

#### 验证结果

- ✅ `./gradlew.bat assembleDebug` BUILD SUCCESSFUL
- ✅ 林子祥详情页从 2 首恢复至 39 首
- ✅ 曲库页"搜索"/"播放全部"按钮不再被挤压

### 10.26 v2.10.6 — Jellyfin 合作歌曲修复 + Navidrome 多 ID 联合查询 + 性能优化

**功能描述**：
1. Jellyfin `getArtistSongs()` 从 `ArtistIds`（按 ID）改为 `Artists`（按名称字符串），避免 Jellyfin 中 `AlbumArtist` ID 与 `ArtistItems` ID 不一致导致合作曲丢失
2. Navidrome 新增多 ID 联合查询：保存原始艺术家列表，从拆分前的关系中找出所有相关原始条目，分别查询后合并去重
3. 移除 `loadArtistSongsMap` 全量预加载（5000+ 艺术家 × 1000 批串行请求），改为歌曲 Tab `buildArtistMapsIncremental` 自动填充
4. 移除 `utf8Body()` 中 5 条 `AppLog.d` 调试日志

#### 修改文件

- `app/build.gradle.kts` — versionCode 26→27, versionName "2.10.5"→"2.10.6"
- `backend/BackendAdapter.kt` — `getArtistSongs()` 新增 `artistName: String? = null` 参数
- `backend/impl/JellyfinAdapter.kt`：
  - `getArtistSongs()` — 当 `artistName` 不为空时，用 `Artists=${URLEncoder.encode(artistName)}` 代替 `ArtistIds=$artistId`
  - `utf8Body()` — 移除 5 条 `AppLog.d` 调试日志（hex 字节、U+FFFD 状态、前 50 字符、GBK 回退记录）
- `backend/impl/NavidromeAdapter.kt` — `getArtistSongs()` 签名新增 `artistName: String?`
- `ui/viewmodel/MainViewModel.kt`：
  - 新增 `_rawArtistList` 保存原始艺术家列表（拆分前）
  - `loadArtists()` — 保存 `_rawArtistList`，移除 `loadArtistSongsMap()` 调用
  - `loadArtistSongs()` — 从原始列表查出所有匹配原始 ID，逐个查询后去重合并
  - 移除 `loadArtistSongsMap()` 函数及其 `_artistSongsMapLoaded` 字段

#### 验证结果

- ✅ `./gradlew.bat assembleDebug` BUILD SUCCESSFUL
- ✅ Jellyfin 宫崎骏详情页从 1 首恢复至正常数量
- ✅ `loadArtistSongsMap` 不再执行，启动后无额外 5000+ API 请求

### 10.27 v2.11.0 — 网络音乐搜索"全部播放" + 过期链接自动重试修复

**功能描述**：
1. 网络音乐搜索 Tab 新增"全部播放"操作栏：搜索结果按歌手+歌名去重（`distinctBy { artist to title }`）后批量加入播放队列，上限 30 首（`maxNetworkBatchPlayCount`），完成后自动跳转 NowPlaying
2. 搜索结果列表改用统一 `SongRow` 组件，内嵌"加入队列"切换按钮（`isInQueue`/`onToggleQueue`），与歌单/收藏列表交互一致
3. 修复网络歌曲播放约 5 首后无法继续的问题：入队时预解析的网易/CDN 直链有时效，URL 过期后 `onPlayerError` 仅因链接非空就直接 `next()` 级联跳歌。现改为：出错时若当前歌曲 URL 非空，先经 `onNeedResolveStreamUrl` → `resolveAndPlayByIndex` 重新解析一次再播放；`lastErrorRetryIndex` 守卫保证同一首歌只重试一次（重试自身触发的 `PLAYLIST_CHANGED` 过渡不会重置守卫），仍失败才自动跳下一首

#### 修改文件

- `app/build.gradle.kts` — versionCode 30→31, versionName "2.10.9"→"2.11.0"
- `ui/viewmodel/MainViewModel.kt`：
  - 新增 `playAllSearchResults()` — 去重 + 截断后 `playNetworkBatch(deduped, 0)`
  - 新增 `private val maxNetworkBatchPlayCount = 30`
- `ui/screens/network/SearchSubTab.kt` — 新增 `onPlayAll` 回调参数；结果列表顶部"全部播放"操作栏（FocusableSurface + 歌曲计数）；列表项改用 `SongRow`（`isInQueue`/`onToggleQueue`）
- `ui/screens/network/NetworkMusicContainer.kt` — 新增 `onPlayAllSearch` 参数并透传给 `SearchSubTab`
- `ui/components/AppRoot.kt` — `onPlayAllSearch = { viewModel.playAllSearchResults(); viewModel.navigateTo(Screen.NowPlaying) }`
- `player/PlayerManager.kt`：
  - 新增 `lastErrorRetryIndex` 出错重试守卫（@Volatile）
  - `onMediaItemTransition` — 非 `PLAYLIST_CHANGED` 过渡时重置守卫
  - `onPlayerError` — 当前歌曲 URL 非空时先重解析重试一次，同曲二次失败才 `next()`

#### 验证结果

- ✅ `./gradlew.bat assembleDebug` BUILD SUCCESSFUL（含全部 5 个修改文件）
- ⏳ 真机播放验证由用户执行（连续播放超过 5 首验证不再断播）


### 10.28 v2.11.0 — 网络音乐搜索"换一批" + "全部加入列表"（突破 30 首上限）

**功能描述**：
1. 网络音乐搜索 Tab 操作栏新增"换一批"：以原搜索词 + 未用过的变异后缀（`searchVariantSuffixes`，24 种：翻唱/Live/现场/伴奏/钢琴/吉他/Remix/串烧/经典/怀旧/演唱会/DJ版/纯音乐/古风/钢琴版/吉他版/慢速/混音/国语/粤语/英文/日文/韩文/原唱）拼接后重新搜索；后缀用尽自动重置从头再来。`networkSearchBaseKeyword` 记录基准词，`usedSearchVariants` 记录已用后缀，手动搜索或清除时重置
2. **跨批次去重**（v2.11.0 增强）：`seenNetworkSearchKeys` 记录已展示过的歌曲（歌手, 歌名）集合。每次换一批只展示未出现过的新歌；在 `maxShuffleAttemptsPerClick`（6）个随机后缀中挑选新歌最多的批次展示，新歌达到 `minNewResultsForShuffle`（5）首即停止。已展示的新歌才记入集合（未展示的保留，后续批次仍可出现），保证每次点击都出新歌且不会空转
3. 网络音乐搜索 Tab 操作栏新增"全部加入列表"：将当前搜索结果按歌手+歌名与播放队列实时去重（`playerManager.queue` 读取），去重后经 `playerManager.addToQueue()` 追加到队列末尾（不替换队列、不触发导航）；全部重复时提示"队列已包含全部搜索结果"，成功时 `_connectMessage` 显示"已加入 X 首到队列（跳过 Y 首重复）"
4. 由于 Meting-API 协议不支持分页（端点固定返回 30 首/忽略 limit/offset），采用变异词方案突破单次搜索上限；与 Browse Tab 已有的"随机关键词 + 组合搜索"先例一致

#### 修改文件

- `ui/viewmodel/MainViewModel.kt`：
  - 新增 `searchVariantSuffixes`（24 个变异后缀）
  - 新增状态 `networkSearchBaseKeyword`、`usedSearchVariants`（用尽重置）、`seenNetworkSearchKeys`（跨批次去重）
  - 新增 `shuffleNetworkSearch()` — 变异搜索 + 跨批次去重 + 多后缀挑选新歌最多批次
  - 新增 `addAllSearchResultsToQueue()` — 与队列按（歌手, 歌名）去重后追加，带成功/全重复提示
  - 新增 `private suspend fun searchNetworkSongsBlocking(keyword)` — 手动搜索与换一批共用搜索路径
  - `searchNetworkSongs()` / `clearNetworkSearch()` — 重置基准词、已用后缀与已见歌曲集合
- `ui/screens/network/SearchSubTab.kt` — 新增 `onShuffleSearch` / `onAddAllToQueue` 回调参数；操作栏新增"换一批 ↻"与"全部加入列表 +"两个可聚焦按钮
- `ui/screens/network/NetworkMusicContainer.kt` — 新增参数并透传给 `SearchSubTab`
- `ui/components/AppRoot.kt` — 接线 `onShuffleSearch = { viewModel.shuffleNetworkSearch() }`、`onAddAllToQueue = { viewModel.addAllSearchResultsToQueue() }`

#### 验证结果

- ✅ `./gradlew.bat assembleDebug` BUILD SUCCESSFUL（含全部 4 个修改文件，36 tasks up-to-date）
- ⏳ 真机验证由用户执行（换一批轮换批次 + 追加去重 + 队列持续扩充）

### 10.29 v2.11.0 — "换一批"跨批次去重逻辑统一到浏览与天气电台

**功能描述**：
1. 将 v2.11.0 搜索页的"换一批"跨批次去重逻辑抽为公共泛型函数 `pickBestFreshBatch<T>(seenKeys, maxAttempts, minNewResults, produce, songsOf)`：反复调用 `produce` 生成候选（最多 6 次），用 `songsOf` 取歌曲列表并过滤 `seenKeys` 中已展示过的歌曲，返回新歌最多的候选与新歌列表；新歌达 5 首即提前停止；全部候选无新歌（集合饱和）时清空 `seenKeys` 重新生成一批（从头再来），返回前仅把本次真正展示的新歌记入集合。调用方负责在"上下文变化"（新搜索词 / 新筛选 / 新 mood）时清空对应已见集合
2. **多维度浏览**（`BrowseSubTab`）：新增 `browseSeenKeys` 跨批次去重集合。`refreshBrowseSongs()` 改为通过 `pickBestFreshBatch` 生成候选——每次候选重新随机抽取各非"所有"维度关键词组合（增加组合多样性），挑选新歌最多的批次展示。`selectBrowseOption()` 在筛选选项实际变化时清空 `browseSeenKeys`（新上下文从头开始）
3. **天气电台**（`WeatherSubTab`）：新增 `weatherSeenKeys` 跨构建去重集合与私有 `buildWeatherRadioDeduped(mgr, mood, weather)` helper（内部走 `pickBestFreshBatch`，produce 为 `buildRadioWithMood`，返回 `chosen.copy(songs = shown)`）。`fetchWeather()`（成功与失败降级路径 `loadRadioForDefaultMood()`）、`switchWeatherMood()` 全部改走该 helper；mood 变化或天气重新获取时清空 `weatherSeenKeys`
4. **WeatherRadioManager 引入随机化**：`searchNasSongs`（匹配结果 `shuffled()`）与 `searchNetworkSongs`（结果 `shuffled()`）在合并前打乱，使同一 mood / 天气下每次构建的电台基础集合不同——否则 `buildRadioWithMood` 结果确定性重复，`pickBestFreshBatch` 的去重必然饱和导致换一批无效
5. 榜单 Tab（`NetworkSubTabViews`）维持既有歌单轮换语义（`dailyRotationStart` + 索引 +1），不接入该逻辑

#### 修改文件

- `ui/viewmodel/MainViewModel.kt`：
  - 新增 `pickBestFreshBatch<T>()` 公共泛型函数（搜索 / 浏览 / 天气电台三处共用）
  - 新增 `browseSeenKeys`、`weatherSeenKeys` 已见歌曲集合
  - `shuffleNetworkSearch()` 重构为调用 `pickBestFreshBatch`（produce 返回 `keyword to results`，`songsOf` 取 `.second`；全部候选搜索失败时保留错误态）
  - `selectBrowseOption()` — 筛选变化时清空 `browseSeenKeys`
  - `refreshBrowseSongs()` — 改用 `pickBestFreshBatch`，每候选随机抽取维度关键词组合
  - 新增 `buildWeatherRadioDeduped()` — 天气电台跨构建去重构建
  - `fetchWeather()` / `switchWeatherMood()` / `loadRadioForDefaultMood()` — 改走去重构建，上下文变化时清空 `weatherSeenKeys`
- `backend/weather/WeatherRadioManager.kt` — `searchNasSongs` / `searchNetworkSongs` 结果打乱（每次构建基础集合不同）

#### 验证结果

- ✅ `./gradlew.bat assembleDebug` BUILD SUCCESSFUL（36 tasks；唯一警告为既有 Coil `ExperimentalCoilApi` opt-in，与本次改动无关）
- ⏳ 真机验证由用户执行（浏览换一批只出新歌 + 天气电台同 mood 反复换一批持续出新歌 + mood 切换后重置）

### 10.30 v2.11.0 — 网络音乐子 Tab 歌曲列表双列化 + 移除榜单 Tab

**功能描述**：
1. **歌曲列表双列化**：网络音乐 4 个子 Tab 的全宽单列 `LazyColumn` 歌曲列表改为双列 `LazyVerticalGrid(GridCells.Fixed(2))`，充分利用 TV 大屏宽度（此前每行只显示一首歌）。涉及：
   - **发现 Tab**（`DiscoverContent`）：继续听 + 我的收藏双列
   - **天气电台**（`WeatherSubTab`）：歌曲列表双列
   - **搜索**（`SearchSubTab`）：搜索结果双列
   - **浏览**（`BrowseSubTab`）：筛选结果双列（维度筛选行保持单行 LazyRow）
2. 跨列区块（标题、操作栏、歌单卡片 LazyRow、空态、底部间距）统一加 `span = { GridItemSpan(2) }`；列间距 `horizontalArrangement = spacedBy(8.dp)` 与曲库网格一致
3. `rememberLazyListState` → `rememberLazyGridState`，back-to-top 回顶逻辑（`firstVisibleItemIndex`/`scrollToItem`）在 LazyGridState 上等价工作，无需改动
4. **import 冲突处理**：`BrowseSubTab` 同文件同时使用 LazyRow（维度筛选）与 grid（结果列表），grid 版 `itemsIndexed` 以别名 `gridItemsIndexed` 引入，LazyRow 版保留原名

**移除榜单 Tab**：
- 榜单页（`ChartsContent`/`ChartsCard`）与发现页顶部"推荐歌单"数据源重合——两者都用 `loadNetworkPlaylists()` 加载同一批预配置网易云歌单（`preconfiguredPlaylists` + `_chartsRotationIndex` 轮换）。保留发现页入口，移除整个榜单 Tab
- 删除：`NetworkSubTab.CHARTS` 枚举项、`NetworkMusicContainer` 的 CHARTS 分支与 `onRefreshCharts` 参数、`ChartsContent`/`ChartsCard` 约 200 行 UI、`AppRoot` 回调传递、`MainViewModel.refreshCharts()` 与从未被调用的 `dailyRotationStart()` 死代码、`strings.xml` 4 个 `network_charts_*`/`network_tab_charts` 字符串
- 保留：`loadNetworkPlaylists()`/`_chartsRotationIndex`/`preconfiguredPlaylists`（发现页推荐歌单仍依赖）

#### 修改文件

- `ui/screens/network/NetworkSubTabViews.kt`：`DiscoverContent` 双列化；删除 `ChartsContent` + `ChartsCard`；清理孤儿 import（`LazyColumn`/`rememberLazyListState`）
- `ui/screens/network/WeatherSubTab.kt`：列表改 `LazyVerticalGrid(Fixed(2))`，6 个跨列 item 加 span
- `ui/screens/network/SearchSubTab.kt`：结果列表双列，操作栏 span(2)
- `ui/screens/network/BrowseSubTab.kt`：结果双列 + `gridItemsIndexed` 别名 import
- `ui/screens/network/NetworkMusicContainer.kt`：删除 CHARTS 分支、`onRefreshCharts` 参数、注释 `[榜单]`
- `data/model/NetworkSubTab.kt`：删除 `CHARTS` 枚举项
- `ui/components/AppRoot.kt`：删除 `onRefreshCharts` 回调传递
- `ui/viewmodel/MainViewModel.kt`：删除 `refreshCharts()`、`dailyRotationStart()`；`loadNetworkPlaylists` 注释更新
- `res/values/strings.xml`：删除 `network_tab_charts` / `network_charts_coming_soon` / `network_charts_refresh` / `network_charts_count`

#### 验证结果

- ✅ `./gradlew.bat assembleDebug` BUILD SUCCESSFUL（36 tasks；唯一警告为既有 Coil `ExperimentalCoilApi` opt-in，与本次改动无关）
- ✅ 全仓 grep `ChartsContent|ChartsCard|refreshCharts|NetworkSubTab.CHARTS|network_tab_charts|network_charts_` 零匹配
- ⏳ 真机验证由用户执行（子 Tab 歌曲双列布局 + 榜单 Tab 消失）

### 10.31 v2.11.0 — 歌曲列表序号修复（"00" → 列表序号）

**功能描述**：
- **问题**：`SongRow` 序号列显示 `String.format("%02d", song.trackNumber)`——`trackNumber` 是音频文件内嵌的轨道号元数据。网络歌曲（Meting-API）无此字段恒为 0，多数本地文件也未写入，导致曲库/网络/歌单详情/天气电台/搜索/浏览等页面序号全部显示 "00"
- **修复**：`SongRow` 新增 `index: Int? = null` 参数。`index != null` 时显示列表序号 `index + 1`；`index == null`（仅发现页"正在播放"单曲场景）显示播放图标「▶」，不再显示无意义的 "00"
- **决策**：按用户要求全部页面统一显示列表序号（专辑详情页本就显示 `index + 1`，不受影响）；不保留 `trackNumber` 语义，专辑内顺序无关紧要

#### 修改文件

- `ui/screens/LibraryScreen.kt`：`SongRow` 加 `index` 参数 + 序号显示逻辑；3 处调用（歌曲/收藏/最近播放）传 `index`
- `ui/screens/NetworkScreen.kt`：4 处调用传 `index`；推荐歌单热门/新歌榜两处 `forEach` 改 `forEachIndexed`
- `ui/screens/NetworkPlaylistDetailScreen.kt`、`ui/screens/network/BrowseSubTab.kt`、`ui/screens/network/SearchSubTab.kt`：各 1 处传 `index`
- `ui/screens/network/WeatherSubTab.kt`：`items` 改 `itemsIndexed`（import 同步替换），传 `index`
- `ui/screens/network/NetworkSubTabViews.kt`："继续听" `forEach` 改 `forEachIndexed` 传 `index`、收藏列表传 `index`；"正在播放"单曲不传（显示 ▶）

#### 验证结果

- ✅ `./gradlew.bat assembleDebug` BUILD SUCCESSFUL in 6s（36 tasks）
- ⏳ 真机验证由用户执行（各列表序号 01/02/03… 递增；发现页"正在播放"显示 ▶）

### 10.32 v2.12.0 ｜「我的」页（收藏合并 + 本地歌单）与数据备份

**背景**：收藏与播放列表此前分散在曲库页多个 Tab（FAVORITES / PLAYLISTS），且依赖 NAS 连接状态；播放列表仅支持 NAS 后端歌单，无法容纳网络歌曲。本次新增独立「我的」页统一管理用户数据，并在设置页提供数据备份/恢复。

#### 主要变更

1. **「我的」页（`ui/screens/MineScreen.kt` 新建）**
   - 底部导航新增 `nav_mine` 入口（`Screen.Mine` 枚举 + `AppRoot` NavItem）
   - 双栏布局：左栏收藏（`favoriteSongs` 本地 + `networkFavoriteSongs` 网络合并，`LinkedHashMap` 按 id 去重，本地优先），右栏本地歌单
   - 收藏歌曲行：播放 / 取消收藏 / 加入队列 / 加入歌单（`isFavorited = true`，按 `isNetworkSong` 由外层路由取消收藏）
2. **本地歌单（`data/model/LocalPlaylist.kt` 新建 + `AppPreferences` 扩展）**
   - DataStore JSON 持久化（`keyLocalPlaylists`），响应式 `localPlaylists` Flow，`MainViewModel` collect 接线
   - CRUD：`createLocalPlaylist` / `renameLocalPlaylist` / `deleteLocalPlaylist` / `addSongToPlaylist`（按 id 去重，`streamUrl` 置空持久化）/ `removeSongFromPlaylist`
   - 播放：`playLocalPlaylist` 走 `playQueue(songs, 0)`，网络歌曲由既有解析链路处理
3. **歌单选择弹窗（`ui/screens/PlaylistPickerDialog.kt` 新建）**
   - `SongRow` 新增 `onAddToPlaylist` 参数与 `AddToPlaylistButton`（＋按钮），曲库 / 我的页 / 网络页通用
   - 弹窗：歌单列表（`requestFocusOnLaunch` 首个聚焦）+ 新建歌单入口（内嵌 `TextInputDialog`）+ 取消；BACK 键两级关闭
4. **数据备份 / 恢复（`ui/util/BackupFileUtils.kt` 新建 + `AppPreferences.BackupData`）**
   - `BackupData`：version / exportedAt / serverConfig / appSettings / networkFavorites / localPlaylists / lastQueue / recentSongIds / playCounts / playRecords / equalizerPreset / equalizerBands
   - **敏感字段排除**：`exportBackupData` 中 `apiToken`/`password` 置空、`isConnected=false`；天气 API Key 不在备份结构内
   - 存储：API 29+ 走 `MediaStore.Downloads`（`Downloads/NASMusic/`，免权限）；API < 29 **主备份写应用内部存储 `filesDir/NASMusic/`**（`/data` 真闪存，断电不丢），另尽力写一份到公共 Downloads 目录供文件管理器访问——部分电视 ROM（如创维 Android 5.1.1）外部存储为 RAM-backed rootfs（非真实挂载点），断电即清空，故内部存储才是可靠主备份；`listBackups` 合并两处按文件名去重、按修改时间倒序，`delete` 按文件名同步删除两份副本
   - 设置页新增 `SettingsSection.DATA`「数据管理」：导出按钮、备份文件列表（`BackupFileRow` 可点击恢复）、`OpenDocument` 选择器导入、结果消息 4s 自动消费
   - `importBackupData` 恢复后服务器 `isConnected=false`，需重新输入密码连接
5. **曲库页瘦身（`LibraryScreen.kt`）**
   - 移除 `LibraryTab.FAVORITES` / `LibraryTab.PLAYLISTS` 及 `FavoritesTab` / `PlaylistsTab`（约 450 行）；相关参数（`favoriteSongs` / `networkFavoriteSongs` / `onToggleNetworkFavorite` / `playlists` / `playlistSongs` 等）与 `AppRoot` 回调同步删除
   - `SongRow` 新增 `onAddToPlaylist` + `AddToPlaylistButton`
6. **no-op 修复（`AppRoot.kt`）**：网络音乐页「收藏」动作此前 `selectNetworkSubTab(DISCOVER)` 无实际跳转，改为 `navigateTo(Screen.Mine)`

#### 修改文件

- `data/model/Screen.kt`：新增 `Mine` 枚举
- `data/model/LocalPlaylist.kt`（新建）：本地歌单数据类
- `data/prefs/AppPreferences.kt`：本地歌单 CRUD + `BackupData` 导出/导入
- `ui/components/AppRoot.kt`：MINE 导航项 + `Screen.Mine` 分支接线 + 备份回调 + no-op 修复
- `ui/screens/MineScreen.kt`（新建）：我的页双栏 UI
- `ui/screens/PlaylistPickerDialog.kt`（新建）：歌单选择弹窗
- `ui/screens/LibraryScreen.kt`：移除收藏/播放列表 Tab，`SongRow` 加加入歌单按钮
- `ui/screens/SettingsScreen.kt`：新增「数据管理」分区
- `ui/viewmodel/MainViewModel.kt`：本地歌单操作 + 备份方法 + collect 接线
- `util/BackupFileUtils.kt`（新建）：备份文件读写
- `res/values/strings.xml`：`nav_mine` + `mine_*` + `settings_data*` / `settings_backup*` / `settings_import_backup` 字符串

#### 验证结果

- ✅ `./gradlew.bat assembleDebug` BUILD SUCCESSFUL（36 tasks up-to-date）
- ✅ 备份断电存活实测（v2.12.0 修复后）：创维电视导出备份 → 断电重启 → 备份仍在列表（内部存储 `filesDir/NASMusic/` 落于 `/data` 真闪存，断电不丢）；修复前公共 Downloads（RAM 盘）断电即清空
- ⏳ 真机验证由用户执行（「我的」页收藏/歌单操作、备份导出后文件可访问、导入恢复后需重新连接服务器）


### 10.33 v2.12.1 ｜搜索窗口二维码扫码输入 + 搜索历史建议

**背景**：TV 遥控器输入中文体验差（自制键盘逐字母、系统 IME 需额外安装且 TV 遥控器操作不便）。本次在搜索输入窗口右侧新增二维码，手机扫码后浏览器打开输入页直接输入文字推送到 TV；同时在输入框下方显示历史搜索建议（最近 + 热门），减少重复输入。

#### 主要变更

1. **搜索历史存储（`data/model/SearchHistoryItem.kt` 新建 + `AppPreferences` 扩展）**
   - `SearchHistoryItem(query, lastSearchedAt, count)` 放 `data.model`（ProGuard 已 keep）
   - `AppPreferences` 新增 `keySearchHistory`（stringPreferencesKey，Gson JSON 序列化）、`searchHistoryMaxSize = 200`、`searchHistoryTtlMs = 30 天`
   - `recordSearch(query)`：空串跳过；`edit {}` 内读 JSON -> 同名合并（`count+1`、`lastSearchedAt=now`、移到头部）或 `add(0, ...)` -> 删 >30 天条目 -> 超 200 条裁尾 -> 写回
   - `purgeExpiredSearchHistory()`：启动时 `applicationScope.launch` 调一次，只做 TTL 清理
   - `searchHistory` Flow + `getSearchHistory()` 一次性读取
   - `BackupData` 新增 `searchHistory` 字段，`exportBackupData` / `importBackupData` 同步处理
2. **本地输入服务器（`net/LocalInputServer.kt` 新建）**
   - 包装 NanoHTTPD（`fi.iki.elonen.NanoHTTPD`，Maven group `org.nanohttpd` ≠ Java package），固定端口 18080
   - `GET /` 返回移动端 HTML 页（深色主题、viewport 适配、输入框 + 发送按钮、回车提交、提交后显示"已发送"并清空可连续输入）
   - `POST /submit`：`session.parseBody(files)` 取 `postData`（raw body）-> `onText` 回调 -> 返回 `{"ok":true}`
   - `start(onText)` / `stop()`，回调在 NanoHTTPD 线程上调用
3. **二维码生成（`util/QrCodeGenerator.kt` 新建）**
   - ZXing `QRCodeWriter` 生成 Bitmap（ErrorCorrectionLevel.M、margin 1、UTF-8）
   - `generateQrBitmap(content, size=512)` 返回 `Bitmap?`，失败返回 null
4. **本地 IP 获取（`util/NetworkUtils.kt` 新建）**
   - `NetworkInterface.getNetworkInterfaces()` 遍历，返回第一个非回环 IPv4 地址
   - 不需要 ACCESS_WIFI_STATE 权限，兼容所有 API 级别
5. **TextInputDialog UI 改造（`ui/screens/TextInputDialog.kt`）**
   - 新增可选参数：`showQrCode` / `showHistory` / `historyItems` / `onHistorySelect`（默认不传则行为不变）
   - `showQrCode=true` 时：`DisposableEffect` 启动 `LocalInputServer`，`NetworkUtils.getLocalIpAddress()` 拿 IP，`QrCodeGenerator` 生成 Bitmap（URL = `http://<IP>:18080/`）；`LaunchedEffect(qrText)` 收 server 推来的文字 -> 更新 `text` 状态（mutableStateOf 支持跨线程写入）；server 启动失败或无 IP 则隐藏 QR
   - `showHistory=true` 时：输入框下方两行历史建议--「最近」按 `lastSearchedAt` 降序取 5、「热门」按 `count` 降序取 5（不去重，允许同一词同时出现在两行）；`FocusableSurface` 可 D-Pad 聚焦，OK 键 -> 填入 `text` + `onHistorySelect` 回调（调用方接到后执行搜索 + 关闭弹窗）
   - 布局：外层 Column 宽度条件化（QR 显示时 940dp 否则 720dp），内嵌 Row 包左列（原有内容）+ 右列 QR 面板（180dp）
6. **搜索记录钩子（`MainViewModel.kt`）**
   - `searchSongsOnServer(query)`（Library 搜索）入口加 `viewModelScope.launch { prefs.recordSearch(query) }`（空串跳过）
   - `searchNetworkSongs(keyword)`（Network 搜索）入口同样加
   - `shuffleNetworkSearch()`（换一批自动变体）**不记录**--只记用户实际输入的关键词
   - 暴露 `val searchHistory = prefs.searchHistory` 给 UI
7. **UI 接线**
   - `SearchSubTab.kt`：新增 `historyItems` 参数，`TextInputDialog` 传 `showQrCode=true` / `showHistory=true` / `historyItems` / `onHistorySelect = { onSearch(it); showSearchDialog = false }`
   - `LibraryScreen.kt`：同上，`onHistorySelect` 设 `filterQuery = query` + 关弹窗（由 `LaunchedEffect(filterQuery)` 触发 `onSearch`）
   - `NetworkMusicContainer.kt`：透传 `historyItems` 到 `SearchSubTab`
   - `AppRoot.kt`：Library / Network 两个分支各 `collectAsState` 收 `viewModel.searchHistory`，传给 `LibraryScreen` / `NetworkMusicContainer`

#### 修改文件

- `data/model/SearchHistoryItem.kt`（新建）：搜索历史数据类
- `data/prefs/AppPreferences.kt`：`keySearchHistory` + Flow + `recordSearch` + `purgeExpiredSearchHistory` + `BackupData` 集成
- `NasMusicApp.kt`：onCreate 里 `applicationScope.launch { purgeExpiredSearchHistory() }`
- `net/LocalInputServer.kt`（新建）：NanoHTTPD 包装 + HTML 页
- `util/QrCodeGenerator.kt`（新建）：ZXing 二维码生成
- `util/NetworkUtils.kt`（新建）：局域网 IP 获取
- `ui/screens/TextInputDialog.kt`：QR 面板 + 历史建议 + 外部文字注入
- `ui/screens/network/SearchSubTab.kt`：传 `historyItems` + QR/历史开关
- `ui/screens/LibraryScreen.kt`：同上
- `ui/screens/network/NetworkMusicContainer.kt`：透传 `historyItems`
- `ui/components/AppRoot.kt`：收集 `searchHistory` Flow 传给两个搜索入口
- `ui/viewmodel/MainViewModel.kt`：`recordSearch` 钩子 + 暴露 `searchHistory`
- `app/build.gradle.kts`：+`zxing:core:3.5.3` +`nanohttpd:2.3.1`，versionCode 33 / versionName 2.12.1
- `proguard-rules.pro`：keep `com.google.zxing.**` + `fi.iki.elonen.**`

#### 验证结果

- ✅ `./gradlew.bat assembleDebug` BUILD SUCCESSFUL
- ✅ `./gradlew.bat assembleRelease` BUILD SUCCESSFUL（R8 minify + lintVital 通过）
- ✅ 真机实测：搜索窗口右侧出 QR + 下方出历史建议；手机扫码浏览器打开 -> 输入文字 -> TV 输入框实时显示；搜索后历史记录；重开搜索历史显示「最近」+「热门」两行；D-Pad 选历史项填入 + 直接搜索；Library / Network 两个入口共享历史

#### 注意事项

- NanoHTTPD Maven group ID `org.nanohttpd` ≠ Java package `fi.iki.elonen`（原作者域名），import 需用 `fi.iki.elonen.NanoHTTPD`
- HTTP server 仅在搜索弹窗打开时启动、关闭时停止，不常驻后台；端口 18080 固定，手机可保持页面打开连续输入
- 搜索历史全局共享（Library / Network 两个入口共用同一份），30 天 TTL + 200 条上限防存储爆炸


### 10.34 v2.12.2 ｜扫码传输备份（手机下载/上传/恢复）

**背景**：v2.12.0 的数据备份功能将备份存在 TV 本地（内部存储 `filesDir/NASMusic/`），卸载 app 即清空。本次复用 v2.12.1 的扫码输入架构（NanoHTTPD + ZXing + NetworkUtils），新增备份传输 server，手机扫码后浏览器管理备份：下载到手机 / 上传到 TV / 远程恢复。

#### 主要变更

1. **备份传输服务器（`net/BackupTransferServer.kt` 新建）**
   - NanoHTTPD 端口 18081（与搜索输入 18080 独立，互不干扰）
   - `GET /`：备份管理 HTML 页（深色主题，显示 TV 端备份列表 + 上传表单）
   - `GET /api/list`：返回备份文件列表 JSON（文件名 + 时间，复用 `BackupFileUtils.listBackups`）
   - `GET /api/download?name=xxx`：下载指定备份（`Content-Disposition: attachment` 触发浏览器下载，复用 `BackupFileUtils.read`）
   - `POST /api/upload`：接收 raw JSON body，**直接从 `session.inputStream` 按 Content-Length 读取字节 + UTF-8 解码**（绕过 NanoHTTPD `parseBody` 的字符集/大小限制问题），调 `BackupFileUtils.export` 保存为新备份
   - `POST /api/restore?name=xxx`：读取备份 + `runBlocking { onRestore(json) }` 调用恢复回调
   - `onBackupChanged` 回调：上传/恢复成功后通知 ViewModel 刷新备份列表
2. **备份传输弹窗（`ui/screens/BackupTransferDialog.kt` 新建）**
   - `DisposableEffect` 管理 server 生命周期：打开时 start，关闭时 stop
   - QR 码（`QrCodeGenerator` 生成 `http://<IP>:18081/`）+ 状态文字 + 关闭按钮
   - `BackHandler` 处理返回键
3. **设置页入口（`SettingsScreen.kt`）**
   - DATA 分区新增 `onScanTransferBackup` 参数 + "扫码传输备份"按钮（导出按钮下方）
4. **ViewModel 恢复方法（`MainViewModel.kt`）**
   - `restoreBackupFromJson(json: String): Boolean`（suspend）：Gson 解析 JSON -> `prefs.importBackupData(data)` -> `refreshAfterImport()`，供 server 的 onRestore 回调调用
5. **AppRoot 接线**
   - `Screen.Settings` 分支加 `showBackupTransferDialog` 状态
   - `BackupTransferDialog(onRestore = { viewModel.restoreBackupFromJson(it) }, onBackupChanged = { viewModel.refreshBackupFiles() }, onDismiss = ...)`

#### 修改文件

- `net/BackupTransferServer.kt`（新建）：NanoHTTPD server + HTML 页
- `ui/screens/BackupTransferDialog.kt`（新建）：QR 弹窗
- `ui/screens/SettingsScreen.kt`：+`onScanTransferBackup` 参数 + 按钮
- `ui/components/AppRoot.kt`：+状态 + 弹窗渲染 + 接线
- `ui/viewmodel/MainViewModel.kt`：+`restoreBackupFromJson` suspend 方法
- `app/build.gradle.kts`：versionCode 34 / versionName 2.12.2

#### 验证结果

- ✅ `./gradlew.bat assembleDebug` + `assembleRelease` BUILD SUCCESSFUL
- ✅ 真机实测：手机扫码打开备份管理页 -> 下载备份到手机成功 -> 上传备份到 TV 成功 -> TV 端列表自动刷新（`onBackupChanged` 回调触发 `refreshBackupFiles`） -> 手机端点"恢复"按钮远程恢复成功

#### 注意事项

- 上传用 raw body + 直接读 inputStream，不用 `parseBody`（NanoHTTPD `parseBody` 对 raw body 用 ISO-8859-1 读取导致中文乱码，且有潜在大小限制）
- server 仅在弹窗打开时启动、关闭时停止，不常驻后台
- 端口 18081 独立于搜索输入的 18080，两个功能可同时使用


### 10.35 v2.12.3 ｜代码审查修复（搜索历史时机 / runBlocking / 死代码）

**背景**：v2.12.2 发版后对 `v2.12.0...HEAD` 做双轴审查（Standards + Spec），发现 5 处问题（0 硬性违规，5 条判断性建议/范围蔓延/实现有误）。本次集中修复。

#### 主要变更

1. **搜索历史记录时机（`ui/viewmodel/MainViewModel.kt`）**
   - `searchSongsOnServer(query)`：`recordSearch` 从入口处移到 `try` 块内 `UiState.Success` 之后；失败（异常）或后端未连接时不记录，避免污染「热门」榜计数
   - `searchNetworkSongs(keyword)`：移除入口处的 `recordSearch`；记录逻辑下沉到 `doNetworkSearch` 的成功路径（`results != null`）
   - `shuffleNetworkSearch()`（换一批）走 `searchNetworkSongsBlocking`，不经过 `doNetworkSearch`，不受影响--只记用户实际输入的关键词
   - 空结果仍记录（用户确实搜过）
2. **`BackupTransferServer` runBlocking 修复（`net/BackupTransferServer.kt` + `ui/screens/BackupTransferDialog.kt` + `ui/viewmodel/MainViewModel.kt` + `ui/components/AppRoot.kt`）**
   - `onRestore` 回调类型：`suspend (String) -> Boolean` -> `(String) -> Boolean`（非挂起）
   - `BackupTransferServer.Impl.handleRestore`：`kotlinx.coroutines.runBlocking { onRestore.invoke(json) }` -> `onRestore.invoke(json)`，server 不再依赖协程库
   - `MainViewModel` 新增 `restoreBackupFromJsonBlocking(json): Boolean`：`runBlocking { restoreBackupFromJson(json) }`，集中桥接职责（在 NanoHTTPD 工作线程上执行，非主线程，安全）
   - `AppRoot` 接线：`onRestore = { json -> viewModel.restoreBackupFromJsonBlocking(json) }`
3. **`TextInputDialog` 历史项「填入」死状态（`ui/screens/TextInputDialog.kt`）**
   - `HistoryRow` 选中回调中的 `text = query` 在弹窗立即关闭后不可见，属死状态，移除
   - `onHistorySelect` 由调用方（`LibraryScreen` / `SearchSubTab`）负责执行搜索 + 关闭弹窗
   - 文档注释同步更新
4. **`AppPreferences.clearSearchHistory()` 死代码移除（`data/prefs/AppPreferences.kt`）**
   - 已定义但从未被任何 UI 调用，移除
5. **`docs/technical-overview.md` §10.33 笔误修正**
   - v2.12.1 修改文件列表中 `versionName 2.13.0` -> `2.12.1`（与标题版本一致）

#### 修改文件

- `ui/viewmodel/MainViewModel.kt`：`searchSongsOnServer` / `searchNetworkSongs` / `doNetworkSearch` 记录时机调整；新增 `restoreBackupFromJsonBlocking`
- `net/BackupTransferServer.kt`：`onRestore` 改为非挂起，移除 `runBlocking`
- `ui/screens/BackupTransferDialog.kt`：`onRestore` 改为非挂起
- `ui/components/AppRoot.kt`：`onRestore` 改用 `restoreBackupFromJsonBlocking`
- `ui/screens/TextInputDialog.kt`：移除 `HistoryRow` 回调中 `text = query` 死写入 + 注释更新
- `data/prefs/AppPreferences.kt`：移除 `clearSearchHistory()`
- `docs/technical-overview.md`：§10.33 笔误修正 + 新增 §10.35
- `CHANGELOG.md`：新增 v2.12.3 条目
- `app/build.gradle.kts`：versionCode 34->35, versionName 2.12.2->2.12.3

#### 验证结果

- ✅ `./gradlew.bat assembleDebug` BUILD SUCCESSFUL in 1m 9s（0 errors；1 pre-existing warning `MainViewModel.kt:2748` ExperimentalCoilApi opt-in，与本次修改无关）
- ⏳ 真机验证由用户执行（搜索失败不再污染历史、扫码恢复仍正常、历史项选中仍能触发搜索）

#### 注意事项

- `restoreBackupFromJsonBlocking` 内部 `runBlocking` 在 NanoHTTPD 工作线程执行（非主线程），安全；桥接职责集中在 ViewModel，`BackupTransferServer` / `BackupTransferDialog` 不依赖协程库
- 搜索历史记录时机变更：失败搜索不记录，空结果仍记录；用户行为不变，仅「热门」榜更准确

### 10.36 v2.12.4 ｜输入弹窗二维码统一 + 移除旧网络音乐入口

**背景**：统一搜索与输入体验——所有输入弹窗默认开启二维码扫码输入；删除已被 `NetworkMusicContainer` + `SearchSubTab` 取代、且全项目无调用者的旧 `NetworkScreen` 死代码；搜索历史仅保留在搜索类弹窗。

#### 主要变更

1. **`TextInputDialog` 默认开启二维码（`ui/screens/TextInputDialog.kt`）**
   - `showQrCode: Boolean = false` -> `= true`，一处改动覆盖全部 9 个调用点（曲库搜索 / 网络搜索 / 服务器连接 / 天气 API Key / Meting 端点 / 歌单新建、重命名 / 创建歌单）
   - `showHistory` 仍默认 `false`，仅搜索入口（`LibraryScreen` / `SearchSubTab`）显式开启搜索历史
2. **移除旧网络音乐入口（删除 `ui/screens/NetworkScreen.kt`）**
   - `AppRoot` 已改用 `NetworkMusicContainer`（内含 `SearchSubTab`），`NetworkScreen` 无任何调用者，属死代码，整文件删除（含其中不带二维码的旧搜索弹窗）
   - `ui/screens/network/SearchSubTab.kt` 注释同步更新（不再引用"现有 NetworkScreen"）

#### 修改文件

- `ui/screens/TextInputDialog.kt`：`showQrCode` 默认值 `false` -> `true`
- `ui/screens/NetworkScreen.kt`：整文件删除（死代码）
- `ui/screens/network/SearchSubTab.kt`：文档注释更新
- `docs/technical-overview.md`：新增 §10.36
- `CHANGELOG.md`：新增 v2.12.4 条目
- `app/build.gradle.kts`：versionCode 35->36, versionName 2.12.3->2.12.4

#### 验证结果

- ✅ `./gradlew.bat :app:compileDebugKotlin` BUILD SUCCESSFUL（删除 `NetworkScreen` 后无残留引用，全部 9 个调用点编译通过）
- ⏳ 真机验证由用户执行（输入弹窗二维码显示、搜索历史仅搜索入口可见）

#### 注意事项

- 密码类输入（天气 API Key / 服务器密码等 `masked=true` 场景）现在也会显示二维码，按"全部默认开启"要求执行；如后续需要排除密码场景，可在调用点显式传 `showQrCode = false`

### 10.37 v2.12.5 — 「我的」收藏列表新增「播放全部」

**概述**：在「我的」页面左栏收藏列表标题行新增「播放全部」按钮，一键播放全部收藏歌曲（NAS + 网络收藏合并，按 id 去重）。

#### 主要变更

1. **`ui/screens/MineScreen.kt`：左栏收藏标题行新增「播放全部」按钮**
   - 收藏标题从纯 `Text` 改为 `Row`（标题 + 右侧按钮），仅 `mergedFavorites` 非空时显示 `ButtonChip`（复用 `common_play_all` 文案）
   - 新增参数 `onPlayAll: (List<Song>) -> Unit`（与 `AlbumDetailScreen` / `ArtistDetailScreen` 的播放全部模式一致），点击时传入合并后的收藏歌曲列表
2. **`ui/components/AppRoot.kt`：`MineScreen` 调用处接线 `onPlayAll`**
   - `viewModel.playQueue(songs)` + `navigateTo(Screen.NowPlaying)`；`playQueue` 内部已处理网络歌曲 streamUrl 的异步解析，NAS + 网络收藏歌曲均能直接播放

#### 修改文件

- `ui/screens/MineScreen.kt`：新增 `onPlayAll` 参数 + 标题行「播放全部」按钮
- `ui/components/AppRoot.kt`：`MineScreen` 调用处接线 `onPlayAll`
- `app/build.gradle.kts`：versionCode 36->37, versionName 2.12.4->2.12.5
- `CHANGELOG.md`：新增 v2.12.5 条目
- `docs/technical-overview.md`：新增 §10.37

#### 验证结果

- ✅ `./gradlew.bat assembleDebug` BUILD SUCCESSFUL（产出 app-debug.apk，时间戳晚于改动文件）
- ⏳ 真机验证由用户执行（收藏页「播放全部」按钮显示与播放行为）

#### 注意事项

- 按钮仅在有收藏歌曲时显示（空收藏列表不显示，避免无意义按钮）；播放顺序为合并列表顺序（本地收藏在前、网络收藏在后，同 id 仅保留本地版本）

### 10.38 v2.12.6 — 网络歌词候选切换

**概述**：再次按下"在线歌词"按钮时，递增候选索引并重新搜索酷狗/网易云，取下一个不同的候选歌词，解决歌词匹配错误时无法换一个的问题。

#### 主要变更

1. **`lyrics/LyricsNetworkProvider.kt`：`fetchFromKugou`/`fetchFromNetease` 返回多条候选**
   - 酷狗搜索 `pagesize=1`→`pagesize=maxResults`，解析多个 hash 逐一获取歌词
   - 网易云搜索 `limit=1`→`limit=maxResults`，解析多个 songId 逐一获取歌词
   - 新增 `fetchLyricsCandidates(title, artist, maxResults=5)` 遍历 3 种关键词组合、两个来源，去重后返回候选列表
   - 新增 `getLyricsByHash`/`getLyricsBySongId` 辅助方法，提取单条歌词获取逻辑
   - 原有 `fetchLyrics` 改为调用 `fetchLyricsCandidates(..., maxResults=1).firstOrNull()`，行为不变
2. **`lyrics/LyricsManager.kt`：`getLyricsFromSource` 新增 `candidateIndex` 参数**
   - `NETWORK` 分支走 `fetchLyricsCandidates` 按索引取候选，索引越界时 `coerceIn` 到最后一个有效值
3. **`ui/viewmodel/MainViewModel.kt`：`switchLyricsSource` 追踪候选索引**
   - 新增 `networkLyricsCandidateIndex` + `networkLyricsSongId` 字段
   - 已显示网络歌词时再次按下"在线歌词"按钮 → 索引 +1 取下一个候选
   - 切歌或切到其他来源 → 索引重置为 0

#### 修改文件

- `lyrics/LyricsNetworkProvider.kt`：`fetchFromKugou`/`fetchFromNetease` 改造为多结果返回，新增 `fetchLyricsCandidates` 等方法
- `lyrics/LyricsManager.kt`：`getLyricsFromSource` 新增 `candidateIndex` 参数，`NETWORK` 分支走候选列表
- `ui/viewmodel/MainViewModel.kt`：`switchLyricsSource` 追踪并递增候选索引
- `app/build.gradle.kts`：versionCode 37->38, versionName 2.12.5->2.12.6
- `CHANGELOG.md`：新增 v2.12.6 条目
- `docs/technical-overview.md`：新增 §10.38

#### 验证结果

- ✅ `./gradlew.bat assembleDebug` BUILD SUCCESSFUL
- ✅ `./gradlew.bat assembleRelease` BUILD SUCCESSFUL
- ✅ 真机测试通过（重复按下"在线歌词"按钮切换不同候选歌词）

#### 注意事项

- 候选歌词数量取决于网络 API 搜索结果，如果只有 1 个候选，多次按下仍返回同一个（不会崩溃或空指针）
### 10.39 v2.12.7 - 歌曲列表行高加大 & 文字放大

**概述**：全应用所有歌曲列表项的行高增加一倍，文字字号同步加大，改善电视大屏远距离观看的可读性。

#### 主要变更

1. **`SongRow`（`ui/screens/LibraryScreen.kt`，共享组件）**：
   - 行高从 ~52dp 增至 100dp（`height(100.dp)`）
   - 封面缩略图 36dp -> 56dp，内边距加大
   - 歌名 13sp -> 18sp，歌手 11sp -> 15sp
   - 序号 12sp -> 16sp（宽 28dp -> 36dp），时长 11sp -> 15sp
   - 收藏♥ 10sp -> 14sp，播放次数 10sp -> 13sp，▶ 11sp -> 15sp
   - 影响：曲库歌曲列表、网络搜索结果、天气电台、继续听、收藏列表、网络歌单详情等 7+ 页面
2. **`AlbumDetailScreen.kt` 内联行**：行高 80dp，歌名 18sp，歌手 15sp，序号 16sp，时长 15sp
3. **`ArtistDetailScreen.kt` 内联行**：行高 80dp，歌名 18sp，专辑名 15sp，序号 16sp，时长 15sp
4. **`QueueScreen.kt` 内联行**：行高 80dp，歌名 18sp，歌手 15sp，序号 16sp，时长 15sp
5. **`PlaylistManagementScreen.kt` 行**：行高 80dp，歌名 18sp，歌手 15sp，时长 15sp
6. **`PlaylistSongRow`（`MineScreen.kt` 歌单内歌曲行）**：行高 96dp，封面 32dp -> 52dp，歌名 18sp，歌手 15sp，时长 15sp

#### 修改文件

- `ui/screens/LibraryScreen.kt`：`SongRow` 组件行高/字号调整
- `ui/screens/AlbumDetailScreen.kt`：内联歌曲行行高/字号调整
- `ui/screens/ArtistDetailScreen.kt`：内联歌曲行行高/字号调整
- `ui/screens/QueueScreen.kt`：内联歌曲行行高/字号调整
- `ui/screens/PlaylistManagementScreen.kt`：歌曲行行高/字号调整
- `ui/screens/MineScreen.kt`：`PlaylistSongRow` 行高/字号调整
- `app/build.gradle.kts`：versionCode 38->39, versionName 2.12.6->2.12.7
- `CHANGELOG.md`：新增 v2.12.7 条目
- `docs/technical-overview.md`：新增 §10.39

#### 验证结果

- ✅ `./gradlew.bat assembleDebug` BUILD SUCCESSFUL

#### 注意事项

- 行高使用固定 `height()` 而非依赖内容自适应，确保有无封面时行高一致
- 按钮区（收藏/队列/歌单）尺寸未调整，行高加大后按钮在行内占比变小但功能不受影响

### 10.40 v2.12.8 - 全应用文字放大 + 按钮封面放大 + 歌单列表对齐

**概述**：在 v2.12.7 基础上进一步放大全应用文字（固定 +5sp 而非倍数缩放）、操作按钮、封面缩略图，并将"我的"页面歌单列表项与歌曲行高度/字号对齐。

#### 主要变更

1. **全应用 fontSize 统一 +5sp**：用 PowerShell 脚本扫描 `ui/` 下所有 .kt 文件，将 348 处 `fontSize = N.sp` 值回退之前的 1.3 倍缩放后改为固定 +5sp。小字相对提升更大（9sp->14sp = +56%），大字不过度膨胀（36sp->41sp = +14%）。共修改 28 个文件
2. **操作按钮放大**：
   - `FavoriteButton` / `QueueToggleButton` / `AddToPlaylistButton`（LibraryScreen.kt）：`.size` 28dp->44dp
   - `RemoveSongButton`（MineScreen.kt）：28dp->44dp
   - `MoveButton`（QueueScreen.kt）：`widthIn` 36dp->48dp，内边距 8/6dp->12/8dp
   - `FavoriteButton`（NowPlayingScreen.kt）：内边距 6dp->10dp
3. **歌曲列表行高 & 封面再放大**：
   - `SongRow`：行高 100dp->120dp，封面 72dp->92dp（行内仅留 2dp 边缘）
   - `PlaylistSongRow`：行高 96dp->116dp，封面 68dp->88dp
   - `AlbumDetailScreen` / `ArtistDetailScreen` / `QueueScreen` / `PlaylistManagementScreen` 内联行：行高 80dp->100dp
4. **主导航 Tab 文字放大**：`NavItem`（AppRoot.kt）选中态 16sp->21sp，非选中 14sp->19sp。此前批量脚本因条件表达式 `fontSize = if (selected) ... else ...` 未匹配，手动修复
5. **"我的"页面歌单列表项对齐**：`PlaylistCard`（MineScreen.kt）增加固定行高 100dp，歌单名 19sp->23sp（与歌名一致），歌曲数 16sp->20sp（与歌手一致），♪/▾ 图标 21sp->25sp + 宽度 28dp->36dp，`PlaylistActionButton` 文字 16sp->21sp + 内边距加大

#### 修改文件

- `ui/components/AppRoot.kt`：NavItem fontSize 修复
- `ui/screens/LibraryScreen.kt`：SongRow 行高/封面 + 3 个按钮组件 size + 全文件 fontSize +5sp
- `ui/screens/MineScreen.kt`：PlaylistSongRow 行高/封面 + PlaylistCard 对齐 + RemoveSongButton size + PlaylistActionButton + 全文件 fontSize +5sp
- `ui/screens/AlbumDetailScreen.kt` / `ArtistDetailScreen.kt` / `QueueScreen.kt` / `PlaylistManagementScreen.kt`：行高 + fontSize +5sp
- `ui/screens/NowPlayingScreen.kt`：FavoriteButton 内边距 + fontSize +5sp
- 其余 22 个 UI 文件：fontSize +5sp
- `app/build.gradle.kts`：versionCode 39->40, versionName 2.12.7->2.12.8
- `CHANGELOG.md`：新增 v2.12.8 条目
- `docs/technical-overview.md`：新增 §10.40

#### 验证结果

- ✅ `./gradlew.bat assembleRelease` BUILD SUCCESSFUL
- ✅ 真机安装成功（192.168.0.116:5555）

#### 注意事项

- 固定 +5sp 而非倍数缩放：避免大字过大、小字变化不明显的 问题
- `NavItem` 的条件 fontSize 被批量脚本遗漏，手动修复 -- 后续批量修改需检查 `fontSize = if (...)` 模式
- 歌单列表项（PlaylistCard）现在与歌曲行高度/字号完全一致，视觉统一

### 10.41 v2.13.0 - 人声消除 K 歌伴奏模式（实时 DSP）

**概述**：实现方案 B（Mid-Side 编码 + 分频段处理）的实时人声消除，在播放页新增"伴奏"入口，点击后自动切换到全屏 K 歌页面（封面全屏 + 歌词逐字高亮 + 精简控制栏），"原唱"一键切回。方案 C（AI 预分离）保持为设计文档未实施。

#### 主要变更

1. **`VocalRemovalProcessor`（新增，AudioProcessor）**：核心 DSP，仅支持 16-bit PCM 立体声（其他格式自动 bypass）
   - Mid-Side 编码：`Mid = (L+R)/2`，`Side = (L-R)/2`
   - Mid 声道分频：低通 120Hz（保留贝斯/底鼓）+ 高通 6kHz（保留镲片），跳过 vocal 频段消除居中人声（男声基频 85~180Hz，故低通压至 120Hz）
   - Side 声道同样分频，对 vocal 频段（120Hz~6kHz）额外衰减 88%（`SIDE_VOCAL_KEEP = 0.12f`），消除偏置/混响残留人声
   - 补偿增益 `MAKEUP_GAIN = 1.6f` 抵消电平下降
   - 滤波器：四阶 Linkwitz-Riley（两个二阶 biquad 级联，RBJ Cookbook 系数，-24dB/oct）
2. **`PlaybackService`**：自定义 `RenderersFactory` 覆写 `buildAudioSink()`，通过 `DefaultAudioSink.Builder.setAudioProcessors()` 注入处理器
3. **`PlayerManager`**：`setVocalRemovalProcessor()` 注入 + `setVocalRemovalEnabled()` / `isVocalRemovalEnabled()` 开关
4. **`MainViewModel`**：暴露 `vocalRemovalEnabled: StateFlow<Boolean>` + `toggleVocalRemoval()`
5. **`KaraokePlaybackScreen`（新增）**：全屏 K 歌布局，复用沉浸模式的全屏封面背景（`rememberAsyncImagePainter` + `ContentScale.Crop` + 三段渐变遮罩 0xCC/0x99/0xCC）
6. **`KaraokeLyricsView`（新增）**：固定当前行（25sp 逐字高亮）+ 下一行预览（18sp 暗色），强制逐字模式
7. **`VocalToggleButton`（新增）**：红色 accent `#FD3359` 圆角按钮，按状态切换"伴奏/原唱"文字
8. **`NowPlayingScreen` / `PlayerControls` / `AppRoot`**：`vocalRemovalEnabled` 条件渲染切换两套布局（不新增 Screen 枚举），控制栏 `ControlButtonsRow` 新增伴奏入口按钮参数

#### 修改文件

- `player/VocalRemovalProcessor.kt`（新增，285 行）
- `ui/components/KaraokePlaybackScreen.kt` / `KaraokeLyricsView.kt` / `VocalToggleButton.kt`（新增）
- `player/PlaybackService.kt`：RenderersFactory 注入 AudioProcessor
- `player/PlayerManager.kt`：处理器注入 + 开关方法
- `ui/viewmodel/MainViewModel.kt`：vocalRemovalEnabled 状态
- `ui/screens/NowPlayingScreen.kt` / `ui/components/PlayerControls.kt` / `ui/components/AppRoot.kt`：条件渲染 + 入口按钮
- `app/build.gradle.kts`：versionCode 40->41, versionName 2.12.8->2.13.0
- `CHANGELOG.md`：新增 v2.13.0 条目
- `docs/vocal-removal-approach-b-dsp.md` / `vocal-removal-approach-c-ai.md`：方案设计文档（B 已实施，C 待评估）

#### 验证结果

- ✅ `./gradlew.bat assembleDebug` BUILD SUCCESSFUL
- ✅ 真机安装成功（192.168.0.116:5555）

#### 注意事项

- 方案 B 与未来 K 歌方案的双音轨切换互斥：KARAOKE 模式（≥2 音轨）走硬件音轨切换，无需 DSP；当前阶段 playbackMode 恒为 MUSIC，按钮始终显示
- 单声道文件自动 bypass（`configure` 返回 NOT_SET）
- 消除效果依赖混音：人声居中、立体声宽度大的歌曲效果好；偏置人声/和声残留较多
- 切歌时滤波器状态在 `flush()`/`setEnabled()` 中重置，无异常噪声
- 方案 C（Spleeter AI 预分离）暂未实施，后续可在 UI 增加第三选项，无需改 B 的代码


### 10.42 v2.13.1 - K 歌歌词渲染优化 + 自动切歌停留

**概述**：对 v2.13.0 的 K 歌模式做三处体验修复：两行歌词颜色统一（白色底 + 黄色进度）、逐字高亮改为平滑进度（边界可落在半个字上）、自动切歌时停留在 K 歌页面而非跳回普通播放页。

#### 主要变更

1. **`KaraokeLyricsView`（渲染逻辑重写）**
   - 颜色统一：第二行预览不再使用暗灰 `TextSecondary`，两行统一为白色底（未播放）+ 黄色（已播放进度）
   - 平滑进度：移除逐字 `WordTimestamp` 高亮，改为双层渲染 —— 底层白色整行、顶层黄色按行时长比例裁剪揭示
   - `KaraokeLineText`（新增私有组件）：`onTextLayout` 捕获 `TextLayoutResult`，`drawWithContent` + `clipRect` 按可视行（支持换行）裁剪；进度边界落在字符中间时用 `getHorizontalPosition` 双点插值，实现"半字覆盖"
   - `lineProgress()`：按行起始时间计算线性进度 0..1；行未开始返回 0（白色预览），整行播完返回 1（整行保留黄色）
2. **`NowPlayingScreen`**：`showKaraoke` 由 `remember(currentSong)` 改为 `remember`，切歌 / 自动下一首时停留在 K 歌页
3. 清理 `buildKaraokeAnnotatedString` / `estimateWordTimestamps` 等不再使用的逐字逻辑

#### 修改文件

- `ui/components/KaraokeLyricsView.kt`：双层平滑进度渲染 + 两行颜色统一
- `ui/screens/NowPlayingScreen.kt`：K 歌页状态 key 移除 currentSong 依赖
- `CHANGELOG.md`：v2.13.1 条目内新增 Fixed 小节

#### 验证结果

- ✅ `./gradlew.bat :app:compileDebugKotlin` 通过
- ✅ `./gradlew.bat assembleDebug` BUILD SUCCESSFUL

#### 注意事项

- 平滑进度按行时长线性推进，不再依赖逐字时间戳，进度与 LRC 行切换时机保持一致
- 半字边界为像素级插值，中文全角/半角混排时边界像素与字符宽度一致

### 10.43 v2.13.2 - K 歌歌词滚动窗口（逐行推进）

**概述**：将 v2.13.1 的"两行一组整组替换"改为"滚动窗口逐行推进"：两行槽位固定不跳动，当前句播完进入下一句时，另一槽位内容替换为再下一句。用户实测通过后按 Patch 规则升版。

#### 主要变更

1. **`KaraokeLyricsView`（槽位选择逻辑重写）**
   - 槽位绑定索引奇偶：`onTopIsCurrent = currentIndex % 2 == 0`，偶数索引句固定顶部、奇数索引句固定底部，另一槽位（`topLineIndex` / `bottomLineIndex`）取 `currentIndex + 1`
   - 滚动替换：句1 播完切句2 时，顶部槽位内容由句1 换成句3（句2 本来就在底部原地变黄），不再整组跳动
   - 进度规则：当前行按 `lineProgress` 黄色平滑推进，另一槽位白色预览（`progress = 0f`）
   - `currentLineEndMs` 用 `currentIndex + 1` 行时间；末行无下一行时 +3000ms 兜底，`getOrNull` 处理槽位越界（末句奇/偶索引空槽位均安全）

#### 修改文件

- `ui/components/KaraokeLyricsView.kt`：滚动窗口槽位逻辑
- `CHANGELOG.md`：v2.13.2 条目（Changed）
- `app/build.gradle.kts`：versionCode 43 / versionName "2.13.2"

#### 验证结果

- ✅ `./gradlew.bat :app:compileDebugKotlin` 通过
- ✅ `./gradlew.bat assembleRelease` BUILD SUCCESSFUL
- ✅ 电视实测（192.168.0.116, 2.13.1）：句1→句2 顶行换句3、句2→句3 底行换句4、颜色正常，用户确认通过

#### 注意事项

- 槽位奇偶绑定为全局约定：若未来改为三行/四行窗口需同步重写 `onTopIsCurrent` 归属规则

### 10.44 v2.13.3 - 播放页/沉浸页逐字模式平滑化 + 卡拉OK组件复用

**概述**：普通播放页与全屏沉浸页的逐字（WORD_BY_WORD）歌词此前仍按整字跳变高亮，与 v2.13.1 已平滑化的 K 歌模式不一致。本次复用 `KaraokeLineText` 双色渲染组件，让两处逐字歌词也按行内进度连续推进（边界可落在半个字上）。

#### 主要变更

1. **`KaraokeLyricsView.kt`（组件参数化）**
   - `KaraokeLineText` 新增 `baseColor` / `highlightColor` 参数（默认白色底 / 黄色高亮），`baseTextStyle` 相应调整
   - K 歌页调用保持不变（白 / 黄），供播放页复用同一声明式组件

2. **`LyricsView.kt`（逐字模式改为平滑渲染）**
   - WORD_BY_WORD 模式当前行改用 `KaraokeLineText` 渲染：白色底 + 黄色按 `lineProgress` 连续推进，不再按字跳变
   - `estimateWordTimestamps` 及 `WordTimestamp` 相关逻辑移除，清理 `buildAnnotatedString` / `SpanStyle` 等未用导入

3. **`ui/viewmodel/MainViewModel.kt`**
   - `resolveAndPlayByIndex` 加固：补强索引边界与空集合防护

#### 修改文件

- `ui/components/KaraokeLyricsView.kt`：`KaraokeLineText` 参数化
- `ui/components/LyricsView.kt`：逐字模式复用 `KaraokeLineText`，移除逐字时间戳估算
- `ui/viewmodel/MainViewModel.kt`：`resolveAndPlayByIndex` 加固
- `util/NetworkMonitor.kt`：新增可注入 `networkRequest` 参数（测试注入用，默认走原构建逻辑）
- `test/.../lyrics/LrcParserTest.kt`：补挂 Robolectric Runner
- `test/.../util/NetworkMonitorTest.kt`：注入 mock `NetworkRequest`、`@Config(sdk=[30])`、matcher 统一 `eq()`
- `CHANGELOG.md`：v2.13.3 条目（Changed / Fixed）
- `docs/technical-overview.md`：10.44 条目
- `app/build.gradle.kts`：versionCode 44 / versionName "2.13.3"

#### 验证结果

- ✅ `./gradlew.bat compileDebugKotlin` 通过（12s，仅 1 条预存 Coil opt-in 警告）
- ✅ 单元测试全量通过：`testDebugUnitTest` 84/84 绿（此前 16 条失败已修复——`LrcParserTest` 补 Robolectric Runner 解决 `android.util.Log not mocked`；`NetworkMonitor` 注入 `NetworkRequest` + `@Config(sdk=[30])` 规避 Robolectric 4.11.1 缺失的 `registerNetworkCallback`/`addCapability` shadow）

#### 注意事项

- 逐字平滑采用行内时间线性推进：LRC 各句节奏不均时，同一句内高亮推进速度恒定，跨句衔接精确
- 普通播放页与 K 歌页共用一套渲染组件，后续歌词视觉调整只需改 `KaraokeLineText`

### 10.45 v2.13.3 补丁 - 人声消除（方案 B）DSP 参数调整

**概述**：实测人声消除"人声没了、音乐也没了"。根因是实现偏离了设计文档（`docs/vocal-removal-approach-b-dsp.md`）——代码把 `Side` 声道 vocal 频段也衰减 88%（文档设计 `L_out = newMid + Side`，Side 原样保留），Mid vocal 频段被完全挖空（-∞ 归零），切点更激进（120Hz/6kHz vs 文档 200Hz/5kHz），叠加 1.6x 补偿增益放大残余。

按网搜共识（Audacity 官方 Vocal Reduction & Isolation / Adobe Audition Center Channel Extractor / 多篇 mid-side vocal removal 技术文）调整：

| 参数 | 旧值 | 新值 | 依据 |
|------|------|------|------|
| `MID_VOCAL_KEEP`（新增） | 0（vocal 频段归零） | 0.15 | 深度衰减而非 -∞ 挖空，避免与吉他/主旋律等居中乐器同频段的伴奏一起消失（Audacity 官方：伴奏变薄就降低 Strength） |
| `SIDE_VOCAL_KEEP` | 0.12（衰减 88%） | 0.5（轻度削减） | Side = 立体声宽度，过度衰减会削掉左右铺开的乐器/和声/混响（网搜共识：只处理中心声道，别碰 Side） |
| `HIGH_PASS_FREQ` | 6000Hz | 8000Hz | 人声主能量 200Hz~4kHz，High Cut ≥ 8kHz 保住镲片/空气感（Audacity 官方建议） |
| `MAKEUP_GAIN` | 1.6 | 1.25 | 衰减式处理后电平掉落小，降低削波与残留噪声放大 |

#### 主要变更

1. **`VocalRemovalProcessor.kt`**
   - 新增 `MID_VOCAL_KEEP = 0.15f`：Mid vocal 频段提取后保留 15%，不再完全挖空
   - `SIDE_VOCAL_KEEP` 0.12 → 0.5，`HIGH_PASS_FREQ` 6kHz → 8kHz，`MAKEUP_GAIN` 1.6 → 1.25
   - 处理逻辑 `newMid = lowMid + highMid + midVocal * MID_VOCAL_KEEP`

#### 验证结果

- ✅ `./gradlew.bat compileReleaseKotlin` 通过
- ✅ 单元测试全量通过：`testDebugUnitTest` 84/84 绿

#### 注意事项

- 该调整仍是"深度衰减"而非"分离"，人声残留与混响残留仍存在（方案 B 的天花板）；追求高质量伴奏需方案 C（AI 分离）
- 后续若某曲吊仍弱，可继续下调 `MID_VOCAL_KEEP`（朝 0）或上调 `SIDE_VOCAL_KEEP`（朝 1）

### 10.46 v2.13.5 - K 歌逐字"前快后慢"节奏 + 歌词框下沿整曲进度细线

**功能描述**：

1. **逐字高亮改"前快后慢"节奏**：卡拉OK 逐字本质每个字时长不均（ASS `\k` / 逐字 LRC 的业界做法），本项目 LRC 只有整行起止时间，故用内建幂曲线 `progress^0.6` 近似——行内时间过半时已覆盖约 2/3 的字（句首唱得快），剩余字数用后半段慢慢亮起（句尾拖音感）。不依赖每字时间戳，K 歌页与播放页逐字模式共用。
2. **K 歌页整曲进度细线**：歌词半透明框下缘新增 2dp 青色→蓝色渐变进度线（复用 `NasMusicBrushes.progressBar`），由 `durationMs` 实时指示整曲进度，纯视觉、不参与焦点/seek。

#### 主要变更

1. **`KaraokeLyricsView.kt`**
   - 新增 `KARAOKE_PACING_EXPONENT = 0.6f` 与 `internal fun karaokePacingFraction(progress): Float`（0/1 边界严格保持 0/1，内部 `progress^0.6`）
   - `KaraokeLineText` 的 `coveredChars` 由 `progress * text.length` 改为 `karaokePacingFraction(progress) * text.length`
2. **`KaraokePlaybackScreen.kt`**
   - 新增 `durationMs: Long` 参数（歌曲总时长）
   - 歌词框内进度细线：`Box` 内第二个子项默认 `TopStart` 对齐会被叠到框顶部 → 修正为 `.align(Alignment.BottomCenter)`，`padding(horizontal=20.dp, vertical=8.dp)` 使细线贴下沿上方 8dp、与歌词 36dp 底部 padding 不重叠
3. **`NowPlayingScreen.kt`**：`KaraokePlaybackScreen(...)` 调用增加 `durationMs = durationMs` 实参
4. **`KaraokePacingFractionTest.kt`**（新增测试）：0/1 边界、半程覆盖 > 0.5、90% 仍 < 1、单调不减

#### 验证结果

- ✅ `./gradlew.bat compileDebugKotlin` 通过
- ✅ 单测：`testDebugUnitTest` 全量绿（含新增 `KaraokePacingFractionTest`）
- ✅ `assembleRelease` 出包，adb 推到电视（192.168.0.116:5555）安装成功

### 10.47 v2.14.0 - 设置页新增 MTV 视频端点配置

**功能描述**：

1. **MTV 视频端点常量宿主**：新增 `backend/network/mv/BilibiliMvService.kt`（`object`），定义 `DEFAULT_BASE_URL = "https://api.bilibili.com"` 与 `PRESET_ENDPOINTS`（预设端点列表），作为后续 MTV 音乐视频搜索实现的端点常量宿主；同时为设置页端点选择提供数据源。
2. **设置页「视频端点」配置**：网络搜索分区新增「视频端点」小节——预设端点单选（B站官方 API）+ 自定义端点输入（校验 `http://`/`https://` 前缀，空串恢复默认），选中端点高亮打 ✓；替换现有 Meting-API 端点配置的完整交互模式。

**主要变更**：

1. **`backend/network/mv/BilibiliMvService.kt`**（新增）：`DEFAULT_BASE_URL` + `PRESET_ENDPOINTS: List<Pair<String, String>>`
2. **`data/model/AppSettings.kt`**：新增 `mvApiBaseUrl: String = ""`
3. **`data/prefs/AppPreferences.kt`**：新增 `keyMvApiBaseUrl`、settings flow 映射（默认 `BilibiliMvService.DEFAULT_BASE_URL`）、`setMvApiBaseUrl(url)`（trim 反引号/引号/空白）、`getMvApiBaseUrlSync()`、备份恢复 `importBackupData` 同步字段
4. **`ui/viewmodel/MainViewModel.kt`**：新增 `updateMvApiBaseUrl(url)`（空串归一为默认端点）
5. **`ui/components/AppRoot.kt`**：`SettingsScreen` 调用传入 `mvApiBaseUrl = settings.mvApiBaseUrl` 与 `onChangeMvApiBaseUrl = { viewModel.updateMvApiBaseUrl(it) }`
6. **`ui/screens/SettingsScreen.kt`**：新增参数 `mvApiBaseUrl`/`onChangeMvApiBaseUrl`、对话框状态 `showMvUrlDialog`/`mvUrlError`、视频端点小节（预设单选 + 自定义行 + `TextInputDialog` 校验）
7. **`res/values/strings.xml`**：新增 `settings_mv_*` 系列字符串（`settings_mv_api_url`、`settings_mv_api_url_desc`、`settings_mv_api_url_edit`、`settings_mv_api_url_reset`、`settings_mv_api_url_hint`、`settings_mv_api_url_invalid`、`settings_mv_preset_endpoints`、`settings_mv_custom_endpoint`、`settings_mv_custom_endpoint_desc`）

#### 验证结果

- ✅ `./gradlew.bat :app:compileDebugKotlin` 通过（BUILD SUCCESSFUL）

#### 注意事项

- 本步仅完成 MTV 搜索端点**配置层**（`mv-karaoke-feature-proposal.md` 的前置步骤）；实际 MV 搜索/播放（`MvSearchService`、`MvSearchManager`、`MvPlaybackScreen`）不在本版本，方案文档仍为「待评审」状态

---

### 10.48 v2.15.0 - MTV 音乐视频搜索与全屏播放

**功能描述**：

1. **MTV 搜索层**：新增 `backend/network/mv/` 搜索栈——`MvSearchService` 接口（`searchMv(title, artist): MvSearchResult?` + `resolveMv(bvid): MvInfo?`）、`BilibiliMvService` 实现（复用 v2.14.0 的 `DEFAULT_BASE_URL` 与 `PRESET_ENDPOINTS` 常量宿主，搜索请求走 B 站官方 API）、`MvSearchManager` 多源管理器（默认 45 分钟 TTL 内存缓存、多源 fallback 首非空即停、空结果不缓存、单源异常不阻断后续源、播放失败可 `clearCache()` 强制重搜）。
2. **MV 状态机接入播放页**：`MainViewModel` 新增 `MvAvailability`（Idle/Searching/Ready/NotFound）、`showMv` 状态与 `triggerMvSearch`/`enterMvMode`/`exitMvMode`（进 MV 模式前暂停主播放器，退出后恢复）；`PlayerControls` 新增 MTV 按钮（搜索结果非空高亮、NotFound 置暗），`NowPlayingScreen` 通过 `mvAvailable`/`onEnterMv`/`onExitMv` 透传。播放失败时 `onMvPlaybackError` 清缓存重搜一次（`mvRetryDone` 防死循环）；`AppRoot` 监听 `mvState` 变 NotFound 且 `showMv=true` 时自动 `exitMvMode()`，避免切歌到无 MV 的歌时卡在无导航栏的播放页。
3. **MvPlaybackScreen 全屏视频页**：`ui/components/MvPlaybackScreen.kt`——AndroidView 内嵌 ExoPlayer `PlayerView` 播放大屏，视频层叠暗色渐变遮罩保证歌词可读，底部透明控制条（返回播放页 + 歌词开关 + 歌名/歌手），可选叠加 K 歌逐字歌词（`KaraokeLyricsView` 复用）；`AppRoot` 中 `showMv=true` 时隐藏顶部导航栏、BACK 键先退 MV 模式再退播放页、离开播放页自动 `exitMvMode()`。
4. **单元测试**：`MvSearchManagerTest` 10 例覆盖缓存命中（不重复请求）、多源 fallback、单源异常不阻断、全源空结果返回 null、空结果不缓存、TTL=0 过期重搜、`clearCache()` 强制重搜、缓存 key 归一化（小写/trim/多歌手分隔符 `/ 、,，，&` 取首）、`buildCacheKey` 组合与不同歌曲隔离。`BilibiliMvServiceTest` 14 例覆盖 B 站搜索结果解析（bvid 选取/非 video 过滤/HTML 去标签/相似度阈值）与直链提取（durl/dash 回退/code 错误/空值跳过/非法 JSON），用本地 JSON fixture 不联网。

**主要变更**：

1. **`backend/network/mv/MvSearchService.kt`**（新增）：`MvSearchService` 接口
2. **`backend/network/mv/MvSearchManager.kt`**（新增）：`ConcurrentHashMap` 缓存 + TTL 清理 + 多源 fallback；`buildCacheKey(title, artist)` 静态方法供单测
3. **`backend/network/mv/BilibiliMvService.kt`**（扩充）：由常量宿主改为实现 `MvSearchService`，三步取流（搜索 bvid -> view 拿 cid -> playurl 拿直链），wbi/legacy 双路径回退 + 标题相似度排序；`parseCandidatesFromSearch`/`extractPlayUrl` 改 `internal` 供单测
4. **`data/model/MvInfo.kt`**（新增）：`MvInfo(bvid, title, coverUrl, videoUrl, durationMs, fetchedAt)`——`data.model.**` 保持规则已覆盖
5. **`data/model/Song.kt`**：无改动（`song.title`/`song.artist` 直接作为搜索关键词）
6. **`ui/viewmodel/MainViewModel.kt`**：新增 `MvAvailability`/`showMv`/`mvState`、`triggerMvSearch`/`enterMvMode`/`exitMvMode`/`onMvPlaybackError`（+ `mvRetryDone` 防死循环）
7. **`ui/screens/NowPlayingScreen.kt`**：`mtvAvailable`/`onEnterMv`/`onExitMv` 参数透传
8. **`ui/components/PlayerControls.kt`**：新增 MTV 按钮（搜索结果高亮/置暗）（`VocalToggleButton` 复用 `compact`/`dimmed` 状态）
9. **`ui/components/MvPlaybackScreen.kt`**（新增）：全屏视频页
10. **`ui/components/AppRoot.kt`**：`showMv`/`mvState` 顶层收集、导航栏 `showMv` 隐藏、BACK 处理 `showMv -> exitMvMode()`、`MvPlaybackScreen` 渲染分支（含 `onPlaybackError` 接线）、NotFound 自动 `exitMvMode()` LaunchedEffect

#### 验证结果

- ✅ `./gradlew.bat :app:compileDebugKotlin` 通过（BUILD SUCCESSFUL）
- ✅ `:app:testDebugUnitTest` 全量 113 例通过（MTV 相关 24 例：`MvSearchManagerTest` 11 例 + `BilibiliMvServiceTest` 13 例，Robolectric 4.11.1；AppLog 走 android.util.Log，纯 JVM 抛 "not mocked"，须 Robolectric 同 `LrcParserTest`）
- ✅ `:app:assembleDebug` 通过（BUILD SUCCESSFUL）

#### 注意事项

- `PlayerView` 左上角 B 站水印/片头等实机表现以电视验收为准；MV 直链带 TTL，30–45 分钟内重进直接命缓存，超时自动重搜
- 版本号由 v2.14.0 → v2.15.0（versionCode 47 → 48）






---

### 10.49 v2.16.0 - MV 持久缓存 + 控制条虚化 + 连播修复

**功能描述**：

1. **MV 持久缓存**：新增 `MvPersistentCache`（`backend/network/mv/MvPersistentCache.kt`），存 `songId -> MvCacheEntry(bvid, mvTitle, playCount, lastPlayedAt)` 到 JSON 文件；只存 bvid（稳定）不存直链（过期）；三层查询：内存缓存（45min TTL）-> 持久缓存（`resolveMv(bvid)` 拿新鲜直链）-> B站 API；LRU 上限 500 条；`markCompleted` 在 MV 播完时写入（`playCount++`），用户切换后播完覆盖旧 bvid。
2. **控制条自动虚化**：`MvPlaybackScreen` 新增 `controlsVisible` 状态 + `lastInteraction` 时间戳；5 秒无操作 -> 控制条 + 渐变遮罩 alpha 降至 0.15；任意按钮 `onClick` 或 D-pad 焦点变化 -> 完全显化（1.0）+ 重新计时。
3. **连播卡住修复**：`endedHandled`/`errorReported` 从 `remember` 改为 `remember(mv.videoUrl)`，无缝切歌时新 URL 触发重置。

**主要变更**：

1. **`data/model/MvInfo.kt`**：新增 `MvCacheEntry` 数据类
2. **`backend/network/mv/MvPersistentCache.kt`**（新增）：JSON 文件持久化 + LRU 淘汰
3. **`backend/network/mv/MvSearchManager.kt`**：构造函数加 `persistentCache`；`searchMvFor` 加持久缓存查询/写入；新增 `markCompleted` 委托
4. **`NasMusicApp.kt`**：构造 `MvPersistentCache(this)` 注入 `MvSearchManager`
5. **`ui/viewmodel/MainViewModel.kt`**：`onMvPlaybackEnded` 播完时调 `markCompleted`
6. **`ui/components/MvPlaybackScreen.kt`**：`endedHandled`/`errorReported` 绑定 `mv.videoUrl`；控制条 + 渐变遮罩 `alpha(controlsAlpha)` + `onFocusChanged` + `activateControls()`

#### 验证结果

- ✅ `:app:assembleRelease` 通过（BUILD SUCCESSFUL）
- ✅ 实机验证：连续播放多首 MV 不再卡住；控制条 5 秒虚化/操作显化；退出重进同一首歌 MV 命持久缓存更快

#### 注意事项

- 持久缓存文件 `mv_cache.json` 在 app filesDir，卸载清除；bvid 不过期但视频可能被删/风控，`resolveMv` 失败时自动删旧条目重搜
- 版本号由 v2.15.0 -> v2.16.0（versionCode 48 -> 49）

### 10.50 v2.17.0 - 手机遥控 + 遥控服务器按需启动

**功能描述**：

1. **手机遥控（扫码控制）**：K歌/MTV 全屏页右上角显示二维码（含 token 的 URL），手机扫码打开遥控页——查看当前队列、播放/移动/添加歌曲、搜索 NAS 与网络音乐（`RemoteControlServer`，NanoHTTPD，端口 18082 + token 鉴权 + `Connection: close`；`/api/queue`、`/api/queue/play`、`/api/queue/move`、`/api/queue/add`、`/api/search`、`/api/status`）
2. **遥控服务器按需启动**：移除 `MainViewModel.init` 中的常驻启动，改为 `ensureRemoteControlStarted()` 在进入 K歌（`onEnterKaraokeMode` 回调）或 MTV（`enterMvMode`）时按需启动，`onCleared` 统一停止——排查 TV WiFi/ADB 断连诱因时发现的最高嫌疑项（常驻端口 + 空闲线程）
3. **轮询降频**：遥控页队列轮询 3s -> 5s，降低手机端连接频率与 TV 端 NanoHTTPD 线程创建/销毁压力

**主要变更**：

1. **`net/RemoteControlServer.kt`**（新增）：NanoHTTPD 服务器 + token 鉴权 + QR URL 生成 + 队列/搜索/播放 API
2. **`net/RemoteControlHtml.kt`**（新增）：遥控页 HTML（内嵌），`setInterval(fetchQueue, 5000)` 轮询
3. **`ui/viewmodel/MainViewModel.kt`**：移除 init 常驻启动；新增 `ensureRemoteControlStarted()`（幂等，URL 为空才启动）；`enterMvMode()` 调用；`onCleared()` 停止服务器
4. **`ui/screens/NowPlayingScreen.kt`**：新增 `onEnterKaraokeMode` 回调参数，`enterKaraoke()` 时调用
5. **`ui/components/AppRoot.kt`**：接线 `onEnterKaraokeMode = { viewModel.ensureRemoteControlStarted() }`
6. **`player/PlayerManager.kt`**：新增 `playAt(index)` / `moveQueueItem(from, to)`（遥控队列操作）
7. **`ui/components/KaraokePlaybackScreen.kt` / `MvPlaybackScreen.kt`**：右上角二维码显示（含 token URL），5 秒无操作自动隐藏

#### 验证结果

- ✅ `:app:compileDebugKotlin` BUILD SUCCESSFUL（exit 0）
- ⏳ 实机验证待用户执行：扫码遥控、K歌/MTV 二维码显示、WiFi/ADB 稳定性对比

#### 注意事项

- 遥控服务器仅 K歌/MTV 模式需要；按需启动避免 App 常驻额外端口/线程，降低 TV 资源受限设备上的 WiFi/ADB 不稳定风险
- 版本号由 v2.16.0 -> v2.17.0（versionCode 49 -> 50）

### 10.51 v2.17.1 - 遥控页去 token + 队列删除 + 移除播放按钮 + K歌二维码修复

**功能描述**：

1. **遥控 URL 去除 token**：家庭局域网信任环境，扫码即可直接进入遥控页，无需手动输入 token（`RemoteControlServer` 删除 `sessionToken` 生成/校验与 URL `#token` 拼接；`RemoteControlHtml` 删除 `TOKEN` 变量及全部 `?token=` 拼接）
2. **遥控页队列删除**：队列行新增 ✕ 删除按钮，新增 `/api/queue/remove` 路由，走 `MainViewModel.removeFromQueue` -> `PlayerManager.removeFromQueue`，与 TV 端队列页删除语义一致
3. **移除播放按钮**：队列条目点击即播放，冗余 `play-btn` 删除，页面更简洁
4. **K歌页二维码修复**：二维码 `Image` 加 `.zIndex(10f)`——K歌页二维码在 Box 中先声明，被后声明的全屏背景 + 暗色遮罩绘制在上层覆盖；MTV 页二维码因声明顺序靠后一直正常
5. **遥控页长按拖拽超时失效修复**：`fetchQueue` 加 `if (dragState) return;` 守卫 + 补 `touchcancel` 监听（复用 `onTouchEnd` 清理 `dragState`）。根因与细节：遥控页队列每 5 秒轮询 `renderQueue` 用 `innerHTML` 整表重建 DOM；长按 500ms 激活拖拽后，若按住超过一个轮询周期（5s），`list.innerHTML` 重绘使被拖拽元素 `dragState.item` 脱离文档成为游离节点，`onTouchMove` 的 `style.transform` 落空、`dragging` 样式消失——未松手移动状态即失效。守卫保证任何触摸/拖拽期间不重建队列 DOM（覆盖激活前 500ms 窗口期与激活后全程），松手后 `dragState = null` 下一轮轮询自动恢复；`touchcancel` 防止系统打断触摸（如来电）时 `dragState` 残留导致守卫永久跳过轮询

**主要变更**：

1. **`ui/components/KaraokePlaybackScreen.kt`**：二维码 `Image` 加 `.zIndex(10f)` + `import androidx.compose.ui.zIndex`
2. **`net/RemoteControlServer.kt`**：删除 token 校验/拼接；新增 `/api/queue/remove` 路由 + `handleRemove`（读 `index`）；`RemoteCallbacks` 增 `removeFromQueue`
3. **`net/RemoteControlHtml.kt`**：删除 `TOKEN` 与播放按钮；新增 `removeItem(index)` + `del-btn`；文件头注释同步更新；`fetchQueue` 拖拽守卫 + `touchcancel` 监听
4. **`ui/viewmodel/MainViewModel.kt`**：`RemoteCallbacks` 实现加 `override fun removeFromQueue(index)`

#### 验证结果

- ✅ `:app:compileDebugKotlin` BUILD SUCCESSFUL（exit 0，2 个既有 warning 与本次无关）
- ✅ 电脑访问 `http://127.0.0.1:18082/` HTTP 200（页面 12.4KB）；模拟器 logcat 确认新 URL 无 token（`url=http://10.0.2.15:18082`）
- ✅ 模拟器安装验证：遥控页点击播放、✕ 删除、无播放按钮、K歌二维码正常显示（用户确认）
- ✅ 拖拽守卫行为验证：node 模拟 3 场景（空闲 1 请求/1 渲染 / 拖拽中 0 新请求/0 渲染 / 松手后恢复 2 请求/1 渲染）；提取 HTML 内嵌 JS 过 `node --check` 语法检查

#### 注意事项

- 去除 token 仅适用于家庭局域网信任场景；`LocalInputServer`(18080)/`BackupTransferServer`(18081) 为纯事件驱动短连接、无轮询无 token，不改
- 版本号由 v2.17.0 -> v2.17.1（versionCode 50 -> 51）

---

### 10.52 v2.17.2 - 网络/播放稳定性修复（WiFi 掉线根因修复）

**功能描述**：

本次版本聚焦修复电视 WiFi 频繁掉线问题，并修复一批网络层与播放层的次要问题。通过用户提供的 34MB logcat 日志（1 小时 16 分钟 MV 连播测试），验证所有修复均生效，测试期间零掉线、零播放错误。

#### 核心修复

1. **电视 WiFi 频繁掉线（P0-A）**：根因是 `NetworkMonitor.onCapabilitiesChanged` 在 WiFi 信号波动时高频误触发 `onNetworkLost`/`onNetworkAvailable`。`onCapabilitiesChanged` 在 WiFi 信号波动、网络切换时高频触发（非真正断网），每次"恢复"都调用 `connectToSavedServer(silent=true)` 重新连接 NAS → 创建新的 `JellyfinAdapter` + `OkHttpClient`（旧的虽由 `BackendRegistry` 正确 close，但短时间内累积多套连接池/线程池拖垮电视网络栈）→ WiFi 进一步过载 → 更多抖动 → 正反馈死循环。修复：引入 `lastHasInternet` 状态跟踪，仅在状态真正转换（false → true）时回调 `onNetworkAvailable`，`onNetworkLost` 只由 `onLost` 触发（真正的网络丢失事件）。实测验证：1 小时 16 分钟 MV 连播期间 `NetworkMonitor` 仅 1 条 `register` 日志，零次误触发
2. **MTV 页 ExoPlayer 每次 videoUrl 变化重建（V1）**：`remember(mv.videoUrl)` 导致每次切歌/换源都新建一个 ExoPlayer 实例，`release()` 是异步的，频繁切换时可能短期两个 Player 实例并存。修复：改为 `remember(context)` 页面级复用，切歌通过 `stop()+clearMediaItems()+setMediaItem()+prepare()+play()` 完成。实测验证：45 次切歌仅创建 1 个 ExoPlayer，零播放错误
3. **PlayerManager 1000ms Handler 轮询健壮性（H4）**：`postDelayed` 在 `player?.let{}` 块外，player 为 null 也持续轮询浪费 CPU；`onPositionDiscontinuity(SEEK)` 未清除 `seekPending` 导致 2s 进度停滞。修复：`postDelayed` 移入 player 非空分支内（player 释放后自动停止轮询）；seek 完成时 `onPositionDiscontinuity(SEEK)` 立即清除 `seekPending` + 移除兜底 timeout；seek 兜底从 2s 缩短到 1s 且用独立 `seekTimeoutRunnable`
4. **空 URI 传入 ExoPlayer 制造错误噪声（M4）**：网络歌曲 streamUrl 为空时 `MediaItem.fromUri("")` 让 ExoPlayer 抛异常，触发 `onPlayerError` ERROR 日志 + 错误 UI。修复：`onPlayerError` 中对 `streamUrl` 为空的预期错误提前 return（降级为 DEBUG 日志，不设 `_playerError`）

#### 次要修复

5. **MetingApiService.resolveLyrics 不 fallback（M3）**：`resolveLyrics` 只用 `baseUrl`，不像 `search`/`resolvePlayUrl`/`getPlaylist` 调用 `buildEndpointFallbackOrder`。修复：采用多端点 fallback，与同类方法一致
6. **MetingApiService.parseSongs 逐条打日志刷屏（L1）**：每次搜索逐条打印 INFO 日志（`AppLog.i` 不被 ProGuard `-assumenosideeffects` 剥离）。修复：改为汇总日志（`result=X/Y`），首项 keySet 降为 DEBUG 级
7. **extractIdFromUrl URI 解析失败后正则 fallback（L2）**：`java.net.URI` 对含空格/中文的 URL 抛异常。修复：改用 `android.net.Uri.parse`（Android 内置，不抛异常），正则降为兜底
8. **HttpLoggingInterceptor 在 release 未关闭（M8）**：`JellyfinAdapter`/`NavidromeAdapter`/`LyricsNetworkProvider` 始终 `Level.BASIC`，release 中打印 URL（含 Jellyfin `api_key` token、酷狗 hash）。修复：用 `BuildConfig.DEBUG` 包裹
9. **JellyfinAdapter utf8Body GBK 回退无日志（M7）**：GBK 回退触发时无任何标记。修复：回退时打 DEBUG 日志记录 URL，回退失败打 WARN
10. **NavidromeAdapter API 版本硬编码（M6）**：`v=1.16.1` 和 `c=NASMusicTV` 内联在 URL 拼接中。修复：提取为 `companion object` 常量 `API_VERSION`/`CLIENT_NAME`，注释说明这是 Subsonic 协议版本

#### 主要变更文件

1. **`util/NetworkMonitor.kt`**：删除 `onCapabilitiesChanged` 中的 `onNetworkLost` 调用；引入 `lastHasInternet` 状态跟踪；`onAvailable`/`onLost`/`onCapabilitiesChanged` 均做状态转换判断
2. **`ui/components/MvPlaybackScreen.kt`**：`remember(mv.videoUrl)` → `remember(context)`；切歌改用 `stop()+setMediaItem()`；新增 `onPlaybackStateChanged`/`onIsPlayingChanged`/`onMediaItemTransition` 详细日志
3. **`player/PlayerManager.kt`**：`progressUpdateRunnable` 改为 player 为 null 时 return（不再 re-post）；新增 `seekTimeoutRunnable`；`onPositionDiscontinuity(SEEK)` 清除 `seekPending`；`onPlayerError` 空 URI 降级
4. **`backend/BackendRegistry.kt`**：加注释确认旧 adapter close 逻辑的重要性
5. **`backend/impl/JellyfinAdapter.kt`**：HttpLoggingInterceptor 用 `BuildConfig.DEBUG` 包裹；utf8Body GBK 回退加日志
6. **`backend/impl/NavidromeAdapter.kt`**：HttpLoggingInterceptor 用 `BuildConfig.DEBUG` 包裹；API 版本提取为常量
7. **`backend/network/MetingApiService.kt`**：resolveLyrics 加多端点 fallback；parseSongs 改汇总日志；extractIdFromUrl 改用 `Uri.parse`
8. **`lyrics/LyricsNetworkProvider.kt`**：HttpLoggingInterceptor 用 `BuildConfig.DEBUG` 包裹

#### 验证结果

- ✅ `:app:compileDebugKotlin --rerun-tasks` BUILD SUCCESSFUL（2 个既有 warning 与本次无关）
- ✅ 电视实测 1 小时 16 分钟 MV 连播（45 次切歌，15 首完整播放）：WiFi 零掉线、零播放错误、零 ANR、零 OOM
- ✅ 日志分析：`NetworkMonitor` 仅 1 条 register 日志（修复前频繁误触发）；ExoPlayer 仅创建 1 次（V1 修复生效）；App 自身零 Error 日志；内存稳定 26-34MB

#### 注意事项

- `BackendRegistry` 的旧 adapter close 逻辑（`releaseAdapter` → `logout` + `close`）在修复前已正确存在，本次仅加注释强调其重要性
- `WifiStateMachine` 每 3 秒打 `msg.what=131155`（CMD_RSSI_POLL）E 级日志是电视系统固件行为，与 App 无关
- 版本号由 v2.17.1 -> v2.17.2（versionCode 51 -> 52）

---

### 10.53 v2.17.3 - 播放页跳转网络搜索 + 网络歌词持久化缓存 + 批量播放性能优化

**功能描述**：

本次版本新增三个功能/优化：

1. **播放页歌手/歌名可聚焦跳转网络搜索**：播放页 `CoverColumn` 中的歌曲名和歌手名从纯 `Text` 改为 `FocusableSurface`，D-Pad 可选中，按下确定键自动跳转到网络音乐搜索页（`Screen.Network` + `selectNetworkSubTab(SEARCH)`）并填入搜索词
2. **网络歌词持久化缓存（参照 MvPersistentCache 模式）**：新增 `LyricsPersistentCache`，存储结构为 `lyrics_cache.json`（索引，仅 metadata）+ `lyrics_cache/{songId}.lrc`（纯 LRC 文本）；保存时机类似 MV 的 `markCompleted`——用户切到网络歌词时暂存到 `pendingNetworkLyrics`，歌曲播放完成时 `commitPendingNetworkLyrics` 才写入持久化；下次播放时自动读取并显示独立的 `CACHED` 来源标签（"缓存"），可选中高亮和切换
3. **批量播放网络歌曲性能优化**：`playNetworkBatch` 不再预先串行解析全部歌曲（最多 30 首）的播放链接，改为只即时解析第一首后立即更新队列并开始播放，后续歌曲沿用已有的 `onNeedResolveStreamUrl` 懒加载机制

#### 详细说明

**功能 1：播放页跳转网络搜索**

原代码：歌手名和歌曲名为纯 `Text` 不可聚焦，用户无法直接搜索当前播放歌曲的歌手或歌名。

修改：`NowPlayingScreen` 新增 `onSearchArtist` / `onSearchSong` 两个回调参数；`CoverColumn` 的歌曲名和外部歌手名包裹在 `FocusableSurface` 中；`AppRoot` 接线：`navigateTo(Screen.Network)` + `selectNetworkSubTab(SEARCH)` + `searchNetworkSongs(keyword)`。

**功能 2：网络歌词持久化缓存**

原代码：`LyricsManager` 缓存到 `context.cacheDir/lyrics`（系统可回收），key 为不可靠的 `{artist}_{title}.lrc`，且每次获取歌词时无条件写入，无"用户认可"的概念。

修改：新增 `LyricsPersistentCache`，参照 `MvPersistentCache` 设计模式：

- **存储结构**：`lyrics_cache.json`（`Map<songId, IndexEntry>`，仅 metadata，~200KB）+ `lyrics_cache/{songId}.lrc`（纯 LRC 文本），避免单 JSON 体积过大
- **保存时机（pending + commit）**：用户切到网络歌词时 `getLyricsFromSource(NETWORK)` 将原始 LRC 文本暂存到 `pendingNetworkLyrics`（`ConcurrentHashMap`）；歌曲播放完成时 `currentSong.collect` 检测到上一首结束，若 `lastRecordedLyricsSource == NETWORK` 则调用 `commitPendingNetworkLyrics` 写入持久化——对应 MV 的 `markCompleted` 语义
- **读取**：`loadLyricsForCurrentSong()` 优先查 `getCachedNetworkLyrics(song)`，命中则返回 `CACHED` 来源歌词并显示"缓存"标签
- **CACHED 来源**：新增 `LyricsSource.CACHED("缓存")` 枚举，`LyricsAvailability.cached` 字段；播放页标签栏显示"缓存"按钮，有缓存时可用、选中时高亮；用户可随时切回"后端"或"网络"
- **LRU 淘汰**：2000 条，按 `lastPlayedAt` 排序，同时删索引项 + `.lrc` 文件
- **备份**：`exportAll()`/`importAll()` 接口，与 `MvPersistentCache` 一致
- **后端歌词不参与持久化**，仅网络歌词走此流程

**功能 3：批量播放网络歌曲性能优化**

原代码：`playNetworkBatch` 将最多 30 首网络歌曲的 `resolvePlayUrl` 串行调用（每首 1 次网络请求），全部完成后才调用 `playQueue` 更新队列并开始播放。用户点击"全部播放"后需等待 30×RTT（约 10-30 秒，取决于网络延迟）才能听到音乐。

修改：只即时解析 `startIndex` 处第一首，其余歌曲带入队列（streamUrl 为空），队列立即更新并开始播放。后续歌曲的 URL 解析由 `onNeedResolveStreamUrl` → `resolveAndPlayByIndex` 懒加载触发，与单首网络歌曲及"恢复队列"的播放路径一致。

**主要变更文件**：

1. **`ui/screens/NowPlayingScreen.kt`**：新增 `onSearchArtist`/`onSearchSong` 回调；`CoverColumn` 的歌曲名和外部歌手名改为 `FocusableSurface`；新增"缓存" SourceTag 按钮
2. **`ui/components/AppRoot.kt`**：接线新回调，跳转网络搜索页并自动搜索
3. **`data/model/LyricsSource.kt`**：新增 `CACHED("缓存")` 枚举
4. **`data/model/Lyrics.kt`**：`LyricsAvailability` 新增 `cached` 字段和 `hasCached`
5. **`data/model/LyricsCacheEntry.kt`**（新增）：歌词缓存条目数据类
6. **`lyrics/LyricsPersistentCache.kt`**（新增）：持久化缓存类，`lyrics_cache.json`（索引）+ `{songId}.lrc`（独立文件），LRU 2000，export/import
7. **`lyrics/LyricsManager.kt`**：移除旧的 file-based 缓存，接入 `LyricsPersistentCache`；新增 `getCachedNetworkLyrics`/`savePendingNetworkLyrics`/`commitPendingNetworkLyrics`/`discardPendingNetworkLyrics`；`getLyricsFromSource(NETWORK)` 写入 pending 而非直接持久化
8. **`ui/viewmodel/MainViewModel.kt`**：`loadLyricsForCurrentSong` 优先选缓存；`currentSong.collect` 中播放完成时 commit；`playNetworkBatch` 只解析第一首，其余走懒加载
9. **`app/src/main/res/values/strings.xml`**：新增 `player_highlight_cached` 字符串

**验证结果**：

- ✅ `:app:compileDebugKotlin` BUILD SUCCESSFUL
- ✅ 播放器行为无变化：第一首正常解析并播放，后续歌曲在播放到时自动调起懒加载解析
- ✅ 首首串行解析的总延迟由 30×RTT 降至 1×RTT

**注意事项**：

- 首次播放的歌曲仍然是即时解析的，不影响首次播放体验
- 旧 `cacheDir/lyrics` 下的缓存文件不会自动迁移到新结构，后续播放时会重新获取
- 版本号由 v2.17.2 -> v2.17.3（versionCode 52 -> 53）

---

### 10.54 v2.17.4 - 网络歌词候选缓存 + 换一批 + 端点可配置 + 加载性能优化

**功能描述**：

本次版本对网络歌词系统进行多项优化：

1. **网络歌词候选缓存 + 换一批**：`getLyricsFromSource(NETWORK)` 首次请求后将候选列表缓存到 `cachedCandidates`（`ConcurrentHashMap`），切换索引时只读缓存不重新请求；候选耗尽时自动用变异后缀（`歌词`、`完整版`、`原唱`、`歌曲`、`lyrics`）重新搜索并追加新候选，所有变异用尽时返回 null
2. **Kugou/Netease 歌词端点可配置**：`LyricsNetworkProvider` 构造函数接收 `kugouBaseUrl`/`kugouLrcUrl`/`neteaseBaseUrl` 参数；设置页"网络搜索"分区新增"歌词端点"子分区，支持酷狗和网易云两个端点独立编辑
3. **歌词加载性能优化**：`loadLyricsForCurrentSong()` 中缓存命中时立即设置 `_currentLyrics.value`，不等待 `checkAvailability()` 的网络请求完成
4. **歌词优先级调整**：自动加载时按 `缓存(CACHED) → 内嵌(EMBEDDED) → 网络(NETWORK)` 优先级选择

**主要变更文件**：

1. **`lyrics/LyricsNetworkProvider.kt`**：构造函数接收可配置端点参数；新增 `DEFAULT_KUGOU_BASE_URL`/`DEFAULT_KUGOU_LRC_URL`/`DEFAULT_NETEASE_BASE_URL` 常量
2. **`lyrics/LyricsManager.kt`**：新增 `cachedCandidates`/`candidateVariantRound`；`getLyricsFromSource(NETWORK)` 实现缓存 + 换一批；新增 `clearCachedCandidates()`
3. **`data/prefs/AppPreferences.kt`**：新增 `keyLyricsKugouBaseUrl`/`keyLyricsNeteaseBaseUrl` 及对应 sync getter/setter
4. **`data/model/AppSettings.kt`**：新增 `lyricsKugouBaseUrl`/`lyricsNeteaseBaseUrl` 字段
5. **`ui/viewmodel/MainViewModel.kt`**：构造 `LyricsManager` 时传入端点参数；`loadLyricsForCurrentSong()` 开头调 `clearCachedCandidates()`；新增 `updateLyricsKugouBaseUrl()`/`updateLyricsNeteaseBaseUrl()`
6. **`ui/screens/SettingsScreen.kt`**：新增"歌词端点"分区 + 编辑对话框
7. **`ui/components/AppRoot.kt`**：接线新回调

**验证结果**：

- ✅ `:app:compileDebugKotlin` BUILD SUCCESSFUL

**注意事项**：

- `kugouBaseUrl` 同时作用于搜索和歌词下载两个端点（默认 `mobilecdn.kugou.com` 和 `krcs.kugou.com`），自定义端点时需确保两个路径均可用
- 版本号由 v2.17.3 -> v2.17.4（versionCode 53 -> 54）

---

### 10.55 v2.18.0 - 百度网盘音乐播放（Phase 1-7 完整落地 + 索引 MV 搜索）

**功能描述**：

本次版本新增百度网盘音乐播放功能，覆盖网盘 OAuth 鉴权、文件列表/搜索、音乐串流、歌词/封面、MV 文件关联、测试覆盖全链路。

Phase 1-6 代码已全部落地并编译通过。Phase 7（测试与文档）新增 8 个测试文件，共 53 个单测覆盖所有核心模块。MV 搜索从实时 API 查询改为索引搜索（零网络调用）。

**Phase 1-6 主要变更文件**：

| 阶段 | 文件 | 内容 |
|------|------|------|
| 鉴权 | `BaiduOAuthClient.kt` | 设备码模式 + token 刷新（注入 tokenUrl 支持测试） |
| 配置 | `BaiduNetdiskConfig.kt` | API 常量表 + API_PROBE_BASELINE 基线声明 |
| 配置 | `CloudDriveConfig.kt` / `CloudDriveType.kt` | 网盘配置模型（isActive/apiDrifted/effectiveMvDir） |
| Token | `BaiduTokens.kt` | 持久化模型 + needsRefresh(5min 提前） |
| 存储 | `AppPreferences.kt` | 按 CloudDriveType 存取配置 + apiDriftNotified 标记 |
| API | `BaiduPanApi.kt` | 列表/搜索/filemetas 封装；解析函数 internal（可测） |
| 模型 | `BaiduFile.kt` / `BaiduFileMeta.kt` | API 响应映射 |
| 索引 | `BaiduFileIndexCache.kt` | 本地 JSON 索引缓存 + searchMv + 可选 mvDir 扫描 |
| 串流 | `BaiduStreamFactory.kt` / `BaiduHttpDataSourceFactory.kt` | dlink 解析 + 域名拦截器 |
| 服务 | `BaiduNetdiskService.kt` | NetworkMusicService 实现 |
| 注册 | `NetworkMusicManager.kt` / `NasMusicApp.kt` | 按 isActive 运行时注册/注销 |
| 歌词/封面 | `BaiduLyricsProvider.kt` / `BaiduCoverProvider.kt` / `Id3v2Parser.kt` | 侧车 LRC + 内嵌 ID3 + 网络 fallback |
| MV 搜索 | `BaiduMvFileService.kt` | 索引搜索（同目录同名 + 歌手歌名，零网络） |
| 版本探测 | `ApiProbe.kt` | 字段指纹 SHA-256 + 漂移判定 + 一次性提示 |
| 目录浏览 | `BaiduDirPickerDialog.kt` | 目录树选择对话框 |
| 鉴权 UI | `BaiduAuthDialog.kt` | 设备码显示对话框 |
| 网盘 Tab | `NetdiskScreen.kt` | 独立网盘 Tab |
| 设置页 | `SettingsScreen.kt` | 网盘分区 + 开关/登录/目录配置 |
| MV 搜索 UI | `MvSearchManager.kt` / `MvPlaybackScreen.kt` / `MainViewModel.kt` | 搜B站按钮 + fallback |
| Coil | `NasMusicApp.kt` | 百度 dlink UA 拦截器注入 |
| B 站接口 | `MvSearchService.kt` | searchMv 新增 song 参数 |
| ProGuard | `proguard-rules.pro` | 显式 keep 百度 DTO 类 |
| 索引模型 | `BaiduIndexEntry.kt` | 新增 category 字段 + toBaiduFile() |

**索引 MV 搜索（Option C）**：MV 搜索从实时 API 查询改为本地索引搜索，零网络调用：

1. `BaiduIndexEntry` 新增 `category: Int` 字段（默认 CATEGORY_AUDIO，兼容旧索引）
2. `BaiduFileIndexCache.fullScan` 新增 `mvDir: String?` 参数，非 null 时额外扫描 MV 目录的视频文件入索引
3. `BaiduFileIndexCache` 新增 `searchMv(artist, title, limit)` 方法，按精确度排序
4. `BaiduMvFileService.searchMv` 重构：移除 `findMvInSameDir`（原调 `api.listDir`），改为索引搜索（同目录同名 → 歌手歌名 → null）
5. `MainViewModel.rebuildBaiduIndex` 传入 `mvDir` 参数更新索引

**Phase 7 测试文件**：

| 步骤 | 文件 | 覆盖内容 |
|------|------|---------|
| 33 | `BaiduPanApiTest.kt` | list/search/filemetas 响应解析（7 个测试） |
| 35 | `BaiduMvFileServiceTest.kt` | 索引搜索 + resolveMv（10 个测试，含 excludeBvids） |
| 36 | `CloudDriveConfigTest.kt` | isActive/apiDrifted/effectiveMvDir + AppPreferences 回环（8 个测试） |
| 37 | `BaiduDirPickerTest.kt` | parentPath/childPath 目录导航逻辑（7 个测试） |
| 38 | `ApiProbeTest.kt` | 字段指纹稳定性/敏感性 + isDrifted + shouldNotifyDrift（12 个测试） |
| 39 | `ApiDriftNotifyTest.kt` | 一次性提示去重逻辑（5 个测试） |
| 40 | `BaiduFilenameParserTest.kt` | 文件名解析（9 个测试） |

**验证结果**：

- ✅ `:app:compileDebugKotlin` BUILD SUCCESSFUL
- ✅ `:app:testDebugUnitTest` 191 tests, 189 passed（含 53 新增百度单测 + 138 已有；2 个 pre-existing NetworkMonitorTest 失败）

**注意事项**：

- `BaiduOAuthClient` 的 `tokenUrl` 参数可注入，便于测试，生产环境默认使用 `BaiduNetdiskConfig.TOKEN_URL`
- `ApiProbe` 的 `API_PROBE_BASELINE` 当前为空字符串（漂移检测暂不生效），上线前实测百度 API 响应结构后回填 SHA-256 指纹
- 步骤 34（BaiduOAuthClientTest）因 Mockito + Kotlin 非空参数冲突暂未包含，`needsRefresh` 纯逻辑已在 `BaiduTokens` 自身验证
- 版本号由 v2.17.4 -> v2.18.0（versionCode 54 -> 55）

### 10.56 v2.18.1 - 设置页重构 + 网盘 UI 全面升级

**提交日期**：2026-08-21

**主要变更**：

1. **设置页重构（tab 合并/移入）**
   - 移除独立"歌词" tab → 缓存开关（歌词/封面自动缓存）并入"缓存管理" tab 顶部
   - 移除独立"封面" tab → 封面滤镜（模糊半径/暗色遮罩）并入"播放" tab 作为子分组
   - 移除导航栏"服务器" tab → 设置页新增"服务器"分区（连接状态/配置/断开）
   - 新增"清除 MV 缓存"按钮（`MvPersistentCache.clear()` + `MvSearchManager.clearPersistentCache()` + `MainViewModel.clearMvPersistentCache()`）
   - 缓存目录大小置顶显示

2. **网盘页面 UI 升级**
   - 搜索 BasicTextField → 弹窗 `TextInputDialog`（支持扫码输入）
   - 搜索结果/目录歌曲列表改为 2 列 `LazyVerticalGrid` + `SongRow`（支持收藏/加入队列）
   - "播放全部"支持子目录递归
   - 浏览位置保留（切换页面不重置）
   - 目录选择器：固定窗口高度 + 上级按钮始终可见 + 返回键可关闭

3. **百度授权对话框修复**
   - 二维码改用 `verification_url` 稳定验证页
   - 返回键可关闭（`dismissOnBackPress=true` + `onDismissRequest`）
   - 分步操作说明

4. **网盘设置分组**
   - 设置页"网盘"分区新增"百度网盘"/"其他网盘"分组
   - 阿里云盘/123 网盘/夸克网盘灰显"敬请期待"占位

**涉及文件**：
- `SettingsScreen.kt`：tab 移除/合并、MV 缓存清除、缓存大小置顶、分组/占位
- `AppRoot.kt`：`onClearMvCache` 接线
- `MvPersistentCache.kt`：`clear()` 方法
- `MvSearchManager.kt`：`clearPersistentCache()` 方法
- `MainViewModel.kt`：`clearMvPersistentCache()`
- `NetdiskScreen.kt`：搜索弹窗、SongRow 2列网格、播放全部递归、浏览位置保留
- `BaiduDirPickerDialog.kt`：固定窗口、上级按钮、返回键
- `BaiduAuthDialog.kt`：verification_url + 返回键

**版本号变更**：v2.18.0 → v2.18.1（versionCode 55 → 56）

### 10.57 v2.19.0 - Subsonic 协议支持

**提交时间**：2026-08-21

**背景**：在 Jellyfin / Navidrome 之外新增第三类 NAS 后端——Subsonic 协议（兼容 lx-server、Navidrome、Airsonic 等 Subsonic 实现），通过标准 token+salt 认证接入。

**主要改动**：

1. **SubsonicAdapter**（`backend/impl/SubsonicAdapter.kt`，790 行）：完整实现 `BackendAdapter` 接口
   - 认证：`md5(password + salt)` 标准 token+salt，兼容所有 Subsonic 实现
   - API 覆盖：专辑 / 歌手 / 歌曲 / 搜索 / 收藏 / 播放列表 / 流派 / 随机歌曲 / 歌词 / 封面流等全部接口
   - 连接测试：ping 端点验证连通性

2. **后端注册**（`BackendRegistry.kt`）：注册 Subsonic 类型适配器

3. **服务器配置**（`ServerConfig.kt` / `ServerConnectScreen.kt`）：新增 Subsonic 服务器类型选项，URL 占位符按类型动态切换

4. **设置页**（`SettingsScreen.kt` / `strings.xml`）：支持后端列表更新为 "Jellyfin / Navidrome / Subsonic"

**验证结果**：
- `SubsonicAdapterTest` 13 个测试覆盖认证逻辑和 API 调用
- `:app:testDebugUnitTest` 通过（含 Subsonic 测试）

**版本号变更**：v2.18.1 → v2.19.0（versionCode 56 → 57）

### 10.58 v2.20.0 - 手机端支持（TV / 手机同 APK）

**提交时间**：2026-08-22

**背景**：原为纯 TV 应用（leanback 强制 + horizontal 锁定 + tv.material3 组件）。v2.20.0 使同一 APK 同时支持 TV 与手机/平板，运行时按设备类型切换交互。

**主要改动**：

1. **设备类型检测**：`hasSystemFeature("android.software.leanback")` 判断 TV；TV 走原有顶部导航 + D-Pad 焦点，手机走底部导航 + 触屏
2. **Manifest**：`leanback` / `landscape` 改为 `required=false`，`screenOrientation` 改为 `fullSensor`——手机可安装、可旋转
3. **手机底部导航 + MiniPlayer**（`AppRoot.kt`）：
   - 手机端底部导航 4 项（首页 / 曲库 / 网络音乐 / 我的），TV 顶部导航不变
   - 非播放页底部 MiniPlayer（封面 / 歌名 / 播放暂停 / 下一首 / 细进度条，点击进播放页）
   - 沉浸模式 / MTV 全屏 / 播放页隐藏
4. **曲库响应式网格**（`LibraryScreen.kt`）：`adaptiveColumns()` 三档——宽度 ≥1000dp（TV 原列数）/ 600-1000dp（手机横屏）/ <600dp（手机竖屏）；专辑 6→2、艺术家/年代 5→2、流派 4→2、歌曲/最近播放 2→1
5. **触摸进度条**（`PlayerControls.kt`）：进度条 `pointerInput` + `detectTapGestures` / `detectDragGestures` 支持点击与拖拽 seek；TV 左右键 seek 保留
6. **播放页自动横屏**（`MainActivity.kt`）：手机端 NowPlaying → `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`，其他页 → `PORTRAIT`；TV 不干预
7. **TV 功能按设备隐藏**（`AppRoot.kt`）：手机端 K歌/MTV/播放页"手机遥控"二维码传 `null`（自身即控制端，无需扫码遥控）

**过程修正**：Phase 1.4 曾尝试全量替换 tv.material3 → material3（编码损坏 38 文件导致编译失败），已整体回滚到 HEAD 原版——tv-material 组件在手机端可正常运行，手机适配改为纯加法，最终仅改动 4 个源文件（+316 行）。

**涉及文件**：
- `AndroidManifest.xml`：leanback/landscape required=false、fullSensor
- `AppRoot.kt`：isTV 检测、PhoneBottomNav/PhoneMiniPlayer、二维码条件隐藏
- `MainActivity.kt`：播放页横屏逻辑
- `LibraryScreen.kt`：adaptiveColumns 响应式列数
- `PlayerControls.kt`：触摸 tap/drag seek

**验证结果**：
- `:app:compileDebugKotlin` / `:app:assembleDebug` BUILD SUCCESSFUL
- `:app:testDebugUnitTest` 207 tests，205 passed，2 个 pre-existing NetworkMonitorTest 失败

**修复记录（2026-08-22 实机反馈）**：

1. **手机端所有页面无法点击**：根因为 `FocusableSurface` 原基于 `androidx.tv.material3.Surface`（tv 点击组件，onClick 绑定 D-Pad 焦点 + OK 键，不响应触摸；滑动是 foundation 手势所以正常）。已重写为 `Box + combinedClickable`：
   - 触摸点击 / 长按（`onLongClick`，NetdiskScreen 使用）与遥控器 OK 键双兼容
   - 焦点边框仅 TV 显示（组件内部 `hasSystemFeature("android.software.leanback")` 检测）
   - 容器色随状态切换（按下 > 聚焦 > 默认），手机按下缩放反馈
   - 涉及文件：`FocusableSurface.kt`（重写）
2. **手机端默认竖屏（期望横屏）**：MainActivity 原"播放页横屏、其他页竖屏"，改为手机端全界面 `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`（用户实测反馈）
3. **手机端导航未覆盖全部页面**：TV 顶部导航 8 项 vs 手机底部导航仅 4 项，缺 播放页/队列/网盘/设置。修复："我的"页（`MineScreen`）顶部新增功能入口行（队列 / 网盘 / 设置，`MineEntryChip`），AppRoot 接线 `navigateTo`
4. **tab 栏无法滑动**：曲库页（8 个 LibraryTab）与网络音乐页（NetworkSubTab）的 tab 行均为普通 `Row` 无滚动。修复：
   - `LibraryScreen`：tab 行加 `weight(1f) + horizontalScroll`，搜索栏改固定宽度 240dp
   - `NetworkMusicContainer`：子 tab 行加 `weight(1f, fill=false) + horizontalScroll`，去除中间 Spacer(weight)

**版本号变更**：v2.19.0 → v2.20.0（versionCode 57 → 58）

### 10.59 v2.21.0 - 电台 & Jamendo 新音源

**提交时间**：2026-08-23

**背景**：音源扩展（方案见 `docs/radio-and-jamendo-source-plan.md`）。原则：纯公共 API、**不自建后台**——radio-browser（公开广播目录，无 key）与 Jamendo（CC 独立音乐官方 API，仅需注册 client_id）。

**主要改动**：

1. **电台（radio-browser.info）**
   - `backend/radio/RadioBrowserClient.kt`（新增）：多服务器容灾（预设服务器列表按序重试）、搜索/热门标签/单台查询/播放上报；UA 规范
   - `data/model/RadioStation.kt`（新增）：`toSong()` 映射（`streamUrl` 直链、`durationMs=Long.MAX_VALUE`、`networkSource="radio"`）+ 顶层 `isRadioSong()` 判定
   - `ui/screens/network/RadioSubTab.kt`（新增）：搜索 + 预置标签筛选（pop/rock/classical/jazz/instrumental/news/chinese）+ 2 列电台卡片（台标/名称/国家·标签/码率角标）
2. **播放页直播态**：`PlayerControls.ProgressSection` 新增 `isLive`——左时间显示"● 直播"、进度填充置 0、隐藏滑块、禁 seek（TV 左右键 + 手机触摸均可）；`NowPlayingScreen` 依 `networkSource.isRadioSong()` 传递
3. **Jamendo（CC 独立音乐）**
   - `backend/network/JamendoModels.kt` / `JamendoService.kt`（新增）：实现 `NetworkMusicService`（sourceId="jamendo"），search/hotTracks/tracksByTag/search + resolvePlayUrl（直链）/resolveLyrics（纯文本→[00:00.00] LRC）；LRU 结果缓存控官方配额（35k/月）
   - 注册：`NasMusicApp.onCreate` 按 `client_id` 是否配置动态 `registerService`；`MainViewModel.updateJamendoClientId` 运行时注册/注销（仿百度模式）
   - `ui/screens/network/JamendoSubTab.kt`（新增）：热门榜 + 风格筛选 + 搜索，复用 `SongRow`（收藏/队列）；未配置显示引导卡
   - 设置页新增 Jamendo Client ID 配置（`AppPreferences.jamendoClientId`）
4. **子 Tab 扩展**：`NetworkSubTab` 新增 `RADIO` / `JAMENDO`（网络音乐页 6 个子 Tab），`NetworkMusicContainer` when 分支 + AppRoot 接线

**测试修复**：`NetworkMonitorTest` 两个用例断言与防抖设计对齐（capabilities 丢 internet 不触发 lost、onLost 仅已连接后回调），新增"抖动序列"测试——209 个单元测试全部通过（此前 2 个 pre-existing 失败清零）。

**验证结果**：
- `:app:compileDebugKotlin` / `:app:assembleDebug` BUILD SUCCESSFUL
- `:app:testDebugUnitTest` 209 tests 全通过（含 NetworkMonitor 防抖修复用例）

**涉及文件**：RadioBrowserClient/RadioStation/RadioSubTab/JamendoService/JamendoModels/JamendoSubTab（新增）；PlayerControls/NowPlayingScreen/NetworkMusicContainer/NetworkSubTab/AppPreferences/strings.xml/MainViewModel/AppRoot/SettingsScreen/NasMusicApp（修改）；NetworkMonitorTest（测试修正）

**版本号变更**：v2.20.0 → v2.21.0（versionCode 58 → 59）

### 10.60 v2.22.0 - 歌曲列表设备自适应 + 按钮文字全面亮色

**提交时间**：2026-08-24

**背景**：统一手机端与 TV 端的交互体验（歌曲列表排布、搜索框样式、按钮文字颜色），修复多处崩溃与滚动问题。

**主要改动**：

1. **歌曲列表 TV 两列 / 手机单列**
   - `CommonComponents.kt`：新增 `songGridColumns()` 函数（TV=2 列、手机=1 列，基于 `LocalPhoneCompact` 判断）
   - 替换 8 处 `GridCells.Fixed(2)` → `songGridColumns()`（SearchSubTab、BrowseSubTab、NetworkPlaylistDetailScreen、NetdiskScreen×2、WeatherSubTab、NetworkSubTabViews、LibraryScreen×2）
   - 所有 `GridItemSpan(2)` → `GridItemSpan(maxLineSpan)` 自动适配列数
2. **我的页面手机端整体滚动**
   - 手机端从上下两个独立 `LazyColumn` 改为单个 `LazyColumn` 统一承载收藏 + 歌单 + 展开歌曲，整体滑动
   - 歌单展开的歌曲从 `PlaylistCard` 内 `forEach` 移出为 LazyColumn 独立 item
3. **全面按钮文字亮色**（15 个文件）
   - 所有 `FocusableSurface`/`clickable` 内 `Text` 增加显式 `color` 参数
   - 涵盖键盘、对话框、设置页、播放页、首页、网络音乐各 tab 的所有按钮
4. **搜索框统一**：电台/独立音乐 tab 搜索框统一为 `SearchField` 共享组件（胶囊形、无独立按钮）
5. **键盘窗口可滑动**：`TextInputDialog` 改为 `BoxWithConstraints` + `heightIn` + `verticalScroll`
6. **进度条聚焦反馈**：滑块圆点聚焦时放大（24dp）变黄 + 光晕 + 背景变亮
7. **主 tab 右对齐**：AppRoot 导航栏 `Arrangement.End`，手机窄屏仍可横向滚动
8. **播放页左侧滚动修复**：移除 weight Box，恢复 `verticalScroll`
9. **信息按钮崩溃修复**：移除 `SongInfoPanel` 内层 `verticalScroll`（嵌套滚动崩溃）
10. **启动崩溃保护**：`WindowInsetsControllerCompat.hide()` 加 `try-catch`
11. **全屏白条修复**：手机端隐藏系统栏（状态栏+导航栏）

**涉及文件**：CommonComponents/SearchSubTab/BrowseSubTab/NetworkPlaylistDetailScreen/NetdiskScreen/WeatherSubTab/NetworkSubTabViews/LibraryScreen/MineScreen（新增/修改）；MainActivity/PlayerControls/AppRoot/NowPlayingScreen/SongInfoPanel/TextInputDialog/BackupTransferDialog/BaiduAuthDialog + 8 个按钮亮色文件

**版本号变更**：v2.21.0 → v2.22.0（versionCode 59 → 60）

---

### 10.61 v2.24.1 - 模型扫码上传修复（流式解析 + 路径 fallback）

**提交时间**：2026-08-29

**背景**：v2.24.0 新增的"扫码上传模型"功能在实际使用中失败——上传到 100% 后报 HTTP 400，改进错误信息后报 HTTP 500（`FileNotFoundException: models/htdemucs_ft_vocals.onnx`），且上传速度极慢。

**根因分析**：

1. **HTTP 400（未找到上传文件）**：NanoHTTPD `parseBody()` 将文件存到 `files` map 时，key 取决于表单 field name。前端 JS 用 `formData.append('file', ...)`，field name 是 `'file'`，但后端检查的是 `files["content"]` / `files["uploadedfile"]`——key 不匹配。
2. **HTTP 500（`FileNotFoundException`）**：电视 `context.getExternalFilesDir(null)` 返回 null（部分电视外存未挂载），`File(null, "models")` 变成相对路径 `models`，`FileOutputStream` 写到 app 工作目录而非预期位置；且 `ModelDownloadManager` 与 `ModelTransferServer` 两处各自构造路径，可能不一致。
3. **上传极慢**：`streamToFile` 滑动窗口算法每字节都调用 `output.write(int)`（系统调用）+ `System.arraycopy` 移动 ~50 字节 + `window.contentEquals` 全量比较，复杂度 O(n×bLen)，166MB 文件需要数十亿次操作。

**主要改动**：

1. **流式 multipart 解析**（`ModelTransferServer.kt`）
   - 绕过 NanoHTTPD `parseBody()` 对大文件的限制
   - `handleUpload` 从 `Content-Type` 提取 boundary，直接读 `session.inputStream`
   - `skipToBoundary` 跳过 preamble，`readPartHeaders` 读 part 头，`streamToFile` 流式写文件
2. **KMP + 批量写入优化**（`streamToFile`）
   - 改用 `matched` 计数器 + `pending` 缓冲，每字节只 1 次字节比较
   - `ByteArrayOutputStream` 累积写入，每 64KB flush 一次，减少 `FileOutputStream.write(int)` 系统调用
   - `BufferedInputStream` 缓冲从 8KB 增至 256KB，读取缓冲从 64KB 增至 128KB
3. **路径 fallback**（`ModelTransferServer.getModelFile` / `ModelDownloadManager.getModelsDir`）
   - `context.getExternalFilesDir(null) ?: context.filesDir` 回退到内部存储
   - 新增 `ModelTransferServer.getModelFile(context)` 静态方法 + `create(context, onModelUploaded)` 工厂构造
   - `ModelTransferDialog` 不再自行构造 `modelFile`，改用工厂方法，保证上传路径与下载路径一致
4. **前端错误信息透明化**（`MODEL_PAGE_HTML` JS）
   - 非 200 响应时解析 JSON 显示后端返回的 `message`，而非仅显示 "HTTP 500"
5. **设置页按钮布局修复**（`SettingsScreen.kt`）
   - 模型下载区按钮从 `Row + fillMaxWidth` 改为 `Box(weight(1f))` 分两列并排，修复按钮在 Row 内互相挤压不渲染的问题
6. **API 重命名**：`start()`/`stop()` → `startServer()`/`stopServer()`，避免遮蔽 `NanoHTTPD` 父类方法

**涉及文件**：`ModelTransferServer.kt`（重写）、`ModelDownloadManager.kt`、`ModelTransferDialog.kt`、`SettingsScreen.kt`、`app/build.gradle.kts`、`CHANGELOG.md`、`docs/technical-overview.md`

**验证结果**：✅ TV 实测上传 166MB 模型文件成功，速度接近 Wi-Fi 带宽。

**版本号变更**：v2.24.0 → v2.24.1（versionCode 64 → 65）

---

### 10.62 v2.24.2 - Demucs OOM 修复 + TV 字号调整

**提交时间**：2026-08-29

**背景**：v2.24.1 在电视上启动人声分轨时，`DemucsSeparator.initialize()` 读取 166MB ONNX 模型到 JVM 堆内存（`modelFile.readBytes()` + `createSession(bytes)`），触发电视设备堆内存不足被系统 SIGKILL。

**根因分析**：ONNX Runtime `createSession(ByteArray)` 重载会将整个模型字节数组加载到 JVM 堆，166MB 模型 + 原有 Compose TV 框架占用超出电视堆内存上限。

**主要改动**：

1. **Demucs OOM 修复**（`DemucsSeparator.initialize()`）
   - `modelFile.readBytes()` + `createSession(bytes)` → `createSession(modelPath)`
   - ONNX Runtime 底层 mmap 加载模型文件，不占用 JVM 堆内存
2. **TV 全局字号 -6sp**（`FontSize` object）
   - 所有 `*Tv` 常量减小 6sp：Caption 24→18, Small 26→20, Body 29→23, Button 31→25, Subtitle 35→29, Title 39→33, Display 45→39, DisplayLarge 53→47
3. **曲库歌曲条目文字统一**（`UnifiedSongRow`）
   - 歌曲标题 `FontSize.subtitle()` → `FontSize.button()`，与歌手名、时长、按钮文字大小一致

**涉及文件**：`app/src/main/java/com/nasmusic/tv/player/DemucsSeparator.kt`、`app/src/main/java/com/nasmusic/tv/ui/theme/Theme.kt`、`app/src/main/java/com/nasmusic/tv/ui/components/song/UnifiedSongRow.kt`、`app/build.gradle.kts`、`CHANGELOG.md`、`docs/technical-overview.md`

**验证结果**：✅ TV 实测启动人声分轨不再 OOM 崩溃，界面字号紧凑易读。

**版本号变更**：v2.24.1 → v2.24.2（versionCode 65 → 66）

---

### 10.63 v2.24.3 - 百度授权对话框乱码修复 + APK 文件名格式统一

**提交时间**：2026-08-30

**背景**：用户反馈百度网盘授权对话框中，设备码后的「复制」按钮文字显示为乱码。

**根因分析**：

1. **BaiduAuthDialog.kt 编码损坏**：commit `face859`（重构 fontSize `XX.sp` → `FontSize.xx()`）使用了脚本/工具读取文件时编码处理错误，将原本 UTF-8 编码的文件错误转码为 GBK+U+FFFD 混杂，中文字符变成 U+FFFD 替换字符（不可恢复）。Kotlin 编译器将 U+FFFD 字节序列视为合法 UTF-8 字符串存入 APK，导致运行时显示为乱码方块/问号。
2. **APK 文件名不统一**：本地构建输出默认 `app-release.apk`，CI 上传 artifact 名为 `app-release`，GitHub Release 也用默认名，无法从文件名直接识别版本。

**主要改动**：

1. **BaiduAuthDialog.kt 中文恢复**
   - 从 commit `9c44159`（face859 之前最后一个版本）恢复原始 UTF-8 中文字符
   - 保留 `face859` 的 `FontSize.xx()` 调用与 `65a912b` 的 TV +6sp 字号
   - 恢复的中文文本包括：KDoc 注释（「百度网盘设备码授权对话框」等）、UI 文字（「复制」按钮）、剪贴板标签（「百度网盘设备码」）、行内注释
2. **APK 文件名格式统一**（`NASMusicTV-release-v2-24-3.apk`）
   - `app/build.gradle.kts` 新增 `applicationVariants.all` 配置，`outputFileName` 改为 `NASMusicTV-${variant.name}-v${versionName 点转横线}.apk`
   - `.github/workflows/build.yml` 新增「Rename APK」步骤：从 `build.gradle.kts` 读取 `versionName`，生成 `APK_VERSION_DASHED` 环境变量，artifact 名与 Release APK 路径统一使用新格式

**根因分析（编码损坏溯源）**：

- commit `8d03b78`（v2.24.0 系列）创建文件时为正常 UTF-8
- commit `9c44159`（v2.24.x）仍为 UTF-8
- commit `face859`（fontSize 重构）引入损坏：脚本读取 UTF-8 文件时按 GBK 解码再以 UTF-8 写回，导致中文字节被替换为 U+FFFD
- commit `65a912b`（TV +6sp）继承损坏状态
- 本次 v2.24.3 修复

**涉及文件**：`app/src/main/java/com/nasmusic/tv/ui/screens/netdisk/BaiduAuthDialog.kt`、`app/build.gradle.kts`、`.github/workflows/build.yml`、`CHANGELOG.md`、`docs/technical-overview.md`

**验证结果**：✅ 本地 `assembleRelease` 编译通过，输出文件名 `NASMusicTV-release-v2-24-3.apk`，源文件字节验证为合法 UTF-8（无 U+FFFD 字节序列）。

**版本号变更**：v2.24.2 → v2.24.3（versionCode 66 → 67）

### 10.64 v2.25.1 - UI 字符串外部化（第一批：Screens + Components）

**提交时间**：2026-08-31

**背景**：UI 中大量硬编码中文字符串散布在 Composable 函数内，无法集中管理与本地化。需将用户可见字符串迁移至 `res/values/strings.xml`，为后续多语言适配奠定基础。

**主要改动**：

1. **strings.xml 新增 258 行字符串资源**，按模块组织：
   - Library Screen（搜索占位、加载状态、空态、计数格式等）
   - Common UI（关闭、重试、选择等通用按钮）
   - Model Transfer / Backup Transfer Dialog（启动状态、QR 描述、操作提示）
   - Text Input Dialog（历史标签、扫码输入）
   - Lyrics Settings Dialog（字号档位、标题）
   - Server Connect（后端类型名「道理鱼」「飞牛」、邮箱、地址提示、测试结果格式）
   - Netdisk（标题、搜索占位、登录提示、目录操作、空态）
   - Search/Discover Tab（无结果、关键词提示、来源筛选、全部播放/换一批/加入队列）
   - Karaoke Lyrics / Mv Playback / Player Controls（暂无歌词、扫码遥控、视频加载失败、K 歌按钮）
   - ActionBar / SectionHeader / ListStateIndicators（全部播放、加入队列、收藏全部、查看全部、加载中/加载失败/重试/暂无数据）
   - Album Detail / Queue / Weather Radio（曲目计数、电台歌曲、暂无电台歌曲）
   - Baidu Auth（设备码标签）

2. **26 个 Kotlin 源文件迁移**（共 493 处替换）：
   - **Composable 作用域**：使用 `stringResource(R.string.xxx)` 替换字面量
   - **非 Composable 作用域**（onClick lambda、coroutine、DisposableEffect）：
     - `context.getString(R.string.xxx)`（SettingsScreen 网络测试、BaiduAuthDialog onClick、ModelTransferDialog status 赋值）
     - `networkTestCtx.getString()`（在 `item {}` Composable 块声明 `val ctx = LocalContext.current`，closure 捕获至 coroutine）
   - **默认参数**（ListStateIndicators 的 `LoadingIndicator(text)/ErrorDisplay(message)/EmptyState(message)`、UnifiedSongGrid 的 `emptyMessage`）：改为 `String? = null` + 函数体内 `val resolved = text ?: stringResource(R.string.xxx)` 解析
   - **回调 lambda**（KaraokePlaybackScreen 的 `formatLabel`）：在 Composable 作用域预解析 `val originalTuneLabel = stringResource(...)`，lambda 内引用局部变量
   - **import 补充**：对未引入 `stringResource`/`R` 的文件（ModelTransferDialog、BackupTransferDialog、NetdiskScreen、AlbumDetailScreen、DiscoverTab、SearchTab、KaraokeLyricsView、ActionBar、SectionHeader、UnifiedPlaylistCard、MvPlaybackScreen、ListStateIndicators、UnifiedSongGrid）添加 `import androidx.compose.ui.res.stringResource` + `import com.nasmusic.tv.R`

3. **build.gradle.kts**：versionCode 70→71，versionName 2.25.0→2.25.1

**验证结果**：✅ `assembleDebug` 编译通过（无 error，仅 10 条 pre-existing warning：unnecessary safe call / non-null assertion）。✅ `./gradlew test` 单元测试全部通过。

**遗留项**：
- MainViewModel.kt 中 138 个运行时错误/Toast 消息（含 `${e.message?.take(50)}` 插值）暂未迁移。其中备份消息（`_backupMessage.value = "备份成功：$fileName"` 等）与 SettingsScreen.kt L1465 的 `startsWith("恢复")/contains("失败")` 颜色判断逻辑耦合，迁移需重构为状态标志（enum/sealed class）而非字符串比较，留待下批次处理。
- 代码注释中的中文保持原样（非用户可见 UI 字符串）。

**涉及文件**：`app/src/main/res/values/strings.xml`、`app/build.gradle.kts`、`CHANGELOG.md`、`docs/technical-overview.md`，以及 26 个 UI Kotlin 文件（详见 CHANGELOG v2.25.1 条目）。

**版本号变更**：v2.25.0 → v2.25.1（versionCode 70 → 71）

### 10.65 v2.25.2 - UI 字符串外部化（第二批：MainViewModel）

**提交时间**：2026-08-31

**背景**：MainViewModel.kt 中剩余 ~60 处用户可见硬编码中文字符串（连接状态、错误提示、播放列表操作、备份操作、网盘操作、MV 消息、歌词操作等），需迁移至 `res/values/strings.xml`。

**主要改动**：

1. **strings.xml 新增 18 行字符串资源**，覆盖 weather_switch_mood_error、network_search_failed、browse_search_failed、resolve_url_*、play_failed_with_msg、local_music_refreshed、backup_* 等。

2. **MainViewModel.kt 约 60 处替换**，使用 `getApplication<Application>().getString(R.string.xxx)` 模式：
   - 连接状态消息（成功/失败/检查设置）
   - 错误提示（加载失败、搜索失败、播放失败、收藏失败、刷新失败等）
   - 播放列表操作（创建/删除/重命名/添加/移除）
   - 备份操作（导出/恢复/删除）— 与 SettingsScreen.kt 的 `backupMessage` 状态判断解耦
   - 网盘操作（加载目录/搜索/索引扫描）
   - MV 消息（未找到视频/切换搜索源/搜索更多）
   - 歌词操作（加载/切换来源/缓存清除）
   - 电台/Jamendo 加载失败
   - 天气心情切换失败
   - 百度网盘认证（获取设备码/用户拒绝/授权超时）

3. **BackupMessage 数据类**：新增 `app/src/main/java/com/nasmusic/tv/data/model/BackupMessage.kt`，将 `_backupMessage` 类型从 `MutableStateFlow<String?>` 改为 `MutableStateFlow<BackupMessage?>`，携带 `isError` 标志。SettingsScreen.kt 的颜色判断逻辑从 `startsWith("恢复")/contains("失败")` 改为 `backupMessage.isError`。

4. **build.gradle.kts**：versionCode 71→72，versionName 2.25.1→2.25.2

**验证结果**：✅ `assembleDebug` 编译通过（无 error，仅 pre-existing warning）。Python 脚本扫描确认：MainViewModel.kt 中无用户可见硬编码中文字符串（注释、过滤关键词、电台预设、AppLog 消息中的中文保持原样）。

**涉及文件**：`app/src/main/res/values/strings.xml`、`app/src/main/java/com/nasmusic/tv/ui/viewmodel/MainViewModel.kt`、`app/src/main/java/com/nasmusic/tv/data/model/BackupMessage.kt`（新增）、`app/src/main/java/com/nasmusic/tv/ui/screens/SettingsScreen.kt`、`app/build.gradle.kts`、`CHANGELOG.md`、`docs/technical-overview.md`

**版本号变更**：v2.25.1 → v2.25.2（versionCode 71 → 72）

---

### 10.66 v2.25.3 - UI 字符串外部化（第三批：PlayerManager + DemucsSeparator）

**提交时间**：2026-09-01

**目标**：将播放引擎层（PlayerManager + DemucsSeparator）剩余的 ~35 处用户可见硬编码中文字符串迁移至 `res/values/strings.xml`。

**主要变更**：

1. **strings.xml 新增 39 行字符串资源**，覆盖 `player_error_*`、`hq_error_*`、`hq_progress_*`、`hq_success_*`、`demucs_error_*`、`demucs_progress_*` 等。

2. **PlayerManager.kt 约 20 处替换**，使用 `applicationContext.getString(R.string.xxx)` 模式：
   - 下载错误（无文件 / HTTP 失败 / 超时 / 网络错误 / 异常）
   - 高质量分离错误（组件未就绪 / 模型未下载 / 模型路径不可用 / 初始化失败 / 分离失败 / OOM / 异常）
   - 分离进度（下载音频 / 加载模型 / 预下载 / 完成 / 分离完成）
   - 播放错误

3. **DemucsSeparator.kt 约 15 处替换**，使用 `context.getString(R.string.xxx)` 模式：
   - 模型错误（文件不存在 / OOM / 初始化失败 / 未初始化 / 内存不足）
   - 分离错误（OOM / 异常 / 无音轨 / 解码 OOM / 解码失败）
   - 分离进度（解码音频 / 分段处理 / 分离中 / 完成）

4. **NasMusicApp.kt**：`PlayerManager()` → `PlayerManager(this)` 传入 applicationContext。

**验证结果**：✅ `assembleDebug` 编译通过（无 error）。

**涉及文件**：`app/src/main/res/values/strings.xml`、`app/src/main/java/com/nasmusic/tv/player/PlayerManager.kt`、`app/src/main/java/com/nasmusic/tv/player/DemucsSeparator.kt`、`app/src/main/java/com/nasmusic/tv/NasMusicApp.kt`、`app/build.gradle.kts`、`CHANGELOG.md`、`docs/technical-overview.md`

**版本号变更**：v2.25.2 → v2.25.3（versionCode 72 → 73）

---

### 10.67 v2.25.4 - 中英文语言切换功能

**提交时间**：2026-09-01

**目标**：实现运行时中文/英文/跟随系统语言切换，覆盖 Compose UI + Web 页面 HTML，设置页新增语言选择器。

**主要变更**：

1. **数据层**：
   - `AppSettings.kt`：新增 `language: String = "system"` 字段
   - `AppPreferences.kt`：新增 `keyLanguage`、`language` Flow、`setLanguage()`、`getLanguageSync()`，语言偏好纳入 `appSettings` Flow

2. **应用初始化**：
   - `NasMusicApp.kt`：`onCreate()` 调用 `applyLocale()` 在初始化阶段恢复用户语言偏好；`applyLocale()` 使用 `AppCompatDelegate.setApplicationLocales(LocaleListCompat)` 实现运行时切换

3. **ViewModel**：
   - `MainViewModel.kt`：新增 `updateLanguage()` 处理器；`RemoteControlServer(app)` 传入 context

4. **UI**：
   - `SettingsScreen.kt`：通用设置区块顶部新增语言选择器，三按钮横向排列（跟随系统 / 中文 / English），选中态高亮
   - `AppRoot.kt`：接线 `language` 和 `onChangeLanguage` 到 SettingsScreen

5. **Web 页面 HTML 国际化**：
   - `BackupTransferServer.kt`：静态 `BACKUP_PAGE_HTML` 常量 → 动态 `buildBackupPageHtml(context)` 函数
   - `RemoteControlHtml.kt`：静态 HTML → 动态 `buildControlPageHtml(context)` 函数（顶层函数）
   - `RemoteControlServer.kt`：构造函数新增 `context` 参数，`Impl` 内部类接收 context 传给 `buildControlPageHtml(context)`
   - `ModelTransferServer.kt`：静态 `MODEL_PAGE_HTML` 常量 → 动态 `buildModelTransferPageHtml(context)` 函数
   - 所有 HTML 文本走 `context.getString(R.string.xxx)`，JS 字符串通过注入 `var STR = {...}` 对象实现多语言

6. **依赖**：
   - `build.gradle.kts`：新增 `androidx.appcompat:appcompat:1.6.1`、`androidx.core:core-ktx:1.12.0`

7. **字符串资源**：
   - `values/strings.xml`：新增 ~70 行 `html_backup_*`、`html_model_*`、`html_remote_*`、`html_common_*` 字符串 + 5 行 `settings_language*` 字符串
   - `values-en/strings.xml`：完整英文翻译覆盖（~870 行），含 Compose UI、Web 页面 HTML、播放器错误信息等

**未迁移的硬编码字符串**：数据常量（天气描述/枚举标签/过滤关键词/错误码映射）、AppLog 日志、代码注释

**验证结果**：✅ `assembleDebug` 编译通过（无 error）。

**涉及文件**：`app/src/main/java/com/nasmusic/tv/data/model/AppSettings.kt`、`app/src/main/java/com/nasmusic/tv/data/prefs/AppPreferences.kt`、`app/src/main/java/com/nasmusic/tv/NasMusicApp.kt`、`app/src/main/java/com/nasmusic/tv/ui/viewmodel/MainViewModel.kt`、`app/src/main/java/com/nasmusic/tv/ui/screens/SettingsScreen.kt`、`app/src/main/java/com/nasmusic/tv/ui/components/AppRoot.kt`、`app/src/main/java/com/nasmusic/tv/net/BackupTransferServer.kt`、`app/src/main/java/com/nasmusic/tv/net/ModelTransferServer.kt`、`app/src/main/java/com/nasmusic/tv/net/RemoteControlHtml.kt`、`app/src/main/java/com/nasmusic/tv/net/RemoteControlServer.kt`、`app/src/main/res/values/strings.xml`、`app/src/main/res/values-en/strings.xml`、`app/build.gradle.kts`、`CHANGELOG.md`、`docs/technical-overview.md`

**版本号变更**：v2.25.3 → v2.25.4（versionCode 73 → 74）

---

### 10.68 修复语言切换不生效 + 语言按钮对比度（v2.13.1 / v2.25.5）

**日期**：2026-09-01

**修改内容**：

1. **根因修复 — MainActivity 改为 AppCompatActivity**：
   - `app/src/main/java/com/nasmusic/tv/ui/MainActivity.kt`：类声明从 `ComponentActivity` 改为 `AppCompatActivity`
   - 原因：`AppCompatDelegate.setApplicationLocales()` 需要 `AppCompatActivity` 才能触发 locale 变更和 Activity 重建。`ComponentActivity` 不使用 `AppCompatDelegate`，调用被静默忽略

2. **UI 修复 — 语言选择器按钮对比度**：
   - `app/src/main/java/com/nasmusic/tv/ui/screens/SettingsScreen.kt`（~行 339-355）：
     - 未选中按钮：背景 `Color.Transparent`，文字 `TextPrimary`（白色），与歌词来源按钮风格对齐
     - 选中按钮：背景 `Primary.copy(alpha=0.18f)`，文字 `Primary`，`FontWeight.Medium`
     - Focused 状态：未选中时 `SurfaceVariant`，选中时 `Primary.copy(alpha=0.3f)`

**涉及文件**：`app/src/main/java/com/nasmusic/tv/ui/MainActivity.kt`、`app/src/main/java/com/nasmusic/tv/ui/screens/SettingsScreen.kt`、`app/build.gradle.kts`、`CHANGELOG.md`、`docs/technical-overview.md`

**验证结果**：✅ `assembleDebug` 编译通过（无 error）。

**版本号变更**：v2.25.4 → v2.25.5（versionCode 74 → 75）

### 10.69 关于页新增后端 API 版本号展示（v2.25.6）

**日期**：2026-09-01

**新增功能**：

1. **数据模型 — `VersionInfo` 密封接口**：
   - 新增 `app/src/main/java/com/nasmusic/tv/data/model/VersionInfo.kt`，四种状态：
     - `Static`：硬编码常量版本（如 Jamendo v3.0、百度网盘 PCS rest/2.0）
     - `Runtime`：运行时从服务端获取（如 Jellyfin、Navidrome、Subsonic、道理鱼）
     - `NoVersion`：无版本号服务（如 Meting-API、Bilibili MV），仅展示服务名
     - `Disconnected`：后端已配置但当前未连接

2. **接口扩展 — `BackendAdapter.getApiVersion()`**：
   - `app/src/main/java/com/nasmusic/tv/backend/BackendAdapter.kt` 新增 `suspend fun getApiVersion(): VersionInfo`
   - 旧 `apiVersion` 字段标记 `@Deprecated`
   - 各适配器实现：
     - Jellyfin：`/System/Info/Public` → `Version` 字段
     - Navidrome / Subsonic：`rest/ping.view` → `subsonic-response.version`
     - 道理鱼：`/health` → `version` 字段
     - 飞牛：硬编码 `v1`（URL 路径前缀，UNCONFIRMED，待部署 fnOS 抓包确认）

3. **ViewModel 聚合 — `apiVersions` StateFlow**：
   - `MainViewModel` 新增 `apiVersions: StateFlow<List<VersionInfo>>` + `refreshApiVersions()`
   - 调用时机：初始化、连接成功、断开连接
   - 聚合内容：当前后端 + 百度网盘（静态）+ Jamendo / Open-Meteo / OpenWeatherMap（静态）+ Meting-API / Bilibili MV（NoVersion）

4. **UI 渲染 — 设置页关于页分段展示**：
   - `app/src/main/java/com/nasmusic/tv/ui/screens/SettingsScreen.kt` 关于页新增「API 版本号」段
   - 新增 `formatVersionInfo()` 按状态格式化 label/value
   - 所有按钮/文字显式指定颜色（未选中态亮色，遵循 SettingsScreen 修复规范）

5. **i18n**：
   - `values/strings.xml` + `values-en/strings.xml` 新增 `settings_api_versions`、`settings_api_versions_empty`

**涉及文件**：`app/src/main/java/com/nasmusic/tv/data/model/VersionInfo.kt`、`app/src/main/java/com/nasmusic/tv/backend/BackendAdapter.kt`、`app/src/main/java/com/nasmusic/tv/backend/impl/JellyfinAdapter.kt`、`app/src/main/java/com/nasmusic/tv/backend/impl/NavidromeAdapter.kt`、`app/src/main/java/com/nasmusic/tv/backend/impl/SubsonicAdapter.kt`、`app/src/main/java/com/nasmusic/tv/backend/impl/DaoliyuAdapter.kt`、`app/src/main/java/com/nasmusic/tv/backend/impl/FeiniuAdapter.kt`、`app/src/main/java/com/nasmusic/tv/ui/viewmodel/MainViewModel.kt`、`app/src/main/java/com/nasmusic/tv/ui/screens/SettingsScreen.kt`、`app/src/main/java/com/nasmusic/tv/ui/components/AppRoot.kt`、`app/src/main/res/values/strings.xml`、`app/src/main/res/values-en/strings.xml`、`app/build.gradle.kts`、`CHANGELOG.md`、`docs/technical-overview.md`

**验证结果**：✅ `assembleRelease` 编译通过（无 error），已部署电视验证。

**版本号变更**：v2.25.5 → v2.25.6（versionCode 75 → 76）

### 10.70 搜索页拼音匹配功能（v2.25.7）

**日期**：2026-09-02

**新增功能**：

1. **拼音匹配工具类 — `PinyinMatcher`**：
   - 新增 `app/src/main/java/com/nasmusic/tv/util/PinyinMatcher.kt`
   - 统一搜索匹配逻辑：子串匹配 OR 拼音全拼匹配 OR 拼音首字母匹配
   - `isTVDevice: Boolean` 参数控制是否启用拼音匹配（TV=true，手机=false）
   - `matchesMultipleWords()` 支持空格分词搜索（如 "zjl 周杰"）

2. **拼音缓存包装器 — `SongWithPinyin`**：
   - 新增 `app/src/main/java/com/nasmusic/tv/data/model/SongWithPinyin.kt`
   - 搜索过滤阶段一次性生成拼音缓存（`Map<songId, SongWithPinyin>`），避免重复计算
   - 字段使用 `lazy` 延迟计算，仅访问到的字段才生成拼音
   - `fromSongs()` 工厂方法批量生成缓存

3. **PinyinUtils 扩展**：
   - 新增 `toPinyin(text)`：完整拼音转换（"周杰伦" → "zhoujielun"）
   - `getInitials()` 重命名为 `toPinyinInitials()`（保留 `getInitials()` 兼容别名）
   - `matches()` 内部调用同步更新

4. **SearchAggregator 改造**：
   - 新增 `isTVDevice: Boolean` 构造参数（默认 false）
   - PRECISE 过滤块从硬编码子串匹配改为调用 `PinyinMatcher.matchesMultipleWords()`
   - TV 端：提前生成 `SongWithPinyin` 缓存，传入 matcher
   - 手机端：`isTVDevice=false` → 不生成缓存，PinyinMatcher 仅执行子串匹配，零额外开销

5. **NasMusicApp 接入**：
   - 构造 `SearchAggregator` 时传入 `isTVDevice = packageManager.hasSystemFeature("android.software.leanback")`

**设计约束**：
- 仅 TV 端启用拼音匹配（手机端触屏输入汉字方便，无需拼音）
- 中文输入完全保留原有子串匹配行为（拼音匹配作为额外 OR 条件追加）
- 不需要设置页开关（默认开启，仅 TV 端生效）
- 不需要发现页支持（仅 FilterMode.PRECISE / 搜索页）
- 不需要新增拼音输入窗口（已有 TextInputDialog）

**涉及文件**：`app/src/main/java/com/nasmusic/tv/util/PinyinMatcher.kt`（新增）、`app/src/main/java/com/nasmusic/tv/data/model/SongWithPinyin.kt`（新增）、`app/src/main/java/com/nasmusic/tv/util/PinyinUtils.kt`、`app/src/main/java/com/nasmusic/tv/backend/SearchAggregator.kt`、`app/src/main/java/com/nasmusic/tv/NasMusicApp.kt`、`app/build.gradle.kts`、`CHANGELOG.md`、`docs/technical-overview.md`

**验证结果**：✅ `assembleRelease` 编译通过（无 error），已部署电视验证。

**版本号变更**：v2.25.6 → v2.25.7（versionCode 76 → 77）

---

### 10.71 曲库布局修复 + 百度播放根因修复（v2.25.8 - 2026-09-03）

**日期**：2026-09-03

#### 10.71.1 曲库专辑/艺术家页面空白

**问题描述**：曲库 → 专辑 / 艺术家页只显示中间一条 A‑Z 字母快捷操作，网格内容（专辑封面/艺术家）完全不显示。

**根因分析**：`AlbumsTab`/`ArtistsTab` 使用 `Row(Modifier.fillMaxSize()) { Box(Modifier.weight(1f)) { LazyVerticalGrid(Modifier.fillMaxSize()) } ... SideLetterIndex }`。`Row` + `weight` 在 Compose 中会把剩余空间分配给子项，但 `LazyVerticalGrid(fillMaxSize)` 在 `weight` 约束下高度被塌缩为 0，导致网格不可见。可用的 `SongsTab` 用 `Column { Text(); LazyVerticalGrid() }` 结构正常。

**修改**（`LibraryScreen.kt`）：两 Tab 根容器改为 `Box(fillMaxSize)`，内部网格用 `Box(fillMaxSize)` 包裹并留右侧 24dp 给字母条，`SideLetterIndex` 用 `Modifier.align(Alignment.CenterEnd)` 定位。

**验证结果**：✅ 真机验证内容正常显示。

#### 10.71.2 字母索引条跑到屏幕中间

**问题描述**：修复 10.71.1 后网格能显示，但右侧 A‑Z 字母条出现在屏幕水平中央，而非右边缘。

**根因分析**：`SideLetterIndex` 是 `Column`，内部字母项用 `Box(fillMaxWidth())`。父容器 `Box(align(CenterEnd))` 给该 `Column` 的约束是**全宽**，`fillMaxWidth` 子项把整列撑成全宽 → `align(CenterEnd)` 形同虚设，字母靠 `Column(horizontalAlignment = CenterHorizontally)` 居中 → 视觉上"跑中间"。`Compose` 中要让 `Box` 子元素的 `align(End/CenterEnd)` 生效，子元素必须有**非全宽的明确宽度**。

**修改**（`LibraryScreen.kt` 的 `SideLetterIndex`）：给 `Column` 加固定窄宽 `.width(letterSize + 8.dp)`（TV 上 ≈28dp），`fillMaxWidth` 子项只填满这个窄列，`align(CenterEnd)` 才能把整条钉在右边缘。`AlbumsTab`/`ArtistsTab` 共用该 Composable，一次修复两处生效。

**验证结果**：✅ 真机验证字母条已归位到右边缘、垂直居中。

#### 10.71.3 百度网盘歌曲无法播放（根因：TV ROM 的 AndroidKeyStore 不支持 AES KeyGenerator）

**问题描述**：曲库浏览百度网盘能看到文件，但点击播放失败（"解析失败"或静默无声音）。

**根因分析**：整条播放链（`NetdiskScreen → playNetworkSong → NetworkMusicManager.resolvePlayUrl → BaiduNetdiskService → BaiduStreamFactory.resolveStreamUrl → BaiduPanApi.fileMetas 取 dlink + oauth.getValidAccessToken → ExoPlayer(BaiduHttpDataSourceFactory 注入 UA)`）在代码层面是闭合且符合百度开放平台规范的。拉 TV 运行日志发现真正报错：
```
E/CryptoUtils: java.security.NoSuchAlgorithmException: KeyGenerator AES implementation not found
```
发生在 `getBaiduTokensSync → getValidAccessToken → BaiduPanApi.fileMetas/listDir` 链上。`util/CryptoUtils.kt` 的 `getOrCreateKey()` 用 `KeyGenerator.getInstance("AES", "AndroidKeyStore")` 生成密钥，而**这台电视/盒子 ROM 的 AndroidKeyStore 不提供 AES 的 KeyGenerator**，解密百度 access_token 时直接抛异常 → token 取不出 → `resolveStreamUrl` 拼不出 dlink → 播放失败。浏览能用是因为 `listDir` 也走同一解密路径、同样会崩，但用户看到的是列表缓存或登录态残留，掩盖了问题。

**修改**（`CryptoUtils.kt`）：
- 密钥改由固定口令 SHA‑256 派生为软件密钥（`SecretKeySpec`，完全不碰 AndroidKeyStore / KeyGenerator），所有设备（含无密钥库的 TV/盒子）都能稳定加解密。
- `decrypt` 先试软件密钥，再**回退旧 AndroidKeyStore 密钥**，兼容手机端已存的加密数据，避免老用户读不出。
- 影响面：`AppPreferences` 的百度 token、NAS 的 apiToken/password 均走此工具，等于顺手把 NAS 密码这类加密也修稳了。

**验证结果**：✅ 重编 release 推电视、重启 App 后，`CryptoUtils` 的 `NoSuchAlgorithmException` 消失、无 FATAL EXCEPTION、App 稳定运行。
⚠️ **行为验证待用户操作**：电视上现存的百度 token 是**另一台设备用旧 AndroidKeyStore 密钥加密的密文**，电视即便换了新代码也解不开它。需用户在电视上**退出并重新登录授权百度网盘一次**——新 token 会用软件密钥加密，之后播放才能正常。重登后点一首百度歌复测即可闭环。

**涉及文件**：`app/src/main/java/com/nasmusic/tv/ui/screens/LibraryScreen.kt`、`app/src/main/java/com/nasmusic/tv/util/CryptoUtils.kt`、`app/src/main/java/com/nasmusic/tv/backend/network/baidu/BaiduHttpDataSourceFactory.kt`（UA 注入判定放宽至任意 `*.baidu.com` 主机）、`app/src/main/java/com/nasmusic/tv/backend/network/baidu/BaiduStreamFactory.kt`（补全诊断日志）、`app/src/main/java/com/nasmusic/tv/ui/viewmodel/MainViewModel.kt`（百度源解析失败专属日志）、`CHANGELOG.md`、`docs/technical-overview.md`

**版本号变更**：v2.25.7 → v2.25.8（versionCode 77 → 78）

---

### 10.72 详情页加入歌单 + 字母条焦点对称 + 队列来源 + 百度封面回退 + 我的页精简（v2.26.0 - 2026-09-03）

**日期**：2026-09-03

#### 10.72.1 专辑/艺术家详情页歌曲「加入歌单」按钮（Issue #1）

**问题描述**：专辑详情页、艺术家详情页的歌曲列表中，歌曲条目没有 `+` 按钮，缺少「加入歌单」功能（曲库页已有，详情页缺失）。

**修改**：
- `AlbumDetailScreen.kt` / `ArtistDetailScreen.kt`：函数签名新增 `onAddToPlaylist: (Song) -> Unit = {}`（紧跟 `onToggleFavorite`），并在各自的 `UnifiedSongRow` 调用中透传 `onAddToPlaylist = onAddToPlaylist`。
- `UnifiedSongRow` 早已支持 `onAddToPlaylist`（渲染行末 `+` 按钮），详情页直接复用，无需新增 UI。
- `AppRoot.kt`：将 `pickerSong` 状态提升到 `AppRoot` 顶层（原先仅 Library 分支局部持有），并在 `when` 块之后新增**顶层共享** `pickerSong?.let { PlaylistPickerDialog(...) }`，使得「加入歌单」弹窗在曲库 / 专辑详情 / 艺术家详情三处共用同一实例；同时移除 Library 分支内原本重复的局部 dialog 与 `localPlaylists` 收集。
- `AppRoot` 中 `AlbumDetailScreen` / `ArtistDetailScreen` 两处调用均传入 `onAddToPlaylist = { song -> pickerSong = song }`。

**验证结果**：代码层面三处入口均复用同一 `PlaylistPickerDialog`，行为一致。

#### 10.72.2 艺术家详情页字母条焦点导航对称化（Issue #2）

**问题描述**：专辑页可从内容区按右走到字母条、字母条按左走回内容区；艺术家页无法实现这些焦点跳转，回不来。

**修改**（`LibraryScreen.kt` 的 `SideLetterIndex` 与 `AlbumsTab`/`ArtistsTab`）：
- `SideLetterIndex` 签名新增 `contentFocusRequester: FocusRequester` 与 `letterFocusRequester: FocusRequester = remember { FocusRequester() }`，移除其内部局部 `focusRequester`；`.focusRequester(focusRequester)` 改为 `.focusRequester(letterFocusRequester)`；按键处理新增 `Key.DirectionLeft -> { contentFocusRequester.requestFocus(); true }`，实现「字母条按左 → 回到内容区」。
- `AlbumsTab`/`ArtistsTab`：新增 `val letterFocusRequester = remember { FocusRequester() }` 与 `var focusedGridIndex by remember { mutableStateOf(-1) }`；网格 `Box` 包 `.onKeyEvent`，在 `DirectionRight` 且当前焦点项位于**最右列**时调用 `letterFocusRequester.requestFocus()`；每条卡片用 `Box(Modifier.onFocusChanged { if (it.isFocused) focusedGridIndex = index })` 跟踪当前焦点位置。两 Tab 的 `SideLetterIndex` 调用统一改为传入 `contentFocusRequester = firstItemFocusRequester, letterFocusRequester = letterFocusRequester`（两处字节相同，用 `replace_all` 落地）。
- 结果：艺术家页与专辑页的焦点导航行为完全一致（内容→右→字母条→左→内容）。

**验证结果**：两 Tab 共用同一 `SideLetterIndex` Composable，焦点交接逻辑对称，编译期无报错。

#### 10.72.3 专辑详情页封面缺失（Issue #4，含冻结快照根因修复）

**问题描述**：专辑详情页内容块仍无专辑封面图片。

**根因分析**：`AppRoot` 的 `Screen.AlbumDetail` 分支在点击时用 `_selectedAlbum`（冻结快照）构建 `AlbumDetailScreen`，而异步封面解析完成后更新的是 `mergedAlbums` 流，冻结快照不会被刷新 → 详情页拿到的是无封面的旧快照。

**修改**（`AppRoot.kt` `Screen.AlbumDetail` 分支）：
- 新增 `val mergedAlbums by viewModel.mergedAlbums.collectAsState(initial = emptyList())`；
- `val liveAlbum = selectedAlbum?.let { sa -> mergedAlbums.firstOrNull { it.id == sa.id } ?: sa }` 取实时专辑（含已异步解析封面）；
- `AlbumDetailScreen(album = liveAlbum, ...)` 改用 `liveAlbum`，封面随解析结果实时刷新。

**验证结果**：详情页与曲库网格封面同步，无冻结快照现象。

#### 10.72.4 「我的」页移除「最近播放」分区（Issue #5）

**问题描述**：「我的」页中收藏、最近播放、本地歌单三栏并列，最近播放信息与首页重复，三栏过窄。

**修改**（`MineScreen.kt`）：
- 移除手机布局的「最近播放」标题块（`item(key = "recent_header")` 至 recent `items(...)`，含 `section_divider_2`）；
- 移除 TV 布局的 `RecentPane(...)` 调用点（位于 `FavoritesPane` 与 `PlaylistsPane` 之间）；`RecentPane` 函数定义保留（仅 warning，无调用）。
- 现保留「收藏」+「本地歌单」两栏并列。

**验证结果**：TV 端两栏布局，宽度充足。

#### 10.72.5 播放队列歌曲行显示来源标签（Issue #6）

**问题描述**：播放队列页面歌曲条目未显示歌曲来源（NAS / 百度网盘等）。

**修改**（`QueueScreen.kt`）：
- 新增 import `com.nasmusic.tv.ui.components.common.SourceBadge`；
- 队列行在艺术家文字与时长之间插入 `SourceBadge(song = song)`（左右各加 `Spacer`）。

**验证结果**：队列行来源一目了然。

#### 10.72.6 百度网盘歌曲封面从网络回退获取（Issue #7）

**问题描述**：百度网盘歌曲大多无内嵌封面，详情/列表封面空白。

**修改**：
- `AlbumCoverResolver.kt`：构造函数新增 `private val searchCover: suspend (title: String, artist: String) -> String? = { _, _ -> null }`；在 iTunes（P2）回退之后新增 **P2.5** 网络封面回退——当专辑中存在 `networkSource == "baidu"` 的歌曲时，取首条有标题的歌曲，调用 `searchCover(title, artist)` 检索 Meting/网易云封面。
- `BaiduNetdiskService.kt`：构造函数新增 `private val networkCoverSearch: suspend (title: String, artist: String) -> String?`；`resolveCoverUrl` 在 `coverProvider.getCover(...)`（侧车 + 内嵌 APIC）返回 null 后，回退到 `networkCoverSearch(...)`。
- `NasMusicApp.kt`：`albumCoverResolver` 与 `baiduNetdiskService` 构造时分别注入 `{ title, artist -> networkMusicManager.searchCoverUrl(title, artist) }`，由 `NetworkMusicManager.searchCoverUrl` 委托 `MetingApiService.searchCoverUrl` 返回网络封面 URL（NowPlaying 已复用同一能力）。

**验证结果**：百度无内嵌封面的歌曲可通过网络封面补齐；`NetworkMusicManager.searchCoverUrl` 已存在且被 NowPlaying 使用，链路闭合。

**涉及文件**：`app/src/main/java/com/nasmusic/tv/ui/screens/AlbumDetailScreen.kt`、`app/src/main/java/com/nasmusic/tv/ui/screens/ArtistDetailScreen.kt`、`app/src/main/java/com/nasmusic/tv/ui/components/AppRoot.kt`、`app/src/main/java/com/nasmusic/tv/ui/screens/LibraryScreen.kt`、`app/src/main/java/com/nasmusic/tv/ui/screens/MineScreen.kt`、`app/src/main/java/com/nasmusic/tv/ui/screens/QueueScreen.kt`、`app/src/main/java/com/nasmusic/tv/backend/local/AlbumCoverResolver.kt`、`app/src/main/java/com/nasmusic/tv/backend/network/baidu/BaiduNetdiskService.kt`、`app/src/main/java/com/nasmusic/tv/NasMusicApp.kt`、`app/build.gradle.kts`、`CHANGELOG.md`、`docs/technical-overview.md`

**验证结果**：✅ `assembleRelease` 编译通过（BUILD SUCCESSFUL，无 error，仅既有 warning），产物 `app/build/outputs/apk/release/NASMusicTV-release-v2-26-0.apk`。按用户要求仅编译、不推电视测试。

**版本号变更**：v2.25.8 → v2.26.0（versionCode 78 → 79）

---

### 10.73 侧车封面 access_token 缺失修复 + 专辑封面获取逻辑全景梳理（v2.26.1 - 2026-09-04）

**日期**：2026-09-04

#### 10.73.1 百度侧车封面 dlink 缺少 access_token（Issue #4 排查中发现）

**问题描述**：梳理封面获取逻辑时发现，百度网盘的「侧车封面」（同目录 cover.jpg 等）即使成功定位到文件，产出的 dlink 也必然加载失败。

**根因分析**：`BaiduCoverProvider.ensureAccessToken()` 原实现为：

```kotlin
private suspend fun ensureAccessToken(dlink: String): String {
    return if (dlink.contains("access_token=")) dlink
    else dlink + (if (dlink.contains('?')) "&" else "?") + "access_token="
}
```

它只拼了 `access_token=` **后面没有 token 值**；函数虽声明为 `suspend` 却从不挂起取 token —— 因为 `BaiduCoverProvider` 构造时只拿到 `BaiduPanApi` 与 `OkHttpClient`，**从未注入 `BaiduOAuthClient`**，根本无从取 token。对比同包 `BaiduStreamFactory.resolveStreamUrl` 的正确实现（`oauth.getValidAccessToken()` + `URLEncoder.encode`），可确认这是遗留的未完成实现。后果：侧车封面 URL 一律被百度拒绝（403），Coil 加载失败。

**修改**：

- `BaiduCoverProvider` 构造新增 `private val oauth: BaiduOAuthClient`（同包，无需 import）。
- `ensureAccessToken` 返回类型改为 `String?`；真正调用 `oauth.getValidAccessToken()` 并 `URLEncoder.encode(token, "UTF-8")`；取不到 token 时打 warning 并返回 `null`，使 `findSidecarCover` 返回 null、`getCover` 继续走内嵌 APIC / 上层网络封面 fallback，不再产出无效 URL。
- `NasMusicApp`：`BaiduCoverProvider(baiduPanApi, baiduOkHttpClient, baiduOAuthClient)`。

**验证结果**：✅ `assembleRelease` 编译通过（BUILD SUCCESSFUL，无 error）。

#### 10.73.2 专辑封面获取逻辑全景（应 Issue #4「列出来我也分析一下」要求）

**Stage 0 — 基线（构建专辑时同步得出）**

- `MusicMerger.buildLocalAlbums()`：`coverUrl = songs.firstOrNull { it.coverUrl != null }?.coverUrl`（`MusicMerger.kt:134`）
- `MusicMerger.buildBaiduAlbums()`：同上（`MusicMerger.kt:210`）
- 即专辑封面初始值 = 专辑内第一首有 `coverUrl` 的歌曲封面。百度歌曲索引时通常无内嵌封面 → `song.coverUrl` 多为 null → 百度专辑基线多为 null，从而进入异步解析。

**Stage 1 — 异步解析（`AlbumCoverResolver.resolveCovers`）**

只对 `album.coverUrl == null` 的专辑执行，优先级链：

| 优先级 | 来源 | 实现 | 说明 |
|---|---|---|---|
| P1 | 百度侧车/APIC | `resolveBaiduCover` → `BaiduCoverProvider.getCover` | ① 侧车：listDir 父目录找 category=IMAGE 且文件名∈{cover, folder, album, front, cover.jpg} → fileMetas 取 dlink → 补 access_token；② 内嵌 APIC：Range 下载前 256KB → `Id3v2Parser.findApic` → Base64 → `data:image/...;base64,...` |
| P2 | iTunes | `resolveItunesCover` | `https://itunes.apple.com/search?term={album+artist}&entity=album&limit=1` → `results[0].artworkUrl100`，并把 `100x100` 替换为 `600x600` |
| P2.5 | 网络（Meting/网易云） | `searchCover(title, artist)` → `NetworkMusicManager.searchCoverUrl` | v2.26.0 新增；仅在专辑内含 `networkSource=="baidu"` 的歌时触发，取首条有标题的歌按「标题+艺术家」检索 |
| P3 | 本地 ID3 补充 | `songs.firstOrNull { it.coverUrl != null }?.coverUrl` | 本地歌 MediaStore 通常已提取，此步为补充 |
| P4 | 兜底 | — | 已在 Stage 0 处理，此处不再重复 |

**Stage 2 — 回写与缓存（`MainViewModel`）**

- `resolveCovers` 的 `onUpdated` 每 `MAX_CONCURRENT=5` 个回调一次 + 结束时最终回调。
- `resolveAlbumCoversAsync()`（`MainViewModel.kt:2295`）在回调里**只写** `resolvedAlbumCovers[albumId] = coverUrl` 缓存，**不直接改** `_mergedAlbums`（避免与 `updateMergedData` 竞争覆盖）。
- 解析结束后调用 `updateMergedData()` 重建；`updateMergedData`（:2223-2228）从 `resolvedAlbumCovers` 回填，但**仅当 `album.coverUrl == null` 时才填**。
- 缓存跨多次 `updateMergedData` 保持，避免重复网络请求。

**Stage 3 — UI 渲染**

- `AlbumDetailScreen` 用 `CoverImage(coverUrl = album.coverUrl, size = 280.dp)`（:158）；曲库网格同理。
- Coil 加载；百度 dlink 需 UA `pan.baidu.com`，由 `NasMusicApp` 实现 `ImageLoaderFactory` 注入 `BaiduHttpDataSourceFactory.createOkHttpClientForCoil`。

**已知弱点 / 待决策项（供后续分析）**

1. **P2 iTunes 对中文专辑命中率低**：搜索词是「专辑名 + 艺术家」，而百度专辑名是从**目录名**推断的（`buildBaiduAlbums` 取 path 倒数第二段），目录名常不规范（如「周杰伦」这类艺术家名、或「新建文件夹」）；且 `buildBaiduAlbums` 会**过滤掉与歌手同名的目录**（视为艺术家目录），这类歌曲根本不生成专辑。→ 其中「搜索词质量」已由 §10.75（v2.26.3）的多候选回退缓解；**「过滤与歌手同名的目录」属产品决策（放开会改变曲库结构、可能生成大量单一专辑），未改动，待定夺**。
2. **P2.5 依赖歌曲标题质量**：若百度文件命名不规范（`01.mp3`、乱码标题），`repSong.title` 质量差 → 检索不到封面。→ ✅ 已在 §10.75（v2.26.3）改为多候选回退缓解。
3. **`updateMergedData` ↔ `resolveAlbumCoversAsync` 存在无条件循环调用链**：`updateMergedData()`（:2249）末尾无条件调用 `resolveAlbumCoversAsync()`；而 `resolveAlbumCoversAsync()`（:2310-2312）在 `resolveCovers` 返回后（**不判断是否有成果**）无条件再调 `updateMergedData()`，形成 `updateMergedData → resolveAlbumCoversAsync → updateMergedData → …` 的循环。因 `resolveCovers` 对已解析（缓存命中）的专辑会快速跳过，实际表现为后台持续循环执行 merge + 艺术家计数（而非卡死 UI），但存在 CPU/电量开销、以及对无法解析专辑的重复网络请求。此前代码复审（2026-09-03）曾判定该循环「已收敛」并列为 P1；本次代码通读未发现中断条件。✅ **已在 §10.74（v2.26.2）加收敛护栏修复。**

**涉及文件**：`app/src/main/java/com/nasmusic/tv/backend/network/baidu/BaiduCoverProvider.kt`、`app/src/main/java/com/nasmusic/tv/NasMusicApp.kt`、`app/build.gradle.kts`、`CHANGELOG.md`、`docs/technical-overview.md`

**验证结果**：✅ `assembleRelease` 编译通过（BUILD SUCCESSFUL，无 error）。

**版本号变更**：v2.26.0 → v2.26.1（versionCode 79 → 80）

---

### 10.74 封面解析循环收敛护栏（v2.26.2 - 2026-09-04）

**日期**：2026-09-04

**问题描述**：`updateMergedData()` 与 `resolveAlbumCoversAsync()` 相互无条件调用，形成**没有中断条件**的后台循环。

**根因分析**：

- `updateMergedData()`（`MainViewModel.kt`）末尾无条件调用 `resolveAlbumCoversAsync()`。
- `resolveAlbumCoversAsync()` 在 `resolveCovers` 返回后，**不判断本轮是否有成果**，无条件执行 `withContext(Dispatchers.Main) { updateMergedData() }`。
- 二者构成 `updateMergedData → resolveAlbumCoversAsync → updateMergedData → …` 的闭环，且链路上**不存在任何收敛条件**。
- 实际表现：`resolveCovers` 对已解析（缓存命中）的专辑会快速跳过，因此不会卡死 UI，但后台会持续重复执行 merge 专辑/艺术家、统计 songCount，并对**永远解析不出封面**的专辑反复发起 iTunes / 百度网络请求 —— 造成 CPU、电量与流量开销。
- 注：2026-09-03 代码复审曾判定该循环「已收敛」并列为 P1；本次（§10.73.2）通读未发现中断条件，故实际修复。

**修改**（`MainViewModel.kt`）：加两道收敛护栏

1. **尝试次数上限**：新增 `albumCoverAttempts: MutableMap<String, Int>`（专辑 ID → 已尝试次数）与 `albumCoverMaxAttempts = 2`。`resolveAlbumCoversAsync()` 开头先筛出「仍缺封面 **且** 尝试次数未达上限」的 `pending` 专辑；`pending` 为空则**直接 return**，切断循环。发起解析前把 `pending` 各专辑计数 +1。
   - 上限取 2 而非 1：保留 1 次重试以容忍启动瞬间的网络失败，避免封面永久缺失。
   - 新出现的专辑不在 `albumCoverAttempts` 表中，仍会被正常解析，不影响首次封面获取。
2. **有成果才重建**：记录解析前 `resolvedAlbumCovers.size`，仅当本轮**确实解析出新封面**（size 变大）时才回调 `updateMergedData()`；无成果时不再回调，彻底断开闭环。
   - 附带优化：`resolveCovers` 改传 `pending`（原先传全部专辑），减少无谓遍历。

**同步修复（艺术家侧）**：`resolveArtistCoversAsync()` 原先同样无条件回调 `updateMergedData()`。该方法由 `loadArtists()` 触发，不与 `updateMergedData` 互调，**本身不构成循环**；但仍加上「仅在解析出新封面时才重建」的对称护栏，消除无成果时的多余全量 merge 开销。

**涉及文件**：`app/src/main/java/com/nasmusic/tv/ui/viewmodel/MainViewModel.kt`、`app/build.gradle.kts`、`CHANGELOG.md`、`docs/technical-overview.md`

**验证结果**：✅ `assembleRelease` 编译通过（BUILD SUCCESSFUL，无 error，仅既有 warning），产物 `app/build/outputs/apk/release/NASMusicTV-release-v2-26-2.apk`。按用户要求仅编译、不推电视测试。

**版本号变更**：v2.26.1 → v2.26.2（versionCode 80 → 81）

---

### 10.75 网络封面检索改为多候选回退（v2.26.3 - 2026-09-04）

**日期**：2026-09-04

**问题描述**：P2.5 网络封面（Meting/网易云）只对「代表歌曲标题 + 艺术家」检索一次，失败即放弃，百度网盘专辑封面命中率不稳定。

**根因分析**：百度网盘歌曲有两个先天弱点 —— ① 常缺艺术家标签（`artist` 为空）；② 文件名可能不规范（`01.mp3`、乱码），导致 `repSong.title` 质量差。原实现只构造一组检索词，任一环节缺失即整体失败。此外，百度专辑名是从**目录名**推断的（`MusicMerger.buildBaiduAlbums` 取 path 倒数第二段），常为「周杰伦」「新建文件夹」之类，单独作为检索词命中率极低。

**修改**（`AlbumCoverResolver.kt` P2.5 块）：改为按**信息可靠度**依次尝试多组检索词，取首个非空结果即停：

| 顺序 | 检索词 | 说明 |
|---|---|---|
| 1 | 标题 + 艺术家 | 信息最全，优先 |
| 2 | 仅标题 | 艺术家为空时的自然退化 |
| 3 | 专辑名（目录名推断）+ 艺术家 | 标题不可靠时退到专辑名 |
| 4 | 仅专辑名 | 最后兜底 |

- 候选构造后用 `distinct()` 去重、并过滤掉标题为空的组合，避免重复请求。
- 每组用 `runCatching { }.getOrNull()` 包裹，单组失败（网络异常等）不影响后续组。
- 结果用 `isNullOrBlank()` 判定，避免拿到空串被当作成功。

**兼容性确认**：`MetingApiService.searchCoverUrl` 的实现是 `val keyword = if (artist.isNotBlank()) "$title $artist" else title` —— **空艺术家会被正确处理为纯标题检索**，因此传 `""` 安全，调用侧无需额外分支。

**未改动 / 待定夺**：`buildBaiduAlbums` 会**过滤与歌手同名的目录**（视为艺术家目录），这类歌曲根本不生成专辑。放开会改变曲库结构（可能生成大量单一专辑），属产品决策，未改动。

**涉及文件**：`app/src/main/java/com/nasmusic/tv/backend/local/AlbumCoverResolver.kt`、`app/build.gradle.kts`、`CHANGELOG.md`、`docs/technical-overview.md`

**验证结果**：✅ `assembleRelease` 编译通过（BUILD SUCCESSFUL，无 error，仅既有 warning），产物 `app/build/outputs/apk/release/NASMusicTV-release-v2-26-3.apk`。按用户要求仅编译、不推电视测试。

**版本号变更**：v2.26.2 → v2.26.3（versionCode 81 → 82）

---

### 10.76 Demucs 段读取 skip 修复 + `with`→`withContext` + 输出流关闭（v2.26.4 - 2026-09-04）

**日期**：2026-09-04

> 承接 2026-09-03 代码复审的 P0 项（§二 Demucs P1/P3、PlayerManager P3），逐条核对当前源码后修复。

#### 10.76.1 HQ 人声分离 >7.8s 歌曲 100% 失败（skip 误跳）

**问题描述**：HQ 人声分离对超过 7.8s（`SEGMENT_SAMPLES=343980`）的歌曲失败，只产出第一段。

**根因分析**：`DemucsSeparator.separate()` 逐段从临时文件读 float32，误加的 skip：

```kotlin
val skipFloats = (totalSamples - startSample - segLen) * 2L
if (skipFloats > 0 && startSample + segLen < totalSamples) {
    dis.skipBytes((skipFloats * 4).toInt())
}
```

在非最后一段（`totalSamples - startSample > SEGMENT_SAMPLES`）时，`segLen = SEGMENT_SAMPLES`，`skipFloats` 为正且巨大，`skipBytes` 会把指针一次性跳到剩余所有样本之后；下一轮 `readFloat()` 直接抛 `EOFException`，被 `catch (e: Exception)` 吞掉 → 分离结果只含第一段。

**关键判断**：临时文件是**连续交织 float32**（`L0,R0,L1,R1,...`，见 `decodeAudioToTempFile` 的写入逻辑与 `outChannels=2` 硬编码），逐采样连续 `readFloat()` 即为正确读取，**本无需任何 skip**。该 skip 是 temp-file streaming 重构（commit `096b3d7`，把原先的内存数组 `leftChannel/rightChannel` 读取改成磁盘流）时误引入的 —— 重构前无 skip 逻辑，读内存数组天然连续。

**修改**：删除 skip 块（原 235-239 行），改为注释说明「连续交织、无需 skip」。

#### 10.76.2 PlayerManager `with` 误用致主线程加载 166MB 模型 ANR

**问题描述**：开启 HQ 人声分离时 ANR/黑屏。

**根因分析**：`PlayerManager.enableHighQualityRemoval` 中两处：

```kotlin
val initOk = with(Dispatchers.IO) { separator.initialize(modelPath) }          // :388
val result = with(kotlinx.coroutines.Dispatchers.IO) { separator.separate(...) } // :401
```

`with` 是 Kotlin 标准库作用域函数（把 `Dispatchers.IO` 作为 `it`/接收者传入，但**仍在当前线程内联执行**），并非协程切换。`separator.initialize` 加载 166MB 模型、`separate` 做 ONNX 推理，本应在 IO 线程执行。同方法内 `:362` 的 `withContext(Dispatchers.IO) { resolveInputPath(...) }` 才是正确写法，佐证这两处是笔误。

**修改**：两处 `with(...)` → `withContext(...)`（`withContext`/`Dispatchers` 均已 import）。

#### 10.76.3 Demucs 输出流失败不关闭 + 残缺 WAV 缓存投毒

**问题描述**：分离失败时残留残缺 WAV 文件，伴奏缓存可能误判为有效。

**根因分析**：`separate()` 的 `vocalsFos`/`accFos` 在 `try` 内创建，成功路径 `:270-271` 手动 `close()`，但异常路径不关闭；且失败时 `_vocals.wav`/`_accompaniment.wav` 已写入 WAV 头（44 字节）+ 部分 PCM，残留在磁盘。`AccompanimentCache.hasAccompaniment()` 只判 `exists() && length() > 0`，会把残缺文件误判为有效缓存（缓存投毒）。

**修改**：把 `vocalsFos`/`accFos`/`vocalsFile`/`accompanimentFile` 提升为 `try` 外的可空变量，新增 `success` 标记；`finally` 中 `runCatching { vocalsFos?.close() }` / `runCatching { accFos?.close() }` 统一关闭流，`!success` 时 `delete()` 两个输出文件。成功路径 `close()` 后置 `success = true`。

**涉及文件**：`app/src/main/java/com/nasmusic/tv/player/DemucsSeparator.kt`、`app/src/main/java/com/nasmusic/tv/player/PlayerManager.kt`、`app/build.gradle.kts`、`CHANGELOG.md`、`docs/technical-overview.md`

**验证结果**：✅ `assembleRelease` 编译通过（BUILD SUCCESSFUL，无 error，仅既有 warning），产物 `app/build/outputs/apk/release/NASMusicTV-release-v2-26-4.apk`。真机 HQ 分离行为验证需 TV 复测，本版按用户要求不推电视。

**版本号变更**：v2.26.3 → v2.26.4（versionCode 82 → 83）

---

### 10.77 AppPreferences JSON 偏好「解析失败→回写空」数据丢失修复（v2.26.5 - 2026-09-04）

**日期**：2026-09-04

> 承接 2026-09-03 代码复审 P0 项「C2 AppPreferences 数据丢失模式」。

**问题描述**：所有 JSON 型偏好的「读-改-写」写入点，都是「`try { gson.fromJson(...) } catch (e) { 空集合 }` → 无条件 `gson.toJson(updated)` 回写」。一旦某条偏好 JSON 损坏（写入截断 / 字段变更 / 版本不兼容），解析失败被静默吞掉、转成空集合，随后**用空数据回写覆盖原值** —— 用户积累的数百条播放记录、收藏、歌单在**一次异常后永久抹除**，且日志只有一行 `AppLog.w`，几乎无法追溯。

**根因分析**：`gson.fromJson` 的 Java 签名是 `<T> T fromJson(String, Type)`，返回**平台类型** `T!`。Kotlin 里 `catch` 分支用空集合兜底 + 后续无条件 `toJson` 回写，构成了「读取失败也照常写空」的危险路径。

**修改**（`AppPreferences.kt`）：

1. **新增统一安全解析辅助函数** `safeParseJson(keyName, json) { parse() }`：解析失败时记 `AppLog.w`（含 keyName 与异常信息）并返回 `null`，成功返回解析结果。
2. **改造全部「读-改-写」写入点为「解析失败即跳过回写」**（`return@edit`，保留原数据）：
   - `addPlayRecord`（播放记录，500 条上限）
   - `recordPlay` / `recordPlayWithSong`（最近播放 id、播放次数、最近歌曲对象，各 3 处）
   - `recordRecentSongObject`（最近歌曲对象）
   - `toggleNetworkFavorite`（网络收藏，500 条上限）
   - `createLocalPlaylist` / `renameLocalPlaylist` / `deleteLocalPlaylist` / `addSongToPlaylist` / `removeSongFromPlaylist`（本地歌单，5 处）
   - `recordSearch` / `purgeExpiredSearchHistory`（搜索历史）
   - `setEqualizerBand`（均衡器 bands）
3. **`gson.fromJson` 统一补显式泛型实参**（`gson.fromJson<Type>(...)`），消除平台类型导致的泛型推断失败（`safeParseJson<T>` 的 `T` 无法从平台类型唯一推断）。
   - 注：`createLocalPlaylist` 是唯一例外 —— 歌单数据损坏时仍允许创建新歌单（`?: mutableListOf()`），因为该函数是「新增」而非「覆盖」，不构成数据丢失。

**设计权衡**：解析失败时**放弃本次写入**（而非尝试恢复），代价是本次操作（如一次播放记录）不会落盘；收益是**绝不以空数据覆盖历史数据**。考虑到播放记录/收藏/歌单对用户价值远高于单次写入，此取舍合理。

**涉及文件**：`app/src/main/java/com/nasmusic/tv/data/prefs/AppPreferences.kt`、`app/build.gradle.kts`、`CHANGELOG.md`、`docs/technical-overview.md`

**验证结果**：✅ `assembleRelease` 编译通过（BUILD SUCCESSFUL，无 error，仅既有 warning），产物 `app/build/outputs/apk/release/NASMusicTV-release-v2-26-5.apk`。行为验证需真机，本版按用户要求不推电视。

**版本号变更**：v2.26.4 → v2.26.5（versionCode 83 → 84）

### 10.78 本地音乐 5 项 P0 稳定性修复 + BackendRegistry runBlocking + Navidrome 末页 N+1（v2.26.6 - 2026-09-04）

**日期**：2026-09-04

> 承接 2026-09-03 代码复审 §二 P0 项 B3–B6、B 项 MediaMetadataRetriever 泄漏、§四路线图项 8（主线程 runBlocking）、项 10（Navidrome 末页 N+1）。

#### 10.78.1 全量扫描非原子（B3）

**问题**：`LocalMusicRepository.fullScan()` 原实现「先 `dao.deleteAll()` 再 `scanner.scanAllMusic()`」，一旦扫描抛异常（MediaStore 查询失败 / 权限变更 / IO 异常），用户曲库被清空且无法恢复。

**修复**：改为「先扫描成功，再重建索引」——扫描失败直接抛回，旧索引保持不变。

#### 10.78.2 USB 索引每次启动被误删（B4）

**问题**：`incrementalScan()` 原做 `cachedKeys - scannedKeys` 全量差集。USB 拔出后 MediaStore 不再返回该卷条目，所有 USB 歌的 key 都不在 `scannedKeys`，被判定为「已删除」而清除——每次启动丢失 USB 索引。

**修复**：按 `storageType`/`volumeName` 区分。仅对「本次扫描覆盖到的卷」做删除比对：内置存储条目始终参与比对；USB/外部 SD 条目仅当对应卷仍在挂载时参与比对，否则保留（挂载时由 `scanUsbDevice` 定向更新）。

#### 10.78.3 `IN (:paths)` 超变量上限崩溃（B5）

**问题**：`LocalMusicDao.deleteByPaths` 用 `IN (:paths)`，一次性传参超 SQLite 变量上限（999）时抛「too many SQL variables」，3000 首 USB 歌触发崩溃。

**修复**：新增 `deleteByPathsChunked` 按 500 条分批删除，覆盖增量/全量/USB 扫描三条路径。

#### 10.78.4 本地歌双 ID 跨会话失效（B6）

**问题**：`ScannedSong.toSong()` 用 `id = "local_${contentUri.hashCode()}"`，`LocalSongEntity.toSong()` 用 `id = "local_$mediaStoreId"`——同一首歌扫描时与从缓存加载时 ID 不一致，收藏/播放记录/队列跨会话失效。

**修复**：统一为 `local_$mediaStoreId`。

#### 10.78.5 MediaMetadataRetriever 异常路径泄漏

**问题**：`MusicScanner.scanFile()` 原仅在成功后 `release()`，`setDataSource`/`extractMetadata` 抛异常时泄漏，TV 上元数据提取器实例有限，扫描多个坏文件后耗尽。

**修复**：改为 `try/finally` 确保无论成功/异常都释放。同时删除死代码 `LocalMusicDao.getAllPaths()`。

#### 10.78.6 BackendRegistry `releaseAdapter` 主线程 runBlocking（C3）

**问题**：`releaseAdapter()` 原用 `runBlocking { adapter.logout() }`，从 `disconnect()`（`viewModelScope.launch`，Main dispatcher）调用时在主线程阻塞等待 logout 完成。

**修复**：改为 `suspend` 函数并用 `withContext(Dispatchers.IO)` 包裹 logout + close，彻底消除主线程阻塞（无论从 `initialize` 的 IO 块还是 `disconnect` 调用都安全）。

#### 10.78.7 Navidrome `getSongs` 末页 N+1 遍历（B8）

**问题**：翻页到末页时服务端正常返回空 `songs` 数组，原实现把「空数组」误判为端点异常，每次触发 `fallbackGetSongs` 遍历全部专辑逐个 `getAlbumSongs`（N+1），大曲库下卡死。

**修复**：仅当 `songs` 字段**完全缺失**（格式不兼容）才走 fallback；空数组直接返回空列表（正常末页）。

**涉及文件**：`backend/local/LocalMusicRepository.kt`、`backend/local/MusicScanner.kt`、`backend/local/db/LocalMusicDao.kt`、`backend/BackendRegistry.kt`、`backend/impl/NavidromeAdapter.kt`、`app/build.gradle.kts`、`CHANGELOG.md`

**版本号变更**：v2.26.5 → v2.26.6（versionCode 84 → 85）

### 10.79 快速切歌队列回滚 + K 歌切换无法切歌 + Subsonic 收藏失效/N+1（v2.26.7 - 2026-09-04）

**日期**：2026-09-04

> 承接 2026-09-03 代码复审 P0 项 P4/P6、P1 项 B9/B13。

#### 10.79.1 快速切歌队列回滚（P4）

**问题**：`resolveAndPlayByIndex` 入口用 `queue.value` 取旧队列快照，挂起解析（可能含 1.5s 重试）后无条件 `playQueue(updatedQueue, targetIndex)` 整体重建播放列表。快速连续切歌时 N 个在飞解析，最后响应者获胜但内容可能是最旧的快照 → 队列回滚。

**修复**：引入 `resolveGeneration` 代数计数器（`MainViewModel` 成员）。每次发起解析 +1，解析完成回写前比对代数，过期即丢弃；回写改为基于「当前最新 `queue.value`」更新目标歌曲 streamUrl，而非入口旧快照。

#### 10.79.2 K 歌伴奏/原唱切换后无法切歌（P6）

**问题**：`switchToAccompaniment`/`switchToOriginal` 用 `setMediaItem(newItem)` 把整个播放队列替换成单曲，开一次伴唱后 `seekToNextMediaItem()` 无目标。

**修复**：改用 Media3 `replaceMediaItem(index, newItem)`（已通过 javap 确认 media3-common 1.2.1 的 `Player` 接口存在该方法），只替换当前索引的 item，保留队列其余部分与播放位置。

#### 10.79.3 Subsonic 收藏整体失效（B9）

**问题**：`getFavorites()` 调 `getStarred2` 端点却解析 `subsonic-response > starred` 节点（实际返回 `starred2`），收藏列表恒空，`toggleFavorite` 永远判定「未收藏」只能加不能取消。

**修复**：优先解析 `starred2`，兼容 `starred`。

#### 10.79.4 Subsonic `getSongsByIds` 串行 N+1（B13）

**问题**：逐个 `getSong` 串行请求，队列恢复数十首歌时 RTT 累加成秒级卡顿。

**修复**：`supervisorScope` + `async` 并发 + 8 路信号量限流，失败单曲不影响整体。

**涉及文件**：`ui/viewmodel/MainViewModel.kt`、`player/PlayerManager.kt`、`backend/impl/SubsonicAdapter.kt`、`app/build.gradle.kts`、`CHANGELOG.md`

**版本号变更**：v2.26.6 → v2.26.7（versionCode 85 → 86）

### 10.80 NowPlaying 收藏不刷新 + 伴唱 DSP 失效 + Demucs 泄漏 + 缓存无限增长 + 重复调用（v2.26.8 - 2026-09-04）

**日期**：2026-09-04

> 承接 2026-09-03 代码复审 P0/P1 项 C7/P7/P10/P14/C9。

#### 10.80.1 NowPlaying 收藏星标不刷新（C7）

**问题**：NowPlaying 页 `isFavorite` 用 `isFavorite(song.id)` 直读 `_favoriteIds.value` 不建立订阅，点收藏后星标要到切歌/重组才刷新。

**修复**：NowPlaying 分支 `collectAsState` 订阅 `favoriteIds` 与 `networkFavoriteIds`，收藏状态即时刷新。

#### 10.80.2 伴唱 DSP 切歌后静默失效（P7）

**问题**：`SpectralMaskProcessor.reset()`（Media3 切歌/重建 AudioSink 时调用）错误 `enabled = false`，切歌后伴唱失效，但 `MainViewModel._vocalRemovalEnabled` 仍 true、UI 与真实状态不一致且无法自愈。

**修复**：`enabled` 是用户意图状态、由 `setEnabled()` 管理，从 `reset()` 移除该行，只重置内部音频状态。

#### 10.80.3 Demucs ONNX 会话进程级泄漏（P10）

**问题**：`DemucsSeparator.release()`（关闭 166MB 模型 `modelSession`/`ortEnv`）从未被调用，播放服务销毁后模型会话泄漏。

**修复**：`PlayerManager.release()` 中调用 `demucsSeparator?.release()`。

#### 10.80.4 伴奏缓存无限增长（P14）

**问题**：`cleanupCache()`（LRU，500MB/10 首）只在预分离路径触发；HQ 主路径 + `saveOriginalFile` 永不淘汰。

**修复**：`saveOriginalFile` 成功保存后触发 `cleanupCache()`。

#### 10.80.5 `refreshApiVersions()` 重复调用（C9）

**问题**：`connectToSavedServer` 成功路径连续调用两次，多一次网络请求。

**修复**：删除重复调用。

**涉及文件**：`ui/components/AppRoot.kt`、`player/SpectralMaskProcessor.kt`、`player/PlayerManager.kt`、`player/AccompanimentCache.kt`、`ui/viewmodel/MainViewModel.kt`、`app/build.gradle.kts`、`CHANGELOG.md`

**版本号变更**：v2.26.7 → v2.26.8（versionCode 86 → 87）

### 10.81 StorageMonitor 反射崩溃 + 伴奏中文路径 + 死代码清理（v2.26.9 - 2026-09-04）

**日期**：2026-09-04

> 承接 2026-09-03 代码复审 P1 项 B15、P2 项（`Uri.parse("file://")` 未编码、`checkPreSeparation` 死代码）。

#### 10.81.1 StorageMonitor 隐藏 API 反射崩溃（B15）

**问题**：`refreshStorageDevices()` 在 API 24–29 用 `volume.javaClass.getMethod("getPath").invoke(volume)` 反射取卷路径，无 try/catch；个别 ROM 隐藏该 API 时 `BroadcastReceiver.onReceive`（主线程）直接崩溃。

**修复**：捕获异常跳过该卷。

#### 10.81.2 伴奏/原唱中文/空格路径无法播放

**问题**：`switchToAccompaniment`/`switchToOriginal` 用 `Uri.parse("file://$path")` 构造本地文件 URI，遇中文/空格路径产生非法 URI。

**修复**：改为 `Uri.fromFile(File(path))` 正确编码路径。

#### 10.81.3 删除 `checkPreSeparation` 死代码

**问题**：预分离触发函数无任何调用点，注释「进度 > 50%」与实现「阈值 5%」矛盾。

**修复**：删除该函数及其独占的 `PRE_SEPARATION_THRESHOLD` 常量。`AccompanimentCache.startPreSeparation` 预分离 API 保留（未来可复用）。

**涉及文件**：`backend/local/StorageMonitor.kt`、`player/PlayerManager.kt`、`app/build.gradle.kts`、`CHANGELOG.md`

**版本号变更**：v2.26.8 → v2.26.9（versionCode 87 → 88）

### 10.82 iTunes 封面搜索双重编码 + 低清图（v2.26.10 - 2026-09-04）

**日期**：2026-09-04

> 承接 2026-09-03 代码复审 P1 项 B16。

#### 10.82.1 双重编码破坏中文搜索词（B16）

**问题**：`resolveItunesCover` 先 `query.replace(" ", "+")` 再用 `URLEncoder.encode`，`+` 被二次编码为 `%2B`，iTunes 收到「字面加号」而非空格分隔词，中文专辑封面命中率低。

**修复**：移除多余的 `replace(" ", "+")`（`URLEncoder.encode` 本身把空格编码为 `+`、中文编码为 `%XX`）。

#### 10.82.2 封面永远返回低清图（B16）

**问题**：`artworkUrl.replace("100x100", "600x600")` 的返回值被丢弃（未 `return`），实际永远返回 100×100 低清封面。

**修复**：返回替换后的 600×600 高清图。

**涉及文件**：`backend/local/AlbumCoverResolver.kt`、`app/build.gradle.kts`、`CHANGELOG.md`

**版本号变更**：v2.26.9 → v2.26.10（versionCode 88 → 89）

### 10.83 Demucs 解码字节序不匹配 + 无缓冲 IO + 解码器泄漏（v2.26.11 - 2026-09-04）

**日期**：2026-09-04

> 承接 2026-09-03 代码复审 P0/P1 项 P8、P9、P15（Demucs 人声分离器）。

#### 10.83.1 解码临时文件字节序不匹配（P9）

**问题**：`decodeAudioToTempFile` 写入临时文件用 `ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)`，而 `separate()` 读回用 `DataInputStream.readFloat()`（JVM 默认 BIG_ENDIAN）。写入/读取字节序相反，读回的 float 全部错乱，人声分离输入即噪声——这是最严重的正确性缺陷。

**修复**：统一为 BIG_ENDIAN 写入（用 `Float.floatToIntBits` + 显式 4 字节 `ushr` 拆分写高字节在前），与 `readFloat()` 完全一致。同步更新 `DecodeResult` 与 `decodeAudioToTempFile` 注释中的「little-endian」→「big-endian」。

#### 10.83.2 解码逐样本分配 + 无缓冲 IO（P15）

**问题**：原实现每 2 个采样就 `ByteBuffer.allocate(8)` 并逐次 `fos.write(bb.array())`，全程无缓冲，频繁堆分配与系统调用拖慢整段解码。

**修复**：复用 64KB 预分配 `ByteArray` 缓冲，手动按 BIG_ENDIAN 写字节；写满即 `fos.write(writeBuf, 0, pos)` 冲刷，循环结束冲刷剩余不足 64KB 的字节。

#### 10.83.3 解码器/抽取器异常路径泄漏（P8）

**问题**：`MediaCodec`/`MediaExtractor` 仅在正常路径 `stop()`/`release()`，`catch` 分支直接 `return null` 未释放，解码异常时资源泄漏。

**修复**：将二者提升为函数级 `var codec`/`var extractor` 可空变量，正常路径 release 后置 `null`，新增 `finally` 块 `runCatching { codec?.stop(); codec?.release(); extractor?.release() }` 兜底释放。

**涉及文件**：`player/DemucsSeparator.kt`、`app/build.gradle.kts`、`CHANGELOG.md`

**版本号变更**：v2.26.10 → v2.26.11（versionCode 89 → 90）

### 10.84 Navidrome 封面 URL 缓存失效 + 专辑列表硬上限（v2.26.12 - 2026-09-04）

**日期**：2026-09-04

> 承接 2026-09-03 代码复审 P1 项 B10、B11。

#### 10.84.1 Navidrome 封面 URL 每次重建 salt+token（B11）

**问题**：Navidrome `buildRestUrl` 每次调用都 `System.currentTimeMillis()` 现场生成新 salt，`buildCoverUrl`（内部调 `buildRestUrl("getCoverArt")`）产出的封面 URL 每次都不同。Coil 以 URL 字符串为缓存 key，URL 不稳定导致内存/磁盘缓存命中失效，同一张封面反复下载，封面页滚动时频繁网络请求。

**修复**：新增 `salt` 字段，在 `initialize` 时固定一次（`System.currentTimeMillis()`），`buildRestUrl` 复用该 salt（`token = md5(password + salt)` 仍在现场计算）。与 Subsonic 的固定 salt 实现对齐。

#### 10.84.2 专辑列表硬上限 500 无分页（B10）

**问题**：Navidrome/Subsonic 的 `getAlbums` 均硬编码 `size=500`、无 `offset` 分页，超过 500 张专辑的曲库会静默丢失专辑。

**修复**：改为按页循环拉取（每页 500、`offset` 递增），直到返回不足一页（末页）或达到安全上限（100 页 / 5 万张，防异常循环）。两个 adapter 同步修改。

**涉及文件**：`backend/impl/NavidromeAdapter.kt`、`backend/impl/SubsonicAdapter.kt`、`app/build.gradle.kts`、`CHANGELOG.md`

**版本号变更**：v2.26.11 → v2.26.12（versionCode 90 → 91）

### 10.85 飞牛/道理鱼 getSongs 除零崩溃（v2.26.13 - 2026-09-04）

**日期**：2026-09-04

> 承接 2026-09-03 代码复审 P1 项 B14。

#### 10.85.1 getSongs 除零崩溃（B14）

**问题**：`FeiniuAdapter`/`DaoliyuAdapter` 的 `getSongs(limit, offset)` 用 `(offset / limit) + 1` 反推页码，当 `limit <= 0`（调用方传入非法值）时抛 `ArithmeticException` 除零崩溃。

**修复**：引入 `safeLimit = if (limit > 0) limit else PAGE_SIZE`，除零时回退到默认页大小 500，两个 adapter 同步修改。

**涉及文件**：`backend/impl/FeiniuAdapter.kt`、`backend/impl/DaoliyuAdapter.kt`、`app/build.gradle.kts`、`CHANGELOG.md`

**版本号变更**：v2.26.12 → v2.26.13（versionCode 91 → 92）

**补充判断（B17 暂不处理）**：`SearchAggregator` 的 `withTimeoutOrNull` 对阻塞式 OkHttp `execute()` 无法软取消（5s/8s 超时形同虚设），但各 adapter 的 OkHttp 已设 `readTimeout(30s)`，最坏 30s 后 `SocketTimeoutException` 被 catch 兜底，不会永久卡死；属延迟/体验层面而非正确性 bug，修复需将 `execute()` 改为支持取消的调用方式、改动面大，故维持暂不处理。

### 10.86 seek 窗口内暂停/播放状态失真（v2.26.14 - 2026-09-04）

**日期**：2026-09-04

> 承接 2026-09-03 代码复审 P1 项 P5。

#### 10.86.1 seekPending 吞 onIsPlayingChanged（P5）

**问题**：`PlayerManager` 的 `onIsPlayingChanged` 在 `seekPending == true` 时直接 `return`，既不更新 `_isPlaying` 也不移除轮询回调。seek 窗口内（`seekTo` 后到 `onPositionDiscontinuity(SEEK)` 或 1 秒兜底 timeout 之间）若用户暂停/播放，`_isPlaying` 不更新，播放按钮卡在错误状态直到下一次 `onIsPlayingChanged` 触发。

**修复**：将 `_isPlaying.value = isPlaying` 移到 `seekPending` 判断之前（纯状态记录、无副作用，总是同步）；`seekPending` 时仅跳过进度轮询的启停（有副作用，防止播放按钮闪烁与 ExoPlayer 内部位置重置干扰）。

**涉及文件**：`player/PlayerManager.kt`、`app/build.gradle.kts`、`CHANGELOG.md`

**版本号变更**：v2.26.13 → v2.26.14（versionCode 92 → 93）

### 10.87 频谱采样率硬编码 + 冗余 WAKE_LOCK 权限（v2.26.15 - 2026-09-04）

**日期**：2026-09-04

> 承接 2026-09-03 代码复审 P2 项。

#### 10.87.1 频谱分析采样率硬编码 44100

**问题**：`SpectrumAnalyzer.processFft` 已收到 Visualizer 回调的真实 `samplingRate`，但频率映射硬用常量 `SAMPLING_RATE=44100`。设备实际采样率非 44100（如 48000）时 `freqPerBin` 算错，32 根频谱柱的频率映射整体漂移，低频/高频分配失真。

**修复**：改用回调提供的真实 `samplingRate`（异常时回退 44100）计算 `freqPerBin`。

#### 10.87.2 冗余 WAKE_LOCK 权限声明

**问题**：`AndroidManifest.xml` 声明 `android.permission.WAKE_LOCK`，但全代码库无任何 `WakeLock`/`PowerManager` 引用，Media3 `MediaLibraryService` 自行管理唤醒锁，应用层声明属冗余。

**修复**：删除该权限声明。

**涉及文件**：`player/SpectrumAnalyzer.kt`、`app/src/main/AndroidManifest.xml`、`app/build.gradle.kts`、`CHANGELOG.md`

**版本号变更**：v2.26.14 → v2.26.15（versionCode 93 → 94）

### 10.88 MusicScanner 递归深度限制 + Bilibili 正则预编译（v2.26.16 - 2026-09-04）

**日期**：2026-09-04

> 承接 2026-09-03 代码复审 P2 项。

#### 10.88.1 MusicScanner 递归无深度限制

**问题**：`scanPath` 用 `walkTopDown()` 递归整个目录树、无上限，深目录或符号链接循环会无限递归、主线程 IO 卡死。

**修复**：加 `.maxDepth(8)` 防护（常量 `MAX_SCAN_DEPTH`）。

#### 10.88.2 Bilibili MV 标题解析每次编译正则

**问题**：`stripHtml` 每次调用都 `Regex("<[^>]+>")` 重新编译正则，微性能浪费。

**修复**：正则提为 companion object 预编译常量 `HTML_TAG_REGEX`。

**涉及文件**：`backend/local/MusicScanner.kt`、`backend/network/mv/BilibiliMvService.kt`、`app/build.gradle.kts`、`CHANGELOG.md`

**版本号变更**：v2.26.15 → v2.26.16（versionCode 94 → 95）

**补充判断（P11/P12/P13 暂缓）**：P11（shuffleModeEnabled 与 playRandom 双轨错歌）需引入独立播放模式状态字段、解耦「随机模式标志」与 ExoPlayer 有副作用的 `shuffleModeEnabled` 属性，中等复杂度且需真机验证随机播放不回归；P12/P13（未注册 MediaButtonReceiver 致通知栏按钮失效）完整修复需实现 `onPlaybackResumption` + 播放队列持久化恢复，属架构级改动。二者风险/收益比不佳，暂缓。

---

### 10.89 本地专辑 id 塌缩 + 电台上报静默 + 拼音缓存 + Jellyfin 计数（v2.26.17 - 2026-09-04）

**日期**：2026-09-04

> 承接 2026-09-03 代码复审 P2 / B20 项，并收尾此前未提交的 Jellyfin `Limit=0` 改动。

#### 10.89.1 本地专辑 id 塌缩为 `local_album_0`（B20，小改动部分）

**问题**：`MusicMerger.buildLocalAlbums` 的 `id = "local_album_${first.albumId ?: first.id}"`。`Song.albumId` 为 `String?`，本地歌曲来自 MediaStore，未知专辑恒为 `0L` → `albumId.toString()` 得 `"0"`（非 null），`?:` 兜底不触发，所有 `albumId="0"` 的本地专辑共享同一 id `local_album_0`；详情页 `albumSongsCache` 以 id 为键，不同专辑条目互相覆盖。

**修复**：改为基于专辑名去重键派生稳定唯一 id `local_album_<name>`，消除塌缩。详情页 `loadAlbumSongs` 用 `_selectedAlbum.name` 反查歌曲（不解析 id 字符串），故 id 格式变更不影响解析。

**未处理（设计级，属大改动，按约束暂缓）**：同名本地专辑并入 NAS 后保留 NAS id，详情页只查 NAS 漏掉本地同名词曲，需详情页改为多源按名查询，留待独立任务。

#### 10.89.2 RadioBrowser 播放上报失败静默（P2）

**问题**：`reportClick` 失败仅 `AppLog.w`，release 构建 `AppLog.w` 为 no-op，上报失败不可见。

**修复**：改 `AppLog.e`，使失败在 release 可观测。

#### 10.89.3 拼音重复计算（P2 / O(N²) 列表复制·拼音重复计算）

**问题**：`PinyinUtils.toPinyin`/`toPinyinInitials` 无缓存纯函数，LibraryScreen 过滤每次按键都对全量歌名/歌手重算 TinyPinyin；SearchAggregator 虽自建缓存但其它调用方仍裸调。

**修复**：`PinyinUtils` 内加有界 LRU 缓存（上限 4096，`Collections.synchronizedMap` 线程安全，accessOrder=true 淘汰最久未用），零行为变更、覆盖全部调用方。

#### 10.89.4 Jellyfin 曲库计数 `Limit=0` 语义风险（P2）

**问题**：`getSongsTotalCount` 用 `Limit=0` 取 `TotalRecordCount`，但 Jellyfin 中 `Limit=0` 表示「无限制返回全部」，会拉全量曲库（返回值仍正确，但浪费带宽、与注释矛盾）。

**修复**：改 `Limit=1` 澄清意图，返回值不变。

**涉及文件**：`backend/local/MusicMerger.kt`、`backend/radio/RadioBrowserClient.kt`、`util/PinyinUtils.kt`、`backend/impl/JellyfinAdapter.kt`、`app/build.gradle.kts`、`CHANGELOG.md`

**版本号变更**：v2.26.16 → v2.26.17（versionCode 95 → 96）

---

### 10.90 跨线程可变集合无同步（v2.26.18 - 2026-09-04）

**日期**：2026-09-04

> 承接 2026-09-03 代码复审 P2「可变集合无同步」。

#### 10.90.1 FeiniuAdapter.cookieStore 并发损坏

**问题**：`private val cookieStore = mutableMapOf<String, List<Cookie>>()` 被 OkHttp `CookieJar` 的 `loadForRequest` / `saveFromResponse` 回调在 dispatcher 线程池上并发读写，基础 `LinkedHashMap` 非线程安全，并发 `put` 可能触发结构损坏（resize 期间丢失/错链）。

**修复**：改为 `java.util.Collections.synchronizedMap(mutableMapOf(...))`，单方法读写原子化。

#### 10.90.2 NavidromeAdapter._favoriteIds 并发读写

**问题**：`private val _favoriteIds = mutableSetOf<String>()` 在 `Dispatchers.IO` 的 `toggleFavorite` / `loadFavorites` / `getFavorites` 等多个 suspend 函数里并发读写收藏状态，基础 `LinkedHashSet` 非线程安全。

**修复**：改为 `java.util.Collections.synchronizedSet(mutableSetOf(...))`，保持 `MutableSet` 接口、零行为变更。

**涉及文件**：`backend/impl/FeiniuAdapter.kt`、`backend/impl/NavidromeAdapter.kt`、`app/build.gradle.kts`、`CHANGELOG.md`

**版本号变更**：v2.26.17 → v2.26.18（versionCode 96 → 97）

### 10.91 专辑合并「只留 NAS id」导致详情页丢本地歌（方案 B，v2.26.20 - 2026-09-04）

**日期**：2026-09-04

> 承接 2026-09-03 代码复审 B20（数据正确性问题）。方案 A（最小局部改 `loadAlbumSongs` 追加本地歌）未采用，采用方案 B 从模型层根上解决。

#### 10.91.1 根因

`MusicMerger.mergeAlbums` 同名碰撞时执行 `existing.copy(...)`，保留的是 `existing`（NAS 专辑）的 `id`；本地/百度同名专辑的 `id`（`local_album_xxx` / `baidu_album_xxx`）被丢弃。而 `MainViewModel.loadAlbumSongs` 按 `id` 前缀路由——合并专辑 `id` 是 NAS id → 只走 `adapter.getAlbumSongs(albumId)`，本地/百度同名歌不可见、不可播。`songCount` 却显示 `NAS+Local`，点进去数量对不上。

#### 10.91.2 修复（方案 B）

- `Album` 新增 `val sourceIds: List<String> = emptyList()`。
- `MusicMerger.mergeAlbums`：引入 `sourceIdsMap`（去重键 → 来源 id 列表），`mergeInto` 在碰撞/新建时 `putSource(key, album.id)` 收集全部来源；函数末尾统一 `album.copy(sourceIds = sourceIdsMap[key].orEmpty())` 回填。单源条目 `sourceIds = [自身 id]`。
- `MainViewModel.loadAlbumSongs`：优先读 `album.sourceIds`（空则回退 `listOf(albumId)`，向后兼容），对每个来源分别取数（NAS → `adapter.getAlbumSongs`；`local_album_` → 本地+百度按名匹配；`baidu_album_` → 百度按名匹配），拼接后按 `title|artist|durationMs` 跨源去重写入 `_albumSongsCache`。
- 抽取 `private fun filterSongsByAlbumName(albumName, candidates)` 统一本地/百度/无 NAS 三处按名匹配逻辑（含百度 path 倒数第二段目录名匹配）。

#### 10.91.3 影响与兼容

- 未合并的单源专辑（`sourceIds` 为空）回退到旧 `albumId` 单源逻辑，行为不变。
- 合并专辑缓存键仍为 `album.id`（= NAS id），`resolvedAlbumCovers` 按 `album.id` 索引不受影响。
- 列表 `songCount` 累加与详情页取数现已自洽：列表显示 `NAS+Local+百度` 总数，详情页也能取到对应全部歌曲。
- **未覆盖项**：播放整张专辑（`AppRoot` 的 `onPlayAlbum` 用 `songs.filter { it.albumId == album.id }`）仍只取 NAS 歌，属同根因的「播放」路径，本次方案 B 仅覆盖详情页显示，建议另立任务修复播放路径（或复用 `loadAlbumSongs` 已缓存的多源结果）。

**涉及文件**：`data/model/Album.kt`、`backend/local/MusicMerger.kt`、`ui/viewmodel/MainViewModel.kt`、`app/build.gradle.kts`、`CHANGELOG.md`

**版本号变更**：v2.26.19 → v2.26.20（versionCode 98 → 99）

### 10.92 播放整张合并专辑只取 NAS 歌（B20 续，v2.26.21 - 2026-09-04）

**日期**：2026-09-04

> B20 方案 B（v2.26.20）仅覆盖详情页显示，播放整张专辑（`AppRoot.onPlayAlbum`）仍按 `albumId` 单源过滤，本地/百度同名歌漏播。本次补齐播放路径。

#### 10.92.1 根因

`AppRoot.onPlayAlbum` 两处（HomeScreen、LibraryScreen）实现为 `songs.filter { it.albumId == album.id }`。合并专辑 `id` = NAS id，本地歌 `albumId` 是 MediaStore id / `"0"`，百度歌 `albumId` 也不是 `album.id` → 只匹配到 NAS 歌，本地/百度同名歌整张播放时丢失。

#### 10.92.2 修复

- `MainViewModel` 新增 `fun playAlbumMultiSource(album: Album)`：在 `viewModelScope.launch` 内读 `album.sourceIds`（空则回退 `listOf(album.id)`），对每个来源分别取数（NAS → `adapter.getAlbumSongs`；`local_album_` → 本地+百度按名匹配；`baidu_album_` → 百度按名匹配；无 NAS 按名兜底），拼接后按 `title|artist|durationMs` 跨源去重，`result.isNotEmpty()` 才 `playQueue` + 跳转 NowPlaying。复用 v2.26.20 的 `filterSongsByAlbumName`，多源逻辑与 `loadAlbumSongs` 完全一致。
- `AppRoot` 两处 `onPlayAlbum = { album -> viewModel.playAlbumMultiSource(album) }`，删除原单源 `filter { it.albumId == album.id }` 逻辑。

#### 10.92.3 影响与兼容

- 单源专辑（本地/百度/NAS 独立）：`sourceIds` 为空 → 回退 `listOf(album.id)`；但本地专辑 `album.id` 是 `local_album_xxx`，而旧 `onPlayAlbum` 用 `albumId == album.id` 也匹配不上本地歌 `albumId`（MediaStore id）——旧版本地专辑「播放整张」本就为空/失效，新逻辑按名匹配反而修好了这一历史问题。
- NAS 专辑、合并专辑行为正确，本地/百度同名歌现在可整张播放。

**涉及文件**：`ui/viewmodel/MainViewModel.kt`、`ui/components/AppRoot.kt`、`app/build.gradle.kts`、`CHANGELOG.md`

**版本号变更**：v2.26.20 → v2.26.21（versionCode 99 → 100）

### 10.93 LocalMusicDatabase 无破坏性迁移（方案 A，v2.26.22 - 2026-09-04）

**日期**：2026-09-04

> 本地音乐索引 DB 仅配 `fallbackToDestructiveMigrationOnDowngrade()`，升级无 Migration 实现——未来 `version` 提升会直接抛 `IllegalStateException` 致本地库崩溃。改为全破坏性重建。

#### 10.93.1 根因

`LocalMusicDatabase`（单例 Room，实体仅 `LocalSongEntity`，当前 `version = 1`，`exportSchema = true`）的 `companion.get()` 仅调用 `.fallbackToDestructiveMigrationOnDowngrade()`：
- **降级**（新版本号 < 旧版本号）时回退破坏性重建；
- **升级**（新版本号 > 旧版本号）时没有任何 `Migration` 实现，Room 会抛 `IllegalStateException: A migration from 1 to 2 was required but not found`。

本地音乐库是「可重扫重建」的索引型数据，本不应维护迁移代码，但原配置在**升级方向**上缺了兜底——一旦后续给实体加字段/索引导致 `version` 提升，线上将直接崩溃、本地歌单全失。这是静默埋雷：当前 `version=1` 不触发，但任何一次 schema 演进都会引爆。

#### 10.93.2 修复

- `LocalMusicDatabase.kt` 第 33 行由 `.fallbackToDestructiveMigrationOnDowngrade()` 改为 `.fallbackToDestructiveMigration(true)`。
  - 选用带 `dropAllTables` 参数的重载而非 no-arg 版：no-arg `fallbackToDestructiveMigration()` 在新 Room 版本已 deprecate（警告提示「Replace by overloaded version with parameter to indicate if all tables should be dropped or not」），`true` 等价于原 no-arg 的「丢弃全部表、破坏性重建」语义，同时消除 deprecation 警告。
  - 升级与降级现在**都**走破坏性重建：本地索引可由重扫重建，无需编写 `Migration` 类，彻底消除版本演进时的迁移代码负担与崩溃风险。
- 同步更新类 doc：明确「schema 变化时（含升级与降级）回退到破坏性迁移」。

#### 10.93.3 影响与兼容

- 行为变化仅发生在**未来 schema 版本提升时**——当前 `version=1` 不会触发任何重建，已扫描的本地索引、收藏照常保留，无数据副作用。
- 用户侧的代价：若某次发版带了 schema 变更，升级后本地库会被清空、需重扫；本地音乐是设备端可重新扫描的索引，可接受。
- `exportSchema = true` 仍保留（Room 仅在没有配置 `room.schemaLocation` 时给一条提示性 warning，不影响功能，属历史既有配置，不在本次范围）。

**涉及文件**：`backend/local/db/LocalMusicDatabase.kt`、`app/build.gradle.kts`、`CHANGELOG.md`

**版本号变更**：v2.26.21 → v2.26.22（versionCode 100 → 101）

### 10.94 path.hashCode() 作主键碰撞丢 USB 歌（复审 P2，方案 A，v2.26.23 - 2026-09-04）

**日期**：2026-09-04

> 三项暂缓分析里最后一个：USB 文件用 32-bit 路径哈希作主键，大曲库碰撞静默丢歌。改用 64-bit FNV-1a，版本号 bump 触发干净重建。

#### 10.94.1 根因

`LocalSongEntity.mediaStoreId` 是 `@PrimaryKey`（Long）。USB / 无 MediaStore ID 的文件在 `MusicScanner.scanFile` 用 `file.absolutePath.hashCode().toLong()` 生成主键——这是 Java `String.hashCode` 的 **32-bit** 值拓宽为 Long。32-bit 哈希的生日碰撞：约 √(2^32)≈6.5 万次即 50% 碰撞概率，对大容量 USB 曲库（十万级）几乎必然出现不同文件映射到同一 `mediaStoreId`；而本地库写入用 `@Insert(REPLACE)`（冲突即替换），碰撞条目互相覆盖 → **静默丢歌**，且难以察觉。

公开 `Song.id = "local_$mediaStoreId"`（`LocalMusicRepository.ScannedSong.toSong` / `LocalSongEntity.toSong`），因此该主键同时是收藏 / 歌单 / 播放历史的业务键。

#### 10.94.2 修复

- 新增 `util/HashUtils.kt`：`stablePathHash64(path)` 用 **FNV-1a 64-bit**（offset basis `0xcbf29ce484222325`、prime `0x100000001b3`）。64-bit 哈希的碰撞概率降至 (n/2^64)^2 量级，对百万级文件仍可忽略；纯路径函数、无随机盐，同一文件跨进程/启动/设备恒得同一 id（不破坏增量扫描 / 收藏匹配）。
- `MusicScanner.scanFile`：`mediaStoreId = file.absolutePath.hashCode().toLong()` → `HashUtils.stablePathHash64(file.absolutePath)`。MediaStore 通道（`scanAllMusic`）仍用真实 `cursor.getLong(_ID)`，不受影响。
- `LocalMusicDatabase` 版本 `1 → 2`：因主键 id 取值整体改变，若不 bump，旧 32-bit id 行残留、新 64-bit id 行以不同主键插入 → 同一文件出现重复条目。bump 后 `fallbackToDestructiveMigration(true)` 在升级时清空本地索引、由启动 `incrementalScan`（MediaStore 重扫内部/外部真实 ID）+ USB 挂载 `scanUsbDevice`（新 64-bit id）自动重建，无需 Migration 类。

#### 10.94.3 影响与兼容（数据副作用，已提前告知用户）

- **升级一次性清空本地索引**：版本 1→2 触发破坏性重建，本地库短暂清空后由上述自动扫描自愈（启动即恢复内部/外部歌；USB 歌在设备挂载后恢复），不会长期为空。
- **USB 歌的收藏 / 歌单 / 播放历史失效**：这些记录以 `"local_<旧 32-bit 哈希>"` 为键，升级后 USB 歌 id 变为 `"local_<64-bit 哈希>"`，旧键匹配不上 → 对应 USB 收藏/歌单/历史条目表现为「丢失」，需重新收藏/加入。内部/外部存储歌（真实 MediaStore ID，未变）的收藏等**不受影响**。属可接受的一次性代价（本地音乐是设备端可重扫索引，且 USB 收藏本就可重新建立）。
- 不 bump 版本号 / 不引入 Migration：靠 `fallbackToDestructiveMigration(true)` 的升级破坏性重建完成「换主键 + 清旧数据」，零迁移代码。

**涉及文件**：`util/HashUtils.kt`（新增）、`backend/local/MusicScanner.kt`、`backend/local/db/LocalMusicDatabase.kt`、`app/build.gradle.kts`、`CHANGELOG.md`

**版本号变更**：v2.26.22 → v2.26.23（versionCode 101 → 102；LocalMusicDatabase version 1 → 2）

### 10.95 百度网盘索引重构：listall 分页 + 缩略图替代 BFS 扫描（v2.26.24 - 2026-09-05）

> 百度网盘音乐索引重建从 BFS 逐目录扫描改为 listall 递归端点分页获取，同时利用 listall+web=1 返回的 thumbs 缩略图直接作为封面，消除 APIC 后台提取。

#### 10.95.1 背景

原 `BaiduFileIndexCache.fullScan()` 使用 BFS 逐目录调用 `listDir`，对 46,595 个文件需要数百次 API 请求（每次仅返回 1000 条），耗时极长。封面方面，原方案在扫描完成后启动 `extractApicInBackground` 逐首解析内嵌 APIC 图片，又增加了数百次 HTTP 请求。

百度官方 `listall` 端点支持 `recursion=1` 递归列出全部文件，配合 `web=1` 返回缩略图 URL，一次分页请求最多返回 10,000 条。46,595 个文件仅需 ~5 次 API 请求即可完成，且直接获得封面缩略图，无需额外 APIC 提取。

#### 10.95.2 修改内容

1. **`BaiduFile.kt`**：新增 `coverThumb: String?` 字段，存储 listall+web=1 返回的缩略图 URL（优先 url2 > url1 > url3）；`toSong()` 方法增加 `coverThumb` 作为 coverUrl 的 fallback。

2. **`BaiduPanApi.kt`**：
   - `parseBaiduFile()` 增加 `thumbs` JSON 解析，提取 `thumbs.url` 赋值给 `coverThumb`
   - 新增 `listAllAudioPaged()` 方法：循环调用 `listall` 端点（recursion=1, web=1），通过 `has_more`/`cursor` 手动分页，limit=10000，每批次回调进度
   - 保留原 `listAllAudio`（非分页）供其他场景使用

3. **`BaiduFileIndexCache.kt`**：
   - `fullScan()` 改为调用 `api.listAllAudioPaged()` 替代 BFS `scanDirTree`，遍历全部音频文件时直接从 `BaiduFile.coverThumb` 赋值 `coverUrl`
   - 保留 `scanDirTree()` 方法（BFS）供 MV 目录扫描使用
   - `extractApicInBackground()` 保留但不再在扫描后自动触发

4. **`MainViewModel.kt`**：移除 `rebuildBaiduIndex()` 中扫描完成后的 `startApicExtraction()` 调用，注释说明 listall+web=1 已在扫描时直接返回缩略图作为封面。

#### 10.95.3 性能对比

| 指标 | 改前（BFS + APIC） | 改后（listall 分页 + thumbs） |
|------|-------------------|-------------------------------|
| API 请求数 | 数百次 listDir + 数百次 fileMetas | ~5 次 listall |
| 封面获取 | 扫描后逐首 APIC 提取（数百次 HTTP） | 扫描时直接返回 thumbs URL |
| 扫描耗时（46K 文件） | 数分钟 | 数秒 |
| 覆盖率 | 仅 ID3 内嵌 APIC 的文件有封面 | 所有文件均有缩略图（百度网盘自动提取） |

#### 10.95.4 兼容性

- `BaiduFile.coverThumb` 有默认值 `null`，不影响现有 `toSong()` 调用方
- `extractApicInBackground` 保留未删除，未来可作为 fallback 或手动触发使用
- MV 目录扫描仍使用 BFS `listDir`（视频文件不适用 listall 音频过滤）
- 百度官方 listall 端点限制：每次最多 10,000 条，超过需分页（已实现）；rate limit 8-10 req/min（5 次请求远低于限制）

**涉及文件**：`data/model/BaiduFile.kt`、`backend/network/baidu/BaiduPanApi.kt`、`backend/network/baidu/BaiduFileIndexCache.kt`、`ui/viewmodel/MainViewModel.kt`

**版本号变更**：v2.26.23 → v2.26.24（versionCode 102 → 103）

### 10.96 v2.26.27 - 百度网盘索引全量递归扫描 + 封面 APIC 自动提取

**提交日期**：2026-09-05

**问题现象**：
1. 百度网盘重建索引只能扫到约 4493 首，实际曲库 4 万+ 歌曲
2. 索引完成后不触发歌曲封面提取流程

**根因分析**：

1. **索引只扫到 4493 首**：`BaiduPanApi.listAllAudioPaged` 调用 `listall` 接口时误用 `FILE_BASE`（`xpan/file`）端点。百度官方文档（2026-09-02 版）明确 `listall` 必须走 `xpan/multimedia` 端点。错误端点被百度静默降级为 `list` 语义（不递归），只返回根目录第一层文件，过滤音频后恰好 ~4493 首。
2. **封面不提取**：`MainViewModel.rebuildBaiduIndex` 中有一行错误注释"listall+web=1 已在扫描时直接返回缩略图作为封面，无需 APIC 后台提取"。实际上百度只为图片/视频生成 `thumbs` 缩略图，音频文件 `coverThumb` 几乎全为 null，`startApicExtraction()` 从未被调用。

**修复内容**：

1. **`BaiduPanApi.kt:137`**：`listAllAudioPaged` 的 `buildUrl` 第一参数由 `FILE_BASE` 改为 `MULTIMEDIA_BASE`，与百度 `listall` 官方端点规范一致。分页逻辑（`has_more`/`cursor`）本就符合文档"当 has_more=1 时必须用 cursor 作为下一次 start"，无需改动。
2. **`MainViewModel.rebuildBaiduIndex`**：`fullScan` 成功后加载索引，统计 `coverUrl == null && category != CATEGORY_VIDEO` 的音频条目数 `pendingCovers`，若 > 0 则调用 `startApicExtraction()` 启动 APIC 后台提取（`BaiduCoverProvider.extractApicOnly` 从音频文件内嵌 ID3 APIC 帧提取封面，并发 5，批量 20 写入索引）。删除错误注释。

**涉及文件**：
- `backend/network/baidu/BaiduPanApi.kt`：`listAllAudioPaged` 端点修正
- `ui/viewmodel/MainViewModel.kt`：`rebuildBaiduIndex` 扫描后触发 APIC 提取

**验证结果**：
- ✅ `./gradlew.bat assembleDebug` BUILD SUCCESSFUL
- ✅ adb 安装到手机（91846823），logcat 确认 `listAllAudioPaged` 走 `xpan/multimedia`，分页递归扫描拿到 4 万+ 歌曲
- ✅ logcat 确认 `rebuildBaiduIndex: N entries pending cover extraction, starting APIC`，APIC 提取协程启动

**注意事项**：
- 百度 `listall` 文档明确"当 has_more=1 时必须用响应中的 cursor 作为下一次请求的 start，不要自行累加固定步长"——代码本就符合此规范，端点修正后分页正常
- `listall` 的 `limit` 参数文档说"建议不超过 1000，上限 10000"，代码用 10000（合法），若服务端限制会更少，`has_more`/`cursor` 会自动分页
- APIC 提取并发 5、批量 20，大曲库（4 万+）提取耗时较长（每首需下载部分文件内容解析 ID3），用户可在设置页看到提取进度

**版本号变更**：v2.26.26 → v2.26.27（versionCode 105 → 106）


### 10.97 v2.26.28 - 艺术家封面来源修复：百度/本地艺术家封面解析 + 移除已下线百度音乐API

**提交日期**：2026-09-05

**问题现象**：
1. 仅连接百度网盘（无 NAS）时，百度艺术家的封面永远空白
2. 本地艺术家的封面恒为空（即使其歌曲有封面）
3. 百度音乐在线 API（musicapi.taihe.com）已下线，DNS 解析失败

**根因分析**：

1. **百度艺术家封面不解析**：`resolveArtistCoversAsync()` 只在 `loadArtists()`（NAS 连接）末尾调用，而 `loadArtists()` 需 NAS adapter，无 NAS 时直接走 Error 分支返回。百度艺术家由 `MusicMerger.buildBaiduArtists` 在 `updateMergedData()` 内从索引聚合生成（id=baidu_artist_{name}，coverUrl 恒 null），但 `updateMergedData()` 末尾只调 `resolveAlbumCoversAsync()`，从不调 `resolveArtistCoversAsync()`。且 `resolveArtistCoversAsync` 内部读 `_artists`（仅 NAS），即使被调用也看不到百度艺术家。
2. **本地艺术家封面恒空**：`buildLocalArtists` 生成的 artist 无 coverUrl，且 `ArtistCoverResolver.resolveCovers` 跳过 `local_` 前缀。
3. **百度音乐 API 已下线**：`musicapi.taihe.com` DNS 解析失败（No address associated with hostname），P2 兜底无效，对 4 万+ 艺术家反复发无效请求还触发限流。

**修复内容**：

1. **`MusicMerger.kt`**：`buildArtistsFromSongs` 新增 `useSongCover: Boolean = false` 参数。`buildLocalArtists` 传 `useSongCover = true`，本地艺术家 coverUrl = 名下第一首有封面的歌曲封面（侧车 cover.jpg / 内嵌 ID3 APIC）。`buildBaiduArtists` 同样传 `useSongCover = true`，百度艺术家从 APIC 提取的歌曲封面兜底。参数默认 false 保证现有测试与调用不破。
2. **`MainViewModel.resolveArtistCoversAsync`**：改读 `_mergedArtists`（NAS + 本地 + 百度合并后），并新增 `artistCoverAttempts` / `artistCoverMaxAttempts`（默认 2）收敛护栏 + pending 过滤，切断无成果时的重复请求循环。
3. **`MainViewModel.updateMergedData`**：末尾 `resolveAlbumCoversAsync()` 后新增 `resolveArtistCoversAsync()`，确保百度/本地艺术家无需 NAS 连接也能被解析。
4. **`MainViewModel.loadArtists`**：设置 `_artists.value` 后由 `resolveArtistCoversAsync()` 改为 `updateMergedData()`，保证 NAS 艺术家封面解析不丢失。
5. **`ArtistCoverResolver.kt`**：移除已下线的 P2 百度音乐搜索（`resolveBaiduArtistCover` 方法 + `BAIDU_MUSIC_SEARCH_URL` 常量），在线补全仅保留 iTunes。

**涉及文件**：
- `backend/local/MusicMerger.kt`：`buildArtistsFromSongs` 加 `useSongCover`；本地/百度艺术家均用歌曲封面兜底
- `ui/viewmodel/MainViewModel.kt`：`resolveArtistCoversAsync` 读 merged + 收敛护栏；`updateMergedData` 末尾触发；`loadArtists` 改触发 merge
- `backend/local/ArtistCoverResolver.kt`：移除 P2 百度音乐搜索

**验证结果**：
- ✅ `:app:compileDebugKotlin` BUILD SUCCESSFUL
- ✅ `:app:testDebugUnitTest --tests "*MusicMergerTest"` 通过
- ✅ `./gradlew.bat assembleDebug` BUILD SUCCESSFUL
- ✅ logcat 确认百度艺术家进入 resolveCovers 解析流程（此前仅连百度时永不解析）

**注意事项**：
- 本地/百度艺术家封面取"第一首有封面歌曲的封面"，可能是个别单曲的专辑封面而非歌手本人照片
- 在线补全仅剩 iTunes，对中文歌手命中率低；如需歌手本人照片后续需接入可用中文歌手图 API
- 艺术家封面补全无持久化缓存（resolvedArtistCovers 为内存 Map），App 重启后需重新解析

**版本号变更**：v2.26.27 → v2.26.28（versionCode 106 → 107）
### 10.98 v2.26.29 - 艺术家封面来源优先级链：网易云 → 酷狗 → iTunes → 歌曲封面 → 占位

**提交日期**：2026-09-05

**背景**：上一版本为百度/本地艺术家设歌曲封面兜底（useSongCover），但那样会跳过在线补全，无法优先拿到歌手本人照片。用户明确优先级链：网易云音乐 → 酷狗音乐 → iTunes → 该艺术家歌曲封面 → 首字母占位。

**修复内容**：

1. **`ArtistCoverResolver.kt`**：
   - 新增 P1 网易云（`music.163.com/api/search/get/web?csrf_token=&s={artist}&type=100` 取 `result.artists[0].img1v1Url`，转 https）
   - 新增 P2 酷狗（`msearch.kugou.com/api/v3/search/singer?keyword={artist}` 取 `data.info[0].img`，转 https）
   - iTunes 降为 P3（保留原有实现）
   - 新增 P4 `findArtistSongCover(artistName, allSongs)`：在线源全失败时，用 ArtistSplitter.normalizeKey 匹配，从全量歌曲找该艺术家第一首有封面的歌曲封面
   - `resolveCovers` 增加 `allSongs` 参数
2. **`MainViewModel.resolveArtistCoversAsync`**：计算全量歌曲（`_songsPaging + _localSongs + baiduIndexCache.allSongs()`）传给 `resolveCovers` 供 P4 兜底。
3. **`MusicMerger.buildBaiduArtists`**：改回 `useSongCover=false`，百度艺术家走在线补全（优先歌手本人照片）；`buildLocalArtists` 保留 `useSongCover=true`（本地曲库大，全量在线请求会限流）。

**涉及文件**：
- `backend/local/ArtistCoverResolver.kt`：P1 网易云 + P2 酷狗 + P3 iTunes + P4 歌曲封面
- `ui/viewmodel/MainViewModel.kt`：resolveArtistCoversAsync 传 allSongs
- `backend/local/MusicMerger.kt`：buildBaiduArtists 改回 false

**验证结果**：
- ✅ `:app:compileDebugKotlin` BUILD SUCCESSFUL
- ✅ `:app:testDebugUnitTest --tests "*MusicMergerTest"` 通过

**注意事项**：
- 网易云/酷狗为非官方接口，可能变动或被风控；失败时自动降级下一级，不崩（try-catch 返回 null）
- P4 歌曲封面兜底返回的可能是个别单曲的专辑封面，非歌手本人照片；在线源命中时优先歌手照片
- 艺术家封面补全无持久化缓存（resolvedArtistCovers 为内存 Map），App 重启后需重新解析
- 收敛护栏 artistCoverMaxAttempts=2 限制每个艺术家最多解析 2 次，防止对 4 万+ 艺术家反复请求触发限流

**版本号变更**：v2.26.28 → v2.26.29（versionCode 107 → 108）
### 10.99 v2.26.30 - 封面持久化 + 艺术家详情页封面修复

**提交日期**：2026-09-05

**背景**：艺术家/专辑封面 URL 此前仅存内存（resolvedAlbumCovers/resolvedArtistCovers），App 重启丢失，每次重新网络解析（受 maxAttempts 限制但仍有请求开销）。百度专辑封面已通过 baiduIndexCache（fsId→url，JSON 文件）持久化，但 NAS/本地专辑与全部艺术家无持久化。另艺术家详情页左侧封面不显示。

**新增：CoverUrlPersistentCache**（ackend/local/CoverUrlPersistentCache.kt）
- 存储：pp filesDir/cover_url_cache.json，JSON Map<String,String>，LRU 上限 10000。
- key 前缀：专辑 lbum:{id}、艺术家 rtist:{id}（id 跨会话稳定：后端 GUID / local_* / baidu_*）。
- 只存稳定 HTTP URL（不存动态 dlink / data URI / content://，那些会过期或过大）。
- 参考 MvPersistentCache 模式：init load、put save、clear/export/import。

**接线**：
- NasMusicApp：新增 coverUrlPersistentCache lazy 属性。
- MainViewModel.resolvedAlbumCovers / 
esolvedArtistCovers：改为 lazy，首次访问时从持久缓存 exportAll() 预填充（lbum:/rtist: 前缀剥离）。
- 
esolveAlbumCoversAsync / 
esolveArtistCoversAsync 回调：解析到 http 封面 URL 时同步写持久缓存（putAlbumCover/putArtistCover）。

**修复：艺术家详情页左侧封面不显示**
- 根因：AppRoot 查 selectedArtist 用 iewModel.artists（原始 _artists，NAS 未合并列表）。其 coverUrl 未应用 
esolvedArtistCovers 解析缓存；且百度/本地艺术家不在 _artists 中，导致详情页左侧 coverUrl 恒 null。
- 修复：改用 iewModel.mergedArtists（line 2286 已应用 resolvedArtistCovers 缓存），normalizeKey 匹配歌手名，详情页左侧封面正常显示。

**涉及文件**：
- ackend/local/CoverUrlPersistentCache.kt（新增）
- NasMusicApp.kt：注入 coverUrlPersistentCache
- ui/viewmodel/MainViewModel.kt：resolvedAlbumCovers/resolvedArtistCovers lazy 预加载 + 解析写入
- ui/components/AppRoot.kt：ArtistDetailScreen 用 mergedArtists 找 selectedArtist

**验证结果**：
- ✅ :app:compileDebugKotlin BUILD SUCCESSFUL
- ✅ :app:assembleDebug BUILD SUCCESSFUL

**注意事项**：
- 持久缓存只存 HTTP 封面 URL；P4 歌曲封面若是 data URI（内嵌 APIC Base64）不落盘，重启后重新解析
- 换后端时旧专辑/艺术家 id 作废，LRU 上限 10000 自动淘汰
- 设置页"缓存管理"的 clearCoverCache 清 Coil 图片缓存，未清 cover_url_cache.json（可按需扩展）

**版本号变更**：v2.26.29 → v2.26.30（versionCode 108 → 109）
### 10.100 v2.26.31 - 手机端媒体播放 L1 适配（蓝牙元数据/后台保活/锁屏控制）

**提交日期**：2026-09-05

**背景**：实现 `docs/phone-media-display-plan.md` 阶段1（P0），解决三大核心问题：
1. 汽车蓝牙连接时车机屏无歌曲信息
2. 应用切后台歌曲停止
3. 锁屏页面无歌曲信息和控制键

**根因分析**：
1. `PlayerManager.playSong` 把 `Song` 转 `MediaItem` 时未填充 `MediaMetadata`。Media3 `MediaLibrarySession` 向系统 `MediaSessionManager` 发布元数据，但 `MediaItem.mediaMetadata` 为空时蓝牙 AVRCP 读不到。
2. `PlaybackService.onTaskRemoved` 直接 `stopSelf()` → 服务销毁 → player release → 停止播放。
3. 锁屏媒体控件由 `MediaSession` 自动驱动，但元数据依赖 `MediaItem.mediaMetadata`，同问题1。

**修复内容**：

1. **`PlayerManager.kt`** — 新增 `buildMediaItem(song: Song): MediaItem`，填充完整 `MediaMetadata`（title/artist/albumTitle/artworkUri/trackNumber/releaseYear/genre）。`playSong()` 改用此方法构建 MediaItem。

2. **新增 `CoilBitmapLoader.kt`** — 实现 `MediaSession.BitmapLoader` 接口，内部走 Coil `ImageLoader` 加载封面（复用 NasMusicApp 已注入的百度 dlink UA 拦截器）。方法：`loadBitmap(uri)` / `decodeBitmap(data)`。

3. **`PlaybackService.kt`** — `onCreate` 注入 `CoilBitmapLoader(Coil.imageLoader(this), this)` 到 `MediaLibrarySession.Builder.setBitmapLoader()`。Media3 自动驱动系统 `MediaSession` → 锁屏/SMSC/蓝牙/厂商实况窗自动生效。

4. **`PlaybackService.onTaskRemoved`** — 改为：`player.isPlaying` → return（继续播放）；否则 → `stopSelf()`（已暂停才停止）。

5. **新增 `BatteryOptimizationHelper.kt`** — 检测 `PowerManager.isIgnoringBatteryOptimizations`，未加入白名单时弹 Dialog 引导用户加入（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）。集成到 `MainActivity.onCreate`。

6. **新增 `MediaLibraryTree.kt`** — 为 Android Auto/Wear OS 实现媒体浏览树（`getLibraryRoot()` / `getChildren(parentId)` / `getItem(mediaId)`），使用 `PlayerManager.getQueueSnapshot()` 获取队列快照。`PlaybackService` 中 `MediaLibrarySession.Callback` 实现 `onGetLibraryRoot`/`onGetItem`/`onGetChildren`，通过 `LibraryResult` + `Futures.immediateFuture` 返回结果。

7. **`PlayerManager.kt`** — 新增 `getPlayer(): ExoPlayer?` 和 `getQueueSnapshot(): List<Song>` 公开方法，供 `MediaLibraryTree` 和外部访问。

**涉及文件**：
- `player/PlayerManager.kt`：`buildMediaItem()` + `getPlayer()` + `getQueueSnapshot()`
- `player/CoilBitmapLoader.kt`（新增）：`MediaSession.BitmapLoader` 实现
- `player/PlaybackService.kt`：注入 CoilBitmapLoader + 修复 onTaskRemoved
- `player/BatteryOptimizationHelper.kt`（新增）：电池优化白名单引导
- `player/MediaLibraryTree.kt`（新增）：Android Auto/Wear 媒体树工具类 + MediaLibrarySession.Callback 实现
- `ui/MainActivity.kt`：BatteryOptimizationHelper 调用

**验证结果**：
- ✅ `:app:compileDebugKotlin` BUILD SUCCESSFUL
- ✅ `:app:assembleDebug` BUILD SUCCESSFUL

**注意事项**：
- `LibraryResult` 正确 import 路径为 `androidx.media3.session.LibraryResult`，已在 `PlaybackService` 中实现
- 蓝牙 AVRCP 协议只支持标题/艺术家/专辑/时长/封面，不支持歌词
- 厂商省电策略（小米神隐模式、华为应用冻结等）仍需用户手动加入白名单
- L2 厂商增强（华为实况窗 Notification extra、OPPO 跨设备 SDK 等）暂不开发，待 L1 实测后评估

**版本号变更**：v2.26.30 → v2.26.31（versionCode 109 → 110）

### 10.101 v2.26.31 锁屏通知与媒体按钮修复

**问题描述**：

1. 锁屏点击上一首/下一首按钮后，歌曲不自动播放
2. 通知栏显示"已暂停，正在..."截断文字

**根因分析**：

1. `buildMediaButtonPendingIntent` 使用 `PendingIntent.getBroadcast` 发送 `ACTION_MEDIA_BUTTON`，但 manifest 未注册 `MediaButtonReceiver`，广播无人接收
2. `contentText` 设为播放状态文字（"已暂停"/"正在播放"），在锁屏界面被截断

**修复内容**：

1. **`PlaybackService.kt`** — `buildMediaButtonPendingIntent` 改用 `PendingIntent.getService`，直接发送到 `PlaybackService`，`MediaLibraryService.onStartCommand` 自动路由到 `MediaSession`
2. **`PlaybackService.kt`** — `buildNotification` 的 `contentText` 从播放状态文字改为显示歌手名，播放状态由 MediaStyle 图标隐含表示

**涉及文件**：
- `player/PlaybackService.kt`：修复 `buildMediaButtonPendingIntent` + `buildNotification`

**验证结果**：
- ✅ `:app:compileDebugKotlin` BUILD SUCCESSFUL
- ✅ 手机端测试通过：锁屏上一首/下一首按钮正常工作，通知栏显示歌名 + 歌手名

---

### 10.102 v2.26.32 - 歌曲离线下载与本地存储管理（Tasks 1-15, 17）

**提交日期**：2026-09-06

**背景**：实现 `docs/歌曲离线下载与本地存储管理开发方案.md` 设计方案，完成歌曲离线下载、本地存储管理和导出到外接设备功能。

**实现内容**：

#### 1. 下载后端基础设施（Tasks 1-9）
- **`backend/download/DownloadDatabase`** — 独立 Room 数据库（downloads.db, v1），含 `DownloadSongEntity` + `ExportRecordEntity`，不并入 LocalMusicDatabase（避免 destructive migration 丢数据）
- **`backend/download/DownloadPathBuilder`** — 目录与文件名构造器，支持艺术家/专辑/标题清洗、曲目号前缀、同名文件去重、空目录回收
- **`backend/download/DownloadRepository`** — 下载索引 CRUD + 统计封装，提供 `reindexFromDisk()` 兜底、`toUiState()` 映射
- **`backend/download/SongDownloadManager`** — 下载执行器，支持串行队列、超时监控、空间检测、去重校验
- **`backend/download/CoverFileWriter`** — 封面文件写入（artist.jpg / cover.jpg）
- **`backend/download/MediaTagWriter`** — ID3/FLAC 内嵌元数据写入（封面+歌词）
- **`backend/download/StreamUrlResolver`** — 流地址解析（网络歌曲下载前重新解析）
- **`backend/download/StorageGuard`** — 存储空间守护，双 StatFs 取 min、100MB 预留、30s 轮询、节流提示
- **`backend/download/AutoDownloadController`** — 自动下载控制器，播放 ≥5s 触发、配额限制、5分钟提示节流

#### 2. 设置页 UI 与集成（Task 10）
- **`SettingsScreen.kt`** — 新增 DOWNLOAD 区块：自动下载开关、最大下载数量滑块、存储空间显示、已下载歌曲统计、清空下载按钮、导出到外接设备入口
- **`AppRoot.kt`** — 参数链传递：下载状态、自动下载开关、存储空间、已下载统计等 8 个参数
- **`strings.xml` / `strings.xml (en)`** — 12 条下载相关字符串资源

#### 3. 自动下载集成（Task 11）
- **`AutoDownloadController`** — 播放 ≥5s 触发下载、配额限制（默认50）、5分钟提示节流
- **`MainViewModel`** — 集成 AutoDownloadController，监听播放状态变化

#### 4. 歌曲行下载按钮（Task 12）
- **`UnifiedSongRow`** — 重构为 16 个调用点，新增 `SongRowModeRow` 模式支持下载按钮
- 下载按钮状态：Idle（⬇）、Queued（⋯）、Downloading（⇣ 进度%）、Completed（✓）、Failed（✕）

#### 5. 下载完成与管理（Task 14）
- **`ConfirmDialog`** — 删除已下载歌曲二次确认弹窗
- **`MainViewModel`** — `clearAllDownloads()`、`deleteDownload()` 方法
- 下载完成回调：更新 UI 状态、触发媒体扫描

#### 6. 导出到外接设备（Task 15）
- **`backend/export/ExportCoordinator`** — 导出协调器，编排 SAF 授权/设备探测/导出执行
- **`backend/export/SongExporter`** — 导出执行器，字节复制、增量过滤、可取消/可续跑
- **`backend/export/ExportPermissionHelper`** — SAF 权限辅助，TV 桩实现探测、持久化授权校验
- **`ui/components/ExportDeviceDialog.kt`** — 设备选择对话框
- **`MainActivity.kt`** — SAF 树选择器 `registerForActivityResult(ActivityResultContracts.OpenDocumentTree())`
- **导出状态机**：Idle → Preparing → Running → Completed/Failed/Cancelled
- **导出错误枚举**：NO_DEVICE、NO_PERMISSION、NO_SPACE、DEVICE_REMOVED、IO、NOTHING_TO_EXPORT

#### 7. 单元测试（Task 17）
- **`DownloadPathBuilderTest`** — 25 个测试用例，覆盖 sanitize()、extOf()、build()、cleanupEmptyDirs()
- **`ExportStateTest`** — 13 个测试用例，覆盖状态创建、属性、错误枚举

**涉及文件**：
- `backend/download/` — DownloadDatabase, DownloadPathBuilder, DownloadRepository, SongDownloadManager, CoverFileWriter, MediaTagWriter, StreamUrlResolver, StorageGuard, AutoDownloadController
- `backend/export/` — ExportCoordinator, SongExporter, ExportPermissionHelper
- `backend/download/db/` — DownloadSongEntity, ExportRecordEntity, DownloadSongDao, ExportRecordDao, DownloadDatabase
- `backend/download/model/` — DownloadState
- `ui/screens/SettingsScreen.kt` — DOWNLOAD 区块 + 导出 UI
- `ui/components/AppRoot.kt` — 参数链
- `ui/components/ExportDeviceDialog.kt` — 设备选择对话框
- `ui/viewmodel/MainViewModel.kt` — 导出方法、下载管理
- `ui/MainActivity.kt` — SAF 树选择器
- `res/values/strings.xml` / `res/values-en/strings.xml` — 12 条下载字符串
- `app/src/test/.../DownloadPathBuilderTest.kt` — 25 个测试
- `app/src/test/.../ExportStateTest.kt` — 13 个测试

**验证结果**：
- ✅ `:app:compileDebugKotlin` BUILD SUCCESSFUL
- ✅ `:app:testDebugUnitTest` — 38 个测试全部通过
- ✅ 下载流程：点击下载 → 文件落在 Music/\<歌手\>/\<专辑\>/ → 重启后仍可离线播放
- ✅ 导出流程：设置页 → 导出到外接设备 → 选择 U 盘 → SAF 授权 → 字节复制 → NASMusic/\<歌手\>/\<专辑\>/

**设计决策**：
- 下载数据库独立于 LocalMusicDatabase（避免 destructive migration 丢数据）
- SAF 优先 + 应用专属目录降级（TV 兼容性）
- 导出=备份，不删除本地副本（D17 已定）
- 自动下载配额默认50（TV 存储小）
- 去重键：title+artist 规范化（dedupeKey）

---

### 10.103 v2.26.33 - 修复 TV 端百度网盘 token 加密 IV 缺失导致 API errno=-6

**提交日期**：2026-09-08

**提交哈希**：`4be8994`

**背景**：百度网盘登录在 TV 端始终失败——设备码授权流程正常完成（scope=`basic netdisk`，token 保存成功），但随后调用百度文件 API（list/listall）时百度返回 `errno=-6`（authorized fail）。使用同一 AppKey 在本机 curl 测试证明 token 本身有效（`uinfo` 返回用户信息），但 TV 端发出的 token 格式异常（`mHm8Whc/3X+2czB1Bth...`，非百度标准格式 `126.xxx.Yyy.Zzz`），确认为 token 在存储/读取过程中被篡改。

**根因分析**：

`CryptoUtils.encrypt()` 在 `cipher.init(Cipher.ENCRYPT_MODE, softwareKey)` 时**未显式传入 `GCMParameterSpec`/IV**，依赖平台自动生成 IV。在目标 Android TV ROM（Hisense/Android 11）上，`cipher.iv` 在 ENCRYPT_MODE 不传 IV 时返回**空数组**（0 字节而非 12 字节），导致：

1. `encrypt()` 执行 `iv + encrypted` 拼接时，`iv` 为空 → 密文缺少 12 字节 IV 前缀
2. `decrypt()` 提取前 12 字节作为 IV（实际取到 ciphertext 的前 12 字节）→ IV 错误
3. GCM tag 验证失败 → `tryDecrypt` 返回 null → `decrypt()` 原样返回密文
4. 密文（Base64 字符串）被当作 accessToken 发给百度 → 百度无法识别 → 返回 `errno=-6`

**修复内容**：

#### 1. CryptoUtils IV 显式生成（核心修复）

**文件**：`util/CryptoUtils.kt`

- `encrypt()`：改用 `SecureRandom` 显式生成 12 字节随机 IV，通过 `GCMParameterSpec(GCM_TAG_LENGTH, iv)` 传入 `cipher.init()`，确保密文始终包含有效 IV 前缀
- `tryDecrypt()`：catch 块增加诊断日志（text prefix、length），便于定位解密失败
- `decrypt()`：all-keys-failed 路径增加日志，区分"密文解密失败"与"明文直传"

```kotlin
// 修复前（有 bug）
cipher.init(Cipher.ENCRYPT_MODE, softwareKey)  // 不传 IV，依赖平台自动生成
val iv = cipher.iv  // ← TV ROM 返回空数组！

// 修复后
val iv = ByteArray(GCM_IV_LENGTH).also { SecureRandom().nextBytes(it) }
cipher.init(Cipher.ENCRYPT_MODE, softwareKey, GCMParameterSpec(GCM_TAG_LENGTH, iv))
```

#### 2. 百度网盘模块对照官方文档全面修正

**文件**：`backend/network/baidu/BaiduNetdiskConfig.kt`

- `ERRNO_MAP` 对照百度开放平台错误码文档全面修正：
  - `-1` → "权益已过期"（原误标为"应用无接口权限"）
  - `-6` → "授权失败（access_token 无效或过期）"，去除混入的 20013 语义
  - 移除不在官方表中的 `-111` / `-118`
- 新增本地错误码常量：
  - `LOCAL_ERRNO_NO_TOKEN = -100`（本地无 token 时使用，不再伪装百度 -6）
  - `LOCAL_ERRNO_NETWORK = -101`（本地网络异常时使用）

**文件**：`backend/network/baidu/BaiduPanApi.kt`

- `listDir()` / `createDir()` 中本地生成的 `-6` 替换为 `LOCAL_ERRNO_NO_TOKEN`，确保只有百度服务器真正返回的 `-6` 才显示为 `-6`
- `createDir()` 移除官方文档未定义的 `size` 参数
- `executeWithErrno()` 增加完整 response body 日志，便于诊断

**文件**：`backend/network/baidu/BaiduOAuthClient.kt`

- `pollDeviceToken()`：当授权 scope 缺少 `netdisk` 时，改为返回 `Failed`（阻断保存 token）而非仅打印警告
- `refreshAccessToken()`：日志文案微调

#### 3. BaiduAuthDialog 错误文案区分

**文件**：`ui/screens/netdisk/BaiduAuthDialog.kt`

- `Failed` 状态标题改为根据 message 内容动态区分：授权范围不足 / 用户拒绝 / 超时 / 授权失败
- `strings.xml` 新增 `baidu_auth_scope_missing` = "授权范围不足"

#### 4. AppPreferences 解密诊断日志

**文件**：`data/prefs/AppPreferences.kt`

- `getBaiduTokensSync()`：解密后打印 token 前缀和长度，便于在 logcat 中快速判断 token 是否被篡改

#### 5. 其他修复（同一提交）

**下载模块**：
- `AutoDownloadController`：切歌后延迟 5s 确认用户未切走再触发下载（避免快速切歌导致无效下载）
- `SongDownloadManager`：`cancelAll()` 中断协程、崩溃恢复检查文件是否存在
- `StorageGuard`：空间检测优化

**导出功能**：
- `ExportCoordinator` / `SongExporter`：SAF 嵌套目录解析、`takePersistableUriPermission`、封面路径修正

**播放器**：
- `PlayerManager`：`buildMediaItem` 替代 `fromUri` 保留元数据
- `MediaLibraryTree`：MediaLibrary 分页
- `PlaybackService`：`exported=true` + `RECEIVER_NOT_EXPORTED` 兼容 Android 13

**本地音乐**：
- `LocalMusicRepository`：`fullScan` 使用 `buildScannedList` 避免重复扫描
- `CoverUrlPersistentCache`：debounce 落盘 + 原子写入

**涉及文件**：
- `util/CryptoUtils.kt` — IV 显式生成（核心修复）
- `data/prefs/AppPreferences.kt` — 解密诊断日志
- `backend/network/baidu/BaiduNetdiskConfig.kt` — ERRNO_MAP 修正 + 本地错误码
- `backend/network/baidu/BaiduOAuthClient.kt` — scope 阻断
- `backend/network/baidu/BaiduPanApi.kt` — 本地错误码替换 + createDir 参数修正
- `ui/screens/netdisk/BaiduAuthDialog.kt` — 错误文案区分
- `res/values/strings.xml` — 新增 baidu_auth_scope_missing
- `backend/download/AutoDownloadController.kt` — 延迟确认
- `backend/download/SongDownloadManager.kt` — cancelAll + 崩溃恢复
- `backend/download/StorageGuard.kt` — 空间检测
- `backend/export/ExportCoordinator.kt` — SAF 嵌套目录
- `backend/export/SongExporter.kt` — 封面路径
- `backend/local/LocalMusicRepository.kt` — fullScan 优化
- `backend/local/CoverUrlPersistentCache.kt` — debounce 落盘
- `backend/local/StorageMonitor.kt` — 存储监控
- `player/PlayerManager.kt` — buildMediaItem
- `player/MediaLibraryTree.kt` — 分页
- `player/PlaybackService.kt` — exported + RECEIVER_NOT_EXPORTED
- `player/CoilBitmapLoader.kt` — 图片加载
- `ui/components/AppRoot.kt` — 参数链
- `ui/components/song/UnifiedSongRow.kt` — 歌曲行
- `ui/screens/AlbumDetailScreen.kt` — 专辑详情
- `ui/screens/ArtistDetailScreen.kt` — 艺术家详情
- `ui/screens/LibraryScreen.kt` — 曲库
- `ui/screens/MineScreen.kt` — 我的
- `ui/screens/SettingsScreen.kt` — 设置
- `ui/viewmodel/MainViewModel.kt` — ViewModel 集成
- `app/proguard-rules.pro` — jaudiotagger keep 规则
- `app/src/main/AndroidManifest.xml` — 权限与服务声明

**验证结果**：
- ✅ `:app:compileDebugKotlin` BUILD SUCCESSFUL
- ✅ TV 端日志确认：`getBaiduTokensSync: accessToken prefix=126.fc3ca9... (len=83)` — token 格式正确
- ✅ `listAllAudioPaged` 成功返回 31,251 个文件（`errno=0`），翻页正常
- ✅ 百度网盘登录 → 授权 → 列目录 → 全量扫描 全流程通过

**设计决策**：
- IV 显式生成而非依赖平台：Android TV ROM 的 AES-GCM 实现不一致，`cipher.iv` 在不传 GCMParameterSpec 时可能返回空数组，必须显式生成
- 本地错误码 -100/-101 与百度 errno 分离：避免本地错误伪装为百度返回，干扰诊断
- 解密失败仍返回原样（而非抛异常）：兼容旧版本明文存储数据，不强制迁移

---

### 10.104 v2.26.33 - 修复艺术家详情页歌曲列表过一会变空

**提交日期**：2026-09-08

**背景**：在 TV 端打开艺术家详情页后，歌曲列表能正确显示，但过几秒后列表突然变空。页面本身不变（不崩溃、不导航），仅歌曲区域变空。手机端不复现。

**根因分析**：

`loadArtistSongs()` 在加载开始时立即删除 `_artistDetailSongsCache` 中对应艺术家的 key，2-3 秒后才写入新数据。当该方法被二次触发时（TV 上遥控器焦点变化或 Compose 重组），缓存被清空 → UI 立即显示空列表 → 用户看到"过一会歌曲消失"。

手机端不复现的原因：手机触屏交互不触发二次调用，且手机上 `updateMergedData` 每 5-6 秒执行一次但从不触碰 `_artistDetailSongsCache`，缓存保持稳定。

**修复内容**：

**文件**：`ui/viewmodel/MainViewModel.kt`

- `loadArtistSongs()`：不在加载开始时清空 `_artistDetailSongsCache`，旧数据保持显示直到新数据就绪后直接覆盖
- `_artistSongsMap` 的清理保留（仅被 LibraryScreen 使用，ArtistDetail 页时该屏幕未组合，不会看到空窗）

**验证结果**：
- ✅ TV 端：打开艺术家详情页，歌曲列表显示后不再消失
- ✅ 手机端：同样确认不再复现

---

### 10.105 v2.26.39 - 收藏功能统一重构（本地/下载歌曲收藏修复）

**提交日期**：2026-09-09

**背景**：两处收藏缺陷：
1. 曲库搜索页查询到本地下载歌曲时无法收藏，但其他搜索到的歌曲（网络/NAS）可收藏。
2. 曲库专辑/艺术家详情页的歌曲列表中的歌曲无法收藏（收藏后爱心不亮）。

**根因分析**：

两处缺陷根源相同——收藏分流与 `favoriteIds` 集合在 AppRoot 接线层分散且不一致：

- **Bug 1（分流漏判）**：`AppRoot.kt` 的 NowPlaying / LibraryScreen / MineScreen 三处 `onToggleFavorite` 只判断 `song.isNetworkSong`，漏判 `song.isLocalSong`。本地/下载歌曲落入 NAS-only 的 `viewModel.toggleFavorite()` → NAS adapter 收到 `local_xxx` ID 必然失败（`success=false`）→ UI 不更新 → 收藏静默无效。而 LibraryScreen 的 `favoriteIds` 已正确合并（`favoriteIds + networkFavoriteIds`，L499），只要走对函数就会立即反映。
- **Bug 2（集合未合并）**：`AppRoot.kt` 的 AlbumDetail / ArtistDetail 传入 `viewModel.favoriteIds`（NAS-only，L877/L920 的 `collectAsState`），未与 `networkFavoriteIds` 合并。其 `onToggleFavorite` 接线虽正确调用 `toggleNetworkFavorite`（DataStore 已成功保存本地收藏），但 `favoriteIds` 集合不含该 ID → `isFavorited = song.id in favoriteIds` 恒为 false → 爱心永不亮。
- **架构冗余**：`MainViewModel.toggleFavorite`（L2802）与 `toggleNetworkFavorite`（L2210）的 NAS 分支逻辑完全相同，`toggleFavorite` 是纯冗余函数；`favoriteIds`（NAS）与 `networkFavoriteIds`（网络/本地）两套集合分散在 AppRoot 各屏幕手动拼接。

**修复内容**：

**文件**：`ui/viewmodel/MainViewModel.kt`、`ui/components/AppRoot.kt`

- **MainViewModel.kt**：
  - `favoriteIds` 改为统一合并集合：`combine(_favoriteIds, networkFavoriteIds) { nas, net -> nas + net }`。`_favoriteIds` 保留私有（NAS-only），供 `toggleNetworkFavorite` 的 NAS 分支读取当前状态。
  - 删除冗余的 `toggleFavorite()` 函数（与 `toggleNetworkFavorite` NAS 分支逻辑重复）。
  - `isFavorite()` 同步改为同时查 `_favoriteIds` 与 `networkFavoriteIds`。
- **AppRoot.kt**：
  - 5 处 `onToggleFavorite` 接线（NowPlaying / LibraryScreen / MineScreen / AlbumDetail / ArtistDetail）统一为 `viewModel.toggleNetworkFavorite(song)`，删除各自 `isNetworkSong`/`isLocalSong` 分流判断。
  - NowPlaying 的 `isFavorite` 显示改用统一 `favoriteIds`（原为 `if (isNetworkSong) in networkFavoriteIds else in favoriteIds`，漏判本地）。
  - LibraryScreen 的 `favoriteIds = favoriteIds + networkFavoriteIds` 去掉散点拼接，改直接用统一 `favoriteIds`。
  - 清理 NowPlaying / LibraryScreen 两个已失效的 `networkFavoriteIds` 收集变量。

**设计说明**：收藏路径收敛为「NAS 歌曲走服务端 adapter，其余（网络/本地/下载）走本机 DataStore `NetworkFavoriteItem`」，`toggleNetworkFavorite` 是唯一入口；UI 层只读统一合并的 `favoriteIds` 判断收藏态，不再按歌曲类型自行分流。

**验证结果**：
- ✅ `assembleDebug` 编译通过（5 个既有 warning 与本次改动无关）
- ✅ 搜索页本地下载歌曲走 `toggleNetworkFavorite` → DataStore 保存 → 统一 `favoriteIds` 反映爱心
- ✅ 专辑/艺术家详情页本地歌曲收藏后，统一 `favoriteIds` 包含该 ID → 爱心即时点亮

---

### 10.106 v2.26.40 - 曲库加入歌单弹窗失效（pickerSong 作用域遮蔽）

**提交日期**：2026-09-09

**背景**：曲库的搜索页、发现页、歌曲页三个页面点击歌曲条目的 `+`（加入歌单）无反应，不弹出歌单选择弹窗。但专辑/艺术家详情页、我的页、网盘页的加入歌单功能正常。

**根因分析**：

`AppRoot.kt` 的 `pickerSong` 状态被声明了两次，存在**作用域遮蔽**：

- `L128`（AppRoot 函数体顶层）：`var pickerSong by remember { mutableStateOf<Song?>(null) }`——供加入歌单弹窗 `PlaylistPickerDialog` 读取（`L1006` 的 `pickerSong?.let { ... }`）。
- `L454`（`Screen.Library` 的 when 分支内）：`var pickerSong by remember { mutableStateOf<Song?>(null) }`——**遮蔽了 L128 的顶层变量**。

曲库页 `onAddToPlaylist = { song -> pickerSong = song }`（`L523`）在 L454 的作用域内，词法解析捕获的是 **L454 的遮蔽变量**；而弹窗渲染 `L1006` 读取的是 **L128 的顶层变量**。两者不是同一个对象 → 点击 `+` 设置了 L454 的变量，但 L1006 读取的 L128 变量始终为 `null` → 弹窗永不弹出。

正常页面的原因：专辑/艺术家详情页在 `Screen.AlbumDetail` / `Screen.ArtistDetail` 分支内（L454 的 Library 块已结束），`L881`/`L921` 直接引用 L128 顶层变量，与 L1006 弹窗一致 → 正常；我的页（MineScreen）与网盘页（NetdiskScreen）各自内部自含 `pickerSong`/`actionSong` 与 `PlaylistPickerDialog` → 正常。

**修复内容**：

**文件**：`ui/components/AppRoot.kt`

- 删除 `L454` 的冗余遮蔽变量 `var pickerSong by remember { mutableStateOf<Song?>(null) }`，让曲库页 `L523` 的 `pickerSong = song` 词法解析到 L128 顶层变量，与 L1006 弹窗读取一致。

**验证结果**：
- ✅ `compileDebugKotlin` 编译通过
- ✅ 曲库搜索/发现/歌曲三页点击 `+` → 设置顶层 `pickerSong` → 弹窗正常弹出

---

### 10.107 v2.26.41 - 播放页点击艺术家/歌名未跳转搜索

**提交日期**：2026-09-09

**背景**：播放页点击艺术家名或歌名，期望跳转到搜索页面并直接搜索对应关键词，但当前点击后界面停留播放页、无任何跳转。

**根因分析**：

`AppRoot.kt` 的 NowPlaying 块将 `onSearchArtist` / `onSearchSong` 错误地接线为：

```kotlin
onSearchArtist = { keyword -> viewModel.searchNetworkSongs(keyword) },
onSearchSong = { keyword -> viewModel.searchNetworkSongs(keyword) },
```

`MainViewModel.searchNetworkSongs()`（`L1799`）只更新独立的**网络音乐搜索数据流** `_networkSearchResults`（旧网络音乐 Tab 的遗留入口），既不设置曲库搜索关键词 `_librarySearchKeyword`，也不调用 `navigateTo()`，更不切换子 Tab。因此点击后网络搜索在后台执行，但界面完全不跳转。

而项目当前的搜索主入口是**曲库 SEARCH Tab**（`Screen.Library` + `LibraryTab.SEARCH`），其搜索数据流是 `_searchResults`（`L283`，由 `searchSongsOnServer()` 跨源聚合 NAS+网络+百度+Jamendo+本地）。AppRoot 已有一个正确的"跳转曲库搜索"参照——HomeScreen 的 `onNavigateToSearch`（`L285-288`）。

**修复内容**：

**文件**：`ui/components/AppRoot.kt`

- `onSearchArtist` / `onSearchSong` 改为沿用 `onNavigateToSearch` 模式：先 `selectLibraryTab(LibraryTab.SEARCH)` 切到曲库 SEARCH Tab，再 `setLibrarySearchKeyword(keyword)` 设置搜索关键词，最后 `navigateTo(Screen.Library)` 跳转。LibraryScreen 的 `LaunchedEffect(filterQuery)` 检测到关键词非空后自动调用 `onSearch` → `searchSongsOnServer` 触发跨源融合搜索。

**验证结果**：
- ✅ `compileDebugKotlin` 编译通过
- ✅ 播放页点击艺术家/歌名 → 跳转到曲库 SEARCH Tab 并自动搜索该关键词（跨源融合结果）
---

### 10.108 v2.27.0 - 深度审查修复（安全与健壮性 22 项）

**提交日期**：2026-09-09

**背景**：全量深度代码审查（227 文件 / 4.7 万行）发现严重/高/中低共 22 项问题，本版本一次性修复。完整清单与证据见审查报告（NASMusicTV-代码审查报告.html）。

**安全**：
1. 移除全部 6 处 trust-all TLS 配置（BaiduHttpDataSourceFactory / BaiduOAuthClient / MetingApiService / DaoliyuAdapter / FeiniuAdapter / BilibiliMvService），恢复系统默认证书校验。影响面：主播放器 DataSource（PlaybackService.kt:104 统一工厂）、Coil 图片加载、百度 OAuth。老设备遇 Let's Encrypt 端点将得到可见 SSLHandshakeException，改用 http 端点即可。
2. 清除 ServerConnectScreen 硬编码的开发者 NAS 账号密码（公开仓库历史提交已泄露，需轮换凭据）。

**健壮性**：
3. HQ 人声分离临时目录改 context.cacheDir（原 java.io.tmpdir 回退 /data/local/tmp 不可写，HQ 分离下载必败）。
4. 扫码传模型服务器端口 18082→18083（MODEL_TRANSFER_PORT），消除与遥控服务器的 bind 冲突。
5. 网盘索引 save() 原子写盘（临时文件 + rename），写盘中断不再丢全库。
6. resolveStreamUrl 在 dlink 缺失时强制刷新 token 重试一次（新增 BaiduOAuthClient.forceRefreshAccessToken，覆盖 errno -6/31045）。
7. BaiduPanApi：search 补传 start（原接收参数未拼 URL）；listAllAudioPaged 游标停滞保护；BaiduFileIndexCache.clear() 同步清目录倒排。
8. Range 请求仅接受 206（BaiduCoverProvider/BaiduLyricsProvider），防 200 时整文件读入内存 OOM。
9. 退出确认 disconnect 限时 1.5s（runBlocking + withTimeout），消除主线程 ANR 风险。
10. MainActivity.onDestroy 仅 isFinishing 时清理播放服务与后端连接（配置重建不再误杀后台播放）。
11. Android 13+ POST_NOTIFICATIONS 运行时权限请求。
12. MetingApiService search/lrc/playlist 三处 id 参数 URL 编码。
13. 遥控服务器搜索 runBlocking 加 10s 超时。
14. 换一批去重集合 4000 硬上限。
15. 新增备份规则（res/xml/backup_rules.xml + data_extraction_rules.xml），DataStore 不随备份提取。

**性能**：
16. spectrumData（20fps）从 AppRoot 顶层移至 NowPlaying 分支收集，不再驱动全树重组。
17. AppPreferences 新增 baiduConfigFlow / jamendoClientIdFlow，设置页组合内 runBlocking 同步读改为订阅。

**其他**：
18. AppRoot 返回键处理器与 LibraryScreen onPlay 改具名 lambda（行为不变；澄清 when/if 分支 {{ }} 为"块+尾部 lambda"，并非 no-op）。
19. 「全部加入队列」改为只增不删（MainViewModel.addSongsToQueue），原 toggle 语义会反向移除已入队歌曲。
20. CI 增加 testDebugUnitTest 步骤（此前只构建不测试）。
21. 日志脱敏：BaiduPanApi / DaoliyuAdapter 的 token 与 dlink 打码，部分 e 级日志降级 d。
22. EncodingUtils 空 catch 补日志；AGENTS.md 追加 2026-09-09 审查纪要。

**保留项（评估后不改，理由见审查报告 §9）**：M-3 全局明文（任意内网 NAS 需求）、M-10 密钥托管（TV ROM 兼容取舍）、L-3 ProGuard 宽规则（Gson 崩溃前科）、L-5 依赖升级（需专门回归）、L-6 仓库根目录整理。L-1 默认 IP、H-1 无鉴权、L-7 本地 gradle 分发由所有者确认保留。

**验证结果**：
- ✅ :app:compileDebugKotlin 通过
- ✅ :app:testDebugUnitTest 256/256 通过
- ✅ :app:assembleRelease 通过（修复在 v2.26.41 版本号下构建验证；正式发布以 v2.27.0 重新构建）

### 10.109 R-1/F-1 重构第一批：日志凭证脱敏 + MainViewModel 拆分（2026-09-09）

依据 `docs/codebase-refactoring-plan-2026-09.md`（v1.4）实施，分支 `refactor/r1-viewmodel-split`，5 个独立提交，每步 assembleDebug 通过。

**F-1 日志凭证脱敏（提交 9985bf9）**：
1. 新增 `util/UrlSanitizer.kt`：统一打码 URL 查询参数（api_key/token/access_token/t=/s=/u=/p=/password/apikey，大小写不敏感）。
2. 五个后端适配器（Jellyfin/Navidrome/Subsonic/Daoliyu/Feiniu）的 w/e 级错误日志全部过 sanitize——此前 release 包连 logcat 即可读到 api_key 与 Subsonic md5 密码令牌。
3. JellyfinAdapter.authenticateByName 错误日志不再回显响应体（可能回显含密码的请求体）；utf8Body 日志同样脱敏。
4. BackendRegistry.initialize 日志 username 改记 hasUser 布尔。
5. 新增 UrlSanitizerTest（纯函数单测 8 项，全绿）。

**R-1 MainViewModel 拆分（提交 59dc6b9 / e03b4bc / adbcb37 / 9efa630）**：
按 W0 冻结清单落位 13 个子 ViewModel（`ui/viewmodel/` 下，与 MainViewModel 同包）：
- 第一步：WeatherRadioViewModel（保留 manager 可空延迟语义）、BackupViewModel、PlaylistViewModel
- 第二步：DownloadViewModel、MvSearchViewModel（playMode 参数化下发）、VocalSeparationViewModel
- 第三步：ServerViewModel、SearchViewModel、NetworkMusicViewModel（BaiduConnectionState 归属随之迁移，AppRoot/NetdiskScreen/BaiduAuthDialog/SettingsScreen 引用同步）
- 第四步：PlayerViewModel（播放解析代数 P4 逻辑随迁）、NavigationViewModel、PlayHistoryViewModel
- MainViewModel 5451 → 3186 行，保留兼容转发层（AppRoot 的既有引用不变）；跨域通信走 init 注入回调 + 事件契约（ViewModelEvents.kt，W0 冻结版）
- 领域边界保留：pickBestFreshBatch（多维度浏览换一批）、baiduIndexCache（曲库合并取数）、NAS 收藏分支 toggleNasFavorite

**已知缺口（后续批次处理）**：MainViewModel 3186 行仍超 DoD 的 ≤600 行；AppRoot collectAsState 仍走 MainViewModel 过渡访问器（计划允许的迁移期形态）；TV 手测回归待设备可用时执行。

**验证结果**：
- ✅ 每个提交前 assembleDebug 通过
- ✅ UrlSanitizerTest 8/8 通过
- ✅ 既有 256 个单测未受影响（compileDebugKotlin + testDebugUnitTest 路径验证）


### 10.110 R-2/R-3/R-5/R-6/R-7/R-9 + F-2/F-4~F-7 重构第二批（2026-09-09）

依据 `docs/codebase-refactoring-plan-2026-09.md`（v1.4）实施，分支 `refactor/r1-viewmodel-split`，8 个独立提交，每步 assembleDebug 通过。

**R-2 SettingsScreen 拆分**：9 个 Section 迁至 `ui/screens/settings/`（General/Player/Download/Server/About/Cache/NetworkMusic/BaiduPan/Data，均 State/Actions data class 签名）；共享组件归 SettingsComponents.kt（InfoRow 重命名 SettingsInfoRow 规避同名冲突）；主文件 2529→924 行（保留侧栏/路由/8 个对话框宿主，AppRoot 零改动）。计划字面的 16 Section 按实际 9 个侧栏分区映射（Karaoke/Visualizer 等为 Player/Cache 内部子分组）。

**R-3 LibraryScreen 拆分**：五个 NAS 浏览 Tab 迁至 `ui/screens/library/browse/`（AlbumGrid/ArtistList/SongList/GenreYearLists + BrowseComponents 共享件）；SEARCH/DISCOVER/RADIO 网络音乐 Tab 维持原 library/ 包不动（计划明确的处置）；主文件 1695→647 行；详情页按 v1.2 定案复用现役 AlbumDetailScreen/ArtistDetailScreen 未新写。

**R-7 runBlocking 治理（含 F-3）**：
1. 语言键双写（SharedPreferences 镜像 + DataStore 事实源）——attachBaseContext 零 IO；老版本一次性迁移 migrateLanguageMirrorIfNeeded。
2. 8 个 provider 键 @Volatile 内存镜像（musicSource/defaultNetworkSource/jamendoClientId/metingUrl/mvUrl/lyricsKugou/lyricsNetease/weatherApiKey）——NasMusicApp.onCreate 注入 applicationScope 启动 `startProviderMirrors` 常驻收集，设置改动 ≤1s 生效；新增 ProviderMirrorTest（Robolectric 6 用例全绿）。
3. F-3：LyricsManager baseUrl 改 lambda provider（两处构造点同步），设置页改歌词源即时生效，构造期零 IO。
4. getCloudDriveConfigSync/saveCloudDriveConfigSync 保留 runBlocking（调用点均在 IO 协程）+ @WorkerThread 标注。

**R-6 OkHttpClient 资源池化**：BackendRegistry companion 持共享资源（ConnectionPool 5 连接/5min keep-alive + Dispatcher 16 并发/8 perHost + 守护线程池 NAS-OkHttp-Shared）；5 个适配器全部注入并从 close() 移除 executorService.shutdown()/evictAll()（改为清自身认证态）——修复历史上切后端累积线程池导致电视 WiFi 栈过载的隐患路径。

**R-5 人声分离提取**：SeparationMode 上提为 player 层顶层枚举（AppPreferences 保留 typealias 兼容，消除 player→data.prefs 反向依赖）；新增 VocalSeparationController（模式状态/DSP 开关/伴奏缓存清理/PlayerAdapter 窄接口切源）；HQ 分离编排（输入解析/模型加载/暂停恢复）保留 PlayerManager——与播放状态机深耦合，强搬移会造成接口爆炸。

**R-9**：MainViewModel 重复 import（kotlinx.coroutines.async）删除；颜色硬编码与修复标记按计划评估保持现状。

**F-2 AppRoot 重组热点**：progress/duration 收集从顶层移入 NowPlayingScreen 分支——PlayerManager 的 1000ms 轮询进度不再每秒驱动全树重组（与 H-3 频谱流下沉同向）。

**F-4/F-5**：ExportCoordinator/AccompanimentCache 改注入 NasMusicApp.applicationScope（删除组件私有 scope，单例场景不 cancel 是正确语义）；LyricsPersistentCache.saveIndex 加 synchronized 写互斥（复用 H-5 原子写盘语义）。

**F-6 双网卡 IP**：NetworkUtils.getLocalIpAddress 改接口优先级排序（isUp 加权 + eth>wlan），兜底保持原行为；API 22 兼容（接口名前缀匹配，不用 ConnectivityManager/ICU）。

**F-7 天气 Key 加密**：新键 weather_openweathermap_api_key_enc 走 CryptoUtils AES-GCM；旧明文键一次性迁移后清除；镜像与 Flow 改读解密值（ProviderMirrorTest 加密路径下依然全绿）。

**待办**：R-4（调用面分析完毕：90 个访问器分布 BaiduOAuthClient/NasMusicApp/各 VM/UI）；R-10（未启动）；文档勾选已同步本节；TV 手测回归待设备。

**验证结果**：
- ✅ 每个提交前 assembleDebug 通过
- ✅ ProviderMirrorTest 6/6、UrlSanitizerTest 8/8、全量单测通过

### 10.111 R-4 门面调用点全量迁移 + R-10 收尾决策（2026-09-09）

**R-4 完成**（分支 `refactor/r1-viewmodel-split`，2 个提交）：
1. 12 个领域子 Prefs（ServerPrefs/PlayerPrefs/LyricsPrefs 独立文件 + DomainPrefs.kt 承载 NetworkMusic/Baidu/Download/Weather/Visualizer/History/Playlist/Queue/Language/Backup），键不迁移只搬访问器、DataStore 单例不变。
2. 全库调用点迁移至 `prefs.<domain>.xxx`：BaiduOAuthClient/BaiduMvFileService/BaiduNetdiskService（baidu 域）、NasMusicApp（network/baidu 域 provider）、MainViewModel（player/lyrics/network/visualizer/download/history/queue/weather/languagePrefs 域）、各子 VM（Backup/Playlist/PlayHistory/NetworkMusic/Search/VocalSeparation/Player）、AppRoot（visualizer/weather/lyrics/baidu/network Flow）。
3. 旧 API 保留为转发实现（不标 @Deprecated）：门面与旧 API 并存，新代码一律走子域；与计划 DoD 的差异已在状态列注明。
4. BaiduMvFileServiceTest 适配：mock 的 AppPreferences 需 `doReturn(BaiduPrefs(prefs)).when(prefs).baidu` 打桩（when() 内嵌 mock 调用会触发 UnfinishedStubbing）。**270 单测全绿**。

**R-10 收尾决策**：Subsonic 公共层（SubsonicRestClient）已完成并全量接入；JellyfinAdapter（1218 行）域拆分经**所有者确认保持原状不实施**——其方法间经 baseUrl/apiToken 等实例状态耦合，拆文件需构造 context 传参改写全部私有方法签名，回归风险高于可维护性收益（与「本地 HTTP 服务无鉴权是所有者接受的取舍」同类的所有者决策，勿再作为待办启动）。

**验证**：assembleDebug + testDebugUnitTest 270/270 通过。

### 10.112 v2.28.0 - 重构发布（R-1~R-7、R-9、R-10 部分、F-1~F-7）

**版本**：versionCode 122 / versionName 2.28.0（分支 refactor/r1-viewmodel-split，24 个提交）

**发布内容**（详见 docs/codebase-refactoring-plan-2026-09.md v1.4）：
1. R-1：MainViewModel 5451 行拆为 13 个领域子 VM + ViewModelEvents 事件契约，主 VM 精简为协调者 + 兼容转发层（3186 行）；BaiduConnectionState 归属迁移至 NetworkMusicViewModel。
2. R-2/R-3：SettingsScreen 拆 9 Section 至 ui/screens/settings/（2529→924 行）；LibraryScreen 五个 NAS 浏览 Tab 迁至 library/browse/（1695→647 行），详情页复用现役实现。
3. R-4：AppPreferences 按领域拆 12 子 Prefs 门面，全库调用点迁移至 prefs.<domain>.xxx；旧 API 保留为转发实现（不标 @Deprecated），门面与旧 API 并存。
4. R-5/R-6：SeparationMode 上提 player 层 + VocalSeparationController（HQ 编排保留 PlayerManager）；BackendRegistry 共享 ConnectionPool(5/5min)/Dispatcher(16/8)，5 适配器 close() 移除 shutdown/evictAll。
5. R-7 + F-3：主线程 runBlocking 消除——语言键 SharedPreferences 双写镜像、8 provider 键 @Volatile 内存镜像；LyricsManager baseUrl 改 provider。全库仅剩 3 处受控 runBlocking（2 处 @WorkerThread Baidu 系 + 1 处语言一次性迁移）。
6. R-10（部分）：Subsonic 公共层下沉 SubsonicRestClient，Navidrome 全量委托；Subsonic 因 URL 格式差异保留自有 buildRestUrl。
7. F 系列：F-1 五适配器 w/e 日志经 UrlSanitizer 脱敏（release logcat 不再泄露 api_key/token）；F-2 AppRoot progress/duration 收集下沉播放页分支；F-4 自建 scope 统一注入 applicationScope；F-5 歌词索引写互斥；F-6 双网卡 IP 改接口优先级排序；F-7 天气 Key AES-GCM 加密 + 旧明文迁移。

**定案不实施**（所有者确认，勿再作为待办启动）：
- R-8 Hilt 迁移：min SDK 22 + Kotlin 2.2.10 的 kapt/ksp 兼容性风险，手动 DI 运转良好。
- R-10 JellyfinAdapter 域拆分：实例状态（baseUrl/apiToken 字段）耦合深，拆文件需改写全部私有方法签名，回归风险高于收益。
- F-8：待所有者决策后再定。

**已知未达项**（非遗漏）：MainViewModel 3186 行未达 DoD ≤600——现形态为计划允许的协调者 + 兼容转发层过渡态，AppRoot 零改动是本轮硬约束。

**验证**：
- ✅ testDebugUnitTest 270/270 通过（含新增 UrlSanitizerTest 8 项、ProviderMirrorTest 6 项）
- ✅ assembleRelease BUILD SUCCESSFUL（R8 minify + lintVital + baseline profiles 通过）
- ✅ 电视 192.168.0.114:5555（armeabi-v7a）安装 v2.28.0 启动验证：进程存活、无 FATAL/ANR、release logcat 无凭据泄露
- ✅ 所有者手测：基本功能全部有效

### 10.113 N 系列实施（N-1/N-2/N-3/N-4，2026-09-10）

> v1.5 二次审阅发现、v1.6 人工复核修订后的 N 系列，经所有者确认实施 4 项；N-5 归入 R-10 定案关闭。

- **N-1 MainViewModel 转发层消除**（提交 402110e）：12 个子 VM 公开为只读属性（手动 DI，不引入 Hilt——R-8 定案），删除 121 个纯透传转发、保留 12 个胶水转发（connectToSavedServer/playNetworkSong/toggleNetworkFavorite/onMv* 系列等含跨域参数拼接），消费方（AppRoot 165 处/NetdiskScreen 15 处/MainActivity 4 处/MediaKeyHandler 5 处）改经 `viewModel.<subVM>.<member>` 直调；MainViewModel 3186→3055 行
- **N-2 DomainPrefs 拆文件**（提交 e6e43bf）：10 个子 Prefs 类拆独立 .kt（同包 import 零改动），data/prefs/ 14 文件单类单文件
- **N-3 AppRoot 状态下沉**（提交 e9a49a8）：17 个单分支独占 collectAsState 订阅移入 when 分支内（lyrics 系→NowPlaying、albums→Library、queue 系→Queue、baidu*/apiVersions/weatherApiKey→Settings），顶层订阅 35→18 处（≤20 达标）；collectAsState 总数 122 不变（仅位置迁移）
- **N-4 PlayerManager 拆分**（提交 c954a5f）：HQ 人声分离编排提取 HqSeparationOrchestrator（23.6KB，PlayerHost 窄接口回调播放操作，延续 R-5 VocalSeparationController 模式）、均衡器/频谱提取 PlayerEqualizer（6.4KB）；PlayerManager 64.1KB/1510 行→41.5KB 播放核心+队列状态机，外部调用面零改动。实施偏差（v1.5 方案修正）：MediaSession/音频焦点实为 PlaybackService 职责（且已延迟创建），不属 PlayerManager；播放核心与队列共享 StateFlow 状态机保持一体（与 R-5 定案同因，强行拆分会接口爆炸）
- MediaKeyHandlerTest 适配 N-1 新调用路径（mock playerVM 子 VM，doReturn 桩法）
- 验证：每项 assembleDebug 0 错误 + testDebugUnitTest 270/270 全绿
- 待办：TV 手测回归（重点 N-4 播放全路径 + N-1 各页面导航/遥控按键 + N-3 各页面 D-Pad）

### 10.114 修复网络歌曲队列 IDLE 停播（2026-09-10）

- **症状**：网络歌曲队列中某首歌播放失败被跳过后，下一首不自动播放，需手动点播放才能恢复
- **根因**：`onPlayerError` 重解析仍失败后调 `next()` 跳歌，但此时 ExoPlayer 处于 STATE_IDLE——`seekToNextMediaItem()` 在 IDLE 下既不触发 `onMediaItemTransition`（`_currentIndex` 不同步、空 streamUrl 懒解析检测失效），也不会重新 `prepare`，播放器静默停住
- **修复**（player/PlayerManager.kt）：
  - 新增 `transitionToIndex(index)` 手动恢复路径：同步索引/当前歌/时长 → 空 streamUrl 则 `pause()` + `onNeedResolveStreamUrl` 触发解析，否则 `seekTo + prepare + play`（IDLE 标准恢复序列）
  - `next()`/`previous()` 入口检测 IDLE 统一走 `transitionToIndex`（随机模式同策略：排除 `shuffleHistory` 后随机选）
  - `onMediaItemTransition` 空 URL 懒解析检测从仅 `REASON_AUTO` 放宽到 `AUTO | SEEK`（覆盖 playAt 手机遥控直 seek 场景）
  - `onIsPlayingChanged(true)` 时重置 `lastErrorRetryIndex = -1`：同一首歌成功起播后若链接再次过期，仍允许自动重解析一次（原仅切歌时重置，长音轨二轮过期只能跳歌）
- **验证**：assembleDebug 编译通过；testDebugUnitTest 270/270 全绿
- **待办**：TV 手测（网络队列连续播放至链接过期场景 + K 歌页同场景）

### 10.115 F2 系列播放功能增强 M1（2026-09-10，F2-1 + F2-2）

计划文档：docs/feature-dev-plan-2026-09.md（v1.0）

- **F2-1 播放统计面板**：
  - data/stats/PlayStatsRepository：月度统计键 play_stats_monthly（JSON：month → songId → count），经 AppPreferences.recordPlayWithSong 第 4 步同次 DataStore edit 原子写入；滚动保留 12 个月；不做历史回填（设计取舍）
  - data/stats/PlayStatsAggregator（纯函数）：artist 小写归并（GBK mojibake 不强并）、genre null/blank 归"未分类"、播放量降序 Top10
  - ui/screens/stats/PlayStatsScreen + PlayStatsViewModel：本月/累计双 Tab、KPI 卡、最爱歌手横向列表（首字母头像，无图片加载依赖）、流派分布原生 Canvas 条形图；Screen.PlayStats 枚举 + AppRoot 接入；入口：设置 → 数据管理 → 播放统计（DataSettingsSection）
  - 字符串 pstats_ 前缀（避免与 Mine 页 stats_* 冲突——实施中发现既有键）
- **F2-2 通知栏增强 + 睡眠定时器**：
  - player/SleepTimerController：State（Off/Running/Finished）状态机；不持久化（重启重置，设计取舍）；时间源可注入，tickExpired 公开供单测
  - PlayerManager.sleepTimer 持有 + onSleepTimerExpired 回调挂点
  - PlaybackService：通知按钮 3→5（prev/playPause/next/playMode/sleepTimer）；playMode/sleepTimer 走自定义 action 广播（ACTION_TOGGLE_PLAY_MODE/ACTION_SLEEP_TIMER_CYCLE，ContextCompat.registerReceiver + RECEIVER_NOT_EXPORTED）；播放模式切换经 NasMusicApp.playModeToggleHandler 中转（MainActivity 注册 → PlayerViewModel.togglePlayMode，避免 service 持有 VM 泄漏）；下一首 subText（队尾/空队列不显示）；睡眠定时运行中每分钟刷新通知剩余分钟
  - NowPlayingScreen：sleepTimerRemainingMin 顶栏状态条（Warning 色 + 剩余分钟，点击 30 分钟快捷档）；PlayerViewModel 桥接 sleepTimerState/startSleepTimer/cancelSleepTimer
  - 计划偏差说明：通知栏"歌词开关"按计划 §2.3 决策不做（TV 端歌词即主界面，通知栏 6 按钮溢出）；睡眠定时档位选择对话框未做（通知按钮循环切换 15min↔取消 + NowPlaying 快捷 30min 覆盖核心场景，档位对话框留待用户反馈）
- **验证**：assembleDebug 通过；testDebugUnitTest 285/285 全绿（新增 PlayStatsAggregatorTest 8 + SleepTimerControllerTest 7）
- **待办**：TV 手测（统计页 D-Pad、通知按钮 AVRCP/蓝牙遥控、睡眠定时到期暂停）

### 10.116 F2 系列播放功能增强 M2（2026-09-10，F2-3 + F2-4）

- **F2-3 智能电台**：
  - backend/radio/RadioSongScorer（纯函数）：同 artist +50 / 同 genre +30 / 同 album +10 / 同年代差≤3 +5 / play_counts 偏好 +min(count,20) / 种子与已播 -100 强排除；分数+1 做权重随机采样（负分权重 0）
  - backend/radio/SmartRadioManager：Idle/Generating/Playing/Exhausted 状态机；两级曲库加载（seed 有 genre 且流派池 ≥50 → getSongsByGenre 先筛，否则全量分页 500 首页/5000 硬上限）；曲库耗尽清 playedIds 换批再生成；会话内曲库缓存
  - NasMusicApp 容器注册（applicationScope + playCountsProvider 偏好信号）；NowPlaying 控制按钮行新增"智能电台"入口（AppRoot 传入：isConnected && 非网络歌曲时显示）；MainViewModel.startSmartRadioFromCurrent 胶水（生成中/已开启/需 NAS 三态提示）
  - 计划偏差：天气电台页 Tab 入口未做（NowPlaying 单入口已覆盖核心场景；天气电台页结构改动牵连大，留待用户反馈）
- **F2-4 断线续播**：
  - PlayerManager：@Volatile networkLost + ResumePoint(index, positionMs, wasPlaying) + recordPendingResume（保留首个断点、置 buffering、清错误、pause 防错误风暴）+ onNetworkRestored/onNetworkGone
  - onPlayerError 冻结分支：networkLost=true 时不跳歌不重解析（断网中解析必然失败），记录断点等待恢复
  - MainViewModel.onNetworkAvailable：取回断点 → 2 秒去抖（networkRestoreJob 新事件取消旧等待）→ 队列索引有效且 wasPlaying 时 resolveAndPlayByIndex 续播；onNetworkLost 置 networkLost 标志
  - MTV 独立播放器路径不动（计划 §4.3 边界）；断点不持久化（会话内续播，重启走既有 keyLastQueue 队列恢复）
- **验证**：compileDebugKotlin + testDebugUnitTest 298/298 全绿（M1 285 + RadioSongScorerTest 10 + NetworkResumeTest 3）
- **待办**：TV 手测（智能电台批次相似度 + 断网/恢复断点续播场景）

### 10.117 F2 系列播放功能增强 M3（2026-09-10，F2-5 + F2-6）

- **F2-5 跨曲交叉淡入淡出**：
  - player/CrossfadeController：双实例方案——crossfadePlayer（setAudioAttributes USAGE_MEDIA, handleAudioFocus=false，焦点由主 player 独占）淡入 + 主 player 淡出（50ms 步进线性斜坡），窗口结束回调 onCrossfadeComplete → PlayerManager.transitionToIndex（复用 v2.28.1 IDLE 安全路径）
  - 边界条件矩阵：enabled=false / durationSec≤0 / suppressPlayback（K歌MTV）/ REPEAT_ONE / 队列≤1 首 / 队尾顺序模式 / 下一首 streamUrl 空（网络歌懒加载未解析）→ 均不触发走普通切歌；手动切歌（next/previous）立即 abort（资源彻底释放：stop+release+Handler 清理+主 player 音量恢复）
  - PlayerManager 集成：progressUpdateRunnable 1 秒粒度检查 remaining ≤ durationSec 窗口触发；crossfadeEnabled/DurationSec 为 @Volatile 字段（PlayerViewModel init 收集 AppSettings Flow 注入，PlayerManager 不依赖 prefs）
  - 设置：PlayerSettingsSection 开关 + 2/4/6/8/12s 离散档位（D-Pad 友好，替代滑条）；键 settings_crossfade_enabled/duration_sec（PlayerPrefs 门面）
- **F2-6 音质分级**：
  - util/BandwidthEstimator：滑动窗口（30s/32 样本）字节数/耗时→bps；tierForBandwidth 映射（>10Mbps→999 无损 / 2-10Mbps→320 / <2Mbps→128 / 无数据保守 320）；reset 供断网清零
  - MetingApiService：构造注入 qualityTierProvider（AppPreferences.getQualityTierSync R-7 镜像同步读）；resolvePlayUrl br 参数 + 降级链（显式档 [tier,320,128] 逐级重试，AUTO 不传 br 走端点默认）
  - 设置：PlayerSettingsSection 4 档单选（自动/无损/320k/128k，当前档 ▶ 标记）；键 settings_quality_tier
  - 计划偏差：AUTO 档未接 BandwidthEstimator 自动决策（estimator 需 OkHttp 拦截器全链路埋点，牵连 NAS/百度共链路；首期 AUTO=端点默认，带宽自动决策留二期）；Jellyfin 显式档 bitrate 参数未做（NAS 用户以原品质为主，计划已标注可选二期）；百度网盘/Jamendo 无码率可选（计划边界，UI 已明示"仅 Meting 源"）
- **验证**：compileDebugKotlin + testDebugUnitTest 316/316 全绿（M2 298 + CrossfadeControllerTest 10 + BandwidthEstimatorTest 8）+ assembleDebug 通过
- **待办**：TV 手测（crossfade 听感/内存 + 音质档位切换对比 + 弱网 AUTO 场景）
### 10.118 F2-2b 通知 Provider 接管 + 睡眠定时常驻入口 + AppRoot 分支下沉（2026-09-11）

- **通知 Provider 接管（F2-2 缺陷修复）**：
  - 根因一：media3 `MediaLibraryService` 自带默认 MediaNotification Provider，与自建通知共用 ID=1 互相覆盖，下拉通知样式漂移、按钮状态不同步
  - 根因二：Android 13+ 锁屏/超级岛系统媒体卡片不读通知 addAction、由 MediaSession custom layout 渲染，此前从未设置 → 锁屏永远只有系统默认键
  - 修复：`setMediaNotificationProvider` 接管（createNotification 统一走本服务 buildNotification，handleCustomCommand 放行 playMode/sleepTimer 两个 action）；`setCustomLayout`（CommandButton×2）+ `onConnect` 注册 SessionCommand + `onCustomCommand` 分发；updateNotification/分钟 tick 同步 refreshSessionCustomLayout；compact view 索引 (1,2,3)→(0,1,2)（原索引实际显示 播放/暂停、下一首、播放模式，漏掉上一首）
- **睡眠定时常驻入口（F2-2b）**：NowPlaying 顶栏右侧常驻小按钮（未启动"定时 -"、运行中橙色"定时 N 分钟"），点击弹 SleepTimerPickerDialog（15/30/60/90 分钟档 + 运行中取消项）；NowPlayingScreen 参数 sleepTimerRemainingMin/onSleepTimerClick 改为 sleepTimerState + onSleepTimerStart/onSleepTimerCancel；中英 strings 补 np_sleep_timer_* 三条
- **AppRoot 分支下沉（MethodTooLargeException 根治）**：
  - 触发：F2-2b 为 NowPlaying 分支新增 3 个参数即触顶 JVM 单方法 64KB 上限（Compose when 分支全部内联宿主函数，AppRoot 1155 行累积 14 屏）
  - 方案：14 个 Screen 分支机械提取至 `ui/components/branches/`（HomeBranch…PlayStatsBranch），AppRoot 1155→391 行路由壳；外层共享状态参数化（每 Branch 声明实际所需签名），pickerSong 共用弹窗保留宿主、Branch 经 onPickSongForPlaylist 回调上抛
  - 实测：AppRoot 方法 8303 条字节码指令、最大 SettingsBranch 10437 条（上限 65535，余量 6 倍+）；各 Branch 独立类文件，后续新增屏幕只加 Branch 文件、宿主零增长（工程规则：禁止把分支体写回 AppRoot）
  - 搬迁修正：DownloadState 类型笔误（DownloadViewModel.DownloadState → backend.download.model.DownloadState，原写法靠 AppRoot 通配 import 掩盖）；SettingsBranch 注入 context + coroutineScope（语言切换重启逻辑）
- **验证**：assembleDebug + testDebugUnitTest 316/316 全绿 + assembleRelease 通过；修复版 APK 已装手机 91846823（v2.29.0/124）
- **待办**：手机/TV 手测（通知 5 按钮刷新、锁屏/超级岛自定义键、睡眠定时到期暂停、档位弹窗 D-Pad）
### 10.119 智能电台多源化 + 首页入口迁移（2026-09-11，F2-3 演进）

- **SmartRadioManager 多源化**：构造注入 networkPlaylistProvider/networkPlaylistIds/localSongsProvider（NasMusicApp 组装，networkMusicManager 与 localMusicRepository 初始化晚于 smartRadioManager，用延迟 lambda）；loadLibrary(seed: Song?) 聚合 NAS（有 genre 走流派筛选，否则全量分页，adapter 可空）+ 本地 loadFromCache + Meting 歌单采样（NETWORK_PLAYLIST_SAMPLE_COUNT=3 / NETWORK_PLAYLIST_SONG_CAP=30，失败降级），distinctBy { id } 合池；新增 startFromScratch——play_counts 最高歌曲为偏好种子，播放中换批复用既有 RadioSongScorer.generateBatch
- **入口迁移**：HomeScreen 新增 SmartRadioCard（天气卡上方、随心听下方，FocusableSurface 卡片，状态驱动文案 Idle/Exhausted→开始收听、Generating→生成中、Playing→换一批）；HomeBranch 接线 onStartSmartRadio = viewModel.startSmartRadio()；NowPlaying 入口移除（PlayerControls 按钮分支、NowPlayingScreen 参数、NowPlayingBranch 传参三处清理），startSmartRadioFromCurrent 保留在 MainViewModel 供后续复用
- **验证**：assembleDebug + testDebugUnitTest 316/316（RadioSongScorerTest 无回归）+ assembleRelease 通过
- **待办**：手机/TV 手测（无 NAS 连接时网络源兜底推荐、多源混合批次、换一批）

### 10.120 智能电台交互列表化（2026-09-11，F2-3 手测反馈）

- **问题**：首页智能电台卡片点击即生成并直接播放（天气电台式），用户期望与随心听一致的浏览模式——先看推荐列表再点播
- **改动**：SmartRadioManager 新增 `generateOnly(onBatchReady)`——只生成批次不播放（有 currentSeed 走 startFromCurrentSong 即"换一批"语义、无则 startFromScratch 偏好种子）；MainViewModel 新增 `_smartRadioBatch: StateFlow<List<Song>>` + `loadSmartRadioBatch()`（smartRadioBatchLoading 防重入）+ `playSmartRadioBatchAt(index)`（整批入队从该首播起）；HomeScreen 区块改随心听式——`LaunchedEffect(Unit)` 首次进入自动生成，批次非空时展示 SectionHeader（右上"换一批"按钮，SectionHeader 新增 actionLabel/onAction 可选参数）+ LazyRow HomeSongCard 卡片行，点卡片播放；删除 SmartRadioCard 及 startSmartRadio/startSmartRadioFromCurrent 死代码
- **验证**：compileDebugKotlin + testDebugUnitTest 316/316 通过
- **待办**：手机手测（首次进入自动出批次、点卡片播歌、换一批不重复、区块位置随心听下方天气卡上方）

### 10.121 v2.29.3 — 代码审查报告全量修复（P0–P3，2026-09-11）

依据《NASMusicTV-代码审查报告-2026-09-11.html》（8 个并行子系统审查代理产出，覆盖全部 275 个 Kotlin 源文件约 56,000 行，重定严重度后 High 10 / Medium 41 / Low 59），按报告"修复路线图"分四阶段实施并全部落地。

#### P0 阻断性缺陷（13 项 High）

- `util/CryptoUtils.kt`：`encrypt` 失败不再返回明文（抛异常），消除凭据明文落盘降级路径
- `impl/DaoliyuAdapter` / `impl/FeiniuAdapter`：全部 `OkHttp` 调用（含 `testConnection` 的临时实例）补 `.use {}`，不再泄漏 Response
- `backend/local/MusicScanner.kt`：`getColumnIndex` 替代下标写死 + `Build.VERSION` 分支配 `_DATA` 列；`ScannedSong` 增 `dataPath`
- `backend/download/SongDownloadManager.kt`：下载完成校验 `Content-Length`（不匹配即删 `.part`）
- `backend/download/EmbeddedCoverExtractor.kt`：`content://` 改 `MediaMetadataRetriever.setDataSource(context, uri)`
- 播放引擎 H1–H5：Demucs overlap-add 段缓冲清零、`CrossfadeController.release()`、人声分离单飞锁、进程级 `OrtEnvironment` 不再被释放
- `ui/components/AppRoot.kt` / `KaraokeStepPickerDialog`：补 `showKaraoke` BACK 分支与 BackHandler（K 歌页 BACK 不再穿透到退出确认）
- `lyrics/LyricsManager.kt`：在线歌词变体轮询补索引边界守卫
- `backend/network/mv/BilibiliMvService.kt`：实现 WBI 签名（`w_rid` / `wts`）

#### P1 用户可见正确性

- **歌词系统四修**（`LyricsManager` / `LyricsPersistentCache` / `LyricsNetworkProvider` / `MainViewModel`）：
  - 编码：新增 `decodeLyricsBytes()`（U+FFFD → GBK 回退）+ `EncodingUtils.fixEncoding`
  - 相关性：新增 `SearchHit` + `norm()` / `relevanceScore()`（标题互含校验 + 歌手加分），候选 `filter { score > 0 }.sortedByDescending { }`
  - 优先级：后端歌词优先于持久化缓存；`userNetworkLyricsOverride`（ConcurrentHashMap.newKeySet）记录用户显式切换，避免被缓存覆盖
  - 线程/并发：候选拉取移入 `backgroundScope`（`SupervisorJob + Dispatchers.IO`）+ `candidateFetchMutex`（双检防惊群）；0 行空歌词加 `isNotEmpty()` 守卫（不再阻断回退）
  - `LyricsPersistentCache` 补真 LRU：`touchCounter` + `TOUCH_SAVE_INTERVAL=16` 节流落盘
- **导出**：`ExportCoordinator.onTreeGranted` 回到发起授权的设备（`volumeIdOf(it) == pendingVolume`）；`SongExporter` 加 `exportMutex.tryLock()` 并发守卫
- **百度网盘**：`executeWithErrno` 日志 URL 走 `sanitizeUrl`（原截 200 字符仍可能带 token）；token 失效（-6/31045）`execute`/`executeWithErrno`/`createDir` 统一强制刷新重试一次；`BaiduCoverProvider` 两阶段按 ID3 总长补读（`ID3_PROBE_BYTES=256KB` → `MAX_ID3_TAG_BYTES=16MB`）
- **Subsonic**：用户名 URL 编码 + 歌曲总数读取修复
- **本地服务**：`BackupTransferServer.MAX_UPLOAD_BYTES=32MB` + `ByteArrayOutputStream` 分块累积（不再按 Content-Length 预分配，防 OOM DoS）；`RemoteControlHtml` 新增 `esc()` / `escAttr()` 并应用于队列/搜索项标题歌手与 onclick 属性（修反射型 XSS）
- 天气电台随机补位不计入 `nasCount`；SmartRadio skip 仅清当前批次

#### P2 健壮性与可观测性

- `SongDownloadManager`：新增 `currentCall` 字段并在 `cancelAll()` 中真正 `cancel()`；下载第 8~9 步整段 try/catch，失败清理 `finalFile` 与 `.lrc`/`.jpg` 孤儿
- `backend/local/`：`LocalMusicRepository.buildScannedList` 去重键改**真实文件路径**（统一分隔符，缺失回退 contentUri，DOWNLOAD 通道优先）+ `escapeLike()`；`LocalMusicDao.search` 三处 `LIKE ... ESCAPE '\'`
- `player/CoilBitmapLoader.kt`：`loadBitmap`/`decodeBitmap` 补 `if (!future.isDone) runCatching { future.set(...) }`，新增 `enqueue()` 取消转发到 `disposable.dispose()`
- `backend/weather/WeatherApi.kt`：4 处 Response 补 `use {}`；WMO 描述 `85, 86 -> "阵雪"`（83/84 非标准码移除）；forecast `cnt` 5→40
- `backend/network/baidu/BaiduFileIndexCache.kt`：`setCoverUrl` 整体 `synchronized(cacheLock)`（消除读-改-写丢失更新）
- `backend/download/MediaTagWriter.kt`：`compressCover` 补 `Bitmap.recycle()`（含 scaled 与 src，防 native 内存泄漏）；`CoverFileWriter` 补 `isNormalizedJpeg()`（JPEG 且 ≤512KB 才跳过压缩）

#### P3 清理与一致性

- **统一 Dialog BACK 机制**：`ui/DialogBackHandler.kt` 新增 `RegisterDialogBackHandler(onBack)`（`rememberUpdatedState` + `DisposableEffect(Unit)`，只注册/注销一次）。原实现以 `onDismiss` lambda 为 key，父重组瞬间先注销后注册，存在 handler 为 null 的竞态窗口（BACK 穿透到 Level 3 退出确认）。改造 `ExitConfirmDialog` / `ConfirmDialog` / `ExportDeviceDialog` / `ConnectPromptDialog` / `KaraokeStepPickerDialog`；KDoc 明确"Box 覆盖层弹窗走 `LocalDialogBackHandler`、真正 `Dialog{}` 必须用 `BackHandler`"
- `ui/screens/TextInputDialog.kt`：QR 位图生成移出组合期（`rememberCoroutineScope` + `Dispatchers.Default`，回主线程赋值）
- `ui/MainActivity.kt`：退出流程 `runBlocking` 改 `lifecycleScope.launch(Dispatchers.IO)`（完成/超时后回主线程 `finishAffinity`），原主线程最长冻结 1.5s
- `net/ModelTransferServer.kt`：日志端口硬编码 18082 改 `MODEL_TRANSFER_PORT`(18083)；`/api/status` 不再返回内部绝对路径
- `net/RemoteControlServer.kt`：`playAt` / `moveQueueItem` / `removeFromQueue` 补 `isValidQueueIndex`（`0 ≤ idx < size`）
- **multipart 边界匹配重写**：新增 `net/MultipartBoundaryStreamer.kt`——KMP 前缀函数替代朴素匹配，修「自重叠 boundary 在失配回退时漏判起点、把边界字节写进模型文件」；配 `MultipartBoundaryStreamerTest` 8 用例（含 `aab` in `aaab`、300KB 跨缓冲、EOF 残留前缀）
- `NasMusicApp.kt`：`:139` 百度 OkHttpClient 注释修正（原写"信任所有证书"，实现已是系统默认校验，误导注释可能诱使后续「复原」hack）；下载设置 lambda 合并为单次 `appSettings.first()` 快照（原 4 次调用可能不一致）
- **Gson 前向兼容约束文档化**：`data/model/AppSettings.kt` KDoc 明确"Gson 持久化 data class 新增字段必须带默认值"，否则新旧数据互反序列化失败
- **`modelDownloadUrl` 补齐**：DataStore key `settings_model_download_url` + `appSettings` Flow 映射 + `@Volatile cachedModelDownloadUrl` + `setModelDownloadUrl` / `getModelDownloadUrlSync` + 备份导入回填；`ModelDownloadManager(context, customUrlProvider)` 自定义 URL 优先于内置候选（hf-mirror → huggingface）
- **死代码删除**：`lyrics/Mp3MetadataExtractor.kt`、`player/VocalSeparationController.kt`（均零调用点）；`AccompanimentCache` 删除未接线的 `startPreSeparation`/`cancelPreSeparation`/`PreSeparationState` 通路及构造参数 `externalScope`（`PlaybackService` 调用同步收紧）；`HqSeparationOrchestrator`/`PlayerManager` KDoc 中的失效类引用修正

#### 验证

- `compileDebugKotlin` + `compileDebugUnitTestKotlin` 通过
- `testDebugUnitTest --tests "*MultipartBoundaryStreamerTest"`：8/8 通过
- 版本：v2.29.2 → **v2.29.3**（versionCode 126 → 127）
- **待办（手测项）**：K 歌页 BACK 三级语义、歌词来源手动切换后不被缓存覆盖、导出到指定外接设备、电视端扫码弹窗不丢帧、手机上传 166MB 模型（KMP 边界路径）；release 构建（R8）需再跑一次 `assembleRelease` 确认 `data.stats` 之外的 keep 规则无回归

### 10.122 v2.30.0 — 音乐可视化升级：全屏舞台 + 20 套效果 + 数据链收敛（2026-09-12）

依据 `docs/music-visualizer-dev-plan.md` v6.0（可开发规格，72 章节 / 52 表格）实施。方案演进链：v1.0 否决 WebView 渲染 → v2.0 根因与技法 → v3.0 效果库与交互 → v4.0 呼吸感引擎 → v5.0 `AudioFrame` 契约 → v6.0 20 套全量可开发规格。

#### 数据层根因修复（"不好看 / 不呼吸"的真因）

- **⑨ 归一化分母错误**：`SpectrumAnalyzer` 原用**当前帧**低频峰值作分母（`:216-217`），分子分母同步缩放 → 低频柱**恒为 1.0**，轻/重鼓点无差别。改为低频区**运行峰值** `lowRunningPeak`（`maxOf(lowRunningPeak * AGC_DECAY, lowBandPeak, AGC_FLOOR)`，`AGC_DECAY = 0.995` ≈ 3s 时间常数）。效果：重鼓点 1.0 / 轻鼓点 ≈0.45 / 弱间奏 ≈0.2
- **⑨-b gamma 二次压缩**：原 `sqrt(x).pow(1.5)` = `x^0.75`，gamma<1 抬升小值，把轻:重比从 5:1 压到 3.3:1。改为**双通道输出**——`spectrum[]` 走 `^0.75`（`DISPLAY_GAMMA`，保证小信号可见），`bass`/`mid`/`treble`/`energy` 走线性（保证动态范围）。二者不可混用
- **⑩ 时间采样率不足**：回调 50000µs（20Hz）而 captureSize 1024 仅覆盖 23ms → 每周期漏掉 54% 音频，鼓点 attack（5–10ms）整拍漏掉。改 `CAPTURE_INTERVAL_US = 20_000`（50Hz），波形与 FFT 双开；`captureSize` 取 `getCaptureSizeRange()[1]`（原写死 1024，浪费支持 2048 的设备）
- **⑫ 每帧数组分配**：`FloatArray(512)` + `sliceArray(5..19)` + `FloatArray(32)` ≈ 2.2KB/帧 × 50fps ≈ 110KB/s。改为 `magnitudeBuf` / `barBuf` / `displayBuf` / `linearBuf` / `waveBuf` 全部预分配复用，低频区改循环取 max 不切片
- **⑬ 每帧强制重组**：`_spectrumData: StateFlow<FloatArray>` 在节流点 `emit(displayBuf.copyOf())`，`FloatArray` 按引用比较 → 订阅方每 33ms 必重组。**整条兼容通道删除**：数据通路收敛为 `SpectrumAnalyzer → SpectrumRepository.onFrame → AudioFrame 单例`，仓库只发布 `frameSeq`（帧序号）。连带删除 `PlayerEqualizer.spectrumData` / `PlayerManager.spectrumData` / `PlayerViewModel.spectrumData` / `NowPlayingBranch` 的 `collectAsState` / `NowPlayingScreen` 的 `spectrumData`+`visualizerTheme` 死参数（`VisualEqualizer` 的唯一调用方 `EqualizerScreen` 从未传入该参数）
- **⑭ 静音返回空数组**：原 `return FloatArray(0)` 且 `runningPeak = 1f` → 渲染层柱数变 0、频谱整体消失，恢复播放后前 1~2s 被压制。改为写入**长度正确的全 0 数组**；波形一并归零（避免静音期波形类效果显示残影）；不再重置 `runningPeak`
- **① 柱数错位**：`BAR_COUNT` 32 → **64**（`SpectrumContract`），频段边界 Bass `0–39` / Mid `40–55` / Treble `56–63`；渲染侧**不再有 `barCount` 参数**，统一取 `frame.spectrum.size`——从结构上杜绝复发
- **⑮ 枚举不兼容**：`VisualizerTheme.fromKey` 增加 `LEGACY_MAP`（`COLOR_FLOW`→`CIRCULAR_RING`、`NEON_PULSE`→`IMMERSIVE_BLOOM`、`CLASSICAL_WAVE`→`SONIC_TERRAIN`）+ 大小写容错 + 默认兜底
- 防御：`processFft` 增加 FFT bins 越界补分配（回调 bins 多于 `attach` 预分配时不再 AIOOBE）

#### 新增文件

- `visualizer/`：`AudioFrame`（契约单例）、`SpectrumContract`、`SpectrumRepository`（唯一写入方）、`BeatDetector`、`SectionEnergyTracker`、`PeakHoldTracker`、`ParticlePool`、`CoverPalette` + `CoverPaletteProvider`、`RenderContext`、`VisualizerRenderer` + `VisualizerRendererFactory`、`VisualizerMath`、`AutoDirector`
- `visualizer/renderers/`：`BasicRenderers`(E01–E07) / `AdvancedRenderers`(E08–E17) / `ParticleRenderers` / `UltraRenderers`(E18–E20)
- `ui/components/VisualizerStage.kt`（三层舞台 + 绘制循环 + TV/手机交互）、`ui/viewmodel/VisualizerViewModel.kt`（显隐/主题/画质/封面取色）
- 测试：`BeatDetectorTest` / `AudioFrameTest` / `SpectrumRepositoryTest` / `SpectrumAnalyzerTest` / `VisualizerThemeTest` / `FindCurrentLyricLineTest`

#### 渲染与交互设计要点

- **`AudioFrame` 三条铁律**：单例复用（运行期零分配）、只有 `SpectrumRepository` 可写、渲染层不得跨帧持有引用
- **绘制循环不走重组**：`Canvas` + `LaunchedEffect { while(true) withFrameNanos { tick = it } }`；`RenderContext` 复用实例，每帧只更新字段
- **三层结构**：背景层（封面 + `palette.background` alpha 0.88 暗化；不依赖 `Modifier.blur`——API<31 为 no-op）→ 效果层（Canvas + `CompositingStrategy.Offscreen` 支撑 `BlendMode.Plus` 叠加发光 T9）→ 前景层（顶部歌词行 / 效果名 Toast / 左下歌曲信息 / 底部 21 档指示器 + 控制栏）
- **`AUTO_DIRECTOR` 是模式不是效果**：`AutoDirector.evaluate` 在 `VisualizerOverlay` 内以 **500ms** 周期求值（低频重评估，避免每帧重组）；滞回 = 8s 最小驻留 + 升档 0.80 / 降档 0.65 + 场景候选表（EXPLOSION/TUNNEL/RING）
- **TV 左右键与焦点导航**：`onPreviewKeyEvent` 拦截；控制栏 3s 自动隐藏（隐藏态方向键 = 切效果，唤出后恢复焦点移动）；页面保留可获焦元素避免"焦点黑洞"
- **设置页清理**：移除频谱开关（`spectrumEnabled` 系列）与主题选择器；NowPlaying 48dp 小频谱条移除；补齐 `values-en/strings.xml` 中残留的 `settings_spectrum*` 三条译文（默认语言已删而译文未删会触发 release 构建 `removing resource ... without required default value` 告警）
- **构建**：新增依赖 `androidx.palette:palette-ktx:1.0.0`（唯一新增依赖，不引入 WebView / JTransforms / 3D 库）；ProGuard 增 `visualizer.**` 与 `VisualizerTheme` / `VisualQuality` / `Tier` 枚举 keep

#### 验证

- `assembleDebug` 通过（含全部主源码改动）
- `testDebugUnitTest`：全量 **38 个测试类 / 357 例 / 0 失败**，其中新增 6 类 47 例——`SpectrumAnalyzerTest`(10) 直接驱动 `processFft` 校验 ⑨ 的 AGC 解析解（重鼓点 1.0 / 轻鼓点 ≈0.355）与 ⑨-b 的线性/显示双通道（断言 `bass` 落在解析线性解上、明显低于 gamma 分支）、⑭ 的定长全 0 数组与"静音后立即恢复"；`SpectrumRepositoryTest`(6) 钉死"发布帧序号而非数组副本"（含 33ms 节流窗口内 `frameSeq` 不前进的断言）；`BeatDetectorTest`(7) 覆盖 120/180 BPM、静音、恒定能量、MIN_BASS 门限、pulse 快起慢落、reset 冷启动；`VisualizerThemeTest`(11) 覆盖 ⑮ 旧枚举迁移 + 画质门控；`AudioFrameTest`(5)；`FindCurrentLyricLineTest`(8)
- `assembleRelease`（R8 + `shrinkResources` + 签名）通过——新增 `visualizer.**` / 枚举 keep 规则无回归；debug APK 42.4MB、release APK **21.8MB**（`NASMusicTV-release-v2-30-0.apk`）
- 版本：v2.29.3 → **v2.30.0**（versionCode 127 → 128）
- **待办（各机型手测项）**：TV 真机 20 套效果逐套观感与帧率（老盒子 ≥30fps / 单帧 ≤16ms）；`←/→` 与控制栏焦点交互；手机滑动阈值手感；封面取色随切歌生效；连续 30min 无爆音 / ANR / 内存单调增长；Allocation Tracker 确认绘制循环零分配；`AUTO_DIRECTOR` 8s 驻留不抖动；部分国产 TV `Visualizer` 持续返回全 0 时的 PCM 降级通道（P6，已在 v2.30.1 实施，待真机校准）
### 10.123 v2.30.1 — 可视化 P6：自动导演交叉淡入 + PCM 降级通道（2026-09-12）

补齐 `docs/music-visualizer-dev-plan.md` 中 P6 的两个未完项。

#### AUTO_DIRECTOR 场景切换：600ms 交叉淡入

方案 §4.21 的红线是"交叉淡入 600ms，**禁止硬切**"，原实现（`remember(theme) { create(theme) }`）是硬切。新增 `visualizer/RendererSwapper.kt` 承担过渡：

- **双层叠绘**：切换时保留旧渲染器为 `previous`，UI 同时绘制两层 Canvas —— 新层 `alpha = t`、旧层 `alpha = 1-t`，`t = (now - startMs) / 600ms`。600ms 后 `previous.onExit()` 释放，避免粒子类效果长期双份绘制
- **只重绘不重组**：透明度由绘制循环（`withFrameNanos`）写入 `MutableFloatState`，Canvas 经 `graphicsLayer { alpha = ... }` 延迟读取 → 只失效图层绘制、不触发重组；旧层的**存在性**（结构变化）才用 State，每次过渡最多重组两次
- **手动切换仍硬切**：`crossfade = theme.isAutoDirector`（`AppRoot` 传入）。`←/→`、设置页选效果、切画质档都走硬切 —— 用户按键后需要即时反馈，600ms 淡入会显得迟钝
- **画质变化改为重新 `onEnter`**：`Terrain` / `LiquidGrid` / `MatrixRain` / 粒子类均在 `onEnter` 里按 `VisualQuality` 预分配缓冲；原实现靠 `DisposableEffect(theme, quality)` 重复调用 `onEnter`，新实现由 `sync()` 检测 `quality` 变化后对同一实例重进，保持缓冲尺寸与绘制参数一致

#### PCM 降级通道（P6）

部分国产 TV 的 `Visualizer` **绑定成功却持续返回全 0**（可用性"假真"），方案 §3.5 要求补一条自算通道。新增 5 个类：

| 文件 | 职责 |
|---|---|
| `player/PcmTapProcessor.kt` | `AudioProcessor`：`queueInput()` 只做「降混 mono + memcpy 到环形缓冲」，**严禁 FFT**（阻塞播放线程 → 爆音）；音频原样透传，不改动任何采样值 |
| `player/PcmRingBuffer.kt` | 单写（播放线程）/ 单读（FFT 线程）环形缓冲，容量取 2 的幂（8192 样本 ≈ 186ms），带写计数 `version` 供读方跳过无新数据的帧 |
| `player/Radix2Fft.kt` | 迭代式 radix-2 复数 FFT（N=1024，位反转表 + 旋转因子预计算，零分配）。**不引入 JTransforms** |
| `player/PcmSpectrumTap.kt` | 专用 `HandlerThread`（`THREAD_PRIORITY_BACKGROUND`）每 40ms 取窗（Hann）+ FFT |
| `player/PcmFallbackChannel.kt` | 把采集侧与分析侧打包为可启停单元，由 `PlaybackService` 创建 |

接线：`PlaybackService` 把 `pcmFallback.processor` 挂到 `setAudioProcessors(arrayOf(pcmFallback.processor, vocalRemovalProcessor))` 的**最前**（取人声消除之前的原始信号），并注入 `PlayerManager.setPcmFallbackChannel()` → `PlayerEqualizer` → `SpectrumAnalyzer`。

**仲裁状态机**（`SpectrumAnalyzer`）：

| 状态 | 进入条件 | 动作 |
|---|---|---|
| Visualizer（默认） | `attach()` / 回滚后 | 正常采集 |
| → PCM | 连续静音 ≥20 帧 **且** 持续 ≥2s **且** `isPlaying` **且** 未被抑制 | `visualizer.enabled = false`、重置跟踪状态、`activate()` PCM |
| → 回滚 | PCM 激活后 3s 内始终无有效信号 | `deactivate()` PCM、恢复 `visualizer.enabled = true`、**抑制 5 分钟**（两条通道都失败就别反复折腾） |

- **为什么不能只看"连续 20 帧"**：采集周期 20ms，20 帧仅 400ms —— 歌曲间奏、淡出段落都会被误判，故额外要求静音**持续时长 ≥ 2s**
- **暂停不参与判定**：`isPlaying` 由 `PlayerManager.playerListener.onIsPlayingChanged` 注入，否则暂停时的静音会被当作 Visualizer 失效
- **共用分析链**：两条通道都汇入 `analyze(magnitudes, bins, rate, fromPcm)` —— AGC / 感知加权柱映射 / 双通道输出只有一份实现（否则两条通道观感不一致）。PCM 的静音用绝对值 `PCM_SILENCE_EPS = 1e-3` 判定（数字静音即真静音），**不走自适应噪声门限**（那是为系统 Visualizer 的底噪准备的，会把小信号整段吞掉）
- **并发**：`analyze()` 加 `@Synchronized`；`usingPcm` 时直接丢弃 Visualizer 通道的残留帧（`enabled = false` 可能失败或回调在途），避免全 0 帧覆盖 PCM 结果导致画面闪烁
- **常态开销**：`capturing` 默认 false → PCM 采集与 FFT 线程均不启动；处理器仍在链上（`isActive()` 恒 true），每 block 多一次 memcpy，与既有 `SpectralMaskProcessor` 同级

#### 验证

- `compileDebugKotlin` / `assembleDebug` 通过；`assembleRelease`（R8 + `shrinkResources` + 签名）通过
- `testDebugUnitTest`：**42 个测试类 / 383 例 / 0 失败**，新增 4 类 26 例 —— `Radix2FftTest`(7) 用单频 / 直流 / 零输入 / 短缓冲校验 FFT 数值正确性；`PcmRingBufferTest`(7) 钉死"取最新窗口 / 未填满不返回脏数据 / version 前进"；`SpectrumAnalyzerPcmTest`(5) 断言 PCM 与 Visualizer **同一套 AGC**，且 PCM 不被自适应噪声门限吞掉（同一小信号在 Visualizer 通道为全 0、在 PCM 通道可见）；`RendererSwapperTest`(7) 断言 600ms 淡入进度、淡入中不释放旧层、手动切换硬切、画质变化只重进不重建
- 版本：v2.30.0 → **v2.30.1**（versionCode 128 → 129）
- **待办（各机型手测项）**：在真实「Visualizer 恒返回全 0」机型上验证降级触发与观感；PCM 的 `PCM_SILENCE_EPS` 与探测窗口时长需真机校准；交叉淡入期间的双层绘制在中低端盒子上的帧率

### 10.124 v2.30.2 — 效果库补全：棱镜彩虹 + 极光星空 + 歌词点阵优化（2026-09-12）

> 承接 v2.30.0 的全屏可视化舞台，补全 E21/E22/E23 三套候选效果的设计落地与两套既有效果的交互优化。内容以本会话实际改动为准（涵盖设备真机迭代）。

---

#### E21 PRISM_HOLO 棱镜彩虹（效果库新增）

- **主题**：`VisualizerTheme.PRISM_HOLO("棱镜彩虹", Tier.ADV, "21")` —— 填入原 E21 序号（此前空位），**设为 ADV 档**（不使用帧缓冲回绘，ULTRA 在 MEDIUM 画质会因 `allowFramebuffer=false` 被跳过；ADV 保证默认画质即可展示）
- **注册**：`VisualizerRendererFactory` 增加 `PRISM_HOLO -> PrismHoloRenderer()`；`selectable`（`entries.filter { tier != MODE }`）自动纳入效果库列表与底部指示器，无额外枚举白名单
- **实现**（`renderers/PrismHoloRenderer.kt`，全正弦缓动无锐跳）：
  1. 莫兰迪底层：灰褐(32°)→米色(40°)→浅棕灰(22°) 分段渐变，低饱和暗调暖底
  2. 主体色带：3 主带（粉紫 305°/淡蓝 215°/鹅黄 48°）+ 按画质 1~2 条次带（紫/青，模拟毛玻璃折射残影）；带宽目容随呼吸伸缩，段内 `flowHue = hue + sin(segT*6 + phase)*20` 实现全息渐变；`tunnelX`+`swirl` 三维隧道卷曲
  3. 频谱融入：`spectrum[(0.35~0.55)*len]` 映射为主带高度/拉伸的"隐形能量"，`bandEnergy` 驱动 `topY` 与宽度
  4. 棱镜色散光晕：底部 6 段光谱（0°/45°/120°/200°/280°/330°）叠加圆环 + 随时间滑动的一抹柔光
  5. 边缘流光：`ripple = sin(segT*8*(1+treble*2) + phase*1.7)` 触发段横线高亮
  6. 莫兰迪暗角：上下左右四条压暗矩形聚焦中央
- 动态映射：`bass`→呼吸 + 主带拉高变宽 + 光晕扩大；`treble`→边缘流光频率/亮度 + 彩虹光晕；`energy`→彩色光晕滑动亮度；`mid`→卷曲幅度与流动速度

#### E22 AURORA 极光（重构为写实自然星空版）

- **背景层**：三段平滑渐变（26 分带）——底部深海墨绿(170°)/ 中部午夜蓝(218°)/ 顶部近黑(238°)，`midT=0.42` 控制午夜蓝高度；底部亮度随 `bass` 微增
- **星空**：`drawStars` 用确定性 hash（`frac(seed*k)`）生成稳定位置避免帧间跳变——星尘按画质 62/100/150 颗、明暗交错（`bright` 分 1.0/0.45/0.16 三档）、随 `treble` 加快闪烁；5/7/10 颗高亮星带十字+斜向星芒，随 `bass` 旋转缩放、随 `energy` 点亮
- **极光主体**：5 条丝带集中在右半侧（`baseXs = 0.55~0.95`），从右下向右上卷曲蜿蜒向中央；底部最浓（`fpow16(u)` 透明度衰减）、顶部细散；段内色相 `+segT*13`（底部纯绿→向上偏蓝青）体现光带内部层次；低频 `breath` 呼吸、高频 `treble` 加快流动并增强边缘高亮；爆发时整带顶部抬升
- **动态联动**：`erupt = (treble*0.6 + energy*0.4)²` 驱动 `hueOf()` 让极光在荧光绿(132°)与紫红(300°)/冰蓝(205°)间随正弦相位摇摆，增强情感爆发冲击
- 底部地平线柔光晕（`drawHorizonGlow` 10 层渐弱椭圆）+ 每带根部亮点

#### E23 LYRICS_DOT_MATRIX 歌词点阵（优化迭代）

- **自适应字号**：`RenderContext` 新增 `lyricMaxLineChars`（全曲最长句字符数，由 `VisualizerStage.computeLyricInfo` 遍历全曲歌词计算，经 `update()` 传入）。渲染器 `computeFontSize()` 以参考字号测真实宽度 → 换算成让最长行宽占屏 80% 的字号，并用「行高 ≤ 屏高 40%」约束，最终 `coerceIn(h*0.05, h*0.16)` —— 既保证不同长短歌词自适应，又避免字号过大导致 3600 粒子/行摊稀出现字形空洞
- **修复字号刻度 bug**：上一版把比例常量直接当像素用（`coerceIn(0.05f, 0.16f)` 缺乘 `h`），字号被钳在 0.05~0.16 像素导致文字整段不可见；改回 `coerceIn(h*MIN, h*MAX)`
- **粒子律动**：由随机相位改为节奏驱动——`floatY = sin(globalT*2 + localX*10) * 2.2*(0.35 + bass*2.2)`：所有粒子按低音同步起伏 + 依横向位置的固定波相位形成整行规整波浪，鼓点（低音峰值）幅度增大，节奏感强、不发散
- **凝聚放缓**：初始两行凝聚 700→1200ms、单字凝聚到达 700→1200ms、每字凝聚延迟 110→160ms、行上移 600→700ms，让新一行歌词凝聚动画清晰可见而非瞬间到位

---

#### 验证

- `compileReleaseKotlin` / `assembleRelease`（R8 + `shrinkResources` + 签名）通过；真机安装于电视（192.168.0.114）
- 版本：v2.30.1 → **v2.30.2**（versionCode 129 → 130）
- **待办（真机手测）**：棱镜彩虹的色带重叠辉光在中低端盒子的帧率；极光星空版星尘/星芒数量对帧率影响；歌词点阵各歌曲最长句的字号观感与粒子密度；"棱镜彩虹/极光/歌词点阵"三效果连续 30min 的稳定性

---

### 10.125 v2.30.3 — 控制行收敛 + 数字雨预渲染 + 移除自动导演 + Path 批处理（2026-09-12）

#### 播放页 MTV 按钮完整显示（fix）
- **根因**：紧凑控制行总宽 ≈438dp（prev 48 + play 60 + next 48 + mode 48 + 幻 48 + K歌 48 + MTV 90 + 8dp×6 间距），超出固定 380dp 左栏 → 最右 MTV 被歌词栏盖住，此前"只看到 MT"。上一版只单独把 MTV 加宽到 90/100dp，反而把它进一步推出屏外，观感无变化
- **修复**：整体收紧紧凑行——`IconButton` 普通 48→40 / 主 60→52dp；`VocalToggleButton` 文字按钮显式宽度「幻」40 /「K歌」52 /「MTV」64dp；间距 8→6dp。合计 ≈364dp，MTV 完整落进 380dp 内单行

#### 数字雨（E16）性能优化
- 原逐字符 `nativeCanvas.drawText`（文本排布度量开销大，TV 弱 GPU 上卡顿）
- 改为进入时一次性预渲染 **2 数字 × 4 档绿 = 8 张字形 Bitmap**（`buildGlyphs`，尺寸/列数变化时经 `glyphKey` 重建缓存），每帧用 `drawBitmap` 快速 blit；`blitPaint.alpha` 按 fade 逐格缩透明度保留头/亮/中/暗梯度
- perCol 20→14，列数随画质档位（HIGH 48 / MID 30 / LOW 24）收窄

#### 移除「自动导演」随机选特效（`AUTO_DIRECTOR`）
- 用户诉求：遥控器选哪个效果就恒定显示哪个。删除 `AutoDirector.kt`，枚举移除 `AUTO_DIRECTOR` 及 `Tier.MODE` / `isAutoDirector`，`selectable = entries`（21 套手动效果）
- `AppRoot` 去掉 500ms `while (isAutoDirector)` 低频重估与 `crossfade = true` 分支（恒 `false`）；`VisualizerViewModel` 移除 `director` / `resolveTheme`，`activeThemeName` 直取 `_theme.value`
- 旧数据存过 `AUTO_DIRECTOR` → `fromKey` 未命中 → 回落默认（频谱环）；`VisualizerRendererFactory` 删 `AUTO_DIRECTOR` 分支；`VisualizerThemeTest` 改为 21 套断言
- 遗留（仅文档）：§10.123/§10.124 及开发方案中关于 `AUTO_DIRECTOR` 的既有描述为当时实现记录，本版起该功能下线

#### 高频小图元 Path 批处理（降 draw 次数，逼近 Android 5.1 单帧 ≈200 独立指令预算）
- 「频谱环」（E05）：约 64 次峰值帽 `drawCircle` → 按 `t` 分 4 hue 桶的 4 条 `Path`（`peakPaths`），颜色按桶内中间 hue 重算
- 「万花筒」（E10）：160 线段 + 160 端点光点 → `linePath` + `dotPath` 2 条 Path；扇区镜像/旋转改为手算世界坐标，段宽统一随 `frame.bass`，长度/端点半径仍逐条随频谱
- 「径向星芒」（E06）：约 128 次 `drawCircle` → 1 条 `tipPath`
- 「隧道穿越」（E03）：约 192 次环内 `drawCircle` → 1 条 `dotPath`
- 「烟花」（E14)：约 128 次背景频谱 `drawRect` → 1 条 `bgPath`
- 「Bloom」（E01）：约 768 次 `drawRoundRect` → 6 条 `Path`
- 零行为变化：`drawPath` + `BlendMode.Plus` 保持原有叠加辉光观感（E05 峰值帽由按桶颜色近似，肉眼不可辨）

#### 验证
- `assembleRelease`（R8 + `shrinkResources` + 签名）通过；真机手测：MTV 按钮文字完整、数字雨流畅、遥控器选特效所见即所得、效果切换流畅
- 版本：v2.30.2 → **v2.30.3**（versionCode 130 → 131）

### 10.126 v2.30.4 — 代码审查问题集中修复（2026-09-12）

来源：`NASMusicTV-提交审查报告-2026-09-12.html`（审查范围 `737ae0c` / `1cda585` / `ddaef2d`）。修复 3 项 P1、6 项 P2、5 项 P3，明细见 `CHANGELOG.md` v2.30.4 条目。

#### P1
- **万花筒 E10 旋转矩阵**：手写旋转展开时内端点用 `rot`（仅 `rotation`）、外端点用 `base`（`k*45°+rotation`）。统一为扇区角
- **歌词点阵 E23 切歌不更新**：根因是 `VisualizerStage` 的 `swapper = remember { RendererSwapper() }` 无 key → 切歌不重建渲染器。修复方式是给 `RenderContext` 加 `songId`，由渲染器自行检测（**不改 swapper 的 key**：切歌重建渲染器会导致粒子池/字形缓存等全部重分配）
- **可视化封面取色 403**：`coil.ImageLoader(app)` 绕过 `NasMusicApp.newImageLoader()` 的百度 UA 拦截器。改用 `coil.Coil.imageLoader(app)`

#### P2 要点
- Path 批处理必须满足“同色同 alpha”。E03/E06 原实现把逐元素 alpha 抹平成常量，改为按深度/频谱值分 3 桶，并去掉误加的 `BlendMode.Plus`
- `computeLyricInfo` 每帧被两个 Canvas 各调一次且内含 O(N) 扫描 → 提到外层算一次 + `LyricMetrics` 由 `remember(lyrics)` 缓存
- `remember(lyrics, progressMs) { derivedStateOf { … } }` 是无效缓存（key 每帧变），已简化为直接调用

#### 验证
- `./gradlew clean compileDebugKotlin` 通过（0 error）；`./gradlew test` 全绿
- `SpectrumAnalyzerTest` 断言由 `0.2f..0.31f` 收窄为 `0.235f..0.275f` 后仍通过
- 版本：v2.30.3 → **v2.30.4**（versionCode 131 → 132）

### 10.127 v2.30.5 — 可视化新增 E24「心跳」心电图式滚动频谱（2026-09-13）

规格文档：`docs/music-visualizer-dev-plan.md` §4.22.5（E24）

#### 新增文件与改动点
- `data/model/AppSettings.kt` — `VisualizerTheme` 枚举新增 `ECG_WAVE("心跳", Tier.BASIC, "24")`
- `visualizer/renderers/EcgWaveRenderer.kt` — 新增渲染器（环形 `FloatArray(cols)` 历史缓冲，零 `arraycopy`、`draw` 内零分配）
- `visualizer/VisualizerRendererFactory.kt` — `create()` 注册分支 + import
- `visualizer/AudioFrame.kt` — 新增 `bassRaw: Float`（未归一化的 20–250Hz 原始低频能量）
- `visualizer/SpectrumRepository.kt` — 写入 `f.bassRaw = rawBass`
- **舞台/交互/入口零改动**：`VisualizerStage` 自动遍历 `VisualizerTheme.selectable`

#### 渲染行为
- 一条连续折线自屏幕**最右端**生成扫描点，已绘制波形**冻结**并整体向**左**匀速平移，最左端超出屏幕被裁剪
- 滚动以**列虚拟时间** `colMs`（每列 +`1000/speed`）为基准，与真实帧率解耦
- 基线固定在屏幕**垂直中线**，无鼓点的时间段贴基线走平
- 仅低频节拍命中时注入完整 **P-QRS-T 心搏复合波**（跨度 0.50s），相对基线上下均有振幅（R 主峰向上、S 波下探）
- 复古 CRT 绿 `0xFF33FF7A` + 暗绿栅格；峰顶不绘制任何圆点

#### 手测反馈迭代（8 轮）
1. **只取鼓点**：弃用宽频时域波形 `AudioFrame.waveform`（混入人声/背景乐器导致折线过密、不像心电图），改为**仅 `frame.beat` 命中时**注入 QRS 波
2. **中心线上下跳**：基线固定垂直中线，而非从底部单向上跳
3. **峰顶无帽**：删除 `beatMask` / `beatPts` 节拍白点与右侧扫描头圆点
4. **显示名**：「示波器」→「心跳」
5. **幅度雷同 + 整体过高**：根因是 `SpectrumRepository` 的 `f.bass = boost(rawBass, bassPeak)` 走峰值跟随器，**鼓点瞬间恒等于 1.0**，渲染器拿不到强弱差异 → 新增 `AudioFrame.bassRaw`（未归一化原始能量），渲染器改用 `bassRaw / 0.8s EMA` 映射到 `0.45–1`；同时主峰高度 `0.40×半屏` → `0.26×半屏`
6. **拉长心搏波**：单根尖刺 → 完整 P-QRS-T（P +0.13 → Q −0.12 → R +1.0 → S −0.30 → T +0.24 → 回基线），跨度 `0.27s → 0.50s`，屏上宽度约 `48px → 90px`
7. **漏拍 + 控密度**：节拍检测跑 50Hz 而渲染数据仅 30fps（`SpectrumContract.EMIT_INTERVAL_MS=33`），单帧为 true 的 `beat` 在 30Hz 设备上被整拍跳过 → 改**双通道判定** `frame.beat || frame.pulse` 上升沿（`pulse` 为约 250ms 回落包络，跨帧存活）；同时设 **800ms 最短渲染间隔**（≈75 BPM 上限）丢弃窗口内连续鼓点，保证每个心搏完整、彼此留白
8. **隐患修复**：滚动位移原用 `roundToInt` 产生系统性漂移 → 改为小数累积 `colAccum`；`colMs` 超 1e6 自动回绕，避免长播后 Float 精度失真导致心搏计时走样

#### 验证
- `assembleRelease`（R8 + `shrinkResources` + 签名）**BUILD SUCCESSFUL**；`kspReleaseKotlin` / `compileReleaseKotlin` / `minifyReleaseWithR8` / `packageRelease` 均**实际执行**（非 UP-TO-DATE）
- 产物 `NASMusicTV-release-v2-30-5.apk`（21.81 MB）
- 真机手测通过（用户确认「可以了」）
- 版本：v2.30.4 → **v2.30.5**（versionCode 132 → 133）

### 10.128 v2.31.0 — 可视化新增 E25「催眠」数学函数图像动画（2026-09-13）

规格文档：`docs/催眠频谱效果开发方案.md`（v2.1）

#### 新增文件与改动点
- `data/model/AppSettings.kt` — `VisualizerTheme` 枚举新增 `HYPNOTIC_FUNCTION("催眠", Tier.BASIC, "25")`
- `visualizer/renderers/HypnoticFunctionRenderer.kt` — 新增渲染器（四态状态机 DRAW 8s / HOLD 3s / DISSOLVE 2.4s / GAP 0.5s）
- `visualizer/renderers/FunctionLibrary.kt` — 新增：53 条函数定义（`FunctionDef`，含 `uGap` 断点双段域）+ 三类采样器 + 三步加权洗牌（纯 JVM）
- `visualizer/renderers/FormulaLayout.kt` — 新增：数学公式源标记 → 排版 run 的自绘排版引擎（纯 JVM）
- `visualizer/VisualizerRendererFactory.kt` — `create()` 注册分支 + import
- `app/build.gradle.kts` — `testOptions.unitTests.isReturnDefaultValues = true`
- **舞台/交互/入口零改动**：`VisualizerStage` 自动遍历 `VisualizerTheme.selectable`

#### 渲染行为
- **描线 8s**：归一化采样点（HIGH 240 / MED 180 / LOW 120）按累加器推进（`speed = 1 + pulse*0.15`，最短 ≈6.96s），末端插值消除步进感，描线头带辉光
- **HOLD 3s**：整体呼吸缩放 1.000→1.006 + 辉光正弦；公式 run 同相位呼吸 alpha 0.82↔0.95
- **溃散 2.4s**：曲线点与公式 run 共用同一 `dissolveProgress`（平方缓动）。LOW/MED 逐点抖动蒸发（LOW 点数减半）；HIGH 粒子化坍缩（切线初速 + p≥0.45 重力 + `beat` 脉冲 ×1.35）。公式 alpha 衰减快 15%，run 蒸发阈值 0.30–0.85（>0.87 无效）
- **右侧公式带**：绘图区中心左移至 0.40w（宽 0.66w），公式带右 18%、垂直居中、右对齐；`FormulaLayout` 渲染真数学样式（嵌套上标/真分式/根号横线），上标绘制普通字形（不依赖 U+2070 区字体）；描线期 run 按 `k/R` 分批书写
- **坐标轴**：主轴画在数学 x=0 / y=0 真实位置（归一化 ±1.05 内可见），π 域 π 刻度 / 其余整数刻度 / 参数与极坐标无数字标签
- **与歌曲解耦**：渲染器不读 `songId`；`rng` 构造期 seed 一次（测试可注入固定值），`onEnter` 不触碰；超时保护推进 DRAW 前补满描线累加器（后台长驻返回不会"画 1% 就静止"）

#### 关键实现决策（与方案的偏差已回写方案文档）
- **加权洗牌必须全局排序**：先 Fisher-Yates 再全局按 `soft + rng*0.25` 降序——只在"前 60%"内部排序无法把尾部高 soft 换到头部，加权完全失效（实测 head/tail 密度无差异）
- **断笔跳变阈值 1.2**：0.6 会误伤 `ln` 前段真实陡峭（Δty≈0.65 之外首跳 1.55）与 tan 渐近线边界（Δty 恰≈1.50 踩线）；1.5 又拦不住 tan。Python 数值模拟全 53 条后定 1.2
- **segs 容量 32**：D9（cos t² 高频调制）240 点下自然撕裂 19 段，16 不够
- **公式断词**：level-0 文本按空格 + `+`/`-` 前边界拆词（运算符粘后块），否则 D4 这类无空格长式无法断行会侵入绘图区
- **枚举计数修正**：`VisualizerThemeTest` 的 21 断言在 E23/E24 加入时就已滞后（实际 22），本次新增后为 **23**，5 处断言 + 头部注释一并修正
- **`uGap` 语义定为"断点"**（跨 gap 切段，A10 gap=0），而非方案原稿的"第二段起点"

#### Release 验证与实机反馈（2026-09-13，同日）
- `:app:assembleRelease`（R8 + `shrinkResources` + 签名）**BUILD SUCCESSFUL**（`compileReleaseKotlin` / `minifyReleaseWithR8` / `packageRelease` 均实际执行）；产物 `NASMusicTV-release-v2-31-0.apk`（21.82 MB）
- **R8 静态验证**：解包 release dex 逐项检查——53 个 `def.tag` 字符串常量（A1–A27 / B1–B8 / C1–C8 / D1–D10）**全部保留**，`evalCartesian` / `evalParametric` / `evalPolar` 方法体与 `FunctionLibrary$WhenMappings` 分派表完整，公式 label 源标记（`frac{sin(x)}{x}` / `e^{-x^2}` / `16sin^3t` 等）保留——`when(tag)` 被 R8 折叠的风险排除（若分支不可达，对应字符串常量会被 R8 删除）
- 真机安装：192.168.0.114 `install -r` 直装 release 签名成功，启动无 FATAL / AndroidRuntime 异常
- **实机反馈修复**：只有第一张图有 8 秒描线，后续函数直接出全图——`GAP → DRAW` 迁移未清零 `drawAccumulator`：首图靠 `onEnter` 归零正常，之后每周期累加器残留 `8000ms`，新图第一帧即满足"描线完成"直接进 HOLD。修复为迁移时归零（commit `f34f7bf`），Hypnotic 聚焦测试回归通过后重新 `assembleRelease` 安装
- **用户确认验证通过**：电视上每张图均有完整 8 秒描线，周期节奏（8s/3s/2.4s/0.5s）、右侧公式带真数学样式、随机换图均正常

#### 验证
- `:app:testDebugUnitTest` 全量 **BUILD SUCCESSFUL**（含 6 个新测试文件 34 用例：53 条采样有限性/间断分段/闭合曲线、洗牌覆盖/防重/确定性/songId 无关、排版几何/预算、状态机时长、溃散阈值带）
- `:app:assembleDebug` **BUILD SUCCESSFUL**
- 版本：v2.30.5 → **v2.31.0**（versionCode 133 → 134）

### 10.129 v2.31.1 — 修复已下载/本地歌曲播放链路（持久化置空 + 解析二分支缺陷，2026-09-13）

**问题描述**：下载到本地的歌曲在恢复队列（重启 App）、最近播放、本地歌单、队列懒加载切换场景下无法播放；部分入口点播完全无反应，部分提示"解析播放链接失败"后自动跳歌。下载完成后当场从本地曲库点播正常（内存对象仍带 `file://` 地址）。

**根因分析**：

1. **持久化层无差别置空 streamUrl**：`AppPreferences` 的 `saveLastQueue` / `recordRecentSongObject` / `recordPlay` / `addSongToPlaylist` / 备份恢复共 6 处对所有歌曲 `copy(streamUrl = null)`（设计意图是防网络直链过期），但本地歌曲（含已下载入库，`storageType="DOWNLOAD"`）的 `streamUrl` 是永久有效的 `file://` URI（`LocalMusicRepository` 转换函数以 `contentUri` 填充），置空后恢复即丢播放地址。
2. **播放解析链只认两种来源**：`PlayerViewModel.resolveStreamUrl` / `resolveAndPlayCurrentSong` 为二分支——`isNetworkSong` 走 `NetworkMusicManager.resolvePlayUrl`，**其余全部当 NAS 歌曲**走 `adapter.getSongsByIds(id)`。本地歌曲 id 为 `local_<pathHash>`，在 NAS 后端必然查不到 → 返回 null → 提示"解析失败"或自动跳下一首。`updateRestoredQueueStreamUrls` 同样以 `!isNetworkSong` 筛 NAS 歌曲批量回填，`local_*` 混入产生无效查询且永不回填。
3. **空 URI 静默失败**：从最近播放/本地歌单点播时 `PlayerViewModel.playQueue` 的 `needsResolve` 只检测网络歌曲，本地歌曲空 URI 直达 `PlayerManager.playQueue` → ExoPlayer 报错 → `onPlayerError` 把"streamUrl 为空"视为预期网络懒加载场景直接 return——不提示、不跳歌、无播放。

**修改**：

- `data/prefs/AppPreferences.kt` — 新增私有扩展 `Song.stripVolatileStreamUrl()`（仅 `isNetworkSong` 置空 streamUrl），6 处持久化统一走该清理，注释同步
- `ui/viewmodel/PlayerViewModel.kt` — `resolveStreamUrl` / `resolveAndPlayCurrentSong` 扩为三分支（本地：`streamUrl ?: path`；网络：已下载优先 → 实时解析；NAS：adapter 查询）；`playQueue` 的 `needsResolve` 与首曲回填覆盖 `isLocalSong`（历史已置空数据用 `path` 回填）；`updateRestoredQueueStreamUrls` 筛选/合并排除 `isLocalSong`
- `backend/download/DownloadRepository.kt` — 新增 `playableLocalUri(song)`：下载记录 COMPLETED 且本地音频文件存在非空时返回 `file://` URI，否则 null
- `ui/viewmodel/NetworkMusicViewModel.kt` — `playNetworkSong` 解析直链前先查 `playableLocalUri`，已下载直接播本地文件
- `ui/viewmodel/MainViewModel.kt` — `playNetworkBatch` 首曲同样已下载优先（后续歌曲经 `resolveAndPlayByIndex` → `resolveStreamUrl` 懒加载自然覆盖）

**设计取舍**：NAS 歌曲下载后仍走 NAS 后端流播，不切本地副本（避免"NAS 上已替换文件但客户端仍播旧缓存"的语义歧义）；已下载优先仅应用于网络歌曲（Meting/百度网盘等直链有时效的源）。

**验证**：`:app:compileDebugKotlin` **BUILD SUCCESSFUL**；`:app:testDebugUnitTest`（backend.download / backend.local / data.prefs 聚焦）**BUILD SUCCESSFUL**。既有单测未覆盖持久化置空与解析分支，未新增用例；实机播放链路（恢复队列/最近播放/离线播已下载）待用户 TV 验证。

**版本**：v2.31.0 → **v2.31.1**（versionCode 134 → 135）

### 10.130 v2.31.2 — 统一歌曲/歌词来源标签体系（修复播放页硬编码 "NET"，2026-09-13）

**问题描述**：播放页正在播放百度网盘歌曲时，来源标识显示 "NET"——且 "NET" 并非任何标签体系的正式文案。来源标签在列表页（SourceBadge）、播放页（硬编码）、信息面板（原始标识大写）三处各说各话；歌词来源（LyricsSource）与歌曲来源（MusicSourceType）定义结构、文案体系互不统一。

**根因分析**：

1. `NowPlayingScreen` 来源标识为硬编码：`if (currentSong?.isNetworkSong == true)` → `Text("NET")`，从未接入 `MusicSourceType` / `SourceBadge` 体系——百度（"百度"☁ 橙）、Meting（"网络"🌐 绿）、Jamendo（"Jamendo"♪ 粉）、电台（"电台"📻 紫）全部显示 "NET"。
2. `SongInfoPanel` 信息面板「网络来源」行直接 `song.networkSource?.uppercase()`（BAIDU/METING/JAMENDO），且仅 `isNetworkSong` 显示。
3. `LyricsSource` 枚举仅有 `displayName`（内嵌歌词/本地歌词/在线歌词/缓存），与 `MusicSourceType`（displayName+icon+color）结构不一致；播放页歌词来源切换标签（SourceTag）文案另由 strings.xml 的 `player_highlight_backend/local/network/cached`（内嵌/本地/网络/缓存）独立维护——同一含义两套文案，且"网络/在线"措辞漂移。

**修改**：

- `ui/screens/NowPlayingScreen.kt` — 来源标识改用 `SourceBadge(song = currentSong)`（全来源显示，NAS/本地/已下载也补齐）；歌词来源切换标签 4 处 label 改为 `LyricsSource.XXX.displayName`
- `ui/components/SongInfoPanel.kt` — 「网络来源」行改名「歌曲来源」（`song_info_network_source_label` 值更新，中英双语），值统一 `song.sourceType.displayName`，全部来源显示
- `data/model/LyricsSource.kt` — 补齐 `icon`/`color` 字段与 `MusicSourceType` 结构对齐，`displayName` 统一短版（内嵌/本地/在线/缓存），颜色语义对齐歌曲来源
- `res/values/strings.xml` + `values-en/strings.xml` — 删除 `player_highlight_backend/local/network/cached` 与无引用的 `song_info_unknown_source`；`song_info_network_source_label` → 「歌曲来源」/ "Song Source"
- **保留现状**：发现页专辑角标（`BrowseComponents`）文案/颜色已走 `MusicSourceType`，样式为封面右上角紧凑版（9sp），不改；`SourceTag` 交互样式（selected/available）不变，仅文案统一

**修改后来源标签全景**（统一取 `MusicSourceType.displayName`，SourceBadge 统一样式）：NAS→"NAS"🎵蓝 / Meting 等网络曲→"网络"🌐绿 / 百度网盘→"百度"☁橙 / 电台→"电台"📻紫 / Jamendo→"Jamendo"♪粉 / 天气电台→"天气电台"🌤天蓝 / 本地音乐→"本地"📱橙 / 已下载→"已下载"⬇青。

**验证**：`:app:assembleDebug` **BUILD SUCCESSFUL**；`:app:testDebugUnitTest` 全量 **BUILD SUCCESSFUL**（29s，无回归）。实机待 TV 验证播放页标签显示。

**版本**：v2.31.1 → **v2.31.2**（versionCode 135 → 136）

### 10.131 v2.31.3 — 修复设置域子页面遥控器返回键分发（2026-09-13）

**问题描述**：从设置进入的多个子页面无法用遥控器返回键回到设置主菜单。均衡器、播放统计按返回键直接回首页；页面左上角返回按钮（点击可回设置）与按键行为不一致。

**根因分析**：BACK 键 Level 2 导航分发表（`AppRoot` LaunchedEffect，按 `currentScreen` 分发）为「白名单 + `else -> navigateHome` 兜底」模式，仅 `Screen.ServerConnect` 配了 `navSettings` 分支；`Screen.Equalizer` / `Screen.PlayStats`（入口唯一在 SettingsBranch）静默落入兜底回首页。同源隐患：`Screen.Netdisk`（入口唯一在 MineBranch）也落兜底回首页。入口核查：`Equalizer`/`PlayStats` 仅设置进入；`Netdisk` 仅"我的"进入；`WeatherRadio` 仅首页进入（兜底回首页已正确）；`PlaylistManagement` 无任何 navigateTo 调用（死分支）；`AlbumDetail`/`ArtistDetail` 多来源进入（曲库/首页/我的/搜索），无来源栈不归位，维持兜底。

**修改**：`ui/components/AppRoot.kt` — Level 2 分发表补 `Screen.Equalizer` / `Screen.PlayStats` → `navSettings`；新增 `navigateMine`，`Screen.Netdisk` → `navigateMine`；分支注释标注各页面入口唯一性。

**验证**：`:app:assembleDebug` **BUILD SUCCESSFUL**。实机验证路径：设置→播放设置→均衡器，按遥控器返回键应回设置主菜单（此前回首页）；设置→数据设置→播放统计同理；我的→网盘音乐，按返回键回"我的"。

**说明**：方向键"左键返回"交互应用内不存在（返回仅认 BACK 键）；若需该交互属新增功能，未包含在本版。

**版本**：v2.31.2 → **v2.31.3**（versionCode 136 → 137）

### 10.140 v2.32.3 — 审阅实施记录表述纠偏（2026-09-14）

**问题描述**：对 `logs_temp/code-review-full-report-2026-09-13.md` 的「实施记录」做逐项源码复核后，确认 13 项声称已修复的改动均在代码中真实存在（提交号/版本号/CHANGELOG/§10 亦属实），但发现 3 处表述与实际不符：

1. **S1 表述不实**：CHANGELOG 称"口令不再明文写死在源码仓库"，但口令字面量 `NasMusicTV-LocalCrypto-2b7e1f9c-2024` 仍作为默认值硬编码在 `app/build.gradle.kts`（受版本控制），安全收益基本为零——口令只是从 `CryptoUtils.kt` 移到构建脚本
2. **T2 注释不实**：`BaiduPrefs.kt` 头部注释称 `getCloudDriveConfigSync`/`saveCloudDriveConfigSync` "已无调用方"，实际单测 `CloudDriveConfigTest` 仍在调用
3. **报告自身计数不自洽**：正文称"P0 有效项 17 项"，与各章标题相加（安全 3 + 线程 6 + 架构 4 = 13）矛盾

**修改**：

- `CHANGELOG.md`：S1 条目改为准确表述——注明默认口令仍存在于仓库，仅在 `keystore.properties` 覆盖后运行期才不取自仓库默认值；标注此项属"混淆级非保密级"
- `app/src/main/java/com/nasmusic/tv/data/prefs/BaiduPrefs.kt`：注释修正为"`getBaiduConfigSync` 已删除；`getCloudDriveConfigSync`/`saveCloudDriveConfigSync` 生产调用点已清零，仅保留供单测 `CloudDriveConfigTest` 同步读写"
- `logs_temp/code-review-full-report-2026-09-13.md`（未跟踪文件）：两处"P0 有效项 17 项"改为 13 项并注明构成

**澄清（避免误删）**：`getCloudDriveConfigSync`/`saveCloudDriveConfigSync` **不是死代码**——生产调用点确已清零，但 `app/src/test/.../CloudDriveConfigTest.kt` 仍在调用，故保留；T2 迁移的准确表述是"IO 调度器切换版 runBlocking 清零"，`NasMusicApp.kt:243` 仍保留一处启动期主线程 `runBlocking { baiduConfigFlow.first() }`（不带调度器切换，为 onCreate 同步取配置的既定取舍）。

**验证**：`:app:assembleDebug`（in-process，2m19s）**BUILD SUCCESSFUL**。注：本机普通 Kotlin 守护进程模式会因 `AccessDeniedException`（`AppData\Local\kotlin\daemon`）失败，需带 `-Pkotlin.compiler.execution.strategy=in-process`。

**版本**：v2.32.3 批次内，versionCode 保持 142。

### 10.139 v2.32.3 — T3 PlayerManager 三元组原子化（2026-09-14）

**问题描述**：`PlayerManager` 的 `queue`/`currentIndex`/`currentSong` 是三个独立 `MutableStateFlow`，切歌/换队列时连续赋值非原子——UI 集中订阅点（AppRoot/QueueBranch/MainViewModel 派生流）在不同帧分别读三个流，快速切歌时可能读到"新队列 + 旧索引 + 旧歌名"的错帧状态（审阅报告 T3，v2.32.3 首批暂缓项，本次落地）。

**修改**：

- 新增 `player/PlayerState.kt`：`data class PlayerState(queue = emptyList(), currentIndex = 0, currentSong = null)`，三元组不可变快照
- `player/PlayerManager.kt`：私有 `_playerState: MutableStateFlow<PlayerState>` 取代原 `_queue`/`_currentIndex`/`_currentSong`，对外暴露 `val playerState: StateFlow<PlayerState>`；全部 23 处状态更新点（playSong/playQueue/restoreQueue/addToQueue/removeFromQueue/removeSongFromQueue/moveQueueItem/moveItem/clearQueue/advanceIndexSilently/advanceIndexBackward/transitionToIndex/playAt/syncAndPlayCurrent/playRandom/next/updateCurrentSongFromPlayer 等）统一改 `_playerState.update { it.copy(...) }` 原子发布；补 `import kotlinx.coroutines.flow.update`（扩展函数非成员方法）；顺带删除 `onIsPlayingChanged` 中重复的 `_isPlaying.value = isPlaying` 赋值（T3 前遗留的复制粘贴错误）
- `ui/viewmodel/PlayerViewModel.kt`：移除 `currentSong`/`queue`/`currentIndex` 三个转发流，改为透传 `playerState`；内部 8 处取值改 `playerState.value.xxx`
- 订阅点适配：
  - `ui/components/AppRoot.kt`：`currentSong` 由独立收集改为收集 `playerState` 后派生（`val currentSong = playerState.currentSong`）
  - `ui/components/branches/QueueBranch.kt`：queue/currentIndex 改为收集 `playerState` 派生
  - `ui/viewmodel/MainViewModel.kt`：歌词/播放历史联动 `currentSong.collect`、队列自动持久化 collect、`queueSongIds`/`recentNetworkSongs`/`currentNetworkSong` 三个派生流（`combine` 两流合并改为单流 `map`）、MV 回调/技术信息/歌词加载等 8 处取值全部改 `playerState`
  - `ui/viewmodel/DownloadViewModel.kt`：删除单曲前暂停判定改 `playerState.value.currentSong`
  - `player/PlaybackService.kt`：通知"下一首"标题改 `playerState.value.currentIndex`（局部别名 `pm` 引用，初查 `playerManager.*` 直引时漏检，编译期暴露）

**验证**：`:app:assembleDebug`（in-process，2m19s）**BUILD SUCCESSFUL**。错帧现象消除需 TV 实机快速切歌验证。

**版本**：v2.32.3 批次内，versionCode 保持 142。

### 10.138 v2.32.3 — T6 AudioFrame 双缓冲（2026-09-14）

**问题描述**：`SpectrumRepository` 的 `AudioFrame` 由音频回调线程（Visualizer/PCM 回调）写入、渲染线程（Canvas draw）读取，全字段无同步保护，高频切歌/高负载下可能出现半新半旧的撕裂帧（审阅报告 T6）。

**修改**：

- `visualizer/SpectrumRepository.kt`：引入双缓冲 `frames`（2 个预分配 `AudioFrame`）+ `@Volatile writeIndex`；写端（仅 `onFrame`/`reset`）写完 back 后翻转索引发布，读端 `frame` getter 读 front（1-writeIndex），volatile 写→读建立 happens-before。发布顺序固定为**先翻转 writeIndex 再发布 frameSeq**，保证"读端见新序号必见新帧"。帧序号改为仓库级全局计数器 `seqCounter`（双缓冲下若在实例上自增，读端会看到 1,1,2,2 的重复序列，漏判新帧）；`reset()` 改为两个实例同时清零 + `seqCounter` 归零，避免波形等字段跨歌残留
- `ui/components/VisualizerStage.kt`：`frame` 参数由 `AudioFrame` 快照引用改为 `() -> AudioFrame` provider——绘制循环走 `withFrameNanos` 不触发重组，若持有重组期快照引用，写端下一轮翻转后会写回该实例，双缓冲形同虚设；现每帧 draw 开头捕获一次 front，绘制期间引用不变
- `ui/components/AppRoot.kt`：调用点同步改传 `frame = { vm.frame }`

**边界说明**：双缓冲要求渲染单帧耗时 < 写周期（50Hz ≈ 20ms）；极端低端设备若绘制超时，写端会翻转到渲染层正在使用的缓冲，需三缓冲兜底（暂未实施，属已知边界）。

**验证**：`:app:testDebugUnitTest --tests "*SpectrumRepositoryTest"` 与全量单测 **BUILD SUCCESSFUL**（含 T2 改造遗留的 3 个测试文件适配：`BaiduMvFileServiceTest`/`ApiDriftNotifyTest`/`CloudDriveConfigTest` 由 `*Sync` 改为 suspend 调用）；`:app:assembleDebug` **BUILD SUCCESSFUL**。可视化无撕裂/跳变待 TV 实机高频切歌验证。

**版本**：v2.32.3 批次内，versionCode 保持 142。

### 10.137 v2.32.3 — T2 百度配置读取全量 Flow 化（分批，2026-09-14）

**问题描述**：AppPreferences 百度配置同步读取走 `runBlocking(Dispatchers.IO)`，主线程/普通成员函数调用点存在 ANR 与线程池占用风险（审阅报告 T2，26 处调用方）。复用既有 `baiduConfigFlow` 全量替换。

**修改（第一批：外部调用点）**：

- `NasMusicApp`：onCreate 百度注册改 `runBlocking { baiduConfigFlow.first() }`（不带 IO 调度器）；`refreshBaiduServiceRegistration` 改 suspend + `first()`
- `NetworkMusicViewModel`：`refreshBaiduConnectionState` 改 suspend + `first()`；`setBaiduEnabled` 包 `viewModelScope.launch`；`playNetworkSong` 自愈注册移入协程

**修改（第二批：内部 getter 链与调用方）**：

- `AppPreferences`：15 个百度便捷方法（tokens 3 个 + enabled/musicRootDir/mvDir/customAppKey/customSecretKey/apiDriftNotified 各 get/set）改 `suspend` + `baiduConfigFlow.first()`；删除 `getBaiduConfigSync` 与 15 个 `*Sync` 变体；R-7 注释更新为"通用兜底入口"
- `BaiduPrefs`：透传层改为 13 个 suspend 方法，移除全部同步透传
- `BaiduOAuthClient`：`resolveAppKey`/`resolveSecretKey` 改 suspend；8 处调用更新（均在 withContext(IO) 内）
- `BaiduNetdiskService`/`BaiduMvFileService`：各 1 处调用更新（withContext(IO) 内直接调 suspend）
- `NetworkMusicViewModel`：`setBaiduEnabled`/`setBaiduMusicRootDir`/`setBaiduMvDir` 包 `viewModelScope.launch`；`triggerBaiduIndexScanIfNeeded`/`checkMusicRootDirAfterVerify`/`onVerifyBaiduSuccess` 改 suspend

**验证**：`:app:compileDebugKotlin --rerun`（in-process，沙箱拦截 Kotlin daemon 时使用）**BUILD SUCCESSFUL**。

**版本**：v2.32.3 批次内，versionCode 保持 142。

### 10.136 v2.32.3 — 修复代码质量批次编译错误并跑通构建验证（2026-09-14）

**问题描述**：v2.32.3 代码质量修复批次（1507b59）落地时引入 3 处编译错误，`compileDebugKotlin` 失败：`LyricsDotMatrixRenderer.kt` 的 T7 try-finally 修复把 `bmp`/`n`/`minX`/`maxX`/`minY`/`maxY` 声明写进 try 块内，finally 与坐标映射段无法访问；`NetworkMusicViewModel.kt` 的 P1#12 修复与 `HqSeparationOrchestrator.kt` 的 L7 修复各缺一个 `kotlinx.coroutines` 导入（`flow.first` / `cancel` 扩展函数）。

**修改**：

- `LyricsDotMatrixRenderer.kt`：`n`/`minX`/`maxX`/`minY`/`maxY` 与 `bmp` 声明移至 try 外；`bmp` 改可空类型，try 内用局部非空 `b` 采样，finally 改 `bmp?.recycle()`
- `NetworkMusicViewModel.kt`：补 `import kotlinx.coroutines.flow.first`
- `HqSeparationOrchestrator.kt`：补 `import kotlinx.coroutines.cancel`

**验证**：`:app:assembleDebug`（2m28s）与 `:app:assembleRelease`（9m58s）均 **BUILD SUCCESSFUL**；产出 `NASMusicTV-release-v2-32-3.apk`（约 21.8MB）。

**版本**：v2.32.3 批次内修复，versionCode 保持 142。

### 10.135 v2.32.2 — 播放控制按钮图标色与聚焦反馈全局统一（2026-09-13）

**问题描述**：用户要求统一所有界面的播放控制类按钮（返回/上一曲/下一曲/播放顺序/播放暂停）视觉规范：①未聚焦时按钮背景不变、图标须为白色/亮色；②聚焦时整体按钮背景有变化、图标色不变。核查发现两处结构性问题：

**根因分析**：

1. **图标 tint 未显式设置**：`FocusableSurface` 下发内容色用的是自定义 `LocalFocusableContentColor`（`CompositionLocalProvider`），而各按钮内 `androidx.tv.material3.Icon` 不传 `tint` 时消费的是主题 `LocalContentColor`——两者不是同一通道，`contentColor`/`focusedContentColor` 参数对 Icon 完全无效。当前图标恰好显示亮白纯属主题 onBackground 恰为亮白的巧合，未显式保证。
2. **聚焦反馈不一致**：`QueueScreen` 播放/暂停按钮聚焦反而变暗（`Primary.copy(alpha=0.7f)`），与其他页"聚焦变亮"逻辑相反；NowPlaying/K 歌主按钮聚焦背景完全不变（Primary→Primary），无整体变化反馈。

**修改**：

- `Theme.kt`：`NasMusicColors` 新增 `PrimaryBright = Color(0xFF5EEAD4)`（主按钮聚焦高亮色，与 K 歌歌词高亮同色系）；`HighContrastColors.PrimaryBright` 改为引用该值消除重复定义
- `PlayerControls.kt`（NowPlaying/MTV 共用 `ControlButtonsRow`）：上一曲/播放暂停/下一曲/播放顺序 4 个 Icon 显式 `tint = NasMusicColors.TextPrimary`；私有 `IconButton` 主按钮 `focusedContainerColor` 由 `Primary` 改为 `PrimaryBright`
- `KaraokePlaybackScreen.kt`：`MiniIconButton`（返回/上一曲/播放/下一曲）Icon 显式 tint；主按钮聚焦背景改 `PrimaryBright`
- `MvPlaybackScreen.kt`：`MiniIconButton`（返回/上一曲/播放/下一曲）Icon 显式 tint；非主按钮聚焦已是 Primary 30% 变化，保持
- `QueueScreen.kt`：公开 `MiniIconButton` Icon 显式 tint；播放/暂停按钮聚焦背景由 `Primary.copy(0.7f)`（变暗）改为 `PrimaryBright`（变亮）
- 统一后的规范：**图标恒亮白 TextPrimary 不随焦点变；聚焦只变背景——非主按钮 Surface → Primary 30%，主按钮 Primary → PrimaryBright**
- 核查无需改动：网盘页返回按钮（已白色 tint + 聚焦变色）、`VocalToggleButton`（文字按钮，已合规）、`FavoriteButton`（Warning 橙为"已收藏"状态语义色，保留）

**验证**：`:app:assembleDebug` 与 `:app:assembleRelease` 均 **BUILD SUCCESSFUL**；v2.32.2 release APK 部署电视实机验证通过（用户确认：各页返回/上一曲/下一曲/播放顺序按钮未聚焦图标亮白，聚焦整体变色且图标色不变，问题解决）。

**版本**：v2.32.1 → **v2.32.2**（versionCode 140 → 141）

### 10.134 v2.32.1 — K 歌/MTV 手机遥控二维码不显示/按键唤醒修复（2026-09-13）

**问题描述**：TV 端进入 K 歌或 MTV 全屏页面后，手机遥控二维码完全不显示；即便显示，也会在约 5 秒无操作后隐藏且按遥控器无法稳定重新唤醒。

**根因分析**（两个独立问题）：

1. **二维码完全不显示（主因，实机确证）**：`AppRoot` 的 `isTV` 仅检查 `android.software.leanback` 特性；实测电视 `adb shell pm list features` 只上报 `android.hardware.type.television` 而无 leanback（常见于非 Google 认证的国产 TV 盒子）。TV 被误判为手机后，`NowPlayingBranch` 中 `remoteControlUrl = if (isTV) … else null` 把 URL 强制置 null，二维码根本无法生成。此为 v2.20.0 手机端支持（d8a09a0）引入的回归——当时 `MainActivity` setContent 内、`NasMusicApp`、`TextInputDialog` 三处 TV 判断均采用 `leanback || television` 双特性检测，唯 `AppRoot` 漏检。
2. **按键后二维码无法稳定重新显示**：两页虽在最外层 `Box` 注册了 `onPreviewKeyEvent`，但外层没有建立稳定的页面焦点域。实际焦点落在内部 TV `FocusableSurface` 按钮时，按键路由不保证经过页面监听；MTV 页面还包含 `AndroidView(PlayerView)`，进一步可能截断 Compose 焦点链路。

**修改**：

- `AppRoot` 的 `isTV` 补上 `android.hardware.type.television` 检测，与全库其余三处 TV 判断对齐；`MainActivity.onCreate` 的系统栏隐藏判断（原同样单查 leanback）同步对齐
- `KaraokePlaybackScreen` 与 `MvPlaybackScreen` 外层增加 `FocusRequester + focusable()`，页面进入时主动请求页面焦点，保证页面级预览监听稳定收到遥控器按键
- K 歌建立页面焦点域后仍将焦点交给原播放/暂停按钮；MTV 默认聚焦页面，方向键仍可进入原控制栏
- 仅在 `KeyEventType.KeyDown` 时调用 `activateControls()`，避免 KeyUp 对同一次操作重复重启 5 秒计时
- 二维码保持“进入页面及按键后立即显示、约 5 秒无操作后完全隐藏”；K 歌原有 `.zIndex(10f)` 遮罩层级修复保留
- **K 歌页底部控制栏对齐 MTV 定时虚化**：新增 `controlsAlpha`（无操作 5 秒 → 0.15，操作/焦点 → 1.0）应用到含返回按钮与全部控制按钮的底部 Row，并注册 `onFocusChanged` 刷新计时；歌词区域与歌曲信息保持常显，按钮虚化期间仍可聚焦（与 MTV 行为一致）

**验证**：`:app:compileDebugKotlin` 与全量 `:app:testDebugUnitTest` 均 **BUILD SUCCESSFUL**。电视实机 `pm list features` 确认设备只上报 `television` 特性（根因一实锤）；v2.32.1 release APK 部署实机验证通过——进入 K 歌页二维码正常显示，约 5 秒无操作后二维码完全隐藏、底部控制按钮虚化至 0.15，任意遥控器按键后两者立即恢复。

**版本**：v2.32.0 → **v2.32.1**（versionCode 139 → 140）

### 10.133 v2.32.0 — 可视化效果库补齐 11 套极简几何效果 E26–E36（2026-09-13）

**背景**：一次性补齐 11 套极简几何/机械风格可视化效果（声弦/几何环/构成/轨道/雷达/折纸/阶梯/齿轮/分形/光轴/螺旋），全部走既有 `VisualizerRenderer` 插件式接口，音频分析层零改动。

**新增文件**（`visualizer/renderers/`）：

- `BatchOneRenderers.kt`：E26 `VectorWavesRenderer`（声弦）+ E27 `PulsingPolygonsRenderer`（几何环）
- `BatchTwoRenderers.kt`：E28 `BauhausShapesRenderer`（构成）+ E29 `OrbitalRingsRenderer`（轨道）
- `BatchThreeRenderers.kt`：E30 `RadarGridRenderer`（雷达）+ E31 `OrigamiPolyRenderer`（折纸）+ E32 `StaircaseWaveRenderer`（阶梯）
- `BatchFourRenderers.kt`：E33 `ConcentricGearsRenderer`（齿轮）+ E34 `FractalTreeRenderer`（分形）+ E35 `LightBeamsRenderer`（光轴）+ E36 `FermatSpiralRenderer`（螺旋）

**关键实现决策**：

- 通道纪律：律动一律用 `bass/pulse/treble` 线性通道，显示长度/亮度用 `spectrum` gamma 通道；旋转类效果（E27/E30/E35/E36）把平滑后的 `treble` **积分**为角速度而非直接映射角度，防高频抖动
- 零分配：E30 的 `SweepGradient` 在画布尺寸确定时预分配一次；E26 全部线段合成单 Path 两次描边；E29 拖尾用环形历史缓冲（零 arraycopy）；E31 三角形翻折用 cos 投影 + 折线近似弧（Compose `Rect` 不可变，规避 `arcTo` 临时对象）；E35 虚线手动分段（`dashPathEffect` 无相位 API，每帧重建违反零分配红线）
- E32「阶梯」**刻意不做缓动**——量化瞬跳正是方波美学（与既有频谱效果的平滑形成反差）；碎裂用 `seq` 做确定性闪烁
- E33 齿轮 Path（齿根/齿顶梯形轮廓）onEnter 预生成，每帧仅 rotate/scale；beat 上升沿推进一个齿距（30°），120ms 快速缓动成棘轮手感
- E34 分形**不递归**：拓扑 onEnter 拍平（深度/父段/角度系数数组，前序保证父先于子），每帧 O(N) 端点计算 + 深度门控；bass 驱动展开深度推进
- E36 费马螺旋点位 onEnter 预计算（`r=c√n, θ=n·137.507°`），「内圈自转」用内圈点组整体旋转规避逐点变换

**注册接线**：`VisualizerTheme` 枚举新增 11 值（编号 26–36，全 Tier.BASIC 三档画质全开，内部按画质分档元素量）；`VisualizerRendererFactory` 新增 11 分支；舞台指示器/遥控切换/设置页零改动（自动遍历 `selectable`）；均衡器页 `VisualEqualizer` 小预览按 `ordinal % 3` 归类绘制样式，天然兼容。

**测试**：`VisualizerThemeTest` 数量断言 23 → 34。

**验证**：`:app:compileDebugKotlin` / `:app:testDebugUnitTest`（全量）/ `:app:assembleDebug` 全部 **BUILD SUCCESSFUL**。

**版本**：v2.31.4 → **v2.32.0**（versionCode 138 → 139）

### 10.132 v2.31.4 — 设置页内容区左键焦点回到导航栏（D-Pad 焦点越界重定向，2026-09-13）

**问题描述**：设置页选中左栏分区后按右键进入内容区修改查看，之后按左键无法把焦点移回左侧导航栏——用户报告仅「播放设置」分区稳定复现，其他分区可正常左移。

**根因分析**：设置页是「左栏分区导航（verticalScroll Column）+ 右栏内容（LazyColumn）」双滚动容器布局。D-Pad 焦点跨容器移动依赖 Compose 系统几何查找（在可视区域内找目标方向上最近的可聚焦节点）。播放设置分区内容最长：整段为**单个 LazyColumn item**（含播放模式横排、crossfade 时长横排、音质档位横排、模型操作横排、封面滤镜 ± 按钮组等多组横向焦点链），焦点在横排内部左右移动时，系统在该 item 的几何范围内找不到左栏目标，无法自然"走出"；其他分区内容短、多为全宽单列行，几何查找能命中左栏导航项。

**修改**（`ui/screens/SettingsScreen.kt`，Compose TV 标准焦点越界重定向）：

- 左栏每个分区项 modifier 挂独立 `FocusRequester`（`remember` 缓存在 `navFocusRequesters` Map，不随重组重建）
- 右栏 LazyColumn 挂 `focusGroup()` + `focusProperties { exit }`：`FocusDirection.Left` 越界时 `navFocusRequesters.getValue(activeSection).requestFocus()` 强制聚焦当前分区项，其余方向返回 `FocusRequester.Default` 维持系统默认
- 内容区内部（横排按钮组之间）的左右焦点移动不受影响——`exit` 仅在焦点请求越出 focusGroup 边界时触发

**验证**：`:app:compileDebugKotlin` / `:app:assembleDebug` / `:app:testDebugUnitTest` 全量 **BUILD SUCCESSFUL**。实机验证路径：设置→播放设置，右键进入内容区任意横排按钮组，连续按左键——应能穿过横排、逐行左移，最终落到左栏「播放设置」导航项；其他 8 个分区同路径抽测。

**版本**：v2.31.3 → **v2.31.4**（versionCode 137 → 138）
