# 网络音乐顶级 Tab + 推荐歌单功能

## TL;DR

> **Quick Summary**: 将网络音乐从曲库子 Tab 中抽离为独立顶级页面，增加推荐歌单卡片行（横向滚动）、热歌榜/新歌榜歌曲列表、搜索平台切换（网易云/QQ/酷狗）、歌单详情子页面等功能。仅扩展后端接口，不引入新依赖。
>
> **Deliverables**:
> - 新增 `Screen.Network` + `Screen.NetworkPlaylistDetail` 枚举值
> - 导航栏从 5 项变 6 项（增加「网络音乐」）
> - `NetworkScreen.kt` — 推荐歌单 + 平台切换 + 搜索 + 收藏
> - `NetworkPlaylistDetailScreen.kt` — 歌单详情
> - `Playlist.kt` — 歌单数据模型
> - 后端：`getPlaylist()` 接口 + MetingApiService 实现
> - `LibraryScreen.kt` 清理（移除 NETWORK tab）
> - `CoverCarousel` 增加 `autoCycle` 参数（解耦播放状态）
>
> **Estimated Effort**: Medium（3-5 天）
> **Parallel Execution**: YES - 3 waves + 1 final wave
> **Critical Path**: Task 1 → Task 4 → Task 7 → Task 11 → F1-F4

---

## Context

### Original Request
将网络歌曲从曲库中抽离，建立独立顶级 Tab。增加推荐歌单、热搜榜、平台切换等功能。

### Interview Summary
**Key Discussions**:
- 网络音乐从 `LibraryScreen` 的子 Tab 提升为独立顶级页面，Screen 枚举加 `Network` + `NetworkPlaylistDetail`
- 页面布局：搜索框 + 平台切换按钮 → 推荐歌单卡片行（LazyRow）→ 热歌榜/新歌榜片段 → 我的收藏
- 歌单卡片点击 → 进入歌单详情子页面（类似 AlbumDetail）
- 歌单封面取前 3 首歌封面轮播（CoverCarousel + autoCycle）
- 搜索平台切换（歌词来源样式）：网易云 / QQ 音乐 / 酷狗
- 推荐内容固定走 Netease，只有搜索受平台切换影响
- 不引入新依赖，不改后端部署方案

**Research Findings**:
- Meting-API 原生支持 `type=playlist` 端点，返回 JSON 数组格式与 search 一致 → 直接复用 `parseSongs()`
- 网易云歌单 ID 已验证可用（3778678 热歌榜等 7 个预置 ID）
- Binaryify/NeteaseCloudMusicApi 已归档死亡，不依赖
- `CoverCarousel` 的轮播节奏绑定 `isPlaying`，歌单卡片需解耦

**Metis Review**:
- CoverCarousel 需要加 `autoCycle` 参数解耦播放状态
- 部分歌单可能因版权下架 → 歌单加载失败时隐藏卡片
- 网络搜索无分页 —— 歌单最多 100 首，够用

---

## Work Objectives

### Core Objective
将网络音乐功能从 Library 子 Tab 提升为独立顶级页面，增加推荐歌单卡片行 + 热歌榜/新歌榜列表 + 平台切换 + 歌单详情子页面。

### Concrete Deliverables
- 新增 `Screen.Network` + `Screen.NetworkPlaylistDetail`
- 导航栏 5→6 项（增加「网络音乐」）
- `NetworkScreen.kt` — 推荐布局 + 平台切换 + 搜索 + 收藏
- `NetworkPlaylistDetailScreen.kt` — 歌单详情子页面
- `Playlist.kt` — 歌单数据模型（含 coverUrls 轮播列表）
- `CoverCarousel.kt` — 增加 `autoCycle` 参数
- `NetworkMusicService.kt` — 增加 `getPlaylist()` 接口方法
- `MetingApiService.kt` — 实现 `getPlaylist()` 调用 `type=playlist`
- `NetworkMusicManager.kt` — 增加路由方法
- `LibraryScreen.kt` — 移除 NETWORK tab + NetworkTab composable
- `MainViewModel.kt` — 增加 playlist 状态 + 加载方法

### Definition of Done
- [ ] `./gradlew.bat test` 编译通过，0 测试回归
- [ ] 导航栏显示「网络音乐」Tab，焦点可到达
- [ ] NetworkScreen 初始化时自动加载推荐内容（歌单卡片 + 热歌榜 + 新歌榜）
- [ ] 搜索功能正常，平台切换后重新搜索
- [ ] 歌单卡片点击进入详情页，歌单封面轮播正常
- [ ] 收藏的歌曲在「我的收藏」片段中显示

### Must Have
- 网络音乐完全独立于 NAS 曲库（Library 不再包含网络子 Tab）
- 导航栏有独立的网络音乐入口
- 推荐内容自动加载（无需用户操作）
- 搜索支持多平台切换（网易云 / QQ 音乐 / 酷狗）
- 歌单卡片封面轮播不受播放状态影响

### Must NOT Have (Guardrails)
- 不引入新依赖（无新 Gradle 依赖、无后端部署变更）
- 不改动 NAS 后端的任何代码（BackendAdapter 及其实现不变）
- 不改动现有播放/队列/收藏的数据流（isNetworkSong 标识不变）
- 不实现 ALAPI / JioSaavn（占位枚举不处理）
- 不实现 Docker 自建后端（可选后续优化）

---

## Verification Strategy (MANDATORY)

### Test Decision
- **Infrastructure exists**: YES（55 tests, 8 files）
- **Automated tests**: None（纯 UI + 后端扩展，现有测试不动）
- **Framework**: 无新增测试
- **Compilation**: 由用户自行编译验证

### QA Policy
每个 Task 的 QA 场景通过代码审查 + 用户自行编译验证确认。用户完成开发后自行运行 `./gradlew.bat test` 和 `./gradlew.bat assembleDebug`。

---

## Execution Strategy

### Parallel Execution Waves

```
Wave 1 (Start Immediately — 基础模型 + 后端 + 组件改造):
├── Task 1: Playlist.kt 数据模型 + CoverCarousel autoCycle 参数
├── Task 2: NetworkMusicService 接口 + MetingApiService 实现 getPlaylist()
└── Task 3: NetworkMusicManager 路由方法

Wave 2 (After Wave 1 — 新 Screen + ViewModel):
├── Task 4: Screen.Network + Screen.NetworkPlaylistDetail + AppRoot 导航
├── Task 5: MainViewModel playlist 状态 + 加载方法
├── Task 6: NetworkScreen.kt 主页面（搜索 + 推荐 + 收藏）
└── Task 7: NetworkPlaylistDetailScreen.kt 歌单详情页

Wave 3 (After Wave 2 — 集成 + 清理):
├── Task 8: LibraryScreen.kt 移除 NETWORK tab
├── Task 9: strings.xml 字符串资源更新
├── Task 10: 平台切换按钮组件
└── Task 11: 集成测试 + 手动验证

Wave FINAL (After ALL tasks — 4 路并行审查):
├── Task F1: Plan compliance audit (oracle)
├── Task F2: Code quality review (unspecified-high)
├── Task F3: Real manual QA via adb install (unspecified-high)
└── Task F4: Scope fidelity check (deep)
    → Present results → Get explicit user okay

Critical Path: Task 1 → Task 4 → Task 6 → Task 11 → F1-F4 → user okay
```

### Agent Dispatch Summary
- **1**: 3 - T1 → `quick`, T2 → `quick`, T3 → `quick`
- **2**: 4 - T4 → `quick`, T5 → `unspecified-high`, T6 → `visual-engineering`, T7 → `visual-engineering`
- **3**: 4 - T8 → `quick`, T9 → `quick`, T10 → `visual-engineering`, T11 → `unspecified-high`
- **FINAL**: 4 - F1 → `oracle`, F2 → `unspecified-high`, F3 → `unspecified-high`, F4 → `deep`

---

## TODOs

- [ ] 1. Playlist 数据模型 + CoverCarousel autoCycle 参数

  **What to do**:
  - 新建 `data/model/Playlist.kt`：`data class Playlist(id, name, coverUrls: List<String>, songCount)`
  - 修改 `CoverCarousel.kt`：给 composable 增加 `autoCycle: Boolean = false` 参数
    当 `autoCycle = true` 时，轮播节奏不依赖 `isPlaying` 状态（始终切换）
    当 `autoCycle = false`（默认）时，保持现有行为（仅在播放时切换）

  **Must NOT do**:
  - 不改 `CoverCarousel` 的现有接口签名（只加带默认值的可选参数）
  - 不改 `Playlist` 的写入方式（只读模型，由 ViewModel 创建）

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: 纯数据类 + 组件加参数，无业务逻辑
  - **Skills**: 无
  - **Skills Evaluated but Omitted**: N/A

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 2, 3)
  - **Blocks**: Task 4, 5, 6, 7
  - **Blocked By**: None

  **References**:
  - `ui/components/CoverCarousel.kt` — 需要加 autoCycle 参数
  - `data/model/Song.kt` — 参考 Song 的字段设计模式

  **Acceptance Criteria**:
  - [ ] Playlist.kt 文件存在，包含 id/name/coverUrls/songCount 字段
  - [ ] CoverCarousel.kt autoCycle 参数编译通过
  - [ ] 现有 CoverCarousel 使用方（NowPlayingScreen）不受影响（autoCycle 默认 false）

  **QA Scenarios**:
  ```
  Scenario: CoverCarousel autoCycle 默认行为不变
    Tool: Bash (./gradlew.bat assembleDebug)
    Preconditions: 无
    Steps:
      1. grep -n "autoCycle" app/src/main/java/com/nasmusic/tv/ui/components/CoverCarousel.kt
      2. grep -n "CoverCarousel" app/src/main/java/com/nasmusic/tv/ui/screens/NowPlayingScreen.kt
    Expected Result: CoverCarousel 声明中有 autoCycle: Boolean = false，
      且 NowPlayingScreen 调用时未传 autoCycle 参数
    Evidence: .omo/evidence/task-1-autoCycle-default.txt
  ```

  **Evidence to Capture**:
  - [ ] task-1-playlist-model.txt — Playlist.kt 文件内容
  - [ ] task-1-autoCycle-default.txt — CoverCarousel autoCycle 参数 grep 结果

  **Commit**: YES
  - Message: `feat(network): add Playlist data model and CoverCarousel autoCycle param`
  - Files: `data/model/Playlist.kt`, `ui/components/CoverCarousel.kt`

- [ ] 2. NetworkMusicService getPlaylist() + MetingApiService 实现

  **What to do**:
  - `NetworkMusicService.kt` 接口增加方法：
    ```kotlin
    suspend fun getPlaylist(playlistId: String): List<Song>
    ```
  - `MetingApiService.kt` 实现：
    ```kotlin
    override suspend fun getPlaylist(playlistId: String): List<Song> = withContext(Dispatchers.IO) {
        val url = "$baseUrl?server=netease&type=playlist&id=$playlistId"
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            val body = response.body?.string()
            if (!response.isSuccessful || body.isNullOrBlank()) emptyList()
            else parseSongs(body)
        }
    }
    ```

  **Must NOT do**:
  - 不改 `parseSongs()`（已兼容 album 字段，直接复用）
  - 不改 `search()`、`resolvePlayUrl()`、`resolveLyrics()` 等现有方法
  - 不处理 playlist 404/版权下架（由调用方处理空列表）

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: 接口 + 实现，代码模式明确
  - **Skills**: 无
  - **Skills Evaluated but Omitted**: N/A

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1, 3)
  - **Blocks**: Task 5, 6
  - **Blocked By**: None

  **References**:
  - `backend/network/NetworkMusicService.kt` — 加接口方法
  - `backend/network/MetingApiService.kt:search()` — 参考现有实现模式
  - `backend/network/MetingApiService.kt:parseSongs()` — 直接复用

  **Acceptance Criteria**:
  - [ ] NetworkMusicService 接口包含 `getPlaylist()` 签名
  - [ ] MetingApiService 实现 `getPlaylist()` 编译通过

  **QA Scenarios**:
  ```
  Scenario: 接口定义检查
    Tool: Bash (grep)
    Preconditions: 无
    Steps:
      1. grep "suspend fun getPlaylist" app/src/main/java/com/nasmusic/tv/backend/network/NetworkMusicService.kt
    Expected Result: 返回 `suspend fun getPlaylist(playlistId: String): List<Song>`
    Evidence: .omo/evidence/task-2-interface.txt
  ```
  ```
  Scenario: 实现检查
    Tool: Bash (grep)
    Preconditions: 无
    Steps:
      1. grep -A10 "override suspend fun getPlaylist" app/src/main/java/com/nasmusic/tv/backend/network/MetingApiService.kt
    Expected Result: 返回 override suspend fun getPlaylist 实现，包含 type=playlist
    Evidence: .omo/evidence/task-2-impl.txt
  ```

  **Evidence to Capture**:
  - [ ] task-2-interface.txt — 接口方法 grep 结果
  - [ ] task-2-impl.txt — 实现方法 grep 结果

  **Commit**: YES (groups with Task 1, 3)
  - Message: `chore(network): squash with Task 1`
  - Files: `backend/network/NetworkMusicService.kt`, `backend/network/MetingApiService.kt`

- [ ] 3. NetworkMusicManager 路由方法

  **What to do**:
  - 在 `NetworkMusicManager.kt` 增加：
    ```kotlin
    suspend fun getPlaylist(playlistId: String): List<Song> {
        val svc = services[defaultSource] ?: return emptyList()
        return try { svc.getPlaylist(playlistId) }
        catch (e: Exception) { AppLog.w(TAG, ...); emptyList() }
    }
    ```
  - 注意：不 fallback 到其他源（歌单 ID 是网易云专有），精确路由

  **Must NOT do**:
  - 不改现有方法（search 等保持不变）
  - 不做多源 fallback

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: 一行路由方法，模式明确
  - **Skills**: 无

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 1 (with Tasks 1, 2)
  - **Blocks**: Task 5
  - **Blocked By**: None

  **References**:
  - `backend/network/NetworkMusicManager.kt:resolvePlayUrl()` — 参考路由模式

  **Acceptance Criteria**:
  - [ ] NetworkMusicManager 包含 `getPlaylist()` 方法，编译通过

  **Commit**: YES (groups with Tasks 1, 2)

- [ ] 4. Screen 枚举 + AppRoot 导航扩展

  **What to do**:
  - `Screen.kt` 增加：`Network`, `NetworkPlaylistDetail`
  - `AppRoot.kt`：
    - 导航栏增加第 6 个 `NavItem`（引用 `R.string.nav_network`）
    - `when(currentScreen)` 增加 `Screen.Network` 分支 → 调用 `NetworkScreen(...)`
    - 增加 `Screen.NetworkPlaylistDetail` 分支 → 调用 `NetworkPlaylistDetailScreen(...)`
    - 数据传递：`networkSearchResults`, `networkSearchKeyword`, `networkFavoriteSongs`, `networkFavoriteIds`, `playlistSongs`, `playlists`

  **Must NOT do**:
  - 不改现有 5 个 NavItem 的顺序
  - 不改现有 9 个 Screen 分支的代码
  - 不把现有 NavItem 的 string resource 改名

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: 纯样板代码，无业务逻辑
  - **Skills**: 无

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Tasks 5, 6, 7)
  - **Blocks**: Task 8, 11
  - **Blocked By**: Task 1

  **References**:
  - `data/model/Screen.kt` — 加枚举值
  - `ui/components/AppRoot.kt:when(currentScreen)` — 加分支
  - `ui/components/AppRoot.kt:NavItem` — 加第 6 项

  **Acceptance Criteria**:
  - [ ] Screen 枚举包含 Network + NetworkPlaylistDetail
  - [ ] 导航栏显示 6 项，Network 是第 3 项
  - [ ] when(currentScreen) 包含 Network 分支
  - [ ] BACK 键从 NetworkScreen → NowPlaying
  - [ ] BACK 键从 NetworkPlaylistDetail → NetworkScreen

- [ ] 5. MainViewModel playlist 状态

  **What to do**:
  - 新增状态：
    ```kotlin
    private val _networkPlaylists = MutableStateFlow<List<Pair<Playlist, List<Song>>>>(emptyList())
    val networkPlaylists: StateFlow<...> = _networkPlaylists
    private val _playlistSongs = MutableStateFlow<List<Song>>(emptyList())
    val playlistSongs: StateFlow<List<Song>> = _playlistSongs
    ```
  - 新增方法：
    ```kotlin
    fun loadNetworkPlaylists()  // 初始化时加载所有预置歌单
    fun loadPlaylistDetail(playlistId: String)  // 加载单个歌单详情
    ```
  - 预置歌单 ID 列表（从 Playlist 模型创建）：
    3778678, 3779629, 19723756, 3136952023, 60198, 377165088, 2211745987
  - 歌单加载失败时隐藏卡片（返回空歌曲列表的卡片不显示）

  **Must NOT do**:
  - 不改 `searchNetworkSongs()` 等现有方法
  - 不自动播放歌单（只加载数据）

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: ViewModel 状态管理，需要理解现有数据流
  - **Skills**: 无
  - **Skills Evaluated but Omitted**: N/A

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Tasks 4, 6, 7)
  - **Blocks**: Task 6, 11
  - **Blocked By**: Task 1, 2, 3

  **References**:
  - `ui/viewmodel/MainViewModel.kt:searchNetworkSongs()` — 参考现有网络搜索数据流
  - `ui/viewmodel/MainViewModel.kt:_networkSearchResults` — 参考 StateFlow 模式

  **Acceptance Criteria**:
  - [ ] ViewModel 在 `init` 或首次访问时自动加载预置歌单
  - [ ] 歌单加载结果通过 `networkPlaylists` 暴露
  - [ ] 失败歌单自动隐藏（不出现在列表中）

- [ ] 6. NetworkScreen 主页面

  **What to do**:
  - 新建 `ui/screens/NetworkScreen.kt`
  - 页面结构（从上到下）：
    1. **搜索行**：SearchBar（复用 LibraryScreen 的 SearchBar 组件或同等模式）
    2. **平台切换行**：3 个按钮（网易云 / QQ 音乐 / 酷狗），歌词来源标签样式
    3. **推荐歌单卡片行**：LazyRow 横向滚动
       - 每张卡片 = 方形封面区域（CoverCarousel + autoCycle=true，取前 3 首歌封面）+ 标题 + 歌曲数
       - 卡片点击 → navigateTo(Screen.NetworkPlaylistDetail(playlistId))
    4. **热歌榜片段**：分段标题 + SongRow 列表（固定 3778678）
    5. **新歌榜片段**：分段标题 + SongRow 列表（固定 3779629）
    6. **我的收藏片段**：分段标题 + 收藏的 SongRow 列表
  - 搜索状态切换：有搜索关键词 → 隐藏推荐内容，显示搜索结果（同现逻辑）
  - 无搜索关键词 → 显示推荐内容
  - 整体 LazyColumn 包裹，各段为 items
  - 平台切换时：关键词不变，调用 `searchNetworkSongs(keyword, server)`

  **Must NOT do**:
  - 不改现有的 `SongRow`、`FavoriteButton`、`QueueToggleButton` 组件
  - 不实现歌单自身的分页加载（Meting-API 最多 100 首，够用）
  - 不从 NAS 后端加载任何数据

  **Recommended Agent Profile**:
  - **Category**: `visual-engineering`
    - Reason: TV UI 布局 + 焦点导航 + D-pad
  - **Skills**: 无
  - **Skills Evaluated but Omitted**: N/A

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Tasks 4, 5, 7)
  - **Blocks**: Task 8, 11
  - **Blocked By**: Task 1, 4, 5

  **References**:
  - `ui/screens/LibraryScreen.kt:NetworkTab()` — 参考现有搜索逻辑
  - `ui/components/CoverCarousel.kt` — 封面轮播组件（autoCycle=true）
  - `ui/screens/LibraryScreen.kt:SongRow` — 歌曲行复用
  - `ui/screens/LibraryScreen.kt:SearchBar` — 搜索栏复用

  **Acceptance Criteria**:
  - [ ] NetworkScreen 打开时自动显示推荐内容（歌单卡片 + 热歌榜 + 新歌榜）
  - [ ] 搜索后有结果时显示搜索结果，清空关键词后回到推荐
  - [ ] 平台切换后重新搜索，关键词不变
  - [ ] 歌单卡片横向可滚动，焦点正常
  - [ ] 歌单封面轮播节奏不受播放状态影响（autoCycle=true）

- [ ] 7. NetworkPlaylistDetailScreen 歌单详情页

  **What to do**:
  - 新建 `ui/screens/NetworkPlaylistDetailScreen.kt`
  - 页面布局：
    - 顶部：BackButton + 歌单标题
    - 内容：LazyVerticalGrid(2 列) 的 SongRow 列表
    - 支持收藏/加入队列操作
  - 从 ViewModel 的 `playlistSongs` 状态读取数据
  - 导航：BACK 键 → NetworkScreen

  **Must NOT do**:
  - 不改 SongRow 组件
  - 不实现歌单封面大图（保持列表风格）

  **Recommended Agent Profile**:
  - **Category**: `visual-engineering`
    - Reason: TV 列表 UI
  - **Skills**: 无

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 2 (with Tasks 4, 5, 6)
  - **Blocks**: Task 11
  - **Blocked By**: Task 1, 4

  **References**:
  - `ui/screens/AlbumDetailScreen.kt` — 参考详情页模式
  - `ui/screens/LibraryScreen.kt:SongRow` — 歌曲行复用

  **Acceptance Criteria**:
  - [ ] 点击推荐歌单卡片 → 进入歌单详情页
  - [ ] 详情页显示歌单所有歌曲（SongRow 列表）
  - [ ] 支持收藏/加入队列
  - [ ] BACK 键 → NetworkScreen

- [ ] 8. LibraryScreen 移除 NETWORK tab

  **What to do**:
  - 从 `LibraryTab` 枚举移除 `NETWORK`
  - 移除 `NetworkTab()` composable（已迁移到 NetworkScreen）
  - 移除相关的 tab 渲染逻辑和状态分支
  - 移除 `networkSearchResults`, `networkSearchKeyword`, `networkFavoriteSongs`, `networkFavoriteIds` 等参数的传递（这些现在走 AppRoot → NetworkScreen）

  **Must NOT do**:
  - 改动 NAS 相关的 7 个 Tab（ALBUMS/ARTISTS/SONGS/GENRES/YEARS/FAVORITES/RECENT）

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: 代码删除 + 参数清理
  - **Skills**: 无

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 9, 10, 11)
  - **Blocks**: None
  - **Blocked By**: Task 4, 6

  **References**:
  - `ui/screens/LibraryScreen.kt:LibraryTab` — 枚举定义
  - `ui/screens/LibraryScreen.kt:NetworkTab()` — 需移除的 composable

  **Acceptance Criteria**:
  - [ ] LibraryScreen 的 Tab 从 8 个变为 7 个
  - [ ] NETWORK Tab 不复存在
  - [ ] 编译通过，无引用错误

- [ ] 9. strings.xml 字符串资源更新

  **What to do**:
  - 新增：`nav_network` → "网络音乐"
  - 新增：`network_hot_songs` → "热歌榜"
  - 新增：`network_new_songs` → "新歌榜"
  - 新增：`network_my_favorites` → "我的收藏"
  - 新增：`network_recommend_playlists` → "推荐歌单"
  - 新增：`network_platform_netease` → "网易云"
  - 新增：`network_platform_qq` → "QQ音乐"
  - 新增：`network_platform_kugou` → "酷狗"
  - 移除：`library_network`（不再需要）

  **Must NOT do**:
  - 不改现有字符串的键名和值

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: 纯字符串操作
  - **Skills**: 无

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 8, 10, 11)
  - **Blocks**: None
  - **Blocked By**: None

  **Acceptance Criteria**:
  - [ ] 所有新增字符串存在且正确
  - [ ] 编译通过

- [ ] 10. 平台切换按钮组件

  **What to do**:
  - 在 NetworkScreen 内实现（或抽取为独立组件）搜索平台切换行
  - 样式：类似歌词来源标签（已选高亮，未选灰色）
  - 3 个选项：网易云 / QQ 音乐 / 酷狗
  - 切换回调：触发 `searchNetworkPlatformChanged(server: String)`

  **Must NOT do**:
  - 不改 LyricsView 的歌词来源标签样式（只参考模式）
  - 不添加预设端点选择（仍在设置页控制）

  **Recommended Agent Profile**:
  - **Category**: `visual-engineering`
    - Reason: TV 焦点 UI 组件
  - **Skills**: 无

  **Parallelization**:
  - **Can Run In Parallel**: YES
  - **Parallel Group**: Wave 3 (with Tasks 8, 9, 11)
  - **Blocks**: None
  - **Blocked By**: Task 4, 6

  **References**:
  - `ui/screens/LibraryScreen.kt:SongRow` — 参考歌词来源标签样式
  - `ui/screens/NowPlayingScreen.kt` — 歌词来源标签的点击和焦点模式

  **Acceptance Criteria**:
  - [ ] 3 个平台按钮正常显示，当前选中高亮
  - [ ] 切换平台后搜索词不变，触发重新搜索

- [ ] 11. 集成测试（用户自行编译）

  **What to do**:
  - 代码审查：确认所有新文件存在且被正确引用，无死引用、无缺失 import
  - **编译工作由用户自行完成**：用户运行 `./gradlew.bat test` 和 `./gradlew.bat assembleDebug`
  - 确认编译通过后，更新版本号和文档

  **Must NOT do**:
  - 不添加新测试
  - 不安装到电视

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: 全项目集成验证，需要理解所有变更
  - **Skills**: 无

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Sequential (after all Wave 3 tasks)
  - **Blocks**: F1-F4
  - **Blocked By**: Task 8, 9, 10

  **Acceptance Criteria**:
  - [ ] 代码审查通过（无死引用、无缺失 import）
  - [ ] 用户编译反馈 → BUILD SUCCESSFUL

---

## 开发完毕后的文档更新流程

所有 11 个任务开发完成、用户编译验证通过后，执行以下步骤：

### 版本号更新
- `app/build.gradle.kts`：`versionCode` 递增 1，`versionName` 相应升级

### 文档更新
- `CHANGELOG.md`：添加新版本记录（列出所有变更，分类 Fixed / Changed / Added）
- `README.md`：版本历史添加新版本条目
- `docs/technical-overview.md`：在 Section 10 添加修改记录（参照 v2.4.4 格式）
- 将 `.omo/plans/network-music-tab.md` 复制到 `docs/network-music-tab-plan.md`

---

## Final Verification Wave (MANDATORY)

- [ ] F1. **Plan Compliance Audit** — `oracle`
  Read the plan end-to-end. For each "Must Have": verify implementation exists. For each "Must NOT Have": search for forbidden patterns.
  Output: `Must Have [N/N] | Must NOT Have [N/N] | VERDICT: APPROVE/REJECT`

- [ ] F2. **Code Quality Review** — `unspecified-high`
  Code review: check for dead code, missing types, unused imports, type safety issues.
  Note: Build + test compilation由用户自行验证。
  Output: `Code Review [CLEAN/N issues] | VERDICT`

- [ ] F3. **Real Manual QA** — `unspecified-high`
  adb install + TV screen verification of navigation flow, search, platform switching, playlist drill-down.
  Output: `Scenarios [N/N pass] | VERDICT`

- [ ] F4. **Scope Fidelity Check** — `deep`
  Verify every task's output matches spec. Check for scope creep.
  Output: `Tasks [N/N compliant] | Contamination [CLEAN/N issues] | VERDICT`

---

## Commit Strategy

- **1**: `feat(network): add Playlist data model and CoverCarousel autoCycle param`
- **2-3**: squash into 1 commit: `feat(network): add getPlaylist() to NetworkMusicService + MetingApiService + Manager`
- **4**: `feat(network): add Screen.Network and Screen.NetworkPlaylistDetail + AppRoot navigation`
- **5**: `feat(network): add playlist state and loading methods to MainViewModel`
- **6**: `feat(network): create NetworkScreen with recommendations, search, platform switching`
- **7**: `feat(network): create NetworkPlaylistDetailScreen`
- **8**: `refactor(library): remove NETWORK tab from LibraryScreen`
- **9**: `chore(strings): add network-related string resources`
- **10**: squash into 6
- **11**: `chore(network): code review and version bump documentation`

---

## Success Criteria

### Verification
- 代码审查通过（无死引用、无缺失 import）
- 用户自行编译 `./gradlew.bat test` → BUILD SUCCESSFUL
- 用户自行编译 `./gradlew.bat assembleDebug` → BUILD SUCCESSFUL
- 开发完成后更新版本号和相关文档

### Final Checklist
- [ ] NavItem 6 项显示正常
- [ ] NetworkScreen 初始化自动加载推荐内容
- [ ] 搜索 + 平台切换正常工作
- [ ] 歌单卡片 → 详情页 → BACK 流程完整
- [ ] LibraryScreen 不再包含网络 Tab
- [ ] 用户编译通过，无回归
- [ ] 版本号已升级，文档已更新
