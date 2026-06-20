# NAS Music TV — 技术架构概述

> 版本：v1.0.1 (STABLE)
> 最后更新：2026-06-20
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

**验证状态**：⏳ 待部署验证。

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
