# NAS Music TV — 技术架构概述

> 版本：v2.1.0 (DEV)
> 最后更新：2026-06-21
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
| 歌词 | `GET /Items/{id}/Lyrics` |

**封面图 fallback 逻辑**（已验证）：
- 优先使用 `ImageTags.Primary` 构造带 tag 的 URL（利用 Jellyfin 缓存）
- 若 `ImageTags.Primary` 为 null，回退到无 tag 的 `/Items/{id}/Images/Primary`（从上级条目继承封面）

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
**状态管理**：7 个 MutableStateFlow

| 状态 | 类型 | 说明 |
|------|------|------|
| `currentSong` | `Song?` | 当前播放歌曲 |
| `isPlaying` | `Boolean` | 播放中 |
| `playMode` | `PlayMode` | 播放模式 |
| `progress` | `Long` | 当前进度(ms) |
| `duration` | `Long` | 总时长(ms) |
| `queue` | `List<Song>` | 播放队列 |
| `currentIndex` | `Int` | 当前在队列中的位置 |
| `buffering` | `Boolean` | 缓冲中 |

**关键方法**：

| 方法 | 行为 |
|------|------|
| `setPlayer(exoPlayer)` | 注册 ExoPlayer 实例（由 PlaybackService 调用） |
| `playSong(song)` | 替换队列为单曲并播放 |
| `playQueue(songs, startIndex)` | 设置多曲队列并播放 |
| `playPause()` | 切换播放/暂停 |
| `next()` | 下一曲（按播放模式决定行为） |
| `previous()` | 上一曲 |
| `seekTo(positionMs)` | 跳转到指定位置 |
| `setPlayMode(mode)` | 设置播放模式并同步 ExoPlayer |
| `addToQueue(song)` | 添加到队列末尾 |
| `removeFromQueue(index)` | 从队列移除指定索引 |
| `clearQueue()` | 清空队列 |
| `onPlaybackEnded()` | 播放结束回调（按播放模式处理） |

**进度更新**：通过 Handler + Runnable 每 500ms 轮询 `player.currentPosition`。同时 ViewModel 也通过协程 `delay(500)` 调用 `updateProgress()`。两套机制共存。

**播放模式行为**：

| 模式 | `next()` 行为 | `onPlaybackEnded()` 行为 |
|------|-------------|------------------------|
| SEQUENTIAL | 下一首（无曲目时停止） | 停止 |
| REPEAT_ONE | 重头播放当前曲目 | 重头播放 |
| REPEAT_ALL | 下一首（末尾回到第一首） | 回到第一首 |
| SHUFFLE | 随机选一首播放 | 随机选一首播放 |

**关于 `updateCurrentSongFromPlayer()`**：从 `player.currentMediaItemIndex` 读取当前索引，同步到 `_currentSong` 和 `_currentIndex`。在 `onMediaItemTransition` 和 `playQueue()` 完成后调用。

#### PlaybackService

**文件**：`player/PlaybackService.kt`  
**类型**：`MediaLibraryService`（Media3）

**生命周期**：
- `onCreate()` → 创建 ExoPlayer → 创建 MediaLibrarySession → 注册到 PlayerManager
- `onTaskRemoved()` → 未播放时自停
- `onDestroy()` → 释放 Player 和 Session

**已知限制**（规划中待改进）：
- `MediaLibrarySession.Callback` 为空实现 -> 外部无法通过 MediaSession 控制播放
- 无前台通知 -> Android 14+ 可能被杀
- 无 `onGetBrowserRoot()` -> 无法外部浏览曲库

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
| Settings | `screens/SettingsScreen.kt` | 415 | 侧边栏导航：通用/播放/歌词/网络/关于 |
| ServerConnect | `screens/ServerConnectScreen.kt` | 757 | 服务器类型选择 + 表单 |
| TextInputDialog | `screens/TextInputDialog.kt` | 315 | TV 虚拟键盘弹窗 |
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

### 设置
- [x] 暗色主题切换
- [x] 界面动画开关
- [x] 自动下一首开关
- [x] 默认播放模式
- [x] 歌词/封面缓存开关
- [x] 歌词偏移调节
- [x] 网络连通性测试
- [x] About 页面（版本信息）

### TV 适配
- [x] `leanback` required
- [x] 横屏锁定
- [x] D-pad 完整导航
- [x] 焦点系统（FocusRequester + onFocusChanged）
- [x] TV 虚拟键盘（TextInputDialog）
- [x] 三层 BACK 键处理

---

## 6. 约束与限制

### 已知技术债务
1. **MediaLibrarySession.Callback 空实现** — 外部无法通过 MediaSession 控制
2. **无前台通知** — Android 14+ 可能杀播放服务
3. **重复的进度更新** — Handler 和协程两条路径共存
4. **裸单例模式** — PlayerManager、BackendRegistry、AppPreferences 均为手动单例，测试困难
5. **零测试** — 项目无单元测试、无集成测试、无 UI 测试
6. **死代码未清理** — backend/jellyfin/ 和 backend/navidrome/ 下两个目录的 Retrofit 实现未使用
7. **错误处理不规范** — 适配器中所有 catch 块直接返回 `emptyList()`，无日志区分

### 已知 Bug / 功能缺失
1. 网络断开后不会自动重连
2. 播放队列不持久化（杀死 App 后丢失）
3. 无收藏/喜欢功能
4. 无专辑详情页（点击专辑直接播放）
5. 无演唱者详情页（点击演唱者直接播放）
6. 无播放列表管理
7. 无均衡器/音效调节
8. 封面图全屏沉浸模式未实现
9. [已修复] ~~Jellyfin 连接泄漏：`testConnection()` 每次创建新 `JellyfinAdapter()` 调用 `authenticateByName()` 在服务端创建永久 session，无 `logout()` 释放。多次测试连接 → session 积满 → 服务端 HTTP 500。此外 `BackendRegistry.disconnect()` 只置 null，不关闭 OkHttp 连接池。修复需在 JellyfinAdapter 添加 `logout()` 并在 `disconnect()` 中调用，同时 `testConnection()` 用完释放临时 adapter。详见 Issue #1。~~ → 详见 10.7.2 和 10.7.4

### 兼容性约束
| 约束 | 说明 |
|------|------|
| 仅横屏 | `screenOrientation="landscape"` |
| 需要 Leanback | `android.software.leanback required=true` |
| 无触摸 UI | D-pad 滚动 + 聚焦 |
| 无 DI 框架 | 手动单例管理 |
| 仅使用 HTTP | `usesCleartextTraffic=true`（NAS 本地网络） |

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
├── NasMusicApp.kt           # Application 类
├── NasMusicVersion.kt       # 版本信息
├── backend/
│   ├── BackendAdapter.kt    # 后端接口
│   ├── BackendRegistry.kt   # 后端注册中心
│   └── impl/
│       ├── JellyfinAdapter.kt   # Jellyfin 实现
│       └── NavidromeAdapter.kt  # Navidrome 实现
├── data/
│   ├── model/
│   │   ├── Album.kt
│   │   ├── AppSettings.kt
│   │   ├── Artist.kt
│   │   ├── Lyrics.kt
│   │   ├── LyricsLine.kt
│   │   ├── LyricsSource.kt
│   │   ├── PlayMode.kt
│   │   ├── ServerConfig.kt
│   │   └── Song.kt
│   └── prefs/
│       └── AppPreferences.kt
├── lyrics/
│   ├── LrcParser.kt
│   ├── LyricsManager.kt
│   ├── LyricsNetworkProvider.kt
│   └── Mp3MetadataExtractor.kt
├── player/
│   ├── CoverArtManager.kt
│   ├── PlayerManager.kt
│   └── PlaybackService.kt
├── ui/
│   ├── MainActivity.kt
│   ├── components/
│   │   ├── ConnectPromptDialog.kt
│   │   ├── LyricsView.kt
│   │   └── PlayerControls.kt
│   ├── screens/
│   │   ├── ExitConfirmDialog.kt
│   │   ├── LibraryScreen.kt
│   │   ├── NowPlayingScreen.kt
│   │   ├── QueueScreen.kt
│   │   ├── ServerConnectScreen.kt
│   │   ├── SettingsScreen.kt
│   │   └── TextInputDialog.kt
│   ├── theme/
│   │   ├── Color.kt
│   │   ├── Theme.kt
│   │   └── Type.kt
│   └── viewmodel/
│       └── MainViewModel.kt
└── util/
    ├── PinyinUtils.kt
    └── TimeUtils.kt
```

### 文档

| 文件 | 用途 |
|------|------|
| `docs/technical-overview.md` | 当前架构、修改记录与回归测试（本文档） |
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

### 10.6 v2.0.0

**版本信息**：VERSION_CODE=3, BUILD_TYPE=STABLE, FILE_FORMAT_VERSION=1

**概要**：完整实现 Phase 2-4 全部功能，包括专辑/演唱者详情页、流派/年代浏览、多演唱者拆分、收藏/最近播放、卡拉 OK 歌词、均衡器、前台通知、网络监控、错误提示、播放列表管理、HDMI-CEC、缓存管理。

---

#### 10.4.1 A-1 专辑详情页

**功能描述**：从 LibraryScreen 点击专辑卡片进入详情页，展示专辑封面、曲目列表。
- `AlbumDetailScreen.kt` — 左侧 360dp 封面 + 右侧曲目列表（itemsIndexed, LazyColumn）
- 支持「播放全部」和逐曲播放，BACK 键返回曲库
- `MainViewModel.openAlbumDetail(album)` + `loadAlbumSongs(albumId)` 缓存至 `_albumSongsCache`

**涉及文件**：`AlbumDetailScreen.kt`（新建）、`MainActivity.kt`（Screen.AlbumDetail 分支）、`MainViewModel.kt`（openAlbumDetail/loadAlbumSongs）

---

#### 10.4.2 A-2 演唱者详情页

**功能描述**：从 LibraryScreen ArtistsTab 点击演唱者进入详情页，展示该演唱者所有歌曲。
- `ArtistDetailScreen.kt` — 顶部演唱者名 + 歌曲列表，支持「播放全部」
- `MainViewModel.selectedArtistName` StateFlow 记录当前查看的演唱者
- 歌曲数据来源于 `_artistSongsMap`（A-4 拆分后映射）

**涉及文件**：`ArtistDetailScreen.kt`（新建）、`MainActivity.kt`（Screen.ArtistDetail 分支）、`MainViewModel.kt`（openArtistDetail）

---

#### 10.4.3 A-3 流派与年代浏览

**功能描述**：LibraryScreen 增加 GENRES / YEARS 标签页，按流派和出版年份筛选歌曲。
- `GenresTab` — 流派列表，点击弹出 Dialog 显示该流派歌曲
- `YearsTab` — 年代区间（1990s/2000s/2010s/2020s），点击弹出 Dialog
- `MainViewModel.getSongsByGenre(genre, callback)` + `getSongsByYearRange(from, to, callback)`
- `BackendAdapter.getGenres()` / `getSongsByGenre()` / `getSongsByYearRange()` 接口方法

**涉及文件**：`LibraryScreen.kt`（GenresTab/YearsTab）、`MainViewModel.kt`、`BackendAdapter.kt`、`JellyfinAdapter.kt`、`NavidromeAdapter.kt`

---

#### 10.4.4 A-4 多演唱者拆分

**功能描述**：将后端返回的 `artist` 字段按分隔符拆分为独立演唱者列表。
- `ArtistSplitter` — 按优先级匹配分隔符：`feat.` → `ft.` → `with` → `&/、/×` → `vs`
- `MainViewModel.buildArtistMaps(songs)` — 生成 `_songArtistMap`（songId→artists）和 `_artistSongsMap`（artist→songs）
- LibraryScreen ArtistsTab 展示拆分后的独立演唱者（而非原始 `artist` 字段）

**涉及文件**：`ArtistSplitter.kt`（新建）、`MainViewModel.kt`、`LibraryScreen.kt`

---

#### 10.4.5 B-1 歌曲收藏

**功能描述**：NowPlayingScreen 增加收藏按钮，LibraryScreen 增加 FAVORITES 标签页。
- `FavoriteButton` — 在 CoverColumn 中与歌名同行显示，❤/♡ 图标
- `MainViewModel.toggleFavorite(song)` / `isFavorite(songId)` / `loadFavorites(adapter)`
- `AppPreferences` 无专门字段 — 收藏状态由后端管理，ViewModel 缓存 `_favoriteIds`（Set\<String\>）和 `_favoriteSongs`
- `BackendAdapter.toggleFavorite()` / `getFavorites()` 接口方法

**涉及文件**：`NowPlayingScreen.kt`、`LibraryScreen.kt`、`MainViewModel.kt`、`BackendAdapter.kt`、`JellyfinAdapter.kt`、`NavidromeAdapter.kt`

---

#### 10.4.6 B-2 最近播放与播放次数

**功能描述**：LibraryScreen 增加 RECENT 标签页，记录最近播放的 50 首歌曲。
- `AppPreferences.recordPlay(songId)` — LRU 队列，JSON 序列化至 DataStore `recent_songs` key
- `AppPreferences.playCounts` — `Map<String, Int>` 播放计数，存储至 `play_counts` key
- `MainViewModel.recentSongIds` / `playCounts` 直接暴露 AppPreferences 的 Flow
- `MainViewModel.recordPlay(song)` — 在 `playSong` / `playQueue` 中调用

**涉及文件**：`AppPreferences.kt`、`MainViewModel.kt`、`LibraryScreen.kt`

---

#### 10.4.7 B-3 卡拉 OK 逐字高亮

**功能描述**：支持词级时间戳的 LRC 歌词，播放时逐字变色。
- `WordTimestamp` 数据类（`word`, `startMs`, `durationMs`）
- `LyricsLine.wordTimestamps` 字段（默认为空列表）
- `LrcParser` 解析 `<mm:ss.ff>word>` 格式标记，生成 `WordTimestamp` 列表
- `LyricsView` 使用 `AnnotatedString` + `SpanStyle` 构建逐字高亮，已播词用 `TextBrightHighlight` 色
- `NasMusicColors.TextBrightHighlight` (#5EEAD4) 新增主题色

**涉及文件**：`LyricsLine.kt`、`LrcParser.kt`、`LyricsView.kt`、`Theme.kt`

---

#### 10.4.8 B-4 均衡器

**功能描述**：创建均衡器界面和 Android AudioFX 绑定。
- `EqualizerScreen.kt` — 预设选择器（Normal/Classical/Dance/etc.）+ 频段增益列表
- `PlayerManager.initEqualizer()` — 绑定 `android.media.audiofx.Equalizer` 到 ExoPlayer 音频会话
- `PlayerManager.setEqualizerBand()` / `getEqualizerBandLevel()` / `getEqualizerBandCount()` / `getEqualizerCenterFreq()`
- `AppPreferences.equalizerPreset` / `equalizerBands` — 持久化预设和自定义频段
- 预设值按频率段换算：pop/rock/jazz/classic/dance 各有独立 EQ 曲线

**涉及文件**：`EqualizerScreen.kt`（新建）、`PlayerManager.kt`、`AppPreferences.kt`、`EqualizerPreset.kt`（新建）、`MainActivity.kt`、`SettingsScreen.kt`

---

#### 10.4.9 D-1 前台通知

**功能描述**：PlaybackService 启动时创建前台通知，显示当前播放歌曲信息。
- `PlaybackService.createNotificationChannel()` — 通道 ID `nas_music_playback`，IMPORTANCE_LOW
- `PlaybackService.buildNotification()` — 显示歌名、演唱者、播放/暂停/上/下一曲按钮
- `PlaybackService.updateNotification()` — 播放状态变化时更新
- `startForeground(1, notification)` — 在 `onCreate()` 中立即调用
- `AndroidManifest.xml` — `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限

**涉及文件**：`PlaybackService.kt`、`AndroidManifest.xml`

---

#### 10.4.10 D-2 网络监控

**功能描述**：监听网络状态变化，断网后自动重连。
- `MainActivity.registerNetworkCallback()` — 注册 `ConnectivityManager.NetworkCallback`
- `MainViewModel.onNetworkAvailable()` — 最多重试 3 次连接
- `MainViewModel.onNetworkLost()` — 重置重试计数，显示断网提示
- ``_isNetworkAvailable` StateFlow` — 供 UI 层使用

**涉及文件**：`MainActivity.kt`、`MainViewModel.kt`

---

#### 10.4.11 D-3 错误提示

**功能描述**：所有后端调用失败时显示用户可见的错误提示。
- `MainViewModel._errorMessage` StateFlow — 5 秒自动清除的消息队列
- `MainViewModel.showError(msg)` — 统一错误显示方法
- 所有 catch 块从 `Log.e` 升级为 `Log.e` + `showError()`（约 16 处修改）
- `MainActivity` 顶部红色错误横幅显示

**涉及文件**：`MainViewModel.kt`、`MainActivity.kt`

---

#### 10.4.12 E-4 缓存管理

**功能描述**：SettingsScreen 增加 CACHE 区域，显示缓存大小，支持清除歌词/封面缓存。
- `SettingsScreen` — CACHE section with `SettingActionButton`
- `CoverArtManager.clearCache()` — 清除 Coil 磁盘/内存缓存
- `CoverArtManager.getCacheSize()` — 获取磁盘缓存大小
- `LyricsManager.clearCache()` — 已有方法（未改动）
- `MainViewModel.clearLyricsCache()` / `clearCoverCache()`

**涉及文件**：`SettingsScreen.kt`、`CoverArtManager.kt`、`MainViewModel.kt`

---

#### 10.4.13 F-1 播放列表管理

**功能描述**：创建、查看、删除、播放播放列表。
- `PlaylistManagementScreen.kt` — 播放列表示 + 选中列表歌曲详情
- `BackendAdapter` 新增 6 个方法：`getPlaylists()`、`createPlaylist()`、`deletePlaylist()`、`getPlaylistSongs()`、`addToPlaylist()`、`removeFromPlaylist()`
- `JellyfinAdapter` — 使用 `/Playlists` 端点实现
- `NavidromeAdapter` — 使用 Subsonic `/rest/getPlaylists` + `createPlaylist` + `deletePlaylist` + `updatePlaylist` 实现

**涉及文件**：`PlaylistManagementScreen.kt`（新建）、`BackendAdapter.kt`、`JellyfinAdapter.kt`、`NavidromeAdapter.kt`、`MainViewModel.kt`、`MainActivity.kt`

---

#### 10.4.14 G-1 HDMI-CEC 媒体键

**功能描述**：通过遥控器媒体键控制播放。
- `MainActivity.onKeyDown()` 拦截 `KEYCODE_MEDIA_PLAY_PAUSE` / `PLAY` / `PAUSE` / `NEXT` / `PREVIOUS` / `STOP`
- 焦点在 NowPlaying 页面时，`DPAD_CENTER`/`ENTER` 键映射为播放/暂停

**涉及文件**：`MainActivity.kt`

---

#### 10.4.15 E-2 单元测试

**功能描述**：为核心工具类编写单元测试。
- `ArtistSplitterTest.kt` — 多分隔符拆分、去重、空白处理（10 个测试用例）
- `PinyinUtilsTest.kt` — 子串匹配、大小写不敏感匹配（6 个测试用例）
- `LrcParserTest.kt` — 单/多时间戳、偏移量、排序、格式检测、二分查找（10 个测试用例）

**涉及文件**：`app/src/test/java/.../util/ArtistSplitterTest.kt`、`PinyinUtilsTest.kt`、`lyrics/LrcParserTest.kt`（新建）

---

### 附：数据模型变更

| 模型 | 变更 |
|------|------|
| `Genre.kt` | 新建（id, name） |
| `EqualizerPreset.kt` | 新建（枚举：NORMAL/POP/ROCK/JAZZ/CLASSICAL/DANCE） |
| `Playlist.kt` | 新建（id, name, songCount, owner） |
| `RecentSong.kt` | 新建（id, timestamp） |
| `LyricsLine.kt` | 新增 `wordTimestamps: List<WordTimestamp>` 字段 |
| `WordTimestamp.kt` | 新建（word, startMs, durationMs） |

### 附：AppPreferences 新增 Key

| Key | 类型 | 用途 |
|-----|------|------|
| `recent_songs` | `List<String>` (JSON) | 最近 50 首歌曲 ID 列表 |
| `play_counts` | `Map<String, Int>` (JSON) | 播放次数映射 |
| `equalizer_preset` | `String` | 均衡器预设名称 |
| `equalizer_bands` | `List<Float>` (JSON) | 自定义频段增益 |

### 附：BackendAdapter 接口变更

新增 13 个方法：
- `getPlaylists()`, `createPlaylist()`, `deletePlaylist()`, `addToPlaylist()`, `removeFromPlaylist()`
- `toggleFavorite()`, `getFavorites()`, `setRating()`
- `getGenres()`, `getSongsByGenre()`, `getSongsByYearRange()`
- `scrobblePlay()`, `getRandomSongs()`

### 附：Screen 枚举变更

新增 4 个值：`AlbumDetail`, `ArtistDetail`, `Equalizer`, `PlaylistManagement`

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
